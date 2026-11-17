package com.pharmatrack.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pharmatrack.ai.entity.RecordLifecycleClassification;

/**
 * Structured assessment for an individual batch record type according to its manufacturing lifecycle stage.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordAssessmentDto {

    private String recordType;
    private RecordLifecycleClassification classification;
    private String explanation;
    private String evidenceReference;
    private Boolean dataInsufficient;

    public RecordAssessmentDto() {
    }

    public RecordAssessmentDto(String recordType,
                               RecordLifecycleClassification classification,
                               String explanation,
                               String evidenceReference) {
        this(recordType, classification, explanation, evidenceReference, Boolean.FALSE);
    }

    public RecordAssessmentDto(String recordType,
                               RecordLifecycleClassification classification,
                               String explanation,
                               String evidenceReference,
                               Boolean dataInsufficient) {
        this.recordType = recordType;
        this.classification = classification;
        this.explanation = explanation;
        this.evidenceReference = evidenceReference;
        this.dataInsufficient = dataInsufficient;
    }

    public String getRecordType() {
        return recordType;
    }

    public void setRecordType(String recordType) {
        this.recordType = recordType;
    }

    public RecordLifecycleClassification getClassification() {
        return classification;
    }

    public void setClassification(RecordLifecycleClassification classification) {
        this.classification = classification;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getEvidenceReference() {
        return evidenceReference;
    }

    public void setEvidenceReference(String evidenceReference) {
        this.evidenceReference = evidenceReference;
    }

    public Boolean getDataInsufficient() {
        return dataInsufficient;
    }

    public void setDataInsufficient(Boolean dataInsufficient) {
        this.dataInsufficient = dataInsufficient;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public com.pharmatrack.ai.entity.LifecycleRecordType getLifecycleRecordType() {
        return com.pharmatrack.ai.entity.LifecycleRecordType.fromRecordType(recordType);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public com.pharmatrack.ai.entity.BatchAnalysisFocusArea getFocusArea() {
        return com.pharmatrack.ai.entity.LifecycleRecordType.mapRecordTypeToFocusArea(recordType);
    }
}
