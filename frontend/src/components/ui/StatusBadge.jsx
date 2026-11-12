import React from 'react';

const TONE_CLASSES = {
  neutral: 'bg-slate-100 text-slate-700 border-slate-200',
  muted: 'bg-slate-200 text-slate-700 border-slate-300',
  pharma: 'bg-pharma-100 text-pharma-800 border-pharma-200',
  info: 'bg-pharma-50 text-pharma-700 border-pharma-200',
  success: 'bg-emerald-100 text-emerald-800 border-emerald-200',
  warning: 'bg-amber-100 text-amber-800 border-amber-200',
  danger: 'bg-rose-100 text-rose-800 border-rose-200',
  dangerSoft: 'bg-rose-50 text-rose-700 border-rose-200',
  indigo: 'bg-indigo-100 text-indigo-800 border-indigo-200',
  indigoSoft: 'bg-indigo-50 text-indigo-800 border-indigo-200',
  purple: 'bg-purple-100 text-purple-800 border-purple-200',
  teal: 'bg-teal-50 text-teal-800 border-teal-200',
};

const DOT_COLOR_CLASSES = {
  neutral: 'bg-slate-500',
  muted: 'bg-slate-500',
  pharma: 'bg-pharma-500',
  info: 'bg-pharma-500',
  success: 'bg-emerald-500',
  warning: 'bg-amber-500',
  danger: 'bg-rose-500',
  dangerSoft: 'bg-rose-500',
  indigo: 'bg-indigo-500',
  indigoSoft: 'bg-indigo-500',
  purple: 'bg-purple-500',
  teal: 'bg-teal-500',
};

const ICON_COLOR_CLASSES = {
  neutral: 'text-slate-500',
  muted: 'text-slate-500',
  pharma: 'text-pharma-600',
  info: 'text-pharma-600',
  success: 'text-emerald-600',
  warning: 'text-amber-600',
  danger: 'text-rose-600',
  dangerSoft: 'text-rose-600',
  indigo: 'text-indigo-600',
  indigoSoft: 'text-indigo-600',
  purple: 'text-purple-600',
  teal: 'text-teal-600',
};

const DOMAIN_STATUS_TONES = {
  order: {
    PLANNED: 'pharma',
    RELEASED: 'indigo',
    IN_PROGRESS: 'warning',
    COMPLETED: 'success',
    CANCELLED: 'danger',
  },
  qa: {
    PENDING_QA: 'pharma',
    RELEASED: 'success',
    ON_HOLD: 'warning',
    REJECTED: 'danger',
  },
  qc: {
    PASS: 'success',
    FAIL: 'danger',
    PENDING: 'warning',
  },
  capa: {
    OPEN: 'warning',
    IN_PROGRESS: 'pharma',
    COMPLETED: 'success',
    CANCELLED: 'neutral',
    PREVENTIVE: 'teal',
    CORRECTIVE: 'indigoSoft',
    OVERDUE: 'danger',
  },
  deviation: {
    OPEN: 'danger',
    UNDER_INVESTIGATION: 'warning',
    ROOT_CAUSE_IDENTIFIED: 'indigo',
    CAPA_IN_PROGRESS: 'pharma',
    PENDING_VERIFICATION: 'purple',
    CLOSED: 'success',
    CRITICAL: 'danger',
    MAJOR: 'warning',
    MINOR: 'neutral',
    HIGH: 'dangerSoft',
    MEDIUM: 'info',
    LOW: 'neutral',
  },
  inventory: {
    IN_STOCK: 'success',
    LOW_STOCK: 'warning',
    OUT_OF_STOCK: 'danger',
    INACTIVE: 'muted',
    ACTIVE: 'success',
    QUARANTINED: 'warning',
    DEPLETED: 'neutral',
    EXPIRED: 'danger',
    GOODS_RECEIPT: 'success',
    MATERIAL_ISSUE: 'indigo',
    STOCK_ADJUSTMENT: 'purple',
    API: 'pharma',
    EXCIPIENT: 'teal',
    PACKAGING: 'neutral',
    SOLVENT: 'indigoSoft',
    OTHER: 'neutral',
  },
};

