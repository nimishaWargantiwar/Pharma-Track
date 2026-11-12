import { apiRequest, buildQueryString } from './apiClient.js';

/**
 * Retrieve completed manufacturing batches eligible for QA review.
 * GET /api/quality/batches
 * GET /api/quality/batches?qaStatus={status}
 *
 * @param {string} [qaStatus] optional BatchQualityStatus filter
 * @returns {Promise<Array>} List<QualityQueueItemDto>
 */
export async function getQualityQueue(qaStatus) {
  const qs = buildQueryString({ qaStatus }, { ignoreAllSentinel: false });
  const path = qs ? `/quality/batches?${qs}` : '/quality/batches';
  return apiRequest(path, { cache: 'no-store' });
}

/**
 * Retrieve full Batch Quality Dossier including QC checks, QA reviews, and audit logs.
 * GET /api/quality/batches/{batchId}
 *
 * @param {number|string} batchId batch ID
 * @returns {Promise<Object>} BatchQualityDossierDto
 */
export async function getBatchQualityDossier(batchId) {
  if (batchId === undefined || batchId === null || String(batchId).trim() === '') {
    throw new Error('Batch ID is required');
  }
  return apiRequest(`/quality/batches/${encodeURIComponent(batchId)}`, {
    cache: 'no-store',
  });
}

/**
 * Record a new Quality Control (QC) check against a completed batch.
 * POST /api/quality/batches/{batchId}/checks
 *
 * @param {number|string} batchId batch ID
 * @param {Object} checkData QualityCheckRequestDto payload
 * @returns {Promise<Object>} QualityCheckResponseDto
 */
export async function createQualityCheck(batchId, checkData) {
  if (batchId === undefined || batchId === null || String(batchId).trim() === '') {
    throw new Error('Batch ID is required');
  }
  return apiRequest(`/quality/batches/${encodeURIComponent(batchId)}/checks`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(checkData || {}),
  });
}

/**
 * Submit an audited correction (or complete a PENDING QC check) with mandatory reason.
 * Actor identity is derived server-side from JWT.
 * POST /api/quality/checks/{checkId}/correct
 *
 * @param {number|string} checkId QC check ID
 * @param {Object} correctionData QualityCheckCorrectionRequestDto payload
 * @returns {Promise<Object>} QualityCheckResponseDto
 */
export async function correctQualityCheck(checkId, correctionData) {
  if (checkId === undefined || checkId === null || String(checkId).trim() === '') {
    throw new Error('QC check ID is required');
  }
  return apiRequest(`/quality/checks/${encodeURIComponent(checkId)}/correct`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(correctionData || {}),
  });
}

/**
 * Record a QA batch disposition decision (RELEASED, ON_HOLD, REJECTED).
 * POST /api/quality/batches/{batchId}/disposition
 *
 * @param {number|string} batchId batch ID
 * @param {Object} dispositionData QaDispositionRequestDto payload
 * @returns {Promise<Object>} updated BatchQualityDossierDto
 */
export async function recordQaDisposition(batchId, dispositionData) {
  if (batchId === undefined || batchId === null || String(batchId).trim() === '') {
    throw new Error('Batch ID is required');
  }
  return apiRequest(`/quality/batches/${encodeURIComponent(batchId)}/disposition`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(dispositionData || {}),
  });
}

/**
 * Query read-only append-only audit log entries.
 * GET /api/quality/audit-logs
 *
 * @param {Object} [filters]
 * @param {number|string} [filters.batchId]
 * @param {string} [filters.entityType]
 * @param {number|string} [filters.entityId]
 * @returns {Promise<Array>} List<AuditLogResponseDto>
 */
export async function getAuditLogs({ batchId, entityType, entityId } = {}) {
  const qs = buildQueryString(
    { batchId, entityType, entityId },
    { ignoreAllSentinel: false }
  );
  const path = qs ? `/quality/audit-logs?${qs}` : '/quality/audit-logs';
  return apiRequest(path, { cache: 'no-store' });
}
