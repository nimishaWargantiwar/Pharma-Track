package com.pharmatrack;

import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeMaterial;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.repository.RecipeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link RecipeRepository} using Spring Boot's @DataJpaTest
 * and an isolated in-memory H2 database.
 */
@DataJpaTest
class RecipeRepositoryTest {

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Product createAndPersistProduct(String productCode, String productName) {
        Product product = new Product(
                productCode,
                productName,
                "500 mg",
                "Tablet",
                "Pharmaceutical master product",
                ProductStatus.ACTIVE
        );
        return entityManager.persistAndFlush(product);
    }

    private Recipe createBaseRecipe(Product product, String recipeCode, Integer version, RecipeStatus status) {
        return new Recipe(
                recipeCode,
                product,
                version,
                new BigDecimal("1000.0000"),
                "kg",
                status,
                "Master recipe description"
        );
    }

    // -------------------------------------------------------------------------
    // 1. Saving and Retrieving a Recipe
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should save a recipe and populate ID, audit timestamps, and defaults")
    void shouldSaveAndRetrieveRecipe() {
        Product product = createAndPersistProduct("PRD-001", "Paracetamol");
        Recipe recipe = createBaseRecipe(product, "RCP-PRD001-V1", 1, RecipeStatus.DRAFT);

        Recipe saved = recipeRepository.saveAndFlush(recipe);

        assertNotNull(saved.getId(), "Generated ID must not be null");
        assertNotNull(saved.getCreatedAt(), "CreatedAt must be populated");
        assertNotNull(saved.getUpdatedAt(), "UpdatedAt must be populated");
        assertEquals("RCP-PRD001-V1", saved.getRecipeCode());
        assertEquals(1, saved.getVersion());
        assertEquals(RecipeStatus.DRAFT, saved.getStatus());
        assertEquals(new BigDecimal("1000.0000"), saved.getBatchSize());
        assertEquals("kg", saved.getBatchUnit());

        Optional<Recipe> retrieved = recipeRepository.findById(saved.getId());
        assertTrue(retrieved.isPresent(), "Recipe should be retrievable by ID");
        assertEquals("PRD-001", retrieved.get().getProduct().getProductCode());
    }

    // -------------------------------------------------------------------------
    // 2. Cascade Persistence for Materials and Steps
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should save a recipe with multiple materials and steps using cascading")
    void shouldCascadeSaveMaterialsAndSteps() {
        Product product = createAndPersistProduct("PRD-002", "Amoxicillin");
        Recipe recipe = createBaseRecipe(product, "RCP-PRD002-V1", 1, RecipeStatus.DRAFT);

        RecipeMaterial mat1 = new RecipeMaterial("Amoxicillin Trihydrate API", new BigDecimal("500.0000"), "kg");
        RecipeMaterial mat2 = new RecipeMaterial("Microcrystalline Cellulose", new BigDecimal("200.0000"), "kg");
        recipe.addMaterial(mat1);
        recipe.addMaterial(mat2);

        RecipeStep step1 = new RecipeStep(1, "Dispensing", "Dispense API and excipients.");
        RecipeStep step2 = new RecipeStep(2, "Blending", "Dry blend powders in V-blender for 20 mins.");
        recipe.addStep(step1);
        recipe.addStep(step2);

        Recipe saved = recipeRepository.saveAndFlush(recipe);
        Long savedId = saved.getId();

        entityManager.clear(); // Clear L1 cache to ensure fresh retrieval from database

        Recipe retrieved = recipeRepository.findById(savedId).orElseThrow();
        assertEquals(2, retrieved.getMaterials().size(), "Both materials must be cascade-persisted");
        assertNotNull(retrieved.getMaterials().get(0).getId(), "Material ID must be generated");
        assertNotNull(retrieved.getMaterials().get(1).getId(), "Material ID must be generated");

        assertEquals(2, retrieved.getSteps().size(), "Both steps must be cascade-persisted");
        assertNotNull(retrieved.getSteps().get(0).getId(), "Step ID must be generated");
        assertNotNull(retrieved.getSteps().get(1).getId(), "Step ID must be generated");
    }

