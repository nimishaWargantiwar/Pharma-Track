package com.pharmatrack.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pharmatrack.ai.entity.BatchAnalysisFocusArea;

/**
 * Structured actionable recommendation linked to specific findings and focus areas.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LinkedRecommendationDto {

    private int number;
    private String recommendation;
    private BatchAnalysisFocusArea focusArea;
    private String rationale;
    private String evidenceReference;

    public LinkedRecommendationDto() {
    }

    public LinkedRecommendationDto(int number,
                                   String recommendation,
                                   BatchAnalysisFocusArea focusArea,
                                   String rationale,
                                   String evidenceReference) {
        this.number = number;
        this.recommendation = recommendation;
        this.focusArea = focusArea;
        this.rationale = rationale;
        this.evidenceReference = evidenceReference;
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    public BatchAnalysisFocusArea getFocusArea() {
        return focusArea;
    }

    public void setFocusArea(BatchAnalysisFocusArea focusArea) {
        this.focusArea = focusArea;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getEvidenceReference() {
        return evidenceReference;
    }

    public void setEvidenceReference(String evidenceReference) {
        this.evidenceReference = evidenceReference;
    }
}
