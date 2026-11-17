/**
 * Centralized HTTP API client and JWT token management for PharmaTrack MES & eBR.
 *
 * Token Storage Strategy & Security Trade-Offs:
 * - Access tokens are stored in memory backed by `window.sessionStorage` (`pharmatrack_access_token`).
 * - Trade-off vs. `localStorage`: `sessionStorage` scopes the JWT to the active browser tab and clears
 *   automatically when the tab/window closes, reducing persistent exposure on shared workstations.
 * - Limitation: Both `sessionStorage` and `localStorage` remain readable by JavaScript in the origin
 *   context and are therefore exposed if an XSS vulnerability occurs. Strict React JSX escaping,
 *   absence of raw HTML injection, and Content Security Policy (CSP) discipline are mandatory.
 * - Production hardening path: Transition token transport to `HttpOnly`, `Secure`, `SameSite=Lax|Strict`
 *   cookies issued by the backend with CSRF protection and short-lived access + refresh token rotation
 *   (the current backend is stateless Bearer-header based with `allowCredentials(false)`).
 */

/* global __PHARMATRACK_PROD_API_URL__ */
import { normalizeApiBaseUrl } from '../utils/apiUrlConfig.js';

const TOKEN_STORAGE_KEY = 'pharmatrack_access_token';

function resolveConfiguredApiBaseUrl() {
  if (typeof __PHARMATRACK_PROD_API_URL__ === 'string') {
    return __PHARMATRACK_PROD_API_URL__;
  }

  try {
    if (
      typeof import.meta !== 'undefined' &&
      import.meta.env &&
      typeof import.meta.env.VITE_API_BASE_URL === 'string'
    ) {
      const configured = normalizeApiBaseUrl(import.meta.env.VITE_API_BASE_URL);
      if (configured !== '') {
        return configured;
      }
    }
  } catch (e) {
    // Ignore when executed outside Vite (e.g. plain Node ESM tests)
  }

  return 'http://localhost:8080/api';
}

export const API_BASE_URL = resolveConfiguredApiBaseUrl();

let inMemoryToken = null;
let unauthorizedHandler = null;
let forbiddenHandler = null;

let isReauthPending = false;
let queuedRequests = [];

function readTokenFromSessionStorage() {
  if (typeof window === 'undefined') {
    return null;
  }
  try {
    const stored = window.sessionStorage?.getItem(TOKEN_STORAGE_KEY);
    return stored && stored.trim() !== '' ? stored : null;
  } catch (e) {
    return null;
  }
}

function writeTokenToSessionStorage(token) {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    if (!window.sessionStorage) {
      return;
    }
    if (token) {
      window.sessionStorage.setItem(TOKEN_STORAGE_KEY, token);
    } else {
      window.sessionStorage.removeItem(TOKEN_STORAGE_KEY);
    }
  } catch (e) {
    // Ignore storage quota / privacy-mode errors cleanly while keeping in-memory token active
  }
}

/**
 * Retrieve the currently active JWT bearer token from memory or sessionStorage.
 */
export function getAuthToken() {
  if (typeof window !== 'undefined' && window.sessionStorage) {
    const stored = readTokenFromSessionStorage();
    inMemoryToken = stored;
    return stored;
  }
  return inMemoryToken;
}

/**
 * Persist the JWT bearer token in memory and sessionStorage, or clear when falsy.
 */
export function setAuthToken(token) {
  const normalized = typeof token === 'string' && token.trim() !== '' ? token.trim() : null;
  inMemoryToken = normalized;
  writeTokenToSessionStorage(normalized);
  return normalized;
}

/**
 * Clear the JWT bearer token from memory and sessionStorage and reject any queued retry requests.
 */
export function clearAuthToken() {
  inMemoryToken = null;
  writeTokenToSessionStorage(null);
  if (isReauthPending || queuedRequests.length > 0) {
    notifyReauthFailure(new Error('User signed out or session cleared.'));
  }
}

/**
 * Check whether a session re-authentication cycle is currently in progress.
 */
export function isReauthInProgress() {
  return Boolean(isReauthPending);
}

/**
 * Notify the API client of successful re-authentication.
 * Updates the active token and replays queued GET requests at most once.
 */
export function notifyReauthSuccess(newToken) {
  isReauthPending = false;
  if (newToken) {
    setAuthToken(newToken);
  }
  const pending = queuedRequests.slice();
  queuedRequests = [];
  pending.forEach(({ path, options, resolve, reject }) => {
    apiRequest(path, { ...options, isRetry: true })
      .then(resolve)
      .catch(reject);
  });
}

