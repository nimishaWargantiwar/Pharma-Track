package com.pharmatrack.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for archiving an AI Batch Analysis Report.
 */
public class ArchiveReportRequestDto {

    @NotBlank(message = "Archive reason is mandatory and cannot be blank")
    @Size(max = 500, message = "Archive reason must not exceed 500 characters")
    private String reason;

    public ArchiveReportRequestDto() {
    }

    public ArchiveReportRequestDto(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
