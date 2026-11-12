import React, { useState, useEffect, useId, useMemo, useRef } from 'react';
import { CheckCircle2, RefreshCw, Layers } from 'lucide-react';
import { InlineAlert, ModalDialog } from './ui/index.js';
import { updateBatchStatus } from '../services/orderApi';

export default function BatchYieldModal({ isOpen, batch, onClose, onSuccess }) {
  const yieldInputRef = useRef(null);
  const errorId = useId();
  const helperId = useId();

  const [actualYield, setActualYield] = useState('');
  const [validationError, setValidationError] = useState('');
  const [serverError, setServerError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const isSubmittingRef = useRef(false);

  useEffect(() => {
    if (isOpen) {
      setActualYield('');
      setValidationError('');
      setServerError('');
      setIsSubmitting(false);
      isSubmittingRef.current = false;
    }
  }, [isOpen, batch?.id]);

  const recoveryPercent = useMemo(() => {
    const trimmed = String(actualYield).trim();
    const numeric = Number(trimmed);
    const planned = Number(batch?.batchSize);
    if (!trimmed || !Number.isFinite(numeric) || numeric <= 0 || !Number.isFinite(planned) || planned <= 0) {
      return null;
    }
    return ((numeric / planned) * 100).toFixed(1);
  }, [actualYield, batch?.batchSize]);

  if (!isOpen || !batch) return null;

  const handleYieldChange = (e) => {
    setActualYield(e.target.value);
    if (validationError) {
      setValidationError('');
    }
    if (serverError) {
      setServerError('');
    }
  };

  const handleSafeClose = () => {
    if (isSubmitting || isSubmittingRef.current) return;
    onClose();
  };

  const handleSubmit = async (e) => {
    e?.preventDefault?.();
    if (isSubmittingRef.current || isSubmitting || !batch?.id) return;

    setServerError('');

    const trimmed = String(actualYield).trim();
    if (!trimmed) {
      setValidationError('Actual yield is required');
      return;
    }

    const numericYield = Number(trimmed);
    if (!Number.isFinite(numericYield) || numericYield <= 0) {
      setValidationError('Actual yield must be a positive number greater than zero');
      return;
    }

    isSubmittingRef.current = true;
    setIsSubmitting(true);

    try {
      const updatedBatch = await updateBatchStatus(batch.id, 'COMPLETED', numericYield);
      if (onSuccess) {
        await onSuccess(updatedBatch);
      }
      onClose();
    } catch (err) {
      if (err.validationErrors?.actualYield) {
        setValidationError(err.validationErrors.actualYield);
      }
      setServerError(err.message || 'Failed to complete production batch');
    } finally {
      isSubmittingRef.current = false;
      setIsSubmitting(false);
    }
  };

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={handleSafeClose}
      preventClose={isSubmitting}
      initialFocusRef={yieldInputRef}
      size="md"
      zIndexClass="z-50"
      maxHeightClass="max-h-[90vh]"
      bodyClassName="overflow-y-auto"
      title="Complete Production Batch"
      subtitle={batch.batchNumber || '—'}
      closeButtonLabel="Close modal"
      icon={
        <div className="w-8 h-8 rounded-lg bg-emerald-600 flex items-center justify-center text-white shadow-sm">
          <CheckCircle2 className="w-4 h-4" aria-hidden="true" />
        </div>
      }
      footer={
        <>
          <button
            type="button"
            onClick={handleSafeClose}
            disabled={isSubmitting}
            className="px-4 py-2 text-sm font-medium text-slate-700 hover:text-slate-900 bg-white hover:bg-slate-50 border border-slate-300 rounded-lg shadow-sm transition-colors disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={isSubmitting}
            className="inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-semibold text-white bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed rounded-lg shadow-sm transition-colors"
          >
            {isSubmitting ? (
              <>
                <RefreshCw className="w-4 h-4 animate-spin" aria-hidden="true" />
                <span>Completing Batch...</span>
              </>
            ) : (
              <>
                <CheckCircle2 className="w-4 h-4" aria-hidden="true" />
                <span>Complete Batch</span>
              </>
            )}
          </button>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate className="p-4 sm:p-6 space-y-4">
        {serverError && (
          <InlineAlert
            variant="error"
            compact
            title="Unable to complete batch"
            message={serverError}
            onDismiss={() => setServerError('')}
            dismissLabel="Dismiss batch completion error"
          />
        )}

        {/* Planned Batch Reference Card */}
        <div className="bg-slate-50 border border-slate-200 rounded-lg p-3.5 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center space-x-2 min-w-0">
            <Layers className="w-4 h-4 text-pharma-600 flex-shrink-0" aria-hidden="true" />
            <div className="min-w-0">
              <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider block">
                Batch Number
              </span>
              <span className="text-xs font-mono font-semibold text-slate-900 truncate block tabular-nums">
                {batch.batchNumber || '—'}
              </span>
            </div>
          </div>
          <div className="text-right shrink-0">
            <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider block">
              Planned Batch Size
            </span>
            <span className="text-sm font-bold text-slate-900 tabular-nums">
              {batch.batchSize != null
                ? `${batch.batchSize}${batch.batchUnit ? ` ${batch.batchUnit}` : ''}`
                : '—'}
            </span>
          </div>
        </div>

        {/* Actual Yield Input */}
        <div>
          <div className="flex items-center justify-between gap-2 mb-1.5">
            <label
              htmlFor="batch-actual-yield"
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider"
            >
              Actual Yield <span className="text-rose-500">*</span>
            </label>
            {recoveryPercent !== null && (
              <span className="text-[11px] font-semibold text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200 tabular-nums">
                {recoveryPercent}% yield recovery
              </span>
            )}
          </div>
          <div className="flex rounded-lg shadow-sm">
            <input
              ref={yieldInputRef}
              id="batch-actual-yield"
              type="number"
              step="any"
              min="0"
              value={actualYield}
              onChange={handleYieldChange}
              placeholder="Enter measured physical yield"
              disabled={isSubmitting}
              aria-invalid={Boolean(validationError)}
              aria-describedby={validationError ? errorId : helperId}
              className={`flex-1 px-3 py-2 text-sm rounded-l-lg border border-r-0 focus:outline-none focus:ring-2 transition-colors font-mono tabular-nums ${
                validationError
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-emerald-200 focus:border-emerald-500 bg-white'
              }`}
            />
            <span className="inline-flex items-center px-3.5 bg-slate-100 border border-l-0 border-slate-300 rounded-r-lg text-xs font-semibold text-slate-700">
              {batch.batchUnit || '—'}
            </span>
          </div>
          {validationError ? (
            <p id={errorId} role="alert" className="mt-1.5 text-xs font-medium text-rose-600">
              {validationError}
            </p>
          ) : (
            <p id={helperId} className="mt-1.5 text-[11px] text-slate-500">
              Record the actual verified output quantity for this batch. Must be greater than zero.
            </p>
          )}
        </div>
      </form>
    </ModalDialog>
  );
}
