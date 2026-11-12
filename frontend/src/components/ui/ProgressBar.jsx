import React from 'react';

const FILL_CLASSES = {
  pharma: 'bg-pharma-600',
  success: 'bg-emerald-500',
  warning: 'bg-amber-500',
  danger: 'bg-rose-500',
  neutral: 'bg-slate-400',
};

const SIZE_CLASSES = {
  sm: 'h-1.5',
  md: 'h-2',
  lg: 'h-2.5',
};

export default function ProgressBar({
  value = 0,
  max = 100,
  label = 'Progress',
  showLabel = false,
  showPercentage = false,
  summaryText = null,
  tone = 'auto', // 'auto' | 'pharma' | 'success' | 'warning' | 'danger' | 'neutral'
  size = 'md', // 'sm' | 'md' | 'lg'
  valueText = undefined,
  className = '',
}) {
  const safeMax = Number.isFinite(Number(max)) && Number(max) > 0 ? Number(max) : 100;
  const numericValue = Number.isFinite(Number(value)) ? Number(value) : 0;
  const clampedValue = Math.min(safeMax, Math.max(0, numericValue));
  const percent = Math.round((clampedValue / safeMax) * 100);

  let resolvedTone = tone;
  if (tone === 'auto') {
    if (percent >= 100) {
      resolvedTone = 'success';
    } else if (percent > 0) {
      resolvedTone = 'pharma';
    } else {
      resolvedTone = 'neutral';
    }
  }

  const fillClass = FILL_CLASSES[resolvedTone] || FILL_CLASSES.pharma;
  const heightClass = SIZE_CLASSES[size] || SIZE_CLASSES.md;
  const hasTopRow = showLabel || showPercentage || Boolean(summaryText);

  return (
    <div className={`w-full ${className}`.trim()}>
      {hasTopRow && (
        <div className="flex items-center justify-between gap-2 text-xs font-medium text-slate-700 mb-1 tabular-nums">
          <div className="truncate">
            {showLabel && <span className="font-semibold text-slate-800">{label}</span>}
            {!showLabel && summaryText && <span>{summaryText}</span>}
          </div>
          <div className="flex items-center gap-2 shrink-0 text-slate-500">
            {showLabel && summaryText && <span>{summaryText}</span>}
            {showPercentage && <span className="font-semibold text-slate-700">{percent}%</span>}
          </div>
        </div>
      )}

      <div
        role="progressbar"
        aria-label={label}
        aria-valuenow={clampedValue}
        aria-valuemin={0}
        aria-valuemax={safeMax}
        aria-valuetext={valueText}
        className={`w-full bg-slate-100 rounded-full overflow-hidden border border-slate-200/70 ${heightClass}`}
      >
        <div
          className={`${heightClass} rounded-full transition-all duration-300 ${fillClass}`}
          style={{ width: `${percent}%` }}
        />
      </div>
    </div>
  );
}

