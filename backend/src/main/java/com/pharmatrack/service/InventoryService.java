package com.pharmatrack.service;

import com.pharmatrack.dto.GoodsReceiptRequestDto;
import com.pharmatrack.dto.InventoryStatusUpdateRequestDto;
import com.pharmatrack.dto.InventorySummaryDto;
import com.pharmatrack.dto.MaterialIssueRequestDto;
import com.pharmatrack.dto.MaterialLotResponseDto;
import com.pharmatrack.dto.OrderMaterialRequirementDto;
import com.pharmatrack.dto.OrderMaterialRequirementsResponseDto;
import com.pharmatrack.dto.RawMaterialRequestDto;
import com.pharmatrack.dto.RawMaterialResponseDto;
import com.pharmatrack.dto.RecipeMaterialReferenceDto;
import com.pharmatrack.dto.StockAdjustmentRequestDto;
import com.pharmatrack.dto.StockMovementResponseDto;
import com.pharmatrack.dto.StorageLocationRequestDto;
import com.pharmatrack.dto.StorageLocationResponseDto;
import com.pharmatrack.dto.WarehouseRequestDto;
import com.pharmatrack.dto.WarehouseResponseDto;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.MaterialLot;
import com.pharmatrack.entity.MaterialLotStatus;
import com.pharmatrack.entity.MaterialStockStatus;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.RawMaterial;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeMaterial;
import com.pharmatrack.entity.StockAdjustmentType;
import com.pharmatrack.entity.StockMovement;
import com.pharmatrack.entity.StockMovementType;
import com.pharmatrack.entity.StorageLocation;
import com.pharmatrack.entity.Warehouse;
import com.pharmatrack.exception.DuplicateResourceException;
import com.pharmatrack.exception.InvalidInventoryOperationException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import com.pharmatrack.repository.MaterialLotRepository;
import com.pharmatrack.repository.RawMaterialRepository;
import com.pharmatrack.repository.RecipeMaterialRepository;
import com.pharmatrack.repository.StockMovementRepository;
import com.pharmatrack.repository.StorageLocationRepository;
import com.pharmatrack.repository.WarehouseRepository;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.util.AppTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Service managing Raw Material Master, Warehouses, Storage Locations, Goods Receipts,
 * lot-traceable Material Issues against Manufacturing Orders, controlled Stock Adjustments,
 * immutable Stock Movements, and append-only Audit Trail recording.
 */
@Service
@Transactional
public class InventoryService {

    public static final String ENTITY_RAW_MATERIAL = "RAW_MATERIAL";
    public static final String ENTITY_WAREHOUSE = "WAREHOUSE";
    public static final String ENTITY_STORAGE_LOCATION = "STORAGE_LOCATION";
    public static final String ENTITY_MATERIAL_LOT = "MATERIAL_LOT";
    public static final int EXPIRING_SOON_DAYS = 30;

    private final RawMaterialRepository rawMaterialRepository;
    private final WarehouseRepository warehouseRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final MaterialLotRepository materialLotRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ManufacturingOrderRepository orderRepository;
    private final BatchRepository batchRepository;
    private final RecipeMaterialRepository recipeMaterialRepository;
    private final AuditTrailService auditTrailService;
    private final AuthenticatedActorResolver authenticatedActorResolver;

    @Autowired
    public InventoryService(RawMaterialRepository rawMaterialRepository,
                            WarehouseRepository warehouseRepository,
                            StorageLocationRepository storageLocationRepository,
                            MaterialLotRepository materialLotRepository,
                            StockMovementRepository stockMovementRepository,
                            ManufacturingOrderRepository orderRepository,
                            BatchRepository batchRepository,
                            RecipeMaterialRepository recipeMaterialRepository,
                            AuditTrailService auditTrailService,
                            AuthenticatedActorResolver authenticatedActorResolver) {
        this.rawMaterialRepository = rawMaterialRepository;
        this.warehouseRepository = warehouseRepository;
        this.storageLocationRepository = storageLocationRepository;
        this.materialLotRepository = materialLotRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.orderRepository = orderRepository;
        this.batchRepository = batchRepository;
        this.recipeMaterialRepository = recipeMaterialRepository;
        this.auditTrailService = auditTrailService;
        this.authenticatedActorResolver = authenticatedActorResolver != null
                ? authenticatedActorResolver
                : new AuthenticatedActorResolver();
    }

    // -------------------------------------------------------------------------
    // Dashboard & Recipe Reference Queries
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public InventorySummaryDto getDashboardSummary() {
        List<RawMaterialResponseDto> materials = getAllRawMaterials();
        List<MaterialLot> lots = materialLotRepository.findAllWithDetails();
        List<Warehouse> warehouses = warehouseRepository.findAll();
        List<StorageLocation> locations = storageLocationRepository.findAll();
        LocalDate today = AppTime.nowDate();
        LocalDate soonThreshold = today.plusDays(EXPIRING_SOON_DAYS);

        InventorySummaryDto dto = new InventorySummaryDto();
        dto.setTotalMaterialsCount(materials.size());
        dto.setActiveMaterialsCount((int) materials.stream().filter(RawMaterialResponseDto::isActive).count());
        dto.setInStockMaterialsCount((int) materials.stream()
                .filter(m -> m.getStockStatus() == MaterialStockStatus.IN_STOCK).count());
        dto.setLowStockMaterialsCount((int) materials.stream()
                .filter(m -> m.getStockStatus() == MaterialStockStatus.LOW_STOCK).count());
        dto.setOutOfStockMaterialsCount((int) materials.stream()
                .filter(m -> m.getStockStatus() == MaterialStockStatus.OUT_OF_STOCK).count());

        dto.setWarehouseCount(warehouses.size());
        dto.setActiveWarehouseCount((int) warehouses.stream().filter(Warehouse::isActive).count());
        dto.setStorageLocationCount((int) locations.stream().filter(StorageLocation::isActive).count());

        int activeLots = 0;
        int quarantinedLots = 0;
        int expiringSoonLots = 0;
        int expiredLots = 0;
        BigDecimal totalAvailable = BigDecimal.ZERO;

        for (MaterialLot lot : lots) {
            boolean dateExpired = isDateExpired(lot, today);
            BigDecimal qty = lot.getAvailableQuantity() != null ? lot.getAvailableQuantity() : BigDecimal.ZERO;
            if (lot.getStatus() == MaterialLotStatus.EXPIRED || (dateExpired && qty.compareTo(BigDecimal.ZERO) > 0)) {
                expiredLots += 1;
            } else if (lot.getStatus() == MaterialLotStatus.QUARANTINED) {
                quarantinedLots += 1;
            } else if (lot.getStatus() == MaterialLotStatus.ACTIVE && qty.compareTo(BigDecimal.ZERO) > 0) {
                activeLots += 1;
                totalAvailable = totalAvailable.add(qty);
                if (lot.getExpiryDate() != null && !lot.getExpiryDate().isAfter(soonThreshold)) {
                    expiringSoonLots += 1;
                }
            }
        }

        dto.setActiveLotCount(activeLots);
        dto.setQuarantinedLotCount(quarantinedLots);
        dto.setExpiringSoonLotCount(expiringSoonLots);
        dto.setExpiredLotCount(expiredLots);
        dto.setTotalAvailableUnits(totalAvailable);
        dto.setTotalMovementsCount(stockMovementRepository.count());
        return dto;
    }

    @Transactional(readOnly = true)
    public List<RecipeMaterialReferenceDto> getRecipeMaterialReferences() {
        List<RecipeMaterial> recipeMaterials = recipeMaterialRepository.findAllWithRecipeAndProduct();
        List<RawMaterial> registered = rawMaterialRepository.findAll();

        Map<String, RawMaterial> byNormalizedName = new LinkedHashMap<>();
        for (RawMaterial rm : registered) {
            if (rm.getMaterialName() != null) {
                byNormalizedName.putIfAbsent(normalizeKey(rm.getMaterialName()), rm);
            }
        }

        Map<String, RecipeMaterialReferenceDto> grouped = new LinkedHashMap<>();
        for (RecipeMaterial item : recipeMaterials) {
            if (item.getMaterialName() == null || item.getMaterialName().trim().isEmpty()) {
                continue;
            }
            String key = normalizeKey(item.getMaterialName());
            RecipeMaterialReferenceDto ref = grouped.computeIfAbsent(key, k -> {
                RecipeMaterialReferenceDto created = new RecipeMaterialReferenceDto();
                created.setMaterialName(item.getMaterialName().trim());
                created.setUnit(item.getUnit() != null ? item.getUnit().trim() : "");
                RawMaterial matched = byNormalizedName.get(key);
                if (matched != null) {
                    created.setRegisteredInInventory(true);
                    created.setRawMaterialId(matched.getId());
                    created.setRawMaterialCode(matched.getMaterialCode());
                }
                return created;
            });

            if (item.getRecipe() != null) {
                String recipeLabel = item.getRecipe().getRecipeCode() + " (v" + item.getRecipe().getVersion() + ")";
                if (!ref.getRecipeCodes().contains(recipeLabel)) {
                    ref.getRecipeCodes().add(recipeLabel);
                }
                if (item.getRecipe().getProduct() != null && item.getRecipe().getProduct().getProductCode() != null) {
                    String prodCode = item.getRecipe().getProduct().getProductCode();
                    if (!ref.getProductCodes().contains(prodCode)) {
                        ref.getProductCodes().add(prodCode);
                    }
                }
            }
        }

        return new ArrayList<>(grouped.values());
    }

