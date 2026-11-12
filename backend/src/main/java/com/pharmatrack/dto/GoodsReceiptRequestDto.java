package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request payload DTO for recording a raw material Goods Receipt into a warehouse storage location.
 */
public class GoodsReceiptRequestDto {

    @NotNull(message = "Raw material ID is mandatory")
    private Long rawMaterialId;

    @NotNull(message = "Warehouse ID is mandatory")
    private Long warehouseId;

    @NotNull(message = "Storage location ID is mandatory")
    private Long storageLocationId;

    @NotBlank(message = "Lot number is mandatory")
    @Size(max = 80, message = "Lot number must not exceed 80 characters")
    private String lotNumber;

    @NotNull(message = "Quantity is mandatory")
    @Positive(message = "Receipt quantity must be greater than zero")
    private BigDecimal quantity;

    @NotBlank(message = "Unit is mandatory")
    @Size(max = 50, message = "Unit must not exceed 50 characters")
    private String unit;

    @NotNull(message = "Received date is mandatory")
    private LocalDate receivedDate;

    @NotNull(message = "Expiry date is mandatory")
    private LocalDate expiryDate;

    @NotBlank(message = "Supplier name is mandatory")
    @Size(max = 150, message = "Supplier name must not exceed 150 characters")
    private String supplierName;

    @Size(max = 100, message = "Supplier reference must not exceed 100 characters")
    private String supplierReference;

    @Size(max = 100, message = "Receipt reference number must not exceed 100 characters")
    private String referenceNumber;

    @Size(max = 1000, message = "Reason/notes must not exceed 1000 characters")
    private String reason;

    public GoodsReceiptRequestDto() {
    }

    public Long getRawMaterialId() {
        return rawMaterialId;
    }

    public void setRawMaterialId(Long rawMaterialId) {
        this.rawMaterialId = rawMaterialId;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public Long getStorageLocationId() {
        return storageLocationId;
    }

    public void setStorageLocationId(Long storageLocationId) {
        this.storageLocationId = storageLocationId;
    }

    public String getLotNumber() {
        return lotNumber;
    }

    public void setLotNumber(String lotNumber) {
        this.lotNumber = lotNumber;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
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

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}

