import test from 'node:test';
import assert from 'node:assert/strict';
import React, { useState } from 'react';
import { createDomEnvironment } from '../../scripts/dom-harness.mjs';

const e = React.createElement;

function jsonResponse(body, status = 200, url = 'http://localhost:8080/api') {
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

test('Session Re-Authentication Lifecycle: preserves user and workspace state, allows password re-auth, replays queued GETs', async () => {
  const dom = await createDomEnvironment();
  const { clearAuthToken, setAuthToken, getAuthToken, apiRequest } = await import(
    '../services/apiClient.js'
  );
  const { AuthProvider, useAuth } = await import('./AuthContext.jsx');

  function WorkspaceHarness() {
    const { user, sessionState, isReauthenticating, reauthenticate, logout } = useAuth();
    const [copilotHistory] = useState([
      { id: 1, text: 'Hello Copilot, summarize MO-2026-0001' },
      { id: 2, text: 'Batch B-2026-0001 is currently in COATING stage' },
    ]);
    const [fetchedOrder, setFetchedOrder] = useState(null);
    const [actionError, setActionError] = useState(null);

    const triggerGetOrder = async () => {
      try {
        const order = await apiRequest('/orders/1');
        setFetchedOrder(order);
      } catch (err) {
        setActionError(err.message);
      }
    };

    return e(
      'div',
      { 'data-testid': 'workspace' },
      e('div', { 'data-testid': 'session-state' }, sessionState),
      e('div', { 'data-testid': 'user-info' }, user?.username),
      e(
        'div',
        { 'data-testid': 'copilot-container' },
        copilotHistory.map((m) =>
          e('div', { key: m.id, 'data-testid': 'copilot-msg' }, m.text)
        )
      ),
      fetchedOrder && e('div', { 'data-testid': 'fetched-order' }, fetchedOrder.orderNumber),
      actionError && e('div', { 'data-testid': 'action-error' }, actionError),
      e(
        'button',
        { 'data-testid': 'btn-get-order', onClick: triggerGetOrder },
        'Get Order'
      ),
      isReauthenticating &&
        e(
          'div',
          { 'data-testid': 'reauth-dialog' },
          e(
            'button',
            {
              'data-testid': 'btn-reauth-valid',
              onClick: async () => {
                try {
                  await reauthenticate({ password: 'valid-password' });
                } catch (e) {
                  // ignored
                }
              },
            },
            'Reauth Valid'
          ),
          e(
            'button',
            {
              'data-testid': 'btn-reauth-wrong',
              onClick: async () => {
                try {
                  await reauthenticate({ password: 'wrong-password' });
                } catch (e) {
                  // ignored
                }
              },
            },
            'Reauth Wrong'
          ),
          e(
            'button',
            {
              'data-testid': 'btn-signout',
              onClick: () => logout('User signed out'),
            },
            'Sign Out'
          )
        )
    );
  }

  const prevFetch = globalThis.fetch;
  let currentToken = 'initial-admin-jwt';
  setAuthToken(currentToken);

  const activeAdmin = {
    id: 1,
    username: 'admin',
    fullName: 'System Administrator',
    role: 'ADMIN',
    enabled: true,
  };

  globalThis.fetch = async (url, options) => {
    const authHeader = options?.headers?.Authorization;

    // Login endpoint
    if (url.endsWith('/auth/login')) {
      const body = JSON.parse(options?.body || '{}');
      if (body.password === 'valid-password') {
        currentToken = 'new-admin-jwt';
        return jsonResponse(
          {
            accessToken: 'new-admin-jwt',
            user: activeAdmin,
          },
          200,
          url
        );
      }
      return jsonResponse({ message: 'Invalid password' }, 401, url);
    }

    // Current user validation on mount
    if (url.endsWith('/auth/me')) {
      if (authHeader === 'Bearer initial-admin-jwt' || authHeader === 'Bearer new-admin-jwt') {
        return jsonResponse(activeAdmin, 200, url);
      }
      return jsonResponse({ message: 'Token expired', errorCode: 'TOKEN_EXPIRED' }, 401, url);
    }

    // Orders endpoint
    if (url.endsWith('/orders/1')) {
      if (authHeader === 'Bearer initial-admin-jwt') {
        // Expired on first try
        return jsonResponse(
          { message: 'Authentication token has expired', errorCode: 'TOKEN_EXPIRED' },
          401,
          url
        );
      }
      if (authHeader === 'Bearer new-admin-jwt') {
        return jsonResponse({ id: 1, orderNumber: 'MO-2026-0001' }, 200, url);
      }
      return jsonResponse({ message: 'Unauthorized' }, 401, url);
    }

    return jsonResponse({}, 200, url);
  };

  try {
    await dom.render(
      e(AuthProvider, null, e(WorkspaceHarness))
    );
    await dom.flush();
    await dom.flush();

    // 1. Initial mounted state
    assert.equal(dom.container.querySelector('[data-testid="user-info"]')?.textContent, 'admin');
    assert.equal(
      dom.container.querySelector('[data-testid="session-state"]')?.textContent,
      'authenticated'
    );
    assert.equal(dom.container.querySelectorAll('[data-testid="copilot-msg"]').length, 2);

    // 2. Trigger GET order while token is expired -> 401 triggers re-auth dialog & enqueues GET
    const btnGetOrder = dom.container.querySelector('[data-testid="btn-get-order"]');
    await dom.click(btnGetOrder);
    await dom.flush();

    // Verification: workspace is NOT unmounted!
    assert.equal(dom.container.querySelector('[data-testid="user-info"]')?.textContent, 'admin');
    assert.equal(
      dom.container.querySelector('[data-testid="session-state"]')?.textContent,
      'reauth_required'
    );
    // In-memory Copilot messages remain 100% intact!
    assert.equal(dom.container.querySelectorAll('[data-testid="copilot-msg"]').length, 2);
    assert.ok(dom.container.querySelector('[data-testid="reauth-dialog"]'));

    // 3. User attempts wrong password -> state becomes reauth_failed, modal stays open
    const btnWrong = dom.container.querySelector('[data-testid="btn-reauth-wrong"]');
    await dom.click(btnWrong);
    await dom.flush();

    assert.equal(
      dom.container.querySelector('[data-testid="session-state"]')?.textContent,
      'reauth_failed'
    );
    assert.equal(dom.container.querySelector('[data-testid="user-info"]')?.textContent, 'admin');
    assert.equal(dom.container.querySelectorAll('[data-testid="copilot-msg"]').length, 2);

    // 4. User provides valid password -> reauth succeeds, queued GET order executes and resolves!
    const btnValid = dom.container.querySelector('[data-testid="btn-reauth-valid"]');
    await dom.click(btnValid);
    await dom.flush();
    await dom.flush();

    assert.equal(
      dom.container.querySelector('[data-testid="session-state"]')?.textContent,
      'authenticated'
    );
    assert.equal(getAuthToken(), 'new-admin-jwt');
    // Replay succeeded! Fetched order rendered in DOM:
    assert.equal(
      dom.container.querySelector('[data-testid="fetched-order"]')?.textContent,
      'MO-2026-0001'
    );
    // Copilot messages still completely intact:
    assert.equal(dom.container.querySelectorAll('[data-testid="copilot-msg"]').length, 2);
    assert.equal(dom.container.querySelector('[data-testid="reauth-dialog"]'), null);
  } finally {
    await dom.unmount();
    clearAuthToken();
    globalThis.fetch = prevFetch;
  }
});

test('Session Re-Authentication: Identity mismatch rejects re-auth and logs out immediately', async () => {
  const dom = await createDomEnvironment();
  const { clearAuthToken, setAuthToken, apiRequest } = await import('../services/apiClient.js');
  const { AuthProvider, useAuth } = await import('./AuthContext.jsx');

  function MismatchHarness() {
    const { user, sessionState, isReauthenticating, reauthenticate } = useAuth();
    return e(
      'div',
      null,
      e('div', { 'data-testid': 'user-val' }, user ? user.username : 'NO_USER'),
      e('div', { 'data-testid': 'state-val' }, sessionState),
      isReauthenticating &&
        e(
          'button',
          {
            'data-testid': 'btn-mismatch',
            onClick: async () => {
              try {
                await reauthenticate({ password: 'switch-user-pwd' });
              } catch (e) {
                // Expected mismatch error
              }
            },
          },
          'Attempt Switch User'
        )
    );
  }

  const prevFetch = globalThis.fetch;
  setAuthToken('admin-sample-token');

  const adminUser = {
    id: 1,
    username: 'admin',
    fullName: 'Admin',
    role: 'ADMIN',
    enabled: true,
  };

  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/me')) {
      return jsonResponse(adminUser, 200, url);
    }
    if (url.endsWith('/auth/login')) {
      // Returns supervisor instead of admin
      return jsonResponse(
        {
          accessToken: 'supervisor-token',
          user: {
            id: 2,
            username: 'supervisor',
            fullName: 'Supervisor',
            role: 'PRODUCTION_SUPERVISOR',
            enabled: true,
          },
        },
        200,
        url
      );
    }
    return jsonResponse({ message: 'Token expired' }, 401, url);
  };

  try {
    await dom.render(
      e(AuthProvider, null, e(MismatchHarness))
    );
    await dom.flush();
    await dom.flush();

    assert.equal(dom.container.querySelector('[data-testid="user-val"]')?.textContent, 'admin');

    // Trigger 401 via GET /products
    const prodReq = apiRequest('/products').catch((err) => err);
    await dom.flush();

    assert.equal(
      dom.container.querySelector('[data-testid="state-val"]')?.textContent,
      'reauth_required'
    );

    // Attempt reauth with mismatched user
    const btnMismatch = dom.container.querySelector('[data-testid="btn-mismatch"]');
    await dom.click(btnMismatch);
    await dom.flush();

    // Must fail closed: logout called, sessionState logged_out, user cleared
    assert.equal(
      dom.container.querySelector('[data-testid="state-val"]')?.textContent,
      'logged_out'
    );
    assert.equal(dom.container.querySelector('[data-testid="user-val"]')?.textContent, 'NO_USER');

    // The queued product request should have been rejected
    const rejectedErr = await prodReq;
    assert.ok(rejectedErr instanceof Error);
    assert.match(rejectedErr.message, /Authenticated identity does not match active session/);
  } finally {
    await dom.unmount();
    clearAuthToken();
    globalThis.fetch = prevFetch;
  }
});
