import React, { useEffect, useId, useMemo, useState } from 'react';
import { ArrowRightLeft, ClipboardList, RefreshCw } from 'lucide-react';
import { InlineAlert, ModalDialog, StatusBadge } from './ui/index.js';
import { getAllOrders } from '../services/orderApi.js';
import { getOrderMaterialRequirements } from '../services/inventoryApi.js';
import {
  formatQuantityDisplay,
  validateMaterialIssueForm,
} from '../utils/inventoryUtils.js';
import { formatCalendarDate } from '../utils/orderUtils.js';

export default function MaterialIssueModal({
  isOpen,
  onClose,
  materials = [],
  lots = [],
  initialOrderId = null,
  initialMaterialId = null,
  initialLotId = null,
  onSave,
  isSubmitting = false,
}) {
  const orderInputId = useId();
  const batchInputId = useId();
  const materialInputId = useId();
  const lotInputId = useId();
  const quantityInputId = useId();
  const unitInputId = useId();
  const refInputId = useId();
  const reasonInputId = useId();

  const [orders, setOrders] = useState([]);
  const [isLoadingOrders, setIsLoadingOrders] = useState(false);
  const [ordersError, setOrdersError] = useState(null);

  const [orderRequirements, setOrderRequirements] = useState(null);
  const [isLoadingRequirements, setIsLoadingRequirements] = useState(false);

  const [form, setForm] = useState({
    manufacturingOrderId: '',
    batchId: '',
    rawMaterialId: '',
    materialLotId: '',
    quantity: '',
    unit: '',
    referenceNumber: '',
    reason: '',
  });
  const [fieldErrors, setFieldErrors] = useState({});
  const [serverError, setServerError] = useState(null);

  const activeMaterials = useMemo(
    () => (Array.isArray(materials) ? materials : []).filter((m) => m.active),
    [materials]
  );

  const eligibleOrders = useMemo(
    () =>
      (Array.isArray(orders) ? orders : []).filter(
        (o) => o.status === 'RELEASED' || o.status === 'IN_PROGRESS'
      ),
    [orders]
  );

  const selectedOrder = useMemo(
    () => eligibleOrders.find((o) => String(o.id) === String(form.manufacturingOrderId)) || null,
    [eligibleOrders, form.manufacturingOrderId]
  );

  const eligibleBatches = useMemo(() => {
    const orderBatches = Array.isArray(selectedOrder?.batches) ? selectedOrder.batches : [];
    return orderBatches.filter((b) => b.status === 'PLANNED' || b.status === 'IN_PROGRESS');
  }, [selectedOrder]);

  const usableLotsForMaterial = useMemo(() => {
    if (!form.rawMaterialId) return [];
    const targetMatId = Number(form.rawMaterialId);
    return (Array.isArray(lots) ? lots : [])
      .filter(
        (lot) =>
          Number(lot.rawMaterialId) === targetMatId &&
          lot.effectiveStatus === 'ACTIVE' &&
          !lot.expired &&
          Number(lot.availableQuantity || 0) > 0
      )
      .sort((a, b) => String(a.expiryDate || '').localeCompare(String(b.expiryDate || '')));
  }, [lots, form.rawMaterialId]);

  const selectedLot = useMemo(
    () =>
      (Array.isArray(lots) ? lots : []).find(
        (l) => String(l.id) === String(form.materialLotId)
      ) || null,
    [lots, form.materialLotId]
  );

  const matchedRequirement = useMemo(() => {
    const reqs = Array.isArray(orderRequirements?.requirements)
      ? orderRequirements.requirements
      : [];
    if (!form.rawMaterialId) return null;
    return reqs.find((r) => String(r.rawMaterialId) === String(form.rawMaterialId)) || null;
  }, [orderRequirements, form.rawMaterialId]);

  // Load Manufacturing Orders when modal opens
  useEffect(() => {
    if (!isOpen) return;
    let active = true;
    setFieldErrors({});
    setServerError(null);
    setOrdersError(null);
    setOrderRequirements(null);

    const initialLotObj =
      initialLotId != null
        ? (Array.isArray(lots) ? lots : []).find((l) => Number(l.id) === Number(initialLotId))
        : null;
    const derivedMatId =
      initialMaterialId != null
        ? String(initialMaterialId)
        : initialLotObj?.rawMaterialId != null
          ? String(initialLotObj.rawMaterialId)
          : '';
    const derivedMatObj = activeMaterials.find((m) => String(m.id) === derivedMatId);

    setForm({
      manufacturingOrderId: initialOrderId != null ? String(initialOrderId) : '',
      batchId: '',
      rawMaterialId: derivedMatId,
      materialLotId: initialLotId != null ? String(initialLotId) : '',
      quantity: '',
      unit: initialLotObj?.unit || derivedMatObj?.unit || '',
      referenceNumber: '',
      reason: '',
    });

    setIsLoadingOrders(true);
    getAllOrders()
      .then((data) => {
        if (!active) return;
        const list = Array.isArray(data) ? data : [];
        setOrders(list);
        const eligible = list.filter(
          (o) => o.status === 'RELEASED' || o.status === 'IN_PROGRESS'
        );
        if (initialOrderId == null && eligible.length > 0) {
          setForm((prev) => ({
            ...prev,
            manufacturingOrderId: prev.manufacturingOrderId || String(eligible[0].id),
          }));
        }
      })
      .catch((err) => {
        if (!active) return;
        setOrdersError(err.message || 'Failed to load manufacturing orders');
      })
      .finally(() => {
        if (active) setIsLoadingOrders(false);
      });

    return () => {
      active = false;
    };
  }, [isOpen, initialOrderId, initialMaterialId, initialLotId]);

  // Load BOM material requirements when order changes
  useEffect(() => {
    if (!isOpen || !form.manufacturingOrderId) {
      setOrderRequirements(null);
      return undefined;
    }
    let active = true;
    setIsLoadingRequirements(true);
    getOrderMaterialRequirements(form.manufacturingOrderId)
      .then((data) => {
        if (!active) return;
        if (data && !Array.isArray(data) && Array.isArray(data.requirements)) {
          setOrderRequirements(data);
        } else {
          setOrderRequirements(null);
        }
      })
      .catch(() => {
        if (active) setOrderRequirements(null);
      })
      .finally(() => {
        if (active) setIsLoadingRequirements(false);
      });

    return () => {
      active = false;
    };
  }, [isOpen, form.manufacturingOrderId]);

  const handleOrderChange = (orderId) => {
    setForm((prev) => ({
      ...prev,
      manufacturingOrderId: orderId,
      batchId: '',
    }));
    setFieldErrors((prev) => {
      const next = { ...prev };
      delete next.manufacturingOrderId;
      return next;
    });
    if (serverError) setServerError(null);
  };

  const handleMaterialChange = (rawMaterialId) => {
    const matchedMat = activeMaterials.find((m) => String(m.id) === String(rawMaterialId));
    const matchingLots = (Array.isArray(lots) ? lots : [])
      .filter(
        (lot) =>
          String(lot.rawMaterialId) === String(rawMaterialId) &&
          lot.effectiveStatus === 'ACTIVE' &&
          !lot.expired &&
          Number(lot.availableQuantity || 0) > 0
      )
      .sort((a, b) => String(a.expiryDate || '').localeCompare(String(b.expiryDate || '')));

    setForm((prev) => ({
      ...prev,
      rawMaterialId,
      materialLotId: matchingLots[0] ? String(matchingLots[0].id) : '',
      unit: matchedMat?.unit || '',
    }));
    setFieldErrors((prev) => {
      const next = { ...prev };
      delete next.rawMaterialId;
      delete next.materialLotId;
      delete next.unit;
      return next;
    });
    if (serverError) setServerError(null);
  };

  const handleSelectRequirement = (reqItem) => {
    if (!reqItem?.registeredInInventory || !reqItem.rawMaterialId) return;
    const matId = String(reqItem.rawMaterialId);
    const matchingLots = (Array.isArray(lots) ? lots : [])
      .filter(
        (lot) =>
          String(lot.rawMaterialId) === matId &&
          lot.effectiveStatus === 'ACTIVE' &&
          !lot.expired &&
          Number(lot.availableQuantity || 0) > 0
      )
      .sort((a, b) => String(a.expiryDate || '').localeCompare(String(b.expiryDate || '')));
    const firstLot = matchingLots[0] || null;
    const suggestedQty =
      reqItem.remainingRequiredQuantity > 0 && firstLot
        ? String(
            Math.min(
              Number(reqItem.remainingRequiredQuantity),
              Number(firstLot.availableQuantity)
            )
          )
        : '';

    setForm((prev) => ({
      ...prev,
      rawMaterialId: matId,
      materialLotId: firstLot ? String(firstLot.id) : '',
      unit: reqItem.inventoryUnit || reqItem.recipeUnit || prev.unit,
      quantity: suggestedQty || prev.quantity,
    }));
    setFieldErrors({});
    if (serverError) setServerError(null);
  };

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

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (isSubmitting) return;

    const validation = validateMaterialIssueForm(form, selectedLot, matchedRequirement);
    if (!validation.isValid) {
      setFieldErrors(validation.errors);
      return;
    }

    const payload = {
      manufacturingOrderId: Number(form.manufacturingOrderId),
      batchId: form.batchId ? Number(form.batchId) : null,
      rawMaterialId: Number(form.rawMaterialId),
      materialLotId: Number(form.materialLotId),
      quantity: Number(form.quantity),
      unit: form.unit.trim(),
      referenceNumber: form.referenceNumber.trim() || null,
      reason: form.reason.trim() || null,
    };

    try {
      await onSave(payload);
    } catch (err) {
      setServerError(err.message || 'Failed to issue material');
    }
  };

  const requirementList = Array.isArray(orderRequirements?.requirements)
    ? orderRequirements.requirements
    : [];

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={onClose}
      preventClose={isSubmitting}
      size="2xl"
      zIndexClass="z-50"
      title="Issue Material to Manufacturing Order"
      subtitle="Dispense verified active lot stock against a RELEASED or IN_PROGRESS order and record an immutable stock movement"
      icon={
        <div className="w-9 h-9 rounded-lg bg-indigo-100 text-indigo-700 flex items-center justify-center">
          <ArrowRightLeft className="w-5 h-5" aria-hidden="true" />
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
            form="material-issue-form"
            disabled={isSubmitting || isLoadingOrders || eligibleOrders.length === 0}
            className="px-4 py-2 text-sm font-semibold text-white bg-indigo-600 hover:bg-indigo-700 rounded-lg shadow-sm transition-colors disabled:opacity-50"
          >
            {isSubmitting ? 'Issuing Material...' : 'Issue Material'}
          </button>
        </>
      }
    >
      <form
        id="material-issue-form"
        onSubmit={handleSubmit}
        noValidate
        className="p-4 sm:p-6 space-y-4 overflow-y-auto max-h-[75vh]"
      >
        {ordersError && (
          <InlineAlert variant="error" role="alert" compact message={ordersError} />
        )}

        {!isLoadingOrders && !ordersError && eligibleOrders.length === 0 && (
          <InlineAlert
            variant="warning"
            compact
            title="No Released or In-Progress Orders Available"
            message="Manufacturing orders must be in RELEASED or IN_PROGRESS status before raw materials can be issued to production."
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
          <div>
            <label
              htmlFor={orderInputId}
              className="block text-xs font-semibold text-slate-700 mb-1"
            >
              Manufacturing Order <span className="text-rose-600">*</span>
            </label>
            <select
              id={orderInputId}
              value={form.manufacturingOrderId}
              disabled={isLoadingOrders}
              onChange={(e) => handleOrderChange(e.target.value)}
              aria-invalid={Boolean(fieldErrors.manufacturingOrderId)}
              aria-describedby={fieldErrors.manufacturingOrderId ? `${orderInputId}-err` : undefined}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              <option value="">
                {isLoadingOrders ? 'Loading orders...' : 'Select manufacturing order...'}
              </option>
              {eligibleOrders.map((order) => (
                <option key={order.id} value={String(order.id)}>
                  {order.orderNumber} — {order.productName} ({order.status})
                </option>
              ))}
            </select>
            {fieldErrors.manufacturingOrderId && (
              <p id={`${orderInputId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.manufacturingOrderId}
              </p>
            )}
          </div>

          <div>
            <label
              htmlFor={batchInputId}
              className="block text-xs font-semibold text-slate-700 mb-1"
            >
              Target Production Batch (Optional)
            </label>
            <select
              id={batchInputId}
              value={form.batchId}
              onChange={(e) => handleFieldChange('batchId', e.target.value)}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              <option value="">Order-level dispensing (all active batches)</option>
              {eligibleBatches.map((batch) => (
                <option key={batch.id} value={String(batch.id)}>
                  {batch.batchNumber} ({batch.batchSize} {batch.batchUnit} — {batch.status})
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* Order Recipe Bill of Materials Panel */}
        {form.manufacturingOrderId && (
          <div className="bg-slate-50 border border-slate-200 rounded-xl p-3.5 space-y-2">
            <div className="flex items-center justify-between gap-2">
              <div className="text-xs font-bold uppercase tracking-wider text-slate-600 flex items-center space-x-1.5">
                <ClipboardList className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                <span>
                  Order Recipe Material Requirements
                  {orderRequirements?.recipeCode
                    ? ` (${orderRequirements.recipeCode} v${orderRequirements.recipeVersion})`
                    : ''}
                </span>
              </div>
              {isLoadingRequirements && (
                <span className="inline-flex items-center space-x-1 text-[11px] text-slate-500">
                  <RefreshCw className="w-3 h-3 animate-spin" aria-hidden="true" />
                  <span>Loading BOM...</span>
                </span>
              )}
            </div>

            {!isLoadingRequirements && requirementList.length === 0 ? (
              <p className="text-xs text-slate-500">
                No structured recipe bill-of-materials breakdown loaded for this order.
              </p>
            ) : (
              <div className="divide-y divide-slate-200 bg-white border border-slate-200 rounded-lg overflow-hidden">
                {requirementList.map((req) => {
                  const isSelected =
                    req.rawMaterialId != null &&
                    String(req.rawMaterialId) === String(form.rawMaterialId);
                  return (
                    <div
                      key={`${req.recipeMaterialName}-${req.rawMaterialId || 'unlinked'}`}
                      className={`px-3 py-2 flex flex-wrap items-center justify-between gap-2 text-xs ${
                        isSelected ? 'bg-indigo-50/70' : ''
                      }`}
                    >
                      <div className="min-w-0">
                        <span className="font-semibold text-slate-800">{req.recipeMaterialName}</span>
                        <span className="ml-2 text-slate-500 tabular-nums">
                          Req: {formatQuantityDisplay(req.totalRequiredQuantity, req.recipeUnit)} |
                          Issued: {formatQuantityDisplay(req.issuedQuantity, req.recipeUnit)} |
                          Remaining:{' '}
                          <strong className="text-slate-900">
                            {formatQuantityDisplay(req.remainingRequiredQuantity, req.recipeUnit)}
                          </strong>
                        </span>
                      </div>
                      <div className="flex items-center space-x-2 shrink-0">
                        {!req.registeredInInventory ? (
                          <StatusBadge status="OUT_OF_STOCK" label="Unregistered" size="xs" />
                        ) : req.fulfilled ? (
                          <StatusBadge status="COMPLETED" label="Fulfilled" size="xs" />
                        ) : (
                          <button
                            type="button"
                            onClick={() => handleSelectRequirement(req)}
                            className="px-2 py-1 text-[11px] font-semibold text-indigo-700 bg-indigo-50 hover:bg-indigo-100 border border-indigo-200 rounded transition-colors"
                          >
                            {isSelected ? 'Selected' : 'Select'}
                          </button>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label
              htmlFor={materialInputId}
              className="block text-xs font-semibold text-slate-700 mb-1"
            >
              Raw Material <span className="text-rose-600">*</span>
            </label>
            <select
              id={materialInputId}
              value={form.rawMaterialId}
              onChange={(e) => handleMaterialChange(e.target.value)}
              aria-invalid={Boolean(fieldErrors.rawMaterialId)}
              aria-describedby={fieldErrors.rawMaterialId ? `${materialInputId}-err` : undefined}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              <option value="">Select raw material...</option>
              {activeMaterials.map((mat) => (
                <option key={mat.id} value={String(mat.id)}>
                  {mat.materialCode} — {mat.materialName} (Avail:{' '}
                  {formatQuantityDisplay(mat.availableStock, mat.unit)})
                </option>
              ))}
            </select>
            {fieldErrors.rawMaterialId && (
              <p id={`${materialInputId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.rawMaterialId}
              </p>
            )}
          </div>

          <div>
            <label
              htmlFor={lotInputId}
              className="block text-xs font-semibold text-slate-700 mb-1"
            >
              Material Lot (FEFO — Active Non-Expired) <span className="text-rose-600">*</span>
            </label>
            <select
              id={lotInputId}
              value={form.materialLotId}
              onChange={(e) => handleFieldChange('materialLotId', e.target.value)}
              aria-invalid={Boolean(fieldErrors.materialLotId)}
              aria-describedby={fieldErrors.materialLotId ? `${lotInputId}-err` : undefined}
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              <option value="">
                {form.rawMaterialId
                  ? usableLotsForMaterial.length > 0
                    ? 'Select active non-expired lot...'
                    : 'No active non-expired lots available'
                  : 'Select raw material first...'}
              </option>
              {usableLotsForMaterial.map((lot) => (
                <option key={lot.id} value={String(lot.id)}>
                  {lot.lotNumber} — Avail: {lot.availableQuantity} {lot.unit} (Exp:{' '}
                  {formatCalendarDate(lot.expiryDate)} @ {lot.warehouseCode}/{lot.locationCode})
                </option>
              ))}
            </select>
            {fieldErrors.materialLotId && (
              <p id={`${lotInputId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                {fieldErrors.materialLotId}
              </p>
            )}
          </div>

          <div className="grid grid-cols-3 gap-2">
            <div className="col-span-2">
              <label
                htmlFor={quantityInputId}
                className="block text-xs font-semibold text-slate-700 mb-1"
              >
                Issue Quantity <span className="text-rose-600">*</span>
              </label>
              <input
                id={quantityInputId}
                type="number"
                step="any"
                min="0.0001"
                value={form.quantity}
                onChange={(e) => handleFieldChange('quantity', e.target.value)}
                placeholder="e.g., 25"
                aria-invalid={Boolean(fieldErrors.quantity)}
                aria-describedby={fieldErrors.quantity ? `${quantityInputId}-err` : undefined}
                className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 tabular-nums focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
              />
              {fieldErrors.quantity && (
                <p id={`${quantityInputId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                  {fieldErrors.quantity}
                </p>
              )}
            </div>

            <div>
              <label
                htmlFor={unitInputId}
                className="block text-xs font-semibold text-slate-700 mb-1"
              >
                Unit <span className="text-rose-600">*</span>
              </label>
              <input
                id={unitInputId}
                type="text"
                readOnly
                value={form.unit}
                aria-invalid={Boolean(fieldErrors.unit)}
                aria-describedby={fieldErrors.unit ? `${unitInputId}-err` : undefined}
                className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 bg-slate-100 text-slate-700"
              />
              {fieldErrors.unit && (
                <p id={`${unitInputId}-err`} role="alert" className="text-xs text-rose-600 mt-1">
                  {fieldErrors.unit}
                </p>
              )}
            </div>
          </div>

          <div>
            <label htmlFor={refInputId} className="block text-xs font-semibold text-slate-700 mb-1">
              Dispensing Ticket / Reference #
            </label>
            <input
              id={refInputId}
              type="text"
              value={form.referenceNumber}
              onChange={(e) => handleFieldChange('referenceNumber', e.target.value)}
              placeholder="Optional unique reference (e.g., DISP-2026-01)"
              className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            />
          </div>
        </div>

        <div>
          <label htmlFor={reasonInputId} className="block text-xs font-semibold text-slate-700 mb-1">
            Dispensing Notes / Reason
          </label>
          <input
            id={reasonInputId}
            type="text"
            value={form.reason}
            onChange={(e) => handleFieldChange('reason', e.target.value)}
            placeholder="Weighed and verified for dispensing booth..."
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
          />
        </div>
      </form>
    </ModalDialog>
  );
}