    // -------------------------------------------------------------------------
    // 3. Retrieving Recipes by Product ID
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should retrieve recipes by product ID")
    void shouldRetrieveRecipesByProductId() {
        Product productA = createAndPersistProduct("PRD-A", "Product A");
        Product productB = createAndPersistProduct("PRD-B", "Product B");

        Recipe recipeA1 = createBaseRecipe(productA, "RCP-A-V1", 1, RecipeStatus.APPROVED);
        Recipe recipeA2 = createBaseRecipe(productA, "RCP-A-V2", 2, RecipeStatus.DRAFT);
        Recipe recipeB1 = createBaseRecipe(productB, "RCP-B-V1", 1, RecipeStatus.APPROVED);

        entityManager.persist(recipeA1);
        entityManager.persist(recipeA2);
        entityManager.persist(recipeB1);
        entityManager.flush();

        List<Recipe> recipesForA = recipeRepository.findByProductId(productA.getId());
        assertEquals(2, recipesForA.size(), "Product A should have 2 recipes");
        assertTrue(recipesForA.stream().allMatch(r -> r.getProduct().getId().equals(productA.getId())));

        List<Recipe> recipesForB = recipeRepository.findByProductId(productB.getId());
        assertEquals(1, recipesForB.size(), "Product B should have 1 recipe");
        assertEquals("RCP-B-V1", recipesForB.get(0).getRecipeCode());
    }

    // -------------------------------------------------------------------------
    // 4. Retrieving Recipes by Product ID and Status
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should retrieve recipes by product ID and status")
    void shouldRetrieveRecipesByProductIdAndStatus() {
        Product product = createAndPersistProduct("PRD-STATUS", "Status Test Product");

        Recipe draft = createBaseRecipe(product, "RCP-STAT-V1", 1, RecipeStatus.DRAFT);
        Recipe approved = createBaseRecipe(product, "RCP-STAT-V2", 2, RecipeStatus.APPROVED);
        Recipe obsolete = createBaseRecipe(product, "RCP-STAT-V3", 3, RecipeStatus.OBSOLETE);

        entityManager.persist(draft);
        entityManager.persist(approved);
        entityManager.persist(obsolete);
        entityManager.flush();

        List<Recipe> approvedList = recipeRepository.findByProductIdAndStatus(product.getId(), RecipeStatus.APPROVED);
        assertEquals(1, approvedList.size());
        assertEquals("RCP-STAT-V2", approvedList.get(0).getRecipeCode());

        List<Recipe> draftList = recipeRepository.findByProductIdAndStatus(product.getId(), RecipeStatus.DRAFT);
        assertEquals(1, draftList.size());
        assertEquals("RCP-STAT-V1", draftList.get(0).getRecipeCode());

        List<Recipe> obsoleteList = recipeRepository.findByProductIdAndStatus(product.getId(), RecipeStatus.OBSOLETE);
        assertEquals(1, obsoleteList.size());
        assertEquals("RCP-STAT-V3", obsoleteList.get(0).getRecipeCode());
    }

    // -------------------------------------------------------------------------
    // 5. Finding the Latest Version for a Product
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should find the latest version for a product")
    void shouldFindLatestVersionForProduct() {
        Product product = createAndPersistProduct("PRD-VER", "Version Test Product");

        Recipe v1 = createBaseRecipe(product, "RCP-VER-V1", 1, RecipeStatus.OBSOLETE);
        Recipe v2 = createBaseRecipe(product, "RCP-VER-V2", 2, RecipeStatus.APPROVED);
        Recipe v3 = createBaseRecipe(product, "RCP-VER-V3", 3, RecipeStatus.DRAFT);

        entityManager.persist(v1);
        entityManager.persist(v2);
        entityManager.persist(v3);
        entityManager.flush();

        Optional<Recipe> latestRecipe = recipeRepository.findFirstByProductIdOrderByVersionDesc(product.getId());
        assertTrue(latestRecipe.isPresent());
        assertEquals(3, latestRecipe.get().getVersion());
        assertEquals("RCP-VER-V3", latestRecipe.get().getRecipeCode());

        Optional<Integer> maxVersion = recipeRepository.findMaxVersionByProductId(product.getId());
        assertTrue(maxVersion.isPresent());
        assertEquals(3, maxVersion.get());

        // For a product with no recipes
        Product emptyProduct = createAndPersistProduct("PRD-NO-RCP", "Empty Product");
        Optional<Recipe> noRecipe = recipeRepository.findFirstByProductIdOrderByVersionDesc(emptyProduct.getId());
        assertTrue(noRecipe.isEmpty());

        Optional<Integer> noMaxVersion = recipeRepository.findMaxVersionByProductId(emptyProduct.getId());
        assertTrue(noMaxVersion.isEmpty());
    }

