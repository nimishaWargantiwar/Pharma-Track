import { apiRequest, buildQueryString } from './apiClient.js';

function requireId(value, label) {
  if (value === undefined || value === null || String(value).trim() === '') {
    throw new Error(`${label} is required`);
  }
}

// -----------------------------------------------------------------------------
// A. Deviation Query Endpoints
// -----------------------------------------------------------------------------

/**
 * List deviations with optional status, severity, priority, category, and batch filters.
 * GET /api/quality/deviations
 *
 * @param {Object} [filters]
 * @param {string} [filters.status]
 * @param {string} [filters.severity]
 * @param {string} [filters.priority]
 * @param {string} [filters.category]
 * @param {number|string} [filters.batchId]
 * @returns {Promise<Array>} List<DeviationSummaryDto>
 */
export async function getDeviations({ status, severity, priority, category, batchId } = {}) {
  const qs = buildQueryString({ status, severity, priority, category, batchId });
  const path = qs ? `/quality/deviations?${qs}` : '/quality/deviations';
  return apiRequest(path, { cache: 'no-store' });
}

/**
 * Retrieve deviations linked to a specific manufacturing batch.
 * GET /api/quality/batches/{batchId}/deviations
 *
 * @param {number|string} batchId
 * @returns {Promise<Array>} List<DeviationSummaryDto>
 */
export async function getBatchDeviations(batchId) {
  requireId(batchId, 'Batch ID');
  return apiRequest(`/quality/batches/${encodeURIComponent(batchId)}/deviations`, {
    cache: 'no-store',
  });
}

/**
 * Retrieve linkable QC checks, eBR execution steps, and audit log events for a batch.
 * GET /api/quality/batches/{batchId}/linkable-records
 *
 * @param {number|string} batchId
 * @returns {Promise<Object>} BatchLinkableRecordsDto
 */
export async function getBatchLinkableRecords(batchId) {
  requireId(batchId, 'Batch ID');
  return apiRequest(`/quality/batches/${encodeURIComponent(batchId)}/linkable-records`, {
    cache: 'no-store',
  });
}

/**
 * Retrieve full details for a single deviation including RCA, CAPA actions,
 * closure readiness, and scoped audit logs.
 * GET /api/quality/deviations/{deviationId}
 *
 * @param {number|string} deviationId
 * @returns {Promise<Object>} DeviationDetailDto
 */
export async function getDeviationById(deviationId) {
  requireId(deviationId, 'Deviation ID');
  return apiRequest(`/quality/deviations/${encodeURIComponent(deviationId)}`, {
    cache: 'no-store',
  });
}

// -----------------------------------------------------------------------------
// B. Deviation Creation & Lifecycle Transitions
// -----------------------------------------------------------------------------

/**
 * Report a new manufacturing or quality deviation (status = OPEN).
 * POST /api/quality/deviations
 *
 * @param {Object} payload CreateDeviationRequestDto
 * @returns {Promise<Object>} DeviationDetailDto
 */
export async function createDeviation(payload) {
  return apiRequest('/quality/deviations', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload || {}),
  });
}

/**
 * Transition deviation from OPEN to UNDER_INVESTIGATION.
 * POST /api/quality/deviations/{deviationId}/start-investigation
 *
 * @param {number|string} deviationId
 * @param {Object} payload StartInvestigationRequestDto
 * @returns {Promise<Object>} DeviationDetailDto
 */
export async function startDeviationInvestigation(deviationId, payload) {
  requireId(deviationId, 'Deviation ID');
  return apiRequest(
    `/quality/deviations/${encodeURIComponent(deviationId)}/start-investigation`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload || {}),
    }
  );
}

/**
 * Transition deviation from UNDER_INVESTIGATION to ROOT_CAUSE_IDENTIFIED.
 * POST /api/quality/deviations/{deviationId}/record-root-cause
 *
 * @param {number|string} deviationId
 * @param {Object} payload RecordRootCauseRequestDto
 * @returns {Promise<Object>} DeviationDetailDto
 */
export async function recordDeviationRootCause(deviationId, payload) {
  requireId(deviationId, 'Deviation ID');
  return apiRequest(
    `/quality/deviations/${encodeURIComponent(deviationId)}/record-root-cause`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload || {}),
    }
  );
}

/**
 * Transition deviation from ROOT_CAUSE_IDENTIFIED to CAPA_IN_PROGRESS.
 * POST /api/quality/deviations/{deviationId}/start-capa
 *
 * @param {number|string} deviationId
 * @param {Object} payload StartCapaRequestDto
 * @returns {Promise<Object>} DeviationDetailDto
 */
