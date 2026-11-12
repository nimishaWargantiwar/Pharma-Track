import React, { useState, useEffect, useId, useRef } from 'react';
import {
  CheckCircle2,
  RefreshCw,
  ClipboardCheck,
  User,
  FileText,
} from 'lucide-react';
import { InlineAlert, ModalDialog } from './ui/index.js';
import { completeExecutionStep } from '../services/productionExecutionApi';
import { useAuth } from '../context/AuthContext.jsx';

const ACTUAL_VALUE_MAX = 255;
const OBSERVATIONS_MAX = 2000;

export default function CompleteExecutionStepModal({
  isOpen,
  batchId,
  batchNumber,
  step,
  onClose,
  onSuccess,
}) {
  const { actorDisplay } = useAuth();
  const actualValueRef = useRef(null);
  const actualValueErrorId = useId();
  const actualValueHelpId = useId();
  const observationsErrorId = useId();

  const [actualValue, setActualValue] = useState('');
  const [observations, setObservations] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});
  const [serverError, setServerError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const isSubmittingRef = useRef(false);

  useEffect(() => {
    if (isOpen) {
      setActualValue('');
      setObservations('');
      setFieldErrors({});
      setServerError('');
      setIsSubmitting(false);
      isSubmittingRef.current = false;
    }
  }, [isOpen, step?.id]);

  if (!isOpen || !step) return null;

  const trimmedActualLength = actualValue.trim().length;
  const trimmedObservationsLength = observations.trim().length;

  const handleFieldChange = (field, setter) => (e) => {
    setter(e.target.value);
    if (fieldErrors[field]) {
      setFieldErrors((prev) => {
        const next = { ...prev };
        delete next[field];
        return next;
      });
    }
    if (serverError) {
      setServerError('');
    }
  };

  const handleSafeClose = () => {
    if (isSubmitting || isSubmittingRef.current) return;
    onClose();
  };

  const validateForm = () => {
    const errors = {};
    const trimmedActual = actualValue.trim();
    const trimmedObs = observations.trim();

    if (!trimmedActual) {
      errors.actualValue = 'Actual value is mandatory';
    } else if (trimmedActual.length > ACTUAL_VALUE_MAX) {
      errors.actualValue = `Actual value must not exceed ${ACTUAL_VALUE_MAX} characters`;
    }

    if (trimmedObs.length > OBSERVATIONS_MAX) {
      errors.observations = `Observations must not exceed ${OBSERVATIONS_MAX} characters`;
    }

    return {
      isValid: Object.keys(errors).length === 0,
      errors,
      payload: {
        actualValue: trimmedActual,
        observations: trimmedObs || null,
      },
    };
  };

  const handleSubmit = async (e) => {
    e?.preventDefault?.();
    if (isSubmittingRef.current || isSubmitting || !batchId || !step?.id) return;

    setServerError('');
    const { isValid, errors, payload } = validateForm();
    if (!isValid) {
      setFieldErrors(errors);
      return;
    }

    isSubmittingRef.current = true;
    setIsSubmitting(true);

    try {
      const completedStep = await completeExecutionStep(batchId, step.id, payload);
      if (onSuccess) {
        await onSuccess(completedStep);
      }
      onClose();
    } catch (err) {
      if (err.validationErrors && typeof err.validationErrors === 'object') {
        setFieldErrors((prev) => ({
          ...prev,
          ...err.validationErrors,
        }));
      }
      setServerError(err.message || 'Failed to complete execution step');
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
      initialFocusRef={actualValueRef}
      size="lg"
      zIndexClass="z-50"
      maxHeightClass="max-h-[90vh]"
      bodyClassName="overflow-y-auto"
      title={`Complete Execution Step ${step.stepNumber}`}
      subtitle={batchNumber || `Batch #${batchId}`}
      closeButtonLabel="Close modal"
      icon={
        <div className="w-8 h-8 rounded-lg bg-emerald-600 flex items-center justify-center text-white shadow-sm">
          <ClipboardCheck className="w-4 h-4" aria-hidden="true" />
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
                <span>Completing Step...</span>
              </>
            ) : (
              <>
                <CheckCircle2 className="w-4 h-4" aria-hidden="true" />
                <span>Complete Step</span>
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
            title="Unable to complete execution step"
            message={serverError}
            onDismiss={() => setServerError('')}
            dismissLabel="Dismiss step completion error"
          />
        )}

        {/* Step Reference Card */}
        <div className="bg-slate-50 border border-slate-200 rounded-lg p-3.5 space-y-2">
          <div className="flex items-center space-x-2">
            <span className="w-6 h-6 rounded-full bg-amber-100 text-amber-800 border border-amber-200 text-xs font-bold flex items-center justify-center flex-shrink-0 tabular-nums">
              {step.stepNumber}
            </span>
            <span className="text-sm font-semibold text-slate-900">
              {step.operationName || '—'}
            </span>
          </div>
          {step.instructions && (
            <p className="text-xs text-slate-600 whitespace-pre-line leading-relaxed max-h-28 overflow-y-auto pl-8">
              {step.instructions}
            </p>
          )}
        </div>

        {/* Actual Value (Required) */}
        <div>
          <div className="flex items-center justify-between mb-1.5">
            <label
              htmlFor="exec-step-actual-value"
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider"
            >
              Actual Value <span className="text-rose-500">*</span>
            </label>
            <span
              className={`text-[11px] font-mono tabular-nums ${
                trimmedActualLength > ACTUAL_VALUE_MAX
                  ? 'text-rose-600 font-semibold'
                  : 'text-slate-500'
              }`}
            >
              {trimmedActualLength}/{ACTUAL_VALUE_MAX}
            </span>
          </div>
          <input
            ref={actualValueRef}
            id="exec-step-actual-value"
            type="text"
            value={actualValue}
            onChange={handleFieldChange('actualValue', setActualValue)}
            placeholder="e.g., 45.2 °C for 30 min, 250.0 g verified, Passed"
            disabled={isSubmitting}
            aria-invalid={Boolean(fieldErrors.actualValue)}
            aria-describedby={
              fieldErrors.actualValue ? actualValueErrorId : actualValueHelpId
            }
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
              fieldErrors.actualValue
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-emerald-200 focus:border-emerald-500 bg-white'
            }`}
          />
          {fieldErrors.actualValue ? (
            <p
              id={actualValueErrorId}
              role="alert"
              className="mt-1.5 text-xs font-medium text-rose-600"
            >
              {fieldErrors.actualValue}
            </p>
          ) : (
            <p id={actualValueHelpId} className="mt-1.5 text-[11px] text-slate-500">
              Record the verified parameter measurement, weight, or operation result.
            </p>
          )}
        </div>

        {/* Executed By (Server-Derived Read-Only Attribution) */}
        <div className="bg-slate-50 border border-slate-200 rounded-lg px-3.5 py-2.5 flex items-center justify-between gap-2">
          <div className="flex items-center space-x-2 text-xs text-slate-600">
            <User className="w-3.5 h-3.5 text-slate-400 shrink-0" aria-hidden="true" />
            <span className="font-semibold uppercase tracking-wider text-slate-500">
              Executed By
            </span>
          </div>
          <span
            data-testid="authenticated-step-operator-display"
            className="text-xs font-semibold text-slate-800"
          >
            {actorDisplay}
          </span>
        </div>

        {/* Observations (Optional) */}
        <div>
          <div className="flex items-center justify-between mb-1.5">
            <label
              htmlFor="exec-step-observations"
              className="flex items-center space-x-1 text-xs font-semibold text-slate-700 uppercase tracking-wider"
            >
              <FileText className="w-3.5 h-3.5 text-slate-400" aria-hidden="true" />
              <span>Observations</span>
            </label>
            <span
              className={`text-[11px] font-mono tabular-nums ${
                trimmedObservationsLength > OBSERVATIONS_MAX
                  ? 'text-rose-600 font-semibold'
                  : 'text-slate-500'
              }`}
            >
              {trimmedObservationsLength}/{OBSERVATIONS_MAX}
            </span>
          </div>
          <textarea
            id="exec-step-observations"
            rows={3}
            value={observations}
            onChange={handleFieldChange('observations', setObservations)}
            placeholder="Optional process observations, equipment notes, or remarks..."
            disabled={isSubmitting}
            aria-invalid={Boolean(fieldErrors.observations)}
            aria-describedby={fieldErrors.observations ? observationsErrorId : undefined}
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
              fieldErrors.observations
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-emerald-200 focus:border-emerald-500 bg-white'
            }`}
          />
          {fieldErrors.observations && (
            <p
              id={observationsErrorId}
              role="alert"
              className="mt-1.5 text-xs font-medium text-rose-600"
            >
              {fieldErrors.observations}
            </p>
          )}
        </div>
      </form>
    </ModalDialog>
  );
}
