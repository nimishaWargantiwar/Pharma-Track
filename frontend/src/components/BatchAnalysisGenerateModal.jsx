import React, { useState, useRef, useEffect } from 'react';
import {
  Sparkles,
  Layers,
  SlidersHorizontal,
  Check,
  RotateCcw,
  AlertCircle,
  Package,
  ClipboardCheck,
  FlaskConical,
  ShieldCheck,
  AlertTriangle,
  History,
  ShieldAlert,
  FileText,
  Info,
} from 'lucide-react';
import ModalDialog from './ui/ModalDialog.jsx';
import InlineAlert from './ui/InlineAlert.jsx';
import {
  BATCH_ANALYSIS_MODES,
  FOCUS_AREA_CATEGORIES,
  FOCUS_AREAS,
  ALL_FOCUS_AREA_KEYS,
} from '../constants/batchAnalysisModes.js';

const AREA_ICON_MAP = {
  MANUFACTURING_YIELD: Layers,
  MATERIAL_TRACEABILITY: Package,
  EBR_EXECUTION: ClipboardCheck,
  QC_INSPECTION: FlaskConical,
  QA_DISPOSITION: ShieldCheck,
  DEVIATIONS_CAPA: AlertTriangle,
  CHRONOLOGICAL_AUDIT: History,
  MISSING_RECORDS: AlertCircle,
  RISK_ASSESSMENT: ShieldAlert,
};

