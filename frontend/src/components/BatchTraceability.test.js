import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { createDomEnvironment } from '../../scripts/dom-harness.mjs';

function jsonResponse(body, status = 200, url = 'http://localhost:8080/api/batches/traceability/BAT-2026-0010-01') {
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

const mockDossier = {
  batchId: 12,
  batchNumber: 'BAT-2026-0010-01',
  qrCode: 'QR-PT-BAT-2026-0010-01',
  orderId: 10,
  orderNumber: 'MO-2026-0010',
  orderStatus: 'RELEASED',
  productId: 2,
  productCode: 'PRD-TAB-002',
  productName: 'Amoxicillin 500mg',
  recipeId: 5,
  recipeCode: 'RCP-AMX-001',
  recipeVersion: 2,
  batchSize: 250.0,
  batchUnit: 'kg',
  actualYield: 248.5,
  batchStatus: 'COMPLETED',
  qaStatus: 'RELEASED',
  manufactureDate: '2026-11-12',
  expiryDate: '2028-11-12',
  startTime: '2026-11-12T08:00:00',
  endTime: '2026-11-12T16:00:00',
  completenessPercent: 100,
  completenessStatus: 'COMPLETE',
  completenessHighlights: [
    'Manufacturing Order & Recipe configuration linked',
    '1 material issue transaction(s) recorded against batch',
    'All 2 eBR execution step(s) completed',
    'All 2 QC inspection(s) passed',
    'QA disposition recorded: RELEASED',
  ],
  plannedQuantity: 250.0,
  targetBatchCount: 1,
  orderPlannedStartDate: '2026-11-12',
  orderPlannedEndDate: '2026-11-13',
  materialRequirements: [
    {
      recipeMaterialName: 'Amoxicillin Trihydrate API',
      rawMaterialCode: 'RM-API-001',
      totalRequiredQuantity: 200.0,
      issuedQuantity: 200.0,
      remainingRequiredQuantity: 0.0,
      recipeUnit: 'kg',
      fulfilled: true,
    },
  ],
  materialMovements: [
    {
      id: 2,
      movementNumber: 'ISS-2026-DFA80F0340',
      materialCode: 'RM-API-001',
      materialName: 'Amoxicillin Trihydrate Active',
      lotNumber: 'LOT-AMX-2026-01',
      warehouseCode: 'WH-PUNE-01',
      warehouseName: 'Main Pune Warehouse',
      locationCode: 'WH-PUNE-01-A01-R01-B01',
      zoneName: 'API Storage',
      quantityDelta: -200.0,
      unit: 'kg',
      supplierName: 'PharmaSupply Corp',
      supplierReference: 'COA-AMX-2026-99',
      performedBy: 'admin',
      occurredAt: '2026-11-12T10:05:00',
    },
  ],
  executionSteps: [
    {
      id: 1,
      stepNumber: 1,
      operationName: 'Granulation',
      instructions: 'Blend API and binder',
      status: 'COMPLETED',
      actualValue: '120 RPM',
      observations: 'Granules uniform',
      executedBy: 'operator.floor',
      startedAt: '2026-11-12T08:30:00',
      completedAt: '2026-11-12T10:00:00',
    },
  ],
  qualityChecks: [
    {
      id: 10,
      testName: 'Assay Potency',
      specification: '98.0 - 102.0 %',
      measuredValue: '99.8',
      unit: '%',
      result: 'PASS',
      required: true,
      inspectorName: 'qc.analyst',
      checkedAt: '2026-11-12T14:00:00',
    },
  ],
  qaReviews: [
    {
      id: 5,
      decisionStatus: 'RELEASED',
      reviewerName: 'qa.reviewer',
      rationale: 'All critical parameters verified compliant.',
      reviewedAt: '2026-11-12T16:30:00',
      passedChecksCount: 1,
      failedChecksCount: 0,
      totalChecksCount: 1,
    },
  ],
  deviations: [
    {
      id: 1,
      deviationNumber: 'DEV-2026-0001',
      title: 'Minor temperature excursion during blending',
      category: 'PROCESS_PARAMETER',
      severity: 'MINOR',
      status: 'CLOSED',
      description: 'Temperature rose 1C above set point for 3 mins.',
      rootCauseAnalysis: 'Cooling jacket valve delay.',
      reportedBy: 'operator.floor',
      reportedAt: '2026-11-12T09:00:00',
      capaActions: [
        {
          id: 101,
          capaNumber: 'CAPA-2026-0001',
          title: 'Recalibrate jacket valve actuator',
          capaType: 'CORRECTIVE',
          status: 'COMPLETED',
          ownerName: 'Maintenance Lead',
          dueDate: '2026-11-15',
        },
      ],
    },
  ],
  auditHistory: [
    {
      id: 33,
      action: 'BATCH_CREATED',
      entityType: 'BATCH',
      entityReference: 'BAT-2026-0010-01',
      actorName: 'admin',
      occurredAt: '2026-11-12T08:00:00',
      reason: 'Batch initiated from MO-2026-0010',
    },
  ],
};

test('BatchTraceability renders all 8 sections (A through H) when dossier is loaded', async () => {
  const { default: BatchTraceability } = await import('./BatchTraceability.jsx');
  const dom = await createDomEnvironment('http://localhost:5173/#/traceability?batchNumber=BAT-2026-0010-01');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.includes('/api/batches/traceability/')) {
      return jsonResponse(mockDossier, 200, url);
    }
    return jsonResponse({}, 404, url);
  };

  try {
    await dom.render(React.createElement(BatchTraceability, { initialBatchNumber: 'BAT-2026-0010-01' }));

    const text = dom.container.textContent;

    // Section A: Overview & Completeness
    assert.ok(text.includes('BAT-2026-0010-01'), 'Batch number rendered');
    assert.ok(text.includes('QR-PT-BAT-2026-0010-01'), 'QR Code rendered');
    assert.ok(text.includes('100%'), 'Traceability completeness rendered');
    assert.ok(text.includes('COMPLETE'), 'Completeness status rendered');
    assert.ok(text.includes('Amoxicillin 500mg'), 'Product name rendered');

    // Section B: Manufacturing Traceability
    assert.ok(text.includes('MO-2026-0010'), 'Order number rendered');
    assert.ok(text.includes('RCP-AMX-001'), 'Recipe code rendered');

    // Section C: Raw Material Traceability
    assert.ok(text.includes('Amoxicillin Trihydrate API'), 'Recipe material name rendered');
    assert.ok(text.includes('LOT-AMX-2026-01'), 'Consumed Lot number rendered');
    assert.ok(text.includes('API Storage'), 'Storage Zone rendered');
    assert.ok(text.includes('COA-AMX-2026-99'), 'COA reference rendered');

    // Section D: eBR Execution Steps
    assert.ok(text.includes('Granulation'), 'Execution step operation name rendered');
    assert.ok(text.includes('120 RPM'), 'Recorded actual value rendered');

    // Section E: Quality Control
    assert.ok(text.includes('Assay Potency'), 'QC test name rendered');
    assert.ok(text.includes('99.8'), 'QC measured value rendered');
    assert.ok(text.includes('PASS'), 'QC result rendered');

    // Section F: Quality Assurance
    assert.ok(text.includes('qa.reviewer'), 'QA reviewer name rendered');
    assert.ok(text.includes('All critical parameters verified compliant.'), 'QA rationale rendered');

    // Section G: Deviations & CAPA
    assert.ok(text.includes('DEV-2026-0001'), 'Deviation number rendered');
    assert.ok(text.includes('CAPA-2026-0001'), 'Linked CAPA number rendered');

    // Section H: Audit History
    assert.ok(text.includes('BATCH_CREATED'), 'Audit action rendered');
  } finally {
    globalThis.fetch = prevFetch;
  }
});

