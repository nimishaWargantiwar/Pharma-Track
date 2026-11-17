package com.pharmatrack;

import com.pharmatrack.config.ProductionConfigurationGuardInitializer;
import com.pharmatrack.security.CorsOriginSupport;
import com.pharmatrack.security.SecurityConfig;
import com.pharmatrack.security.SecurityConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code prod} profile fail-closed behavior, environment variable resolution,
 * managed-MySQL SSL defaults, Flyway / Hibernate validation properties, and isolation
 * from {@code local} profile settings.
 */
class ProductionProfileConfigurationTest {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String MAIN_AND_PROD_CONFIG =
            "file:src/main/resources/application.properties,file:src/main/resources/application-prod.properties";

    private static String randomJwtSecret() {
        byte[] bytes = new byte[36];
        SECURE_RANDOM.nextBytes(bytes);
        return "base64:" + Base64.getEncoder().encodeToString(bytes);
    }

    private static String[] buildArgs(String profiles, Map<String, String> overrides) {
        List<String> args = new ArrayList<>();
        args.add("--spring.config.location=" + MAIN_AND_PROD_CONFIG);
        args.add("--spring.profiles.active=" + profiles);
        overrides.forEach((k, v) -> args.add("--" + k + "=" + v));
        return args.toArray(String[]::new);
    }

