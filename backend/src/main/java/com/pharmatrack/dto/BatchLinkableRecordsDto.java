package com.pharmatrack.dto;

import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;

import java.util.List;

/**
 * Response DTO containing existing Quality Checks, eBR Execution Steps, and Audit Log entries
 * belonging to a specific manufacturing batch that can be optionally linked when reporting a deviation.
 */
public class BatchLinkableRecordsDto {

    private Long batchId;
    private String batchNumber;
    private BatchStatus batchStatus;
    private BatchQualityStatus qaStatus;
    private String orderNumber;
    private String productCode;
    private String productName;
    private List<QualityCheckResponseDto> qualityChecks;
    private List<BatchExecutionStepResponseDto> executionSteps;
    private List<AuditLogResponseDto> auditLogs;

    public BatchLinkableRecordsDto() {
        this.qualityChecks = List.of();
        this.executionSteps = List.of();
        this.auditLogs = List.of();
    }

    public BatchLinkableRecordsDto(Long batchId, String batchNumber, BatchStatus batchStatus,
                                   BatchQualityStatus qaStatus, String orderNumber,
                                   String productCode, String productName,
                                   List<QualityCheckResponseDto> qualityChecks,
                                   List<BatchExecutionStepResponseDto> executionSteps,
                                   List<AuditLogResponseDto> auditLogs) {
        this.batchId = batchId;
        this.batchNumber = batchNumber;
        this.batchStatus = batchStatus;
        this.qaStatus = qaStatus;
        this.orderNumber = orderNumber;
        this.productCode = productCode;
        this.productName = productName;
        this.qualityChecks = qualityChecks != null ? qualityChecks : List.of();
        this.executionSteps = executionSteps != null ? executionSteps : List.of();
        this.auditLogs = auditLogs != null ? auditLogs : List.of();
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

    public BatchQualityStatus getQaStatus() {
        return qaStatus;
    }

    public void setQaStatus(BatchQualityStatus qaStatus) {
        this.qaStatus = qaStatus;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public List<QualityCheckResponseDto> getQualityChecks() {
        return qualityChecks;
    }

    public void setQualityChecks(List<QualityCheckResponseDto> qualityChecks) {
        this.qualityChecks = qualityChecks != null ? qualityChecks : List.of();
    }

    public List<BatchExecutionStepResponseDto> getExecutionSteps() {
        return executionSteps;
    }

    public void setExecutionSteps(List<BatchExecutionStepResponseDto> executionSteps) {
        this.executionSteps = executionSteps != null ? executionSteps : List.of();
    }

    public List<AuditLogResponseDto> getAuditLogs() {
        return auditLogs;
    }

    public void setAuditLogs(List<AuditLogResponseDto> auditLogs) {
        this.auditLogs = auditLogs != null ? auditLogs : List.of();
    }
}

