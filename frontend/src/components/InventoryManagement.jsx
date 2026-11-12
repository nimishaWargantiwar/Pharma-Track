import React, { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  ArrowRightLeft,
  CheckCircle2,
  Edit2,
  Eye,
  FlaskConical,
  History,
  Layers,
  MapPin,
  Package,
  PackagePlus,
  Plus,
  Power,
  RefreshCw,
  Search,
  SlidersHorizontal,
  Warehouse,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext.jsx';
import { useNavigation } from '../context/NavigationContext.jsx';
import {
  adjustStock,
  createRawMaterial,
  createStorageLocation,
  createWarehouse,
  getAllLocations,
  getAllLots,
  getAllMovements,
  getAllRawMaterials,
  getAllWarehouses,
  getInventorySummary,
  getRecipeMaterialReferences,
  issueMaterial,
  receiveGoods,
  updateRawMaterial,
  updateRawMaterialStatus,
  updateStorageLocation,
  updateStorageLocationStatus,
  updateWarehouse,
  updateWarehouseStatus,
} from '../services/inventoryApi.js';
import {
  canAdjustStock,
  canIssueMaterials,
  canManageRawMaterials,
  canManageWarehouses,
  canReceiveGoods,
} from '../utils/rbacUtils.js';
import { formatAppShortDateTime } from '../utils/dateTimeUtils.js';
import { formatCalendarDate } from '../utils/orderUtils.js';
import {
  formatQuantityDisplay,
  formatSignedDelta,
  MATERIAL_TYPES,
} from '../utils/inventoryUtils.js';
import {
  DataTable,
  DEFAULT_PAGE_SIZE,
  InlineAlert,
  PageHeader,
  paginateItems,
  StatusBadge,
} from './ui/index.js';
import ConfirmModal from './ConfirmModal.jsx';
import NotificationBanner from './NotificationBanner.jsx';
import RawMaterialModal from './RawMaterialModal.jsx';
import WarehouseModal from './WarehouseModal.jsx';
import StorageLocationModal from './StorageLocationModal.jsx';
import GoodsReceiptModal from './GoodsReceiptModal.jsx';
import MaterialIssueModal from './MaterialIssueModal.jsx';
import StockAdjustmentModal from './StockAdjustmentModal.jsx';
import MaterialDetailDrawer from './MaterialDetailDrawer.jsx';

const SUB_TABS = [
  { id: 'MATERIALS', label: 'Raw Materials', icon: Package },
  { id: 'LOTS', label: 'Lots & Expiry', icon: Layers },
  { id: 'WAREHOUSES', label: 'Warehouses & Locations', icon: Warehouse },
  { id: 'MOVEMENTS', label: 'Movement Ledger', icon: History },
];

export default function InventoryManagement() {
  const { role } = useAuth();
  const { routeParams, routeNonce, navigateToTab } = useNavigation();

  const canManageMaterials = canManageRawMaterials(role);
  const canManageWh = canManageWarehouses(role);
  const canReceive = canReceiveGoods(role);
  const canIssue = canIssueMaterials(role);
  const canAdjust = canAdjustStock(role);

  const searchInputId = useId();
  const secondaryFilterId = useId();
  const statusFilterId = useId();

  const [subTab, setSubTab] = useState('MATERIALS');
  const [summary, setSummary] = useState(null);
  const [materials, setMaterials] = useState([]);
  const [warehouses, setWarehouses] = useState([]);
  const [locations, setLocations] = useState([]);
  const [lots, setLots] = useState([]);
  const [movements, setMovements] = useState([]);
  const [recipeReferences, setRecipeReferences] = useState([]);

  const [isLoading, setIsLoading] = useState(true);
  const [hasLoadedOnce, setHasLoadedOnce] = useState(false);
  const [error, setError] = useState(null);
  const [notification, setNotification] = useState(null);

  // Full-dataset Search, Filter, Sort & Pagination State
  const [searchTerm, setSearchTerm] = useState('');
  const [materialTypeFilter, setMaterialTypeFilter] = useState('ALL');
  const [materialStatusFilter, setMaterialStatusFilter] = useState('ALL');
  const [lotWarehouseFilter, setLotWarehouseFilter] = useState('ALL');
  const [lotStatusFilter, setLotStatusFilter] = useState('ALL');
  const [movementTypeFilter, setMovementTypeFilter] = useState('ALL');
  const [sortState, setSortState] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  // Modals & Drawers State
  const [materialModalState, setMaterialModalState] = useState({
    open: false,
    material: null,
    prefillRef: null,
  });
  const [isSavingMaterial, setIsSavingMaterial] = useState(false);

  const [warehouseModalState, setWarehouseModalState] = useState({
    open: false,
    warehouse: null,
  });
  const [isSavingWarehouse, setIsSavingWarehouse] = useState(false);

  const [locationModalState, setLocationModalState] = useState({
    open: false,
    location: null,
    defaultWarehouseId: null,
  });
  const [isSavingLocation, setIsSavingLocation] = useState(false);

  const [receiptModalState, setReceiptModalState] = useState({
    open: false,
    initialMaterialId: null,
  });
  const [isSavingReceipt, setIsSavingReceipt] = useState(false);

  const [issueModalState, setIssueModalState] = useState({
    open: false,
    initialOrderId: null,
    initialMaterialId: null,
    initialLotId: null,
  });
  const [isSavingIssue, setIsSavingIssue] = useState(false);

  const [adjustmentModalState, setAdjustmentModalState] = useState({
    open: false,
    initialLot: null,
    initialActionType: null,
  });
  const [isSavingAdjustment, setIsSavingAdjustment] = useState(false);

  const [inspectedMaterialId, setInspectedMaterialId] = useState(null);
  const [confirmStatusAction, setConfirmStatusAction] = useState(null);
  const [isUpdatingStatus, setIsUpdatingStatus] = useState(false);

  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const loadInventoryData = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const [
        summaryRes,
        materialsRes,
        warehousesRes,
        locationsRes,
        lotsRes,
        movementsRes,
        recipeRefsRes,
      ] = await Promise.all([
        getInventorySummary(),
        getAllRawMaterials(),
        getAllWarehouses(),
        getAllLocations(),
        getAllLots(),
        getAllMovements(),
        getRecipeMaterialReferences().catch(() => []),
      ]);

      if (!mountedRef.current) return;

      setSummary(summaryRes && !Array.isArray(summaryRes) ? summaryRes : null);
      setMaterials(Array.isArray(materialsRes) ? materialsRes : []);
      setWarehouses(Array.isArray(warehousesRes) ? warehousesRes : []);
      setLocations(Array.isArray(locationsRes) ? locationsRes : []);
      setLots(Array.isArray(lotsRes) ? lotsRes : []);
      setMovements(Array.isArray(movementsRes) ? movementsRes : []);
      setRecipeReferences(Array.isArray(recipeRefsRes) ? recipeRefsRes : []);
      setHasLoadedOnce(true);
    } catch (err) {
      if (!mountedRef.current) return;
      const message = err.message || 'Unable to load inventory and warehouse records';
      setError(message);
    } finally {
      if (mountedRef.current) {
        setIsLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    loadInventoryData();
  }, [loadInventoryData]);

  // Apply deep-link route params cleanly when navigating from Orders or external tabs
  useEffect(() => {
    if (!routeParams) return;
    const viewParam = (routeParams.get('view') || '').trim().toLowerCase();
    if (viewParam === 'lots') {
      setSubTab('LOTS');
      setCurrentPage(1);
    } else if (viewParam === 'warehouses') {
      setSubTab('WAREHOUSES');
      setCurrentPage(1);
    } else if (viewParam === 'movements') {
      setSubTab('MOVEMENTS');
      setCurrentPage(1);
    } else if (viewParam === 'materials') {
      setSubTab('MATERIALS');
      setCurrentPage(1);
    }

    const actionParam = (routeParams.get('action') || '').trim().toLowerCase();
    const orderIdParam = routeParams.get('orderId');
    const materialIdParam = routeParams.get('materialId');
    if (actionParam === 'issue' && canIssue && orderIdParam) {
      setIssueModalState({
        open: true,
        initialOrderId: Number(orderIdParam),
        initialMaterialId: materialIdParam ? Number(materialIdParam) : null,
        initialLotId: null,
      });
    }
  }, [routeParams, routeNonce, canIssue]);

  const handleSwitchSubTab = (nextTab) => {
    setSubTab(nextTab);
    setSortState(null);
    setCurrentPage(1);
    setSearchTerm('');
  };

  // KPI Metrics (derived fallback ensures unit test mocks work cleanly even without explicit summary payload)
  const kpiMetrics = useMemo(() => {
    if (summary && typeof summary.totalMaterialsCount === 'number') {
      return summary;
    }
    const activeMats = materials.filter((m) => m.active);
    const activeLotsList = lots.filter(
      (l) => l.effectiveStatus === 'ACTIVE' && !l.expired && Number(l.availableQuantity || 0) > 0
    );
    const totalAvail = activeLotsList.reduce(
      (acc, l) => acc + Number(l.availableQuantity || 0),
      0
    );
    return {
      totalMaterialsCount: materials.length,
      activeMaterialsCount: activeMats.length,
      lowStockMaterialsCount: materials.filter((m) => m.stockStatus === 'LOW_STOCK').length,
      outOfStockMaterialsCount: materials.filter((m) => m.stockStatus === 'OUT_OF_STOCK').length,
      activeLotCount: activeLotsList.length,
      expiringSoonLotCount: activeLotsList.filter((l) => l.expiringSoon).length,
      quarantinedLotCount: lots.filter((l) => l.effectiveStatus === 'QUARANTINED').length,
      expiredLotCount: lots.filter((l) => l.effectiveStatus === 'EXPIRED' || l.expired).length,
      warehouseCount: warehouses.length,
      activeWarehouseCount: warehouses.filter((w) => w.active).length,
      storageLocationCount: locations.filter((l) => l.active).length,
      totalAvailableUnits: totalAvail,
      totalMovementsCount: movements.length,
    };
  }, [summary, materials, lots, warehouses, locations, movements]);

  const unregisteredRecipeRefs = useMemo(
    () => recipeReferences.filter((r) => !r.registeredInInventory),
    [recipeReferences]
  );

  const inspectedMaterial = useMemo(
    () =>
      inspectedMaterialId != null
        ? materials.find((m) => Number(m.id) === Number(inspectedMaterialId)) || null
        : null,
    [materials, inspectedMaterialId]
  );

  // Filtered & Sorted Materials (BEFORE pagination)
  const filteredMaterials = useMemo(() => {
    const query = searchTerm.trim().toLowerCase();
    return materials.filter((m) => {
      if (materialTypeFilter !== 'ALL' && m.materialType !== materialTypeFilter) {
        return false;
      }
      if (materialStatusFilter !== 'ALL' && m.stockStatus !== materialStatusFilter) {
        return false;
      }
      if (query) {
        const matchCode = m.materialCode?.toLowerCase().includes(query);
        const matchName = m.materialName?.toLowerCase().includes(query);
        const matchRecipes = Array.isArray(m.linkedRecipeCodes)
          ? m.linkedRecipeCodes.some((rc) => rc.toLowerCase().includes(query))
          : false;
        return Boolean(matchCode || matchName || matchRecipes);
      }
      return true;
    });
  }, [materials, searchTerm, materialTypeFilter, materialStatusFilter]);

  const sortedMaterials = useMemo(() => {
    if (!sortState?.key) return filteredMaterials;
    const dir = sortState.direction === 'desc' ? -1 : 1;
    return [...filteredMaterials].sort((a, b) => {
      const valA = a?.[sortState.key];
      const valB = b?.[sortState.key];
      if (valA == null && valB == null) return 0;
      if (valA == null) return -1 * dir;
      if (valB == null) return 1 * dir;
      if (typeof valA === 'number' && typeof valB === 'number') return (valA - valB) * dir;
      return String(valA).localeCompare(String(valB), undefined, { numeric: true }) * dir;
    });
  }, [filteredMaterials, sortState]);

  const paginatedMaterials = useMemo(
    () => paginateItems(sortedMaterials, currentPage, pageSize),
    [sortedMaterials, currentPage, pageSize]
  );

  // Filtered & Sorted Lots (BEFORE pagination)
  const filteredLots = useMemo(() => {
    const query = searchTerm.trim().toLowerCase();
    return lots.filter((lot) => {
      if (lotWarehouseFilter !== 'ALL' && String(lot.warehouseId) !== String(lotWarehouseFilter)) {
        return false;
      }
      if (lotStatusFilter === 'EXPIRING_SOON') {
        if (!lot.expiringSoon) return false;
      } else if (lotStatusFilter !== 'ALL' && lot.effectiveStatus !== lotStatusFilter) {
        return false;
      }
      if (query) {
        const matchLot = lot.lotNumber?.toLowerCase().includes(query);
        const matchMat =
          lot.materialCode?.toLowerCase().includes(query) ||
          lot.materialName?.toLowerCase().includes(query);
        const matchSupplier = lot.supplierName?.toLowerCase().includes(query);
        const matchLoc =
          lot.warehouseCode?.toLowerCase().includes(query) ||
          lot.locationCode?.toLowerCase().includes(query);
        return Boolean(matchLot || matchMat || matchSupplier || matchLoc);
      }
      return true;
    });
  }, [lots, searchTerm, lotWarehouseFilter, lotStatusFilter]);

  const sortedLots = useMemo(() => {
    if (!sortState?.key) return filteredLots;
    const dir = sortState.direction === 'desc' ? -1 : 1;
    return [...filteredLots].sort((a, b) => {
      const valA = a?.[sortState.key];
      const valB = b?.[sortState.key];
      if (valA == null && valB == null) return 0;
      if (valA == null) return -1 * dir;
      if (valB == null) return 1 * dir;
      if (typeof valA === 'number' && typeof valB === 'number') return (valA - valB) * dir;
      return String(valA).localeCompare(String(valB), undefined, { numeric: true }) * dir;
    });
  }, [filteredLots, sortState]);

  const paginatedLots = useMemo(
    () => paginateItems(sortedLots, currentPage, pageSize),
    [sortedLots, currentPage, pageSize]
  );

  // Filtered & Sorted Movements (BEFORE pagination)
  const filteredMovements = useMemo(() => {
    const query = searchTerm.trim().toLowerCase();
    return movements.filter((mv) => {
      if (movementTypeFilter !== 'ALL' && mv.movementType !== movementTypeFilter) {
        return false;
      }
      if (query) {
        return Boolean(
          mv.movementNumber?.toLowerCase().includes(query) ||
            mv.referenceNumber?.toLowerCase().includes(query) ||
            mv.materialCode?.toLowerCase().includes(query) ||
            mv.materialName?.toLowerCase().includes(query) ||
            mv.lotNumber?.toLowerCase().includes(query) ||
            mv.orderNumber?.toLowerCase().includes(query) ||
            mv.batchNumber?.toLowerCase().includes(query) ||
            mv.performedBy?.toLowerCase().includes(query)
        );
      }
      return true;
    });
  }, [movements, searchTerm, movementTypeFilter]);

  const sortedMovements = useMemo(() => {
    if (!sortState?.key) return filteredMovements;
    const dir = sortState.direction === 'desc' ? -1 : 1;
    return [...filteredMovements].sort((a, b) => {
      const valA = a?.[sortState.key];
      const valB = b?.[sortState.key];
      if (valA == null && valB == null) return 0;
      if (valA == null) return -1 * dir;
      if (valB == null) return 1 * dir;
      if (typeof valA === 'number' && typeof valB === 'number') return (valA - valB) * dir;
      return String(valA).localeCompare(String(valB), undefined, { numeric: true }) * dir;
    });
  }, [filteredMovements, sortState]);

  const paginatedMovements = useMemo(
    () => paginateItems(sortedMovements, currentPage, pageSize),
    [sortedMovements, currentPage, pageSize]
  );

  // Filtered Warehouses & Locations
  const filteredWarehouses = useMemo(() => {
    const query = searchTerm.trim().toLowerCase();
    if (!query) return warehouses;
    return warehouses.filter((wh) => {
      const matchWh =
        wh.warehouseCode?.toLowerCase().includes(query) ||
        wh.warehouseName?.toLowerCase().includes(query) ||
        wh.storageCondition?.toLowerCase().includes(query);
      const childLocs = locations.filter((l) => Number(l.warehouseId) === Number(wh.id));
      const matchLoc = childLocs.some(
        (l) =>
          l.locationCode?.toLowerCase().includes(query) ||
          l.zoneName?.toLowerCase().includes(query)
      );
      return Boolean(matchWh || matchLoc);
    });
  }, [warehouses, locations, searchTerm]);

  // Action Handlers
  const handleSaveRawMaterial = async (payload) => {
    setIsSavingMaterial(true);
    try {
      const editing = materialModalState.material;
      const saved = editing?.id
        ? await updateRawMaterial(editing.id, payload)
        : await createRawMaterial(payload);
      setMaterialModalState({ open: false, material: null, prefillRef: null });
      setNotification({
        type: 'success',
        message: editing?.id
          ? `Raw material ${saved.materialCode} updated.`
          : `Raw material ${saved.materialCode} (${saved.materialName}) registered.`,
      });
      await loadInventoryData();
    } finally {
      setIsSavingMaterial(false);
    }
  };

  const handleSaveWarehouse = async (payload) => {
    setIsSavingWarehouse(true);
    try {
      const editing = warehouseModalState.warehouse;
      const saved = editing?.id
        ? await updateWarehouse(editing.id, payload)
        : await createWarehouse(payload);
      setWarehouseModalState({ open: false, warehouse: null });
      setNotification({
        type: 'success',
        message: editing?.id
          ? `Warehouse ${saved.warehouseCode} updated.`
          : `Warehouse ${saved.warehouseCode} created.`,
      });
      await loadInventoryData();
    } finally {
      setIsSavingWarehouse(false);
    }
  };

  const handleSaveStorageLocation = async (warehouseId, payload) => {
    setIsSavingLocation(true);
    try {
      const editing = locationModalState.location;
      const saved = editing?.id
        ? await updateStorageLocation(editing.id, payload)
        : await createStorageLocation(warehouseId, payload);
      setLocationModalState({ open: false, location: null, defaultWarehouseId: null });
      setNotification({
        type: 'success',
        message: editing?.id
          ? `Storage location ${saved.locationCode} updated.`
          : `Storage location ${saved.locationCode} added to ${saved.warehouseCode || 'warehouse'}.`,
      });
      await loadInventoryData();
    } finally {
      setIsSavingLocation(false);
    }
  };

  const handleReceiveGoods = async (payload) => {
    setIsSavingReceipt(true);
    try {
      const movement = await receiveGoods(payload);
      setReceiptModalState({ open: false, initialMaterialId: null });
      setNotification({
        type: 'success',
        message: `Goods Receipt ${movement.movementNumber} recorded: +${movement.quantityDelta} ${movement.unit} (${movement.lotNumber}).`,
      });
      await loadInventoryData();
    } finally {
      setIsSavingReceipt(false);
    }
  };

  const handleIssueMaterial = async (payload) => {
    setIsSavingIssue(true);
    try {
      const movement = await issueMaterial(payload);
      setIssueModalState({
        open: false,
        initialOrderId: null,
        initialMaterialId: null,
        initialLotId: null,
      });
      setNotification({
        type: 'success',
        message: `Material Issue ${movement.movementNumber} recorded for order ${
          movement.orderNumber || payload.manufacturingOrderId
        }: ${movement.quantityDelta} ${movement.unit}.`,
      });
      await loadInventoryData();
    } finally {
      setIsSavingIssue(false);
    }
  };

  const handleAdjustStock = async (payload) => {
    setIsSavingAdjustment(true);
    try {
      const movement = await adjustStock(payload);
      setAdjustmentModalState({ open: false, initialLot: null, initialActionType: null });
      setNotification({
        type: 'success',
        message: `Stock adjustment ${movement.movementNumber} recorded for lot ${movement.lotNumber}.`,
      });
      await loadInventoryData();
    } finally {
      setIsSavingAdjustment(false);
    }
  };

  const handleConfirmToggleStatus = async () => {
    if (!confirmStatusAction) return;
    const { entityType, record, targetActive } = confirmStatusAction;
    setIsUpdatingStatus(true);
    try {
      if (entityType === 'MATERIAL') {
        await updateRawMaterialStatus(record.id, targetActive);
      } else if (entityType === 'WAREHOUSE') {
        await updateWarehouseStatus(record.id, targetActive);
      } else if (entityType === 'LOCATION') {
        await updateStorageLocationStatus(record.id, targetActive);
      }
      setConfirmStatusAction(null);
      setNotification({
        type: 'success',
        message: `${
          entityType === 'MATERIAL'
            ? `Raw material ${record.materialCode}`
            : entityType === 'WAREHOUSE'
              ? `Warehouse ${record.warehouseCode}`
              : `Storage location ${record.locationCode}`
        } ${targetActive ? 'activated' : 'deactivated'}.`,
      });
      await loadInventoryData();
    } catch (err) {
      setConfirmStatusAction(null);
      setNotification({
        type: 'error',
        message: err.message || 'Failed to update status',
      });
    } finally {
      setIsUpdatingStatus(false);
    }
  };

  // Column definitions
  const materialColumns = [
    {
      key: 'materialCode',
      header: 'Material',
      sortable: true,
      render: (row) => (
        <div>
          <div className="font-mono font-semibold text-pharma-700">{row.materialCode}</div>
          <div className="text-sm font-medium text-slate-900">{row.materialName}</div>
        </div>
      ),
    },
    {
      key: 'materialType',
      header: 'Category',
      sortable: true,
      render: (row) => (
        <StatusBadge status={row.materialType} domain="inventory" size="xs" variant="tag" />
      ),
    },
    {
      key: 'availableStock',
      header: 'Available Stock',
      sortable: true,
      align: 'right',
      render: (row) => (
        <div className="tabular-nums">
          <div className="font-mono font-bold text-slate-900">
            {formatQuantityDisplay(row.availableStock, row.unit)}
          </div>
          {Number(row.quarantinedStock || 0) > 0 && (
            <div className="text-[11px] text-amber-700">
              {formatQuantityDisplay(row.quarantinedStock, row.unit)} quarantined
            </div>
          )}
        </div>
      ),
    },
    {
      key: 'minimumStockLevel',
      header: 'Min Threshold',
      sortable: true,
      align: 'right',
      render: (row) => (
        <span className="font-mono text-xs text-slate-600 tabular-nums">
          {formatQuantityDisplay(row.minimumStockLevel, row.unit)}
        </span>
      ),
    },
    {
      key: 'stockStatus',
      header: 'Stock Status',
      sortable: true,
      render: (row) => (
        <div className="space-y-1">
          <StatusBadge status={row.stockStatus} domain="inventory" size="xs" showDot />
          {Number(row.expiringSoonLotCount || 0) > 0 && (
            <div className="text-[11px] text-amber-700 font-medium">
              {row.expiringSoonLotCount} lot(s) expiring soon
            </div>
          )}
        </div>
      ),
    },
    {
      key: 'linkedRecipeCount',
      header: 'Recipe Links',
      sortable: true,
      render: (row) =>
        row.linkedRecipeCount > 0 ? (
          <div className="flex flex-wrap gap-1 max-w-[180px]">
            {(row.linkedRecipeCodes || []).slice(0, 2).map((rc) => (
              <span
                key={rc}
                className="px-1.5 py-0.5 text-[11px] font-mono rounded bg-indigo-50 text-indigo-700 border border-indigo-200"
              >
                {rc}
              </span>
            ))}
            {row.linkedRecipeCount > 2 && (
              <span className="text-[11px] text-slate-500">+{row.linkedRecipeCount - 2} more</span>
            )}
          </div>
        ) : (
          <span className="text-xs text-slate-400">Unlinked</span>
        ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      render: (row) => (
        <div className="inline-flex flex-wrap items-center justify-end gap-1.5">
          <button
            type="button"
            onClick={() => setInspectedMaterialId(row.id)}
            title="Inspect material lots and movements"
            className="inline-flex items-center space-x-1 px-2.5 py-1 text-xs font-semibold text-slate-700 bg-white hover:bg-slate-100 border border-slate-200 rounded-md transition-colors"
          >
            <Eye className="w-3.5 h-3.5 text-slate-500" aria-hidden="true" />
            <span>Inspect</span>
          </button>

          {canReceive && row.active && (
            <button
              type="button"
              onClick={() => setReceiptModalState({ open: true, initialMaterialId: row.id })}
              title="Receive goods into this material"
              className="inline-flex items-center space-x-1 px-2.5 py-1 text-xs font-semibold text-emerald-800 bg-emerald-50 hover:bg-emerald-100 border border-emerald-200 rounded-md transition-colors"
            >
              <PackagePlus className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Receive</span>
            </button>
          )}

          {canIssue && row.active && Number(row.availableStock || 0) > 0 && (
            <button
              type="button"
              onClick={() =>
                setIssueModalState({
                  open: true,
                  initialOrderId: null,
                  initialMaterialId: row.id,
                  initialLotId: null,
                })
              }
              title="Issue material lot to an order"
              className="inline-flex items-center space-x-1 px-2.5 py-1 text-xs font-semibold text-indigo-700 bg-indigo-50 hover:bg-indigo-100 border border-indigo-200 rounded-md transition-colors"
            >
              <ArrowRightLeft className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Issue</span>
            </button>
          )}

          {canManageMaterials && (
            <>
              <button
                type="button"
                onClick={() =>
                  setMaterialModalState({ open: true, material: row, prefillRef: null })
                }
                title="Edit raw material specification"
                aria-label={`Edit ${row.materialCode}`}
                className="inline-flex items-center p-1.5 text-slate-600 hover:text-pharma-700 hover:bg-pharma-50 rounded-md transition-colors"
              >
                <Edit2 className="w-3.5 h-3.5" aria-hidden="true" />
              </button>
              <button
                type="button"
                onClick={() =>
                  setConfirmStatusAction({
                    entityType: 'MATERIAL',
                    record: row,
                    targetActive: !row.active,
                  })
                }
                title={row.active ? 'Deactivate raw material' : 'Activate raw material'}
                className={`inline-flex items-center space-x-1 px-2 py-1 text-xs font-semibold rounded-md border transition-colors ${
                  row.active
                    ? 'text-amber-800 bg-amber-50 hover:bg-amber-100 border-amber-200'
                    : 'text-emerald-800 bg-emerald-50 hover:bg-emerald-100 border-emerald-200'
                }`}
              >
                <Power className="w-3 h-3" aria-hidden="true" />
                <span>{row.active ? 'Deactivate' : 'Activate'}</span>
              </button>
            </>
          )}
        </div>
      ),
    },
  ];

  const lotColumns = [
    {
      key: 'lotNumber',
      header: 'Lot Number',
      sortable: true,
      render: (row) => (
        <div>
          <div className="font-mono font-semibold text-slate-900">{row.lotNumber}</div>
          <div className="text-xs text-slate-500">
            {row.supplierName || '—'}
            {row.supplierReference ? ` (${row.supplierReference})` : ''}
          </div>
        </div>
      ),
    },
    {
      key: 'materialCode',
      header: 'Raw Material',
      sortable: true,
      render: (row) => (
        <div>
          <span className="font-mono text-xs font-semibold text-pharma-700">
            {row.materialCode}
          </span>
          <div className="text-sm font-medium text-slate-800">{row.materialName}</div>
        </div>
      ),
    },
    {
      key: 'locationCode',
      header: 'Warehouse / Location',
      sortable: true,
      render: (row) => (
        <div className="text-xs">
          <div className="font-semibold text-slate-800">
            {row.warehouseCode} / {row.locationCode}
          </div>
          <div className="text-slate-500">{row.zoneName || row.warehouseName}</div>
        </div>
      ),
    },
    {
      key: 'availableQuantity',
      header: 'Available / Initial',
      sortable: true,
      align: 'right',
      render: (row) => (
        <div className="tabular-nums">
          <div className="font-mono font-bold text-slate-900">
            {formatQuantityDisplay(row.availableQuantity, row.unit)}
          </div>
          <div className="text-[11px] text-slate-500">
            of {formatQuantityDisplay(row.initialQuantity, row.unit)}
          </div>
        </div>
      ),
    },
    {
      key: 'expiryDate',
      header: 'Received / Expiry',
      sortable: true,
      render: (row) => (
        <div className="text-xs tabular-nums">
          <div className="text-slate-500">Rcvd: {formatCalendarDate(row.receivedDate)}</div>
          <div
            className={
              row.expired
                ? 'text-rose-700 font-bold'
                : row.expiringSoon
                  ? 'text-amber-700 font-semibold'
                  : 'text-slate-800 font-medium'
            }
          >
            Exp: {formatCalendarDate(row.expiryDate)}
            {row.expiringSoon && !row.expired ? ` (${row.daysToExpiry}d)` : ''}
          </div>
        </div>
      ),
    },
    {
      key: 'effectiveStatus',
      header: 'Status',
      sortable: true,
      render: (row) => (
        <StatusBadge status={row.effectiveStatus} domain="inventory" size="xs" showDot />
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      render: (row) => {
        const usable =
          row.effectiveStatus === 'ACTIVE' &&
          !row.expired &&
          Number(row.availableQuantity || 0) > 0;
        return (
          <div className="inline-flex flex-wrap items-center justify-end gap-1.5">
            {canIssue && usable && (
              <button
                type="button"
                onClick={() =>
                  setIssueModalState({
                    open: true,
                    initialOrderId: null,
                    initialMaterialId: row.rawMaterialId,
                    initialLotId: row.id,
                  })
                }
                className="inline-flex items-center space-x-1 px-2.5 py-1 text-xs font-semibold text-indigo-700 bg-indigo-50 hover:bg-indigo-100 border border-indigo-200 rounded-md"
              >
                <ArrowRightLeft className="w-3 h-3" aria-hidden="true" />
                <span>Issue</span>
              </button>
            )}
            {canAdjust && (
              <button
                type="button"
                onClick={() =>
                  setAdjustmentModalState({
                    open: true,
                    initialLot: row,
                    initialActionType:
                      row.effectiveStatus === 'QUARANTINED' ? 'RELEASE_QUARANTINE' : 'DECREASE',
                  })
                }
                className="inline-flex items-center space-x-1 px-2.5 py-1 text-xs font-semibold text-purple-700 bg-purple-50 hover:bg-purple-100 border border-purple-200 rounded-md"
              >
                <SlidersHorizontal className="w-3 h-3" aria-hidden="true" />
                <span>Adjust</span>
              </button>
            )}
          </div>
        );
      },
    },
  ];

  const movementColumns = [
    {
      key: 'occurredAt',
      header: 'Movement / Time',
      sortable: true,
      render: (row) => (
        <div>
          <div className="font-mono text-xs font-semibold text-slate-900">
            {row.movementNumber}
          </div>
          <div className="text-[11px] text-slate-500 tabular-nums">
            {formatAppShortDateTime(row.occurredAt)}
          </div>
        </div>
      ),
    },
    {
      key: 'movementType',
      header: 'Type',
      sortable: true,
      render: (row) => (
        <StatusBadge status={row.movementType} domain="inventory" size="xs" showDot />
      ),
    },
    {
      key: 'materialCode',
      header: 'Material & Lot',
      sortable: true,
      render: (row) => (
        <div className="text-xs">
          <div className="font-semibold text-slate-900">
            <span className="font-mono text-pharma-700">{row.materialCode}</span> &bull;{' '}
            {row.materialName}
          </div>
          <div className="text-slate-500 font-mono">
            Lot: {row.lotNumber} ({row.warehouseCode}/{row.locationCode})
          </div>
        </div>
      ),
    },
    {
      key: 'quantityDelta',
      header: 'Delta (Before -> After)',
      sortable: true,
      align: 'right',
      render: (row) => (
        <div className="font-mono tabular-nums">
          <div
            className={`font-bold ${
              Number(row.quantityDelta) > 0
                ? 'text-emerald-700'
                : Number(row.quantityDelta) < 0
                  ? 'text-rose-700'
                  : 'text-slate-700'
            }`}
          >
            {formatSignedDelta(row.quantityDelta, row.unit)}
          </div>
          <div className="text-[11px] text-slate-500">
            {row.quantityBefore} &rarr; {row.quantityAfter} {row.unit}
          </div>
        </div>
      ),
    },
    {
      key: 'orderNumber',
      header: 'Order / Ref / Reason',
      render: (row) => (
        <div className="text-xs max-w-xs space-y-0.5">
          <div className="flex flex-wrap items-center gap-1.5">
            {row.orderNumber && (
              <button
                type="button"
                onClick={() => navigateToTab('orders', { orderId: row.manufacturingOrderId })}
                className="px-1.5 py-0.5 rounded bg-indigo-50 text-indigo-700 border border-indigo-200 font-mono font-semibold hover:underline"
              >
                {row.orderNumber}
                {row.batchNumber ? ` / ${row.batchNumber}` : ''}
              </button>
            )}
            {row.referenceNumber && (
              <span className="px-1.5 py-0.5 rounded bg-slate-100 text-slate-700 font-mono">
                Ref: {row.referenceNumber}
              </span>
            )}
          </div>
          <p className="text-slate-600 break-words">{row.reason || '—'}</p>
        </div>
      ),
    },
    {
      key: 'performedBy',
      header: 'Actor',
      sortable: true,
      render: (row) => (
        <span className="font-mono text-xs text-slate-700">@{row.performedBy}</span>
      ),
    },
  ];

  return (
    <div data-testid="inventory-management" className="space-y-6">
      <NotificationBanner
        notification={notification}
        onDismiss={() => setNotification(null)}
      />

      <PageHeader
        title="Raw Material Inventory & Warehouse"
        badge="Stock & Lot Traceability"
        description="Manage raw material masters, multi-warehouse storage locations, goods receipts, order-linked material issues, and immutable stock movements."
        actions={
          <>
            <button
              type="button"
              onClick={loadInventoryData}
              disabled={isLoading}
              title="Refresh inventory and warehouse data"
              className="inline-flex items-center space-x-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white hover:bg-slate-50 border border-slate-200 rounded-lg shadow-sm transition-colors disabled:opacity-50"
            >
              <RefreshCw
                className={`w-4 h-4 ${isLoading ? 'animate-spin text-pharma-600' : 'text-slate-500'}`}
                aria-hidden="true"
              />
              <span className="hidden sm:inline">Refresh</span>
            </button>

            {canReceive && (
              <button
                type="button"
                onClick={() => setReceiptModalState({ open: true, initialMaterialId: null })}
                className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-sm font-semibold text-white bg-emerald-600 hover:bg-emerald-700 rounded-lg shadow-sm transition-colors"
              >
                <PackagePlus className="w-4 h-4" aria-hidden="true" />
                <span>Goods Receipt</span>
              </button>
            )}

            {canIssue && (
              <button
                type="button"
                onClick={() =>
                  setIssueModalState({
                    open: true,
                    initialOrderId: null,
                    initialMaterialId: null,
                    initialLotId: null,
                  })
                }
                className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-sm font-semibold text-white bg-indigo-600 hover:bg-indigo-700 rounded-lg shadow-sm transition-colors"
              >
                <ArrowRightLeft className="w-4 h-4" aria-hidden="true" />
                <span>Issue Material</span>
              </button>
            )}

            {canManageMaterials && (
              <button
                type="button"
                onClick={() =>
                  setMaterialModalState({ open: true, material: null, prefillRef: null })
                }
                className="inline-flex items-center space-x-1.5 px-3.5 py-2 text-sm font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors"
              >
                <Plus className="w-4 h-4" aria-hidden="true" />
                <span>Add Material</span>
              </button>
            )}
          </>
        }
      />

      {/* Initial Loading State */}
      {isLoading && !hasLoadedOnce && (
        <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-12 text-center">
          <RefreshCw
            className="w-8 h-8 mx-auto text-pharma-600 animate-spin mb-3"
            aria-hidden="true"
          />
          <p className="text-sm font-medium text-slate-700">
            Loading inventory and warehouse records...
          </p>
          <p className="text-xs text-slate-500 mt-1">
            Fetching raw materials, storage locations, lot balances, and movement ledger
          </p>
        </div>
      )}

      {/* Initial Load Error (No empty state rendered beneath) */}
      {!isLoading && !hasLoadedOnce && error && (
        <InlineAlert
          variant="error"
          title="Unable to load inventory and warehouse records"
          message={error}
          actions={
            <button
              type="button"
              onClick={loadInventoryData}
              className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-rose-900 bg-white hover:bg-rose-100 border border-rose-300 rounded-lg transition-colors"
            >
              <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Retry</span>
            </button>
          }
        />
      )}

      {/* Main Loaded Workspace */}
      {hasLoadedOnce && (
        <>
          {error && (
            <InlineAlert
              variant="error"
              title="Unable to refresh inventory data"
              message={error}
              onDismiss={() => setError(null)}
              actions={
                <button
                  type="button"
                  onClick={loadInventoryData}
                  className="inline-flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold text-rose-900 bg-white hover:bg-rose-100 border border-rose-300 rounded-lg transition-colors"
                >
                  <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>Retry</span>
                </button>
              }
            />
          )}

          {/* KPI Summary Cards */}
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Raw Materials
                </p>
                <p className="text-2xl font-extrabold text-slate-900 mt-1 tabular-nums">
                  {kpiMetrics.totalMaterialsCount}
                </p>
                <p className="text-xs text-slate-500 mt-0.5 tabular-nums">
                  {kpiMetrics.activeMaterialsCount} active &bull;{' '}
                  {kpiMetrics.activeWarehouseCount}/{kpiMetrics.warehouseCount} warehouses
                </p>
              </div>
              <div className="w-10 h-10 rounded-lg bg-pharma-50 text-pharma-600 flex items-center justify-center">
                <Package className="w-5 h-5" aria-hidden="true" />
              </div>
            </div>

            <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Active Lots
                </p>
                <p className="text-2xl font-extrabold text-emerald-600 mt-1 tabular-nums">
                  {kpiMetrics.activeLotCount}
                </p>
                <p className="text-xs text-slate-500 mt-0.5 tabular-nums">
                  {kpiMetrics.storageLocationCount} active storage locations
                </p>
              </div>
              <div className="w-10 h-10 rounded-lg bg-emerald-50 text-emerald-600 flex items-center justify-center">
                <CheckCircle2 className="w-5 h-5" aria-hidden="true" />
              </div>
            </div>

            <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Low / Out of Stock
                </p>
                <p className="text-2xl font-extrabold text-amber-600 mt-1 tabular-nums">
                  {Number(kpiMetrics.lowStockMaterialsCount || 0) +
                    Number(kpiMetrics.outOfStockMaterialsCount || 0)}
                </p>
                <p className="text-xs text-slate-500 mt-0.5 tabular-nums">
                  {kpiMetrics.lowStockMaterialsCount} low &bull; {kpiMetrics.outOfStockMaterialsCount}{' '}
                  out of stock
                </p>
              </div>
              <div className="w-10 h-10 rounded-lg bg-amber-50 text-amber-600 flex items-center justify-center">
                <AlertTriangle className="w-5 h-5" aria-hidden="true" />
              </div>
            </div>

            <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Expiry &amp; Quarantine
                </p>
                <p className="text-2xl font-extrabold text-rose-600 mt-1 tabular-nums">
                  {Number(kpiMetrics.expiringSoonLotCount || 0) +
                    Number(kpiMetrics.quarantinedLotCount || 0) +
                    Number(kpiMetrics.expiredLotCount || 0)}
                </p>
                <p className="text-xs text-slate-500 mt-0.5 tabular-nums">
                  {kpiMetrics.expiringSoonLotCount} expiring &le;30d &bull;{' '}
                  {kpiMetrics.quarantinedLotCount} quarantined &bull; {kpiMetrics.expiredLotCount}{' '}
                  expired
                </p>
              </div>
              <div className="w-10 h-10 rounded-lg bg-rose-50 text-rose-600 flex items-center justify-center">
                <Layers className="w-5 h-5" aria-hidden="true" />
              </div>
            </div>
          </div>

          {/* Unregistered Recipe Materials Quick Link Banner */}
          {canManageMaterials && unregisteredRecipeRefs.length > 0 && subTab === 'MATERIALS' && (
            <div className="bg-indigo-50/70 border border-indigo-200 rounded-xl p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <div className="flex items-start space-x-2.5 min-w-0">
                <FlaskConical className="w-4 h-4 text-indigo-600 mt-0.5 shrink-0" aria-hidden="true" />
                <div className="text-xs text-indigo-950">
                  <span className="font-semibold">
                    {unregisteredRecipeRefs.length} Master Recipe material(s) are not yet registered in Raw Material Inventory:
                  </span>{' '}
                  <span>
                    {unregisteredRecipeRefs
                      .slice(0, 4)
                      .map((r) => `${r.materialName} (${r.unit})`)
                      .join(', ')}
                    {unregisteredRecipeRefs.length > 4
                      ? ` +${unregisteredRecipeRefs.length - 4} more`
                      : ''}
                  </span>
                </div>
              </div>
              <button
                type="button"
                onClick={() =>
                  setMaterialModalState({
                    open: true,
                    material: null,
                    prefillRef: unregisteredRecipeRefs[0],
                  })
                }
                className="px-3 py-1.5 text-xs font-semibold text-indigo-800 bg-white hover:bg-indigo-100 border border-indigo-300 rounded-lg shadow-sm transition-colors shrink-0"
              >
                Register {unregisteredRecipeRefs[0].materialName}
              </button>
            </div>
          )}

          {/* Sub-Tab Switcher */}
          <div
            role="tablist"
            aria-label="Inventory workspace views"
            className="flex flex-wrap items-center gap-1.5 bg-slate-100 p-1 rounded-xl border border-slate-200"
          >
            {SUB_TABS.map((tab) => {
              const Icon = tab.icon;
              const isActive = subTab === tab.id;
              return (
                <button
                  key={tab.id}
                  type="button"
                  role="tab"
                  aria-selected={isActive}
                  onClick={() => handleSwitchSubTab(tab.id)}
                  className={`inline-flex items-center space-x-2 px-3.5 py-2 text-xs sm:text-sm font-medium rounded-lg transition-colors ${
                    isActive
                      ? 'bg-white text-pharma-800 font-semibold shadow-sm'
                      : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  <Icon className="w-4 h-4 shrink-0" aria-hidden="true" />
                  <span>{tab.label}</span>
                </button>
              );
            })}
          </div>

          {/* Search & Filter Toolbar */}
          <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm flex flex-col lg:flex-row lg:items-center justify-between gap-3">
            <div className="relative flex-1 max-w-md min-w-0">
              <label htmlFor={searchInputId} className="sr-only">
                Search inventory records
              </label>
              <Search
                className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2"
                aria-hidden="true"
              />
              <input
                id={searchInputId}
                type="text"
                value={searchTerm}
                onChange={(e) => {
                  setSearchTerm(e.target.value);
                  setCurrentPage(1);
                }}
                placeholder={
                  subTab === 'MATERIALS'
                    ? 'Search materials by code, name, or recipe...'
                    : subTab === 'LOTS'
                      ? 'Search lots by lot #, material, supplier, or location...'
                      : subTab === 'WAREHOUSES'
                        ? 'Search warehouses or storage locations...'
                        : 'Search movements by doc #, order #, lot, or actor...'
                }
                className="w-full pl-9 pr-14 py-2 text-sm rounded-lg border border-slate-300 focus:outline-none focus:ring-2 focus:ring-pharma-200 focus:border-pharma-500 bg-white"
              />
              {searchTerm && (
                <button
                  type="button"
                  onClick={() => {
                    setSearchTerm('');
                    setCurrentPage(1);
                  }}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-xs font-medium text-slate-500 hover:text-slate-700"
                >
                  Clear
                </button>
              )}
            </div>

            {subTab === 'MATERIALS' && (
              <div className="flex flex-wrap items-center gap-3">
                <div className="flex items-center space-x-2">
                  <label
                    htmlFor={secondaryFilterId}
                    className="text-xs font-semibold uppercase tracking-wider text-slate-500"
                  >
                    Type:
                  </label>
                  <select
                    id={secondaryFilterId}
                    value={materialTypeFilter}
                    onChange={(e) => {
                      setMaterialTypeFilter(e.target.value);
                      setCurrentPage(1);
                    }}
                    className="px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white text-slate-700"
                  >
                    <option value="ALL">All Types</option>
                    {MATERIAL_TYPES.map((t) => (
                      <option key={t} value={t}>
                        {t}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="flex items-center space-x-2">
                  <label
                    htmlFor={statusFilterId}
                    className="text-xs font-semibold uppercase tracking-wider text-slate-500"
                  >
                    Stock:
                  </label>
                  <select
                    id={statusFilterId}
                    value={materialStatusFilter}
                    onChange={(e) => {
                      setMaterialStatusFilter(e.target.value);
                      setCurrentPage(1);
                    }}
                    className="px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white text-slate-700"
                  >
                    <option value="ALL">All Statuses</option>
                    <option value="IN_STOCK">IN_STOCK</option>
                    <option value="LOW_STOCK">LOW_STOCK</option>
                    <option value="OUT_OF_STOCK">OUT_OF_STOCK</option>
                    <option value="INACTIVE">INACTIVE</option>
                  </select>
                </div>
              </div>
            )}

            {subTab === 'LOTS' && (
              <div className="flex flex-wrap items-center gap-3">
                <div className="flex items-center space-x-2">
                  <label
                    htmlFor={secondaryFilterId}
                    className="text-xs font-semibold uppercase tracking-wider text-slate-500"
                  >
                    Warehouse:
                  </label>
                  <select
                    id={secondaryFilterId}
                    value={lotWarehouseFilter}
                    onChange={(e) => {
                      setLotWarehouseFilter(e.target.value);
                      setCurrentPage(1);
                    }}
                    className="px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white text-slate-700"
                  >
                    <option value="ALL">All Warehouses</option>
                    {warehouses.map((wh) => (
                      <option key={wh.id} value={String(wh.id)}>
                        {wh.warehouseCode} — {wh.warehouseName}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="flex items-center space-x-2">
                  <label
                    htmlFor={statusFilterId}
                    className="text-xs font-semibold uppercase tracking-wider text-slate-500"
                  >
                    Lot Status:
                  </label>
                  <select
                    id={statusFilterId}
                    value={lotStatusFilter}
                    onChange={(e) => {
                      setLotStatusFilter(e.target.value);
                      setCurrentPage(1);
                    }}
                    className="px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white text-slate-700"
                  >
                    <option value="ALL">All Lots</option>
                    <option value="ACTIVE">ACTIVE</option>
                    <option value="EXPIRING_SOON">Expiring &le; 30 Days</option>
                    <option value="QUARANTINED">QUARANTINED</option>
                    <option value="EXPIRED">EXPIRED</option>
                    <option value="DEPLETED">DEPLETED</option>
                  </select>
                </div>

                {canAdjust && (
                  <button
                    type="button"
                    onClick={() =>
                      setAdjustmentModalState({
                        open: true,
                        initialLot: null,
                        initialActionType: null,
                      })
                    }
                    className="inline-flex items-center space-x-1.5 px-3 py-2 text-xs font-semibold text-purple-800 bg-purple-50 hover:bg-purple-100 border border-purple-200 rounded-lg transition-colors"
                  >
                    <SlidersHorizontal className="w-3.5 h-3.5" aria-hidden="true" />
                    <span>Stock Adjustment</span>
                  </button>
                )}
              </div>
            )}

            {subTab === 'WAREHOUSES' && canManageWh && (
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  onClick={() =>
                    setLocationModalState({ open: true, location: null, defaultWarehouseId: null })
                  }
                  disabled={warehouses.filter((w) => w.active).length === 0}
                  className="inline-flex items-center space-x-1.5 px-3 py-2 text-xs font-semibold text-pharma-700 bg-pharma-50 hover:bg-pharma-100 border border-pharma-200 rounded-lg transition-colors disabled:opacity-50"
                >
                  <MapPin className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>Add Location</span>
                </button>
                <button
                  type="button"
                  onClick={() => setWarehouseModalState({ open: true, warehouse: null })}
                  className="inline-flex items-center space-x-1.5 px-3 py-2 text-xs font-semibold text-white bg-pharma-600 hover:bg-pharma-700 rounded-lg shadow-sm transition-colors"
                >
                  <Plus className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>Add Warehouse</span>
                </button>
              </div>
            )}

            {subTab === 'MOVEMENTS' && (
              <div className="flex items-center space-x-2">
                <label
                  htmlFor={statusFilterId}
                  className="text-xs font-semibold uppercase tracking-wider text-slate-500"
                >
                  Movement Type:
                </label>
                <select
                  id={statusFilterId}
                  value={movementTypeFilter}
                  onChange={(e) => {
                    setMovementTypeFilter(e.target.value);
                    setCurrentPage(1);
                  }}
                  className="px-3 py-2 text-sm rounded-lg border border-slate-300 bg-white text-slate-700"
                >
                  <option value="ALL">All Movements</option>
                  <option value="GOODS_RECEIPT">GOODS_RECEIPT</option>
                  <option value="MATERIAL_ISSUE">MATERIAL_ISSUE</option>
                  <option value="STOCK_ADJUSTMENT">STOCK_ADJUSTMENT</option>
                </select>
              </div>
            )}
          </div>

          {/* Sub-Tab Content */}
          {subTab === 'MATERIALS' && (
            <DataTable
              ariaLabel="Raw materials inventory table"
              columns={materialColumns}
              data={paginatedMaterials.items}
              rowKey={(row) => row.id ?? row.materialCode}
              sortState={sortState}
              onSortChange={(nextSort) => {
                setSortState(nextSort);
                setCurrentPage(1);
              }}
              emptyTitle="No raw materials found"
              emptyDescription={
                materials.length === 0
                  ? 'Register raw materials to begin receiving lots and tracking warehouse stock.'
                  : 'No raw materials match your current search or filter criteria.'
              }
              pagination={
                filteredMaterials.length > 0
                  ? {
                      currentPage: paginatedMaterials.page,
                      pageSize: paginatedMaterials.pageSize,
                      totalItems: paginatedMaterials.totalItems,
                      onPageChange: setCurrentPage,
                      onPageSizeChange: (nextSize) => {
                        setPageSize(nextSize);
                        setCurrentPage(1);
                      },
                      itemLabel: 'materials',
                    }
                  : null
              }
            />
          )}

          {subTab === 'LOTS' && (
            <DataTable
              ariaLabel="Material lots and expiry traceability table"
              columns={lotColumns}
              data={paginatedLots.items}
              rowKey={(row) => row.id ?? row.lotNumber}
              sortState={sortState}
              onSortChange={(nextSort) => {
                setSortState(nextSort);
                setCurrentPage(1);
              }}
              emptyTitle="No material lots found"
              emptyDescription={
                lots.length === 0
                  ? 'Record a Goods Receipt to log material lots with supplier metadata and expiry dates.'
                  : 'No material lots match your current search or filter criteria.'
              }
              pagination={
                filteredLots.length > 0
                  ? {
                      currentPage: paginatedLots.page,
                      pageSize: paginatedLots.pageSize,
                      totalItems: paginatedLots.totalItems,
                      onPageChange: setCurrentPage,
                      onPageSizeChange: (nextSize) => {
                        setPageSize(nextSize);
                        setCurrentPage(1);
                      },
                      itemLabel: 'lots',
                    }
                  : null
              }
            />
          )}

          {subTab === 'WAREHOUSES' && (
            <div className="space-y-4">
              {filteredWarehouses.length === 0 ? (
                <div className="bg-white rounded-xl shadow-sm border border-slate-200 p-10 text-center">
                  <Warehouse className="w-8 h-8 mx-auto text-slate-400 mb-3" aria-hidden="true" />
                  <h3 className="text-base font-semibold text-slate-800">No warehouses found</h3>
                  <p className="text-sm text-slate-500 mt-1">
                    {warehouses.length === 0
                      ? 'Create a warehouse facility and storage bins to organize raw material lots.'
                      : 'No warehouses match your current search filter.'}
                  </p>
                </div>
              ) : (
                filteredWarehouses.map((wh) => {
                  const childLocs = locations.filter((l) => Number(l.warehouseId) === Number(wh.id));
                  return (
                    <div
                      key={wh.id}
                      className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden"
                    >
                      <div className="px-4 sm:px-6 py-4 bg-slate-50 border-b border-slate-200 flex flex-wrap items-center justify-between gap-3">
                        <div className="min-w-0">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="font-mono text-sm font-bold text-pharma-700">
                              {wh.warehouseCode}
                            </span>
                            <h3 className="text-base font-bold text-slate-900">
                              {wh.warehouseName}
                            </h3>
                            <StatusBadge
                              status={wh.active ? 'ACTIVE' : 'INACTIVE'}
                              domain="inventory"
                              size="xs"
                              showDot
                            />
                          </div>
                          <p className="text-xs text-slate-500 mt-0.5">
                            Condition: {wh.storageCondition || 'Standard'} &bull;{' '}
                            {wh.activeLocationCount}/{wh.locationCount} active locations &bull;{' '}
                            {wh.activeLotCount} active lot(s)
                            {wh.locationSummary ? ` • ${wh.locationSummary}` : ''}
                          </p>
                        </div>

                        {canManageWh && (
                          <div className="flex flex-wrap items-center gap-1.5">
                            {wh.active && (
                              <button
                                type="button"
                                onClick={() =>
                                  setLocationModalState({
                                    open: true,
                                    location: null,
                                    defaultWarehouseId: wh.id,
                                  })
                                }
                                className="inline-flex items-center space-x-1 px-2.5 py-1.5 text-xs font-semibold text-pharma-700 bg-white hover:bg-pharma-50 border border-pharma-200 rounded-lg"
                              >
                                <MapPin className="w-3.5 h-3.5" aria-hidden="true" />
                                <span>Add Location</span>
                              </button>
                            )}
                            <button
                              type="button"
                              onClick={() =>
                                setWarehouseModalState({ open: true, warehouse: wh })
                              }
                              aria-label={`Edit warehouse ${wh.warehouseCode}`}
                              className="inline-flex items-center space-x-1 px-2.5 py-1.5 text-xs font-semibold text-slate-700 bg-white hover:bg-slate-100 border border-slate-200 rounded-lg"
                            >
                              <Edit2 className="w-3.5 h-3.5" aria-hidden="true" />
                              <span>Edit</span>
                            </button>
                            <button
                              type="button"
                              onClick={() =>
                                setConfirmStatusAction({
                                  entityType: 'WAREHOUSE',
                                  record: wh,
                                  targetActive: !wh.active,
                                })
                              }
                              className={`inline-flex items-center space-x-1 px-2.5 py-1.5 text-xs font-semibold rounded-lg border ${
                                wh.active
                                  ? 'text-amber-800 bg-amber-50 hover:bg-amber-100 border-amber-200'
                                  : 'text-emerald-800 bg-emerald-50 hover:bg-emerald-100 border-emerald-200'
                              }`}
                            >
                              <Power className="w-3 h-3" aria-hidden="true" />
                              <span>{wh.active ? 'Deactivate' : 'Activate'}</span>
                            </button>
                          </div>
                        )}
                      </div>

                      {childLocs.length === 0 ? (
                        <div className="p-5 text-xs text-slate-500">
                          No storage locations defined inside this warehouse yet.
                        </div>
                      ) : (
                        <div className="overflow-x-auto">
                          <table className="w-full text-left border-collapse text-xs">
                            <thead>
                              <tr className="bg-slate-50/60 border-b border-slate-200 text-slate-500 font-semibold uppercase tracking-wider">
                                <th className="py-2.5 px-4">Location Code</th>
                                <th className="py-2.5 px-4">Zone</th>
                                <th className="py-2.5 px-4">Aisle / Rack / Bin</th>
                                <th className="py-2.5 px-4 text-right">Active Lots</th>
                                <th className="py-2.5 px-4">Status</th>
                                {canManageWh && <th className="py-2.5 px-4 text-right">Actions</th>}
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                              {childLocs.map((loc) => (
                                <tr key={loc.id} className="hover:bg-slate-50/50">
                                  <td className="py-2.5 px-4 font-mono font-semibold text-slate-900">
                                    {loc.locationCode}
                                  </td>
                                  <td className="py-2.5 px-4 text-slate-700">
                                    {loc.zoneName || '—'}
                                  </td>
                                  <td className="py-2.5 px-4 font-mono text-slate-600">
                                    {[loc.aisle, loc.rack, loc.bin].filter(Boolean).join(' / ') ||
                                      '—'}
                                  </td>
                                  <td className="py-2.5 px-4 text-right font-mono font-semibold text-slate-900 tabular-nums">
                                    {loc.activeLotCount}
                                  </td>
                                  <td className="py-2.5 px-4">
                                    <StatusBadge
                                      status={loc.active ? 'ACTIVE' : 'INACTIVE'}
                                      domain="inventory"
                                      size="xs"
                                      showDot
                                    />
                                  </td>
                                  {canManageWh && (
                                    <td className="py-2.5 px-4 text-right whitespace-nowrap space-x-1.5">
                                      <button
                                        type="button"
                                        onClick={() =>
                                          setLocationModalState({
                                            open: true,
                                            location: loc,
                                            defaultWarehouseId: wh.id,
                                          })
                                        }
                                        className="inline-flex items-center space-x-1 px-2 py-1 text-[11px] font-semibold text-slate-700 bg-white hover:bg-slate-100 border border-slate-200 rounded"
                                      >
                                        <Edit2 className="w-3 h-3" aria-hidden="true" />
                                        <span>Edit</span>
                                      </button>
                                      <button
                                        type="button"
                                        onClick={() =>
                                          setConfirmStatusAction({
                                            entityType: 'LOCATION',
                                            record: loc,
                                            targetActive: !loc.active,
                                          })
                                        }
                                        className={`inline-flex items-center space-x-1 px-2 py-1 text-[11px] font-semibold rounded border ${
                                          loc.active
                                            ? 'text-amber-800 bg-amber-50 hover:bg-amber-100 border-amber-200'
                                            : 'text-emerald-800 bg-emerald-50 hover:bg-emerald-100 border-emerald-200'
                                        }`}
                                      >
                                        <span>{loc.active ? 'Deactivate' : 'Activate'}</span>
                                      </button>
                                    </td>
                                  )}
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  );
                })
              )}
            </div>
          )}

          {subTab === 'MOVEMENTS' && (
            <DataTable
              ariaLabel="Immutable stock movement ledger"
              columns={movementColumns}
              data={paginatedMovements.items}
              rowKey={(row) => row.id ?? row.movementNumber}
              sortState={sortState}
              onSortChange={(nextSort) => {
                setSortState(nextSort);
                setCurrentPage(1);
              }}
              emptyTitle="No stock movements recorded"
              emptyDescription={
                movements.length === 0
                  ? 'Goods receipts, manufacturing material issues, and controlled adjustments will appear here as an append-only ledger.'
                  : 'No stock movements match your current search or filter criteria.'
              }
              pagination={
                filteredMovements.length > 0
                  ? {
                      currentPage: paginatedMovements.page,
                      pageSize: paginatedMovements.pageSize,
                      totalItems: paginatedMovements.totalItems,
                      onPageChange: setCurrentPage,
                      onPageSizeChange: (nextSize) => {
                        setPageSize(nextSize);
                        setCurrentPage(1);
                      },
                      itemLabel: 'movements',
                    }
                  : null
              }
            />
          )}
        </>
      )}

      {/* Modals & Detail Drawer */}
      <RawMaterialModal
        isOpen={materialModalState.open}
        material={materialModalState.material}
        prefillReference={materialModalState.prefillRef}
        recipeReferences={recipeReferences}
        onClose={() => {
          if (!isSavingMaterial) {
            setMaterialModalState({ open: false, material: null, prefillRef: null });
          }
        }}
        onSave={handleSaveRawMaterial}
        isSubmitting={isSavingMaterial}
      />

      <WarehouseModal
        isOpen={warehouseModalState.open}
        warehouse={warehouseModalState.warehouse}
        onClose={() => {
          if (!isSavingWarehouse) {
            setWarehouseModalState({ open: false, warehouse: null });
          }
        }}
        onSave={handleSaveWarehouse}
        isSubmitting={isSavingWarehouse}
      />

      <StorageLocationModal
        isOpen={locationModalState.open}
        location={locationModalState.location}
        warehouses={warehouses}
        defaultWarehouseId={locationModalState.defaultWarehouseId}
        onClose={() => {
          if (!isSavingLocation) {
            setLocationModalState({
              open: false,
              location: null,
              defaultWarehouseId: null,
            });
          }
        }}
        onSave={handleSaveStorageLocation}
        isSubmitting={isSavingLocation}
      />

      <GoodsReceiptModal
        isOpen={receiptModalState.open}
        materials={materials}
        warehouses={warehouses}
        locations={locations}
        initialMaterialId={receiptModalState.initialMaterialId}
        onClose={() => {
          if (!isSavingReceipt) {
            setReceiptModalState({ open: false, initialMaterialId: null });
          }
        }}
        onSave={handleReceiveGoods}
        isSubmitting={isSavingReceipt}
      />

      <MaterialIssueModal
        isOpen={issueModalState.open}
        materials={materials}
        lots={lots}
        initialOrderId={issueModalState.initialOrderId}
        initialMaterialId={issueModalState.initialMaterialId}
        initialLotId={issueModalState.initialLotId}
        onClose={() => {
          if (!isSavingIssue) {
            setIssueModalState({
              open: false,
              initialOrderId: null,
              initialMaterialId: null,
              initialLotId: null,
            });
          }
        }}
        onSave={handleIssueMaterial}
        isSubmitting={isSavingIssue}
      />

      <StockAdjustmentModal
        isOpen={adjustmentModalState.open}
        lots={lots}
        initialLot={adjustmentModalState.initialLot}
        initialActionType={adjustmentModalState.initialActionType}
        onClose={() => {
          if (!isSavingAdjustment) {
            setAdjustmentModalState({
              open: false,
              initialLot: null,
              initialActionType: null,
            });
          }
        }}
        onSave={handleAdjustStock}
        isSubmitting={isSavingAdjustment}
      />

      <MaterialDetailDrawer
        isOpen={Boolean(inspectedMaterial)}
        material={inspectedMaterial}
        lots={lots}
        movements={movements}
        canReceive={canReceive}
        canIssue={canIssue}
        canAdjust={canAdjust}
        onClose={() => setInspectedMaterialId(null)}
        onReceiveGoods={(mat) =>
          setReceiptModalState({ open: true, initialMaterialId: mat.id })
        }
        onIssueFromLot={(lot) =>
          setIssueModalState({
            open: true,
            initialOrderId: null,
            initialMaterialId: lot.rawMaterialId,
            initialLotId: lot.id,
          })
        }
        onAdjustLot={(lot) =>
          setAdjustmentModalState({
            open: true,
            initialLot: lot,
            initialActionType:
              lot.effectiveStatus === 'QUARANTINED' ? 'RELEASE_QUARANTINE' : 'DECREASE',
          })
        }
      />

      {confirmStatusAction && (
        <ConfirmModal
          isOpen={Boolean(confirmStatusAction)}
          title={`${confirmStatusAction.targetActive ? 'Activate' : 'Deactivate'} ${
            confirmStatusAction.entityType === 'MATERIAL'
              ? 'Raw Material'
              : confirmStatusAction.entityType === 'WAREHOUSE'
                ? 'Warehouse'
                : 'Storage Location'
          }`}
          message={
            confirmStatusAction.targetActive
              ? 'Are you sure you want to reactivate this record for active inventory operations?'
              : 'Are you sure you want to deactivate this record? Existing historical lots and movements remain preserved, while new goods receipts or issues against it will be blocked.'
          }
          confirmText={confirmStatusAction.targetActive ? 'Activate' : 'Deactivate'}
          confirmVariant={confirmStatusAction.targetActive ? 'primary' : 'warning'}
          onConfirm={handleConfirmToggleStatus}
          onClose={() => {
            if (!isUpdatingStatus) setConfirmStatusAction(null);
          }}
          isLoading={isUpdatingStatus}
        />
      )}
    </div>
  );
}

