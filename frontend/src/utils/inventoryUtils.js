import { getTodayIsoDateInAppTimeZone } from './dateTimeUtils.js';

export const MATERIAL_TYPES = ['API', 'EXCIPIENT', 'PACKAGING', 'SOLVENT', 'OTHER'];

export const MATERIAL_TYPE_LABELS = Object.freeze({
  API: 'Active Ingredient (API)',
  EXCIPIENT: 'Excipient',
  PACKAGING: 'Packaging',
  SOLVENT: 'Solvent',
  OTHER: 'Other',
});

export const ADJUSTMENT_TYPES = [
  { value: 'INCREASE', label: 'Cycle Count Increase (+)' },
  { value: 'DECREASE', label: 'Cycle Count / Scrap Decrease (-)' },
  { value: 'QUARANTINE', label: 'Place Lot in Quarantine' },
  { value: 'RELEASE_QUARANTINE', label: 'Release Lot from Quarantine' },
];

export function formatQuantityDisplay(value, unit = '') {
  if (value === null || value === undefined || value === '') return '—';
  const num = Number(value);
  const formatted = Number.isFinite(num)
    ? num.toLocaleString(undefined, { maximumFractionDigits: 4 })
    : String(value);
  return unit ? `${formatted} ${unit}` : formatted;
}

export function formatSignedDelta(delta, unit = '') {
  if (delta === null || delta === undefined || delta === '') return '—';
  const num = Number(delta);
  if (!Number.isFinite(num)) return unit ? `${delta} ${unit}` : String(delta);
  const absFormatted = Math.abs(num).toLocaleString(undefined, { maximumFractionDigits: 4 });
  const sign = num > 0 ? '+' : num < 0 ? '-' : '';
  return unit ? `${sign}${absFormatted} ${unit}` : `${sign}${absFormatted}`;
}

export function validateRawMaterialForm(form = {}) {
  const errors = {};
  const code = String(form.materialCode ?? '').trim();
  const name = String(form.materialName ?? '').trim();
  const unit = String(form.unit ?? '').trim();
  const minRaw = form.minimumStockLevel;
  const minNum = Number(minRaw);

  if (!code) {
    errors.materialCode = 'Material code is required';
  } else if (code.length > 50) {
    errors.materialCode = 'Material code must not exceed 50 characters';
  }

  if (!name) {
    errors.materialName = 'Material name is required';
  } else if (name.length > 255) {
    errors.materialName = 'Material name must not exceed 255 characters';
  }

  if (!form.materialType || !MATERIAL_TYPES.includes(form.materialType)) {
    errors.materialType = 'Select a valid material type';
  }

  if (!unit) {
    errors.unit = 'Unit of measurement is required';
  } else if (unit.length > 50) {
    errors.unit = 'Unit must not exceed 50 characters';
  }

  if (minRaw === '' || minRaw === null || minRaw === undefined || !Number.isFinite(minNum) || minNum < 0) {
    errors.minimumStockLevel = 'Minimum stock level must be 0 or greater';
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  };
}

export function validateWarehouseForm(form = {}) {
  const errors = {};
  const code = String(form.warehouseCode ?? '').trim();
  const name = String(form.warehouseName ?? '').trim();

  if (!code) {
    errors.warehouseCode = 'Warehouse code is required';
  } else if (code.length > 50) {
    errors.warehouseCode = 'Warehouse code must not exceed 50 characters';
  }

  if (!name) {
    errors.warehouseName = 'Warehouse name is required';
  } else if (name.length > 150) {
    errors.warehouseName = 'Warehouse name must not exceed 150 characters';
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  };
}

export function validateStorageLocationForm(form = {}) {
  const errors = {};
  const code = String(form.locationCode ?? '').trim();

  if (!code) {
    errors.locationCode = 'Location code is required';
  } else if (code.length > 60) {
    errors.locationCode = 'Location code must not exceed 60 characters';
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  };
}

