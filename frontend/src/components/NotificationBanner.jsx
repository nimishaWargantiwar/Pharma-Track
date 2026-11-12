import React, { useEffect, useRef } from 'react';
import { CheckCircle2, AlertCircle, X } from 'lucide-react';
import { useOptionalToast } from './ui/ToastProvider.jsx';

export default function NotificationBanner({ notification, onDismiss }) {
  const toastCtx = useOptionalToast();
  const forwardedRef = useRef(null);
  const onDismissRef = useRef(onDismiss);
  onDismissRef.current = onDismiss;

  useEffect(() => {
    if (!notification) return undefined;

    if (toastCtx) {
      if (forwardedRef.current !== notification) {
        forwardedRef.current = notification;
        toastCtx.addToast(notification);
      }
      if (typeof onDismissRef.current === 'function') {
        onDismissRef.current();
      }
      return undefined;
    }

    const timer = setTimeout(() => {
      if (typeof onDismissRef.current === 'function') {
        onDismissRef.current();
      }
    }, 5000);
    return () => clearTimeout(timer);
  }, [notification, toastCtx]);

  if (toastCtx || !notification) return null;

  const isSuccess = notification.type === 'success';

  return (
    <div
      className={`fixed top-16 right-6 z-50 max-w-md w-full shadow-lg rounded-lg border p-4 flex items-start space-x-3 transition-all duration-300 ${
        isSuccess
          ? 'bg-emerald-50 border-emerald-200 text-emerald-900'
          : 'bg-rose-50 border-rose-200 text-rose-900'
      }`}
      role="alert"
    >
      <div className="flex-shrink-0 mt-0.5">
        {isSuccess ? (
          <CheckCircle2 className="w-5 h-5 text-emerald-600" />
        ) : (
          <AlertCircle className="w-5 h-5 text-rose-600" />
        )}
      </div>

      <div className="flex-1 text-sm">
        <p className="font-semibold">{isSuccess ? 'Success' : 'Error'}</p>
        <p className="mt-0.5 text-xs text-slate-700">{notification.message}</p>
      </div>

      <button
        type="button"
        onClick={onDismiss}
        aria-label="Dismiss notification"
        className="text-slate-500 hover:text-slate-700 p-1 rounded-md transition-colors"
      >
        <X className="w-4 h-4" />
      </button>
    </div>
  );
}

