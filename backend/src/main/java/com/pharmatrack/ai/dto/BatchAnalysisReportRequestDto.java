package com.pharmatrack.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pharmatrack.ai.entity.BatchAnalysisFocusArea;
import com.pharmatrack.ai.entity.BatchAnalysisMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Request payload for initiating an AI Batch Analysis Report generation.
 *
 * <p>Supports {@link BatchAnalysisMode#FULL} (default, 12-section comprehensive report)
 * and {@link BatchAnalysisMode#FOCUSED} (requires at least one valid focus area).
 * Extraneous or unexpected fields are strictly rejected via {@link #handleUnknownProperty(String, Object)}.</p>
 */
public class BatchAnalysisReportRequestDto {

    @NotBlank(message = "Client request ID is mandatory for idempotency tracking")
    @Size(min = 8, max = 64, message = "Client request ID must be between 8 and 64 characters")
    private String clientRequestId;

    @JsonProperty("analysisMode")
    @JsonAlias({"mode"})
    private BatchAnalysisMode analysisMode = BatchAnalysisMode.FULL;

    @JsonProperty("focusAreas")
    private List<BatchAnalysisFocusArea> focusAreas = new ArrayList<>();

    public BatchAnalysisReportRequestDto() {
    }

    public BatchAnalysisReportRequestDto(String clientRequestId) {
        this(clientRequestId, BatchAnalysisMode.FULL, null);
    }

    public BatchAnalysisReportRequestDto(String clientRequestId,
                                        BatchAnalysisMode analysisMode,
                                        List<BatchAnalysisFocusArea> focusAreas) {
        this.clientRequestId = clientRequestId;
        this.analysisMode = analysisMode != null ? analysisMode : BatchAnalysisMode.FULL;
        this.focusAreas = focusAreas != null ? new ArrayList<>(focusAreas) : new ArrayList<>();
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public BatchAnalysisMode getAnalysisMode() {
        return analysisMode != null ? analysisMode : BatchAnalysisMode.FULL;
    }

    public void setAnalysisMode(BatchAnalysisMode analysisMode) {
        this.analysisMode = analysisMode != null ? analysisMode : BatchAnalysisMode.FULL;
    }

    public List<BatchAnalysisFocusArea> getFocusAreas() {
        return focusAreas;
    }

    public void setFocusAreas(List<BatchAnalysisFocusArea> focusAreas) {
        this.focusAreas = focusAreas != null ? new ArrayList<>(focusAreas) : new ArrayList<>();
    }

    @JsonIgnore
    @AssertTrue(message = "At least one focus area is required for FOCUSED analysis mode")
    public boolean isFocusedModeValid() {
        if (getAnalysisMode() == BatchAnalysisMode.FOCUSED) {
            return focusAreas != null && !focusAreas.isEmpty();
        }
        return true;
    }

    @JsonIgnore
    @AssertTrue(message = "Focus areas are not permitted for FULL analysis mode")
    public boolean isFullModeValid() {
        if (getAnalysisMode() == BatchAnalysisMode.FULL) {
            return focusAreas == null || focusAreas.isEmpty();
        }
        return true;
    }

    @JsonIgnore
    @AssertTrue(message = "Duplicate focus areas are not permitted")
    public boolean hasNoDuplicateFocusAreas() {
        if (focusAreas == null || focusAreas.isEmpty()) {
            return true;
        }
        Set<BatchAnalysisFocusArea> unique = new HashSet<>(focusAreas);
        return unique.size() == focusAreas.size();
    }

    @JsonAnySetter
    public void handleUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unrecognized field: " + name);
    }
}
