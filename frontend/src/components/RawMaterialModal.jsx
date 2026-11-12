import React, { useEffect, useId, useState } from 'react';
import { FlaskConical, Package } from 'lucide-react';
import { InlineAlert, ModalDialog } from './ui/index.js';
import {
  MATERIAL_TYPE_LABELS,
  MATERIAL_TYPES,
  validateRawMaterialForm,
} from '../utils/inventoryUtils.js';

export default function RawMaterialModal({
  isOpen,
  onClose,
  material = null,
  recipeReferences = [],
  prefillReference = null,
  onSave,
  isSubmitting = false,
}) {
  const isEditing = Boolean(material?.id);

  const codeId = useId();
  const nameId = useId();
  const typeId = useId();
  const unitId = useId();
  const minStockId = useId();
  const descId = useId();
  const activeId = useId();
  const refSelectId = useId();

  const [form, setForm] = useState({
    materialCode: '',
    materialName: '',
    materialType: 'API',
    unit: 'kg',
    minimumStockLevel: '0',
    description: '',
    active: true,
  });
  const [selectedRefKey, setSelectedRefKey] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});
  const [serverError, setServerError] = useState(null);

  const hasExistingLots = Boolean(
    isEditing &&
      (Number(material?.activeLotCount || 0) > 0 ||
        Number(material?.quarantinedStock || 0) > 0 ||
        Number(material?.expiredLotCount || 0) > 0 ||
        Number(material?.expiredStock || 0) > 0)
  );

  useEffect(() => {
    if (!isOpen) return;
    setFieldErrors({});
    setServerError(null);
    setSelectedRefKey('');

    if (material) {
      setForm({
        materialCode: material.materialCode || '',
        materialName: material.materialName || '',
        materialType: material.materialType || 'API',
        unit: material.unit || '',
        minimumStockLevel:
          material.minimumStockLevel !== null && material.minimumStockLevel !== undefined
            ? String(material.minimumStockLevel)
            : '0',
        description: material.description || '',
        active: material.active !== false,
      });
    } else if (prefillReference) {
      const suggestedCode = String(prefillReference.materialName || '')
        .trim()
        .toUpperCase()
        .replace(/[^A-Z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 20);
      setForm({
        materialCode: suggestedCode ? `RM-${suggestedCode}` : '',
        materialName: prefillReference.materialName || '',
        materialType: 'API',
        unit: prefillReference.unit || 'kg',
        minimumStockLevel: '10',
        description:
          Array.isArray(prefillReference.recipeCodes) && prefillReference.recipeCodes.length > 0
            ? `Linked to formulation(s): ${prefillReference.recipeCodes.join(', ')}`
            : '',
        active: true,
      });
      setSelectedRefKey(prefillReference.materialName || '');
    } else {
      setForm({
        materialCode: '',
        materialName: '',
        materialType: 'API',
        unit: 'kg',
        minimumStockLevel: '0',
        description: '',
        active: true,
      });
    }
  }, [isOpen, material, prefillReference]);

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

  const handleApplyRecipeReference = (refName) => {
    setSelectedRefKey(refName);
    const match = (Array.isArray(recipeReferences) ? recipeReferences : []).find(
      (item) => item.materialName === refName
    );
    if (!match) return;
    const suggestedCode = String(match.materialName || '')
      .trim()
      .toUpperCase()
      .replace(/[^A-Z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 20);
    setForm((prev) => ({
      ...prev,
      materialCode: prev.materialCode.trim() ? prev.materialCode : suggestedCode ? `RM-${suggestedCode}` : '',
      materialName: match.materialName || prev.materialName,
      unit: match.unit || prev.unit,
      description:
        prev.description.trim() ||
        (Array.isArray(match.recipeCodes) && match.recipeCodes.length > 0
          ? `Used in recipes: ${match.recipeCodes.join(', ')}`
          : ''),
    }));
    setFieldErrors({});
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (isSubmitting) return;

    const validation = validateRawMaterialForm(form);
    if (!validation.isValid) {
      setFieldErrors(validation.errors);
      return;
    }

    const payload = {
      materialCode: form.materialCode.trim().toUpperCase(),
      materialName: form.materialName.trim(),
      materialType: form.materialType,
      unit: form.unit.trim(),
      minimumStockLevel: Number(form.minimumStockLevel),
      description: form.description.trim() || null,
      active: Boolean(form.active),
    };

    try {
      await onSave(payload);
    } catch (err) {
      setServerError(err.message || 'Failed to save raw material');
    }
  };

  const unregisteredReferences = (Array.isArray(recipeReferences) ? recipeReferences : []).filter(
    (ref) => !ref.registeredInInventory
  );

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={onClose}
      preventClose={isSubmitting}
      size="lg"
      zIndexClass="z-50"
      title={isEditing ? 'Edit Raw Material Master' : 'Register Raw Material'}
      subtitle={
        isEditing
          ? `Updating specification for ${material.materialCode}`
          : 'Define a tracked raw material and reorder threshold for warehouse receipts and recipe issue'
      }
      icon={
        <div className="w-9 h-9 rounded-lg bg-pharma-100 text-pharma-700 flex items-center justify-center">
          <Package className="w-5 h-5" aria-hidden="true" />
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
            form="raw-material-form"
            disabled={isSubmitting}
            className="px-4 py-2 text-sm font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors disabled:opacity-50"
          >
            {isSubmitting ? 'Saving...' : isEditing ? 'Save Changes' : 'Create Material'}
          </button>
        </>
      }
    >
      <form
        id="raw-material-form"
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

        {!isEditing && unregisteredReferences.length > 0 && (
          <div className="bg-pharma-50/70 border border-pharma-200 rounded-lg p-3.5 space-y-2">
            <label
              htmlFor={refSelectId}
              className="text-xs font-semibold text-pharma-900 flex items-center space-x-1.5"
            >
              <FlaskConical className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
              <span>Optional: Prefill from Unregistered Recipe Material</span>
            </label>
            <select
              id={refSelectId}
              value={selectedRefKey}
              onChange={(e) => handleApplyRecipeReference(e.target.value)}
              className="w-full px-3 py-2 text-sm rounded-lg border border-pharma-200 bg-white text-slate-800 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              <option value="">-- Choose a Master Recipe material to link --</option>
              {unregisteredReferences.map((ref) => (
                <option key={`${ref.materialName}-${ref.unit}`} value={ref.materialName}>
                  {ref.materialName} ({ref.unit || 'unit'}) — {(ref.recipeCodes || []).join(', ')}
                </option>
              ))}
            </select>
          </div>
        )}

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label htmlFor={codeId} className="block text-xs font-semibold text-slate-700 mb-1">
              Material Code <span className="text-rose-600">*</span>
            </label>
            <input
              id={codeId}
              type="text"
              value={form.materialCode}
              onChange={(e) => handleFieldChange('materialCode', e.target.value)}
              placeholder="e.g., RM-API-PARA"
              aria-invalid={Boolean(fieldErrors.materialCode)}
              aria-describedby={fieldErrors.materialCode ? `${codeId}-err` : undefined}
              className="w-full px-3 py-2 text-sm font-mono uppercase rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
            {fieldErrors.materialCode && (
              <p id={`${codeId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.materialCode}
              </p>
            )}
          </div>

          <div>
            <label htmlFor={typeId} className="block text-xs font-semibold text-slate-700 mb-1">
              Material Type <span className="text-rose-600">*</span>
            </label>
            <select
              id={typeId}
              value={form.materialType}
              onChange={(e) => handleFieldChange('materialType', e.target.value)}
              aria-invalid={Boolean(fieldErrors.materialType)}
              aria-describedby={fieldErrors.materialType ? `${typeId}-err` : undefined}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              {MATERIAL_TYPES.map((typeKey) => (
                <option key={typeKey} value={typeKey}>
                  {MATERIAL_TYPE_LABELS[typeKey] || typeKey}
                </option>
              ))}
            </select>
            {fieldErrors.materialType && (
              <p id={`${typeId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.materialType}
              </p>
            )}
          </div>
        </div>

        <div>
          <label htmlFor={nameId} className="block text-xs font-semibold text-slate-700 mb-1">
            Material Name <span className="text-rose-600">*</span>
          </label>
          <input
            id={nameId}
            type="text"
            value={form.materialName}
            onChange={(e) => handleFieldChange('materialName', e.target.value)}
            placeholder="Must match exact recipe material name for BOM linking (e.g., Paracetamol API)"
            aria-invalid={Boolean(fieldErrors.materialName)}
            aria-describedby={fieldErrors.materialName ? `${nameId}-err` : undefined}
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
          />
          {fieldErrors.materialName && (
            <p id={`${nameId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
              {fieldErrors.materialName}
            </p>
          )}
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label htmlFor={unitId} className="block text-xs font-semibold text-slate-700 mb-1">
              Unit of Measurement <span className="text-rose-600">*</span>
            </label>
            <input
              id={unitId}
              type="text"
              value={form.unit}
              disabled={hasExistingLots}
              onChange={(e) => handleFieldChange('unit', e.target.value)}
              placeholder="e.g., kg, g, L, units"
              aria-invalid={Boolean(fieldErrors.unit)}
              aria-describedby={fieldErrors.unit ? `${unitId}-err` : undefined}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 disabled:bg-slate-100 disabled:text-slate-500"
            />
            {hasExistingLots && (
              <p className="text-[11px] text-amber-700 mt-1">
                Unit is locked because lots already exist for this material.
              </p>
            )}
            {fieldErrors.unit && (
              <p id={`${unitId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.unit}
              </p>
            )}
          </div>

          <div>
            <label htmlFor={minStockId} className="block text-xs font-semibold text-slate-700 mb-1">
              Minimum Stock Threshold <span className="text-rose-600">*</span>
            </label>
            <input
              id={minStockId}
              type="number"
              step="any"
              min="0"
              value={form.minimumStockLevel}
              onChange={(e) => handleFieldChange('minimumStockLevel', e.target.value)}
              aria-invalid={Boolean(fieldErrors.minimumStockLevel)}
              aria-describedby={fieldErrors.minimumStockLevel ? `${minStockId}-err` : undefined}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 tabular-nums focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
            {fieldErrors.minimumStockLevel && (
              <p id={`${minStockId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.minimumStockLevel}
              </p>
            )}
          </div>
        </div>

        <div>
          <label htmlFor={descId} className="block text-xs font-semibold text-slate-700 mb-1">
            Specification / Handling Notes
          </label>
          <textarea
            id={descId}
            rows={2}
            value={form.description}
            onChange={(e) => handleFieldChange('description', e.target.value)}
            placeholder="Grade, handling instructions, or supplier qualification notes..."
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
            onChange={(e) => handleFieldChange('active', e.target.checked)}
            className="w-4 h-4 rounded border-slate-300 text-pharma-600 focus:ring-pharma-500"
          />
          <span className="text-xs font-medium text-slate-700">
            Active material (eligible for goods receipts and manufacturing issues)
          </span>
        </label>
      </form>
    </ModalDialog>
  );
}

