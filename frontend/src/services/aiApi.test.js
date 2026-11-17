import test from 'node:test';
import assert from 'node:assert/strict';
import { setAuthToken, clearAuthToken } from './apiClient.js';
import {
  generateClientRequestId,
  listConversations,
  createConversation,
  getConversationDetail,
  renameConversation,
  deleteConversation,
  submitConversationMessage,
  queryAiCopilot,
} from './aiApi.js';

function jsonResponse(body, status = 200, url = 'http://localhost:8080/api/ai/query') {
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

test('generateClientRequestId emits safe, non-empty unique string within 64 chars', () => {
  const id1 = generateClientRequestId();
  const id2 = generateClientRequestId();
  assert.ok(id1.startsWith('req-'));
  assert.ok(id2.startsWith('req-'));
  assert.notEqual(id1, id2);
  assert.ok(id1.length <= 64);
  assert.ok(id2.length <= 64);
});

test('listConversations queries GET /api/ai/conversations with pagination', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  setAuthToken('test-list-token');

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse([
      {
        id: 101,
        title: 'Batch Trace Thread',
        messageCount: 3,
        createdAt: '2026-11-14T03:00:00',
        updatedAt: '2026-11-14T03:05:00',
      },
    ]);
  };

  try {
    const list = await listConversations({ page: 0, size: 20 });
    assert.equal(captured.length, 1);
    assert.match(captured[0].url, /\/api\/ai\/conversations\?page=0&size=20$/);
    assert.equal(captured[0].options.method, 'GET');
    assert.equal(captured[0].options.headers.Authorization, 'Bearer test-list-token');
    assert.equal(list.length, 1);
    assert.equal(list[0].id, 101);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('createConversation posts title and context to POST /api/ai/conversations', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  setAuthToken('test-create-token');

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse({
      id: 202,
      title: 'Order MO-2026-0001 Inquiry',
      activeModule: 'orders',
      contextType: 'MANUFACTURING_ORDER',
      contextId: 'MO-2026-0001',
      messageCount: 0,
      createdAt: '2026-11-14T03:10:00',
      updatedAt: '2026-11-14T03:10:00',
    }, 201);
  };

  try {
    const created = await createConversation({
      title: 'Order MO-2026-0001 Inquiry',
      activeModule: 'orders',
      contextType: 'MANUFACTURING_ORDER',
      contextId: 'MO-2026-0001',
    });

    assert.equal(captured.length, 1);
    assert.match(captured[0].url, /\/api\/ai\/conversations$/);
    assert.equal(captured[0].options.method, 'POST');
    const sent = JSON.parse(captured[0].options.body);
    assert.deepEqual(sent, {
      title: 'Order MO-2026-0001 Inquiry',
      activeModule: 'orders',
      contextType: 'MANUFACTURING_ORDER',
      contextId: 'MO-2026-0001',
    });
    assert.equal(created.id, 202);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('getConversationDetail retrieves conversation transcript via GET /api/ai/conversations/{id}', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse({
      id: 303,
      title: 'Detailed Thread',
      messages: [
        { id: 1, role: 'USER', content: 'What is yield?' },
        { id: 2, role: 'ASSISTANT', content: 'Yield is 98.5%' },
      ],
    });
  };

  try {
    const detail = await getConversationDetail(303);
    assert.equal(captured.length, 1);
    assert.match(captured[0].url, /\/api\/ai\/conversations\/303$/);
    assert.equal(captured[0].options.method, 'GET');
    assert.equal(detail.id, 303);
    assert.equal(detail.messages.length, 2);
  } finally {
    globalThis.fetch = prevFetch;
  }
});

test('renameConversation patches title via PATCH /api/ai/conversations/{id}', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse({
      id: 404,
      title: 'Renamed Title',
    });
  };

  try {
    const updated = await renameConversation(404, { title: 'Renamed Title' });
    assert.equal(captured.length, 1);
    assert.match(captured[0].url, /\/api\/ai\/conversations\/404$/);
    assert.equal(captured[0].options.method, 'PATCH');
    const sent = JSON.parse(captured[0].options.body);
    assert.deepEqual(sent, { title: 'Renamed Title' });
    assert.equal(updated.title, 'Renamed Title');
  } finally {
    globalThis.fetch = prevFetch;
  }
});

test('deleteConversation executes DELETE /api/ai/conversations/{id}', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return {
      ok: true,
      status: 204,
      headers: { get: () => null },
      json: async () => null,
      text: async () => '',
    };
  };

  try {
    await deleteConversation(505);
    assert.equal(captured.length, 1);
    assert.match(captured[0].url, /\/api\/ai\/conversations\/505$/);
    assert.equal(captured[0].options.method, 'DELETE');
  } finally {
    globalThis.fetch = prevFetch;
  }
});

