export const APP_TIME_ZONE = 'Asia/Kolkata';
export const APP_TIME_ZONE_OFFSET = '+05:30';
export const APP_TIME_ZONE_OFFSET_MINUTES = 5 * 60 + 30;

const ISO_CALENDAR_DATE_REGEX = /^(\d{4})-(\d{2})-(\d{2})$/;
const ISO_TIMESTAMP_REGEX =
  /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?(Z|[+-]\d{2}:?\d{2})?(?:\[.*\])?$/i;

function pad2(n) {
  return String(n).padStart(2, '0');
}

export function isValidIsoCalendarDate(value) {
  if (!value || typeof value !== 'string') return false;
  const match = ISO_CALENDAR_DATE_REGEX.exec(value.trim());
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const check = new Date(Date.UTC(year, month - 1, day));
  return (
    check.getUTCFullYear() === year &&
    check.getUTCMonth() === month - 1 &&
    check.getUTCDate() === day
  );
}

export function parseCalendarDateKey(value) {
  if (!isValidIsoCalendarDate(value)) return null;
  const match = ISO_CALENDAR_DATE_REGEX.exec(value.trim());
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  return year * 10000 + month * 100 + day;
}

export function getTodayIsoDateInAppTimeZone(now = new Date()) {
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: APP_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
  const parts = formatter.formatToParts(now);
  const year = parts.find((p) => p.type === 'year')?.value;
  const month = parts.find((p) => p.type === 'month')?.value;
  const day = parts.find((p) => p.type === 'day')?.value;
  return `${year}-${month}-${day}`;
}

export function getAppTimeZoneDateTimeComponents(now = new Date()) {
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: APP_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  });
  const parts = formatter.formatToParts(now);
  const getPart = (type) => Number(parts.find((p) => p.type === type)?.value);
  return {
    year: getPart('year'),
    month: getPart('month'),
    day: getPart('day'),
    hour: getPart('hour') % 24,
    minute: getPart('minute'),
    second: getPart('second'),
  };
}

export function getNowIsoDateTimeInAppTimeZone(now = new Date()) {
  const { year, month, day, hour, minute } = getAppTimeZoneDateTimeComponents(now);
  return `${year}-${pad2(month)}-${pad2(day)}T${pad2(hour)}:${pad2(minute)}`;
}

/**
 * Parses backend / user ISO-8601 timestamps without ever relying on the browser's local timezone.
 * - Offset-bearing strings (`Z`, `+05:30`, `-05:00`) resolve to their exact instant.
 * - Offset-free ISO datetime strings (`YYYY-MM-DDTHH:mm[:ss[.fraction]]`) are interpreted
 *   strictly in `Asia/Kolkata` (`+05:30`).
 * - Pure calendar dates (`YYYY-MM-DD`) and invalid dates return `null`.
 */
export function parseBackendTimestampToDate(value) {
  if (value == null || value === '') return null;
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value;
  }
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return null;
    const d = new Date(value);
    return Number.isNaN(d.getTime()) ? null : d;
  }
  if (typeof value !== 'string') return null;

  const trimmed = value.trim();
  if (!trimmed || ISO_CALENDAR_DATE_REGEX.test(trimmed)) {
    return null;
  }

  const match = ISO_TIMESTAMP_REGEX.exec(trimmed);
  if (match) {
    const year = Number(match[1]);
    const month = Number(match[2]);
    const day = Number(match[3]);
    const hour = Number(match[4]);
    const minute = Number(match[5]);
    const second = match[6] !== undefined ? Number(match[6]) : 0;
    const fraction = match[7] ? match[7].padEnd(3, '0').slice(0, 3) : '000';
    const millisecond = Number(fraction);
    const tzSuffix = match[8];

    if (
      !isValidIsoCalendarDate(`${match[1]}-${match[2]}-${match[3]}`) ||
      hour < 0 ||
      hour > 23 ||
      minute < 0 ||
      minute > 59 ||
      second < 0 ||
      second > 59
    ) {
      return null;
    }

    let offsetMinutes = APP_TIME_ZONE_OFFSET_MINUTES;
    if (tzSuffix) {
      if (tzSuffix.toUpperCase() === 'Z') {
        offsetMinutes = 0;
      } else {
        const sign = tzSuffix.startsWith('-') ? -1 : 1;
        const digits = tzSuffix.slice(1).replace(':', '');
        const offHours = Number(digits.slice(0, 2));
        const offMins = Number(digits.slice(2, 4));
        if (offHours > 23 || offMins > 59) {
          return null;
        }
        offsetMinutes = sign * (offHours * 60 + offMins);
      }
    }

    const utcMs =
      Date.UTC(year, month - 1, day, hour, minute, second, millisecond) -
      offsetMinutes * 60 * 1000;
    const date = new Date(utcMs);
    return Number.isNaN(date.getTime()) ? null : date;
  }

  // Only allow native Date fallback for explicit timezone-qualified RFC/HTTP timestamps (never offset-free strings)
  if (/(?:GMT|UTC|Z|[+-]\d{2}:?\d{2})\b/i.test(trimmed)) {
    const parsed = new Date(trimmed);
    return Number.isNaN(parsed.getTime()) ? null : parsed;
  }
  return null;
}

export function formatAppDateTimeIsoOffset(value) {
  const date = parseBackendTimestampToDate(value);
  if (!date) return null;
  const { year, month, day, hour, minute, second } = getAppTimeZoneDateTimeComponents(date);
  return `${year}-${pad2(month)}-${pad2(day)}T${pad2(hour)}:${pad2(minute)}:${pad2(second)}${APP_TIME_ZONE_OFFSET}`;
}

export function normalizeAppDateTimeForApi(value) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!trimmed) return null;
  const formatted = formatAppDateTimeIsoOffset(trimmed);
  return formatted || trimmed;
}

export function formatCalendarDate(dateString, fallback = '—') {
  if (!dateString) return fallback;
  const trimmed = String(dateString).trim();
  if (ISO_CALENDAR_DATE_REGEX.test(trimmed)) {
    if (!isValidIsoCalendarDate(trimmed)) {
      return String(dateString);
    }
    const [, y, m, d] = ISO_CALENDAR_DATE_REGEX.exec(trimmed);
    const utcCalendarDate = new Date(Date.UTC(Number(y), Number(m) - 1, Number(d)));
    return utcCalendarDate.toLocaleDateString(undefined, {
      timeZone: 'UTC',
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  }

  const parsedTimestamp = parseBackendTimestampToDate(trimmed);
  if (!parsedTimestamp) {
    return String(dateString);
  }
  return parsedTimestamp.toLocaleDateString(undefined, {
    timeZone: APP_TIME_ZONE,
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
}

export function formatAppDateTime(value, fallback = '—') {
  if (!value) return fallback;
  const date = parseBackendTimestampToDate(value);
  if (!date) return String(value);
  return date.toLocaleString(undefined, {
    timeZone: APP_TIME_ZONE,
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function formatAppShortDateTime(value, { includeYear = true, fallback = '—' } = {}) {
  if (!value) return fallback;
  const date = parseBackendTimestampToDate(value);
  if (!date) return String(value);
  const options = {
    timeZone: APP_TIME_ZONE,
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  };
  if (includeYear) {
    options.year = 'numeric';
  }
  return date.toLocaleString(undefined, options);
}

export function formatAppTimeOnly(value, fallback = '') {
  if (!value) return fallback;
  const date = parseBackendTimestampToDate(value);
  if (!date) return fallback;
  return date.toLocaleTimeString(undefined, {
    timeZone: APP_TIME_ZONE,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

