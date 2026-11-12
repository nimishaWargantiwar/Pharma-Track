import React from 'react';
import { ArrowLeft } from 'lucide-react';

export default function PageHeader({
  title,
  description = null,
  badge = null,
  eyebrow = null,
  icon = null,
  actions = null,
  backAction = null,
  className = '',
  headingAs = 'h1',
}) {
  const HeadingTag = headingAs === 'h2' ? 'h2' : 'h1';

  return (
    <div
      className={`flex flex-col sm:flex-row sm:flex-wrap sm:items-center sm:justify-between gap-4 pb-6 border-b border-slate-200 ${className}`.trim()}
    >
      <div className="min-w-0 sm:min-w-[260px] flex-1">
        {backAction && typeof backAction.onClick === 'function' && (
          <button
            type="button"
            onClick={backAction.onClick}
            className="inline-flex items-center space-x-1.5 text-xs font-semibold text-pharma-700 hover:text-pharma-800 mb-2 rounded focus:outline-none focus:ring-2 focus:ring-pharma-500"
          >
            <ArrowLeft className="w-3.5 h-3.5" aria-hidden="true" />
            <span>{backAction.label || 'Back'}</span>
          </button>
        )}

        {eyebrow && (
          <p className="text-[11px] font-bold uppercase tracking-wider text-pharma-700 mb-1">
            {eyebrow}
          </p>
        )}

        <div className="flex flex-wrap items-center gap-2.5">
          <div className="flex items-center gap-2.5 min-w-0">
            {icon && <div className="shrink-0">{icon}</div>}
            <HeadingTag className="text-xl sm:text-2xl font-bold text-slate-900 tracking-tight break-words min-w-0">
              {title}
            </HeadingTag>
          </div>
          {badge &&
            (typeof badge === 'string' ? (
              <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-pharma-100 text-pharma-800 border border-pharma-200 shrink-0">
                {badge}
              </span>
            ) : (
              badge
            ))}
        </div>

        {description && (
          <p className="mt-1 text-sm text-slate-500 leading-relaxed max-w-3xl">
            {description}
          </p>
        )}
      </div>

      {actions && (
        <div className="flex flex-wrap items-center gap-2.5 max-w-full">{actions}</div>
      )}
    </div>
  );
}

