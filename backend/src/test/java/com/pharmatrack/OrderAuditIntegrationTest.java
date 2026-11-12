package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.BatchStatusUpdateDto;
import com.pharmatrack.dto.CompleteExecutionStepRequestDto;
import com.pharmatrack.dto.OrderRequestDto;
import com.pharmatrack.dto.OrderStatusUpdateDto;
import com.pharmatrack.dto.RecipeStatusUpdateDto;
import com.pharmatrack.dto.StatusUpdateRequestDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchExecutionStepRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.JwtService;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.ManufacturingOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Isolated Spring Boot + H2 + MockMvc integration tests verifying comprehensive
 * append-only audit trail coverage, meaningful JSON snapshots, queryability via
 * {@code /api/quality/audit-logs} and Quality Batch Dossier, failure non-auditing,
 * and complete transaction rollback for Manufacturing Order &amp; Batch operations.
 *
 * <p>Test methods intentionally run without {@code @Transactional} so real Spring
 * transaction boundaries commit and roll back across multi-step persistence and
 * audit logging operations.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_order_audit_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderAuditIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private ManufacturingOrderRepository orderRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private BatchExecutionStepRepository executionStepRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @SpyBean
    private AuditTrailService auditTrailService;

    @AfterEach
    void resetSpy() {
        Mockito.reset(auditTrailService);
    }

    private String bearerFor(String username, Role role) {
        String normalized = UserAccount.normalizeUsername(username);
        UserAccount user = userAccountRepository.findByUsernameIgnoreCase(normalized)
                .orElseGet(() -> userAccountRepository.saveAndFlush(new UserAccount(
                        normalized,
                        passwordEncoder.encode("TestPass#" + UUID.randomUUID()),
                        username.trim(),
                        role,
                        true
                )));
        assertEquals(role, user.getRole());
        return "Bearer " + jwtService.generateToken(user);
    }

    private record SeedBundle(Product product, Recipe recipe) {
    }

    private record CreatedOrder(long orderId, String orderNumber, List<Long> batchIds, List<String> batchNumbers) {
    }

    private SeedBundle seedProductAndRecipe(String tag, ProductStatus productStatus, RecipeStatus recipeStatus, int version) {
        int n = SEQ.getAndIncrement();
        Product product = productRepository.saveAndFlush(new Product(
                "OA-PRD-" + tag + "-" + n,
                "Order Audit Product " + tag + " " + n,
                "500 mg",
                "Tablet",
                "Order audit test formulation " + n,
                productStatus
        ));

        Recipe recipe = new Recipe(
                "RCP-" + product.getProductCode() + "-V" + version,
                product,
                version,
                new BigDecimal("100.0000"),
                "kg",
                recipeStatus,
                "Seeded recipe for " + tag
        );
        recipe.addStep(new RecipeStep(1, "Dispensing", "Dispense materials accurately."));
        recipe.addStep(new RecipeStep(2, "Blending", "Blend dry powders at 25 RPM."));
        Recipe savedRecipe = recipeRepository.saveAndFlush(recipe);

        return new SeedBundle(product, savedRecipe);
    }

    private CreatedOrder createOrderViaApi(Product product, Recipe recipe, int batchCount, String notes, String bearer) throws Exception {
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);
        OrderRequestDto req = new OrderRequestDto(
                product.getId(),
                recipe.getId(),
                new BigDecimal("100.0000").multiply(BigDecimal.valueOf(batchCount)),
                "kg",
                today,
                today.plusDays(4),
                notes
        );

        MvcResult res = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.targetBatchCount").value(batchCount))
                .andReturn();

        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        List<Long> batchIds = new ArrayList<>();
        List<String> batchNumbers = new ArrayList<>();
        for (JsonNode b : json.get("batches")) {
            batchIds.add(b.get("id").asLong());
            batchNumbers.add(b.get("batchNumber").asText());
        }
        return new CreatedOrder(
                json.get("id").asLong(),
                json.get("orderNumber").asText(),
                batchIds,
                batchNumbers
        );
    }

    private void completeAllExecutionStepsAndFinishBatch(long batchId, String actualYield, String operatorBearer) throws Exception {
        MvcResult stepsRes = mockMvc.perform(get("/api/batches/{batchId}/steps", batchId)
                        .header(HttpHeaders.AUTHORIZATION, operatorBearer))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode stepsRoot = objectMapper.readTree(stepsRes.getResponse().getContentAsString());
        for (JsonNode step : stepsRoot.get("executionSteps")) {
            long stepId = step.get("id").asLong();
            mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/start", batchId, stepId)
                            .header(HttpHeaders.AUTHORIZATION, operatorBearer)
                            .header("X-Actor-Name", "Spoofed Step Starter"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

            mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/complete", batchId, stepId)
                            .header(HttpHeaders.AUTHORIZATION, operatorBearer)
                            .header("X-Actor-Name", "Spoofed Step Finisher")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"actualValue\":\"Within specification\",\"observations\":\"Executed during Order Audit integration test\",\"executedBy\":\"Spoofed Executor\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        }

        mockMvc.perform(patch("/api/batches/{id}/status", batchId)
                        .header(HttpHeaders.AUTHORIZATION, operatorBearer)
                        .header("X-Actor-Name", "Spoofed Batch Finisher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\",\"actualYield\":" + new BigDecimal(actualYield).toPlainString() + ",\"actorName\":\"Spoofed Body Finisher\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.qaStatus").value("PENDING_QA"));
    }

    @Test
    @DisplayName("1. Full Order & Batch lifecycle records accurate audit events, snapshots, actor attribution, and Quality dossier visibility")
    void fullOrderAndBatchLifecycleRecordsAccurateAuditTrail() throws Exception {
        String plannerSupBearer = bearerFor("Planner.System", Role.PRODUCTION_SUPERVISOR);
        String schedulerB1Bearer = bearerFor("Body.Scheduler", Role.PRODUCTION_SUPERVISOR);
        String plantMgrBearer = bearerFor("Plant.Manager", Role.PRODUCTION_SUPERVISOR);
        String floorOp1Bearer = bearerFor("Floor.Op1", Role.OPERATOR);
        String floorOp2Bearer = bearerFor("Floor.Op2", Role.OPERATOR);
        String leadOpBearer = bearerFor("Lead.Operator", Role.OPERATOR);
        String qaBearer = bearerFor("qa.reviewer", Role.QA_REVIEWER);

        SeedBundle seed = seedProductAndRecipe("LIFE", ProductStatus.ACTIVE, RecipeStatus.APPROVED, 2);
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);

        // 1. Create order (3 batches) authenticated as planner.system
        CreatedOrder created = createOrderViaApi(seed.product(), seed.recipe(), 3, "Campaign Run", plannerSupBearer);
        long orderId = created.orderId();
        long b1 = created.batchIds().get(0);
        long b2 = created.batchIds().get(1);
        long b3 = created.batchIds().get(2);

        long auditsAfterCreate = auditLogRepository.count();

        // 2. Idempotent no-ops emit zero extra audit entries
        mockMvc.perform(patch("/api/batches/{id}/status", b1)
                        .header(HttpHeaders.AUTHORIZATION, plannerSupBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.PLANNED))))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, plannerSupBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.PLANNED))))
                .andExpect(status().isOk());
        assertEquals(auditsAfterCreate, auditLogRepository.count());

        // 3. Cancel batch 1 while PLANNED -> forged X-Actor-Name and body actorName ignored; JWT identity wins
        mockMvc.perform(patch("/api/batches/{id}/status", b1)
                        .header(HttpHeaders.AUTHORIZATION, schedulerB1Bearer)
                        .header("X-Actor-Name", "Forged Header Actor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\",\"actorName\":\"Forged Body Scheduler\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 4. Release order -> forged oversized X-Actor-Name ignored; JWT identity recorded
        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, plantMgrBearer)
                        .header("X-Actor-Name", "A".repeat(120))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        // 5. Start batch 2 as Floor.Op1 -> emits BATCH_STARTED (floor.op1) + MANUFACTURING_ORDER_UPDATED (floor.op1)
        // Ignored forged header & body actor fields
        mockMvc.perform(patch("/api/batches/{id}/status", b2)
                        .header(HttpHeaders.AUTHORIZATION, floorOp1Bearer)
                        .header("X-Actor-Name", "Spoofed Op")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"actorName\":\"Spoofed Body Op\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // Verify idempotent retry on already IN_PROGRESS batch emits zero duplicate audits
        long auditsAfterFirstBatchStart = auditLogRepository.count();
        mockMvc.perform(patch("/api/batches/{id}/status", b2)
                        .header(HttpHeaders.AUTHORIZATION, floorOp1Bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isOk());
        assertEquals(auditsAfterFirstBatchStart, auditLogRepository.count());

        // 6. Start batch 3 while order is already IN_PROGRESS -> emits 1 BATCH_STARTED (floor.op2) and zero duplicate order update audits
        mockMvc.perform(patch("/api/batches/{id}/status", b3)
                        .header(HttpHeaders.AUTHORIZATION, floorOp2Bearer)
                        .header("X-Actor-Name", "Forged Starter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"actorName\":\"Forged Body Starter\"}"))
                .andExpect(status().isOk());
        assertEquals(auditsAfterFirstBatchStart + 1, auditLogRepository.count(),
                "Starting subsequent batch must emit only BATCH_STARTED and no duplicate MANUFACTURING_ORDER_UPDATED");

        // 7. Complete batch 2 while batch 3 remains IN_PROGRESS -> emits 1 BATCH_COMPLETED (floor.op2), zero order completion audit
        completeAllExecutionStepsAndFinishBatch(b2, "98.5000", floorOp2Bearer);
        assertEquals(auditsAfterFirstBatchStart + 2, auditLogRepository.count(),
                "Completing non-final batch must emit only BATCH_COMPLETED and no MANUFACTURING_ORDER_COMPLETED");

        // Verify all b2 execution steps recorded trusted lowercase principal (floor.op2)
        executionStepRepository.findByBatchIdOrderByStepNumberAsc(b2)
                .forEach(step -> assertEquals("floor.op2", step.getExecutedBy()));

        // 8. Complete batch 3 (final non-cancelled batch) -> emits BATCH_COMPLETED + MANUFACTURING_ORDER_COMPLETED (lead.operator)
        completeAllExecutionStepsAndFinishBatch(b3, "99.2000", leadOpBearer);
        executionStepRepository.findByBatchIdOrderByStepNumberAsc(b3)
                .forEach(step -> assertEquals("lead.operator", step.getExecutedBy()));

        // Verify MANUFACTURING_ORDER audit trail in reverse chronological order (4 entries)
        MvcResult orderAuditRes = mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .param("entityType", "MANUFACTURING_ORDER")
                        .param("entityId", String.valueOf(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andReturn();

        JsonNode orderLogs = objectMapper.readTree(orderAuditRes.getResponse().getContentAsString());

        // [0] MANUFACTURING_ORDER_COMPLETED
        JsonNode completedLog = orderLogs.get(0);
        assertEquals("MANUFACTURING_ORDER_COMPLETED", completedLog.get("action").asText());
        assertEquals("lead.operator", completedLog.get("actorName").asText());
        assertEquals(created.orderNumber(), completedLog.get("entityReference").asText());
        assertTrue(completedLog.get("batchId").isNull());
        JsonNode compBefore = objectMapper.readTree(completedLog.get("beforeValues").asText());
        JsonNode compAfter = objectMapper.readTree(completedLog.get("afterValues").asText());
        assertEquals("IN_PROGRESS", compBefore.get("status").asText());
        assertTrue(compBefore.get("actualEndDate").isNull());
        assertEquals("COMPLETED", compAfter.get("status").asText());
        assertFalse(compAfter.get("actualEndDate").isNull());
        assertEquals(3, compAfter.get("batchSummary").get("totalBatches").asInt());
        assertEquals(2, compAfter.get("batchSummary").get("completedBatches").asInt());
        assertEquals(1, compAfter.get("batchSummary").get("cancelledBatches").asInt());
        assertEquals(0, compAfter.get("batchSummary").get("inProgressBatches").asInt());
        assertEquals(0, new BigDecimal("98.5").compareTo(compAfter.get("batches").get(1).get("actualYield").decimalValue()));
        assertEquals(0, new BigDecimal("99.2").compareTo(compAfter.get("batches").get(2).get("actualYield").decimalValue()));

        // [1] MANUFACTURING_ORDER_UPDATED (auto-advance on b2 start)
        JsonNode updatedLog = orderLogs.get(1);
        assertEquals("MANUFACTURING_ORDER_UPDATED", updatedLog.get("action").asText());
        assertEquals("floor.op1", updatedLog.get("actorName").asText());
        JsonNode updBefore = objectMapper.readTree(updatedLog.get("beforeValues").asText());
        JsonNode updAfter = objectMapper.readTree(updatedLog.get("afterValues").asText());
        assertEquals("RELEASED", updBefore.get("status").asText());
        assertTrue(updBefore.get("actualStartDate").isNull());
        assertEquals("IN_PROGRESS", updAfter.get("status").asText());
        assertFalse(updAfter.get("actualStartDate").isNull());

        // [2] MANUFACTURING_ORDER_RELEASED
        JsonNode releasedLog = orderLogs.get(2);
        assertEquals("MANUFACTURING_ORDER_RELEASED", releasedLog.get("action").asText());
        assertEquals("plant.manager", releasedLog.get("actorName").asText());
        JsonNode relBefore = objectMapper.readTree(releasedLog.get("beforeValues").asText());
        JsonNode relAfter = objectMapper.readTree(releasedLog.get("afterValues").asText());
        assertEquals("PLANNED", relBefore.get("status").asText());
        assertEquals("RELEASED", relAfter.get("status").asText());
        assertEquals(1, relAfter.get("batchSummary").get("cancelledBatches").asInt());
        assertEquals(2, relAfter.get("batchSummary").get("plannedBatches").asInt());
        assertEquals(relAfter, updBefore, "RELEASED after-snapshot must match UPDATED before-snapshot");

        // [3] MANUFACTURING_ORDER_CREATED
        JsonNode createdLog = orderLogs.get(3);
        assertEquals("MANUFACTURING_ORDER_CREATED", createdLog.get("action").asText());
        assertEquals("planner.system", createdLog.get("actorName").asText());
        assertTrue(createdLog.get("beforeValues").isNull());
        JsonNode creAfter = objectMapper.readTree(createdLog.get("afterValues").asText());
        assertEquals(orderId, creAfter.get("orderId").asLong());
        assertEquals(created.orderNumber(), creAfter.get("orderNumber").asText());
        assertEquals(seed.product().getProductCode(), creAfter.get("productCode").asText());
        assertEquals(seed.recipe().getRecipeCode(), creAfter.get("recipeCode").asText());
        assertEquals(2, creAfter.get("recipeVersion").asInt());
        assertEquals(0, new BigDecimal("100").compareTo(creAfter.get("standardBatchSize").decimalValue()));
        assertEquals(0, new BigDecimal("300").compareTo(creAfter.get("plannedQuantity").decimalValue()));
        assertEquals(3, creAfter.get("targetBatchCount").asInt());
        assertEquals("PLANNED", creAfter.get("status").asText());
        assertEquals(today.toString(), creAfter.get("plannedStartDate").asText());
        assertEquals(today.plusDays(4).toString(), creAfter.get("plannedEndDate").asText());
        assertEquals("Campaign Run", creAfter.get("notes").asText());
        assertEquals(3, creAfter.get("batches").size());

        // Verify BATCH audit trail for cancelled b1 via both entityType+entityId and batchId queries (2 entries)
        MvcResult b1AuditRes = mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .param("entityType", "BATCH")
                        .param("entityId", String.valueOf(b1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        JsonNode b1Logs = objectMapper.readTree(b1AuditRes.getResponse().getContentAsString());
        assertEquals("BATCH_CANCELLED", b1Logs.get(0).get("action").asText());
        assertEquals("body.scheduler", b1Logs.get(0).get("actorName").asText());
        assertEquals(b1, b1Logs.get(0).get("batchId").asLong());
        assertEquals("PLANNED", objectMapper.readTree(b1Logs.get(0).get("beforeValues").asText()).get("status").asText());
        assertEquals("CANCELLED", objectMapper.readTree(b1Logs.get(0).get("afterValues").asText()).get("status").asText());

        assertEquals("BATCH_CREATED", b1Logs.get(1).get("action").asText());
        assertEquals("planner.system", b1Logs.get(1).get("actorName").asText());
        assertTrue(b1Logs.get(1).get("beforeValues").isNull());
        assertEquals("PLANNED", objectMapper.readTree(b1Logs.get(1).get("afterValues").asText()).get("status").asText());

        // Verify batch 2 audit trail via /api/quality/audit-logs?batchId=..., entityType+entityId, and Quality Dossier (3 entries)
        MvcResult b2AuditRes = mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .param("batchId", String.valueOf(b2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].action").value("BATCH_COMPLETED"))
                .andExpect(jsonPath("$[1].action").value("BATCH_STARTED"))
                .andExpect(jsonPath("$[2].action").value("BATCH_CREATED"))
                .andReturn();

        JsonNode b2Logs = objectMapper.readTree(b2AuditRes.getResponse().getContentAsString());

        JsonNode b2Completed = b2Logs.get(0);
        assertEquals("BATCH", b2Completed.get("entityType").asText());
        assertEquals(b2, b2Completed.get("entityId").asLong());
        assertEquals(b2, b2Completed.get("batchId").asLong());
        assertEquals("floor.op2", b2Completed.get("actorName").asText());
        JsonNode b2CompBefore = objectMapper.readTree(b2Completed.get("beforeValues").asText());
        JsonNode b2CompAfter = objectMapper.readTree(b2Completed.get("afterValues").asText());
        assertEquals("IN_PROGRESS", b2CompBefore.get("status").asText());
        assertTrue(b2CompBefore.get("qaStatus").isNull());
        assertTrue(b2CompBefore.get("actualYield").isNull());
        assertTrue(b2CompBefore.get("endTime").isNull());
        assertEquals("COMPLETED", b2CompAfter.get("status").asText());
        assertEquals("PENDING_QA", b2CompAfter.get("qaStatus").asText());
        assertEquals(0, new BigDecimal("98.5").compareTo(b2CompAfter.get("actualYield").decimalValue()));
        assertFalse(b2CompAfter.get("endTime").isNull());
        assertEquals(2, b2CompAfter.get("recipeVersion").asInt());
        assertEquals(orderId, b2CompAfter.get("orderId").asLong());

        JsonNode b2Started = b2Logs.get(1);
        assertEquals("BATCH", b2Started.get("entityType").asText());
        assertEquals(b2, b2Started.get("entityId").asLong());
        assertEquals(b2, b2Started.get("batchId").asLong());
        assertEquals("floor.op1", b2Started.get("actorName").asText());
        JsonNode b2StartBefore = objectMapper.readTree(b2Started.get("beforeValues").asText());
        JsonNode b2StartAfter = objectMapper.readTree(b2Started.get("afterValues").asText());
        assertEquals("PLANNED", b2StartBefore.get("status").asText());
        assertTrue(b2StartBefore.get("startTime").isNull());
        assertTrue(b2StartBefore.get("manufactureDate").isNull());
        assertEquals("IN_PROGRESS", b2StartAfter.get("status").asText());
        assertFalse(b2StartAfter.get("startTime").isNull());
        assertFalse(b2StartAfter.get("manufactureDate").isNull());
        assertEquals(2, b2StartAfter.get("recipeVersion").asInt());

        mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .param("entityType", "BATCH")
                        .param("entityId", String.valueOf(b3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].action").value("BATCH_COMPLETED"))
                .andExpect(jsonPath("$[0].actorName").value("lead.operator"))
                .andExpect(jsonPath("$[1].action").value("BATCH_STARTED"))
                .andExpect(jsonPath("$[1].actorName").value("floor.op2"))
                .andExpect(jsonPath("$[2].action").value("BATCH_CREATED"))
                .andExpect(jsonPath("$[2].actorName").value("planner.system"));

        mockMvc.perform(get("/api/quality/batches/{batchId}", b2)
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditLogs.length()").value(3))
                .andExpect(jsonPath("$.auditLogs[0].action").value("BATCH_COMPLETED"))
                .andExpect(jsonPath("$.auditLogs[1].action").value("BATCH_STARTED"))
                .andExpect(jsonPath("$.auditLogs[2].action").value("BATCH_CREATED"))
                .andExpect(jsonPath("$.auditLogs[0].entityType").value("BATCH"));
    }

    @Test
    @DisplayName("2. Manual order transitions, order cancellation cascades (skipping already-cancelled batches), and auto-cancel emit exact audits")
    void manualTransitionsCascadesAndAutoCancelEmitExactAudits() throws Exception {
        String creatorABearer = bearerFor("creator.a", Role.PRODUCTION_SUPERVISOR);
        String shiftSupBearer = bearerFor("Shift.Supervisor", Role.PRODUCTION_SUPERVISOR);
        String prodDirBearer = bearerFor("Production.Director", Role.PRODUCTION_SUPERVISOR);
        String plannerBBearer = bearerFor("planner.b", Role.PRODUCTION_SUPERVISOR);
        String schedulerB1Bearer = bearerFor("scheduler.b1", Role.PRODUCTION_SUPERVISOR);
        String masterPlannerBearer = bearerFor("Master.Planner", Role.PRODUCTION_SUPERVISOR);
        String plannerCBearer = bearerFor("planner.c", Role.PRODUCTION_SUPERVISOR);
        String earlyCancellerBearer = bearerFor("Early.Canceller", Role.PRODUCTION_SUPERVISOR);
        String opsDirBearer = bearerFor("Operations.Director", Role.PRODUCTION_SUPERVISOR);
        String plannerDBearer = bearerFor("Planner.D", Role.PRODUCTION_SUPERVISOR);
        String qaBearer = bearerFor("qa.reviewer", Role.QA_REVIEWER);

        SeedBundle seed = seedProductAndRecipe("CASC", ProductStatus.ACTIVE, RecipeStatus.APPROVED, 1);

        // Subflow A: Manual RELEASED -> IN_PROGRESS via authenticated supervisor
        CreatedOrder orderA = createOrderViaApi(seed.product(), seed.recipe(), 1, "Manual start test", creatorABearer);
        mockMvc.perform(patch("/api/orders/{id}/status", orderA.orderId())
                        .header(HttpHeaders.AUTHORIZATION, creatorABearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/orders/{id}/status", orderA.orderId())
                        .header(HttpHeaders.AUTHORIZATION, shiftSupBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OrderStatusUpdateDto(OrderStatus.IN_PROGRESS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.actualStartDate").isNotEmpty());

        MvcResult aLogsRes = mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .param("entityType", "MANUFACTURING_ORDER")
                        .param("entityId", String.valueOf(orderA.orderId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn();
        JsonNode aLogs = objectMapper.readTree(aLogsRes.getResponse().getContentAsString());
        assertEquals("MANUFACTURING_ORDER_UPDATED", aLogs.get(0).get("action").asText());
        assertEquals("shift.supervisor", aLogs.get(0).get("actorName").asText());
        assertEquals("RELEASED", objectMapper.readTree(aLogs.get(0).get("beforeValues").asText()).get("status").asText());
        assertEquals("IN_PROGRESS", objectMapper.readTree(aLogs.get(0).get("afterValues").asText()).get("status").asText());

        // Subflow B: Manual IN_PROGRESS -> COMPLETED (for an order already IN_PROGRESS whose non-cancelled batch is COMPLETED)
        ManufacturingOrder manualCompOrder = orderRepository.saveAndFlush(new ManufacturingOrder(
                "MO-MANUAL-COMP-" + SEQ.getAndIncrement(),
                seed.product(),
                seed.recipe(),
                1,
                new BigDecimal("100.0000"),
                "kg",
                1,
                OrderStatus.IN_PROGRESS,
                LocalDate.now(ManufacturingOrderService.APP_ZONE_ID),
                LocalDate.now(ManufacturingOrderService.APP_ZONE_ID).plusDays(2),
                "Seeded for manual completion audit"
        ));
        manualCompOrder.setActualStartDate(LocalDateTime.now(ManufacturingOrderService.APP_ZONE_ID).minusHours(2));
        manualCompOrder = orderRepository.saveAndFlush(manualCompOrder);
        Batch completedSeedBatch = new Batch(
                "BAT-MANUAL-COMP-" + SEQ.getAndIncrement(),
                manualCompOrder,
                new BigDecimal("100.0000"),
                "kg",
                BatchStatus.COMPLETED,
                LocalDate.now(ManufacturingOrderService.APP_ZONE_ID),
                null
        );
        completedSeedBatch.setActualYield(new BigDecimal("99.5000"));
        batchRepository.saveAndFlush(completedSeedBatch);

        mockMvc.perform(patch("/api/orders/{id}/status", manualCompOrder.getId())
                        .header(HttpHeaders.AUTHORIZATION, prodDirBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.COMPLETED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .param("entityType", "MANUFACTURING_ORDER")
                        .param("entityId", String.valueOf(manualCompOrder.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("MANUFACTURING_ORDER_COMPLETED"))
                .andExpect(jsonPath("$[0].actorName").value("production.director"));

        // Subflow C: Cancelling final PLANNED batch auto-cancels parent order (BATCH_CANCELLED + MANUFACTURING_ORDER_CANCELLED)
        CreatedOrder orderB = createOrderViaApi(seed.product(), seed.recipe(), 2, "Auto-cancel test", plannerBBearer);
        long ob1 = orderB.batchIds().get(0);
        long ob2 = orderB.batchIds().get(1);

        mockMvc.perform(patch("/api/batches/{id}/status", ob1)
                        .header(HttpHeaders.AUTHORIZATION, schedulerB1Bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/batches/{id}/status", ob2)
                        .header(HttpHeaders.AUTHORIZATION, masterPlannerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/orders/{id}", orderB.orderId())
                        .header(HttpHeaders.AUTHORIZATION, plannerBBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        MvcResult bLogsRes = mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .param("entityType", "MANUFACTURING_ORDER")
                        .param("entityId", String.valueOf(orderB.orderId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();
        JsonNode bOrderLogs = objectMapper.readTree(bLogsRes.getResponse().getContentAsString());
        assertEquals("MANUFACTURING_ORDER_CANCELLED", bOrderLogs.get(0).get("action").asText());
        assertEquals("master.planner", bOrderLogs.get(0).get("actorName").asText());
        assertTrue(bOrderLogs.get(0).get("reason").asText().contains("all child batches are CANCELLED"));

        // Subflow D: RELEASED -> CANCELLED with 1 already-CANCELLED batch skips duplicate BATCH_CANCELLED on already-cancelled batch
        CreatedOrder orderC = createOrderViaApi(seed.product(), seed.recipe(), 3, "Cascade test", plannerCBearer);
        long oc1 = orderC.batchIds().get(0);
        long oc2 = orderC.batchIds().get(1);
        long oc3 = orderC.batchIds().get(2);

        mockMvc.perform(patch("/api/batches/{id}/status", oc1)
                        .header(HttpHeaders.AUTHORIZATION, earlyCancellerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/orders/{id}/status", orderC.orderId())
                        .header(HttpHeaders.AUTHORIZATION, plannerCBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/orders/{id}/status", orderC.orderId())
                        .header(HttpHeaders.AUTHORIZATION, opsDirBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.CANCELLED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // oc1 has exactly 2 logs (CREATED + initial BATCH_CANCELLED), NOT 3!
        mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .param("entityType", "BATCH")
                        .param("entityId", String.valueOf(oc1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].actorName").value("early.canceller"));

        // oc2 and oc3 each get BATCH_CANCELLED with operations.director
        for (long cascadedBatchId : List.of(oc2, oc3)) {
            mockMvc.perform(get("/api/quality/audit-logs")
                            .header(HttpHeaders.AUTHORIZATION, qaBearer)
                            .param("entityType", "BATCH")
                            .param("entityId", String.valueOf(cascadedBatchId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].action").value("BATCH_CANCELLED"))
                    .andExpect(jsonPath("$[0].actorName").value("operations.director"));
        }

        // Subflow E: PLANNED -> CANCELLED direct cascade
        CreatedOrder orderD = createOrderViaApi(seed.product(), seed.recipe(), 2, "Planned cancel", plannerDBearer);
        mockMvc.perform(patch("/api/orders/{id}/status", orderD.orderId())
                        .header(HttpHeaders.AUTHORIZATION, plannerDBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.CANCELLED))))
                .andExpect(status().isOk());

        for (long batchId : orderD.batchIds()) {
            mockMvc.perform(get("/api/quality/audit-logs")
                            .header(HttpHeaders.AUTHORIZATION, qaBearer)
                            .param("entityType", "BATCH")
                            .param("entityId", String.valueOf(batchId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].action").value("BATCH_CANCELLED"))
                    .andExpect(jsonPath("$[0].actorName").value("planner.d"));
        }
    }

    @Test
    @DisplayName("3. Validation failures, illegal transitions, missing entities, and unauthenticated/unauthorized calls write zero audit logs")
    void failedOrderAndBatchOperationsLeaveZeroAuditLogs() throws Exception {
        String supBearer = bearerFor("order.fail.sup", Role.PRODUCTION_SUPERVISOR);
        String opBearer = bearerFor("order.fail.op", Role.OPERATOR);

        SeedBundle activeSeed = seedProductAndRecipe("FAILACT", ProductStatus.ACTIVE, RecipeStatus.APPROVED, 1);
        SeedBundle inactiveSeed = seedProductAndRecipe("FAILINACT", ProductStatus.INACTIVE, RecipeStatus.DRAFT, 1);
        Recipe draftRecipe = new Recipe(
                "RCP-DRAFT-" + SEQ.getAndIncrement(),
                activeSeed.product(),
                2,
                new BigDecimal("100.0000"),
                "kg",
                RecipeStatus.DRAFT,
                "Draft recipe"
        );
        draftRecipe.addStep(new RecipeStep(1, "Step 1", "Instr"));
        draftRecipe = recipeRepository.saveAndFlush(draftRecipe);

        CreatedOrder plannedOrder = createOrderViaApi(activeSeed.product(), activeSeed.recipe(), 2, "Valid planned", supBearer);
        CreatedOrder releasedOrder = createOrderViaApi(activeSeed.product(), activeSeed.recipe(), 2, "Valid released", supBearer);
        mockMvc.perform(patch("/api/orders/{id}/status", releasedOrder.orderId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk());

        long ordersBefore = orderRepository.count();
        long batchesBefore = batchRepository.count();
        long stepsBefore = executionStepRepository.count();
        long auditsBefore = auditLogRepository.count();
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);

        // Bean Validation error on create (400)
        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // Inactive product on create (400)
        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderRequestDto(
                                inactiveSeed.product().getId(), inactiveSeed.recipe().getId(),
                                new BigDecimal("100"), "kg", today, today.plusDays(1), null))))
                .andExpect(status().isBadRequest());

        // Draft recipe on create (400)
        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderRequestDto(
                                activeSeed.product().getId(), draftRecipe.getId(),
                                new BigDecimal("100"), "kg", today, today.plusDays(1), null))))
                .andExpect(status().isBadRequest());

        // Non-multiple quantity on create (400)
        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderRequestDto(
                                activeSeed.product().getId(), activeSeed.recipe().getId(),
                                new BigDecimal("150"), "kg", today, today.plusDays(1), null))))
                .andExpect(status().isBadRequest());

        // Missing product on create (404)
        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderRequestDto(
                                999999L, activeSeed.recipe().getId(),
                                new BigDecimal("100"), "kg", today, today.plusDays(1), null))))
                .andExpect(status().isNotFound());

        // Unauthenticated requests on POST / PATCH order / PATCH batch -> HTTP 401
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderRequestDto(
                                activeSeed.product().getId(), activeSeed.recipe().getId(),
                                new BigDecimal("100"), "kg", today, today.plusDays(1), null))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/orders/{id}/status", plannedOrder.orderId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/batches/{id}/status", plannedOrder.batchIds().get(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isUnauthorized());

        // Unauthorized role (OPERATOR) attempting order creation or batch cancellation -> HTTP 403
        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderRequestDto(
                                activeSeed.product().getId(), activeSeed.recipe().getId(),
                                new BigDecimal("100"), "kg", today, today.plusDays(1), null))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/batches/{id}/status", plannedOrder.batchIds().get(0))
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isForbidden());

        // Illegal order status transitions & missing ID
        mockMvc.perform(patch("/api/orders/{id}/status", plannedOrder.orderId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.COMPLETED))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/orders/{id}/status", 999999L)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isNotFound());

        // Invalid batch transitions: cancel under RELEASED order, start under PLANNED order, missing batch
        mockMvc.perform(patch("/api/batches/{id}/status", releasedOrder.batchIds().get(0))
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/batches/{id}/status", plannedOrder.batchIds().get(0))
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/batches/{id}/status", 999999L)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isNotFound());

        // Unauthenticated and unauthorized roles attempting production execution operations -> HTTP 401 / 403 and zero audit logs
        String qcBearer = bearerFor("oa.qc", Role.QC_ANALYST);
        long releasedBatchId = releasedOrder.batchIds().get(0);
        mockMvc.perform(post("/api/batches/{batchId}/steps/initialize", releasedBatchId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/start", releasedBatchId, 1L))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/complete", releasedBatchId, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CompleteExecutionStepRequestDto("Spec", "Notes"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/batches/{id}/status", releasedBatchId)
                        .header(HttpHeaders.AUTHORIZATION, qcBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/batches/{id}/status", releasedBatchId)
                        .header(HttpHeaders.AUTHORIZATION, qcBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("95.0000")))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/batches/{batchId}/steps/initialize", releasedBatchId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/start", releasedBatchId, 1L)
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/complete", releasedBatchId, 1L)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CompleteExecutionStepRequestDto("Spec", "Notes"))))
                .andExpect(status().isForbidden());

        assertEquals(ordersBefore, orderRepository.count());
        assertEquals(batchesBefore, batchRepository.count());
        assertEquals(stepsBefore, executionStepRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());
        assertEquals(OrderStatus.PLANNED, orderRepository.findById(plannedOrder.orderId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.RELEASED, orderRepository.findById(releasedOrder.orderId()).orElseThrow().getStatus());

        // Start batch cleanly to verify failed batch completion attempts (non-positive yield / incomplete steps) emit zero audits
        mockMvc.perform(patch("/api/batches/{id}/status", releasedBatchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isOk());
        long auditsAfterStart = auditLogRepository.count();

        mockMvc.perform(patch("/api/batches/{id}/status", releasedBatchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.COMPLETED, BigDecimal.ZERO))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/batches/{id}/status", releasedBatchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("98.0000")))))
                .andExpect(status().isBadRequest());
        assertEquals(auditsAfterStart, auditLogRepository.count(),
                "Failed batch completion attempts must emit zero audit logs");
        assertEquals(BatchStatus.IN_PROGRESS, batchRepository.findById(releasedBatchId).orElseThrow().getStatus());
        assertEquals(OrderStatus.PLANNED, orderRepository.findById(plannedOrder.orderId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("4. Audit persistence failure rolls back order creation, status updates, cascades, batch start steps, and completions")
    void auditPersistenceFailureRollsBackAllOrderAndBatchTransactions() throws Exception {
        String supBearer = bearerFor("rb.sup", Role.PRODUCTION_SUPERVISOR);
        String opBearer = bearerFor("rb.op", Role.OPERATOR);

        SeedBundle seed = seedProductAndRecipe("RB", ProductStatus.ACTIVE, RecipeStatus.APPROVED, 1);
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);

        // Baseline valid orders for rollback testing
        CreatedOrder orderPlanned = createOrderViaApi(seed.product(), seed.recipe(), 2, "RB planned", supBearer);
        CreatedOrder orderSingle = createOrderViaApi(seed.product(), seed.recipe(), 1, "RB single", supBearer);
        CreatedOrder orderReleaseAndRun = createOrderViaApi(seed.product(), seed.recipe(), 1, "RB run", supBearer);

        long ordersBaseline = orderRepository.count();
        long batchesBaseline = batchRepository.count();
        long stepsBaseline = executionStepRepository.count();
        long auditsBaseline = auditLogRepository.count();

        OrderRequestDto createAttempt = new OrderRequestDto(
                seed.product().getId(),
                seed.recipe().getId(),
                new BigDecimal("200.0000"),
                "kg",
                today,
                today.plusDays(2),
                "Should roll back"
        );

        // A1. Rollback on createOrder when 1st recordAudit (MANUFACTURING_ORDER_CREATED) fails
        doThrow(new RuntimeException("Injected audit failure on order create"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createAttempt)))
                .andExpect(status().isInternalServerError());

        assertEquals(ordersBaseline, orderRepository.count());
        assertEquals(batchesBaseline, batchRepository.count());
        assertEquals(auditsBaseline, auditLogRepository.count());

        // A2. Partial failure rollback on createOrder:
        // 1st recordAudit (MANUFACTURING_ORDER_CREATED) succeeds, 2nd recordAudit (BATCH_CREATED) fails -> ALL roll back!
        Mockito.reset(auditTrailService);
        doCallRealMethod()
                .doThrow(new RuntimeException("Injected audit failure on BATCH_CREATED"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createAttempt)))
                .andExpect(status().isInternalServerError());

        assertEquals(ordersBaseline, orderRepository.count());
        assertEquals(batchesBaseline, batchRepository.count());
        assertEquals(auditsBaseline, auditLogRepository.count(),
                "1st MANUFACTURING_ORDER_CREATED audit must roll back when 2nd BATCH_CREATED fails");

        // B. Rollback on updateOrderStatus(RELEASED)
        Mockito.reset(auditTrailService);
        doThrow(new RuntimeException("Injected audit failure on RELEASED"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(patch("/api/orders/{id}/status", orderPlanned.orderId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isInternalServerError());

        assertEquals(OrderStatus.PLANNED, orderRepository.findById(orderPlanned.orderId()).orElseThrow().getStatus());
        assertEquals(auditsBaseline, auditLogRepository.count());

        // C. Partial failure rollback on updateOrderStatus(CANCELLED) cascade:
        // 1st recordAudit (MANUFACTURING_ORDER_CANCELLED) succeeds, 2nd (BATCH_CANCELLED) fails -> order + batches + 1st audit roll back!
        Mockito.reset(auditTrailService);
        doCallRealMethod()
                .doThrow(new RuntimeException("Injected audit failure on cascade BATCH_CANCELLED"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(patch("/api/orders/{id}/status", orderPlanned.orderId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.CANCELLED))))
                .andExpect(status().isInternalServerError());

        assertEquals(OrderStatus.PLANNED, orderRepository.findById(orderPlanned.orderId()).orElseThrow().getStatus());
        for (long bid : orderPlanned.batchIds()) {
            assertEquals(BatchStatus.PLANNED, batchRepository.findById(bid).orElseThrow().getStatus());
        }
        assertEquals(auditsBaseline, auditLogRepository.count());

        // D. Partial failure rollback on individual batch cancel that would auto-cancel parent order:
        // 1st recordAudit (BATCH_CANCELLED) succeeds, 2nd (MANUFACTURING_ORDER_CANCELLED) fails -> BOTH roll back!
        Mockito.reset(auditTrailService);
        doCallRealMethod()
                .doThrow(new RuntimeException("Injected audit failure on auto-cancel order"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(patch("/api/batches/{id}/status", orderSingle.batchIds().get(0))
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isInternalServerError());

        assertEquals(OrderStatus.PLANNED, orderRepository.findById(orderSingle.orderId()).orElseThrow().getStatus());
        assertEquals(BatchStatus.PLANNED, batchRepository.findById(orderSingle.batchIds().get(0)).orElseThrow().getStatus());
        assertEquals(auditsBaseline, auditLogRepository.count());

        // E1. Release orderReleaseAndRun normally, then inject audit failure on 1st recordAudit (BATCH_STARTED)
        Mockito.reset(auditTrailService);
        mockMvc.perform(patch("/api/orders/{id}/status", orderReleaseAndRun.orderId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk());
        long auditsAfterRelease = auditLogRepository.count();

        doThrow(new RuntimeException("Injected audit failure on BATCH_STARTED"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        long runBatchId = orderReleaseAndRun.batchIds().get(0);
        mockMvc.perform(patch("/api/batches/{id}/status", runBatchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isInternalServerError());

        ManufacturingOrder rolledBackOrder = orderRepository.findById(orderReleaseAndRun.orderId()).orElseThrow();
        Batch rolledBackBatch = batchRepository.findById(runBatchId).orElseThrow();
        assertEquals(OrderStatus.RELEASED, rolledBackOrder.getStatus());
        assertNull(rolledBackOrder.getActualStartDate());
        assertEquals(BatchStatus.PLANNED, rolledBackBatch.getStatus());
        assertNull(rolledBackBatch.getStartTime());
        assertNull(rolledBackBatch.getManufactureDate());
        assertEquals(stepsBaseline, executionStepRepository.count(),
                "Execution steps initialized on batch start must also roll back when audit persistence fails");
        assertEquals(auditsAfterRelease, auditLogRepository.count());

        // E2. Partial failure rollback on batch start:
        // 1st recordAudit (BATCH_STARTED) succeeds, 2nd recordAudit (MANUFACTURING_ORDER_UPDATED) fails -> ALL roll back!
        Mockito.reset(auditTrailService);
        doCallRealMethod()
                .doThrow(new RuntimeException("Injected audit failure on auto-advance MANUFACTURING_ORDER_UPDATED"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(patch("/api/batches/{id}/status", runBatchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isInternalServerError());

        ManufacturingOrder partialRolledBackOrder = orderRepository.findById(orderReleaseAndRun.orderId()).orElseThrow();
        Batch partialRolledBackBatch = batchRepository.findById(runBatchId).orElseThrow();
        assertEquals(OrderStatus.RELEASED, partialRolledBackOrder.getStatus());
        assertNull(partialRolledBackOrder.getActualStartDate());
        assertEquals(BatchStatus.PLANNED, partialRolledBackBatch.getStatus());
        assertNull(partialRolledBackBatch.getStartTime());
        assertNull(partialRolledBackBatch.getManufactureDate());
        assertEquals(stepsBaseline, executionStepRepository.count(),
                "Execution steps initialized on batch start must roll back on partial second-audit failure");
        assertEquals(auditsAfterRelease, auditLogRepository.count(),
                "1st BATCH_STARTED audit must roll back when 2nd MANUFACTURING_ORDER_UPDATED audit fails");

        // F1. Reset spy, start batch and complete steps cleanly, then fail on 1st recordAudit (BATCH_COMPLETED)
        Mockito.reset(auditTrailService);
        mockMvc.perform(patch("/api/batches/{id}/status", runBatchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isOk());

        MvcResult stepsRes = mockMvc.perform(get("/api/batches/{batchId}/steps", runBatchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode step : objectMapper.readTree(stepsRes.getResponse().getContentAsString()).get("executionSteps")) {
            long stepId = step.get("id").asLong();
            mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/start", runBatchId, stepId)
                            .header(HttpHeaders.AUTHORIZATION, opBearer))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/complete", runBatchId, stepId)
                            .header(HttpHeaders.AUTHORIZATION, opBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CompleteExecutionStepRequestDto("Spec met", "Notes"))))
                    .andExpect(status().isOk());
        }
        long auditsBeforeComplete = auditLogRepository.count();

        doThrow(new RuntimeException("Injected audit failure on BATCH_COMPLETED"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(patch("/api/batches/{id}/status", runBatchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("98.0000")))))
                .andExpect(status().isInternalServerError());

        ManufacturingOrder notCompletedOrder = orderRepository.findById(orderReleaseAndRun.orderId()).orElseThrow();
        Batch notCompletedBatch = batchRepository.findById(runBatchId).orElseThrow();
        assertEquals(OrderStatus.IN_PROGRESS, notCompletedOrder.getStatus());
        assertNull(notCompletedOrder.getActualEndDate());
        assertEquals(BatchStatus.IN_PROGRESS, notCompletedBatch.getStatus());
        assertNull(notCompletedBatch.getActualYield());
        assertNull(notCompletedBatch.getEndTime());
        assertNull(notCompletedBatch.getQaStatus());
        assertEquals(auditsBeforeComplete, auditLogRepository.count());

        // F2. Partial failure rollback on final batch complete:
        // 1st recordAudit (BATCH_COMPLETED) succeeds, 2nd recordAudit (MANUFACTURING_ORDER_COMPLETED) fails -> ALL roll back!
        Mockito.reset(auditTrailService);
        doCallRealMethod()
                .doThrow(new RuntimeException("Injected audit failure on auto-complete MANUFACTURING_ORDER_COMPLETED"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(patch("/api/batches/{id}/status", runBatchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("98.0000")))))
                .andExpect(status().isInternalServerError());

        ManufacturingOrder partialNotCompletedOrder = orderRepository.findById(orderReleaseAndRun.orderId()).orElseThrow();
        Batch partialNotCompletedBatch = batchRepository.findById(runBatchId).orElseThrow();
        assertEquals(OrderStatus.IN_PROGRESS, partialNotCompletedOrder.getStatus());
        assertNull(partialNotCompletedOrder.getActualEndDate());
        assertEquals(BatchStatus.IN_PROGRESS, partialNotCompletedBatch.getStatus());
        assertNull(partialNotCompletedBatch.getActualYield());
        assertNull(partialNotCompletedBatch.getEndTime());
        assertNull(partialNotCompletedBatch.getQaStatus());
        assertEquals(auditsBeforeComplete, auditLogRepository.count(),
                "1st BATCH_COMPLETED audit must roll back when 2nd MANUFACTURING_ORDER_COMPLETED audit fails");

        // G. Clean recovery once spy resets -> emits BATCH_COMPLETED + MANUFACTURING_ORDER_COMPLETED
        Mockito.reset(auditTrailService);
        completeAllStepsAlreadyCompleted_JustCompleteBatch(runBatchId, opBearer);
        assertEquals(OrderStatus.COMPLETED, orderRepository.findById(orderReleaseAndRun.orderId()).orElseThrow().getStatus());
        assertEquals(auditsBeforeComplete + 2, auditLogRepository.count());
    }

    private void completeAllStepsAlreadyCompleted_JustCompleteBatch(long batchId, String opBearer) throws Exception {
        mockMvc.perform(patch("/api/batches/{id}/status", batchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("98.5000")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("5. Manufacturing Order and Batch audit logs remain append-only and read-only over HTTP")
    void orderAndBatchAuditLogsRemainAppendOnlyOverHttp() throws Exception {
        String supBearer = bearerFor("Prod.Sup.Immut", Role.PRODUCTION_SUPERVISOR);
        String qaBearer = bearerFor("qa.auditor", Role.QA_REVIEWER);

        SeedBundle seed = seedProductAndRecipe("IMMUT", ProductStatus.ACTIVE, RecipeStatus.APPROVED, 1);
        CreatedOrder created = createOrderViaApi(seed.product(), seed.recipe(), 1, "Immutable test", supBearer);

        List<AuditLog> orderLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                "MANUFACTURING_ORDER", created.orderId());
        assertEquals(1, orderLogs.size());
        assertEquals("MANUFACTURING_ORDER_CREATED", orderLogs.get(0).getAction());

        mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .param("entityType", "MANUFACTURING_ORDER")
                        .param("entityId", String.valueOf(created.orderId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("MANUFACTURING_ORDER_CREATED"))
                .andExpect(jsonPath("$[0].actorName").value("prod.sup.immut"));

        mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .param("entityType", "BATCH")
                        .param("entityId", String.valueOf(created.batchIds().get(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("BATCH_CREATED"));

        mockMvc.perform(put("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("6. Release revalidates active product and approved recipe without mutating order, batches, or audits on rejection")
    void releaseRevalidatesProductAndRecipeOverHttp() throws Exception {
        String supBearer = bearerFor("relval.planner", Role.PRODUCTION_SUPERVISOR);
        String adminBearer = bearerFor("master.admin", Role.ADMIN);
        String qaBearer = bearerFor("qa.approver", Role.QA_REVIEWER);

        SeedBundle seed = seedProductAndRecipe("RELVAL", ProductStatus.ACTIVE, RecipeStatus.APPROVED, 1);
        CreatedOrder mo1 = createOrderViaApi(seed.product(), seed.recipe(), 2, "Release revalidation test", supBearer);

        // Step 1: Deactivate product after MO1 was planned -> attempting to release MO1 must return 400
        mockMvc.perform(patch("/api/products/{id}/status", seed.product().getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StatusUpdateRequestDto(ProductStatus.INACTIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        long auditsBeforeProductRejection = auditLogRepository.count();
        mockMvc.perform(patch("/api/orders/{id}/status", mo1.orderId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("is not ACTIVE")));

        ManufacturingOrder afterProductRejectOrder = orderRepository.findById(mo1.orderId()).orElseThrow();
        assertEquals(OrderStatus.PLANNED, afterProductRejectOrder.getStatus());
        for (Long batchId : mo1.batchIds()) {
            assertEquals(BatchStatus.PLANNED, batchRepository.findById(batchId).orElseThrow().getStatus());
        }
        assertEquals(auditsBeforeProductRejection, auditLogRepository.count());
        assertEquals(1, auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                "MANUFACTURING_ORDER", mo1.orderId()).size());

        // Step 2: Reactivate product, create v2 recipe, and approve v2 -> v1 becomes OBSOLETE
        mockMvc.perform(patch("/api/products/{id}/status", seed.product().getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StatusUpdateRequestDto(ProductStatus.ACTIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        MvcResult v2CreateRes = mockMvc.perform(post("/api/recipes/{id}/new-version", seed.recipe().getId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andReturn();
        long v2Id = objectMapper.readTree(v2CreateRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(patch("/api/recipes/{id}/status", v2Id)
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertEquals(RecipeStatus.OBSOLETE, recipeRepository.findById(seed.recipe().getId()).orElseThrow().getStatus());

        // Attempting to release MO1 (still referencing OBSOLETE v1) must return 400 with zero state/audit mutation
        long auditsBeforeRecipeRejection = auditLogRepository.count();
        mockMvc.perform(patch("/api/orders/{id}/status", mo1.orderId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("OBSOLETE")))
                .andExpect(jsonPath("$.message", containsString("Only APPROVED recipes can be released")));

        assertEquals(OrderStatus.PLANNED, orderRepository.findById(mo1.orderId()).orElseThrow().getStatus());
        for (Long batchId : mo1.batchIds()) {
            assertEquals(BatchStatus.PLANNED, batchRepository.findById(batchId).orElseThrow().getStatus());
        }
        assertEquals(auditsBeforeRecipeRejection, auditLogRepository.count());
        assertEquals(1, auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                "MANUFACTURING_ORDER", mo1.orderId()).size());

        // Step 3: MO1 can still be cleanly cancelled even with an OBSOLETE recipe
        mockMvc.perform(patch("/api/orders/{id}/status", mo1.orderId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.CANCELLED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Step 4: An order created against active product + approved v2 releases cleanly
        Recipe v2Recipe = recipeRepository.findById(v2Id).orElseThrow();
        CreatedOrder mo2 = createOrderViaApi(seed.product(), v2Recipe, 1, "Valid v2 order", supBearer);
        mockMvc.perform(patch("/api/orders/{id}/status", mo2.orderId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        List<AuditLog> mo2Logs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                "MANUFACTURING_ORDER", mo2.orderId());
        assertEquals(2, mo2Logs.size());
        assertEquals("MANUFACTURING_ORDER_RELEASED", mo2Logs.get(0).getAction());
    }
}

