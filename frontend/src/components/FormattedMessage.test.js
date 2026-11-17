import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { createDomEnvironment } from '../../scripts/dom-harness.mjs';

test('FormattedMessage renders bold, italic, and bold-italic styling safely', async () => {
  const { default: FormattedMessage } = await import('./FormattedMessage.jsx');
  const dom = await createDomEnvironment();

  try {
    const markdown = 'Order **MO-2026-0001** is *in progress* with ***high priority*** status.';
    await dom.render(React.createElement(FormattedMessage, { content: markdown }));
    await dom.flush();

    const strongElements = dom.container.querySelectorAll('strong');
    const emElements = dom.container.querySelectorAll('em');

    assert.ok(strongElements.length >= 2);
    assert.equal(strongElements[0].textContent, 'MO-2026-0001');

    assert.ok(emElements.length >= 2);
    assert.equal(emElements[0].textContent, 'in progress');
    assert.equal(emElements[1].textContent, 'high priority');
  } finally {
    await dom.unmount?.();
  }
});

test('FormattedMessage renders headings H1, H2, and H3 correctly', async () => {
  const { default: FormattedMessage } = await import('./FormattedMessage.jsx');
  const dom = await createDomEnvironment();

  try {
    const markdown = `
# Production Overview
## Manufacturing Order MO-2026-0001
### Child Batch Progress
`;
    await dom.render(React.createElement(FormattedMessage, { content: markdown }));
    await dom.flush();

    const h1 = dom.container.querySelector('h1');
    const h2 = dom.container.querySelector('h2');
    const h3 = dom.container.querySelector('h3');

    assert.ok(h1);
    assert.equal(h1.textContent, 'Production Overview');
    assert.ok(h2);
    assert.equal(h2.textContent, 'Manufacturing Order MO-2026-0001');
    assert.ok(h3);
    assert.equal(h3.textContent, 'Child Batch Progress');
  } finally {
    await dom.unmount?.();
  }
});

test('FormattedMessage renders unordered and ordered lists', async () => {
  const { default: FormattedMessage } = await import('./FormattedMessage.jsx');
  const dom = await createDomEnvironment();

  try {
    const markdown = `
Key milestones:
- Batch 1 completed successfully
- Batch 2 undergoing packaging

Execution steps:
1. Dispensing verified
2. Granulation started
3. Compression scheduled
`;
    await dom.render(React.createElement(FormattedMessage, { content: markdown }));
    await dom.flush();

    const ul = dom.container.querySelector('ul');
    const ol = dom.container.querySelector('ol');

    assert.ok(ul);
    const ulItems = ul.querySelectorAll('li');
    assert.equal(ulItems.length, 2);
    assert.ok(ulItems[0].textContent.includes('Batch 1 completed successfully'));

    assert.ok(ol);
    const olItems = ol.querySelectorAll('li');
    assert.equal(olItems.length, 3);
    assert.ok(olItems[0].textContent.includes('Dispensing verified'));
  } finally {
    await dom.unmount?.();
  }
});

test('FormattedMessage splits run-on inline numbered recommendations into separate ordered list items', async () => {
  const { default: FormattedMessage } = await import('./FormattedMessage.jsx');
  const dom = await createDomEnvironment();

  try {
    const runOnText = '1. Initiate batch production and record Batch Start Time. 2. Complete all EBR steps with operator sign-offs. 3. Perform required QC in-process tests. 4. Capture actual yield.';
    await dom.render(React.createElement(FormattedMessage, { content: runOnText }));
    await dom.flush();

    const ol = dom.container.querySelector('ol');
    assert.ok(ol, 'Should render an ordered list element');
    const items = ol.querySelectorAll('li');
    assert.equal(items.length, 4, 'Should parse exactly 4 list items');
    assert.ok(items[0].textContent.includes('Initiate batch production'));
    assert.ok(items[1].textContent.includes('Complete all EBR steps'));
    assert.ok(items[2].textContent.includes('Perform required QC'));
    assert.ok(items[3].textContent.includes('Capture actual yield'));
  } finally {
    await dom.unmount?.();
  }
});

test('FormattedMessage renders structured responsive tables', async () => {
  const { default: FormattedMessage } = await import('./FormattedMessage.jsx');
  const dom = await createDomEnvironment();

  try {
    const markdown = `
| Batch Number | Batch Size | Actual Yield | QA Status |
| --- | --- | --- | --- |
| B-2026-0001 | 500.00 kg | 495.50 kg | APPROVED |
| B-2026-0002 | 500.00 kg | In progress | PENDING_QA |
`;
    await dom.render(React.createElement(FormattedMessage, { content: markdown }));
    await dom.flush();

    const table = dom.container.querySelector('table');
    assert.ok(table);

    const headers = table.querySelectorAll('th');
    assert.equal(headers.length, 4);
    assert.equal(headers[0].textContent, 'Batch Number');
    assert.equal(headers[1].textContent, 'Batch Size');
    assert.equal(headers[2].textContent, 'Actual Yield');
    assert.equal(headers[3].textContent, 'QA Status');

    const tbody = table.querySelector('tbody');
    assert.ok(tbody);
    const rows = tbody.querySelectorAll('tr');
    assert.equal(rows.length, 2);

    const cellsRow1 = rows[0].querySelectorAll('td');
    assert.equal(cellsRow1[0].textContent, 'B-2026-0001');
    assert.equal(cellsRow1[1].textContent, '500.00 kg');
    assert.equal(cellsRow1[2].textContent, '495.50 kg');
    assert.equal(cellsRow1[3].textContent, 'APPROVED');
  } finally {
    await dom.unmount?.();
  }
});

