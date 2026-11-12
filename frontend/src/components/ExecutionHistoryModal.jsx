import React, { useEffect, useId, useRef } from 'react';
import { X, ClipboardCheck, Lock, ShieldCheck } from 'lucide-react';
import ExecutionWorkspace from './ExecutionWorkspace';
import { useNavigation } from '../context/NavigationContext.jsx';

export default function ExecutionHistoryModal({
  isOpen,
  batch,
  batchContext = null,
  fromQualityDossier = false,
  onClose,
}) {
  const { navigateToTab } = useNavigation();
  const titleId = useId();
  const drawerRef = useRef(null);
  const previousFocusRef = useRef(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!isOpen || !batch?.id || typeof document === 'undefined') {
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
          event.stopPropagation?.();
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
  }, [isOpen, batch?.id]);

  if (!isOpen || !batch?.id) return null;

  const mergedContext = batchContext
    ? { ...batchContext, fromQualityDossier }
    : { ...batch, fromQualityDossier };

  return (
    <div className="fixed inset-0 z-[45] flex justify-end">
      {/* Click-outside scrim */}
      <div
        aria-hidden="true"
        onClick={onClose}
        className="fixed inset-0 bg-slate-900/45 backdrop-blur-sm transition-opacity"
      />

      {/* In-tree non-modal accessible eBR read-only drawer */}
      <aside
        ref={drawerRef}
        role="region"
        aria-labelledby={titleId}
        tabIndex={-1}
        data-ebr-drawer="true"
        data-testid="execution-history-drawer"
        className="relative z-10 w-full max-w-5xl bg-white shadow-2xl border-l border-slate-200 flex flex-col h-full focus:outline-none"
      >
        {/* Header */}
        <div className="px-4 sm:px-6 py-4 bg-slate-50 border-b border-slate-200 flex items-center justify-between gap-3 flex-shrink-0">
          <div className="flex items-center space-x-3 min-w-0">
            <div className="w-9 h-9 rounded-lg bg-pharma-800 flex items-center justify-center text-white shadow-sm shrink-0">
              <ClipboardCheck className="w-5 h-5" aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h3
                  id={titleId}
                  className="text-base sm:text-lg font-bold text-slate-900 font-mono tracking-tight break-all tabular-nums"
                >
                  {batch.batchNumber || `Batch #${batch.id}`}
                </h3>
                <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold bg-slate-200 text-slate-800 border border-slate-300">
                  <Lock className="w-3 h-3 mr-1" aria-hidden="true" />
                  Read-Only History
                </span>
              </div>
              <p className="text-xs text-slate-500 mt-0.5 truncate">
                Electronic Batch Execution History &amp; Snapshotted Step Records
              </p>
            </div>
          </div>

          <button
            type="button"
            onClick={onClose}
            aria-label="Close execution history modal"
            className="text-slate-500 hover:text-slate-700 p-1.5 rounded-lg hover:bg-slate-100 transition-colors shrink-0"
          >
            <X className="w-5 h-5" aria-hidden="true" />
          </button>
        </div>

        {/* Scrollable Body */}
        <div className="p-4 sm:p-6 overflow-y-auto flex-1 bg-slate-50/50">
          <ExecutionWorkspace
            batchId={batch.id}
            batchContext={mergedContext}
            readOnly={true}
            readOnlyReason="history"
          />
        </div>

        {/* Footer */}
        <div className="px-4 sm:px-6 py-3.5 bg-slate-50 border-t border-slate-200 flex flex-wrap items-center justify-between gap-3 flex-shrink-0">
          <div className="flex flex-wrap items-center gap-2">
            {!fromQualityDossier && batch.status === 'COMPLETED' && (
              <button
                type="button"
                onClick={() => {
                  onClose?.();
                  navigateToTab('quality', { batchId: batch.id });
                }}
                className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg transition-colors"
              >
                <ShieldCheck className="w-3.5 h-3.5" aria-hidden="true" />
                <span>Open Quality Dossier</span>
              </button>
            )}
          </div>

          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-100 border border-slate-300 rounded-lg shadow-sm transition-colors ml-auto"
          >
            Close
          </button>
        </div>
      </aside>
    </div>
  );
}
