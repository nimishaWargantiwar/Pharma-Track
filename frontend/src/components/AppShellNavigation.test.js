import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { createDomEnvironment, MiniEvent } from '../../scripts/dom-harness.mjs';

function makeAuthValue(role = 'ADMIN') {
  const map = {
    ADMIN: { username: 'admin', fullName: 'System Admin' },
    OPERATOR: { username: 'operator.floor', fullName: 'Vikram Joshi' },
    PRODUCTION_SUPERVISOR: { username: 'prod.sup', fullName: 'Rohan Mehta' },
    QC_ANALYST: { username: 'qc.analyst', fullName: 'Sneha Kulkarni' },
    QA_REVIEWER: { username: 'qa.reviewer', fullName: 'Dr. Asha Sen' },
  };
  const info = map[role] || map.ADMIN;
  return {
    user: { id: 1, username: info.username, fullName: info.fullName, role, enabled: true },
    role,
    token: 'shell-test-jwt',
    actorDisplay: `${info.fullName} (@${info.username})`,
    isAuthenticated: true,
    isInitializing: false,
    sessionMessage: null,
    forbiddenBanner: null,
    login: async () => {},
    logout: () => {},
    clearSessionMessage: () => {},
    dismissForbiddenBanner: () => {},
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

function makeKeyEvent(key) {
  const evt = new MiniEvent('keydown', { bubbles: true, cancelable: true });
  evt.key = key;
  evt.code = key;
  return evt;
}

test('Desktop collapsible sidebar highlights active module, respects RBAC, and toggles 240px/64px width', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/products');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: App } = await import('../App.jsx');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(App)
      )
    );
    await dom.flush();

    const sidebar = dom.container.querySelector('[data-testid="desktop-sidebar"]');
    const toggleBtn = dom.container.querySelector('[data-testid="sidebar-collapse-toggle"]');
    assert.ok(sidebar);
    assert.ok(toggleBtn);
    assert.equal(sidebar.getAttribute('data-collapsed'), 'false');
    assert.ok(sidebar.className.includes('lg:w-60'));
    assert.ok(sidebar.className.includes('w-16')); // tablet compact rail

    // Collapse sidebar -> 64px (w-16)
    await dom.click(toggleBtn);
    await dom.flush();
    assert.equal(sidebar.getAttribute('data-collapsed'), 'true');
    assert.equal(toggleBtn.getAttribute('aria-expanded'), 'false');
    assert.ok(sidebar.className.includes('w-16'));
    assert.equal(sidebar.className.includes('lg:w-60'), false);

    // Click Orders in sidebar -> updates route & aria-current="page"
    const ordersBtn = sidebar.querySelector('button[aria-label="Manufacturing Orders"]');
    assert.ok(ordersBtn);
    await dom.click(ordersBtn);
    await dom.flush();

    assert.equal(dom.window.location.hash, '#/orders');
    assert.equal(ordersBtn.getAttribute('aria-current'), 'page');
    assert.equal(
      dom.container.querySelector('[data-testid="active-module-indicator"]')?.textContent,
      'Manufacturing Orders'
    );

    // Click Inventory & Warehouse in sidebar -> updates route & aria-current="page"
    const inventoryBtn = sidebar.querySelector('button[aria-label="Inventory & Warehouse"]');
    assert.ok(inventoryBtn);
    await dom.click(inventoryBtn);
    await dom.flush();

    assert.equal(dom.window.location.hash, '#/inventory');
    assert.equal(inventoryBtn.getAttribute('aria-current'), 'page');
    assert.equal(
      dom.container.querySelector('[data-testid="active-module-indicator"]')?.textContent,
      'Inventory & Warehouse'
    );

    // Click User Management in ADMIN sidebar -> navigates to #/users
    const usersBtn = sidebar.querySelector('button[aria-label="User Management"]');
    assert.ok(usersBtn);
    await dom.click(usersBtn);
    await dom.flush();

    assert.equal(dom.window.location.hash, '#/users');
    assert.equal(usersBtn.getAttribute('aria-current'), 'page');
    assert.equal(
      dom.container.querySelector('[data-testid="active-module-indicator"]')?.textContent,
      'User Management'
    );

    // Re-render as OPERATOR -> Recipe Management and User Management absent from sidebar
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(App)
      )
    );
    await dom.flush();
    const operatorSidebar = dom.container.querySelector('[data-testid="desktop-sidebar"]');
    assert.ok(operatorSidebar);
    assert.ok(operatorSidebar.querySelector('button[aria-label="Product Master"]'));
    assert.equal(
      operatorSidebar.querySelector('button[aria-label="Recipe Management"]'),
      null
    );
    assert.equal(
      operatorSidebar.querySelector('button[aria-label="User Management"]'),
      null
    );
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('Mobile navigation drawer opens, supports Escape & keyboard focus restore, and navigates modules cleanly', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/products');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: App } = await import('../App.jsx');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('QA_REVIEWER') },
        React.createElement(App)
      )
    );
    await dom.flush();

    const menuBtn = dom.container.querySelector('[data-testid="mobile-menu-toggle"]');
    assert.ok(menuBtn);
    assert.equal(menuBtn.getAttribute('aria-expanded'), 'false');
    assert.equal(dom.container.querySelector('#mobile-navigation-drawer'), null);

    // Open mobile drawer
    await dom.click(menuBtn);
    await dom.flush();

    const drawer = dom.container.querySelector('#mobile-navigation-drawer');
    assert.ok(drawer);
    assert.equal(drawer.getAttribute('role'), 'dialog');
    assert.equal(drawer.getAttribute('aria-modal'), 'true');
    assert.equal(menuBtn.getAttribute('aria-expanded'), 'true');
    assert.equal(drawer.contains(dom.document.activeElement), true);

    // Press Escape -> closes drawer and restores focus to mobile menu toggle
    await dom.act(async () => {
      drawer.dispatchEvent(makeKeyEvent('Escape'));
    });
    await dom.flush();
    assert.equal(dom.container.querySelector('#mobile-navigation-drawer'), null);
    assert.equal(dom.document.activeElement, menuBtn);

    // Re-open mobile drawer -> close via explicit Close button -> restores focus cleanly
    await dom.click(menuBtn);
    await dom.flush();
    const closeMenuBtn = dom.container.querySelector('button[aria-label="Close navigation menu"]');
    assert.ok(closeMenuBtn);
    await dom.click(closeMenuBtn);
    await dom.flush();
    assert.equal(dom.container.querySelector('#mobile-navigation-drawer'), null);
    assert.equal(dom.document.activeElement, menuBtn);

    // Re-open mobile drawer and navigate to Quality Management
    await dom.click(menuBtn);
    await dom.flush();
    const openDrawer = dom.container.querySelector('#mobile-navigation-drawer');
    const mobileNav = openDrawer?.querySelector('nav');
    const qualityNavBtn = [...(mobileNav?.querySelectorAll('button') || [])].find((b) =>
      b.textContent.includes('Quality Management')
    );
    assert.ok(qualityNavBtn);
    await dom.click(qualityNavBtn);
    await dom.flush();

    assert.equal(dom.window.location.hash, '#/quality');
    assert.equal(dom.container.querySelector('#mobile-navigation-drawer'), null);
    assert.equal(
      dom.container.querySelector('[data-testid="active-module-indicator"]')?.textContent,
      'Quality Management'
    );
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('Toast notifications triggered by modules persist in ToastRegion across module navigation', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/products');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: App } = await import('../App.jsx');

  let productList = [
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
  globalThis.fetch = async (url, options = {}) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    if (url.endsWith('/products') && (!options.method || options.method === 'GET')) {
      return jsonResponse(productList, 200, url);
    }
    if (url.includes('/products/1/status') && options.method === 'PATCH') {
      productList = [{ ...productList[0], status: 'INACTIVE' }];
      return jsonResponse(productList[0], 200, url);
    }
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(App)
      )
    );
    await dom.flush();

    // Trigger a module notification from ProductManagement via NotificationBanner -> ToastProvider bridge
    const deactivateBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Deactivate')
    );
    assert.ok(deactivateBtn);
    await dom.click(deactivateBtn);
    await dom.flush();

    const confirmDeactivateBtn = dom.container
      .querySelector('[role="dialog"]')
      ?.querySelectorAll('button');
    const confirmActionBtn = [...(confirmDeactivateBtn || [])].find((b) =>
      b.textContent.includes('Deactivate Product')
    );
    assert.ok(confirmActionBtn);
    await dom.click(confirmActionBtn);
    await dom.flush();
    await dom.flush();

    const toastRegion = dom.container.querySelector('[data-testid="toast-region"]');
    assert.ok(toastRegion);
    assert.ok(toastRegion.textContent.includes('Product PRD-001 status changed to INACTIVE.'));

    // Navigate away from Product Master -> Manufacturing Orders -> System Overview
    const sidebar = dom.container.querySelector('[data-testid="desktop-sidebar"]');
    await dom.click(sidebar.querySelector('button[aria-label="Manufacturing Orders"]'));
    await dom.flush();
    assert.equal(dom.window.location.hash, '#/orders');
    assert.ok(toastRegion.textContent.includes('Product PRD-001 status changed to INACTIVE.'));

    await dom.click(sidebar.querySelector('button[aria-label="System Overview"]'));
    await dom.flush();
    assert.equal(dom.window.location.hash, '#/system');
    assert.ok(toastRegion.textContent.includes('Product PRD-001 status changed to INACTIVE.'));

    // Manual dismiss removes the persisted toast cleanly
    const dismissBtn = toastRegion.querySelector('button[aria-label="Dismiss success notification"]');
    assert.ok(dismissBtn);
    await dom.click(dismissBtn);
    await dom.flush();
    assert.equal(toastRegion.textContent.includes('Product PRD-001 status changed to INACTIVE.'), false);
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});

