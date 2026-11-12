import React, { useMemo, useState } from 'react';
import { Code2, GitCompare } from 'lucide-react';

const SENSITIVE_KEY_PATTERN =
  /(password|passwd|pwd|secret|token|jwt|api[_-]?key|credential|authorization|private[_-]?key|session[_-]?id)/i;

const SENSITIVE_TOKEN_PATTERN = /\bBearer\s+[A-Za-z0-9\-._~+/]+=*/gi;
const JWT_PATTERN = /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/g;

export function redactAuditText(value) {
  if (typeof value !== 'string') return value;
  return value
    .replace(SENSITIVE_TOKEN_PATTERN, 'Bearer [REDACTED]')
    .replace(JWT_PATTERN, '[REDACTED_JWT]');
}

export function sanitizeAuditValue(value, keyHint = '') {
  if (keyHint && SENSITIVE_KEY_PATTERN.test(keyHint)) {
    return value === undefined || value === null ? value : '[REDACTED]';
  }
  if (Array.isArray(value)) {
    return value.map((item) => sanitizeAuditValue(item));
  }
  if (value !== null && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      out[k] = sanitizeAuditValue(v, k);
    }
    return out;
  }
  if (typeof value === 'string') {
    return redactAuditText(value);
  }
  return value;
}

export function parseAuditSnapshot(rawValue) {
  if (rawValue === null || rawValue === undefined || rawValue === '') {
    return { kind: 'empty', parsed: null, formatted: '' };
  }

  if (typeof rawValue === 'object') {
    const sanitized = sanitizeAuditValue(rawValue);
    const isArray = Array.isArray(sanitized);
    return {
      kind: isArray ? 'array' : 'object',
      parsed: sanitized,
      formatted: JSON.stringify(sanitized, null, 2),
    };
  }

  const text = String(rawValue);
  const trimmed = text.trim();
  if (!trimmed) {
    return { kind: 'empty', parsed: null, formatted: '' };
  }

  try {
    const parsed = JSON.parse(trimmed);
    const sanitized = sanitizeAuditValue(parsed);
    if (sanitized !== null && typeof sanitized === 'object') {
      return {
        kind: Array.isArray(sanitized) ? 'array' : 'object',
        parsed: sanitized,
        formatted: JSON.stringify(sanitized, null, 2),
      };
    }
    return {
      kind: 'primitive',
      parsed: sanitized,
      formatted: JSON.stringify(sanitized),
    };
  } catch {
    const sanitizedText = redactAuditText(text);
    return {
      kind: 'text',
      parsed: sanitizedText,
      formatted: sanitizedText,
    };
  }
}

function serializeFieldValue(val) {
  if (val === undefined) return '—';
  if (val === null) return 'null';
  if (typeof val === 'object') return JSON.stringify(val);
  return String(val);
}

export function computeAuditDiffRows(beforeValues, afterValues) {
  const beforeSnap = parseAuditSnapshot(beforeValues);
  const afterSnap = parseAuditSnapshot(afterValues);

  const beforeObj = beforeSnap.kind === 'object' ? beforeSnap.parsed : null;
  const afterObj = afterSnap.kind === 'object' ? afterSnap.parsed : null;

  if (!beforeObj && !afterObj) {
    return {
      isStructuredObject: false,
      beforeSnap,
      afterSnap,
      rows: [],
    };
  }

  const keys = Array.from(
    new Set([
      ...(beforeObj ? Object.keys(beforeObj) : []),
      ...(afterObj ? Object.keys(afterObj) : []),
    ])
  ).sort((a, b) => a.localeCompare(b));

  const rows = keys.map((key) => {
    const hasBefore = Boolean(beforeObj && Object.prototype.hasOwnProperty.call(beforeObj, key));
    const hasAfter = Boolean(afterObj && Object.prototype.hasOwnProperty.call(afterObj, key));
    const rawBefore = hasBefore ? beforeObj[key] : undefined;
    const rawAfter = hasAfter ? afterObj[key] : undefined;
    const beforeDisplay = serializeFieldValue(rawBefore);
    const afterDisplay = serializeFieldValue(rawAfter);

    let changeType = 'unchanged';
    if (!hasBefore && hasAfter) {
      changeType = 'added';
    } else if (hasBefore && !hasAfter) {
      changeType = 'removed';
    } else if (JSON.stringify(rawBefore) !== JSON.stringify(rawAfter)) {
      changeType = 'changed';
    }

    return {
      key,
      hasBefore,
      hasAfter,
      beforeDisplay,
      afterDisplay,
      changeType,
    };
  });

  return {
    isStructuredObject: true,
    beforeSnap,
    afterSnap,
    rows,
  };
}

