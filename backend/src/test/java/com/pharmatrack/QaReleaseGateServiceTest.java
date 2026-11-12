package com.pharmatrack;

import com.pharmatrack.dto.BatchQualityDossierDto;
import com.pharmatrack.dto.CloseDeviationRequestDto;
import com.pharmatrack.dto.CompleteCapaActionRequestDto;
import com.pharmatrack.dto.CreateCapaActionRequestDto;
import com.pharmatrack.dto.CreateDeviationRequestDto;
import com.pharmatrack.dto.QaDispositionRequestDto;
import com.pharmatrack.dto.QualityCheckCorrectionRequestDto;
import com.pharmatrack.dto.QualityCheckRequestDto;
import com.pharmatrack.dto.QualityQueueItemDto;
import com.pharmatrack.dto.RecordRootCauseRequestDto;
import com.pharmatrack.dto.ReopenCapaRequestDto;
import com.pharmatrack.dto.StartCapaActionRequestDto;
import com.pharmatrack.dto.StartCapaRequestDto;
import com.pharmatrack.dto.StartInvestigationRequestDto;
import com.pharmatrack.dto.SubmitVerificationRequestDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchQaReview;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.CapaType;
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
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchExecutionStepRepository;
import com.pharmatrack.repository.BatchQaReviewRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.CapaActionRepository;
import com.pharmatrack.repository.DeviationRepository;
import com.pharmatrack.repository.QualityCheckRepository;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.DeviationService;
import com.pharmatrack.service.QualityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 Negative Testing – Step 4: QA Release Gate Integrity (Service + Real H2 JPA Layer).
 * Verifies with real Spring Data JPA persistence and zero mocks that QA release prerequisites
 * cannot be bypassed and rejected release attempts leave batches, reviews, and audit logs unchanged.
 */
