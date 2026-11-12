const UNIT_TOKEN =
  '(?:%|mg|mcg|ug|µg|g|kg|ml|l|ph|ppm|ppb|cfu(?:/(?:g|ml))?|eu(?:/ml)?|s|sec|min|h|hr|mm|um|µm|c|°c|nm|mv|kp|n)';

const MEASURED_VALUE_REGEX = new RegExp(
  `^(?:ph\\s*)?([+-]?\\d+(?:\\.\\d+)?)(?:\\s*${UNIT_TOKEN})?$`,
  'i'
);

const PLUS_MINUS_REGEX = new RegExp(
  `(?:^|\\b)([+-]?\\d+(?:\\.\\d+)?)\\s*${UNIT_TOKEN}?\\s*(?:±|\\+\\/-|\\+-)\\s*(\\d+(?:\\.\\d+)?)\\s*${UNIT_TOKEN}?`,
  'i'
);

const BETWEEN_RANGE_REGEX = new RegExp(
  `\\b(?:between|from)\\s+([+-]?\\d+(?:\\.\\d+)?)\\s*${UNIT_TOKEN}?\\s+(?:and|to|-|–|—)\\s+([+-]?\\d+(?:\\.\\d+)?)`,
  'i'
);

const SYMBOLIC_RANGE_REGEX = new RegExp(
  `(?:^|[\\s(:=])([+-]?\\d+(?:\\.\\d+)?)\\s*${UNIT_TOKEN}?\\s*(?:-|–|—|~|\\bto\\b)\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*${UNIT_TOKEN}?(?=$|[\\s).,;])`,
  'i'
);

const SYMBOLIC_MIN_REGEX = /(>=|≥|>)\s*([+-]?\d+(?:\.\d+)?)/i;
const TEXT_MIN_INC_REGEX =
  /\b(?:minimum|min\.?|at\s+least|not\s+less\s+than|no\s+less\s+than|nlt|greater\s+than\s+or\s+equal\s+to)\s*(?:of\s+|[:=]\s*)?([+-]?\d+(?:\.\d+)?)/i;
const TEXT_MIN_EXC_REGEX =
  /\b(?:greater\s+than|more\s+than|above)\s*(?:[:=]\s*)?([+-]?\d+(?:\.\d+)?)/i;

const SYMBOLIC_MAX_REGEX = /(<=|≤|<)\s*([+-]?\d+(?:\.\d+)?)/i;
const TEXT_MAX_INC_REGEX =
  /\b(?:maximum|max\.?|at\s+most|not\s+more\s+than|no\s+more\s+than|not\s+greater\s+than|not\s+exceeding|nmt|less\s+than\s+or\s+equal\s+to)\s*(?:of\s+|[:=]\s*)?([+-]?\d+(?:\.\d+)?)/i;
const TEXT_MAX_EXC_REGEX =
  /\b(?:less\s+than|below|under)\s*(?:[:=]\s*)?([+-]?\d+(?:\.\d+)?)/i;

/**
 * Parse numeric acceptance limits (minimum, maximum, range, or +/- tolerance)
 * from a QC acceptance specification string. Returns null for qualitative specs.
 */
export function parseNumericSpecification(specification) {
  if (!specification || typeof specification !== 'string' || !specification.trim()) {
    return null;
  }
  const spec = specification.trim();

  const plusMinus = spec.match(PLUS_MINUS_REGEX);
  if (plusMinus) {
    const target = Number(plusMinus[1]);
    const tol = Number(plusMinus[2]);
    if (Number.isFinite(target) && Number.isFinite(tol)) {
      const min = Number((target - tol).toPrecision(12));
      const max = Number((target + tol).toPrecision(12));
      return {
        min,
        minInclusive: true,
        max,
        maxInclusive: true,
        describe: `${min} to ${max}`,
      };
    }
  }

  const betweenRange = spec.match(BETWEEN_RANGE_REGEX);
  if (betweenRange) {
    const min = Number(betweenRange[1]);
    const max = Number(betweenRange[2]);
    if (Number.isFinite(min) && Number.isFinite(max) && min <= max) {
      return {
        min,
        minInclusive: true,
        max,
        maxInclusive: true,
        describe: `${min} to ${max}`,
      };
    }
  }

  const symRange = spec.match(SYMBOLIC_RANGE_REGEX);
  if (symRange) {
    const min = Number(symRange[1]);
    const max = Number(symRange[2]);
    if (Number.isFinite(min) && Number.isFinite(max) && min <= max) {
      return {
        min,
        minInclusive: true,
        max,
        maxInclusive: true,
        describe: `${min} to ${max}`,
      };
    }
  }

  const symMin = spec.match(SYMBOLIC_MIN_REGEX);
  if (symMin) {
    const op = symMin[1];
    const min = Number(symMin[2]);
    if (Number.isFinite(min)) {
      const minInclusive = op !== '>';
      return {
        min,
        minInclusive,
        max: null,
        maxInclusive: true,
        describe: `${minInclusive ? '>=' : '>'} ${min}`,
      };
    }
  }

  const textMinInc = spec.match(TEXT_MIN_INC_REGEX);
  if (textMinInc) {
    const min = Number(textMinInc[1]);
    if (Number.isFinite(min)) {
      return {
        min,
        minInclusive: true,
        max: null,
        maxInclusive: true,
        describe: `>= ${min}`,
      };
    }
  }

  const textMinExc = spec.match(TEXT_MIN_EXC_REGEX);
  if (textMinExc) {
    const min = Number(textMinExc[1]);
    if (Number.isFinite(min)) {
      return {
        min,
        minInclusive: false,
        max: null,
        maxInclusive: true,
        describe: `> ${min}`,
      };
    }
  }

  const symMax = spec.match(SYMBOLIC_MAX_REGEX);
  if (symMax) {
    const op = symMax[1];
    const max = Number(symMax[2]);
    if (Number.isFinite(max)) {
      const maxInclusive = op !== '<';
      return {
        min: null,
        minInclusive: true,
        max,
        maxInclusive,
        describe: `${maxInclusive ? '<=' : '<'} ${max}`,
      };
    }
  }

  const textMaxInc = spec.match(TEXT_MAX_INC_REGEX);
  if (textMaxInc) {
    const max = Number(textMaxInc[1]);
    if (Number.isFinite(max)) {
      return {
        min: null,
        minInclusive: true,
        max,
        maxInclusive: true,
        describe: `<= ${max}`,
      };
    }
  }

  const textMaxExc = spec.match(TEXT_MAX_EXC_REGEX);
  if (textMaxExc) {
    const max = Number(textMaxExc[1]);
    if (Number.isFinite(max)) {
      return {
        min: null,
        minInclusive: true,
        max,
        maxInclusive: false,
        describe: `< ${max}`,
      };
    }
  }

  return null;
}

