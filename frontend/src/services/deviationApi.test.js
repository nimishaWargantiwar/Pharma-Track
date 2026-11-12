import test from 'node:test';
import assert from 'node:assert/strict';
import {
  cancelCapaAction,
  closeDeviation,
  completeCapaAction,
  createCapaAction,
  createDeviation,
  getBatchDeviations,
  getBatchLinkableRecords,
  getDeviationById,
  getDeviations,
  recordDeviationRootCause,
  reopenDeviationCapa,
  startCapaAction,
  startDeviationCapa,
  startDeviationInvestigation,
  submitDeviationForVerification,
} from './deviationApi.js';

function createFetchMock(responseFactory) {
  const calls = [];
  const mockFn = async (url, options = {}) => {
    calls.push({ url, options });
    return responseFactory(url, options);
  };
  return { calls, mockFn };
}

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get: (name) => (name.toLowerCase() === 'content-type' ? 'application/json' : null),
    },
    json: async () => body,
  };
}

test('getDeviations builds query parameters cleanly and ignores ALL values', async () => {
  const originalFetch = globalThis.fetch;
  const { calls, mockFn } = createFetchMock(() => jsonResponse([]));
  globalThis.fetch = mockFn;
  try {
    await getDeviations();
    await getDeviations({
      status: 'OPEN',
      severity: 'CRITICAL',
      priority: 'ALL',
      category: 'QC_OOS_FAILURE',
      batchId: 10,
    });
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.equal(calls[0].url, 'http://localhost:8080/api/quality/deviations');
  assert.equal(calls[0].options.cache, 'no-store');
  assert.equal(
    calls[1].url,
    'http://localhost:8080/api/quality/deviations?status=OPEN&severity=CRITICAL&category=QC_OOS_FAILURE&batchId=10'
  );
});

test('batch-scoped queries and detail endpoints call exact REST routes', async () => {
  const originalFetch = globalThis.fetch;
  const { calls, mockFn } = createFetchMock(() => jsonResponse({ ok: true }));
  globalThis.fetch = mockFn;
  try {
    await getBatchDeviations(15);
    await getBatchLinkableRecords(15);
    await getDeviationById(22);
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.equal(calls[0].url, 'http://localhost:8080/api/quality/batches/15/deviations');
  assert.equal(calls[1].url, 'http://localhost:8080/api/quality/batches/15/linkable-records');
  assert.equal(calls[2].url, 'http://localhost:8080/api/quality/deviations/22');
});

test('deviation lifecycle and CAPA action POST endpoints send JSON payloads to exact URLs', async () => {
  const originalFetch = globalThis.fetch;
  const { calls, mockFn } = createFetchMock(() => jsonResponse({ id: 1 }, 201));
  globalThis.fetch = mockFn;
  try {
    await createDeviation({ batchId: 5, title: 'OOS' });
    await startDeviationInvestigation(1, { investigatedBy: 'QA' });
    await recordDeviationRootCause(1, { rootCauseCategory: 'Equipment' });
    await startDeviationCapa(1, { actorName: 'QA' });
    await submitDeviationForVerification(1, { submittedBy: 'QA' });
    await reopenDeviationCapa(1, { actorName: 'QA', reason: 'More evidence' });
    await closeDeviation(1, { verifiedBy: 'QA Lead', verificationNotes: 'Verified' });
    await createCapaAction(1, { capaType: 'CORRECTIVE', title: 'Calibrate' });
    await startCapaAction(9, { actorName: 'Eng' });
    await completeCapaAction(9, { completedBy: 'Eng', completionEvidence: 'Cert #1' });
    await cancelCapaAction(9, { actorName: 'QA', cancellationReason: 'Duplicate' });
  } finally {
    globalThis.fetch = originalFetch;
  }

  const expected = [
    ['POST', 'http://localhost:8080/api/quality/deviations'],
    ['POST', 'http://localhost:8080/api/quality/deviations/1/start-investigation'],
    ['POST', 'http://localhost:8080/api/quality/deviations/1/record-root-cause'],
    ['POST', 'http://localhost:8080/api/quality/deviations/1/start-capa'],
    ['POST', 'http://localhost:8080/api/quality/deviations/1/submit-verification'],
    ['POST', 'http://localhost:8080/api/quality/deviations/1/reopen-capa'],
    ['POST', 'http://localhost:8080/api/quality/deviations/1/close'],
    ['POST', 'http://localhost:8080/api/quality/deviations/1/capas'],
    ['POST', 'http://localhost:8080/api/quality/capas/9/start'],
    ['POST', 'http://localhost:8080/api/quality/capas/9/complete'],
    ['POST', 'http://localhost:8080/api/quality/capas/9/cancel'],
  ];

  assert.equal(calls.length, expected.length);
  expected.forEach(([method, url], index) => {
    assert.equal(calls[index].options.method, method);
    assert.equal(calls[index].url, url);
    assert.equal(calls[index].options.headers['Content-Type'], 'application/json');
  });
});

test('missing IDs reject upfront and HTTP validation errors preserve status & validationErrors', async () => {
  await assert.rejects(() => getDeviationById(''), /Deviation ID is required/);
  await assert.rejects(() => getBatchDeviations(null), /Batch ID is required/);
  await assert.rejects(() => completeCapaAction(undefined, {}), /CAPA action ID is required/);

  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    jsonResponse(
      {
        message: 'Validation Failed',
        validationErrors: { title: 'Deviation title is mandatory' },
      },
      400
    );

  try {
    await assert.rejects(
      () => createDeviation({}),
      (err) => {
        assert.equal(err.status, 400);
        assert.equal(err.message, 'Validation Failed');
        assert.equal(err.validationErrors.title, 'Deviation title is mandatory');
        return true;
      }
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

