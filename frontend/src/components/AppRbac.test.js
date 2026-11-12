import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { createDomEnvironment } from '../../scripts/dom-harness.mjs';

function makeAuthValue(role, overrides = {}) {
  const usernameMap = {
    ADMIN: 'admin',
    PRODUCTION_SUPERVISOR: 'prod.sup',
    OPERATOR: 'operator.floor',
    QC_ANALYST: 'qc.analyst',
    QA_REVIEWER: 'qa.reviewer',
  };
  const nameMap = {
    ADMIN: 'System Admin',
    PRODUCTION_SUPERVISOR: 'Rohan Mehta',
    OPERATOR: 'Vikram Joshi',
    QC_ANALYST: 'Sneha Kulkarni',
    QA_REVIEWER: 'Dr. Asha Sen',
  };
  const username = usernameMap[role] || 'user';
  const fullName = nameMap[role] || 'User';
  return {
    user: { id: 1, username, fullName, role, enabled: true },
    role,
    token: 'test-jwt-token',
    actorDisplay: `${fullName} (@${username})`,
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

test('App hides Recipe Management for OPERATOR and blocks direct #/recipes access without calling /api/recipes', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/recipes');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: App } = await import('../App.jsx');

  const requestedUrls = [];
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    requestedUrls.push(url);
    if (url.endsWith('/health')) {
      return jsonResponse({ status: 'UP', message: 'Connected' }, 200, url);
    }
    if (url.endsWith('/products')) {
      return jsonResponse([], 200, url);
    }
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(App)
      )
    );
    await dom.flush();

    const navText = dom.container.querySelector('nav')?.textContent || '';
    assert.equal(navText.includes('Recipe Management'), false);
    assert.ok(navText.includes('Product Master'));
    assert.ok(navText.includes('Production Execution'));

    // Direct #/recipes renders AccessDeniedView and makes ZERO calls to /api/recipes
    assert.ok(dom.container.textContent.includes('Access Denied to Recipe Management'));
    assert.equal(
      requestedUrls.some((u) => u.includes('/api/recipes')),
      false
    );

    // Authenticated user chip displays fullName, @username, and Operator role badge
    assert.ok(dom.container.textContent.includes('Vikram Joshi'));
    assert.ok(dom.container.textContent.includes('@operator.floor'));
    assert.ok(dom.container.textContent.includes('Operator'));

    // Return button switches back to default allowed module (products)
    const returnBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Return to Product Master')
    );
    assert.ok(returnBtn);
    await dom.click(returnBtn);
    await dom.flush();
    assert.equal(dom.window.location.hash, '#/products');
    assert.ok(requestedUrls.some((u) => u.endsWith('/api/products')));
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('App shows Recipe Management nav for non-OPERATOR roles', async () => {
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: App } = await import('../App.jsx');

  for (const role of ['ADMIN', 'PRODUCTION_SUPERVISOR', 'QC_ANALYST', 'QA_REVIEWER']) {
    const html = renderToStaticMarkup(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue(role) },
        React.createElement(App)
      )
    );
    assert.ok(html.includes('Recipe Management'), `Expected Recipe Management tab for ${role}`);
  }
});

