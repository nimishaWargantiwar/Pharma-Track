package com.pharmatrack;

import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.UserAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Startup & bootstrap configuration validation tests verifying fail-closed behavior for
 * weak/missing JWT secrets and missing non-local admin bootstrap credentials, as well as
 * idempotent non-overwriting behavior and zero-default-password local demo seeding.
 *
 * <p>Uses explicit command-line overrides with {@code spring.config.location} pointed away
 * from local files so isolated in-memory H2 databases are always used and MySQL is never touched.
 */
class BootstrapConfigurationValidationTest {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static String randomJwtSecret(int len) {
        byte[] bytes = new byte[len];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, len);
    }

    private static String ephemeralPassword() {
        return "BootSec9" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> expectedType, String messageFragment) {
        Throwable current = throwable;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                if (messageFragment == null || (current.getMessage() != null && current.getMessage().contains(messageFragment))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static ConfigurableApplicationContext startAppContext(String dbName,
                                                                    String profiles,
                                                                    String ddlAuto,
                                                                    Map<String, String> extraProps) {
        List<String> args = new ArrayList<>();
        args.add("--spring.config.location=optional:classpath:/isolated-bootstrap-none.properties");
        args.add("--server.port=0");
        args.add("--spring.profiles.active=" + profiles);
        args.add("--spring.datasource.url=jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
        args.add("--spring.datasource.driver-class-name=org.h2.Driver");
        args.add("--spring.datasource.username=sa");
        args.add("--spring.datasource.password=");
        args.add("--spring.jpa.hibernate.ddl-auto=" + ddlAuto);
        args.add("--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect");
        args.add("--spring.jpa.open-in-view=false");
        args.add("--spring.jpa.show-sql=false");
        args.add("--spring.flyway.enabled=false");
        extraProps.forEach((k, v) -> args.add("--" + k + "=" + v));

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(PharmaTrackApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(args.toArray(String[]::new));

        String resolvedUrl = ctx.getEnvironment().getProperty("spring.datasource.url", "");
        assertTrue(resolvedUrl.startsWith("jdbc:h2:mem:"), "Test context must remain isolated on H2, got: " + resolvedUrl);
        return ctx;
    }

    @Test
    @DisplayName("1. Startup fails closed when JWT signing secret is blank or shorter than 256 bits")
    void startupFailsClosedWhenJwtSecretMissingOrWeak() {
        String db1 = "boot_jwt_missing_" + UUID.randomUUID().toString().replace("-", "");
        Exception missingEx = assertThrows(Exception.class, () -> {
            try (ConfigurableApplicationContext ignored = startAppContext(
                    db1,
                    "test",
                    "create-drop",
                    Map.of("app.jwt.secret", "", "app.security.bootstrap.enabled", "false")
            )) {
                fail("Expected startup failure for empty JWT secret");
            }
        });
        assertTrue(hasCause(missingEx, IllegalStateException.class, "JWT signing secret"),
                "Expected IllegalStateException mentioning JWT signing secret, got: " + missingEx);

        String db2 = "boot_jwt_weak_" + UUID.randomUUID().toString().replace("-", "");
        Exception weakEx = assertThrows(Exception.class, () -> {
            try (ConfigurableApplicationContext ignored = startAppContext(
                    db2,
                    "test",
                    "create-drop",
                    Map.of("app.jwt.secret", randomJwtSecret(16), "app.security.bootstrap.enabled", "false")
            )) {
                fail("Expected startup failure for 128-bit JWT secret");
            }
        });
        assertTrue(hasCause(weakEx, IllegalStateException.class, "too weak"),
                "Expected IllegalStateException mentioning weak JWT signing secret, got: " + weakEx);
    }

    @Test
    @DisplayName("2. Non-local startup fails closed when zero ADMINs exist and bootstrap credentials are missing or weak")
    void prodBootstrapFailsClosedWhenZeroAdminsAndMissingOrWeakCredentials() {
        String jwtSecret = randomJwtSecret(48);

        String dbMissing = "boot_prod_missing_" + UUID.randomUUID().toString().replace("-", "");
        Exception missingAdminEx = assertThrows(Exception.class, () -> {
            try (ConfigurableApplicationContext ignored = startAppContext(
                    dbMissing,
                    "prod",
                    "create-drop",
                    Map.of(
                            "app.jwt.secret", jwtSecret,
                            "app.security.cors.allowed-origins", "https://pharmatrack.example.com",
                            "app.security.bootstrap.enabled", "true",
                            "app.security.bootstrap.admin-username", "",
                            "app.security.bootstrap.admin-password", ""
                    )
            )) {
                fail("Expected fail-closed startup without bootstrap admin credentials");
            }
        });
        assertTrue(hasCause(missingAdminEx, IllegalStateException.class, "BOOTSTRAP_ADMIN_USERNAME"),
                "Expected clear configuration guidance, got: " + missingAdminEx);

        String dbWeak = "boot_prod_weak_" + UUID.randomUUID().toString().replace("-", "");
        Exception weakAdminEx = assertThrows(Exception.class, () -> {
            try (ConfigurableApplicationContext ignored = startAppContext(
                    dbWeak,
                    "prod",
                    "create-drop",
                    Map.of(
                            "app.jwt.secret", jwtSecret,
                            "app.security.cors.allowed-origins", "https://pharmatrack.example.com",
                            "app.security.bootstrap.enabled", "true",
                            "app.security.bootstrap.admin-username", "root_admin",
                            "app.security.bootstrap.admin-password", "short"
                    )
            )) {
                fail("Expected fail-closed startup for weak bootstrap admin password");
            }
        });
        assertTrue(hasCause(weakAdminEx, IllegalStateException.class, "Security bootstrap configuration invalid"),
                "Expected bootstrap validation failure, got: " + weakAdminEx);
    }

    @Test
    @DisplayName("3. Non-local bootstrap creates ADMIN idempotently and never overwrites an existing ADMIN across restarts")
    void prodBootstrapIsIdempotentAndNeverOverwritesExistingAdmin() {
        String dbName = "boot_prod_idem_" + UUID.randomUUID().toString().replace("-", "");
        String jwtSecret = randomJwtSecret(48);
        String initialPassword = ephemeralPassword();
        String differentPassword = ephemeralPassword();

        String originalHash;
        try (ConfigurableApplicationContext ctx1 = startAppContext(
                dbName,
                "prod",
                "create",
                Map.of(
                        "app.jwt.secret", jwtSecret,
                        "app.security.cors.allowed-origins", "https://pharmatrack.example.com",
                        "app.security.bootstrap.enabled", "true",
                        "app.security.bootstrap.admin-username", "Bootstrap.Admin",
                        "app.security.bootstrap.admin-password", initialPassword,
                        "app.security.bootstrap.admin-full-name", "Primary Bootstrap Admin"
                )
        )) {
            UserAccountRepository repo = ctx1.getBean(UserAccountRepository.class);
            PasswordEncoder encoder = ctx1.getBean(PasswordEncoder.class);

            assertEquals(1, repo.count());
            UserAccount admin = repo.findByUsernameIgnoreCase("bootstrap.admin").orElseThrow();
            assertEquals("bootstrap.admin", admin.getUsername());
            assertEquals(Role.ADMIN, admin.getRole());
            assertTrue(admin.isEnabled());
            assertEquals("Primary Bootstrap Admin", admin.getFullName());
            assertTrue(encoder.matches(initialPassword, admin.getPasswordHash()));
            originalHash = admin.getPasswordHash();
        }

        // Restart #2 against same persisted schema with DIFFERENT bootstrap password -> MUST NOT overwrite existing admin
        try (ConfigurableApplicationContext ctx2 = startAppContext(
                dbName,
                "prod",
                "update",
                Map.of(
                        "app.jwt.secret", jwtSecret,
                        "app.security.cors.allowed-origins", "https://pharmatrack.example.com",
                        "app.security.bootstrap.enabled", "true",
                        "app.security.bootstrap.admin-username", "Bootstrap.Admin",
                        "app.security.bootstrap.admin-password", differentPassword,
                        "app.security.bootstrap.admin-full-name", "Attempted Overwrite"
                )
        )) {
            UserAccountRepository repo = ctx2.getBean(UserAccountRepository.class);
            PasswordEncoder encoder = ctx2.getBean(PasswordEncoder.class);

            assertEquals(1, repo.count());
            UserAccount reloaded = repo.findByUsernameIgnoreCase("bootstrap.admin").orElseThrow();
            assertEquals(originalHash, reloaded.getPasswordHash());
            assertEquals("Primary Bootstrap Admin", reloaded.getFullName());
            assertTrue(encoder.matches(initialPassword, reloaded.getPasswordHash()));
            assertFalse(encoder.matches(differentPassword, reloaded.getPasswordHash()));
        }

        // Restart #3 with bootstrap creds REMOVED -> succeeds because an ADMIN already exists
        try (ConfigurableApplicationContext ctx3 = startAppContext(
                dbName,
                "prod",
                "update",
                Map.of(
                        "app.jwt.secret", jwtSecret,
                        "app.security.cors.allowed-origins", "https://pharmatrack.example.com",
                        "app.security.bootstrap.enabled", "true",
                        "app.security.bootstrap.admin-username", "",
                        "app.security.bootstrap.admin-password", ""
                )
        )) {
            UserAccountRepository repo = ctx3.getBean(UserAccountRepository.class);
            assertEquals(1, repo.countByRole(Role.ADMIN));
        }
    }

    @Test
    @DisplayName("4. Local demo user initializer skips unconfigured/weak passwords and idempotently seeds all 5 roles without modifying existing users or business records")
    void localDemoProvisioningNeverUsesDefaultsAndLeavesExistingDataUntouched() {
        String jwtSecret = randomJwtSecret(48);

        // A. Local profile with demo enabled but blank password creates zero accounts (never invents default passwords)
        String dbBlankPwd = "boot_local_off_" + UUID.randomUUID().toString().replace("-", "");
        try (ConfigurableApplicationContext ctx = startAppContext(
                dbBlankPwd,
                "local",
                "create-drop",
                Map.of(
                        "app.jwt.secret", jwtSecret,
                        "app.security.bootstrap.enabled", "true",
                        "app.security.local-demo.enabled", "true",
                        "app.security.local-demo.password", ""
                )
        )) {
            assertEquals(0, ctx.getBean(UserAccountRepository.class).count());
        }

        // B. Local profile with weak password skips provisioning cleanly (creates zero users)
        String dbWeak = "boot_local_weak_" + UUID.randomUUID().toString().replace("-", "");
        try (ConfigurableApplicationContext ctx = startAppContext(
                dbWeak,
                "local",
                "create-drop",
                Map.of(
                        "app.jwt.secret", jwtSecret,
                        "app.security.bootstrap.enabled", "true",
                        "app.security.local-demo.enabled", "true",
                        "app.security.local-demo.password", "password"
                )
        )) {
            assertEquals(0, ctx.getBean(UserAccountRepository.class).count());
        }

        // C. Local profile with valid password seeds all 5 roles on first boot and preserves existing users + business data on reboot
        String dbSeeded = "boot_local_seed_" + UUID.randomUUID().toString().replace("-", "");
        String demoPwd1 = ephemeralPassword();
        String demoPwd2 = ephemeralPassword();
        String originalAdminHash;
        Long existingProductId;

        try (ConfigurableApplicationContext ctx1 = startAppContext(
                dbSeeded,
                "local",
                "create",
                Map.of(
                        "app.jwt.secret", jwtSecret,
                        "app.security.bootstrap.enabled", "true",
                        "app.security.local-demo.enabled", "true",
                        "app.security.local-demo.password", demoPwd1
                )
        )) {
            UserAccountRepository userRepo = ctx1.getBean(UserAccountRepository.class);
            PasswordEncoder encoder = ctx1.getBean(PasswordEncoder.class);
            ProductRepository productRepo = ctx1.getBean(ProductRepository.class);
            AuditLogRepository auditRepo = ctx1.getBean(AuditLogRepository.class);

            assertEquals(5, userRepo.count());
            for (Role role : Role.values()) {
                assertEquals(1, userRepo.countByRoleAndEnabledTrue(role), "Missing enabled user for role " + role);
            }
            UserAccount admin = userRepo.findByUsernameIgnoreCase("admin").orElseThrow();
            assertTrue(encoder.matches(demoPwd1, admin.getPasswordHash()));
            originalAdminHash = admin.getPasswordHash();

            // Simulate existing business + audit records in DB prior to subsequent local startup
            Product p = productRepo.saveAndFlush(
                    new Product("LOCAL-PRD-1", "Existing Product", "500 mg", "Tablet", "Keep intact", ProductStatus.ACTIVE));
            existingProductId = p.getId();
            auditRepo.save(new AuditLog(
                    "PRODUCT", p.getId(), p.getProductCode(), null, "PRODUCT_CREATED",
                    "SYSTEM", null, "Baseline record", null, "{\"productCode\":\"LOCAL-PRD-1\"}"
            ));
        }

        // Restart with a different PHARMATRACK_DEMO_PASSWORD -> existing 5 users AND existing product/audit logs remain untouched
        try (ConfigurableApplicationContext ctx2 = startAppContext(
                dbSeeded,
                "local",
                "update",
                Map.of(
                        "app.jwt.secret", jwtSecret,
                        "app.security.bootstrap.enabled", "true",
                        "app.security.local-demo.enabled", "true",
                        "app.security.local-demo.password", demoPwd2
                )
        )) {
            UserAccountRepository userRepo = ctx2.getBean(UserAccountRepository.class);
            ProductRepository productRepo = ctx2.getBean(ProductRepository.class);
            AuditLogRepository auditRepo = ctx2.getBean(AuditLogRepository.class);

            assertEquals(5, userRepo.count());
            UserAccount adminAfterRestart = userRepo.findByUsernameIgnoreCase("admin").orElseThrow();
            assertEquals(originalAdminHash, adminAfterRestart.getPasswordHash());

            assertEquals(1, productRepo.count());
            assertEquals("Existing Product", productRepo.findById(existingProductId).orElseThrow().getProductName());
            assertEquals(1, auditRepo.count());
        }
    }
}
