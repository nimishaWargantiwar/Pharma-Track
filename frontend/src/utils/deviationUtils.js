import {
  APP_TIME_ZONE,
  getAppTimeZoneDateTimeComponents,
  getNowIsoDateTimeInAppTimeZone,
  getTodayIsoDateInAppTimeZone,
  isValidIsoCalendarDate,
  normalizeAppDateTimeForApi,
  parseBackendTimestampToDate,
} from './dateTimeUtils.js';

export {
  APP_TIME_ZONE,
  getAppTimeZoneDateTimeComponents,
  getNowIsoDateTimeInAppTimeZone,
  getTodayIsoDateInAppTimeZone,
  isValidIsoCalendarDate,
};

export const DEVIATION_LIFECYCLE_STAGES = [
  { key: 'OPEN', label: 'Open', shortLabel: 'Open', step: 1 },
  { key: 'UNDER_INVESTIGATION', label: 'Under Investigation', shortLabel: 'Investigating', step: 2 },
  { key: 'ROOT_CAUSE_IDENTIFIED', label: 'Root Cause Identified', shortLabel: 'Root Cause', step: 3 },
  { key: 'CAPA_IN_PROGRESS', label: 'CAPA In Progress', shortLabel: 'CAPA Active', step: 4 },
  { key: 'PENDING_VERIFICATION', label: 'Pending Verification', shortLabel: 'Verification', step: 5 },
  { key: 'CLOSED', label: 'Closed', shortLabel: 'Closed', step: 6 },
];

export const DEVIATION_CATEGORY_OPTIONS = [
  { value: 'QC_OOS_FAILURE', label: 'QC Out-of-Specification (OOS)' },
  { value: 'PROCESS_PARAMETER', label: 'Process Parameter Excursion' },
  { value: 'IN_PROCESS_CONTROL', label: 'In-Process Control (IPC)' },
  { value: 'EQUIPMENT_MALFUNCTION', label: 'Equipment Malfunction' },
  { value: 'MATERIAL_ISSUE', label: 'Material / Component Issue' },
  { value: 'DOCUMENTATION_ERROR', label: 'Documentation Error' },
  { value: 'ENVIRONMENTAL_EXCURSION', label: 'Environmental Excursion' },
  { value: 'OTHER', label: 'Other Deviation' },
];

export const DEVIATION_SEVERITY_OPTIONS = [
  { value: 'CRITICAL', label: 'Critical' },
  { value: 'MAJOR', label: 'Major' },
  { value: 'MINOR', label: 'Minor' },
];

export const DEVIATION_PRIORITY_OPTIONS = [
  { value: 'HIGH', label: 'High' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'LOW', label: 'Low' },
];

export const CAPA_TYPE_OPTIONS = [
  { value: 'CORRECTIVE', label: 'Corrective Action' },
  { value: 'PREVENTIVE', label: 'Preventive Action' },
];

const STRICT_ISO_DATETIME_INPUT_REGEX =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:[.,]\d{1,9})?)?(?:Z|[+-]\d{2}:?\d{2})?$/i;

export function normalizeOccurredAtForApi(occurredAt) {
  return normalizeAppDateTimeForApi(occurredAt);
}

export function validateDeviationOccurredAt(occurredAt, referenceNow = new Date()) {
  if (occurredAt == null) return null;
  if (typeof occurredAt !== 'string') {
    return 'Invalid occurrence timestamp';
  }
  const trimmed = occurredAt.trim();
  if (!trimmed) {
    return null;
  }

  if (!STRICT_ISO_DATETIME_INPUT_REGEX.test(trimmed)) {
    return 'Invalid occurrence timestamp';
  }

  const parsedDate = parseBackendTimestampToDate(trimmed);
  if (!parsedDate) {
    return 'Invalid occurrence timestamp';
  }

  const refDate = parseBackendTimestampToDate(referenceNow) || new Date();
  if (parsedDate.getTime() > refDate.getTime() + 5 * 60 * 1000) {
    return 'Deviation occurrence timestamp cannot be in the future (Asia/Kolkata)';
  }
  return null;
}

export function validateCapaDueDate(dueDate, referenceToday = getTodayIsoDateInAppTimeZone()) {
  const trimmed = typeof dueDate === 'string' ? dueDate.trim() : '';
  if (!trimmed) {
    return 'CAPA due date is mandatory';
  }
  if (!isValidIsoCalendarDate(trimmed)) {
    return 'CAPA due date must be a valid date (YYYY-MM-DD)';
  }
  if (trimmed < referenceToday) {
    return `CAPA due date cannot be earlier than today (${referenceToday}, Asia/Kolkata).`;
  }
  return null;
}

export function getCategoryLabel(category) {
  const match = DEVIATION_CATEGORY_OPTIONS.find((item) => item.value === category);
  return match ? match.label : category || '—';
}

