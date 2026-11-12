import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildDeviationPrefillFromQc,
  canCancelCapaAction,
  canEditDeviationCapas,
  canExecuteDeviationCapas,
  filterDeviationsBySearch,
  getCategoryLabel,
  getDeviationStageIndex,
  getNowIsoDateTimeInAppTimeZone,
  getTodayIsoDateInAppTimeZone,
  mapCapaItemValidationErrors,
  normalizeOccurredAtForApi,
  summarizeDeviationCounts,
  validateCapaDueDate,
  validateDeviationOccurredAt,
} from './deviationUtils.js';

test('getTodayIsoDateInAppTimeZone returns valid YYYY-MM-DD in Asia/Kolkata', () => {
  const sample = new Date('2026-11-10T20:00:00Z'); // 2026-11-11 01:30 IST
  assert.equal(getTodayIsoDateInAppTimeZone(sample), '2026-11-11');
});

test('validateCapaDueDate enforces mandatory, calendar validity, and non-past Asia/Kolkata rules', () => {
  assert.match(validateCapaDueDate('', '2026-11-10'), /mandatory/);
  assert.match(validateCapaDueDate('2026-02-30', '2026-11-10'), /valid date/);
  assert.match(validateCapaDueDate('2026-11-09', '2026-11-10'), /cannot be earlier than today/);
  assert.equal(validateCapaDueDate('2026-11-10', '2026-11-10'), null);
  assert.equal(validateCapaDueDate('2026-11-25', '2026-11-10'), null);
});

test('summarizeDeviationCounts and filterDeviationsBySearch compute status & unresolved totals', () => {
  const sampleList = [
    {
      id: 1,
      deviationNumber: 'DEV-2026-0001',
      batchNumber: 'BAT-2026-0009-01',
      productName: 'Amoxicillin Capsules',
      title: 'Dissolution OOS',
      reportedBy: 'A. Nair',
      severity: 'CRITICAL',
      status: 'OPEN',
      overdueCapaCount: 1,
    },
    {
      id: 2,
      deviationNumber: 'DEV-2026-0002',
      batchNumber: 'BAT-2026-0010-01',
      productName: 'Paracetamol Tablets',
      title: 'Tablet weight drift',
      reportedBy: 'B. Rao',
      severity: 'MINOR',
      status: 'CLOSED',
      overdueCapaCount: 0,
    },
    {
      id: 3,
      deviationNumber: 'DEV-2026-0003',
      batchNumber: 'BAT-2026-0011-01',
      productName: 'Paracetamol Tablets',
      title: 'Granulator RPM alert',
      reportedBy: 'C. Patel',
      severity: 'MAJOR',
      status: 'CAPA_IN_PROGRESS',
      overdueCapaCount: 2,
    },
  ];

  const counts = summarizeDeviationCounts(sampleList);
  assert.equal(counts.ALL, 3);
  assert.equal(counts.UNRESOLVED, 2);
  assert.equal(counts.OPEN, 1);
  assert.equal(counts.CAPA_IN_PROGRESS, 1);
  assert.equal(counts.CLOSED, 1);
  assert.equal(counts.CRITICAL, 1);
  assert.equal(counts.OVERDUE_CAPAS, 3);

  assert.equal(filterDeviationsBySearch(sampleList, '0009').length, 1);
  assert.equal(filterDeviationsBySearch(sampleList, 'Paracetamol').length, 2);
  assert.equal(filterDeviationsBySearch(sampleList, 'DEV-2026-0003').length, 1);
  assert.equal(filterDeviationsBySearch(sampleList, '').length, 3);
});

test('buildDeviationPrefillFromQc prefills batch, qualityCheckId, and QC_OOS_FAILURE category', () => {
  const dossier = { batchId: 10, batchNumber: 'BAT-2026-0009-01' };
  const qc = {
    id: 42,
    testName: 'Dissolution Test',
    specification: 'Minimum 80% dissolution within 30 minutes',
    measuredValue: '65',
    unit: '%',
    result: 'FAIL',
    remarks: 'Failed Stage 1 sample',
  };

  const prefill = buildDeviationPrefillFromQc(dossier, qc);
  assert.equal(prefill.batchId, '10');
  assert.equal(prefill.qualityCheckId, '42');
  assert.equal(prefill.category, 'QC_OOS_FAILURE');
  assert.equal(prefill.severity, 'CRITICAL');
  assert.equal(prefill.priority, 'HIGH');
  assert.match(prefill.title, /Dissolution Test/);
  assert.match(prefill.description, /65 %/);
  assert.match(prefill.description, /Minimum 80%/);
});