test('FormattedMessage renders inline code, code blocks, and math expressions', async () => {
  const { default: FormattedMessage } = await import('./FormattedMessage.jsx');
  const dom = await createDomEnvironment();

  try {
    const markdown = `
Calculate yield percentage with $(49,800 / 50,000) \\times 100 = 99.6%$.
Use code \`MO-2026-0001\` for lookups.

\`\`\`json
{
  "orderNumber": "MO-2026-0001",
  "yieldPct": 99.6
}
\`\`\`
`;
    await dom.render(React.createElement(FormattedMessage, { content: markdown }));
    await dom.flush();

    // Inline code
    const inlineCodes = dom.container.querySelectorAll('code');
    assert.ok(inlineCodes.length >= 2); // 1 inline + 1 in block
    assert.equal(inlineCodes[0].textContent, 'MO-2026-0001');

    // Code block
    const pre = dom.container.querySelector('pre');
    assert.ok(pre);
    assert.ok(pre.textContent.includes('"orderNumber": "MO-2026-0001"'));

    // Math expression
    const mathSpan = dom.container.querySelector('span[data-testid="math-expr"]');
    assert.ok(mathSpan);
    assert.ok(mathSpan.textContent.includes('49,800 / 50,000'));
  } finally {
    await dom.unmount?.();
  }
});

test('FormattedMessage safely escapes untrusted raw HTML without executing scripts', async () => {
  const { default: FormattedMessage } = await import('./FormattedMessage.jsx');
  const dom = await createDomEnvironment();

  try {
    const malicious = `
Here is a test: <script>window.__xss_triggered = true;</script>
And an image payload: <img src="x" onerror="window.__xss_triggered = true;" />
`;
    await dom.render(React.createElement(FormattedMessage, { content: malicious }));
    await dom.flush();

    // Verify no script tag or executable img tag exists in DOM
    const scripts = dom.container.querySelectorAll('script');
    assert.equal(scripts.length, 0);

    // Verify raw HTML was treated as safe plain text string
    assert.ok(dom.container.textContent.includes("<script>window.__xss_triggered = true;</script>"));
    assert.equal(globalThis.__xss_triggered, undefined);
  } finally {
    await dom.unmount?.();
  }
});

test('FormattedMessage handles empty or null input gracefully', async () => {
  const { default: FormattedMessage } = await import('./FormattedMessage.jsx');
  const dom = await createDomEnvironment();

  try {
    await dom.render(React.createElement(FormattedMessage, { content: null }));
    await dom.flush();
    assert.equal(dom.container.textContent, '');

    await dom.render(React.createElement(FormattedMessage, { content: '' }));
    await dom.flush();
    assert.equal(dom.container.textContent, '');
  } finally {
    await dom.unmount?.();
  }
});

test('FormattedMessage groups loose ordered lists separated by blank lines into a single ordered list', async () => {
  const { default: FormattedMessage } = await import('./FormattedMessage.jsx');
  const dom = await createDomEnvironment();

  try {
    const markdown = `
1. Initiate manufacturing execution by staging raw materials.

2. Verify equipment calibration and line clearance.

3. Complete all eBR steps with electronic signatures.

4. Perform in-process quality testing.

5. Submit completed batch dossier to QA review queue.
`;
    await dom.render(React.createElement(FormattedMessage, { content: markdown }));
    await dom.flush();

    const olElements = dom.container.querySelectorAll('ol');
    assert.equal(olElements.length, 1, 'Should render exactly one ordered list container');
    const items = olElements[0].querySelectorAll('li');
    assert.equal(items.length, 5, 'Should render 5 list items');
    assert.ok(items[0].textContent.includes('Initiate manufacturing execution'));
    assert.ok(items[4].textContent.includes('Submit completed batch dossier'));
  } finally {
    await dom.unmount?.();
  }
});

test('FormattedMessage preserves "Total recorded QC checks: 0." without stripping count', async () => {
  const { default: FormattedMessage, parseMarkdownBlocks } = await import('./FormattedMessage.jsx');
  const dom = await createDomEnvironment();

  try {
    const text = 'Total recorded QC checks: 0. No quality control inspections have been performed or recorded for this batch in available MES records.';

    // Verify AST block parsing
    const blocks = parseMarkdownBlocks(text);
    assert.equal(blocks.length, 1);
    assert.equal(blocks[0].type, 'paragraph');
    assert.ok(blocks[0].content.includes('Total recorded QC checks: 0.'));

    // Verify DOM rendering
    await dom.render(React.createElement(FormattedMessage, { content: text }));
    await dom.flush();

    const p = dom.container.querySelector('p');
    assert.ok(p, 'Should render a paragraph element');
    assert.ok(p.textContent.includes('Total recorded QC checks: 0.'), 'Paragraph must contain "Total recorded QC checks: 0."');
    assert.equal(dom.container.querySelector('ol'), null, 'Should not render as an ordered list');
  } finally {
    await dom.unmount?.();
  }
});
