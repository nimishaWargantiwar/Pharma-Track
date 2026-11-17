package com.pharmatrack.ai;

import com.pharmatrack.ai.entity.BatchAnalysisFocusArea;
import com.pharmatrack.ai.entity.BatchAnalysisMode;
import com.pharmatrack.ai.entity.BatchAnalysisReport;
import com.pharmatrack.ai.entity.BatchAnalysisReportStatus;
import com.pharmatrack.ai.exception.BatchAnalysisConflictException;
import com.pharmatrack.ai.repository.BatchAnalysisReportRepository;
import com.pharmatrack.ai.service.BatchAnalysisReportTxHelper;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.service.AuditTrailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 4F.1 - Scope-Aware Idempotency Verification")
class BatchAnalysisScopeAwareIdempotencyTest {

    @Mock
    private BatchAnalysisReportRepository reportRepository;

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private AuditTrailService auditTrailService;

    private BatchAnalysisReportTxHelper txHelper;
    private Batch testBatch;
    private UserAccount testSupervisor;

    @BeforeEach
    void setUp() {
        txHelper = new BatchAnalysisReportTxHelper(reportRepository, batchRepository, auditTrailService);

        testBatch = new Batch();
        testBatch.setId(100L);
        testBatch.setBatchNumber("BAT-SCOPE-01");

        testSupervisor = new UserAccount();
        testSupervisor.setId(50L);
        testSupervisor.setUsername("supervisor1");
        testSupervisor.setRole(Role.PRODUCTION_SUPERVISOR);

        when(batchRepository.findByIdWithLock(100L)).thenReturn(Optional.of(testBatch));
    }

    @Test
    @DisplayName("Creates new report with FULL mode and empty focus areas when unspecified")
    void testCreateNewReportDefaultFullMode() {
        when(reportRepository.findByClientRequestId("REQ-SCOPE-1")).thenReturn(Optional.empty());
        when(reportRepository.findFirstByBatchIdAndStatusOrderByCreatedAtDesc(100L, BatchAnalysisReportStatus.GENERATING))
                .thenReturn(Optional.empty());

        BatchAnalysisReport savedReport = new BatchAnalysisReport(testBatch, testSupervisor, "REQ-SCOPE-1");
        savedReport.setAnalysisMode(BatchAnalysisMode.FULL);
        when(reportRepository.saveAndFlush(any(BatchAnalysisReport.class))).thenReturn(savedReport);

        BatchAnalysisReport result = txHelper.getOrCreateInitialReportTx(
                testBatch, testSupervisor, "REQ-SCOPE-1", BatchAnalysisMode.FULL, Collections.emptyList()
        );

        assertThat(result.getAnalysisMode()).isEqualTo(BatchAnalysisMode.FULL);
        assertThat(result.getFocusAreas()).isEmpty();

        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditTrailService).recordAudit(
                eq(BatchAnalysisReportTxHelper.AUDIT_ENTITY_TYPE),
                any(),
                any(),
                eq(100L),
                eq(BatchAnalysisReportTxHelper.ACTION_REPORT_REQUESTED),
                eq("supervisor1"),
                any(),
                any(),
                any(),
                auditCaptor.capture()
        );

