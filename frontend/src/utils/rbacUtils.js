/**
 * Frontend Role-Based Access Control (RBAC) policy helpers aligned with backend
 * Spring Security rules in SecurityConfig.java and BatchController.java.
 *
 * UI permission checks govern navigation, route guards, and action button visibility,
 * while the Spring Boot backend independently enforces all endpoint permissions.
 */

export const ROLES = Object.freeze({
  ADMIN: 'ADMIN',
  PRODUCTION_SUPERVISOR: 'PRODUCTION_SUPERVISOR',
  OPERATOR: 'OPERATOR',
  QC_ANALYST: 'QC_ANALYST',
  QA_REVIEWER: 'QA_REVIEWER',
});

export const ALL_ROLES = Object.freeze(Object.values(ROLES));

export const ROLE_METADATA = Object.freeze({
  [ROLES.ADMIN]: {
    label: 'Administrator',
    shortLabel: 'Admin',
    badgeClass: 'bg-indigo-100 text-indigo-800 border-indigo-200',
  },
  [ROLES.PRODUCTION_SUPERVISOR]: {
    label: 'Production Supervisor',
    shortLabel: 'Prod Supervisor',
    badgeClass: 'bg-sky-100 text-sky-800 border-sky-200',
  },
  [ROLES.OPERATOR]: {
    label: 'Production Operator',
    shortLabel: 'Operator',
    badgeClass: 'bg-amber-100 text-amber-800 border-amber-200',
  },
  [ROLES.QC_ANALYST]: {
    label: 'QC Analyst',
    shortLabel: 'QC Analyst',
    badgeClass: 'bg-teal-100 text-teal-800 border-teal-200',
  },
  [ROLES.QA_REVIEWER]: {
    label: 'QA Reviewer',
    shortLabel: 'QA Reviewer',
    badgeClass: 'bg-emerald-100 text-emerald-800 border-emerald-200',
  },
});

export const MODULE_TAB_PERMISSIONS = Object.freeze({
  products: ALL_ROLES,
  recipes: [
    ROLES.ADMIN,
    ROLES.PRODUCTION_SUPERVISOR,
    ROLES.QC_ANALYST,
    ROLES.QA_REVIEWER,
  ],
  orders: ALL_ROLES,
  execution: ALL_ROLES,
  inventory: ALL_ROLES,
  quality: ALL_ROLES,
  traceability: ALL_ROLES,
  users: [ROLES.ADMIN],
  system: ALL_ROLES,
});

export function normalizeRole(role) {
  if (typeof role !== 'string') {
    return '';
  }
  const normalized = role.trim().toUpperCase();
  return normalized.startsWith('ROLE_') ? normalized.slice(5) : normalized;
}

export function isValidRole(role) {
  const normalized = normalizeRole(role);
  return ALL_ROLES.includes(normalized);
}

export function hasAnyRole(role, allowedRoles) {
  const normalized = normalizeRole(role);
  if (!normalized || !Array.isArray(allowedRoles)) {
    return false;
  }
  return allowedRoles.includes(normalized);
}

export function getRoleMetadata(role) {
  const normalized = normalizeRole(role);
  return (
    ROLE_METADATA[normalized] || {
      label: normalized || 'Unassigned Role',
      shortLabel: normalized || 'Unknown',
      badgeClass: 'bg-slate-100 text-slate-700 border-slate-200',
    }
  );
}

export function formatActorDisplay(user) {
  if (!user) {
    return 'Authenticated User';
  }
  const fullName = typeof user.fullName === 'string' ? user.fullName.trim() : '';
  const username = typeof user.username === 'string' ? user.username.trim() : '';
  if (fullName && username) {
    return `${fullName} (@${username})`;
  }
  return fullName || username || 'Authenticated User';
}

// -----------------------------------------------------------------------------
// Navigation & Route Guards
// -----------------------------------------------------------------------------

export function canAccessTab(role, tabId) {
  const allowed = MODULE_TAB_PERMISSIONS[tabId];
  if (!allowed) {
    return false;
  }
  return hasAnyRole(role, allowed);
}

export function getAllowedTabsForRole(role) {
  return Object.keys(MODULE_TAB_PERMISSIONS).filter((tabId) => canAccessTab(role, tabId));
}

export function getDefaultTabForRole(role) {
  const allowed = getAllowedTabsForRole(role);
  if (allowed.includes('products')) {
    return 'products';
  }
  return allowed[0] || 'products';
}

// -----------------------------------------------------------------------------
// Product Master
// -----------------------------------------------------------------------------

export function canManageProducts(role) {
  return hasAnyRole(role, [ROLES.ADMIN]);
}

// -----------------------------------------------------------------------------
// User Management (ADMIN Exclusive)
// -----------------------------------------------------------------------------

export function canManageUsers(role) {
  return hasAnyRole(role, [ROLES.ADMIN]);
}

