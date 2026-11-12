import { API_BASE_URL, apiRequest, handleResponse } from './apiClient.js';

export { API_BASE_URL, handleResponse };
export const BATCHES_BASE_URL = `${API_BASE_URL}/batches`;

// -----------------------------------------------------------------------------
// Manufacturing Orders API
// -----------------------------------------------------------------------------

/**
 * Create a new manufacturing order and automatically generate planned batches.
 * POST /api/orders
 *
 * @param {Object} orderData creation payload
 * @returns {Promise<Object>} OrderResponseDto
 */
export async function createOrder(orderData) {
  return apiRequest('/orders', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(orderData),
  });
}

/**
 * Retrieve all manufacturing orders, optionally filtered by lifecycle status.
 * GET /api/orders
 * GET /api/orders?status={status}
 *
 * @param {string} [status] optional OrderStatus filter ('PLANNED', 'RELEASED', etc.)
 * @returns {Promise<Array>} List<OrderResponseDto>
 */
export async function getAllOrders(status) {
  const path = status
    ? `/orders?status=${encodeURIComponent(status)}`
    : '/orders';
  return apiRequest(path);
}

/**
 * Retrieve a single manufacturing order by primary key ID.
 * GET /api/orders/{id}
 *
 * @param {number|string} id order ID
 * @returns {Promise<Object>} OrderResponseDto
 */
export async function getOrderById(id) {
  return apiRequest(`/orders/${encodeURIComponent(id)}`);
}

/**
 * Retrieve a single manufacturing order by unique order number (e.g., MO-2026-0001).
 * GET /api/orders/number/{orderNumber}
 *
 * @param {string} orderNumber unique order number
 * @returns {Promise<Object>} OrderResponseDto
 */
export async function getOrderByOrderNumber(orderNumber) {
  return apiRequest(`/orders/number/${encodeURIComponent(orderNumber)}`);
}

/**
 * Retrieve all manufacturing orders associated with a product ID.
 * GET /api/orders/product/{productId}
 *
 * @param {number|string} productId product ID
 * @returns {Promise<Array>} List<OrderResponseDto>
 */
export async function getOrdersByProductId(productId) {
  return apiRequest(`/orders/product/${encodeURIComponent(productId)}`);
}

/**
 * Update the lifecycle status of a manufacturing order.
 * PATCH /api/orders/{id}/status
 *
 * @param {number|string} id order ID
 * @param {string} status target OrderStatus ('RELEASED', 'CANCELLED', etc.)
 * @returns {Promise<Object>} updated OrderResponseDto
 */
export async function updateOrderStatus(id, status) {
  return apiRequest(`/orders/${encodeURIComponent(id)}/status`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ status }),
  });
}

/**
 * Retrieve all child production batches belonging to a specific order.
 * GET /api/orders/{orderId}/batches
 *
 * @param {number|string} orderId order ID
 * @returns {Promise<Array>} List<BatchResponseDto>
 */
export async function getBatchesByOrderId(orderId) {
  return apiRequest(`/orders/${encodeURIComponent(orderId)}/batches`);
}

// -----------------------------------------------------------------------------
// Batches API
// -----------------------------------------------------------------------------

/**
 * Retrieve a single production batch by primary key ID.
 * GET /api/batches/{id}
 *
 * @param {number|string} id batch ID
 * @returns {Promise<Object>} BatchResponseDto
 */
export async function getBatchById(id) {
  return apiRequest(`/batches/${encodeURIComponent(id)}`);
}

/**
 * Retrieve a single production batch by unique batch number (e.g., BAT-2026-0001-01).
 * GET /api/batches/number/{batchNumber}
 *
 * @param {string} batchNumber unique batch number
 * @returns {Promise<Object>} BatchResponseDto
 */
export async function getBatchByBatchNumber(batchNumber) {
  return apiRequest(`/batches/number/${encodeURIComponent(batchNumber)}`);
}

/**
 * Update the execution status of a production batch.
 * When completing a batch, actualYield is required.
 * PATCH /api/batches/{id}/status
 *
 * @param {number|string} id batch ID
 * @param {string} status target BatchStatus ('IN_PROGRESS', 'COMPLETED', 'CANCELLED')
 * @param {number|string} [actualYield] recorded physical output quantity
 * @returns {Promise<Object>} updated BatchResponseDto
 */
export async function updateBatchStatus(id, status, actualYield) {
  const payload = { status };
  if (actualYield !== undefined && actualYield !== null && actualYield !== '') {
    payload.actualYield = actualYield;
  }

  return apiRequest(`/batches/${encodeURIComponent(id)}/status`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });
}