/**
 * Notify the API client of failed or cancelled re-authentication.
 * Rejects all queued requests and clears the queue.
 */
export function notifyReauthFailure(error) {
  isReauthPending = false;
  const failureErr = error || new Error('Session re-authentication failed.');
  if (typeof failureErr.status === 'undefined') {
    failureErr.status = 401;
    failureErr.isUnauthorized = true;
  }
  const pending = queuedRequests.slice();
  queuedRequests = [];
  pending.forEach(({ reject }) => {
    try {
      reject(failureErr);
    } catch (e) {
      // Ignore uncaught rejection in listener
    }
  });
}

/**
 * Reset re-authentication state and reject any pending requests.
 */
export function resetReauthState() {
  notifyReauthFailure(new Error('Session reset.'));
}

/**
 * Register a listener invoked when a protected endpoint responds with HTTP 401 Unauthorized.
 */
export function setUnauthorizedHandler(handler) {
  unauthorizedHandler = typeof handler === 'function' ? handler : null;
}

/**
 * Register a listener invoked when an endpoint responds with HTTP 403 Forbidden.
 */
export function setForbiddenHandler(handler) {
  forbiddenHandler = typeof handler === 'function' ? handler : null;
}

/**
 * Build a full URL against API_BASE_URL unless already absolute.
 */
export function resolveApiUrl(path) {
  if (!path) {
    return API_BASE_URL;
  }
  if (path.startsWith('http://') || path.startsWith('https://')) {
    return path;
  }
  return path.startsWith('/') ? `${API_BASE_URL}${path}` : `${API_BASE_URL}/${path}`;
}

/**
 * Build an encoded query string ignoring null/undefined/empty values and optionally 'ALL'.
 */
export function buildQueryString(params = {}, { ignoreAllSentinel = true } = {}) {
  const searchParams = new URLSearchParams();
  Object.entries(params || {}).forEach(([key, rawValue]) => {
    if (rawValue === undefined || rawValue === null) {
      return;
    }
    const strValue = String(rawValue).trim();
    if (strValue === '') {
      return;
    }
    if (ignoreAllSentinel && strValue === 'ALL') {
      return;
    }
    searchParams.set(key, strValue);
  });
  return searchParams.toString();
}

/**
 * Parse API responses, normalize validation/business/auth errors, and trigger 401/403 hooks.
 */
export async function handleResponse(
  response,
  {
    skipAuth = false,
    suppressForbiddenHandler = false,
    skipReauthQueue = false,
    path = null,
    method = 'GET',
    isRetry = false,
    requestOptions = {},
  } = {}
) {
  if (!response.ok) {
    let errorData = null;
    try {
      errorData = await response.json();
    } catch (e) {
      // Response body may be empty or non-JSON
    }

    const fallbackMessage =
      response.status === 401
        ? 'Your session has expired or authentication failed. Please sign in again.'
        : response.status === 403
          ? 'You do not have permission to perform this action.'
          : `Request failed with status ${response.status}`;

    const errorMessage = errorData?.message || fallbackMessage;
    const errorCode = errorData?.errorCode;
    const error = new Error(errorMessage);
    error.status = response.status;
    error.validationErrors = errorData?.validationErrors || null;
    error.data = errorData;
    error.errorCode = errorCode;

    if (response.status === 401) {
      error.isUnauthorized = true;
      if (!skipAuth) {
        const normalizedMethod = (method || 'GET').toUpperCase();
        const isGet = normalizedMethod === 'GET';
        const isAuthMe = typeof path === 'string' && (path === '/auth/me' || path.endsWith('/auth/me'));

        // Clear invalid token from memory and sessionStorage
        inMemoryToken = null;
        writeTokenToSessionStorage(null);

        // Only GET requests (excluding identity verification /auth/me) may be queued for retry at most once
        if (isGet && !isRetry && path && !skipReauthQueue && !isAuthMe) {
          const retryPromise = new Promise((resolve, reject) => {
            queuedRequests.push({
              path,
              options: { ...requestOptions, method: 'GET', isRetry: true },
              resolve,
              reject,
            });
          });

          // Trigger unauthorized handler once per re-auth cycle AFTER queuing this request
          if (!isReauthPending) {
            isReauthPending = true;
            if (unauthorizedHandler) {
              try {
                unauthorizedHandler({
                  status: 401,
                  message: errorMessage,
                  errorCode,
                  url: response.url,
                });
              } catch (e) {
                // Ignore handler error
              }
            }
          }

          return retryPromise;
        }

        // Trigger unauthorized handler once if mutation or non-retryable
        if (!isReauthPending) {
          isReauthPending = true;
          if (unauthorizedHandler) {
            try {
              unauthorizedHandler({
                status: 401,
                message: errorMessage,
                errorCode,
                url: response.url,
              });
            } catch (e) {
              // Ignore handler error
            }
          }
        }

        // Mutations, identity verification, or repeated failures reject immediately
        const authErr = new Error(
          isGet
            ? (errorData?.message || 'Session verification failed.')
            : 'Session expired during action. Please re-authenticate and retry.'
        );
        authErr.status = 401;
        authErr.isUnauthorized = true;
        authErr.isMutation = !isGet;
        authErr.sessionExpired = true;
        authErr.errorCode = errorCode;
        authErr.data = errorData;
        throw authErr;
      }
    } else if (response.status === 403) {
      error.isForbidden = true;
      if (!suppressForbiddenHandler && forbiddenHandler) {
        forbiddenHandler({
          status: 403,
          message: errorMessage,
          url: response.url,
        });
      }
    }

    throw error;
  }

  if (response.status === 204) {
    return null;
  }

  const contentType = response.headers?.get ? response.headers.get('content-type') : null;
  if (contentType && contentType.includes('application/json')) {
    return await response.json();
  }
  return null;
}

