import test from 'node:test';
import assert from 'node:assert/strict';
import React, { useState } from 'react';
import { createDomEnvironment, MiniEvent } from '../../scripts/dom-harness.mjs';

function makeAuthValue(role = 'ADMIN') {
  const map = {
    ADMIN: { username: 'admin', fullName: 'System Admin' },
    PRODUCTION_SUPERVISOR: { username: 'prod.sup', fullName: 'Rohan Mehta' },
    QA_REVIEWER: { username: 'qa.reviewer', fullName: 'Dr. Asha Sen' },
  };
  const info = map[role] || map.ADMIN;
  return {
    user: { id: 1, username: info.username, fullName: info.fullName, role, enabled: true },
    role,
    token: 'phase-8b3-jwt',
    actorDisplay: `${info.fullName} (@${info.username})`,
    isAuthenticated: true,
    isInitializing: false,
    sessionMessage: null,
    forbiddenBanner: null,
    login: async () => {},
    logout: () => {},
    clearSessionMessage: () => {},
    dismissForbiddenBanner: () => {},
  };
}

function jsonResponse(body, status = 200, url = 'http://localhost:8080/api/test') {
  return {
    ok: status >= 200 && status < 300,
    status,
    url,
    headers: {
      get: (name) => (name.toLowerCase() === 'content-type' ? 'application/json' : null),
    },
    json: async () => body,
  };
}

function makeKeyEvent(key) {
  const evt = new MiniEvent('keydown', { bubbles: true, cancelable: true });
  evt.key = key;
  evt.code = key;
  return evt;
}

function assertLabelPairsExist(container, labelTexts) {
  const labels = container.querySelectorAll('label');
  for (const text of labelTexts) {
    const label = labels.find((l) => l.textContent.includes(text));
    assert.ok(label, `Expected label containing "${text}"`);
    const targetId = label.getAttribute('for');
    assert.ok(targetId, `Expected htmlFor attribute on label "${text}"`);
    const targetEl = container.querySelector(`#${targetId}`);
    assert.ok(targetEl, `Expected control with id="${targetId}" for label "${text}"`);
  }
}

