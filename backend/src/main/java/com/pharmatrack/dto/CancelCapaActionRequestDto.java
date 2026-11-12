package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for cancelling an open or in-progress CAPA action with a mandatory reason.
 */
public class CancelCapaActionRequestDto {

    @NotBlank(message = "Cancellation reason is mandatory")
    @Size(max = 1000, message = "Cancellation reason must not exceed 1000 characters")
    private String cancellationReason;

    public CancelCapaActionRequestDto() {
    }

    public CancelCapaActionRequestDto(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }
}

