import test from 'node:test';
import assert from 'node:assert/strict';
import { setAuthToken, clearAuthToken } from './apiClient.js';
import {
  generateBatchAnalysisReport,
  listBatchAnalysisReports,
  getBatchAnalysisReportDetails,
  archiveBatchAnalysisReport,
  permanentDeleteBatchAnalysisReport,
  generateClientRequestId,
  validateNumericId,
} from './batchAnalysisApi.js';

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

function errorResponse(status, message, body = null) {
  return {
    ok: false,
    status,
    url: 'http://localhost:8080/api',
    headers: {
      get: (name) => (name.toLowerCase() === 'content-type' ? 'application/json' : null),
    },
    json: async () => body || {
      status,
      message,
      timestamp: '2026-11-15T02:00:00+05:30',
    },
  };
}

const sampleReportResponse = {
  id: 101,
  batchId: 42,
  batchNumber: 'BAT-2026-0042-01',
  status: 'COMPLETED',
  model: 'gpt-4',
  clientRequestId: 'req-test-client-12345',
  generatedByUsername: 'supervisor_1',
  createdAt: '2026-11-15T02:00:00',
  completedAt: '2026-11-15T02:00:08',
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
    executiveSummary: 'Batch execution completed within specifications.',
  },
  reportMarkdown: '# Batch Analysis Report\n\nExecution was successful.',
  sourceReferences: [
    {
      recordType: 'BATCH',
      recordIdentifier: 'BAT-2026-0042-01',
      title: 'BAT-2026-0042-01 (COMPLETED)',
      module: 'Production Execution',
    },
  ],
  regulatoryDisclaimer: 'ADVISORY ONLY: ...',
};

const sampleHistoryList = [
  {
    id: 101,
    batchId: 42,
    batchNumber: 'BAT-2026-0042-01',
    status: 'COMPLETED',
    model: 'gpt-4',
    clientRequestId: 'req-test-client-12345',
    generatedByUsername: 'supervisor_1',
    createdAt: '2026-11-15T02:00:00',
    completedAt: '2026-11-15T02:00:08',
    yieldPercent: 99.64,
    deviationCount: 0,
  },
];

// =============================================================================
// 1. generateBatchAnalysisReport tests
// =============================================================================

