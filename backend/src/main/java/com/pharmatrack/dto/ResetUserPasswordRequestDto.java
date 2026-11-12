package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;

/**
 * Administrator request payload for resetting a user's temporary password.
 */
public class ResetUserPasswordRequestDto {

    @Null(message = "User ID must not be provided when resetting password")
    private Long id;

    @NotBlank(message = "New password is mandatory")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String newPassword;

    public ResetUserPasswordRequestDto() {
    }

    public ResetUserPasswordRequestDto(String newPassword) {
        this.newPassword = newPassword;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}

