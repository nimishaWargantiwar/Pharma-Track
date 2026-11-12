package com.pharmatrack.service;

import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.exception.InvalidQualityOperationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates numeric acceptance limits embedded in QC specifications (minimum, maximum,
 * range, and plus/minus tolerance limits) and enforces consistency between the
 * measured value and the recorded {@link QualityCheckResult} ({@code PASS}/{@code FAIL}).
 * Qualitative specifications without numeric acceptance criteria are preserved without
 * numeric comparison.
 */
public final class QualitySpecificationEvaluator {

    private static final String UNIT_TOKEN =
            "(?:%|mg|mcg|ug|µg|g|kg|ml|l|ph|ppm|ppb|cfu(?:/(?:g|ml))?|eu(?:/ml)?|s|sec|min|h|hr|mm|um|µm|c|°c|nm|mv|kp|n)";

    private static final Pattern MEASURED_VALUE_PATTERN = Pattern.compile(
            "^(?:ph\\s*)?([+-]?\\d+(?:\\.\\d+)?)(?:\\s*" + UNIT_TOKEN + ")?$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PLUS_MINUS_PATTERN = Pattern.compile(
            "(?:^|\\b)([+-]?\\d+(?:\\.\\d+)?)\\s*" + UNIT_TOKEN + "?\\s*(?:±|\\+/-|\\+-)\\s*(\\d+(?:\\.\\d+)?)\\s*" + UNIT_TOKEN + "?",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BETWEEN_RANGE_PATTERN = Pattern.compile(
            "\\b(?:between|from)\\s+([+-]?\\d+(?:\\.\\d+)?)\\s*" + UNIT_TOKEN + "?\\s+(?:and|to|-|–|—)\\s+([+-]?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SYMBOLIC_RANGE_PATTERN = Pattern.compile(
            "(?:^|[\\s(:=])([+-]?\\d+(?:\\.\\d+)?)\\s*" + UNIT_TOKEN + "?\\s*(?:-|–|—|~|\\bto\\b)\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*" + UNIT_TOKEN + "?(?=$|[\\s).,;])",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SYMBOLIC_MIN_PATTERN = Pattern.compile(
            "(>=|≥|>)\\s*([+-]?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TEXT_MIN_INCLUSIVE_PATTERN = Pattern.compile(
            "\\b(?:minimum|min\\.?|at\\s+least|not\\s+less\\s+than|no\\s+less\\s+than|nlt|greater\\s+than\\s+or\\s+equal\\s+to)\\s*(?:of\\s+|[:=]\\s*)?([+-]?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TEXT_MIN_EXCLUSIVE_PATTERN = Pattern.compile(
            "\\b(?:greater\\s+than|more\\s+than|above)\\s*(?:[:=]\\s*)?([+-]?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SYMBOLIC_MAX_PATTERN = Pattern.compile(
            "(<=|≤|<)\\s*([+-]?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TEXT_MAX_INCLUSIVE_PATTERN = Pattern.compile(
            "\\b(?:maximum|max\\.?|at\\s+most|not\\s+more\\s+than|no\\s+more\\s+than|not\\s+greater\\s+than|not\\s+exceeding|nmt|less\\s+than\\s+or\\s+equal\\s+to)\\s*(?:of\\s+|[:=]\\s*)?([+-]?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TEXT_MAX_EXCLUSIVE_PATTERN = Pattern.compile(
            "\\b(?:less\\s+than|below|under)\\s*(?:[:=]\\s*)?([+-]?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE
    );

    private QualitySpecificationEvaluator() {
    }

    /**
     * Validate that a QC check's {@code result} is consistent with its {@code specification}
     * and {@code measuredValue}.
     *
     * @param specification acceptance specification text
     * @param measuredValue normalized measured value (may be null when {@code result == PENDING})
     * @param result        selected QC check outcome
     */
    public static void validateConsistency(String specification, String measuredValue, QualityCheckResult result) {
        if (result == null || result == QualityCheckResult.PENDING) {
            return;
        }

        Optional<NumericRule> parsedRule = parseNumericRule(specification);
        if (parsedRule.isEmpty()) {
            // Qualitative specification (e.g., Appearance Test) — numeric comparison is not applicable
            return;
        }

        NumericRule rule = parsedRule.get();
        Optional<BigDecimal> parsedMeasured = parseNumericMeasuredValue(measuredValue);
        if (parsedMeasured.isEmpty()) {
            throw new InvalidQualityOperationException(
                    "Measured value '" + measuredValue + "' must be numeric to evaluate against acceptance specification '"
                            + specification + "' (" + rule.describe() + ")."
            );
        }

        BigDecimal actual = parsedMeasured.get();
        boolean satisfied = rule.isSatisfiedBy(actual);

        if (result == QualityCheckResult.PASS && !satisfied) {
            throw new InvalidQualityOperationException(
                    "Measured value " + actual.stripTrailingZeros().toPlainString()
                            + " does not meet acceptance specification '" + specification
                            + "' (expected " + rule.describe() + ") and cannot be recorded as PASS."
            );
        }

        if (result == QualityCheckResult.FAIL && satisfied) {
            throw new InvalidQualityOperationException(
                    "Measured value " + actual.stripTrailingZeros().toPlainString()
                            + " satisfies acceptance specification '" + specification
                            + "' (expected " + rule.describe() + ") and cannot be recorded as FAIL."
            );
        }
    }

    /**
     * Parse a numeric acceptance rule from a specification string, if present.
     */
    public static Optional<NumericRule> parseNumericRule(String specification) {
        if (specification == null || specification.trim().isEmpty()) {
            return Optional.empty();
        }
        String spec = specification.trim();

        // 1. Plus/minus tolerance (e.g., "100.0 ± 5.0%", "100 +/- 5")
        Matcher plusMinus = PLUS_MINUS_PATTERN.matcher(spec);
        if (plusMinus.find()) {
            BigDecimal target = new BigDecimal(plusMinus.group(1));
            BigDecimal tolerance = new BigDecimal(plusMinus.group(2));
            return Optional.of(NumericRule.range(target.subtract(tolerance), true, target.add(tolerance), true));
        }

        // 2. Explicit between/from range (e.g., "between 95.0 and 105.0%")
        Matcher betweenRange = BETWEEN_RANGE_PATTERN.matcher(spec);
        if (betweenRange.find()) {
            BigDecimal lower = new BigDecimal(betweenRange.group(1));
            BigDecimal upper = new BigDecimal(betweenRange.group(2));
            if (lower.compareTo(upper) <= 0) {
                return Optional.of(NumericRule.range(lower, true, upper, true));
            }
        }

        // 3. Symbolic/hyphenated range (e.g., "95.0% - 105.0%", "6.5 – 7.5", "95 to 105")
        Matcher symbolicRange = SYMBOLIC_RANGE_PATTERN.matcher(spec);
        if (symbolicRange.find()) {
            BigDecimal lower = new BigDecimal(symbolicRange.group(1));
            BigDecimal upper = new BigDecimal(symbolicRange.group(2));
            if (lower.compareTo(upper) <= 0) {
                return Optional.of(NumericRule.range(lower, true, upper, true));
            }
        }

        // 4. Symbolic minimum (>=, ≥, >)
        Matcher symMin = SYMBOLIC_MIN_PATTERN.matcher(spec);
        if (symMin.find()) {
            String op = symMin.group(1);
            BigDecimal threshold = new BigDecimal(symMin.group(2));
            boolean inclusive = !">".equals(op);
            return Optional.of(NumericRule.minimum(threshold, inclusive));
        }

        // 5. Textual inclusive minimum (e.g., "Minimum 80% dissolution within 30 minutes", "NLT 80%", "at least 80")
        Matcher textMinInc = TEXT_MIN_INCLUSIVE_PATTERN.matcher(spec);
        if (textMinInc.find()) {
            BigDecimal threshold = new BigDecimal(textMinInc.group(1));
            return Optional.of(NumericRule.minimum(threshold, true));
        }

        // 6. Textual exclusive minimum (e.g., "greater than 80")
        Matcher textMinExc = TEXT_MIN_EXCLUSIVE_PATTERN.matcher(spec);
        if (textMinExc.find()) {
            BigDecimal threshold = new BigDecimal(textMinExc.group(1));
            return Optional.of(NumericRule.minimum(threshold, false));
        }

        // 7. Symbolic maximum (<=, ≤, <)
        Matcher symMax = SYMBOLIC_MAX_PATTERN.matcher(spec);
        if (symMax.find()) {
            String op = symMax.group(1);
            BigDecimal threshold = new BigDecimal(symMax.group(2));
            boolean inclusive = !"<".equals(op);
            return Optional.of(NumericRule.maximum(threshold, inclusive));
        }

        // 8. Textual inclusive maximum (e.g., "Maximum 0.5%", "NMT 0.5%", "not more than 0.5%")
        Matcher textMaxInc = TEXT_MAX_INCLUSIVE_PATTERN.matcher(spec);
        if (textMaxInc.find()) {
            BigDecimal threshold = new BigDecimal(textMaxInc.group(1));
            return Optional.of(NumericRule.maximum(threshold, true));
        }

        // 9. Textual exclusive maximum (e.g., "less than 10 CFU", "below 0.5%")
        Matcher textMaxExc = TEXT_MAX_EXCLUSIVE_PATTERN.matcher(spec);
        if (textMaxExc.find()) {
            BigDecimal threshold = new BigDecimal(textMaxExc.group(1));
            return Optional.of(NumericRule.maximum(threshold, false));
        }

        return Optional.empty();
    }

    /**
     * Extract a numeric {@link BigDecimal} from a measured value string (e.g., "65", "65%", "98.4 mg").
     */
    public static Optional<BigDecimal> parseNumericMeasuredValue(String measuredValue) {
        if (measuredValue == null || measuredValue.trim().isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = MEASURED_VALUE_PATTERN.matcher(measuredValue.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(matcher.group(1)));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Parsed numeric acceptance criterion supporting lower bound, upper bound, or closed/open interval.
     */
    public static final class NumericRule {
        private final BigDecimal min;
        private final boolean minInclusive;
        private final BigDecimal max;
        private final boolean maxInclusive;

        private NumericRule(BigDecimal min, boolean minInclusive, BigDecimal max, boolean maxInclusive) {
            this.min = min;
            this.minInclusive = minInclusive;
            this.max = max;
            this.maxInclusive = maxInclusive;
        }

        public static NumericRule minimum(BigDecimal min, boolean inclusive) {
            return new NumericRule(min, inclusive, null, true);
        }

        public static NumericRule maximum(BigDecimal max, boolean inclusive) {
            return new NumericRule(null, true, max, inclusive);
        }

        public static NumericRule range(BigDecimal min, boolean minInclusive, BigDecimal max, boolean maxInclusive) {
            return new NumericRule(min, minInclusive, max, maxInclusive);
        }

        public boolean isSatisfiedBy(BigDecimal value) {
            if (value == null) {
                return false;
            }
            if (min != null) {
                int cmpMin = value.compareTo(min);
                if (minInclusive ? cmpMin < 0 : cmpMin <= 0) {
                    return false;
                }
            }
            if (max != null) {
                int cmpMax = value.compareTo(max);
                if (maxInclusive ? cmpMax > 0 : cmpMax >= 0) {
                    return false;
                }
            }
            return true;
        }

        public String describe() {
            if (min != null && max != null) {
                return min.stripTrailingZeros().toPlainString() + " to " + max.stripTrailingZeros().toPlainString();
            }
            if (min != null) {
                return (minInclusive ? ">= " : "> ") + min.stripTrailingZeros().toPlainString();
            }
            if (max != null) {
                return (maxInclusive ? "<= " : "< ") + max.stripTrailingZeros().toPlainString();
            }
            return "specified criteria";
        }
    }
}

