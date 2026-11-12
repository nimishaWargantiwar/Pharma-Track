import React, { useId, useRef, useState } from 'react';
import {
  AlertCircle,
  Eye,
  EyeOff,
  Lock,
  LogIn,
  Pill,
  RefreshCw,
  ShieldCheck,
  User,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext.jsx';

export default function LoginPage({ backendStatus = 'checking', healthOutcome = null, onRetryHealth = null }) {
  const { login, sessionMessage, clearSessionMessage } = useAuth();
  const mainRef = useRef(null);
  const usernameErrorId = useId();
  const passwordErrorId = useId();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [errorMessage, setErrorMessage] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (isSubmitting) {
      return;
    }

    const trimmedUsername = username.trim();
    const nextErrors = {};
    if (!trimmedUsername) {
      nextErrors.username = 'Username is required.';
    }
    if (!password) {
      nextErrors.password = 'Password is required.';
    }

    if (Object.keys(nextErrors).length > 0) {
      setFieldErrors(nextErrors);
      setErrorMessage('Please enter both username and password.');
      return;
    }

    setFieldErrors({});
    setErrorMessage(null);
    if (sessionMessage && clearSessionMessage) {
      clearSessionMessage();
    }
    setIsSubmitting(true);

    try {
      await login({ username: trimmedUsername, password });
    } catch (err) {
      if (err?.validationErrors && typeof err.validationErrors === 'object') {
        setFieldErrors(err.validationErrors);
      }
      setErrorMessage(
        err?.message || 'Unable to sign in. Please verify your credentials and try again.'
      );
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col justify-between">
      <a
        href="#login-main-content"
        onClick={(e) => {
          e.preventDefault();
          mainRef.current?.focus?.();
          mainRef.current?.scrollIntoView?.();
        }}
        className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:px-3.5 focus:py-2 focus:rounded-lg focus:bg-pharma-900 focus:text-white focus:text-xs focus:font-semibold focus:shadow-lg focus:ring-2 focus:ring-pharma-400"
      >
        Skip to sign in form
      </a>

      {/* Top Status Strip */}
      <header className="w-full border-b border-pharma-800 bg-pharma-900 text-white shadow-sm">
        <div className="max-w-7xl mx-auto px-3 sm:px-6 lg:px-8 h-14 flex items-center justify-between gap-2">
          <div className="flex items-center space-x-2 sm:space-x-2.5 min-w-0">
            <div className="w-8 h-8 rounded-lg bg-pharma-600 border border-pharma-500/40 flex items-center justify-center text-white shadow-sm shrink-0">
              <Pill className="w-4 h-4" aria-hidden="true" />
            </div>
            <span className="text-sm sm:text-base font-bold tracking-tight text-white whitespace-nowrap">
              PharmaTrack
            </span>
            <span className="hidden sm:inline-block px-2 py-0.5 text-[11px] font-semibold bg-pharma-800 text-sky-200 rounded-full border border-pharma-700 whitespace-nowrap">
              MES &amp; eBR
            </span>
          </div>

          <div className="flex items-center space-x-1.5 sm:space-x-2 text-xs bg-[#091b2e]/70 py-1 px-2.5 rounded-full border border-pharma-800 whitespace-nowrap shrink-0">
            {backendStatus === 'connected' && healthOutcome !== 'degraded' && (
              <>
                <span className="h-2 w-2 rounded-full bg-emerald-400 shrink-0"></span>
                <span className="text-emerald-300 font-medium">API Online</span>
              </>
            )}
            {(backendStatus === 'checking' || healthOutcome === 'checking') && (
              <>
                <RefreshCw className="w-3 h-3 animate-spin text-sky-300 shrink-0" aria-hidden="true" />
                <span className="text-slate-200">Checking API...</span>
              </>
            )}
            {backendStatus !== 'checking' && healthOutcome === 'degraded' && (
              <>
                <span className="h-2 w-2 rounded-full bg-amber-400 shrink-0"></span>
                <span className="text-amber-300 font-medium">API Degraded</span>
              </>
            )}
            {backendStatus === 'disconnected' && healthOutcome !== 'degraded' && (
              <>
                <span className="h-2 w-2 rounded-full bg-rose-400 shrink-0"></span>
                <span className="text-rose-300 font-medium">API Unreachable</span>
              </>
            )}
            {typeof onRetryHealth === 'function' && (
              <button
                type="button"
                onClick={onRetryHealth}
                title="Check backend status"
                aria-label="Check backend status"
                className="text-slate-300 hover:text-white transition-colors shrink-0"
              >
                <RefreshCw className="w-3 h-3" aria-hidden="true" />
              </button>
            )}
          </div>
        </div>
      </header>

      {/* Centered Login Card */}
      <main
        ref={mainRef}
        id="login-main-content"
        tabIndex={-1}
        className="flex-1 flex items-center justify-center px-4 py-8 sm:py-10 sm:px-6 focus:outline-none"
      >
        <div className="w-full max-w-md">
          <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
            <div className="px-5 sm:px-6 pt-6 sm:pt-7 pb-5 sm:pb-6 border-b border-slate-100 bg-gradient-to-b from-pharma-50/70 to-white">
              <div className="flex items-center space-x-3 mb-3">
                <div className="w-11 h-11 rounded-xl bg-pharma-900 flex items-center justify-center text-white shadow-sm shrink-0">
                  <Pill className="w-6 h-6" aria-hidden="true" />
                </div>
                <div className="min-w-0">
                  <h1 className="text-lg sm:text-xl font-bold text-slate-900 tracking-tight">
                    PharmaTrack MES &amp; eBR
                  </h1>
                  <p className="text-xs font-medium text-pharma-700">
                    Pharmaceutical Manufacturing &amp; Quality Operations
                  </p>
                </div>
              </div>
              <p className="text-sm text-slate-600">
                Sign in with your assigned user account to access manufacturing, execution, and quality modules.
              </p>
            </div>

            <form onSubmit={handleSubmit} className="p-5 sm:p-6 space-y-5" noValidate>
              {sessionMessage && (
                <div
                  role="status"
                  className="rounded-lg border border-amber-200 bg-amber-50 px-3.5 py-2.5 text-sm text-amber-800 flex items-start space-x-2"
                >
                  <AlertCircle className="w-4 h-4 mt-0.5 shrink-0 text-amber-600" aria-hidden="true" />
                  <div className="flex-1">{sessionMessage}</div>
                </div>
              )}

              {errorMessage && (
                <div
                  role="alert"
                  className="rounded-lg border border-rose-200 bg-rose-50 px-3.5 py-2.5 text-sm text-rose-800 flex items-start space-x-2"
                >
                  <AlertCircle className="w-4 h-4 mt-0.5 shrink-0 text-rose-600" aria-hidden="true" />
                  <div className="flex-1">{errorMessage}</div>
                </div>
              )}

              <div>
                <label
                  htmlFor="login-username"
                  className="block text-xs font-semibold uppercase tracking-wider text-slate-600 mb-1.5"
                >
                  Username
                </label>
                <div className="relative">
                  <User className="w-4 h-4 text-slate-500 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" aria-hidden="true" />
                  <input
                    id="login-username"
                    name="username"
                    type="text"
                    autoComplete="username"
                    autoFocus
                    disabled={isSubmitting}
                    value={username}
                    aria-invalid={fieldErrors.username ? 'true' : undefined}
                    aria-describedby={fieldErrors.username ? usernameErrorId : undefined}
                    onChange={(e) => {
                      setUsername(e.target.value);
                      if (fieldErrors.username) {
                        setFieldErrors((prev) => ({ ...prev, username: undefined }));
                      }
                    }}
                    placeholder="Enter username"
                    className={`w-full pl-9 pr-3 py-2.5 text-sm rounded-lg border bg-white text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 transition-colors disabled:bg-slate-50 disabled:text-slate-500 ${
                      fieldErrors.username
                        ? 'border-rose-300 focus:border-rose-500 focus:ring-rose-200'
                        : 'border-slate-300 focus:border-pharma-500 focus:ring-pharma-200'
                    }`}
                  />
                </div>
                {fieldErrors.username && (
                  <p id={usernameErrorId} role="alert" className="mt-1 text-xs text-rose-600">
                    {fieldErrors.username}
                  </p>
                )}
              </div>

              <div>
                <label
                  htmlFor="login-password"
                  className="block text-xs font-semibold uppercase tracking-wider text-slate-600 mb-1.5"
                >
                  Password
                </label>
                <div className="relative">
                  <Lock className="w-4 h-4 text-slate-500 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" aria-hidden="true" />
                  <input
                    id="login-password"
                    name="password"
                    type={showPassword ? 'text' : 'password'}
                    autoComplete="current-password"
                    disabled={isSubmitting}
                    value={password}
                    aria-invalid={fieldErrors.password ? 'true' : undefined}
                    aria-describedby={fieldErrors.password ? passwordErrorId : undefined}
                    onChange={(e) => {
                      setPassword(e.target.value);
                      if (fieldErrors.password) {
                        setFieldErrors((prev) => ({ ...prev, password: undefined }));
                      }
                    }}
                    placeholder="Enter password"
                    className={`w-full pl-9 pr-10 py-2.5 text-sm rounded-lg border bg-white text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 transition-colors disabled:bg-slate-50 disabled:text-slate-500 ${
                      fieldErrors.password
                        ? 'border-rose-300 focus:border-rose-500 focus:ring-rose-200'
                        : 'border-slate-300 focus:border-pharma-500 focus:ring-pharma-200'
                    }`}
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((prev) => !prev)}
                    aria-label={showPassword ? 'Hide password' : 'Show password'}
                    title={showPassword ? 'Hide password' : 'Show password'}
                    disabled={isSubmitting}
                    className="absolute right-2.5 top-1/2 -translate-y-1/2 p-1 text-slate-500 hover:text-slate-700 rounded transition-colors disabled:opacity-50"
                  >
                    {showPassword ? <EyeOff className="w-4 h-4" aria-hidden="true" /> : <Eye className="w-4 h-4" aria-hidden="true" />}
                  </button>
                </div>
                {fieldErrors.password && (
                  <p id={passwordErrorId} role="alert" className="mt-1 text-xs text-rose-600">
                    {fieldErrors.password}
                  </p>
                )}
              </div>

              <button
                type="submit"
                disabled={isSubmitting}
                className="w-full inline-flex items-center justify-center space-x-2 px-4 py-2.5 rounded-lg bg-pharma-600 hover:bg-pharma-700 text-white text-sm font-semibold shadow-sm transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
              >
                {isSubmitting ? (
                  <>
                    <RefreshCw className="w-4 h-4 animate-spin" aria-hidden="true" />
                    <span>Signing in...</span>
                  </>
                ) : (
                  <>
                    <LogIn className="w-4 h-4" aria-hidden="true" />
                    <span>Sign In</span>
                  </>
                )}
              </button>

              <div className="pt-2.5 border-t border-slate-100 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-1.5 text-xs text-slate-500">
                <span className="inline-flex items-center space-x-1">
                  <ShieldCheck className="w-3.5 h-3.5 text-pharma-600 shrink-0" aria-hidden="true" />
                  <span>JWT Role-Based Access</span>
                </span>
                <span>Account provisioning by Admin only</span>
              </div>
            </form>
          </div>
        </div>
      </main>

      <footer className="py-4 px-4 text-center text-xs text-slate-500">
        PharmaTrack MES &amp; eBR &bull; Authenticated Audit-Attributed Session
      </footer>
    </div>
  );
}

