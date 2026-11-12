import React, { useEffect, useId, useMemo, useRef } from 'react';
import {
  X,
  FlaskConical,
  Box,
  Layers,
  CheckCircle2,
  History,
  Edit2,
  Copy,
  CheckCircle,
} from 'lucide-react';
import { InlineAlert, StatusBadge } from './ui/index.js';
import { formatAppShortDateTime } from '../utils/dateTimeUtils.js';

export default function RecipeDetailDrawer({
  isOpen,
  onClose,
  recipe,
  allVersions = [],
  onSelectVersion = null,
  canAuthor = false,
  canApprove = false,
  onEdit = null,
  onApprove = null,
  onCreateVersion = null,
  actionLoadingId = null,
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

  const orderedSteps = useMemo(() => {
    if (!recipe?.steps) return [];
    return [...recipe.steps].sort((a, b) => (a.stepNumber ?? 0) - (b.stepNumber ?? 0));
  }, [recipe?.steps]);

  const sortedVersions = useMemo(() => {
    if (!Array.isArray(allVersions) || allVersions.length === 0) return [];
    return [...allVersions].sort((a, b) => (b.version ?? 0) - (a.version ?? 0));
  }, [allVersions]);

  if (!isOpen || !recipe) return null;

  const isDraft = recipe.status === 'DRAFT';
  const isApproved = recipe.status === 'APPROVED';
  const isObsolete = recipe.status === 'OBSOLETE';
  const isActionBusy = actionLoadingId === recipe.id;

  const lifecycleBanner = isDraft
    ? {
        variant: 'info',
        title: 'Draft Formulation Under Authoring',
        message:
          'This recipe version is in DRAFT status. It can be edited or submitted for QA approval before manufacturing orders can reference it.',
      }
    : isApproved
      ? {
          variant: 'success',
          title: 'Active Approved Master Recipe',
          message:
            'This approved version is locked as an immutable formulation standard. Create a new DRAFT version to propose revisions.',
        }
      : {
          variant: 'warning',
          title: 'Superseded / Obsolete Revision',
          message:
            'This recipe revision has been superseded and is retained strictly for historical batch record traceability.',
        };

  return (
    <div className="fixed inset-0 z-40 flex justify-end">
      {/* Click-outside scrim */}
      <div
        aria-hidden="true"
        onClick={onClose}
        className="fixed inset-0 bg-slate-900/35 backdrop-blur-sm transition-opacity"
      />

      {/* In-tree non-modal accessible detail drawer */}
      <aside
        ref={drawerRef}
        role="region"
        aria-labelledby={titleId}
        tabIndex={-1}
        data-testid="recipe-detail-drawer"
        className="relative z-10 w-full max-w-2xl bg-white shadow-2xl border-l border-slate-200 flex flex-col h-full focus:outline-none"
      >
        {/* Header */}
        <div className="px-4 sm:px-6 py-4 bg-slate-50 border-b border-slate-200 flex items-start justify-between gap-3 shrink-0">
          <div className="flex items-start space-x-3 min-w-0">
            <div className="w-9 h-9 rounded-lg bg-pharma-600 flex items-center justify-center text-white shadow-sm shrink-0">
              <FlaskConical className="w-5 h-5" aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h2
                  id={titleId}
                  className="text-base sm:text-lg font-bold text-slate-900 font-mono tracking-tight break-all"
                >
                  {recipe.recipeCode}
                </h2>
                <span className="px-2 py-0.5 rounded text-xs font-bold bg-indigo-50 text-indigo-700 border border-indigo-200 tabular-nums">
                  Version {recipe.version}
                </span>
                <StatusBadge status={recipe.status} showDot />
              </div>
              <p className="text-xs text-slate-500 mt-0.5 truncate">
                {recipe.productName} &middot;{' '}
                <span className="font-mono">{recipe.productCode}</span>
              </p>
            </div>
          </div>

          <button
            type="button"
            onClick={onClose}
            aria-label="Close recipe details"
            className="text-slate-500 hover:text-slate-700 p-1.5 rounded-lg hover:bg-slate-100 transition-colors shrink-0"
          >
            <X className="w-5 h-5" aria-hidden="true" />
          </button>
        </div>

        {/* Scrollable Body */}
        <div className="p-4 sm:p-6 overflow-y-auto space-y-6 flex-1 text-sm">
          <InlineAlert
            variant={lifecycleBanner.variant}
            compact
            title={lifecycleBanner.title}
            message={lifecycleBanner.message}
          />

          {/* Associated Product Summary */}
          <div className="bg-slate-50 rounded-xl border border-slate-200 p-4">
            <div className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-2 flex items-center space-x-1.5">
              <Box className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
              <span>Target Product Master Data</span>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <div>
                <span className="text-xs text-slate-500 block">Product Name</span>
                <span className="text-sm font-semibold text-slate-900">{recipe.productName}</span>
              </div>
              <div>
                <span className="text-xs text-slate-500 block">Product Code</span>
                <span className="text-sm font-mono font-medium text-slate-800 bg-white px-2 py-0.5 rounded border border-slate-200 inline-block">
                  {recipe.productCode}
                </span>
              </div>
              <div>
                <span className="text-xs text-slate-500 block">Product ID</span>
                <span className="text-sm font-mono text-slate-600 tabular-nums">
                  #{recipe.productId}
                </span>
              </div>
            </div>
          </div>

          {/* Batch Specifications & Timestamps */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <div className="bg-white border border-slate-200 rounded-lg p-3">
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider block">
                Batch Size
              </span>
              <span className="text-lg font-bold text-slate-900 mt-0.5 block tabular-nums">
                {recipe.batchSize}
              </span>
            </div>
            <div className="bg-white border border-slate-200 rounded-lg p-3">
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider block">
                Batch Unit
              </span>
              <span className="text-lg font-bold text-slate-900 mt-0.5 block">
                {recipe.batchUnit}
              </span>
            </div>
            <div className="bg-white border border-slate-200 rounded-lg p-3">
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider block">
                Created
              </span>
              <span className="text-xs font-medium text-slate-700 mt-1 block tabular-nums">
                {formatAppShortDateTime(recipe.createdAt)}
              </span>
            </div>
            <div className="bg-white border border-slate-200 rounded-lg p-3">
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider block">
                Last Updated
              </span>
              <span className="text-xs font-medium text-slate-700 mt-1 block tabular-nums">
                {formatAppShortDateTime(recipe.updatedAt)}
              </span>
            </div>
          </div>

          {/* Description */}
          {recipe.description && (
            <div>
              <h3 className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-1.5">
                Process Description / Notes
              </h3>
              <p className="text-sm text-slate-700 bg-slate-50 p-3.5 rounded-lg border border-slate-200 whitespace-pre-line leading-relaxed">
                {recipe.description}
              </p>
            </div>
          )}

          {/* Raw Materials Table */}
          <div>
            <h3 className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-2 flex items-center space-x-1.5 tabular-nums">
              <Layers className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
              <span>Raw Materials Bill of Materials ({recipe.materials?.length || 0})</span>
            </h3>
            <div className="border border-slate-200 rounded-lg overflow-hidden">
              <div className="overflow-x-auto">
                <table className="w-full text-left border-collapse text-xs">
                  <thead>
                    <tr className="bg-slate-50 border-b border-slate-200 text-slate-500 font-semibold uppercase">
                      <th scope="col" className="py-2.5 px-3 sm:px-4 whitespace-nowrap">
                        #
                      </th>
                      <th scope="col" className="py-2.5 px-3 sm:px-4">
                        Material Name
                      </th>
                      <th scope="col" className="py-2.5 px-3 sm:px-4 text-right whitespace-nowrap">
                        Quantity
                      </th>
                      <th scope="col" className="py-2.5 px-3 sm:px-4 whitespace-nowrap">
                        Unit
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {recipe.materials && recipe.materials.length > 0 ? (
                      recipe.materials.map((mat, idx) => (
                        <tr
                          key={mat.id ?? `${recipe.id}-mat-${mat.materialName}-${idx}`}
                          className="hover:bg-slate-50/50"
                        >
                          <td className="py-2 px-3 sm:px-4 text-slate-500 font-mono tabular-nums whitespace-nowrap">
                            {idx + 1}
                          </td>
                          <td className="py-2 px-3 sm:px-4 font-semibold text-slate-800">
                            {mat.materialName}
                          </td>
                          <td className="py-2 px-3 sm:px-4 text-right font-mono font-bold text-slate-900 tabular-nums whitespace-nowrap">
                            {mat.quantity}
                          </td>
                          <td className="py-2 px-3 sm:px-4 text-slate-600 whitespace-nowrap">{mat.unit}</td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan={4} className="py-3 px-4 text-center text-slate-500 italic">
                          No materials defined.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          {/* Manufacturing Steps */}
          <div>
            <h3 className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-2 flex items-center space-x-1.5 tabular-nums">
              <CheckCircle2 className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
              <span>Manufacturing &amp; Processing Steps ({orderedSteps.length})</span>
            </h3>
            <div className="space-y-2.5">
              {orderedSteps.length > 0 ? (
                orderedSteps.map((step, idx) => (
                  <div
                    key={step.id ?? `${recipe.id}-step-${step.stepNumber}-${idx}`}
                    className="border border-slate-200 rounded-lg p-3.5 bg-slate-50/50"
                  >
                    <div className="flex items-center space-x-2.5 mb-1.5">
                      <span className="w-6 h-6 rounded-full bg-pharma-100 text-pharma-800 text-xs font-bold flex items-center justify-center shrink-0 tabular-nums">
                        {step.stepNumber}
                      </span>
                      <span className="font-semibold text-slate-900 text-sm">
                        {step.operationName}
                      </span>
                    </div>
                    <p className="text-xs text-slate-600 pl-8 whitespace-pre-line leading-relaxed">
                      {step.instructions}
                    </p>
                  </div>
                ))
              ) : (
                <div className="border border-slate-200 rounded-lg p-4 text-center text-slate-500 italic text-xs">
                  No manufacturing steps defined.
                </div>
              )}
            </div>
          </div>

          {/* Version History */}
          {sortedVersions.length > 0 && (
            <div>
              <h3 className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-2 flex items-center space-x-1.5 tabular-nums">
                <History className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                <span>Product Recipe Version History ({sortedVersions.length})</span>
              </h3>
              <div className="border border-slate-200 rounded-lg divide-y divide-slate-100 bg-white">
                {sortedVersions.map((ver) => {
                  const isCurrent = ver.id === recipe.id;
                  return (
                    <div
                      key={ver.id ?? `ver-${ver.recipeCode}-${ver.version}`}
                      className={`px-3.5 py-2.5 flex items-center justify-between gap-3 ${
                        isCurrent ? 'bg-pharma-50/50' : 'hover:bg-slate-50'
                      }`}
                    >
                      <div className="flex flex-wrap items-center gap-2 min-w-0">
                        <span className="font-mono text-xs font-bold text-indigo-700 bg-indigo-50 border border-indigo-200 px-2 py-0.5 rounded tabular-nums">
                          v{ver.version}
                        </span>
                        <span className="font-mono text-xs font-semibold text-slate-800 truncate">
                          {ver.recipeCode}
                        </span>
                        <StatusBadge status={ver.status} size="xs" showDot />
                        <span className="text-[11px] text-slate-500 tabular-nums">
                          {formatAppShortDateTime(ver.updatedAt || ver.createdAt)}
                        </span>
                      </div>

                      {isCurrent ? (
                        <span className="text-[11px] font-semibold text-pharma-700 shrink-0">
                          Inspecting
                        </span>
                      ) : (
                        typeof onSelectVersion === 'function' && (
                          <button
                            type="button"
                            onClick={() => onSelectVersion(ver)}
                            className="text-xs font-semibold text-pharma-700 hover:text-pharma-800 hover:underline shrink-0"
                          >
                            Inspect
                          </button>
                        )
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-4 sm:px-6 py-3.5 bg-slate-50 border-t border-slate-200 flex flex-wrap items-center justify-between gap-3 shrink-0">
          <div className="flex flex-wrap items-center gap-2">
            {canApprove && isDraft && typeof onApprove === 'function' && (
              <button
                type="button"
                onClick={() => onApprove(recipe)}
                disabled={isActionBusy}
                className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-emerald-800 bg-emerald-50 hover:bg-emerald-100 border border-emerald-200 rounded-lg transition-colors disabled:opacity-50"
              >
                <CheckCircle className="w-3.5 h-3.5 text-emerald-600" aria-hidden="true" />
                <span>Approve Version</span>
              </button>
            )}

            {canAuthor && isDraft && typeof onEdit === 'function' && (
              <button
                type="button"
                onClick={() => {
                  onClose?.();
                  onEdit(recipe);
                }}
                disabled={isActionBusy}
                className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-slate-700 bg-white hover:bg-slate-100 border border-slate-200 rounded-lg transition-colors disabled:opacity-50"
              >
                <Edit2 className="w-3.5 h-3.5 text-slate-600" aria-hidden="true" />
                <span>Edit Draft</span>
              </button>
            )}

            {canAuthor && typeof onCreateVersion === 'function' && (
              <button
                type="button"
                onClick={() => onCreateVersion(recipe)}
                disabled={isActionBusy}
                className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-indigo-700 bg-indigo-50 hover:bg-indigo-100 border border-indigo-200 rounded-lg transition-colors disabled:opacity-50"
              >
                <Copy className="w-3.5 h-3.5 text-indigo-600" aria-hidden="true" />
                <span>{isObsolete ? 'Clone Revision' : 'New Version'}</span>
              </button>
            )}
          </div>

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
