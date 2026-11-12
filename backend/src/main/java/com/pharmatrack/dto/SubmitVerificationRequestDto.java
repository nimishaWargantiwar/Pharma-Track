package com.pharmatrack.dto;

import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for submitting a deviation from {@code CAPA_IN_PROGRESS}
 * into {@code PENDING_VERIFICATION}.
 */
public class SubmitVerificationRequestDto {

    @Size(max = 2000, message = "Submission notes must not exceed 2000 characters")
    private String submissionNotes;

    public SubmitVerificationRequestDto() {
    }

    public SubmitVerificationRequestDto(String submissionNotes) {
        this.submissionNotes = submissionNotes;
    }

    public String getSubmissionNotes() {
        return submissionNotes;
    }

    public void setSubmissionNotes(String submissionNotes) {
        this.submissionNotes = submissionNotes;
    }
}

