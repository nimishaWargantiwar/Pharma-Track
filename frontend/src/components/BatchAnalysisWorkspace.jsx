import React, { useCallback, useEffect, useId, useRef, useState } from 'react';
import {
  AlertCircle,
  AlertTriangle,
  Archive,
  ArrowLeft,
  ArrowRight,
  ArrowUpRight,
  Box,
  Calendar,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  ChevronsUpDown,
  ClipboardCheck,
  ClipboardList,
  Clock,
  Database,
  FileCheck2,
  FileText,
  FlaskConical,
  History,
  Layers,
  Package,
  RefreshCw,
  RotateCcw,
  ShieldAlert,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  Trash2,
  TrendingUp,
  User,
  X,
  XCircle,
} from 'lucide-react';
import InlineAlert from './ui/InlineAlert.jsx';
import StatusBadge from './ui/StatusBadge.jsx';
import FormattedMessage from './FormattedMessage.jsx';
import AiSourceReferencePill from './AiSourceReferencePill.jsx';
import BatchAnalysisGenerateModal from './BatchAnalysisGenerateModal.jsx';
import { AuthContext } from '../context/AuthContext.jsx';
import {
  canGenerateBatchAnalysis,
  canPermanentlyDeleteBatchAnalysis,
  canViewBatchAnalysis,
  normalizeRole,
} from '../utils/rbacUtils.js';
import {
  formatAppDateTime,
  formatAppShortDateTime,
} from '../utils/dateTimeUtils.js';
import {
  archiveBatchAnalysisReport,
  generateBatchAnalysisReport,
  generateClientRequestId,
  getBatchAnalysisReportDetails,
  listBatchAnalysisReports,
  permanentDeleteBatchAnalysisReport,
} from '../services/batchAnalysisApi.js';

export const SECTION_GROUPS = [
  { id: 'manufacturing', title: '1. Manufacturing & Yield Performance' },
  { id: 'execution', title: '2. Execution Integrity & Materials' },
  { id: 'quality', title: '3. Quality & Traceability' },
  { id: 'risk', title: '4. Risk Assessment & Recommendations' },
];

export const SECTION_DEFINITIONS = [
  { key: 'executiveSummary', label: '1. Executive Summary', group: 'manufacturing', icon: FileText },
  { key: 'batchStatusAndProgress', label: '2. Batch Status & Manufacturing Progress', group: 'manufacturing', icon: Clock },
  { key: 'plannedVsActualMetrics', label: '3. Planned vs Actual Production Metrics', group: 'manufacturing', icon: Layers },
  { key: 'materialTraceability', label: '4. Material Traceability & Reconciliation', group: 'manufacturing', icon: Package },
  { key: 'ebrStepEvaluation', label: '5. eBR Step Execution Evaluation', group: 'execution', icon: ClipboardCheck },
  { key: 'qcInspectionAnalysis', label: '6. QC Inspection Analysis', group: 'execution', icon: FlaskConical },
  { key: 'qaDispositionStatus', label: '7. QA Disposition Status', group: 'execution', icon: ShieldCheck, isQaSection: true },
  { key: 'deviationsAndCapa', label: '8. Deviations & CAPA Assessment', group: 'quality', icon: AlertTriangle },
  { key: 'chronologicalAuditAnalysis', label: '9. Chronological Audit Analysis', group: 'quality', icon: History },
  { key: 'missingRecords', label: '10. Missing Records & Incompleteness', group: 'quality', icon: AlertCircle, isWarningSection: true },
  { key: 'riskObservations', label: '11. Risk Observations & Severity', group: 'risk', icon: ShieldAlert },
  { key: 'followUpRecommendations', label: '12. Follow-up Recommendations', group: 'risk', icon: CheckCircle2 },
];

export const FOCUS_AREA_TO_SECTION_KEY = {
  MANUFACTURING_YIELD: 'plannedVsActualMetrics',
  MATERIAL_TRACEABILITY: 'materialTraceability',
  EBR_EXECUTION: 'ebrStepEvaluation',
  QC_INSPECTION: 'qcInspectionAnalysis',
  QA_DISPOSITION: 'qaDispositionStatus',
  DEVIATIONS_CAPA: 'deviationsAndCapa',
  CHRONOLOGICAL_AUDIT: 'chronologicalAuditAnalysis',
  MISSING_RECORDS: 'missingRecords',
  RISK_ASSESSMENT: 'riskObservations',
};

export function getReportBatchStatus(report, dossier) {
  if (dossier?.batchStatus) {
    return dossier.batchStatus;
  }
  if (report?.batchStatus) {
    return report.batchStatus;
  }
  if (report?.batchContextSummary) {
    const match = report.batchContextSummary.match(/Status:\s*([A-Z_]+)/i);
    if (match) return match[1].toUpperCase();
  }
  if (report?.reportMarkdown) {
    const match = report.reportMarkdown.match(/\*\*Batch Status:\*\*\s*([A-Z_]+)/i);
    if (match) return match[1].toUpperCase();
  }
  return null;
}

export function getActualYieldFallbackText(batchStatus) {
  switch (batchStatus) {
    case 'PLANNED':
      return 'Not yet expected';
    case 'IN_PROGRESS':
      return 'In progress';
    case 'CANCELLED':
      return 'Not applicable';
    case 'COMPLETED':
    default:
      return 'Not recorded';
  }
}

export function getYieldMetricFallbackText(batchStatus) {
  switch (batchStatus) {
    case 'PLANNED':
      return 'Not yet expected';
    case 'IN_PROGRESS':
      return 'In progress';
    case 'CANCELLED':
      return 'Not applicable';
    case 'COMPLETED':
    default:
      return 'Unavailable';
  }
}

export function getReportVisibleSections(report) {
  if (!report) {
    return { isFocused: false, allowedKeys: new Set(), visibleGroups: [] };
  }

  const isFocused = report.analysisMode === 'FOCUSED';
  if (!isFocused) {
    return {
      isFocused: false,
      allowedKeys: new Set(SECTION_DEFINITIONS.map((s) => s.key)),
      visibleGroups: SECTION_GROUPS.map((grp) => ({
        ...grp,
        sections: SECTION_DEFINITIONS.filter((s) => s.group === grp.id),
      })),
    };
  }

  // FOCUSED mode:
  // 1. Executive Summary is always included
  // 2. Sections corresponding to selected focus areas
  const allowedKeys = new Set(['executiveSummary']);

  const areas = Array.isArray(report.focusAreas)
    ? report.focusAreas.map((fa) => (typeof fa === 'string' ? fa : fa?.id || fa?.name)).filter(Boolean)
    : [];

  areas.forEach((area) => {
    const secKey = FOCUS_AREA_TO_SECTION_KEY[area];
    if (secKey) {
      allowedKeys.add(secKey);
    }
  });

  // Include recommendations if present
  const rawFollowUp = report.structuredAnalysis?.followUpRecommendations;
  const hasAdvisory = Array.isArray(report.advisoryRecommendations) && report.advisoryRecommendations.length > 0;
  if ((rawFollowUp && typeof rawFollowUp === 'string' && rawFollowUp.trim().length > 0) || hasAdvisory) {
    allowedKeys.add('followUpRecommendations');
  }

  // Build visible groups, excluding empty groups and empty sections
  const visibleGroups = SECTION_GROUPS.map((grp) => {
    const groupSections = SECTION_DEFINITIONS.filter((s) => {
      if (s.group !== grp.id) {
        return false;
      }
      if (!allowedKeys.has(s.key)) {
        return false;
      }
      const rawContent = report.structuredAnalysis?.[s.key];
      const displayContent = formatSectionContentForDisplay(s.key, rawContent, report);
      return displayContent && typeof displayContent === 'string' && displayContent.trim().length > 0;
    });
    return {
      ...grp,
      sections: groupSections,
    };
  }).filter((grp) => grp.sections.length > 0);

  return {
    isFocused: true,
    allowedKeys,
    visibleGroups,
  };
}

const MAX_POLL_ATTEMPTS = 10;
const PAGE_SIZE = 20;

export const HISTORICAL_COMPLIANCE_ADVISORY =
  'Advisory Notice: This report contains statements regarding compliance risk or status based on recorded deviations. In accordance with GMP standards, absence of recorded deviations in MES does not constitute verified compliance or formal quality release. Formal batch disposition resides exclusively with Qualified Person / QA review.';

