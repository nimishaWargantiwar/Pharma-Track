import { apiRequest } from './apiClient.js';
import { generateClientRequestId } from '../utils/requestIdUtils.js';

export { generateClientRequestId };

/**
 * Validate that an identifier represents a valid positive numeric database ID.
 *
 * @param {number|string} id Identifier value to validate
 * @param {string} [name='ID'] Contextual name for the error message
 * @returns {string} Normalized string representation of the numeric ID
 * @throws {Error} If ID is missing, non-numeric, zero, negative, or not a safe integer
 */
export function validateNumericId(id, name = 'ID') {
  if (id === null || id === undefined || id === '') {
    throw new Error(`Valid numeric ${name} is required`);
  }
  const str = String(id).trim();
  if (!/^\d+$/.test(str) || Number(str) <= 0 || !Number.isSafeInteger(Number(str))) {
    throw new Error(`Valid numeric ${name} is required`);
  }
  return str;
}

/**
 * Initiate dedicated AI Batch Analysis Report generation for a batch.
 * POST /api/batches/{batchId}/analysis-reports
 *
 * <p>Enforces idempotency using the required {@code clientRequestId}.
 * Supports {@code FULL} analysis mode (comprehensive 12 sections) and
 * {@code FOCUSED} analysis mode (requires non-empty {@code focusAreas}).</p>
 *
 * @param {number|string} batchId Valid numeric database batch ID (mandatory)
 * @param {Object} [options]
 * @param {string} [options.clientRequestId] Optional idempotency tracking key (must be 8-64 characters)
 * @param {string} [options.analysisMode] Optional analysis mode ('FULL' or 'FOCUSED')
 * @param {Array<string>} [options.focusAreas] Optional array of focus area identifiers (required for FOCUSED)
 * @returns {Promise<Object>} BatchAnalysisReportResponseDto
 * @throws {Error} If batchId is invalid, clientRequestId length is out of range, or backend returns an error
 */
export async function generateBatchAnalysisReport(batchId, { clientRequestId, analysisMode, focusAreas } = {}) {
  const cleanBatchId = validateNumericId(batchId, 'batch ID');

  let reqId;
  if (clientRequestId !== undefined && clientRequestId !== null && String(clientRequestId).trim() !== '') {
    reqId = String(clientRequestId).trim();
    if (reqId.length < 8 || reqId.length > 64) {
      throw new Error('clientRequestId must be between 8 and 64 characters');
    }
  } else {
    reqId = generateClientRequestId();
  }

  const payload = {
    clientRequestId: reqId,
  };

  if (analysisMode === 'FOCUSED') {
    payload.analysisMode = 'FOCUSED';
    if (!Array.isArray(focusAreas) || focusAreas.length === 0) {
      throw new Error('At least one focus area is required for FOCUSED analysis mode');
    }
    payload.focusAreas = focusAreas;
  } else if (analysisMode === 'FULL') {
    payload.analysisMode = 'FULL';
    // FULL mode strictly must not submit focus areas
  }

  return apiRequest(`/batches/${encodeURIComponent(cleanBatchId)}/analysis-reports`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });
}

/**
 * List historical AI Batch Analysis Reports for a batch in reverse chronological order.
 * GET /api/batches/{batchId}/analysis-reports?page=0&size=20
 *
 * <p>The backend returns a direct JSON array of {@code BatchAnalysisReportSummaryDto} objects,
 * ordered by {@code createdAt DESC}. Unexpected response shapes are rejected.</p>
 *
 * @param {number|string} batchId Valid numeric database batch ID (mandatory)
 * @param {Object} [pagination]
 * @param {number} [pagination.page=0] 0-based page index (defaults to 0)
 * @param {number} [pagination.size=20] Page size limit (defaults to 20, clamped between 1 and 100)
 * @returns {Promise<Array<Object>>} Direct JSON array of BatchAnalysisReportSummaryDto objects
 * @throws {Error} If batchId is invalid, response is not an array, or backend returns an error
 */
export async function listBatchAnalysisReports(batchId, { page = 0, size = 20, includeArchived = false } = {}) {
  const cleanBatchId = validateNumericId(batchId, 'batch ID');

  const pageNum = page != null && !Number.isNaN(Number(page))
    ? Math.max(0, Math.floor(Number(page)))
    : 0;

  const pageSize = size != null && !Number.isNaN(Number(size))
    ? Math.min(100, Math.max(1, Math.floor(Number(size))))
    : 20;

  const params = new URLSearchParams();
  params.set('page', String(pageNum));
  params.set('size', String(pageSize));
  if (includeArchived) {
    params.set('includeArchived', 'true');
  }

  const result = await apiRequest(
    `/batches/${encodeURIComponent(cleanBatchId)}/analysis-reports?${params.toString()}`,
    {
      method: 'GET',
    }
  );

  if (!Array.isArray(result)) {
    throw new Error('Unexpected response format: expected an array of report summaries');
  }

  return result;
}

