package com.pharmatrack;

import com.pharmatrack.dto.RecipeMaterialDto;
import com.pharmatrack.dto.RecipeRequestDto;
import com.pharmatrack.dto.RecipeResponseDto;
import com.pharmatrack.dto.RecipeStatusUpdateDto;
import com.pharmatrack.dto.RecipeStepDto;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.exception.InvalidRecipeException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Recipe DTO validation rules and InvalidRecipeException.
 * Uses Jakarta Bean Validation to test constraints and nested cascade validations.
 */
class RecipeDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private RecipeRequestDto createValidRecipeRequest() {
        RecipeMaterialDto material = new RecipeMaterialDto("Active Pharmaceutical Ingredient", new BigDecimal("500.0000"), "kg");
        RecipeStepDto step = new RecipeStepDto(1, "Dispensing", "Dispense raw materials inside cleanroom airlock.");
        return new RecipeRequestDto(
                1L,
                new BigDecimal("1000.0000"),
                "kg",
                "Standard batch recipe",
                new ArrayList<>(List.of(material)),
                new ArrayList<>(List.of(step))
        );
    }

    // -------------------------------------------------------------------------
    // 1. Valid RecipeRequestDto
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. Valid RecipeRequestDto should pass all validations")
    void validRecipeRequestDtoShouldPassValidation() {
        RecipeRequestDto dto = createValidRecipeRequest();

        Set<ConstraintViolation<RecipeRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Expected valid RecipeRequestDto to have no violations");
    }

    // -------------------------------------------------------------------------
    // 2. Missing Product ID
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2. Missing productId should fail validation")
    void missingProductIdShouldFailValidation() {
        RecipeRequestDto dto = createValidRecipeRequest();
        dto.setProductId(null);

        Set<ConstraintViolation<RecipeRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("productId", violations.iterator().next().getPropertyPath().toString());
    }

    // -------------------------------------------------------------------------
    // 3. Batch Size validation (null, zero, negative)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3a. Null batchSize should fail validation")
    void nullBatchSizeShouldFailValidation() {
        RecipeRequestDto dto = createValidRecipeRequest();
        dto.setBatchSize(null);

        Set<ConstraintViolation<RecipeRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("batchSize", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("3b. Zero batchSize should fail validation")
    void zeroBatchSizeShouldFailValidation() {
        RecipeRequestDto dto = createValidRecipeRequest();
        dto.setBatchSize(BigDecimal.ZERO);

        Set<ConstraintViolation<RecipeRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("batchSize", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("3c. Negative batchSize should fail validation")
    void negativeBatchSizeShouldFailValidation() {
        RecipeRequestDto dto = createValidRecipeRequest();
        dto.setBatchSize(new BigDecimal("-100.5000"));

        Set<ConstraintViolation<RecipeRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("batchSize", violations.iterator().next().getPropertyPath().toString());
    }

    // -------------------------------------------------------------------------
    // 4. Blank Batch Unit
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("4. Blank or whitespace batchUnit should fail validation")
    void blankBatchUnitShouldFailValidation() {
        RecipeRequestDto dto = createValidRecipeRequest();
        dto.setBatchUnit("   ");

        Set<ConstraintViolation<RecipeRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("batchUnit", violations.iterator().next().getPropertyPath().toString());
    }

    // -------------------------------------------------------------------------
    // 5. Empty Materials List
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5. Empty materials list should fail validation")
    void emptyMaterialsListShouldFailValidation() {
        RecipeRequestDto dto = createValidRecipeRequest();
        dto.setMaterials(Collections.emptyList());

        Set<ConstraintViolation<RecipeRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        ConstraintViolation<RecipeRequestDto> violation = violations.iterator().next();
        assertEquals("materials", violation.getPropertyPath().toString());
        assertEquals("Recipe must contain at least one material", violation.getMessage());
    }

    // -------------------------------------------------------------------------
    // 6. Empty Steps List
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. Empty steps list should fail validation")
    void emptyStepsListShouldFailValidation() {
        RecipeRequestDto dto = createValidRecipeRequest();
        dto.setSteps(Collections.emptyList());

        Set<ConstraintViolation<RecipeRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        ConstraintViolation<RecipeRequestDto> violation = violations.iterator().next();
        assertEquals("steps", violation.getPropertyPath().toString());
        assertEquals("Recipe must contain at least one manufacturing step", violation.getMessage());
    }

    // -------------------------------------------------------------------------
    // 7. Nested Material Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("7a. Nested material with blank materialName is rejected")
    void nestedMaterialWithBlankNameShouldFail() {
        RecipeRequestDto dto = createValidRecipeRequest();
        dto.getMaterials().get(0).setMaterialName("   ");

        Set<ConstraintViolation<RecipeRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("materialName")));
    }

    @Test
    @DisplayName("7b. Nested material with zero or negative quantity is rejected")
    void nestedMaterialWithZeroOrNegativeQuantityShouldFail() {
        RecipeRequestDto dtoZero = createValidRecipeRequest();
        dtoZero.getMaterials().get(0).setQuantity(BigDecimal.ZERO);

        Set<ConstraintViolation<RecipeRequestDto>> violationsZero = validator.validate(dtoZero);
        assertFalse(violationsZero.isEmpty());
        assertTrue(violationsZero.stream().anyMatch(v -> v.getPropertyPath().toString().contains("quantity")));

        RecipeRequestDto dtoNeg = createValidRecipeRequest();
        dtoNeg.getMaterials().get(0).setQuantity(new BigDecimal("-5.0000"));

        Set<ConstraintViolation<RecipeRequestDto>> violationsNeg = validator.validate(dtoNeg);
        assertFalse(violationsNeg.isEmpty());
        assertTrue(violationsNeg.stream().anyMatch(v -> v.getPropertyPath().toString().contains("quantity")));
    }

    @Test
    @DisplayName("7c. Nested material with blank unit is rejected")
    void nestedMaterialWithBlankUnitShouldFail() {
        RecipeRequestDto dto = createValidRecipeRequest();
        dto.getMaterials().get(0).setUnit("");

        Set<ConstraintViolation<RecipeRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("unit")));
    }

    @Test
    @DisplayName("7d. Standalone RecipeMaterialDto validation behaves identically")
    void standaloneRecipeMaterialDtoValidation() {
        RecipeMaterialDto validMaterial = new RecipeMaterialDto("Starch", new BigDecimal("25.0000"), "kg");
        assertTrue(validator.validate(validMaterial).isEmpty());

        RecipeMaterialDto invalidMaterial = new RecipeMaterialDto("", new BigDecimal("-1.0"), "");
        Set<ConstraintViolation<RecipeMaterialDto>> violations = validator.validate(invalidMaterial);
        assertEquals(3, violations.size());
    }

    // -------------------------------------------------------------------------
    // 8. Nested Step Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("8a. Nested step with missing stepNumber is rejected")
    void nestedStepWithMissingStepNumberShouldFail() {
        RecipeRequestDto dto = createValidRecipeRequest();
        dto.getSteps().get(0).setStepNumber(null);

        Set<ConstraintViolation<RecipeRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("stepNumber")));
    }

    @Test
    @DisplayName("8b. Nested step with stepNumber below 1 is rejected")
    void nestedStepWithStepNumberBelowOneShouldFail() {
        RecipeRequestDto dtoZero = createValidRecipeRequest();
        dtoZero.getSteps().get(0).setStepNumber(0);

        Set<ConstraintViolation<RecipeRequestDto>> violationsZero = validator.validate(dtoZero);
        assertFalse(violationsZero.isEmpty());
        assertTrue(violationsZero.stream().anyMatch(v -> v.getPropertyPath().toString().contains("stepNumber")));

        RecipeRequestDto dtoNeg = createValidRecipeRequest();
        dtoNeg.getSteps().get(0).setStepNumber(-1);

        Set<ConstraintViolation<RecipeRequestDto>> violationsNeg = validator.validate(dtoNeg);
        assertFalse(violationsNeg.isEmpty());
        assertTrue(violationsNeg.stream().anyMatch(v -> v.getPropertyPath().toString().contains("stepNumber")));
    }

    @Test
    @DisplayName("8c. Nested step with blank operationName is rejected")
    void nestedStepWithBlankOperationNameShouldFail() {
        RecipeRequestDto dto = createValidRecipeRequest();
        dto.getSteps().get(0).setOperationName("   ");

        Set<ConstraintViolation<RecipeRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("operationName")));
    }

    @Test
    @DisplayName("8d. Nested step with blank instructions is rejected")
    void nestedStepWithBlankInstructionsShouldFail() {
        RecipeRequestDto dto = createValidRecipeRequest();
        dto.getSteps().get(0).setInstructions("");

        Set<ConstraintViolation<RecipeRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("instructions")));
    }

    @Test
    @DisplayName("8e. Standalone RecipeStepDto validation behaves identically")
    void standaloneRecipeStepDtoValidation() {
        RecipeStepDto validStep = new RecipeStepDto(1, "Blending", "Blend excipients and API for 15 minutes.");
        assertTrue(validator.validate(validStep).isEmpty());

        RecipeStepDto invalidStep = new RecipeStepDto(0, "", "");
        Set<ConstraintViolation<RecipeStepDto>> violations = validator.validate(invalidStep);
        assertEquals(3, violations.size());
    }

    // -------------------------------------------------------------------------
    // 9. RecipeStatusUpdateDto validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9. RecipeStatusUpdateDto rejects null status and accepts valid status")
    void recipeStatusUpdateDtoValidation() {
        RecipeStatusUpdateDto nullStatusDto = new RecipeStatusUpdateDto(null);
        Set<ConstraintViolation<RecipeStatusUpdateDto>> violations = validator.validate(nullStatusDto);
        assertEquals(1, violations.size());
        assertEquals("status", violations.iterator().next().getPropertyPath().toString());

        RecipeStatusUpdateDto validStatusDto = new RecipeStatusUpdateDto(RecipeStatus.APPROVED);
        assertTrue(validator.validate(validStatusDto).isEmpty());
    }

    // -------------------------------------------------------------------------
    // 10. InvalidRecipeException behavior
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("10. InvalidRecipeException preserves message and cause correctly")
    void invalidRecipeExceptionPreservesMessageAndCause() {
        InvalidRecipeException msgOnly = new InvalidRecipeException("Cannot approve obsolete recipe");
        assertEquals("Cannot approve obsolete recipe", msgOnly.getMessage());
        assertNull(msgOnly.getCause());

        Throwable cause = new IllegalStateException("Database lock timeout");
        InvalidRecipeException msgAndCause = new InvalidRecipeException("Failed to update recipe status", cause);
        assertEquals("Failed to update recipe status", msgAndCause.getMessage());
        assertSame(cause, msgAndCause.getCause());
    }

    // -------------------------------------------------------------------------
    // Additional: RecipeResponseDto integrity test
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("11. RecipeResponseDto preserves all fields and structure")
    void recipeResponseDtoPreservesFields() {
        LocalDateTime now = LocalDateTime.now();
        RecipeMaterialDto material = new RecipeMaterialDto("API", new BigDecimal("10.0"), "kg");
        RecipeStepDto step = new RecipeStepDto(1, "Step 1", "Instructions");

        RecipeResponseDto response = new RecipeResponseDto(
                100L,
                "RCP-PRD001-V1",
                1L,
                "PRD-001",
                "Paracetamol 500mg",
                1,
                new BigDecimal("500.0000"),
                "kg",
                RecipeStatus.DRAFT,
                "Initial master recipe",
                List.of(material),
                List.of(step),
                now,
                now
        );

        assertEquals(100L, response.getId());
        assertEquals("RCP-PRD001-V1", response.getRecipeCode());
        assertEquals(1L, response.getProductId());
        assertEquals("PRD-001", response.getProductCode());
        assertEquals("Paracetamol 500mg", response.getProductName());
        assertEquals(1, response.getVersion());
        assertEquals(new BigDecimal("500.0000"), response.getBatchSize());
        assertEquals("kg", response.getBatchUnit());
        assertEquals(RecipeStatus.DRAFT, response.getStatus());
        assertEquals("Initial master recipe", response.getDescription());
        assertEquals(1, response.getMaterials().size());
        assertEquals(1, response.getSteps().size());
        assertEquals(now, response.getCreatedAt());
        assertEquals(now, response.getUpdatedAt());
    }
}

