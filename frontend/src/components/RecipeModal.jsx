import React, { useState, useEffect, useCallback, useId, useRef } from 'react';
import {
  FlaskConical,
  Plus,
  Trash2,
  Save,
  Layers,
  CheckCircle2,
  RefreshCw,
} from 'lucide-react';
import { getAllProducts } from '../services/productApi';
import {
  getNextStepNumber,
  mapRecipeServerErrors,
  validateRecipeForm,
} from '../utils/recipeUtils';
import { InlineAlert, ModalDialog } from './ui/index.js';

export default function RecipeModal({ isOpen, onClose, onSave, initialData, isSubmitting }) {
  const isEditMode = Boolean(initialData && initialData.id);
  const baseId = useId();

  const keyCounterRef = useRef(0);
  const nextRowKey = useCallback((prefix) => {
    keyCounterRef.current += 1;
    return `${prefix}-${keyCounterRef.current}`;
  }, []);

  const buildInitialFormData = useCallback(
    (sourceData) => {
      if (sourceData) {
        return {
          productId: sourceData.productId || '',
          batchSize: sourceData.batchSize ?? '',
          batchUnit: sourceData.batchUnit || 'kg',
          description: sourceData.description || '',
          materials:
            sourceData.materials && sourceData.materials.length > 0
              ? sourceData.materials.map((m) => ({
                  _key: nextRowKey('mat'),
                  materialName: m.materialName || '',
                  quantity: m.quantity ?? '',
                  unit: m.unit || 'kg',
                }))
              : [{ _key: nextRowKey('mat'), materialName: '', quantity: '', unit: 'kg' }],
          steps:
            sourceData.steps && sourceData.steps.length > 0
              ? sourceData.steps.map((s, idx) => ({
                  _key: nextRowKey('step'),
                  stepNumber: s.stepNumber ?? idx + 1,
                  operationName: s.operationName || '',
                  instructions: s.instructions || '',
                }))
              : [
                  {
                    _key: nextRowKey('step'),
                    stepNumber: 1,
                    operationName: '',
                    instructions: '',
                  },
                ],
        };
      }
      return {
        productId: '',
        batchSize: '',
        batchUnit: 'kg',
        description: '',
        materials: [{ _key: nextRowKey('mat'), materialName: '', quantity: '', unit: 'kg' }],
        steps: [
          {
            _key: nextRowKey('step'),
            stepNumber: 1,
            operationName: '',
            instructions: '',
          },
        ],
      };
    },
    [nextRowKey]
  );

  const [activeProducts, setActiveProducts] = useState([]);
  const [isLoadingProducts, setIsLoadingProducts] = useState(false);
  const [productsError, setProductsError] = useState('');

  const [formData, setFormData] = useState(() => buildInitialFormData(initialData));
  const [errors, setErrors] = useState({});
  const [badStepInputs, setBadStepInputs] = useState({});
  const [serverError, setServerError] = useState('');

  const stepNumberRefs = useRef({});
  const productSelectRef = useRef(null);
  const batchSizeInputRef = useRef(null);
  const wasOpenRef = useRef(false);
  const lastRecordKeyRef = useRef(null);
  const isMountedRef = useRef(true);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  const recordKey = isOpen ? (initialData?.id ? `edit-${initialData.id}` : 'create') : null;

  // Initialize form state ONLY on open transitions or when editing a different recipe record
  useEffect(() => {
    if (!isOpen) {
      wasOpenRef.current = false;
      lastRecordKeyRef.current = null;
      return;
    }

    if (!wasOpenRef.current || lastRecordKeyRef.current !== recordKey) {
      wasOpenRef.current = true;
      lastRecordKeyRef.current = recordKey;
      setFormData(buildInitialFormData(initialData));
      setErrors({});
      setBadStepInputs({});
      setServerError('');
      setProductsError('');
      stepNumberRefs.current = {};
    }
  }, [isOpen, recordKey, initialData, buildInitialFormData]);

  const fetchActiveProducts = useCallback(async () => {
    if (!isOpen || isEditMode) return;
    setIsLoadingProducts(true);
    setProductsError('');
    try {
      const products = await getAllProducts();
      if (!isMountedRef.current || !wasOpenRef.current) return;
      const active = (Array.isArray(products) ? products : []).filter(
        (p) => p.status === 'ACTIVE'
      );
      setActiveProducts(active);
      if (active.length > 0) {
        setFormData((prev) => (prev.productId ? prev : { ...prev, productId: active[0].id }));
      }
    } catch (err) {
      if (!isMountedRef.current || !wasOpenRef.current) return;
      setProductsError(err.message || 'Unable to load active products');
    } finally {
      if (isMountedRef.current) {
        setIsLoadingProducts(false);
      }
    }
  }, [isOpen, isEditMode]);

  useEffect(() => {
    if (!isOpen || isEditMode) return;
    fetchActiveProducts();
  }, [isOpen, isEditMode, fetchActiveProducts]);

  // Material helpers
  const handleAddMaterial = () => {
    setFormData((prev) => ({
      ...prev,
      materials: [
        ...prev.materials,
        {
          _key: nextRowKey('mat'),
          materialName: '',
          quantity: '',
          unit: prev.batchUnit || 'kg',
        },
      ],
    }));
    if (errors.materials) {
      setErrors((prev) => ({ ...prev, materials: '' }));
    }
  };

  const handleRemoveMaterial = (index, targetKey) => {
    if (formData.materials.length <= 1) return;
    setFormData((prev) => ({
      ...prev,
      materials: prev.materials.filter((m, i) =>
        targetKey ? m._key !== targetKey : i !== index
      ),
    }));
    setErrors((prev) => {
      const next = {};
      Object.entries(prev).forEach(([key, val]) => {
        if (key === 'materials') return;
        const match = key.match(/^mat_(\d+)_(name|qty|unit)$/);
        if (!match) {
          next[key] = val;
          return;
        }
        const rowIdx = Number(match[1]);
        const suffix = match[2];
        if (rowIdx < index) next[key] = val;
        else if (rowIdx > index) next[`mat_${rowIdx - 1}_${suffix}`] = val;
      });
      return next;
    });
  };

  const handleMaterialChange = (index, field, value) => {
    setFormData((prev) => {
      const updated = [...prev.materials];
      updated[index] = { ...updated[index], [field]: value };
      return { ...prev, materials: updated };
    });

    const errFieldMap = {
      materialName: `mat_${index}_name`,
      quantity: `mat_${index}_qty`,
      unit: `mat_${index}_unit`,
    };
    const errorKey = errFieldMap[field];
    if (errorKey && errors[errorKey]) {
      setErrors((prev) => ({ ...prev, [errorKey]: '' }));
    }
  };

  // Step helpers
  const handleAddStep = () => {
    setFormData((prev) => ({
      ...prev,
      steps: [
        ...prev.steps,
        {
          _key: nextRowKey('step'),
          stepNumber: getNextStepNumber(prev.steps),
          operationName: '',
          instructions: '',
        },
      ],
    }));
    if (errors.steps) {
      setErrors((prev) => ({ ...prev, steps: '' }));
    }
  };

  const handleRemoveStep = (index, targetKey) => {
    if (formData.steps.length <= 1) return;
    setFormData((prev) => {
      const filtered = prev.steps.filter((s, i) =>
        targetKey ? s._key !== targetKey : i !== index
      );
      const reindexed = filtered.map((step, i) => ({ ...step, stepNumber: i + 1 }));
      return { ...prev, steps: reindexed };
    });

    if (targetKey) {
      delete stepNumberRefs.current[targetKey];
      setBadStepInputs((prev) => {
        if (!(targetKey in prev)) return prev;
        const next = { ...prev };
        delete next[targetKey];
        return next;
      });
    }

    setErrors((prev) => {
      const next = {};
      Object.entries(prev).forEach(([key, val]) => {
        if (key === 'steps') {
          return;
        }
        const match = key.match(/^step_(\d+)_(num|op|inst)$/);
        if (!match) {
          next[key] = val;
          return;
        }
        const rowIdx = Number(match[1]);
        const suffix = match[2];
        if (suffix === 'num') return;
        if (rowIdx < index) next[key] = val;
        else if (rowIdx > index) next[`step_${rowIdx - 1}_${suffix}`] = val;
      });
      return next;
    });
  };

  const handleStepChange = (index, stepKey, field, value, isBadInput = false) => {
    setFormData((prev) => {
      const updated = [...prev.steps];
      updated[index] = { ...updated[index], [field]: value };
      return { ...prev, steps: updated };
    });

    const errorKeyMap = {
      stepNumber: `step_${index}_num`,
      operationName: `step_${index}_op`,
      instructions: `step_${index}_inst`,
    };
    const errorKey = errorKeyMap[field];
    if (field === 'stepNumber') {
      setBadStepInputs((prev) => ({ ...prev, [stepKey]: isBadInput }));
      setErrors((prev) => {
        const next = { ...prev };
        if (next[errorKey]) {
          next[errorKey] = '';
        }
        if (next.steps) {
          next.steps = '';
        }
        Object.keys(next).forEach((key) => {
          if (
            /^step_\d+_num$/.test(key) &&
            typeof next[key] === 'string' &&
            next[key].startsWith('Duplicate step number')
          ) {
            next[key] = '';
          }
        });
        return next;
      });
    } else if (errorKey && errors[errorKey]) {
      setErrors((prev) => ({ ...prev, [errorKey]: '' }));
    }
  };

  const validate = () => {
    const effectiveBadStepInputs = {};
    formData.steps.forEach((step, idx) => {
      if (badStepInputs[step._key] || stepNumberRefs.current[step._key]?.validity?.badInput) {
        effectiveBadStepInputs[idx] = true;
      }
    });

    const { isValid, errors: newErrors } = validateRecipeForm(formData, {
      isEditMode,
      badStepInputs: effectiveBadStepInputs,
    });

    setErrors(newErrors);
    return isValid;
  };

  const handleSubmit = async (e) => {
    e?.preventDefault?.();
    setServerError('');

    if (!validate()) {
      return;
    }

    const payload = {
      productId: Number(formData.productId),
      batchSize: Number(formData.batchSize),
      batchUnit: formData.batchUnit.trim(),
      description: formData.description.trim() || null,
      materials: formData.materials.map((m) => ({
        materialName: m.materialName.trim(),
        quantity: Number(m.quantity),
        unit: m.unit.trim(),
      })),
      steps: formData.steps.map((s) => ({
        stepNumber: Number.parseInt(String(s.stepNumber).trim(), 10),
        operationName: s.operationName.trim(),
        instructions: s.instructions.trim(),
      })),
    };

    try {
      await onSave(payload);
    } catch (err) {
      const { fieldErrors, serverError: nextServerError } = mapRecipeServerErrors(err);
      if (Object.keys(fieldErrors).length > 0) {
        setErrors(fieldErrors);
      }
      setServerError(nextServerError);
    }
  };

  const productSelectId = `${baseId}-product`;
  const productErrorId = `${baseId}-product-error`;
  const batchSizeId = `${baseId}-batch-size`;
  const batchSizeErrorId = `${baseId}-batch-size-error`;
  const batchUnitId = `${baseId}-batch-unit`;
  const batchUnitErrorId = `${baseId}-batch-unit-error`;
  const descriptionId = `${baseId}-description`;

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={onClose}
      preventClose={isSubmitting}
      size="5xl"
      maxHeightClass="max-h-[92vh]"
      headingAs="h3"
      initialFocusRef={isEditMode ? batchSizeInputRef : productSelectRef}
      title={
        isEditMode
          ? `Edit Recipe: ${initialData.recipeCode}`
          : 'Create Master Manufacturing Recipe'
      }
      subtitle={
        isEditMode
          ? 'Update batch specifications, raw materials bill, and process steps'
          : 'Define formulation details and operational instructions for an active product'
      }
      icon={
        <div className="w-9 h-9 rounded-lg bg-pharma-600 flex items-center justify-center text-white shadow-sm">
          <FlaskConical className="w-5 h-5" aria-hidden="true" />
        </div>
      }
      bodyClassName="overflow-y-auto"
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
            <span>{isSubmitting ? 'Saving...' : isEditMode ? 'Update Recipe' : 'Create Recipe'}</span>
          </button>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate className="p-5 sm:p-6 space-y-6 text-sm">
        {serverError && (
          <InlineAlert
            variant="error"
            compact
            message={serverError}
            onDismiss={() => setServerError('')}
            dismissLabel="Dismiss form error"
          />
        )}

        {/* Section 1: Formulation Header & Batch Parameters */}
        <section
          aria-labelledby={`${baseId}-section-params`}
          className="bg-slate-50 p-4 sm:p-5 rounded-xl border border-slate-200 space-y-4"
        >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h4
              id={`${baseId}-section-params`}
              className="text-xs font-bold uppercase tracking-wider text-slate-600"
            >
              1. Basic Recipe Parameters
            </h4>
            <span className="text-[11px] font-medium text-slate-500">
              {isEditMode
                ? `Draft Version v${initialData.version}`
                : 'New Master Recipe Draft'}
            </span>
          </div>

          {!isEditMode && productsError && (
            <InlineAlert
              variant="error"
              compact
              title="Unable to load active products"
              message={productsError}
              actions={
                <button
                  type="button"
                  onClick={fetchActiveProducts}
                  disabled={isLoadingProducts}
                  className="inline-flex items-center space-x-1 px-2.5 py-1 text-xs font-semibold text-rose-900 bg-white hover:bg-rose-100 border border-rose-300 rounded-md"
                >
                  <RefreshCw className="w-3 h-3" aria-hidden="true" />
                  <span>Retry</span>
                </button>
              }
            />
          )}

          {!isEditMode && !productsError && !isLoadingProducts && activeProducts.length === 0 && (
            <InlineAlert
              variant="warning"
              compact
              title="No active products available"
              message="Activate or register a pharmaceutical product before creating a master recipe."
            />
          )}

          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            <div className="sm:col-span-2 lg:col-span-1">
              <label
                htmlFor={isEditMode ? undefined : productSelectId}
                className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
              >
                Product <span className="text-rose-500">*</span>
              </label>
              {isEditMode ? (
                <div className="px-3 py-2 text-sm rounded-lg border border-slate-200 bg-white font-medium text-slate-900">
                  <div>{initialData.productName}</div>
                  <div className="text-xs font-mono text-slate-500">{initialData.productCode}</div>
                </div>
              ) : (
                <div>
                  <select
                    ref={productSelectRef}
                    id={productSelectId}
                    value={formData.productId}
                    onChange={(e) => {
                      setFormData((prev) => ({ ...prev, productId: e.target.value }));
                      if (errors.productId) setErrors((prev) => ({ ...prev, productId: '' }));
                    }}
                    disabled={isSubmitting || isLoadingProducts}
                    aria-invalid={Boolean(errors.productId)}
                    aria-describedby={errors.productId ? productErrorId : undefined}
                    className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 bg-white ${
                      errors.productId
                        ? 'border-rose-300 focus:ring-rose-200'
                        : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                    }`}
                  >
                    <option value="">-- Select Active Product --</option>
                    {activeProducts.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.productName} ({p.productCode} - {p.strength})
                      </option>
                    ))}
                  </select>
                  {errors.productId && (
                    <p id={productErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                      {errors.productId}
                    </p>
                  )}
                </div>
              )}
            </div>

            <div>
              <label
                htmlFor={batchSizeId}
                className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
              >
                Batch Size <span className="text-rose-500">*</span>
              </label>
              <input
                ref={batchSizeInputRef}
                id={batchSizeId}
                type="number"
                step="any"
                min="0.0001"
                value={formData.batchSize}
                onChange={(e) => {
                  setFormData((prev) => ({ ...prev, batchSize: e.target.value }));
                  if (errors.batchSize) setErrors((prev) => ({ ...prev, batchSize: '' }));
                }}
                placeholder="e.g. 1000"
                disabled={isSubmitting}
                aria-invalid={Boolean(errors.batchSize)}
                aria-describedby={errors.batchSize ? batchSizeErrorId : undefined}
                className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 font-mono tabular-nums ${
                  errors.batchSize
                    ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                    : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
                }`}
              />
              {errors.batchSize && (
                <p id={batchSizeErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                  {errors.batchSize}
                </p>
              )}
            </div>

            <div>
              <label
                htmlFor={batchUnitId}
                className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
              >
                Batch Unit <span className="text-rose-500">*</span>
              </label>
              <input
                id={batchUnitId}
                type="text"
                value={formData.batchUnit}
                onChange={(e) => {
                  setFormData((prev) => ({ ...prev, batchUnit: e.target.value }));
                  if (errors.batchUnit) setErrors((prev) => ({ ...prev, batchUnit: '' }));
                }}
                placeholder="e.g. kg, L, tablets"
                disabled={isSubmitting}
                aria-invalid={Boolean(errors.batchUnit)}
                aria-describedby={errors.batchUnit ? batchUnitErrorId : undefined}
                className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 ${
                  errors.batchUnit
                    ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                    : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
                }`}
              />
              {errors.batchUnit && (
                <p id={batchUnitErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                  {errors.batchUnit}
                </p>
              )}
            </div>
          </div>

          <div>
            <label
              htmlFor={descriptionId}
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
            >
              Description / Clinical Batch Notes{' '}
              <span className="text-slate-500 font-normal">(Optional)</span>
            </label>
            <textarea
              id={descriptionId}
              rows={2}
              value={formData.description}
              onChange={(e) => setFormData((prev) => ({ ...prev, description: e.target.value }))}
              placeholder="Manufacturing instructions, line requirements, yield expectations..."
              disabled={isSubmitting}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 bg-white resize-none"
            />
          </div>
        </section>

        {/* Section 2: Bill of Raw Materials */}
        <section aria-labelledby={`${baseId}-section-materials`} className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2 pb-2 border-b border-slate-200">
            <div className="flex items-center space-x-2">
              <Layers className="w-4 h-4 text-pharma-600" aria-hidden="true" />
              <h4
                id={`${baseId}-section-materials`}
                className="text-xs font-bold uppercase tracking-wider text-slate-700 tabular-nums"
              >
                2. Bill of Raw Materials ({formData.materials.length}){' '}
                <span className="text-rose-500">*</span>
              </h4>
            </div>
            <button
              type="button"
              onClick={handleAddMaterial}
              disabled={isSubmitting}
              className="inline-flex items-center space-x-1 px-2.5 py-1.5 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 rounded-lg border border-pharma-200 transition-colors disabled:opacity-50"
            >
              <Plus className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Add Material</span>
            </button>
          </div>

          {errors.materials && (
            <InlineAlert variant="error" compact message={errors.materials} />
          )}

          <div className="space-y-2.5">
            {formData.materials.map((material, idx) => {
              const nameInputId = `${baseId}-mat-${material._key}-name`;
              const nameErrorId = `${baseId}-mat-${material._key}-name-error`;
              const qtyInputId = `${baseId}-mat-${material._key}-qty`;
              const qtyErrorId = `${baseId}-mat-${material._key}-qty-error`;
              const unitInputId = `${baseId}-mat-${material._key}-unit`;
              const unitErrorId = `${baseId}-mat-${material._key}-unit-error`;

              const nameError = errors[`mat_${idx}_name`];
              const qtyError = errors[`mat_${idx}_qty`];
              const unitError = errors[`mat_${idx}_unit`];

              return (
                <div
                  key={material._key}
                  data-row-key={material._key}
                  className="bg-slate-50 p-3 rounded-xl border border-slate-200 space-y-2 sm:space-y-0"
                >
                  <div className="flex items-center justify-between sm:hidden">
                    <span className="text-xs font-mono font-bold text-slate-500 tabular-nums">
                      Material #{idx + 1}
                    </span>
                    <button
                      type="button"
                      onClick={() => handleRemoveMaterial(idx, material._key)}
                      disabled={formData.materials.length <= 1 || isSubmitting}
                      aria-label={`Remove material row ${idx + 1}`}
                      title="Remove material row"
                      className="p-1 text-slate-500 hover:text-rose-600 disabled:opacity-30"
                    >
                      <Trash2 className="w-4 h-4" aria-hidden="true" />
                    </button>
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-12 gap-2.5 items-start">
                    <div className="hidden sm:flex sm:col-span-1 items-center justify-center pt-6">
                      <span className="text-xs font-mono font-bold text-slate-500 tabular-nums">
                        {idx + 1}
                      </span>
                    </div>

                    <div className="sm:col-span-5">
                      <label
                        htmlFor={nameInputId}
                        className="block text-[11px] font-semibold text-slate-600 uppercase tracking-wider mb-1"
                      >
                        Material Name <span className="text-rose-500">*</span>
                      </label>
                      <input
                        id={nameInputId}
                        type="text"
                        value={material.materialName}
                        onChange={(e) =>
                          handleMaterialChange(idx, 'materialName', e.target.value)
                        }
                        placeholder="e.g. Paracetamol API"
                        disabled={isSubmitting}
                        aria-invalid={Boolean(nameError)}
                        aria-describedby={nameError ? nameErrorId : undefined}
                        className={`w-full px-3 py-1.5 text-xs rounded-md border focus:outline-none focus:ring-2 bg-white ${
                          nameError
                            ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                            : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                        }`}
                      />
                      {nameError && (
                        <p id={nameErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                          {nameError}
                        </p>
                      )}
                    </div>

                    <div className="sm:col-span-3">
                      <label
                        htmlFor={qtyInputId}
                        className="block text-[11px] font-semibold text-slate-600 uppercase tracking-wider mb-1"
                      >
                        Quantity <span className="text-rose-500">*</span>
                      </label>
                      <input
                        id={qtyInputId}
                        type="number"
                        step="any"
                        min="0.0001"
                        value={material.quantity}
                        onChange={(e) => handleMaterialChange(idx, 'quantity', e.target.value)}
                        placeholder="e.g. 500"
                        disabled={isSubmitting}
                        aria-invalid={Boolean(qtyError)}
                        aria-describedby={qtyError ? qtyErrorId : undefined}
                        className={`w-full px-3 py-1.5 text-xs rounded-md border focus:outline-none focus:ring-2 font-mono tabular-nums bg-white ${
                          qtyError
                            ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                            : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                        }`}
                      />
                      {qtyError && (
                        <p id={qtyErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                          {qtyError}
                        </p>
                      )}
                    </div>

                    <div className="sm:col-span-2">
                      <label
                        htmlFor={unitInputId}
                        className="block text-[11px] font-semibold text-slate-600 uppercase tracking-wider mb-1"
                      >
                        Unit <span className="text-rose-500">*</span>
                      </label>
                      <input
                        id={unitInputId}
                        type="text"
                        value={material.unit}
                        onChange={(e) => handleMaterialChange(idx, 'unit', e.target.value)}
                        placeholder="kg"
                        disabled={isSubmitting}
                        aria-invalid={Boolean(unitError)}
                        aria-describedby={unitError ? unitErrorId : undefined}
                        className={`w-full px-3 py-1.5 text-xs rounded-md border focus:outline-none focus:ring-2 bg-white ${
                          unitError
                            ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                            : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                        }`}
                      />
                      {unitError && (
                        <p id={unitErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                          {unitError}
                        </p>
                      )}
                    </div>

                    <div className="hidden sm:flex sm:col-span-1 justify-end pt-5">
                      <button
                        type="button"
                        onClick={() => handleRemoveMaterial(idx, material._key)}
                        disabled={formData.materials.length <= 1 || isSubmitting}
                        aria-label={`Remove material row ${idx + 1}`}
                        title="Remove material row"
                        className="p-1.5 text-slate-500 hover:text-rose-600 disabled:opacity-30 disabled:hover:text-slate-500 transition-colors"
                      >
                        <Trash2 className="w-4 h-4" aria-hidden="true" />
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </section>

        {/* Section 3: Manufacturing Process Steps */}
        <section aria-labelledby={`${baseId}-section-steps`} className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2 pb-2 border-b border-slate-200">
            <div className="flex items-center space-x-2">
              <CheckCircle2 className="w-4 h-4 text-pharma-600" aria-hidden="true" />
              <h4
                id={`${baseId}-section-steps`}
                className="text-xs font-bold uppercase tracking-wider text-slate-700 tabular-nums"
              >
                3. Manufacturing Process Steps ({formData.steps.length}){' '}
                <span className="text-rose-500">*</span>
              </h4>
            </div>
            <button
              type="button"
              onClick={handleAddStep}
              disabled={isSubmitting}
              className="inline-flex items-center space-x-1 px-2.5 py-1.5 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 rounded-lg border border-pharma-200 transition-colors disabled:opacity-50"
            >
              <Plus className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Add Step</span>
            </button>
          </div>

          {errors.steps && <InlineAlert variant="error" compact message={errors.steps} />}

          <div className="space-y-3">
            {formData.steps.map((step, idx) => {
              const numInputId = `${baseId}-step-${step._key}-num`;
              const numErrorId = `${baseId}-step-${step._key}-num-error`;
              const opInputId = `${baseId}-step-${step._key}-op`;
              const opErrorId = `${baseId}-step-${step._key}-op-error`;
              const instInputId = `${baseId}-step-${step._key}-inst`;
              const instErrorId = `${baseId}-step-${step._key}-inst-error`;

              const numError = errors[`step_${idx}_num`];
              const opError = errors[`step_${idx}_op`];
              const instError = errors[`step_${idx}_inst`];

              return (
                <div
                  key={step._key}
                  data-row-key={step._key}
                  className="bg-slate-50 p-3.5 rounded-xl border border-slate-200 space-y-3"
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center space-x-2">
                      <span className="w-5 h-5 rounded-full bg-pharma-600 text-white text-xs font-bold flex items-center justify-center tabular-nums">
                        {idx + 1}
                      </span>
                      <span className="text-xs font-bold text-slate-700 tabular-nums">
                        Step {idx + 1}
                      </span>
                    </div>

                    <button
                      type="button"
                      onClick={() => handleRemoveStep(idx, step._key)}
                      disabled={formData.steps.length <= 1 || isSubmitting}
                      aria-label={`Remove step ${idx + 1}`}
                      title="Remove step"
                      className="p-1 text-slate-500 hover:text-rose-600 disabled:opacity-30 transition-colors"
                    >
                      <Trash2 className="w-4 h-4" aria-hidden="true" />
                    </button>
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-4 gap-3">
                    <div className="sm:col-span-1">
                      <label
                        htmlFor={numInputId}
                        className="block text-[11px] font-semibold text-slate-600 uppercase tracking-wider mb-1"
                      >
                        Step Number <span className="text-rose-500">*</span>
                      </label>
                      <input
                        ref={(el) => {
                          if (el) {
                            stepNumberRefs.current[step._key] = el;
                          } else {
                            delete stepNumberRefs.current[step._key];
                          }
                        }}
                        id={numInputId}
                        type="number"
                        min="1"
                        step="1"
                        value={step.stepNumber}
                        onChange={(e) =>
                          handleStepChange(
                            idx,
                            step._key,
                            'stepNumber',
                            e.target.value,
                            Boolean(e.target.validity?.badInput)
                          )
                        }
                        disabled={isSubmitting}
                        aria-invalid={Boolean(numError)}
                        aria-describedby={numError ? numErrorId : undefined}
                        className={`w-full px-2.5 py-1.5 text-xs rounded-md border font-mono tabular-nums bg-white focus:outline-none focus:ring-2 ${
                          numError
                            ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                            : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                        }`}
                      />
                      {numError && (
                        <p id={numErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                          {numError}
                        </p>
                      )}
                    </div>

                    <div className="sm:col-span-3">
                      <label
                        htmlFor={opInputId}
                        className="block text-[11px] font-semibold text-slate-600 uppercase tracking-wider mb-1"
                      >
                        Operation Name <span className="text-rose-500">*</span>
                      </label>
                      <input
                        id={opInputId}
                        type="text"
                        value={step.operationName}
                        onChange={(e) =>
                          handleStepChange(idx, step._key, 'operationName', e.target.value)
                        }
                        placeholder="e.g. Dispensing, Wet Granulation, Fluid Bed Drying, Compression"
                        disabled={isSubmitting}
                        aria-invalid={Boolean(opError)}
                        aria-describedby={opError ? opErrorId : undefined}
                        className={`w-full px-2.5 py-1.5 text-xs rounded-md border bg-white focus:outline-none focus:ring-2 ${
                          opError
                            ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                            : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                        }`}
                      />
                      {opError && (
                        <p id={opErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                          {opError}
                        </p>
                      )}
                    </div>
                  </div>

                  <div>
                    <label
                      htmlFor={instInputId}
                      className="block text-[11px] font-semibold text-slate-600 uppercase tracking-wider mb-1"
                    >
                      Process Instructions &amp; Critical Parameters{' '}
                      <span className="text-rose-500">*</span>
                    </label>
                    <textarea
                      id={instInputId}
                      rows={2}
                      value={step.instructions}
                      onChange={(e) =>
                        handleStepChange(idx, step._key, 'instructions', e.target.value)
                      }
                      placeholder="Detailed manufacturing SOP, temperature limits, rotation speed, blending time..."
                      disabled={isSubmitting}
                      aria-invalid={Boolean(instError)}
                      aria-describedby={instError ? instErrorId : undefined}
                      className={`w-full px-2.5 py-1.5 text-xs rounded-md border bg-white resize-none focus:outline-none focus:ring-2 ${
                        instError
                          ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                          : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                      }`}
                    />
                    {instError && (
                      <p id={instErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                        {instError}
                      </p>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      </form>
    </ModalDialog>
  );
}
