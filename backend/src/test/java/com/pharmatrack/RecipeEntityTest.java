package com.pharmatrack;

import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeMaterial;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.util.AppTime;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated unit tests for Recipe JPA domain entities and enums.
 * Tests POJO properties, bidirectional helper contracts, lifecycle callbacks,
 * and equals/hashCode implementations in memory without database dependencies.
 */
class RecipeEntityTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private Product createSampleProduct() {
        return new Product("PRD-001", "Paracetamol", "500 mg", "Tablet", "Analgesic", ProductStatus.ACTIVE);
    }

    // -------------------------------------------------------------------------
    // 1. Recipe Default Status is DRAFT
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. Recipe default status is DRAFT after PrePersist callback")
    void recipeDefaultStatusIsDraft() throws Exception {
        Recipe recipe = new Recipe();
        assertNull(recipe.getStatus(), "Status should be null prior to lifecycle callback");

        Method onCreateMethod = Recipe.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(recipe);

        assertEquals(RecipeStatus.DRAFT, recipe.getStatus(), "Default status must be DRAFT");
    }

    // -------------------------------------------------------------------------
    // 2. Recipe Default Version is 1
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2. Recipe default version is 1 after PrePersist callback")
    void recipeDefaultVersionIsOne() throws Exception {
        Recipe recipe = new Recipe();
        assertNull(recipe.getVersion(), "Version should be null prior to lifecycle callback");

        Method onCreateMethod = Recipe.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(recipe);

        assertEquals(1, recipe.getVersion(), "Default version must be 1");
    }

    // -------------------------------------------------------------------------
    // 3. Batch Size and Batch Unit Storage
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3. Recipe can store batchSize and batchUnit correctly")
    void recipeStoresBatchSizeAndBatchUnit() {
        Recipe recipe = new Recipe();
        BigDecimal batchSize = new BigDecimal("1250.7500");
        String batchUnit = "kg";

        recipe.setBatchSize(batchSize);
        recipe.setBatchUnit(batchUnit);

        assertEquals(batchSize, recipe.getBatchSize());
        assertEquals("kg", recipe.getBatchUnit());
    }

    // -------------------------------------------------------------------------
    // 4. Recipe Initializes Collections & Guards Against Null
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("4. Recipe initializes materials and steps collections safely")
    void recipeInitializesCollectionsSafely() {
        Recipe recipe = new Recipe();

        assertNotNull(recipe.getMaterials(), "Materials list should never be null on construction");
        assertTrue(recipe.getMaterials().isEmpty(), "Materials list should start empty");

        assertNotNull(recipe.getSteps(), "Steps list should never be null on construction");
        assertTrue(recipe.getSteps().isEmpty(), "Steps list should start empty");

        // Verify null setter guard
        recipe.setMaterials(null);
        assertNotNull(recipe.getMaterials(), "setMaterials(null) must guard against null and set empty list");

        recipe.setSteps(null);
        assertNotNull(recipe.getSteps(), "setSteps(null) must guard against null and set empty list");
    }

    // -------------------------------------------------------------------------
    // 5. Adding Material Sets Parent Recipe Reference
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5. Adding a material sets both sides of the relationship")
    void addMaterialSetsParentRecipe() {
        Recipe recipe = new Recipe();
        RecipeMaterial material = new RecipeMaterial("Active Pharmaceutical Ingredient", new BigDecimal("50.0000"), "kg");

        recipe.addMaterial(material);

        assertTrue(recipe.getMaterials().contains(material), "Recipe materials must contain the added material");
        assertSame(recipe, material.getRecipe(), "Material must reference the parent recipe");
    }

    // -------------------------------------------------------------------------
    // 6. Adding Step Sets Parent Recipe Reference
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. Adding a step sets both sides of the relationship")
    void addStepSetsParentRecipe() {
        Recipe recipe = new Recipe();
        RecipeStep step = new RecipeStep(1, "Dispensing", "Dispense API and excipients.");

        recipe.addStep(step);

        assertTrue(recipe.getSteps().contains(step), "Recipe steps must contain the added step");
        assertSame(recipe, step.getRecipe(), "Step must reference the parent recipe");
    }

    // -------------------------------------------------------------------------
    // 7. Removing Material Clears Parent Reference
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("7. Removing a material removes it and clears parent reference")
    void removeMaterialClearsParentReference() {
        Recipe recipe = new Recipe();
        RecipeMaterial material = new RecipeMaterial("Excipient", new BigDecimal("10.0000"), "kg");

        recipe.addMaterial(material);
        assertEquals(1, recipe.getMaterials().size());
        assertSame(recipe, material.getRecipe());

        recipe.removeMaterial(material);

        assertTrue(recipe.getMaterials().isEmpty(), "Materials list should be empty after removal");
        assertNull(material.getRecipe(), "Removed material must have its recipe reference set to null");
    }

    // -------------------------------------------------------------------------
    // 8. Removing Step Clears Parent Reference
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("8. Removing a step removes it and clears parent reference")
    void removeStepClearsParentReference() {
        Recipe recipe = new Recipe();
        RecipeStep step = new RecipeStep(1, "Granulation", "Mix powders with solvent.");

        recipe.addStep(step);
        assertEquals(1, recipe.getSteps().size());
        assertSame(recipe, step.getRecipe());

        recipe.removeStep(step);

        assertTrue(recipe.getSteps().isEmpty(), "Steps list should be empty after removal");
        assertNull(step.getRecipe(), "Removed step must have its recipe reference set to null");
    }

    // -------------------------------------------------------------------------
    // 9. RecipeStatus Enum Values
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9. RecipeStatus contains DRAFT, APPROVED, and OBSOLETE")
    void recipeStatusEnumContainsApprovedValues() {
        RecipeStatus[] values = RecipeStatus.values();
        assertEquals(3, values.length, "RecipeStatus must have exactly three enum states");

        assertEquals(RecipeStatus.DRAFT, RecipeStatus.valueOf("DRAFT"));
        assertEquals(RecipeStatus.APPROVED, RecipeStatus.valueOf("APPROVED"));
        assertEquals(RecipeStatus.OBSOLETE, RecipeStatus.valueOf("OBSOLETE"));
    }

    // -------------------------------------------------------------------------
    // 10. Timestamps Populated During Lifecycle Callbacks
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("10. Recipe timestamps populated during PrePersist and PreUpdate")
    void recipeTimestampsPopulatedDuringCallbacks() throws Exception {
        Recipe recipe = new Recipe();
        assertNull(recipe.getCreatedAt());
        assertNull(recipe.getUpdatedAt());

        // Test onCreate (@PrePersist)
        Method onCreateMethod = Recipe.class.getDeclaredMethod("onCreate");
        onCreateMethod.setAccessible(true);
        onCreateMethod.invoke(recipe);

        LocalDateTime createdAt = recipe.getCreatedAt();
        LocalDateTime updatedAt = recipe.getUpdatedAt();

        assertNotNull(createdAt, "createdAt must be set on create");
        assertNotNull(updatedAt, "updatedAt must be set on create");
        assertEquals(createdAt, updatedAt, "createdAt and updatedAt should match initially");

        // Simulate time passing and test onUpdate (@PreUpdate)
        LocalDateTime past = AppTime.nowDateTime().minusHours(1);
        recipe.setCreatedAt(past);
        recipe.setUpdatedAt(past);

        Method onUpdateMethod = Recipe.class.getDeclaredMethod("onUpdate");
        onUpdateMethod.setAccessible(true);
        onUpdateMethod.invoke(recipe);

        assertEquals(past, recipe.getCreatedAt(), "createdAt must remain untouched on update");
        assertTrue(recipe.getUpdatedAt().isAfter(past), "updatedAt must be refreshed to a newer time");
    }

    // -------------------------------------------------------------------------
    // 11. RecipeMaterial Stores Fields Correctly
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("11. RecipeMaterial stores materialName, quantity, and unit correctly")
    void recipeMaterialStoresFields() {
        RecipeMaterial material = new RecipeMaterial();
        material.setId(10L);
        material.setMaterialName("Microcrystalline Cellulose");
        material.setQuantity(new BigDecimal("150.2500"));
        material.setUnit("kg");

        assertEquals(10L, material.getId());
        assertEquals("Microcrystalline Cellulose", material.getMaterialName());
        assertEquals(new BigDecimal("150.2500"), material.getQuantity());
        assertEquals("kg", material.getUnit());

        // Parameterized constructor verification
        RecipeMaterial paramMaterial = new RecipeMaterial("Lactose Monohydrate", new BigDecimal("75.0000"), "kg");
        assertEquals("Lactose Monohydrate", paramMaterial.getMaterialName());
        assertEquals(new BigDecimal("75.0000"), paramMaterial.getQuantity());
        assertEquals("kg", paramMaterial.getUnit());
    }

    // -------------------------------------------------------------------------
    // 12. RecipeStep Stores Fields Correctly
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("12. RecipeStep stores stepNumber, operationName, and instructions correctly")
    void recipeStepStoresFields() {
        RecipeStep step = new RecipeStep();
        step.setId(20L);
        step.setStepNumber(2);
        step.setOperationName("Dry Blending");
        step.setInstructions("Blend all dry powders for 20 minutes at 25 RPM.");

        assertEquals(20L, step.getId());
        assertEquals(2, step.getStepNumber());
        assertEquals("Dry Blending", step.getOperationName());
        assertEquals("Blend all dry powders for 20 minutes at 25 RPM.", step.getInstructions());

        // Parameterized constructor verification
        RecipeStep paramStep = new RecipeStep(3, "Compression", "Compress tablets to 500mg target weight.");
        assertEquals(3, paramStep.getStepNumber());
        assertEquals("Compression", paramStep.getOperationName());
        assertEquals("Compress tablets to 500mg target weight.", paramStep.getInstructions());
    }

    // -------------------------------------------------------------------------
    // 13. Equals and HashCode Verification
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("13a. Recipe equals and hashCode are based on recipeCode")
    void recipeEqualsAndHashCodeBasedOnRecipeCode() {
        Product product = createSampleProduct();

        Recipe r1 = new Recipe("RCP-PRD001-V1", product, 1, new BigDecimal("1000.0000"), "kg", RecipeStatus.DRAFT, "First");
        Recipe r2 = new Recipe("RCP-PRD001-V1", product, 2, new BigDecimal("2000.0000"), "kg", RecipeStatus.APPROVED, "Second");
        Recipe r3 = new Recipe("RCP-PRD001-V2", product, 2, new BigDecimal("1000.0000"), "kg", RecipeStatus.DRAFT, "Third");

        // Reflexive
        assertEquals(r1, r1);
        // Symmetric
        assertEquals(r1, r2);
        assertEquals(r2, r1);
        assertEquals(r1.hashCode(), r2.hashCode());

        // Non-equal
        assertNotEquals(r1, r3);
        assertNotEquals(r1, null);
        assertNotEquals(r1, "Not a recipe object");
    }

    @Test
    @DisplayName("13b. RecipeMaterial equals and hashCode evaluate ID or value properties")
    void recipeMaterialEqualsAndHashCode() {
        // Persisted entities with matching IDs
        RecipeMaterial m1 = new RecipeMaterial("API", new BigDecimal("10.0"), "kg");
        m1.setId(1L);
        RecipeMaterial m2 = new RecipeMaterial("Different Name", new BigDecimal("99.0"), "g");
        m2.setId(1L);
        assertEquals(m1, m2);

        // Unpersisted entities with null IDs match on properties
        RecipeMaterial unpersisted1 = new RecipeMaterial("Binder", new BigDecimal("5.0"), "kg");
        RecipeMaterial unpersisted2 = new RecipeMaterial("Binder", new BigDecimal("5.0"), "kg");
        RecipeMaterial unpersisted3 = new RecipeMaterial("Disintegrant", new BigDecimal("5.0"), "kg");
        assertEquals(unpersisted1, unpersisted2);
        assertEquals(unpersisted1.hashCode(), unpersisted2.hashCode());
        assertNotEquals(unpersisted1, unpersisted3);
    }

    @Test
    @DisplayName("13c. RecipeStep equals and hashCode evaluate ID or step properties")
    void recipeStepEqualsAndHashCode() {
        // Persisted entities with matching IDs
        RecipeStep s1 = new RecipeStep(1, "Blending", "Inst 1");
        s1.setId(5L);
        RecipeStep s2 = new RecipeStep(2, "Coating", "Inst 2");
        s2.setId(5L);
        assertEquals(s1, s2);

        // Unpersisted entities match on stepNumber and operationName
        RecipeStep unpersisted1 = new RecipeStep(1, "Dispensing", "Instructions A");
        RecipeStep unpersisted2 = new RecipeStep(1, "Dispensing", "Different instructions");
        RecipeStep unpersisted3 = new RecipeStep(2, "Dispensing", "Instructions A");
        assertEquals(unpersisted1, unpersisted2);
        assertEquals(unpersisted1.hashCode(), unpersisted2.hashCode());
        assertNotEquals(unpersisted1, unpersisted3);
    }

    // -------------------------------------------------------------------------
    // 14. Entity-level Validation Annotation Checks
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("14. Blank Recipe fails Bean Validation constraints")
    void blankRecipeFailsValidation() {
        Recipe recipe = new Recipe();
        // Mandatory fields are left null/empty

        Set<ConstraintViolation<Recipe>> violations = validator.validate(recipe);
        assertFalse(violations.isEmpty(), "Blank Recipe must fail Bean Validation");

        Set<String> violatedFields = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(violatedFields.contains("recipeCode"));
        assertTrue(violatedFields.contains("product"));
        assertTrue(violatedFields.contains("version"));
        assertTrue(violatedFields.contains("batchSize"));
        assertTrue(violatedFields.contains("batchUnit"));
        assertTrue(violatedFields.contains("status"));
    }
}