export async function startDeviationCapa(deviationId, payload) {
  requireId(deviationId, 'Deviation ID');
  return apiRequest(
    `/quality/deviations/${encodeURIComponent(deviationId)}/start-capa`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload || {}),
    }
  );
}

/**
 * Transition deviation from CAPA_IN_PROGRESS to PENDING_VERIFICATION.
 * POST /api/quality/deviations/{deviationId}/submit-verification
 *
 * @param {number|string} deviationId
 * @param {Object} payload SubmitVerificationRequestDto
 * @returns {Promise<Object>} DeviationDetailDto
 */
export async function submitDeviationForVerification(deviationId, payload) {
  requireId(deviationId, 'Deviation ID');
  return apiRequest(
    `/quality/deviations/${encodeURIComponent(deviationId)}/submit-verification`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload || {}),
    }
  );
}

/**
 * Return a PENDING_VERIFICATION deviation back to CAPA_IN_PROGRESS with a mandatory reason.
 * POST /api/quality/deviations/{deviationId}/reopen-capa
 *
 * @param {number|string} deviationId
 * @param {Object} payload ReopenCapaRequestDto
 * @returns {Promise<Object>} DeviationDetailDto
 */
export async function reopenDeviationCapa(deviationId, payload) {
  requireId(deviationId, 'Deviation ID');
  return apiRequest(
    `/quality/deviations/${encodeURIComponent(deviationId)}/reopen-capa`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload || {}),
    }
  );
}

/**
 * Verify effectiveness and close a PENDING_VERIFICATION deviation (terminal CLOSED state).
 * POST /api/quality/deviations/{deviationId}/close
 *
 * @param {number|string} deviationId
 * @param {Object} payload CloseDeviationRequestDto
 * @returns {Promise<Object>} DeviationDetailDto
 */
export async function closeDeviation(deviationId, payload) {
  requireId(deviationId, 'Deviation ID');
  return apiRequest(
    `/quality/deviations/${encodeURIComponent(deviationId)}/close`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload || {}),
    }
  );
}

// -----------------------------------------------------------------------------
// C. CAPA Action Creation & Lifecycle Endpoints
// -----------------------------------------------------------------------------

/**
 * Create a new Corrective or Preventive Action linked to a deviation.
 * POST /api/quality/deviations/{deviationId}/capas
 *
 * @param {number|string} deviationId
 * @param {Object} payload CreateCapaActionRequestDto
 * @returns {Promise<Object>} CapaActionResponseDto
 */
export async function createCapaAction(deviationId, payload) {
  requireId(deviationId, 'Deviation ID');
  return apiRequest(
    `/quality/deviations/${encodeURIComponent(deviationId)}/capas`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload || {}),
    }
  );
}

/**
 * Start work on an OPEN CAPA action.
 * POST /api/quality/capas/{capaId}/start
 *
 * @param {number|string} capaId
 * @param {Object} payload StartCapaActionRequestDto
 * @returns {Promise<Object>} CapaActionResponseDto
 */
export async function startCapaAction(capaId, payload) {
  requireId(capaId, 'CAPA action ID');
  return apiRequest(
    `/quality/capas/${encodeURIComponent(capaId)}/start`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload || {}),
    }
  );
}

/**
 * Complete an OPEN or IN_PROGRESS CAPA action with mandatory completion evidence.
 * POST /api/quality/capas/{capaId}/complete
 *
 * @param {number|string} capaId
 * @param {Object} payload CompleteCapaActionRequestDto
 * @returns {Promise<Object>} CapaActionResponseDto
 */
export async function completeCapaAction(capaId, payload) {
  requireId(capaId, 'CAPA action ID');
  return apiRequest(
    `/quality/capas/${encodeURIComponent(capaId)}/complete`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload || {}),
    }
  );
}

/**
 * Cancel an OPEN or IN_PROGRESS CAPA action with a mandatory reason.
 * POST /api/quality/capas/{capaId}/cancel
 *
 * @param {number|string} capaId
 * @param {Object} payload CancelCapaActionRequestDto
 * @returns {Promise<Object>} CapaActionResponseDto
 */
export async function cancelCapaAction(capaId, payload) {
  requireId(capaId, 'CAPA action ID');
  return apiRequest(
    `/quality/capas/${encodeURIComponent(capaId)}/cancel`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload || {}),
    }
  );
}
