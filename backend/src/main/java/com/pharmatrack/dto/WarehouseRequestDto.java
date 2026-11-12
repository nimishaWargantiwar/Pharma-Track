package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for creating or updating a Warehouse facility.
 */
public class WarehouseRequestDto {

    @NotBlank(message = "Warehouse code is mandatory")
    @Size(max = 50, message = "Warehouse code must not exceed 50 characters")
    private String warehouseCode;

    @NotBlank(message = "Warehouse name is mandatory")
    @Size(max = 150, message = "Warehouse name must not exceed 150 characters")
    private String warehouseName;

    @Size(max = 255, message = "Location summary must not exceed 255 characters")
    private String locationSummary;

    @Size(max = 100, message = "Storage condition must not exceed 100 characters")
    private String storageCondition;

    private Boolean active = Boolean.TRUE;

    public WarehouseRequestDto() {
    }

    public String getWarehouseCode() {
        return warehouseCode;
    }

    public void setWarehouseCode(String warehouseCode) {
        this.warehouseCode = warehouseCode;
    }

    public String getWarehouseName() {
        return warehouseName;
    }

    public void setWarehouseName(String warehouseName) {
        this.warehouseName = warehouseName;
    }

    public String getLocationSummary() {
        return locationSummary;
    }

    public void setLocationSummary(String locationSummary) {
        this.locationSummary = locationSummary;
    }

    public String getStorageCondition() {
        return storageCondition;
    }

    public void setStorageCondition(String storageCondition) {
        this.storageCondition = storageCondition;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}

