import test from 'node:test';
import assert from 'node:assert/strict';
import {
  APP_TIME_ZONE,
  APP_TIME_ZONE_OFFSET,
  formatAppDateTime,
  formatAppDateTimeIsoOffset,
  formatAppShortDateTime,
  formatAppTimeOnly,
  formatCalendarDate,
  getAppTimeZoneDateTimeComponents,
  getNowIsoDateTimeInAppTimeZone,
  getTodayIsoDateInAppTimeZone,
  isValidIsoCalendarDate,
  normalizeAppDateTimeForApi,
  parseBackendTimestampToDate,
  parseCalendarDateKey,
} from './dateTimeUtils.js';
import { formatCalendarDate as formatOrderCalendarDate } from './orderUtils.js';
import { formatDateTime as formatDossierDateTime } from '../components/QualityBatchDossier.jsx';

test('parseBackendTimestampToDate resolves offset-bearing and offset-free timestamps strictly as Asia/Kolkata instants', () => {
  const expectedIso = '2026-11-10T18:30:00.000Z'; // 2026-11-11T00:00:00+05:30

  const utcMidnight = parseBackendTimestampToDate('2026-11-10T18:30:00Z');
  const istExplicit = parseBackendTimestampToDate('2026-11-11T00:00:00+05:30');
  const nyExplicit = parseBackendTimestampToDate('2026-11-10T13:30:00-05:00');
  const offsetFreeIst = parseBackendTimestampToDate('2026-11-11T00:00:00');
  const offsetFreeMinutesOnly = parseBackendTimestampToDate('2026-11-11T00:00');
  const spaceSeparated = parseBackendTimestampToDate('2026-11-11 00:00:00');

  assert.equal(utcMidnight?.toISOString(), expectedIso);
  assert.equal(istExplicit?.toISOString(), expectedIso);
  assert.equal(nyExplicit?.toISOString(), expectedIso);
  assert.equal(offsetFreeIst?.toISOString(), expectedIso);
  assert.equal(offsetFreeMinutesOnly?.toISOString(), expectedIso);
  assert.equal(spaceSeparated?.toISOString(), expectedIso);

  // Microsecond precision from Java LocalDateTime/OffsetDateTime truncates cleanly to ms
  const micro = parseBackendTimestampToDate('2026-11-11T15:56:07.881080+05:30');
  assert.equal(micro?.toISOString(), '2026-11-11T10:26:07.881Z');
  const offsetFreeMicro = parseBackendTimestampToDate('2026-11-11T15:56:07.881080');
  assert.equal(offsetFreeMicro?.toISOString(), '2026-11-11T10:26:07.881Z');

  // Invalid / date-only / empty values return null safely
  assert.equal(parseBackendTimestampToDate('2026-11-11'), null);
  assert.equal(parseBackendTimestampToDate('2026-02-30T10:00:00'), null);
  assert.equal(parseBackendTimestampToDate('2026-11-11T25:00:00'), null);
  assert.equal(parseBackendTimestampToDate('not-a-date'), null);
  assert.equal(parseBackendTimestampToDate(''), null);
  assert.equal(parseBackendTimestampToDate(null), null);
});

