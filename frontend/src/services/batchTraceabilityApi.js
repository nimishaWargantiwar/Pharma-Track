import { apiRequest } from './apiClient.js';

/**
 * API client methods for QR Code Batch Traceability and Dossier retrieval.
 */

/**
 * Fetch QR code metadata and base64 image for a batch by numeric ID.
 * @param {number|string} batchId
 * @returns {Promise<Object>} BatchQrCodeDto
 */
export async function fetchBatchQrById(batchId) {
  if (!batchId) {
    throw new Error('Batch ID is required');
  }
  return apiRequest(`/batches/${encodeURIComponent(batchId)}/qr`);
}

/**
 * Fetch QR code metadata for a batch by its QR code or batch number.
 * @param {string} identifier
 * @returns {Promise<Object>} BatchQrCodeDto
 */
export async function fetchBatchQrByIdentifier(identifier) {
  if (!identifier || !String(identifier).trim()) {
    throw new Error('Batch identifier is required');
  }
  const cleanId = encodeURIComponent(String(identifier).trim());
  return apiRequest(`/batches/qr/${cleanId}`);
}

/**
 * Fetch the complete read-only Batch Traceability Dossier by numeric batch ID.
 * @param {number|string} batchId
 * @returns {Promise<Object>} BatchTraceabilityDossierDto
 */
export async function fetchBatchTraceabilityById(batchId) {
  if (!batchId) {
    throw new Error('Batch ID is required');
  }
  return apiRequest(`/batches/${encodeURIComponent(batchId)}/traceability`);
}

/**
 * Fetch the complete read-only Batch Traceability Dossier by batch number or QR code.
 * @param {string} identifier
 * @returns {Promise<Object>} BatchTraceabilityDossierDto
 */
export async function fetchBatchTraceabilityByIdentifier(identifier) {
  if (!identifier || !String(identifier).trim()) {
    throw new Error('Batch identifier is required');
  }
  const cleanId = encodeURIComponent(String(identifier).trim());
  return apiRequest(`/batches/traceability/${cleanId}`);
}
