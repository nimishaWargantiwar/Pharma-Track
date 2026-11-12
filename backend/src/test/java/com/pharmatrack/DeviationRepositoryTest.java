package com.pharmatrack;

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
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchExecutionStepRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.CapaActionRepository;
import com.pharmatrack.repository.DeviationRepository;
import com.pharmatrack.repository.QualityCheckRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JPA integration tests for {@link DeviationRepository} and {@link CapaActionRepository}
 * verifying additive persistence, foreign-key relationships, unique constraints,
 * open-deviation queries, and non-mutation of historical batch/QC/execution/audit records.
 */
@DataJpaTest
class DeviationRepositoryTest {

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

    private record TestFixture(Batch batch, QualityCheck failedCheck, BatchExecutionStep step, AuditLog auditLog) {
    }

    private TestFixture persistBatchFixture(String suffix) {
        Product product = entityManager.persistAndFlush(new Product(
                "PROD-" + suffix, "Test Product " + suffix, "500 mg", "Tablet", "Analgesic", ProductStatus.ACTIVE
        ));
        Recipe recipe = new Recipe(
                "RCP-" + suffix, product, 1, new BigDecimal("100.0000"), "kg", RecipeStatus.APPROVED, "Master"
        );
        RecipeStep recipeStep = new RecipeStep(recipe, 1, "Granulation", "Mix for 15 min at 120 RPM");
        recipe.addStep(recipeStep);
        recipe = entityManager.persistAndFlush(recipe);

        ManufacturingOrder order = entityManager.persistAndFlush(new ManufacturingOrder(
                "MO-" + suffix, product, recipe, 1, new BigDecimal("100.0000"), "kg", 1,
                OrderStatus.COMPLETED, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), "Order " + suffix
        ));

        Batch batch = new Batch(
                "BAT-" + suffix, order, new BigDecimal("100.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null
        );
        batch.setQaStatus(BatchQualityStatus.PENDING_QA);
        batch.setActualYield(new BigDecimal("98.5000"));
        batch.setEndTime(LocalDateTime.of(2026, 11, 10, 12, 0));
        batch = entityManager.persistAndFlush(batch);

        BatchExecutionStep step = new BatchExecutionStep(
                batch, recipe.getSteps().get(0), 1, "Granulation", "Mix for 15 min", ExecutionStepStatus.COMPLETED
        );
        step.setActualValue("122 RPM");
        step.setExecutedBy("Operator 1");
        step = entityManager.persistAndFlush(step);

        QualityCheck failedCheck = entityManager.persistAndFlush(new QualityCheck(
                batch, "Dissolution Test", "Minimum 80%", "65", "%",
                QualityCheckResult.FAIL, true, "A. Nair", LocalDateTime.of(2026, 11, 10, 13, 0), "Failed dissolution"
        ));

        AuditLog audit = auditLogRepository.save(new AuditLog(
                "QUALITY_CHECK", failedCheck.getId(), batch.getBatchNumber(), batch.getId(),
                "QC_CHECK_CREATED", "A. Nair", LocalDateTime.of(2026, 11, 10, 13, 1),
                "Recorded QC check", null, "{\"result\":\"FAIL\"}"
        ));
        entityManager.flush();
        entityManager.clear();

        return new TestFixture(batch, failedCheck, step, audit);
    }

    @Test
    @DisplayName("1. Persist Deviation linked to Batch, QualityCheck, BatchExecutionStep, and AuditLog and query eagerly")
    void persistAndQueryDeviationWithLinks() {
        TestFixture f = persistBatchFixture("D1");

        Deviation dev = new Deviation(
                "DEV-2026-0001",
                f.batch(),
                f.failedCheck(),
                f.step(),
                f.auditLog().getId(),
                "Dissolution test out-of-specification",
                DeviationCategory.QC_OOS_FAILURE,
                DeviationSeverity.CRITICAL,
                DeviationPriority.HIGH,
                DeviationStatus.OPEN,
                "Measured 65% against 80% minimum acceptance limit.",
                "Segregate batch cartons pending QA review.",
                "Dr. Menon",
                LocalDateTime.of(2026, 11, 10, 13, 5),
                LocalDateTime.of(2026, 11, 10, 13, 10)
        );

        Deviation saved = deviationRepository.saveAndFlush(dev);
        assertNotNull(saved.getId());
        entityManager.clear();

        Deviation loaded = deviationRepository.findByIdWithDetails(saved.getId()).orElseThrow();
        assertEquals("DEV-2026-0001", loaded.getDeviationNumber());
        assertEquals("BAT-D1", loaded.getBatch().getBatchNumber());
        assertEquals("MO-D1", loaded.getBatch().getOrder().getOrderNumber());
        assertEquals("Test Product D1", loaded.getBatch().getOrder().getProduct().getProductName());
        assertEquals("Dissolution Test", loaded.getQualityCheck().getTestName());
        assertEquals("Granulation", loaded.getExecutionStep().getOperationName());
        assertEquals(f.auditLog().getId(), loaded.getSourceAuditLogId());

        List<Deviation> all = deviationRepository.findAllWithDetailsOrderByReportedAtDesc();
        assertEquals(1, all.size());
        assertEquals(1, deviationRepository.findByBatchIdWithDetailsOrderByReportedAtDesc(f.batch().getId()).size());
    }

