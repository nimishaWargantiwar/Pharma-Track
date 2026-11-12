package com.pharmatrack;

import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.exception.InvalidQualityOperationException;
import com.pharmatrack.service.QualitySpecificationEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link QualitySpecificationEvaluator} verifying numeric acceptance specification
 * parsing (minimum, maximum, range, plus/minus tolerance), PASS/FAIL consistency enforcement,
 * and preservation of qualitative checks.
 */
class QualitySpecificationEvaluatorTest {

    @Test
    @DisplayName("Valid PASS across minimum, maximum, range, tolerance, and boundary limits")
    void validPassCases() {
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "Minimum 80% dissolution within 30 minutes", "85", QualityCheckResult.PASS));
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "Minimum 80% dissolution within 30 minutes", "80.0", QualityCheckResult.PASS));
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                ">= 80%", "82.4%", QualityCheckResult.PASS));

        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "Maximum 0.5% total impurities", "0.35", QualityCheckResult.PASS));
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "NMT 0.5%", "0.5", QualityCheckResult.PASS));

        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "95.0% - 105.0% of label claim", "99.4", QualityCheckResult.PASS));
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "6.5 – 7.5 pH", "6.5", QualityCheckResult.PASS));
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "Between 98.0 and 102.0%", "102.0", QualityCheckResult.PASS));
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "100.0 ± 5.0%", "95.0", QualityCheckResult.PASS));
    }

    @Test
    @DisplayName("Valid FAIL when measured value falls outside minimum, maximum, or range limits")
    void validFailCases() {
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "Minimum 80% dissolution within 30 minutes", "65", QualityCheckResult.FAIL));
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "> 80%", "80", QualityCheckResult.FAIL));

        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "Maximum 0.5% total impurities", "0.85", QualityCheckResult.FAIL));
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "< 10 CFU", "10", QualityCheckResult.FAIL));

        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "95.0% - 105.0% of label claim", "92.5", QualityCheckResult.FAIL));
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "95.0% - 105.0% of label claim", "106.1", QualityCheckResult.FAIL));
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "100.0 ± 5.0%", "105.1", QualityCheckResult.FAIL));
    }

    @Test
    @DisplayName("Inconsistent PASS rejected when measured value violates limit or is non-numeric")
    void inconsistentPassRejected() {
        InvalidQualityOperationException minEx = assertThrows(
                InvalidQualityOperationException.class,
                () -> QualitySpecificationEvaluator.validateConsistency(
                        "Minimum 80% dissolution within 30 minutes", "65", QualityCheckResult.PASS)
        );
        assertTrue(minEx.getMessage().contains("does not meet acceptance specification"));
        assertTrue(minEx.getMessage().contains("cannot be recorded as PASS"));

        InvalidQualityOperationException maxEx = assertThrows(
                InvalidQualityOperationException.class,
                () -> QualitySpecificationEvaluator.validateConsistency(
                        "Maximum 0.5%", "0.9", QualityCheckResult.PASS)
        );
        assertTrue(maxEx.getMessage().contains("cannot be recorded as PASS"));

        InvalidQualityOperationException rangeEx = assertThrows(
                InvalidQualityOperationException.class,
                () -> QualitySpecificationEvaluator.validateConsistency(
                        "95.0% - 105.0%", "106.5", QualityCheckResult.PASS)
        );
        assertTrue(rangeEx.getMessage().contains("cannot be recorded as PASS"));

        InvalidQualityOperationException nonNumericEx = assertThrows(
                InvalidQualityOperationException.class,
                () -> QualitySpecificationEvaluator.validateConsistency(
                        "Minimum 80% dissolution within 30 minutes", "Conforms", QualityCheckResult.PASS)
        );
        assertTrue(nonNumericEx.getMessage().contains("must be numeric"));
    }

    @Test
    @DisplayName("Inconsistent FAIL rejected when measured value satisfies minimum, maximum, or range limit")
    void inconsistentFailRejected() {
        InvalidQualityOperationException minEx = assertThrows(
                InvalidQualityOperationException.class,
                () -> QualitySpecificationEvaluator.validateConsistency(
                        "Minimum 80% dissolution within 30 minutes", "85", QualityCheckResult.FAIL)
        );
        assertTrue(minEx.getMessage().contains("satisfies acceptance specification"));
        assertTrue(minEx.getMessage().contains("cannot be recorded as FAIL"));

        InvalidQualityOperationException maxEx = assertThrows(
                InvalidQualityOperationException.class,
                () -> QualitySpecificationEvaluator.validateConsistency(
                        "Maximum 0.5%", "0.2", QualityCheckResult.FAIL)
        );
        assertTrue(maxEx.getMessage().contains("cannot be recorded as FAIL"));

        InvalidQualityOperationException rangeEx = assertThrows(
                InvalidQualityOperationException.class,
                () -> QualitySpecificationEvaluator.validateConsistency(
                        "95.0% - 105.0%", "100.0", QualityCheckResult.FAIL)
        );
        assertTrue(rangeEx.getMessage().contains("cannot be recorded as FAIL"));
    }

    @Test
    @DisplayName("Qualitative specifications without numeric limits allow both PASS and FAIL")
    void qualitativeSpecificationsAllowed() {
        assertTrue(QualitySpecificationEvaluator.parseNumericRule(
                "Uniform appearance; free from visible defects").isEmpty());

        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "Uniform appearance; free from visible defects", "Conforms", QualityCheckResult.PASS));
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "Uniform appearance; free from visible defects", "Discolored tablets observed", QualityCheckResult.FAIL));
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "Clear colorless solution", "Conforms", QualityCheckResult.PASS));
        assertDoesNotThrow(() -> QualitySpecificationEvaluator.validateConsistency(
                "Minimum 80% dissolution within 30 minutes", null, QualityCheckResult.PENDING));
    }
}

