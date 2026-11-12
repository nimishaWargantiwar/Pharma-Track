import React from 'react';
import {
  Edit2,
  Power,
  Pill,
  RefreshCw,
  RotateCcw,
} from 'lucide-react';
import { DataTable, InlineAlert, StatusBadge } from './ui/index.js';
import { formatAppShortDateTime } from '../utils/dateTimeUtils.js';

export default function ProductTable({
  products = [],
  isLoading = false,
  error = null,
  onRetry = null,
  hasActiveFilters = false,
  onResetFilters = null,
  onEdit,
  onToggleStatus,
  actionLoadingId = null,
  canManageProducts = false,
  sortState = null,
  onSortChange = null,
  pagination = null,
}) {
  const formatDate = (dateString) => formatAppShortDateTime(dateString);

  if (!isLoading && error && products.length === 0) {
    return (
      <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-6 sm:p-8">
        <InlineAlert
          variant="error"
          title="Unable to load pharmaceutical product catalog"
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
      key: 'productCode',
      header: 'Product Code',
      accessor: 'productCode',
      sortable: true,
      headerClassName: 'sm:pl-6',
      cellClassName: 'sm:pl-6 font-mono font-medium text-slate-900 whitespace-nowrap',
      render: (product) => (
        <span className="inline-block bg-slate-100 text-slate-800 px-2.5 py-1 rounded-md border border-slate-200 text-xs font-semibold">
          {product.productCode}
        </span>
      ),
    },
    {
      key: 'productName',
      header: 'Product Name',
      accessor: 'productName',
      sortable: true,
      render: (product) => (
        <div>
          <div className="font-semibold text-slate-900">{product.productName}</div>
          {product.description && (
            <div className="text-xs text-slate-500 line-clamp-1 max-w-xs mt-0.5">
              {product.description}
            </div>
          )}
          <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-slate-500 sm:hidden">
            <span className="font-medium text-slate-700 tabular-nums">{product.strength}</span>
            <span aria-hidden="true">&middot;</span>
            <span>{product.dosageForm}</span>
          </div>
        </div>
      ),
    },
    {
      key: 'strength',
      header: 'Strength',
      accessor: 'strength',
      sortable: true,
      headerClassName: 'hidden sm:table-cell',
      cellClassName: 'hidden sm:table-cell font-medium text-slate-700 tabular-nums whitespace-nowrap',
    },
    {
      key: 'dosageForm',
      header: 'Dosage Form',
      accessor: 'dosageForm',
      sortable: true,
      headerClassName: 'hidden sm:table-cell',
      cellClassName: 'hidden sm:table-cell whitespace-nowrap',
      render: (product) => (
        <span className="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium bg-slate-100 text-slate-700 border border-slate-200">
          {product.dosageForm}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      accessor: 'status',
      sortable: true,
      cellClassName: 'whitespace-nowrap',
      render: (product) => <StatusBadge status={product.status} showDot />,
    },
    {
      key: 'updatedAt',
      header: 'Last Updated',
      sortable: true,
      sortValue: (product) => product.updatedAt || product.createdAt || '',
      headerClassName: 'hidden lg:table-cell',
      cellClassName: 'hidden lg:table-cell text-xs text-slate-500 tabular-nums whitespace-nowrap',
      render: (product) => formatDate(product.updatedAt || product.createdAt),
    },
  ];

  if (canManageProducts) {
    columns.push({
      key: 'actions',
      header: 'Actions',
      align: 'right',
      headerClassName: 'sm:pr-6',
      cellClassName: 'sm:pr-6 text-right whitespace-nowrap',
      render: (product) => {
        const isActive = product.status === 'ACTIVE';
        const isActionLoading = actionLoadingId === product.id;

        return (
          <div className="inline-flex items-center space-x-2">
            <button
              type="button"
              onClick={() => onEdit(product)}
              disabled={isActionLoading}
              title="Edit product formulation"
              aria-label={`Edit ${product.productCode}`}
              className="inline-flex items-center space-x-1 px-2.5 py-1.5 text-xs font-medium text-slate-700 bg-white hover:bg-slate-100 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
            >
              <Edit2 className="w-3.5 h-3.5 text-slate-500" aria-hidden="true" />
              <span className="hidden sm:inline">Edit</span>
            </button>

            <button
              type="button"
              onClick={() => onToggleStatus(product)}
              disabled={isActionLoading}
              title={isActive ? 'Deactivate product' : 'Activate product'}
              aria-label={`${isActive ? 'Deactivate' : 'Activate'} ${product.productCode}`}
              className={`inline-flex items-center space-x-1 px-2.5 py-1.5 text-xs font-medium rounded-lg border shadow-sm transition-colors disabled:opacity-50 ${
                isActive
                  ? 'text-amber-800 bg-amber-50 hover:bg-amber-100 border-amber-200'
                  : 'text-emerald-800 bg-emerald-50 hover:bg-emerald-100 border-emerald-200'
              }`}
            >
              <Power className="w-3.5 h-3.5" aria-hidden="true" />
              <span>{isActive ? 'Deactivate' : 'Activate'}</span>
            </button>
          </div>
        );
      },
    });
  }

  const emptyState = (
    <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-8 sm:p-12 text-center">
      <div className="w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center mx-auto mb-3 text-slate-400">
        <Pill className="w-6 h-6" aria-hidden="true" />
      </div>
      <h3 className="text-base font-semibold text-slate-800">
        {hasActiveFilters ? 'No matching products found' : 'No products found'}
      </h3>
      <p className="text-sm text-slate-500 mt-1 max-w-sm mx-auto">
        {hasActiveFilters
          ? 'No pharmaceutical products match your current search or filter criteria.'
          : 'No pharmaceutical products are registered in the master catalog yet.'}
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
      data={products}
      rowKey={(product, idx) => product.id ?? idx}
      ariaLabel="Pharmaceutical products master catalog"
      caption="Pharmaceutical products master catalog"
      isLoading={isLoading}
      loadingTitle="Loading pharmaceutical product catalog..."
      loadingDescription="Retrieving product records from the API service..."
      emptyState={emptyState}
      sortState={sortState}
      onSortChange={onSortChange}
      pagination={pagination}
      itemLabel="products"
    />
  );
}
