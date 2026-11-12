package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.GoodsReceiptRequestDto;
import com.pharmatrack.dto.InventoryStatusUpdateRequestDto;
import com.pharmatrack.dto.LoginRequestDto;
import com.pharmatrack.dto.MaterialIssueRequestDto;
import com.pharmatrack.dto.OrderRequestDto;
import com.pharmatrack.dto.OrderStatusUpdateDto;
import com.pharmatrack.dto.ProductCreateRequestDto;
import com.pharmatrack.dto.RawMaterialRequestDto;
import com.pharmatrack.dto.RecipeMaterialDto;
import com.pharmatrack.dto.RecipeRequestDto;
import com.pharmatrack.dto.RecipeStatusUpdateDto;
import com.pharmatrack.dto.RecipeStepDto;
import com.pharmatrack.dto.StockAdjustmentRequestDto;
import com.pharmatrack.dto.StorageLocationRequestDto;
import com.pharmatrack.dto.WarehouseRequestDto;
import com.pharmatrack.entity.MaterialType;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.StockAdjustmentType;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.MaterialLotRepository;
import com.pharmatrack.repository.RawMaterialRepository;
import com.pharmatrack.repository.StockMovementRepository;
import com.pharmatrack.repository.StorageLocationRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.repository.WarehouseRepository;
import com.pharmatrack.util.AppTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full Spring Boot + H2 + MockMvc HTTP integration suite verifying the complete
 * 5-role RBAC matrix, unauthenticated 401 enforcement, end-to-end Raw Material Inventory
 * &amp; Warehouse workflow (linking recipes and manufacturing orders), server-side actor
 * attribution, append-only StockMovement enforcement, and sanitized validation/conflict errors.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_inv_http_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RawMaterialRepository rawMaterialRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private StorageLocationRepository storageLocationRepository;

    @Autowired
    private MaterialLotRepository materialLotRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    private final Map<Role, String> roleTokens = new EnumMap<>(Role.class);

    @BeforeEach
    void setUpTokens() throws Exception {
        if (!roleTokens.isEmpty()) {
            return;
        }
        for (Role role : Role.values()) {
            String username = "invmvc." + role.name().toLowerCase();
            String rawPassword = "InvPass9!" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            UserAccount user = userAccountRepository.findByUsernameIgnoreCase(username)
                    .orElseGet(() -> userAccountRepository.saveAndFlush(new UserAccount(
                            username,
                            passwordEncoder.encode(rawPassword),
                            "Inv MVC " + role.name(),
                            role,
                            true
                    )));
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            user.setRole(role);
            user.setEnabled(true);
            userAccountRepository.saveAndFlush(user);

            MvcResult loginRes = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequestDto(username, rawPassword))))
                    .andExpect(status().isOk())
                    .andReturn();
            String jwt = objectMapper.readTree(loginRes.getResponse().getContentAsString()).get("accessToken").asText();
            roleTokens.put(role, "Bearer " + jwt);
        }
    }

    private String auth(Role role) {
        return roleTokens.get(role);
    }

    private JsonNode createProductRecipeAndReleasedOrder(String materialName, BigDecimal perBatchQty) throws Exception {
        int n = SEQ.getAndIncrement();
        ProductCreateRequestDto prodDto = new ProductCreateRequestDto(
                "INV-HTTP-PRD-" + n,
                "Inventory HTTP Product " + n,
                "250 mg",
                "Capsule",
                "HTTP integration test product",
                ProductStatus.ACTIVE
        );
        MvcResult prodRes = mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prodDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long productId = objectMapper.readTree(prodRes.getResponse().getContentAsString()).get("id").asLong();

        RecipeRequestDto recipeDto = new RecipeRequestDto(
                productId,
                new BigDecimal("100.0000"),
                "kg",
                "Inventory BOM Recipe " + n,
                List.of(new RecipeMaterialDto(materialName, perBatchQty, "kg")),
                List.of(new RecipeStepDto(1, "Weighing & Charging", "Charge pre-weighed raw material"))
        );
        MvcResult recRes = mockMvc.perform(post("/api/recipes")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(recipeDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long recipeId = objectMapper.readTree(recRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(patch("/api/recipes/{id}/status", recipeId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.QA_REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isOk());

        LocalDate today = AppTime.nowDate();
        OrderRequestDto orderDto = new OrderRequestDto(
                productId, recipeId, new BigDecimal("200.0000"), "kg", today, today.plusDays(4), "Inventory HTTP order"
        );
        MvcResult orderRes = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderDto)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode orderJson = objectMapper.readTree(orderRes.getResponse().getContentAsString());
        long orderId = orderJson.get("id").asLong();

        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk());

        return orderJson;
    }

    @Test
    @DisplayName("1. All /api/inventory/** endpoints reject unauthenticated callers with 401 without mutating state")
    void unauthenticatedRequestsReturn401WithoutMutation() throws Exception {
        long materialsBefore = rawMaterialRepository.count();
        long warehousesBefore = warehouseRepository.count();
        long lotsBefore = materialLotRepository.count();
        long movementsBefore = stockMovementRepository.count();

        List<MvcResult> results = List.of(
                mockMvc.perform(get("/api/inventory/summary")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/inventory/recipe-material-references")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/inventory/materials")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/inventory/materials/1")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/inventory/warehouses")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/inventory/locations")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/inventory/warehouses/1/locations")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/inventory/lots")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/inventory/lots/1")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/inventory/movements")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/inventory/orders/1/requirements")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(post("/api/inventory/materials").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(put("/api/inventory/materials/1").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(patch("/api/inventory/materials/1/status").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(post("/api/inventory/warehouses").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(post("/api/inventory/warehouses/1/locations").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(post("/api/inventory/receipts").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(post("/api/inventory/issues").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(post("/api/inventory/adjustments").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andReturn()
        );

        for (MvcResult r : results) {
            assertEquals(401, objectMapper.readTree(r.getResponse().getContentAsString()).get("status").asInt());
        }

        assertEquals(materialsBefore, rawMaterialRepository.count());
        assertEquals(warehousesBefore, warehouseRepository.count());
        assertEquals(lotsBefore, materialLotRepository.count());
        assertEquals(movementsBefore, stockMovementRepository.count());
    }

    @Test
    @DisplayName("2. Five-role RBAC matrix enforced strictly across Inventory, Warehouse, Goods Receipt, Material Issue, and Adjustments")
    void fiveRoleRbacMatrixEnforcedAcrossAllInventoryEndpoints() throws Exception {
        // Seed 1 material, warehouse, location, lot, and released order using authorized roles
        String apiName = "RBAC API Material " + SEQ.getAndIncrement();
        JsonNode orderJson = createProductRecipeAndReleasedOrder(apiName, new BigDecimal("50.0000"));
        long orderId = orderJson.get("id").asLong();

        RawMaterialRequestDto matDto = new RawMaterialRequestDto();
        matDto.setMaterialCode("RBAC-MAT-" + SEQ.getAndIncrement());
        matDto.setMaterialName(apiName);
        matDto.setMaterialType(MaterialType.API);
        matDto.setUnit("kg");
        matDto.setMinimumStockLevel(new BigDecimal("10.0000"));
        matDto.setActive(true);

        MvcResult matRes = mockMvc.perform(post("/api/inventory/materials")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(matDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long materialId = objectMapper.readTree(matRes.getResponse().getContentAsString()).get("id").asLong();

        WarehouseRequestDto whDto = new WarehouseRequestDto();
        whDto.setWarehouseCode("RBAC-WH-" + SEQ.getAndIncrement());
        whDto.setWarehouseName("RBAC Main Warehouse");
        whDto.setActive(true);
        MvcResult whRes = mockMvc.perform(post("/api/inventory/warehouses")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(whDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long warehouseId = objectMapper.readTree(whRes.getResponse().getContentAsString()).get("id").asLong();

        StorageLocationRequestDto locDto = new StorageLocationRequestDto();
        locDto.setLocationCode("LOC-RBAC-" + SEQ.getAndIncrement());
        locDto.setZoneName("Zone 1");
        locDto.setActive(true);
        MvcResult locRes = mockMvc.perform(post("/api/inventory/warehouses/{wid}/locations", warehouseId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(locDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long locationId = objectMapper.readTree(locRes.getResponse().getContentAsString()).get("id").asLong();

        LocalDate today = AppTime.nowDate();
        GoodsReceiptRequestDto grnDto = new GoodsReceiptRequestDto();
        grnDto.setRawMaterialId(materialId);
        grnDto.setWarehouseId(warehouseId);
        grnDto.setStorageLocationId(locationId);
        grnDto.setLotNumber("LOT-RBAC-" + SEQ.getAndIncrement());
        grnDto.setQuantity(new BigDecimal("100.0000"));
        grnDto.setUnit("kg");
        grnDto.setReceivedDate(today);
        grnDto.setExpiryDate(today.plusDays(180));
        grnDto.setSupplierName("RBAC FineChem");

        MvcResult grnRes = mockMvc.perform(post("/api/inventory/receipts")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grnDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long lotId = objectMapper.readTree(grnRes.getResponse().getContentAsString()).get("materialLotId").asLong();

        // 2a. All 5 roles can read all GET /api/inventory/** endpoints
        for (Role role : Role.values()) {
            mockMvc.perform(get("/api/inventory/summary").header(HttpHeaders.AUTHORIZATION, auth(role))).andExpect(status().isOk());
            mockMvc.perform(get("/api/inventory/recipe-material-references").header(HttpHeaders.AUTHORIZATION, auth(role))).andExpect(status().isOk());
            mockMvc.perform(get("/api/inventory/materials").header(HttpHeaders.AUTHORIZATION, auth(role))).andExpect(status().isOk());
            mockMvc.perform(get("/api/inventory/materials/{id}", materialId).header(HttpHeaders.AUTHORIZATION, auth(role))).andExpect(status().isOk());
            mockMvc.perform(get("/api/inventory/warehouses").header(HttpHeaders.AUTHORIZATION, auth(role))).andExpect(status().isOk());
            mockMvc.perform(get("/api/inventory/locations").header(HttpHeaders.AUTHORIZATION, auth(role))).andExpect(status().isOk());
            mockMvc.perform(get("/api/inventory/warehouses/{id}/locations", warehouseId).header(HttpHeaders.AUTHORIZATION, auth(role))).andExpect(status().isOk());
            mockMvc.perform(get("/api/inventory/lots").header(HttpHeaders.AUTHORIZATION, auth(role))).andExpect(status().isOk());
            mockMvc.perform(get("/api/inventory/lots/{id}", lotId).header(HttpHeaders.AUTHORIZATION, auth(role))).andExpect(status().isOk());
            mockMvc.perform(get("/api/inventory/movements").header(HttpHeaders.AUTHORIZATION, auth(role))).andExpect(status().isOk());
            mockMvc.perform(get("/api/inventory/orders/{id}/requirements", orderId).header(HttpHeaders.AUTHORIZATION, auth(role))).andExpect(status().isOk());
        }

        // 2b. Master data + Goods Receipts: OPERATOR, QC_ANALYST, QA_REVIEWER get 403 Forbidden
        long matsBefore = rawMaterialRepository.count();
        long whsBefore = warehouseRepository.count();
        long locsBefore = storageLocationRepository.count();
        long movsBefore = stockMovementRepository.count();

        InventoryStatusUpdateRequestDto statusDto = new InventoryStatusUpdateRequestDto(true);
        for (Role denied : List.of(Role.OPERATOR, Role.QC_ANALYST, Role.QA_REVIEWER)) {
            mockMvc.perform(post("/api/inventory/materials")
                            .header(HttpHeaders.AUTHORIZATION, auth(denied))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(matDto)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(not(containsString("Exception"))));

            mockMvc.perform(put("/api/inventory/materials/{id}", materialId)
                            .header(HttpHeaders.AUTHORIZATION, auth(denied))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(matDto)))
                    .andExpect(status().isForbidden());

            mockMvc.perform(patch("/api/inventory/materials/{id}/status", materialId)
                            .header(HttpHeaders.AUTHORIZATION, auth(denied))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusDto)))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/api/inventory/warehouses")
                            .header(HttpHeaders.AUTHORIZATION, auth(denied))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(whDto)))
                    .andExpect(status().isForbidden());

            mockMvc.perform(put("/api/inventory/warehouses/{id}", warehouseId)
                            .header(HttpHeaders.AUTHORIZATION, auth(denied))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(whDto)))
                    .andExpect(status().isForbidden());

            mockMvc.perform(patch("/api/inventory/warehouses/{id}/status", warehouseId)
                            .header(HttpHeaders.AUTHORIZATION, auth(denied))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusDto)))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/api/inventory/warehouses/{id}/locations", warehouseId)
                            .header(HttpHeaders.AUTHORIZATION, auth(denied))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(locDto)))
                    .andExpect(status().isForbidden());

            mockMvc.perform(put("/api/inventory/locations/{id}", locationId)
                            .header(HttpHeaders.AUTHORIZATION, auth(denied))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(locDto)))
                    .andExpect(status().isForbidden());

            mockMvc.perform(patch("/api/inventory/locations/{id}/status", locationId)
                            .header(HttpHeaders.AUTHORIZATION, auth(denied))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusDto)))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/api/inventory/receipts")
                            .header(HttpHeaders.AUTHORIZATION, auth(denied))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grnDto)))
                    .andExpect(status().isForbidden());
        }

        // 2c. Material Issue: QC_ANALYST & QA_REVIEWER get 403; ADMIN, PRODUCTION_SUPERVISOR, OPERATOR allowed
        MaterialIssueRequestDto issueDto = new MaterialIssueRequestDto();
        issueDto.setManufacturingOrderId(orderId);
        issueDto.setRawMaterialId(materialId);
        issueDto.setMaterialLotId(lotId);
        issueDto.setQuantity(new BigDecimal("10.0000"));
        issueDto.setUnit("kg");

        for (Role deniedIssue : List.of(Role.QC_ANALYST, Role.QA_REVIEWER)) {
            mockMvc.perform(post("/api/inventory/issues")
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedIssue))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(issueDto)))
                    .andExpect(status().isForbidden());
        }

        // 2d. Stock Adjustment: OPERATOR & QC_ANALYST get 403; ADMIN, PRODUCTION_SUPERVISOR, QA_REVIEWER allowed
        StockAdjustmentRequestDto adjDto = new StockAdjustmentRequestDto();
        adjDto.setMaterialLotId(lotId);
        adjDto.setAdjustmentType(StockAdjustmentType.INCREASE);
        adjDto.setQuantity(new BigDecimal("1.0000"));
        adjDto.setReason("RBAC positive check");

        for (Role deniedAdj : List.of(Role.OPERATOR, Role.QC_ANALYST)) {
            mockMvc.perform(post("/api/inventory/adjustments")
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedAdj))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(adjDto)))
                    .andExpect(status().isForbidden());
        }

        assertEquals(matsBefore, rawMaterialRepository.count());
        assertEquals(whsBefore, warehouseRepository.count());
        assertEquals(locsBefore, storageLocationRepository.count());
        assertEquals(movsBefore, stockMovementRepository.count());

        // Allowed roles succeed on Issue & Adjustment
        mockMvc.perform(post("/api/inventory/issues")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(issueDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.performedBy").value("invmvc.operator"));

        mockMvc.perform(post("/api/inventory/adjustments")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.QA_REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adjDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.performedBy").value("invmvc.qa_reviewer"));
    }

    @Test
    @DisplayName("3. Full end-to-end Inventory workflow over HTTP enforces BOM limits, trusted actor attribution, immutable ledger, and audit trail")
    void fullEndToEndWorkflowAndSanitizedErrors() throws Exception {
        String excipientName = "Microcrystalline Cellulose " + SEQ.getAndIncrement();
        // Order with 2 batches * 20 kg = 40 kg total BOM requirement
        JsonNode orderJson = createProductRecipeAndReleasedOrder(excipientName, new BigDecimal("20.0000"));
        long orderId = orderJson.get("id").asLong();
        long batch1Id = orderJson.get("batches").get(0).get("id").asLong();

        // 1. Before registration, recipe material reference reports registeredInInventory=false
        MvcResult preRefsRes = mockMvc.perform(get("/api/inventory/recipe-material-references")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode preRefs = objectMapper.readTree(preRefsRes.getResponse().getContentAsString());
        boolean foundUnregistered = false;
        for (JsonNode ref : preRefs) {
            if (excipientName.equals(ref.get("materialName").asText())) {
                foundUnregistered = !ref.get("registeredInInventory").asBoolean();
            }
        }
        assertTrue(foundUnregistered);

        // 2. Register RawMaterial -> linkedRecipeCount == 1 and stockStatus == OUT_OF_STOCK
        RawMaterialRequestDto matDto = new RawMaterialRequestDto();
        matDto.setMaterialCode("E2E-MCC-" + SEQ.getAndIncrement());
        matDto.setMaterialName(excipientName);
        matDto.setMaterialType(MaterialType.EXCIPIENT);
        matDto.setUnit("kg");
        matDto.setMinimumStockLevel(new BigDecimal("15.0000"));
        matDto.setActive(true);

        MvcResult createdMatRes = mockMvc.perform(post("/api/inventory/materials")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(matDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stockStatus").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.linkedRecipeCount").value(1))
                .andReturn();
        long materialId = objectMapper.readTree(createdMatRes.getResponse().getContentAsString()).get("id").asLong();

        // Duplicate material code -> 409 Conflict without leaking stack traces
        mockMvc.perform(post("/api/inventory/materials")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(matDto)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(content().string(not(containsString("Exception"))));

        // 3. Create Warehouse + Storage Location
        WarehouseRequestDto whDto = new WarehouseRequestDto();
        whDto.setWarehouseCode("E2E-WH-" + SEQ.getAndIncrement());
        whDto.setWarehouseName("E2E Warehouse");
        whDto.setActive(true);
        long whId = objectMapper.readTree(mockMvc.perform(post("/api/inventory/warehouses")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(whDto)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        StorageLocationRequestDto locDto = new StorageLocationRequestDto();
        locDto.setLocationCode("R1-B2-" + SEQ.getAndIncrement());
        locDto.setZoneName("Ambient Store");
        locDto.setActive(true);
        long locId = objectMapper.readTree(mockMvc.perform(post("/api/inventory/warehouses/{wid}/locations", whId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(locDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.zoneName").value("Ambient Store"))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        // 4. Goods Receipt with spoofed header & body fields -> server ignores spoofing & uses JWT username
        LocalDate today = AppTime.nowDate();
        String grnBody = """
                {
                  "rawMaterialId": %d,
                  "warehouseId": %d,
                  "storageLocationId": %d,
                  "lotNumber": "E2E-LOT-%d",
                  "quantity": 40.0000,
                  "unit": "kg",
                  "receivedDate": "%s",
                  "expiryDate": "%s",
                  "supplierName": "PharmaExcipients Inc",
                  "supplierReference": "COA-2026-99",
                  "referenceNumber": "GRN-E2E-%d",
                  "performedBy": "spoofed.body.actor",
                  "createdBy": "spoofed.creator"
                }
                """.formatted(materialId, whId, locId, SEQ.getAndIncrement(), today, today.plusMonths(12), SEQ.getAndIncrement());

        MvcResult grnRes = mockMvc.perform(post("/api/inventory/receipts")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .header("X-Actor-Name", "Spoofed Header")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grnBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movementType").value("GOODS_RECEIPT"))
                .andExpect(jsonPath("$.performedBy").value("invmvc.production_supervisor"))
                .andExpect(jsonPath("$.quantityDelta").value(40.0))
                .andReturn();
        long lotId = objectMapper.readTree(grnRes.getResponse().getContentAsString()).get("materialLotId").asLong();

        // 5. Order material requirements show 40 kg total required, 40 kg available
        mockMvc.perform(get("/api/inventory/orders/{id}/requirements", orderId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleForIssue").value(true))
                .andExpect(jsonPath("$.requirements[0].registeredInInventory").value(true))
                .andExpect(jsonPath("$.requirements[0].totalRequiredQuantity").value(40.0))
                .andExpect(jsonPath("$.requirements[0].remainingRequiredQuantity").value(40.0))
                .andExpect(jsonPath("$.requirements[0].fulfilled").value(false));

        // 6. QA_REVIEWER quarantines & releases lot; while quarantined, OPERATOR issue fails with 400
        StockAdjustmentRequestDto qReq = new StockAdjustmentRequestDto();
        qReq.setMaterialLotId(lotId);
        qReq.setAdjustmentType(StockAdjustmentType.QUARANTINE);
        qReq.setReason("Visual inspection hold");
        mockMvc.perform(post("/api/inventory/adjustments")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.QA_REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(qReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.performedBy").value("invmvc.qa_reviewer"));

        MaterialIssueRequestDto issueDto = new MaterialIssueRequestDto();
        issueDto.setManufacturingOrderId(orderId);
        issueDto.setBatchId(batch1Id);
        issueDto.setRawMaterialId(materialId);
        issueDto.setMaterialLotId(lotId);
        issueDto.setQuantity(new BigDecimal("40.0000"));
        issueDto.setUnit("kg");
        issueDto.setReason("Full campaign dispense");

        mockMvc.perform(post("/api/inventory/issues")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(issueDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("quarantined")))
                .andExpect(content().string(not(containsString("Exception"))));

        StockAdjustmentRequestDto relReq = new StockAdjustmentRequestDto();
        relReq.setMaterialLotId(lotId);
        relReq.setAdjustmentType(StockAdjustmentType.RELEASE_QUARANTINE);
        relReq.setReason("COA verified clean");
        mockMvc.perform(post("/api/inventory/adjustments")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.QA_REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(relReq)))
                .andExpect(status().isCreated());

        // 7. OPERATOR issues full 40 kg -> lot becomes DEPLETED & BOM becomes fulfilled=true
        mockMvc.perform(post("/api/inventory/issues")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR))
                        .header("X-Actor-Name", "Spoofed Op")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(issueDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movementType").value("MATERIAL_ISSUE"))
                .andExpect(jsonPath("$.quantityDelta").value(-40.0))
                .andExpect(jsonPath("$.quantityAfter").value(0.0))
                .andExpect(jsonPath("$.performedBy").value("invmvc.operator"));

        mockMvc.perform(get("/api/inventory/lots/{id}", lotId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveStatus").value("DEPLETED"))
                .andExpect(jsonPath("$.availableQuantity").value(0.0));

        mockMvc.perform(get("/api/inventory/orders/{id}/requirements", orderId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirements[0].issuedQuantity").value(40.0))
                .andExpect(jsonPath("$.requirements[0].remainingRequiredQuantity").value(0.0))
                .andExpect(jsonPath("$.requirements[0].fulfilled").value(true));

        // 8. Verify Audit Trail captured MATERIAL_LOT actions for the lot with exact actors
        mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.QA_REVIEWER))
                        .param("entityType", "MATERIAL_LOT")
                        .param("entityId", String.valueOf(lotId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].action").value("MATERIAL_ISSUED"))
                .andExpect(jsonPath("$[0].actorName").value("invmvc.operator"))
                .andExpect(jsonPath("$[1].action").value("MATERIAL_LOT_RELEASED"))
                .andExpect(jsonPath("$[2].action").value("MATERIAL_LOT_QUARANTINED"))
                .andExpect(jsonPath("$[3].action").value("GOODS_RECEIPT_RECORDED"))
                .andExpect(jsonPath("$[3].actorName").value("invmvc.production_supervisor"));

        // 9. Verify StockMovement endpoint rejects mutation HTTP verbs (fail-closed)
        mockMvc.perform(put("/api/inventory/movements")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/inventory/movements")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN)))
                .andExpect(status().isForbidden());

        // 10. Non-existent entity returns sanitized 404
        mockMvc.perform(get("/api/inventory/materials/999999")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("4. Storage location creation retains Zone / Area immediately after create, upon GET reload, and in audit trail (regression test)")
    void storageLocationZoneNameRetainedImmediatelyAfterCreateAndOnReload() throws Exception {
        WarehouseRequestDto whDto = new WarehouseRequestDto();
        whDto.setWarehouseCode("WH-ZONE-TEST-" + SEQ.getAndIncrement());
        whDto.setWarehouseName("Zone Regression Test Warehouse");
        whDto.setStorageCondition("Controlled Room Temperature (15-25 C)");
        whDto.setActive(true);
        long whId = objectMapper.readTree(mockMvc.perform(post("/api/inventory/warehouses")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(whDto)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        StorageLocationRequestDto locDto = new StorageLocationRequestDto();
        String locCode = "A01-R01-B01-" + SEQ.getAndIncrement();
        locDto.setLocationCode(locCode);
        locDto.setZoneName("API Storage");
        locDto.setAisle("A01");
        locDto.setRack("R01");
        locDto.setBin("B01");
        locDto.setActive(true);

        // 1. Immediately after creation: response contains zoneName
        MvcResult createResult = mockMvc.perform(post("/api/inventory/warehouses/{wid}/locations", whId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(locDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.locationCode").value(locCode))
                .andExpect(jsonPath("$.zoneName").value("API Storage"))
                .andExpect(jsonPath("$.aisle").value("A01"))
                .andExpect(jsonPath("$.rack").value("R01"))
                .andExpect(jsonPath("$.bin").value("B01"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        long locId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        // 2. When fetched again via GET /api/inventory/locations?warehouseId={wid}
        mockMvc.perform(get("/api/inventory/locations")
                        .param("warehouseId", String.valueOf(whId))
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(locId))
                .andExpect(jsonPath("$[0].locationCode").value(locCode))
                .andExpect(jsonPath("$[0].zoneName").value("API Storage"))
                .andExpect(jsonPath("$[0].aisle").value("A01"))
                .andExpect(jsonPath("$[0].rack").value("R01"))
                .andExpect(jsonPath("$[0].bin").value("B01"));

        // 3. When fetched again via GET /api/inventory/locations (all)
        mockMvc.perform(get("/api/inventory/locations")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + locId + ")].zoneName").value("API Storage"));

        // 4. Verify audit trail snapshot has zoneName: "API Storage"
        mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.QA_REVIEWER))
                        .param("entityType", "STORAGE_LOCATION")
                        .param("entityId", String.valueOf(locId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("STORAGE_LOCATION_CREATED"))
                .andExpect(jsonPath("$[0].afterValues").value(org.hamcrest.Matchers.containsString("API Storage")));

        // 5. Verify update keeps/modifies zoneName correctly
        locDto.setZoneName("Cold Chain Vault");
        mockMvc.perform(put("/api/inventory/locations/{id}", locId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(locDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zoneName").value("Cold Chain Vault"));
    }
}
