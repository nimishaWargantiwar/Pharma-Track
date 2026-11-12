import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import ToastRegion from './ToastRegion.jsx';

export const ToastContext = createContext(null);

export const DEFAULT_TOAST_DURATION = 5000;

export function ToastProvider({
  children,
  defaultDuration = DEFAULT_TOAST_DURATION,
  maxToasts = 5,
  renderRegion = true,
}) {
  const [toasts, setToasts] = useState([]);
  const timersRef = useRef(new Map());
  const idCounterRef = useRef(0);
  const lastAddedRef = useRef({ signature: null, at: 0, id: null });

  const clearTimer = useCallback((id) => {
    const existing = timersRef.current.get(id);
    if (existing) {
      clearTimeout(existing);
      timersRef.current.delete(id);
    }
  }, []);

  const dismissToast = useCallback(
    (id) => {
      clearTimer(id);
      setToasts((prev) => prev.filter((item) => item.id !== id));
    },
    [clearTimer]
  );

  const clearToasts = useCallback(() => {
    timersRef.current.forEach((timerId) => clearTimeout(timerId));
    timersRef.current.clear();
    setToasts([]);
  }, []);

  const addToast = useCallback(
    (toastInput) => {
      if (!toastInput) return null;
      const normalized =
        typeof toastInput === 'string'
          ? { type: 'info', message: toastInput }
          : toastInput;

      const type = normalized.type === 'danger' ? 'error' : normalized.type || 'info';
      const message = normalized.message ? String(normalized.message) : '';
      const title = normalized.title ? String(normalized.title) : '';

      if (!message && !title) {
        return null;
      }

      const now = Date.now();
      const signature = `${type}::${title}::${message}`;
      if (
        !normalized.id &&
        lastAddedRef.current.signature === signature &&
        now - lastAddedRef.current.at < 150
      ) {
        return lastAddedRef.current.id;
      }

      idCounterRef.current += 1;
      const id = normalized.id || `toast-${idCounterRef.current}`;
      const duration =
        normalized.duration !== undefined ? normalized.duration : defaultDuration;

      lastAddedRef.current = { signature, at: now, id };

      const newToast = {
        id,
        type,
        title,
        message,
        duration,
        createdAt: now,
      };

      setToasts((prev) => {
        const filtered = prev.filter((item) => item.id !== id);
        const next = [...filtered, newToast];
        if (maxToasts > 0 && next.length > maxToasts) {
          const evicted = next.slice(0, next.length - maxToasts);
          evicted.forEach((oldItem) => clearTimer(oldItem.id));
          return next.slice(next.length - maxToasts);
        }
        return next;
      });

      clearTimer(id);
      if (typeof duration === 'number' && duration > 0) {
        const timerId = setTimeout(() => {
          dismissToast(id);
        }, duration);
        timersRef.current.set(id, timerId);
      }

      return id;
    },
    [clearTimer, defaultDuration, dismissToast, maxToasts]
  );

  useEffect(() => {
    return () => {
      timersRef.current.forEach((timerId) => clearTimeout(timerId));
      timersRef.current.clear();
    };
  }, []);

  const success = useCallback(
    (message, options = {}) => addToast({ ...options, type: 'success', message }),
    [addToast]
  );
  const error = useCallback(
    (message, options = {}) => addToast({ ...options, type: 'error', message }),
    [addToast]
  );
  const warning = useCallback(
    (message, options = {}) => addToast({ ...options, type: 'warning', message }),
    [addToast]
  );
  const info = useCallback(
    (message, options = {}) => addToast({ ...options, type: 'info', message }),
    [addToast]
  );

  const contextValue = useMemo(
    () => ({
      toasts,
      addToast,
      notify: addToast,
      success,
      error,
      warning,
      info,
      dismissToast,
      clearToasts,
    }),
    [toasts, addToast, success, error, warning, info, dismissToast, clearToasts]
  );

  return (
    <ToastContext.Provider value={contextValue}>
      {children}
      {renderRegion && <ToastRegion toasts={toasts} onDismiss={dismissToast} />}
    </ToastContext.Provider>
  );
}

export function useOptionalToast() {
  return useContext(ToastContext);
}

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) {
    throw new Error('useToast must be used within a ToastProvider');
  }
  return ctx;
}

