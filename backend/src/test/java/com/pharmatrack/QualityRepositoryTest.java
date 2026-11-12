package com.pharmatrack;

import com.pharmatrack.dto.BatchQualityDossierDto;
import com.pharmatrack.dto.CloseDeviationRequestDto;
import com.pharmatrack.dto.CompleteCapaActionRequestDto;
import com.pharmatrack.dto.CreateCapaActionRequestDto;
import com.pharmatrack.dto.CreateDeviationRequestDto;
import com.pharmatrack.dto.DeviationDetailDto;
import com.pharmatrack.dto.QaDispositionRequestDto;
import com.pharmatrack.dto.RecordRootCauseRequestDto;
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
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Quality Management repositories, backfill queries,
 * and append-only immutability on H2 in-memory database.
 */
@DataJpaTest
class QualityRepositoryTest {

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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username,
                        "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_" + role)
                )
        );
    }

    private ManufacturingOrder createAndPersistOrder(String orderNumber) {
        Product product = entityManager.persistAndFlush(new Product(
                "AMOX-250-" + orderNumber,
                "Amoxicillin Capsules",
                "250 mg",
                "Capsule",
                "Antibiotic",
                ProductStatus.ACTIVE
        ));
        Recipe recipe = entityManager.persistAndFlush(new Recipe(
                "RCP-" + orderNumber,
                product,
                1,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Master Recipe"
        ));
        ManufacturingOrder order = new ManufacturingOrder(
                orderNumber, product, recipe, 1, new BigDecimal("500.0000"), "kg", 2,
                OrderStatus.IN_PROGRESS, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 15), "Test order"
        );
        return entityManager.persistAndFlush(order);
    }

    @Test
    @DisplayName("1. Quality queue query returns only COMPLETED batches ordered by endTime desc")
    void findCompletedBatchesExcludesNonCompleted() {
        ManufacturingOrder order = createAndPersistOrder("MO-Q1");

        Batch olderCompleted = new Batch("BAT-Q1-01", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 9), null);
        olderCompleted.setQaStatus(BatchQualityStatus.PENDING_QA);
        olderCompleted.setActualYield(new BigDecimal("248.0000"));
        olderCompleted.setEndTime(LocalDateTime.of(2026, 11, 9, 10, 0));

        Batch newerCompleted = new Batch("BAT-Q1-02", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        newerCompleted.setQaStatus(BatchQualityStatus.RELEASED);
        newerCompleted.setActualYield(new BigDecimal("249.5000"));
        newerCompleted.setEndTime(LocalDateTime.of(2026, 11, 10, 11, 0));

        Batch inProgress = new Batch("BAT-Q1-03", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.IN_PROGRESS, LocalDate.of(2026, 11, 10), null);
        Batch planned = new Batch("BAT-Q1-04", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.PLANNED, null, null);
        Batch cancelled = new Batch("BAT-Q1-05", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.CANCELLED, null, null);

        entityManager.persistAndFlush(olderCompleted);
        entityManager.persistAndFlush(newerCompleted);
        entityManager.persistAndFlush(inProgress);
        entityManager.persistAndFlush(planned);
        entityManager.persistAndFlush(cancelled);
        entityManager.clear();

        List<Batch> results = batchRepository.findByStatusWithDetailsOrderByEndTimeDesc(BatchStatus.COMPLETED);
        assertEquals(2, results.size());
        assertEquals("BAT-Q1-02", results.get(0).getBatchNumber());
        assertEquals("BAT-Q1-01", results.get(1).getBatchNumber());
        assertNotNull(results.get(0).getOrder().getProduct().getProductName());
        assertNotNull(results.get(0).getOrder().getRecipe().getRecipeCode());
    }

    @Test
    @DisplayName("2. Additive backfill sets PENDING_QA on legacy COMPLETED batches without altering yield or updatedAt")
    void backfillInitializesPendingQaWithoutAlteringTimestampsOrYield() {
        ManufacturingOrder order = createAndPersistOrder("MO-Q2");

        Batch legacyCompleted = new Batch("BAT-Q2-LEGACY", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 8), null);
        legacyCompleted.setActualYield(new BigDecimal("248.5000"));
        legacyCompleted.setStartTime(LocalDateTime.of(2026, 11, 8, 9, 0));
        legacyCompleted.setEndTime(LocalDateTime.of(2026, 11, 8, 12, 0));
        legacyCompleted = entityManager.persistAndFlush(legacyCompleted);

        Batch alreadyReleased = new Batch("BAT-Q2-REL", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 8), null);
        alreadyReleased.setQaStatus(BatchQualityStatus.RELEASED);
        alreadyReleased.setActualYield(new BigDecimal("249.0000"));
        alreadyReleased = entityManager.persistAndFlush(alreadyReleased);

        Batch inProgress = new Batch("BAT-Q2-INPROG", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.IN_PROGRESS, LocalDate.of(2026, 11, 9), null);
        inProgress = entityManager.persistAndFlush(inProgress);

        LocalDateTime originalUpdatedAt = legacyCompleted.getUpdatedAt();
        LocalDateTime originalEndTime = legacyCompleted.getEndTime();
        BigDecimal originalYield = legacyCompleted.getActualYield();

        entityManager.clear();

        int updatedRows = batchRepository.initializePendingQaStatusForCompletedBatches();
        assertEquals(1, updatedRows, "Only COMPLETED batch with NULL qaStatus should be initialized");

        Batch reloadedLegacy = batchRepository.findById(legacyCompleted.getId()).orElseThrow();
        assertEquals(BatchQualityStatus.PENDING_QA, reloadedLegacy.getQaStatus());
        assertEquals(BatchStatus.COMPLETED, reloadedLegacy.getStatus());
        assertEquals(0, originalYield.compareTo(reloadedLegacy.getActualYield()));
        assertEquals(originalEndTime, reloadedLegacy.getEndTime());
        assertEquals(originalUpdatedAt, reloadedLegacy.getUpdatedAt(), "updatedAt must remain untouched by backfill");

        Batch reloadedReleased = batchRepository.findById(alreadyReleased.getId()).orElseThrow();
        assertEquals(BatchQualityStatus.RELEASED, reloadedReleased.getQaStatus());

        Batch reloadedInProgress = batchRepository.findById(inProgress.getId()).orElseThrow();
        assertNull(reloadedInProgress.getQaStatus());
    }

    @Test
    @DisplayName("3. QualityCheckRepository and BatchQaReviewRepository persist and retrieve ordered records")
    void qualityCheckAndQaReviewRepositoriesWork() {
        ManufacturingOrder order = createAndPersistOrder("MO-Q3");
        Batch batch = new Batch("BAT-Q3-01", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        batch.setQaStatus(BatchQualityStatus.PENDING_QA);
        batch = entityManager.persistAndFlush(batch);

        QualityCheck c1 = new QualityCheck(batch, "Assay", "95-105%", "98.5", "%",
                QualityCheckResult.PASS, true, "Analyst 1", LocalDateTime.of(2026, 11, 10, 10, 0), null);
        QualityCheck c2 = new QualityCheck(batch, "Dissolution", ">= 80%", "91.0", "%",
                QualityCheckResult.PASS, true, "Analyst 2", LocalDateTime.of(2026, 11, 10, 10, 30), null);
        qualityCheckRepository.saveAndFlush(c1);
        qualityCheckRepository.saveAndFlush(c2);

        List<QualityCheck> checks = qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(batch.getId());
        assertEquals(2, checks.size());
        assertEquals("Assay", checks.get(0).getTestName());
        assertEquals("Dissolution", checks.get(1).getTestName());
        assertTrue(qualityCheckRepository.findByIdWithBatch(checks.get(0).getId()).isPresent());

        BatchQaReview r1 = new BatchQaReview(batch, BatchQualityStatus.PENDING_QA, BatchQualityStatus.ON_HOLD,
                "QA 1", "Holding for second check", LocalDateTime.of(2026, 11, 10, 11, 0), 1, 1, 0, 0);
        BatchQaReview r2 = new BatchQaReview(batch, BatchQualityStatus.ON_HOLD, BatchQualityStatus.RELEASED,
                "QA 2", "All checks passed", LocalDateTime.of(2026, 11, 10, 12, 0), 2, 2, 0, 0);
        batchQaReviewRepository.saveAndFlush(r1);
        batchQaReviewRepository.saveAndFlush(r2);

        List<BatchQaReview> reviews = batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(batch.getId());
        assertEquals(2, reviews.size());
        assertEquals(BatchQualityStatus.RELEASED, reviews.get(0).getDecisionStatus());
        assertEquals(BatchQualityStatus.ON_HOLD, reviews.get(1).getDecisionStatus());
        assertEquals(2, batchQaReviewRepository.findByBatchIdInOrderByReviewedAtDescIdDesc(List.of(batch.getId())).size());
        assertTrue(batchQaReviewRepository.findById(reviews.get(0).getId()).isPresent());
        assertEquals(2L, batchQaReviewRepository.count());
    }

    @Test
    @DisplayName("4. AuditLogRepository exposes no delete methods and entity blocks remove operations")
    void auditLogRepositoryAndEntityAreAppendOnly() {
        Set<String> methodNames = Arrays.stream(AuditLogRepository.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertTrue(methodNames.stream().noneMatch(name -> {
            String lower = name.toLowerCase();
            return lower.contains("delete") || lower.contains("remove");
        }), "AuditLogRepository must not expose delete/remove methods");

        AuditLog entry = auditLogRepository.save(new AuditLog(
                "QUALITY_CHECK", 10L, "BAT-Q4-01", 10L, "QC_CHECK_CREATED",
                "Analyst 1", LocalDateTime.now(), "Initial check", null, "{\"result\":\"PASS\"}"
        ));
        entityManager.flush();

        assertEquals(1, auditLogRepository.count());
        assertEquals(1, auditLogRepository.findByBatchIdOrderByOccurredAtDescIdDesc(10L).size());
        assertEquals(1, auditLogRepository.findByEntityTypeOrderByOccurredAtDescIdDesc("QUALITY_CHECK").size());

        AuditLog managed = entityManager.find(AuditLog.class, entry.getId());
        try {
            assertThrows(UnsupportedOperationException.class, () -> {
                entityManager.remove(managed);
                entityManager.flush();
            });
        } finally {
            entityManager.clear();
            assertEquals(1, auditLogRepository.count());
            assertTrue(auditLogRepository.findById(entry.getId()).isPresent());
        }
    }

    @Test
    @DisplayName("4b. AuditLog rejects managed JPA entity update on flush and preserves database row")
    void auditLogRejectsManagedUpdateOnFlush() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 11, 10, 9, 30);
        AuditLog entry = auditLogRepository.save(new AuditLog(
                "DEVIATION", 101L, "DEV-2026-0101", 55L, "DEVIATION_REPORTED",
                "Original Actor", occurredAt, "Original audit reason", null, "{\"status\":\"OPEN\"}"
        ));
        entityManager.flush();
        entityManager.clear();

        AuditLog managed = entityManager.find(AuditLog.class, entry.getId());
        assertNotNull(managed);
        managed.setReason("Tampered reason via managed setter");
        managed.setActorName("Tampered Actor");
        managed.setAfterValues("{\"status\":\"CLOSED\"}");

        try {
            assertThrows(UnsupportedOperationException.class, () -> entityManager.flush(),
                    "Modifying a managed AuditLog and flushing must be rejected via @PreUpdate");
        } finally {
            entityManager.clear();
            AuditLog verified = auditLogRepository.findById(entry.getId()).orElseThrow();
            assertEquals("DEVIATION", verified.getEntityType());
            assertEquals(101L, verified.getEntityId());
            assertEquals("DEV-2026-0101", verified.getEntityReference());
            assertEquals(55L, verified.getBatchId());
            assertEquals("DEVIATION_REPORTED", verified.getAction());
            assertEquals("Original Actor", verified.getActorName());
            assertEquals(occurredAt, verified.getOccurredAt());
            assertEquals("Original audit reason", verified.getReason());
            assertNull(verified.getBeforeValues());
            assertEquals("{\"status\":\"OPEN\"}", verified.getAfterValues());
        }
    }

    @Test
    @DisplayName("4c. AuditLog rejects detached merge and save updates on flush and preserves database row")
    void auditLogRejectsMergeUpdateOnFlushAndPreservesDatabaseRow() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 11, 10, 9, 35);
        AuditLog entry = auditLogRepository.save(new AuditLog(
                "DEVIATION", 102L, "DEV-2026-0102", 56L, "DEVIATION_REPORTED",
                "Original Actor", occurredAt, "Original audit reason", null, "{\"status\":\"OPEN\"}"
        ));
        entityManager.flush();
        entityManager.clear();

        AuditLog detachedForMerge = new AuditLog(
                "DEVIATION", 102L, "DEV-2026-0102", 56L, "DEVIATION_REPORTED",
                "Merged Tamper Actor", occurredAt, "Tampered reason via merge", null, "{\"status\":\"TAMPERED\"}"
        );
        detachedForMerge.setId(entry.getId());

        try {
            assertThrows(UnsupportedOperationException.class, () -> {
                entityManager.merge(detachedForMerge);
                entityManager.flush();
            }, "Merging an edited AuditLog and flushing must be rejected");
        } finally {
            entityManager.clear();
        }

        AuditLog afterMerge = auditLogRepository.findById(entry.getId()).orElseThrow();
        assertEquals("Original Actor", afterMerge.getActorName());
        assertEquals("Original audit reason", afterMerge.getReason());
        assertEquals("{\"status\":\"OPEN\"}", afterMerge.getAfterValues());
        entityManager.clear();

        AuditLog detachedForSave = new AuditLog(
                "DEVIATION", 102L, "DEV-2026-0102", 56L, "DEVIATION_REPORTED",
                "Saved Tamper Actor", occurredAt, "Tampered reason via repository save", null, "{\"status\":\"SAVED\"}"
        );
        detachedForSave.setId(entry.getId());

        try {
            assertThrows(UnsupportedOperationException.class, () -> {
                auditLogRepository.save(detachedForSave);
                entityManager.flush();
            }, "Saving an edited detached AuditLog and flushing must be rejected");
        } finally {
            entityManager.clear();
            AuditLog verified = auditLogRepository.findById(entry.getId()).orElseThrow();
            assertEquals("DEVIATION", verified.getEntityType());
            assertEquals(102L, verified.getEntityId());
            assertEquals("DEV-2026-0102", verified.getEntityReference());
            assertEquals(56L, verified.getBatchId());
            assertEquals("DEVIATION_REPORTED", verified.getAction());
            assertEquals("Original Actor", verified.getActorName());
            assertEquals(occurredAt, verified.getOccurredAt());
            assertEquals("Original audit reason", verified.getReason());
            assertNull(verified.getBeforeValues());
            assertEquals("{\"status\":\"OPEN\"}", verified.getAfterValues());
        }
    }

    @Test
    @DisplayName("4d. AuditLog blocks JPA delete and preserves original audit record")
    void auditLogBlocksDeleteAndPreservesOriginalRecord() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 11, 10, 9, 40);
        AuditLog entry = auditLogRepository.save(new AuditLog(
                "DEVIATION", 103L, "DEV-2026-0103", 57L, "DEVIATION_REPORTED",
                "Original Actor", occurredAt, "Original audit reason", null, "{\"status\":\"OPEN\"}"
        ));
        entityManager.flush();
        entityManager.clear();

        AuditLog forDelete = entityManager.find(AuditLog.class, entry.getId());
        try {
            assertThrows(UnsupportedOperationException.class, () -> {
                entityManager.remove(forDelete);
                entityManager.flush();
            }, "Deleting AuditLog via JPA must be rejected");
        } finally {
            entityManager.clear();
            AuditLog verified = auditLogRepository.findById(entry.getId()).orElseThrow();
            assertEquals("Original Actor", verified.getActorName());
            assertEquals("Original audit reason", verified.getReason());
            assertEquals("{\"status\":\"OPEN\"}", verified.getAfterValues());
        }
    }

    @Test
    @DisplayName("5. End-to-end QualityService + JPA persistence blocks RELEASED on failed required QC check with zero disposition or audit rows created")
    void qualityServiceRejectsReleaseOnFailedRequiredCheckWithoutPersistingReviewOrAudit() {
        ManufacturingOrder order = createAndPersistOrder("MO-Q5");
        Batch batch = new Batch("BAT-Q5-01", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        batch.setQaStatus(BatchQualityStatus.PENDING_QA);
        batch.setActualYield(new BigDecimal("248.5000"));
        batch.setEndTime(LocalDateTime.of(2026, 11, 10, 14, 0));
        batch = entityManager.persistAndFlush(batch);

        QualityCheck failedRequired = new QualityCheck(
                batch, "Dissolution Test", "Minimum 80%", "65", "%",
                QualityCheckResult.FAIL, true, "QC Analyst", LocalDateTime.now(), "Below limit"
        );
        qualityCheckRepository.saveAndFlush(failedRequired);

        long auditCountBefore = auditLogRepository.count();
        long reviewCountBefore = batchQaReviewRepository.count();

        AuditTrailService auditTrailService = new AuditTrailService(auditLogRepository);
        QualityService qualityService = new QualityService(
                batchRepository, qualityCheckRepository, batchQaReviewRepository,
                deviationRepository, capaActionRepository, auditTrailService
        );

        BatchQualityDossierDto dossier = qualityService.getBatchQualityDossier(batch.getId());
        assertFalse(dossier.isEligibleForRelease());
        assertTrue(dossier.getReleaseBlockers().stream()
                .anyMatch(msg -> msg.contains("1 required QC check(s) have FAILED")));

        authenticateAs("dr.menon", "QA_REVIEWER");

        final Long batchId = batch.getId();
        InvalidQualityOperationException ex = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        batchId,
                        new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Bypass attempt")
                )
        );
        assertTrue(ex.getMessage().contains("1 required QC check(s) have FAILED"));

        entityManager.flush();
        entityManager.clear();

        Batch reloaded = batchRepository.findById(batchId).orElseThrow();
        assertEquals(BatchQualityStatus.PENDING_QA, reloaded.getQaStatus());
        assertEquals(reviewCountBefore, batchQaReviewRepository.count(),
                "No BatchQaReview row may be created on rejected disposition attempt");
        assertEquals(auditCountBefore, auditLogRepository.count(),
                "No AuditLog row may be created on rejected disposition attempt");
    }

    @Test
    @DisplayName("6. Real JPA QA Release Gate: non-CLOSED deviations block RELEASED, allow ON_HOLD/REJECTED, and closure never auto-releases")
    void realJpaDeviationReleaseGateEnforcedAndClosingDoesNotAutoRelease() {
        ManufacturingOrder order = createAndPersistOrder("MO-Q6");

        Batch batch = new Batch("BAT-Q6-01", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        batch.setQaStatus(BatchQualityStatus.PENDING_QA);
        batch.setActualYield(new BigDecimal("248.5000"));
        batch.setEndTime(LocalDateTime.of(2026, 11, 10, 14, 0));
        batch = entityManager.persistAndFlush(batch);

        Batch rejectedTargetBatch = new Batch("BAT-Q6-02", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        rejectedTargetBatch.setQaStatus(BatchQualityStatus.PENDING_QA);
        rejectedTargetBatch.setActualYield(new BigDecimal("240.0000"));
        rejectedTargetBatch.setEndTime(LocalDateTime.of(2026, 11, 10, 15, 0));
        rejectedTargetBatch = entityManager.persistAndFlush(rejectedTargetBatch);

        QualityCheck passingCheck = new QualityCheck(
                batch, "Assay", "95-105%", "99.4", "%",
                QualityCheckResult.PASS, true, "QC Analyst", LocalDateTime.now().minusMinutes(20), "Passes"
        );
        qualityCheckRepository.saveAndFlush(passingCheck);

        AuditTrailService auditTrailService = new AuditTrailService(auditLogRepository);
        QualityService qualityService = new QualityService(
                batchRepository, qualityCheckRepository, batchQaReviewRepository,
                deviationRepository, capaActionRepository, auditTrailService
        );
        DeviationService deviationService = new DeviationService(
                deviationRepository, capaActionRepository, batchRepository,
                qualityCheckRepository, executionStepRepository, auditLogRepository, auditTrailService
        );

        authenticateAs("dr.menon", "QA_REVIEWER");

        final Long batchId = batch.getId();
        assertTrue(qualityService.getBatchQualityDossier(batchId).isEligibleForRelease());

        // Report deviation -> OPEN
        DeviationDetailDto dev = deviationService.createDeviation(new CreateDeviationRequestDto(
                batchId, null, null, null,
                "Label printer misfeed on packaging line",
                DeviationCategory.EQUIPMENT_MALFUNCTION,
                DeviationSeverity.MAJOR,
                DeviationPriority.HIGH,
                "Observed intermittent label skew during secondary cartoning.",
                "Quarantined affected shippers.",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(5)
        ));

        BatchQualityDossierDto dossierWithOpenDev = qualityService.getBatchQualityDossier(batchId);
        assertFalse(dossierWithOpenDev.isEligibleForRelease());
        assertEquals(1, dossierWithOpenDev.getOpenDeviationsCount());
        assertEquals(1, dossierWithOpenDev.getTotalDeviationsCount());
        assertTrue(dossierWithOpenDev.getReleaseBlockers().stream()
                .anyMatch(msg -> msg.contains("1 unresolved deviation(s) must be CLOSED")));

        long reviewsBefore = batchQaReviewRepository.count();
        long auditsBefore = auditLogRepository.count();

        // Attempt RELEASED while OPEN -> blocked with zero writes
        InvalidQualityOperationException openEx = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        batchId,
                        new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Release attempt")
                )
        );
        assertTrue(openEx.getMessage().contains("1 unresolved deviation(s)"));
        entityManager.flush();
        assertEquals(reviewsBefore, batchQaReviewRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());

        // Transition to UNDER_INVESTIGATION -> attempt RELEASED -> still blocked
        deviationService.startInvestigation(dev.getId(),
                new StartInvestigationRequestDto("Checking encoder tension and label stock."));
        auditsBefore = auditLogRepository.count();

        InvalidQualityOperationException invEx = assertThrows(
                InvalidQualityOperationException.class,
                () -> qualityService.recordQaDisposition(
                        batchId,
                        new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Release while investigating")
                )
        );
        assertTrue(invEx.getMessage().contains("1 unresolved deviation(s)"));
        entityManager.flush();
        assertEquals(reviewsBefore, batchQaReviewRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());

        // ON_HOLD remains permitted despite unresolved deviation
        BatchQualityDossierDto onHoldDossier = qualityService.recordQaDisposition(
                batchId,
                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Holding batch pending CAPA closure")
        );
        assertEquals(BatchQualityStatus.ON_HOLD, onHoldDossier.getQaStatus());
        long reviewsAfterHold = batchQaReviewRepository.count();
        assertEquals(reviewsBefore + 1, reviewsAfterHold);

        // Second batch with unresolved deviation also permits REJECTED
        deviationService.createDeviation(new CreateDeviationRequestDto(
                rejectedTargetBatch.getId(), null, null, null,
                "Severe carton water damage",
                DeviationCategory.MATERIAL_ISSUE,
                DeviationSeverity.CRITICAL,
                DeviationPriority.HIGH,
                "Water ingress detected on pallet.",
                "Segregated pallet.",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(5)
        ));
        BatchQualityDossierDto rejectedDossier = qualityService.recordQaDisposition(
                rejectedTargetBatch.getId(),
                new QaDispositionRequestDto(BatchQualityStatus.REJECTED, "Rejecting water-damaged batch")
        );
        assertEquals(BatchQualityStatus.REJECTED, rejectedDossier.getQaStatus());
        long reviewsAfterBoth = batchQaReviewRepository.count();

        // Advance first deviation through full CAPA closure
        deviationService.recordRootCause(dev.getId(), new RecordRootCauseRequestDto(
                "Equipment / Packaging",
                "Worn label applicator roller", "Limited to BAT-Q6-01 shippers", null
        ));
        LocalDate dueDate = LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(2);
        var capa = deviationService.createCapaAction(dev.getId(), new CreateCapaActionRequestDto(
                CapaType.CORRECTIVE, "Replace roller", "Replace applicator feed roller and inspect cartons",
                "Eng. Patel", dueDate
        ));
        deviationService.startCapa(dev.getId(), new StartCapaRequestDto("Work order opened"));
        deviationService.completeCapaAction(capa.getId(),
                new CompleteCapaActionRequestDto("Roller replaced and 100% carton inspection passed."));
        deviationService.submitForVerification(dev.getId(),
                new SubmitVerificationRequestDto("Ready for QA verification"));
        DeviationDetailDto closedDev = deviationService.closeDeviation(dev.getId(),
                new CloseDeviationRequestDto("Verified roller maintenance log and inspection report."));
        assertEquals("CLOSED", closedDev.getStatus().name());

        entityManager.flush();
        entityManager.clear();

        // Closing deviation MUST NEVER automatically release the batch
        Batch afterDevClosed = batchRepository.findById(batchId).orElseThrow();
        assertEquals(BatchQualityStatus.ON_HOLD, afterDevClosed.getQaStatus(),
                "Closing a deviation must never auto-release the batch");
        assertEquals(BatchStatus.COMPLETED, afterDevClosed.getStatus());
        assertEquals(0, new BigDecimal("248.5000").compareTo(afterDevClosed.getActualYield()));
        assertEquals(reviewsAfterBoth, batchQaReviewRepository.count(),
                "Closing a deviation must not insert an automatic BatchQaReview");

        // Now explicit QA RELEASED disposition succeeds because all QC checks passed and all deviations are CLOSED
        BatchQualityDossierDto dossierReady = qualityService.getBatchQualityDossier(batchId);
        assertTrue(dossierReady.isEligibleForRelease());
        assertEquals(0, dossierReady.getOpenDeviationsCount());

        BatchQualityDossierDto released = qualityService.recordQaDisposition(
                batchId,
                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "All QC passed and DEV closed")
        );
        assertEquals(BatchQualityStatus.RELEASED, released.getQaStatus());
        assertEquals(BatchStatus.COMPLETED, released.getBatchStatus());
        assertEquals(0, new BigDecimal("248.5000").compareTo(released.getActualYield()));
    }

    @Test
    @DisplayName("7. BatchQaReview entity blocks JPA delete operations via @PreRemove and repository exposes no delete methods")
    void batchQaReviewBlocksJpaDeleteOperations() {
        Set<String> methodNames = Arrays.stream(BatchQaReviewRepository.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertTrue(methodNames.stream().noneMatch(name -> {
            String lower = name.toLowerCase();
            return lower.contains("delete") || lower.contains("remove");
        }), "BatchQaReviewRepository must not expose bulk delete or remove methods");

        ManufacturingOrder order = createAndPersistOrder("MO-Q7");
        Batch batch = new Batch("BAT-Q7-01", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        batch.setQaStatus(BatchQualityStatus.ON_HOLD);
        batch = entityManager.persistAndFlush(batch);

        BatchQaReview review = batchQaReviewRepository.saveAndFlush(new BatchQaReview(
                batch, BatchQualityStatus.PENDING_QA, BatchQualityStatus.ON_HOLD,
                "QA Reviewer", "Hold for additional verification", LocalDateTime.now(), 1, 1, 0, 0
        ));
        assertEquals(1, batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(batch.getId()).size());

        BatchQaReview managed = entityManager.find(BatchQaReview.class, review.getId());
        try {
            assertThrows(UnsupportedOperationException.class, () -> {
                entityManager.remove(managed);
                entityManager.flush();
            });
        } finally {
            entityManager.clear();
            assertEquals(1, batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(batch.getId()).size());
            assertTrue(batchQaReviewRepository.findById(review.getId()).isPresent());
        }
    }

    @Test
    @DisplayName("8a. BatchQaReview rejects managed JPA entity update on flush and preserves database row")
    void batchQaReviewRejectsManagedUpdateOnFlush() {
        ManufacturingOrder order = createAndPersistOrder("MO-Q8A");
        Batch batch = new Batch("BAT-Q8A-01", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        batch.setQaStatus(BatchQualityStatus.ON_HOLD);
        batch = entityManager.persistAndFlush(batch);

        LocalDateTime reviewedAt = LocalDateTime.of(2026, 11, 10, 14, 15);
        BatchQaReview review = batchQaReviewRepository.saveAndFlush(new BatchQaReview(
                batch, BatchQualityStatus.PENDING_QA, BatchQualityStatus.ON_HOLD,
                "Dr. Menon", "Original QA rationale for hold", reviewedAt, 2, 1, 1, 0
        ));
        LocalDateTime originalCreatedAt = review.getCreatedAt();
        entityManager.clear();

        BatchQaReview managed = entityManager.find(BatchQaReview.class, review.getId());
        managed.setDecisionStatus(BatchQualityStatus.RELEASED);
        managed.setRationale("Tampered approval rationale");
        managed.setReviewerName("Tampered Reviewer");

        try {
            assertThrows(UnsupportedOperationException.class, () -> entityManager.flush(),
                    "Modifying managed BatchQaReview and flushing must be rejected via @PreUpdate");
        } finally {
            entityManager.clear();
            BatchQaReview verified = batchQaReviewRepository.findById(review.getId()).orElseThrow();
            assertEquals(BatchQualityStatus.PENDING_QA, verified.getPreviousStatus());
            assertEquals(BatchQualityStatus.ON_HOLD, verified.getDecisionStatus());
            assertEquals("Dr. Menon", verified.getReviewerName());
            assertEquals("Original QA rationale for hold", verified.getRationale());
            assertEquals(reviewedAt, verified.getReviewedAt());
            assertEquals(originalCreatedAt, verified.getCreatedAt());
        }
    }

    @Test
    @DisplayName("8b. BatchQaReview rejects detached merge and save updates on flush and preserves database row")
    void batchQaReviewRejectsMergeAndSaveOnFlush() {
        ManufacturingOrder order = createAndPersistOrder("MO-Q8B");
        Batch batch = new Batch("BAT-Q8B-01", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        batch.setQaStatus(BatchQualityStatus.ON_HOLD);
        batch = entityManager.persistAndFlush(batch);

        LocalDateTime reviewedAt = LocalDateTime.of(2026, 11, 10, 14, 20);
        BatchQaReview review = batchQaReviewRepository.saveAndFlush(new BatchQaReview(
                batch, BatchQualityStatus.PENDING_QA, BatchQualityStatus.ON_HOLD,
                "Dr. Menon", "Original QA rationale for hold", reviewedAt, 2, 1, 1, 0
        ));
        LocalDateTime originalCreatedAt = review.getCreatedAt();
        entityManager.clear();

        BatchQaReview detachedForMerge = new BatchQaReview(
                batch, BatchQualityStatus.PENDING_QA, BatchQualityStatus.RELEASED,
                "Tampered Merger", "Merged tampered rationale", reviewedAt, 2, 2, 0, 0
        );
        detachedForMerge.setId(review.getId());
        detachedForMerge.setCreatedAt(originalCreatedAt);

        try {
            assertThrows(UnsupportedOperationException.class, () -> {
                entityManager.merge(detachedForMerge);
                entityManager.flush();
            }, "Merging edited BatchQaReview and flushing must be rejected");
        } finally {
            entityManager.clear();
        }

        BatchQaReview afterMerge = batchQaReviewRepository.findById(review.getId()).orElseThrow();
        assertEquals(BatchQualityStatus.ON_HOLD, afterMerge.getDecisionStatus());
        assertEquals("Dr. Menon", afterMerge.getReviewerName());
        assertEquals("Original QA rationale for hold", afterMerge.getRationale());
        entityManager.clear();

        BatchQaReview detachedForSave = new BatchQaReview(
                batch, BatchQualityStatus.PENDING_QA, BatchQualityStatus.REJECTED,
                "Tampered Saver", "Saved tampered rationale", reviewedAt, 2, 0, 2, 0
        );
        detachedForSave.setId(review.getId());

        try {
            assertThrows(UnsupportedOperationException.class, () -> {
                batchQaReviewRepository.save(detachedForSave);
                entityManager.flush();
            }, "Saving edited detached BatchQaReview (even with unset createdAt) and flushing must be rejected");
        } finally {
            entityManager.clear();
            BatchQaReview verified = batchQaReviewRepository.findById(review.getId()).orElseThrow();
            assertEquals(BatchQualityStatus.PENDING_QA, verified.getPreviousStatus());
            assertEquals(BatchQualityStatus.ON_HOLD, verified.getDecisionStatus());
            assertEquals("Dr. Menon", verified.getReviewerName());
            assertEquals("Original QA rationale for hold", verified.getRationale());
            assertEquals(reviewedAt, verified.getReviewedAt());
            assertEquals(originalCreatedAt, verified.getCreatedAt());
            assertEquals(2L, verified.getTotalChecksCount());
            assertEquals(1L, verified.getPassedChecksCount());
            assertEquals(1L, verified.getFailedChecksCount());
            assertEquals(0L, verified.getPendingChecksCount());
        }
    }

    @Test
    @DisplayName("8c. BatchQaReview rejects JPA delete and repository exposes no delete methods while preserving historical record")
    void batchQaReviewBlocksAllDeleteAttemptsAndPreservesRecord() {
        Set<String> methodNames = Arrays.stream(BatchQaReviewRepository.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertTrue(methodNames.stream().noneMatch(name -> {
            String lower = name.toLowerCase();
            return lower.contains("delete") || lower.contains("remove");
        }), "BatchQaReviewRepository must not expose delete or bulk delete methods");

        ManufacturingOrder order = createAndPersistOrder("MO-Q8C");
        Batch batch = new Batch("BAT-Q8C-01", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        batch.setQaStatus(BatchQualityStatus.ON_HOLD);
        batch = entityManager.persistAndFlush(batch);

        LocalDateTime reviewedAt = LocalDateTime.of(2026, 11, 10, 14, 25);
        BatchQaReview review = batchQaReviewRepository.saveAndFlush(new BatchQaReview(
                batch, BatchQualityStatus.PENDING_QA, BatchQualityStatus.ON_HOLD,
                "Dr. Menon", "Original QA rationale for hold", reviewedAt, 2, 1, 1, 0
        ));
        entityManager.clear();

        BatchQaReview forDelete = entityManager.find(BatchQaReview.class, review.getId());
        try {
            assertThrows(UnsupportedOperationException.class, () -> {
                entityManager.remove(forDelete);
                entityManager.flush();
            });
        } finally {
            entityManager.clear();
            BatchQaReview verified = batchQaReviewRepository.findById(review.getId()).orElseThrow();
            assertEquals(BatchQualityStatus.PENDING_QA, verified.getPreviousStatus());
            assertEquals(BatchQualityStatus.ON_HOLD, verified.getDecisionStatus());
            assertEquals("Dr. Menon", verified.getReviewerName());
            assertEquals("Original QA rationale for hold", verified.getRationale());
            assertEquals(reviewedAt, verified.getReviewedAt());
        }
    }
}