export function summarizeAuditChanges(beforeValues, afterValues) {
  const diff = computeAuditDiffRows(beforeValues, afterValues);
  const hasBefore = diff.beforeSnap.kind !== 'empty';
  const hasAfter = diff.afterSnap.kind !== 'empty';
  const hasSnapshot = hasBefore || hasAfter;
  if (!hasSnapshot) {
    return {
      hasSnapshot: false,
      hasBefore: false,
      hasAfter: false,
      isStructuredObject: false,
      changedCount: 0,
      label: 'No snapshot',
    };
  }
  if (!diff.isStructuredObject) {
    return {
      hasSnapshot: true,
      hasBefore,
      hasAfter,
      isStructuredObject: false,
      changedCount: hasBefore && hasAfter ? 1 : 0,
      label: hasBefore && hasAfter ? 'Before / After text' : hasAfter ? 'Recorded snapshot' : 'Prior snapshot',
    };
  }
  const changedCount = diff.rows.filter((r) => r.changeType !== 'unchanged').length;
  return {
    hasSnapshot: true,
    hasBefore,
    hasAfter,
    isStructuredObject: true,
    changedCount,
    totalKeys: diff.rows.length,
    label:
      changedCount > 0
        ? `${changedCount} field${changedCount === 1 ? '' : 's'} changed`
        : `${diff.rows.length} snapshot field${diff.rows.length === 1 ? '' : 's'}`,
  };
}

const CHANGE_BADGE_MAP = {
  added: 'bg-emerald-100 text-emerald-800 border-emerald-200',
  removed: 'bg-rose-100 text-rose-800 border-rose-200',
  changed: 'bg-amber-100 text-amber-800 border-amber-200',
  unchanged: 'bg-slate-100 text-slate-600 border-slate-200',
};

