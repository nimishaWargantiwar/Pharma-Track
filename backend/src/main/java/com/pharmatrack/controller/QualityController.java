package com.pharmatrack.controller;

import com.pharmatrack.dto.AuditLogResponseDto;
import com.pharmatrack.dto.BatchQualityDossierDto;
import com.pharmatrack.dto.QaDispositionRequestDto;
import com.pharmatrack.dto.QualityCheckCorrectionRequestDto;
import com.pharmatrack.dto.QualityCheckRequestDto;
import com.pharmatrack.dto.QualityCheckResponseDto;
import com.pharmatrack.dto.QualityQueueItemDto;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.QualityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for Quality Management & Compliance Phase 1:
 * - Quality Inspection Queue
 * - Batch Quality Dossier
 * - QC Check creation and audited correction
 * - QA Batch Disposition
 * - Append-only Audit Trail queries (strictly read-only; no PUT/PATCH/DELETE)
 */
@RestController
@RequestMapping("/api/quality")
public class QualityController {

    private final QualityService qualityService;
    private final AuditTrailService auditTrailService;

    public QualityController(QualityService qualityService, AuditTrailService auditTrailService) {
        this.qualityService = qualityService;
        this.auditTrailService = auditTrailService;
    }

    /**
     * Retrieve completed manufacturing batches eligible for QA review.
     * GET /api/quality/batches
     * GET /api/quality/batches?qaStatus=PENDING_QA
     */
    @GetMapping("/batches")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<QualityQueueItemDto>> getQualityInspectionQueue(
            @RequestParam(required = false) BatchQualityStatus qaStatus) {
        return ResponseEntity.ok(qualityService.getQualityInspectionQueue(qaStatus));
    }

    /**
     * Retrieve the full Batch Quality Dossier (metadata, QC checks, QA reviews, audit logs).
     * GET /api/quality/batches/{batchId}
     */
    @GetMapping("/batches/{batchId:\\d+}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BatchQualityDossierDto> getBatchQualityDossier(@PathVariable Long batchId) {
        return ResponseEntity.ok(qualityService.getBatchQualityDossier(batchId));
    }

    /**
     * Record a new Quality Control (QC) check against a completed batch.
     * POST /api/quality/batches/{batchId}/checks
     */
    @PostMapping("/batches/{batchId:\\d+}/checks")
    @PreAuthorize("hasRole('QC_ANALYST')")
    public ResponseEntity<QualityCheckResponseDto> createQualityCheck(
            @PathVariable Long batchId,
            @Valid @RequestBody QualityCheckRequestDto requestDto) {
        QualityCheckResponseDto created = qualityService.createQualityCheck(batchId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Perform an audited correction (or complete a PENDING QC check) with mandatory reason.
     * Direct un-audited edits (PUT/PATCH/DELETE) are intentionally not exposed.
     * POST /api/quality/checks/{checkId}/correct
     */
    @PostMapping("/checks/{checkId:\\d+}/correct")
    @PreAuthorize("hasRole('QC_ANALYST')")
    public ResponseEntity<QualityCheckResponseDto> correctQualityCheck(
            @PathVariable Long checkId,
            @Valid @RequestBody QualityCheckCorrectionRequestDto requestDto) {
        QualityCheckResponseDto updated = qualityService.correctQualityCheck(checkId, requestDto);
        return ResponseEntity.ok(updated);
    }

    /**
     * Record a QA batch disposition decision (RELEASED, ON_HOLD, REJECTED).
     * POST /api/quality/batches/{batchId}/disposition
     */
    @PostMapping("/batches/{batchId:\\d+}/disposition")
    @PreAuthorize("hasRole('QA_REVIEWER')")
    public ResponseEntity<BatchQualityDossierDto> recordQaDisposition(
            @PathVariable Long batchId,
            @Valid @RequestBody QaDispositionRequestDto requestDto) {
        BatchQualityDossierDto updatedDossier = qualityService.recordQaDisposition(batchId, requestDto);
        return ResponseEntity.ok(updatedDossier);
    }

    /**
     * Query append-only audit log entries, optionally filtered by batchId, entityType, and entityId.
     * Audit logs are strictly read-only; no update or delete endpoints exist.
     * GET /api/quality/audit-logs
     */
    @GetMapping("/audit-logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'QA_REVIEWER')")
    public ResponseEntity<List<AuditLogResponseDto>> getAuditLogs(
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId) {
        return ResponseEntity.ok(auditTrailService.getAuditLogs(batchId, entityType, entityId));
    }
}

