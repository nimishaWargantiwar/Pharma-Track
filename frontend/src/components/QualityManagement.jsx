import React, { useState, useEffect, useCallback, useMemo, useRef, useId } from 'react';
import {
  ShieldCheck,
  RefreshCw,
  Search,
  Clock,
  CheckCircle2,
  PauseCircle,
  XCircle,
  ArrowRight,
  History,
  Lock,
  FlaskConical,
  AlertTriangle,
  ChevronDown,
  ChevronRight,
  Filter,
} from 'lucide-react';
import NotificationBanner from './NotificationBanner';
import AccessDeniedView from './AccessDeniedView.jsx';
import QualityBatchDossier, { formatDateTime, getQaStatusBadge } from './QualityBatchDossier';
import DeviationManagement from './DeviationManagement';
import DeviationWorkspace from './DeviationWorkspace';
import {
  AuditDiffViewer,
  DataTable,
  DEFAULT_PAGE_SIZE,
  InlineAlert,
  PageHeader,
  paginateItems,
  redactAuditText,
  StatusBadge,
  summarizeAuditChanges,
} from './ui/index.js';
import { getQualityQueue, getAuditLogs } from '../services/qualityApi';
import { getDeviations } from '../services/deviationApi';
import {
  assessBatchReleaseEligibility,
  QA_RELEASE_POLICY_COPY,
} from '../utils/qualityPolicyUtils';
import { useAuth } from '../context/AuthContext.jsx';
import { useNavigation } from '../context/NavigationContext.jsx';
import { canViewGlobalAuditTrail } from '../utils/rbacUtils.js';

const STATUS_FILTERS = [
  { key: 'ALL', label: 'All Completed' },
  { key: 'PENDING_QA', label: 'Pending QA' },
  { key: 'ON_HOLD', label: 'On Hold' },
  { key: 'RELEASED', label: 'Released' },
  { key: 'REJECTED', label: 'Rejected' },
];

const RELEASE_GATE_FILTERS = [
  { key: 'ALL', label: 'All Release Gates' },
  { key: 'READY', label: 'Eligible for Release' },
  { key: 'BLOCKED', label: 'Release Blocked' },
  { key: 'FINALIZED', label: 'Finalized Disposition' },
];