        Map<String, Object> auditMap = auditCaptor.getValue();
        assertThat(auditMap).containsEntry("analysisMode", "FULL");
    }

    @Test
    @DisplayName("Creates new report with FOCUSED mode and persists focus summary in audit")
    void testCreateNewReportFocusedMode() {
        when(reportRepository.findByClientRequestId("REQ-SCOPE-2")).thenReturn(Optional.empty());
        when(reportRepository.findFirstByBatchIdAndStatusOrderByCreatedAtDesc(100L, BatchAnalysisReportStatus.GENERATING))
                .thenReturn(Optional.empty());

        List<BatchAnalysisFocusArea> focusAreas = List.of(
                BatchAnalysisFocusArea.QC_INSPECTION,
                BatchAnalysisFocusArea.DEVIATIONS_CAPA
        );
        BatchAnalysisReport savedReport = new BatchAnalysisReport(
                testBatch, testSupervisor, "REQ-SCOPE-2", BatchAnalysisMode.FOCUSED,
                BatchAnalysisFocusArea.toJson(focusAreas),
                BatchAnalysisFocusArea.toSummary(focusAreas)
        );
        when(reportRepository.saveAndFlush(any(BatchAnalysisReport.class))).thenReturn(savedReport);

        BatchAnalysisReport result = txHelper.getOrCreateInitialReportTx(
                testBatch, testSupervisor, "REQ-SCOPE-2", BatchAnalysisMode.FOCUSED, focusAreas
        );

        assertThat(result.getAnalysisMode()).isEqualTo(BatchAnalysisMode.FOCUSED);
        assertThat(result.getFocusAreas()).containsExactly(
                BatchAnalysisFocusArea.QC_INSPECTION,
                BatchAnalysisFocusArea.DEVIATIONS_CAPA
        );

        ArgumentCaptor<Map<String, Object>> auditCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditTrailService).recordAudit(
                eq(BatchAnalysisReportTxHelper.AUDIT_ENTITY_TYPE),
                any(),
                any(),
                eq(100L),
                eq(BatchAnalysisReportTxHelper.ACTION_REPORT_REQUESTED),
                eq("supervisor1"),
                any(),
                any(),
                any(),
                auditCaptor.capture()
        );

        Map<String, Object> auditMap = auditCaptor.getValue();
        assertThat(auditMap).containsEntry("analysisMode", "FOCUSED");
        assertThat(auditMap).containsEntry("focusSummary", "QC Inspection, Deviations & CAPA");
    }

    @Test
    @DisplayName("Returns existing COMPLETED report when clientRequestId and scope match exactly")
    void testIdempotentReplayScopeMatch() {
        List<BatchAnalysisFocusArea> areas = List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD);
        BatchAnalysisReport existing = new BatchAnalysisReport(
                testBatch, testSupervisor, "REQ-SCOPE-IDEMPOTENT", BatchAnalysisMode.FOCUSED,
                BatchAnalysisFocusArea.toJson(areas),
                BatchAnalysisFocusArea.toSummary(areas)
        );
        // transition to completed
        existing.complete("test-model", null, null, null, null, null, null, null, "{}", "markdown", "[]");

        when(reportRepository.findByClientRequestId("REQ-SCOPE-IDEMPOTENT")).thenReturn(Optional.of(existing));

        BatchAnalysisReport result = txHelper.getOrCreateInitialReportTx(
                testBatch, testSupervisor, "REQ-SCOPE-IDEMPOTENT", BatchAnalysisMode.FOCUSED, areas
        );

        assertThat(result).isSameAs(existing);
        assertThat(result.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);
    }

    @Test
    @DisplayName("Rejects clientRequestId replay when requested analysis mode mismatches existing report mode (409)")
    void testScopeConflictModeMismatch() {
        BatchAnalysisReport existing = new BatchAnalysisReport(
                testBatch, testSupervisor, "REQ-SCOPE-CONFLICT-MODE", BatchAnalysisMode.FULL, null, null
        );
        existing.complete("test-model", null, null, null, null, null, null, null, "{}", "markdown", "[]");

        when(reportRepository.findByClientRequestId("REQ-SCOPE-CONFLICT-MODE")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> txHelper.getOrCreateInitialReportTx(
                testBatch, testSupervisor, "REQ-SCOPE-CONFLICT-MODE",
                BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD)
        ))
                .isInstanceOf(BatchAnalysisConflictException.class)
                .hasMessageContaining("was already used for analysis mode FULL, but requested mode is FOCUSED");
    }

    @Test
    @DisplayName("Rejects clientRequestId replay when requested focus areas mismatch existing report focus areas (409)")
    void testScopeConflictFocusAreasMismatch() {
        List<BatchAnalysisFocusArea> existingAreas = List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD);
        BatchAnalysisReport existing = new BatchAnalysisReport(
                testBatch, testSupervisor, "REQ-SCOPE-CONFLICT-AREAS", BatchAnalysisMode.FOCUSED,
                BatchAnalysisFocusArea.toJson(existingAreas),
                BatchAnalysisFocusArea.toSummary(existingAreas)
        );
        existing.complete("test-model", null, null, null, null, null, null, null, "{}", "markdown", "[]");

        when(reportRepository.findByClientRequestId("REQ-SCOPE-CONFLICT-AREAS")).thenReturn(Optional.of(existing));

        List<BatchAnalysisFocusArea> requestedAreas = List.of(BatchAnalysisFocusArea.QC_INSPECTION);

        assertThatThrownBy(() -> txHelper.getOrCreateInitialReportTx(
                testBatch, testSupervisor, "REQ-SCOPE-CONFLICT-AREAS",
                BatchAnalysisMode.FOCUSED, requestedAreas
        ))
                .isInstanceOf(BatchAnalysisConflictException.class)
                .hasMessageContaining("was already used with different focus areas");
    }
}
