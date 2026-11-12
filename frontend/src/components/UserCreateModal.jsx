import React, { useEffect, useId, useRef, useState } from 'react';
import { Check, Eye, EyeOff, UserPlus, X } from 'lucide-react';
import { InlineAlert, ModalDialog, StatusBadge } from './ui/index.js';
import { ALL_ROLES, ROLE_METADATA, ROLES } from '../utils/rbacUtils.js';
import {
  getPasswordPolicyChecks,
  validateFullNameInput,
  validatePasswordInput,
  validateUsernameInput,
} from '../utils/userAccountValidation.js';

function buildInitialCreateForm() {
  return {
    fullName: '',
    username: '',
    password: '',
    role: ROLES.OPERATOR,
    enabled: true,
  };
}

export default function UserCreateModal({ isOpen, onClose, onSave, isSubmitting }) {
  const baseId = useId();
  const fullNameRef = useRef(null);

  const [formData, setFormData] = useState(buildInitialCreateForm);
  const [errors, setErrors] = useState({});
  const [serverError, setServerError] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  useEffect(() => {
    setFormData(buildInitialCreateForm());
    setErrors({});
    setServerError('');
    setShowPassword(false);
  }, [isOpen]);

  const validate = () => {
    const nextErrors = {};
    const fullNameErr = validateFullNameInput(formData.fullName);
    if (fullNameErr) {
      nextErrors.fullName = fullNameErr;
    }

    const usernameErr = validateUsernameInput(formData.username);
    if (usernameErr) {
      nextErrors.username = usernameErr;
    }

    const passwordErr = validatePasswordInput(formData.password, formData.username);
    if (passwordErr) {
      nextErrors.password = passwordErr;
    }

    if (!formData.role || !ALL_ROLES.includes(formData.role)) {
      nextErrors.role = 'Role is required';
    }

    setErrors(nextErrors);
    return Object.keys(nextErrors).length === 0;
  };

  const handleFieldChange = (field, value) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
    if (errors[field]) {
      setErrors((prev) => ({ ...prev, [field]: '' }));
    }
  };

  const handleSubmit = async (e) => {
    e?.preventDefault?.();
    setServerError('');
    if (!validate()) {
      return;
    }

    try {
      await onSave({
        fullName: formData.fullName.trim(),
        username: formData.username.trim().toLowerCase(),
        password: formData.password,
        role: formData.role,
        enabled: Boolean(formData.enabled),
      });
    } catch (err) {
      if (err.validationErrors && Object.keys(err.validationErrors).length > 0) {
        setErrors(err.validationErrors);
      } else {
        setServerError(err.message || 'Unable to create user account');
      }
    }
  };

  const policy = getPasswordPolicyChecks(formData.password, formData.username);

  const fullNameInputId = `${baseId}-create-full-name`;
  const fullNameErrorId = `${baseId}-create-full-name-error`;
  const usernameInputId = `${baseId}-create-username`;
  const usernameErrorId = `${baseId}-create-username-error`;
  const usernameHintId = `${baseId}-create-username-hint`;
  const passwordInputId = `${baseId}-create-password`;
  const passwordErrorId = `${baseId}-create-password-error`;
  const roleSelectId = `${baseId}-create-role`;
  const roleErrorId = `${baseId}-create-role-error`;
  const statusEnabledId = `${baseId}-create-status-enabled`;
  const statusDisabledId = `${baseId}-create-status-disabled`;

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={onClose}
      preventClose={isSubmitting}
      size="lg"
      headingAs="h3"
      initialFocusRef={fullNameRef}
      title="Provision New User Account"
      subtitle="Create an authenticated user account with role-based permissions and a temporary password."
      icon={
        <div className="w-8 h-8 rounded-lg bg-pharma-600 flex items-center justify-center text-white">
          <UserPlus className="w-4 h-4" aria-hidden="true" />
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
            <UserPlus className="w-4 h-4" aria-hidden="true" />
            <span>{isSubmitting ? 'Provisioning...' : 'Create User'}</span>
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
            value={formData.fullName}
            onChange={(e) => handleFieldChange('fullName', e.target.value)}
            placeholder="e.g. Dr. Priya Nair"
            disabled={isSubmitting}
            aria-invalid={Boolean(errors.fullName)}
            aria-describedby={errors.fullName ? fullNameErrorId : undefined}
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
              errors.fullName
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
            }`}
          />
          {errors.fullName && (
            <p id={fullNameErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {errors.fullName}
            </p>
          )}
        </div>

        <div>
          <label
            htmlFor={usernameInputId}
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
          >
            Username <span className="text-rose-500">*</span>
          </label>
          <input
            id={usernameInputId}
            type="text"
            name="username"
            autoComplete="off"
            value={formData.username}
            onChange={(e) => handleFieldChange('username', e.target.value)}
            placeholder="e.g. p.nair"
            disabled={isSubmitting}
            aria-invalid={Boolean(errors.username)}
            aria-describedby={
              errors.username ? `${usernameErrorId} ${usernameHintId}` : usernameHintId
            }
            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors font-mono ${
              errors.username
                ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
            }`}
          />
          <p id={usernameHintId} className="mt-1 text-xs text-slate-500">
            Normalized to lowercase (3–50 chars: letters, digits, dots, underscores, hyphens). Immutable after provisioning to preserve audit attribution.
          </p>
          {errors.username && (
            <p id={usernameErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {errors.username}
            </p>
          )}
        </div>

        <div>
          <label
            htmlFor={passwordInputId}
            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
          >
            Temporary Password <span className="text-rose-500">*</span>
          </label>
          <div className="relative">
            <input
              id={passwordInputId}
              type={showPassword ? 'text' : 'password'}
              name="password"
              autoComplete="new-password"
              value={formData.password}
              onChange={(e) => handleFieldChange('password', e.target.value)}
              placeholder="Enter temporary password (min 8 chars)"
              disabled={isSubmitting}
              aria-invalid={Boolean(errors.password)}
              aria-describedby={errors.password ? passwordErrorId : undefined}
              className={`w-full pl-3 pr-10 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 transition-colors ${
                errors.password
                  ? 'border-rose-300 focus:ring-rose-200 bg-rose-50/30'
                  : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500 bg-white'
              }`}
            />
            <button
              type="button"
              onClick={() => setShowPassword((prev) => !prev)}
              disabled={isSubmitting}
              aria-label={showPassword ? 'Hide temporary password' : 'Show temporary password'}
              className="absolute right-2.5 top-1/2 -translate-y-1/2 p-1 rounded text-slate-500 hover:text-slate-700 hover:bg-slate-100"
            >
              {showPassword ? (
                <EyeOff className="w-4 h-4" aria-hidden="true" />
              ) : (
                <Eye className="w-4 h-4" aria-hidden="true" />
              )}
            </button>
          </div>
          {errors.password && (
            <p id={passwordErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
              {errors.password}
            </p>
          )}
          <div className="mt-2 grid grid-cols-1 sm:grid-cols-2 gap-1.5 text-xs">
            {[
              { ok: policy.minLength && policy.maxBytes, label: '8–72 UTF-8 bytes' },
              { ok: policy.hasLetterAndDigit, label: 'Includes letter & digit' },
              { ok: policy.notUsername, label: 'Differs from username' },
              { ok: policy.noControlChars, label: 'No control characters' },
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
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label
              htmlFor={roleSelectId}
              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
            >
              Assigned Role <span className="text-rose-500">*</span>
            </label>
            <select
              id={roleSelectId}
              name="role"
              value={formData.role}
              onChange={(e) => handleFieldChange('role', e.target.value)}
              disabled={isSubmitting}
              aria-invalid={Boolean(errors.role)}
              aria-describedby={errors.role ? roleErrorId : undefined}
              className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 bg-white ${
                errors.role
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
            {errors.role && (
              <p id={roleErrorId} role="alert" className="mt-1 text-xs font-medium text-rose-600">
                {errors.role}
              </p>
            )}
          </div>

          <fieldset>
            <legend className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-2">
              Initial Account Status <span className="text-rose-500">*</span>
            </legend>
            <div className="flex items-center space-x-4 pt-0.5">
              <label
                htmlFor={statusEnabledId}
                className="flex items-center space-x-2 text-sm text-slate-700 cursor-pointer"
              >
                <input
                  id={statusEnabledId}
                  type="radio"
                  name="enabled"
                  value="true"
                  checked={formData.enabled === true}
                  onChange={() => handleFieldChange('enabled', true)}
                  disabled={isSubmitting}
                  className="text-pharma-600 focus:ring-pharma-500"
                />
                <StatusBadge status="ENABLED" tone="success" label="Enabled" variant="tag" size="xs" />
              </label>

              <label
                htmlFor={statusDisabledId}
                className="flex items-center space-x-2 text-sm text-slate-700 cursor-pointer"
              >
                <input
                  id={statusDisabledId}
                  type="radio"
                  name="enabled"
                  value="false"
                  checked={formData.enabled === false}
                  onChange={() => handleFieldChange('enabled', false)}
                  disabled={isSubmitting}
                  className="text-pharma-600 focus:ring-pharma-500"
                />
                <StatusBadge status="DISABLED" tone="muted" label="Disabled" variant="tag" size="xs" />
              </label>
            </div>
          </fieldset>
        </div>
      </form>
    </ModalDialog>
  );
}

