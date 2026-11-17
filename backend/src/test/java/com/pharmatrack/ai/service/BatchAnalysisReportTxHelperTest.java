package com.pharmatrack.ai.service;

import com.pharmatrack.ai.dto.BatchAnalysisMetricsDto;
import com.pharmatrack.ai.entity.BatchAnalysisReport;
import com.pharmatrack.ai.entity.BatchAnalysisReportStatus;
import com.pharmatrack.ai.exception.BatchAnalysisConflictException;
import com.pharmatrack.ai.repository.BatchAnalysisReportRepository;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.util.AppTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BatchAnalysisReportTxHelper Unit Tests")
class BatchAnalysisReportTxHelperTest {

    @Mock
    private BatchAnalysisReportRepository reportRepository;

    @Mock
    private com.pharmatrack.repository.BatchRepository batchRepository;

    @Mock
    private AuditTrailService auditTrailService;

    private BatchAnalysisReportTxHelper txHelper;

    private UserAccount supervisor1;
    private UserAccount supervisor2;
    private Batch batch1;
    private Batch batch2;

    @BeforeEach
    void setUp() {
        txHelper = new BatchAnalysisReportTxHelper(reportRepository, batchRepository, auditTrailService);

        supervisor1 = new UserAccount("supervisor1", "hash", "Supervisor 1", Role.PRODUCTION_SUPERVISOR, true);
        setField(supervisor1, "id", 1L);

        supervisor2 = new UserAccount("supervisor2", "hash", "Supervisor 2", Role.PRODUCTION_SUPERVISOR, true);
        setField(supervisor2, "id", 2L);

        batch1 = new Batch();
        setField(batch1, "id", 101L);
        batch1.setBatchNumber("BAT-2026-0001-01");

        batch2 = new Batch();
        setField(batch2, "id", 102L);
        batch2.setBatchNumber("BAT-2026-0002-01");

        lenient().when(batchRepository.findByIdWithLock(101L)).thenReturn(Optional.of(batch1));
        lenient().when(batchRepository.findByIdWithLock(102L)).thenReturn(Optional.of(batch2));
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Should create new report and log audit trail when clientRequestId does not exist")
    void shouldCreateNewReport() {
        when(reportRepository.findByClientRequestId("REQ-NEW-123")).thenReturn(Optional.empty());

        BatchAnalysisReport saved = new BatchAnalysisReport(batch1, supervisor1, "REQ-NEW-123");
        setField(saved, "id", 501L);
        when(reportRepository.saveAndFlush(any())).thenReturn(saved);

        BatchAnalysisReport result = txHelper.getOrCreateInitialReportTx(batch1, supervisor1, "REQ-NEW-123");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(501L);
        verify(auditTrailService).recordAudit(
                eq(BatchAnalysisReportTxHelper.AUDIT_ENTITY_TYPE),
                eq(501L),
                eq("REPORT-501"),
                eq(101L),
                eq(BatchAnalysisReportTxHelper.ACTION_REPORT_REQUESTED),
                eq("supervisor1"),
                any(),
                anyString(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("Should reject request ID reuse across different batches with BatchAnalysisConflictException")
    void shouldRejectCrossBatchRequestIdReuse() {
        BatchAnalysisReport existing = new BatchAnalysisReport(batch1, supervisor1, "REQ-SHARED");
        setField(existing, "id", 502L);
        when(reportRepository.findByClientRequestId("REQ-SHARED")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> txHelper.getOrCreateInitialReportTx(batch2, supervisor1, "REQ-SHARED"))
                .isInstanceOf(BatchAnalysisConflictException.class)
                .hasMessageContaining("already assigned to a different batch: BAT-2026-0001-01");
    }

    @Test
    @DisplayName("Should reject request ID reuse across different users with BatchAnalysisConflictException")
    void shouldRejectCrossUserRequestIdReuse() {
        BatchAnalysisReport existing = new BatchAnalysisReport(batch1, supervisor1, "REQ-SHARED-USER");
        setField(existing, "id", 503L);
        when(reportRepository.findByClientRequestId("REQ-SHARED-USER")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> txHelper.getOrCreateInitialReportTx(batch1, supervisor2, "REQ-SHARED-USER"))
                .isInstanceOf(BatchAnalysisConflictException.class)
                .hasMessageContaining("belongs to another user");
    }

    @Test
    @DisplayName("Should reject concurrent submission when report is actively GENERATING")
    void shouldRejectConcurrentInFlightGeneration() {
        BatchAnalysisReport existing = new BatchAnalysisReport(batch1, supervisor1, "REQ-IN-FLIGHT");
        existing.setCreatedAt(AppTime.nowDateTime().minusMinutes(1));
        when(reportRepository.findByClientRequestId("REQ-IN-FLIGHT")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> txHelper.getOrCreateInitialReportTx(batch1, supervisor1, "REQ-IN-FLIGHT"))
                .isInstanceOf(BatchAnalysisConflictException.class)
                .hasMessageContaining("Report generation is currently in progress");
    }

    @Test
    @DisplayName("Should recover stale GENERATING report older than 10 minutes by marking FAILED")
    void shouldRecoverStaleGeneratingReport() {
        BatchAnalysisReport existing = new BatchAnalysisReport(batch1, supervisor1, "REQ-STALE");
        setField(existing, "id", 504L);
        existing.setCreatedAt(AppTime.nowDateTime().minusMinutes(15));
        when(reportRepository.findByClientRequestId("REQ-STALE")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> txHelper.getOrCreateInitialReportTx(batch1, supervisor1, "REQ-STALE"))
                .isInstanceOf(BatchAnalysisConflictException.class)
                .hasMessageContaining("timed out and was marked FAILED");

        assertThat(existing.getStatus()).isEqualTo(BatchAnalysisReportStatus.FAILED);
        verify(reportRepository).saveAndFlush(existing);
        verify(auditTrailService).recordAudit(
                eq(BatchAnalysisReportTxHelper.AUDIT_ENTITY_TYPE),
                eq(504L),
                eq("REPORT-504"),
                eq(101L),
                eq(BatchAnalysisReportTxHelper.ACTION_REPORT_FAILED),
                eq("supervisor1"),
                any(),
                anyString(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("Should return existing report when clientRequestId is already COMPLETED")
    void shouldReturnCompletedReport() {
        BatchAnalysisReport existing = new BatchAnalysisReport(batch1, supervisor1, "REQ-COMPLETED");
        existing.complete("gpt-4o", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, 1, 1, 0, "{}", "#", "[]");
        when(reportRepository.findByClientRequestId("REQ-COMPLETED")).thenReturn(Optional.of(existing));

        BatchAnalysisReport result = txHelper.getOrCreateInitialReportTx(batch1, supervisor1, "REQ-COMPLETED");
        assertThat(result.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);
    }

    @Test
    @DisplayName("Should complete report and record audit log")
    void shouldCompleteReportAndRecordAudit() {
        BatchAnalysisReport report = new BatchAnalysisReport(batch1, supervisor1, "REQ-COMPLETE-TEST");
        setField(report, "id", 601L);
        when(reportRepository.findById(601L)).thenReturn(Optional.of(report));
        when(reportRepository.saveAndFlush(any())).thenReturn(report);

        BatchAnalysisMetricsDto metrics = new BatchAnalysisMetricsDto(
                new BigDecimal("1000.0000"),
                new BigDecimal("990.0000"),
                new BigDecimal("99.0000"),
                new BigDecimal("-10.0000"),
                5,
                3,
                0
        );

        BatchAnalysisReport completed = txHelper.completeReportTx(
                601L,
                "gpt-4o-mini",
                metrics,
                "{\"summary\": \"done\"}",
                "# Report",
                "[]",
                "supervisor1"
        );

        assertThat(completed.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);
        verify(auditTrailService).recordAudit(
                eq(BatchAnalysisReportTxHelper.AUDIT_ENTITY_TYPE),
                eq(601L),
                eq("REPORT-601"),
                eq(101L),
                eq(BatchAnalysisReportTxHelper.ACTION_REPORT_COMPLETED),
                eq("supervisor1"),
                any(),
                anyString(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("Should reject different request ID for same batch when GENERATING report is active")
    void shouldRejectDifferentRequestIdForSameBatchWhenGenerating() {
        when(reportRepository.findByClientRequestId("REQ-NEW-2")).thenReturn(Optional.empty());

        BatchAnalysisReport activeReport = new BatchAnalysisReport(batch1, supervisor1, "REQ-ACTIVE-1");
        activeReport.setCreatedAt(AppTime.nowDateTime().minusMinutes(2));
        when(reportRepository.findFirstByBatchIdAndStatusOrderByCreatedAtDesc(101L, BatchAnalysisReportStatus.GENERATING))
                .thenReturn(Optional.of(activeReport));

        assertThatThrownBy(() -> txHelper.getOrCreateInitialReportTx(batch1, supervisor1, "REQ-NEW-2"))
                .isInstanceOf(BatchAnalysisConflictException.class)
                .hasMessageContaining("Another batch analysis report generation is already in progress for batch BAT-2026-0001-01");

        verify(reportRepository, never()).saveAndFlush(argThat(r -> "REQ-NEW-2".equals(r.getClientRequestId())));
    }

    @Test
    @DisplayName("Should allow independent generation for different batches concurrently")
    void shouldAllowIndependentGenerationForDifferentBatches() {
        when(reportRepository.findByClientRequestId("REQ-BATCH2")).thenReturn(Optional.empty());
        when(reportRepository.findFirstByBatchIdAndStatusOrderByCreatedAtDesc(102L, BatchAnalysisReportStatus.GENERATING))
                .thenReturn(Optional.empty());

        BatchAnalysisReport savedBatch2Report = new BatchAnalysisReport(batch2, supervisor1, "REQ-BATCH2");
        setField(savedBatch2Report, "id", 702L);
        when(reportRepository.saveAndFlush(any())).thenReturn(savedBatch2Report);

        BatchAnalysisReport result = txHelper.getOrCreateInitialReportTx(batch2, supervisor1, "REQ-BATCH2");
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(702L);
    }

    @Test
    @DisplayName("Should allow fresh request ID when previous report for the batch has FAILED")
    void shouldAllowFreshRequestIdWhenPreviousReportFailed() {
        when(reportRepository.findByClientRequestId("REQ-RETRY-FRESH")).thenReturn(Optional.empty());
        // No report is currently in GENERATING status (previous one was FAILED)
        when(reportRepository.findFirstByBatchIdAndStatusOrderByCreatedAtDesc(101L, BatchAnalysisReportStatus.GENERATING))
                .thenReturn(Optional.empty());

        BatchAnalysisReport retryReport = new BatchAnalysisReport(batch1, supervisor1, "REQ-RETRY-FRESH");
        setField(retryReport, "id", 703L);
        when(reportRepository.saveAndFlush(any())).thenReturn(retryReport);

        BatchAnalysisReport result = txHelper.getOrCreateInitialReportTx(batch1, supervisor1, "REQ-RETRY-FRESH");
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(703L);
        assertThat(result.getStatus()).isEqualTo(BatchAnalysisReportStatus.GENERATING);
    }
}
