import React, { useState, useEffect, useMemo, useCallback, useId, useRef } from 'react';
import {
  Plus,
  Search,
  RefreshCw,
  ClipboardList,
  Clock,
  Play,
  CheckCircle2,
  XCircle,
  ArrowUpRight,
} from 'lucide-react';
import OrderTable from './OrderTable';
import OrderModal from './OrderModal';
import OrderDetailsModal from './OrderDetailsModal';
import NotificationBanner from './NotificationBanner';
import {
  DEFAULT_PAGE_SIZE,
  InlineAlert,
  PageHeader,
  paginateItems,
} from './ui/index.js';
import { getAllOrders, getOrderByOrderNumber } from '../services/orderApi';
import { useAuth } from '../context/AuthContext.jsx';
import { useNavigation } from '../context/NavigationContext.jsx';
import { canManageManufacturingOrders } from '../utils/rbacUtils.js';
import { calculateOrderBatchProgress } from '../utils/orderUtils.js';

export default function OrderManagement({ onOpenCopilot = null }) {
  const { role } = useAuth();
  const { activeTab, routeParams, routeNonce, navigateToTab } = useNavigation();
  const canManageOrders = canManageManufacturingOrders(role);
  const searchInputId = useId();
  const consumedOrderLinkRef = useRef('');

  const [orders, setOrders] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
  const [notification, setNotification] = useState(null);

  // Modal & Drawer State
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [isDetailsModalOpen, setIsDetailsModalOpen] = useState(false);
  const [selectedOrderId, setSelectedOrderId] = useState(null);

  // Search, Filter, Sort & Pagination State
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL'); // 'ALL' | 'PLANNED' | 'RELEASED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'
  const [sortState, setSortState] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  const loadOrders = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const data = await getAllOrders();
      setOrders(Array.isArray(data) ? data : []);
    } catch (err) {
      setError(err.message || 'Unable to connect to manufacturing order API');
      setNotification({
        type: 'error',
        message: err.message || 'Failed to load manufacturing orders from server',
      });
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadOrders();
  }, [loadOrders]);

  // Consume deep-link params (?orderId=... or ?orderNumber=...)
  const requestedOrderId = routeParams?.get?.('orderId');
  const requestedOrderNumber = routeParams?.get?.('orderNumber');

  useEffect(() => {
    if (!requestedOrderId && !requestedOrderNumber) {
      return undefined;
    }

    const linkKey = `${routeNonce}|${requestedOrderId || ''}|${requestedOrderNumber || ''}`;
    if (consumedOrderLinkRef.current === linkKey) {
      return undefined;
    }

    if (requestedOrderId) {
      consumedOrderLinkRef.current = linkKey;
      const numericId = Number(requestedOrderId);
      setSelectedOrderId(Number.isFinite(numericId) && numericId > 0 ? numericId : requestedOrderId);
      setIsDetailsModalOpen(true);
      return undefined;
    }

    const localMatch = orders.find(
      (o) => String(o.orderNumber || '').toLowerCase() === String(requestedOrderNumber).toLowerCase()
    );
    if (localMatch?.id) {
      consumedOrderLinkRef.current = linkKey;
      setSelectedOrderId(localMatch.id);
      setIsDetailsModalOpen(true);
      return undefined;
    }

    if (isLoading) {
      return undefined;
    }

    let cancelled = false;
    consumedOrderLinkRef.current = linkKey;
    getOrderByOrderNumber(requestedOrderNumber)
      .then((fetched) => {
        if (!cancelled && fetched?.id) {
          setSelectedOrderId(fetched.id);
          setIsDetailsModalOpen(true);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setNotification({
            type: 'error',
            message:
              err.message || `Unable to open manufacturing order ${requestedOrderNumber}`,
          });
        }
      });

    return () => {
      cancelled = true;
    };
  }, [requestedOrderId, requestedOrderNumber, routeNonce, orders, isLoading]);

  // Filter across full dataset BEFORE pagination
  const filteredOrders = useMemo(() => {
    const query = searchTerm.trim().toLowerCase();
    return orders.filter((order) => {
      if (statusFilter !== 'ALL' && order.status !== statusFilter) {
        return false;
      }
      if (query) {
        const orderNumberMatch = order.orderNumber?.toLowerCase().includes(query);
        const productNameMatch = order.productName?.toLowerCase().includes(query);
        const productCodeMatch = order.productCode?.toLowerCase().includes(query);
        const recipeMatch = order.recipeCode?.toLowerCase().includes(query);
        return Boolean(orderNumberMatch || productNameMatch || productCodeMatch || recipeMatch);
      }
      return true;
    });
  }, [orders, statusFilter, searchTerm]);

  // Sort across filtered dataset BEFORE pagination
  const sortedOrders = useMemo(() => {
    if (!sortState?.key) {
      return filteredOrders;
    }
    const dir = sortState.direction === 'desc' ? -1 : 1;
    return [...filteredOrders].sort((a, b) => {
      let valA;
      let valB;
      if (sortState.key === 'plannedQuantity') {
        valA = Number(a?.plannedQuantity) || 0;
        valB = Number(b?.plannedQuantity) || 0;
      } else if (sortState.key === 'batchProgress') {
        const progA = calculateOrderBatchProgress(a);
        const progB = calculateOrderBatchProgress(b);
        valA = a?.status === 'CANCELLED' || progA.allCancelled ? -1 : progA.progressPercent;
        valB = b?.status === 'CANCELLED' || progB.allCancelled ? -1 : progB.progressPercent;
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
  }, [filteredOrders, sortState]);

  const paginatedResult = useMemo(
    () => paginateItems(sortedOrders, currentPage, pageSize),
    [sortedOrders, currentPage, pageSize]
  );

  // Keep currentPage bounded if filtered dataset shrinks
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

  // Statistics & KPI Counts (computed over full dataset)
  const totalCount = orders.length;
  const plannedCount = useMemo(() => orders.filter((o) => o.status === 'PLANNED').length, [orders]);
  const releasedCount = useMemo(() => orders.filter((o) => o.status === 'RELEASED').length, [orders]);
  const inProgressCount = useMemo(() => orders.filter((o) => o.status === 'IN_PROGRESS').length, [orders]);
  const completedCount = useMemo(() => orders.filter((o) => o.status === 'COMPLETED').length, [orders]);
  const cancelledCount = useMemo(() => orders.filter((o) => o.status === 'CANCELLED').length, [orders]);

  // Modal & Drawer Handlers
  const handleOpenCreateModal = () => {
    setIsCreateModalOpen(true);
  };

  const handleCloseCreateModal = () => {
    setIsCreateModalOpen(false);
  };

  const handleOrderCreated = async (createdOrder) => {
    setIsCreateModalOpen(false);
    const orderNum = createdOrder?.orderNumber ? ` (${createdOrder.orderNumber})` : '';
    setNotification({
      type: 'success',
      message: `Manufacturing order${orderNum} created successfully with planned batches.`,
    });

    setIsLoading(true);
    try {
      const data = await getAllOrders();
      setOrders(Array.isArray(data) ? data : []);
      setError(null);
      setCurrentPage(1);
    } catch (err) {
      setError(err.message || 'Order created, but unable to refresh manufacturing orders list');
      if (createdOrder) {
        setOrders((prev) => [createdOrder, ...prev.filter((o) => o.id !== createdOrder.id)]);
      }
    } finally {
      setIsLoading(false);
    }
  };

  const handleViewDetails = (order) => {
    if (!order?.id) return;
    setSelectedOrderId(order.id);
    setIsDetailsModalOpen(true);
  };

  const handleCloseDetailsModal = () => {
    setIsDetailsModalOpen(false);
    setSelectedOrderId(null);
    if (activeTab === 'orders' && (requestedOrderId || requestedOrderNumber)) {
      navigateToTab('orders');
    }
  };

  const handleOrderStatusUpdated = async (updatedOrder, successMessage) => {
    setNotification({
      type: 'success',
      message: successMessage,
    });

    try {
      const data = await getAllOrders();
      setOrders(Array.isArray(data) ? data : []);
      setError(null);
    } catch (err) {
      setError(err.message || 'Order status updated, but unable to refresh manufacturing orders list');
      if (updatedOrder?.id) {
        setOrders((prev) =>
          prev.map((o) => (o.id === updatedOrder.id ? updatedOrder : o))
        );
      }
    }
  };

  return (
    <div className="space-y-6">
      <NotificationBanner
        notification={notification}
        onDismiss={() => setNotification(null)}
      />

      <PageHeader
        title="Manufacturing Orders"
        badge="MES Execution"
        description="Track manufacturing orders, production batches, and shop-floor execution progress."
        actions={
          <>
            <button
              type="button"
              onClick={() => navigateToTab('execution')}
              className="inline-flex items-center space-x-1.5 px-3 py-2 text-sm font-medium text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg shadow-sm transition-colors"
            >
              <span>Production Execution</span>
              <ArrowUpRight className="w-4 h-4" aria-hidden="true" />
            </button>

            <button
              type="button"
              onClick={loadOrders}
              disabled={isLoading}
              title="Refresh order list"
              className="inline-flex items-center space-x-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
            >
              <RefreshCw
                className={`w-4 h-4 ${isLoading ? 'animate-spin text-pharma-600' : 'text-slate-500'}`}
                aria-hidden="true"
              />
              <span className="hidden sm:inline">Refresh</span>
            </button>

            {canManageOrders && (
              <button
                type="button"
                onClick={handleOpenCreateModal}
                className="inline-flex items-center space-x-1.5 px-4 py-2 text-sm font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors"
              >
                <Plus className="w-4 h-4" aria-hidden="true" />
                <span>Create Manufacturing Order</span>
              </button>
            )}
          </>
        }
      />

      {/* KPI Cards */}
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-5 gap-4">
        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">Total Orders</p>
            <p className="text-2xl font-extrabold text-slate-900 mt-1 tabular-nums">{totalCount}</p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-pharma-50 text-pharma-600 flex items-center justify-center">
            <ClipboardList className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">Planned</p>
            <p className="text-2xl font-extrabold text-pharma-600 mt-1 tabular-nums">{plannedCount}</p>
            {releasedCount > 0 && (
              <p className="text-[11px] font-medium text-indigo-700 mt-0.5 tabular-nums">
                {releasedCount} released
              </p>
            )}
          </div>
          <div className="w-10 h-10 rounded-lg bg-pharma-50 text-pharma-600 flex items-center justify-center">
            <Clock className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">In Progress</p>
            <p className="text-2xl font-extrabold text-amber-600 mt-1 tabular-nums">{inProgressCount}</p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-amber-50 text-amber-600 flex items-center justify-center">
            <Play className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">Completed</p>
            <p className="text-2xl font-extrabold text-emerald-600 mt-1 tabular-nums">{completedCount}</p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-emerald-50 text-emerald-600 flex items-center justify-center">
            <CheckCircle2 className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>

        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between col-span-2 md:col-span-1">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">Cancelled</p>
            <p className="text-2xl font-extrabold text-rose-600 mt-1 tabular-nums">{cancelledCount}</p>
          </div>
          <div className="w-10 h-10 rounded-lg bg-rose-50 text-rose-600 flex items-center justify-center">
            <XCircle className="w-5 h-5" aria-hidden="true" />
          </div>
        </div>
      </div>

      {/* Toolbar: Search & Status Filters */}
      <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm flex flex-col lg:flex-row lg:items-center justify-between gap-3">
        <div className="relative flex-1 max-w-md">
          <label htmlFor={searchInputId} className="sr-only">
            Search manufacturing orders by order number, product name, or product code
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
            placeholder="Search by order number, product name, or product code..."
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
          aria-label="Filter manufacturing orders by status"
          className="flex flex-wrap items-center gap-1.5 bg-slate-100 p-1 rounded-lg border border-slate-200 self-start lg:self-auto max-w-full tabular-nums"
        >
          <button
            type="button"
            onClick={() => handleStatusFilterChange('ALL')}
            aria-pressed={statusFilter === 'ALL'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md whitespace-nowrap transition-colors ${
              statusFilter === 'ALL'
                ? 'bg-white text-slate-900 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            All ({totalCount})
          </button>
          <button
            type="button"
            onClick={() => handleStatusFilterChange('PLANNED')}
            aria-pressed={statusFilter === 'PLANNED'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md whitespace-nowrap transition-colors ${
              statusFilter === 'PLANNED'
                ? 'bg-white text-pharma-800 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Planned ({plannedCount})
          </button>
          <button
            type="button"
            onClick={() => handleStatusFilterChange('RELEASED')}
            aria-pressed={statusFilter === 'RELEASED'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md whitespace-nowrap transition-colors ${
              statusFilter === 'RELEASED'
                ? 'bg-white text-indigo-800 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Released ({releasedCount})
          </button>
          <button
            type="button"
            onClick={() => handleStatusFilterChange('IN_PROGRESS')}
            aria-pressed={statusFilter === 'IN_PROGRESS'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md whitespace-nowrap transition-colors ${
              statusFilter === 'IN_PROGRESS'
                ? 'bg-white text-amber-800 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            In Progress ({inProgressCount})
          </button>
          <button
            type="button"
            onClick={() => handleStatusFilterChange('COMPLETED')}
            aria-pressed={statusFilter === 'COMPLETED'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md whitespace-nowrap transition-colors ${
              statusFilter === 'COMPLETED'
                ? 'bg-white text-emerald-800 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Completed ({completedCount})
          </button>
          <button
            type="button"
            onClick={() => handleStatusFilterChange('CANCELLED')}
            aria-pressed={statusFilter === 'CANCELLED'}
            className={`px-3 py-1.5 text-xs font-medium rounded-md whitespace-nowrap transition-colors ${
              statusFilter === 'CANCELLED'
                ? 'bg-white text-rose-800 shadow-sm font-semibold'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Cancelled ({cancelledCount})
          </button>
        </div>
      </div>

      {/* Refresh error alert when cached/existing rows are displayed */}
      {error && orders.length > 0 && !isLoading && (
        <InlineAlert
          variant="error"
          title="Unable to refresh manufacturing orders"
          message={error}
          onDismiss={() => setError(null)}
          actions={
            <button
              type="button"
              onClick={loadOrders}
              className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-rose-900 bg-white hover:bg-rose-100 border border-rose-300 rounded-lg transition-colors"
            >
              <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Retry</span>
            </button>
          }
        />
      )}

      {/* Orders Table */}
      <OrderTable
        orders={paginatedResult.items}
        isLoading={isLoading}
        error={error}
        onRetry={loadOrders}
        hasActiveFilters={hasActiveFilters}
        onResetFilters={handleResetFilters}
        onView={handleViewDetails}
        sortState={sortState}
        onSortChange={handleSortChange}
        pagination={
          filteredOrders.length > 0
            ? {
                currentPage: paginatedResult.page,
                pageSize: paginatedResult.pageSize,
                totalItems: paginatedResult.totalItems,
                onPageChange: setCurrentPage,
                onPageSizeChange: (nextSize) => {
                  setPageSize(nextSize);
                  setCurrentPage(1);
                },
                itemLabel: 'orders',
              }
            : null
        }
      />

      {/* Create Order Modal */}
      {canManageOrders && (
        <OrderModal
          isOpen={isCreateModalOpen}
          onClose={handleCloseCreateModal}
          onSuccess={handleOrderCreated}
        />
      )}

      {/* Order Details Slide-Over Drawer */}
      <OrderDetailsModal
        isOpen={isDetailsModalOpen}
        onClose={handleCloseDetailsModal}
        orderId={selectedOrderId}
        onOrderUpdated={handleOrderStatusUpdated}
        onOpenCopilot={onOpenCopilot}
      />
    </div>
  );
}
