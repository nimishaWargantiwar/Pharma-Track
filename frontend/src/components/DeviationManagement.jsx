import React, { useState, useEffect, useCallback, useMemo, useId } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  ClipboardCheck,
  Clock,
  RefreshCw,
  Search,
  Plus,
  ArrowRight,
  Wrench,
  Filter,
  RotateCcw,
} from 'lucide-react';
import { getDeviations } from '../services/deviationApi';
import {
  DEVIATION_CATEGORY_OPTIONS,
  DEVIATION_LIFECYCLE_STAGES,
  DEVIATION_PRIORITY_OPTIONS,
  DEVIATION_SEVERITY_OPTIONS,
  filterDeviationsBySearch,
  getCategoryLabel,
  getDeviationStageIndex,
  summarizeDeviationCounts,
} from '../utils/deviationUtils';
import {
  getDeviationPriorityBadge,
  getDeviationSeverityBadge,
  getDeviationStatusBadge,
} from './DeviationBadges';
import {
  DataTable,
  DEFAULT_PAGE_SIZE,
  InlineAlert,
  paginateItems,
  ProgressBar,
  StatusBadge,
} from './ui/index.js';
import ReportDeviationModal from './ReportDeviationModal';
import { useAuth } from '../context/AuthContext.jsx';
import { canReportDeviation } from '../utils/rbacUtils.js';
import { formatAppShortDateTime } from '../utils/dateTimeUtils.js';

function formatShortDate(dateTimeString) {
  return formatAppShortDateTime(dateTimeString);
}

const SEVERITY_ORDER = {
  CRITICAL: 3,
  MAJOR: 2,
  MINOR: 1,
};

