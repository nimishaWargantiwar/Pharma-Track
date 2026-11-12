import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { createDomEnvironment } from '../../scripts/dom-harness.mjs';

function makeAuthValue(role = 'ADMIN', overrides = {}) {
  const userMap = {
    ADMIN: { id: 1, username: 'admin', fullName: 'System Administrator' },
    PRODUCTION_SUPERVISOR: { id: 2, username: 'prod.sup', fullName: 'Rohan Mehta' },
    OPERATOR: { id: 3, username: 'operator.floor', fullName: 'Vikram Joshi' },
    QC_ANALYST: { id: 4, username: 'qc.analyst', fullName: 'Sneha Kulkarni' },
    QA_REVIEWER: { id: 5, username: 'qa.reviewer', fullName: 'Dr. Asha Sen' },
  };
  const info = userMap[role] || userMap.ADMIN;
  return {
    user: { id: info.id, username: info.username, fullName: info.fullName, role, enabled: true },
    role,
    token: 'user-mgmt-jwt',
    actorDisplay: `${info.fullName} (@${info.username})`,
    isAuthenticated: true,
    isInitializing: false,
    sessionMessage: null,
    forbiddenBanner: null,
    login: async () => {},
    logout: () => {},
    clearSessionMessage: () => {},
    dismissForbiddenBanner: () => {},
    ...overrides,
  };
}

function jsonResponse(body, status = 200, url = 'http://localhost:8080/api/admin/users') {
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

test('UserManagement denies direct non-ADMIN access with zero API calls', async () => {
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: UserManagement } = await import('./UserManagement.jsx');

  for (const role of ['PRODUCTION_SUPERVISOR', 'OPERATOR', 'QC_ANALYST', 'QA_REVIEWER']) {
    const dom = await createDomEnvironment('http://localhost:5173/#/users');
    const calls = [];
    const prevFetch = globalThis.fetch;
    globalThis.fetch = async (url) => {
      calls.push(url);
      return jsonResponse([], 200, url);
    };

    try {
      await dom.render(
        React.createElement(
          AuthContext.Provider,
          { value: makeAuthValue(role) },
          React.createElement(UserManagement)
        )
      );
      await dom.flush();

      assert.equal(calls.length, 0, `${role} should trigger zero API requests`);
      assert.ok(dom.container.textContent.includes('Access Denied to User Management'));
    } finally {
      await dom.unmount();
      globalThis.fetch = prevFetch;
    }
  }
});