export function getDeviationStageIndex(status) {
  const idx = DEVIATION_LIFECYCLE_STAGES.findIndex((stage) => stage.key === status);
  return idx;
}

export function summarizeDeviationCounts(deviations = []) {
  const list = Array.isArray(deviations) ? deviations : [];
  const summary = {
    ALL: list.length,
    UNRESOLVED: 0,
    OPEN: 0,
    UNDER_INVESTIGATION: 0,
    ROOT_CAUSE_IDENTIFIED: 0,
    CAPA_IN_PROGRESS: 0,
    PENDING_VERIFICATION: 0,
    CLOSED: 0,
    CRITICAL: 0,
    OVERDUE_CAPAS: 0,
  };

  for (const item of list) {
    if (!item) continue;
    const status = item.status;
    if (status && summary[status] !== undefined) {
      summary[status] += 1;
    }
    if (status && status !== 'CLOSED') {
      summary.UNRESOLVED += 1;
      if (item.severity === 'CRITICAL') {
        summary.CRITICAL += 1;
      }
    }
    summary.OVERDUE_CAPAS += Number(item.overdueCapaCount || 0);
  }
  return summary;
}

export function filterDeviationsBySearch(deviations = [], searchTerm = '') {
  const list = Array.isArray(deviations) ? deviations : [];
  const q = typeof searchTerm === 'string' ? searchTerm.trim().toLowerCase() : '';
  if (!q) return list;

  return list.filter((item) => {
    if (!item) return false;
    return (
      item.deviationNumber?.toLowerCase().includes(q) ||
      item.batchNumber?.toLowerCase().includes(q) ||
      item.orderNumber?.toLowerCase().includes(q) ||
      item.productName?.toLowerCase().includes(q) ||
      item.productCode?.toLowerCase().includes(q) ||
      item.title?.toLowerCase().includes(q) ||
      item.reportedBy?.toLowerCase().includes(q) ||
      item.linkedCheckTestName?.toLowerCase().includes(q)
    );
  });
}

export function buildDeviationPrefillFromQc(dossier, qcCheck) {
  if (!qcCheck) {
    return {
      batchId: dossier?.batchId ? String(dossier.batchId) : '',
      batchNumber: dossier?.batchNumber || '',
      qualityCheckId: '',
      category: '',
      severity: 'MAJOR',
      priority: 'HIGH',
      title: '',
      description: '',
    };
  }

  const batchNum = dossier?.batchNumber || qcCheck.batchNumber || '';
  const measuredText = qcCheck.measuredValue
    ? `${qcCheck.measuredValue} ${qcCheck.unit || ''}`.trim()
    : 'No value recorded';
  const specText = qcCheck.specification || 'specified acceptance criteria';

  return {
    batchId: dossier?.batchId ? String(dossier.batchId) : qcCheck.batchId ? String(qcCheck.batchId) : '',
    batchNumber: batchNum,
    qualityCheckId: qcCheck.id ? String(qcCheck.id) : '',
    category: 'QC_OOS_FAILURE',
    severity: 'CRITICAL',
    priority: 'HIGH',
    title: `${qcCheck.testName || 'QC Check'} OOS failure (${measuredText}) on ${batchNum || 'batch'}`.slice(0, 200),
    description:
      `Out-of-specification QC result recorded for ${qcCheck.testName || 'QC test'}` +
      `${batchNum ? ` on batch ${batchNum}` : ''}. ` +
      `Measured value: ${measuredText} vs. acceptance specification: ${specText}.` +
      `${qcCheck.remarks ? ` QC remarks: ${qcCheck.remarks}` : ''}`,
  };
}

export function canEditDeviationCapas(status) {
  return status === 'ROOT_CAUSE_IDENTIFIED' || status === 'CAPA_IN_PROGRESS';
}

export function canExecuteDeviationCapas(status) {
  return status === 'CAPA_IN_PROGRESS';
}

export function canCancelCapaAction(deviationStatus, capaStatus, activeCapaCount) {
  if (!canEditDeviationCapas(deviationStatus)) {
    return false;
  }
  if (capaStatus !== 'OPEN' && capaStatus !== 'IN_PROGRESS') {
    return false;
  }
  if (deviationStatus === 'CAPA_IN_PROGRESS' && Number(activeCapaCount) <= 1) {
    return false;
  }
  return true;
}

export function mapCapaItemValidationErrors(validationErrors = {}) {
  if (!validationErrors || typeof validationErrors !== 'object') {
    return {};
  }
  const mapped = {};
  const actorError =
    validationErrors.actor || validationErrors.actorName || validationErrors.completedBy;
  const textError =
    validationErrors.text ||
    validationErrors.notes ||
    validationErrors.completionEvidence ||
    validationErrors.cancellationReason;

  if (actorError) {
    mapped.actor = actorError;
  }
  if (textError) {
    mapped.text = textError;
  }
  return mapped;
}

