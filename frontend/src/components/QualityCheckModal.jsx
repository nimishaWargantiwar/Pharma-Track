import React, { useState, useEffect, useId, useRef } from 'react';
import {
  FlaskConical,
  RefreshCw,
  CheckCircle2,
  User,
  FileText,
  Lock,
  Clock,
} from 'lucide-react';
import { createQualityCheck, correctQualityCheck } from '../services/qualityApi';
import { evaluateQcConsistency } from '../utils/qcSpecificationValidator';
import {
  normalizeQcCheckedAtForApi,
  QA_RELEASE_POLICY_COPY,
  validateQcCheckedAt,
} from '../utils/qualityPolicyUtils';
import { useAuth } from '../context/AuthContext.jsx';
import { InlineAlert, ModalDialog } from './ui/index.js';

const TEST_NAME_MAX = 150;
const SPECIFICATION_MAX = 255;
const MEASURED_VALUE_MAX = 150;
const UNIT_MAX = 50;
const REMARKS_MAX = 2000;
const REASON_MAX = 1000;

export default function QualityCheckModal({
  isOpen,
  mode = 'create', // 'create' | 'correct'
  batchId,
  batchNumber,
  existingCheck = null,
  onClose,
  onSuccess,
}) {
  const { actorDisplay } = useAuth();
  const isCorrectionMode = mode === 'correct' && Boolean(existingCheck?.id);
  const uid = useId();
  const testNameRef = useRef(null);

  const [testName, setTestName] = useState('');
  const [specification, setSpecification] = useState('');
  const [measuredValue, setMeasuredValue] = useState('');
  const [unit, setUnit] = useState('');
  const [result, setResult] = useState('');
  const [required, setRequired] = useState(true);
  const [checkedAt, setCheckedAt] = useState('');
  const [remarks, setRemarks] = useState('');
  const [correctionReason, setCorrectionReason] = useState('');

  const [fieldErrors, setFieldErrors] = useState({});
  const [serverError, setServerError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const isSubmittingRef = useRef(false);

  useEffect(() => {
    if (!isOpen) return;

    if (isCorrectionMode && existingCheck) {
      setTestName(existingCheck.testName || '');
      setSpecification(existingCheck.specification || '');
      setMeasuredValue(existingCheck.measuredValue || '');
      setUnit(existingCheck.unit || '');
      setResult(
        existingCheck.result === 'PENDING' ? '' : existingCheck.result || ''
      );
      setRequired(existingCheck.required !== false);
      setCheckedAt('');
      setRemarks(existingCheck.remarks || '');
      setCorrectionReason('');
    } else {
      setTestName('');
      setSpecification('');
      setMeasuredValue('');
      setUnit('');
      setResult('');
      setRequired(true);
      setCheckedAt('');
      setRemarks('');
      setCorrectionReason('');
    }

    setFieldErrors({});
    setServerError('');
    setIsSubmitting(false);
    isSubmittingRef.current = false;
  }, [isOpen, isCorrectionMode, existingCheck]);

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
    const trimmedTestName = testName.trim();
    const trimmedSpec = specification.trim();
    const trimmedMeasured = measuredValue.trim();
    const trimmedUnit = unit.trim();
    const trimmedRemarks = remarks.trim();
    const trimmedReason = correctionReason.trim();

    if (!trimmedTestName) {
      errors.testName = 'Test name is mandatory';
    } else if (trimmedTestName.length > TEST_NAME_MAX) {
      errors.testName = `Test name must not exceed ${TEST_NAME_MAX} characters`;
    }

    if (!trimmedSpec) {
      errors.specification = 'Specification is mandatory';
    } else if (trimmedSpec.length > SPECIFICATION_MAX) {
      errors.specification = `Specification must not exceed ${SPECIFICATION_MAX} characters`;
    }

    if (!trimmedUnit) {
      errors.unit = 'Unit is mandatory';
    } else if (trimmedUnit.length > UNIT_MAX) {
      errors.unit = `Unit must not exceed ${UNIT_MAX} characters`;
    }

    if (!result || !['PENDING', 'PASS', 'FAIL'].includes(result)) {
      errors.result = 'Please select a valid QC result (PASS, FAIL, or PENDING)';
    }

    if ((result === 'PASS' || result === 'FAIL') && !trimmedMeasured) {
      errors.measuredValue = `Measured value is mandatory when result is ${result}`;
    } else if (trimmedMeasured.length > MEASURED_VALUE_MAX) {
      errors.measuredValue = `Measured value must not exceed ${MEASURED_VALUE_MAX} characters`;
    } else if (
      trimmedMeasured &&
      ['nan', 'infinity', '-infinity', '+infinity'].includes(trimmedMeasured.toLowerCase())
    ) {
      errors.measuredValue = 'Measured value contains an invalid value';
    } else if ((result === 'PASS' || result === 'FAIL') && trimmedSpec && trimmedMeasured) {
      const consistency = evaluateQcConsistency(trimmedSpec, trimmedMeasured, result);
      if (!consistency.valid && consistency.errorMessage) {
        const targetField = consistency.errorField || 'result';
        errors[targetField] = consistency.errorMessage;
      }
    }

    if (trimmedRemarks.length > REMARKS_MAX) {
      errors.remarks = `Remarks must not exceed ${REMARKS_MAX} characters`;
    }

    if (isCorrectionMode) {
      if (!trimmedReason) {
        errors.correctionReason = 'Correction reason is mandatory for audit logging';
      } else if (trimmedReason.length > REASON_MAX) {
        errors.correctionReason = `Correction reason must not exceed ${REASON_MAX} characters`;
      }
    } else {
      const checkedAtError = validateQcCheckedAt(checkedAt);
      if (checkedAtError) {
        errors.checkedAt = checkedAtError;
      }
    }

    const formattedCheckedAt = normalizeQcCheckedAtForApi(checkedAt);

    const payload = isCorrectionMode
      ? {
          testName: trimmedTestName,
          specification: trimmedSpec,
          measuredValue: trimmedMeasured || null,
          unit: trimmedUnit,
          result,
          required: Boolean(required),
          remarks: trimmedRemarks || null,
          correctionReason: trimmedReason,
        }
      : {
          testName: trimmedTestName,
          specification: trimmedSpec,
          measuredValue: trimmedMeasured || null,
          unit: trimmedUnit,
          result,
          required: Boolean(required),
          checkedAt: formattedCheckedAt,
          remarks: trimmedRemarks || null,
        };

    return {
      isValid: Object.keys(errors).length === 0,
      errors,
      payload,
    };
  };

  const handleSubmit = async (e) => {
    e?.preventDefault?.();
    if (isSubmittingRef.current || isSubmitting) return;

    setServerError('');
    const { isValid, errors, payload } = validateForm();
    if (!isValid) {
      setFieldErrors(errors);
      return;
    }

    isSubmittingRef.current = true;
    setIsSubmitting(true);

    try {
      const saved = isCorrectionMode
        ? await correctQualityCheck(existingCheck.id, payload)
        : await createQualityCheck(batchId, payload);

      if (onSuccess) {
        await onSuccess(saved, isCorrectionMode);
      }
      onClose();
    } catch (err) {
      if (err.validationErrors && typeof err.validationErrors === 'object') {
        setFieldErrors((prev) => ({
          ...prev,
          ...err.validationErrors,
        }));
      }
      setServerError(err.message || 'Failed to save QC check');
    } finally {
      isSubmittingRef.current = false;
      setIsSubmitting(false);
    }
  };

  const modalTitle = isCorrectionMode
    ? existingCheck?.result === 'PENDING'
      ? 'Record Result for Pending QC Check'
      : 'Audited QC Record Correction'
    : 'Record Quality Control (QC) Check';

  const modalSubtitle = `${batchNumber || `Batch #${batchId}`}${
    isCorrectionMode && existingCheck?.id ? ` • QC #${existingCheck.id}` : ''
  }`;

  const checkedAtHelperId = `${uid}-checked-at-help`;
  const testNameErrorId = `${uid}-test-name-err`;
  const specErrorId = `${uid}-spec-err`;
  const measuredErrorId = `${uid}-measured-err`;
  const unitErrorId = `${uid}-unit-err`;
  const resultErrorId = `${uid}-result-err`;
  const checkedAtErrorId = `${uid}-checked-at-err`;
  const reasonErrorId = `${uid}-reason-err`;
  const remarksErrorId = `${uid}-remarks-err`;

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={handleSafeClose}
      preventClose={isSubmitting}
      size="2xl"
      maxHeightClass="max-h-[92vh]"
      bodyClassName="overflow-y-auto"
      headingAs="h3"
      title={modalTitle}
      subtitle={modalSubtitle}
      description="Record or update analytical Quality Control check values and outcome"
      closeButtonLabel="Close QC check modal"
      initialFocusRef={testNameRef}
      icon={
        <div
          className={`w-8 h-8 rounded-lg flex items-center justify-center text-white shadow-sm ${
            isCorrectionMode ? 'bg-amber-600' : 'bg-pharma-600'
          }`}
        >
          {isCorrectionMode ? (
            <Lock className="w-4 h-4" aria-hidden="true" />
          ) : (
            <FlaskConical className="w-4 h-4" aria-hidden="true" />
          )}
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
            className={`inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-semibold text-white rounded-lg shadow-sm transition-colors disabled:opacity-50 ${
              isCorrectionMode
                ? 'bg-amber-600 hover:bg-amber-700'
                : 'bg-pharma-600 hover:bg-pharma-700'
            }`}
          >
            {isSubmitting ? (
              <>
                <RefreshCw className="w-4 h-4 animate-spin" aria-hidden="true" />
                <span>Saving...</span>
              </>
            ) : (
              <>
                <CheckCircle2 className="w-4 h-4" aria-hidden="true" />
                <span>{isCorrectionMode ? 'Save Audited Update' : 'Save QC Check'}</span>
              </>
            )}
          </button>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate className="p-4 sm:p-6 space-y-4">
        {isCorrectionMode && (
          <InlineAlert
            variant="warning"
            compact
            icon={Lock}
            title="Audited Correction Workflow"
            message={`Original check by ${
              existingCheck?.inspectorName || 'analyst'
            } is preserved in history. Actor name, correction reason, and before/after values will be appended to the audit log.`}
          />
        )}

        {serverError && (
          <InlineAlert
            variant="error"
            compact
            role="alert"
            title="Unable to save QC check"
            message={serverError}
            onDismiss={() => setServerError('')}
            dismissLabel="Dismiss QC check error"
          />
        )}

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          {/* Test Name */}
          <div className="sm:col-span-2">
            <label
              htmlFor="qc-test-name"
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
            >
              Test Name <span className="text-rose-500">*</span>
            </label>
            <input
              ref={testNameRef}
              id="qc-test-name"
              type="text"
              value={testName}
              onChange={handleFieldChange('testName', setTestName)}
              placeholder="e.g., Assay (HPLC), Dissolution, Loss on Drying"
              disabled={isSubmitting}
              aria-invalid={Boolean(fieldErrors.testName)}
              aria-describedby={fieldErrors.testName ? testNameErrorId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
                fieldErrors.testName
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
              }`}
            />
            {fieldErrors.testName && (
              <p id={testNameErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {fieldErrors.testName}
              </p>
            )}
          </div>

          {/* Specification */}
          <div className="sm:col-span-2">
            <label
              htmlFor="qc-specification"
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
            >
              Acceptance Specification <span className="text-rose-500">*</span>
            </label>
            <input
              id="qc-specification"
              type="text"
              value={specification}
              onChange={handleFieldChange('specification', setSpecification)}
              placeholder="e.g., 95.0% – 105.0% of label claim"
              disabled={isSubmitting}
              aria-invalid={Boolean(fieldErrors.specification)}
              aria-describedby={fieldErrors.specification ? specErrorId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
                fieldErrors.specification
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
              }`}
            />
            {fieldErrors.specification && (
              <p id={specErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {fieldErrors.specification}
              </p>
            )}
          </div>

          {/* Measured Value */}
          <div>
            <label
              htmlFor="qc-measured-value"
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
            >
              Measured Value{' '}
              {(result === 'PASS' || result === 'FAIL') && (
                <span className="text-rose-500">*</span>
              )}
            </label>
            <input
              id="qc-measured-value"
              type="text"
              value={measuredValue}
              onChange={(e) => {
                setMeasuredValue(e.target.value);
                if (fieldErrors.measuredValue || fieldErrors.result) {
                  setFieldErrors((prev) => {
                    const next = { ...prev };
                    delete next.measuredValue;
                    delete next.result;
                    return next;
                  });
                }
                if (serverError) {
                  setServerError('');
                }
              }}
              placeholder={
                result === 'PENDING'
                  ? 'Optional until test completes'
                  : 'e.g., 99.4, 88.2, Conforms'
              }
              disabled={isSubmitting}
              aria-invalid={Boolean(fieldErrors.measuredValue)}
              aria-describedby={fieldErrors.measuredValue ? measuredErrorId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors font-mono tabular-nums ${
                fieldErrors.measuredValue
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
              }`}
            />
            {fieldErrors.measuredValue && (
              <p id={measuredErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {fieldErrors.measuredValue}
              </p>
            )}
          </div>

          {/* Unit */}
          <div>
            <label
              htmlFor="qc-unit"
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
            >
              Unit <span className="text-rose-500">*</span>
            </label>
            <input
              id="qc-unit"
              type="text"
              value={unit}
              onChange={handleFieldChange('unit', setUnit)}
              placeholder="e.g., %, mg, pH, Visual"
              disabled={isSubmitting}
              aria-invalid={Boolean(fieldErrors.unit)}
              aria-describedby={fieldErrors.unit ? unitErrorId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
                fieldErrors.unit
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
              }`}
            />
            {fieldErrors.unit && (
              <p id={unitErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {fieldErrors.unit}
              </p>
            )}
          </div>

          {/* Live Acceptance Specification Evaluation Preview */}
          {specification.trim() && measuredValue.trim() && result !== 'PENDING' && (() => {
            const preview = evaluateQcConsistency(specification, measuredValue, result);
            if (!preview.isNumericSpec) {
              return (
                <div className="sm:col-span-2 px-3 py-2 rounded-lg bg-slate-50 border border-slate-200 text-xs text-slate-600">
                  Qualitative specification detected — numeric limit comparison is not applicable.
                </div>
              );
            }
            if (preview.numericMeasured === null) {
              return (
                <InlineAlert
                  variant="warning"
                  compact
                  className="sm:col-span-2"
                  role="status"
                  message={`Numeric acceptance limit detected (${preview.rule.describe}) — enter a numeric measured value to evaluate PASS/FAIL.`}
                />
              );
            }
            return (
              <InlineAlert
                variant={preview.satisfied ? 'success' : 'error'}
                compact
                role="status"
                className="sm:col-span-2"
                message={
                  preview.satisfied
                    ? `Measured value ${preview.numericMeasured} meets acceptance limit (${preview.rule.describe}) — valid outcome: PASS.`
                    : `Measured value ${preview.numericMeasured} is outside acceptance limit (${preview.rule.describe}) — valid outcome: FAIL.`
                }
              />
            );
          })()}

          {/* Result */}
          <div className="sm:col-span-2">
            <label
              htmlFor="qc-result"
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
            >
              QC Result <span className="text-rose-500">*</span>
            </label>
            <select
              id="qc-result"
              value={result}
              onChange={(e) => {
                setResult(e.target.value);
                if (fieldErrors.result || fieldErrors.measuredValue) {
                  setFieldErrors((prev) => {
                    const next = { ...prev };
                    delete next.result;
                    delete next.measuredValue;
                    return next;
                  });
                }
                if (serverError) {
                  setServerError('');
                }
              }}
              disabled={isSubmitting}
              aria-invalid={Boolean(fieldErrors.result)}
              aria-describedby={fieldErrors.result ? resultErrorId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors bg-white ${
                fieldErrors.result
                  ? 'border-rose-300 focus:ring-rose-200'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
              }`}
            >
              <option value="">-- Select QC Result --</option>
              <option value="PASS">PASS</option>
              <option value="FAIL">FAIL</option>
              <option value="PENDING">PENDING</option>
            </select>
            {fieldErrors.result && (
              <p id={resultErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {fieldErrors.result}
              </p>
            )}
          </div>

          {/* Required vs Optional Classification */}
          <div className="sm:col-span-2">
            <div
              className={`p-3 rounded-lg border transition-colors ${
                required
                  ? 'bg-pharma-50/70 border-pharma-200'
                  : 'bg-slate-50 border-slate-200'
              }`}
            >
              <label
                htmlFor="qc-required"
                className="flex items-start space-x-2.5 cursor-pointer select-none"
              >
                <input
                  id="qc-required"
                  type="checkbox"
                  checked={required}
                  onChange={(e) => setRequired(e.target.checked)}
                  disabled={isSubmitting}
                  className="mt-0.5 w-4 h-4 rounded border-slate-300 text-pharma-600 focus:ring-pharma-500"
                />
                <div className="flex-1 text-xs">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold uppercase tracking-wider text-slate-800">
                      {QA_RELEASE_POLICY_COPY.requiredLabel}
                    </span>
                    <span
                      className={`px-1.5 py-0.5 rounded text-[10px] font-bold uppercase border ${
                        required
                          ? 'bg-pharma-100 text-pharma-800 border-pharma-300'
                          : 'bg-slate-200 text-slate-700 border-slate-300'
                      }`}
                    >
                      {required ? 'Required' : 'Optional'}
                    </span>
                  </div>
                  <p className="mt-1 text-slate-600 leading-relaxed">
                    {required
                      ? QA_RELEASE_POLICY_COPY.requiredHelp
                      : QA_RELEASE_POLICY_COPY.optionalHelp}
                  </p>
                </div>
              </label>
            </div>
          </div>

          {/* Server-Derived Actor Attribution + CheckedAt (Create mode) OR Reason (Correction mode) */}
          {!isCorrectionMode ? (
            <>
              <div>
                <span className="flex items-center space-x-1 text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                  <User className="w-3.5 h-3.5 text-slate-400" aria-hidden="true" />
                  <span>Inspector (Authenticated Session)</span>
                </span>
                <div className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 bg-slate-50 text-slate-800 font-medium">
                  {actorDisplay}
                </div>
              </div>

              <div>
                <label
                  htmlFor="qc-checked-at"
                  className="flex items-center space-x-1 text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
                >
                  <Clock className="w-3.5 h-3.5 text-slate-400" aria-hidden="true" />
                  <span>Check Timestamp — IST (Asia/Kolkata) (Optional)</span>
                </label>
                <input
                  id="qc-checked-at"
                  type="datetime-local"
                  value={checkedAt}
                  onChange={handleFieldChange('checkedAt', setCheckedAt)}
                  disabled={isSubmitting}
                  aria-invalid={Boolean(fieldErrors.checkedAt)}
                  aria-describedby={fieldErrors.checkedAt ? checkedAtErrorId : checkedAtHelperId}
                  className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors tabular-nums ${
                    fieldErrors.checkedAt
                      ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                      : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
                  }`}
                />
                {fieldErrors.checkedAt ? (
                  <p id={checkedAtErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                    {fieldErrors.checkedAt}
                  </p>
                ) : (
                  <p id={checkedAtHelperId} className="mt-1 text-[11px] text-slate-500">
                    Leave blank to stamp current IST (Asia/Kolkata) server time automatically.
                  </p>
                )}
              </div>
            </>
          ) : (
            <>
              <div className="sm:col-span-2 bg-slate-50 border border-slate-200 rounded-lg px-3.5 py-2.5 flex items-center justify-between gap-2">
                <div className="flex items-center space-x-2 text-xs text-slate-600">
                  <User className="w-3.5 h-3.5 text-slate-400 shrink-0" aria-hidden="true" />
                  <span className="font-semibold uppercase tracking-wider text-slate-500">
                    Actor / Corrected By
                  </span>
                </div>
                <span className="text-xs font-semibold text-slate-800">{actorDisplay}</span>
              </div>

              <div className="sm:col-span-2">
                <label
                  htmlFor="qc-correction-reason"
                  className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
                >
                  Reason for Change <span className="text-rose-500">*</span>
                </label>
                <textarea
                  id="qc-correction-reason"
                  rows={2}
                  value={correctionReason}
                  onChange={handleFieldChange('correctionReason', setCorrectionReason)}
                  placeholder="Document why this QC record is being updated or completed..."
                  disabled={isSubmitting}
                  aria-invalid={Boolean(fieldErrors.correctionReason)}
                  aria-describedby={fieldErrors.correctionReason ? reasonErrorId : undefined}
                  className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
                    fieldErrors.correctionReason
                      ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                      : 'border-slate-300 focus:ring-amber-200 focus:border-amber-500 bg-white'
                  }`}
                />
                {fieldErrors.correctionReason && (
                  <p id={reasonErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                    {fieldErrors.correctionReason}
                  </p>
                )}
              </div>
            </>
          )}

          {/* Remarks */}
          <div className="sm:col-span-2">
            <label
              htmlFor="qc-remarks"
              className="flex items-center space-x-1 text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
            >
              <FileText className="w-3.5 h-3.5 text-slate-400" aria-hidden="true" />
              <span>Remarks / Analytical Notes (Optional)</span>
            </label>
            <textarea
              id="qc-remarks"
              rows={2}
              value={remarks}
              onChange={handleFieldChange('remarks', setRemarks)}
              placeholder="Instrument reference, sample dilution notes, or analytical observations..."
              disabled={isSubmitting}
              aria-invalid={Boolean(fieldErrors.remarks)}
              aria-describedby={fieldErrors.remarks ? remarksErrorId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
                fieldErrors.remarks
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
              }`}
            />
            {fieldErrors.remarks && (
              <p id={remarksErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {fieldErrors.remarks}
              </p>
            )}
          </div>
        </div>
      </form>
    </ModalDialog>
  );
}
