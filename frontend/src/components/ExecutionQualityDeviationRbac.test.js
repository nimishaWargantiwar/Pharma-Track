import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { createDomEnvironment } from '../../scripts/dom-harness.mjs';

function makeAuthValue(role, overrides = {}) {
  const usernameMap = {
    ADMIN: 'admin',
    PRODUCTION_SUPERVISOR: 'prod.sup',
    OPERATOR: 'operator.floor',
    QC_ANALYST: 'qc.analyst',
    QA_REVIEWER: 'qa.reviewer',
  };
  const nameMap = {
    ADMIN: 'System Admin',
    PRODUCTION_SUPERVISOR: 'Rohan Mehta',
    OPERATOR: 'Vikram Joshi',
    QC_ANALYST: 'Sneha Kulkarni',
    QA_REVIEWER: 'Dr. Asha Sen',
  };
  const username = usernameMap[role] || 'user';
  const fullName = nameMap[role] || 'User';
  return {
    user: { id: 1, username, fullName, role, enabled: true },
    role,
    token: 'test-jwt-token',
    actorDisplay: `${fullName} (@${username})`,
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

test('OrderManagement & OrderDetailsModal separate supervisor planning from operator batch start/complete', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: OrderManagement } = await import('./OrderManagement.jsx');
  const { default: OrderDetailsModal } = await import('./OrderDetailsModal.jsx');

  const plannedOrder = {
    id: 11,
    orderNumber: 'MO-2026-0011',
    status: 'PLANNED',
    productName: 'Paracetamol 500mg',
    productCode: 'PRD-001',
    recipeCode: 'RCP-001',
    recipeVersion: 1,
    targetBatchCount: 1,
    plannedQuantity: 1000,
    unit: 'Tablets',
    batches: [
      {
        id: 211,
        batchNumber: 'BAT-2026-0011-01',
        batchSize: 1000,
        batchUnit: 'Tablets',
        status: 'PLANNED',
      },
    ],
  };

  const releasedOrder = {
    id: 12,
    orderNumber: 'MO-2026-0012',
    status: 'RELEASED',
    productName: 'Paracetamol 500mg',
    productCode: 'PRD-001',
    recipeCode: 'RCP-001',
    recipeVersion: 1,
    targetBatchCount: 2,
    plannedQuantity: 2000,
    unit: 'Tablets',
    batches: [
      {
        id: 212,
        batchNumber: 'BAT-2026-0012-01',
        batchSize: 1000,
        batchUnit: 'Tablets',
        status: 'PLANNED',
      },
      {
        id: 213,
        batchNumber: 'BAT-2026-0012-02',
        batchSize: 1000,
        batchUnit: 'Tablets',
        status: 'IN_PROGRESS',
      },
    ],
  };

  const prevFetch = globalThis.fetch;
  let currentModalOrder = plannedOrder;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/orders')) return jsonResponse([plannedOrder, releasedOrder], 200, url);
    if (url.includes('/orders/11')) return jsonResponse(plannedOrder, 200, url);
    if (url.includes('/orders/12')) return jsonResponse(releasedOrder, 200, url);
    return jsonResponse([], 200, url);
  };

  try {
    // 1. OrderManagement Create button gating
    for (const [role, expectedCreate] of [
      ['ADMIN', true],
      ['PRODUCTION_SUPERVISOR', true],
      ['OPERATOR', false],
      ['QC_ANALYST', false],
      ['QA_REVIEWER', false],
    ]) {
      await dom.render(
        React.createElement(
          AuthContext.Provider,
          { value: makeAuthValue(role) },
          React.createElement(OrderManagement)
        )
      );
      await dom.flush();
      assert.equal(
        dom.container.textContent.includes('Create Manufacturing Order'),
        expectedCreate,
        `Create Manufacturing Order visibility mismatch for ${role}`
      );
    }

    // 2. OrderDetailsModal on PLANNED order: PRODUCTION_SUPERVISOR sees Release Order, Cancel Order, Cancel Batch
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('PRODUCTION_SUPERVISOR') },
        React.createElement(OrderDetailsModal, {
          isOpen: true,
          orderId: currentModalOrder.id,
          onClose: () => {},
        })
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Release Order'));
    assert.ok(dom.container.textContent.includes('Cancel Order'));
    assert.ok(dom.container.textContent.includes('Cancel Batch'));
    assert.equal(dom.container.textContent.includes('Start Batch'), false);

    // 3. OrderDetailsModal on RELEASED order: OPERATOR sees Start Batch + Complete Batch + Execution History, NO Release/Cancel Order
    currentModalOrder = releasedOrder;
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(OrderDetailsModal, {
          isOpen: true,
          orderId: releasedOrder.id,
          onClose: () => {},
        })
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Start Batch'));
    assert.ok(dom.container.textContent.includes('Complete Batch'));
    assert.ok(dom.container.textContent.includes('Execution History'));
    assert.equal(dom.container.textContent.includes('Release Order'), false);
    assert.equal(dom.container.textContent.includes('Cancel Order'), false);

    // 4. QA_REVIEWER on RELEASED order sees Execution History ONLY
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(OrderDetailsModal, {
          isOpen: true,
          orderId: releasedOrder.id,
          onClose: () => {},
        })
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Execution History'));
    assert.equal(dom.container.textContent.includes('Start Batch'), false);
    assert.equal(dom.container.textContent.includes('Complete Batch'), false);
    assert.equal(dom.container.textContent.includes('Release Order'), false);
    assert.equal(dom.container.textContent.includes('Cancel Order'), false);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('ProductionExecution & CompleteExecutionStepModal enforce OPERATOR-only execution and omit executedBy', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: ProductionExecution } = await import('./ProductionExecution.jsx');

  const queueData = [
    {
      batchId: 101,
      batchNumber: 'BAT-2026-0101-01',
      orderNumber: 'MO-2026-0101',
      productName: 'Paracetamol 500mg',
      recipeCode: 'RCP-001',
      recipeVersion: 1,
      batchSize: 1000,
      batchUnit: 'Tablets',
      batchStatus: 'IN_PROGRESS',
    },
  ];

  const legacyWorkspace = {
    batchId: 101,
    batchNumber: 'BAT-2026-0101-01',
    orderNumber: 'MO-2026-0101',
    productName: 'Paracetamol 500mg',
    recipeCode: 'RCP-001',
    recipeVersion: 1,
    batchSize: 1000,
    batchUnit: 'Tablets',
    batchStatus: 'IN_PROGRESS',
    executionSteps: [],
  };

  const activeWorkspace = {
    ...legacyWorkspace,
    executionSteps: [
      {
        id: 501,
        stepNumber: 1,
        operationName: 'Dry Mixing',
        instructions: 'Blend for 20 min',
        status: 'IN_PROGRESS',
      },
      {
        id: 502,
        stepNumber: 2,
        operationName: 'Compression',
        instructions: 'Compress tablets',
        status: 'PENDING',
      },
    ],
  };

  const prevFetch = globalThis.fetch;
  const requests = [];
  let workspacePayload = legacyWorkspace;

  globalThis.fetch = async (url, options = {}) => {
    const method = options.method || 'GET';
    const body = options.body ? JSON.parse(options.body) : null;
    requests.push({ url, method, body });

    if (url.endsWith('/batches/execution')) {
      return jsonResponse(queueData, 200, url);
    }
    if (url.endsWith('/batches/101/steps')) {
      return jsonResponse(workspacePayload, 200, url);
    }
    if (url.endsWith('/batches/101/steps/501/complete')) {
      return jsonResponse({ ...activeWorkspace.executionSteps[0], status: 'COMPLETED' }, 200, url);
    }
    return jsonResponse({}, 200, url);
  };

  try {
    // 1. PRODUCTION_SUPERVISOR monitors read-only & NEVER triggers /steps/initialize even when steps=[]
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('PRODUCTION_SUPERVISOR') },
        React.createElement(ProductionExecution)
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Read-Only Monitoring'));
    const viewBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('View Workspace')
    );
    assert.ok(viewBtn);
    await dom.click(viewBtn);
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Read-Only Monitoring Mode (Role Restricted)'));
    assert.equal(
      requests.some((r) => r.url.includes('/steps/initialize')),
      false,
      'Non-operator must never call POST /steps/initialize'
    );

    // 2. OPERATOR opens active workspace & completes step without executedBy input/payload
    workspacePayload = activeWorkspace;
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(ProductionExecution)
      )
    );
    await dom.flush();

    const completeStepBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Complete Step')
    );
    assert.ok(completeStepBtn);
    await dom.click(completeStepBtn);
    await dom.flush();

    // Authenticated attribution shown, no spoofable executedBy input
    assert.ok(dom.container.textContent.includes('Vikram Joshi (@operator.floor)'));
    assert.equal(dom.container.querySelector('input[id*="executed"]'), null);

    await dom.changeInput(dom.container.querySelector('#exec-step-actual-value'), '45.2 C verified');
    await dom.changeInput(dom.container.querySelector('#exec-step-observations'), 'Within limits');
    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();

    const completeCall = requests.find((r) => r.url.endsWith('/batches/101/steps/501/complete'));
    assert.ok(completeCall);
    assert.equal(completeCall.method, 'PATCH');
    assert.deepEqual(completeCall.body, {
      actualValue: '45.2 C verified',
      observations: 'Within limits',
    });
    assert.equal('executedBy' in completeCall.body, false);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('QualityManagement, QualityBatchDossier, QualityCheckModal, and QaDispositionModal enforce RBAC and remove actor spoofing', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: QualityManagement } = await import('./QualityManagement.jsx');
  const { default: QualityBatchDossier } = await import('./QualityBatchDossier.jsx');
  const { default: QualityCheckModal } = await import('./QualityCheckModal.jsx');
  const { default: QaDispositionModal } = await import('./QaDispositionModal.jsx');

  const sampleDossier = {
    batchId: 77,
    batchNumber: 'BAT-2026-0077-01',
    batchStatus: 'COMPLETED',
    qaStatus: 'PENDING_QA',
    orderNumber: 'MO-2026-0077',
    productName: 'Paracetamol 500mg',
    productCode: 'PRD-001',
    recipeCode: 'RCP-001',
    recipeVersion: 1,
    batchSize: 1000,
    batchUnit: 'Tablets',
    actualYield: 992,
    qualityChecks: [
      {
        id: 901,
        testName: 'Assay (HPLC)',
        specification: '95.0% - 105.0%',
        measuredValue: '99.5',
        unit: '%',
        result: 'PASS',
        required: true,
        inspectorName: 'qc.analyst',
      },
      {
        id: 902,
        testName: 'Moisture',
        specification: '< 2.0%',
        measuredValue: '2.6',
        unit: '%',
        result: 'FAIL',
        required: false,
        inspectorName: 'qc.analyst',
      },
    ],
    qaReviews: [],
    deviations: [],
    auditLogs: [],
  };

  const prevFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options = {}) => {
    const method = options.method || 'GET';
    const body = options.body ? JSON.parse(options.body) : null;
    calls.push({ url, method, body });

    if (url.endsWith('/quality/queue')) return jsonResponse([sampleDossier], 200, url);
    if (url.endsWith('/quality/audit-logs')) return jsonResponse([], 200, url);
    if (url.endsWith('/quality/batches/77')) return jsonResponse(sampleDossier, 200, url);
    if (url.endsWith('/quality/batches/77/checks')) {
      return jsonResponse({ id: 903, testName: 'pH', result: 'PASS' }, 201, url);
    }
    if (url.endsWith('/quality/checks/901/correct')) {
      return jsonResponse({ id: 901, testName: 'Assay (HPLC)', result: 'PASS' }, 200, url);
    }
    if (url.endsWith('/quality/batches/77/disposition')) {
      return jsonResponse({ ...sampleDossier, qaStatus: body.qaStatus }, 200, url);
    }
    return jsonResponse([], 200, url);
  };

  try {
    // 1. Audit Trail tab hidden for QC_ANALYST and never calls /audit-logs
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QC_ANALYST') },
        React.createElement(QualityManagement)
      )
    );
    await dom.flush();
    assert.equal(dom.container.textContent.includes('Audit Trail'), false);
    assert.equal(calls.some((c) => c.url.includes('/quality/audit-logs')), false);

    // 2. Audit Trail tab visible for QA_REVIEWER and loads /audit-logs when selected
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(QualityManagement)
      )
    );
    await dom.flush();
    const auditTab = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Audit Trail')
    );
    assert.ok(auditTab);
    await dom.click(auditTab);
    await dom.flush();
    assert.ok(calls.some((c) => c.url.includes('/quality/audit-logs')));

    // 3. QualityBatchDossier action gating across QC_ANALYST, QA_REVIEWER, ADMIN
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QC_ANALYST') },
        React.createElement(QualityBatchDossier, { batchId: 77, onBack: () => {} })
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Add QC Check'));
    assert.ok(dom.container.textContent.includes('Audited Correction'));
    assert.ok(dom.container.textContent.includes('Report Deviation'));
    assert.ok(dom.container.textContent.includes('QA Disposition Requires QA Reviewer'));
    assert.equal(dom.container.textContent.includes('Place On Hold'), false);

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(QualityBatchDossier, { batchId: 77, onBack: () => {} })
      )
    );
    await dom.flush();
    assert.equal(dom.container.textContent.includes('Add QC Check'), false);
    assert.equal(dom.container.textContent.includes('Audited Correction'), false);
    assert.ok(dom.container.textContent.includes('Place On Hold'));
    assert.ok(dom.container.textContent.includes('Reject Batch'));
    assert.ok(dom.container.textContent.includes('Report Deviation'));

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(QualityBatchDossier, { batchId: 77, onBack: () => {} })
      )
    );
    await dom.flush();
    assert.equal(dom.container.textContent.includes('Add QC Check'), false);
    assert.equal(dom.container.textContent.includes('Report Deviation'), false);
    assert.ok(dom.container.textContent.includes('QA Disposition Requires QA Reviewer'));

    // 4. QualityCheckModal (create mode) omits inspectorName input & payload
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QC_ANALYST') },
        React.createElement(QualityCheckModal, {
          isOpen: true,
          mode: 'create',
          batchId: 77,
          batchNumber: 'BAT-2026-0077-01',
          onClose: () => {},
        })
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Sneha Kulkarni (@qc.analyst)'));
    await dom.changeInput(dom.container.querySelector('#qc-test-name'), 'pH');
    await dom.changeInput(dom.container.querySelector('#qc-specification'), '6.0 - 7.5');
    await dom.changeInput(dom.container.querySelector('#qc-measured-value'), '6.8');
    await dom.changeInput(dom.container.querySelector('#qc-unit'), 'pH');
    await dom.changeInput(dom.container.querySelector('#qc-result'), 'PASS');
    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();

    const qcCreateCall = calls.find((c) => c.url.endsWith('/quality/batches/77/checks'));
    assert.ok(qcCreateCall);
    assert.equal('inspectorName' in qcCreateCall.body, false);

    // 5. QualityCheckModal (correct mode) omits correctedBy input & payload
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QC_ANALYST') },
        React.createElement(QualityCheckModal, {
          isOpen: true,
          mode: 'correct',
          batchId: 77,
          batchNumber: 'BAT-2026-0077-01',
          existingCheck: sampleDossier.qualityChecks[0],
          onClose: () => {},
        })
      )
    );
    await dom.flush();
    await dom.changeInput(dom.container.querySelector('#qc-correction-reason'), 'Transcription typo');
    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();

    const qcCorrectCall = calls.find((c) => c.url.endsWith('/quality/checks/901/correct'));
    assert.ok(qcCorrectCall);
    assert.equal('correctedBy' in qcCorrectCall.body, false);

    // 6. QaDispositionModal omits reviewerName input & payload
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(QaDispositionModal, {
          isOpen: true,
          dossier: sampleDossier,
          initialDecision: 'ON_HOLD',
          onClose: () => {},
        })
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Dr. Asha Sen (@qa.reviewer)'));
    await dom.changeInput(dom.container.querySelector('#qa-rationale'), 'Pending moisture review');
    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();

    const dispCall = calls.find((c) => c.url.endsWith('/quality/batches/77/disposition'));
    assert.ok(dispCall);
    assert.deepEqual(dispCall.body, {
      qaStatus: 'ON_HOLD',
      rationale: 'Pending moisture review',
    });
    assert.equal('reviewerName' in dispCall.body, false);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('DeviationManagement, ReportDeviationModal, and DeviationWorkspace enforce RBAC and keep CAPA ownerName editable', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: DeviationManagement } = await import('./DeviationManagement.jsx');
  const { default: ReportDeviationModal } = await import('./ReportDeviationModal.jsx');
  const { default: DeviationWorkspace } = await import('./DeviationWorkspace.jsx');

  const futureDate = '2099-12-31';
  const baseDeviation = {
    id: 9,
    deviationNumber: 'DEV-2026-0009',
    batchId: 50,
    batchNumber: 'BAT-50',
    title: 'Tablet weight drift',
    category: 'IN_PROCESS_CONTROL',
    severity: 'MAJOR',
    priority: 'HIGH',
    status: 'OPEN',
    reportedBy: 'operator.floor',
    reportedAt: '2026-11-10T10:00:00',
    capaActions: [],
    auditLogs: [],
  };

  let currentDeviation = baseDeviation;
  const requests = [];
  const prevFetch = globalThis.fetch;

  globalThis.fetch = async (url, options = {}) => {
    const method = options.method || 'GET';
    const body = options.body ? JSON.parse(options.body) : null;
    requests.push({ url, method, body });

    if (url.includes('/quality/batches/50/linkable-records')) {
      return jsonResponse({ qualityChecks: [], executionSteps: [], auditLogs: [] }, 200, url);
    }
    if (url.endsWith('/quality/deviations') && method === 'POST') {
      return jsonResponse({ ...baseDeviation, title: body.title }, 201, url);
    }
    if (url.includes('/quality/deviations/9/start-investigation')) {
      return jsonResponse({ ...currentDeviation, status: 'UNDER_INVESTIGATION' }, 200, url);
    }
    if (url.includes('/quality/deviations/9/capas') && method === 'POST') {
      return jsonResponse(
        {
          ...currentDeviation,
          capaActions: [
            {
              id: 801,
              capaNumber: 'CAPA-001',
              capaType: body.capaType,
              title: body.title,
              actionPlan: body.actionPlan,
              ownerName: body.ownerName,
              dueDate: body.dueDate,
              status: 'OPEN',
              createdBy: 'prod.sup',
            },
          ],
        },
        201,
        url
      );
    }
    if (url.includes('/quality/deviations/9')) {
      return jsonResponse(currentDeviation, 200, url);
    }
    return jsonResponse([], 200, url);
  };

  try {
    // 1. DeviationManagement hides Report Deviation from ADMIN and shows it to OPERATOR
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(DeviationManagement)
      )
    );
    await dom.flush();
    assert.equal(dom.container.textContent.includes('Report Deviation'), false);

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(DeviationManagement)
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Report Deviation'));

    // 2. ReportDeviationModal omits reportedBy input & payload
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(ReportDeviationModal, {
          isOpen: true,
          lockBatchSelection: true,
          prefillData: { batchId: 50, batchNumber: 'BAT-50' },
          onClose: () => {},
        })
      )
    );
    await dom.flush();
    assert.equal(
      dom.container.querySelector('[data-testid="authenticated-reporter-display"]')?.textContent,
      'Vikram Joshi (@operator.floor)'
    );
    await dom.changeInput(dom.container.querySelector('#dev-title'), 'Punch sticking observed');
    await dom.changeInput(dom.container.querySelector('#dev-category'), 'EQUIPMENT_FAILURE');
    await dom.changeInput(dom.container.querySelector('#dev-description'), 'Upper punch binding');
    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();

    const devCreateCall = requests.find(
      (r) => r.url.endsWith('/quality/deviations') && r.method === 'POST'
    );
    assert.ok(devCreateCall);
    assert.equal('reportedBy' in devCreateCall.body, false);

    // 3. DeviationWorkspace OPEN: QC_ANALYST investigates without investigatedBy; OPERATOR cannot
    currentDeviation = { ...baseDeviation, status: 'OPEN' };
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(DeviationWorkspace, { key: 'op-open', deviationId: 9, onBack: () => {} })
      )
    );
    await dom.flush();
    assert.equal(dom.container.textContent.includes('Start Investigation'), false);

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QC_ANALYST') },
        React.createElement(DeviationWorkspace, { key: 'qc-open', deviationId: 9, onBack: () => {} })
      )
    );
    await dom.flush();
    assert.equal(
      dom.container.querySelector('[data-testid="authenticated-investigator-display"]')?.textContent,
      'Sneha Kulkarni (@qc.analyst)'
    );
    await dom.changeInput(dom.container.querySelector('#inv-summary'), 'Reviewed compression logs');
    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();

    const invCall = requests.find((r) => r.url.includes('/start-investigation'));
    assert.ok(invCall);
    assert.deepEqual(invCall.body, { investigationSummary: 'Reviewed compression logs' });

    // 4. DeviationWorkspace ROOT_CAUSE_IDENTIFIED:
    //    PRODUCTION_SUPERVISOR can create CAPA (keeping ownerName editable, omitting createdBy)
    //    and CANNOT click Enter CAPA In Progress (QA_REVIEWER only)
    currentDeviation = {
      ...baseDeviation,
      status: 'ROOT_CAUSE_IDENTIFIED',
      investigatedBy: 'qc.analyst',
      investigationSummary: 'Reviewed compression logs',
      rootCauseIdentifiedBy: 'qc.analyst',
      rootCauseCategory: 'Equipment',
      rootCauseAnalysis: 'Worn cam track',
      impactAssessment: 'No downstream lots impacted',
      capaActions: [
        {
          id: 800,
          capaNumber: 'CAPA-000',
          capaType: 'CORRECTIVE',
          title: 'Inspect die table',
          actionPlan: 'Replace cam track',
          ownerName: 'Maintenance Lead',
          dueDate: futureDate,
          status: 'OPEN',
          createdBy: 'prod.sup',
        },
        {
          id: 802,
          capaNumber: 'CAPA-002',
          capaType: 'PREVENTIVE',
          title: 'Update PM schedule',
          actionPlan: 'Add monthly inspection',
          ownerName: 'QA Eng',
          dueDate: futureDate,
          status: 'OPEN',
          createdBy: 'prod.sup',
        },
      ],
    };

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('PRODUCTION_SUPERVISOR') },
        React.createElement(DeviationWorkspace, { key: 'sup-rca', deviationId: 9, onBack: () => {} })
      )
    );
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Awaiting QA Reviewer CAPA Phase Start'));
    assert.equal(dom.container.textContent.includes('Enter CAPA In Progress'), false);
    assert.equal(
      [...dom.container.querySelectorAll('button')].some((b) => b.textContent.trim() === 'Cancel'),
      false,
      'Non-QA_REVIEWER must not see CAPA Cancel button'
    );

    const addCapaBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Add CAPA Action')
    );
    assert.ok(addCapaBtn);
    await dom.click(addCapaBtn);

    // ownerName (#capa-owner) MUST remain editable while createdBy is server-derived display
    const ownerInput = dom.container.querySelector('#capa-owner');
    assert.ok(ownerInput);
    assert.equal(
      dom.container.querySelector('[data-testid="authenticated-capa-creator-display"]')?.textContent,
      'Rohan Mehta (@prod.sup)'
    );
    await dom.changeInput(ownerInput, 'M. Deshmukh');
    await dom.changeInput(dom.container.querySelector('#capa-title'), 'Calibrate turret');
    await dom.changeInput(dom.container.querySelector('#capa-due-date'), futureDate);
    await dom.changeInput(dom.container.querySelector('#capa-plan'), 'Verify calibration cert');
    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();

    const capaCall = requests.find(
      (r) => r.url.endsWith('/quality/deviations/9/capas') && r.method === 'POST'
    );
    assert.ok(capaCall);
    assert.deepEqual(capaCall.body, {
      capaType: 'CORRECTIVE',
      title: 'Calibrate turret',
      actionPlan: 'Verify calibration cert',
      ownerName: 'M. Deshmukh',
      dueDate: futureDate,
    });
    assert.equal('createdBy' in capaCall.body, false);

    // QA_REVIEWER DOES see Enter CAPA In Progress & CAPA Cancel buttons
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(DeviationWorkspace, { key: 'qa-rca', deviationId: 9, onBack: () => {} })
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Enter CAPA In Progress'));
    assert.ok(
      [...dom.container.querySelectorAll('button')].some((b) => b.textContent.trim() === 'Cancel')
    );

    // 5. DeviationWorkspace PENDING_VERIFICATION: QA_REVIEWER sees Verify & Close + Reopen CAPA; Supervisor does not
    currentDeviation = {
      ...currentDeviation,
      status: 'PENDING_VERIFICATION',
      eligibleForClosure: true,
    };
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('PRODUCTION_SUPERVISOR') },
        React.createElement(DeviationWorkspace, { key: 'sup-verif', deviationId: 9, onBack: () => {} })
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Awaiting QA Reviewer Verification'));
    assert.equal(dom.container.textContent.includes('Verify & Close Deviation'), false);
    assert.equal(dom.container.textContent.includes('Reopen CAPA Actions'), false);

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(DeviationWorkspace, { key: 'qa-verif', deviationId: 9, onBack: () => {} })
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Verify & Close Deviation'));
    assert.ok(dom.container.textContent.includes('Reopen CAPA Actions'));
    assert.equal(
      dom.container.querySelector('[data-testid="authenticated-qa-closer-display"]')?.textContent,
      'Dr. Asha Sen (@qa.reviewer)'
    );
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('DeviationWorkspace executes complete lifecycle (RCA -> start CAPA -> start/complete/cancel CAPA -> verification -> reopen/close) without actor payload spoofing', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: DeviationWorkspace } = await import('./DeviationWorkspace.jsx');

  const findClosestForm = (el) => {
    let cursor = el;
    while (cursor) {
      if (cursor.localName === 'form') return cursor;
      cursor = cursor.parentNode;
    }
    return null;
  };

  const futureDate = '2099-12-31';
  let currentDeviation = {
    id: 19,
    deviationNumber: 'DEV-2026-0019',
    batchId: 51,
    batchNumber: 'BAT-51',
    batchStatus: 'IN_PROGRESS',
    title: 'Blend uniformity OOS',
    description: 'Potency RSD exceeded specification',
    category: 'QC_OOS_FAILURE',
    severity: 'CRITICAL',
    priority: 'HIGH',
    status: 'UNDER_INVESTIGATION',
    reportedBy: 'operator.floor',
    reportedAt: '2026-11-10T09:00:00',
    investigatedBy: 'qc.analyst',
    investigationSummary: 'Sampled all blender locations',
    investigationStartedAt: '2026-11-10T10:00:00',
    openCapaCount: 0,
    inProgressCapaCount: 0,
    completedCapaCount: 0,
    cancelledCapaCount: 0,
    overdueCapaCount: 0,
    eligibleForClosure: false,
    capaActions: [],
    auditLogs: [],
  };

  const calls = [];
  const notifications = [];
  const prevFetch = globalThis.fetch;

  const recountCapas = (dev) => {
    const list = dev.capaActions || [];
    return {
      ...dev,
      openCapaCount: list.filter((c) => c.status === 'OPEN').length,
      inProgressCapaCount: list.filter((c) => c.status === 'IN_PROGRESS').length,
      completedCapaCount: list.filter((c) => c.status === 'COMPLETED').length,
      cancelledCapaCount: list.filter((c) => c.status === 'CANCELLED').length,
    };
  };

  globalThis.fetch = async (url, options = {}) => {
    const method = options.method || 'GET';
    const body = options.body ? JSON.parse(options.body) : null;
    calls.push({ url, method, body });

    if (url.endsWith('/quality/deviations/19/record-root-cause') && method === 'POST') {
      currentDeviation = {
        ...currentDeviation,
        status: 'ROOT_CAUSE_IDENTIFIED',
        rootCauseIdentifiedBy: 'qc.analyst',
        rootCauseCategory: body.rootCauseCategory,
        rootCauseAnalysis: body.rootCauseAnalysis,
        impactAssessment: body.impactAssessment,
        investigationSummary: body.investigationSummary || currentDeviation.investigationSummary,
        capaActions: [
          {
            id: 901,
            capaNumber: 'CAPA-101',
            capaType: 'CORRECTIVE',
            title: 'Recalibrate blender speed sensor',
            actionPlan: 'Verify tachometer calibration',
            ownerName: 'Engineering Maintenance',
            dueDate: futureDate,
            status: 'OPEN',
            createdBy: 'prod.sup',
          },
          {
            id: 902,
            capaNumber: 'CAPA-102',
            capaType: 'PREVENTIVE',
            title: 'Revise SOP-BLD-04',
            actionPlan: 'Add intermediate blend speed check',
            ownerName: 'QA Documentation',
            dueDate: futureDate,
            status: 'OPEN',
            createdBy: 'prod.sup',
          },
        ],
      };
      currentDeviation = recountCapas(currentDeviation);
      return jsonResponse(currentDeviation, 200, url);
    }

    if (url.endsWith('/quality/deviations/19/start-capa') && method === 'POST') {
      currentDeviation = {
        ...currentDeviation,
        status: 'CAPA_IN_PROGRESS',
        capaPlanSummary: body.capaPlanSummary,
      };
      return jsonResponse(currentDeviation, 200, url);
    }

    if (url.endsWith('/quality/capas/901/start') && method === 'POST') {
      currentDeviation = recountCapas({
        ...currentDeviation,
        capaActions: currentDeviation.capaActions.map((c) =>
          c.id === 901 ? { ...c, status: 'IN_PROGRESS' } : c
        ),
      });
      return jsonResponse(currentDeviation, 200, url);
    }

    if (url.endsWith('/quality/capas/901/complete') && method === 'POST') {
      currentDeviation = recountCapas({
        ...currentDeviation,
        capaActions: currentDeviation.capaActions.map((c) =>
          c.id === 901
            ? {
                ...c,
                status: 'COMPLETED',
                completedBy: 'qc.analyst',
                completedAt: '2026-11-11T12:00:00',
                completionEvidence: body.completionEvidence,
              }
            : c
        ),
      });
      return jsonResponse(currentDeviation, 200, url);
    }

    if (url.endsWith('/quality/capas/902/cancel') && method === 'POST') {
      currentDeviation = recountCapas({
        ...currentDeviation,
        capaActions: currentDeviation.capaActions.map((c) =>
          c.id === 902
            ? { ...c, status: 'CANCELLED', cancellationReason: body.cancellationReason }
            : c
        ),
      });
      return jsonResponse(currentDeviation, 200, url);
    }

    if (url.endsWith('/quality/deviations/19/submit-verification') && method === 'POST') {
      currentDeviation = {
        ...currentDeviation,
        status: 'PENDING_VERIFICATION',
        eligibleForClosure: true,
      };
      return jsonResponse(currentDeviation, 200, url);
    }

    if (url.endsWith('/quality/deviations/19/reopen-capa') && method === 'POST') {
      currentDeviation = {
        ...currentDeviation,
        status: 'CAPA_IN_PROGRESS',
        eligibleForClosure: false,
      };
      return jsonResponse(currentDeviation, 200, url);
    }

    if (url.endsWith('/quality/deviations/19/close') && method === 'POST') {
      currentDeviation = {
        ...currentDeviation,
        status: 'CLOSED',
        verifiedBy: 'qa.reviewer',
        closedBy: 'qa.reviewer',
        closedAt: '2026-11-11T14:00:00',
        verificationNotes: body.verificationNotes,
      };
      return jsonResponse(currentDeviation, 200, url);
    }

    if (url.includes('/quality/deviations/19')) {
      return jsonResponse(currentDeviation, 200, url);
    }
    return jsonResponse({}, 200, url);
  };

  try {
    // 1. UNDER_INVESTIGATION: OPERATOR blocked, QC_ANALYST records root cause without rootCauseIdentifiedBy
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(DeviationWorkspace, {
          key: 'op-rca',
          deviationId: 19,
          onBack: () => {},
          onNotify: (n) => notifications.push(n),
        })
      )
    );
    await dom.flush();
    assert.equal(dom.container.querySelector('#rca-category'), null);
    assert.ok(
      dom.container.textContent.includes(
        'Recording root cause & impact requires Production Supervisor, QC Analyst, or QA Reviewer access'
      )
    );

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QC_ANALYST') },
        React.createElement(DeviationWorkspace, {
          key: 'qc-rca',
          deviationId: 19,
          onBack: () => {},
          onNotify: (n) => notifications.push(n),
        })
      )
    );
    await dom.flush();
    assert.equal(
      dom.container.querySelector('[data-testid="authenticated-rca-actor-display"]')?.textContent,
      'Sneha Kulkarni (@qc.analyst)'
    );
    await dom.changeInput(
      dom.container.querySelector('#rca-category'),
      'Equipment / Instrument Calibration'
    );
    await dom.changeInput(
      dom.container.querySelector('#rca-analysis'),
      'Tachometer sensor drifted 12% low'
    );
    await dom.changeInput(
      dom.container.querySelector('#rca-impact'),
      'Only sub-lot B blend homogenisation affected'
    );
    await dom.changeInput(
      dom.container.querySelector('#rca-inv-summary'),
      'Sampled all blender locations; sensor check failed'
    );
    await dom.submit(findClosestForm(dom.container.querySelector('#rca-category')));
    await dom.flush();

    const rcaCall = calls.find((c) => c.url.endsWith('/record-root-cause'));
    assert.ok(rcaCall);
    assert.deepEqual(rcaCall.body, {
      rootCauseCategory: 'Equipment / Instrument Calibration',
      rootCauseAnalysis: 'Tachometer sensor drifted 12% low',
      impactAssessment: 'Only sub-lot B blend homogenisation affected',
      investigationSummary: 'Sampled all blender locations; sensor check failed',
    });
    assert.equal('rootCauseIdentifiedBy' in rcaCall.body, false);
    assert.equal('actorName' in rcaCall.body, false);

    // 2. ROOT_CAUSE_IDENTIFIED: QA_REVIEWER enters CAPA_IN_PROGRESS without actorName
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(DeviationWorkspace, {
          key: 'qa-start-capa',
          deviationId: 19,
          onBack: () => {},
          onNotify: (n) => notifications.push(n),
        })
      )
    );
    await dom.flush();
    assert.equal(
      dom.container.querySelector('[data-testid="authenticated-start-capa-actor-display"]')
        ?.textContent,
      'Dr. Asha Sen (@qa.reviewer)'
    );
    await dom.changeInput(
      dom.container.querySelector('#start-capa-plan'),
      'Execute calibration CAPA and retire duplicate SOP action'
    );
    await dom.submit(findClosestForm(dom.container.querySelector('#start-capa-plan')));
    await dom.flush();

    const startCapaCall = calls.find((c) => c.url.endsWith('/start-capa'));
    assert.ok(startCapaCall);
    assert.deepEqual(startCapaCall.body, {
      capaPlanSummary: 'Execute calibration CAPA and retire duplicate SOP action',
    });
    assert.equal('actorName' in startCapaCall.body, false);

    // 3. CAPA_IN_PROGRESS: OPERATOR/ADMIN cannot see Start/Complete/Submit; PRODUCTION_SUPERVISOR starts CAPA-101
    for (const blockedRole of ['OPERATOR', 'ADMIN']) {
      await dom.render(
        React.createElement(
          AuthContext.Provider,
          { value: makeAuthValue(blockedRole) },
          React.createElement(DeviationWorkspace, {
            key: `blocked-${blockedRole}`,
            deviationId: 19,
            onBack: () => {},
          })
        )
      );
      await dom.flush();
      const buttons = [...dom.container.querySelectorAll('button')].map((b) =>
        b.textContent.trim()
      );
      assert.equal(buttons.includes('Start'), false, `${blockedRole} must not see CAPA Start`);
      assert.equal(
        buttons.includes('Complete'),
        false,
        `${blockedRole} must not see CAPA Complete`
      );
      assert.equal(buttons.includes('Cancel'), false, `${blockedRole} must not see CAPA Cancel`);
      assert.equal(dom.container.querySelector('#submit-verif-notes'), null);
    }

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('PRODUCTION_SUPERVISOR') },
        React.createElement(DeviationWorkspace, {
          key: 'sup-exec-capa',
          deviationId: 19,
          onBack: () => {},
        })
      )
    );
    await dom.flush();

    const startBtn = [...dom.container.querySelectorAll('button')].find(
      (b) => b.textContent.trim() === 'Start'
    );
    assert.ok(startBtn);
    await dom.click(startBtn);
    const startNoteInput = dom.container.querySelector(
      'input[placeholder="Optional kickoff note..."]'
    );
    assert.ok(startNoteInput);
    await dom.changeInput(startNoteInput, 'Calibration work order WO-88 issued');
    await dom.submit(findClosestForm(startNoteInput));
    await dom.flush();

    const capaStartCall = calls.find((c) => c.url.endsWith('/quality/capas/901/start'));
    assert.ok(capaStartCall);
    assert.deepEqual(capaStartCall.body, { notes: 'Calibration work order WO-88 issued' });
    assert.equal('actorName' in capaStartCall.body, false);

    // 4. QC_ANALYST completes CAPA-101 with evidence (omitting completedBy/actorName)
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QC_ANALYST') },
        React.createElement(DeviationWorkspace, {
          key: 'qc-complete-capa',
          deviationId: 19,
          onBack: () => {},
        })
      )
    );
    await dom.flush();
    const completeBtn = [...dom.container.querySelectorAll('button')].find(
      (b) => b.textContent.trim() === 'Complete'
    );
    assert.ok(completeBtn);
    await dom.click(completeBtn);
    const evidenceInput = dom.container.querySelector(
      'input[placeholder="Calibration cert #, training record ID, verification ref..."]'
    );
    assert.ok(evidenceInput);
    await dom.changeInput(evidenceInput, 'CAL-CERT-2026-771 passed +-0.1%');
    await dom.submit(findClosestForm(evidenceInput));
    await dom.flush();

    const capaCompleteCall = calls.find((c) => c.url.endsWith('/quality/capas/901/complete'));
    assert.ok(capaCompleteCall);
    assert.deepEqual(capaCompleteCall.body, {
      completionEvidence: 'CAL-CERT-2026-771 passed +-0.1%',
    });
    assert.equal('completedBy' in capaCompleteCall.body, false);
    assert.equal('actorName' in capaCompleteCall.body, false);

    // 5. QA_REVIEWER cancels CAPA-102, then submits deviation for QA verification
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(DeviationWorkspace, {
          key: 'qa-cancel-submit',
          deviationId: 19,
          onBack: () => {},
        })
      )
    );
    await dom.flush();
    const cancelBtn = [...dom.container.querySelectorAll('button')].find(
      (b) => b.textContent.trim() === 'Cancel'
    );
    assert.ok(cancelBtn);
    await dom.click(cancelBtn);
    const cancelReasonInput = dom.container.querySelector(
      'input[placeholder="Document rationale for cancelling action..."]'
    );
    assert.ok(cancelReasonInput);
    await dom.changeInput(cancelReasonInput, 'Covered by existing SOP-BLD-04 Rev 5');
    await dom.submit(findClosestForm(cancelReasonInput));
    await dom.flush();

    const capaCancelCall = calls.find((c) => c.url.endsWith('/quality/capas/902/cancel'));
    assert.ok(capaCancelCall);
    assert.deepEqual(capaCancelCall.body, {
      cancellationReason: 'Covered by existing SOP-BLD-04 Rev 5',
    });
    assert.equal('actorName' in capaCancelCall.body, false);

    const submitNotesInput = dom.container.querySelector('#submit-verif-notes');
    assert.ok(submitNotesInput);
    await dom.changeInput(submitNotesInput, 'All calibration evidence attached');
    await dom.submit(findClosestForm(submitNotesInput));
    await dom.flush();

    const submitVerifCall = calls.find((c) => c.url.endsWith('/submit-verification'));
    assert.ok(submitVerifCall);
    assert.deepEqual(submitVerifCall.body, {
      submissionNotes: 'All calibration evidence attached',
    });
    assert.equal('submittedBy' in submitVerifCall.body, false);
    assert.equal('actorName' in submitVerifCall.body, false);

    // 6. PENDING_VERIFICATION: QA_REVIEWER reopens CAPA (no actorName), re-submits, then Verify & Close (no verifiedBy/closedBy)
    const reopenToggleBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Reopen CAPA Actions')
    );
    assert.ok(reopenToggleBtn);
    await dom.click(reopenToggleBtn);
    await dom.changeInput(
      dom.container.querySelector('#reopen-reason'),
      'Need secondary check log'
    );
    await dom.submit(findClosestForm(dom.container.querySelector('#reopen-reason')));
    await dom.flush();

    const reopenCall = calls.find((c) => c.url.endsWith('/reopen-capa'));
    assert.ok(reopenCall);
    assert.deepEqual(reopenCall.body, { reason: 'Need secondary check log' });
    assert.equal('actorName' in reopenCall.body, false);

    // Submit again from CAPA_IN_PROGRESS -> PENDING_VERIFICATION -> close
    await dom.submit(findClosestForm(dom.container.querySelector('#submit-verif-notes')));
    await dom.flush();

    await dom.changeInput(
      dom.container.querySelector('#close-verification-notes'),
      'Verified secondary check log & effectiveness'
    );
    await dom.submit(findClosestForm(dom.container.querySelector('#close-verification-notes')));
    await dom.flush();

    const closeCall = calls.find((c) => c.url.endsWith('/close'));
    assert.ok(closeCall);
    assert.deepEqual(closeCall.body, {
      verificationNotes: 'Verified secondary check log & effectiveness',
    });
    assert.equal('verifiedBy' in closeCall.body, false);
    assert.equal('closedBy' in closeCall.body, false);
    assert.equal('actorName' in closeCall.body, false);
    assert.ok(dom.container.textContent.includes('CLOSED & LOCKED'));
    assert.ok(notifications.length > 0);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('DeviationWorkspace renders CLOSED deviation permanently read-only with zero mutation controls even for QA_REVIEWER', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: DeviationWorkspace } = await import('./DeviationWorkspace.jsx');

  const closedDeviation = {
    id: 29,
    deviationNumber: 'DEV-2026-0029',
    batchId: 52,
    batchNumber: 'BAT-52',
    batchStatus: 'COMPLETED',
    orderNumber: 'MO-2026-0052',
    productName: 'Paracetamol 500mg',
    recipeCode: 'RCP-001',
    recipeVersion: 1,
    title: 'Closed excursion',
    description: 'Verified moisture spike resolved',
    category: 'PROCESS_PARAMETER',
    severity: 'MAJOR',
    priority: 'HIGH',
    status: 'CLOSED',
    reportedBy: 'operator.floor',
    reportedAt: '2026-11-10T09:00:00',
    investigatedBy: 'qc.analyst',
    investigationSummary: 'Checked exhaust sensor',
    investigationStartedAt: '2026-11-10T10:00:00',
    rootCauseIdentifiedBy: 'qc.analyst',
    rootCauseCategory: 'Equipment',
    rootCauseAnalysis: 'Sensor drift',
    impactAssessment: 'Contained',
    rootCauseIdentifiedAt: '2026-11-10T11:00:00',
    capaPlanSummary: 'Recalibrated sensor',
    verifiedBy: 'qa.reviewer',
    verificationNotes: 'Calibration confirmed within spec',
    closedBy: 'qa.reviewer',
    closedAt: '2026-11-11T15:00:00',
    openCapaCount: 0,
    inProgressCapaCount: 0,
    completedCapaCount: 1,
    cancelledCapaCount: 0,
    overdueCapaCount: 0,
    eligibleForClosure: false,
    capaActions: [
      {
        id: 951,
        capaNumber: 'CAPA-951',
        capaType: 'CORRECTIVE',
        title: 'Recalibrate exhaust probe',
        actionPlan: '3-point check',
        ownerName: 'Maintenance',
        dueDate: '2099-12-31',
        status: 'COMPLETED',
        createdBy: 'prod.sup',
        completedBy: 'qc.analyst',
        completionEvidence: 'Cert #881',
      },
    ],
    auditLogs: [],
  };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.includes('/quality/deviations/29')) {
      return jsonResponse(closedDeviation, 200, url);
    }
    return jsonResponse({}, 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(DeviationWorkspace, { key: 'qa-closed-29', deviationId: 29, onBack: () => {} })
      )
    );
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Deviation Closed & Permanently Locked'));
    assert.ok(dom.container.textContent.includes('CLOSED & LOCKED'));

    const buttons = [...dom.container.querySelectorAll('button')].map((b) => b.textContent.trim());
    for (const forbiddenButton of [
      'Start Investigation',
      'Record Root Cause & Impact',
      'Add CAPA Action',
      'Enter CAPA In Progress',
      'Start',
      'Complete',
      'Cancel',
      'Submit for QA Verification',
      'Reopen CAPA Actions',
      'Verify & Close Deviation',
    ]) {
      assert.equal(
        buttons.includes(forbiddenButton),
        false,
        `CLOSED DeviationWorkspace must not render ${forbiddenButton}`
      );
    }

    assert.equal(dom.container.querySelector('#inv-summary'), null);
    assert.equal(dom.container.querySelector('#rca-category'), null);
    assert.equal(dom.container.querySelector('#start-capa-plan'), null);
    assert.equal(dom.container.querySelector('#submit-verif-notes'), null);
    assert.equal(dom.container.querySelector('#reopen-reason'), null);
    assert.equal(dom.container.querySelector('#close-verification-notes'), null);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});
