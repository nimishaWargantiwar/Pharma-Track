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
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated unit tests for {@link BatchExecutionStep} JPA entity and {@link ExecutionStepStatus} enum.
 * Tests enum states, default status, snapshot and execution field storage, lifecycle callbacks,
 * JPA mapping/constraint metadata, bean validation, and equals/hashCode behavior in memory.
 */
class BatchExecutionStepEntityTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private Batch createSampleBatch() {
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
                OrderStatus.IN_PROGRESS,
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 20),
                "Produce two batches"
        );
        order.setId(100L);

        Batch batch = new Batch(
                "BAT-2026-0001-01",
                order,
                new BigDecimal("250.0000"),
                "kg",
                BatchStatus.IN_PROGRESS,
                LocalDate.of(2026, 11, 10),
                null
        );
        batch.setId(200L);
        return batch;
    }

    private RecipeStep createSampleRecipeStep() {
        RecipeStep step = new RecipeStep(1, "Dry Blending", "Blend dry powders in V-blender at 25 RPM for 20 minutes.");
        step.setId(300L);
        return step;
    }

    private BatchExecutionStep createValidExecutionStep() {
        return new BatchExecutionStep(
                createSampleBatch(),
                createSampleRecipeStep(),
                1,
                "Dry Blending",
                "Blend dry powders in V-blender at 25 RPM for 20 minutes."
        );
    }

    // -------------------------------------------------------------------------
    // 1. ExecutionStepStatus Enum Values
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. ExecutionStepStatus contains PENDING, IN_PROGRESS, and COMPLETED")
    void executionStepStatusContainsAllRequiredStates() {
        ExecutionStepStatus[] statuses = ExecutionStepStatus.values();
        assertEquals(3, statuses.length);
        assertEquals(ExecutionStepStatus.PENDING, ExecutionStepStatus.valueOf("PENDING"));
        assertEquals(ExecutionStepStatus.IN_PROGRESS, ExecutionStepStatus.valueOf("IN_PROGRESS"));
        assertEquals(ExecutionStepStatus.COMPLETED, ExecutionStepStatus.valueOf("COMPLETED"));
    }

    // -------------------------------------------------------------------------
    // 2. Default Status is PENDING
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2. Default status is PENDING upon construction and when PrePersist runs with null status")
    void defaultStatusIsPending() throws Exception {
        BatchExecutionStep step = new BatchExecutionStep();
        assertEquals(ExecutionStepStatus.PENDING, step.getStatus(), "Default status must be PENDING on new instance");

        // Also verify PrePersist fallback restores PENDING if explicitly cleared
        step.setStatus(null);
        assertNull(step.getStatus());

        Method onCreateMethod = BatchExecutionStep.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(step);

        assertEquals(ExecutionStepStatus.PENDING, step.getStatus(), "PrePersist must default null status to PENDING");
    }

    // -------------------------------------------------------------------------
    // 3. Entity Fields and Relationships Storage
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3. BatchExecutionStep stores all relationships, snapshot fields, execution data, and timestamps correctly")
    void storesAllFieldsCorrectly() {
        BatchExecutionStep step = new BatchExecutionStep();
        Batch batch = createSampleBatch();
        RecipeStep recipeStep = createSampleRecipeStep();
        LocalDateTime startedAt = LocalDateTime.of(2026, 11, 9, 10, 0);
        LocalDateTime completedAt = LocalDateTime.of(2026, 11, 9, 10, 25);
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 9, 9, 55);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 11, 9, 10, 25);

        step.setId(42L);
        step.setBatch(batch);
        step.setRecipeStep(recipeStep);
        step.setStepNumber(2);
        step.setOperationName("Wet Granulation");
        step.setInstructions("Add binder solution steadily over 15 minutes.");
        step.setStatus(ExecutionStepStatus.COMPLETED);
        step.setActualValue("24 RPM / 42.5 °C");
        step.setObservations("Uniform wet mass achieved without lumps.");
        step.setExecutedBy("J. Doe");
        step.setStartedAt(startedAt);
        step.setCompletedAt(completedAt);
        step.setCreatedAt(createdAt);
        step.setUpdatedAt(updatedAt);

        assertEquals(42L, step.getId());
        assertSame(batch, step.getBatch());
        assertSame(recipeStep, step.getRecipeStep());
        assertEquals(2, step.getStepNumber());
        assertEquals("Wet Granulation", step.getOperationName());
        assertEquals("Add binder solution steadily over 15 minutes.", step.getInstructions());
        assertEquals(ExecutionStepStatus.COMPLETED, step.getStatus());
        assertEquals("24 RPM / 42.5 °C", step.getActualValue());
        assertEquals("Uniform wet mass achieved without lumps.", step.getObservations());
        assertEquals("J. Doe", step.getExecutedBy());
        assertEquals(startedAt, step.getStartedAt());
        assertEquals(completedAt, step.getCompletedAt());
        assertEquals(createdAt, step.getCreatedAt());
        assertEquals(updatedAt, step.getUpdatedAt());
    }

    // -------------------------------------------------------------------------
    // 4. PrePersist Initializes Creation & Update Timestamps
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("4. PrePersist callback initializes createdAt and updatedAt timestamps")
    void prePersistInitializesTimestamps() throws Exception {
        BatchExecutionStep step = new BatchExecutionStep();
        assertNull(step.getCreatedAt());
        assertNull(step.getUpdatedAt());

        Method onCreateMethod = BatchExecutionStep.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(step);

        assertNotNull(step.getCreatedAt(), "createdAt must be initialized on PrePersist");
        assertNotNull(step.getUpdatedAt(), "updatedAt must be initialized on PrePersist");
        assertEquals(step.getCreatedAt(), step.getUpdatedAt(), "createdAt and updatedAt should match initially");
    }

    // -------------------------------------------------------------------------
    // 5. PreUpdate Updates Timestamp Behavior
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5. PreUpdate callback refreshes updatedAt while preserving createdAt")
    void preUpdateRefreshesUpdatedAt() throws Exception {
        BatchExecutionStep step = new BatchExecutionStep();
        LocalDateTime past = LocalDateTime.of(2026, 1, 1, 12, 0);
        step.setCreatedAt(past);
        step.setUpdatedAt(past);

        Method onUpdateMethod = BatchExecutionStep.class.getDeclaredMethod("onUpdate");
        onUpdateMethod.setAccessible(true);
        onUpdateMethod.invoke(step);

        assertEquals(past, step.getCreatedAt(), "createdAt must remain unchanged on update");
        assertTrue(step.getUpdatedAt().isAfter(past), "updatedAt must be refreshed to a newer timestamp");
    }

    // -------------------------------------------------------------------------
    // 6. JPA Table, Relationship & Column Constraint Metadata
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. JPA table, unique constraint uk_batch_execution_step, and column constraints match specification")
    void jpaAnnotationsAndConstraintsMatchSpecification() throws NoSuchFieldException {
        Table table = BatchExecutionStep.class.getAnnotation(Table.class);
        assertNotNull(table, "@Table must be present");
        assertEquals("batch_execution_steps", table.name());

        UniqueConstraint[] constraints = table.uniqueConstraints();
        assertEquals(1, constraints.length);
        assertEquals("uk_batch_execution_step", constraints[0].name());
        assertEquals(Arrays.asList("batch_id", "step_number"), Arrays.asList(constraints[0].columnNames()));

        Field batchField = BatchExecutionStep.class.getDeclaredField("batch");
        ManyToOne batchManyToOne = batchField.getAnnotation(ManyToOne.class);
        JoinColumn batchJoinColumn = batchField.getAnnotation(JoinColumn.class);
        assertNotNull(batchManyToOne);
        assertFalse(batchManyToOne.optional(), "batch relationship must be mandatory");
        assertNotNull(batchJoinColumn);
        assertEquals("batch_id", batchJoinColumn.name());
        assertFalse(batchJoinColumn.nullable(), "batch_id column must not be nullable");

        Field recipeStepField = BatchExecutionStep.class.getDeclaredField("recipeStep");
        ManyToOne recipeStepManyToOne = recipeStepField.getAnnotation(ManyToOne.class);
        JoinColumn recipeStepJoinColumn = recipeStepField.getAnnotation(JoinColumn.class);
        assertNotNull(recipeStepManyToOne);
        assertFalse(recipeStepManyToOne.optional(), "recipeStep relationship must be mandatory");
        assertNotNull(recipeStepJoinColumn);
        assertEquals("recipe_step_id", recipeStepJoinColumn.name());
        assertFalse(recipeStepJoinColumn.nullable(), "recipe_step_id column must not be nullable");

        Field statusField = BatchExecutionStep.class.getDeclaredField("status");
        Enumerated enumerated = statusField.getAnnotation(Enumerated.class);
        Column statusColumn = statusField.getAnnotation(Column.class);
        assertNotNull(enumerated);
        assertEquals(EnumType.STRING, enumerated.value());
        assertNotNull(statusColumn);
        assertFalse(statusColumn.nullable());
        assertEquals(20, statusColumn.length());

        Field actualValueField = BatchExecutionStep.class.getDeclaredField("actualValue");
        Column actualValueColumn = actualValueField.getAnnotation(Column.class);
        assertNotNull(actualValueColumn);
        assertTrue(actualValueColumn.nullable(), "actual_value must remain nullable at the database level");
        assertEquals(255, actualValueColumn.length());

        Field executedByField = BatchExecutionStep.class.getDeclaredField("executedBy");
        Column executedByColumn = executedByField.getAnnotation(Column.class);
        assertNotNull(executedByColumn);
        assertTrue(executedByColumn.nullable());
        assertEquals(100, executedByColumn.length());
    }

    // -------------------------------------------------------------------------
    // 7. Bean Validation Constraints
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("7. Valid BatchExecutionStep with nullable execution fields passes Bean Validation")
    void validStepWithNullableExecutionFieldsPassesValidation() {
        BatchExecutionStep step = createValidExecutionStep();
        step.setActualValue(null);
        step.setObservations(null);
        step.setExecutedBy(null);
        step.setStartedAt(null);
        step.setCompletedAt(null);

        Set<ConstraintViolation<BatchExecutionStep>> violations = validator.validate(step);
        assertTrue(violations.isEmpty(), "Valid execution step with nullable execution fields should pass validation");
    }

    @Test
    @DisplayName("8. Missing mandatory fields fail Bean Validation")
    void missingMandatoryFieldsFailValidation() {
        BatchExecutionStep step = new BatchExecutionStep();
        step.setStatus(null);

        Set<ConstraintViolation<BatchExecutionStep>> violations = validator.validate(step);
        assertFalse(violations.isEmpty(), "Blank execution step must fail Bean Validation");

        Set<String> violatedFields = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertTrue(violatedFields.contains("batch"));
        assertTrue(violatedFields.contains("recipeStep"));
        assertTrue(violatedFields.contains("stepNumber"));
        assertTrue(violatedFields.contains("operationName"));
        assertTrue(violatedFields.contains("instructions"));
        assertTrue(violatedFields.contains("status"));
    }

    @Test
    @DisplayName("9. Invalid stepNumber and oversized/blank strings produce Bean Validation violations")
    void invalidNumericAndStringFieldsProduceViolations() {
        BatchExecutionStep step = createValidExecutionStep();

        // stepNumber < 1
        step.setStepNumber(0);
        Set<ConstraintViolation<BatchExecutionStep>> violations = validator.validate(step);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("stepNumber")));

        step.setStepNumber(-2);
        violations = validator.validate(step);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("stepNumber")));
        step.setStepNumber(1);

        // blank operationName
        step.setOperationName("   ");
        violations = validator.validate(step);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("operationName")));

        // oversized operationName (> 255 chars)
        step.setOperationName("O".repeat(256));
        violations = validator.validate(step);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("operationName")));
        step.setOperationName("Dry Blending");

        // blank instructions
        step.setInstructions("   ");
        violations = validator.validate(step);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("instructions")));
        step.setInstructions("Blend powders thoroughly.");

        // oversized actualValue (> 255 chars)
        step.setActualValue("V".repeat(256));
        violations = validator.validate(step);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("actualValue")));
        step.setActualValue("25 RPM");

        // oversized executedBy (> 100 chars)
        step.setExecutedBy("E".repeat(101));
        violations = validator.validate(step);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("executedBy")));
    }

    // -------------------------------------------------------------------------
    // 8. Equals, HashCode, and toString
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("10. Equals, hashCode, and toString work safely without accessing lazy proxies")
    void equalsHashCodeAndToString() {
        BatchExecutionStep step1 = new BatchExecutionStep();
        step1.setId(1L);
        step1.setStepNumber(1);
        step1.setOperationName("Dispensing");

        BatchExecutionStep step2 = new BatchExecutionStep();
        step2.setId(1L);

        BatchExecutionStep step3 = new BatchExecutionStep();
        step3.setId(2L);

        BatchExecutionStep transientA = new BatchExecutionStep();
        BatchExecutionStep transientB = new BatchExecutionStep();

        assertEquals(step1, step1);
        assertEquals(step1, step2);
        assertEquals(step1.hashCode(), step2.hashCode());
        assertEquals(BatchExecutionStep.class.hashCode(), step1.hashCode());
        assertNotEquals(step1, step3);
        assertNotEquals(transientA, transientB);
        assertEquals(transientA, transientA);
        assertNotEquals(step1, null);
        assertNotEquals(step1, "other");

        String str = step1.toString();
        assertTrue(str.contains("Dispensing"));
        assertTrue(str.contains("PENDING"));
    }
}

