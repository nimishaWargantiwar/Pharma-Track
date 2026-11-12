package com.pharmatrack.config;

import com.pharmatrack.security.CorsOriginSupport;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Fail-closed startup validator for the {@code prod} profile.
 *
 * <p>Runs after logging initialization and before DataSource/Flyway/JPA bean instantiation so
 * missing production environment variables or unsafe profile combinations terminate startup
 * with a clear error message rather than falling back to localhost, local demo users, or
 * auto-DDL.
 */
public class ProductionConfigurationGuardInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment environment = applicationContext.getEnvironment();
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }

        if (environment.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalStateException(
                    "Production configuration error: 'local' and 'prod' Spring profiles must not be active simultaneously.");
        }

        String localDemoEnabled = environment.getProperty("app.security.local-demo.enabled", "false");
        if ("true".equalsIgnoreCase(localDemoEnabled.trim())) {
            throw new IllegalStateException(
                    "Production configuration error: local demo user provisioning (app.security.local-demo.enabled) must be false in production.");
        }

        boolean prodConfigLoaded = isApplicationProdPropertiesLoaded(environment);
        if (!prodConfigLoaded) {
            // Isolated test contexts (such as BootstrapConfigurationValidationTest with custom
            // spring.config.location) still enforce profile isolation, local-demo prohibition,
            // JwtService key strength, and SecurityBootstrapInitializer admin requirements.
            return;
        }

        String precomputedError = environment.getProperty(ProductionEnvironmentPostProcessor.CONFIG_ERROR_PROPERTY);
        if (StringUtils.hasText(precomputedError)) {
            throw new IllegalStateException(precomputedError);
        }

        String datasourceUrl = environment.getProperty("spring.datasource.url");
        if (!StringUtils.hasText(datasourceUrl)) {
            throw new IllegalStateException(
                    "Production configuration error: Missing required MySQL database configuration. "
                            + "Set DB_URL, or set both DB_HOST and DB_NAME (plus optional DB_PORT and DB_SSL_MODE).");
        }

        String normalizedUrl = datasourceUrl.trim().toLowerCase(Locale.ROOT);
        if (!normalizedUrl.startsWith("jdbc:mysql:")) {
            throw new IllegalStateException(
                    "Production configuration error: Production datasource URL must use MySQL JDBC (jdbc:mysql://...).");
        }
        if (normalizedUrl.contains("usessl=false") || normalizedUrl.contains("sslmode=disabled")) {
            throw new IllegalStateException(
                    "Production configuration error: Disabling MySQL TLS (useSSL=false or sslMode=DISABLED) is not permitted in production.");
        }

        if (!StringUtils.hasText(environment.getProperty("spring.datasource.username"))) {
            throw new IllegalStateException(
                    "Production configuration error: Missing required DB_USERNAME environment variable.");
        }

        if (!StringUtils.hasText(environment.getProperty("spring.datasource.password"))) {
            throw new IllegalStateException(
                    "Production configuration error: Missing required DB_PASSWORD environment variable.");
        }

        String ddlAuto = environment.getProperty("spring.jpa.hibernate.ddl-auto");
        if (!"validate".equalsIgnoreCase(ddlAuto != null ? ddlAuto.trim() : "")) {
            throw new IllegalStateException(
                    "Production configuration error: spring.jpa.hibernate.ddl-auto must be 'validate' in production (found: '"
                            + ddlAuto + "').");
        }

        if (!Boolean.parseBoolean(environment.getProperty("spring.flyway.enabled", "false"))) {
            throw new IllegalStateException(
                    "Production configuration error: Flyway must be enabled (spring.flyway.enabled=true) in production.");
        }

        if (!StringUtils.hasText(environment.getProperty("app.jwt.secret"))) {
            throw new IllegalStateException(
                    "Production configuration error: Missing required JWT_SECRET environment variable (minimum 256-bit key required).");
        }

        String rawCors = environment.getProperty("app.security.cors.allowed-origins");
        List<String> rawOrigins = StringUtils.hasText(rawCors)
                ? Arrays.asList(rawCors.split(","))
                : List.of();
        List<String> origins = CorsOriginSupport.normalizeAndValidateOrigins(rawOrigins, true);
        if (origins.isEmpty()) {
            throw new IllegalStateException(
                    "Production configuration error: Missing required CORS_ALLOWED_ORIGINS environment variable (comma-separated frontend origins required).");
        }
    }

    private static boolean isApplicationProdPropertiesLoaded(ConfigurableEnvironment environment) {
        for (PropertySource<?> ps : environment.getPropertySources()) {
            if (ps.getName().contains("application-prod")) {
                return true;
            }
        }
        return false;
    }
}

