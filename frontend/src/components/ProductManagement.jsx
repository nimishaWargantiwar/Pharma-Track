import React, { useState, useEffect, useMemo, useCallback, useId } from 'react';
import {
  Plus,
  Search,
  RefreshCw,
  Pill,
  CheckCircle2,
  AlertCircle,
} from 'lucide-react';
import ProductTable from './ProductTable';
import ProductModal from './ProductModal';
import ConfirmModal from './ConfirmModal';
import NotificationBanner from './NotificationBanner';
import {
  DEFAULT_PAGE_SIZE,
  InlineAlert,
  PageHeader,
  paginateItems,
} from './ui/index.js';
import {
  getAllProducts,
  createProduct,
  updateProduct,
  updateProductStatus,
} from '../services/productApi';
import { useAuth } from '../context/AuthContext.jsx';
import { canManageProducts } from '../utils/rbacUtils.js';

export default function ProductManagement() {
  const { role } = useAuth();
  const canManage = canManageProducts(role);
  const searchInputId = useId();

  const [products, setProducts] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
  const [notification, setNotification] = useState(null);

  // Search, Filter, Sort & Pagination State
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL'); // 'ALL' | 'ACTIVE' | 'INACTIVE'
  const [sortState, setSortState] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  // Modals
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [selectedProduct, setSelectedProduct] = useState(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [actionLoadingId, setActionLoadingId] = useState(null);

  // Status confirmation dialog
  const [statusConfirmTarget, setStatusConfirmTarget] = useState(null);
  const [isStatusConfirming, setIsStatusConfirming] = useState(false);

  const loadProducts = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const data = await getAllProducts();
      setProducts(Array.isArray(data) ? data : []);
    } catch (err) {
      const message = err.message || 'Unable to connect to product catalog API';
      setError(message);
      setNotification({
        type: 'error',
        message: err.message || 'Failed to load products from server',
      });
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadProducts();
  }, [loadProducts]);

  // Filter across full dataset BEFORE pagination
  const filteredProducts = useMemo(() => {
    const query = searchTerm.trim().toLowerCase();
    return products.filter((product) => {
      if (statusFilter !== 'ALL' && product.status !== statusFilter) {
        return false;
      }
      if (query) {
        const codeMatch = product.productCode?.toLowerCase().includes(query);
        const nameMatch = product.productName?.toLowerCase().includes(query);
        return Boolean(codeMatch || nameMatch);
      }
      return true;
    });
  }, [products, statusFilter, searchTerm]);

  // Sort across filtered dataset BEFORE pagination
  const sortedProducts = useMemo(() => {
    if (!sortState?.key) {
      return filteredProducts;
    }
    const dir = sortState.direction === 'desc' ? -1 : 1;
    return [...filteredProducts].sort((a, b) => {
      const valA =
        sortState.key === 'updatedAt'
          ? a?.updatedAt || a?.createdAt || ''
          : a?.[sortState.key];
      const valB =
        sortState.key === 'updatedAt'
          ? b?.updatedAt || b?.createdAt || ''
          : b?.[sortState.key];

      if (valA == null && valB == null) return 0;
      if (valA == null) return -1 * dir;
      if (valB == null) return 1 * dir;
      return String(valA).localeCompare(String(valB), undefined, { numeric: true }) * dir;
    });
  }, [filteredProducts, sortState]);

  const paginatedResult = useMemo(
    () => paginateItems(sortedProducts, currentPage, pageSize),
    [sortedProducts, currentPage, pageSize]
  );

  // Keep currentPage bounded when filtered dataset shrinks
  useEffect(() => {
    if (paginatedResult.page !== currentPage) {
      setCurrentPage(paginatedResult.page);
    }
  }, [paginatedResult.page, currentPage]);

  const hasActiveFilters = Boolean(searchTerm.trim() || statusFilter !== 'ALL');

  const handleSearchChange = (nextValue) => {
    setSearchTerm(nextValue);
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

  // Statistics (computed over full catalog)
  const totalCount = products.length;
  const activeCount = useMemo(
    () => products.filter((p) => p.status === 'ACTIVE').length,
    [products]
  );
  const inactiveCount = useMemo(
    () => products.filter((p) => p.status === 'INACTIVE').length,
    [products]
  );

  // Modal Handlers
  const handleOpenAddModal = () => {
    setSelectedProduct(null);
    setIsModalOpen(true);
  };

  const handleOpenEditModal = (product) => {
    setSelectedProduct(product);
    setIsModalOpen(true);
  };

  const handleCloseModal = () => {
    setIsModalOpen(false);
    setSelectedProduct(null);
  };

  const handleSaveProduct = async (formData) => {
    setIsSubmitting(true);
    try {
      if (selectedProduct && selectedProduct.id) {
        await updateProduct(selectedProduct.id, formData);
        setNotification({
          type: 'success',
          message: `Product ${formData.productCode} updated successfully.`,
        });
      } else {
        await createProduct(formData);
        setNotification({
          type: 'success',
          message: `Product ${formData.productCode} registered successfully.`,
        });
      }
      handleCloseModal();
      await loadProducts();
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleRequestToggleStatus = (product) => {
    setStatusConfirmTarget(product);
  };

  const handleConfirmToggleStatus = async () => {
    if (!statusConfirmTarget) return;
    const target = statusConfirmTarget;
    const newStatus = target.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';

    setIsStatusConfirming(true);
    setActionLoadingId(target.id);
    try {
      await updateProductStatus(target.id, newStatus);
      setStatusConfirmTarget(null);
      setNotification({
        type: 'success',
        message: `Product ${target.productCode} status changed to ${newStatus}.`,
      });
      await loadProducts();
    } catch (err) {
      setStatusConfirmTarget(null);
      setNotification({
        type: 'error',
        message: err.message || `Failed to update status for ${target.productCode}`,
      });
    } finally {
      setIsStatusConfirming(false);
      setActionLoadingId(null);
    }
  };

  const isDeactivatingTarget = statusConfirmTarget?.status === 'ACTIVE';

  return (
    <div className="space-y-6">
      <NotificationBanner
        notification={notification}
        onDismiss={() => setNotification(null)}
      />

      <PageHeader
        title="Product Management"
        badge="MES Master Data"
        description="Manage pharmaceutical product master data, dosage strengths, and lifecycle availability."
        actions={
          <>
            <button
              type="button"
              onClick={loadProducts}
              disabled={isLoading}
              title="Refresh product list"
              className="inline-flex items-center space-x-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
            >
              <RefreshCw
                className={`w-4 h-4 ${isLoading ? 'animate-spin text-pharma-600' : 'text-slate-500'}`}
                aria-hidden="true"
              />
              <span className="hidden sm:inline">Refresh</span>
            </button>

            {canManage && (
              <button
                type="button"
                onClick={handleOpenAddModal}
                className="inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors"
              >
                <Plus className="w-4 h-4" aria-hidden="true" />
                <span>Add Product</span>
              </button>
            )}
          </>
        }
      />

      {/* KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Total Products
            </p>
            <p className="text-2xl font-extrabold text-slate-900 mt-1 tabular-nums">{totalCount}</p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-pharma-50 text-pharma-600 flex items-center justify-center">
            <Pill className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Active Products
            </p>
            <p className="text-2xl font-extrabold text-emerald-600 mt-1 tabular-nums">
              {activeCount}
            </p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-emerald-50 text-emerald-600 flex items-center justify-center">
            <CheckCircle2 className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Inactive Products
            </p>
            <p className="text-2xl font-extrabold text-slate-500 mt-1 tabular-nums">
              {inactiveCount}
            </p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-slate-100 text-slate-500 flex items-center justify-center">
            <AlertCircle className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>
      </div>

      {/* Toolbar: Search & Status Filters */}
      <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-3">
        <div className="relative flex-1 max-w-md">
          <label htmlFor={searchInputId} className="sr-only">
            Search products by product code or product name
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
            placeholder="Search products by product code or product name..."
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
          aria-label="Filter products by status"
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
            onClick={() => handleStatusFilterChange('ACTIVE')}
            aria-pressed={statusFilter === 'ACTIVE'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md transition-colors whitespace-nowrap ${
              statusFilter === 'ACTIVE'
                ? 'bg-white text-emerald-800 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Active ({activeCount})
          </button>
          <button
            type="button"
            onClick={() => handleStatusFilterChange('INACTIVE')}
            aria-pressed={statusFilter === 'INACTIVE'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md transition-colors whitespace-nowrap ${
              statusFilter === 'INACTIVE'
                ? 'bg-white text-slate-800 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Inactive ({inactiveCount})
          </button>
        </div>
      </div>

      {/* Refresh error alert when cached/existing rows are displayed */}
      {error && products.length > 0 && !isLoading && (
        <InlineAlert
          variant="error"
          title="Unable to refresh product catalog"
          message={error}
          onDismiss={() => setError(null)}
          actions={
            <button
              type="button"
              onClick={loadProducts}
              className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-rose-900 bg-white hover:bg-rose-100 border border-rose-300 rounded-lg transition-colors"
            >
              <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Retry</span>
            </button>
          }
        />
      )}

      {/* Product Table */}
      <ProductTable
        products={paginatedResult.items}
        isLoading={isLoading}
        error={error}
        onRetry={loadProducts}
        hasActiveFilters={hasActiveFilters}
        onResetFilters={handleResetFilters}
        onEdit={handleOpenEditModal}
        onToggleStatus={handleRequestToggleStatus}
        actionLoadingId={actionLoadingId}
        canManageProducts={canManage}
        sortState={sortState}
        onSortChange={handleSortChange}
        pagination={
          filteredProducts.length > 0
            ? {
                currentPage: paginatedResult.page,
                pageSize: paginatedResult.pageSize,
                totalItems: paginatedResult.totalItems,
                onPageChange: setCurrentPage,
                onPageSizeChange: (nextSize) => {
                  setPageSize(nextSize);
                  setCurrentPage(1);
                },
                itemLabel: 'products',
              }
            : null
        }
      />

      {/* Add / Edit Product Modal */}
      {canManage && (
        <ProductModal
          isOpen={isModalOpen}
          onClose={handleCloseModal}
          onSave={handleSaveProduct}
          initialData={selectedProduct}
          isSubmitting={isSubmitting}
        />
      )}

      {/* Product Status Confirmation Modal */}
      {canManage && statusConfirmTarget && (
        <ConfirmModal
          isOpen={Boolean(statusConfirmTarget)}
          title={isDeactivatingTarget ? 'Deactivate Product' : 'Activate Product'}
          message={
            isDeactivatingTarget
              ? `Are you sure you want to deactivate "${statusConfirmTarget.productName}" (${statusConfirmTarget.productCode})? Deactivated products cannot be selected when authoring new manufacturing recipes or scheduling new production orders.`
              : `Are you sure you want to activate "${statusConfirmTarget.productName}" (${statusConfirmTarget.productCode})? Active products become selectable for master recipe authoring and batch production.`
          }
          confirmText={isDeactivatingTarget ? 'Deactivate Product' : 'Activate Product'}
          confirmVariant={isDeactivatingTarget ? 'warning' : 'primary'}
          onConfirm={handleConfirmToggleStatus}
          onClose={() => {
            if (!isStatusConfirming) {
              setStatusConfirmTarget(null);
            }
          }}
          isLoading={isStatusConfirming}
        />
      )}
    </div>
  );
}
