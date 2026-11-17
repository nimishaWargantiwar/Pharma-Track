import test from 'node:test';
import assert from 'node:assert/strict';
import {
  API_BASE_URL,
  apiRequest,
  buildQueryString,
  clearAuthToken,
  getAuthToken,
  isReauthInProgress,
  notifyReauthFailure,
  notifyReauthSuccess,
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
  setUnauthorizedHandler((info) => {
    unauthorizedCount += 1;
    notifyReauthFailure(new Error(info?.message || 'Token expired'));
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

test('Session Re-Authentication: Concurrency lock ensures single unauthorizedHandler call across concurrent 401s', async () => {
  const storage = setupStorageStub();
  const prevFetch = globalThis.fetch;
  let handlerCallCount = 0;
  let lastInfo = null;

  setAuthToken('expired-sample-token');
  setUnauthorizedHandler((info) => {
    handlerCallCount += 1;
    lastInfo = info;
  });

  globalThis.fetch = async (url) => {
    return jsonResponse(
      { message: 'Authentication token has expired', errorCode: 'TOKEN_EXPIRED' },
      401,
      url
    );
  };

  try {
    // Fire 3 concurrent GET requests
    const p1 = apiRequest('/products');
    const p2 = apiRequest('/recipes');
    const p3 = apiRequest('/orders');

    // Wait a tick for fetch calls to return 401 and queue
    await new Promise((r) => setTimeout(r, 10));

    // Concurrency lock: handler called exactly once
    assert.equal(handlerCallCount, 1);
    assert.equal(lastInfo?.errorCode, 'TOKEN_EXPIRED');
    assert.equal(isReauthInProgress(), true);

    // Resolving reauth failure releases all 3 with 401 rejection
    notifyReauthFailure(new Error('Session re-authentication cancelled'));

    await assert.rejects(() => p1, /Session re-authentication cancelled/);
    await assert.rejects(() => p2, /Session re-authentication cancelled/);
    await assert.rejects(() => p3, /Session re-authentication cancelled/);
    assert.equal(isReauthInProgress(), false);
  } finally {
    setUnauthorizedHandler(null);
    clearAuthToken();
    globalThis.fetch = prevFetch;
    storage.restore();
  }
});

test('Session Re-Authentication: GET requests are queued and retried at most once on notifyReauthSuccess', async () => {
  const storage = setupStorageStub();
  const prevFetch = globalThis.fetch;
  let authAttempts = 0;

  setAuthToken('initial-token');
  setUnauthorizedHandler(() => {
    // Simulates re-auth dialog opening
  });

  globalThis.fetch = async (url, options) => {
    const authHeader = options?.headers?.Authorization;
    if (authHeader === 'Bearer initial-token') {
      authAttempts += 1;
      return jsonResponse(
        { message: 'Authentication token has expired', errorCode: 'TOKEN_EXPIRED' },
        401,
        url
      );
    }
    if (authHeader === 'Bearer refreshed-token') {
      return jsonResponse([{ id: 1, name: 'Paracetamol' }], 200, url);
    }
    return jsonResponse({ message: 'Unauthorized' }, 401, url);
  };

  try {
    const requestPromise = apiRequest('/products');
    // Wait a tick for initial 401 fetch to complete and enqueue
    await new Promise((r) => setTimeout(r, 10));
    assert.equal(isReauthInProgress(), true);

    // Notify success with new token
    notifyReauthSuccess('refreshed-token');

    const result = await requestPromise;
    assert.deepEqual(result, [{ id: 1, name: 'Paracetamol' }]);
    assert.equal(getAuthToken(), 'refreshed-token');
    assert.equal(isReauthInProgress(), false);
  } finally {
    setUnauthorizedHandler(null);
    clearAuthToken();
    globalThis.fetch = prevFetch;
    storage.restore();
  }
});

test('Session Re-Authentication: Non-GET mutations immediately reject and are NEVER queued for auto-replay', async () => {
  const storage = setupStorageStub();
  const prevFetch = globalThis.fetch;
  let handlerCalled = false;

  setAuthToken('sample-token');
  setUnauthorizedHandler(() => {
    handlerCalled = true;
  });

  globalThis.fetch = async (url) => {
    return jsonResponse(
      { message: 'Authentication token has expired', errorCode: 'TOKEN_EXPIRED' },
      401,
      url
    );
  };

  try {
    // POST request fails with 401
    await assert.rejects(
      () =>
        apiRequest('/orders', {
          method: 'POST',
          body: { orderNumber: 'MO-2026-9999' },
        }),
      (err) => {
        assert.equal(err.status, 401);
        assert.equal(err.isUnauthorized, true);
        assert.equal(err.isMutation, true);
        assert.equal(err.sessionExpired, true);
        assert.match(err.message, /Session expired during action/);
        return true;
      }
    );

    assert.equal(handlerCalled, true);
    assert.equal(isReauthInProgress(), true);

    // While re-auth is in progress, any subsequent mutation is immediately blocked without network fetch
    await assert.rejects(
      () =>
        apiRequest('/orders/1', {
          method: 'DELETE',
        }),
      (err) => {
        assert.equal(err.status, 401);
        assert.equal(err.isMutation, true);
        assert.equal(err.sessionExpired, true);
        assert.match(err.message, /Session re-authentication in progress/);
        return true;
      }
    );
  } finally {
    setUnauthorizedHandler(null);
    clearAuthToken();
    globalThis.fetch = prevFetch;
    storage.restore();
  }
});

