import React, { useEffect, useId, useMemo, useState } from 'react';
import { PackagePlus } from 'lucide-react';
import { InlineAlert, ModalDialog } from './ui/index.js';
import { getTodayIsoDateInAppTimeZone } from '../utils/dateTimeUtils.js';
import { validateGoodsReceiptForm } from '../utils/inventoryUtils.js';

export default function GoodsReceiptModal({
  isOpen,
  onClose,
  materials = [],
  warehouses = [],
  locations = [],
  initialMaterialId = null,
  onSave,
  isSubmitting = false,
}) {
  const materialIdInput = useId();
  const warehouseIdInput = useId();
  const locationIdInput = useId();
  const lotNumberInput = useId();
  const quantityInput = useId();
  const unitInput = useId();
  const receivedDateInput = useId();
  const expiryDateInput = useId();
  const supplierNameInput = useId();
  const supplierRefInput = useId();
  const refNumberInput = useId();
  const reasonInput = useId();

  const activeMaterials = useMemo(
    () => (Array.isArray(materials) ? materials : []).filter((m) => m.active),
    [materials]
  );
  const activeWarehouses = useMemo(
    () => (Array.isArray(warehouses) ? warehouses : []).filter((w) => w.active),
    [warehouses]
  );

  const [form, setForm] = useState({
    rawMaterialId: '',
    warehouseId: '',
    storageLocationId: '',
    lotNumber: '',
    quantity: '',
    unit: '',
    receivedDate: '',
    expiryDate: '',
    supplierName: '',
    supplierReference: '',
    referenceNumber: '',
    reason: '',
  });
  const [fieldErrors, setFieldErrors] = useState({});
  const [serverError, setServerError] = useState(null);

  const activeLocationsForWarehouse = useMemo(() => {
    if (!form.warehouseId) return [];
    const targetWhId = Number(form.warehouseId);
    return (Array.isArray(locations) ? locations : []).filter(
      (loc) => loc.active && Number(loc.warehouseId) === targetWhId
    );
  }, [locations, form.warehouseId]);

  useEffect(() => {
    if (!isOpen) return;
    setFieldErrors({});
    setServerError(null);

    const today = getTodayIsoDateInAppTimeZone() || '';
    const defaultMat =
      initialMaterialId != null
        ? activeMaterials.find((m) => Number(m.id) === Number(initialMaterialId)) || activeMaterials[0]
        : activeMaterials[0];
    const defaultWh = activeWarehouses[0];
    const defaultLocs = defaultWh
      ? (Array.isArray(locations) ? locations : []).filter(
          (l) => l.active && Number(l.warehouseId) === Number(defaultWh.id)
        )
      : [];

    setForm({
      rawMaterialId: defaultMat ? String(defaultMat.id) : '',
      warehouseId: defaultWh ? String(defaultWh.id) : '',
      storageLocationId: defaultLocs[0] ? String(defaultLocs[0].id) : '',
      lotNumber: '',
      quantity: '',
      unit: defaultMat?.unit || '',
      receivedDate: today,
      expiryDate: '',
      supplierName: '',
      supplierReference: '',
      referenceNumber: '',
      reason: '',
    });
  }, [isOpen, initialMaterialId]);

  const handleFieldChange = (field, value) => {
    setForm((prev) => {
      const next = { ...prev, [field]: value };
      if (field === 'rawMaterialId') {
        const matched = activeMaterials.find((m) => String(m.id) === String(value));
        next.unit = matched?.unit || '';
      }
      if (field === 'warehouseId') {
        const matchingLocs = (Array.isArray(locations) ? locations : []).filter(
          (l) => l.active && String(l.warehouseId) === String(value)
        );
        next.storageLocationId = matchingLocs[0] ? String(matchingLocs[0].id) : '';
      }
      return next;
    });
    if (fieldErrors[field]) {
      setFieldErrors((prev) => {
        const next = { ...prev };
        delete next[field];
        return next;
      });
    }
    if (serverError) setServerError(null);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (isSubmitting) return;

    const validation = validateGoodsReceiptForm(form);
    if (!validation.isValid) {
      setFieldErrors(validation.errors);
      return;
    }

    const payload = {
      rawMaterialId: Number(form.rawMaterialId),
      warehouseId: Number(form.warehouseId),
      storageLocationId: Number(form.storageLocationId),
      lotNumber: form.lotNumber.trim(),
      quantity: Number(form.quantity),
      unit: form.unit.trim(),
      receivedDate: form.receivedDate,
      expiryDate: form.expiryDate,
      supplierName: form.supplierName.trim(),
      supplierReference: form.supplierReference.trim() || null,
      referenceNumber: form.referenceNumber.trim() || null,
      reason: form.reason.trim() || null,
    };

    try {
      await onSave(payload);
    } catch (err) {
      setServerError(err.message || 'Failed to record goods receipt');
    }
  };

  const prerequisitesMissing =
    activeMaterials.length === 0 || activeWarehouses.length === 0 || (Array.isArray(locations) ? locations : []).filter((l) => l.active).length === 0;

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={onClose}
      preventClose={isSubmitting}
      size="2xl"
      zIndexClass="z-50"
      title="Record Goods Receipt"
      subtitle="Receive an incoming raw material lot into a specific warehouse storage location"
      icon={
        <div className="w-9 h-9 rounded-lg bg-emerald-100 text-emerald-700 flex items-center justify-center">
          <PackagePlus className="w-5 h-5" aria-hidden="true" />
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
            form="goods-receipt-form"
            disabled={isSubmitting || prerequisitesMissing}
            className="px-4 py-2 text-sm font-semibold text-white bg-emerald-600 hover:bg-emerald-700 rounded-lg shadow-sm transition-colors disabled:opacity-50"
          >
            {isSubmitting ? 'Recording Receipt...' : 'Record Goods Receipt'}
          </button>
        </>
      }
    >
      <form
        id="goods-receipt-form"
        onSubmit={handleSubmit}
        noValidate
        className="p-4 sm:p-6 space-y-4 overflow-y-auto max-h-[75vh]"
      >
        {prerequisitesMissing && (
          <InlineAlert
            variant="warning"
            compact
            title="Master Data Prerequisite Missing"
            message="At least one active Raw Material, active Warehouse, and active Storage Location must exist before recording a Goods Receipt."
          />
        )}

        {serverError && (
          <InlineAlert
            variant="error"
            role="alert"
            compact
            message={serverError}
            onDismiss={() => setServerError(null)}
          />
        )}

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div className="sm:col-span-2">
            <label
              htmlFor={materialIdInput}
              className="block text-xs font-semibold text-slate-700 mb-1"
            >
              Raw Material <span className="text-rose-600">*</span>
            </label>
            <select
              id={materialIdInput}
              value={form.rawMaterialId}
              onChange={(e) => handleFieldChange('rawMaterialId', e.target.value)}
              aria-invalid={Boolean(fieldErrors.rawMaterialId)}
              aria-describedby={fieldErrors.rawMaterialId ? `${materialIdInput}-err` : undefined}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              <option value="">Select active raw material...</option>
              {activeMaterials.map((mat) => (
                <option key={mat.id} value={String(mat.id)}>
                  {mat.materialCode} — {mat.materialName} ({mat.unit})
                </option>
              ))}
            </select>
            {fieldErrors.rawMaterialId && (
              <p id={`${materialIdInput}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.rawMaterialId}
              </p>
            )}
          </div>

          <div>
            <label
              htmlFor={warehouseIdInput}
              className="block text-xs font-semibold text-slate-700 mb-1"
            >
              Destination Warehouse <span className="text-rose-600">*</span>
            </label>
            <select
              id={warehouseIdInput}
              value={form.warehouseId}
              onChange={(e) => handleFieldChange('warehouseId', e.target.value)}
              aria-invalid={Boolean(fieldErrors.warehouseId)}
              aria-describedby={fieldErrors.warehouseId ? `${warehouseIdInput}-err` : undefined}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              <option value="">Select warehouse...</option>
              {activeWarehouses.map((wh) => (
                <option key={wh.id} value={String(wh.id)}>
                  {wh.warehouseCode} — {wh.warehouseName}
                </option>
              ))}
            </select>
            {fieldErrors.warehouseId && (
              <p id={`${warehouseIdInput}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.warehouseId}
              </p>
            )}
          </div>

          <div>
            <label
              htmlFor={locationIdInput}
              className="block text-xs font-semibold text-slate-700 mb-1"
            >
              Storage Location / Bin <span className="text-rose-600">*</span>
            </label>
            <select
              id={locationIdInput}
              value={form.storageLocationId}
              onChange={(e) => handleFieldChange('storageLocationId', e.target.value)}
              aria-invalid={Boolean(fieldErrors.storageLocationId)}
              aria-describedby={fieldErrors.storageLocationId ? `${locationIdInput}-err` : undefined}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              <option value="">
                {form.warehouseId ? 'Select storage location...' : 'Select warehouse first...'}
              </option>
              {activeLocationsForWarehouse.map((loc) => (
                <option key={loc.id} value={String(loc.id)}>
                  {loc.locationCode}
                  {loc.zoneName ? ` (${loc.zoneName})` : ''}
                </option>
              ))}
            </select>
            {fieldErrors.storageLocationId && (
              <p id={`${locationIdInput}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.storageLocationId}
              </p>
            )}
          </div>

          <div>
            <label htmlFor={lotNumberInput} className="block text-xs font-semibold text-slate-700 mb-1">
              Supplier Lot / Batch Number <span className="text-rose-600">*</span>
            </label>
            <input
              id={lotNumberInput}
              type="text"
              value={form.lotNumber}
              onChange={(e) => handleFieldChange('lotNumber', e.target.value)}
              placeholder="e.g., LOT-PARA-2026-01"
              aria-invalid={Boolean(fieldErrors.lotNumber)}
              aria-describedby={fieldErrors.lotNumber ? `${lotNumberInput}-err` : undefined}
              className="w-full px-3 py-2 text-sm font-mono rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
            {fieldErrors.lotNumber && (
              <p id={`${lotNumberInput}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.lotNumber}
              </p>
            )}
          </div>

          <div className="grid grid-cols-3 gap-2">
            <div className="col-span-2">
              <label htmlFor={quantityInput} className="block text-xs font-semibold text-slate-700 mb-1">
                Receipt Quantity <span className="text-rose-600">*</span>
              </label>
              <input
                id={quantityInput}
                type="number"
                step="any"
                min="0.0001"
                value={form.quantity}
                onChange={(e) => handleFieldChange('quantity', e.target.value)}
                placeholder="e.g., 100"
                aria-invalid={Boolean(fieldErrors.quantity)}
                aria-describedby={fieldErrors.quantity ? `${quantityInput}-err` : undefined}
                className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 tabular-nums focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
              />
              {fieldErrors.quantity && (
                <p id={`${quantityInput}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                  {fieldErrors.quantity}
                </p>
              )}
            </div>

            <div>
              <label htmlFor={unitInput} className="block text-xs font-semibold text-slate-700 mb-1">
                Unit <span className="text-rose-600">*</span>
              </label>
              <input
                id={unitInput}
                type="text"
                readOnly
                value={form.unit}
                aria-invalid={Boolean(fieldErrors.unit)}
                aria-describedby={fieldErrors.unit ? `${unitInput}-err` : undefined}
                className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 bg-slate-100 text-slate-700"
              />
              {fieldErrors.unit && (
                <p id={`${unitInput}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                  {fieldErrors.unit}
                </p>
              )}
            </div>
          </div>

          <div>
            <label
              htmlFor={receivedDateInput}
              className="block text-xs font-semibold text-slate-700 mb-1"
            >
              Received Date <span className="text-rose-600">*</span>
            </label>
            <input
              id={receivedDateInput}
              type="date"
              value={form.receivedDate}
              onChange={(e) => handleFieldChange('receivedDate', e.target.value)}
              aria-invalid={Boolean(fieldErrors.receivedDate)}
              aria-describedby={fieldErrors.receivedDate ? `${receivedDateInput}-err` : undefined}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 tabular-nums focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
            {fieldErrors.receivedDate && (
              <p id={`${receivedDateInput}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.receivedDate}
              </p>
            )}
          </div>

          <div>
            <label
              htmlFor={expiryDateInput}
              className="block text-xs font-semibold text-slate-700 mb-1"
            >
              Lot Expiry Date <span className="text-rose-600">*</span>
            </label>
            <input
              id={expiryDateInput}
              type="date"
              value={form.expiryDate}
              onChange={(e) => handleFieldChange('expiryDate', e.target.value)}
              aria-invalid={Boolean(fieldErrors.expiryDate)}
              aria-describedby={fieldErrors.expiryDate ? `${expiryDateInput}-err` : undefined}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 tabular-nums focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
            {fieldErrors.expiryDate && (
              <p id={`${expiryDateInput}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.expiryDate}
              </p>
            )}
          </div>

          <div>
            <label
              htmlFor={supplierNameInput}
              className="block text-xs font-semibold text-slate-700 mb-1"
            >
              Supplier Name <span className="text-rose-600">*</span>
            </label>
            <input
              id={supplierNameInput}
              type="text"
              value={form.supplierName}
              onChange={(e) => handleFieldChange('supplierName', e.target.value)}
              placeholder="e.g., Granules India Ltd."
              aria-invalid={Boolean(fieldErrors.supplierName)}
              aria-describedby={fieldErrors.supplierName ? `${supplierNameInput}-err` : undefined}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
            {fieldErrors.supplierName && (
              <p id={`${supplierNameInput}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.supplierName}
              </p>
            )}
          </div>

          <div>
            <label htmlFor={supplierRefInput} className="block text-xs font-semibold text-slate-700 mb-1">
              Supplier COA / Ref #
            </label>
            <input
              id={supplierRefInput}
              type="text"
              value={form.supplierReference}
              onChange={(e) => handleFieldChange('supplierReference', e.target.value)}
              placeholder="e.g., COA-2026-8821"
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
          </div>

          <div>
            <label htmlFor={refNumberInput} className="block text-xs font-semibold text-slate-700 mb-1">
              Receipt / PO Reference #
            </label>
            <input
              id={refNumberInput}
              type="text"
              value={form.referenceNumber}
              onChange={(e) => handleFieldChange('referenceNumber', e.target.value)}
              placeholder="Optional unique doc ref (e.g., PO-44102)"
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
          </div>

          <div>
            <label htmlFor={reasonInput} className="block text-xs font-semibold text-slate-700 mb-1">
              Receiving Remarks
            </label>
            <input
              id={reasonInput}
              type="text"
              value={form.reason}
              onChange={(e) => handleChangeNoteCompat(handleFieldChange, e.target.value)}
              placeholder="Container seal intact, visual check passed"
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
          </div>
        </div>
      </form>
    </ModalDialog>
  );
}

function handleChangeNoteCompat(fn, val) {
  fn('reason', val);
}