const DEFAULT_STATUS_TONES = {
  ACTIVE: 'success',
  INACTIVE: 'muted',
  IN_STOCK: 'success',
  LOW_STOCK: 'warning',
  OUT_OF_STOCK: 'danger',
  QUARANTINED: 'warning',
  DEPLETED: 'neutral',
  EXPIRED: 'danger',
  GOODS_RECEIPT: 'success',
  MATERIAL_ISSUE: 'indigo',
  STOCK_ADJUSTMENT: 'purple',
  APPROVED: 'success',
  DRAFT: 'pharma',
  OBSOLETE: 'muted',
  PLANNED: 'pharma',
  IN_PROGRESS: 'warning',
  COMPLETED: 'success',
  CANCELLED: 'danger',
  PENDING: 'warning',
  PENDING_QA: 'pharma',
  RELEASED: 'success',
  ON_HOLD: 'warning',
  REJECTED: 'danger',
  PASS: 'success',
  FAIL: 'danger',
  OPEN: 'danger',
  UNDER_INVESTIGATION: 'warning',
  ROOT_CAUSE_IDENTIFIED: 'indigo',
  CAPA_IN_PROGRESS: 'pharma',
  PENDING_VERIFICATION: 'purple',
  CLOSED: 'success',
  CRITICAL: 'danger',
  MAJOR: 'warning',
  MINOR: 'neutral',
  HIGH: 'dangerSoft',
  MEDIUM: 'info',
  LOW: 'neutral',
  PREVENTIVE: 'teal',
  CORRECTIVE: 'indigoSoft',
  OVERDUE: 'danger',
  UP: 'success',
  DOWN: 'danger',
};

export function resolveStatusTone(status, domain = null, explicitTone = null) {
  if (explicitTone && TONE_CLASSES[explicitTone]) {
    return explicitTone;
  }
  const normalized = typeof status === 'string' ? status.trim().toUpperCase() : '';
  if (domain && DOMAIN_STATUS_TONES[domain]?.[normalized]) {
    return DOMAIN_STATUS_TONES[domain][normalized];
  }
  return DEFAULT_STATUS_TONES[normalized] || 'neutral';
}

export default function StatusBadge({
  status,
  label,
  tone = null,
  domain = null,
  variant = 'pill', // 'pill' | 'tag'
  size = 'sm', // 'xs' | 'sm' | 'md'
  showDot = false,
  pulseDot = false,
  icon = null,
  className = '',
  title,
}) {
  const resolvedTone = resolveStatusTone(status, domain, tone);
  const toneClass = TONE_CLASSES[resolvedTone] || TONE_CLASSES.neutral;
  const dotClass = DOT_COLOR_CLASSES[resolvedTone] || DOT_COLOR_CLASSES.neutral;
  const iconClass = ICON_COLOR_CLASSES[resolvedTone] || ICON_COLOR_CLASSES.neutral;

  const sizeClass =
    size === 'xs'
      ? 'px-2 py-0.5 text-[11px]'
      : size === 'md'
        ? 'px-3 py-1 text-xs'
        : 'px-2.5 py-0.5 text-xs';

  const shapeClass = variant === 'tag' ? 'rounded-md' : 'rounded-full';
  const displayLabel = label ?? (status || 'UNKNOWN');

  let renderedIcon = null;
  if (icon) {
    if (React.isValidElement(icon)) {
      renderedIcon = icon;
    } else if (typeof icon === 'function' || typeof icon === 'object') {
      const IconComponent = icon;
      renderedIcon = <IconComponent className={`w-3.5 h-3.5 mr-1 shrink-0 ${iconClass}`} aria-hidden="true" />;
    }
  }

  return (
    <span
      title={title}
      className={`inline-flex items-center font-semibold border whitespace-nowrap ${shapeClass} ${sizeClass} ${toneClass} ${className}`.trim()}
    >
      {showDot && (
        <span
          aria-hidden="true"
          className={`w-1.5 h-1.5 mr-1.5 rounded-full shrink-0 ${dotClass} ${
            pulseDot ? 'animate-pulse' : ''
          }`.trim()}
        />
      )}
      {renderedIcon}
      <span>{displayLabel}</span>
    </span>
  );
}

