import React, { useCallback, useEffect, useId, useMemo, useState } from 'react';
import {
  CheckCircle2,
  RefreshCw,
  Search,
  Shield,
  UserPlus,
  Users,
  UserX,
} from 'lucide-react';
import AccessDeniedView from './AccessDeniedView.jsx';
import ConfirmModal from './ConfirmModal.jsx';
import NotificationBanner from './NotificationBanner.jsx';
import UserCreateModal from './UserCreateModal.jsx';
import UserPasswordResetModal from './UserPasswordResetModal.jsx';
import UserProfileModal from './UserProfileModal.jsx';
import UserRoleModal from './UserRoleModal.jsx';
import UserTable from './UserTable.jsx';
import {
  DEFAULT_PAGE_SIZE,
  InlineAlert,
  PageHeader,
  paginateItems,
} from './ui/index.js';
import {
  createUser,
  getAllUsers,
  resetUserPassword,
  updateUserProfile,
  updateUserRole,
  updateUserStatus,
} from '../services/adminUserApi.js';
import { useAuth } from '../context/AuthContext.jsx';
import { useNavigation } from '../context/NavigationContext.jsx';
import {
  ALL_ROLES,
  canManageUsers,
  getDefaultTabForRole,
  ROLE_METADATA,
  ROLES,
} from '../utils/rbacUtils.js';