/**
 * Execute an HTTP request against the PharmaTrack backend with automatic JWT header injection
 * and centralized 401 / 403 / validation / network error handling.
 */
export async function apiRequest(path, options = {}) {
  const {
    method = 'GET',
    headers = {},
    body,
    cache,
    skipAuth = false,
    suppressForbiddenHandler = false,
    skipReauthQueue = false,
    isRetry = false,
  } = options;

  const normalizedMethod = (method || 'GET').toUpperCase();
  const isGet = normalizedMethod === 'GET';
  const isAuthMe = typeof path === 'string' && (path === '/auth/me' || path.endsWith('/auth/me'));

  // If a re-authentication cycle is currently in progress:
  if (!skipAuth && isReauthPending && !skipReauthQueue && !isAuthMe) {
    if (isGet && !isRetry) {
      // Queue GET requests to replay once re-authentication succeeds
      return new Promise((resolve, reject) => {
        queuedRequests.push({
          path,
          options: { ...options, isRetry: true },
          resolve,
          reject,
        });
      });
    }

    // Mutations reject immediately to prevent duplicate or ambiguous execution
    const pendingErr = new Error(
      isGet
        ? 'Session verification in progress.'
        : 'Session re-authentication in progress. Please complete re-authentication before retrying.'
    );
    pendingErr.status = 401;
    pendingErr.isUnauthorized = true;
    pendingErr.isMutation = !isGet;
    pendingErr.sessionExpired = true;
    throw pendingErr;
  }

  const url = resolveApiUrl(path);
  const requestHeaders = { ...headers };

  if (!skipAuth) {
    const token = getAuthToken();
    if (token) {
      requestHeaders.Authorization = `Bearer ${token}`;
    }
  }

  let serializedBody = body;
  if (
    body !== undefined &&
    body !== null &&
    typeof body !== 'string' &&
    !(typeof FormData !== 'undefined' && body instanceof FormData)
  ) {
    serializedBody = JSON.stringify(body);
    if (!requestHeaders['Content-Type']) {
      requestHeaders['Content-Type'] = 'application/json';
    }
  }

  const fetchOptions = {};
  if (method) {
    fetchOptions.method = method;
  }
  if (Object.keys(requestHeaders).length > 0) {
    fetchOptions.headers = requestHeaders;
  }
  if (serializedBody !== undefined) {
    fetchOptions.body = serializedBody;
  }
  if (cache) {
    fetchOptions.cache = cache;
  }

  let response;
  try {
    response = await fetch(url, fetchOptions);
  } catch (networkErr) {
    const wrapped = new Error(
      'Unable to reach PharmaTrack backend server. Please verify the API service is running.'
    );
    wrapped.status = 0;
    wrapped.isNetworkError = true;
    wrapped.cause = networkErr;
    throw wrapped;
  }

  return handleResponse(response, {
    skipAuth,
    suppressForbiddenHandler,
    skipReauthQueue,
    path,
    method: normalizedMethod,
    isRetry,
    requestOptions: options,
  });
}

