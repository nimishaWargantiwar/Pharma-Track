import React, { useId } from 'react';
import { AlertTriangle, Info, X } from 'lucide-react';
import ModalDialog from './ui/ModalDialog.jsx';

export default function ConfirmModal({
  isOpen,
  title,
  message,
  confirmText = 'Confirm',
  cancelText = 'Cancel',
  confirmVariant = 'primary', // 'primary' | 'warning' | 'danger'
  onConfirm,
  onClose,
  isLoading = false,
}) {
  const titleId = useId();
  const descriptionId = useId();

  const variantStyles = {
    danger: {
      icon: <AlertTriangle className="w-5 h-5 text-rose-600" aria-hidden="true" />,
      iconBg: 'bg-rose-100',
      btn: 'bg-rose-600 hover:bg-rose-700 text-white',
    },
    warning: {
      icon: <AlertTriangle className="w-5 h-5 text-amber-600" aria-hidden="true" />,
      iconBg: 'bg-amber-100',
      btn: 'bg-amber-600 hover:bg-amber-700 text-white',
    },
    primary: {
      icon: <Info className="w-5 h-5 text-pharma-600" aria-hidden="true" />,
      iconBg: 'bg-pharma-100',
      btn: 'bg-pharma-600 hover:bg-pharma-700 text-white',
    },
  }[confirmVariant] || {
    icon: <Info className="w-5 h-5 text-pharma-600" aria-hidden="true" />,
    iconBg: 'bg-pharma-100',
    btn: 'bg-pharma-600 hover:bg-pharma-700 text-white',
  };

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={onClose}
      preventClose={isLoading}
      size="md"
      zIndexClass="z-[60]"
      ariaLabelledBy={titleId}
      ariaDescribedBy={descriptionId}
    >
      <div className="p-4 sm:p-6">
        <div className="flex items-start space-x-3 sm:space-x-4">
          <div
            className={`w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0 ${variantStyles.iconBg}`}
          >
            {variantStyles.icon}
          </div>
          <div className="flex-1 min-w-0">
            <h3 id={titleId} className="text-base font-semibold text-slate-900 break-words">
              {title}
            </h3>
            <p id={descriptionId} className="mt-2 text-sm text-slate-600 leading-relaxed break-words">
              {message}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={isLoading}
            aria-label="Close confirmation dialog"
            className="text-slate-500 hover:text-slate-700 p-1 rounded-lg hover:bg-slate-100 transition-colors disabled:opacity-50 shrink-0"
          >
            <X className="w-5 h-5" aria-hidden="true" />
          </button>
        </div>

        <div className="mt-6 flex flex-wrap items-center justify-end gap-2.5 sm:gap-3 pt-4 border-t border-slate-100">
          <button
            type="button"
            onClick={onClose}
            disabled={isLoading}
            className="px-4 py-2 text-sm font-medium text-slate-700 hover:text-slate-900 bg-slate-100 hover:bg-slate-200 rounded-lg transition-colors disabled:opacity-50"
          >
            {cancelText}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={isLoading}
            className={`px-4 py-2 text-sm font-semibold rounded-lg shadow-sm transition-colors disabled:opacity-50 ${variantStyles.btn}`}
          >
            {isLoading ? 'Processing...' : confirmText}
          </button>
        </div>
      </div>
    </ModalDialog>
  );
}

