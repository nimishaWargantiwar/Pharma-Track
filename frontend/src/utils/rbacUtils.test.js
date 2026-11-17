import test from 'node:test';
import assert from 'node:assert/strict';
import {
  ALL_ROLES,
  ROLES,
  canAccessTab,
  canAdjustStock,
  canApproveRecipes,
  canAuthorRecipes,
  canCancelBatchRole,
  canCancelCapaActionRole,
  canCompleteBatchRole,
  canCreateCapaAction,
  canExecuteCapaAction,
  canExecuteProductionSteps,
  canGenerateBatchAnalysis,
  canInvestigateDeviation,
  canIssueMaterials,
  canManageManufacturingOrders,
  canManageProducts,
  canManageRawMaterials,
  canManageUsers,
  canManageWarehouses,
  canPerformQaDisposition,
  canPermanentlyDeleteBatchAnalysis,
  canReceiveGoods,
  canRecordQcChecks,
  canReopenCapaPhase,
  canReportDeviation,
  canStartBatchRole,
  canStartCapaPhase,
  canVerifyAndCloseDeviation,
  canViewBatchAnalysis,
  canViewGlobalAuditTrail,
  canViewInventory,
  canViewRecipes,
  formatActorDisplay,
  getAllowedTabsForRole,
  getDefaultTabForRole,
  getRoleMetadata,
  normalizeRole,
} from './rbacUtils.js';

test('normalizeRole and formatActorDisplay handle prefixes, casing, and missing metadata safely', () => {
  assert.equal(normalizeRole('ROLE_operator'), 'OPERATOR');
  assert.equal(normalizeRole('  qa_reviewer '), 'QA_REVIEWER');
  assert.equal(normalizeRole(null), '');

  assert.equal(getRoleMetadata('ADMIN').label, 'Administrator');
  assert.equal(getRoleMetadata('UNKNOWN').shortLabel, 'UNKNOWN');

  assert.equal(
    formatActorDisplay({ fullName: 'Dr. Asha Sen', username: 'asha.qa' }),
    'Dr. Asha Sen (@asha.qa)'
  );
  assert.equal(formatActorDisplay({ username: 'op1' }), 'op1');
  assert.equal(formatActorDisplay(null), 'Authenticated User');
});

test('navigation tab permissions match SecurityConfig matrix (OPERATOR excluded from recipes)', () => {
  assert.equal(canAccessTab(ROLES.OPERATOR, 'recipes'), false);
  assert.equal(canViewRecipes(ROLES.OPERATOR), false);

  for (const role of [ROLES.ADMIN, ROLES.PRODUCTION_SUPERVISOR, ROLES.QC_ANALYST, ROLES.QA_REVIEWER]) {
    assert.equal(canAccessTab(role, 'recipes'), true);
    assert.equal(canViewRecipes(role), true);
  }

  for (const role of ALL_ROLES) {
    assert.deepEqual(
      ['products', 'orders', 'execution', 'inventory', 'quality', 'traceability', 'system'].every((t) =>
        canAccessTab(role, t)
      ),
      true
    );
    assert.equal(getDefaultTabForRole(role), 'products');
  }

  assert.deepEqual(getAllowedTabsForRole(ROLES.OPERATOR), [
    'products',
    'orders',
    'execution',
    'inventory',
    'quality',
    'traceability',
    'system',
  ]);
  assert.deepEqual(getAllowedTabsForRole(ROLES.ADMIN), [
    'products',
    'recipes',
    'orders',
    'execution',
    'inventory',
    'quality',
    'traceability',
    'users',
    'system',
  ]);
  assert.equal(canAccessTab(ROLES.ADMIN, 'users'), true);
  assert.equal(canManageUsers(ROLES.ADMIN), true);
  for (const role of [ROLES.PRODUCTION_SUPERVISOR, ROLES.OPERATOR, ROLES.QC_ANALYST, ROLES.QA_REVIEWER]) {
    assert.equal(canAccessTab(role, 'users'), false);
    assert.equal(canManageUsers(role), false);
  }
  assert.equal(canAccessTab(ROLES.ADMIN, 'unknown-tab'), false);
});

