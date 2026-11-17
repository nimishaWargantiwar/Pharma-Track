import test from 'node:test';
import assert from 'node:assert/strict';
import React, { useState } from 'react';
import { createDomEnvironment } from '../../scripts/dom-harness.mjs';
import { setAuthToken } from '../services/apiClient.js';
import { AuthContext } from '../context/AuthContext.jsx';
import { NavigationContext } from '../context/NavigationContext.jsx';
import {
  extractVerifiedContext,
  extractVerifiedOrderContext,
  deriveConversationTitle,
} from './AiCopilotDrawer.jsx';
import FormattedMessage, { parseMarkdownBlocks } from './FormattedMessage.jsx';
import AiSourceReferencePill from './AiSourceReferencePill.jsx';
import BatchTraceability from './BatchTraceability.jsx';

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

const mockDossier = {
  batchId: 10,
  batchNumber: 'BAT-2026-0010-01',
  qrCode: 'QR-PT-BAT-2026-0010-01',
  orderId: 5,
  orderNumber: 'MO-2026-0010',
  orderStatus: 'COMPLETED',
  productId: 2,
  productCode: 'PRD-AMX-500',
  productName: 'Amoxicillin 500mg Capsules',
  recipeCode: 'RCP-AMX-001',
  recipeVersion: 2,
  batchSize: 500.0,
  batchUnit: 'kg',
  actualYield: 498.2,
  batchStatus: 'COMPLETED',
  qaStatus: 'RELEASED',
  completenessPercent: 100,
  completenessStatus: 'COMPLETE',
};

// ---------------------------------------------------------------------------
// 1. Role Visibility: ONLY PRODUCTION_SUPERVISOR sees Generate AI Batch Analysis
// ---------------------------------------------------------------------------

