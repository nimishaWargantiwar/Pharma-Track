package com.pharmatrack.dto;

import com.pharmatrack.entity.ProductStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Payload DTO for updating product lifecycle status.
 */
public class StatusUpdateRequestDto {

    @NotNull(message = "Status is mandatory (ACTIVE, INACTIVE)")
    private ProductStatus status;

    public StatusUpdateRequestDto() {
    }

    public StatusUpdateRequestDto(ProductStatus status) {
        this.status = status;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public void setStatus(ProductStatus status) {
        this.status = status;
    }
}

