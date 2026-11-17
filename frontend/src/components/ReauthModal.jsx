import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Lock, ShieldAlert, LogOut, RefreshCw, AlertCircle, User, KeyRound } from 'lucide-react';

/**
 * In-place session re-authentication modal dialog.
 * Prompts the user for their password when their session expires,
 * preserving all active workspace state (AI Copilot history, order context, form inputs).
 */
export default function ReauthModal({
  user,
  reauthReason,
  reauthError,
  sessionState,
  onReauthenticate,
  onSignOut,
}) {
  const [password, setPassword] = useState('');
  const [localError, setLocalError] = useState(null);
  const isSubmitting = sessionState === 'reauth_in_progress';

  const modalRef = useRef(null);
  const passwordInputRef = useRef(null);
  const cancelButtonRef = useRef(null);

  // Auto-focus the password input when modal mounts
  useEffect(() => {
    const timer = setTimeout(() => {
      passwordInputRef.current?.focus();
    }, 50);
    return () => clearTimeout(timer);
  }, []);

  // Trap focus inside modal
  const handleKeyDown = useCallback((e) => {
    if (e.key === 'Escape') {
      // Prevent closing via Escape to ensure session state remains protected
      e.preventDefault();
      return;
    }

    if (e.key === 'Tab') {
      const focusable = modalRef.current?.querySelectorAll(
        'button:not([disabled]), input:not([disabled])'
      );
      if (!focusable || focusable.length === 0) return;

      const first = focusable[0];
      const last = focusable[focusable.length - 1];

      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }
  }, []);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!password.trim() || isSubmitting) {
      return;
    }
    setLocalError(null);
    try {
      await onReauthenticate({ password });
      setPassword('');
    } catch (err) {
      setPassword('');
      setLocalError(err?.message || 'Invalid password. Please try again.');
      passwordInputRef.current?.focus();
    }
  };

  const displayedError = localError || reauthError;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6 bg-slate-900/70 backdrop-blur-sm animate-fadeIn"
      role="dialog"
      aria-modal="true"
      aria-labelledby="reauth-modal-title"
      aria-describedby="reauth-modal-description"
      onKeyDown={handleKeyDown}
    >
      <div
        ref={modalRef}
        className="w-full max-w-md bg-white rounded-2xl shadow-2xl border border-slate-200 overflow-hidden transform transition-all"
      >
        {/* Header */}
        <div className="bg-gradient-to-r from-amber-500 to-amber-600 px-6 py-5 text-white flex items-center space-x-3">
          <div className="p-2.5 bg-white/20 rounded-xl backdrop-blur-md">
            <Lock className="w-6 h-6 text-white" aria-hidden="true" />
          </div>
          <div>
            <h2 id="reauth-modal-title" className="text-lg font-bold tracking-tight">
              Session Re-Authentication
            </h2>
            <p className="text-xs text-amber-100 font-medium">PharmaTrack MES &amp; eBR</p>
          </div>
        </div>

        {/* Body */}
        <div className="p-6 space-y-5">
          {/* Active User Card */}
          <div className="flex items-center justify-between p-3.5 bg-slate-50 border border-slate-200 rounded-xl">
            <div className="flex items-center space-x-3">
              <div className="w-10 h-10 rounded-full bg-pharma-100 text-pharma-700 flex items-center justify-center font-bold text-sm">
                {user?.fullName?.charAt(0) || user?.username?.charAt(0) || 'U'}
              </div>
              <div>
                <p className="text-sm font-semibold text-slate-800">
                  {user?.fullName || user?.username || 'Active User'}
                </p>
                <p className="text-xs text-slate-500 font-mono">@{user?.username}</p>
              </div>
            </div>
            {user?.role && (
              <span className="px-2.5 py-1 text-xs font-semibold rounded-full bg-slate-200 text-slate-700">
                {user.role}
              </span>
            )}
          </div>

          <p id="reauth-modal-description" className="text-xs text-slate-600 leading-relaxed">
            {reauthReason ||
              'Your session has timed out. Re-authenticate to resume your active workflow, pending review tasks, and Copilot context without losing data.'}
          </p>

          {/* Error Message */}
          {displayedError && (
            <div
              role="alert"
              className="flex items-start space-x-2.5 p-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-xs"
            >
              <AlertCircle className="w-4 h-4 mt-0.5 flex-shrink-0 text-red-600" />
              <span>{displayedError}</span>
            </div>
          )}

          {/* Form */}
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label
                htmlFor="reauth-password"
                className="block text-xs font-semibold text-slate-700 mb-1.5"
              >
                Account Password
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-slate-400">
                  <KeyRound className="w-4 h-4" />
                </div>
                <input
                  id="reauth-password"
                  ref={passwordInputRef}
                  type="password"
                  autoComplete="current-password"
                  disabled={isSubmitting}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Enter your password to unlock"
                  className="w-full pl-9 pr-3 py-2 text-sm border border-slate-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-pharma-500 focus:border-pharma-500 disabled:opacity-60 disabled:cursor-not-allowed"
                  required
                />
              </div>
            </div>

            <div className="pt-2 flex flex-col sm:flex-row gap-2.5">
              <button
                type="submit"
                disabled={isSubmitting || !password.trim()}
                className="flex-1 inline-flex items-center justify-center space-x-2 px-4 py-2.5 rounded-lg bg-pharma-600 hover:bg-pharma-700 text-white text-sm font-semibold shadow-sm transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
              >
                {isSubmitting ? (
                  <>
                    <RefreshCw className="w-4 h-4 animate-spin" />
                    <span>Verifying...</span>
                  </>
                ) : (
                  <>
                    <Lock className="w-4 h-4" />
                    <span>Re-Authenticate</span>
                  </>
                )}
              </button>

              <button
                ref={cancelButtonRef}
                type="button"
                disabled={isSubmitting}
                onClick={onSignOut}
                className="inline-flex items-center justify-center space-x-1.5 px-3 py-2.5 rounded-lg border border-slate-300 bg-white hover:bg-slate-50 text-slate-700 text-sm font-semibold transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                title="Sign out and switch to another account"
              >
                <LogOut className="w-4 h-4 text-slate-500" />
                <span>Sign Out</span>
              </button>
            </div>
          </form>
        </div>

        {/* Footer info */}
        <div className="bg-slate-50 px-6 py-3 border-t border-slate-100 flex items-center justify-between text-[11px] text-slate-500">
          <span className="flex items-center space-x-1">
            <ShieldAlert className="w-3.5 h-3.5 text-slate-400" />
            <span>21 CFR Part 11 Session Guard</span>
          </span>
          <span>PharmaTrack v1.0</span>
        </div>
      </div>
    </div>
  );
}
