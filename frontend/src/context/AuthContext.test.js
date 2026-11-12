import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { createDomEnvironment } from '../../scripts/dom-harness.mjs';

function jsonResponse(body, status = 200, url = 'http://localhost:8080/api/auth') {
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

function SessionProbe() {
  const {
    user,
    role,
    isAuthenticated,
    isInitializing,
    sessionMessage,
    forbiddenBanner,
    logout,
    dismissForbiddenBanner,
  } = React.useContext(SessionProbe.AuthContext);

  if (isInitializing) {
    return React.createElement('div', { 'data-testid': 'booting' }, 'Verifying...');
  }
  if (!isAuthenticated) {
    return React.createElement(SessionProbe.LoginPage, { backendStatus: 'connected' });
  }
  return React.createElement(
    'div',
    null,
    React.createElement('span', { 'data-testid': 'auth-user' }, `${user.username}:${role}`),
    sessionMessage && React.createElement('span', { 'data-testid': 'session-msg' }, sessionMessage),
    forbiddenBanner && React.createElement('span', { 'data-testid': 'forbidden-banner' }, forbiddenBanner),
    React.createElement(
      'button',
      { type: 'button', 'data-testid': 'logout-btn', onClick: () => logout() },
      'Sign Out'
    ),
    React.createElement(
      'button',
      { type: 'button', 'data-testid': 'dismiss-403-btn', onClick: () => dismissForbiddenBanner() },
      'Dismiss'
    )
  );
}

test('AuthProvider renders LoginPage unauthenticated on empty boot without calling /auth/me', async () => {
  const dom = await createDomEnvironment();
  const { clearAuthToken } = await import('../services/apiClient.js');
  const { AuthProvider, AuthContext } = await import('./AuthContext.jsx');
  const { default: LoginPage } = await import('../components/LoginPage.jsx');
  SessionProbe.AuthContext = AuthContext;
  SessionProbe.LoginPage = LoginPage;

  clearAuthToken();
  const calls = [];
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url, options });
    return jsonResponse({}, 200, url);
  };

  try {
    await dom.render(
      React.createElement(AuthProvider, null, React.createElement(SessionProbe))
    );
    await dom.flush();

    assert.equal(calls.length, 0);
    assert.ok(dom.container.textContent.includes('PharmaTrack MES & eBR'));
    assert.ok(dom.container.querySelector('#login-username'));
    assert.ok(dom.container.querySelector('#login-password'));
    // Must not prefill any demo password or hint
    assert.equal(dom.container.querySelector('#login-password').value, '');
    assert.equal(dom.container.textContent.toLowerCase().includes('demo'), false);
  } finally {
    await dom.unmount();
    clearAuthToken();
    globalThis.fetch = prevFetch;
  }
});

test('AuthProvider validates restored token with GET /api/auth/me and clears expired session on 401', async () => {
  const dom = await createDomEnvironment();
  const { clearAuthToken, getAuthToken } = await import('../services/apiClient.js');
  const { AuthProvider, AuthContext } = await import('./AuthContext.jsx');
  const { default: LoginPage } = await import('../components/LoginPage.jsx');
  SessionProbe.AuthContext = AuthContext;
  SessionProbe.LoginPage = LoginPage;

  const prevFetch = globalThis.fetch;

  // Scenario A: valid token restored from sessionStorage
  clearAuthToken();
  dom.sessionStorage.setItem('pharmatrack_access_token', 'restored-valid-jwt');
  const validCalls = [];
  globalThis.fetch = async (url, options = {}) => {
    validCalls.push({ url, options });
    return jsonResponse(
      {
        id: 2,
        username: 'qa.reviewer',
        fullName: 'Dr. Asha Sen',
        role: 'QA_REVIEWER',
        enabled: true,
      },
      200,
      url
    );
  };

  try {
    await dom.render(
      React.createElement(AuthProvider, null, React.createElement(SessionProbe))
    );
    await dom.flush();

    assert.equal(validCalls.length, 1);
    assert.equal(validCalls[0].url, 'http://localhost:8080/api/auth/me');
    assert.equal(validCalls[0].options.headers?.Authorization, 'Bearer restored-valid-jwt');
    assert.equal(
      dom.container.querySelector('[data-testid="auth-user"]')?.textContent,
      'qa.reviewer:QA_REVIEWER'
    );
    await dom.unmount();

    // Scenario B: expired token returns 401 -> cleared & LoginPage shown with session banner
    const domExpired = await createDomEnvironment();
    clearAuthToken();
    domExpired.sessionStorage.setItem('pharmatrack_access_token', 'expired-jwt');
    globalThis.fetch = async (url) =>
      jsonResponse({ message: 'JWT signature expired' }, 401, url);

    try {
      await domExpired.render(
        React.createElement(AuthProvider, null, React.createElement(SessionProbe))
      );
      await domExpired.flush();

      assert.equal(getAuthToken(), null);
      assert.equal(domExpired.sessionStorage.getItem('pharmatrack_access_token'), null);
      assert.ok(domExpired.container.querySelector('#login-username'));
      assert.ok(domExpired.container.textContent.includes('JWT signature expired'));
    } finally {
      await domExpired.unmount();
    }
  } finally {
    clearAuthToken();
    globalThis.fetch = prevFetch;
  }
});

