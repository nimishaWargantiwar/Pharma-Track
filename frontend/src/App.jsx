import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Activity,
  AlertCircle,
  Box,
  CheckCircle2,
  ClipboardCheck,
  ClipboardList,
  Clock,
  Cpu,
  FileSpreadsheet,
  FlaskConical,
  Layers,
  LogOut,
  Menu,
  PanelLeftClose,
  PanelLeftOpen,
  Pill,
  QrCode,
  RefreshCw,
  ShieldCheck,
  User,
  Users,
  Warehouse,
  X,
} from 'lucide-react';
import { useAuth } from './context/AuthContext.jsx';
import {
  TAB_ROUTES,
  NavigationProvider,
  buildModuleHash,
  parseHashRoute,
} from './context/NavigationContext.jsx';
import { checkSystemHealth } from './services/authApi.js';
import {
  canAccessTab,
  getDefaultTabForRole,
  getRoleMetadata,
} from './utils/rbacUtils.js';
import { formatAppTimeOnly } from './utils/dateTimeUtils.js';
import AccessDeniedView from './components/AccessDeniedView.jsx';
import LoginPage from './components/LoginPage.jsx';
import ProductManagement from './components/ProductManagement.jsx';
import RecipeManagement from './components/RecipeManagement.jsx';
import OrderManagement from './components/OrderManagement.jsx';
import ProductionExecution from './components/ProductionExecution.jsx';
import InventoryManagement from './components/InventoryManagement.jsx';
import QualityManagement from './components/QualityManagement.jsx';
import BatchTraceability from './components/BatchTraceability.jsx';
import UserManagement from './components/UserManagement.jsx';
import InlineAlert from './components/ui/InlineAlert.jsx';
import StatusBadge from './components/ui/StatusBadge.jsx';
import { getFocusableElements } from './components/ui/ModalDialog.jsx';
import { ToastProvider, useOptionalToast } from './components/ui/ToastProvider.jsx';

export { TAB_ROUTES };

const NAV_TABS = [
  {
    id: 'products',
    label: 'Product Master',
    mobileLabel: 'Product Master',
    icon: Box,
  },
  {
    id: 'recipes',
    label: 'Recipe Management',
    mobileLabel: 'Recipe Mgmt',
    icon: FlaskConical,
  },
  {
    id: 'orders',
    label: 'Manufacturing Orders',
    mobileLabel: 'Manufacturing Orders',
    icon: ClipboardList,
  },
  {
    id: 'execution',
    label: 'Production Execution',
    mobileLabel: 'Production Execution',
    icon: ClipboardCheck,
  },
  {
    id: 'inventory',
    label: 'Inventory & Warehouse',
    mobileLabel: 'Inventory',
    icon: Warehouse,
  },
  {
    id: 'quality',
    label: 'Quality Management',
    mobileLabel: 'Quality Mgmt',
    icon: ShieldCheck,
  },
  {
    id: 'traceability',
    label: 'Batch Traceability',
    mobileLabel: 'Traceability',
    icon: QrCode,
  },
  {
    id: 'users',
    label: 'User Management',
    mobileLabel: 'Users',
    icon: Users,
  },
  {
    id: 'system',
    label: 'System Overview',
    mobileLabel: 'System Overview',
    icon: Activity,
  },
];

export function getTabFromLocation() {
  return parseHashRoute().tab;
}

