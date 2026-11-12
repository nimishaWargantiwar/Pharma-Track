import React, { useEffect, useId, useState } from 'react';
import { MapPin } from 'lucide-react';
import { InlineAlert, ModalDialog } from './ui/index.js';
import { validateStorageLocationForm } from '../utils/inventoryUtils.js';

export default function StorageLocationModal({
  isOpen,
  onClose,
  location = null,
  warehouses = [],
  defaultWarehouseId = null,
  onSave,
  isSubmitting = false,
}) {
  const isEditing = Boolean(location?.id);

  const warehouseSelectId = useId();
  const codeId = useId();
  const zoneId = useId();
  const aisleId = useId();
  const rackId = useId();
  const binId = useId();
  const activeId = useId();

  const [form, setForm] = useState({
    warehouseId: '',
    locationCode: '',
    zoneName: '',
    aisle: '',
    rack: '',
    bin: '',
    active: true,
  });
  const [fieldErrors, setFieldErrors] = useState({});
  const [serverError, setServerError] = useState(null);

  const activeWarehouses = (Array.isArray(warehouses) ? warehouses : []).filter((w) => w.active);

  useEffect(() => {
    if (!isOpen) return;
    setFieldErrors({});
    setServerError(null);
    if (location) {
      setForm({
        warehouseId: location.warehouseId ? String(location.warehouseId) : '',
        locationCode: location.locationCode || '',
        zoneName: location.zoneName || '',
        aisle: location.aisle || '',
        rack: location.rack || '',
        bin: location.bin || '',
        active: location.active !== false,
      });
    } else {
      const initialWh =
        defaultWarehouseId != null && defaultWarehouseId !== ''
          ? String(defaultWarehouseId)
          : activeWarehouses[0]?.id
            ? String(activeWarehouses[0].id)
            : '';
      setForm({
        warehouseId: initialWh,
        locationCode: '',
        zoneName: '',
        aisle: '',
        rack: '',
        bin: '',
        active: true,
      });
    }
  }, [isOpen, location, defaultWarehouseId]);

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

    const validation = validateStorageLocationForm(form);
    const errors = { ...validation.errors };
    if (!isEditing && !form.warehouseId) {
      errors.warehouseId = 'Select an active parent warehouse';
    }
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      return;
    }

    const payload = {
      warehouseId: Number(form.warehouseId),
      locationCode: form.locationCode.trim().toUpperCase(),
      zoneName: form.zoneName.trim() || null,
      aisle: form.aisle.trim() || null,
      rack: form.rack.trim() || null,
      bin: form.bin.trim() || null,
      active: Boolean(form.active),
    };

    try {
      await onSave(Number(form.warehouseId), payload);
    } catch (err) {
      setServerError(err.message || 'Failed to save storage location');
    }
  };

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={onClose}
      preventClose={isSubmitting}
      size="md"
      zIndexClass="z-50"
      title={isEditing ? 'Edit Storage Location' : 'Add Storage Location'}
      subtitle={
        isEditing
          ? `Updating bin/rack in ${location.warehouseCode || 'warehouse'}`
          : 'Define a bin, rack, or staging zone inside an active warehouse'
      }
      icon={
        <div className="w-9 h-9 rounded-lg bg-pharma-100 text-pharma-700 flex items-center justify-center">
          <MapPin className="w-5 h-5" aria-hidden="true" />
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
            form="storage-location-form"
            disabled={isSubmitting}
            className="px-4 py-2 text-sm font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors disabled:opacity-50"
          >
            {isSubmitting ? 'Saving...' : isEditing ? 'Save Changes' : 'Create Location'}
          </button>
        </>
      }
    >
      <form
        id="storage-location-form"
        onSubmit={handleSubmit}
        noValidate
        className="p-4 sm:p-6 space-y-4"
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
          <label
            htmlFor={warehouseSelectId}
            className="block text-xs font-semibold text-slate-700 mb-1"
          >
            Parent Warehouse <span className="text-rose-600">*</span>
          </label>
          <select
            id={warehouseSelectId}
            value={form.warehouseId}
            disabled={isEditing}
            onChange={(e) => handleChange('warehouseId', e.target.value)}
            aria-invalid={Boolean(fieldErrors.warehouseId)}
            aria-describedby={fieldErrors.warehouseId ? `${warehouseSelectId}-err` : undefined}
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 disabled:bg-slate-100 disabled:text-slate-500"
          >
            <option value="">Select active warehouse...</option>
            {(isEditing ? warehouses : activeWarehouses).map((wh) => (
              <option key={wh.id} value={String(wh.id)}>
                {wh.warehouseCode} — {wh.warehouseName}
              </option>
            ))}
          </select>
          {fieldErrors.warehouseId && (
            <p id={`${warehouseSelectId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
              {fieldErrors.warehouseId}
            </p>
          )}
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label htmlFor={codeId} className="block text-xs font-semibold text-slate-700 mb-1">
              Location Code <span className="text-rose-600">*</span>
            </label>
            <input
              id={codeId}
              type="text"
              value={form.locationCode}
              onChange={(e) => handleChange('locationCode', e.target.value)}
              placeholder="e.g., A1-R01-B01"
              aria-invalid={Boolean(fieldErrors.locationCode)}
              aria-describedby={fieldErrors.locationCode ? `${codeId}-err` : undefined}
              className="w-full px-3 py-2 text-sm font-mono uppercase rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
            {fieldErrors.locationCode && (
              <p id={`${codeId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.locationCode}
              </p>
            )}
          </div>

          <div>
            <label htmlFor={zoneId} className="block text-xs font-semibold text-slate-700 mb-1">
              Zone / Area
            </label>
            <input
              id={zoneId}
              type="text"
              value={form.zoneName}
              onChange={(e) => handleChange('zoneName', e.target.value)}
              placeholder="e.g., API Vault, Dispensing Zone"
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
          </div>
        </div>

        <div className="grid grid-cols-3 gap-3">
          <div>
            <label htmlFor={aisleId} className="block text-xs font-semibold text-slate-700 mb-1">
              Aisle
            </label>
            <input
              id={aisleId}
              type="text"
              value={form.aisle}
              onChange={(e) => handleChange('aisle', e.target.value)}
              placeholder="A1"
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
          </div>
          <div>
            <label htmlFor={rackId} className="block text-xs font-semibold text-slate-700 mb-1">
              Rack
            </label>
            <input
              id={rackId}
              type="text"
              value={form.rack}
              onChange={(e) => handleChange('rack', e.target.value)}
              placeholder="R01"
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
          </div>
          <div>
            <label htmlFor={binId} className="block text-xs font-semibold text-slate-700 mb-1">
              Bin
            </label>
            <input
              id={binId}
              type="text"
              value={form.bin}
              onChange={(e) => handleChange('bin', e.target.value)}
              placeholder="B02"
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
          </div>
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
            Active storage location (available for storing new lots)
          </span>
        </label>
      </form>
    </ModalDialog>
  );
}