test('formatAppDateTime, formatAppShortDateTime, and formatAppTimeOnly render identical Asia/Kolkata values regardless of host TZ', () => {
  assert.equal(APP_TIME_ZONE, 'Asia/Kolkata');
  assert.equal(APP_TIME_ZONE_OFFSET, '+05:30');

  const equivalentInputs = [
    '2026-11-10T18:30:00Z',
    '2026-11-11T00:00:00+05:30',
    '2026-11-10T13:30:00-05:00',
    '2026-11-11T00:00:00',
    '2026-11-11T00:00',
  ];

  const expectedDateTime = new Date('2026-11-10T18:30:00Z').toLocaleString(undefined, {
    timeZone: 'Asia/Kolkata',
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
  const expectedShort = new Date('2026-11-10T18:30:00Z').toLocaleString(undefined, {
    timeZone: 'Asia/Kolkata',
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
  const expectedTimeOnly = new Date('2026-11-10T18:30:00Z').toLocaleTimeString(undefined, {
    timeZone: 'Asia/Kolkata',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });

  for (const input of equivalentInputs) {
    assert.equal(formatAppDateTime(input), expectedDateTime);
    assert.equal(formatDossierDateTime(input), expectedDateTime);
    assert.equal(formatAppShortDateTime(input), expectedShort);
    assert.equal(formatAppTimeOnly(input), expectedTimeOnly);
  }

  // Verify midnight boundary did not shift back to Nov 10 or ahead to 05:30
  assert.match(expectedDateTime, /11/);
  assert.doesNotMatch(expectedDateTime, /\b10\b/);
  assert.deepEqual(getAppTimeZoneDateTimeComponents(new Date('2026-11-10T18:30:00Z')), {
    year: 2026,
    month: 11,
    day: 11,
    hour: 0,
    minute: 0,
    second: 0,
  });

  // Compact format without year (used by deviation linkable records picker)
  const compact = formatAppShortDateTime('2026-11-10T18:30:00Z', { includeYear: false, fallback: '' });
  assert.ok(compact.length > 0);
  assert.equal(formatAppShortDateTime('', { includeYear: false, fallback: '' }), '');
  assert.equal(formatAppDateTime(null), '—');
  assert.equal(formatAppTimeOnly(null), '');
});

test('formatCalendarDate preserves business-local calendar dates without timezone shifts including DST boundaries', () => {
  const calendarDates = ['2026-11-11', '2026-03-08', '2026-11-01', '2024-02-29'];

  for (const dateStr of calendarDates) {
    const [y, m, d] = dateStr.split('-').map(Number);
    const expected = new Date(Date.UTC(y, m - 1, d)).toLocaleDateString(undefined, {
      timeZone: 'UTC',
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
    assert.equal(formatCalendarDate(dateStr), expected);
    assert.equal(formatOrderCalendarDate(dateStr), expected);
  }

  // Invalid calendar date stays unshifted / uncoerced
  assert.equal(isValidIsoCalendarDate('2026-02-30'), false);
  assert.equal(isValidIsoCalendarDate('2024-02-29'), true);
  assert.equal(parseCalendarDateKey('2026-11-11'), 20261111);
  assert.equal(parseCalendarDateKey('2026-02-30'), null);
  assert.equal(formatCalendarDate('2026-02-30'), '2026-02-30');
  assert.equal(formatCalendarDate(null), '—');
});

test('normalizeAppDateTimeForApi and IST wall-clock helpers emit canonical +05:30 ISO strings', () => {
  const sampleInstant = new Date('2026-11-10T18:34:00Z'); // 2026-11-11T00:04:00+05:30
  assert.equal(getTodayIsoDateInAppTimeZone(sampleInstant), '2026-11-11');
  assert.equal(getNowIsoDateTimeInAppTimeZone(sampleInstant), '2026-11-11T00:04');
  assert.equal(formatAppDateTimeIsoOffset(sampleInstant), '2026-11-11T00:04:00+05:30');

  assert.equal(normalizeAppDateTimeForApi('2026-11-11T00:04'), '2026-11-11T00:04:00+05:30');
  assert.equal(normalizeAppDateTimeForApi('2026-11-11T00:04:30'), '2026-11-11T00:04:30+05:30');
  assert.equal(normalizeAppDateTimeForApi('2026-11-10T18:34:00Z'), '2026-11-11T00:04:00+05:30');
  assert.equal(normalizeAppDateTimeForApi('2026-11-10T13:34:00-05:00'), '2026-11-11T00:04:00+05:30');
  assert.equal(normalizeAppDateTimeForApi('   '), null);
  assert.equal(normalizeAppDateTimeForApi(null), null);
});

