package com.pharmatrack.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for permanently deleting an AI Batch Analysis Report.
 * Restricted strictly to local development and authenticated Administrators.
 */
public class PermanentDeleteReportRequestDto {

    public static final String REQUIRED_CONFIRMATION_KEYWORD = "PERMANENTLY DELETE";

    @NotBlank(message = "Deletion reason is mandatory and cannot be blank.")
    @Size(max = 500, message = "Deletion reason must not exceed 500 characters.")
    private String reason;

    @NotBlank(message = "Confirmation keyword is mandatory.")
    @Pattern(regexp = "^PERMANENTLY DELETE$", message = "Confirmation keyword must be exactly 'PERMANENTLY DELETE'.")
    private String confirmationKeyword;

    public PermanentDeleteReportRequestDto() {
    }

    public PermanentDeleteReportRequestDto(String reason, String confirmationKeyword) {
        this.reason = reason;
        this.confirmationKeyword = confirmationKeyword;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getConfirmationKeyword() {
        return confirmationKeyword;
    }

    public void setConfirmationKeyword(String confirmationKeyword) {
        this.confirmationKeyword = confirmationKeyword;
    }
}