test('ProductManagement and ProductTable show Add/Edit/Status actions ONLY to ADMIN', async () => {
  const dom = await createDomEnvironment();
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: ProductManagement } = await import('./ProductManagement.jsx');
  const { default: ProductTable } = await import('./ProductTable.jsx');

  const sampleProducts = [
    {
      id: 1,
      productCode: 'PRD-001',
      productName: 'Paracetamol 500mg',
      dosageForm: 'Tablet',
      strength: '500 mg',
      status: 'ACTIVE',
    },
  ];
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => jsonResponse(sampleProducts, 200, url);

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(ProductManagement)
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Add Product'));
    assert.ok(dom.container.textContent.includes('Deactivate'));
    assert.ok(dom.container.textContent.includes('Edit'));

    for (const role of ['PRODUCTION_SUPERVISOR', 'OPERATOR', 'QC_ANALYST', 'QA_REVIEWER']) {
      await dom.render(
        React.createElement(
          AuthContext.Provider,
          { value: makeAuthValue(role) },
          React.createElement(ProductManagement)
        )
      );
      await dom.flush();
      assert.equal(
        dom.container.textContent.includes('Add Product'),
        false,
        `${role} should not see Add Product`
      );
      assert.equal(
        dom.container.textContent.includes('Deactivate'),
        false,
        `${role} should not see Deactivate`
      );
      assert.equal(
        Boolean(dom.container.querySelector('button[title="Edit product formulation"]')),
        false,
        `${role} should not see Edit button`
      );
    }

    // Verify ProductTable prop guard directly
    const readOnlyTableHtml = renderToStaticMarkup(
      React.createElement(ProductTable, {
        products: sampleProducts,
        onEdit: () => {},
        onToggleStatus: () => {},
        canManageProducts: false,
      })
    );
    assert.equal(readOnlyTableHtml.includes('Deactivate'), false);
    assert.equal(readOnlyTableHtml.includes('Edit product formulation'), false);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('RecipeManagement enforces OPERATOR guard and separates authoring vs approval actions cleanly', async () => {
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: RecipeManagement } = await import('./RecipeManagement.jsx');
  const { default: RecipeTable } = await import('./RecipeTable.jsx');

  // Direct mount as OPERATOR never fetches /api/recipes and renders AccessDeniedView
  const domOp = await createDomEnvironment();
  const opCalls = [];
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    opCalls.push(url);
    return jsonResponse([], 200, url);
  };

  try {
    await domOp.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(RecipeManagement)
      )
    );
    await domOp.flush();
    assert.equal(opCalls.length, 0);
    assert.ok(domOp.container.textContent.includes('Access Denied to Recipe Management'));
  } finally {
    await domOp.unmount();
  }

  const draftRecipe = [
    {
      id: 10,
      recipeCode: 'RCP-PAR-500',
      version: 1,
      status: 'DRAFT',
      productName: 'Paracetamol 500mg',
      productCode: 'PRD-001',
      batchSize: 1000,
      batchUnit: 'Tablets',
      materials: [{ materialName: 'API', quantity: 500, unit: 'g' }],
      steps: [{ stepNumber: 1, operationName: 'Blend', instructions: 'Mix' }],
    },
  ];

  // Test real RecipeManagement -> RecipeTable prop wiring across roles
  const dom = await createDomEnvironment();
  globalThis.fetch = async (url) => jsonResponse(draftRecipe, 200, url);

  try {
    // PRODUCTION_SUPERVISOR sees authoring buttons (Add Recipe, Edit, New Ver, Delete) but NOT Approve
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('PRODUCTION_SUPERVISOR') },
        React.createElement(RecipeManagement)
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Add Recipe'));
    assert.ok(dom.container.querySelector('button[title="Edit DRAFT recipe"]'));
    assert.ok(dom.container.textContent.includes('New Ver'));
    assert.ok(dom.container.querySelector('button[title="Delete DRAFT recipe"]'));
    assert.equal(dom.container.querySelector('button[title="Approve recipe version"]'), null);

    // QA_REVIEWER sees Approve + View Details, but NO Add/Edit/New Ver/Delete
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(RecipeManagement)
      )
    );
    await dom.flush();
    assert.equal(dom.container.textContent.includes('Add Recipe'), false);
    assert.ok(dom.container.querySelector('button[title="Approve recipe version"]'));
    assert.equal(dom.container.querySelector('button[title="Edit DRAFT recipe"]'), null);
    assert.equal(dom.container.textContent.includes('New Ver'), false);
    assert.equal(dom.container.querySelector('button[title="Delete DRAFT recipe"]'), null);

    // QC_ANALYST is read-only
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QC_ANALYST') },
        React.createElement(RecipeManagement)
      )
    );
    await dom.flush();
    assert.equal(dom.container.textContent.includes('Add Recipe'), false);
    assert.equal(dom.container.querySelector('button[title="Approve recipe version"]'), null);
    assert.equal(dom.container.querySelector('button[title="Edit DRAFT recipe"]'), null);
    assert.equal(dom.container.textContent.includes('New Ver'), false);

    // ADMIN has full authoring + approval
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(RecipeManagement)
      )
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Add Recipe'));
    assert.ok(dom.container.querySelector('button[title="Approve recipe version"]'));
    assert.ok(dom.container.querySelector('button[title="Edit DRAFT recipe"]'));

    // Also verify RecipeTable direct read-only render
    const readOnlyTable = renderToStaticMarkup(
      React.createElement(RecipeTable, {
        recipes: draftRecipe,
        onView: () => {},
        canAuthor: false,
        canApprove: false,
      })
    );
    assert.equal(readOnlyTable.includes('Approve recipe version'), false);
    assert.equal(readOnlyTable.includes('Edit DRAFT recipe'), false);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('App shows User Management navigation ONLY to ADMIN and blocks direct #/users for all non-ADMIN roles without calling /api/admin/users', async () => {
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: App } = await import('../App.jsx');

  // ADMIN sees User Management tab
  const adminHtml = renderToStaticMarkup(
    React.createElement(
      AuthContext.Provider,
      { value: makeAuthValue('ADMIN') },
      React.createElement(App)
    )
  );
  assert.ok(adminHtml.includes('User Management'));

  // Non-ADMIN roles neither see the User Management tab nor call /api/admin/users on direct #/users navigation
  for (const nonAdminRole of ['PRODUCTION_SUPERVISOR', 'OPERATOR', 'QC_ANALYST', 'QA_REVIEWER']) {
    const dom = await createDomEnvironment('http://localhost:5173/#/users');
    const requestedUrls = [];
    const prevFetch = globalThis.fetch;
    globalThis.fetch = async (url) => {
      requestedUrls.push(url);
      if (url.endsWith('/health')) {
        return jsonResponse({ status: 'UP', message: 'Connected' }, 200, url);
      }
      return jsonResponse([], 200, url);
    };

    try {
      await dom.render(
        React.createElement(
          AuthContext.Provider,
          { value: makeAuthValue(nonAdminRole) },
          React.createElement(App)
        )
      );
      await dom.flush();

      const navText = dom.container.querySelector('nav')?.textContent || '';
      assert.equal(
        navText.includes('User Management'),
        false,
        `${nonAdminRole} should not see User Management nav item`
      );
      assert.ok(
        dom.container.textContent.includes('Access Denied to User Management'),
        `${nonAdminRole} should see Access Denied to User Management`
      );
      assert.equal(
        requestedUrls.some((u) => u.includes('/api/admin/users')),
        false,
        `${nonAdminRole} must make zero calls to /api/admin/users`
      );
    } finally {
      await dom.unmount();
      globalThis.fetch = prevFetch;
    }
  }
});

