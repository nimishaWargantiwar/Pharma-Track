package com.pharmatrack.service;

import com.pharmatrack.dto.CreateUserRequestDto;
import com.pharmatrack.dto.ResetUserPasswordRequestDto;
import com.pharmatrack.dto.UpdateUserProfileRequestDto;
import com.pharmatrack.dto.UpdateUserRoleRequestDto;
import com.pharmatrack.dto.UpdateUserStatusRequestDto;
import com.pharmatrack.dto.UserSummaryDto;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.exception.DuplicateResourceException;
import com.pharmatrack.exception.InvalidUserOperationException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.security.UserAccountValidationSupport;
import com.pharmatrack.util.AppTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Administrator user-management service with strict input validation, BCrypt hashing,
 * self-disable prevention, last-enabled-admin protection, JWT tokenVersion revocation,
 * and transactional append-only audit trail recording.
 */
@Service
@Transactional
public class AdminUserService {

    public static final String ENTITY_USER_ACCOUNT = "USER_ACCOUNT";

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrailService auditTrailService;
    private final AuthenticatedActorResolver authenticatedActorResolver;

    @Autowired
    public AdminUserService(UserAccountRepository userAccountRepository,
                            PasswordEncoder passwordEncoder,
                            AuditTrailService auditTrailService,
                            AuthenticatedActorResolver authenticatedActorResolver) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditTrailService = auditTrailService;
        this.authenticatedActorResolver = authenticatedActorResolver != null
                ? authenticatedActorResolver
                : new AuthenticatedActorResolver();
    }

    @Transactional(readOnly = true)
    public List<UserSummaryDto> getAllUsers() {
        return userAccountRepository.findAllByOrderByIdAsc()
                .stream()
                .map(UserSummaryDto::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserSummaryDto getUserById(Long targetUserId) {
        if (targetUserId == null) {
            throw new InvalidUserOperationException("User ID is mandatory");
        }
        UserAccount target = userAccountRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + targetUserId));
        return UserSummaryDto.fromEntity(target);
    }

    public UserSummaryDto createUser(CreateUserRequestDto request) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();
        if (request == null) {
            throw new InvalidUserOperationException("User payload cannot be null");
        }
        if (request.getId() != null) {
            throw new InvalidUserOperationException("User ID must not be provided when creating a user");
        }

        String normalizedUsername = UserAccountValidationSupport.validateAndNormalizeUsername(request.getUsername());
        String normalizedFullName = UserAccountValidationSupport.validateAndNormalizeFullName(request.getFullName());
        UserAccountValidationSupport.validatePassword(request.getPassword(), normalizedUsername);

        if (request.getRole() == null) {
            throw new InvalidUserOperationException("Role is mandatory");
        }

        if (userAccountRepository.existsByUsernameIgnoreCase(normalizedUsername)) {
            throw new DuplicateResourceException("User already exists with username: " + normalizedUsername);
        }

        boolean enabled = request.getEnabled() == null || request.getEnabled();
        String passwordHash = passwordEncoder.encode(request.getPassword());

        UserAccount newUser = new UserAccount(
                normalizedUsername,
                passwordHash,
                normalizedFullName,
                request.getRole(),
                enabled
        );
        UserAccount saved = userAccountRepository.saveAndFlush(newUser);

        auditTrailService.recordAudit(
                ENTITY_USER_ACCOUNT,
                saved.getId(),
                saved.getUsername(),
                null,
                "USER_CREATED",
                resolvedActor,
                AppTime.nowDateTime(),
                "Created user account " + saved.getUsername() + " with role " + saved.getRole().name()
                        + (saved.isEnabled() ? " (enabled)" : " (disabled)"),
                null,
                buildUserSnapshot(saved)
        );

        return UserSummaryDto.fromEntity(saved);
    }

    public UserSummaryDto updateUserProfile(Long targetUserId, UpdateUserProfileRequestDto request) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();
        if (targetUserId == null) {
            throw new InvalidUserOperationException("User ID is mandatory");
        }
        if (request == null) {
            throw new InvalidUserOperationException("User profile payload cannot be null");
        }
        if (request.getId() != null) {
            throw new InvalidUserOperationException("User ID must not be provided when updating user profile");
        }

        String normalizedFullName = UserAccountValidationSupport.validateAndNormalizeFullName(request.getFullName());

        UserAccount target = userAccountRepository.findByIdWithLock(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + targetUserId));

        if (normalizedFullName.equals(target.getFullName())) {
            return UserSummaryDto.fromEntity(target);
        }

        Map<String, Object> beforeSnapshot = buildUserSnapshot(target);
        target.setFullName(normalizedFullName);
        UserAccount saved = userAccountRepository.saveAndFlush(target);

        auditTrailService.recordAudit(
                ENTITY_USER_ACCOUNT,
                saved.getId(),
                saved.getUsername(),
                null,
                "USER_PROFILE_UPDATED",
                resolvedActor,
                AppTime.nowDateTime(),
                "Updated profile details for user " + saved.getUsername(),
                beforeSnapshot,
                buildUserSnapshot(saved)
        );

        return UserSummaryDto.fromEntity(saved);
    }

    public UserSummaryDto updateUserStatus(Long targetUserId,
                                           UpdateUserStatusRequestDto request,
                                           Long currentActorUserId,
                                           String currentActorUsername) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();
        if (targetUserId == null) {
            throw new InvalidUserOperationException("User ID is mandatory");
        }
        if (request == null || request.getEnabled() == null) {
            throw new InvalidUserOperationException("Enabled status is mandatory");
        }

        UserAccount target = userAccountRepository.findByIdWithLock(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + targetUserId));

        boolean desiredEnabled = request.getEnabled();
        boolean isSelf = (currentActorUserId != null && currentActorUserId.equals(target.getId()))
                || target.getUsername().equalsIgnoreCase(resolvedActor)
                || (currentActorUsername != null
                && target.getUsername().equalsIgnoreCase(UserAccount.normalizeUsername(currentActorUsername)));

        if (!desiredEnabled && isSelf) {
            throw new InvalidUserOperationException("Administrators cannot disable their own account");
        }

        if (target.isEnabled() == desiredEnabled) {
            return UserSummaryDto.fromEntity(target);
        }

        if (!desiredEnabled && target.getRole() == Role.ADMIN) {
            List<UserAccount> enabledAdmins = userAccountRepository.findEnabledByRoleWithLock(Role.ADMIN);
            if (enabledAdmins.size() <= 1) {
                throw new InvalidUserOperationException("Cannot disable the last enabled ADMIN account");
            }
        }

        Map<String, Object> beforeSnapshot = buildUserSnapshot(target);
        target.setEnabled(desiredEnabled);
        target.incrementTokenVersion();
        UserAccount saved = userAccountRepository.saveAndFlush(target);

        auditTrailService.recordAudit(
                ENTITY_USER_ACCOUNT,
                saved.getId(),
                saved.getUsername(),
                null,
                desiredEnabled ? "USER_ENABLED" : "USER_DISABLED",
                resolvedActor,
                AppTime.nowDateTime(),
                (desiredEnabled ? "Enabled user account " : "Disabled user account ") + saved.getUsername(),
                beforeSnapshot,
                buildUserSnapshot(saved)
        );

        return UserSummaryDto.fromEntity(saved);
    }

    public UserSummaryDto updateUserRole(Long targetUserId, UpdateUserRoleRequestDto request) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();
        if (targetUserId == null) {
            throw new InvalidUserOperationException("User ID is mandatory");
        }
        if (request == null || request.getRole() == null) {
            throw new InvalidUserOperationException("Role is mandatory");
        }

        UserAccount target = userAccountRepository.findByIdWithLock(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + targetUserId));

        Role previousRole = target.getRole();
        Role desiredRole = request.getRole();
        if (previousRole == desiredRole) {
            return UserSummaryDto.fromEntity(target);
        }

        if (previousRole == Role.ADMIN && target.isEnabled() && desiredRole != Role.ADMIN) {
            List<UserAccount> enabledAdmins = userAccountRepository.findEnabledByRoleWithLock(Role.ADMIN);
            if (enabledAdmins.size() <= 1) {
                throw new InvalidUserOperationException("Cannot demote the last enabled ADMIN account");
            }
        }

        Map<String, Object> beforeSnapshot = buildUserSnapshot(target);
        target.setRole(desiredRole);
        target.incrementTokenVersion();
        UserAccount saved = userAccountRepository.saveAndFlush(target);

        auditTrailService.recordAudit(
                ENTITY_USER_ACCOUNT,
                saved.getId(),
                saved.getUsername(),
                null,
                "USER_ROLE_UPDATED",
                resolvedActor,
                AppTime.nowDateTime(),
                "Changed role for user " + saved.getUsername() + " from " + previousRole.name() + " to " + saved.getRole().name(),
                beforeSnapshot,
                buildUserSnapshot(saved)
        );

        return UserSummaryDto.fromEntity(saved);
    }

    public UserSummaryDto resetUserPassword(Long targetUserId, ResetUserPasswordRequestDto request) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();
        if (targetUserId == null) {
            throw new InvalidUserOperationException("User ID is mandatory");
        }
        if (request == null) {
            throw new InvalidUserOperationException("Password reset payload cannot be null");
        }
        if (request.getId() != null) {
            throw new InvalidUserOperationException("User ID must not be provided when resetting password");
        }

        UserAccount target = userAccountRepository.findByIdWithLock(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + targetUserId));

        UserAccountValidationSupport.validatePassword(request.getNewPassword(), target.getUsername());

        Map<String, Object> beforeSnapshot = buildUserSnapshot(target);
        beforeSnapshot.put("activeSessionsRevoked", false);

        target.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        target.incrementTokenVersion();
        UserAccount saved = userAccountRepository.saveAndFlush(target);

        Map<String, Object> afterSnapshot = buildUserSnapshot(saved);
        afterSnapshot.put("activeSessionsRevoked", true);

        auditTrailService.recordAudit(
                ENTITY_USER_ACCOUNT,
                saved.getId(),
                saved.getUsername(),
                null,
                "USER_PASSWORD_RESET",
                resolvedActor,
                AppTime.nowDateTime(),
                "Reset temporary password and revoked active sessions for user " + saved.getUsername(),
                beforeSnapshot,
                afterSnapshot
        );

        return UserSummaryDto.fromEntity(saved);
    }

    private Map<String, Object> buildUserSnapshot(UserAccount user) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("username", user.getUsername());
        snapshot.put("fullName", user.getFullName());
        snapshot.put("role", user.getRole() != null ? user.getRole().name() : null);
        snapshot.put("enabled", user.isEnabled());
        return snapshot;
    }
}