export default function DeviationManagement({
  onSelectDeviation,
  onDeviationsChanged,
  onNotify,
  refreshSignal = 0,
}) {
  const { role } = useAuth();
  const allowReportDeviation = canReportDeviation(role);

  const searchInputId = useId();
  const severitySelectId = useId();
  const prioritySelectId = useId();
  const categorySelectId = useId();

  const [allDeviations, setAllDeviations] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);

  const [statusFilter, setStatusFilter] = useState('ALL');
  const [severityFilter, setSeverityFilter] = useState('ALL');
  const [priorityFilter, setPriorityFilter] = useState('ALL');
  const [categoryFilter, setCategoryFilter] = useState('ALL');
  const [searchTerm, setSearchTerm] = useState('');
  const [sortState, setSortState] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  const [showReportModal, setShowReportModal] = useState(false);

  const hasActiveFilters =
    statusFilter !== 'ALL' ||
    severityFilter !== 'ALL' ||
    priorityFilter !== 'ALL' ||
    categoryFilter !== 'ALL' ||
    Boolean(searchTerm.trim());

  const loadDeviations = useCallback(
    async ({ showSpinner = true, suppressErrorToast = false } = {}) => {
      if (showSpinner) {
        setIsLoading(true);
      }
      setError(null);
      try {
        const data = await getDeviations({});
        setAllDeviations(Array.isArray(data) ? data : []);
      } catch (err) {
        const message = err.message || 'Unable to load deviations';
        setError(message);
        if (!suppressErrorToast && onNotify) {
          onNotify({ type: 'error', message });
        }
      } finally {
        if (showSpinner) {
          setIsLoading(false);
        }
      }
    },
    [onNotify]
  );

  useEffect(() => {
    loadDeviations();
  }, [loadDeviations, refreshSignal]);

  const counts = useMemo(
    () => summarizeDeviationCounts(allDeviations),
    [allDeviations]
  );

  // Filter across full dataset BEFORE sorting and pagination
  const filteredDeviations = useMemo(() => {
    const attributeFiltered = allDeviations.filter((item) => {
      if (!item) return false;
      if (statusFilter !== 'ALL' && item.status !== statusFilter) {
        return false;
      }
      if (severityFilter !== 'ALL' && item.severity !== severityFilter) {
        return false;
      }
      if (priorityFilter !== 'ALL' && item.priority !== priorityFilter) {
        return false;
      }
      if (categoryFilter !== 'ALL' && item.category !== categoryFilter) {
        return false;
      }
      return true;
    });
    return filterDeviationsBySearch(attributeFiltered, searchTerm);
  }, [allDeviations, statusFilter, severityFilter, priorityFilter, categoryFilter, searchTerm]);

  // Sort across full filtered dataset BEFORE pagination
  const sortedDeviations = useMemo(() => {
    if (!sortState?.key) {
      return filteredDeviations;
    }
    const dir = sortState.direction === 'desc' ? -1 : 1;
    return [...filteredDeviations].sort((a, b) => {
      let valA;
      let valB;
      if (sortState.key === 'severity') {
        valA = SEVERITY_ORDER[a?.severity] ?? 0;
        valB = SEVERITY_ORDER[b?.severity] ?? 0;
      } else if (sortState.key === 'status') {
        valA = getDeviationStageIndex(a?.status);
        valB = getDeviationStageIndex(b?.status);
      } else if (sortState.key === 'capaProgress') {
        const activeA = Math.max(0, Number(a?.totalCapaCount || 0) - Number(a?.cancelledCapaCount || 0));
        const activeB = Math.max(0, Number(b?.totalCapaCount || 0) - Number(b?.cancelledCapaCount || 0));
        valA = activeA > 0 ? Number(a?.completedCapaCount || 0) / activeA : -1;
        valB = activeB > 0 ? Number(b?.completedCapaCount || 0) / activeB : -1;
      } else {
        valA = a?.[sortState.key];
        valB = b?.[sortState.key];
      }

      if (valA == null && valB == null) return 0;
      if (valA == null) return -1 * dir;
      if (valB == null) return 1 * dir;
      if (typeof valA === 'number' && typeof valB === 'number') {
        return (valA - valB) * dir;
      }
      return String(valA).localeCompare(String(valB), undefined, { numeric: true }) * dir;
    });
  }, [filteredDeviations, sortState]);

  const paginatedResult = useMemo(
    () => paginateItems(sortedDeviations, currentPage, pageSize),
    [sortedDeviations, currentPage, pageSize]
  );

  useEffect(() => {
    if (paginatedResult.page !== currentPage) {
      setCurrentPage(paginatedResult.page);
    }
  }, [paginatedResult.page, currentPage]);

  const handleResetFilters = () => {
    setStatusFilter('ALL');
    setSeverityFilter('ALL');
    setPriorityFilter('ALL');
    setCategoryFilter('ALL');
    setSearchTerm('');
    setCurrentPage(1);
  };

  const handleDeviationCreated = async (created) => {
    if (onNotify) {
      onNotify({
        type: 'success',
        message: `Deviation ${created?.deviationNumber || ''} reported against batch ${
          created?.batchNumber || ''
        }.`,
      });
    }
    setCurrentPage(1);
    await loadDeviations({ showSpinner: false, suppressErrorToast: true });
    if (onDeviationsChanged) {
      await onDeviationsChanged();
    }
  };

  const columns = useMemo(
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
              {formatShortDate(dev.reportedAt)}
            </div>
          </div>
        ),
      },
      {
        key: 'batchNumber',
        header: 'Batch & Product',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top',
        render: (dev) => (
          <div>
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="font-mono font-semibold text-pharma-700 tabular-nums">
                {dev.batchNumber || '—'}
              </span>
              {dev.batchStatus && (
                <StatusBadge
                  status={dev.batchStatus}
                  label={`Mfg: ${dev.batchStatus}`}
                  domain="order"
                  size="xs"
                />
              )}
            </div>
            <div className="text-xs font-medium text-slate-800 mt-0.5">
              {dev.productName || '—'}
            </div>
            {dev.orderNumber && (
              <div className="text-[11px] font-mono text-slate-500 tabular-nums">
                {dev.orderNumber}
              </div>
            )}
          </div>
        ),
      },
      {
        key: 'title',
        header: 'Title & Source',
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
                  QC: {dev.linkedCheckTestName} ({dev.linkedCheckResult})
                </span>
              )}
              {dev.linkedStepNumber != null && (
                <span className="text-[11px] px-1.5 py-0.5 rounded bg-indigo-50 text-indigo-700 border border-indigo-200 tabular-nums">
                  eBR Step #{dev.linkedStepNumber}
                </span>
              )}
              {dev.status && dev.status !== 'CLOSED' && (
                <span className="text-[11px] px-1.5 py-0.5 rounded bg-amber-50 text-amber-800 border border-amber-200 font-semibold">
                  Blocks QA Release
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
        header: 'Lifecycle Status',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'whitespace-nowrap align-top',
        render: (dev) => {
          const stageIdx = getDeviationStageIndex(dev.status);
          return (
            <div className="space-y-1">
              {getDeviationStatusBadge(dev.status)}
              {stageIdx >= 0 && (
                <div className="text-[11px] text-slate-500 font-medium tabular-nums">
                  Stage {stageIdx + 1} of {DEVIATION_LIFECYCLE_STAGES.length}
                </div>
              )}
            </div>
          );
        },
      },
      {
        key: 'capaProgress',
        header: 'CAPA Progress',
        sortable: true,
        headerClassName: 'whitespace-nowrap',
        cellClassName: 'min-w-[165px] align-top text-xs',
        render: (dev) => {
          const activeCount = Math.max(
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
                max={Math.max(1, activeCount)}
                label={`CAPA completion for ${dev.deviationNumber}`}
                summaryText={`${dev.completedCapaCount || 0} / ${activeCount} Active Done`}
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
        headerClassName: 'pr-4 sm:pr-6 whitespace-nowrap',
        cellClassName: 'pr-4 sm:pr-6 whitespace-nowrap align-top',
        render: (dev) => (
          <button
            type="button"
            onClick={() => onSelectDeviation && onSelectDeviation(dev.id)}
            className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors"
          >
            <span>Open Workspace</span>
            <ArrowRight className="w-3.5 h-3.5" aria-hidden="true" />
          </button>
        ),
      },
    ],
    [onSelectDeviation]
  );

  return (
    <div className="space-y-6">
      {/* KPI Summary Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-white p-4 sm:p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Unresolved (QA Blocking)
            </p>
            <p className="text-2xl font-extrabold text-rose-600 mt-1 tabular-nums">
              {counts.UNRESOLVED}
            </p>
            <p className="text-[11px] text-slate-500 mt-0.5 tabular-nums">
              {counts.CRITICAL} Critical active
            </p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-rose-50 text-rose-600 flex items-center justify-center">
            <AlertTriangle className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-4 sm:p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Open / Investigating / RCA
            </p>
            <p className="text-2xl font-extrabold text-amber-600 mt-1 tabular-nums">
              {counts.OPEN + counts.UNDER_INVESTIGATION + counts.ROOT_CAUSE_IDENTIFIED}
            </p>
            <p className="text-[11px] text-slate-500 mt-0.5 tabular-nums">
              {counts.OPEN} Open &bull; {counts.UNDER_INVESTIGATION} Investigating
            </p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-amber-50 text-amber-600 flex items-center justify-center">
            <Clock className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-4 sm:p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              CAPA &amp; Verification
            </p>
            <p className="text-2xl font-extrabold text-pharma-600 mt-1 tabular-nums">
              {counts.CAPA_IN_PROGRESS + counts.PENDING_VERIFICATION}
            </p>
            <p className="text-[11px] text-slate-500 mt-0.5 tabular-nums">
              {counts.PENDING_VERIFICATION} Pending Verify
              {counts.OVERDUE_CAPAS > 0 ? ` • ${counts.OVERDUE_CAPAS} Overdue CAPA` : ''}
            </p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-pharma-50 text-pharma-600 flex items-center justify-center">
            <Wrench className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-4 sm:p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Closed Deviations
            </p>
            <p className="text-2xl font-extrabold text-emerald-600 mt-1 tabular-nums">
              {counts.CLOSED}
            </p>
            <p className="text-[11px] text-slate-500 mt-0.5 tabular-nums">
              Total logged: {counts.ALL}
            </p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-emerald-50 text-emerald-600 flex items-center justify-center">
            <CheckCircle2 className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>
      </div>

      {/* Filter, Search & Primary CTA Toolbar */}
      <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm space-y-3">
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-3">
          {/* Status Pills */}
          <div className="flex flex-wrap items-center gap-1.5">
            <button
              type="button"
              aria-pressed={statusFilter === 'ALL'}
              onClick={() => {
                setStatusFilter('ALL');
                setCurrentPage(1);
              }}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors tabular-nums ${
                statusFilter === 'ALL'
                  ? 'bg-pharma-600 text-white shadow-sm'
                  : 'bg-slate-100 text-slate-600 hover:bg-slate-200 hover:text-slate-900'
              }`}
            >
              All ({counts.ALL})
            </button>
            {DEVIATION_LIFECYCLE_STAGES.map((stage) => (
              <button
                key={stage.key}
                type="button"
                aria-pressed={statusFilter === stage.key}
                onClick={() => {
                  setStatusFilter(stage.key);
                  setCurrentPage(1);
                }}
                className={`px-2.5 py-1.5 rounded-lg text-xs font-semibold transition-colors tabular-nums ${
                  statusFilter === stage.key
                    ? 'bg-pharma-600 text-white shadow-sm'
                    : 'bg-slate-100 text-slate-600 hover:bg-slate-200 hover:text-slate-900'
                }`}
              >
                {stage.shortLabel} ({counts[stage.key] ?? 0})
              </button>
            ))}
          </div>

          {allowReportDeviation && (
            <button
              type="button"
              onClick={() => setShowReportModal(true)}
              className="inline-flex items-center space-x-1.5 px-4 py-2 text-xs font-semibold text-white bg-rose-600 hover:bg-rose-700 rounded-lg shadow-sm transition-colors self-start lg:self-auto"
            >
              <Plus className="w-4 h-4" aria-hidden="true" />
              <span>Report Deviation</span>
            </button>
          )}
        </div>

        {/* Attribute Dropdowns + Text Search */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-2.5 pt-2 border-t border-slate-100">
          <div className="relative lg:col-span-2">
            <label htmlFor={searchInputId} className="sr-only">
              Search deviations
            </label>
            <Search
              className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2"
              aria-hidden="true"
            />
            <input
              id={searchInputId}
              type="text"
              value={searchTerm}
              onChange={(e) => {
                setSearchTerm(e.target.value);
                setCurrentPage(1);
              }}
              placeholder="Search deviation #, batch #, product, title, reporter..."
              className="w-full pl-9 pr-12 py-2 text-xs rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 bg-white"
            />
            {searchTerm && (
              <button
                type="button"
                onClick={() => {
                  setSearchTerm('');
                  setCurrentPage(1);
                }}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-[11px] font-medium text-slate-500 hover:text-slate-700"
              >
                Clear
              </button>
            )}
          </div>

          <div>
            <label htmlFor={severitySelectId} className="sr-only">
              Filter by severity
            </label>
            <select
              id={severitySelectId}
              aria-label="Filter by severity"
              value={severityFilter}
              onChange={(e) => {
                setSeverityFilter(e.target.value);
                setCurrentPage(1);
              }}
              className="w-full px-3 py-2 text-xs font-medium rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              <option value="ALL">All Severities</option>
              {DEVIATION_SEVERITY_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  Severity: {opt.label}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor={prioritySelectId} className="sr-only">
              Filter by priority
            </label>
            <select
              id={prioritySelectId}
              aria-label="Filter by priority"
              value={priorityFilter}
              onChange={(e) => {
                setPriorityFilter(e.target.value);
                setCurrentPage(1);
              }}
              className="w-full px-3 py-2 text-xs font-medium rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              <option value="ALL">All Priorities</option>
              {DEVIATION_PRIORITY_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  Priority: {opt.label}
                </option>
              ))}
            </select>
          </div>

          <div className="flex items-center space-x-2">
            <label htmlFor={categorySelectId} className="sr-only">
              Filter by category
            </label>
            <select
              id={categorySelectId}
              aria-label="Filter by category"
              value={categoryFilter}
              onChange={(e) => {
                setCategoryFilter(e.target.value);
                setCurrentPage(1);
              }}
              className="flex-1 px-3 py-2 text-xs font-medium rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              <option value="ALL">All Categories</option>
              {DEVIATION_CATEGORY_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>

            {hasActiveFilters && (
              <button
                type="button"
                onClick={handleResetFilters}
                title="Reset all filters"
                aria-label="Reset all filters"
                className="p-2 text-slate-600 hover:text-slate-900 bg-slate-100 hover:bg-slate-200 rounded-lg transition-colors"
              >
                <RotateCcw className="w-3.5 h-3.5" aria-hidden="true" />
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Error State */}
      {error && !isLoading && (
        <InlineAlert
          variant="error"
          role="alert"
          title="Unable to load deviations"
          message={error}
          actions={
            <button
              type="button"
              onClick={() => loadDeviations({ showSpinner: true })}
              className="text-xs font-semibold px-3 py-1.5 bg-white border border-rose-300 rounded-lg text-rose-800 hover:bg-rose-50"
            >
              Retry
            </button>
          }
        />
      )}

      {/* List / Loading / Empty States */}
      {isLoading ? (
        <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-12 text-center">
          <RefreshCw
            className="w-8 h-8 mx-auto text-pharma-600 animate-spin mb-3"
            aria-hidden="true"
          />
          <p className="text-sm font-medium text-slate-700">Loading deviations &amp; CAPA records...</p>
        </div>
      ) : allDeviations.length === 0 && !error ? (
        <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-12 text-center">
          <ClipboardCheck className="w-10 h-10 mx-auto text-slate-300 mb-3" aria-hidden="true" />
          <h3 className="text-base font-semibold text-slate-800">No deviations recorded yet</h3>
          <p className="text-sm text-slate-500 mt-1 max-w-md mx-auto">
            Deviations logged against IN_PROGRESS or COMPLETED manufacturing batches appear here for investigation and CAPA tracking.
          </p>
          {allowReportDeviation && (
            <button
              type="button"
              onClick={() => setShowReportModal(true)}
              className="mt-4 inline-flex items-center space-x-1.5 px-4 py-2 text-xs font-semibold text-white bg-rose-600 hover:bg-rose-700 rounded-lg shadow-sm"
            >
              <Plus className="w-4 h-4" aria-hidden="true" />
              <span>Report First Deviation</span>
            </button>
          )}
        </div>
      ) : allDeviations.length > 0 && sortedDeviations.length === 0 ? (
        <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-12 text-center">
          <Filter className="w-8 h-8 mx-auto text-slate-300 mb-2" aria-hidden="true" />
          <h3 className="text-base font-semibold text-slate-800">
            No deviations match current filters
          </h3>
          <button
            type="button"
            onClick={handleResetFilters}
            className="mt-3 px-3.5 py-1.5 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg"
          >
            Reset Filters
          </button>
        </div>
      ) : sortedDeviations.length > 0 ? (
        <DataTable
          columns={columns}
          data={paginatedResult.items}
          rowKey={(row) => row.id}
          ariaLabel="Deviations and CAPA Table"
          sortState={sortState}
          onSortChange={(nextSort) => {
            setSortState(nextSort);
            setCurrentPage(1);
          }}
          pagination={{
            currentPage: paginatedResult.page,
            pageSize: paginatedResult.pageSize,
            totalItems: paginatedResult.totalItems,
            onPageChange: setCurrentPage,
            onPageSizeChange: (nextSize) => {
              setPageSize(nextSize);
              setCurrentPage(1);
            },
            itemLabel: 'deviations',
          }}
        />
      ) : null}

      {/* Report Deviation Modal */}
      {allowReportDeviation && (
        <ReportDeviationModal
          isOpen={showReportModal}
          onClose={() => setShowReportModal(false)}
          onSuccess={handleDeviationCreated}
        />
      )}
    </div>
  );
}
