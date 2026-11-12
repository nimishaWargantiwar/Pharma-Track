package com.pharmatrack.controller;

import com.pharmatrack.dto.GoodsReceiptRequestDto;
import com.pharmatrack.dto.InventoryStatusUpdateRequestDto;
import com.pharmatrack.dto.InventorySummaryDto;
import com.pharmatrack.dto.MaterialIssueRequestDto;
import com.pharmatrack.dto.MaterialLotResponseDto;
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
import com.pharmatrack.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST Controller for Raw Material Inventory, Warehouse & Storage Location Management,
 * Goods Receipts, Order-Linked Material Issues, Controlled Stock Adjustments,
 * Lot Traceability, and Append-Only Stock Movement Ledger.
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    // -------------------------------------------------------------------------
    // Dashboard & Recipe Material References
    // -------------------------------------------------------------------------

    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InventorySummaryDto> getDashboardSummary() {
        return ResponseEntity.ok(inventoryService.getDashboardSummary());
    }

    @GetMapping("/recipe-material-references")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<RecipeMaterialReferenceDto>> getRecipeMaterialReferences() {
        return ResponseEntity.ok(inventoryService.getRecipeMaterialReferences());
    }

    // -------------------------------------------------------------------------
    // Raw Material Master
    // -------------------------------------------------------------------------

    @GetMapping("/materials")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<RawMaterialResponseDto>> getAllMaterials() {
        return ResponseEntity.ok(inventoryService.getAllRawMaterials());
    }

    @GetMapping("/materials/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RawMaterialResponseDto> getMaterialById(@PathVariable Long id) {
        return ResponseEntity.ok(inventoryService.getRawMaterialById(id));
    }

    @PostMapping("/materials")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<RawMaterialResponseDto> createMaterial(@Valid @RequestBody RawMaterialRequestDto requestDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.createRawMaterial(requestDto));
    }

    @PutMapping("/materials/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<RawMaterialResponseDto> updateMaterial(@PathVariable Long id,
                                                                 @Valid @RequestBody RawMaterialRequestDto requestDto) {
        return ResponseEntity.ok(inventoryService.updateRawMaterial(id, requestDto));
    }

    @PatchMapping("/materials/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<RawMaterialResponseDto> updateMaterialStatus(
            @PathVariable Long id,
            @Valid @RequestBody InventoryStatusUpdateRequestDto requestDto) {
        return ResponseEntity.ok(inventoryService.updateRawMaterialStatus(id, requestDto));
    }

    // -------------------------------------------------------------------------
    // Warehouses & Storage Locations
    // -------------------------------------------------------------------------

    @GetMapping("/warehouses")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<WarehouseResponseDto>> getAllWarehouses() {
        return ResponseEntity.ok(inventoryService.getAllWarehouses());
    }

    @PostMapping("/warehouses")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<WarehouseResponseDto> createWarehouse(@Valid @RequestBody WarehouseRequestDto requestDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.createWarehouse(requestDto));
    }

    @PutMapping("/warehouses/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<WarehouseResponseDto> updateWarehouse(@PathVariable Long id,
                                                                @Valid @RequestBody WarehouseRequestDto requestDto) {
        return ResponseEntity.ok(inventoryService.updateWarehouse(id, requestDto));
    }

    @PatchMapping("/warehouses/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<WarehouseResponseDto> updateWarehouseStatus(
            @PathVariable Long id,
            @Valid @RequestBody InventoryStatusUpdateRequestDto requestDto) {
        return ResponseEntity.ok(inventoryService.updateWarehouseStatus(id, requestDto));
    }

    @GetMapping("/locations")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<StorageLocationResponseDto>> getAllLocations(
            @RequestParam(required = false) Long warehouseId) {
        return ResponseEntity.ok(inventoryService.getAllStorageLocations(warehouseId));
    }

    @GetMapping("/warehouses/{warehouseId}/locations")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<StorageLocationResponseDto>> getLocationsByWarehouse(@PathVariable Long warehouseId) {
        return ResponseEntity.ok(inventoryService.getAllStorageLocations(warehouseId));
    }

    @PostMapping("/warehouses/{warehouseId}/locations")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<StorageLocationResponseDto> createStorageLocation(
            @PathVariable Long warehouseId,
            @Valid @RequestBody StorageLocationRequestDto requestDto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(inventoryService.createStorageLocation(warehouseId, requestDto));
    }

    @PutMapping("/locations/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<StorageLocationResponseDto> updateStorageLocation(
            @PathVariable Long id,
            @Valid @RequestBody StorageLocationRequestDto requestDto) {
        return ResponseEntity.ok(inventoryService.updateStorageLocation(id, requestDto));
    }

    @PatchMapping("/locations/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<StorageLocationResponseDto> updateStorageLocationStatus(
            @PathVariable Long id,
            @Valid @RequestBody InventoryStatusUpdateRequestDto requestDto) {
        return ResponseEntity.ok(inventoryService.updateStorageLocationStatus(id, requestDto));
    }

    // -------------------------------------------------------------------------
    // Material Lots, Order Requirements & Stock Movements
    // -------------------------------------------------------------------------

    @GetMapping("/lots")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<MaterialLotResponseDto>> getAllLots(
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) Long warehouseId) {
        return ResponseEntity.ok(inventoryService.getAllLots(materialId, warehouseId));
    }

    @GetMapping("/lots/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MaterialLotResponseDto> getLotById(@PathVariable Long id) {
        return ResponseEntity.ok(inventoryService.getLotById(id));
    }

    @GetMapping("/movements")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<StockMovementResponseDto>> getAllMovements(
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) Long orderId) {
        return ResponseEntity.ok(inventoryService.getAllStockMovements(materialId, orderId));
    }

    @GetMapping("/orders/{orderId}/requirements")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<OrderMaterialRequirementsResponseDto> getOrderMaterialRequirements(@PathVariable Long orderId) {
        return ResponseEntity.ok(inventoryService.getOrderMaterialRequirements(orderId));
    }

    // -------------------------------------------------------------------------
    // Stock Mutation Operations (Goods Receipt, Material Issue, Adjustment)
    // -------------------------------------------------------------------------

    @PostMapping("/receipts")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<StockMovementResponseDto> receiveGoods(@Valid @RequestBody GoodsReceiptRequestDto requestDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.receiveGoods(requestDto));
    }

    @PostMapping("/issues")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR', 'OPERATOR')")
    public ResponseEntity<StockMovementResponseDto> issueMaterial(@Valid @RequestBody MaterialIssueRequestDto requestDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.issueMaterial(requestDto));
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR', 'QA_REVIEWER')")
    public ResponseEntity<StockMovementResponseDto> adjustStock(@Valid @RequestBody StockAdjustmentRequestDto requestDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.adjustStock(requestDto));
    }
}

