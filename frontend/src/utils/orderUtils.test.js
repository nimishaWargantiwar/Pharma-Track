import test from 'node:test';
import assert from 'node:assert/strict';
import {
  calculateOrderBatchProgress,
  canReleaseOrder,
  canCancelOrder,
  canStartBatch,
  canCancelBatch,
  canCompleteBatch,
  isLastRemainingNonCancelledBatch,
} from './orderUtils.js';

test('calculateOrderBatchProgress handles all batches completed -> 100%', () => {
  const result = calculateOrderBatchProgress({
    status: 'COMPLETED',
    targetBatchCount: 2,
    batches: [
      { id: 1, status: 'COMPLETED' },
      { id: 2, status: 'COMPLETED' },
    ],
  });

  assert.deepEqual(result, {
    totalCount: 2,
    activeCount: 2,
    completedCount: 2,
    cancelledCount: 0,
    inProgressCount: 0,
    plannedCount: 0,
    progressPercent: 100,
    allCancelled: false,
  });
});

test('calculateOrderBatchProgress excludes CANCELLED batches from progress denominator', () => {
  const mixedCompletedAndCancelled = calculateOrderBatchProgress({
    status: 'COMPLETED',
    targetBatchCount: 3,
    batches: [
      { id: 1, status: 'CANCELLED' },
      { id: 2, status: 'COMPLETED' },
      { id: 3, status: 'CANCELLED' },
    ],
  });

  assert.equal(mixedCompletedAndCancelled.totalCount, 3);
  assert.equal(mixedCompletedAndCancelled.activeCount, 1);
  assert.equal(mixedCompletedAndCancelled.completedCount, 1);
  assert.equal(mixedCompletedAndCancelled.cancelledCount, 2);
  assert.equal(mixedCompletedAndCancelled.progressPercent, 100);
  assert.equal(mixedCompletedAndCancelled.allCancelled, false);

  const mixedPartialProgress = calculateOrderBatchProgress({
    status: 'IN_PROGRESS',
    targetBatchCount: 4,
    batches: [
      { id: 1, status: 'CANCELLED' },
      { id: 2, status: 'COMPLETED' },
      { id: 3, status: 'IN_PROGRESS' },
      { id: 4, status: 'PLANNED' },
    ],
  });

  assert.equal(mixedPartialProgress.totalCount, 4);
  assert.equal(mixedPartialProgress.activeCount, 3);
  assert.equal(mixedPartialProgress.completedCount, 1);
  assert.equal(mixedPartialProgress.cancelledCount, 1);
  assert.equal(mixedPartialProgress.inProgressCount, 1);
  assert.equal(mixedPartialProgress.plannedCount, 1);
  assert.equal(mixedPartialProgress.progressPercent, 33);
  assert.equal(mixedPartialProgress.allCancelled, false);
});

test('calculateOrderBatchProgress handles all batches cancelled cleanly at 0% without division by zero', () => {
  const result = calculateOrderBatchProgress({
    status: 'CANCELLED',
    targetBatchCount: 2,
    batches: [
      { id: 1, status: 'CANCELLED' },
      { id: 2, status: 'CANCELLED' },
    ],
  });

  assert.deepEqual(result, {
    totalCount: 2,
    activeCount: 0,
    completedCount: 0,
    cancelledCount: 2,
    inProgressCount: 0,
    plannedCount: 0,
    progressPercent: 0,
    allCancelled: true,
  });
});

test('calculateOrderBatchProgress handles missing/empty order inputs safely', () => {
  assert.equal(calculateOrderBatchProgress(null).progressPercent, 0);
  assert.equal(calculateOrderBatchProgress(undefined).totalCount, 0);

  const cancelledWithoutArray = calculateOrderBatchProgress({
    status: 'CANCELLED',
    targetBatchCount: 2,
  });
  assert.equal(cancelledWithoutArray.activeCount, 0);
  assert.equal(cancelledWithoutArray.cancelledCount, 2);
  assert.equal(cancelledWithoutArray.allCancelled, true);
  assert.equal(cancelledWithoutArray.progressPercent, 0);
});