    @Test
    @DisplayName("2. Persist and query CapaAction records ordered under parent Deviation")
    void persistAndQueryCapaActions() {
        TestFixture f = persistBatchFixture("D2");
        Deviation dev = deviationRepository.saveAndFlush(new Deviation(
                "DEV-2026-0002", f.batch(), f.failedCheck(), null, null,
                "Tablet hardness deviation", DeviationCategory.PROCESS_PARAMETER,
                DeviationSeverity.MAJOR, DeviationPriority.MEDIUM, DeviationStatus.ROOT_CAUSE_IDENTIFIED,
                "Hardness variability observed.", null, "A. Nair",
                LocalDateTime.of(2026, 11, 10, 13, 0), LocalDateTime.of(2026, 11, 10, 13, 5)
        ));

        CapaAction c1 = new CapaAction(
                "CAPA-2026-0001", dev, CapaType.CORRECTIVE,
                "Recalibrate compression press",
                "Perform turret calibration check and log reference weights.",
                "Eng. Kumar", LocalDate.of(2026, 11, 18), CapaStatus.COMPLETED, "Dr. Menon"
        );
        c1.setCompletedBy("Eng. Kumar");
        c1.setCompletedAt(LocalDateTime.of(2026, 11, 12, 10, 0));
        c1.setCompletionEvidence("CAL-2026-112 signed certificate attached.");

        CapaAction c2 = new CapaAction(
                "CAPA-2026-0002", dev, CapaType.PREVENTIVE,
                "Update pre-run inspection checklist",
                "Add turret torque check to SOP-CMP-02.",
                "QA Spec", LocalDate.of(2026, 11, 25), CapaStatus.OPEN, "Dr. Menon"
        );

        capaActionRepository.saveAndFlush(c1);
        capaActionRepository.saveAndFlush(c2);
        entityManager.clear();

        List<CapaAction> ordered = capaActionRepository.findByDeviationIdOrderByIdAsc(dev.getId());
        assertEquals(2, ordered.size());
        assertEquals("CAPA-2026-0001", ordered.get(0).getCapaNumber());
        assertEquals("CAPA-2026-0002", ordered.get(1).getCapaNumber());

        assertEquals(2, capaActionRepository.countByDeviationId(dev.getId()));
        assertEquals(1, capaActionRepository.countByDeviationIdAndStatus(dev.getId(), CapaStatus.COMPLETED));
        assertEquals(1, capaActionRepository.countByDeviationIdAndStatusIn(dev.getId(), List.of(CapaStatus.OPEN, CapaStatus.IN_PROGRESS)));
        assertTrue(capaActionRepository.findByIdWithDeviationAndBatch(ordered.get(0).getId()).isPresent());
        assertEquals(2, capaActionRepository.findByDeviationIdInOrderByIdAsc(List.of(dev.getId())).size());
    }

    @Test
    @DisplayName("3. Unique constraints reject duplicate deviationNumber and capaNumber")
    void uniqueConstraintsRejectDuplicates() {
        TestFixture f = persistBatchFixture("D3");
        Deviation d1 = deviationRepository.saveAndFlush(new Deviation(
                "DEV-2026-0010", f.batch(), null, null, null,
                "First deviation", DeviationCategory.OTHER, DeviationSeverity.MINOR,
                DeviationPriority.LOW, DeviationStatus.OPEN, "Initial description", null,
                "Reporter", LocalDateTime.now(), LocalDateTime.now()
        ));

        Deviation dupDev = new Deviation(
                "DEV-2026-0010", f.batch(), null, null, null,
                "Duplicate deviation", DeviationCategory.OTHER, DeviationSeverity.MINOR,
                DeviationPriority.LOW, DeviationStatus.OPEN, "Dup description", null,
                "Reporter", LocalDateTime.now(), LocalDateTime.now()
        );
        assertThrows(DataIntegrityViolationException.class, () -> deviationRepository.saveAndFlush(dupDev));

        entityManager.clear();
        Deviation reloadedDev = deviationRepository.findByDeviationNumber("DEV-2026-0010").orElseThrow();
        capaActionRepository.saveAndFlush(new CapaAction(
                "CAPA-2026-0010", reloadedDev, CapaType.CORRECTIVE, "First CAPA", "Plan A",
                "Owner A", LocalDate.of(2026, 11, 20), CapaStatus.OPEN, "Dr. Menon"
        ));

        CapaAction dupCapa = new CapaAction(
                "CAPA-2026-0010", reloadedDev, CapaType.PREVENTIVE, "Dup CAPA", "Plan B",
                "Owner B", LocalDate.of(2026, 11, 21), CapaStatus.OPEN, "Dr. Menon"
        );
        assertThrows(DataIntegrityViolationException.class, () -> capaActionRepository.saveAndFlush(dupCapa));
    }

