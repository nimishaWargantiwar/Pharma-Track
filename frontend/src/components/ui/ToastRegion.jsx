import React from 'react';
import { AlertCircle, AlertTriangle, CheckCircle2, Info, X } from 'lucide-react';

const TOAST_STYLES = {
  success: {
    card: 'bg-emerald-50 border-emerald-200 text-emerald-950',
    iconWrap: 'text-emerald-600',
    titleText: 'text-emerald-900',
    messageText: 'text-emerald-800',
    dismissBtn: 'text-emerald-500 hover:text-emerald-800 hover:bg-emerald-100/70',
    Icon: CheckCircle2,
    defaultTitle: 'Success',
  },
  error: {
    card: 'bg-rose-50 border-rose-200 text-rose-950',
    iconWrap: 'text-rose-600',
    titleText: 'text-rose-900',
    messageText: 'text-rose-800',
    dismissBtn: 'text-rose-500 hover:text-rose-800 hover:bg-rose-100/70',
    Icon: AlertCircle,
    defaultTitle: 'Error',
  },
  warning: {
    card: 'bg-amber-50 border-amber-200 text-amber-950',
    iconWrap: 'text-amber-600',
    titleText: 'text-amber-900',
    messageText: 'text-amber-800',
    dismissBtn: 'text-amber-500 hover:text-amber-800 hover:bg-amber-100/70',
    Icon: AlertTriangle,
    defaultTitle: 'Warning',
  },
  info: {
    card: 'bg-pharma-50 border-pharma-200 text-pharma-900',
    iconWrap: 'text-pharma-600',
    titleText: 'text-pharma-900',
    messageText: 'text-pharma-800',
    dismissBtn: 'text-pharma-600 hover:text-pharma-900 hover:bg-pharma-100/70',
    Icon: Info,
    defaultTitle: 'Notice',
  },
};

export default function ToastRegion({ toasts = [], onDismiss }) {
  return (
    <div
      role="region"
      aria-label="Notifications"
      aria-live="polite"
      data-testid="toast-region"
      className="fixed top-16 right-4 sm:right-6 z-50 flex flex-col gap-2.5 max-w-md w-[calc(100vw-2rem)] sm:w-full pointer-events-none"
    >
      {toasts.map((toast) => {
        const typeKey = toast.type === 'danger' ? 'error' : toast.type;
        const style = TOAST_STYLES[typeKey] || TOAST_STYLES.info;
        const IconComponent = style.Icon;
        const resolvedTitle = toast.title || style.defaultTitle;

        return (
          <div
            key={toast.id}
            role={typeKey === 'error' ? 'alert' : 'status'}
            className={`pointer-events-auto shadow-lg rounded-xl border p-4 flex items-start space-x-3 animate-modal-enter ${style.card}`}
          >
            <div className={`shrink-0 mt-0.5 ${style.iconWrap}`}>
              <IconComponent className="w-5 h-5" aria-hidden="true" />
            </div>

            <div className="flex-1 min-w-0 text-sm">
              <p className={`font-semibold ${style.titleText}`}>{resolvedTitle}</p>
              {toast.message && (
                <p className={`mt-0.5 text-xs leading-relaxed break-words ${style.messageText}`}>
                  {toast.message}
                </p>
              )}
            </div>

            {typeof onDismiss === 'function' && (
              <button
                type="button"
                onClick={() => onDismiss(toast.id)}
                aria-label={`Dismiss ${resolvedTitle.toLowerCase()} notification`}
                className={`p-1 rounded-md transition-colors shrink-0 ${style.dismissBtn}`}
              >
                <X className="w-4 h-4" aria-hidden="true" />
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
}

