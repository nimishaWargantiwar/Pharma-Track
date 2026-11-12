import React from 'react';
import { ArrowLeft, ShieldAlert } from 'lucide-react';
import { getRoleMetadata } from '../utils/rbacUtils.js';

export default function AccessDeniedView({
  role,
  moduleName = 'this module',
  description = 'Your assigned role does not grant access to view or manage this section.',
  onReturnHome,
  returnLabel = 'Return to Product Master',
}) {
  const roleMeta = getRoleMetadata(role);

  return (
    <div className="max-w-xl mx-auto my-6 sm:my-8 bg-white border border-slate-200 rounded-2xl shadow-sm p-5 sm:p-8 text-center">
      <div className="w-14 h-14 mx-auto mb-4 rounded-2xl bg-rose-50 border border-rose-200 flex items-center justify-center text-rose-600">
        <ShieldAlert className="w-7 h-7" aria-hidden="true" />
      </div>

      <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-rose-50 text-rose-700 border border-rose-200 mb-3">
        HTTP 403 &bull; Access Restricted
      </span>

      <h2 className="text-lg sm:text-xl font-bold text-slate-900 mb-2">
        Access Denied to {moduleName}
      </h2>

      <p className="text-sm text-slate-600 leading-relaxed mb-4">
        {description}
      </p>

      {role && (
        <div className="inline-flex flex-wrap items-center justify-center gap-2 text-xs text-slate-600 bg-slate-50 border border-slate-200 rounded-lg px-3 py-1.5 mb-6">
          <span>Active Role:</span>
          <span
            className={`inline-flex items-center px-2 py-0.5 rounded-full font-semibold border ${roleMeta.badgeClass}`}
          >
            {roleMeta.label}
          </span>
        </div>
      )}

      <div>
        {typeof onReturnHome === 'function' && (
          <button
            type="button"
            onClick={onReturnHome}
            className="inline-flex items-center space-x-2 px-4 py-2 rounded-lg bg-pharma-600 hover:bg-pharma-700 text-white text-sm font-medium shadow-sm transition-colors"
          >
            <ArrowLeft className="w-4 h-4" />
            <span>{returnLabel}</span>
          </button>
        )}
      </div>
    </div>
  );
}

