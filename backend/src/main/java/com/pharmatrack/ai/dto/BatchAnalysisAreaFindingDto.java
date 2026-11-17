package com.pharmatrack.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pharmatrack.ai.entity.BatchAnalysisFocusArea;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured analytical finding for a specific domain focus area.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchAnalysisAreaFindingDto {

    private BatchAnalysisFocusArea focusArea;
    private String title;
    private String status; // CONFORMING, VARIANCE_OBSERVED, INVESTIGATION_REQUIRED, GAP_IDENTIFIED, NOT_APPLICABLE
    private String explanation;
    private List<String> evidenceReferences = new ArrayList<>();

    public BatchAnalysisAreaFindingDto() {
    }

    public BatchAnalysisAreaFindingDto(BatchAnalysisFocusArea focusArea,
                                       String title,
                                       String status,
                                       String explanation,
                                       List<String> evidenceReferences) {
        this.focusArea = focusArea;
        this.title = title;
        this.status = status;
        this.explanation = explanation;
        this.evidenceReferences = evidenceReferences != null ? new ArrayList<>(evidenceReferences) : new ArrayList<>();
    }

    public BatchAnalysisFocusArea getFocusArea() {
        return focusArea;
    }

    public void setFocusArea(BatchAnalysisFocusArea focusArea) {
        this.focusArea = focusArea;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public List<String> getEvidenceReferences() {
        return evidenceReferences;
    }

    public void setEvidenceReferences(List<String> evidenceReferences) {
        this.evidenceReferences = evidenceReferences != null ? new ArrayList<>(evidenceReferences) : new ArrayList<>();
    }
}