test('BatchTraceability handles batch not found error gracefully', async () => {
  const { default: BatchTraceability } = await import('./BatchTraceability.jsx');
  const dom = await createDomEnvironment('http://localhost:5173/#/traceability');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    return jsonResponse({ message: 'Batch not found with identifier: UNKNOWN-BATCH' }, 404, url);
  };

  try {
    await dom.render(React.createElement(BatchTraceability, { initialBatchNumber: 'UNKNOWN-BATCH' }));

    const text = dom.container.textContent;
    assert.ok(text.includes('UNKNOWN-BATCH'), 'Error references the unknown batch');
  } finally {
    globalThis.fetch = prevFetch;
  }
});

test('BatchQrModal renders QR metadata, image, and action buttons', async () => {
  const { default: BatchQrModal } = await import('./BatchQrModal.jsx');
  const dom = await createDomEnvironment('http://localhost:5173/');

  const mockQrDto = {
    batchId: 12,
    batchNumber: 'BAT-2026-0010-01',
    qrCode: 'QR-PT-BAT-2026-0010-01',
    dossierUrl: '#/traceability?batchNumber=BAT-2026-0010-01',
    qrImageBase64: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAARgAAAE...',
    orderNumber: 'MO-2026-0010',
    productName: 'Amoxicillin 500mg',
  };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    return jsonResponse(mockQrDto, 200, url);
  };

  try {
    await dom.render(
      React.createElement(BatchQrModal, {
        isOpen: true,
        batchId: 12,
        batchNumber: 'BAT-2026-0010-01',
        onClose: () => {},
      })
    );

    const text = dom.container.textContent;
    assert.ok(text.includes('BAT-2026-0010-01'), 'Modal renders batch number');
    assert.ok(text.includes('QR-PT-BAT-2026-0010-01'), 'Modal renders QR code identifier');
    assert.ok(text.includes('Print QR'), 'Modal renders Print button');
    assert.ok(text.includes('Download PNG'), 'Modal renders Download button');
    assert.ok(text.includes('Open Traceability Dossier'), 'Modal renders Open Dossier button');
  } finally {
    globalThis.fetch = prevFetch;
  }
});

