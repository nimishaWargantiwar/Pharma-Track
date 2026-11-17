import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { createDomEnvironment, MiniEvent } from '../../scripts/dom-harness.mjs';
import { resetReauthState, setAuthToken } from '../services/apiClient.js';

function makeKeyEvent(key) {
  const evt = new MiniEvent('keydown', { bubbles: true, cancelable: true });
  evt.key = key;
  evt.code = key;
  return evt;
}

function makeAuthValue(role = 'ADMIN', userId = 1) {
  return {
    user: { id: userId, username: `user_${userId}`, fullName: `Test User ${userId}`, role, enabled: true },
    role,
    token: `test-copilot-token-${userId}`,
    actorDisplay: `Test User ${userId} (@user_${userId})`,
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

function jsonResponse(body, status = 200, url = 'http://localhost:8080/api/ai/conversations') {
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

/**
 * In-memory Mock Copilot Backend helper simulating persistent conversation APIs.
 */
function createMockCopilotBackend(initialConversations = [], customHandlers = {}) {
  let conversations = JSON.parse(JSON.stringify(initialConversations));
  const calls = [];
  let nextMsgId = 1000;

  const handleFetch = async (url, options = {}) => {
    const urlStr = String(url);
    const method = (options.method || 'GET').toUpperCase();
    let body = null;
    if (options.body) {
      try {
        body = typeof options.body === 'string' ? JSON.parse(options.body) : options.body;
      } catch {
        body = options.body;
      }
    }
    calls.push({ url: urlStr, method, body, headers: options.headers });

    if (customHandlers.beforeRequest) {
      const intercepted = await customHandlers.beforeRequest(urlStr, method, body, options);
      if (intercepted) return intercepted;
    }

    // 1. GET /api/ai/conversations (list)
    if (urlStr.match(/\/api\/ai\/conversations(\?.*)?$/) && method === 'GET') {
      const summaries = conversations.map((c) => ({
        id: c.id,
        title: c.title,
        activeModule: c.activeModule || 'orders',
        contextType: c.contextType,
        contextId: c.contextId,
        messageCount: c.messages ? c.messages.length : c.messageCount || 0,
        createdAt: c.createdAt || '2026-11-14T10:00:00Z',
        updatedAt: c.updatedAt || '2026-11-14T10:00:00Z',
      }));
      return jsonResponse(summaries, 200, urlStr);
    }

    // 2. POST /api/ai/conversations (create)
    if (urlStr.match(/\/api\/ai\/conversations(\?.*)?$/) && method === 'POST') {
      const newId = conversations.length > 0 ? Math.max(...conversations.map((c) => c.id)) + 1 : 1;
      const newConv = {
        id: newId,
        title: body?.title || 'New Inquiry',
        activeModule: body?.activeModule || 'orders',
        contextType: body?.contextType,
        contextId: body?.contextId,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        messages: [],
      };
      conversations.unshift(newConv);
      return jsonResponse(newConv, 201, urlStr);
    }

    // 3. GET /api/ai/conversations/:id (detail)
    const detailMatch = urlStr.match(/\/api\/ai\/conversations\/(\d+)(\?.*)?$/);
    if (detailMatch && method === 'GET') {
      const id = Number(detailMatch[1]);
      if (customHandlers.onDetail) {
        const customRes = await customHandlers.onDetail(id, urlStr);
        if (customRes) return customRes;
      }
      const conv = conversations.find((c) => c.id === id);
      if (!conv) {
        return jsonResponse({ status: 404, message: 'Conversation not found' }, 404, urlStr);
      }
      return jsonResponse(conv, 200, urlStr);
    }

    // 4. PATCH /api/ai/conversations/:id (rename)
    if (detailMatch && method === 'PATCH') {
      const id = Number(detailMatch[1]);
      const conv = conversations.find((c) => c.id === id);
      if (!conv) {
        return jsonResponse({ status: 404, message: 'Conversation not found' }, 404, urlStr);
      }
      conv.title = body?.title || conv.title;
      conv.updatedAt = new Date().toISOString();
      return jsonResponse(conv, 200, urlStr);
    }

    // 5. DELETE /api/ai/conversations/:id
    if (detailMatch && method === 'DELETE') {
      const id = Number(detailMatch[1]);
      conversations = conversations.filter((c) => c.id !== id);
      return jsonResponse({}, 204, urlStr);
    }

    // 6. POST /api/ai/conversations/:id/messages
    const msgMatch = urlStr.match(/\/api\/ai\/conversations\/(\d+)\/messages(\?.*)?$/);
    if (msgMatch && method === 'POST') {
      const id = Number(msgMatch[1]);
      if (customHandlers.onMessage) {
        const customRes = await customHandlers.onMessage(id, body, urlStr);
        if (customRes) return customRes;
      }
      const conv = conversations.find((c) => c.id === id);
      const userMsg = {
        id: ++nextMsgId,
        requestId: 500,
        role: 'USER',
        content: body?.query,
        createdAt: new Date().toISOString(),
      };
      const assistantMsg = {
        id: ++nextMsgId,
        requestId: 500,
        role: 'ASSISTANT',
        content: `Answer for: ${body?.query}`,
        model: 'gpt-6-luna',
        status: 'COMPLETED',
        sources: [],
        createdAt: new Date().toISOString(),
      };
      if (conv) {
        if (!conv.messages) conv.messages = [];
        conv.messages.push(userMsg, assistantMsg);
        conv.updatedAt = new Date().toISOString();
      }
      return jsonResponse(
        {
          id: assistantMsg.id,
          conversationId: id,
          requestId: 500,
          clientRequestId: body?.clientRequestId,
          userMessage: userMsg,
          assistantMessage: assistantMsg,
          role: 'ASSISTANT',
          content: assistantMsg.content,
          model: assistantMsg.model,
          status: assistantMsg.status,
          sources: assistantMsg.sources,
          createdAt: assistantMsg.createdAt,
        },
        200,
        urlStr
      );
    }

    return jsonResponse({}, 200, urlStr);
  };

  return {
    handleFetch,
    getConversations: () => conversations,
    getCalls: () => calls,
  };
}

/* =========================================================================================
 * SECTION 1: Baseline Component Verification Tests
 * ========================================================================================= */

test('AiSourceReferencePill renders order and batch citations and routes navigation correctly', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiSourceReferencePill } = await import('./AiSourceReferencePill.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');

  const navCalls = [];
  const navigateToTab = (tab, params) => navCalls.push({ tab, params });

  const dom = await createDomEnvironment();

  try {
    let pillNavEvent = null;
    await dom.render(
      React.createElement(
        NavigationProvider,
        {
          value: {
            activeTab: 'orders',
            routeParams: new URLSearchParams(),
            routeNonce: 0,
            navigateToTab,
          },
        },
        React.createElement(
          'div',
          null,
          React.createElement(AiSourceReferencePill, {
            source: {
              recordType: 'MANUFACTURING_ORDER',
              recordIdentifier: 'MO-2026-0001',
              title: 'MO-2026-0001 (Aspirin 500mg)',
              module: 'Manufacturing Orders',
              summary: 'Status: COMPLETED',
            },
            onNavigate: (src) => {
              pillNavEvent = src;
            },
          }),
          React.createElement(AiSourceReferencePill, {
            source: {
              recordType: 'BATCH',
              recordIdentifier: 'BAT-2026-0001-01',
              title: 'BAT-2026-0001-01 (COMPLETED)',
              module: 'Production Execution',
              summary: 'Status: COMPLETED | QA: RELEASED',
            },
          })
        )
      )
    );
    await dom.flush();

    const buttons = dom.container.querySelectorAll('button');
    assert.equal(buttons.length, 2);

    assert.match(buttons[0].textContent, /Order:\s*MO-2026-0001/);
    assert.doesNotMatch(buttons[0].textContent, /^ORDERMO-2026-0001$/);

    assert.match(buttons[1].textContent, /Batch:\s*BAT-2026-0001-01/);
    assert.doesNotMatch(buttons[1].textContent, /^BATCHBAT-2026-0001-01$/);

    await dom.click(buttons[0]);
    await dom.flush();

    assert.equal(navCalls.length, 1);
    assert.deepEqual(navCalls[0], { tab: 'orders', params: { orderNumber: 'MO-2026-0001' } });
    assert.equal(pillNavEvent?.recordIdentifier, 'MO-2026-0001');

    await dom.click(buttons[1]);
    await dom.flush();

    assert.equal(navCalls.length, 2);
    assert.deepEqual(navCalls[1], { tab: 'traceability', params: { batchNumber: 'BAT-2026-0001-01' } });
  } finally {
    await dom.unmount?.();
  }
});

test('AiCopilotDrawer displays initial welcome state, suggested questions, and disclaimer', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => jsonResponse([], 200, url);

  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(AiCopilotDrawer, {
        isOpen: true,
        onClose: () => {},
      })
    );
    await dom.flush();

    const drawer = dom.container.querySelector('[data-testid="ai-copilot-drawer"]');
    assert.ok(drawer);

    const text = drawer.textContent;
    assert.ok(text.includes('PharmaTrack AI Copilot'));
    assert.ok(text.includes('Advisory'));
    assert.ok(text.includes('Grounded in verified MES database records'));
    assert.ok(text.includes('Suggested Questions'));
    assert.ok(text.includes('What is the status of manufacturing order MO-2026-0001?'));
    assert.ok(text.includes('Answers are grounded in retrieved PharmaTrack MES records. AI responses are advisory and must be independently verified.'));
    assert.equal(text.includes('GxP compliant'), false, 'Should not contain unsupported GxP compliance claims');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('AiCopilotDrawer displays active order context and allows clearing it', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => jsonResponse([], 200, url);

  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const dom = await createDomEnvironment();

  let contextCleared = false;

  try {
    await dom.render(
      React.createElement(AiCopilotDrawer, {
        isOpen: true,
        onClose: () => {},
        activeContext: {
          activeModule: 'orders',
          contextType: 'MANUFACTURING_ORDER',
          contextId: 'MO-2026-0005',
          title: 'Aspirin 500mg Tablets',
        },
        onClearContext: () => {
          contextCleared = true;
        },
      })
    );
    await dom.flush();

    const contextBadge = dom.container.querySelector('[data-testid="active-context-badge"]');
    assert.ok(contextBadge);
    assert.ok(contextBadge.textContent.includes('MO-2026-0005'));

    const text = dom.container.textContent;
    assert.ok(text.includes('What is the current status and batch progress?'));

    const clearBtn = dom.container.querySelector('button[aria-label="Clear active order context"]');
    assert.ok(clearBtn);
    await dom.click(clearBtn);
    await dom.flush();
    assert.equal(contextCleared, true);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('AiCopilotDrawer submits query and renders assistant response with source references', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');

  const prevFetch = globalThis.fetch;
  const capturedRequests = [];

  globalThis.fetch = async (url, options = {}) => {
    capturedRequests.push({ url: String(url), options });
    const urlStr = String(url);
    const method = (options.method || 'GET').toUpperCase();

    if (urlStr.includes('/api/ai/conversations') && method === 'GET') {
      return jsonResponse([], 200, urlStr);
    }
    if (urlStr.endsWith('/api/ai/conversations') && method === 'POST') {
      return jsonResponse({ id: 101, title: 'Inquiry', activeModule: 'orders' }, 201, urlStr);
    }
    if (urlStr.includes('/messages') && method === 'POST') {
      const body = options.body ? JSON.parse(options.body) : {};
      return jsonResponse(
        {
          id: 201,
          conversationId: 101,
          requestId: 301,
          clientRequestId: body.clientRequestId,
          role: 'ASSISTANT',
          content: 'Manufacturing order MO-2026-0001 is COMPLETED with 1 batch.',
          contextType: 'MANUFACTURING_ORDER',
          contextIdentifier: 'MO-2026-0001',
          sources: [
            {
              recordType: 'MANUFACTURING_ORDER',
              recordIdentifier: 'MO-2026-0001',
              title: 'MO-2026-0001 (Aspirin 500mg)',
              module: 'Manufacturing Orders',
              summary: 'Status: COMPLETED',
            },
          ],
          model: 'gpt-6-luna',
          status: 'COMPLETED',
          timestamp: '2026-11-13T17:55:00+05:30',
          disclaimer: 'Advisory guidance only.',
        },
        200,
        urlStr
      );
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        NavigationProvider,
        {
          value: {
            activeTab: 'orders',
            routeParams: new URLSearchParams(),
            routeNonce: 0,
            navigateToTab: () => {},
          },
        },
        React.createElement(AiCopilotDrawer, {
          isOpen: true,
          onClose: () => {},
        })
      )
    );
    await dom.flush();

    const textarea = dom.container.querySelector('[data-testid="copilot-query-input"]');
    const submitBtn = dom.container.querySelector('[data-testid="copilot-submit-button"]');
    assert.ok(textarea);
    assert.ok(submitBtn);

    await dom.changeInput(textarea, 'What is the status of MO-2026-0001?');
    await dom.flush();

    const form = dom.container.querySelector('form');
    assert.ok(form);
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    const msgReq = capturedRequests.find((r) => r.url.includes('/messages'));
    assert.ok(msgReq, 'Messages endpoint must be called');
    const body = JSON.parse(msgReq.options.body);
    assert.equal(body.query, 'What is the status of MO-2026-0001?');
    assert.ok(body.clientRequestId.startsWith('req-'), 'clientRequestId must be provided');

    const userMsg = dom.container.querySelector('[data-testid="message-user"]');
    const assistantMsg = dom.container.querySelector('[data-testid="message-assistant"]');
    assert.ok(userMsg);
    assert.ok(assistantMsg);
    assert.ok(userMsg.textContent.includes('What is the status of MO-2026-0001?'));
    assert.ok(assistantMsg.textContent.includes('MO-2026-0001 is COMPLETED with 1 batch'));
    assert.ok(assistantMsg.textContent.includes('Verified PharmaTrack Records (1)'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('AiCopilotDrawer handles 503 AI disabled error with administrative explanation', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    const urlStr = String(url);
    const method = (options.method || 'GET').toUpperCase();
    if (urlStr.includes('/api/ai/conversations') && method === 'GET') {
      return jsonResponse([], 200, urlStr);
    }
    return jsonResponse(
      {
        status: 503,
        error: 'Service Unavailable',
        message: 'PharmaTrack AI Copilot is currently unavailable.',
        timestamp: '2026-11-13T17:55:00+05:30',
      },
      503,
      urlStr
    );
  };

  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(AiCopilotDrawer, {
        isOpen: true,
        onClose: () => {},
      })
    );
    await dom.flush();

    const textarea = dom.container.querySelector('[data-testid="copilot-query-input"]');
    await dom.changeInput(textarea, 'Status of MO-2026-0001?');
    await dom.flush();

    const form = dom.container.querySelector('form');
    assert.ok(form);
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    const assistantMsg = dom.container.querySelector('[data-testid="message-assistant"]');
    assert.ok(assistantMsg);
    assert.ok(assistantMsg.textContent.includes('currently disabled in system configuration'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('App header renders global AI Copilot toggle and opens drawer', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: App } = await import('../App.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    return jsonResponse([], 200, url);
  };

  const dom = await createDomEnvironment('http://localhost:5173/#/products');

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(App)
      )
    );
    await dom.flush();

    const copilotToggle = dom.container.querySelector('[data-testid="global-ai-copilot-toggle"]');
    assert.ok(copilotToggle);
    assert.equal(copilotToggle.getAttribute('aria-expanded'), 'false');

    await dom.click(copilotToggle);
    await dom.flush();

    assert.equal(copilotToggle.getAttribute('aria-expanded'), 'true');
    const drawer = dom.container.querySelector('[data-testid="ai-copilot-drawer"]');
    assert.ok(drawer);

    const closeBtn = dom.container.querySelector('[data-testid="close-copilot-drawer"]');
    assert.ok(closeBtn);
    await dom.click(closeBtn);
    await dom.flush();

    const closedDrawer = dom.container.querySelector('[data-testid="ai-copilot-drawer"]');
    assert.equal(closedDrawer, null);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('extractVerifiedOrderContext extracts valid order and ignores invalid sources', async () => {
  const { extractVerifiedOrderContext } = await import('./AiCopilotDrawer.jsx');

  const valid = extractVerifiedOrderContext({
    sources: [
      { recordType: 'BATCH', recordIdentifier: 'BAT-01' },
      { recordType: 'MANUFACTURING_ORDER', recordIdentifier: 'mo-2026-0001', title: 'MO-2026-0001 (Aspirin)' },
    ],
  });
  assert.deepEqual(valid, {
    activeModule: 'orders',
    contextType: 'MANUFACTURING_ORDER',
    contextId: 'MO-2026-0001',
    title: 'MO-2026-0001 (Aspirin)',
  });

  const noOrder = extractVerifiedOrderContext({
    sources: [{ recordType: 'BATCH', recordIdentifier: 'BAT-01' }],
  });
  assert.equal(noOrder, null);

  const invalidId = extractVerifiedOrderContext({
    sources: [{ recordType: 'MANUFACTURING_ORDER', recordIdentifier: 'INVALID-ORDER-123' }],
  });
  assert.equal(invalidId, null);

  assert.equal(extractVerifiedOrderContext(null), null);
  assert.equal(extractVerifiedOrderContext({}), null);
  assert.equal(extractVerifiedOrderContext({ sources: [] }), null);
});

test('AiCopilotDrawer establishes context on first query, reuses on follow-up, and switches on new order', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');

  const prevFetch = globalThis.fetch;
  const capturedMessages = [];

  globalThis.fetch = async (url, options = {}) => {
    const urlStr = String(url);
    const method = (options.method || 'GET').toUpperCase();

    if (urlStr.includes('/api/ai/conversations') && method === 'GET') {
      return jsonResponse([], 200, urlStr);
    }
    if (urlStr.endsWith('/api/ai/conversations') && method === 'POST') {
      return jsonResponse({ id: 101, title: 'Order Inquiry' }, 201, urlStr);
    }
    if (urlStr.includes('/messages') && method === 'POST') {
      const body = options.body ? JSON.parse(options.body) : {};
      capturedMessages.push({ url: urlStr, body });

      if (body.query.includes('MO-2026-0001')) {
        return jsonResponse(
          {
            id: 201,
            conversationId: 101,
            content: 'MO-2026-0001 has 2 batches in progress.',
            contextType: 'MANUFACTURING_ORDER',
            contextIdentifier: 'MO-2026-0001',
            sources: [
              {
                recordType: 'MANUFACTURING_ORDER',
                recordIdentifier: 'MO-2026-0001',
                title: 'MO-2026-0001 (Ibuprofen 200mg)',
                module: 'Manufacturing Orders',
                summary: 'Status: IN_PROGRESS',
              },
            ],
            model: 'gpt-6-luna',
            status: 'COMPLETED',
          },
          200,
          urlStr
        );
      }

      if (body.query.includes('MO-2026-0002')) {
        return jsonResponse(
          {
            id: 202,
            conversationId: 101,
            content: 'MO-2026-0002 is COMPLETED.',
            contextType: 'MANUFACTURING_ORDER',
            contextIdentifier: 'MO-2026-0002',
            sources: [
              {
                recordType: 'MANUFACTURING_ORDER',
                recordIdentifier: 'MO-2026-0002',
                title: 'MO-2026-0002 (Paracetamol 500mg)',
                module: 'Manufacturing Orders',
                summary: 'Status: COMPLETED',
              },
            ],
            model: 'gpt-6-luna',
            status: 'COMPLETED',
          },
          200,
          urlStr
        );
      }

      return jsonResponse(
        {
          id: 203,
          conversationId: 101,
          content: 'For the active order, planned quantity is 1000 kg and actual yield is 990 kg (99.0%).',
          contextType: 'MANUFACTURING_ORDER',
          contextIdentifier: body.contextId,
          sources: [
            {
              recordType: 'MANUFACTURING_ORDER',
              recordIdentifier: body.contextId,
              title: `${body.contextId} (Order)`,
              module: 'Manufacturing Orders',
              summary: 'Planned vs Actual',
            },
          ],
          model: 'gpt-6-luna',
          status: 'COMPLETED',
        },
        200,
        urlStr
      );
    }

    return jsonResponse({});
  };

  const dom = await createDomEnvironment();

  try {
    let contextChangedVal = null;
    await dom.render(
      React.createElement(
        NavigationProvider,
        {
          value: {
            activeTab: 'orders',
            routeParams: new URLSearchParams(),
            routeNonce: 0,
            navigateToTab: () => {},
          },
        },
        React.createElement(AiCopilotDrawer, {
          isOpen: true,
          onClose: () => {},
          onContextChange: (ctx) => {
            contextChangedVal = ctx;
          },
        })
      )
    );
    await dom.flush();

    const textarea = dom.container.querySelector('[data-testid="copilot-query-input"]');
    const form = dom.container.querySelector('form');
    assert.ok(textarea);
    assert.ok(form);

    assert.ok(dom.container.textContent.includes('Global (specify order number'));

    // Step 1: Submit first query mentioning MO-2026-0001
    await dom.changeInput(textarea, 'What is the status of MO-2026-0001?');
    await dom.flush();
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    assert.equal(capturedMessages.length, 1);
    assert.equal(capturedMessages[0].body.query, 'What is the status of MO-2026-0001?');
    assert.equal(capturedMessages[0].body.contextId, undefined);

    const badge1 = dom.container.querySelector('[data-testid="active-context-badge"]');
    assert.ok(badge1);
    assert.ok(badge1.textContent.includes('MO-2026-0001'));
    assert.equal(contextChangedVal?.contextId, 'MO-2026-0001');

    // Step 2: Follow-up question without order number reuses active context
    await dom.changeInput(textarea, 'Compare planned quantity with actual yield of both batches');
    await dom.flush();
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    assert.equal(capturedMessages.length, 2);
    assert.equal(capturedMessages[1].body.query, 'Compare planned quantity with actual yield of both batches');
    assert.equal(capturedMessages[1].body.contextId, 'MO-2026-0001');
    assert.equal(capturedMessages[1].body.contextType, 'MANUFACTURING_ORDER');

    // Step 3: Explicit new order in query switches context to MO-2026-0002
    await dom.changeInput(textarea, 'What about manufacturing order MO-2026-0002?');
    await dom.flush();
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    assert.equal(capturedMessages.length, 3);
    assert.equal(capturedMessages[2].body.query, 'What about manufacturing order MO-2026-0002?');

    const badge2 = dom.container.querySelector('[data-testid="active-context-badge"]');
    assert.ok(badge2);
    assert.ok(badge2.textContent.includes('MO-2026-0002'));
    assert.equal(contextChangedVal?.contextId, 'MO-2026-0002');

    // Step 4: Clear active context
    const clearBtn = dom.container.querySelector('button[aria-label="Clear active order context"]');
    assert.ok(clearBtn);
    await dom.click(clearBtn);
    await dom.flush();

    assert.equal(dom.container.querySelector('[data-testid="active-context-badge"]'), null);
    assert.ok(dom.container.textContent.includes('Global (specify order number'));
    assert.equal(contextChangedVal, null);

    // Step 5: Next query without order number has no contextId
    await dom.changeInput(textarea, 'How many batches?');
    await dom.flush();
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    assert.equal(capturedMessages.length, 4);
    assert.equal(capturedMessages[3].body.query, 'How many batches?');
    assert.equal(capturedMessages[3].body.contextId, undefined);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Defect 3 Fix: AiCopilotDrawer renders multiple verified source pills in an accessible separated list without concatenation', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const navCalls = [];
  const navigateToTab = (tab, params) => navCalls.push({ tab, params });

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    const urlStr = String(url);
    const method = (options.method || 'GET').toUpperCase();

    if (urlStr.includes('/api/ai/conversations') && method === 'GET') {
      return jsonResponse([], 200, urlStr);
    }
    if (urlStr.endsWith('/api/ai/conversations') && method === 'POST') {
      return jsonResponse({ id: 101, title: 'Inquiry' }, 201, urlStr);
    }
    if (urlStr.includes('/messages') && method === 'POST') {
      return jsonResponse(
        {
          id: 201,
          conversationId: 101,
          content: 'Manufacturing order **MO-2026-0001** has two completed batches awaiting QA disposition.',
          contextType: 'MANUFACTURING_ORDER',
          contextIdentifier: 'MO-2026-0001',
          sources: [
            {
              recordType: 'MANUFACTURING_ORDER',
              recordIdentifier: 'MO-2026-0001',
              title: 'MO-2026-0001 (Aspirin 500mg)',
              module: 'Manufacturing Orders',
              summary: 'Status: COMPLETED | Product: PROD-ASP-500',
            },
            {
              recordType: 'BATCH',
              recordIdentifier: 'BAT-2026-0001-01',
              title: 'BAT-2026-0001-01 (COMPLETED)',
              module: 'Production Execution',
              summary: 'Status: COMPLETED | QA: PENDING_QA',
            },
            {
              recordType: 'BATCH',
              recordIdentifier: 'BAT-2026-0001-02',
              title: 'BAT-2026-0001-02 (COMPLETED)',
              module: 'Production Execution',
              summary: 'Status: COMPLETED | QA: PENDING_QA',
            },
          ],
          disclaimer: 'Advisory Guidance Only.',
          model: 'openai/gpt-oss-20b',
          status: 'COMPLETED',
        },
        200,
        urlStr
      );
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('PRODUCTION_SUPERVISOR') },
        React.createElement(
          NavigationProvider,
          {
            value: {
              activeTab: 'orders',
              routeParams: new URLSearchParams(),
              routeNonce: 0,
              navigateToTab,
            },
          },
          React.createElement(AiCopilotDrawer, {
            isOpen: true,
            onClose: () => {},
          })
        )
      )
    );
    await dom.flush();

    const textarea = dom.container.querySelector('textarea');
    const form = dom.container.querySelector('form');
    await dom.changeInput(textarea, 'What is the status of MO-2026-0001?');
    await dom.flush();
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    const sourcesContainer = dom.container.querySelector('[data-testid="verified-sources-list"]');
    assert.ok(sourcesContainer, 'Sources list container should be rendered');
    assert.equal(sourcesContainer.getAttribute('role'), 'list');

    const listItems = sourcesContainer.querySelectorAll('[role="listitem"]');
    assert.equal(listItems.length, 3, 'Should render 3 distinct list item wrappers');

    const pills = sourcesContainer.querySelectorAll('button');
    assert.equal(pills.length, 3);

    const pill1Text = pills[0].textContent;
    const pill2Text = pills[1].textContent;
    const pill3Text = pills[2].textContent;

    assert.match(pill1Text, /Order:\s*MO-2026-0001/);
    assert.match(pill2Text, /Batch:\s*BAT-2026-0001-01/);
    assert.match(pill3Text, /Batch:\s*BAT-2026-0001-02/);

    await dom.click(pills[2]);
    await dom.flush();

    assert.equal(navCalls.length, 1);
    assert.deepEqual(navCalls[0], {
      tab: 'traceability',
      params: { batchNumber: 'BAT-2026-0001-02' },
    });
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('ORDER and BATCH citation clicks invoke navigation and close the Copilot drawer without clearing messages', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const navCalls = [];
  const navigateToTab = (tab, params) => navCalls.push({ tab, params });
  let closedCount = 0;
  const onClose = () => {
    closedCount += 1;
  };
  let navigatedSource = null;
  const onNavigateSource = (src) => {
    navigatedSource = src;
  };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    const urlStr = String(url);
    const method = (options.method || 'GET').toUpperCase();

    if (urlStr.includes('/api/ai/conversations') && method === 'GET') {
      return jsonResponse([], 200, urlStr);
    }
    if (urlStr.endsWith('/api/ai/conversations') && method === 'POST') {
      return jsonResponse({ id: 101, title: 'Inquiry' }, 201, urlStr);
    }
    if (urlStr.includes('/messages') && method === 'POST') {
      return jsonResponse(
        {
          id: 201,
          conversationId: 101,
          content: 'Summary for MO-2026-0001',
          model: 'gpt-6-luna',
          status: 'COMPLETED',
          contextType: 'MANUFACTURING_ORDER',
          contextIdentifier: 'MO-2026-0001',
          sources: [
            {
              recordType: 'MANUFACTURING_ORDER',
              recordIdentifier: 'MO-2026-0001',
              title: 'MO-2026-0001 (Amoxicillin)',
              module: 'Manufacturing Orders',
              summary: 'Status: COMPLETED',
            },
            {
              recordType: 'BATCH',
              recordIdentifier: 'BAT-2026-0001-01',
              title: 'BAT-2026-0001-01 (COMPLETED)',
              module: 'Production Execution',
              summary: 'Status: COMPLETED | QA: PENDING_QA',
            },
          ],
        },
        200,
        urlStr
      );
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(
          NavigationProvider,
          {
            value: {
              activeTab: 'orders',
              routeParams: new URLSearchParams(),
              routeNonce: 0,
              navigateToTab,
            },
          },
          React.createElement(AiCopilotDrawer, {
            isOpen: true,
            onClose,
            onNavigateSource,
          })
        )
      )
    );
    await dom.flush();

    const textarea = dom.container.querySelector('textarea');
    const form = dom.container.querySelector('form');
    await dom.changeInput(textarea, 'Summarize MO-2026-0001');
    await dom.flush();
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    const sourcesContainer = dom.container.querySelector('[data-testid="verified-sources-list"]');
    assert.ok(sourcesContainer, 'Sources list container should be rendered');
    const pills = sourcesContainer.querySelectorAll('button');
    assert.equal(pills.length, 2, 'Should render 2 source pills');

    // 1. Click ORDER citation pill
    await dom.click(pills[0]);
    await dom.flush();

    assert.equal(closedCount, 1, 'Clicking ORDER pill must close the drawer');
    assert.equal(navCalls.length, 1);
    assert.deepEqual(navCalls[0], {
      tab: 'orders',
      params: { orderNumber: 'MO-2026-0001' },
    });
    assert.equal(navigatedSource?.recordIdentifier, 'MO-2026-0001');

    // 2. Click BATCH citation pill
    await dom.click(pills[1]);
    await dom.flush();

    assert.equal(closedCount, 2, 'Clicking BATCH pill must close the drawer');
    assert.equal(navCalls.length, 2);
    assert.deepEqual(navCalls[1], {
      tab: 'traceability',
      params: { batchNumber: 'BAT-2026-0001-01' },
    });
    assert.equal(navigatedSource?.recordIdentifier, 'BAT-2026-0001-01');

    assert.ok(dom.container.textContent.includes('Summary for MO-2026-0001'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Copilot conversation messages remain preserved across drawer close and reopen', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend();
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;

  const dom = await createDomEnvironment();

  try {
    function TestWrapper() {
      const [isOpen, setIsOpen] = React.useState(true);
      return React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(
          NavigationProvider,
          {
            value: {
              activeTab: 'orders',
              routeParams: new URLSearchParams(),
              routeNonce: 0,
              navigateToTab: () => {},
            },
          },
          React.createElement('button', {
            'data-testid': 'toggle-drawer',
            onClick: () => setIsOpen((prev) => !prev),
          }, 'Toggle'),
          React.createElement(AiCopilotDrawer, {
            isOpen,
            onClose: () => setIsOpen(false),
          })
        )
      );
    }

    await dom.render(React.createElement(TestWrapper));
    await dom.flush();

    const textarea = dom.container.querySelector('textarea');
    const form = dom.container.querySelector('form');
    await dom.changeInput(textarea, 'Query to preserve');
    await dom.flush();
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Answer for: Query to preserve'));

    // Toggle drawer closed
    const toggleBtn = dom.container.querySelector('[data-testid="toggle-drawer"]');
    await dom.click(toggleBtn);
    await dom.flush();

    assert.strictEqual(dom.container.querySelector('[data-testid="ai-copilot-drawer"]'), null);

    // Toggle drawer open again
    await dom.click(toggleBtn);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    assert.ok(dom.container.querySelector('[data-testid="ai-copilot-drawer"]'));
    assert.ok(dom.container.textContent.includes('Answer for: Query to preserve'));
    assert.ok(dom.container.textContent.includes('Query to preserve'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

/* =========================================================================================
 * SECTION 2: Phase 3 Step 4 Persistent Conversation Comprehensive Lifecycle Tests
 * ========================================================================================= */

// Scenario 1: Loading conversation list
test('Scenario 1: AiCopilotDrawer loads conversation list and displays summaries', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    {
      id: 10,
      title: 'Batch Dispositions Inquiry',
      activeModule: 'orders',
      contextType: 'MANUFACTURING_ORDER',
      contextId: 'MO-2026-0010',
      messageCount: 4,
      createdAt: '2026-11-14T09:00:00Z',
      updatedAt: '2026-11-14T09:30:00Z',
      messages: [
        { id: 1, role: 'USER', content: 'What is MO-2026-0010?' },
        { id: 2, role: 'ASSISTANT', content: 'Details for MO-2026-0010.' },
      ],
    },
    {
      id: 11,
      title: 'Yield Discrepancy Analysis',
      activeModule: 'orders',
      messageCount: 2,
      createdAt: '2026-11-14T08:00:00Z',
      updatedAt: '2026-11-14T08:15:00Z',
      messages: [],
    },
  ]);

  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    // Toggle to history view
    const historyToggle = dom.container.querySelector('[data-testid="copilot-history-toggle"]');
    assert.ok(historyToggle, 'History toggle button should exist');
    await dom.click(historyToggle);
    await dom.flush();

    const listContainer = dom.container.querySelector('[data-testid="copilot-conversation-list"]');
    assert.ok(listContainer, 'Conversation list should be rendered');
    assert.ok(listContainer.textContent.includes('Batch Dispositions Inquiry'));
    assert.ok(listContainer.textContent.includes('Yield Discrepancy Analysis'));
    assert.ok(listContainer.textContent.includes('MO-2026-0010'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 2: Empty conversation state
test('Scenario 2: AiCopilotDrawer renders empty state when user has zero conversations', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([]);
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    // In chat view, empty messages renders the welcome card and suggested questions
    assert.ok(dom.container.textContent.includes('Manufacturing Order Intelligence'));
    assert.ok(dom.container.textContent.includes('Suggested Questions'));

    // Switch to history view to verify empty list state
    const historyToggle = dom.container.querySelector('[data-testid="copilot-history-toggle"]');
    await dom.click(historyToggle);
    await dom.flush();

    assert.ok(dom.container.textContent.includes('No conversations yet'));
    assert.ok(dom.container.textContent.includes('Start a Chat'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 3: Creating a conversation via New Chat button and duplicate click guarding
test('Scenario 3: New Chat button creates conversation and guards against duplicate clicks', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  let resolveCreation = null;
  let createAttempts = 0;
  const backend = createMockCopilotBackend([], {
    beforeRequest: async (urlStr, method) => {
      if (urlStr.endsWith('/api/ai/conversations') && method === 'POST') {
        createAttempts += 1;
        await new Promise((r) => {
          resolveCreation = r;
        });
      }
      return null;
    },
  });
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    // Toggle history view to access New Chat button
    const historyToggle = dom.container.querySelector('[data-testid="copilot-history-toggle"]');
    await dom.click(historyToggle);
    await dom.flush();

    const newChatBtn = dom.container.querySelector('[data-testid="copilot-new-chat-button"]');
    assert.ok(newChatBtn, 'New Chat button must exist');

    // First click initiates creation and is held pending by resolveCreation
    await dom.click(newChatBtn);
    await dom.flush();

    // Second click occurs while isCreatingRef.current is true
    await dom.click(newChatBtn);
    await dom.flush();

    // Resolve the first creation request
    if (resolveCreation) {
      resolveCreation();
    }
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.equal(createAttempts, 1, 'Only one backend create request should be dispatched');
    const createdConvs = backend.getConversations();
    assert.equal(createdConvs.length, 1, 'Only one conversation should be created');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 4: Switching conversations and restoring messages
test('Scenario 4: Switching conversations loads transcript of selected thread', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    {
      id: 201,
      title: 'Thread A',
      messages: [
        { id: 1, role: 'USER', content: 'Message in Thread A' },
        { id: 2, role: 'ASSISTANT', content: 'Response in Thread A' },
      ],
    },
    {
      id: 202,
      title: 'Thread B',
      messages: [
        { id: 3, role: 'USER', content: 'Message in Thread B' },
        { id: 4, role: 'ASSISTANT', content: 'Response in Thread B' },
      ],
    },
  ]);

  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    // By default, Thread A (first) is active
    assert.ok(dom.container.textContent.includes('Response in Thread A'));

    // Switch to history view
    await dom.click(dom.container.querySelector('[data-testid="copilot-history-toggle"]'));
    await dom.flush();

    // Click Thread B
    const itemB = dom.container.querySelector('[data-testid="copilot-conversation-item-202"]');
    assert.ok(itemB, 'Conversation item 202 should exist');
    await dom.click(itemB);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    // Drawer should have navigated back to chat view with Thread B messages
    assert.ok(dom.container.textContent.includes('Response in Thread B'));
    assert.strictEqual(dom.container.textContent.includes('Response in Thread A'), false);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 5: Refresh / re-mount restoration from sessionStorage
test('Scenario 5: Active conversation is restored from sessionStorage upon re-mount', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token-1');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    {
      id: 301,
      title: 'First Thread',
      messages: [{ id: 1, role: 'USER', content: 'Msg 1' }],
    },
    {
      id: 302,
      title: 'Restored Active Thread',
      messages: [{ id: 2, role: 'ASSISTANT', content: 'Restored from Session Storage Content' }],
    },
  ]);

  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    dom.sessionStorage.setItem('pharmatrack_copilot_active_conv_1', '302');

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', 1) },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Restored from Session Storage Content'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 6: Submitting and persisting a message with clientRequestId
test('Scenario 6: Message submission passes unique clientRequestId and persists to transcript', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    { id: 401, title: 'Inquiry Thread', messages: [] },
  ]);
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    const textarea = dom.container.querySelector('textarea');
    assert.ok(textarea, 'Query textarea must exist');
    const form = dom.container.querySelector('form');
    assert.ok(form, 'Query form must exist');

    await dom.changeInput(textarea, 'How many deviations for MO-2026-0001?');
    await dom.flush();
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    const calls = backend.getCalls();
    const msgPost = calls.find((c) => c.url.includes('/messages') && c.method === 'POST');
    assert.ok(msgPost, 'Message submission POST must be dispatched');
    assert.ok(msgPost.body.clientRequestId, 'clientRequestId must be present');
    assert.match(msgPost.body.clientRequestId, /^req-[a-z0-9]+-[a-z0-9]+$/);
    assert.ok(msgPost.body.clientRequestId.length < 64);
    assert.equal(msgPost.body.query, 'How many deviations for MO-2026-0001?');

    assert.ok(dom.container.textContent.includes('Answer for: How many deviations for MO-2026-0001?'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 7: Preventing duplicate submission on rapid clicks
test('Scenario 7: Prevents duplicate message submission on rapid clicks', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    { id: 501, title: 'Rapid Click Thread', messages: [] },
  ]);
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    const textarea = dom.container.querySelector('textarea');
    assert.ok(textarea, 'Query textarea must exist');
    const form = dom.container.querySelector('form');
    assert.ok(form, 'Query form must exist');

    await dom.changeInput(textarea, 'Query to submit rapidly');
    await dom.flush();

    await dom.submit(form);
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    const calls = backend.getCalls().filter((c) => c.url.includes('/messages'));
    assert.equal(calls.length, 1, 'Only one submission should be dispatched');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 8: Pending assistant response handling (status: PENDING)
test('Scenario 8: Handles PENDING assistant response with in-progress placeholder styling', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    {
      id: 601,
      title: 'Pending Thread',
      messages: [
        {
          id: 99,
          role: 'ASSISTANT',
          status: 'PENDING',
          content: 'Inquiry is currently in progress. Please check back shortly.',
        },
      ],
    },
  ]);

  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Inquiry is currently in progress. Please check back shortly.'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 9: Failed request handling (status: FAILED)
test('Scenario 9: Handles FAILED request status and renders error message with retry trigger', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend(
    [{ id: 701, title: 'Failing Thread', messages: [] }],
    {
      onMessage: () =>
        jsonResponse(
          {
            id: 799,
            role: 'ASSISTANT',
            status: 'FAILED',
            content: 'LLM reasoning provider encountered an unrecoverable internal error.',
          },
          200
        ),
    }
  );

  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    const textarea = dom.container.querySelector('textarea');
    const form = dom.container.querySelector('form');
    await dom.changeInput(textarea, 'Query leading to failure');
    await dom.flush();
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.ok(dom.container.textContent.includes('unrecoverable internal error'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 10: Retrying with a new request ID after a failed request
test('Scenario 10: Retrying failed inquiry issues a new request with fresh clientRequestId', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  let callCount = 0;
  const capturedClientRequestIds = [];

  const backend = createMockCopilotBackend(
    [{ id: 801, title: 'Retry Thread', messages: [] }],
    {
      onMessage: (id, body) => {
        callCount += 1;
        capturedClientRequestIds.push(body.clientRequestId);
        if (callCount === 1) {
          return jsonResponse({ status: 504, message: 'Gateway Timeout while querying LLM' }, 504);
        }
        return jsonResponse({
          id: 802,
          role: 'ASSISTANT',
          content: 'Retry succeeded after timeout',
          status: 'COMPLETED',
        });
      },
    }
  );

  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    const textarea = dom.container.querySelector('textarea');
    const form = dom.container.querySelector('form');
    await dom.changeInput(textarea, 'Query to retry');
    await dom.flush();
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    const retryInList = Array.from(dom.container.querySelectorAll('button')).find((b) =>
      b.textContent.includes('Retry')
    );
    assert.ok(retryInList, 'Retry button must be visible in error banner');

    await dom.click(retryInList);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.equal(capturedClientRequestIds.length, 2);
    assert.notEqual(
      capturedClientRequestIds[0],
      capturedClientRequestIds[1],
      'Each submission attempt must generate a new clientRequestId'
    );
    assert.ok(dom.container.textContent.includes('Retry succeeded after timeout'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 11: Renaming a conversation (PATCH)
test('Scenario 11: Renaming conversation invokes PATCH and updates conversation title', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    { id: 901, title: 'Original Name', messages: [] },
  ]);
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    const renameBtn = dom.container.querySelector('[data-testid="copilot-rename-button"]');
    assert.ok(renameBtn, 'Rename button should exist in active conversation header');
    await dom.click(renameBtn);
    await dom.flush();

    const renameInput = dom.container.querySelector('[data-testid="rename-input-901"]');
    assert.ok(renameInput, 'Rename input field should appear');
    await dom.changeInput(renameInput, 'Updated Custom Name');
    await dom.flush();

    const saveBtn = dom.container.querySelector('[data-testid="save-rename-901"]');
    assert.ok(saveBtn, 'Save rename button should exist');
    await dom.click(saveBtn);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    const patchCall = backend.getCalls().find((c) => c.method === 'PATCH');
    assert.ok(patchCall, 'PATCH endpoint must be called');
    assert.equal(patchCall.body.title, 'Updated Custom Name');
    assert.ok(dom.container.textContent.includes('Updated Custom Name'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 12: Deleting a conversation with confirmation (DELETE)
test('Scenario 12: Deleting conversation requires confirmation and calls DELETE endpoint', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    { id: 1001, title: 'Thread To Delete', messages: [] },
  ]);
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    const deleteBtn = dom.container.querySelector('[data-testid="copilot-delete-button"]');
    assert.ok(deleteBtn, 'Delete button should exist');
    await dom.click(deleteBtn);
    await dom.flush();

    const confirmBtn = dom.container.querySelector('[data-testid="confirm-delete-conv-1001"]');
    assert.ok(confirmBtn, 'Confirm delete button should appear');
    await dom.click(confirmBtn);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    const deleteCall = backend.getCalls().find((c) => c.method === 'DELETE');
    assert.ok(deleteCall, 'DELETE endpoint must be called');
    assert.equal(backend.getConversations().length, 0);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 13: Deleting currently selected conversation falls back to next available
test('Scenario 13: Deleting selected conversation automatically selects next available thread', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    { id: 1101, title: 'First Conversation', messages: [{ id: 1, role: 'USER', content: 'Message 1' }] },
    { id: 1102, title: 'Second Conversation', messages: [{ id: 2, role: 'USER', content: 'Message 2' }] },
  ]);
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.ok(dom.container.textContent.includes('First Conversation'));

    await dom.click(dom.container.querySelector('[data-testid="copilot-delete-button"]'));
    await dom.flush();
    await dom.click(dom.container.querySelector('[data-testid="confirm-delete-conv-1101"]'));
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Second Conversation'));
    assert.ok(dom.container.textContent.includes('Message 2'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 14: Switching users / logging out without leaking previous conversation data
test('Scenario 14: Changing authenticated user clears in-memory conversation state and storage', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token-1');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([], {
    beforeRequest: async (urlStr, method, body, options) => {
      const authHeader = options?.headers?.Authorization || '';
      if (authHeader.includes('test-copilot-token-2')) {
        if (urlStr.match(/\/api\/ai\/conversations(\?.*)?$/) && method === 'GET') {
          return jsonResponse([], 200, urlStr);
        }
      }
      return null;
    },
  });

  backend.getConversations().push({
    id: 1201,
    title: 'User 1 Confidential Thread',
    messages: [{ id: 1, role: 'USER', content: 'Secret 1' }],
  });

  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    function MultiUserWrapper({ userId }) {
      return React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', userId) },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      );
    }

    await dom.render(React.createElement(MultiUserWrapper, { userId: 1 }));
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.ok(dom.container.textContent.includes('User 1 Confidential Thread'));
    assert.equal(dom.sessionStorage.getItem('pharmatrack_copilot_active_conv_1'), '1201');

    setAuthToken('test-copilot-token-2');
    await dom.render(React.createElement(MultiUserWrapper, { userId: 2 }));
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.strictEqual(dom.sessionStorage.getItem('pharmatrack_copilot_active_conv_1'), null);
    assert.strictEqual(dom.container.textContent.includes('Secret 1'), false);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 15: ORDER and BATCH source-reference navigation
test('Scenario 15: Source reference pills navigate to tab and close drawer', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { NavigationProvider } = await import('../context/NavigationContext.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  let navTarget = null;
  const navigateToTab = (tab, params) => {
    navTarget = { tab, params };
  };
  let closed = false;
  const onClose = () => {
    closed = true;
  };

  const backend = createMockCopilotBackend([
    {
      id: 1301,
      title: 'Citation Thread',
      messages: [
        {
          id: 1,
          role: 'ASSISTANT',
          content: 'Analysis report with citations',
          sources: [
            {
              recordType: 'MANUFACTURING_ORDER',
              recordIdentifier: 'MO-2026-0099',
              title: 'MO-2026-0099',
              module: 'orders',
            },
          ],
        },
      ],
    },
  ]);

  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(
          NavigationProvider,
          {
            value: {
              activeTab: 'orders',
              routeParams: new URLSearchParams(),
              routeNonce: 0,
              navigateToTab,
            },
          },
          React.createElement(AiCopilotDrawer, { isOpen: true, onClose })
        )
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    const sourcesContainer = dom.container.querySelector('[data-testid="verified-sources-list"]');
    assert.ok(sourcesContainer, 'Sources list container must be rendered');
    const pill = sourcesContainer.querySelector('button');
    assert.ok(pill, 'Source pill must be rendered');
    await dom.click(pill);
    await dom.flush();

    assert.equal(closed, true, 'Clicking source pill must close drawer');
    assert.deepEqual(navTarget, { tab: 'orders', params: { orderNumber: 'MO-2026-0099' } });
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 16: Session re-authentication preserves selected conversation and messages
test('Scenario 16: Session re-authentication retains current conversation and transcript', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    {
      id: 1401,
      title: 'Active In-Progress Session',
      messages: [{ id: 1, role: 'USER', content: 'Preserved user prompt' }],
    },
  ]);
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', 1) },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Preserved user prompt'));

    setAuthToken('refreshed-copilot-token');
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: { ...makeAuthValue('ADMIN', 1), token: 'refreshed-copilot-token' } },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Preserved user prompt'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// Scenario 17: Stale response protection during rapid conversation switching
test('Scenario 17: Discards stale transcript responses when rapidly switching conversations', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  let resolveSlowCall = null;
  const backend = createMockCopilotBackend(
    [
      { id: 1500, title: 'Initial Thread', messages: [{ id: 0, role: 'USER', content: 'Initial Content' }] },
      { id: 1501, title: 'Thread 1 (Slow)', messages: [{ id: 1, role: 'USER', content: 'Slow Thread 1501 Content' }] },
      { id: 1502, title: 'Thread 2 (Fast)', messages: [{ id: 2, role: 'USER', content: 'Fast Thread 1502 Content' }] },
    ],
    {
      onDetail: (id, urlStr) => {
        if (id === 1501) {
          return new Promise((resolve) => {
            resolveSlowCall = () =>
              resolve(
                jsonResponse(
                  {
                    id: 1501,
                    title: 'Thread 1 (Slow)',
                    messages: [{ id: 1, role: 'USER', content: 'Slow Thread 1501 Content' }],
                  },
                  200,
                  urlStr
                )
              );
          });
        }
        return null;
      },
    }
  );

  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    const historyToggle = dom.container.querySelector('[data-testid="copilot-history-toggle"]');
    await dom.click(historyToggle);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    const item1 = dom.container.querySelector('[data-testid="copilot-conversation-item-1501"]');
    assert.ok(item1, 'Conversation item 1501 must be rendered');
    await dom.click(item1);
    await dom.flush();

    // Re-open history view while 1501 is pending to switch to 1502
    await dom.click(dom.container.querySelector('[data-testid="copilot-history-toggle"]'));
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    const item2 = dom.container.querySelector('[data-testid="copilot-conversation-item-1502"]');
    assert.ok(item2, 'Conversation item 1502 must be rendered');
    await dom.click(item2);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Fast Thread 1502 Content'));

    if (resolveSlowCall) {
      resolveSlowCall();
    }
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Fast Thread 1502 Content'));
    assert.strictEqual(dom.container.textContent.includes('Slow Thread 1501 Content'), false);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

/* =========================================================================================
 * SECTION 3: Delete Conversation Centered Confirmation Modal Tests
 * ========================================================================================= */

test('Delete Modal: Clicking Delete Conversation opens centered modal with title, message, and buttons', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    { id: 2001, title: 'Order MO-2026-0001 Inquiry', messages: [] },
  ]);
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    const deleteBtn = dom.container.querySelector('[data-testid="copilot-delete-button"]');
    assert.ok(deleteBtn, 'Delete Conversation button must exist');
    await dom.click(deleteBtn);
    await dom.flush();

    const modal = dom.container.querySelector('[data-testid="delete-conversation-modal"]');
    assert.ok(modal, 'Delete confirmation modal must be open');
    assert.equal(modal.getAttribute('role') || modal.parentElement?.getAttribute('role'), 'dialog');

    const titleEl = dom.container.querySelector('[data-testid="delete-dialog-title"]');
    assert.ok(titleEl, 'Title element must exist');
    assert.equal(titleEl.textContent.trim(), 'Delete Conversation?');

    const descEl = dom.container.querySelector('[data-testid="delete-dialog-description"]');
    assert.ok(descEl, 'Description element must exist');
    assert.equal(
      descEl.textContent.trim(),
      'Are you sure you want to permanently delete this conversation? All messages in this conversation will also be deleted. This action cannot be undone.'
    );

    const cancelBtn = dom.container.querySelector('[data-testid="cancel-delete-conv-2001"]');
    const confirmBtn = dom.container.querySelector('[data-testid="confirm-delete-conv-2001"]');
    assert.ok(cancelBtn, 'Cancel button must exist');
    assert.ok(confirmBtn, 'Delete Conversation confirmation button must exist');
    assert.equal(cancelBtn.textContent.trim(), 'Cancel');
    assert.ok(confirmBtn.textContent.includes('Delete Conversation'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Delete Modal: Cancel button dismisses modal without calling DELETE endpoint', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    { id: 2002, title: 'Active Thread Keep Safe', messages: [{ id: 1, role: 'USER', content: 'Important query' }] },
  ]);
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    await dom.click(dom.container.querySelector('[data-testid="copilot-delete-button"]'));
    await dom.flush();

    assert.ok(dom.container.querySelector('[data-testid="delete-conversation-modal"]'));

    const cancelBtn = dom.container.querySelector('[data-testid="cancel-delete-conv-2002"]');
    await dom.click(cancelBtn);
    await dom.flush();

    assert.strictEqual(dom.container.querySelector('[data-testid="delete-conversation-modal"]'), null);
    const deleteCalls = backend.getCalls().filter((c) => c.method === 'DELETE');
    assert.equal(deleteCalls.length, 0, 'No DELETE call should have been dispatched');
    assert.equal(backend.getConversations().length, 1, 'Conversation must still exist in backend');
    assert.ok(dom.container.textContent.includes('Active Thread Keep Safe'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Delete Modal: Escape key dismisses modal without calling DELETE endpoint', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    { id: 2003, title: 'Escape Dismiss Thread', messages: [] },
  ]);
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    await dom.click(dom.container.querySelector('[data-testid="copilot-delete-button"]'));
    await dom.flush();

    const modal = dom.container.querySelector('[data-testid="delete-conversation-modal"]');
    assert.ok(modal);

    // Dispatch Escape key event (bubbles to document)
    await dom.act(async () => {
      modal.dispatchEvent(makeKeyEvent('Escape'));
    });
    await dom.flush();

    assert.strictEqual(dom.container.querySelector('[data-testid="delete-conversation-modal"]'), null);
    const deleteCalls = backend.getCalls().filter((c) => c.method === 'DELETE');
    assert.equal(deleteCalls.length, 0);
    assert.equal(backend.getConversations().length, 1);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Delete Modal: Handling deletion failure displays error and retains conversation in the UI', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend(
    [{ id: 2004, title: 'Failing Delete Thread', messages: [{ id: 1, role: 'USER', content: 'Preserved content' }] }],
    {
      beforeRequest: async (urlStr, method) => {
        if (urlStr.includes('/api/ai/conversations/2004') && method === 'DELETE') {
          return jsonResponse({ status: 500, message: 'Server error while deleting conversation' }, 500);
        }
        return null;
      },
    }
  );
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    await dom.click(dom.container.querySelector('[data-testid="copilot-delete-button"]'));
    await dom.flush();

    const confirmBtn = dom.container.querySelector('[data-testid="confirm-delete-conv-2004"]');
    await dom.click(confirmBtn);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    // Verify deletion error is surfaced in the modal
    const errorEl = dom.container.querySelector('[data-testid="delete-error-message"]');
    assert.ok(errorEl, 'Deletion error banner must be rendered');
    assert.ok(errorEl.textContent.includes('Server error while deleting conversation'));

    // Verify the conversation is still present and NOT lost from the UI
    assert.ok(dom.container.textContent.includes('Failing Delete Thread'));
    assert.ok(dom.container.textContent.includes('Preserved content'));
    assert.equal(backend.getConversations().length, 1, 'Conversation must remain in backend');

    // Click Cancel to dismiss the failed deletion modal
    await dom.click(dom.container.querySelector('[data-testid="cancel-delete-conv-2004"]'));
    await dom.flush();

    // Modal closed, thread still active
    assert.strictEqual(dom.container.querySelector('[data-testid="delete-conversation-modal"]'), null);
    assert.ok(dom.container.textContent.includes('Failing Delete Thread'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Delete Modal: Prevents duplicate deletion requests on rapid clicks', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  let resolveDelete = null;
  let deleteAttempts = 0;

  const backend = createMockCopilotBackend(
    [{ id: 2005, title: 'Rapid Delete Thread', messages: [] }],
    {
      beforeRequest: async (urlStr, method) => {
        if (urlStr.includes('/api/ai/conversations/2005') && method === 'DELETE') {
          deleteAttempts += 1;
          await new Promise((r) => {
            resolveDelete = r;
          });
        }
        return null;
      },
    }
  );
  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN') },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    await dom.click(dom.container.querySelector('[data-testid="copilot-delete-button"]'));
    await dom.flush();

    const confirmBtn = dom.container.querySelector('[data-testid="confirm-delete-conv-2005"]');
    assert.ok(confirmBtn);

    // Click twice rapidly
    await dom.click(confirmBtn);
    await dom.click(confirmBtn);
    await dom.flush();

    if (resolveDelete) {
      resolveDelete();
    }
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.equal(deleteAttempts, 1, 'Only one DELETE request should have been dispatched');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// =========================================================================
// Browser Refresh & Screen/Copilot State Preservation Scenarios
// =========================================================================

test('Refresh Scenario 1: Refresh while Copilot is open on Product Master keeps module and Copilot open', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token-1');
  const { default: App } = await import('../App.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    { id: 101, title: 'Active Chat', messages: [{ id: 1, role: 'USER', content: 'Hello' }] },
  ]);

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    return backend.handleFetch(url, opts);
  };

  const dom = await createDomEnvironment('http://localhost:5173/#/products');

  try {
    // Simulate pre-refresh state in sessionStorage
    dom.sessionStorage.setItem('pharmatrack_copilot_open_1', 'true');

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', 1) },
        React.createElement(App)
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    // Verify module is Product Master
    const moduleIndicator = dom.container.querySelector('[data-testid="active-module-indicator"]');
    assert.ok(moduleIndicator);
    assert.equal(moduleIndicator.textContent.trim(), 'Product Master');

    // Verify Copilot drawer is open
    const drawer = dom.container.querySelector('[data-testid="ai-copilot-drawer"]');
    assert.ok(drawer, 'Copilot drawer should be open after refresh');

    const toggle = dom.container.querySelector('[data-testid="global-ai-copilot-toggle"]');
    assert.equal(toggle.getAttribute('aria-expanded'), 'true');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Refresh Scenario 2: Refresh while in Chat History restores Chat History view', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token-1');
  const { default: App } = await import('../App.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    { id: 201, title: 'History Chat One', messages: [] },
    { id: 202, title: 'History Chat Two', messages: [] },
  ]);

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    return backend.handleFetch(url, opts);
  };

  const dom = await createDomEnvironment('http://localhost:5173/#/products');

  try {
    // Simulate pre-refresh: Copilot open and in history view
    dom.sessionStorage.setItem('pharmatrack_copilot_open_1', 'true');
    dom.sessionStorage.setItem('pharmatrack_copilot_view_1', 'history');

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', 1) },
        React.createElement(App)
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    // Verify Copilot history panel is open
    const historyPanel = dom.container.querySelector('[data-testid="copilot-history-panel"]');
    assert.ok(historyPanel, 'Copilot history panel should be visible after refresh');

    // Verify conversation items are loaded in history view
    const item1 = dom.container.querySelector('[data-testid="copilot-conversation-item-201"]');
    const item2 = dom.container.querySelector('[data-testid="copilot-conversation-item-202"]');
    assert.ok(item1);
    assert.ok(item2);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Refresh Scenario 3: Refresh inside an existing conversation restores that thread and messages', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token-1');
  const { default: App } = await import('../App.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    {
      id: 301,
      title: 'Active Thread 301',
      messages: [{ id: 1, role: 'ASSISTANT', content: 'Message from thread 301' }],
    },
    {
      id: 302,
      title: 'Target Restored Thread 302',
      messages: [{ id: 2, role: 'ASSISTANT', content: 'Special persisted text for thread 302' }],
    },
  ]);

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    return backend.handleFetch(url, opts);
  };

  const dom = await createDomEnvironment('http://localhost:5173/#/products');

  try {
    // Simulate pre-refresh: Copilot open, chat view, thread 302 selected
    dom.sessionStorage.setItem('pharmatrack_copilot_open_1', 'true');
    dom.sessionStorage.setItem('pharmatrack_copilot_view_1', 'chat');
    dom.sessionStorage.setItem('pharmatrack_copilot_active_conv_1', '302');

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', 1) },
        React.createElement(App)
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 100));
    await dom.flush();

    // Verify messages from conversation 302 are displayed
    assert.ok(
      dom.container.textContent.includes('Special persisted text for thread 302'),
      'Messages from conversation 302 should be rendered'
    );
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Refresh Scenario 4: Refresh with order context preserves the active order context', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token-1');
  const { default: App } = await import('../App.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const backend = createMockCopilotBackend([
    { id: 401, title: 'Order Inquiry', messages: [] },
  ]);

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    return backend.handleFetch(url, opts);
  };

  const dom = await createDomEnvironment('http://localhost:5173/#/orders');

  try {
    // Simulate pre-refresh: Copilot open with order context MO-2026-0888
    dom.sessionStorage.setItem('pharmatrack_copilot_open_1', 'true');
    dom.sessionStorage.setItem(
      'pharmatrack_copilot_context_1',
      JSON.stringify({
        activeModule: 'orders',
        contextType: 'MANUFACTURING_ORDER',
        contextId: 'MO-2026-0888',
        title: 'MO-2026-0888',
      })
    );

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', 1) },
        React.createElement(App)
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    // Verify active context chip displays MO-2026-0888
    const contextChip = dom.container.querySelector('[data-testid="active-context-badge"]');
    assert.ok(contextChip, 'Copilot context indicator should be rendered');
    assert.ok(contextChip.textContent.includes('MO-2026-0888'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Refresh Scenario 5: Refresh while Copilot is closed keeps drawer closed', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token-1');
  const { default: App } = await import('../App.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    return jsonResponse([], 200, url);
  };

  const dom = await createDomEnvironment('http://localhost:5173/#/products');

  try {
    // Simulate pre-refresh: Copilot closed
    dom.sessionStorage.setItem('pharmatrack_copilot_open_1', 'false');

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', 1) },
        React.createElement(App)
      )
    );
    await dom.flush();

    const drawer = dom.container.querySelector('[data-testid="ai-copilot-drawer"]');
    assert.equal(drawer, null, 'Copilot drawer should remain closed');

    const toggle = dom.container.querySelector('[data-testid="global-ai-copilot-toggle"]');
    assert.equal(toggle.getAttribute('aria-expanded'), 'false');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Refresh Scenario 6: Refresh on another module with Copilot closed preserves that module and closed state', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token-1');
  const { default: App } = await import('../App.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    return jsonResponse([], 200, url);
  };

  const dom = await createDomEnvironment('http://localhost:5173/#/inventory');

  try {
    dom.sessionStorage.setItem('pharmatrack_copilot_open_1', 'false');

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', 1) },
        React.createElement(App)
      )
    );
    await dom.flush();

    const moduleIndicator = dom.container.querySelector('[data-testid="active-module-indicator"]');
    assert.ok(moduleIndicator);
    assert.equal(moduleIndicator.textContent.trim(), 'Inventory & Warehouse');

    const drawer = dom.container.querySelector('[data-testid="ai-copilot-drawer"]');
    assert.equal(drawer, null);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Refresh Scenario 7: Refresh on another module with Copilot open preserves that module and open state', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token-1');
  const { default: App } = await import('../App.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    return jsonResponse([], 200, url);
  };

  const dom = await createDomEnvironment('http://localhost:5173/#/recipes');

  try {
    dom.sessionStorage.setItem('pharmatrack_copilot_open_1', 'true');

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', 1) },
        React.createElement(App)
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    const moduleIndicator = dom.container.querySelector('[data-testid="active-module-indicator"]');
    assert.ok(moduleIndicator);
    assert.equal(moduleIndicator.textContent.trim(), 'Recipe Management');

    const drawer = dom.container.querySelector('[data-testid="ai-copilot-drawer"]');
    assert.ok(drawer);
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Logout & Account Switching: Sign out clears Copilot state and prevents leaking to another user', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token-1');
  const { default: App } = await import('../App.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  let loggedOut = false;
  const authValueUser1 = {
    ...makeAuthValue('ADMIN', 1),
    logout: () => {
      loggedOut = true;
    },
  };

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    return jsonResponse([], 200, url);
  };

  const dom = await createDomEnvironment('http://localhost:5173/#/products');

  try {
    // User 1 has open Copilot state in sessionStorage
    dom.sessionStorage.setItem('pharmatrack_copilot_open_1', 'true');
    dom.sessionStorage.setItem('pharmatrack_copilot_view_1', 'history');
    dom.sessionStorage.setItem('pharmatrack_copilot_active_conv_1', '888');

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: authValueUser1 },
        React.createElement(App)
      )
    );
    await dom.flush();

    // Click Sign Out
    const signOutBtn = dom.container.querySelector('button[aria-label="Sign Out"]');
    assert.ok(signOutBtn);
    await dom.click(signOutBtn);
    await dom.flush();

    assert.equal(loggedOut, true, 'logout handler should be called');

    // Verify User 1 keys were wiped from sessionStorage
    assert.equal(dom.sessionStorage.getItem('pharmatrack_copilot_open_1'), null);
    assert.equal(dom.sessionStorage.getItem('pharmatrack_copilot_view_1'), null);
    assert.equal(dom.sessionStorage.getItem('pharmatrack_copilot_active_conv_1'), null);

    // Now render as User 2 (OPERATOR)
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR', 2) },
        React.createElement(App)
      )
    );
    await dom.flush();

    // Verify User 2 has closed Copilot and no state from User 1
    const drawer = dom.container.querySelector('[data-testid="ai-copilot-drawer"]');
    assert.equal(drawer, null, 'User 2 should start with Copilot closed');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Session Storage Safety: Malformed or corrupt sessionStorage values fail safely', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token-1');
  const { default: App } = await import('../App.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    return jsonResponse([], 200, url);
  };

  const dom = await createDomEnvironment('http://localhost:5173/#/products');

  try {
    // Corrupted non-boolean open state, malformed JSON context, invalid view string
    dom.sessionStorage.setItem('pharmatrack_copilot_open_1', 'corrupted_string');
    dom.sessionStorage.setItem('pharmatrack_copilot_context_1', '{bad-json:undefined,');
    dom.sessionStorage.setItem('pharmatrack_copilot_view_1', 'unrecognized_view');

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', 1) },
        React.createElement(App)
      )
    );
    await dom.flush();

    // Copilot should safely fail-closed
    const drawer = dom.container.querySelector('[data-testid="ai-copilot-drawer"]');
    assert.equal(drawer, null, 'Drawer should fail closed with corrupt open state');

    const toggle = dom.container.querySelector('[data-testid="global-ai-copilot-toggle"]');
    assert.equal(toggle.getAttribute('aria-expanded'), 'false');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('User Isolation: Stale asynchronous conversation detail cannot settle for switched user', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token-1');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  let resolveSlowDetail = null;

  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    if (url.endsWith('/health')) return jsonResponse({ status: 'UP', message: 'OK' }, 200, url);
    const authHeader = (opts && opts.headers && (opts.headers.Authorization || opts.headers.authorization)) || '';
    const isUser1 = authHeader.includes('test-copilot-token-1');
    if (!isUser1) {
      return jsonResponse([], 200, url);
    }
    if (url.includes('/api/ai/conversations/901')) {
      await new Promise((r) => {
        resolveSlowDetail = r;
      });
      return jsonResponse(
        {
          id: 901,
          title: 'User 1 Confidential Thread',
          messages: [{ id: 1, role: 'USER', content: 'User 1 confidential message' }],
        },
        200,
        url
      );
    }
    if (url.includes('/api/ai/conversations')) {
      return jsonResponse([{ id: 901, title: 'User 1 Thread' }], 200, url);
    }
    return jsonResponse([], 200, url);
  };

  const dom = await createDomEnvironment();

  try {
    // Render drawer for User 1
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', 1) },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();

    // Switch account to User 2 while User 1 detail request is in-flight
    setAuthToken('test-copilot-token-2');
    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('OPERATOR', 2) },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      )
    );
    await dom.flush();

    // Now let User 1's slow response resolve
    if (resolveSlowDetail) {
      resolveSlowDetail();
    }
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    // User 1's confidential text must NOT leak into User 2's transcript
    assert.equal(
      dom.container.textContent.includes('User 1 confidential message'),
      false,
      'Stale async response from User 1 must be discarded and never leak to User 2'
    );
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

