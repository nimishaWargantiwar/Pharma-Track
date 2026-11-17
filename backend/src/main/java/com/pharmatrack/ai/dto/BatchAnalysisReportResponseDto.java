package com.pharmatrack.ai.dto;

import com.pharmatrack.ai.entity.BatchAnalysisReportStatus;
import com.pharmatrack.dto.AiSourceReferenceDto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Authoritative response DTO representing a complete AI Batch Analysis Report.
 *
 * <p>Strictly segregates verified MES facts & deterministic calculations from
 * generative AI observations, missing-data warnings, and advisory recommendations.
 * Mandates a prominent regulatory disclaimer per GxP (EU Annex 16 / 21 CFR Part 211).</p>
 */
public class BatchAnalysisReportResponseDto {

    public static final String REGULATORY_DISCLAIMER =
            "ADVISORY ONLY: This AI-generated batch analysis report is for operational oversight and decision-support " +
            "purposes only. It does not constitute formal Quality Assurance batch release authorization or supersede " +
            "Qualified Person (QP) review under applicable GxP regulations (EU GMP Annex 16 / 21 CFR Part 211). " +
            "Formal release requires qualified human authorization.";

    private Long id;
    private Long batchId;
    private String batchNumber;
    private BatchAnalysisReportStatus status;
    private String model;
    private String clientRequestId;
    private String generatedByUsername;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private String errorMessage;
    private boolean archived;
    private LocalDateTime archivedAt;
    private String archivedByUsername;
    private String archiveReason;
    private com.pharmatrack.ai.entity.BatchAnalysisMode analysisMode = com.pharmatrack.ai.entity.BatchAnalysisMode.FULL;
    private List<com.pharmatrack.ai.entity.BatchAnalysisFocusArea> focusAreas = new ArrayList<>();
    private String focusSummary;
    private List<BatchAnalysisAreaFindingDto> structuredFindings = new ArrayList<>();
    private List<RecordAssessmentDto> missingRecordAssessments = new ArrayList<>();
    private List<LinkedRecommendationDto> linkedRecommendations = new ArrayList<>();
    private String batchContextSummary;

    /**
     * Authoritative, verified MES facts and Java-computed deterministic calculations.
     */
    private BatchAnalysisMetricsDto deterministicMetrics;

    /**
     * AI-generated structured analytical sections and qualitative interpretations.
     */
    private BatchAnalysisSectionsDto structuredAnalysis;

    /**
     * Complete rendered Markdown narrative for human review, printing, or export.
     */
    private String reportMarkdown;

    /**
     * Verifiable MES database records cited as evidence for this analysis.
     */
    private List<AiSourceReferenceDto> sourceReferences = new ArrayList<>();

    /**
     * Explicit operational warnings for missing, unexecuted, or pending records.
     */
    private List<String> missingDataWarnings = new ArrayList<>();

    /**
     * Forward-looking advisory recommendations for Production Supervisors and QA.
     */
    private List<String> advisoryRecommendations = new ArrayList<>();

    /**
     * Prominent GxP regulatory non-actionability disclaimer.
     */
    private String regulatoryDisclaimer = REGULATORY_DISCLAIMER;

    public BatchAnalysisReportResponseDto() {
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

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public BatchAnalysisMetricsDto getDeterministicMetrics() {
        return deterministicMetrics;
    }

    public void setDeterministicMetrics(BatchAnalysisMetricsDto deterministicMetrics) {
        this.deterministicMetrics = deterministicMetrics;
    }

    public BatchAnalysisSectionsDto getStructuredAnalysis() {
        return structuredAnalysis;
    }

    public void setStructuredAnalysis(BatchAnalysisSectionsDto structuredAnalysis) {
        this.structuredAnalysis = structuredAnalysis;
    }

    public String getReportMarkdown() {
        return reportMarkdown;
    }

    public void setReportMarkdown(String reportMarkdown) {
        this.reportMarkdown = reportMarkdown;
    }

    public List<AiSourceReferenceDto> getSourceReferences() {
        return sourceReferences;
    }

    public void setSourceReferences(List<AiSourceReferenceDto> sourceReferences) {
        this.sourceReferences = sourceReferences != null ? sourceReferences : new ArrayList<>();
    }

    public List<String> getMissingDataWarnings() {
        return missingDataWarnings;
    }

    public void setMissingDataWarnings(List<String> missingDataWarnings) {
        this.missingDataWarnings = missingDataWarnings != null ? missingDataWarnings : new ArrayList<>();
    }

    public List<String> getAdvisoryRecommendations() {
        return advisoryRecommendations;
    }

    public void setAdvisoryRecommendations(List<String> advisoryRecommendations) {
        this.advisoryRecommendations = advisoryRecommendations != null ? advisoryRecommendations : new ArrayList<>();
    }

    public String getRegulatoryDisclaimer() {
        return regulatoryDisclaimer;
    }

    public void setRegulatoryDisclaimer(String regulatoryDisclaimer) {
        this.regulatoryDisclaimer = regulatoryDisclaimer != null ? regulatoryDisclaimer : REGULATORY_DISCLAIMER;
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

    public List<com.pharmatrack.ai.entity.BatchAnalysisFocusArea> getFocusAreas() {
        return focusAreas;
    }

    public void setFocusAreas(List<com.pharmatrack.ai.entity.BatchAnalysisFocusArea> focusAreas) {
        this.focusAreas = focusAreas != null ? new ArrayList<>(focusAreas) : new ArrayList<>();
    }

    public String getFocusSummary() {
        return focusSummary;
    }

    public void setFocusSummary(String focusSummary) {
        this.focusSummary = focusSummary;
    }

    public List<BatchAnalysisAreaFindingDto> getStructuredFindings() {
        return structuredFindings;
    }

    public void setStructuredFindings(List<BatchAnalysisAreaFindingDto> structuredFindings) {
        this.structuredFindings = structuredFindings != null ? structuredFindings : new ArrayList<>();
    }

    public List<RecordAssessmentDto> getMissingRecordAssessments() {
        return missingRecordAssessments;
    }

    public void setMissingRecordAssessments(List<RecordAssessmentDto> missingRecordAssessments) {
        this.missingRecordAssessments = missingRecordAssessments != null ? missingRecordAssessments : new ArrayList<>();
    }

    public List<LinkedRecommendationDto> getLinkedRecommendations() {
        return linkedRecommendations;
    }

    public void setLinkedRecommendations(List<LinkedRecommendationDto> linkedRecommendations) {
        this.linkedRecommendations = linkedRecommendations != null ? linkedRecommendations : new ArrayList<>();
    }

    public String getBatchContextSummary() {
        return batchContextSummary;
    }

    public void setBatchContextSummary(String batchContextSummary) {
        this.batchContextSummary = batchContextSummary;
    }
}
