package com.pharmatrack.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Structured analytical sections for the dedicated AI Batch Analysis Report.
 * Maps with the 12 required analytical dimensions persisted in {@code structured_analysis_json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchAnalysisSectionsDto {

    @JsonProperty("executiveSummary")
    private String executiveSummary;

    @JsonProperty("batchStatusAndProgress")
    private String batchStatusAndProgress;

    @JsonProperty("plannedVsActualMetrics")
    private String plannedVsActualMetrics;

    @JsonProperty("materialTraceability")
    private String materialTraceability;

    @JsonProperty("ebrStepEvaluation")
    private String ebrStepEvaluation;

    @JsonProperty("qcInspectionAnalysis")
    @JsonAlias({"qualitySummary", "qualityControlEvaluation"})
    private String qcInspectionAnalysis;

    @JsonProperty("qaDispositionStatus")
    private String qaDispositionStatus;

    @JsonProperty("deviationsAndCapa")
    private String deviationsAndCapa;

    @JsonProperty("chronologicalAuditAnalysis")
    private String chronologicalAuditAnalysis;

    @JsonProperty("missingRecords")
    private String missingRecords;

    @JsonProperty("riskObservations")
    private String riskObservations;

    @JsonProperty("followUpRecommendations")
    private String followUpRecommendations;

    @JsonProperty("structuredFindings")
    private java.util.List<BatchAnalysisAreaFindingDto> structuredFindings = new java.util.ArrayList<>();

    @JsonProperty("missingRecordAssessments")
    private java.util.List<RecordAssessmentDto> missingRecordAssessments = new java.util.ArrayList<>();

    @JsonProperty("linkedRecommendations")
    private java.util.List<LinkedRecommendationDto> linkedRecommendations = new java.util.ArrayList<>();

    @JsonProperty("batchContextSummary")
    private String batchContextSummary;

    public BatchAnalysisSectionsDto() {
    }

    /**
     * Backward-compatible 7-section constructor from Phase 1.
     */
    public BatchAnalysisSectionsDto(String executiveSummary,
                                   String plannedVsActualMetrics,
                                   String ebrStepEvaluation,
                                   String qualitySummary,
                                   String deviationsAndCapa,
                                   String missingRecords,
                                   String followUpRecommendations) {
        this.executiveSummary = executiveSummary;
        this.plannedVsActualMetrics = plannedVsActualMetrics;
        this.ebrStepEvaluation = ebrStepEvaluation;
        this.qcInspectionAnalysis = qualitySummary;
        this.deviationsAndCapa = deviationsAndCapa;
        this.missingRecords = missingRecords;
        this.followUpRecommendations = followUpRecommendations;
    }

    /**
     * Full 12-section constructor.
     */
    public BatchAnalysisSectionsDto(String executiveSummary,
                                   String batchStatusAndProgress,
                                   String plannedVsActualMetrics,
                                   String materialTraceability,
                                   String ebrStepEvaluation,
                                   String qcInspectionAnalysis,
                                   String qaDispositionStatus,
                                   String deviationsAndCapa,
                                   String chronologicalAuditAnalysis,
                                   String missingRecords,
                                   String riskObservations,
                                   String followUpRecommendations) {
        this.executiveSummary = executiveSummary;
        this.batchStatusAndProgress = batchStatusAndProgress;
        this.plannedVsActualMetrics = plannedVsActualMetrics;
        this.materialTraceability = materialTraceability;
        this.ebrStepEvaluation = ebrStepEvaluation;
        this.qcInspectionAnalysis = qcInspectionAnalysis;
        this.qaDispositionStatus = qaDispositionStatus;
        this.deviationsAndCapa = deviationsAndCapa;
        this.chronologicalAuditAnalysis = chronologicalAuditAnalysis;
        this.missingRecords = missingRecords;
        this.riskObservations = riskObservations;
        this.followUpRecommendations = followUpRecommendations;
    }

    public String getExecutiveSummary() {
        return executiveSummary;
    }

    public void setExecutiveSummary(String executiveSummary) {
        this.executiveSummary = executiveSummary;
    }

    public String getBatchStatusAndProgress() {
        return batchStatusAndProgress;
    }

    public void setBatchStatusAndProgress(String batchStatusAndProgress) {
        this.batchStatusAndProgress = batchStatusAndProgress;
    }

    public String getPlannedVsActualMetrics() {
        return plannedVsActualMetrics;
    }

    public void setPlannedVsActualMetrics(String plannedVsActualMetrics) {
        this.plannedVsActualMetrics = plannedVsActualMetrics;
    }

    public String getMaterialTraceability() {
        return materialTraceability;
    }

    public void setMaterialTraceability(String materialTraceability) {
        this.materialTraceability = materialTraceability;
    }

    public String getEbrStepEvaluation() {
        return ebrStepEvaluation;
    }

    public void setEbrStepEvaluation(String ebrStepEvaluation) {
        this.ebrStepEvaluation = ebrStepEvaluation;
    }

    @JsonProperty("qcInspectionAnalysis")
    public String getQcInspectionAnalysis() {
        return qcInspectionAnalysis;
    }

    public void setQcInspectionAnalysis(String qcInspectionAnalysis) {
        this.qcInspectionAnalysis = qcInspectionAnalysis;
    }

    /**
     * Alias for {@link #getQcInspectionAnalysis()} for Phase 1 backward compatibility.
     */
    @JsonIgnore
    public String getQualitySummary() {
        return qcInspectionAnalysis;
    }

    /**
     * Alias for {@link #setQcInspectionAnalysis(String)} for Phase 1 backward compatibility.
     */
    @JsonIgnore
    public void setQualitySummary(String qualitySummary) {
        this.qcInspectionAnalysis = qualitySummary;
    }

    public String getQaDispositionStatus() {
        return qaDispositionStatus;
    }

    public void setQaDispositionStatus(String qaDispositionStatus) {
        this.qaDispositionStatus = qaDispositionStatus;
    }

    public String getDeviationsAndCapa() {
        return deviationsAndCapa;
    }

    public void setDeviationsAndCapa(String deviationsAndCapa) {
        this.deviationsAndCapa = deviationsAndCapa;
    }

    public String getChronologicalAuditAnalysis() {
        return chronologicalAuditAnalysis;
    }

    public void setChronologicalAuditAnalysis(String chronologicalAuditAnalysis) {
        this.chronologicalAuditAnalysis = chronologicalAuditAnalysis;
    }

    public String getMissingRecords() {
        return missingRecords;
    }

    public void setMissingRecords(String missingRecords) {
        this.missingRecords = missingRecords;
    }

    public String getRiskObservations() {
        return riskObservations;
    }

    public void setRiskObservations(String riskObservations) {
        this.riskObservations = riskObservations;
    }

    public String getFollowUpRecommendations() {
        return followUpRecommendations;
    }

    public void setFollowUpRecommendations(String followUpRecommendations) {
        this.followUpRecommendations = followUpRecommendations;
    }

    public java.util.List<BatchAnalysisAreaFindingDto> getStructuredFindings() {
        return structuredFindings;
    }

    public void setStructuredFindings(java.util.List<BatchAnalysisAreaFindingDto> structuredFindings) {
        this.structuredFindings = structuredFindings != null ? structuredFindings : new java.util.ArrayList<>();
    }

    public java.util.List<RecordAssessmentDto> getMissingRecordAssessments() {
        return missingRecordAssessments;
    }

    public void setMissingRecordAssessments(java.util.List<RecordAssessmentDto> missingRecordAssessments) {
        this.missingRecordAssessments = missingRecordAssessments != null ? missingRecordAssessments : new java.util.ArrayList<>();
    }

    public java.util.List<LinkedRecommendationDto> getLinkedRecommendations() {
        return linkedRecommendations;
    }

    public void setLinkedRecommendations(java.util.List<LinkedRecommendationDto> linkedRecommendations) {
        this.linkedRecommendations = linkedRecommendations != null ? linkedRecommendations : new java.util.ArrayList<>();
    }

    public String getBatchContextSummary() {
        return batchContextSummary;
    }

    public void setBatchContextSummary(String batchContextSummary) {
        this.batchContextSummary = batchContextSummary;
    }
}
