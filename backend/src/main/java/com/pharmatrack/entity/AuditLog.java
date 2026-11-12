package com.pharmatrack.entity;

import com.pharmatrack.util.AppTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Append-only audit log entry recording entity type, entity ID, action,
 * actor name, timestamp, reason, and before/after value snapshots.
 * Updates and deletions are forbidden.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_logs_batch_id", columnList = "batch_id"),
        @Index(name = "idx_audit_logs_occurred_at", columnList = "occurred_at")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotBlank(message = "Entity type is mandatory")
    @Size(max = 60, message = "Entity type must not exceed 60 characters")
    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @NotNull(message = "Entity ID is mandatory")
    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Size(max = 100, message = "Entity reference must not exceed 100 characters")
    @Column(name = "entity_reference", length = 100)
    private String entityReference;

    @Column(name = "batch_id")
    private Long batchId;

    @NotBlank(message = "Action is mandatory")
    @Size(max = 60, message = "Action must not exceed 60 characters")
    @Column(name = "action", nullable = false, length = 60)
    private String action;

    @NotBlank(message = "Actor name is mandatory")
    @Size(max = 100, message = "Actor name must not exceed 100 characters")
    @Column(name = "actor_name", nullable = false, length = 100)
    private String actorName;

    @NotNull(message = "Occurrence timestamp is mandatory")
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Size(max = 2000, message = "Reason must not exceed 2000 characters")
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "before_values", columnDefinition = "TEXT")
    private String beforeValues;

    @Column(name = "after_values", columnDefinition = "TEXT")
    private String afterValues;

    public AuditLog() {
    }

    public AuditLog(String entityType, Long entityId, String entityReference, Long batchId,
                    String action, String actorName, LocalDateTime occurredAt,
                    String reason, String beforeValues, String afterValues) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.entityReference = entityReference;
        this.batchId = batchId;
        this.action = action;
        this.actorName = actorName;
        this.occurredAt = occurredAt;
        this.reason = reason;
        this.beforeValues = beforeValues;
        this.afterValues = afterValues;
    }

    @PrePersist
    protected void onCreate() {
        if (this.occurredAt == null) {
            this.occurredAt = AppTime.nowDateTime();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        throw new UnsupportedOperationException("Audit log entries are append-only and cannot be modified");
    }

    @PreRemove
    protected void onRemove() {
        throw new UnsupportedOperationException("Audit log entries are append-only and cannot be deleted");
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public void setEntityId(Long entityId) {
        this.entityId = entityId;
    }

    public String getEntityReference() {
        return entityReference;
    }

    public void setEntityReference(String entityReference) {
        this.entityReference = entityReference;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getBeforeValues() {
        return beforeValues;
    }

    public void setBeforeValues(String beforeValues) {
        this.beforeValues = beforeValues;
    }

    public String getAfterValues() {
        return afterValues;
    }

    public void setAfterValues(String afterValues) {
        this.afterValues = afterValues;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditLog)) return false;
        AuditLog other = (AuditLog) o;
        if (getId() == null || other.getId() == null) {
            return false;
        }
        return Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return AuditLog.class.hashCode();
    }
}

