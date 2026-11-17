import React from 'react';
import { AlertTriangle, ArrowUpRight, Box, ClipboardList, Database } from 'lucide-react';
import { useNavigation } from '../context/NavigationContext.jsx';

/**
 * Interactive citation card linking backend-verified MES records (Orders, Batches, Deviations)
 * directly into the appropriate PharmaTrack module via hash navigation.
 *
 * @param {Object} props
 * @param {Object} props.source Backend-provided citation reference
 * @param {string} props.source.recordType 'MANUFACTURING_ORDER' | 'BATCH' | 'DEVIATION'
 * @param {string} props.source.recordIdentifier e.g. 'MO-2026-0001', 'BAT-2026-0001-01', or 'DEV-2026-0001'
 * @param {string} props.source.title Display title
 * @param {string} props.source.module Associated module name
 * @param {string} [props.source.summary] Factual summary of the retrieved record
 * @param {Function} [props.onNavigate] Callback invoked when navigation is triggered
 */
export default function AiSourceReferencePill({ source, onNavigate }) {
  const { navigateToTab } = useNavigation();

  if (!source) {
    return null;
  }

  const { recordType, recordIdentifier, title, module, summary } = source;
  const isOrder = recordType === 'MANUFACTURING_ORDER';
  const isBatch = recordType === 'BATCH';
  const isDeviation = recordType === 'DEVIATION';

  const handleClick = (e) => {
    e.preventDefault();
    if (typeof onNavigate === 'function') {
      onNavigate(source);
    }

    if (isOrder && recordIdentifier) {
      navigateToTab('orders', { orderNumber: recordIdentifier });
    } else if (isBatch && recordIdentifier) {
      navigateToTab('traceability', { batchNumber: recordIdentifier });
    } else if (isDeviation && recordIdentifier) {
      navigateToTab('quality', { deviationNumber: recordIdentifier, view: 'deviations' });
    }
  };

  const IconComponent = isOrder ? ClipboardList : isBatch ? Box : isDeviation ? AlertTriangle : Database;
  const badgeToneClass = isOrder
    ? 'bg-sky-50 text-sky-800 border-sky-300 hover:bg-sky-100 hover:border-sky-400 focus:ring-sky-500'
    : isBatch
      ? 'bg-emerald-50 text-emerald-800 border-emerald-300 hover:bg-emerald-100 hover:border-emerald-400 focus:ring-emerald-500'
      : isDeviation
        ? 'bg-amber-50 text-amber-800 border-amber-300 hover:bg-amber-100 hover:border-amber-400 focus:ring-amber-500'
        : 'bg-slate-50 text-slate-800 border-slate-300 hover:bg-slate-100 hover:border-slate-400 focus:ring-slate-500';

  const typeLabel = isOrder ? 'Order' : isBatch ? 'Batch' : isDeviation ? 'Deviation' : recordType || 'Record';

  return (
    <button
      type="button"
      onClick={handleClick}
      title={summary ? `${title || recordIdentifier}: ${summary}` : (title || recordIdentifier)}
      aria-label={`Open ${typeLabel} ${recordIdentifier || title} in ${module || 'PharmaTrack'}`}
      className={`group inline-flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-xs font-medium border text-left transition-all shadow-2xs hover:shadow-xs focus:outline-none focus:ring-2 ${badgeToneClass}`}
    >
      <IconComponent className="w-3.5 h-3.5 shrink-0 opacity-80 group-hover:opacity-100" aria-hidden="true" />
      <span className="font-semibold uppercase tracking-wider text-[10px] px-1.5 py-0.5 rounded bg-white/80 border border-current/20 shadow-2xs shrink-0">
        {typeLabel}
      </span>
      <span className="sr-only">: </span>
      <span className="font-mono font-bold text-slate-900 tracking-tight">
        {recordIdentifier || title}
      </span>
      <ArrowUpRight className="w-3 h-3 shrink-0 ml-1 opacity-50 group-hover:opacity-100 transition-opacity" aria-hidden="true" />
    </button>
  );
}
