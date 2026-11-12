/**
 * Validation and error-mapping helpers for Master Recipe creation and editing.
 */

export function getStepNumberError(value, isBadInput = false) {
  if (isBadInput) {
    return 'Step number must be a positive integer greater than zero';
  }
  const raw = String(value ?? '').trim();
  if (!raw) {
    return 'Step number must be a positive integer greater than zero';
  }
  if (!/^-?\d+$/.test(raw)) {
    const parsed = Number(raw);
    if (Number.isFinite(parsed) && parsed <= 0) {
      return 'Step number must be greater than zero';
    }
    return 'Step number must be a positive integer greater than zero';
  }
  const num = Number(raw);
  if (!Number.isSafeInteger(num) || num <= 0) {
    return 'Step number must be greater than zero';
  }
  return '';
}

export function parsePositiveIntegerStepNumber(value, isBadInput = false) {
  if (isBadInput) {
    return null;
  }
  const raw = String(value ?? '').trim();
  if (!/^-?\d+$/.test(raw)) {
    return null;
  }
  const num = Number(raw);
  if (!Number.isSafeInteger(num) || num <= 0) {
    return null;
  }
  return num;
}

export function findDuplicateStepNumbers(steps = [], badStepInputs = {}) {
  if (!Array.isArray(steps) || steps.length === 0) {
    return {
      duplicates: [],
      duplicateRowIndices: {},
    };
  }

  const rowsByStepNumber = new Map();
  steps.forEach((step, idx) => {
    const num = parsePositiveIntegerStepNumber(
      step?.stepNumber,
      Boolean(badStepInputs[idx])
    );
    if (num !== null) {
      if (!rowsByStepNumber.has(num)) {
        rowsByStepNumber.set(num, []);
      }
      rowsByStepNumber.get(num).push(idx);
    }
  });

  const duplicates = [];
  const duplicateRowIndices = {};

  for (const [stepNum, indices] of rowsByStepNumber.entries()) {
    if (indices.length > 1) {
      duplicates.push(stepNum);
      for (const rowIdx of indices) {
        duplicateRowIndices[rowIdx] = stepNum;
      }
    }
  }

  return {
    duplicates,
    duplicateRowIndices,
  };
}

export function getNextStepNumber(steps = []) {
  if (!Array.isArray(steps) || steps.length === 0) {
    return 1;
  }
  let maxStep = 0;
  for (const step of steps) {
    const num = parsePositiveIntegerStepNumber(step?.stepNumber);
    if (num !== null && num > maxStep) {
      maxStep = num;
    }
  }
  return Math.max(steps.length, maxStep) + 1;
}

export function validateRecipeForm(
  formData,
  { isEditMode = false, badStepInputs = {} } = {}
) {
  const errors = {};
  const data = formData || {};

  if (!isEditMode && !data.productId) {
    errors.productId = 'Product selection is mandatory';
  }

  if (!data.batchSize || Number(data.batchSize) <= 0) {
    errors.batchSize = 'Batch size must be greater than zero';
  }

  if (!String(data.batchUnit ?? '').trim()) {
    errors.batchUnit = 'Batch unit is required';
  }

  const materials = Array.isArray(data.materials) ? data.materials : [];
  if (materials.length === 0) {
    errors.materials = 'At least one raw material is required';
  } else {
    materials.forEach((mat, idx) => {
      if (!String(mat?.materialName ?? '').trim()) {
        errors[`mat_${idx}_name`] = 'Material name is required';
      }
      if (!mat?.quantity || Number(mat.quantity) <= 0) {
        errors[`mat_${idx}_qty`] = 'Quantity must be > 0';
      }
      if (!String(mat?.unit ?? '').trim()) {
        errors[`mat_${idx}_unit`] = 'Unit required';
      }
    });
  }

  const steps = Array.isArray(data.steps) ? data.steps : [];
  if (steps.length === 0) {
    errors.steps = 'At least one manufacturing step is required';
  } else {
    steps.forEach((step, idx) => {
      const isBadInput = Boolean(badStepInputs[idx]);
      const stepNumError = getStepNumberError(step?.stepNumber, isBadInput);
      if (stepNumError) {
        errors[`step_${idx}_num`] = stepNumError;
      }
      if (!String(step?.operationName ?? '').trim()) {
        errors[`step_${idx}_op`] = 'Operation name required';
      }
      if (!String(step?.instructions ?? '').trim()) {
        errors[`step_${idx}_inst`] = 'Instructions required';
      }
    });

    const { duplicates, duplicateRowIndices } = findDuplicateStepNumbers(
      steps,
      badStepInputs
    );
    if (duplicates.length > 0) {
      errors.steps = `Duplicate step number(s) found: ${duplicates.join(
        ', '
      )}. Each manufacturing step must have a unique step number.`;
      Object.entries(duplicateRowIndices).forEach(([rowIdx, dupNum]) => {
        if (!errors[`step_${rowIdx}_num`]) {
          errors[`step_${rowIdx}_num`] = `Duplicate step number (${dupNum}) — must be unique`;
        }
      });
    }
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  };
}

export function mapRecipeServerErrors(err) {
  if (!err || typeof err !== 'object') {
    return {
      fieldErrors: {},
      serverError: 'Failed to save recipe',
    };
  }

  if (err.validationErrors && typeof err.validationErrors === 'object') {
    const mappedErrors = { ...err.validationErrors };
    Object.entries(err.validationErrors).forEach(([key, msg]) => {
      const stepMatch = key.match(/^steps\[(\d+)\]\.(stepNumber|operationName|instructions)$/);
      if (stepMatch) {
        const [, i, field] = stepMatch;
        const suffix = field === 'stepNumber' ? 'num' : field === 'operationName' ? 'op' : 'inst';
        mappedErrors[`step_${i}_${suffix}`] = msg;
      }
      const matMatch = key.match(/^materials\[(\d+)\]\.(materialName|quantity|unit)$/);
      if (matMatch) {
        const [, i, field] = matMatch;
        const suffix = field === 'materialName' ? 'name' : field === 'quantity' ? 'qty' : 'unit';
        mappedErrors[`mat_${i}_${suffix}`] = msg;
      }
    });

    return {
      fieldErrors: mappedErrors,
      serverError:
        mappedErrors.steps ||
        mappedErrors.stepNumbersUnique ||
        err.message ||
        'Input validation failed for one or more fields',
    };
  }

  return {
    fieldErrors: {},
    serverError: err.message || 'Failed to save recipe',
  };
}