test('BatchTraceability displays clear error and does not mount video preview when BarcodeDetector is unavailable', async () => {
  const { default: BatchTraceability } = await import('./BatchTraceability.jsx');
  const dom = await createDomEnvironment('http://localhost:5173/#/traceability');

  const prevNavigator = globalThis.navigator;
  const prevBarcodeDetector = window.BarcodeDetector;
  delete window.BarcodeDetector;
  delete globalThis.BarcodeDetector;

  const mockNav = {
    ...prevNavigator,
    mediaDevices: {
      getUserMedia: async () => ({
        getTracks: () => [],
        getVideoTracks: () => [],
      }),
    },
  };
  globalThis.navigator = mockNav;
  window.navigator = mockNav;

  try {
    await dom.render(React.createElement(BatchTraceability, {}));

    const scanBtn = Array.from(dom.container.querySelectorAll('button')).find((b) =>
      b.textContent.includes('Scan QR')
    );
    assert.ok(scanBtn, 'Scan QR button exists');

    await dom.click(scanBtn);
    await dom.flush();

    const text = dom.container.textContent;
    assert.ok(
      text.includes('BarcodeDetector API unavailable') || text.includes('BarcodeDetector is not available'),
      'Clear error alert displayed when BarcodeDetector is missing'
    );
    assert.strictEqual(
      dom.container.querySelector('video'),
      null,
      'Video element is not mounted when scanner is unsupported (no black preview left)'
    );
  } finally {
    globalThis.navigator = prevNavigator;
    window.navigator = prevNavigator;
    if (prevBarcodeDetector !== undefined) {
      window.BarcodeDetector = prevBarcodeDetector;
      globalThis.BarcodeDetector = prevBarcodeDetector;
    } else {
      delete window.BarcodeDetector;
      delete globalThis.BarcodeDetector;
    }
  }
});