@DataJpaTest
class QaReleaseGateServiceTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private QualityCheckRepository qualityCheckRepository;

    @Autowired
    private BatchQaReviewRepository batchQaReviewRepository;

    @Autowired
    private DeviationRepository deviationRepository;

    @Autowired
    private CapaActionRepository capaActionRepository;

    @Autowired
    private BatchExecutionStepRepository executionStepRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TestEntityManager entityManager;

    private QualityService qualityService;
    private DeviationService deviationService;

    private void authenticateAs(String username, String... roles) {
        String[] authorities = Arrays.stream(roles)
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                .toArray(String[]::new);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "N/A", AuthorityUtils.createAuthorityList(authorities))
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUpServices() {
        AuditTrailService auditTrailService = new AuditTrailService(auditLogRepository);
        qualityService = new QualityService(
                batchRepository,
                qualityCheckRepository,
                batchQaReviewRepository,
                deviationRepository,
                capaActionRepository,
                auditTrailService
        );
        deviationService = new DeviationService(
                deviationRepository,
                capaActionRepository,
                batchRepository,
                qualityCheckRepository,
                executionStepRepository,
                auditLogRepository,
                auditTrailService
        );
        authenticateAs("dr.menon", "QA_REVIEWER");
    }

    private Batch seedBatch(String tag, BatchStatus batchStatus, BatchQualityStatus qaStatus) {
        int n = SEQ.getAndIncrement();
        Product product = entityManager.persistAndFlush(new Product(
                "PRD-GATE-" + tag + "-" + n,
                "Gate Product " + tag,
                "500 mg",
                "Tablet",
                "Analgesic",
                ProductStatus.ACTIVE
        ));
        Recipe recipe = entityManager.persistAndFlush(new Recipe(
                "RCP-GATE-" + tag + "-" + n,
                product,
                1,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Master Recipe " + tag
        ));
        ManufacturingOrder order = entityManager.persistAndFlush(new ManufacturingOrder(
                "MO-GATE-" + tag + "-" + n,
                product,
                recipe,
                1,
                new BigDecimal("250.0000"),
                "kg",
                1,
                OrderStatus.IN_PROGRESS,
                LocalDate.now(DeviationService.APP_ZONE_ID),
                LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(5),
                "Release gate test order"
        ));
        Batch batch = new Batch(
                "BAT-GATE-" + tag + "-" + n,
                order,
                new BigDecimal("250.0000"),
                "kg",
                batchStatus,
                LocalDate.now(DeviationService.APP_ZONE_ID),
                null
        );
        batch.setQaStatus(qaStatus);
        if (batchStatus == BatchStatus.COMPLETED) {
            batch.setActualYield(new BigDecimal("248.5000"));
            batch.setStartTime(LocalDateTime.now(DeviationService.APP_ZONE_ID).minusHours(4));
            batch.setEndTime(LocalDateTime.now(DeviationService.APP_ZONE_ID).minusHours(1));
        }
        return entityManager.persistAndFlush(batch);
    }

    private QualityCheck addQcCheck(Batch batch, String testName, String spec, String measured,
                                    QualityCheckResult result, boolean required) {
        return qualityCheckRepository.saveAndFlush(new QualityCheck(
                batch,
                testName,
                spec,
                measured,
                "%",
                result,
                required,
                "QC Analyst",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(15),
                "Seed QC check"
        ));
    }

    private Long createOpenDeviation(Batch batch, String title) {
        return deviationService.createDeviation(new CreateDeviationRequestDto(
                batch.getId(),
                null,
                null,
                null,
                title,
                DeviationCategory.PROCESS_PARAMETER,
                DeviationSeverity.MAJOR,
                DeviationPriority.HIGH,
                "Detailed excursion description for " + title,
                "Lot placed under containment.",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(10)
        )).getId();
    }

    private void assertRejectedReleaseLeavesDatabaseUnchanged(Long batchId,
                                                              BatchQualityStatus expectedQaStatus,
                                                              String expectedMessageFragment) {
        long reviewsBefore = batchQaReviewRepository.count();
        long auditsBefore = auditLogRepository.count();

        InvalidQualityOperationException ex = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        batchId,
                        new QaDispositionRequestDto(
                                BatchQualityStatus.RELEASED,
                                "Attempt to bypass release gate"
                        )
                )
        );
        assertTrue(ex.getMessage().contains(expectedMessageFragment),
                "Expected error message to contain '" + expectedMessageFragment + "' but was: " + ex.getMessage());

        entityManager.flush();
        entityManager.clear();

        Batch reloaded = batchRepository.findById(batchId).orElseThrow();
        assertEquals(expectedQaStatus, reloaded.getQaStatus(),
                "Batch qaStatus must remain unchanged after rejected RELEASED attempt");
        assertEquals(reviewsBefore, batchQaReviewRepository.count(),
                "No BatchQaReview row may be created on rejected RELEASED attempt");
        assertEquals(auditsBefore, auditLogRepository.count(),
                "No AuditLog row may be created on rejected RELEASED attempt");
    }

    @Test
    @DisplayName("1. Every non-CLOSED deviation stage (OPEN, UNDER_INVESTIGATION, ROOT_CAUSE_IDENTIFIED, CAPA_IN_PROGRESS, PENDING_VERIFICATION) individually blocks QA RELEASED with zero DB mutations")
    void allNonClosedDeviationStagesIndividuallyBlockReleaseWithZeroDbChanges() {
        Batch batch = seedBatch("DEVSTAGES", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(batch, "Assay (HPLC)", "95.0% - 105.0%", "99.3", QualityCheckResult.PASS, true);

        Long devId = createOpenDeviation(batch, "Granulation temperature drift");
        LocalDate dueDate = LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(3);

        // Stage 1: OPEN
        assertFalse(qualityService.getBatchQualityDossier(batch.getId()).isEligibleForRelease());
        assertRejectedReleaseLeavesDatabaseUnchanged(batch.getId(), BatchQualityStatus.PENDING_QA, "[OPEN]");

        // Stage 2: UNDER_INVESTIGATION
        deviationService.startInvestigation(devId, new StartInvestigationRequestDto("Reviewing logs"));
        assertRejectedReleaseLeavesDatabaseUnchanged(batch.getId(), BatchQualityStatus.PENDING_QA, "[UNDER_INVESTIGATION]");

        // Stage 3: ROOT_CAUSE_IDENTIFIED
        deviationService.recordRootCause(devId, new RecordRootCauseRequestDto(
                "Equipment", "Faulty RTD probe", "Single batch affected", "Calibrate RTD"));
        assertEquals(DeviationStatus.ROOT_CAUSE_IDENTIFIED,
                deviationRepository.findById(devId).orElseThrow().getStatus());
        assertFalse(qualityService.getBatchQualityDossier(batch.getId()).isEligibleForRelease());
        assertRejectedReleaseLeavesDatabaseUnchanged(batch.getId(), BatchQualityStatus.PENDING_QA, "[ROOT_CAUSE_IDENTIFIED]");

        // Stage 4: CAPA_IN_PROGRESS
        var capa = deviationService.createCapaAction(devId, new CreateCapaActionRequestDto(
                CapaType.CORRECTIVE, "Replace RTD probe", "Install calibrated RTD probe",
                "Eng. Kumar", dueDate));
        deviationService.startCapa(devId, new StartCapaRequestDto("Starting remediation"));
        assertEquals(DeviationStatus.CAPA_IN_PROGRESS,
                deviationRepository.findById(devId).orElseThrow().getStatus());
        assertFalse(qualityService.getBatchQualityDossier(batch.getId()).isEligibleForRelease());
        assertRejectedReleaseLeavesDatabaseUnchanged(batch.getId(), BatchQualityStatus.PENDING_QA, "[CAPA_IN_PROGRESS]");

        // Stage 5: PENDING_VERIFICATION
        deviationService.startCapaAction(capa.getId(), new StartCapaActionRequestDto("Working"));
        deviationService.completeCapaAction(capa.getId(), new CompleteCapaActionRequestDto("Cert RTD-11"));
        deviationService.submitForVerification(devId, new SubmitVerificationRequestDto("Ready for QA"));
        assertEquals(DeviationStatus.PENDING_VERIFICATION,
                deviationRepository.findById(devId).orElseThrow().getStatus());
        assertFalse(qualityService.getBatchQualityDossier(batch.getId()).isEligibleForRelease());
        assertRejectedReleaseLeavesDatabaseUnchanged(batch.getId(), BatchQualityStatus.PENDING_QA, "[PENDING_VERIFICATION]");

        // Verify cross-batch isolation: another clean batch CAN be released despite this batch's unresolved deviation
        Batch cleanOtherBatch = seedBatch("CLEANOTHER", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(cleanOtherBatch, "Assay (HPLC)", "95.0% - 105.0%", "99.5", QualityCheckResult.PASS, true);
        BatchQualityDossierDto releasedOther = qualityService.recordQaDisposition(
                cleanOtherBatch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Independent clean batch")
        );
        assertEquals(BatchQualityStatus.RELEASED, releasedOther.getQaStatus());
    }

    @Test
    @DisplayName("2. Mixed CLOSED + non-CLOSED deviations on same batch count only unresolved deviation and still block QA RELEASED")
    void mixedClosedAndUnresolvedDeviationsOnSameBatchStillBlockRelease() {
        Batch batch = seedBatch("MIXDEV", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(batch, "Assay", "95-105%", "99.0", QualityCheckResult.PASS, true);

        Long closedDevId = createOpenDeviation(batch, "Closed deviation 1");
        Long activeDevId = createOpenDeviation(batch, "Still CAPA_IN_PROGRESS deviation 2");
        LocalDate dueDate = LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(2);

        // Close dev1 cleanly
        deviationService.startInvestigation(closedDevId, new StartInvestigationRequestDto("Inv 1"));
        deviationService.recordRootCause(closedDevId, new RecordRootCauseRequestDto(
                "Method", "Minor typo", "None", null));
        var capa1 = deviationService.createCapaAction(closedDevId, new CreateCapaActionRequestDto(
                CapaType.CORRECTIVE, "Fix typo", "Correct log entry", "QA Lead", dueDate));
        deviationService.startCapa(closedDevId, new StartCapaRequestDto("Start"));
        deviationService.completeCapaAction(capa1.getId(), new CompleteCapaActionRequestDto("Done"));
        deviationService.submitForVerification(closedDevId, new SubmitVerificationRequestDto("Submitted"));
        deviationService.closeDeviation(closedDevId, new CloseDeviationRequestDto("Closed"));

        // Leave dev2 in CAPA_IN_PROGRESS
        deviationService.startInvestigation(activeDevId, new StartInvestigationRequestDto("Inv 2"));
        deviationService.recordRootCause(activeDevId, new RecordRootCauseRequestDto(
                "Equipment", "Valve wear", "Batch hold", null));
        deviationService.createCapaAction(activeDevId, new CreateCapaActionRequestDto(
                CapaType.CORRECTIVE, "Replace valve", "Replace diaphragm valve", "Eng", dueDate));
        deviationService.startCapa(activeDevId, new StartCapaRequestDto("Executing"));

        BatchQualityDossierDto dossier = qualityService.getBatchQualityDossier(batch.getId());
        assertEquals(2, dossier.getTotalDeviationsCount());
        assertEquals(1, dossier.getOpenDeviationsCount());
        assertFalse(dossier.isEligibleForRelease());
        assertRejectedReleaseLeavesDatabaseUnchanged(batch.getId(), BatchQualityStatus.PENDING_QA, "1 unresolved deviation(s)");
    }

    @Test
    @DisplayName("3. Zero QC checks, optional-only PASS checks (zero required checks), required FAILED, and required PENDING checks all block QA RELEASED with zero DB writes")
    void zeroChecksNoRequiredChecksFailedRequiredAndPendingRequiredBlockRelease() {
        // Case A: 0 QC checks
        Batch zeroCheckBatch = seedBatch("ZEROQC", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        BatchQualityDossierDto zeroDossier = qualityService.getBatchQualityDossier(zeroCheckBatch.getId());
        assertFalse(zeroDossier.isEligibleForRelease());
        assertTrue(zeroDossier.getReleaseBlockers().stream()
                .anyMatch(b -> b.contains("At least one QC check must be recorded")));
        assertRejectedReleaseLeavesDatabaseUnchanged(
                zeroCheckBatch.getId(), BatchQualityStatus.PENDING_QA,
                "at least one Quality Control (QC) check must be recorded first");

        // Case B: Only optional PASS QC check(s) — 0 required QC checks
        Batch optionalOnlyBatch = seedBatch("OPTONLY", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(optionalOnlyBatch, "Supplemental Color Check", "Uniform off-white", "Conforms",
                QualityCheckResult.PASS, false);
        QualityQueueItemDto optOnlyQueueItem = qualityService.getQualityInspectionQueue(null).stream()
                .filter(q -> q.getBatchId().equals(optionalOnlyBatch.getId()))
                .findFirst().orElseThrow();
        assertEquals(0, optOnlyQueueItem.getRequiredChecksCount());
        assertEquals(1, optOnlyQueueItem.getOptionalChecksCount());
        assertFalse(optOnlyQueueItem.isEligibleForRelease());
        assertTrue(optOnlyQueueItem.getReleaseBlockers().stream()
                .anyMatch(b -> b.contains("At least one required QC check must exist and PASS")));

        BatchQualityDossierDto optOnlyDossier = qualityService.getBatchQualityDossier(optionalOnlyBatch.getId());
        assertEquals(1, optOnlyDossier.getTotalChecksCount());
        assertEquals(1, optOnlyDossier.getPassedChecksCount());
        assertEquals(0, optOnlyDossier.getRequiredChecksCount());
        assertFalse(optOnlyDossier.isEligibleForRelease());
        assertEquals(optOnlyDossier.getReleaseBlockers(), optOnlyQueueItem.getReleaseBlockers());
        assertRejectedReleaseLeavesDatabaseUnchanged(
                optionalOnlyBatch.getId(), BatchQualityStatus.PENDING_QA,
                "at least one required QC check must exist and PASS");

        // Case C: Required FAIL check (with another required PASS check)
        Batch requiredFailBatch = seedBatch("REQFAIL", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(requiredFailBatch, "Assay", "95-105%", "99.1", QualityCheckResult.PASS, true);
        addQcCheck(requiredFailBatch, "Dissolution", "Minimum 80%", "65", QualityCheckResult.FAIL, true);
        assertFalse(qualityService.getBatchQualityDossier(requiredFailBatch.getId()).isEligibleForRelease());
        assertRejectedReleaseLeavesDatabaseUnchanged(
                requiredFailBatch.getId(), BatchQualityStatus.PENDING_QA,
                "1 required QC check(s) have FAILED");

        // Case D: Required PENDING check (with another required PASS check)
        Batch requiredPendingBatch = seedBatch("REQPEND", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(requiredPendingBatch, "Assay", "95-105%", "99.1", QualityCheckResult.PASS, true);
        addQcCheck(requiredPendingBatch, "Microbial Limit", "Absent", null, QualityCheckResult.PENDING, true);
        assertFalse(qualityService.getBatchQualityDossier(requiredPendingBatch.getId()).isEligibleForRelease());
        assertRejectedReleaseLeavesDatabaseUnchanged(
                requiredPendingBatch.getId(), BatchQualityStatus.PENDING_QA,
                "1 QC check(s) are still PENDING");

        // Case E: Combined required FAIL + required PENDING + OPEN deviation -> all reported together
        Batch combinedBatch = seedBatch("COMBO", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(combinedBatch, "Dissolution", "Minimum 80%", "65", QualityCheckResult.FAIL, true);
        addQcCheck(combinedBatch, "Sterility", "Sterile", null, QualityCheckResult.PENDING, true);
        createOpenDeviation(combinedBatch, "Combined blocker deviation");

        BatchQualityDossierDto combinedDossier = qualityService.getBatchQualityDossier(combinedBatch.getId());
        assertFalse(combinedDossier.isEligibleForRelease());
        assertEquals(3, combinedDossier.getReleaseBlockers().size());
        assertRejectedReleaseLeavesDatabaseUnchanged(
                combinedBatch.getId(), BatchQualityStatus.PENDING_QA,
                "1 required QC check(s) have FAILED; and 1 QC check(s) are still PENDING; and 1 unresolved deviation(s)");
    }

    @Test
    @DisplayName("4. Optional QC check FAILED or PENDING blocks QA RELEASED in Queue & Dossier, and unblocks once corrected to PASS")
    void optionalFailedOrPendingQcChecksFollowCurrentBlockingPolicyAndUnblockWhenCorrected() {
        // Case 4A: 1 Required PASS + 1 Optional (required=false) FAIL -> BLOCKS release across Queue & Dossier
        Batch optFailBatch = seedBatch("OPTFAIL", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(optFailBatch, "Assay", "95-105%", "99.4", QualityCheckResult.PASS, true);
        QualityCheck optFailCheck = addQcCheck(optFailBatch, "Friability (Optional Monitor)",
                "Maximum 1.0%", "1.4", QualityCheckResult.FAIL, false);

        QualityQueueItemDto optFailQueueItem = qualityService.getQualityInspectionQueue(null).stream()
                .filter(q -> q.getBatchId().equals(optFailBatch.getId()))
                .findFirst().orElseThrow();
        assertEquals(0, optFailQueueItem.getRequiredBlockingChecksCount(),
                "Legacy requiredBlockingChecksCount still counts only required FAIL/PENDING checks");
        assertEquals(1, optFailQueueItem.getBlockingChecksCount(),
                "Queue blockingChecksCount includes both required and optional FAIL/PENDING checks");
        assertEquals(1, optFailQueueItem.getOptionalChecksCount());
        assertEquals(1, optFailQueueItem.getFailedChecksCount());
        assertFalse(optFailQueueItem.isEligibleForRelease());
        assertTrue(optFailQueueItem.getReleaseBlockers().contains("1 optional QC check(s) have FAILED"));

        BatchQualityDossierDto optFailDossier = qualityService.getBatchQualityDossier(optFailBatch.getId());
        assertFalse(optFailDossier.isEligibleForRelease(),
                "Conservative release policy blocks release on any FAILED check even when required=false");
        assertEquals(optFailDossier.getReleaseBlockers(), optFailQueueItem.getReleaseBlockers());
        assertRejectedReleaseLeavesDatabaseUnchanged(
                optFailBatch.getId(), BatchQualityStatus.PENDING_QA, "1 optional QC check(s) have FAILED");

        // Case 4B: 1 Required PASS + 1 Optional (required=false) PENDING -> BLOCKS release across Queue & Dossier
        Batch optPendingBatch = seedBatch("OPTPEND", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(optPendingBatch, "Assay", "95-105%", "99.4", QualityCheckResult.PASS, true);
        QualityCheck optPendingCheck = addQcCheck(optPendingBatch, "Supplemental Moisture (Optional)",
                "Maximum 2.0%", null, QualityCheckResult.PENDING, false);

        QualityQueueItemDto optPendingQueueItem = qualityService.getQualityInspectionQueue(null).stream()
                .filter(q -> q.getBatchId().equals(optPendingBatch.getId()))
                .findFirst().orElseThrow();
        assertEquals(0, optPendingQueueItem.getRequiredBlockingChecksCount());
        assertEquals(1, optPendingQueueItem.getBlockingChecksCount());
        assertEquals(1, optPendingQueueItem.getPendingChecksCount());
        assertFalse(optPendingQueueItem.isEligibleForRelease());
        assertTrue(optPendingQueueItem.getReleaseBlockers().contains("1 optional QC check(s) are still PENDING"));

        BatchQualityDossierDto optPendingDossier = qualityService.getBatchQualityDossier(optPendingBatch.getId());
        assertFalse(optPendingDossier.isEligibleForRelease(),
                "Conservative release policy blocks release on any PENDING check even when required=false");
        assertEquals(optPendingDossier.getReleaseBlockers(), optPendingQueueItem.getReleaseBlockers());
        assertRejectedReleaseLeavesDatabaseUnchanged(
                optPendingBatch.getId(), BatchQualityStatus.PENDING_QA, "1 optional QC check(s) are still PENDING");

        // Case 4C: 1 Required FAIL + 1 Optional FAIL -> reports BOTH required and optional failures without hiding optional
        Batch bothFailBatch = seedBatch("BOTHFAIL", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(bothFailBatch, "Assay", "95-105%", "90.0", QualityCheckResult.FAIL, true);
        addQcCheck(bothFailBatch, "Optional Check", "Maximum 1.0%", "1.5", QualityCheckResult.FAIL, false);
        BatchQualityDossierDto bothFailDossier = qualityService.getBatchQualityDossier(bothFailBatch.getId());
        assertTrue(bothFailDossier.getReleaseBlockers().contains("1 required QC check(s) have FAILED"));
        assertTrue(bothFailDossier.getReleaseBlockers().contains("1 optional QC check(s) have FAILED"));
        assertRejectedReleaseLeavesDatabaseUnchanged(
                bothFailBatch.getId(), BatchQualityStatus.PENDING_QA,
                "1 required QC check(s) have FAILED; and 1 optional QC check(s) have FAILED");

        // Case 4D: Correct optional checks to PASS -> Queue & Dossier both become eligible and RELEASED succeeds
        authenticateAs("qc.analyst", "QC_ANALYST");
        qualityService.correctQualityCheck(optFailCheck.getId(), new QualityCheckCorrectionRequestDto(
                "Friability (Optional Monitor)", "Maximum 1.0%", "0.4", "%",
                QualityCheckResult.PASS, false, "Retested representative composite",
                "Verified calibration and composite retest"));
        qualityService.correctQualityCheck(optPendingCheck.getId(), new QualityCheckCorrectionRequestDto(
                "Supplemental Moisture (Optional)", "Maximum 2.0%", "1.1", "%",
                QualityCheckResult.PASS, false, "Completed Karl Fischer run",
                "Recorded pending optional check result"));
        authenticateAs("dr.menon", "QA_REVIEWER");

        QualityQueueItemDto optFailQueueAfter = qualityService.getQualityInspectionQueue(null).stream()
                .filter(q -> q.getBatchId().equals(optFailBatch.getId()))
                .findFirst().orElseThrow();
        assertTrue(optFailQueueAfter.isEligibleForRelease());
        assertTrue(optFailQueueAfter.getReleaseBlockers().isEmpty());
        assertEquals(0, optFailQueueAfter.getBlockingChecksCount());

        assertTrue(qualityService.getBatchQualityDossier(optFailBatch.getId()).isEligibleForRelease());
        assertEquals(BatchQualityStatus.RELEASED, qualityService.recordQaDisposition(
                optFailBatch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "All checks PASS")
        ).getQaStatus());

        assertTrue(qualityService.getBatchQualityDossier(optPendingBatch.getId()).isEligibleForRelease());
        assertEquals(BatchQualityStatus.RELEASED, qualityService.recordQaDisposition(
                optPendingBatch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "All checks PASS")
        ).getQaStatus());
    }

    @Test
    @DisplayName("5. ON_HOLD and REJECTED dispositions continue to succeed even when QC/deviation blockers exist; finalized REJECTED cannot later release")
    void onHoldAndRejectedWorkDespiteBlockersAndRejectedIsFinal() {
        // Batch A has 0 QC checks AND an OPEN deviation -> ON_HOLD -> REJECTED must both succeed
        Batch zeroCheckDeviatedBatch = seedBatch("HOLDREJ", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        createOpenDeviation(zeroCheckDeviatedBatch, "Packaging integrity failure");

        BatchQualityDossierDto holdDossier = qualityService.recordQaDisposition(
                zeroCheckDeviatedBatch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Hold despite 0 QC checks & OPEN dev")
        );
        assertEquals(BatchQualityStatus.ON_HOLD, holdDossier.getQaStatus());
        assertEquals(1, batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(zeroCheckDeviatedBatch.getId()).size());

        BatchQualityDossierDto rejectedFromHold = qualityService.recordQaDisposition(
                zeroCheckDeviatedBatch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.REJECTED, "Rejected from ON_HOLD due to defect")
        );
        assertEquals(BatchQualityStatus.REJECTED, rejectedFromHold.getQaStatus());
        assertEquals(2, batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(zeroCheckDeviatedBatch.getId()).size());

        // Attempting to release an already REJECTED batch fails with zero DB changes
        assertRejectedReleaseLeavesDatabaseUnchanged(
                zeroCheckDeviatedBatch.getId(), BatchQualityStatus.REJECTED, "already finalized as REJECTED");

        // Batch B has required FAIL + PENDING + CAPA_IN_PROGRESS deviation -> direct PENDING_QA -> REJECTED succeeds
        Batch failedDeviatedBatch = seedBatch("DIRREJ", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(failedDeviatedBatch, "Assay", "95-105%", "88.0", QualityCheckResult.FAIL, true);
        addQcCheck(failedDeviatedBatch, "Sterility", "Sterile", null, QualityCheckResult.PENDING, true);
        Long devId = createOpenDeviation(failedDeviatedBatch, "OOS assay excursion");
        deviationService.startInvestigation(devId, new StartInvestigationRequestDto("Investigating OOS"));
        deviationService.recordRootCause(devId, new RecordRootCauseRequestDto(
                "Material", "Degraded API lot", "Entire batch affected", null));
        deviationService.createCapaAction(devId, new CreateCapaActionRequestDto(
                CapaType.PREVENTIVE, "Quarantine API supplier lot", "Block remaining drum",
                "QA Lead", LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(2)));
        deviationService.startCapa(devId, new StartCapaRequestDto("Containment CAPA"));

        BatchQualityDossierDto directRejected = qualityService.recordQaDisposition(
                failedDeviatedBatch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.REJECTED, "Batch rejected due to OOS assay")
        );
        assertEquals(BatchQualityStatus.REJECTED, directRejected.getQaStatus());
        assertFalse(directRejected.isEligibleForRelease());

        // Non-COMPLETED batch (IN_PROGRESS) cannot bypass QA disposition either
        Batch inProgressBatch = seedBatch("INPROG", BatchStatus.IN_PROGRESS, null);
        assertThrows(InvalidQualityOperationException.class, () -> qualityService.recordQaDisposition(
                inProgressBatch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Premature release")
        ));
        assertThrows(InvalidQualityOperationException.class, () -> qualityService.recordQaDisposition(
                inProgressBatch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Premature hold")
        ));
    }

    @Test
    @DisplayName("6. Closing a deviation never auto-releases PENDING_QA or ON_HOLD batches; explicit QA RELEASED is still required and succeeds once gates clear")
    void closingDeviationNeverAutoReleasesPendingOrOnHoldBatchAndExplicitReleaseSucceeds() {
        Batch pendingQaBatch = seedBatch("NOAUTO_PEND", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(pendingQaBatch, "Assay", "95-105%", "99.2", QualityCheckResult.PASS, true);

        Long devId = createOpenDeviation(pendingQaBatch, "Temporary HVAC pressure dip");
        LocalDate dueDate = LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(2);
        deviationService.startInvestigation(devId, new StartInvestigationRequestDto("Checked AHU differential"));
        deviationService.recordRootCause(devId, new RecordRootCauseRequestDto(
                "Facility", "Filter differential alarm", "No cleanroom excursion", null));
        var capa = deviationService.createCapaAction(devId, new CreateCapaActionRequestDto(
                CapaType.CORRECTIVE, "Replace AHU pre-filter", "Install new HEPA pre-filter",
                "Facility Eng", dueDate));
        deviationService.startCapa(devId, new StartCapaRequestDto("Executing"));
        deviationService.completeCapaAction(capa.getId(), new CompleteCapaActionRequestDto("Replaced and certified"));
        deviationService.submitForVerification(devId, new SubmitVerificationRequestDto("Submitted"));

        long reviewsBeforeClose = batchQaReviewRepository.count();
        deviationService.closeDeviation(devId, new CloseDeviationRequestDto("Verified cleanroom pressure logs"));

        entityManager.flush();
        entityManager.clear();

        // Batch must remain PENDING_QA — zero automatic QA disposition or review rows
        Batch afterClose = batchRepository.findById(pendingQaBatch.getId()).orElseThrow();
        assertEquals(BatchQualityStatus.PENDING_QA, afterClose.getQaStatus(),
                "Closing deviation must never auto-release a PENDING_QA batch");
        assertEquals(BatchStatus.COMPLETED, afterClose.getStatus());
        assertEquals(reviewsBeforeClose, batchQaReviewRepository.count());
        List<AuditLog> releaseAuditsBeforeDisposition = auditLogRepository.findByBatchIdOrderByOccurredAtDescIdDesc(pendingQaBatch.getId())
                .stream().filter(a -> "QA_DISPOSITION_RELEASED".equals(a.getAction())).toList();
        assertTrue(releaseAuditsBeforeDisposition.isEmpty());

        // Dossier now reports release-eligible and explicit RELEASED succeeds
        BatchQualityDossierDto eligibleDossier = qualityService.getBatchQualityDossier(pendingQaBatch.getId());
        assertTrue(eligibleDossier.isEligibleForRelease());
        assertEquals(0, eligibleDossier.getOpenDeviationsCount());
        assertTrue(eligibleDossier.getReleaseBlockers().isEmpty());

        BatchQualityDossierDto released = qualityService.recordQaDisposition(
                pendingQaBatch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Explicit QA approval post-closure")
        );
        assertEquals(BatchQualityStatus.RELEASED, released.getQaStatus());
        assertEquals(BatchStatus.COMPLETED, released.getBatchStatus());
        assertEquals(0, new BigDecimal("248.5000").compareTo(released.getActualYield()));
        assertEquals(1, released.getQaReviews().size());
        BatchQaReview persistedReview = batchQaReviewRepository
                .findByBatchIdOrderByReviewedAtDescIdDesc(pendingQaBatch.getId()).get(0);
        assertEquals(BatchQualityStatus.PENDING_QA, persistedReview.getPreviousStatus());
        assertEquals(BatchQualityStatus.RELEASED, persistedReview.getDecisionStatus());

        List<AuditLog> releaseAuditsAfter = auditLogRepository.findByBatchIdOrderByOccurredAtDescIdDesc(pendingQaBatch.getId())
                .stream().filter(a -> "QA_DISPOSITION_RELEASED".equals(a.getAction())).toList();
        assertEquals(1, releaseAuditsAfter.size());
        assertTrue(releaseAuditsAfter.get(0).getBeforeValues().contains("\"qaStatus\":\"PENDING_QA\""));
        assertTrue(releaseAuditsAfter.get(0).getAfterValues().contains("\"qaStatus\":\"RELEASED\""));
    }

    @Test
    @DisplayName("7. Multiple consecutive rejected RELEASED attempts leave exact Batch, BatchQaReview, and AuditLog tables untouched")
    void repeatedRejectedReleaseAttemptsCauseZeroMutations() {
        Batch batch = seedBatch("ZERO_MUT", BatchStatus.COMPLETED, BatchQualityStatus.ON_HOLD);
        QualityCheck pendingCheck = addQcCheck(batch, "Assay", "95-105%", null, QualityCheckResult.PENDING, true);
        createOpenDeviation(batch, "Unresolved packaging alert");

        long reviewsSnapshot = batchQaReviewRepository.count();
        long auditsSnapshot = auditLogRepository.count();
        BigDecimal yieldSnapshot = batchRepository.findById(batch.getId()).orElseThrow().getActualYield();

        // Repeated failed release attempts
        for (int i = 0; i < 3; i++) {
            final int attempt = i + 1;
            assertThrows(InvalidQualityOperationException.class, () -> qualityService.recordQaDisposition(
                    batch.getId(),
                    new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Bypass try #" + attempt)
            ));
        }

        // Also reject reverting ON_HOLD -> PENDING_QA or repeating ON_HOLD -> ON_HOLD
        assertThrows(InvalidQualityOperationException.class, () -> qualityService.recordQaDisposition(
                batch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.PENDING_QA, "Revert to PENDING_QA")
        ));
        assertThrows(InvalidQualityOperationException.class, () -> qualityService.recordQaDisposition(
                batch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Duplicate ON_HOLD")
        ));

        entityManager.flush();
        entityManager.clear();

        Batch reloaded = batchRepository.findById(batch.getId()).orElseThrow();
        assertEquals(BatchQualityStatus.ON_HOLD, reloaded.getQaStatus());
        assertEquals(BatchStatus.COMPLETED, reloaded.getStatus());
        assertEquals(0, yieldSnapshot.compareTo(reloaded.getActualYield()));
        assertEquals(reviewsSnapshot, batchQaReviewRepository.count());
        assertEquals(auditsSnapshot, auditLogRepository.count());
        assertEquals(QualityCheckResult.PENDING, qualityCheckRepository.findById(pendingCheck.getId()).orElseThrow().getResult());
    }

    @Test
    @DisplayName("8. Optional-only FAIL/PENDING, combined required+optional PENDING, and demoting required PASS to optional all block release across Queue, Dossier, and Disposition")
    void demotingRequiredCheckToOptionalCombinedPendingAndOptionalOnlyFailuresAllBlockRelease() {
        // 8A. Optional-only FAIL (0 required checks) -> reports BOTH missing required check AND optional FAIL; ON_HOLD permitted
        Batch optOnlyFail = seedBatch("OPT_ONLY_FAIL", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(optOnlyFail, "Friability (Optional)", "Maximum 1.0%", "1.6", QualityCheckResult.FAIL, false);

        QualityQueueItemDto optOnlyFailQueue = qualityService.getQualityInspectionQueue(null).stream()
                .filter(q -> q.getBatchId().equals(optOnlyFail.getId()))
                .findFirst().orElseThrow();
        assertFalse(optOnlyFailQueue.isEligibleForRelease());
        assertEquals(List.of(
                "At least one required QC check must exist and PASS",
                "1 optional QC check(s) have FAILED"
        ), optOnlyFailQueue.getReleaseBlockers());
        assertEquals(optOnlyFailQueue.getReleaseBlockers(),
                qualityService.getBatchQualityDossier(optOnlyFail.getId()).getReleaseBlockers());
        assertRejectedReleaseLeavesDatabaseUnchanged(
                optOnlyFail.getId(),
                BatchQualityStatus.PENDING_QA,
                "at least one required QC check must exist and PASS; and 1 optional QC check(s) have FAILED"
        );

        // 8B. Optional-only PENDING (0 required checks) -> reports BOTH missing required check AND optional PENDING; ON_HOLD & REJECTED permitted with exact snapshot counts
        Batch optOnlyPend = seedBatch("OPT_ONLY_PEND", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(optOnlyPend, "Moisture (Optional)", "Maximum 2.0%", null, QualityCheckResult.PENDING, false);

        QualityQueueItemDto optOnlyPendQueue = qualityService.getQualityInspectionQueue(null).stream()
                .filter(q -> q.getBatchId().equals(optOnlyPend.getId()))
                .findFirst().orElseThrow();
        assertFalse(optOnlyPendQueue.isEligibleForRelease());
        assertEquals(List.of(
                "At least one required QC check must exist and PASS",
                "1 optional QC check(s) are still PENDING"
        ), optOnlyPendQueue.getReleaseBlockers());
        assertRejectedReleaseLeavesDatabaseUnchanged(
                optOnlyPend.getId(),
                BatchQualityStatus.PENDING_QA,
                "at least one required QC check must exist and PASS; and 1 optional QC check(s) are still PENDING"
        );

        BatchQualityDossierDto holdOptOnly = qualityService.recordQaDisposition(
                optOnlyPend.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Holding optional-only pending batch")
        );
        assertEquals(BatchQualityStatus.ON_HOLD, holdOptOnly.getQaStatus());
        BatchQaReview holdReview = batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(optOnlyPend.getId()).get(0);
        assertEquals(1L, holdReview.getTotalChecksCount());
        assertEquals(0L, holdReview.getPassedChecksCount());
        assertEquals(0L, holdReview.getFailedChecksCount());
        assertEquals(1L, holdReview.getPendingChecksCount());

        // 8C. Combined required PENDING + optional PENDING -> emits distinct counts for both
        Batch bothPending = seedBatch("BOTH_PEND", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(bothPending, "Assay", "95-105%", "99.0", QualityCheckResult.PASS, true);
        addQcCheck(bothPending, "Sterility (Required)", "Sterile", null, QualityCheckResult.PENDING, true);
        addQcCheck(bothPending, "Appearance Note (Optional)", "Clean", null, QualityCheckResult.PENDING, false);

        QualityQueueItemDto bothPendingQueue = qualityService.getQualityInspectionQueue(null).stream()
                .filter(q -> q.getBatchId().equals(bothPending.getId()))
                .findFirst().orElseThrow();
        assertEquals(List.of(
                "1 required QC check(s) are still PENDING",
                "1 optional QC check(s) are still PENDING"
        ), bothPendingQueue.getReleaseBlockers());
        assertEquals(bothPendingQueue.getReleaseBlockers(),
                qualityService.getBatchQualityDossier(bothPending.getId()).getReleaseBlockers());
        assertRejectedReleaseLeavesDatabaseUnchanged(
                bothPending.getId(),
                BatchQualityStatus.PENDING_QA,
                "1 required QC check(s) are still PENDING; and 1 optional QC check(s) are still PENDING"
        );

        // 8D. Demoting sole required PASS check to optional via audited correction immediately revokes release eligibility
        Batch demoteBatch = seedBatch("DEMOTE_REQ", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        QualityCheck assay = addQcCheck(demoteBatch, "Assay (HPLC)", "95-105%", "99.3", QualityCheckResult.PASS, true);
        assertTrue(qualityService.getBatchQualityDossier(demoteBatch.getId()).isEligibleForRelease());

        authenticateAs("qc.analyst", "QC_ANALYST");
        qualityService.correctQualityCheck(assay.getId(), new QualityCheckCorrectionRequestDto(
                "Assay (HPLC)", "95-105%", "99.3", "%",
                QualityCheckResult.PASS, false, "Reclassified as optional",
                "Testing demotion of sole required check to optional"
        ));
        authenticateAs("dr.menon", "QA_REVIEWER");

        QualityQueueItemDto afterDemoteQueue = qualityService.getQualityInspectionQueue(null).stream()
                .filter(q -> q.getBatchId().equals(demoteBatch.getId()))
                .findFirst().orElseThrow();
        assertFalse(afterDemoteQueue.isEligibleForRelease());
        assertEquals(0, afterDemoteQueue.getRequiredChecksCount());
        assertEquals(1, afterDemoteQueue.getOptionalChecksCount());
        assertEquals(List.of("At least one required QC check must exist and PASS"), afterDemoteQueue.getReleaseBlockers());
        assertRejectedReleaseLeavesDatabaseUnchanged(
                demoteBatch.getId(),
                BatchQualityStatus.PENDING_QA,
                "at least one required QC check must exist and PASS"
        );

        // Restoring required=true restores eligibility and explicit RELEASED succeeds
        authenticateAs("qc.analyst", "QC_ANALYST");
        qualityService.correctQualityCheck(assay.getId(), new QualityCheckCorrectionRequestDto(
                "Assay (HPLC)", "95-105%", "99.3", "%",
                QualityCheckResult.PASS, true, "Restored as required release check",
                "Restoring mandatory release criterion"
        ));
        authenticateAs("dr.menon", "QA_REVIEWER");
        assertTrue(qualityService.getBatchQualityDossier(demoteBatch.getId()).isEligibleForRelease());
        BatchQualityDossierDto released = qualityService.recordQaDisposition(
                demoteBatch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Required check restored")
        );
        assertEquals(BatchQualityStatus.RELEASED, released.getQaStatus());
    }

    @Test
    @DisplayName("9. Reopened deviation re-blocks release; closing deviation never auto-releases ON_HOLD batch; valid release with required+optional PASS & CLOSED dev succeeds")
    void reopenedDeviationBlocksReleaseAndClosingPreservesOnHoldUntilExplicitRelease() {
        Batch batch = seedBatch("REOPEN_HOLD", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        addQcCheck(batch, "Assay (HPLC)", "95-105%", "99.6", QualityCheckResult.PASS, true);
        addQcCheck(batch, "Humidity Monitor (Optional)", "Maximum 45%", "32.0", QualityCheckResult.PASS, false);

        Long devId = createOpenDeviation(batch, "Tablet deduster vibration excursion");
        LocalDate dueDate = LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(3);

        // Place batch ON_HOLD despite open deviation (Scenario 6)
        BatchQualityDossierDto onHold = qualityService.recordQaDisposition(
                batch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Holding batch for deviation closure")
        );
        assertEquals(BatchQualityStatus.ON_HOLD, onHold.getQaStatus());
        assertFalse(onHold.isEligibleForRelease());

        // Move deviation to PENDING_VERIFICATION, then reopen back to CAPA_IN_PROGRESS
        deviationService.startInvestigation(devId, new StartInvestigationRequestDto("Checked deduster bearings"));
        deviationService.recordRootCause(devId, new RecordRootCauseRequestDto(
                "Equipment", "Loose isolator mount", "Single lot affected", null));
        var capa1 = deviationService.createCapaAction(devId, new CreateCapaActionRequestDto(
                CapaType.CORRECTIVE, "Secure isolator mount", "Replace damper bolts", "Eng. Kumar", dueDate));
        deviationService.startCapa(devId, new StartCapaRequestDto("Executing"));
        deviationService.completeCapaAction(capa1.getId(), new CompleteCapaActionRequestDto("Mount tightened"));
        deviationService.submitForVerification(devId, new SubmitVerificationRequestDto("Submitted for QA"));

        assertRejectedReleaseLeavesDatabaseUnchanged(batch.getId(), BatchQualityStatus.ON_HOLD, "[PENDING_VERIFICATION]");

        deviationService.reopenCapa(devId, new ReopenCapaRequestDto("Require 2-hour post-repair vibration log"));
        QualityQueueItemDto reopenedQueue = qualityService.getQualityInspectionQueue(null).stream()
                .filter(q -> q.getBatchId().equals(batch.getId()))
                .findFirst().orElseThrow();
        assertFalse(reopenedQueue.isEligibleForRelease());
        assertEquals(1, reopenedQueue.getOpenDeviationsCount());
        assertRejectedReleaseLeavesDatabaseUnchanged(batch.getId(), BatchQualityStatus.ON_HOLD, "[CAPA_IN_PROGRESS]");

        // Complete follow-up CAPA, re-submit, and close deviation -> must keep qaStatus ON_HOLD (never auto-release!)
        var capa2 = deviationService.createCapaAction(devId, new CreateCapaActionRequestDto(
                CapaType.PREVENTIVE, "Log 2h vibration run", "Attach accelerometer chart", "Eng. Kumar", dueDate.plusDays(1)));
        deviationService.completeCapaAction(capa2.getId(), new CompleteCapaActionRequestDto("Chart VB-02 normal"));
        deviationService.submitForVerification(devId, new SubmitVerificationRequestDto("Verification packet complete"));

        long reviewsBeforeClose = batchQaReviewRepository.count();
        deviationService.closeDeviation(devId, new CloseDeviationRequestDto("Effectiveness confirmed"));

        entityManager.flush();
        entityManager.clear();

        Batch afterClose = batchRepository.findById(batch.getId()).orElseThrow();
        assertEquals(BatchQualityStatus.ON_HOLD, afterClose.getQaStatus(),
                "Closing deviation must never auto-release an ON_HOLD batch");
        assertEquals(reviewsBeforeClose, batchQaReviewRepository.count());

        QualityQueueItemDto readyQueue = qualityService.getQualityInspectionQueue(null).stream()
                .filter(q -> q.getBatchId().equals(batch.getId()))
                .findFirst().orElseThrow();
        assertTrue(readyQueue.isEligibleForRelease());
        assertEquals(0, readyQueue.getOpenDeviationsCount());
        assertEquals(1, readyQueue.getTotalDeviationsCount());
        assertTrue(readyQueue.getReleaseBlockers().isEmpty());

        // Explicit RELEASED from ON_HOLD with required PASS + optional PASS + CLOSED deviation succeeds
        BatchQualityDossierDto released = qualityService.recordQaDisposition(
                batch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Releasing ON_HOLD batch after deviation closure")
        );
        assertEquals(BatchQualityStatus.RELEASED, released.getQaStatus());
        assertEquals(BatchStatus.COMPLETED, released.getBatchStatus());
        assertEquals(0, new BigDecimal("248.5000").compareTo(released.getActualYield()));
        assertFalse(released.isEligibleForRelease());
        assertEquals(2, released.getQaReviews().size());

        BatchQaReview latestReview = batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(batch.getId()).get(0);
        assertEquals(BatchQualityStatus.ON_HOLD, latestReview.getPreviousStatus());
        assertEquals(BatchQualityStatus.RELEASED, latestReview.getDecisionStatus());
        assertEquals(2L, latestReview.getTotalChecksCount());
        assertEquals(2L, latestReview.getPassedChecksCount());
        assertEquals(0L, latestReview.getFailedChecksCount());
        assertEquals(0L, latestReview.getPendingChecksCount());

        // Finalized RELEASED batch cannot be modified to REJECTED or ON_HOLD
        assertThrows(InvalidQualityOperationException.class, () -> qualityService.recordQaDisposition(
                batch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.REJECTED, "Post-release override attempt")
        ));
    }
}

