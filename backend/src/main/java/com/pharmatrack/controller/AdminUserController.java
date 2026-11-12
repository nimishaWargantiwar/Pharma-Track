package com.pharmatrack.controller;

import com.pharmatrack.dto.CreateUserRequestDto;
import com.pharmatrack.dto.ResetUserPasswordRequestDto;
import com.pharmatrack.dto.UpdateUserProfileRequestDto;
import com.pharmatrack.dto.UpdateUserRoleRequestDto;
import com.pharmatrack.dto.UpdateUserStatusRequestDto;
import com.pharmatrack.dto.UserSummaryDto;
import com.pharmatrack.security.CustomUserDetails;
import com.pharmatrack.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Administrator-only endpoints for listing, inspecting, creating, updating profile metadata,
 * enabling/disabling, changing roles, and resetting temporary passwords of user accounts.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public ResponseEntity<List<UserSummaryDto>> getAllUsers() {
        return ResponseEntity.ok(adminUserService.getAllUsers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserSummaryDto> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(adminUserService.getUserById(id));
    }

    @PostMapping
    public ResponseEntity<UserSummaryDto> createUser(@Valid @RequestBody CreateUserRequestDto request) {
        UserSummaryDto created = adminUserService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<UserSummaryDto> updateUserProfile(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserProfileRequestDto request) {
        return ResponseEntity.ok(adminUserService.updateUserProfile(id, request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<UserSummaryDto> updateUserStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserStatusRequestDto request,
            Authentication authentication) {
        Long currentActorUserId = null;
        String currentActorUsername = null;
        if (authentication != null) {
            if (authentication.getPrincipal() instanceof CustomUserDetails details) {
                currentActorUserId = details.getUserId();
                currentActorUsername = details.getUsername();
            } else {
                currentActorUsername = authentication.getName();
            }
        }
        return ResponseEntity.ok(adminUserService.updateUserStatus(id, request, currentActorUserId, currentActorUsername));
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<UserSummaryDto> updateUserRole(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRoleRequestDto request) {
        return ResponseEntity.ok(adminUserService.updateUserRole(id, request));
    }

    @PatchMapping("/{id}/password")
    public ResponseEntity<UserSummaryDto> resetUserPassword(
            @PathVariable Long id,
            @Valid @RequestBody ResetUserPasswordRequestDto request) {
        return ResponseEntity.ok(adminUserService.resetUserPassword(id, request));
    }
}