test('BatchTraceability assigns srcObject to video element upon camera start, falls back constraints, and cleans up tracks on stop', async () => {
  const { default: BatchTraceability } = await import('./BatchTraceability.jsx');
  const dom = await createDomEnvironment('http://localhost:5173/#/traceability');

  let trackStopped = false;
  const mockTrack = {
    stop: () => {
      trackStopped = true;
    },
    onended: null,
  };

  const mockStream = {
    getTracks: () => [mockTrack],
    getVideoTracks: () => [mockTrack],
  };

  const constraintsReceived = [];
  const prevNavigator = globalThis.navigator;
  const prevBarcodeDetector = window.BarcodeDetector;

  const mockNav = {
    ...prevNavigator,
    mediaDevices: {
      getUserMedia: async (constraints) => {
        constraintsReceived.push(constraints);
        // Simulate overconstrained on first attempt to verify constraint fallback
        if (constraints?.video?.facingMode) {
          const err = new Error('Overconstrained');
          err.name = 'OverconstrainedError';
          throw err;
        }
        return mockStream;
      },
    },
  };
  globalThis.navigator = mockNav;
  window.navigator = mockNav;

  class MockBarcodeDetector {
    constructor() {}
    static async getSupportedFormats() {
      return ['qr_code'];
    }
    async detect() {
      return [];
    }
  }
  window.BarcodeDetector = MockBarcodeDetector;
  globalThis.BarcodeDetector = MockBarcodeDetector;

  try {
    await dom.render(React.createElement(BatchTraceability, {}));

    const scanBtn = Array.from(dom.container.querySelectorAll('button')).find((b) =>
      b.textContent.includes('Scan QR')
    );
    assert.ok(scanBtn, 'Scan QR button found');

    await dom.click(scanBtn);
    await dom.flush();

    // Verify constraints fallback: first environment, then fallback to video: true
    assert.strictEqual(constraintsReceived.length, 2, 'Attempted initial constraints then fallback');
    assert.deepStrictEqual(constraintsReceived[0]?.video?.facingMode, { ideal: 'environment' });
    assert.strictEqual(constraintsReceived[1]?.video, true);

    // Verify video element mounted and srcObject is properly assigned
    const video = dom.container.querySelector('video');
    assert.ok(video, 'Video element mounted in the DOM');
    assert.strictEqual(video.srcObject, mockStream, 'video.srcObject is assigned the active stream (no black preview)');

    // Verify Stop Camera button is now displayed
    const stopBtn = Array.from(dom.container.querySelectorAll('button')).find((b) =>
      b.textContent.includes('Stop Camera')
    );
    assert.ok(stopBtn, 'Stop Camera button displayed while active');

    // Click Stop Camera and verify cleanup
    await dom.click(stopBtn);
    await dom.flush();

    assert.strictEqual(trackStopped, true, 'Camera tracks cleanly stopped');
    assert.strictEqual(dom.container.querySelector('video'), null, 'Video element unmounted on stop');
  } finally {
    globalThis.navigator = prevNavigator;
    window.navigator = prevNavigator;
    if (prevBarcodeDetector !== undefined) {
      window.BarcodeDetector = prevBarcodeDetector;
      globalThis.BarcodeDetector = prevBarcodeDetector;
    } else {
      delete window.BarcodeDetector;
      delete globalThis.BarcodeDetector;
    }
  }
});

test('BatchTraceability handles camera permission denial without leaving a black preview', async () => {
  const { default: BatchTraceability } = await import('./BatchTraceability.jsx');
  const dom = await createDomEnvironment('http://localhost:5173/#/traceability');

  const prevNavigator = globalThis.navigator;
  const prevBarcodeDetector = window.BarcodeDetector;

  const mockNav = {
    ...prevNavigator,
    mediaDevices: {
      getUserMedia: async () => {
        const err = new Error('Permission denied');
        err.name = 'NotAllowedError';
        throw err;
      },
    },
  };
  globalThis.navigator = mockNav;
  window.navigator = mockNav;

  class MockBarcodeDetector {
    constructor() {}
    static async getSupportedFormats() {
      return ['qr_code'];
    }
    async detect() {
      return [];
    }
  }
  window.BarcodeDetector = MockBarcodeDetector;
  globalThis.BarcodeDetector = MockBarcodeDetector;

  try {
    await dom.render(React.createElement(BatchTraceability, {}));

    const scanBtn = Array.from(dom.container.querySelectorAll('button')).find((b) =>
      b.textContent.includes('Scan QR')
    );
    await dom.click(scanBtn);
    await dom.flush();

    const text = dom.container.textContent;
    assert.ok(text.includes('Camera permission was denied'), 'Clear permission denial error displayed');
    assert.strictEqual(dom.container.querySelector('video'), null, 'Video element not displayed on error');
  } finally {
    globalThis.navigator = prevNavigator;
    window.navigator = prevNavigator;
    if (prevBarcodeDetector !== undefined) {
      window.BarcodeDetector = prevBarcodeDetector;
      globalThis.BarcodeDetector = prevBarcodeDetector;
    } else {
      delete window.BarcodeDetector;
      delete globalThis.BarcodeDetector;
    }
  }
});