    // -------------------------------------------------------------------------
    // 6. Rejecting Duplicate Recipe Codes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should reject duplicate recipe codes with DataIntegrityViolationException")
    void shouldRejectDuplicateRecipeCode() {
        Product product1 = createAndPersistProduct("PRD-C1", "Product 1");
        Product product2 = createAndPersistProduct("PRD-C2", "Product 2");

        Recipe recipe1 = createBaseRecipe(product1, "RCP-UNIQUE-CODE", 1, RecipeStatus.DRAFT);
        entityManager.persistAndFlush(recipe1);

        Recipe duplicateCodeRecipe = createBaseRecipe(product2, "RCP-UNIQUE-CODE", 1, RecipeStatus.DRAFT);

        assertThrows(DataIntegrityViolationException.class, () -> {
            recipeRepository.saveAndFlush(duplicateCodeRecipe);
        }, "Unique constraint 'uk_recipe_code' must reject duplicate recipe_code");
    }

    // -------------------------------------------------------------------------
    // 7. Rejecting Duplicate (product_id, version) Combinations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should reject duplicate (product_id, version) combinations with DataIntegrityViolationException")
    void shouldRejectDuplicateProductVersion() {
        Product product = createAndPersistProduct("PRD-V-DUP", "Version Conflict Product");

        Recipe version1 = createBaseRecipe(product, "RCP-PRD-V1", 1, RecipeStatus.APPROVED);
        entityManager.persistAndFlush(version1);

        Recipe duplicateVersion = createBaseRecipe(product, "RCP-PRD-V1-DUP", 1, RecipeStatus.DRAFT);

        assertThrows(DataIntegrityViolationException.class, () -> {
            recipeRepository.saveAndFlush(duplicateVersion);
        }, "Composite unique constraint 'uk_recipe_product_version' must reject duplicate (product_id, version)");
    }

    // -------------------------------------------------------------------------
    // 8. Verifying Step Ordering (@OrderBy)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should maintain step ordering ascending by stepNumber when retrieving recipe")
    void shouldMaintainStepOrderingAscending() {
        Product product = createAndPersistProduct("PRD-ORDER", "Step Order Product");
        Recipe recipe = createBaseRecipe(product, "RCP-ORDER-V1", 1, RecipeStatus.DRAFT);

        // Add steps intentionally out of order: Step 3, Step 1, Step 2
        RecipeStep step3 = new RecipeStep(3, "Coating", "Film coating in pan coater.");
        RecipeStep step1 = new RecipeStep(1, "Dispensing", "Dispense raw materials.");
        RecipeStep step2 = new RecipeStep(2, "Blending", "Blend powders in bin blender.");

        recipe.addStep(step3);
        recipe.addStep(step1);
        recipe.addStep(step2);

        Recipe saved = recipeRepository.saveAndFlush(recipe);
        Long recipeId = saved.getId();

        entityManager.clear(); // Clear cache to reload entity from DB

        Recipe retrieved = recipeRepository.findById(recipeId).orElseThrow();
        List<RecipeStep> steps = retrieved.getSteps();

        assertEquals(3, steps.size());
        assertEquals(1, steps.get(0).getStepNumber());
        assertEquals("Dispensing", steps.get(0).getOperationName());

        assertEquals(2, steps.get(1).getStepNumber());
        assertEquals("Blending", steps.get(1).getOperationName());

        assertEquals(3, steps.get(2).getStepNumber());
        assertEquals("Coating", steps.get(2).getOperationName());
    }

