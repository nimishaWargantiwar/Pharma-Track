import React, { useState, useEffect, useMemo, useCallback, useId, useRef } from 'react';
import {
  Plus,
  Search,
  RefreshCw,
  FlaskConical,
  CheckCircle2,
  Clock,
  Archive,
} from 'lucide-react';
import RecipeTable from './RecipeTable';
import RecipeModal from './RecipeModal';
import RecipeDetailDrawer from './RecipeDetailDrawer';
import ConfirmModal from './ConfirmModal';
import NotificationBanner from './NotificationBanner';
import AccessDeniedView from './AccessDeniedView.jsx';
import {
  DEFAULT_PAGE_SIZE,
  InlineAlert,
  PageHeader,
  paginateItems,
} from './ui/index.js';
import {
  getAllRecipes,
  createRecipe,
  updateRecipe,
  updateRecipeStatus,
  createNewVersion,
  deleteRecipe,
} from '../services/recipeApi';
import { useAuth } from '../context/AuthContext.jsx';
import { useNavigation } from '../context/NavigationContext.jsx';
import {
  canApproveRecipes,
  canAuthorRecipes,
  canViewRecipes,
} from '../utils/rbacUtils.js';

export default function RecipeManagement() {
  const { role } = useAuth();
  const { routeParams, routeNonce } = useNavigation();
  const canView = canViewRecipes(role);
  const canAuthor = canAuthorRecipes(role);
  const canApprove = canApproveRecipes(role);
  const searchInputId = useId();
  const consumedRecipeLinkRef = useRef('');

  const [recipes, setRecipes] = useState([]);
  const [isLoading, setIsLoading] = useState(canView);
  const [loadError, setLoadError] = useState(null);
  const [actionError, setActionError] = useState(null);
  const [notification, setNotification] = useState(null);

  // Search, Filter, Sort & Pagination State
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL'); // 'ALL' | 'DRAFT' | 'APPROVED' | 'OBSOLETE'
  const [sortState, setSortState] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  // Modals & Detail Drawer
  const [isAddEditModalOpen, setIsAddEditModalOpen] = useState(false);
  const [selectedRecipe, setSelectedRecipe] = useState(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const [isDetailsDrawerOpen, setIsDetailsDrawerOpen] = useState(false);
  const [detailsRecipeId, setDetailsRecipeId] = useState(null);
  const [detailsFallbackRecipe, setDetailsFallbackRecipe] = useState(null);

  const [confirmConfig, setConfirmConfig] = useState(null);
  const [isConfirming, setIsConfirming] = useState(false);
  const [actionLoadingId, setActionLoadingId] = useState(null);

  const loadRecipes = useCallback(async () => {
    if (!canView) {
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    setLoadError(null);
    try {
      const data = await getAllRecipes();
      setRecipes(Array.isArray(data) ? data : []);
    } catch (err) {
      setLoadError(err.message || 'Unable to connect to recipe catalog API');
      setNotification({
        type: 'error',
        message: err.message || 'Failed to load master recipes from server',
      });
    } finally {
      setIsLoading(false);
    }
  }, [canView]);

  useEffect(() => {
    if (!canView) {
      setIsLoading(false);
      return;
    }
    loadRecipes();
  }, [canView, loadRecipes]);

  const requestedRecipeId = routeParams?.get?.('recipeId');
  const requestedRecipeCode = routeParams?.get?.('recipeCode');

  useEffect(() => {
    if (!canView || (!requestedRecipeId && !requestedRecipeCode) || recipes.length === 0) {
      return;
    }
    const linkKey = `${routeNonce}|${requestedRecipeId || ''}|${requestedRecipeCode || ''}`;
    if (consumedRecipeLinkRef.current === linkKey) {
      return;
    }
    const match = requestedRecipeId
      ? recipes.find((r) => String(r.id) === String(requestedRecipeId))
      : recipes.find(
          (r) => String(r.recipeCode || '').toLowerCase() === String(requestedRecipeCode).toLowerCase()
        );
    if (match) {
      consumedRecipeLinkRef.current = linkKey;
      setDetailsRecipeId(match.id ?? null);
      setDetailsFallbackRecipe(match);
      setIsDetailsDrawerOpen(true);
    }
  }, [canView, requestedRecipeId, requestedRecipeCode, routeNonce, recipes]);

  // Filter across full dataset BEFORE pagination
  const filteredRecipes = useMemo(() => {
    const query = searchTerm.trim().toLowerCase();
    return recipes.filter((recipe) => {
      if (statusFilter !== 'ALL' && recipe.status !== statusFilter) {
        return false;
      }
      if (query) {
        const codeMatch = recipe.recipeCode?.toLowerCase().includes(query);
        const nameMatch = recipe.productName?.toLowerCase().includes(query);
        const prodCodeMatch = recipe.productCode?.toLowerCase().includes(query);
        return Boolean(codeMatch || nameMatch || prodCodeMatch);
      }
      return true;
    });
  }, [recipes, statusFilter, searchTerm]);

  // Sort across filtered dataset BEFORE pagination
  const sortedRecipes = useMemo(() => {
    if (!sortState?.key) {
      return filteredRecipes;
    }
    const dir = sortState.direction === 'desc' ? -1 : 1;
    return [...filteredRecipes].sort((a, b) => {
      let valA;
      let valB;
      if (sortState.key === 'updatedAt') {
        valA = a?.updatedAt || a?.createdAt || '';
        valB = b?.updatedAt || b?.createdAt || '';
      } else if (sortState.key === 'batchSize') {
        valA = Number(a?.batchSize) || 0;
        valB = Number(b?.batchSize) || 0;
      } else if (sortState.key === 'structure') {
        valA = (a?.materials?.length || 0) + (a?.steps?.length || 0);
        valB = (b?.materials?.length || 0) + (b?.steps?.length || 0);
      } else {
        valA = a?.[sortState.key];
        valB = b?.[sortState.key];
      }

      if (valA == null && valB == null) return 0;
      if (valA == null) return -1 * dir;
      if (valB == null) return 1 * dir;
      if (typeof valA === 'number' && typeof valB === 'number') {
        return (valA - valB) * dir;
      }
      return String(valA).localeCompare(String(valB), undefined, { numeric: true }) * dir;
    });
  }, [filteredRecipes, sortState]);

  const paginatedResult = useMemo(
    () => paginateItems(sortedRecipes, currentPage, pageSize),
    [sortedRecipes, currentPage, pageSize]
  );

  useEffect(() => {
    if (paginatedResult.page !== currentPage) {
      setCurrentPage(paginatedResult.page);
    }
  }, [paginatedResult.page, currentPage]);

  const hasActiveFilters = Boolean(searchTerm.trim() || statusFilter !== 'ALL');

  const handleSearchChange = (nextVal) => {
    setSearchTerm(nextVal);
    setCurrentPage(1);
  };

  const handleStatusFilterChange = (nextStatus) => {
    setStatusFilter(nextStatus);
    setCurrentPage(1);
  };

  const handleSortChange = (nextSort) => {
    setSortState(nextSort);
    setCurrentPage(1);
  };

  const handleResetFilters = () => {
    setSearchTerm('');
    setStatusFilter('ALL');
    setCurrentPage(1);
  };

  // Statistics
  const totalCount = recipes.length;
  const approvedCount = useMemo(
    () => recipes.filter((r) => r.status === 'APPROVED').length,
    [recipes]
  );
  const draftCount = useMemo(() => recipes.filter((r) => r.status === 'DRAFT').length, [recipes]);
  const obsoleteCount = useMemo(
    () => recipes.filter((r) => r.status === 'OBSOLETE').length,
    [recipes]
  );

  // Live synced recipe & sibling version history for the detail drawer
  const activeDetailsRecipe = useMemo(() => {
    if (!isDetailsDrawerOpen) return null;
    if (detailsRecipeId != null) {
      const match = recipes.find((r) => r.id === detailsRecipeId);
      if (match) return match;
    }
    return detailsFallbackRecipe;
  }, [isDetailsDrawerOpen, detailsRecipeId, recipes, detailsFallbackRecipe]);

  const activeRecipeVersions = useMemo(() => {
    if (!activeDetailsRecipe?.productId) return [];
    return recipes.filter((r) => r.productId === activeDetailsRecipe.productId);
  }, [recipes, activeDetailsRecipe]);

  // Modal & Drawer Handlers
  const handleOpenAddModal = () => {
    setSelectedRecipe(null);
    setIsAddEditModalOpen(true);
  };

  const handleOpenEditModal = (recipe) => {
    setSelectedRecipe(recipe);
    setIsAddEditModalOpen(true);
  };

  const handleCloseAddEditModal = () => {
    setIsAddEditModalOpen(false);
    setSelectedRecipe(null);
  };

  const handleOpenDetailsDrawer = (recipe) => {
    setDetailsRecipeId(recipe?.id ?? null);
    setDetailsFallbackRecipe(recipe || null);
    setIsDetailsDrawerOpen(true);
  };

  const handleCloseDetailsDrawer = () => {
    setIsDetailsDrawerOpen(false);
    setDetailsRecipeId(null);
    setDetailsFallbackRecipe(null);
  };

  // Save (Create or Update)
  const handleSaveRecipe = async (formData) => {
    setIsSubmitting(true);
    setActionError(null);
    try {
      if (selectedRecipe && selectedRecipe.id) {
        const updated = await updateRecipe(selectedRecipe.id, formData);
        setNotification({
          type: 'success',
          message: `Recipe ${updated.recipeCode} updated successfully.`,
        });
      } else {
        const created = await createRecipe(formData);
        setNotification({
          type: 'success',
          message: `Recipe ${created.recipeCode} created successfully.`,
        });
      }
      handleCloseAddEditModal();
      await loadRecipes();
    } finally {
      setIsSubmitting(false);
    }
  };

  // Action: Approve
  const handleRequestApprove = (recipe) => {
    setActionError(null);
    setConfirmConfig({
      title: 'Approve Manufacturing Recipe',
      message: `Are you sure you want to approve "${recipe.recipeCode}"? Approving will make any currently active approved version for ${recipe.productName} OBSOLETE, and lock this recipe as immutable.`,
      confirmText: 'Approve Recipe',
      confirmVariant: 'warning',
      onConfirm: async () => {
        setIsConfirming(true);
        setActionLoadingId(recipe.id);
        setActionError(null);
        try {
          await updateRecipeStatus(recipe.id, 'APPROVED');
          setNotification({
            type: 'success',
            message: `Recipe ${recipe.recipeCode} has been APPROVED. Previous approved version (if any) was set to OBSOLETE.`,
          });
          setConfirmConfig(null);
          await loadRecipes();
        } catch (err) {
          const message = err.message || `Failed to approve recipe ${recipe.recipeCode}`;
          setConfirmConfig(null);
          setActionError(message);
          setNotification({
            type: 'error',
            message,
          });
        } finally {
          setIsConfirming(false);
          setActionLoadingId(null);
        }
      },
    });
  };

  // Action: Create New Version
  const handleRequestNewVersion = (recipe) => {
    setActionError(null);
    setConfirmConfig({
      title: 'Create New Recipe Version',
      message: `Do you want to create a new DRAFT version cloned from "${recipe.recipeCode}"? This will copy all raw materials and process steps to version ${recipe.version + 1}.`,
      confirmText: 'Create Version',
      confirmVariant: 'primary',
      onConfirm: async () => {
        setIsConfirming(true);
        setActionLoadingId(recipe.id);
        setActionError(null);
        try {
          const newVer = await createNewVersion(recipe.id);
          setNotification({
            type: 'success',
            message: `New version ${newVer.recipeCode} (v${newVer.version}) created in DRAFT status.`,
          });
          setConfirmConfig(null);
          await loadRecipes();
        } catch (err) {
          const message =
            err.message || `Failed to create new version for ${recipe.recipeCode}`;
          setConfirmConfig(null);
          setActionError(message);
          setNotification({
            type: 'error',
            message,
          });
        } finally {
          setIsConfirming(false);
          setActionLoadingId(null);
        }
      },
    });
  };

  // Action: Delete
  const handleRequestDelete = (recipe) => {
    setActionError(null);
    setConfirmConfig({
      title: 'Delete Draft Recipe',
      message: `Are you sure you want to permanently delete draft recipe "${recipe.recipeCode}"? Only unapproved DRAFT recipes can be deleted. This action cannot be reversed.`,
      confirmText: 'Delete Recipe',
      confirmVariant: 'danger',
      onConfirm: async () => {
        setIsConfirming(true);
        setActionLoadingId(recipe.id);
        setActionError(null);
        try {
          await deleteRecipe(recipe.id);
          if (detailsRecipeId === recipe.id) {
            handleCloseDetailsDrawer();
          }
          setNotification({
            type: 'success',
            message: `Draft recipe ${recipe.recipeCode} deleted successfully.`,
          });
          setConfirmConfig(null);
          await loadRecipes();
        } catch (err) {
          const message = err.message || `Failed to delete recipe ${recipe.recipeCode}`;
          setConfirmConfig(null);
          setActionError(message);
          setNotification({
            type: 'error',
            message,
          });
        } finally {
          setIsConfirming(false);
          setActionLoadingId(null);
        }
      },
    });
  };

  if (!canView) {
    return (
      <AccessDeniedView
        role={role}
        moduleName="Recipe Management"
        description="Master Recipe records and formulation instructions are restricted for your current role."
      />
    );
  }

  return (
    <div className="space-y-6">
      <NotificationBanner
        notification={notification}
        onDismiss={() => setNotification(null)}
      />

      <PageHeader
        title="Recipe Management"
        badge="MES & eBR Formulation"
        description="Manage pharmaceutical master manufacturing recipes, materials bill, and process steps."
        actions={
          <>
            <button
              type="button"
              onClick={loadRecipes}
              disabled={isLoading}
              title="Refresh recipe list"
              className="inline-flex items-center space-x-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
            >
              <RefreshCw
                className={`w-4 h-4 ${isLoading ? 'animate-spin text-pharma-600' : 'text-slate-500'}`}
                aria-hidden="true"
              />
              <span className="hidden sm:inline">Refresh</span>
            </button>

            {canAuthor && (
              <button
                type="button"
                onClick={handleOpenAddModal}
                className="inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors"
              >
                <Plus className="w-4 h-4" aria-hidden="true" />
                <span>Add Recipe</span>
              </button>
            )}
          </>
        }
      />

      {/* KPI Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Total Recipes
            </p>
            <p className="text-2xl font-extrabold text-slate-900 mt-1 tabular-nums">{totalCount}</p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-pharma-50 text-pharma-600 flex items-center justify-center">
            <FlaskConical className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Approved
            </p>
            <p className="text-2xl font-extrabold text-emerald-600 mt-1 tabular-nums">
              {approvedCount}
            </p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-emerald-50 text-emerald-600 flex items-center justify-center">
            <CheckCircle2 className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Drafts
            </p>
            <p className="text-2xl font-extrabold text-pharma-600 mt-1 tabular-nums">
              {draftCount}
            </p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-pharma-50 text-pharma-600 flex items-center justify-center">
            <Clock className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Obsolete
            </p>
            <p className="text-2xl font-extrabold text-slate-500 mt-1 tabular-nums">
              {obsoleteCount}
            </p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-slate-100 text-slate-500 flex items-center justify-center">
            <Archive className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>
      </div>

      {/* Toolbar: Search & Status Filters */}
      <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-3">
        <div className="relative flex-1 max-w-md">
          <label htmlFor={searchInputId} className="sr-only">
            Search recipes by recipe code or product name
          </label>
          <Search
            className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2"
            aria-hidden="true"
          />
          <input
            id={searchInputId}
            type="text"
            value={searchTerm}
            onChange={(e) => handleSearchChange(e.target.value)}
            placeholder="Search by recipe code or product name..."
            className="w-full pl-9 pr-14 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 bg-white"
          />
          {searchTerm && (
            <button
              type="button"
              onClick={() => handleSearchChange('')}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-xs font-medium text-slate-500 hover:text-slate-700"
            >
              Clear
            </button>
          )}
        </div>

        <div
          role="group"
          aria-label="Filter recipes by status"
          className="flex flex-wrap items-center gap-1.5 bg-slate-100 p-1 rounded-lg border border-slate-200 self-start md:self-auto tabular-nums max-w-full"
        >
          <button
            type="button"
            onClick={() => handleStatusFilterChange('ALL')}
            aria-pressed={statusFilter === 'ALL'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md transition-colors whitespace-nowrap ${
              statusFilter === 'ALL'
                ? 'bg-white text-slate-900 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            All ({totalCount})
          </button>
          <button
            type="button"
            onClick={() => handleStatusFilterChange('APPROVED')}
            aria-pressed={statusFilter === 'APPROVED'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md transition-colors whitespace-nowrap ${
              statusFilter === 'APPROVED'
                ? 'bg-white text-emerald-800 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Approved ({approvedCount})
          </button>
          <button
            type="button"
            onClick={() => handleStatusFilterChange('DRAFT')}
            aria-pressed={statusFilter === 'DRAFT'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md transition-colors whitespace-nowrap ${
              statusFilter === 'DRAFT'
                ? 'bg-white text-pharma-800 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Drafts ({draftCount})
          </button>
          <button
            type="button"
            onClick={() => handleStatusFilterChange('OBSOLETE')}
            aria-pressed={statusFilter === 'OBSOLETE'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md transition-colors whitespace-nowrap ${
              statusFilter === 'OBSOLETE'
                ? 'bg-white text-slate-800 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Obsolete ({obsoleteCount})
          </button>
        </div>
      </div>

      {/* Refresh failure alert when existing recipes remain displayed */}
      {loadError && recipes.length > 0 && !isLoading && (
        <InlineAlert
          variant="error"
          title="Unable to refresh master recipes"
          message={loadError}
          onDismiss={() => setLoadError(null)}
          actions={
            <button
              type="button"
              onClick={loadRecipes}
              className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-rose-900 bg-white hover:bg-rose-100 border border-rose-300 rounded-lg transition-colors"
            >
              <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Retry</span>
            </button>
          }
        />
      )}

      {/* Action rejection alert */}
      {actionError && (
        <InlineAlert
          variant="error"
          title="Action Rejected"
          message={actionError}
          onDismiss={() => setActionError(null)}
          dismissLabel="Dismiss action error"
        />
      )}

      {/* Recipe Table */}
      <RecipeTable
        recipes={paginatedResult.items}
        isLoading={isLoading}
        error={loadError}
        onRetry={loadRecipes}
        hasActiveFilters={hasActiveFilters}
        onResetFilters={handleResetFilters}
        onView={handleOpenDetailsDrawer}
        onEdit={handleOpenEditModal}
        onApprove={handleRequestApprove}
        onCreateVersion={handleRequestNewVersion}
        onDelete={handleRequestDelete}
        actionLoadingId={actionLoadingId}
        canAuthor={canAuthor}
        canApprove={canApprove}
        sortState={sortState}
        onSortChange={handleSortChange}
        pagination={
          filteredRecipes.length > 0
            ? {
                currentPage: paginatedResult.page,
                pageSize: paginatedResult.pageSize,
                totalItems: paginatedResult.totalItems,
                onPageChange: setCurrentPage,
                onPageSizeChange: (nextSize) => {
                  setPageSize(nextSize);
                  setCurrentPage(1);
                },
                itemLabel: 'recipes',
              }
            : null
        }
      />

      {/* Add / Edit Recipe Modal */}
      {canAuthor && (
        <RecipeModal
          isOpen={isAddEditModalOpen}
          onClose={handleCloseAddEditModal}
          onSave={handleSaveRecipe}
          initialData={selectedRecipe}
          isSubmitting={isSubmitting}
        />
      )}

      {/* In-Tree Non-Modal Accessible Recipe Detail Drawer */}
      <RecipeDetailDrawer
        isOpen={isDetailsDrawerOpen}
        onClose={handleCloseDetailsDrawer}
        recipe={activeDetailsRecipe}
        allVersions={activeRecipeVersions}
        onSelectVersion={(ver) => {
          setDetailsRecipeId(ver?.id ?? null);
          setDetailsFallbackRecipe(ver || null);
        }}
        canAuthor={canAuthor}
        canApprove={canApprove}
        onEdit={handleOpenEditModal}
        onApprove={handleRequestApprove}
        onCreateVersion={handleRequestNewVersion}
        actionLoadingId={actionLoadingId}
      />

      {/* Confirm Action Dialog */}
      {confirmConfig && (
        <ConfirmModal
          isOpen={Boolean(confirmConfig)}
          title={confirmConfig.title}
          message={confirmConfig.message}
          confirmText={confirmConfig.confirmText}
          confirmVariant={confirmConfig.confirmVariant}
          onConfirm={confirmConfig.onConfirm}
          onClose={() => {
            if (!isConfirming) {
              setConfirmConfig(null);
            }
          }}
          isLoading={isConfirming}
        />
      )}
    </div>
  );
}
