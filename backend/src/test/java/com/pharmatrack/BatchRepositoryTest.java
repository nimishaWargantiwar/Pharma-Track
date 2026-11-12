package com.pharmatrack;

import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.repository.BatchRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link BatchRepository} using @DataJpaTest
 * and an isolated in-memory H2 database.
 */
@DataJpaTest
class BatchRepositoryTest {

    @Autowired
    private BatchRepository batchRepository;

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

    private Recipe createAndPersistRecipe(Product product, String recipeCode, Integer version) {
        Recipe recipe = new Recipe(
                recipeCode,
                product,
                version,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Approved manufacturing master recipe"
        );
        return entityManager.persistAndFlush(recipe);
    }

    private ManufacturingOrder createAndPersistOrder(String orderNumber, Product product, Recipe recipe, OrderStatus status) {
        ManufacturingOrder order = new ManufacturingOrder(
                orderNumber,
                product,
                recipe,
                recipe.getVersion(),
                new BigDecimal("500.0000"),
                "kg",
                2,
                status,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 5),
                "Production order for batch testing"
        );
        return entityManager.persistAndFlush(order);
    }

    private Batch createBatch(String batchNumber, ManufacturingOrder order, BatchStatus status) {
        return new Batch(
                batchNumber,
                order,
                new BigDecimal("250.0000"),
                "kg",
                status,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2028, 3, 1)
        );
    }

    // -------------------------------------------------------------------------
    // 1. Basic Persistence & Auditing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should save a batch and generate ID and audit timestamps")
    void shouldSaveAndRetrieveBatch() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0010", product, recipe, OrderStatus.RELEASED);

        Batch batch = createBatch("BAT-2026-0010", order, BatchStatus.PLANNED);
        batch.setStartTime(LocalDateTime.of(2026, 3, 1, 8, 30));
        batch.setActualYield(new BigDecimal("248.5000"));

        Batch saved = batchRepository.saveAndFlush(batch);

        assertNotNull(saved.getId(), "Generated ID must not be null");
        assertNotNull(saved.getCreatedAt(), "CreatedAt timestamp must be populated");
        assertNotNull(saved.getUpdatedAt(), "UpdatedAt timestamp must be populated");
        assertEquals("BAT-2026-0010", saved.getBatchNumber());
        assertEquals(BatchStatus.PLANNED, saved.getStatus());
        assertEquals(new BigDecimal("250.0000"), saved.getBatchSize());
        assertEquals("kg", saved.getBatchUnit());
        assertEquals(new BigDecimal("248.5000"), saved.getActualYield());
        assertEquals(order.getId(), saved.getOrder().getId());

        entityManager.clear();

        Optional<Batch> retrieved = batchRepository.findById(saved.getId());
        assertTrue(retrieved.isPresent(), "Batch should be retrievable by ID");
        assertEquals("BAT-2026-0010", retrieved.get().getBatchNumber());
        assertEquals("MO-2026-0010", retrieved.get().getOrder().getOrderNumber());
    }

    // -------------------------------------------------------------------------
    // 2. Find by Batch Number & Existence Checks
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should find batch by batchNumber when it exists")
    void shouldFindBatchByBatchNumberWhenExists() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0020", product, recipe, OrderStatus.RELEASED);

        Batch batch = createBatch("BAT-2026-0020", order, BatchStatus.IN_PROGRESS);
        entityManager.persistAndFlush(batch);

        Optional<Batch> found = batchRepository.findByBatchNumber("BAT-2026-0020");

        assertTrue(found.isPresent(), "Batch should be found by batch number");
        assertEquals("BAT-2026-0020", found.get().getBatchNumber());
        assertEquals(BatchStatus.IN_PROGRESS, found.get().getStatus());
    }

    @Test
    @DisplayName("Should return empty Optional when batchNumber does not exist")
    void shouldReturnEmptyOptionalWhenBatchNumberNotFound() {
        Optional<Batch> found = batchRepository.findByBatchNumber("BAT-NONEXISTENT");

        assertTrue(found.isEmpty(), "Optional must be empty for non-existent batch number");
    }

    @Test
    @DisplayName("Should return true when checking existsByBatchNumber for an existing batch")
    void shouldReturnTrueWhenBatchNumberExists() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0030", product, recipe, OrderStatus.RELEASED);

        Batch batch = createBatch("BAT-2026-0030", order, BatchStatus.PLANNED);
        entityManager.persistAndFlush(batch);

        boolean exists = batchRepository.existsByBatchNumber("BAT-2026-0030");

        assertTrue(exists, "existsByBatchNumber must return true for an existing batch");
    }

    @Test
    @DisplayName("Should return false when checking existsByBatchNumber for a non-existent batch")
    void shouldReturnFalseWhenBatchNumberDoesNotExist() {
        boolean exists = batchRepository.existsByBatchNumber("BAT-UNKNOWN-999");

        assertFalse(exists, "existsByBatchNumber must return false for a non-existent batch");
    }

    // -------------------------------------------------------------------------
    // 3. Queries by Order ID
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should find all batches belonging to a specific orderId")
    void shouldFindBatchesByOrderId() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order1 = createAndPersistOrder("MO-2026-0041", product, recipe, OrderStatus.RELEASED);
        ManufacturingOrder order2 = createAndPersistOrder("MO-2026-0042", product, recipe, OrderStatus.RELEASED);

        entityManager.persistAndFlush(createBatch("BAT-ORD1-A", order1, BatchStatus.PLANNED));
        entityManager.persistAndFlush(createBatch("BAT-ORD1-B", order1, BatchStatus.IN_PROGRESS));
        entityManager.persistAndFlush(createBatch("BAT-ORD2-A", order2, BatchStatus.PLANNED));

        List<Batch> order1Batches = batchRepository.findByOrderId(order1.getId());
        List<Batch> order2Batches = batchRepository.findByOrderId(order2.getId());

        assertEquals(2, order1Batches.size(), "Should find 2 batches for order 1");
        assertEquals(1, order2Batches.size(), "Should find 1 batch for order 2");
        assertEquals("BAT-ORD2-A", order2Batches.get(0).getBatchNumber());
    }

    @Test
    @DisplayName("Should return empty list when no batches match the given orderId")
    void shouldReturnEmptyListWhenNoBatchesMatchOrderId() {
        List<Batch> batches = batchRepository.findByOrderId(9999L);

        assertNotNull(batches, "Result list must not be null");
        assertTrue(batches.isEmpty(), "Result list must be empty for non-existent orderId");
    }

    // -------------------------------------------------------------------------
    // 4. Queries by Status
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should find batches by status")
    void shouldFindBatchesByStatus() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0050", product, recipe, OrderStatus.RELEASED);

        entityManager.persistAndFlush(createBatch("BAT-STAT-1", order, BatchStatus.PLANNED));
        entityManager.persistAndFlush(createBatch("BAT-STAT-2", order, BatchStatus.IN_PROGRESS));
        entityManager.persistAndFlush(createBatch("BAT-STAT-3", order, BatchStatus.COMPLETED));
        entityManager.persistAndFlush(createBatch("BAT-STAT-4", order, BatchStatus.IN_PROGRESS));

        List<Batch> inProgressBatches = batchRepository.findByStatus(BatchStatus.IN_PROGRESS);
        List<Batch> completedBatches = batchRepository.findByStatus(BatchStatus.COMPLETED);

        assertEquals(2, inProgressBatches.size(), "Should find 2 batches with IN_PROGRESS status");
        assertEquals(1, completedBatches.size(), "Should find 1 batch with COMPLETED status");
        assertEquals("BAT-STAT-3", completedBatches.get(0).getBatchNumber());
    }

    @Test
    @DisplayName("Should return empty list when no batches match the given status")
    void shouldReturnEmptyListWhenNoBatchesMatchStatus() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0051", product, recipe, OrderStatus.RELEASED);
        entityManager.persistAndFlush(createBatch("BAT-STAT-5", order, BatchStatus.PLANNED));

        List<Batch> cancelledBatches = batchRepository.findByStatus(BatchStatus.CANCELLED);

        assertNotNull(cancelledBatches, "Result list must not be null");
        assertTrue(cancelledBatches.isEmpty(), "Result list must be empty when no batches match status");
    }

    // -------------------------------------------------------------------------
    // 5. Queries by Order ID and Status
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should find batches by orderId and status")
    void shouldFindBatchesByOrderIdAndStatus() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order1 = createAndPersistOrder("MO-2026-0061", product, recipe, OrderStatus.IN_PROGRESS);
        ManufacturingOrder order2 = createAndPersistOrder("MO-2026-0062", product, recipe, OrderStatus.IN_PROGRESS);

        entityManager.persistAndFlush(createBatch("BAT-OAS-1", order1, BatchStatus.PLANNED));
        entityManager.persistAndFlush(createBatch("BAT-OAS-2", order1, BatchStatus.IN_PROGRESS));
        entityManager.persistAndFlush(createBatch("BAT-OAS-3", order2, BatchStatus.IN_PROGRESS));

        List<Batch> order1InProgress = batchRepository.findByOrderIdAndStatus(order1.getId(), BatchStatus.IN_PROGRESS);
        List<Batch> order1Planned = batchRepository.findByOrderIdAndStatus(order1.getId(), BatchStatus.PLANNED);

        assertEquals(1, order1InProgress.size(), "Should find 1 IN_PROGRESS batch for order 1");
        assertEquals("BAT-OAS-2", order1InProgress.get(0).getBatchNumber());
        assertEquals(1, order1Planned.size(), "Should find 1 PLANNED batch for order 1");
        assertEquals("BAT-OAS-1", order1Planned.get(0).getBatchNumber());
    }

    @Test
    @DisplayName("Should return empty list when no batches match orderId and status")
    void shouldReturnEmptyListWhenNoBatchesMatchOrderIdAndStatus() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0063", product, recipe, OrderStatus.RELEASED);
        entityManager.persistAndFlush(createBatch("BAT-OAS-4", order, BatchStatus.PLANNED));

        List<Batch> results = batchRepository.findByOrderIdAndStatus(order.getId(), BatchStatus.CANCELLED);

        assertNotNull(results, "Result list must not be null");
        assertTrue(results.isEmpty(), "Result list must be empty when no batches match orderId and status");
    }

    // -------------------------------------------------------------------------
    // 6. Unique Constraint Enforcement
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should throw DataIntegrityViolationException on duplicate batchNumber")
    void shouldFailWhenSavingDuplicateBatchNumber() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0070", product, recipe, OrderStatus.RELEASED);

        Batch batch1 = createBatch("BAT-DUP-001", order, BatchStatus.PLANNED);
        batchRepository.saveAndFlush(batch1);

        Batch batch2 = createBatch("BAT-DUP-001", order, BatchStatus.PLANNED);

        assertThrows(DataIntegrityViolationException.class, () -> {
            batchRepository.saveAndFlush(batch2);
        }, "Persisting duplicate batch number must trigger unique constraint violation");
    }

    // -------------------------------------------------------------------------
    // 7. Pessimistic Write Lock Query & Annotation Configuration
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should configure PESSIMISTIC_WRITE lock on findByIdWithLock and retrieve batch in JPA context")
    void shouldFindBatchByIdWithPessimisticWriteLock() throws NoSuchMethodException {
        Method lockMethod = BatchRepository.class.getMethod("findByIdWithLock", Long.class);
        Lock lockAnnotation = lockMethod.getAnnotation(Lock.class);
        Query queryAnnotation = lockMethod.getAnnotation(Query.class);

        assertNotNull(lockAnnotation, "@Lock annotation must be present on findByIdWithLock");
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lockAnnotation.value(),
                "@Lock must specify LockModeType.PESSIMISTIC_WRITE");
        assertNotNull(queryAnnotation, "@Query annotation must be present on findByIdWithLock");
        assertEquals("SELECT b FROM Batch b WHERE b.id = :id", queryAnnotation.value());

        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createAndPersistOrder("MO-2026-0080", product, recipe, OrderStatus.IN_PROGRESS);
        Batch batch = batchRepository.saveAndFlush(createBatch("BAT-LOCK-001", order, BatchStatus.IN_PROGRESS));

        entityManager.clear();

        Optional<Batch> locked = batchRepository.findByIdWithLock(batch.getId());
        assertTrue(locked.isPresent(), "findByIdWithLock should retrieve an existing batch");
        assertEquals("BAT-LOCK-001", locked.get().getBatchNumber());
        assertEquals(LockModeType.PESSIMISTIC_WRITE,
                entityManager.getEntityManager().getLockMode(locked.get()),
                "EntityManager lock mode for loaded batch should be PESSIMISTIC_WRITE");

        assertTrue(batchRepository.findByIdWithLock(999999L).isEmpty(),
                "findByIdWithLock should return empty Optional when batch ID does not exist");
    }
}

