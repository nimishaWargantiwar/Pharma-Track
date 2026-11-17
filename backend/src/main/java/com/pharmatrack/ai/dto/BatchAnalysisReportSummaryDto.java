package com.pharmatrack.ai.dto;

import com.pharmatrack.ai.entity.BatchAnalysisReportStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Lightweight summary DTO representing a historical AI Batch Analysis Report
 * for dropdown selectors and history audit tables.
 */
public class BatchAnalysisReportSummaryDto {

    private Long id;
    private Long batchId;
    private String batchNumber;
    private BatchAnalysisReportStatus status;
    private String model;
    private String clientRequestId;
    private String generatedByUsername;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private BigDecimal yieldPercent;
    private Integer deviationCount;
    private boolean archived;
    private LocalDateTime archivedAt;
    private String archivedByUsername;
    private String archiveReason;
    private com.pharmatrack.ai.entity.BatchAnalysisMode analysisMode = com.pharmatrack.ai.entity.BatchAnalysisMode.FULL;
    private java.util.List<com.pharmatrack.ai.entity.BatchAnalysisFocusArea> focusAreas = new java.util.ArrayList<>();
    private String focusSummary;

    public BatchAnalysisReportSummaryDto() {
    }

    public BatchAnalysisReportSummaryDto(Long id,
                                        Long batchId,
                                        String batchNumber,
                                        BatchAnalysisReportStatus status,
                                        String model,
                                        String clientRequestId,
                                        String generatedByUsername,
                                        LocalDateTime createdAt,
                                        LocalDateTime completedAt,
                                        BigDecimal yieldPercent,
                                        Integer deviationCount) {
        this.id = id;
        this.batchId = batchId;
        this.batchNumber = batchNumber;
        this.status = status;
        this.model = model;
        this.clientRequestId = clientRequestId;
        this.generatedByUsername = generatedByUsername;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.yieldPercent = yieldPercent;
        this.deviationCount = deviationCount;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String getGeneratedByUsername() {
        return generatedByUsername;
    }

    public void setGeneratedByUsername(String generatedByUsername) {
        this.generatedByUsername = generatedByUsername;
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

    public BigDecimal getYieldPercent() {
        return yieldPercent;
    }

    public void setYieldPercent(BigDecimal yieldPercent) {
        this.yieldPercent = yieldPercent;
    }

    public Integer getDeviationCount() {
        return deviationCount;
    }

    public void setDeviationCount(Integer deviationCount) {
        this.deviationCount = deviationCount;
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

    public String getArchivedByUsername() {
        return archivedByUsername;
    }

    public void setArchivedByUsername(String archivedByUsername) {
        this.archivedByUsername = archivedByUsername;
    }

    public String getArchiveReason() {
        return archiveReason;
    }

    public void setArchiveReason(String archiveReason) {
        this.archiveReason = archiveReason;
    }

    public com.pharmatrack.ai.entity.BatchAnalysisMode getAnalysisMode() {
        return analysisMode;
    }

    public void setAnalysisMode(com.pharmatrack.ai.entity.BatchAnalysisMode analysisMode) {
        this.analysisMode = analysisMode != null ? analysisMode : com.pharmatrack.ai.entity.BatchAnalysisMode.FULL;
    }

    public java.util.List<com.pharmatrack.ai.entity.BatchAnalysisFocusArea> getFocusAreas() {
        return focusAreas;
    }

    public void setFocusAreas(java.util.List<com.pharmatrack.ai.entity.BatchAnalysisFocusArea> focusAreas) {
        this.focusAreas = focusAreas != null ? new java.util.ArrayList<>(focusAreas) : new java.util.ArrayList<>();
    }

    public String getFocusSummary() {
        return focusSummary;
    }

    public void setFocusSummary(String focusSummary) {
        this.focusSummary = focusSummary;
    }
}