export function hasUnsupportedComplianceClaim(report) {
  if (!report) return false;
  const textToCheck = [
    report.structuredAnalysis?.riskObservations,
    report.structuredAnalysis?.missingRecords,
    report.reportMarkdown,
  ].filter(Boolean).join(' ');

  const claimPattern = /(?:(?:no|zero|absence\s+of)\s+[^.]*?(?:deviations?|non-conformances?|quality\s+issues?)[^.]*?(?:so|therefore|thus|hence|meaning|indicating|suggesting|consequently)?\s*,?\s*(?:compliance\s+risk\s+(?:is|remains)\s+(?:low|minimal|acceptable|negligible)|low\s+compliance\s+risk|risk\s+(?:is|remains)\s+(?:low|minimal)|no\s+significant\s+compliance\s+concerns|batch\s+is\s+(?:fully\s+|GMP\s+)?compliant|(?:fully|GMP)\s+compliant|100%\s+compliant))|(?:(?:compliance\s+risk\s+(?:is|remains)\s+(?:low|minimal|acceptable)|risk\s+(?:is|remains)\s+(?:low|minimal)|no\s+significant\s+compliance\s+concerns|batch\s+is\s+(?:fully\s+|GMP\s+)?compliant)\s+(?:because|due\s+to|as|since|given)\s+(?:no|zero|the\s+absence\s+of)\s+[^.]*?(?:deviations?|non-conformances?))/i;

  return claimPattern.test(textToCheck);
}

export function formatSectionContentForDisplay(key, rawContent, report) {
  if (!rawContent || typeof rawContent !== 'string') {
    return rawContent;
  }
  if (key === 'followUpRecommendations') {
    if (Array.isArray(report?.advisoryRecommendations) && report.advisoryRecommendations.length > 0) {
      return report.advisoryRecommendations
        .map((rec, i) => `${i + 1}. ${rec.replace(/^\d+[\.\)]\s*/, '')}`)
        .join('\n');
    }
    return rawContent;
  }
  return rawContent;
}

export function formatNarrativeMarkdownForDisplay(markdown) {
  return markdown;
}

