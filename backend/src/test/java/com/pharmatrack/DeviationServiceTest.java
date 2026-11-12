package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.AuditLogResponseDto;
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
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchExecutionStep;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.CapaAction;
import com.pharmatrack.entity.CapaStatus;
import com.pharmatrack.entity.CapaType;
import com.pharmatrack.entity.Deviation;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.QualityCheck;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.exception.InvalidDeviationOperationException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchExecutionStepRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.CapaActionRepository;
import com.pharmatrack.repository.DeviationRepository;
import com.pharmatrack.repository.QualityCheckRepository;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.DeviationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for {@link DeviationService} covering:
 * - Batch status & cross-batch link validation
 * - Sequential DEV-YYYY-XXXX & CAPA-YYYY-XXXX numbering
 * - Strict 6-stage lifecycle transitions and invalid stage skips
 * - CAPA creation, Asia/Kolkata due-date checks, start, complete, cancel, and reopen workflows
 * - Closure eligibility gates & terminal CLOSED immutability
 * - Append-only AuditLog recording with before/after snapshots
 * - Zero database side effects when operations are rejected
 * - Complete non-mutation of historical Batch, QC check, and ExecutionStep records
 */
@DataJpaTest
class DeviationServiceTest {

    @Autowired
    private DeviationRepository deviationRepository;

    @Autowired
    private CapaActionRepository capaActionRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private QualityCheckRepository qualityCheckRepository;

    @Autowired
    private BatchExecutionStepRepository executionStepRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TestEntityManager entityManager;

    private AuditTrailService auditTrailService;
    private DeviationService deviationService;

    private Batch completedBatch;
    private Batch inProgressBatch;
    private Batch plannedBatch;
    private Batch cancelledBatch;
    private QualityCheck failedCheckOnCompleted;
    private QualityCheck checkOnOtherBatch;
    private BatchExecutionStep stepOnCompleted;
    private BatchExecutionStep stepOnInProgress;
    private AuditLog auditOnCompleted;
    private AuditLog auditOnInProgress;

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
    void setUp() {
        auditTrailService = new AuditTrailService(auditLogRepository);
        deviationService = new DeviationService(
                deviationRepository,
                capaActionRepository,
                batchRepository,
                qualityCheckRepository,
                executionStepRepository,
                auditLogRepository,
                auditTrailService
        );
        authenticateAs("qa.reviewer", "QA_REVIEWER");

        Product product = entityManager.persistAndFlush(new Product(
                "AMOX-250", "Amoxicillin Capsules", "250 mg", "Capsule", "Antibiotic", ProductStatus.ACTIVE
        ));
        Recipe recipe = new Recipe(
                "RCP-AMOX-250-V3", product, 3, new BigDecimal("250.0000"), "kg", RecipeStatus.APPROVED, "Master V3"
        );
        recipe.addStep(new RecipeStep(recipe, 1, "Granulation", "Blend for 15 minutes at 120 RPM"));
        recipe = entityManager.persistAndFlush(recipe);

        ManufacturingOrder order = entityManager.persistAndFlush(new ManufacturingOrder(
                "MO-2026-0001", product, recipe, 3, new BigDecimal("1000.0000"), "kg", 4,
                OrderStatus.IN_PROGRESS, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 20), "Test run"
        ));

        completedBatch = new Batch("BAT-2026-0001-01", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        completedBatch.setQaStatus(BatchQualityStatus.PENDING_QA);
        completedBatch.setActualYield(new BigDecimal("248.5000"));
        completedBatch.setEndTime(LocalDateTime.of(2026, 11, 10, 12, 0));
        completedBatch = entityManager.persistAndFlush(completedBatch);

        inProgressBatch = new Batch("BAT-2026-0001-02", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.IN_PROGRESS, LocalDate.of(2026, 11, 10), null);
        inProgressBatch.setStartTime(LocalDateTime.of(2026, 11, 10, 13, 0));
        inProgressBatch = entityManager.persistAndFlush(inProgressBatch);

        plannedBatch = new Batch("BAT-2026-0001-03", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.PLANNED, null, null);
        plannedBatch = entityManager.persistAndFlush(plannedBatch);

        cancelledBatch = new Batch("BAT-2026-0001-04", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.CANCELLED, null, null);
        cancelledBatch = entityManager.persistAndFlush(cancelledBatch);

        Batch otherCompletedBatch = new Batch("BAT-2026-0001-05", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        otherCompletedBatch.setQaStatus(BatchQualityStatus.PENDING_QA);
        otherCompletedBatch = entityManager.persistAndFlush(otherCompletedBatch);

        failedCheckOnCompleted = entityManager.persistAndFlush(new QualityCheck(
                completedBatch, "Dissolution Test", "Minimum 80%", "65", "%",
                QualityCheckResult.FAIL, true, "A. Nair", LocalDateTime.of(2026, 11, 10, 13, 15), "OOS dissolution"
        ));

        checkOnOtherBatch = entityManager.persistAndFlush(new QualityCheck(
                otherCompletedBatch, "Assay", "95-105%", "99.0", "%",
                QualityCheckResult.PASS, true, "A. Nair", LocalDateTime.of(2026, 11, 10, 13, 20), null
        ));

        RecipeStep recipeStep = recipe.getSteps().get(0);
        stepOnCompleted = entityManager.persistAndFlush(new BatchExecutionStep(
                completedBatch, recipeStep, 1, "Granulation", "Blend 15 min", ExecutionStepStatus.COMPLETED
        ));
        stepOnInProgress = entityManager.persistAndFlush(new BatchExecutionStep(
                inProgressBatch, recipeStep, 1, "Granulation", "Blend 15 min", ExecutionStepStatus.IN_PROGRESS
        ));

        auditOnCompleted = auditLogRepository.save(new AuditLog(
                "QUALITY_CHECK", failedCheckOnCompleted.getId(), completedBatch.getBatchNumber(), completedBatch.getId(),
                "QC_CHECK_CREATED", "A. Nair", LocalDateTime.of(2026, 11, 10, 13, 16),
                "Recorded FAIL dissolution", null, "{\"result\":\"FAIL\"}"
        ));
        auditOnInProgress = auditLogRepository.save(new AuditLog(
                "EXECUTION_STEP", stepOnInProgress.getId(), inProgressBatch.getBatchNumber(), inProgressBatch.getId(),
                "STEP_UPDATED", "Operator 1", LocalDateTime.of(2026, 11, 10, 13, 18),
                "Observed parameter excursion", null, "{\"rpm\":\"145\"}"
        ));
        entityManager.flush();
    }

    private CreateDeviationRequestDto validCreateRequest(Long batchId, Long qcId, Long stepId, Long auditId) {
        return new CreateDeviationRequestDto(
                batchId, qcId, stepId, auditId,
                "Dissolution test out-of-specification (65% vs min 80%)",
                DeviationCategory.QC_OOS_FAILURE,
                DeviationSeverity.CRITICAL,
                DeviationPriority.HIGH,
                "Observed 65% dissolution at 30 minutes against minimum 80% specification.",
                "Segregated finished cartons in QA quarantine cage.",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(10)
        );
    }

    @Test
    @DisplayName("1. Full valid 6-stage Deviation & CAPA lifecycle succeeds, numbers sequences properly, writes audit logs, and leaves batch/QC untouched")
    void fullValidLifecycleCompletesAndAuditsAllEvents() {
        long devCountStart = deviationRepository.count();
        long capaCountStart = capaActionRepository.count();
        long auditCountStart = auditLogRepository.count();
        LocalDate today = LocalDate.now(DeviationService.APP_ZONE_ID);
        int year = today.getYear();

        // 1. Create deviation on COMPLETED batch linked to failed QC, step, and audit entry -> OPEN
        authenticateAs("a.nair", "QC_ANALYST");
        DeviationDetailDto created = deviationService.createDeviation(validCreateRequest(
                completedBatch.getId(),
                failedCheckOnCompleted.getId(),
                stepOnCompleted.getId(),
                auditOnCompleted.getId()
        ));
        assertNotNull(created.getId());
        assertEquals(String.format("DEV-%d-%04d", year, created.getId()), created.getDeviationNumber());
        assertEquals(DeviationStatus.OPEN, created.getStatus());
        assertEquals("a.nair", created.getReportedBy());
        assertEquals(completedBatch.getId(), created.getBatchId());
        assertEquals(failedCheckOnCompleted.getId(), created.getQualityCheckId());
        assertEquals(stepOnCompleted.getId(), created.getExecutionStepId());
        assertEquals(auditOnCompleted.getId(), created.getSourceAuditLogId());
        assertFalse(created.isEligibleForClosure());

        // Also confirm IN_PROGRESS batch can have a deviation reported
        authenticateAs("line.operator", "OPERATOR");
        DeviationDetailDto inProgDev = deviationService.createDeviation(validCreateRequest(
                inProgressBatch.getId(), null, stepOnInProgress.getId(), auditOnInProgress.getId()
        ));
        assertEquals(DeviationStatus.OPEN, inProgDev.getStatus());
        assertEquals("line.operator", inProgDev.getReportedBy());
        assertNotEquals(created.getDeviationNumber(), inProgDev.getDeviationNumber());

        // 2. OPEN -> UNDER_INVESTIGATION
        authenticateAs("  Dr.R.Menon  ", "QA_REVIEWER");
        DeviationDetailDto investigating = deviationService.startInvestigation(
                created.getId(),
                new StartInvestigationRequestDto("Inspecting granulation moisture logs and tablet press dwell force.")
        );
        assertEquals(DeviationStatus.UNDER_INVESTIGATION, investigating.getStatus());
        assertEquals("dr.r.menon", investigating.getInvestigatedBy());
        assertNotNull(investigating.getInvestigationStartedAt());

        // 3. UNDER_INVESTIGATION -> ROOT_CAUSE_IDENTIFIED
        DeviationDetailDto rcaRecorded = deviationService.recordRootCause(
                created.getId(),
                new RecordRootCauseRequestDto(
                        "Equipment / Compression Control",
                        "Tablet press main compression force drifted below target due to worn punch spring.",
                        "Affected only batch BAT-2026-0001-01; upstream blend assay was within specification.",
                        null
                )
        );
        assertEquals(DeviationStatus.ROOT_CAUSE_IDENTIFIED, rcaRecorded.getStatus());
        assertEquals("Equipment / Compression Control", rcaRecorded.getRootCauseCategory());
        assertNotNull(rcaRecorded.getRootCauseIdentifiedAt());

        // 4. Add two CAPAs (today's date + future date) and cancel one, keep one corrective
        CapaActionResponseDto capa1 = deviationService.createCapaAction(
                created.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.CORRECTIVE,
                        "Replace punch spring and recalibrate press turret",
                        "Replace worn compression spring on Station 4 and perform load-cell calibration.",
                        "Eng. Kumar",
                        today
                )
        );
        assertEquals(String.format("CAPA-%d-%04d", year, capa1.getId()), capa1.getCapaNumber());
        assertEquals(CapaStatus.OPEN, capa1.getStatus());
        assertFalse(capa1.isOverdue());

        CapaActionResponseDto capa2 = deviationService.createCapaAction(
                created.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.PREVENTIVE,
                        "Optional supplemental lubrication review",
                        "Evaluate cam track lubricant interval.",
                        "Eng. Kumar",
                        today.plusDays(7)
                )
        );

        // 5. ROOT_CAUSE_IDENTIFIED -> CAPA_IN_PROGRESS
        DeviationDetailDto capaStarted = deviationService.startCapa(
                created.getId(),
                new StartCapaRequestDto("Proceeding with mechanical remediation and press verification.")
        );
        assertEquals(DeviationStatus.CAPA_IN_PROGRESS, capaStarted.getStatus());

