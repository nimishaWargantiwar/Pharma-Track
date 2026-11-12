package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for creating or updating a storage location within a warehouse.
 */
public class StorageLocationRequestDto {

    private Long warehouseId;

    @NotBlank(message = "Location code is mandatory")
    @Size(max = 60, message = "Location code must not exceed 60 characters")
    private String locationCode;

    @Size(max = 100, message = "Zone name must not exceed 100 characters")
    private String zoneName;

    @Size(max = 40, message = "Aisle must not exceed 40 characters")
    private String aisle;

    @Size(max = 40, message = "Rack must not exceed 40 characters")
    private String rack;

    @Size(max = 40, message = "Bin must not exceed 40 characters")
    private String bin;

    private Boolean active = Boolean.TRUE;

    public StorageLocationRequestDto() {
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public void setLocationCode(String locationCode) {
        this.locationCode = locationCode;
    }

    public String getZoneName() {
        return zoneName;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    public String getAisle() {
        return aisle;
    }

    public void setAisle(String aisle) {
        this.aisle = aisle;
    }

    public String getRack() {
        return rack;
    }

    public void setRack(String rack) {
        this.rack = rack;
    }

    public String getBin() {
        return bin;
    }

    public void setBin(String bin) {
        this.bin = bin;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}

