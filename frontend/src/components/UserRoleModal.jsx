import React, { useEffect, useId, useRef, useState } from 'react';
import { Shield } from 'lucide-react';
import { InlineAlert, ModalDialog, StatusBadge } from './ui/index.js';
import { ALL_ROLES, getRoleMetadata, ROLE_METADATA, ROLES } from '../utils/rbacUtils.js';

export default function UserRoleModal({
  isOpen,
  user,
  isSelf = false,
  isSoleEnabledAdmin = false,
  onClose,
  onSave,
  isSubmitting,
}) {
  const baseId = useId();
  const roleSelectRef = useRef(null);

  const [selectedRole, setSelectedRole] = useState(ROLES.OPERATOR);
  const [error, setError] = useState('');
  const [serverError, setServerError] = useState('');

  useEffect(() => {
    if (isOpen && user) {
      setSelectedRole(user.role || ROLES.OPERATOR);
      setError('');
      setServerError('');
    } else if (!isOpen) {
      setError('');
      setServerError('');
    }
  }, [isOpen, user]);

  const wouldDemoteLastAdmin =
    Boolean(isSoleEnabledAdmin) &&
    user?.role === ROLES.ADMIN &&
    selectedRole !== ROLES.ADMIN;

  const handleSubmit = async (e) => {
    e?.preventDefault?.();
    if (!user) return;
    setServerError('');

    if (!selectedRole || !ALL_ROLES.includes(selectedRole)) {
      setError('Role is mandatory');
      return;
    }

    if (wouldDemoteLastAdmin) {
      setError('Cannot demote the last enabled ADMIN account');
      return;
    }

    try {
      await onSave(user, selectedRole);
    } catch (err) {
      if (err.validationErrors?.role) {
        setError(err.validationErrors.role);
      } else {
        setServerError(err.message || 'Failed to update user role');
      }
    }
  };

  const roleSelectId = `${baseId}-role-select`;
  const roleErrorId = `${baseId}-role-error`;
  const currentRoleMeta = getRoleMetadata(user?.role);

  return (
    <ModalDialog
      isOpen={isOpen && Boolean(user)}
      onClose={onClose}
      preventClose={isSubmitting}
      size="md"
      headingAs="h3"
      initialFocusRef={roleSelectRef}
      title="Change Assigned Role"
      subtitle="Update the user's RBAC authorization role across PharmaTrack modules."
      icon={
        <div className="w-8 h-8 rounded-lg bg-indigo-600 flex items-center justify-center text-white">
          <Shield className="w-4 h-4" aria-hidden="true" />
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
            className="inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-semibold text-white bg-indigo-600 hover:bg-indigo-700 rounded-lg shadow-sm transition-colors disabled:opacity-50"
          >
            <Shield className="w-4 h-4" aria-hidden="true" />
            <span>{isSubmitting ? 'Updating...' : 'Update Role'}</span>
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
            dismissLabel="Dismiss role error"
          />
        )}

        {user && (
          <div className="bg-slate-50 border border-slate-200 rounded-lg p-3.5 flex flex-wrap items-center justify-between gap-2">
            <div className="min-w-0">
              <div className="text-sm font-semibold text-slate-900 truncate">
                {user.fullName}
              </div>
              <div className="text-xs font-mono text-slate-600">@{user.username}</div>
            </div>
            <div className="flex flex-wrap items-center gap-1.5">
              <span
                className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold border ${currentRoleMeta.badgeClass}`}
              >
                Current: {currentRoleMeta.shortLabel}
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
        )}

        <div>
          <label
            htmlFor={roleSelectId}
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
          >
            New Role <span className="text-rose-500">*</span>
          </label>
          <select
            ref={roleSelectRef}
            id={roleSelectId}
            name="role"
            value={selectedRole}
            onChange={(e) => {
              setSelectedRole(e.target.value);
              if (error) setError('');
            }}
            disabled={isSubmitting}
            aria-invalid={Boolean(error || wouldDemoteLastAdmin)}
            aria-describedby={error ? roleErrorId : undefined}
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 bg-white ${
              error || wouldDemoteLastAdmin
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
            }`}
          >
            {ALL_ROLES.map((roleKey) => (
              <option key={roleKey} value={roleKey}>
                {ROLE_METADATA[roleKey]?.label || roleKey}
              </option>
            ))}
          </select>
          {error && (
            <p id={roleErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {error}
            </p>
          )}
        </div>

        {isSoleEnabledAdmin && (
          <InlineAlert
            variant="warning"
            compact
            message="This is the last enabled Administrator account. Demoting it below Administrator is blocked to prevent administrative lockout."
          />
        )}

        <InlineAlert
          variant={isSelf && selectedRole !== user?.role ? 'warning' : 'info'}
          compact
          message={
            isSelf && selectedRole !== user?.role
              ? 'You are changing your own assigned role. Your active JWT session will be invalidated immediately and you will be signed out.'
              : 'Updating a user role increments tokenVersion and immediately invalidates any active sessions held by that account.'
          }
        />
      </form>
    </ModalDialog>
  );
}

