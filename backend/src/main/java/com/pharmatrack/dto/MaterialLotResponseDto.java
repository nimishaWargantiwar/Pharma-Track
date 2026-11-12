package com.pharmatrack.dto;

import com.pharmatrack.entity.MaterialLotStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response payload DTO representing a traceable material lot with warehouse/location details
 * and evaluated expiry indicators.
 */
public class MaterialLotResponseDto {

    private Long id;
    private Long rawMaterialId;
    private String materialCode;
    private String materialName;
    private boolean materialActive;

    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;

    private Long storageLocationId;
    private String locationCode;
    private String zoneName;

    private String lotNumber;
    private String supplierName;
    private String supplierReference;
    private LocalDate receivedDate;
    private LocalDate expiryDate;
    private BigDecimal initialQuantity;
    private BigDecimal availableQuantity;
    private String unit;
    private MaterialLotStatus status;
    private MaterialLotStatus effectiveStatus;
    private boolean expired;
    private boolean expiringSoon;
    private long daysToExpiry;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public MaterialLotResponseDto() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRawMaterialId() {
        return rawMaterialId;
    }

    public void setRawMaterialId(Long rawMaterialId) {
        this.rawMaterialId = rawMaterialId;
    }

    public String getMaterialCode() {
        return materialCode;
    }

    public void setMaterialCode(String materialCode) {
        this.materialCode = materialCode;
    }

    public String getMaterialName() {
        return materialName;
    }

    public void setMaterialName(String materialName) {
        this.materialName = materialName;
    }

    public boolean isMaterialActive() {
        return materialActive;
    }

    public void setMaterialActive(boolean materialActive) {
        this.materialActive = materialActive;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
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

    public Long getStorageLocationId() {
        return storageLocationId;
    }

    public void setStorageLocationId(Long storageLocationId) {
        this.storageLocationId = storageLocationId;
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

    public String getLotNumber() {
        return lotNumber;
    }

    public void setLotNumber(String lotNumber) {
        this.lotNumber = lotNumber;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public void setSupplierName(String supplierName) {
        this.supplierName = supplierName;
    }

    public String getSupplierReference() {
        return supplierReference;
    }

    public void setSupplierReference(String supplierReference) {
        this.supplierReference = supplierReference;
    }

    public LocalDate getReceivedDate() {
        return receivedDate;
    }

    public void setReceivedDate(LocalDate receivedDate) {
        this.receivedDate = receivedDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public BigDecimal getInitialQuantity() {
        return initialQuantity;
    }

    public void setInitialQuantity(BigDecimal initialQuantity) {
        this.initialQuantity = initialQuantity;
    }

    public BigDecimal getAvailableQuantity() {
        return availableQuantity;
    }

    public void setAvailableQuantity(BigDecimal availableQuantity) {
        this.availableQuantity = availableQuantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public MaterialLotStatus getStatus() {
        return status;
    }

    public void setStatus(MaterialLotStatus status) {
        this.status = status;
    }

    public MaterialLotStatus getEffectiveStatus() {
        return effectiveStatus;
    }

    public void setEffectiveStatus(MaterialLotStatus effectiveStatus) {
        this.effectiveStatus = effectiveStatus;
    }

    public boolean isExpired() {
        return expired;
    }

    public void setExpired(boolean expired) {
        this.expired = expired;
    }

    public boolean isExpiringSoon() {
        return expiringSoon;
    }

    public void setExpiringSoon(boolean expiringSoon) {
        this.expiringSoon = expiringSoon;
    }

    public long getDaysToExpiry() {
        return daysToExpiry;
    }

    public void setDaysToExpiry(long daysToExpiry) {
        this.daysToExpiry = daysToExpiry;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
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

