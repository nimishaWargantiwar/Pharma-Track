import {
  parseBackendTimestampToDate,
} from './dateTimeUtils.js';
import {
  normalizeOccurredAtForApi,
} from './deviationUtils.js';

export const normalizeQcCheckedAtForApi = normalizeOccurredAtForApi;

const STRICT_ISO_DATETIME_INPUT_REGEX =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:[.,]\d{1,9})?)?(?:Z|[+-]\d{2}:?\d{2})?$/i;

export function validateQcCheckedAt(checkedAt, referenceNow = new Date()) {
  if (checkedAt == null) return null;
  if (typeof checkedAt !== 'string') {
    return 'Invalid check timestamp';
  }
  const trimmed = checkedAt.trim();
  if (!trimmed) {
    return null;
  }

  if (!STRICT_ISO_DATETIME_INPUT_REGEX.test(trimmed)) {
    return 'Invalid check timestamp';
  }

  const parsedDate = parseBackendTimestampToDate(trimmed);
  if (!parsedDate) {
    return 'Invalid check timestamp';
  }

  const refDate = parseBackendTimestampToDate(referenceNow) || new Date();
  if (parsedDate.getTime() > refDate.getTime() + 5 * 60 * 1000) {
    return 'Check timestamp cannot be in the future (Asia/Kolkata)';
  }
  return null;
}

export const QA_RELEASE_POLICY_COPY = {
  ruleSummary:
    'At least one required QC check must exist, every recorded QC check (required and optional) must have a PASS result, and zero unresolved deviations may remain before QA RELEASED disposition.',
  requiredLabel: 'Required QC Check',
  optionalLabel: 'Optional QC Check',
  requiredHelp:
    'Required checks must be recorded (at least one required check per batch) and have a PASS result before QA RELEASED disposition.',
  optionalHelp:
    'Optional checks do not need to be recorded. However, any recorded optional check must also have a PASS result — PENDING or FAIL still blocks QA RELEASED disposition.',
  qcSectionSubtitle:
    'Required checks must be recorded and PASS; optional checks need not be recorded, but any recorded optional check must also PASS.',
  eligibleBannerTitle: 'Batch Meets All QA Release Prerequisites',
  eligibleBannerDescription:
    'At least one required QC check is recorded, all recorded QC checks (required and optional) have passed, and zero unresolved deviations remain. Review eBR execution history before recording QA release.',
  blockedBannerTitle: 'QA Release Currently Blocked',
  blockedPolicyNote:
    'ON_HOLD and REJECTED dispositions remain permitted when release blockers exist. Closing a deviation never automatically releases a batch.',
};

function toNonNegativeInt(value, fallback = 0) {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.max(0, Math.trunc(value));
  }
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) {
      return Math.max(0, Math.trunc(parsed));
    }
  }
  return Math.max(0, Math.trunc(fallback));
}

function summarizeCheckBreakdown(record, checksArray) {
  const total = toNonNegativeInt(
    record.totalChecksCount,
    checksArray ? checksArray.length : 0
  );
  const passed = toNonNegativeInt(
    record.passedChecksCount,
    checksArray ? checksArray.filter((c) => c.result === 'PASS').length : 0
  );
  const failed = toNonNegativeInt(
    record.failedChecksCount,
    checksArray ? checksArray.filter((c) => c.result === 'FAIL').length : 0
  );
  const pending = toNonNegativeInt(
    record.pendingChecksCount,
    checksArray
      ? checksArray.filter((c) => c.result !== 'PASS' && c.result !== 'FAIL').length
      : 0
  );
  const required = toNonNegativeInt(
    record.requiredChecksCount,
    checksArray ? checksArray.filter((c) => Boolean(c.required)).length : 0
  );
  const optional = toNonNegativeInt(
    record.optionalChecksCount,
    Math.max(0, total - required)
  );
  const blocking = toNonNegativeInt(record.blockingChecksCount, failed + pending);

  return { total, passed, failed, pending, required, optional, blocking };
}

/**
 * Assess QA release eligibility and structured release blockers consistently
 * across the Quality Inspection Queue, Batch Quality Dossier, and QA Disposition Modal.
 *
 * Works identically on QualityQueueItemDto and BatchQualityDossierDto objects.
 */
