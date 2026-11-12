package com.pharmatrack.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the production MySQL JDBC URL when {@code prod} is active using either {@code DB_URL}
 * or {@code DB_HOST} + {@code DB_PORT} + {@code DB_NAME} + {@code DB_SSL_MODE}, without ever
 * falling back to localhost defaults or failing eagerly during Spring placeholder parsing when
 * {@code DB_URL} is supplied without {@code DB_HOST}/{@code DB_NAME}.
 */
public class ProductionEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String RESOLVED_DB_URL_PROPERTY = "prod.resolved.datasource.url";
    static final String CONFIG_ERROR_PROPERTY = "prod.configuration.error";
    private static final String PROPERTY_SOURCE_NAME = "pharmatrackProdResolvedDatasource";

    private static final Set<String> ALLOWED_PROD_SSL_MODES = Set.of(
            "REQUIRED",
            "VERIFY_CA",
            "VERIFY_IDENTITY",
            "PREFERRED"
    );

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }

        MutablePropertySources propertySources = environment.getPropertySources();
        propertySources.remove(PROPERTY_SOURCE_NAME);

        Map<String, Object> computed = new HashMap<>();

        String dbUrl = trimToNull(environment.getProperty("DB_URL"));
        if (dbUrl != null) {
            computed.put(RESOLVED_DB_URL_PROPERTY, ensureUtcServerTimezone(dbUrl));
            insertBeforeProdConfig(propertySources, new MapPropertySource(PROPERTY_SOURCE_NAME, computed));
            return;
        }

        String dbHost = trimToNull(environment.getProperty("DB_HOST"));
        String dbName = trimToNull(environment.getProperty("DB_NAME"));
        if (dbHost == null || dbName == null) {
            return;
        }

        String rawPort = trimToNull(environment.getProperty("DB_PORT"));
        int port = 3306;
        if (rawPort != null) {
            try {
                port = Integer.parseInt(rawPort);
                if (port < 1 || port > 65535) {
                    computed.put(CONFIG_ERROR_PROPERTY,
                            "Production configuration error: DB_PORT must be between 1 and 65535 (got '" + rawPort + "').");
                }
            } catch (NumberFormatException ex) {
                computed.put(CONFIG_ERROR_PROPERTY,
                        "Production configuration error: DB_PORT must be a numeric port (got '" + rawPort + "').");
            }
        }

        String rawSslMode = trimToNull(environment.getProperty("DB_SSL_MODE"));
        String sslMode = rawSslMode == null ? "REQUIRED" : rawSslMode.toUpperCase(Locale.ROOT);
        if (!ALLOWED_PROD_SSL_MODES.contains(sslMode)) {
            computed.put(CONFIG_ERROR_PROPERTY,
                    "Production configuration error: DB_SSL_MODE='" + sslMode
                            + "' is not permitted in production. Use REQUIRED, VERIFY_CA, VERIFY_IDENTITY, or PREFERRED.");
        }

        if (!computed.containsKey(CONFIG_ERROR_PROPERTY)) {
            StringBuilder jdbcUrl = new StringBuilder("jdbc:mysql://")
                    .append(dbHost)
                    .append(":")
                    .append(port)
                    .append("/")
                    .append(dbName)
                    .append("?sslMode=").append(sslMode)
                    .append("&serverTimezone=UTC");

            String truststoreUrl = trimToNull(environment.getProperty("DB_SSL_TRUSTSTORE_URL"));
            if (truststoreUrl != null) {
                jdbcUrl.append("&trustCertificateKeyStoreUrl=").append(truststoreUrl);
                String truststorePassword = trimToNull(environment.getProperty("DB_SSL_TRUSTSTORE_PASSWORD"));
                if (truststorePassword != null) {
                    jdbcUrl.append("&trustCertificateKeyStorePassword=").append(truststorePassword);
                }
            }

            String builtUrl = jdbcUrl.toString();
            computed.put(RESOLVED_DB_URL_PROPERTY, builtUrl);

            // If DB_URL="" (empty string) exists in environment while DB_HOST + DB_NAME are configured,
            // supply spring.datasource.url ahead of application-prod.properties (below CLI/system args).
            if (environment.containsProperty("DB_URL") && !hasExplicitCliOrSysPropDatasourceUrl(propertySources)) {
                computed.put("spring.datasource.url", builtUrl);
            }
        }

        if (!computed.isEmpty()) {
            insertBeforeProdConfig(propertySources, new MapPropertySource(PROPERTY_SOURCE_NAME, computed));
        }
    }

    private static boolean hasExplicitCliOrSysPropDatasourceUrl(MutablePropertySources propertySources) {
        for (PropertySource<?> ps : propertySources) {
            String name = ps.getName();
            if ("commandLineArgs".equals(name) || "systemProperties".equals(name)) {
                Object val = ps.getProperty("spring.datasource.url");
                if (val instanceof String s && StringUtils.hasText(s)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void insertBeforeProdConfig(MutablePropertySources propertySources, MapPropertySource source) {
        for (PropertySource<?> existing : propertySources) {
            if (existing.getName().contains("application-prod")) {
                propertySources.addBefore(existing.getName(), source);
                return;
            }
        }
        propertySources.addLast(source);
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String ensureUtcServerTimezone(String jdbcUrl) {
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        if (lower.contains("servertimezone=") || lower.contains("connectiontimezone=")) {
            return jdbcUrl;
        }
        if (jdbcUrl.endsWith("?") || jdbcUrl.endsWith("&")) {
            return jdbcUrl + "serverTimezone=UTC";
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&serverTimezone=UTC" : "?serverTimezone=UTC");
    }
}