test('LoginPage validates inputs, toggles password visibility, authenticates, handles 401 & 403, and logs out', async () => {
  const dom = await createDomEnvironment();
  const { clearAuthToken, apiRequest, getAuthToken } = await import('../services/apiClient.js');
  const { AuthProvider, AuthContext } = await import('./AuthContext.jsx');
  const { default: LoginPage } = await import('../components/LoginPage.jsx');
  SessionProbe.AuthContext = AuthContext;
  SessionProbe.LoginPage = LoginPage;

  clearAuthToken();
  const prevFetch = globalThis.fetch;
  let shouldRejectLogin = true;

  globalThis.fetch = async (url, options = {}) => {
    if (url.endsWith('/auth/login')) {
      const body = JSON.parse(options.body);
      if (shouldRejectLogin || body.password !== 'valid-secret') {
        return jsonResponse({ message: 'Invalid username or password.' }, 401, url);
      }
      return jsonResponse(
        {
          accessToken: 'fresh-login-jwt',
          tokenType: 'Bearer',
          expiresInSeconds: 28800,
          user: {
            id: 4,
            username: body.username,
            fullName: 'Floor Operator',
            role: 'OPERATOR',
            enabled: true,
          },
        },
        200,
        url
      );
    }
    if (url.endsWith('/recipes')) {
      return jsonResponse({ message: 'Forbidden for OPERATOR role' }, 403, url);
    }
    return jsonResponse({}, 200, url);
  };

  try {
    await dom.render(
      React.createElement(AuthProvider, null, React.createElement(SessionProbe))
    );
    await dom.flush();

    // 1. Toggle show/hide password
    const pwInput = dom.container.querySelector('#login-password');
    assert.equal(pwInput.getAttribute('type'), 'password');
    await dom.click(dom.container.querySelector('button[aria-label="Show password"]'));
    assert.equal(pwInput.getAttribute('type'), 'text');
    await dom.click(dom.container.querySelector('button[aria-label="Hide password"]'));
    assert.equal(pwInput.getAttribute('type'), 'password');

    // 2. Empty submit shows inline validation errors
    await dom.submit(dom.container.querySelector('form'));
    assert.ok(dom.container.textContent.includes('Username is required.'));
    assert.ok(dom.container.textContent.includes('Password is required.'));

    // 3. Invalid credentials render server alert
    await dom.changeInput(dom.container.querySelector('#login-username'), 'op1');
    await dom.changeInput(pwInput, 'wrong');
    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Invalid username or password.'));
    assert.equal(getAuthToken(), null);

    // 4. Valid credentials authenticate and persist token
    shouldRejectLogin = false;
    await dom.changeInput(pwInput, 'valid-secret');
    await dom.submit(dom.container.querySelector('form'));
    await dom.flush();

    assert.equal(getAuthToken(), 'fresh-login-jwt');
    assert.equal(dom.sessionStorage.getItem('pharmatrack_access_token'), 'fresh-login-jwt');
    assert.equal(
      dom.container.querySelector('[data-testid="auth-user"]')?.textContent,
      'op1:OPERATOR'
    );

    // 5. Protected 403 notifies forbiddenBanner WITHOUT logging user out
    await dom.act(async () => {
      await assert.rejects(() => apiRequest('/recipes'), (err) => err.status === 403);
    });
    await dom.flush();
    assert.equal(
      dom.container.querySelector('[data-testid="forbidden-banner"]')?.textContent,
      'Forbidden for OPERATOR role'
    );
    assert.equal(getAuthToken(), 'fresh-login-jwt');

    await dom.click(dom.container.querySelector('[data-testid="dismiss-403-btn"]'));
    assert.equal(dom.container.querySelector('[data-testid="forbidden-banner"]'), null);

    // 6. Logout clears session and returns to LoginPage
    await dom.click(dom.container.querySelector('[data-testid="logout-btn"]'));
    await dom.flush();
    assert.equal(getAuthToken(), null);
    assert.ok(dom.container.querySelector('#login-username'));
  } finally {
    await dom.unmount();
    clearAuthToken();
    globalThis.fetch = prevFetch;
  }
});

