package com.pharmatrack.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request payload DTO for activating or deactivating an inventory master entity.
 */
public class InventoryStatusUpdateRequestDto {

    @NotNull(message = "Active status flag is mandatory")
    private Boolean active;

    public InventoryStatusUpdateRequestDto() {
    }

    public InventoryStatusUpdateRequestDto(Boolean active) {
        this.active = active;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}