export function assessBatchReleaseEligibility(record) {
  if (!record || typeof record !== 'object') {
    return {
      qaStatus: 'PENDING_QA',
      isFinalized: false,
      isEligible: false,
      statusKey: 'BLOCKED',
      statusLabel: 'Blocked',
      blockers: ['Batch quality data is unavailable'],
      blockerChips: [
        { key: 'unavailable', label: 'Data Unavailable', tone: 'amber' },
      ],
      qcCounts: {
        total: 0,
        passed: 0,
        failed: 0,
        pending: 0,
        required: 0,
        optional: 0,
        blocking: 0,
      },
      openDeviationsCount: 0,
    };
  }

  const qaStatus = record.qaStatus || 'PENDING_QA';
  const batchStatus = record.batchStatus || 'COMPLETED';
  const isFinalized = qaStatus === 'RELEASED' || qaStatus === 'REJECTED';

  const checksArray = Array.isArray(record.qualityChecks) ? record.qualityChecks : null;
  const qcCounts = summarizeCheckBreakdown(record, checksArray);

  const deviationsArray = Array.isArray(record.deviations) ? record.deviations : null;
  const openDeviationsCount = toNonNegativeInt(
    record.openDeviationsCount,
    deviationsArray
      ? deviationsArray.filter((d) => d.status !== 'CLOSED').length
      : 0
  );

  const derivedBlockers = [];
  if (!isFinalized) {
    if (batchStatus !== 'COMPLETED') {
      derivedBlockers.push(
        `Manufacturing batch status must be COMPLETED (current: ${batchStatus})`
      );
    }

    if (qcCounts.total === 0) {
      derivedBlockers.push(
        'At least one QC check must be recorded (at least one required QC check must exist and PASS)'
      );
    } else {
      if (qcCounts.required === 0) {
        derivedBlockers.push('At least one required QC check must exist and PASS');
      }

      if (qcCounts.failed > 0) {
        if (checksArray) {
          const reqFailed = checksArray.filter(
            (c) => Boolean(c.required) && c.result === 'FAIL'
          ).length;
          const optFailed = Math.max(0, qcCounts.failed - reqFailed);
          if (reqFailed > 0) {
            derivedBlockers.push(`${reqFailed} required QC check(s) have FAILED`);
          }
          if (optFailed > 0) {
            derivedBlockers.push(`${optFailed} optional QC check(s) have FAILED`);
          }
        } else if (qcCounts.required === 0) {
          derivedBlockers.push(`${qcCounts.failed} optional QC check(s) have FAILED`);
        } else {
          derivedBlockers.push(`${qcCounts.failed} QC check(s) have FAILED`);
        }
      }

      if (qcCounts.pending > 0) {
        if (checksArray) {
          const reqPending = checksArray.filter(
            (c) => Boolean(c.required) && c.result !== 'PASS' && c.result !== 'FAIL'
          ).length;
          const optPending = Math.max(0, qcCounts.pending - reqPending);
          if (reqPending > 0 && optPending === 0) {
            derivedBlockers.push(`${reqPending} QC check(s) are still PENDING`);
          } else {
            if (reqPending > 0) {
              derivedBlockers.push(
                `${reqPending} required QC check(s) are still PENDING`
              );
            }
            if (optPending > 0) {
              derivedBlockers.push(
                `${optPending} optional QC check(s) are still PENDING`
              );
            }
          }
        } else if (qcCounts.required === 0) {
          derivedBlockers.push(`${qcCounts.pending} optional QC check(s) are still PENDING`);
        } else {
          derivedBlockers.push(`${qcCounts.pending} QC check(s) are still PENDING`);
        }
      }
    }

    if (openDeviationsCount > 0) {
      derivedBlockers.push(
        `${openDeviationsCount} unresolved deviation(s) must be CLOSED before QA release`
      );
    }
  }

  const hasBackendBlockers =
    Array.isArray(record.releaseBlockers) && record.releaseBlockers.length > 0;
  const hasBackendEligible = typeof record.eligibleForRelease === 'boolean';

  const blockers = isFinalized
    ? []
    : hasBackendBlockers
      ? record.releaseBlockers
      : derivedBlockers;

  const isEligible = isFinalized
    ? false
    : hasBackendEligible
      ? record.eligibleForRelease && derivedBlockers.length === 0
      : derivedBlockers.length === 0;

  const blockerChips = [];
  if (!isFinalized) {
    if (batchStatus !== 'COMPLETED') {
      blockerChips.push({
        key: 'batch_status',
        label: `Mfg: ${batchStatus}`,
        tone: 'amber',
      });
    }

    if (qcCounts.total === 0) {
      blockerChips.push({
        key: 'no_qc',
        label: 'No QC Checks (Req. Missing)',
        tone: 'amber',
      });
    } else if (qcCounts.required === 0) {
      blockerChips.push({
        key: 'missing_required_qc',
        label: 'Missing Required QC',
        tone: 'rose',
      });
    }

    if (qcCounts.failed > 0) {
      blockerChips.push({
        key: 'failed_qc',
        label: `${qcCounts.failed} Failed QC`,
        tone: 'rose',
      });
    }

    if (qcCounts.pending > 0) {
      blockerChips.push({
        key: 'pending_qc',
        label: `${qcCounts.pending} Pending QC`,
        tone: 'amber',
      });
    }

    if (openDeviationsCount > 0) {
      blockerChips.push({
        key: 'open_deviations',
        label: `${openDeviationsCount} Open Dev`,
        tone: 'rose',
      });
    }

    if (!isEligible && blockerChips.length === 0) {
      blockerChips.push({
        key: 'blocked',
        label: 'Release Blocked',
        tone: 'amber',
      });
    }
  }

  let statusKey = 'BLOCKED';
  let statusLabel = 'Blocked';
  if (qaStatus === 'RELEASED') {
    statusKey = 'FINALIZED_RELEASED';
    statusLabel = 'Finalized';
  } else if (qaStatus === 'REJECTED') {
    statusKey = 'FINALIZED_REJECTED';
    statusLabel = 'Finalized';
  } else if (isEligible) {
    statusKey = 'READY';
    statusLabel = 'Eligible for Release';
  }

  return {
    qaStatus,
    isFinalized,
    isEligible,
    statusKey,
    statusLabel,
    blockers,
    blockerChips,
    qcCounts,
    openDeviationsCount,
  };
}

