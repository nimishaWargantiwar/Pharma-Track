import React, { useState, useEffect, useCallback, useRef, useId } from 'react';
import {
  X,
  ClipboardList,
  Box,
  FlaskConical,
  Calendar,
  Clock,
  Layers,
  FileText,
  RefreshCw,
  Send,
  XCircle,
  CheckCircle2,
  Play,
  ClipboardCheck,
  ShieldCheck,
  ArrowUpRight,
  Package,
  QrCode,
  ArrowRightLeft,
  Sparkles,
} from 'lucide-react';
import { getOrderById, updateOrderStatus, updateBatchStatus } from '../services/orderApi';
import { getOrderMaterialRequirements } from '../services/inventoryApi';
import {
  calculateOrderBatchProgress,
  canReleaseOrder,
  canCancelOrder,
  canStartBatch as canStartBatchRule,
  canCancelBatch as canCancelBatchRule,
  canCompleteBatch as canCompleteBatchRule,
  isLastRemainingNonCancelledBatch,
  formatCalendarDate,
} from '../utils/orderUtils';
import { formatAppShortDateTime } from '../utils/dateTimeUtils.js';
import { InlineAlert, ProgressBar, StatusBadge } from './ui/index.js';
import BatchQrModal from './BatchQrModal.jsx';
import ConfirmModal from './ConfirmModal';
import BatchYieldModal from './BatchYieldModal';
import ExecutionHistoryModal from './ExecutionHistoryModal';
import { useAuth } from '../context/AuthContext.jsx';
import { useNavigation } from '../context/NavigationContext.jsx';
import {
  canCancelBatchRole,
  canCompleteBatchRole,
  canIssueMaterials,
  canManageManufacturingOrders,
  canStartBatchRole,
  canViewRecipes,
} from '../utils/rbacUtils.js';

