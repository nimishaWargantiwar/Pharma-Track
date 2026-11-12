import React, { useEffect, useId, useRef, useState } from 'react';
import { Check, Eye, EyeOff, KeyRound, X } from 'lucide-react';
import { InlineAlert, ModalDialog, StatusBadge } from './ui/index.js';
import { getRoleMetadata } from '../utils/rbacUtils.js';
import {
  getPasswordPolicyChecks,
  validatePasswordInput,
} from '../utils/userAccountValidation.js';

export default function UserPasswordResetModal({
  isOpen,
  user,
  isSelf = false,
  onClose,
  onSave,
  isSubmitting,
}) {
  const baseId = useId();
  const newPasswordRef = useRef(null);

  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showNewPassword, setShowNewPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [errors, setErrors] = useState({});
  const [serverError, setServerError] = useState('');

  useEffect(() => {
    setNewPassword('');
    setConfirmPassword('');
    setShowNewPassword(false);
    setShowConfirmPassword(false);
    setErrors({});
    setServerError('');
  }, [isOpen, user]);

  const validate = () => {
    const nextErrors = {};
    const passwordErr = validatePasswordInput(newPassword, user?.username || '');
    if (passwordErr) {
      nextErrors.newPassword = passwordErr;
    }

    if (!confirmPassword) {
      nextErrors.confirmPassword = 'Please confirm the new temporary password';
    } else if (confirmPassword !== newPassword) {
      nextErrors.confirmPassword = 'Confirmation password does not match';
    }

    setErrors(nextErrors);
    return Object.keys(nextErrors).length === 0;
  };

  const handleSubmit = async (e) => {
    e?.preventDefault?.();
    if (!user) return;
    setServerError('');

    if (!validate()) {
      return;
    }

    try {
      await onSave(user, newPassword);
    } catch (err) {
      if (err.validationErrors?.newPassword) {
        setErrors({ newPassword: err.validationErrors.newPassword });
      } else {
        setServerError(err.message || 'Failed to reset user password');
      }
    }
  };

  const policy = getPasswordPolicyChecks(newPassword, user?.username || '');
  const roleMeta = getRoleMetadata(user?.role);

  const newPwdId = `${baseId}-reset-new-password`;
  const newPwdErrorId = `${baseId}-reset-new-password-error`;
  const confirmPwdId = `${baseId}-reset-confirm-password`;
  const confirmPwdErrorId = `${baseId}-reset-confirm-password-error`;

  return (
    <ModalDialog
      isOpen={isOpen && Boolean(user)}
      onClose={onClose}
      preventClose={isSubmitting}
      size="md"
      headingAs="h3"
      initialFocusRef={newPasswordRef}
      title="Reset Temporary Password"
      subtitle="Set a new BCrypt-hashed temporary password and revoke active sessions."
      icon={
        <div className="w-8 h-8 rounded-lg bg-slate-800 flex items-center justify-center text-white">
          <KeyRound className="w-4 h-4" aria-hidden="true" />
        </div>
      }
      bodyClassName="overflow-y-auto"
      maxHeightClass="max-h-[90vh]"
      footer={
        <>
          <button
            type="button"
            onClick={onClose}
            disabled={isSubmitting}
            className="px-4 py-2 text-sm font-medium text-slate-600 hover:text-slate-800 bg-slate-100 hover:bg-slate-200 rounded-lg transition-colors disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={isSubmitting}
            className="inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors disabled:opacity-50"
          >
            <KeyRound className="w-4 h-4" aria-hidden="true" />
            <span>{isSubmitting ? 'Resetting...' : 'Reset Password'}</span>
          </button>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate className="p-4 sm:p-6 space-y-4">
        {serverError && (
          <InlineAlert
            variant="error"
            compact
            role="alert"
            message={serverError}
            onDismiss={() => setServerError('')}
            dismissLabel="Dismiss password reset error"
          />
        )}

        {user && (
          <div className="bg-slate-50 border border-slate-200 rounded-lg p-3.5 space-y-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="min-w-0">
                <div className="text-sm font-semibold text-slate-900 truncate">
                  {user.fullName}
                </div>
                <div className="text-xs font-mono text-slate-600">@{user.username}</div>
              </div>
              <div className="flex flex-wrap items-center gap-1.5">
                <span
                  className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold border ${roleMeta.badgeClass}`}
                >
                  {roleMeta.shortLabel}
                </span>
                <StatusBadge
                  status={user.enabled ? 'ENABLED' : 'DISABLED'}
                  tone={user.enabled ? 'success' : 'muted'}
                  label={user.enabled ? 'Enabled' : 'Disabled'}
                  size="xs"
                  showDot
                />
              </div>
            </div>
            {user.enabled === false && (
              <p className="text-xs text-slate-600">
                This account is currently disabled. Resetting its password keeps the account disabled until explicitly re-enabled.
              </p>
            )}
          </div>
        )}

        <div>
          <label
            htmlFor={newPwdId}
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
          >
            New Temporary Password <span className="text-rose-500">*</span>
          </label>
          <div className="relative">
            <input
              ref={newPasswordRef}
              id={newPwdId}
              type={showNewPassword ? 'text' : 'password'}
              name="newPassword"
              autoComplete="new-password"
              value={newPassword}
              onChange={(e) => {
                setNewPassword(e.target.value);
                if (errors.newPassword) {
                  setErrors((prev) => ({ ...prev, newPassword: '' }));
                }
              }}
              placeholder="Enter new temporary password"
              disabled={isSubmitting}
              aria-invalid={Boolean(errors.newPassword)}
              aria-describedby={errors.newPassword ? newPwdErrorId : undefined}
              className={`w-full pl-3 pr-10 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
                errors.newPassword
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
              }`}
            />
            <button
              type="button"
              onClick={() => setShowNewPassword((prev) => !prev)}
              disabled={isSubmitting}
              aria-label={showNewPassword ? 'Hide new password' : 'Show new password'}
              className="absolute right-2.5 top-1/2 -translate-y-1/2 p-1 rounded text-slate-500 hover:text-slate-700 hover:bg-slate-100"
            >
              {showNewPassword ? (
                <EyeOff className="w-4 h-4" aria-hidden="true" />
              ) : (
                <Eye className="w-4 h-4" aria-hidden="true" />
              )}
            </button>
          </div>
          {errors.newPassword && (
            <p id={newPwdErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {errors.newPassword}
            </p>
          )}
        </div>

        <div>
          <label
            htmlFor={confirmPwdId}
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
          >
            Confirm Temporary Password <span className="text-rose-500">*</span>
          </label>
          <div className="relative">
            <input
              id={confirmPwdId}
              type={showConfirmPassword ? 'text' : 'password'}
              name="confirmPassword"
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(e) => {
                setConfirmPassword(e.target.value);
                if (errors.confirmPassword) {
                  setErrors((prev) => ({ ...prev, confirmPassword: '' }));
                }
              }}
              placeholder="Re-enter temporary password"
              disabled={isSubmitting}
              aria-invalid={Boolean(errors.confirmPassword)}
              aria-describedby={errors.confirmPassword ? confirmPwdErrorId : undefined}
              className={`w-full pl-3 pr-10 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
                errors.confirmPassword
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
              }`}
            />
            <button
              type="button"
              onClick={() => setShowConfirmPassword((prev) => !prev)}
              disabled={isSubmitting}
              aria-label={showConfirmPassword ? 'Hide confirm password' : 'Show confirm password'}
              className="absolute right-2.5 top-1/2 -translate-y-1/2 p-1 rounded text-slate-500 hover:text-slate-700 hover:bg-slate-100"
            >
              {showConfirmPassword ? (
                <EyeOff className="w-4 h-4" aria-hidden="true" />
              ) : (
                <Eye className="w-4 h-4" aria-hidden="true" />
              )}
            </button>
          </div>
          {errors.confirmPassword && (
            <p id={confirmPwdErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {errors.confirmPassword}
            </p>
          )}
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5 text-xs pt-0.5">
          {[
            { ok: policy.minLength && policy.maxBytes, label: '8–72 UTF-8 bytes' },
            { ok: policy.hasLetterAndDigit, label: 'Includes letter & digit' },
            { ok: policy.notUsername, label: 'Differs from username' },
            { ok: Boolean(confirmPassword) && confirmPassword === newPassword, label: 'Passwords match' },
          ].map((item) => (
            <div
              key={item.label}
              className={`flex items-center space-x-1.5 ${
                item.ok ? 'text-emerald-700 font-medium' : 'text-slate-500'
              }`}
            >
              {item.ok ? (
                <Check className="w-3.5 h-3.5 text-emerald-600 shrink-0" aria-hidden="true" />
              ) : (
                <X className="w-3.5 h-3.5 text-slate-400 shrink-0" aria-hidden="true" />
              )}
              <span>{item.label}</span>
            </div>
          ))}
        </div>

        <InlineAlert
          variant={isSelf ? 'warning' : 'info'}
          compact
          message={
            isSelf
              ? 'You are resetting your own password. Your active session will immediately expire and you will need to sign in with your new temporary password.'
              : 'Resetting a password invalidates all active JWT sessions for this account and records a redacted USER_PASSWORD_RESET entry in the audit trail.'
          }
        />
      </form>
    </ModalDialog>
  );
}

