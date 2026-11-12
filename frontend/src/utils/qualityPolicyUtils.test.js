import test from 'node:test';
import assert from 'node:assert/strict';
import {
  assessBatchReleaseEligibility,
  normalizeQcCheckedAtForApi,
  QA_RELEASE_POLICY_COPY,
  validateQcCheckedAt,
} from './qualityPolicyUtils.js';

test('QA_RELEASE_POLICY_COPY accurately distinguishes required vs optional QC checks without misleading wording', () => {
  assert.match(QA_RELEASE_POLICY_COPY.ruleSummary, /required QC check/i);
  assert.match(QA_RELEASE_POLICY_COPY.ruleSummary, /required and optional/i);
  assert.match(QA_RELEASE_POLICY_COPY.optionalHelp, /do not need to be recorded/i);
  assert.match(QA_RELEASE_POLICY_COPY.optionalHelp, /PENDING or FAIL still blocks QA RELEASED/i);
  assert.match(QA_RELEASE_POLICY_COPY.eligibleBannerDescription, /required and optional/i);
  assert.match(QA_RELEASE_POLICY_COPY.blockedPolicyNote, /ON_HOLD and REJECTED/);
  assert.match(QA_RELEASE_POLICY_COPY.blockedPolicyNote, /never automatically releases/i);
  assert.doesNotMatch(JSON.stringify(QA_RELEASE_POLICY_COPY), /21 CFR Part 11|GMP certified/i);
});

test('Zero QC checks blocks QA release and surfaces missing QC check chip', () => {
  const result = assessBatchReleaseEligibility({
    batchId: 10,
    batchStatus: 'COMPLETED',
    qaStatus: 'PENDING_QA',
    totalChecksCount: 0,
    passedChecksCount: 0,
    failedChecksCount: 0,
    pendingChecksCount: 0,
    requiredChecksCount: 0,
    openDeviationsCount: 0,
  });

  assert.equal(result.isFinalized, false);
  assert.equal(result.isEligible, false);
  assert.equal(result.statusKey, 'BLOCKED');
  assert.equal(result.statusLabel, 'Blocked');
  assert.deepEqual(
    result.blockerChips.map((c) => c.key),
    ['no_qc']
  );
  assert.match(result.blockers[0], /required QC check must exist and PASS/i);
});

test('Optional-only PASS checks (zero required checks) remain blocked and flag Missing Required QC', () => {
  const queueItem = {
    batchId: 11,
    batchStatus: 'COMPLETED',
    qaStatus: 'PENDING_QA',
    totalChecksCount: 1,
    passedChecksCount: 1,
    failedChecksCount: 0,
    pendingChecksCount: 0,
    requiredChecksCount: 0,
    optionalChecksCount: 1,
    openDeviationsCount: 0,
    eligibleForRelease: false,
    releaseBlockers: ['At least one required QC check must exist and PASS'],
  };

  const result = assessBatchReleaseEligibility(queueItem);

  assert.equal(result.isEligible, false);
  assert.equal(result.qcCounts.required, 0);
  assert.equal(result.qcCounts.optional, 1);
  assert.deepEqual(
    result.blockerChips.map((c) => c.label),
    ['Missing Required QC']
  );
  assert.deepEqual(result.blockers, ['At least one required QC check must exist and PASS']);
});

