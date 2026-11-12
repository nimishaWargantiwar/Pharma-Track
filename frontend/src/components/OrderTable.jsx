import React from 'react';
import {
  ClipboardList,
  Eye,
  Calendar,
  RefreshCw,
  RotateCcw,
} from 'lucide-react';
import {
  DataTable,
  InlineAlert,
  ProgressBar,
  StatusBadge,
} from './ui/index.js';
import { calculateOrderBatchProgress, formatCalendarDate } from '../utils/orderUtils.js';

export default function OrderTable({
  orders = [],
  isLoading = false,
  error = null,
  onRetry = null,
  hasActiveFilters = false,
  onResetFilters = null,
  onView,
  sortState = null,
  onSortChange = null,
  pagination = null,
}) {
  const formatDate = (dateString) => formatCalendarDate(dateString);

  if (!isLoading && error && orders.length === 0) {
    return (
      <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-6 sm:p-8">
        <InlineAlert
          variant="error"
          title="Unable to load manufacturing orders"
          message={error}
          actions={
            typeof onRetry === 'function' ? (
              <button
                type="button"
                onClick={onRetry}
                className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-rose-900 bg-white hover:bg-rose-100 border border-rose-300 rounded-lg shadow-sm transition-colors"
              >
                <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" />
                <span>Retry</span>
              </button>
            ) : null
          }
        />
      </div>
    );
  }

  const columns = [
    {
      key: 'orderNumber',
      header: 'Order Number',
      accessor: 'orderNumber',
      sortable: true,
      headerClassName: 'sm:pl-6 whitespace-nowrap',
      cellClassName: 'sm:pl-6 font-medium text-slate-900 whitespace-nowrap',
      render: (order) => (
        <div>
          <span className="font-mono font-bold text-pharma-700 bg-pharma-50 px-2.5 py-1 rounded-md border border-pharma-200 inline-block tabular-nums">
            {order.orderNumber}
          </span>
          <div className="mt-1 text-xs text-slate-500 sm:hidden">
            <span className="font-semibold text-slate-800">{order.productName || '—'}</span>
            {order.plannedQuantity != null && (
              <span className="ml-1.5 tabular-nums">
                &middot; {order.plannedQuantity} {order.quantityUnit || ''}
              </span>
            )}
          </div>
        </div>
      ),
    },
    {
      key: 'productName',
      header: 'Product',
      accessor: 'productName',
      sortable: true,
      headerClassName: 'hidden sm:table-cell whitespace-nowrap',
      cellClassName: 'hidden sm:table-cell whitespace-nowrap',
      render: (order) => (
        <div className="flex flex-col">
          <span className="font-semibold text-slate-900">{order.productName || '—'}</span>
          <span className="text-xs text-slate-500 font-mono mt-0.5 tabular-nums">
            {order.productCode || '—'}
          </span>
        </div>
      ),
    },
    {
      key: 'recipeCode',
      header: 'Recipe & Version',
      accessor: 'recipeCode',
      sortable: true,
      headerClassName: 'hidden lg:table-cell whitespace-nowrap',
      cellClassName: 'hidden lg:table-cell whitespace-nowrap',
      render: (order) => (
        <div className="flex items-center space-x-2">
          <span className="font-mono text-xs text-slate-700 tabular-nums">
            {order.recipeCode || '—'}
          </span>
          {order.recipeVersion != null && (
            <span className="px-1.5 py-0.5 rounded text-[11px] font-bold bg-indigo-50 text-indigo-700 border border-indigo-200 tabular-nums">
              v{order.recipeVersion}
            </span>
          )}
        </div>
      ),
    },
    {
      key: 'plannedQuantity',
      header: 'Planned Qty',
      accessor: 'plannedQuantity',
      sortable: true,
      sortValue: (order) => Number(order?.plannedQuantity) || 0,
      headerClassName: 'hidden md:table-cell whitespace-nowrap',
      cellClassName: 'hidden md:table-cell whitespace-nowrap tabular-nums',
      render: (order) =>
        order.plannedQuantity != null ? (
          <>
            <span className="font-semibold text-slate-900">{order.plannedQuantity}</span>{' '}
            <span className="text-xs text-slate-500 font-medium">{order.quantityUnit}</span>
          </>
        ) : (
          '—'
        ),
    },
    {
      key: 'batchProgress',
      header: 'Batch Progress',
      sortable: true,
      sortValue: (order) => {
        const progress = calculateOrderBatchProgress(order);
        if (order?.status === 'CANCELLED' || progress.allCancelled) return -1;
        return progress.progressPercent;
      },
      headerClassName: 'min-w-[160px] sm:min-w-[180px] whitespace-nowrap',
      cellClassName: 'min-w-[160px] sm:min-w-[180px]',
      render: (order) => {
        const progress = calculateOrderBatchProgress(order);
        const isOrderCancelled = order.status === 'CANCELLED' || progress.allCancelled;

        if (isOrderCancelled) {
          const cancelledSummary =
            progress.cancelledCount > 0
              ? `Cancelled (${progress.cancelledCount} of ${progress.totalCount})`
              : 'Cancelled (not completed)';
          return (
            <div className="space-y-1">
              <div className="flex items-center justify-between text-xs font-medium text-rose-700 tabular-nums">
                <span>{cancelledSummary}</span>
                <span className="text-[11px] text-rose-600 font-semibold">0%</span>
              </div>
              <ProgressBar
                value={0}
                max={100}
                tone="danger"
                size="sm"
                label={`Batch progress for ${order.orderNumber || 'order'}`}
                valueText="Order cancelled — 0% completed"
              />
            </div>
          );
        }

        const summaryText =
          progress.cancelledCount > 0
            ? `${progress.completedCount} of ${progress.activeCount} active`
            : `${progress.completedCount} of ${progress.totalCount} batches`;

        const tone =
          progress.progressPercent === 100
            ? 'success'
            : progress.progressPercent > 0 || progress.inProgressCount > 0
              ? 'warning'
              : 'neutral';

        return (
          <div className="space-y-1">
            <ProgressBar
              value={progress.progressPercent}
              max={100}
              tone={tone}
              size="sm"
              label={`Batch progress for ${order.orderNumber || 'order'}`}
              summaryText={summaryText}
              showPercentage={true}
              valueText={`${progress.completedCount} of ${progress.activeCount} batches completed (${progress.progressPercent}%)`}
            />
            {progress.cancelledCount > 0 && (
              <span className="block text-[11px] text-slate-500 tabular-nums">
                {progress.cancelledCount} cancelled of {progress.totalCount} total
              </span>
            )}
          </div>
        );
      },
    },
    {
      key: 'plannedStartDate',
      header: 'Planned Start',
      accessor: 'plannedStartDate',
      sortable: true,
      headerClassName: 'hidden xl:table-cell whitespace-nowrap',
      cellClassName: 'hidden xl:table-cell whitespace-nowrap text-xs text-slate-600 tabular-nums',
      render: (order) => (
        <div className="flex items-center space-x-1">
          <Calendar className="w-3.5 h-3.5 text-slate-400" aria-hidden="true" />
          <span>{formatDate(order.plannedStartDate)}</span>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      accessor: 'status',
      sortable: true,
      headerClassName: 'whitespace-nowrap',
      cellClassName: 'whitespace-nowrap',
      render: (order) => (
        <StatusBadge
          status={order.status}
          domain="order"
          showDot
          pulseDot={order.status === 'IN_PROGRESS'}
        />
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      headerClassName: 'sm:pr-6 whitespace-nowrap',
      cellClassName: 'sm:pr-6 text-right whitespace-nowrap',
      render: (order) => (
        <button
          type="button"
          onClick={() => onView(order)}
          aria-label={`View order ${order.orderNumber || ''}`.trim()}
          className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-slate-700 hover:text-slate-900 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors"
          title="View order details and batch records"
        >
          <Eye className="w-3.5 h-3.5 text-slate-500" aria-hidden="true" />
          <span>View</span>
        </button>
      ),
    },
  ];

  const emptyState = (
    <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-12 text-center">
      <div className="w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center mx-auto mb-3 text-slate-400">
        <ClipboardList className="w-6 h-6" aria-hidden="true" />
      </div>
      <h3 className="text-base font-semibold text-slate-800">
        {hasActiveFilters ? 'No matching manufacturing orders found' : 'No manufacturing orders found'}
      </h3>
      <p className="text-sm text-slate-500 mt-1 max-w-sm mx-auto">
        {hasActiveFilters
          ? 'No manufacturing orders match your current search or status filter.'
          : 'No manufacturing orders have been scheduled yet.'}
      </p>
      {hasActiveFilters && typeof onResetFilters === 'function' && (
        <button
          type="button"
          onClick={onResetFilters}
          className="mt-4 inline-flex items-center space-x-1.5 px-3.5 py-2 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg transition-colors"
        >
          <RotateCcw className="w-3.5 h-3.5" aria-hidden="true" />
          <span>Reset Filters</span>
        </button>
      )}
    </div>
  );

  return (
    <DataTable
      columns={columns}
      data={orders}
      rowKey={(order, idx) => order.id ?? idx}
      ariaLabel="Manufacturing orders schedule"
      caption="Manufacturing orders schedule"
      isLoading={isLoading}
      loadingTitle="Loading manufacturing orders..."
      loadingDescription="Retrieving manufacturing order records from the API service..."
      emptyState={emptyState}
      sortState={sortState}
      onSortChange={onSortChange}
      pagination={pagination}
      itemLabel="orders"
    />
  );
}
