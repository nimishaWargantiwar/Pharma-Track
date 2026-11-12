package com.pharmatrack;

import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
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
 * Dedicated unit tests for ManufacturingOrder JPA entity and OrderStatus enum.
 * Tests POJO properties, bidirectional helper contracts, lifecycle callbacks,
 * bean validation, and equals/hashCode implementations in memory without database dependencies.
 */
class OrderEntityTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private Product createSampleProduct() {
        Product product = new Product("AMOX-250", "Amoxicillin Capsules", "250 mg", "Capsule", "Antibiotic", ProductStatus.ACTIVE);
        product.setId(1L);
        return product;
    }

    private Recipe createSampleRecipe(Product product) {
        Recipe recipe = new Recipe("RCP-AMOX-250-V3", product, 3, new BigDecimal("250.0000"), "kg", RecipeStatus.APPROVED, "Master Recipe V3");
        recipe.setId(10L);
        return recipe;
    }

    private ManufacturingOrder createValidOrder() {
        Product product = createSampleProduct();
        Recipe recipe = createSampleRecipe(product);
        return new ManufacturingOrder(
                "MO-2026-0001",
                product,
                recipe,
                3,
                new BigDecimal("500.0000"),
                "kg",
                2,
                OrderStatus.PLANNED,
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 20),
                "Produce two batches of 250 kg each"
        );
    }

    // -------------------------------------------------------------------------
    // 1. OrderStatus Enum Values
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. OrderStatus contains all 5 required lifecycle states")
    void orderStatusContainsAllRequiredStates() {
        OrderStatus[] statuses = OrderStatus.values();
        assertEquals(5, statuses.length);
        assertNotNull(OrderStatus.valueOf("PLANNED"));
        assertNotNull(OrderStatus.valueOf("RELEASED"));
        assertNotNull(OrderStatus.valueOf("IN_PROGRESS"));
        assertNotNull(OrderStatus.valueOf("COMPLETED"));
        assertNotNull(OrderStatus.valueOf("CANCELLED"));
    }

    // -------------------------------------------------------------------------
    // 2. Default Status is PLANNED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2. Default status is PLANNED after PrePersist callback")
    void orderDefaultStatusIsPlanned() throws Exception {
        ManufacturingOrder order = new ManufacturingOrder();
        assertNull(order.getStatus(), "Status should be null prior to lifecycle callback");

        Method onCreateMethod = ManufacturingOrder.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(order);

        assertEquals(OrderStatus.PLANNED, order.getStatus(), "Default status must be PLANNED");
    }

    // -------------------------------------------------------------------------
    // 3. PrePersist Initializes Timestamps
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3. PrePersist callback populates createdAt and updatedAt timestamps")
    void prePersistPopulatesTimestamps() throws Exception {
        ManufacturingOrder order = new ManufacturingOrder();
        assertNull(order.getCreatedAt());
        assertNull(order.getUpdatedAt());

        Method onCreateMethod = ManufacturingOrder.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(order);

        assertNotNull(order.getCreatedAt());
        assertNotNull(order.getUpdatedAt());
        assertEquals(order.getCreatedAt(), order.getUpdatedAt());
    }

    // -------------------------------------------------------------------------
    // 4. PreUpdate Updates Timestamp
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("4. PreUpdate callback updates updatedAt timestamp")
    void preUpdateRefreshesTimestamp() throws Exception {
        ManufacturingOrder order = new ManufacturingOrder();
        LocalDateTime past = LocalDateTime.of(2026, 1, 1, 12, 0);
        order.setUpdatedAt(past);

        Method onUpdateMethod = ManufacturingOrder.class.getDeclaredMethod("onUpdate");
        onUpdateMethod.setAccessible(true);
        onUpdateMethod.invoke(order);

        assertTrue(order.getUpdatedAt().isAfter(past), "updatedAt must be refreshed to a more recent timestamp");
    }

    // -------------------------------------------------------------------------
    // 5. Getters and Setters Store Field Values Correctly
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5. Order stores all fields correctly via getters and setters")
    void orderStoresAllFieldsCorrectly() {
        ManufacturingOrder order = new ManufacturingOrder();
        Product product = createSampleProduct();
        Recipe recipe = createSampleRecipe(product);

        order.setId(100L);
        order.setOrderNumber("MO-2026-0005");
        order.setProduct(product);
        order.setRecipe(recipe);
        order.setRecipeVersion(3);
        order.setPlannedQuantity(new BigDecimal("750.0000"));
        order.setQuantityUnit("kg");
        order.setTargetBatchCount(3);
        order.setStatus(OrderStatus.RELEASED);
        order.setPlannedStartDate(LocalDate.of(2026, 11, 15));
        order.setPlannedEndDate(LocalDate.of(2026, 11, 25));
        order.setActualStartDate(LocalDateTime.of(2026, 11, 16, 8, 30));
        order.setActualEndDate(LocalDateTime.of(2026, 11, 24, 17, 0));
        order.setNotes("Priority run for hospital supply");

        assertEquals(100L, order.getId());
        assertEquals("MO-2026-0005", order.getOrderNumber());
        assertEquals(product, order.getProduct());
        assertEquals(recipe, order.getRecipe());
        assertEquals(3, order.getRecipeVersion());
        assertEquals(new BigDecimal("750.0000"), order.getPlannedQuantity());
        assertEquals("kg", order.getQuantityUnit());
        assertEquals(3, order.getTargetBatchCount());
        assertEquals(OrderStatus.RELEASED, order.getStatus());
        assertEquals(LocalDate.of(2026, 11, 15), order.getPlannedStartDate());
        assertEquals(LocalDate.of(2026, 11, 25), order.getPlannedEndDate());
        assertEquals(LocalDateTime.of(2026, 11, 16, 8, 30), order.getActualStartDate());
        assertEquals(LocalDateTime.of(2026, 11, 24, 17, 0), order.getActualEndDate());
        assertEquals("Priority run for hospital supply", order.getNotes());
    }

    // -------------------------------------------------------------------------
    // 6. Batches Collection Initialization
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. Order initializes batches collection to a non-null empty list")
    void orderInitializesBatchesCollection() {
        ManufacturingOrder order = new ManufacturingOrder();
        assertNotNull(order.getBatches(), "Batches collection must be initialized");
        assertTrue(order.getBatches().isEmpty(), "Batches collection should be initially empty");

        order.setBatches(null);
        assertNotNull(order.getBatches(), "setBatches(null) must guard against null and fallback to empty list");
        assertTrue(order.getBatches().isEmpty());
    }

    // -------------------------------------------------------------------------
    // 7. Bidirectional Helper: addBatch
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("7. addBatch maintains both sides of the bidirectional relationship")
    void addBatchMaintainsBidirectionalLink() {
        ManufacturingOrder order = createValidOrder();
        Batch batch = new Batch("BAT-2026-0001-01", null, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);

        assertNull(batch.getOrder());
        order.addBatch(batch);

        assertEquals(1, order.getBatches().size());
        assertTrue(order.getBatches().contains(batch));
        assertEquals(order, batch.getOrder(), "Batch order reference must be set to parent order");

        // Null safe
        order.addBatch(null);
        assertEquals(1, order.getBatches().size(), "Adding null batch should be safely ignored");
    }

    // -------------------------------------------------------------------------
    // 8. Protect Batches from Accidental Deletion
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("8. Cascade configuration protects batches from accidental deletion (orphanRemoval=false, PERSIST & MERGE only)")
    void cascadeConfigurationProtectsBatchesFromAccidentalDeletion() throws NoSuchFieldException {
        Field batchesField = ManufacturingOrder.class.getDeclaredField("batches");
        OneToMany oneToMany = batchesField.getAnnotation(OneToMany.class);

        assertNotNull(oneToMany, "@OneToMany must be present on batches field");
        assertFalse(oneToMany.orphanRemoval(), "orphanRemoval must be false to prevent automatic deletion of batches");

        CascadeType[] cascades = oneToMany.cascade();
        assertEquals(2, cascades.length, "Only PERSIST and MERGE cascades should be configured");
        List<CascadeType> cascadeList = Arrays.asList(cascades);
        assertTrue(cascadeList.contains(CascadeType.PERSIST), "Must cascade PERSIST");
        assertTrue(cascadeList.contains(CascadeType.MERGE), "Must cascade MERGE");
        assertFalse(cascadeList.contains(CascadeType.REMOVE), "Must NOT cascade REMOVE");
        assertFalse(cascadeList.contains(CascadeType.ALL), "Must NOT cascade ALL");

        // Verify removeBatch is not exposed on ManufacturingOrder
        boolean hasRemoveBatch = Arrays.stream(ManufacturingOrder.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("removeBatch"));
        assertFalse(hasRemoveBatch, "ManufacturingOrder must not expose removeBatch to prevent silent unlinking of batches");
    }

    // -------------------------------------------------------------------------
    // 9. Bean Validation on Valid Order
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9. Valid ManufacturingOrder passes Bean Validation without violations")
    void validOrderPassesValidation() {
        ManufacturingOrder order = createValidOrder();
        Set<ConstraintViolation<ManufacturingOrder>> violations = validator.validate(order);
        assertTrue(violations.isEmpty(), "Valid order should have no validation violations");
    }

    // -------------------------------------------------------------------------
    // 10. Bean Validation on Missing Mandatory Fields
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("10. Blank ManufacturingOrder fails validation with all mandatory field violations")
    void blankOrderFailsValidation() {
        ManufacturingOrder order = new ManufacturingOrder();
        Set<ConstraintViolation<ManufacturingOrder>> violations = validator.validate(order);
        assertFalse(violations.isEmpty(), "Blank order must produce violations");

        Set<String> violatedFields = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertTrue(violatedFields.contains("orderNumber"));
        assertTrue(violatedFields.contains("product"));
        assertTrue(violatedFields.contains("recipe"));
        assertTrue(violatedFields.contains("recipeVersion"));
        assertTrue(violatedFields.contains("plannedQuantity"));
        assertTrue(violatedFields.contains("quantityUnit"));
        assertTrue(violatedFields.contains("targetBatchCount"));
        assertTrue(violatedFields.contains("status"));
    }

    // -------------------------------------------------------------------------
    // 11. Bean Validation on Invalid Numeric Values
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("11. Invalid quantities, batch counts, and versions produce validation violations")
    void invalidNumericFieldsProduceViolations() {
        ManufacturingOrder order = createValidOrder();

        // Negative planned quantity
        order.setPlannedQuantity(new BigDecimal("-100.0000"));
        Set<ConstraintViolation<ManufacturingOrder>> violations = validator.validate(order);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("plannedQuantity")));

        // Zero planned quantity
        order.setPlannedQuantity(BigDecimal.ZERO);
        violations = validator.validate(order);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("plannedQuantity")));

        // Zero target batch count
        order.setPlannedQuantity(new BigDecimal("500.0000"));
        order.setTargetBatchCount(0);
        violations = validator.validate(order);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("targetBatchCount")));

        // Zero recipe version
        order.setTargetBatchCount(2);
        order.setRecipeVersion(0);
        violations = validator.validate(order);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("recipeVersion")));

        // Blank order number
        order.setRecipeVersion(3);
        order.setOrderNumber("   ");
        violations = validator.validate(order);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("orderNumber")));

        // Order number too long (> 50 chars)
        order.setOrderNumber("MO-" + "A".repeat(50));
        violations = validator.validate(order);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("orderNumber")));
    }

    // -------------------------------------------------------------------------
    // 12. Equals and HashCode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("12. Equals and HashCode adhere to Hibernate-proxy-safe contract with base class hash code and null protection")
    void equalsAndHashCodeContract() {
        ManufacturingOrder order1 = new ManufacturingOrder();
        order1.setId(1L);
        order1.setOrderNumber("MO-2026-0001");

        ManufacturingOrder order2 = new ManufacturingOrder();
        order2.setId(2L);
        order2.setOrderNumber("MO-2026-0001"); // Same business key

        ManufacturingOrder order3 = new ManufacturingOrder();
        order3.setId(3L);
        order3.setOrderNumber("MO-2026-0002"); // Different business key

        // 1. An entity equals itself
        assertEquals(order1, order1, "An entity must equal itself");

        // 2. Two entities with the same non-null natural key compare as equal
        assertEquals(order1, order2, "Orders with identical orderNumber must be equal");
        assertEquals(order1.hashCode(), order2.hashCode(), "Orders with identical orderNumber must share hashCode");
        assertEquals(ManufacturingOrder.class.hashCode(), order1.hashCode(), "HashCode must match ManufacturingOrder base class constant");

        // 3. Two entities with different non-null natural keys are not equal
        assertNotEquals(order1, order3, "Orders with different orderNumber must not be equal");
        assertNotEquals(order1, null, "Order should not equal null");
        assertNotEquals(order1, "someString", "Order should not equal different type");

        // 4. Two separate entities with null natural keys must NOT compare as equal
        ManufacturingOrder nullKeyOrderA = new ManufacturingOrder();
        ManufacturingOrder nullKeyOrderB = new ManufacturingOrder();
        assertEquals(nullKeyOrderA, nullKeyOrderA, "Transient entity with null natural key must equal itself");
        assertNotEquals(nullKeyOrderA, nullKeyOrderB, "Two separate entities with null natural keys must NOT be equal");
        assertNotEquals(nullKeyOrderA, order1, "Entity with null key must not equal entity with non-null key");
        assertNotEquals(order1, nullKeyOrderA, "Entity with non-null key must not equal entity with null key");

        // 5. Hash codes remain stable when a natural key changes from null to a value
        ManufacturingOrder mutatingOrder = new ManufacturingOrder();
        int hashBefore = mutatingOrder.hashCode();
        assertEquals(ManufacturingOrder.class.hashCode(), hashBefore);
        mutatingOrder.setOrderNumber("MO-2026-0099");
        int hashAfter = mutatingOrder.hashCode();
        assertEquals(hashBefore, hashAfter, "HashCode must remain stable when natural key changes from null to a value");

        // 6. Subclass / simulated proxy compatibility:
        // Note: This is an in-memory unit test simulating proxy subclass behavior (not an integration test
        // with actual Hibernate runtime ByteBuddy proxies). It verifies instanceof and getter-based access on both sides.
        class ManufacturingOrderSubclassProxy extends ManufacturingOrder {
            private final String proxyOrderNumber;
            ManufacturingOrderSubclassProxy(String orderNumber) {
                super(); // super.orderNumber remains null
                this.proxyOrderNumber = orderNumber;
            }
            @Override
            public String getOrderNumber() {
                return this.proxyOrderNumber;
            }
        }
        ManufacturingOrder proxy = new ManufacturingOrderSubclassProxy("MO-2026-0001");
        assertEquals(order1, proxy, "Order must equal its subclass proxy when getOrderNumber() matches (instanceof check)");
        assertEquals(proxy, order1, "Subclass proxy must equal Order when getOrderNumber() matches (symmetric getter access)");
    }

    // -------------------------------------------------------------------------
    // 13. toString Representation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("13. toString contains order number and key metadata")
    void toStringContainsExpectedFields() {
        ManufacturingOrder order = createValidOrder();
        String str = order.toString();
        assertTrue(str.contains("MO-2026-0001"));
        assertTrue(str.contains("500.0000"));
        assertTrue(str.contains("kg"));
        assertTrue(str.contains("PLANNED"));
    }
}

