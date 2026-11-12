/**
 * Utility functions for Manufacturing Order progress metrics and
 * batch/order lifecycle action eligibility matching backend rules.
 */
import { formatCalendarDate } from './dateTimeUtils.js';

export { formatCalendarDate };

export function calculateOrderBatchProgress(order) {
  if (!order || typeof order !== 'object') {
    return {
      totalCount: 0,
      activeCount: 0,
      completedCount: 0,
      cancelledCount: 0,
      inProgressCount: 0,
      plannedCount: 0,
      progressPercent: 0,
      allCancelled: false,
    };
  }

  const batches = Array.isArray(order.batches) ? order.batches : [];
  const rawTarget = Number(order.targetBatchCount);
  const fallbackTotal =
    Number.isFinite(rawTarget) && rawTarget > 0 ? Math.trunc(rawTarget) : 0;

  if (batches.length === 0) {
    const isOrderCancelled = order.status === 'CANCELLED';
    const cancelledCount = isOrderCancelled ? fallbackTotal : 0;
    const activeCount = isOrderCancelled ? 0 : fallbackTotal;
    return {
      totalCount: fallbackTotal,
      activeCount,
      completedCount: 0,
      cancelledCount,
      inProgressCount: 0,
      plannedCount: isOrderCancelled ? 0 : fallbackTotal,
      progressPercent: 0,
      allCancelled: isOrderCancelled && fallbackTotal > 0,
    };
  }

  let completedCount = 0;
  let cancelledCount = 0;
  let inProgressCount = 0;
  let plannedCount = 0;

  for (const batch of batches) {
    switch (batch?.status) {
      case 'COMPLETED':
        completedCount += 1;
        break;
      case 'CANCELLED':
        cancelledCount += 1;
        break;
      case 'IN_PROGRESS':
        inProgressCount += 1;
        break;
      case 'PLANNED':
        plannedCount += 1;
        break;
      default:
        break;
    }
  }

  const totalCount = batches.length;
  const activeCount = Math.max(0, totalCount - cancelledCount);
  const allCancelled = totalCount > 0 && cancelledCount === totalCount;
  const progressPercent =
    activeCount > 0 ? Math.round((completedCount / activeCount) * 100) : 0;

  return {
    totalCount,
    activeCount,
    completedCount,
    cancelledCount,
    inProgressCount,
    plannedCount,
    progressPercent,
    allCancelled,
  };
}

export function canReleaseOrder(order) {
  if (!order || order.status !== 'PLANNED') {
    return false;
  }
  const batches = Array.isArray(order.batches) ? order.batches : [];
  if (batches.length === 0) {
    return true;
  }
  return batches.some((b) => b?.status !== 'CANCELLED');
}

export function canCancelOrder(order) {
  if (!order) return false;
  if (order.status === 'PLANNED') {
    return true;
  }
  if (order.status === 'RELEASED') {
    const batches = Array.isArray(order.batches) ? order.batches : [];
    return batches.every(
      (b) => b?.status === 'PLANNED' || b?.status === 'CANCELLED'
    );
  }
  return false;
}

export function canStartBatch(batch, order) {
  return Boolean(
    batch &&
      order &&
      batch.status === 'PLANNED' &&
      (order.status === 'RELEASED' || order.status === 'IN_PROGRESS')
  );
}

/**
 * Individual batch cancellation is permitted only while both the target batch
 * and its parent manufacturing order remain in PLANNED status.
 */
export function canCancelBatch(batch, order) {
  return Boolean(
    batch &&
      order &&
      batch.status === 'PLANNED' &&
      order.status === 'PLANNED'
  );
}

export function canCompleteBatch(batch) {
  return Boolean(batch && batch.status === 'IN_PROGRESS');
}

export function isLastRemainingNonCancelledBatch(batch, order) {
  if (!batch || !order || !Array.isArray(order.batches) || order.batches.length === 0) {
    return false;
  }
  return order.batches.every((candidate) => {
    if (candidate === batch || (batch.id != null && candidate?.id === batch.id)) {
      return true;
    }
    return candidate?.status === 'CANCELLED';
  });
}

