package com.pharmatrack.dto;

import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.QualityCheckResult;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Detailed Deviation workspace response DTO containing batch/order/product metadata,
 * linked QC/execution/audit references, investigation and root-cause analysis,
 * CAPA actions, closure readiness assessment, and append-only audit log history.
 */
public class DeviationDetailDto {

    private Long id;
    private String deviationNumber;
    private Long batchId;
    private String batchNumber;
    private BatchStatus batchStatus;
    private BatchQualityStatus batchQaStatus;
    private Long orderId;
    private String orderNumber;
    private Long productId;
    private String productCode;
    private String productName;
    private String recipeCode;
    private Integer recipeVersion;
    private Long qualityCheckId;
    private String linkedCheckTestName;
    private String linkedCheckSpecification;
    private String linkedCheckMeasuredValue;
    private String linkedCheckUnit;
    private QualityCheckResult linkedCheckResult;
    private Long executionStepId;
    private Integer linkedStepNumber;
    private String linkedStepOperationName;
    private ExecutionStepStatus linkedStepStatus;
    private Long sourceAuditLogId;
    private String title;
    private DeviationCategory category;
    private DeviationSeverity severity;
    private DeviationPriority priority;
    private DeviationStatus status;
    private String description;
    private String immediateContainment;
    private String reportedBy;
    private LocalDateTime occurredAt;
    private LocalDateTime reportedAt;
    private String investigationSummary;
    private String investigatedBy;
    private LocalDateTime investigationStartedAt;
    private String rootCauseCategory;
    private String rootCauseAnalysis;
    private String impactAssessment;
    private String rootCauseIdentifiedBy;
    private LocalDateTime rootCauseIdentifiedAt;
    private String capaPlanSummary;
    private String verifiedBy;
    private String verificationNotes;
    private String closedBy;
    private LocalDateTime closedAt;
    private long totalCapaCount;
    private long openCapaCount;
    private long inProgressCapaCount;
    private long completedCapaCount;
    private long cancelledCapaCount;
    private long overdueCapaCount;
    private boolean eligibleForClosure;
    private List<String> closureBlockers;
    private List<CapaActionResponseDto> capaActions;
    private List<AuditLogResponseDto> auditLogs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public DeviationDetailDto() {
        this.closureBlockers = List.of();
        this.capaActions = List.of();
        this.auditLogs = List.of();
    }

