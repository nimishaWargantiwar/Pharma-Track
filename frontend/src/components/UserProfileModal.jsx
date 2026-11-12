import React, { useEffect, useId, useRef, useState } from 'react';
import { Save, UserCog } from 'lucide-react';
import { InlineAlert, ModalDialog, StatusBadge } from './ui/index.js';
import { getRoleMetadata } from '../utils/rbacUtils.js';
import { validateFullNameInput } from '../utils/userAccountValidation.js';

export default function UserProfileModal({ isOpen, user, onClose, onSave, isSubmitting }) {
  const baseId = useId();
  const fullNameRef = useRef(null);

  const [fullName, setFullName] = useState('');
  const [error, setError] = useState('');
  const [serverError, setServerError] = useState('');

  useEffect(() => {
    if (isOpen && user) {
      setFullName(user.fullName || '');
      setError('');
      setServerError('');
    } else if (!isOpen) {
      setFullName('');
      setError('');
      setServerError('');
    }
  }, [isOpen, user]);

  const handleSubmit = async (e) => {
    e?.preventDefault?.();
    if (!user) return;
    setServerError('');

    const validationMessage = validateFullNameInput(fullName);
    if (validationMessage) {
      setError(validationMessage);
      return;
    }

    try {
      await onSave(user, { fullName: fullName.trim() });
    } catch (err) {
      if (err.validationErrors?.fullName) {
        setError(err.validationErrors.fullName);
      } else {
        setServerError(err.message || 'Failed to update user profile');
      }
    }
  };

  const fullNameInputId = `${baseId}-profile-full-name`;
  const fullNameErrorId = `${baseId}-profile-full-name-error`;
  const roleMeta = getRoleMetadata(user?.role);

  return (
    <ModalDialog
      isOpen={isOpen && Boolean(user)}
      onClose={onClose}
      preventClose={isSubmitting}
      size="md"
      headingAs="h3"
      initialFocusRef={fullNameRef}
      title="Edit User Profile"
      subtitle="Update account display metadata without altering immutable username attribution."
      icon={
        <div className="w-8 h-8 rounded-lg bg-pharma-600 flex items-center justify-center text-white">
          <UserCog className="w-4 h-4" aria-hidden="true" />
        </div>
      }
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
            <Save className="w-4 h-4" aria-hidden="true" />
            <span>{isSubmitting ? 'Saving...' : 'Save Profile'}</span>
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
            dismissLabel="Dismiss form error"
          />
        )}

        {user && (
          <div className="bg-slate-50 border border-slate-200 rounded-lg p-3.5 space-y-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="min-w-0">
                <div className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Account Username (Immutable)
                </div>
                <div className="font-mono text-sm font-semibold text-slate-900 mt-0.5">
                  @{user.username}
                </div>
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
            <p className="text-xs text-slate-500">
              Username cannot be edited after creation so eBR steps, QC checks, deviations, and audit logs keep accurate historical attribution.
            </p>
          </div>
        )}

        <div>
          <label
            htmlFor={fullNameInputId}
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
          >
            Full Name <span className="text-rose-500">*</span>
          </label>
          <input
            ref={fullNameRef}
            id={fullNameInputId}
            type="text"
            name="fullName"
            value={fullName}
            onChange={(e) => {
              setFullName(e.target.value);
              if (error) setError('');
            }}
            placeholder="e.g. Dr. Kavita Iyer"
            disabled={isSubmitting}
            aria-invalid={Boolean(error)}
            aria-describedby={error ? fullNameErrorId : undefined}
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
              error
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
            }`}
          />
          {error && (
            <p id={fullNameErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {error}
            </p>
          )}
        </div>
      </form>
    </ModalDialog>
  );
}