export default function AuditDiffViewer({
  beforeValues = null,
  afterValues = null,
  beforeLabel = 'Before',
  afterLabel = 'After',
  showUnchangedByDefault = false,
  showEmpty = false,
  className = '',
}) {
  const [viewMode, setViewMode] = useState('diff'); // 'diff' | 'raw'
  const [showUnchanged, setShowUnchanged] = useState(showUnchangedByDefault);

  const diffData = useMemo(
    () => computeAuditDiffRows(beforeValues, afterValues),
    [beforeValues, afterValues]
  );

  const hasBefore = diffData.beforeSnap.kind !== 'empty';
  const hasAfter = diffData.afterSnap.kind !== 'empty';

  if (!hasBefore && !hasAfter) {
    if (!showEmpty) return null;
    return (
      <div className={`text-xs text-slate-500 italic ${className}`.trim()}>
        No before/after state snapshot recorded.
      </div>
    );
  }

  const changedRows = diffData.rows.filter((r) => r.changeType !== 'unchanged');
  const unchangedCount = diffData.rows.length - changedRows.length;
  const visibleRows =
    showUnchanged || changedRows.length === 0 ? diffData.rows : changedRows;

  return (
    <div className={`relative space-y-2 ${className}`.trim()}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center space-x-1.5 text-[11px] font-medium text-slate-500">
          {diffData.isStructuredObject && (
            <span className="tabular-nums">
              {changedRows.length} changed field{changedRows.length === 1 ? '' : 's'}
            </span>
          )}
        </div>

        <div className="flex items-center space-x-1.5">
          {diffData.isStructuredObject && viewMode === 'diff' && unchangedCount > 0 && changedRows.length > 0 && (
            <button
              type="button"
              onClick={() => setShowUnchanged((prev) => !prev)}
              className="px-2 py-0.5 rounded border border-slate-200 bg-white hover:bg-slate-50 text-[11px] font-medium text-slate-600 transition-colors tabular-nums"
            >
              {showUnchanged ? 'Changed only' : `Show all (${diffData.rows.length})`}
            </button>
          )}

          {diffData.isStructuredObject && (
            <button
              type="button"
              onClick={() => setViewMode((prev) => (prev === 'diff' ? 'raw' : 'diff'))}
              aria-pressed={viewMode === 'raw'}
              className="inline-flex items-center space-x-1 px-2 py-0.5 rounded border border-slate-200 bg-white hover:bg-slate-50 text-[11px] font-medium text-slate-600 transition-colors"
            >
              {viewMode === 'raw' ? (
                <>
                  <GitCompare className="w-3 h-3 text-pharma-600" aria-hidden="true" />
                  <span>Structured Diff</span>
                </>
              ) : (
                <>
                  <Code2 className="w-3 h-3 text-slate-500" aria-hidden="true" />
                  <span>Raw JSON</span>
                </>
              )}
            </button>
          )}
        </div>
      </div>

      {diffData.isStructuredObject && viewMode === 'diff' ? (
        <div className="border border-slate-200 rounded-lg overflow-hidden bg-white max-w-full min-w-0">
          <div className="divide-y divide-slate-100 text-[11px]">
            {visibleRows.map((row) => (
              <div
                key={row.key}
                className="px-2.5 py-2 space-y-1.5 min-w-0"
              >
                <div className="flex flex-wrap items-center justify-between gap-1.5 min-w-0">
                  <span className="font-mono font-semibold text-slate-800 break-words min-w-0">
                    {row.key}
                  </span>
                  <span
                    className={`px-1.5 py-0.5 rounded border text-[10px] font-bold uppercase shrink-0 ${
                      CHANGE_BADGE_MAP[row.changeType]
                    }`}
                  >
                    {row.changeType}
                  </span>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5 min-w-0">
                  <div className="min-w-0">
                    {row.hasBefore ? (
                      <div
                        className={`relative overflow-hidden font-mono rounded px-1.5 py-0.5 break-all tabular-nums ${
                          row.changeType === 'changed' || row.changeType === 'removed'
                            ? 'bg-rose-50 text-rose-900 border border-rose-200/70'
                            : 'bg-slate-50 text-slate-600 border border-slate-200/70'
                        }`}
                      >
                        <span className="sr-only">{beforeLabel}: </span>
                        {row.beforeDisplay}
                      </div>
                    ) : (
                      <span className="text-slate-500 italic font-mono">—</span>
                    )}
                  </div>

                  <div className="min-w-0">
                    {row.hasAfter ? (
                      <div
                        className={`relative overflow-hidden font-mono rounded px-1.5 py-0.5 break-all tabular-nums ${
                          row.changeType === 'changed' || row.changeType === 'added'
                            ? 'bg-emerald-50 text-emerald-900 border border-emerald-200/70'
                            : 'bg-slate-50 text-slate-600 border border-slate-200/70'
                        }`}
                      >
                        <span className="sr-only">{afterLabel}: </span>
                        {row.afterDisplay}
                      </div>
                    ) : (
                      <span className="text-slate-500 italic font-mono">—</span>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-1.5 min-w-0 max-w-full">
          {hasBefore && (
            <div className="bg-rose-50/70 border border-rose-200/80 rounded-lg p-2">
              <div className="text-[10px] font-bold uppercase tracking-wider text-rose-700 mb-1">
                {beforeLabel}
              </div>
              <pre className="font-mono text-[11px] text-rose-950 whitespace-pre-wrap break-all m-0 tabular-nums">
                {diffData.beforeSnap.formatted}
              </pre>
            </div>
          )}
          {hasAfter && (
            <div className="bg-emerald-50/70 border border-emerald-200/80 rounded-lg p-2">
              <div className="text-[10px] font-bold uppercase tracking-wider text-emerald-700 mb-1">
                {afterLabel}
              </div>
              <pre className="font-mono text-[11px] text-emerald-950 whitespace-pre-wrap break-all m-0 tabular-nums">
                {diffData.afterSnap.formatted}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

