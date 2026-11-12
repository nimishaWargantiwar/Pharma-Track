import React from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';

export const DEFAULT_PAGE_SIZE = 25;

export function paginateItems(items = [], currentPage = 1, pageSize = DEFAULT_PAGE_SIZE) {
  const safeList = Array.isArray(items) ? items : [];
  const safePageSize = Math.max(1, Math.trunc(Number(pageSize) || DEFAULT_PAGE_SIZE));
  const totalItems = safeList.length;
  const totalPages = Math.max(1, Math.ceil(totalItems / safePageSize));
  const safePage = Math.min(Math.max(1, Math.trunc(Number(currentPage) || 1)), totalPages);
  const startIndex = totalItems === 0 ? 0 : (safePage - 1) * safePageSize;
  const endIndex = Math.min(totalItems, startIndex + safePageSize);

  return {
    items: safeList.slice(startIndex, endIndex),
    page: safePage,
    pageSize: safePageSize,
    totalItems,
    totalPages,
    startIndex,
    endIndex,
  };
}

function buildPageRange(currentPage, totalPages) {
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, idx) => idx + 1);
  }

  const pages = [1];
  const left = Math.max(2, currentPage - 1);
  const right = Math.min(totalPages - 1, currentPage + 1);

  if (left > 2) {
    pages.push('ellipsis-left');
  }
  for (let p = left; p <= right; p += 1) {
    pages.push(p);
  }
  if (right < totalPages - 1) {
    pages.push('ellipsis-right');
  }
  pages.push(totalPages);
  return pages;
}

export default function Pagination({
  currentPage = 1,
  totalItems = 0,
  pageSize = DEFAULT_PAGE_SIZE,
  pageSizeOptions = [10, 25, 50, 100],
  onPageChange,
  onPageSizeChange = null,
  itemLabel = 'records',
  ariaLabel = 'Pagination',
  hideWhenEmpty = false,
  className = '',
}) {
  const safeTotal = Math.max(0, Math.trunc(Number(totalItems) || 0));
  const safePageSize = Math.max(1, Math.trunc(Number(pageSize) || DEFAULT_PAGE_SIZE));
  const totalPages = Math.max(1, Math.ceil(safeTotal / safePageSize));
  const safeCurrentPage = Math.min(Math.max(1, Math.trunc(Number(currentPage) || 1)), totalPages);

  if (hideWhenEmpty && safeTotal === 0) {
    return null;
  }

  const startItem = safeTotal === 0 ? 0 : (safeCurrentPage - 1) * safePageSize + 1;
  const endItem = Math.min(safeTotal, safeCurrentPage * safePageSize);
  const pages = buildPageRange(safeCurrentPage, totalPages);

  const handleGoToPage = (nextPage) => {
    if (typeof onPageChange !== 'function') return;
    const bounded = Math.min(Math.max(1, nextPage), totalPages);
    if (bounded !== safeCurrentPage) {
      onPageChange(bounded);
    }
  };

  return (
    <div
      className={`px-4 py-3 bg-slate-50/80 border-t border-slate-200 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 text-xs text-slate-600 ${className}`.trim()}
    >
      <div className="flex flex-wrap items-center gap-3">
        <span className="tabular-nums">
          {safeTotal === 0 ? (
            <>Showing 0 {itemLabel}</>
          ) : (
            <>
              Showing <span className="font-semibold text-slate-800">{startItem}</span>&ndash;
              <span className="font-semibold text-slate-800">{endItem}</span> of{' '}
              <span className="font-semibold text-slate-800">{safeTotal}</span> {itemLabel}
            </>
          )}
        </span>

        {typeof onPageSizeChange === 'function' && (
          <label className="inline-flex items-center space-x-1.5 text-slate-600">
            <span>Rows:</span>
            <select
              aria-label="Rows per page"
              value={safePageSize}
              onChange={(e) => onPageSizeChange(Number(e.target.value) || DEFAULT_PAGE_SIZE)}
              className="px-2 py-1 text-xs font-medium bg-white border border-slate-200 rounded-md text-slate-700 tabular-nums focus:outline-none focus:ring-2 focus:ring-pharma-500"
            >
              {pageSizeOptions.map((opt) => (
                <option key={opt} value={opt}>
                  {opt}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>

      <nav aria-label={ariaLabel} className="flex flex-wrap items-center gap-1">
        <button
          type="button"
          onClick={() => handleGoToPage(safeCurrentPage - 1)}
          disabled={safeCurrentPage <= 1 || safeTotal === 0}
          aria-label="Previous page"
          className="inline-flex items-center px-2 py-1.5 rounded-md border border-slate-200 bg-white text-slate-700 hover:bg-slate-100 disabled:opacity-45 disabled:cursor-not-allowed transition-colors"
        >
          <ChevronLeft className="w-3.5 h-3.5" aria-hidden="true" />
          <span className="ml-0.5 hidden sm:inline">Prev</span>
        </button>

        {pages.map((entry) => {
          if (typeof entry !== 'number') {
            return (
              <span key={entry} className="px-1.5 text-slate-500 select-none" aria-hidden="true">
                &hellip;
              </span>
            );
          }
          const isCurrent = entry === safeCurrentPage;
          return (
            <button
              key={entry}
              type="button"
              onClick={() => handleGoToPage(entry)}
              aria-current={isCurrent ? 'page' : undefined}
              aria-label={`Page ${entry}`}
              className={`min-w-[28px] px-2 py-1.5 rounded-md border text-xs font-medium tabular-nums transition-colors ${
                isCurrent
                  ? 'bg-pharma-600 text-white border-pharma-600 font-semibold shadow-sm'
                  : 'bg-white text-slate-700 border-slate-200 hover:bg-slate-100'
              }`}
            >
              {entry}
            </button>
          );
        })}

        <button
          type="button"
          onClick={() => handleGoToPage(safeCurrentPage + 1)}
          disabled={safeCurrentPage >= totalPages || safeTotal === 0}
          aria-label="Next page"
          className="inline-flex items-center px-2 py-1.5 rounded-md border border-slate-200 bg-white text-slate-700 hover:bg-slate-100 disabled:opacity-45 disabled:cursor-not-allowed transition-colors"
        >
          <span className="mr-0.5 hidden sm:inline">Next</span>
          <ChevronRight className="w-3.5 h-3.5" aria-hidden="true" />
        </button>
      </nav>
    </div>
  );
}

