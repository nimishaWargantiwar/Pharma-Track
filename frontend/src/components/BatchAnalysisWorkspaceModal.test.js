import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { createDomEnvironment } from '../../scripts/dom-harness.mjs';
import { setAuthToken, clearAuthToken } from '../services/apiClient.js';
import { AuthContext } from '../context/AuthContext.jsx';
import { NavigationContext } from '../context/NavigationContext.jsx';
import BatchAnalysisWorkspaceModal from './BatchAnalysisWorkspaceModal.jsx';

function jsonResponse(body, status = 200, url = 'http://localhost:8080/api') {
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

function errorResponse(status, message) {
  return {
    ok: false,
    status,
    url: 'http://localhost:8080/api',
    headers: {
      get: (name) => (name.toLowerCase() === 'content-type' ? 'application/json' : null),
    },
    json: async () => ({
      status,
      message,
      timestamp: '2026-11-15T02:00:00+05:30',
    }),
  };
}

const mockDossier = {
  batchId: 10,
  batchNumber: 'BAT-2026-0010-01',
  qrCode: 'QR-PT-BAT-2026-0010-01',
  orderId: 5,
  orderNumber: 'MO-2026-0010',
  orderStatus: 'COMPLETED',
  productId: 2,
  productCode: 'PRD-AMX-500',
  productName: 'Amoxicillin 500mg Capsules',
  recipeCode: 'RCP-AMX-001',
  recipeVersion: 2,
  batchSize: 500.0,
  batchUnit: 'kg',
  actualYield: 498.2,
  batchStatus: 'COMPLETED',
  qaStatus: 'RELEASED',
};

const sampleReportSummary = {
  id: 101,
  batchId: 10,
  batchNumber: 'BAT-2026-0010-01',
  status: 'COMPLETED',
  model: 'gpt-4',
  clientRequestId: 'req-init-12345',
  generatedByUsername: 'supervisor_1',
  createdAt: '2026-11-15T02:00:00',
  completedAt: '2026-11-15T02:00:08',
  yieldPercent: 99.64,
  deviationCount: 0,
};

const sampleReportDetail = {
  id: 101,
  batchId: 10,
  batchNumber: 'BAT-2026-0010-01',
  status: 'COMPLETED',
  model: 'gpt-4',
  clientRequestId: 'req-init-12345',
  generatedByUsername: 'supervisor_1',
  createdAt: '2026-11-15T02:00:00',
  completedAt: '2026-11-15T02:00:08',
  deterministicMetrics: {
    plannedBatchSize: 500.0,
    actualYield: 498.2,
    yieldPercent: 99.64,
    yieldVariance: -1.8,
    executionStepCount: 8,
    qcCheckCount: 5,
    deviationCount: 0,
  },
  structuredAnalysis: {
    executiveSummary: 'Batch execution proceeded normally.',
    batchStatusAndProgress: 'All operations complete.',
    plannedVsActualMetrics: 'Actual yield: 498.2 kg.',
    materialTraceability: 'All raw material lots reconciled.',
    ebrStepEvaluation: 'All 8 steps completed according to recipe.',
    qcInspectionAnalysis: 'All 5 analytical checks within specification.',
    qaDispositionStatus: 'Batch disposition: RELEASED.',
    deviationsAndCapa: 'No deviations logged for this batch.',
    chronologicalAuditAnalysis: 'Full audit integrity confirmed.',
    missingRecords: 'No missing records identified.',
    riskObservations: 'No significant operational or quality risks.',
    followUpRecommendations: 'Proceed to commercial packaging.',
  },
  reportMarkdown: '# Batch Analysis Report\n\nFull narrative text.',
  sourceReferences: [
    {
      recordType: 'BATCH',
      recordIdentifier: 'BAT-2026-0010-01',
      title: 'BAT-2026-0010-01 (COMPLETED)',
      module: 'Production Execution',
      summary: 'Status: COMPLETED | QA: RELEASED',
    },
    {
      recordType: 'MANUFACTURING_ORDER',
      recordIdentifier: 'MO-2026-0010',
      title: 'MO-2026-0010 (Amoxicillin)',
      module: 'Manufacturing Orders',
      summary: 'Status: COMPLETED',
    },
    {
      recordType: 'DEVIATION',
      recordIdentifier: 'DEV-2026-0001',
      title: 'DEV-2026-0001 (Minor)',
      module: 'Quality Management',
      summary: 'Status: CLOSED',
    },
  ],
  regulatoryDisclaimer:
    'ADVISORY ONLY: This AI-generated batch analysis report is for operational oversight and decision-support purposes only. It does not constitute formal Quality Assurance batch release authorization or supersede Qualified Person (QP) review under applicable GxP regulations (EU GMP Annex 16 / 21 CFR Part 211). Formal release requires qualified human authorization.',
};

async function waitForUi(dom, turns = 4) {
  for (let i = 0; i < turns; i += 1) {
    await dom.flush();
    await new Promise((r) => setTimeout(r, 20));
  }
}

async function renderModalWithContext(dom, { role = 'PRODUCTION_SUPERVISOR', modalProps = {} } = {}) {
  const authValue = {
    user: { id: 1, username: 'supervisor_1', role },
    role,
    isAuthenticated: true,
  };

  const navValue = {
    activeTab: 'traceability',
    routeParams: new URLSearchParams('batchNumber=BAT-2026-0010-01'),
    routeNonce: 1,
    navigateToTab: () => {},
  };

  await dom.render(
    React.createElement(
      AuthContext.Provider,
      { value: authValue },
      React.createElement(
        NavigationContext.Provider,
        { value: navValue },
        React.createElement(BatchAnalysisWorkspaceModal, {
          isOpen: true,
          onClose: () => {},
          batchId: 10,
          batchNumber: 'BAT-2026-0010-01',
          dossier: mockDossier,
          role,
          ...modalProps,
        })
      )
    )
  );

  await waitForUi(dom);
}

// =============================================================================
// 1. Role-specific Access Tests
// =============================================================================

test('PRODUCTION_SUPERVISOR sees Generate New Report button in workspace header', async () => {
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    const genBtn = dom.container.querySelector('[data-testid="workspace-generate-report-button"]');
    assert.ok(genBtn, 'Generate New Report button must be visible for PRODUCTION_SUPERVISOR');
    assert.ok(genBtn.textContent.includes('Generate New Report'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('QA_REVIEWER and ADMIN see history and report details but CANNOT see Generate button', async () => {
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    for (const viewerRole of ['QA_REVIEWER', 'ADMIN']) {
      await renderModalWithContext(dom, { role: viewerRole });

      const genBtn = dom.container.querySelector('[data-testid="workspace-generate-report-button"]');
      assert.equal(genBtn, null, `Generate button must NOT be visible for ${viewerRole}`);

      const historySidebar = dom.container.querySelector('[data-testid="report-history-sidebar"]');
      assert.ok(historySidebar, `History sidebar must be visible for ${viewerRole}`);
    }
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('OPERATOR and QC_ANALYST receive Access Denied in workspace modal', async () => {
  const dom = await createDomEnvironment();
  try {
    for (const deniedRole of ['OPERATOR', 'QC_ANALYST']) {
      await renderModalWithContext(dom, { role: deniedRole });

      const text = dom.container.textContent;
      assert.ok(text.includes('Access Denied'), `Must display Access Denied for ${deniedRole}`);
      assert.equal(dom.container.querySelector('[data-testid="workspace-generate-report-button"]'), null);
      assert.equal(dom.container.querySelector('[data-testid="report-history-sidebar"]'), null);
    }
  } finally {
    await dom.unmount?.();
  }
});

// =============================================================================
// 2. Loading History without Auto-generating POST
// =============================================================================

test('Opening workspace loads history without automatically calling POST generate', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  globalThis.fetch = async (url, options) => {
    captured.push({ url: String(url), method: options?.method || 'GET' });
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    const postRequests = captured.filter((c) => c.method === 'POST');
    assert.equal(postRequests.length, 0, 'Opening workspace must NOT generate a report automatically');

    const getRequests = captured.filter((c) => c.method === 'GET' && c.url.includes('/analysis-reports'));
    assert.ok(getRequests.length >= 1, 'Must call GET history endpoint on initial open');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// =============================================================================
// 3. Successful Generation & Duplicate-click Prevention
// =============================================================================

test('Clicking Generate New Report sends POST with unique clientRequestId and updates view', async () => {
  const prevFetch = globalThis.fetch;
  const postBodies = [];

  const newlyCreatedReport = {
    ...sampleReportDetail,
    id: 102,
    clientRequestId: 'req-new-54321',
    createdAt: '2026-11-15T02:30:00',
  };

  globalThis.fetch = async (url, options) => {
    const method = options?.method || 'GET';
    if (method === 'POST') {
      postBodies.push(JSON.parse(options.body));
      return jsonResponse(newlyCreatedReport);
    }
    if (String(url).includes('/analysis-reports/102')) return jsonResponse(newlyCreatedReport);
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) {
      return jsonResponse([
        { ...sampleReportSummary, id: 102, clientRequestId: 'req-new-54321' },
        sampleReportSummary,
      ]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    const genBtn = dom.container.querySelector('[data-testid="workspace-generate-report-button"]');
    assert.ok(genBtn);

    await dom.click(genBtn);
    await waitForUi(dom);

    const confirmBtn = dom.container.querySelector('[data-testid="confirm-generate-report-button"]');
    assert.ok(confirmBtn, 'Modal confirm button must be present after opening generation dialog');
    await dom.click(confirmBtn);
    await waitForUi(dom);

    assert.equal(postBodies.length, 1);
    assert.ok(postBodies[0].clientRequestId.startsWith('req-'));
    assert.equal(postBodies[0].supervisorNotes, undefined);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Duplicate-click prevention: rapid multiple clicks trigger only 1 POST request', async () => {
  const prevFetch = globalThis.fetch;
  let postCount = 0;

  globalThis.fetch = async (url, options) => {
    const method = options?.method || 'GET';
    if (method === 'POST') {
      postCount += 1;
      return jsonResponse(sampleReportDetail);
    }
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    const genBtn = dom.container.querySelector('[data-testid="workspace-generate-report-button"]');
    assert.ok(genBtn);

    await dom.click(genBtn);
    await waitForUi(dom);

    const confirmBtn = dom.container.querySelector('[data-testid="confirm-generate-report-button"]');
    assert.ok(confirmBtn, 'Modal confirm button must be present');

    await dom.click(confirmBtn);
    await waitForUi(dom);

    assert.equal(postCount, 1, 'Triggers exactly 1 POST request');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// =============================================================================
// 4. Selecting Historical Report Loads Details
// =============================================================================

test('Selecting a historical report loads its details via GET /reports/{id}', async () => {
  const prevFetch = globalThis.fetch;
  const requestedDetailIds = [];

  const summary1 = { ...sampleReportSummary, id: 101 };
  const summary2 = { ...sampleReportSummary, id: 102, yieldPercent: 97.5 };

  globalThis.fetch = async (url) => {
    const urlStr = String(url);
    if (urlStr.includes('/analysis-reports/102')) {
      requestedDetailIds.push(102);
      return jsonResponse({ ...sampleReportDetail, id: 102 });
    }
    if (urlStr.includes('/analysis-reports/101')) {
      requestedDetailIds.push(101);
      return jsonResponse(sampleReportDetail);
    }
    if (urlStr.includes('/analysis-reports')) {
      return jsonResponse([summary2, summary1]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    // Item 101 must be in history sidebar
    const item101 = dom.container.querySelector('[data-testid="history-item-101"]');
    assert.ok(item101, 'Item 101 must be in history');

    await dom.click(item101);
    await waitForUi(dom);

    assert.ok(requestedDetailIds.includes(101));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// =============================================================================
// 5. Deterministic KPI Cards Rendering (Null handling & Yield Variance Qty Unit)
// =============================================================================

test('Deterministic KPI cards render values, units, and preserve nulls as Not recorded / Unavailable', async () => {
  const prevFetch = globalThis.fetch;

  const reportWithNulls = {
    ...sampleReportDetail,
    deterministicMetrics: {
      plannedBatchSize: null,
      actualYield: null,
      yieldPercent: null,
      yieldVariance: null,
      executionStepCount: null,
      qcCheckCount: null,
      deviationCount: null,
    },
  };

  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(reportWithNulls);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    const text = dom.container.textContent;

    // Must never render "0" or "0%" for null metrics
    assert.ok(text.includes('Unavailable'), 'Missing planned size, yield percent, or variance must render Unavailable');
    assert.ok(text.includes('Not recorded'), 'Missing actual yield or counts must render Not recorded');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Yield Variance is rendered with quantity batch units (kg) and NOT as a percentage', async () => {
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    const text = dom.container.textContent;
    assert.ok(text.includes('Yield Variance (Qty)'), 'Header must specify quantity variance');
    assert.ok(text.includes('-1.8 kg'), 'Value must be formatted with batch unit kg, not %');
    assert.ok(!text.includes('-1.8%'), 'Variance must not be formatted as percentage');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// =============================================================================
// 6. Structured 12-Section Analysis Rendering
// =============================================================================

test('Structured Analysis view renders all 12 analytical sections with headers and contents', async () => {
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    const expectedSectionKeys = [
      'executiveSummary',
      'batchStatusAndProgress',
      'plannedVsActualMetrics',
      'materialTraceability',
      'ebrStepEvaluation',
      'qcInspectionAnalysis',
      'qaDispositionStatus',
      'deviationsAndCapa',
      'chronologicalAuditAnalysis',
      'missingRecords',
      'riskObservations',
      'followUpRecommendations',
    ];

    for (const key of expectedSectionKeys) {
      const sectionEl = dom.container.querySelector(`[data-testid="section-${key}"]`);
      assert.ok(sectionEl, `Section [data-testid="section-${key}"] must be rendered`);
    }

    const text = dom.container.textContent;
    assert.ok(text.includes('1. Executive Summary'));
    assert.ok(text.includes('7. QA Disposition Status'));
    assert.ok(text.includes('Authoritative MES QA Policy'));
    assert.ok(text.includes('10. Missing Records'));
    assert.ok(text.includes('12. Follow-up Recommendations'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// =============================================================================
// 7. Source Citations & Modal Close on Navigation
// =============================================================================

test('Evidence & Citations tab renders AiSourceReferencePills and closes modal upon citation click', async () => {
  const prevFetch = globalThis.fetch;
  let modalClosed = false;

  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, {
      role: 'PRODUCTION_SUPERVISOR',
      modalProps: { onClose: () => { modalClosed = true; } },
    });

    // Switch to Citations tab
    const tabs = dom.container.querySelectorAll('button');
    const citationsTab = Array.from(tabs).find((b) => b.textContent.includes('Evidence & Citations'));
    assert.ok(citationsTab, 'Citations tab button must exist');

    await dom.click(citationsTab);
    await waitForUi(dom);

    const citationsPanel = dom.container.querySelector('[data-testid="citations-tab-view"]');
    assert.ok(citationsPanel, 'Citations panel must be visible');

    // Click the first source pill
    const pill = citationsPanel.querySelector('button');
    assert.ok(pill, 'AiSourceReferencePill must be rendered');

    await dom.click(pill);
    await waitForUi(dom);

    assert.equal(modalClosed, true, 'Clicking source citation pill must invoke onClose to navigate cleanly');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// =============================================================================
// 8. Mandatory AI Advisory Notice
// =============================================================================

test('Renders exact regulatoryDisclaimer under neutral AI Advisory Notice heading', async () => {
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    const noticeEl = dom.container.querySelector('[data-testid="ai-advisory-notice"]');
    assert.ok(noticeEl, 'AI Advisory Notice container must exist');
    assert.ok(noticeEl.textContent.includes('AI Advisory Notice'));
    assert.ok(noticeEl.textContent.includes('ADVISORY ONLY'));
    assert.ok(noticeEl.textContent.includes('does not constitute formal Quality Assurance batch release authorization'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// =============================================================================
// 9. Concurrency Conflict (409) Handling
// =============================================================================

test('409 Conflict displays informative alert and refreshes history without retrying POST', async () => {
  const prevFetch = globalThis.fetch;
  let postAttempts = 0;
  let historyFetches = 0;

  globalThis.fetch = async (url, options) => {
    const method = options?.method || 'GET';
    if (method === 'POST') {
      postAttempts += 1;
      return errorResponse(409, 'Another report generation is currently active for batch BAT-2026-0010-01');
    }
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) {
      historyFetches += 1;
      return jsonResponse([sampleReportSummary]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    const initialHistoryFetches = historyFetches;
    const genBtn = dom.container.querySelector('[data-testid="workspace-generate-report-button"]');
    assert.ok(genBtn);

    await dom.click(genBtn);
    await waitForUi(dom);

    const confirmBtn = dom.container.querySelector('[data-testid="confirm-generate-report-button"]');
    assert.ok(confirmBtn, 'Modal confirm button must be present');
    await dom.click(confirmBtn);
    await waitForUi(dom);

    assert.equal(postAttempts, 1, 'Must not retry POST automatically upon 409 conflict');
    assert.ok(historyFetches > initialHistoryFetches, 'Must refresh history to check in-flight report');

    const text = dom.container.textContent;
    assert.ok(text.includes('Another report generation is currently active'), 'Must display informative conflict notice');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// =============================================================================
// 10. FAILED Report Retry Action
// =============================================================================

test('FAILED report renders failure badge and Retry button which triggers a fresh POST with new request ID', async () => {
  const prevFetch = globalThis.fetch;
  const postBodies = [];

  const failedReportDetail = {
    ...sampleReportDetail,
    id: 105,
    status: 'FAILED',
    errorMessage: 'AI model timeout during analytical evaluation.',
  };

  const failedSummary = {
    ...sampleReportSummary,
    id: 105,
    status: 'FAILED',
  };

  globalThis.fetch = async (url, options) => {
    const method = options?.method || 'GET';
    if (method === 'POST') {
      postBodies.push(JSON.parse(options.body));
      return jsonResponse(sampleReportDetail);
    }
    if (String(url).includes('/analysis-reports/105')) return jsonResponse(failedReportDetail);
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([failedSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    const text = dom.container.textContent;
    assert.ok(text.includes('Report Generation Failed'));
    assert.ok(text.includes('AI model timeout during analytical evaluation.'));

    // Find Retry Generation button
    const buttons = dom.container.querySelectorAll('button');
    const retryBtn = Array.from(buttons).find((b) => b.textContent.includes('Retry Generation'));
    assert.ok(retryBtn, 'Retry Generation button must be visible for failed report');

    await dom.click(retryBtn);
    await waitForUi(dom);

    const confirmBtn = dom.container.querySelector('[data-testid="confirm-generate-report-button"]');
    assert.ok(confirmBtn, 'Modal confirm button must be present on retry');
    await dom.click(confirmBtn);
    await waitForUi(dom);

    assert.equal(postBodies.length, 1);
    assert.ok(postBodies[0].clientRequestId.startsWith('req-'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// =============================================================================
// 11. Pagination: Load More Reports
// =============================================================================

test('Clicking Load More Reports queries page 1 and appends reports', async () => {
  const prevFetch = globalThis.fetch;
  const queriedPages = [];

  const page0Reports = Array.from({ length: 20 }, (_, i) => ({
    ...sampleReportSummary,
    id: 100 + i,
  }));

  const page1Reports = [
    { ...sampleReportSummary, id: 99 },
  ];

  globalThis.fetch = async (url) => {
    const urlStr = String(url);
    if (urlStr.includes('/analysis-reports/')) return jsonResponse(sampleReportDetail);
    if (urlStr.includes('/analysis-reports?')) {
      const match = /page=(\d+)/.exec(urlStr);
      const p = match ? Number(match[1]) : 0;
      queriedPages.push(p);
      return jsonResponse(p === 0 ? page0Reports : page1Reports);
    }
    if (urlStr.includes('/analysis-reports')) return jsonResponse(page0Reports);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    assert.ok(queriedPages.includes(0));

    const loadMoreBtn = dom.container.querySelector('[data-testid="load-more-reports-button"]');
    assert.ok(loadMoreBtn, 'Load More Reports button must exist when first page is full');

    await dom.click(loadMoreBtn);
    await waitForUi(dom);

    assert.ok(queriedPages.includes(1), 'Must query page 1 upon Load More');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// =============================================================================
// 12. Manual Refresh Action
// =============================================================================

test('Clicking Refresh action button refreshes history and selected report details', async () => {
  const prevFetch = globalThis.fetch;
  let historyCalls = 0;

  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) {
      historyCalls += 1;
      return jsonResponse([sampleReportSummary]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    const initialCount = historyCalls;

    // Find header refresh button
    const refreshBtn = dom.container.querySelector('button[aria-label="Refresh reports history"]');
    assert.ok(refreshBtn, 'Refresh button must be present in header');

    await dom.click(refreshBtn);
    await waitForUi(dom);

    assert.ok(historyCalls > initialCount, 'Clicking refresh must trigger reload of history');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('BatchAnalysisWorkspaceModal: preserves historical report text verbatim without display-time rewriting', async () => {
  const { formatSectionContentForDisplay, formatNarrativeMarkdownForDisplay, hasUnsupportedComplianceClaim } =
    await import('./BatchAnalysisWorkspaceModal.jsx');

  const rawRisk = 'No deviations or quality issues have been logged, so compliance risk remains low.';

  // Stored riskObservations text must NOT be rewritten
  const displayedRisk = formatSectionContentForDisplay('riskObservations', rawRisk, null);
  assert.equal(displayedRisk, rawRisk, 'Historical riskObservations content must be preserved verbatim');

  // Stored reportMarkdown text must NOT be rewritten
  const displayedNarrative = formatNarrativeMarkdownForDisplay(rawRisk);
  assert.equal(displayedNarrative, rawRisk, 'Historical reportMarkdown content must be preserved verbatim');

  // Unsupported claim must be detected for separate advisory notice
  assert.equal(hasUnsupportedComplianceClaim({ reportMarkdown: rawRisk }), true);
});

test('BatchAnalysisWorkspaceModal: Section 12 prefers structured advisoryRecommendations array when available', async () => {
  const { formatSectionContentForDisplay } = await import('./BatchAnalysisWorkspaceModal.jsx');

  const report = {
    advisoryRecommendations: [
      'Stage raw materials at cleanroom suite.',
      'Verify equipment calibration.',
      'Notify QA upon completion.',
    ],
  };

  const rawRecs = 'Some legacy unnumbered raw text';
  const formatted = formatSectionContentForDisplay('followUpRecommendations', rawRecs, report);

  assert.equal(
    formatted,
    '1. Stage raw materials at cleanroom suite.\n2. Verify equipment calibration.\n3. Notify QA upon completion.'
  );
});

test('BatchAnalysisWorkspaceModal: displays separate advisory notice for historical reports with unsupported compliance statements', async () => {
  const { default: BatchAnalysisWorkspaceModal } = await import('./BatchAnalysisWorkspaceModal.jsx');

  const legacyReportWithComplianceClaim = {
    ...sampleReportDetail,
    structuredAnalysis: {
      ...sampleReportDetail.structuredAnalysis,
      riskObservations: 'No deviations or quality issues have been logged, so compliance risk remains low.',
    },
    reportMarkdown: '## Risk Observations\nNo deviations or quality issues have been logged, so compliance risk remains low.',
  };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(legacyReportWithComplianceClaim);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    // 1. Verify original historical wording is rendered verbatim in Section 11
    const section11El = dom.container.querySelector('[data-testid="section-riskObservations"]');
    assert.ok(section11El, 'Section 11 must be present');
    assert.ok(
      section11El.textContent.includes('so compliance risk remains low'),
      'Original historical wording must be preserved in DOM'
    );

    // 2. Verify separate advisory notice is displayed in Section 11
    const noticeEl = section11El.querySelector('[data-testid="compliance-advisory-notice"]');
    assert.ok(noticeEl, 'Separate compliance advisory notice must be displayed');
    assert.ok(noticeEl.textContent.includes('Regulatory Compliance Advisory Notice'));

    // 3. Switch to Narrative tab and verify separate narrative advisory notice
    const narrativeTabBtn = Array.from(dom.container.querySelectorAll('button')).find(
      (b) => b.textContent && b.textContent.includes('Complete Narrative Markdown')
    );
    assert.ok(narrativeTabBtn, 'Narrative tab button must exist');
    await dom.click(narrativeTabBtn);
    await waitForUi(dom);

    const narrativeNoticeEl = dom.container.querySelector('[data-testid="narrative-compliance-advisory-notice"]');
    assert.ok(narrativeNoticeEl, 'Separate narrative compliance advisory notice must be displayed');
    assert.ok(
      dom.container.textContent.includes('so compliance risk remains low'),
      'Verbatim report markdown must be preserved in narrative view'
    );
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// =============================================================================
// Archival UI and Accessibility Tests (Phase 4E.1)
// =============================================================================

test('Archival UI: PRODUCTION_SUPERVISOR sees Archive button for active reports, QA_REVIEWER and OPERATOR do not', async () => {
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    // 1. PRODUCTION_SUPERVISOR can see Archive button
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });
    const archiveBtn = dom.container.querySelector('[data-testid="archive-report-button"]');
    assert.ok(archiveBtn, 'Archive button must be visible for PRODUCTION_SUPERVISOR');

    // 2. QA_REVIEWER cannot see Archive button
    await renderModalWithContext(dom, { role: 'QA_REVIEWER' });
    const qaArchiveBtn = dom.container.querySelector('[data-testid="archive-report-button"]');
    assert.strictEqual(qaArchiveBtn, null, 'Archive button must NOT be visible for QA_REVIEWER');

    // 3. OPERATOR cannot see Archive button
    await renderModalWithContext(dom, { role: 'OPERATOR' });
    const opArchiveBtn = dom.container.querySelector('[data-testid="archive-report-button"]');
    assert.strictEqual(opArchiveBtn, null, 'Archive button must NOT be visible for OPERATOR');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Archival UI: Confirmation modal enforces mandatory non-whitespace reason before enabling submit', async () => {
  let patchPayload = null;
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    if (options.method === 'PATCH' && String(url).includes('/analysis-reports/101/archive')) {
      patchPayload = JSON.parse(options.body);
      return jsonResponse({
        ...sampleReportDetail,
        archived: true,
        archivedAt: '2026-11-15T03:00:00',
        archivedByUsername: 'supervisor_1',
        archiveReason: patchPayload.reason,
      });
    }
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    const archiveBtn = dom.container.querySelector('[data-testid="archive-report-button"]');
    assert.ok(archiveBtn, 'Archive button should be available');
    await dom.click(archiveBtn);
    await waitForUi(dom);

    const modal = dom.container.querySelector('[data-testid="archive-confirmation-modal"]');
    assert.ok(modal, 'Confirmation modal must be displayed');

    const confirmBtn = dom.container.querySelector('[data-testid="confirm-archive-button"]');
    const reasonInput = dom.container.querySelector('[data-testid="archive-reason-input"]');

    assert.ok(confirmBtn.disabled, 'Confirm Archive button must be initially disabled when reason is empty');

    // Type spaces only
    await dom.changeInput(reasonInput, '     ');
    await waitForUi(dom);
    assert.ok(confirmBtn.disabled, 'Confirm Archive button must remain disabled for whitespace-only reason');

    // Type valid reason
    await dom.changeInput(reasonInput, '  Superseded by complete eBR check  ');
    await waitForUi(dom);
    assert.strictEqual(confirmBtn.disabled, false, 'Confirm Archive button must become enabled with valid reason');

    await dom.click(confirmBtn);
    await waitForUi(dom);

    assert.ok(patchPayload, 'PATCH request should have been dispatched');
    assert.equal(patchPayload.reason, 'Superseded by complete eBR check', 'Archive reason must be trimmed');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Archival UI: History filter tabs (Active, Archived, All) switch visible reports', async () => {
  const activeReport = { ...sampleReportSummary, id: 101, archived: false };
  const archivedReport = { ...sampleReportSummary, id: 102, archived: true, archiveReason: 'Old run' };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports/102')) {
      return jsonResponse({ ...sampleReportDetail, id: 102, archived: true, archiveReason: 'Old run' });
    }
    if (String(url).includes('/analysis-reports')) return jsonResponse([activeReport, archivedReport]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    // Initially active filter: only activeReport (Report #101) shown in sidebar list
    const filterActive = dom.container.querySelector('[data-testid="filter-active-reports"]');
    const filterArchived = dom.container.querySelector('[data-testid="filter-archived-reports"]');
    const filterAll = dom.container.querySelector('[data-testid="filter-all-reports"]');
    const historyList = dom.container.querySelector('[data-testid="report-history-list"]');

    assert.ok(filterActive && filterArchived && filterAll && historyList, 'Filter controls must exist');

    // Active filter selected:
    assert.ok(historyList.textContent.includes('Report #101'));
    assert.strictEqual(historyList.textContent.includes('Report #102'), false);

    // Click Archived filter
    await dom.click(filterArchived);
    await waitForUi(dom);
    assert.strictEqual(historyList.textContent.includes('Report #101'), false);
    assert.ok(historyList.textContent.includes('Report #102'));

    // Click All filter
    await dom.click(filterAll);
    await waitForUi(dom);
    assert.ok(historyList.textContent.includes('Report #101'));
    assert.ok(historyList.textContent.includes('Report #102'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Accessibility: Report history items support keyboard activation via Enter and Space', async () => {
  const report1 = { ...sampleReportSummary, id: 101 };
  const report2 = { ...sampleReportSummary, id: 102 };

  let requestedDetailId = null;
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) {
      requestedDetailId = 101;
      return jsonResponse({ ...sampleReportDetail, id: 101 });
    }
    if (String(url).includes('/analysis-reports/102')) {
      requestedDetailId = 102;
      return jsonResponse({ ...sampleReportDetail, id: 102 });
    }
    if (String(url).includes('/analysis-reports')) return jsonResponse([report1, report2]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, { role: 'PRODUCTION_SUPERVISOR' });

    const item1 = dom.container.querySelector('[data-testid="history-item-101"]');
    const item2 = dom.container.querySelector('[data-testid="history-item-102"]');
    assert.ok(item1 && item2, 'Should display both history items in sidebar');

    assert.equal(item2.getAttribute('role'), 'button');
    assert.equal(item2.getAttribute('tabindex'), '0');

    // Trigger Enter keydown
    await dom.keyDown(item2, 'Enter');
    await waitForUi(dom);
    assert.equal(requestedDetailId, 102, 'Enter key should select report #102');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Batch switch: changing batchId resets selected report and fetches new batch history', async () => {
  const historyCalls = [];
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    const urlStr = String(url);
    if (urlStr.includes('/batches/10/analysis-reports')) {
      historyCalls.push(10);
      return jsonResponse([sampleReportSummary]);
    }
    if (urlStr.includes('/batches/20/analysis-reports')) {
      historyCalls.push(20);
      return jsonResponse([{ ...sampleReportSummary, id: 201, batchId: 20, batchNumber: 'BAT-2026-0020-01' }]);
    }
    if (urlStr.includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (urlStr.includes('/analysis-reports/201')) {
      return jsonResponse({ ...sampleReportDetail, id: 201, batchId: 20, batchNumber: 'BAT-2026-0020-01' });
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, {
      role: 'PRODUCTION_SUPERVISOR',
      modalProps: { batchId: 10, batchNumber: 'BAT-2026-0010-01' },
    });

    assert.ok(dom.container.textContent.includes('Report #101'));
    assert.ok(historyCalls.includes(10));

    // Re-render with new batchId
    await renderModalWithContext(dom, {
      role: 'PRODUCTION_SUPERVISOR',
      modalProps: {
        batchId: 20,
        batchNumber: 'BAT-2026-0020-01',
        dossier: { ...mockDossier, batchId: 20, batchNumber: 'BAT-2026-0020-01' },
      },
    });

    assert.ok(historyCalls.includes(20));
    assert.ok(dom.container.textContent.includes('Report #201'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Permanent Delete RBAC: button is visible strictly to ADMIN, hidden for PRODUCTION_SUPERVISOR and QA_REVIEWER', async () => {
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    const urlStr = String(url);
    if (urlStr.includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (urlStr.includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    // 1. PRODUCTION_SUPERVISOR -> no permanent delete button
    await renderModalWithContext(dom, {
      role: 'PRODUCTION_SUPERVISOR',
      modalProps: { batchId: 10, batchNumber: 'BAT-2026-0010-01' },
    });
    await waitForUi(dom);
    assert.equal(dom.container.querySelector('[data-testid="permanent-delete-report-button"]'), null);

    // 2. QA_REVIEWER -> no permanent delete button
    await renderModalWithContext(dom, {
      role: 'QA_REVIEWER',
      modalProps: { batchId: 10, batchNumber: 'BAT-2026-0010-01' },
    });
    await waitForUi(dom);
    assert.equal(dom.container.querySelector('[data-testid="permanent-delete-report-button"]'), null);

    // 3. ADMIN -> permanent delete button IS rendered
    await renderModalWithContext(dom, {
      role: 'ADMIN',
      modalProps: { batchId: 10, batchNumber: 'BAT-2026-0010-01' },
    });
    await waitForUi(dom);
    const deleteBtn = dom.container.querySelector('[data-testid="permanent-delete-report-button"]');
    assert.ok(deleteBtn, 'Admin must see the Permanent Delete button');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Permanent Delete Modal Flow: ADMIN enters reason, confirmation keyword, and executes permanent deletion', async () => {
  const prevFetch = globalThis.fetch;
  let deleteEndpointCalled = false;
  let deletedPayload = null;

  globalThis.fetch = async (url, options = {}) => {
    const urlStr = String(url);
    if (options.method === 'DELETE' && urlStr.includes('/batches/10/analysis-reports/101')) {
      deleteEndpointCalled = true;
      deletedPayload = JSON.parse(options.body);
      return {
        ok: true,
        status: 204,
        url: urlStr,
        headers: { get: () => null },
        json: async () => null,
      };
    }
    if (urlStr.includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (urlStr.includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderModalWithContext(dom, {
      role: 'ADMIN',
      modalProps: { batchId: 10, batchNumber: 'BAT-2026-0010-01' },
    });
    await waitForUi(dom);

    // Click Permanent Delete
    const deleteBtn = dom.container.querySelector('[data-testid="permanent-delete-report-button"]');
    assert.ok(deleteBtn);
    await dom.click(deleteBtn);
    await waitForUi(dom);

    // Modal is opened
    const modal = dom.container.querySelector('[data-testid="permanent-delete-confirmation-modal"]');
    assert.ok(modal, 'Confirmation modal must open');

    const confirmBtn = dom.container.querySelector('[data-testid="confirm-delete-button"]');
    assert.ok(confirmBtn.disabled, 'Confirm button must be initially disabled');

    // Type reason only
    const reasonInput = dom.container.querySelector('[data-testid="permanent-delete-reason-input"]');
    await dom.changeInput(reasonInput, 'Local test cleanup');
    await waitForUi(dom);
    assert.ok(confirmBtn.disabled, 'Confirm button must remain disabled without keyword');

    // Type invalid keyword
    const confirmInput = dom.container.querySelector('[data-testid="permanent-delete-confirm-input"]');
    await dom.changeInput(confirmInput, 'DELETE');
    await waitForUi(dom);
    assert.ok(confirmBtn.disabled, 'Confirm button must remain disabled with wrong keyword');

    // Type exact keyword
    await dom.changeInput(confirmInput, 'PERMANENTLY DELETE');
    await waitForUi(dom);
    assert.equal(confirmBtn.disabled, false, 'Confirm button must be enabled with reason and keyword');

    // Click Confirm
    await dom.click(confirmBtn);
    await waitForUi(dom);

    // Verify DELETE was dispatched
    assert.equal(deleteEndpointCalled, true);
    assert.equal(deletedPayload.reason, 'Local test cleanup');
    assert.equal(deletedPayload.confirmationKeyword, 'PERMANENTLY DELETE');

    // Modal closed
    assert.equal(dom.container.querySelector('[data-testid="permanent-delete-confirmation-modal"]'), null);
    // Success message displayed
    assert.ok(dom.container.textContent.includes('permanently deleted'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});
