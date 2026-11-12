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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity representing a Corrective or Preventive Action ({@code CAPA})
 * owned and tracked under a parent {@link Deviation}.
 */
@Entity
@Table(
        name = "capa_actions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_capa_number", columnNames = "capa_number")
        },
        indexes = {
                @Index(name = "idx_capa_actions_deviation_id", columnList = "deviation_id"),
                @Index(name = "idx_capa_actions_status_due", columnList = "status, due_date")
        }
)
public class CapaAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotBlank(message = "CAPA number is mandatory")
    @Size(max = 50, message = "CAPA number must not exceed 50 characters")
    @Column(name = "capa_number", nullable = false, unique = true, length = 50)
    private String capaNumber;

    @NotNull(message = "Parent deviation reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deviation_id", nullable = false, updatable = false)
    private Deviation deviation;

    @NotNull(message = "CAPA type is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "capa_type", nullable = false, length = 20)
    private CapaType capaType;

    @NotBlank(message = "CAPA title is mandatory")
    @Size(max = 200, message = "CAPA title must not exceed 200 characters")
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @NotBlank(message = "CAPA action plan is mandatory")
    @Size(max = 2000, message = "CAPA action plan must not exceed 2000 characters")
    @Column(name = "action_plan", nullable = false, columnDefinition = "TEXT")
    private String actionPlan;

    @NotBlank(message = "Action owner name is mandatory")
    @Size(max = 100, message = "Action owner name must not exceed 100 characters")
    @Column(name = "owner_name", nullable = false, length = 100)
    private String ownerName;

    @NotNull(message = "Due date is mandatory")
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @NotNull(message = "CAPA status is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CapaStatus status;

    @Size(max = 2000, message = "Completion evidence must not exceed 2000 characters")
    @Column(name = "completion_evidence", columnDefinition = "TEXT")
    private String completionEvidence;

    @Size(max = 100, message = "Completed by must not exceed 100 characters")
    @Column(name = "completed_by", length = 100)
    private String completedBy;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Size(max = 1000, message = "Cancellation reason must not exceed 1000 characters")
    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @NotBlank(message = "Creator name is mandatory")
    @Size(max = 100, message = "Creator name must not exceed 100 characters")
    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CapaAction() {
    }

    public CapaAction(String capaNumber, Deviation deviation, CapaType capaType,
                      String title, String actionPlan, String ownerName,
                      LocalDate dueDate, CapaStatus status, String createdBy) {
        this.capaNumber = capaNumber;
        this.deviation = deviation;
        this.capaType = capaType;
        this.title = title;
        this.actionPlan = actionPlan;
        this.ownerName = ownerName;
        this.dueDate = dueDate;
        this.status = status != null ? status : CapaStatus.OPEN;
        this.createdBy = createdBy;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = AppTime.nowDateTime();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = CapaStatus.OPEN;
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

    public String getCapaNumber() {
        return capaNumber;
    }

    public void setCapaNumber(String capaNumber) {
        this.capaNumber = capaNumber;
    }

    public Deviation getDeviation() {
        return deviation;
    }

    public void setDeviation(Deviation deviation) {
        this.deviation = deviation;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CapaAction)) return false;
        CapaAction other = (CapaAction) o;
        String thisNumber = getCapaNumber();
        String otherNumber = other.getCapaNumber();
        if (thisNumber == null || otherNumber == null) {
            return false;
        }
        return Objects.equals(thisNumber, otherNumber);
    }

    @Override
    public int hashCode() {
        return CapaAction.class.hashCode();
    }

    @Override
    public String toString() {
        return "CapaAction{" +
                "id=" + id +
                ", capaNumber='" + capaNumber + '\'' +
                ", capaType=" + capaType +
                ", title='" + title + '\'' +
                ", ownerName='" + ownerName + '\'' +
                ", dueDate=" + dueDate +
                ", status=" + status +
                '}';
    }
}

