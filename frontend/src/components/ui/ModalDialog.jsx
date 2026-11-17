import React, { useEffect, useId, useRef } from 'react';
import { X } from 'lucide-react';

const openDialogStack = [];
let bodyLockCount = 0;
let previousBodyOverflow = '';

const SIZE_CLASSES = {
  sm: 'max-w-sm',
  md: 'max-w-md',
  lg: 'max-w-lg',
  xl: 'max-w-xl',
  '2xl': 'max-w-2xl',
  '3xl': 'max-w-3xl',
  '4xl': 'max-w-4xl',
  '5xl': 'max-w-5xl',
  '6xl': 'max-w-6xl',
  '7xl': 'max-w-7xl',
  full: 'w-[96vw] max-w-[96vw]',
  workspace: 'w-[94vw] max-w-[1680px]',
};

const FOCUSABLE_TAGS = new Set(['button', 'input', 'select', 'textarea', 'summary']);

function isElementFocusable(el) {
  if (!el || el.nodeType !== 1) return false;
  if (el.disabled || el.hasAttribute?.('disabled')) return false;
  if (el.hidden || el.hasAttribute?.('hidden')) return false;
  if (el.getAttribute?.('aria-hidden') === 'true') return false;

  const tabIndexAttr = el.getAttribute?.('tabindex');
  if (tabIndexAttr === '-1') return false;

  const tag = (el.localName || el.tagName || '').toLowerCase();
  if (FOCUSABLE_TAGS.has(tag)) {
    if (tag === 'input' && (el.type === 'hidden' || el.getAttribute?.('type') === 'hidden')) {
      return false;
    }
    return true;
  }
  if (tag === 'a' && el.hasAttribute?.('href')) {
    return true;
  }
  if (tabIndexAttr !== null && tabIndexAttr !== undefined) {
    const parsed = Number(tabIndexAttr);
    return Number.isFinite(parsed) && parsed >= 0;
  }
  return false;
}

export function getFocusableElements(rootEl) {
  if (!rootEl || !rootEl.childNodes) {
    return [];
  }
  const results = [];
  const walk = (node) => {
    const children = node.childNodes;
    if (!children) return;
    for (let i = 0; i < children.length; i += 1) {
      const child = children[i];
      if (child && child.nodeType === 1) {
        if (isElementFocusable(child)) {
          results.push(child);
        }
        walk(child);
      }
    }
  };
  walk(rootEl);
  return results;
}

