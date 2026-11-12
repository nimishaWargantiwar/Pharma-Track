package com.pharmatrack.dto;

import com.pharmatrack.entity.BatchQualityStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for recording a QA disposition decision against a completed batch.
 */
public class QaDispositionRequestDto {

    @NotNull(message = "Target QA disposition status is mandatory")
    private BatchQualityStatus qaStatus;

    @NotBlank(message = "Decision rationale is mandatory")
    @Size(max = 2000, message = "Decision rationale must not exceed 2000 characters")
    private String rationale;

    public QaDispositionRequestDto() {
    }

    public QaDispositionRequestDto(BatchQualityStatus qaStatus, String rationale) {
        this.qaStatus = qaStatus;
        this.rationale = rationale;
    }

    public BatchQualityStatus getQaStatus() {
        return qaStatus;
    }

    public void setQaStatus(BatchQualityStatus qaStatus) {
        this.qaStatus = qaStatus;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }
}

