package com.pharmatrack;

import com.pharmatrack.dto.BatchStatusUpdateDto;
import com.pharmatrack.dto.GoodsReceiptRequestDto;
import com.pharmatrack.dto.InventoryStatusUpdateRequestDto;
import com.pharmatrack.dto.InventorySummaryDto;
import com.pharmatrack.dto.MaterialIssueRequestDto;
import com.pharmatrack.dto.OrderMaterialRequirementsResponseDto;
import com.pharmatrack.dto.OrderRequestDto;
import com.pharmatrack.dto.OrderResponseDto;
import com.pharmatrack.dto.OrderStatusUpdateDto;
import com.pharmatrack.dto.RawMaterialRequestDto;
import com.pharmatrack.dto.RawMaterialResponseDto;
import com.pharmatrack.dto.StockAdjustmentRequestDto;
import com.pharmatrack.dto.StockMovementResponseDto;
import com.pharmatrack.dto.StorageLocationRequestDto;
import com.pharmatrack.dto.StorageLocationResponseDto;
import com.pharmatrack.dto.WarehouseRequestDto;
import com.pharmatrack.dto.WarehouseResponseDto;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.MaterialLot;
import com.pharmatrack.entity.MaterialLotStatus;
import com.pharmatrack.entity.MaterialStockStatus;
import com.pharmatrack.entity.MaterialType;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeMaterial;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.entity.StockAdjustmentType;
import com.pharmatrack.entity.StockMovement;
import com.pharmatrack.entity.StockMovementType;
import com.pharmatrack.exception.DuplicateResourceException;
import com.pharmatrack.exception.InvalidInventoryOperationException;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.MaterialLotRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.RawMaterialRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.repository.StockMovementRepository;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.InventoryService;
import com.pharmatrack.service.ManufacturingOrderService;
import com.pharmatrack.util.AppTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Isolated Spring Boot + H2 (MODE=MySQL) service tests covering Raw Material Inventory,
 * Warehouses &amp; Storage Locations, Goods Receipts, BOM-validated Material Issues,
 * Controlled Stock Adjustments, StockMovement immutability, multi-threaded PESSIMISTIC_WRITE
 * concurrency safety, and full transaction rollback upon audit failure.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_inv_service_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryServiceAndConcurrencyTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ManufacturingOrderService manufacturingOrderService;

    @Autowired
    private RawMaterialRepository rawMaterialRepository;

    @Autowired
    private MaterialLotRepository materialLotRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @SpyBean
    private AuditTrailService auditTrailService;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        Mockito.reset(auditTrailService);
    }

    private void authenticateAs(String username, String role) {
        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                AuthorityUtils.createAuthorityList(authority)
        ));
        SecurityContextHolder.setContext(context);
    }

    private RawMaterialResponseDto createMaterial(String codePrefix, String name, String unit, BigDecimal minLevel) {
        authenticateAs("inv.supervisor", "PRODUCTION_SUPERVISOR");
        int n = SEQ.getAndIncrement();
        RawMaterialRequestDto dto = new RawMaterialRequestDto();
        dto.setMaterialCode(codePrefix + "-" + n);
        dto.setMaterialName(name);
        dto.setMaterialType(MaterialType.API);
        dto.setUnit(unit);
        dto.setMinimumStockLevel(minLevel);
        dto.setDescription("Service test raw material " + n);
        dto.setActive(true);
        return inventoryService.createRawMaterial(dto);
    }

    private WarehouseResponseDto createWarehouse(String codePrefix) {
        authenticateAs("inv.supervisor", "PRODUCTION_SUPERVISOR");
        int n = SEQ.getAndIncrement();
        WarehouseRequestDto dto = new WarehouseRequestDto();
        dto.setWarehouseCode(codePrefix + "-" + n);
        dto.setWarehouseName("Warehouse " + codePrefix + " " + n);
        dto.setLocationSummary("Building B");
        dto.setStorageCondition("Controlled Room Temp");
        dto.setActive(true);
        return inventoryService.createWarehouse(dto);
    }

    private StorageLocationResponseDto createLocation(Long warehouseId, String code) {
        authenticateAs("inv.supervisor", "PRODUCTION_SUPERVISOR");
        StorageLocationRequestDto dto = new StorageLocationRequestDto();
        dto.setLocationCode(code + "-" + SEQ.getAndIncrement());
        dto.setZoneName("Zone A");
        dto.setAisle("A1");
        dto.setRack("R1");
        dto.setBin("B1");
        dto.setActive(true);
        return inventoryService.createStorageLocation(warehouseId, dto);
    }

    private Recipe createApprovedRecipeWithMaterials(String tag, BigDecimal batchSize, List<RecipeMaterial> materials) {
        int n = SEQ.getAndIncrement();
        Product product = productRepository.saveAndFlush(new Product(
                "INV-PRD-" + tag + "-" + n,
                "Inventory Product " + tag + " " + n,
                "500 mg",
                "Tablet",
                "Formulation for inventory test",
                ProductStatus.ACTIVE
        ));

        Recipe recipe = new Recipe(
                "INV-RCP-" + tag + "-" + n,
                product,
                1,
                batchSize,
                "kg",
                RecipeStatus.APPROVED,
                "Approved BOM recipe"
        );
        for (RecipeMaterial mat : materials) {
            recipe.addMaterial(new RecipeMaterial(mat.getMaterialName(), mat.getQuantity(), mat.getUnit()));
        }
        recipe.addStep(new RecipeStep(1, "Dispensing", "Verify weight and dispense"));
        return recipeRepository.saveAndFlush(recipe);
    }

    private OrderResponseDto createAndOptionallyReleaseOrder(Recipe recipe, int batches, boolean release) {
        authenticateAs("inv.supervisor", "PRODUCTION_SUPERVISOR");
        LocalDate today = AppTime.nowDate();
        OrderRequestDto orderReq = new OrderRequestDto(
                recipe.getProduct().getId(),
                recipe.getId(),
                recipe.getBatchSize().multiply(BigDecimal.valueOf(batches)),
                recipe.getBatchUnit(),
                today,
                today.plusDays(5),
                "Inventory test order"
        );
        OrderResponseDto created = manufacturingOrderService.createOrder(orderReq);
        if (release) {
            created = manufacturingOrderService.updateOrderStatus(
                    created.getId(), new OrderStatusUpdateDto(OrderStatus.RELEASED));
        }
        return created;
    }

    @Test
    @DisplayName("1. StockMovementRepository exposes append-only contract and JPA @PreRemove / column immutability protects rows")
    void stockMovementIsAppendOnlyAndImmutable() {
        // Repository interface must NOT expose delete/update methods from CrudRepository/JpaRepository
        assertFalse(CrudRepository.class.isAssignableFrom(StockMovementRepository.class));
        assertFalse(PagingAndSortingRepository.class.isAssignableFrom(StockMovementRepository.class));
        for (Method method : StockMovementRepository.class.getMethods()) {
            assertFalse(method.getName().startsWith("delete"), "Unexpected delete method: " + method.getName());
            assertFalse(method.getName().startsWith("remove"), "Unexpected remove method: " + method.getName());
        }

        RawMaterialResponseDto mat = createMaterial("IMM", "Immutable Test Excipient", "kg", new BigDecimal("10.0000"));
        WarehouseResponseDto wh = createWarehouse("WH-IMM");
        StorageLocationResponseDto loc = createLocation(wh.getId(), "BIN-IMM");

        LocalDate today = AppTime.nowDate();
        GoodsReceiptRequestDto grn = new GoodsReceiptRequestDto();
        grn.setRawMaterialId(mat.getId());
        grn.setWarehouseId(wh.getId());
        grn.setStorageLocationId(loc.getId());
        grn.setLotNumber("LOT-IMM-" + SEQ.getAndIncrement());
        grn.setQuantity(new BigDecimal("40.0000"));
        grn.setUnit("kg");
        grn.setReceivedDate(today);
        grn.setExpiryDate(today.plusMonths(12));
        grn.setSupplierName("CleanChem Co");
        grn.setReason("Original immutable reason");

        StockMovementResponseDto saved = inventoryService.receiveGoods(grn);
        assertNotNull(saved.getId());

        // Attempting to remove via EntityManager must throw UnsupportedOperationException (@PreRemove)
        assertThrows(UnsupportedOperationException.class, () ->
                transactionTemplate.executeWithoutResult(status -> {
                    StockMovement managed = entityManager.find(StockMovement.class, saved.getId());
                    assertNotNull(managed);
                    entityManager.remove(managed);
                    entityManager.flush();
                }));

        // Attempting to mutate updatable=false fields via EntityManager must never alter persisted row
        try {
            transactionTemplate.executeWithoutResult(status -> {
                StockMovement managed = entityManager.find(StockMovement.class, saved.getId());
                managed.setReason("Tampered reason");
                managed.setPerformedBy("spoofed.actor");
                entityManager.flush();
            });
        } catch (UnsupportedOperationException | PersistenceException ignored) {
            // Expected if Hibernate triggers @PreUpdate
        }

        StockMovement reloaded = stockMovementRepository.findById(saved.getId()).orElseThrow();
        assertEquals("Original immutable reason", reloaded.getReason());
        assertEquals("inv.supervisor", reloaded.getPerformedBy());
    }

    @Test
    @DisplayName("2. Goods Receipt validation enforces dates, positive qty, unit match, unique lot & reference, and active masters")
    void goodsReceiptValidationsEnforcedStrictly() {
        String materialName = "Paracetamol API " + SEQ.getAndIncrement();
        RawMaterialResponseDto activeMat = createMaterial("GRN", materialName, "kg", new BigDecimal("50.0000"));
        WarehouseResponseDto activeWh = createWarehouse("WH-GRN");
        StorageLocationResponseDto activeLoc = createLocation(activeWh.getId(), "A1");
        WarehouseResponseDto otherWh = createWarehouse("WH-OTH");
        StorageLocationResponseDto otherLoc = createLocation(otherWh.getId(), "B1");

        LocalDate today = AppTime.nowDate();

        // Future received date -> rejected
        GoodsReceiptRequestDto futureReceived = baseReceipt(activeMat.getId(), activeWh.getId(), activeLoc.getId(), today.plusDays(1), today.plusYears(1));
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.receiveGoods(futureReceived));

        // Expiry <= receivedDate -> rejected
        GoodsReceiptRequestDto nonAfterExpiry = baseReceipt(activeMat.getId(), activeWh.getId(), activeLoc.getId(), today, today);
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.receiveGoods(nonAfterExpiry));

        // Already expired lot -> rejected
        GoodsReceiptRequestDto expiredLot = baseReceipt(activeMat.getId(), activeWh.getId(), activeLoc.getId(), today.minusDays(10), today.minusDays(1));
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.receiveGoods(expiredLot));

        // Unit mismatch -> rejected
        GoodsReceiptRequestDto badUnit = baseReceipt(activeMat.getId(), activeWh.getId(), activeLoc.getId(), today, today.plusDays(120));
        badUnit.setUnit("L");
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.receiveGoods(badUnit));

        // Location belongs to another warehouse -> rejected
        GoodsReceiptRequestDto wrongLocWh = baseReceipt(activeMat.getId(), activeWh.getId(), otherLoc.getId(), today, today.plusDays(120));
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.receiveGoods(wrongLocWh));

        // Deactivate location -> rejected
        inventoryService.updateStorageLocationStatus(activeLoc.getId(), new InventoryStatusUpdateRequestDto(false));
        GoodsReceiptRequestDto inactiveLocReq = baseReceipt(activeMat.getId(), activeWh.getId(), activeLoc.getId(), today, today.plusDays(120));
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.receiveGoods(inactiveLocReq));
        inventoryService.updateStorageLocationStatus(activeLoc.getId(), new InventoryStatusUpdateRequestDto(true));

        // Valid goods receipt -> succeeds with LOW_STOCK (30 <= 50)
        GoodsReceiptRequestDto validFirst = baseReceipt(activeMat.getId(), activeWh.getId(), activeLoc.getId(), today, today.plusDays(180));
        validFirst.setQuantity(new BigDecimal("30.0000"));
        validFirst.setReferenceNumber("GRN-REF-" + SEQ.getAndIncrement());
        StockMovementResponseDto grn1 = inventoryService.receiveGoods(validFirst);
        assertEquals(StockMovementType.GOODS_RECEIPT, grn1.getMovementType());
        assertEquals(0, new BigDecimal("30.0000").compareTo(grn1.getQuantityDelta()));

        RawMaterialResponseDto afterFirst = inventoryService.getRawMaterialById(activeMat.getId());
        assertEquals(MaterialStockStatus.LOW_STOCK, afterFirst.getStockStatus());
        assertEquals(0, new BigDecimal("30.0000").compareTo(afterFirst.getAvailableStock()));

        // Duplicate (rawMaterialId, lotNumber) case-insensitive -> DuplicateResourceException
        GoodsReceiptRequestDto dupLot = baseReceipt(activeMat.getId(), activeWh.getId(), activeLoc.getId(), today, today.plusDays(180));
        dupLot.setLotNumber(validFirst.getLotNumber().toLowerCase());
        dupLot.setReferenceNumber("GRN-REF-" + SEQ.getAndIncrement());
        assertThrows(DuplicateResourceException.class, () -> inventoryService.receiveGoods(dupLot));

        // Duplicate referenceNumber -> DuplicateResourceException
        GoodsReceiptRequestDto dupRef = baseReceipt(activeMat.getId(), activeWh.getId(), activeLoc.getId(), today, today.plusDays(180));
        dupRef.setReferenceNumber(validFirst.getReferenceNumber());
        assertThrows(DuplicateResourceException.class, () -> inventoryService.receiveGoods(dupRef));

        // Second receipt pushes available stock > minimumStockLevel -> IN_STOCK
        GoodsReceiptRequestDto validSecond = baseReceipt(activeMat.getId(), activeWh.getId(), activeLoc.getId(), today, today.plusDays(20));
        validSecond.setQuantity(new BigDecimal("40.0000"));
        inventoryService.receiveGoods(validSecond);

        RawMaterialResponseDto afterSecond = inventoryService.getRawMaterialById(activeMat.getId());
        assertEquals(MaterialStockStatus.IN_STOCK, afterSecond.getStockStatus());
        assertEquals(0, new BigDecimal("70.0000").compareTo(afterSecond.getAvailableStock()));
        assertEquals(2, afterSecond.getActiveLotCount());
        assertEquals(1, afterSecond.getExpiringSoonLotCount());

        // Prevent changing material unit once lots exist
        RawMaterialRequestDto unitChange = new RawMaterialRequestDto();
        unitChange.setMaterialCode(afterSecond.getMaterialCode());
        unitChange.setMaterialName(afterSecond.getMaterialName());
        unitChange.setMaterialType(afterSecond.getMaterialType());
        unitChange.setUnit("g");
        unitChange.setMinimumStockLevel(afterSecond.getMinimumStockLevel());
        assertThrows(InvalidInventoryOperationException.class,
                () -> inventoryService.updateRawMaterial(activeMat.getId(), unitChange));
    }

    @Test
    @DisplayName("3. Material Issue validates order/batch status, recipe BOM cumulative qty (excluding cancelled batches), and lot lifecycle")
    void materialIssueValidatesBomLifecycleAndDepletion() {
        String apiName = "Atorvastatin API " + SEQ.getAndIncrement();
        String unlistedName = "Unlisted Filler " + SEQ.getAndIncrement();
        RawMaterialResponseDto apiMat = createMaterial("BOM-API", apiName, "kg", new BigDecimal("10.0000"));
        RawMaterialResponseDto unlistedMat = createMaterial("BOM-UNL", unlistedName, "kg", new BigDecimal("5.0000"));
        WarehouseResponseDto wh = createWarehouse("WH-BOM");
        StorageLocationResponseDto loc = createLocation(wh.getId(), "Z1");

        // Recipe: 100 kg batch requires 25 kg of apiName
        Recipe recipe = createApprovedRecipeWithMaterials(
                "BOM",
                new BigDecimal("100.0000"),
                List.of(new RecipeMaterial(apiName, new BigDecimal("25.0000"), "kg"))
        );

        // Planned order (2 batches = 50 kg total) -> cannot issue before RELEASED
        OrderResponseDto plannedOrder = createAndOptionallyReleaseOrder(recipe, 2, false);

        LocalDate today = AppTime.nowDate();
        GoodsReceiptRequestDto apiReceipt = baseReceipt(apiMat.getId(), wh.getId(), loc.getId(), today, today.plusDays(90));
        apiReceipt.setQuantity(new BigDecimal("40.0000"));
        StockMovementResponseDto apiGrn = inventoryService.receiveGoods(apiReceipt);
        Long apiLotId = apiGrn.getMaterialLotId();

        GoodsReceiptRequestDto unlistedReceipt = baseReceipt(unlistedMat.getId(), wh.getId(), loc.getId(), today, today.plusDays(90));
        unlistedReceipt.setQuantity(new BigDecimal("20.0000"));
        Long unlistedLotId = inventoryService.receiveGoods(unlistedReceipt).getMaterialLotId();

        authenticateAs("floor.operator", "OPERATOR");
        MaterialIssueRequestDto plannedAttempt = new MaterialIssueRequestDto();
        plannedAttempt.setManufacturingOrderId(plannedOrder.getId());
        plannedAttempt.setRawMaterialId(apiMat.getId());
        plannedAttempt.setMaterialLotId(apiLotId);
        plannedAttempt.setQuantity(new BigDecimal("10.0000"));
        plannedAttempt.setUnit("kg");
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.issueMaterial(plannedAttempt));

        // Cancel 1 of the 2 planned batches before release -> effective activeBatchCount = 1 -> BOM total requirement = 25 kg!
        authenticateAs("inv.supervisor", "PRODUCTION_SUPERVISOR");
        Long batch1Id = plannedOrder.getBatches().get(0).getId();
        Long batch2Id = plannedOrder.getBatches().get(1).getId();
        manufacturingOrderService.updateBatchStatus(batch1Id, new BatchStatusUpdateDto(BatchStatus.CANCELLED));
        manufacturingOrderService.updateOrderStatus(plannedOrder.getId(), new OrderStatusUpdateDto(OrderStatus.RELEASED));

        OrderMaterialRequirementsResponseDto reqs = inventoryService.getOrderMaterialRequirements(plannedOrder.getId());
        assertEquals(2, reqs.getTargetBatchCount());
        assertEquals(1, reqs.getActiveBatchCount());
        assertTrue(reqs.isEligibleForIssue());
        assertEquals(0, new BigDecimal("25.0000").compareTo(reqs.getRequirements().get(0).getTotalRequiredQuantity()));

        authenticateAs("floor.operator", "OPERATOR");

        // Unlisted material rejected against recipe BOM
        MaterialIssueRequestDto unlistedAttempt = new MaterialIssueRequestDto();
        unlistedAttempt.setManufacturingOrderId(plannedOrder.getId());
        unlistedAttempt.setRawMaterialId(unlistedMat.getId());
        unlistedAttempt.setMaterialLotId(unlistedLotId);
        unlistedAttempt.setQuantity(new BigDecimal("5.0000"));
        unlistedAttempt.setUnit("kg");
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.issueMaterial(unlistedAttempt));

        // Issuing to cancelled batch rejected
        MaterialIssueRequestDto cancelledBatchAttempt = new MaterialIssueRequestDto();
        cancelledBatchAttempt.setManufacturingOrderId(plannedOrder.getId());
        cancelledBatchAttempt.setBatchId(batch1Id);
        cancelledBatchAttempt.setRawMaterialId(apiMat.getId());
        cancelledBatchAttempt.setMaterialLotId(apiLotId);
        cancelledBatchAttempt.setQuantity(new BigDecimal("10.0000"));
        cancelledBatchAttempt.setUnit("kg");
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.issueMaterial(cancelledBatchAttempt));

        // Quarantine lot blocks issue, then release quarantine restores issue
        authenticateAs("qa.reviewer", "QA_REVIEWER");
        StockAdjustmentRequestDto quarantineReq = new StockAdjustmentRequestDto();
        quarantineReq.setMaterialLotId(apiLotId);
        quarantineReq.setAdjustmentType(StockAdjustmentType.QUARANTINE);
        quarantineReq.setReason("Hold pending assay confirmation");
        inventoryService.adjustStock(quarantineReq);

        authenticateAs("floor.operator", "OPERATOR");
        MaterialIssueRequestDto quarantinedAttempt = new MaterialIssueRequestDto();
        quarantinedAttempt.setManufacturingOrderId(plannedOrder.getId());
        quarantinedAttempt.setBatchId(batch2Id);
        quarantinedAttempt.setRawMaterialId(apiMat.getId());
        quarantinedAttempt.setMaterialLotId(apiLotId);
        quarantinedAttempt.setQuantity(new BigDecimal("10.0000"));
        quarantinedAttempt.setUnit("kg");
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.issueMaterial(quarantinedAttempt));

        authenticateAs("qa.reviewer", "QA_REVIEWER");
        StockAdjustmentRequestDto releaseReq = new StockAdjustmentRequestDto();
        releaseReq.setMaterialLotId(apiLotId);
        releaseReq.setAdjustmentType(StockAdjustmentType.RELEASE_QUARANTINE);
        releaseReq.setReason("Assay within specification");
        inventoryService.adjustStock(releaseReq);

        // Partial issue 15 kg to batch2 -> remaining BOM requirement is 10 kg
        authenticateAs("floor.operator", "OPERATOR");
        MaterialIssueRequestDto firstIssue = new MaterialIssueRequestDto();
        firstIssue.setManufacturingOrderId(plannedOrder.getId());
        firstIssue.setBatchId(batch2Id);
        firstIssue.setRawMaterialId(apiMat.getId());
        firstIssue.setMaterialLotId(apiLotId);
        firstIssue.setQuantity(new BigDecimal("15.0000"));
        firstIssue.setUnit("kg");
        StockMovementResponseDto issue1Res = inventoryService.issueMaterial(firstIssue);
        assertEquals(StockMovementType.MATERIAL_ISSUE, issue1Res.getMovementType());
        assertEquals(0, new BigDecimal("-15.0000").compareTo(issue1Res.getQuantityDelta()));
        assertEquals(0, new BigDecimal("25.0000").compareTo(issue1Res.getQuantityAfter()));

        // Attempting 12 kg exceeds remaining 10 kg BOM requirement
        MaterialIssueRequestDto overBomAttempt = new MaterialIssueRequestDto();
        overBomAttempt.setManufacturingOrderId(plannedOrder.getId());
        overBomAttempt.setBatchId(batch2Id);
        overBomAttempt.setRawMaterialId(apiMat.getId());
        overBomAttempt.setMaterialLotId(apiLotId);
        overBomAttempt.setQuantity(new BigDecimal("12.0000"));
        overBomAttempt.setUnit("kg");
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.issueMaterial(overBomAttempt));

        // Issue remaining 10 kg -> BOM fulfilled; lot has 15 kg left
        MaterialIssueRequestDto secondIssue = new MaterialIssueRequestDto();
        secondIssue.setManufacturingOrderId(plannedOrder.getId());
        secondIssue.setBatchId(batch2Id);
        secondIssue.setRawMaterialId(apiMat.getId());
        secondIssue.setMaterialLotId(apiLotId);
        secondIssue.setQuantity(new BigDecimal("10.0000"));
        secondIssue.setUnit("kg");
        inventoryService.issueMaterial(secondIssue);

        OrderMaterialRequirementsResponseDto afterFulfill = inventoryService.getOrderMaterialRequirements(plannedOrder.getId());
        assertTrue(afterFulfill.getRequirements().get(0).isFulfilled());
        assertEquals(0, BigDecimal.ZERO.compareTo(afterFulfill.getRequirements().get(0).getRemainingRequiredQuantity()));

        // Issue remaining 15 kg to a second order -> lot transitions to DEPLETED at 0.0000
        OrderResponseDto secondOrder = createAndOptionallyReleaseOrder(recipe, 1, true);
        authenticateAs("floor.operator", "OPERATOR");
        MaterialIssueRequestDto depleteIssue = new MaterialIssueRequestDto();
        depleteIssue.setManufacturingOrderId(secondOrder.getId());
        depleteIssue.setRawMaterialId(apiMat.getId());
        depleteIssue.setMaterialLotId(apiLotId);
        depleteIssue.setQuantity(new BigDecimal("15.0000"));
        depleteIssue.setUnit("kg");
        inventoryService.issueMaterial(depleteIssue);

        MaterialLot depletedLot = materialLotRepository.findById(apiLotId).orElseThrow();
        assertEquals(MaterialLotStatus.DEPLETED, depletedLot.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(depletedLot.getAvailableQuantity()));

        // Further issue from depleted lot rejected
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.issueMaterial(depleteIssue));
    }

    @Test
    @DisplayName("4. Controlled Stock Adjustment handles INCREASE, DECREASE, negative guard, and expired quarantine-release block")
    void stockAdjustmentControlsAndGuards() {
        RawMaterialResponseDto mat = createMaterial("ADJ", "Magnesium Stearate " + SEQ.getAndIncrement(), "kg", new BigDecimal("5.0000"));
        WarehouseResponseDto wh = createWarehouse("WH-ADJ");
        StorageLocationResponseDto loc = createLocation(wh.getId(), "C1");

        LocalDate today = AppTime.nowDate();
        GoodsReceiptRequestDto grn = baseReceipt(mat.getId(), wh.getId(), loc.getId(), today, today.plusDays(60));
        grn.setQuantity(new BigDecimal("20.0000"));
        Long lotId = inventoryService.receiveGoods(grn).getMaterialLotId();

        authenticateAs("qa.reviewer", "QA_REVIEWER");

        // Negative stock decrease guard
        StockAdjustmentRequestDto tooMuchDecrease = new StockAdjustmentRequestDto();
        tooMuchDecrease.setMaterialLotId(lotId);
        tooMuchDecrease.setAdjustmentType(StockAdjustmentType.DECREASE);
        tooMuchDecrease.setQuantity(new BigDecimal("25.0000"));
        tooMuchDecrease.setReason("Sampling + spill count");
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.adjustStock(tooMuchDecrease));

        // Missing reason guard
        StockAdjustmentRequestDto blankReason = new StockAdjustmentRequestDto();
        blankReason.setMaterialLotId(lotId);
        blankReason.setAdjustmentType(StockAdjustmentType.INCREASE);
        blankReason.setQuantity(new BigDecimal("5.0000"));
        blankReason.setReason("   ");
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.adjustStock(blankReason));

        // INCREASE by 5 kg -> 25 kg
        StockAdjustmentRequestDto increase = new StockAdjustmentRequestDto();
        increase.setMaterialLotId(lotId);
        increase.setAdjustmentType(StockAdjustmentType.INCREASE);
        increase.setQuantity(new BigDecimal("5.0000"));
        increase.setReason("Cycle count reconciliation");
        StockMovementResponseDto incRes = inventoryService.adjustStock(increase);
        assertEquals(0, new BigDecimal("25.0000").compareTo(incRes.getQuantityAfter()));

        // DECREASE by 25 kg -> 0 kg & DEPLETED
        StockAdjustmentRequestDto fullDecrease = new StockAdjustmentRequestDto();
        fullDecrease.setMaterialLotId(lotId);
        fullDecrease.setAdjustmentType(StockAdjustmentType.DECREASE);
        fullDecrease.setQuantity(new BigDecimal("25.0000"));
        fullDecrease.setReason("Scrapped damaged container");
        inventoryService.adjustStock(fullDecrease);

        MaterialLot afterScrap = materialLotRepository.findById(lotId).orElseThrow();
        assertEquals(MaterialLotStatus.DEPLETED, afterScrap.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(afterScrap.getAvailableQuantity()));

        // Quarantined expired lot cannot be released back to ACTIVE
        MaterialLot expiredQuarantined = transactionTemplate.execute(status -> {
            MaterialLot ml = new MaterialLot();
            ml.setRawMaterial(rawMaterialRepository.findById(mat.getId()).orElseThrow());
            ml.setWarehouse(materialLotRepository.findById(lotId).orElseThrow().getWarehouse());
            ml.setStorageLocation(materialLotRepository.findById(lotId).orElseThrow().getStorageLocation());
            ml.setLotNumber("LOT-EXP-Q-" + SEQ.getAndIncrement());
            ml.setSupplierName("Legacy Supplier");
            ml.setReceivedDate(today.minusDays(90));
            ml.setExpiryDate(today.minusDays(2));
            ml.setInitialQuantity(new BigDecimal("10.0000"));
            ml.setAvailableQuantity(new BigDecimal("10.0000"));
            ml.setUnit("kg");
            ml.setStatus(MaterialLotStatus.QUARANTINED);
            ml.setCreatedBy("inv.supervisor");
            return materialLotRepository.saveAndFlush(ml);
        });

        StockAdjustmentRequestDto releaseExpired = new StockAdjustmentRequestDto();
        releaseExpired.setMaterialLotId(expiredQuarantined.getId());
        releaseExpired.setAdjustmentType(StockAdjustmentType.RELEASE_QUARANTINE);
        releaseExpired.setReason("Attempt release after expiry");
        assertThrows(InvalidInventoryOperationException.class, () -> inventoryService.adjustStock(releaseExpired));

        // Summary reflects counts consistently
        InventorySummaryDto summary = inventoryService.getDashboardSummary();
        assertTrue(summary.getTotalMaterialsCount() >= 1);
        assertTrue(summary.getExpiredLotCount() >= 1);
    }

    @Test
    @DisplayName("5. Multi-threaded PESSIMISTIC_WRITE concurrency test prevents oversell and negative stock across 8 simultaneous issues")
    void concurrentMaterialIssuesPreventNegativeAndOverIssue() throws Exception {
        String apiName = "Concurrent API " + SEQ.getAndIncrement();
        RawMaterialResponseDto mat = createMaterial("CONC", apiName, "kg", new BigDecimal("5.0000"));
        WarehouseResponseDto wh = createWarehouse("WH-CONC");
        StorageLocationResponseDto loc = createLocation(wh.getId(), "C-BIN");

        // BOM allows up to 240 kg (2 batches x 120 kg) so the 100 kg lot balance is the limiting constraint
        Recipe recipe = createApprovedRecipeWithMaterials(
                "CONC",
                new BigDecimal("100.0000"),
                List.of(new RecipeMaterial(apiName, new BigDecimal("120.0000"), "kg"))
        );
        OrderResponseDto order = createAndOptionallyReleaseOrder(recipe, 2, true);

        LocalDate today = AppTime.nowDate();
        GoodsReceiptRequestDto grn = baseReceipt(mat.getId(), wh.getId(), loc.getId(), today, today.plusDays(180));
        grn.setQuantity(new BigDecimal("100.0000"));
        Long lotId = inventoryService.receiveGoods(grn).getMaterialLotId();

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyGate = new CountDownLatch(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int workerNum = i + 1;
            futures.add(executor.submit(() -> {
                readyGate.countDown();
                try {
                    assertTrue(startGate.await(10, TimeUnit.SECONDS));
                    authenticateAs("conc.op." + workerNum, "OPERATOR");
                    MaterialIssueRequestDto req = new MaterialIssueRequestDto();
                    req.setManufacturingOrderId(order.getId());
                    req.setRawMaterialId(mat.getId());
                    req.setMaterialLotId(lotId);
                    req.setQuantity(new BigDecimal("30.0000"));
                    req.setUnit("kg");
                    req.setReason("Concurrent issue attempt " + workerNum);
                    inventoryService.issueMaterial(req);
                    successCount.incrementAndGet();
                } catch (InvalidInventoryOperationException ex) {
                    failureCount.incrementAndGet();
                } finally {
                    SecurityContextHolder.clearContext();
                }
                return null;
            }));
        }

        assertTrue(readyGate.await(10, TimeUnit.SECONDS));
        startGate.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(3, successCount.get(), "Exactly 3 issues of 30 kg must succeed from a 100 kg lot");
        assertEquals(5, failureCount.get(), "Remaining 5 concurrent issues must fail safely");

        MaterialLot finalLot = materialLotRepository.findById(lotId).orElseThrow();
        assertEquals(MaterialLotStatus.ACTIVE, finalLot.getStatus());
        assertEquals(0, new BigDecimal("10.0000").compareTo(finalLot.getAvailableQuantity()),
                "Lot balance must never go negative and must equal 100 - 3*30 = 10 kg");

        BigDecimal totalIssued = stockMovementRepository.sumIssuedQuantityForOrderAndMaterial(
                order.getId(), mat.getId(), StockMovementType.MATERIAL_ISSUE);
        assertEquals(0, new BigDecimal("90.0000").compareTo(totalIssued));
    }

    @Test
    @DisplayName("6. Audit persistence failure completely rolls back Goods Receipt and Material Issue lot & movement changes")
    void auditFailureRollsBackInventoryTransactionsCompletely() {
        String apiName = "Rollback API " + SEQ.getAndIncrement();
        RawMaterialResponseDto mat = createMaterial("RB", apiName, "kg", new BigDecimal("10.0000"));
        WarehouseResponseDto wh = createWarehouse("WH-RB");
        StorageLocationResponseDto loc = createLocation(wh.getId(), "RB-BIN");
        Recipe recipe = createApprovedRecipeWithMaterials(
                "RB",
                new BigDecimal("100.0000"),
                List.of(new RecipeMaterial(apiName, new BigDecimal("50.0000"), "kg"))
        );
        OrderResponseDto order = createAndOptionallyReleaseOrder(recipe, 1, true);

        long lotsBeforeGrnFail = materialLotRepository.count();
        long movementsBeforeGrnFail = stockMovementRepository.count();
        long auditsBeforeGrnFail = auditLogRepository.count();

        doThrow(new RuntimeException("Injected audit failure on Goods Receipt"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        LocalDate today = AppTime.nowDate();
        GoodsReceiptRequestDto failedGrn = baseReceipt(mat.getId(), wh.getId(), loc.getId(), today, today.plusDays(90));
        failedGrn.setQuantity(new BigDecimal("50.0000"));

        authenticateAs("inv.supervisor", "PRODUCTION_SUPERVISOR");
        assertThrows(RuntimeException.class, () -> inventoryService.receiveGoods(failedGrn));

        assertEquals(lotsBeforeGrnFail, materialLotRepository.count());
        assertEquals(movementsBeforeGrnFail, stockMovementRepository.count());
        assertEquals(auditsBeforeGrnFail, auditLogRepository.count());

        // Reset spy and record a valid Goods Receipt
        Mockito.reset(auditTrailService);
        Long validLotId = inventoryService.receiveGoods(failedGrn).getMaterialLotId();
        long movementsBeforeIssueFail = stockMovementRepository.count();
        long auditsBeforeIssueFail = auditLogRepository.count();

        // Inject audit failure on Material Issue -> both lot deduction and StockMovement insert must roll back
        doThrow(new RuntimeException("Injected audit failure on Material Issue"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        authenticateAs("rb.operator", "OPERATOR");
        MaterialIssueRequestDto issueReq = new MaterialIssueRequestDto();
        issueReq.setManufacturingOrderId(order.getId());
        issueReq.setRawMaterialId(mat.getId());
        issueReq.setMaterialLotId(validLotId);
        issueReq.setQuantity(new BigDecimal("25.0000"));
        issueReq.setUnit("kg");

        assertThrows(RuntimeException.class, () -> inventoryService.issueMaterial(issueReq));

        MaterialLot unchangedLot = materialLotRepository.findById(validLotId).orElseThrow();
        assertEquals(0, new BigDecimal("50.0000").compareTo(unchangedLot.getAvailableQuantity()));
        assertEquals(MaterialLotStatus.ACTIVE, unchangedLot.getStatus());
        assertEquals(movementsBeforeIssueFail, stockMovementRepository.count());
        assertEquals(auditsBeforeIssueFail, auditLogRepository.count());
    }

    private GoodsReceiptRequestDto baseReceipt(Long materialId, Long whId, Long locId, LocalDate receivedDate, LocalDate expiryDate) {
        GoodsReceiptRequestDto dto = new GoodsReceiptRequestDto();
        dto.setRawMaterialId(materialId);
        dto.setWarehouseId(whId);
        dto.setStorageLocationId(locId);
        dto.setLotNumber("LOT-" + SEQ.getAndIncrement());
        dto.setQuantity(new BigDecimal("10.0000"));
        dto.setUnit("kg");
        dto.setReceivedDate(receivedDate);
        dto.setExpiryDate(expiryDate);
        dto.setSupplierName("FineChem Pharma Inputs");
        dto.setSupplierReference("SUP-" + SEQ.getAndIncrement());
        return dto;
    }
}

