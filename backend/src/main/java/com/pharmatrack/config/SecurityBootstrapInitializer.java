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
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent administrator bootstrap initializer.
 *
 * <p>In non-{@code local} deployments, startup fails closed if zero {@link Role#ADMIN} accounts
 * exist and {@code BOOTSTRAP_ADMIN_USERNAME} / {@code BOOTSTRAP_ADMIN_PASSWORD} are missing or invalid.
 * Existing {@link Role#ADMIN} accounts are never overwritten.
 */
@Component
@Order(10)
public class SecurityBootstrapInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityBootstrapInitializer.class);

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityConfigProperties securityProperties;
    private final Environment environment;

    public SecurityBootstrapInitializer(UserAccountRepository userAccountRepository,
                                        PasswordEncoder passwordEncoder,
                                        SecurityConfigProperties securityProperties,
                                        Environment environment) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityProperties = securityProperties;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        SecurityConfigProperties.Bootstrap bootstrap = securityProperties.getBootstrap();
        if (bootstrap == null || !bootstrap.isEnabled()) {
            return;
        }

        boolean isLocalProfile = environment.acceptsProfiles(Profiles.of("local"));
        long existingAdminCount = userAccountRepository.countByRole(Role.ADMIN);
        if (existingAdminCount > 0) {
            log.info("Security bootstrap skipped: {} ADMIN account(s) already exist", existingAdminCount);
            return;
        }

        String rawUsername = bootstrap.getAdminUsername();
        String rawPassword = bootstrap.getAdminPassword();
        boolean missingUsername = rawUsername == null || rawUsername.isBlank();
        boolean missingPassword = rawPassword == null || rawPassword.isBlank();

        if (missingUsername || missingPassword) {
            if (isLocalProfile) {
                log.info("No ADMIN account provisioned via bootstrap in local profile (set BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD if desired).");
                return;
            }
            throw new IllegalStateException(
                    "Security bootstrap failed: zero ADMIN users exist and required credentials are missing. "
                            + "Set BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD (or app.security.bootstrap.admin-username / admin-password) before starting.");
        }

        String normalizedUsername;
        String normalizedFullName;
        try {
            normalizedUsername = UserAccountValidationSupport.validateAndNormalizeUsername(rawUsername);
            String fullNameInput = bootstrap.getAdminFullName() == null || bootstrap.getAdminFullName().isBlank()
                    ? "System Administrator"
                    : bootstrap.getAdminFullName();
            normalizedFullName = UserAccountValidationSupport.validateAndNormalizeFullName(fullNameInput);
            UserAccountValidationSupport.validatePassword(rawPassword, normalizedUsername);
        } catch (InvalidUserOperationException ex) {
            throw new IllegalStateException("Security bootstrap configuration invalid: " + ex.getMessage(), ex);
        }

        if (userAccountRepository.findByUsernameIgnoreCase(normalizedUsername).isPresent()) {
            throw new IllegalStateException(
                    "Security bootstrap failed: username '" + normalizedUsername + "' already exists as a non-ADMIN user");
        }

        UserAccount admin = new UserAccount(
                normalizedUsername,
                passwordEncoder.encode(rawPassword),
                normalizedFullName,
                Role.ADMIN,
                true
        );
        userAccountRepository.save(admin);
        log.info("Security bootstrap created initial ADMIN account '{}'", normalizedUsername);
    }
}

