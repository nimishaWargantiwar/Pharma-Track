package com.pharmatrack;

import com.pharmatrack.dto.BatchExecutionQueueItemDto;
import com.pharmatrack.dto.BatchExecutionStepResponseDto;
import com.pharmatrack.dto.BatchExecutionWorkspaceDto;
import com.pharmatrack.dto.CompleteExecutionStepRequestDto;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.exception.InvalidExecutionStepException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated unit tests for Production Execution DTO validations, DTO construction,
 * and {@link InvalidExecutionStepException}.
 */
class ExecutionDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private CompleteExecutionStepRequestDto createValidCompleteRequest() {
        return new CompleteExecutionStepRequestDto(
                "25 RPM / 42.5 °C",
                "Powder blend uniform with no visible agglomerates."
        );
    }

    // -------------------------------------------------------------------------
    // 1. CompleteExecutionStepRequestDto Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. Valid CompleteExecutionStepRequestDto passes all validations")
    void validCompleteExecutionStepRequestPassesValidation() {
        CompleteExecutionStepRequestDto dto = createValidCompleteRequest();

        Set<ConstraintViolation<CompleteExecutionStepRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Valid step completion request should produce no violations");
    }

    @Test
    @DisplayName("2. CompleteExecutionStepRequestDto with null optional observations passes validation")
    void completeRequestWithNullOptionalFieldsPassesValidation() {
        CompleteExecutionStepRequestDto dto = new CompleteExecutionStepRequestDto(
                "Verified",
                null
        );

        Set<ConstraintViolation<CompleteExecutionStepRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Optional observations may be null");
    }

    @Test
    @DisplayName("3. CompleteExecutionStepRequestDto accepts exact maximum field lengths (255, 2000)")
    void completeRequestAcceptsMaxBoundaryLengths() {
        CompleteExecutionStepRequestDto dto = new CompleteExecutionStepRequestDto(
                "A".repeat(255),
                "B".repeat(2000)
        );

        Set<ConstraintViolation<CompleteExecutionStepRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Boundary lengths within max constraints should pass validation");
    }

    @Test
    @DisplayName("4. Missing (null) actualValue fails validation")
    void missingActualValueFailsValidation() {
        CompleteExecutionStepRequestDto dto = createValidCompleteRequest();
        dto.setActualValue(null);

        Set<ConstraintViolation<CompleteExecutionStepRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("actualValue", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("5. Blank or empty actualValue fails validation")
    void blankActualValueFailsValidation() {
        CompleteExecutionStepRequestDto emptyDto = createValidCompleteRequest();
        emptyDto.setActualValue("");
        Set<ConstraintViolation<CompleteExecutionStepRequestDto>> emptyViolations = validator.validate(emptyDto);
        assertEquals(1, emptyViolations.size());
        assertEquals("actualValue", emptyViolations.iterator().next().getPropertyPath().toString());

        CompleteExecutionStepRequestDto whitespaceDto = createValidCompleteRequest();
        whitespaceDto.setActualValue("   ");
        Set<ConstraintViolation<CompleteExecutionStepRequestDto>> whitespaceViolations = validator.validate(whitespaceDto);
        assertEquals(1, whitespaceViolations.size());
        assertEquals("actualValue", whitespaceViolations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("6. actualValue exceeding 255 characters fails validation")
    void oversizedActualValueFailsValidation() {
        CompleteExecutionStepRequestDto dto = createValidCompleteRequest();
        dto.setActualValue("V".repeat(256));

        Set<ConstraintViolation<CompleteExecutionStepRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("actualValue", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("7. observations exceeding 2000 characters fails validation")
    void oversizedObservationsFailsValidation() {
        CompleteExecutionStepRequestDto dto = createValidCompleteRequest();
        dto.setObservations("N".repeat(2001));

        Set<ConstraintViolation<CompleteExecutionStepRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("observations", violations.iterator().next().getPropertyPath().toString());
    }

    // -------------------------------------------------------------------------
    // 2. Response DTO Construction and Field Storage
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9. BatchExecutionStepResponseDto constructs and stores all snapshot and execution fields")
    void batchExecutionStepResponseDtoStoresFields() {
        LocalDateTime started = LocalDateTime.of(2026, 11, 9, 9, 0);
        LocalDateTime completed = LocalDateTime.of(2026, 11, 9, 9, 30);

        BatchExecutionStepResponseDto dto = new BatchExecutionStepResponseDto(
                501L,
                1,
                "Dry Blending",
                "Blend dry powders in V-blender at 25 RPM for 20 minutes.",
                ExecutionStepStatus.COMPLETED,
                "25 RPM / 20 min",
                "No lumps observed",
                "J. Doe",
                started,
                completed
        );

        assertEquals(501L, dto.getId());
        assertEquals(1, dto.getStepNumber());
        assertEquals("Dry Blending", dto.getOperationName());
        assertEquals("Blend dry powders in V-blender at 25 RPM for 20 minutes.", dto.getInstructions());
        assertEquals(ExecutionStepStatus.COMPLETED, dto.getStatus());
        assertEquals("25 RPM / 20 min", dto.getActualValue());
        assertEquals("No lumps observed", dto.getObservations());
        assertEquals("J. Doe", dto.getExecutedBy());
        assertEquals(started, dto.getStartedAt());
        assertEquals(completed, dto.getCompletedAt());

        BatchExecutionStepResponseDto emptyDto = new BatchExecutionStepResponseDto();
        emptyDto.setId(502L);
        emptyDto.setStepNumber(2);
        emptyDto.setOperationName("Compression");
        emptyDto.setInstructions("Compress tablets to 500mg.");
        emptyDto.setStatus(ExecutionStepStatus.PENDING);
        assertEquals(502L, emptyDto.getId());
        assertEquals(2, emptyDto.getStepNumber());
        assertEquals("Compression", emptyDto.getOperationName());
        assertEquals("Compress tablets to 500mg.", emptyDto.getInstructions());
        assertEquals(ExecutionStepStatus.PENDING, emptyDto.getStatus());
        assertNull(emptyDto.getActualValue());
        assertNull(emptyDto.getStartedAt());
    }

    // -------------------------------------------------------------------------
    // 3. Queue & Workspace DTO Construction
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("10. BatchExecutionQueueItemDto constructs and preserves all queue metadata")
    void batchExecutionQueueItemDtoStoresFields() {
        LocalDateTime startTime = LocalDateTime.of(2026, 11, 9, 8, 15);
        BatchExecutionQueueItemDto queueItem = new BatchExecutionQueueItemDto(
                200L,
                "BAT-2026-0001-01",
                BatchStatus.IN_PROGRESS,
                "MO-2026-0001",
                "Amoxicillin Capsules",
                "RCP-AMOX-250-V3",
                3,
                new BigDecimal("250.0000"),
                "kg",
                startTime
        );

        assertEquals(200L, queueItem.getBatchId());
        assertEquals("BAT-2026-0001-01", queueItem.getBatchNumber());
        assertEquals(BatchStatus.IN_PROGRESS, queueItem.getBatchStatus());
        assertEquals("MO-2026-0001", queueItem.getOrderNumber());
        assertEquals("Amoxicillin Capsules", queueItem.getProductName());
        assertEquals("RCP-AMOX-250-V3", queueItem.getRecipeCode());
        assertEquals(3, queueItem.getRecipeVersion());
        assertEquals(new BigDecimal("250.0000"), queueItem.getBatchSize());
        assertEquals("kg", queueItem.getBatchUnit());
        assertEquals(startTime, queueItem.getStartTime());
    }

    @Test
    @DisplayName("11. BatchExecutionWorkspaceDto constructs workspace metadata and guards null step list")
    void batchExecutionWorkspaceDtoStoresFieldsAndGuardsNullSteps() {
        BatchExecutionStepResponseDto step1 = new BatchExecutionStepResponseDto(
                1L,
                1,
                "Dispensing",
                "Dispense API and excipients.",
                ExecutionStepStatus.COMPLETED,
                "250.0 kg",
                "Verified weight",
                "A. Smith",
                LocalDateTime.of(2026, 11, 9, 8, 0),
                LocalDateTime.of(2026, 11, 9, 8, 20)
        );
        BatchExecutionStepResponseDto step2 = new BatchExecutionStepResponseDto(
                2L,
                2,
                "Granulation",
                "Granulate mixture for 15 minutes.",
                ExecutionStepStatus.PENDING,
                null,
                null,
                null,
                null,
                null
        );

        BatchExecutionWorkspaceDto workspace = new BatchExecutionWorkspaceDto(
                200L,
                "BAT-2026-0001-01",
                BatchStatus.IN_PROGRESS,
                "MO-2026-0001",
                "Amoxicillin Capsules",
                "RCP-AMOX-250-V3",
                3,
                new BigDecimal("250.0000"),
                "kg",
                List.of(step1, step2)
        );

        assertEquals(200L, workspace.getBatchId());
        assertEquals("BAT-2026-0001-01", workspace.getBatchNumber());
        assertEquals(BatchStatus.IN_PROGRESS, workspace.getBatchStatus());
        assertEquals("MO-2026-0001", workspace.getOrderNumber());
        assertEquals("Amoxicillin Capsules", workspace.getProductName());
        assertEquals("RCP-AMOX-250-V3", workspace.getRecipeCode());
        assertEquals(3, workspace.getRecipeVersion());
        assertEquals(new BigDecimal("250.0000"), workspace.getBatchSize());
        assertEquals("kg", workspace.getBatchUnit());
        assertEquals(2, workspace.getExecutionSteps().size());
        assertEquals(1, workspace.getExecutionSteps().get(0).getStepNumber());
        assertEquals(2, workspace.getExecutionSteps().get(1).getStepNumber());

        // Null-safe empty list fallback
        BatchExecutionWorkspaceDto defaultWorkspace = new BatchExecutionWorkspaceDto();
        assertNotNull(defaultWorkspace.getExecutionSteps());
        assertTrue(defaultWorkspace.getExecutionSteps().isEmpty());

        defaultWorkspace.setExecutionSteps(null);
        assertNotNull(defaultWorkspace.getExecutionSteps());
        assertTrue(defaultWorkspace.getExecutionSteps().isEmpty());
    }

    // -------------------------------------------------------------------------
    // 4. InvalidExecutionStepException Construction
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("12. InvalidExecutionStepException preserves message, cause, and RuntimeException contract")
    void invalidExecutionStepExceptionPreservesMessageAndCause() {
        InvalidExecutionStepException ex = new InvalidExecutionStepException("Cannot start step 2 before step 1 is completed");
        assertTrue(ex instanceof RuntimeException);
        assertEquals("Cannot start step 2 before step 1 is completed", ex.getMessage());
        assertNull(ex.getCause());

        Throwable cause = new IllegalStateException("Batch is not IN_PROGRESS");
        InvalidExecutionStepException exWithCause = new InvalidExecutionStepException("Invalid execution transition", cause);
        assertEquals("Invalid execution transition", exWithCause.getMessage());
        assertSame(cause, exWithCause.getCause());
    }
}

