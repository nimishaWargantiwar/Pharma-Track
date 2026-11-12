import React, { useMemo, useState } from 'react';
import { ArrowDown, ArrowUp, ArrowUpDown, Inbox, RefreshCw } from 'lucide-react';
import Pagination, { DEFAULT_PAGE_SIZE, paginateItems } from './Pagination.jsx';

export default function DataTable({
  columns = [],
  data = [],
  rowKey = (row, idx) => row?.id ?? idx,
  isLoading = false,
  loadingTitle = 'Loading records...',
  loadingDescription = 'Retrieving records from the API service...',
  loadingState = null,
  emptyTitle = 'No records found',
  emptyDescription = 'No records match your current criteria.',
  emptyIcon = null,
  emptyState = null,
  caption = null,
  ariaLabel = undefined,
  sortState = null,
  onSortChange = null,
  onRowClick = null,
  rowClassName = '',
  pagination = null, // { currentPage, pageSize, totalItems, onPageChange, onPageSizeChange, itemLabel }
  autoPaginate = false,
  defaultPageSize = DEFAULT_PAGE_SIZE,
  itemLabel = 'records',
  footer = null,
  className = '',
  tableClassName = '',
}) {
  const safeData = Array.isArray(data) ? data : [];

  const [internalSort, setInternalSort] = useState(null);
  const [internalPage, setInternalPage] = useState(1);
  const [internalPageSize, setInternalPageSize] = useState(defaultPageSize);

  const activeSort = sortState ?? internalSort;

  const sortedData = useMemo(() => {
    if (!activeSort?.key || typeof onSortChange === 'function') {
      return safeData;
    }
    const targetCol = columns.find((col) => col.key === activeSort.key);
    if (!targetCol) {
      return safeData;
    }
    const dir = activeSort.direction === 'desc' ? -1 : 1;
    return [...safeData].sort((a, b) => {
      const valA =
        typeof targetCol.sortValue === 'function'
          ? targetCol.sortValue(a)
          : targetCol.accessor
            ? a?.[targetCol.accessor]
            : a?.[targetCol.key];
      const valB =
        typeof targetCol.sortValue === 'function'
          ? targetCol.sortValue(b)
          : targetCol.accessor
            ? b?.[targetCol.accessor]
            : b?.[targetCol.key];

      if (valA == null && valB == null) return 0;
      if (valA == null) return -1 * dir;
      if (valB == null) return 1 * dir;
      if (typeof valA === 'number' && typeof valB === 'number') {
        return (valA - valB) * dir;
      }
      return String(valA).localeCompare(String(valB), undefined, { numeric: true }) * dir;
    });
  }, [safeData, activeSort, columns, onSortChange]);

  const paginatedResult = useMemo(() => {
    if (!autoPaginate) {
      return {
        items: sortedData,
        page: 1,
        pageSize: sortedData.length || defaultPageSize,
        totalItems: sortedData.length,
      };
    }
    return paginateItems(sortedData, internalPage, internalPageSize);
  }, [autoPaginate, sortedData, internalPage, internalPageSize, defaultPageSize]);

  const rowsToRender = paginatedResult.items;

  const handleSortToggle = (column) => {
    if (!column.sortable) return;
    const isSameCol = activeSort?.key === column.key;
    const nextDirection =
      isSameCol && activeSort?.direction === 'asc' ? 'desc' : 'asc';
    const nextSort = { key: column.key, direction: nextDirection };

    if (typeof onSortChange === 'function') {
      onSortChange(nextSort);
    } else {
      setInternalSort(nextSort);
      if (autoPaginate) {
        setInternalPage(1);
      }
    }
  };

  if (isLoading) {
    if (loadingState) {
      return loadingState;
    }
    return (
      <div className={`bg-white rounded-xl shadow-sm border border-slate-200 p-8 sm:p-12 text-center ${className}`.trim()}>
        <RefreshCw className="w-8 h-8 mx-auto text-pharma-600 animate-spin mb-3" aria-hidden="true" />
        <p className="text-sm font-medium text-slate-700">{loadingTitle}</p>
        {loadingDescription && (
          <p className="text-xs text-slate-500 mt-1">{loadingDescription}</p>
        )}
      </div>
    );
  }

  if (safeData.length === 0) {
    if (emptyState) {
      return emptyState;
    }
    return (
      <div className={`bg-white rounded-xl shadow-sm border border-slate-200 p-8 sm:p-12 text-center ${className}`.trim()}>
        <div className="w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center mx-auto mb-3 text-slate-500">
          {emptyIcon || <Inbox className="w-6 h-6" aria-hidden="true" />}
        </div>
        <h3 className="text-base font-semibold text-slate-800">{emptyTitle}</h3>
        {emptyDescription && (
          <p className="text-sm text-slate-500 mt-1 max-w-sm mx-auto">{emptyDescription}</p>
        )}
      </div>
    );
  }

  return (
    <div className={`bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden max-w-full min-w-0 ${className}`.trim()}>
      <div className="relative overflow-x-auto max-w-full">
        <table
          aria-label={ariaLabel}
          className={`w-full text-left border-collapse ${tableClassName}`.trim()}
        >
          {caption && <caption className="sr-only">{caption}</caption>}
          <thead>
            <tr className="bg-slate-50/80 border-b border-slate-200 text-xs font-semibold text-slate-500 uppercase tracking-wider">
              {columns.map((column) => {
                const alignClass =
                  column.align === 'right'
                    ? 'text-right'
                    : column.align === 'center'
                      ? 'text-center'
                      : 'text-left';

                const isSorted = activeSort?.key === column.key;
                const ariaSort = column.sortable
                  ? isSorted
                    ? activeSort.direction === 'desc'
                      ? 'descending'
                      : 'ascending'
                    : 'none'
                  : undefined;

                return (
                  <th
                    key={column.key}
                    scope="col"
                    aria-sort={ariaSort}
                    className={`py-3.5 px-4 ${alignClass} ${column.headerClassName || ''}`.trim()}
                  >
                    {column.sortable ? (
                      <button
                        type="button"
                        onClick={() => handleSortToggle(column)}
                        className={`inline-flex items-center gap-1 font-semibold uppercase tracking-wider hover:text-slate-800 focus:outline-none focus:ring-2 focus:ring-pharma-500 rounded ${
                          column.align === 'right' ? 'flex-row-reverse' : ''
                        }`}
                      >
                        <span>{column.header}</span>
                        {isSorted ? (
                          activeSort.direction === 'desc' ? (
                            <ArrowDown className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                          ) : (
                            <ArrowUp className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                          )
                        ) : (
                          <ArrowUpDown className="w-3.5 h-3.5 text-slate-400" aria-hidden="true" />
                        )}
                      </button>
                    ) : (
                      column.header
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>

          <tbody className="divide-y divide-slate-200 text-sm text-slate-700">
            {rowsToRender.map((row, rowIndex) => {
              const key = typeof rowKey === 'function' ? rowKey(row, rowIndex) : row?.[rowKey] ?? rowIndex;
              const customRowClass =
                typeof rowClassName === 'function' ? rowClassName(row, rowIndex) : rowClassName;

              return (
                <tr
                  key={key}
                  onClick={typeof onRowClick === 'function' ? () => onRowClick(row) : undefined}
                  className={`hover:bg-slate-50/75 transition-colors ${
                    typeof onRowClick === 'function' ? 'cursor-pointer' : ''
                  } ${customRowClass || ''}`.trim()}
                >
                  {columns.map((column) => {
                    const alignClass =
                      column.align === 'right'
                        ? 'text-right'
                        : column.align === 'center'
                          ? 'text-center'
                          : 'text-left';

                    const content =
                      typeof column.render === 'function'
                        ? column.render(row, rowIndex)
                        : column.accessor
                          ? row?.[column.accessor]
                          : row?.[column.key];

                    return (
                      <td
                        key={column.key}
                        className={`py-4 px-4 ${alignClass} ${column.cellClassName || ''}`.trim()}
                      >
                        {content ?? '—'}
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {autoPaginate && (
        <Pagination
          currentPage={paginatedResult.page}
          totalItems={paginatedResult.totalItems}
          pageSize={paginatedResult.pageSize}
          onPageChange={setInternalPage}
          onPageSizeChange={(nextSize) => {
            setInternalPageSize(nextSize);
            setInternalPage(1);
          }}
          itemLabel={itemLabel}
        />
      )}

      {!autoPaginate && pagination && <Pagination {...pagination} />}

      {footer}
    </div>
  );
}

