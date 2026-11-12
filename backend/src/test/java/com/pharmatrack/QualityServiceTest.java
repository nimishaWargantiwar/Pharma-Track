package com.pharmatrack;

import com.pharmatrack.dto.AuditLogResponseDto;
import com.pharmatrack.dto.BatchQualityDossierDto;
import com.pharmatrack.dto.QaDispositionRequestDto;
import com.pharmatrack.dto.QualityCheckCorrectionRequestDto;
import com.pharmatrack.dto.QualityCheckRequestDto;
import com.pharmatrack.dto.QualityCheckResponseDto;
import com.pharmatrack.dto.QualityQueueItemDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchQaReview;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.CapaAction;
import com.pharmatrack.entity.Deviation;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.QualityCheck;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.exception.InvalidQualityOperationException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchQaReviewRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.CapaActionRepository;
import com.pharmatrack.repository.DeviationRepository;
import com.pharmatrack.repository.QualityCheckRepository;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.QualityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link QualityService} and {@link AuditTrailService}, verifying:
 * - Quality Inspection Queue filtering (completed batches only)
 * - QC Check creation and audited correction with audit logging
 * - Pending/Failed/Zero QC checks blocking QA release
 * - Incomplete batches cannot be released or accept QC checks
 * - Successful QA release/hold/rejection and preservation of BatchStatus & yield
 */
@ExtendWith(MockitoExtension.class)
class QualityServiceTest {

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private QualityCheckRepository qualityCheckRepository;

    @Mock
    private BatchQaReviewRepository batchQaReviewRepository;

    @Mock
    private DeviationRepository deviationRepository;

    @Mock
    private CapaActionRepository capaActionRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditTrailService auditTrailService;
    private QualityService qualityService;

    private ManufacturingOrder completedOrder;
    private Batch completedBatch;
    private Batch inProgressBatch;
    private Batch plannedBatch;

    @BeforeEach
    void setUp() {
        auditTrailService = new AuditTrailService(auditLogRepository);
        qualityService = new QualityService(
                batchRepository,
                qualityCheckRepository,
                batchQaReviewRepository,
                deviationRepository,
                capaActionRepository,
                auditTrailService
        );

        Product product = new Product("AMOX-250", "Amoxicillin Capsules", "250 mg", "Capsule", "Antibiotic", ProductStatus.ACTIVE);
        product.setId(1L);
        Recipe recipe = new Recipe("RCP-AMOX-250-V3", product, 3, new BigDecimal("250.0000"), "kg", RecipeStatus.APPROVED, "V3");
        recipe.setId(10L);

        completedOrder = new ManufacturingOrder(
                "MO-2026-0001", product, recipe, 3, new BigDecimal("500.0000"), "kg", 2,
                OrderStatus.IN_PROGRESS, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 15), "Notes"
        );
        completedOrder.setId(100L);

        completedBatch = new Batch("BAT-2026-0001-01", completedOrder, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        completedBatch.setId(501L);
        completedBatch.setQaStatus(BatchQualityStatus.PENDING_QA);
        completedBatch.setActualYield(new BigDecimal("248.5000"));
        completedBatch.setEndTime(LocalDateTime.of(2026, 11, 10, 14, 0));

        inProgressBatch = new Batch("BAT-2026-0001-02", completedOrder, new BigDecimal("250.0000"), "kg",
                BatchStatus.IN_PROGRESS, LocalDate.of(2026, 11, 10), null);
        inProgressBatch.setId(502L);

        plannedBatch = new Batch("BAT-2026-0001-03", completedOrder, new BigDecimal("250.0000"), "kg",
                BatchStatus.PLANNED, null, null);
        plannedBatch.setId(503L);
        authenticateAs("qc.analyst", "QC_ANALYST");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, String... roles) {
        String[] authorities = new String[roles.length];
        for (int i = 0; i < roles.length; i++) {
            authorities[i] = roles[i].startsWith("ROLE_") ? roles[i] : "ROLE_" + roles[i];
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a", AuthorityUtils.createAuthorityList(authorities))
        );
    }

