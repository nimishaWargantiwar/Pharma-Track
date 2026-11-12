package com.pharmatrack.dto;

import java.time.LocalDateTime;

/**
 * Response payload DTO representing an append-only audit log entry.
 */
public class AuditLogResponseDto {

    private Long id;
    private String entityType;
    private Long entityId;
    private String entityReference;
    private Long batchId;
    private String action;
    private String actorName;
    private LocalDateTime occurredAt;
    private String reason;
    private String beforeValues;
    private String afterValues;

    public AuditLogResponseDto() {
    }

    public AuditLogResponseDto(Long id, String entityType, Long entityId, String entityReference,
                               Long batchId, String action, String actorName, LocalDateTime occurredAt,
                               String reason, String beforeValues, String afterValues) {
        this.id = id;
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
}

