package com.pharmatrack.dto;

import com.pharmatrack.entity.BatchStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response payload DTO representing one Batch in the Production Execution queue.
 */
public class BatchExecutionQueueItemDto {

    private Long batchId;
    private String batchNumber;
    private BatchStatus batchStatus;
    private String orderNumber;
    private String productName;
    private String recipeCode;
    private Integer recipeVersion;
    private BigDecimal batchSize;
    private String batchUnit;
    private LocalDateTime startTime;

    public BatchExecutionQueueItemDto() {
    }

    public BatchExecutionQueueItemDto(Long batchId, String batchNumber, BatchStatus batchStatus,
                                      String orderNumber, String productName, String recipeCode,
                                      Integer recipeVersion, BigDecimal batchSize, String batchUnit,
                                      LocalDateTime startTime) {
        this.batchId = batchId;
        this.batchNumber = batchNumber;
        this.batchStatus = batchStatus;
        this.orderNumber = orderNumber;
        this.productName = productName;
        this.recipeCode = recipeCode;
        this.recipeVersion = recipeVersion;
        this.batchSize = batchSize;
        this.batchUnit = batchUnit;
        this.startTime = startTime;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public String getBatchNumber() {
        return batchNumber;
    }

    public void setBatchNumber(String batchNumber) {
        this.batchNumber = batchNumber;
    }

    public BatchStatus getBatchStatus() {
        return batchStatus;
    }

    public void setBatchStatus(BatchStatus batchStatus) {
        this.batchStatus = batchStatus;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getRecipeCode() {
        return recipeCode;
    }

    public void setRecipeCode(String recipeCode) {
        this.recipeCode = recipeCode;
    }

    public Integer getRecipeVersion() {
        return recipeVersion;
    }

    public void setRecipeVersion(Integer recipeVersion) {
        this.recipeVersion = recipeVersion;
    }

    public BigDecimal getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(BigDecimal batchSize) {
        this.batchSize = batchSize;
    }

    public String getBatchUnit() {
        return batchUnit;
    }

    public void setBatchUnit(String batchUnit) {
        this.batchUnit = batchUnit;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }
}

