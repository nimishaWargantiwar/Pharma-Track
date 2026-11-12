package com.pharmatrack;

import com.pharmatrack.dto.QaDispositionRequestDto;
import com.pharmatrack.dto.QualityCheckCorrectionRequestDto;
import com.pharmatrack.dto.QualityCheckRequestDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchQaReview;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.QualityCheck;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.exception.InvalidQualityOperationException;
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
 * Unit tests for Quality Management Phase 1 enums, entities, immutability lifecycle hooks,
 * and request DTO validations.
 */
class QualityEntityAndDtoTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private Batch createCompletedSampleBatch() {
        Product product = new Product("AMOX-250", "Amoxicillin Capsules", "250 mg", "Capsule", "Antibiotic", ProductStatus.ACTIVE);
        product.setId(1L);
        Recipe recipe = new Recipe("RCP-AMOX-250-V3", product, 3, new BigDecimal("250.0000"), "kg", RecipeStatus.APPROVED, "Master V3");
        recipe.setId(10L);
        ManufacturingOrder order = new ManufacturingOrder(
                "MO-2026-0001", product, recipe, 3, new BigDecimal("250.0000"), "kg", 1,
                OrderStatus.COMPLETED, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), "Run 1"
        );
        order.setId(100L);
        Batch batch = new Batch("BAT-2026-0001-01", order, new BigDecimal("250.0000"), "kg",
                BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null);
        batch.setId(500L);
        batch.setQaStatus(BatchQualityStatus.PENDING_QA);
        return batch;
    }

    @Test
    @DisplayName("1. BatchQualityStatus contains PENDING_QA, ON_HOLD, RELEASED, REJECTED")
    void batchQualityStatusContainsAllStates() {
        assertEquals(4, BatchQualityStatus.values().length);
        assertNotNull(BatchQualityStatus.valueOf("PENDING_QA"));
        assertNotNull(BatchQualityStatus.valueOf("ON_HOLD"));
        assertNotNull(BatchQualityStatus.valueOf("RELEASED"));
        assertNotNull(BatchQualityStatus.valueOf("REJECTED"));
    }

    @Test
    @DisplayName("2. QualityCheckResult contains PENDING, PASS, FAIL")
    void qualityCheckResultContainsAllStates() {
        assertEquals(3, QualityCheckResult.values().length);
        assertNotNull(QualityCheckResult.valueOf("PENDING"));
        assertNotNull(QualityCheckResult.valueOf("PASS"));
        assertNotNull(QualityCheckResult.valueOf("FAIL"));
    }

    @Test
    @DisplayName("3. Valid QualityCheck passes Bean Validation and initializes PrePersist defaults")
    void validQualityCheckPassesValidationAndPrePersist() throws Exception {
        Batch batch = createCompletedSampleBatch();
        QualityCheck check = new QualityCheck(
                batch,
                "Assay (HPLC)",
                "95.0% - 105.0%",
                "99.2",
                "%",
                QualityCheckResult.PASS,
                null,
                "A. Nair",
                null,
                "Conforms to specification"
        );

        Method onCreate = QualityCheck.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(check);

        assertNotNull(check.getCreatedAt());
        assertNotNull(check.getUpdatedAt());
        assertNotNull(check.getCheckedAt());
        assertTrue(check.isRequired());
        assertEquals(0, check.getCorrectionCount());

        Set<ConstraintViolation<QualityCheck>> violations = validator.validate(check);
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("4. Blank QualityCheck fails mandatory field validation")
    void blankQualityCheckFailsValidation() {
        QualityCheck blank = new QualityCheck();
        Set<ConstraintViolation<QualityCheck>> violations = validator.validate(blank);

        Set<String> paths = violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertTrue(paths.contains("batch"));
        assertTrue(paths.contains("testName"));
        assertTrue(paths.contains("specification"));
        assertTrue(paths.contains("unit"));
        assertTrue(paths.contains("result"));
        assertTrue(paths.contains("inspectorName"));
        assertTrue(paths.contains("checkedAt"));
    }

    @Test
    @DisplayName("5. BatchQaReview enforces Bean Validation and blocks updates via PreUpdate")
    void batchQaReviewIsImmutableAndValidated() throws Exception {
        BatchQaReview blank = new BatchQaReview();
        Set<String> paths = validator.validate(blank).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
        assertTrue(paths.contains("batch"));
        assertTrue(paths.contains("decisionStatus"));
        assertTrue(paths.contains("reviewerName"));
        assertTrue(paths.contains("rationale"));
        assertTrue(paths.contains("reviewedAt"));

        BatchQaReview valid = new BatchQaReview(
                createCompletedSampleBatch(),
                BatchQualityStatus.PENDING_QA,
                BatchQualityStatus.RELEASED,
                "Dr. Menon",
                "All QC checks passed and eBR steps verified.",
                LocalDateTime.now(),
                2, 2, 0, 0
        );
        assertTrue(validator.validate(valid).isEmpty());

        Method onUpdate = BatchQaReview.class.getDeclaredMethod("onUpdate");
        onUpdate.setAccessible(true);
        Throwable invoked = assertThrows(Exception.class, () -> onUpdate.invoke(valid));
        assertTrue(invoked.getCause() instanceof UnsupportedOperationException);

        Method onRemove = BatchQaReview.class.getDeclaredMethod("onRemove");
        onRemove.setAccessible(true);
        Throwable removed = assertThrows(Exception.class, () -> onRemove.invoke(valid));
        assertTrue(removed.getCause() instanceof UnsupportedOperationException);
    }

    @Test
    @DisplayName("6. AuditLog enforces append-only PreUpdate and PreRemove exceptions and mandatory fields")
    void auditLogIsAppendOnlyAndValidated() throws Exception {
        AuditLog blank = new AuditLog();
        Set<String> paths = validator.validate(blank).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
        assertTrue(paths.contains("entityType"));
        assertTrue(paths.contains("entityId"));
        assertTrue(paths.contains("action"));
        assertTrue(paths.contains("actorName"));
        assertTrue(paths.contains("occurredAt"));

        AuditLog entry = new AuditLog(
                "QUALITY_CHECK",
                1L,
                "BAT-2026-0001-01",
                500L,
                "QC_CHECK_CREATED",
                "A. Nair",
                null,
                "Recorded QC check",
                null,
                "{\"result\":\"PASS\"}"
        );
        Method onCreate = AuditLog.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(entry);
        assertNotNull(entry.getOccurredAt());
        assertTrue(validator.validate(entry).isEmpty());

        Method onUpdate = AuditLog.class.getDeclaredMethod("onUpdate");
        onUpdate.setAccessible(true);
        Throwable updateErr = assertThrows(Exception.class, () -> onUpdate.invoke(entry));
        assertTrue(updateErr.getCause() instanceof UnsupportedOperationException);

        Method onRemove = AuditLog.class.getDeclaredMethod("onRemove");
        onRemove.setAccessible(true);
        Throwable removeErr = assertThrows(Exception.class, () -> onRemove.invoke(entry));
        assertTrue(removeErr.getCause() instanceof UnsupportedOperationException);
    }

    @Test
    @DisplayName("7. Quality request DTOs validate mandatory inputs and field size limits")
    void requestDtosValidateMandatoryAndSizeConstraints() {
        QualityCheckRequestDto invalidCheck = new QualityCheckRequestDto(" ", "", "1", "", null, true, null, null);
        Set<String> checkViolations = validator.validate(invalidCheck).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
        assertTrue(checkViolations.contains("testName"));
        assertTrue(checkViolations.contains("specification"));
        assertTrue(checkViolations.contains("unit"));
        assertTrue(checkViolations.contains("result"));

        QualityCheckCorrectionRequestDto invalidCorrection = new QualityCheckCorrectionRequestDto(
                "Assay", "95-105%", "99.0", "%", QualityCheckResult.PASS, true, null, ""
        );
        Set<String> corrViolations = validator.validate(invalidCorrection).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
        assertTrue(corrViolations.contains("correctionReason"));

        QaDispositionRequestDto invalidDisposition = new QaDispositionRequestDto(null, "");
        Set<String> dispViolations = validator.validate(invalidDisposition).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
        assertTrue(dispViolations.contains("qaStatus"));
        assertTrue(dispViolations.contains("rationale"));

        InvalidQualityOperationException ex = new InvalidQualityOperationException("Pending QC check blocks release");
        assertEquals("Pending QC check blocks release", ex.getMessage());
    }
}

