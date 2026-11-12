package com.pharmatrack;

import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchExecutionStep;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.repository.BatchExecutionStepRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link BatchExecutionStepRepository} using @DataJpaTest
 * and an isolated in-memory H2 database.
 */
@DataJpaTest
class BatchExecutionStepRepositoryTest {

    @Autowired
    private BatchExecutionStepRepository stepRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Product createAndPersistProduct(String productCode, String productName) {
        Product product = new Product(
                productCode,
                productName,
                "250 mg",
                "Capsule",
                "Antibiotic formulation",
                ProductStatus.ACTIVE
        );
        return entityManager.persistAndFlush(product);
    }

    private Recipe createAndPersistRecipeWithSteps(Product product, String recipeCode, Integer version) {
        Recipe recipe = new Recipe(
                recipeCode,
                product,
                version,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Approved manufacturing master recipe"
        );
        recipe.addStep(new RecipeStep(1, "Dispensing", "Dispense API and excipients accurately."));
        recipe.addStep(new RecipeStep(2, "Dry Blending", "Blend dry powders in V-blender at 25 RPM for 20 minutes."));
        recipe.addStep(new RecipeStep(3, "Encapsulation", "Fill hard gelatin capsules to target weight."));
        return entityManager.persistAndFlush(recipe);
    }

