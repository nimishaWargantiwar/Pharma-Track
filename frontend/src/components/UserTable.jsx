import React from 'react';
import {
  Edit2,
  KeyRound,
  Power,
  RefreshCw,
  RotateCcw,
  Shield,
  Users,
} from 'lucide-react';
import { DataTable, InlineAlert, StatusBadge } from './ui/index.js';
import { formatAppShortDateTime } from '../utils/dateTimeUtils.js';
import { getRoleMetadata } from '../utils/rbacUtils.js';

export default function UserTable({
  users = [],
  currentUserId = null,
  currentUsername = '',
  enabledAdminCount = 0,
  isLoading = false,
  error = null,
  onRetry = null,
  hasActiveFilters = false,
  onResetFilters = null,
  onEditProfile,
  onChangeRole,
  onResetPassword,
  onToggleStatus,
  actionLoadingId = null,
  sortState = null,
  onSortChange = null,
  pagination = null,
}) {
  if (!isLoading && error && users.length === 0) {
    return (
      <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-6 sm:p-8">
        <InlineAlert
          variant="error"
          title="Unable to load user accounts"
          message={error}
          actions={
            typeof onRetry === 'function' ? (
              <button
                type="button"
                onClick={onRetry}
                className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-rose-900 bg-white hover:bg-rose-100 border border-rose-300 rounded-lg shadow-sm transition-colors"
              >
                <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" />
                <span>Retry</span>
              </button>
            ) : null
          }
        />
      </div>
    );
  }

  const normalizedCurrentUsername =
    typeof currentUsername === 'string' ? currentUsername.trim().toLowerCase() : '';

  const columns = [
    {
      key: 'fullName',
      header: 'User Account',
      accessor: 'fullName',
      sortable: true,
      headerClassName: 'sm:pl-6',
      cellClassName: 'sm:pl-6',
      render: (account) => {
        const isSelf =
          (currentUserId != null && account.id === currentUserId) ||
          (normalizedCurrentUsername &&
            account.username?.toLowerCase() === normalizedCurrentUsername);
        const roleMeta = getRoleMetadata(account.role);

        return (
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="font-semibold text-slate-900 break-words">
                {account.fullName}
              </span>
              {isSelf && (
                <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[11px] font-semibold bg-pharma-50 text-pharma-700 border border-pharma-200">
                  You
                </span>
              )}
            </div>
            <div className="text-xs font-mono text-slate-600 mt-0.5 break-all">
              @{account.username}
            </div>
            <div className="mt-1.5 flex flex-wrap items-center gap-1.5 sm:hidden">
              <span
                className={`inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold border whitespace-nowrap ${roleMeta.badgeClass}`}
              >
                {roleMeta.shortLabel}
              </span>
              <StatusBadge
                status={account.enabled ? 'ENABLED' : 'DISABLED'}
                tone={account.enabled ? 'success' : 'muted'}
                label={account.enabled ? 'Enabled' : 'Disabled'}
                size="xs"
                showDot
              />
            </div>
          </div>
        );
      },
    },
    {
      key: 'username',
      header: 'Username',
      accessor: 'username',
      sortable: true,
      headerClassName: 'hidden md:table-cell',
      cellClassName: 'hidden md:table-cell font-mono text-xs text-slate-700 whitespace-nowrap',
      render: (account) => (
        <span className="inline-block bg-slate-100 text-slate-800 px-2.5 py-1 rounded-md border border-slate-200 font-semibold">
          {account.username}
        </span>
      ),
    },
    {
      key: 'role',
      header: 'Role',
      accessor: 'role',
      sortable: true,
      headerClassName: 'hidden sm:table-cell',
      cellClassName: 'hidden sm:table-cell whitespace-nowrap',
      render: (account) => {
        const roleMeta = getRoleMetadata(account.role);
        return (
          <span
            className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold border whitespace-nowrap ${roleMeta.badgeClass}`}
          >
            {roleMeta.label}
          </span>
        );
      },
    },
    {
      key: 'enabled',
      header: 'Status',
      sortable: true,
      sortValue: (account) => (account.enabled ? 'ENABLED' : 'DISABLED'),
      headerClassName: 'hidden sm:table-cell',
      cellClassName: 'hidden sm:table-cell whitespace-nowrap',
      render: (account) => (
        <StatusBadge
          status={account.enabled ? 'ENABLED' : 'DISABLED'}
          tone={account.enabled ? 'success' : 'muted'}
          label={account.enabled ? 'Enabled' : 'Disabled'}
          showDot
        />
      ),
    },
    {
      key: 'createdAt',
      header: 'Created',
      accessor: 'createdAt',
      sortable: true,
      headerClassName: 'hidden lg:table-cell',
      cellClassName: 'hidden lg:table-cell text-xs text-slate-500 tabular-nums whitespace-nowrap',
      render: (account) => formatAppShortDateTime(account.createdAt),
    },
    {
      key: 'updatedAt',
      header: 'Last Updated',
      sortable: true,
      sortValue: (account) => account.updatedAt || account.createdAt || '',
      headerClassName: 'hidden xl:table-cell',
      cellClassName: 'hidden xl:table-cell text-xs text-slate-500 tabular-nums whitespace-nowrap',
      render: (account) => formatAppShortDateTime(account.updatedAt || account.createdAt),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      headerClassName: 'sm:pr-6',
      cellClassName: 'sm:pr-6 text-right whitespace-nowrap',
      render: (account) => {
        const isSelf =
          (currentUserId != null && account.id === currentUserId) ||
          (normalizedCurrentUsername &&
            account.username?.toLowerCase() === normalizedCurrentUsername);
        const isSoleEnabledAdmin =
          account.role === 'ADMIN' && account.enabled && enabledAdminCount <= 1;
        const disableBlockedReason = !account.enabled
          ? ''
          : isSelf
            ? 'You cannot disable your own active account'
            : isSoleEnabledAdmin
              ? 'Cannot disable the last enabled ADMIN account'
              : '';
        const isActionLoading = actionLoadingId === account.id;

        return (
          <div className="inline-flex flex-wrap items-center justify-end gap-1.5">
            <button
              type="button"
              onClick={() => onEditProfile?.(account)}
              disabled={isActionLoading}
              title="Edit user profile"
              aria-label={`Edit profile for ${account.username}`}
              className="inline-flex items-center space-x-1 px-2.5 py-1.5 text-xs font-medium text-slate-700 bg-white hover:bg-slate-100 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
            >
              <Edit2 className="w-3.5 h-3.5 text-slate-500" aria-hidden="true" />
              <span className="hidden sm:inline">Edit</span>
            </button>

            <button
              type="button"
              onClick={() => onChangeRole?.(account)}
              disabled={isActionLoading}
              title="Change user role"
              aria-label={`Change role for ${account.username}`}
              className="inline-flex items-center space-x-1 px-2.5 py-1.5 text-xs font-medium text-indigo-800 bg-indigo-50 hover:bg-indigo-100 border border-indigo-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
            >
              <Shield className="w-3.5 h-3.5" aria-hidden="true" />
              <span className="hidden sm:inline">Role</span>
            </button>

            <button
              type="button"
              onClick={() => onResetPassword?.(account)}
              disabled={isActionLoading}
              title="Reset temporary password"
              aria-label={`Reset password for ${account.username}`}
              className="inline-flex items-center space-x-1 px-2.5 py-1.5 text-xs font-medium text-slate-700 bg-slate-50 hover:bg-slate-100 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
            >
              <KeyRound className="w-3.5 h-3.5 text-slate-500" aria-hidden="true" />
              <span className="hidden md:inline">Reset Password</span>
            </button>

            <button
              type="button"
              onClick={() => onToggleStatus?.(account)}
              disabled={isActionLoading || Boolean(disableBlockedReason)}
              title={
                disableBlockedReason ||
                (account.enabled ? 'Disable user account' : 'Enable user account')
              }
              aria-label={`${account.enabled ? 'Disable' : 'Enable'} ${account.username}`}
              className={`inline-flex items-center space-x-1 px-2.5 py-1.5 text-xs font-medium rounded-lg border shadow-sm transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
                account.enabled
                  ? 'text-amber-800 bg-amber-50 hover:bg-amber-100 border-amber-200'
                  : 'text-emerald-800 bg-emerald-50 hover:bg-emerald-100 border-emerald-200'
              }`}
            >
              <Power className="w-3.5 h-3.5" aria-hidden="true" />
              <span>{account.enabled ? 'Disable' : 'Enable'}</span>
            </button>
          </div>
        );
      },
    },
  ];

  const emptyState = (
    <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-8 sm:p-12 text-center">
      <div className="w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center mx-auto mb-3 text-slate-400">
        <Users className="w-6 h-6" aria-hidden="true" />
      </div>
      <h3 className="text-base font-semibold text-slate-800">
        {hasActiveFilters ? 'No matching users found' : 'No user accounts found'}
      </h3>
      <p className="text-sm text-slate-500 mt-1 max-w-sm mx-auto">
        {hasActiveFilters
          ? 'No PharmaTrack user accounts match your current search, role, or status filters.'
          : 'No user accounts are provisioned in the system directory yet.'}
      </p>
      {hasActiveFilters && typeof onResetFilters === 'function' && (
        <button
          type="button"
          onClick={onResetFilters}
          className="mt-4 inline-flex items-center space-x-1.5 px-3.5 py-2 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg transition-colors"
        >
          <RotateCcw className="w-3.5 h-3.5" aria-hidden="true" />
          <span>Reset Filters</span>
        </button>
      )}
    </div>
  );

  return (
    <DataTable
      columns={columns}
      data={users}
      rowKey={(account, idx) => account.id ?? account.username ?? idx}
      ariaLabel="System user accounts directory"
      caption="System user accounts directory"
      isLoading={isLoading}
      loadingTitle="Loading user directory..."
      loadingDescription="Retrieving user accounts and assigned RBAC roles..."
      emptyState={emptyState}
      sortState={sortState}
      onSortChange={onSortChange}
      pagination={pagination}
      itemLabel="users"
    />
  );
}