/**
 * Retrieve complete details of a specific AI Batch Analysis Report scoped to its parent batch.
 * GET /api/batches/{batchId}/analysis-reports/{reportId}
 *
 * <p>The backend enforces that the report belongs to the specified batchId (scoped lookup).
 * Returns the full {@code BatchAnalysisReportResponseDto}.</p>
 *
 * @param {number|string} batchId Valid numeric database batch ID (mandatory)
 * @param {number|string} reportId Valid numeric report ID (mandatory)
 * @returns {Promise<Object>} BatchAnalysisReportResponseDto
 * @throws {Error} If batchId or reportId is invalid, response is not an object, or backend returns an error
 */
export async function getBatchAnalysisReportDetails(batchId, reportId) {
  const cleanBatchId = validateNumericId(batchId, 'batch ID');
  const cleanReportId = validateNumericId(reportId, 'report ID');

  const result = await apiRequest(
    `/batches/${encodeURIComponent(cleanBatchId)}/analysis-reports/${encodeURIComponent(cleanReportId)}`,
    {
      method: 'GET',
    }
  );

  if (!result || typeof result !== 'object' || Array.isArray(result)) {
    throw new Error('Unexpected response format: expected a report detail object');
  }

  return result;
}

/**
 * Archive an AI Batch Analysis Report to remove it from active operational history while
 * strictly preserving its historical content and recording an append-only GxP audit trail entry.
 * PATCH /api/batches/{batchId}/analysis-reports/{reportId}/archive
 *
 * @param {number|string} batchId Valid numeric database batch ID (mandatory)
 * @param {number|string} reportId Valid numeric report ID (mandatory)
 * @param {Object} options
 * @param {string} options.reason Mandatory reason for archiving the report
 * @returns {Promise<Object>} Updated BatchAnalysisReportResponseDto
 * @throws {Error} If batchId or reportId is invalid, reason is missing/blank, or backend returns an error
 */
export async function archiveBatchAnalysisReport(batchId, reportId, { reason } = {}) {
  const cleanBatchId = validateNumericId(batchId, 'batch ID');
  const cleanReportId = validateNumericId(reportId, 'report ID');

  const trimmedReason = typeof reason === 'string' ? reason.trim() : '';
  if (!trimmedReason) {
    throw new Error('Archive reason is mandatory and cannot be blank.');
  }
  if (trimmedReason.length > 500) {
    throw new Error('Archive reason must not exceed 500 characters.');
  }

  const payload = { reason: trimmedReason };

  const result = await apiRequest(
    `/batches/${encodeURIComponent(cleanBatchId)}/analysis-reports/${encodeURIComponent(cleanReportId)}/archive`,
    {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    }
  );

  if (!result || typeof result !== 'object' || Array.isArray(result)) {
    throw new Error('Unexpected response format: expected an updated report detail object');
  }

  return result;
}

/**
 * Permanently delete an AI Batch Analysis Report in local development / test environments.
 * Strictly restricted to authenticated Administrators. Records a durable audit event before deletion.
 * DELETE /api/batches/{batchId}/analysis-reports/{reportId}
 *
 * @param {number|string} batchId Valid numeric database batch ID (mandatory)
 * @param {number|string} reportId Valid numeric report ID (mandatory)
 * @param {Object} options
 * @param {string} options.reason Mandatory deletion reason (max 500 characters)
 * @param {string} options.confirmationKeyword Exact confirmation keyword ('PERMANENTLY DELETE')
 * @returns {Promise<void>} Resolves when deletion completes (204 No Content)
 * @throws {Error} If parameters are invalid, keyword does not match, or backend rejects the request
 */
export async function permanentDeleteBatchAnalysisReport(batchId, reportId, { reason, confirmationKeyword } = {}) {
  const cleanBatchId = validateNumericId(batchId, 'batch ID');
  const cleanReportId = validateNumericId(reportId, 'report ID');

  const trimmedReason = typeof reason === 'string' ? reason.trim() : '';
  if (!trimmedReason) {
    throw new Error('Deletion reason is mandatory and cannot be blank.');
  }
  if (trimmedReason.length > 500) {
    throw new Error('Deletion reason must not exceed 500 characters.');
  }

  const trimmedKeyword = typeof confirmationKeyword === 'string' ? confirmationKeyword.trim() : '';
  if (trimmedKeyword !== 'PERMANENTLY DELETE') {
    throw new Error("Confirmation keyword must be exactly 'PERMANENTLY DELETE'.");
  }

  const payload = {
    reason: trimmedReason,
    confirmationKeyword: trimmedKeyword,
  };

  await apiRequest(
    `/batches/${encodeURIComponent(cleanBatchId)}/analysis-reports/${encodeURIComponent(cleanReportId)}`,
    {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    }
  );
}
