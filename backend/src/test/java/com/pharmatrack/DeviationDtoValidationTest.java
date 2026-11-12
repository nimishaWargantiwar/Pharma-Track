package com.pharmatrack;

import com.pharmatrack.dto.CancelCapaActionRequestDto;
import com.pharmatrack.dto.CloseDeviationRequestDto;
import com.pharmatrack.dto.CompleteCapaActionRequestDto;
import com.pharmatrack.dto.CreateCapaActionRequestDto;
import com.pharmatrack.dto.CreateDeviationRequestDto;
import com.pharmatrack.dto.RecordRootCauseRequestDto;
import com.pharmatrack.dto.ReopenCapaRequestDto;
import com.pharmatrack.dto.StartCapaActionRequestDto;
import com.pharmatrack.dto.StartCapaRequestDto;
import com.pharmatrack.dto.StartInvestigationRequestDto;
import com.pharmatrack.dto.SubmitVerificationRequestDto;
import com.pharmatrack.entity.CapaType;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bean Validation unit tests for all Phase 2 Deviation and CAPA request DTOs.
 */
class DeviationDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private Set<String> violatedFields(Object dto) {
        return validator.validate(dto).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("1. CreateDeviationRequestDto validates mandatory fields and field size bounds")
    void createDeviationDtoValidation() {
        CreateDeviationRequestDto valid = new CreateDeviationRequestDto(
                10L, 2L, null, null,
                "Dissolution test out-of-specification",
                DeviationCategory.QC_OOS_FAILURE,
                DeviationSeverity.CRITICAL,
                DeviationPriority.HIGH,
                "Measured 65% vs minimum 80% specification.",
                "Placed batch under physical quarantine.",
                LocalDateTime.of(2026, 11, 10, 12, 0)
        );
        assertTrue(validator.validate(valid).isEmpty());

        CreateDeviationRequestDto invalid = new CreateDeviationRequestDto(
                null, null, null, null,
                " ", null, null, null,
                "", "C".repeat(2001), null
        );
        Set<String> fields = violatedFields(invalid);
        assertTrue(fields.contains("batchId"));
        assertTrue(fields.contains("title"));
        assertTrue(fields.contains("category"));
        assertTrue(fields.contains("severity"));
        assertTrue(fields.contains("priority"));
        assertTrue(fields.contains("description"));
        assertTrue(fields.contains("immediateContainment"));
    }

    @Test
    @DisplayName("2. Investigation, Root Cause, Start CAPA, Verification, Reopen, and Closure DTOs enforce mandatory fields")
    void lifecycleDtosValidation() {
        StartInvestigationRequestDto invalidInv = new StartInvestigationRequestDto("");
        Set<String> invFields = violatedFields(invalidInv);
        assertTrue(invFields.contains("investigationSummary"));
        assertTrue(validator.validate(new StartInvestigationRequestDto("Reviewing blending parameters.")).isEmpty());

        RecordRootCauseRequestDto invalidRca = new RecordRootCauseRequestDto("", " ", " ", "S".repeat(3001));
        Set<String> rcaFields = violatedFields(invalidRca);
        assertTrue(rcaFields.contains("rootCauseCategory"));
        assertTrue(rcaFields.contains("rootCauseAnalysis"));
        assertTrue(rcaFields.contains("impactAssessment"));
        assertTrue(rcaFields.contains("investigationSummary"));
        assertTrue(validator.validate(new RecordRootCauseRequestDto(
                "Equipment Calibration", "Compression force sensor drifted -12%.",
                "Affects Batch BAT-2026-0001-01 only.", null
        )).isEmpty());

        Set<String> startCapaFields = violatedFields(new StartCapaRequestDto("P".repeat(2001)));
        assertTrue(startCapaFields.contains("capaPlanSummary"));
        assertTrue(validator.validate(new StartCapaRequestDto("Executing CAPA plan")).isEmpty());

        Set<String> submitFields = violatedFields(new SubmitVerificationRequestDto("N".repeat(2001)));
        assertTrue(submitFields.contains("submissionNotes"));
        assertTrue(validator.validate(new SubmitVerificationRequestDto("CAPAs done")).isEmpty());

        Set<String> reopenFields = violatedFields(new ReopenCapaRequestDto(" "));
        assertTrue(reopenFields.contains("reason"));

        Set<String> closeFields = violatedFields(new CloseDeviationRequestDto(""));
        assertTrue(closeFields.contains("verificationNotes"));
        assertTrue(validator.validate(new CloseDeviationRequestDto("Verified CAPA evidence.")).isEmpty());
    }

    @Test
    @DisplayName("3. CAPA create, start, complete, and cancel DTOs enforce mandatory fields (retaining ownerName)")
    void capaDtosValidation() {
        CreateCapaActionRequestDto invalidCreate = new CreateCapaActionRequestDto(
                null, " ", "", " ", null
        );
        Set<String> createFields = violatedFields(invalidCreate);
        assertTrue(createFields.contains("capaType"));
        assertTrue(createFields.contains("title"));
        assertTrue(createFields.contains("actionPlan"));
        assertTrue(createFields.contains("ownerName"));
        assertTrue(createFields.contains("dueDate"));

        assertTrue(validator.validate(new CreateCapaActionRequestDto(
                CapaType.CORRECTIVE, "Calibrate press", "Perform 3-point calibration.",
                "M. Rao", LocalDate.of(2026, 11, 20)
        )).isEmpty());

        assertTrue(violatedFields(new StartCapaActionRequestDto("N".repeat(1001))).contains("notes"));
        assertTrue(violatedFields(new CompleteCapaActionRequestDto("")).contains("completionEvidence"));
        assertTrue(violatedFields(new CancelCapaActionRequestDto(" ")).contains("cancellationReason"));
    }
}