    // -------------------------------------------------------------------------
    // 1. Quality Inspection Queue
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. Queue returns only COMPLETED batches and excludes IN_PROGRESS/PLANNED batches")
    void queueExcludesNonCompletedBatches() {
        when(batchRepository.findByStatusWithDetailsOrderByEndTimeDesc(BatchStatus.COMPLETED))
                .thenReturn(List.of(completedBatch, inProgressBatch, plannedBatch));
        when(qualityCheckRepository.findByBatchIdIn(List.of(501L)))
                .thenReturn(List.of(
                        new QualityCheck(completedBatch, "Assay", "95-105%", "99.1", "%",
                                QualityCheckResult.PASS, true, "A. Nair", LocalDateTime.now(), null)
                ));
        when(batchQaReviewRepository.findByBatchIdInOrderByReviewedAtDescIdDesc(List.of(501L)))
                .thenReturn(List.of());

        List<QualityQueueItemDto> queue = qualityService.getQualityInspectionQueue(null);

        assertEquals(1, queue.size());
        assertEquals(501L, queue.get(0).getBatchId());
        assertEquals("BAT-2026-0001-01", queue.get(0).getBatchNumber());
        assertEquals(BatchQualityStatus.PENDING_QA, queue.get(0).getQaStatus());
        assertEquals(1, queue.get(0).getTotalChecksCount());
        assertEquals(1, queue.get(0).getPassedChecksCount());
        assertEquals(1, queue.get(0).getRequiredChecksCount());
        assertEquals(0, queue.get(0).getOptionalChecksCount());
        assertEquals(0, queue.get(0).getBlockingChecksCount());
        assertTrue(queue.get(0).isEligibleForRelease());
        assertTrue(queue.get(0).getReleaseBlockers().isEmpty());
        assertEquals(new BigDecimal("248.5000"), queue.get(0).getActualYield());
    }