export default function BatchAnalysisWorkspace({
  batchId,
  batchNumber,
  dossier = null,
  role: roleProp = null,
  onClose = null,
}) {
  const auth = React.useContext(AuthContext);
  const role = normalizeRole(roleProp || auth?.user?.role || auth?.role);
  const canGenerate = canGenerateBatchAnalysis(role);
  const canView = canViewBatchAnalysis(role);
  const canPermanentlyDelete = canPermanentlyDeleteBatchAnalysis(role);

  // History & Report States
  const [reports, setReports] = useState([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyPage, setHistoryPage] = useState(0);
  const [hasMorePages, setHasMorePages] = useState(false);
  const [historyFilter, setHistoryFilter] = useState('active'); // 'active' | 'archived' | 'all'

  // Selected Report State
  const [selectedReportId, setSelectedReportId] = useState(null);
  const [selectedReport, setSelectedReport] = useState(null);
  const [reportLoading, setReportLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('structured'); // 'overview' | 'structured' | 'narrative' | 'citations'

  // Generation & Operation States
  const [isGenerating, setIsGenerating] = useState(false);
  const [isGenerateModalOpen, setIsGenerateModalOpen] = useState(false);
  const [statusMessage, setStatusMessage] = useState(null);
  const [errorMessage, setErrorMessage] = useState(null);

  // Archive Modal State
  const [reportToArchive, setReportToArchive] = useState(null);
  const [archiveReason, setArchiveReason] = useState('');
  const [isArchiving, setIsArchiving] = useState(false);

  // Permanent Delete Modal State (Admin only)
  const [reportToDelete, setReportToDelete] = useState(null);
  const [deleteReason, setDeleteReason] = useState('');
  const [deleteConfirmationKeyword, setDeleteConfirmationKeyword] = useState('');
  const [isDeleting, setIsDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState(null);

  // Section Accordion State
  const [expandedSections, setExpandedSections] = useState(() =>
    SECTION_DEFINITIONS.reduce((acc, s) => ({ ...acc, [s.key]: true }), {})
  );

  // Polling Refs
  const pollTimerRef = useRef(null);
  const pollAttemptsRef = useRef(0);
  const isSubmittingRef = useRef(false);

  const currentBatchIdRef = useRef(batchId);
  currentBatchIdRef.current = batchId;

  // Clean up polling timer on unmount
  useEffect(() => {
    return () => {
      if (pollTimerRef.current) {
        clearTimeout(pollTimerRef.current);
      }
    };
  }, []);

  // Reset workspace selection whenever batchId changes
  useEffect(() => {
    setSelectedReportId(null);
    setSelectedReport(null);
    setReports([]);
    setErrorMessage(null);
    setStatusMessage(null);
    setHistoryPage(0);
    setHasMorePages(false);
  }, [batchId]);

  // Load report history
  const loadHistory = useCallback(
    async (pageToLoad = 0, append = false) => {
      if (!batchId || !canView) return;
      const targetBatchId = batchId;
      try {
        setHistoryLoading(true);
        setErrorMessage(null);
        // Include archived reports so frontend can filter client-side cleanly
        const result = await listBatchAnalysisReports(targetBatchId, {
          page: pageToLoad,
          size: PAGE_SIZE,
          includeArchived: true,
        });

        if (currentBatchIdRef.current !== targetBatchId) return;

        if (Array.isArray(result)) {
          setReports((prev) => (append ? [...prev, ...result] : result));
          setHistoryPage(pageToLoad);
          setHasMorePages(result.length === PAGE_SIZE);

          // Auto-select latest active report for THIS batch if none selected yet
          if (!append && result.length > 0) {
            const firstActive = result.find((r) => !r.archived) || result[0];
            setSelectedReportId(firstActive.id);
            try {
              const detail = await getBatchAnalysisReportDetails(targetBatchId, firstActive.id);
              if (currentBatchIdRef.current === targetBatchId) {
                setSelectedReport(detail);
              }
            } catch (_) {}
          }
        }
      } catch (err) {
        if (currentBatchIdRef.current !== targetBatchId) return;
        setErrorMessage(
          err?.status === 403
            ? 'You do not have permission to access batch analysis reports.'
            : err?.message || 'Failed to load report history.'
        );
      } finally {
        if (currentBatchIdRef.current === targetBatchId) {
          setHistoryLoading(false);
        }
      }
    },
    [batchId, canView]
  );

  // Initial load
  useEffect(() => {
    loadHistory(0, false);
  }, [loadHistory]);

  // Polling for in-progress report
  const startPolling = useCallback(
    (reportId, clientRequestId) => {
      if (pollTimerRef.current) {
        clearTimeout(pollTimerRef.current);
      }
      pollAttemptsRef.current = 0;

      const poll = async () => {
        pollAttemptsRef.current += 1;
        try {
          const detail = await getBatchAnalysisReportDetails(batchId, reportId);
          if (detail.status === 'COMPLETED' || detail.status === 'FAILED') {
            setSelectedReport(detail);
            setIsGenerating(false);
            setStatusMessage(
              detail.status === 'COMPLETED'
                ? 'AI Batch Analysis Report completed successfully.'
                : 'Report generation failed.'
            );
            loadHistory(0, false);
            return;
          }

          if (pollAttemptsRef.current >= MAX_POLL_ATTEMPTS) {
            setIsGenerating(false);
            setStatusMessage(
              'Report is taking longer than expected. Please check back shortly or refresh.'
            );
            return;
          }

          pollTimerRef.current = setTimeout(poll, 3000);
        } catch (_) {
          setIsGenerating(false);
        }
      };

      pollTimerRef.current = setTimeout(poll, 3000);
    },
    [batchId, loadHistory]
  );

  // Load full details for selected report
  useEffect(() => {
    if (!selectedReportId || !batchId || !canView) {
      setSelectedReport(null);
      return;
    }

    const targetBatchId = batchId;
    const targetReportId = selectedReportId;
    let isCurrent = true;

    const fetchDetail = async () => {
      try {
        setReportLoading(true);
        const detail = await getBatchAnalysisReportDetails(targetBatchId, targetReportId);
        if (isCurrent && currentBatchIdRef.current === targetBatchId) {
          setSelectedReport(detail);

          // If report is GENERATING, start polling
          if (detail.status === 'GENERATING') {
            startPolling(detail.id, detail.clientRequestId);
          }
        }
      } catch (err) {
        if (isCurrent && currentBatchIdRef.current === targetBatchId) {
          setErrorMessage(
            err?.status === 403
              ? 'You do not have permission to view this report.'
              : err?.message || 'Failed to load report details.'
          );
        }
      } finally {
        if (isCurrent && currentBatchIdRef.current === targetBatchId) {
          setReportLoading(false);
        }
      }
    };

    fetchDetail();
    return () => {
      isCurrent = false;
    };
  }, [batchId, selectedReportId, canView, startPolling]);

  // Open generate selection modal
  const handleOpenGenerateModal = () => {
    if (!canGenerate || isGenerating) return;
    setIsGenerateModalOpen(true);
  };

  const handleGenerateReport = handleOpenGenerateModal;

  // Confirm and execute report generation
  const handleConfirmGenerate = async ({ mode, focusAreas }) => {
    if (!canGenerate || isGenerating || isSubmittingRef.current) return;

    isSubmittingRef.current = true;
    setIsGenerating(true);
    setErrorMessage(null);
    setStatusMessage(
      mode === 'FOCUSED'
        ? 'Initiating Focused AI Batch Analysis Report...'
        : 'Initiating AI Batch Analysis Report...'
    );

    try {
      const clientReqId = generateClientRequestId();
      const payload = {
        clientRequestId: clientReqId,
        analysisMode: mode,
      };
      if (mode === 'FOCUSED' && Array.isArray(focusAreas) && focusAreas.length > 0) {
        payload.focusAreas = focusAreas;
      }

      const response = await generateBatchAnalysisReport(batchId, payload);

      setIsGenerateModalOpen(false);
      setSelectedReportId(response.id);
      setSelectedReport(response);

      if (response.status === 'COMPLETED') {
        setIsGenerating(false);
        setStatusMessage('AI Batch Analysis Report completed successfully.');
        loadHistory(0, false);
      } else if (response.status === 'FAILED') {
        setIsGenerating(false);
        setErrorMessage(response.errorMessage || 'AI report generation failed.');
        loadHistory(0, false);
      } else {
        setStatusMessage('Evaluating batch data with AI model... this may take 15-30 seconds.');
        startPolling(response.id, response.clientRequestId);
        loadHistory(0, false);
      }
    } catch (err) {
      setIsGenerating(false);
      if (err?.status === 409) {
        setIsGenerateModalOpen(false);
        setStatusMessage(
          'Another report generation is currently active for this batch. Loading latest reports...'
        );
        loadHistory(0, false);
      } else {
        setErrorMessage(
          err?.status === 502
            ? 'AI service is temporarily unavailable. Please retry later.'
            : err?.message || 'Failed to initiate report generation.'
        );
      }
      throw err;
    } finally {
      isSubmittingRef.current = false;
    }
  };

  // Archive report confirmation
  const handleConfirmArchive = async () => {
    if (!reportToArchive || isArchiving) return;
    const trimmedReason = (archiveReason || '').trim();
    if (!trimmedReason) {
      setErrorMessage('Archive reason is mandatory and cannot be blank.');
      return;
    }
    if (trimmedReason.length > 500) {
      setErrorMessage('Archive reason must not exceed 500 characters.');
      return;
    }

    try {
      setIsArchiving(true);
      const updated = await archiveBatchAnalysisReport(batchId, reportToArchive.id, {
        reason: trimmedReason,
      });

      // Update local report state
      if (selectedReportId === updated.id) {
        setSelectedReport(updated);
      }

      setReports((prev) =>
        prev.map((r) => (r.id === updated.id ? { ...r, archived: true, archiveReason: trimmedReason } : r))
      );

      setReportToArchive(null);
      setArchiveReason('');
      setStatusMessage(`Report #${updated.id} archived successfully.`);
    } catch (err) {
      setErrorMessage(err?.message || 'Failed to archive report.');
    } finally {
      setIsArchiving(false);
    }
  };

  // Confirm and execute permanent report deletion (Admin only)
  const handleConfirmPermanentDelete = async () => {
    if (!reportToDelete || !canPermanentlyDelete || isDeleting) return;

    const trimmedReason = deleteReason.trim();
    if (!trimmedReason) {
      setDeleteError('A deletion reason is mandatory.');
      return;
    }
    if (trimmedReason.length > 500) {
      setDeleteError('Deletion reason must not exceed 500 characters.');
      return;
    }
    if (deleteConfirmationKeyword.trim() !== 'PERMANENTLY DELETE') {
      setDeleteError("Confirmation keyword must be exactly 'PERMANENTLY DELETE'.");
      return;
    }

    setIsDeleting(true);
    setDeleteError(null);
    try {
      await permanentDeleteBatchAnalysisReport(batchId, reportToDelete.id, {
        reason: trimmedReason,
        confirmationKeyword: 'PERMANENTLY DELETE',
      });

      const deletedId = reportToDelete.id;
      if (selectedReportId === deletedId) {
        setSelectedReportId(null);
        setSelectedReport(null);
      }

      setReports((prev) => prev.filter((r) => r.id !== deletedId));

      setReportToDelete(null);
      setDeleteReason('');
      setDeleteConfirmationKeyword('');
      setStatusMessage(`Report #${deletedId} permanently deleted. Durable audit record created.`);
    } catch (err) {
      setDeleteError(err?.message || 'Failed to permanently delete report.');
    } finally {
      setIsDeleting(false);
    }
  };

  // Expand / collapse all sections
  const handleExpandAll = () => {
    setExpandedSections(
      SECTION_DEFINITIONS.reduce((acc, s) => ({ ...acc, [s.key]: true }), {})
    );
  };

  const handleCollapseAll = () => {
    setExpandedSections(
      SECTION_DEFINITIONS.reduce((acc, s) => ({ ...acc, [s.key]: false }), {})
    );
  };

  const toggleSection = (key) => {
    setExpandedSections((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  const TAB_KEYS = ['structured', 'narrative', 'citations'];
  const handleTabKeyDown = (e, currentKey) => {
    const idx = TAB_KEYS.indexOf(currentKey);
    if (e.key === 'ArrowRight') {
      e.preventDefault();
      const nextKey = TAB_KEYS[(idx + 1) % TAB_KEYS.length];
      setActiveTab(nextKey);
      document.getElementById(`tab-${nextKey}`)?.focus();
    } else if (e.key === 'ArrowLeft') {
      e.preventDefault();
      const prevKey = TAB_KEYS[(idx - 1 + TAB_KEYS.length) % TAB_KEYS.length];
      setActiveTab(prevKey);
      document.getElementById(`tab-${prevKey}`)?.focus();
    } else if (e.key === 'Home') {
      e.preventDefault();
      setActiveTab(TAB_KEYS[0]);
      document.getElementById(`tab-${TAB_KEYS[0]}`)?.focus();
    } else if (e.key === 'End') {
      e.preventDefault();
      setActiveTab(TAB_KEYS[TAB_KEYS.length - 1]);
      document.getElementById(`tab-${TAB_KEYS[TAB_KEYS.length - 1]}`)?.focus();
    }
  };

  // Filter reports
  const filteredReports = reports.filter((r) => {
    if (historyFilter === 'active') return !r.archived;
    if (historyFilter === 'archived') return r.archived;
    return true;
  });

  const activeCount = reports.filter((r) => !r.archived).length;
  const archivedCount = reports.filter((r) => r.archived).length;
  const latestCompletedReport = reports.find((r) => r.status === 'COMPLETED' && !r.archived);

  // Access Denied guard
  if (!canView) {
    return (
      <div
        data-testid="access-denied-view"
        className="w-full min-h-[500px] flex items-center justify-center p-6 bg-slate-50"
      >
        <div className="max-w-md w-full bg-white p-6 rounded-xl border border-rose-200 shadow-sm text-center">
          <ShieldAlert className="w-12 h-12 text-rose-500 mx-auto mb-3" />
          <h2 className="text-lg font-bold text-slate-900 mb-1">Access Denied</h2>
          <p className="text-sm text-slate-600 mb-4">
            You do not have permission to view or generate AI Batch Analysis Reports. Access is
            reserved for Production Supervisors, QA Reviewers, and Administrators.
          </p>
          {onClose && (
            <button
              type="button"
              onClick={onClose}
              data-testid="close-workspace-button"
              className="px-4 py-2 text-sm font-semibold text-slate-700 bg-slate-100 hover:bg-slate-200 rounded-lg"
            >
              Back to Batch Traceability
            </button>
          )}
        </div>
      </div>
    );
  }

  return (
    <div
      data-testid="ai-batch-analysis-workspace"
      className="w-full min-h-[calc(100vh-140px)] flex flex-col bg-slate-50 text-slate-900"
    >
      {/* Top Breadcrumb & Page Header */}
      <div className="bg-white border-b border-slate-200 px-4 sm:px-6 py-3.5 sticky top-0 z-20 shadow-2xs">
        <div className="max-w-7xl mx-auto flex flex-wrap items-center justify-between gap-3">
          {/* Breadcrumb & Batch Identity */}
          <div className="flex items-center gap-3">
            {onClose && (
              <button
                type="button"
                onClick={onClose}
                data-testid="back-to-traceability-button"
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-slate-700 bg-slate-100 hover:bg-slate-200 rounded-lg transition-colors focus:outline-none focus:ring-2 focus:ring-pharma-500"
                title="Return to Batch Traceability dossier"
              >
                <ArrowLeft className="w-4 h-4 text-slate-500" />
                <span>Back to Dossier</span>
              </button>
            )}

            <nav aria-label="Breadcrumb" className="flex items-center gap-2 text-xs text-slate-500">
              <span className="font-medium text-slate-700">Batch Traceability</span>
              <span>/</span>
              <span className="font-mono font-semibold text-pharma-700">
                {dossier?.batchNumber || batchNumber}
              </span>
              <span>/</span>
              <span className="font-semibold text-slate-900 flex items-center gap-1">
                <Sparkles className="w-3.5 h-3.5 text-pharma-600" />
                AI Batch Analysis Workspace
              </span>
            </nav>
          </div>

          {/* Primary Action Controls */}
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => {
                loadHistory(0, false);
                if (selectedReportId) {
                  getBatchAnalysisReportDetails(batchId, selectedReportId).then(setSelectedReport).catch(() => {});
                }
              }}
              data-testid="refresh-workspace-button"
              aria-label="Refresh reports history"
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-slate-600 bg-white hover:bg-slate-100 border border-slate-200 rounded-lg shadow-2xs transition-colors"
              title="Refresh report history and current view"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${historyLoading ? 'animate-spin' : ''}`} />
              <span className="hidden sm:inline">Refresh</span>
            </button>

            {canGenerate && (
              <button
                type="button"
                onClick={handleGenerateReport}
                disabled={isGenerating}
                data-testid="workspace-generate-report-button"
                className={`inline-flex items-center gap-2 px-4 py-1.5 text-xs font-semibold text-white rounded-lg shadow-xs transition-colors focus:outline-none focus:ring-2 focus:ring-pharma-500 ${
                  isGenerating
                    ? 'bg-pharma-400 cursor-not-allowed'
                    : 'bg-pharma-600 hover:bg-pharma-700 active:bg-pharma-800'
                }`}
                title="Initiate dedicated AI analysis for this batch"
              >
                <Sparkles className={`w-3.5 h-3.5 ${isGenerating ? 'animate-spin' : 'text-sky-200'}`} />
                <span>{isGenerating ? 'Generating Report...' : 'Generate New Report'}</span>
              </button>
            )}
          </div>
        </div>

        {/* Batch Metadata Strip */}
        <div className="max-w-7xl mx-auto mt-3 pt-3 border-t border-slate-100 flex flex-wrap items-center justify-between gap-x-6 gap-y-2 text-xs">
          <div className="flex flex-wrap items-center gap-x-5 gap-y-1">
            <div className="flex items-center gap-1.5">
              <span className="text-slate-500">Batch:</span>
              <span className="font-mono font-bold text-slate-900">{dossier?.batchNumber || batchNumber}</span>
            </div>
            {dossier?.productName && (
              <div className="flex items-center gap-1.5">
                <span className="text-slate-500">Product:</span>
                <span className="font-medium text-slate-800">{dossier.productName}</span>
                {dossier.productCode && (
                  <span className="text-slate-400 font-mono text-[11px]">({dossier.productCode})</span>
                )}
              </div>
            )}
            {dossier?.orderNumber && (
              <div className="flex items-center gap-1.5">
                <span className="text-slate-500">Order:</span>
                <span className="font-mono text-slate-700">{dossier.orderNumber}</span>
              </div>
            )}
            {dossier?.recipeCode && (
              <div className="flex items-center gap-1.5">
                <span className="text-slate-500">Recipe:</span>
                <span className="font-mono text-slate-700">{dossier.recipeCode}</span>
              </div>
            )}
          </div>

          <div className="flex items-center gap-2">
            {dossier?.batchStatus && (
              <StatusBadge status={dossier.batchStatus} type="batch" size="sm" />
            )}
            {dossier?.qaStatus && (
              <StatusBadge status={dossier.qaStatus} type="qa" size="sm" />
            )}
          </div>
        </div>
      </div>

      {/* Global Status & Alert Banners */}
      <div className="max-w-7xl w-full mx-auto px-4 sm:px-6 pt-4">
        {errorMessage && (
          <div className="mb-4">
            <InlineAlert
              type="error"
              message={errorMessage}
              onClose={() => setErrorMessage(null)}
            />
          </div>
        )}
        {statusMessage && (
          <div className="mb-4">
            <InlineAlert
              type="info"
              message={statusMessage}
              onClose={() => setStatusMessage(null)}
            />
          </div>
        )}
      </div>

      {/* Main Workspace Body: Master-Detail 30 / 70 Grid */}
      <div className="max-w-7xl w-full mx-auto px-4 sm:px-6 pb-8 flex-1 grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Left Column: Report History & Filter (4 cols on lg) */}
        <div data-testid="report-history-sidebar" className="lg:col-span-4 bg-white border border-slate-200 rounded-xl shadow-2xs overflow-hidden flex flex-col">
          {/* History Header & Filter Pills */}
          <div className="p-4 border-b border-slate-200 bg-slate-50/70 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <History className="w-4 h-4 text-slate-600" />
                <h2 className="text-xs font-bold uppercase tracking-wider text-slate-700">
                  Report History
                </h2>
                <span className="px-2 py-0.5 rounded-full text-[11px] font-semibold bg-slate-200 text-slate-700">
                  {reports.length}
                </span>
              </div>
            </div>

            {/* Filter Tabs: Active | Archived | All */}
            <div className="flex items-center p-0.5 bg-slate-200/80 rounded-lg text-xs font-medium">
              <button
                type="button"
                onClick={() => setHistoryFilter('active')}
                data-testid="filter-active-reports"
                className={`flex-1 py-1 text-center rounded-md transition-all ${
                  historyFilter === 'active'
                    ? 'bg-white text-slate-900 font-semibold shadow-2xs'
                    : 'text-slate-600 hover:text-slate-900'
                }`}
              >
                Active ({activeCount})
              </button>
              <button
                type="button"
                onClick={() => setHistoryFilter('archived')}
                data-testid="filter-archived-reports"
                className={`flex-1 py-1 text-center rounded-md transition-all ${
                  historyFilter === 'archived'
                    ? 'bg-white text-slate-900 font-semibold shadow-2xs'
                    : 'text-slate-600 hover:text-slate-900'
                }`}
              >
                Archived ({archivedCount})
              </button>
              <button
                type="button"
                onClick={() => setHistoryFilter('all')}
                data-testid="filter-all-reports"
                className={`flex-1 py-1 text-center rounded-md transition-all ${
                  historyFilter === 'all'
                    ? 'bg-white text-slate-900 font-semibold shadow-2xs'
                    : 'text-slate-600 hover:text-slate-900'
                }`}
              >
                All ({reports.length})
              </button>
            </div>
          </div>

          {/* Report List */}
          <div
            data-testid="report-history-list"
            className="divide-y divide-slate-100 max-h-[600px] overflow-y-auto"
          >
            {historyLoading && reports.length === 0 ? (
              <div className="p-6 text-center text-xs text-slate-400">
                <RefreshCw className="w-5 h-5 mx-auto mb-2 animate-spin text-slate-400" />
                <span>Loading report history...</span>
              </div>
            ) : filteredReports.length === 0 ? (
              <div data-testid="empty-reports-state" className="p-8 text-center text-xs text-slate-500">
                <FileText className="w-8 h-8 text-slate-300 mx-auto mb-2" />
                <p className="font-semibold text-slate-700 mb-1">
                  {historyFilter === 'archived'
                    ? 'No archived reports'
                    : 'No AI batch analysis reports found'}
                </p>
                <p className="text-slate-500 text-[11px] max-w-xs mx-auto mb-3">
                  {historyFilter === 'archived'
                    ? 'Archived reports will appear here for audit retention.'
                    : 'Generate a report to evaluate yield, deviations, eBR steps, and missing records.'}
                </p>
                {canGenerate && historyFilter !== 'archived' && (
                  <button
                    type="button"
                    onClick={handleGenerateReport}
                    disabled={isGenerating}
                    className="px-3 py-1.5 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg transition-colors"
                  >
                    Generate First Report
                  </button>
                )}
              </div>
            ) : (
              filteredReports.map((r) => {
                const isSelected = selectedReportId === r.id;
                return (
                  <div
                    key={r.id}
                    role="button"
                    tabIndex={0}
                    data-testid={`history-item-${r.id}`}
                    onClick={() => setSelectedReportId(r.id)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        setSelectedReportId(r.id);
                      }
                    }}
                    aria-current={isSelected ? 'true' : undefined}
                    aria-label={`Report #${r.id}, status ${r.status}, generated on ${formatAppShortDateTime(r.createdAt)}`}
                    className={`p-3.5 cursor-pointer transition-colors text-left flex flex-col gap-2 focus:outline-none focus:ring-2 focus:ring-pharma-500 focus:ring-inset ${
                      isSelected
                        ? 'bg-pharma-50/70 border-l-4 border-pharma-600'
                        : 'hover:bg-slate-50/80 border-l-4 border-transparent'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-1.5 font-semibold text-xs text-slate-900">
                        <span>Report #{r.id}</span>
                        {r.archived && (
                          <span
                            className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold bg-amber-100 text-amber-800"
                            title={`Archived by @${r.archivedByUsername || 'user'}: ${r.archiveReason || 'No reason specified'}`}
                          >
                            ARCHIVED
                          </span>
                        )}
                      </div>
                      <StatusBadge status={r.status} type="report" size="xs" />
                    </div>

                    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-slate-500">
                      <span className="flex items-center gap-1">
                        <Clock className="w-3 h-3 text-slate-400" />
                        {formatAppShortDateTime(r.createdAt)}
                      </span>
                      {r.generatedByUsername && (
                        <span className="flex items-center gap-1">
                          <User className="w-3 h-3 text-slate-400" />
                          @{r.generatedByUsername}
                        </span>
                      )}
                    </div>

                    {r.status === 'COMPLETED' && (
                      <div className="flex items-center gap-3 text-[11px] pt-1 border-t border-slate-100">
                        {r.yieldPercent != null && (
                          <span className="text-emerald-700 font-medium">
                            Yield: {Number(r.yieldPercent).toFixed(2)}%
                          </span>
                        )}
                        <span className="text-slate-600 font-medium">
                          Deviations: {r.deviationCount ?? 0}
                        </span>
                        {r.model && (
                          <span className="text-slate-400 ml-auto font-mono text-[10px] truncate max-w-[100px]">
                            {r.model}
                          </span>
                        )}
                      </div>
                    )}

                    {/* Report action buttons */}
                    <div className="flex items-center justify-between pt-1 text-[11px]">
                      <button
                        type="button"
                        data-testid={`select-report-${r.id}`}
                        onClick={(e) => {
                          e.stopPropagation();
                          setSelectedReportId(r.id);
                        }}
                        className="text-pharma-600 hover:text-pharma-800 font-semibold inline-flex items-center gap-1"
                      >
                        <span>View Details</span>
                        <ArrowRight className="w-3 h-3" />
                      </button>

                      <div className="flex items-center gap-2">
                        {!r.archived && canGenerate && (
                          <button
                            type="button"
                            data-testid={`archive-report-button-${r.id}`}
                            onClick={(e) => {
                              e.stopPropagation();
                              setReportToArchive(r);
                            }}
                            className="text-slate-400 hover:text-amber-600 text-[11px] font-medium"
                            title="Archive this report to remove from active operational history"
                          >
                            Archive
                          </button>
                        )}
                        {canPermanentlyDelete && (
                          <button
                            type="button"
                            data-testid={`delete-report-button-${r.id}`}
                            onClick={(e) => {
                              e.stopPropagation();
                              setReportToDelete(r);
                              setDeleteReason('');
                              setDeleteConfirmationKeyword('');
                              setDeleteError(null);
                            }}
                            className="text-slate-400 hover:text-rose-600 text-[11px] font-medium"
                            title="Permanently Delete (Admin only)"
                          >
                            Delete
                          </button>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })
            )}
          </div>

          {/* Pagination: Load More */}
          {hasMorePages && (
            <div className="p-2 border-t border-slate-100 bg-slate-50 text-center">
              <button
                type="button"
                onClick={() => loadHistory(historyPage + 1, true)}
                disabled={historyLoading}
                data-testid="load-more-reports-button"
                className="w-full py-1.5 text-xs font-semibold text-slate-700 hover:text-slate-900 hover:bg-slate-200/60 rounded-md transition-colors"
              >
                {historyLoading ? 'Loading more...' : 'Load More Reports'}
              </button>
            </div>
          )}
        </div>

        {/* Right Column: Selected Report Presentation (8 cols on lg) */}
        <div data-testid="report-detail-panel" className="lg:col-span-8 bg-white border border-slate-200 rounded-xl shadow-2xs overflow-hidden flex flex-col">
          {reportLoading && !selectedReport ? (
            <div className="p-12 text-center text-slate-400">
              <RefreshCw className="w-6 h-6 mx-auto mb-2 animate-spin text-slate-400" />
              <p className="text-sm font-medium">Loading report details...</p>
            </div>
          ) : !selectedReport ? (
            <div className="p-12 text-center text-slate-500">
              <FileCheck2 className="w-12 h-12 text-slate-300 mx-auto mb-3" />
              <h3 className="text-sm font-bold text-slate-700 mb-1">No Report Selected</h3>
              <p className="text-xs text-slate-500 max-w-sm mx-auto mb-4">
                Select a historical report from the left sidebar to view its deterministic KPIs,
                analytical findings, and source evidence.
              </p>
              {latestCompletedReport && (
                <button
                  type="button"
                  onClick={() => setSelectedReportId(latestCompletedReport.id)}
                  data-testid="open-latest-report-button"
                  className="px-4 py-2 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-2xs"
                >
                  Open Latest Report #{latestCompletedReport.id}
                </button>
              )}
            </div>
          ) : (
            <div className="flex flex-col">
              {/* Report Header Bar */}
              <div className="p-5 border-b border-slate-200 bg-slate-50/70">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <div className="flex items-center gap-2 mb-1">
                      <h2 className="text-base font-bold text-slate-900">
                        AI Batch Analysis Report #{selectedReport.id}
                      </h2>
                      <StatusBadge status={selectedReport.status} type="report" size="sm" />
                      {selectedReport.analysisMode === 'FOCUSED' ? (
                        <span
                          data-testid="report-focused-badge"
                          className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-sky-100 text-sky-800 border border-sky-200"
                          title={selectedReport.focusSummary || 'Focused analysis report'}
                        >
                          FOCUSED
                        </span>
                      ) : (
                        <span
                          data-testid="report-full-badge"
                          className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-emerald-100 text-emerald-800 border border-emerald-200"
                          title="Full 12-section analysis report"
                        >
                          FULL
                        </span>
                      )}
                      {selectedReport.archived && (
                        <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-amber-100 text-amber-800">
                          ARCHIVED
                        </span>
                      )}
                    </div>
                    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500">
                      <span>Batch: <strong className="font-mono text-slate-700">{selectedReport.batchNumber}</strong></span>
                      <span>Generated: <strong>{formatAppDateTime(selectedReport.createdAt)}</strong></span>
                      {selectedReport.generatedByUsername && (
                        <span>By: <strong>@{selectedReport.generatedByUsername}</strong></span>
                      )}
                      {selectedReport.model && (
                        <span className="font-mono text-[11px] text-slate-500">Model: {selectedReport.model}</span>
                      )}
                    </div>
                    {selectedReport.analysisMode === 'FOCUSED' && (
                      <div
                        data-testid="report-focus-summary-banner"
                        className="mt-3 p-2.5 bg-sky-50/70 border border-sky-200 rounded-lg text-xs text-sky-900 flex items-start gap-2"
                      >
                        <SlidersHorizontal className="w-4 h-4 text-sky-600 shrink-0 mt-0.5" />
                        <div>
                          <span className="font-semibold text-sky-950">Focused Scope: </span>
                          <span>
                            {selectedReport.focusSummary ||
                              (Array.isArray(selectedReport.focusAreas) && selectedReport.focusAreas.length > 0
                                ? selectedReport.focusAreas.join(', ')
                                : 'Targeted operational domains')}
                          </span>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* Actions: Archive Report & Permanent Delete */}
                  <div className="flex items-center gap-2">
                    {!selectedReport.archived && canGenerate && (
                      <button
                        type="button"
                        onClick={() => setReportToArchive(selectedReport)}
                        data-testid="archive-report-button"
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-slate-600 hover:text-amber-700 bg-white hover:bg-amber-50 border border-slate-200 hover:border-amber-200 rounded-lg shadow-2xs transition-colors"
                        title="Archive this report to remove from active operational history"
                      >
                        <Archive className="w-3.5 h-3.5" />
                        <span>Archive</span>
                      </button>
                    )}

                    {canPermanentlyDelete && (
                      <button
                        type="button"
                        onClick={() => {
                          setReportToDelete(selectedReport);
                          setDeleteReason('');
                          setDeleteConfirmationKeyword('');
                          setDeleteError(null);
                        }}
                        data-testid="permanent-delete-report-button"
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-rose-700 hover:text-rose-800 bg-rose-50 hover:bg-rose-100 border border-rose-200 rounded-lg shadow-2xs transition-colors"
                        title="Permanently Delete Report (Admin / Local only)"
                      >
                        <Trash2 className="w-3.5 h-3.5 text-rose-600" />
                        <span>Permanently Delete</span>
                      </button>
                    )}
                  </div>
                </div>

                {/* Archival metadata callout if archived */}
                {selectedReport.archived && (
                  <div className="mt-3 p-3 bg-amber-50/80 border border-amber-200 rounded-lg text-xs text-amber-900 flex items-start gap-2">
                    <Archive className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
                    <div>
                      <p className="font-semibold">
                        This report was archived on {formatAppDateTime(selectedReport.archivedAt)} by @
                        {selectedReport.archivedByUsername || 'user'}.
                      </p>
                      {selectedReport.archiveReason && (
                        <p className="text-amber-800 text-[11px] mt-0.5">
                          Reason: {selectedReport.archiveReason}
                        </p>
                      )}
                    </div>
                  </div>
                )}
              </div>

              {/* Status Specific Bodies */}
              {selectedReport.status === 'GENERATING' ? (
                <div data-testid="report-generating-placeholder" className="p-12 text-center">
                  <div className="w-12 h-12 rounded-full bg-pharma-50 flex items-center justify-center mx-auto mb-3">
                    <Sparkles className="w-6 h-6 text-pharma-600 animate-spin" />
                  </div>
                  <h3 className="text-sm font-bold text-slate-800 mb-1">
                    AI Report Generation In Progress
                  </h3>
                  <p className="text-xs text-slate-500 max-w-md mx-auto mb-4">
                    Evaluating batch execution records, electronic batch records (eBR), QC test
                    results, material reconciliation, and deviation history.
                  </p>
                  <div className="w-48 h-1.5 bg-slate-100 rounded-full mx-auto overflow-hidden">
                    <div className="w-full h-full bg-pharma-600 rounded-full animate-pulse" />
                  </div>
                </div>
              ) : selectedReport.status === 'FAILED' ? (
                <div data-testid="report-failure-alert" className="p-8 text-center">
                  <XCircle className="w-10 h-10 text-rose-500 mx-auto mb-3" />
                  <h3 className="text-sm font-bold text-rose-900 mb-1">Report Generation Failed</h3>
                  <p className="text-xs text-rose-700 max-w-md mx-auto mb-4">
                    {selectedReport.errorMessage ||
                      'AI service was unable to complete the analysis. This attempt does not contain verified batch conclusions.'}
                  </p>
                  {canGenerate && (
                    <button
                      type="button"
                      onClick={handleGenerateReport}
                      data-testid="retry-generation-button"
                      className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-2xs"
                    >
                      <RotateCcw className="w-3.5 h-3.5" />
                      <span>Retry Generation</span>
                    </button>
                  )}
                </div>
              ) : (
                /* Completed Report Presentation */
                <div className="p-5 flex flex-col gap-6">
                  {/* AI Advisory Notice */}
                  <div
                    data-testid="ai-advisory-notice"
                    className="p-3 bg-slate-50 border border-slate-200 rounded-lg text-xs text-slate-600 flex items-start gap-2.5"
                  >
                    <ShieldCheck className="w-4 h-4 text-slate-500 shrink-0 mt-0.5" />
                    <div>
                      <strong className="font-semibold text-slate-800">AI Advisory Notice: </strong>
                      <span>
                        {selectedReport.regulatoryDisclaimer ||
                          'This AI-generated analysis is an operational decision-support tool only. It does not replace Qualified Person / QA batch release.'}
                      </span>
                    </div>
                  </div>

                  {/* Deterministic Manufacturing KPIs Grid */}
                  <div>
                    <h3 className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-2.5 flex items-center gap-1.5">
                      <TrendingUp className="w-3.5 h-3.5 text-slate-600" />
                      <span>Deterministic Manufacturing KPIs</span>
                    </h3>

                    <div
                      data-testid="deterministic-kpi-grid"
                      className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-7 gap-2.5"
                    >
                      {/* Planned Size */}
                      <div className="p-2.5 bg-slate-50 border border-slate-200 rounded-lg text-left">
                        <span className="text-[10px] uppercase font-semibold text-slate-500 block">
                          Planned Size
                        </span>
                        <span
                          data-testid="kpi-planned-batch-size"
                          className="text-xs sm:text-sm font-bold text-slate-900 block truncate"
                        >
                          {selectedReport.deterministicMetrics?.plannedBatchSize != null
                            ? `${Number(selectedReport.deterministicMetrics.plannedBatchSize).toLocaleString()} kg`
                            : 'Unavailable'}
                        </span>
                      </div>

                      {/* Actual Yield */}
                      <div className="p-2.5 bg-slate-50 border border-slate-200 rounded-lg text-left">
                        <span className="text-[10px] uppercase font-semibold text-slate-500 block">
                          Actual Yield
                        </span>
                        <span
                          data-testid="kpi-actual-yield"
                          className="text-xs sm:text-sm font-bold text-slate-900 block truncate"
                        >
                          {selectedReport.deterministicMetrics?.actualYield != null
                            ? `${Number(selectedReport.deterministicMetrics.actualYield).toLocaleString()} kg`
                            : getActualYieldFallbackText(getReportBatchStatus(selectedReport, dossier))}
                        </span>
                      </div>

                      {/* Yield % */}
                      <div className="p-2.5 bg-slate-50 border border-slate-200 rounded-lg text-left">
                        <span className="text-[10px] uppercase font-semibold text-slate-500 block">
                          Yield %
                        </span>
                        <span
                          data-testid="kpi-yield-percent"
                          className="text-xs sm:text-sm font-bold text-emerald-700 block truncate"
                        >
                          {selectedReport.deterministicMetrics?.yieldPercent != null
                            ? `${Number(selectedReport.deterministicMetrics.yieldPercent).toFixed(2)}%`
                            : getYieldMetricFallbackText(getReportBatchStatus(selectedReport, dossier))}
                        </span>
                      </div>

                      {/* Yield Variance (Qty) */}
                      <div className="p-2.5 bg-slate-50 border border-slate-200 rounded-lg text-left">
                        <span className="text-[10px] uppercase font-semibold text-slate-500 block">
                          Yield Variance (Qty)
                        </span>
                        <span
                          data-testid="kpi-yield-variance"
                          className="text-xs sm:text-sm font-bold text-slate-800 block truncate"
                        >
                          {selectedReport.deterministicMetrics?.yieldVariance != null
                            ? `${Number(selectedReport.deterministicMetrics.yieldVariance) > 0 ? '+' : ''}${selectedReport.deterministicMetrics.yieldVariance} kg`
                            : getYieldMetricFallbackText(getReportBatchStatus(selectedReport, dossier))}
                        </span>
                      </div>

                      {/* Execution Steps */}
                      <div className="p-2.5 bg-slate-50 border border-slate-200 rounded-lg text-left">
                        <span className="text-[10px] uppercase font-semibold text-slate-500 block">
                          eBR Steps
                        </span>
                        <span
                          data-testid="kpi-execution-steps"
                          className="text-xs sm:text-sm font-bold text-slate-800 block truncate"
                        >
                          {selectedReport.deterministicMetrics?.executionStepCount != null
                            ? `${selectedReport.deterministicMetrics.executionStepCount} recorded`
                            : 'Not recorded'}
                        </span>
                      </div>

                      {/* QC Checks */}
                      <div className="p-2.5 bg-slate-50 border border-slate-200 rounded-lg text-left">
                        <span className="text-[10px] uppercase font-semibold text-slate-500 block">
                          QC Checks
                        </span>
                        <span
                          data-testid="kpi-qc-checks"
                          className="text-xs sm:text-sm font-bold text-slate-800 block truncate"
                        >
                          {selectedReport.deterministicMetrics?.qcCheckCount != null
                            ? `${selectedReport.deterministicMetrics.qcCheckCount} checks`
                            : 'Not recorded'}
                        </span>
                      </div>

                      {/* Deviations */}
                      <div className="p-2.5 bg-slate-50 border border-slate-200 rounded-lg text-left col-span-2 sm:col-span-3 md:col-span-1">
                        <span className="text-[10px] uppercase font-semibold text-slate-500 block">
                          Deviations
                        </span>
                        <span
                          data-testid="kpi-deviations"
                          className="text-xs sm:text-sm font-bold text-slate-800 block truncate"
                        >
                          {selectedReport.deterministicMetrics?.deviationCount != null
                            ? `${selectedReport.deterministicMetrics.deviationCount} logged`
                            : 'Not recorded'}
                        </span>
                      </div>
                    </div>
                  </div>

                  {/* Navigation Tabs */}
                  <div
                    role="tablist"
                    aria-label="Analysis report views"
                    className="border-b border-slate-200 flex items-center justify-between gap-4"
                  >
                    <div className="flex items-center gap-6 text-xs font-semibold">
                      <button
                        type="button"
                        id="tab-structured"
                        role="tab"
                        aria-selected={activeTab === 'structured'}
                        aria-controls="panel-structured"
                        tabIndex={activeTab === 'structured' ? 0 : -1}
                        onClick={() => setActiveTab('structured')}
                        onKeyDown={(e) => handleTabKeyDown(e, 'structured')}
                        data-testid="tab-structured"
                        className={`pb-2.5 border-b-2 transition-colors focus:outline-none focus:ring-2 focus:ring-pharma-500 ${
                          activeTab === 'structured'
                            ? 'border-pharma-600 text-pharma-700'
                            : 'border-transparent text-slate-500 hover:text-slate-800'
                        }`}
                      >
                        {selectedReport?.analysisMode === 'FOCUSED'
                          ? 'Structured Focused Analysis'
                          : 'Structured 12-Section Analysis'}
                      </button>
                      <button
                        type="button"
                        id="tab-narrative"
                        role="tab"
                        aria-selected={activeTab === 'narrative'}
                        aria-controls="panel-narrative"
                        tabIndex={activeTab === 'narrative' ? 0 : -1}
                        onClick={() => setActiveTab('narrative')}
                        onKeyDown={(e) => handleTabKeyDown(e, 'narrative')}
                        data-testid="tab-narrative"
                        className={`pb-2.5 border-b-2 transition-colors focus:outline-none focus:ring-2 focus:ring-pharma-500 ${
                          activeTab === 'narrative'
                            ? 'border-pharma-600 text-pharma-700'
                            : 'border-transparent text-slate-500 hover:text-slate-800'
                        }`}
                      >
                        Complete Narrative Markdown
                      </button>
                      <button
                        type="button"
                        id="tab-citations"
                        role="tab"
                        aria-selected={activeTab === 'citations'}
                        aria-controls="panel-citations"
                        tabIndex={activeTab === 'citations' ? 0 : -1}
                        onClick={() => setActiveTab('citations')}
                        onKeyDown={(e) => handleTabKeyDown(e, 'citations')}
                        data-testid="tab-citations"
                        className={`pb-2.5 border-b-2 transition-colors focus:outline-none focus:ring-2 focus:ring-pharma-500 ${
                          activeTab === 'citations'
                            ? 'border-pharma-600 text-pharma-700'
                            : 'border-transparent text-slate-500 hover:text-slate-800'
                        }`}
                      >
                        Evidence &amp; Citations ({selectedReport.sourceReferences?.length || 0})
                      </button>
                    </div>

                    {/* Expand / Collapse Controls for Structured Tab */}
                    {activeTab === 'structured' && (
                      <div className="hidden sm:flex items-center gap-2 pb-2 text-[11px] text-slate-500">
                        <button
                          type="button"
                          onClick={handleExpandAll}
                          className="hover:text-slate-900 underline font-medium focus:outline-none focus:ring-1 focus:ring-pharma-500"
                        >
                          Expand All
                        </button>
                        <span>•</span>
                        <button
                          type="button"
                          onClick={handleCollapseAll}
                          className="hover:text-slate-900 underline font-medium focus:outline-none focus:ring-1 focus:ring-pharma-500"
                        >
                          Collapse All
                        </button>
                      </div>
                    )}
                  </div>

                  {/* Tab 1: Structured Analysis */}
                  {activeTab === 'structured' && (() => {
                    const { visibleGroups } = getReportVisibleSections(selectedReport);
                    return (
                      <div
                        id="panel-structured"
                        role="tabpanel"
                        aria-labelledby="tab-structured"
                        tabIndex={0}
                        data-testid="structured-analysis-sections"
                        className="space-y-6 focus:outline-none"
                      >
                        {visibleGroups.map((grp) => (
                          <div key={grp.id} className="space-y-2.5" data-testid={`section-group-${grp.id}`}>
                            <div className="px-3 py-1.5 bg-slate-100/80 border-l-3 border-pharma-600 rounded-r-md">
                              <h4 className="text-xs font-bold uppercase tracking-wider text-slate-700">
                                {grp.title}
                              </h4>
                            </div>
                            <div className="space-y-2.5">
                              {grp.sections.map((def) => {
                                const rawContent = selectedReport.structuredAnalysis?.[def.key] ?? (def.key === 'qcInspectionAnalysis' ? selectedReport.structuredAnalysis?.qualitySummary : undefined);
                                const displayContent = formatSectionContentForDisplay(def.key, rawContent, selectedReport);
                                const isExpanded = expandedSections[def.key] ?? true;
                                const SectionIcon = def.icon;

                                return (
                                  <div
                                    key={def.key}
                                    data-testid={`section-${def.key}`}
                                    className={`border rounded-lg overflow-hidden transition-colors ${
                                      def.isWarningSection && displayContent
                                        ? 'border-amber-200 bg-amber-50/20'
                                        : def.isQaSection
                                        ? 'border-blue-200 bg-blue-50/20'
                                        : 'border-slate-200 bg-white'
                                    }`}
                                  >
                                    <button
                                      type="button"
                                      onClick={() => toggleSection(def.key)}
                                      className="w-full px-4 py-3 text-left flex items-center justify-between bg-slate-50/50 hover:bg-slate-100/50 transition-colors focus:outline-none focus:ring-2 focus:ring-inset focus:ring-pharma-500"
                                    >
                                      <div className="flex items-center gap-2.5">
                                        <SectionIcon
                                          className={`w-4 h-4 ${
                                            def.isWarningSection
                                              ? 'text-amber-600'
                                              : def.isQaSection
                                              ? 'text-blue-600'
                                              : 'text-slate-500'
                                          }`}
                                        />
                                        <span className="text-xs font-bold text-slate-800">
                                          {def.label}
                                        </span>
                                      </div>
                                      <ChevronDown
                                        className={`w-4 h-4 text-slate-400 transition-transform ${
                                          isExpanded ? 'rotate-180' : ''
                                        }`}
                                      />
                                    </button>

                                    {isExpanded && (
                                      <div className="p-4 border-t border-slate-100 text-xs text-slate-700 leading-relaxed">
                                        {def.key === 'riskObservations' &&
                                          hasUnsupportedComplianceClaim(selectedReport) && (
                                            <div
                                              data-testid="compliance-advisory-notice"
                                              className="mb-3 p-3 bg-amber-50 border border-amber-200 rounded-md text-amber-900 text-xs flex items-start gap-2"
                                            >
                                              <ShieldAlert className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
                                              <div>
                                                <strong className="block font-semibold">
                                                  Regulatory Compliance Advisory Notice
                                                </strong>
                                                <p className="text-[11px] mt-0.5">
                                                  {HISTORICAL_COMPLIANCE_ADVISORY}
                                                </p>
                                              </div>
                                            </div>
                                          )}

                                        {displayContent ? (
                                          <FormattedMessage content={displayContent} />
                                        ) : (
                                          <span className="text-slate-400 italic">
                                            No findings or observations recorded for this section.
                                          </span>
                                        )}

                                        {def.isQaSection && (
                                          <div className="mt-3.5 p-3 rounded-lg bg-indigo-50/70 border border-indigo-200/80 text-[11px] text-indigo-900 flex items-start gap-2.5">
                                            <ShieldCheck className="w-4 h-4 text-indigo-700 shrink-0 mt-0.5" aria-hidden="true" />
                                            <div className="space-y-0.5">
                                              <strong className="block font-semibold">Authoritative MES QA Policy:</strong>
                                              <p className="leading-relaxed">
                                                QA release authorization resides exclusively in the Quality Management module.
                                                AI analytical observations cannot grant batch release or bypass formal Qualified Person disposition.
                                              </p>
                                            </div>
                                          </div>
                                        )}
                                      </div>
                                    )}
                                  </div>
                                );
                              })}
                            </div>
                          </div>
                        ))}
                      </div>
                    );
                  })()}

                  {/* Tab 2: Complete Narrative Markdown */}
                  {activeTab === 'narrative' && (
                    <div
                      id="panel-narrative"
                      role="tabpanel"
                      aria-labelledby="tab-narrative"
                      tabIndex={0}
                      data-testid="narrative-markdown-view"
                      className="p-6 bg-slate-50/50 border border-slate-200 rounded-lg text-xs leading-relaxed text-slate-800 space-y-4 focus:outline-none"
                    >
                      {hasUnsupportedComplianceClaim(selectedReport) && (
                        <div
                          data-testid="narrative-compliance-advisory-notice"
                          className="p-3 bg-amber-50 border border-amber-200 rounded-md text-amber-900 text-xs flex items-start gap-2"
                        >
                          <ShieldAlert className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
                          <div>
                            <strong className="block font-semibold">
                              Regulatory Compliance Advisory Notice
                            </strong>
                            <p className="text-[11px] mt-0.5">
                              {HISTORICAL_COMPLIANCE_ADVISORY}
                            </p>
                          </div>
                        </div>
                      )}

                      {selectedReport.reportMarkdown ? (
                        <FormattedMessage
                          content={formatNarrativeMarkdownForDisplay(selectedReport.reportMarkdown)}
                        />
                      ) : (
                        <span className="text-slate-400 italic">
                          Narrative Markdown report content is unavailable.
                        </span>
                      )}
                    </div>
                  )}

                  {/* Tab 3: Evidence & Citations */}
                  {activeTab === 'citations' && (
                    <div
                      id="panel-citations"
                      role="tabpanel"
                      aria-labelledby="tab-citations"
                      tabIndex={0}
                      data-testid="citations-tab-view"
                      className="p-4 bg-slate-50 border border-slate-200 rounded-lg focus:outline-none"
                    >
                      <h4 className="text-xs font-bold text-slate-700 uppercase tracking-wider mb-3">
                        Verified MES Source Records Cited ({selectedReport.sourceReferences?.length || 0})
                      </h4>
                      {selectedReport.sourceReferences && selectedReport.sourceReferences.length > 0 ? (
                        <div className="flex flex-wrap gap-2">
                          {selectedReport.sourceReferences.map((ref, idx) => (
                            <AiSourceReferencePill
                              key={`${ref.recordType}-${ref.recordIdentifier}-${idx}`}
                              source={ref}
                              onNavigate={() => {
                                if (onClose) {
                                  onClose({ isCitationNavigation: true });
                                }
                              }}
                            />
                          ))}
                        </div>
                      ) : (
                        <p className="text-xs text-slate-400 italic">
                          No programmatic source citations attached to this report.
                        </p>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Archive Confirmation Modal */}
      {reportToArchive && (
        <div
          data-testid="archive-confirmation-modal"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-xs"
        >
          <div className="bg-white rounded-xl shadow-xl max-w-md w-full border border-slate-200 overflow-hidden">
            <div className="p-5 border-b border-slate-100 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Archive className="w-5 h-5 text-amber-600" />
                <h3 className="text-sm font-bold text-slate-900">
                  Archive AI Batch Analysis Report #{reportToArchive.id}
                </h3>
              </div>
              <button
                type="button"
                onClick={() => {
                  setReportToArchive(null);
                  setArchiveReason('');
                }}
                className="text-slate-400 hover:text-slate-600"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-5 text-xs text-slate-600 space-y-3">
              <p>
                Archiving this report removes it from active operational history while{' '}
                <strong className="text-slate-800">
                  strictly preserving its complete content and immutable GxP audit trail
                </strong>{' '}
                in compliance with regulatory retention standards.
              </p>

              <div>
                <label className="block text-slate-700 font-semibold mb-1">
                  Reason for Archival <span className="text-rose-600 font-bold" aria-label="required">*</span>
                </label>
                <textarea
                  data-testid="archive-reason-input"
                  required
                  value={archiveReason}
                  onChange={(e) => setArchiveReason(e.target.value)}
                  placeholder="Mandatory justification required by GxP standards (e.g. Superseded by newer analysis with complete QC data)"
                  className="w-full h-20 p-2 text-xs border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-pharma-500"
                />
                {!archiveReason.trim() && (
                  <p className="text-[11px] text-rose-600 mt-1">
                    An archive reason is required before confirming.
                  </p>
                )}
              </div>
            </div>

            <div className="p-4 bg-slate-50 border-t border-slate-100 flex items-center justify-end gap-2">
              <button
                type="button"
                onClick={() => {
                  setReportToArchive(null);
                  setArchiveReason('');
                }}
                data-testid="cancel-archive-button"
                className="px-3 py-1.5 text-xs font-semibold text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-100"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleConfirmArchive}
                disabled={isArchiving || !archiveReason.trim()}
                data-testid="confirm-archive-button"
                className={`px-3 py-1.5 text-xs font-semibold text-white rounded-lg shadow-2xs transition-colors ${
                  isArchiving || !archiveReason.trim()
                    ? 'bg-amber-300 cursor-not-allowed'
                    : 'bg-amber-600 hover:bg-amber-700 active:bg-amber-800'
                }`}
              >
                {isArchiving ? 'Archiving...' : 'Confirm Archive'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Permanent Delete Confirmation Modal (Admin only) */}
      {reportToDelete && canPermanentlyDelete && (
        <div
          data-testid="permanent-delete-confirmation-modal"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-xs"
        >
          <div className="bg-white rounded-xl shadow-2xl max-w-lg w-full border border-rose-200 overflow-hidden">
            <div className="p-5 border-b border-rose-100 bg-rose-50/50 flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <div className="p-2 bg-rose-100 text-rose-700 rounded-lg">
                  <AlertTriangle className="w-5 h-5 text-rose-600" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-rose-950">
                    Permanently Delete AI Batch Analysis Report #{reportToDelete.id}
                  </h3>
                  <p className="text-[11px] text-rose-700">
                    Batch: {reportToDelete.batchNumber || dossier?.batchNumber || batchNumber} • Local Development Only
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => {
                  setReportToDelete(null);
                  setDeleteReason('');
                  setDeleteConfirmationKeyword('');
                  setDeleteError(null);
                }}
                className="text-slate-400 hover:text-slate-600"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-5 text-xs text-slate-600 space-y-4">
              <div className="p-3 bg-rose-50/80 border border-rose-200 rounded-lg text-rose-800 space-y-1">
                <p className="font-semibold text-rose-900">
                  Warning: Irreversible Physical Deletion
                </p>
                <p className="text-[11px] leading-relaxed">
                  This report will be permanently removed from the database. A durable administrative
                  audit record containing the full pre-deletion snapshot, deleting administrator, and justification
                  will be created.
                </p>
              </div>

              {deleteError && (
                <div
                  data-testid="permanent-delete-error"
                  className="p-2.5 bg-rose-50 border border-rose-300 text-rose-800 text-[11px] rounded-lg"
                >
                  {deleteError}
                </div>
              )}

              <div>
                <label className="block text-slate-700 font-semibold mb-1">
                  Reason for Permanent Deletion <span className="text-rose-600 font-bold" aria-label="required">*</span>
                </label>
                <textarea
                  data-testid="permanent-delete-reason-input"
                  required
                  value={deleteReason}
                  onChange={(e) => {
                    setDeleteReason(e.target.value);
                    if (deleteError) setDeleteError(null);
                  }}
                  maxLength={500}
                  placeholder="Mandatory administrative justification (max 500 characters)"
                  className="w-full h-20 p-2 text-xs border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-rose-500"
                />
                <div className="flex justify-between items-center text-[11px] text-slate-400 mt-0.5">
                  <span>{!deleteReason.trim() ? 'A deletion reason is required.' : ''}</span>
                  <span>{deleteReason.length}/500</span>
                </div>
              </div>

              <div>
                <label className="block text-slate-700 font-semibold mb-1">
                  Confirmation Keyword <span className="text-rose-600 font-bold" aria-label="required">*</span>
                </label>
                <p className="text-[11px] text-slate-500 mb-1.5">
                  Type <code className="px-1.5 py-0.5 bg-slate-100 text-rose-700 font-mono font-bold rounded">PERMANENTLY DELETE</code> to confirm.
                </p>
                <input
                  type="text"
                  data-testid="permanent-delete-confirm-input"
                  required
                  value={deleteConfirmationKeyword}
                  onChange={(e) => {
                    setDeleteConfirmationKeyword(e.target.value);
                    if (deleteError) setDeleteError(null);
                  }}
                  placeholder="PERMANENTLY DELETE"
                  className="w-full p-2 text-xs border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-rose-500 font-mono"
                />
              </div>
            </div>

            <div className="p-4 bg-slate-50 border-t border-slate-100 flex items-center justify-end gap-2">
              <button
                type="button"
                onClick={() => {
                  setReportToDelete(null);
                  setDeleteReason('');
                  setDeleteConfirmationKeyword('');
                  setDeleteError(null);
                }}
                data-testid="cancel-delete-button"
                className="px-3 py-1.5 text-xs font-semibold text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-100"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleConfirmPermanentDelete}
                disabled={
                  isDeleting ||
                  !deleteReason.trim() ||
                  deleteConfirmationKeyword.trim() !== 'PERMANENTLY DELETE'
                }
                data-testid="confirm-delete-button"
                className={`px-3 py-1.5 text-xs font-semibold text-white rounded-lg shadow-2xs transition-colors ${
                  isDeleting ||
                  !deleteReason.trim() ||
                  deleteConfirmationKeyword.trim() !== 'PERMANENTLY DELETE'
                    ? 'bg-rose-300 cursor-not-allowed'
                    : 'bg-rose-600 hover:bg-rose-700 active:bg-rose-800'
                }`}
              >
                {isDeleting ? 'Deleting...' : 'Confirm Permanent Deletion'}
              </button>
            </div>
          </div>
        </div>
      )}
      {/* Generate Report Selection Modal */}
      <BatchAnalysisGenerateModal
        isOpen={isGenerateModalOpen}
        onClose={() => setIsGenerateModalOpen(false)}
        onConfirm={handleConfirmGenerate}
        batchNumber={dossier?.batchNumber || batchNumber}
        productName={dossier?.productName}
      />
    </div>
  );
}
