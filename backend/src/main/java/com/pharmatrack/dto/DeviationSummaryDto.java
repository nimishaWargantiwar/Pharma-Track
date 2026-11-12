package com.pharmatrack.dto;

import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.QualityCheckResult;

import java.time.LocalDateTime;

/**
 * Summary response DTO for Deviation list views and batch dossier summaries.
 */
public class DeviationSummaryDto {

    private Long id;
    private String deviationNumber;
    private Long batchId;
    private String batchNumber;
    private BatchStatus batchStatus;
    private BatchQualityStatus batchQaStatus;
    private String orderNumber;
    private String productCode;
    private String productName;
    private Long qualityCheckId;
    private String linkedCheckTestName;
    private QualityCheckResult linkedCheckResult;
    private Long executionStepId;
    private Integer linkedStepNumber;
    private String linkedStepOperationName;
    private Long sourceAuditLogId;
    private String title;
    private DeviationCategory category;
    private DeviationSeverity severity;
    private DeviationPriority priority;
    private DeviationStatus status;
    private String reportedBy;
    private LocalDateTime occurredAt;
    private LocalDateTime reportedAt;
    private LocalDateTime closedAt;
    private long totalCapaCount;
    private long openCapaCount;
    private long inProgressCapaCount;
    private long completedCapaCount;
    private long cancelledCapaCount;
    private long overdueCapaCount;

    public DeviationSummaryDto() {
    }

    public DeviationSummaryDto(Long id, String deviationNumber, Long batchId, String batchNumber,
                               BatchStatus batchStatus, BatchQualityStatus batchQaStatus,
                               String orderNumber, String productCode, String productName,
                               Long qualityCheckId, String linkedCheckTestName,
                               QualityCheckResult linkedCheckResult, Long executionStepId,
                               Integer linkedStepNumber, String linkedStepOperationName,
                               Long sourceAuditLogId, String title, DeviationCategory category,
                               DeviationSeverity severity, DeviationPriority priority,
                               DeviationStatus status, String reportedBy, LocalDateTime occurredAt,
                               LocalDateTime reportedAt, LocalDateTime closedAt,
                               long totalCapaCount, long openCapaCount, long inProgressCapaCount,
                               long completedCapaCount, long cancelledCapaCount, long overdueCapaCount) {
        this.id = id;
        this.deviationNumber = deviationNumber;
        this.batchId = batchId;
        this.batchNumber = batchNumber;
        this.batchStatus = batchStatus;
        this.batchQaStatus = batchQaStatus;
        this.orderNumber = orderNumber;
        this.productCode = productCode;
        this.productName = productName;
        this.qualityCheckId = qualityCheckId;
        this.linkedCheckTestName = linkedCheckTestName;
        this.linkedCheckResult = linkedCheckResult;
        this.executionStepId = executionStepId;
        this.linkedStepNumber = linkedStepNumber;
        this.linkedStepOperationName = linkedStepOperationName;
        this.sourceAuditLogId = sourceAuditLogId;
        this.title = title;
        this.category = category;
        this.severity = severity;
        this.priority = priority;
        this.status = status;
        this.reportedBy = reportedBy;
        this.occurredAt = occurredAt;
        this.reportedAt = reportedAt;
        this.closedAt = closedAt;
        this.totalCapaCount = totalCapaCount;
        this.openCapaCount = openCapaCount;
        this.inProgressCapaCount = inProgressCapaCount;
        this.completedCapaCount = completedCapaCount;
        this.cancelledCapaCount = cancelledCapaCount;
        this.overdueCapaCount = overdueCapaCount;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeviationNumber() {
        return deviationNumber;
    }

    public void setDeviationNumber(String deviationNumber) {
        this.deviationNumber = deviationNumber;
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

    public BatchQualityStatus getBatchQaStatus() {
        return batchQaStatus;
    }

    public void setBatchQaStatus(BatchQualityStatus batchQaStatus) {
        this.batchQaStatus = batchQaStatus;
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

    public Long getQualityCheckId() {
        return qualityCheckId;
    }

    public void setQualityCheckId(Long qualityCheckId) {
        this.qualityCheckId = qualityCheckId;
    }

    public String getLinkedCheckTestName() {
        return linkedCheckTestName;
    }

    public void setLinkedCheckTestName(String linkedCheckTestName) {
        this.linkedCheckTestName = linkedCheckTestName;
    }

    public QualityCheckResult getLinkedCheckResult() {
        return linkedCheckResult;
    }

    public void setLinkedCheckResult(QualityCheckResult linkedCheckResult) {
        this.linkedCheckResult = linkedCheckResult;
    }

    public Long getExecutionStepId() {
        return executionStepId;
    }

    public void setExecutionStepId(Long executionStepId) {
        this.executionStepId = executionStepId;
    }

    public Integer getLinkedStepNumber() {
        return linkedStepNumber;
    }

    public void setLinkedStepNumber(Integer linkedStepNumber) {
        this.linkedStepNumber = linkedStepNumber;
    }

    public String getLinkedStepOperationName() {
        return linkedStepOperationName;
    }

    public void setLinkedStepOperationName(String linkedStepOperationName) {
        this.linkedStepOperationName = linkedStepOperationName;
    }

    public Long getSourceAuditLogId() {
        return sourceAuditLogId;
    }

    public void setSourceAuditLogId(Long sourceAuditLogId) {
        this.sourceAuditLogId = sourceAuditLogId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public DeviationCategory getCategory() {
        return category;
    }

    public void setCategory(DeviationCategory category) {
        this.category = category;
    }

    public DeviationSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(DeviationSeverity severity) {
        this.severity = severity;
    }

    public DeviationPriority getPriority() {
        return priority;
    }

    public void setPriority(DeviationPriority priority) {
        this.priority = priority;
    }

    public DeviationStatus getStatus() {
        return status;
    }

    public void setStatus(DeviationStatus status) {
        this.status = status;
    }

    public String getReportedBy() {
        return reportedBy;
    }

    public void setReportedBy(String reportedBy) {
        this.reportedBy = reportedBy;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public LocalDateTime getReportedAt() {
        return reportedAt;
    }

    public void setReportedAt(LocalDateTime reportedAt) {
        this.reportedAt = reportedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public long getTotalCapaCount() {
        return totalCapaCount;
    }

    public void setTotalCapaCount(long totalCapaCount) {
        this.totalCapaCount = totalCapaCount;
    }

    public long getOpenCapaCount() {
        return openCapaCount;
    }

    public void setOpenCapaCount(long openCapaCount) {
        this.openCapaCount = openCapaCount;
    }

    public long getInProgressCapaCount() {
        return inProgressCapaCount;
    }

    public void setInProgressCapaCount(long inProgressCapaCount) {
        this.inProgressCapaCount = inProgressCapaCount;
    }

    public long getCompletedCapaCount() {
        return completedCapaCount;
    }

    public void setCompletedCapaCount(long completedCapaCount) {
        this.completedCapaCount = completedCapaCount;
    }

    public long getCancelledCapaCount() {
        return cancelledCapaCount;
    }

    public void setCancelledCapaCount(long cancelledCapaCount) {
        this.cancelledCapaCount = cancelledCapaCount;
    }

    public long getOverdueCapaCount() {
        return overdueCapaCount;
    }

    public void setOverdueCapaCount(long overdueCapaCount) {
        this.overdueCapaCount = overdueCapaCount;
    }
}