export default function BatchAnalysisGenerateModal({
  isOpen,
  onClose,
  onConfirm,
  batchNumber,
  productName = null,
}) {
  const [mode, setMode] = useState(BATCH_ANALYSIS_MODES.FULL);
  const [selectedAreas, setSelectedAreas] = useState([...ALL_FOCUS_AREA_KEYS]);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState(null);
  const isSubmittingRef = useRef(false);

  // Reset state when modal opens
  useEffect(() => {
    if (isOpen) {
      setMode(BATCH_ANALYSIS_MODES.FULL);
      setSelectedAreas([...ALL_FOCUS_AREA_KEYS]);
      setIsSubmitting(false);
      setError(null);
      isSubmittingRef.current = false;
    }
  }, [isOpen]);

  const handleToggleArea = (key) => {
    setError(null);
    setSelectedAreas((prev) =>
      prev.includes(key) ? prev.filter((k) => k !== key) : [...prev, key]
    );
  };

  const handleSelectAll = () => {
    setError(null);
    setSelectedAreas([...ALL_FOCUS_AREA_KEYS]);
  };

  const handleClearAll = () => {
    setError(null);
    setSelectedAreas([]);
  };

  const handleConfirm = async (e) => {
    if (e) e.preventDefault();
    if (isSubmittingRef.current || isSubmitting) return;

    if (mode === BATCH_ANALYSIS_MODES.FOCUSED && selectedAreas.length === 0) {
      setError('Please select at least one focus area for focused analysis.');
      return;
    }

    isSubmittingRef.current = true;
    setIsSubmitting(true);
    setError(null);

    try {
      await onConfirm({
        mode,
        focusAreas: mode === BATCH_ANALYSIS_MODES.FOCUSED ? selectedAreas : undefined,
      });
    } catch (err) {
      setError(err?.message || 'Failed to initiate report generation.');
      setIsSubmitting(false);
      isSubmittingRef.current = false;
    }
  };

  const isFocusedEmpty = mode === BATCH_ANALYSIS_MODES.FOCUSED && selectedAreas.length === 0;

  return (
    <ModalDialog
      isOpen={isOpen}
      onClose={isSubmitting ? () => {} : onClose}
      size="3xl"
      title="Generate AI Batch Analysis"
      subtitle={`Batch: ${batchNumber || 'N/A'}${productName ? ` • ${productName}` : ''}`}
      testId="batch-analysis-generate-modal"
      footer={
        <div className="flex items-center justify-between w-full">
          <div className="text-xs text-slate-500">
            {mode === BATCH_ANALYSIS_MODES.FULL ? (
              <span>Full analysis evaluates all 12 dossier sections.</span>
            ) : (
              <span data-testid="selected-areas-summary" className="font-medium text-slate-700">
                {selectedAreas.length} of {ALL_FOCUS_AREA_KEYS.length} areas selected
              </span>
            )}
          </div>
          <div className="flex items-center gap-2.5">
            <button
              type="button"
              onClick={onClose}
              disabled={isSubmitting}
              data-testid="cancel-generate-report-button"
              className="px-3.5 py-1.5 text-xs font-medium text-slate-600 hover:text-slate-800 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg transition-colors disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleConfirm}
              disabled={isSubmitting || isFocusedEmpty}
              data-testid="confirm-generate-report-button"
              className={`inline-flex items-center gap-2 px-4 py-1.5 text-xs font-semibold text-white rounded-lg shadow-xs transition-colors focus:outline-none focus:ring-2 focus:ring-pharma-500 ${
                isSubmitting || isFocusedEmpty
                  ? 'bg-pharma-400 cursor-not-allowed opacity-60'
                  : 'bg-pharma-600 hover:bg-pharma-700 active:bg-pharma-800'
              }`}
            >
              <Sparkles className={`w-3.5 h-3.5 ${isSubmitting ? 'animate-spin' : 'text-sky-200'}`} />
              <span>
                {isSubmitting
                  ? 'Generating Report...'
                  : mode === BATCH_ANALYSIS_MODES.FULL
                  ? 'Generate Full Report (12 Sections)'
                  : `Generate Focused Report (${selectedAreas.length} Areas)`}
              </span>
            </button>
          </div>
        </div>
      }
    >
      <div data-testid="batch-analysis-generate-modal" className="space-y-5">
        {/* Error notification if submission failed */}
        {error && (
          <div data-testid="generate-modal-error">
            <InlineAlert
              variant="error"
              message={error}
              onDismiss={() => setError(null)}
            />
          </div>
        )}

        {/* Mode Selector Cards */}
        <fieldset className="space-y-2 border-0 p-0 m-0">
          <legend className="block text-xs font-bold uppercase tracking-wider text-slate-500 mb-2">
            Analysis Mode
          </legend>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3" role="radiogroup" aria-label="Analysis Mode">
            {/* FULL Mode Card */}
            <label
              htmlFor="mode-full-radio"
              data-testid="mode-card-full"
              onClick={() => {
                if (mode !== BATCH_ANALYSIS_MODES.FULL) {
                  setMode(BATCH_ANALYSIS_MODES.FULL);
                  setError(null);
                }
              }}
              className={`relative flex flex-col p-4 border rounded-xl cursor-pointer transition-all select-none ${
                mode === BATCH_ANALYSIS_MODES.FULL
                  ? 'border-pharma-600 bg-pharma-50/70 ring-2 ring-pharma-500 shadow-sm'
                  : 'border-slate-200 bg-white hover:bg-slate-50 hover:border-slate-300 opacity-80 hover:opacity-100'
              }`}
            >
              <div className="flex items-start justify-between gap-2 mb-1.5">
                <div className="flex items-center gap-2.5">
                  <input
                    type="radio"
                    id="mode-full-radio"
                    name="analysisMode"
                    value={BATCH_ANALYSIS_MODES.FULL}
                    checked={mode === BATCH_ANALYSIS_MODES.FULL}
                    onChange={() => {
                      setMode(BATCH_ANALYSIS_MODES.FULL);
                      setError(null);
                    }}
                    onClick={() => {
                      setMode(BATCH_ANALYSIS_MODES.FULL);
                      setError(null);
                    }}
                    data-testid="mode-full-radio"
                    className="h-4 w-4 text-pharma-600 focus:ring-pharma-500 border-slate-300 cursor-pointer"
                  />
                  <span data-testid="mode-full-title" className="text-sm font-bold text-slate-900">
                    FULL Analysis
                  </span>
                </div>
                <div className="flex items-center gap-1.5">
                  {mode === BATCH_ANALYSIS_MODES.FULL && (
                    <span
                      data-testid="mode-full-active-indicator"
                      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-bold bg-pharma-100 text-pharma-800 border border-pharma-300"
                    >
                      <Check className="w-3 h-3 text-pharma-700" />
                      Active
                    </span>
                  )}
                  <span
                    data-testid="mode-full-badge"
                    className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-semibold bg-emerald-100 text-emerald-800 border border-emerald-200"
                  >
                    12 Sections
                  </span>
                </div>
              </div>
              <p data-testid="mode-full-description" className="text-xs text-slate-600 pl-6 leading-relaxed">
                Comprehensive evaluation covering all 12 batch dossier sections: yield metrics, eBR execution,
                QC inspections, QA disposition, deviations, audit chronology, and risk evaluation.
              </p>
            </label>

            {/* FOCUSED Mode Card */}
            <label
              htmlFor="mode-focused-radio"
              data-testid="mode-card-focused"
              onClick={() => {
                if (mode !== BATCH_ANALYSIS_MODES.FOCUSED) {
                  setMode(BATCH_ANALYSIS_MODES.FOCUSED);
                  setError(null);
                }
              }}
              className={`relative flex flex-col p-4 border rounded-xl cursor-pointer transition-all select-none ${
                mode === BATCH_ANALYSIS_MODES.FOCUSED
                  ? 'border-pharma-600 bg-pharma-50/70 ring-2 ring-pharma-500 shadow-sm'
                  : 'border-slate-200 bg-white hover:bg-slate-50 hover:border-slate-300 opacity-80 hover:opacity-100'
              }`}
            >
              <div className="flex items-start justify-between gap-2 mb-1.5">
                <div className="flex items-center gap-2.5">
                  <input
                    type="radio"
                    id="mode-focused-radio"
                    name="analysisMode"
                    value={BATCH_ANALYSIS_MODES.FOCUSED}
                    checked={mode === BATCH_ANALYSIS_MODES.FOCUSED}
                    onChange={() => {
                      setMode(BATCH_ANALYSIS_MODES.FOCUSED);
                      setError(null);
                    }}
                    onClick={() => {
                      setMode(BATCH_ANALYSIS_MODES.FOCUSED);
                      setError(null);
                    }}
                    data-testid="mode-focused-radio"
                    className="h-4 w-4 text-pharma-600 focus:ring-pharma-500 border-slate-300 cursor-pointer"
                  />
                  <span data-testid="mode-focused-title" className="text-sm font-bold text-slate-900">
                    FOCUSED Analysis
                  </span>
                </div>
                <div className="flex items-center gap-1.5">
                  {mode === BATCH_ANALYSIS_MODES.FOCUSED && (
                    <span
                      data-testid="mode-focused-active-indicator"
                      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-bold bg-sky-100 text-sky-800 border border-sky-300"
                    >
                      <Check className="w-3 h-3 text-sky-700" />
                      Active
                    </span>
                  )}
                  <span
                    data-testid="mode-focused-badge"
                    className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-semibold bg-sky-100 text-sky-800 border border-sky-200"
                  >
                    Targeted Scope
                  </span>
                </div>
              </div>
              <p data-testid="mode-focused-description" className="text-xs text-slate-600 pl-6 leading-relaxed">
                Allows the supervisor to select specific analysis areas. Focused reports contain only the
                selected analysis areas, along with the executive summary and recommendations.
              </p>
            </label>
          </div>
        </fieldset>

        {/* FOCUSED Areas Selection Panel */}
        {mode === BATCH_ANALYSIS_MODES.FOCUSED && (
          <div
            data-testid="focused-areas-selection-panel"
            className="border border-slate-200 bg-slate-50/50 rounded-xl p-4 space-y-4"
          >
            {/* Scope Clarification Notice */}
            <div
              data-testid="focused-mode-scope-clarification"
              className="p-3 bg-sky-50 border border-sky-200 rounded-lg text-xs text-sky-900 flex items-start gap-2.5"
            >
              <Info className="w-4 h-4 text-sky-600 shrink-0 mt-0.5" />
              <div className="leading-relaxed">
                <span className="font-semibold text-sky-950">Notice: </span>
                <span>Selecting all 9 focus areas still generates a FOCUSED report. To include all 12 dossier sections, select FULL Analysis.</span>
              </div>
            </div>
            {/* Header with Quick Actions */}
            <div className="flex flex-wrap items-center justify-between gap-2 pb-3 border-b border-slate-200">
              <div>
                <h4 className="text-xs font-bold text-slate-800 flex items-center gap-1.5">
                  <SlidersHorizontal className="w-3.5 h-3.5 text-pharma-600" />
                  <span>Select Analysis Focus Areas</span>
                </h4>
                <p className="text-[11px] text-slate-500 mt-0.5">
                  Choose one or more domains. Findings and recommendations will be strictly isolated to your selection.
                </p>
              </div>

              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={handleSelectAll}
                  data-testid="select-all-focus-areas-button"
                  className="px-2.5 py-1 text-[11px] font-medium text-pharma-700 hover:text-pharma-800 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-md transition-colors"
                >
                  Select All (9)
                </button>
                <button
                  type="button"
                  onClick={handleClearAll}
                  data-testid="clear-all-focus-areas-button"
                  className="px-2.5 py-1 text-[11px] font-medium text-slate-600 hover:text-slate-800 bg-white hover:bg-slate-100 border border-slate-200 rounded-md transition-colors"
                >
                  Clear All
                </button>
              </div>
            </div>

            {/* Validation warning if 0 selected */}
            {isFocusedEmpty && (
              <div
                data-testid="no-areas-selected-warning"
                className="p-3 bg-amber-50 border border-amber-200 rounded-lg text-xs text-amber-800 flex items-center gap-2"
              >
                <AlertCircle className="w-4 h-4 text-amber-600 shrink-0" />
                <span>Please select at least one focus area to proceed with focused analysis.</span>
              </div>
            )}

            {/* Categories & Areas Grid */}
            <div className="space-y-4">
              {FOCUS_AREA_CATEGORIES.map((cat) => {
                const categoryAreas = FOCUS_AREAS.filter((fa) => fa.category === cat.id);
                return (
                  <div key={cat.id} className="space-y-2">
                    <div className="text-[11px] font-bold uppercase tracking-wider text-slate-600 flex items-center justify-between">
                      <span>{cat.title}</span>
                      <span className="text-[10px] text-slate-600 font-normal normal-case">
                        {cat.description}
                      </span>
                    </div>

                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2.5">
                      {categoryAreas.map((area) => {
                        const isSelected = selectedAreas.includes(area.key);
                        const Icon = AREA_ICON_MAP[area.key] || FileText;

                        return (
                          <label
                            key={area.key}
                            htmlFor={`focus-area-checkbox-${area.key}`}
                            className={`p-3 rounded-lg border text-left cursor-pointer transition-all flex items-start gap-2.5 ${
                              isSelected
                                ? 'bg-white border-pharma-500 shadow-2xs ring-1 ring-pharma-500/20'
                                : 'bg-white/80 border-slate-200 hover:border-slate-300 opacity-75 hover:opacity-100'
                            }`}
                          >
                            <input
                              type="checkbox"
                              id={`focus-area-checkbox-${area.key}`}
                              checked={isSelected}
                              onChange={() => handleToggleArea(area.key)}
                              data-testid={`focus-area-checkbox-${area.key}`}
                              className="mt-0.5 h-4 w-4 rounded border-slate-300 text-pharma-600 focus:ring-pharma-500"
                            />
                            <div className="min-w-0 flex-1">
                              <div className="flex items-center gap-1.5 mb-0.5">
                                <Icon className={`w-3.5 h-3.5 ${isSelected ? 'text-pharma-600' : 'text-slate-400'}`} />
                                <span className={`text-xs font-semibold ${isSelected ? 'text-slate-900' : 'text-slate-700'}`}>
                                  {area.label}
                                </span>
                              </div>
                              <p className="text-[11px] text-slate-500 leading-snug line-clamp-2">
                                {area.description}
                              </p>
                            </div>
                          </label>
                        );
                      })}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>
    </ModalDialog>
  );
}