function AppShell() {
  const {
    user,
    role,
    token,
    isAuthenticated,
    isInitializing,
    isRetryingSession,
    sessionRestoreError,
    retrySessionRestore,
    forbiddenBanner,
    logout,
    dismissForbiddenBanner,
  } = useAuth();

  const [routeState, setRouteState] = useState(() => {
    const parsed = parseHashRoute();
    return {
      tab: parsed.tab,
      params: parsed.params,
      nonce: 0,
    };
  });
  const activeTab = routeState.tab;
  const [backendStatus, setBackendStatus] = useState('checking'); // 'checking' | 'connected' | 'disconnected'
  const [healthOutcome, setHealthOutcome] = useState('checking'); // 'checking' | 'healthy' | 'degraded' | 'unreachable'
  const [backendData, setBackendData] = useState(null);
  const [lastChecked, setLastChecked] = useState(null);

  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const [isMobileNavOpen, setIsMobileNavOpen] = useState(false);

  const mobileMenuBtnRef = useRef(null);
  const mobileDrawerRef = useRef(null);
  const mobileCloseBtnRef = useRef(null);
  const shouldRestoreMobileFocusRef = useRef(false);
  const mainContentRef = useRef(null);

  const closeMobileNav = useCallback(({ restoreFocus = true } = {}) => {
    shouldRestoreMobileFocusRef.current = restoreFocus;
    setIsMobileNavOpen(false);
  }, []);

  const navigateToTab = useCallback(
    (tab, params = {}) => {
      const requestedTab = TAB_ROUTES[tab] ? tab : getDefaultTabForRole(role);
      const targetTab =
        isAuthenticated && !canAccessTab(role, requestedTab)
          ? getDefaultTabForRole(role)
          : requestedTab;
      const targetHash = buildModuleHash(targetTab, params);

      if (typeof window !== 'undefined') {
        if (window.location.pathname !== '/') {
          window.history.pushState(null, '', `/${targetHash}`);
        } else if (window.location.hash !== targetHash) {
          window.location.hash = targetHash;
        }
      }

      const nextParams = new URLSearchParams();
      if (params && typeof params === 'object') {
        Object.entries(params).forEach(([key, value]) => {
          if (value !== undefined && value !== null && String(value).trim() !== '') {
            nextParams.set(key, String(value).trim());
          }
        });
      }

      setRouteState((prev) => ({
        tab: targetTab,
        params: nextParams,
        nonce: prev.nonce + 1,
      }));

      if (isMobileNavOpen) {
        closeMobileNav({ restoreFocus: false });
      }
    },
    [role, isAuthenticated, isMobileNavOpen, closeMobileNav]
  );

  useEffect(() => {
    if (typeof window === 'undefined') {
      return undefined;
    }

    const syncTabFromUrl = () => {
      const parsed = parseHashRoute();
      setRouteState((prev) => {
        const prevKey = `${prev.tab}|${prev.params?.toString?.() || ''}`;
        const nextKey = `${parsed.tab}|${parsed.params?.toString?.() || ''}`;
        if (prevKey === nextKey) {
          return prev;
        }
        return {
          tab: parsed.tab,
          params: parsed.params,
          nonce: prev.nonce + 1,
        };
      });
    };

    window.addEventListener('hashchange', syncTabFromUrl);
    window.addEventListener('popstate', syncTabFromUrl);

    return () => {
      window.removeEventListener('hashchange', syncTabFromUrl);
      window.removeEventListener('popstate', syncTabFromUrl);
    };
  }, []);

  const checkBackendHealth = async () => {
    setBackendStatus('checking');
    setHealthOutcome('checking');
    try {
      const data = await checkSystemHealth();
      setBackendData(data);
      if (data?.status === 'UP') {
        setBackendStatus('connected');
        setHealthOutcome('healthy');
      } else {
        setBackendStatus('disconnected');
        setHealthOutcome('degraded');
      }
    } catch (err) {
      if (err?.data) {
        setBackendData(err.data);
        setHealthOutcome('degraded');
      } else {
        setBackendData(null);
        setHealthOutcome('unreachable');
      }
      setBackendStatus('disconnected');
    } finally {
      setLastChecked(formatAppTimeOnly(new Date()));
    }
  };

  useEffect(() => {
    checkBackendHealth();
  }, []);

  useEffect(() => {
    if (!isMobileNavOpen || typeof document === 'undefined') {
      return undefined;
    }

    if (mobileCloseBtnRef.current && typeof mobileCloseBtnRef.current.focus === 'function') {
      mobileCloseBtnRef.current.focus();
    } else if (mobileDrawerRef.current && typeof mobileDrawerRef.current.focus === 'function') {
      mobileDrawerRef.current.focus();
    }

    const handleKeyDown = (event) => {
      const key = event.key || event.code;
      if (key === 'Escape') {
        event.preventDefault?.();
        event.stopPropagation?.();
        closeMobileNav({ restoreFocus: true });
        return;
      }

      if (key === 'Tab' && mobileDrawerRef.current) {
        const focusables = getFocusableElements(mobileDrawerRef.current);
        if (focusables.length === 0) {
          event.preventDefault?.();
          mobileDrawerRef.current.focus?.();
          return;
        }
        const firstEl = focusables[0];
        const lastEl = focusables[focusables.length - 1];
        const current = document.activeElement;
        const isInside = mobileDrawerRef.current.contains?.(current);

        if (event.shiftKey) {
          if (!isInside || current === firstEl || current === mobileDrawerRef.current) {
            event.preventDefault?.();
            lastEl.focus?.();
          }
        } else if (!isInside || current === lastEl) {
          event.preventDefault?.();
          firstEl.focus?.();
        }
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      if (
        shouldRestoreMobileFocusRef.current &&
        mobileMenuBtnRef.current &&
        typeof mobileMenuBtnRef.current.focus === 'function'
      ) {
        shouldRestoreMobileFocusRef.current = false;
        mobileMenuBtnRef.current.focus();
      }
    };
  }, [isMobileNavOpen, closeMobileNav]);

  if (isInitializing) {
    return (
      <div className="min-h-screen bg-slate-50 flex flex-col items-center justify-center px-4">
        <div className="w-12 h-12 rounded-xl bg-pharma-900 flex items-center justify-center text-white shadow-sm mb-4">
          <Pill className="w-6 h-6" />
        </div>
        <div className="flex items-center space-x-2 text-sm font-medium text-slate-700">
          <RefreshCw className="w-4 h-4 animate-spin text-pharma-600" />
          <span>Verifying PharmaTrack session...</span>
        </div>
      </div>
    );
  }

  if (!isAuthenticated && token && sessionRestoreError) {
    return (
      <div className="min-h-screen bg-slate-50 flex flex-col items-center justify-center px-4 py-10">
        <div className="w-full max-w-md bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
          <div className="px-6 pt-7 pb-5 border-b border-slate-100 bg-gradient-to-b from-amber-50/70 to-white">
            <div className="flex items-center space-x-3 mb-2">
              <div className="w-10 h-10 rounded-xl bg-amber-100 text-amber-700 flex items-center justify-center border border-amber-200">
                <AlertCircle className="w-5 h-5" />
              </div>
              <div>
                <h1 className="text-lg font-bold text-slate-900 tracking-tight">
                  Session Verification Interrupted
                </h1>
                <p className="text-xs font-medium text-amber-800">
                  PharmaTrack MES &amp; eBR &bull; Token Preserved
                </p>
              </div>
            </div>
            <p className="text-sm text-slate-600">
              Your existing sign-in session is retained, but PharmaTrack could not reach the authentication service to verify it.
            </p>
          </div>

          <div className="p-6 space-y-4">
            <InlineAlert
              variant="warning"
              compact
              role="alert"
              message={sessionRestoreError}
            />

            <div className="flex flex-col sm:flex-row gap-2.5">
              <button
                type="button"
                disabled={Boolean(isRetryingSession)}
                onClick={() => {
                  if (retrySessionRestore) {
                    retrySessionRestore();
                  }
                  checkBackendHealth();
                }}
                className="flex-1 inline-flex items-center justify-center space-x-2 px-4 py-2.5 rounded-lg bg-pharma-600 hover:bg-pharma-700 text-white text-sm font-semibold shadow-sm transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
              >
                <RefreshCw className={`w-4 h-4 ${isRetryingSession ? 'animate-spin' : ''}`} />
                <span>{isRetryingSession ? 'Retrying...' : 'Retry Verification'}</span>
              </button>

              <button
                type="button"
                disabled={Boolean(isRetryingSession)}
                onClick={() => logout()}
                className="flex-1 inline-flex items-center justify-center space-x-2 px-4 py-2.5 rounded-lg border border-slate-300 bg-white hover:bg-slate-50 text-slate-700 text-sm font-semibold transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
              >
                <LogOut className="w-4 h-4" />
                <span>Sign Out</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <LoginPage
        backendStatus={backendStatus}
        healthOutcome={healthOutcome}
        onRetryHealth={checkBackendHealth}
      />
    );
  }

  const visibleTabs = NAV_TABS.filter((tab) => canAccessTab(role, tab.id));
  const isCurrentTabAllowed = canAccessTab(role, activeTab);
  const roleMeta = getRoleMetadata(role);
  const defaultTab = getDefaultTabForRole(role);
  const activeTabMeta = NAV_TABS.find((t) => t.id === activeTab);

  return (
    <NavigationProvider
      value={{
        activeTab,
        routeParams: routeState.params,
        routeNonce: routeState.nonce,
        navigateToTab,
      }}
    >
      <div className="min-h-screen bg-slate-50 flex flex-col">
      <a
        href="#main-content"
        onClick={(e) => {
          e.preventDefault();
          mainContentRef.current?.focus?.();
          mainContentRef.current?.scrollIntoView?.();
        }}
        className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-[70] focus:px-3.5 focus:py-2 focus:rounded-lg focus:bg-pharma-900 focus:text-white focus:text-xs focus:font-semibold focus:shadow-lg focus:ring-2 focus:ring-pharma-400"
      >
        Skip to main content
      </a>
      {/* Compact Top Bar */}
      <header className="bg-pharma-900 border-b border-pharma-800 text-white sticky top-0 z-30 shadow-sm">
        <div className="w-full px-2.5 sm:px-6 h-14 flex items-center justify-between gap-1.5 sm:gap-3">
          {/* Left: Mobile Drawer Trigger, Application Identity & Active Module Context */}
          <div className="flex items-center space-x-1.5 sm:space-x-3 min-w-0">
            <button
              type="button"
              ref={mobileMenuBtnRef}
              onClick={() => setIsMobileNavOpen(true)}
              aria-label="Open navigation menu"
              aria-expanded={isMobileNavOpen}
              aria-controls="mobile-navigation-drawer"
              data-testid="mobile-menu-toggle"
              className="md:hidden inline-flex items-center justify-center p-1.5 sm:p-2 rounded-lg text-slate-200 hover:text-white hover:bg-pharma-800 focus:outline-none focus:ring-2 focus:ring-pharma-400 transition-colors shrink-0"
            >
              <Menu className="w-5 h-5" aria-hidden="true" />
            </button>

            <button
              type="button"
              onClick={() => navigateToTab(defaultTab)}
              className="flex items-center space-x-2 sm:space-x-2.5 cursor-pointer shrink-0 text-left rounded-lg pr-1 focus:outline-none focus:ring-2 focus:ring-pharma-400"
            >
              <div className="w-8 h-8 rounded-lg bg-pharma-600 border border-pharma-500/40 flex items-center justify-center text-white shadow-sm shrink-0">
                <Pill className="w-4 h-4" aria-hidden="true" />
              </div>
              <div className="flex items-center">
                <span className="text-sm sm:text-base font-bold tracking-tight text-white">PharmaTrack</span>
                <span className="hidden sm:inline-block ml-2 px-2 py-0.5 text-[11px] font-semibold bg-pharma-800 text-sky-200 rounded-full border border-pharma-700">
                  MES &amp; eBR
                </span>
              </div>
            </button>

            <div className="hidden lg:flex items-center space-x-2 pl-3 border-l border-pharma-800 min-w-0">
              <span className="text-xs text-slate-300 shrink-0">Module:</span>
              <span
                data-testid="active-module-indicator"
                className="text-xs font-semibold text-white truncate"
              >
                {isCurrentTabAllowed ? activeTabMeta?.label || 'Workspace' : 'Access Restricted'}
              </span>
            </div>
          </div>

          {/* Right User Context, Health & Sign Out */}
          <div className="flex items-center space-x-1.5 sm:space-x-2.5 shrink-0">
            {/* Compact Health Indicator */}
            <div
              className="hidden sm:flex items-center space-x-1.5 text-xs bg-[#091b2e]/70 py-1 px-2.5 rounded-full border border-pharma-800"
              title={
                healthOutcome === 'healthy'
                  ? 'Backend API & database connectivity checks passing'
                  : healthOutcome === 'degraded'
                    ? 'Backend API reachable; database health check failed'
                    : healthOutcome === 'checking'
                      ? 'Checking service health...'
                      : 'Backend API unreachable'
              }
            >
              {healthOutcome === 'healthy' && (
                <>
                  <span className="relative flex h-2 w-2">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                    <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-400"></span>
                  </span>
                  <span className="hidden xl:inline text-emerald-300 font-medium">API Online</span>
                </>
              )}
              {healthOutcome === 'degraded' && (
                <>
                  <span className="h-2 w-2 rounded-full bg-amber-400"></span>
                  <span className="hidden xl:inline text-amber-300 font-medium">API Degraded</span>
                </>
              )}
              {healthOutcome === 'checking' && (
                <>
                  <RefreshCw className="w-3 h-3 animate-spin text-sky-300" aria-hidden="true" />
                  <span className="hidden xl:inline text-slate-300">Checking...</span>
                </>
              )}
              {healthOutcome === 'unreachable' && (
                <>
                  <span className="h-2 w-2 rounded-full bg-rose-400"></span>
                  <span className="hidden xl:inline text-rose-300 font-medium">API Offline</span>
                </>
              )}
              <button
                type="button"
                onClick={checkBackendHealth}
                title="Retry backend health check"
                aria-label="Retry backend health check"
                className="p-0.5 text-slate-300 hover:text-white rounded transition-colors"
              >
                <RefreshCw className="w-3 h-3" aria-hidden="true" />
              </button>
            </div>

            {/* Authenticated User Chip */}
            <div className="flex items-center space-x-1.5 sm:space-x-2 bg-[#091b2e]/60 border border-pharma-800 rounded-lg px-1.5 sm:px-2.5 py-1">
              <div className="w-6 h-6 rounded-full bg-pharma-700 text-sky-100 flex items-center justify-center shrink-0">
                <User className="w-3.5 h-3.5" aria-hidden="true" />
              </div>
              <div className="hidden sm:block text-left leading-tight">
                <div className="text-xs font-semibold text-white truncate max-w-[120px]">
                  {user?.fullName || user?.username}
                </div>
                <div className="text-[11px] text-slate-300 truncate max-w-[120px]">
                  @{user?.username}
                </div>
              </div>
              <span
                className={`inline-flex items-center px-1.5 sm:px-2 py-0.5 rounded-full text-[11px] font-semibold border whitespace-nowrap ${roleMeta.badgeClass}`}
              >
                {roleMeta.shortLabel}
              </span>
            </div>

            {/* Logout Button */}
            <button
              type="button"
              onClick={() => logout()}
              aria-label="Sign Out"
              className="inline-flex items-center space-x-1.5 px-2 sm:px-2.5 py-1.5 rounded-lg border border-pharma-700 bg-pharma-800/80 hover:bg-pharma-700 text-slate-100 text-xs font-medium transition-colors"
            >
              <LogOut className="w-3.5 h-3.5" aria-hidden="true" />
              <span className="hidden sm:inline">Sign Out</span>
            </button>
          </div>
        </div>
      </header>

      {/* Shell Body: Responsive Sidebar + Main Content Column */}
      <div className="flex-1 flex min-h-0">
        {/* Desktop Collapsible Sidebar (240px expanded / 64px collapsed) & Tablet Navigation Rail (64px) */}
        <aside
          aria-label="Workspace Navigation"
          data-testid="desktop-sidebar"
          data-collapsed={isSidebarCollapsed ? 'true' : 'false'}
          className={`hidden md:flex md:flex-col shrink-0 bg-[#091b2e] text-slate-200 border-r border-pharma-800 sticky top-14 h-[calc(100vh-3.5rem)] transition-all duration-200 ${
            isSidebarCollapsed ? 'w-16' : 'w-16 lg:w-60'
          }`}
        >
          <div
            className={`h-11 px-2.5 border-b border-pharma-800/80 hidden lg:flex items-center ${
              isSidebarCollapsed ? 'justify-center' : 'justify-between'
            }`}
          >
            {!isSidebarCollapsed && (
              <span className="text-[11px] font-bold uppercase tracking-wider text-slate-300 px-1.5">
                Modules
              </span>
            )}
            <button
              type="button"
              onClick={() => setIsSidebarCollapsed((prev) => !prev)}
              aria-label={isSidebarCollapsed ? 'Expand sidebar navigation' : 'Collapse sidebar navigation'}
              aria-expanded={!isSidebarCollapsed}
              data-testid="sidebar-collapse-toggle"
              title={isSidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
              className="p-1.5 rounded-lg text-slate-300 hover:text-white hover:bg-pharma-900 focus:outline-none focus:ring-2 focus:ring-pharma-400 transition-colors"
            >
              {isSidebarCollapsed ? (
                <PanelLeftOpen className="w-4 h-4" aria-hidden="true" />
              ) : (
                <PanelLeftClose className="w-4 h-4" aria-hidden="true" />
              )}
            </button>
          </div>

          <nav
            aria-label="Primary Module Navigation"
            className="flex-1 px-2 py-3 space-y-1 overflow-y-auto"
          >
            {visibleTabs.map((tab) => {
              const Icon = tab.icon;
              const isActive = activeTab === tab.id;
              return (
                <button
                  key={tab.id}
                  type="button"
                  onClick={() => navigateToTab(tab.id)}
                  aria-current={isActive ? 'page' : undefined}
                  aria-label={tab.label}
                  title={tab.label}
                  className={`w-full flex items-center gap-2.5 py-2.5 rounded-lg text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-pharma-400 ${
                    isSidebarCollapsed
                      ? 'justify-center px-2'
                      : 'justify-center lg:justify-start px-2 lg:px-3'
                  } ${
                    isActive
                      ? 'bg-pharma-600 text-white font-semibold shadow-sm border border-pharma-500/40'
                      : 'text-slate-200 hover:text-white hover:bg-pharma-900/70 border border-transparent'
                  }`}
                >
                  <Icon className="w-4 h-4 shrink-0" aria-hidden="true" />
                  <span
                    className={
                      isSidebarCollapsed
                        ? 'sr-only'
                        : 'md:sr-only lg:not-sr-only lg:inline truncate'
                    }
                  >
                    {tab.label}
                  </span>
                </button>
              );
            })}
          </nav>

          {!isSidebarCollapsed && (
            <div className="hidden lg:block px-3.5 py-3 border-t border-pharma-800/80 text-xs text-slate-300">
              <div className="text-[11px] uppercase tracking-wider text-slate-300 font-semibold">
                Assigned Role
              </div>
              <div className="mt-1 font-medium text-white truncate">{roleMeta.label}</div>
            </div>
          )}
        </aside>

        {/* Mobile Accessible Slide-Out Navigation Drawer */}
        {isMobileNavOpen && (
          <div className="md:hidden fixed inset-0 z-40 flex">
            <div
              aria-hidden="true"
              onClick={() => closeMobileNav({ restoreFocus: true })}
              className="fixed inset-0 bg-slate-900/60 backdrop-blur-sm animate-backdrop-enter"
            />
            <div
              ref={mobileDrawerRef}
              id="mobile-navigation-drawer"
              role="dialog"
              aria-modal="true"
              aria-label="Mobile Navigation"
              tabIndex={-1}
              className="relative z-10 w-72 max-w-[85vw] h-full bg-[#091b2e] text-white border-r border-pharma-800 shadow-2xl flex flex-col animate-modal-enter focus:outline-none"
            >
              <div className="h-14 px-4 border-b border-pharma-800 flex items-center justify-between">
                <div className="flex items-center space-x-2.5">
                  <div className="w-8 h-8 rounded-lg bg-pharma-600 border border-pharma-500/40 flex items-center justify-center text-white">
                    <Pill className="w-4 h-4" aria-hidden="true" />
                  </div>
                  <div>
                    <div className="text-sm font-bold tracking-tight text-white">PharmaTrack</div>
                    <div className="text-[11px] text-sky-200">MES &amp; eBR Navigation</div>
                  </div>
                </div>
                <button
                  type="button"
                  ref={mobileCloseBtnRef}
                  onClick={() => closeMobileNav({ restoreFocus: true })}
                  aria-label="Close navigation menu"
                  className="p-1.5 rounded-lg text-slate-300 hover:text-white hover:bg-pharma-800 focus:outline-none focus:ring-2 focus:ring-pharma-400 transition-colors"
                >
                  <X className="w-5 h-5" aria-hidden="true" />
                </button>
              </div>

              <nav
                aria-label="Mobile Module Navigation"
                className="flex-1 px-3 py-4 space-y-1 overflow-y-auto"
              >
                {visibleTabs.map((tab) => {
                  const Icon = tab.icon;
                  const isActive = activeTab === tab.id;
                  return (
                    <button
                      key={tab.id}
                      type="button"
                      onClick={() => navigateToTab(tab.id)}
                      aria-current={isActive ? 'page' : undefined}
                      className={`w-full flex items-center space-x-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-pharma-400 ${
                        isActive
                          ? 'bg-pharma-600 text-white font-semibold shadow-sm border border-pharma-500/40'
                          : 'text-slate-300 hover:text-white hover:bg-pharma-900/70 border border-transparent'
                      }`}
                    >
                      <Icon className="w-4 h-4 shrink-0" aria-hidden="true" />
                      <span className="truncate">{tab.label}</span>
                    </button>
                  );
                })}
              </nav>

              <div className="px-4 py-3 border-t border-pharma-800 space-y-2.5 bg-pharma-900/40">
                <div className="flex items-center justify-between gap-2">
                  <div className="min-w-0">
                    <div className="text-xs font-semibold text-white truncate">
                      {user?.fullName || user?.username}
                    </div>
                    <div className="text-[11px] text-slate-300 truncate">@{user?.username}</div>
                  </div>
                  <span
                    className={`inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold border whitespace-nowrap ${roleMeta.badgeClass}`}
                  >
                    {roleMeta.shortLabel}
                  </span>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    closeMobileNav({ restoreFocus: false });
                    logout();
                  }}
                  className="w-full inline-flex items-center justify-center space-x-1.5 px-3 py-2 rounded-lg border border-pharma-700 bg-pharma-800 hover:bg-pharma-700 text-xs font-medium text-white transition-colors"
                >
                  <LogOut className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>Sign Out</span>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Workspace Column */}
        <div className="flex-1 min-w-0 flex flex-col justify-between">
          {/* Main Content Area */}
          <main
            ref={mainContentRef}
            id="main-content"
            tabIndex={-1}
            className="flex-1 min-w-0 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-6 lg:py-8 focus:outline-none"
          >
            {forbiddenBanner && (
              <InlineAlert
                variant="warning"
                role="alert"
                message={forbiddenBanner}
                onDismiss={dismissForbiddenBanner}
                dismissLabel="Dismiss permission notice"
                className="mb-6"
              />
            )}

            {!isCurrentTabAllowed && (
              <AccessDeniedView
                role={role}
                moduleName={
                  NAV_TABS.find((t) => t.id === activeTab)?.label || 'Requested Module'
                }
                description={
                  activeTab === 'users'
                    ? 'Administrator privileges are required to view user accounts, assign roles, reset temporary passwords, or manage access. Choose an authorized module from navigation or return below.'
                    : activeTab === 'recipes'
                      ? 'Master Recipe records and formulation instructions are restricted for your current role. Choose an authorized module from navigation or return below.'
                      : 'Your current role does not grant access to this module. Choose an authorized module from navigation or return below.'
                }
                onReturnHome={() => navigateToTab(defaultTab)}
              />
            )}

            {isCurrentTabAllowed && activeTab === 'products' && <ProductManagement />}
            {isCurrentTabAllowed && activeTab === 'recipes' && <RecipeManagement />}
            {isCurrentTabAllowed && activeTab === 'orders' && <OrderManagement />}
            {isCurrentTabAllowed && activeTab === 'execution' && <ProductionExecution />}
            {isCurrentTabAllowed && activeTab === 'inventory' && <InventoryManagement />}
            {isCurrentTabAllowed && activeTab === 'quality' && <QualityManagement />}
            {isCurrentTabAllowed && activeTab === 'traceability' && <BatchTraceability />}
            {isCurrentTabAllowed && activeTab === 'users' && <UserManagement />}
            {isCurrentTabAllowed && activeTab === 'system' && (
              <div>
                {/* Hero / System Overview Section */}
                <div className="text-center max-w-3xl mx-auto mb-8 sm:mb-12">
                  <div className="inline-flex items-center space-x-2 px-3 py-1 mb-4 rounded-full bg-pharma-100 text-pharma-800 text-xs font-semibold tracking-wide uppercase">
                    <ShieldCheck className="w-4 h-4" aria-hidden="true" />
                    <span>Quality &amp; Batch Traceability Architecture</span>
                  </div>
                  <h1 className="text-3xl sm:text-5xl font-extrabold text-slate-900 tracking-tight mb-4">
                    PharmaTrack
                  </h1>
                  <p className="text-lg sm:text-2xl text-slate-600 font-medium">
                    Pharmaceutical Manufacturing Execution &amp; Electronic Batch Record System
                  </p>
                  <p className="mt-4 text-base text-slate-600 leading-relaxed">
                    A comprehensive manufacturing platform designed for pharmaceutical formulation,
                    batch production tracking, line clearance, in-process quality assurance, and append-only audit trails.
                  </p>
                </div>

                {/* System Status Dashboard Card */}
                <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-4 sm:p-6 mb-8 sm:mb-12">
                  <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between pb-4 border-b border-slate-100 mb-6 gap-3">
                    <div>
                      <h2 className="text-lg font-semibold text-slate-900 flex items-center space-x-2">
                        <Activity className="w-5 h-5 text-pharma-600 shrink-0" aria-hidden="true" />
                        <span>System Initialization &amp; Health Status</span>
                      </h2>
                      <p className="text-sm text-slate-500">Verified services and communication status</p>
                    </div>
                    <div className="flex flex-wrap items-center gap-3">
                      {lastChecked && (
                        <div className="flex items-center text-xs text-slate-500 space-x-1 tabular-nums">
                          <Clock className="w-3.5 h-3.5 text-slate-500 shrink-0" aria-hidden="true" />
                          <span>Last polled at {lastChecked} IST (Asia/Kolkata)</span>
                        </div>
                      )}
                      <button
                        type="button"
                        onClick={checkBackendHealth}
                        disabled={healthOutcome === 'checking'}
                        className="inline-flex items-center space-x-1.5 px-2.5 py-1.5 rounded-lg border border-slate-200 bg-white hover:bg-slate-50 text-slate-700 text-xs font-medium shadow-sm transition-colors disabled:opacity-60"
                      >
                        <RefreshCw
                          className={`w-3.5 h-3.5 ${healthOutcome === 'checking' ? 'animate-spin text-pharma-600' : ''}`}
                          aria-hidden="true"
                        />
                        <span>Refresh</span>
                      </button>
                    </div>
                  </div>

                  <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                    {/* Frontend Service Card */}
                    <div className="bg-slate-50 border border-slate-200 rounded-lg p-4">
                      <div className="flex items-center justify-between gap-2 mb-2">
                        <span className="text-xs font-bold uppercase tracking-wider text-slate-500">Frontend Client</span>
                        <StatusBadge
                          status="UP"
                          tone="success"
                          label="Active"
                          variant="tag"
                          icon={CheckCircle2}
                        />
                      </div>
                      <div className="text-sm font-semibold text-slate-800">React 18 + Vite + Tailwind CSS</div>
                      <div className="text-xs text-slate-500 mt-1">
                        Timezone Contract: <span className="font-mono text-slate-700">IST (Asia/Kolkata)</span>
                      </div>
                    </div>

                    {/* Backend Service Card */}
                    <div className="bg-slate-50 border border-slate-200 rounded-lg p-4">
                      <div className="flex items-center justify-between gap-2 mb-2">
                        <span className="text-xs font-bold uppercase tracking-wider text-slate-500">Backend API</span>
                        {healthOutcome === 'healthy' && (
                          <StatusBadge
                            status="UP"
                            tone="success"
                            label="Operational"
                            variant="tag"
                            icon={CheckCircle2}
                          />
                        )}
                        {healthOutcome === 'degraded' && (
                          <StatusBadge
                            status="DEGRADED"
                            tone="warning"
                            label="Degraded"
                            variant="tag"
                            icon={AlertCircle}
                          />
                        )}
                        {healthOutcome === 'checking' && (
                          <StatusBadge
                            status="CHECKING"
                            tone="muted"
                            label="Checking..."
                            variant="tag"
                            icon={<RefreshCw className="w-3.5 h-3.5 mr-1 shrink-0 animate-spin text-slate-600" aria-hidden="true" />}
                          />
                        )}
                        {healthOutcome === 'unreachable' && (
                          <StatusBadge
                            status="DOWN"
                            tone="danger"
                            label="Unreachable"
                            variant="tag"
                            icon={AlertCircle}
                          />
                        )}
                      </div>
                      <div className="text-sm font-semibold text-slate-800">Java 17 + Spring Boot 3.3.5</div>
                      <div className="text-xs text-slate-500 mt-1">
                        Endpoint: <span className="font-mono text-slate-700">GET /api/health</span>
                      </div>
                      {backendData?.message ? (
                        <div className="mt-2 text-xs bg-white p-2 rounded border border-slate-200 font-mono text-slate-600 break-words">
                          {backendData.message}
                        </div>
                      ) : healthOutcome === 'unreachable' ? (
                        <div className="mt-2 text-xs bg-white p-2 rounded border border-rose-200 text-rose-700">
                          Health endpoint did not respond. Verify backend service availability.
                        </div>
                      ) : null}
                    </div>

                    {/* Database Service Card */}
                    <div className="bg-slate-50 border border-slate-200 rounded-lg p-4">
                      <div className="flex items-center justify-between gap-2 mb-2">
                        <span className="text-xs font-bold uppercase tracking-wider text-slate-500">Database Layer</span>
                        {healthOutcome === 'healthy' && (
                          <StatusBadge
                            status="UP"
                            tone="success"
                            label="Operational"
                            variant="tag"
                            icon={CheckCircle2}
                          />
                        )}
                        {healthOutcome === 'degraded' && (
                          <StatusBadge
                            status="DOWN"
                            tone="danger"
                            label="Unavailable"
                            variant="tag"
                            icon={AlertCircle}
                          />
                        )}
                        {healthOutcome === 'checking' && (
                          <StatusBadge
                            status="CHECKING"
                            tone="muted"
                            label="Checking..."
                            variant="tag"
                            icon={<RefreshCw className="w-3.5 h-3.5 mr-1 shrink-0 animate-spin text-slate-600" aria-hidden="true" />}
                          />
                        )}
                        {healthOutcome === 'unreachable' && (
                          <StatusBadge
                            status="UNVERIFIED"
                            tone="muted"
                            label="Unverified"
                            variant="tag"
                            icon={AlertCircle}
                          />
                        )}
                      </div>
                      <div className="text-sm font-semibold text-slate-800">MySQL 8.0 + Spring Data JPA</div>
                      <div className="text-xs text-slate-500 mt-1">
                        {healthOutcome === 'healthy' && 'Connectivity verified via backend health probe'}
                        {healthOutcome === 'degraded' && 'Database connectivity check reported failure'}
                        {healthOutcome === 'checking' && 'Awaiting backend health probe...'}
                        {healthOutcome === 'unreachable' && 'Cannot verify while Backend API is unreachable'}
                      </div>
                    </div>
                  </div>
                </div>

                {/* System Highlights */}
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                  <div className="bg-white p-5 sm:p-6 rounded-xl border border-slate-200 shadow-sm hover:border-pharma-300 transition-colors">
                    <div className="w-12 h-12 rounded-lg bg-pharma-100 text-pharma-700 flex items-center justify-center mb-4">
                      <FileSpreadsheet className="w-6 h-6" aria-hidden="true" />
                    </div>
                    <h3 className="text-base font-semibold text-slate-900 mb-2">Electronic Batch Record (eBR)</h3>
                    <p className="text-sm text-slate-600">
                      Digital recipe execution, step-by-step verification, operator sign-offs, and batch genealogy tracking.
                    </p>
                  </div>

                  <div className="bg-white p-5 sm:p-6 rounded-xl border border-slate-200 shadow-sm hover:border-pharma-300 transition-colors">
                    <div className="w-12 h-12 rounded-lg bg-indigo-100 text-indigo-700 flex items-center justify-center mb-4">
                      <Layers className="w-6 h-6" aria-hidden="true" />
                    </div>
                    <h3 className="text-base font-semibold text-slate-900 mb-2">Manufacturing Execution (MES)</h3>
                    <p className="text-sm text-slate-600">
                      Shop floor dispatching, equipment status monitoring, cleanroom line clearance, and shift management.
                    </p>
                  </div>

                  <div className="bg-white p-5 sm:p-6 rounded-xl border border-slate-200 shadow-sm hover:border-pharma-300 transition-colors">
                    <div className="w-12 h-12 rounded-lg bg-teal-100 text-teal-700 flex items-center justify-center mb-4">
                      <ShieldCheck className="w-6 h-6" aria-hidden="true" />
                    </div>
                    <h3 className="text-base font-semibold text-slate-900 mb-2">Quality &amp; Deviations</h3>
                    <p className="text-sm text-slate-600">
                      Append-only audit logs, deviation &amp; CAPA workflows, QC testing, and QA batch disposition controls.
                    </p>
                  </div>

                  <div className="bg-white p-5 sm:p-6 rounded-xl border border-slate-200 shadow-sm hover:border-pharma-300 transition-colors">
                    <div className="w-12 h-12 rounded-lg bg-amber-100 text-amber-700 flex items-center justify-center mb-4">
                      <Cpu className="w-6 h-6" aria-hidden="true" />
                    </div>
                    <h3 className="text-base font-semibold text-slate-900 mb-2">REST Architecture</h3>
                    <p className="text-sm text-slate-600">
                      Modern decoupled React UI communicating with secure Spring Boot REST APIs with validation and JPA ORM.
                    </p>
                  </div>
                </div>
              </div>
            )}
          </main>

          {/* Footer */}
          <footer className="bg-white border-t border-slate-200 py-6 mt-12">
            <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex flex-col sm:flex-row items-center justify-between text-xs text-slate-500 gap-2 text-center sm:text-left">
              <div className="flex flex-col sm:flex-row items-center justify-center sm:justify-start gap-1 sm:gap-2">
                <span className="font-semibold text-slate-700">PharmaTrack MES</span>
                <span className="hidden sm:inline" aria-hidden="true">&bull;</span>
                <span>Modules: Product Master, Recipes, Orders, Execution, Inventory, Quality, Deviations &amp; CAPA</span>
              </div>
              <div>
                React 18 &bull; Vite &bull; Tailwind CSS &bull; Spring Boot 3 &bull; Java 17 &bull; MySQL
              </div>
            </div>
          </footer>
        </div>
      </div>
      </div>
    </NavigationProvider>
  );
}

export default function App() {
  const existingToast = useOptionalToast();
  if (existingToast) {
    return <AppShell />;
  }
  return (
    <ToastProvider>
      <AppShell />
    </ToastProvider>
  );
}
