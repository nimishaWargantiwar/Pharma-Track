package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for documenting root-cause analysis and impact assessment
 * when transitioning from {@code UNDER_INVESTIGATION} to {@code ROOT_CAUSE_IDENTIFIED}.
 */
public class RecordRootCauseRequestDto {

    @NotBlank(message = "Root cause category is mandatory")
    @Size(max = 100, message = "Root cause category must not exceed 100 characters")
    private String rootCauseCategory;

    @NotBlank(message = "Root cause analysis is mandatory")
    @Size(max = 3000, message = "Root cause analysis must not exceed 3000 characters")
    private String rootCauseAnalysis;

    @NotBlank(message = "Impact assessment is mandatory")
    @Size(max = 2000, message = "Impact assessment must not exceed 2000 characters")
    private String impactAssessment;

    @Size(max = 3000, message = "Investigation summary must not exceed 3000 characters")
    private String investigationSummary;

    public RecordRootCauseRequestDto() {
    }

    public RecordRootCauseRequestDto(String rootCauseCategory,
                                     String rootCauseAnalysis, String impactAssessment,
                                     String investigationSummary) {
        this.rootCauseCategory = rootCauseCategory;
        this.rootCauseAnalysis = rootCauseAnalysis;
        this.impactAssessment = impactAssessment;
        this.investigationSummary = investigationSummary;
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

    public String getInvestigationSummary() {
        return investigationSummary;
    }

    public void setInvestigationSummary(String investigationSummary) {
        this.investigationSummary = investigationSummary;
    }
}