    @Test
    @DisplayName("2. Opening dossier for non-COMPLETED batch is rejected")
    void getDossierRejectsNonCompletedBatch() {
        when(batchRepository.findById(502L)).thenReturn(Optional.of(inProgressBatch));

        InvalidQualityOperationException ex = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.getBatchQualityDossier(502L)
        );
        assertTrue(ex.getMessage().contains("COMPLETED"));
    }

    // -------------------------------------------------------------------------
    // 2. QC Check Creation & Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3. Create QC check succeeds on COMPLETED batch and appends AuditLog")
    void createQualityCheckAppendsAuditLog() {
        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.save(any(QualityCheck.class))).thenAnswer(inv -> {
            QualityCheck q = inv.getArgument(0);
            q.setId(701L);
            return q;
        });
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> {
            AuditLog log = inv.getArgument(0);
            log.setId(901L);
            return log;
        });

        authenticateAs("  A.Nair  ", "QC_ANALYST");
        QualityCheckRequestDto request = new QualityCheckRequestDto(
                "Assay (HPLC)", "95.0% - 105.0%", "99.4", "%",
                QualityCheckResult.PASS, true, null, "Verified duplicate injections"
        );

        QualityCheckResponseDto created = qualityService.createQualityCheck(501L, request);

        assertEquals(701L, created.getId());
        assertEquals("Assay (HPLC)", created.getTestName());
        assertEquals(QualityCheckResult.PASS, created.getResult());
        assertTrue(created.isLocked());

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AuditLog savedAudit = auditCaptor.getValue();
        assertEquals("QUALITY_CHECK", savedAudit.getEntityType());
        assertEquals(701L, savedAudit.getEntityId());
        assertEquals(501L, savedAudit.getBatchId());
        assertEquals("QC_CHECK_CREATED", savedAudit.getAction());
        assertEquals("a.nair", savedAudit.getActorName());
        assertNull(savedAudit.getBeforeValues());
        assertTrue(savedAudit.getAfterValues().contains("Assay (HPLC)"));
        assertTrue(savedAudit.getAfterValues().contains("PASS"));
    }

    @Test
    @DisplayName("4. Create QC check rejects missing measured value for PASS/FAIL and non-completed batches")
    void createQualityCheckValidatesBusinessRules() {
        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        QualityCheckRequestDto missingMeasureForPass = new QualityCheckRequestDto(
                "Assay", "95-105%", "   ", "%", QualityCheckResult.PASS, true, null, null
        );
        InvalidQualityOperationException ex1 = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.createQualityCheck(501L, missingMeasureForPass)
        );
        assertTrue(ex1.getMessage().contains("Measured value is mandatory"));

        when(batchRepository.findByIdWithLock(502L)).thenReturn(Optional.of(inProgressBatch));
        QualityCheckRequestDto validPayload = new QualityCheckRequestDto(
                "Assay", "95-105%", "99.0", "%", QualityCheckResult.PASS, true, null, null
        );
        InvalidQualityOperationException ex2 = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.createQualityCheck(502L, validPayload)
        );
        assertTrue(ex2.getMessage().contains("COMPLETED"));

        verify(auditLogRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 3. Audited QC Correction Mechanism
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5. Audited QC correction updates record and writes before/after AuditLog; blocks finalized batch")
    void correctQualityCheckWritesBeforeAfterAuditAndBlocksFinalizedBatch() {
        authenticateAs("qc.corrector", "QC_ANALYST");
        QualityCheck existing = new QualityCheck(
                completedBatch, "Assay", "95-105%", "93.1", "%",
                QualityCheckResult.FAIL, true, "Analyst 1", LocalDateTime.now().minusMinutes(30), "Initial reading"
        );
        existing.setId(710L);

        when(qualityCheckRepository.findByIdWithBatch(710L)).thenReturn(Optional.of(existing));
        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.save(any(QualityCheck.class))).thenAnswer(inv -> inv.getArgument(0));
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        QualityCheckCorrectionRequestDto correction = new QualityCheckCorrectionRequestDto(
                "Assay", "95-105%", "99.0", "%", QualityCheckResult.PASS, true,
                "Retest after HPLC calibration", "Calibration baseline corrected"
        );

        QualityCheckResponseDto updated = qualityService.correctQualityCheck(710L, correction);
        assertEquals(QualityCheckResult.PASS, updated.getResult());
        assertEquals("99.0", updated.getMeasuredValue());
        assertEquals(1, updated.getCorrectionCount());
        assertEquals("qc.corrector", updated.getCorrectedBy());

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AuditLog savedAudit = auditCaptor.getValue();
        assertEquals("QC_CHECK_CORRECTED", savedAudit.getAction());
        assertEquals("qc.corrector", savedAudit.getActorName());
        assertEquals("Calibration baseline corrected", savedAudit.getReason());
        assertTrue(savedAudit.getBeforeValues().contains("\"result\":\"FAIL\""));
        assertTrue(savedAudit.getAfterValues().contains("\"result\":\"PASS\""));

        // Now mark batch RELEASED and verify further QC correction is rejected
        completedBatch.setQaStatus(BatchQualityStatus.RELEASED);
        assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.correctQualityCheck(710L, correction)
        );
    }

    // -------------------------------------------------------------------------
    // 4. QA Disposition Release Gates & Workflows
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. Pending QC check blocks QA batch release")
    void pendingQcBlocksRelease() {
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        QualityCheck passCheck = new QualityCheck(completedBatch, "Assay", "95-105%", "99.0", "%",
                QualityCheckResult.PASS, true, "Analyst 1", LocalDateTime.now(), null);
        QualityCheck pendingCheck = new QualityCheck(completedBatch, "Microbial", "Absent", null, "CFU",
                QualityCheckResult.PENDING, true, "Analyst 2", LocalDateTime.now(), null);

        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(501L))
                .thenReturn(List.of(passCheck, pendingCheck));

        InvalidQualityOperationException ex = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        501L,
                        new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Trying to release")
                )
        );
        assertTrue(ex.getMessage().contains("PENDING"));
        assertEquals(BatchQualityStatus.PENDING_QA, completedBatch.getQaStatus());
        verify(batchQaReviewRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("7. Failed QC check blocks QA batch release")
    void failedQcBlocksRelease() {
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        QualityCheck passCheck = new QualityCheck(completedBatch, "Assay", "95-105%", "99.0", "%",
                QualityCheckResult.PASS, true, "Analyst 1", LocalDateTime.now(), null);
        QualityCheck failCheck = new QualityCheck(completedBatch, "Dissolution", ">= 80%", "71.5", "%",
                QualityCheckResult.FAIL, true, "Analyst 2", LocalDateTime.now(), null);

        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(501L))
                .thenReturn(List.of(passCheck, failCheck));

        InvalidQualityOperationException ex = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        501L,
                        new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Trying to release")
                )
        );
        assertTrue(ex.getMessage().contains("FAILED"));
        assertEquals(BatchQualityStatus.PENDING_QA, completedBatch.getQaStatus());
        verify(batchRepository, never()).save(any());
        verify(batchQaReviewRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("8. Zero QC checks blocks QA batch release")
    void zeroQcChecksBlocksRelease() {
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(501L)).thenReturn(List.of());

        InvalidQualityOperationException ex = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        501L,
                        new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "No checks yet")
                )
        );
        assertTrue(ex.getMessage().contains("at least one"));
    }

    @Test
    @DisplayName("9. Incomplete batches (IN_PROGRESS / PLANNED) cannot receive QA disposition or release")
    void incompleteBatchesCannotBeReleased() {
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        when(batchRepository.findByIdWithLock(502L)).thenReturn(Optional.of(inProgressBatch));
        InvalidQualityOperationException ex1 = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        502L,
                        new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Should fail")
                )
        );
        assertTrue(ex1.getMessage().contains("COMPLETED"));

        when(batchRepository.findByIdWithLock(503L)).thenReturn(Optional.of(plannedBatch));
        InvalidQualityOperationException ex2 = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        503L,
                        new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Should fail")
                )
        );
        assertTrue(ex2.getMessage().contains("COMPLETED"));
    }

    @Test
    @DisplayName("10. Successful QA release when all required QC checks PASS persists review, audit log, and leaves BatchStatus intact")
    void successfulReleaseWhenAllRequiredChecksPass() {
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        QualityCheck check1 = new QualityCheck(completedBatch, "Assay", "95-105%", "99.4", "%",
                QualityCheckResult.PASS, true, "Analyst 1", LocalDateTime.now(), null);
        check1.setId(1L);
        QualityCheck check2 = new QualityCheck(completedBatch, "Dissolution", ">= 80%", "92.0", "%",
                QualityCheckResult.PASS, true, "Analyst 2", LocalDateTime.now(), null);
        check2.setId(2L);

        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(501L)).thenReturn(List.of(check1, check2));
        when(batchRepository.save(any(Batch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchQaReviewRepository.save(any(BatchQaReview.class))).thenAnswer(inv -> {
            BatchQaReview review = inv.getArgument(0);
            review.setId(88L);
            return review;
        });
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> {
            AuditLog log = inv.getArgument(0);
            log.setId(99L);
            return log;
        });
        when(batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(501L))
                .thenReturn(List.of(new BatchQaReview(completedBatch, BatchQualityStatus.PENDING_QA,
                        BatchQualityStatus.RELEASED, "dr.menon", "All specs and eBR steps passed",
                        LocalDateTime.now(), 2, 2, 0, 0)));
        when(auditLogRepository.findByBatchIdOrderByOccurredAtDescIdDesc(501L))
                .thenReturn(List.of(new AuditLog("BATCH_QA_DISPOSITION", 88L, "BAT-2026-0001-01", 501L,
                        "QA_DISPOSITION_RELEASED", "dr.menon", LocalDateTime.now(),
                        "All specs and eBR steps passed", null, "{\"qaStatus\":\"RELEASED\"}")));

        BatchQualityDossierDto dossier = qualityService.recordQaDisposition(
                501L,
                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "All specs and eBR steps passed")
        );

        assertEquals(BatchQualityStatus.RELEASED, dossier.getQaStatus());
        assertEquals(BatchStatus.COMPLETED, completedBatch.getStatus(), "Manufacturing BatchStatus must remain COMPLETED");
        assertEquals(new BigDecimal("248.5000"), completedBatch.getActualYield(), "Actual yield must remain intact");
        assertEquals(1, dossier.getQaReviews().size());
        assertEquals(BatchQualityStatus.RELEASED, dossier.getQaReviews().get(0).getDecisionStatus());

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertEquals("QA_DISPOSITION_RELEASED", auditCaptor.getValue().getAction());
        assertEquals("dr.menon", auditCaptor.getValue().getActorName());
        assertEquals("All specs and eBR steps passed", auditCaptor.getValue().getReason());
    }

    @Test
    @DisplayName("11. Rejecting a failed batch sets REJECTED disposition and prevents subsequent changes")
    void rejectDispositionFinalizesBatchAndBlocksFurtherTransitions() {
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        QualityCheck failCheck = new QualityCheck(completedBatch, "Dissolution", ">= 80%", "65.0", "%",
                QualityCheckResult.FAIL, true, "Analyst 1", LocalDateTime.now(), null);
        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(501L)).thenReturn(List.of(failCheck));
        when(batchRepository.save(any(Batch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchQaReviewRepository.save(any(BatchQaReview.class))).thenAnswer(inv -> {
            BatchQaReview review = inv.getArgument(0);
            review.setId(89L);
            return review;
        });
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(501L)).thenReturn(List.of());
        when(auditLogRepository.findByBatchIdOrderByOccurredAtDescIdDesc(501L)).thenReturn(List.of());

        BatchQualityDossierDto dossier = qualityService.recordQaDisposition(
                501L,
                new QaDispositionRequestDto(BatchQualityStatus.REJECTED, "OOS dissolution failure")
        );

        assertEquals(BatchQualityStatus.REJECTED, dossier.getQaStatus());

        assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        501L,
                        new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Should not reopen")
                )
        );
    }

    @Test
    @DisplayName("12. Missing batch ID throws ResourceNotFoundException cleanly")
    void unknownBatchThrowsNotFound() {
        when(batchRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> qualityService.getBatchQualityDossier(999L));
    }

    // -------------------------------------------------------------------------
    // 5. Numeric Acceptance Specification & Consistency Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("13. Valid PASS and valid FAIL succeed against minimum, maximum, and range specs; failed required check blocks QA release")
    void validPassAndFailAndReleaseBlockOnFailedRequiredCheck() {
        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.save(any(QualityCheck.class))).thenAnswer(inv -> {
            QualityCheck q = inv.getArgument(0);
            q.setId(801L);
            return q;
        });
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        // Valid PASS against Minimum 80% dissolution within 30 minutes
        QualityCheckResponseDto validMinPass = qualityService.createQualityCheck(
                501L,
                new QualityCheckRequestDto(
                        "Dissolution Test", "Minimum 80% dissolution within 30 minutes",
                        "86.5", "%", QualityCheckResult.PASS, true, null, null
                )
        );
        assertEquals(QualityCheckResult.PASS, validMinPass.getResult());

        // Valid FAIL (measured 65 vs minimum 80%)
        QualityCheckResponseDto validMinFail = qualityService.createQualityCheck(
                501L,
                new QualityCheckRequestDto(
                        "Dissolution Test", "Minimum 80% dissolution within 30 minutes",
                        "65", "%", QualityCheckResult.FAIL, true, null, "Out of specification"
                )
        );
        assertEquals(QualityCheckResult.FAIL, validMinFail.getResult());

        // Verify required failed check blocks QA release
        QualityCheck savedFail = new QualityCheck(
                completedBatch, "Dissolution Test", "Minimum 80% dissolution within 30 minutes",
                "65", "%", QualityCheckResult.FAIL, true, "QC Analyst", LocalDateTime.now(), null
        );
        when(batchRepository.findById(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(501L)).thenReturn(List.of(savedFail));
        when(batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(501L)).thenReturn(List.of());
        when(auditLogRepository.findByBatchIdOrderByOccurredAtDescIdDesc(501L)).thenReturn(List.of());

        BatchQualityDossierDto dossier = qualityService.getBatchQualityDossier(501L);
        assertFalse(dossier.isEligibleForRelease());
        assertTrue(dossier.getReleaseBlockers().stream().anyMatch(b -> b.contains("required QC check(s) have FAILED")));

        authenticateAs("Dr.Menon", "QA_REVIEWER");
        InvalidQualityOperationException releaseEx = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        501L,
                        new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Attempting release")
                )
        );
        assertTrue(releaseEx.getMessage().contains("required QC check(s) have FAILED"));
    }

    @Test
    @DisplayName("14. Reject inconsistent PASS and inconsistent FAIL on createQualityCheck and correctQualityCheck without persistence")
    void inconsistentPassAndFailRejectedOnCreateAndCorrection() {
        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));

        // Inconsistent PASS: 65 against Minimum 80%
        QualityCheckRequestDto inconsistentPass = new QualityCheckRequestDto(
                "Dissolution Test", "Minimum 80% dissolution within 30 minutes",
                "65", "%", QualityCheckResult.PASS, true, null, null
        );
        InvalidQualityOperationException exPass = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.createQualityCheck(501L, inconsistentPass)
        );
        assertTrue(exPass.getMessage().contains("cannot be recorded as PASS"));

        // Inconsistent FAIL: 88 against Minimum 80%
        QualityCheckRequestDto inconsistentFail = new QualityCheckRequestDto(
                "Dissolution Test", "Minimum 80% dissolution within 30 minutes",
                "88", "%", QualityCheckResult.FAIL, true, null, null
        );
        InvalidQualityOperationException exFail = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.createQualityCheck(501L, inconsistentFail)
        );
        assertTrue(exFail.getMessage().contains("cannot be recorded as FAIL"));

        // Inconsistent correction: keeping 65 while setting PASS
        QualityCheck existingFail = new QualityCheck(
                completedBatch, "Dissolution Test", "Minimum 80% dissolution within 30 minutes",
                "65", "%", QualityCheckResult.FAIL, true, "QC Analyst", LocalDateTime.now().minusMinutes(10), null
        );
        existingFail.setId(802L);
        when(qualityCheckRepository.findByIdWithBatch(802L)).thenReturn(Optional.of(existingFail));

        QualityCheckCorrectionRequestDto inconsistentCorrection = new QualityCheckCorrectionRequestDto(
                "Dissolution Test", "Minimum 80% dissolution within 30 minutes",
                "65", "%", QualityCheckResult.PASS, true, "Overriding to PASS", "Reason"
        );
        InvalidQualityOperationException exCorrect = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.correctQualityCheck(802L, inconsistentCorrection)
        );
        assertTrue(exCorrect.getMessage().contains("cannot be recorded as PASS"));

        verify(qualityCheckRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("15. Qualitative QC checks accept both PASS and FAIL without numeric limit rejection")
    void qualitativeChecksWorkWithoutNumericLimits() {
        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.save(any(QualityCheck.class))).thenAnswer(inv -> {
            QualityCheck q = inv.getArgument(0);
            q.setId(803L);
            return q;
        });
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        QualityCheckResponseDto passAppearance = qualityService.createQualityCheck(
                501L,
                new QualityCheckRequestDto(
                        "Appearance Test", "Uniform appearance; free from visible defects",
                        "Conforms", "Visual Inspection", QualityCheckResult.PASS, true, null, null
                )
        );
        assertEquals(QualityCheckResult.PASS, passAppearance.getResult());

        QualityCheckResponseDto failAppearance = qualityService.createQualityCheck(
                501L,
                new QualityCheckRequestDto(
                        "Appearance Test", "Uniform appearance; free from visible defects",
                        "Chipped tablets", "Visual Inspection", QualityCheckResult.FAIL, true, null, null
                )
        );
        assertEquals(QualityCheckResult.FAIL, failAppearance.getResult());
    }

    @Test
    @DisplayName("16. Failed required Dissolution check (65% vs Minimum 80%) rejects RELEASED with zero QA review or audit records saved")
    void singleFailedRequiredCheckBlocksReleaseWithZeroSideEffects() {
        authenticateAs("qa.lead", "QA_REVIEWER");
        Batch targetBatch = new Batch("BAT-2026-0009-01", completedOrder, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        targetBatch.setId(10L);
        targetBatch.setQaStatus(BatchQualityStatus.PENDING_QA);
        targetBatch.setActualYield(new BigDecimal("248.5000"));
        targetBatch.setEndTime(LocalDateTime.of(2026, 11, 10, 2, 12));

        QualityCheck failedDissolution = new QualityCheck(
                targetBatch, "Dissolution Test", "Minimum 80%", "65", "%",
                QualityCheckResult.FAIL, true, "Demo QC Inspector", LocalDateTime.now(), "OOS result"
        );
        failedDissolution.setId(2L);

        when(batchRepository.findByIdWithLock(10L)).thenReturn(Optional.of(targetBatch));
        when(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(10L))
                .thenReturn(List.of(failedDissolution));

        InvalidQualityOperationException ex = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        10L,
                        new QaDispositionRequestDto(
                                BatchQualityStatus.RELEASED,
                                "Direct API attempt to release batch with failed required QC check"
                        )
                )
        );

        assertTrue(ex.getMessage().contains("1 required QC check(s) have FAILED"));
        assertEquals(BatchQualityStatus.PENDING_QA, targetBatch.getQaStatus());
        verify(batchRepository, never()).save(any());
        verify(batchQaReviewRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 6. Phase 2 QA Deviation Release Gate & Dossier Enrichment
    // -------------------------------------------------------------------------

    private Deviation buildSampleDeviation(Long id, String devNum, Batch batch, DeviationStatus status) {
        Deviation dev = new Deviation(
                devNum, batch, null, null, null,
                "Observed process anomaly", DeviationCategory.PROCESS_PARAMETER,
                DeviationSeverity.MAJOR, DeviationPriority.HIGH, status,
                "Parameter excursion detected during compression.", null,
                "Operator A", LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)
        );
        dev.setId(id);
        return dev;
    }

    @Test
    @DisplayName("17. Single OPEN deviation blocks QA RELEASED with zero DB saves even when all QC checks pass")
    void releaseBlockedByOpenDeviation() {
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        QualityCheck passCheck = new QualityCheck(completedBatch, "Assay", "95-105%", "99.1", "%",
                QualityCheckResult.PASS, true, "Analyst 1", LocalDateTime.now(), null);
        passCheck.setId(1L);
        Deviation openDev = buildSampleDeviation(11L, "DEV-2026-0001", completedBatch, DeviationStatus.OPEN);

        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(501L)).thenReturn(List.of(passCheck));
        when(deviationRepository.findByBatchIdAndStatusNotWithLock(501L, DeviationStatus.CLOSED))
                .thenReturn(List.of(openDev));

        InvalidQualityOperationException ex = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        501L,
                        new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Release attempt")
                )
        );

        assertTrue(ex.getMessage().contains("1 unresolved deviation(s)"));
        assertTrue(ex.getMessage().contains("DEV-2026-0001"));
        assertEquals(BatchQualityStatus.PENDING_QA, completedBatch.getQaStatus());
        verify(batchRepository, never()).save(any());
        verify(batchQaReviewRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("18. UNDER_INVESTIGATION and multiple unresolved deviations are counted and block QA RELEASED")
    void multipleUnresolvedDeviationsCountedAndBlockRelease() {
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        QualityCheck passCheck = new QualityCheck(completedBatch, "Assay", "95-105%", "99.1", "%",
                QualityCheckResult.PASS, true, "Analyst 1", LocalDateTime.now(), null);
        passCheck.setId(1L);
        Deviation d1 = buildSampleDeviation(11L, "DEV-2026-0001", completedBatch, DeviationStatus.OPEN);
        Deviation d2 = buildSampleDeviation(12L, "DEV-2026-0002", completedBatch, DeviationStatus.UNDER_INVESTIGATION);
        Deviation d3 = buildSampleDeviation(13L, "DEV-2026-0003", completedBatch, DeviationStatus.PENDING_VERIFICATION);

        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(501L)).thenReturn(List.of(passCheck));
        when(deviationRepository.findByBatchIdAndStatusNotWithLock(501L, DeviationStatus.CLOSED))
                .thenReturn(List.of(d1, d2, d3));

        InvalidQualityOperationException ex = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        501L,
                        new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Release attempt")
                )
        );

        assertTrue(ex.getMessage().contains("3 unresolved deviation(s)"));
        verify(batchRepository, never()).save(any());
        verify(batchQaReviewRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());

        // Dossier also exposes the exact blocker and open deviation count
        when(batchRepository.findById(501L)).thenReturn(Optional.of(completedBatch));
        when(batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(501L)).thenReturn(List.of());
        when(deviationRepository.findByBatchIdWithDetailsOrderByReportedAtDesc(501L)).thenReturn(List.of(d1, d2, d3));
        when(auditLogRepository.findByBatchIdOrderByOccurredAtDescIdDesc(501L)).thenReturn(List.of());

        BatchQualityDossierDto dossier = qualityService.getBatchQualityDossier(501L);
        assertFalse(dossier.isEligibleForRelease());
        assertEquals(3, dossier.getOpenDeviationsCount());
        assertEquals(3, dossier.getTotalDeviationsCount());
        assertTrue(dossier.getReleaseBlockers().stream().anyMatch(b -> b.contains("3 unresolved deviation(s)")));
    }

    @Test
    @DisplayName("19. ON_HOLD and REJECTED dispositions remain permitted despite unresolved deviations")
    void onHoldAndRejectedAllowedDespiteOpenDeviations() {
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        QualityCheck passCheck = new QualityCheck(completedBatch, "Assay", "95-105%", "99.1", "%",
                QualityCheckResult.PASS, true, "Analyst 1", LocalDateTime.now(), null);
        passCheck.setId(1L);
        Deviation d1 = buildSampleDeviation(11L, "DEV-2026-0001", completedBatch, DeviationStatus.OPEN);

        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(501L)).thenReturn(List.of(passCheck));
        when(batchRepository.save(any(Batch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchQaReviewRepository.save(any(BatchQaReview.class))).thenAnswer(inv -> {
            BatchQaReview r = inv.getArgument(0);
            r.setId(201L);
            return r;
        });
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(deviationRepository.findByBatchIdWithDetailsOrderByReportedAtDesc(501L)).thenReturn(List.of(d1));

        BatchQualityDossierDto holdDossier = qualityService.recordQaDisposition(
                501L,
                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Placing batch on hold pending DEV-2026-0001")
        );
        assertEquals(BatchQualityStatus.ON_HOLD, holdDossier.getQaStatus());
        assertEquals(1, holdDossier.getOpenDeviationsCount());
        assertFalse(holdDossier.isEligibleForRelease());

        BatchQualityDossierDto rejectedDossier = qualityService.recordQaDisposition(
                501L,
                new QaDispositionRequestDto(BatchQualityStatus.REJECTED, "Rejecting batch due to confirmed root cause")
        );
        assertEquals(BatchQualityStatus.REJECTED, rejectedDossier.getQaStatus());
        verify(deviationRepository, never()).findByBatchIdAndStatusNotWithLock(any(), any());
    }

    @Test
    @DisplayName("20. CLOSED deviation allows QA RELEASED when all required QC checks pass; Queue exposes open count")
    void closedDeviationAllowsReleaseWhenQcPasses() {
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        QualityCheck passCheck = new QualityCheck(completedBatch, "Assay", "95-105%", "99.1", "%",
                QualityCheckResult.PASS, true, "Analyst 1", LocalDateTime.now(), null);
        passCheck.setId(1L);
        Deviation closedDev = buildSampleDeviation(11L, "DEV-2026-0001", completedBatch, DeviationStatus.CLOSED);
        closedDev.setClosedAt(LocalDateTime.now().minusMinutes(5));

        when(batchRepository.findByStatusWithDetailsOrderByEndTimeDesc(BatchStatus.COMPLETED)).thenReturn(List.of(completedBatch));
        when(qualityCheckRepository.findByBatchIdIn(List.of(501L))).thenReturn(List.of(passCheck));
        when(batchQaReviewRepository.findByBatchIdInOrderByReviewedAtDescIdDesc(List.of(501L))).thenReturn(List.of());
        when(deviationRepository.findByBatchIdIn(List.of(501L))).thenReturn(List.of(closedDev));

        List<QualityQueueItemDto> queue = qualityService.getQualityInspectionQueue(null);
        assertEquals(1, queue.size());
        assertEquals(1, queue.get(0).getTotalDeviationsCount());
        assertEquals(0, queue.get(0).getOpenDeviationsCount());

        when(batchRepository.findByIdWithLock(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(501L)).thenReturn(List.of(passCheck));
        when(deviationRepository.findByBatchIdAndStatusNotWithLock(501L, DeviationStatus.CLOSED)).thenReturn(List.of());
        when(batchRepository.save(any(Batch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchQaReviewRepository.save(any(BatchQaReview.class))).thenAnswer(inv -> {
            BatchQaReview r = inv.getArgument(0);
            r.setId(202L);
            return r;
        });
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(deviationRepository.findByBatchIdWithDetailsOrderByReportedAtDesc(501L)).thenReturn(List.of(closedDev));

        BatchQualityDossierDto released = qualityService.recordQaDisposition(
                501L,
                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Deviation closed & all QC checks pass")
        );
        assertEquals(BatchQualityStatus.RELEASED, released.getQaStatus());
        assertEquals(1, released.getTotalDeviationsCount());
        assertEquals(0, released.getOpenDeviationsCount());
    }

    @Test
    @DisplayName("21. Queue and Dossier return identical blockers for missing required QC, optional FAIL/PENDING, and combined failures")
    void queueAndDossierReportConsistentOptionalAndRequiredBlockers() {
        QualityCheck reqPass = new QualityCheck(completedBatch, "Assay", "95-105%", "99.1", "%",
                QualityCheckResult.PASS, true, "Analyst 1", LocalDateTime.now(), null);
        QualityCheck optFail = new QualityCheck(completedBatch, "Friability", "Max 1.0%", "1.4", "%",
                QualityCheckResult.FAIL, false, "Analyst 1", LocalDateTime.now(), null);
        QualityCheck optPending = new QualityCheck(completedBatch, "Moisture", "Max 2.0%", null, "%",
                QualityCheckResult.PENDING, false, "Analyst 2", LocalDateTime.now(), null);

        when(batchRepository.findByStatusWithDetailsOrderByEndTimeDesc(BatchStatus.COMPLETED)).thenReturn(List.of(completedBatch));
        when(qualityCheckRepository.findByBatchIdIn(List.of(501L))).thenReturn(List.of(reqPass, optFail, optPending));
        when(batchQaReviewRepository.findByBatchIdInOrderByReviewedAtDescIdDesc(List.of(501L))).thenReturn(List.of());
        when(deviationRepository.findByBatchIdIn(List.of(501L))).thenReturn(List.of());

        List<QualityQueueItemDto> queue = qualityService.getQualityInspectionQueue(null);
        assertEquals(1, queue.size());
        QualityQueueItemDto item = queue.get(0);
        assertFalse(item.isEligibleForRelease());
        assertEquals(1, item.getRequiredChecksCount());
        assertEquals(2, item.getOptionalChecksCount());
        assertEquals(2, item.getBlockingChecksCount());
        assertEquals(0, item.getRequiredBlockingChecksCount());
        assertEquals(List.of(
                "1 optional QC check(s) have FAILED",
                "1 optional QC check(s) are still PENDING"
        ), item.getReleaseBlockers());

        when(batchRepository.findById(501L)).thenReturn(Optional.of(completedBatch));
        when(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(501L)).thenReturn(List.of(reqPass, optFail, optPending));
        when(batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(501L)).thenReturn(List.of());
        when(deviationRepository.findByBatchIdWithDetailsOrderByReportedAtDesc(501L)).thenReturn(List.of());
        when(auditLogRepository.findByBatchIdOrderByOccurredAtDescIdDesc(501L)).thenReturn(List.of());

        BatchQualityDossierDto dossier = qualityService.getBatchQualityDossier(501L);
        assertFalse(dossier.isEligibleForRelease());
        assertEquals(item.getReleaseBlockers(), dossier.getReleaseBlockers());
    }

    @Test
    @DisplayName("22. QC creation, QC correction, and QA disposition fail closed without authentication and write zero audits")
    void qualityMutationsFailClosedWithoutAuthentication() {
        SecurityContextHolder.clearContext();

        assertThrows(AuthenticationCredentialsNotFoundException.class, () ->
                qualityService.createQualityCheck(501L, new QualityCheckRequestDto(
                        "Assay", "95.0-105.0%", "99.5", "%", QualityCheckResult.PASS, true, null, "No auth"
                )));

        assertThrows(AuthenticationCredentialsNotFoundException.class, () ->
                qualityService.correctQualityCheck(900L, new QualityCheckCorrectionRequestDto(
                        "Assay", "95.0-105.0%", "99.5", "%", QualityCheckResult.PASS, true, "No auth", "Retest"
                )));

        assertThrows(AuthenticationCredentialsNotFoundException.class, () ->
                qualityService.recordQaDisposition(501L, new QaDispositionRequestDto(
                        BatchQualityStatus.RELEASED, "Should be blocked without auth"
                )));

        verify(qualityCheckRepository, never()).save(any());
        verify(batchQaReviewRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }
}