test('Required PASS + optional FAIL or optional PENDING blocks release identically in Queue and Dossier', () => {
  const queueOptFail = {
    batchId: 12,
    batchStatus: 'COMPLETED',
    qaStatus: 'PENDING_QA',
    totalChecksCount: 2,
    passedChecksCount: 1,
    failedChecksCount: 1,
    pendingChecksCount: 0,
    requiredChecksCount: 1,
    optionalChecksCount: 1,
    blockingChecksCount: 1,
    openDeviationsCount: 0,
    eligibleForRelease: false,
    releaseBlockers: ['1 optional QC check(s) have FAILED'],
  };

  const dossierOptFail = {
    ...queueOptFail,
    qualityChecks: [
      { id: 1, testName: 'Assay', result: 'PASS', required: true },
      { id: 2, testName: 'Friability', result: 'FAIL', required: false },
    ],
  };

  const qRes = assessBatchReleaseEligibility(queueOptFail);
  const dRes = assessBatchReleaseEligibility(dossierOptFail);

  assert.equal(qRes.isEligible, false);
  assert.equal(dRes.isEligible, false);
  assert.deepEqual(qRes.blockerChips, dRes.blockerChips);
  assert.deepEqual(qRes.blockers, dRes.blockers);
  assert.deepEqual(
    qRes.blockerChips.map((c) => c.label),
    ['1 Failed QC']
  );
  assert.equal(qRes.qcCounts.blocking, 1);

  const dossierOptPending = assessBatchReleaseEligibility({
    batchId: 13,
    batchStatus: 'COMPLETED',
    qaStatus: 'ON_HOLD',
    totalChecksCount: 2,
    passedChecksCount: 1,
    failedChecksCount: 0,
    pendingChecksCount: 1,
    requiredChecksCount: 1,
    openDeviationsCount: 0,
    qualityChecks: [
      { id: 1, testName: 'Assay', result: 'PASS', required: true },
      { id: 2, testName: 'Moisture', result: 'PENDING', required: false },
    ],
  });

  assert.equal(dossierOptPending.isEligible, false);
  assert.deepEqual(
    dossierOptPending.blockerChips.map((c) => c.label),
    ['1 Pending QC']
  );
  assert.deepEqual(dossierOptPending.blockers, [
    '1 optional QC check(s) are still PENDING',
  ]);
});

test('Combined required+optional failures, pending checks, and unresolved deviations report all blockers accurately', () => {
  const dossier = assessBatchReleaseEligibility({
    batchId: 14,
    batchStatus: 'COMPLETED',
    qaStatus: 'PENDING_QA',
    totalChecksCount: 4,
    passedChecksCount: 0,
    failedChecksCount: 2,
    pendingChecksCount: 2,
    requiredChecksCount: 2,
    openDeviationsCount: 2,
    qualityChecks: [
      { id: 1, result: 'FAIL', required: true },
      { id: 2, result: 'FAIL', required: false },
      { id: 3, result: 'PENDING', required: true },
      { id: 4, result: 'PENDING', required: false },
    ],
  });

  assert.equal(dossier.isEligible, false);
  assert.deepEqual(
    dossier.blockerChips.map((c) => c.label),
    ['2 Failed QC', '2 Pending QC', '2 Open Dev']
  );
  assert.deepEqual(dossier.blockers, [
    '1 required QC check(s) have FAILED',
    '1 optional QC check(s) have FAILED',
    '1 required QC check(s) are still PENDING',
    '1 optional QC check(s) are still PENDING',
    '2 unresolved deviation(s) must be CLOSED before QA release',
  ]);
});

test('Required PASS only (with optional omitted) and Required+Optional PASS with CLOSED deviations are Eligible for Release', () => {
  const requiredOnlyQueue = assessBatchReleaseEligibility({
    batchId: 15,
    batchStatus: 'COMPLETED',
    qaStatus: 'PENDING_QA',
    totalChecksCount: 1,
    passedChecksCount: 1,
    failedChecksCount: 0,
    pendingChecksCount: 0,
    requiredChecksCount: 1,
    optionalChecksCount: 0,
    openDeviationsCount: 0,
    eligibleForRelease: true,
    releaseBlockers: [],
  });

  assert.equal(requiredOnlyQueue.isEligible, true);
  assert.equal(requiredOnlyQueue.statusKey, 'READY');
  assert.equal(requiredOnlyQueue.statusLabel, 'Eligible for Release');
  assert.deepEqual(requiredOnlyQueue.blockerChips, []);
  assert.deepEqual(requiredOnlyQueue.blockers, []);

  const withClosedDevAndOptPassDossier = assessBatchReleaseEligibility({
    batchId: 16,
    batchStatus: 'COMPLETED',
    qaStatus: 'ON_HOLD',
    totalChecksCount: 2,
    passedChecksCount: 2,
    failedChecksCount: 0,
    pendingChecksCount: 0,
    requiredChecksCount: 1,
    totalDeviationsCount: 1,
    openDeviationsCount: 0,
    eligibleForRelease: true,
    releaseBlockers: [],
  });

  assert.equal(withClosedDevAndOptPassDossier.isEligible, true);
  assert.equal(withClosedDevAndOptPassDossier.qcCounts.optional, 1);
  assert.equal(withClosedDevAndOptPassDossier.statusLabel, 'Eligible for Release');
});