    public DeviationDetailDto(Long id, String deviationNumber, Long batchId, String batchNumber,
                              BatchStatus batchStatus, BatchQualityStatus batchQaStatus,
                              Long orderId, String orderNumber, Long productId,
                              String productCode, String productName, String recipeCode,
                              Integer recipeVersion, Long qualityCheckId,
                              String linkedCheckTestName, String linkedCheckSpecification,
                              String linkedCheckMeasuredValue, String linkedCheckUnit,
                              QualityCheckResult linkedCheckResult, Long executionStepId,
                              Integer linkedStepNumber, String linkedStepOperationName,
                              ExecutionStepStatus linkedStepStatus, Long sourceAuditLogId,
                              String title, DeviationCategory category, DeviationSeverity severity,
                              DeviationPriority priority, DeviationStatus status,
                              String description, String immediateContainment, String reportedBy,
                              LocalDateTime occurredAt, LocalDateTime reportedAt,
                              String investigationSummary, String investigatedBy,
                              LocalDateTime investigationStartedAt, String rootCauseCategory,
                              String rootCauseAnalysis, String impactAssessment,
                              String rootCauseIdentifiedBy, LocalDateTime rootCauseIdentifiedAt,
                              String capaPlanSummary, String verifiedBy, String verificationNotes,
                              String closedBy, LocalDateTime closedAt, long totalCapaCount,
                              long openCapaCount, long inProgressCapaCount, long completedCapaCount,
                              long cancelledCapaCount, long overdueCapaCount,
                              boolean eligibleForClosure, List<String> closureBlockers,
                              List<CapaActionResponseDto> capaActions,
                              List<AuditLogResponseDto> auditLogs,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.deviationNumber = deviationNumber;
        this.batchId = batchId;
        this.batchNumber = batchNumber;
        this.batchStatus = batchStatus;
        this.batchQaStatus = batchQaStatus;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.productId = productId;
        this.productCode = productCode;
        this.productName = productName;
        this.recipeCode = recipeCode;
        this.recipeVersion = recipeVersion;
        this.qualityCheckId = qualityCheckId;
        this.linkedCheckTestName = linkedCheckTestName;
        this.linkedCheckSpecification = linkedCheckSpecification;
        this.linkedCheckMeasuredValue = linkedCheckMeasuredValue;
        this.linkedCheckUnit = linkedCheckUnit;
        this.linkedCheckResult = linkedCheckResult;
        this.executionStepId = executionStepId;
        this.linkedStepNumber = linkedStepNumber;
        this.linkedStepOperationName = linkedStepOperationName;
        this.linkedStepStatus = linkedStepStatus;
        this.sourceAuditLogId = sourceAuditLogId;
        this.title = title;
        this.category = category;
        this.severity = severity;
        this.priority = priority;
        this.status = status;
        this.description = description;
        this.immediateContainment = immediateContainment;
        this.reportedBy = reportedBy;
        this.occurredAt = occurredAt;
        this.reportedAt = reportedAt;
        this.investigationSummary = investigationSummary;
        this.investigatedBy = investigatedBy;
        this.investigationStartedAt = investigationStartedAt;
        this.rootCauseCategory = rootCauseCategory;
        this.rootCauseAnalysis = rootCauseAnalysis;
        this.impactAssessment = impactAssessment;
        this.rootCauseIdentifiedBy = rootCauseIdentifiedBy;
        this.rootCauseIdentifiedAt = rootCauseIdentifiedAt;
        this.capaPlanSummary = capaPlanSummary;
        this.verifiedBy = verifiedBy;
        this.verificationNotes = verificationNotes;
        this.closedBy = closedBy;
        this.closedAt = closedAt;
        this.totalCapaCount = totalCapaCount;
        this.openCapaCount = openCapaCount;
        this.inProgressCapaCount = inProgressCapaCount;
        this.completedCapaCount = completedCapaCount;
        this.cancelledCapaCount = cancelledCapaCount;
        this.overdueCapaCount = overdueCapaCount;
        this.eligibleForClosure = eligibleForClosure;
        this.closureBlockers = closureBlockers != null ? closureBlockers : List.of();
        this.capaActions = capaActions != null ? capaActions : List.of();
        this.auditLogs = auditLogs != null ? auditLogs : List.of();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
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

    public String getLinkedCheckSpecification() {
        return linkedCheckSpecification;
    }

    public void setLinkedCheckSpecification(String linkedCheckSpecification) {
        this.linkedCheckSpecification = linkedCheckSpecification;
    }

    public String getLinkedCheckMeasuredValue() {
        return linkedCheckMeasuredValue;
    }

    public void setLinkedCheckMeasuredValue(String linkedCheckMeasuredValue) {
        this.linkedCheckMeasuredValue = linkedCheckMeasuredValue;
    }

    public String getLinkedCheckUnit() {
        return linkedCheckUnit;
    }

    public void setLinkedCheckUnit(String linkedCheckUnit) {
        this.linkedCheckUnit = linkedCheckUnit;
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

    public ExecutionStepStatus getLinkedStepStatus() {
        return linkedStepStatus;
    }

    public void setLinkedStepStatus(ExecutionStepStatus linkedStepStatus) {
        this.linkedStepStatus = linkedStepStatus;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImmediateContainment() {
        return immediateContainment;
    }

    public void setImmediateContainment(String immediateContainment) {
        this.immediateContainment = immediateContainment;
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

    public String getInvestigationSummary() {
        return investigationSummary;
    }

    public void setInvestigationSummary(String investigationSummary) {
        this.investigationSummary = investigationSummary;
    }

    public String getInvestigatedBy() {
        return investigatedBy;
    }

    public void setInvestigatedBy(String investigatedBy) {
        this.investigatedBy = investigatedBy;
    }

    public LocalDateTime getInvestigationStartedAt() {
        return investigationStartedAt;
    }

    public void setInvestigationStartedAt(LocalDateTime investigationStartedAt) {
        this.investigationStartedAt = investigationStartedAt;
    }

    public String getRootCauseCategory() {
        return rootCauseCategory;
    }

    public void setRootCauseCategory(String rootCauseCategory) {
        this.rootCauseCategory = rootCauseCategory;
    }

    public String getRootCauseAnalysis() {
        return rootCauseAnalysis;
    }

    public void setRootCauseAnalysis(String rootCauseAnalysis) {
        this.rootCauseAnalysis = rootCauseAnalysis;
    }

    public String getImpactAssessment() {
        return impactAssessment;
    }

    public void setImpactAssessment(String impactAssessment) {
        this.impactAssessment = impactAssessment;
    }

    public String getRootCauseIdentifiedBy() {
        return rootCauseIdentifiedBy;
    }

    public void setRootCauseIdentifiedBy(String rootCauseIdentifiedBy) {
        this.rootCauseIdentifiedBy = rootCauseIdentifiedBy;
    }

    public LocalDateTime getRootCauseIdentifiedAt() {
        return rootCauseIdentifiedAt;
    }

    public void setRootCauseIdentifiedAt(LocalDateTime rootCauseIdentifiedAt) {
        this.rootCauseIdentifiedAt = rootCauseIdentifiedAt;
    }

    public String getCapaPlanSummary() {
        return capaPlanSummary;
    }

    public void setCapaPlanSummary(String capaPlanSummary) {
        this.capaPlanSummary = capaPlanSummary;
    }

    public String getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(String verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public String getVerificationNotes() {
        return verificationNotes;
    }

    public void setVerificationNotes(String verificationNotes) {
        this.verificationNotes = verificationNotes;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(String closedBy) {
        this.closedBy = closedBy;
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

    public boolean isEligibleForClosure() {
        return eligibleForClosure;
    }

    public void setEligibleForClosure(boolean eligibleForClosure) {
        this.eligibleForClosure = eligibleForClosure;
    }

    public List<String> getClosureBlockers() {
        return closureBlockers;
    }

    public void setClosureBlockers(List<String> closureBlockers) {
        this.closureBlockers = closureBlockers != null ? closureBlockers : List.of();
    }

    public List<CapaActionResponseDto> getCapaActions() {
        return capaActions;
    }

    public void setCapaActions(List<CapaActionResponseDto> capaActions) {
        this.capaActions = capaActions != null ? capaActions : List.of();
    }

    public List<AuditLogResponseDto> getAuditLogs() {
        return auditLogs;
    }

    public void setAuditLogs(List<AuditLogResponseDto> auditLogs) {
        this.auditLogs = auditLogs != null ? auditLogs : List.of();
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

