package com.pharmatrack;

import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ManufacturingOrderRepository} using @DataJpaTest
 * and an isolated in-memory H2 database.
 */
@DataJpaTest
class ManufacturingOrderRepositoryTest {

    @Autowired
    private ManufacturingOrderRepository orderRepository;

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

    private ManufacturingOrder createOrder(String orderNumber, Product product, Recipe recipe, OrderStatus status) {
        return new ManufacturingOrder(
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
                "Production order for batch run"
        );
    }

    // -------------------------------------------------------------------------
    // 1. Basic Persistence & Auditing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should save a manufacturing order and generate ID and audit timestamps")
    void shouldSaveAndRetrieveOrder() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createOrder("MO-2026-0001", product, recipe, OrderStatus.PLANNED);

        ManufacturingOrder saved = orderRepository.saveAndFlush(order);

        assertNotNull(saved.getId(), "Generated ID must not be null");
        assertNotNull(saved.getCreatedAt(), "CreatedAt timestamp must be populated");
        assertNotNull(saved.getUpdatedAt(), "UpdatedAt timestamp must be populated");
        assertEquals("MO-2026-0001", saved.getOrderNumber());
        assertEquals(OrderStatus.PLANNED, saved.getStatus());
        assertEquals(3, saved.getRecipeVersion());
        assertEquals(new BigDecimal("500.0000"), saved.getPlannedQuantity());
        assertEquals(2, saved.getTargetBatchCount());

        entityManager.clear();