test('canCancelBatch enforces PLANNED batch AND PLANNED parent order rule', () => {
  const plannedBatch = { id: 10, status: 'PLANNED' };
  const inProgressBatch = { id: 11, status: 'IN_PROGRESS' };
  const completedBatch = { id: 12, status: 'COMPLETED' };
  const cancelledBatch = { id: 13, status: 'CANCELLED' };

  assert.equal(canCancelBatch(plannedBatch, { status: 'PLANNED' }), true);
  assert.equal(canCancelBatch(plannedBatch, { status: 'RELEASED' }), false);
  assert.equal(canCancelBatch(plannedBatch, { status: 'IN_PROGRESS' }), false);
  assert.equal(canCancelBatch(plannedBatch, { status: 'COMPLETED' }), false);
  assert.equal(canCancelBatch(plannedBatch, { status: 'CANCELLED' }), false);

  assert.equal(canCancelBatch(inProgressBatch, { status: 'PLANNED' }), false);
  assert.equal(canCancelBatch(completedBatch, { status: 'PLANNED' }), false);
  assert.equal(canCancelBatch(cancelledBatch, { status: 'PLANNED' }), false);
  assert.equal(canCancelBatch(null, { status: 'PLANNED' }), false);
  assert.equal(canCancelBatch(plannedBatch, null), false);
});

test('canStartBatch and canCompleteBatch preserve mutually exclusive UI actions', () => {
  const plannedBatch = { id: 1, status: 'PLANNED' };
  const inProgressBatch = { id: 2, status: 'IN_PROGRESS' };
  const cancelledBatch = { id: 3, status: 'CANCELLED' };

  assert.equal(canStartBatch(plannedBatch, { status: 'PLANNED' }), false);
  assert.equal(canStartBatch(plannedBatch, { status: 'RELEASED' }), true);
  assert.equal(canStartBatch(plannedBatch, { status: 'IN_PROGRESS' }), true);
  assert.equal(canStartBatch(cancelledBatch, { status: 'RELEASED' }), false);

  assert.equal(canCompleteBatch(inProgressBatch), true);
  assert.equal(canCompleteBatch(plannedBatch), false);
  assert.equal(canCompleteBatch(cancelledBatch), false);
});

test('canCancelOrder and canReleaseOrder account for partially cancelled child batches', () => {
  assert.equal(
    canReleaseOrder({
      status: 'PLANNED',
      batches: [{ status: 'CANCELLED' }, { status: 'PLANNED' }],
    }),
    true
  );
  assert.equal(
    canReleaseOrder({
      status: 'PLANNED',
      batches: [{ status: 'CANCELLED' }, { status: 'CANCELLED' }],
    }),
    false
  );

  assert.equal(
    canCancelOrder({
      status: 'RELEASED',
      batches: [{ status: 'CANCELLED' }, { status: 'PLANNED' }],
    }),
    true
  );
  assert.equal(
    canCancelOrder({
      status: 'RELEASED',
      batches: [{ status: 'CANCELLED' }, { status: 'IN_PROGRESS' }],
    }),
    false
  );
});

test('isLastRemainingNonCancelledBatch detects when cancelling a batch empties active batches', () => {
  const b1 = { id: 1, status: 'CANCELLED' };
  const b2 = { id: 2, status: 'PLANNED' };
  const b3 = { id: 3, status: 'PLANNED' };

  assert.equal(
    isLastRemainingNonCancelledBatch(b2, { batches: [b1, b2, b3] }),
    false
  );
  assert.equal(
    isLastRemainingNonCancelledBatch(b2, { batches: [b1, b2] }),
    true
  );
  assert.equal(
    isLastRemainingNonCancelledBatch(b2, { batches: [b2] }),
    true
  );
});

