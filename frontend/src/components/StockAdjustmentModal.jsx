import React, { useEffect, useId, useMemo, useState } from 'react';
import { SlidersHorizontal } from 'lucide-react';
import { InlineAlert, ModalDialog, StatusBadge } from './ui/index.js';
import {
  ADJUSTMENT_TYPES,
  formatQuantityDisplay,
  validateStockAdjustmentForm,
} from '../utils/inventoryUtils.js';

export default function StockAdjustmentModal({
  isOpen,
  onClose,
  lots = [],
  initialLot = null,
  initialActionType = null,
  onSave,
  isSubmitting = false,
}) {
  const lotInputId = useId();
  const typeInputId = useId();
  const qtyInputId = useId();
  const refInputId = useId();
  const reasonInputId = useId();

  const [form, setForm] = useState({
    materialLotId: '',
    adjustmentType: 'DECREASE',
    quantity: '',
    referenceNumber: '',
    reason: '',
  });
  const [fieldErrors, setFieldErrors] = useState({});
  const [serverError, setServerError] = useState(null);

  const selectedLot = useMemo(
    () =>
      (Array.isArray(lots) ? lots : []).find(
        (l) => String(l.id) === String(form.materialLotId)
      ) || null,
    [lots, form.materialLotId]
  );

  useEffect(() => {
    if (!isOpen) return;
    setFieldErrors({});
    setServerError(null);
    const defaultLot = initialLot || (Array.isArray(lots) ? lots[0] : null);
    const defaultType =
      initialActionType ||
      (defaultLot?.effectiveStatus === 'QUARANTINED' ? 'RELEASE_QUARANTINE' : 'DECREASE');

    setForm({
      materialLotId: defaultLot ? String(defaultLot.id) : '',
      adjustmentType: defaultType,
      quantity: '',
      referenceNumber: '',
      reason: '',
    });
  }, [isOpen, initialLot, initialActionType]);

  const handleFieldChange = (field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }));
    if (fieldErrors[field]) {
      setFieldErrors((prev) => {
        const next = { ...prev };
        delete next[field];
        return next;
      });
    }
    if (serverError) setServerError(null);
  };

  const requiresQuantity =
    form.adjustmentType === 'INCREASE' || form.adjustmentType === 'DECREASE';

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (isSubmitting) return;

    const validation = validateStockAdjustmentForm(form, selectedLot);
    if (!validation.isValid) {
      setFieldErrors(validation.errors);
      return;
    }

    const payload = {
      materialLotId: Number(form.materialLotId),
      adjustmentType: form.adjustmentType,
      quantity: requiresQuantity ? Number(form.quantity) : null,
      referenceNumber: form.referenceNumber.trim() || null,
      reason: form.reason.trim(),
    };

    try {
      await onSave(payload);
    } catch (err) {
      setServerError(err.message || 'Failed to record stock adjustment');
    }
  };

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={onClose}
      preventClose={isSubmitting}
      size="lg"
      zIndexClass="z-50"
      title="Controlled Stock Adjustment & Quarantine"
      subtitle="Apply audited cycle-count adjustments or place/release lot quarantine with mandatory justification"
      icon={
        <div className="w-9 h-9 rounded-lg bg-purple-100 text-purple-700 flex items-center justify-center">
          <SlidersHorizontal className="w-5 h-5" aria-hidden="true" />
        </div>
      }
      footer={
        <>
          <button
            type="button"
            onClick={onClose}
            disabled={isSubmitting}
            className="px-4 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-100 border border-slate-300 rounded-lg transition-colors disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="submit"
            form="stock-adjustment-form"
            disabled={isSubmitting || (Array.isArray(lots) ? lots.length === 0 : true)}
            className="px-4 py-2 text-sm font-semibold text-white bg-purple-600 hover:bg-purple-700 rounded-lg shadow-sm transition-colors disabled:opacity-50"
          >
            {isSubmitting ? 'Applying Adjustment...' : 'Apply Stock Adjustment'}
          </button>
        </>
      }
    >
      <form
        id="stock-adjustment-form"
        onSubmit={handleSubmit}
        noValidate
        className="p-4 sm:p-6 space-y-4 overflow-y-auto max-h-[75vh]"
      >
        {serverError && (
          <InlineAlert
            variant="error"
            role="alert"
            compact
            message={serverError}
            onDismiss={() => setServerError(null)}
          />
        )}

        <div>
          <label htmlFor={lotInputId} className="block text-xs font-semibold text-slate-700 mb-1">
            Material Lot <span className="text-rose-600">*</span>
          </label>
          <select
            id={lotInputId}
            value={form.materialLotId}
            onChange={(e) => handleFieldChange('materialLotId', e.target.value)}
            aria-invalid={Boolean(fieldErrors.materialLotId)}
            aria-describedby={fieldErrors.materialLotId ? `${lotInputId}-err` : undefined}
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
          >
            <option value="">Select material lot...</option>
            {(Array.isArray(lots) ? lots : []).map((lot) => (
              <option key={lot.id} value={String(lot.id)}>
                {lot.materialCode} / {lot.lotNumber} —{' '}
                {formatQuantityDisplay(lot.availableQuantity, lot.unit)} ({lot.effectiveStatus})
              </option>
            ))}
          </select>
          {fieldErrors.materialLotId && (
            <p id={`${lotInputId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
              {fieldErrors.materialLotId}
            </p>
          )}
        </div>

        {selectedLot && (
          <div className="bg-slate-50 border border-slate-200 rounded-lg p-3 flex flex-wrap items-center justify-between gap-2 text-xs">
            <div>
              <span className="font-semibold text-slate-800">{selectedLot.materialName}</span>
              <span className="text-slate-500 ml-2 font-mono">{selectedLot.lotNumber}</span>
              <span className="text-slate-500 ml-2">
                ({selectedLot.warehouseCode} / {selectedLot.locationCode})
              </span>
            </div>
            <div className="flex items-center space-x-2">
              <span className="font-mono font-bold text-slate-900 tabular-nums">
                Avail: {formatQuantityDisplay(selectedLot.availableQuantity, selectedLot.unit)}
              </span>
              <StatusBadge status={selectedLot.effectiveStatus} domain="inventory" size="xs" showDot />
            </div>
          </div>
        )}

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label htmlFor={typeInputId} className="block text-xs font-semibold text-slate-700 mb-1">
              Adjustment Action <span className="text-rose-600">*</span>
            </label>
            <select
              id={typeInputId}
              value={form.adjustmentType}
              onChange={(e) => handleFieldChange('adjustmentType', e.target.value)}
              aria-invalid={Boolean(fieldErrors.adjustmentType)}
              aria-describedby={fieldErrors.adjustmentType ? `${typeInputId}-err` : undefined}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              {ADJUSTMENT_TYPES.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
            {fieldErrors.adjustmentType && (
              <p id={`${typeInputId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.adjustmentType}
              </p>
            )}
          </div>

          {requiresQuantity ? (
            <div>
              <label
                htmlFor={qtyInputId}
                className="block text-xs font-semibold text-slate-700 mb-1"
              >
                Adjustment Quantity ({selectedLot?.unit || 'units'}){' '}
                <span className="text-rose-600">*</span>
              </label>
              <input
                id={qtyInputId}
                type="number"
                step="any"
                min="0.0001"
                value={form.quantity}
                onChange={(e) => handleFieldChange('quantity', e.target.value)}
                placeholder="e.g., 5"
                aria-invalid={Boolean(fieldErrors.quantity)}
                aria-describedby={fieldErrors.quantity ? `${qtyInputId}-err` : undefined}
                className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 tabular-nums focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
              />
              {fieldErrors.quantity && (
                <p id={`${qtyInputId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                  {fieldErrors.quantity}
                </p>
              )}
            </div>
          ) : (
            <div>
              <label
                htmlFor={refInputId}
                className="block text-xs font-semibold text-slate-700 mb-1"
              >
                Deviation / Investigation Ref #
              </label>
              <input
                id={refInputId}
                type="text"
                value={form.referenceNumber}
                onChange={(e) => handleFieldChange('referenceNumber', e.target.value)}
                placeholder="Optional unique reference (e.g., QAR-2026-04)"
                className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
              />
            </div>
          )}
        </div>

        {requiresQuantity && (
          <div>
            <label htmlFor={refInputId} className="block text-xs font-semibold text-slate-700 mb-1">
              Adjustment Document / Reference #
            </label>
            <input
              id={refInputId}
              type="text"
              value={form.referenceNumber}
              onChange={(e) => handleFieldChange('referenceNumber', e.target.value)}
              placeholder="Optional unique reference (e.g., ADJ-CC-2026-01)"
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
          </div>
        )}

        <div>
          <label htmlFor={reasonInputId} className="block text-xs font-semibold text-slate-700 mb-1">
            Mandatory Audit Reason / Justification <span className="text-rose-600">*</span>
          </label>
          <textarea
            id={reasonInputId}
            rows={3}
            value={form.reason}
            onChange={(e) => handleFieldChange('reason', e.target.value)}
            placeholder="State physical verification result, damaged container count, QA hold rationale, or release review..."
            aria-invalid={Boolean(fieldErrors.reason)}
            aria-describedby={fieldErrors.reason ? `${reasonInputId}-err` : undefined}
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
          />
          {fieldErrors.reason && (
            <p id={`${reasonInputId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
              {fieldErrors.reason}
            </p>
          )}
        </div>
      </form>
    </ModalDialog>
  );
}

