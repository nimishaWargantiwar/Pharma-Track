import React from 'react';
import {
  FlaskConical,
  Eye,
  Edit2,
  Trash2,
  CheckCircle,
  Copy,
  RefreshCw,
  RotateCcw,
} from 'lucide-react';
import { DataTable, InlineAlert, StatusBadge } from './ui/index.js';
import { formatAppShortDateTime } from '../utils/dateTimeUtils.js';

export default function RecipeTable({
  recipes = [],
  isLoading = false,
  error = null,
  onRetry = null,
  hasActiveFilters = false,
  onResetFilters = null,
  onView,
  onEdit,
  onApprove,
  onCreateVersion,
  onDelete,
  actionLoadingId = null,
  canAuthor = false,
  canApprove = false,
  sortState = null,
  onSortChange = null,
  pagination = null,
}) {
  const formatDate = (dateString) => formatAppShortDateTime(dateString);

  if (!isLoading && error && recipes.length === 0) {
    return (
      <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-6 sm:p-8">
        <InlineAlert
          variant="error"
          title="Unable to load master recipes"
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
      key: 'recipeCode',
      header: 'Recipe Code',
      accessor: 'recipeCode',
      sortable: true,
      headerClassName: 'sm:pl-6',
      cellClassName: 'sm:pl-6 font-mono font-medium text-slate-900 whitespace-nowrap',
      render: (recipe) => (
        <span className="inline-block bg-slate-100 text-slate-800 px-2.5 py-1 rounded-md border border-slate-200 text-xs font-semibold">
          {recipe.recipeCode}
        </span>
      ),
    },
    {
      key: 'productName',
      header: 'Product Name & Code',
      accessor: 'productName',
      sortable: true,
      render: (recipe) => (
        <div>
          <div className="font-semibold text-slate-900">{recipe.productName}</div>
          <div className="text-xs font-mono text-slate-500 mt-0.5">{recipe.productCode}</div>
        </div>
      ),
    },
    {
      key: 'version',
      header: 'Ver',
      accessor: 'version',
      align: 'center',
      sortable: true,
      cellClassName: 'whitespace-nowrap',
      render: (recipe) => (
        <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-bold bg-indigo-50 text-indigo-700 border border-indigo-200 font-mono tabular-nums">
          v{recipe.version}
        </span>
      ),
    },
    {
      key: 'batchSize',
      header: 'Batch Size',
      sortable: true,
      sortValue: (recipe) => Number(recipe.batchSize) || 0,
      headerClassName: 'hidden sm:table-cell',
      cellClassName: 'hidden sm:table-cell tabular-nums whitespace-nowrap',
      render: (recipe) => (
        <div>
          <span className="font-semibold text-slate-900 font-mono">{recipe.batchSize}</span>{' '}
          <span className="text-xs text-slate-500">{recipe.batchUnit}</span>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      accessor: 'status',
      sortable: true,
      cellClassName: 'whitespace-nowrap',
      render: (recipe) => <StatusBadge status={recipe.status} showDot />,
    },
    {
      key: 'structure',
      header: 'Structure',
      sortable: true,
      sortValue: (recipe) => (recipe.materials?.length || 0) + (recipe.steps?.length || 0),
      headerClassName: 'hidden md:table-cell',
      cellClassName: 'hidden md:table-cell text-xs text-slate-500 tabular-nums whitespace-nowrap',
      render: (recipe) => (
        <div>
          <div>
            <span className="font-semibold text-slate-700">{recipe.materials?.length || 0}</span>{' '}
            materials
          </div>
          <div>
            <span className="font-semibold text-slate-700">{recipe.steps?.length || 0}</span> steps
          </div>
        </div>
      ),
    },
    {
      key: 'updatedAt',
      header: 'Last Updated',
      sortable: true,
      sortValue: (recipe) => recipe.updatedAt || recipe.createdAt || '',
      headerClassName: 'hidden lg:table-cell',
      cellClassName: 'hidden lg:table-cell text-xs text-slate-500 tabular-nums whitespace-nowrap',
      render: (recipe) => formatDate(recipe.updatedAt || recipe.createdAt),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      headerClassName: 'sm:pr-6',
      cellClassName: 'sm:pr-6 text-right whitespace-nowrap',
      render: (recipe) => {
        const isDraft = recipe.status === 'DRAFT';
        const isLoadingCurrent = actionLoadingId === recipe.id;

        return (
          <div className="inline-flex items-center space-x-1.5">
            <button
              type="button"
              onClick={() => onView?.(recipe)}
              disabled={isLoadingCurrent}
              title="View recipe details"
              aria-label={`View details for ${recipe.recipeCode}`}
              className="inline-flex items-center p-1.5 text-xs font-medium text-slate-700 bg-white hover:bg-slate-100 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
            >
              <Eye className="w-3.5 h-3.5 text-slate-600" aria-hidden="true" />
            </button>

            {canApprove && isDraft && (
              <button
                type="button"
                onClick={() => onApprove?.(recipe)}
                disabled={isLoadingCurrent}
                title="Approve recipe version"
                aria-label={`Approve ${recipe.recipeCode}`}
                className="inline-flex items-center space-x-1 px-2 py-1.5 text-xs font-medium text-emerald-800 bg-emerald-50 hover:bg-emerald-100 border border-emerald-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
              >
                <CheckCircle className="w-3.5 h-3.5 text-emerald-600" aria-hidden="true" />
                <span className="hidden xl:inline">Approve</span>
              </button>
            )}

            {canAuthor && isDraft && (
              <button
                type="button"
                onClick={() => onEdit?.(recipe)}
                disabled={isLoadingCurrent}
                title="Edit DRAFT recipe"
                aria-label={`Edit ${recipe.recipeCode}`}
                className="inline-flex items-center p-1.5 text-xs font-medium text-slate-700 bg-white hover:bg-slate-100 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
              >
                <Edit2 className="w-3.5 h-3.5 text-slate-600" aria-hidden="true" />
              </button>
            )}

            {canAuthor && (
              <button
                type="button"
                onClick={() => onCreateVersion?.(recipe)}
                disabled={isLoadingCurrent}
                title="Create new draft version cloned from this recipe"
                aria-label={`Create new version from ${recipe.recipeCode}`}
                className="inline-flex items-center space-x-1 px-2 py-1.5 text-xs font-medium text-indigo-700 bg-indigo-50 hover:bg-indigo-100 border border-indigo-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
              >
                <Copy className="w-3.5 h-3.5 text-indigo-600" aria-hidden="true" />
                <span className="hidden xl:inline">New Ver</span>
              </button>
            )}

            {canAuthor && isDraft && (
              <button
                type="button"
                onClick={() => onDelete?.(recipe)}
                disabled={isLoadingCurrent}
                title="Delete DRAFT recipe"
                aria-label={`Delete ${recipe.recipeCode}`}
                className="inline-flex items-center p-1.5 text-xs font-medium text-rose-700 bg-rose-50 hover:bg-rose-100 border border-rose-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
              >
                <Trash2 className="w-3.5 h-3.5 text-rose-600" aria-hidden="true" />
              </button>
            )}
          </div>
        );
      },
    },
  ];

  const emptyState = (
    <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-8 sm:p-12 text-center">
      <div className="w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center mx-auto mb-3 text-slate-400">
        <FlaskConical className="w-6 h-6" aria-hidden="true" />
      </div>
      <h3 className="text-base font-semibold text-slate-800">
        {hasActiveFilters ? 'No matching master recipes found' : 'No master recipes found'}
      </h3>
      <p className="text-sm text-slate-500 mt-1 max-w-sm mx-auto">
        {hasActiveFilters
          ? 'No pharmaceutical recipes match your current search or filter criteria.'
          : 'No master manufacturing recipes have been created yet.'}
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
      data={recipes}
      rowKey={(recipe, idx) => recipe.id ?? idx}
      ariaLabel="Master manufacturing recipes catalog"
      caption="Master manufacturing recipes catalog"
      isLoading={isLoading}
      loadingTitle="Loading master manufacturing recipes..."
      loadingDescription="Retrieving master recipe records from the API service..."
      emptyState={emptyState}
      sortState={sortState}
      onSortChange={onSortChange}
      pagination={pagination}
      itemLabel="recipes"
    />
  );
}