test('UserManagement separates loading, error + Retry recovery, empty directory, populated states, and never leaks password fields', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/users');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: UserManagement } = await import('./UserManagement.jsx');

  let resolveInitial;
  let attempt = 0;
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    attempt += 1;
    if (attempt === 1) {
      return new Promise((resolve) => {
        resolveInitial = resolve;
      });
    }
    if (attempt === 2) {
      return jsonResponse([], 200, url);
    }
    return jsonResponse(
      [
        {
          id: 1,
          username: 'admin',
          fullName: 'System Administrator',
          role: 'ADMIN',
          enabled: true,
          createdAt: '2026-11-01T09:00:00+05:30',
          updatedAt: '2026-11-01T09:00:00+05:30',
          passwordHash: '$2a$10$SHOULDNEVERAPPEARINUIHASH',
        },
        {
          id: 2,
          username: 'qa.reviewer',
          fullName: 'Dr. Asha Sen',
          role: 'QA_REVIEWER',
          enabled: true,
          createdAt: '2026-11-02T11:15:00+05:30',
          updatedAt: '2026-11-05T14:30:00+05:30',
        },
      ],
      200,
      url
    );
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(UserManagement)
      )
    );

    // 1. Loading state
    assert.ok(dom.container.textContent.includes('Loading user directory...'));
    assert.equal(dom.container.textContent.includes('No user accounts found'), false);

    // 2. Initial error state -> shows Retry, NOT empty state
    resolveInitial(jsonResponse({ message: 'Directory backend unavailable' }, 503));
    await dom.flush();
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Unable to load user accounts'));
    assert.ok(dom.container.textContent.includes('Directory backend unavailable'));
    assert.equal(dom.container.textContent.includes('No user accounts found'), false);

    // 3. Retry -> empty state
    const retryBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Retry')
    );
    assert.ok(retryBtn);
    await dom.click(retryBtn);
    await dom.flush();

    assert.ok(dom.container.textContent.includes('No user accounts found'));
    assert.equal(dom.container.textContent.includes('Unable to load user accounts'), false);

    // 4. Refresh -> populated table, no password hashes displayed
    const refreshBtn = dom.container.querySelector('button[title="Refresh user directory"]');
    assert.ok(refreshBtn);
    await dom.click(refreshBtn);
    await dom.flush();

    assert.ok(dom.container.textContent.includes('System Administrator'));
    assert.ok(dom.container.textContent.includes('@admin'));
    assert.ok(dom.container.textContent.includes('Dr. Asha Sen'));
    assert.ok(dom.container.textContent.includes('@qa.reviewer'));
    assert.equal(dom.container.textContent.includes('SHOULDNEVERAPPEARINUIHASH'), false);
    assert.ok(dom.container.textContent.includes('Showing 1\u20132 of 2 users'));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('UserManagement filters across full dataset (search, role, status) before 25-row pagination and resets to page 1', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/users');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: UserManagement } = await import('./UserManagement.jsx');

  const users = Array.from({ length: 30 }, (_, idx) => {
    const n = idx + 1;
    const role = n === 1 ? 'ADMIN' : n % 2 === 0 ? 'OPERATOR' : 'QC_ANALYST';
    const enabled = n !== 28; // User 28 on page 2 is Disabled QC_ANALYST
    return {
      id: n,
      username: `user.${String(n).padStart(2, '0')}`,
      fullName: `Pharma User ${String(n).padStart(2, '0')}`,
      role,
      enabled,
      createdAt: '2026-11-01T10:00:00+05:30',
      updatedAt: '2026-11-01T10:00:00+05:30',
    };
  });

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => jsonResponse(users, 200, url);

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', { user: users[0] }) },
        React.createElement(UserManagement)
      )
    );
    await dom.flush();

    // Default 25 rows per page
    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 30 users'));
    assert.equal(dom.container.textContent.includes('Pharma User 28'), false);

    // Move to page 2 -> see User 28
    const nextPageBtn = dom.container.querySelector('button[aria-label="Next page"]');
    assert.ok(nextPageBtn);
    await dom.click(nextPageBtn);
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Showing 26\u201330 of 30 users'));
    assert.ok(dom.container.textContent.includes('Pharma User 28'));

    // Sorting also resets page to 1 (sorted descending puts user 30 first)
    const sortUsernameBtn = [...dom.container.querySelectorAll('th')]
      .map((th) => th.querySelector('button'))
      .find((b) => b && b.textContent.includes('Username'));
    assert.ok(sortUsernameBtn);
    await dom.click(sortUsernameBtn); // asc
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 30 users'));
    await dom.click(sortUsernameBtn); // desc -> user.30 down to user.06
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Pharma User 30'));

    // Re-move to page 2 so we verify status filter resets page to 1
    await dom.click(dom.container.querySelector('button[aria-label="Next page"]'));
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Showing 26\u201330 of 30 users'));

    // Filter by Disabled status -> resets to page 1 and finds User 28 across full dataset
    const disabledFilterBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Disabled (1)')
    );
    assert.ok(disabledFilterBtn);
    await dom.click(disabledFilterBtn);
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Showing 1\u20131 of 1 users'));
    assert.ok(dom.container.textContent.includes('Pharma User 28'));

    // Switch back to All and filter by role QC_ANALYST, then search for "user.29"
    const allFilterBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('All (30)')
    );
    await dom.click(allFilterBtn);
    await dom.flush();

    const roleSelect = dom.container
      .querySelectorAll('select')
      .find((s) => s.options.some((o) => o.value === 'QC_ANALYST'));
    assert.ok(roleSelect);
    await dom.changeInput(roleSelect, 'QC_ANALYST');
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Showing 1\u201314 of 14 users'));

    const searchInput = dom.container.querySelector(
      'input[placeholder="Search users by full name or username..."]'
    );
    assert.ok(searchInput);
    await dom.changeInput(searchInput, 'user.29');
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Pharma User 29'));
    assert.equal(dom.container.textContent.includes('Pharma User 03'), false);

    // Clear via Reset Filters when mismatched
    await dom.changeInput(searchInput, 'nonexistent.account');
    await dom.flush();
    assert.ok(dom.container.textContent.includes('No matching users found'));

    const resetBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Reset Filters')
    );
    assert.ok(resetBtn);
    await dom.click(resetBtn);
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 30 users'));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('UserManagement supports Create User validation & 409 error, Edit Profile, Change Role, Reset Password, self-session logout, and Enable/Disable ConfirmModal', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/users');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: UserManagement } = await import('./UserManagement.jsx');

  let users = [
    {
      id: 1,
      username: 'admin',
      fullName: 'System Admin',
      role: 'ADMIN',
      enabled: true,
      createdAt: '2026-11-01T09:00:00+05:30',
      updatedAt: '2026-11-01T09:00:00+05:30',
    },
    {
      id: 2,
      username: 'line.worker',
      fullName: 'Ramesh Rao',
      role: 'OPERATOR',
      enabled: true,
      createdAt: '2026-11-02T09:00:00+05:30',
      updatedAt: '2026-11-02T09:00:00+05:30',
    },
  ];

  const apiCalls = [];
  const logoutReasons = [];
  let simulateDuplicateOnce = true;
  const prevFetch = globalThis.fetch;

  globalThis.fetch = async (url, options = {}) => {
    const method = options.method || 'GET';
    const body = options.body ? JSON.parse(options.body) : null;
    apiCalls.push({ url, method, body });

    if (url.endsWith('/admin/users') && method === 'GET') {
      return jsonResponse(users, 200, url);
    }
    if (url.endsWith('/admin/users') && method === 'POST') {
      if (simulateDuplicateOnce) {
        simulateDuplicateOnce = false;
        return jsonResponse({ message: 'User already exists with username: p.nair' }, 409, url);
      }
      const created = {
        id: 3,
        username: body.username,
        fullName: body.fullName,
        role: body.role,
        enabled: body.enabled,
        createdAt: '2026-11-12T10:00:00+05:30',
        updatedAt: '2026-11-12T10:00:00+05:30',
      };
      users = [...users, created];
      return jsonResponse(created, 201, url);
    }
    if (url.endsWith('/admin/users/2') && method === 'PATCH') {
      users = users.map((u) => (u.id === 2 ? { ...u, fullName: body.fullName } : u));
      return jsonResponse(users.find((u) => u.id === 2), 200, url);
    }
    if (url.endsWith('/admin/users/2/role') && method === 'PATCH') {
      users = users.map((u) => (u.id === 2 ? { ...u, role: body.role } : u));
      return jsonResponse(users.find((u) => u.id === 2), 200, url);
    }
    if (url.endsWith('/admin/users/2/password') && method === 'PATCH') {
      return jsonResponse(users.find((u) => u.id === 2), 200, url);
    }
    if (url.endsWith('/admin/users/1/password') && method === 'PATCH') {
      return jsonResponse(users.find((u) => u.id === 1), 200, url);
    }
    if (url.endsWith('/admin/users/2/status') && method === 'PATCH') {
      users = users.map((u) => (u.id === 2 ? { ...u, enabled: body.enabled } : u));
      return jsonResponse(users.find((u) => u.id === 2), 200, url);
    }
    return jsonResponse({}, 404, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        {
          value: makeAuthValue('ADMIN', {
            user: users[0],
            logout: (msg) => logoutReasons.push(msg),
          }),
        },
        React.createElement(UserManagement)
      )
    );
    await dom.flush();

    // Self disable button & sole-admin disable button disabled cleanly on admin row
    const disableSelfBtn = dom.container.querySelector('button[aria-label="Disable admin"]');
    assert.equal(disableSelfBtn?.disabled, true);

    // 1. Create User modal -> empty submit validates -> 409 error -> success
    const addBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Add User')
    );
    assert.ok(addBtn);
    await dom.click(addBtn);
    await dom.flush();

    const createDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(createDialog);
    const submitCreateBtn = [...createDialog.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Create User')
    );
    await dom.click(submitCreateBtn);
    await dom.flush();
    assert.ok(createDialog.textContent.includes('Full name is required'));
    assert.ok(createDialog.textContent.includes('Username is required'));
    assert.ok(createDialog.textContent.includes('Password is required'));

    const fullNameInput = createDialog.querySelector('input[name="fullName"]');
    const usernameInput = createDialog.querySelector('input[name="username"]');
    const pwdInput = createDialog.querySelector('input[name="password"]');
    const roleSelect = createDialog.querySelector('select[name="role"]');

    // Password show/hide toggle
    const toggleCreatePwdBtn = createDialog.querySelector('button[aria-label="Show temporary password"]');
    assert.equal(pwdInput.type, 'password');
    await dom.click(toggleCreatePwdBtn);
    await dom.flush();
    assert.equal(pwdInput.type, 'text');

    await dom.changeInput(fullNameInput, 'Dr. Priya Nair');
    await dom.changeInput(usernameInput, 'P.Nair');
    await dom.changeInput(pwdInput, 'TempPass9');
    await dom.changeInput(roleSelect, 'QC_ANALYST');

    // First submit triggers 409 Duplicate error
    await dom.click(submitCreateBtn);
    await dom.flush();
    await dom.flush();
    assert.ok(createDialog.textContent.includes('User already exists with username: p.nair'));

    // Second submit succeeds
    await dom.click(submitCreateBtn);
    await dom.flush();
    await dom.flush();
    assert.equal(dom.container.querySelector('[role="dialog"]'), null);
    assert.ok(dom.container.textContent.includes('Dr. Priya Nair'));
    assert.ok(dom.container.textContent.includes('@p.nair'));

    // 2. Edit Profile for line.worker
    const editWorkerBtn = dom.container.querySelector('button[aria-label="Edit profile for line.worker"]');
    assert.ok(editWorkerBtn);
    await dom.click(editWorkerBtn);
    await dom.flush();

    const editDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(editDialog.textContent.includes('@line.worker'));
    assert.ok(editDialog.textContent.includes('Immutable'));
    await dom.changeInput(editDialog.querySelector('input[name="fullName"]'), '  Ramesh S. Rao  ');
    const saveProfileBtn = [...editDialog.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Save Profile')
    );
    await dom.click(saveProfileBtn);
    await dom.flush();
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Ramesh S. Rao'));

    // 3. Change Role for line.worker -> QC_ANALYST
    const roleWorkerBtn = dom.container.querySelector('button[aria-label="Change role for line.worker"]');
    await dom.click(roleWorkerBtn);
    await dom.flush();

    const roleDialog = dom.container.querySelector('[role="dialog"]');
    await dom.changeInput(roleDialog.querySelector('select[name="role"]'), 'QA_REVIEWER');
    const updateRoleBtn = [...roleDialog.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Update Role')
    );
    await dom.click(updateRoleBtn);
    await dom.flush();
    await dom.flush();
    assert.ok(
      apiCalls.some(
        (c) =>
          c.url.endsWith('/admin/users/2/role') &&
          c.method === 'PATCH' &&
          c.body?.role === 'QA_REVIEWER'
      )
    );

    // 4. Enable / Disable ConfirmModal: Cancel triggers 0 PATCH calls; Confirm toggles status
    const disableWorkerBtn = dom.container.querySelector('button[aria-label="Disable line.worker"]');
    await dom.click(disableWorkerBtn);
    await dom.flush();

    const confirmDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(confirmDialog.textContent.includes('Disable User Account'));
    const cancelBtn = [...confirmDialog.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Cancel')
    );
    await dom.click(cancelBtn);
    await dom.flush();
    assert.equal(
      apiCalls.some((c) => c.url.endsWith('/admin/users/2/status')),
      false
    );

    await dom.click(dom.container.querySelector('button[aria-label="Disable line.worker"]'));
    await dom.flush();
    const confirmDisableBtn = [...dom.container.querySelector('[role="dialog"]').querySelectorAll('button')].find(
      (b) => b.textContent.includes('Disable Account')
    );
    await dom.click(confirmDisableBtn);
    await dom.flush();
    await dom.flush();
    assert.ok(
      apiCalls.some(
        (c) =>
          c.url.endsWith('/admin/users/2/status') &&
          c.method === 'PATCH' &&
          c.body?.enabled === false
      )
    );
    assert.ok(dom.container.querySelector('button[aria-label="Enable line.worker"]'));

    // 5. Reset Temporary Password for line.worker (validates mismatch, succeeds, never renders secret in table)
    await dom.click(dom.container.querySelector('button[aria-label="Reset password for line.worker"]'));
    await dom.flush();
    const pwdDialog = dom.container.querySelector('[role="dialog"]');
    await dom.changeInput(pwdDialog.querySelector('input[name="newPassword"]'), 'FreshTemp9');
    await dom.changeInput(pwdDialog.querySelector('input[name="confirmPassword"]'), 'Mismatch9');
    const resetSubmitBtn = [...pwdDialog.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Reset Password')
    );
    await dom.click(resetSubmitBtn);
    await dom.flush();
    assert.ok(pwdDialog.textContent.includes('Confirmation password does not match'));

    await dom.changeInput(pwdDialog.querySelector('input[name="confirmPassword"]'), 'FreshTemp9');
    await dom.click(resetSubmitBtn);
    await dom.flush();
    await dom.flush();
    assert.equal(dom.container.querySelector('[role="dialog"]'), null);
    assert.equal(dom.container.textContent.includes('FreshTemp9'), false);

    // 6. Self password reset calls logout gracefully without triggering extra GET /admin/users with revoked token
    const fetchesBeforeSelfReset = apiCalls.filter(
      (c) => c.url.endsWith('/admin/users') && c.method === 'GET'
    ).length;
    await dom.click(dom.container.querySelector('button[aria-label="Reset password for admin"]'));
    await dom.flush();
    const selfPwdDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(selfPwdDialog.textContent.includes('You are resetting your own password'));
    await dom.changeInput(selfPwdDialog.querySelector('input[name="newPassword"]'), 'AdminSelf99');
    await dom.changeInput(selfPwdDialog.querySelector('input[name="confirmPassword"]'), 'AdminSelf99');
    await dom.click(
      [...selfPwdDialog.querySelectorAll('button')].find((b) => b.textContent.includes('Reset Password'))
    );
    await dom.flush();
    await dom.flush();

    assert.equal(logoutReasons.length, 1);
    assert.ok(logoutReasons[0].includes('invalidated'));
    const fetchesAfterSelfReset = apiCalls.filter(
      (c) => c.url.endsWith('/admin/users') && c.method === 'GET'
    ).length;
    assert.equal(fetchesAfterSelfReset, fetchesBeforeSelfReset);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});