    @Test
    @DisplayName("4. Open-deviation batch queries accurately distinguish non-CLOSED vs CLOSED deviations")
    void openDeviationQueriesWorkForReleaseGateLookups() {
        TestFixture f = persistBatchFixture("D4");
        Long batchId = f.batch().getId();

        Deviation closedDev = new Deviation(
                "DEV-2026-0020", f.batch(), null, null, null,
                "Resolved minor documentation issue", DeviationCategory.DOCUMENTATION_ERROR,
                DeviationSeverity.MINOR, DeviationPriority.LOW, DeviationStatus.CLOSED,
                "Missing signature corrected via audited note.", null,
                "Operator", LocalDateTime.now().minusDays(1), LocalDateTime.now().minusDays(1)
        );
        closedDev.setClosedBy("QA Lead");
        closedDev.setClosedAt(LocalDateTime.now());
        deviationRepository.saveAndFlush(closedDev);

        assertFalse(deviationRepository.existsByBatchIdAndStatusNot(batchId, DeviationStatus.CLOSED));
        assertEquals(0, deviationRepository.countByBatchIdAndStatusNot(batchId, DeviationStatus.CLOSED));

        Deviation openDev = new Deviation(
                "DEV-2026-0021", f.batch(), f.failedCheck(), null, null,
                "Open OOS investigation", DeviationCategory.QC_OOS_FAILURE,
                DeviationSeverity.CRITICAL, DeviationPriority.HIGH, DeviationStatus.UNDER_INVESTIGATION,
                "Dissolution failure under active root-cause analysis.", null,
                "A. Nair", LocalDateTime.now(), LocalDateTime.now()
        );
        deviationRepository.saveAndFlush(openDev);

        assertTrue(deviationRepository.existsByBatchIdAndStatusNot(batchId, DeviationStatus.CLOSED));
        assertEquals(1, deviationRepository.countByBatchIdAndStatusNot(batchId, DeviationStatus.CLOSED));
        assertEquals(2, deviationRepository.countByBatchId(batchId));
    }

    @Test
    @DisplayName("5. Persisting Deviations and CAPAs never modifies existing Batch, QC, Execution Step, or AuditLog rows")
    void persistingDeviationDoesNotModifyExistingHistory() {
        TestFixture f = persistBatchFixture("D5");

        LocalDateTime originalBatchUpdatedAt = f.batch().getUpdatedAt();
        BatchQualityStatus originalQaStatus = f.batch().getQaStatus();
        BigDecimal originalYield = f.batch().getActualYield();
        QualityCheckResult originalQcResult = f.failedCheck().getResult();
        String originalMeasured = f.failedCheck().getMeasuredValue();
        long auditCountBefore = auditLogRepository.count();

        Deviation dev = new Deviation(
                "DEV-2026-0030", f.batch(), f.failedCheck(), f.step(), f.auditLog().getId(),
                "OOS investigation", DeviationCategory.QC_OOS_FAILURE,
                DeviationSeverity.MAJOR, DeviationPriority.HIGH, DeviationStatus.OPEN,
                "Investigating failed dissolution check.", null,
                "QC Lead", LocalDateTime.now(), LocalDateTime.now()
        );
        dev.addCapaAction(new CapaAction(
                "CAPA-2026-0030", dev, CapaType.CORRECTIVE, "Inspect blend time",
                "Verify blender RPM chart.", "Ops Lead", LocalDate.of(2026, 11, 25),
                CapaStatus.OPEN, "QC Lead"
        ));
        deviationRepository.saveAndFlush(dev);
        entityManager.clear();

        Batch reloadedBatch = batchRepository.findById(f.batch().getId()).orElseThrow();
        QualityCheck reloadedCheck = qualityCheckRepository.findById(f.failedCheck().getId()).orElseThrow();
        BatchExecutionStep reloadedStep = executionStepRepository.findById(f.step().getId()).orElseThrow();

        assertEquals(originalQaStatus, reloadedBatch.getQaStatus());
        assertEquals(BatchStatus.COMPLETED, reloadedBatch.getStatus());
        assertEquals(0, originalYield.compareTo(reloadedBatch.getActualYield()));
        assertEquals(originalBatchUpdatedAt, reloadedBatch.getUpdatedAt());
        assertEquals(originalQcResult, reloadedCheck.getResult());
        assertEquals(originalMeasured, reloadedCheck.getMeasuredValue());
        assertEquals(ExecutionStepStatus.COMPLETED, reloadedStep.getStatus());
        assertEquals(auditCountBefore, auditLogRepository.count());
    }
}