        // Start & Complete capa1, Cancel capa2
        authenticateAs("eng.kumar", "PRODUCTION_SUPERVISOR");
        CapaActionResponseDto capa1Started = deviationService.startCapaAction(
                capa1.getId(),
                new StartCapaActionRequestDto("Work order WO-882 opened")
        );
        assertEquals(CapaStatus.IN_PROGRESS, capa1Started.getStatus());

        CapaActionResponseDto capa1Completed = deviationService.completeCapaAction(
                capa1.getId(),
                new CompleteCapaActionRequestDto("Work order WO-882 signed off; calibration certificate CAL-411 verified.")
        );
        assertEquals(CapaStatus.COMPLETED, capa1Completed.getStatus());
        assertNotNull(capa1Completed.getCompletedAt());

        authenticateAs("dr.r.menon", "QA_REVIEWER");
        CapaActionResponseDto capa2Cancelled = deviationService.cancelCapaAction(
                capa2.getId(),
                new CancelCapaActionRequestDto("Superseded by annual preventive maintenance schedule PM-09.")
        );
        assertEquals(CapaStatus.CANCELLED, capa2Cancelled.getStatus());

        // 6. CAPA_IN_PROGRESS -> PENDING_VERIFICATION
        authenticateAs("eng.kumar", "PRODUCTION_SUPERVISOR");
        DeviationDetailDto pendingVer = deviationService.submitForVerification(
                created.getId(),
                new SubmitVerificationRequestDto("All corrective actions executed and evidence attached.")
        );
        assertEquals(DeviationStatus.PENDING_VERIFICATION, pendingVer.getStatus());
        assertTrue(pendingVer.isEligibleForClosure());
        assertTrue(pendingVer.getClosureBlockers().isEmpty());

        // 7. PENDING_VERIFICATION -> CLOSED
        authenticateAs("qa.lead.patel", "QA_REVIEWER");
        DeviationDetailDto closed = deviationService.closeDeviation(
                created.getId(),
                new CloseDeviationRequestDto("Verified calibration certificate CAL-411 and equipment logs.")
        );
        assertEquals(DeviationStatus.CLOSED, closed.getStatus());
        assertEquals("qa.lead.patel", closed.getVerifiedBy());
        assertEquals("qa.lead.patel", closed.getClosedBy());
        assertNotNull(closed.getClosedAt());
        assertFalse(closed.isEligibleForClosure());

        // Confirm DB counts & complete audit trail (2 reported + 5 transitions + 5 CAPA events = 12 audit logs)
        assertEquals(devCountStart + 2, deviationRepository.count());
        assertEquals(capaCountStart + 2, capaActionRepository.count());
        assertEquals(auditCountStart + 12, auditLogRepository.count());
        assertEquals(11, closed.getAuditLogs().size());
        assertEquals(1, deviationService.getDeviationById(inProgDev.getId()).getAuditLogs().size());

        // Verify scoped audit logs on closed deviation
        List<String> actions = closed.getAuditLogs().stream().map(a -> a.getAction()).toList();
        assertTrue(actions.containsAll(List.of(
                "DEVIATION_REPORTED",
                "DEVIATION_INVESTIGATION_STARTED",
                "DEVIATION_ROOT_CAUSE_RECORDED",
                "DEVIATION_CAPA_STARTED",
                "CAPA_CREATED",
                "CAPA_STARTED",
                "CAPA_COMPLETED",
                "CAPA_CANCELLED",
                "DEVIATION_SUBMITTED_FOR_VERIFICATION",
                "DEVIATION_CLOSED"
        )));

        // Verify query filtering methods
        List<DeviationSummaryDto> closedFilter = deviationService.getDeviations(
                DeviationStatus.CLOSED, null, null, null, null
        );
        assertEquals(1, closedFilter.size());
        assertEquals(created.getDeviationNumber(), closedFilter.get(0).getDeviationNumber());
        assertEquals(1, deviationService.getBatchDeviations(completedBatch.getId()).size());