    // -------------------------------------------------------------------------
    // Raw Material Master
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<RawMaterialResponseDto> getAllRawMaterials() {
        List<RawMaterial> materials = rawMaterialRepository.findAllByOrderByMaterialNameAsc();
        List<MaterialLot> allLots = materialLotRepository.findAllWithDetails();
        List<RecipeMaterial> allRecipeMaterials = recipeMaterialRepository.findAllWithRecipeAndProduct();

        Map<Long, List<MaterialLot>> lotsByMaterial = new LinkedHashMap<>();
        for (MaterialLot lot : allLots) {
            if (lot.getRawMaterial() != null) {
                lotsByMaterial.computeIfAbsent(lot.getRawMaterial().getId(), k -> new ArrayList<>()).add(lot);
            }
        }

        Map<String, Set<String>> recipeCodesByMaterialKey = buildRecipeLinksByMaterialKey(allRecipeMaterials);
        LocalDate today = AppTime.nowDate();

        return materials.stream()
                .map(mat -> mapToRawMaterialResponseDto(
                        mat,
                        lotsByMaterial.getOrDefault(mat.getId(), List.of()),
                        recipeCodesByMaterialKey.getOrDefault(normalizeKey(mat.getMaterialName()), Set.of()),
                        today
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public RawMaterialResponseDto getRawMaterialById(Long id) {
        RawMaterial material = rawMaterialRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Raw material not found with id: " + id));
        List<MaterialLot> lots = materialLotRepository.findByRawMaterialIdWithDetails(id);
        Map<String, Set<String>> recipeLinks = buildRecipeLinksByMaterialKey(recipeMaterialRepository.findAllWithRecipeAndProduct());
        return mapToRawMaterialResponseDto(
                material,
                lots,
                recipeLinks.getOrDefault(normalizeKey(material.getMaterialName()), Set.of()),
                AppTime.nowDate()
        );
    }

    public RawMaterialResponseDto createRawMaterial(RawMaterialRequestDto requestDto) {
        String actor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null) {
            throw new InvalidInventoryOperationException("Raw material payload cannot be null");
        }

        String code = normalizeCode(requestDto.getMaterialCode());
        String name = trimRequired(requestDto.getMaterialName(), "Material name");
        String unit = trimRequired(requestDto.getUnit(), "Unit");

        if (rawMaterialRepository.existsByMaterialCodeIgnoreCase(code)) {
            throw new DuplicateResourceException("Raw material with code '" + code + "' already exists");
        }
        if (rawMaterialRepository.existsByMaterialNameIgnoreCase(name)) {
            throw new DuplicateResourceException("Raw material with name '" + name + "' already exists");
        }
        if (requestDto.getMinimumStockLevel() == null || requestDto.getMinimumStockLevel().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidInventoryOperationException("Minimum stock level cannot be negative");
        }

        RawMaterial material = new RawMaterial(
                code,
                name,
                requestDto.getMaterialType(),
                unit,
                requestDto.getMinimumStockLevel(),
                trimOptional(requestDto.getDescription()),
                requestDto.getActive() == null || requestDto.getActive()
        );

        RawMaterial saved = rawMaterialRepository.saveAndFlush(material);
        auditTrailService.recordAudit(
                ENTITY_RAW_MATERIAL,
                saved.getId(),
                saved.getMaterialCode(),
                null,
                "RAW_MATERIAL_CREATED",
                actor,
                AppTime.nowDateTime(),
                truncateReason("Created raw material " + saved.getMaterialCode() + " (" + saved.getMaterialName() + ")"),
                null,
                buildRawMaterialSnapshot(saved)
        );

        return getRawMaterialById(saved.getId());
    }

    public RawMaterialResponseDto updateRawMaterial(Long id, RawMaterialRequestDto requestDto) {
        String actor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null) {
            throw new InvalidInventoryOperationException("Raw material payload cannot be null");
        }

        RawMaterial existing = rawMaterialRepository.findByIdWithLock(id)
                .orElseThrow(() -> new ResourceNotFoundException("Raw material not found with id: " + id));

        String newCode = normalizeCode(requestDto.getMaterialCode());
        String newName = trimRequired(requestDto.getMaterialName(), "Material name");
        String newUnit = trimRequired(requestDto.getUnit(), "Unit");

        rawMaterialRepository.findByMaterialCodeIgnoreCase(newCode).ifPresent(conflict -> {
            if (!Objects.equals(conflict.getId(), existing.getId())) {
                throw new DuplicateResourceException("Raw material with code '" + newCode + "' already exists");
            }
        });
        rawMaterialRepository.findByMaterialNameIgnoreCase(newName).ifPresent(conflict -> {
            if (!Objects.equals(conflict.getId(), existing.getId())) {
                throw new DuplicateResourceException("Raw material with name '" + newName + "' already exists");
            }
        });

        if (requestDto.getMinimumStockLevel() == null || requestDto.getMinimumStockLevel().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidInventoryOperationException("Minimum stock level cannot be negative");
        }

        List<MaterialLot> existingLots = materialLotRepository.findByRawMaterialIdWithDetails(existing.getId());
        if (!existingLots.isEmpty() && !existing.getUnit().trim().equalsIgnoreCase(newUnit)) {
            throw new InvalidInventoryOperationException(
                    "Cannot change unit of measurement from '" + existing.getUnit() + "' to '" + newUnit
                            + "' for raw material '" + existing.getMaterialCode() + "' because material lots already exist");
        }

        Map<String, Object> beforeSnapshot = buildRawMaterialSnapshot(existing);

        existing.setMaterialCode(newCode);
        existing.setMaterialName(newName);
        existing.setMaterialType(requestDto.getMaterialType());
        existing.setUnit(newUnit);
        existing.setMinimumStockLevel(requestDto.getMinimumStockLevel());
        existing.setDescription(trimOptional(requestDto.getDescription()));
        if (requestDto.getActive() != null) {
            existing.setActive(requestDto.getActive());
        }

        RawMaterial saved = rawMaterialRepository.saveAndFlush(existing);
        auditTrailService.recordAudit(
                ENTITY_RAW_MATERIAL,
                saved.getId(),
                saved.getMaterialCode(),
                null,
                "RAW_MATERIAL_UPDATED",
                actor,
                AppTime.nowDateTime(),
                truncateReason("Updated raw material " + saved.getMaterialCode() + " (" + saved.getMaterialName() + ")"),
                beforeSnapshot,
                buildRawMaterialSnapshot(saved)
        );

        return getRawMaterialById(saved.getId());
    }

    public RawMaterialResponseDto updateRawMaterialStatus(Long id, InventoryStatusUpdateRequestDto requestDto) {
        String actor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null || requestDto.getActive() == null) {
            throw new InvalidInventoryOperationException("Active status flag is mandatory");
        }

        RawMaterial material = rawMaterialRepository.findByIdWithLock(id)
                .orElseThrow(() -> new ResourceNotFoundException("Raw material not found with id: " + id));

        boolean targetActive = requestDto.getActive();
        if (material.isActive() == targetActive) {
            return getRawMaterialById(material.getId());
        }

        Map<String, Object> beforeSnapshot = buildRawMaterialSnapshot(material);
        material.setActive(targetActive);
        RawMaterial saved = rawMaterialRepository.saveAndFlush(material);

        auditTrailService.recordAudit(
                ENTITY_RAW_MATERIAL,
                saved.getId(),
                saved.getMaterialCode(),
                null,
                targetActive ? "RAW_MATERIAL_ACTIVATED" : "RAW_MATERIAL_DEACTIVATED",
                actor,
                AppTime.nowDateTime(),
                truncateReason((targetActive ? "Activated" : "Deactivated") + " raw material " + saved.getMaterialCode()),
                beforeSnapshot,
                buildRawMaterialSnapshot(saved)
        );

        return getRawMaterialById(saved.getId());
    }

    // -------------------------------------------------------------------------
    // Warehouses & Storage Locations
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<WarehouseResponseDto> getAllWarehouses() {
        List<Warehouse> warehouses = warehouseRepository.findAllByOrderByWarehouseNameAsc();
        List<StorageLocation> allLocations = storageLocationRepository.findAllWithWarehouse();
        List<MaterialLot> allLots = materialLotRepository.findAllWithDetails();
        LocalDate today = AppTime.nowDate();

        Map<Long, List<StorageLocation>> locsByWh = new LinkedHashMap<>();
        for (StorageLocation loc : allLocations) {
            if (loc.getWarehouse() != null) {
                locsByWh.computeIfAbsent(loc.getWarehouse().getId(), k -> new ArrayList<>()).add(loc);
            }
        }

        Map<Long, Integer> activeLotCountsByWh = new LinkedHashMap<>();
        for (MaterialLot lot : allLots) {
            if (lot.getWarehouse() != null && isUsableActiveLot(lot, today)) {
                activeLotCountsByWh.merge(lot.getWarehouse().getId(), 1, Integer::sum);
            }
        }

        return warehouses.stream()
                .map(wh -> mapToWarehouseDto(
                        wh,
                        locsByWh.getOrDefault(wh.getId(), List.of()),
                        activeLotCountsByWh.getOrDefault(wh.getId(), 0)
                ))
                .toList();
    }

    public WarehouseResponseDto createWarehouse(WarehouseRequestDto requestDto) {
        String actor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null) {
            throw new InvalidInventoryOperationException("Warehouse payload cannot be null");
        }
        String code = normalizeCode(requestDto.getWarehouseCode());
        String name = trimRequired(requestDto.getWarehouseName(), "Warehouse name");

        if (warehouseRepository.existsByWarehouseCodeIgnoreCase(code)) {
            throw new DuplicateResourceException("Warehouse with code '" + code + "' already exists");
        }

        Warehouse warehouse = new Warehouse(
                code,
                name,
                trimOptional(requestDto.getLocationSummary()),
                trimOptional(requestDto.getStorageCondition()),
                requestDto.getActive() == null || requestDto.getActive()
        );

