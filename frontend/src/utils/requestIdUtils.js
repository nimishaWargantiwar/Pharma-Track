/**
 * Utility for generating safe, unique client request IDs for idempotency tracking.
 */

/**
 * Generate a unique, safe client request ID for idempotency tracking (max 64 chars).
 * Format: req-<timestamp-base36>-<random-base36> (~20 chars).
 *
 * @returns {string} Unique client request identifier
 */
export function generateClientRequestId() {
  const ts = Date.now().toString(36);
  const rnd = Math.random().toString(36).substring(2, 10);
  return `req-${ts}-${rnd}`;
}
