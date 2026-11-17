import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { createDomEnvironment } from '../../scripts/dom-harness.mjs';

test('ReauthModal renders active user identity and accessible modal structure', async () => {
  const { default: ReauthModal } = await import('./ReauthModal.jsx');
  const dom = await createDomEnvironment();

  try {
    const user = {
      id: 1,
      username: 'admin',
      fullName: 'System Administrator',
      role: 'ADMIN',
    };

    await dom.render(
      React.createElement(ReauthModal, {
        user,
        reauthReason: 'Your session has timed out. Re-authenticate to continue.',
        reauthError: null,
        sessionState: 'reauth_required',
        onReauthenticate: async () => {},
        onSignOut: () => {},
      })
    );

    const dialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(dialog, 'Expected dialog element');
    assert.equal(dialog.getAttribute('aria-modal'), 'true');

    const textContent = dom.container.textContent;
    assert.ok(textContent.includes('Session Re-Authentication'));
    assert.ok(textContent.includes('System Administrator'));
    assert.ok(textContent.includes('@admin'));
    assert.ok(textContent.includes('ADMIN'));
    assert.ok(textContent.includes('Your session has timed out'));

    const passwordInput = dom.container.querySelector('input[type="password"]');
    assert.ok(passwordInput, 'Expected password input field');

    const reauthButton = dom.container.querySelector('button[type="submit"]');
    assert.ok(reauthButton, 'Expected submit button');
    assert.ok(reauthButton.textContent.includes('Re-Authenticate'));

    const signOutButton = Array.from(dom.container.querySelectorAll('button')).find((b) =>
      b.textContent.includes('Sign Out')
    );
    assert.ok(signOutButton, 'Expected sign out button');
  } finally {
    await dom.unmount();
  }
});

test('ReauthModal triggers onReauthenticate on submit and onSignOut on button click', async () => {
  const { default: ReauthModal } = await import('./ReauthModal.jsx');
  const dom = await createDomEnvironment();

  let submittedPassword = null;
  let signOutCalled = false;

  const onReauthenticate = async ({ password }) => {
    submittedPassword = password;
  };

  const onSignOut = () => {
    signOutCalled = true;
  };

  try {
    const user = {
      id: 2,
      username: 'supervisor',
      fullName: 'Plant Supervisor',
      role: 'PRODUCTION_SUPERVISOR',
    };

    await dom.render(
      React.createElement(ReauthModal, {
        user,
        reauthReason: 'Test expiry',
        reauthError: null,
        sessionState: 'reauth_required',
        onReauthenticate,
        onSignOut,
      })
    );

    const passwordInput = dom.container.querySelector('input[type="password"]');
    await dom.changeInput(passwordInput, 'SecretPass123');

    const form = dom.container.querySelector('form');
    await dom.submit(form);

    assert.equal(submittedPassword, 'SecretPass123');

    const signOutButton = Array.from(dom.container.querySelectorAll('button')).find((b) =>
      b.textContent.includes('Sign Out')
    );
    assert.ok(signOutButton);
    await dom.click(signOutButton);
    assert.equal(signOutCalled, true);
  } finally {
    await dom.unmount();
  }
});

test('ReauthModal displays error message when reauth fails', async () => {
  const { default: ReauthModal } = await import('./ReauthModal.jsx');
  const dom = await createDomEnvironment();

  try {
    const user = {
      id: 1,
      username: 'admin',
      fullName: 'Admin',
      role: 'ADMIN',
    };

    await dom.render(
      React.createElement(ReauthModal, {
        user,
        reauthReason: 'Expired',
        reauthError: 'Invalid password. Please try again.',
        sessionState: 'reauth_failed',
        onReauthenticate: async () => {},
        onSignOut: () => {},
      })
    );

    const alert = dom.container.querySelector('[role="alert"]');
    assert.ok(alert, 'Expected alert banner');
    assert.ok(alert.textContent.includes('Invalid password. Please try again.'));
  } finally {
    await dom.unmount();
  }
});