test('Finalized RELEASED and REJECTED batches report Finalized cleanly without active blocker chips', () => {
  const releasedWithPostDeviation = assessBatchReleaseEligibility({
    batchId: 17,
    batchStatus: 'COMPLETED',
    qaStatus: 'RELEASED',
    totalChecksCount: 1,
    passedChecksCount: 1,
    failedChecksCount: 0,
    pendingChecksCount: 0,
    requiredChecksCount: 1,
    openDeviationsCount: 1,
    eligibleForRelease: false,
    releaseBlockers: [
      'Batch is already RELEASED',
      '1 unresolved deviation(s) must be CLOSED before QA release',
    ],
  });

  assert.equal(releasedWithPostDeviation.isFinalized, true);
  assert.equal(releasedWithPostDeviation.isEligible, false);
  assert.equal(releasedWithPostDeviation.statusKey, 'FINALIZED_RELEASED');
  assert.equal(releasedWithPostDeviation.statusLabel, 'Finalized');
  assert.deepEqual(releasedWithPostDeviation.blockerChips, []);
  assert.deepEqual(releasedWithPostDeviation.blockers, []);

  const rejected = assessBatchReleaseEligibility({
    batchId: 18,
    batchStatus: 'COMPLETED',
    qaStatus: 'REJECTED',
    totalChecksCount: 1,
    passedChecksCount: 0,
    failedChecksCount: 1,
    pendingChecksCount: 0,
    requiredChecksCount: 1,
    openDeviationsCount: 1,
    eligibleForRelease: false,
    releaseBlockers: ['Batch is finalized as REJECTED'],
  });

  assert.equal(rejected.isFinalized, true);
  assert.equal(rejected.isEligible, false);
  assert.equal(rejected.statusKey, 'FINALIZED_REJECTED');
  assert.equal(rejected.statusLabel, 'Finalized');
  assert.deepEqual(rejected.blockerChips, []);
  assert.deepEqual(rejected.blockers, []);
});

test('Optional-only FAIL and optional-only PENDING without required checks surface combined Missing Required QC + optional check blockers', () => {
  const optOnlyFail = assessBatchReleaseEligibility({
    batchId: 19,
    batchStatus: 'COMPLETED',
    qaStatus: 'PENDING_QA',
    totalChecksCount: 1,
    passedChecksCount: 0,
    failedChecksCount: 1,
    pendingChecksCount: 0,
    requiredChecksCount: 0,
    openDeviationsCount: 0,
  });

  assert.equal(optOnlyFail.isEligible, false);
  assert.deepEqual(
    optOnlyFail.blockerChips.map((c) => c.label),
    ['Missing Required QC', '1 Failed QC']
  );
  assert.deepEqual(optOnlyFail.blockers, [
    'At least one required QC check must exist and PASS',
    '1 optional QC check(s) have FAILED',
  ]);

  const optOnlyPending = assessBatchReleaseEligibility({
    batchId: 20,
    batchStatus: 'COMPLETED',
    qaStatus: 'ON_HOLD',
    totalChecksCount: 1,
    passedChecksCount: 0,
    failedChecksCount: 0,
    pendingChecksCount: 1,
    requiredChecksCount: 0,
    openDeviationsCount: 0,
  });

  assert.equal(optOnlyPending.isEligible, false);
  assert.deepEqual(
    optOnlyPending.blockerChips.map((c) => c.label),
    ['Missing Required QC', '1 Pending QC']
  );
  assert.deepEqual(optOnlyPending.blockers, [
    'At least one required QC check must exist and PASS',
    '1 optional QC check(s) are still PENDING',
  ]);
});