test('lifecycle stage index, CAPA mutation locks, cancel guard, and category labels are accurate', () => {
  assert.equal(getDeviationStageIndex('OPEN'), 0);
  assert.equal(getDeviationStageIndex('CLOSED'), 5);

  // CAPA creation / planning permitted ONLY in ROOT_CAUSE_IDENTIFIED and CAPA_IN_PROGRESS
  assert.equal(canEditDeviationCapas('OPEN'), false);
  assert.equal(canEditDeviationCapas('UNDER_INVESTIGATION'), false);
  assert.equal(canEditDeviationCapas('ROOT_CAUSE_IDENTIFIED'), true);
  assert.equal(canEditDeviationCapas('CAPA_IN_PROGRESS'), true);
  assert.equal(canEditDeviationCapas('PENDING_VERIFICATION'), false);
  assert.equal(canEditDeviationCapas('CLOSED'), false);
  assert.equal(canEditDeviationCapas(null), false);

  // CAPA start / complete execution permitted ONLY in CAPA_IN_PROGRESS
  assert.equal(canExecuteDeviationCapas('OPEN'), false);
  assert.equal(canExecuteDeviationCapas('UNDER_INVESTIGATION'), false);
  assert.equal(canExecuteDeviationCapas('ROOT_CAUSE_IDENTIFIED'), false);
  assert.equal(canExecuteDeviationCapas('CAPA_IN_PROGRESS'), true);
  assert.equal(canExecuteDeviationCapas('PENDING_VERIFICATION'), false);
  assert.equal(canExecuteDeviationCapas('CLOSED'), false);

  // CAPA cancellation allowed in ROOT_CAUSE_IDENTIFIED (even last) or CAPA_IN_PROGRESS when >1 active
  assert.equal(canCancelCapaAction('OPEN', 'OPEN', 1), false);
  assert.equal(canCancelCapaAction('UNDER_INVESTIGATION', 'OPEN', 1), false);
  assert.equal(canCancelCapaAction('ROOT_CAUSE_IDENTIFIED', 'OPEN', 1), true);
  assert.equal(canCancelCapaAction('CAPA_IN_PROGRESS', 'OPEN', 1), false);
  assert.equal(canCancelCapaAction('CAPA_IN_PROGRESS', 'IN_PROGRESS', 1), false);
  assert.equal(canCancelCapaAction('CAPA_IN_PROGRESS', 'OPEN', 2), true);
  assert.equal(canCancelCapaAction('CAPA_IN_PROGRESS', 'COMPLETED', 2), false);
  assert.equal(canCancelCapaAction('CAPA_IN_PROGRESS', 'CANCELLED', 2), false);
  assert.equal(canCancelCapaAction('PENDING_VERIFICATION', 'OPEN', 2), false);

  assert.equal(getCategoryLabel('QC_OOS_FAILURE'), 'QC Out-of-Specification (OOS)');
});

test('mapCapaItemValidationErrors translates backend DTO field keys to UI inline error keys', () => {
  assert.deepEqual(
    mapCapaItemValidationErrors({ actorName: 'Actor name is mandatory', notes: 'Max 1000 chars' }),
    { actor: 'Actor name is mandatory', text: 'Max 1000 chars' }
  );
  assert.deepEqual(
    mapCapaItemValidationErrors({
      completedBy: 'Completed by is mandatory',
      completionEvidence: 'Completion evidence is mandatory',
    }),
    {
      actor: 'Completed by is mandatory',
      text: 'Completion evidence is mandatory',
    }
  );
  assert.deepEqual(
    mapCapaItemValidationErrors({
      actorName: 'Actor name is mandatory',
      cancellationReason: 'Cancellation reason is mandatory',
    }),
    {
      actor: 'Actor name is mandatory',
      text: 'Cancellation reason is mandatory',
    }
  );
  assert.deepEqual(mapCapaItemValidationErrors(null), {});
});

test('validateDeviationOccurredAt and normalizeOccurredAtForApi enforce Asia/Kolkata rules consistently', () => {
  // Midnight boundary in IST: 2026-11-10T18:30:00Z === 2026-11-11 00:00:00 Asia/Kolkata
  const midnightIstInstant = new Date('2026-11-10T18:30:00Z');
  assert.equal(getNowIsoDateTimeInAppTimeZone(midnightIstInstant), '2026-11-11T00:00');

  // Within +5 minute tolerance in Asia/Kolkata -> allowed (offset-free IST or explicit offsets)
  assert.equal(validateDeviationOccurredAt('2026-11-11T00:04', midnightIstInstant), null);
  assert.equal(validateDeviationOccurredAt('2026-11-11T00:04:00+05:30', midnightIstInstant), null);
  assert.equal(validateDeviationOccurredAt('2026-11-10T18:34:00Z', midnightIstInstant), null);
  assert.equal(validateDeviationOccurredAt('2026-11-10T23:55', midnightIstInstant), null);
  assert.equal(validateDeviationOccurredAt('', midnightIstInstant), null);
  assert.equal(validateDeviationOccurredAt('   ', midnightIstInstant), null);

  // Beyond +5 minute tolerance in Asia/Kolkata -> rejected regardless of host timezone
  assert.match(
    validateDeviationOccurredAt('2026-11-11T00:06', midnightIstInstant),
    /cannot be in the future \(Asia\/Kolkata\)/
  );
  assert.match(
    validateDeviationOccurredAt('2026-11-10T18:36:00Z', midnightIstInstant),
    /cannot be in the future \(Asia\/Kolkata\)/
  );

  // Invalid format or impossible calendar date -> rejected
  assert.match(validateDeviationOccurredAt('2026-02-30T10:00', midnightIstInstant), /Invalid occurrence timestamp/);
  assert.match(validateDeviationOccurredAt('2026-11-11', midnightIstInstant), /Invalid occurrence timestamp/);
  assert.match(validateDeviationOccurredAt('not-a-timestamp', midnightIstInstant), /Invalid occurrence timestamp/);

  // API normalization emits canonical +05:30 ISO timestamps
  assert.equal(normalizeOccurredAtForApi('2026-11-11T00:04'), '2026-11-11T00:04:00+05:30');
  assert.equal(normalizeOccurredAtForApi('2026-11-11T00:04:30'), '2026-11-11T00:04:30+05:30');
  assert.equal(normalizeOccurredAtForApi('2026-11-10T18:34:00Z'), '2026-11-11T00:04:00+05:30');
  assert.equal(normalizeOccurredAtForApi('  '), null);
});

