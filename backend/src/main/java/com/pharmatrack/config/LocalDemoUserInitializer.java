package com.pharmatrack.config;

import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.exception.InvalidUserOperationException;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.SecurityConfigProperties;
import com.pharmatrack.security.UserAccountValidationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Optional idempotent local development demo user initializer for all 5 MES/eBR roles.
 * Strictly requires external configuration ({@code LOCAL_DEMO_USERS_ENABLED=true} and
 * {@code PHARMATRACK_DEMO_PASSWORD}) via environment variables or gitignored local config.
 * Never invents default passwords, never overwrites existing user accounts, and never touches business tables.
 */
@Component
@Profile("local")
@Order(20)
public class LocalDemoUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDemoUserInitializer.class);

    private record DemoUserSpec(String username, String fullName, Role role) {
    }

    private static final List<DemoUserSpec> DEMO_USERS = List.of(
            new DemoUserSpec("admin", "System Administrator", Role.ADMIN),
            new DemoUserSpec("supervisor", "Production Supervisor", Role.PRODUCTION_SUPERVISOR),
            new DemoUserSpec("operator", "Line Operator", Role.OPERATOR),
            new DemoUserSpec("qc_analyst", "QC Analyst", Role.QC_ANALYST),
            new DemoUserSpec("qa_reviewer", "QA Reviewer", Role.QA_REVIEWER)
    );

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityConfigProperties securityProperties;

    public LocalDemoUserInitializer(UserAccountRepository userAccountRepository,
                                    PasswordEncoder passwordEncoder,
                                    SecurityConfigProperties securityProperties) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityProperties = securityProperties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        SecurityConfigProperties.LocalDemo localDemo = securityProperties.getLocalDemo();
        if (localDemo == null || !localDemo.isEnabled()) {
            log.info("Local demo users not enabled. To seed optional local demo users for all 5 roles, set LOCAL_DEMO_USERS_ENABLED=true and PHARMATRACK_DEMO_PASSWORD in env or gitignored application-local.properties.");
            return;
        }

        String rawPassword = localDemo.getPassword();
        if (rawPassword == null || rawPassword.isBlank()) {
            log.warn("Skipping local demo user provisioning: LOCAL_DEMO_USERS_ENABLED=true requires PHARMATRACK_DEMO_PASSWORD (or app.security.local-demo.password) to be set via env or gitignored application-local.properties.");
            return;
        }

        for (DemoUserSpec spec : DEMO_USERS) {
            try {
                UserAccountValidationSupport.validatePassword(rawPassword, spec.username());
            } catch (InvalidUserOperationException ex) {
                log.warn("Skipping local demo user provisioning: configured PHARMATRACK_DEMO_PASSWORD fails policy validation ({}).", ex.getMessage());
                return;
            }
        }

        int createdCount = 0;
        for (DemoUserSpec spec : DEMO_USERS) {
            if (userAccountRepository.findByUsernameIgnoreCase(spec.username()).isPresent()) {
                continue;
            }
            UserAccount account = new UserAccount(
                    spec.username(),
                    passwordEncoder.encode(rawPassword),
                    spec.fullName(),
                    spec.role(),
                    true
            );
            userAccountRepository.save(account);
            createdCount++;
        }

        if (createdCount > 0) {
            log.info("Local demo user provisioning created {} missing role account(s) across the 5 application roles.", createdCount);
        } else {
            log.info("Local demo user provisioning skipped: all 5 role demo accounts already exist.");
        }
    }
}

