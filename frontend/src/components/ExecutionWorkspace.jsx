import React, { useState, useEffect, useCallback, useMemo, useRef, useContext } from 'react';
import {
  ArrowLeft,
  ArrowUpRight,
  RefreshCw,
  CheckCircle2,
  Clock,
  Play,
  Lock,
  ClipboardCheck,
  User,
  FileText,
  Layers,
  FlaskConical,
  Box,
  ClipboardList,
  ShieldCheck,
  Calendar,
} from 'lucide-react';
import {
  getBatchExecutionWorkspace,
  initializeBatchExecutionSteps,
  startExecutionStep,
} from '../services/productionExecutionApi';
import CompleteExecutionStepModal from './CompleteExecutionStepModal';
import { InlineAlert, ProgressBar, StatusBadge } from './ui/index.js';
import { AuthContext } from '../context/AuthContext.jsx';
import { useNavigation } from '../context/NavigationContext.jsx';
import { canViewRecipes } from '../utils/rbacUtils.js';
import {
  formatAppDateTime,
  formatAppTimeOnly,
  formatCalendarDate,
} from '../utils/dateTimeUtils.js';

function formatDateTime(dateTimeString) {
  return formatAppDateTime(dateTimeString);
}

function formatTimeOnly(date) {
  return formatAppTimeOnly(date);
}

function formatDateOnly(dateString) {
  return formatCalendarDate(dateString);
}

function renderStepStatusBadge(status) {
  switch (status) {
    case 'COMPLETED':
      return (
        <StatusBadge
          status="COMPLETED"
          icon={CheckCircle2}
        />
      );
    case 'IN_PROGRESS':
      return (
        <StatusBadge
          status="IN_PROGRESS"
          showDot
          pulseDot
        />
      );
    case 'PENDING':
    default:
      return (
        <StatusBadge
          status={status || 'PENDING'}
          tone="neutral"
          icon={Clock}
        />
      );
  }
}

