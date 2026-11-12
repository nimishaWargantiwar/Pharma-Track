import React, { useEffect, useId, useState } from 'react';
import { Warehouse } from 'lucide-react';
import { InlineAlert, ModalDialog } from './ui/index.js';
import { validateWarehouseForm } from '../utils/inventoryUtils.js';

export default function WarehouseModal({
  isOpen,
  onClose,
  warehouse = null,
  onSave,
  isSubmitting = false,
}) {
  const isEditing = Boolean(warehouse?.id);

  const codeId = useId();
  const nameId = useId();
  const conditionId = useId();
  const summaryId = useId();
  const activeId = useId();

  const [form, setForm] = useState({
    warehouseCode: '',
    warehouseName: '',
    storageCondition: '',
    locationSummary: '',
    active: true,
  });
  const [fieldErrors, setFieldErrors] = useState({});
  const [serverError, setServerError] = useState(null);

  useEffect(() => {
    if (!isOpen) return;
    setFieldErrors({});
    setServerError(null);
    if (warehouse) {
      setForm({
        warehouseCode: warehouse.warehouseCode || '',
        warehouseName: warehouse.warehouseName || '',
        storageCondition: warehouse.storageCondition || '',
        locationSummary: warehouse.locationSummary || '',
        active: warehouse.active !== false,
      });
    } else {
      setForm({
        warehouseCode: '',
        warehouseName: '',
        storageCondition: 'Ambient (15-25 C)',
        locationSummary: '',
        active: true,
      });
    }
  }, [isOpen, warehouse]);

  const handleChange = (field, value) => {
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

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (isSubmitting) return;

    const validation = validateWarehouseForm(form);
    if (!validation.isValid) {
      setFieldErrors(validation.errors);
      return;
    }

    const payload = {
      warehouseCode: form.warehouseCode.trim().toUpperCase(),
      warehouseName: form.warehouseName.trim(),
      storageCondition: form.storageCondition.trim() || null,
      locationSummary: form.locationSummary.trim() || null,
      active: Boolean(form.active),
    };

    try {
      await onSave(payload);
    } catch (err) {
      setServerError(err.message || 'Failed to save warehouse');
    }
  };

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={onClose}
      preventClose={isSubmitting}
      size="md"
      zIndexClass="z-50"
      title={isEditing ? 'Edit Warehouse' : 'Create Warehouse'}
      subtitle={
        isEditing
          ? `Updating facility details for ${warehouse.warehouseCode}`
          : 'Register a storage facility before adding aisles, racks, or material lots'
      }
      icon={
        <div className="w-9 h-9 rounded-lg bg-pharma-100 text-pharma-700 flex items-center justify-center">
          <Warehouse className="w-5 h-5" aria-hidden="true" />
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
            form="warehouse-form"
            disabled={isSubmitting}
            className="px-4 py-2 text-sm font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors disabled:opacity-50"
          >
            {isSubmitting ? 'Saving...' : isEditing ? 'Save Changes' : 'Create Warehouse'}
          </button>
        </>
      }
    >
      <form id="warehouse-form" onSubmit={handleSubmit} noValidate className="p-4 sm:p-6 space-y-4">
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
          <div>
            <label htmlFor={codeId} className="block text-xs font-semibold text-slate-700 mb-1">
              Warehouse Code <span className="text-rose-600">*</span>
            </label>
            <input
              id={codeId}
              type="text"
              value={form.warehouseCode}
              onChange={(e) => handleChange('warehouseCode', e.target.value)}
              placeholder="e.g., WH-MAIN"
              aria-invalid={Boolean(fieldErrors.warehouseCode)}
              aria-describedby={fieldErrors.warehouseCode ? `${codeId}-err` : undefined}
              className="w-full px-3 py-2 text-sm font-mono uppercase rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
            {fieldErrors.warehouseCode && (
              <p id={`${codeId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.warehouseCode}
              </p>
            )}
          </div>

          <div>
            <label htmlFor={conditionId} className="block text-xs font-semibold text-slate-700 mb-1">
              Storage Condition
            </label>
            <input
              id={conditionId}
              type="text"
              value={form.storageCondition}
              onChange={(e) => handleChange('storageCondition', e.target.value)}
              placeholder="e.g., Controlled 15-25 C"
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
          </div>
        </div>

        <div>
          <label htmlFor={nameId} className="block text-xs font-semibold text-slate-700 mb-1">
            Warehouse Name <span className="text-rose-600">*</span>
          </label>
          <input
            id={nameId}
            type="text"
            value={form.warehouseName}
            onChange={(e) => handleChange('warehouseName', e.target.value)}
            placeholder="e.g., Main Raw Material Store"
            aria-invalid={Boolean(fieldErrors.warehouseName)}
            aria-describedby={fieldErrors.warehouseName ? `${nameId}-err` : undefined}
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
          />
          {fieldErrors.warehouseName && (
            <p id={`${nameId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
              {fieldErrors.warehouseName}
            </p>
          )}
        </div>

        <div>
          <label htmlFor={summaryId} className="block text-xs font-semibold text-slate-700 mb-1">
            Facility Location / Notes
          </label>
          <input
            id={summaryId}
            type="text"
            value={form.locationSummary}
            onChange={(e) => handleChange('locationSummary', e.target.value)}
            placeholder="Building, bay, or staging summary..."
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
          />
        </div>

        <label
          htmlFor={activeId}
          className="flex items-center space-x-2.5 p-3 rounded-lg bg-slate-50 border border-slate-200 cursor-pointer"
        >
          <input
            id={activeId}
            type="checkbox"
            checked={Boolean(form.active)}
            onChange={(e) => handleChange('active', e.target.checked)}
            className="w-4 h-4 rounded border-slate-300 text-pharma-600 focus:ring-pharma-500"
          />
          <span className="text-xs font-medium text-slate-700">
            Active warehouse (available for receiving material lots)
          </span>
        </label>
      </form>
    </ModalDialog>
  );
}

