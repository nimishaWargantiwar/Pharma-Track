import React, { useState, useEffect, useId, useRef } from 'react';
import {
  AlertTriangle,
  RefreshCw,
  CheckCircle2,
  User,
  FileText,
  Clock,
  Link2,
  Search,
  Layers,
} from 'lucide-react';
import { getQualityQueue } from '../services/qualityApi';
import { getExecutionQueue } from '../services/productionExecutionApi';
import { getBatchByBatchNumber } from '../services/orderApi';
import { createDeviation, getBatchLinkableRecords } from '../services/deviationApi';
import {
  DEVIATION_CATEGORY_OPTIONS,
  DEVIATION_PRIORITY_OPTIONS,
  DEVIATION_SEVERITY_OPTIONS,
  normalizeOccurredAtForApi,
  validateDeviationOccurredAt,
} from '../utils/deviationUtils';
import { useAuth } from '../context/AuthContext.jsx';
import { formatAppShortDateTime } from '../utils/dateTimeUtils.js';
import { InlineAlert, ModalDialog } from './ui/index.js';

const TITLE_MAX = 200;
const DESCRIPTION_MAX = 3000;
const CONTAINMENT_MAX = 2000;

function formatShortTimestamp(value) {
  return formatAppShortDateTime(value, { includeYear: false, fallback: '' });
}