test('Non-COMPLETED batch and null input are safely treated as ineligible for QA release', () => {
  const inProgress = assessBatchReleaseEligibility({
    batchId: 21,
    batchStatus: 'IN_PROGRESS',
    qaStatus: 'PENDING_QA',
    totalChecksCount: 1,
    passedChecksCount: 1,
    failedChecksCount: 0,
    pendingChecksCount: 0,
    requiredChecksCount: 1,
    openDeviationsCount: 0,
    eligibleForRelease: true,
  });

  assert.equal(inProgress.isEligible, false);
  assert.equal(inProgress.statusKey, 'BLOCKED');
  assert.deepEqual(
    inProgress.blockerChips.map((c) => c.label),
    ['Mfg: IN_PROGRESS']
  );
  assert.match(inProgress.blockers[0], /must be COMPLETED/i);

  const nullResult = assessBatchReleaseEligibility(null);
  assert.equal(nullResult.isEligible, false);
  assert.equal(nullResult.blockerChips[0].key, 'unavailable');
});

test('validateQcCheckedAt and normalizeQcCheckedAtForApi validate Asia/Kolkata wall clock and +5 min tolerance', () => {
  // 2026-11-10T18:30:00Z === 2026-11-11T00:00:00 Asia/Kolkata
  const midnightIstInstant = new Date('2026-11-10T18:30:00Z');

  // Blank or null allows server stamping
  assert.equal(validateQcCheckedAt('', midnightIstInstant), null);
  assert.equal(validateQcCheckedAt('   ', midnightIstInstant), null);
  assert.equal(validateQcCheckedAt(null, midnightIstInstant), null);

  // Past and within +5 minute IST tolerance pass even across UTC midnight rollover
  assert.equal(validateQcCheckedAt('2026-11-10T23:45', midnightIstInstant), null);
  assert.equal(validateQcCheckedAt('2026-11-11T00:00', midnightIstInstant), null);
  assert.equal(validateQcCheckedAt('2026-11-11T00:05', midnightIstInstant), null);
  assert.equal(validateQcCheckedAt('2026-11-10T18:35:00Z', midnightIstInstant), null);

  // > +5 minute ahead of Asia/Kolkata current time is rejected
  assert.match(
    validateQcCheckedAt('2026-11-11T00:06', midnightIstInstant),
    /cannot be in the future \(Asia\/Kolkata\)/
  );
  assert.match(
    validateQcCheckedAt('2026-11-10T18:36:00Z', midnightIstInstant),
    /cannot be in the future \(Asia\/Kolkata\)/
  );

  // Invalid format or calendar date rejected cleanly
  assert.equal(validateQcCheckedAt('2026-02-30T10:00', midnightIstInstant), 'Invalid check timestamp');
  assert.equal(validateQcCheckedAt('2026-11-11', midnightIstInstant), 'Invalid check timestamp');
  assert.equal(validateQcCheckedAt('invalid-date', midnightIstInstant), 'Invalid check timestamp');

  // Normalization for backend Asia/Kolkata JSON deserialization (+05:30 explicit)
  assert.equal(normalizeQcCheckedAtForApi('2026-11-11T00:04'), '2026-11-11T00:04:00+05:30');
  assert.equal(normalizeQcCheckedAtForApi('2026-11-11T00:04:30'), '2026-11-11T00:04:30+05:30');
  assert.equal(normalizeQcCheckedAtForApi('2026-11-10T18:34:00Z'), '2026-11-11T00:04:00+05:30');
  assert.equal(normalizeQcCheckedAtForApi('   '), null);
});



