package com.pharmatrack.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Administrator request payload for enabling or disabling a user account.
 */
public class UpdateUserStatusRequestDto {

    @NotNull(message = "Enabled status is mandatory")
    private Boolean enabled;

    public UpdateUserStatusRequestDto() {
    }

    public UpdateUserStatusRequestDto(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}