    // -------------------------------------------------------------------------
    // 9. Verifying Orphan Removal for Materials and Steps
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should remove child records from database when removed from collections (orphan removal)")
    void shouldRemoveOrphansWhenChildRemovedFromCollection() {
        Product product = createAndPersistProduct("PRD-ORPHAN", "Orphan Removal Product");
        Recipe recipe = createBaseRecipe(product, "RCP-ORPHAN-V1", 1, RecipeStatus.DRAFT);

        RecipeMaterial mat1 = new RecipeMaterial("Material 1", new BigDecimal("10.0000"), "kg");
        RecipeMaterial mat2 = new RecipeMaterial("Material 2", new BigDecimal("20.0000"), "kg");
        recipe.addMaterial(mat1);
        recipe.addMaterial(mat2);

        RecipeStep step1 = new RecipeStep(1, "Step 1", "Instructions 1");
        RecipeStep step2 = new RecipeStep(2, "Step 2", "Instructions 2");
        recipe.addStep(step1);
        recipe.addStep(step2);

        Recipe saved = recipeRepository.saveAndFlush(recipe);
        Long recipeId = saved.getId();

        entityManager.clear();

        // Reload recipe
        Recipe managedRecipe = recipeRepository.findById(recipeId).orElseThrow();
        assertEquals(2, managedRecipe.getMaterials().size());
        assertEquals(2, managedRecipe.getSteps().size());

        Long removedMaterialId = managedRecipe.getMaterials().get(0).getId();
        Long removedStepId = managedRecipe.getSteps().get(0).getId();

        // Remove first material and first step using helper methods
        managedRecipe.removeMaterial(managedRecipe.getMaterials().get(0));
        managedRecipe.removeStep(managedRecipe.getSteps().get(0));

        // Flush changes through repository
        recipeRepository.saveAndFlush(managedRecipe);
        entityManager.clear();

        // Verify orphan records were deleted from the database
        RecipeMaterial orphanMaterial = entityManager.find(RecipeMaterial.class, removedMaterialId);
        assertNull(orphanMaterial, "Orphaned material record should be deleted from database");

        RecipeStep orphanStep = entityManager.find(RecipeStep.class, removedStepId);
        assertNull(orphanStep, "Orphaned step record should be deleted from database");

        // Verify remaining recipe state
        Recipe updatedRecipe = recipeRepository.findById(recipeId).orElseThrow();
        assertEquals(1, updatedRecipe.getMaterials().size(), "Only 1 material should remain");
        assertEquals(1, updatedRecipe.getSteps().size(), "Only 1 step should remain");
    }

    // -------------------------------------------------------------------------
    // 10. Recipe Code Query and Existence Check
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should find recipe by code and check existence")
    void shouldFindRecipeByCodeAndCheckExistence() {
        Product product = createAndPersistProduct("PRD-QUERY", "Query Test Product");
        Recipe recipe = createBaseRecipe(product, "RCP-QUERY-V1", 1, RecipeStatus.DRAFT);
        entityManager.persistAndFlush(recipe);

        Optional<Recipe> found = recipeRepository.findByRecipeCode("RCP-QUERY-V1");
        assertTrue(found.isPresent());
        assertEquals("PRD-QUERY", found.get().getProduct().getProductCode());

        Optional<Recipe> notFound = recipeRepository.findByRecipeCode("RCP-NONEXISTENT");
        assertTrue(notFound.isEmpty());

        assertTrue(recipeRepository.existsByRecipeCode("RCP-QUERY-V1"));
        assertFalse(recipeRepository.existsByRecipeCode("RCP-NONEXISTENT"));
    }

    @Test
    @DisplayName("Reproduce: clearing and adding steps with same step_number causes unique constraint violation without flush")
    void shouldFailWhenClearingAndAddingStepsWithoutFlush() {
        Product product = createAndPersistProduct("PRD-REPRO", "Repro Product");
        Recipe recipe = createBaseRecipe(product, "RCP-REPRO-V1", 1, RecipeStatus.DRAFT);

        recipe.addStep(new RecipeStep(1, "Old Step 1", "Old instructions 1"));
        recipe.addStep(new RecipeStep(2, "Old Step 2", "Old instructions 2"));

        Recipe saved = recipeRepository.saveAndFlush(recipe);
        Long recipeId = saved.getId();
        entityManager.clear();

        Recipe managed = recipeRepository.findById(recipeId).orElseThrow();
        assertEquals(2, managed.getSteps().size());

        // Clear and add new steps with same step numbers (1 and 2) without intermediate flush
        managed.getSteps().clear();
        managed.addStep(new RecipeStep(1, "New Step 1", "New instructions 1"));
        managed.addStep(new RecipeStep(2, "New Step 2", "New instructions 2"));

        // Hibernate queues inserts BEFORE deletes, resulting in unique constraint violation on uk_recipe_step
        assertThrows(DataIntegrityViolationException.class, () -> {
            recipeRepository.saveAndFlush(managed);
        });
    }

