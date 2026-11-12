package com.pharmatrack.dto;

import com.pharmatrack.entity.Role;
import jakarta.validation.constraints.NotNull;

/**
 * Administrator request payload for updating a user account's role.
 */
public class UpdateUserRoleRequestDto {

    @NotNull(message = "Role is mandatory")
    private Role role;

    public UpdateUserRoleRequestDto() {
    }

    public UpdateUserRoleRequestDto(Role role) {
        this.role = role;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }
}

