import test from 'node:test';
import assert from 'node:assert/strict';
import {
  API_BASE_URL,
  apiRequest,
  buildQueryString,
  clearAuthToken,
  getAuthToken,
  resolveApiUrl,
  setAuthToken,
  setForbiddenHandler,
  setUnauthorizedHandler,
} from './apiClient.js';
import { loginUser, fetchCurrentUser, checkSystemHealth } from './authApi.js';
import { getAllProducts } from './productApi.js';
import { getAllRecipes } from './recipeApi.js';
import { getAllOrders } from './orderApi.js';
import { getExecutionQueue } from './productionExecutionApi.js';
import { getQualityQueue } from './qualityApi.js';
import { getDeviations } from './deviationApi.js';

function setupStorageStub() {
  const store = new Map();
  const prevWindow = globalThis.window;
  globalThis.window = {
    sessionStorage: {
      getItem: (k) => (store.has(k) ? store.get(k) : null),
      setItem: (k, v) => store.set(k, String(v)),
      removeItem: (k) => store.delete(k),
    },
  };
  return {
    store,
    restore() {
      if (prevWindow === undefined) {
        delete globalThis.window;
      } else {
        globalThis.window = prevWindow;
      }
    },
  };
}

function jsonResponse(body, status = 200, url = 'http://localhost:8080/api/test') {
  return {
    ok: status >= 200 && status < 300,
    status,
    url,
    headers: {
      get: (name) => (name.toLowerCase() === 'content-type' ? 'application/json' : null),
    },
    json: async () => body,
  };
}

test('API base URL configuration and query/URL builders behave predictably', () => {
  assert.equal(API_BASE_URL, 'http://localhost:8080/api');
  assert.equal(resolveApiUrl('/auth/me'), 'http://localhost:8080/api/auth/me');
  assert.equal(resolveApiUrl('products'), 'http://localhost:8080/api/products');
  assert.equal(resolveApiUrl('http://example.test/x'), 'http://example.test/x');
  assert.equal(
    buildQueryString({ status: 'OPEN', filter: 'ALL', empty: '', count: 3 }),
    'status=OPEN&count=3'
  );
});

test('setAuthToken persists in memory and sessionStorage and clearAuthToken wipes both', () => {
  const storage = setupStorageStub();
  clearAuthToken();
  try {
    assert.equal(getAuthToken(), null);
    setAuthToken('  jwt.token.abc  ');
    assert.equal(getAuthToken(), 'jwt.token.abc');
    assert.equal(storage.store.get('pharmatrack_access_token'), 'jwt.token.abc');

    clearAuthToken();
    assert.equal(getAuthToken(), null);
    assert.equal(storage.store.has('pharmatrack_access_token'), false);
  } finally {
    clearAuthToken();
    storage.restore();
  }
});

test('apiRequest attaches Bearer JWT for all protected services and omits it on skipAuth endpoints', async () => {
  const storage = setupStorageStub();
  const prevFetch = globalThis.fetch;
  const recordedCalls = [];

  setAuthToken('signed-jwt-token');
  globalThis.fetch = async (url, options = {}) => {
    recordedCalls.push({ url, options });
    if (url.endsWith('/auth/login')) {
      return jsonResponse({ accessToken: 'signed-jwt-token', user: { id: 1, role: 'ADMIN' } });
    }
    return jsonResponse([]);
  };

  try {
    await loginUser({ username: 'admin', password: 'pw' });
    await checkSystemHealth();
    await fetchCurrentUser();
    await getAllProducts();
    await getAllRecipes();
    await getAllOrders();
    await getExecutionQueue();
    await getQualityQueue();
    await getDeviations();

    assert.equal(recordedCalls[0].url, 'http://localhost:8080/api/auth/login');
    assert.equal(recordedCalls[0].options.headers?.Authorization, undefined);

    assert.equal(recordedCalls[1].url, 'http://localhost:8080/api/health');
    assert.equal(recordedCalls[1].options.headers?.Authorization, undefined);

    for (let i = 2; i < recordedCalls.length; i += 1) {
      assert.equal(
        recordedCalls[i].options.headers?.Authorization,
        'Bearer signed-jwt-token',
        `Expected Bearer token on ${recordedCalls[i].url}`
      );
      assert.equal(recordedCalls[i].options.headers?.['X-Actor-Name'], undefined);
    }
  } finally {
    clearAuthToken();
    globalThis.fetch = prevFetch;
    storage.restore();
  }
});

test('401 on protected endpoint clears token and fires unauthorizedHandler, while 401 on login does not', async () => {
  const storage = setupStorageStub();
  const prevFetch = globalThis.fetch;
  let unauthorizedCount = 0;

  setAuthToken('existing-token');
  setUnauthorizedHandler(() => {
    unauthorizedCount += 1;
  });

  globalThis.fetch = async (url) => {
    if (url.endsWith('/auth/login')) {
      return jsonResponse({ message: 'Invalid username or password.' }, 401, url);
    }
    return jsonResponse({ message: 'Token expired' }, 401, url);
  };

  try {
    await assert.rejects(
      () => loginUser({ username: 'admin', password: 'wrong' }),
      (err) => err.status === 401 && err.isUnauthorized === true
    );
    assert.equal(unauthorizedCount, 0);
    assert.equal(getAuthToken(), 'existing-token');

    await assert.rejects(
      () => apiRequest('/products'),
      (err) => err.status === 401 && err.isUnauthorized === true && err.message === 'Token expired'
    );
    assert.equal(unauthorizedCount, 1);
    assert.equal(getAuthToken(), null);
  } finally {
    setUnauthorizedHandler(null);
    clearAuthToken();
    globalThis.fetch = prevFetch;
    storage.restore();
  }
});

test('403 Forbidden preserves token and invokes forbiddenHandler, and network errors wrap cleanly', async () => {
  const storage = setupStorageStub();
  const prevFetch = globalThis.fetch;
  let forbiddenPayload = null;

  setAuthToken('operator-token');
  setForbiddenHandler((info) => {
    forbiddenPayload = info;
  });

  try {
    globalThis.fetch = async (url) =>
      jsonResponse({ message: 'Access Denied by backend' }, 403, url);

    await assert.rejects(
      () => apiRequest('/recipes'),
      (err) => err.status === 403 && err.isForbidden === true
    );
    assert.equal(getAuthToken(), 'operator-token');
    assert.equal(forbiddenPayload?.status, 403);
    assert.equal(forbiddenPayload?.message, 'Access Denied by backend');

    globalThis.fetch = async () => {
      throw new TypeError('Failed to fetch');
    };
    await assert.rejects(
      () => apiRequest('/products'),
      (err) => err.status === 0 && err.isNetworkError === true
    );
  } finally {
    setForbiddenHandler(null);
    clearAuthToken();
    globalThis.fetch = prevFetch;
    storage.restore();
  }
});