export default function ExecutionWorkspace({
  batchId,
  batchContext = null,
  readOnly = false,
  readOnlyReason = 'history',
  onBack,
  onExecutionUpdated,
  onNotify,
}) {
  const authContext = useContext(AuthContext);
  const role = authContext?.role ?? null;
  const { navigateToTab } = useNavigation();
  const canOpenRecipes = canViewRecipes(role);

  const [workspace, setWorkspace] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [loadingPhase, setLoadingPhase] = useState('loading'); // 'loading' | 'initializing'
  const [loadError, setLoadError] = useState(null);
  const [refreshError, setRefreshError] = useState(null);
  const [lastRefreshedAt, setLastRefreshedAt] = useState(null);
  const [actionError, setActionError] = useState(null);
  const [initNotice, setInitNotice] = useState(null);
  const [startingStepId, setStartingStepId] = useState(null);
  const [completeModalStep, setCompleteModalStep] = useState(null);
  const [stepFilter, setStepFilter] = useState('ALL');

  const isMountedRef = useRef(true);
  const isStartingRef = useRef(false);
  const isRefreshingRef = useRef(false);
  const workspaceLoadedRef = useRef(false);
  const onExecutionUpdatedRef = useRef(onExecutionUpdated);
  const onNotifyRef = useRef(onNotify);

  useEffect(() => {
    onExecutionUpdatedRef.current = onExecutionUpdated;
  }, [onExecutionUpdated]);

  useEffect(() => {
    onNotifyRef.current = onNotify;
  }, [onNotify]);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  const loadWorkspaceData = useCallback(
    async ({
      allowLegacyInit = false,
      manualRefresh = false,
      isCancelled = () => !isMountedRef.current,
    } = {}) => {
      if (!batchId) return;

      if (manualRefresh) {
        isRefreshingRef.current = true;
        setIsRefreshing(true);
        setRefreshError(null);
      } else {
        setIsLoading(true);
        setLoadingPhase('loading');
        setLoadError(null);
        setRefreshError(null);
      }

      try {
        const data = await getBatchExecutionWorkspace(batchId);
        if (isCancelled()) return;

        const steps = Array.isArray(data?.executionSteps) ? data.executionSteps : [];

        // Legacy backfill: only for active IN_PROGRESS batches in interactive execution mode
        // when no execution step records exist yet.
        if (allowLegacyInit && data?.batchStatus === 'IN_PROGRESS' && steps.length === 0) {
          setWorkspace(data);
          workspaceLoadedRef.current = true;
          setLoadingPhase('initializing');

          await initializeBatchExecutionSteps(batchId);
          if (isCancelled()) return;

          const reloadedWorkspace = await getBatchExecutionWorkspace(batchId);
          if (isCancelled()) return;

          setWorkspace(reloadedWorkspace || data);
          workspaceLoadedRef.current = true;
          setLastRefreshedAt(new Date());
          const initMessage = `Initialized execution steps from master recipe for batch ${
            reloadedWorkspace?.batchNumber || data?.batchNumber || `#${batchId}`
          }.`;
          setInitNotice(initMessage);

          if (onExecutionUpdatedRef.current) {
            onExecutionUpdatedRef.current();
          }
        } else {
          setWorkspace(data || null);
          workspaceLoadedRef.current = Boolean(data);
          setLastRefreshedAt(new Date());
        }
      } catch (err) {
        if (isCancelled()) return;
        const message = err.message || 'Failed to load batch execution workspace';
        if (manualRefresh && workspaceLoadedRef.current) {
          setRefreshError(message);
          if (onNotifyRef.current) {
            onNotifyRef.current({ type: 'error', message });
          }
        } else {
          setLoadError(message);
        }
      } finally {
        if (manualRefresh) {
          isRefreshingRef.current = false;
        }
        if (!isCancelled()) {
          if (manualRefresh) {
            setIsRefreshing(false);
          } else {
            setIsLoading(false);
            setLoadingPhase('loading');
          }
        }
      }
    },
    [batchId]
  );

  useEffect(() => {
    setWorkspace(null);
    workspaceLoadedRef.current = false;
    setIsRefreshing(false);
    isRefreshingRef.current = false;
    setLoadError(null);
    setRefreshError(null);
    setLastRefreshedAt(null);
    setActionError(null);
    setInitNotice(null);
    setStartingStepId(null);
    setCompleteModalStep(null);
    setStepFilter('ALL');
    isStartingRef.current = false;

    if (!batchId) {
      setIsLoading(false);
      return undefined;
    }

    let cancelled = false;
    loadWorkspaceData({
      allowLegacyInit: !readOnly,
      isCancelled: () => cancelled || !isMountedRef.current,
    });

    return () => {
      cancelled = true;
    };
  }, [batchId, readOnly, loadWorkspaceData]);

  const sortedSteps = useMemo(() => {
    const rawSteps = Array.isArray(workspace?.executionSteps) ? workspace.executionSteps : [];
    return [...rawSteps].sort((a, b) => (a.stepNumber ?? 0) - (b.stepNumber ?? 0));
  }, [workspace]);

  const completedStepsCount = useMemo(
    () => sortedSteps.filter((s) => s.status === 'COMPLETED').length,
    [sortedSteps]
  );

  const inProgressStepsCount = useMemo(
    () => sortedSteps.filter((s) => s.status === 'IN_PROGRESS').length,
    [sortedSteps]
  );

  const pendingStepsCount = useMemo(
    () => sortedSteps.filter((s) => s.status === 'PENDING' || (!s.status && s.status !== 'COMPLETED' && s.status !== 'IN_PROGRESS')).length,
    [sortedSteps]
  );

  const visibleSteps = useMemo(() => {
    if (stepFilter === 'ALL') return sortedSteps;
    return sortedSteps.filter((step) => (step.status || 'PENDING') === stepFilter);
  }, [sortedSteps, stepFilter]);

  const totalStepsCount = sortedSteps.length;
  const isCancelledBatch = workspace?.batchStatus === 'CANCELLED';
  const rawProgressPercent =
    totalStepsCount > 0 ? Math.round((completedStepsCount / totalStepsCount) * 100) : 0;
  const progressPercent = isCancelledBatch ? 0 : rawProgressPercent;

  const isBatchInProgress = workspace?.batchStatus === 'IN_PROGRESS';
  const isStrictlyReadOnly = readOnly || !isBatchInProgress;
  const isInteractive = Boolean(workspace && !readOnly && isBatchInProgress);

  const hasActiveStep = useMemo(
    () => sortedSteps.some((step) => step.status === 'IN_PROGRESS'),
    [sortedSteps]
  );

  const nextEligibleStep = useMemo(() => {
    if (!isInteractive || hasActiveStep) return null;
    const candidate = sortedSteps.find((step) => step.status !== 'COMPLETED');
    return candidate && candidate.status === 'PENDING' ? candidate : null;
  }, [isInteractive, hasActiveStep, sortedSteps]);

  const allStepsCompleted =
    !isCancelledBatch && totalStepsCount > 0 && completedStepsCount === totalStepsCount;

  // Merge verified API fields optionally passed by parent Order or Quality views
  const effectiveOrderId = batchContext?.orderId ?? workspace?.orderId ?? null;
  const effectiveOrderNumber = workspace?.orderNumber ?? batchContext?.orderNumber ?? null;
  const effectiveProductCode = batchContext?.productCode ?? workspace?.productCode ?? null;
  const effectiveRecipeId = batchContext?.recipeId ?? workspace?.recipeId ?? null;
  const effectiveActualYield = batchContext?.actualYield ?? workspace?.actualYield ?? null;
  const effectiveQaStatus = batchContext?.qaStatus ?? workspace?.qaStatus ?? null;
  const effectiveManufactureDate =
    batchContext?.manufactureDate ?? workspace?.manufactureDate ?? null;
  const effectiveExpiryDate = batchContext?.expiryDate ?? workspace?.expiryDate ?? null;
  const effectiveStartTime = batchContext?.startTime ?? workspace?.startTime ?? null;
  const effectiveEndTime = batchContext?.endTime ?? workspace?.endTime ?? null;
  const fromOrderDetails = Boolean(batchContext?.fromOrderDetails);
  const fromQualityDossier = Boolean(batchContext?.fromQualityDossier);

  const yieldRecoveryPercent =
    effectiveActualYield != null &&
    workspace?.batchSize != null &&
    Number(workspace.batchSize) > 0
      ? ((Number(effectiveActualYield) / Number(workspace.batchSize)) * 100).toFixed(1)
      : null;

  const hasQualitySummary =
    batchContext?.totalChecksCount != null || batchContext?.totalDeviationsCount != null;

  const hasExtendedEbrContext = Boolean(
    effectiveActualYield != null ||
      effectiveQaStatus ||
      effectiveManufactureDate ||
      effectiveExpiryDate ||
      effectiveStartTime ||
      effectiveEndTime ||
      hasQualitySummary
  );

  const handleRefresh = () => {
    if (isLoading || isRefreshing || isRefreshingRef.current || startingStepId !== null) return;
    setActionError(null);
    setRefreshError(null);
    loadWorkspaceData({ allowLegacyInit: !readOnly, manualRefresh: true });
  };

  const handleStartStep = async (step) => {
    if (
      !isInteractive ||
      !step?.id ||
      isStartingRef.current ||
      startingStepId !== null ||
      isRefreshing
    ) {
      return;
    }

    isStartingRef.current = true;
    setStartingStepId(step.id);
    setActionError(null);

    try {
      const startedStep = await startExecutionStep(batchId, step.id);
      const successMsg = `Step ${startedStep?.stepNumber ?? step.stepNumber} (${
        startedStep?.operationName || step.operationName
      }) started.`;

      if (onNotify) {
        onNotify({ type: 'success', message: successMsg });
      }

      await loadWorkspaceData({ allowLegacyInit: false });
      if (onExecutionUpdatedRef.current) {
        await onExecutionUpdatedRef.current();
      }
    } catch (err) {
      const errorMsg = err.message || `Failed to start step ${step.stepNumber}`;
      setActionError(errorMsg);
      if (onNotify) {
        onNotify({ type: 'error', message: errorMsg });
      }
    } finally {
      isStartingRef.current = false;
      if (isMountedRef.current) {
        setStartingStepId(null);
      }
    }
  };

  const handleStepCompleted = async (completedStep) => {
    setActionError(null);
    const successMsg = `Step ${completedStep?.stepNumber ?? completeModalStep?.stepNumber} (${
      completedStep?.operationName || completeModalStep?.operationName || 'Operation'
    }) completed.`;

    if (onNotify) {
      onNotify({ type: 'success', message: successMsg });
    }

    await loadWorkspaceData({ allowLegacyInit: false });
    if (onExecutionUpdatedRef.current) {
      await onExecutionUpdatedRef.current();
    }
  };

  const handleOpenOrder = () => {
    if (effectiveOrderId) {
      navigateToTab('orders', { orderId: effectiveOrderId });
    } else if (effectiveOrderNumber) {
      navigateToTab('orders', { orderNumber: effectiveOrderNumber });
    }
  };

  const handleOpenRecipe = () => {
    if (!canOpenRecipes) return;
    if (effectiveRecipeId) {
      navigateToTab('recipes', { recipeId: effectiveRecipeId });
    } else if (workspace?.recipeCode) {
      navigateToTab('recipes', { recipeCode: workspace.recipeCode });
    }
  };

  return (
    <div className="space-y-5">
      {/* Top Action Bar (rendered inline inside ProductionExecution) */}
      {onBack && (
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 pb-4 border-b border-slate-200">
          <div className="flex flex-col sm:flex-row sm:items-center gap-2.5 sm:gap-3 min-w-0">
            <button
              type="button"
              onClick={onBack}
              className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors self-start sm:self-auto whitespace-nowrap shrink-0"
            >
              <ArrowLeft className="w-4 h-4 text-slate-500 shrink-0" aria-hidden="true" />
              <span>Back to Execution Queue</span>
            </button>
            <div className="min-w-0">
              <h1 className="text-xl font-bold text-slate-900 tracking-tight flex flex-wrap items-center gap-2">
                <span>Batch Execution Workspace</span>
                {isStrictlyReadOnly && workspace && (
                  <StatusBadge
                    status="Read-Only"
                    label="Read-Only"
                    tone="muted"
                    icon={Lock}
                    size="xs"
                  />
                )}
              </h1>
            </div>
          </div>

          <button
            type="button"
            onClick={handleRefresh}
            disabled={isLoading || isRefreshing || startingStepId !== null}
            className="inline-flex items-center space-x-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50 disabled:cursor-not-allowed self-start sm:self-auto whitespace-nowrap shrink-0"
          >
            <RefreshCw
              className={`w-4 h-4 ${
                isLoading || isRefreshing ? 'animate-spin text-pharma-600' : 'text-slate-500'
              }`}
              aria-hidden="true"
            />
            <span>{isRefreshing ? 'Refreshing...' : 'Refresh Workspace'}</span>
          </button>
        </div>
      )}

      {/* Initial Loading State */}
      {isLoading && !workspace && (
        <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-8 sm:p-12 text-center">
          <RefreshCw className="w-8 h-8 mx-auto text-pharma-600 animate-spin mb-3" aria-hidden="true" />
          <p className="text-sm font-medium text-slate-700">
            {loadingPhase === 'initializing'
              ? 'Initializing execution steps for active batch...'
              : 'Loading batch execution workspace...'}
          </p>
          <p className="text-xs text-slate-500 mt-1">
            Fetching batch metadata and ordered operation steps from server
          </p>
        </div>
      )}

      {/* Initializing Banner when workspace metadata already loaded */}
      {isLoading && workspace && loadingPhase === 'initializing' && (
        <InlineAlert
          variant="info"
          icon={RefreshCw}
          title="Initializing legacy execution steps..."
          message="Creating ordered execution records from the associated master recipe and reloading workspace."
        />
      )}

      {/* Fatal Load / Initialization Error Alert */}
      {!isLoading && loadError && (
        <InlineAlert
          variant="error"
          role="alert"
          title="Unable to load execution workspace"
          message={loadError}
          actions={
            <button
              type="button"
              onClick={() => loadWorkspaceData({ allowLegacyInit: !readOnly })}
              className="inline-flex items-center space-x-1.5 text-xs font-semibold px-3 py-1.5 bg-white border border-rose-300 rounded-lg text-rose-800 hover:bg-rose-50 transition-colors"
            >
              <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Retry</span>
            </button>
          }
        />
      )}

      {/* Workspace Content */}
      {workspace && !loadError && (
        <>
          {/* Read-Only / Completion Banners */}
          {workspace.batchStatus === 'COMPLETED' && (
            <InlineAlert
              variant="success"
              icon={Lock}
              title="Completed Batch — Read-Only Execution Record"
              message="This production batch is COMPLETED. All snapshotted recipe instructions, recorded values, and Asia/Kolkata (IST) timestamps are locked for traceability and audit review."
            />
          )}

          {isCancelledBatch && (
            <InlineAlert
              variant="error"
              icon={Lock}
              title="Cancelled Batch — Not Completed"
              message="This production batch is CANCELLED and excluded from completion. Historical records remain locked."
            />
          )}

          {readOnly && workspace.batchStatus === 'IN_PROGRESS' && (
            <InlineAlert
              variant="info"
              icon={Lock}
              title={
                readOnlyReason === 'role'
                  ? 'Read-Only Monitoring Mode (Role Restricted)'
                  : 'Read-Only Execution History'
              }
              message={
                readOnlyReason === 'role'
                  ? 'Shop-floor step initialization and execution require the Production Operator role. You can monitor progress and recorded eBR data here.'
                  : 'Viewing active batch step records in read-only mode from historical context.'
              }
              actions={
                readOnlyReason !== 'role' && workspace.batchId != null ? (
                  <button
                    type="button"
                    onClick={() => navigateToTab('execution', { batchId: workspace.batchId })}
                    className="inline-flex items-center space-x-1.5 text-xs font-semibold px-3 py-1.5 bg-white border border-pharma-300 rounded-lg text-pharma-800 hover:bg-pharma-100 transition-colors"
                  >
                    <span>Open in Production Execution</span>
                    <ArrowUpRight className="w-3.5 h-3.5" aria-hidden="true" />
                  </button>
                ) : null
              }
            />
          )}

          {!readOnly && workspace.batchStatus !== 'IN_PROGRESS' && workspace.batchStatus !== 'COMPLETED' && !isCancelledBatch && (
            <InlineAlert
              variant="warning"
              icon={Lock}
              title={`Execution Actions Disabled (${workspace.batchStatus})`}
              message="Operation execution actions are only permitted when the batch status is IN_PROGRESS."
            />
          )}

          {/* Auto-Initialization Notice */}
          {initNotice && (
            <InlineAlert
              variant="info"
              title="Legacy Batch Initialized"
              message={initNotice}
              onDismiss={() => setInitNotice(null)}
              dismissLabel="Dismiss notice"
            />
          )}

          {/* All Steps Complete Guidance */}
          {isInteractive && allStepsCompleted && (
            <InlineAlert
              variant="success"
              icon={CheckCircle2}
              title="All Execution Steps Completed"
              message={`Every recipe step for batch ${workspace.batchNumber} is verified and COMPLETED. Record actual yield and finalize batch completion in Manufacturing Orders.`}
              actions={
                effectiveOrderId || effectiveOrderNumber ? (
                  <button
                    type="button"
                    onClick={handleOpenOrder}
                    className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-emerald-900 bg-white hover:bg-emerald-100 border border-emerald-300 rounded-lg shadow-sm transition-colors"
                  >
                    <span>Open Manufacturing Order to Complete Batch</span>
                    <ArrowUpRight className="w-3.5 h-3.5" aria-hidden="true" />
                  </button>
                ) : null
              }
            />
          )}

          {/* Action Error Alert */}
          {actionError && (
            <InlineAlert
              variant="error"
              role="alert"
              title="Execution Action Rejected"
              message={actionError}
              onDismiss={() => setActionError(null)}
              dismissLabel="Dismiss error"
            />
          )}

          {/* Batch Summary Card */}
          <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-5 space-y-4">
            <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4 pb-4 border-b border-slate-100">
              <div className="flex items-center space-x-3">
                <div className="w-10 h-10 rounded-lg bg-pharma-600 text-white flex items-center justify-center shadow-sm flex-shrink-0">
                  <Layers className="w-5 h-5" aria-hidden="true" />
                </div>
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <h2 className="text-lg font-bold font-mono text-slate-900 tracking-tight tabular-nums">
                      {workspace.batchNumber || `Batch #${workspace.batchId}`}
                    </h2>
                    <StatusBadge
                      status={workspace.batchStatus}
                      domain="order"
                      showDot
                      pulseDot={workspace.batchStatus === 'IN_PROGRESS'}
                    />
                    {effectiveQaStatus && (
                      <StatusBadge
                        status={effectiveQaStatus}
                        domain="qa"
                        size="xs"
                      />
                    )}
                  </div>
                  <p className="text-xs text-slate-500 mt-0.5">
                    Electronic Batch Execution Record (eBR)
                  </p>
                </div>
              </div>

              {/* Accessible Progress Indicator */}
              <div className="sm:min-w-[230px] max-w-xs w-full">
                {isCancelledBatch ? (
                  <ProgressBar
                    value={0}
                    max={100}
                    tone="danger"
                    size="md"
                    label="Batch execution progress"
                    showLabel
                    summaryText={`${completedStepsCount} of ${totalStepsCount} prior to cancel`}
                    showPercentage={false}
                    valueText="Batch cancelled — 0% active completion"
                  />
                ) : (
                  <ProgressBar
                    value={progressPercent}
                    max={100}
                    tone={
                      progressPercent === 100
                        ? 'success'
                        : progressPercent > 0 || hasActiveStep
                          ? 'warning'
                          : 'neutral'
                    }
                    size="md"
                    label="Step Progress"
                    showLabel
                    summaryText={`${completedStepsCount} of ${totalStepsCount}`}
                    showPercentage
                    valueText={`${completedStepsCount} of ${totalStepsCount} steps completed (${progressPercent}%)`}
                  />
                )}
              </div>
            </div>

            {/* Primary Batch Identity Metadata Grid */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3.5">
              <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                    <ClipboardList className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                    <span>Order Number</span>
                  </span>
                  {!fromOrderDetails && (effectiveOrderId || effectiveOrderNumber) && (
                    <button
                      type="button"
                      onClick={handleOpenOrder}
                      className="inline-flex items-center space-x-0.5 text-[11px] font-semibold text-pharma-700 hover:text-pharma-800 hover:underline"
                    >
                      <span>Open Order</span>
                      <ArrowUpRight className="w-3 h-3" aria-hidden="true" />
                    </button>
                  )}
                </div>
                <span className="text-sm font-mono font-bold text-slate-900 mt-1 block tabular-nums">
                  {effectiveOrderNumber || '—'}
                </span>
              </div>

              <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                  <Box className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                  <span>Product Name</span>
                </span>
                <span className="text-sm font-semibold text-slate-900 mt-1 block">
                  {workspace.productName || '—'}
                </span>
                {effectiveProductCode && (
                  <span className="text-[11px] font-mono text-slate-500 mt-0.5 block tabular-nums">
                    {effectiveProductCode}
                  </span>
                )}
              </div>

              <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                    <FlaskConical className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                    <span>Recipe &amp; Version</span>
                  </span>
                  {canOpenRecipes && (effectiveRecipeId || workspace.recipeCode) && (
                    <button
                      type="button"
                      onClick={handleOpenRecipe}
                      className="inline-flex items-center space-x-0.5 text-[11px] font-semibold text-pharma-700 hover:text-pharma-800 hover:underline"
                    >
                      <span>Inspect BOM</span>
                      <ArrowUpRight className="w-3 h-3" aria-hidden="true" />
                    </button>
                  )}
                </div>
                <div className="flex items-center space-x-2 mt-1">
                  <span className="text-sm font-mono font-semibold text-slate-900 tabular-nums">
                    {workspace.recipeCode || '—'}
                  </span>
                  {workspace.recipeVersion != null && (
                    <span className="px-1.5 py-0.5 rounded text-[11px] font-bold bg-indigo-50 text-indigo-700 border border-indigo-200 tabular-nums">
                      v{workspace.recipeVersion}
                    </span>
                  )}
                </div>
              </div>

              <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                  <Layers className="w-3.5 h-3.5 text-pharma-600" aria-hidden="true" />
                  <span>Target Batch Size</span>
                </span>
                <span className="text-sm font-bold text-slate-900 mt-1 block tabular-nums">
                  {workspace.batchSize != null
                    ? `${workspace.batchSize}${workspace.batchUnit ? ` ${workspace.batchUnit}` : ''}`
                    : '—'}
                </span>
              </div>
            </div>

            {/* Extended Verified eBR Yield, Timeline & Quality Summary (only when provided by parent context) */}
            {hasExtendedEbrContext && (
              <div
                data-testid="ebr-extended-summary"
                className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 pt-1"
              >
                <div className="bg-white border border-slate-200 rounded-lg p-3">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 block">
                    Actual Yield &amp; Recovery
                  </span>
                  <span className="text-sm font-bold text-slate-900 mt-1 block tabular-nums">
                    {effectiveActualYield != null
                      ? `${effectiveActualYield}${workspace.batchUnit ? ` ${workspace.batchUnit}` : ''}`
                      : 'Pending completion'}
                  </span>
                  {yieldRecoveryPercent && (
                    <span className="text-[11px] font-medium text-emerald-700 mt-0.5 block tabular-nums">
                      {yieldRecoveryPercent}% of target size
                    </span>
                  )}
                </div>

                <div className="bg-white border border-slate-200 rounded-lg p-3">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                    <Clock className="w-3 h-3 text-slate-400" aria-hidden="true" />
                    <span>Batch Execution Window</span>
                  </span>
                  <div className="text-xs text-slate-700 mt-1 space-y-0.5 tabular-nums">
                    <div>Start: {formatDateTime(effectiveStartTime)}</div>
                    <div>End: {formatDateTime(effectiveEndTime)}</div>
                  </div>
                </div>

                <div className="bg-white border border-slate-200 rounded-lg p-3">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                    <Calendar className="w-3 h-3 text-slate-400" aria-hidden="true" />
                    <span>Manufacture &amp; Expiry</span>
                  </span>
                  <div className="text-xs text-slate-700 mt-1 space-y-0.5 tabular-nums">
                    <div>Mfg Date: {formatDateOnly(effectiveManufactureDate)}</div>
                    <div>Expiry Date: {formatDateOnly(effectiveExpiryDate)}</div>
                  </div>
                </div>

                <div className="bg-white border border-slate-200 rounded-lg p-3">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                    <ShieldCheck className="w-3 h-3 text-pharma-600" aria-hidden="true" />
                    <span>Quality &amp; Release Context</span>
                  </span>
                  <div className="mt-1 space-y-1">
                    {effectiveQaStatus ? (
                      <div className="text-xs font-semibold text-slate-800">
                        Disposition: {effectiveQaStatus}
                      </div>
                    ) : (
                      <div className="text-xs text-slate-500">QA Disposition: Pending completion</div>
                    )}
                    {hasQualitySummary && (
                      <div className="text-[11px] text-slate-600 tabular-nums">
                        QC: {batchContext.passedChecksCount ?? 0}P / {batchContext.failedChecksCount ?? 0}F /{' '}
                        {batchContext.pendingChecksCount ?? 0} Pending &bull; Deviations:{' '}
                        {batchContext.openDeviationsCount ?? 0} Open /{' '}
                        {batchContext.totalDeviationsCount ?? 0} Total
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )}

            {/* Honest Snapshotted Recipe vs. Master BOM Reference Note + Inline Navigation */}
            <div className="pt-3 border-t border-slate-100 flex flex-col lg:flex-row lg:items-center justify-between gap-2.5 text-xs text-slate-600">
              <p className="leading-relaxed">
                <span className="font-semibold text-slate-700">Snapshotted eBR Scope:</span>{' '}
                Operation instructions below are snapshotted from recipe{' '}
                <span className="font-mono font-semibold text-slate-800">
                  {workspace.recipeCode || '—'}
                  {workspace.recipeVersion != null ? ` v${workspace.recipeVersion}` : ''}
                </span>
                . Master ingredient BOM quantities are defined in Recipe Management; timestamps render in Asia/Kolkata (IST).
              </p>

              {!fromQualityDossier &&
                workspace.batchStatus === 'COMPLETED' &&
                workspace.batchId != null &&
                onBack && (
                  <button
                    type="button"
                    onClick={() => navigateToTab('quality', { batchId: workspace.batchId })}
                    className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg transition-colors shrink-0 self-start lg:self-auto"
                  >
                    <ShieldCheck className="w-3.5 h-3.5" aria-hidden="true" />
                    <span>Open Quality Dossier</span>
                  </button>
                )}
            </div>
          </div>

          {/* Execution Steps List & Non-Mutating Status Filters */}
          <div className="space-y-3">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <div>
                <h3 className="text-xs font-bold uppercase tracking-wider text-slate-500 flex items-center space-x-1.5">
                  <ClipboardCheck className="w-4 h-4 text-pharma-600" aria-hidden="true" />
                  <span className="tabular-nums">Execution Operations ({sortedSteps.length})</span>
                </h3>
                {sortedSteps.length > 0 && (
                  <p className="text-[11px] text-slate-500 mt-0.5 tabular-nums">
                    Active: <span className="font-semibold text-amber-700">{inProgressStepsCount}</span>{' '}
                    &bull; Pending:{' '}
                    <span className="font-semibold text-slate-700">{pendingStepsCount}</span> &bull;
                    Completed:{' '}
                    <span className="font-semibold text-emerald-700">{completedStepsCount}</span>
                  </p>
                )}
              </div>

              <div className="flex flex-wrap items-center gap-2">
                {sortedSteps.length > 0 && (
                  <div
                    role="group"
                    aria-label="Filter execution steps by status"
                    className="inline-flex flex-wrap items-center gap-1 bg-slate-100 p-1 rounded-lg border border-slate-200 tabular-nums max-w-full"
                  >
                    {[
                      { id: 'ALL', label: `All (${sortedSteps.length})` },
                      { id: 'IN_PROGRESS', label: `Active (${inProgressStepsCount})` },
                      { id: 'PENDING', label: `Pending (${pendingStepsCount})` },
                      { id: 'COMPLETED', label: `Completed (${completedStepsCount})` },
                    ].map((opt) => (
                      <button
                        key={opt.id}
                        type="button"
                        aria-pressed={stepFilter === opt.id}
                        onClick={() => setStepFilter(opt.id)}
                        className={`px-2.5 py-1 text-[11px] font-medium rounded-md transition-colors whitespace-nowrap ${
                          stepFilter === opt.id
                            ? 'bg-white text-slate-900 shadow-sm font-semibold'
                            : 'text-slate-600 hover:text-slate-900'
                        }`}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                )}

                {!onBack && (
                  <div className="flex items-center space-x-3">
                    {lastRefreshedAt && !isRefreshing && (
                      <span className="text-[11px] text-slate-500 hidden sm:inline tabular-nums">
                        Last refreshed {formatTimeOnly(lastRefreshedAt)}
                      </span>
                    )}
                    <button
                      type="button"
                      onClick={handleRefresh}
                      disabled={isLoading || isRefreshing || startingStepId !== null}
                      title="Fetch latest execution history from server"
                      className="inline-flex items-center space-x-1.5 text-xs font-medium text-slate-700 hover:text-slate-900 px-3 py-1.5 rounded-md border border-slate-200 bg-white hover:bg-slate-50 shadow-sm transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                    >
                      <RefreshCw
                        className={`w-3.5 h-3.5 ${
                          isLoading || isRefreshing ? 'animate-spin text-pharma-600' : 'text-slate-500'
                        }`}
                        aria-hidden="true"
                      />
                      <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
                    </button>
                  </div>
                )}
              </div>
            </div>

            {refreshError && (
              <InlineAlert
                variant="error"
                role="alert"
                title="Unable to refresh execution history"
                message={refreshError}
                onDismiss={() => setRefreshError(null)}
                dismissLabel="Dismiss refresh error"
              />
            )}

            {isRefreshing ? (
              <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-10 text-center">
                <RefreshCw className="w-7 h-7 mx-auto text-pharma-600 animate-spin mb-2.5" aria-hidden="true" />
                <p className="text-sm font-medium text-slate-700">
                  {readOnly
                    ? 'Refreshing execution history...'
                    : 'Refreshing execution workspace...'}
                </p>
                <p className="text-xs text-slate-500 mt-1">
                  Fetching latest batch metadata and step records from server
                </p>
              </div>
            ) : sortedSteps.length === 0 ? (
              <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-8 sm:p-10 text-center">
                <div className="w-12 h-12 rounded-full bg-slate-100 text-slate-500 flex items-center justify-center mx-auto mb-3">
                  <ClipboardCheck className="w-6 h-6" aria-hidden="true" />
                </div>
                <h4 className="text-base font-semibold text-slate-800">
                  No execution steps recorded
                </h4>
                <p className="text-xs text-slate-500 mt-1 max-w-md mx-auto">
                  {readOnly && workspace.batchStatus === 'IN_PROGRESS'
                    ? readOnlyReason === 'role'
                      ? 'Execution steps have not been initialized by a Production Operator for this active batch yet.'
                      : 'Execution steps have not been initialized for this active batch yet.'
                    : 'No execution step records exist for this production batch.'}
                </p>
                {readOnly &&
                  workspace.batchStatus === 'IN_PROGRESS' &&
                  readOnlyReason !== 'role' &&
                  workspace.batchId != null && (
                    <button
                      type="button"
                      onClick={() => navigateToTab('execution', { batchId: workspace.batchId })}
                      className="mt-4 inline-flex items-center space-x-1.5 px-3.5 py-2 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg transition-colors"
                    >
                      <span>Open in Production Execution</span>
                      <ArrowUpRight className="w-3.5 h-3.5" aria-hidden="true" />
                    </button>
                  )}
              </div>
            ) : visibleSteps.length === 0 ? (
              <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-8 text-center">
                <p className="text-sm font-semibold text-slate-800">
                  No execution steps in this filter
                </p>
                <p className="text-xs text-slate-500 mt-1">
                  Switch back to All steps to view the full sequential operation list.
                </p>
                <button
                  type="button"
                  onClick={() => setStepFilter('ALL')}
                  className="mt-3 px-3 py-1.5 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg transition-colors"
                >
                  Show All Steps ({sortedSteps.length})
                </button>
              </div>
            ) : (
              <div className="space-y-3">
                {visibleSteps.map((step) => {
                  const isStepCompleted = step.status === 'COMPLETED';
                  const isStepInProgress = step.status === 'IN_PROGRESS';
                  const isStepPending = step.status === 'PENDING';
                  const isStepEligibleToStart = Boolean(
                    isInteractive && isStepPending && nextEligibleStep && nextEligibleStep.id === step.id
                  );
                  const isStepStarting = startingStepId === step.id;
                  const anyStartBusy = startingStepId !== null;

                  return (
                    <div
                      key={step.id ?? step.stepNumber}
                      data-step-status={step.status || 'PENDING'}
                      data-step-eligible={isStepEligibleToStart ? 'true' : 'false'}
                      className={`bg-white rounded-xl border border-l-4 shadow-sm overflow-hidden transition-all ${
                        isStepInProgress
                          ? 'border-amber-300 border-l-amber-500 ring-1 ring-amber-200'
                          : isStepEligibleToStart
                            ? 'border-pharma-300 border-l-pharma-500 ring-1 ring-pharma-100'
                            : isStepCompleted
                              ? 'border-emerald-200 border-l-emerald-500'
                              : 'border-slate-200 border-l-slate-300'
                      }`}
                    >
                      {/* Step Header */}
                      <div
                        className={`px-5 py-3.5 border-b flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 ${
                          isStepInProgress
                            ? 'bg-amber-50/70 border-amber-200'
                            : isStepCompleted
                              ? 'bg-emerald-50/45 border-emerald-100'
                              : isStepEligibleToStart
                                ? 'bg-pharma-50/40 border-pharma-100'
                                : 'bg-slate-50/70 border-slate-200'
                        }`}
                      >
                        <div className="flex items-center space-x-3">
                          <span
                            className={`w-8 h-8 rounded-full text-xs font-bold flex items-center justify-center flex-shrink-0 border tabular-nums ${
                              isStepCompleted
                                ? 'bg-emerald-100 text-emerald-800 border-emerald-200'
                                : isStepInProgress
                                  ? 'bg-amber-100 text-amber-800 border-amber-300'
                                  : isStepEligibleToStart
                                    ? 'bg-pharma-100 text-pharma-800 border-pharma-200'
                                    : 'bg-slate-100 text-slate-600 border-slate-200'
                            }`}
                          >
                            {step.stepNumber}
                          </span>
                          <div>
                            <div className="flex flex-wrap items-center gap-2">
                              <h4 className="text-sm font-bold text-slate-900">
                                {step.operationName || '—'}
                              </h4>
                              {renderStepStatusBadge(step.status)}
                              {isStepEligibleToStart && (
                                <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-semibold bg-pharma-50 text-pharma-700 border border-pharma-200">
                                  Next Eligible Step
                                </span>
                              )}
                            </div>
                          </div>
                        </div>

                        {/* Step Action / Lock Controls */}
                        <div className="flex items-center space-x-2 self-start sm:self-auto">
                          {isStepCompleted && (
                            <span className="inline-flex items-center space-x-1 px-2.5 py-1 rounded-md text-xs font-semibold text-emerald-700 bg-emerald-50 border border-emerald-200">
                              <Lock className="w-3.5 h-3.5" aria-hidden="true" />
                              <span>Completed</span>
                            </span>
                          )}

                          {isInteractive && isStepInProgress && (
                            <button
                              type="button"
                              onClick={() => setCompleteModalStep(step)}
                              disabled={anyStartBusy || isLoading || isRefreshing}
                              className="inline-flex items-center space-x-1.5 px-3.5 py-1.5 text-xs font-semibold text-white bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed rounded-lg shadow-sm transition-colors"
                            >
                              <CheckCircle2 className="w-3.5 h-3.5" aria-hidden="true" />
                              <span>Complete Step</span>
                            </button>
                          )}

                          {isInteractive && isStepPending && (
                            isStepEligibleToStart ? (
                              <button
                                type="button"
                                onClick={() => handleStartStep(step)}
                                disabled={anyStartBusy || isLoading || isRefreshing}
                                className="inline-flex items-center space-x-1.5 px-3.5 py-1.5 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 disabled:opacity-50 disabled:cursor-not-allowed rounded-lg shadow-sm transition-colors"
                              >
                                {isStepStarting ? (
                                  <>
                                    <RefreshCw className="w-3.5 h-3.5 animate-spin" aria-hidden="true" />
                                    <span>Starting...</span>
                                  </>
                                ) : (
                                  <>
                                    <Play className="w-3.5 h-3.5" aria-hidden="true" />
                                    <span>Start Step</span>
                                  </>
                                )}
                              </button>
                            ) : (
                              <button
                                type="button"
                                disabled
                                title={
                                  hasActiveStep
                                    ? 'Another step is currently in progress'
                                    : 'Complete previous sequential steps first'
                                }
                                className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-medium text-slate-600 bg-slate-100 border border-slate-200 rounded-lg cursor-not-allowed"
                              >
                                <Lock className="w-3.5 h-3.5" aria-hidden="true" />
                                <span>
                                  {hasActiveStep ? 'Locked (Step Active)' : 'Awaiting Prior Step'}
                                </span>
                              </button>
                            )
                          )}

                          {!isInteractive && !isStepCompleted && (
                            <span className="inline-flex items-center space-x-1 px-2.5 py-1 rounded-md text-xs font-medium text-slate-600 bg-slate-100 border border-slate-200">
                              <Lock className="w-3 h-3" aria-hidden="true" />
                              <span>Read-Only</span>
                            </span>
                          )}
                        </div>
                      </div>

                      {/* Step Body: Snapshotted Instructions + Recorded Execution Fields */}
                      <div className="p-4 sm:p-5 space-y-4 text-sm">
                        <div>
                          <span className="text-[11px] font-bold uppercase tracking-wider text-slate-500 block mb-1">
                            Recipe Instructions (Snapshotted)
                          </span>
                          <div className="text-xs sm:text-sm text-slate-700 bg-slate-50 border border-slate-200 rounded-lg p-3 whitespace-pre-line leading-relaxed">
                            {step.instructions || '—'}
                          </div>
                        </div>

                        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
                          <div className="bg-white border border-slate-200 rounded-lg p-3">
                            <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 block">
                              Actual Value
                            </span>
                            <span className="text-xs sm:text-sm font-mono font-semibold text-slate-900 mt-1 block break-words tabular-nums">
                              {step.actualValue || '—'}
                            </span>
                          </div>

                          <div className="bg-white border border-slate-200 rounded-lg p-3">
                            <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                              <User className="w-3 h-3" aria-hidden="true" />
                              <span>Executed By</span>
                            </span>
                            <span className="text-xs sm:text-sm font-medium text-slate-800 mt-1 block break-words">
                              {step.executedBy || '—'}
                            </span>
                          </div>

                          <div className="bg-white border border-slate-200 rounded-lg p-3">
                            <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                              <Clock className="w-3 h-3" aria-hidden="true" />
                              <span>Started At</span>
                            </span>
                            <span className="text-xs font-medium text-slate-700 mt-1 block tabular-nums">
                              {formatDateTime(step.startedAt)}
                            </span>
                          </div>

                          <div className="bg-white border border-slate-200 rounded-lg p-3">
                            <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1">
                              <Clock className="w-3 h-3" aria-hidden="true" />
                              <span>Completed At</span>
                            </span>
                            <span className="text-xs font-medium text-slate-700 mt-1 block tabular-nums">
                              {formatDateTime(step.completedAt)}
                            </span>
                          </div>
                        </div>

                        <div>
                          <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500 flex items-center space-x-1 mb-1">
                            <FileText className="w-3 h-3" aria-hidden="true" />
                            <span>Observations</span>
                          </span>
                          <div className="text-xs sm:text-sm text-slate-700 bg-slate-50/70 border border-slate-200 rounded-lg p-3 whitespace-pre-line leading-relaxed break-words">
                            {step.observations || '—'}
                          </div>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </>
      )}

      {/* Complete Step Modal */}
      {isInteractive && (
        <CompleteExecutionStepModal
          isOpen={Boolean(completeModalStep)}
          batchId={workspace?.batchId ?? batchId}
          batchNumber={workspace?.batchNumber}
          step={completeModalStep}
          onClose={() => setCompleteModalStep(null)}
          onSuccess={handleStepCompleted}
        />
      )}
    </div>
  );
}