test('generateBatchAnalysisReport posts to /api/batches/{batchId}/analysis-reports with valid batchId', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  setAuthToken('test-jwt-token');

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse(sampleReportResponse);
  };

  try {
    const report = await generateBatchAnalysisReport(42, { clientRequestId: 'req-custom-client-12345' });

    assert.equal(captured.length, 1);
    assert.match(captured[0].url, /\/api\/batches\/42\/analysis-reports$/);
    assert.equal(captured[0].options.method, 'POST');
    assert.equal(captured[0].options.headers.Authorization, 'Bearer test-jwt-token');
    assert.equal(captured[0].options.headers['Content-Type'], 'application/json');

    const parsedBody = JSON.parse(captured[0].options.body);
    assert.deepEqual(Object.keys(parsedBody), ['clientRequestId']);
    assert.equal(parsedBody.clientRequestId, 'req-custom-client-12345');

    assert.equal(report.id, 101);
    assert.equal(report.batchNumber, 'BAT-2026-0042-01');
    assert.equal(report.status, 'COMPLETED');
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('generateBatchAnalysisReport generates clientRequestId automatically when omitted', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse(sampleReportResponse);
  };

  try {
    await generateBatchAnalysisReport(42);

    assert.equal(captured.length, 1);
    const parsedBody = JSON.parse(captured[0].options.body);

    assert.ok(parsedBody.clientRequestId && typeof parsedBody.clientRequestId === 'string');
    assert.ok(parsedBody.clientRequestId.startsWith('req-'));
    assert.ok(parsedBody.clientRequestId.length >= 8 && parsedBody.clientRequestId.length <= 64);
    assert.deepEqual(Object.keys(parsedBody), ['clientRequestId']);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('generateBatchAnalysisReport generates unique request IDs across separate calls', async () => {
  const prevFetch = globalThis.fetch;
  const requestIds = [];

  globalThis.fetch = async (url, options) => {
    const body = JSON.parse(options.body);
    requestIds.push(body.clientRequestId);
    return jsonResponse(sampleReportResponse);
  };

  try {
    await generateBatchAnalysisReport(42);
    await generateBatchAnalysisReport(42);

    assert.equal(requestIds.length, 2);
    assert.notEqual(requestIds[0], requestIds[1]);
    assert.ok(requestIds[0].length >= 8 && requestIds[0].length <= 64);
    assert.ok(requestIds[1].length >= 8 && requestIds[1].length <= 64);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('generateBatchAnalysisReport strictly never includes supervisorNotes or extra properties', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse(sampleReportResponse);
  };

  try {
    await generateBatchAnalysisReport(42, { clientRequestId: 'req-12345678-test' });

    assert.equal(captured.length, 1);
    const parsedBody = JSON.parse(captured[0].options.body);
    assert.equal(parsedBody.supervisorNotes, undefined);
    assert.equal(Object.prototype.hasOwnProperty.call(parsedBody, 'supervisorNotes'), false);
    assert.deepEqual(Object.keys(parsedBody), ['clientRequestId']);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('generateBatchAnalysisReport rejects invalid custom clientRequestId length', async () => {
  await assert.rejects(
    () => generateBatchAnalysisReport(42, { clientRequestId: 'short' }),
    /clientRequestId must be between 8 and 64 characters/
  );

  const longId = 'a'.repeat(65);
  await assert.rejects(
    () => generateBatchAnalysisReport(42, { clientRequestId: longId }),
    /clientRequestId must be between 8 and 64 characters/
  );
});

test('generateBatchAnalysisReport with analysisMode FULL sends analysisMode and no focusAreas', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse(sampleReportResponse);
  };

  try {
    await generateBatchAnalysisReport(42, {
      clientRequestId: 'req-full-mode-12345',
      analysisMode: 'FULL',
      focusAreas: ['MANUFACTURING_YIELD'], // should be stripped in FULL mode
    });

    assert.equal(captured.length, 1);
    const parsedBody = JSON.parse(captured[0].options.body);
    assert.equal(parsedBody.clientRequestId, 'req-full-mode-12345');
    assert.equal(parsedBody.analysisMode, 'FULL');
    assert.equal(parsedBody.focusAreas, undefined);
    assert.deepEqual(Object.keys(parsedBody), ['clientRequestId', 'analysisMode']);
  } finally {
    globalThis.fetch = prevFetch;
  }
});

test('generateBatchAnalysisReport with analysisMode FOCUSED sends focusAreas array', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse(sampleReportResponse);
  };

  try {
    await generateBatchAnalysisReport(42, {
      clientRequestId: 'req-focused-mode-12345',
      analysisMode: 'FOCUSED',
      focusAreas: ['MANUFACTURING_YIELD', 'QC_INSPECTION'],
    });

    assert.equal(captured.length, 1);
    const parsedBody = JSON.parse(captured[0].options.body);
    assert.equal(parsedBody.clientRequestId, 'req-focused-mode-12345');
    assert.equal(parsedBody.analysisMode, 'FOCUSED');
    assert.deepEqual(parsedBody.focusAreas, ['MANUFACTURING_YIELD', 'QC_INSPECTION']);
  } finally {
    globalThis.fetch = prevFetch;
  }
});

test('generateBatchAnalysisReport with analysisMode FOCUSED rejects empty focusAreas without calling fetch', async () => {
  const prevFetch = globalThis.fetch;
  let fetchCalled = false;

  globalThis.fetch = async () => {
    fetchCalled = true;
    return jsonResponse(sampleReportResponse);
  };

  try {
    await assert.rejects(
      () => generateBatchAnalysisReport(42, {
        clientRequestId: 'req-focused-empty-123',
        analysisMode: 'FOCUSED',
        focusAreas: [],
      }),
      /At least one focus area is required for FOCUSED analysis mode/
    );

    await assert.rejects(
      () => generateBatchAnalysisReport(42, {
        clientRequestId: 'req-focused-empty-456',
        analysisMode: 'FOCUSED',
      }),
      /At least one focus area is required for FOCUSED analysis mode/
    );

    assert.equal(fetchCalled, false);
  } finally {
    globalThis.fetch = prevFetch;
  }
});

// =============================================================================
// 2. listBatchAnalysisReports tests
// =============================================================================

test('listBatchAnalysisReports queries GET with default pagination (page=0, size=20)', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  setAuthToken('test-list-token');

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse(sampleHistoryList);
  };

  try {
    const list = await listBatchAnalysisReports(42);

    assert.equal(captured.length, 1);
    assert.match(captured[0].url, /\/api\/batches\/42\/analysis-reports\?page=0&size=20$/);
    assert.equal(captured[0].options.method, 'GET');
    assert.equal(captured[0].options.headers.Authorization, 'Bearer test-list-token');
    assert.ok(Array.isArray(list));
    assert.equal(list.length, 1);
    assert.equal(list[0].id, 101);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('listBatchAnalysisReports respects custom pagination and clamps size to 100', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse(sampleHistoryList);
  };

  try {
    await listBatchAnalysisReports(42, { page: 3, size: 50 });
    assert.match(captured[0].url, /\/api\/batches\/42\/analysis-reports\?page=3&size=50$/);

    // Clamps size to 100 maximum
    await listBatchAnalysisReports(42, { page: 1, size: 250 });
    assert.match(captured[1].url, /\/api\/batches\/42\/analysis-reports\?page=1&size=100$/);

    // Clamps negative page to 0
    await listBatchAnalysisReports(42, { page: -5, size: 10 });
    assert.match(captured[2].url, /\/api\/batches\/42\/analysis-reports\?page=0&size=10$/);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('listBatchAnalysisReports returns backend direct JSON array and rejects non-array shapes', async () => {
  const prevFetch = globalThis.fetch;

  // Returns array directly
  globalThis.fetch = async () => jsonResponse([]);
  try {
    const emptyList = await listBatchAnalysisReports(42);
    assert.deepEqual(emptyList, []);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }

  // Reject unexpected Spring Page object envelope (do not pretend it is a Page or silently absorb)
  globalThis.fetch = async () => jsonResponse({ content: sampleHistoryList, totalPages: 1 });
  try {
    await assert.rejects(
      () => listBatchAnalysisReports(42),
      /Unexpected response format: expected an array of report summaries/
    );
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

// =============================================================================
// 3. getBatchAnalysisReportDetails tests
// =============================================================================

test('getBatchAnalysisReportDetails queries GET /api/batches/{batchId}/analysis-reports/{reportId}', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  setAuthToken('test-detail-token');

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse(sampleReportResponse);
  };

  try {
    const report = await getBatchAnalysisReportDetails(42, 101);

    assert.equal(captured.length, 1);
    assert.match(captured[0].url, /\/api\/batches\/42\/analysis-reports\/101$/);
    assert.equal(captured[0].options.method, 'GET');
    assert.equal(captured[0].options.headers.Authorization, 'Bearer test-detail-token');
    assert.equal(report.id, 101);
    assert.equal(report.batchId, 42);
    assert.equal(report.structuredAnalysis.executiveSummary, 'Batch execution completed within specifications.');
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('getBatchAnalysisReportDetails rejects non-object or array responses', async () => {
  const prevFetch = globalThis.fetch;

  globalThis.fetch = async () => jsonResponse([sampleReportResponse]);
  try {
    await assert.rejects(
      () => getBatchAnalysisReportDetails(42, 101),
      /Unexpected response format: expected a report detail object/
    );
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

// =============================================================================
// 4. Batch ID and Report ID validation tests
// =============================================================================

test('validateNumericId enforces positive integer values', () => {
  assert.equal(validateNumericId(10, 'batch ID'), '10');
  assert.equal(validateNumericId('42', 'batch ID'), '42');
  assert.equal(validateNumericId('  99  ', 'batch ID'), '99');

  const invalidInputs = [null, undefined, '', '   ', 'abc', -5, 0, 1.5, '1.5', {}, [], NaN, Infinity];
  for (const input of invalidInputs) {
    assert.throws(
      () => validateNumericId(input, 'batch ID'),
      /Valid numeric batch ID is required/,
      `Input ${String(input)} should have failed validation`
    );
  }
});

test('generateBatchAnalysisReport rejects invalid batchId without calling fetch', async () => {
  const prevFetch = globalThis.fetch;
  let fetchCalled = false;
  globalThis.fetch = async () => {
    fetchCalled = true;
    return jsonResponse({});
  };

  try {
    await assert.rejects(() => generateBatchAnalysisReport(null), /Valid numeric batch ID is required/);
    await assert.rejects(() => generateBatchAnalysisReport(''), /Valid numeric batch ID is required/);
    await assert.rejects(() => generateBatchAnalysisReport('invalid-id'), /Valid numeric batch ID is required/);
    await assert.rejects(() => generateBatchAnalysisReport(0), /Valid numeric batch ID is required/);
    assert.equal(fetchCalled, false);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('listBatchAnalysisReports rejects invalid batchId without calling fetch', async () => {
  const prevFetch = globalThis.fetch;
  let fetchCalled = false;
  globalThis.fetch = async () => {
    fetchCalled = true;
    return jsonResponse([]);
  };

  try {
    await assert.rejects(() => listBatchAnalysisReports(undefined), /Valid numeric batch ID is required/);
    await assert.rejects(() => listBatchAnalysisReports('abc'), /Valid numeric batch ID is required/);
    assert.equal(fetchCalled, false);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('getBatchAnalysisReportDetails rejects invalid batchId or reportId without calling fetch', async () => {
  const prevFetch = globalThis.fetch;
  let fetchCalled = false;
  globalThis.fetch = async () => {
    fetchCalled = true;
    return jsonResponse({});
  };

  try {
    await assert.rejects(() => getBatchAnalysisReportDetails('invalid', 101), /Valid numeric batch ID is required/);
    await assert.rejects(() => getBatchAnalysisReportDetails(42, null), /Valid numeric report ID is required/);
    await assert.rejects(() => getBatchAnalysisReportDetails(42, 'xyz'), /Valid numeric report ID is required/);
    assert.equal(fetchCalled, false);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

// =============================================================================
// 5. Backend HTTP Error Propagation tests (401, 403, 404, 409, 502)
// =============================================================================

test('HTTP 401 Unauthorized propagates error status and message', async () => {
  const prevFetch = globalThis.fetch;
  setAuthToken('expired-token');

  globalThis.fetch = async () => errorResponse(401, 'Your session has expired or authentication failed. Please sign in again.');

  try {
    await assert.rejects(
      () => generateBatchAnalysisReport(42),
      (err) => {
        assert.equal(err.status, 401);
        assert.equal(err.isUnauthorized, true);
        assert.ok(err.message.includes('Session expired') || err.message.includes('session has expired'));
        return true;
      }
    );
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('HTTP 403 Forbidden propagates error status and message', async () => {
  const prevFetch = globalThis.fetch;
  setAuthToken('operator-token');

  globalThis.fetch = async () => errorResponse(403, 'Access is denied. Role PRODUCTION_SUPERVISOR required.');

  try {
    await assert.rejects(
      () => generateBatchAnalysisReport(42),
      (err) => {
        assert.equal(err.status, 403);
        assert.match(err.message, /Access is denied/);
        return true;
      }
    );
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('HTTP 404 Not Found propagates error status and message', async () => {
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async () => errorResponse(404, 'Batch not found with id: 999');

  try {
    await assert.rejects(
      () => getBatchAnalysisReportDetails(999, 101),
      (err) => {
        assert.equal(err.status, 404);
        assert.match(err.message, /Batch not found with id: 999/);
        return true;
      }
    );
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('HTTP 409 Conflict (concurrency collision) propagates cleanly', async () => {
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async () => errorResponse(409, 'Another report generation is currently active for batch BAT-001');

  try {
    await assert.rejects(
      () => generateBatchAnalysisReport(42),
      (err) => {
        assert.equal(err.status, 409);
        assert.match(err.message, /Another report generation is currently active/);
        return true;
      }
    );
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('HTTP 502 Bad Gateway (AI compliance failure) propagates cleanly', async () => {
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async () => errorResponse(502, 'AI batch analysis report generation failed output compliance validation.');

  try {
    await assert.rejects(
      () => generateBatchAnalysisReport(42),
      (err) => {
        assert.equal(err.status, 502);
        assert.match(err.message, /failed output compliance validation/);
        return true;
      }
    );
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

// =============================================================================
// 4. Archival and includeArchived tests
// =============================================================================

test('listBatchAnalysisReports passes includeArchived query parameter when true', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];
  setAuthToken('test-jwt-token');

  globalThis.fetch = async (url) => {
    captured.push(url.toString());
    return jsonResponse(sampleHistoryList);
  };

  try {
    const res = await listBatchAnalysisReports(42, { includeArchived: true });
    assert.deepEqual(res, sampleHistoryList);
    assert.equal(captured.length, 1);
    assert.ok(captured[0].includes('includeArchived=true'), 'URL should include includeArchived=true');
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('archiveBatchAnalysisReport sends PATCH to /api/batches/{batchId}/analysis-reports/{reportId}/archive', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];
  setAuthToken('test-jwt-token');

  globalThis.fetch = async (url, options) => {
    captured.push({ url: url.toString(), options });
    return jsonResponse({
      ...sampleReportResponse,
      archived: true,
      archiveReason: 'Superseded by Report #2',
    });
  };

  try {
    const res = await archiveBatchAnalysisReport(42, 101, { reason: 'Superseded by Report #2' });
    assert.equal(res.archived, true);
    assert.equal(res.archiveReason, 'Superseded by Report #2');
    assert.equal(captured.length, 1);
    assert.ok(captured[0].url.endsWith('/batches/42/analysis-reports/101/archive'));
    assert.equal(captured[0].options.method, 'PATCH');
    const parsedBody = JSON.parse(captured[0].options.body);
    assert.equal(parsedBody.reason, 'Superseded by Report #2');
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('archiveBatchAnalysisReport enforces mandatory archive reason constraints without calling fetch', async () => {
  const prevFetch = globalThis.fetch;
  let fetchCalled = false;
  globalThis.fetch = async () => {
    fetchCalled = true;
    return jsonResponse({});
  };

  try {
    // Missing reason
    await assert.rejects(
      () => archiveBatchAnalysisReport(42, 101),
      /Archive reason is mandatory and cannot be blank/
    );
    // Null reason
    await assert.rejects(
      () => archiveBatchAnalysisReport(42, 101, { reason: null }),
      /Archive reason is mandatory and cannot be blank/
    );
    // Empty string
    await assert.rejects(
      () => archiveBatchAnalysisReport(42, 101, { reason: '' }),
      /Archive reason is mandatory and cannot be blank/
    );
    // Whitespace only
    await assert.rejects(
      () => archiveBatchAnalysisReport(42, 101, { reason: '   \n\t  ' }),
      /Archive reason is mandatory and cannot be blank/
    );
    // Exceeds 500 chars
    const longReason = 'A'.repeat(501);
    await assert.rejects(
      () => archiveBatchAnalysisReport(42, 101, { reason: longReason }),
      /Archive reason must not exceed 500 characters/
    );

    assert.equal(fetchCalled, false);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('permanentDeleteBatchAnalysisReport makes DELETE request with valid reason and confirmation keyword', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];
  setAuthToken('test-admin-jwt-token');

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return {
      ok: true,
      status: 204,
      url: 'http://localhost:8080/api',
      headers: {
        get: () => null,
      },
      json: async () => null,
      text: async () => '',
    };
  };

  try {
    await permanentDeleteBatchAnalysisReport(42, 101, {
      reason: 'Local database cleanup',
      confirmationKeyword: 'PERMANENTLY DELETE',
    });

    assert.equal(captured.length, 1);
    assert.ok(captured[0].url.endsWith('/batches/42/analysis-reports/101'));
    assert.equal(captured[0].options.method, 'DELETE');
    const parsedBody = JSON.parse(captured[0].options.body);
    assert.equal(parsedBody.reason, 'Local database cleanup');
    assert.equal(parsedBody.confirmationKeyword, 'PERMANENTLY DELETE');
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('permanentDeleteBatchAnalysisReport enforces reason and confirmationKeyword constraints without calling fetch', async () => {
  const prevFetch = globalThis.fetch;
  let fetchCalled = false;
  globalThis.fetch = async () => {
    fetchCalled = true;
    return { ok: true, status: 204, json: async () => null };
  };

  try {
    // Missing reason
    await assert.rejects(
      () => permanentDeleteBatchAnalysisReport(42, 101, { confirmationKeyword: 'PERMANENTLY DELETE' }),
      /Deletion reason is mandatory and cannot be blank/
    );
    // Blank reason
    await assert.rejects(
      () => permanentDeleteBatchAnalysisReport(42, 101, { reason: '   ', confirmationKeyword: 'PERMANENTLY DELETE' }),
      /Deletion reason is mandatory and cannot be blank/
    );
    // Reason > 500 chars
    await assert.rejects(
      () => permanentDeleteBatchAnalysisReport(42, 101, { reason: 'X'.repeat(501), confirmationKeyword: 'PERMANENTLY DELETE' }),
      /Deletion reason must not exceed 500 characters/
    );
    // Missing keyword
    await assert.rejects(
      () => permanentDeleteBatchAnalysisReport(42, 101, { reason: 'Valid reason' }),
      /Confirmation keyword must be exactly 'PERMANENTLY DELETE'/
    );
    // Invalid keyword
    await assert.rejects(
      () => permanentDeleteBatchAnalysisReport(42, 101, { reason: 'Valid reason', confirmationKeyword: 'delete' }),
      /Confirmation keyword must be exactly 'PERMANENTLY DELETE'/
    );
    await assert.rejects(
      () => permanentDeleteBatchAnalysisReport(42, 101, { reason: 'Valid reason', confirmationKeyword: 'YES' }),
      /Confirmation keyword must be exactly 'PERMANENTLY DELETE'/
    );
    // Invalid IDs
    await assert.rejects(
      () => permanentDeleteBatchAnalysisReport('invalid', 101, { reason: 'Valid', confirmationKeyword: 'PERMANENTLY DELETE' }),
      /Valid numeric batch ID is required/
    );

    assert.equal(fetchCalled, false);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});
