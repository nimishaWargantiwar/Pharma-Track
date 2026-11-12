package com.pharmatrack;

import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated unit tests for Batch JPA entity and BatchStatus enum.
 * Tests POJO properties, navigation through parent order, lifecycle callbacks,
 * bean validation, and equals/hashCode implementations in memory without database dependencies.
 */
class BatchEntityTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private ManufacturingOrder createSampleOrder() {
        Product product = new Product("AMOX-250", "Amoxicillin Capsules", "250 mg", "Capsule", "Antibiotic", ProductStatus.ACTIVE);
        product.setId(1L);

        Recipe recipe = new Recipe("RCP-AMOX-250-V3", product, 3, new BigDecimal("250.0000"), "kg", RecipeStatus.APPROVED, "Master Recipe V3");
        recipe.setId(10L);

        ManufacturingOrder order = new ManufacturingOrder(
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
                "Produce two batches"
        );
        order.setId(100L);
        return order;
    }

    private Batch createValidBatch() {
        ManufacturingOrder order = createSampleOrder();
        return new Batch(
                "BAT-2026-0001-01",
                order,
                new BigDecimal("250.0000"),
                "kg",
                BatchStatus.PLANNED,
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2028, 11, 10)
        );
    }

    // -------------------------------------------------------------------------
    // 1. BatchStatus Enum Values
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. BatchStatus contains all 4 required lifecycle states")
    void batchStatusContainsAllRequiredStates() {
        BatchStatus[] statuses = BatchStatus.values();
        assertEquals(4, statuses.length);
        assertNotNull(BatchStatus.valueOf("PLANNED"));
        assertNotNull(BatchStatus.valueOf("IN_PROGRESS"));
        assertNotNull(BatchStatus.valueOf("COMPLETED"));
        assertNotNull(BatchStatus.valueOf("CANCELLED"));
    }

    // -------------------------------------------------------------------------
    // 2. Default Status is PLANNED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2. Default status is PLANNED after PrePersist callback")
    void batchDefaultStatusIsPlanned() throws Exception {
        Batch batch = new Batch();
        assertNull(batch.getStatus(), "Status should be null prior to lifecycle callback");

        Method onCreateMethod = Batch.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(batch);

        assertEquals(BatchStatus.PLANNED, batch.getStatus(), "Default status must be PLANNED");
    }

    // -------------------------------------------------------------------------
    // 3. PrePersist Initializes Timestamps
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3. PrePersist callback populates createdAt and updatedAt timestamps")
    void prePersistPopulatesTimestamps() throws Exception {
        Batch batch = new Batch();
        assertNull(batch.getCreatedAt());
        assertNull(batch.getUpdatedAt());

        Method onCreateMethod = Batch.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(batch);

        assertNotNull(batch.getCreatedAt());
        assertNotNull(batch.getUpdatedAt());
        assertEquals(batch.getCreatedAt(), batch.getUpdatedAt());
    }

    // -------------------------------------------------------------------------
    // 4. PreUpdate Updates Timestamp
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("4. PreUpdate callback updates updatedAt timestamp")
    void preUpdateRefreshesTimestamp() throws Exception {
        Batch batch = new Batch();
        LocalDateTime past = LocalDateTime.of(2026, 1, 1, 12, 0);
        batch.setUpdatedAt(past);

        Method onUpdateMethod = Batch.class.getDeclaredMethod("onUpdate");
        onUpdateMethod.setAccessible(true);
        onUpdateMethod.invoke(batch);

        assertTrue(batch.getUpdatedAt().isAfter(past), "updatedAt must be refreshed to a more recent timestamp");
    }

    // -------------------------------------------------------------------------
    // 5. Getters and Setters Store Field Values Correctly
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5. Batch stores all fields correctly via getters and setters")
    void batchStoresAllFieldsCorrectly() {
        Batch batch = new Batch();
        ManufacturingOrder order = createSampleOrder();

        batch.setId(50L);
        batch.setBatchNumber("BAT-2026-0001-02");
        batch.setOrder(order);
        batch.setBatchSize(new BigDecimal("250.0000"));
        batch.setBatchUnit("kg");
        batch.setActualYield(new BigDecimal("248.7500"));
        batch.setStatus(BatchStatus.IN_PROGRESS);
        batch.setManufactureDate(LocalDate.of(2026, 11, 11));
        batch.setExpiryDate(LocalDate.of(2028, 11, 11));
        batch.setStartTime(LocalDateTime.of(2026, 11, 11, 9, 0));
        batch.setEndTime(LocalDateTime.of(2026, 11, 11, 17, 30));

        assertEquals(50L, batch.getId());
        assertEquals("BAT-2026-0001-02", batch.getBatchNumber());
        assertEquals(order, batch.getOrder());
        assertEquals(new BigDecimal("250.0000"), batch.getBatchSize());
        assertEquals("kg", batch.getBatchUnit());
        assertEquals(new BigDecimal("248.7500"), batch.getActualYield());
        assertEquals(BatchStatus.IN_PROGRESS, batch.getStatus());
        assertEquals(LocalDate.of(2026, 11, 11), batch.getManufactureDate());
        assertEquals(LocalDate.of(2028, 11, 11), batch.getExpiryDate());
        assertEquals(LocalDateTime.of(2026, 11, 11, 9, 0), batch.getStartTime());
        assertEquals(LocalDateTime.of(2026, 11, 11, 17, 30), batch.getEndTime());
    }

    // -------------------------------------------------------------------------
    // 6. Navigation to Product and Recipe through Parent Order
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. Product and Recipe are correctly accessed through the parent order without redundant batch foreign keys")
    void navigatesToProductAndRecipeThroughOrder() {
        Batch batch = createValidBatch();

        assertNotNull(batch.getOrder());
        assertEquals("MO-2026-0001", batch.getOrder().getOrderNumber());

        // Access product via order
        assertNotNull(batch.getOrder().getProduct());
        assertEquals("AMOX-250", batch.getOrder().getProduct().getProductCode());
        assertEquals("Amoxicillin Capsules", batch.getOrder().getProduct().getProductName());

        // Access recipe via order
        assertNotNull(batch.getOrder().getRecipe());
        assertEquals("RCP-AMOX-250-V3", batch.getOrder().getRecipe().getRecipeCode());
        assertEquals(3, batch.getOrder().getRecipeVersion());
    }

    // -------------------------------------------------------------------------
    // 7. Bean Validation on Valid Batch
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("7. Valid Batch passes Bean Validation without violations")
    void validBatchPassesValidation() {
        Batch batch = createValidBatch();
        Set<ConstraintViolation<Batch>> violations = validator.validate(batch);
        assertTrue(violations.isEmpty(), "Valid batch should have no validation violations");
    }

    // -------------------------------------------------------------------------
    // 8. Bean Validation on Missing Mandatory Fields
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("8. Blank Batch fails validation with all mandatory field violations")
    void blankBatchFailsValidation() {
        Batch batch = new Batch();
        Set<ConstraintViolation<Batch>> violations = validator.validate(batch);
        assertFalse(violations.isEmpty(), "Blank batch must produce violations");

        Set<String> violatedFields = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertTrue(violatedFields.contains("batchNumber"));
        assertTrue(violatedFields.contains("order"));
        assertTrue(violatedFields.contains("batchSize"));
        assertTrue(violatedFields.contains("batchUnit"));
        assertTrue(violatedFields.contains("status"));
    }

    // -------------------------------------------------------------------------
    // 9. Bean Validation on Invalid Numeric Values & String Sizes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9. Zero/negative batch sizes and blank/oversized strings produce validation violations")
    void invalidFieldValuesProduceViolations() {
        Batch batch = createValidBatch();

        // Negative batch size
        batch.setBatchSize(new BigDecimal("-50.0000"));
        Set<ConstraintViolation<Batch>> violations = validator.validate(batch);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("batchSize")));

        // Zero batch size
        batch.setBatchSize(BigDecimal.ZERO);
        violations = validator.validate(batch);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("batchSize")));

        // Reset batch size
        batch.setBatchSize(new BigDecimal("250.0000"));

        // Blank batch number
        batch.setBatchNumber("   ");
        violations = validator.validate(batch);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("batchNumber")));

        // Batch number too long (> 50 chars)
        batch.setBatchNumber("BAT-" + "X".repeat(50));
        violations = validator.validate(batch);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("batchNumber")));

        // Reset batch number
        batch.setBatchNumber("BAT-2026-0001-01");

        // Blank batch unit
        batch.setBatchUnit("   ");
        violations = validator.validate(batch);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("batchUnit")));

        // Batch unit too long (> 50 chars)
        batch.setBatchUnit("U".repeat(51));
        violations = validator.validate(batch);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("batchUnit")));
    }

    // -------------------------------------------------------------------------
    // 10. Optional Fields Do Not Trigger Violations When Null
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("10. Optional fields (actualYield, dates, times) can be null without violating validation")
    void optionalFieldsCanBeNull() {
        ManufacturingOrder order = createSampleOrder();
        Batch batch = new Batch("BAT-2026-0001-01", order, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch.setActualYield(null);
        batch.setManufactureDate(null);
        batch.setExpiryDate(null);
        batch.setStartTime(null);
        batch.setEndTime(null);

        Set<ConstraintViolation<Batch>> violations = validator.validate(batch);
        assertTrue(violations.isEmpty(), "Null optional fields must not cause validation violations");
    }

    // -------------------------------------------------------------------------
    // 11. Equals and HashCode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("11. Equals and HashCode adhere to Hibernate-proxy-safe contract with base class hash code and null protection")
    void equalsAndHashCodeContract() {
        Batch batch1 = new Batch();
        batch1.setId(1L);
        batch1.setBatchNumber("BAT-2026-0001-01");

        Batch batch2 = new Batch();
        batch2.setId(2L);
        batch2.setBatchNumber("BAT-2026-0001-01"); // Same business key

        Batch batch3 = new Batch();
        batch3.setId(3L);
        batch3.setBatchNumber("BAT-2026-0001-02"); // Different business key

        // 1. An entity equals itself
        assertEquals(batch1, batch1, "An entity must equal itself");

        // 2. Two entities with the same non-null natural key compare as equal
        assertEquals(batch1, batch2, "Batches with identical batchNumber must be equal");
        assertEquals(batch1.hashCode(), batch2.hashCode(), "Batches with identical batchNumber must share hashCode");
        assertEquals(Batch.class.hashCode(), batch1.hashCode(), "HashCode must match Batch base class constant");

        // 3. Two entities with different non-null natural keys are not equal
        assertNotEquals(batch1, batch3, "Batches with different batchNumber must not be equal");
        assertNotEquals(batch1, null, "Batch should not equal null");
        assertNotEquals(batch1, "someString", "Batch should not equal different type");

        // 4. Two separate entities with null natural keys must NOT compare as equal
        Batch nullKeyBatchA = new Batch();
        Batch nullKeyBatchB = new Batch();
        assertEquals(nullKeyBatchA, nullKeyBatchA, "Transient batch with null natural key must equal itself");
        assertNotEquals(nullKeyBatchA, nullKeyBatchB, "Two separate entities with null natural keys must NOT be equal");
        assertNotEquals(nullKeyBatchA, batch1, "Entity with null key must not equal entity with non-null key");
        assertNotEquals(batch1, nullKeyBatchA, "Entity with non-null key must not equal entity with null key");

        // 5. Hash codes remain stable when a natural key changes from null to a value
        Batch mutatingBatch = new Batch();
        int hashBefore = mutatingBatch.hashCode();
        assertEquals(Batch.class.hashCode(), hashBefore);
        mutatingBatch.setBatchNumber("BAT-2026-0001-99");
        int hashAfter = mutatingBatch.hashCode();
        assertEquals(hashBefore, hashAfter, "HashCode must remain stable when natural key changes from null to a value");

        // 6. Subclass / simulated proxy compatibility:
        // Note: This is an in-memory unit test simulating proxy subclass behavior (not an integration test
        // with actual Hibernate runtime ByteBuddy proxies). It verifies instanceof and getter-based access on both sides.
        class BatchSubclassProxy extends Batch {
            private final String proxyBatchNumber;
            BatchSubclassProxy(String batchNumber) {
                super(); // super.batchNumber remains null
                this.proxyBatchNumber = batchNumber;
            }
            @Override
            public String getBatchNumber() {
                return this.proxyBatchNumber;
            }
        }
        Batch proxy = new BatchSubclassProxy("BAT-2026-0001-01");
        assertEquals(batch1, proxy, "Batch must equal its subclass proxy when getBatchNumber() matches (instanceof check)");
        assertEquals(proxy, batch1, "Subclass proxy must equal Batch when getBatchNumber() matches (symmetric getter access)");
    }

    // -------------------------------------------------------------------------
    // 12. toString Representation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("12. toString contains batch number, size, unit, and status")
    void toStringContainsExpectedFields() {
        Batch batch = createValidBatch();
        String str = batch.toString();
        assertTrue(str.contains("BAT-2026-0001-01"));
        assertTrue(str.contains("250.0000"));
        assertTrue(str.contains("kg"));
        assertTrue(str.contains("PLANNED"));
    }
}