export default function ReportDeviationModal({
  isOpen,
  prefillData = null,
  lockBatchSelection = false,
  onClose,
  onSuccess,
}) {
  const { actorDisplay } = useAuth();
  const uid = useId();
  const batchSelectRef = useRef(null);
  const titleInputRef = useRef(null);

  const [batchOptions, setBatchOptions] = useState([]);
  const [isLoadingBatches, setIsLoadingBatches] = useState(false);
  const [batchLoadWarning, setBatchLoadWarning] = useState('');
  const [batchLookupNumber, setBatchLookupNumber] = useState('');
  const [isLookingUpBatch, setIsLookingUpBatch] = useState(false);
  const [batchLookupError, setBatchLookupError] = useState('');

  const [batchId, setBatchId] = useState('');
  const [qualityCheckId, setQualityCheckId] = useState('');
  const [executionStepId, setExecutionStepId] = useState('');
  const [sourceAuditLogId, setSourceAuditLogId] = useState('');

  const [linkableRecords, setLinkableRecords] = useState(null);
  const [isLoadingLinkables, setIsLoadingLinkables] = useState(false);
  const [linkablesError, setLinkablesError] = useState('');

  const [title, setTitle] = useState('');
  const [category, setCategory] = useState('');
  const [severity, setSeverity] = useState('MAJOR');
  const [priority, setPriority] = useState('HIGH');
  const [description, setDescription] = useState('');
  const [immediateContainment, setImmediateContainment] = useState('');
  const [occurredAt, setOccurredAt] = useState('');

  const [fieldErrors, setFieldErrors] = useState({});
  const [serverError, setServerError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const isSubmittingRef = useRef(false);

  // Initialize fields on open / prefillData changes
  useEffect(() => {
    if (!isOpen) return;

    const nextBatchId = prefillData?.batchId ? String(prefillData.batchId) : '';
    setBatchId(nextBatchId);
    setQualityCheckId(prefillData?.qualityCheckId ? String(prefillData.qualityCheckId) : '');
    setExecutionStepId(prefillData?.executionStepId ? String(prefillData.executionStepId) : '');
    setSourceAuditLogId(prefillData?.sourceAuditLogId ? String(prefillData.sourceAuditLogId) : '');
    setTitle(prefillData?.title || '');
    setCategory(prefillData?.category || '');
    setSeverity(prefillData?.severity || 'MAJOR');
    setPriority(prefillData?.priority || 'HIGH');
    setDescription(prefillData?.description || '');
    setImmediateContainment(prefillData?.immediateContainment || '');
    setOccurredAt('');

    setBatchLookupNumber('');
    setBatchLookupError('');
    setBatchLoadWarning('');
    setLinkableRecords(null);
    setLinkablesError('');
    setFieldErrors({});
    setServerError('');
    setIsSubmitting(false);
    isSubmittingRef.current = false;
  }, [isOpen, prefillData]);

  // Load eligible IN_PROGRESS & COMPLETED batches when opened standalone
  useEffect(() => {
    if (!isOpen || lockBatchSelection) return;

    let cancelled = false;
    const loadBatches = async () => {
      setIsLoadingBatches(true);
      setBatchLoadWarning('');
      try {
        const [execRes, qaRes] = await Promise.allSettled([
          getExecutionQueue(),
          getQualityQueue(),
        ]);

        if (cancelled) return;

        const mergedMap = new Map();
        if (execRes.status === 'fulfilled' && Array.isArray(execRes.value)) {
          for (const item of execRes.value) {
            if (!item?.batchId) continue;
            mergedMap.set(String(item.batchId), {
              batchId: String(item.batchId),
              batchNumber: item.batchNumber,
              batchStatus: item.batchStatus || 'IN_PROGRESS',
              productName: item.productName,
              orderNumber: item.orderNumber,
            });
          }
        }
        if (qaRes.status === 'fulfilled' && Array.isArray(qaRes.value)) {
          for (const item of qaRes.value) {
            if (!item?.batchId) continue;
            mergedMap.set(String(item.batchId), {
              batchId: String(item.batchId),
              batchNumber: item.batchNumber,
              batchStatus: item.batchStatus || 'COMPLETED',
              qaStatus: item.qaStatus,
              productName: item.productName,
              orderNumber: item.orderNumber,
            });
          }
        }

        if (execRes.status === 'rejected' && qaRes.status === 'rejected') {
          setBatchLoadWarning('Unable to load batch lists automatically. Use Batch Number lookup below.');
        } else if (execRes.status === 'rejected' || qaRes.status === 'rejected') {
          setBatchLoadWarning('Some batches could not be loaded automatically; use Batch Number lookup if needed.');
        }

        const list = Array.from(mergedMap.values()).sort((a, b) => {
          if (a.batchStatus !== b.batchStatus) {
            return a.batchStatus === 'COMPLETED' ? -1 : 1;
          }
          return (b.batchNumber || '').localeCompare(a.batchNumber || '');
        });
        setBatchOptions(list);
      } finally {
        if (!cancelled) {
          setIsLoadingBatches(false);
        }
      }
    };

    loadBatches();
    return () => {
      cancelled = true;
    };
  }, [isOpen, lockBatchSelection]);

  // Fetch authoritative linkable QC checks / eBR steps / Audit logs whenever batchId changes
  useEffect(() => {
    if (!isOpen || !batchId) {
      setLinkableRecords(null);
      setLinkablesError('');
      return;
    }

    let cancelled = false;
    const loadLinkables = async () => {
      setIsLoadingLinkables(true);
      setLinkablesError('');
      try {
        const data = await getBatchLinkableRecords(batchId);
        if (!cancelled) {
          setLinkableRecords(data || null);
        }
      } catch (err) {
        if (!cancelled) {
          setLinkableRecords(null);
          setLinkablesError(err.message || 'Unable to load linkable batch records');
        }
      } finally {
        if (!cancelled) {
          setIsLoadingLinkables(false);
        }
      }
    };

    loadLinkables();
    return () => {
      cancelled = true;
    };
  }, [isOpen, batchId]);

  const clearFieldError = (field) => {
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

  const handleFieldChange = (field, setter) => (e) => {
    setter(e.target.value);
    clearFieldError(field);
  };

  const handleBatchSelectionChange = (e) => {
    const newBatchId = e.target.value;
    setBatchId(newBatchId);
    setQualityCheckId('');
    setExecutionStepId('');
    setSourceAuditLogId('');
    clearFieldError('batchId');
  };

  const handleLookupBatchByNumber = async () => {
    const trimmed = batchLookupNumber.trim();
    if (!trimmed) {
      setBatchLookupError('Enter a batch number to look up.');
      return;
    }

    setIsLookingUpBatch(true);
    setBatchLookupError('');
    try {
      const found = await getBatchByBatchNumber(trimmed);
      if (!found?.id) {
        setBatchLookupError(`Batch "${trimmed}" was not found.`);
        return;
      }
      if (found.status !== 'IN_PROGRESS' && found.status !== 'COMPLETED') {
        setBatchLookupError(
          `Batch ${found.batchNumber} is ${found.status}. Deviations can only be reported against IN_PROGRESS or COMPLETED batches.`
        );
        return;
      }

      const idStr = String(found.id);
      setBatchOptions((prev) => {
        if (prev.some((b) => b.batchId === idStr)) return prev;
        return [
          {
            batchId: idStr,
            batchNumber: found.batchNumber,
            batchStatus: found.status,
            qaStatus: found.qaStatus,
            orderNumber: found.orderNumber,
          },
          ...prev,
        ];
      });
      setBatchId(idStr);
      setQualityCheckId('');
      setExecutionStepId('');
      setSourceAuditLogId('');
      setBatchLookupNumber('');
      clearFieldError('batchId');
    } catch (err) {
      setBatchLookupError(err.message || `Batch "${trimmed}" not found.`);
    } finally {
      setIsLookingUpBatch(false);
    }
  };

  const handleSafeClose = () => {
    if (isSubmitting || isSubmittingRef.current) return;
    onClose();
  };

  const validateForm = () => {
    const errors = {};
    const trimmedTitle = title.trim();
    const trimmedDescription = description.trim();
    const trimmedContainment = immediateContainment.trim();

    if (!batchId) {
      errors.batchId = 'Affected manufacturing batch is mandatory';
    }
    if (!trimmedTitle) {
      errors.title = 'Deviation title is mandatory';
    } else if (trimmedTitle.length > TITLE_MAX) {
      errors.title = `Deviation title must not exceed ${TITLE_MAX} characters`;
    }

    if (!category) {
      errors.category = 'Deviation category is mandatory';
    }
    if (!severity) {
      errors.severity = 'Deviation severity is mandatory';
    }
    if (!priority) {
      errors.priority = 'Deviation priority is mandatory';
    }

    if (!trimmedDescription) {
      errors.description = 'Deviation description is mandatory';
    } else if (trimmedDescription.length > DESCRIPTION_MAX) {
      errors.description = `Deviation description must not exceed ${DESCRIPTION_MAX} characters`;
    }

    if (trimmedContainment.length > CONTAINMENT_MAX) {
      errors.immediateContainment = `Immediate containment must not exceed ${CONTAINMENT_MAX} characters`;
    }

    const occurredAtError = validateDeviationOccurredAt(occurredAt);
    if (occurredAtError) {
      errors.occurredAt = occurredAtError;
    }

    const formattedOccurredAt = normalizeOccurredAtForApi(occurredAt);

    const payload = {
      batchId: batchId ? Number(batchId) : null,
      qualityCheckId: qualityCheckId ? Number(qualityCheckId) : null,
      executionStepId: executionStepId ? Number(executionStepId) : null,
      sourceAuditLogId: sourceAuditLogId ? Number(sourceAuditLogId) : null,
      title: trimmedTitle,
      category,
      severity,
      priority,
      description: trimmedDescription,
      immediateContainment: trimmedContainment || null,
      occurredAt: formattedOccurredAt,
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
      const created = await createDeviation(payload);
      if (onSuccess) {
        await onSuccess(created);
      }
      onClose();
    } catch (err) {
      if (err.validationErrors && typeof err.validationErrors === 'object') {
        setFieldErrors((prev) => ({
          ...prev,
          ...err.validationErrors,
        }));
      }
      setServerError(err.message || 'Failed to report deviation');
    } finally {
      isSubmittingRef.current = false;
      setIsSubmitting(false);
    }
  };

  const linkableChecks = Array.isArray(linkableRecords?.qualityChecks)
    ? linkableRecords.qualityChecks
    : [];
  const linkableSteps = Array.isArray(linkableRecords?.executionSteps)
    ? linkableRecords.executionSteps
    : [];
  const linkableAuditLogs = Array.isArray(linkableRecords?.auditLogs)
    ? linkableRecords.auditLogs
    : [];

  const batchErrId = `${uid}-batch-err`;
  const lookupInputId = `${uid}-batch-lookup`;
  const titleErrId = `${uid}-title-err`;
  const categoryErrId = `${uid}-category-err`;
  const severityErrId = `${uid}-severity-err`;
  const priorityErrId = `${uid}-priority-err`;
  const descriptionErrId = `${uid}-description-err`;
  const containmentErrId = `${uid}-containment-err`;
  const occurredAtErrId = `${uid}-occurred-err`;
  const occurredAtHelpId = `${uid}-occurred-help`;

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={handleSafeClose}
      preventClose={isSubmitting}
      size="2xl"
      maxHeightClass="max-h-[92vh]"
      bodyClassName="overflow-y-auto"
      headingAs="h3"
      title="Report Manufacturing / Quality Deviation"
      subtitle={
        prefillData?.batchNumber
          ? `Batch ${prefillData.batchNumber}`
          : 'Creates an audited OPEN deviation linked to an active or completed batch'
      }
      description="Submit a deviation report linked to a manufacturing batch and optional source QC, execution step, or audit record"
      closeButtonLabel="Close report deviation modal"
      initialFocusRef={lockBatchSelection ? titleInputRef : batchSelectRef}
      icon={
        <div className="w-8 h-8 rounded-lg bg-rose-600 flex items-center justify-center text-white shadow-sm">
          <AlertTriangle className="w-4 h-4" aria-hidden="true" />
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
            className="inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-semibold text-white bg-rose-600 hover:bg-rose-700 rounded-lg shadow-sm transition-colors disabled:opacity-50"
          >
            {isSubmitting ? (
              <>
                <RefreshCw className="w-4 h-4 animate-spin" aria-hidden="true" />
                <span>Submitting...</span>
              </>
            ) : (
              <>
                <CheckCircle2 className="w-4 h-4" aria-hidden="true" />
                <span>Submit Deviation</span>
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
            title="Unable to create deviation"
            message={serverError}
            onDismiss={() => setServerError('')}
            dismissLabel="Dismiss deviation form error"
          />
        )}

        {/* Affected Batch */}
        {lockBatchSelection && prefillData?.batchId ? (
          <div className="p-3 rounded-lg bg-slate-50 border border-slate-200 flex flex-wrap items-center justify-between gap-2 text-xs">
            <div className="flex items-center space-x-2">
              <Layers className="w-4 h-4 text-pharma-600" aria-hidden="true" />
              <span className="font-semibold text-slate-700">Affected Batch:</span>
              <span className="font-mono font-bold text-pharma-700 bg-pharma-50 px-2 py-0.5 rounded border border-pharma-200 tabular-nums">
                {prefillData.batchNumber || `Batch #${prefillData.batchId}`}
              </span>
            </div>
            {linkableRecords?.productName && (
              <span className="text-slate-500 tabular-nums">
                {linkableRecords.productName} ({linkableRecords.orderNumber})
              </span>
            )}
          </div>
        ) : (
          <div className="space-y-2">
            <label
              htmlFor="dev-batch-select"
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider"
            >
              Affected Batch (IN_PROGRESS or COMPLETED) <span className="text-rose-500">*</span>
            </label>
            <select
              ref={batchSelectRef}
              id="dev-batch-select"
              value={batchId}
              onChange={handleBatchSelectionChange}
              disabled={isSubmitting || isLoadingBatches}
              aria-invalid={Boolean(fieldErrors.batchId)}
              aria-describedby={fieldErrors.batchId ? batchErrId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 bg-white transition-colors ${
                fieldErrors.batchId
                  ? 'border-rose-300 focus:ring-rose-200'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
              }`}
            >
              <option value="">
                {isLoadingBatches
                  ? 'Loading eligible batches...'
                  : '-- Select Affected Manufacturing Batch --'}
              </option>
              {batchOptions.map((b) => (
                <option key={b.batchId} value={b.batchId}>
                  {b.batchNumber} ({b.batchStatus})
                  {b.productName ? ` — ${b.productName}` : ''}
                  {b.orderNumber ? ` [${b.orderNumber}]` : ''}
                </option>
              ))}
            </select>
            {fieldErrors.batchId && (
              <p id={batchErrId} role="alert" className="text-xs font-medium text-rose-600">
                {fieldErrors.batchId}
              </p>
            )}
            {batchLoadWarning && (
              <InlineAlert variant="warning" compact role="status" message={batchLoadWarning} />
            )}

            {/* Direct batch number lookup fallback */}
            <div className="flex flex-wrap items-center gap-2 pt-1">
              <div className="relative flex-1 min-w-0 sm:min-w-[200px]">
                <label htmlFor={lookupInputId} className="sr-only">
                  Look up batch by number
                </label>
                <Search className="w-3.5 h-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2" aria-hidden="true" />
                <input
                  id={lookupInputId}
                  type="text"
                  value={batchLookupNumber}
                  onChange={(e) => {
                    setBatchLookupNumber(e.target.value);
                    setBatchLookupError('');
                  }}
                  placeholder="Or look up batch by number (e.g., BAT-2026-0009-01)..."
                  disabled={isSubmitting || isLookingUpBatch}
                  className="w-full pl-8 pr-3 py-1.5 text-xs rounded-lg border border-slate-200 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 bg-slate-50"
                />
              </div>
              <button
                type="button"
                onClick={handleLookupBatchByNumber}
                disabled={isSubmitting || isLookingUpBatch}
                className="px-3 py-1.5 text-xs font-semibold text-slate-700 bg-white hover:bg-slate-100 border border-slate-300 rounded-lg transition-colors disabled:opacity-50"
              >
                {isLookingUpBatch ? 'Finding...' : 'Find Batch'}
              </button>
            </div>
            {batchLookupError && (
              <p role="alert" className="text-xs font-medium text-rose-600">
                {batchLookupError}
              </p>
            )}
          </div>
        )}

        {/* Authoritative Record Linking */}
        {batchId && (
          <div className="p-3.5 rounded-lg bg-slate-50 border border-slate-200 space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-700 flex items-center space-x-1.5">
                <Link2 className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                <span>Link Source Records (Optional Traceability)</span>
              </span>
              {isLoadingLinkables && (
                <span className="text-[11px] text-slate-500 inline-flex items-center">
                  <RefreshCw className="w-3 h-3 animate-spin mr-1" aria-hidden="true" />
                  Loading batch records...
                </span>
              )}
            </div>

            {linkablesError ? (
              <p role="status" className="text-xs text-amber-700">
                {linkablesError}
              </p>
            ) : (
              <div className="grid grid-cols-1 gap-2.5">
                <div>
                  <label
                    htmlFor="dev-link-qc"
                    className="block text-[11px] font-semibold text-slate-600 mb-1"
                  >
                    Quality Control (QC) Check
                  </label>
                  <select
                    id="dev-link-qc"
                    value={qualityCheckId}
                    onChange={handleFieldChange('qualityCheckId', setQualityCheckId)}
                    disabled={isSubmitting || isLoadingLinkables}
                    className="w-full px-2.5 py-1.5 text-xs rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
                  >
                    <option value="">— None linked —</option>
                    {linkableChecks.map((qc) => (
                      <option key={qc.id} value={String(qc.id)}>
                        [{qc.result}] {qc.testName}
                        {qc.measuredValue ? ` (${qc.measuredValue} ${qc.unit || ''})` : ''}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                  <div>
                    <label
                      htmlFor="dev-link-step"
                      className="block text-[11px] font-semibold text-slate-600 mb-1"
                    >
                      eBR Execution Step
                    </label>
                    <select
                      id="dev-link-step"
                      value={executionStepId}
                      onChange={handleFieldChange('executionStepId', setExecutionStepId)}
                      disabled={isSubmitting || isLoadingLinkables}
                      className="w-full px-2.5 py-1.5 text-xs rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
                    >
                      <option value="">— None linked —</option>
                      {linkableSteps.map((step) => (
                        <option key={step.id} value={String(step.id)}>
                          Step {step.stepNumber}: {step.operationName} ({step.status})
                        </option>
                      ))}
                    </select>
                  </div>

                  <div>
                    <label
                      htmlFor="dev-link-audit"
                      className="block text-[11px] font-semibold text-slate-600 mb-1"
                    >
                      Source Audit Event
                    </label>
                    <select
                      id="dev-link-audit"
                      value={sourceAuditLogId}
                      onChange={handleFieldChange('sourceAuditLogId', setSourceAuditLogId)}
                      disabled={isSubmitting || isLoadingLinkables}
                      className="w-full px-2.5 py-1.5 text-xs rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
                    >
                      <option value="">— None linked —</option>
                      {linkableAuditLogs.map((log) => (
                        <option key={log.id} value={String(log.id)}>
                          #{log.id} {log.action} ({log.actorName}
                          {log.occurredAt ? `, ${formatShortTimestamp(log.occurredAt)}` : ''})
                        </option>
                      ))}
                    </select>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {/* Title */}
        <div>
          <label
            htmlFor="dev-title"
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
          >
            Deviation Title <span className="text-rose-500">*</span>
          </label>
          <input
            ref={titleInputRef}
            id="dev-title"
            type="text"
            value={title}
            onChange={handleFieldChange('title', setTitle)}
            placeholder="Concise summary of the deviation event..."
            disabled={isSubmitting}
            aria-invalid={Boolean(fieldErrors.title)}
            aria-describedby={fieldErrors.title ? titleErrId : undefined}
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
              fieldErrors.title
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
            }`}
          />
          {fieldErrors.title && (
            <p id={titleErrId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {fieldErrors.title}
            </p>
          )}
        </div>

        {/* Classification Grid */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3.5">
          <div>
            <label
              htmlFor="dev-category"
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
            >
              Category <span className="text-rose-500">*</span>
            </label>
            <select
              id="dev-category"
              value={category}
              onChange={handleFieldChange('category', setCategory)}
              disabled={isSubmitting}
              aria-invalid={Boolean(fieldErrors.category)}
              aria-describedby={fieldErrors.category ? categoryErrId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 bg-white transition-colors ${
                fieldErrors.category
                  ? 'border-rose-300 focus:ring-rose-200'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
              }`}
            >
              <option value="">-- Select --</option>
              {DEVIATION_CATEGORY_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
            {fieldErrors.category && (
              <p id={categoryErrId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {fieldErrors.category}
              </p>
            )}
          </div>

          <div>
            <label
              htmlFor="dev-severity"
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
            >
              Severity <span className="text-rose-500">*</span>
            </label>
            <select
              id="dev-severity"
              value={severity}
              onChange={handleFieldChange('severity', setSeverity)}
              disabled={isSubmitting}
              aria-invalid={Boolean(fieldErrors.severity)}
              aria-describedby={fieldErrors.severity ? severityErrId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 bg-white transition-colors ${
                fieldErrors.severity
                  ? 'border-rose-300 focus:ring-rose-200'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
              }`}
            >
              {DEVIATION_SEVERITY_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
            {fieldErrors.severity && (
              <p id={severityErrId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {fieldErrors.severity}
              </p>
            )}
          </div>

          <div>
            <label
              htmlFor="dev-priority"
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
            >
              Priority <span className="text-rose-500">*</span>
            </label>
            <select
              id="dev-priority"
              value={priority}
              onChange={handleFieldChange('priority', setPriority)}
              disabled={isSubmitting}
              aria-invalid={Boolean(fieldErrors.priority)}
              aria-describedby={fieldErrors.priority ? priorityErrId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 bg-white transition-colors ${
                fieldErrors.priority
                  ? 'border-rose-300 focus:ring-rose-200'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
              }`}
            >
              {DEVIATION_PRIORITY_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
            {fieldErrors.priority && (
              <p id={priorityErrId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {fieldErrors.priority}
              </p>
            )}
          </div>
        </div>

        {/* Description */}
        <div>
          <label
            htmlFor="dev-description"
            className="flex items-center space-x-1 text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
          >
            <FileText className="w-3.5 h-3.5 text-slate-400" aria-hidden="true" />
            <span>
              Event Description <span className="text-rose-500">*</span>
            </span>
          </label>
          <textarea
            id="dev-description"
            rows={3}
            value={description}
            onChange={handleFieldChange('description', setDescription)}
            placeholder="Detail observed conditions, expected criteria, sampling facts, and affected scope..."
            disabled={isSubmitting}
            aria-invalid={Boolean(fieldErrors.description)}
            aria-describedby={fieldErrors.description ? descriptionErrId : undefined}
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
              fieldErrors.description
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
            }`}
          />
          {fieldErrors.description && (
            <p id={descriptionErrId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {fieldErrors.description}
            </p>
          )}
        </div>

        {/* Immediate Containment */}
        <div>
          <label
            htmlFor="dev-containment"
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
          >
            Immediate Containment Action (Optional)
          </label>
          <textarea
            id="dev-containment"
            rows={2}
            value={immediateContainment}
            onChange={handleFieldChange('immediateContainment', setImmediateContainment)}
            placeholder="e.g., Batch quarantined physically and held from disposition pending OOS investigation..."
            disabled={isSubmitting}
            aria-invalid={Boolean(fieldErrors.immediateContainment)}
            aria-describedby={fieldErrors.immediateContainment ? containmentErrId : undefined}
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
              fieldErrors.immediateContainment
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
            }`}
          />
          {fieldErrors.immediateContainment && (
            <p id={containmentErrId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {fieldErrors.immediateContainment}
            </p>
          )}
        </div>

        {/* Reporter & Timestamp */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3.5">
          <div>
            <span className="flex items-center space-x-1 text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
              <User className="w-3.5 h-3.5 text-slate-400" aria-hidden="true" />
              <span>Reported By</span>
            </span>
            <div
              data-testid="authenticated-reporter-display"
              className="px-3 py-2 text-sm rounded-lg border border-slate-200 bg-slate-50 text-slate-800 font-medium"
            >
              {actorDisplay}
            </div>
            <p className="mt-1 text-[11px] text-slate-500">
              Attributed automatically from your authenticated session.
            </p>
          </div>

          <div>
            <label
              htmlFor="dev-occurred-at"
              className="flex items-center space-x-1 text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5"
            >
              <Clock className="w-3.5 h-3.5 text-slate-400" aria-hidden="true" />
              <span>Occurred At — IST (Asia/Kolkata) (Optional)</span>
            </label>
            <input
              id="dev-occurred-at"
              type="datetime-local"
              value={occurredAt}
              onChange={handleFieldChange('occurredAt', setOccurredAt)}
              disabled={isSubmitting}
              aria-invalid={Boolean(fieldErrors.occurredAt)}
              aria-describedby={fieldErrors.occurredAt ? occurredAtErrId : occurredAtHelpId}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors tabular-nums ${
                fieldErrors.occurredAt
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
              }`}
            />
            {fieldErrors.occurredAt ? (
              <p id={occurredAtErrId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {fieldErrors.occurredAt}
              </p>
            ) : (
              <p id={occurredAtHelpId} className="mt-1 text-[11px] text-slate-500">
                Leave blank to stamp current IST (Asia/Kolkata) server time.
              </p>
            )}
          </div>
        </div>
      </form>
    </ModalDialog>
  );
}