test('App shows Inventory & Warehouse navigation for all 5 roles and renders InventoryManagement at #/inventory', async () => {
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: App } = await import('../App.jsx');

  for (const role of ['ADMIN', 'PRODUCTION_SUPERVISOR', 'OPERATOR', 'QC_ANALYST', 'QA_REVIEWER']) {
    const html = renderToStaticMarkup(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue(role) },
        React.createElement(App)
      )
    );
    assert.ok(html.includes('Inventory &amp; Warehouse'), `Expected Inventory tab for ${role}`);
  }

  const dom = await createDomEnvironment('http://localhost:5173/#/inventory');
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    if (url.endsWith('/inventory/summary')) return jsonResponse({}, 200, url);
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QC_ANALYST') },
        React.createElement(App)
      )
    );
    await dom.flush();
    assert.ok(dom.container.querySelector('[data-testid="inventory-management"]'));
    assert.equal(
      dom.container.querySelector('[data-testid="active-module-indicator"]')?.textContent,
      'Inventory & Warehouse'
    );
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('QualityManagement blocks direct #/quality?view=audit for OPERATOR, QC_ANALYST, and PRODUCTION_SUPERVISOR without calling /api/quality/audit-logs, while allowing ADMIN and QA_REVIEWER', async () => {
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: App } = await import('../App.jsx');

  for (const deniedRole of ['OPERATOR', 'QC_ANALYST', 'PRODUCTION_SUPERVISOR']) {
    const dom = await createDomEnvironment('http://localhost:5173/#/quality?view=audit');
    const requestedUrls = [];
    const prevFetch = globalThis.fetch;
    globalThis.fetch = async (url) => {
      requestedUrls.push(url);
      if (url.endsWith('/health')) {
        return jsonResponse({ status: 'UP', message: 'Connected' }, 200, url);
      }
      return jsonResponse([], 200, url);
    };

    try {
      await dom.render(
        React.createElement(
          AuthContext.Provider,
          { value: makeAuthValue(deniedRole) },
          React.createElement(App)
        )
      );
      await dom.flush();

      assert.equal(
        dom.container.textContent.includes('Append-Only Quality Record Log'),
        false,
        `${deniedRole} must not see global audit trail content`
      );
      assert.equal(
        dom.container.textContent.includes('Audit Trail'),
        false,
        `${deniedRole} must not see Audit Trail sub-tab`
      );
      assert.equal(
        requestedUrls.some((u) => u.includes('/quality/audit-logs')),
        false,
        `${deniedRole} must make zero requests to /api/quality/audit-logs`
      );
    } finally {
      await dom.unmount();
      globalThis.fetch = prevFetch;
    }
  }

  for (const allowedRole of ['ADMIN', 'QA_REVIEWER']) {
    const dom = await createDomEnvironment('http://localhost:5173/#/quality?view=audit');
    const requestedUrls = [];
    const prevFetch = globalThis.fetch;
    globalThis.fetch = async (url) => {
      requestedUrls.push(url);
      if (url.endsWith('/health')) {
        return jsonResponse({ status: 'UP', message: 'Connected' }, 200, url);
      }
      return jsonResponse([], 200, url);
    };

    try {
      await dom.render(
        React.createElement(
          AuthContext.Provider,
          { value: makeAuthValue(allowedRole) },
          React.createElement(App)
        )
      );
      await dom.flush();

      assert.ok(
        dom.container.textContent.includes('Append-Only Quality Record Log'),
        `${allowedRole} should see global audit trail content on #/quality?view=audit`
      );
      assert.ok(
        requestedUrls.some((u) => u.includes('/quality/audit-logs')),
        `${allowedRole} should load /api/quality/audit-logs`
      );
    } finally {
      await dom.unmount();
      globalThis.fetch = prevFetch;
    }
  }
});
