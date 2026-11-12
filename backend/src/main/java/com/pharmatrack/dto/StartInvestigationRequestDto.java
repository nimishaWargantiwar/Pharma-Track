package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for transitioning a deviation from {@code OPEN} to {@code UNDER_INVESTIGATION}.
 */
public class StartInvestigationRequestDto {

    @NotBlank(message = "Investigation summary is mandatory")
    @Size(max = 3000, message = "Investigation summary must not exceed 3000 characters")
    private String investigationSummary;

    public StartInvestigationRequestDto() {
    }

    public StartInvestigationRequestDto(String investigationSummary) {
        this.investigationSummary = investigationSummary;
    }

    public String getInvestigationSummary() {
        return investigationSummary;
    }

    public void setInvestigationSummary(String investigationSummary) {
        this.investigationSummary = investigationSummary;
    }
}

