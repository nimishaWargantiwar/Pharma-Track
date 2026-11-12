package com.pharmatrack.dto;

import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Request payload DTO for reporting a new manufacturing or quality deviation.
 */
public class CreateDeviationRequestDto {

    @NotNull(message = "Affected batch ID is mandatory")
    private Long batchId;

    private Long qualityCheckId;

    private Long executionStepId;

    private Long sourceAuditLogId;

    @NotBlank(message = "Deviation title is mandatory")
    @Size(max = 200, message = "Deviation title must not exceed 200 characters")
    private String title;

    @NotNull(message = "Deviation category is mandatory")
    private DeviationCategory category;

    @NotNull(message = "Deviation severity is mandatory")
    private DeviationSeverity severity;

    @NotNull(message = "Deviation priority is mandatory")
    private DeviationPriority priority;

    @NotBlank(message = "Deviation description is mandatory")
    @Size(max = 3000, message = "Deviation description must not exceed 3000 characters")
    private String description;

    @Size(max = 2000, message = "Immediate containment action must not exceed 2000 characters")
    private String immediateContainment;

    private LocalDateTime occurredAt;

    public CreateDeviationRequestDto() {
    }

    public CreateDeviationRequestDto(Long batchId, Long qualityCheckId, Long executionStepId,
                                     Long sourceAuditLogId, String title, DeviationCategory category,
                                     DeviationSeverity severity, DeviationPriority priority,
                                     String description, String immediateContainment,
                                     LocalDateTime occurredAt) {
        this.batchId = batchId;
        this.qualityCheckId = qualityCheckId;
        this.executionStepId = executionStepId;
        this.sourceAuditLogId = sourceAuditLogId;
        this.title = title;
        this.category = category;
        this.severity = severity;
        this.priority = priority;
        this.description = description;
        this.immediateContainment = immediateContainment;
        this.occurredAt = occurredAt;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getQualityCheckId() {
        return qualityCheckId;
    }

    public void setQualityCheckId(Long qualityCheckId) {
        this.qualityCheckId = qualityCheckId;
    }

    public Long getExecutionStepId() {
        return executionStepId;
    }

    public void setExecutionStepId(Long executionStepId) {
        this.executionStepId = executionStepId;
    }

    public Long getSourceAuditLogId() {
        return sourceAuditLogId;
    }

    public void setSourceAuditLogId(Long sourceAuditLogId) {
        this.sourceAuditLogId = sourceAuditLogId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public DeviationCategory getCategory() {
        return category;
    }

    public void setCategory(DeviationCategory category) {
        this.category = category;
    }

    public DeviationSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(DeviationSeverity severity) {
        this.severity = severity;
    }

    public DeviationPriority getPriority() {
        return priority;
    }

    public void setPriority(DeviationPriority priority) {
        this.priority = priority;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImmediateContainment() {
        return immediateContainment;
    }

    public void setImmediateContainment(String immediateContainment) {
        this.immediateContainment = immediateContainment;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }
}