    private ManufacturingOrder createAndPersistOrder(String orderNumber, Product product, Recipe recipe) {
        ManufacturingOrder order = new ManufacturingOrder(
                orderNumber,
                product,
                recipe,
                recipe.getVersion(),
                new BigDecimal("500.0000"),
                "kg",
                2,
                OrderStatus.IN_PROGRESS,
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 20),
                "Production order for execution step repository tests"
        );
        return entityManager.persistAndFlush(order);
    }

    private Batch createAndPersistBatch(String batchNumber, ManufacturingOrder order, BatchStatus status) {
        Batch batch = new Batch(
                batchNumber,
                order,
                new BigDecimal("250.0000"),
                "kg",
                status,
                LocalDate.of(2026, 11, 10),
                null
        );
        return entityManager.persistAndFlush(batch);
    }

    private BatchExecutionStep buildExecutionStep(Batch batch, RecipeStep recipeStep, ExecutionStepStatus status) {
        return new BatchExecutionStep(
                batch,
                recipeStep,
                recipeStep.getStepNumber(),
                recipeStep.getOperationName(),
                recipeStep.getInstructions(),
                status
        );
    }

    // -------------------------------------------------------------------------
    // 1. Saving and Retrieving Execution Steps
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. Should save a BatchExecutionStep and persist timestamps, snapshot fields, and execution data")
    void shouldSaveAndRetrieveExecutionStep() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipeWithSteps(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0101", product, recipe);
        Batch batch = createAndPersistBatch("BAT-2026-0101-01", order, BatchStatus.IN_PROGRESS);
        RecipeStep recipeStep = recipe.getSteps().get(0);

        LocalDateTime startedAt = LocalDateTime.of(2026, 11, 10, 8, 0);
        LocalDateTime completedAt = LocalDateTime.of(2026, 11, 10, 8, 25);

        BatchExecutionStep step = buildExecutionStep(batch, recipeStep, ExecutionStepStatus.COMPLETED);
        step.setActualValue("250.0 kg dispensed");
        step.setObservations("Verified against dual balance calibration.");
        step.setExecutedBy("J. Doe");
        step.setStartedAt(startedAt);
        step.setCompletedAt(completedAt);

        BatchExecutionStep saved = stepRepository.saveAndFlush(step);
        Long savedId = saved.getId();

        assertNotNull(savedId, "Generated ID must not be null");
        assertNotNull(saved.getCreatedAt(), "createdAt must be populated");
        assertNotNull(saved.getUpdatedAt(), "updatedAt must be populated");

        entityManager.clear();

        Optional<BatchExecutionStep> retrievedOpt = stepRepository.findById(savedId);
        assertTrue(retrievedOpt.isPresent(), "Execution step should be retrievable by ID");

        BatchExecutionStep retrieved = retrievedOpt.get();
        assertEquals(batch.getId(), retrieved.getBatch().getId());
        assertEquals(recipeStep.getId(), retrieved.getRecipeStep().getId());
        assertEquals(1, retrieved.getStepNumber());
        assertEquals("Dispensing", retrieved.getOperationName());
        assertEquals("Dispense API and excipients accurately.", retrieved.getInstructions());
        assertEquals(ExecutionStepStatus.COMPLETED, retrieved.getStatus());
        assertEquals("250.0 kg dispensed", retrieved.getActualValue());
        assertEquals("Verified against dual balance calibration.", retrieved.getObservations());
        assertEquals("J. Doe", retrieved.getExecutedBy());
        assertEquals(startedAt, retrieved.getStartedAt());
        assertEquals(completedAt, retrieved.getCompletedAt());
    }

    // -------------------------------------------------------------------------
    // 2. Retrieving Steps Ordered by stepNumber Ascending
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2. Should retrieve execution steps for a batch ordered by stepNumber ascending")
    void shouldFindByBatchIdOrderByStepNumberAsc() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipeWithSteps(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0102", product, recipe);
        Batch batch1 = createAndPersistBatch("BAT-2026-0102-01", order, BatchStatus.IN_PROGRESS);
        Batch batch2 = createAndPersistBatch("BAT-2026-0102-02", order, BatchStatus.IN_PROGRESS);

        RecipeStep rStep1 = recipe.getSteps().get(0);
        RecipeStep rStep2 = recipe.getSteps().get(1);
        RecipeStep rStep3 = recipe.getSteps().get(2);

        // Persist out of order (3, 1, 2)
        stepRepository.saveAndFlush(buildExecutionStep(batch1, rStep3, ExecutionStepStatus.PENDING));
        stepRepository.saveAndFlush(buildExecutionStep(batch1, rStep1, ExecutionStepStatus.COMPLETED));
        stepRepository.saveAndFlush(buildExecutionStep(batch1, rStep2, ExecutionStepStatus.IN_PROGRESS));

        // Step belonging to another batch must not leak into batch1 query
        stepRepository.saveAndFlush(buildExecutionStep(batch2, rStep1, ExecutionStepStatus.PENDING));

        entityManager.clear();

        List<BatchExecutionStep> batch1Steps = stepRepository.findByBatchIdOrderByStepNumberAsc(batch1.getId());
        assertEquals(3, batch1Steps.size(), "Should return all 3 steps for batch 1");
        assertEquals(1, batch1Steps.get(0).getStepNumber());
        assertEquals("Dispensing", batch1Steps.get(0).getOperationName());
        assertEquals(2, batch1Steps.get(1).getStepNumber());
        assertEquals("Dry Blending", batch1Steps.get(1).getOperationName());
        assertEquals(3, batch1Steps.get(2).getStepNumber());
        assertEquals("Encapsulation", batch1Steps.get(2).getOperationName());

        List<BatchExecutionStep> missingBatchSteps = stepRepository.findByBatchIdOrderByStepNumberAsc(99999L);
        assertNotNull(missingBatchSteps);
        assertTrue(missingBatchSteps.isEmpty());
    }

    // -------------------------------------------------------------------------
    // 3. Finding a Step by Batch ID and Step Number
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3. Should find a specific execution step by batchId and stepNumber")
    void shouldFindByBatchIdAndStepNumber() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipeWithSteps(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0103", product, recipe);
        Batch batch1 = createAndPersistBatch("BAT-2026-0103-01", order, BatchStatus.IN_PROGRESS);
        Batch batch2 = createAndPersistBatch("BAT-2026-0103-02", order, BatchStatus.IN_PROGRESS);

        stepRepository.saveAndFlush(buildExecutionStep(batch1, recipe.getSteps().get(0), ExecutionStepStatus.COMPLETED));
        stepRepository.saveAndFlush(buildExecutionStep(batch1, recipe.getSteps().get(1), ExecutionStepStatus.IN_PROGRESS));
        stepRepository.saveAndFlush(buildExecutionStep(batch2, recipe.getSteps().get(1), ExecutionStepStatus.PENDING));

        Optional<BatchExecutionStep> found = stepRepository.findByBatchIdAndStepNumber(batch1.getId(), 2);
        assertTrue(found.isPresent());
        assertEquals(batch1.getId(), found.get().getBatch().getId());
        assertEquals(2, found.get().getStepNumber());
        assertEquals("Dry Blending", found.get().getOperationName());
        assertEquals(ExecutionStepStatus.IN_PROGRESS, found.get().getStatus());

        assertTrue(stepRepository.findByBatchIdAndStepNumber(batch1.getId(), 99).isEmpty());
        assertTrue(stepRepository.findByBatchIdAndStepNumber(99999L, 1).isEmpty());
    }

    // -------------------------------------------------------------------------
    // 4. Existence Checks for a Batch
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("4. Should check whether execution steps exist for a batch")
    void shouldCheckExistsByBatchId() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipeWithSteps(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0104", product, recipe);
        Batch batchWithSteps = createAndPersistBatch("BAT-2026-0104-01", order, BatchStatus.IN_PROGRESS);
        Batch batchWithoutSteps = createAndPersistBatch("BAT-2026-0104-02", order, BatchStatus.PLANNED);

        stepRepository.saveAndFlush(buildExecutionStep(batchWithSteps, recipe.getSteps().get(0), ExecutionStepStatus.PENDING));

        assertTrue(stepRepository.existsByBatchId(batchWithSteps.getId()));
        assertFalse(stepRepository.existsByBatchId(batchWithoutSteps.getId()));
        assertFalse(stepRepository.existsByBatchId(99999L));
    }

    // -------------------------------------------------------------------------
    // 5. Existence Checks by Batch ID and Status
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5. Should check whether a batch has an execution step in a specific status")
    void shouldCheckExistsByBatchIdAndStatus() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipeWithSteps(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0105", product, recipe);
        Batch batch1 = createAndPersistBatch("BAT-2026-0105-01", order, BatchStatus.IN_PROGRESS);
        Batch batch2 = createAndPersistBatch("BAT-2026-0105-02", order, BatchStatus.IN_PROGRESS);

        stepRepository.saveAndFlush(buildExecutionStep(batch1, recipe.getSteps().get(0), ExecutionStepStatus.COMPLETED));
        stepRepository.saveAndFlush(buildExecutionStep(batch1, recipe.getSteps().get(1), ExecutionStepStatus.IN_PROGRESS));
        // Batch 2 has PENDING step to ensure batch scoping works properly
        stepRepository.saveAndFlush(buildExecutionStep(batch2, recipe.getSteps().get(0), ExecutionStepStatus.PENDING));

        assertTrue(stepRepository.existsByBatchIdAndStatus(batch1.getId(), ExecutionStepStatus.COMPLETED));
        assertTrue(stepRepository.existsByBatchIdAndStatus(batch1.getId(), ExecutionStepStatus.IN_PROGRESS));
        assertFalse(stepRepository.existsByBatchIdAndStatus(batch1.getId(), ExecutionStepStatus.PENDING));
        assertFalse(stepRepository.existsByBatchIdAndStatus(99999L, ExecutionStepStatus.PENDING));
    }

    // -------------------------------------------------------------------------
    // 6. Counts by Status and Excluded Status
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. Should count steps by status and count steps where status is not a supplied status")
    void shouldCountByBatchIdAndStatusAndStatusNot() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipeWithSteps(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0106", product, recipe);
        Batch batch1 = createAndPersistBatch("BAT-2026-0106-01", order, BatchStatus.IN_PROGRESS);
        Batch batch2 = createAndPersistBatch("BAT-2026-0106-02", order, BatchStatus.IN_PROGRESS);

        BatchExecutionStep b1Step1 = stepRepository.saveAndFlush(
                buildExecutionStep(batch1, recipe.getSteps().get(0), ExecutionStepStatus.COMPLETED));
        BatchExecutionStep b1Step2 = stepRepository.saveAndFlush(
                buildExecutionStep(batch1, recipe.getSteps().get(1), ExecutionStepStatus.IN_PROGRESS));
        BatchExecutionStep b1Step3 = stepRepository.saveAndFlush(
                buildExecutionStep(batch1, recipe.getSteps().get(2), ExecutionStepStatus.PENDING));

        // Add step on batch 2 to confirm batch isolation
        stepRepository.saveAndFlush(buildExecutionStep(batch2, recipe.getSteps().get(0), ExecutionStepStatus.PENDING));

        assertEquals(1L, stepRepository.countByBatchIdAndStatus(batch1.getId(), ExecutionStepStatus.COMPLETED));
        assertEquals(1L, stepRepository.countByBatchIdAndStatus(batch1.getId(), ExecutionStepStatus.IN_PROGRESS));
        assertEquals(1L, stepRepository.countByBatchIdAndStatus(batch1.getId(), ExecutionStepStatus.PENDING));
        assertEquals(2L, stepRepository.countByBatchIdAndStatusNot(batch1.getId(), ExecutionStepStatus.COMPLETED));
        assertEquals(2L, stepRepository.countByBatchIdAndStatusNot(batch1.getId(), ExecutionStepStatus.PENDING));

        // Complete remaining steps in batch 1 and re-verify counts
        b1Step2.setStatus(ExecutionStepStatus.COMPLETED);
        b1Step3.setStatus(ExecutionStepStatus.COMPLETED);
        stepRepository.saveAndFlush(b1Step2);
        stepRepository.saveAndFlush(b1Step3);

        assertEquals(3L, stepRepository.countByBatchIdAndStatus(batch1.getId(), ExecutionStepStatus.COMPLETED));
        assertEquals(0L, stepRepository.countByBatchIdAndStatus(batch1.getId(), ExecutionStepStatus.IN_PROGRESS));
        assertEquals(0L, stepRepository.countByBatchIdAndStatus(batch1.getId(), ExecutionStepStatus.PENDING));
        assertEquals(0L, stepRepository.countByBatchIdAndStatusNot(batch1.getId(), ExecutionStepStatus.COMPLETED));
        assertEquals(0L, stepRepository.countByBatchIdAndStatusNot(99999L, ExecutionStepStatus.COMPLETED));
        assertNotNull(b1Step1.getId());
    }

    // -------------------------------------------------------------------------
    // 7. Unique Constraint Enforcement on (batch_id, step_number)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("7. Should reject duplicate (batch_id, step_number) with DataIntegrityViolationException")
    void shouldRejectDuplicateBatchAndStepNumber() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipeWithSteps(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0107", product, recipe);
        Batch batch1 = createAndPersistBatch("BAT-2026-0107-01", order, BatchStatus.IN_PROGRESS);
        Batch batch2 = createAndPersistBatch("BAT-2026-0107-02", order, BatchStatus.IN_PROGRESS);

        RecipeStep rStep1 = recipe.getSteps().get(0);
        RecipeStep rStep2 = recipe.getSteps().get(1);

        // First step #1 for batch 1 succeeds
        stepRepository.saveAndFlush(new BatchExecutionStep(
                batch1, rStep1, 1, "Dispensing", "Dispense materials."));

        // Same stepNumber = 1 on a different batch (batch 2) must succeed
        BatchExecutionStep otherBatchStep = stepRepository.saveAndFlush(new BatchExecutionStep(
                batch2, rStep1, 1, "Dispensing", "Dispense materials."));
        assertNotNull(otherBatchStep.getId());

        // Duplicate (batch1, stepNumber=1) must fail unique constraint uk_batch_execution_step
        BatchExecutionStep duplicateStep = new BatchExecutionStep(
                batch1, rStep2, 1, "Duplicate Step One", "Duplicate step number for batch 1.");

        assertThrows(DataIntegrityViolationException.class, () -> {
            stepRepository.saveAndFlush(duplicateStep);
        }, "Composite unique constraint 'uk_batch_execution_step' must reject duplicate (batch_id, step_number)");
    }
}

