import React, { useState, useEffect, useId, useRef } from 'react';
import {
  ShieldCheck,
  AlertTriangle,
  CheckCircle2,
  PauseCircle,
  XCircle,
  RefreshCw,
  User,
  FileText,
} from 'lucide-react';
import { recordQaDisposition } from '../services/qualityApi';
import {
  assessBatchReleaseEligibility,
  QA_RELEASE_POLICY_COPY,
} from '../utils/qualityPolicyUtils';
import { useAuth } from '../context/AuthContext.jsx';
import { InlineAlert, ModalDialog, StatusBadge } from './ui/index.js';

const RATIONALE_MAX = 2000;

export default function QaDispositionModal({
  isOpen,
  dossier,
  initialDecision = 'RELEASED', // 'RELEASED' | 'ON_HOLD' | 'REJECTED'
  onClose,
  onSuccess,
}) {
  const { actorDisplay } = useAuth();
  const uid = useId();
  const rationaleRef = useRef(null);

  const [qaStatus, setQaStatus] = useState(initialDecision);
  const [rationale, setRationale] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});
  const [serverError, setServerError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const isSubmittingRef = useRef(false);

  useEffect(() => {
    if (!isOpen) return;
    setQaStatus(initialDecision || 'RELEASED');
    setRationale('');
    setFieldErrors({});
    setServerError('');
    setIsSubmitting(false);
    isSubmittingRef.current = false;
  }, [isOpen, initialDecision, dossier?.batchId]);

  if (!isOpen || !dossier) return null;

  const currentQaStatus = dossier.qaStatus;
  const eligibility = assessBatchReleaseEligibility(dossier);
  const releaseBlockers = eligibility.blockers;
  const openDeviationsCount = eligibility.openDeviationsCount;
  const { passed, failed, pending, required, optional, total } = eligibility.qcCounts;
  const canRelease = eligibility.isEligible;
  const canPlaceOnHold = currentQaStatus === 'PENDING_QA';
  const releaseBlockedSelected = qaStatus === 'RELEASED' && !canRelease;

  const decisionConfig = {
    RELEASED: {
      title: 'QA Batch Release',
      subtitle: 'Approve completed batch for QA release',
      IconComp: CheckCircle2,
      headerBg: 'bg-emerald-600',
      btnClass: 'bg-emerald-600 hover:bg-emerald-700 text-white',
      alertVariant: 'success',
      btnLabel: 'Approve & Release Batch',
    },
    ON_HOLD: {
      title: 'Place Batch On QA Hold',
      subtitle: 'Hold batch pending additional review or testing',
      IconComp: PauseCircle,
      headerBg: 'bg-amber-600',
      btnClass: 'bg-amber-600 hover:bg-amber-700 text-white',
      alertVariant: 'warning',
      btnLabel: 'Place Batch On Hold',
    },
    REJECTED: {
      title: 'Reject Manufacturing Batch',
      subtitle: 'Record final QA rejection decision',
      IconComp: XCircle,
      headerBg: 'bg-rose-600',
      btnClass: 'bg-rose-600 hover:bg-rose-700 text-white',
      alertVariant: 'error',
      btnLabel: 'Confirm Batch Rejection',
    },
  }[qaStatus] || {
    title: 'Record QA Disposition',
    subtitle: 'Record quality assurance decision',
    IconComp: ShieldCheck,
    headerBg: 'bg-pharma-600',
    btnClass: 'bg-pharma-600 hover:bg-pharma-700 text-white',
    alertVariant: 'info',
    btnLabel: 'Submit Disposition',
  };

  const DecisionIcon = decisionConfig.IconComp;

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
    const trimmedRationale = rationale.trim();

    if (!qaStatus || !['RELEASED', 'ON_HOLD', 'REJECTED'].includes(qaStatus)) {
      errors.qaStatus = 'Target QA disposition must be RELEASED, ON_HOLD, or REJECTED';
    }

    if (!trimmedRationale) {
      errors.rationale = 'Decision rationale is mandatory';
    } else if (trimmedRationale.length > RATIONALE_MAX) {
      errors.rationale = `Decision rationale must not exceed ${RATIONALE_MAX} characters`;
    }

    return {
      isValid: Object.keys(errors).length === 0,
      errors,
      payload: {
        qaStatus,
        rationale: trimmedRationale,
      },
    };
  };

  const handleSubmit = async (e) => {
    e?.preventDefault?.();
    if (isSubmittingRef.current || isSubmitting || releaseBlockedSelected) return;

    setServerError('');
    const { isValid, errors, payload } = validateForm();
    if (!isValid) {
      setFieldErrors(errors);
      return;
    }

    isSubmittingRef.current = true;
    setIsSubmitting(true);

    try {
      const updatedDossier = await recordQaDisposition(dossier.batchId, payload);
      if (onSuccess) {
        await onSuccess(updatedDossier, payload.qaStatus);
      }
      onClose();
    } catch (err) {
      if (err.validationErrors && typeof err.validationErrors === 'object') {
        setFieldErrors((prev) => ({
          ...prev,
          ...err.validationErrors,
        }));
      }
      setServerError(err.message || 'Failed to record QA disposition');
    } finally {
      isSubmittingRef.current = false;
      setIsSubmitting(false);
    }
  };

  const selectErrorId = `${uid}-qa-select-err`;
  const rationaleErrorId = `${uid}-qa-rationale-err`;

  return (
    <ModalDialog
      isOpen={Boolean(isOpen && dossier)}
      onClose={handleSafeClose}
      preventClose={isSubmitting}
      size="lg"
      maxHeightClass="max-h-[92vh]"
      bodyClassName="overflow-y-auto"
      headingAs="h3"
      title={decisionConfig.title}
      subtitle={`${dossier.batchNumber} • ${dossier.productName || ''}`}
      description="Record QA batch disposition decision with mandatory rationale"
      closeButtonLabel="Close QA disposition modal"
      initialFocusRef={rationaleRef}
      icon={
        <div
          className={`w-8 h-8 rounded-lg ${decisionConfig.headerBg} flex items-center justify-center text-white shadow-sm`}
        >
          <DecisionIcon className="w-4 h-4" aria-hidden="true" />
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
            disabled={isSubmitting || releaseBlockedSelected}
            className={`inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-semibold rounded-lg shadow-sm transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${decisionConfig.btnClass}`}
          >
            {isSubmitting ? (
              <>
                <RefreshCw className="w-4 h-4 animate-spin" aria-hidden="true" />
                <span>Submitting...</span>
              </>
            ) : (
              <>
                <DecisionIcon className="w-4 h-4" aria-hidden="true" />
                <span>{decisionConfig.btnLabel}</span>
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
            role="alert"
            title="QA Disposition Rejected"
            message={serverError}
            onDismiss={() => setServerError('')}
            dismissLabel="Dismiss QA disposition error"
          />
        )}

        {/* Current Manufacturing vs QA State Strip */}
        <div className="bg-slate-50 border border-slate-200 rounded-lg px-3.5 py-2.5 flex flex-wrap items-center justify-between gap-2 text-xs">
          <div className="flex items-center gap-2">
            <span className="font-semibold text-slate-600 uppercase tracking-wider text-[11px]">
              Current State:
            </span>
            <StatusBadge
              status={dossier.batchStatus || 'COMPLETED'}
              label={`Mfg: ${dossier.batchStatus || 'COMPLETED'}`}
              domain="order"
              size="xs"
            />
            <StatusBadge
              status={currentQaStatus || 'PENDING_QA'}
              label={`QA: ${currentQaStatus || 'PENDING_QA'}`}
              domain="qa"
              size="xs"
            />
          </div>
          <span className="text-[11px] text-slate-500">
            Manufacturing completion is separate from QA disposition
          </span>
        </div>

        {/* Target Disposition Selector */}
        <div>
          <label
            htmlFor="qa-disposition-select"
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
          >
            QA Disposition Decision <span className="text-rose-500">*</span>
          </label>
          <select
            id="qa-disposition-select"
            value={qaStatus}
            onChange={handleFieldChange('qaStatus', setQaStatus)}
            disabled={isSubmitting}
            aria-invalid={Boolean(fieldErrors.qaStatus)}
            aria-describedby={fieldErrors.qaStatus ? selectErrorId : undefined}
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 bg-white font-medium text-slate-800"
          >
            <option value="RELEASED">RELEASED — Approve batch for QA release</option>
            {canPlaceOnHold && (
              <option value="ON_HOLD">ON_HOLD — Place batch on QA hold</option>
            )}
            <option value="REJECTED">REJECTED — Reject batch (final disposition)</option>
          </select>
          {fieldErrors.qaStatus && (
            <p id={selectErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {fieldErrors.qaStatus}
            </p>
          )}
        </div>

        {/* Release Pre-check Banner */}
        {releaseBlockedSelected ? (
          <InlineAlert
            variant="error"
            compact
            icon={AlertTriangle}
            role="alert"
            title={QA_RELEASE_POLICY_COPY.blockedBannerTitle}
          >
            <ul className="list-disc pl-5 mt-1 space-y-0.5 text-rose-800 text-xs">
              {releaseBlockers.map((blocker, idx) => (
                <li key={idx}>{blocker}</li>
              ))}
            </ul>
            <p className="text-[11px] font-medium text-rose-800 mt-2 pt-1.5 border-t border-rose-200">
              {QA_RELEASE_POLICY_COPY.blockedPolicyNote}
            </p>
          </InlineAlert>
        ) : (
          <InlineAlert
            variant={decisionConfig.alertVariant}
            compact
            role="status"
            title={
              qaStatus === 'RELEASED'
                ? QA_RELEASE_POLICY_COPY.eligibleBannerTitle
                : decisionConfig.subtitle
            }
          >
            <p className="mt-1 text-xs tabular-nums">
              QC summary: {passed} PASS, {failed} FAIL, {pending} PENDING ({total} total &bull;{' '}
              {required} Required, {optional} Optional) &bull; Unresolved deviations:{' '}
              {openDeviationsCount}.
            </p>
            {qaStatus !== 'RELEASED' && !canRelease && (
              <p className="mt-1 text-[11px] opacity-90">
                Release blockers currently prevent RELEASED, while ON_HOLD and REJECTED remain permitted. Closing deviations never auto-releases a batch.
              </p>
            )}
          </InlineAlert>
        )}

        {/* Authenticated Reviewer Attribution (Derived Server-Side from JWT) */}
        <div className="bg-slate-50 border border-slate-200 rounded-lg px-3.5 py-2.5 flex items-center justify-between gap-2">
          <div className="flex items-center space-x-2 text-xs text-slate-600">
            <User className="w-3.5 h-3.5 text-slate-400 shrink-0" aria-hidden="true" />
            <span className="font-semibold uppercase tracking-wider text-slate-500">
              QA Reviewer
            </span>
          </div>
          <span className="text-xs font-semibold text-slate-800">{actorDisplay}</span>
        </div>

        {/* Decision Rationale */}
        <div>
          <label
            htmlFor="qa-rationale"
            className="flex items-center space-x-1 text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
          >
            <FileText className="w-3.5 h-3.5 text-slate-400" aria-hidden="true" />
            <span>
              Decision Rationale <span className="text-rose-500">*</span>
            </span>
          </label>
          <textarea
            ref={rationaleRef}
            id="qa-rationale"
            rows={3}
            value={rationale}
            onChange={handleFieldChange('rationale', setRationale)}
            placeholder="Document review findings, eBR inspection summary, and reason for disposition..."
            disabled={isSubmitting}
            aria-invalid={Boolean(fieldErrors.rationale)}
            aria-describedby={fieldErrors.rationale ? rationaleErrorId : undefined}
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
              fieldErrors.rationale
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
            }`}
          />
          {fieldErrors.rationale && (
            <p id={rationaleErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {fieldErrors.rationale}
            </p>
          )}
        </div>
      </form>
    </ModalDialog>
  );
}
