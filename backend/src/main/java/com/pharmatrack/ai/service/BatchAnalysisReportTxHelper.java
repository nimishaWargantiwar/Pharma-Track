package com.pharmatrack.ai.service;

import com.pharmatrack.ai.dto.BatchAnalysisMetricsDto;
import com.pharmatrack.ai.entity.BatchAnalysisFocusArea;
import com.pharmatrack.ai.entity.BatchAnalysisMode;
import com.pharmatrack.ai.entity.BatchAnalysisReport;
import com.pharmatrack.ai.entity.BatchAnalysisReportStatus;
import com.pharmatrack.ai.exception.BatchAnalysisConflictException;
import com.pharmatrack.ai.repository.BatchAnalysisReportRepository;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.util.AppTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Transactional helper for dedicated AI Batch Analysis Reports.
 *
 * <p>Architectural guarantees:
 * <ul>
 *   <li>All mutations execute in isolated short-lived transactions ({@link Propagation#REQUIRES_NEW}).</li>
 *   <li>No database transaction is held during external LLM provider invocations.</li>
 *   <li>Guarantees safe concurrency handling and atomic state transitions.</li>
 *   <li>Records immutable audit log entries without logging sensitive prompts or credentials.</li>
 * </ul>
 */
@Service
public class BatchAnalysisReportTxHelper {

    private static final Logger log = LoggerFactory.getLogger(BatchAnalysisReportTxHelper.class);

    public static final String AUDIT_ENTITY_TYPE = "BATCH_ANALYSIS_REPORT";
    public static final String ACTION_REPORT_REQUESTED = "AI_BATCH_ANALYSIS_REQUESTED";
    public static final String ACTION_REPORT_COMPLETED = "AI_BATCH_ANALYSIS_COMPLETED";
    public static final String ACTION_REPORT_FAILED = "AI_BATCH_ANALYSIS_FAILED";

    private final BatchAnalysisReportRepository reportRepository;
    private final com.pharmatrack.repository.BatchRepository batchRepository;
    private final AuditTrailService auditTrailService;

    public BatchAnalysisReportTxHelper(BatchAnalysisReportRepository reportRepository,
                                       com.pharmatrack.repository.BatchRepository batchRepository,
                                       AuditTrailService auditTrailService) {
        this.reportRepository = reportRepository;
        this.batchRepository = batchRepository;
        this.auditTrailService = auditTrailService;
    }

    /**
     * Initializes or resolves an existing report record in an isolated short transaction.
     *
     * <p>Acquires a pessimistic write lock (SELECT ... FOR UPDATE) on the parent {@link Batch} row
     * to strictly serialize concurrent generation attempts for the same batch and prevent duplicate
     * in-flight GENERATING records.</p>
     *
     * @param batch           target manufacturing batch
     * @param user            requesting supervisor
     * @param clientRequestId unique idempotency key
     * @return persisted {@link BatchAnalysisReport} in either GENERATING or already COMPLETED status
     * @throws BatchAnalysisConflictException if the request ID is mismatched across batches/users or in-flight
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchAnalysisReport getOrCreateInitialReportTx(Batch batch, UserAccount user, String clientRequestId) {
        return getOrCreateInitialReportTx(batch, user, clientRequestId, BatchAnalysisMode.FULL, Collections.emptyList());
    }

    /**
     * Initializes or resolves an existing report record in an isolated short transaction.
     *
     * <p>Acquires a pessimistic write lock (SELECT ... FOR UPDATE) on the parent {@link Batch} row
     * to strictly serialize concurrent generation attempts for the same batch and prevent duplicate
     * in-flight GENERATING records.</p>
     *
     * @param batch           target manufacturing batch
     * @param user            requesting supervisor
     * @param clientRequestId unique idempotency key
     * @param analysisMode    analysis mode (FULL or FOCUSED)
     * @param focusAreas      selected focus areas if FOCUSED mode
     * @return persisted {@link BatchAnalysisReport} in either GENERATING or already COMPLETED status
     * @throws BatchAnalysisConflictException if the request ID is mismatched across batches/users/scope or in-flight
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchAnalysisReport getOrCreateInitialReportTx(Batch batch,
                                                          UserAccount user,
                                                          String clientRequestId,
                                                          BatchAnalysisMode analysisMode,
                                                          List<BatchAnalysisFocusArea> focusAreas) {
        // Step 1: Acquire pessimistic write lock on the requested batch row
        // Serializes concurrent generation attempts for the same batch
        Batch lockedBatch = batchRepository.findByIdWithLock(batch.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batch.getId()));

        // Step 2: Check for existing report with the exact clientRequestId
        Optional<BatchAnalysisReport> existingOpt = reportRepository.findByClientRequestId(clientRequestId);
        if (existingOpt.isPresent()) {
            return handleExistingReport(existingOpt.get(), lockedBatch, user, clientRequestId, analysisMode, focusAreas);
        }

        // Step 3: Check whether another report is currently in GENERATING status for this batch
        Optional<BatchAnalysisReport> activeGeneratingOpt = reportRepository
                .findFirstByBatchIdAndStatusOrderByCreatedAtDesc(lockedBatch.getId(), BatchAnalysisReportStatus.GENERATING);

        if (activeGeneratingOpt.isPresent()) {
            BatchAnalysisReport activeReport = activeGeneratingOpt.get();
            LocalDateTime tenMinutesAgo = AppTime.nowDateTime().minusMinutes(10);
            if (activeReport.getCreatedAt() != null && activeReport.getCreatedAt().isBefore(tenMinutesAgo)) {
                log.warn("Active report {} for batch {} in GENERATING state exceeded 10 minutes. Recovering as FAILED.",
                        activeReport.getId(), lockedBatch.getBatchNumber());
                activeReport.fail("Previous generation attempt timed out or terminated unexpectedly.");
                reportRepository.saveAndFlush(activeReport);

                auditTrailService.recordAudit(
                        AUDIT_ENTITY_TYPE,
                        activeReport.getId(),
                        "REPORT-" + activeReport.getId(),
                        lockedBatch.getId(),
                        ACTION_REPORT_FAILED,
                        user.getUsername(),
                        AppTime.nowDateTime(),
                        "Stale AI Batch Analysis Report timed out for batch " + lockedBatch.getBatchNumber(),
                        Map.of("status", "GENERATING"),
                        Map.of("status", "FAILED", "reason", "Stale GENERATING timeout recovery during fresh submission")
                );
                // Stale report recovered; proceed to insert the fresh report
            } else {
                throw new BatchAnalysisConflictException(
                        "Another batch analysis report generation is already in progress for batch " + lockedBatch.getBatchNumber()
                );
            }
        }

        BatchAnalysisMode effectiveMode = analysisMode != null ? analysisMode : BatchAnalysisMode.FULL;
        String focusAreasJson = BatchAnalysisFocusArea.toJson(focusAreas);
        String focusSummary = BatchAnalysisFocusArea.toSummary(focusAreas);
        BatchAnalysisReport newReport = new BatchAnalysisReport(
                lockedBatch, user, clientRequestId, effectiveMode, focusAreasJson, focusSummary
        );
        try {
            BatchAnalysisReport saved = reportRepository.saveAndFlush(newReport);

            Map<String, Object> afterAudit = new HashMap<>();
            afterAudit.put("status", "GENERATING");
            afterAudit.put("clientRequestId", clientRequestId);
            afterAudit.put("analysisMode", effectiveMode.name());
            if (focusSummary != null) {
                afterAudit.put("focusSummary", focusSummary);
            }

            auditTrailService.recordAudit(
                    AUDIT_ENTITY_TYPE,
                    saved.getId(),
                    "REPORT-" + saved.getId(),
                    lockedBatch.getId(),
                    ACTION_REPORT_REQUESTED,
                    user.getUsername(),
                    AppTime.nowDateTime(),
                    "AI Batch Analysis Report requested for batch " + lockedBatch.getBatchNumber(),
                    null,
                    afterAudit
            );

            if (saved.getGeneratedByUser() != null) {
                saved.getGeneratedByUser().getUsername();
            }
            return saved;
        } catch (DataIntegrityViolationException ex) {
            log.info("Concurrent insert detected for clientRequestId {}. Reloading existing report.", clientRequestId);
            BatchAnalysisReport concurrentReport = reportRepository.findByClientRequestId(clientRequestId)
                    .orElseThrow(() -> new IllegalStateException("Failed to find concurrent report with clientRequestId: " + clientRequestId, ex));
            return handleExistingReport(concurrentReport, lockedBatch, user, clientRequestId, analysisMode, focusAreas);
        }
    }

    private BatchAnalysisReport handleExistingReport(BatchAnalysisReport existing,
                                                    Batch batch,
                                                    UserAccount user,
                                                    String clientRequestId,
                                                    BatchAnalysisMode analysisMode,
                                                    List<BatchAnalysisFocusArea> focusAreas) {
        if (!existing.getBatch().getId().equals(batch.getId())) {
            throw new BatchAnalysisConflictException(
                    "Client request ID '" + clientRequestId + "' is already assigned to a different batch: " + existing.getBatchNumber()
            );
        }
        if (!existing.getGeneratedByUser().getId().equals(user.getId())) {
            throw new BatchAnalysisConflictException(
                    "Client request ID '" + clientRequestId + "' belongs to another user"
            );
        }

        BatchAnalysisMode requestedMode = analysisMode != null ? analysisMode : BatchAnalysisMode.FULL;
        if (existing.getAnalysisMode() != requestedMode) {
            throw new BatchAnalysisConflictException(
                    "Client request ID '" + clientRequestId + "' was already used for analysis mode " +
                    existing.getAnalysisMode() + ", but requested mode is " + requestedMode
            );
        }

        List<BatchAnalysisFocusArea> requestedAreas = focusAreas != null ? focusAreas : Collections.emptyList();
        List<BatchAnalysisFocusArea> existingAreas = existing.getFocusAreas();
        if (!Objects.equals(existingAreas, requestedAreas)) {
            throw new BatchAnalysisConflictException(
                    "Client request ID '" + clientRequestId + "' was already used with different focus areas"
            );
        }

        if (existing.getStatus() == BatchAnalysisReportStatus.COMPLETED) {
            log.info("Report with clientRequestId {} is already COMPLETED. Returning existing report.", clientRequestId);
            return existing;
        }

        if (existing.getStatus() == BatchAnalysisReportStatus.GENERATING) {
            LocalDateTime tenMinutesAgo = AppTime.nowDateTime().minusMinutes(10);
            if (existing.getCreatedAt() != null && existing.getCreatedAt().isBefore(tenMinutesAgo)) {
                log.warn("Report with clientRequestId {} has been in GENERATING state over 10 minutes. Recovering as FAILED.", clientRequestId);
                existing.fail("Previous generation attempt timed out or terminated unexpectedly.");
                reportRepository.saveAndFlush(existing);

                auditTrailService.recordAudit(
                        AUDIT_ENTITY_TYPE,
                        existing.getId(),
                        "REPORT-" + existing.getId(),
                        batch.getId(),
                        ACTION_REPORT_FAILED,
                        user.getUsername(),
                        AppTime.nowDateTime(),
                        "Stale AI Batch Analysis Report timed out for batch " + batch.getBatchNumber(),
                        Map.of("status", "GENERATING"),
                        Map.of("status", "FAILED", "reason", "Stale GENERATING timeout recovery")
                );

                throw new BatchAnalysisConflictException(
                        "Previous report generation attempt timed out and was marked FAILED. Please submit a retry with a new clientRequestId."
                );
            }

            throw new BatchAnalysisConflictException(
                    "Report generation is currently in progress for clientRequestId: " + clientRequestId
            );
        }

        // FAILED status
        throw new BatchAnalysisConflictException(
                "Previous report generation failed for clientRequestId: " + clientRequestId + ". Please submit a retry with a new clientRequestId."
        );
    }

    /**
     * Atomically transitions a report to COMPLETED state and records audit log.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchAnalysisReport completeReportTx(Long reportId,
                                                String model,
                                                BatchAnalysisMetricsDto metrics,
                                                String structuredAnalysisJson,
                                                String reportMarkdown,
                                                String sourcesJson,
                                                String actorUsername) {
        BatchAnalysisReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found with id: " + reportId));

        report.complete(
                model,
                metrics != null ? metrics.getPlannedBatchSize() : null,
                metrics != null ? metrics.getActualYield() : null,
                metrics != null ? metrics.getYieldPercent() : null,
                metrics != null ? metrics.getYieldVariance() : null,
                metrics != null ? metrics.getExecutionStepCount() : null,
                metrics != null ? metrics.getQcCheckCount() : null,
                metrics != null ? metrics.getDeviationCount() : null,
                structuredAnalysisJson,
                reportMarkdown,
                sourcesJson
        );

        BatchAnalysisReport saved = reportRepository.saveAndFlush(report);

        auditTrailService.recordAudit(
                AUDIT_ENTITY_TYPE,
                saved.getId(),
                "REPORT-" + saved.getId(),
                saved.getBatch().getId(),
                ACTION_REPORT_COMPLETED,
                actorUsername,
                AppTime.nowDateTime(),
                "AI Batch Analysis Report completed successfully for batch " + saved.getBatchNumber(),
                Map.of("status", "GENERATING"),
                Map.of("status", "COMPLETED", "model", model != null ? model : "unknown")
        );

        if (saved.getGeneratedByUser() != null) {
            saved.getGeneratedByUser().getUsername();
        }
        return saved;
    }

    /**
     * Atomically transitions a report to FAILED state and records audit log.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failReportTx(Long reportId, String errorMessage, String actorUsername) {
        BatchAnalysisReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found with id: " + reportId));

        if (report.getStatus() == BatchAnalysisReportStatus.COMPLETED) {
            log.warn("Cannot fail report {} because it has already COMPLETED successfully", reportId);
            return;
        }

        report.fail(errorMessage);
        BatchAnalysisReport saved = reportRepository.saveAndFlush(report);

        auditTrailService.recordAudit(
                AUDIT_ENTITY_TYPE,
                saved.getId(),
                "REPORT-" + saved.getId(),
                saved.getBatch().getId(),
                ACTION_REPORT_FAILED,
                actorUsername,
                AppTime.nowDateTime(),
                "AI Batch Analysis Report generation failed for batch " + saved.getBatchNumber(),
                Map.of("status", "GENERATING"),
                Map.of("status", "FAILED", "reason", errorMessage != null ? errorMessage : "Unknown error")
        );
    }
}
