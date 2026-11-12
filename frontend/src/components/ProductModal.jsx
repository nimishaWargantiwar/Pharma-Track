import React, { useState, useEffect, useId, useRef } from 'react';
import { Pill, Save } from 'lucide-react';
import { InlineAlert, ModalDialog, StatusBadge } from './ui/index.js';

const DOSAGE_FORM_OPTIONS = [
  'Tablet',
  'Capsule',
  'Syrup',
  'Injection',
  'Suspension',
  'Ointment',
  'Inhalation',
  'Solution',
];

function buildInitialProductForm(initialData) {
  if (initialData) {
    return {
      productCode: initialData.productCode || '',
      productName: initialData.productName || '',
      strength: initialData.strength || '',
      dosageForm: initialData.dosageForm || 'Tablet',
      description: initialData.description || '',
      status: initialData.status || 'ACTIVE',
    };
  }
  return {
    productCode: '',
    productName: '',
    strength: '',
    dosageForm: 'Tablet',
    description: '',
    status: 'ACTIVE',
  };
}

export default function ProductModal({ isOpen, onClose, onSave, initialData, isSubmitting }) {
  const isEditMode = Boolean(initialData && initialData.id);
  const baseId = useId();
  const productCodeRef = useRef(null);
  const wasOpenRef = useRef(false);
  const lastRecordKeyRef = useRef(null);

  const [formData, setFormData] = useState(() => buildInitialProductForm(initialData));
  const [errors, setErrors] = useState({});
  const [serverError, setServerError] = useState('');

  const recordKey = isOpen ? (initialData?.id ? `edit-${initialData.id}` : 'create') : null;

  useEffect(() => {
    if (!isOpen) {
      wasOpenRef.current = false;
      lastRecordKeyRef.current = null;
      return;
    }

    if (!wasOpenRef.current || lastRecordKeyRef.current !== recordKey) {
      wasOpenRef.current = true;
      lastRecordKeyRef.current = recordKey;
      setFormData(buildInitialProductForm(initialData));
      setErrors({});
      setServerError('');
    }
  }, [isOpen, recordKey, initialData]);

  const validate = () => {
    const newErrors = {};

    if (!formData.productCode.trim()) {
      newErrors.productCode = 'Product code is required';
    } else if (formData.productCode.length > 50) {
      newErrors.productCode = 'Product code cannot exceed 50 characters';
    }

    if (!formData.productName.trim()) {
      newErrors.productName = 'Product name is required';
    } else if (formData.productName.length > 255) {
      newErrors.productName = 'Product name cannot exceed 255 characters';
    }

    if (!formData.strength.trim()) {
      newErrors.strength = 'Strength is required (e.g., 500 mg)';
    }

    if (!formData.dosageForm.trim()) {
      newErrors.dosageForm = 'Dosage form is required';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
    if (errors[name]) {
      setErrors((prev) => ({ ...prev, [name]: '' }));
    }
  };

  const handleSubmit = async (e) => {
    e?.preventDefault?.();
    setServerError('');

    if (!validate()) {
      return;
    }

    try {
      await onSave(formData);
    } catch (err) {
      if (err.validationErrors) {
        setErrors(err.validationErrors);
      } else {
        setServerError(err.message || 'Failed to save product');
      }
    }
  };

  const codeInputId = `${baseId}-product-code`;
  const codeErrorId = `${baseId}-product-code-error`;
  const nameInputId = `${baseId}-product-name`;
  const nameErrorId = `${baseId}-product-name-error`;
  const strengthInputId = `${baseId}-strength`;
  const strengthErrorId = `${baseId}-strength-error`;
  const dosageInputId = `${baseId}-dosage-form`;
  const dosageErrorId = `${baseId}-dosage-form-error`;
  const statusActiveId = `${baseId}-status-active`;
  const statusInactiveId = `${baseId}-status-inactive`;
  const descriptionInputId = `${baseId}-description`;

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={onClose}
      preventClose={isSubmitting}
      size="lg"
      headingAs="h3"
      initialFocusRef={productCodeRef}
      title={isEditMode ? 'Edit Pharmaceutical Product' : 'Register New Product'}
      subtitle={
        isEditMode
          ? 'Update master product formulation details'
          : 'Add product master record to the catalog'
      }
      icon={
        <div className="w-8 h-8 rounded-lg bg-pharma-600 flex items-center justify-center text-white">
          <Pill className="w-4 h-4" aria-hidden="true" />
        </div>
      }
      bodyClassName="overflow-y-auto"
      maxHeightClass="max-h-[90vh]"
      footer={
        <>
          <button
            type="button"
            onClick={onClose}
            disabled={isSubmitting}
            className="px-4 py-2 text-sm font-medium text-slate-600 hover:text-slate-800 bg-slate-100 hover:bg-slate-200 rounded-lg transition-colors disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={isSubmitting}
            className="inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-medium text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors disabled:opacity-50"
          >
            <Save className="w-4 h-4" aria-hidden="true" />
            <span>{isSubmitting ? 'Saving...' : isEditMode ? 'Update Product' : 'Create Product'}</span>
          </button>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate className="p-4 sm:p-6 space-y-4">
        {serverError && (
          <InlineAlert
            variant="error"
            compact
            message={serverError}
            onDismiss={() => setServerError('')}
            dismissLabel="Dismiss form error"
          />
        )}

        <div>
          <label
            htmlFor={codeInputId}
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
          >
            Product Code <span className="text-rose-500">*</span>
          </label>
          <input
            ref={productCodeRef}
            id={codeInputId}
            type="text"
            name="productCode"
            value={formData.productCode}
            onChange={handleChange}
            placeholder="e.g. PRD-TAB-500"
            disabled={isSubmitting}
            aria-invalid={Boolean(errors.productCode)}
            aria-describedby={errors.productCode ? codeErrorId : undefined}
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors font-mono ${
              errors.productCode
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
            }`}
          />
          {errors.productCode && (
            <p id={codeErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {errors.productCode}
            </p>
          )}
        </div>

        <div>
          <label
            htmlFor={nameInputId}
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
          >
            Product Name <span className="text-rose-500">*</span>
          </label>
          <input
            id={nameInputId}
            type="text"
            name="productName"
            value={formData.productName}
            onChange={handleChange}
            placeholder="e.g. Paracetamol Extra"
            disabled={isSubmitting}
            aria-invalid={Boolean(errors.productName)}
            aria-describedby={errors.productName ? nameErrorId : undefined}
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
              errors.productName
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
            }`}
          />
          {errors.productName && (
            <p id={nameErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {errors.productName}
            </p>
          )}
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label
              htmlFor={strengthInputId}
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
            >
              Strength <span className="text-rose-500">*</span>
            </label>
            <input
              id={strengthInputId}
              type="text"
              name="strength"
              value={formData.strength}
              onChange={handleChange}
              placeholder="e.g. 500 mg, 10 mg/mL"
              disabled={isSubmitting}
              aria-invalid={Boolean(errors.strength)}
              aria-describedby={errors.strength ? strengthErrorId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
                errors.strength
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
              }`}
            />
            {errors.strength && (
              <p id={strengthErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {errors.strength}
              </p>
            )}
          </div>

          <div>
            <label
              htmlFor={dosageInputId}
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
            >
              Dosage Form <span className="text-rose-500">*</span>
            </label>
            <select
              id={dosageInputId}
              name="dosageForm"
              value={formData.dosageForm}
              onChange={handleChange}
              disabled={isSubmitting}
              aria-invalid={Boolean(errors.dosageForm)}
              aria-describedby={errors.dosageForm ? dosageErrorId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 bg-white ${
                errors.dosageForm
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
              }`}
            >
              {DOSAGE_FORM_OPTIONS.map((form) => (
                <option key={form} value={form}>
                  {form}
                </option>
              ))}
            </select>
            {errors.dosageForm && (
              <p id={dosageErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {errors.dosageForm}
              </p>
            )}
          </div>
        </div>

        <fieldset>
          <legend className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
            Status <span className="text-rose-500">*</span>
          </legend>
          <div className="flex items-center space-x-4">
            <label
              htmlFor={statusActiveId}
              className="flex items-center space-x-2 text-sm text-slate-700 cursor-pointer"
            >
              <input
                id={statusActiveId}
                type="radio"
                name="status"
                value="ACTIVE"
                checked={formData.status === 'ACTIVE'}
                onChange={handleChange}
                disabled={isSubmitting}
                className="text-pharma-600 focus:ring-pharma-500"
              />
              <StatusBadge status="ACTIVE" variant="tag" size="xs" />
            </label>

            <label
              htmlFor={statusInactiveId}
              className="flex items-center space-x-2 text-sm text-slate-700 cursor-pointer"
            >
              <input
                id={statusInactiveId}
                type="radio"
                name="status"
                value="INACTIVE"
                checked={formData.status === 'INACTIVE'}
                onChange={handleChange}
                disabled={isSubmitting}
                className="text-pharma-600 focus:ring-pharma-500"
              />
              <StatusBadge status="INACTIVE" variant="tag" size="xs" />
            </label>
          </div>
        </fieldset>

        <div>
          <label
            htmlFor={descriptionInputId}
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
          >
            Description <span className="text-slate-500 font-normal">(Optional)</span>
          </label>
          <textarea
            id={descriptionInputId}
            name="description"
            rows={3}
            value={formData.description}
            onChange={handleChange}
            placeholder="Therapeutic indications, packaging notes, storage guidelines..."
            disabled={isSubmitting}
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 bg-white resize-none"
          />
        </div>
      </form>
    </ModalDialog>
  );
}
