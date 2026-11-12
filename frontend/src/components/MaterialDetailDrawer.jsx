import React, { useEffect, useId, useMemo, useRef } from 'react';
import {
  ArrowRightLeft,
  FlaskConical,
  History,
  Layers,
  Package,
  PackagePlus,
  SlidersHorizontal,
  X,
} from 'lucide-react';
import { InlineAlert, StatusBadge } from './ui/index.js';
import { formatAppShortDateTime } from '../utils/dateTimeUtils.js';
import { formatCalendarDate } from '../utils/orderUtils.js';
import {
  formatQuantityDisplay,
  formatSignedDelta,
  MATERIAL_TYPE_LABELS,
} from '../utils/inventoryUtils.js';

export default function MaterialDetailDrawer({
  isOpen,
  onClose,
  material,
  lots = [],
  movements = [],
  canReceive = false,
  canIssue = false,
  canAdjust = false,
  onReceiveGoods = null,
  onIssueFromLot = null,
  onAdjustLot = null,
}) {
  const titleId = useId();
  const drawerRef = useRef(null);
  const previousFocusRef = useRef(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!isOpen || typeof document === 'undefined') {
      return undefined;
    }

    previousFocusRef.current = document.activeElement || null;
    drawerRef.current?.focus?.();

    const handleKeyDown = (event) => {
      const key = event.key || event.code;
      if (key === 'Escape') {
        const hasModalOverlay = Boolean(document.body?.querySelector?.('[aria-modal="true"]'));
        if (!hasModalOverlay && typeof onCloseRef.current === 'function') {
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

  const materialLots = useMemo(() => {
    if (!material) return [];
    return (Array.isArray(lots) ? lots : []).filter(
      (lot) => Number(lot.rawMaterialId) === Number(material.id)
    );
  }, [lots, material]);

  const materialMovements = useMemo(() => {
    if (!material) return [];
    return (Array.isArray(movements) ? movements : []).filter(
      (m) => Number(m.rawMaterialId) === Number(material.id)
    );
  }, [movements, material]);

  if (!isOpen || !material) return null;

  return (
    <div className="fixed inset-0 z-40 flex justify-end">
      <div
        aria-hidden="true"
        onClick={onClose}
        className="fixed inset-0 bg-slate-900/35 backdrop-blur-sm transition-opacity"
      />

      <aside
        ref={drawerRef}
        role="region"
        aria-labelledby={titleId}
        tabIndex={-1}
        data-testid="material-detail-drawer"
        className="relative z-10 w-full max-w-3xl bg-white shadow-2xl border-l border-slate-200 flex flex-col h-full focus:outline-none"
      >
        <div className="px-4 sm:px-6 py-4 bg-slate-50 border-b border-slate-200 flex items-start justify-between gap-3 shrink-0">
          <div className="flex items-start space-x-3 min-w-0">
            <div className="w-9 h-9 rounded-lg bg-pharma-600 flex items-center justify-center text-white shadow-sm shrink-0">
              <Package className="w-5 h-5" aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h2
                  id={titleId}
                  className="text-base sm:text-lg font-bold text-slate-900 font-mono tracking-tight break-all"
                >
                  {material.materialCode}
                </h2>
                <StatusBadge status={material.materialType} domain="inventory" size="xs" variant="tag" />
                <StatusBadge status={material.stockStatus} domain="inventory" size="xs" showDot />
              </div>
              <p className="text-xs text-slate-600 mt-0.5 truncate">
                {material.materialName} &bull;{' '}
                {MATERIAL_TYPE_LABELS[material.materialType] || material.materialType}
              </p>
            </div>
          </div>

          <button
            type="button"
            onClick={onClose}
            aria-label="Close material details"
            className="text-slate-500 hover:text-slate-700 p-1.5 rounded-lg hover:bg-slate-100 transition-colors shrink-0"
          >
            <X className="w-5 h-5" aria-hidden="true" />
          </button>
        </div>

        <div className="p-4 sm:p-6 overflow-y-auto space-y-6 flex-1 text-sm">
          {material.stockStatus === 'OUT_OF_STOCK' && (
            <InlineAlert
              variant="error"
              compact
              title="Zero Usable Stock"
              message="No active, non-expired stock is currently available for dispensing."
            />
          )}
          {material.stockStatus === 'LOW_STOCK' && (
            <InlineAlert
              variant="warning"
              compact
              title="Below Minimum Reorder Threshold"
              message={`Available usable stock (${formatQuantityDisplay(
                material.availableStock,
                material.unit
              )}) is at or below minimum threshold (${formatQuantityDisplay(
                material.minimumStockLevel,
                material.unit
              )}).`}
            />
          )}

          {/* Stock Breakdown Grid */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <div className="bg-white border border-slate-200 rounded-lg p-3">
              <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 block">
                Available Stock
              </span>
              <span className="text-base font-bold text-emerald-700 mt-1 block tabular-nums">
                {formatQuantityDisplay(material.availableStock, material.unit)}
              </span>
            </div>
            <div className="bg-white border border-slate-200 rounded-lg p-3">
              <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 block">
                Min Threshold
              </span>
              <span className="text-base font-bold text-slate-900 mt-1 block tabular-nums">
                {formatQuantityDisplay(material.minimumStockLevel, material.unit)}
              </span>
            </div>
            <div className="bg-white border border-slate-200 rounded-lg p-3">
              <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 block">
                Quarantined
              </span>
              <span className="text-base font-bold text-amber-700 mt-1 block tabular-nums">
                {formatQuantityDisplay(material.quarantinedStock, material.unit)}
              </span>
            </div>
            <div className="bg-white border border-slate-200 rounded-lg p-3">
              <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 block">
                Expired Unusable
              </span>
              <span className="text-base font-bold text-rose-700 mt-1 block tabular-nums">
                {formatQuantityDisplay(material.expiredStock, material.unit)}
              </span>
            </div>
          </div>

          {material.description && (
            <div className="bg-slate-50 border border-slate-200 rounded-lg p-3.5 text-xs text-slate-700 leading-relaxed">
              {material.description}
            </div>
          )}

          {/* Linked Master Recipes */}
          <div>
            <h3 className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-2 flex items-center space-x-1.5">
              <FlaskConical className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
              <span>Linked Master Recipes ({material.linkedRecipeCount || 0})</span>
            </h3>
            {Array.isArray(material.linkedRecipeCodes) && material.linkedRecipeCodes.length > 0 ? (
              <div className="flex flex-wrap gap-1.5">
                {material.linkedRecipeCodes.map((rc) => (
                  <span
                    key={rc}
                    className="px-2.5 py-1 rounded-md text-xs font-mono font-semibold bg-indigo-50 text-indigo-700 border border-indigo-200"
                  >
                    {rc}
                  </span>
                ))}
              </div>
            ) : (
              <p className="text-xs text-slate-500">
                Not currently linked by name to an existing Master Recipe BOM.
              </p>
            )}
          </div>

          {/* Material Lots Table */}
          <div>
            <div className="flex items-center justify-between gap-2 mb-2">
              <h3 className="text-xs font-bold uppercase tracking-wider text-slate-500 flex items-center space-x-1.5 tabular-nums">
                <Layers className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                <span>Tracked Material Lots ({materialLots.length})</span>
              </h3>
              {canReceive && material.active && typeof onReceiveGoods === 'function' && (
                <button
                  type="button"
                  onClick={() => onReceiveGoods(material)}
                  className="inline-flex items-center space-x-1 px-2.5 py-1 text-xs font-semibold text-emerald-800 bg-emerald-50 hover:bg-emerald-100 border border-emerald-200 rounded-lg transition-colors"
                >
                  <PackagePlus className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>Receive Lot</span>
                </button>
              )}
            </div>

            {materialLots.length === 0 ? (
              <div className="border border-slate-200 rounded-lg p-6 text-center text-xs text-slate-500 bg-slate-50/50">
                No material lots have been received for this raw material yet.
              </div>
            ) : (
              <div className="border border-slate-200 rounded-lg overflow-hidden">
                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse text-xs">
                    <thead>
                      <tr className="bg-slate-50 border-b border-slate-200 text-slate-500 font-semibold uppercase">
                        <th className="py-2.5 px-3">Lot #</th>
                        <th className="py-2.5 px-3">Location</th>
                        <th className="py-2.5 px-3 text-right">Available</th>
                        <th className="py-2.5 px-3">Expiry</th>
                        <th className="py-2.5 px-3">Status</th>
                        <th className="py-2.5 px-3 text-right">Actions</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {materialLots.map((lot) => {
                        const usable =
                          lot.effectiveStatus === 'ACTIVE' &&
                          !lot.expired &&
                          Number(lot.availableQuantity || 0) > 0;
                        return (
                          <tr key={lot.id} className="hover:bg-slate-50/60">
                            <td className="py-2.5 px-3 font-mono font-semibold text-slate-900 whitespace-nowrap">
                              {lot.lotNumber}
                            </td>
                            <td className="py-2.5 px-3 text-slate-700 whitespace-nowrap">
                              {lot.warehouseCode} / {lot.locationCode}
                            </td>
                            <td className="py-2.5 px-3 text-right font-mono font-bold text-slate-900 tabular-nums whitespace-nowrap">
                              {formatQuantityDisplay(lot.availableQuantity, lot.unit)}
                            </td>
                            <td className="py-2.5 px-3 whitespace-nowrap tabular-nums">
                              <span className={lot.expired ? 'text-rose-700 font-semibold' : 'text-slate-700'}>
                                {formatCalendarDate(lot.expiryDate)}
                              </span>
                            </td>
                            <td className="py-2.5 px-3 whitespace-nowrap">
                              <StatusBadge
                                status={lot.effectiveStatus}
                                domain="inventory"
                                size="xs"
                                showDot
                              />
                            </td>
                            <td className="py-2.5 px-3 text-right whitespace-nowrap space-x-1.5">
                              {canIssue && usable && typeof onIssueFromLot === 'function' && (
                                <button
                                  type="button"
                                  onClick={() => onIssueFromLot(lot)}
                                  className="inline-flex items-center space-x-1 px-2 py-1 text-[11px] font-semibold text-indigo-700 bg-indigo-50 hover:bg-indigo-100 border border-indigo-200 rounded"
                                >
                                  <ArrowRightLeft className="w-3 h-3" aria-hidden="true" />
                                  <span>Issue</span>
                                </button>
                              )}
                              {canAdjust && typeof onAdjustLot === 'function' && (
                                <button
                                  type="button"
                                  onClick={() => onAdjustLot(lot)}
                                  className="inline-flex items-center space-x-1 px-2 py-1 text-[11px] font-semibold text-purple-700 bg-purple-50 hover:bg-purple-100 border border-purple-200 rounded"
                                >
                                  <SlidersHorizontal className="w-3 h-3" aria-hidden="true" />
                                  <span>Adjust</span>
                                </button>
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

          {/* Material-scoped Stock Movements */}
          <div>
            <h3 className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-2 flex items-center space-x-1.5 tabular-nums">
              <History className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
              <span>Recent Stock Movements ({materialMovements.length})</span>
            </h3>
            {materialMovements.length === 0 ? (
              <div className="border border-slate-200 rounded-lg p-6 text-center text-xs text-slate-500 bg-slate-50/50">
                No stock movements recorded yet for this material.
              </div>
            ) : (
              <div className="border border-slate-200 rounded-lg divide-y divide-slate-100 bg-white">
                {materialMovements.slice(0, 20).map((mv) => (
                  <div key={mv.id} className="p-3 flex flex-wrap items-start justify-between gap-2 text-xs">
                    <div className="space-y-0.5 min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-mono font-semibold text-slate-900">
                          {mv.movementNumber}
                        </span>
                        <StatusBadge status={mv.movementType} domain="inventory" size="xs" />
                        <span className="font-mono text-slate-600">Lot: {mv.lotNumber}</span>
                        {mv.orderNumber && (
                          <span className="px-1.5 py-0.5 rounded bg-slate-100 font-mono text-slate-700">
                            Order: {mv.orderNumber}
                          </span>
                        )}
                      </div>
                      <p className="text-slate-600">{mv.reason || '—'}</p>
                      <p className="text-[11px] text-slate-500">
                        By {mv.performedBy} &bull; {formatAppShortDateTime(mv.occurredAt)}
                      </p>
                    </div>
                    <div className="text-right shrink-0 font-mono tabular-nums">
                      <span
                        className={`font-bold ${
                          Number(mv.quantityDelta) > 0
                            ? 'text-emerald-700'
                            : Number(mv.quantityDelta) < 0
                              ? 'text-rose-700'
                              : 'text-slate-700'
                        }`}
                      >
                        {formatSignedDelta(mv.quantityDelta, mv.unit)}
                      </span>
                      <div className="text-[11px] text-slate-500">
                        {mv.quantityBefore} &rarr; {mv.quantityAfter} {mv.unit}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        <div className="px-4 sm:px-6 py-3.5 bg-slate-50 border-t border-slate-200 flex items-center justify-end shrink-0">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-100 border border-slate-300 rounded-lg shadow-sm transition-colors"
          >
            Close
          </button>
        </div>
      </aside>
    </div>
  );
}

