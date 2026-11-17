import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { createDomEnvironment } from '../../scripts/dom-harness.mjs';
import { AuthContext } from '../context/AuthContext.jsx';
import { NavigationContext } from '../context/NavigationContext.jsx';
import BatchAnalysisWorkspaceModal from './BatchAnalysisWorkspaceModal.jsx';
import BatchAnalysisGenerateModal from './BatchAnalysisGenerateModal.jsx';
import {
  formatSectionContentForDisplay,
  formatNarrativeMarkdownForDisplay,
} from './BatchAnalysisWorkspace.jsx';
import {
  BATCH_ANALYSIS_MODES,
  FOCUS_AREAS,
  FOCUS_AREA_CATEGORIES,
  ALL_FOCUS_AREA_KEYS,
} from '../constants/batchAnalysisModes.js';

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
      timestamp: '2026-11-16T20:00:00+05:30',
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
  analysisMode: 'FULL',
  model: 'gpt-4o',
  createdAt: '2026-11-16T12:00:00',
  completedAt: '2026-11-16T12:00:05',
  archived: false,
};

const sampleReportDetail = {
  ...sampleReportSummary,
  clientRequestId: 'req-initial-12345',
  generatedByUsername: 'supervisor_jane',
  deterministicMetrics: {
    plannedBatchSize: 500.0,
    actualYield: 498.2,
    yieldPercent: 99.64,
    yieldVariance: -0.36,
    executionStepCount: 8,
    qcCheckCount: 5,
    deviationCount: 0,
  },
  structuredAnalysis: {
    executiveSummary: 'Batch executed in full compliance.',
    plannedVsActualMetrics: 'Target yield achieved.',
    ebrStepEvaluation: 'All 8 steps completed.',
    qcInspectionAnalysis: 'All 5 checks passed.',
    qaDispositionStatus: 'Authoritative QA Disposition: RELEASED',
    followUpRecommendations: 'Proceed to secondary packaging.',
  },
  reportMarkdown: '# Full Report\nAll clear.',
  sourceReferences: [],
  regulatoryDisclaimer: 'ADVISORY ONLY.',
};

async function waitForUi(dom, turns = 4) {
  for (let i = 0; i < turns; i += 1) {
    await dom.flush();
    await new Promise((r) => setTimeout(r, 20));
  }
}