export function validateGoodsReceiptForm(form = {}, todayIso = getTodayIsoDateInAppTimeZone()) {
  const errors = {};
  const qty = Number(form.quantity);

  if (!form.rawMaterialId) {
    errors.rawMaterialId = 'Select an active raw material';
  }
  if (!form.warehouseId) {
    errors.warehouseId = 'Select an active warehouse';
  }
  if (!form.storageLocationId) {
    errors.storageLocationId = 'Select an active storage location';
  }
  if (!String(form.lotNumber ?? '').trim()) {
    errors.lotNumber = 'Lot/batch number is required';
  }
  if (!form.quantity || !Number.isFinite(qty) || qty <= 0) {
    errors.quantity = 'Receipt quantity must be greater than zero';
  }
  if (!String(form.unit ?? '').trim()) {
    errors.unit = 'Unit is required';
  }
  if (!form.receivedDate) {
    errors.receivedDate = 'Received date is required';
  } else if (todayIso && form.receivedDate > todayIso) {
    errors.receivedDate = 'Received date cannot be in the future';
  }
  if (!form.expiryDate) {
    errors.expiryDate = 'Expiry date is required';
  } else {
    if (form.receivedDate && form.expiryDate <= form.receivedDate) {
      errors.expiryDate = 'Expiry date must be strictly after received date';
    } else if (todayIso && form.expiryDate < todayIso) {
      errors.expiryDate = 'Cannot receive an already-expired lot';
    }
  }
  if (!String(form.supplierName ?? '').trim()) {
    errors.supplierName = 'Supplier name is required';
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  };
}

export function validateMaterialIssueForm(form = {}, selectedLot = null, matchedRequirement = null) {
  const errors = {};
  const qty = Number(form.quantity);

  if (!form.manufacturingOrderId) {
    errors.manufacturingOrderId = 'Select a released or in-progress manufacturing order';
  }
  if (!form.rawMaterialId) {
    errors.rawMaterialId = 'Select a raw material';
  }
  if (!form.materialLotId) {
    errors.materialLotId = 'Select an active non-expired material lot';
  }
  if (!form.quantity || !Number.isFinite(qty) || qty <= 0) {
    errors.quantity = 'Issue quantity must be greater than zero';
  } else if (selectedLot && qty > Number(selectedLot.availableQuantity)) {
    errors.quantity = `Cannot exceed lot available stock (${selectedLot.availableQuantity} ${selectedLot.unit})`;
  } else if (
    matchedRequirement &&
    matchedRequirement.remainingRequiredQuantity != null &&
    qty > Number(matchedRequirement.remainingRequiredQuantity)
  ) {
    errors.quantity = `Exceeds remaining order recipe requirement (${matchedRequirement.remainingRequiredQuantity} ${matchedRequirement.recipeUnit})`;
  }

  if (selectedLot && (selectedLot.expired || selectedLot.effectiveStatus === 'EXPIRED')) {
    errors.materialLotId = 'Selected lot is expired and cannot be issued';
  } else if (selectedLot && selectedLot.effectiveStatus === 'QUARANTINED') {
    errors.materialLotId = 'Selected lot is quarantined and cannot be issued';
  }

  if (!String(form.unit ?? '').trim()) {
    errors.unit = 'Unit is required';
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  };
}

export function validateStockAdjustmentForm(form = {}, selectedLot = null) {
  const errors = {};
  const type = form.adjustmentType;
  const qty = Number(form.quantity);

  if (!form.materialLotId) {
    errors.materialLotId = 'Select a material lot';
  }
  if (!type) {
    errors.adjustmentType = 'Select an adjustment action';
  }

  if (type === 'INCREASE' || type === 'DECREASE') {
    if (!form.quantity || !Number.isFinite(qty) || qty <= 0) {
      errors.quantity = 'Quantity must be greater than zero';
    } else if (type === 'DECREASE' && selectedLot && qty > Number(selectedLot.availableQuantity)) {
      errors.quantity = `Cannot decrease below 0 (available: ${selectedLot.availableQuantity} ${selectedLot.unit})`;
    }
  }

  const reason = String(form.reason ?? '').trim();
  if (!reason || reason.length < 3) {
    errors.reason = 'Adjustment reason (minimum 3 characters) is mandatory';
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  };
}

