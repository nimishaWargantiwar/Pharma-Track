package com.pharmatrack.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.AuditLogResponseDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.exception.InvalidQualityOperationException;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.util.AppTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Append-only audit trail service recording Quality Control, QA Disposition,
 * and related compliance events without exposing any update or delete operations.
 */
@Service
@Transactional
public class AuditTrailService {

    public static final ZoneId APP_ZONE_ID = AppTime.APP_ZONE_ID;

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditTrailService(AuditLogRepository auditLogRepository) {
        this(auditLogRepository, AppTime.createObjectMapper());
    }

    @Autowired
    public AuditTrailService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper != null ? objectMapper : AppTime.createObjectMapper();
    }

    /**
     * Persist an immutable append-only audit entry.
     */
    public AuditLog recordAudit(String entityType,
                                Long entityId,
                                String entityReference,
                                Long batchId,
                                String action,
                                String actorName,
                                LocalDateTime occurredAt,
                                String reason,
                                Map<String, ?> beforeMap,
                                Map<String, ?> afterMap) {
        if (entityType == null || entityType.trim().isEmpty()) {
            throw new InvalidQualityOperationException("Audit entityType is mandatory");
        }
        if (entityId == null) {
            throw new InvalidQualityOperationException("Audit entityId is mandatory");
        }
        if (action == null || action.trim().isEmpty()) {
            throw new InvalidQualityOperationException("Audit action is mandatory");
        }
        if (actorName == null || actorName.trim().isEmpty()) {
            throw new InvalidQualityOperationException("Audit actorName is mandatory");
        }

        String beforeJson = serializeSnapshot(beforeMap);
        String afterJson = serializeSnapshot(afterMap);
        LocalDateTime effectiveTime = occurredAt != null ? occurredAt : AppTime.nowDateTime();

        AuditLog entry = new AuditLog(
                entityType.trim(),
                entityId,
                entityReference != null ? entityReference.trim() : null,
                batchId,
                action.trim(),
                actorName.trim(),
                effectiveTime,
                reason != null ? reason.trim() : null,
                beforeJson,
                afterJson
        );
        return auditLogRepository.save(entry);
    }

    /**
     * Read-only query returning append-only audit entries ordered newest first,
     * optionally filtered by batchId, entityType, and/or entityId.
     */
    @Transactional(readOnly = true)
    public List<AuditLogResponseDto> getAuditLogs(Long batchId, String entityType, Long entityId) {
        List<AuditLog> logs;
        if (batchId != null) {
            logs = auditLogRepository.findByBatchIdOrderByOccurredAtDescIdDesc(batchId);
        } else if (entityType != null && !entityType.trim().isEmpty() && entityId != null) {
            logs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                    entityType.trim(), entityId);
        } else if (entityType != null && !entityType.trim().isEmpty()) {
            logs = auditLogRepository.findByEntityTypeOrderByOccurredAtDescIdDesc(entityType.trim());
        } else {
            logs = auditLogRepository.findAllByOrderByOccurredAtDescIdDesc();
        }

        if (logs == null || logs.isEmpty()) {
            return List.of();
        }
        return logs.stream()
                .map(this::mapToDto)
                .toList();
    }

    public AuditLogResponseDto mapToDto(AuditLog entry) {
        return new AuditLogResponseDto(
                entry.getId(),
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getEntityReference(),
                entry.getBatchId(),
                entry.getAction(),
                entry.getActorName(),
                entry.getOccurredAt(),
                entry.getReason(),
                entry.getBeforeValues(),
                entry.getAfterValues()
        );
    }

    private String serializeSnapshot(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            return String.valueOf(values);
        }
    }
}