async function renderWorkspace(dom, { role = 'PRODUCTION_SUPERVISOR', modalProps = {} } = {}) {
  const authValue = {
    user: { id: 1, username: 'supervisor_jane', role },
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
// Phase 4F.4.1 Tests — FULL and FOCUSED Batch Analysis Selection UI
// =============================================================================

test('Phase 4F.4.1 (1): Constants verify all 9 focus areas and 4 categories', () => {
  assert.equal(FOCUS_AREAS.length, 9, 'Must define exactly 9 focus areas');
  assert.equal(ALL_FOCUS_AREA_KEYS.length, 9);
  assert.equal(FOCUS_AREA_CATEGORIES.length, 4, 'Must organize into 4 categories');

  const expectedAreas = [
    'MANUFACTURING_YIELD',
    'MATERIAL_TRACEABILITY',
    'EBR_EXECUTION',
    'QC_INSPECTION',
    'QA_DISPOSITION',
    'DEVIATIONS_CAPA',
    'CHRONOLOGICAL_AUDIT',
    'MISSING_RECORDS',
    'RISK_ASSESSMENT',
  ];

  for (const area of expectedAreas) {
    assert.ok(ALL_FOCUS_AREA_KEYS.includes(area), `Must include focus area ${area}`);
  }
});

test('Phase 4F.4.1 (2): Clicking Generate New Report opens dialog without generating report immediately', async () => {
  const prevFetch = globalThis.fetch;
  const postCalls = [];

  globalThis.fetch = async (url, options) => {
    const method = options?.method || 'GET';
    if (method === 'POST') {
      postCalls.push(JSON.parse(options.body));
      return jsonResponse(sampleReportDetail);
    }
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderWorkspace(dom);

    const genBtn = dom.container.querySelector('[data-testid="workspace-generate-report-button"]');
    assert.ok(genBtn, 'Generate New Report button must exist');

    // Initially modal is closed
    assert.equal(
      dom.container.querySelector('[data-testid="batch-analysis-generate-modal"]'),
      null,
      'Generate modal must not be open initially'
    );

    // Click button to open dialog
    await dom.click(genBtn);
    await waitForUi(dom);

    // Modal dialog must now be visible
    const modal = dom.container.querySelector('[data-testid="batch-analysis-generate-modal"]');
    assert.ok(modal, 'Modal dialog must be open');
    assert.equal(postCalls.length, 0, 'No report generation must occur upon opening dialog');

    // Both modes must be available
    assert.ok(dom.container.querySelector('[data-testid="mode-full-radio"]'));
    assert.ok(dom.container.querySelector('[data-testid="mode-focused-radio"]'));

    // Cancel closes dialog without generating
    const cancelBtn = dom.container.querySelector('[data-testid="cancel-generate-report-button"]');
    assert.ok(cancelBtn);
    await dom.click(cancelBtn);
    await waitForUi(dom);

    assert.equal(
      dom.container.querySelector('[data-testid="batch-analysis-generate-modal"]'),
      null,
      'Modal must be closed after Cancel'
    );
    assert.equal(postCalls.length, 0, 'No POST request sent after cancel');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Phase 4F.4.1 (3): FULL mode submits analysisMode FULL without focusAreas', async () => {
  const prevFetch = globalThis.fetch;
  const postBodies = [];

  const createdReport = {
    ...sampleReportDetail,
    id: 102,
    analysisMode: 'FULL',
    clientRequestId: 'req-full-confirm-001',
  };

  globalThis.fetch = async (url, options) => {
    const method = options?.method || 'GET';
    if (method === 'POST') {
      postBodies.push(JSON.parse(options.body));
      return jsonResponse(createdReport);
    }
    if (String(url).includes('/analysis-reports/102')) return jsonResponse(createdReport);
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) {
      return jsonResponse([
        { ...sampleReportSummary, id: 102, analysisMode: 'FULL' },
        sampleReportSummary,
      ]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderWorkspace(dom);

    const genBtn = dom.container.querySelector('[data-testid="workspace-generate-report-button"]');
    await dom.click(genBtn);
    await waitForUi(dom);

    // FULL radio is checked by default
    const fullRadio = dom.container.querySelector('[data-testid="mode-full-radio"]');
    assert.ok(fullRadio);
    assert.equal(fullRadio.checked, true);

    // Confirm button
    const confirmBtn = dom.container.querySelector('[data-testid="confirm-generate-report-button"]');
    assert.ok(confirmBtn);
    assert.ok(confirmBtn.textContent.includes('Full Report'));

    await dom.click(confirmBtn);
    await waitForUi(dom);

    // Check payload
    assert.equal(postBodies.length, 1);
    assert.equal(postBodies[0].analysisMode, 'FULL');
    assert.equal(postBodies[0].focusAreas, undefined, 'FULL mode must NOT submit focus areas');
    assert.ok(postBodies[0].clientRequestId.startsWith('req-'));

    // Modal closes and new report is displayed
    assert.equal(dom.container.querySelector('[data-testid="batch-analysis-generate-modal"]'), null);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Phase 4F.4.1 (4): FOCUSED mode renders 9 areas, Select All / Clear All, and validation', async () => {
  const dom = await createDomEnvironment();
  try {
    let confirmedData = null;
    let closed = false;

    await dom.render(
      React.createElement(BatchAnalysisGenerateModal, {
        isOpen: true,
        onClose: () => {
          closed = true;
        },
        onConfirm: async (data) => {
          confirmedData = data;
        },
        batchNumber: 'BAT-2026-0010-01',
        productName: 'Amoxicillin 500mg',
      })
    );
    await waitForUi(dom);

    // Switch to FOCUSED mode
    const focusedRadio = dom.container.querySelector('[data-testid="mode-focused-radio"]');
    assert.ok(focusedRadio);
    await dom.click(focusedRadio);
    await waitForUi(dom);

    // Focused panel is visible
    const panel = dom.container.querySelector('[data-testid="focused-areas-selection-panel"]');
    assert.ok(panel, 'Focused areas selection panel must be visible');

    // All 9 checkboxes exist
    for (const key of ALL_FOCUS_AREA_KEYS) {
      const cb = dom.container.querySelector(`[data-testid="focus-area-checkbox-${key}"]`);
      assert.ok(cb, `Checkbox for ${key} must exist`);
    }

    // Summary says 9 of 9 selected initially
    const summary = dom.container.querySelector('[data-testid="selected-areas-summary"]');
    assert.ok(summary.textContent.includes('9 of 9'));

    // Test Clear All
    const clearAllBtn = dom.container.querySelector('[data-testid="clear-all-focus-areas-button"]');
    assert.ok(clearAllBtn);
    await dom.click(clearAllBtn);
    await waitForUi(dom);

    // Summary now says 0 of 9
    assert.ok(summary.textContent.includes('0 of 9'));

    // Warning is visible and confirm button is disabled
    const warning = dom.container.querySelector('[data-testid="no-areas-selected-warning"]');
    assert.ok(warning, 'Warning must be displayed when 0 areas are selected');

    const confirmBtn = dom.container.querySelector('[data-testid="confirm-generate-report-button"]');
    assert.ok(confirmBtn.disabled, 'Confirm button must be disabled when 0 areas selected');

    // Clicking confirm while disabled does NOT invoke onConfirm
    await dom.click(confirmBtn);
    await waitForUi(dom);
    assert.equal(confirmedData, null);

    // Test Select All
    const selectAllBtn = dom.container.querySelector('[data-testid="select-all-focus-areas-button"]');
    assert.ok(selectAllBtn);
    await dom.click(selectAllBtn);
    await waitForUi(dom);

    assert.ok(summary.textContent.includes('9 of 9'));
    assert.equal(confirmBtn.disabled, false);

    // Deselect all then select exactly two specific areas: MANUFACTURING_YIELD and QC_INSPECTION
    await dom.click(clearAllBtn);
    await waitForUi(dom);

    const yieldCb = dom.container.querySelector('[data-testid="focus-area-checkbox-MANUFACTURING_YIELD"]');
    const qcCb = dom.container.querySelector('[data-testid="focus-area-checkbox-QC_INSPECTION"]');
    assert.ok(yieldCb);
    assert.ok(qcCb);

    await dom.click(yieldCb);
    await waitForUi(dom);
    assert.ok(summary.textContent.includes('1 of 9'));
    assert.equal(confirmBtn.disabled, false);

    await dom.click(qcCb);
    await waitForUi(dom);
    assert.ok(summary.textContent.includes('2 of 9'));

    // Confirm submission
    await dom.click(confirmBtn);
    await waitForUi(dom);

    assert.ok(confirmedData);
    assert.equal(confirmedData.mode, 'FOCUSED');
    assert.deepEqual(confirmedData.focusAreas, ['MANUFACTURING_YIELD', 'QC_INSPECTION']);
  } finally {
    await dom.unmount?.();
  }
});

test('Phase 4F.4.1 (5): FOCUSED mode generation submits selected areas and displays focused report', async () => {
  const prevFetch = globalThis.fetch;
  const postBodies = [];

  const createdFocusedReport = {
    ...sampleReportDetail,
    id: 103,
    analysisMode: 'FOCUSED',
    focusAreas: ['MANUFACTURING_YIELD', 'DEVIATIONS_CAPA'],
    focusSummary: 'Manufacturing & Yield, Deviations & CAPA',
    clientRequestId: 'req-focused-confirm-002',
  };

  globalThis.fetch = async (url, options) => {
    const method = options?.method || 'GET';
    if (method === 'POST') {
      postBodies.push(JSON.parse(options.body));
      return jsonResponse(createdFocusedReport);
    }
    if (String(url).includes('/analysis-reports/103')) return jsonResponse(createdFocusedReport);
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) {
      return jsonResponse([
        { ...sampleReportSummary, id: 103, analysisMode: 'FOCUSED', focusSummary: 'Manufacturing & Yield, Deviations & CAPA' },
        sampleReportSummary,
      ]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderWorkspace(dom);

    const genBtn = dom.container.querySelector('[data-testid="workspace-generate-report-button"]');
    await dom.click(genBtn);
    await waitForUi(dom);

    // Switch to FOCUSED mode
    const focusedRadio = dom.container.querySelector('[data-testid="mode-focused-radio"]');
    await dom.click(focusedRadio);
    await waitForUi(dom);

    // Clear all and select single area
    const clearAllBtn = dom.container.querySelector('[data-testid="clear-all-focus-areas-button"]');
    await dom.click(clearAllBtn);
    await waitForUi(dom);

    const yieldCb = dom.container.querySelector('[data-testid="focus-area-checkbox-MANUFACTURING_YIELD"]');
    const devCb = dom.container.querySelector('[data-testid="focus-area-checkbox-DEVIATIONS_CAPA"]');
    await dom.click(yieldCb);
    await dom.click(devCb);
    await waitForUi(dom);

    const confirmBtn = dom.container.querySelector('[data-testid="confirm-generate-report-button"]');
    await dom.click(confirmBtn);
    await waitForUi(dom);

    // Check submitted POST payload
    assert.equal(postBodies.length, 1);
    assert.equal(postBodies[0].analysisMode, 'FOCUSED');
    assert.deepEqual(postBodies[0].focusAreas, ['MANUFACTURING_YIELD', 'DEVIATIONS_CAPA']);
    assert.ok(postBodies[0].clientRequestId.startsWith('req-'));

    // Modal is closed
    assert.equal(dom.container.querySelector('[data-testid="batch-analysis-generate-modal"]'), null);

    // Focused badge and summary banner are rendered
    const focusedBadge = dom.container.querySelector('[data-testid="report-focused-badge"]');
    assert.ok(focusedBadge, 'FOCUSED badge must be rendered for focused report');

    const summaryBanner = dom.container.querySelector('[data-testid="report-focus-summary-banner"]');
    assert.ok(summaryBanner, 'Focus summary banner must be displayed');
    assert.ok(summaryBanner.textContent.includes('Manufacturing & Yield, Deviations & CAPA'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});


test('Phase 4F.4.1 (6): API failure in modal displays error message without closing dialog', async () => {
  const prevFetch = globalThis.fetch;

  globalThis.fetch = async (url, options) => {
    const method = options?.method || 'GET';
    if (method === 'POST') {
      return errorResponse(502, 'AI provider rate limit exceeded. Please wait 30 seconds.');
    }
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderWorkspace(dom);

    const genBtn = dom.container.querySelector('[data-testid="workspace-generate-report-button"]');
    await dom.click(genBtn);
    await waitForUi(dom);

    const confirmBtn = dom.container.querySelector('[data-testid="confirm-generate-report-button"]');
    await dom.click(confirmBtn);
    await waitForUi(dom);

    // Modal remains open and shows error alert
    const errorAlert = dom.container.querySelector('[data-testid="generate-modal-error"]');
    assert.ok(errorAlert, 'Error alert must be visible inside modal');
    assert.ok(errorAlert.textContent.includes('AI provider rate limit exceeded') || errorAlert.textContent.includes('AI service is temporarily unavailable'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Phase 4F.4.2 (1): FOCUSED report with MANUFACTURING_YIELD renders only relevant sections and excludes empty placeholders & unrelated group headings', async () => {
  const focusedReport7 = {
    id: 107,
    batchId: 10,
    batchNumber: 'BAT-2026-0010-01',
    status: 'COMPLETED',
    analysisMode: 'FOCUSED',
    focusAreas: ['MANUFACTURING_YIELD'],
    focusSummary: 'Manufacturing Yield',
    model: 'gpt-4o',
    createdAt: '2026-11-16T14:00:00',
    completedAt: '2026-11-16T14:00:05',
    archived: false,
    clientRequestId: 'req-report-7',
    generatedByUsername: 'supervisor_jane',
    deterministicMetrics: {
      plannedBatchSize: 500.0,
      actualYield: null,
      yieldPercent: null,
      yieldVariance: null,
      executionStepCount: 0,
      qcCheckCount: 0,
      deviationCount: 0,
    },
    structuredAnalysis: {
      executiveSummary: 'Focused summary of manufacturing yield.',
      plannedVsActualMetrics: 'Target batch size is 500 kg. Production has not commenced.',
      followUpRecommendations: '1. Initiate batch dispensing.\n2. Verify eBR steps.',
      batchStatusAndProgress: null,
      materialTraceability: null,
      ebrStepEvaluation: null,
      qcInspectionAnalysis: null,
      qaDispositionStatus: null,
      deviationsAndCapa: null,
      chronologicalAuditAnalysis: null,
      missingRecords: null,
      riskObservations: null,
    },
    reportMarkdown: '# Focused Report\nSummary content.',
    sourceReferences: [],
    regulatoryDisclaimer: 'ADVISORY ONLY.',
  };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/107')) return jsonResponse(focusedReport7);
    if (String(url).includes('/analysis-reports')) {
      return jsonResponse([
        {
          id: 107,
          batchId: 10,
          batchNumber: 'BAT-2026-0010-01',
          status: 'COMPLETED',
          analysisMode: 'FOCUSED',
          focusAreas: ['MANUFACTURING_YIELD'],
          focusSummary: 'Manufacturing Yield',
          createdAt: '2026-11-16T14:00:00',
          completedAt: '2026-11-16T14:00:05',
          archived: false,
        },
      ]);
    }
    return jsonResponse({});
  };

  const plannedDossier = { ...mockDossier, batchStatus: 'PLANNED', actualYield: null };
  const dom = await createDomEnvironment();
  try {
    await renderWorkspace(dom, { modalProps: { dossier: plannedDossier } });

    // Tab title should reflect focused analysis
    const tabStructured = dom.container.querySelector('[data-testid="tab-structured"]');
    assert.ok(tabStructured);
    assert.equal(tabStructured.textContent.trim(), 'Structured Focused Analysis');

    // Only relevant sections must be present
    assert.ok(dom.container.querySelector('[data-testid="section-executiveSummary"]'), 'Executive summary must be displayed');
    assert.ok(dom.container.querySelector('[data-testid="section-plannedVsActualMetrics"]'), 'Planned vs Actual metrics must be displayed');
    assert.ok(dom.container.querySelector('[data-testid="section-followUpRecommendations"]'), 'Follow-up recommendations must be displayed');

    // Unrelated sections must NOT be in the DOM
    assert.equal(dom.container.querySelector('[data-testid="section-batchStatusAndProgress"]'), null);
    assert.equal(dom.container.querySelector('[data-testid="section-materialTraceability"]'), null);
    assert.equal(dom.container.querySelector('[data-testid="section-ebrStepEvaluation"]'), null);
    assert.equal(dom.container.querySelector('[data-testid="section-qcInspectionAnalysis"]'), null);
    assert.equal(dom.container.querySelector('[data-testid="section-qaDispositionStatus"]'), null);
    assert.equal(dom.container.querySelector('[data-testid="section-deviationsAndCapa"]'), null);
    assert.equal(dom.container.querySelector('[data-testid="section-chronologicalAuditAnalysis"]'), null);
    assert.equal(dom.container.querySelector('[data-testid="section-missingRecords"]'), null);
    assert.equal(dom.container.querySelector('[data-testid="section-riskObservations"]'), null);

    // Group headings: Group 1 (manufacturing) and Group 4 (risk) present; Group 2 (execution) and Group 3 (quality) absent
    assert.ok(dom.container.querySelector('[data-testid="section-group-manufacturing"]'), 'Group 1 must be rendered');
    assert.ok(dom.container.querySelector('[data-testid="section-group-risk"]'), 'Group 4 must be rendered');
    assert.equal(dom.container.querySelector('[data-testid="section-group-execution"]'), null, 'Group 2 must NOT be rendered');
    assert.equal(dom.container.querySelector('[data-testid="section-group-quality"]'), null, 'Group 3 must NOT be rendered');

    // Must NOT contain empty placeholder text
    const structuredContainer = dom.container.querySelector('[data-testid="structured-analysis-sections"]');
    assert.ok(!structuredContainer.textContent.includes('No findings or observations recorded for this section.'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Phase 4F.4.2 (2): FOCUSED report with multi-area selection renders union without duplicates', async () => {
  const multiFocusReport = {
    id: 108,
    batchId: 10,
    batchNumber: 'BAT-2026-0010-01',
    status: 'COMPLETED',
    analysisMode: 'FOCUSED',
    focusAreas: ['QC_INSPECTION', 'DEVIATIONS_CAPA'],
    focusSummary: 'QC Inspection, Deviations & CAPA',
    model: 'gpt-4o',
    createdAt: '2026-11-16T15:00:00',
    completedAt: '2026-11-16T15:00:05',
    archived: false,
    clientRequestId: 'req-report-8',
    generatedByUsername: 'supervisor_jane',
    deterministicMetrics: {
      plannedBatchSize: 500.0,
      actualYield: 490.0,
      yieldPercent: 98.0,
      yieldVariance: -10.0,
      executionStepCount: 5,
      qcCheckCount: 3,
      deviationCount: 1,
    },
    structuredAnalysis: {
      executiveSummary: 'QC and Deviation evaluation.',
      qcInspectionAnalysis: '3 QC tests executed. 2 passed, 1 pending.',
      deviationsAndCapa: '1 deviation logged for temperature excursion.',
      followUpRecommendations: '1. Resolve open deviation.\n2. Complete pending QC check.',
      plannedVsActualMetrics: null,
      batchStatusAndProgress: null,
      materialTraceability: null,
      ebrStepEvaluation: null,
      qaDispositionStatus: null,
      chronologicalAuditAnalysis: null,
      missingRecords: null,
      riskObservations: null,
    },
    reportMarkdown: '# Multi Focus Report',
    sourceReferences: [],
    regulatoryDisclaimer: 'ADVISORY ONLY.',
  };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/108')) return jsonResponse(multiFocusReport);
    if (String(url).includes('/analysis-reports')) return jsonResponse([multiFocusReport]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderWorkspace(dom, mockDossier);

    // Relevant sections are present
    assert.ok(dom.container.querySelector('[data-testid="section-executiveSummary"]'));
    assert.ok(dom.container.querySelector('[data-testid="section-qcInspectionAnalysis"]'));
    assert.ok(dom.container.querySelector('[data-testid="section-deviationsAndCapa"]'));
    assert.ok(dom.container.querySelector('[data-testid="section-followUpRecommendations"]'));

    // Verify union without duplicates: exactly one card of each
    assert.equal(dom.container.querySelectorAll('[data-testid="section-executiveSummary"]').length, 1);
    assert.equal(dom.container.querySelectorAll('[data-testid="section-qcInspectionAnalysis"]').length, 1);
    assert.equal(dom.container.querySelectorAll('[data-testid="section-deviationsAndCapa"]').length, 1);
    assert.equal(dom.container.querySelectorAll('[data-testid="section-followUpRecommendations"]').length, 1);

    // Unselected sections are NOT rendered
    assert.equal(dom.container.querySelector('[data-testid="section-plannedVsActualMetrics"]'), null);
    assert.equal(dom.container.querySelector('[data-testid="section-ebrStepEvaluation"]'), null);

    // Group 1 has no relevant sections with content (executiveSummary is in group 1, so group 1 is present!)
    assert.ok(dom.container.querySelector('[data-testid="section-group-manufacturing"]'), 'Group 1 present because executiveSummary is there');
    assert.ok(dom.container.querySelector('[data-testid="section-group-execution"]'), 'Group 2 present because qcInspectionAnalysis is there');
    assert.ok(dom.container.querySelector('[data-testid="section-group-quality"]'), 'Group 3 present because deviationsAndCapa is there');
    assert.ok(dom.container.querySelector('[data-testid="section-group-risk"]'), 'Group 4 present because followUpRecommendations is there');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Phase 4F.4.2 (3): FULL report preserves complete 12-section layout and all 4 group headings', async () => {
  const fullReport = {
    ...sampleReportDetail,
    analysisMode: 'FULL',
    focusAreas: [],
    focusSummary: null,
  };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(fullReport);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderWorkspace(dom, mockDossier);

    // Tab title should reflect 12-section analysis
    const tabStructured = dom.container.querySelector('[data-testid="tab-structured"]');
    assert.ok(tabStructured);
    assert.equal(tabStructured.textContent.trim(), 'Structured 12-Section Analysis');

    // All 4 groups must exist
    assert.ok(dom.container.querySelector('[data-testid="section-group-manufacturing"]'));
    assert.ok(dom.container.querySelector('[data-testid="section-group-execution"]'));
    assert.ok(dom.container.querySelector('[data-testid="section-group-quality"]'));
    assert.ok(dom.container.querySelector('[data-testid="section-group-risk"]'));

    // All 12 sections must exist
    const expectedSections = [
      'executiveSummary', 'batchStatusAndProgress', 'plannedVsActualMetrics',
      'materialTraceability', 'ebrStepEvaluation', 'qcInspectionAnalysis',
      'qaDispositionStatus', 'deviationsAndCapa', 'chronologicalAuditAnalysis',
      'missingRecords', 'riskObservations', 'followUpRecommendations',
    ];
    for (const key of expectedSections) {
      assert.ok(dom.container.querySelector(`[data-testid="section-${key}"]`), `Section ${key} must exist in FULL mode`);
    }
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Phase 4F.4.2 (4): Deterministic KPI cards display "Not yet expected" for PLANNED batch', async () => {
  const plannedReport = {
    ...sampleReportDetail,
    batchStatus: 'PLANNED',
    deterministicMetrics: {
      plannedBatchSize: 500.0,
      actualYield: null,
      yieldPercent: null,
      yieldVariance: null,
      executionStepCount: 0,
      qcCheckCount: 0,
      deviationCount: 0,
    },
  };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(plannedReport);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const plannedDossier = { ...mockDossier, batchStatus: 'PLANNED', actualYield: null };
  const dom = await createDomEnvironment();
  try {
    await renderWorkspace(dom, { modalProps: { dossier: plannedDossier } });

    const actualYieldKpi = dom.container.querySelector('[data-testid="kpi-actual-yield"]');
    assert.ok(actualYieldKpi);
    assert.equal(actualYieldKpi.textContent.trim(), 'Not yet expected');

    const yieldPercentKpi = dom.container.querySelector('[data-testid="kpi-yield-percent"]');
    assert.ok(yieldPercentKpi);
    assert.equal(yieldPercentKpi.textContent.trim(), 'Not yet expected');

    const yieldVarianceKpi = dom.container.querySelector('[data-testid="kpi-yield-variance"]');
    assert.ok(yieldVarianceKpi);
    assert.equal(yieldVarianceKpi.textContent.trim(), 'Not yet expected');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Phase 4F.4.2 (5): Deterministic KPI cards display "In progress" for IN_PROGRESS batch and "Not recorded" for COMPLETED batch', async () => {
  const unrecordedReport = {
    ...sampleReportDetail,
    deterministicMetrics: {
      plannedBatchSize: 500.0,
      actualYield: null,
      yieldPercent: null,
      yieldVariance: null,
      executionStepCount: 3,
      qcCheckCount: 1,
      deviationCount: 0,
    },
  };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(unrecordedReport);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  // Test IN_PROGRESS
  const inProgressDossier = { ...mockDossier, batchStatus: 'IN_PROGRESS', actualYield: null };
  const dom1 = await createDomEnvironment();
  try {
    await renderWorkspace(dom1, { modalProps: { dossier: inProgressDossier } });

    const actualYieldKpi = dom1.container.querySelector('[data-testid="kpi-actual-yield"]');
    assert.equal(actualYieldKpi.textContent.trim(), 'In progress');

    const yieldPercentKpi = dom1.container.querySelector('[data-testid="kpi-yield-percent"]');
    assert.equal(yieldPercentKpi.textContent.trim(), 'In progress');

    const yieldVarianceKpi = dom1.container.querySelector('[data-testid="kpi-yield-variance"]');
    assert.equal(yieldVarianceKpi.textContent.trim(), 'In progress');
  } finally {
    await dom1.unmount?.();
  }

  // Test COMPLETED without yield
  const completedDossier = { ...mockDossier, batchStatus: 'COMPLETED', actualYield: null };
  const dom2 = await createDomEnvironment();
  try {
    await renderWorkspace(dom2, { modalProps: { dossier: completedDossier } });

    const actualYieldKpi = dom2.container.querySelector('[data-testid="kpi-actual-yield"]');
    assert.equal(actualYieldKpi.textContent.trim(), 'Not recorded');

    const yieldPercentKpi = dom2.container.querySelector('[data-testid="kpi-yield-percent"]');
    assert.equal(yieldPercentKpi.textContent.trim(), 'Unavailable');

    const yieldVarianceKpi = dom2.container.querySelector('[data-testid="kpi-yield-variance"]');
    assert.equal(yieldVarianceKpi.textContent.trim(), 'Unavailable');
  } finally {
    globalThis.fetch = prevFetch;
    await dom2.unmount?.();
  }
});

test('Phase 4F.4.3.1: Historical reports display their stored Markdown and structured analysis verbatim', () => {
  const legacyReport = {
    batchNumber: 'BAT-2026-0010-01',
    batchStatus: 'PLANNED',
    analysisMode: 'FOCUSED',
    focusAreas: ['MANUFACTURING_YIELD'],
  };

  // 1. Structured Section: Planned vs Actual Metrics is preserved verbatim
  const rawMetrics = 'Planned Batch Size: 250.0000 kg\n' +
    'Actual Yield: Not recorded (manufacturing not yet started)\n' +
    'Yield Percentage: Not recorded\n' +
    'Yield Variance: Not recorded\n' +
    'Production Duration: Not started';

  const displayedMetrics = formatSectionContentForDisplay('plannedVsActualMetrics', rawMetrics, legacyReport);
  assert.equal(displayedMetrics, rawMetrics, 'Historical structured analysis must be displayed verbatim');

  // 2. Structured Section: Executive Summary is preserved verbatim
  const rawExec = 'All manufacturing yield metrics — including actual yield quantity, yield percentage, and yield variance — are unrecorded due to the batch not yet having progressed beyond the planning stage.';
  const displayedExec = formatSectionContentForDisplay('executiveSummary', rawExec, legacyReport);
  assert.equal(displayedExec, rawExec, 'Historical executive summary must be displayed verbatim');

  // 3. Complete Narrative Markdown is preserved verbatim
  const legacyMarkdown = '## 2. Planned vs Actual Production Metrics\n' +
    '- **Planned Batch Size:** 250.0000 kg\n' +
    '- **Actual Yield:** Unrecorded / In progress\n' +
    '- **Yield Percentage:** N/A\n' +
    '- **Yield Variance:** N/A\n\n' +
    '## 3. Recommended Follow-Up Actions\n' +
    '1. Initiate manufacturing process to capture actual yield data.\n' +
    '2. Ensure real-time yield tracking is enabled.\n' +
    '3. Recalculate yield percentage and variance once actual yield is available.';

  const displayedMarkdown = formatNarrativeMarkdownForDisplay(legacyMarkdown);
  assert.equal(displayedMarkdown, legacyMarkdown, 'Historical markdown narrative must be displayed verbatim without rewriting');
});

// =============================================================================
// Phase 4F.4.4 Tests — Card Selection Reliability and Scope Clarification Notice
// =============================================================================

test('Phase 4F.4.4 (1): Card surfaces (title, badge, description, container) are reliably selectable and toggle modes', async () => {
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/analysis-reports/101')) return jsonResponse(sampleReportDetail);
    if (String(url).includes('/analysis-reports')) return jsonResponse([sampleReportSummary]);
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderWorkspace(dom);

    const genBtn = dom.container.querySelector('[data-testid="workspace-generate-report-button"]');
    assert.ok(genBtn);
    await dom.click(genBtn);
    await waitForUi(dom);

    // 1. Initial state is FULL
    const fullRadio = dom.container.querySelector('[data-testid="mode-full-radio"]');
    const focusedRadio = dom.container.querySelector('[data-testid="mode-focused-radio"]');
    assert.equal(fullRadio.checked, true, 'FULL radio must be checked by default');
    assert.equal(focusedRadio.checked, false, 'FOCUSED radio must not be checked initially');
    assert.ok(dom.container.querySelector('[data-testid="mode-full-active-indicator"]'), 'FULL active indicator must be visible');
    assert.equal(dom.container.querySelector('[data-testid="mode-focused-active-indicator"]'), null);
    assert.equal(dom.container.querySelector('[data-testid="focused-areas-selection-panel"]'), null);

    // 2. Click FOCUSED description -> switches to FOCUSED
    const focusedDesc = dom.container.querySelector('[data-testid="mode-focused-description"]');
    assert.ok(focusedDesc, 'FOCUSED description must exist');
    await dom.click(focusedDesc);
    await waitForUi(dom);

    assert.equal(focusedRadio.checked, true, 'FOCUSED radio must be checked after clicking description');
    assert.equal(fullRadio.checked, false, 'FULL radio must be unchecked');
    assert.ok(dom.container.querySelector('[data-testid="mode-focused-active-indicator"]'), 'FOCUSED active indicator must appear');
    assert.equal(dom.container.querySelector('[data-testid="mode-full-active-indicator"]'), null);
    assert.ok(dom.container.querySelector('[data-testid="focused-areas-selection-panel"]'), 'FOCUSED panel must appear');

    // 3. Verify scope clarification notice in FOCUSED mode
    const notice = dom.container.querySelector('[data-testid="focused-mode-scope-clarification"]');
    assert.ok(notice, 'FOCUSED scope clarification notice must be visible');
    assert.ok(
      notice.textContent.includes('Selecting all 9 focus areas still generates a FOCUSED report. To include all 12 dossier sections, select FULL Analysis.'),
      'Notice must explain the distinction between all 9 focus areas and Full Analysis'
    );

    // 4. Click FULL badge -> switches back to FULL
    const fullBadge = dom.container.querySelector('[data-testid="mode-full-badge"]');
    assert.ok(fullBadge, 'FULL badge must exist');
    await dom.click(fullBadge);
    await waitForUi(dom);

    assert.equal(fullRadio.checked, true, 'FULL radio must be checked after clicking badge');
    assert.equal(focusedRadio.checked, false, 'FOCUSED radio must be unchecked');
    assert.ok(dom.container.querySelector('[data-testid="mode-full-active-indicator"]'));
    assert.equal(dom.container.querySelector('[data-testid="focused-areas-selection-panel"]'), null);

    // 5. Click FOCUSED badge -> switches to FOCUSED
    const focusedBadge = dom.container.querySelector('[data-testid="mode-focused-badge"]');
    assert.ok(focusedBadge, 'FOCUSED badge must exist');
    await dom.click(focusedBadge);
    await waitForUi(dom);

    assert.equal(focusedRadio.checked, true, 'FOCUSED radio must be checked after clicking badge');
    assert.equal(fullRadio.checked, false);

    // 6. Click FULL title -> switches to FULL
    const fullTitle = dom.container.querySelector('[data-testid="mode-full-title"]');
    assert.ok(fullTitle, 'FULL title must exist');
    await dom.click(fullTitle);
    await waitForUi(dom);

    assert.equal(fullRadio.checked, true, 'FULL radio must be checked after clicking title');
    assert.equal(focusedRadio.checked, false);

    // 7. Click FOCUSED title -> switches to FOCUSED
    const focusedTitle = dom.container.querySelector('[data-testid="mode-focused-title"]');
    assert.ok(focusedTitle, 'FOCUSED title must exist');
    await dom.click(focusedTitle);
    await waitForUi(dom);

    assert.equal(focusedRadio.checked, true, 'FOCUSED radio must be checked after clicking title');
    assert.equal(fullRadio.checked, false);

    // 8. Click FULL description -> switches to FULL
    const fullDesc = dom.container.querySelector('[data-testid="mode-full-description"]');
    assert.ok(fullDesc, 'FULL description must exist');
    await dom.click(fullDesc);
    await waitForUi(dom);

    assert.equal(fullRadio.checked, true, 'FULL radio must be checked after clicking description');
    assert.equal(focusedRadio.checked, false);

    // 9. Click FOCUSED card container -> switches to FOCUSED
    const focusedCard = dom.container.querySelector('[data-testid="mode-card-focused"]');
    assert.ok(focusedCard, 'FOCUSED card container must exist');
    await dom.click(focusedCard);
    await waitForUi(dom);

    assert.equal(focusedRadio.checked, true, 'FOCUSED radio must be checked after clicking card container');
    assert.equal(fullRadio.checked, false);

    // 10. Click FULL card container -> switches to FULL
    const fullCard = dom.container.querySelector('[data-testid="mode-card-full"]');
    assert.ok(fullCard, 'FULL card container must exist');
    await dom.click(fullCard);
    await waitForUi(dom);

    assert.equal(fullRadio.checked, true, 'FULL radio must be checked after clicking card container');
    assert.equal(focusedRadio.checked, false);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Phase 4F.4.4 (2): Repeated mode switching and exact payload verification for FULL vs FOCUSED', async () => {
  const prevFetch = globalThis.fetch;
  const postBodies = [];

  let reportCounter = 200;
  globalThis.fetch = async (url, options) => {
    const method = options?.method || 'GET';
    if (method === 'POST') {
      const body = JSON.parse(options.body);
      postBodies.push(body);
      reportCounter += 1;
      const created = {
        ...sampleReportDetail,
        id: reportCounter,
        analysisMode: body.analysisMode,
        focusAreas: body.focusAreas || null,
        clientRequestId: body.clientRequestId,
      };
      return jsonResponse(created);
    }
    if (String(url).includes('/analysis-reports/') && !String(url).endsWith('/analysis-reports')) {
      return jsonResponse({
        ...sampleReportDetail,
        id: reportCounter,
        analysisMode: postBodies[postBodies.length - 1]?.analysisMode || 'FULL',
        focusAreas: postBodies[postBodies.length - 1]?.focusAreas || null,
      });
    }
    if (String(url).includes('/analysis-reports')) {
      return jsonResponse([
        { ...sampleReportSummary, id: reportCounter },
      ]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    await renderWorkspace(dom);

    // --- Scenario A: Switch to FOCUSED, select areas, switch BACK to FULL via card click, submit ---
    const genBtn = dom.container.querySelector('[data-testid="workspace-generate-report-button"]');
    await dom.click(genBtn);
    await waitForUi(dom);

    // Switch to FOCUSED by clicking card description
    const focusedDesc = dom.container.querySelector('[data-testid="mode-focused-description"]');
    await dom.click(focusedDesc);
    await waitForUi(dom);

    // Select all in FOCUSED mode
    const selectAllBtn = dom.container.querySelector('[data-testid="select-all-focus-areas-button"]');
    await dom.click(selectAllBtn);
    await waitForUi(dom);

    // User decides to switch BACK to FULL mode by clicking the FULL card description
    const fullDesc = dom.container.querySelector('[data-testid="mode-full-description"]');
    await dom.click(fullDesc);
    await waitForUi(dom);

    // Verify FULL mode is active
    const fullRadio = dom.container.querySelector('[data-testid="mode-full-radio"]');
    assert.equal(fullRadio.checked, true);
    const confirmBtnA = dom.container.querySelector('[data-testid="confirm-generate-report-button"]');
    assert.ok(confirmBtnA.textContent.includes('Full Report (12 Sections)'));

    // Confirm generation
    await dom.click(confirmBtnA);
    await waitForUi(dom);

    // Verify payload strictly sends FULL without focusAreas
    assert.equal(postBodies.length, 1);
    assert.equal(postBodies[0].analysisMode, 'FULL');
    assert.equal(postBodies[0].focusAreas, undefined, 'FULL mode must NOT include focusAreas');

    // --- Scenario B: Open modal again, select FOCUSED with all 9 areas, confirm ---
    await dom.click(genBtn);
    await waitForUi(dom);

    // Switch to FOCUSED by clicking FOCUSED card container
    const focusedCard = dom.container.querySelector('[data-testid="mode-card-focused"]');
    await dom.click(focusedCard);
    await waitForUi(dom);

    // Ensure all 9 areas selected
    const selectAllBtnB = dom.container.querySelector('[data-testid="select-all-focus-areas-button"]');
    await dom.click(selectAllBtnB);
    await waitForUi(dom);

    const confirmBtnB = dom.container.querySelector('[data-testid="confirm-generate-report-button"]');
    assert.ok(confirmBtnB.textContent.includes('Generate Focused Report (9 Areas)'));

    // Confirm generation
    await dom.click(confirmBtnB);
    await waitForUi(dom);

    // Verify payload strictly sends FOCUSED with all 9 areas
    assert.equal(postBodies.length, 2);
    assert.equal(postBodies[1].analysisMode, 'FOCUSED');
    assert.equal(Array.isArray(postBodies[1].focusAreas), true);
    assert.equal(postBodies[1].focusAreas.length, 9);
    assert.deepEqual(postBodies[1].focusAreas, ALL_FOCUS_AREA_KEYS);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});