export default function QualityManagement() {
  const { role } = useAuth();
  const { activeTab, routeParams, routeNonce, navigateToTab } = useNavigation();
  const canViewAudit = canViewGlobalAuditTrail(role);

  const consumedRouteRef = useRef('');
  const queueSearchId = useId();
  const releaseGateFilterId = useId();
  const auditSearchId = useId();
  const auditActionFilterId = useId();
  const auditEntityFilterId = useId();

  const [subView, setSubView] = useState('queue'); // 'queue' | 'deviations' | 'audit'
  const [selectedBatchId, setSelectedBatchId] = useState(null);
  const [selectedDeviationState, setSelectedDeviationState] = useState(null);
  const [deviationRefreshSignal, setDeviationRefreshSignal] = useState(0);

  // Inspection queue state
  const [queueItems, setQueueItems] = useState([]);
  const [isLoadingQueue, setIsLoadingQueue] = useState(true);
  const [queueError, setQueueError] = useState(null);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [releaseGateFilter, setReleaseGateFilter] = useState('ALL');
  const [searchTerm, setSearchTerm] = useState('');
  const [queueSortState, setQueueSortState] = useState(null);
  const [queuePage, setQueuePage] = useState(1);
  const [queuePageSize, setQueuePageSize] = useState(DEFAULT_PAGE_SIZE);

  // Global audit trail state
  const [auditLogs, setAuditLogs] = useState([]);
  const [isLoadingAudit, setIsLoadingAudit] = useState(false);
  const [auditError, setAuditError] = useState(null);
  const [auditSearch, setAuditSearch] = useState('');
  const [auditActionFilter, setAuditActionFilter] = useState('ALL');
  const [auditEntityFilter, setAuditEntityFilter] = useState('ALL');
  const [auditSortState, setAuditSortState] = useState({ key: 'occurredAt', direction: 'desc' });
  const [auditPage, setAuditPage] = useState(1);
  const [auditPageSize, setAuditPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [expandedAuditIds, setExpandedAuditIds] = useState({});

  const [notification, setNotification] = useState(null);

  const loadQueue = useCallback(async ({ showSpinner = true, suppressErrorToast = false } = {}) => {
    if (showSpinner) {
      setIsLoadingQueue(true);
    }
    setQueueError(null);
    try {
      const data = await getQualityQueue();
      setQueueItems(Array.isArray(data) ? data : []);
      setNotification((prev) => (prev?.source === 'queue-load' ? null : prev));
    } catch (err) {
      const message = err.message || 'Unable to load Quality Inspection Queue';
      setQueueError(message);
      if (!suppressErrorToast) {
        setNotification({ type: 'error', message, source: 'queue-load' });
      }
    } finally {
      if (showSpinner) {
        setIsLoadingQueue(false);
      }
    }
  }, []);

  const loadAuditLogs = useCallback(async () => {
    if (!canViewAudit) {
      return;
    }
    setIsLoadingAudit(true);
    setAuditError(null);
    try {
      const data = await getAuditLogs();
      setAuditLogs(Array.isArray(data) ? data : []);
      setNotification((prev) => (prev?.source === 'audit-load' ? null : prev));
    } catch (err) {
      const message = err.message || 'Unable to load audit trail records';
      setAuditError(message);
      setNotification({ type: 'error', message, source: 'audit-load' });
    } finally {
      setIsLoadingAudit(false);
    }
  }, [canViewAudit]);

  useEffect(() => {
    loadQueue();
  }, [loadQueue]);

  const requestedBatchId = routeParams?.get?.('batchId');
  const requestedDeviationId = routeParams?.get?.('deviationId');
  const requestedDeviationNumber = routeParams?.get?.('deviationNumber');
  const requestedView = routeParams?.get?.('view');

  useEffect(() => {
    if (!requestedBatchId && !requestedDeviationId && !requestedDeviationNumber && !requestedView) {
      return;
    }
    const linkKey = `${routeNonce}|${requestedBatchId || ''}|${requestedDeviationId || ''}|${requestedDeviationNumber || ''}|${requestedView || ''}`;
    if (consumedRouteRef.current === linkKey) {
      return;
    }
    consumedRouteRef.current = linkKey;

    const targetDevIdentifier = requestedDeviationId || requestedDeviationNumber;
    if (targetDevIdentifier) {
      const numericDevId = Number(targetDevIdentifier);
      setSelectedBatchId(null);
      if (Number.isFinite(numericDevId) && numericDevId > 0) {
        setSelectedDeviationState({
          id: numericDevId,
          returnToBatchId: null,
        });
      } else {
        setSubView('deviations');
        getDeviations()
          .then((devs) => {
            if (Array.isArray(devs)) {
              const cleanId = String(targetDevIdentifier).trim().toLowerCase();
              const match = devs.find(
                (d) =>
                  String(d?.deviationNumber || '').trim().toLowerCase() === cleanId ||
                  String(d?.id || '').trim().toLowerCase() === cleanId
              );
              if (match?.id) {
                setSelectedDeviationState({ id: match.id, returnToBatchId: null });
              }
            }
          })
          .catch(() => {});
      }
      return;
    }

    if (requestedBatchId) {
      const numericBatchId = Number(requestedBatchId);
      setSelectedDeviationState(null);
      setSelectedBatchId(
        Number.isFinite(numericBatchId) && numericBatchId > 0 ? numericBatchId : requestedBatchId
      );
      return;
    }

    if (requestedView === 'deviations') {
      setSelectedBatchId(null);
      setSelectedDeviationState(null);
      setSubView('deviations');
    } else if (requestedView === 'audit' && canViewAudit) {
      setSelectedBatchId(null);
      setSelectedDeviationState(null);
      setSubView('audit');
    } else if (requestedView === 'queue') {
      setSelectedBatchId(null);
      setSelectedDeviationState(null);
      setSubView('queue');
    }
  }, [requestedBatchId, requestedDeviationId, requestedDeviationNumber, requestedView, routeNonce, canViewAudit]);

  useEffect(() => {
    if (
      canViewAudit &&
      subView === 'audit' &&
      selectedBatchId == null &&
      selectedDeviationState == null
    ) {
      loadAuditLogs();
    }
  }, [canViewAudit, subView, selectedBatchId, selectedDeviationState, loadAuditLogs]);

  // Pre-compute release eligibility & QC metrics per queue item
  const enrichedQueueItems = useMemo(() => {
    return queueItems.map((item) => ({
      ...item,
      _eligibility: assessBatchReleaseEligibility(item),
    }));
  }, [queueItems]);

  const counts = useMemo(() => {
    const summary = {
      ALL: queueItems.length,
      PENDING_QA: 0,
      ON_HOLD: 0,
      RELEASED: 0,
      REJECTED: 0,
      READY_TO_RELEASE: 0,
    };
    for (const item of enrichedQueueItems) {
      const st = item.qaStatus || 'PENDING_QA';
      if (summary[st] !== undefined) {
        summary[st] += 1;
      }
      if (!item._eligibility.isFinalized && item._eligibility.isEligible) {
        summary.READY_TO_RELEASE += 1;
      }
    }
    return summary;
  }, [queueItems.length, enrichedQueueItems]);

  // Filter across full queue dataset BEFORE pagination
  const filteredQueueItems = useMemo(() => {
    const q = searchTerm.trim().toLowerCase();
    return enrichedQueueItems.filter((item) => {
      const itemQaStatus = item.qaStatus || 'PENDING_QA';
      if (statusFilter !== 'ALL' && itemQaStatus !== statusFilter) {
        return false;
      }
      const eligibility = item._eligibility;
      if (releaseGateFilter === 'READY' && (!eligibility.isEligible || eligibility.isFinalized)) {
        return false;
      }
      if (releaseGateFilter === 'BLOCKED' && (eligibility.isEligible || eligibility.isFinalized)) {
        return false;
      }
      if (releaseGateFilter === 'FINALIZED' && !eligibility.isFinalized) {
        return false;
      }
      if (!q) return true;
      return Boolean(
        item.batchNumber?.toLowerCase().includes(q) ||
          item.orderNumber?.toLowerCase().includes(q) ||
          item.productName?.toLowerCase().includes(q) ||
          item.productCode?.toLowerCase().includes(q) ||
          item.recipeCode?.toLowerCase().includes(q) ||
          itemQaStatus.toLowerCase().includes(q)
      );
    });
  }, [enrichedQueueItems, statusFilter, releaseGateFilter, searchTerm]);

  // Sort across full filtered dataset BEFORE pagination
  const sortedQueueItems = useMemo(() => {
    if (!queueSortState?.key) {
      return filteredQueueItems;
    }
    const dir = queueSortState.direction === 'desc' ? -1 : 1;
    return [...filteredQueueItems].sort((a, b) => {
      let valA;
      let valB;
      if (queueSortState.key === 'actualYield') {
        valA = Number(a?.actualYield ?? -1);
        valB = Number(b?.actualYield ?? -1);
      } else if (queueSortState.key === 'openDeviationsCount') {
        valA = Number(a?.openDeviationsCount ?? 0);
        valB = Number(b?.openDeviationsCount ?? 0);
      } else if (queueSortState.key === 'releaseGate') {
        valA = a._eligibility.statusLabel;
        valB = b._eligibility.statusLabel;
      } else {
        valA = a?.[queueSortState.key];
        valB = b?.[queueSortState.key];
      }

      if (valA == null && valB == null) return 0;
      if (valA == null) return -1 * dir;
      if (valB == null) return 1 * dir;
      if (typeof valA === 'number' && typeof valB === 'number') {
        return (valA - valB) * dir;
      }
      return String(valA).localeCompare(String(valB), undefined, { numeric: true }) * dir;
    });
  }, [filteredQueueItems, queueSortState]);

  const paginatedQueue = useMemo(
    () => paginateItems(sortedQueueItems, queuePage, queuePageSize),
    [sortedQueueItems, queuePage, queuePageSize]
  );

  useEffect(() => {
    if (paginatedQueue.page !== queuePage) {
      setQueuePage(paginatedQueue.page);
    }
  }, [paginatedQueue.page, queuePage]);

  // Audit filter options derived from full auditLogs dataset
  const auditActionOptions = useMemo(() => {
    const actions = new Set();
    for (const entry of auditLogs) {
      if (entry?.action) actions.add(entry.action);
    }
    return Array.from(actions).sort((a, b) => a.localeCompare(b));
  }, [auditLogs]);

  const auditEntityOptions = useMemo(() => {
    const types = new Set();
    for (const entry of auditLogs) {
      if (entry?.entityType) types.add(entry.entityType);
    }
    return Array.from(types).sort((a, b) => a.localeCompare(b));
  }, [auditLogs]);

  // Filter across full audit trail dataset BEFORE pagination
  const filteredAuditLogs = useMemo(() => {
    const q = auditSearch.trim().toLowerCase();
    return auditLogs.filter((entry) => {
      if (!entry) return false;
      if (auditActionFilter !== 'ALL' && entry.action !== auditActionFilter) {
        return false;
      }
      if (auditEntityFilter !== 'ALL' && entry.entityType !== auditEntityFilter) {
        return false;
      }
      if (!q) return true;
      return Boolean(
        entry.entityReference?.toLowerCase().includes(q) ||
          entry.entityType?.toLowerCase().includes(q) ||
          String(entry.entityId ?? '').toLowerCase().includes(q) ||
          entry.action?.toLowerCase().includes(q) ||
          entry.actorName?.toLowerCase().includes(q) ||
          entry.reason?.toLowerCase().includes(q) ||
          entry.beforeValues?.toLowerCase().includes(q) ||
          entry.afterValues?.toLowerCase().includes(q)
      );
    });
  }, [auditLogs, auditSearch, auditActionFilter, auditEntityFilter]);

  // Sort audit logs across full filtered dataset BEFORE pagination
  const sortedAuditLogs = useMemo(() => {
    if (!auditSortState?.key) {
      return filteredAuditLogs;
    }
    const dir = auditSortState.direction === 'desc' ? -1 : 1;
    return [...filteredAuditLogs].sort((a, b) => {
      let valA;
      let valB;
      if (auditSortState.key === 'occurredAt') {
        valA = a?.occurredAt || '';
        valB = b?.occurredAt || '';
      } else {
        valA = a?.[auditSortState.key];
        valB = b?.[auditSortState.key];
      }
      if (valA == null && valB == null) return 0;
      if (valA == null) return -1 * dir;
      if (valB == null) return 1 * dir;
      return String(valA).localeCompare(String(valB), undefined, { numeric: true }) * dir;
    });
  }, [filteredAuditLogs, auditSortState]);

  const paginatedAuditLogs = useMemo(
    () => paginateItems(sortedAuditLogs, auditPage, auditPageSize),
    [sortedAuditLogs, auditPage, auditPageSize]
  );

  useEffect(() => {
    if (paginatedAuditLogs.page !== auditPage) {
      setAuditPage(paginatedAuditLogs.page);
    }
  }, [paginatedAuditLogs.page, auditPage]);

  const hasQueueFilters = Boolean(
    statusFilter !== 'ALL' || releaseGateFilter !== 'ALL' || searchTerm.trim()
  );

  const hasAuditFilters = Boolean(
    auditActionFilter !== 'ALL' || auditEntityFilter !== 'ALL' || auditSearch.trim()
  );

  const handleResetQueueFilters = () => {
    setStatusFilter('ALL');
    setReleaseGateFilter('ALL');
    setSearchTerm('');
    setQueuePage(1);
  };

  const handleResetAuditFilters = () => {
    setAuditActionFilter('ALL');
    setAuditEntityFilter('ALL');
    setAuditSearch('');
    setAuditPage(1);
  };

  const handleBackToQueue = () => {
    setSelectedBatchId(null);
    if (activeTab === 'quality' && requestedBatchId) {
      navigateToTab('quality');
    }
    loadQueue({ showSpinner: true });
  };

  const handleBackFromDeviation = () => {
    const returnBatchId = selectedDeviationState?.returnToBatchId ?? null;
    setSelectedDeviationState(null);
    if (returnBatchId != null) {
      setSelectedBatchId(returnBatchId);
    } else {
      if (activeTab === 'quality' && requestedDeviationId) {
        navigateToTab('quality', { view: 'deviations' });
      }
      setSubView('deviations');
      setDeviationRefreshSignal((n) => n + 1);
    }
  };

  const handleQualityUpdated = useCallback(() => {
    setDeviationRefreshSignal((n) => n + 1);
    return loadQueue({ showSpinner: false, suppressErrorToast: true });
  }, [loadQueue]);

  const handleNotify = useCallback((toast) => {
    setNotification(toast);
  }, []);

  const toggleAuditRowDiff = (logId) => {
    setExpandedAuditIds((prev) => ({
      ...prev,
      [logId]: !prev[logId],
    }));
  };

  const queueColumns = useMemo(
    () => [
      {
        key: 'batchNumber',
        header: 'Batch & Order',
        sortable: true,
        headerClassName: 'sm:px-5 whitespace-nowrap',
        cellClassName: 'sm:px-5 whitespace-nowrap align-top',
        render: (item) => (
          <div>
            <span className="font-mono font-bold text-pharma-700 bg-pharma-50 px-2 py-0.5 rounded border border-pharma-200 inline-block tabular-nums">
              {item.batchNumber}
            </span>
            <div className="mt-1 flex flex-wrap items-center gap-1.5">
              <span className="text-[11px] font-mono font-medium text-slate-500 tabular-nums">
                {item.orderNumber || '—'}
              </span>
              <StatusBadge
                status={item.batchStatus || 'COMPLETED'}
                label={`Mfg: ${item.batchStatus || 'COMPLETED'}`}
                domain="order"
                size="xs"
              />
            </div>
          </div>
        ),
      },
      {
        key: 'productName',
        header: 'Product & Recipe',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'align-top',
        render: (item) => (
          <div>
            <div className="font-semibold text-slate-900">{item.productName || '—'}</div>
            <div className="flex flex-wrap items-center gap-1.5 mt-0.5">
              {item.productCode && (
                <span className="text-[11px] font-mono text-slate-500 tabular-nums">
                  {item.productCode}
                </span>
              )}
              {(item.recipeCode || item.recipeVersion != null) && (
                <>
                  <span className="text-slate-300" aria-hidden="true">&bull;</span>
                  <span className="font-mono text-[11px] text-slate-600 tabular-nums">
                    {item.recipeCode || '—'}
                  </span>
                  {item.recipeVersion != null && (
                    <span className="px-1.5 py-0.5 rounded text-[10px] font-bold bg-indigo-50 text-indigo-700 border border-indigo-200 tabular-nums">
                      v{item.recipeVersion}
                    </span>
                  )}
                </>
              )}
            </div>
          </div>
        ),
      },
      {
        key: 'actualYield',
        header: 'Actual Yield',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top',
        render: (item) => {
          const recovery =
            item.actualYield != null &&
            item.batchSize != null &&
            Number(item.batchSize) > 0
              ? ((Number(item.actualYield) / Number(item.batchSize)) * 100).toFixed(1)
              : null;
          return item.actualYield != null ? (
            <div>
              <span className="font-semibold text-slate-900 tabular-nums">
                {item.actualYield}{' '}
                <span className="text-xs text-slate-500 font-medium">{item.batchUnit || ''}</span>
              </span>
              {recovery && (
                <div className="text-[11px] text-emerald-700 font-medium tabular-nums mt-0.5">
                  {recovery}% of {item.batchSize}
                </div>
              )}
            </div>
          ) : (
            '—'
          );
        },
      },
      {
        key: 'qcSummary',
        header: 'QC Summary',
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top',
        render: (item) => {
          const eligibility = item._eligibility;
          const { qcCounts } = eligibility;
          if (qcCounts.total === 0) {
            return (
              <span className="inline-flex items-center text-xs text-slate-500">
                <FlaskConical className="w-3.5 h-3.5 mr-1" aria-hidden="true" />
                No QC Checks
              </span>
            );
          }
          return (
            <div className="space-y-1">
              <div className="flex flex-wrap items-center gap-1 text-[11px] font-semibold tabular-nums">
                <span className="px-1.5 py-0.5 rounded bg-emerald-50 text-emerald-700 border border-emerald-200">
                  {qcCounts.passed} PASS
                </span>
                {qcCounts.failed > 0 && (
                  <span className="px-1.5 py-0.5 rounded bg-rose-50 text-rose-700 border border-rose-200">
                    {qcCounts.failed} FAIL
                  </span>
                )}
                {qcCounts.pending > 0 && (
                  <span className="px-1.5 py-0.5 rounded bg-amber-50 text-amber-700 border border-amber-200">
                    {qcCounts.pending} PENDING
                  </span>
                )}
              </div>
              <div className="text-[11px] text-slate-500 font-medium tabular-nums">
                <span
                  className={
                    !eligibility.isFinalized && qcCounts.required === 0
                      ? 'text-rose-700 font-semibold'
                      : ''
                  }
                >
                  {qcCounts.required} Required
                </span>
                {' • '}
                <span>{qcCounts.optional} Optional</span>
              </div>
            </div>
          );
        },
      },
      {
        key: 'openDeviationsCount',
        header: 'Deviations',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top',
        render: (item) =>
          item.openDeviationsCount > 0 ? (
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold bg-rose-100 text-rose-800 border border-rose-200 tabular-nums">
              <AlertTriangle className="w-3 h-3 mr-1 text-rose-600" aria-hidden="true" />
              {item.openDeviationsCount} Open
            </span>
          ) : item.totalDeviationsCount > 0 ? (
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold bg-emerald-50 text-emerald-700 border border-emerald-200 tabular-nums">
              {item.totalDeviationsCount} Closed
            </span>
          ) : (
            <span className="text-xs text-slate-500">None</span>
          ),
      },
      {
        key: 'qaStatus',
        header: 'QA Disposition',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top',
        render: (item) => getQaStatusBadge(item.qaStatus),
      },
      {
        key: 'releaseGate',
        header: 'Release Gate',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'min-w-[165px] align-top',
        render: (item) => {
          const eligibility = item._eligibility;
          const gateTitle = eligibility.isFinalized
            ? `Final QA disposition recorded (${item.qaStatus})`
            : eligibility.isEligible
              ? QA_RELEASE_POLICY_COPY.eligibleBannerDescription
              : eligibility.blockers.join('; ');

          if (eligibility.isFinalized) {
            return (
              <span
                title={gateTitle}
                className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold bg-slate-100 text-slate-600 border border-slate-200"
              >
                <Lock className="w-3 h-3 mr-1 text-slate-500" aria-hidden="true" />
                {eligibility.statusLabel}
              </span>
            );
          }

          if (eligibility.isEligible) {
            return (
              <span
                title={gateTitle}
                className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold bg-emerald-100 text-emerald-800 border border-emerald-200"
              >
                <CheckCircle2 className="w-3 h-3 mr-1 text-emerald-600" aria-hidden="true" />
                {eligibility.statusLabel}
              </span>
            );
          }

          return (
            <div className="space-y-1" title={gateTitle}>
              <div className="inline-flex items-center text-[11px] font-semibold text-amber-800">
                <AlertTriangle className="w-3 h-3 mr-1 text-amber-600 shrink-0" aria-hidden="true" />
                <span>Release Blocked</span>
              </div>
              <div className="flex flex-wrap gap-1">
                {eligibility.blockerChips.map((chip) => (
                  <span
                    key={chip.key}
                    className={`px-1.5 py-0.5 rounded text-[10px] font-semibold border whitespace-nowrap ${
                      chip.tone === 'rose'
                        ? 'bg-rose-50 text-rose-700 border-rose-200'
                        : 'bg-amber-50 text-amber-800 border-amber-200'
                    }`}
                  >
                    {chip.label}
                  </span>
                ))}
              </div>
            </div>
          );
        },
      },
      {
        key: 'actions',
        header: 'Actions',
        align: 'right',
        headerClassName: 'pr-4 sm:pr-5 whitespace-nowrap',
        cellClassName: 'pr-4 sm:pr-5 whitespace-nowrap align-top',
        render: (item) => (
          <button
            type="button"
            onClick={() => setSelectedBatchId(item.batchId)}
            className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors"
          >
            <span>Open Dossier</span>
            <ArrowRight className="w-3.5 h-3.5" aria-hidden="true" />
          </button>
        ),
      },
    ],
    []
  );

  const auditColumns = useMemo(
    () => [
      {
        key: 'occurredAt',
        header: 'Timestamp (IST)',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top text-xs text-slate-600 tabular-nums',
        render: (log) => formatDateTime(log.occurredAt),
      },
      {
        key: 'action',
        header: 'Action',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top',
        render: (log) => (
          <span className="font-mono font-bold text-xs text-slate-800 bg-slate-100 px-2 py-0.5 rounded border border-slate-200">
            {log.action}
          </span>
        ),
      },
      {
        key: 'entityReference',
        header: 'Entity Reference',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top',
        render: (log) => (
          <div>
            <div className="font-mono text-xs font-semibold text-pharma-700 tabular-nums">
              {log.entityReference || `#${log.entityId}`}
            </div>
            <div className="text-[11px] text-slate-500 tabular-nums">
              {log.entityType} #{log.entityId}
            </div>
          </div>
        ),
      },
      {
        key: 'actorName',
        header: 'Actor',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top text-xs font-semibold text-slate-800',
        render: (log) => log.actorName || '—',
      },
      {
        key: 'details',
        header: 'Reason & State Diff',
        headerClassName: 'min-w-[240px]',
        cellClassName: 'align-top text-xs min-w-[240px] max-w-xl',
        render: (log) => {
          const snapshotSummary = summarizeAuditChanges(log.beforeValues, log.afterValues);
          const isExpanded = Boolean(expandedAuditIds[log.id]);
          const safeReason = log.reason ? redactAuditText(log.reason) : '';

          return (
            <div className="space-y-2 min-w-0">
              {safeReason && <p className="text-slate-700 leading-relaxed">{safeReason}</p>}

              <div className="flex flex-wrap items-center gap-2">
                <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-slate-100 text-slate-600 border border-slate-200 tabular-nums">
                  {snapshotSummary.label}
                </span>

                {snapshotSummary.hasSnapshot && (
                  <button
                    type="button"
                    aria-expanded={isExpanded}
                    onClick={() => toggleAuditRowDiff(log.id)}
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
        },
      },
    ],
    [expandedAuditIds]
  );

  return (
    <div className="space-y-6 min-w-0">
      <NotificationBanner
        notification={notification}
        onDismiss={() => setNotification(null)}
      />

      {selectedDeviationState != null ? (
        <DeviationWorkspace
          deviationId={selectedDeviationState.id}
          backLabel={
            selectedDeviationState.returnToBatchId != null
              ? 'Back to Batch Dossier'
              : 'Back to Deviations & CAPA'
          }
          onBack={handleBackFromDeviation}
          onOpenBatchDossier={(bId) => {
            setSelectedDeviationState(null);
            setSelectedBatchId(bId);
          }}
          onDeviationUpdated={handleQualityUpdated}
          onNotify={handleNotify}
        />
      ) : selectedBatchId != null ? (
        <QualityBatchDossier
          batchId={selectedBatchId}
          onBack={handleBackToQueue}
          onOpenDeviation={(devId) =>
            setSelectedDeviationState({ id: devId, returnToBatchId: selectedBatchId })
          }
          onQualityUpdated={handleQualityUpdated}
          onNotify={handleNotify}
        />
      ) : (
        <>
          <PageHeader
            eyebrow={
              canViewAudit
                ? 'Quality Control, Deviations & Inspection'
                : 'Quality Control & Deviations'
            }
            icon={<ShieldCheck className="w-6 h-6 text-pharma-600" aria-hidden="true" />}
            title="Quality Management & Deviations"
            badge="QA / QC & CAPA"
            description={
              canViewAudit
                ? 'Inspect completed batches, record QC analytical checks, manage Deviations & CAPA, review global audit records, and record QA disposition.'
                : 'Inspect completed batches, record QC analytical checks, manage Deviations & CAPA, and record QA disposition.'
            }
            actions={
              <>
                <div
                  role="group"
                  aria-label="Quality workspace sections"
                  className="inline-flex flex-wrap rounded-lg border border-slate-200 bg-white p-1 shadow-sm"
                >
                  <button
                    type="button"
                    aria-pressed={subView === 'queue'}
                    onClick={() => setSubView('queue')}
                    className={`inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-md text-xs font-semibold transition-colors ${
                      subView === 'queue'
                        ? 'bg-pharma-600 text-white'
                        : 'text-slate-600 hover:text-slate-900'
                    }`}
                  >
                    <ShieldCheck className="w-3.5 h-3.5" aria-hidden="true" />
                    <span>Inspection Queue</span>
                  </button>
                  <button
                    type="button"
                    aria-pressed={subView === 'deviations'}
                    onClick={() => setSubView('deviations')}
                    className={`inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-md text-xs font-semibold transition-colors ${
                      subView === 'deviations'
                        ? 'bg-pharma-600 text-white'
                        : 'text-slate-600 hover:text-slate-900'
                    }`}
                  >
                    <AlertTriangle className="w-3.5 h-3.5" aria-hidden="true" />
                    <span>Deviations &amp; CAPA</span>
                  </button>
                  {canViewAudit && (
                    <button
                      type="button"
                      aria-pressed={subView === 'audit'}
                      onClick={() => setSubView('audit')}
                      className={`inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-md text-xs font-semibold transition-colors ${
                        subView === 'audit'
                          ? 'bg-pharma-600 text-white'
                          : 'text-slate-600 hover:text-slate-900'
                      }`}
                    >
                      <History className="w-3.5 h-3.5" aria-hidden="true" />
                      <span>Audit Trail</span>
                    </button>
                  )}
                </div>

                <button
                  type="button"
                  onClick={() => {
                    if (subView === 'queue') {
                      loadQueue({ showSpinner: true });
                    } else if (subView === 'deviations') {
                      setDeviationRefreshSignal((n) => n + 1);
                    } else if (canViewAudit) {
                      loadAuditLogs();
                    }
                  }}
                  disabled={
                    (subView === 'queue' && isLoadingQueue) ||
                    (subView === 'audit' && isLoadingAudit)
                  }
                  className="inline-flex items-center space-x-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
                >
                  <RefreshCw
                    className={`w-4 h-4 ${
                      (subView === 'queue' && isLoadingQueue) ||
                      (subView === 'audit' && isLoadingAudit)
                        ? 'animate-spin text-pharma-600'
                        : 'text-slate-500'
                    }`}
                    aria-hidden="true"
                  />
                  <span>Refresh</span>
                </button>
              </>
            }
          />

          {subView === 'queue' ? (
            <>
              {/* KPI Strip */}
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
                <div className="bg-white p-4 sm:p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                      Pending QA Review
                    </p>
                    <p className="text-2xl font-extrabold text-pharma-600 mt-1 tabular-nums">
                      {counts.PENDING_QA}
                    </p>
                    <p className="text-[11px] text-slate-500 mt-0.5 tabular-nums">
                      {counts.READY_TO_RELEASE} eligible for release
                    </p>
                  </div>
                  <div className="w-10 h-10 rounded-lg bg-pharma-50 text-pharma-600 flex items-center justify-center">
                    <Clock className="w-5 h-5" aria-hidden="true" />
                  </div>
                </div>

                <div className="bg-white p-4 sm:p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                      On QA Hold
                    </p>
                    <p className="text-2xl font-extrabold text-amber-600 mt-1 tabular-nums">
                      {counts.ON_HOLD}
                    </p>
                    <p className="text-[11px] text-slate-500 mt-0.5">
                      Requires QC / CAPA resolution
                    </p>
                  </div>
                  <div className="w-10 h-10 rounded-lg bg-amber-50 text-amber-600 flex items-center justify-center">
                    <PauseCircle className="w-5 h-5" aria-hidden="true" />
                  </div>
                </div>

                <div className="bg-white p-4 sm:p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                      QA Released
                    </p>
                    <p className="text-2xl font-extrabold text-emerald-600 mt-1 tabular-nums">
                      {counts.RELEASED}
                    </p>
                    <p className="text-[11px] text-slate-500 mt-0.5">
                      Finalized release disposition
                    </p>
                  </div>
                  <div className="w-10 h-10 rounded-lg bg-emerald-50 text-emerald-600 flex items-center justify-center">
                    <CheckCircle2 className="w-5 h-5" aria-hidden="true" />
                  </div>
                </div>

                <div className="bg-white p-4 sm:p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                      QA Rejected
                    </p>
                    <p className="text-2xl font-extrabold text-rose-600 mt-1 tabular-nums">
                      {counts.REJECTED}
                    </p>
                    <p className="text-[11px] text-slate-500 mt-0.5">
                      Finalized rejection disposition
                    </p>
                  </div>
                  <div className="w-10 h-10 rounded-lg bg-rose-50 text-rose-600 flex items-center justify-center">
                    <XCircle className="w-5 h-5" aria-hidden="true" />
                  </div>
                </div>
              </div>

              {/* Filter & Search Toolbar */}
              <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm space-y-3">
                <div className="flex flex-wrap items-center gap-1.5">
                  {STATUS_FILTERS.map((f) => (
                    <button
                      key={f.key}
                      type="button"
                      aria-pressed={statusFilter === f.key}
                      onClick={() => {
                        setStatusFilter(f.key);
                        setQueuePage(1);
                      }}
                      className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors tabular-nums ${
                        statusFilter === f.key
                          ? 'bg-pharma-600 text-white shadow-sm'
                          : 'bg-slate-100 text-slate-600 hover:bg-slate-200 hover:text-slate-900'
                      }`}
                    >
                      {f.label} ({counts[f.key] ?? 0})
                    </button>
                  ))}
                </div>

                <div className="flex flex-col sm:flex-row sm:items-center gap-2.5 pt-2 border-t border-slate-100">
                  <div className="relative flex-1">
                    <label htmlFor={queueSearchId} className="sr-only">
                      Search quality inspection queue
                    </label>
                    <Search
                      className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2"
                      aria-hidden="true"
                    />
                    <input
                      id={queueSearchId}
                      type="text"
                      value={searchTerm}
                      onChange={(e) => {
                        setSearchTerm(e.target.value);
                        setQueuePage(1);
                      }}
                      placeholder="Search batch, order, product, or recipe..."
                      className="w-full pl-9 pr-12 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 bg-white"
                    />
                    {searchTerm && (
                      <button
                        type="button"
                        onClick={() => {
                          setSearchTerm('');
                          setQueuePage(1);
                        }}
                        className="absolute right-3 top-1/2 -translate-y-1/2 text-xs font-medium text-slate-500 hover:text-slate-700"
                      >
                        Clear
                      </button>
                    )}
                  </div>

                  <div className="flex items-center gap-2">
                    <label htmlFor={releaseGateFilterId} className="sr-only">
                      Filter by release readiness
                    </label>
                    <div className="flex items-center space-x-1.5">
                      <Filter className="w-3.5 h-3.5 text-slate-400 shrink-0" aria-hidden="true" />
                      <select
                        id={releaseGateFilterId}
                        aria-label="Filter by release readiness"
                        value={releaseGateFilter}
                        onChange={(e) => {
                          setReleaseGateFilter(e.target.value);
                          setQueuePage(1);
                        }}
                        className="px-3 py-2 text-xs font-medium rounded-lg border border-slate-300 bg-white text-slate-700 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
                      >
                        {RELEASE_GATE_FILTERS.map((opt) => (
                          <option key={opt.key} value={opt.key}>
                            {opt.label}
                          </option>
                        ))}
                      </select>
                    </div>
                  </div>
                </div>
              </div>

              {/* Queue Error Banner (Initial or Refresh Error) */}
              {queueError && !isLoadingQueue && (
                <InlineAlert
                  variant="error"
                  role="alert"
                  title="Unable to load quality inspection queue"
                  message={queueError}
                  actions={
                    <button
                      type="button"
                      onClick={() => loadQueue({ showSpinner: true })}
                      className="text-xs font-semibold px-3 py-1.5 bg-white border border-rose-300 rounded-lg text-rose-800 hover:bg-rose-50"
                    >
                      Retry
                    </button>
                  }
                />
              )}

              {/* Queue Table / Distinct Loading & Empty States */}
              {isLoadingQueue ? (
                <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-12 text-center">
                  <RefreshCw
                    className="w-8 h-8 mx-auto text-pharma-600 animate-spin mb-3"
                    aria-hidden="true"
                  />
                  <p className="text-sm font-medium text-slate-700">
                    Loading completed batches awaiting QA review...
                  </p>
                </div>
              ) : queueItems.length === 0 && !queueError ? (
                <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-12 text-center">
                  <ShieldCheck
                    className="w-10 h-10 mx-auto text-slate-300 mb-3"
                    aria-hidden="true"
                  />
                  <h3 className="text-base font-semibold text-slate-800">
                    No completed batches in QA queue
                  </h3>
                  <p className="text-sm text-slate-500 mt-1 max-w-md mx-auto">
                    Only completed manufacturing batches appear here for QC check recording and QA batch disposition.
                  </p>
                </div>
              ) : queueItems.length > 0 && sortedQueueItems.length === 0 ? (
                <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-12 text-center">
                  <Search className="w-8 h-8 mx-auto text-slate-300 mb-2" aria-hidden="true" />
                  <h3 className="text-base font-semibold text-slate-800">
                    No matching completed batches
                  </h3>
                  <p className="text-sm text-slate-500 mt-1">
                    No batches match your active QA status, release gate, or search criteria.
                  </p>
                  {hasQueueFilters && (
                    <button
                      type="button"
                      onClick={handleResetQueueFilters}
                      className="mt-3 px-3.5 py-1.5 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg"
                    >
                      Reset Filters
                    </button>
                  )}
                </div>
              ) : sortedQueueItems.length > 0 ? (
                <div className="space-y-3">
                  <div className="bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 flex items-start sm:items-center gap-2 text-xs text-slate-600">
                    <ShieldCheck
                      className="w-4 h-4 text-pharma-600 shrink-0 mt-0.5 sm:mt-0"
                      aria-hidden="true"
                    />
                    <span>
                      <span className="font-semibold text-slate-700">QA Release Gate:</span>{' '}
                      {QA_RELEASE_POLICY_COPY.ruleSummary}
                    </span>
                  </div>

                  <DataTable
                    columns={queueColumns}
                    data={paginatedQueue.items}
                    rowKey={(row) => row.batchId}
                    ariaLabel="Quality Inspection Queue"
                    sortState={queueSortState}
                    onSortChange={(nextSort) => {
                      setQueueSortState(nextSort);
                      setQueuePage(1);
                    }}
                    pagination={{
                      currentPage: paginatedQueue.page,
                      pageSize: paginatedQueue.pageSize,
                      totalItems: paginatedQueue.totalItems,
                      onPageChange: setQueuePage,
                      onPageSizeChange: (nextSize) => {
                        setQueuePageSize(nextSize);
                        setQueuePage(1);
                      },
                      itemLabel: 'batches',
                    }}
                  />
                </div>
              ) : null}
            </>
          ) : subView === 'deviations' ? (
            <DeviationManagement
              onSelectDeviation={(devId) =>
                setSelectedDeviationState({ id: devId, returnToBatchId: null })
              }
              onDeviationsChanged={() => loadQueue({ showSpinner: false, suppressErrorToast: true })}
              onNotify={handleNotify}
              refreshSignal={deviationRefreshSignal}
            />
          ) : !canViewAudit ? (
            <AccessDeniedView
              role={role}
              moduleName="Global Quality Record History"
              description="System-wide quality log access is restricted to Administrators and QA Reviewers."
              onReturnHome={() => setSubView('queue')}
              returnLabel="Return to Inspection Queue"
            />
          ) : (
            /* Global Append-Only Audit Trail View */
            <div className="space-y-4 min-w-0">
              <InlineAlert
                variant="info"
                icon={Lock}
                title="Append-Only Quality Record Log"
                message={`Showing immutable quality management records (${auditLogs.length} total recorded). Edits and deletions are disabled.`}
              />

              {/* Audit Filter Toolbar */}
              <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
                <div className="relative lg:col-span-2">
                  <label htmlFor={auditSearchId} className="sr-only">
                    Search global audit trail
                  </label>
                  <Search
                    className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2"
                    aria-hidden="true"
                  />
                  <input
                    id={auditSearchId}
                    type="text"
                    value={auditSearch}
                    onChange={(e) => {
                      setAuditSearch(e.target.value);
                      setAuditPage(1);
                    }}
                    placeholder="Search reference, actor, action, reason, or snapshot..."
                    className="w-full pl-9 pr-12 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 bg-white"
                  />
                  {auditSearch && (
                    <button
                      type="button"
                      onClick={() => {
                        setAuditSearch('');
                        setAuditPage(1);
                      }}
                      className="absolute right-3 top-1/2 -translate-y-1/2 text-xs font-medium text-slate-500 hover:text-slate-700"
                    >
                      Clear
                    </button>
                  )}
                </div>

                <div>
                  <label htmlFor={auditActionFilterId} className="sr-only">
                    Filter audit records by action
                  </label>
                  <select
                    id={auditActionFilterId}
                    aria-label="Filter by audit action"
                    value={auditActionFilter}
                    onChange={(e) => {
                      setAuditActionFilter(e.target.value);
                      setAuditPage(1);
                    }}
                    className="w-full px-3 py-2 text-xs font-medium rounded-lg border border-slate-300 bg-white text-slate-700 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
                  >
                    <option value="ALL">All Actions ({auditActionOptions.length})</option>
                    {auditActionOptions.map((act) => (
                      <option key={act} value={act}>
                        {act}
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <label htmlFor={auditEntityFilterId} className="sr-only">
                    Filter audit records by entity type
                  </label>
                  <select
                    id={auditEntityFilterId}
                    aria-label="Filter by entity type"
                    value={auditEntityFilter}
                    onChange={(e) => {
                      setAuditEntityFilter(e.target.value);
                      setAuditPage(1);
                    }}
                    className="w-full px-3 py-2 text-xs font-medium rounded-lg border border-slate-300 bg-white text-slate-700 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
                  >
                    <option value="ALL">All Entities ({auditEntityOptions.length})</option>
                    {auditEntityOptions.map((ent) => (
                      <option key={ent} value={ent}>
                        {ent}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              {auditError && !isLoadingAudit && (
                <InlineAlert
                  variant="error"
                  role="alert"
                  title="Unable to load audit trail records"
                  message={auditError}
                  actions={
                    <button
                      type="button"
                      onClick={() => loadAuditLogs()}
                      className="text-xs font-semibold px-3 py-1.5 bg-white border border-rose-300 rounded-lg text-rose-800 hover:bg-rose-50"
                    >
                      Retry
                    </button>
                  }
                />
              )}

              {isLoadingAudit ? (
                <div className="bg-white rounded-xl border border-slate-200 p-12 text-center">
                  <RefreshCw
                    className="w-7 h-7 mx-auto text-pharma-600 animate-spin mb-2"
                    aria-hidden="true"
                  />
                  <p className="text-sm text-slate-600">Loading audit logs...</p>
                </div>
              ) : auditLogs.length === 0 && !auditError ? (
                <div className="bg-white rounded-xl border border-slate-200 p-12 text-center">
                  <History className="w-9 h-9 mx-auto text-slate-300 mb-2" aria-hidden="true" />
                  <p className="text-sm font-medium text-slate-700">No audit trail entries recorded yet</p>
                </div>
              ) : auditLogs.length > 0 && sortedAuditLogs.length === 0 ? (
                <div className="bg-white rounded-xl border border-slate-200 p-12 text-center">
                  <Search className="w-8 h-8 mx-auto text-slate-300 mb-2" aria-hidden="true" />
                  <p className="text-sm font-medium text-slate-700">
                    No matching audit trail entries
                  </p>
                  {hasAuditFilters && (
                    <button
                      type="button"
                      onClick={handleResetAuditFilters}
                      className="mt-3 px-3.5 py-1.5 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg"
                    >
                      Reset Filters
                    </button>
                  )}
                </div>
              ) : sortedAuditLogs.length > 0 ? (
                <DataTable
                  columns={auditColumns}
                  data={paginatedAuditLogs.items}
                  rowKey={(row, idx) => row.id ?? idx}
                  ariaLabel="Global Quality Audit Trail"
                  sortState={auditSortState}
                  onSortChange={(nextSort) => {
                    setAuditSortState(nextSort);
                    setAuditPage(1);
                  }}
                  pagination={{
                    currentPage: paginatedAuditLogs.page,
                    pageSize: paginatedAuditLogs.pageSize,
                    totalItems: paginatedAuditLogs.totalItems,
                    onPageChange: setAuditPage,
                    onPageSizeChange: (nextSize) => {
                      setAuditPageSize(nextSize);
                      setAuditPage(1);
                    },
                    itemLabel: 'audit entries',
                  }}
                />
              ) : null}
            </div>
          )}
        </>
      )}
    </div>
  );
}
