import test from 'node:test';
import assert from 'node:assert/strict';
import {
  formatQuantityDisplay,
  formatSignedDelta,
  validateGoodsReceiptForm,
  validateMaterialIssueForm,
  validateRawMaterialForm,
  validateStockAdjustmentForm,
  validateStorageLocationForm,
  validateWarehouseForm,
} from './inventoryUtils.js';

test('formatQuantityDisplay and formatSignedDelta format numbers, signs, and missing values consistently', () => {
  assert.equal(formatQuantityDisplay(null, 'kg'), '—');
  assert.equal(formatQuantityDisplay('', 'kg'), '—');
  assert.equal(formatQuantityDisplay(250, 'kg'), '250 kg');
  assert.equal(formatQuantityDisplay(12.34567, 'L'), '12.3457 L');
  assert.equal(formatQuantityDisplay('not-a-number', 'pcs'), 'not-a-number pcs');

  assert.equal(formatSignedDelta(null, 'kg'), '—');
  assert.equal(formatSignedDelta(150, 'kg'), '+150 kg');
  assert.equal(formatSignedDelta(-40.5, 'kg'), '-40.5 kg');
  assert.equal(formatSignedDelta(0, 'kg'), '0 kg');
});

test('validateRawMaterialForm enforces required fields, valid materialType, length bounds, and non-negative minimumStockLevel', () => {
  const invalid = validateRawMaterialForm({
    materialCode: ' ',
    materialName: '',
    materialType: 'INVALID',
    unit: '',
    minimumStockLevel: -5,
  });
  assert.equal(invalid.isValid, false);
  assert.ok(invalid.errors.materialCode);
  assert.ok(invalid.errors.materialName);
  assert.ok(invalid.errors.materialType);
  assert.ok(invalid.errors.unit);
  assert.ok(invalid.errors.minimumStockLevel);

  const validZeroMin = validateRawMaterialForm({
    materialCode: 'RM-API-01',
    materialName: 'Paracetamol BP',
    materialType: 'API',
    unit: 'kg',
    minimumStockLevel: 0,
  });
  assert.equal(validZeroMin.isValid, true);
  assert.deepEqual(validZeroMin.errors, {});
});

test('validateWarehouseForm and validateStorageLocationForm validate required codes and names', () => {
  assert.equal(validateWarehouseForm({ warehouseCode: '', warehouseName: '' }).isValid, false);
  assert.equal(validateWarehouseForm({ warehouseCode: 'WH-01', warehouseName: 'Main Pharma Store' }).isValid, true);

  assert.equal(validateStorageLocationForm({ locationCode: '  ' }).isValid, false);
  assert.equal(validateStorageLocationForm({ locationCode: 'A1-R2-B3' }).isValid, true);
});

test('validateGoodsReceiptForm validates dates, positive quantity, and required fields', () => {
  const todayIso = '2026-11-12';

  const futureReceived = validateGoodsReceiptForm(
    {
      rawMaterialId: 1,
      warehouseId: 2,
      storageLocationId: 3,
      lotNumber: 'LOT-001',
      quantity: 100,
      unit: 'kg',
      receivedDate: '2026-11-13',
      expiryDate: '2027-11-12',
      supplierName: 'FineChem Ltd',
    },
    todayIso
  );
  assert.equal(futureReceived.isValid, false);
  assert.equal(futureReceived.errors.receivedDate, 'Received date cannot be in the future');

  const nonAfterExpiry = validateGoodsReceiptForm(
    {
      rawMaterialId: 1,
      warehouseId: 2,
      storageLocationId: 3,
      lotNumber: 'LOT-001',
      quantity: 100,
      unit: 'kg',
      receivedDate: '2026-11-12',
      expiryDate: '2026-11-12',
      supplierName: 'FineChem Ltd',
    },
    todayIso
  );
  assert.equal(nonAfterExpiry.isValid, false);
  assert.equal(nonAfterExpiry.errors.expiryDate, 'Expiry date must be strictly after received date');

  const expiredLot = validateGoodsReceiptForm(
    {
      rawMaterialId: 1,
      warehouseId: 2,
      storageLocationId: 3,
      lotNumber: 'LOT-001',
      quantity: 100,
      unit: 'kg',
      receivedDate: '2026-10-01',
      expiryDate: '2026-11-10',
      supplierName: 'FineChem Ltd',
    },
    todayIso
  );
  assert.equal(expiredLot.isValid, false);
  assert.equal(expiredLot.errors.expiryDate, 'Cannot receive an already-expired lot');

  const valid = validateGoodsReceiptForm(
    {
      rawMaterialId: 1,
      warehouseId: 2,
      storageLocationId: 3,
      lotNumber: 'LOT-001',
      quantity: 100,
      unit: 'kg',
      receivedDate: '2026-11-12',
      expiryDate: '2027-11-12',
      supplierName: 'FineChem Ltd',
    },
    todayIso
  );
  assert.equal(valid.isValid, true);
});

