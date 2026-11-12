package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for completing a CAPA action with mandatory completion evidence.
 */
public class CompleteCapaActionRequestDto {

    @NotBlank(message = "Completion evidence is mandatory")
    @Size(max = 2000, message = "Completion evidence must not exceed 2000 characters")
    private String completionEvidence;

    public CompleteCapaActionRequestDto() {
    }

    public CompleteCapaActionRequestDto(String completionEvidence) {
        this.completionEvidence = completionEvidence;
    }

    public String getCompletionEvidence() {
        return completionEvidence;
    }

    public void setCompletionEvidence(String completionEvidence) {
        this.completionEvidence = completionEvidence;
    }
}

