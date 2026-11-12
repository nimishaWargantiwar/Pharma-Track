import React from 'react';
import { AlertCircle, AlertTriangle, CheckCircle2, Info, X } from 'lucide-react';

const VARIANT_CONFIG = {
  success: {
    container: 'bg-emerald-50 border-emerald-200 text-emerald-900',
    iconColor: 'text-emerald-600',
    bodyColor: 'text-emerald-800',
    dismissBtn: 'text-emerald-600 hover:text-emerald-900 hover:bg-emerald-100/70',
    Icon: CheckCircle2,
    defaultRole: 'status',
  },
  warning: {
    container: 'bg-amber-50 border-amber-200 text-amber-900',
    iconColor: 'text-amber-600',
    bodyColor: 'text-amber-800',
    dismissBtn: 'text-amber-600 hover:text-amber-900 hover:bg-amber-100/70',
    Icon: AlertTriangle,
    defaultRole: 'alert',
  },
  error: {
    container: 'bg-rose-50 border-rose-200 text-rose-900',
    iconColor: 'text-rose-600',
    bodyColor: 'text-rose-800',
    dismissBtn: 'text-rose-600 hover:text-rose-900 hover:bg-rose-100/70',
    Icon: AlertCircle,
    defaultRole: 'alert',
  },
  info: {
    container: 'bg-pharma-50 border-pharma-200 text-pharma-900',
    iconColor: 'text-pharma-600',
    bodyColor: 'text-pharma-800',
    dismissBtn: 'text-pharma-600 hover:text-pharma-900 hover:bg-pharma-100/70',
    Icon: Info,
    defaultRole: 'status',
  },
};

export default function InlineAlert({
  variant = 'info', // 'success' | 'warning' | 'error' | 'info'
  title = null,
  message = null,
  children = null,
  icon = null,
  onDismiss = null,
  dismissLabel = 'Dismiss notice',
  actions = null,
  compact = false,
  role = undefined,
  className = '',
}) {
  const normalizedVariant = variant === 'danger' ? 'error' : variant;
  const config = VARIANT_CONFIG[normalizedVariant] || VARIANT_CONFIG.info;
  const IconComponent = config.Icon;
  const resolvedRole = role ?? config.defaultRole;

  let renderedIcon = <IconComponent className={`w-4 h-4 shrink-0 mt-0.5 ${config.iconColor}`} aria-hidden="true" />;
  if (icon) {
    if (React.isValidElement(icon)) {
      renderedIcon = icon;
    } else if (typeof icon === 'function' || typeof icon === 'object') {
      const CustomIcon = icon;
      renderedIcon = <CustomIcon className={`w-4 h-4 shrink-0 mt-0.5 ${config.iconColor}`} aria-hidden="true" />;
    }
  }

  return (
    <div
      role={resolvedRole}
      className={`rounded-xl border shadow-sm flex items-start justify-between gap-3 ${
        compact ? 'px-3.5 py-2.5 text-xs' : 'px-4 py-3 text-sm'
      } ${config.container} ${className}`.trim()}
    >
      <div className="flex items-start space-x-2.5 flex-1 min-w-0">
        {renderedIcon}
        <div className="flex-1 min-w-0">
          {title && <p className="font-semibold leading-snug">{title}</p>}
          {message && (
            <p className={`${title ? 'mt-0.5 text-xs' : ''} ${config.bodyColor} leading-relaxed`.trim()}>
              {message}
            </p>
          )}
          {children}
          {actions && <div className="mt-2.5 flex flex-wrap items-center gap-2">{actions}</div>}
        </div>
      </div>

      {typeof onDismiss === 'function' && (
        <button
          type="button"
          onClick={onDismiss}
          aria-label={dismissLabel}
          className={`p-1 rounded-md transition-colors shrink-0 ${config.dismissBtn}`}
        >
          <X className="w-4 h-4" aria-hidden="true" />
        </button>
      )}
    </div>
  );
}

