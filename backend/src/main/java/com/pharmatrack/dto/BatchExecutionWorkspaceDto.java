package com.pharmatrack.dto;

import com.pharmatrack.entity.BatchStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Response payload DTO representing the complete Production Execution workspace
 * for a single production Batch, including batch context and ordered execution steps.
 */
public class BatchExecutionWorkspaceDto {

    private Long batchId;
    private String batchNumber;
    private BatchStatus batchStatus;
    private String orderNumber;
    private String productName;
    private String recipeCode;
    private Integer recipeVersion;
    private BigDecimal batchSize;
    private String batchUnit;
    private List<BatchExecutionStepResponseDto> executionSteps = new ArrayList<>();

    public BatchExecutionWorkspaceDto() {
    }

    public BatchExecutionWorkspaceDto(Long batchId, String batchNumber, BatchStatus batchStatus,
                                      String orderNumber, String productName, String recipeCode,
                                      Integer recipeVersion, BigDecimal batchSize, String batchUnit,
                                      List<BatchExecutionStepResponseDto> executionSteps) {
        this.batchId = batchId;
        this.batchNumber = batchNumber;
        this.batchStatus = batchStatus;
        this.orderNumber = orderNumber;
        this.productName = productName;
        this.recipeCode = recipeCode;
        this.recipeVersion = recipeVersion;
        this.batchSize = batchSize;
        this.batchUnit = batchUnit;
        this.executionSteps = executionSteps != null ? executionSteps : new ArrayList<>();
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

    public List<BatchExecutionStepResponseDto> getExecutionSteps() {
        return executionSteps;
    }

    public void setExecutionSteps(List<BatchExecutionStepResponseDto> executionSteps) {
        this.executionSteps = executionSteps != null ? executionSteps : new ArrayList<>();
    }
}