// -----------------------------------------------------------------------------
// Recipe Management
// -----------------------------------------------------------------------------

export function canViewRecipes(role) {
  return hasAnyRole(role, MODULE_TAB_PERMISSIONS.recipes);
}

export function canAuthorRecipes(role) {
  return hasAnyRole(role, [ROLES.ADMIN, ROLES.PRODUCTION_SUPERVISOR]);
}

export function canApproveRecipes(role) {
  return hasAnyRole(role, [ROLES.ADMIN, ROLES.QA_REVIEWER]);
}

// -----------------------------------------------------------------------------
// Manufacturing Orders & Batches
// -----------------------------------------------------------------------------

export function canManageManufacturingOrders(role) {
  return hasAnyRole(role, [ROLES.ADMIN, ROLES.PRODUCTION_SUPERVISOR]);
}

export function canStartBatchRole(role) {
  return hasAnyRole(role, [ROLES.PRODUCTION_SUPERVISOR, ROLES.OPERATOR]);
}

export function canCompleteBatchRole(role) {
  return hasAnyRole(role, [ROLES.PRODUCTION_SUPERVISOR, ROLES.OPERATOR]);
}

export function canCancelBatchRole(role) {
  return hasAnyRole(role, [ROLES.ADMIN, ROLES.PRODUCTION_SUPERVISOR]);
}

// -----------------------------------------------------------------------------
// Production Execution (eBR Steps)
// -----------------------------------------------------------------------------

export function canExecuteProductionSteps(role) {
  return hasAnyRole(role, [ROLES.OPERATOR]);
}

// -----------------------------------------------------------------------------
// Quality Management (QC, QA Disposition, Global Audit Trail)
// -----------------------------------------------------------------------------

export function canViewGlobalAuditTrail(role) {
  return hasAnyRole(role, [ROLES.ADMIN, ROLES.QA_REVIEWER]);
}

export function canRecordQcChecks(role) {
  return hasAnyRole(role, [ROLES.QC_ANALYST]);
}

export function canPerformQaDisposition(role) {
  return hasAnyRole(role, [ROLES.QA_REVIEWER]);
}

export function canReportDeviation(role) {
  return hasAnyRole(role, [
    ROLES.PRODUCTION_SUPERVISOR,
    ROLES.OPERATOR,
    ROLES.QC_ANALYST,
    ROLES.QA_REVIEWER,
  ]);
}

// -----------------------------------------------------------------------------
// Deviations & CAPA Lifecycle
// -----------------------------------------------------------------------------

export function canInvestigateDeviation(role) {
  return hasAnyRole(role, [
    ROLES.PRODUCTION_SUPERVISOR,
    ROLES.QC_ANALYST,
    ROLES.QA_REVIEWER,
  ]);
}

export function canCreateCapaAction(role) {
  return hasAnyRole(role, [ROLES.PRODUCTION_SUPERVISOR, ROLES.QA_REVIEWER]);
}

export function canExecuteCapaAction(role) {
  return hasAnyRole(role, [
    ROLES.PRODUCTION_SUPERVISOR,
    ROLES.QC_ANALYST,
    ROLES.QA_REVIEWER,
  ]);
}

export function canStartCapaPhase(role) {
  return hasAnyRole(role, [ROLES.QA_REVIEWER]);
}

export function canCancelCapaActionRole(role) {
  return hasAnyRole(role, [ROLES.QA_REVIEWER]);
}

export function canReopenCapaPhase(role) {
  return hasAnyRole(role, [ROLES.QA_REVIEWER]);
}

export function canVerifyAndCloseDeviation(role) {
  return hasAnyRole(role, [ROLES.QA_REVIEWER]);
}

// -----------------------------------------------------------------------------
// Raw Material Inventory & Warehouse Management
// -----------------------------------------------------------------------------

export function canViewInventory(role) {
  return hasAnyRole(role, MODULE_TAB_PERMISSIONS.inventory);
}

export function canManageRawMaterials(role) {
  return hasAnyRole(role, [ROLES.ADMIN, ROLES.PRODUCTION_SUPERVISOR]);
}

export function canManageWarehouses(role) {
  return hasAnyRole(role, [ROLES.ADMIN, ROLES.PRODUCTION_SUPERVISOR]);
}

export function canReceiveGoods(role) {
  return hasAnyRole(role, [ROLES.ADMIN, ROLES.PRODUCTION_SUPERVISOR]);
}

export function canIssueMaterials(role) {
  return hasAnyRole(role, [
    ROLES.ADMIN,
    ROLES.PRODUCTION_SUPERVISOR,
    ROLES.OPERATOR,
  ]);
}

export function canAdjustStock(role) {
  return hasAnyRole(role, [
    ROLES.ADMIN,
    ROLES.PRODUCTION_SUPERVISOR,
    ROLES.QA_REVIEWER,
  ]);
}

