package com.pharmatrack.dto;

import com.pharmatrack.entity.CapaStatus;
import com.pharmatrack.entity.CapaType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response payload DTO representing a Corrective or Preventive Action ({@code CAPA}).
 */
public class CapaActionResponseDto {

    private Long id;
    private String capaNumber;
    private Long deviationId;
    private String deviationNumber;
    private CapaType capaType;
    private String title;
    private String actionPlan;
    private String ownerName;
    private LocalDate dueDate;
    private boolean overdue;
    private CapaStatus status;
    private String completionEvidence;
    private String completedBy;
    private LocalDateTime completedAt;
    private String cancellationReason;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public CapaActionResponseDto() {
    }

    public CapaActionResponseDto(Long id, String capaNumber, Long deviationId, String deviationNumber,
                                 CapaType capaType, String title, String actionPlan, String ownerName,
                                 LocalDate dueDate, boolean overdue, CapaStatus status,
                                 String completionEvidence, String completedBy, LocalDateTime completedAt,
                                 String cancellationReason, String createdBy,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.capaNumber = capaNumber;
        this.deviationId = deviationId;
        this.deviationNumber = deviationNumber;
        this.capaType = capaType;
        this.title = title;
        this.actionPlan = actionPlan;
        this.ownerName = ownerName;
        this.dueDate = dueDate;
        this.overdue = overdue;
        this.status = status;
        this.completionEvidence = completionEvidence;
        this.completedBy = completedBy;
        this.completedAt = completedAt;
        this.cancellationReason = cancellationReason;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCapaNumber() {
        return capaNumber;
    }

    public void setCapaNumber(String capaNumber) {
        this.capaNumber = capaNumber;
    }

    public Long getDeviationId() {
        return deviationId;
    }

    public void setDeviationId(Long deviationId) {
        this.deviationId = deviationId;
    }

    public String getDeviationNumber() {
        return deviationNumber;
    }

    public void setDeviationNumber(String deviationNumber) {
        this.deviationNumber = deviationNumber;
    }

    public CapaType getCapaType() {
        return capaType;
    }

    public void setCapaType(CapaType capaType) {
        this.capaType = capaType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getActionPlan() {
        return actionPlan;
    }

    public void setActionPlan(String actionPlan) {
        this.actionPlan = actionPlan;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public boolean isOverdue() {
        return overdue;
    }

    public void setOverdue(boolean overdue) {
        this.overdue = overdue;
    }

    public CapaStatus getStatus() {
        return status;
    }

    public void setStatus(CapaStatus status) {
        this.status = status;
    }

    public String getCompletionEvidence() {
        return completionEvidence;
    }

    public void setCompletionEvidence(String completionEvidence) {
        this.completionEvidence = completionEvidence;
    }

    public String getCompletedBy() {
        return completedBy;
    }

    public void setCompletedBy(String completedBy) {
        this.completedBy = completedBy;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
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
}