export default function UserManagement() {
  const { user: currentUser, role, logout } = useAuth();
  const { navigateToTab } = useNavigation();
  const canManage = canManageUsers(role);

  const searchInputId = useId();
  const roleFilterId = useId();

  const [users, setUsers] = useState([]);
  const [isLoading, setIsLoading] = useState(canManage);
  const [error, setError] = useState(null);
  const [notification, setNotification] = useState(null);

  // Full-dataset Search, Filter, Sort & Pagination State
  const [searchTerm, setSearchTerm] = useState('');
  const [roleFilter, setRoleFilter] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL'); // 'ALL' | 'ENABLED' | 'DISABLED'
  const [sortState, setSortState] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  // Modal & Action State
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [isCreating, setIsCreating] = useState(false);

  const [profileTarget, setProfileTarget] = useState(null);
  const [isUpdatingProfile, setIsUpdatingProfile] = useState(false);

  const [roleTarget, setRoleTarget] = useState(null);
  const [isUpdatingRole, setIsUpdatingRole] = useState(false);

  const [passwordTarget, setPasswordTarget] = useState(null);
  const [isResettingPassword, setIsResettingPassword] = useState(false);

  const [statusConfirmTarget, setStatusConfirmTarget] = useState(null);
  const [isConfirmingStatus, setIsConfirmingStatus] = useState(false);
  const [actionLoadingId, setActionLoadingId] = useState(null);

  const loadUsers = useCallback(async () => {
    if (!canManage) {
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    setError(null);
    try {
      const data = await getAllUsers();
      setUsers(Array.isArray(data) ? data : []);
    } catch (err) {
      const message = err.message || 'Unable to connect to user directory API';
      setError(message);
      setNotification({
        type: 'error',
        message: err.message || 'Failed to load user accounts from server',
      });
    } finally {
      setIsLoading(false);
    }
  }, [canManage]);

  useEffect(() => {
    if (!canManage) {
      setIsLoading(false);
      return;
    }
    loadUsers();
  }, [canManage, loadUsers]);

  // Statistics across full directory
  const totalCount = users.length;
  const enabledCount = useMemo(
    () => users.filter((u) => u.enabled === true).length,
    [users]
  );
  const disabledCount = useMemo(
    () => users.filter((u) => u.enabled === false).length,
    [users]
  );
  const enabledAdminCount = useMemo(
    () => users.filter((u) => u.role === ROLES.ADMIN && u.enabled === true).length,
    [users]
  );

  // Filter full dataset BEFORE pagination
  const filteredUsers = useMemo(() => {
    const query = searchTerm.trim().toLowerCase();
    return users.filter((account) => {
      if (roleFilter !== 'ALL' && account.role !== roleFilter) {
        return false;
      }
      if (statusFilter === 'ENABLED' && account.enabled !== true) {
        return false;
      }
      if (statusFilter === 'DISABLED' && account.enabled !== false) {
        return false;
      }
      if (query) {
        const nameMatch = account.fullName?.toLowerCase().includes(query);
        const usernameMatch = account.username?.toLowerCase().includes(query);
        const roleLabelMatch = ROLE_METADATA[account.role]?.label?.toLowerCase().includes(query);
        return Boolean(nameMatch || usernameMatch || roleLabelMatch);
      }
      return true;
    });
  }, [users, roleFilter, statusFilter, searchTerm]);

  // Sort across filtered dataset BEFORE pagination
  const sortedUsers = useMemo(() => {
    if (!sortState?.key) {
      return filteredUsers;
    }
    const dir = sortState.direction === 'desc' ? -1 : 1;
    return [...filteredUsers].sort((a, b) => {
      const resolveSortVal = (record) => {
        if (sortState.key === 'enabled') {
          return record?.enabled ? 'ENABLED' : 'DISABLED';
        }
        if (sortState.key === 'updatedAt') {
          return record?.updatedAt || record?.createdAt || '';
        }
        return record?.[sortState.key];
      };
      const valA = resolveSortVal(a);
      const valB = resolveSortVal(b);

      if (valA == null && valB == null) return 0;
      if (valA == null) return -1 * dir;
      if (valB == null) return 1 * dir;
      return String(valA).localeCompare(String(valB), undefined, { numeric: true }) * dir;
    });
  }, [filteredUsers, sortState]);

  const paginatedResult = useMemo(
    () => paginateItems(sortedUsers, currentPage, pageSize),
    [sortedUsers, currentPage, pageSize]
  );

  useEffect(() => {
    if (paginatedResult.page !== currentPage) {
      setCurrentPage(paginatedResult.page);
    }
  }, [paginatedResult.page, currentPage]);

  if (!canManage) {
    const defaultTab = getDefaultTabForRole(role);
    return (
      <AccessDeniedView
        role={role}
        moduleName="User Management"
        description="Administrator privileges are required to view user accounts, assign roles, reset temporary passwords, or manage access."
        onReturnHome={() => navigateToTab(defaultTab)}
      />
    );
  }

  const normalizedCurrentUsername =
    typeof currentUser?.username === 'string'
      ? currentUser.username.trim().toLowerCase()
      : '';

  const isTargetCurrentUser = (account) => {
    if (!account) return false;
    return (
      (currentUser?.id != null && account.id === currentUser.id) ||
      (Boolean(normalizedCurrentUsername) &&
        account.username?.toLowerCase() === normalizedCurrentUsername)
    );
  };

  const isTargetSoleEnabledAdmin = (account) =>
    Boolean(account && account.role === ROLES.ADMIN && account.enabled && enabledAdminCount <= 1);

  const hasActiveFilters = Boolean(
    searchTerm.trim() || roleFilter !== 'ALL' || statusFilter !== 'ALL'
  );

  const handleSearchChange = (nextValue) => {
    setSearchTerm(nextValue);
    setCurrentPage(1);
  };

  const handleRoleFilterChange = (nextRole) => {
    setRoleFilter(nextRole);
    setCurrentPage(1);
  };

  const handleStatusFilterChange = (nextStatus) => {
    setStatusFilter(nextStatus);
    setCurrentPage(1);
  };

  const handleSortChange = (nextSort) => {
    setSortState(nextSort);
    setCurrentPage(1);
  };

  const handleResetFilters = () => {
    setSearchTerm('');
    setRoleFilter('ALL');
    setStatusFilter('ALL');
    setCurrentPage(1);
  };

  const handleCreateUser = async (payload) => {
    setIsCreating(true);
    try {
      const created = await createUser(payload);
      setIsCreateModalOpen(false);
      setNotification({
        type: 'success',
        message: `User @${created.username} created with role ${
          ROLE_METADATA[created.role]?.label || created.role
        }.`,
      });
      await loadUsers();
    } finally {
      setIsCreating(false);
    }
  };

  const handleSaveProfile = async (target, payload) => {
    setIsUpdatingProfile(true);
    setActionLoadingId(target.id);
    try {
      const updated = await updateUserProfile(target.id, payload);
      setProfileTarget(null);
      setNotification({
        type: 'success',
        message: `Profile updated for @${updated.username} (${updated.fullName}).`,
      });
      await loadUsers();
    } finally {
      setIsUpdatingProfile(false);
      setActionLoadingId(null);
    }
  };

  const handleSaveRole = async (target, nextRole) => {
    const roleChanged = target.role !== nextRole;
    const isSelf = isTargetCurrentUser(target);
    setIsUpdatingRole(true);
    setActionLoadingId(target.id);
    try {
      const updated = await updateUserRole(target.id, nextRole);
      setRoleTarget(null);

      if (roleChanged && isSelf) {
        logout(
          'Your account role was updated and your previous session was invalidated. Please sign in again.'
        );
        return;
      }

      setNotification({
        type: 'success',
        message: roleChanged
          ? `Role for @${updated.username} updated to ${
              ROLE_METADATA[updated.role]?.label || updated.role
            }. Active sessions revoked.`
          : `Role for @${updated.username} remains ${
              ROLE_METADATA[updated.role]?.label || updated.role
            }.`,
      });
      await loadUsers();
    } finally {
      setIsUpdatingRole(false);
      setActionLoadingId(null);
    }
  };

  const handleResetPassword = async (target, newPassword) => {
    const isSelf = isTargetCurrentUser(target);
    setIsResettingPassword(true);
    setActionLoadingId(target.id);
    try {
      const updated = await resetUserPassword(target.id, newPassword);
      setPasswordTarget(null);

      if (isSelf) {
        logout(
          'Your temporary password was reset and your previous session was invalidated. Please sign in with your new password.'
        );
        return;
      }

      setNotification({
        type: 'success',
        message: `Temporary password reset for @${updated.username}. Active sessions revoked.`,
      });
      await loadUsers();
    } finally {
      setIsResettingPassword(false);
      setActionLoadingId(null);
    }
  };

  const handleConfirmToggleStatus = async () => {
    if (!statusConfirmTarget) return;
    const target = statusConfirmTarget;
    const desiredEnabled = !target.enabled;

    setIsConfirmingStatus(true);
    setActionLoadingId(target.id);
    try {
      await updateUserStatus(target.id, desiredEnabled);
      setStatusConfirmTarget(null);
      setNotification({
        type: 'success',
        message: `User @${target.username} has been ${
          desiredEnabled ? 'enabled' : 'disabled'
        }. Active sessions revoked.`,
      });
      await loadUsers();
    } catch (err) {
      setStatusConfirmTarget(null);
      setNotification({
        type: 'error',
        message: err.message || `Failed to update status for @${target.username}`,
      });
    } finally {
      setIsConfirmingStatus(false);
      setActionLoadingId(null);
    }
  };

  const isDisablingTarget = Boolean(statusConfirmTarget?.enabled);

  return (
    <div className="space-y-6">
      <NotificationBanner
        notification={notification}
        onDismiss={() => setNotification(null)}
      />

      <PageHeader
        title="User Management"
        badge="RBAC Directory"
        description="Provision system accounts, update profile details, assign roles, reset temporary passwords, and control account activation."
        actions={
          <>
            <button
              type="button"
              onClick={loadUsers}
              disabled={isLoading}
              title="Refresh user directory"
              className="inline-flex items-center space-x-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
            >
              <RefreshCw
                className={`w-4 h-4 ${isLoading ? 'animate-spin text-pharma-600' : 'text-slate-500'}`}
                aria-hidden="true"
              />
              <span className="hidden sm:inline">Refresh</span>
            </button>

            <button
              type="button"
              onClick={() => setIsCreateModalOpen(true)}
              className="inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors"
            >
              <UserPlus className="w-4 h-4" aria-hidden="true" />
              <span>Add User</span>
            </button>
          </>
        }
      />

      {/* KPI Summary Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Total Accounts
            </p>
            <p className="text-2xl font-extrabold text-slate-900 mt-1 tabular-nums">
              {totalCount}
            </p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-pharma-50 text-pharma-600 flex items-center justify-center">
            <Users className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Enabled Users
            </p>
            <p className="text-2xl font-extrabold text-emerald-600 mt-1 tabular-nums">
              {enabledCount}
            </p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-emerald-50 text-emerald-600 flex items-center justify-center">
            <CheckCircle2 className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Disabled Users
            </p>
            <p className="text-2xl font-extrabold text-slate-500 mt-1 tabular-nums">
              {disabledCount}
            </p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-slate-100 text-slate-500 flex items-center justify-center">
            <UserX className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Active Admins
            </p>
            <p className="text-2xl font-extrabold text-indigo-600 mt-1 tabular-nums">
              {enabledAdminCount}
            </p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-indigo-50 text-indigo-600 flex items-center justify-center">
            <Shield className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>
      </div>

      {/* Filter & Search Toolbar */}
      <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm flex flex-col lg:flex-row lg:items-center justify-between gap-3">
        <div className="flex flex-col sm:flex-row sm:items-center gap-3 flex-1 min-w-0">
          <div className="relative flex-1 max-w-md min-w-0">
            <label htmlFor={searchInputId} className="sr-only">
              Search users by full name or username
            </label>
            <Search
              className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2"
              aria-hidden="true"
            />
            <input
              id={searchInputId}
              type="text"
              value={searchTerm}
              onChange={(e) => handleSearchChange(e.target.value)}
              placeholder="Search users by full name or username..."
              className="w-full pl-9 pr-14 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 bg-white"
            />
            {searchTerm && (
              <button
                type="button"
                onClick={() => handleSearchChange('')}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-xs font-medium text-slate-500 hover:text-slate-700"
              >
                Clear
              </button>
            )}
          </div>

          <div className="flex items-center space-x-2 shrink-0">
            <label
              htmlFor={roleFilterId}
              className="text-xs font-semibold uppercase tracking-wider text-slate-500 whitespace-nowrap"
            >
              Role:
            </label>
            <select
              id={roleFilterId}
              value={roleFilter}
              onChange={(e) => handleRoleFilterChange(e.target.value)}
              className="px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white text-slate-700 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
            >
              <option value="ALL">All Roles</option>
              {ALL_ROLES.map((roleKey) => (
                <option key={roleKey} value={roleKey}>
                  {ROLE_METADATA[roleKey]?.label || roleKey}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div
          role="group"
          aria-label="Filter users by account status"
          className="flex flex-wrap items-center gap-1.5 bg-slate-100 p-1 rounded-lg border border-slate-200 self-start lg:self-auto tabular-nums max-w-full"
        >
          <button
            type="button"
            onClick={() => handleStatusFilterChange('ALL')}
            aria-pressed={statusFilter === 'ALL'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md transition-colors whitespace-nowrap ${
              statusFilter === 'ALL'
                ? 'bg-white text-slate-900 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            All ({totalCount})
          </button>
          <button
            type="button"
            onClick={() => handleStatusFilterChange('ENABLED')}
            aria-pressed={statusFilter === 'ENABLED'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md transition-colors whitespace-nowrap ${
              statusFilter === 'ENABLED'
                ? 'bg-white text-emerald-800 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Enabled ({enabledCount})
          </button>
          <button
            type="button"
            onClick={() => handleStatusFilterChange('DISABLED')}
            aria-pressed={statusFilter === 'DISABLED'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md transition-colors whitespace-nowrap ${
              statusFilter === 'DISABLED'
                ? 'bg-white text-slate-800 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Disabled ({disabledCount})
          </button>
        </div>
      </div>

      {/* Refresh error alert when cached/existing rows are visible */}
      {error && users.length > 0 && !isLoading && (
        <InlineAlert
          variant="error"
          title="Unable to refresh user directory"
          message={error}
          onDismiss={() => setError(null)}
          actions={
            <button
              type="button"
              onClick={loadUsers}
              className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-rose-900 bg-white hover:bg-rose-100 border border-rose-300 rounded-lg transition-colors"
            >
              <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Retry</span>
            </button>
          }
        />
      )}

      {/* User Directory Table */}
      <UserTable
        users={paginatedResult.items}
        currentUserId={currentUser?.id ?? null}
        currentUsername={currentUser?.username || ''}
        enabledAdminCount={enabledAdminCount}
        isLoading={isLoading}
        error={error}
        onRetry={loadUsers}
        hasActiveFilters={hasActiveFilters}
        onResetFilters={handleResetFilters}
        onEditProfile={(account) => setProfileTarget(account)}
        onChangeRole={(account) => setRoleTarget(account)}
        onResetPassword={(account) => setPasswordTarget(account)}
        onToggleStatus={(account) => setStatusConfirmTarget(account)}
        actionLoadingId={actionLoadingId}
        sortState={sortState}
        onSortChange={handleSortChange}
        pagination={
          filteredUsers.length > 0
            ? {
                currentPage: paginatedResult.page,
                pageSize: paginatedResult.pageSize,
                totalItems: paginatedResult.totalItems,
                onPageChange: setCurrentPage,
                onPageSizeChange: (nextSize) => {
                  setPageSize(nextSize);
                  setCurrentPage(1);
                },
                itemLabel: 'users',
              }
            : null
        }
      />

      {/* Modals */}
      <UserCreateModal
        isOpen={isCreateModalOpen}
        onClose={() => {
          if (!isCreating) setIsCreateModalOpen(false);
        }}
        onSave={handleCreateUser}
        isSubmitting={isCreating}
      />

      <UserProfileModal
        isOpen={Boolean(profileTarget)}
        user={profileTarget}
        onClose={() => {
          if (!isUpdatingProfile) setProfileTarget(null);
        }}
        onSave={handleSaveProfile}
        isSubmitting={isUpdatingProfile}
      />

      <UserRoleModal
        isOpen={Boolean(roleTarget)}
        user={roleTarget}
        isSelf={isTargetCurrentUser(roleTarget)}
        isSoleEnabledAdmin={isTargetSoleEnabledAdmin(roleTarget)}
        onClose={() => {
          if (!isUpdatingRole) setRoleTarget(null);
        }}
        onSave={handleSaveRole}
        isSubmitting={isUpdatingRole}
      />

      <UserPasswordResetModal
        isOpen={Boolean(passwordTarget)}
        user={passwordTarget}
        isSelf={isTargetCurrentUser(passwordTarget)}
        onClose={() => {
          if (!isResettingPassword) setPasswordTarget(null);
        }}
        onSave={handleResetPassword}
        isSubmitting={isResettingPassword}
      />

      {statusConfirmTarget && (
        <ConfirmModal
          isOpen={Boolean(statusConfirmTarget)}
          title={isDisablingTarget ? 'Disable User Account' : 'Enable User Account'}
          message={
            isDisablingTarget
              ? `Are you sure you want to disable ${statusConfirmTarget.fullName} (@${statusConfirmTarget.username})? Active sessions for this account will be revoked immediately and sign-in will be blocked until re-enabled.`
              : `Are you sure you want to enable ${statusConfirmTarget.fullName} (@${statusConfirmTarget.username})? Enabled accounts can sign in using their existing role and password credentials.`
          }
          confirmText={isDisablingTarget ? 'Disable Account' : 'Enable Account'}
          confirmVariant={isDisablingTarget ? 'warning' : 'primary'}
          onConfirm={handleConfirmToggleStatus}
          onClose={() => {
            if (!isConfirmingStatus) setStatusConfirmTarget(null);
          }}
          isLoading={isConfirmingStatus}
        />
      )}
    </div>
  );
}

