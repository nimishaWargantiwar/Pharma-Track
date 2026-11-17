package com.pharmatrack.ai.controller;

import com.pharmatrack.ai.dto.ArchiveReportRequestDto;
import com.pharmatrack.ai.dto.BatchAnalysisReportRequestDto;
import com.pharmatrack.ai.dto.BatchAnalysisReportResponseDto;
import com.pharmatrack.ai.dto.BatchAnalysisReportSummaryDto;
import com.pharmatrack.ai.dto.PermanentDeleteReportRequestDto;
import com.pharmatrack.ai.service.BatchAnalysisReportService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing dedicated endpoints for AI Batch Analysis Reports.
 *
 * <p>Security Contract:
 * <ul>
 *   <li>Only {@code PRODUCTION_SUPERVISOR} may initiate report generation (POST).</li>
 *   <li>{@code PRODUCTION_SUPERVISOR}, {@code QA_REVIEWER}, and {@code ADMIN} may view report history and details (GET).</li>
 *   <li>{@code OPERATOR} and {@code QC_ANALYST} are strictly excluded from generating, listing, or viewing reports.</li>
 *   <li>User identity is resolved strictly server-side from {@code SecurityContext}; client-supplied identity is rejected.</li>
 *   <li>Nonexistent batch, nonexistent report, or report belonging to another batch return HTTP 404 to prevent information disclosure.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/batches/{batchId}/analysis-reports")
public class BatchAnalysisReportController {

    private final BatchAnalysisReportService reportService;

    public BatchAnalysisReportController(BatchAnalysisReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * Generate a dedicated AI Batch Analysis Report for the specified batch.
     * POST /api/batches/{batchId}/analysis-reports
     *
     * <p>Enforces idempotency using the required {@code clientRequestId}. Repeated submissions
     * with the same clientRequestId return the existing completed report.</p>
     */
    @PostMapping
    @PreAuthorize("hasRole('PRODUCTION_SUPERVISOR')")
    public ResponseEntity<BatchAnalysisReportResponseDto> generateReport(
            @PathVariable Long batchId,
            @Valid @RequestBody BatchAnalysisReportRequestDto requestDto) {
        BatchAnalysisReportResponseDto response = reportService.generateReport(String.valueOf(batchId), requestDto);
        return ResponseEntity.ok(response);
    }

    /**
     * List historical AI Batch Analysis Reports for the specified batch in reverse chronological order.
     * GET /api/batches/{batchId}/analysis-reports
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('PRODUCTION_SUPERVISOR', 'QA_REVIEWER', 'ADMIN')")
    public ResponseEntity<List<BatchAnalysisReportSummaryDto>> listReports(
            @PathVariable Long batchId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false, defaultValue = "false") Boolean includeArchived) {
        List<BatchAnalysisReportSummaryDto> history = Boolean.TRUE.equals(includeArchived)
                ? reportService.getReportsForBatch(String.valueOf(batchId), page, size, true)
                : reportService.getReportsForBatch(String.valueOf(batchId), page, size);
        return ResponseEntity.ok(history);
    }

    /**
     * Archive an AI Batch Analysis Report to remove it from active operational history.
     * PATCH /api/batches/{batchId}/analysis-reports/{reportId}/archive
     */
    @PatchMapping("/{reportId}/archive")
    @PreAuthorize("hasAnyRole('PRODUCTION_SUPERVISOR', 'ADMIN')")
    public ResponseEntity<BatchAnalysisReportResponseDto> archiveReport(
            @PathVariable Long batchId,
            @PathVariable Long reportId,
            @Valid @RequestBody ArchiveReportRequestDto requestDto) {
        BatchAnalysisReportResponseDto response = reportService.archiveReport(String.valueOf(batchId), reportId, requestDto);
        return ResponseEntity.ok(response);
    }

    /**
     * Permanently delete an AI Batch Analysis Report from the local development database.
     * DELETE /api/batches/{batchId}/analysis-reports/{reportId}
     *
     * <p>Security Contract: Strictly restricted to {@code ADMIN}. Blocked in production environments.
     * Requires mandatory non-blank reason (max 500 chars) and exact confirmation keyword.</p>
     */
    @DeleteMapping("/{reportId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> permanentlyDeleteReport(
            @PathVariable Long batchId,
            @PathVariable Long reportId,
            @Valid @RequestBody PermanentDeleteReportRequestDto requestDto) {
        reportService.permanentlyDeleteReport(String.valueOf(batchId), reportId, requestDto);
        return ResponseEntity.noContent().build();
    }

    /**
     * Retrieve full details of a specific AI Batch Analysis Report scoped to its parent batch.
     * GET /api/batches/{batchId}/analysis-reports/{reportId}
     */
    @GetMapping("/{reportId}")
    @PreAuthorize("hasAnyRole('PRODUCTION_SUPERVISOR', 'QA_REVIEWER', 'ADMIN')")
    public ResponseEntity<BatchAnalysisReportResponseDto> getReportDetails(
            @PathVariable Long batchId,
            @PathVariable Long reportId) {
        BatchAnalysisReportResponseDto response = reportService.getReportById(String.valueOf(batchId), reportId);
        return ResponseEntity.ok(response);
    }
}