/**
 * Parse a numeric value from a measuredValue input (e.g., "65", "65%", "98.4 mg").
 */
export function parseNumericMeasuredValue(measuredValue) {
  if (!measuredValue || typeof measuredValue !== 'string' || !measuredValue.trim()) {
    return null;
  }
  const match = measuredValue.trim().match(MEASURED_VALUE_REGEX);
  if (!match) {
    return null;
  }
  const num = Number(match[1]);
  return Number.isFinite(num) ? num : null;
}

export function isValueWithinRule(rule, numericValue) {
  if (!rule || numericValue === null || !Number.isFinite(numericValue)) {
    return false;
  }
  if (rule.min !== null) {
    if (rule.minInclusive ? numericValue < rule.min : numericValue <= rule.min) {
      return false;
    }
  }
  if (rule.max !== null) {
    if (rule.maxInclusive ? numericValue > rule.max : numericValue >= rule.max) {
      return false;
    }
  }
  return true;
}

/**
 * Evaluate consistency between acceptance specification, measured value, and selected QC result.
 */
export function evaluateQcConsistency(specification, measuredValue, result) {
  const rule = parseNumericSpecification(specification);
  if (!rule) {
    return {
      isNumericSpec: false,
      rule: null,
      numericMeasured: null,
      satisfied: null,
      valid: true,
      errorField: null,
      errorMessage: null,
    };
  }

  const trimmedMeasured = typeof measuredValue === 'string' ? measuredValue.trim() : '';
  const numericMeasured = parseNumericMeasuredValue(trimmedMeasured);

  if (!trimmedMeasured) {
    return {
      isNumericSpec: true,
      rule,
      numericMeasured: null,
      satisfied: null,
      valid: result === 'PENDING',
      errorField: null,
      errorMessage: null,
    };
  }

  if (numericMeasured === null) {
    if (result === 'PASS' || result === 'FAIL') {
      return {
        isNumericSpec: true,
        rule,
        numericMeasured: null,
        satisfied: false,
        valid: false,
        errorField: 'measuredValue',
        errorMessage: `Measured value '${trimmedMeasured}' must be numeric to evaluate against acceptance specification (${rule.describe}).`,
      };
    }
    return {
      isNumericSpec: true,
      rule,
      numericMeasured: null,
      satisfied: null,
      valid: true,
      errorField: null,
      errorMessage: null,
    };
  }

  const satisfied = isValueWithinRule(rule, numericMeasured);

  if (result === 'PASS' && !satisfied) {
    return {
      isNumericSpec: true,
      rule,
      numericMeasured,
      satisfied: false,
      valid: false,
      errorField: 'result',
      errorMessage: `Measured value ${numericMeasured} does not meet acceptance specification (expected ${rule.describe}) and cannot be recorded as PASS.`,
    };
  }

  if (result === 'FAIL' && satisfied) {
    return {
      isNumericSpec: true,
      rule,
      numericMeasured,
      satisfied: true,
      valid: false,
      errorField: 'result',
      errorMessage: `Measured value ${numericMeasured} satisfies acceptance specification (expected ${rule.describe}) and cannot be recorded as FAIL.`,
    };
  }

  return {
    isNumericSpec: true,
    rule,
    numericMeasured,
    satisfied,
    valid: true,
    errorField: null,
    errorMessage: null,
  };
}

