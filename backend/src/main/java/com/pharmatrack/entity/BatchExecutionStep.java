package com.pharmatrack.entity;

import com.pharmatrack.util.AppTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity representing the shop-floor execution record of a single manufacturing
 * operation step within a physical production {@link Batch}.
 * Snapshots the originating {@link RecipeStep} sequence, operation name, and instructions
 * so each batch maintains a self-contained execution record.
 */
@Entity
@Table(name = "batch_execution_steps", uniqueConstraints = {
        @UniqueConstraint(name = "uk_batch_execution_step", columnNames = {"batch_id", "step_number"})
})
public class BatchExecutionStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotNull(message = "Batch reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

    @NotNull(message = "Recipe step reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_step_id", nullable = false)
    private RecipeStep recipeStep;

    @NotNull(message = "Step number is mandatory")
    @Min(value = 1, message = "Step number must be at least 1")
    @Column(name = "step_number", nullable = false)
    private Integer stepNumber;

    @NotBlank(message = "Operation name is mandatory")
    @Size(max = 255, message = "Operation name must not exceed 255 characters")
    @Column(name = "operation_name", nullable = false, length = 255)
    private String operationName;

    @NotBlank(message = "Instructions are mandatory")
    @Column(name = "instructions", nullable = false, columnDefinition = "TEXT")
    private String instructions;

    @NotNull(message = "Status is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExecutionStepStatus status = ExecutionStepStatus.PENDING;

    @Size(max = 255, message = "Actual value must not exceed 255 characters")
    @Column(name = "actual_value", length = 255)
    private String actualValue;

    @Column(name = "observations", columnDefinition = "TEXT")
    private String observations;

    @Size(max = 100, message = "Executed by must not exceed 100 characters")
    @Column(name = "executed_by", length = 100)
    private String executedBy;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public BatchExecutionStep() {
    }

    public BatchExecutionStep(Batch batch, RecipeStep recipeStep, Integer stepNumber,
                              String operationName, String instructions) {
        this.batch = batch;
        this.recipeStep = recipeStep;
        this.stepNumber = stepNumber;
        this.operationName = operationName;
        this.instructions = instructions;
        this.status = ExecutionStepStatus.PENDING;
    }

    public BatchExecutionStep(Batch batch, RecipeStep recipeStep, Integer stepNumber,
                              String operationName, String instructions, ExecutionStepStatus status) {
        this.batch = batch;
        this.recipeStep = recipeStep;
        this.stepNumber = stepNumber;
        this.operationName = operationName;
        this.instructions = instructions;
        this.status = status;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = AppTime.nowDateTime();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = ExecutionStepStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = AppTime.nowDateTime();
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
    }

    public RecipeStep getRecipeStep() {
        return recipeStep;
    }

    public void setRecipeStep(RecipeStep recipeStep) {
        this.recipeStep = recipeStep;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BatchExecutionStep)) return false;
        BatchExecutionStep other = (BatchExecutionStep) o;
        return getId() != null && Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return BatchExecutionStep.class.hashCode();
    }

    @Override
    public String toString() {
        return "BatchExecutionStep{" +
                "id=" + id +
                ", stepNumber=" + stepNumber +
                ", operationName='" + operationName + '\'' +
                ", status=" + status +
                ", actualValue='" + actualValue + '\'' +
                ", executedBy='" + executedBy + '\'' +
                ", startedAt=" + startedAt +
                ", completedAt=" + completedAt +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

