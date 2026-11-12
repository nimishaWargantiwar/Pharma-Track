package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for verifying and closing a deviation ({@code PENDING_VERIFICATION} -> {@code CLOSED}).
 */
public class CloseDeviationRequestDto {

    @NotBlank(message = "Verification notes are mandatory")
    @Size(max = 2000, message = "Verification notes must not exceed 2000 characters")
    private String verificationNotes;

    public CloseDeviationRequestDto() {
    }

    public CloseDeviationRequestDto(String verificationNotes) {
        this.verificationNotes = verificationNotes;
    }

    public String getVerificationNotes() {
        return verificationNotes;
    }

    public void setVerificationNotes(String verificationNotes) {
        this.verificationNotes = verificationNotes;
    }
}

