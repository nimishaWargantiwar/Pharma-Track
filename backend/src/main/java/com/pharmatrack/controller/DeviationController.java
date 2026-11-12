package com.pharmatrack.controller;

import com.pharmatrack.dto.BatchLinkableRecordsDto;
import com.pharmatrack.dto.CancelCapaActionRequestDto;
import com.pharmatrack.dto.CapaActionResponseDto;
import com.pharmatrack.dto.CloseDeviationRequestDto;
import com.pharmatrack.dto.CompleteCapaActionRequestDto;
import com.pharmatrack.dto.CreateCapaActionRequestDto;
import com.pharmatrack.dto.CreateDeviationRequestDto;
import com.pharmatrack.dto.DeviationDetailDto;
import com.pharmatrack.dto.DeviationSummaryDto;
import com.pharmatrack.dto.RecordRootCauseRequestDto;
import com.pharmatrack.dto.ReopenCapaRequestDto;
import com.pharmatrack.dto.StartCapaActionRequestDto;
import com.pharmatrack.dto.StartCapaRequestDto;
import com.pharmatrack.dto.StartInvestigationRequestDto;
import com.pharmatrack.dto.SubmitVerificationRequestDto;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.service.DeviationService;
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
 * REST controller for Quality Management Phase 2 — Deviations & CAPA:
 * - Deviation listing with filters and batch-scoped lookups
 * - Linkable batch records (QC checks, eBR steps, audit logs)
 * - Deviation reporting and strict 6-stage lifecycle transitions
 * - CAPA action creation and audited completion/cancellation workflows
 */
@RestController
@RequestMapping("/api/quality")
public class DeviationController {

    private final DeviationService deviationService;

    public DeviationController(DeviationService deviationService) {
        this.deviationService = deviationService;
    }

    // -------------------------------------------------------------------------
    // A. Query Endpoints
    // -------------------------------------------------------------------------

    /**
     * List deviations with optional status, severity, priority, category, and batch filters.
     * GET /api/quality/deviations
     */
    @GetMapping("/deviations")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<DeviationSummaryDto>> getDeviations(
            @RequestParam(required = false) DeviationStatus status,
            @RequestParam(required = false) DeviationSeverity severity,
            @RequestParam(required = false) DeviationPriority priority,
            @RequestParam(required = false) DeviationCategory category,
            @RequestParam(required = false) Long batchId) {
        return ResponseEntity.ok(deviationService.getDeviations(status, severity, priority, category, batchId));
    }

    /**
     * Retrieve deviations linked to a specific manufacturing batch.
     * GET /api/quality/batches/{batchId}/deviations
     */
    @GetMapping("/batches/{batchId}/deviations")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<DeviationSummaryDto>> getBatchDeviations(@PathVariable Long batchId) {
        return ResponseEntity.ok(deviationService.getBatchDeviations(batchId));
    }

    /**
     * Retrieve existing QC checks, eBR execution steps, and audit log events for a batch
     * so deviation reporting forms can link to authoritative records.
     * GET /api/quality/batches/{batchId}/linkable-records
     */
    @GetMapping("/batches/{batchId}/linkable-records")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BatchLinkableRecordsDto> getBatchLinkableRecords(@PathVariable Long batchId) {
        return ResponseEntity.ok(deviationService.getLinkableRecordsForBatch(batchId));
    }

    /**
     * Retrieve full details for a single deviation including RCA, CAPA actions, closure readiness,
     * and scoped append-only audit logs.
     * GET /api/quality/deviations/{deviationId}
     */
    @GetMapping("/deviations/{deviationId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DeviationDetailDto> getDeviationById(@PathVariable Long deviationId) {
        return ResponseEntity.ok(deviationService.getDeviationById(deviationId));
    }

    // -------------------------------------------------------------------------
    // B. Deviation Creation & Lifecycle Transitions
    // -------------------------------------------------------------------------

