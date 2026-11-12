import { apiRequest } from './apiClient.js';

/**
 * Authenticate with username and password.
 * POST /api/auth/login (public endpoint; skips auth header & session-expired 401 redirect)
 *
 * @param {Object} credentials
 * @param {string} credentials.username
 * @param {string} credentials.password
 * @returns {Promise<Object>} LoginResponseDto { accessToken, tokenType, expiresInSeconds, user }
 */
export async function loginUser({ username, password }) {
  return apiRequest('/auth/login', {
    method: 'POST',
    skipAuth: true,
    suppressForbiddenHandler: true,
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      username: typeof username === 'string' ? username.trim() : '',
      password: typeof password === 'string' ? password : '',
    }),
  });
}

/**
 * Validate the current bearer token and retrieve fresh UserSummaryDto from the backend.
 * GET /api/auth/me
 *
 * @returns {Promise<Object>} UserSummaryDto
 */
export async function fetchCurrentUser() {
  return apiRequest('/auth/me', {
    cache: 'no-store',
    suppressForbiddenHandler: true,
  });
}

/**
 * Poll backend and MySQL database connectivity status.
 * GET /api/health (public endpoint)
 *
 * @returns {Promise<Object>} HealthResponseDto { status, message }
 */
export async function checkSystemHealth() {
  return apiRequest('/health', {
    skipAuth: true,
    suppressForbiddenHandler: true,
    cache: 'no-store',
  });
}

