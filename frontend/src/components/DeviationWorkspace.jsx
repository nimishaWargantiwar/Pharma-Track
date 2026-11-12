import React, { useState, useEffect, useCallback, useRef, useMemo, useId } from 'react';
import {
  ArrowUpRight,
  RefreshCw,
  AlertTriangle,
  CheckCircle2,
  Clock,
  Lock,
  History,
  FileText,
  User,
  FlaskConical,
  ClipboardList,
  Layers,
  Wrench,
  Plus,
  Play,
  RotateCcw,
  ShieldCheck,
  Calendar,
  ExternalLink,
  ChevronDown,
  ChevronRight,
} from 'lucide-react';
import {
  getDeviationById,
  startDeviationInvestigation,
  recordDeviationRootCause,
  startDeviationCapa,
  submitDeviationForVerification,
  reopenDeviationCapa,
  closeDeviation,
  createCapaAction,
  startCapaAction,
  completeCapaAction,
  cancelCapaAction,
} from '../services/deviationApi';
import {
  CAPA_TYPE_OPTIONS,
  DEVIATION_LIFECYCLE_STAGES,
  canCancelCapaAction,
  canEditDeviationCapas,
  canExecuteDeviationCapas,
  getCategoryLabel,
  getDeviationStageIndex,
  getTodayIsoDateInAppTimeZone,
  mapCapaItemValidationErrors,
  validateCapaDueDate,
} from '../utils/deviationUtils';
import {
  getCapaStatusBadge,
  getCapaTypeBadge,
  getDeviationPriorityBadge,
  getDeviationSeverityBadge,
  getDeviationStatusBadge,
} from './DeviationBadges';
import {
  AuditDiffViewer,
  DEFAULT_PAGE_SIZE,
  InlineAlert,
  PageHeader,
  Pagination,
  paginateItems,
  ProgressBar,
  redactAuditText,
  StatusBadge,
  summarizeAuditChanges,
} from './ui/index.js';
import { useAuth } from '../context/AuthContext.jsx';
import { useNavigation } from '../context/NavigationContext.jsx';
import {
  canInvestigateDeviation,
  canCreateCapaAction,
  canExecuteCapaAction,
  canStartCapaPhase,
  canCancelCapaActionRole,
  canReopenCapaPhase,
  canVerifyAndCloseDeviation,
  canViewRecipes,
} from '../utils/rbacUtils.js';
import { formatAppDateTime, formatCalendarDate } from '../utils/dateTimeUtils.js';

const ACTOR_MAX = 100;
const TITLE_MAX = 200;
const RCA_CATEGORY_MAX = 100;
const PLAN_NOTES_MAX = 2000;
const SUMMARY_MAX = 3000;
const CAPA_NOTES_MAX = 1000;

const ROOT_CAUSE_SUGGESTIONS = [
  'Equipment / Instrument Calibration',
  'Procedural / Human Execution Error',
  'Raw Material / Component Variance',
  'Process Parameter Control Drift',
  'Analytical Method / Lab Testing',
  'HVAC / Environmental Control',
  'Packaging / Labeling Control',
];

function formatDateTime(dateTimeString) {
  return formatAppDateTime(dateTimeString);
}