test('AuthProvider clears session on 403 from GET /api/auth/me without leaving forbiddenBanner', async () => {
  const dom = await createDomEnvironment();
  const { clearAuthToken, getAuthToken } = await import('../services/apiClient.js');
  const { AuthProvider, AuthContext } = await import('./AuthContext.jsx');
  const { default: LoginPage } = await import('../components/LoginPage.jsx');
  SessionProbe.AuthContext = AuthContext;
  SessionProbe.LoginPage = LoginPage;

  clearAuthToken();
  dom.sessionStorage.setItem('pharmatrack_access_token', 'forbidden-jwt');
  const prevFetch = globalThis.fetch;
  let meCalls = 0;

  globalThis.fetch = async (url) => {
    if (url.endsWith('/auth/me')) {
      meCalls += 1;
      return jsonResponse({ message: 'Account role revoked or forbidden' }, 403, url);
    }
    return jsonResponse({}, 200, url);
  };

  try {
    let latestContext = null;
    function Inspector() {
      latestContext = React.useContext(AuthContext);
      return React.createElement(SessionProbe);
    }

    await dom.render(
      React.createElement(AuthProvider, null, React.createElement(Inspector))
    );
    await dom.flush();

    assert.equal(meCalls, 1);
    assert.equal(getAuthToken(), null);
    assert.equal(dom.sessionStorage.getItem('pharmatrack_access_token'), null);
    assert.equal(latestContext.forbiddenBanner, null);
    assert.equal(latestContext.sessionRestoreError, null);
    assert.ok(dom.container.querySelector('#login-username'));
    assert.ok(dom.container.textContent.includes('Account role revoked or forbidden'));
  } finally {
    await dom.unmount();
    clearAuthToken();
    globalThis.fetch = prevFetch;
  }
});

test('AuthProvider + App retain JWT on /api/auth/me network outage, avoid retry loops, and allow manual retry to recover', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/products');
  const { clearAuthToken, getAuthToken } = await import('../services/apiClient.js');
  const { AuthProvider } = await import('./AuthContext.jsx');
  const { default: App } = await import('../App.jsx');

  clearAuthToken();
  dom.sessionStorage.setItem('pharmatrack_access_token', 'preserved-network-jwt');
  const prevFetch = globalThis.fetch;
  let meCalls = 0;
  let failNetwork = true;

  globalThis.fetch = async (url) => {
    if (url.endsWith('/auth/me')) {
      meCalls += 1;
      if (failNetwork) {
        throw new TypeError('Failed to fetch');
      }
      return jsonResponse(
        {
          id: 5,
          username: 'qa.reviewer',
          fullName: 'Dr. Asha Sen',
          role: 'QA_REVIEWER',
          enabled: true,
        },
        200,
        url
      );
    }
    if (url.endsWith('/health')) {
      if (failNetwork) {
        throw new TypeError('Failed to fetch');
      }
      return jsonResponse({ status: 'UP', message: 'Connected' }, 200, url);
    }
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(React.createElement(AuthProvider, null, React.createElement(App)));
    await dom.flush();
    await dom.flush();

    // Must preserve token and attempt /api/auth/me strictly once (no retry loop)
    assert.equal(meCalls, 1);
    assert.equal(getAuthToken(), 'preserved-network-jwt');
    assert.equal(dom.sessionStorage.getItem('pharmatrack_access_token'), 'preserved-network-jwt');
    assert.ok(dom.container.textContent.includes('Session Verification Interrupted'));
    assert.ok(dom.container.textContent.includes('Retry Verification'));
    assert.ok(dom.container.textContent.includes('Sign Out'));

    // Bring backend back and click Retry Verification
    failNetwork = false;
    const retryBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Retry Verification')
    );
    assert.ok(retryBtn);
    await dom.click(retryBtn);
    await dom.flush();

    assert.equal(meCalls, 2);
    assert.equal(getAuthToken(), 'preserved-network-jwt');
    assert.equal(dom.container.textContent.includes('Session Verification Interrupted'), false);
    assert.ok(dom.container.textContent.includes('Dr. Asha Sen'));
    assert.ok(dom.container.textContent.includes('@qa.reviewer'));
  } finally {
    await dom.unmount();
    clearAuthToken();
    globalThis.fetch = prevFetch;
  }
});