test('Skip-to-main-content links move focus without mutating active hash routes and LoginPage exposes aria-describedby errors', async () => {
  const dom = await createDomEnvironment('http://localhost:5173/#/orders');
  const { AuthContext } = await import('../context/AuthContext.jsx');
  const { default: App } = await import('../App.jsx');
  const { default: LoginPage } = await import('./LoginPage.jsx');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    return jsonResponse([], 200, url);
  };

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(App)
      )
    );
    await dom.flush();

    const skipLink = dom.container.querySelector('a[href="#main-content"]');
    const mainEl = dom.container.querySelector('#main-content');
    assert.ok(skipLink);
    assert.ok(mainEl);

    await dom.click(skipLink);
    await dom.flush();
    assert.equal(dom.window.location.hash, '#/orders');
    assert.equal(dom.document.activeElement, mainEl);

    // Render LoginPage unauthenticated state and submit empty credentials
    const loginAuth = {
      ...makeAuthValue('ADMIN'),
      isAuthenticated: false,
      user: null,
      role: null,
      token: null,
    };
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: loginAuth },
        React.createElement(LoginPage, { backendStatus: 'up' })
      )
    );
    await dom.flush();

    const loginSkipLink = dom.container.querySelector('a[href="#login-main-content"]');
    const loginMain = dom.container.querySelector('#login-main-content');
    assert.ok(loginSkipLink);
    assert.ok(loginMain);
    await dom.click(loginSkipLink);
    await dom.flush();
    assert.equal(dom.document.activeElement, loginMain);

    const form = dom.container.querySelector('form');
    assert.ok(form);
    await dom.submit(form);
    await dom.flush();

    const usernameInput = dom.container.querySelector('#login-username');
    const passwordInput = dom.container.querySelector('#login-password');
    assert.equal(usernameInput?.getAttribute('aria-invalid'), 'true');
    assert.equal(passwordInput?.getAttribute('aria-invalid'), 'true');

    const usernameErrId = usernameInput?.getAttribute('aria-describedby');
    const passwordErrId = passwordInput?.getAttribute('aria-describedby');
    assert.ok(usernameErrId);
    assert.ok(passwordErrId);
    assert.equal(dom.container.querySelector(`#${usernameErrId}`)?.getAttribute('role'), 'alert');
    assert.equal(dom.container.querySelector(`#${passwordErrId}`)?.getAttribute('role'), 'alert');
  } finally {
    await dom.unmount();
    globalThis.fetch = prevFetch;
  }
});
