package com.pharmatrack.dto;

import com.pharmatrack.entity.BatchStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Payload DTO for updating a production Batch's lifecycle status and optional recorded yield.
 */
public class BatchStatusUpdateDto {

    @NotNull(message = "Batch status is mandatory")
    private BatchStatus status;

    @Positive(message = "Actual yield must be greater than zero")
    private BigDecimal actualYield;

    public BatchStatusUpdateDto() {
    }

    public BatchStatusUpdateDto(BatchStatus status) {
        this.status = status;
    }

    public BatchStatusUpdateDto(BatchStatus status, BigDecimal actualYield) {
        this.status = status;
        this.actualYield = actualYield;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public void setStatus(BatchStatus status) {
        this.status = status;
    }

    public BigDecimal getActualYield() {
        return actualYield;
    }

    public void setActualYield(BigDecimal actualYield) {
        this.actualYield = actualYield;
    }
}