export default function OrderDetailsModal({ isOpen, onClose, orderId, onOrderUpdated, onOpenCopilot = null }) {
  const { role } = useAuth();
  const { navigateToTab } = useNavigation();
  const canManageOrders = canManageManufacturingOrders(role);
  const canStartByRole = canStartBatchRole(role);
  const canCompleteByRole = canCompleteBatchRole(role);
  const canCancelByRole = canCancelBatchRole(role);
  const canOpenRecipes = canViewRecipes(role);
  const canIssueByRole = canIssueMaterials(role);

  const titleId = useId();
  const drawerRef = useRef(null);
  const previousFocusRef = useRef(null);

  const [order, setOrder] = useState(null);
  const [materialRequirements, setMaterialRequirements] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);

  // Lifecycle action state
  const [confirmConfig, setConfirmConfig] = useState(null);
  const [yieldModalBatch, setYieldModalBatch] = useState(null);
  const [historyModalBatch, setHistoryModalBatch] = useState(null);
  const [qrModalBatch, setQrModalBatch] = useState(null);
  const [isUpdatingStatus, setIsUpdatingStatus] = useState(false);
  const [actionFeedback, setActionFeedback] = useState(null);
  const isSubmittingRef = useRef(false);

  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  const blockerRef = useRef({
    blocked: false,
  });
  blockerRef.current.blocked = Boolean(
    isUpdatingStatus || confirmConfig || yieldModalBatch || historyModalBatch
  );

  const fetchOrderDetails = useCallback(async () => {
    if (!orderId) return;
    setIsLoading(true);
    setError(null);
    setActionFeedback(null);
    try {
      const data = await getOrderById(orderId);
      setOrder(data || null);
      if (data?.id) {
        getOrderMaterialRequirements(data.id)
          .then((reqData) => {
            if (
              reqData &&
              !Array.isArray(reqData) &&
              Number(reqData.orderId) === Number(data.id) &&
              Array.isArray(reqData.requirements)
            ) {
              setMaterialRequirements(reqData);
            } else {
              setMaterialRequirements(null);
            }
          })
          .catch(() => setMaterialRequirements(null));
      } else {
        setMaterialRequirements(null);
      }
    } catch (err) {
      setError(err.message || 'Failed to load manufacturing order details');
      setOrder(null);
      setMaterialRequirements(null);
    } finally {
      setIsLoading(false);
    }
  }, [orderId]);

  useEffect(() => {
    if (isOpen && orderId) {
      setOrder(null);
      setMaterialRequirements(null);
      setConfirmConfig(null);
      setYieldModalBatch(null);
      setHistoryModalBatch(null);
      setActionFeedback(null);
      setIsUpdatingStatus(false);
      isSubmittingRef.current = false;
      fetchOrderDetails();
    } else if (!isOpen) {
      setOrder(null);
      setMaterialRequirements(null);
      setError(null);
      setIsLoading(false);
      setConfirmConfig(null);
      setYieldModalBatch(null);
      setHistoryModalBatch(null);
      setActionFeedback(null);
      setIsUpdatingStatus(false);
      isSubmittingRef.current = false;
    }
  }, [isOpen, orderId, fetchOrderDetails]);

  useEffect(() => {
    if (!isOpen || typeof document === 'undefined') {
      return undefined;
    }

    previousFocusRef.current = document.activeElement || null;
    drawerRef.current?.focus?.();

    const handleKeyDown = (event) => {
      const key = event.key || event.code;
      if (key === 'Escape') {
        if (blockerRef.current.blocked) return;
        const hasModalOverlay = Boolean(document.body?.querySelector?.('[aria-modal="true"]'));
        const hasHistoryDrawer = Boolean(document.body?.querySelector?.('[data-ebr-drawer="true"]'));
        if (!hasModalOverlay && !hasHistoryDrawer && typeof onCloseRef.current === 'function') {
          event.preventDefault?.();
          onCloseRef.current();
        }
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      const restoreTarget = previousFocusRef.current;
      previousFocusRef.current = null;
      if (
        restoreTarget &&
        typeof restoreTarget.focus === 'function' &&
        (!document.body?.contains || document.body.contains(restoreTarget))
      ) {
        restoreTarget.focus();
      }
    };
  }, [isOpen]);

  if (!isOpen) return null;

  const formatDateOnly = (dateString) => formatCalendarDate(dateString);
  const formatDateTime = (dateTimeString) => formatAppShortDateTime(dateTimeString);

  const batches = Array.isArray(order?.batches) ? order.batches : [];
  const batchProgress = calculateOrderBatchProgress(order);
  const isOrderCancelled = order?.status === 'CANCELLED' || batchProgress.allCancelled;
  const hasNotes = order?.notes != null && String(order.notes).trim() !== '';

  const canRelease = Boolean(canManageOrders && order && !isLoading && !error && canReleaseOrder(order));
  const canCancel = Boolean(canManageOrders && order && !isLoading && !error && canCancelOrder(order));

  const canStartBatch = (batch) => canStartByRole && canStartBatchRule(batch, order);
  const canCancelBatch = (batch) => canCancelByRole && canCancelBatchRule(batch, order);
  const canCompleteBatch = (batch) => canCompleteByRole && canCompleteBatchRule(batch);

  const handleRequestRelease = () => {
    if (!order || isUpdatingStatus) return;
    const activeBatchCount = batchProgress.activeCount || batches.length;
    setConfirmConfig({
      actionType: 'ORDER_STATUS',
      targetStatus: 'RELEASED',
      title: 'Release Manufacturing Order',
      message: `Are you sure you want to release order "${order.orderNumber}" to production? Once released, shop-floor execution of its ${activeBatchCount} planned batch(es) can begin.`,
      confirmText: 'Release Order',
      confirmVariant: 'primary',
    });
  };

  const handleRequestCancel = () => {
    if (!order || isUpdatingStatus) return;
    setConfirmConfig({
      actionType: 'ORDER_STATUS',
      targetStatus: 'CANCELLED',
      title: 'Cancel Manufacturing Order',
      message: `Are you sure you want to cancel order "${order.orderNumber}"? This will mark the manufacturing order and all remaining planned batches as CANCELLED. This action cannot be undone.`,
      confirmText: 'Cancel Order',
      confirmVariant: 'danger',
    });
  };

  const handleRequestStartBatch = (batch) => {
    if (!batch || isUpdatingStatus) return;
    setConfirmConfig({
      actionType: 'BATCH_START',
      batch,
      targetStatus: 'IN_PROGRESS',
      title: 'Start Production Batch',
      message: `Are you sure you want to start batch "${batch.batchNumber}" (${batch.batchSize} ${batch.batchUnit})? Its start timestamp will be recorded.`,
      confirmText: 'Start Batch',
      confirmVariant: 'primary',
    });
  };

  const handleRequestCancelBatch = (batch) => {
    if (!batch || isUpdatingStatus) return;
    const cancelsParentOrder = isLastRemainingNonCancelledBatch(batch, order);
    const parentImpactNote = cancelsParentOrder
      ? ` Because no non-cancelled batches will remain, manufacturing order "${order.orderNumber}" will also transition to CANCELLED.`
      : ' Remaining planned batches in this order will stay active.';
    setConfirmConfig({
      actionType: 'BATCH_CANCEL',
      batch,
      targetStatus: 'CANCELLED',
      title: 'Cancel Production Batch',
      message: `Are you sure you want to cancel planned batch "${batch.batchNumber}" (${batch.batchSize} ${batch.batchUnit})?${parentImpactNote} This action cannot be undone.`,
      confirmText: 'Cancel Batch',
      confirmVariant: 'danger',
    });
  };

  const handleRequestCompleteBatch = (batch) => {
    if (!batch || isUpdatingStatus) return;
    setYieldModalBatch(batch);
  };

  const handleCloseConfirm = () => {
    if (isUpdatingStatus) return;
    setConfirmConfig(null);
  };

  const handleConfirmAction = async () => {
    if (isSubmittingRef.current || isUpdatingStatus || !confirmConfig || !order?.id) {
      return;
    }

    isSubmittingRef.current = true;
    setIsUpdatingStatus(true);
    setActionFeedback(null);

    try {
      if (confirmConfig.actionType === 'BATCH_START') {
        const targetBatch = confirmConfig.batch;
        const updatedBatch = await updateBatchStatus(targetBatch.id, 'IN_PROGRESS');
        setConfirmConfig(null);

        let latestOrder = null;
        try {
          const refreshed = await getOrderById(order.id);
          if (refreshed) {
            latestOrder = refreshed;
            setOrder(refreshed);
          }
        } catch {
          if (updatedBatch?.id) {
            setOrder((prev) => {
              if (!prev) return prev;
              const nextOrder = {
                ...prev,
                batches: Array.isArray(prev.batches)
                  ? prev.batches.map((b) => (b.id === updatedBatch.id ? updatedBatch : b))
                  : prev.batches,
              };
              latestOrder = nextOrder;
              return nextOrder;
            });
          }
        }

        const successMessage = `Batch ${updatedBatch?.batchNumber || targetBatch.batchNumber} has been started (IN_PROGRESS).`;
        setActionFeedback({ type: 'success', message: successMessage });
        if (onOrderUpdated) {
          await onOrderUpdated(latestOrder || order, successMessage);
        }
      } else if (confirmConfig.actionType === 'BATCH_CANCEL') {
        const targetBatch = confirmConfig.batch;
        const updatedBatch = await updateBatchStatus(targetBatch.id, 'CANCELLED');
        setConfirmConfig(null);

        let latestOrder = null;
        try {
          const refreshed = await getOrderById(order.id);
          if (refreshed) {
            latestOrder = refreshed;
            setOrder(refreshed);
          }
        } catch {
          if (updatedBatch?.id) {
            const nextBatches = Array.isArray(order.batches)
              ? order.batches.map((b) => (b.id === updatedBatch.id ? updatedBatch : b))
              : order.batches;
            const allNowCancelled =
              Array.isArray(nextBatches) &&
              nextBatches.length > 0 &&
              nextBatches.every((b) => b.status === 'CANCELLED');
            latestOrder = {
              ...order,
              status: allNowCancelled ? 'CANCELLED' : order.status,
              batches: nextBatches,
            };
            setOrder(latestOrder);
          }
        }

        const effectiveOrder = latestOrder || order;
        let successMessage = `Batch ${updatedBatch?.batchNumber || targetBatch.batchNumber} has been cancelled.`;
        if (effectiveOrder?.status === 'CANCELLED') {
          successMessage += ` All batches are now cancelled, so manufacturing order ${effectiveOrder.orderNumber || ''} has been marked CANCELLED.`;
        }

        setActionFeedback({ type: 'success', message: successMessage });
        if (onOrderUpdated) {
          await onOrderUpdated(effectiveOrder, successMessage);
        }
      } else {
        const { targetStatus } = confirmConfig;
        const updatedOrder = await updateOrderStatus(order.id, targetStatus);
        setConfirmConfig(null);

        let latestOrder = updatedOrder;
        try {
          const refreshed = await getOrderById(order.id);
          if (refreshed) {
            latestOrder = refreshed;
          }
        } catch {
          // Keep PATCH response fallback
        }

        setOrder(latestOrder);

        const successMessage =
          targetStatus === 'RELEASED'
            ? `Manufacturing order ${latestOrder?.orderNumber || ''} has been released to production.`
            : `Manufacturing order ${latestOrder?.orderNumber || ''} has been cancelled.`;

        setActionFeedback({ type: 'success', message: successMessage });
        if (onOrderUpdated) {
          await onOrderUpdated(latestOrder, successMessage);
        }
      }
    } catch (err) {
      setConfirmConfig(null);
      setActionFeedback({
        type: 'error',
        message: err.message || 'Failed to update status',
      });
      try {
        const refreshed = await getOrderById(order.id);
        if (refreshed) {
          setOrder(refreshed);
        }
      } catch {
        // Ignore secondary refresh error
      }
    } finally {
      isSubmittingRef.current = false;
      setIsUpdatingStatus(false);
    }
  };

  const handleBatchCompleted = async (updatedBatch) => {
    setActionFeedback(null);
    setIsUpdatingStatus(true);

    try {
      let latestOrder = null;
      try {
        const refreshed = await getOrderById(order.id);
        if (refreshed) {
          latestOrder = refreshed;
          setOrder(refreshed);
        }
      } catch {
        if (updatedBatch?.id) {
          setOrder((prev) => {
            if (!prev) return prev;
            const nextOrder = {
              ...prev,
              batches: Array.isArray(prev.batches)
                ? prev.batches.map((b) => (b.id === updatedBatch.id ? updatedBatch : b))
                : prev.batches,
            };
            latestOrder = nextOrder;
            return nextOrder;
          });
        }
      }

      const batchNum = updatedBatch?.batchNumber || '';
      const yieldDisplay =
        updatedBatch?.actualYield != null
          ? `${updatedBatch.actualYield}${updatedBatch.batchUnit ? ` ${updatedBatch.batchUnit}` : ''}`
          : '';

      let successMessage = `Batch ${batchNum} completed${yieldDisplay ? ` with actual yield of ${yieldDisplay}` : ''}.`;
      if (latestOrder?.status === 'COMPLETED') {
        successMessage += ` Manufacturing order ${latestOrder.orderNumber || ''} is now COMPLETED.`;
      }

      setActionFeedback({ type: 'success', message: successMessage });
      if (onOrderUpdated) {
        await onOrderUpdated(latestOrder || order, successMessage);
      }
    } finally {
      setIsUpdatingStatus(false);
    }
  };

  const handleSafeClose = () => {
    if (blockerRef.current.blocked) return;
    onClose?.();
  };

  const historyContext =
    historyModalBatch && order
      ? {
          ...historyModalBatch,
          orderId: order.id,
          orderNumber: order.orderNumber,
          productName: order.productName,
          productCode: order.productCode,
          recipeId: order.recipeId,
          recipeCode: order.recipeCode,
          recipeVersion: order.recipeVersion,
          fromOrderDetails: true,
        }
      : historyModalBatch;

  return (
    <>
      <div className="fixed inset-0 z-40 flex justify-end">
        {/* Click-outside scrim */}
        <div
          aria-hidden="true"
          onClick={handleSafeClose}
          className="fixed inset-0 bg-slate-900/35 backdrop-blur-sm transition-opacity"
        />

        {/* In-tree accessible non-modal order & batch detail drawer */}
        <aside
          ref={drawerRef}
          role="region"
          aria-labelledby={titleId}
          tabIndex={-1}
          data-testid="order-detail-drawer"
          className="relative z-10 w-full max-w-5xl bg-white shadow-2xl border-l border-slate-200 flex flex-col h-full focus:outline-none"
        >
          {/* Header */}
          <div className="px-4 sm:px-6 py-4 bg-slate-50 border-b border-slate-200 flex items-start justify-between gap-3 flex-shrink-0">
            <div className="flex items-start space-x-3 min-w-0">
              <div className="w-9 h-9 rounded-lg bg-pharma-600 flex items-center justify-center text-white shadow-sm shrink-0">
                <ClipboardList className="w-5 h-5" aria-hidden="true" />
              </div>
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h2
                    id={titleId}
                    className="text-base sm:text-lg font-bold text-slate-900 font-mono tracking-tight break-all tabular-nums"
                  >
                    {order?.orderNumber || 'Manufacturing Order Details'}
                  </h2>
                  {order?.status && (
                    <StatusBadge
                      status={order.status}
                      domain="order"
                      showDot
                      pulseDot={order.status === 'IN_PROGRESS'}
                    />
                  )}
                </div>
                <p className="text-xs text-slate-500 mt-0.5">
                  Manufacturing Order Specification &amp; Batch Records
                </p>
              </div>
            </div>

            <div className="flex items-center space-x-2 shrink-0">
              {order?.orderNumber && typeof onOpenCopilot === 'function' && (
                <button
                  type="button"
                  onClick={() => {
                    onOpenCopilot({
                      activeModule: 'orders',
                      contextType: 'MANUFACTURING_ORDER',
                      contextId: order.orderNumber,
                      title: `${order.orderNumber} (${order.productName || 'Order'})`,
                    });
                  }}
                  data-testid="order-ask-copilot-button"
                  title={`Ask PharmaTrack AI Copilot about order ${order.orderNumber}`}
                  aria-label={`Ask AI Copilot about order ${order.orderNumber}`}
                  className="inline-flex items-center space-x-1.5 px-2.5 py-1.5 rounded-lg border border-pharma-200 bg-pharma-50 hover:bg-pharma-100 text-pharma-700 text-xs font-semibold shadow-2xs transition-colors"
                >
                  <Sparkles className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                  <span className="hidden sm:inline">Ask Copilot</span>
                </button>
              )}
              <button
                type="button"
                onClick={fetchOrderDetails}
                disabled={isLoading || isUpdatingStatus}
                title="Refresh order details"
                aria-label="Refresh order details"
                className="text-slate-500 hover:text-slate-700 p-1.5 rounded-lg hover:bg-slate-100 transition-colors disabled:opacity-50"
              >
                <RefreshCw
                  className={`w-4 h-4 ${isLoading ? 'animate-spin text-pharma-600' : ''}`}
                  aria-hidden="true"
                />
              </button>
              <button
                type="button"
                onClick={handleSafeClose}
                disabled={isUpdatingStatus}
                aria-label="Close modal"
                className="text-slate-500 hover:text-slate-700 p-1.5 rounded-lg hover:bg-slate-100 transition-colors disabled:opacity-50"
              >
                <X className="w-5 h-5" aria-hidden="true" />
              </button>
            </div>
          </div>

          {/* Scrollable Body */}
          <div className="p-4 sm:p-6 overflow-y-auto space-y-6 flex-1 text-sm">
            {isLoading && (
              <div className="py-16 text-center">
                <RefreshCw className="w-8 h-8 mx-auto text-pharma-600 animate-spin mb-3" aria-hidden="true" />
                <p className="text-sm font-medium text-slate-700">
                  Loading manufacturing order details...
                </p>
                <p className="text-xs text-slate-500 mt-1">
                  Fetching latest order and batch status from server
                </p>
              </div>
            )}

            {!isLoading && error && (
              <InlineAlert
                variant="error"
                title="Unable to load manufacturing order details"
                message={error}
                actions={
                  <button
                    type="button"
                    onClick={fetchOrderDetails}
                    className="inline-flex items-center space-x-1.5 text-xs font-semibold px-3 py-1.5 bg-white border border-rose-300 rounded-lg text-rose-800 hover:bg-rose-50 transition-colors"
                  >
                    <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" />
                    <span>Retry</span>
                  </button>
                }
              />
            )}

            {!isLoading && !error && !order && (
              <div className="py-14 text-center">
                <div className="w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center mx-auto mb-3 text-slate-400">
                  <ClipboardList className="w-6 h-6" aria-hidden="true" />
                </div>
                <h4 className="text-base font-semibold text-slate-800">
                  Order details unavailable
                </h4>
                <p className="text-sm text-slate-500 mt-1">
                  No details could be retrieved for the selected manufacturing order.
                </p>
              </div>
            )}

            {!isLoading && !error && order && (
              <>
                {actionFeedback && (
                  <InlineAlert
                    variant={actionFeedback.type === 'success' ? 'success' : 'error'}
                    role="alert"
                    title={actionFeedback.type === 'success' ? 'Success' : 'Error'}
                    message={actionFeedback.message}
                    onDismiss={() => setActionFeedback(null)}
                    dismissLabel="Dismiss alert"
                  />
                )}

                {isOrderCancelled && (
                  <InlineAlert
                    variant="error"
                    compact
                    title="Order Cancelled"
                    message="This manufacturing order is cancelled and its planned batches are excluded from completion."
                  />
                )}

                {/* Product & Recipe Summary Cards */}
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="bg-slate-50 rounded-xl border border-slate-200 p-4">
                    <div className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-3 flex items-center space-x-1.5">
                      <Box className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                      <span>Target Product</span>
                    </div>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                      <div>
                        <span className="text-xs text-slate-500 block">Product Name</span>
                        <span className="text-sm font-semibold text-slate-900 mt-0.5 block">
                          {order.productName || '—'}
                        </span>
                      </div>
                      <div>
                        <span className="text-xs text-slate-500 block">Product Code</span>
                        <span className="text-sm font-mono font-medium text-slate-800 bg-white px-2 py-0.5 rounded border border-slate-200 inline-block mt-0.5 tabular-nums">
                          {order.productCode || '—'}
                        </span>
                      </div>
                    </div>
                  </div>

                  <div className="bg-slate-50 rounded-xl border border-slate-200 p-4">
                    <div className="flex flex-wrap items-center justify-between gap-2 mb-3">
                      <div className="text-xs font-bold uppercase tracking-wider text-slate-500 flex items-center space-x-1.5">
                        <FlaskConical className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                        <span>Master Recipe Snapshot</span>
                      </div>
                      {canOpenRecipes && order.recipeId && (
                        <button
                          type="button"
                          onClick={() => {
                            handleSafeClose();
                            navigateToTab('recipes', { recipeId: order.recipeId });
                          }}
                          className="inline-flex items-center space-x-1 text-[11px] font-semibold text-pharma-700 hover:text-pharma-800 hover:underline whitespace-nowrap shrink-0"
                        >
                          <span>Inspect Recipe</span>
                          <ArrowUpRight className="w-3 h-3" aria-hidden="true" />
                        </button>
                      )}
                    </div>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                      <div>
                        <span className="text-xs text-slate-500 block">Recipe Code</span>
                        <span className="text-sm font-mono font-semibold text-slate-900 mt-0.5 block tabular-nums">
                          {order.recipeCode || '—'}
                        </span>
                      </div>
                      <div>
                        <span className="text-xs text-slate-500 block">Recipe Version</span>
                        <span className="mt-0.5 inline-block">
                          {order.recipeVersion != null ? (
                            <span className="px-2 py-0.5 rounded text-xs font-bold bg-indigo-50 text-indigo-700 border border-indigo-200 tabular-nums">
                              v{order.recipeVersion}
                            </span>
                          ) : (
                            <span className="text-sm text-slate-700">—</span>
                          )}
                        </span>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Quantity, Target Batch Count & Dates Grid */}
                <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
                  <div className="bg-white border border-slate-200 rounded-lg p-3">
                    <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider block">
                      Planned Quantity
                    </span>
                    <span className="text-base font-bold text-slate-900 mt-1 block tabular-nums">
                      {order.plannedQuantity != null ? (
                        <>
                          {order.plannedQuantity}{' '}
                          <span className="text-xs font-medium text-slate-500">
                            {order.quantityUnit || ''}
                          </span>
                        </>
                      ) : (
                        '—'
                      )}
                    </span>
                  </div>

                  <div className="bg-white border border-slate-200 rounded-lg p-3">
                    <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider block">
                      Target Batches
                    </span>
                    <span className="text-base font-bold text-slate-900 mt-1 block tabular-nums">
                      {order.targetBatchCount != null ? order.targetBatchCount : '—'}
                    </span>
                  </div>

                  <div className="bg-white border border-slate-200 rounded-lg p-3">
                    <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider flex items-center space-x-1">
                      <Calendar className="w-3 h-3 text-slate-500" aria-hidden="true" />
                      <span>Planned Start</span>
                    </span>
                    <span className="text-xs font-medium text-slate-800 mt-1.5 block tabular-nums">
                      {formatDateOnly(order.plannedStartDate)}
                    </span>
                  </div>

                  <div className="bg-white border border-slate-200 rounded-lg p-3">
                    <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider flex items-center space-x-1">
                      <Calendar className="w-3 h-3 text-slate-500" aria-hidden="true" />
                      <span>Planned End</span>
                    </span>
                    <span className="text-xs font-medium text-slate-800 mt-1.5 block tabular-nums">
                      {formatDateOnly(order.plannedEndDate)}
                    </span>
                  </div>

                  <div className="bg-white border border-slate-200 rounded-lg p-3">
                    <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider flex items-center space-x-1">
                      <Clock className="w-3 h-3 text-slate-500" aria-hidden="true" />
                      <span>Actual Start</span>
                    </span>
                    <span className="text-xs font-medium text-slate-800 mt-1.5 block tabular-nums">
                      {formatDateTime(order.actualStartDate)}
                    </span>
                  </div>

                  <div className="bg-white border border-slate-200 rounded-lg p-3">
                    <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider flex items-center space-x-1">
                      <Clock className="w-3 h-3 text-slate-500" aria-hidden="true" />
                      <span>Actual End</span>
                    </span>
                    <span className="text-xs font-medium text-slate-800 mt-1.5 block tabular-nums">
                      {formatDateTime(order.actualEndDate)}
                    </span>
                  </div>
                </div>

                {/* Production Notes */}
                <div>
                  <h4 className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-1.5 flex items-center space-x-1.5">
                    <FileText className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                    <span>Production Notes</span>
                  </h4>
                  <div className="text-sm text-slate-700 bg-slate-50 p-3.5 rounded-lg border border-slate-200 whitespace-pre-line leading-relaxed">
                    {hasNotes ? order.notes : '—'}
                  </div>
                </div>

                {/* Batch Completion Summary & Batches Table */}
                <div className="space-y-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <h4 className="text-xs font-bold uppercase tracking-wider text-slate-500 flex items-center space-x-1.5 tabular-nums">
                      <Layers className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                      <span>
                        Production Batches ({batches.length}
                        {order.targetBatchCount != null ? ` of ${order.targetBatchCount}` : ''})
                      </span>
                    </h4>
                  </div>

                  {batches.length > 0 && (
                    <div className="bg-slate-50 border border-slate-200 rounded-lg p-3.5">
                      {isOrderCancelled ? (
                        <ProgressBar
                          value={0}
                          max={100}
                          tone="danger"
                          size="sm"
                          label="Order batch completion progress"
                          showLabel={true}
                          summaryText={
                            batchProgress.cancelledCount > 0
                              ? `All ${batchProgress.cancelledCount} cancelled`
                              : 'Order cancelled'
                          }
                          showPercentage={false}
                          valueText="Cancelled — 0% completed"
                        />
                      ) : (
                        <ProgressBar
                          value={batchProgress.progressPercent}
                          max={100}
                          tone={
                            batchProgress.progressPercent === 100
                              ? 'success'
                              : batchProgress.progressPercent > 0 || batchProgress.inProgressCount > 0
                                ? 'warning'
                                : 'neutral'
                          }
                          size="sm"
                          label="Order batch completion progress"
                          showLabel={true}
                          summaryText={
                            batchProgress.cancelledCount > 0
                              ? `${batchProgress.completedCount} of ${batchProgress.activeCount} active (${batchProgress.cancelledCount} cancelled)`
                              : `${batchProgress.completedCount} of ${batchProgress.totalCount} completed`
                          }
                          showPercentage={true}
                          valueText={`${batchProgress.completedCount} of ${batchProgress.activeCount} completed (${batchProgress.progressPercent}%)`}
                        />
                      )}
                    </div>
                  )}

                  {batches.length === 0 ? (
                    <div className="border border-slate-200 rounded-lg p-8 text-center bg-slate-50/50">
                      <Layers className="w-6 h-6 mx-auto text-slate-400 mb-2" aria-hidden="true" />
                      <p className="text-sm font-medium text-slate-700">No batches available</p>
                      <p className="text-xs text-slate-500 mt-0.5">
                        No production batches were returned for this manufacturing order.
                      </p>
                    </div>
                  ) : (
                    <div className="border border-slate-200 rounded-lg overflow-hidden">
                      <div className="overflow-x-auto">
                        <table className="w-full text-left border-collapse text-xs">
                          <thead>
                            <tr className="bg-slate-50 border-b border-slate-200 text-slate-500 font-semibold uppercase tracking-wider">
                              <th scope="col" className="py-2.5 px-3 sm:px-4 whitespace-nowrap">Batch Number</th>
                              <th scope="col" className="py-2.5 px-3 whitespace-nowrap">Batch Size</th>
                              <th scope="col" className="py-2.5 px-3 whitespace-nowrap">Status</th>
                              <th scope="col" className="py-2.5 px-3 whitespace-nowrap">Actual Yield</th>
                              <th scope="col" className="py-2.5 px-3 hidden lg:table-cell whitespace-nowrap">Mfg / Expiry</th>
                              <th scope="col" className="py-2.5 px-3 hidden md:table-cell whitespace-nowrap">Execution Window</th>
                              <th scope="col" className="py-2.5 px-3 sm:pr-4 text-right whitespace-nowrap">Actions</th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-slate-100">
                            {batches.map((batch) => {
                              const showStart = canStartBatch(batch);
                              const showCancel = canCancelBatch(batch);
                              const showComplete = canCompleteBatch(batch);
                              const showHistory =
                                batch.status === 'IN_PROGRESS' || batch.status === 'COMPLETED';
                              const showWorkspaceNav = batch.status === 'IN_PROGRESS';
                              const showQualityNav = batch.status === 'COMPLETED';
                              const actionsDisabled =
                                isUpdatingStatus ||
                                isLoading ||
                                Boolean(yieldModalBatch) ||
                                Boolean(historyModalBatch);

                              const yieldPercent =
                                batch.actualYield != null && Number(batch.batchSize) > 0
                                  ? ((Number(batch.actualYield) / Number(batch.batchSize)) * 100).toFixed(1)
                                  : null;

                              return (
                                <tr key={batch.id ?? batch.batchNumber} className="hover:bg-slate-50/60 align-top">
                                  <td className="py-3 px-3 sm:px-4 whitespace-nowrap font-mono font-semibold text-pharma-700 tabular-nums">
                                    {batch.batchNumber || '—'}
                                  </td>
                                  <td className="py-3 px-3 whitespace-nowrap font-medium text-slate-900 tabular-nums">
                                    {batch.batchSize != null
                                      ? `${batch.batchSize}${batch.batchUnit ? ` ${batch.batchUnit}` : ''}`
                                      : '—'}
                                  </td>
                                  <td className="py-3 px-3 whitespace-nowrap">
                                    <div className="flex flex-col items-start gap-1">
                                      <StatusBadge
                                        status={batch.status}
                                        size="xs"
                                        showDot
                                        pulseDot={batch.status === 'IN_PROGRESS'}
                                      />
                                      {batch.qaStatus && (
                                        <StatusBadge
                                          status={batch.qaStatus}
                                          domain="qa"
                                          size="xs"
                                        />
                                      )}
                                    </div>
                                  </td>
                                  <td className="py-3 px-3 whitespace-nowrap font-mono text-slate-800 tabular-nums">
                                    {batch.actualYield != null ? (
                                      <div>
                                        <span>
                                          {batch.actualYield}
                                          {batch.batchUnit ? ` ${batch.batchUnit}` : ''}
                                        </span>
                                        {yieldPercent && (
                                          <span className="block text-[11px] font-sans text-slate-500">
                                            {yieldPercent}% yield
                                          </span>
                                        )}
                                      </div>
                                    ) : (
                                      '—'
                                    )}
                                  </td>
                                  <td className="py-3 px-3 hidden lg:table-cell whitespace-nowrap text-slate-700 tabular-nums">
                                    <div>Mfg: {formatDateOnly(batch.manufactureDate)}</div>
                                    <div className="text-[11px] text-slate-500">
                                      Exp: {formatDateOnly(batch.expiryDate)}
                                    </div>
                                  </td>
                                  <td className="py-3 px-3 hidden md:table-cell whitespace-nowrap text-slate-600 tabular-nums">
                                    <div>Start: {formatDateTime(batch.startTime)}</div>
                                    <div className="text-[11px] text-slate-500">
                                      End: {formatDateTime(batch.endTime)}
                                    </div>
                                  </td>
                                  <td className="py-3 px-3 sm:pr-4 text-right whitespace-nowrap">
                                    {showStart ||
                                    showCancel ||
                                    showComplete ||
                                    showHistory ||
                                    showWorkspaceNav ||
                                    showQualityNav ? (
                                      <div className="inline-flex flex-wrap items-center justify-end gap-1.5">
                                        {showStart && (
                                          <button
                                            type="button"
                                            onClick={() => handleRequestStartBatch(batch)}
                                            disabled={actionsDisabled}
                                            className="inline-flex items-center space-x-1 px-2.5 py-1 text-[11px] font-semibold text-white bg-pharma-600 hover:bg-pharma-700 disabled:opacity-50 disabled:cursor-not-allowed rounded-md shadow-sm transition-colors"
                                          >
                                            <Play className="w-3 h-3" aria-hidden="true" />
                                            <span>Start Batch</span>
                                          </button>
                                        )}
                                        {showCancel && (
                                          <button
                                            type="button"
                                            onClick={() => handleRequestCancelBatch(batch)}
                                            disabled={actionsDisabled}
                                            title="Cancel this planned batch before order release"
                                            className="inline-flex items-center space-x-1 px-2.5 py-1 text-[11px] font-semibold text-rose-700 bg-rose-50 hover:bg-rose-100 border border-rose-200 disabled:opacity-50 disabled:cursor-not-allowed rounded-md shadow-sm transition-colors"
                                          >
                                            <XCircle className="w-3 h-3" aria-hidden="true" />
                                            <span>Cancel Batch</span>
                                          </button>
                                        )}
                                        {showComplete && (
                                          <button
                                            type="button"
                                            onClick={() => handleRequestCompleteBatch(batch)}
                                            disabled={actionsDisabled}
                                            className="inline-flex items-center space-x-1 px-2.5 py-1 text-[11px] font-semibold text-white bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed rounded-md shadow-sm transition-colors"
                                          >
                                            <CheckCircle2 className="w-3 h-3" aria-hidden="true" />
                                            <span>Complete Batch</span>
                                          </button>
                                        )}
                                        {showWorkspaceNav && (
                                          <button
                                            type="button"
                                            onClick={() => {
                                              handleSafeClose();
                                              navigateToTab('execution', { batchId: batch.id });
                                            }}
                                            disabled={actionsDisabled}
                                            title="Open batch in Production Execution workspace"
                                            className="inline-flex items-center space-x-1 px-2.5 py-1 text-[11px] font-semibold text-amber-800 bg-amber-50 hover:bg-amber-100 border border-amber-200 disabled:opacity-50 rounded-md shadow-sm transition-colors"
                                          >
                                            <span>Workspace</span>
                                            <ArrowUpRight className="w-3 h-3" aria-hidden="true" />
                                          </button>
                                        )}
                                        {showQualityNav && (
                                          <button
                                            type="button"
                                            onClick={() => {
                                              handleSafeClose();
                                              navigateToTab('quality', { batchId: batch.id });
                                            }}
                                            disabled={actionsDisabled}
                                            title="Open batch quality dossier in Quality Management"
                                            className="inline-flex items-center space-x-1 px-2.5 py-1 text-[11px] font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 disabled:opacity-50 rounded-md shadow-sm transition-colors"
                                          >
                                            <ShieldCheck className="w-3 h-3" aria-hidden="true" />
                                            <span>QA Dossier</span>
                                          </button>
                                        )}
                                        <button
                                          type="button"
                                          onClick={() => setQrModalBatch(batch)}
                                          disabled={actionsDisabled}
                                          title="View Batch QR Code & Traceability Identity"
                                          className="inline-flex items-center space-x-1 px-2.5 py-1 text-[11px] font-semibold text-teal-700 bg-teal-50 hover:bg-teal-100 border border-teal-200 disabled:opacity-50 rounded-md shadow-sm transition-colors"
                                        >
                                          <QrCode className="w-3 h-3 text-teal-600" aria-hidden="true" />
                                          <span>Batch QR</span>
                                        </button>
                                        {showHistory && (
                                          <button
                                            type="button"
                                            onClick={() => setHistoryModalBatch(batch)}
                                            disabled={actionsDisabled}
                                            title="View read-only batch execution history"
                                            className="inline-flex items-center space-x-1 px-2.5 py-1 text-[11px] font-semibold text-slate-700 bg-white hover:bg-slate-100 border border-slate-300 disabled:opacity-50 disabled:cursor-not-allowed rounded-md shadow-sm transition-colors"
                                          >
                                            <ClipboardCheck className="w-3 h-3 text-slate-500" aria-hidden="true" />
                                            <span>Execution History</span>
                                          </button>
                                        )}
                                      </div>
                                    ) : (
                                      <span className="text-slate-500">—</span>
                                    )}
                                  </td>
                                </tr>
                              );
                            })}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  )}
                </div>

                {/* Order Raw Material Requirements (Inventory Integration) */}
                {materialRequirements &&
                  Array.isArray(materialRequirements.requirements) &&
                  materialRequirements.requirements.length > 0 && (
                    <div className="space-y-2.5">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <h4 className="text-xs font-bold uppercase tracking-wider text-slate-500 flex items-center space-x-1.5">
                          <Package className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                          <span>Order Raw Material Requirements</span>
                        </h4>
                        {canIssueByRole &&
                          (order.status === 'RELEASED' || order.status === 'IN_PROGRESS') && (
                            <button
                              type="button"
                              onClick={() => {
                                handleSafeClose();
                                navigateToTab('inventory', {
                                  action: 'issue',
                                  orderId: order.id,
                                });
                              }}
                              className="inline-flex items-center space-x-1 px-2.5 py-1 text-xs font-semibold text-indigo-700 bg-indigo-50 hover:bg-indigo-100 border border-indigo-200 rounded-md transition-colors"
                            >
                              <ArrowRightLeft className="w-3.5 h-3.5" aria-hidden="true" />
                              <span>Issue Materials</span>
                            </button>
                          )}
                      </div>

                      <div className="border border-slate-200 rounded-lg overflow-hidden">
                        <div className="overflow-x-auto">
                          <table className="w-full text-left border-collapse text-xs">
                            <thead>
                              <tr className="bg-slate-50 border-b border-slate-200 text-slate-500 font-semibold uppercase tracking-wider">
                                <th className="py-2 px-3">Material</th>
                                <th className="py-2 px-3 text-right">Required</th>
                                <th className="py-2 px-3 text-right">Issued</th>
                                <th className="py-2 px-3 text-right">Remaining</th>
                                <th className="py-2 px-3 text-right">Available Stock</th>
                                <th className="py-2 px-3">Status</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                              {materialRequirements.requirements.map((req) => (
                                <tr key={req.recipeMaterialName} className="hover:bg-slate-50/60">
                                  <td className="py-2.5 px-3">
                                    <div className="font-semibold text-slate-900">
                                      {req.recipeMaterialName}
                                    </div>
                                    {req.rawMaterialCode && (
                                      <div className="font-mono text-[11px] text-pharma-700">
                                        {req.rawMaterialCode}
                                      </div>
                                    )}
                                  </td>
                                  <td className="py-2.5 px-3 text-right font-mono tabular-nums text-slate-800">
                                    {req.totalRequiredQuantity} {req.recipeUnit}
                                  </td>
                                  <td className="py-2.5 px-3 text-right font-mono tabular-nums text-emerald-700 font-semibold">
                                    {req.issuedQuantity ?? 0} {req.inventoryUnit || req.recipeUnit}
                                  </td>
                                  <td className="py-2.5 px-3 text-right font-mono tabular-nums text-slate-900 font-bold">
                                    {req.remainingRequiredQuantity ?? req.totalRequiredQuantity}{' '}
                                    {req.recipeUnit}
                                  </td>
                                  <td className="py-2.5 px-3 text-right font-mono tabular-nums text-slate-700">
                                    {req.registeredInInventory
                                      ? `${req.availableStock ?? 0} ${req.inventoryUnit || req.recipeUnit}`
                                      : 'Unregistered'}
                                  </td>
                                  <td className="py-2.5 px-3">
                                    <StatusBadge
                                      status={
                                        !req.registeredInInventory
                                          ? 'OUT_OF_STOCK'
                                          : req.fulfilled
                                            ? 'FULFILLED'
                                            : 'PARTIAL'
                                      }
                                      domain="inventory"
                                      tone={
                                        !req.registeredInInventory
                                          ? 'warning'
                                          : req.fulfilled
                                            ? 'success'
                                            : 'info'
                                      }
                                      label={
                                        !req.registeredInInventory
                                          ? 'Unregistered'
                                          : req.unitMismatch
                                            ? 'Unit Mismatch'
                                            : req.fulfilled
                                              ? 'Fulfilled'
                                              : 'Pending Issue'
                                      }
                                      size="xs"
                                    />
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    </div>
                  )}
              </>
            )}
          </div>

          {/* Footer */}
          <div className="px-4 sm:px-6 py-3.5 bg-slate-50 border-t border-slate-200 flex flex-wrap items-center justify-between gap-2.5 flex-shrink-0">
            <div className="flex flex-wrap items-center gap-2">
              {canRelease && (
                <button
                  type="button"
                  onClick={handleRequestRelease}
                  disabled={isUpdatingStatus || isLoading}
                  className="inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-semibold text-white bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed rounded-lg shadow-sm transition-colors"
                >
                  <Send className="w-4 h-4" aria-hidden="true" />
                  <span>Release Order</span>
                </button>
              )}

              {canCancel && (
                <button
                  type="button"
                  onClick={handleRequestCancel}
                  disabled={isUpdatingStatus || isLoading}
                  className="inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-semibold text-rose-700 bg-rose-50 hover:bg-rose-100 border border-rose-200 disabled:opacity-50 disabled:cursor-not-allowed rounded-lg shadow-sm transition-colors"
                >
                  <XCircle className="w-4 h-4" aria-hidden="true" />
                  <span>Cancel Order</span>
                </button>
              )}
            </div>

            <button
              type="button"
              onClick={handleSafeClose}
              disabled={isUpdatingStatus}
              className="px-4 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-100 disabled:opacity-50 border border-slate-300 rounded-lg shadow-sm transition-colors ml-auto"
            >
              Close
            </button>
          </div>
        </aside>
      </div>

      <ConfirmModal
        isOpen={Boolean(confirmConfig)}
        title={confirmConfig?.title || ''}
        message={confirmConfig?.message || ''}
        confirmText={confirmConfig?.confirmText || 'Confirm'}
        confirmVariant={confirmConfig?.confirmVariant || 'primary'}
        onConfirm={handleConfirmAction}
        onClose={handleCloseConfirm}
        isLoading={isUpdatingStatus}
      />

      {canCompleteByRole && (
        <BatchYieldModal
          isOpen={Boolean(yieldModalBatch)}
          batch={yieldModalBatch}
          onClose={() => setYieldModalBatch(null)}
          onSuccess={handleBatchCompleted}
        />
      )}

      <ExecutionHistoryModal
        isOpen={Boolean(historyModalBatch)}
        batch={historyModalBatch}
        batchContext={historyContext}
        onClose={() => setHistoryModalBatch(null)}
      />

      <BatchQrModal
        isOpen={Boolean(qrModalBatch)}
        batch={qrModalBatch}
        onClose={() => setQrModalBatch(null)}
      />
    </>
  );
}