test('Message Submission Isolation: In-flight message submitted by User A cannot settle or leak when switching to User B', async () => {
  resetReauthState();
  setAuthToken('test-copilot-token-1');
  const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');
  const { AuthContext } = await import('../context/AuthContext.jsx');

  let resolveUser1Message = null;

  const backend = createMockCopilotBackend(
    [
      {
        id: 101,
        title: 'User 1 Active Conversation',
        messages: [{ id: 10, role: 'USER', content: 'User 1 initial inquiry' }],
      },
    ],
    {
      beforeRequest: async (urlStr, method, body, options) => {
        const authHeader = options?.headers?.Authorization || options?.headers?.authorization || '';
        if (urlStr.includes('/messages') && method === 'POST') {
          await new Promise((r) => {
            resolveUser1Message = r;
          });
        }
        if (authHeader.includes('test-copilot-token-2')) {
          if (urlStr.match(/\/api\/ai\/conversations(\?.*)?$/) && method === 'GET') {
            return jsonResponse([], 200, urlStr);
          }
        }
        return null;
      },
    }
  );

  const prevFetch = globalThis.fetch;
  globalThis.fetch = backend.handleFetch;
  const dom = await createDomEnvironment();

  try {
    function MultiUserWrapper({ userId }) {
      return React.createElement(
        AuthContext.Provider,
        { value: makeAuthValue('ADMIN', userId) },
        React.createElement(AiCopilotDrawer, { isOpen: true, onClose: () => {} })
      );
    }

    // 1. User 1 opens Copilot and loads conversation 101
    await dom.render(React.createElement(MultiUserWrapper, { userId: 1 }));
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    assert.ok(dom.container.textContent.includes('User 1 initial inquiry'));

    // 2. User 1 submits a query
    const input = dom.container.querySelector('[data-testid="copilot-query-input"]');
    assert.ok(input);
    await dom.changeInput(input, 'User 1 question in flight');
    await dom.flush();
    const form = dom.container.querySelector('form');
    assert.ok(form);
    await dom.submit(form);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    // Verify User 1 message submission is now in-flight
    assert.ok(resolveUser1Message, 'User 1 message request should be in-flight');

    // 3. User 1 logs out / account switches to User 2 before response arrives
    setAuthToken('test-copilot-token-2');
    await dom.render(React.createElement(MultiUserWrapper, { userId: 2 }));
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    // Verify User 2 has fresh/empty conversation state
    assert.equal(dom.container.textContent.includes('User 1 initial inquiry'), false);

    // 4. User 1's pending AI response completes afterward
    resolveUser1Message();
    await dom.flush();
    await new Promise((r) => setTimeout(r, 100));
    await dom.flush();

    // 5. Verify User 1's response NEVER appeared in User 2's transcript
    const textAfter = dom.container.textContent;
    assert.equal(
      textAfter.includes('User 1 question in flight'),
      false,
      "User 1's in-flight query must not appear in User 2's conversation"
    );

    // Verify User 2 is not blocked by User 1's in-flight submission lock or loading state
    const inputUser2 = dom.container.querySelector('[data-testid="copilot-query-input"]');
    assert.ok(inputUser2);
    assert.equal(inputUser2.disabled, false, "User 2 input must not be disabled by User 1's pending submission");

    await dom.changeInput(inputUser2, 'User 2 new question');
    await dom.flush();
    const submitBtn = dom.container.querySelector('button[type="submit"]');
    assert.ok(submitBtn);
    assert.equal(submitBtn.disabled, false, "User 2 must be able to submit queries normally");
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});
