import { apiRequest } from './apiClient.js';

/**
 * Retrieve active production batches in the shop-floor execution queue.
 * GET /api/batches/execution
 *
 * @returns {Promise<Array>} List<BatchExecutionQueueItemDto>
 */
export async function getExecutionQueue() {
  return apiRequest('/batches/execution', {
    cache: 'no-store',
  });
}

/**
 * Retrieve the execution workspace and ordered steps for a specific batch.
 * Read-only GET endpoint; supports both IN_PROGRESS and COMPLETED batches.
 * GET /api/batches/{batchId}/steps
 *
 * @param {number|string} batchId batch ID
 * @returns {Promise<Object>} BatchExecutionWorkspaceDto
 */
export async function getBatchExecutionWorkspace(batchId) {
  if (batchId === undefined || batchId === null || String(batchId).trim() === '') {
    throw new Error('Batch ID is required');
  }
  return apiRequest(`/batches/${encodeURIComponent(batchId)}/steps`, {
    cache: 'no-store',
  });
}

/**
 * Explicitly initialize (or idempotently retrieve existing) execution steps
 * for an already IN_PROGRESS batch missing step records.
 * POST /api/batches/{batchId}/steps/initialize
 *
 * @param {number|string} batchId batch ID
 * @returns {Promise<Object>} BatchExecutionWorkspaceDto
 */
export async function initializeBatchExecutionSteps(batchId) {
  if (batchId === undefined || batchId === null || String(batchId).trim() === '') {
    throw new Error('Batch ID is required');
  }
  return apiRequest(`/batches/${encodeURIComponent(batchId)}/steps/initialize`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
  });
}

/**
 * Start a sequential PENDING execution step within an active production batch.
 * PATCH /api/batches/{batchId}/steps/{stepId}/start
 *
 * @param {number|string} batchId batch ID
 * @param {number|string} stepId execution step ID
 * @returns {Promise<Object>} BatchExecutionStepResponseDto
 */
export async function startExecutionStep(batchId, stepId) {
  if (batchId === undefined || batchId === null || String(batchId).trim() === '') {
    throw new Error('Batch ID is required');
  }
  if (stepId === undefined || stepId === null || String(stepId).trim() === '') {
    throw new Error('Execution step ID is required');
  }
  return apiRequest(
    `/batches/${encodeURIComponent(batchId)}/steps/${encodeURIComponent(stepId)}/start`,
    {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
      },
    }
  );
}

/**
 * Complete an IN_PROGRESS execution step with mandatory actual value
 * and optional observations. Operator identity is derived server-side from JWT.
 * PATCH /api/batches/{batchId}/steps/{stepId}/complete
 *
 * @param {number|string} batchId batch ID
 * @param {number|string} stepId execution step ID
 * @param {Object} stepData CompleteExecutionStepRequestDto payload
 * @param {string} stepData.actualValue required actual measured/observed value (max 255)
 * @param {string|null} [stepData.observations] optional notes/deviations (max 2000)
 * @returns {Promise<Object>} BatchExecutionStepResponseDto
 */
export async function completeExecutionStep(batchId, stepId, stepData) {
  if (batchId === undefined || batchId === null || String(batchId).trim() === '') {
    throw new Error('Batch ID is required');
  }
  if (stepId === undefined || stepId === null || String(stepId).trim() === '') {
    throw new Error('Execution step ID is required');
  }

  return apiRequest(
    `/batches/${encodeURIComponent(batchId)}/steps/${encodeURIComponent(stepId)}/complete`,
    {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(stepData || {}),
    }
  );
}
