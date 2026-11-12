package com.pharmatrack.dto;

import com.pharmatrack.entity.StockAdjustmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request payload DTO for performing a controlled stock adjustment or quarantine status change
 * on an existing material lot.
 */
public class StockAdjustmentRequestDto {

    @NotNull(message = "Material lot ID is mandatory")
    private Long materialLotId;

    @NotNull(message = "Adjustment type is mandatory")
    private StockAdjustmentType adjustmentType;

    /**
     * Required and strictly positive for INCREASE and DECREASE adjustments; ignored for QUARANTINE / RELEASE_QUARANTINE.
     */
    private BigDecimal quantity;

    @Size(max = 100, message = "Adjustment reference number must not exceed 100 characters")
    private String referenceNumber;

    @NotBlank(message = "Reason is mandatory for all stock adjustments")
    @Size(min = 3, max = 1000, message = "Adjustment reason must be between 3 and 1000 characters")
    private String reason;

    public StockAdjustmentRequestDto() {
    }

    public Long getMaterialLotId() {
        return materialLotId;
    }

    public void setMaterialLotId(Long materialLotId) {
        this.materialLotId = materialLotId;
    }

    public StockAdjustmentType getAdjustmentType() {
        return adjustmentType;
    }

    public void setAdjustmentType(StockAdjustmentType adjustmentType) {
        this.adjustmentType = adjustmentType;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
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