        Warehouse saved = warehouseRepository.saveAndFlush(warehouse);
        auditTrailService.recordAudit(
                ENTITY_WAREHOUSE,
                saved.getId(),
                saved.getWarehouseCode(),
                null,
                "WAREHOUSE_CREATED",
                actor,
                AppTime.nowDateTime(),
                truncateReason("Created warehouse " + saved.getWarehouseCode() + " (" + saved.getWarehouseName() + ")"),
                null,
                buildWarehouseSnapshot(saved)
        );

        return mapToWarehouseDto(saved, List.of(), 0);
    }

    public WarehouseResponseDto updateWarehouse(Long id, WarehouseRequestDto requestDto) {
        String actor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null) {
            throw new InvalidInventoryOperationException("Warehouse payload cannot be null");
        }

        Warehouse existing = warehouseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with id: " + id));

        String newCode = normalizeCode(requestDto.getWarehouseCode());
        String newName = trimRequired(requestDto.getWarehouseName(), "Warehouse name");

        warehouseRepository.findByWarehouseCodeIgnoreCase(newCode).ifPresent(conflict -> {
            if (!Objects.equals(conflict.getId(), existing.getId())) {
                throw new DuplicateResourceException("Warehouse with code '" + newCode + "' already exists");
            }
        });

        Map<String, Object> beforeSnapshot = buildWarehouseSnapshot(existing);

        existing.setWarehouseCode(newCode);
        existing.setWarehouseName(newName);
        existing.setLocationSummary(trimOptional(requestDto.getLocationSummary()));
        existing.setStorageCondition(trimOptional(requestDto.getStorageCondition()));
        if (requestDto.getActive() != null) {
            existing.setActive(requestDto.getActive());
        }

        Warehouse saved = warehouseRepository.saveAndFlush(existing);
        auditTrailService.recordAudit(
                ENTITY_WAREHOUSE,
                saved.getId(),
                saved.getWarehouseCode(),
                null,
                "WAREHOUSE_UPDATED",
                actor,
                AppTime.nowDateTime(),
                truncateReason("Updated warehouse " + saved.getWarehouseCode() + " (" + saved.getWarehouseName() + ")"),
                beforeSnapshot,
                buildWarehouseSnapshot(saved)
        );

        return buildSingleWarehouseDto(saved);
    }

    public WarehouseResponseDto updateWarehouseStatus(Long id, InventoryStatusUpdateRequestDto requestDto) {
        String actor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null || requestDto.getActive() == null) {
            throw new InvalidInventoryOperationException("Active status flag is mandatory");
        }

        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with id: " + id));

        boolean targetActive = requestDto.getActive();
        if (warehouse.isActive() == targetActive) {
            return buildSingleWarehouseDto(warehouse);
        }

        Map<String, Object> beforeSnapshot = buildWarehouseSnapshot(warehouse);
        warehouse.setActive(targetActive);
        Warehouse saved = warehouseRepository.saveAndFlush(warehouse);

        auditTrailService.recordAudit(
                ENTITY_WAREHOUSE,
                saved.getId(),
                saved.getWarehouseCode(),
                null,
                targetActive ? "WAREHOUSE_ACTIVATED" : "WAREHOUSE_DEACTIVATED",
                actor,
                AppTime.nowDateTime(),
                truncateReason((targetActive ? "Activated" : "Deactivated") + " warehouse " + saved.getWarehouseCode()),
                beforeSnapshot,
                buildWarehouseSnapshot(saved)
        );

        return buildSingleWarehouseDto(saved);
    }

    @Transactional(readOnly = true)
    public List<StorageLocationResponseDto> getAllStorageLocations(Long warehouseId) {
        List<StorageLocation> locations;
        if (warehouseId != null) {
            if (!warehouseRepository.existsById(warehouseId)) {
                throw new ResourceNotFoundException("Warehouse not found with id: " + warehouseId);
            }
            locations = storageLocationRepository.findByWarehouseIdOrderByLocationCodeAsc(warehouseId);
        } else {
            locations = storageLocationRepository.findAllWithWarehouse();
        }

        List<MaterialLot> allLots = materialLotRepository.findAllWithDetails();
        LocalDate today = AppTime.nowDate();
        Map<Long, Integer> activeLotsByLoc = new LinkedHashMap<>();
        for (MaterialLot lot : allLots) {
            if (lot.getStorageLocation() != null && isUsableActiveLot(lot, today)) {
                activeLotsByLoc.merge(lot.getStorageLocation().getId(), 1, Integer::sum);
            }
        }

        return locations.stream()
                .map(loc -> mapToLocationDto(loc, activeLotsByLoc.getOrDefault(loc.getId(), 0)))
                .toList();
    }

    public StorageLocationResponseDto createStorageLocation(Long warehouseId, StorageLocationRequestDto requestDto) {
        String actor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null) {
            throw new InvalidInventoryOperationException("Storage location payload cannot be null");
        }
        Long targetWarehouseId = warehouseId != null ? warehouseId : requestDto.getWarehouseId();
        if (targetWarehouseId == null) {
            throw new InvalidInventoryOperationException("Warehouse ID is mandatory for storage location");
        }

        Warehouse warehouse = warehouseRepository.findById(targetWarehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with id: " + targetWarehouseId));
        if (!warehouse.isActive()) {
            throw new InvalidInventoryOperationException(
                    "Cannot add storage location to inactive warehouse '" + warehouse.getWarehouseCode() + "'");
        }

        String locationCode = normalizeCode(requestDto.getLocationCode());
        if (storageLocationRepository.existsByWarehouseIdAndLocationCodeIgnoreCase(warehouse.getId(), locationCode)) {
            throw new DuplicateResourceException(
                    "Storage location '" + locationCode + "' already exists in warehouse '" + warehouse.getWarehouseCode() + "'");
        }

        StorageLocation location = new StorageLocation(
                warehouse,
                locationCode,
                trimOptional(requestDto.getZoneName()),
                trimOptional(requestDto.getAisle()),
                trimOptional(requestDto.getRack()),
                trimOptional(requestDto.getBin()),
                requestDto.getActive() == null || requestDto.getActive()
        );

        StorageLocation saved = storageLocationRepository.saveAndFlush(location);
        auditTrailService.recordAudit(
                ENTITY_STORAGE_LOCATION,
                saved.getId(),
                warehouse.getWarehouseCode() + " / " + saved.getLocationCode(),
                null,
                "STORAGE_LOCATION_CREATED",
                actor,
                AppTime.nowDateTime(),
                truncateReason("Created storage location " + saved.getLocationCode() + " in warehouse " + warehouse.getWarehouseCode()),
                null,
                buildLocationSnapshot(saved)
        );

        return mapToLocationDto(saved, 0);
    }

    public StorageLocationResponseDto updateStorageLocation(Long id, StorageLocationRequestDto requestDto) {
        String actor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null) {
            throw new InvalidInventoryOperationException("Storage location payload cannot be null");
        }

        StorageLocation existing = storageLocationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Storage location not found with id: " + id));
        Warehouse warehouse = existing.getWarehouse();

        String newCode = normalizeCode(requestDto.getLocationCode());
        storageLocationRepository.findByWarehouseIdAndLocationCodeIgnoreCase(warehouse.getId(), newCode)
                .ifPresent(conflict -> {
                    if (!Objects.equals(conflict.getId(), existing.getId())) {
                        throw new DuplicateResourceException(
                                "Storage location '" + newCode + "' already exists in warehouse '" + warehouse.getWarehouseCode() + "'");
                    }
                });

        if (Boolean.TRUE.equals(requestDto.getActive()) && !warehouse.isActive()) {
            throw new InvalidInventoryOperationException(
                    "Cannot activate storage location while parent warehouse '" + warehouse.getWarehouseCode() + "' is inactive");
        }

        Map<String, Object> beforeSnapshot = buildLocationSnapshot(existing);
        existing.setLocationCode(newCode);
        existing.setZoneName(trimOptional(requestDto.getZoneName()));
        existing.setAisle(trimOptional(requestDto.getAisle()));
        existing.setRack(trimOptional(requestDto.getRack()));
        existing.setBin(trimOptional(requestDto.getBin()));
        if (requestDto.getActive() != null) {
            existing.setActive(requestDto.getActive());
        }

        StorageLocation saved = storageLocationRepository.saveAndFlush(existing);
        auditTrailService.recordAudit(
                ENTITY_STORAGE_LOCATION,
                saved.getId(),
                warehouse.getWarehouseCode() + " / " + saved.getLocationCode(),
                null,
                "STORAGE_LOCATION_UPDATED",
                actor,
                AppTime.nowDateTime(),
                truncateReason("Updated storage location " + saved.getLocationCode() + " in warehouse " + warehouse.getWarehouseCode()),
                beforeSnapshot,
                buildLocationSnapshot(saved)
        );

        return buildSingleLocationDto(saved);
    }

    public StorageLocationResponseDto updateStorageLocationStatus(Long id, InventoryStatusUpdateRequestDto requestDto) {
        String actor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null || requestDto.getActive() == null) {
            throw new InvalidInventoryOperationException("Active status flag is mandatory");
        }

        StorageLocation location = storageLocationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Storage location not found with id: " + id));

        boolean targetActive = requestDto.getActive();
        if (targetActive && !location.getWarehouse().isActive()) {
            throw new InvalidInventoryOperationException(
                    "Cannot activate storage location while parent warehouse '" + location.getWarehouse().getWarehouseCode() + "' is inactive");
        }

        if (location.isActive() == targetActive) {
            return buildSingleLocationDto(location);
        }

        Map<String, Object> beforeSnapshot = buildLocationSnapshot(location);
        location.setActive(targetActive);
        StorageLocation saved = storageLocationRepository.saveAndFlush(location);

        auditTrailService.recordAudit(
                ENTITY_STORAGE_LOCATION,
                saved.getId(),
                saved.getWarehouse().getWarehouseCode() + " / " + saved.getLocationCode(),
                null,
                targetActive ? "STORAGE_LOCATION_ACTIVATED" : "STORAGE_LOCATION_DEACTIVATED",
                actor,
                AppTime.nowDateTime(),
                truncateReason((targetActive ? "Activated" : "Deactivated") + " storage location " + saved.getLocationCode()),
                beforeSnapshot,
                buildLocationSnapshot(saved)
        );

        return buildSingleLocationDto(saved);
    }

    // -------------------------------------------------------------------------
    // Material Lots & Stock Movements Queries
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MaterialLotResponseDto> getAllLots(Long rawMaterialId, Long warehouseId) {
        List<MaterialLot> lots;
        if (rawMaterialId != null) {
            if (!rawMaterialRepository.existsById(rawMaterialId)) {
                throw new ResourceNotFoundException("Raw material not found with id: " + rawMaterialId);
            }
            lots = materialLotRepository.findByRawMaterialIdWithDetails(rawMaterialId);
        } else if (warehouseId != null) {
            if (!warehouseRepository.existsById(warehouseId)) {
                throw new ResourceNotFoundException("Warehouse not found with id: " + warehouseId);
            }
            lots = materialLotRepository.findByWarehouseIdWithDetails(warehouseId);
        } else {
            lots = materialLotRepository.findAllWithDetails();
        }

        LocalDate today = AppTime.nowDate();
        return lots.stream().map(lot -> mapToLotDto(lot, today)).toList();
    }

    @Transactional(readOnly = true)
    public MaterialLotResponseDto getLotById(Long id) {
        MaterialLot lot = materialLotRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material lot not found with id: " + id));
        return mapToLotDto(lot, AppTime.nowDate());
    }

    @Transactional(readOnly = true)
    public List<StockMovementResponseDto> getAllStockMovements(Long rawMaterialId, Long orderId) {
        List<StockMovement> movements;
        if (rawMaterialId != null) {
            if (!rawMaterialRepository.existsById(rawMaterialId)) {
                throw new ResourceNotFoundException("Raw material not found with id: " + rawMaterialId);
            }
            movements = stockMovementRepository.findByRawMaterialIdWithDetails(rawMaterialId);
        } else if (orderId != null) {
            if (!orderRepository.existsById(orderId)) {
                throw new ResourceNotFoundException("Manufacturing order not found with id: " + orderId);
            }
            movements = stockMovementRepository.findByManufacturingOrderIdWithDetails(orderId);
        } else {
            movements = stockMovementRepository.findAllWithDetailsOrderByOccurredAtDescIdDesc();
        }
        return movements.stream().map(this::mapToMovementDto).toList();
    }

    // -------------------------------------------------------------------------
    // Order Recipe Material Requirements
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public OrderMaterialRequirementsResponseDto getOrderMaterialRequirements(Long orderId) {
        ManufacturingOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Manufacturing order not found with id: " + orderId));

        List<RawMaterialResponseDto> allMaterials = getAllRawMaterials();
        Map<String, RawMaterialResponseDto> materialByKey = new LinkedHashMap<>();
        for (RawMaterialResponseDto dto : allMaterials) {
            if (dto.getMaterialName() != null) {
                materialByKey.putIfAbsent(normalizeKey(dto.getMaterialName()), dto);
            }
        }

        int targetBatches = order.getTargetBatchCount() != null ? order.getTargetBatchCount() : 1;
        int activeBatches = resolveEffectiveActiveBatchCount(order);

        OrderMaterialRequirementsResponseDto response = new OrderMaterialRequirementsResponseDto();
        response.setOrderId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setOrderStatus(order.getStatus());
        response.setEligibleForIssue(order.getStatus() == OrderStatus.RELEASED || order.getStatus() == OrderStatus.IN_PROGRESS);
        if (order.getProduct() != null) {
            response.setProductCode(order.getProduct().getProductCode());
            response.setProductName(order.getProduct().getProductName());
        }
        Recipe recipe = order.getRecipe();
        if (recipe != null) {
            response.setRecipeCode(recipe.getRecipeCode());
            response.setRecipeVersion(order.getRecipeVersion() != null ? order.getRecipeVersion() : recipe.getVersion());
        }
        response.setPlannedQuantity(order.getPlannedQuantity());
        response.setQuantityUnit(order.getQuantityUnit());
        response.setTargetBatchCount(targetBatches);
        response.setActiveBatchCount(activeBatches);

        List<OrderMaterialRequirementDto> items = new ArrayList<>();
        if (recipe != null && recipe.getMaterials() != null) {
            // Group identical material names if repeated in recipe
            Map<String, BigDecimal> perBatchByName = new LinkedHashMap<>();
            Map<String, String> displayNameByKey = new LinkedHashMap<>();
            Map<String, String> unitByKey = new LinkedHashMap<>();
            for (RecipeMaterial rm : recipe.getMaterials()) {
                if (rm.getMaterialName() == null) continue;
                String key = normalizeKey(rm.getMaterialName());
                displayNameByKey.putIfAbsent(key, rm.getMaterialName().trim());
                unitByKey.putIfAbsent(key, rm.getUnit() != null ? rm.getUnit().trim() : "");
                perBatchByName.merge(key, rm.getQuantity(), BigDecimal::add);
            }

            for (Map.Entry<String, BigDecimal> entry : perBatchByName.entrySet()) {
                String key = entry.getKey();
                BigDecimal perBatch = entry.getValue();
                BigDecimal totalReq = perBatch.multiply(BigDecimal.valueOf(activeBatches));
                String recipeUnit = unitByKey.getOrDefault(key, "");

                OrderMaterialRequirementDto item = new OrderMaterialRequirementDto();
                item.setRecipeMaterialName(displayNameByKey.get(key));
                item.setQuantityPerBatch(perBatch);
                item.setTotalRequiredQuantity(totalReq);
                item.setRecipeUnit(recipeUnit);

                RawMaterialResponseDto matched = materialByKey.get(key);
                if (matched != null) {
                    item.setRegisteredInInventory(true);
                    item.setRawMaterialId(matched.getId());
                    item.setRawMaterialCode(matched.getMaterialCode());
                    item.setRawMaterialName(matched.getMaterialName());
                    item.setInventoryUnit(matched.getUnit());
                    item.setMaterialActive(matched.isActive());
                    item.setUnitMismatch(matched.getUnit() == null || !matched.getUnit().trim().equalsIgnoreCase(recipeUnit));
                    item.setAvailableStock(matched.getAvailableStock() != null ? matched.getAvailableStock() : BigDecimal.ZERO);

                    BigDecimal issued = stockMovementRepository.sumIssuedQuantityForOrderAndMaterial(
                            order.getId(), matched.getId(), StockMovementType.MATERIAL_ISSUE);
                    if (issued == null) {
                        issued = BigDecimal.ZERO;
                    }
                    item.setIssuedQuantity(issued);
                    BigDecimal remaining = totalReq.subtract(issued);
                    item.setRemainingRequiredQuantity(remaining.compareTo(BigDecimal.ZERO) > 0 ? remaining : BigDecimal.ZERO);
                    item.setFulfilled(issued.compareTo(totalReq) >= 0);
                } else {
                    item.setRegisteredInInventory(false);
                    item.setMaterialActive(false);
                    item.setUnitMismatch(false);
                    item.setAvailableStock(BigDecimal.ZERO);
                    item.setIssuedQuantity(BigDecimal.ZERO);
                    item.setRemainingRequiredQuantity(totalReq);
                    item.setFulfilled(false);
                }
                items.add(item);
            }
        }

        response.setRequirements(items);
        return response;
    }

    // -------------------------------------------------------------------------
    // Goods Receipt
    // -------------------------------------------------------------------------

    public StockMovementResponseDto receiveGoods(GoodsReceiptRequestDto requestDto) {
        String actor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null) {
            throw new InvalidInventoryOperationException("Goods receipt payload cannot be null");
        }
        if (requestDto.getRawMaterialId() == null) {
            throw new InvalidInventoryOperationException("Raw material ID is mandatory");
        }
        if (requestDto.getWarehouseId() == null) {
            throw new InvalidInventoryOperationException("Warehouse ID is mandatory");
        }
        if (requestDto.getStorageLocationId() == null) {
            throw new InvalidInventoryOperationException("Storage location ID is mandatory");
        }
        if (requestDto.getQuantity() == null || requestDto.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidInventoryOperationException("Receipt quantity must be greater than zero");
        }

        String lotNumber = trimRequired(requestDto.getLotNumber(), "Lot number");
        String unit = trimRequired(requestDto.getUnit(), "Unit");
        String supplierName = trimRequired(requestDto.getSupplierName(), "Supplier name");
        String supplierReference = trimOptional(requestDto.getSupplierReference());
        String referenceNumber = normalizeOptionalReference(requestDto.getReferenceNumber());

        LocalDate today = AppTime.nowDate();
        LocalDate receivedDate = requestDto.getReceivedDate();
        LocalDate expiryDate = requestDto.getExpiryDate();
        if (receivedDate == null) {
            throw new InvalidInventoryOperationException("Received date is mandatory");
        }
        if (expiryDate == null) {
            throw new InvalidInventoryOperationException("Expiry date is mandatory");
        }
        if (receivedDate.isAfter(today)) {
            throw new InvalidInventoryOperationException("Received date cannot be in the future");
        }
        if (!expiryDate.isAfter(receivedDate)) {
            throw new InvalidInventoryOperationException("Expiry date must be strictly after the received date");
        }
        if (expiryDate.isBefore(today)) {
            throw new InvalidInventoryOperationException("Cannot receive already-expired material lot (expiry date " + expiryDate + " is in the past)");
        }

        if (referenceNumber != null && stockMovementRepository.existsByReferenceNumberIgnoreCase(referenceNumber)) {
            throw new DuplicateResourceException("Receipt/movement reference number '" + referenceNumber + "' already exists");
        }

        RawMaterial material = rawMaterialRepository.findByIdWithLock(requestDto.getRawMaterialId())
                .orElseThrow(() -> new ResourceNotFoundException("Raw material not found with id: " + requestDto.getRawMaterialId()));

        if (!material.isActive()) {
            throw new InvalidInventoryOperationException(
                    "Cannot receive goods for inactive raw material '" + material.getMaterialCode() + "'");
        }
        if (!material.getUnit().trim().equalsIgnoreCase(unit)) {
            throw new InvalidInventoryOperationException(
                    "Unit mismatch: raw material '" + material.getMaterialCode() + "' uses '" + material.getUnit()
                            + "', not '" + unit + "'");
        }

        Warehouse warehouse = warehouseRepository.findById(requestDto.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with id: " + requestDto.getWarehouseId()));
        if (!warehouse.isActive()) {
            throw new InvalidInventoryOperationException(
                    "Cannot receive goods into inactive warehouse '" + warehouse.getWarehouseCode() + "'");
        }

        StorageLocation location = storageLocationRepository.findById(requestDto.getStorageLocationId())
                .orElseThrow(() -> new ResourceNotFoundException("Storage location not found with id: " + requestDto.getStorageLocationId()));
        if (!Objects.equals(location.getWarehouse().getId(), warehouse.getId())) {
            throw new InvalidInventoryOperationException(
                    "Storage location '" + location.getLocationCode() + "' does not belong to warehouse '" + warehouse.getWarehouseCode() + "'");
        }
        if (!location.isActive()) {
            throw new InvalidInventoryOperationException(
                    "Cannot receive goods into inactive storage location '" + location.getLocationCode() + "'");
        }

        if (materialLotRepository.existsByRawMaterialIdAndLotNumberIgnoreCase(material.getId(), lotNumber)) {
            throw new DuplicateResourceException(
                    "Lot number '" + lotNumber + "' already exists for raw material '" + material.getMaterialCode() + "'");
        }

        MaterialLot lot = new MaterialLot();
        lot.setRawMaterial(material);
        lot.setWarehouse(warehouse);
        lot.setStorageLocation(location);
        lot.setLotNumber(lotNumber);
        lot.setSupplierName(supplierName);
        lot.setSupplierReference(supplierReference);
        lot.setReceivedDate(receivedDate);
        lot.setExpiryDate(expiryDate);
        lot.setInitialQuantity(requestDto.getQuantity());
        lot.setAvailableQuantity(requestDto.getQuantity());
        lot.setUnit(material.getUnit());
        lot.setStatus(MaterialLotStatus.ACTIVE);
        lot.setCreatedBy(actor);

        MaterialLot savedLot = materialLotRepository.saveAndFlush(lot);

        LocalDateTime now = AppTime.nowDateTime();
        String reason = trimOptional(requestDto.getReason());
        if (reason == null) {
            reason = "Goods receipt of " + formatQuantity(requestDto.getQuantity()) + " " + material.getUnit()
                    + " into " + warehouse.getWarehouseCode() + " / " + location.getLocationCode()
                    + " from " + supplierName;
        }

        StockMovement movement = new StockMovement();
        movement.setMovementNumber(generateMovementNumber("GRN"));
        movement.setReferenceNumber(referenceNumber);
        movement.setMovementType(StockMovementType.GOODS_RECEIPT);
        movement.setRawMaterial(material);
        movement.setMaterialLot(savedLot);
        movement.setWarehouse(warehouse);
        movement.setStorageLocation(location);
        movement.setQuantityDelta(requestDto.getQuantity());
        movement.setQuantityBefore(BigDecimal.ZERO);
        movement.setQuantityAfter(requestDto.getQuantity());
        movement.setUnit(material.getUnit());
        movement.setSupplierName(supplierName);
        movement.setSupplierReference(supplierReference);
        movement.setReason(reason);
        movement.setPerformedBy(actor);
        movement.setOccurredAt(now);

        StockMovement savedMovement = stockMovementRepository.saveAndFlush(movement);

        auditTrailService.recordAudit(
                ENTITY_MATERIAL_LOT,
                savedLot.getId(),
                material.getMaterialCode() + " / " + savedLot.getLotNumber(),
                null,
                "GOODS_RECEIPT_RECORDED",
                actor,
                now,
                truncateReason(reason),
                null,
                buildLotSnapshot(savedLot, savedMovement)
        );

        return mapToMovementDto(savedMovement);
    }

    // -------------------------------------------------------------------------
    // Material Issue Against Manufacturing Order (Concurrency-Safe)
    // -------------------------------------------------------------------------

    public StockMovementResponseDto issueMaterial(MaterialIssueRequestDto requestDto) {
        String actor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null) {
            throw new InvalidInventoryOperationException("Material issue payload cannot be null");
        }
        if (requestDto.getManufacturingOrderId() == null) {
            throw new InvalidInventoryOperationException("Manufacturing order ID is mandatory");
        }
        if (requestDto.getRawMaterialId() == null) {
            throw new InvalidInventoryOperationException("Raw material ID is mandatory");
        }
        if (requestDto.getMaterialLotId() == null) {
            throw new InvalidInventoryOperationException("Material lot ID is mandatory");
        }
        if (requestDto.getQuantity() == null || requestDto.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidInventoryOperationException("Issue quantity must be greater than zero");
        }

        String requestedUnit = trimRequired(requestDto.getUnit(), "Unit");
        String referenceNumber = normalizeOptionalReference(requestDto.getReferenceNumber());
        if (referenceNumber != null && stockMovementRepository.existsByReferenceNumberIgnoreCase(referenceNumber)) {
            throw new DuplicateResourceException("Issue/movement reference number '" + referenceNumber + "' already exists");
        }

        // Acyclic lock ordering: ManufacturingOrder -> RawMaterial -> MaterialLot
        ManufacturingOrder order = orderRepository.findByIdWithLock(requestDto.getManufacturingOrderId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Manufacturing order not found with id: " + requestDto.getManufacturingOrderId()));

        if (order.getStatus() != OrderStatus.RELEASED && order.getStatus() != OrderStatus.IN_PROGRESS) {
            throw new InvalidInventoryOperationException(
                    "Cannot issue material to order '" + order.getOrderNumber() + "' in status " + order.getStatus()
                            + ". Order must be RELEASED or IN_PROGRESS.");
        }

        Batch targetBatch = null;
        if (requestDto.getBatchId() != null) {
            targetBatch = batchRepository.findByIdWithLock(requestDto.getBatchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + requestDto.getBatchId()));
            if (targetBatch.getOrder() == null || !Objects.equals(targetBatch.getOrder().getId(), order.getId())) {
                throw new InvalidInventoryOperationException(
                        "Batch '" + targetBatch.getBatchNumber() + "' does not belong to manufacturing order '" + order.getOrderNumber() + "'");
            }
            if (targetBatch.getStatus() != BatchStatus.PLANNED && targetBatch.getStatus() != BatchStatus.IN_PROGRESS) {
                throw new InvalidInventoryOperationException(
                        "Cannot issue material to batch '" + targetBatch.getBatchNumber()
                                + "' in status " + targetBatch.getStatus());
            }
        }

        RawMaterial material = rawMaterialRepository.findByIdWithLock(requestDto.getRawMaterialId())
                .orElseThrow(() -> new ResourceNotFoundException("Raw material not found with id: " + requestDto.getRawMaterialId()));
        if (!material.isActive()) {
            throw new InvalidInventoryOperationException(
                    "Cannot issue inactive raw material '" + material.getMaterialCode() + "'");
        }
        if (!material.getUnit().trim().equalsIgnoreCase(requestedUnit)) {
            throw new InvalidInventoryOperationException(
                    "Unit mismatch: material '" + material.getMaterialCode() + "' uses '" + material.getUnit()
                            + "', not '" + requestedUnit + "'");
        }

        MaterialLot lot = materialLotRepository.findByIdWithLock(requestDto.getMaterialLotId())
                .orElseThrow(() -> new ResourceNotFoundException("Material lot not found with id: " + requestDto.getMaterialLotId()));
        if (!Objects.equals(lot.getRawMaterial().getId(), material.getId())) {
            throw new InvalidInventoryOperationException(
                    "Material lot '" + lot.getLotNumber() + "' does not belong to raw material '" + material.getMaterialCode() + "'");
        }
        if (!lot.getUnit().trim().equalsIgnoreCase(material.getUnit())) {
            throw new InvalidInventoryOperationException(
                    "Lot unit '" + lot.getUnit() + "' conflicts with material unit '" + material.getUnit() + "'");
        }

        LocalDate today = AppTime.nowDate();
        if (lot.getStatus() == MaterialLotStatus.QUARANTINED) {
            throw new InvalidInventoryOperationException(
                    "Cannot issue from quarantined lot '" + lot.getLotNumber() + "'");
        }
        if (lot.getStatus() == MaterialLotStatus.EXPIRED || isDateExpired(lot, today)) {
            throw new InvalidInventoryOperationException(
                    "Cannot issue from expired lot '" + lot.getLotNumber()
                            + "' (expired on " + lot.getExpiryDate() + ")");
        }
        if (lot.getStatus() == MaterialLotStatus.DEPLETED
                || lot.getAvailableQuantity() == null
                || lot.getAvailableQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidInventoryOperationException(
                    "Cannot issue from depleted lot '" + lot.getLotNumber() + "' (available stock is 0 " + lot.getUnit() + ")");
        }

        // Validate against Order Recipe Material & Quantity requirements when recipe specifies materials
        validateOrderRecipeRequirement(order, material, requestDto.getQuantity());

        BigDecimal beforeQty = lot.getAvailableQuantity();
        if (beforeQty.compareTo(requestDto.getQuantity()) < 0) {
            throw new InvalidInventoryOperationException(
                    "Insufficient available stock in lot '" + lot.getLotNumber() + "': available "
                            + formatQuantity(beforeQty) + " " + lot.getUnit() + ", requested "
                            + formatQuantity(requestDto.getQuantity()) + " " + lot.getUnit());
        }

        BigDecimal afterQty = beforeQty.subtract(requestDto.getQuantity());
        if (afterQty.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidInventoryOperationException("Material issue would result in negative stock");
        }

        Map<String, Object> lotBeforeSnapshot = buildLotSnapshot(lot, null);
        lot.setAvailableQuantity(afterQty);
        if (afterQty.compareTo(BigDecimal.ZERO) == 0) {
            lot.setStatus(MaterialLotStatus.DEPLETED);
        }
        MaterialLot savedLot = materialLotRepository.saveAndFlush(lot);

        LocalDateTime now = AppTime.nowDateTime();
        String reason = trimOptional(requestDto.getReason());
        if (reason == null) {
            reason = "Issued " + formatQuantity(requestDto.getQuantity()) + " " + material.getUnit()
                    + " from lot " + savedLot.getLotNumber() + " to order " + order.getOrderNumber()
                    + (targetBatch != null ? " (batch " + targetBatch.getBatchNumber() + ")" : "");
        }

        StockMovement movement = new StockMovement();
        movement.setMovementNumber(generateMovementNumber("ISS"));
        movement.setReferenceNumber(referenceNumber);
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setRawMaterial(material);
        movement.setMaterialLot(savedLot);
        movement.setWarehouse(savedLot.getWarehouse());
        movement.setStorageLocation(savedLot.getStorageLocation());
        movement.setManufacturingOrder(order);
        movement.setBatch(targetBatch);
        movement.setQuantityDelta(requestDto.getQuantity().negate());
        movement.setQuantityBefore(beforeQty);
        movement.setQuantityAfter(afterQty);
        movement.setUnit(material.getUnit());
        movement.setReason(reason);
        movement.setPerformedBy(actor);
        movement.setOccurredAt(now);

        StockMovement savedMovement = stockMovementRepository.saveAndFlush(movement);

        auditTrailService.recordAudit(
                ENTITY_MATERIAL_LOT,
                savedLot.getId(),
                material.getMaterialCode() + " / " + savedLot.getLotNumber(),
                targetBatch != null ? targetBatch.getId() : null,
                "MATERIAL_ISSUED",
                actor,
                now,
                truncateReason(reason),
                lotBeforeSnapshot,
                buildLotSnapshot(savedLot, savedMovement)
        );

        return mapToMovementDto(savedMovement);
    }

    // -------------------------------------------------------------------------
    // Controlled Stock Adjustment & Lot Quarantine
    // -------------------------------------------------------------------------

    public StockMovementResponseDto adjustStock(StockAdjustmentRequestDto requestDto) {
        String actor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null) {
            throw new InvalidInventoryOperationException("Stock adjustment payload cannot be null");
        }
        if (requestDto.getMaterialLotId() == null) {
            throw new InvalidInventoryOperationException("Material lot ID is mandatory");
        }
        if (requestDto.getAdjustmentType() == null) {
            throw new InvalidInventoryOperationException("Adjustment type is mandatory");
        }
        String reason = trimRequired(requestDto.getReason(), "Adjustment reason");
        String referenceNumber = normalizeOptionalReference(requestDto.getReferenceNumber());
        if (referenceNumber != null && stockMovementRepository.existsByReferenceNumberIgnoreCase(referenceNumber)) {
            throw new DuplicateResourceException("Adjustment/movement reference number '" + referenceNumber + "' already exists");
        }

        // Scalar lookup avoids loading MaterialLot into L1 cache before PESSIMISTIC_WRITE lock
        Long rawMaterialId = materialLotRepository.findRawMaterialIdById(requestDto.getMaterialLotId())
                .orElseThrow(() -> new ResourceNotFoundException("Material lot not found with id: " + requestDto.getMaterialLotId()));
        RawMaterial material = rawMaterialRepository.findByIdWithLock(rawMaterialId)
                .orElseThrow(() -> new ResourceNotFoundException("Raw material not found"));
        MaterialLot lot = materialLotRepository.findByIdWithLock(requestDto.getMaterialLotId())
                .orElseThrow(() -> new ResourceNotFoundException("Material lot not found with id: " + requestDto.getMaterialLotId()));

        LocalDate today = AppTime.nowDate();
        boolean dateExpired = isDateExpired(lot, today);
        BigDecimal beforeQty = lot.getAvailableQuantity();
        BigDecimal delta;
        BigDecimal afterQty;
        String auditAction;
        Map<String, Object> beforeSnapshot = buildLotSnapshot(lot, null);

        StockAdjustmentType type = requestDto.getAdjustmentType();
        if (type == StockAdjustmentType.INCREASE) {
            if (requestDto.getQuantity() == null || requestDto.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidInventoryOperationException("Adjustment quantity must be greater than zero for INCREASE");
            }
            if (lot.getStatus() == MaterialLotStatus.EXPIRED || dateExpired) {
                throw new InvalidInventoryOperationException("Cannot increase stock on an expired material lot '" + lot.getLotNumber() + "'");
            }
            if (lot.getStatus() == MaterialLotStatus.QUARANTINED) {
                throw new InvalidInventoryOperationException("Cannot increase stock on quarantined lot '" + lot.getLotNumber() + "'. Release quarantine first.");
            }
            delta = requestDto.getQuantity();
            afterQty = beforeQty.add(delta);
            lot.setAvailableQuantity(afterQty);
            lot.setStatus(MaterialLotStatus.ACTIVE);
            auditAction = "STOCK_ADJUSTMENT_INCREASE";
        } else if (type == StockAdjustmentType.DECREASE) {
            if (requestDto.getQuantity() == null || requestDto.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidInventoryOperationException("Adjustment quantity must be greater than zero for DECREASE");
            }
            if (beforeQty.compareTo(requestDto.getQuantity()) < 0) {
                throw new InvalidInventoryOperationException(
                        "Cannot decrease stock below zero for lot '" + lot.getLotNumber()
                                + "': available " + formatQuantity(beforeQty) + " " + lot.getUnit()
                                + ", requested decrease " + formatQuantity(requestDto.getQuantity()) + " " + lot.getUnit());
            }
            delta = requestDto.getQuantity().negate();
            afterQty = beforeQty.subtract(requestDto.getQuantity());
            lot.setAvailableQuantity(afterQty);
            if (afterQty.compareTo(BigDecimal.ZERO) == 0 && lot.getStatus() != MaterialLotStatus.QUARANTINED) {
                lot.setStatus(MaterialLotStatus.DEPLETED);
            }
            auditAction = "STOCK_ADJUSTMENT_DECREASE";
        } else if (type == StockAdjustmentType.QUARANTINE) {
            if (lot.getStatus() == MaterialLotStatus.QUARANTINED) {
                throw new InvalidInventoryOperationException("Lot '" + lot.getLotNumber() + "' is already QUARANTINED");
            }
            if (lot.getStatus() == MaterialLotStatus.DEPLETED || beforeQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidInventoryOperationException("Cannot quarantine depleted lot '" + lot.getLotNumber() + "'");
            }
            delta = BigDecimal.ZERO;
            afterQty = beforeQty;
            lot.setStatus(MaterialLotStatus.QUARANTINED);
            auditAction = "MATERIAL_LOT_QUARANTINED";
        } else { // RELEASE_QUARANTINE
            if (lot.getStatus() != MaterialLotStatus.QUARANTINED) {
                throw new InvalidInventoryOperationException("Lot '" + lot.getLotNumber() + "' is not currently QUARANTINED");
            }
            if (dateExpired) {
                throw new InvalidInventoryOperationException(
                        "Cannot release quarantined lot '" + lot.getLotNumber() + "' to ACTIVE because it expired on " + lot.getExpiryDate());
            }
            delta = BigDecimal.ZERO;
            afterQty = beforeQty;
            lot.setStatus(afterQty.compareTo(BigDecimal.ZERO) > 0 ? MaterialLotStatus.ACTIVE : MaterialLotStatus.DEPLETED);
            auditAction = "MATERIAL_LOT_RELEASED";
        }

        MaterialLot savedLot = materialLotRepository.saveAndFlush(lot);
        LocalDateTime now = AppTime.nowDateTime();

        StockMovement movement = new StockMovement();
        movement.setMovementNumber(generateMovementNumber("ADJ"));
        movement.setReferenceNumber(referenceNumber);
        movement.setMovementType(StockMovementType.STOCK_ADJUSTMENT);
        movement.setRawMaterial(material);
        movement.setMaterialLot(savedLot);
        movement.setWarehouse(savedLot.getWarehouse());
        movement.setStorageLocation(savedLot.getStorageLocation());
        movement.setQuantityDelta(delta);
        movement.setQuantityBefore(beforeQty);
        movement.setQuantityAfter(afterQty);
        movement.setUnit(material.getUnit());
        movement.setReason("[" + type.name() + "] " + reason);
        movement.setPerformedBy(actor);
        movement.setOccurredAt(now);

        StockMovement savedMovement = stockMovementRepository.saveAndFlush(movement);

        auditTrailService.recordAudit(
                ENTITY_MATERIAL_LOT,
                savedLot.getId(),
                material.getMaterialCode() + " / " + savedLot.getLotNumber(),
                null,
                auditAction,
                actor,
                now,
                truncateReason("[" + type.name() + "] " + reason),
                beforeSnapshot,
                buildLotSnapshot(savedLot, savedMovement)
        );

        return mapToMovementDto(savedMovement);
    }

    // -------------------------------------------------------------------------
    // Internal Validation & Mapping Helpers
    // -------------------------------------------------------------------------

    private void validateOrderRecipeRequirement(ManufacturingOrder order, RawMaterial material, BigDecimal requestedQty) {
        Recipe recipe = order.getRecipe();
        if (recipe == null || recipe.getMaterials() == null || recipe.getMaterials().isEmpty()) {
            return;
        }

        String targetKey = normalizeKey(material.getMaterialName());
        BigDecimal perBatchRequired = BigDecimal.ZERO;
        String recipeUnit = null;
        boolean matchedInRecipe = false;

        for (RecipeMaterial rm : recipe.getMaterials()) {
            if (rm.getMaterialName() != null && normalizeKey(rm.getMaterialName()).equals(targetKey)) {
                matchedInRecipe = true;
                perBatchRequired = perBatchRequired.add(rm.getQuantity());
                if (recipeUnit == null) {
                    recipeUnit = rm.getUnit();
                } else if (rm.getUnit() != null && !rm.getUnit().trim().equalsIgnoreCase(recipeUnit.trim())) {
                    throw new InvalidInventoryOperationException(
                            "Recipe '" + recipe.getRecipeCode() + "' contains inconsistent units for material '"
                                    + rm.getMaterialName() + "'");
                }
            }
        }

        if (!matchedInRecipe) {
            throw new InvalidInventoryOperationException(
                    "Raw material '" + material.getMaterialName() + "' (" + material.getMaterialCode()
                            + ") is not listed in recipe '" + recipe.getRecipeCode() + " v" + order.getRecipeVersion()
                            + "' for manufacturing order '" + order.getOrderNumber() + "'");
        }

        if (recipeUnit == null || !material.getUnit().trim().equalsIgnoreCase(recipeUnit.trim())) {
            throw new InvalidInventoryOperationException(
                    "Material unit '" + material.getUnit() + "' does not match recipe requirement unit '"
                            + recipeUnit + "' for '" + material.getMaterialName() + "'");
        }

        int activeBatchCount = resolveEffectiveActiveBatchCount(order);
        BigDecimal totalOrderRequired = perBatchRequired.multiply(BigDecimal.valueOf(activeBatchCount));

        BigDecimal alreadyIssued = stockMovementRepository.sumIssuedQuantityForOrderAndMaterial(
                order.getId(), material.getId(), StockMovementType.MATERIAL_ISSUE);
        if (alreadyIssued == null) {
            alreadyIssued = BigDecimal.ZERO;
        }

        if (alreadyIssued.add(requestedQty).compareTo(totalOrderRequired) > 0) {
            throw new InvalidInventoryOperationException(
                    "Issue quantity (" + formatQuantity(requestedQty) + " " + material.getUnit()
                            + ") plus already issued quantity (" + formatQuantity(alreadyIssued) + " " + material.getUnit()
                            + ") exceeds order recipe requirement (" + formatQuantity(totalOrderRequired) + " " + material.getUnit()
                            + ") for order '" + order.getOrderNumber() + "'");
        }
    }

    private int resolveEffectiveActiveBatchCount(ManufacturingOrder order) {
        List<Batch> batches = order.getBatches();
        if (batches != null && !batches.isEmpty()) {
            long nonCancelled = batches.stream()
                    .filter(b -> b.getStatus() != BatchStatus.CANCELLED)
                    .count();
            if (nonCancelled > 0) {
                return Math.toIntExact(nonCancelled);
            }
        }
        return order.getTargetBatchCount() != null && order.getTargetBatchCount() > 0
                ? order.getTargetBatchCount()
                : 1;
    }

    private WarehouseResponseDto buildSingleWarehouseDto(Warehouse wh) {
        List<StorageLocation> locs = storageLocationRepository.findByWarehouseIdOrderByLocationCodeAsc(wh.getId());
        List<MaterialLot> lots = materialLotRepository.findByWarehouseIdWithDetails(wh.getId());
        LocalDate today = AppTime.nowDate();
        int activeLots = (int) lots.stream().filter(lot -> isUsableActiveLot(lot, today)).count();
        return mapToWarehouseDto(wh, locs, activeLots);
    }

    private StorageLocationResponseDto buildSingleLocationDto(StorageLocation loc) {
        LocalDate today = AppTime.nowDate();
        int activeLots = (int) materialLotRepository.findByWarehouseIdWithDetails(loc.getWarehouse().getId()).stream()
                .filter(lot -> lot.getStorageLocation() != null
                        && Objects.equals(lot.getStorageLocation().getId(), loc.getId())
                        && isUsableActiveLot(lot, today))
                .count();
        return mapToLocationDto(loc, activeLots);
    }

    private RawMaterialResponseDto mapToRawMaterialResponseDto(RawMaterial material,
                                                             List<MaterialLot> lots,
                                                             Set<String> linkedRecipeCodes,
                                                             LocalDate today) {
        LocalDate soonThreshold = today.plusDays(EXPIRING_SOON_DAYS);
        BigDecimal availableStock = BigDecimal.ZERO;
        BigDecimal quarantinedStock = BigDecimal.ZERO;
        BigDecimal expiredStock = BigDecimal.ZERO;
        int activeLotCount = 0;
        int expiringSoonLotCount = 0;
        int expiredLotCount = 0;

        for (MaterialLot lot : lots) {
            BigDecimal qty = lot.getAvailableQuantity() != null ? lot.getAvailableQuantity() : BigDecimal.ZERO;
            boolean dateExpired = isDateExpired(lot, today);
            if (lot.getStatus() == MaterialLotStatus.EXPIRED || dateExpired) {
                if (qty.compareTo(BigDecimal.ZERO) > 0) {
                    expiredStock = expiredStock.add(qty);
                    expiredLotCount += 1;
                }
            } else if (lot.getStatus() == MaterialLotStatus.QUARANTINED) {
                quarantinedStock = quarantinedStock.add(qty);
            } else if (lot.getStatus() == MaterialLotStatus.ACTIVE && qty.compareTo(BigDecimal.ZERO) > 0) {
                availableStock = availableStock.add(qty);
                activeLotCount += 1;
                if (lot.getExpiryDate() != null && !lot.getExpiryDate().isAfter(soonThreshold)) {
                    expiringSoonLotCount += 1;
                }
            }
        }

        MaterialStockStatus stockStatus;
        if (!material.isActive()) {
            stockStatus = MaterialStockStatus.INACTIVE;
        } else if (availableStock.compareTo(BigDecimal.ZERO) <= 0) {
            stockStatus = MaterialStockStatus.OUT_OF_STOCK;
        } else if (material.getMinimumStockLevel() != null
                && material.getMinimumStockLevel().compareTo(BigDecimal.ZERO) > 0
                && availableStock.compareTo(material.getMinimumStockLevel()) <= 0) {
            stockStatus = MaterialStockStatus.LOW_STOCK;
        } else {
            stockStatus = MaterialStockStatus.IN_STOCK;
        }

        RawMaterialResponseDto dto = new RawMaterialResponseDto();
        dto.setId(material.getId());
        dto.setMaterialCode(material.getMaterialCode());
        dto.setMaterialName(material.getMaterialName());
        dto.setMaterialType(material.getMaterialType());
        dto.setUnit(material.getUnit());
        dto.setMinimumStockLevel(material.getMinimumStockLevel());
        dto.setDescription(material.getDescription());
        dto.setActive(material.isActive());
        dto.setAvailableStock(availableStock);
        dto.setQuarantinedStock(quarantinedStock);
        dto.setExpiredStock(expiredStock);
        dto.setActiveLotCount(activeLotCount);
        dto.setExpiringSoonLotCount(expiringSoonLotCount);
        dto.setExpiredLotCount(expiredLotCount);
        dto.setStockStatus(stockStatus);
        dto.setLinkedRecipeCount(linkedRecipeCodes != null ? linkedRecipeCodes.size() : 0);
        dto.setLinkedRecipeCodes(linkedRecipeCodes != null ? new ArrayList<>(linkedRecipeCodes) : List.of());
        dto.setCreatedAt(material.getCreatedAt());
        dto.setUpdatedAt(material.getUpdatedAt());
        return dto;
    }

    private WarehouseResponseDto mapToWarehouseDto(Warehouse warehouse,
                                                 List<StorageLocation> locations,
                                                 int activeLotCount) {
        WarehouseResponseDto dto = new WarehouseResponseDto();
        dto.setId(warehouse.getId());
        dto.setWarehouseCode(warehouse.getWarehouseCode());
        dto.setWarehouseName(warehouse.getWarehouseName());
        dto.setLocationSummary(warehouse.getLocationSummary());
        dto.setStorageCondition(warehouse.getStorageCondition());
        dto.setActive(warehouse.isActive());
        dto.setLocationCount(locations != null ? locations.size() : 0);
        dto.setActiveLocationCount(locations != null ? (int) locations.stream().filter(StorageLocation::isActive).count() : 0);
        dto.setActiveLotCount(activeLotCount);
        dto.setCreatedAt(warehouse.getCreatedAt());
        dto.setUpdatedAt(warehouse.getUpdatedAt());
        return dto;
    }

    private StorageLocationResponseDto mapToLocationDto(StorageLocation loc, int activeLotCount) {
        StorageLocationResponseDto dto = new StorageLocationResponseDto();
        dto.setId(loc.getId());
        if (loc.getWarehouse() != null) {
            dto.setWarehouseId(loc.getWarehouse().getId());
            dto.setWarehouseCode(loc.getWarehouse().getWarehouseCode());
            dto.setWarehouseName(loc.getWarehouse().getWarehouseName());
        }
        dto.setLocationCode(loc.getLocationCode());
        dto.setZoneName(loc.getZoneName());
        dto.setAisle(loc.getAisle());
        dto.setRack(loc.getRack());
        dto.setBin(loc.getBin());
        dto.setActive(loc.isActive());
        dto.setActiveLotCount(activeLotCount);
        dto.setCreatedAt(loc.getCreatedAt());
        dto.setUpdatedAt(loc.getUpdatedAt());
        return dto;
    }

    private MaterialLotResponseDto mapToLotDto(MaterialLot lot, LocalDate today) {
        boolean dateExpired = isDateExpired(lot, today);
        BigDecimal available = lot.getAvailableQuantity() != null ? lot.getAvailableQuantity() : BigDecimal.ZERO;
        MaterialLotStatus effectiveStatus;
        if (lot.getStatus() == MaterialLotStatus.EXPIRED || (dateExpired && available.compareTo(BigDecimal.ZERO) > 0)) {
            effectiveStatus = MaterialLotStatus.EXPIRED;
        } else if (lot.getStatus() == MaterialLotStatus.QUARANTINED) {
            effectiveStatus = MaterialLotStatus.QUARANTINED;
        } else if (available.compareTo(BigDecimal.ZERO) <= 0 || lot.getStatus() == MaterialLotStatus.DEPLETED) {
            effectiveStatus = MaterialLotStatus.DEPLETED;
        } else {
            effectiveStatus = MaterialLotStatus.ACTIVE;
        }

        long daysToExpiry = lot.getExpiryDate() != null
                ? ChronoUnit.DAYS.between(today, lot.getExpiryDate())
                : Long.MAX_VALUE;
        boolean expiringSoon = effectiveStatus == MaterialLotStatus.ACTIVE
                && daysToExpiry >= 0
                && daysToExpiry <= EXPIRING_SOON_DAYS;

        MaterialLotResponseDto dto = new MaterialLotResponseDto();
        dto.setId(lot.getId());
        if (lot.getRawMaterial() != null) {
            dto.setRawMaterialId(lot.getRawMaterial().getId());
            dto.setMaterialCode(lot.getRawMaterial().getMaterialCode());
            dto.setMaterialName(lot.getRawMaterial().getMaterialName());
            dto.setMaterialActive(lot.getRawMaterial().isActive());
        }
        if (lot.getWarehouse() != null) {
            dto.setWarehouseId(lot.getWarehouse().getId());
            dto.setWarehouseCode(lot.getWarehouse().getWarehouseCode());
            dto.setWarehouseName(lot.getWarehouse().getWarehouseName());
        }
        if (lot.getStorageLocation() != null) {
            dto.setStorageLocationId(lot.getStorageLocation().getId());
            dto.setLocationCode(lot.getStorageLocation().getLocationCode());
            dto.setZoneName(lot.getStorageLocation().getZoneName());
        }
        dto.setLotNumber(lot.getLotNumber());
        dto.setSupplierName(lot.getSupplierName());
        dto.setSupplierReference(lot.getSupplierReference());
        dto.setReceivedDate(lot.getReceivedDate());
        dto.setExpiryDate(lot.getExpiryDate());
        dto.setInitialQuantity(lot.getInitialQuantity());
        dto.setAvailableQuantity(available);
        dto.setUnit(lot.getUnit());
        dto.setStatus(lot.getStatus());
        dto.setEffectiveStatus(effectiveStatus);
        dto.setExpired(effectiveStatus == MaterialLotStatus.EXPIRED);
        dto.setExpiringSoon(expiringSoon);
        dto.setDaysToExpiry(daysToExpiry);
        dto.setCreatedBy(lot.getCreatedBy());
        dto.setCreatedAt(lot.getCreatedAt());
        dto.setUpdatedAt(lot.getUpdatedAt());
        return dto;
    }

    private StockMovementResponseDto mapToMovementDto(StockMovement sm) {
        StockMovementResponseDto dto = new StockMovementResponseDto();
        dto.setId(sm.getId());
        dto.setMovementNumber(sm.getMovementNumber());
        dto.setReferenceNumber(sm.getReferenceNumber());
        dto.setMovementType(sm.getMovementType());
        if (sm.getRawMaterial() != null) {
            dto.setRawMaterialId(sm.getRawMaterial().getId());
            dto.setMaterialCode(sm.getRawMaterial().getMaterialCode());
            dto.setMaterialName(sm.getRawMaterial().getMaterialName());
        }
        if (sm.getMaterialLot() != null) {
            dto.setMaterialLotId(sm.getMaterialLot().getId());
            dto.setLotNumber(sm.getMaterialLot().getLotNumber());
        }
        if (sm.getWarehouse() != null) {
            dto.setWarehouseId(sm.getWarehouse().getId());
            dto.setWarehouseCode(sm.getWarehouse().getWarehouseCode());
            dto.setWarehouseName(sm.getWarehouse().getWarehouseName());
        }
        if (sm.getStorageLocation() != null) {
            dto.setStorageLocationId(sm.getStorageLocation().getId());
            dto.setLocationCode(sm.getStorageLocation().getLocationCode());
            dto.setZoneName(sm.getStorageLocation().getZoneName());
        }
        if (sm.getManufacturingOrder() != null) {
            dto.setManufacturingOrderId(sm.getManufacturingOrder().getId());
            dto.setOrderNumber(sm.getManufacturingOrder().getOrderNumber());
        }
        if (sm.getBatch() != null) {
            dto.setBatchId(sm.getBatch().getId());
            dto.setBatchNumber(sm.getBatch().getBatchNumber());
        }
        dto.setQuantityDelta(sm.getQuantityDelta());
        dto.setQuantityBefore(sm.getQuantityBefore());
        dto.setQuantityAfter(sm.getQuantityAfter());
        dto.setUnit(sm.getUnit());
        dto.setSupplierName(sm.getSupplierName());
        dto.setSupplierReference(sm.getSupplierReference());
        dto.setReason(sm.getReason());
        dto.setPerformedBy(sm.getPerformedBy());
        dto.setOccurredAt(sm.getOccurredAt());
        return dto;
    }

    private Map<String, Set<String>> buildRecipeLinksByMaterialKey(List<RecipeMaterial> recipeMaterials) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (RecipeMaterial rm : recipeMaterials) {
            if (rm.getMaterialName() == null || rm.getRecipe() == null) continue;
            String key = normalizeKey(rm.getMaterialName());
            String code = rm.getRecipe().getRecipeCode() + " v" + rm.getRecipe().getVersion();
            map.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(code);
        }
        return map;
    }

    private Map<String, Object> buildRawMaterialSnapshot(RawMaterial mat) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", mat.getId());
        snap.put("materialCode", mat.getMaterialCode());
        snap.put("materialName", mat.getMaterialName());
        snap.put("materialType", mat.getMaterialType() != null ? mat.getMaterialType().name() : null);
        snap.put("unit", mat.getUnit());
        snap.put("minimumStockLevel", mat.getMinimumStockLevel());
        snap.put("active", mat.isActive());
        return snap;
    }

    private Map<String, Object> buildWarehouseSnapshot(Warehouse wh) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", wh.getId());
        snap.put("warehouseCode", wh.getWarehouseCode());
        snap.put("warehouseName", wh.getWarehouseName());
        snap.put("storageCondition", wh.getStorageCondition());
        snap.put("active", wh.isActive());
        return snap;
    }

    private Map<String, Object> buildLocationSnapshot(StorageLocation loc) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", loc.getId());
        snap.put("warehouseCode", loc.getWarehouse() != null ? loc.getWarehouse().getWarehouseCode() : null);
        snap.put("locationCode", loc.getLocationCode());
        snap.put("zoneName", loc.getZoneName());
        snap.put("active", loc.isActive());
        return snap;
    }

    private Map<String, Object> buildLotSnapshot(MaterialLot lot, StockMovement movement) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("lotId", lot.getId());
        snap.put("lotNumber", lot.getLotNumber());
        snap.put("materialCode", lot.getRawMaterial() != null ? lot.getRawMaterial().getMaterialCode() : null);
        snap.put("warehouseCode", lot.getWarehouse() != null ? lot.getWarehouse().getWarehouseCode() : null);
        snap.put("locationCode", lot.getStorageLocation() != null ? lot.getStorageLocation().getLocationCode() : null);
        snap.put("availableQuantity", lot.getAvailableQuantity());
        snap.put("unit", lot.getUnit());
        snap.put("status", lot.getStatus() != null ? lot.getStatus().name() : null);
        snap.put("expiryDate", lot.getExpiryDate() != null ? lot.getExpiryDate().toString() : null);
        if (movement != null) {
            snap.put("movementNumber", movement.getMovementNumber());
            snap.put("movementType", movement.getMovementType() != null ? movement.getMovementType().name() : null);
            snap.put("quantityDelta", movement.getQuantityDelta());
            if (movement.getManufacturingOrder() != null) {
                snap.put("orderNumber", movement.getManufacturingOrder().getOrderNumber());
            }
            if (movement.getBatch() != null) {
                snap.put("batchNumber", movement.getBatch().getBatchNumber());
            }
        }
        return snap;
    }

    private static boolean isDateExpired(MaterialLot lot, LocalDate today) {
        return lot.getExpiryDate() != null && lot.getExpiryDate().isBefore(today);
    }

    private static boolean isUsableActiveLot(MaterialLot lot, LocalDate today) {
        return lot.getStatus() == MaterialLotStatus.ACTIVE
                && !isDateExpired(lot, today)
                && lot.getAvailableQuantity() != null
                && lot.getAvailableQuantity().compareTo(BigDecimal.ZERO) > 0;
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeCode(String value) {
        String trimmed = trimRequired(value, "Code");
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static String trimRequired(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidInventoryOperationException(label + " is mandatory");
        }
        return value.trim();
    }

    private static String trimOptional(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeOptionalReference(String value) {
        String trimmed = trimOptional(value);
        return trimmed != null ? trimmed : null;
    }

    private static String generateMovementNumber(String prefix) {
        int year = AppTime.nowDate().getYear();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        return prefix + "-" + year + "-" + suffix;
    }

    private static String formatQuantity(BigDecimal value) {
        if (value == null) return "0";
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }

    private static String truncateReason(String text) {
        if (text == null) return null;
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }
}
