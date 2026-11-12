import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { createDomEnvironment, MiniEvent } from '../../scripts/dom-harness.mjs';

function makeAuthValue(role = 'QA_REVIEWER', overrides = {}) {
  const map = {
    ADMIN: { username: 'admin', fullName: 'System Admin' },
    PRODUCTION_SUPERVISOR: { username: 'prod.sup', fullName: 'Rohan Mehta' },
    OPERATOR: { username: 'operator.floor', fullName: 'Vikram Joshi' },
    QC_ANALYST: { username: 'qc.analyst', fullName: 'Sneha Kulkarni' },
    QA_REVIEWER: { username: 'qa.reviewer', fullName: 'Dr. Asha Sen' },
  };
  const info = map[role] || map.QA_REVIEWER;
  return {
    user: { id: 1, username: info.username, fullName: info.fullName, role, enabled: true },
    role,
    token: 'phase-8b5-jwt',
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

function getBodyRows(container) {
  return container.querySelector('tbody')?.querySelectorAll('tr') || [];
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

function makeQueueDataset(count = 30) {
  return Array.from({ length: count }, (_, idx) => {
    const num = idx + 1;
    const isHold = num % 5 === 0;
    const isReleased = num === 2;
    const isReadyPending = num === 3 || num === 29;
    const qaStatus = isReleased ? 'RELEASED' : isHold ? 'ON_HOLD' : 'PENDING_QA';
    return {
      batchId: 1000 + num,
      batchNumber: `BAT-Q-${String(num).padStart(3, '0')}`,
      batchStatus: 'COMPLETED',
      qaStatus,
      orderNumber: `MO-2026-${String(num).padStart(3, '0')}`,
      productName: isReadyPending ? `TargetReleaseProd ${num}` : `Standard Tablet ${num}`,
      productCode: `PRD-${String(num).padStart(3, '0')}`,
      recipeCode: `RCP-${String(num).padStart(3, '0')}`,
      recipeVersion: 1,
      batchSize: 1000,
      batchUnit: 'Tablets',
      actualYield: 980,
      totalChecksCount: 1,
      passedChecksCount: isReadyPending || isReleased ? 1 : 0,
      failedChecksCount: isHold ? 1 : 0,
      pendingChecksCount: isReadyPending || isReleased || isHold ? 0 : 1,
      requiredChecksCount: 1,
      optionalChecksCount: 0,
      openDeviationsCount: isHold ? 1 : 0,
      totalDeviationsCount: isHold ? 1 : 0,
      eligibleForRelease: isReadyPending,
      releaseBlockers: isReadyPending || isReleased ? [] : ['Pending QC or deviation resolution'],
    };
  });
}

function makeDeviationsDataset(count = 30) {
  return Array.from({ length: count }, (_, idx) => {
    const num = idx + 1;
    const isClosed = num % 6 === 0;
    const isSpecial = num === 28 || num === 29;
    return {
      id: 200 + num,
      deviationNumber: `DEV-2026-${String(num).padStart(4, '0')}`,
      batchId: 500 + num,
      batchNumber: `BAT-D-${String(num).padStart(3, '0')}`,
      batchStatus: num % 2 === 0 ? 'COMPLETED' : 'IN_PROGRESS',
      orderNumber: `MO-D-${String(num).padStart(3, '0')}`,
      productName: isSpecial ? `PriorityBlend Batch ${num}` : `Capsule Form ${num}`,
      productCode: `PRD-D-${num}`,
      title: isSpecial
        ? `TargetFilter Excursion #${num}`
        : `Standard Excursion #${num}`,
      category: isSpecial ? 'EQUIPMENT_MALFUNCTION' : 'IN_PROCESS_CONTROL',
      severity: isSpecial ? 'CRITICAL' : num % 3 === 0 ? 'MAJOR' : 'MINOR',
      priority: isSpecial ? 'HIGH' : 'MEDIUM',
      status: isClosed ? 'CLOSED' : isSpecial ? 'CAPA_IN_PROGRESS' : 'OPEN',
      reportedBy: 'operator.floor',
      reportedAt: `2026-11-${String((num % 28) + 1).padStart(2, '0')}T09:00:00`,
      totalCapaCount: isSpecial ? 2 : 1,
      completedCapaCount: isSpecial ? 1 : isClosed ? 1 : 0,
      cancelledCapaCount: 0,
      overdueCapaCount: isSpecial ? 1 : 0,
    };
  });
}

function makeAuditDataset(count = 30) {
  return Array.from({ length: count }, (_, idx) => {
    const num = idx + 1;
    const isDisposition = num === 29;
    return {
      id: 5000 + num,
      occurredAt: `2026-11-${String((num % 28) + 1).padStart(2, '0')}T14:${String(num % 60).padStart(2, '0')}:00`,
      action: isDisposition ? 'QA_DISPOSITION_RELEASED' : 'QC_CHECK_RECORDED',
      entityType: isDisposition ? 'BATCH' : 'QUALITY_CHECK',
      entityId: 300 + num,
      entityReference: isDisposition ? 'BAT-AUDIT-SPECIAL' : `QC-REF-${String(num).padStart(3, '0')}`,
      actorName: isDisposition ? 'Dr. Asha Sen' : 'Sneha Kulkarni',
      reason: isDisposition
        ? 'All checks passed; Bearer tok_hidden_abc and jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.sigtoken12345 stripped'
        : `Recorded analytical reading #${num}`,
      beforeValues: isDisposition
        ? JSON.stringify({
            qaStatus: 'PENDING_QA',
            apiKey: 'super-secret-value',
          })
        : JSON.stringify({ result: 'PENDING', count: num - 1 }),
      afterValues: isDisposition
        ? JSON.stringify({
            qaStatus: 'RELEASED',
            password: 'should-never-appear',
            token: 'secret-token-xyz',
          })
        : JSON.stringify({ result: 'PASS', count: num }),
    };
  });
}

test('QualityManagement separates loading, error + Retry (no empty card), true empty, and filters across all items before 25-row pagination', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: QualityManagement } = await import('./QualityManagement.jsx');

  const dataset = makeQueueDataset(30);
  let mode = 'error'; // 'error' | 'empty' | 'populated'
  const prevFetch = globalThis.fetch;

  globalThis.fetch = async (url) => {
    if (url.endsWith('/quality/batches')) {
      if (mode === 'error') {
        return jsonResponse({ message: 'Quality queue service unavailable' }, 503, url);
      }
      if (mode === 'empty') {
        return jsonResponse([], 200, url);
      }
      return jsonResponse(dataset, 200, url);
    }
    return jsonResponse([], 200, url);
  };

  try {
    // 1. Initial fetch error: shows error alert + Retry, NEVER renders "No completed batches in QA queue"
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(QualityManagement)
      )
    );
    await dom.flush();

    const inlineQueueAlert = [...dom.container.querySelectorAll('[role="alert"]')].find((el) =>
      el.textContent.includes('Unable to load quality inspection queue')
    );
    assert.ok(inlineQueueAlert, 'Expected InlineAlert role="alert" on queue failure');
    assert.ok(inlineQueueAlert.textContent.includes('Quality queue service unavailable'));
    assert.equal(
      dom.container.textContent.includes('No completed batches in QA queue'),
      false,
      'Must NOT show empty queue card when initial fetch failed'
    );

    // 2. Retry succeeds with true empty list -> empty state appears
    mode = 'empty';
    const retryBtn = [...dom.container.querySelectorAll('button')].find(
      (b) => b.textContent.trim() === 'Retry'
    );
    assert.ok(retryBtn);
    await dom.click(retryBtn);
    await dom.flush();

    assert.equal(dom.container.querySelector('[role="alert"]'), null);
    assert.ok(dom.container.textContent.includes('No completed batches in QA queue'));

    // 3. Refresh to populated dataset (30 rows) -> defaults to 25 rows on page 1
    mode = 'populated';
    const refreshBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Refresh')
    );
    assert.ok(refreshBtn);
    await dom.click(refreshBtn);
    await dom.flush();

    assert.equal(getBodyRows(dom.container).length, 25, 'Default page size must render 25 rows');
    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 30 batches'));

    // Navigate to Page 2 -> 5 rows (including BAT-Q-029)
    const nextBtn = dom.container.querySelector('button[aria-label="Next page"]');
    assert.ok(nextBtn);
    await dom.click(nextBtn);
    await dom.flush();
    assert.equal(getBodyRows(dom.container).length, 5);
    assert.ok(dom.container.textContent.includes('BAT-Q-029'));

    // Filter by Release Gate "Eligible for Release" across FULL dataset -> resets to Page 1!
    const gateSelect = dom.container.querySelector('select[aria-label="Filter by release readiness"]');
    assert.ok(gateSelect);
    await dom.changeInput(gateSelect, 'READY');
    await dom.flush();

    const readyRows = getBodyRows(dom.container);
    assert.equal(readyRows.length, 2, 'Should find BAT-Q-003 (page 1) and BAT-Q-029 (page 2)');
    assert.ok(dom.container.textContent.includes('BAT-Q-003'));
    assert.ok(dom.container.textContent.includes('BAT-Q-029'));
    assert.ok(dom.container.textContent.includes('Showing 1\u20132 of 2 batches'));

    // Combine with search on non-matching text -> filtered empty + Reset Filters
    const searchInput = dom.container.querySelector(
      'input[placeholder="Search batch, order, product, or recipe..."]'
    );
    assert.ok(searchInput);
    await dom.changeInput(searchInput, 'NON_EXISTENT_999');
    await dom.flush();
    assert.ok(dom.container.textContent.includes('No matching completed batches'));

    const resetBtn = [...dom.container.querySelectorAll('button')].find(
      (b) => b.textContent.trim() === 'Reset Filters'
    );
    assert.ok(resetBtn);
    await dom.click(resetBtn);
    await dom.flush();
    assert.equal(getBodyRows(dom.container).length, 25);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('QualityBatchDossier distinguishes Mfg vs QA status, renders QC limits & correctedAt, links Order/Recipe/eBR, and inspects scoped diffs', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');
  const { default: QualityBatchDossier } = await import('./QualityBatchDossier.jsx');

  const navCalls = [];
  const sampleDossier = {
    batchId: 77,
    batchNumber: 'BAT-2026-0077-01',
    batchStatus: 'COMPLETED',
    qaStatus: 'ON_HOLD',
    orderId: 501,
    orderNumber: 'MO-2026-0077',
    productName: 'Paracetamol 500mg',
    productCode: 'PRD-001',
    recipeId: 301,
    recipeCode: 'RCP-001',
    recipeVersion: 2,
    batchSize: 1000,
    batchUnit: 'Tablets',
    actualYield: 985,
    startTime: '2026-11-10T08:00:00',
    endTime: '2026-11-10T16:30:00',
    manufactureDate: '2026-11-10',
    expiryDate: '2028-11-10',
    openDeviationsCount: 1,
    totalDeviationsCount: 1,
    qualityChecks: [
      {
        id: 901,
        testName: 'Assay (HPLC)',
        specification: '95.0% - 105.0%',
        measuredValue: '99.4',
        unit: '%',
        result: 'PASS',
        required: true,
        locked: true,
        inspectorName: 'qc.analyst',
        checkedAt: '2026-11-10T17:00:00',
        correctionCount: 1,
        correctedBy: 'qc.analyst',
        correctedAt: '2026-11-10T17:15:00',
        correctionReason: 'Corrected decimal transcription',
      },
      {
        id: 902,
        testName: 'Loss on Drying',
        specification: '< 2.0%',
        measuredValue: '2.4',
        unit: '%',
        result: 'FAIL',
        required: true,
        locked: true,
        inspectorName: 'qc.analyst',
        checkedAt: '2026-11-10T17:20:00',
        correctionCount: 0,
      },
    ],
    deviations: [
      {
        id: 41,
        deviationNumber: 'DEV-2026-0041',
        title: 'LOD exceeded specification',
        category: 'QC_OOS_FAILURE',
        severity: 'CRITICAL',
        priority: 'HIGH',
        status: 'CAPA_IN_PROGRESS',
        reportedAt: '2026-11-10T17:30:00',
        totalCapaCount: 2,
        completedCapaCount: 1,
        cancelledCapaCount: 0,
        overdueCapaCount: 1,
      },
    ],
    qaReviews: [
      {
        id: 12,
        previousStatus: 'PENDING_QA',
        decisionStatus: 'ON_HOLD',
        rationale: 'Hold pending LOD root cause',
        reviewerName: 'qa.reviewer',
        reviewedAt: '2026-11-10T18:00:00',
        passedChecksCount: 1,
        failedChecksCount: 1,
        pendingChecksCount: 0,
      },
    ],
    auditLogs: [
      {
        id: 888,
        occurredAt: '2026-11-10T18:00:00',
        action: 'QA_DISPOSITION_ON_HOLD',
        entityType: 'BATCH',
        entityId: 77,
        actorName: 'Dr. Asha Sen',
        reason: 'Batch held pending investigation; password token scrubbed',
        beforeValues: JSON.stringify({ qaStatus: 'PENDING_QA', password: 'secret' }),
        afterValues: JSON.stringify({ qaStatus: 'ON_HOLD', password: 'secret' }),
      },
    ],
  };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/quality/batches/77')) {
      return jsonResponse(sampleDossier, 200, url);
    }
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(
          NavigationProvider,
          {
            value: {
              activeTab: 'quality',
              routeParams: new URLSearchParams(),
              routeNonce: 0,
              navigateToTab: (tab, params) => navCalls.push({ tab, params }),
            },
          },
          React.createElement(QualityBatchDossier, {
            batchId: 77,
            onBack: () => {},
            onOpenDeviation: () => {},
          })
        )
      )
    );
    await dom.flush();

    const text = dom.container.textContent;
    assert.ok(text.includes('Mfg: COMPLETED'), 'Must clearly distinguish Manufacturing COMPLETED');
    assert.ok(text.includes('ON_HOLD'), 'Must show distinct QA status');
    assert.ok(text.includes('Closing a deviation never automatically releases a batch'));
    assert.ok(text.includes('Limit: 95 to 105'));
    assert.ok(text.includes('Within Limit'));
    assert.ok(text.includes('Out of Limit'));
    assert.ok(text.includes('Corrected (1x) by qc.analyst'));
    const { formatDateTime } = await import('./QualityBatchDossier.jsx');
    assert.ok(
      text.includes(formatDateTime('2026-11-10T17:15:00')),
      'Expected formatted correctedAt timestamp'
    );
    assert.ok(text.includes('98.5% of target size'));
    assert.ok(text.includes('1 of 2 QC checks passed'));

    // Progress bars for yield, QC pass summary, and linked deviation CAPA
    const progressBars = dom.container.querySelectorAll('[role="progressbar"]');
    assert.ok(progressBars.length >= 3, 'Expected ProgressBar instances for yield, QC, and CAPA');

    // Cross-module navigation buttons: Open Order + Inspect Recipe
    const openOrderBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Open Order')
    );
    const inspectRecipeBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Inspect Recipe')
    );
    assert.ok(openOrderBtn);
    assert.ok(inspectRecipeBtn);
    await dom.click(openOrderBtn);
    await dom.click(inspectRecipeBtn);
    assert.deepEqual(navCalls[0], { tab: 'orders', params: { orderId: 501 } });
    assert.deepEqual(navCalls[1], { tab: 'recipes', params: { recipeId: 301 } });

    // Batch Audit Trail starts collapsed -> Inspect Diff expands AuditDiffViewer with redaction
    assert.equal(text.includes('qaStatus'), false);
    const inspectDiffBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Inspect Diff')
    );
    assert.ok(inspectDiffBtn);
    await dom.click(inspectDiffBtn);
    await dom.flush();
    assert.ok(dom.container.textContent.includes('qaStatus'));
    assert.ok(dom.container.textContent.includes('PENDING_QA'));
    assert.ok(dom.container.textContent.includes('ON_HOLD'));
    assert.equal(dom.container.textContent.includes('secret'), false, 'Sensitive values must be redacted');
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('QualityCheckModal, QaDispositionModal, and ReportDeviationModal use ModalDialog with ARIA semantics and validation alerts', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: QualityCheckModal } = await import('./QualityCheckModal.jsx');
  const { default: QaDispositionModal } = await import('./QaDispositionModal.jsx');
  const { default: ReportDeviationModal } = await import('./ReportDeviationModal.jsx');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.includes('/quality/batches/77/linkable-records')) {
      return jsonResponse({ qualityChecks: [], executionSteps: [], auditLogs: [] }, 200, url);
    }
    return jsonResponse([], 200, url);
  };

  let closedCount = 0;

  try {
    // 1. QualityCheckModal ARIA dialog + labels + validation role="alert" + Escape close
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QC_ANALYST') },
        React.createElement(QualityCheckModal, {
          isOpen: true,
          mode: 'create',
          batchId: 77,
          batchNumber: 'BAT-77',
          onClose: () => {
            closedCount += 1;
          },
        })
      )
    );
    await dom.flush();

    const qcDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(qcDialog);
    assert.equal(qcDialog.getAttribute('aria-modal'), 'true');
    assertLabelPairsExist(dom.container, [
      'Test Name',
      'Acceptance Specification',
      'Result',
      'Unit',
    ]);

    const recordBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Save QC Check')
    );
    assert.ok(recordBtn);
    await dom.click(recordBtn);
    await dom.flush();
    assert.ok(dom.container.querySelector('[role="alert"]'), 'Expected accessible validation alert');
    assert.equal(dom.container.querySelector('#qc-test-name').getAttribute('aria-invalid'), 'true');

    qcDialog.dispatchEvent(makeKeyEvent('Escape'));
    assert.equal(closedCount, 1);

    // 2. QaDispositionModal ARIA dialog + empty rationale validation alert
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(QaDispositionModal, {
          isOpen: true,
          dossier: {
            batchId: 77,
            batchNumber: 'BAT-77',
            batchStatus: 'COMPLETED',
            qaStatus: 'PENDING_QA',
            actualYield: 990,
            batchSize: 1000,
            batchUnit: 'Tablets',
            qualityChecks: [
              { id: 1, result: 'PASS', required: true },
            ],
            deviations: [],
          },
          initialDecision: 'RELEASED',
          onClose: () => {},
        })
      )
    );
    await dom.flush();

    const qaDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(qaDialog);
    assert.equal(qaDialog.getAttribute('aria-modal'), 'true');
    assertLabelPairsExist(dom.container, ['QA Disposition Decision', 'Decision Rationale']);
    const confirmDispBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Approve & Release Batch')
    );
    assert.ok(confirmDispBtn);
    await dom.click(confirmDispBtn);
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Decision rationale is mandatory'));

    // 3. ReportDeviationModal ARIA dialog + validation role="alert"
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(ReportDeviationModal, {
          isOpen: true,
          lockBatchSelection: true,
          prefillData: { batchId: 77, batchNumber: 'BAT-77' },
          onClose: () => {},
        })
      )
    );
    await dom.flush();
    const devDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(devDialog);
    assert.equal(devDialog.getAttribute('aria-modal'), 'true');
    assertLabelPairsExist(dom.container, [
      'Deviation Title',
      'Category',
      'Severity',
      'Priority',
      'Event Description',
    ]);
    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();
    assert.ok(dom.container.querySelector('[role="alert"]'));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('DeviationManagement separates error + Retry from empty state and filters across all deviations before 25-row pagination', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: DeviationManagement } = await import('./DeviationManagement.jsx');

  const dataset = makeDeviationsDataset(30);
  let mode = 'error';
  const prevFetch = globalThis.fetch;

  globalThis.fetch = async (url) => {
    if (url.includes('/quality/deviations')) {
      if (mode === 'error') {
        return jsonResponse({ message: 'Deviation query failed' }, 500, url);
      }
      return jsonResponse(dataset, 200, url);
    }
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(DeviationManagement, { onSelectDeviation: () => {} })
      )
    );
    await dom.flush();

    assert.ok(dom.container.querySelector('[role="alert"]'));
    assert.equal(
      dom.container.textContent.includes('No deviations recorded yet'),
      false,
      'Must NOT display empty state during load failure'
    );

    mode = 'populated';
    const retryBtn = [...dom.container.querySelectorAll('button')].find(
      (b) => b.textContent.trim() === 'Retry'
    );
    await dom.click(retryBtn);
    await dom.flush();

    assert.equal(getBodyRows(dom.container).length, 25);
    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 30 deviations'));

    // Move to page 2 -> 5 rows
    await dom.click(dom.container.querySelector('button[aria-label="Next page"]'));
    await dom.flush();
    assert.equal(getBodyRows(dom.container).length, 5);
    assert.ok(dom.container.textContent.includes('DEV-2026-0029'));

    // Filter by CRITICAL severity -> matches page-2 items across full dataset and resets to page 1
    await dom.changeInput(
      dom.container.querySelector('select[aria-label="Filter by severity"]'),
      'CRITICAL'
    );
    await dom.flush();

    const filteredRows = getBodyRows(dom.container);
    assert.equal(filteredRows.length, 2);
    assert.ok(dom.container.textContent.includes('DEV-2026-0028'));
    assert.ok(dom.container.textContent.includes('DEV-2026-0029'));
    assert.ok(dom.container.textContent.includes('Showing 1\u20132 of 2 deviations'));
    assert.ok(dom.container.querySelectorAll('[role="progressbar"]').length >= 2);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('DeviationWorkspace separates Corrective vs Preventive CAPAs, shows traceability links, and inspects scoped redacted diffs', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');
  const { default: DeviationWorkspace } = await import('./DeviationWorkspace.jsx');

  const navCalls = [];
  const devDetail = {
    id: 19,
    deviationNumber: 'DEV-2026-0019',
    batchId: 51,
    batchNumber: 'BAT-51',
    batchStatus: 'COMPLETED',
    batchQaStatus: 'ON_HOLD',
    orderId: 700,
    orderNumber: 'MO-700',
    recipeId: 800,
    recipeCode: 'RCP-800',
    recipeVersion: 3,
    productName: 'Ibuprofen 400mg',
    productCode: 'PRD-800',
    title: 'Compression force excursion',
    description: 'Upper punch force spike observed',
    category: 'PROCESS_PARAMETER',
    severity: 'CRITICAL',
    priority: 'HIGH',
    status: 'CAPA_IN_PROGRESS',
    reportedBy: 'operator.floor',
    reportedAt: '2026-11-10T09:00:00',
    occurredAt: '2026-11-10T08:45:00',
    openCapaCount: 0,
    inProgressCapaCount: 1,
    completedCapaCount: 1,
    cancelledCapaCount: 0,
    overdueCapaCount: 1,
    totalCapaCount: 2,
    capaActions: [
      {
        id: 101,
        capaNumber: 'CAPA-C-01',
        capaType: 'CORRECTIVE',
        title: 'Recalibrate force transducer',
        actionPlan: 'Calibrate load cell and verify reading',
        ownerName: 'Maintenance Eng',
        dueDate: '2026-11-01',
        status: 'IN_PROGRESS',
        overdue: true,
        createdBy: 'prod.sup',
      },
      {
        id: 102,
        capaNumber: 'CAPA-P-01',
        capaType: 'PREVENTIVE',
        title: 'Add pre-run check to SOP-CMP-02',
        actionPlan: 'Require pre-compression verification signoff',
        ownerName: 'QA Eng',
        dueDate: '2099-12-31',
        status: 'COMPLETED',
        overdue: false,
        createdBy: 'prod.sup',
        completedBy: 'qa.reviewer',
        completedAt: '2026-11-11T10:00:00',
        completionEvidence: 'ECO-2026-19 approved',
      },
    ],
    auditLogs: [
      {
        id: 71,
        occurredAt: '2026-11-11T10:00:00',
        action: 'CAPA_COMPLETED',
        entityType: 'CAPA_ACTION',
        entityId: 102,
        entityReference: 'CAPA-P-01',
        actorName: 'Dr. Asha Sen',
        reason: 'Attached ECO-2026-19; secret Bearer tok_live_abc999 stripped',
        beforeValues: JSON.stringify({ status: 'IN_PROGRESS', token: 'secret_val' }),
        afterValues: JSON.stringify({ status: 'COMPLETED', token: 'secret_val' }),
      },
    ],
  };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.includes('/quality/deviations/19')) {
      return jsonResponse(devDetail, 200, url);
    }
    return jsonResponse({}, 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(
          NavigationProvider,
          {
            value: {
              activeTab: 'quality',
              routeParams: new URLSearchParams(),
              routeNonce: 0,
              navigateToTab: (tab, params) => navCalls.push({ tab, params }),
            },
          },
          React.createElement(DeviationWorkspace, {
            deviationId: 19,
            onBack: () => {},
            onOpenBatchDossier: () => {},
          })
        )
      )
    );
    await dom.flush();

    const correctiveSection = dom.container.querySelector('section[aria-label="Corrective Actions"]');
    const preventiveSection = dom.container.querySelector('section[aria-label="Preventive Actions"]');
    assert.ok(correctiveSection, 'Expected separate Corrective Actions subsection');
    assert.ok(preventiveSection, 'Expected separate Preventive Actions subsection');
    assert.ok(correctiveSection.textContent.includes('CAPA-C-01'));
    assert.equal(correctiveSection.textContent.includes('CAPA-P-01'), false);
    assert.ok(preventiveSection.textContent.includes('CAPA-P-01'));
    assert.equal(preventiveSection.textContent.includes('CAPA-C-01'), false);
    assert.ok(preventiveSection.textContent.includes('ECO-2026-19 approved'));

    // Cross-module links: eBR execution, Open Order, Inspect Recipe
    const ebrBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Inspect eBR Execution')
    );
    const orderBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Open Order')
    );
    const recipeBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Inspect Recipe')
    );
    assert.ok(ebrBtn);
    assert.ok(orderBtn);
    assert.ok(recipeBtn);
    await dom.click(ebrBtn);
    await dom.click(orderBtn);
    await dom.click(recipeBtn);
    assert.deepEqual(navCalls, [
      { tab: 'execution', params: { batchId: 51 } },
      { tab: 'orders', params: { orderId: 700 } },
      { tab: 'recipes', params: { recipeId: 800 } },
    ]);

    // Scoped audit log redaction & diff expansion
    assert.equal(dom.container.textContent.includes('tok_live_abc999'), false);
    assert.equal(dom.container.textContent.includes('secret_val'), false);
    assert.ok(dom.container.textContent.includes('Bearer [REDACTED]'));

    const diffBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Inspect Diff')
    );
    assert.ok(diffBtn);
    await dom.click(diffBtn);
    await dom.flush();
    assert.ok(dom.container.textContent.includes('IN_PROGRESS'));
    assert.ok(dom.container.textContent.includes('COMPLETED'));
    assert.equal(dom.container.textContent.includes('secret_val'), false);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('Global Audit Trail enforces RBAC, read-only immutability, 25-row pagination, filtering before pagination, and AuditDiffViewer redaction', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');
  const { default: QualityManagement } = await import('./QualityManagement.jsx');

  const auditDataset = makeAuditDataset(30);
  const requests = [];
  const prevFetch = globalThis.fetch;

  globalThis.fetch = async (url) => {
    requests.push(url);
    if (url.endsWith('/quality/batches')) return jsonResponse([], 200, url);
    if (url.endsWith('/quality/audit-logs')) return jsonResponse(auditDataset, 200, url);
    return jsonResponse([], 200, url);
  };

  try {
    // 1. QC_ANALYST cannot see Audit Trail and even ?view=audit route param cannot load /audit-logs
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QC_ANALYST') },
        React.createElement(
          NavigationProvider,
          {
            value: {
              activeTab: 'quality',
              routeParams: new URLSearchParams('view=audit'),
              routeNonce: 1,
              navigateToTab: () => {},
            },
          },
          React.createElement(QualityManagement)
        )
      )
    );
    await dom.flush();
    assert.equal(dom.container.textContent.includes('Audit Trail'), false);
    assert.equal(
      requests.some((u) => u.includes('/quality/audit-logs')),
      false,
      'QC_ANALYST must never fetch global audit logs'
    );

    // 2. QA_REVIEWER switches to Audit Trail -> append-only banner, 25-row default pagination, zero edit/delete actions
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(QualityManagement)
      )
    );
    await dom.flush();

    const auditTabBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Audit Trail')
    );
    assert.ok(auditTabBtn);
    await dom.click(auditTabBtn);
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Append-Only Quality Record Log'));
    assert.ok(dom.container.textContent.includes('Edits and deletions are disabled'));
    assert.equal(getBodyRows(dom.container).length, 25);
    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 30 audit entries'));

    const allButtonTexts = [...dom.container.querySelectorAll('button')].map((b) =>
      b.textContent.trim()
    );
    assert.equal(allButtonTexts.some((t) => /^(Edit|Delete|Modify|Remove)$/i.test(t)), false);

    // Filter by Action dropdown across full dataset -> finds entry #29
    const actionSelect = dom.container.querySelector('select[aria-label="Filter by audit action"]');
    assert.ok(actionSelect);
    await dom.changeInput(actionSelect, 'QA_DISPOSITION_RELEASED');
    await dom.flush();

    assert.equal(getBodyRows(dom.container).length, 1);
    assert.ok(dom.container.textContent.includes('BAT-AUDIT-SPECIAL'));
    assert.equal(dom.container.textContent.includes('tok_hidden_abc'), false, 'Bearer token must be redacted in reason');
    assert.equal(dom.container.textContent.includes('sigtoken12345'), false, 'JWT must be redacted in reason');
    assert.ok(dom.container.textContent.includes('Bearer [REDACTED]'));
    assert.ok(dom.container.textContent.includes('[REDACTED_JWT]'));

    // Expand AuditDiffViewer and verify sensitive fields are redacted in both Diff and Raw JSON views
    const inspectBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Inspect Diff')
    );
    assert.ok(inspectBtn);
    await dom.click(inspectBtn);
    await dom.flush();

    assert.equal(dom.container.textContent.includes('super-secret-value'), false);
    assert.equal(dom.container.textContent.includes('should-never-appear'), false);
    assert.equal(dom.container.textContent.includes('secret-token-xyz'), false);
    assert.ok(dom.container.textContent.includes('[REDACTED]'));
    assert.ok(dom.container.textContent.includes('qaStatus'));

    const rawBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Raw JSON')
    );
    assert.ok(rawBtn);
    await dom.click(rawBtn);
    await dom.flush();
    assert.equal(dom.container.textContent.includes('should-never-appear'), false);
    assert.ok(dom.container.textContent.includes('[REDACTED]'));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});
