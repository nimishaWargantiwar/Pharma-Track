import { apiRequest } from './apiClient.js';

/**
 * Fetch all products from the backend.
 */
export async function getAllProducts() {
  return apiRequest('/products');
}

/**
 * Fetch a single product by ID.
 */
export async function getProductById(id) {
  return apiRequest(`/products/${encodeURIComponent(id)}`);
}

/**
 * Create a new product.
 */
export async function createProduct(productData) {
  return apiRequest('/products', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(productData),
  });
}

/**
 * Update an existing product.
 */
export async function updateProduct(id, productData) {
  return apiRequest(`/products/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(productData),
  });
}

/**
 * Update product lifecycle status (ACTIVE or INACTIVE).
 */
export async function updateProductStatus(id, status) {
  return apiRequest(`/products/${encodeURIComponent(id)}/status`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ status }),
  });
}