    /**
     * Report a new deviation against an IN_PROGRESS or COMPLETED batch.
     * POST /api/quality/deviations
     */
    @PostMapping("/deviations")
    @PreAuthorize("hasAnyRole('PRODUCTION_SUPERVISOR', 'OPERATOR', 'QC_ANALYST', 'QA_REVIEWER')")
    public ResponseEntity<DeviationDetailDto> createDeviation(
            @Valid @RequestBody CreateDeviationRequestDto requestDto) {
        DeviationDetailDto created = deviationService.createDeviation(requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Transition deviation from OPEN to UNDER_INVESTIGATION.
     * POST /api/quality/deviations/{deviationId}/start-investigation
     */
    @PostMapping("/deviations/{deviationId}/start-investigation")
    @PreAuthorize("hasAnyRole('PRODUCTION_SUPERVISOR', 'QC_ANALYST', 'QA_REVIEWER')")
    public ResponseEntity<DeviationDetailDto> startInvestigation(
            @PathVariable Long deviationId,
            @Valid @RequestBody StartInvestigationRequestDto requestDto) {
        return ResponseEntity.ok(deviationService.startInvestigation(deviationId, requestDto));
    }

    /**
     * Transition deviation from UNDER_INVESTIGATION to ROOT_CAUSE_IDENTIFIED.
     * POST /api/quality/deviations/{deviationId}/record-root-cause
     */
    @PostMapping("/deviations/{deviationId}/record-root-cause")
    @PreAuthorize("hasAnyRole('PRODUCTION_SUPERVISOR', 'QC_ANALYST', 'QA_REVIEWER')")
    public ResponseEntity<DeviationDetailDto> recordRootCause(
            @PathVariable Long deviationId,
            @Valid @RequestBody RecordRootCauseRequestDto requestDto) {
        return ResponseEntity.ok(deviationService.recordRootCause(deviationId, requestDto));
    }

    /**
     * Transition deviation from ROOT_CAUSE_IDENTIFIED to CAPA_IN_PROGRESS.
     * POST /api/quality/deviations/{deviationId}/start-capa
     */
    @PostMapping("/deviations/{deviationId}/start-capa")
    @PreAuthorize("hasRole('QA_REVIEWER')")
    public ResponseEntity<DeviationDetailDto> startCapa(
            @PathVariable Long deviationId,
            @Valid @RequestBody StartCapaRequestDto requestDto) {
        return ResponseEntity.ok(deviationService.startCapa(deviationId, requestDto));
    }

    /**
     * Transition deviation from CAPA_IN_PROGRESS to PENDING_VERIFICATION.
     * POST /api/quality/deviations/{deviationId}/submit-verification
     */
    @PostMapping("/deviations/{deviationId}/submit-verification")
    @PreAuthorize("hasAnyRole('PRODUCTION_SUPERVISOR', 'QC_ANALYST', 'QA_REVIEWER')")
    public ResponseEntity<DeviationDetailDto> submitForVerification(
            @PathVariable Long deviationId,
            @Valid @RequestBody SubmitVerificationRequestDto requestDto) {
        return ResponseEntity.ok(deviationService.submitForVerification(deviationId, requestDto));
    }

    /**
     * Return a PENDING_VERIFICATION deviation back to CAPA_IN_PROGRESS with a mandatory reason.
     * POST /api/quality/deviations/{deviationId}/reopen-capa
     */
    @PostMapping("/deviations/{deviationId}/reopen-capa")
    @PreAuthorize("hasRole('QA_REVIEWER')")
    public ResponseEntity<DeviationDetailDto> reopenCapa(
            @PathVariable Long deviationId,
            @Valid @RequestBody ReopenCapaRequestDto requestDto) {
        return ResponseEntity.ok(deviationService.reopenCapa(deviationId, requestDto));
    }

    /**
     * Verify effectiveness and close a PENDING_VERIFICATION deviation (terminal CLOSED state).
     * POST /api/quality/deviations/{deviationId}/close
     */
    @PostMapping("/deviations/{deviationId}/close")
    @PreAuthorize("hasRole('QA_REVIEWER')")
    public ResponseEntity<DeviationDetailDto> closeDeviation(
            @PathVariable Long deviationId,
            @Valid @RequestBody CloseDeviationRequestDto requestDto) {
        return ResponseEntity.ok(deviationService.closeDeviation(deviationId, requestDto));
    }

    // -------------------------------------------------------------------------
    // C. CAPA Action Creation & Lifecycle Endpoints
    // -------------------------------------------------------------------------

    /**
     * Create a new Corrective or Preventive Action linked to a deviation.
     * POST /api/quality/deviations/{deviationId}/capas
     */
    @PostMapping("/deviations/{deviationId}/capas")
    @PreAuthorize("hasAnyRole('PRODUCTION_SUPERVISOR', 'QA_REVIEWER')")
    public ResponseEntity<CapaActionResponseDto> createCapaAction(
            @PathVariable Long deviationId,
            @Valid @RequestBody CreateCapaActionRequestDto requestDto) {
        CapaActionResponseDto created = deviationService.createCapaAction(deviationId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Start work on an OPEN CAPA action.
     * POST /api/quality/capas/{capaId}/start
     */
    @PostMapping("/capas/{capaId}/start")
    @PreAuthorize("hasAnyRole('PRODUCTION_SUPERVISOR', 'QC_ANALYST', 'QA_REVIEWER')")
    public ResponseEntity<CapaActionResponseDto> startCapaAction(
            @PathVariable Long capaId,
            @Valid @RequestBody StartCapaActionRequestDto requestDto) {
        return ResponseEntity.ok(deviationService.startCapaAction(capaId, requestDto));
    }

    /**
     * Complete an OPEN or IN_PROGRESS CAPA action with mandatory completion evidence.
     * POST /api/quality/capas/{capaId}/complete
     */
    @PostMapping("/capas/{capaId}/complete")
    @PreAuthorize("hasAnyRole('PRODUCTION_SUPERVISOR', 'QC_ANALYST', 'QA_REVIEWER')")
    public ResponseEntity<CapaActionResponseDto> completeCapaAction(
            @PathVariable Long capaId,
            @Valid @RequestBody CompleteCapaActionRequestDto requestDto) {
        return ResponseEntity.ok(deviationService.completeCapaAction(capaId, requestDto));
    }

    /**
     * Cancel an OPEN or IN_PROGRESS CAPA action with a mandatory reason.
     * POST /api/quality/capas/{capaId}/cancel
     */
    @PostMapping("/capas/{capaId}/cancel")
    @PreAuthorize("hasRole('QA_REVIEWER')")
    public ResponseEntity<CapaActionResponseDto> cancelCapaAction(
            @PathVariable Long capaId,
            @Valid @RequestBody CancelCapaActionRequestDto requestDto) {
        return ResponseEntity.ok(deviationService.cancelCapaAction(capaId, requestDto));
    }
}