    @Test
    @DisplayName("Should update draft recipe replacing existing steps and materials without unique constraint violation when flushed")
    void shouldUpdateDraftRecipeReplacingExistingStepsWithoutUniqueConstraintViolation() {
        Product product = createAndPersistProduct("PRD-UPDATE-STEPS", "Update Steps Product");
        Recipe recipe = createBaseRecipe(product, "RCP-UPDATE-STEPS-V1", 1, RecipeStatus.DRAFT);

        RecipeMaterial mat1 = new RecipeMaterial("Old Material A", new BigDecimal("10.0000"), "kg");
        RecipeMaterial mat2 = new RecipeMaterial("Old Material B", new BigDecimal("20.0000"), "kg");
        recipe.addMaterial(mat1);
        recipe.addMaterial(mat2);

        recipe.addStep(new RecipeStep(1, "Old Step 1", "Old instructions 1"));
        recipe.addStep(new RecipeStep(2, "Old Step 2", "Old instructions 2"));

        Recipe saved = recipeRepository.saveAndFlush(recipe);
        Long recipeId = saved.getId();
        Long oldMat1Id = saved.getMaterials().get(0).getId();
        Long oldMat2Id = saved.getMaterials().get(1).getId();
        Long oldStep1Id = saved.getSteps().get(0).getId();
        Long oldStep2Id = saved.getSteps().get(1).getId();

        entityManager.clear();

        // Load managed recipe
        Recipe managed = recipeRepository.findById(recipeId).orElseThrow();
        assertEquals(2, managed.getSteps().size());
        assertEquals(2, managed.getMaterials().size());

        // Clear existing collections and flush immediately to delete old child records from DB
        managed.getMaterials().clear();
        managed.getSteps().clear();
        recipeRepository.flush();

        // Add replacement materials and steps (same step_numbers 1 and 2)
        RecipeMaterial newMat = new RecipeMaterial("Replacement Material C", new BigDecimal("50.0000"), "kg");
        managed.addMaterial(newMat);

        managed.addStep(new RecipeStep(1, "Replacement Step 1", "Updated instructions for step 1"));
        managed.addStep(new RecipeStep(2, "Replacement Step 2", "Updated instructions for step 2"));

        Recipe updated = recipeRepository.saveAndFlush(managed);
        assertNotNull(updated);

        entityManager.clear();

        // Verify reloaded state
        Recipe reloaded = recipeRepository.findById(recipeId).orElseThrow();
        assertEquals(recipeId, reloaded.getId());
        assertEquals("RCP-UPDATE-STEPS-V1", reloaded.getRecipeCode());
        assertEquals(1, reloaded.getVersion());
        assertEquals(RecipeStatus.DRAFT, reloaded.getStatus());

        // Verify steps
        assertEquals(2, reloaded.getSteps().size());
        assertEquals(1, reloaded.getSteps().get(0).getStepNumber());
        assertEquals("Replacement Step 1", reloaded.getSteps().get(0).getOperationName());
        assertEquals("Updated instructions for step 1", reloaded.getSteps().get(0).getInstructions());
        assertEquals(2, reloaded.getSteps().get(1).getStepNumber());
        assertEquals("Replacement Step 2", reloaded.getSteps().get(1).getOperationName());
        assertEquals("Updated instructions for step 2", reloaded.getSteps().get(1).getInstructions());

        // Verify materials
        assertEquals(1, reloaded.getMaterials().size());
        assertEquals("Replacement Material C", reloaded.getMaterials().get(0).getMaterialName());
        assertEquals(new BigDecimal("50.0000"), reloaded.getMaterials().get(0).getQuantity());

        // Verify old entities were deleted
        assertNull(entityManager.find(RecipeStep.class, oldStep1Id));
        assertNull(entityManager.find(RecipeStep.class, oldStep2Id));
        assertNull(entityManager.find(RecipeMaterial.class, oldMat1Id));
        assertNull(entityManager.find(RecipeMaterial.class, oldMat2Id));
    }
}