        // Verify existing Batch, QC check, and eBR step remain untouched
        entityManager.clear();
        Batch reloadedBatch = batchRepository.findById(completedBatch.getId()).orElseThrow();
        QualityCheck reloadedQc = qualityCheckRepository.findById(failedCheckOnCompleted.getId()).orElseThrow();
        assertEquals(BatchStatus.COMPLETED, reloadedBatch.getStatus());
        assertEquals(BatchQualityStatus.PENDING_QA, reloadedBatch.getQaStatus());
        assertEquals(0, new BigDecimal("248.5000").compareTo(reloadedBatch.getActualYield()));
        assertEquals(QualityCheckResult.FAIL, reloadedQc.getResult());
    }

    @Test
    @DisplayName("2. Reject PLANNED/CANCELLED batches and mismatched cross-batch QC/Step/Audit links with zero DB side effects")
    void rejectsIneligibleBatchesAndMismatchedLinksWithoutSideEffects() {
        long devBefore = deviationRepository.count();
        long auditBefore = auditLogRepository.count();

        // PLANNED batch rejected
        InvalidDeviationOperationException exPlanned = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.createDeviation(validCreateRequest(plannedBatch.getId(), null, null, null))
        );
        assertTrue(exPlanned.getMessage().contains("IN_PROGRESS or COMPLETED"));

        // CANCELLED batch rejected
        InvalidDeviationOperationException exCancelled = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.createDeviation(validCreateRequest(cancelledBatch.getId(), null, null, null))
        );
        assertTrue(exCancelled.getMessage().contains("CANCELLED"));

        // Cross-batch QC check rejected
        InvalidDeviationOperationException exQc = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.createDeviation(validCreateRequest(
                        completedBatch.getId(), checkOnOtherBatch.getId(), null, null
                ))
        );
        assertTrue(exQc.getMessage().contains("does not belong to batch"));

        // Cross-batch Execution step rejected
        InvalidDeviationOperationException exStep = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.createDeviation(validCreateRequest(
                        completedBatch.getId(), null, stepOnInProgress.getId(), null
                ))
        );
        assertTrue(exStep.getMessage().contains("does not belong to batch"));

        // Cross-batch Audit log rejected
        InvalidDeviationOperationException exAudit = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.createDeviation(validCreateRequest(
                        completedBatch.getId(), null, null, auditOnInProgress.getId()
                ))
        );
        assertTrue(exAudit.getMessage().contains("does not belong to batch"));

        // Unknown batch throws ResourceNotFoundException
        assertThrows(
                ResourceNotFoundException.class,
                () -> deviationService.createDeviation(validCreateRequest(999999L, null, null, null))
        );

        assertEquals(devBefore, deviationRepository.count());
        assertEquals(auditBefore, auditLogRepository.count());
    }

    @Test
    @DisplayName("3. Invalid lifecycle skips, unauthenticated calls, and missing mandatory inputs are rejected cleanly without side effects")
    void rejectsInvalidTransitionsAndMissingFieldsWithoutSideEffects() {
        DeviationDetailDto dev = deviationService.createDeviation(
                validCreateRequest(completedBatch.getId(), failedCheckOnCompleted.getId(), null, null)
        );
        long auditCountAfterCreate = auditLogRepository.count();

        // Cannot skip directly from OPEN -> ROOT_CAUSE_IDENTIFIED, CAPA_IN_PROGRESS, PENDING_VERIFICATION, or CLOSED
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.recordRootCause(
                dev.getId(),
                new RecordRootCauseRequestDto("Equipment", "RCA", "Impact", "Inv")
        ));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.startCapa(
                dev.getId(),
                new StartCapaRequestDto("Plan")
        ));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.submitForVerification(
                dev.getId(),
                new SubmitVerificationRequestDto("Notes")
        ));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.closeDeviation(
                dev.getId(),
                new CloseDeviationRequestDto("Verified")
        ));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.reopenCapa(
                dev.getId(),
                new ReopenCapaRequestDto("Reason")
        ));

        // Unauthenticated create / startInvestigation fail closed with AuthenticationCredentialsNotFoundException
        SecurityContextHolder.clearContext();
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> deviationService.createDeviation(
                validCreateRequest(completedBatch.getId(), failedCheckOnCompleted.getId(), null, null)
        ));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> deviationService.startInvestigation(
                dev.getId(), new StartInvestigationRequestDto("Some summary")
        ));

        // Missing mandatory summary on OPEN -> UNDER_INVESTIGATION
        authenticateAs("qa.lead", "QA_REVIEWER");
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.startInvestigation(
                dev.getId(), new StartInvestigationRequestDto(" ")
        ));

        // Advance validly to ROOT_CAUSE_IDENTIFIED
        deviationService.startInvestigation(dev.getId(), new StartInvestigationRequestDto("Reviewing records"));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.recordRootCause(
                dev.getId(), new RecordRootCauseRequestDto(" ", "RCA text", "Impact text", null)
        ));
        deviationService.recordRootCause(dev.getId(), new RecordRootCauseRequestDto(
                "Process", "Blend duration short", "Single batch", null
        ));

        // Cannot enter CAPA_IN_PROGRESS without at least one active (non-cancelled) CAPA
        InvalidDeviationOperationException noCapaEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.startCapa(dev.getId(), new StartCapaRequestDto("Start CAPA"))
        );
        assertTrue(noCapaEx.getMessage().contains("at least one active CAPA"));

        // Confirm audit logs only increased by the 2 valid transitions (startInvestigation + recordRootCause)
        assertEquals(auditCountAfterCreate + 2, auditLogRepository.count());
        assertEquals(DeviationStatus.ROOT_CAUSE_IDENTIFIED, deviationService.getDeviationById(dev.getId()).getStatus());
    }

    @Test
    @DisplayName("4. Rejects past CAPA due dates (Asia/Kolkata), incomplete CAPAs, cancelled-only CAPAs, and supports CAPA reopen workflow")
    void rejectsInvalidDueDatesIncompleteCapasAndSupportsReopen() {
        LocalDate today = LocalDate.now(DeviationService.APP_ZONE_ID);

        DeviationDetailDto dev = deviationService.createDeviation(
                validCreateRequest(completedBatch.getId(), failedCheckOnCompleted.getId(), null, null)
        );
        deviationService.startInvestigation(dev.getId(), new StartInvestigationRequestDto("Investigation started"));
        deviationService.recordRootCause(dev.getId(), new RecordRootCauseRequestDto(
                "Equipment", "Sensor drift detected", "Limited to current batch", null
        ));

        long capaBefore = capaActionRepository.count();
        long auditBefore = auditLogRepository.count();

        // Past due date rejected (Asia/Kolkata)
        InvalidDeviationOperationException pastDateEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.createCapaAction(
                        dev.getId(),
                        new CreateCapaActionRequestDto(
                                CapaType.CORRECTIVE, "Past due CAPA", "Fix sensor",
                                "Eng. Rao", today.minusDays(1)
                        )
                )
        );
        assertTrue(pastDateEx.getMessage().contains("cannot be earlier than today"));
        assertEquals(capaBefore, capaActionRepository.count());
        assertEquals(auditBefore, auditLogRepository.count());

        // Create two CAPAs: cancel first, keep second OPEN
        CapaActionResponseDto cancelledOnly = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.CORRECTIVE, "Temp check", "Temporary manual verification",
                        "Eng. Rao", today.plusDays(3)
                )
        );
        deviationService.cancelCapaAction(cancelledOnly.getId(), new CancelCapaActionRequestDto("Duplicate action"));

        // Cancelled-only CAPA cannot enter CAPA_IN_PROGRESS
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.startCapa(
                dev.getId(), new StartCapaRequestDto("Trying with only cancelled CAPA")
        ));

        // Add active CAPA and start CAPA_IN_PROGRESS
        CapaActionResponseDto activeCapa = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.CORRECTIVE, "Replace load cell", "Install calibrated sensor and verify linearity",
                        "Eng. Rao", today.plusDays(5)
                )
        );
        deviationService.startCapa(dev.getId(), new StartCapaRequestDto("CAPA plan approved"));

        // Cannot submit for verification while CAPA is still OPEN
        InvalidDeviationOperationException openCapaEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.submitForVerification(
                        dev.getId(), new SubmitVerificationRequestDto("Premature submit")
                )
        );
        assertTrue(openCapaEx.getMessage().contains("OPEN or IN_PROGRESS"));

        // Complete CAPA missing evidence fails without saving
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.completeCapaAction(
                activeCapa.getId(), new CompleteCapaActionRequestDto("   ")
        ));

        // Complete active CAPA properly -> submit for verification -> PENDING_VERIFICATION
        deviationService.completeCapaAction(
                activeCapa.getId(), new CompleteCapaActionRequestDto("Calibration report CR-901 uploaded.")
        );
        DeviationDetailDto pendingVer = deviationService.submitForVerification(
                dev.getId(), new SubmitVerificationRequestDto("Ready for QA effectiveness verification.")
        );
        assertEquals(DeviationStatus.PENDING_VERIFICATION, pendingVer.getStatus());

        // While in PENDING_VERIFICATION, CAPA edits/additions are locked
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.PREVENTIVE, "Late CAPA", "Should require reopen first",
                        "QA Lead", today.plusDays(10)
                )
        ));

        // Reopen CAPA requires mandatory non-blank reason
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.reopenCapa(
                dev.getId(), new ReopenCapaRequestDto("   ")
        ));

        // Valid Reopen transitions back to CAPA_IN_PROGRESS with audit entry
        DeviationDetailDto reopened = deviationService.reopenCapa(
                dev.getId(),
                new ReopenCapaRequestDto("Need preventive SOP revision before closing deviation.")
        );
        assertEquals(DeviationStatus.CAPA_IN_PROGRESS, reopened.getStatus());

        // Now add preventive CAPA, complete it, resubmit, verify and close
        CapaActionResponseDto preventiveCapa = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.PREVENTIVE, "Update SOP-PM-03", "Add 90-day load-cell drift verification check",
                        "QA Spec", today.plusDays(10)
                )
        );
        deviationService.completeCapaAction(
                preventiveCapa.getId(),
                new CompleteCapaActionRequestDto("SOP-PM-03 rev 4 approved via DOC-402.")
        );
        deviationService.submitForVerification(
                dev.getId(),
                new SubmitVerificationRequestDto("Both corrective and preventive actions verified.")
        );

        // Closure requires non-blank verification notes
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.closeDeviation(
                dev.getId(), new CloseDeviationRequestDto("   ")
        ));

        authenticateAs("qa.head", "QA_REVIEWER");
        DeviationDetailDto closed = deviationService.closeDeviation(
                dev.getId(),
                new CloseDeviationRequestDto("Effectiveness verified against CR-901 and DOC-402.")
        );
        assertEquals(DeviationStatus.CLOSED, closed.getStatus());
        assertEquals("qa.head", closed.getVerifiedBy());
        assertEquals("qa.head", closed.getClosedBy());
        assertTrue(closed.getAuditLogs().stream().anyMatch(a -> "DEVIATION_CAPA_REOPENED".equals(a.getAction())));
    }

    @Test
    @DisplayName("5. Closed deviation is strictly immutable across all deviation and CAPA operations")
    void closedDeviationIsImmutable() {
        LocalDate today = LocalDate.now(DeviationService.APP_ZONE_ID);

        DeviationDetailDto dev = deviationService.createDeviation(
                validCreateRequest(completedBatch.getId(), failedCheckOnCompleted.getId(), null, null)
        );
        deviationService.startInvestigation(dev.getId(), new StartInvestigationRequestDto("Investigation notes"));
        deviationService.recordRootCause(dev.getId(), new RecordRootCauseRequestDto(
                "Method", "Probe misaligned", "Batch BAT-2026-0001-01", null
        ));
        CapaActionResponseDto capa = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.CORRECTIVE, "Realign probe", "Realign dissolution shaft and run test verification",
                        "QC Analyst", today.plusDays(1)
                )
        );
        deviationService.startCapa(dev.getId(), new StartCapaRequestDto(null));
        deviationService.completeCapaAction(capa.getId(), new CompleteCapaActionRequestDto("Verified shaft alignment log AL-12."));
        deviationService.submitForVerification(dev.getId(), new SubmitVerificationRequestDto(null));
        deviationService.closeDeviation(dev.getId(), new CloseDeviationRequestDto("Closure approved."));

        long auditCountWhenClosed = auditLogRepository.count();

        // All subsequent mutations on deviation or CAPAs must fail with zero additional audit logs
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.startInvestigation(
                dev.getId(), new StartInvestigationRequestDto("Retry")
        ));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.recordRootCause(
                dev.getId(), new RecordRootCauseRequestDto("Method", "Edit", "Edit", null)
        ));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.startCapa(
                dev.getId(), new StartCapaRequestDto("Retry")
        ));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.submitForVerification(
                dev.getId(), new SubmitVerificationRequestDto("Retry")
        ));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.reopenCapa(
                dev.getId(), new ReopenCapaRequestDto("Reopen after close")
        ));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.closeDeviation(
                dev.getId(), new CloseDeviationRequestDto("Reclose")
        ));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.createCapaAction(
                dev.getId(), new CreateCapaActionRequestDto(
                        CapaType.PREVENTIVE, "Extra", "Plan", "Owner", today.plusDays(2)
                )
        ));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.startCapaAction(
                capa.getId(), new StartCapaActionRequestDto(null)
        ));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.completeCapaAction(
                capa.getId(), new CompleteCapaActionRequestDto("Overwrite evidence")
        ));
        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.cancelCapaAction(
                capa.getId(), new CancelCapaActionRequestDto("Cancel closed CAPA")
        ));

        assertEquals(auditCountWhenClosed, auditLogRepository.count(), "No audit logs may be written after rejected closed-deviation calls");
        assertEquals(DeviationStatus.CLOSED, deviationService.getDeviationById(dev.getId()).getStatus());
    }

    @Test
    @DisplayName("6. Defensive gate blocks closure if an underlying deviation is persisted without complete RCA or active CAPAs")
    void closureGateBlocksIncompleteRecordsEvenInPendingVerificationState() {
        // Persist a synthetic record directly in PENDING_VERIFICATION with missing RCA and 0 CAPAs
        Deviation broken = new Deviation(
                "DEV-2026-9999",
                completedBatch,
                null,
                null,
                null,
                "Synthetic incomplete deviation",
                DeviationCategory.OTHER,
                DeviationSeverity.MINOR,
                DeviationPriority.LOW,
                DeviationStatus.PENDING_VERIFICATION,
                "Description present.",
                null,
                "Reporter",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusHours(1),
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusHours(1)
        );
        broken = deviationRepository.saveAndFlush(broken);
        final Long brokenId = broken.getId();

        DeviationDetailDto detail = deviationService.getDeviationById(brokenId);
        assertFalse(detail.isEligibleForClosure());
        assertFalse(detail.getClosureBlockers().isEmpty());
        assertTrue(detail.getClosureBlockers().stream().anyMatch(m -> m.contains("Investigation")));
        assertTrue(detail.getClosureBlockers().stream().anyMatch(m -> m.contains("Root cause")));
        assertTrue(detail.getClosureBlockers().stream().anyMatch(m -> m.contains("Impact assessment")));
        assertTrue(detail.getClosureBlockers().stream().anyMatch(m -> m.contains("At least one non-cancelled CAPA")));

        long auditBefore = auditLogRepository.count();
        InvalidDeviationOperationException ex = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.closeDeviation(
                        brokenId,
                        new CloseDeviationRequestDto("Force closure attempt")
                )
        );
        assertTrue(ex.getMessage().contains("Cannot close deviation"));
        assertEquals(auditBefore, auditLogRepository.count());
        assertEquals(DeviationStatus.PENDING_VERIFICATION, deviationRepository.findById(brokenId).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("7. getLinkableRecordsForBatch returns batch-scoped QC checks, eBR steps, and audit logs and rejects unknown/PLANNED/CANCELLED batches")
    void getLinkableRecordsForBatchEnforcesScopeAndBatchStatus() {
        BatchLinkableRecordsDto completedRecords = deviationService.getLinkableRecordsForBatch(completedBatch.getId());
        assertEquals(completedBatch.getId(), completedRecords.getBatchId());
        assertEquals(completedBatch.getBatchNumber(), completedRecords.getBatchNumber());
        assertEquals(BatchStatus.COMPLETED, completedRecords.getBatchStatus());
        assertEquals(BatchQualityStatus.PENDING_QA, completedRecords.getQaStatus());
        assertEquals("MO-2026-0001", completedRecords.getOrderNumber());
        assertEquals("AMOX-250", completedRecords.getProductCode());
        assertEquals(1, completedRecords.getQualityChecks().size());
        assertEquals(failedCheckOnCompleted.getId(), completedRecords.getQualityChecks().get(0).getId());
        assertEquals(1, completedRecords.getExecutionSteps().size());
        assertEquals(stepOnCompleted.getId(), completedRecords.getExecutionSteps().get(0).getId());
        assertEquals(1, completedRecords.getAuditLogs().size());
        assertEquals(auditOnCompleted.getId(), completedRecords.getAuditLogs().get(0).getId());

        BatchLinkableRecordsDto inProgressRecords = deviationService.getLinkableRecordsForBatch(inProgressBatch.getId());
        assertEquals(inProgressBatch.getId(), inProgressRecords.getBatchId());
        assertEquals(BatchStatus.IN_PROGRESS, inProgressRecords.getBatchStatus());
        assertTrue(inProgressRecords.getQualityChecks().isEmpty());
        assertEquals(1, inProgressRecords.getExecutionSteps().size());
        assertEquals(stepOnInProgress.getId(), inProgressRecords.getExecutionSteps().get(0).getId());
        assertEquals(1, inProgressRecords.getAuditLogs().size());
        assertEquals(auditOnInProgress.getId(), inProgressRecords.getAuditLogs().get(0).getId());

        assertThrows(ResourceNotFoundException.class,
                () -> deviationService.getLinkableRecordsForBatch(999999L));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.getLinkableRecordsForBatch(plannedBatch.getId()));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.getLinkableRecordsForBatch(cancelledBatch.getId()));
    }

    @Test
    @DisplayName("8. Enforces CAPA stage restrictions (creation/cancel in RCA or CAPA_IN_PROGRESS, execution only in CAPA_IN_PROGRESS, never cancel last active CAPA)")
    void enforcesCapaStageRestrictionsAndPreventsCancellingLastActiveCapa() {
        LocalDate today = LocalDate.now(DeviationService.APP_ZONE_ID);
        DeviationDetailDto dev = deviationService.createDeviation(
                validCreateRequest(completedBatch.getId(), failedCheckOnCompleted.getId(), null, null)
        );
        long capaStartCount = capaActionRepository.count();
        long auditAfterCreate = auditLogRepository.count();

        // OPEN: CAPA creation blocked
        InvalidDeviationOperationException createOpenEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.createCapaAction(
                        dev.getId(),
                        new CreateCapaActionRequestDto(
                                CapaType.CORRECTIVE, "Premature CAPA", "Should be blocked in OPEN",
                                "Eng. Kumar", today.plusDays(2)
                        )
                )
        );
        assertTrue(createOpenEx.getMessage().contains("ROOT_CAUSE_IDENTIFIED or CAPA_IN_PROGRESS"));
        assertEquals(capaStartCount, capaActionRepository.count());
        assertEquals(auditAfterCreate, auditLogRepository.count());

        // UNDER_INVESTIGATION: CAPA creation blocked
        deviationService.startInvestigation(
                dev.getId(),
                new StartInvestigationRequestDto("Evaluating root causes")
        );
        long auditAfterInv = auditLogRepository.count();

        InvalidDeviationOperationException createInvEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.createCapaAction(
                        dev.getId(),
                        new CreateCapaActionRequestDto(
                                CapaType.CORRECTIVE, "Premature CAPA", "Should be blocked in UNDER_INVESTIGATION",
                                "Eng. Kumar", today.plusDays(2)
                        )
                )
        );
        assertTrue(createInvEx.getMessage().contains("UNDER_INVESTIGATION"));
        assertEquals(capaStartCount, capaActionRepository.count());
        assertEquals(auditAfterInv, auditLogRepository.count());

        // ROOT_CAUSE_IDENTIFIED: CAPA creation & cancellation allowed, start & complete blocked
        deviationService.recordRootCause(
                dev.getId(),
                new RecordRootCauseRequestDto("Equipment", "Loose sensor", "Single batch", null)
        );
        CapaActionResponseDto draftCapa = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.CORRECTIVE, "Draft remediation", "Re-terminate sensor",
                        "Eng. Kumar", today.plusDays(3)
                )
        );
        long auditAfterDraft = auditLogRepository.count();

        InvalidDeviationOperationException startInRcaEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.startCapaAction(draftCapa.getId(), new StartCapaActionRequestDto("Too early"))
        );
        assertTrue(startInRcaEx.getMessage().contains("expected CAPA_IN_PROGRESS"));

        InvalidDeviationOperationException completeInRcaEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.completeCapaAction(
                        draftCapa.getId(),
                        new CompleteCapaActionRequestDto("Too early evidence")
                )
        );
        assertTrue(completeInRcaEx.getMessage().contains("expected CAPA_IN_PROGRESS"));
        assertEquals(CapaStatus.OPEN, capaActionRepository.findById(draftCapa.getId()).orElseThrow().getStatus());
        assertEquals(auditAfterDraft, auditLogRepository.count());

        // Single CAPA cancellation IS permitted in ROOT_CAUSE_IDENTIFIED
        CapaActionResponseDto cancelledDraft = deviationService.cancelCapaAction(
                draftCapa.getId(),
                new CancelCapaActionRequestDto("Replaced with updated remediation scope")
        );
        assertEquals(CapaStatus.CANCELLED, cancelledDraft.getStatus());

        // Create active CAPA and enter CAPA_IN_PROGRESS
        CapaActionResponseDto activeCapa = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.CORRECTIVE, "Calibrate transmitter", "Full 3-point calibration",
                        "Eng. Kumar", today.plusDays(3)
                )
        );
        deviationService.startCapa(dev.getId(), new StartCapaRequestDto("Plan approved"));
        long auditInCapaProgress = auditLogRepository.count();

        // Cannot cancel the last remaining active CAPA in CAPA_IN_PROGRESS (even when cancelled sibling exists)
        InvalidDeviationOperationException cancelLastOpenEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.cancelCapaAction(
                        activeCapa.getId(),
                        new CancelCapaActionRequestDto("Attempt to leave 0 active CAPAs")
                )
        );
        assertTrue(cancelLastOpenEx.getMessage().contains("last active CAPA action"));
        assertEquals(CapaStatus.OPEN, capaActionRepository.findById(activeCapa.getId()).orElseThrow().getStatus());
        assertEquals(auditInCapaProgress, auditLogRepository.count());

        // Start CAPA -> still cannot cancel while it is the only active CAPA
        deviationService.startCapaAction(activeCapa.getId(), new StartCapaActionRequestDto("Started work"));
        long auditAfterStart = auditLogRepository.count();
        assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.cancelCapaAction(
                        activeCapa.getId(),
                        new CancelCapaActionRequestDto("Still last active")
                )
        );
        assertEquals(CapaStatus.IN_PROGRESS, capaActionRepository.findById(activeCapa.getId()).orElseThrow().getStatus());
        assertEquals(auditAfterStart, auditLogRepository.count());

        // Adding a replacement CAPA allows cancelling one while keeping at least one active CAPA
        CapaActionResponseDto replacementCapa = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.PREVENTIVE, "Add locknut", "Install vibration locknut on transmitter",
                        "Eng. Kumar", today.plusDays(5)
                )
        );
        CapaActionResponseDto cancelledFirst = deviationService.cancelCapaAction(
                activeCapa.getId(),
                new CancelCapaActionRequestDto("Superseded by replacement CAPA")
        );
        assertEquals(CapaStatus.CANCELLED, cancelledFirst.getStatus());

        // Now replacementCapa becomes the last active CAPA, so it cannot be cancelled either
        assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.cancelCapaAction(
                        replacementCapa.getId(),
                        new CancelCapaActionRequestDto("Would leave zero active CAPAs")
                )
        );

        // Complete replacement CAPA and submit for verification -> all CAPA start/complete/cancel blocked in PENDING_VERIFICATION
        deviationService.completeCapaAction(
                replacementCapa.getId(),
                new CompleteCapaActionRequestDto("Locknut installed and verified.")
        );
        deviationService.submitForVerification(dev.getId(), new SubmitVerificationRequestDto("Ready"));

        CapaActionResponseDto secondActiveForLockedCheck;
        deviationService.reopenCapa(dev.getId(), new ReopenCapaRequestDto("Add extra check"));
        secondActiveForLockedCheck = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.PREVENTIVE, "Training check", "Train operators on assembly torque",
                        "QA Lead", today.plusDays(6)
                )
        );
        deviationService.completeCapaAction(
                secondActiveForLockedCheck.getId(),
                new CompleteCapaActionRequestDto("Training log TR-12 signed")
        );
        deviationService.submitForVerification(dev.getId(), new SubmitVerificationRequestDto("Resubmitted"));
        long auditPendingVerify = auditLogRepository.count();

        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.startCapaAction(secondActiveForLockedCheck.getId(), new StartCapaActionRequestDto(null)));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.completeCapaAction(secondActiveForLockedCheck.getId(), new CompleteCapaActionRequestDto("Re-complete")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.cancelCapaAction(secondActiveForLockedCheck.getId(), new CancelCapaActionRequestDto("Cancel in verify")));
        assertEquals(auditPendingVerify, auditLogRepository.count());
    }

    @Test
    @DisplayName("9. Audit snapshots capture full reporting, investigation, root-cause, CAPA plan, verification, and closure details")
    void auditSnapshotsCaptureComprehensiveBeforeAfterDetails() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        LocalDate today = LocalDate.now(DeviationService.APP_ZONE_ID);

        authenticateAs("A.Nair", "QC_ANALYST");
        CreateDeviationRequestDto createReq = new CreateDeviationRequestDto(
                completedBatch.getId(),
                failedCheckOnCompleted.getId(),
                stepOnCompleted.getId(),
                auditOnCompleted.getId(),
                "Dissolution excursion investigation snapshot check",
                DeviationCategory.QC_OOS_FAILURE,
                DeviationSeverity.CRITICAL,
                DeviationPriority.HIGH,
                "Detailed description of observed 65% dissolution result.",
                "Batch placed in quarantine cage C1.",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(5)
        );
        DeviationDetailDto created = deviationService.createDeviation(createReq);

        // Long 2400-character investigation summary to verify snapshot preserves full text while AuditLog.reason stays <= 2000
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        String longInvestigationSummary = "Investigation Findings: " + "R".repeat(2375);
        deviationService.startInvestigation(
                created.getId(),
                new StartInvestigationRequestDto(longInvestigationSummary)
        );
        deviationService.recordRootCause(
                created.getId(),
                new RecordRootCauseRequestDto(
                        "Equipment / Mechanical",
                        "Punch spring fatigue reduced compression force below lower control limit.",
                        "Impacted BAT-2026-0001-01 only; bracketing lots verified within spec.",
                        null
                )
        );
        CapaActionResponseDto capa1 = deviationService.createCapaAction(
                created.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.CORRECTIVE,
                        "Replace punch spring",
                        "Install OEM spring set and calibrate press turret pressure sensors.",
                        "Eng. Kumar",
                        today.plusDays(2)
                )
        );
        CapaActionResponseDto capa2 = deviationService.createCapaAction(
                created.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.PREVENTIVE,
                        "Optional checklist",
                        "Supplemental visual check already covered by SOP-09.",
                        "Eng. Kumar",
                        today.plusDays(4)
                )
        );
        deviationService.startCapa(
                created.getId(),
                new StartCapaRequestDto("Execute corrective replacement and sensor calibration.")
        );
        authenticateAs("Eng.Kumar", "PRODUCTION_SUPERVISOR");
        deviationService.completeCapaAction(
                capa1.getId(),
                new CompleteCapaActionRequestDto("Spring set replaced; calibration report CAL-505 passed.")
        );
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        deviationService.cancelCapaAction(
                capa2.getId(),
                new CancelCapaActionRequestDto("Consolidated into SOP-09 preventive check.")
        );
        authenticateAs("Eng.Kumar", "PRODUCTION_SUPERVISOR");
        deviationService.submitForVerification(
                created.getId(),
                new SubmitVerificationRequestDto("Evidence package attached for QA effectiveness check.")
        );
        authenticateAs("QA.Head.Patel", "QA_REVIEWER");
        DeviationDetailDto closed = deviationService.closeDeviation(
                created.getId(),
                new CloseDeviationRequestDto("Verified CAL-505 and press compression charts.")
        );

        List<AuditLogResponseDto> logs = closed.getAuditLogs();

        AuditLogResponseDto reportedLog = logs.stream()
                .filter(l -> "DEVIATION_REPORTED".equals(l.getAction()))
                .findFirst().orElseThrow();
        JsonNode reportedAfter = mapper.readTree(reportedLog.getAfterValues());
        assertEquals("Detailed description of observed 65% dissolution result.", reportedAfter.get("description").asText());
        assertEquals("Batch placed in quarantine cage C1.", reportedAfter.get("immediateContainment").asText());
        assertEquals("a.nair", reportedAfter.get("reportedBy").asText());
        assertFalse(reportedAfter.get("occurredAt").isNull());
        assertFalse(reportedAfter.get("reportedAt").isNull());

        AuditLogResponseDto invLog = logs.stream()
                .filter(l -> "DEVIATION_INVESTIGATION_STARTED".equals(l.getAction()))
                .findFirst().orElseThrow();
        assertTrue(invLog.getReason().length() <= 2000);
        JsonNode invBefore = mapper.readTree(invLog.getBeforeValues());
        JsonNode invAfter = mapper.readTree(invLog.getAfterValues());
        assertEquals("OPEN", invBefore.get("status").asText());
        assertEquals("UNDER_INVESTIGATION", invAfter.get("status").asText());
        assertEquals(longInvestigationSummary, invAfter.get("investigationSummary").asText());
        assertFalse(invAfter.get("investigationStartedAt").isNull());

        AuditLogResponseDto rcaLog = logs.stream()
                .filter(l -> "DEVIATION_ROOT_CAUSE_RECORDED".equals(l.getAction()))
                .findFirst().orElseThrow();
        JsonNode rcaAfter = mapper.readTree(rcaLog.getAfterValues());
        assertEquals("Equipment / Mechanical", rcaAfter.get("rootCauseCategory").asText());
        assertEquals("Punch spring fatigue reduced compression force below lower control limit.",
                rcaAfter.get("rootCauseAnalysis").asText());
        assertEquals("Impacted BAT-2026-0001-01 only; bracketing lots verified within spec.",
                rcaAfter.get("impactAssessment").asText());
        assertEquals("dr.menon", rcaAfter.get("rootCauseIdentifiedBy").asText());
        assertFalse(rcaAfter.get("rootCauseIdentifiedAt").isNull());

        AuditLogResponseDto capaStartLog = logs.stream()
                .filter(l -> "DEVIATION_CAPA_STARTED".equals(l.getAction()))
                .findFirst().orElseThrow();
        assertEquals("Execute corrective replacement and sensor calibration.",
                mapper.readTree(capaStartLog.getAfterValues()).get("capaPlanSummary").asText());

        AuditLogResponseDto capaCompletedLog = logs.stream()
                .filter(l -> "CAPA_COMPLETED".equals(l.getAction()))
                .findFirst().orElseThrow();
        JsonNode capaCompAfter = mapper.readTree(capaCompletedLog.getAfterValues());
        assertEquals("Install OEM spring set and calibrate press turret pressure sensors.",
                capaCompAfter.get("actionPlan").asText());
        assertEquals("dr.menon", capaCompAfter.get("createdBy").asText());
        assertEquals("eng.kumar", capaCompAfter.get("completedBy").asText());
        assertEquals("Spring set replaced; calibration report CAL-505 passed.",
                capaCompAfter.get("completionEvidence").asText());
        assertFalse(capaCompAfter.get("completedAt").isNull());

        AuditLogResponseDto closedLog = logs.stream()
                .filter(l -> "DEVIATION_CLOSED".equals(l.getAction()))
                .findFirst().orElseThrow();
        JsonNode closedBefore = mapper.readTree(closedLog.getBeforeValues());
        JsonNode closedAfter = mapper.readTree(closedLog.getAfterValues());
        assertEquals("PENDING_VERIFICATION", closedBefore.get("status").asText());
        assertEquals("CLOSED", closedAfter.get("status").asText());
        assertEquals("qa.head.patel", closedAfter.get("verifiedBy").asText());
        assertEquals("Verified CAL-505 and press compression charts.", closedAfter.get("verificationNotes").asText());
        assertEquals("qa.head.patel", closedAfter.get("closedBy").asText());
        assertFalse(closedAfter.get("closedAt").isNull());
    }

    @Test
    @DisplayName("10. Post-disposition deviations on RELEASED and REJECTED batches remain allowed and preserve existing QA disposition")
    void postDispositionDeviationsRemainAllowedWithoutChangingBatchQaStatus() {
        LocalDate today = LocalDate.now(DeviationService.APP_ZONE_ID);

        ManufacturingOrder order = completedBatch.getOrder();
        Batch releasedBatch = new Batch("BAT-2026-REL-POST", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        releasedBatch.setQaStatus(BatchQualityStatus.RELEASED);
        releasedBatch = entityManager.persistAndFlush(releasedBatch);

        Batch rejectedBatch = new Batch("BAT-2026-REJ-POST", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        rejectedBatch.setQaStatus(BatchQualityStatus.REJECTED);
        rejectedBatch = entityManager.persistAndFlush(rejectedBatch);

        // Report & close post-release deviation on RELEASED batch
        DeviationDetailDto postRelease = deviationService.createDeviation(new CreateDeviationRequestDto(
                releasedBatch.getId(), null, null, null,
                "Post-release market stability sample trend alert",
                DeviationCategory.OTHER, DeviationSeverity.MAJOR, DeviationPriority.MEDIUM,
                "3-month stability chamber humidity excursion noted.",
                "Initiated retrospective lot evaluation.",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(5)
        ));
        assertEquals(BatchQualityStatus.RELEASED, postRelease.getBatchQaStatus());
        assertEquals(BatchQualityStatus.RELEASED,
                batchRepository.findById(releasedBatch.getId()).orElseThrow().getQaStatus());

        deviationService.startInvestigation(postRelease.getId(),
                new StartInvestigationRequestDto("Stability chamber review"));
        deviationService.recordRootCause(postRelease.getId(), new RecordRootCauseRequestDto(
                "Environmental", "Temporary HVAC sensor drift", "No product potency impact", null
        ));
        CapaActionResponseDto postCapa = deviationService.createCapaAction(postRelease.getId(), new CreateCapaActionRequestDto(
                CapaType.PREVENTIVE, "Calibrate stability sensor", "Recalibrate chamber RH probe",
                "Facility Eng", today.plusDays(3)
        ));
        deviationService.startCapa(postRelease.getId(), new StartCapaRequestDto("Executing probe calibration"));
        deviationService.completeCapaAction(postCapa.getId(),
                new CompleteCapaActionRequestDto("RH probe calibration certificate issued."));
        deviationService.submitForVerification(postRelease.getId(),
                new SubmitVerificationRequestDto("Verified"));
        DeviationDetailDto closedPostRelease = deviationService.closeDeviation(postRelease.getId(),
                new CloseDeviationRequestDto("Confirmed no adverse batch impact."));
        assertEquals(DeviationStatus.CLOSED, closedPostRelease.getStatus());
        assertEquals(BatchQualityStatus.RELEASED,
                batchRepository.findById(releasedBatch.getId()).orElseThrow().getQaStatus());

        // Report post-rejection deviation on REJECTED batch -> allowed & qaStatus preserved
        DeviationDetailDto postReject = deviationService.createDeviation(new CreateDeviationRequestDto(
                rejectedBatch.getId(), null, null, null,
                "Traceability deviation for rejected scrap reconciliation",
                DeviationCategory.DOCUMENTATION_ERROR, DeviationSeverity.MINOR, DeviationPriority.LOW,
                "Reconciliation weight discrepancy prior to destruction.",
                "Held destruction lot for recount.",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(5)
        ));
        assertEquals(DeviationStatus.OPEN, postReject.getStatus());
        assertEquals(BatchQualityStatus.REJECTED,
                batchRepository.findById(rejectedBatch.getId()).orElseThrow().getQaStatus());
    }

    @Test
    @DisplayName("11. Rejects duplicate and backward deviation lifecycle transitions across every stage with zero DB or audit writes")
    void rejectsDuplicateAndBackwardDeviationTransitionsAcrossAllStages() {
        LocalDate today = LocalDate.now(DeviationService.APP_ZONE_ID);

        DeviationDetailDto dev = deviationService.createDeviation(
                validCreateRequest(completedBatch.getId(), failedCheckOnCompleted.getId(), null, null)
        );
        final Long devId = dev.getId();

        // 1. Move OPEN -> UNDER_INVESTIGATION
        authenticateAs("Dr.Original", "QA_REVIEWER");
        deviationService.startInvestigation(
                devId,
                new StartInvestigationRequestDto("Initial equipment and batch review.")
        );
        entityManager.flush();
        entityManager.clear();

        long auditAfterInv = auditLogRepository.count();
        // Duplicate startInvestigation + out-of-order jumps from UNDER_INVESTIGATION
        authenticateAs("Dr.Intruder", "QA_REVIEWER");
        InvalidDeviationOperationException dupInvEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.startInvestigation(
                        devId,
                        new StartInvestigationRequestDto("Attempted overwrite of investigation")
                )
        );
        assertTrue(dupInvEx.getMessage().contains("UNDER_INVESTIGATION"));
        assertTrue(dupInvEx.getMessage().contains("expected OPEN"));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.startCapa(devId, new StartCapaRequestDto("Skip RCA")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.submitForVerification(devId, new SubmitVerificationRequestDto("Skip to verify")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.reopenCapa(devId, new ReopenCapaRequestDto("Cannot reopen yet")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.closeDeviation(devId, new CloseDeviationRequestDto("Skip to close")));

        entityManager.flush();
        entityManager.clear();
        Deviation afterInvRejections = deviationRepository.findById(devId).orElseThrow();
        assertEquals(DeviationStatus.UNDER_INVESTIGATION, afterInvRejections.getStatus());
        assertEquals("dr.original", afterInvRejections.getInvestigatedBy());
        assertEquals("Initial equipment and batch review.", afterInvRejections.getInvestigationSummary());
        assertEquals(auditAfterInv, auditLogRepository.count());

        // 2. Move UNDER_INVESTIGATION -> ROOT_CAUSE_IDENTIFIED
        authenticateAs("Dr.Original", "QA_REVIEWER");
        deviationService.recordRootCause(
                devId,
                new RecordRootCauseRequestDto(
                        "Equipment", "Primary spring fatigue", "Isolated to current lot", null
                )
        );
        entityManager.flush();
        entityManager.clear();

        long auditAfterRca = auditLogRepository.count();
        // Backward startInvestigation + duplicate recordRootCause + out-of-order submit/reopen/close
        authenticateAs("Dr.Intruder", "QA_REVIEWER");
        InvalidDeviationOperationException backToInvEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.startInvestigation(
                        devId, new StartInvestigationRequestDto("Backward transition attempt")
                )
        );
        assertTrue(backToInvEx.getMessage().contains("ROOT_CAUSE_IDENTIFIED"));
        InvalidDeviationOperationException dupRcaEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.recordRootCause(
                        devId, new RecordRootCauseRequestDto("Method", "Overwrite RCA", "Overwrite impact", null)
                )
        );
        assertTrue(dupRcaEx.getMessage().contains("expected UNDER_INVESTIGATION"));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.submitForVerification(devId, new SubmitVerificationRequestDto("Skip start-capa")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.reopenCapa(devId, new ReopenCapaRequestDto("Invalid reopen")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.closeDeviation(devId, new CloseDeviationRequestDto("Skip to close")));

        entityManager.flush();
        entityManager.clear();
        Deviation afterRcaRejections = deviationRepository.findById(devId).orElseThrow();
        assertEquals(DeviationStatus.ROOT_CAUSE_IDENTIFIED, afterRcaRejections.getStatus());
        assertEquals("Equipment", afterRcaRejections.getRootCauseCategory());
        assertEquals("Primary spring fatigue", afterRcaRejections.getRootCauseAnalysis());
        assertEquals(auditAfterRca, auditLogRepository.count());

        // 3. Create CAPA and move ROOT_CAUSE_IDENTIFIED -> CAPA_IN_PROGRESS
        authenticateAs("Dr.Original", "QA_REVIEWER");
        CapaActionResponseDto capa1 = deviationService.createCapaAction(
                devId,
                new CreateCapaActionRequestDto(
                        CapaType.CORRECTIVE, "Replace spring", "Install new spring and re-qualify press",
                        "Eng. Rao", today.plusDays(3)
                )
        );
        deviationService.startCapa(devId, new StartCapaRequestDto("Approved CAPA Plan v1"));
        entityManager.flush();
        entityManager.clear();

        long auditAfterStartCapa = auditLogRepository.count();
        // Backward transitions + duplicate startCapa + out-of-order reopen/close
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.startInvestigation(devId, new StartInvestigationRequestDto("Backward")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.recordRootCause(devId, new RecordRootCauseRequestDto("Other", "Backward", "Impact", null)));
        InvalidDeviationOperationException dupStartCapaEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.startCapa(devId, new StartCapaRequestDto("Overwrite CAPA Plan"))
        );
        assertTrue(dupStartCapaEx.getMessage().contains("CAPA_IN_PROGRESS"));
        assertTrue(dupStartCapaEx.getMessage().contains("expected ROOT_CAUSE_IDENTIFIED"));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.reopenCapa(devId, new ReopenCapaRequestDto("Already in CAPA_IN_PROGRESS")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.closeDeviation(devId, new CloseDeviationRequestDto("Skip verification")));

        entityManager.flush();
        entityManager.clear();
        Deviation afterCapaStartRejections = deviationRepository.findById(devId).orElseThrow();
        assertEquals(DeviationStatus.CAPA_IN_PROGRESS, afterCapaStartRejections.getStatus());
        assertEquals("Approved CAPA Plan v1", afterCapaStartRejections.getCapaPlanSummary());
        assertEquals(auditAfterStartCapa, auditLogRepository.count());

        // 4. Complete CAPA and move CAPA_IN_PROGRESS -> PENDING_VERIFICATION
        authenticateAs("Eng.Rao", "PRODUCTION_SUPERVISOR");
        deviationService.completeCapaAction(capa1.getId(), new CompleteCapaActionRequestDto("Press qualified per PQ-12."));
        deviationService.submitForVerification(devId, new SubmitVerificationRequestDto("Ready for QA sign-off"));
        entityManager.flush();
        entityManager.clear();

        long auditAfterVerifySubmit = auditLogRepository.count();
        // Backward transitions + duplicate submitForVerification
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.startInvestigation(devId, new StartInvestigationRequestDto("Backward")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.recordRootCause(devId, new RecordRootCauseRequestDto("Other", "Backward", "Impact", null)));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.startCapa(devId, new StartCapaRequestDto("Backward to CAPA")));
        InvalidDeviationOperationException dupSubmitEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.submitForVerification(devId, new SubmitVerificationRequestDto("Duplicate submit"))
        );
        assertTrue(dupSubmitEx.getMessage().contains("PENDING_VERIFICATION"));
        assertTrue(dupSubmitEx.getMessage().contains("expected CAPA_IN_PROGRESS"));

        assertEquals(DeviationStatus.PENDING_VERIFICATION, deviationRepository.findById(devId).orElseThrow().getStatus());
        assertEquals(auditAfterVerifySubmit, auditLogRepository.count());

        // 5. Reopen -> CAPA_IN_PROGRESS, then duplicate reopenCapa rejected, then valid close succeeds
        authenticateAs("QA.Head", "QA_REVIEWER");
        deviationService.reopenCapa(devId, new ReopenCapaRequestDto("Confirm SOP revision attachment"));
        long auditAfterReopen = auditLogRepository.count();

        InvalidDeviationOperationException dupReopenEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.reopenCapa(devId, new ReopenCapaRequestDto("Duplicate reopen attempt"))
        );
        assertTrue(dupReopenEx.getMessage().contains("CAPA_IN_PROGRESS"));
        assertTrue(dupReopenEx.getMessage().contains("expected PENDING_VERIFICATION"));
        assertEquals(auditAfterReopen, auditLogRepository.count());

        // Valid forward path still completes cleanly
        authenticateAs("Eng.Rao", "PRODUCTION_SUPERVISOR");
        deviationService.submitForVerification(devId, new SubmitVerificationRequestDto("SOP attached"));
        authenticateAs("QA.Head", "QA_REVIEWER");
        DeviationDetailDto closed = deviationService.closeDeviation(
                devId, new CloseDeviationRequestDto("Effectiveness confirmed.")
        );
        assertEquals(DeviationStatus.CLOSED, closed.getStatus());
    }

    @Test
    @DisplayName("12. Enforces CAPA stage gates, rejects invalid CAPA item state transitions (IN_PROGRESS/COMPLETED/CANCELLED), and locks PENDING_VERIFICATION")
    void rejectsInvalidCapaStateTransitionsAcrossStartCompleteCancelAndVerificationLock() {
        LocalDate today = LocalDate.now(DeviationService.APP_ZONE_ID);

        // A. Case 3 & Defensive Case 4: OPEN / UNDER_INVESTIGATION reject CAPA creation and any CAPA execution/cancellation
        DeviationDetailDto earlyDev = deviationService.createDeviation(
                validCreateRequest(completedBatch.getId(), failedCheckOnCompleted.getId(), null, null)
        );
        Deviation managedEarly = deviationRepository.findById(earlyDev.getId()).orElseThrow();
        CapaAction seededEarlyCapa = entityManager.persistAndFlush(new CapaAction(
                "CAPA-2026-9901", managedEarly, CapaType.CORRECTIVE, "Seeded early CAPA",
                "Verify defensive gate in OPEN/UNDER_INVESTIGATION", "Eng. Rao",
                today.plusDays(4), CapaStatus.OPEN, "system"
        ));
        entityManager.flush();
        entityManager.clear();

        long auditEarly = auditLogRepository.count();
        long capaCountEarly = capaActionRepository.count();

        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.createCapaAction(
                earlyDev.getId(), new CreateCapaActionRequestDto(CapaType.CORRECTIVE, "Blocked", "In OPEN", "Eng", today.plusDays(1))
        ));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.startCapaAction(seededEarlyCapa.getId(), new StartCapaActionRequestDto("Blocked in OPEN")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.completeCapaAction(seededEarlyCapa.getId(), new CompleteCapaActionRequestDto("Blocked in OPEN")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.cancelCapaAction(seededEarlyCapa.getId(), new CancelCapaActionRequestDto("Blocked in OPEN")));

        deviationService.startInvestigation(earlyDev.getId(), new StartInvestigationRequestDto("Investigating"));
        long auditEarlyInv = auditLogRepository.count();

        assertThrows(InvalidDeviationOperationException.class, () -> deviationService.createCapaAction(
                earlyDev.getId(), new CreateCapaActionRequestDto(CapaType.CORRECTIVE, "Blocked", "In UNDER_INVESTIGATION", "Eng", today.plusDays(1))
        ));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.startCapaAction(seededEarlyCapa.getId(), new StartCapaActionRequestDto("Blocked in UNDER_INVESTIGATION")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.completeCapaAction(seededEarlyCapa.getId(), new CompleteCapaActionRequestDto("Blocked in UNDER_INVESTIGATION")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.cancelCapaAction(seededEarlyCapa.getId(), new CancelCapaActionRequestDto("Blocked in UNDER_INVESTIGATION")));

        assertEquals(capaCountEarly, capaActionRepository.count());
        assertEquals(auditEarly + 1, auditEarlyInv);
        assertEquals(auditEarlyInv, auditLogRepository.count());
        assertEquals(CapaStatus.OPEN, capaActionRepository.findById(seededEarlyCapa.getId()).orElseThrow().getStatus());

        // B. Normal lifecycle deviation for Case 4 (ROOT_CAUSE_IDENTIFIED), Case 5, Case 6, Case 7
        DeviationDetailDto dev = deviationService.createDeviation(
                validCreateRequest(inProgressBatch.getId(), null, stepOnInProgress.getId(), null)
        );
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        deviationService.startInvestigation(dev.getId(), new StartInvestigationRequestDto("Process excursion review"));
        deviationService.recordRootCause(dev.getId(), new RecordRootCauseRequestDto(
                "Process Parameter", "Impeller speed drift", "Limited to BAT-2026-0001-02", null
        ));

        CapaActionResponseDto capaOpenForDirectComplete = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(CapaType.CORRECTIVE, "Verify mixer torque", "Torque verification run", "Eng. Rao", today.plusDays(2))
        );
        CapaActionResponseDto capaToStart = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(CapaType.CORRECTIVE, "Recalibrate tachometer", "3-point RPM calibration", "Eng. Rao", today.plusDays(3))
        );
        CapaActionResponseDto capaToComplete = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(CapaType.PREVENTIVE, "Inspect drive belt", "Check drive belt wear and tension", "Eng. Rao", today.plusDays(4))
        );
        CapaActionResponseDto capaToCancel = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(CapaType.PREVENTIVE, "Redundant manual log", "Temporary duplicate paper check", "Eng. Rao", today.plusDays(5))
        );

        // Case 4 in ROOT_CAUSE_IDENTIFIED: start and complete CAPA actions rejected prior to CAPA_IN_PROGRESS
        long auditBeforeRcaCapaAttempt = auditLogRepository.count();
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.startCapaAction(capaToStart.getId(), new StartCapaActionRequestDto("Premature start")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.completeCapaAction(capaToStart.getId(), new CompleteCapaActionRequestDto("Premature complete")));
        assertEquals(CapaStatus.OPEN, capaActionRepository.findById(capaToStart.getId()).orElseThrow().getStatus());
        assertEquals(auditBeforeRcaCapaAttempt, auditLogRepository.count());

        // Enter CAPA_IN_PROGRESS
        deviationService.startCapa(dev.getId(), new StartCapaRequestDto("Proceed with remediation"));

        // Put CAPAs into distinct target states while keeping >1 active CAPA:
        // capaToStart -> IN_PROGRESS, capaToComplete -> COMPLETED, capaToCancel -> CANCELLED, capaOpenForDirectComplete stays OPEN
        authenticateAs("Eng.Rao", "PRODUCTION_SUPERVISOR");
        deviationService.startCapaAction(capaToStart.getId(), new StartCapaActionRequestDto("Started tachometer work"));
        deviationService.completeCapaAction(capaToComplete.getId(), new CompleteCapaActionRequestDto("Belt tension log BL-07 passed."));
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        deviationService.cancelCapaAction(capaToCancel.getId(), new CancelCapaActionRequestDto("Covered automatically by SCADA alarm."));
        entityManager.flush();
        entityManager.clear();

        long auditBeforeItemNegatives = auditLogRepository.count();

        // Case 5: Attempt to start an already IN_PROGRESS, COMPLETED, or CANCELLED CAPA
        InvalidDeviationOperationException startStartedEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.startCapaAction(capaToStart.getId(), new StartCapaActionRequestDto("Duplicate start"))
        );
        assertTrue(startStartedEx.getMessage().contains("IN_PROGRESS"));
        assertTrue(startStartedEx.getMessage().contains("expected OPEN"));

        InvalidDeviationOperationException startCompletedEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.startCapaAction(capaToComplete.getId(), new StartCapaActionRequestDto("Restart completed"))
        );
        assertTrue(startCompletedEx.getMessage().contains("COMPLETED"));

        InvalidDeviationOperationException startCancelledEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.startCapaAction(capaToCancel.getId(), new StartCapaActionRequestDto("Start cancelled"))
        );
        assertTrue(startCancelledEx.getMessage().contains("CANCELLED"));

        // Case 6: Attempt to complete or cancel an already COMPLETED or CANCELLED CAPA (with >1 active CAPAs present)
        authenticateAs("Intruder", "QA_REVIEWER");
        InvalidDeviationOperationException completeDoneEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.completeCapaAction(capaToComplete.getId(), new CompleteCapaActionRequestDto("Overwrite evidence"))
        );
        assertTrue(completeDoneEx.getMessage().contains("COMPLETED"));

        InvalidDeviationOperationException cancelDoneEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.cancelCapaAction(capaToComplete.getId(), new CancelCapaActionRequestDto("Cancel completed"))
        );
        assertTrue(cancelDoneEx.getMessage().contains("already COMPLETED"));

        InvalidDeviationOperationException completeCancelledEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.completeCapaAction(capaToCancel.getId(), new CompleteCapaActionRequestDto("Complete cancelled"))
        );
        assertTrue(completeCancelledEx.getMessage().contains("CANCELLED"));

        InvalidDeviationOperationException cancelCancelledEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.cancelCapaAction(capaToCancel.getId(), new CancelCapaActionRequestDto("Overwrite cancel reason"))
        );
        assertTrue(cancelCancelledEx.getMessage().contains("already CANCELLED"));

        entityManager.flush();
        entityManager.clear();

        // Confirm zero audit writes and intact evidence/reasons
        assertEquals(auditBeforeItemNegatives, auditLogRepository.count());
        CapaAction reloadedDone = capaActionRepository.findById(capaToComplete.getId()).orElseThrow();
        assertEquals(CapaStatus.COMPLETED, reloadedDone.getStatus());
        assertEquals("eng.rao", reloadedDone.getCompletedBy());
        assertEquals("Belt tension log BL-07 passed.", reloadedDone.getCompletionEvidence());
        CapaAction reloadedCancelled = capaActionRepository.findById(capaToCancel.getId()).orElseThrow();
        assertEquals(CapaStatus.CANCELLED, reloadedCancelled.getStatus());
        assertEquals("Covered automatically by SCADA alarm.", reloadedCancelled.getCancellationReason());

        // Valid completions from both OPEN and IN_PROGRESS still work cleanly
        authenticateAs("Eng.Rao", "PRODUCTION_SUPERVISOR");
        deviationService.completeCapaAction(capaOpenForDirectComplete.getId(), new CompleteCapaActionRequestDto("Torque report TR-19 passed."));
        deviationService.completeCapaAction(capaToStart.getId(), new CompleteCapaActionRequestDto("Tachometer cert TC-44 passed."));

        // Case 7: Submit for verification -> PENDING_VERIFICATION locks ALL CAPA mutations (create, start, complete, cancel)
        deviationService.submitForVerification(dev.getId(), new SubmitVerificationRequestDto("All active CAPAs completed"));
        entityManager.flush();
        entityManager.clear();

        long auditAtVerify = auditLogRepository.count();
        long capaCountAtVerify = capaActionRepository.count();

        authenticateAs("Dr.Menon", "QA_REVIEWER");
        InvalidDeviationOperationException createVerifyEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.createCapaAction(dev.getId(), new CreateCapaActionRequestDto(
                        CapaType.PREVENTIVE, "Extra check", "Must reopen first", "Eng. Rao", today.plusDays(6)
                ))
        );
        assertTrue(createVerifyEx.getMessage().contains("PENDING_VERIFICATION"));
        assertTrue(createVerifyEx.getMessage().contains("Reopen CAPA"));

        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.startCapaAction(capaOpenForDirectComplete.getId(), new StartCapaActionRequestDto("Start in verify")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.completeCapaAction(capaToStart.getId(), new CompleteCapaActionRequestDto("Complete in verify")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.cancelCapaAction(capaOpenForDirectComplete.getId(), new CancelCapaActionRequestDto("Cancel in verify")));
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.cancelCapaAction(capaToCancel.getId(), new CancelCapaActionRequestDto("Cancel sibling in verify")));

        assertEquals(capaCountAtVerify, capaActionRepository.count());
        assertEquals(auditAtVerify, auditLogRepository.count());

        // Valid closure still succeeds
        DeviationDetailDto closed = deviationService.closeDeviation(
                dev.getId(), new CloseDeviationRequestDto("Verified TR-19, TC-44, and BL-07.")
        );
        assertEquals(DeviationStatus.CLOSED, closed.getStatus());
    }

    @Test
    @DisplayName("13. Permits cancelling CAPAs when >1 active CAPA exists (subject to lifecycle rules) and blocks last active CAPA in CAPA_IN_PROGRESS")
    void verifiesMultiCapaCancellationRulesAndLastActiveCapaProtection() {
        LocalDate today = LocalDate.now(DeviationService.APP_ZONE_ID);

        DeviationDetailDto dev = deviationService.createDeviation(
                validCreateRequest(completedBatch.getId(), failedCheckOnCompleted.getId(), null, null)
        );
        deviationService.startInvestigation(dev.getId(), new StartInvestigationRequestDto("Evaluating OOS dissolution"));
        deviationService.recordRootCause(dev.getId(), new RecordRootCauseRequestDto(
                "Method", "Paddle speed wobble", "Limited to current batch", null
        ));

        // 1. In ROOT_CAUSE_IDENTIFIED: multiple active CAPAs can be planned and cancelled freely (down to 0 active)
        CapaActionResponseDto rcaCapa1 = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(CapaType.CORRECTIVE, "Plan draft A", "Replace shaft A", "Eng. Kumar", today.plusDays(2))
        );
        CapaActionResponseDto rcaCapa2 = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(CapaType.PREVENTIVE, "Plan draft B", "Replace shaft B", "Eng. Kumar", today.plusDays(3))
        );

        assertEquals(CapaStatus.CANCELLED,
                deviationService.cancelCapaAction(rcaCapa1.getId(), new CancelCapaActionRequestDto("Revising plan A")).getStatus());
        assertEquals(CapaStatus.CANCELLED,
                deviationService.cancelCapaAction(rcaCapa2.getId(), new CancelCapaActionRequestDto("Revising plan B too")).getStatus());

        // Cannot transition to CAPA_IN_PROGRESS with 0 active CAPAs
        assertThrows(InvalidDeviationOperationException.class,
                () -> deviationService.startCapa(dev.getId(), new StartCapaRequestDto("No active CAPAs left")));

        // 2. Create 3 active CAPAs and enter CAPA_IN_PROGRESS
        CapaActionResponseDto c1Completed = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(CapaType.CORRECTIVE, "Replace dissolution shaft", "Install OEM shaft", "Eng. Kumar", today.plusDays(2))
        );
        CapaActionResponseDto c2Open = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(CapaType.PREVENTIVE, "Optional SOP addendum", "Supplemental note", "Eng. Kumar", today.plusDays(4))
        );
        CapaActionResponseDto c3InProgress = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(CapaType.PREVENTIVE, "Optional spare bushing kit", "Stock spare kit", "Eng. Kumar", today.plusDays(5))
        );
        deviationService.startCapa(dev.getId(), new StartCapaRequestDto("Approved 3-CAPA remediation plan"));

        // Move c1 -> COMPLETED, c3 -> IN_PROGRESS (3 active CAPAs: c1 COMPLETED, c2 OPEN, c3 IN_PROGRESS)
        deviationService.completeCapaAction(c1Completed.getId(), new CompleteCapaActionRequestDto("OEM shaft cert SH-71 verified."));
        deviationService.startCapaAction(c3InProgress.getId(), new StartCapaActionRequestDto("Ordering kit"));

        // Permitted: cancel c2 (OPEN) because >1 active CAPAs exist (3 -> 2 active)
        CapaActionResponseDto c2Cancelled = deviationService.cancelCapaAction(
                c2Open.getId(), new CancelCapaActionRequestDto("Addendum unnecessary per QA")
        );
        assertEquals(CapaStatus.CANCELLED, c2Cancelled.getStatus());

        // Still subject to lifecycle rules: cannot cancel c1 (COMPLETED) even though >1 active CAPAs remain
        long auditBeforeLifecycleBlock = auditLogRepository.count();
        InvalidDeviationOperationException cancelCompletedWithSiblingEx = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.cancelCapaAction(
                        c1Completed.getId(), new CancelCapaActionRequestDto("Attempt to cancel COMPLETED CAPA")
                )
        );
        assertTrue(cancelCompletedWithSiblingEx.getMessage().contains("already COMPLETED"));
        assertEquals(auditBeforeLifecycleBlock, auditLogRepository.count());

        // Permitted: cancel c3 (IN_PROGRESS) while c1 (COMPLETED) remains as the 1 active non-cancelled CAPA
        CapaActionResponseDto c3Cancelled = deviationService.cancelCapaAction(
                c3InProgress.getId(), new CancelCapaActionRequestDto("Kit already stocked centrally")
        );
        assertEquals(CapaStatus.CANCELLED, c3Cancelled.getStatus());

        // 3. Dedicated test with NO COMPLETED CAPA left: only 1 active OPEN/IN_PROGRESS CAPA in CAPA_IN_PROGRESS
        DeviationDetailDto singleDev = deviationService.createDeviation(
                validCreateRequest(inProgressBatch.getId(), null, stepOnInProgress.getId(), null)
        );
        deviationService.startInvestigation(singleDev.getId(), new StartInvestigationRequestDto("Investigating step"));
        deviationService.recordRootCause(singleDev.getId(), new RecordRootCauseRequestDto(
                "Equipment", "Encoder slip", "Single batch", null
        ));
        CapaActionResponseDto singleOpen = deviationService.createCapaAction(
                singleDev.getId(),
                new CreateCapaActionRequestDto(CapaType.CORRECTIVE, "Replace encoder", "Replace rotary encoder", "Eng. Rao", today.plusDays(2))
        );
        CapaActionResponseDto cancelledSibling = deviationService.createCapaAction(
                singleDev.getId(),
                new CreateCapaActionRequestDto(CapaType.PREVENTIVE, "Duplicate check", "Duplicate check", "Eng. Rao", today.plusDays(3))
        );
        deviationService.startCapa(singleDev.getId(), new StartCapaRequestDto("Start execution"));
        deviationService.cancelCapaAction(cancelledSibling.getId(), new CancelCapaActionRequestDto("Trimmed scope"));
        long auditBeforeLastCancel = auditLogRepository.count();

        // Only 1 active (OPEN) CAPA remains -> cannot cancel
        InvalidDeviationOperationException blockLastOpen = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.cancelCapaAction(singleOpen.getId(), new CancelCapaActionRequestDto("Would leave zero active CAPAs"))
        );
        assertTrue(blockLastOpen.getMessage().contains("last active CAPA action"));
        assertEquals(CapaStatus.OPEN, capaActionRepository.findById(singleOpen.getId()).orElseThrow().getStatus());
        assertEquals(auditBeforeLastCancel, auditLogRepository.count());

        // Start that last active CAPA -> still cannot cancel while IN_PROGRESS
        deviationService.startCapaAction(singleOpen.getId(), new StartCapaActionRequestDto("Installing encoder"));
        long auditAfterStartLast = auditLogRepository.count();
        InvalidDeviationOperationException blockLastInProgress = assertThrows(
                InvalidDeviationOperationException.class,
                () -> deviationService.cancelCapaAction(singleOpen.getId(), new CancelCapaActionRequestDto("Still zero replacement"))
        );
        assertTrue(blockLastInProgress.getMessage().contains("last active CAPA action"));
        assertEquals(CapaStatus.IN_PROGRESS, capaActionRepository.findById(singleOpen.getId()).orElseThrow().getStatus());
        assertEquals(auditAfterStartLast, auditLogRepository.count());

        // Add replacement -> now 2 active CAPAs -> cancelling the original IN_PROGRESS CAPA succeeds!
        CapaActionResponseDto replacement = deviationService.createCapaAction(
                singleDev.getId(),
                new CreateCapaActionRequestDto(CapaType.CORRECTIVE, "Upgrade to sealed encoder", "Install IP67 sealed encoder", "Eng. Rao", today.plusDays(4))
        );
        assertEquals(CapaStatus.CANCELLED,
                deviationService.cancelCapaAction(singleOpen.getId(), new CancelCapaActionRequestDto("Superseded by IP67 upgrade")).getStatus());

        // Complete valid lifecycle on both deviations (singleDev replacement CAPA + dev c1Completed)
        deviationService.completeCapaAction(replacement.getId(), new CompleteCapaActionRequestDto("IP67 encoder verified."));
        deviationService.submitForVerification(singleDev.getId(), new SubmitVerificationRequestDto("Ready"));
        assertEquals(DeviationStatus.CLOSED,
                deviationService.closeDeviation(singleDev.getId(), new CloseDeviationRequestDto("Closed cleanly")).getStatus());

        deviationService.submitForVerification(dev.getId(), new SubmitVerificationRequestDto("OEM shaft complete"));
        assertEquals(DeviationStatus.CLOSED,
                deviationService.closeDeviation(dev.getId(), new CloseDeviationRequestDto("Closed cleanly")).getStatus());
    }

    @Test
    @DisplayName("14. Audit snapshots preserve full before/after investigation, root-cause, CAPA-plan, reopen, and verification details across max-length fields and truncated reasons")
    void auditSnapshotsPreserveMaxLengthDetailsAndLongReasonsSafely() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        LocalDate today = LocalDate.now(DeviationService.APP_ZONE_ID);

        authenticateAs("A.Nair", "QC_ANALYST");
        String maxDescription = "DESC-" + "D".repeat(2995);
        String maxContainment = "CONT-" + "C".repeat(1995);
        DeviationDetailDto dev = deviationService.createDeviation(new CreateDeviationRequestDto(
                completedBatch.getId(),
                failedCheckOnCompleted.getId(),
                stepOnCompleted.getId(),
                auditOnCompleted.getId(),
                "Max-length audit snapshot integrity test",
                DeviationCategory.QC_OOS_FAILURE,
                DeviationSeverity.CRITICAL,
                DeviationPriority.HIGH,
                maxDescription,
                maxContainment,
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(5)
        ));

        // 1. Investigation at max 3000 chars -> reason truncated safely to 2000 chars while snapshot retains all 3000 chars
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        String initialMaxInvestigation = "INV1-" + "I".repeat(2990) + "-END1";
        assertEquals(3000, initialMaxInvestigation.length());
        deviationService.startInvestigation(
                dev.getId(),
                new StartInvestigationRequestDto(initialMaxInvestigation)
        );

        // 2. Root cause updates investigation summary (3000 chars) + max RCA (3000 chars) + max impact (2000 chars)
        String updatedMaxInvestigation = "INV2-" + "J".repeat(2990) + "-END2";
        String maxRootCauseAnalysis = "RCA-" + "R".repeat(2992) + "-END";
        String maxImpactAssessment = "IMP-" + "M".repeat(1992) + "-END";
        deviationService.recordRootCause(
                dev.getId(),
                new RecordRootCauseRequestDto(
                        "Equipment / Control Loop",
                        maxRootCauseAnalysis,
                        maxImpactAssessment,
                        updatedMaxInvestigation
                )
        );

        // 3. Create CAPA (2000-char actionPlan), start CAPA plan (2000-char capaPlanSummary), start/complete/cancel CAPAs
        String maxActionPlan = "PLAN-" + "P".repeat(1991) + "-END";
        CapaActionResponseDto capa1 = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.CORRECTIVE, "Calibrate control loop", maxActionPlan,
                        "Eng. Kumar", today.plusDays(2)
                )
        );
        CapaActionResponseDto capa2 = deviationService.createCapaAction(
                dev.getId(),
                new CreateCapaActionRequestDto(
                        CapaType.PREVENTIVE, "Redundant check", "Supplemental redundant check",
                        "Eng. Kumar", today.plusDays(4)
                )
        );

        String maxCapaPlanSummary = "CPAN-" + "S".repeat(1991) + "-END";
        deviationService.startCapa(dev.getId(), new StartCapaRequestDto(maxCapaPlanSummary));
        authenticateAs("Eng.Kumar", "PRODUCTION_SUPERVISOR");
        deviationService.startCapaAction(capa1.getId(), new StartCapaActionRequestDto("Work initiated"));

        String maxCompletionEvidence = "EVID-" + "E".repeat(1991) + "-END";
        deviationService.completeCapaAction(
                capa1.getId(),
                new CompleteCapaActionRequestDto(maxCompletionEvidence)
        );
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        String maxCancelReason = "CANC-" + "X".repeat(991) + "-END";
        deviationService.cancelCapaAction(
                capa2.getId(),
                new CancelCapaActionRequestDto(maxCancelReason)
        );

        // 4. Submit verification -> Reopen CAPA (2000-char reason) -> Resubmit -> Close (2000-char verification notes)
        authenticateAs("Eng.Kumar", "PRODUCTION_SUPERVISOR");
        String maxSubmitNotes = "SUBM-" + "V".repeat(1991) + "-END";
        deviationService.submitForVerification(dev.getId(), new SubmitVerificationRequestDto(maxSubmitNotes));

        authenticateAs("QA.Head", "QA_REVIEWER");
        String maxReopenReason = "REOP-" + "O".repeat(1991) + "-END";
        deviationService.reopenCapa(dev.getId(), new ReopenCapaRequestDto(maxReopenReason));

        authenticateAs("Eng.Kumar", "PRODUCTION_SUPERVISOR");
        deviationService.submitForVerification(dev.getId(), new SubmitVerificationRequestDto("Final submission package."));

        authenticateAs("QA.Director", "QA_REVIEWER");
        String maxVerificationNotes = "VERF-" + "F".repeat(1991) + "-END";
        DeviationDetailDto closed = deviationService.closeDeviation(
                dev.getId(),
                new CloseDeviationRequestDto(maxVerificationNotes)
        );

        List<AuditLogResponseDto> logs = closed.getAuditLogs();

        // Check DEVIATION_INVESTIGATION_STARTED: reason safely truncated to 2000 with "...", full 3000-char summary in afterValues
        AuditLogResponseDto invLog = logs.stream()
                .filter(l -> "DEVIATION_INVESTIGATION_STARTED".equals(l.getAction()))
                .findFirst().orElseThrow();
        assertEquals(2000, invLog.getReason().length());
        assertTrue(invLog.getReason().endsWith("..."));
        assertEquals(initialMaxInvestigation.substring(0, 1997) + "...", invLog.getReason());
        JsonNode invBefore = mapper.readTree(invLog.getBeforeValues());
        JsonNode invAfter = mapper.readTree(invLog.getAfterValues());
        assertEquals("OPEN", invBefore.get("status").asText());
        assertTrue(invBefore.get("investigationSummary").isNull());
        assertEquals(maxDescription, invBefore.get("description").asText());
        assertEquals(maxContainment, invBefore.get("immediateContainment").asText());
        assertEquals(initialMaxInvestigation, invAfter.get("investigationSummary").asText());

        // Check DEVIATION_ROOT_CAUSE_RECORDED: preserves old investigationSummary in beforeValues and full updated 3000/2000 values in afterValues
        AuditLogResponseDto rcaLog = logs.stream()
                .filter(l -> "DEVIATION_ROOT_CAUSE_RECORDED".equals(l.getAction()))
                .findFirst().orElseThrow();
        JsonNode rcaBefore = mapper.readTree(rcaLog.getBeforeValues());
        JsonNode rcaAfter = mapper.readTree(rcaLog.getAfterValues());
        assertEquals("UNDER_INVESTIGATION", rcaBefore.get("status").asText());
        assertEquals(initialMaxInvestigation, rcaBefore.get("investigationSummary").asText());
        assertTrue(rcaBefore.get("rootCauseAnalysis").isNull());
        assertEquals("ROOT_CAUSE_IDENTIFIED", rcaAfter.get("status").asText());
        assertEquals(updatedMaxInvestigation, rcaAfter.get("investigationSummary").asText());
        assertEquals(maxRootCauseAnalysis, rcaAfter.get("rootCauseAnalysis").asText());
        assertEquals(maxImpactAssessment, rcaAfter.get("impactAssessment").asText());

        // Check DEVIATION_CAPA_STARTED: preserves full 2000-char capaPlanSummary plus cumulative investigation & RCA details
        AuditLogResponseDto capaStartLog = logs.stream()
                .filter(l -> "DEVIATION_CAPA_STARTED".equals(l.getAction()))
                .findFirst().orElseThrow();
        assertEquals(maxCapaPlanSummary, capaStartLog.getReason());
        JsonNode csBefore = mapper.readTree(capaStartLog.getBeforeValues());
        JsonNode csAfter = mapper.readTree(capaStartLog.getAfterValues());
        assertTrue(csBefore.get("capaPlanSummary").isNull());
        assertEquals(maxCapaPlanSummary, csAfter.get("capaPlanSummary").asText());
        assertEquals(updatedMaxInvestigation, csAfter.get("investigationSummary").asText());
        assertEquals(maxRootCauseAnalysis, csAfter.get("rootCauseAnalysis").asText());

        // Check CAPA_STARTED, CAPA_COMPLETED, CAPA_CANCELLED snapshots
        AuditLogResponseDto cStartLog = logs.stream()
                .filter(l -> "CAPA_STARTED".equals(l.getAction()))
                .findFirst().orElseThrow();
        assertEquals("OPEN", mapper.readTree(cStartLog.getBeforeValues()).get("status").asText());
        assertEquals("IN_PROGRESS", mapper.readTree(cStartLog.getAfterValues()).get("status").asText());

        AuditLogResponseDto cCompLog = logs.stream()
                .filter(l -> "CAPA_COMPLETED".equals(l.getAction()))
                .findFirst().orElseThrow();
        assertEquals(maxCompletionEvidence, cCompLog.getReason());
        JsonNode ccBefore = mapper.readTree(cCompLog.getBeforeValues());
        JsonNode ccAfter = mapper.readTree(cCompLog.getAfterValues());
        assertEquals("IN_PROGRESS", ccBefore.get("status").asText());
        assertTrue(ccBefore.get("completionEvidence").isNull());
        assertEquals("COMPLETED", ccAfter.get("status").asText());
        assertEquals(maxActionPlan, ccAfter.get("actionPlan").asText());
        assertEquals(maxCompletionEvidence, ccAfter.get("completionEvidence").asText());

        AuditLogResponseDto cCancLog = logs.stream()
                .filter(l -> "CAPA_CANCELLED".equals(l.getAction()))
                .findFirst().orElseThrow();
        assertEquals(maxCancelReason, cCancLog.getReason());
        assertEquals("OPEN", mapper.readTree(cCancLog.getBeforeValues()).get("status").asText());
        assertEquals(maxCancelReason, mapper.readTree(cCancLog.getAfterValues()).get("cancellationReason").asText());

        // Check DEVIATION_SUBMITTED_FOR_VERIFICATION and DEVIATION_CAPA_REOPENED preserve full details
        AuditLogResponseDto subLog = logs.stream()
                .filter(l -> "DEVIATION_SUBMITTED_FOR_VERIFICATION".equals(l.getAction()) && maxSubmitNotes.equals(l.getReason()))
                .findFirst().orElseThrow();
        assertEquals("CAPA_IN_PROGRESS", mapper.readTree(subLog.getBeforeValues()).get("status").asText());
        JsonNode subAfter = mapper.readTree(subLog.getAfterValues());
        assertEquals("PENDING_VERIFICATION", subAfter.get("status").asText());
        assertEquals(maxCapaPlanSummary, subAfter.get("capaPlanSummary").asText());

        AuditLogResponseDto reopenLog = logs.stream()
                .filter(l -> "DEVIATION_CAPA_REOPENED".equals(l.getAction()))
                .findFirst().orElseThrow();
        assertEquals(maxReopenReason, reopenLog.getReason());
        assertEquals("PENDING_VERIFICATION", mapper.readTree(reopenLog.getBeforeValues()).get("status").asText());
        JsonNode reopenAfter = mapper.readTree(reopenLog.getAfterValues());
        assertEquals("CAPA_IN_PROGRESS", reopenAfter.get("status").asText());
        assertEquals(updatedMaxInvestigation, reopenAfter.get("investigationSummary").asText());
        assertEquals(maxRootCauseAnalysis, reopenAfter.get("rootCauseAnalysis").asText());
        assertEquals(maxCapaPlanSummary, reopenAfter.get("capaPlanSummary").asText());

        // Check DEVIATION_CLOSED before/after preserve verification details & all substantive history
        AuditLogResponseDto closedLog = logs.stream()
                .filter(l -> "DEVIATION_CLOSED".equals(l.getAction()))
                .findFirst().orElseThrow();
        assertEquals(maxVerificationNotes, closedLog.getReason());
        assertEquals("qa.director", closedLog.getActorName());
        JsonNode closedBefore = mapper.readTree(closedLog.getBeforeValues());
        JsonNode closedAfter = mapper.readTree(closedLog.getAfterValues());
        assertEquals("PENDING_VERIFICATION", closedBefore.get("status").asText());
        assertTrue(closedBefore.get("verifiedBy").isNull());
        assertTrue(closedBefore.get("verificationNotes").isNull());
        assertTrue(closedBefore.get("closedBy").isNull());
        assertEquals("CLOSED", closedAfter.get("status").asText());
        assertEquals("qa.director", closedAfter.get("verifiedBy").asText());
        assertEquals(maxVerificationNotes, closedAfter.get("verificationNotes").asText());
        assertEquals("qa.director", closedAfter.get("closedBy").asText());
        assertEquals(updatedMaxInvestigation, closedAfter.get("investigationSummary").asText());
        assertEquals(maxRootCauseAnalysis, closedAfter.get("rootCauseAnalysis").asText());
        assertEquals(maxImpactAssessment, closedAfter.get("impactAssessment").asText());
        assertEquals(maxCapaPlanSummary, closedAfter.get("capaPlanSummary").asText());
    }
}
