import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  ArrowUpRight,
  RefreshCw,
  CheckCircle2,
  XCircle,
  PauseCircle,
  Clock,
  ShieldCheck,
  FlaskConical,
  Box,
  ClipboardList,
  Layers,
  ClipboardCheck,
  Plus,
  Edit3,
  Lock,
  History,
  FileCheck2,
  AlertTriangle,
  ArrowRight,
  Wrench,
  Calendar,
  ChevronDown,
  ChevronRight,
  QrCode,
} from 'lucide-react';
import { getBatchQualityDossier } from '../services/qualityApi';
import QualityCheckModal from './QualityCheckModal';
import QaDispositionModal from './QaDispositionModal';
import ExecutionHistoryModal from './ExecutionHistoryModal';
import BatchQrModal from './BatchQrModal.jsx';
import ReportDeviationModal from './ReportDeviationModal';
import {
  AuditDiffViewer,
  DataTable,
  DEFAULT_PAGE_SIZE,
  InlineAlert,
  PageHeader,
  Pagination,
  paginateItems,
  ProgressBar,
  redactAuditText,
  StatusBadge,
  summarizeAuditChanges,
} from './ui/index.js';
import {
  buildDeviationPrefillFromQc,
  getCategoryLabel,
} from '../utils/deviationUtils';
import {
  assessBatchReleaseEligibility,
  QA_RELEASE_POLICY_COPY,
} from '../utils/qualityPolicyUtils';
import {
  isValueWithinRule,
  parseNumericMeasuredValue,
  parseNumericSpecification,
} from '../utils/qcSpecificationValidator';
import {
  getDeviationPriorityBadge,
  getDeviationSeverityBadge,
  getDeviationStatusBadge,
} from './DeviationBadges';
import { useAuth } from '../context/AuthContext.jsx';
import { useNavigation } from '../context/NavigationContext.jsx';
import {
  canPerformQaDisposition,
  canRecordQcChecks,
  canReportDeviation,
  canViewRecipes,
} from '../utils/rbacUtils.js';
import { formatAppDateTime, formatCalendarDate } from '../utils/dateTimeUtils.js';

export function formatDateTime(dateTimeString) {
  return formatAppDateTime(dateTimeString);
}

export function getQaStatusBadge(qaStatus) {
  switch (qaStatus) {
    case 'RELEASED':
      return <StatusBadge status="RELEASED" domain="qa" icon={CheckCircle2} />;
    case 'ON_HOLD':
      return <StatusBadge status="ON_HOLD" domain="qa" icon={PauseCircle} />;
    case 'REJECTED':
      return <StatusBadge status="REJECTED" domain="qa" icon={XCircle} />;
    case 'PENDING_QA':
    default:
      return (
        <StatusBadge
          status={qaStatus || 'PENDING_QA'}
          label={qaStatus || 'PENDING_QA'}
          domain="qa"
          icon={Clock}
        />
      );
  }
}

function getQcResultBadge(result) {
  switch (result) {
    case 'PASS':
      return <StatusBadge status="PASS" domain="qc" icon={CheckCircle2} />;
    case 'FAIL':
      return <StatusBadge status="FAIL" domain="qc" icon={XCircle} />;
    case 'PENDING':
    default:
      return <StatusBadge status="PENDING" domain="qc" icon={Clock} />;
  }
}