    private static Map<String, String> validProdBaseVars() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("PORT", "10000");
        vars.put("DB_HOST", "managed-mysql.example.internal");
        vars.put("DB_PORT", "25060");
        vars.put("DB_NAME", "pharmatrack_prod");
        vars.put("DB_USERNAME", "pt_prod_user");
        vars.put("DB_PASSWORD", "ProdTestPwd#98765");
        vars.put("JWT_SECRET", randomJwtSecret());
        vars.put("JWT_EXPIRATION_MS", "1800000");
        vars.put("CORS_ALLOWED_ORIGINS", "https://pharmatrack.vercel.app,https://staging.vercel.app");
        vars.put("BOOTSTRAP_ADMIN_USERNAME", "prod_admin");
        vars.put("BOOTSTRAP_ADMIN_PASSWORD", "BootAdmin#987654");
        return vars;
    }

    private static Exception startExpectingFailure(String profiles, Map<String, String> vars) {
        return assertThrows(Exception.class, () -> {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(PharmaTrackApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(buildArgs(profiles, vars))) {
                fail("Expected startup to fail closed, but context succeeded");
            }
        });
    }

    private static ConfigurableEnvironment prepareAndValidateProdEnvironment(Map<String, String> vars) {
        AtomicReference<ConfigurableEnvironment> captured = new AtomicReference<>();
        SpringApplication app = new SpringApplicationBuilder(PharmaTrackApplication.class)
                .web(WebApplicationType.NONE)
                .listeners((ApplicationListener<ApplicationPreparedEvent>) event -> {
                    captured.set(event.getApplicationContext().getEnvironment());
                    throw new AbortedAfterValidationException();
                })
                .build();

        assertThrows(AbortedAfterValidationException.class, () -> app.run(buildArgs("prod", vars)));
        assertNotNull(captured.get(), "Expected ApplicationPreparedEvent after ProductionConfigurationGuardInitializer");
        return captured.get();
    }

    private static boolean hasMessage(Throwable ex, String fragment) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur.getMessage() != null && cur.getMessage().contains(fragment)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    @Test
    @DisplayName("1. Production fails closed when DB connection vars are missing (never falls back to localhost)")
    void prodFailsClosedWhenDbMissing() {
        Map<String, String> vars = validProdBaseVars();
        vars.remove("DB_HOST");
        vars.remove("DB_NAME");

        Exception ex = startExpectingFailure("prod", vars);
        assertTrue(hasMessage(ex, "Missing required MySQL database configuration"), "Unexpected error: " + ex);
    }

    @Test
    @DisplayName("2. Production fails closed when only DB_HOST is set without DB_NAME")
    void prodFailsClosedWhenDbNameMissing() {
        Map<String, String> vars = validProdBaseVars();
        vars.remove("DB_NAME");

        Exception ex = startExpectingFailure("prod", vars);
        assertTrue(hasMessage(ex, "Missing required MySQL database configuration"), "Unexpected error: " + ex);
    }

    @Test
    @DisplayName("3. Production fails closed when DB_USERNAME is missing (never falls back to pharmatrack_app)")
    void prodFailsClosedWhenDbUsernameMissing() {
        Map<String, String> vars = validProdBaseVars();
        vars.remove("DB_USERNAME");

        Exception ex = startExpectingFailure("prod", vars);
        assertTrue(hasMessage(ex, "DB_USERNAME"), "Unexpected error: " + ex);
    }

    @Test
    @DisplayName("4. Production fails closed when DB_PASSWORD is missing")
    void prodFailsClosedWhenDbPasswordMissing() {
        Map<String, String> vars = validProdBaseVars();
        vars.remove("DB_PASSWORD");

        Exception ex = startExpectingFailure("prod", vars);
        assertTrue(hasMessage(ex, "DB_PASSWORD"), "Unexpected error: " + ex);
    }

    @Test
    @DisplayName("5. Production fails closed when JWT_SECRET is missing")
    void prodFailsClosedWhenJwtSecretMissing() {
        Map<String, String> vars = validProdBaseVars();
        vars.remove("JWT_SECRET");

        Exception ex = startExpectingFailure("prod", vars);
        assertTrue(hasMessage(ex, "JWT_SECRET"), "Unexpected error: " + ex);
    }

    @Test
    @DisplayName("6. Production fails closed when CORS_ALLOWED_ORIGINS is missing or wildcard '*'")
    void prodFailsClosedWhenCorsMissingOrWildcard() {
        Map<String, String> missingCors = validProdBaseVars();
        missingCors.remove("CORS_ALLOWED_ORIGINS");
        Exception exMissing = startExpectingFailure("prod", missingCors);
        assertTrue(hasMessage(exMissing, "CORS_ALLOWED_ORIGINS"), "Unexpected error: " + exMissing);

        Map<String, String> wildcardCors = validProdBaseVars();
        wildcardCors.put("CORS_ALLOWED_ORIGINS", "*");
        Exception exWildcard = startExpectingFailure("prod", wildcardCors);
        assertTrue(hasMessage(exWildcard, "wildcard '*' is not permitted"), "Unexpected error: " + exWildcard);

        Map<String, String> subdomainWildcardCors = validProdBaseVars();
        subdomainWildcardCors.put("CORS_ALLOWED_ORIGINS", "https://*.vercel.app");
        Exception exSubdomainWildcard = startExpectingFailure("prod", subdomainWildcardCors);
        assertTrue(hasMessage(exSubdomainWildcard, "wildcard '*' is not permitted"), "Unexpected error: " + exSubdomainWildcard);

        Map<String, String> pathCors = validProdBaseVars();
        pathCors.put("CORS_ALLOWED_ORIGINS", "https://pharmatrack.vercel.app/login");
        Exception exPath = startExpectingFailure("prod", pathCors);
        assertTrue(hasMessage(exPath, "without path, query, or fragment"), "Unexpected error: " + exPath);
    }

    @Test
    @DisplayName("6b. Production CORS normalizes trailing slashes, deduplicates, and has zero localhost fallback")
    void prodCorsNormalizesTrailingSlashesAndHasZeroLocalhostFallback() {
        Map<String, String> varsWithSlashes = validProdBaseVars();
        varsWithSlashes.put("CORS_ALLOWED_ORIGINS",
                "  https://PharmaTrack.vercel.app/ , https://staging.vercel.app///, https://pharmatrack.vercel.app ");

        ConfigurableEnvironment env = prepareAndValidateProdEnvironment(varsWithSlashes);
        assertNotNull(env);

        List<String> normalized = CorsOriginSupport.normalizeAndValidateOrigins(
                List.of("  https://PharmaTrack.vercel.app/ ", "https://staging.vercel.app///", "https://pharmatrack.vercel.app"),
                true
        );
        assertEquals(List.of("https://pharmatrack.vercel.app", "https://staging.vercel.app"), normalized);

        // Verify SecurityConfig rejects empty CORS under prod without falling back to localhost
        SecurityConfig securityConfig = new SecurityConfig();
        SecurityConfigProperties emptyProps = new SecurityConfigProperties();
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class,
                () -> securityConfig.corsConfigurationSource(emptyProps, prodEnv));

        // Verify configured trailing-slash origin resolves exact match without trailing slash on /api/**
        SecurityConfigProperties trailingSlashProps = new SecurityConfigProperties();
        trailingSlashProps.getCors().setAllowedOrigins(List.of("https://pharmatrack.vercel.app/"));
        CorsConfigurationSource corsSource = securityConfig.corsConfigurationSource(trailingSlashProps, prodEnv);
        CorsConfiguration resolved = corsSource.getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/auth/login"));
        assertNotNull(resolved);
        assertEquals(List.of("https://pharmatrack.vercel.app"), resolved.getAllowedOrigins());
    }

    @Test
    @DisplayName("7. Production fails closed when combined with local profile or forced local demo mode")
    void prodRejectsLocalProfileMixAndLocalDemo() {
        Map<String, String> vars = validProdBaseVars();
        Exception combinedProfilesEx = startExpectingFailure("local,prod", vars);
        assertTrue(hasMessage(combinedProfilesEx, "'local' and 'prod'"), "Unexpected error: " + combinedProfilesEx);

        Map<String, String> forcedDemo = validProdBaseVars();
        forcedDemo.put("app.security.local-demo.enabled", "true");
        Exception demoEx = startExpectingFailure("prod", forcedDemo);
        assertTrue(hasMessage(demoEx, "local-demo.enabled"), "Unexpected error: " + demoEx);
    }

    @Test
    @DisplayName("8. Production rejects disabled MySQL SSL settings")
    void prodRejectsDisabledMySqlSsl() {
        Map<String, String> disabledSslMode = validProdBaseVars();
        disabledSslMode.put("DB_SSL_MODE", "DISABLED");
        Exception ex1 = startExpectingFailure("prod", disabledSslMode);
        assertTrue(hasMessage(ex1, "DB_SSL_MODE"), "Unexpected error: " + ex1);

        Map<String, String> insecureDbUrl = validProdBaseVars();
        insecureDbUrl.remove("DB_HOST");
        insecureDbUrl.remove("DB_NAME");
        insecureDbUrl.put("DB_URL", "jdbc:mysql://db.example.com:3306/pharmatrack_prod?useSSL=false&serverTimezone=UTC");
        Exception ex2 = startExpectingFailure("prod", insecureDbUrl);
        assertTrue(hasMessage(ex2, "Disabling MySQL TLS"), "Unexpected error: " + ex2);
    }

    @Test
    @DisplayName("9. Valid host/name production config resolves managed MySQL SSL URL, Flyway, validate DDL, Hikari, PORT, and disables local demo")
    void validProdHostAndNameConfigurationResolvesCleanly() {
        Map<String, String> vars = validProdBaseVars();
        // Attempting to turn on local demo via env var must have NO effect in application-prod.properties
        vars.put("LOCAL_DEMO_USERS_ENABLED", "true");
        vars.put("PHARMATRACK_DEMO_PASSWORD", "IgnoredDemoPass9");

        ConfigurableEnvironment env = prepareAndValidateProdEnvironment(vars);

        assertEquals("10000", env.getProperty("server.port"));
        assertEquals(
                "jdbc:mysql://managed-mysql.example.internal:25060/pharmatrack_prod?sslMode=REQUIRED&serverTimezone=UTC",
                env.getProperty("spring.datasource.url"));
        assertEquals("pt_prod_user", env.getProperty("spring.datasource.username"));
        assertEquals("ProdTestPwd#98765", env.getProperty("spring.datasource.password"));
        assertEquals("com.mysql.cj.jdbc.Driver", env.getProperty("spring.datasource.driver-class-name"));

        assertEquals("validate", env.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("UTC", env.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone"));
        assertEquals("true", env.getProperty("spring.jpa.properties.hibernate.type.java_time_use_direct_jdbc"));
        assertEquals("false", env.getProperty("spring.jpa.show-sql"));
        assertEquals("WARN", env.getProperty("logging.level.org.hibernate.SQL"));
        assertEquals("OFF", env.getProperty("logging.level.org.hibernate.orm.jdbc.bind"));

        assertEquals("true", env.getProperty("spring.flyway.enabled"));
        assertEquals("classpath:db/migration", env.getProperty("spring.flyway.locations"));
        assertEquals("false", env.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("1", env.getProperty("spring.flyway.baseline-version"));
        assertEquals("true", env.getProperty("spring.flyway.validate-on-migrate"));

        assertEquals("PharmaTrackProdPool", env.getProperty("spring.datasource.hikari.pool-name"));
        assertEquals("10", env.getProperty("spring.datasource.hikari.maximum-pool-size"));
        assertEquals("2", env.getProperty("spring.datasource.hikari.minimum-idle"));

        assertEquals("1800000", env.getProperty("app.jwt.expiration-ms"));
        assertEquals("https://pharmatrack.vercel.app,https://staging.vercel.app",
                env.getProperty("app.security.cors.allowed-origins"));
        assertEquals("false", env.getProperty("app.security.local-demo.enabled"));
        assertEquals("", env.getProperty("app.security.local-demo.password"));
    }

    @Test
    @DisplayName("10. Valid DB_URL production config works without requiring DB_HOST or DB_NAME, and empty DB_URL falls back to DB_HOST+DB_NAME")
    void validProdDbUrlWorksIndependentlyOfHostAndName() {
        Map<String, String> withDbUrlOnly = validProdBaseVars();
        withDbUrlOnly.remove("DB_HOST");
        withDbUrlOnly.remove("DB_PORT");
        withDbUrlOnly.remove("DB_NAME");
        withDbUrlOnly.put("DB_URL", "jdbc:mysql://rds.example.com:3306/pharmatrack_rds?sslMode=VERIFY_CA&serverTimezone=UTC");

        ConfigurableEnvironment env1 = prepareAndValidateProdEnvironment(withDbUrlOnly);
        assertEquals(
                "jdbc:mysql://rds.example.com:3306/pharmatrack_rds?sslMode=VERIFY_CA&serverTimezone=UTC",
                env1.getProperty("spring.datasource.url"));

        Map<String, String> withEmptyDbUrlAndHost = validProdBaseVars();
        withEmptyDbUrlAndHost.put("DB_URL", "");
        ConfigurableEnvironment env2 = prepareAndValidateProdEnvironment(withEmptyDbUrlAndHost);
        assertEquals(
                "jdbc:mysql://managed-mysql.example.internal:25060/pharmatrack_prod?sslMode=REQUIRED&serverTimezone=UTC",
                env2.getProperty("spring.datasource.url"));

        Map<String, String> dbUrlWithoutTimezone = validProdBaseVars();
        dbUrlWithoutTimezone.remove("DB_HOST");
        dbUrlWithoutTimezone.remove("DB_PORT");
        dbUrlWithoutTimezone.remove("DB_NAME");
        dbUrlWithoutTimezone.put("DB_URL", "jdbc:mysql://rds.example.com:3306/pharmatrack_rds?sslMode=REQUIRED");
        ConfigurableEnvironment env3 = prepareAndValidateProdEnvironment(dbUrlWithoutTimezone);
        assertEquals(
                "jdbc:mysql://rds.example.com:3306/pharmatrack_rds?sslMode=REQUIRED&serverTimezone=UTC",
                env3.getProperty("spring.datasource.url"));

        Map<String, String> bareDbUrlWithoutTimezone = validProdBaseVars();
        bareDbUrlWithoutTimezone.remove("DB_HOST");
        bareDbUrlWithoutTimezone.remove("DB_PORT");
        bareDbUrlWithoutTimezone.remove("DB_NAME");
        bareDbUrlWithoutTimezone.put("DB_URL", "jdbc:mysql://rds.example.com:3306/pharmatrack_rds");
        ConfigurableEnvironment env4 = prepareAndValidateProdEnvironment(bareDbUrlWithoutTimezone);
        assertEquals(
                "jdbc:mysql://rds.example.com:3306/pharmatrack_rds?serverTimezone=UTC",
                env4.getProperty("spring.datasource.url"));

        Map<String, String> dbUrlWithConnectionTimezone = validProdBaseVars();
        dbUrlWithConnectionTimezone.remove("DB_HOST");
        dbUrlWithConnectionTimezone.remove("DB_PORT");
        dbUrlWithConnectionTimezone.remove("DB_NAME");
        dbUrlWithConnectionTimezone.put("DB_URL",
                "jdbc:mysql://rds.example.com:3306/pharmatrack_rds?sslMode=REQUIRED&connectionTimeZone=UTC");
        ConfigurableEnvironment env5 = prepareAndValidateProdEnvironment(dbUrlWithConnectionTimezone);
        assertEquals(
                "jdbc:mysql://rds.example.com:3306/pharmatrack_rds?sslMode=REQUIRED&connectionTimeZone=UTC",
                env5.getProperty("spring.datasource.url"));
    }

    @Test
    @DisplayName("11. Default local profile resolution loads application-local.properties cleanly without prod guard interference")
    void localProfileRemainsIndependentFromProd() {
        AtomicReference<ConfigurableEnvironment> captured = new AtomicReference<>();
        SpringApplication app = new SpringApplicationBuilder(PharmaTrackApplication.class)
                .web(WebApplicationType.NONE)
                .listeners((ApplicationListener<ApplicationPreparedEvent>) event -> {
                    captured.set(event.getApplicationContext().getEnvironment());
                    throw new AbortedAfterValidationException();
                })
                .build();

        // Mirrors local execution inside backend/: default active profile resolves to local
        // and external config/application-local.properties loads cleanly (secrets not asserted/logged).
        String[] localArgs = new String[]{
                "--spring.config.location=file:src/main/resources/application.properties,optional:file:config/"
        };
        assertThrows(AbortedAfterValidationException.class, () -> app.run(localArgs));
        ConfigurableEnvironment localEnv = captured.get();
        assertNotNull(localEnv);
        assertTrue(List.of(localEnv.getActiveProfiles()).contains("local"));
        assertFalse(List.of(localEnv.getActiveProfiles()).contains("prod"));
        assertEquals("validate", localEnv.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("true", localEnv.getProperty("spring.flyway.enabled"));
        assertEquals("false", localEnv.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("true", localEnv.getProperty("spring.flyway.validate-on-migrate"));
        assertEquals("UTC", localEnv.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone"));
        assertEquals("true", localEnv.getProperty("spring.jpa.properties.hibernate.type.java_time_use_direct_jdbc"));
        assertEquals("pharmatrack_app", localEnv.getProperty("spring.datasource.username"));
        assertTrue(localEnv.getProperty("spring.datasource.url", "").contains("/pharmatrack_db"));
        assertEquals("true", localEnv.getProperty("app.ai.enabled"));
        assertEquals("openai", localEnv.getProperty("app.ai.provider"));
        assertEquals("https://api.groq.com/openai/v1", localEnv.getProperty("app.ai.base-url"));
        assertEquals("openai/gpt-oss-20b", localEnv.getProperty("app.ai.model"));
        assertNotNull(localEnv.getProperty("app.ai.api-key"));
        assertFalse(localEnv.getProperty("app.ai.api-key").isBlank());
    }

    @Test
    @DisplayName("12. External config/application-local.properties loads under local profile and is completely ignored under prod profile")
    void externalLocalConfigAppliesOnlyToLocalAndNeverLeaksIntoProd(@TempDir Path tempConfigDir) throws Exception {
        Path localFile = tempConfigDir.resolve("application-local.properties");
        Files.writeString(localFile, """
                spring.datasource.url=jdbc:mysql://localhost:3306/pharmatrack_db?useSSL=false&serverTimezone=UTC
                spring.datasource.username=local_custom_user
                spring.datasource.password=LocalCustomPass99
                app.jwt.secret=LocalOnlySecretThatIsLongEnoughFor256BitsVerification123456
                app.security.local-demo.enabled=true
                app.security.local-demo.password=LocalDemoPass99
                spring.flyway.enabled=true
                spring.flyway.baseline-on-migrate=false
                spring.flyway.validate-on-migrate=true
                """);

        String configLocations = "file:src/main/resources/,optional:file:" + tempConfigDir.toAbsolutePath() + "/";

        // Under default/local profile, external application-local.properties IS applied
        AtomicReference<ConfigurableEnvironment> localCaptured = new AtomicReference<>();
        SpringApplication localApp = new SpringApplicationBuilder(PharmaTrackApplication.class)
                .web(WebApplicationType.NONE)
                .listeners((ApplicationListener<ApplicationPreparedEvent>) event -> {
                    localCaptured.set(event.getApplicationContext().getEnvironment());
                    throw new AbortedAfterValidationException();
                })
                .build();
        assertThrows(AbortedAfterValidationException.class, () ->
                localApp.run("--spring.config.location=" + configLocations));
        ConfigurableEnvironment localEnv = localCaptured.get();
        assertNotNull(localEnv);
        assertEquals("local_custom_user", localEnv.getProperty("spring.datasource.username"));
        assertEquals("true", localEnv.getProperty("app.security.local-demo.enabled"));
        assertEquals("validate", localEnv.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("true", localEnv.getProperty("spring.flyway.enabled"));
        assertEquals("false", localEnv.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("true", localEnv.getProperty("spring.flyway.validate-on-migrate"));

        // Under prod profile, external application-local.properties MUST NOT load and missing prod env vars fail closed
        Exception prodWithoutVars = assertThrows(Exception.class, () -> {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(PharmaTrackApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.config.location=" + configLocations, "--spring.profiles.active=prod")) {
                fail("Expected prod profile to ignore application-local.properties and fail closed");
            }
        });
        assertTrue(hasMessage(prodWithoutVars, "Missing required MySQL database configuration"),
                "Unexpected error: " + prodWithoutVars);
    }

    @Test
    @DisplayName("13. Test profile retains isolated H2 in-memory datasource, create-drop DDL, and disabled Flyway")
    void testProfileRetainsIsolatedDatabaseBehavior() {
        AtomicReference<ConfigurableEnvironment> captured = new AtomicReference<>();
        SpringApplication app = new SpringApplicationBuilder(PharmaTrackApplication.class)
                .web(WebApplicationType.NONE)
                .listeners((ApplicationListener<ApplicationPreparedEvent>) event -> {
                    captured.set(event.getApplicationContext().getEnvironment());
                    throw new AbortedAfterValidationException();
                })
                .build();

        String[] testArgs = new String[]{
                "--spring.config.location=file:src/main/resources/application.properties,file:src/test/resources/application.properties"
        };
        assertThrows(AbortedAfterValidationException.class, () -> app.run(testArgs));

        ConfigurableEnvironment testEnv = captured.get();
        assertNotNull(testEnv);
        assertTrue(List.of(testEnv.getActiveProfiles()).contains("test"));
        assertFalse(List.of(testEnv.getActiveProfiles()).contains("prod"));
        assertTrue(testEnv.getProperty("spring.datasource.url", "").startsWith("jdbc:h2:mem:pharmatrack_test"));
        assertEquals("org.h2.Driver", testEnv.getProperty("spring.datasource.driver-class-name"));
        assertEquals("create-drop", testEnv.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("false", testEnv.getProperty("spring.flyway.enabled"));
        assertEquals("false", testEnv.getProperty("app.security.bootstrap.enabled"));
        assertEquals("false", testEnv.getProperty("app.security.local-demo.enabled"));
        assertEquals("false", testEnv.getProperty("app.ai.enabled"));
    }

    @Test
    @DisplayName("14. Default local startup automatically imports AI copilot settings from config/application-local.properties without shell exports")
    void localStartupAutomaticallyImportsAiConfigurationWithoutShellExports() {
        AtomicReference<ConfigurableEnvironment> captured = new AtomicReference<>();
        SpringApplication app = new SpringApplicationBuilder(PharmaTrackApplication.class)
                .web(WebApplicationType.NONE)
                .listeners((ApplicationListener<ApplicationPreparedEvent>) event -> {
                    captured.set(event.getApplicationContext().getEnvironment());
                    throw new AbortedAfterValidationException();
                })
                .build();

        // Mirrors local startup: loading main application.properties which triggers spring.config.import
        String[] args = new String[]{
                "--spring.config.location=file:src/main/resources/application.properties"
        };
        assertThrows(AbortedAfterValidationException.class, () -> app.run(args));

        ConfigurableEnvironment localEnv = captured.get();
        assertNotNull(localEnv);
        assertTrue(List.of(localEnv.getActiveProfiles()).contains("local"));
        assertFalse(List.of(localEnv.getActiveProfiles()).contains("prod"));

        // Imported from config/application-local.properties
        assertEquals("true", localEnv.getProperty("app.ai.enabled"));
        assertEquals("openai", localEnv.getProperty("app.ai.provider"));
        assertEquals("https://api.groq.com/openai/v1", localEnv.getProperty("app.ai.base-url"));
        assertEquals("openai/gpt-oss-20b", localEnv.getProperty("app.ai.model"));
        assertNotNull(localEnv.getProperty("app.ai.api-key"));
        assertFalse(localEnv.getProperty("app.ai.api-key").isBlank());
    }

    @Test
    @DisplayName("15. Environment variable / System property overrides take precedence over imported application-local.properties")
    void propertyOverridesTakePrecedenceOverImportedLocalConfig() {
        AtomicReference<ConfigurableEnvironment> captured = new AtomicReference<>();
        SpringApplication app = new SpringApplicationBuilder(PharmaTrackApplication.class)
                .web(WebApplicationType.NONE)
                .listeners((ApplicationListener<ApplicationPreparedEvent>) event -> {
                    captured.set(event.getApplicationContext().getEnvironment());
                    throw new AbortedAfterValidationException();
                })
                .build();

        // Passing CLI / System override for model and enabled flag
        String[] args = new String[]{
                "--spring.config.location=file:src/main/resources/application.properties",
                "--app.ai.model=custom-cli-model",
                "--app.ai.enabled=false"
        };
        assertThrows(AbortedAfterValidationException.class, () -> app.run(args));

        ConfigurableEnvironment localEnv = captured.get();
        assertNotNull(localEnv);
        assertEquals("false", localEnv.getProperty("app.ai.enabled"));
        assertEquals("custom-cli-model", localEnv.getProperty("app.ai.model"));
    }

    @Test
    @DisplayName("16. Missing local config file gracefully resolves with optional import without failing startup")
    void missingLocalConfigFileGracefullyResolvesWithOptionalImport(@TempDir Path tempEmptyDir) throws Exception {
        AtomicReference<ConfigurableEnvironment> captured = new AtomicReference<>();
        SpringApplication app = new SpringApplicationBuilder(PharmaTrackApplication.class)
                .web(WebApplicationType.NONE)
                .listeners((ApplicationListener<ApplicationPreparedEvent>) event -> {
                    captured.set(event.getApplicationContext().getEnvironment());
                    throw new AbortedAfterValidationException();
                })
                .build();

        // Synthetic properties file with optional import to non-existent file
        Path syntheticProps = tempEmptyDir.resolve("application.properties");
        Files.writeString(syntheticProps, """
                spring.application.name=test-app
                spring.profiles.active=local
                spring.config.import=optional:file:""" + tempEmptyDir.resolve("nonexistent.properties").toAbsolutePath() + "\n" +
                """
                app.ai.enabled=${APP_AI_ENABLED:false}
                app.ai.model=default-model
                """);

        String[] args = new String[]{
                "--spring.config.location=file:" + syntheticProps.toAbsolutePath()
        };
        assertThrows(AbortedAfterValidationException.class, () -> app.run(args));

        ConfigurableEnvironment localEnv = captured.get();
        assertNotNull(localEnv);
        assertEquals("false", localEnv.getProperty("app.ai.enabled"));
        assertEquals("default-model", localEnv.getProperty("app.ai.model"));
    }

    private static final class AbortedAfterValidationException extends RuntimeException {
    }
}