test('AuthProvider + App retain JWT on 503 outage, clear on 401 retry rejection or manual Sign Out', async () => {
  // Part A: 503 retained -> Retry hits 401 -> clears session to LoginPage
  const dom = await createDomEnvironment();
  const { clearAuthToken, getAuthToken } = await import('../services/apiClient.js');
  const { AuthProvider } = await import('./AuthContext.jsx');
  const { default: App } = await import('../App.jsx');

  clearAuthToken();
  dom.sessionStorage.setItem('pharmatrack_access_token', 'preserved-503-jwt');
  const prevFetch = globalThis.fetch;
  let meStatus = 503;
  let meCalls = 0;

  globalThis.fetch = async (url) => {
    if (url.endsWith('/auth/me')) {
      meCalls += 1;
      return jsonResponse(
        { message: meStatus === 503 ? 'Service temporarily unavailable' : 'Token revoked' },
        meStatus,
        url
      );
    }
    if (url.endsWith('/health')) {
      return jsonResponse({ status: 'DOWN' }, 503, url);
    }
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(React.createElement(AuthProvider, null, React.createElement(App)));
    await dom.flush();
    await dom.flush();

    assert.equal(meCalls, 1);
    assert.equal(getAuthToken(), 'preserved-503-jwt');
    assert.equal(dom.sessionStorage.getItem('pharmatrack_access_token'), 'preserved-503-jwt');
    assert.ok(dom.container.textContent.includes('Session Verification Interrupted'));
    assert.ok(dom.container.textContent.includes('Service temporarily unavailable'));

    // Next attempt gets 401 -> must clear token and render LoginPage with session error
    meStatus = 401;
    const retryBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Retry Verification')
    );
    await dom.click(retryBtn);
    await dom.flush();

    assert.equal(meCalls, 2);
    assert.equal(getAuthToken(), null);
    assert.equal(dom.sessionStorage.getItem('pharmatrack_access_token'), null);
    assert.ok(dom.container.querySelector('#login-username'));
    assert.ok(dom.container.textContent.includes('Token revoked'));
  } finally {
    await dom.unmount();
    clearAuthToken();
    globalThis.fetch = prevFetch;
  }

  // Part B: 503 retained -> user clicks Sign Out -> clears token and navigates to LoginPage
  const domSignOut = await createDomEnvironment();
  clearAuthToken();
  domSignOut.sessionStorage.setItem('pharmatrack_access_token', 'preserved-signout-jwt');
  globalThis.fetch = async (url) => {
    if (url.endsWith('/auth/me')) {
      return jsonResponse({ message: 'Gateway timeout' }, 504, url);
    }
    if (url.endsWith('/health')) {
      return jsonResponse({ status: 'DOWN' }, 503, url);
    }
    return jsonResponse([], 200, url);
  };

  try {
    await domSignOut.render(React.createElement(AuthProvider, null, React.createElement(App)));
    await domSignOut.flush();

    assert.equal(getAuthToken(), 'preserved-signout-jwt');
    assert.ok(domSignOut.container.textContent.includes('Session Verification Interrupted'));

    const signOutBtn = [...domSignOut.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Sign Out')
    );
    assert.ok(signOutBtn);
    await domSignOut.click(signOutBtn);
    await domSignOut.flush();

    assert.equal(getAuthToken(), null);
    assert.equal(domSignOut.sessionStorage.getItem('pharmatrack_access_token'), null);
    assert.ok(domSignOut.container.querySelector('#login-username'));
  } finally {
    await domSignOut.unmount();
    clearAuthToken();
    globalThis.fetch = prevFetch;
  }
});
