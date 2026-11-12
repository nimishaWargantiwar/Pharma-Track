import React, { useState, useEffect, useMemo, useId, useRef } from 'react';
import {
  ClipboardList,
  CheckCircle2,
  AlertCircle,
  FlaskConical,
  RefreshCw,
} from 'lucide-react';
import { InlineAlert, ModalDialog } from './ui/index.js';
import { getAllProducts } from '../services/productApi';
import { getRecipesByProductId } from '../services/recipeApi';
import { createOrder } from '../services/orderApi';
import {
  getTodayIsoDateInAppTimeZone,
  parseCalendarDateKey,
} from '../utils/dateTimeUtils.js';

const START_DATE_PAST_ERROR_MESSAGE =
  'Planned start date cannot be earlier than today.';

const DATE_RANGE_ERROR_MESSAGE =
  'Planned end date cannot be earlier than planned start date.';

const parseCalendarDate = parseCalendarDateKey;

export default function OrderModal({ isOpen, onClose, onSuccess }) {
  const productSelectRef = useRef(null);
  const productIdInputId = useId();
  const productErrorId = useId();
  const recipeIdInputId = useId();
  const recipeErrorId = useId();
  const qtyInputId = useId();
  const qtyErrorId = useId();
  const startDateInputId = useId();
  const startDateErrorId = useId();
  const endDateInputId = useId();
  const endDateErrorId = useId();
  const notesInputId = useId();

  // Products and recipes state
  const [activeProducts, setActiveProducts] = useState([]);
  const [isLoadingProducts, setIsLoadingProducts] = useState(false);
  const [productError, setProductError] = useState('');

  const [approvedRecipes, setApprovedRecipes] = useState([]);
  const [isLoadingRecipes, setIsLoadingRecipes] = useState(false);
  const [recipeError, setRecipeError] = useState('');

  // Form State
  const [formData, setFormData] = useState({
    productId: '',
    recipeId: '',
    plannedQuantity: '',
    plannedStartDate: '',
    plannedEndDate: '',
    notes: '',
  });

  const [errors, setErrors] = useState({});
  const [serverError, setServerError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Fetch active products on modal open
  useEffect(() => {
    if (!isOpen) return;

    setFormData({
      productId: '',
      recipeId: '',
      plannedQuantity: '',
      plannedStartDate: '',
      plannedEndDate: '',
      notes: '',
    });
    setErrors({});
    setServerError('');
    setProductError('');
    setRecipeError('');
    setApprovedRecipes([]);

    const fetchProducts = async () => {
      setIsLoadingProducts(true);
      try {
        const products = await getAllProducts();
        const active = (Array.isArray(products) ? products : []).filter(
          (p) => p.status === 'ACTIVE'
        );
        setActiveProducts(active);
      } catch (err) {
        setProductError(err.message || 'Failed to load active products from server');
        setActiveProducts([]);
      } finally {
        setIsLoadingProducts(false);
      }
    };

    fetchProducts();
  }, [isOpen]);

  // Handle product selection & fetch approved recipes
  const handleProductChange = async (e) => {
    const selectedProductId = e.target.value;

    setFormData((prev) => ({
      ...prev,
      productId: selectedProductId,
      recipeId: '',
    }));

    if (errors.productId) {
      setErrors((prev) => ({ ...prev, productId: '' }));
    }
    if (errors.recipeId) {
      setErrors((prev) => ({ ...prev, recipeId: '' }));
    }
    setServerError('');
    setRecipeError('');

    if (!selectedProductId) {
      setApprovedRecipes([]);
      return;
    }

    setIsLoadingRecipes(true);
    try {
      const recipes = await getRecipesByProductId(selectedProductId);
      const approved = (Array.isArray(recipes) ? recipes : []).filter(
        (r) => r.status === 'APPROVED'
      );
      setApprovedRecipes(approved);

      if (approved.length === 1) {
        setFormData((prev) => ({
          ...prev,
          productId: selectedProductId,
          recipeId: String(approved[0].id),
        }));
      }
    } catch (err) {
      setRecipeError(err.message || 'Failed to load recipes for selected product');
      setApprovedRecipes([]);
    } finally {
      setIsLoadingRecipes(false);
    }
  };

  // Selected recipe lookup
  const selectedRecipe = useMemo(() => {
    if (!formData.recipeId) return null;
    return approvedRecipes.find((r) => String(r.id) === String(formData.recipeId)) || null;
  }, [approvedRecipes, formData.recipeId]);

  // Live Batch Calculation & Multiples Validation
  const batchCalculation = useMemo(() => {
    if (!selectedRecipe || !formData.plannedQuantity) {
      return null;
    }

    const qty = parseFloat(formData.plannedQuantity);
    const batchSize = parseFloat(selectedRecipe.batchSize);

    if (isNaN(qty) || isNaN(batchSize) || qty <= 0 || batchSize <= 0) {
      return {
        isValid: false,
        message: 'Planned quantity must be a positive number',
      };
    }

    const div = qty / batchSize;
    const rounded = Math.round(div);
    const isMultiple = Math.abs(div - rounded) < 1e-7 && rounded > 0;

    if (isMultiple) {
      return {
        isValid: true,
        batchCount: rounded,
        batchSize: selectedRecipe.batchSize,
        batchUnit: selectedRecipe.batchUnit,
        totalQuantity: qty,
      };
    }

    const nextMultiple = +(Math.ceil(div) * batchSize).toFixed(4);
    const prevMultiple = +(Math.floor(div) * batchSize).toFixed(4);

    return {
      isValid: false,
      batchCount: null,
      batchSize: selectedRecipe.batchSize,
      batchUnit: selectedRecipe.batchUnit,
      suggestedNext: nextMultiple,
      suggestedPrev: prevMultiple > 0 ? prevMultiple : null,
      message: `Planned quantity (${qty} ${selectedRecipe.batchUnit}) is not an exact multiple of recipe batch size (${selectedRecipe.batchSize} ${selectedRecipe.batchUnit})`,
    };
  }, [selectedRecipe, formData.plannedQuantity]);

  const todayIsoDate = getTodayIsoDateInAppTimeZone();

  const liveStartDateError = useMemo(() => {
    const startKey = parseCalendarDate(formData.plannedStartDate);
    const todayKey = parseCalendarDate(todayIsoDate);
    if (startKey === null || todayKey === null) {
      return '';
    }
    if (startKey < todayKey) {
      return START_DATE_PAST_ERROR_MESSAGE;
    }
    return '';
  }, [formData.plannedStartDate, todayIsoDate]);

  const isStartDateInvalid = Boolean(liveStartDateError);

  const dateRangeError = useMemo(() => {
    const startKey = parseCalendarDate(formData.plannedStartDate);
    const endKey = parseCalendarDate(formData.plannedEndDate);
    if (startKey === null || endKey === null) {
      return '';
    }
    if (endKey < startKey) {
      return DATE_RANGE_ERROR_MESSAGE;
    }
    return '';
  }, [formData.plannedStartDate, formData.plannedEndDate]);

  const isDateRangeInvalid = Boolean(dateRangeError);

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));

    if (errors[name]) {
      setErrors((prev) => ({ ...prev, [name]: '' }));
    }
    if (name === 'plannedStartDate' || name === 'plannedEndDate') {
      if (
        errors.plannedStartDate ||
        errors.plannedStartDateValid ||
        errors.plannedEndDate ||
        errors.plannedEndDateValid
      ) {
        setErrors((prev) => ({
          ...prev,
          plannedStartDate: '',
          plannedStartDateValid: '',
          plannedEndDate: '',
          plannedEndDateValid: '',
        }));
      }
    }
  };

  const validate = () => {
    const newErrors = {};

    if (!formData.productId) {
      newErrors.productId = 'Product is required';
    }

    if (!formData.recipeId) {
      newErrors.recipeId = 'Recipe is required';
    }

    if (!formData.plannedQuantity) {
      newErrors.plannedQuantity = 'Planned quantity is required';
    } else {
      const qty = parseFloat(formData.plannedQuantity);
      if (isNaN(qty) || qty <= 0) {
        newErrors.plannedQuantity = 'Planned quantity must be greater than zero';
      } else if (selectedRecipe) {
        const batchSize = parseFloat(selectedRecipe.batchSize);
        if (batchSize > 0) {
          const div = qty / batchSize;
          const rounded = Math.round(div);
          const isMultiple = Math.abs(div - rounded) < 1e-7 && rounded > 0;
          if (!isMultiple) {
            newErrors.plannedQuantity = `Planned quantity must be an exact positive multiple of recipe batch size (${selectedRecipe.batchSize} ${selectedRecipe.batchUnit})`;
          }
        }
      }
    }

    if (formData.plannedStartDate) {
      const startKey = parseCalendarDate(formData.plannedStartDate);
      const todayKey = parseCalendarDate(getTodayIsoDateInAppTimeZone());
      if (startKey !== null && todayKey !== null && startKey < todayKey) {
        newErrors.plannedStartDate = START_DATE_PAST_ERROR_MESSAGE;
      }
    }

    if (formData.plannedStartDate && formData.plannedEndDate) {
      const startKey = parseCalendarDate(formData.plannedStartDate);
      const endKey = parseCalendarDate(formData.plannedEndDate);
      if (startKey !== null && endKey !== null && endKey < startKey) {
        newErrors.plannedEndDate = DATE_RANGE_ERROR_MESSAGE;
      }
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (e) => {
    e?.preventDefault?.();
    if (isSubmitting) return;
    setServerError('');

    if (!validate()) {
      return;
    }

    const orderPayload = {
      productId: Number(formData.productId),
      recipeId: Number(formData.recipeId),
      plannedQuantity: parseFloat(formData.plannedQuantity),
      quantityUnit: selectedRecipe ? selectedRecipe.batchUnit : '',
      plannedStartDate: formData.plannedStartDate || null,
      plannedEndDate: formData.plannedEndDate || null,
      notes: formData.notes ? formData.notes.trim() : null,
    };

    setIsSubmitting(true);
    try {
      const createdOrder = await createOrder(orderPayload);
      if (onSuccess) {
        onSuccess(createdOrder);
      }
      onClose();
    } catch (err) {
      if (err.validationErrors) {
        setErrors(err.validationErrors);
      }
      setServerError(err.message || 'Failed to create manufacturing order');
    } finally {
      setIsSubmitting(false);
    }
  };

  const startDateError =
    liveStartDateError || errors.plannedStartDate || errors.plannedStartDateValid;
  const endDateError =
    dateRangeError || errors.plannedEndDate || errors.plannedEndDateValid;

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={onClose}
      preventClose={isSubmitting}
      initialFocusRef={productSelectRef}
      size="2xl"
      maxHeightClass="max-h-[90vh]"
      bodyClassName="overflow-y-auto"
      title="Create Manufacturing Order"
      subtitle="Schedule production and automatically generate planned batches"
      closeButtonLabel="Close create manufacturing order dialog"
      icon={
        <div className="w-8 h-8 rounded-lg bg-pharma-600 flex items-center justify-center text-white">
          <ClipboardList className="w-4 h-4" aria-hidden="true" />
        </div>
      }
      footer={
        <>
          <button
            type="button"
            onClick={onClose}
            disabled={isSubmitting}
            className="px-4 py-2 text-sm font-medium text-slate-700 hover:text-slate-900 bg-white hover:bg-slate-50 border border-slate-300 rounded-lg shadow-sm transition-colors disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={
              isSubmitting ||
              !formData.productId ||
              !formData.recipeId ||
              (batchCalculation && !batchCalculation.isValid) ||
              isStartDateInvalid ||
              isDateRangeInvalid
            }
            className="inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-semibold text-white bg-pharma-600 hover:bg-pharma-700 disabled:opacity-50 disabled:cursor-not-allowed rounded-lg shadow-sm transition-colors"
          >
            {isSubmitting ? (
              <>
                <RefreshCw className="w-4 h-4 animate-spin" aria-hidden="true" />
                <span>Creating Order...</span>
              </>
            ) : (
              <>
                <CheckCircle2 className="w-4 h-4" aria-hidden="true" />
                <span>Create Manufacturing Order</span>
              </>
            )}
          </button>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate className="p-4 sm:p-6 space-y-5">
        {serverError && (
          <InlineAlert
            variant="error"
            compact
            title="Unable to create order"
            message={serverError}
            onDismiss={() => setServerError('')}
            dismissLabel="Dismiss form error"
          />
        )}

        {/* 1. Product Selection */}
        <div>
          <label
            htmlFor={productIdInputId}
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
          >
            Target Product <span className="text-rose-500">*</span>
          </label>
          <select
            ref={productSelectRef}
            id={productIdInputId}
            name="productId"
            value={formData.productId}
            onChange={handleProductChange}
            disabled={isSubmitting || isLoadingProducts}
            aria-invalid={Boolean(errors.productId || productError)}
            aria-describedby={errors.productId || productError ? productErrorId : undefined}
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors bg-white ${
              errors.productId || productError
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
            }`}
          >
            <option value="">
              {isLoadingProducts
                ? 'Loading active products...'
                : activeProducts.length === 0
                  ? 'No active products available'
                  : '-- Select Active Product --'}
            </option>
            {activeProducts.map((p) => (
              <option key={p.id} value={p.id}>
                {p.productName} ({p.productCode}) — {p.dosageForm} {p.strength}
              </option>
            ))}
          </select>
          {(errors.productId || productError) && (
            <p id={productErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {errors.productId || productError}
            </p>
          )}
        </div>

        {/* 2. Recipe Selection */}
        <div>
          <label
            htmlFor={recipeIdInputId}
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
          >
            Master Recipe (Approved Only) <span className="text-rose-500">*</span>
          </label>
          <select
            id={recipeIdInputId}
            name="recipeId"
            value={formData.recipeId}
            onChange={handleChange}
            disabled={isSubmitting || !formData.productId || isLoadingRecipes}
            aria-invalid={Boolean(errors.recipeId || recipeError)}
            aria-describedby={errors.recipeId || recipeError ? recipeErrorId : undefined}
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors bg-white ${
              errors.recipeId || recipeError
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
            }`}
          >
            <option value="">
              {!formData.productId
                ? '-- Select a product first --'
                : isLoadingRecipes
                  ? 'Loading recipes...'
                  : approvedRecipes.length === 0
                    ? '-- No approved recipes available for this product --'
                    : '-- Select Master Recipe --'}
            </option>
            {approvedRecipes.map((r) => (
              <option key={r.id} value={r.id}>
                {r.recipeCode} (v{r.version}) — Batch: {r.batchSize} {r.batchUnit}
              </option>
            ))}
          </select>

          {(errors.recipeId || recipeError) && (
            <p id={recipeErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {errors.recipeId || recipeError}
            </p>
          )}

          {formData.productId && !isLoadingRecipes && approvedRecipes.length === 0 && !recipeError && (
            <InlineAlert
              variant="warning"
              compact
              className="mt-2"
              message="No approved recipes found for this product. Recipes must be in APPROVED status before they can be used to issue manufacturing orders."
            />
          )}

          {selectedRecipe && (
            <div className="mt-2.5 p-3 bg-slate-50 border border-slate-200 rounded-lg text-xs text-slate-700 flex items-center justify-between gap-3">
              <div className="flex items-center space-x-2 min-w-0">
                <FlaskConical className="w-4 h-4 text-pharma-600 flex-shrink-0" aria-hidden="true" />
                <div className="min-w-0">
                  <span className="font-semibold font-mono text-slate-900">
                    {selectedRecipe.recipeCode}
                  </span>
                  <span className="ml-1.5 px-1.5 py-0.5 rounded text-[11px] font-bold bg-indigo-50 text-indigo-700 border border-indigo-200 tabular-nums">
                    v{selectedRecipe.version}
                  </span>
                  {selectedRecipe.description && (
                    <p className="text-[11px] text-slate-500 mt-0.5 truncate">
                      {selectedRecipe.description}
                    </p>
                  )}
                </div>
              </div>
              <div className="text-right shrink-0">
                <span className="text-[11px] text-slate-500 block uppercase tracking-wider font-semibold">
                  Standard Batch Size
                </span>
                <span className="font-bold text-slate-900 tabular-nums">
                  {selectedRecipe.batchSize} {selectedRecipe.batchUnit}
                </span>
              </div>
            </div>
          )}
        </div>

        {/* 3. Planned Quantity & Batch Unit */}
        <div>
          <label
            htmlFor={qtyInputId}
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
          >
            Planned Order Quantity <span className="text-rose-500">*</span>
          </label>
          <div className="flex rounded-lg shadow-sm">
            <input
              id={qtyInputId}
              type="number"
              step="any"
              min="0"
              name="plannedQuantity"
              value={formData.plannedQuantity}
              onChange={handleChange}
              placeholder={
                selectedRecipe ? `e.g. ${selectedRecipe.batchSize * 2}` : 'Select a recipe first'
              }
              disabled={isSubmitting || !selectedRecipe}
              aria-invalid={Boolean(errors.plannedQuantity)}
              aria-describedby={errors.plannedQuantity ? qtyErrorId : undefined}
              className={`flex-1 px-3 py-2 text-sm rounded-l-lg border border-r-0 focus:outline-none focus:ring-2 transition-colors font-mono tabular-nums ${
                errors.plannedQuantity
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
              } ${!selectedRecipe ? 'bg-slate-100 cursor-not-allowed' : ''}`}
            />
            <span className="inline-flex items-center px-4 bg-slate-100 border border-l-0 border-slate-300 rounded-r-lg text-xs font-semibold text-slate-700 tracking-wider">
              {selectedRecipe ? selectedRecipe.batchUnit : '—'}
            </span>
          </div>
          {errors.plannedQuantity && (
            <p id={qtyErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {errors.plannedQuantity}
            </p>
          )}

          {batchCalculation && (
            <div className="mt-2.5">
              {batchCalculation.isValid ? (
                <div className="p-3 bg-emerald-50 border border-emerald-200 rounded-lg text-xs text-emerald-900 flex items-start space-x-2">
                  <CheckCircle2 className="w-4 h-4 text-emerald-600 flex-shrink-0 mt-0.5" aria-hidden="true" />
                  <div className="flex-1">
                    <p className="font-semibold text-emerald-800 tabular-nums">
                      Exact Multiple Confirmed: {batchCalculation.batchCount} Planned Batches
                    </p>
                    <p className="text-[11px] text-emerald-700 mt-0.5 tabular-nums">
                      Order total of {batchCalculation.totalQuantity} {batchCalculation.batchUnit}{' '}
                      will automatically generate <strong>{batchCalculation.batchCount}</strong>{' '}
                      batches at {batchCalculation.batchSize} {batchCalculation.batchUnit} each.
                    </p>
                  </div>
                </div>
              ) : (
                <div className="p-3 bg-amber-50 border border-amber-200 rounded-lg text-xs text-amber-900 flex items-start space-x-2">
                  <AlertCircle className="w-4 h-4 text-amber-600 flex-shrink-0 mt-0.5" aria-hidden="true" />
                  <div className="flex-1">
                    <p className="font-semibold">{batchCalculation.message}</p>
                    {batchCalculation.suggestedNext && (
                      <p className="text-[11px] text-amber-800 mt-1 tabular-nums">
                        Suggested valid quantity:{' '}
                        <button
                          type="button"
                          onClick={() => {
                            setFormData((prev) => ({
                              ...prev,
                              plannedQuantity: String(batchCalculation.suggestedNext),
                            }));
                            if (errors.plannedQuantity) {
                              setErrors((prev) => ({ ...prev, plannedQuantity: '' }));
                            }
                          }}
                          className="font-bold underline text-amber-900 hover:text-amber-950"
                        >
                          {batchCalculation.suggestedNext} {selectedRecipe.batchUnit}
                        </button>
                        {batchCalculation.suggestedPrev && (
                          <>
                            {' '}
                            or{' '}
                            <button
                              type="button"
                              onClick={() => {
                                setFormData((prev) => ({
                                  ...prev,
                                  plannedQuantity: String(batchCalculation.suggestedPrev),
                                }));
                                if (errors.plannedQuantity) {
                                  setErrors((prev) => ({ ...prev, plannedQuantity: '' }));
                                }
                              }}
                              className="font-bold underline text-amber-900 hover:text-amber-950"
                            >
                              {batchCalculation.suggestedPrev} {selectedRecipe.batchUnit}
                            </button>
                          </>
                        )}
                      </p>
                    )}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        {/* 4. Dates Grid: Planned Start Date & Planned End Date */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label
              htmlFor={startDateInputId}
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
            >
              Planned Start Date
            </label>
            <input
              id={startDateInputId}
              type="date"
              name="plannedStartDate"
              min={todayIsoDate}
              value={formData.plannedStartDate}
              onChange={handleChange}
              disabled={isSubmitting}
              aria-invalid={Boolean(startDateError)}
              aria-describedby={startDateError ? startDateErrorId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
                startDateError
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
              }`}
            />
            {startDateError && (
              <p id={startDateErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {startDateError}
              </p>
            )}
          </div>

          <div>
            <label
              htmlFor={endDateInputId}
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
            >
              Planned End Date
            </label>
            <input
              id={endDateInputId}
              type="date"
              name="plannedEndDate"
              value={formData.plannedEndDate}
              onChange={handleChange}
              disabled={isSubmitting}
              aria-invalid={Boolean(endDateError)}
              aria-describedby={endDateError ? endDateErrorId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
                endDateError
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
              }`}
            />
            {endDateError && (
              <p id={endDateErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {endDateError}
              </p>
            )}
          </div>
        </div>

        {/* 5. Production Notes */}
        <div>
          <label
            htmlFor={notesInputId}
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
          >
            Production Notes <span className="text-slate-500 font-normal lowercase">(optional)</span>
          </label>
          <textarea
            id={notesInputId}
            name="notes"
            rows={3}
            value={formData.notes}
            onChange={handleChange}
            placeholder="Campaign notes, special handling requirements, or packaging directives..."
            disabled={isSubmitting}
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 bg-white"
          />
        </div>
      </form>
    </ModalDialog>
  );
}
