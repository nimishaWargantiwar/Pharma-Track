import test from 'node:test';
import assert from 'node:assert/strict';
import React, { useState } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { createDomEnvironment, MiniEvent } from '../../scripts/dom-harness.mjs';

function makeKeyEvent(key, { shiftKey = false } = {}) {
  const evt = new MiniEvent('keydown', { bubbles: true, cancelable: true });
  evt.key = key;
  evt.code = key;
  evt.shiftKey = shiftKey;
  return evt;
}

test('StatusBadge preserves domain-specific semantic colors and fallback rendering', async () => {
  const { default: StatusBadge } = await import('./ui/StatusBadge.jsx');

  const qaReleased = renderToStaticMarkup(
    React.createElement(StatusBadge, { status: 'RELEASED', domain: 'qa' })
  );
  assert.ok(qaReleased.includes('bg-emerald-100 text-emerald-800'));

  const orderReleased = renderToStaticMarkup(
    React.createElement(StatusBadge, { status: 'RELEASED', domain: 'order' })
  );
  assert.ok(orderReleased.includes('bg-indigo-100 text-indigo-800'));

  const devOpen = renderToStaticMarkup(
    React.createElement(StatusBadge, { status: 'OPEN', domain: 'deviation' })
  );
  assert.ok(devOpen.includes('bg-rose-100 text-rose-800'));

  const capaOpen = renderToStaticMarkup(
    React.createElement(StatusBadge, { status: 'OPEN', domain: 'capa' })
  );
  assert.ok(capaOpen.includes('bg-amber-100 text-amber-800'));

  const capaInProgress = renderToStaticMarkup(
    React.createElement(StatusBadge, { status: 'IN_PROGRESS', domain: 'capa' })
  );
  assert.ok(capaInProgress.includes('bg-pharma-100 text-pharma-800'));
});

test('InlineAlert, PageHeader, and ProgressBar render accessible semantics and clamping', async () => {
  const { default: InlineAlert } = await import('./ui/InlineAlert.jsx');
  const { default: PageHeader } = await import('./ui/PageHeader.jsx');
  const { default: ProgressBar } = await import('./ui/ProgressBar.jsx');

  let dismissed = false;
  const dom = await createDomEnvironment();

  try {
    await dom.render(
      React.createElement(
        'div',
        null,
        React.createElement(PageHeader, {
          title: 'Batch Quality Dossiers',
          badge: 'QA Review',
          description: 'Review QC checks and QA disposition records.',
          actions: React.createElement('button', { type: 'button' }, 'Refresh Queue'),
        }),
        React.createElement(InlineAlert, {
          variant: 'warning',
          title: 'Pending Verification',
          message: 'Check all deviation closures before release.',
          onDismiss: () => {
            dismissed = true;
          },
          dismissLabel: 'Dismiss warning alert',
        }),
        React.createElement(ProgressBar, {
          value: 140,
          max: 100,
          label: 'Batch Completion Progress',
          showLabel: true,
          showPercentage: true,
          summaryText: '4 of 4 steps',
        })
      )
    );
    await dom.flush();

    assert.equal(dom.container.querySelector('h1')?.textContent, 'Batch Quality Dossiers');
    assert.ok(dom.container.textContent.includes('QA Review'));
    assert.ok(dom.container.textContent.includes('Refresh Queue'));

    const alertEl = dom.container.querySelector('[role="alert"]');
    assert.ok(alertEl);
    assert.ok(alertEl.textContent.includes('Pending Verification'));
    await dom.click(dom.container.querySelector('button[aria-label="Dismiss warning alert"]'));
    assert.equal(dismissed, true);

    const progressEl = dom.container.querySelector('[role="progressbar"]');
    assert.ok(progressEl);
    assert.equal(progressEl.getAttribute('aria-valuenow'), '100');
    assert.equal(progressEl.getAttribute('aria-valuemin'), '0');
    assert.equal(progressEl.getAttribute('aria-valuemax'), '100');
    assert.equal(progressEl.getAttribute('aria-label'), 'Batch Completion Progress');
    assert.ok(dom.container.textContent.includes('100%'));
  } finally {
    await dom.unmount();
  }
});