export default function QualityBatchDossier({
  batchId,
  onBack,
  onOpenDeviation,
  onQualityUpdated,
  onNotify,
}) {
  const { role } = useAuth();
  const { navigateToTab } = useNavigation();
  const canRecordQc = canRecordQcChecks(role);
  const canDisposeBatch = canPerformQaDisposition(role);
  const canReportDev = canReportDeviation(role);
  const canOpenRecipes = canViewRecipes(role);

  const [dossier, setDossier] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState(null);

  // Pagination & Sort State for tables & history cards
  const [qcSortState, setQcSortState] = useState(null);
  const [qcPage, setQcPage] = useState(1);
  const [qcPageSize, setQcPageSize] = useState(DEFAULT_PAGE_SIZE);

  const [devSortState, setDevSortState] = useState(null);
  const [devPage, setDevPage] = useState(1);
  const [devPageSize, setDevPageSize] = useState(DEFAULT_PAGE_SIZE);

  const [reviewPage, setReviewPage] = useState(1);
  const [reviewPageSize, setReviewPageSize] = useState(DEFAULT_PAGE_SIZE);

  const [auditPage, setAuditPage] = useState(1);
  const [auditPageSize, setAuditPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [expandedAuditIds, setExpandedAuditIds] = useState({});

  // Sub-modals & drawer state
  const [qcModalState, setQcModalState] = useState({ open: false, mode: 'create', check: null });
  const [dispositionModal, setDispositionModal] = useState({ open: false, decision: 'RELEASED' });
  const [deviationModalState, setDeviationModalState] = useState({ open: false, prefill: null });
  const [showEbrModal, setShowEbrModal] = useState(false);
  const [showQrModal, setShowQrModal] = useState(false);

  const loadDossier = useCallback(
    async ({ manualRefresh = false } = {}) => {
      if (!batchId) return;
      if (manualRefresh) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }
      setError(null);
      try {
        const data = await getBatchQualityDossier(batchId);
        setDossier(data || null);
      } catch (err) {
        const message = err.message || 'Failed to load batch quality dossier';
        setError(message);
        if (manualRefresh && onNotify) {
          onNotify({ type: 'error', message });
        }
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [batchId, onNotify]
  );

  useEffect(() => {
    setDossier(null);
    setQcPage(1);
    setDevPage(1);
    setReviewPage(1);
    setAuditPage(1);
    setExpandedAuditIds({});
    loadDossier();
  }, [loadDossier]);

  const handleQcSaved = async (savedCheck, isCorrection) => {
    const actionText = isCorrection ? 'Audited QC update recorded' : 'QC check recorded';
    if (onNotify) {
      onNotify({
        type: 'success',
        message: `${actionText}: ${savedCheck?.testName || 'QC Check'} (${savedCheck?.result || ''}).`,
      });
    }
    await loadDossier({ manualRefresh: true });
    if (onQualityUpdated) {
      await onQualityUpdated();
    }
  };

  const handleDispositionSaved = async (updatedDossier, newStatus) => {
    setDossier(updatedDossier || null);
    if (onNotify) {
      onNotify({
        type: 'success',
        message: `Batch ${updatedDossier?.batchNumber || ''} QA disposition updated to ${newStatus}.`,
      });
    }
    if (onQualityUpdated) {
      await onQualityUpdated();
    }
  };

  const handleDeviationCreated = async (created) => {
    if (onNotify) {
      onNotify({
        type: 'success',
        message: `Deviation ${created?.deviationNumber || ''} reported against batch ${
          dossier?.batchNumber || ''
        }.`,
      });
    }
    await loadDossier({ manualRefresh: true });
    if (onQualityUpdated) {
      await onQualityUpdated();
    }
  };

  const qualityChecks = useMemo(
    () => (Array.isArray(dossier?.qualityChecks) ? dossier.qualityChecks : []),
    [dossier]
  );
  const qaReviews = useMemo(
    () => (Array.isArray(dossier?.qaReviews) ? dossier.qaReviews : []),
    [dossier]
  );
  const auditLogs = useMemo(
    () => (Array.isArray(dossier?.auditLogs) ? dossier.auditLogs : []),
    [dossier]
  );
  const deviations = useMemo(
    () => (Array.isArray(dossier?.deviations) ? dossier.deviations : []),
    [dossier]
  );

  const eligibility = assessBatchReleaseEligibility(dossier);
  const isFinalized = eligibility.isFinalized;
  const openDeviationsCount = eligibility.openDeviationsCount;
  const totalDeviationsCount = Number(dossier?.totalDeviationsCount ?? deviations.length);
  const releaseBlockers = eligibility.blockers;
  const qcCounts = eligibility.qcCounts;

  const yieldRecoveryPercent =
    dossier?.actualYield != null &&
    dossier?.batchSize != null &&
    Number(dossier.batchSize) > 0
      ? ((Number(dossier.actualYield) / Number(dossier.batchSize)) * 100).toFixed(1)
      : null;

  // Sort & paginate QC checks
  const sortedQcChecks = useMemo(() => {
    if (!qcSortState?.key) return qualityChecks;
    const dir = qcSortState.direction === 'desc' ? -1 : 1;
    return [...qualityChecks].sort((a, b) => {
      const valA = a?.[qcSortState.key];
      const valB = b?.[qcSortState.key];
      if (valA == null && valB == null) return 0;
      if (valA == null) return -1 * dir;
      if (valB == null) return 1 * dir;
      return String(valA).localeCompare(String(valB), undefined, { numeric: true }) * dir;
    });
  }, [qualityChecks, qcSortState]);

  const paginatedQcChecks = useMemo(
    () => paginateItems(sortedQcChecks, qcPage, qcPageSize),
    [sortedQcChecks, qcPage, qcPageSize]
  );

  useEffect(() => {
    if (paginatedQcChecks.page !== qcPage) setQcPage(paginatedQcChecks.page);
  }, [paginatedQcChecks.page, qcPage]);

  // Sort & paginate linked deviations
  const sortedDeviations = useMemo(() => {
    if (!devSortState?.key) return deviations;
    const dir = devSortState.direction === 'desc' ? -1 : 1;
    return [...deviations].sort((a, b) => {
      const valA = a?.[devSortState.key];
      const valB = b?.[devSortState.key];
      if (valA == null && valB == null) return 0;
      if (valA == null) return -1 * dir;
      if (valB == null) return 1 * dir;
      return String(valA).localeCompare(String(valB), undefined, { numeric: true }) * dir;
    });
  }, [deviations, devSortState]);

  const paginatedDeviations = useMemo(
    () => paginateItems(sortedDeviations, devPage, devPageSize),
    [sortedDeviations, devPage, devPageSize]
  );

  useEffect(() => {
    if (paginatedDeviations.page !== devPage) setDevPage(paginatedDeviations.page);
  }, [paginatedDeviations.page, devPage]);

  // Paginate QA reviews & audit trail
  const paginatedReviews = useMemo(
    () => paginateItems(qaReviews, reviewPage, reviewPageSize),
    [qaReviews, reviewPage, reviewPageSize]
  );

  const paginatedAuditLogs = useMemo(
    () => paginateItems(auditLogs, auditPage, auditPageSize),
    [auditLogs, auditPage, auditPageSize]
  );

  const toggleAuditRowDiff = (logId) => {
    setExpandedAuditIds((prev) => ({
      ...prev,
      [logId]: !prev[logId],
    }));
  };

  const qcColumns = useMemo(
    () => [
      {
        key: 'testName',
        header: 'Test Name & Acceptance Spec',
        sortable: true,
        headerClassName: 'sm:px-6',
        cellClassName: 'sm:px-6 align-top',
        render: (check) => {
          const parsedLimit = parseNumericSpecification(check.specification);
          return (
            <div>
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="font-semibold text-slate-900">{check.testName}</span>
                {check.required ? (
                  <span
                    title={QA_RELEASE_POLICY_COPY.requiredHelp}
                    className="px-1.5 py-0.5 rounded text-[10px] font-bold bg-pharma-50 text-pharma-700 border border-pharma-200 uppercase"
                  >
                    Required
                  </span>
                ) : (
                  <span
                    title={QA_RELEASE_POLICY_COPY.optionalHelp}
                    className="px-1.5 py-0.5 rounded text-[10px] font-bold bg-slate-100 text-slate-600 border border-slate-200 uppercase"
                  >
                    Optional
                  </span>
                )}
                {!isFinalized && check.result !== 'PASS' && (
                  <span className="px-1.5 py-0.5 rounded text-[10px] font-semibold bg-amber-50 text-amber-800 border border-amber-200">
                    Blocks Release
                  </span>
                )}
              </div>
              <p className="text-xs text-slate-500 mt-0.5">{check.specification}</p>
              {parsedLimit && (
                <span className="inline-block mt-1 px-1.5 py-0.5 rounded text-[10px] font-mono font-medium bg-slate-100 text-slate-600 border border-slate-200 tabular-nums">
                  Limit: {parsedLimit.describe}
                </span>
              )}
            </div>
          );
        },
      },
      {
        key: 'measuredValue',
        header: 'Measured Value',
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top',
        render: (check) => {
          const parsedLimit = parseNumericSpecification(check.specification);
          const numericMeasured = parseNumericMeasuredValue(check.measuredValue);
          const withinSpec =
            parsedLimit && numericMeasured !== null
              ? isValueWithinRule(parsedLimit, numericMeasured)
              : null;

          if (!check.measuredValue) {
            return <span className="text-xs text-slate-500">Pending ({check.unit || '—'})</span>;
          }
          return (
            <div>
              <span className="font-mono font-semibold text-slate-900 tabular-nums">
                {check.measuredValue}{' '}
                <span className="text-xs font-sans text-slate-500">{check.unit}</span>
              </span>
              {withinSpec !== null && (
                <div className="mt-1">
                  <span
                    className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-semibold border ${
                      withinSpec
                        ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                        : 'bg-rose-50 text-rose-700 border-rose-200'
                    }`}
                  >
                    {withinSpec ? 'Within Limit' : 'Out of Limit'}
                  </span>
                </div>
              )}
            </div>
          );
        },
      },
      {
        key: 'result',
        header: 'Result',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top',
        render: (check) => (
          <div className="flex flex-col items-start space-y-1">
            {getQcResultBadge(check.result)}
            {check.locked && (
              <span className="inline-flex items-center text-[10px] text-slate-500 font-medium">
                <Lock className="w-2.5 h-2.5 mr-0.5" aria-hidden="true" />
                Locked
              </span>
            )}
          </div>
        ),
      },
      {
        key: 'checkedAt',
        header: 'Inspector & Time',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top',
        render: (check) => (
          <div>
            <p className="text-xs font-medium text-slate-800">{check.inspectorName}</p>
            <p className="text-[11px] text-slate-500 mt-0.5 tabular-nums">
              {formatDateTime(check.checkedAt)}
            </p>
          </div>
        ),
      },
      {
        key: 'notes',
        header: 'Notes / Corrections',
        cellClassName: 'align-top max-w-xs',
        render: (check) => (
          <div>
            {check.remarks && (
              <p className="text-xs text-slate-700 break-words">{check.remarks}</p>
            )}
            {check.correctionCount > 0 && (
              <div className="mt-1 p-2 rounded bg-amber-50 border border-amber-200 text-[11px] text-amber-900 space-y-0.5">
                <span className="font-semibold block">
                  Corrected ({check.correctionCount}x) by {check.correctedBy}
                </span>
                {check.correctedAt && (
                  <span className="block text-[10px] text-amber-700 tabular-nums">
                    {formatDateTime(check.correctedAt)}
                  </span>
                )}
                {check.correctionReason && (
                  <p className="text-amber-800">{check.correctionReason}</p>
                )}
              </div>
            )}
            {!check.remarks && !check.correctionCount && (
              <span className="text-xs text-slate-500">—</span>
            )}
          </div>
        ),
      },
      {
        key: 'actions',
        header: 'Actions',
        align: 'right',
        headerClassName: 'pr-4 sm:pr-6 whitespace-nowrap',
        cellClassName: 'pr-4 sm:pr-6 whitespace-nowrap align-top',
        render: (check) => (
          <div className="flex flex-col items-end gap-1.5">
            {isFinalized ? (
              <span className="inline-flex items-center text-xs text-slate-500">
                <Lock className="w-3.5 h-3.5 mr-1" aria-hidden="true" />
                Finalized
              </span>
            ) : canRecordQc ? (
              <button
                type="button"
                onClick={() => setQcModalState({ open: true, mode: 'correct', check })}
                className={`inline-flex items-center space-x-1 px-2.5 py-1.5 text-xs font-medium rounded-lg border transition-colors ${
                  check.result === 'PENDING'
                    ? 'text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border-pharma-200'
                    : 'text-amber-800 bg-amber-50 hover:bg-amber-100 border-amber-200'
                }`}
              >
                <Edit3 className="w-3.5 h-3.5" aria-hidden="true" />
                <span>
                  {check.result === 'PENDING' ? 'Record Result' : 'Audited Correction'}
                </span>
              </button>
            ) : (
              check.result !== 'FAIL' && (
                <span className="text-xs text-slate-500">Read-Only</span>
              )
            )}

            {check.result === 'FAIL' && canReportDev && (
              <button
                type="button"
                onClick={() =>
                  setDeviationModalState({
                    open: true,
                    prefill: buildDeviationPrefillFromQc(dossier, check),
                  })
                }
                className="inline-flex items-center space-x-1 px-2.5 py-1 text-xs font-semibold text-rose-700 bg-rose-50 hover:bg-rose-100 border border-rose-200 rounded-lg transition-colors"
              >
                <AlertTriangle className="w-3.5 h-3.5" aria-hidden="true" />
                <span>Report Deviation</span>
              </button>
            )}
          </div>
        ),
      },
    ],
    [canRecordQc, canReportDev, dossier, isFinalized]
  );

  const deviationColumns = useMemo(
    () => [
      {
        key: 'deviationNumber',
        header: 'Deviation #',
        sortable: true,
        headerClassName: 'sm:px-6 whitespace-nowrap',
        cellClassName: 'sm:px-6 whitespace-nowrap align-top',
        render: (dev) => (
          <div>
            <span className="font-mono font-bold text-rose-700 bg-rose-50 px-2 py-0.5 rounded border border-rose-200 tabular-nums">
              {dev.deviationNumber}
            </span>
            <div className="text-[11px] text-slate-500 mt-1 tabular-nums">
              {formatDateTime(dev.reportedAt)}
            </div>
          </div>
        ),
      },
      {
        key: 'title',
        header: 'Title & Category',
        sortable: true,
        cellClassName: 'max-w-sm align-top',
        render: (dev) => (
          <div>
            <p className="font-semibold text-slate-900 leading-snug">{dev.title}</p>
            <div className="flex flex-wrap items-center gap-1.5 mt-1">
              <span className="text-[11px] px-1.5 py-0.5 rounded bg-slate-100 text-slate-700 border border-slate-200">
                {getCategoryLabel(dev.category)}
              </span>
              {dev.linkedCheckTestName && (
                <span className="text-[11px] px-1.5 py-0.5 rounded bg-rose-50 text-rose-700 border border-rose-200">
                  QC: {dev.linkedCheckTestName}
                </span>
              )}
              {!isFinalized && dev.status !== 'CLOSED' && (
                <span className="text-[11px] px-1.5 py-0.5 rounded bg-amber-50 text-amber-800 border border-amber-200 font-semibold">
                  Blocks Release
                </span>
              )}
            </div>
          </div>
        ),
      },
      {
        key: 'severity',
        header: 'Severity / Priority',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top',
        render: (dev) => (
          <div className="flex flex-col items-start gap-1">
            {getDeviationSeverityBadge(dev.severity)}
            {getDeviationPriorityBadge(dev.priority)}
          </div>
        ),
      },
      {
        key: 'status',
        header: 'Status',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top',
        render: (dev) => getDeviationStatusBadge(dev.status),
      },
      {
        key: 'capaProgress',
        header: 'CAPA Progress',
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'min-w-[160px] align-top text-xs',
        render: (dev) => {
          const activeCapaCount = Math.max(
            0,
            Number(dev.totalCapaCount || 0) - Number(dev.cancelledCapaCount || 0)
          );
          if (Number(dev.totalCapaCount || 0) === 0) {
            return <span className="text-slate-500">No CAPAs yet</span>;
          }
          return (
            <div className="space-y-1.5">
              <ProgressBar
                value={Number(dev.completedCapaCount || 0)}
                max={Math.max(1, activeCapaCount)}
                label={`CAPA progress for ${dev.deviationNumber}`}
                summaryText={`${dev.completedCapaCount || 0} / ${activeCapaCount} Active Done`}
                tone={dev.overdueCapaCount > 0 ? 'danger' : 'auto'}
                size="sm"
              />
              {dev.overdueCapaCount > 0 && (
                <span className="inline-block px-1.5 py-0.5 rounded text-[10px] font-bold bg-rose-100 text-rose-800 border border-rose-200 tabular-nums">
                  {dev.overdueCapaCount} Overdue
                </span>
              )}
            </div>
          );
        },
      },
      {
        key: 'actions',
        header: 'Actions',
        align: 'right',
        headerClassName: 'pr-6 whitespace-nowrap',
        cellClassName: 'pr-6 whitespace-nowrap align-top',
        render: (dev) =>
          onOpenDeviation ? (
            <button
              type="button"
              onClick={() => onOpenDeviation(dev.id)}
              className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors"
            >
              <span>Open Deviation</span>
              <ArrowRight className="w-3.5 h-3.5" aria-hidden="true" />
            </button>
          ) : null,
      },
    ],
    [isFinalized, onOpenDeviation]
  );

  return (
    <div className="space-y-6">
      <PageHeader
        backAction={{ label: 'Back to Quality Queue', onClick: onBack }}
        eyebrow="Quality Management / Batch Dossier"
        icon={<ShieldCheck className="w-6 h-6 text-pharma-600" aria-hidden="true" />}
        title="Batch Quality Dossier"
        badge={dossier?.batchNumber || undefined}
        description="Inspect manufacturing context, analytical QC check outcomes, linked deviations, and immutable QA disposition history."
        actions={
          <>
            {dossier && (
              <button
                type="button"
                onClick={() => setShowEbrModal(true)}
                className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-sm font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg shadow-sm transition-colors"
              >
                <ClipboardCheck className="w-4 h-4" aria-hidden="true" />
                <span>Inspect eBR History (Read-Only)</span>
              </button>
            )}

            <button
              type="button"
              onClick={() => loadDossier({ manualRefresh: true })}
              disabled={isLoading || isRefreshing}
              className="inline-flex items-center space-x-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
            >
              <RefreshCw
                className={`w-4 h-4 ${
                  isLoading || isRefreshing ? 'animate-spin text-pharma-600' : 'text-slate-500'
                }`}
                aria-hidden="true"
              />
              <span>{isRefreshing ? 'Refreshing...' : 'Refresh Dossier'}</span>
            </button>
          </>
        }
      />

      {/* Loading State */}
      {isLoading && !dossier && (
        <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-8 sm:p-12 text-center">
          <RefreshCw className="w-8 h-8 mx-auto text-pharma-600 animate-spin mb-3" aria-hidden="true" />
          <p className="text-sm font-medium text-slate-700">Loading batch quality dossier...</p>
          <p className="text-xs text-slate-500 mt-1">
            Fetching batch metadata, QC check records, QA reviews, and audit logs
          </p>
        </div>
      )}

      {/* Error State */}
      {!isLoading && error && !dossier && (
        <InlineAlert
          variant="error"
          role="alert"
          title="Unable to load batch quality dossier"
          message={error}
          actions={
            <button
              type="button"
              onClick={() => loadDossier()}
              className="text-xs font-semibold px-3 py-1.5 bg-white border border-rose-300 rounded-lg text-rose-800 hover:bg-rose-50"
            >
              Retry
            </button>
          }
        />
      )}

      {/* Main Dossier Content */}
      {dossier && (
        <>
          {/* Batch Summary & Metadata Card */}
          <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-4 sm:p-5 space-y-5">
            <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4 pb-4 border-b border-slate-100">
              <div className="flex items-start space-x-3">
                <div className="w-11 h-11 rounded-lg bg-pharma-600 text-white flex items-center justify-center shadow-sm shrink-0">
                  <FileCheck2 className="w-6 h-6" aria-hidden="true" />
                </div>
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <h2 className="text-xl font-bold font-mono text-slate-900 tracking-tight tabular-nums">
                      {dossier.batchNumber}
                    </h2>
                    <StatusBadge
                      status={dossier.batchStatus}
                      label={`Mfg: ${dossier.batchStatus}`}
                      domain="order"
                    />
                    <span className="inline-flex items-center space-x-1">
                      <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
                        QA:
                      </span>
                      {getQaStatusBadge(dossier.qaStatus)}
                    </span>
                    {openDeviationsCount > 0 && (
                      <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-rose-100 text-rose-800 border border-rose-200 tabular-nums">
                        <AlertTriangle className="w-3.5 h-3.5 mr-1 text-rose-600" aria-hidden="true" />
                        {openDeviationsCount} Unresolved Deviation{openDeviationsCount > 1 ? 's' : ''}
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-slate-500 mt-1 tabular-nums">
                    Manufacturing completed at {formatDateTime(dossier.endTime)} &bull; Target batch size{' '}
                    <span className="font-semibold text-slate-700 tabular-nums">
                      {dossier.batchSize} {dossier.batchUnit}
                    </span>
                  </p>
                </div>
              </div>

              {/* QA Disposition Action Buttons */}
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  onClick={() => setShowQrModal(true)}
                  title="View Batch QR Code & Traceability Identity"
                  className="inline-flex items-center space-x-1.5 px-3 py-2 text-xs font-semibold text-teal-700 bg-teal-50 hover:bg-teal-100 border border-teal-200 rounded-lg shadow-sm transition-colors"
                >
                  <QrCode className="w-4 h-4 text-teal-600" aria-hidden="true" />
                  <span>Batch QR</span>
                </button>
                {isFinalized ? (
                  <span className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-slate-100 text-slate-700 border border-slate-300">
                    <Lock className="w-3.5 h-3.5 text-slate-500" aria-hidden="true" />
                    <span>Final Disposition Recorded ({dossier.qaStatus})</span>
                  </span>
                ) : canDisposeBatch ? (
                  <>
                    <button
                      type="button"
                      onClick={() =>
                        setDispositionModal({ open: true, decision: 'RELEASED' })
                      }
                      disabled={!eligibility.isEligible}
                      title={
                        eligibility.isEligible
                          ? 'Approve batch for QA release'
                          : releaseBlockers.join('; ')
                      }
                      className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-xs font-semibold text-white bg-emerald-600 hover:bg-emerald-700 disabled:bg-slate-300 disabled:text-slate-500 disabled:cursor-not-allowed rounded-lg shadow-sm transition-colors"
                    >
                      <CheckCircle2 className="w-4 h-4" aria-hidden="true" />
                      <span>Release Batch</span>
                    </button>

                    {dossier.qaStatus === 'PENDING_QA' && (
                      <button
                        type="button"
                        onClick={() =>
                          setDispositionModal({ open: true, decision: 'ON_HOLD' })
                        }
                        className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-xs font-semibold text-amber-800 bg-amber-50 hover:bg-amber-100 border border-amber-300 rounded-lg transition-colors"
                      >
                        <PauseCircle className="w-4 h-4 text-amber-600" aria-hidden="true" />
                        <span>Place On Hold</span>
                      </button>
                    )}

                    <button
                      type="button"
                      onClick={() =>
                        setDispositionModal({ open: true, decision: 'REJECTED' })
                      }
                      className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-xs font-semibold text-rose-800 bg-rose-50 hover:bg-rose-100 border border-rose-300 rounded-lg transition-colors"
                    >
                      <XCircle className="w-4 h-4 text-rose-600" aria-hidden="true" />
                      <span>Reject Batch</span>
                    </button>
                  </>
                ) : (
                  <span className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-medium bg-slate-100 text-slate-600 border border-slate-200">
                    <Lock className="w-3.5 h-3.5 text-slate-400" aria-hidden="true" />
                    <span>QA Disposition Requires QA Reviewer</span>
                  </span>
                )}
              </div>
            </div>

            {/* Primary Traceability Metadata Cards */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3.5">
              <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                    <ClipboardList className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                    <span>Manufacturing Order</span>
                  </span>
                  {(dossier.orderId || dossier.orderNumber) && (
                    <button
                      type="button"
                      onClick={() =>
                        navigateToTab(
                          'orders',
                          dossier.orderId
                            ? { orderId: dossier.orderId }
                            : { orderNumber: dossier.orderNumber }
                        )
                      }
                      className="inline-flex items-center space-x-0.5 text-[11px] font-semibold text-pharma-700 hover:text-pharma-800 hover:underline whitespace-nowrap shrink-0"
                    >
                      <span>Open Order</span>
                      <ArrowUpRight className="w-3 h-3" aria-hidden="true" />
                    </button>
                  )}
                </div>
                <span className="text-sm font-mono font-bold text-slate-900 mt-1 block tabular-nums">
                  {dossier.orderNumber || '—'}
                </span>
              </div>

              <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                  <Box className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                  <span>Product</span>
                </span>
                <span className="text-sm font-semibold text-slate-900 mt-1 block">
                  {dossier.productName || '—'}
                </span>
                {dossier.productCode && (
                  <span className="text-[11px] font-mono text-slate-500 tabular-nums">
                    {dossier.productCode}
                  </span>
                )}
              </div>

              <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                    <FlaskConical className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                    <span>Master Recipe</span>
                  </span>
                  {canOpenRecipes && (dossier.recipeId || dossier.recipeCode) && (
                    <button
                      type="button"
                      onClick={() =>
                        navigateToTab(
                          'recipes',
                          dossier.recipeId
                            ? { recipeId: dossier.recipeId }
                            : { recipeCode: dossier.recipeCode }
                        )
                      }
                      className="inline-flex items-center space-x-0.5 text-[11px] font-semibold text-pharma-700 hover:text-pharma-800 hover:underline whitespace-nowrap shrink-0"
                    >
                      <span>Inspect Recipe</span>
                      <ArrowUpRight className="w-3 h-3" aria-hidden="true" />
                    </button>
                  )}
                </div>
                <div className="flex items-center space-x-2 mt-1">
                  <span className="text-sm font-mono font-semibold text-slate-900 tabular-nums">
                    {dossier.recipeCode || '—'}
                  </span>
                  {dossier.recipeVersion != null && (
                    <span className="px-1.5 py-0.5 rounded text-[11px] font-bold bg-indigo-50 text-indigo-700 border border-indigo-200 tabular-nums">
                      v{dossier.recipeVersion}
                    </span>
                  )}
                </div>
              </div>

              <div className="bg-slate-50 border border-slate-200 rounded-lg p-3 space-y-1.5">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                  <Layers className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                  <span>Actual Yield &amp; Recovery</span>
                </span>
                <span className="text-sm font-bold text-slate-900 block tabular-nums">
                  {dossier.actualYield != null
                    ? `${dossier.actualYield} ${dossier.batchUnit || ''}`
                    : '—'}
                </span>
                {yieldRecoveryPercent && (
                  <>
                    <ProgressBar
                      value={Number(dossier.actualYield)}
                      max={Number(dossier.batchSize)}
                      label="Yield recovery"
                      size="sm"
                      tone="success"
                    />
                    <span className="text-[11px] font-medium text-emerald-700 block tabular-nums">
                      {yieldRecoveryPercent}% of target size
                    </span>
                  </>
                )}
              </div>
            </div>

            {/* Verified Execution & Shelf-Life Timeline Strip */}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
              <div className="bg-white border border-slate-200 rounded-lg p-3">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                  <Clock className="w-3 h-3 text-slate-500" aria-hidden="true" />
                  <span>Execution Started</span>
                </span>
                <span className="text-xs font-medium text-slate-800 mt-1 block tabular-nums">
                  {formatDateTime(dossier.startTime)}
                </span>
              </div>

              <div className="bg-white border border-slate-200 rounded-lg p-3">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                  <Clock className="w-3 h-3 text-slate-500" aria-hidden="true" />
                  <span>Batch Completed</span>
                </span>
                <span className="text-xs font-medium text-slate-800 mt-1 block tabular-nums">
                  {formatDateTime(dossier.endTime)}
                </span>
              </div>

              <div className="bg-white border border-slate-200 rounded-lg p-3">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                  <Calendar className="w-3 h-3 text-slate-500" aria-hidden="true" />
                  <span>Manufacture Date</span>
                </span>
                <span className="text-xs font-medium text-slate-800 mt-1 block tabular-nums">
                  {formatCalendarDate(dossier.manufactureDate)}
                </span>
              </div>

              <div className="bg-white border border-slate-200 rounded-lg p-3">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                  <Calendar className="w-3 h-3 text-slate-500" aria-hidden="true" />
                  <span>Expiry Date</span>
                </span>
                <span className="text-xs font-medium text-slate-800 mt-1 block tabular-nums">
                  {formatCalendarDate(dossier.expiryDate)}
                </span>
              </div>
            </div>

            {/* QA Release Eligibility Banner */}
            {!isFinalized && (
              <div
                className={`rounded-lg border p-3.5 flex items-start space-x-3 text-xs ${
                  eligibility.isEligible
                    ? 'bg-emerald-50 border-emerald-200 text-emerald-900'
                    : 'bg-amber-50 border-amber-200 text-amber-900'
                }`}
              >
                {eligibility.isEligible ? (
                  <CheckCircle2 className="w-4 h-4 text-emerald-600 shrink-0 mt-0.5" aria-hidden="true" />
                ) : (
                  <AlertTriangle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" aria-hidden="true" />
                )}
                <div className="flex-1">
                  <p className="font-semibold text-sm">
                    {eligibility.isEligible
                      ? QA_RELEASE_POLICY_COPY.eligibleBannerTitle
                      : QA_RELEASE_POLICY_COPY.blockedBannerTitle}
                  </p>
                  {eligibility.isEligible ? (
                    <p className="mt-0.5 text-emerald-800">
                      {QA_RELEASE_POLICY_COPY.eligibleBannerDescription}
                    </p>
                  ) : (
                    <div className="mt-1 space-y-2 text-amber-900">
                      <ul className="list-disc pl-4 space-y-0.5 text-amber-800">
                        {releaseBlockers.map((blocker, idx) => (
                          <li key={idx}>{blocker}</li>
                        ))}
                      </ul>
                      <p className="text-[11px] text-amber-900 bg-white/70 border border-amber-200 rounded px-2.5 py-1.5 leading-relaxed">
                        <span className="font-semibold">QA Release Policy:</span>{' '}
                        {QA_RELEASE_POLICY_COPY.ruleSummary}{' '}
                        {QA_RELEASE_POLICY_COPY.blockedPolicyNote}
                      </p>
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>

          {/* Quality Control (QC) Checks Card */}
          <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
            <div className="px-4 sm:px-6 py-4 bg-slate-50/70 border-b border-slate-200 space-y-3">
              <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
                <div>
                  <h3 className="text-base font-semibold text-slate-900 flex items-center space-x-2">
                    <FlaskConical className="w-4 h-4 text-pharma-600" aria-hidden="true" />
                    <span className="tabular-nums">Quality Control (QC) Checks ({qualityChecks.length})</span>
                  </h3>
                  <p className="text-xs text-slate-600 mt-0.5 tabular-nums">
                    Pass: <span className="font-semibold text-emerald-700">{qcCounts.passed}</span>{' '}
                    &bull; Fail: <span className="font-semibold text-rose-700">{qcCounts.failed}</span>{' '}
                    &bull; Pending: <span className="font-semibold text-amber-700">{qcCounts.pending}</span>{' '}
                    &bull; Required:{' '}
                    <span
                      className={`font-semibold ${
                        !isFinalized && qcCounts.required === 0
                          ? 'text-rose-700'
                          : 'text-slate-700'
                      }`}
                    >
                      {qcCounts.required}
                      {!isFinalized && qcCounts.required === 0 ? ' (Missing)' : ''}
                    </span>{' '}
                    &bull; Optional: <span className="font-semibold text-slate-700">{qcCounts.optional}</span>
                  </p>
                  <p className="text-[11px] text-slate-500 mt-1">
                    {QA_RELEASE_POLICY_COPY.qcSectionSubtitle}
                  </p>
                </div>

                {!isFinalized && canRecordQc && (
                  <button
                    type="button"
                    onClick={() =>
                      setQcModalState({ open: true, mode: 'create', check: null })
                    }
                    className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors self-start sm:self-auto"
                  >
                    <Plus className="w-4 h-4" aria-hidden="true" />
                    <span>Add QC Check</span>
                  </button>
                )}
              </div>

              {qualityChecks.length > 0 && (
                <ProgressBar
                  value={qcCounts.passed}
                  max={Math.max(1, qcCounts.total)}
                  label="QC check pass summary"
                  summaryText={`${qcCounts.passed} of ${qcCounts.total} QC checks passed`}
                  showPercentage={true}
                  tone={qcCounts.failed > 0 ? 'danger' : qcCounts.pending > 0 ? 'warning' : 'success'}
                  size="sm"
                />
              )}
            </div>

            {qualityChecks.length === 0 ? (
              <div className="p-10 text-center">
                <FlaskConical className="w-8 h-8 text-slate-300 mx-auto mb-2" aria-hidden="true" />
                <p className="text-sm font-medium text-slate-700">No QC checks recorded yet</p>
                <p className="text-xs text-slate-500 mt-1">
                  Record at least one Required QC check with a PASS result before QA release.
                </p>
              </div>
            ) : (
              <DataTable
                columns={qcColumns}
                data={paginatedQcChecks.items}
                rowKey={(row) => row.id}
                ariaLabel="Quality Control Checks"
                className="border-0 shadow-none rounded-none"
                sortState={qcSortState}
                onSortChange={(nextSort) => {
                  setQcSortState(nextSort);
                  setQcPage(1);
                }}
                pagination={{
                  currentPage: paginatedQcChecks.page,
                  pageSize: paginatedQcChecks.pageSize,
                  totalItems: paginatedQcChecks.totalItems,
                  onPageChange: setQcPage,
                  onPageSizeChange: (nextSize) => {
                    setQcPageSize(nextSize);
                    setQcPage(1);
                  },
                  itemLabel: 'QC checks',
                  hideWhenEmpty: true,
                }}
              />
            )}
          </div>

          {/* Linked Deviations & CAPA Card */}
          <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
            <div className="px-4 sm:px-6 py-4 bg-slate-50/70 border-b border-slate-200 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
              <div>
                <h3 className="text-base font-semibold text-slate-900 flex items-center space-x-2">
                  <AlertTriangle className="w-4 h-4 text-rose-600" aria-hidden="true" />
                  <span className="tabular-nums">Linked Deviations &amp; CAPA ({totalDeviationsCount})</span>
                </h3>
                <p className="text-xs text-slate-500 mt-0.5 tabular-nums">
                  Unresolved:{' '}
                  <span
                    className={`font-semibold ${
                      openDeviationsCount > 0 ? 'text-rose-700' : 'text-emerald-700'
                    }`}
                  >
                    {openDeviationsCount}
                  </span>{' '}
                  &bull; Closed:{' '}
                  <span className="font-semibold text-emerald-700">
                    {Math.max(0, totalDeviationsCount - openDeviationsCount)}
                  </span>
                </p>
              </div>

              {canReportDev && (
                <button
                  type="button"
                  onClick={() =>
                    setDeviationModalState({
                      open: true,
                      prefill: buildDeviationPrefillFromQc(dossier, null),
                    })
                  }
                  className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-xs font-semibold text-white bg-rose-600 hover:bg-rose-700 rounded-lg shadow-sm transition-colors self-start sm:self-auto"
                >
                  <Plus className="w-4 h-4" aria-hidden="true" />
                  <span>Report Deviation</span>
                </button>
              )}
            </div>

            {deviations.length === 0 ? (
              <div className="p-8 text-center">
                <Wrench className="w-8 h-8 text-slate-300 mx-auto mb-2" aria-hidden="true" />
                <p className="text-sm font-medium text-slate-700">No deviations linked to this batch</p>
                <p className="text-xs text-slate-500 mt-1">
                  Deviations can be logged at batch level or from a failed QC check. Any non-CLOSED deviation blocks QA RELEASED disposition.
                </p>
              </div>
            ) : (
              <DataTable
                columns={deviationColumns}
                data={paginatedDeviations.items}
                rowKey={(row) => row.id}
                ariaLabel="Linked Deviations"
                className="border-0 shadow-none rounded-none"
                sortState={devSortState}
                onSortChange={(nextSort) => {
                  setDevSortState(nextSort);
                  setDevPage(1);
                }}
                pagination={{
                  currentPage: paginatedDeviations.page,
                  pageSize: paginatedDeviations.pageSize,
                  totalItems: paginatedDeviations.totalItems,
                  onPageChange: setDevPage,
                  onPageSizeChange: (nextSize) => {
                    setDevPageSize(nextSize);
                    setDevPage(1);
                  },
                  itemLabel: 'deviations',
                  hideWhenEmpty: true,
                }}
              />
            )}
          </div>

          {/* Two-Column Bottom Row: QA Disposition Reviews & Append-Only Audit Trail */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            {/* QA Review History */}
            <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden flex flex-col">
              <div className="px-4 sm:px-5 py-4 bg-slate-50/70 border-b border-slate-200 flex items-center justify-between">
                <h3 className="text-sm font-semibold text-slate-900 flex items-center space-x-2">
                  <ShieldCheck className="w-4 h-4 text-pharma-600" aria-hidden="true" />
                  <span className="tabular-nums">QA Disposition History ({qaReviews.length})</span>
                </h3>
                <span className="text-[11px] font-medium text-slate-500">Immutable Record</span>
              </div>

              {qaReviews.length === 0 ? (
                <div className="p-8 text-center text-xs text-slate-500">
                  No QA disposition decisions recorded yet for this batch.
                </div>
              ) : (
                <>
                  <div className="divide-y divide-slate-200 flex-1">
                    {paginatedReviews.items.map((review) => (
                      <div key={review.id} className="p-4 space-y-2 text-xs">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <div className="flex items-center space-x-2">
                            <span className="text-slate-500 font-mono">
                              {review.previousStatus || 'PENDING_QA'} &rarr;
                            </span>
                            {getQaStatusBadge(review.decisionStatus)}
                          </div>
                          <span className="text-slate-500 tabular-nums">{formatDateTime(review.reviewedAt)}</span>
                        </div>
                        <p className="text-slate-800 bg-slate-50 border border-slate-200 rounded-lg p-2.5 leading-relaxed">
                          {review.rationale}
                        </p>
                        <div className="flex flex-wrap items-center justify-between text-[11px] text-slate-500">
                          <span>
                            Reviewer: <span className="font-semibold text-slate-700">{review.reviewerName}</span>
                          </span>
                          <span className="tabular-nums">
                            QC Snapshot: {review.passedChecksCount} PASS / {review.failedChecksCount} FAIL /{' '}
                            {review.pendingChecksCount} PENDING
                          </span>
                        </div>
                      </div>
                    ))}
                  </div>
                  <Pagination
                    currentPage={paginatedReviews.page}
                    pageSize={paginatedReviews.pageSize}
                    totalItems={paginatedReviews.totalItems}
                    onPageChange={setReviewPage}
                    onPageSizeChange={(nextSize) => {
                      setReviewPageSize(nextSize);
                      setReviewPage(1);
                    }}
                    itemLabel="reviews"
                    hideWhenEmpty={true}
                  />
                </>
              )}
            </div>

            {/* Batch Audit Trail */}
            <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden flex flex-col">
              <div className="px-4 sm:px-5 py-4 bg-slate-50/70 border-b border-slate-200 flex flex-wrap items-center justify-between gap-2">
                <h3 className="text-sm font-semibold text-slate-900 flex items-center space-x-2">
                  <History className="w-4 h-4 text-pharma-600" aria-hidden="true" />
                  <span className="tabular-nums">Batch Audit Trail ({auditLogs.length})</span>
                </h3>
                <span className="inline-flex items-center text-[11px] font-medium text-slate-500">
                  <Lock className="w-3 h-3 mr-1" aria-hidden="true" />
                  Append-Only &bull; Read-Only
                </span>
              </div>

              {auditLogs.length === 0 ? (
                <div className="p-8 text-center text-xs text-slate-500">
                  No audit entries recorded for this batch yet.
                </div>
              ) : (
                <>
                  <div className="divide-y divide-slate-200 flex-1">
                    {paginatedAuditLogs.items.map((log, idx) => {
                      const entryKey = log.id ?? `audit-${idx}`;
                      const snapshotSummary = summarizeAuditChanges(
                        log.beforeValues,
                        log.afterValues
                      );
                      const isExpanded = Boolean(expandedAuditIds[entryKey]);
                      const safeReason = log.reason ? redactAuditText(log.reason) : '';

                      return (
                        <div key={entryKey} className="p-4 space-y-2 text-xs">
                          <div className="flex flex-wrap items-center justify-between gap-2">
                            <span className="font-mono font-bold text-slate-800 bg-slate-100 px-2 py-0.5 rounded border border-slate-200">
                              {log.action}
                            </span>
                            <span className="text-slate-500 tabular-nums">
                              {formatDateTime(log.occurredAt)}
                            </span>
                          </div>
                          <div className="text-slate-600 flex flex-wrap items-center gap-x-3 gap-y-1">
                            <span>
                              Actor: <span className="font-semibold text-slate-800">{log.actorName}</span>
                            </span>
                            <span>
                              Target:{' '}
                              <span className="font-mono tabular-nums">
                                {log.entityType} #{log.entityId}
                              </span>
                            </span>
                          </div>
                          {safeReason && <p className="text-slate-700">{safeReason}</p>}

                          <div className="flex flex-wrap items-center gap-2">
                            <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-slate-100 text-slate-600 border border-slate-200 tabular-nums">
                              {snapshotSummary.label}
                            </span>
                            {snapshotSummary.hasSnapshot && (
                              <button
                                type="button"
                                aria-expanded={isExpanded}
                                onClick={() => toggleAuditRowDiff(entryKey)}
                                className="inline-flex items-center space-x-1 px-2 py-0.5 rounded text-[11px] font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 transition-colors"
                              >
                                {isExpanded ? (
                                  <>
                                    <ChevronDown className="w-3 h-3" aria-hidden="true" />
                                    <span>Hide Diff</span>
                                  </>
                                ) : (
                                  <>
                                    <ChevronRight className="w-3 h-3" aria-hidden="true" />
                                    <span>Inspect Diff</span>
                                  </>
                                )}
                              </button>
                            )}
                          </div>

                          {isExpanded && snapshotSummary.hasSnapshot && (
                            <AuditDiffViewer
                              beforeValues={log.beforeValues}
                              afterValues={log.afterValues}
                              className="pt-1"
                            />
                          )}
                        </div>
                      );
                    })}
                  </div>
                  <Pagination
                    currentPage={paginatedAuditLogs.page}
                    pageSize={paginatedAuditLogs.pageSize}
                    totalItems={paginatedAuditLogs.totalItems}
                    onPageChange={setAuditPage}
                    onPageSizeChange={(nextSize) => {
                      setAuditPageSize(nextSize);
                      setAuditPage(1);
                    }}
                    itemLabel="audit entries"
                    hideWhenEmpty={true}
                  />
                </>
              )}
            </div>
          </div>
        </>
      )}

      {/* Modals & Read-Only eBR Drawer */}
      {canRecordQc && (
        <QualityCheckModal
          isOpen={qcModalState.open}
          mode={qcModalState.mode}
          batchId={dossier?.batchId ?? batchId}
          batchNumber={dossier?.batchNumber}
          existingCheck={qcModalState.check}
          onClose={() => setQcModalState({ open: false, mode: 'create', check: null })}
          onSuccess={handleQcSaved}
        />
      )}

      {canDisposeBatch && (
        <QaDispositionModal
          isOpen={dispositionModal.open}
          dossier={dossier}
          initialDecision={dispositionModal.decision}
          onClose={() => setDispositionModal({ open: false, decision: 'RELEASED' })}
          onSuccess={handleDispositionSaved}
        />
      )}

      <ExecutionHistoryModal
        isOpen={showEbrModal}
        batch={
          dossier
            ? {
                id: dossier.batchId,
                batchNumber: dossier.batchNumber,
                status: dossier.batchStatus,
              }
            : null
        }
        batchContext={dossier}
        fromQualityDossier={true}
        onClose={() => setShowEbrModal(false)}
      />

      {canReportDev && (
        <ReportDeviationModal
          isOpen={deviationModalState.open}
          prefillData={deviationModalState.prefill}
          lockBatchSelection={true}
          onClose={() => setDeviationModalState({ open: false, prefill: null })}
          onSuccess={handleDeviationCreated}
        />
      )}

      <BatchQrModal
        isOpen={showQrModal}
        batchId={dossier?.batchId}
        batchNumber={dossier?.batchNumber}
        onClose={() => setShowQrModal(false)}
      />
    </div>
  );
}