        Optional<ManufacturingOrder> retrieved = orderRepository.findById(saved.getId());
        assertTrue(retrieved.isPresent(), "Order should be retrievable by ID");
        assertEquals("MO-2026-0001", retrieved.get().getOrderNumber());
        assertEquals("AMOX-250", retrieved.get().getProduct().getProductCode());
        assertEquals("RCP-AMOX-250-V3", retrieved.get().getRecipe().getRecipeCode());
    }

    // -------------------------------------------------------------------------
    // 2. Cascading Persistence to Child Batches
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should cascade persist child batches when order is saved")
    void shouldCascadePersistBatchesWhenOrderIsSaved() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createOrder("MO-2026-0002", product, recipe, OrderStatus.PLANNED);

        Batch batch1 = new Batch("BAT-2026-0001", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.PLANNED, LocalDate.of(2026, 3, 1), LocalDate.of(2028, 3, 1));
        Batch batch2 = new Batch("BAT-2026-0002", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.PLANNED, LocalDate.of(2026, 3, 2), LocalDate.of(2028, 3, 2));

        order.addBatch(batch1);
        order.addBatch(batch2);

        ManufacturingOrder saved = orderRepository.saveAndFlush(order);
        Long savedOrderId = saved.getId();

        entityManager.clear();

        ManufacturingOrder reloaded = orderRepository.findById(savedOrderId).orElseThrow();
        assertEquals(2, reloaded.getBatches().size(), "Both child batches must be cascade persisted");
        assertNotNull(reloaded.getBatches().get(0).getId(), "Batch 1 ID must be generated");
        assertNotNull(reloaded.getBatches().get(1).getId(), "Batch 2 ID must be generated");
        assertEquals("BAT-2026-0001", reloaded.getBatches().get(0).getBatchNumber());
        assertEquals("BAT-2026-0002", reloaded.getBatches().get(1).getBatchNumber());
    }

    // -------------------------------------------------------------------------
    // 3. Find by Order Number & Existence Checks
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should find manufacturing order by orderNumber when it exists")
    void shouldFindOrderByOrderNumberWhenExists() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createOrder("MO-2026-0003", product, recipe, OrderStatus.RELEASED);
        entityManager.persistAndFlush(order);

        Optional<ManufacturingOrder> found = orderRepository.findByOrderNumber("MO-2026-0003");

        assertTrue(found.isPresent(), "Order should be found by order number");
        assertEquals(OrderStatus.RELEASED, found.get().getStatus());
        assertEquals("MO-2026-0003", found.get().getOrderNumber());
    }

    @Test
    @DisplayName("Should return empty Optional when orderNumber does not exist")
    void shouldReturnEmptyOptionalWhenOrderNumberNotFound() {
        Optional<ManufacturingOrder> found = orderRepository.findByOrderNumber("MO-NONEXISTENT");

        assertTrue(found.isEmpty(), "Optional must be empty for non-existent order number");
    }

    @Test
    @DisplayName("Should return true when checking existsByOrderNumber for an existing order")
    void shouldReturnTrueWhenOrderNumberExists() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        ManufacturingOrder order = createOrder("MO-2026-0004", product, recipe, OrderStatus.PLANNED);
        entityManager.persistAndFlush(order);

        boolean exists = orderRepository.existsByOrderNumber("MO-2026-0004");

        assertTrue(exists, "existsByOrderNumber must return true for an existing order");
    }

    @Test
    @DisplayName("Should return false when checking existsByOrderNumber for a non-existent order")
    void shouldReturnFalseWhenOrderNumberDoesNotExist() {
        boolean exists = orderRepository.existsByOrderNumber("MO-UNKNOWN-999");

        assertFalse(exists, "existsByOrderNumber must return false for a non-existent order");
    }

    // -------------------------------------------------------------------------
    // 4. Queries by Status
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should find manufacturing orders by status")
    void shouldFindOrdersByStatus() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);

        entityManager.persistAndFlush(createOrder("MO-STAT-1", product, recipe, OrderStatus.PLANNED));
        entityManager.persistAndFlush(createOrder("MO-STAT-2", product, recipe, OrderStatus.RELEASED));
        entityManager.persistAndFlush(createOrder("MO-STAT-3", product, recipe, OrderStatus.PLANNED));

        List<ManufacturingOrder> plannedOrders = orderRepository.findByStatus(OrderStatus.PLANNED);
        List<ManufacturingOrder> releasedOrders = orderRepository.findByStatus(OrderStatus.RELEASED);

        assertEquals(2, plannedOrders.size(), "Should find 2 orders with PLANNED status");
        assertEquals(1, releasedOrders.size(), "Should find 1 order with RELEASED status");
        assertEquals("MO-STAT-2", releasedOrders.get(0).getOrderNumber());
    }

    @Test
    @DisplayName("Should return empty list when no orders match the given status")
    void shouldReturnEmptyListWhenNoOrdersMatchStatus() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);
        entityManager.persistAndFlush(createOrder("MO-STAT-4", product, recipe, OrderStatus.PLANNED));

        List<ManufacturingOrder> cancelledOrders = orderRepository.findByStatus(OrderStatus.CANCELLED);

        assertNotNull(cancelledOrders, "Result list must not be null");
        assertTrue(cancelledOrders.isEmpty(), "Result list must be empty when no orders match status");
    }

    // -------------------------------------------------------------------------
    // 5. Queries by Product & Recipe Associations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should find manufacturing orders by productId")
    void shouldFindOrdersByProductId() {
        Product prod1 = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Product prod2 = createAndPersistProduct("PARA-500", "Paracetamol Tablets");
        Recipe recipe1 = createAndPersistRecipe(prod1, "RCP-AMOX-250-V3", 3);
        Recipe recipe2 = createAndPersistRecipe(prod2, "RCP-PARA-500-V1", 1);

        entityManager.persistAndFlush(createOrder("MO-PRD-1", prod1, recipe1, OrderStatus.PLANNED));
        entityManager.persistAndFlush(createOrder("MO-PRD-2", prod1, recipe1, OrderStatus.RELEASED));
        entityManager.persistAndFlush(createOrder("MO-PRD-3", prod2, recipe2, OrderStatus.PLANNED));

        List<ManufacturingOrder> prod1Orders = orderRepository.findByProductId(prod1.getId());
        List<ManufacturingOrder> prod2Orders = orderRepository.findByProductId(prod2.getId());

        assertEquals(2, prod1Orders.size(), "Should find 2 orders for product 1");
        assertEquals(1, prod2Orders.size(), "Should find 1 order for product 2");
    }

    @Test
    @DisplayName("Should return empty list when no orders match the given productId")
    void shouldReturnEmptyListWhenNoOrdersMatchProductId() {
        List<ManufacturingOrder> orders = orderRepository.findByProductId(9999L);

        assertNotNull(orders, "Result list must not be null");
        assertTrue(orders.isEmpty(), "Result list must be empty for non-existent productId");
    }

    @Test
    @DisplayName("Should find manufacturing orders by recipeId")
    void shouldFindOrdersByRecipeId() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe1 = createAndPersistRecipe(product, "RCP-AMOX-250-V1", 1);
        Recipe recipe2 = createAndPersistRecipe(product, "RCP-AMOX-250-V2", 2);

        entityManager.persistAndFlush(createOrder("MO-RCP-1", product, recipe1, OrderStatus.COMPLETED));
        entityManager.persistAndFlush(createOrder("MO-RCP-2", product, recipe2, OrderStatus.PLANNED));

        List<ManufacturingOrder> recipe1Orders = orderRepository.findByRecipeId(recipe1.getId());
        List<ManufacturingOrder> recipe2Orders = orderRepository.findByRecipeId(recipe2.getId());

        assertEquals(1, recipe1Orders.size(), "Should find 1 order for recipe 1");
        assertEquals("MO-RCP-1", recipe1Orders.get(0).getOrderNumber());
        assertEquals(1, recipe2Orders.size(), "Should find 1 order for recipe 2");
        assertEquals("MO-RCP-2", recipe2Orders.get(0).getOrderNumber());
    }

    @Test
    @DisplayName("Should return empty list when no orders match the given recipeId")
    void shouldReturnEmptyListWhenNoOrdersMatchRecipeId() {
        List<ManufacturingOrder> orders = orderRepository.findByRecipeId(9999L);

        assertNotNull(orders, "Result list must not be null");
        assertTrue(orders.isEmpty(), "Result list must be empty for non-existent recipeId");
    }

    @Test
    @DisplayName("Should find manufacturing orders by productId and status")
    void shouldFindOrdersByProductIdAndStatus() {
        Product prod1 = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Product prod2 = createAndPersistProduct("PARA-500", "Paracetamol Tablets");
        Recipe recipe1 = createAndPersistRecipe(prod1, "RCP-AMOX-250-V3", 3);
        Recipe recipe2 = createAndPersistRecipe(prod2, "RCP-PARA-500-V1", 1);

        entityManager.persistAndFlush(createOrder("MO-COMB-1", prod1, recipe1, OrderStatus.PLANNED));
        entityManager.persistAndFlush(createOrder("MO-COMB-2", prod1, recipe1, OrderStatus.RELEASED));
        entityManager.persistAndFlush(createOrder("MO-COMB-3", prod2, recipe2, OrderStatus.PLANNED));

        List<ManufacturingOrder> prod1Planned = orderRepository.findByProductIdAndStatus(prod1.getId(), OrderStatus.PLANNED);
        List<ManufacturingOrder> prod1Released = orderRepository.findByProductIdAndStatus(prod1.getId(), OrderStatus.RELEASED);

        assertEquals(1, prod1Planned.size(), "Should find 1 PLANNED order for product 1");
        assertEquals("MO-COMB-1", prod1Planned.get(0).getOrderNumber());
        assertEquals(1, prod1Released.size(), "Should find 1 RELEASED order for product 1");
        assertEquals("MO-COMB-2", prod1Released.get(0).getOrderNumber());
    }

    // -------------------------------------------------------------------------
    // 6. Unique Constraint Enforcement
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should throw DataIntegrityViolationException on duplicate orderNumber")
    void shouldFailWhenSavingDuplicateOrderNumber() {
        Product product = createAndPersistProduct("AMOX-250", "Amoxicillin Capsules");
        Recipe recipe = createAndPersistRecipe(product, "RCP-AMOX-250-V3", 3);

        ManufacturingOrder order1 = createOrder("MO-DUP-001", product, recipe, OrderStatus.PLANNED);
        orderRepository.saveAndFlush(order1);

        ManufacturingOrder order2 = createOrder("MO-DUP-001", product, recipe, OrderStatus.PLANNED);

        assertThrows(DataIntegrityViolationException.class, () -> {
            orderRepository.saveAndFlush(order2);
        }, "Persisting duplicate order number must trigger unique constraint violation");
    }
}

