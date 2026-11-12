package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;

/**
 * Administrator request payload for updating non-security account profile metadata.
 * Username remains immutable to preserve historical batch execution and audit attribution.
 */
public class UpdateUserProfileRequestDto {

    @Null(message = "User ID must not be provided when updating user profile")
    private Long id;

    @NotBlank(message = "Full name is mandatory")
    @Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters")
    private String fullName;

    public UpdateUserProfileRequestDto() {
    }

    public UpdateUserProfileRequestDto(String fullName) {
        this.fullName = fullName;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }
}