test('validateMaterialIssueForm blocks over-issue against lot, over-issue against order BOM, and expired/quarantined lots', () => {
  const activeLot = {
    id: 10,
    availableQuantity: 50,
    unit: 'kg',
    effectiveStatus: 'ACTIVE',
    expired: false,
  };
  const requirement = {
    remainingRequiredQuantity: 40,
    recipeUnit: 'kg',
  };

  const overLot = validateMaterialIssueForm(
    { manufacturingOrderId: 1, rawMaterialId: 2, materialLotId: 10, quantity: 60, unit: 'kg' },
    activeLot,
    null
  );
  assert.equal(overLot.isValid, false);
  assert.match(overLot.errors.quantity, /Cannot exceed lot available stock/);

  const overBom = validateMaterialIssueForm(
    { manufacturingOrderId: 1, rawMaterialId: 2, materialLotId: 10, quantity: 45, unit: 'kg' },
    activeLot,
    requirement
  );
  assert.equal(overBom.isValid, false);
  assert.match(overBom.errors.quantity, /Exceeds remaining order recipe requirement/);

  const quarantinedRes = validateMaterialIssueForm(
    { manufacturingOrderId: 1, rawMaterialId: 2, materialLotId: 10, quantity: 10, unit: 'kg' },
    { ...activeLot, effectiveStatus: 'QUARANTINED' },
    requirement
  );
  assert.equal(quarantinedRes.isValid, false);
  assert.match(quarantinedRes.errors.materialLotId, /quarantined/i);

  const expiredRes = validateMaterialIssueForm(
    { manufacturingOrderId: 1, rawMaterialId: 2, materialLotId: 10, quantity: 10, unit: 'kg' },
    { ...activeLot, expired: true, effectiveStatus: 'EXPIRED' },
    requirement
  );
  assert.equal(expiredRes.isValid, false);
  assert.match(expiredRes.errors.materialLotId, /expired/i);

  const valid = validateMaterialIssueForm(
    { manufacturingOrderId: 1, rawMaterialId: 2, materialLotId: 10, quantity: 40, unit: 'kg' },
    activeLot,
    requirement
  );
  assert.equal(valid.isValid, true);
});

test('validateStockAdjustmentForm enforces mandatory reason, positive qty for INCREASE/DECREASE, and prevents negative stock', () => {
  const lot = { id: 5, availableQuantity: 25, unit: 'kg', effectiveStatus: 'ACTIVE' };

  const missingReason = validateStockAdjustmentForm(
    { materialLotId: 5, adjustmentType: 'QUARANTINE', reason: 'hi' },
    lot
  );
  assert.equal(missingReason.isValid, false);
  assert.ok(missingReason.errors.reason);

  const negativeDecrease = validateStockAdjustmentForm(
    { materialLotId: 5, adjustmentType: 'DECREASE', quantity: 30, reason: 'Broken drum scrap' },
    lot
  );
  assert.equal(negativeDecrease.isValid, false);
  assert.match(negativeDecrease.errors.quantity, /Cannot decrease below 0/);

  const validQuarantine = validateStockAdjustmentForm(
    { materialLotId: 5, adjustmentType: 'QUARANTINE', reason: 'Pending moisture retest' },
    lot
  );
  assert.equal(validQuarantine.isValid, true);
});

