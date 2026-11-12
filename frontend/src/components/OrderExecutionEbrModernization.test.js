import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { createDomEnvironment, MiniEvent } from '../../scripts/dom-harness.mjs';

function makeAuthValue(role = 'ADMIN', overrides = {}) {
  const map = {
    ADMIN: { username: 'admin', fullName: 'System Admin' },
    PRODUCTION_SUPERVISOR: { username: 'prod.sup', fullName: 'Rohan Mehta' },
    OPERATOR: { username: 'operator.floor', fullName: 'Vikram Joshi' },
    QC_ANALYST: { username: 'qc.analyst', fullName: 'Sneha Kulkarni' },
    QA_REVIEWER: { username: 'qa.reviewer', fullName: 'Dr. Asha Sen' },
  };
  const info = map[role] || map.ADMIN;
  return {
    user: { id: 1, username: info.username, fullName: info.fullName, role, enabled: true },
    role,
    token: 'phase-8b4-jwt',
    actorDisplay: `${info.fullName} (@${info.username})`,
    isAuthenticated: true,
    isInitializing: false,
    sessionMessage: null,
    forbiddenBanner: null,
    login: async () => {},
    logout: () => {},
    clearSessionMessage: () => {},
    dismissForbiddenBanner: () => {},
    ...overrides,
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

function getBodyRows(container) {
  return container.querySelector('tbody')?.querySelectorAll('tr') || [];
}

test('OrderManagement separates loading, initial error + Retry, and prevents cancelled orders/batches from appearing completed', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: OrderManagement } = await import('./OrderManagement.jsx');

  const cancelledOrder = {
    id: 501,
    orderNumber: 'MO-CANC-501',
    status: 'CANCELLED',
    productName: 'Paracetamol 500mg',
    productCode: 'PRD-001',
    recipeCode: 'RCP-001',
    recipeVersion: 1,
    plannedQuantity: 2000,
    quantityUnit: 'Tablets',
    targetBatchCount: 2,
    plannedStartDate: '2099-05-01',
    plannedEndDate: '2099-05-05',
    batches: [
      {
        id: 6001,
        batchNumber: 'BAT-CANC-01',
        batchSize: 1000,
        batchUnit: 'Tablets',
        status: 'CANCELLED',
      },
      {
        id: 6002,
        batchNumber: 'BAT-CANC-02',
        batchSize: 1000,
        batchUnit: 'Tablets',
        status: 'CANCELLED',
      },
    ],
  };

  const completedOrder = {
    id: 502,
    orderNumber: 'MO-COMP-502',
    status: 'COMPLETED',
    productName: 'Amoxicillin 250mg',
    productCode: 'PRD-002',
    recipeCode: 'RCP-002',
    recipeVersion: 2,
    plannedQuantity: 1000,
    quantityUnit: 'Capsules',
    targetBatchCount: 1,
    plannedStartDate: '2099-06-01',
    plannedEndDate: '2099-06-03',
    batches: [
      {
        id: 6003,
        batchNumber: 'BAT-COMP-01',
        batchSize: 1000,
        batchUnit: 'Capsules',
        status: 'COMPLETED',
        actualYield: 985,
      },
    ],
  };

  let resolveFirst;
  let orderFetchCount = 0;
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/orders')) {
      orderFetchCount += 1;
      if (orderFetchCount === 1) {
        return new Promise((resolve) => {
          resolveFirst = resolve;
        });
      }
      return jsonResponse([cancelledOrder, completedOrder], 200, url);
    }
    if (url.includes('/orders/501')) {
      return jsonResponse(cancelledOrder, 200, url);
    }
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('PRODUCTION_SUPERVISOR') },
        React.createElement(OrderManagement)
      )
    );

    // 1. Loading state
    assert.ok(dom.container.textContent.includes('Loading manufacturing orders...'));
    assert.equal(dom.container.textContent.includes('No manufacturing orders found'), false);

    // 2. Fetch error -> error panel with Retry, NO empty state underneath
    resolveFirst(jsonResponse({ message: 'Orders service unavailable' }, 503));
    await dom.flush();
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Unable to load manufacturing orders'));
    assert.ok(dom.container.textContent.includes('Orders service unavailable'));
    assert.equal(dom.container.textContent.includes('No manufacturing orders found'), false);

    // 3. Retry -> populated orders table
    const retryBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Retry')
    );
    assert.ok(retryBtn);
    await dom.click(retryBtn);
    await dom.flush();

    const rows = getBodyRows(dom.container);
    assert.equal(rows.length, 2);

    const cancelledRow = rows.find((r) => r.textContent.includes('MO-CANC-501'));
    assert.ok(cancelledRow);
    assert.ok(cancelledRow.textContent.includes('CANCELLED'));
    assert.ok(cancelledRow.textContent.includes('Cancelled (2 of 2)'));
    assert.equal(cancelledRow.textContent.includes('100%'), false);

    const cancelledBar = cancelledRow.querySelector('[role="progressbar"]');
    assert.ok(cancelledBar);
    assert.equal(cancelledBar.getAttribute('aria-valuenow'), '0');
    assert.equal(cancelledBar.getAttribute('aria-valuetext'), 'Order cancelled — 0% completed');

    const completedRow = rows.find((r) => r.textContent.includes('MO-COMP-502'));
    assert.ok(completedRow);
    const completedBar = completedRow.querySelector('[role="progressbar"]');
    assert.equal(completedBar.getAttribute('aria-valuenow'), '100');

    // 4. Open OrderDetailsModal for CANCELLED order -> detail drawer also blocks 100% completion look
    const viewCancelledBtn = cancelledRow.querySelector('button[title="View order details and batch records"]');
    assert.ok(viewCancelledBtn);
    await dom.click(viewCancelledBtn);
    await dom.flush();

    const detailDrawer = dom.container.querySelector('[data-testid="order-detail-drawer"]');
    assert.ok(detailDrawer);
    assert.ok(detailDrawer.textContent.includes('Order Cancelled'));
    assert.ok(detailDrawer.textContent.includes('All 2 cancelled'));
    const detailBar = detailDrawer.querySelector('[role="progressbar"]');
    assert.ok(detailBar);
    assert.equal(detailBar.getAttribute('aria-valuenow'), '0');
    assert.equal(detailBar.getAttribute('aria-valuetext'), 'Cancelled — 0% completed');
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('OrderManagement filters across the full dataset before 25-row pagination and resets to page 1 on filter or search change', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: OrderManagement } = await import('./OrderManagement.jsx');

  const orders = Array.from({ length: 30 }, (_, idx) => {
    const num = idx + 1;
    const isTarget = num === 30;
    return {
      id: num,
      orderNumber: `MO-2026-${String(num).padStart(4, '0')}`,
      status: isTarget ? 'CANCELLED' : num % 2 === 0 ? 'PLANNED' : 'COMPLETED',
      productName: isTarget ? 'Cefixime 200mg Special' : `Standard Tablet ${num}`,
      productCode: isTarget ? 'PRD-CEFIX' : `PRD-${String(num).padStart(3, '0')}`,
      recipeCode: `RCP-${String(num).padStart(3, '0')}`,
      recipeVersion: 1,
      plannedQuantity: 1000,
      quantityUnit: 'Tablets',
      targetBatchCount: 1,
      plannedStartDate: '2099-04-10',
      plannedEndDate: '2099-04-15',
      batches: [
        {
          id: 9000 + num,
          batchNumber: `BAT-${String(num).padStart(3, '0')}`,
          batchSize: 1000,
          batchUnit: 'Tablets',
          status: isTarget ? 'CANCELLED' : num % 2 === 0 ? 'PLANNED' : 'COMPLETED',
        },
      ],
    };
  });

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => jsonResponse(orders, 200, url);

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(OrderManagement)
      )
    );
    await dom.flush();

    // Default page size = 25
    assert.equal(getBodyRows(dom.container).length, 25);
    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 30 orders'));
    assert.equal(dom.container.textContent.includes('Cefixime 200mg Special'), false);

    // Move to page 2 -> see remaining 5 including row 30
    const page2Btn = dom.container.querySelector('button[aria-label="Page 2"]');
    assert.ok(page2Btn);
    await dom.click(page2Btn);
    await dom.flush();

    assert.equal(getBodyRows(dom.container).length, 5);
    assert.ok(dom.container.textContent.includes('Showing 26\u201330 of 30 orders'));
    assert.ok(dom.container.textContent.includes('Cefixime 200mg Special'));

    // Search for row 30 while on page 2 -> resets to page 1 and matches across full dataset
    const searchInput = dom.container.querySelector(
      'input[placeholder="Search by order number, product name, or product code..."]'
    );
    assert.ok(searchInput);
    await dom.changeInput(searchInput, 'Cefixime');
    await dom.flush();

    assert.equal(getBodyRows(dom.container).length, 1);
    assert.ok(dom.container.textContent.includes('Showing 1\u20131 of 1 orders'));
    assert.ok(dom.container.textContent.includes('MO-2026-0030'));

    // Reset Filters restores all 30 orders
    await dom.click(
      [...dom.container.querySelectorAll('button')].find((b) => b.textContent.includes('Clear'))
    );
    await dom.flush();
    assert.equal(getBodyRows(dom.container).length, 25);

    // Jump to Page 2, then switch status filter -> must reset back to Page 1
    await dom.click(dom.container.querySelector('button[aria-label="Page 2"]'));
    await dom.flush();
    const cancelledFilterBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Cancelled (1)')
    );
    assert.ok(cancelledFilterBtn);
    await dom.click(cancelledFilterBtn);
    await dom.flush();

    assert.equal(getBodyRows(dom.container).length, 1);
    assert.ok(dom.container.textContent.includes('Showing 1\u20131 of 1 orders'));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('OrderModal and BatchYieldModal associate labels with controls, show accessible validation alerts, and display yield recovery preview', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: OrderModal } = await import('./OrderModal.jsx');
  const { default: BatchYieldModal } = await import('./BatchYieldModal.jsx');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/products')) {
      return jsonResponse(
        [
          {
            id: 10,
            productCode: 'PRD-010',
            productName: 'Paracetamol 500mg',
            status: 'ACTIVE',
          },
        ],
        200,
        url
      );
    }
    if (url.includes('/recipes')) {
      return jsonResponse(
        [
          {
            id: 20,
            productId: 10,
            recipeCode: 'RCP-020',
            version: 1,
            batchSize: 1000,
            batchUnit: 'Tablets',
            status: 'APPROVED',
          },
        ],
        200,
        url
      );
    }
    return jsonResponse({}, 200, url);
  };

  try {
    // 1. OrderModal accessible labels & validation alerts
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(OrderModal, {
          isOpen: true,
          onClose: () => {},
          onSuccess: () => {},
        })
      )
    );
    await dom.flush();

    assertLabelPairsExist(dom.container, [
      'Target Product',
      'Master Recipe (Approved Only)',
      'Planned Order Quantity',
      'Planned Start Date',
      'Planned End Date',
      'Production Notes',
    ]);

    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();

    const orderAlerts = dom.container.querySelectorAll('[role="alert"]');
    assert.ok(orderAlerts.length >= 3);
    assert.ok(dom.container.textContent.includes('Product is required'));
    assert.ok(dom.container.textContent.includes('Recipe is required'));
    assert.ok(dom.container.textContent.includes('Planned quantity is required'));
    assert.ok(dom.container.querySelector('[aria-invalid="true"]'));

    // 2. BatchYieldModal label, invalid yield alert, and live recovery percentage
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(BatchYieldModal, {
          isOpen: true,
          batch: {
            id: 77,
            batchNumber: 'BAT-2026-0077',
            batchSize: 1000,
            batchUnit: 'Tablets',
          },
          onClose: () => {},
          onSuccess: () => {},
        })
      )
    );
    await dom.flush();

    assertLabelPairsExist(dom.container, ['Actual Yield']);
    const yieldInput = dom.container.querySelector('#batch-actual-yield');
    assert.ok(yieldInput);

    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();
    assert.equal(yieldInput.getAttribute('aria-invalid'), 'true');
    assert.ok(dom.container.querySelector('[role="alert"]').textContent.includes('Actual yield is required'));

    await dom.changeInput(yieldInput, '985');
    await dom.flush();
    assert.ok(dom.container.textContent.includes('98.5% yield recovery'));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('ProductionExecution handles error + Retry recovery, 25-row default pagination, full-dataset search reset, and empty-state module links', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: ProductionExecution } = await import('./ProductionExecution.jsx');

  const queueBatches = Array.from({ length: 28 }, (_, idx) => {
    const num = idx + 1;
    return {
      batchId: 1000 + num,
      batchNumber: `BAT-EXEC-${String(num).padStart(4, '0')}`,
      orderNumber: `MO-EXEC-${String(num).padStart(4, '0')}`,
      productName: num === 28 ? 'Ibuprofen 400mg Express' : `Batch Product ${num}`,
      recipeCode: `RCP-${String(num).padStart(3, '0')}`,
      recipeVersion: 1,
      batchSize: 500,
      batchUnit: 'Tablets',
      startTime: '2026-11-11T08:00:00',
      batchStatus: 'IN_PROGRESS',
    };
  });

  let queueCalls = 0;
  let resolveInitial;
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/batches/execution')) {
      queueCalls += 1;
      if (queueCalls === 1) {
        return new Promise((resolve) => {
          resolveInitial = resolve;
        });
      }
      if (queueCalls === 2) {
        return jsonResponse(queueBatches, 200, url);
      }
      return jsonResponse([], 200, url);
    }
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(ProductionExecution)
      )
    );

    assert.ok(dom.container.textContent.includes('Loading production execution queue...'));

    // 1. Initial error -> Retry card cleanly hides empty queue message
    resolveInitial(jsonResponse({ message: 'Queue API error 502' }, 502));
    await dom.flush();
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Unable to load production execution queue'));
    assert.ok(dom.container.textContent.includes('Queue API error 502'));
    assert.equal(dom.container.textContent.includes('No active batches in execution queue'), false);

    // 2. Retry -> loads 28 items paginated at 25/page
    const retryBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Retry')
    );
    await dom.click(retryBtn);
    await dom.flush();

    assert.equal(getBodyRows(dom.container).length, 25);
    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 28 batches'));
    assert.equal(dom.container.textContent.includes('Ibuprofen 400mg Express'), false);

    // 3. Navigate to page 2 -> shows remaining 3 including BAT-EXEC-0028
    await dom.click(dom.container.querySelector('button[aria-label="Page 2"]'));
    await dom.flush();
    assert.equal(getBodyRows(dom.container).length, 3);
    assert.ok(dom.container.textContent.includes('Ibuprofen 400mg Express'));

    // 4. Search from page 2 matches across full dataset & resets to page 1
    const searchInput = dom.container.querySelector(
      'input[placeholder="Search by batch number, order number, product, or recipe..."]'
    );
    await dom.changeInput(searchInput, 'Ibuprofen');
    await dom.flush();

    assert.equal(getBodyRows(dom.container).length, 1);
    assert.ok(dom.container.textContent.includes('Showing 1\u20131 of 1 batches'));

    // Clear search and refresh into empty queue -> verify actionable cross-module empty state buttons
    await dom.click(
      [...dom.container.querySelectorAll('button')].find((b) => b.textContent.includes('Clear'))
    );
    const refreshBtn = dom.container.querySelector('button[title="Refresh execution queue"]');
    await dom.click(refreshBtn);
    await dom.flush();

    assert.ok(dom.container.textContent.includes('No active batches in execution queue'));
    assert.ok(dom.container.textContent.includes('Go to Manufacturing Orders'));
    assert.ok(dom.container.textContent.includes('Open Quality Management'));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('ExecutionWorkspace renders ARIA progress, sequential step states, non-mutating filters, step validation, and legacy init guard', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: ExecutionWorkspace } = await import('./ExecutionWorkspace.jsx');

  const baseWorkspace = {
    batchId: 201,
    batchNumber: 'BAT-2026-0201-01',
    batchStatus: 'IN_PROGRESS',
    orderNumber: 'MO-2026-0201',
    productName: 'Paracetamol 500mg',
    recipeCode: 'RCP-001',
    recipeVersion: 2,
    batchSize: 1000,
    batchUnit: 'Tablets',
    executionSteps: [
      {
        id: 1,
        stepNumber: 1,
        operationName: 'Dispensing',
        instructions: 'Weigh API',
        status: 'COMPLETED',
        actualValue: '500.0 g',
        observations: 'Exact match',
        executedBy: 'operator.floor',
        startedAt: '2026-11-11T09:00:00',
        completedAt: '2026-11-11T09:15:00',
      },
      {
        id: 2,
        stepNumber: 2,
        operationName: 'Granulation',
        instructions: 'Granulate 20 min',
        status: 'IN_PROGRESS',
        startedAt: '2026-11-11T09:20:00',
      },
      {
        id: 3,
        stepNumber: 3,
        operationName: 'Compression',
        instructions: 'Compress at 12 kN',
        status: 'PENDING',
      },
    ],
  };

  let currentWorkspace = baseWorkspace;
  const requests = [];
  const prevFetch = globalThis.fetch;

  globalThis.fetch = async (url, options = {}) => {
    const method = options.method || 'GET';
    const body = options.body ? JSON.parse(options.body) : null;
    requests.push({ url, method, body });

    if (url.endsWith('/batches/201/steps')) {
      return jsonResponse(currentWorkspace, 200, url);
    }
    if (url.endsWith('/batches/201/steps/2/complete')) {
      currentWorkspace = {
        ...baseWorkspace,
        executionSteps: [
          baseWorkspace.executionSteps[0],
          {
            ...baseWorkspace.executionSteps[1],
            status: 'COMPLETED',
            actualValue: body.actualValue,
            observations: body.observations,
            executedBy: 'operator.floor',
          },
          baseWorkspace.executionSteps[2],
        ],
      };
      return jsonResponse(currentWorkspace.executionSteps[1], 200, url);
    }
    if (url.endsWith('/batches/202/steps')) {
      return jsonResponse({ ...baseWorkspace, batchId: 202, executionSteps: [] }, 200, url);
    }
    if (url.endsWith('/batches/202/steps/initialize')) {
      return jsonResponse([], 200, url);
    }
    return jsonResponse({}, 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(ExecutionWorkspace, {
          batchId: 201,
          readOnly: false,
          onBack: () => {},
        })
      )
    );
    await dom.flush();

    // 1. Accessible ARIA ProgressBar
    const bar = dom.container.querySelector('[role="progressbar"]');
    assert.ok(bar);
    assert.equal(bar.getAttribute('aria-valuenow'), '33');
    assert.equal(bar.getAttribute('aria-valuetext'), '1 of 3 steps completed (33%)');

    // 2. Step visual distinction & active step lock
    assert.ok(dom.container.querySelector('[data-step-status="COMPLETED"]'));
    assert.ok(dom.container.querySelector('[data-step-status="IN_PROGRESS"]'));
    assert.ok(dom.container.querySelector('[data-step-status="PENDING"]'));
    assert.ok(dom.container.textContent.includes('Complete Step'));
    assert.ok(dom.container.textContent.includes('Locked (Step Active)'));

    // 3. Non-mutating step filter
    const completedFilterBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Completed (1)')
    );
    assert.ok(completedFilterBtn);
    await dom.click(completedFilterBtn);
    await dom.flush();

    assert.equal(dom.container.querySelectorAll('[data-step-status]').length, 1);
    assert.ok(dom.container.textContent.includes('Dispensing'));
    assert.equal(dom.container.textContent.includes('Granulation'), false);

    const allFilterBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('All (3)')
    );
    await dom.click(allFilterBtn);
    await dom.flush();
    assert.equal(dom.container.querySelectorAll('[data-step-status]').length, 3);

    // 4. CompleteStepModal validation + successful submission -> Step 3 becomes Next Eligible Step
    const completeBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Complete Step')
    );
    await dom.click(completeBtn);
    await dom.flush();

    const actualInput = dom.container.querySelector('#exec-step-actual-value');
    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();

    assert.equal(actualInput.getAttribute('aria-invalid'), 'true');
    assert.ok(
      dom.container.querySelector('[role="alert"]').textContent.includes('Actual value is mandatory')
    );

    await dom.changeInput(actualInput, '19.5 min @ 42 C');
    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();

    assert.equal(dom.container.querySelector('#exec-step-actual-value'), null);
    assert.equal(dom.container.querySelector('[role="progressbar"]').getAttribute('aria-valuenow'), '67');
    const step3El = dom.container.querySelector('[data-step-status="PENDING"]');
    assert.ok(step3El);
    assert.equal(step3El.getAttribute('data-step-eligible'), 'true');
    assert.ok(step3El.textContent.includes('Next Eligible Step'));
    assert.ok(step3El.textContent.includes('Start Step'));

    // 5. readOnly=true never triggers legacy backfill POST /steps/initialize even when steps=[]
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(ExecutionWorkspace, {
          batchId: 202,
          readOnly: true,
          readOnlyReason: 'history',
        })
      )
    );
    await dom.flush();
    assert.equal(
      requests.some((r) => r.url.includes('/steps/initialize')),
      false,
      'Read-only workspace must never initialize execution steps'
    );
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('OrderDetailsModal and ExecutionHistoryModal operate as non-nested drawers with Escape/focus restoration and verified eBR context', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: OrderDetailsModal } = await import('./OrderDetailsModal.jsx');

  const orderData = {
    id: 40,
    orderNumber: 'MO-2026-0040',
    status: 'COMPLETED',
    productName: 'Azithromycin 500mg',
    productCode: 'PRD-AZM',
    recipeId: 9,
    recipeCode: 'RCP-AZM',
    recipeVersion: 3,
    plannedQuantity: 1000,
    quantityUnit: 'Tablets',
    targetBatchCount: 1,
    plannedStartDate: '2026-11-01',
    plannedEndDate: '2026-11-02',
    actualStartDate: '2026-11-01T08:00:00',
    actualEndDate: '2026-11-02T17:00:00',
    batches: [
      {
        id: 401,
        batchNumber: 'BAT-AZM-01',
        batchSize: 1000,
        batchUnit: 'Tablets',
        actualYield: 992,
        status: 'COMPLETED',
        qaStatus: 'RELEASED',
        manufactureDate: '2026-11-02',
        expiryDate: '2028-11-02',
        startTime: '2026-11-01T08:15:00',
        endTime: '2026-11-02T16:45:00',
      },
    ],
  };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.includes('/orders/40')) {
      return jsonResponse(orderData, 200, url);
    }
    if (url.endsWith('/batches/401/steps')) {
      return jsonResponse(
        {
          batchId: 401,
          batchNumber: 'BAT-AZM-01',
          batchStatus: 'COMPLETED',
          orderNumber: 'MO-2026-0040',
          productName: 'Azithromycin 500mg',
          recipeCode: 'RCP-AZM',
          recipeVersion: 3,
          batchSize: 1000,
          batchUnit: 'Tablets',
          executionSteps: [
            {
              id: 701,
              stepNumber: 1,
              operationName: 'Final Blending',
              instructions: 'Blend 15 min @ 12 RPM',
              status: 'COMPLETED',
              actualValue: '15 min @ 12 RPM',
              observations: 'Uniform blend verified',
              executedBy: 'operator.floor',
              startedAt: '2026-11-01T08:30:00',
              completedAt: '2026-11-01T08:45:00',
            },
          ],
        },
        200,
        url
      );
    }
    return jsonResponse({}, 200, url);
  };

  let closedOrderDrawer = false;
  try {
    const triggerFocusTarget = dom.document.createElement('button');
    triggerFocusTarget.textContent = 'External Trigger';
    dom.document.body.appendChild(triggerFocusTarget);
    triggerFocusTarget.focus();

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(OrderDetailsModal, {
          isOpen: true,
          orderId: 40,
          onClose: () => {
            closedOrderDrawer = true;
          },
        })
      )
    );
    await dom.flush();

    const orderDrawer = dom.container.querySelector('[data-testid="order-detail-drawer"]');
    assert.ok(orderDrawer);
    assert.equal(orderDrawer.getAttribute('role'), 'region');
    assert.equal(dom.container.querySelectorAll('[aria-modal="true"]').length, 0);

    // Open ExecutionHistoryModal from inside OrderDetailsModal
    const historyBtn = [...orderDrawer.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Execution History')
    );
    assert.ok(historyBtn);
    historyBtn.focus();
    await dom.click(historyBtn);
    await dom.flush();

    const historyDrawer = dom.container.querySelector('[data-testid="execution-history-drawer"]');
    assert.ok(historyDrawer);
    assert.equal(historyDrawer.getAttribute('role'), 'region');
    assert.equal(historyDrawer.getAttribute('data-ebr-drawer'), 'true');
    // Still NO stacked aria-modal dialogs
    assert.equal(dom.container.querySelectorAll('[aria-modal="true"]').length, 0);

    // Verified eBR yield recovery & dates are passed cleanly and displayed
    assert.ok(historyDrawer.textContent.includes('992 Tablets'));
    assert.ok(historyDrawer.textContent.includes('99.2% of target size'));
    assert.ok(historyDrawer.textContent.includes('RELEASED'));
    assert.ok(historyDrawer.textContent.includes('Blend 15 min @ 12 RPM'));
    assert.ok(historyDrawer.textContent.includes('Uniform blend verified'));
    assert.ok(historyDrawer.textContent.includes('Open Quality Dossier'));

    // First Escape closes ONLY ExecutionHistoryModal and preserves OrderDetailsModal
    await dom.act(async () => {
      historyDrawer.dispatchEvent(makeKeyEvent('Escape'));
    });
    await dom.flush();

    assert.equal(dom.container.querySelector('[data-testid="execution-history-drawer"]'), null);
    assert.ok(dom.container.querySelector('[data-testid="order-detail-drawer"]'));
    assert.equal(closedOrderDrawer, false);
    assert.equal(dom.document.activeElement, historyBtn);

    // Second Escape closes OrderDetailsModal
    await dom.act(async () => {
      orderDrawer.dispatchEvent(makeKeyEvent('Escape'));
    });
    await dom.flush();
    assert.equal(closedOrderDrawer, true);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('App supports deep links, cross-module navigation, eBR QualityBatchDossier timeline/recovery, and Recipe RBAC safety', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/orders?orderId=11');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: App } = await import('../App.jsx');

  const sampleOrder = {
    id: 11,
    orderNumber: 'MO-2026-0011',
    status: 'IN_PROGRESS',
    productId: 10,
    productName: 'Paracetamol 500mg',
    productCode: 'PRD-001',
    recipeId: 5,
    recipeCode: 'RCP-005',
    recipeVersion: 2,
    plannedQuantity: 1000,
    quantityUnit: 'Tablets',
    targetBatchCount: 1,
    plannedStartDate: '2026-11-10',
    plannedEndDate: '2026-11-12',
    batches: [
      {
        id: 77,
        batchNumber: 'BAT-2026-0077-01',
        batchSize: 1000,
        batchUnit: 'Tablets',
        actualYield: 992,
        status: 'COMPLETED',
        qaStatus: 'PENDING_QA',
      },
    ],
  };

  const sampleDossier = {
    batchId: 77,
    batchNumber: 'BAT-2026-0077-01',
    batchStatus: 'COMPLETED',
    qaStatus: 'PENDING_QA',
    orderId: 11,
    orderNumber: 'MO-2026-0011',
    productId: 10,
    productName: 'Paracetamol 500mg',
    productCode: 'PRD-001',
    recipeId: 5,
    recipeCode: 'RCP-005',
    recipeVersion: 2,
    batchSize: 1000,
    batchUnit: 'Tablets',
    actualYield: 992,
    manufactureDate: '2026-11-11',
    expiryDate: '2028-11-11',
    startTime: '2026-11-11T08:00:00',
    endTime: '2026-11-11T16:30:00',
    totalChecksCount: 1,
    passedChecksCount: 1,
    failedChecksCount: 0,
    pendingChecksCount: 0,
    requiredChecksCount: 1,
    totalDeviationsCount: 0,
    openDeviationsCount: 0,
    qualityChecks: [
      {
        id: 901,
        testName: 'Assay',
        specification: '95% - 105%',
        measuredValue: '99.2',
        unit: '%',
        result: 'PASS',
        required: true,
        inspectorName: 'qc.analyst',
      },
    ],
    qaReviews: [],
    deviations: [],
    auditLogs: [],
  };

  const sampleRecipes = [
    {
      id: 5,
      recipeCode: 'RCP-005',
      productId: 10,
      productCode: 'PRD-001',
      productName: 'Paracetamol 500mg',
      version: 2,
      batchSize: 1000,
      batchUnit: 'Tablets',
      status: 'APPROVED',
      materials: [{ materialName: 'Paracetamol API', quantity: 500, unit: 'g' }],
      steps: [{ stepNumber: 1, operationName: 'Mixing', instructions: 'Mix 15 min' }],
    },
  ];

  const prevFetch = globalThis.fetch;
  const calledUrls = [];
  globalThis.fetch = async (url) => {
    calledUrls.push(url);
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP' }, 200, url);
    if (url.endsWith('/orders')) return jsonResponse([sampleOrder], 200, url);
    if (url.includes('/orders/11')) return jsonResponse(sampleOrder, 200, url);
    if (url.endsWith('/recipes')) return jsonResponse(sampleRecipes, 200, url);
    if (url.endsWith('/products')) return jsonResponse([], 200, url);
    if (url.endsWith('/batches/execution')) return jsonResponse([], 200, url);
    if (url.endsWith('/batches/101/steps')) {
      return jsonResponse(
        {
          batchId: 101,
          batchNumber: 'BAT-101',
          batchStatus: 'IN_PROGRESS',
          orderNumber: 'MO-2026-0011',
          productName: 'Paracetamol 500mg',
          recipeCode: 'RCP-005',
          recipeVersion: 2,
          batchSize: 1000,
          batchUnit: 'Tablets',
          executionSteps: [],
        },
        200,
        url
      );
    }
    if (url.endsWith('/batches/77/steps')) {
      return jsonResponse(
        {
          batchId: 77,
          batchNumber: 'BAT-2026-0077-01',
          batchStatus: 'COMPLETED',
          orderNumber: 'MO-2026-0011',
          productName: 'Paracetamol 500mg',
          recipeCode: 'RCP-005',
          recipeVersion: 2,
          batchSize: 1000,
          batchUnit: 'Tablets',
          executionSteps: [
            {
              id: 1,
              stepNumber: 1,
              operationName: 'Mixing',
              instructions: 'Mix 15 min',
              status: 'COMPLETED',
              actualValue: '15 min verified',
            },
          ],
        },
        200,
        url
      );
    }
    if (url.endsWith('/quality/queue')) return jsonResponse([sampleDossier], 200, url);
    if (url.endsWith('/quality/batches/77')) return jsonResponse(sampleDossier, 200, url);
    return jsonResponse([], 200, url);
  };

  try {
    // 1. Deep-link #/orders?orderId=11 auto-opens OrderDetailsModal for QA_REVIEWER
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(App)
      )
    );
    await dom.flush();
    await dom.flush();

    const orderDrawer = dom.container.querySelector('[data-testid="order-detail-drawer"]');
    assert.ok(orderDrawer);
    assert.ok(orderDrawer.textContent.includes('MO-2026-0011'));

    // Click 'Inspect Recipe' -> cross-navigates to #/recipes?recipeId=5 & opens RecipeDetailDrawer
    const inspectRecipeBtn = [...orderDrawer.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Inspect Recipe')
    );
    assert.ok(inspectRecipeBtn);
    await dom.click(inspectRecipeBtn);
    await dom.flush();
    await dom.flush();

    assert.equal(dom.window.location.hash, '#/recipes?recipeId=5');
    const recipeDrawer = dom.container.querySelector('[data-testid="recipe-detail-drawer"]');
    assert.ok(recipeDrawer);
    assert.ok(recipeDrawer.textContent.includes('Paracetamol API'));

    // 2. Hash deep link to #/execution?batchId=101 auto-opens ExecutionWorkspace in Read-Only Monitoring mode for QA_REVIEWER
    await dom.act(async () => {
      dom.window.setHash('#/execution?batchId=101');
    });
    await dom.flush();
    await dom.flush();
    assert.ok(dom.container.textContent.includes('BAT-101'));
    assert.ok(dom.container.textContent.includes('Read-Only Monitoring Mode (Role Restricted)'));

    // Back button returns to Execution Queue and clears ?batchId param
    const backExecBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Back to Execution Queue')
    );
    assert.ok(backExecBtn);
    await dom.click(backExecBtn);
    await dom.flush();
    assert.equal(dom.window.location.hash, '#/execution');

    // 3. Hash deep link to #/quality?batchId=77 opens QualityBatchDossier with yield recovery, timeline & read-only eBR drawer
    await dom.act(async () => {
      dom.window.setHash('#/quality?batchId=77');
    });
    await dom.flush();
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Batch Quality Dossier'));
    assert.ok(dom.container.textContent.includes('99.2% of target size'));
    assert.ok(dom.container.textContent.includes('Manufacture Date'));
    assert.ok(dom.container.textContent.includes('Expiry Date'));
    assert.ok(dom.container.textContent.includes('Inspect Recipe'));

    const inspectEbrBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Inspect eBR History (Read-Only)')
    );
    assert.ok(inspectEbrBtn);
    await dom.click(inspectEbrBtn);
    await dom.flush();

    const ebrDrawer = dom.container.querySelector('[data-testid="execution-history-drawer"]');
    assert.ok(ebrDrawer);
    assert.ok(ebrDrawer.textContent.includes('Mix 15 min'));
    assert.ok(ebrDrawer.textContent.includes('QC: 1P / 0F / 0 Pending'));
    // Opened from Quality Dossier -> must not render circular Open Quality Dossier button
    assert.equal(ebrDrawer.textContent.includes('Open Quality Dossier'), false);

    // 4. OPERATOR deep-link to #/quality?batchId=77 NEVER shows unauthorized Recipe BOM link
    await dom.unmount();
    const opDom = await createDomEnvironment('http://localhost:5173/#/quality?batchId=77');
    calledUrls.length = 0;

    try {
      await opDom.render(
        React.createElement(
          AuthContext.Provider,
          { value: makeAuthValue('OPERATOR') },
          React.createElement(App)
        )
      );
      await opDom.flush();
      await opDom.flush();

      assert.ok(opDom.container.textContent.includes('Batch Quality Dossier'));
      assert.ok(opDom.container.textContent.includes('Open Order'));
      assert.equal(opDom.container.textContent.includes('Inspect Recipe'), false);
      assert.equal(calledUrls.some((u) => u.endsWith('/recipes')), false);
    } finally {
      await opDom.unmount();
    }
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});
