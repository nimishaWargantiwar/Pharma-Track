package com.pharmatrack.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.sql.Connection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed security guard enforcing that permanent physical deletion of AI Batch
 * Analysis Reports is permitted exclusively in verified local development and test environments.
 *
 * <p>Enforces multi-tier defense in depth:
 * <ol>
 *   <li>Rejects production profile ({@code prod}) immediately.</li>
 *   <li>Requires an explicit active {@code local} or {@code test} Spring profile.</li>
 *   <li>Requires explicit feature flag {@code app.ai.batch-analysis.permanent-delete.enabled=true} (default: false).</li>
 *   <li>Inspects the actual active JDBC connection from the {@link DataSource}.</li>
 *   <li>Verifies that the database host is strictly a loopback address or an approved local H2 test instance.</li>
 *   <li>Rejects TiDB, cloud endpoints, and unknown database identities.</li>
 *   <li>Fails closed if any check is uncertain or throws an exception. Never relies on database name alone.</li>
 * </ol>
 */
@Component
public class LocalEnvironmentDeletionGuard {

    private static final Logger log = LoggerFactory.getLogger(LocalEnvironmentDeletionGuard.class);

    public static final String FEATURE_FLAG_PROPERTY = "app.ai.batch-analysis.permanent-delete.enabled";

    // Regex pattern extracting host and port from standard JDBC URLs: jdbc:<subprotocol>://<host>[:<port>]/...
    private static final Pattern JDBC_HOST_PATTERN = Pattern.compile(
            "^jdbc:[a-zA-Z0-9_]+://([^/?#:]+)(?::(\\d+))?(?:/.*)?$",
            Pattern.CASE_INSENSITIVE
    );

    private final Environment environment;
    private final DataSource dataSource;

    @Autowired
    public LocalEnvironmentDeletionGuard(Environment environment, DataSource dataSource) {
        this.environment = environment;
        this.dataSource = dataSource;
    }

    /**
     * Executes all verification tiers and fails closed if any check fails or is uncertain.
     *
     * @throws AccessDeniedException if environment or permissions forbid deletion
     * @throws IllegalStateException if configuration, DataSource, or JDBC identity is invalid or uncertain
     */
    public void verifyPermanentDeletionAllowed() {
        // ---------------------------------------------------------------------
        // Tier 1: Spring Profiles Isolation
        // ---------------------------------------------------------------------
        if (environment == null) {
            log.error("Permanent deletion rejected: Spring Environment is null.");
            throw new IllegalStateException("Permanent delete rejected: Environment is unavailable for safety validation.");
        }

        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            log.error("SECURITY ALERT: Permanent delete attempted under active 'prod' profile!");
            throw new AccessDeniedException("Permanent deletion of AI Batch Analysis reports is strictly prohibited in production environments.");
        }

        boolean hasLocalOrTestProfile = environment.acceptsProfiles(Profiles.of("local"))
                || environment.acceptsProfiles(Profiles.of("test"));
        if (!hasLocalOrTestProfile) {
            log.error("Permanent deletion rejected: active profiles do not include 'local' or 'test'.");
            throw new AccessDeniedException("Permanent deletion requires an active 'local' or 'test' Spring profile.");
        }

        // ---------------------------------------------------------------------
        // Tier 2: Explicit Feature Flag (Default False)
        // ---------------------------------------------------------------------
        Boolean flagEnabled = environment.getProperty(FEATURE_FLAG_PROPERTY, Boolean.class, false);
        if (!Boolean.TRUE.equals(flagEnabled)) {
            log.warn("Permanent deletion rejected: feature flag '{}' is false or missing.", FEATURE_FLAG_PROPERTY);
            throw new AccessDeniedException("Permanent deletion of AI Batch Analysis reports is disabled by configuration (" + FEATURE_FLAG_PROPERTY + "=false).");
        }

        // ---------------------------------------------------------------------
        // Tier 3: Actual Active JDBC Connection Verification
        // ---------------------------------------------------------------------
        if (dataSource == null) {
            log.error("Permanent deletion rejected: DataSource is null.");
            throw new IllegalStateException("Permanent delete rejected: DataSource is unavailable for database identity verification.");
        }

        String rawJdbcUrl;
        try (Connection conn = dataSource.getConnection()) {
            if (conn == null || conn.getMetaData() == null) {
                log.error("Permanent deletion rejected: Connection or metadata is null.");
                throw new IllegalStateException("Permanent delete rejected: unable to establish connection metadata.");
            }
            rawJdbcUrl = conn.getMetaData().getURL();
        } catch (Exception ex) {
            log.error("Permanent deletion rejected: exception while inspecting database connection metadata: {}", ex.getMessage());
            throw new IllegalStateException("Permanent delete rejected: failed to verify database connection: " + ex.getMessage(), ex);
        }

        if (rawJdbcUrl == null || rawJdbcUrl.trim().isEmpty()) {
            log.error("Permanent deletion rejected: active JDBC connection URL is null or empty.");
            throw new IllegalStateException("Permanent delete rejected: JDBC connection URL cannot be determined.");
        }

        String normalizedUrl = rawJdbcUrl.trim().toLowerCase(Locale.ROOT);

        // Fail closed immediately on known cloud/production database markers
        if (normalizedUrl.contains("tidbcloud") || normalizedUrl.contains("gateway01")
                || normalizedUrl.contains("amazonaws") || normalizedUrl.contains("onrender")
                || normalizedUrl.contains(".prod.") || normalizedUrl.contains("-prod.")
                || normalizedUrl.contains("azure") || normalizedUrl.contains("google")) {
            log.error("SECURITY ALERT: Permanent delete rejected against cloud/production database endpoint: {}", rawJdbcUrl);
            throw new AccessDeniedException("Permanent delete rejected: active database connection is a cloud or production endpoint.");
        }

        // Approved local test connections: H2 in-memory or file databases
        boolean isApprovedH2 = normalizedUrl.startsWith("jdbc:h2:mem:")
                || normalizedUrl.startsWith("jdbc:h2:file:")
                || normalizedUrl.startsWith("jdbc:h2:tcp://localhost")
                || normalizedUrl.startsWith("jdbc:h2:tcp://127.0.0.1");

        if (isApprovedH2) {
            log.debug("Permanent delete safety check passed: approved local H2 database verified ({})", rawJdbcUrl);
            return;
        }

        // For networked relational databases (e.g. MySQL): extract and verify loopback host strictly
        Matcher matcher = JDBC_HOST_PATTERN.matcher(normalizedUrl);
        if (!matcher.find()) {
            log.error("Permanent deletion rejected: unrecognized JDBC URL format '{}'", rawJdbcUrl);
            throw new IllegalStateException("Permanent delete rejected: unrecognized JDBC URL format.");
        }

        String host = matcher.group(1).trim();
        if (!isVerifiedLoopbackHost(host)) {
            log.error("SECURITY ALERT: Permanent delete rejected: database host '{}' is not a verified loopback address.", host);
            throw new AccessDeniedException("Permanent delete rejected: database host '" + host + "' is not a verified loopback host.");
        }

        log.debug("Permanent delete safety check passed: verified loopback database host '{}' ({})", host, rawJdbcUrl);
    }

    /**
     * Determines whether a host string definitively resolves to a loopback address.
     */
    private boolean isVerifiedLoopbackHost(String host) {
        if ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host)) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr != null && addr.isLoopbackAddress();
        } catch (Exception ex) {
            log.warn("DNS resolution failed for host '{}': {}", host, ex.getMessage());
            return false;
        }
    }
}