test('submitConversationMessage posts inquiry to POST /api/ai/conversations/{id}/messages', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  setAuthToken('test-msg-token');

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse({
      conversationId: 606,
      requestId: 707,
      clientRequestId: 'req-custom-1',
      status: 'COMPLETED',
      userMessage: { id: 10, role: 'USER', content: 'Yield query' },
      assistantMessage: { id: 11, role: 'ASSISTANT', content: '99% yield', status: 'COMPLETED' },
    });
  };

  try {
    const res = await submitConversationMessage(606, {
      clientRequestId: 'req-custom-1',
      query: 'Yield query',
      activeModule: 'orders',
      contextType: 'MANUFACTURING_ORDER',
      contextId: 'MO-2026-0001',
    });

    assert.equal(captured.length, 1);
    assert.match(captured[0].url, /\/api\/ai\/conversations\/606\/messages$/);
    assert.equal(captured[0].options.method, 'POST');
    const sent = JSON.parse(captured[0].options.body);
    assert.deepEqual(sent, {
      clientRequestId: 'req-custom-1',
      query: 'Yield query',
      activeModule: 'orders',
      contextType: 'MANUFACTURING_ORDER',
      contextId: 'MO-2026-0001',
    });
    assert.equal(res.status, 'COMPLETED');
    assert.equal(res.assistantMessage.content, '99% yield');
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('queryAiCopilot attaches JWT token, serializes request DTO, and handles success response', async () => {
  const prevFetch = globalThis.fetch;
  const captured = [];

  setAuthToken('test-ai-token-xyz');

  globalThis.fetch = async (url, options) => {
    captured.push({ url, options });
    return jsonResponse({
      answer: 'Manufacturing order MO-2026-0001 is COMPLETED with 1 batch.',
      contextType: 'MANUFACTURING_ORDER',
      contextIdentifier: 'MO-2026-0001',
      sources: [
        {
          recordType: 'MANUFACTURING_ORDER',
          recordIdentifier: 'MO-2026-0001',
          title: 'MO-2026-0001 (Aspirin 500mg Tablets)',
          module: 'Manufacturing Orders',
          summary: 'Status: COMPLETED | Product: PROD-ASP-500 | Planned: 50000.00 TABLETS',
        },
      ],
      model: 'gpt-6-luna',
      timestamp: '2026-11-13T17:55:00+05:30',
      disclaimer: 'Advisory MES Copilot guidance based exclusively on verified PharmaTrack database records.',
    });
  };

  try {
    const result = await queryAiCopilot({
      query: 'What is the status of MO-2026-0001?',
      activeModule: 'orders',
      contextType: 'MANUFACTURING_ORDER',
      contextId: 'MO-2026-0001',
    });

    assert.equal(captured.length, 1);
    assert.match(captured[0].url, /\/api\/ai\/query$/);
    assert.equal(captured[0].options.method, 'POST');
    assert.equal(captured[0].options.headers.Authorization, 'Bearer test-ai-token-xyz');
    assert.equal(captured[0].options.headers['Content-Type'], 'application/json');

    const sentBody = JSON.parse(captured[0].options.body);
    assert.deepEqual(sentBody, {
      query: 'What is the status of MO-2026-0001?',
      activeModule: 'orders',
      contextType: 'MANUFACTURING_ORDER',
      contextId: 'MO-2026-0001',
    });

    assert.equal(result.contextIdentifier, 'MO-2026-0001');
    assert.equal(result.model, 'gpt-6-luna');
    assert.equal(result.sources.length, 1);
    assert.match(result.answer, /MO-2026-0001 is COMPLETED/);
  } finally {
    globalThis.fetch = prevFetch;
    clearAuthToken();
  }
});

test('queryAiCopilot omits empty optional context fields cleanly', async () => {
  const prevFetch = globalThis.fetch;
  let sentBody = null;

  globalThis.fetch = async (url, options) => {
    sentBody = JSON.parse(options.body);
    return jsonResponse({
      answer: 'Please specify a valid manufacturing order number.',
      contextType: 'MANUFACTURING_ORDER',
      contextIdentifier: null,
      sources: [],
      model: 'gpt-6-luna',
      timestamp: '2026-11-13T17:55:00+05:30',
      disclaimer: 'Advisory guidance only.',
    });
  };

  try {
    await queryAiCopilot({
      query: '  General query without context  ',
      activeModule: '',
      contextType: null,
      contextId: undefined,
    });

    assert.deepEqual(sentBody, {
      query: 'General query without context',
    });
  } finally {
    globalThis.fetch = prevFetch;
  }
});

test('queryAiCopilot propagates 503 AI disabled error with server message', async () => {
  const prevFetch = globalThis.fetch;

  globalThis.fetch = async (url) => {
    return jsonResponse(
      {
        status: 503,
        error: 'Service Unavailable',
        message: 'PharmaTrack AI Copilot is currently unavailable.',
        timestamp: '2026-11-13T17:55:00+05:30',
      },
      503,
      url
    );
  };

  try {
    await assert.rejects(
      async () => {
        await queryAiCopilot({ query: 'Status of MO-2026-0001?' });
      },
      (err) => {
        assert.equal(err.status, 503);
        assert.equal(err.message, 'PharmaTrack AI Copilot is currently unavailable.');
        return true;
      }
    );
  } finally {
    globalThis.fetch = prevFetch;
  }
});