test('ProductManagement distinguishes loading, fetch error + Retry recovery, empty catalog, and populated states', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: ProductManagement } = await import('./ProductManagement.jsx');

  let resolveInitialFetch;
  let callCount = 0;
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    callCount += 1;
    if (callCount === 1) {
      return new Promise((resolve) => {
        resolveInitialFetch = resolve;
      });
    }
    if (callCount === 2) {
      return jsonResponse([], 200, url);
    }
    return jsonResponse(
      [
        {
          id: 101,
          productCode: 'PRD-101',
          productName: 'Amoxicillin 250mg',
          strength: '250 mg',
          dosageForm: 'Capsule',
          status: 'ACTIVE',
          updatedAt: '2026-11-10T10:00:00',
        },
      ],
      200,
      url
    );
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(ProductManagement)
      )
    );

    // 1. Loading state
    assert.ok(dom.container.textContent.includes('Loading pharmaceutical product catalog...'));
    assert.equal(dom.container.textContent.includes('No products found'), false);

    // 2. Fetch fails -> distinct error panel with Retry (NOT empty list copy)
    resolveInitialFetch(jsonResponse({ message: 'Database connection unavailable' }, 503));
    await dom.flush();
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Unable to load pharmaceutical product catalog'));
    assert.ok(dom.container.textContent.includes('Database connection unavailable'));
    assert.equal(dom.container.textContent.includes('No products found'), false);
    assert.equal(dom.container.textContent.includes('No matching products found'), false);

    // 3. Retry -> succeeds with empty array -> distinct empty catalog state
    const retryBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Retry')
    );
    assert.ok(retryBtn);
    await dom.click(retryBtn);
    await dom.flush();

    assert.ok(dom.container.textContent.includes('No products found'));
    assert.equal(
      dom.container.textContent.includes('Unable to load pharmaceutical product catalog'),
      false
    );

    // 4. Refresh -> populated table
    const refreshBtn = dom.container.querySelector('button[title="Refresh product list"]');
    assert.ok(refreshBtn);
    await dom.click(refreshBtn);
    await dom.flush();

    assert.ok(dom.container.textContent.includes('PRD-101'));
    assert.ok(dom.container.textContent.includes('Amoxicillin 250mg'));
    assert.ok(dom.container.textContent.includes('Showing 1\u20131 of 1 products'));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('ProductManagement requires confirmation before deactivating or activating a product', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: ProductManagement } = await import('./ProductManagement.jsx');

  let currentProduct = {
    id: 1,
    productCode: 'PRD-001',
    productName: 'Paracetamol 500mg',
    strength: '500 mg',
    dosageForm: 'Tablet',
    status: 'ACTIVE',
    updatedAt: '2026-11-10T10:00:00',
  };
  const patchCalls = [];

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    if (url.includes('/products/1/status') && options.method === 'PATCH') {
      const payload = JSON.parse(options.body);
      patchCalls.push(payload);
      currentProduct = { ...currentProduct, status: payload.status };
      return jsonResponse(currentProduct, 200, url);
    }
    return jsonResponse([currentProduct], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(ProductManagement)
      )
    );
    await dom.flush();

    // Click Deactivate -> opens confirmation dialog without mutating yet
    const deactivateBtn = dom.container.querySelector('button[title="Deactivate product"]');
    assert.ok(deactivateBtn);
    await dom.click(deactivateBtn);
    await dom.flush();

    const dialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(dialog);
    assert.ok(dialog.textContent.includes('Deactivate Product'));
    assert.ok(dialog.textContent.includes('Paracetamol 500mg'));
    assert.equal(patchCalls.length, 0);

    // Cancel leaves product ACTIVE with 0 PATCH requests
    const cancelBtn = [...dialog.querySelectorAll('button')].find(
      (b) => b.textContent === 'Cancel'
    );
    assert.ok(cancelBtn);
    await dom.click(cancelBtn);
    await dom.flush();

    assert.equal(dom.container.querySelector('[role="dialog"]'), null);
    assert.equal(patchCalls.length, 0);
    assert.ok(dom.container.querySelector('button[title="Deactivate product"]'));

    // Re-open Deactivate and confirm -> executes PATCH INACTIVE
    await dom.click(dom.container.querySelector('button[title="Deactivate product"]'));
    await dom.flush();
    const confirmDialog = dom.container.querySelector('[role="dialog"]');
    const confirmDeactivateBtn = [...confirmDialog.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Deactivate Product')
    );
    assert.ok(confirmDeactivateBtn);
    await dom.click(confirmDeactivateBtn);
    await dom.flush();
    await dom.flush();

    assert.deepEqual(patchCalls, [{ status: 'INACTIVE' }]);
    assert.ok(dom.container.querySelector('button[title="Activate product"]'));

    // Activate flow also asks confirmation
    await dom.click(dom.container.querySelector('button[title="Activate product"]'));
    await dom.flush();
    const activateDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(activateDialog?.textContent.includes('Activate Product'));
    const confirmActivateBtn = [...activateDialog.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Activate Product')
    );
    await dom.click(confirmActivateBtn);
    await dom.flush();
    await dom.flush();

    assert.deepEqual(patchCalls, [{ status: 'INACTIVE' }, { status: 'ACTIVE' }]);
    assert.ok(dom.container.querySelector('button[title="Deactivate product"]'));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('ProductManagement filters full dataset before pagination and resets page to 1 when filters or search change', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: ProductManagement } = await import('./ProductManagement.jsx');

  const products = Array.from({ length: 32 }, (_, idx) => {
    const num = idx + 1;
    const isInactive = num === 2 || num === 28 || num === 31;
    return {
      id: num,
      productCode: `PRD-${String(num).padStart(3, '0')}`,
      productName: `Medicine Form ${num}`,
      strength: '100 mg',
      dosageForm: 'Tablet',
      status: isInactive ? 'INACTIVE' : 'ACTIVE',
      updatedAt: '2026-11-10T10:00:00',
    };
  });

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => jsonResponse(products, 200, url);

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(ProductManagement)
      )
    );
    await dom.flush();

    // Default 25 rows/page
    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 32 products'));
    assert.equal(dom.container.querySelector('tbody').querySelectorAll('tr').length, 25);

    // Move to Page 2
    await dom.click(dom.container.querySelector('button[aria-label="Page 2"]'));
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Showing 26\u201332 of 32 products'));
    assert.equal(
      dom.container.querySelector('button[aria-label="Page 2"]').getAttribute('aria-current'),
      'page'
    );

    // Filter to INACTIVE while on Page 2 -> matches all 3 INACTIVE items across full dataset and resets to page 1
    const inactiveTab = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Inactive (3)')
    );
    assert.ok(inactiveTab);
    await dom.click(inactiveTab);
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Showing 1\u20133 of 3 products'));
    assert.ok(dom.container.textContent.includes('PRD-002'));
    assert.ok(dom.container.textContent.includes('PRD-028'));
    assert.ok(dom.container.textContent.includes('PRD-031'));

    // Return to All (32), go to Page 2, then search for item from Page 1 + item from Page 2
    const allTab = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('All (32)')
    );
    await dom.click(allTab);
    await dom.flush();
    await dom.click(dom.container.querySelector('button[aria-label="Page 2"]'));
    await dom.flush();

    const searchInput = dom.container.querySelector('input[type="text"]');
    await dom.changeInput(searchInput, 'PRD-005');
    await dom.flush();

    // Found Page 1 item while user was previously on Page 2 -> page reset to 1
    assert.ok(dom.container.textContent.includes('Showing 1\u20131 of 1 products'));
    assert.ok(dom.container.textContent.includes('PRD-005'));
    assert.equal(
      dom.container.querySelector('button[aria-label="Page 1"]').getAttribute('aria-current'),
      'page'
    );

    // Non-matching search shows empty state with Reset Filters button
    await dom.changeInput(searchInput, 'NONEXISTENT-999');
    await dom.flush();
    assert.ok(dom.container.textContent.includes('No matching products found'));
    const resetBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Reset Filters')
    );
    assert.ok(resetBtn);
    await dom.click(resetBtn);
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 32 products'));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('ProductModal associates labels with all inputs and surfaces accessible validation errors', async () => {
  const dom = await createDomEnvironment();
  const { default: ProductModal } = await import('./ProductModal.jsx');

  let savedPayload = null;
  let closed = false;

  try {
    await dom.render(
      React.createElement(ProductModal, {
        isOpen: true,
        onClose: () => {
          closed = true;
        },
        onSave: async (data) => {
          savedPayload = data;
        },
        initialData: null,
        isSubmitting: false,
      })
    );
    await dom.flush();

    const dialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(dialog);
    assert.equal(dialog.getAttribute('aria-modal'), 'true');
    assert.ok(dialog.querySelector('fieldset')?.querySelector('legend'));

    assertLabelPairsExist(dialog, [
      'Product Code',
      'Product Name',
      'Strength',
      'Dosage Form',
      'Description',
    ]);

    // Submit empty -> validation errors linked via aria-invalid and aria-describedby
    const submitBtn = [...dialog.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Create Product')
    );
    assert.ok(submitBtn);
    await dom.click(submitBtn);
    await dom.flush();

    assert.equal(savedPayload, null);
    const codeInput = dialog.querySelector('input[name="productCode"]');
    assert.equal(codeInput.getAttribute('aria-invalid'), 'true');
    const describedById = codeInput.getAttribute('aria-describedby');
    assert.ok(describedById);
    const errEl = dialog.querySelector(`#${describedById}`);
    assert.equal(errEl.getAttribute('role'), 'alert');
    assert.match(errEl.textContent, /Product code is required/);

    // Fill valid fields and submit
    await dom.changeInput(codeInput, 'PRD-NEW-1');
    await dom.changeInput(dialog.querySelector('input[name="productName"]'), 'Cetirizine 10mg');
    await dom.changeInput(dialog.querySelector('input[name="strength"]'), '10 mg');
    await dom.click(submitBtn);
    await dom.flush();

    assert.equal(savedPayload?.productCode, 'PRD-NEW-1');
    assert.equal(savedPayload?.productName, 'Cetirizine 10mg');
    assert.equal(savedPayload?.strength, '10 mg');

    await dom.act(async () => {
      dialog.dispatchEvent(makeKeyEvent('Escape'));
    });
    assert.equal(closed, true);
  } finally {
    await dom.unmount();
  }
});