export default function ModalDialog({
  isOpen,
  onClose,
  title = null,
  subtitle = null,
  description = null,
  icon = null,
  headerActions = null,
  footer = null,
  children,
  size = 'md', // 'sm' | 'md' | 'lg' | 'xl' | '2xl' | '3xl' | '4xl' | '5xl'
  zIndexClass = 'z-50',
  closeOnEscape = true,
  closeOnBackdropClick = true,
  preventClose = false,
  showCloseButton = true,
  closeButtonLabel = 'Close dialog',
  initialFocusRef = null,
  headingAs = 'h2',
  ariaLabel = undefined,
  ariaLabelledBy = undefined,
  ariaDescribedBy = undefined,
  maxHeightClass = '',
  panelClassName = '',
  headerClassName = '',
  bodyClassName = '',
  footerClassName = '',
}) {
  const reactId = useId();
  const dialogId = `modal-dialog-${reactId}`;
  const titleId = `${dialogId}-title`;
  const descriptionId = `${dialogId}-description`;

  const backdropRef = useRef(null);
  const panelRef = useRef(null);
  const previousFocusRef = useRef(null);
  const mouseDownTargetRef = useRef(null);

  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  const optionsRef = useRef({ closeOnEscape, preventClose });
  optionsRef.current = { closeOnEscape, preventClose };

  useEffect(() => {
    if (!isOpen || typeof document === 'undefined') {
      return undefined;
    }

    const previousFocus = document.activeElement || null;
    previousFocusRef.current = previousFocus;

    openDialogStack.push(dialogId);

    if (document.body?.style) {
      if (bodyLockCount === 0) {
        previousBodyOverflow = document.body.style.overflow || '';
        document.body.style.overflow = 'hidden';
      }
      bodyLockCount += 1;
    }

    // Set initial focus inside the dialog synchronously (safe in both browsers and custom DOM harness)
    const panelEl = panelRef.current;
    if (panelEl) {
      const requestedTarget = initialFocusRef?.current;
      if (requestedTarget && typeof requestedTarget.focus === 'function') {
        requestedTarget.focus();
      } else {
        const focusables = getFocusableElements(panelEl);
        if (focusables.length > 0 && typeof focusables[0].focus === 'function') {
          focusables[0].focus();
        } else if (typeof panelEl.focus === 'function') {
          panelEl.focus();
        }
      }
    }

    const handleKeyDown = (event) => {
      const isTopmost = openDialogStack[openDialogStack.length - 1] === dialogId;
      if (!isTopmost) {
        return;
      }

      const key = event.key || event.code;
      if (key === 'Escape') {
        const { closeOnEscape: allowEsc, preventClose: blocked } = optionsRef.current;
        if (allowEsc && !blocked && typeof onCloseRef.current === 'function') {
          event.preventDefault?.();
          event.stopPropagation?.();
          onCloseRef.current();
        }
        return;
      }

      if (key === 'Tab' && panelRef.current) {
        const focusables = getFocusableElements(panelRef.current);
        if (focusables.length === 0) {
          event.preventDefault?.();
          panelRef.current.focus?.();
          return;
        }

        const firstEl = focusables[0];
        const lastEl = focusables[focusables.length - 1];
        const current = document.activeElement;
        const isInside = panelRef.current.contains?.(current);

        if (event.shiftKey) {
          if (!isInside || current === firstEl || current === panelRef.current) {
            event.preventDefault?.();
            lastEl.focus?.();
          }
        } else if (!isInside || current === lastEl) {
          event.preventDefault?.();
          firstEl.focus?.();
        }
      }
    };

    document.addEventListener('keydown', handleKeyDown);

    return () => {
      document.removeEventListener('keydown', handleKeyDown);

      const idx = openDialogStack.lastIndexOf(dialogId);
      if (idx !== -1) {
        openDialogStack.splice(idx, 1);
      }

      if (document.body?.style && bodyLockCount > 0) {
        bodyLockCount -= 1;
        if (bodyLockCount === 0) {
          document.body.style.overflow = previousBodyOverflow;
        }
      }

      const targetToRestore = previousFocusRef.current;
      previousFocusRef.current = null;
      if (
        targetToRestore &&
        typeof targetToRestore.focus === 'function' &&
        (!document.body?.contains || document.body.contains(targetToRestore))
      ) {
        targetToRestore.focus();
      }
    };
  }, [isOpen, dialogId, initialFocusRef]);

  if (!isOpen) {
    return null;
  }

  const sizeClass = SIZE_CLASSES[size] || (typeof size === 'string' && size.trim() !== '' ? size : SIZE_CLASSES.md);
  const resolvedLabelledBy = ariaLabelledBy || (title ? titleId : undefined);
  const resolvedDescribedBy = ariaDescribedBy || (description ? descriptionId : undefined);
  const HeadingTag = headingAs === 'h3' ? 'h3' : 'h2';

  const handleBackdropMouseDown = (event) => {
    mouseDownTargetRef.current = event.target;
  };

  const handleBackdropClick = (event) => {
    const startedTarget = mouseDownTargetRef.current;
    mouseDownTargetRef.current = null;

    if (
      event.target === backdropRef.current &&
      (startedTarget === null || startedTarget === backdropRef.current) &&
      closeOnBackdropClick &&
      !preventClose &&
      typeof onClose === 'function'
    ) {
      onClose();
    }
  };

  return (
    <div
      ref={backdropRef}
      onMouseDown={handleBackdropMouseDown}
      onClick={handleBackdropClick}
      className={`fixed inset-0 ${zIndexClass} overflow-y-auto bg-slate-900/60 backdrop-blur-sm flex items-center justify-center p-3 sm:p-4 animate-backdrop-enter`}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={!resolvedLabelledBy ? ariaLabel : undefined}
        aria-labelledby={resolvedLabelledBy}
        aria-describedby={resolvedDescribedBy}
        tabIndex={-1}
        className={`relative bg-white rounded-xl shadow-2xl border border-slate-200 ${sizeClass} w-full ${maxHeightClass} flex flex-col overflow-hidden animate-modal-enter focus:outline-none ${panelClassName}`.trim()}
      >
        {title && (
          <div
            className={`px-4 sm:px-6 py-3.5 sm:py-4 bg-slate-50 border-b border-slate-200 flex items-start sm:items-center justify-between gap-3 shrink-0 ${headerClassName}`.trim()}
          >
            <div className="flex items-start sm:items-center space-x-2.5 min-w-0">
              {icon && <div className="shrink-0 mt-0.5 sm:mt-0">{icon}</div>}
              <div className="min-w-0">
                <HeadingTag id={titleId} className="text-base font-semibold text-slate-900 break-words">
                  {title}
                </HeadingTag>
                {subtitle && <p className="text-xs text-slate-500 mt-0.5 break-words">{subtitle}</p>}
              </div>
            </div>

            <div className="flex items-center space-x-2 shrink-0">
              {headerActions}
              {showCloseButton && typeof onClose === 'function' && (
                <button
                  type="button"
                  onClick={onClose}
                  disabled={preventClose}
                  aria-label={closeButtonLabel}
                  className="text-slate-500 hover:text-slate-700 p-1.5 rounded-lg hover:bg-slate-100 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  <X className="w-5 h-5" aria-hidden="true" />
                </button>
              )}
            </div>
          </div>
        )}

        {description && (
          <p id={descriptionId} className="sr-only">
            {description}
          </p>
        )}

        <div className={`flex-1 min-h-0 ${bodyClassName}`.trim()}>{children}</div>

        {footer && (
          <div
            className={`px-4 sm:px-6 py-3.5 sm:py-4 bg-slate-50 border-t border-slate-200 flex flex-wrap items-center justify-end gap-2.5 sm:gap-3 shrink-0 ${footerClassName}`.trim()}
          >
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}

