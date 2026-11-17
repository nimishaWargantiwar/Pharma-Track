package com.pharmatrack.ai.entity;

import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.util.AppTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity representing a persistent, independent AI Batch Analysis Report.
 *
 * <p>Separated completely from multi-turn Copilot conversations.
 * Stores deterministic manufacturing KPIs alongside structured JSON analysis,
 * printable Markdown narrative, and verifiable MES source records.</p>
 */
@Entity
@Table(name = "batch_analysis_reports", uniqueConstraints = {
        @UniqueConstraint(name = "uk_batch_report_client_req", columnNames = "client_request_id")
}, indexes = {
        @Index(name = "idx_batch_report_batch_created", columnList = "batch_id, created_at DESC"),
        @Index(name = "idx_batch_report_user_created", columnList = "generated_by_user_id, created_at DESC"),
        @Index(name = "idx_batch_report_archived", columnList = "batch_id, archived, created_at DESC"),
        @Index(name = "idx_batch_report_mode", columnList = "batch_id, analysis_mode, created_at DESC")
})
public class BatchAnalysisReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotNull(message = "Batch reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false, updatable = false)
    private Batch batch;

    @NotBlank(message = "Batch number snapshot is mandatory")
    @Size(max = 50, message = "Batch number must not exceed 50 characters")
    @Column(name = "batch_number", nullable = false, updatable = false, length = 50)
    private String batchNumber;

    @NotNull(message = "Generating user reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generated_by_user_id", nullable = false, updatable = false)
    private UserAccount generatedByUser;

    @NotBlank(message = "Client request ID is mandatory")
    @Size(max = 64, message = "Client request ID must not exceed 64 characters")
    @Column(name = "client_request_id", nullable = false, updatable = false, length = 64)
    private String clientRequestId;

    @NotNull(message = "Report status is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BatchAnalysisReportStatus status;

    @Size(max = 50, message = "Model identifier must not exceed 50 characters")
    @Column(name = "model", length = 50)
    private String model;

    @Column(name = "planned_batch_size", precision = 12, scale = 4)
    private BigDecimal plannedBatchSize;

    @Column(name = "actual_yield", precision = 12, scale = 4)
    private BigDecimal actualYield;

    @Column(name = "yield_percent", precision = 10, scale = 4)
    private BigDecimal yieldPercent;

    @Column(name = "yield_variance", precision = 12, scale = 4)
    private BigDecimal yieldVariance;

    @Column(name = "execution_step_count")
    private Integer executionStepCount;

    @Column(name = "qc_check_count")
    private Integer qcCheckCount;

    @Column(name = "deviation_count")
    private Integer deviationCount;

    @Column(name = "structured_analysis_json", columnDefinition = "MEDIUMTEXT")
    private String structuredAnalysisJson;

    @Column(name = "report_markdown", columnDefinition = "MEDIUMTEXT")
    private String reportMarkdown;

    @Column(name = "sources_json", columnDefinition = "MEDIUMTEXT")
    private String sourcesJson;

    @Size(max = 1000, message = "Error message must not exceed 1000 characters")
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @NotNull(message = "Creation timestamp is mandatory")
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "archived", nullable = false)
    private boolean archived = false;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archived_by_user_id")
    private UserAccount archivedByUser;

    @Size(max = 500, message = "Archive reason must not exceed 500 characters")
    @Column(name = "archive_reason", length = 500)
    private String archiveReason;

    @NotNull(message = "Analysis mode is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_mode", nullable = false, length = 30)
    private BatchAnalysisMode analysisMode = BatchAnalysisMode.FULL;

    @Column(name = "focus_areas_json", length = 1000)
    private String focusAreasJson;

    @Size(max = 255, message = "Focus summary must not exceed 255 characters")
    @Column(name = "focus_summary", length = 255)
    private String focusSummary;

    public BatchAnalysisReport() {
    }

    public BatchAnalysisReport(Batch batch, UserAccount generatedByUser, String clientRequestId) {
        this(batch, generatedByUser, clientRequestId, BatchAnalysisMode.FULL, null, null);
    }

    public BatchAnalysisReport(Batch batch, UserAccount generatedByUser, String clientRequestId,
                               BatchAnalysisMode analysisMode, String focusAreasJson, String focusSummary) {
        this.batch = batch;
        this.batchNumber = batch != null ? batch.getBatchNumber() : null;
        this.generatedByUser = generatedByUser;
        this.clientRequestId = clientRequestId;
        this.analysisMode = analysisMode != null ? analysisMode : BatchAnalysisMode.FULL;
        this.focusAreasJson = focusAreasJson;
        this.focusSummary = focusSummary;
        this.status = BatchAnalysisReportStatus.GENERATING;
        this.createdAt = AppTime.nowDateTime();
    }

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = AppTime.nowDateTime();
        }
        if (status == null) {
            status = BatchAnalysisReportStatus.GENERATING;
        }
        if (analysisMode == null) {
            analysisMode = BatchAnalysisMode.FULL;
        }
        if (batchNumber == null && batch != null) {
            batchNumber = batch.getBatchNumber();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
        if (batch != null && this.batchNumber == null) {
            this.batchNumber = batch.getBatchNumber();
        }
    }

    public String getBatchNumber() {
        return batchNumber;
    }

    public void setBatchNumber(String batchNumber) {
        this.batchNumber = batchNumber;
    }

    public UserAccount getGeneratedByUser() {
        return generatedByUser;
    }

    public void setGeneratedByUser(UserAccount generatedByUser) {
        this.generatedByUser = generatedByUser;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public BatchAnalysisReportStatus getStatus() {
        return status;
    }

    public void setStatus(BatchAnalysisReportStatus status) {
        this.status = status;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public BigDecimal getPlannedBatchSize() {
        return plannedBatchSize;
    }

    public void setPlannedBatchSize(BigDecimal plannedBatchSize) {
        this.plannedBatchSize = plannedBatchSize;
    }

    public BigDecimal getActualYield() {
        return actualYield;
    }

    public void setActualYield(BigDecimal actualYield) {
        this.actualYield = actualYield;
    }

    public BigDecimal getYieldPercent() {
        return yieldPercent;
    }

    public void setYieldPercent(BigDecimal yieldPercent) {
        this.yieldPercent = yieldPercent;
    }

    public BigDecimal getYieldVariance() {
        return yieldVariance;
    }

    public void setYieldVariance(BigDecimal yieldVariance) {
        this.yieldVariance = yieldVariance;
    }

    public Integer getExecutionStepCount() {
        return executionStepCount;
    }

    public void setExecutionStepCount(Integer executionStepCount) {
        this.executionStepCount = executionStepCount;
    }

    public Integer getQcCheckCount() {
        return qcCheckCount;
    }

    public void setQcCheckCount(Integer qcCheckCount) {
        this.qcCheckCount = qcCheckCount;
    }

    public Integer getDeviationCount() {
        return deviationCount;
    }

    public void setDeviationCount(Integer deviationCount) {
        this.deviationCount = deviationCount;
    }

    public String getStructuredAnalysisJson() {
        return structuredAnalysisJson;
    }

    public void setStructuredAnalysisJson(String structuredAnalysisJson) {
        this.structuredAnalysisJson = structuredAnalysisJson;
    }

    public String getReportMarkdown() {
        return reportMarkdown;
    }

    public void setReportMarkdown(String reportMarkdown) {
        this.reportMarkdown = reportMarkdown;
    }

    public String getSourcesJson() {
        return sourcesJson;
    }

    public void setSourcesJson(String sourcesJson) {
        this.sourcesJson = sourcesJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage != null ? errorMessage.trim() : null;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public void complete(String model,
                         BigDecimal plannedBatchSize,
                         BigDecimal actualYield,
                         BigDecimal yieldPercent,
                         BigDecimal yieldVariance,
                         Integer executionStepCount,
                         Integer qcCheckCount,
                         Integer deviationCount,
                         String structuredAnalysisJson,
                         String reportMarkdown,
                         String sourcesJson) {
        if (this.status == BatchAnalysisReportStatus.COMPLETED) {
            throw new IllegalStateException("Report " + id + " is already COMPLETED and is immutable");
        }
        if (this.status != BatchAnalysisReportStatus.GENERATING) {
            throw new IllegalStateException("Cannot complete report " + id + " from state " + this.status);
        }
        this.status = BatchAnalysisReportStatus.COMPLETED;
        this.model = model;
        this.plannedBatchSize = plannedBatchSize;
        this.actualYield = actualYield;
        this.yieldPercent = yieldPercent;
        this.yieldVariance = yieldVariance;
        this.executionStepCount = executionStepCount;
        this.qcCheckCount = qcCheckCount;
        this.deviationCount = deviationCount;
        this.structuredAnalysisJson = structuredAnalysisJson;
        this.reportMarkdown = reportMarkdown;
        this.sourcesJson = sourcesJson;
        this.errorMessage = null;
        this.completedAt = AppTime.nowDateTime();
    }

    public void fail(String errorMessage) {
        if (this.status == BatchAnalysisReportStatus.COMPLETED) {
            throw new IllegalStateException("Cannot fail report " + id + " because it is already COMPLETED and immutable");
        }
        if (this.status == BatchAnalysisReportStatus.FAILED) {
            return; // Idempotent
        }
        if (this.status != BatchAnalysisReportStatus.GENERATING) {
            throw new IllegalStateException("Cannot fail report " + id + " from state " + this.status);
        }
        this.status = BatchAnalysisReportStatus.FAILED;
        this.errorMessage = errorMessage != null && errorMessage.length() > 1000
                ? errorMessage.substring(0, 1000)
                : errorMessage;
        this.completedAt = AppTime.nowDateTime();
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public LocalDateTime getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(LocalDateTime archivedAt) {
        this.archivedAt = archivedAt;
    }

    public UserAccount getArchivedByUser() {
        return archivedByUser;
    }

    public void setArchivedByUser(UserAccount archivedByUser) {
        this.archivedByUser = archivedByUser;
    }

    public String getArchiveReason() {
        return archiveReason;
    }

    public void setArchiveReason(String archiveReason) {
        this.archiveReason = archiveReason;
    }

    public BatchAnalysisMode getAnalysisMode() {
        return analysisMode;
    }

    public void setAnalysisMode(BatchAnalysisMode analysisMode) {
        this.analysisMode = analysisMode != null ? analysisMode : BatchAnalysisMode.FULL;
    }

    public String getFocusAreasJson() {
        return focusAreasJson;
    }

    public void setFocusAreasJson(String focusAreasJson) {
        this.focusAreasJson = focusAreasJson;
    }

    public String getFocusSummary() {
        return focusSummary;
    }

    public void setFocusSummary(String focusSummary) {
        this.focusSummary = focusSummary;
    }

    public java.util.List<BatchAnalysisFocusArea> getFocusAreas() {
        return BatchAnalysisFocusArea.fromJson(this.focusAreasJson);
    }

    public void setFocusAreas(java.util.List<BatchAnalysisFocusArea> areas) {
        this.focusAreasJson = BatchAnalysisFocusArea.toJson(areas);
        this.focusSummary = BatchAnalysisFocusArea.toSummary(areas);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BatchAnalysisReport other)) return false;
        if (getId() == null || other.getId() == null) {
            return false;
        }
        return Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return BatchAnalysisReport.class.hashCode();
    }

    @Override
    public String toString() {
        return "BatchAnalysisReport{" +
                "id=" + id +
                ", batchId=" + (batch != null ? batch.getId() : null) +
                ", batchNumber='" + batchNumber + '\'' +
                ", clientRequestId='" + clientRequestId + '\'' +
                ", status=" + status +
                ", model='" + model + '\'' +
                ", createdAt=" + createdAt +
                ", completedAt=" + completedAt +
                '}';
    }
}
