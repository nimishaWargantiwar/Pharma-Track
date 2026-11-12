package com.pharmatrack.dto;

import java.time.LocalDateTime;

/**
 * Response payload DTO representing a Warehouse facility with location and lot counts.
 */
public class WarehouseResponseDto {

    private Long id;
    private String warehouseCode;
    private String warehouseName;
    private String locationSummary;
    private String storageCondition;
    private boolean active;
    private int locationCount;
    private int activeLocationCount;
    private int activeLotCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public WarehouseResponseDto() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getLocationCount() {
        return locationCount;
    }

    public void setLocationCount(int locationCount) {
        this.locationCount = locationCount;
    }

    public int getActiveLocationCount() {
        return activeLocationCount;
    }

    public void setActiveLocationCount(int activeLocationCount) {
        this.activeLocationCount = activeLocationCount;
    }

    public int getActiveLotCount() {
        return activeLotCount;
    }

    public void setActiveLotCount(int activeLotCount) {
        this.activeLotCount = activeLotCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

