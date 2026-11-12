package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for returning a deviation from {@code PENDING_VERIFICATION}
 * back to {@code CAPA_IN_PROGRESS} with a mandatory audit reason.
 */
public class ReopenCapaRequestDto {

    @NotBlank(message = "Reopen reason is mandatory")
    @Size(max = 2000, message = "Reopen reason must not exceed 2000 characters")
    private String reason;

    public ReopenCapaRequestDto() {
    }

    public ReopenCapaRequestDto(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}