test('Pagination defaults to 25 rows per page and DataTable supports sorting and pagination', async () => {
  const { DEFAULT_PAGE_SIZE, paginateItems } = await import('./ui/Pagination.jsx');
  const { default: DataTable } = await import('./ui/DataTable.jsx');

  assert.equal(DEFAULT_PAGE_SIZE, 25);

  const sampleItems = Array.from({ length: 60 }, (_, idx) => ({
    id: idx + 1,
    code: `PRD-${String(idx + 1).padStart(3, '0')}`,
    rank: 60 - idx,
  }));

  const page1 = paginateItems(sampleItems, 1);
  assert.equal(page1.pageSize, 25);
  assert.equal(page1.items.length, 25);
  assert.equal(page1.items[0].code, 'PRD-001');
  assert.equal(page1.totalPages, 3);

  const page3 = paginateItems(sampleItems, 99);
  assert.equal(page3.page, 3);
  assert.equal(page3.items.length, 10);
  assert.equal(page3.items[0].code, 'PRD-051');

  const dom = await createDomEnvironment();
  try {
    await dom.render(
      React.createElement(DataTable, {
        data: sampleItems,
        autoPaginate: true,
        itemLabel: 'products',
        columns: [
          { key: 'code', header: 'Code', sortable: true },
          { key: 'rank', header: 'Rank', sortable: true },
        ],
      })
    );
    await dom.flush();

    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 60 products'));
    assert.equal(dom.container.querySelector('tbody').querySelectorAll('tr').length, 25);
    assert.equal(dom.container.querySelectorAll('th[scope="col"]').length, 2);

    // Navigate to Page 2
    const page2Btn = dom.container.querySelector('button[aria-label="Page 2"]');
    assert.ok(page2Btn);
    await dom.click(page2Btn);
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Showing 26\u201350 of 60 products'));
    assert.ok(dom.container.textContent.includes('PRD-026'));

    // Sort by Rank resets to page 1 and sets aria-sort="ascending"
    const rankThEl = dom.container.querySelectorAll('th')[1];
    const rankSortBtn = rankThEl?.querySelector('button');
    assert.ok(rankSortBtn);
    await dom.click(rankSortBtn);
    await dom.flush();

    const rankTh = dom.container.querySelectorAll('th')[1];
    assert.equal(rankTh.getAttribute('aria-sort'), 'ascending');
    assert.ok(dom.container.textContent.includes('Showing 1\u201325 of 60 products'));
    assert.ok(dom.container.textContent.includes('PRD-060'));
  } finally {
    await dom.unmount();
  }
});

test('AuditDiffViewer parses JSON snapshots, highlights changes, and toggles raw JSON view', async () => {
  const { default: AuditDiffViewer, computeAuditDiffRows } = await import(
    './ui/AuditDiffViewer.jsx'
  );

  const computed = computeAuditDiffRows(
    JSON.stringify({ status: 'PLANNED', quantity: 100, note: 'Initial' }),
    JSON.stringify({ status: 'RELEASED', quantity: 100, approvedBy: 'qa.reviewer' })
  );
  assert.equal(computed.isStructuredObject, true);
  assert.deepEqual(
    computed.rows.map((r) => [r.key, r.changeType]),
    [
      ['approvedBy', 'added'],
      ['note', 'removed'],
      ['quantity', 'unchanged'],
      ['status', 'changed'],
    ]
  );

  const dom = await createDomEnvironment();
  try {
    await dom.render(
      React.createElement(AuditDiffViewer, {
        beforeValues: JSON.stringify({ status: 'PLANNED', quantity: 100 }),
        afterValues: JSON.stringify({ status: 'RELEASED', quantity: 100 }),
      })
    );
    await dom.flush();

    assert.ok(dom.container.textContent.includes('1 changed field'));
    assert.ok(dom.container.textContent.includes('status'));
    assert.ok(dom.container.textContent.includes('PLANNED'));
    assert.ok(dom.container.textContent.includes('RELEASED'));

    // Toggle to Raw JSON
    const rawBtn = [...dom.container.querySelectorAll('button')].find((b) =>
      b.textContent.includes('Raw JSON')
    );
    assert.ok(rawBtn);
    await dom.click(rawBtn);
    await dom.flush();
    assert.ok(dom.container.querySelector('pre'));
    assert.ok(dom.container.textContent.includes('"status": "RELEASED"'));

    // Gracefully renders non-JSON plain strings without throwing
    await dom.render(
      React.createElement(AuditDiffViewer, {
        beforeValues: 'Legacy text state',
        afterValues: 'Updated text state',
      })
    );
    await dom.flush();
    assert.ok(dom.container.textContent.includes('Legacy text state'));
    assert.ok(dom.container.textContent.includes('Updated text state'));
  } finally {
    await dom.unmount();
  }
});