test('RecipeManagement separates fetch errors with Retry and filters full recipe catalog before 25-row pagination', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: RecipeManagement } = await import('./RecipeManagement.jsx');

  const recipes = Array.from({ length: 30 }, (_, idx) => {
    const num = idx + 1;
    return {
      id: num,
      recipeCode: `RCP-${String(num).padStart(3, '0')}`,
      productId: num,
      productCode: `PRD-${String(num).padStart(3, '0')}`,
      productName: `Product ${num}`,
      version: 1,
      batchSize: 500 + num,
      batchUnit: 'kg',
      status: num % 2 === 0 ? 'APPROVED' : 'DRAFT',
      materials: [{ materialName: 'API', quantity: 100, unit: 'kg' }],
      steps: [{ stepNumber: 1, operationName: 'Blend', instructions: 'Mix evenly' }],
      updatedAt: '2026-11-10T10:00:00',
    };
  });

  let callCount = 0;
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    callCount += 1;
    if (callCount === 1) {
      return jsonResponse({ message: 'Recipe service timeout' }, 504, url);
    }
    return jsonResponse(recipes, 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(RecipeManagement)
      )
    );
    await dom.flush();

    // Fetch error is clearly separated from empty state
    assert.ok(dom.container.textContent.includes('Unable to load master recipes'));
    assert.ok(dom.container.textContent.includes('Recipe service timeout'));
    assert.equal(dom.container.textContent.includes('No master recipes found'), false);

    // Retry loads paginated recipes
    const retryBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Retry')
    );
    assert.ok(retryBtn);
    await dom.click(retryBtn);
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 30 recipes'));
    await dom.click(dom.container.querySelector('button[aria-label="Page 2"]'));
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Showing 26\u201330 of 30 recipes'));

    // Search while on Page 2 matches Page 1 recipe and resets page to 1
    const searchInput = dom.container.querySelector('input[type="text"]');
    await dom.changeInput(searchInput, 'RCP-003');
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Showing 1\u20131 of 1 recipes'));
    assert.ok(dom.container.textContent.includes('RCP-003'));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('RecipeModal associates labels & validation, uses stable row keys across deletions, and resists unstable parent re-renders', async () => {
  const dom = await createDomEnvironment();
  const { default: RecipeModal } = await import('./RecipeModal.jsx');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) =>
    jsonResponse(
      [
        {
          id: 7,
          productCode: 'PRD-007',
          productName: 'Ibuprofen 400mg',
          strength: '400 mg',
          status: 'ACTIVE',
        },
        {
          id: 8,
          productCode: 'PRD-008',
          productName: 'Retired Product',
          strength: '10 mg',
          status: 'INACTIVE',
        },
      ],
      200,
      url
    );

  function RecipeEditorHarness() {
    const [tick, setTick] = useState(0);
    // Intentionally construct a brand-new initialData object on every parent re-render
    const clonedInitial = {
      id: 42,
      recipeCode: 'RCP-042-V1',
      productId: 7,
      productName: 'Ibuprofen 400mg',
      productCode: 'PRD-007',
      version: 1,
      batchSize: 500,
      batchUnit: 'kg',
      description: 'Original persisted description',
      materials: [
        { materialName: 'Ibuprofen API', quantity: 400, unit: 'kg' },
        { materialName: 'Starch Binder', quantity: 50, unit: 'kg' },
        { materialName: 'Magnesium Stearate', quantity: 10, unit: 'kg' },
      ],
      steps: [
        { stepNumber: 1, operationName: 'Dispensing', instructions: 'Weigh active & excipients' },
        { stepNumber: 2, operationName: 'Granulation', instructions: 'Wet granulate 15 min' },
        { stepNumber: 3, operationName: 'Compression', instructions: 'Compress tablets' },
      ],
    };

    return React.createElement(
      'div',
      null,
      React.createElement(
        'button',
        {
          type: 'button',
          id: 'rerender-parent-btn',
          onClick: () => setTick((t) => t + 1),
        },
        `Re-render Parent (${tick})`
      ),
      React.createElement(RecipeModal, {
        isOpen: true,
        onClose: () => {},
        onSave: async () => {},
        initialData: clonedInitial,
        isSubmitting: false,
      })
    );
  }

  try {
    // Part A: Create mode labels, active-only product select, and accessible validation
    await dom.render(
      React.createElement(RecipeModal, {
        isOpen: true,
        onClose: () => {},
        onSave: async () => {},
        initialData: null,
        isSubmitting: false,
      })
    );
    await dom.flush();

    const createDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(createDialog);
    assert.ok(createDialog.textContent.includes('Ibuprofen 400mg'));
    assert.equal(createDialog.textContent.includes('Retired Product'), false);

    assertLabelPairsExist(createDialog, [
      'Product',
      'Batch Size',
      'Batch Unit',
      'Description',
      'Material Name',
      'Quantity',
      'Unit',
      'Step Number',
      'Operation Name',
      'Process Instructions',
    ]);

    const createBtn = [...createDialog.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Create Recipe')
    );
    await dom.click(createBtn);
    await dom.flush();

    const batchSizeInput = createDialog.querySelector('input[placeholder="e.g. 1000"]');
    assert.equal(batchSizeInput.getAttribute('aria-invalid'), 'true');
    const batchSizeErr = createDialog.querySelector(`#${batchSizeInput.getAttribute('aria-describedby')}`);
    assert.equal(batchSizeErr.getAttribute('role'), 'alert');
    assert.match(batchSizeErr.textContent, /Batch size must be greater than zero/);

    // Part B: Edit mode stable keys on middle deletion + resilience against unstable parent re-renders
    await dom.render(React.createElement(RecipeEditorHarness));
    await dom.flush();

    const editDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(editDialog);

    // Check stable keys for 3 materials and 3 steps
    const initialMatRows = editDialog.querySelectorAll('[data-row-key]').filter((el) =>
      el.getAttribute('data-row-key').startsWith('mat-')
    );
    const initialStepRows = editDialog.querySelectorAll('[data-row-key]').filter((el) =>
      el.getAttribute('data-row-key').startsWith('step-')
    );
    assert.equal(initialMatRows.length, 3);
    assert.equal(initialStepRows.length, 3);

    const firstMatKey = initialMatRows[0].getAttribute('data-row-key');
    const thirdMatKey = initialMatRows[2].getAttribute('data-row-key');
    const firstStepKey = initialStepRows[0].getAttribute('data-row-key');
    const thirdStepKey = initialStepRows[2].getAttribute('data-row-key');

    // Remove the MIDDLE material (row 2) and MIDDLE step (row 2)
    await dom.click(editDialog.querySelector('button[aria-label="Remove material row 2"]'));
    await dom.click(editDialog.querySelector('button[aria-label="Remove step 2"]'));
    await dom.flush();

    const remainingMatRows = editDialog.querySelectorAll('[data-row-key]').filter((el) =>
      el.getAttribute('data-row-key').startsWith('mat-')
    );
    const remainingStepRows = editDialog.querySelectorAll('[data-row-key]').filter((el) =>
      el.getAttribute('data-row-key').startsWith('step-')
    );
    assert.deepEqual(
      remainingMatRows.map((el) => el.getAttribute('data-row-key')),
      [firstMatKey, thirdMatKey]
    );
    assert.deepEqual(
      remainingStepRows.map((el) => el.getAttribute('data-row-key')),
      [firstStepKey, thirdStepKey]
    );
    // Remaining steps preserved content (Dispensing, Compression) and re-indexed sequentially 1, 2
    assert.equal(remainingStepRows[0].querySelector('input[type="text"]').value, 'Dispensing');
    assert.equal(remainingStepRows[1].querySelector('input[type="text"]').value, 'Compression');
    assert.equal(remainingStepRows[1].querySelector('input[type="number"]').value, '2');

    // User modifies batchSize & description, then triggers parent re-render with fresh initialData object
    const editBatchInput = editDialog.querySelector('input[placeholder="e.g. 1000"]');
    const editDescInput = editDialog.querySelector(
      'textarea[placeholder="Manufacturing instructions, line requirements, yield expectations..."]'
    );
    await dom.changeInput(editBatchInput, '950');
    await dom.changeInput(editDescInput, 'Unsaved operator formulation adjustment');

    await dom.click(dom.container.querySelector('#rerender-parent-btn'));
    await dom.flush();

    // Unstable initialData object identity must NOT reset user's active edits
    assert.equal(editBatchInput.value, '950');
    assert.equal(editDescInput.value, 'Unsaved operator formulation adjustment');
    assert.equal(
      editDialog.querySelectorAll('[data-row-key]').filter((el) =>
        el.getAttribute('data-row-key').startsWith('mat-')
      ).length,
      2
    );
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('RecipeDetailDrawer opens in-tree without nested modals, supports version history switching, and closes cleanly with focus restore', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: RecipeManagement } = await import('./RecipeManagement.jsx');

  const recipes = [
    {
      id: 1,
      recipeCode: 'RCP-ASP-V1',
      productId: 10,
      productCode: 'PRD-ASP',
      productName: 'Aspirin 75mg',
      version: 1,
      batchSize: 100,
      batchUnit: 'kg',
      status: 'OBSOLETE',
      materials: [{ materialName: 'Aspirin API', quantity: 50, unit: 'kg' }],
      steps: [{ stepNumber: 1, operationName: 'Legacy Sieve', instructions: 'Mesh #30' }],
      createdAt: '2026-10-01T10:00:00',
      updatedAt: '2026-10-02T10:00:00',
    },
    {
      id: 2,
      recipeCode: 'RCP-ASP-V2',
      productId: 10,
      productCode: 'PRD-ASP',
      productName: 'Aspirin 75mg',
      version: 2,
      batchSize: 200,
      batchUnit: 'kg',
      status: 'DRAFT',
      description: 'Updated granulation speed',
      materials: [{ materialName: 'Aspirin API', quantity: 100, unit: 'kg' }],
      steps: [{ stepNumber: 1, operationName: 'Updated Blend', instructions: 'Blend 20 min' }],
      createdAt: '2026-11-01T10:00:00',
      updatedAt: '2026-11-02T10:00:00',
    },
  ];

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => jsonResponse(recipes, 200, url);

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(RecipeManagement)
      )
    );
    await dom.flush();

    const viewBtns = dom.container.querySelectorAll('button[title="View recipe details"]');
    assert.equal(viewBtns.length, 2);

    // Focus the second recipe's View button (v2 DRAFT) and open the detail drawer
    viewBtns[1].focus();
    await dom.click(viewBtns[1]);
    await dom.flush();

    const drawer = dom.container.querySelector('[data-testid="recipe-detail-drawer"]');
    assert.ok(drawer);
    assert.equal(drawer.getAttribute('role'), 'region');
    // Crucial: NOT a nested modal dialog
    assert.equal(dom.container.querySelectorAll('[aria-modal="true"]').length, 0);
    assert.ok(drawer.textContent.includes('RCP-ASP-V2'));
    assert.ok(drawer.textContent.includes('Updated granulation speed'));
    assert.ok(drawer.textContent.includes('Updated Blend'));
    assert.ok(drawer.textContent.includes('Product Recipe Version History (2)'));

    // Switch to inspecting v1 inside version history
    const inspectBtn = [...drawer.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Inspect')
    );
    assert.ok(inspectBtn);
    await dom.click(inspectBtn);
    await dom.flush();

    assert.ok(drawer.textContent.includes('RCP-ASP-V1'));
    assert.ok(drawer.textContent.includes('Legacy Sieve'));
    assert.ok(drawer.textContent.includes('Superseded / Obsolete Revision'));

    // Switch back to v2 DRAFT and click Approve Version -> opens ConfirmModal WITHOUT nested modal conflict
    const inspectBackBtn = [...drawer.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Inspect')
    );
    await dom.click(inspectBackBtn);
    await dom.flush();

    const approveDrawerBtn = [...drawer.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Approve Version')
    );
    assert.ok(approveDrawerBtn);
    await dom.click(approveDrawerBtn);
    await dom.flush();

    // Only ONE aria-modal dialog exists (ConfirmModal)
    const openDialogs = dom.container.querySelectorAll('[aria-modal="true"]');
    assert.equal(openDialogs.length, 1);

    // Escape closes ConfirmModal ONLY while keeping drawer open
    await dom.act(async () => {
      openDialogs[0].dispatchEvent(makeKeyEvent('Escape'));
    });
    await dom.flush();
    assert.equal(dom.container.querySelectorAll('[aria-modal="true"]').length, 0);
    assert.ok(dom.container.querySelector('[data-testid="recipe-detail-drawer"]'));

    // Second Escape closes the drawer cleanly and restores focus to triggering View button
    await dom.act(async () => {
      drawer.dispatchEvent(makeKeyEvent('Escape'));
    });
    await dom.flush();
    assert.equal(dom.container.querySelector('[data-testid="recipe-detail-drawer"]'), null);
    assert.equal(dom.document.activeElement, viewBtns[1]);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

