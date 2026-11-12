package com.pharmatrack.dto;

import com.pharmatrack.entity.ExecutionStepStatus;

import java.time.LocalDateTime;

/**
 * Response payload DTO representing a single snapshotted BatchExecutionStep operation
 * within a production batch.
 */
public class BatchExecutionStepResponseDto {

    private Long id;
    private Integer stepNumber;
    private String operationName;
    private String instructions;
    private ExecutionStepStatus status;
    private String actualValue;
    private String observations;
    private String executedBy;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public BatchExecutionStepResponseDto() {
    }

    public BatchExecutionStepResponseDto(Long id, Integer stepNumber, String operationName,
                                         String instructions, ExecutionStepStatus status,
                                         String actualValue, String observations, String executedBy,
                                         LocalDateTime startedAt, LocalDateTime completedAt) {
        this.id = id;
        this.stepNumber = stepNumber;
        this.operationName = operationName;
        this.instructions = instructions;
        this.status = status;
        this.actualValue = actualValue;
        this.observations = observations;
        this.executedBy = executedBy;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getStepNumber() {
        return stepNumber;
    }

    public void setStepNumber(Integer stepNumber) {
        this.stepNumber = stepNumber;
    }

    public String getOperationName() {
        return operationName;
    }

    public void setOperationName(String operationName) {
        this.operationName = operationName;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public ExecutionStepStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStepStatus status) {
        this.status = status;
    }

    public String getActualValue() {
        return actualValue;
    }

    public void setActualValue(String actualValue) {
        this.actualValue = actualValue;
    }

    public String getObservations() {
        return observations;
    }

    public void setObservations(String observations) {
        this.observations = observations;
    }

    public String getExecutedBy() {
        return executedBy;
    }

    public void setExecutedBy(String executedBy) {
        this.executedBy = executedBy;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}