test('ModalDialog & ConfirmModal provide in-tree ARIA dialog, Escape, Tab trap, and focus restoration', async () => {
  const { default: ModalDialog } = await import('./ui/ModalDialog.jsx');
  const { default: ConfirmModal } = await import('./ConfirmModal.jsx');

  const dom = await createDomEnvironment();

  function ModalHarness({ preventClose = false }) {
    const [open, setOpen] = useState(false);
    const [confirmOpen, setConfirmOpen] = useState(false);
    return React.createElement(
      'div',
      null,
      React.createElement(
        'button',
        { type: 'button', id: 'launch-modal-btn', onClick: () => setOpen(true) },
        'Open Test Modal'
      ),
      React.createElement(
        'button',
        { type: 'button', id: 'launch-confirm-btn', onClick: () => setConfirmOpen(true) },
        'Open Confirm Modal'
      ),
      React.createElement(
        ModalDialog,
        {
          isOpen: open,
          onClose: () => setOpen(false),
          title: 'Accessible Dialog Title',
          description: 'Dialog description text',
          preventClose,
        },
        React.createElement('input', { id: 'dialog-input', type: 'text' }),
        React.createElement(
          'button',
          { type: 'button', id: 'dialog-action-btn', onClick: () => setOpen(false) },
          'Done'
        )
      ),
      React.createElement(ConfirmModal, {
        isOpen: confirmOpen,
        title: 'Confirm Action',
        message: 'Proceed cleanly?',
        onConfirm: () => setConfirmOpen(false),
        onClose: () => setConfirmOpen(false),
        isLoading: preventClose,
      })
    );
  }

  try {
    await dom.render(React.createElement(ModalHarness, { preventClose: false }));
    await dom.flush();

    const triggerBtn = dom.container.querySelector('#launch-modal-btn');
    triggerBtn.focus();
    assert.equal(dom.document.activeElement, triggerBtn);

    await dom.click(triggerBtn);
    await dom.flush();

    // Must render in-tree inside container (no React portal)
    const dialogEl = dom.container.querySelector('[role="dialog"]');
    assert.ok(dialogEl);
    assert.equal(dialogEl.getAttribute('aria-modal'), 'true');
    const labelledBy = dialogEl.getAttribute('aria-labelledby');
    assert.ok(labelledBy);
    assert.equal(dom.container.querySelector(`#${labelledBy}`)?.textContent, 'Accessible Dialog Title');

    // Initial focus moves inside dialog
    assert.equal(dialogEl.contains(dom.document.activeElement), true);

    // Tab cycle stays inside dialog
    const closeBtn = dom.container.querySelector('button[aria-label="Close dialog"]');
    const doneBtn = dom.container.querySelector('#dialog-action-btn');
    doneBtn.focus();
    await dom.act(async () => {
      doneBtn.dispatchEvent(makeKeyEvent('Tab'));
    });
    assert.equal(dom.document.activeElement, closeBtn);

    // Escape closes dialog and restores focus to triggerBtn
    await dom.act(async () => {
      dialogEl.dispatchEvent(makeKeyEvent('Escape'));
    });
    await dom.flush();
    assert.equal(dom.container.querySelector('[role="dialog"]'), null);
    assert.equal(dom.document.activeElement, triggerBtn);

    // Direct backdrop click closes modal, while accidental mousedown-inside -> click-backdrop does NOT close
    await dom.click(triggerBtn);
    await dom.flush();
    const reopenedDialog = dom.container.querySelector('[role="dialog"]');
    const backdrop = reopenedDialog.parentNode;
    const innerInput = dom.container.querySelector('#dialog-input');
    await dom.act(async () => {
      innerInput.dispatchEvent(new MiniEvent('mousedown', { bubbles: true }));
      backdrop.dispatchEvent(new MiniEvent('click', { bubbles: true }));
    });
    await dom.flush();
    assert.ok(dom.container.querySelector('[role="dialog"]'));

    await dom.click(backdrop);
    await dom.flush();
    assert.equal(dom.container.querySelector('[role="dialog"]'), null);

    // ConfirmModal also renders accessible role="dialog" and honors preventClose while loading
    await dom.render(React.createElement(ModalHarness, { preventClose: true }));
    await dom.flush();
    await dom.click(dom.container.querySelector('#launch-confirm-btn'));
    await dom.flush();

    const confirmDialog = dom.container.querySelector('[role="dialog"]');
    assert.ok(confirmDialog);
    assert.equal(confirmDialog.getAttribute('aria-modal'), 'true');
    await dom.act(async () => {
      confirmDialog.dispatchEvent(makeKeyEvent('Escape'));
    });
    await dom.flush();
    // Must stay open while isLoading / preventClose = true
    assert.ok(dom.container.querySelector('[role="dialog"]'));

    // Stacked modals: Escape closes ONLY the topmost dialog
    await dom.render(React.createElement(ModalHarness, { preventClose: false }));
    await dom.flush();
    await dom.click(triggerBtn);
    await dom.flush();
    await dom.click(dom.container.querySelector('#launch-confirm-btn'));
    await dom.flush();
    const stackedDialogs = dom.container.querySelectorAll('[role="dialog"]');
    assert.equal(stackedDialogs.length, 2);
    await dom.act(async () => {
      stackedDialogs[1].dispatchEvent(makeKeyEvent('Escape'));
    });
    await dom.flush();
    assert.equal(dom.container.querySelectorAll('[role="dialog"]').length, 1);
  } finally {
    await dom.unmount();
  }
});