export default function DeviationWorkspace({
  deviationId,
  backLabel = 'Back to Deviations & CAPA',
  onBack,
  onOpenBatchDossier,
  onDeviationUpdated,
  onNotify,
}) {
  const { role, actorDisplay } = useAuth();
  const { navigateToTab } = useNavigation();
  const allowInvestigate = canInvestigateDeviation(role);
  const allowCreateCapa = canCreateCapaAction(role);
  const allowExecuteCapa = canExecuteCapaAction(role);
  const allowStartCapaPhase = canStartCapaPhase(role);
  const allowCancelCapa = canCancelCapaActionRole(role);
  const allowReopenCapa = canReopenCapaPhase(role);
  const allowCloseDeviation = canVerifyAndCloseDeviation(role);
  const canOpenRecipes = canViewRecipes(role);

  const capaItemInputId = useId();
  const errUid = useId();
  const invSummaryErrId = `${errUid}-inv-summary-err`;
  const rcaCategoryErrId = `${errUid}-rca-category-err`;
  const rcaAnalysisErrId = `${errUid}-rca-analysis-err`;
  const rcaImpactErrId = `${errUid}-rca-impact-err`;
  const rcaInvSummaryErrId = `${errUid}-rca-inv-summary-err`;
  const capaOwnerErrId = `${errUid}-capa-owner-err`;
  const capaDueDateErrId = `${errUid}-capa-due-date-err`;
  const capaTitleErrId = `${errUid}-capa-title-err`;
  const capaPlanErrId = `${errUid}-capa-plan-err`;
  const startCapaPlanErrId = `${errUid}-start-capa-plan-err`;
  const submitVerifNotesErrId = `${errUid}-submit-verif-notes-err`;
  const closeVerifNotesErrId = `${errUid}-close-verif-notes-err`;
  const reopenReasonErrId = `${errUid}-reopen-reason-err`;

  const [detail, setDetail] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState(null);
  const [actionError, setActionError] = useState('');
  const [isMutating, setIsMutating] = useState(false);
  const isMutatingRef = useRef(false);

  // Stage 1 -> 2: Investigation form
  const [investigationSummary, setInvestigationSummary] = useState('');
  const [investigationErrors, setInvestigationErrors] = useState({});

  // Stage 2 -> 3: Root Cause & Impact form
  const [rootCauseCategory, setRootCauseCategory] = useState('');
  const [rootCauseAnalysis, setRootCauseAnalysis] = useState('');
  const [impactAssessment, setImpactAssessment] = useState('');
  const [rcaInvestigationSummary, setRcaInvestigationSummary] = useState('');
  const [rcaErrors, setRcaErrors] = useState({});

  // Stage 3 -> 4: Enter CAPA In Progress form
  const [capaPlanSummary, setCapaPlanSummary] = useState('');
  const [capaStartErrors, setCapaStartErrors] = useState({});

  // Add CAPA Action form (ownerName remains editable: business assignee of the CAPA action)
  const [showAddCapaForm, setShowAddCapaForm] = useState(false);
  const [capaType, setCapaType] = useState('CORRECTIVE');
  const [capaTitle, setCapaTitle] = useState('');
  const [capaOwnerName, setCapaOwnerName] = useState('');
  const [capaDueDate, setCapaDueDate] = useState('');
  const [capaActionPlan, setCapaActionPlan] = useState('');
  const [capaFormErrors, setCapaFormErrors] = useState({});

  // Inline CAPA item action form ('start' | 'complete' | 'cancel')
  const [activeCapaItemForm, setActiveCapaItemForm] = useState(null); // { capaId, mode }
  const [capaItemText, setCapaItemText] = useState('');
  const [capaItemErrors, setCapaItemErrors] = useState({});

  // Stage 4 -> 5: Submit for Verification form
  const [submissionNotes, setSubmissionNotes] = useState('');
  const [submitVerifyErrors, setSubmitVerifyErrors] = useState({});

  // Stage 5 -> 6: Close Deviation form OR Reopen CAPA form
  const [verificationNotes, setVerificationNotes] = useState('');
  const [closeErrors, setCloseErrors] = useState({});

  const [showReopenForm, setShowReopenForm] = useState(false);
  const [reopenReason, setReopenReason] = useState('');
  const [reopenErrors, setReopenErrors] = useState({});

  // Scoped Audit Trail pagination & expanded diff rows
  const [auditPage, setAuditPage] = useState(1);
  const [auditPageSize, setAuditPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [expandedAuditIds, setExpandedAuditIds] = useState({});

  const resetActionForms = useCallback((loadedDetail) => {
    setActionError('');
    setInvestigationErrors({});
    setRcaErrors({});
    setCapaStartErrors({});
    setCapaFormErrors({});
    setCapaItemErrors({});
    setSubmitVerifyErrors({});
    setCloseErrors({});
    setReopenErrors({});

    setInvestigationSummary('');

    setRootCauseCategory('');
    setRootCauseAnalysis('');
    setImpactAssessment('');
    setRcaInvestigationSummary(loadedDetail?.investigationSummary || '');

    setCapaPlanSummary(loadedDetail?.capaPlanSummary || '');

    setShowAddCapaForm(false);
    setCapaType('CORRECTIVE');
    setCapaTitle('');
    setCapaOwnerName('');
    setCapaDueDate('');
    setCapaActionPlan('');

    setActiveCapaItemForm(null);
    setCapaItemText('');

    setSubmissionNotes('');

    setVerificationNotes('');

    setShowReopenForm(false);
    setReopenReason('');
  }, []);

  const loadDetail = useCallback(
    async ({ manualRefresh = false } = {}) => {
      if (!deviationId) return;
      if (manualRefresh) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }
      setError(null);
      try {
        const data = await getDeviationById(deviationId);
        setDetail(data || null);
        if (!manualRefresh) {
          resetActionForms(data);
        } else {
          setRcaInvestigationSummary((prev) =>
            prev || data?.investigationSummary || ''
          );
        }
      } catch (err) {
        const message = err.message || 'Unable to load deviation workspace';
        setError(message);
        if (manualRefresh && onNotify) {
          onNotify({ type: 'error', message });
        }
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [deviationId, onNotify, resetActionForms]
  );

  useEffect(() => {
    setDetail(null);
    setAuditPage(1);
    setExpandedAuditIds({});
    loadDetail();
  }, [loadDetail]);

  const runMutation = async (fn, successMessage, { resetForms = true } = {}) => {
    if (isMutatingRef.current || isMutating) return null;
    isMutatingRef.current = true;
    setIsMutating(true);
    setActionError('');
    try {
      const result = await fn();
      const updatedDetail =
        result && Array.isArray(result.capaActions) && result.status
          ? result
          : await getDeviationById(deviationId);

      setDetail(updatedDetail || null);
      if (resetForms) {
        resetActionForms(updatedDetail);
      }
      if (onNotify) {
        onNotify({ type: 'success', message: successMessage });
      }
      if (onDeviationUpdated) {
        await onDeviationUpdated();
      }
      return updatedDetail;
    } catch (err) {
      setActionError(err.message || 'Action could not be completed');
      if (onNotify) {
        onNotify({ type: 'error', message: err.message || 'Action failed' });
      }
      throw err;
    } finally {
      isMutatingRef.current = false;
      setIsMutating(false);
    }
  };

  // --- Handlers for each workflow transition & CAPA action ---

  const handleStartInvestigation = async (e) => {
    e.preventDefault();
    if (!allowInvestigate) return;
    const errs = {};
    const summary = investigationSummary.trim();
    if (!summary) errs.investigationSummary = 'Investigation summary is mandatory';
    else if (summary.length > SUMMARY_MAX) errs.investigationSummary = `Max ${SUMMARY_MAX} characters`;

    if (Object.keys(errs).length > 0) {
      setInvestigationErrors(errs);
      return;
    }
    setInvestigationErrors({});
    try {
      await runMutation(
        () =>
          startDeviationInvestigation(detail.id, {
            investigationSummary: summary,
          }),
        `${detail.deviationNumber} moved to UNDER_INVESTIGATION.`
      );
    } catch (err) {
      if (err.validationErrors) setInvestigationErrors(err.validationErrors);
    }
  };

  const handleRecordRootCause = async (e) => {
    e.preventDefault();
    if (!allowInvestigate) return;
    const errs = {};
    const rcCat = rootCauseCategory.trim();
    const rcAnalysis = rootCauseAnalysis.trim();
    const impact = impactAssessment.trim();
    const invSummary = rcaInvestigationSummary.trim();

    if (!rcCat) errs.rootCauseCategory = 'Root cause category is mandatory';
    else if (rcCat.length > RCA_CATEGORY_MAX) errs.rootCauseCategory = `Max ${RCA_CATEGORY_MAX} characters`;
    if (!rcAnalysis) errs.rootCauseAnalysis = 'Root cause analysis is mandatory';
    else if (rcAnalysis.length > SUMMARY_MAX) errs.rootCauseAnalysis = `Max ${SUMMARY_MAX} characters`;
    if (!impact) errs.impactAssessment = 'Impact assessment is mandatory';
    else if (impact.length > PLAN_NOTES_MAX) errs.impactAssessment = `Max ${PLAN_NOTES_MAX} characters`;
    if (invSummary.length > SUMMARY_MAX) errs.investigationSummary = `Max ${SUMMARY_MAX} characters`;

    if (Object.keys(errs).length > 0) {
      setRcaErrors(errs);
      return;
    }
    setRcaErrors({});
    try {
      await runMutation(
        () =>
          recordDeviationRootCause(detail.id, {
            rootCauseCategory: rcCat,
            rootCauseAnalysis: rcAnalysis,
            impactAssessment: impact,
            investigationSummary: invSummary || null,
          }),
        `Root cause recorded for ${detail.deviationNumber}.`
      );
    } catch (err) {
      if (err.validationErrors) setRcaErrors(err.validationErrors);
    }
  };

  const handleCreateCapaAction = async (e) => {
    e.preventDefault();
    if (!allowCreateCapa) return;
    const errs = {};
    const tTitle = capaTitle.trim();
    const tOwner = capaOwnerName.trim();
    const tPlan = capaActionPlan.trim();
    const dueError = validateCapaDueDate(capaDueDate, getTodayIsoDateInAppTimeZone());

    if (!capaType) errs.capaType = 'CAPA type is mandatory';
    if (!tTitle) errs.title = 'CAPA title is mandatory';
    else if (tTitle.length > TITLE_MAX) errs.title = `Max ${TITLE_MAX} characters`;
    if (!tOwner) errs.ownerName = 'Action owner name is mandatory';
    else if (tOwner.length > ACTOR_MAX) errs.ownerName = `Max ${ACTOR_MAX} characters`;
    if (dueError) errs.dueDate = dueError;
    if (!tPlan) errs.actionPlan = 'CAPA action plan is mandatory';
    else if (tPlan.length > PLAN_NOTES_MAX) errs.actionPlan = `Max ${PLAN_NOTES_MAX} characters`;

    if (Object.keys(errs).length > 0) {
      setCapaFormErrors(errs);
      return;
    }
    setCapaFormErrors({});
    try {
      await runMutation(
        () =>
          createCapaAction(detail.id, {
            capaType,
            title: tTitle,
            actionPlan: tPlan,
            ownerName: tOwner,
            dueDate: capaDueDate.trim(),
          }),
        `CAPA action created for ${detail.deviationNumber}.`,
        { resetForms: true }
      );
    } catch (err) {
      if (err.validationErrors) setCapaFormErrors(err.validationErrors);
    }
  };

  const handleCapaItemActionSubmit = async (e) => {
    e.preventDefault();
    if (!activeCapaItemForm) return;
    const { capaId, mode } = activeCapaItemForm;
    if ((mode === 'start' || mode === 'complete') && !allowExecuteCapa) return;
    if (mode === 'cancel' && !allowCancelCapa) return;

    const errs = {};
    const text = capaItemText.trim();

    if (mode === 'complete') {
      if (!text) errs.text = 'Completion evidence is mandatory';
      else if (text.length > PLAN_NOTES_MAX) errs.text = `Max ${PLAN_NOTES_MAX} characters`;
    } else if (mode === 'cancel') {
      if (!text) errs.text = 'Cancellation reason is mandatory';
      else if (text.length > CAPA_NOTES_MAX) errs.text = `Max ${CAPA_NOTES_MAX} characters`;
    } else if (text.length > CAPA_NOTES_MAX) {
      errs.text = `Max ${CAPA_NOTES_MAX} characters`;
    }

    if (Object.keys(errs).length > 0) {
      setCapaItemErrors(errs);
      return;
    }
    setCapaItemErrors({});
    try {
      if (mode === 'start') {
        await runMutation(
          () => startCapaAction(capaId, { notes: text || null }),
          'CAPA action started (IN_PROGRESS).'
        );
      } else if (mode === 'complete') {
        await runMutation(
          () => completeCapaAction(capaId, { completionEvidence: text }),
          'CAPA action marked COMPLETED with evidence.'
        );
      } else if (mode === 'cancel') {
        await runMutation(
          () => cancelCapaAction(capaId, { cancellationReason: text }),
          'CAPA action cancelled.'
        );
      }
    } catch (err) {
      if (err.validationErrors) {
        setCapaItemErrors(mapCapaItemValidationErrors(err.validationErrors));
      }
    }
  };

  const handleStartDeviationCapa = async (e) => {
    e.preventDefault();
    if (!allowStartCapaPhase) return;
    const errs = {};
    const plan = capaPlanSummary.trim();
    if (plan.length > PLAN_NOTES_MAX) errs.capaPlanSummary = `Max ${PLAN_NOTES_MAX} characters`;

    if (Object.keys(errs).length > 0) {
      setCapaStartErrors(errs);
      return;
    }
    setCapaStartErrors({});
    try {
      await runMutation(
        () =>
          startDeviationCapa(detail.id, {
            capaPlanSummary: plan || null,
          }),
        `${detail.deviationNumber} entered CAPA_IN_PROGRESS.`
      );
    } catch (err) {
      if (err.validationErrors) setCapaStartErrors(err.validationErrors);
    }
  };

  const handleSubmitForVerification = async (e) => {
    e.preventDefault();
    if (!allowInvestigate) return;
    const errs = {};
    const notes = submissionNotes.trim();
    if (notes.length > PLAN_NOTES_MAX) errs.submissionNotes = `Max ${PLAN_NOTES_MAX} characters`;

    if (Object.keys(errs).length > 0) {
      setSubmitVerifyErrors(errs);
      return;
    }
    setSubmitVerifyErrors({});
    try {
      await runMutation(
        () =>
          submitDeviationForVerification(detail.id, {
            submissionNotes: notes || null,
          }),
        `${detail.deviationNumber} submitted for QA verification.`
      );
    } catch (err) {
      if (err.validationErrors) setSubmitVerifyErrors(err.validationErrors);
    }
  };

  const handleReopenCapa = async (e) => {
    e.preventDefault();
    if (!allowReopenCapa) return;
    const errs = {};
    const reason = reopenReason.trim();
    if (!reason) errs.reason = 'Reopen reason is mandatory';
    else if (reason.length > PLAN_NOTES_MAX) errs.reason = `Max ${PLAN_NOTES_MAX} characters`;

    if (Object.keys(errs).length > 0) {
      setReopenErrors(errs);
      return;
    }
    setReopenErrors({});
    try {
      await runMutation(
        () => reopenDeviationCapa(detail.id, { reason }),
        `${detail.deviationNumber} reopened to CAPA_IN_PROGRESS.`
      );
    } catch (err) {
      if (err.validationErrors) setReopenErrors(err.validationErrors);
    }
  };

  const handleCloseDeviation = async (e) => {
    e.preventDefault();
    if (!allowCloseDeviation) return;
    const errs = {};
    const notes = verificationNotes.trim();
    if (!notes) errs.verificationNotes = 'Verification notes are mandatory';
    else if (notes.length > PLAN_NOTES_MAX) errs.verificationNotes = `Max ${PLAN_NOTES_MAX} characters`;

    if (Object.keys(errs).length > 0) {
      setCloseErrors(errs);
      return;
    }
    setCloseErrors({});
    try {
      await runMutation(
        () =>
          closeDeviation(detail.id, {
            verificationNotes: notes,
          }),
        `${detail.deviationNumber} verified and CLOSED.`
      );
    } catch (err) {
      if (err.validationErrors) setCloseErrors(err.validationErrors);
    }
  };

  const capaActions = useMemo(
    () => (Array.isArray(detail?.capaActions) ? detail.capaActions : []),
    [detail]
  );
  const auditLogs = useMemo(
    () => (Array.isArray(detail?.auditLogs) ? detail.auditLogs : []),
    [detail]
  );
  const closureBlockers = Array.isArray(detail?.closureBlockers) ? detail.closureBlockers : [];
  const activeCapas = capaActions.filter((c) => c.status !== 'CANCELLED');
  const unfinishedCapas = activeCapas.filter(
    (c) => c.status === 'OPEN' || c.status === 'IN_PROGRESS'
  );
  const completedCapas = activeCapas.filter((c) => c.status === 'COMPLETED');
  const canSubmitVerification =
    detail?.status === 'CAPA_IN_PROGRESS' &&
    completedCapas.length > 0 &&
    unfinishedCapas.length === 0;
  const capaMutationsAllowed = canEditDeviationCapas(detail?.status);
  const capaExecutionAllowed = canExecuteDeviationCapas(detail?.status);
  const currentStageIndex = getDeviationStageIndex(detail?.status);
  const todayInKolkata = getTodayIsoDateInAppTimeZone();

  const correctiveCapas = useMemo(
    () => capaActions.filter((c) => c.capaType === 'CORRECTIVE'),
    [capaActions]
  );
  const preventiveCapas = useMemo(
    () => capaActions.filter((c) => c.capaType === 'PREVENTIVE'),
    [capaActions]
  );
  const otherCapas = useMemo(
    () =>
      capaActions.filter(
        (c) => c.capaType !== 'CORRECTIVE' && c.capaType !== 'PREVENTIVE'
      ),
    [capaActions]
  );

  const paginatedAuditLogs = useMemo(
    () => paginateItems(auditLogs, auditPage, auditPageSize),
    [auditLogs, auditPage, auditPageSize]
  );

  const toggleAuditRowDiff = (entryKey) => {
    setExpandedAuditIds((prev) => ({
      ...prev,
      [entryKey]: !prev[entryKey],
    }));
  };

  const renderCapaCard = (capa) => {
    const canStart = capaExecutionAllowed && allowExecuteCapa && capa.status === 'OPEN';
    const canComplete =
      capaExecutionAllowed &&
      allowExecuteCapa &&
      (capa.status === 'OPEN' || capa.status === 'IN_PROGRESS');
    const canCancel =
      allowCancelCapa &&
      canCancelCapaAction(detail?.status, capa.status, activeCapas.length);
    const isLastActiveInProgress =
      allowCancelCapa &&
      detail?.status === 'CAPA_IN_PROGRESS' &&
      (capa.status === 'OPEN' || capa.status === 'IN_PROGRESS') &&
      activeCapas.length <= 1;
    const isActiveItemForm = activeCapaItemForm?.capaId === capa.id;

    return (
      <div
        key={capa.id}
        className={`rounded-xl border p-4 space-y-3 shadow-sm ${
          capa.overdue && capa.status !== 'COMPLETED' && capa.status !== 'CANCELLED'
            ? 'border-rose-200 bg-rose-50/20'
            : 'border-slate-200 bg-white'
        }`}
      >
        <div className="flex flex-col md:flex-row md:items-start md:justify-between gap-2.5">
          <div className="space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono font-bold text-xs text-slate-900 bg-slate-100 px-2 py-0.5 rounded border border-slate-200 tabular-nums">
                {capa.capaNumber}
              </span>
              {getCapaTypeBadge(capa.capaType)}
              {getCapaStatusBadge(capa.status, capa.overdue)}
            </div>
            <h5 className="text-sm font-bold text-slate-900">{capa.title}</h5>
            <p className="text-xs text-slate-700 whitespace-pre-wrap leading-relaxed">
              {capa.actionPlan}
            </p>
          </div>

          {/* Action Buttons (Preserve exact RBAC & button labels) */}
          {capaMutationsAllowed &&
            (canStart || canComplete || canCancel || isLastActiveInProgress) && (
              <div className="flex flex-wrap items-center gap-1.5 shrink-0">
                {canStart && (
                  <button
                    type="button"
                    disabled={isMutating}
                    onClick={() => {
                      setActiveCapaItemForm({ capaId: capa.id, mode: 'start' });
                      setCapaItemText('');
                      setCapaItemErrors({});
                    }}
                    className="px-2.5 py-1 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg"
                  >
                    Start
                  </button>
                )}
                {canComplete && (
                  <button
                    type="button"
                    disabled={isMutating}
                    onClick={() => {
                      setActiveCapaItemForm({ capaId: capa.id, mode: 'complete' });
                      setCapaItemText('');
                      setCapaItemErrors({});
                    }}
                    className="px-2.5 py-1 text-xs font-semibold text-emerald-700 bg-emerald-50 hover:bg-emerald-100 border border-emerald-200 rounded-lg"
                  >
                    Complete
                  </button>
                )}
                {(canCancel || isLastActiveInProgress) && (
                  <button
                    type="button"
                    disabled={isMutating || !canCancel}
                    title={
                      isLastActiveInProgress
                        ? 'Cannot cancel the last active CAPA action in CAPA_IN_PROGRESS. Add a replacement CAPA first.'
                        : undefined
                    }
                    onClick={() => {
                      if (!canCancel) return;
                      setActiveCapaItemForm({ capaId: capa.id, mode: 'cancel' });
                      setCapaItemText('');
                      setCapaItemErrors({});
                    }}
                    className="px-2.5 py-1 text-xs font-semibold text-rose-700 bg-rose-50 hover:bg-rose-100 border border-rose-200 rounded-lg disabled:opacity-45 disabled:cursor-not-allowed disabled:hover:bg-rose-50"
                  >
                    Cancel
                  </button>
                )}
              </div>
            )}
        </div>

        {detail.status === 'ROOT_CAUSE_IDENTIFIED' && capa.status === 'OPEN' && (
          <p className="text-[11px] text-pharma-700 bg-pharma-50/70 border border-pharma-100 rounded px-2.5 py-1">
            Start and Complete actions unlock after a QA Reviewer moves this deviation to{' '}
            <span className="font-mono font-semibold">CAPA_IN_PROGRESS</span> below.
          </p>
        )}

        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] text-slate-500 pt-1.5 border-t border-slate-100">
          <span>
            Owner: <span className="font-semibold text-slate-700">{capa.ownerName}</span>
          </span>
          <span>
            Due Date:{' '}
            <span className="font-mono font-semibold text-slate-700 tabular-nums">
              {formatCalendarDate(capa.dueDate)}
            </span>
          </span>
          <span>
            Created By: <span className="font-semibold text-slate-700">{capa.createdBy}</span>
          </span>
        </div>

        {capa.status === 'COMPLETED' && (
          <div className="p-2.5 rounded-lg bg-emerald-50 border border-emerald-200 text-xs text-emerald-900 space-y-1">
            <p className="font-semibold tabular-nums">
              Completed by {capa.completedBy} on {formatDateTime(capa.completedAt)}
            </p>
            <p className="text-emerald-800 whitespace-pre-wrap">{capa.completionEvidence}</p>
          </div>
        )}

        {capa.status === 'CANCELLED' && capa.cancellationReason && (
          <div className="p-2.5 rounded-lg bg-slate-50 border border-slate-200 text-xs text-slate-700">
            <span className="font-semibold">Cancellation Reason:</span> {capa.cancellationReason}
          </div>
        )}

        {/* Inline Action Sub-Form for this CAPA */}
        {isActiveItemForm && (
          <form
            onSubmit={handleCapaItemActionSubmit}
            noValidate
            className="p-3 rounded-lg bg-slate-50 border border-slate-200 space-y-2.5 text-xs"
          >
            <p className="font-semibold text-slate-800">
              {activeCapaItemForm.mode === 'start'
                ? `Start ${capa.capaNumber}`
                : activeCapaItemForm.mode === 'complete'
                  ? `Complete ${capa.capaNumber} with Evidence`
                  : `Cancel ${capa.capaNumber}`}
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
              <div>
                <span className="block font-semibold text-slate-700 mb-1">
                  Authenticated Actor
                </span>
                <div
                  data-testid="authenticated-capa-item-actor-display"
                  className="px-2.5 py-1.5 rounded-lg border border-slate-200 bg-white text-sm text-slate-800 font-medium"
                >
                  {actorDisplay}
                </div>
              </div>
              <div>
                <label
                  htmlFor={`${capaItemInputId}-${capa.id}`}
                  className="block font-semibold text-slate-700 mb-1"
                >
                  {activeCapaItemForm.mode === 'complete'
                    ? 'Completion Evidence *'
                    : activeCapaItemForm.mode === 'cancel'
                      ? 'Cancellation Reason *'
                      : 'Notes (Optional)'}
                </label>
                <input
                  id={`${capaItemInputId}-${capa.id}`}
                  type="text"
                  value={capaItemText}
                  onChange={(e) => {
                    setCapaItemText(e.target.value);
                    setCapaItemErrors((prev) => ({ ...prev, text: undefined }));
                  }}
                  aria-invalid={Boolean(capaItemErrors.text)}
                  aria-describedby={
                    capaItemErrors.text ? `${capaItemInputId}-${capa.id}-err` : undefined
                  }
                  placeholder={
                    activeCapaItemForm.mode === 'complete'
                      ? 'Calibration cert #, training record ID, verification ref...'
                      : activeCapaItemForm.mode === 'cancel'
                        ? 'Document rationale for cancelling action...'
                        : 'Optional kickoff note...'
                  }
                  disabled={isMutating}
                  className={`w-full px-2.5 py-1.5 rounded-lg border bg-white text-sm focus:outline-none focus:ring-2 ${
                    capaItemErrors.text
                      ? 'border-rose-400 focus:ring-rose-200'
                      : 'border-slate-300 focus:ring-pharma-200'
                  }`}
                />
                {capaItemErrors.text && (
                  <p id={`${capaItemInputId}-${capa.id}-err`} role="alert" className="mt-1 text-rose-600">
                    {capaItemErrors.text}
                  </p>
                )}
              </div>
            </div>
            <div className="flex items-center justify-end space-x-2">
              <button
                type="button"
                onClick={() => setActiveCapaItemForm(null)}
                className="px-2.5 py-1 bg-white border border-slate-300 rounded-md text-slate-700 hover:bg-slate-100"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={isMutating}
                className="px-3 py-1 bg-pharma-600 hover:bg-pharma-700 text-white font-semibold rounded-md disabled:opacity-50"
              >
                Confirm
              </button>
            </div>
          </form>
        )}
      </div>
    );
  };

  return (
    <div className="space-y-6 min-w-0">
      <PageHeader
        backAction={{ label: backLabel, onClick: onBack }}
        eyebrow="Quality Management / Deviation & CAPA"
        icon={<AlertTriangle className="w-6 h-6 text-rose-600" aria-hidden="true" />}
        title="Deviation & CAPA Workspace"
        badge={detail?.deviationNumber || undefined}
        description="Structured investigation, root cause documentation, separated Corrective & Preventive Actions, and QA effectiveness verification."
        actions={
          <>
            {detail && onOpenBatchDossier && (
              detail.batchStatus === 'COMPLETED' ? (
                <button
                  type="button"
                  onClick={() => onOpenBatchDossier(detail.batchId)}
                  className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-sm font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg shadow-sm transition-colors tabular-nums"
                >
                  <ExternalLink className="w-4 h-4" aria-hidden="true" />
                  <span>Open Batch Dossier ({detail.batchNumber})</span>
                </button>
              ) : (
                <span className="inline-flex items-center px-3 py-1.5 rounded-lg text-xs font-medium bg-slate-100 text-slate-600 border border-slate-200 tabular-nums">
                  Batch {detail.batchNumber} ({detail.batchStatus}) — Dossier opens after completion
                </span>
              )
            )}

            <button
              type="button"
              onClick={() => loadDetail({ manualRefresh: true })}
              disabled={isLoading || isRefreshing || isMutating}
              className="inline-flex items-center space-x-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
            >
              <RefreshCw
                className={`w-4 h-4 ${
                  isLoading || isRefreshing ? 'animate-spin text-pharma-600' : 'text-slate-500'
                }`}
                aria-hidden="true"
              />
              <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
            </button>
          </>
        }
      />

      {/* Loading State */}
      {isLoading && !detail && (
        <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-8 sm:p-12 text-center">
          <RefreshCw
            className="w-8 h-8 mx-auto text-pharma-600 animate-spin mb-3"
            aria-hidden="true"
          />
          <p className="text-sm font-medium text-slate-700">Loading deviation workspace...</p>
          <p className="text-xs text-slate-500 mt-1">
            Fetching investigation records, CAPA actions, and scoped audit history
          </p>
        </div>
      )}

      {/* Load Error */}
      {!isLoading && error && !detail && (
        <InlineAlert
          variant="error"
          role="alert"
          title="Unable to load deviation details"
          message={error}
          actions={
            <button
              type="button"
              onClick={() => loadDetail()}
              className="text-xs font-semibold px-3 py-1.5 bg-white border border-rose-300 rounded-lg text-rose-800 hover:bg-rose-50"
            >
              Retry
            </button>
          }
        />
      )}

      {detail && (
        <>
          {/* Global Action Error Banner */}
          {actionError && (
            <InlineAlert
              variant="error"
              role="alert"
              title="Deviation Action Rejected"
              message={actionError}
            />
          )}

          {/* Header Summary + Lifecycle Stepper */}
          <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-4 sm:p-5 space-y-5">
            <div className="flex flex-col lg:flex-row lg:items-start lg:justify-between gap-4 pb-4 border-b border-slate-100">
              <div className="space-y-1.5">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono font-bold text-base text-slate-900 bg-slate-100 px-2.5 py-0.5 rounded border border-slate-200 tabular-nums">
                    {detail.deviationNumber}
                  </span>
                  {getDeviationStatusBadge(detail.status)}
                  {getDeviationSeverityBadge(detail.severity)}
                  {getDeviationPriorityBadge(detail.priority)}
                  <span className="px-2 py-0.5 rounded text-xs font-medium bg-slate-50 text-slate-700 border border-slate-200">
                    {getCategoryLabel(detail.category)}
                  </span>
                </div>
                <h2 className="text-lg font-bold text-slate-900">{detail.title}</h2>
                <p className="text-xs text-slate-500 tabular-nums">
                  Reported by <span className="font-semibold text-slate-700">{detail.reportedBy}</span> on{' '}
                  {formatDateTime(detail.reportedAt)} &bull; Occurred {formatDateTime(detail.occurredAt)}
                </p>
              </div>
            </div>

            {/* 6-Stage Visual Lifecycle Stepper */}
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 mb-2.5">
                Deviation Lifecycle Progress
              </p>
              <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2">
                {DEVIATION_LIFECYCLE_STAGES.map((stage, idx) => {
                  const isCompleted =
                    detail.status === 'CLOSED' || (currentStageIndex > -1 && idx < currentStageIndex);
                  const isCurrent = detail.status !== 'CLOSED' && idx === currentStageIndex;
                  return (
                    <div
                      key={stage.key}
                      className={`rounded-lg border px-3 py-2 text-xs transition-colors ${
                        detail.status === 'CLOSED'
                          ? 'bg-emerald-50 border-emerald-200 text-emerald-900'
                          : isCurrent
                            ? 'bg-pharma-50 border-pharma-400 text-pharma-900 ring-1 ring-pharma-300'
                            : isCompleted
                              ? 'bg-emerald-50/70 border-emerald-200 text-emerald-800'
                              : 'bg-slate-50 border-slate-200 text-slate-500'
                      }`}
                    >
                      <div className="flex items-center justify-between mb-0.5">
                        <span className="text-[10px] font-bold uppercase tabular-nums">
                          Step {stage.step}
                        </span>
                        {isCompleted ? (
                          <CheckCircle2 className="w-3.5 h-3.5 text-emerald-600" aria-hidden="true" />
                        ) : isCurrent ? (
                          <Clock className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                        ) : null}
                      </div>
                      <p className="font-semibold leading-tight">{stage.shortLabel}</p>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* QA Release Impact / Terminal Lock Banner */}
            {detail.status === 'CLOSED' ? (
              <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3.5 flex items-start space-x-3 text-xs text-emerald-900">
                <Lock className="w-4 h-4 text-emerald-600 shrink-0 mt-0.5" aria-hidden="true" />
                <div className="flex-1">
                  <p className="font-semibold text-sm">
                    Deviation Closed &amp; Permanently Locked
                  </p>
                  <p className="mt-0.5 text-emerald-800 tabular-nums">
                    Verified by <span className="font-semibold">{detail.verifiedBy}</span> &bull; Closed by{' '}
                    <span className="font-semibold">{detail.closedBy}</span> at {formatDateTime(detail.closedAt)}.
                    This deviation no longer blocks QA release eligibility, and closing a deviation does not automatically release batch{' '}
                    <span className="font-mono font-semibold">{detail.batchNumber}</span>.
                  </p>
                </div>
              </div>
            ) : (
              <div className="rounded-lg border border-amber-200 bg-amber-50 p-3.5 flex items-start space-x-3 text-xs text-amber-900">
                <AlertTriangle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" aria-hidden="true" />
                <div className="flex-1 space-y-1">
                  <p className="font-semibold text-sm">
                    Active QA Release Blocker for Batch {detail.batchNumber}
                  </p>
                  <p className="text-amber-800">
                    Any non-CLOSED deviation blocks QA <span className="font-mono font-semibold">RELEASED</span> disposition
                    (<span className="font-mono">ON_HOLD</span> and <span className="font-mono">REJECTED</span> remain permitted).
                    Closing this deviation satisfies the deviation gate only; QA must still record batch disposition explicitly.
                  </p>
                  {closureBlockers.length > 0 && (
                    <div className="pt-1">
                      <span className="font-semibold text-amber-900">Remaining Closure Blockers:</span>
                      <ul className="list-disc pl-4 mt-0.5 space-y-0.5 text-amber-800">
                        {closureBlockers.map((blocker, i) => (
                          <li key={i}>{blocker}</li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              </div>
            )}

            {/* Metadata & Traceability Cards */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3.5">
              <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                    <Layers className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                    <span>Affected Batch</span>
                  </span>
                  {detail.batchId && (
                    <button
                      type="button"
                      onClick={() => navigateToTab('execution', { batchId: detail.batchId })}
                      className="inline-flex items-center space-x-0.5 text-[11px] font-semibold text-pharma-700 hover:text-pharma-800 hover:underline whitespace-nowrap shrink-0"
                    >
                      <span>Inspect eBR Execution</span>
                      <ArrowUpRight className="w-3 h-3" aria-hidden="true" />
                    </button>
                  )}
                </div>
                <span className="text-sm font-mono font-bold text-slate-900 mt-1 block tabular-nums">
                  {detail.batchNumber || '—'}
                </span>
                <div className="flex flex-wrap items-center gap-1.5 mt-1">
                  {detail.batchStatus && (
                    <StatusBadge
                      status={detail.batchStatus}
                      label={`Mfg: ${detail.batchStatus}`}
                      domain="order"
                      size="xs"
                    />
                  )}
                  <span className="text-[11px] text-slate-500">
                    QA: {detail.batchQaStatus || 'N/A'}
                  </span>
                </div>
              </div>

              <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                    <ClipboardList className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                    <span>Manufacturing Order</span>
                  </span>
                  {(detail.orderId || detail.orderNumber) && (
                    <button
                      type="button"
                      onClick={() =>
                        navigateToTab(
                          'orders',
                          detail.orderId
                            ? { orderId: detail.orderId }
                            : { orderNumber: detail.orderNumber }
                        )
                      }
                      className="inline-flex items-center space-x-0.5 text-[11px] font-semibold text-pharma-700 hover:text-pharma-800 hover:underline whitespace-nowrap shrink-0"
                    >
                      <span>Open Order</span>
                      <ArrowUpRight className="w-3 h-3" aria-hidden="true" />
                    </button>
                  )}
                </div>
                <span className="text-sm font-mono font-bold text-slate-900 mt-1 block tabular-nums">
                  {detail.orderNumber || '—'}
                </span>
                <div className="flex flex-wrap items-center justify-between gap-2 mt-1">
                  <span className="text-[11px] text-slate-500 tabular-nums">
                    Recipe {detail.recipeCode || '—'}{' '}
                    {detail.recipeVersion != null ? `v${detail.recipeVersion}` : ''}
                  </span>
                  {canOpenRecipes && (detail.recipeId || detail.recipeCode) && (
                    <button
                      type="button"
                      onClick={() =>
                        navigateToTab(
                          'recipes',
                          detail.recipeId
                            ? { recipeId: detail.recipeId }
                            : { recipeCode: detail.recipeCode }
                        )
                      }
                      className="inline-flex items-center space-x-0.5 text-[11px] font-semibold text-pharma-700 hover:text-pharma-800 hover:underline whitespace-nowrap shrink-0"
                    >
                      <span>Inspect Recipe</span>
                      <ArrowUpRight className="w-3 h-3" aria-hidden="true" />
                    </button>
                  )}
                </div>
              </div>

              <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                  <FlaskConical className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                  <span>Product</span>
                </span>
                <span className="text-sm font-semibold text-slate-900 mt-1 block">
                  {detail.productName || '—'}
                </span>
                {detail.productCode && (
                  <span className="text-[11px] font-mono text-slate-500 tabular-nums">
                    {detail.productCode}
                  </span>
                )}
              </div>

              <div className="bg-slate-50 border border-slate-200 rounded-lg p-3 space-y-1.5">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                  <Wrench className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                  <span>CAPA Progress</span>
                </span>
                <span className="text-sm font-bold text-slate-900 block tabular-nums">
                  {detail.completedCapaCount} / {Math.max(0, (detail.totalCapaCount || 0) - (detail.cancelledCapaCount || 0))} Active Done
                </span>
                <ProgressBar
                  value={Number(detail.completedCapaCount || 0)}
                  max={Math.max(1, (detail.totalCapaCount || 0) - (detail.cancelledCapaCount || 0))}
                  label="Deviation CAPA completion"
                  size="sm"
                  tone={detail.overdueCapaCount > 0 ? 'danger' : 'auto'}
                />
                <span className="text-[11px] text-slate-500 block tabular-nums">
                  Open: {detail.openCapaCount} &bull; Active: {detail.inProgressCapaCount}
                  {detail.overdueCapaCount > 0 ? ` • ${detail.overdueCapaCount} Overdue` : ''}
                </span>
              </div>
            </div>

            {/* Description, Containment, and Linked Source Traceability */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              <div className="bg-slate-50/70 border border-slate-200 rounded-lg p-4 space-y-3 text-xs">
                <div>
                  <span className="font-semibold uppercase tracking-wider text-slate-500 block mb-1">
                    Deviation Description
                  </span>
                  <p className="text-sm text-slate-800 whitespace-pre-wrap leading-relaxed">
                    {detail.description}
                  </p>
                </div>
                <div>
                  <span className="font-semibold uppercase tracking-wider text-slate-500 block mb-1">
                    Immediate Containment Action
                  </span>
                  <p className="text-slate-700 whitespace-pre-wrap">
                    {detail.immediateContainment || 'No immediate containment recorded at report time.'}
                  </p>
                </div>
              </div>

              <div className="bg-slate-50/70 border border-slate-200 rounded-lg p-4 space-y-2.5 text-xs">
                <span className="font-semibold uppercase tracking-wider text-slate-500 block">
                  Linked Manufacturing / Quality Records
                </span>

                {!detail.qualityCheckId && !detail.executionStepId && !detail.sourceAuditLogId ? (
                  <p className="text-slate-500">
                    Reported at batch level without specific QC check, execution step, or audit entry link.
                  </p>
                ) : (
                  <div className="space-y-2">
                    {detail.qualityCheckId && (
                      <div className="p-2.5 rounded-lg bg-white border border-slate-200">
                        <div className="flex items-center justify-between gap-2">
                          <span className="font-semibold text-slate-800">
                            QC Check #{detail.qualityCheckId}: {detail.linkedCheckTestName}
                          </span>
                          <StatusBadge
                            status={detail.linkedCheckResult || 'PENDING'}
                            domain="qc"
                            size="xs"
                          />
                        </div>
                        <p className="text-slate-500 mt-1">
                          Measured:{' '}
                          <span className="font-mono font-semibold text-slate-700">
                            {detail.linkedCheckMeasuredValue || '—'} {detail.linkedCheckUnit || ''}
                          </span>
                          {' • '}Spec: {detail.linkedCheckSpecification || '—'}
                        </p>
                      </div>
                    )}

                    {detail.executionStepId && (
                      <div className="p-2.5 rounded-lg bg-white border border-slate-200">
                        <div className="flex items-center justify-between gap-2">
                          <span className="font-semibold text-slate-800">
                            eBR Step #{detail.linkedStepNumber}: {detail.linkedStepOperationName}
                          </span>
                          <span className="px-2 py-0.5 rounded text-[11px] font-semibold bg-slate-100 text-slate-700">
                            {detail.linkedStepStatus}
                          </span>
                        </div>
                      </div>
                    )}

                    {detail.sourceAuditLogId && (
                      <div className="p-2.5 rounded-lg bg-white border border-slate-200 text-slate-700">
                        Linked Source Audit Entry:{' '}
                        <span className="font-mono font-semibold">Audit #{detail.sourceAuditLogId}</span>
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* Stage 2: Investigation + Stage 3: Root Cause & Impact Assessment */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            {/* Investigation Panel */}
            <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
              <div className="px-5 py-4 bg-slate-50/70 border-b border-slate-200 flex items-center justify-between">
                <h3 className="text-sm font-semibold text-slate-900 flex items-center space-x-2">
                  <FileText className="w-4 h-4 text-pharma-600" aria-hidden="true" />
                  <span>1. Deviation Investigation</span>
                </h3>
                {detail.investigatedBy && (
                  <span className="text-[11px] font-semibold text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200">
                    Recorded
                  </span>
                )}
              </div>

              <div className="p-5">
                {detail.status === 'OPEN' ? (
                  allowInvestigate ? (
                    <form onSubmit={handleStartInvestigation} noValidate className="space-y-3.5">
                      <p className="text-xs text-slate-600">
                        Start investigation under your authenticated session to move this deviation from{' '}
                        <span className="font-mono font-semibold">OPEN</span> to{' '}
                        <span className="font-mono font-semibold">UNDER_INVESTIGATION</span>.
                      </p>
                      <div>
                        <span className="flex items-center space-x-1 text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1">
                          <User className="w-3.5 h-3.5 text-slate-400" aria-hidden="true" />
                          <span>Investigated By</span>
                        </span>
                        <div
                          data-testid="authenticated-investigator-display"
                          className="px-3 py-2 text-sm rounded-lg border border-slate-200 bg-slate-50 text-slate-800 font-medium"
                        >
                          {actorDisplay}
                        </div>
                        <p className="mt-1 text-[11px] text-slate-500">
                          Attributed automatically from your authenticated session.
                        </p>
                      </div>

                      <div>
                        <label
                          htmlFor="inv-summary"
                          className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                        >
                          Investigation Summary <span className="text-rose-500">*</span>
                        </label>
                        <textarea
                          id="inv-summary"
                          rows={3}
                          value={investigationSummary}
                          onChange={(e) => {
                            setInvestigationSummary(e.target.value);
                            setInvestigationErrors((prev) => ({
                              ...prev,
                              investigationSummary: undefined,
                            }));
                          }}
                          aria-invalid={Boolean(investigationErrors.investigationSummary)}
                          aria-describedby={
                            investigationErrors.investigationSummary ? invSummaryErrId : undefined
                          }
                          placeholder="Document initial containment verification, lab re-test review, and batch record audit plan..."
                          disabled={isMutating}
                          className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 ${
                            investigationErrors.investigationSummary
                              ? 'border-rose-400 focus:ring-rose-200'
                              : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                          }`}
                        />
                        {investigationErrors.investigationSummary && (
                          <p id={invSummaryErrId} role="alert" className="mt-1 text-xs text-rose-600">
                            {investigationErrors.investigationSummary}
                          </p>
                        )}
                      </div>

                      <button
                        type="submit"
                        disabled={isMutating}
                        className="inline-flex items-center space-x-1.5 px-4 py-2 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors disabled:opacity-50"
                      >
                        <Play className="w-3.5 h-3.5" aria-hidden="true" />
                        <span>Start Investigation</span>
                      </button>
                    </form>
                  ) : (
                    <div className="p-3 rounded-lg bg-slate-50 border border-slate-200 text-xs text-slate-600">
                      Investigation has not started yet. A Production Supervisor, QC Analyst, or QA Reviewer must begin the investigation.
                    </div>
                  )
                ) : detail.investigatedBy ? (
                  <div className="space-y-3 text-xs">
                    <div className="flex flex-wrap items-center justify-between gap-2 text-slate-600">
                      <span>
                        Investigator: <span className="font-semibold text-slate-900">{detail.investigatedBy}</span>
                      </span>
                      <span className="tabular-nums">Started: {formatDateTime(detail.investigationStartedAt)}</span>
                    </div>
                    <div className="bg-slate-50 border border-slate-200 rounded-lg p-3 text-sm text-slate-800 whitespace-pre-wrap leading-relaxed">
                      {detail.investigationSummary}
                    </div>
                  </div>
                ) : (
                  <p className="text-xs text-slate-500">Investigation not started yet.</p>
                )}
              </div>
            </div>

            {/* Root Cause & Impact Assessment Panel */}
            <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
              <div className="px-5 py-4 bg-slate-50/70 border-b border-slate-200 flex items-center justify-between">
                <h3 className="text-sm font-semibold text-slate-900 flex items-center space-x-2">
                  <AlertTriangle className="w-4 h-4 text-pharma-600" aria-hidden="true" />
                  <span>2. Root Cause &amp; Impact Assessment</span>
                </h3>
                {detail.rootCauseIdentifiedBy && (
                  <span className="text-[11px] font-semibold text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200">
                    Recorded
                  </span>
                )}
              </div>

              <div className="p-5">
                {detail.status === 'UNDER_INVESTIGATION' ? (
                  allowInvestigate ? (
                    <form onSubmit={handleRecordRootCause} noValidate className="space-y-3">
                      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                        <div>
                          <span className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1">
                            Identified By
                          </span>
                          <div
                            data-testid="authenticated-rca-actor-display"
                            className="px-3 py-2 text-sm rounded-lg border border-slate-200 bg-slate-50 text-slate-800 font-medium"
                          >
                            {actorDisplay}
                          </div>
                        </div>

                        <div>
                          <label
                            htmlFor="rca-category"
                            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                          >
                            Root Cause Category <span className="text-rose-500">*</span>
                          </label>
                          <input
                            id="rca-category"
                            list="rca-category-suggestions"
                            type="text"
                            value={rootCauseCategory}
                            onChange={(e) => {
                              setRootCauseCategory(e.target.value);
                              setRcaErrors((prev) => ({
                                ...prev,
                                rootCauseCategory: undefined,
                              }));
                            }}
                            aria-invalid={Boolean(rcaErrors.rootCauseCategory)}
                            aria-describedby={
                              rcaErrors.rootCauseCategory ? rcaCategoryErrId : undefined
                            }
                            placeholder="Select or type category..."
                            disabled={isMutating}
                            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 ${
                              rcaErrors.rootCauseCategory
                                ? 'border-rose-400 focus:ring-rose-200'
                                : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                            }`}
                          />
                          <datalist id="rca-category-suggestions">
                            {ROOT_CAUSE_SUGGESTIONS.map((item) => (
                              <option key={item} value={item} />
                            ))}
                          </datalist>
                          {rcaErrors.rootCauseCategory && (
                            <p id={rcaCategoryErrId} role="alert" className="mt-1 text-xs text-rose-600">
                              {rcaErrors.rootCauseCategory}
                            </p>
                          )}
                        </div>
                      </div>

                      <div>
                        <label
                          htmlFor="rca-analysis"
                          className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                        >
                          Root Cause Analysis <span className="text-rose-500">*</span>
                        </label>
                        <textarea
                          id="rca-analysis"
                          rows={2}
                          value={rootCauseAnalysis}
                          onChange={(e) => {
                            setRootCauseAnalysis(e.target.value);
                            setRcaErrors((prev) => ({
                              ...prev,
                              rootCauseAnalysis: undefined,
                            }));
                          }}
                          aria-invalid={Boolean(rcaErrors.rootCauseAnalysis)}
                          aria-describedby={
                            rcaErrors.rootCauseAnalysis ? rcaAnalysisErrId : undefined
                          }
                          placeholder="5-Why / Fishbone conclusion and verified assignable cause..."
                          disabled={isMutating}
                          className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 ${
                            rcaErrors.rootCauseAnalysis
                              ? 'border-rose-400 focus:ring-rose-200'
                              : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                          }`}
                        />
                        {rcaErrors.rootCauseAnalysis && (
                          <p id={rcaAnalysisErrId} role="alert" className="mt-1 text-xs text-rose-600">
                            {rcaErrors.rootCauseAnalysis}
                          </p>
                        )}
                      </div>

                      <div>
                        <label
                          htmlFor="rca-impact"
                          className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                        >
                          Batch / Product Impact Assessment <span className="text-rose-500">*</span>
                        </label>
                        <textarea
                          id="rca-impact"
                          rows={2}
                          value={impactAssessment}
                          onChange={(e) => {
                            setImpactAssessment(e.target.value);
                            setRcaErrors((prev) => ({ ...prev, impactAssessment: undefined }));
                          }}
                          aria-invalid={Boolean(rcaErrors.impactAssessment)}
                          aria-describedby={
                            rcaErrors.impactAssessment ? rcaImpactErrId : undefined
                          }
                          placeholder="Assess impact on batch quality, adjacent lots, equipment, and potency/dissolution..."
                          disabled={isMutating}
                          className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 ${
                            rcaErrors.impactAssessment
                              ? 'border-rose-400 focus:ring-rose-200'
                              : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                          }`}
                        />
                        {rcaErrors.impactAssessment && (
                          <p id={rcaImpactErrId} role="alert" className="mt-1 text-xs text-rose-600">
                            {rcaErrors.impactAssessment}
                          </p>
                        )}
                      </div>

                      <div>
                        <label
                          htmlFor="rca-inv-summary"
                          className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                        >
                          Updated Investigation Summary (Optional)
                        </label>
                        <textarea
                          id="rca-inv-summary"
                          rows={2}
                          value={rcaInvestigationSummary}
                          onChange={(e) => {
                            setRcaInvestigationSummary(e.target.value);
                            if (rcaErrors.investigationSummary) {
                              setRcaErrors((prev) => ({
                                ...prev,
                                investigationSummary: undefined,
                              }));
                            }
                          }}
                          aria-invalid={Boolean(rcaErrors.investigationSummary)}
                          aria-describedby={
                            rcaErrors.investigationSummary ? rcaInvSummaryErrId : undefined
                          }
                          placeholder="Keep existing summary or refine with completed investigation findings..."
                          disabled={isMutating}
                          className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 ${
                            rcaErrors.investigationSummary
                              ? 'border-rose-400 focus:ring-rose-200'
                              : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                          }`}
                        />
                        {rcaErrors.investigationSummary && (
                          <p id={rcaInvSummaryErrId} role="alert" className="mt-1 text-xs text-rose-600">
                            {rcaErrors.investigationSummary}
                          </p>
                        )}
                      </div>

                      <button
                        type="submit"
                        disabled={isMutating}
                        className="inline-flex items-center space-x-1.5 px-4 py-2 text-xs font-semibold text-white bg-indigo-600 hover:bg-indigo-700 rounded-lg shadow-sm transition-colors disabled:opacity-50"
                      >
                        <CheckCircle2 className="w-3.5 h-3.5" aria-hidden="true" />
                        <span>Record Root Cause &amp; Impact</span>
                      </button>
                    </form>
                  ) : (
                    <div className="p-3 rounded-lg bg-slate-50 border border-slate-200 text-xs text-slate-600">
                      Investigation is underway. Recording root cause &amp; impact requires Production Supervisor, QC Analyst, or QA Reviewer access.
                    </div>
                  )
                ) : detail.rootCauseIdentifiedBy ? (
                  <div className="space-y-3 text-xs">
                    <div className="flex flex-wrap items-center justify-between gap-2 text-slate-600">
                      <span>
                        Identified by <span className="font-semibold text-slate-900">{detail.rootCauseIdentifiedBy}</span>
                      </span>
                      <span className="tabular-nums">{formatDateTime(detail.rootCauseIdentifiedAt)}</span>
                    </div>
                    <div>
                      <span className="font-semibold text-slate-500 uppercase tracking-wider">Category:</span>{' '}
                      <span className="px-2 py-0.5 rounded bg-indigo-50 text-indigo-800 border border-indigo-200 font-semibold">
                        {detail.rootCauseCategory}
                      </span>
                    </div>
                    <div className="bg-slate-50 border border-slate-200 rounded-lg p-3 space-y-2">
                      <div>
                        <span className="text-[11px] font-semibold uppercase text-slate-500 block">
                          Root Cause Analysis
                        </span>
                        <p className="text-sm text-slate-800 whitespace-pre-wrap mt-0.5">
                          {detail.rootCauseAnalysis}
                        </p>
                      </div>
                      <div className="pt-2 border-t border-slate-200">
                        <span className="text-[11px] font-semibold uppercase text-slate-500 block">
                          Impact Assessment
                        </span>
                        <p className="text-slate-700 whitespace-pre-wrap mt-0.5">
                          {detail.impactAssessment}
                        </p>
                      </div>
                    </div>
                  </div>
                ) : (
                  <p className="text-xs text-slate-500">
                    Available once investigation starts (<span className="font-mono">UNDER_INVESTIGATION</span>).
                  </p>
                )}
              </div>
            </div>
          </div>

          {/* Stage 4: CAPA Plan & Action Tracking (Separated Corrective vs Preventive Actions) */}
          <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
            <div className="px-4 sm:px-6 py-4 bg-slate-50/70 border-b border-slate-200 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
              <div>
                <h3 className="text-base font-semibold text-slate-900 flex items-center space-x-2">
                  <Wrench className="w-4 h-4 text-pharma-600" aria-hidden="true" />
                  <span className="tabular-nums">
                    3. Corrective &amp; Preventive Actions (CAPA) ({capaActions.length})
                  </span>
                </h3>
                <p className="text-xs text-slate-500 mt-0.5 tabular-nums">
                  Corrective: <span className="font-semibold text-indigo-700">{correctiveCapas.length}</span>
                  {' • '}Preventive: <span className="font-semibold text-teal-700">{preventiveCapas.length}</span>
                  {' • '}Open: <span className="font-semibold text-amber-700">{detail.openCapaCount}</span>
                  {' • '}In Progress: <span className="font-semibold text-pharma-700">{detail.inProgressCapaCount}</span>
                  {' • '}Completed: <span className="font-semibold text-emerald-700">{detail.completedCapaCount}</span>
                  {' • '}Cancelled: <span className="font-semibold text-slate-600">{detail.cancelledCapaCount}</span>
                  {detail.overdueCapaCount > 0 && (
                    <span className="text-rose-700 font-semibold">
                      {' • '}Overdue: {detail.overdueCapaCount}
                    </span>
                  )}
                </p>
              </div>

              {capaMutationsAllowed ? (
                allowCreateCapa ? (
                  <button
                    type="button"
                    onClick={() => {
                      setShowAddCapaForm((v) => !v);
                      setCapaFormErrors({});
                    }}
                    className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors self-start sm:self-auto"
                  >
                    <Plus className="w-4 h-4" aria-hidden="true" />
                    <span>{showAddCapaForm ? 'Close CAPA Form' : 'Add CAPA Action'}</span>
                  </button>
                ) : (
                  <span className="inline-flex items-center space-x-1 px-2.5 py-1 rounded-lg text-xs font-semibold bg-slate-100 text-slate-600 border border-slate-200">
                    <Lock className="w-3.5 h-3.5" aria-hidden="true" />
                    <span>Add CAPA: Supervisor / QA Only</span>
                  </span>
                )
              ) : (
                <span className="inline-flex items-center space-x-1 px-2.5 py-1 rounded-lg text-xs font-semibold bg-slate-100 text-slate-600 border border-slate-200">
                  <Lock className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>
                    {detail.status === 'OPEN' || detail.status === 'UNDER_INVESTIGATION'
                      ? `CAPAs Unlock After Root Cause (${detail.status})`
                      : `CAPA Edits Locked (${detail.status})`}
                  </span>
                </span>
              )}
            </div>

            <div className="p-5 space-y-5">
              {detail.capaPlanSummary && (
                <div className="p-3 rounded-lg bg-pharma-50/70 border border-pharma-200 text-xs text-pharma-900">
                  <span className="font-semibold uppercase tracking-wider text-[11px] block mb-0.5">
                    CAPA Plan Summary
                  </span>
                  <p>{detail.capaPlanSummary}</p>
                </div>
              )}

              {/* Create CAPA Inline Form */}
              {capaMutationsAllowed && allowCreateCapa && showAddCapaForm && (
                <form
                  onSubmit={handleCreateCapaAction}
                  noValidate
                  className="p-4 rounded-xl bg-slate-50 border border-slate-200 space-y-3.5"
                >
                  <h4 className="text-sm font-semibold text-slate-900">Create New CAPA Action</h4>
                  <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                    <div>
                      <label
                        htmlFor="capa-type"
                        className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                      >
                        CAPA Type <span className="text-rose-500">*</span>
                      </label>
                      <select
                        id="capa-type"
                        value={capaType}
                        onChange={(e) => setCapaType(e.target.value)}
                        disabled={isMutating}
                        className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500"
                      >
                        {CAPA_TYPE_OPTIONS.map((opt) => (
                          <option key={opt.value} value={opt.value}>
                            {opt.label}
                          </option>
                        ))}
                      </select>
                    </div>

                    <div>
                      <label
                        htmlFor="capa-owner"
                        className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                      >
                        Action Owner <span className="text-rose-500">*</span>
                      </label>
                      <input
                        id="capa-owner"
                        type="text"
                        value={capaOwnerName}
                        onChange={(e) => {
                          setCapaOwnerName(e.target.value);
                          setCapaFormErrors((prev) => ({ ...prev, ownerName: undefined }));
                        }}
                        aria-invalid={Boolean(capaFormErrors.ownerName)}
                        aria-describedby={capaFormErrors.ownerName ? capaOwnerErrId : undefined}
                        placeholder="e.g., M. Deshmukh"
                        disabled={isMutating}
                        className={`w-full px-3 py-2 text-sm rounded-lg border bg-white focus:outline-none focus:ring-2 ${
                          capaFormErrors.ownerName
                            ? 'border-rose-400 focus:ring-rose-200'
                            : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                        }`}
                      />
                      {capaFormErrors.ownerName && (
                        <p id={capaOwnerErrId} role="alert" className="mt-1 text-xs text-rose-600">
                          {capaFormErrors.ownerName}
                        </p>
                      )}
                    </div>

                    <div>
                      <label
                        htmlFor="capa-due-date"
                        className="flex items-center space-x-1 text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                      >
                        <Calendar className="w-3.5 h-3.5 text-slate-500" aria-hidden="true" />
                        <span>
                          Due Date — IST (Asia/Kolkata) <span className="text-rose-500">*</span>
                        </span>
                      </label>
                      <input
                        id="capa-due-date"
                        type="date"
                        min={todayInKolkata}
                        value={capaDueDate}
                        onChange={(e) => {
                          setCapaDueDate(e.target.value);
                          setCapaFormErrors((prev) => ({ ...prev, dueDate: undefined }));
                        }}
                        aria-invalid={Boolean(capaFormErrors.dueDate)}
                        aria-describedby={capaFormErrors.dueDate ? capaDueDateErrId : undefined}
                        disabled={isMutating}
                        className={`w-full px-3 py-2 text-sm rounded-lg border bg-white focus:outline-none focus:ring-2 tabular-nums ${
                          capaFormErrors.dueDate
                            ? 'border-rose-400 focus:ring-rose-200'
                            : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                        }`}
                      />
                      {capaFormErrors.dueDate && (
                        <p id={capaDueDateErrId} role="alert" className="mt-1 text-xs text-rose-600">
                          {capaFormErrors.dueDate}
                        </p>
                      )}
                    </div>
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                      <label
                        htmlFor="capa-title"
                        className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                      >
                        Action Title <span className="text-rose-500">*</span>
                      </label>
                      <input
                        id="capa-title"
                        type="text"
                        value={capaTitle}
                        onChange={(e) => {
                          setCapaTitle(e.target.value);
                          setCapaFormErrors((prev) => ({ ...prev, title: undefined }));
                        }}
                        aria-invalid={Boolean(capaFormErrors.title)}
                        aria-describedby={capaFormErrors.title ? capaTitleErrId : undefined}
                        placeholder="e.g., Recalibrate dissolution bath paddles & SOP training"
                        disabled={isMutating}
                        className={`w-full px-3 py-2 text-sm rounded-lg border bg-white focus:outline-none focus:ring-2 ${
                          capaFormErrors.title
                            ? 'border-rose-400 focus:ring-rose-200'
                            : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                        }`}
                      />
                      {capaFormErrors.title && (
                        <p id={capaTitleErrId} role="alert" className="mt-1 text-xs text-rose-600">
                          {capaFormErrors.title}
                        </p>
                      )}
                    </div>

                    <div>
                      <span className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1">
                        Created By (Authenticated)
                      </span>
                      <div
                        data-testid="authenticated-capa-creator-display"
                        className="px-3 py-2 text-sm rounded-lg border border-slate-200 bg-white text-slate-800 font-medium"
                      >
                        {actorDisplay}
                      </div>
                    </div>
                  </div>

                  <div>
                    <label
                      htmlFor="capa-plan"
                      className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                    >
                      Action Plan Details <span className="text-rose-500">*</span>
                    </label>
                    <textarea
                      id="capa-plan"
                      rows={2}
                      value={capaActionPlan}
                      onChange={(e) => {
                        setCapaActionPlan(e.target.value);
                        setCapaFormErrors((prev) => ({ ...prev, actionPlan: undefined }));
                      }}
                      aria-invalid={Boolean(capaFormErrors.actionPlan)}
                      aria-describedby={capaFormErrors.actionPlan ? capaPlanErrId : undefined}
                      placeholder="Specify exact corrective/preventive steps, verification criteria, and affected SOP/equipment..."
                      disabled={isMutating}
                      className={`w-full px-3 py-2 text-sm rounded-lg border bg-white focus:outline-none focus:ring-2 ${
                        capaFormErrors.actionPlan
                          ? 'border-rose-400 focus:ring-rose-200'
                          : 'border-slate-300 focus:ring-pharma-200 focus:border-pharma-500'
                      }`}
                    />
                    {capaFormErrors.actionPlan && (
                      <p id={capaPlanErrId} role="alert" className="mt-1 text-xs text-rose-600">
                        {capaFormErrors.actionPlan}
                      </p>
                    )}
                  </div>

                  <div className="flex items-center justify-end space-x-2">
                    <button
                      type="button"
                      onClick={() => setShowAddCapaForm(false)}
                      disabled={isMutating}
                      className="px-3 py-1.5 text-xs font-medium text-slate-700 bg-white hover:bg-slate-100 border border-slate-300 rounded-lg"
                    >
                      Cancel
                    </button>
                    <button
                      type="submit"
                      disabled={isMutating}
                      className="inline-flex items-center space-x-1.5 px-4 py-1.5 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm disabled:opacity-50"
                    >
                      <CheckCircle2 className="w-3.5 h-3.5" aria-hidden="true" />
                      <span>Save CAPA Action</span>
                    </button>
                  </div>
                </form>
              )}

              {/* Separated Corrective & Preventive CAPA Subsections */}
              {capaActions.length === 0 ? (
                <div className="p-8 rounded-lg border border-dashed border-slate-300 text-center">
                  <Wrench className="w-8 h-8 text-slate-300 mx-auto mb-2" aria-hidden="true" />
                  <p className="text-sm font-medium text-slate-700">No CAPA actions defined yet</p>
                  <p className="text-xs text-slate-500 mt-1">
                    {detail.status === 'OPEN' || detail.status === 'UNDER_INVESTIGATION'
                      ? 'Complete investigation and record root cause before defining CAPA actions.'
                      : 'Add at least one Corrective or Preventive Action before starting CAPA execution.'}
                  </p>
                </div>
              ) : (
                <div className="space-y-6">
                  {/* Corrective Actions Subsection */}
                  <section aria-label="Corrective Actions" className="space-y-3">
                    <div className="flex flex-wrap items-center justify-between gap-2 pb-2 border-b border-slate-200">
                      <div className="flex items-center gap-2">
                        <h4 className="text-sm font-semibold text-slate-900">
                          Corrective Actions ({correctiveCapas.length})
                        </h4>
                        <StatusBadge
                          status="CORRECTIVE"
                          label="Root Cause Remediation"
                          domain="capa"
                          size="xs"
                          variant="tag"
                        />
                      </div>
                      <span className="text-[11px] text-slate-500">
                        Actions addressing the documented root cause
                      </span>
                    </div>

                    {correctiveCapas.length === 0 ? (
                      <p className="text-xs text-slate-500 italic py-2">
                        No corrective actions defined yet.
                      </p>
                    ) : (
                      <div className="space-y-3">{correctiveCapas.map(renderCapaCard)}</div>
                    )}
                  </section>

                  {/* Preventive Actions Subsection */}
                  <section aria-label="Preventive Actions" className="space-y-3">
                    <div className="flex flex-wrap items-center justify-between gap-2 pb-2 border-b border-slate-200">
                      <div className="flex items-center gap-2">
                        <h4 className="text-sm font-semibold text-slate-900">
                          Preventive Actions ({preventiveCapas.length})
                        </h4>
                        <StatusBadge
                          status="PREVENTIVE"
                          label="Recurrence Prevention"
                          domain="capa"
                          size="xs"
                          variant="tag"
                        />
                      </div>
                      <span className="text-[11px] text-slate-500">
                        Actions intended to prevent recurrence across processes
                      </span>
                    </div>

                    {preventiveCapas.length === 0 ? (
                      <p className="text-xs text-slate-500 italic py-2">
                        No preventive actions defined yet.
                      </p>
                    ) : (
                      <div className="space-y-3">{preventiveCapas.map(renderCapaCard)}</div>
                    )}
                  </section>

                  {otherCapas.length > 0 && (
                    <section aria-label="Additional CAPA Actions" className="space-y-3">
                      <div className="pb-2 border-b border-slate-200">
                        <h4 className="text-sm font-semibold text-slate-900">
                          Additional CAPA Actions ({otherCapas.length})
                        </h4>
                      </div>
                      <div className="space-y-3">{otherCapas.map(renderCapaCard)}</div>
                    </section>
                  )}
                </div>
              )}

              {/* Transition: ROOT_CAUSE_IDENTIFIED -> CAPA_IN_PROGRESS */}
              {detail.status === 'ROOT_CAUSE_IDENTIFIED' && (
                allowStartCapaPhase ? (
                  <form
                    onSubmit={handleStartDeviationCapa}
                    noValidate
                    className="p-4 rounded-xl bg-pharma-50 border border-pharma-200 space-y-3"
                  >
                    <div>
                      <h4 className="text-sm font-semibold text-pharma-900">
                        Stage Transition: Start CAPA Execution
                      </h4>
                      <p className="text-xs text-pharma-800 mt-0.5 tabular-nums">
                        {activeCapas.length === 0
                          ? 'Add at least one non-cancelled CAPA action above before moving to CAPA_IN_PROGRESS.'
                          : `${activeCapas.length} active CAPA action(s) defined. Ready to move into CAPA_IN_PROGRESS.`}
                      </p>
                    </div>

                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                      <div>
                        <span className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1">
                          Initiated By (QA Reviewer)
                        </span>
                        <div
                          data-testid="authenticated-start-capa-actor-display"
                          className="px-3 py-2 text-sm rounded-lg border border-slate-200 bg-white text-slate-800 font-medium"
                        >
                          {actorDisplay}
                        </div>
                      </div>

                      <div>
                        <label
                          htmlFor="start-capa-plan"
                          className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                        >
                          CAPA Plan Summary (Optional)
                        </label>
                        <input
                          id="start-capa-plan"
                          type="text"
                          value={capaPlanSummary}
                          onChange={(e) => {
                            setCapaPlanSummary(e.target.value);
                            setCapaStartErrors((prev) => ({
                              ...prev,
                              capaPlanSummary: undefined,
                            }));
                          }}
                          aria-invalid={Boolean(capaStartErrors.capaPlanSummary)}
                          aria-describedby={
                            capaStartErrors.capaPlanSummary ? startCapaPlanErrId : undefined
                          }
                          placeholder="Overall corrective & preventive strategy..."
                          disabled={isMutating}
                          className={`w-full px-3 py-2 text-sm rounded-lg border bg-white focus:outline-none focus:ring-2 ${
                            capaStartErrors.capaPlanSummary
                              ? 'border-rose-400 focus:ring-rose-200'
                              : 'border-slate-300 focus:ring-pharma-200'
                          }`}
                        />
                        {capaStartErrors.capaPlanSummary && (
                          <p id={startCapaPlanErrId} role="alert" className="mt-1 text-xs text-rose-600">
                            {capaStartErrors.capaPlanSummary}
                          </p>
                        )}
                      </div>
                    </div>

                    <button
                      type="submit"
                      disabled={isMutating || activeCapas.length === 0}
                      className="inline-flex items-center space-x-1.5 px-4 py-2 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 disabled:bg-slate-300 disabled:text-slate-500 disabled:cursor-not-allowed rounded-lg shadow-sm"
                    >
                      <Play className="w-3.5 h-3.5" aria-hidden="true" />
                      <span>Enter CAPA In Progress</span>
                    </button>
                  </form>
                ) : (
                  <div className="p-4 rounded-xl bg-pharma-50 border border-pharma-200 text-xs text-pharma-900">
                    <p className="font-semibold">Awaiting QA Reviewer CAPA Phase Start</p>
                    <p className="mt-0.5 text-pharma-800">
                      Only a QA Reviewer can transition this deviation from ROOT_CAUSE_IDENTIFIED to CAPA_IN_PROGRESS.
                    </p>
                  </div>
                )
              )}

              {/* Transition: CAPA_IN_PROGRESS -> PENDING_VERIFICATION */}
              {detail.status === 'CAPA_IN_PROGRESS' && (
                allowInvestigate ? (
                  <form
                    onSubmit={handleSubmitForVerification}
                    noValidate
                    className="p-4 rounded-xl bg-purple-50 border border-purple-200 space-y-3"
                  >
                    <div>
                      <h4 className="text-sm font-semibold text-purple-950">
                        Stage Transition: Submit for QA Verification
                      </h4>
                      <p className="text-xs text-purple-800 mt-0.5">
                        {canSubmitVerification
                          ? 'All active CAPAs have documented completion evidence and are ready for QA verification.'
                          : 'Complete every OPEN / IN_PROGRESS CAPA with evidence (or cancel unnecessary actions) before submitting for QA verification.'}
                      </p>
                    </div>

                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                      <div>
                        <span className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1">
                          Submitted By
                        </span>
                        <div
                          data-testid="authenticated-submit-verify-actor-display"
                          className="px-3 py-2 text-sm rounded-lg border border-slate-200 bg-white text-slate-800 font-medium"
                        >
                          {actorDisplay}
                        </div>
                      </div>

                      <div>
                        <label
                          htmlFor="submit-verif-notes"
                          className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                        >
                          Submission Notes (Optional)
                        </label>
                        <input
                          id="submit-verif-notes"
                          type="text"
                          value={submissionNotes}
                          onChange={(e) => {
                            setSubmissionNotes(e.target.value);
                            setSubmitVerifyErrors((prev) => ({
                              ...prev,
                              submissionNotes: undefined,
                            }));
                          }}
                          aria-invalid={Boolean(submitVerifyErrors.submissionNotes)}
                          aria-describedby={
                            submitVerifyErrors.submissionNotes ? submitVerifNotesErrId : undefined
                          }
                          placeholder="Summary of completed CAPA package..."
                          disabled={isMutating}
                          className={`w-full px-3 py-2 text-sm rounded-lg border bg-white focus:outline-none focus:ring-2 ${
                            submitVerifyErrors.submissionNotes
                              ? 'border-rose-400 focus:ring-rose-200'
                              : 'border-slate-300 focus:ring-purple-200'
                          }`}
                        />
                        {submitVerifyErrors.submissionNotes && (
                          <p id={submitVerifNotesErrId} role="alert" className="mt-1 text-xs text-rose-600">
                            {submitVerifyErrors.submissionNotes}
                          </p>
                        )}
                      </div>
                    </div>

                    <button
                      type="submit"
                      disabled={isMutating || !canSubmitVerification}
                      className="inline-flex items-center space-x-1.5 px-4 py-2 text-xs font-semibold text-white bg-purple-600 hover:bg-purple-700 disabled:bg-slate-300 disabled:text-slate-500 disabled:cursor-not-allowed rounded-lg shadow-sm"
                    >
                      <CheckCircle2 className="w-3.5 h-3.5" aria-hidden="true" />
                      <span>Submit for QA Verification</span>
                    </button>
                  </form>
                ) : (
                  <div className="p-4 rounded-xl bg-purple-50 border border-purple-200 text-xs text-purple-900">
                    <p className="font-semibold">QA Verification Submission Restricted</p>
                    <p className="mt-0.5 text-purple-800">
                      Submitting completed CAPAs for QA verification requires Production Supervisor, QC Analyst, or QA Reviewer access.
                    </p>
                  </div>
                )
              )}
            </div>
          </div>

          {/* Stage 5 & 6: QA Effectiveness Verification, Reopen CAPA, & Closure */}
          {(detail.status === 'PENDING_VERIFICATION' || detail.status === 'CLOSED') && (
            <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
              <div className="px-4 sm:px-6 py-4 bg-slate-50/70 border-b border-slate-200 flex flex-wrap items-center justify-between gap-2">
                <h3 className="text-base font-semibold text-slate-900 flex items-center space-x-2">
                  <ShieldCheck className="w-4 h-4 text-pharma-600" aria-hidden="true" />
                  <span>4. QA Effectiveness Verification &amp; Deviation Closure</span>
                </h3>
                {detail.status === 'CLOSED' && (
                  <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-100 text-emerald-800 border border-emerald-200">
                    CLOSED &amp; LOCKED
                  </span>
                )}
              </div>

              <div className="p-4 sm:p-6 space-y-5">
                {detail.status === 'CLOSED' ? (
                  <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs">
                    <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
                      <span className="font-semibold uppercase text-slate-500 block">Verified By</span>
                      <span className="text-sm font-semibold text-slate-900 mt-1 block">
                        {detail.verifiedBy}
                      </span>
                    </div>
                    <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
                      <span className="font-semibold uppercase text-slate-500 block">
                        Closed By &amp; Timestamp
                      </span>
                      <span className="text-sm font-semibold text-slate-900 mt-1 block tabular-nums">
                        {detail.closedBy} &bull; {formatDateTime(detail.closedAt)}
                      </span>
                    </div>
                    <div className="bg-slate-50 border border-slate-200 rounded-lg p-3 md:col-span-3">
                      <span className="font-semibold uppercase text-slate-500 block">
                        Verification Notes
                      </span>
                      <p className="text-sm text-slate-800 mt-1 whitespace-pre-wrap">
                        {detail.verificationNotes}
                      </p>
                    </div>
                  </div>
                ) : allowCloseDeviation || allowReopenCapa ? (
                  <div className="space-y-5">
                    {allowCloseDeviation && (
                      <form onSubmit={handleCloseDeviation} noValidate className="space-y-4">
                        <div className="rounded-lg bg-slate-50 border border-slate-200 px-3.5 py-2.5 flex items-center justify-between gap-2 text-xs">
                          <span className="font-semibold text-slate-600 uppercase tracking-wider">
                            QA Verifier / Closed By
                          </span>
                          <span
                            data-testid="authenticated-qa-closer-display"
                            className="font-semibold text-slate-900"
                          >
                            {actorDisplay}
                          </span>
                        </div>

                        <div>
                          <label
                            htmlFor="close-verification-notes"
                            className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                          >
                            Effectiveness Verification Notes <span className="text-rose-500">*</span>
                          </label>
                          <textarea
                            id="close-verification-notes"
                            rows={3}
                            value={verificationNotes}
                            onChange={(e) => {
                              setVerificationNotes(e.target.value);
                              setCloseErrors((prev) => ({
                                ...prev,
                                verificationNotes: undefined,
                              }));
                            }}
                            aria-invalid={Boolean(closeErrors.verificationNotes)}
                            aria-describedby={
                              closeErrors.verificationNotes ? closeVerifNotesErrId : undefined
                            }
                            placeholder="Document review of completed CAPA evidence, verification check summary, and closure rationale..."
                            disabled={isMutating}
                            className={`w-full px-3 py-2 text-sm rounded-lg border focus:outline-none focus:ring-2 ${
                              closeErrors.verificationNotes
                                ? 'border-rose-400 focus:ring-rose-200'
                                : 'border-slate-300 focus:ring-emerald-200 focus:border-emerald-500'
                            }`}
                          />
                          {closeErrors.verificationNotes && (
                            <p id={closeVerifNotesErrId} role="alert" className="mt-1 text-xs text-rose-600">
                              {closeErrors.verificationNotes}
                            </p>
                          )}
                        </div>

                        <div className="flex flex-wrap items-center justify-between gap-3 pt-2 border-t border-slate-100">
                          {allowReopenCapa ? (
                            <button
                              type="button"
                              onClick={() => setShowReopenForm((v) => !v)}
                              disabled={isMutating}
                              className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-xs font-semibold text-amber-800 bg-amber-50 hover:bg-amber-100 border border-amber-300 rounded-lg"
                            >
                              <RotateCcw className="w-3.5 h-3.5" aria-hidden="true" />
                              <span>{showReopenForm ? 'Hide Reopen CAPA' : 'Reopen CAPA Actions'}</span>
                            </button>
                          ) : (
                            <span />
                          )}

                          <button
                            type="submit"
                            disabled={isMutating || !detail.eligibleForClosure}
                            className="inline-flex items-center space-x-1.5 px-4 py-2 text-xs font-semibold text-white bg-emerald-600 hover:bg-emerald-700 disabled:bg-slate-300 disabled:text-slate-500 disabled:cursor-not-allowed rounded-lg shadow-sm"
                          >
                            <CheckCircle2 className="w-4 h-4" aria-hidden="true" />
                            <span>Verify &amp; Close Deviation</span>
                          </button>
                        </div>
                      </form>
                    )}

                    {/* Reopen CAPA Form */}
                    {allowReopenCapa && showReopenForm && (
                      <form
                        onSubmit={handleReopenCapa}
                        noValidate
                        className="p-4 rounded-xl bg-amber-50 border border-amber-200 space-y-3"
                      >
                        <h4 className="text-sm font-semibold text-amber-950">
                          Return Deviation to CAPA_IN_PROGRESS
                        </h4>
                        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                          <div>
                            <span className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1">
                              QA Reviewer (Authenticated)
                            </span>
                            <div
                              data-testid="authenticated-reopen-actor-display"
                              className="px-3 py-2 text-sm rounded-lg border border-slate-200 bg-white text-slate-800 font-medium"
                            >
                              {actorDisplay}
                            </div>
                          </div>

                          <div>
                            <label
                              htmlFor="reopen-reason"
                              className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1"
                            >
                              Mandatory Reopen Reason <span className="text-rose-500">*</span>
                            </label>
                            <input
                              id="reopen-reason"
                              type="text"
                              value={reopenReason}
                              onChange={(e) => {
                                setReopenReason(e.target.value);
                                setReopenErrors((prev) => ({ ...prev, reason: undefined }));
                              }}
                              aria-invalid={Boolean(reopenErrors.reason)}
                              aria-describedby={
                                reopenErrors.reason ? reopenReasonErrId : undefined
                              }
                              placeholder="Explain insufficient evidence or follow-up CAPA required..."
                              disabled={isMutating}
                              className={`w-full px-3 py-2 text-sm rounded-lg border bg-white focus:outline-none focus:ring-2 ${
                                reopenErrors.reason
                                  ? 'border-rose-400 focus:ring-rose-200'
                                  : 'border-slate-300 focus:ring-amber-200'
                              }`}
                            />
                            {reopenErrors.reason && (
                              <p id={reopenReasonErrId} role="alert" className="mt-1 text-xs text-rose-600">
                                {reopenErrors.reason}
                              </p>
                            )}
                          </div>
                        </div>

                        <button
                          type="submit"
                          disabled={isMutating}
                          className="inline-flex items-center space-x-1.5 px-4 py-2 text-xs font-semibold text-white bg-amber-600 hover:bg-amber-700 rounded-lg shadow-sm"
                        >
                          <RotateCcw className="w-3.5 h-3.5" aria-hidden="true" />
                          <span>Confirm Reopen CAPA</span>
                        </button>
                      </form>
                    )}
                  </div>
                ) : (
                  <div className="p-4 rounded-xl bg-slate-50 border border-slate-200 text-xs text-slate-700">
                    <p className="font-semibold text-slate-800">Awaiting QA Reviewer Verification</p>
                    <p className="mt-0.5 text-slate-600">
                      Only a QA Reviewer can verify effectiveness, reopen CAPAs, or close this deviation.
                    </p>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* Scoped Append-Only Audit History */}
          <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden flex flex-col">
            <div className="px-4 sm:px-6 py-4 bg-slate-50/70 border-b border-slate-200 flex flex-wrap items-center justify-between gap-2">
              <h3 className="text-sm font-semibold text-slate-900 flex items-center space-x-2">
                <History className="w-4 h-4 text-pharma-600" aria-hidden="true" />
                <span className="tabular-nums">
                  Deviation &amp; CAPA Audit Trail ({auditLogs.length})
                </span>
              </h3>
              <span className="inline-flex items-center text-[11px] font-medium text-slate-500">
                <Lock className="w-3 h-3 mr-1" aria-hidden="true" />
                Append-Only &bull; Read-Only
              </span>
            </div>

            {auditLogs.length === 0 ? (
              <div className="p-8 text-center text-xs text-slate-500">
                No audit entries recorded for this deviation yet.
              </div>
            ) : (
              <>
                <div className="divide-y divide-slate-200">
                  {paginatedAuditLogs.items.map((log, idx) => {
                    const entryKey = log.id ?? `dev-audit-${idx}`;
                    const snapshotSummary = summarizeAuditChanges(
                      log.beforeValues,
                      log.afterValues
                    );
                    const isExpanded = Boolean(expandedAuditIds[entryKey]);
                    const safeReason = log.reason ? redactAuditText(log.reason) : '';

                    return (
                      <div key={entryKey} className="p-4 space-y-2 text-xs">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="font-mono font-bold text-slate-800 bg-slate-100 px-2 py-0.5 rounded border border-slate-200">
                            {log.action}
                          </span>
                          <span className="text-slate-500 tabular-nums">
                            {formatDateTime(log.occurredAt)}
                          </span>
                        </div>
                        <div className="text-slate-600 flex flex-wrap items-center gap-x-3 gap-y-1">
                          <span>
                            Actor: <span className="font-semibold text-slate-800">{log.actorName}</span>
                          </span>
                          <span>
                            Ref:{' '}
                            <span className="font-mono tabular-nums">
                              {log.entityReference || `${log.entityType} #${log.entityId}`}
                            </span>
                          </span>
                        </div>
                        {safeReason && <p className="text-slate-700">{safeReason}</p>}

                        <div className="flex flex-wrap items-center gap-2">
                          <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-medium bg-slate-100 text-slate-600 border border-slate-200 tabular-nums">
                            {snapshotSummary.label}
                          </span>
                          {snapshotSummary.hasSnapshot && (
                            <button
                              type="button"
                              aria-expanded={isExpanded}
                              onClick={() => toggleAuditRowDiff(entryKey)}
                              className="inline-flex items-center space-x-1 px-2 py-0.5 rounded text-[11px] font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 transition-colors"
                            >
                              {isExpanded ? (
                                <>
                                  <ChevronDown className="w-3 h-3" aria-hidden="true" />
                                  <span>Hide Diff</span>
                                </>
                              ) : (
                                <>
                                  <ChevronRight className="w-3 h-3" aria-hidden="true" />
                                  <span>Inspect Diff</span>
                                </>
                              )}
                            </button>
                          )}
                        </div>

                        {isExpanded && snapshotSummary.hasSnapshot && (
                          <AuditDiffViewer
                            beforeValues={log.beforeValues}
                            afterValues={log.afterValues}
                            className="pt-1"
                          />
                        )}
                      </div>
                    );
                  })}
                </div>
                <Pagination
                  currentPage={paginatedAuditLogs.page}
                  pageSize={paginatedAuditLogs.pageSize}
                  totalItems={paginatedAuditLogs.totalItems}
                  onPageChange={setAuditPage}
                  onPageSizeChange={(nextSize) => {
                    setAuditPageSize(nextSize);
                    setAuditPage(1);
                  }}
                  itemLabel="audit entries"
                  hideWhenEmpty={true}
                />
              </>
            )}
          </div>
        </>
      )}
    </div>
  );
}