test('module and action predicates accurately enforce separation of duties across all 5 roles', () => {
  // Product Master: ADMIN only
  assert.equal(canManageProducts(ROLES.ADMIN), true);
  for (const role of [ROLES.PRODUCTION_SUPERVISOR, ROLES.OPERATOR, ROLES.QC_ANALYST, ROLES.QA_REVIEWER]) {
    assert.equal(canManageProducts(role), false);
  }

  // Recipes: author (ADMIN, PRODUCTION_SUPERVISOR) vs approve/obsolete (ADMIN, QA_REVIEWER)
  assert.equal(canAuthorRecipes(ROLES.ADMIN), true);
  assert.equal(canAuthorRecipes(ROLES.PRODUCTION_SUPERVISOR), true);
  assert.equal(canAuthorRecipes(ROLES.QA_REVIEWER), false);
  assert.equal(canAuthorRecipes(ROLES.QC_ANALYST), false);
  assert.equal(canAuthorRecipes(ROLES.OPERATOR), false);

  assert.equal(canApproveRecipes(ROLES.ADMIN), true);
  assert.equal(canApproveRecipes(ROLES.QA_REVIEWER), true);
  assert.equal(canApproveRecipes(ROLES.PRODUCTION_SUPERVISOR), false);
  assert.equal(canApproveRecipes(ROLES.QC_ANALYST), false);
  assert.equal(canApproveRecipes(ROLES.OPERATOR), false);

  // Manufacturing Orders & Batches
  assert.equal(canManageManufacturingOrders(ROLES.ADMIN), true);
  assert.equal(canManageManufacturingOrders(ROLES.PRODUCTION_SUPERVISOR), true);
  assert.equal(canManageManufacturingOrders(ROLES.OPERATOR), false);

  assert.equal(canStartBatchRole(ROLES.PRODUCTION_SUPERVISOR), true);
  assert.equal(canStartBatchRole(ROLES.OPERATOR), true);
  assert.equal(canStartBatchRole(ROLES.ADMIN), false);
  assert.equal(canCompleteBatchRole(ROLES.OPERATOR), true);
  assert.equal(canCompleteBatchRole(ROLES.QC_ANALYST), false);

  assert.equal(canCancelBatchRole(ROLES.ADMIN), true);
  assert.equal(canCancelBatchRole(ROLES.PRODUCTION_SUPERVISOR), true);
  assert.equal(canCancelBatchRole(ROLES.OPERATOR), false);

  // Production Execution eBR steps: OPERATOR only
  assert.equal(canExecuteProductionSteps(ROLES.OPERATOR), true);
  for (const role of [ROLES.ADMIN, ROLES.PRODUCTION_SUPERVISOR, ROLES.QC_ANALYST, ROLES.QA_REVIEWER]) {
    assert.equal(canExecuteProductionSteps(role), false);
  }

  // Quality & Audit
  assert.equal(canViewGlobalAuditTrail(ROLES.ADMIN), true);
  assert.equal(canViewGlobalAuditTrail(ROLES.QA_REVIEWER), true);
  assert.equal(canViewGlobalAuditTrail(ROLES.QC_ANALYST), false);
  assert.equal(canViewGlobalAuditTrail(ROLES.PRODUCTION_SUPERVISOR), false);
  assert.equal(canViewGlobalAuditTrail(ROLES.OPERATOR), false);

  assert.equal(canRecordQcChecks(ROLES.QC_ANALYST), true);
  assert.equal(canRecordQcChecks(ROLES.QA_REVIEWER), false);
  assert.equal(canRecordQcChecks(ROLES.ADMIN), false);

  assert.equal(canPerformQaDisposition(ROLES.QA_REVIEWER), true);
  assert.equal(canPerformQaDisposition(ROLES.ADMIN), false);
  assert.equal(canPerformQaDisposition(ROLES.QC_ANALYST), false);

  // Deviations & CAPA
  assert.equal(canReportDeviation(ROLES.ADMIN), false);
  for (const role of [ROLES.PRODUCTION_SUPERVISOR, ROLES.OPERATOR, ROLES.QC_ANALYST, ROLES.QA_REVIEWER]) {
    assert.equal(canReportDeviation(role), true);
  }

  assert.equal(canInvestigateDeviation(ROLES.PRODUCTION_SUPERVISOR), true);
  assert.equal(canInvestigateDeviation(ROLES.QC_ANALYST), true);
  assert.equal(canInvestigateDeviation(ROLES.QA_REVIEWER), true);
  assert.equal(canInvestigateDeviation(ROLES.OPERATOR), false);
  assert.equal(canInvestigateDeviation(ROLES.ADMIN), false);

  assert.equal(canCreateCapaAction(ROLES.PRODUCTION_SUPERVISOR), true);
  assert.equal(canCreateCapaAction(ROLES.QA_REVIEWER), true);
  assert.equal(canCreateCapaAction(ROLES.QC_ANALYST), false);
  assert.equal(canCreateCapaAction(ROLES.OPERATOR), false);
  assert.equal(canCreateCapaAction(ROLES.ADMIN), false);

  assert.equal(canExecuteCapaAction(ROLES.PRODUCTION_SUPERVISOR), true);
  assert.equal(canExecuteCapaAction(ROLES.QC_ANALYST), true);
  assert.equal(canExecuteCapaAction(ROLES.QA_REVIEWER), true);
  assert.equal(canExecuteCapaAction(ROLES.ADMIN), false);

  assert.equal(canStartCapaPhase(ROLES.QA_REVIEWER), true);
  assert.equal(canStartCapaPhase(ROLES.PRODUCTION_SUPERVISOR), false);

  assert.equal(canCancelCapaActionRole(ROLES.QA_REVIEWER), true);
  assert.equal(canCancelCapaActionRole(ROLES.PRODUCTION_SUPERVISOR), false);

  assert.equal(canReopenCapaPhase(ROLES.QA_REVIEWER), true);
  assert.equal(canReopenCapaPhase(ROLES.ADMIN), false);

  assert.equal(canVerifyAndCloseDeviation(ROLES.QA_REVIEWER), true);
  assert.equal(canVerifyAndCloseDeviation(ROLES.ADMIN), false);

  // Raw Material Inventory & Warehouse
  for (const role of ALL_ROLES) {
    assert.equal(canViewInventory(role), true);
  }
  for (const role of [ROLES.ADMIN, ROLES.PRODUCTION_SUPERVISOR]) {
    assert.equal(canManageRawMaterials(role), true);
    assert.equal(canManageWarehouses(role), true);
    assert.equal(canReceiveGoods(role), true);
  }
  for (const role of [ROLES.OPERATOR, ROLES.QC_ANALYST, ROLES.QA_REVIEWER]) {
    assert.equal(canManageRawMaterials(role), false);
    assert.equal(canManageWarehouses(role), false);
    assert.equal(canReceiveGoods(role), false);
  }

  assert.equal(canIssueMaterials(ROLES.ADMIN), true);
  assert.equal(canIssueMaterials(ROLES.PRODUCTION_SUPERVISOR), true);
  assert.equal(canIssueMaterials(ROLES.OPERATOR), true);
  assert.equal(canIssueMaterials(ROLES.QC_ANALYST), false);
  assert.equal(canIssueMaterials(ROLES.QA_REVIEWER), false);

  assert.equal(canAdjustStock(ROLES.ADMIN), true);
  assert.equal(canAdjustStock(ROLES.PRODUCTION_SUPERVISOR), true);
  assert.equal(canAdjustStock(ROLES.QA_REVIEWER), true);
  assert.equal(canAdjustStock(ROLES.OPERATOR), false);
  assert.equal(canAdjustStock(ROLES.QC_ANALYST), false);

  // Dedicated AI Batch Analysis Reports
  assert.equal(canGenerateBatchAnalysis(ROLES.PRODUCTION_SUPERVISOR), true);
  assert.equal(canGenerateBatchAnalysis(ROLES.ADMIN), false);
  assert.equal(canGenerateBatchAnalysis(ROLES.QA_REVIEWER), false);
  assert.equal(canGenerateBatchAnalysis(ROLES.OPERATOR), false);
  assert.equal(canGenerateBatchAnalysis(ROLES.QC_ANALYST), false);

  assert.equal(canViewBatchAnalysis(ROLES.PRODUCTION_SUPERVISOR), true);
  assert.equal(canViewBatchAnalysis(ROLES.QA_REVIEWER), true);
  assert.equal(canViewBatchAnalysis(ROLES.ADMIN), true);
  assert.equal(canViewBatchAnalysis(ROLES.OPERATOR), false);
  assert.equal(canViewBatchAnalysis(ROLES.QC_ANALYST), false);

  assert.equal(canPermanentlyDeleteBatchAnalysis(ROLES.ADMIN), true);
  assert.equal(canPermanentlyDeleteBatchAnalysis(ROLES.PRODUCTION_SUPERVISOR), false);
  assert.equal(canPermanentlyDeleteBatchAnalysis(ROLES.QA_REVIEWER), false);
  assert.equal(canPermanentlyDeleteBatchAnalysis(ROLES.OPERATOR), false);
  assert.equal(canPermanentlyDeleteBatchAnalysis(ROLES.QC_ANALYST), false);
});