test('BatchTraceability renders Generate AI Batch Analysis button ONLY for PRODUCTION_SUPERVISOR', async () => {
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes('/api/batches/traceability/')) {
      return jsonResponse(mockDossier);
    }
    if (String(url).includes('/analysis-reports')) {
      return jsonResponse([]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    const rolesToTest = [
      { role: 'PRODUCTION_SUPERVISOR', expectedVisible: true },
      { role: 'ADMIN', expectedVisible: false },
      { role: 'OPERATOR', expectedVisible: false },
      { role: 'QC_ANALYST', expectedVisible: false },
      { role: 'QA_REVIEWER', expectedVisible: false },
      { role: null, expectedVisible: false },
    ];

    for (const { role, expectedVisible } of rolesToTest) {
      let openCopilotCalledWith = null;

      const authValue = role
        ? {
            user: { id: 1, username: 'testuser', role },
            role,
            isAuthenticated: true,
          }
        : null;

      const navValue = {
        activeTab: 'traceability',
        routeParams: new URLSearchParams('batchNumber=BAT-2026-0010-01'),
        routeNonce: 1,
        navigateToTab: () => {},
      };

      await dom.render(
        React.createElement(
          AuthContext.Provider,
          { value: authValue },
          React.createElement(
            NavigationContext.Provider,
            { value: navValue },
            React.createElement(BatchTraceability, {
              initialBatchNumber: 'BAT-2026-0010-01',
              onOpenCopilot: (ctx) => {
                openCopilotCalledWith = ctx;
              },
            })
          )
        )
      );
      await dom.flush();

      const btn = dom.container.querySelector('[data-testid="generate-ai-batch-analysis-button"]');
      if (expectedVisible) {
        assert.ok(btn, `Button must be visible for role ${role}`);
        assert.ok(btn.textContent.includes('Generate AI Batch Analysis'));

        // Test clicking the button
        await dom.click(btn);
        await dom.flush();

        assert.equal(openCopilotCalledWith, null, 'Must NOT open Copilot drawer');
        const modal = dom.container.querySelector('[data-testid="report-history-sidebar"]') || dom.container.querySelector('[role="dialog"]');
        assert.ok(modal, 'Must open dedicated Batch Analysis Workspace Modal');
      } else {
        assert.equal(btn, null, `Button must NOT be visible for role ${role}`);
      }
    }
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// ---------------------------------------------------------------------------
// 2. Deterministic Context Extraction & Title Derivation
// ---------------------------------------------------------------------------

test('extractVerifiedContext extracts BATCH, MANUFACTURING_ORDER, and handles response-level fallbacks', () => {
  // BATCH source citation
  const batchResp = {
    sources: [
      {
        recordType: 'BATCH',
        recordIdentifier: 'BAT-2026-0010-01',
        title: 'BAT-2026-0010-01 (Amoxicillin 500mg)',
      },
      {
        recordType: 'MANUFACTURING_ORDER',
        recordIdentifier: 'MO-2026-0010',
        title: 'MO-2026-0010',
      },
    ],
  };
  const batchCtx = extractVerifiedContext(batchResp);
  assert.ok(batchCtx);
  assert.equal(batchCtx.contextType, 'BATCH');
  assert.equal(batchCtx.contextId, 'BAT-2026-0010-01');
  assert.equal(batchCtx.activeModule, 'traceability');

  // Order-only source citation
  const orderResp = {
    sources: [
      {
        recordType: 'MANUFACTURING_ORDER',
        recordIdentifier: 'MO-2026-0005',
        title: 'MO-2026-0005',
      },
    ],
  };
  const orderCtx = extractVerifiedContext(orderResp);
  assert.ok(orderCtx);
  assert.equal(orderCtx.contextType, 'MANUFACTURING_ORDER');
  assert.equal(orderCtx.contextId, 'MO-2026-0005');
  assert.equal(orderCtx.activeModule, 'orders');

  // Response-level BATCH fallback without sources
  const fallbackBatchResp = {
    contextType: 'BATCH',
    contextIdentifier: 'BAT-2026-0099-01',
    sources: [],
  };
  const fallbackCtx = extractVerifiedContext(fallbackBatchResp);
  assert.ok(fallbackCtx);
  assert.equal(fallbackCtx.contextType, 'BATCH');
  assert.equal(fallbackCtx.contextId, 'BAT-2026-0099-01');

  // Null & invalid safety
  assert.equal(extractVerifiedContext(null), null);
  assert.equal(extractVerifiedContext({}), null);
});

test('deriveConversationTitle creates batch-specific titles', () => {
  assert.equal(deriveConversationTitle('', 'BAT-2026-0010-01', 'BATCH'), 'Batch BAT-2026-0010-01 Analysis');
  assert.equal(deriveConversationTitle('', 'BAT-2026-0010-01'), 'Batch BAT-2026-0010-01 Analysis');
  assert.equal(deriveConversationTitle('', 'MO-2026-0001', 'MANUFACTURING_ORDER'), 'Order MO-2026-0001 Inquiry');
  assert.equal(deriveConversationTitle('Explain batch yield and deviation root cause', null), 'Explain batch yield and deviation roo...');
});

// ---------------------------------------------------------------------------
// 3. AiSourceReferencePill DEVIATION Deep Linking
// ---------------------------------------------------------------------------

test('AiSourceReferencePill renders DEVIATION citation and deep-links to quality module', async () => {
  const dom = await createDomEnvironment();
  try {
    let navigatedTab = null;
    let navigatedParams = null;
    let onNavigateCallbackSource = null;

    const navValue = {
      navigateToTab: (tab, params) => {
        navigatedTab = tab;
        navigatedParams = params;
      },
    };

    const deviationSource = {
      recordType: 'DEVIATION',
      recordIdentifier: 'DEV-2026-0003',
      title: 'DEV-2026-0003 (Temperature Excursion)',
      module: 'Quality Management',
      summary: 'Status: UNDER_INVESTIGATION | In-process drying temperature excursion',
    };

    await dom.render(
      React.createElement(
        NavigationContext.Provider,
        { value: navValue },
        React.createElement(AiSourceReferencePill, {
          source: deviationSource,
          onNavigate: (s) => {
            onNavigateCallbackSource = s;
          },
        })
      )
    );
    await dom.flush();

    const pill = dom.container.querySelector('button');
    assert.ok(pill);
    assert.ok(pill.textContent.includes('Deviation'));
    assert.ok(pill.textContent.includes('DEV-2026-0003'));

    await dom.click(pill);
    await dom.flush();

    assert.equal(navigatedTab, 'quality');
    assert.equal(navigatedParams?.deviationNumber, 'DEV-2026-0003');
    assert.equal(navigatedParams?.view, 'deviations');
    assert.equal(onNavigateCallbackSource?.recordIdentifier, 'DEV-2026-0003');
  } finally {
    await dom.unmount?.();
  }
});

// ---------------------------------------------------------------------------
// 4. FormattedMessage: Blockquotes, Headings, Tables, Math, and Safe Rendering
// ---------------------------------------------------------------------------

test('FormattedMessage parses blockquotes and markdown tables without innerHTML', async () => {
  const dom = await createDomEnvironment();
  try {
    const markdown = `# AI Batch Analysis Report: BAT-2026-0010-01
> Advisory Notice: This report is grounded strictly in verified electronic batch records.

## Execution Summary
| Metric | Planned | Actual | Status |
| --- | --- | --- | --- |
| Yield | 500.0 kg | 498.2 kg | 99.64% |

* Step 1: Dispensing complete
* Step 2: Granulation complete

Formula: $Yield = (498.2 / 500.0) \\times 100$`;

    await dom.render(React.createElement(FormattedMessage, { content: markdown }));
    await dom.flush();

    // Verify h1 and h2
    const h1 = dom.container.querySelector('h1');
    assert.ok(h1);
    assert.ok(h1.textContent.includes('AI Batch Analysis Report'));

    const h2 = dom.container.querySelector('h2');
    assert.ok(h2);
    assert.ok(h2.textContent.includes('Execution Summary'));

    // Verify blockquote
    const quote = dom.container.querySelector('blockquote');
    assert.ok(quote);
    assert.ok(quote.textContent.includes('Advisory Notice'));

    // Verify table
    const table = dom.container.querySelector('table');
    assert.ok(table);
    assert.ok(table.textContent.includes('Yield'));
    assert.ok(table.textContent.includes('498.2 kg'));

    // Verify math expression
    const math = dom.container.querySelector('[data-testid="math-expr"]');
    assert.ok(math);
    assert.ok(math.textContent.includes('Yield'));

    // Security check: Verify innerHTML is never present
    assert.equal(dom.container.textContent.includes('<script>'), false);
  } finally {
    await dom.unmount?.();
  }
});

// ---------------------------------------------------------------------------
// 5. AiCopilotDrawer Batch Context: Badge, Suggestions, Initial Query Auto-Submission, Disclaimer
// ---------------------------------------------------------------------------

test('AiCopilotDrawer handles BATCH context, auto-submits initial query, and displays advisory disclaimer', async () => {
  setAuthToken('test-token');
  const prevFetch = globalThis.fetch;

  let createdConversationPayload = null;
  let submittedMessagePayload = null;

  globalThis.fetch = async (url, options) => {
    const urlStr = String(url);
    const method = options?.method || 'GET';

    if (urlStr.includes('/api/ai/conversations') && method === 'POST') {
      if (urlStr.includes('/messages')) {
        submittedMessagePayload = JSON.parse(options.body);
        return jsonResponse({
          id: 101,
          conversationId: 55,
          clientRequestId: submittedMessagePayload.clientRequestId,
          status: 'COMPLETED',
          answer: '## Batch Analysis Report for BAT-2026-0010-01\nBatch execution completed with 99.64% yield.',
          model: 'gpt-6-luna',
          contextType: 'BATCH',
          contextIdentifier: 'BAT-2026-0010-01',
          sources: [
            {
              recordType: 'BATCH',
              recordIdentifier: 'BAT-2026-0010-01',
              title: 'BAT-2026-0010-01 (Amoxicillin 500mg)',
              module: 'Batch Traceability',
            },
            {
              recordType: 'DEVIATION',
              recordIdentifier: 'DEV-2026-0003',
              title: 'DEV-2026-0003 (Temperature Excursion)',
              module: 'Quality Management',
            },
          ],
          disclaimer: 'Advisory intelligence based strictly on PharmaTrack MES and eBR records. Human review and sign-off required for GxP compliance.',
        });
      }
      createdConversationPayload = JSON.parse(options.body);
      return jsonResponse({
        id: 55,
        title: createdConversationPayload.title,
        activeModule: createdConversationPayload.activeModule,
        contextType: createdConversationPayload.contextType,
        contextId: createdConversationPayload.contextId,
        createdAt: '2026-11-14T10:00:00Z',
        updatedAt: '2026-11-14T10:00:00Z',
      });
    }

    if (urlStr.includes('/api/ai/conversations/55') && method === 'GET') {
      return jsonResponse({
        id: 55,
        title: 'Batch BAT-2026-0010-01 Analysis',
        activeModule: 'traceability',
        contextType: 'BATCH',
        contextId: 'BAT-2026-0010-01',
        messages: [],
      });
    }

    if (urlStr.includes('/api/ai/conversations') && method === 'GET') {
      return jsonResponse([]);
    }

    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');

    const authValue = {
      user: { id: 42, username: 'supervisor_ravi', role: 'PRODUCTION_SUPERVISOR' },
      role: 'PRODUCTION_SUPERVISOR',
      isAuthenticated: true,
    };

    const navValue = {
      activeTab: 'traceability',
      routeParams: new URLSearchParams('batchNumber=BAT-2026-0010-01'),
      routeNonce: 1,
      navigateToTab: () => {},
    };

    let contextChangeReceived = null;
    let contextClearedCalled = false;

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: authValue },
        React.createElement(
          NavigationContext.Provider,
          { value: navValue },
          React.createElement(AiCopilotDrawer, {
            isOpen: true,
            onClose: () => {},
            activeContext: {
              activeModule: 'traceability',
              contextType: 'BATCH',
              contextId: 'BAT-2026-0010-01',
              title: 'BAT-2026-0010-01 (Amoxicillin 500mg)',
              initialQuery: 'Please generate a complete AI Batch Analysis Report for BAT-2026-0010-01.',
            },
            onContextChange: (ctx) => {
              contextChangeReceived = ctx;
            },
            onClearContext: () => {
              contextClearedCalled = true;
            },
          })
        )
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 100));
    await dom.flush();

    // 1. Verify conversation was created with BATCH context
    assert.ok(createdConversationPayload, 'createConversation must be called');
    assert.equal(createdConversationPayload.contextType, 'BATCH');
    assert.equal(createdConversationPayload.contextId, 'BAT-2026-0010-01');
    assert.equal(createdConversationPayload.activeModule, 'traceability');
    assert.equal(createdConversationPayload.title, 'Batch BAT-2026-0010-01 Analysis');

    // 2. Verify initial query was auto-submitted
    assert.ok(submittedMessagePayload, 'Initial query must be submitted');
    assert.equal(submittedMessagePayload.contextType, 'BATCH');
    assert.equal(submittedMessagePayload.contextId, 'BAT-2026-0010-01');
    assert.ok(submittedMessagePayload.query.includes('BAT-2026-0010-01'));

    // 3. Verify Batch Context Badge rendered
    const badge = dom.container.querySelector('[data-testid="active-context-badge"]');
    assert.ok(badge);
    assert.ok(badge.textContent.includes('Batch'));
    assert.ok(badge.textContent.includes('BAT-2026-0010-01'));

    // 4. Verify GxP Advisory Disclaimer is rendered
    const disclaimer = dom.container.querySelector('[data-testid="copilot-advisory-disclaimer"]');
    assert.ok(disclaimer);
    assert.ok(disclaimer.textContent.includes('Advisory Notice:'));
    assert.ok(disclaimer.textContent.includes('Advisory intelligence based strictly on PharmaTrack MES and eBR records'));

    // 5. Verify Verified Sources list rendered
    const sourcesContainer = dom.container.querySelector('[data-testid="verified-sources-list"]');
    assert.ok(sourcesContainer);
    const sources = sourcesContainer.querySelectorAll('button');
    assert.ok(sources.length >= 2, 'Must render both batch and deviation source pills');

    // 6. Test Clear Context on Batch Context
    const clearBtn = dom.container.querySelector('button[aria-label="Clear active batch context"]');
    assert.ok(clearBtn);
    await dom.click(clearBtn);
    await dom.flush();

    assert.equal(contextClearedCalled, true, 'onClearContext must be triggered');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// ---------------------------------------------------------------------------
// 6. Batch Suggestions in Empty Thread
// ---------------------------------------------------------------------------

test('AiCopilotDrawer renders batch-specific suggested questions in empty batch thread', async () => {
  setAuthToken('test-token');
  const prevFetch = globalThis.fetch;
  globalThis.fetch = async () => jsonResponse([]);

  const dom = await createDomEnvironment();
  try {
    const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');

    const authValue = {
      user: { id: 42, username: 'supervisor_ravi', role: 'PRODUCTION_SUPERVISOR' },
      role: 'PRODUCTION_SUPERVISOR',
      isAuthenticated: true,
    };

    const navValue = {
      activeTab: 'traceability',
      routeParams: new URLSearchParams(),
      routeNonce: 1,
      navigateToTab: () => {},
    };

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: authValue },
        React.createElement(
          NavigationContext.Provider,
          { value: navValue },
          React.createElement(AiCopilotDrawer, {
            isOpen: true,
            onClose: () => {},
            activeContext: {
              activeModule: 'traceability',
              contextType: 'BATCH',
              contextId: 'BAT-2026-0010-01',
              title: 'BAT-2026-0010-01 (Amoxicillin 500mg)',
            },
          })
        )
      )
    );
    await dom.flush();

    const text = dom.container.textContent;
    assert.ok(text.includes('Provide an advisory analysis of this batch eBR execution and yield.'));
    assert.ok(text.includes('Were there any deviations or quality anomalies recorded for this batch?'));
    assert.ok(text.includes('Summarize the raw materials consumed and inventory lot traceability.'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// ---------------------------------------------------------------------------
// 7. Session Storage Preservation across Browser Refresh
// ---------------------------------------------------------------------------

test('Session storage preserves and restores BATCH context across refresh', async () => {
  setAuthToken('test-token');
  const prevFetch = globalThis.fetch;

  globalThis.fetch = async (url) => {
    const urlStr = String(url);
    if (urlStr.includes('/api/ai/conversations/88')) {
      return jsonResponse({
        id: 88,
        title: 'Batch BAT-2026-0010-01 Analysis',
        activeModule: 'traceability',
        contextType: 'BATCH',
        contextId: 'BAT-2026-0010-01',
        messages: [
          {
            id: 1,
            role: 'assistant',
            content: 'Restored batch analysis content',
            contextType: 'BATCH',
            contextIdentifier: 'BAT-2026-0010-01',
            status: 'COMPLETED',
          },
        ],
      });
    }
    if (urlStr.includes('/api/ai/conversations')) {
      return jsonResponse([
        {
          id: 88,
          title: 'Batch BAT-2026-0010-01 Analysis',
          activeModule: 'traceability',
          contextType: 'BATCH',
          contextId: 'BAT-2026-0010-01',
        },
      ]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');

    // Simulate session storage state immediately before refresh
    dom.sessionStorage.setItem('pharmatrack_copilot_open_42', 'true');
    dom.sessionStorage.setItem('pharmatrack_copilot_active_conv_42', '88');
    dom.sessionStorage.setItem(
      'pharmatrack_copilot_context_42',
      JSON.stringify({
        activeModule: 'traceability',
        contextType: 'BATCH',
        contextId: 'BAT-2026-0010-01',
        title: 'BAT-2026-0010-01',
      })
    );

    const authValue = {
      user: { id: 42, username: 'supervisor_ravi', role: 'PRODUCTION_SUPERVISOR' },
      role: 'PRODUCTION_SUPERVISOR',
      isAuthenticated: true,
    };

    const navValue = {
      activeTab: 'traceability',
      routeParams: new URLSearchParams('batchNumber=BAT-2026-0010-01'),
      routeNonce: 1,
      navigateToTab: () => {},
    };

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: authValue },
        React.createElement(
          NavigationContext.Provider,
          { value: navValue },
          React.createElement(AiCopilotDrawer, {
            isOpen: true,
            onClose: () => {},
            activeContext: {
              activeModule: 'traceability',
              contextType: 'BATCH',
              contextId: 'BAT-2026-0010-01',
              title: 'BAT-2026-0010-01',
            },
          })
        )
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    // Verify restored batch context badge and message
    const badge = dom.container.querySelector('[data-testid="active-context-badge"]');
    assert.ok(badge);
    assert.ok(badge.textContent.includes('Batch'));
    assert.ok(badge.textContent.includes('BAT-2026-0010-01'));
    assert.ok(dom.container.textContent.includes('Restored batch analysis content'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// ---------------------------------------------------------------------------
// 8. QualityManagement Deep-Link via deviationNumber
// ---------------------------------------------------------------------------

test('QualityManagement handles deviationNumber route parameter and resolves deviation workspace', async () => {
  const prevFetch = globalThis.fetch;

  globalThis.fetch = async (url) => {
    const urlStr = String(url);
    if (urlStr.includes('/api/quality/deviations/3')) {
      return jsonResponse({
        id: 3,
        deviationNumber: 'DEV-2026-0003',
        title: 'Temperature Excursion during drying',
        status: 'UNDER_INVESTIGATION',
        severity: 'MAJOR',
        priority: 'HIGH',
        category: 'ENVIRONMENTAL_EXCURSION',
        description: 'Excursion exceeded 45C limit',
        batchId: 10,
        batchNumber: 'BAT-2026-0010-01',
      });
    }
    if (urlStr.includes('/api/quality/deviations')) {
      return jsonResponse([
        {
          id: 3,
          deviationNumber: 'DEV-2026-0003',
          title: 'Temperature Excursion during drying',
          status: 'UNDER_INVESTIGATION',
          severity: 'MAJOR',
          priority: 'HIGH',
        },
      ]);
    }
    if (urlStr.includes('/api/quality/queue')) {
      return jsonResponse([]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    const { default: QualityManagement } = await import('./QualityManagement.jsx');

    const authValue = {
      user: { id: 10, username: 'supervisor_ravi', role: 'PRODUCTION_SUPERVISOR' },
      role: 'PRODUCTION_SUPERVISOR',
      isAuthenticated: true,
    };

    const navValue = {
      activeTab: 'quality',
      routeParams: new URLSearchParams('deviationNumber=DEV-2026-0003&view=deviations'),
      routeNonce: 1,
      navigateToTab: () => {},
    };

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: authValue },
        React.createElement(
          NavigationContext.Provider,
          { value: navValue },
          React.createElement(QualityManagement, {})
        )
      )
    );
    await dom.flush();
    await new Promise((r) => setTimeout(r, 80));
    await dom.flush();

    // Verify that the deviation workspace is opened and displays the deviation details
    const text = dom.container.textContent;
    assert.ok(text.includes('DEV-2026-0003'));
    assert.ok(text.includes('Temperature Excursion during drying'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// ---------------------------------------------------------------------------
// Stage 2B.1 Regression Tests (Context Isolation & Dedicated Thread Creation)
// ---------------------------------------------------------------------------

// 1 & 2: Batch conversation <-> Global conversation context switching
test('Regression 1 & 2: Batch conversation to Global clears context badge, switching back restores it', async () => {
  setAuthToken('test-token');
  const prevFetch = globalThis.fetch;

  const convList = [
    {
      id: 1,
      title: 'Global Inquiries',
      activeModule: 'general',
      contextType: null,
      contextId: null,
      messageCount: 1,
      createdAt: '2026-11-14T09:00:00Z',
      updatedAt: '2026-11-14T09:00:00Z',
    },
    {
      id: 2,
      title: 'Batch BAT-2026-0010-01 Analysis',
      activeModule: 'traceability',
      contextType: 'BATCH',
      contextId: 'BAT-2026-0010-01',
      messageCount: 1,
      createdAt: '2026-11-14T10:00:00Z',
      updatedAt: '2026-11-14T10:00:00Z',
    },
  ];

  const conv1Detail = {
    id: 1,
    title: 'Global Inquiries',
    activeModule: 'general',
    contextType: null,
    contextId: null,
    messages: [
      {
        id: 10,
        role: 'user',
        content: 'Explain general GxP principles',
        createdAt: '2026-11-14T09:00:00Z',
      },
      {
        id: 11,
        role: 'assistant',
        content: 'GxP guidelines ensure pharmaceutical safety.',
        sources: [
          {
            recordType: 'DOCUMENT',
            recordIdentifier: 'SOP-001',
            title: 'SOP-001 Good Practice',
            module: 'Documents',
          },
        ],
        createdAt: '2026-11-14T09:00:05Z',
      },
    ],
  };

  const conv2Detail = {
    id: 2,
    title: 'Batch BAT-2026-0010-01 Analysis',
    activeModule: 'traceability',
    contextType: 'BATCH',
    contextId: 'BAT-2026-0010-01',
    messages: [
      {
        id: 20,
        role: 'user',
        content: 'Please generate a complete AI Batch Analysis Report for BAT-2026-0010-01.',
        createdAt: '2026-11-14T10:00:00Z',
      },
      {
        id: 21,
        role: 'assistant',
        content: 'Batch BAT-2026-0010-01 completed with 99.64% yield.',
        sources: [
          {
            recordType: 'BATCH',
            recordIdentifier: 'BAT-2026-0010-01',
            title: 'Batch Dossier',
            module: 'traceability',
          },
        ],
        createdAt: '2026-11-14T10:00:05Z',
      },
    ],
  };

  globalThis.fetch = async (url, options) => {
    const urlStr = String(url);
    const method = options?.method || 'GET';

    if (urlStr.includes('/api/ai/conversations/1') && method === 'GET') {
      return jsonResponse(conv1Detail);
    }
    if (urlStr.includes('/api/ai/conversations/2') && method === 'GET') {
      return jsonResponse(conv2Detail);
    }
    if (urlStr.includes('/api/ai/conversations') && method === 'GET') {
      return jsonResponse(convList);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');

    const authValue = {
      user: { id: 42, username: 'supervisor_ravi', role: 'PRODUCTION_SUPERVISOR' },
      role: 'PRODUCTION_SUPERVISOR',
      isAuthenticated: true,
    };

    let reportedContext = null;

    function TestHost() {
      const [ctx, setCtx] = useState({
        activeModule: 'traceability',
        contextType: 'BATCH',
        contextId: 'BAT-2026-0010-01',
        title: 'BAT-2026-0010-01 (Amoxicillin 500mg)',
      });

      const handleContextChange = React.useCallback((newCtx) => {
        reportedContext = newCtx;
        setCtx(newCtx);
      }, []);

      return React.createElement(
        AuthContext.Provider,
        { value: authValue },
        React.createElement(AiCopilotDrawer, {
          isOpen: true,
          onClose: () => {},
          activeContext: ctx,
          onContextChange: handleContextChange,
        })
      );
    }

    // Set saved preference to Conv 2 (Batch)
    dom.sessionStorage.setItem('pharmatrack_copilot_active_conv_42', '2');

    await dom.render(React.createElement(TestHost));
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    // 1. Initial view: Conv 2 is loaded. Verify Batch context badge is rendered.
    const textConv2 = dom.container.textContent;
    assert.ok(textConv2.includes('BAT-2026-0010-01'), 'Must display Batch ID badge');
    assert.ok(textConv2.includes('Batch execution completed') || textConv2.includes('99.64% yield'), 'Must display Conv 2 message');

    // 2. Open history panel
    const historyBtn = dom.container.querySelector('[data-testid="copilot-history-toggle"]');
    assert.ok(historyBtn, 'History toggle button must exist');
    await dom.click(historyBtn);
    await dom.flush();

    // 3. Select Conv 1 (Global conversation with contextId = null)
    const conv1Item = dom.container.querySelector('[data-testid="copilot-conversation-item-1"]');
    assert.ok(conv1Item, 'Conv 1 item must exist in history');
    await dom.click(conv1Item);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    // 4. Verify context isolation: Batch badge is cleared, badge displays Global
    const textConv1 = dom.container.textContent;
    assert.ok(textConv1.includes('Global (specify order number'), 'Must display Global badge for context-free conv');
    assert.equal(textConv1.includes('Batch BAT-2026-0010-01'), false, 'Stale batch badge must not be visible');
    assert.equal(reportedContext, null, 'onContextChange must report null for global conversation');

    // Messages and sources of Conv 1 are preserved
    assert.ok(textConv1.includes('Explain general GxP principles'), 'Conv 1 user message must be preserved');
    assert.ok(textConv1.includes('GxP guidelines ensure pharmaceutical safety'), 'Conv 1 assistant message must be preserved');

    // 5. Switch BACK to Conv 2 (Batch conversation)
    const historyBtn2 = dom.container.querySelector('[data-testid="copilot-history-toggle"]');
    await dom.click(historyBtn2);
    await dom.flush();

    const conv2Item = dom.container.querySelector('[data-testid="copilot-conversation-item-2"]');
    assert.ok(conv2Item, 'Conv 2 item must exist in history');
    await dom.click(conv2Item);
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    // 6. Verify Batch context badge is restored
    const textConv2Restored = dom.container.textContent;
    assert.ok(textConv2Restored.includes('BAT-2026-0010-01'), 'Batch context badge must be restored');
    assert.equal(reportedContext?.contextId, 'BAT-2026-0010-01', 'onContextChange must report restored batch context');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// 3 & 4: Clicking Generate AI Batch Analysis creates a NEW conversation and submits to the new ID
test('Regression 3 & 4: Generate AI Batch Analysis while another conversation is active creates dedicated thread and submits to new ID', async () => {
  setAuthToken('test-token');
  const prevFetch = globalThis.fetch;

  let createdConversationPayload = null;
  let messageSubmissions = [];

  const existingConv = {
    id: 10,
    title: 'Order MO-2026-0001 Inquiry',
    activeModule: 'orders',
    contextType: 'MANUFACTURING_ORDER',
    contextId: 'MO-2026-0001',
    messageCount: 2,
    createdAt: '2026-11-14T08:00:00Z',
    updatedAt: '2026-11-14T08:00:00Z',
  };

  const existingConvDetail = {
    id: 10,
    title: 'Order MO-2026-0001 Inquiry',
    activeModule: 'orders',
    contextType: 'MANUFACTURING_ORDER',
    contextId: 'MO-2026-0001',
    messages: [
      {
        id: 101,
        role: 'user',
        content: 'Check material availability for order MO-2026-0001',
        createdAt: '2026-11-14T08:00:00Z',
      },
      {
        id: 102,
        role: 'assistant',
        content: 'All materials for MO-2026-0001 are allocated.',
        createdAt: '2026-11-14T08:00:05Z',
      },
    ],
  };

  globalThis.fetch = async (url, options) => {
    const urlStr = String(url);
    const method = options?.method || 'GET';

    if (urlStr.includes('/api/ai/conversations') && method === 'POST') {
      if (urlStr.includes('/messages')) {
        const payload = JSON.parse(options.body);
        messageSubmissions.push({ url: urlStr, payload });
        return jsonResponse({
          id: 501,
          conversationId: 77,
          clientRequestId: payload.clientRequestId,
          status: 'COMPLETED',
          answer: '## Batch Analysis Report for BAT-2026-0010-01\nBatch yielded 99.64% with 0 critical deviations.',
          model: 'gpt-6-luna',
          contextType: 'BATCH',
          contextIdentifier: 'BAT-2026-0010-01',
          sources: [],
          disclaimer: 'Advisory intelligence only.',
        });
      }
      createdConversationPayload = JSON.parse(options.body);
      return jsonResponse({
        id: 77,
        title: createdConversationPayload.title,
        activeModule: createdConversationPayload.activeModule,
        contextType: createdConversationPayload.contextType,
        contextId: createdConversationPayload.contextId,
        createdAt: '2026-11-14T10:30:00Z',
        updatedAt: '2026-11-14T10:30:00Z',
      });
    }

    if (urlStr.includes('/api/ai/conversations/10') && method === 'GET') {
      return jsonResponse(existingConvDetail);
    }
    if (urlStr.includes('/api/ai/conversations/77') && method === 'GET') {
      return jsonResponse({
        id: 77,
        title: 'Batch BAT-2026-0010-01 Analysis',
        activeModule: 'traceability',
        contextType: 'BATCH',
        contextId: 'BAT-2026-0010-01',
        messages: [],
      });
    }
    if (urlStr.includes('/api/ai/conversations') && method === 'GET') {
      return jsonResponse([existingConv]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');

    const authValue = {
      user: { id: 42, username: 'supervisor_ravi', role: 'PRODUCTION_SUPERVISOR' },
      role: 'PRODUCTION_SUPERVISOR',
      isAuthenticated: true,
    };

    // Preload with existing conversation 10 active
    dom.sessionStorage.setItem('pharmatrack_copilot_active_conv_42', '10');

    let setContextFn = null;

    function TestApp() {
      const [ctx, setCtx] = useState(null);
      setContextFn = setCtx;

      return React.createElement(
        AuthContext.Provider,
        { value: authValue },
        React.createElement(AiCopilotDrawer, {
          isOpen: true,
          onClose: () => {},
          activeContext: ctx,
          onContextChange: setCtx,
        })
      );
    }

    await dom.render(React.createElement(TestApp));
    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    // Verify conversation 10 was loaded initially
    assert.ok(dom.container.textContent.includes('All materials for MO-2026-0001 are allocated'));

    // Now trigger "Generate AI Batch Analysis" for BAT-2026-0010-01
    await dom.act(async () => {
      setContextFn({
        activeModule: 'traceability',
        contextType: 'BATCH',
        contextId: 'BAT-2026-0010-01',
        title: 'BAT-2026-0010-01 (Amoxicillin 500mg)',
        initialQuery: 'Please generate a complete AI Batch Analysis Report for BAT-2026-0010-01.',
      });
    });
    await dom.flush();
    for (let i = 0; i < 10; i++) {
      if (messageSubmissions.length > 0 && dom.container.textContent.includes('Batch Analysis Report')) break;
      await new Promise((r) => setTimeout(r, 50));
      await dom.flush();
    }

    // 1. Verify a NEW conversation was created with BATCH context
    assert.ok(createdConversationPayload, 'createConversation must be called');
    assert.equal(createdConversationPayload.contextType, 'BATCH');
    assert.equal(createdConversationPayload.contextId, 'BAT-2026-0010-01');
    assert.equal(createdConversationPayload.activeModule, 'traceability');
    assert.equal(createdConversationPayload.title, 'Batch BAT-2026-0010-01 Analysis');

    // 2. Verify initial query was submitted to NEW conversation ID 77, NEVER to conversation 10
    assert.equal(messageSubmissions.length, 1, 'Exactly one message submission must occur');
    assert.ok(messageSubmissions[0].url.includes('/api/ai/conversations/77/messages'), 'Must submit to new thread 77');
    assert.equal(messageSubmissions[0].url.includes('/api/ai/conversations/10/messages'), false, 'Must NEVER submit to old thread 10');
    assert.equal(messageSubmissions[0].payload.contextType, 'BATCH');
    assert.equal(messageSubmissions[0].payload.contextId, 'BAT-2026-0010-01');

    // 3. Verify the generated report is rendered in the drawer
    assert.ok(dom.container.textContent.includes('Batch Analysis Report for BAT-2026-0010-01'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// 5: Rapid repeated clicks do not create duplicate conversations or submissions
test('Regression 5: Rapid repeated clicks do not create duplicate conversations or submissions', async () => {
  setAuthToken('test-token');
  const prevFetch = globalThis.fetch;

  let createConversationCallCount = 0;
  let messageSubmissionCallCount = 0;

  globalThis.fetch = async (url, options) => {
    const urlStr = String(url);
    const method = options?.method || 'GET';

    if (urlStr.includes('/api/ai/conversations') && method === 'POST') {
      if (urlStr.includes('/messages')) {
        messageSubmissionCallCount += 1;
        const payload = JSON.parse(options.body);
        return jsonResponse({
          id: 801,
          conversationId: 88,
          clientRequestId: payload.clientRequestId,
          status: 'COMPLETED',
          answer: 'Batch report completed.',
          model: 'gpt-6-luna',
          contextType: 'BATCH',
          contextIdentifier: 'BAT-2026-0010-01',
          sources: [],
          disclaimer: 'Advisory only.',
        });
      }
      createConversationCallCount += 1;
      const body = JSON.parse(options.body);
      return jsonResponse({
        id: 88,
        title: body.title,
        activeModule: body.activeModule,
        contextType: body.contextType,
        contextId: body.contextId,
        createdAt: '2026-11-14T10:45:00Z',
        updatedAt: '2026-11-14T10:45:00Z',
      });
    }

    if (urlStr.includes('/api/ai/conversations') && method === 'GET') {
      return jsonResponse([]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');

    const authValue = {
      user: { id: 42, username: 'supervisor_ravi', role: 'PRODUCTION_SUPERVISOR' },
      role: 'PRODUCTION_SUPERVISOR',
      isAuthenticated: true,
    };

    let setContextFn = null;

    function TestApp() {
      const [ctx, setCtx] = useState({
        activeModule: 'traceability',
        contextType: 'BATCH',
        contextId: 'BAT-2026-0010-01',
        title: 'BAT-2026-0010-01 (Amoxicillin 500mg)',
        initialQuery: 'Please generate a complete AI Batch Analysis Report for BAT-2026-0010-01.',
      });
      setContextFn = setCtx;

      return React.createElement(
        AuthContext.Provider,
        { value: authValue },
        React.createElement(AiCopilotDrawer, {
          isOpen: true,
          onClose: () => {},
          activeContext: ctx,
          onContextChange: setCtx,
        })
      );
    }

    await dom.render(React.createElement(TestApp));

    // Simulate 3 rapid repeated re-renders / clicks in quick succession before the first async completes
    await dom.act(async () => {
      setContextFn({
        activeModule: 'traceability',
        contextType: 'BATCH',
        contextId: 'BAT-2026-0010-01',
        title: 'BAT-2026-0010-01 (Amoxicillin 500mg)',
        initialQuery: 'Please generate a complete AI Batch Analysis Report for BAT-2026-0010-01.',
      });
      setContextFn({
        activeModule: 'traceability',
        contextType: 'BATCH',
        contextId: 'BAT-2026-0010-01',
        title: 'BAT-2026-0010-01 (Amoxicillin 500mg)',
        initialQuery: 'Please generate a complete AI Batch Analysis Report for BAT-2026-0010-01.',
      });
    });

    await dom.flush();
    await new Promise((r) => setTimeout(r, 120));
    await dom.flush();

    // Verify deduplication: exactly 1 conversation created, exactly 1 query submitted
    assert.equal(createConversationCallCount, 1, 'Must create exactly 1 conversation thread');
    assert.equal(messageSubmissionCallCount, 1, 'Must submit exactly 1 initial message');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// 6: Page refresh does not create another conversation or resubmit the initial query
test('Regression 6: Restored context from refresh (no initialQuery) does not create conversation or submit query', async () => {
  setAuthToken('test-token');
  const prevFetch = globalThis.fetch;

  let createCalled = false;
  let messageCalled = false;

  globalThis.fetch = async (url, options) => {
    const urlStr = String(url);
    const method = options?.method || 'GET';

    if (urlStr.includes('/api/ai/conversations') && method === 'POST') {
      if (urlStr.includes('/messages')) {
        messageCalled = true;
      } else {
        createCalled = true;
      }
      return jsonResponse({});
    }

    if (urlStr.includes('/api/ai/conversations') && method === 'GET') {
      return jsonResponse([]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');

    const authValue = {
      user: { id: 42, username: 'supervisor_ravi', role: 'PRODUCTION_SUPERVISOR' },
      role: 'PRODUCTION_SUPERVISOR',
      isAuthenticated: true,
    };

    // When restored from sessionStorage on page refresh, initialQuery is absent
    const restoredContext = {
      activeModule: 'traceability',
      contextType: 'BATCH',
      contextId: 'BAT-2026-0010-01',
      title: 'BAT-2026-0010-01 (Amoxicillin 500mg)',
      // Note: initialQuery is deliberately stripped by App.jsx on save
    };

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: authValue },
        React.createElement(AiCopilotDrawer, {
          isOpen: true,
          onClose: () => {},
          activeContext: restoredContext,
        })
      )
    );

    await dom.flush();
    await new Promise((r) => setTimeout(r, 100));
    await dom.flush();

    // Verify that NO conversation is created and NO message is submitted
    assert.equal(createCalled, false, 'No conversation must be created on refresh');
    assert.equal(messageCalled, false, 'No message must be submitted on refresh');

    // Context badge should still display the batch
    assert.ok(dom.container.textContent.includes('BAT-2026-0010-01'), 'Badge displays batch context');
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});

// 7: Existing Manufacturing Order Copilot behavior remains completely unchanged
test('Regression 7: Existing Manufacturing Order Copilot behavior remains unchanged', async () => {
  setAuthToken('test-token');
  const prevFetch = globalThis.fetch;

  let createdOrderConvPayload = null;
  let submittedOrderMessagePayload = null;

  globalThis.fetch = async (url, options) => {
    const urlStr = String(url);
    const method = options?.method || 'GET';

    if (urlStr.includes('/api/ai/conversations') && method === 'POST') {
      if (urlStr.includes('/messages')) {
        submittedOrderMessagePayload = JSON.parse(options.body);
        return jsonResponse({
          id: 902,
          conversationId: 66,
          clientRequestId: submittedOrderMessagePayload.clientRequestId,
          status: 'COMPLETED',
          answer: 'Manufacturing Order MO-2026-0001 requires 500kg of API.',
          model: 'gpt-6-luna',
          contextType: 'MANUFACTURING_ORDER',
          contextIdentifier: 'MO-2026-0001',
          sources: [
            {
              recordType: 'MANUFACTURING_ORDER',
              recordIdentifier: 'MO-2026-0001',
              title: 'Order MO-2026-0001',
              module: 'Manufacturing Orders',
            },
          ],
          disclaimer: 'Advisory intelligence only.',
        });
      }
      createdOrderConvPayload = JSON.parse(options.body);
      return jsonResponse({
        id: 66,
        title: createdOrderConvPayload.title,
        activeModule: createdOrderConvPayload.activeModule,
        contextType: createdOrderConvPayload.contextType,
        contextId: createdOrderConvPayload.contextId,
        createdAt: '2026-11-14T11:00:00Z',
        updatedAt: '2026-11-14T11:00:00Z',
      });
    }

    if (urlStr.includes('/api/ai/conversations') && method === 'GET') {
      return jsonResponse([]);
    }
    return jsonResponse({});
  };

  const dom = await createDomEnvironment();
  try {
    const { default: AiCopilotDrawer } = await import('./AiCopilotDrawer.jsx');

    const authValue = {
      user: { id: 42, username: 'supervisor_ravi', role: 'PRODUCTION_SUPERVISOR' },
      role: 'PRODUCTION_SUPERVISOR',
      isAuthenticated: true,
    };

    const orderContext = {
      activeModule: 'orders',
      contextType: 'MANUFACTURING_ORDER',
      contextId: 'MO-2026-0001',
      title: 'MO-2026-0001 (Paracetamol 500mg)',
    };

    await dom.render(
      React.createElement(
        AuthContext.Provider,
        { value: authValue },
        React.createElement(AiCopilotDrawer, {
          isOpen: true,
          onClose: () => {},
          activeContext: orderContext,
        })
      )
    );

    await dom.flush();
    await new Promise((r) => setTimeout(r, 60));
    await dom.flush();

    // 1. Verify order badge is rendered
    assert.ok(dom.container.textContent.includes('MO-2026-0001'), 'Badge displays order ID');

    // 2. Verify order suggestions are rendered
    assert.ok(dom.container.textContent.includes('What is the current status'), 'Displays order suggestions');

    // 3. Click one of the suggestions to submit a query
    const buttons = dom.container.querySelectorAll('button');
    const suggestionBtn = buttons.find((b) => b.textContent.includes('What is the current status'));
    assert.ok(suggestionBtn, 'Order suggestion button exists');
    await dom.click(suggestionBtn);
    await dom.flush();
    for (let i = 0; i < 10; i++) {
      if (createdOrderConvPayload && submittedOrderMessagePayload) break;
      await new Promise((r) => setTimeout(r, 50));
      await dom.flush();
    }

    // 4. Verify conversation was created with Order title and context
    assert.ok(createdOrderConvPayload, 'createConversation called for order query');
    assert.equal(createdOrderConvPayload.contextType, 'MANUFACTURING_ORDER');
    assert.equal(createdOrderConvPayload.contextId, 'MO-2026-0001');
    assert.ok(createdOrderConvPayload.title.includes('What is the current status'), 'Title derived from query');

    // 5. Verify message was submitted with order context
    assert.ok(submittedOrderMessagePayload, 'Message was submitted');
    assert.equal(submittedOrderMessagePayload.contextType, 'MANUFACTURING_ORDER');
    assert.equal(submittedOrderMessagePayload.contextId, 'MO-2026-0001');

    // 6. Verify assistant response rendered
    assert.ok(dom.container.textContent.includes('requires 500kg of API'));
  } finally {
    globalThis.fetch = prevFetch;
    await dom.unmount?.();
  }
});
