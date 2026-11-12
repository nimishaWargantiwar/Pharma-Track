import React, { useState, useEffect, useCallback, useMemo, useId, useRef } from 'react';
import {
  ClipboardCheck,
  RefreshCw,
  Search,
  Play,
  ClipboardList,
  Box,
  Clock,
  ArrowRight,
  ArrowUpRight,
  RotateCcw,
} from 'lucide-react';
import NotificationBanner from './NotificationBanner';
import ExecutionWorkspace from './ExecutionWorkspace';
import {
  DEFAULT_PAGE_SIZE,
  DataTable,
  InlineAlert,
  PageHeader,
  StatusBadge,
  paginateItems,
} from './ui/index.js';
import { getExecutionQueue } from '../services/productionExecutionApi';
import { useAuth } from '../context/AuthContext.jsx';
import { useNavigation } from '../context/NavigationContext.jsx';
import { canExecuteProductionSteps } from '../utils/rbacUtils.js';
import { formatAppShortDateTime } from '../utils/dateTimeUtils.js';

function formatDateTime(dateTimeString) {
  return formatAppShortDateTime(dateTimeString);
}

export default function ProductionExecution() {
  const { role } = useAuth();
  const { activeTab, routeParams, routeNonce, navigateToTab } = useNavigation();
  const canExecute = canExecuteProductionSteps(role);
  const searchInputId = useId();
  const consumedBatchLinkRef = useRef('');

  const [queueItems, setQueueItems] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
  const [notification, setNotification] = useState(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [sortState, setSortState] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [selectedBatchId, setSelectedBatchId] = useState(null);

  const loadQueue = useCallback(async ({ showSpinner = true, suppressErrorToast = false } = {}) => {
    if (showSpinner) {
      setIsLoading(true);
    }
    setError(null);
    try {
      const data = await getExecutionQueue();
      setQueueItems(Array.isArray(data) ? data : []);
    } catch (err) {
      const errorMessage = err.message || 'Unable to connect to production execution queue API';
      setError(errorMessage);
      if (!suppressErrorToast) {
        setNotification({
          type: 'error',
          message: err.message || 'Failed to load production execution queue from server',
        });
      }
    } finally {
      if (showSpinner) {
        setIsLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    loadQueue();
  }, [loadQueue]);

  // Consume deep-link param (#/execution?batchId=...)
  const requestedBatchId = routeParams?.get?.('batchId');

  useEffect(() => {
    if (!requestedBatchId) {
      return;
    }
    const linkKey = `${routeNonce}|${requestedBatchId}`;
    if (consumedBatchLinkRef.current === linkKey) {
      return;
    }
    consumedBatchLinkRef.current = linkKey;
    const numericId = Number(requestedBatchId);
    setSelectedBatchId(Number.isFinite(numericId) && numericId > 0 ? numericId : requestedBatchId);
  }, [requestedBatchId, routeNonce]);

  // Filter across full dataset BEFORE pagination
  const filteredQueueItems = useMemo(() => {
    const query = searchTerm.trim().toLowerCase();
    if (!query) return queueItems;
    return queueItems.filter((item) => {
      const batchMatch = item.batchNumber?.toLowerCase().includes(query);
      const orderMatch = item.orderNumber?.toLowerCase().includes(query);
      const productMatch = item.productName?.toLowerCase().includes(query);
      const recipeMatch = item.recipeCode?.toLowerCase().includes(query);
      return Boolean(batchMatch || orderMatch || productMatch || recipeMatch);
    });
  }, [queueItems, searchTerm]);

  // Sort across filtered dataset BEFORE pagination
  const sortedQueueItems = useMemo(() => {
    if (!sortState?.key) {
      return filteredQueueItems;
    }
    const dir = sortState.direction === 'desc' ? -1 : 1;
    return [...filteredQueueItems].sort((a, b) => {
      let valA;
      let valB;
      if (sortState.key === 'batchSize') {
        valA = Number(a?.batchSize) || 0;
        valB = Number(b?.batchSize) || 0;
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
  }, [filteredQueueItems, sortState]);

  const paginatedResult = useMemo(
    () => paginateItems(sortedQueueItems, currentPage, pageSize),
    [sortedQueueItems, currentPage, pageSize]
  );

  useEffect(() => {
    if (paginatedResult.page !== currentPage) {
      setCurrentPage(paginatedResult.page);
    }
  }, [paginatedResult.page, currentPage]);

  const distinctOrdersCount = useMemo(
    () => new Set(queueItems.map((i) => i.orderNumber).filter(Boolean)).size,
    [queueItems]
  );

  const distinctProductsCount = useMemo(
    () => new Set(queueItems.map((i) => i.productName).filter(Boolean)).size,
    [queueItems]
  );

  const handleSearchChange = (nextValue) => {
    setSearchTerm(nextValue);
    setCurrentPage(1);
  };

  const handleSortChange = (nextSort) => {
    setSortState(nextSort);
    setCurrentPage(1);
  };

  const handleResetFilters = () => {
    setSearchTerm('');
    setCurrentPage(1);
  };

  const handleOpenWorkspace = (item) => {
    if (!item?.batchId) return;
    setSelectedBatchId(item.batchId);
  };

  const handleBackToQueue = () => {
    setSelectedBatchId(null);
    if (activeTab === 'execution' && requestedBatchId) {
      navigateToTab('execution');
    }
    loadQueue({ showSpinner: true });
  };

  const handleExecutionUpdated = useCallback(() => {
    return loadQueue({ showSpinner: false, suppressErrorToast: true });
  }, [loadQueue]);

  const handleNotify = useCallback((toast) => {
    setNotification(toast);
  }, []);

  const selectedQueueContext = useMemo(
    () => queueItems.find((item) => String(item.batchId) === String(selectedBatchId)) || null,
    [queueItems, selectedBatchId]
  );

  const hasActiveFilters = Boolean(searchTerm.trim());

  const columns = [
    {
      key: 'batchNumber',
      header: 'Batch Number',
      accessor: 'batchNumber',
      sortable: true,
      headerClassName: 'sm:pl-6 whitespace-nowrap',
      cellClassName: 'sm:pl-6 whitespace-nowrap',
      render: (item) => (
        <div>
          <span className="font-mono font-bold text-pharma-700 bg-pharma-50 px-2 py-0.5 rounded border border-pharma-200 inline-block tabular-nums">
            {item.batchNumber || '—'}
          </span>
          <div className="mt-1 text-xs text-slate-500 sm:hidden">
            <span className="font-semibold text-slate-800">{item.productName || '—'}</span>
            {item.orderNumber && (
              <span className="ml-1.5 font-mono tabular-nums">&middot; {item.orderNumber}</span>
            )}
          </div>
        </div>
      ),
    },
    {
      key: 'orderNumber',
      header: 'Order Number',
      accessor: 'orderNumber',
      sortable: true,
      headerClassName: 'hidden sm:table-cell whitespace-nowrap',
      cellClassName: 'hidden sm:table-cell whitespace-nowrap',
      render: (item) =>
        item.orderNumber ? (
          <button
            type="button"
            onClick={() => navigateToTab('orders', { orderNumber: item.orderNumber })}
            title={`Open manufacturing order ${item.orderNumber}`}
            className="inline-flex items-center space-x-1 font-mono font-semibold text-pharma-700 hover:text-pharma-800 hover:underline tabular-nums"
          >
            <span>{item.orderNumber}</span>
            <ArrowUpRight className="w-3 h-3" aria-hidden="true" />
          </button>
        ) : (
          '—'
        ),
    },
    {
      key: 'productName',
      header: 'Product',
      accessor: 'productName',
      sortable: true,
      headerClassName: 'whitespace-nowrap',
      cellClassName: 'whitespace-nowrap font-semibold text-slate-900',
      render: (item) => item.productName || '—',
    },
    {
      key: 'recipeCode',
      header: 'Recipe & Version',
      accessor: 'recipeCode',
      sortable: true,
      headerClassName: 'hidden lg:table-cell whitespace-nowrap',
      cellClassName: 'hidden lg:table-cell whitespace-nowrap',
      render: (item) => (
        <div className="flex items-center space-x-2">
          <span className="font-mono text-xs text-slate-700 tabular-nums">
            {item.recipeCode || '—'}
          </span>
          {item.recipeVersion != null && (
            <span className="px-1.5 py-0.5 rounded text-[11px] font-bold bg-indigo-50 text-indigo-700 border border-indigo-200 tabular-nums">
              v{item.recipeVersion}
            </span>
          )}
        </div>
      ),
    },
    {
      key: 'batchSize',
      header: 'Batch Size',
      accessor: 'batchSize',
      sortable: true,
      sortValue: (item) => Number(item?.batchSize) || 0,
      headerClassName: 'hidden md:table-cell whitespace-nowrap',
      cellClassName: 'hidden md:table-cell whitespace-nowrap tabular-nums',
      render: (item) =>
        item.batchSize != null ? (
          <>
            <span className="font-semibold text-slate-900">{item.batchSize}</span>{' '}
            <span className="text-xs text-slate-500 font-medium">{item.batchUnit || ''}</span>
          </>
        ) : (
          '—'
        ),
    },
    {
      key: 'startTime',
      header: 'Batch Start Time',
      accessor: 'startTime',
      sortable: true,
      headerClassName: 'hidden xl:table-cell whitespace-nowrap',
      cellClassName: 'hidden xl:table-cell whitespace-nowrap text-xs text-slate-600 tabular-nums',
      render: (item) => (
        <div className="flex items-center space-x-1.5">
          <Clock className="w-3.5 h-3.5 text-slate-400" aria-hidden="true" />
          <span>{formatDateTime(item.startTime)}</span>
        </div>
      ),
    },
    {
      key: 'batchStatus',
      header: 'Batch Status',
      accessor: 'batchStatus',
      sortable: true,
      headerClassName: 'whitespace-nowrap',
      cellClassName: 'whitespace-nowrap',
      render: (item) => (
        <StatusBadge
          status={item.batchStatus}
          domain="order"
          showDot
          pulseDot={item.batchStatus === 'IN_PROGRESS'}
        />
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      headerClassName: 'sm:pr-6 whitespace-nowrap',
      cellClassName: 'sm:pr-6 text-right whitespace-nowrap',
      render: (item) => (
        <button
          type="button"
          onClick={() => handleOpenWorkspace(item)}
          className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors"
        >
          <span>{canExecute ? 'Open Workspace' : 'View Workspace'}</span>
          <ArrowRight className="w-3.5 h-3.5" aria-hidden="true" />
        </button>
      ),
    },
  ];

  const emptyState = hasActiveFilters ? (
    <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-12 text-center">
      <div className="w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center mx-auto mb-3 text-slate-400">
        <Search className="w-6 h-6" aria-hidden="true" />
      </div>
      <h3 className="text-base font-semibold text-slate-800">No matching batches found</h3>
      <p className="text-sm text-slate-500 mt-1 max-w-sm mx-auto">
        No active batches match &ldquo;{searchTerm}&rdquo;.
      </p>
      <button
        type="button"
        onClick={handleResetFilters}
        className="mt-4 inline-flex items-center space-x-1.5 px-3.5 py-2 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg transition-colors"
      >
        <RotateCcw className="w-3.5 h-3.5" aria-hidden="true" />
        <span>Reset Filters</span>
      </button>
    </div>
  ) : (
    <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-12 text-center">
      <div className="w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center mx-auto mb-3 text-slate-400">
        <ClipboardCheck className="w-6 h-6" aria-hidden="true" />
      </div>
      <h3 className="text-base font-semibold text-slate-800">
        No active batches in execution queue
      </h3>
      <p className="text-sm text-slate-500 mt-1 max-w-md mx-auto">
        Only active IN_PROGRESS batches belonging to an IN_PROGRESS manufacturing order appear here.
        Start a released batch in Manufacturing Orders or inspect finished batches in Quality Management.
      </p>
      <div className="mt-5 flex flex-wrap items-center justify-center gap-2.5">
        <button
          type="button"
          onClick={() => navigateToTab('orders')}
          className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors"
        >
          <span>Go to Manufacturing Orders</span>
          <ArrowUpRight className="w-3.5 h-3.5" aria-hidden="true" />
        </button>
        <button
          type="button"
          onClick={() => navigateToTab('quality')}
          className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-xs font-semibold text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors"
        >
          <span>Open Quality Management</span>
          <ArrowUpRight className="w-3.5 h-3.5" aria-hidden="true" />
        </button>
      </div>
    </div>
  );

  return (
    <div className="space-y-6">
      <NotificationBanner
        notification={notification}
        onDismiss={() => setNotification(null)}
      />

      {selectedBatchId != null ? (
        <ExecutionWorkspace
          batchId={selectedBatchId}
          batchContext={selectedQueueContext}
          readOnly={!canExecute}
          readOnlyReason={!canExecute ? 'role' : 'history'}
          onBack={handleBackToQueue}
          onExecutionUpdated={handleExecutionUpdated}
          onNotify={handleNotify}
        />
      ) : (
        <>
          <PageHeader
            title="Production Execution"
            badge={
              <div className="flex flex-wrap items-center gap-2">
                <StatusBadge
                  status="IN_PROGRESS"
                  tone="warning"
                  label="Shop-Floor eBR"
                />
                {!canExecute && (
                  <StatusBadge
                    status="Monitoring"
                    tone="muted"
                    label="Read-Only Monitoring"
                  />
                )}
              </div>
            }
            description={
              canExecute
                ? 'Execute active batch recipe operations step-by-step and record verified in-process values.'
                : 'Monitor active batch recipe progress and eBR step records (step execution is restricted to Operators).'
            }
            actions={
              <>
                <button
                  type="button"
                  onClick={() => navigateToTab('orders')}
                  className="inline-flex items-center space-x-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors"
                >
                  <span>Manufacturing Orders</span>
                  <ArrowUpRight className="w-4 h-4 text-slate-500" aria-hidden="true" />
                </button>
                <button
                  type="button"
                  onClick={() => navigateToTab('quality')}
                  className="inline-flex items-center space-x-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors"
                >
                  <span>Quality Management</span>
                  <ArrowUpRight className="w-4 h-4 text-slate-500" aria-hidden="true" />
                </button>
                <button
                  type="button"
                  onClick={() => loadQueue({ showSpinner: true })}
                  disabled={isLoading}
                  title="Refresh execution queue"
                  className="inline-flex items-center space-x-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
                >
                  <RefreshCw
                    className={`w-4 h-4 ${isLoading ? 'animate-spin text-pharma-600' : 'text-slate-500'}`}
                    aria-hidden="true"
                  />
                  <span>Refresh Queue</span>
                </button>
              </>
            }
          />

          {/* Batch Lifecycle Flow Strip */}
          <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-4">
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-xs">
              <div className="rounded-lg border border-pharma-100 bg-pharma-50/40 p-3 flex items-start justify-between gap-2">
                <div>
                  <div className="flex items-center gap-2">
                    <StatusBadge status="PLANNED" domain="order" size="xs" />
                    <span className="font-semibold text-slate-700">1. Order &amp; Batch Start</span>
                  </div>
                  <p className="text-slate-600 mt-1 leading-relaxed">
                    Planned batches start in Manufacturing Orders after order release.
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => navigateToTab('orders')}
                  className="text-[11px] font-semibold text-pharma-700 hover:underline shrink-0"
                >
                  Orders
                </button>
              </div>

              <div className="rounded-lg border border-amber-200 bg-amber-50/60 p-3">
                <div className="flex items-center gap-2">
                  <StatusBadge status="IN_PROGRESS" domain="order" size="xs" showDot pulseDot />
                  <span className="font-semibold text-amber-900">2. Active eBR Queue (Here)</span>
                </div>
                <p className="text-amber-900/80 mt-1 leading-relaxed">
                  Sequential recipe operations execute here; cancelled batches are excluded.
                </p>
              </div>

              <div className="rounded-lg border border-emerald-200 bg-emerald-50/40 p-3 flex items-start justify-between gap-2">
                <div>
                  <div className="flex items-center gap-2">
                    <StatusBadge status="COMPLETED" domain="order" size="xs" />
                    <span className="font-semibold text-slate-700">3. Yield &amp; QA Review</span>
                  </div>
                  <p className="text-slate-600 mt-1 leading-relaxed">
                    Record final batch yield in Orders, then complete QC &amp; QA release.
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => navigateToTab('quality')}
                  className="text-[11px] font-semibold text-emerald-700 hover:underline shrink-0"
                >
                  Quality
                </button>
              </div>
            </div>
          </div>

          {/* KPI Summary Cards */}
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Active Batches in Queue
                </p>
                <p className="text-2xl font-extrabold text-amber-600 mt-1 tabular-nums">
                  {queueItems.length}
                </p>
              </div>
              <div className="w-10 h-10 rounded-lg bg-amber-50 text-amber-600 flex items-center justify-center">
                <Play className="w-5 h-5" aria-hidden="true" />
              </div>
            </div>

            <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Active Orders
                </p>
                <p className="text-2xl font-extrabold text-slate-900 mt-1 tabular-nums">
                  {distinctOrdersCount}
                </p>
              </div>
              <div className="w-10 h-10 rounded-lg bg-pharma-50 text-pharma-600 flex items-center justify-center">
                <ClipboardList className="w-5 h-5" aria-hidden="true" />
              </div>
            </div>

            <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Products in Execution
                </p>
                <p className="text-2xl font-extrabold text-slate-900 mt-1 tabular-nums">
                  {distinctProductsCount}
                </p>
              </div>
              <div className="w-10 h-10 rounded-lg bg-indigo-50 text-indigo-600 flex items-center justify-center">
                <Box className="w-5 h-5" aria-hidden="true" />
              </div>
            </div>
          </div>

          {/* Search Toolbar */}
          <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div className="relative flex-1 max-w-md">
              <label htmlFor={searchInputId} className="sr-only">
                Search execution queue by batch number, order number, product, or recipe
              </label>
              <Search
                className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2"
                aria-hidden="true"
              />
              <input
                id={searchInputId}
                type="text"
                value={searchTerm}
                onChange={(e) => handleSearchChange(e.target.value)}
                placeholder="Search by batch number, order number, product, or recipe..."
                className="w-full pl-9 pr-14 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 bg-white"
              />
              {searchTerm && (
                <button
                  type="button"
                  onClick={() => handleSearchChange('')}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-xs font-medium text-slate-500 hover:text-slate-700"
                >
                  Clear
                </button>
              )}
            </div>

            <div className="text-xs text-slate-500 tabular-nums">
              Showing <span className="font-semibold text-slate-700">{filteredQueueItems.length}</span> of{' '}
              <span className="font-semibold text-slate-700">{queueItems.length}</span> active batch(es)
            </div>
          </div>

          {/* Refresh Error Banner (when existing cached rows remain visible) */}
          {error && queueItems.length > 0 && !isLoading && (
            <InlineAlert
              variant="error"
              role="alert"
              title="Unable to refresh production execution queue"
              message={error}
              onDismiss={() => setError(null)}
              actions={
                <button
                  type="button"
                  onClick={() => loadQueue({ showSpinner: true })}
                  className="inline-flex items-center space-x-1.5 text-xs font-semibold px-3 py-1.5 bg-white border border-rose-300 rounded-lg text-rose-800 hover:bg-rose-50 transition-colors"
                >
                  <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>Retry</span>
                </button>
              }
            />
          )}

          {/* Initial Fetch Error State OR Paginated Execution Queue Table */}
          {!isLoading && error && queueItems.length === 0 ? (
            <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-6 sm:p-8">
              <InlineAlert
                variant="error"
                role="alert"
                title="Unable to load production execution queue"
                message={error}
                actions={
                  <button
                    type="button"
                    onClick={() => loadQueue({ showSpinner: true })}
                    className="inline-flex items-center space-x-1.5 text-xs font-semibold px-3.5 py-2 bg-white border border-rose-300 rounded-lg text-rose-800 hover:bg-rose-50 shadow-sm transition-colors"
                  >
                    <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" />
                    <span>Retry</span>
                  </button>
                }
              />
            </div>
          ) : (
            <DataTable
              columns={columns}
              data={paginatedResult.items}
              rowKey={(item, idx) => item.batchId ?? item.batchNumber ?? idx}
              ariaLabel="Production execution queue"
              caption="Active production batches eligible for eBR step execution"
              isLoading={isLoading}
              loadingTitle="Loading production execution queue..."
              loadingDescription="Fetching active IN_PROGRESS batches from server"
              emptyState={emptyState}
              sortState={sortState}
              onSortChange={handleSortChange}
              itemLabel="batches"
              pagination={
                filteredQueueItems.length > 0
                  ? {
                      currentPage: paginatedResult.page,
                      pageSize: paginatedResult.pageSize,
                      totalItems: paginatedResult.totalItems,
                      onPageChange: setCurrentPage,
                      onPageSizeChange: (nextSize) => {
                        setPageSize(nextSize);
                        setCurrentPage(1);
                      },
                      itemLabel: 'batches',
                    }
                  : null
              }
            />
          )}
        </>
      )}
    </div>
  );
}