test('ToastProvider & ToastRegion render notifications, prevent duplicates, and clean up timers', async () => {
  const { ToastProvider, useToast } = await import('./ui/ToastProvider.jsx');

  const dom = await createDomEnvironment();
  function TriggerView() {
    const toast = useToast();
    return React.createElement(
      'div',
      null,
      React.createElement(
        'button',
        {
          type: 'button',
          id: 'push-success',
          onClick: () => toast.success('Batch released successfully.', { duration: 0 }),
        },
        'Push Success'
      ),
      React.createElement(
        'button',
        {
          type: 'button',
          id: 'push-error',
          onClick: () => toast.error('Validation error occurred.', { duration: 0 }),
        },
        'Push Error'
      )
    );
  }

  try {
    await dom.render(
      React.createElement(ToastProvider, null, React.createElement(TriggerView))
    );
    await dom.flush();

    await dom.click(dom.container.querySelector('#push-success'));
    await dom.click(dom.container.querySelector('#push-error'));
    await dom.flush();

    const region = dom.container.querySelector('[data-testid="toast-region"]');
    assert.ok(region);
    assert.ok(region.textContent.includes('Batch released successfully.'));
    assert.ok(region.textContent.includes('Validation error occurred.'));

    const dismissBtn = region.querySelector('button[aria-label="Dismiss success notification"]');
    assert.ok(dismissBtn);
    await dom.click(dismissBtn);
    await dom.flush();

    assert.equal(region.textContent.includes('Batch released successfully.'), false);
    assert.ok(region.textContent.includes('Validation error occurred.'));
  } finally {
    await dom.unmount();
  }
});

test('AuditDiffViewer uses stacked two-column snapshot layout and RecipeDetailDrawer wraps BOM table in overflow-x-auto', async () => {
  const { default: AuditDiffViewer } = await import('./ui/AuditDiffViewer.jsx');
  const { default: RecipeDetailDrawer } = await import('./RecipeDetailDrawer.jsx');

  const diffMarkup = renderToStaticMarkup(
    React.createElement(AuditDiffViewer, {
      beforeValues: JSON.stringify({ openDeviationsCount: 1, qaStatus: 'PENDING_QA' }),
      afterValues: JSON.stringify({ openDeviationsCount: 0, qaStatus: 'RELEASED' }),
    })
  );
  assert.ok(diffMarkup.includes('grid-cols-1 sm:grid-cols-2'));
  assert.equal(diffMarkup.includes('sm:grid-cols-12'), false);
  assert.ok(diffMarkup.includes('relative overflow-hidden'));
  assert.ok(diffMarkup.includes('openDeviationsCount'));

  const drawerMarkup = renderToStaticMarkup(
    React.createElement(RecipeDetailDrawer, {
      isOpen: true,
      onClose: () => {},
      recipe: {
        id: 1,
        recipeCode: 'RCP-001',
        version: 1,
        status: 'APPROVED',
        productCode: 'PRD-001',
        productName: 'Paracetamol 500mg',
        batchSize: 500,
        batchUnit: 'kg',
        materials: [{ id: 1, materialName: 'API', quantity: 250, unit: 'kg' }],
        steps: [{ id: 1, stepNumber: 1, operationName: 'Blending', instructions: 'Blend 20 min' }],
      },
    })
  );
  assert.ok(drawerMarkup.includes('overflow-x-auto'));
});
