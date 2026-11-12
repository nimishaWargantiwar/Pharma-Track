package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request payload DTO for issuing raw material stock from a specific lot
 * against an active Manufacturing Order (and optional child batch).
 */
public class MaterialIssueRequestDto {

    @NotNull(message = "Manufacturing order ID is mandatory")
    private Long manufacturingOrderId;

    private Long batchId;

    @NotNull(message = "Raw material ID is mandatory")
    private Long rawMaterialId;

    @NotNull(message = "Material lot ID is mandatory")
    private Long materialLotId;

    @NotNull(message = "Quantity is mandatory")
    @Positive(message = "Issue quantity must be greater than zero")
    private BigDecimal quantity;

    @NotBlank(message = "Unit is mandatory")
    @Size(max = 50, message = "Unit must not exceed 50 characters")
    private String unit;

    @Size(max = 100, message = "Issue reference number must not exceed 100 characters")
    private String referenceNumber;

    @Size(max = 1000, message = "Reason/notes must not exceed 1000 characters")
    private String reason;

    public MaterialIssueRequestDto() {
    }

    public Long getManufacturingOrderId() {
        return manufacturingOrderId;
    }

    public void setManufacturingOrderId(Long manufacturingOrderId) {
        this.manufacturingOrderId = manufacturingOrderId;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getRawMaterialId() {
        return rawMaterialId;
    }

    public void setRawMaterialId(Long rawMaterialId) {
        this.rawMaterialId = rawMaterialId;
    }

    public Long getMaterialLotId() {
        return materialLotId;
    }

    public void setMaterialLotId(Long materialLotId) {
        this.materialLotId = materialLotId;
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

