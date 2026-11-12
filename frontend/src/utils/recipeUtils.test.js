import test from 'node:test';
import assert from 'node:assert/strict';
import {
  getStepNumberError,
  parsePositiveIntegerStepNumber,
  findDuplicateStepNumbers,
  getNextStepNumber,
  validateRecipeForm,
  mapRecipeServerErrors,
} from './recipeUtils.js';

function createValidFormData() {
  return {
    productId: 1,
    batchSize: '1000',
    batchUnit: 'kg',
    description: 'Standard Paracetamol formulation',
    materials: [
      { materialName: 'Paracetamol API', quantity: '500', unit: 'kg' },
    ],
    steps: [
      { stepNumber: 1, operationName: 'Dispensing', instructions: 'Weigh all ingredients.' },
      { stepNumber: 2, operationName: 'Blending', instructions: 'Blend for 20 minutes.' },
    ],
  };
}

test('getStepNumberError validates positive integers and rejects invalid step inputs', () => {
  assert.equal(getStepNumberError(1), '');
  assert.equal(getStepNumberError('3'), '');
  assert.equal(getStepNumberError(' 12 '), '');

  assert.match(getStepNumberError(0), /greater than zero/i);
  assert.match(getStepNumberError(-1), /greater than zero/i);
  assert.match(getStepNumberError('1.5'), /positive integer/i);
  assert.match(getStepNumberError('1.0'), /positive integer/i);
  assert.match(getStepNumberError('abc'), /positive integer/i);
  assert.match(getStepNumberError(''), /positive integer/i);
  assert.match(getStepNumberError('1', true), /positive integer/i);
});

test('parsePositiveIntegerStepNumber extracts positive integers only', () => {
  assert.equal(parsePositiveIntegerStepNumber(1), 1);
  assert.equal(parsePositiveIntegerStepNumber('4'), 4);
  assert.equal(parsePositiveIntegerStepNumber(0), null);
  assert.equal(parsePositiveIntegerStepNumber(-2), null);
  assert.equal(parsePositiveIntegerStepNumber('1.5'), null);
  assert.equal(parsePositiveIntegerStepNumber('', false), null);
  assert.equal(parsePositiveIntegerStepNumber(2, true), null);
});

test('findDuplicateStepNumbers detects duplicate and non-contiguous duplicates cleanly', () => {
  const unique = findDuplicateStepNumbers([
    { stepNumber: 1 },
    { stepNumber: 2 },
    { stepNumber: 3 },
  ]);
  assert.deepEqual(unique.duplicates, []);
  assert.deepEqual(unique.duplicateRowIndices, {});

  const adjDup = findDuplicateStepNumbers([
    { stepNumber: 1 },
    { stepNumber: '1' },
  ]);
  assert.deepEqual(adjDup.duplicates, [1]);
  assert.deepEqual(adjDup.duplicateRowIndices, { 0: 1, 1: 1 });

  const multiDup = findDuplicateStepNumbers([
    { stepNumber: 2 },
    { stepNumber: 1 },
    { stepNumber: 2 },
    { stepNumber: 3 },
    { stepNumber: 3 },
  ]);
  assert.deepEqual(multiDup.duplicates, [2, 3]);
  assert.deepEqual(multiDup.duplicateRowIndices, { 0: 2, 2: 2, 3: 3, 4: 3 });
});

test('getNextStepNumber avoids colliding with manually renumbered steps', () => {
  assert.equal(getNextStepNumber([]), 1);
  assert.equal(getNextStepNumber([{ stepNumber: 1 }]), 2);
  assert.equal(getNextStepNumber([{ stepNumber: 2 }]), 3);
  assert.equal(getNextStepNumber([{ stepNumber: 1 }, { stepNumber: 5 }]), 6);
});

test('validateRecipeForm accepts valid recipe in both create and edit modes', () => {
  const validData = createValidFormData();

  const createResult = validateRecipeForm(validData, { isEditMode: false });
  assert.equal(createResult.isValid, true);
  assert.deepEqual(createResult.errors, {});

  const editWithoutProductSelect = { ...validData, productId: '' };
  const editResult = validateRecipeForm(editWithoutProductSelect, { isEditMode: true });
  assert.equal(editResult.isValid, true);
  assert.deepEqual(editResult.errors, {});
});

test('validateRecipeForm rejects duplicate step numbers during creation and editing', () => {
  const formData = createValidFormData();
  formData.steps = [
    { stepNumber: 2, operationName: 'Dispensing', instructions: 'Step A instructions.' },
    { stepNumber: 1, operationName: 'Blending', instructions: 'Step B instructions.' },
    { stepNumber: '2', operationName: 'Compression', instructions: 'Step C instructions.' },
  ];

  const createResult = validateRecipeForm(formData, { isEditMode: false });
  assert.equal(createResult.isValid, false);
  assert.match(createResult.errors.steps, /Duplicate step number\(s\) found: 2/);
  assert.match(createResult.errors.step_0_num, /Duplicate step number \(2\)/);
  assert.equal(createResult.errors.step_1_num, undefined);
  assert.match(createResult.errors.step_2_num, /Duplicate step number \(2\)/);

  const editResult = validateRecipeForm(formData, { isEditMode: true });
  assert.equal(editResult.isValid, false);
  assert.match(editResult.errors.steps, /Duplicate step number\(s\) found: 2/);
  assert.match(editResult.errors.step_0_num, /Duplicate step number \(2\)/);
  assert.match(editResult.errors.step_2_num, /Duplicate step number \(2\)/);
});

test('validateRecipeForm reports non-positive step errors without overwriting them with duplicate errors', () => {
  const formData = createValidFormData();
  formData.steps = [
    { stepNumber: 0, operationName: 'Dispensing', instructions: 'Instructions 1' },
    { stepNumber: 1, operationName: 'Blending', instructions: 'Instructions 2' },
    { stepNumber: 1, operationName: 'Compression', instructions: 'Instructions 3' },
  ];

  const result = validateRecipeForm(formData, { isEditMode: false });
  assert.equal(result.isValid, false);
  assert.match(result.errors.step_0_num, /greater than zero/);
  assert.match(result.errors.step_1_num, /Duplicate step number \(1\)/);
  assert.match(result.errors.step_2_num, /Duplicate step number \(1\)/);
  assert.match(result.errors.steps, /Duplicate step number\(s\) found: 1/);
});

test('mapRecipeServerErrors preserves clear backend validation and business error messages', () => {
  const dupStepErr = mapRecipeServerErrors({
    message: 'Duplicate step number(s) found: 1. Each manufacturing step in a recipe must have a unique step number.',
  });
  assert.deepEqual(dupStepErr.fieldErrors, {});
  assert.match(dupStepErr.serverError, /Duplicate step number\(s\) found: 1/);

  const fieldErr = mapRecipeServerErrors({
    message: 'Input validation failed for one or more fields',
    validationErrors: {
      'steps[1].stepNumber': 'Step number must be greater than zero',
      'materials[0].quantity': 'Quantity must be greater than zero',
    },
  });
  assert.equal(fieldErr.fieldErrors.step_1_num, 'Step number must be greater than zero');
  assert.equal(fieldErr.fieldErrors.mat_0_qty, 'Quantity must be greater than zero');
  assert.equal(fieldErr.serverError, 'Input validation failed for one or more fields');
});

