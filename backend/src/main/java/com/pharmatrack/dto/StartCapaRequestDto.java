package com.pharmatrack.dto;

import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for transitioning a deviation from {@code ROOT_CAUSE_IDENTIFIED}
 * into {@code CAPA_IN_PROGRESS}.
 */
public class StartCapaRequestDto {

    @Size(max = 2000, message = "CAPA plan summary must not exceed 2000 characters")
    private String capaPlanSummary;

    public StartCapaRequestDto() {
    }

    public StartCapaRequestDto(String capaPlanSummary) {
        this.capaPlanSummary = capaPlanSummary;
    }

    public String getCapaPlanSummary() {
        return capaPlanSummary;
    }

    public void setCapaPlanSummary(String capaPlanSummary) {
        this.capaPlanSummary = capaPlanSummary;
    }
}

