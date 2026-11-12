package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.BatchStatusUpdateDto;
import com.pharmatrack.dto.CompleteExecutionStepRequestDto;
import com.pharmatrack.dto.OrderRequestDto;
import com.pharmatrack.dto.OrderStatusUpdateDto;
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
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.JwtService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack Spring Boot + H2 + MockMvc integration tests verifying Manufacturing Order
 * completion and individual batch cancellation lifecycle rules.
 * Runs exclusively against an isolated in-memory H2 database.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_order_cancel_completion_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderBatchCancellationCompletionIntegrationTest {

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
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

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

    private record SeedProductRecipe(Product product, Recipe recipe) {
    }

    private SeedProductRecipe seedApprovedProductAndRecipe(String tag) {
        int n = SEQ.getAndIncrement();
        Product product = productRepository.saveAndFlush(new Product(
                "PRD-OCC-" + tag + "-" + n,
                "Order Lifecycle Product " + tag,
                "500 mg",
                "Tablet",
                "Analgesic",
                ProductStatus.ACTIVE
        ));

        Recipe recipe = new Recipe(
                "RCP-OCC-" + tag + "-" + n,
                product,
                1,
                new BigDecimal("100.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Master recipe for " + tag
        );
        recipe.addStep(new RecipeStep(1, "Blending", "Blend API and excipients uniformly."));
        recipe.addStep(new RecipeStep(2, "Compression", "Compress tablets to target hardness."));
        Recipe savedRecipe = recipeRepository.saveAndFlush(recipe);

        return new SeedProductRecipe(product, savedRecipe);
    }

    private record CreatedOrder(long orderId, List<Long> batchIds) {
    }

    private CreatedOrder createOrderViaApi(String tag, int batchCount) throws Exception {
        String supBearer = bearerFor("occ.prod.sup", Role.PRODUCTION_SUPERVISOR);
        SeedProductRecipe seed = seedApprovedProductAndRecipe(tag);
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        BigDecimal totalQty = new BigDecimal("100.0000").multiply(BigDecimal.valueOf(batchCount));

        OrderRequestDto req = new OrderRequestDto(
                seed.product().getId(),
                seed.recipe().getId(),
                totalQty,
                "kg",
                today,
                today.plusDays(3),
                "Integration test order " + tag
        );

        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.targetBatchCount").value(batchCount))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        long orderId = root.get("id").asLong();
        List<Long> batchIds = new ArrayList<>();
        for (JsonNode b : root.get("batches")) {
            batchIds.add(b.get("id").asLong());
        }
        assertEquals(batchCount, batchIds.size());
        return new CreatedOrder(orderId, batchIds);
    }

    private void startAndCompleteBatchViaApi(long batchId, String actualYield) throws Exception {
        String opBearer = bearerFor("occ.operator", Role.OPERATOR);
        mockMvc.perform(patch("/api/batches/{id}/status", batchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        MvcResult stepsRes = mockMvc.perform(get("/api/batches/{batchId}/steps", batchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode stepsRoot = objectMapper.readTree(stepsRes.getResponse().getContentAsString());
        for (JsonNode step : stepsRoot.get("executionSteps")) {
            long stepId = step.get("id").asLong();
            mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/start", batchId, stepId)
                            .header(HttpHeaders.AUTHORIZATION, opBearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

            CompleteExecutionStepRequestDto completeStepReq = new CompleteExecutionStepRequestDto(
                    "Within specification",
                    "Completed during automated H2 integration test"
            );
            mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/complete", batchId, stepId)
                            .header(HttpHeaders.AUTHORIZATION, opBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(completeStepReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        }

        mockMvc.perform(patch("/api/batches/{id}/status", batchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal(actualYield)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.qaStatus").value("PENDING_QA"));
    }

    @Test
    @DisplayName("1. All batches completed -> order auto-completes to COMPLETED with actualEndDate")
    void shouldAutoCompleteOrderWhenAllBatchesAreCompleted() throws Exception {
        String supBearer = bearerFor("occ.prod.sup", Role.PRODUCTION_SUPERVISOR);
        CreatedOrder created = createOrderViaApi("ALLCOMP", 2);
        long orderId = created.orderId();
        long batch1 = created.batchIds().get(0);
        long batch2 = created.batchIds().get(1);

        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        startAndCompleteBatchViaApi(batch1, "98.5000");

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.actualEndDate").isEmpty());

        startAndCompleteBatchViaApi(batch2, "99.1000");

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.actualEndDate").isNotEmpty());

        ManufacturingOrder persistedOrder = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.COMPLETED, persistedOrder.getStatus());
        assertNotNull(persistedOrder.getActualEndDate());
    }

    @Test
    @DisplayName("2. Cancelled batches + remaining batches completed -> order auto-completes to COMPLETED")
    void shouldAutoCompleteOrderWhenSomeBatchesCancelledAndRemainingCompleted() throws Exception {
        String supBearer = bearerFor("occ.prod.sup", Role.PRODUCTION_SUPERVISOR);
        CreatedOrder created = createOrderViaApi("MIXCOMP", 3);
        long orderId = created.orderId();
        long batch1 = created.batchIds().get(0);
        long batch2 = created.batchIds().get(1);
        long batch3 = created.batchIds().get(2);

        // Cancel two batches while parent order is PLANNED
        mockMvc.perform(patch("/api/batches/{id}/status", batch1)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(patch("/api/batches/{id}/status", batch3)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Order must still be PLANNED because batch2 is PLANNED
        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNED"));

        // Release order and execute the sole remaining batch
        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        startAndCompleteBatchViaApi(batch2, "97.4000");

        // Order must now automatically be COMPLETED
        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.actualEndDate").isNotEmpty());

        ManufacturingOrder persistedOrder = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.COMPLETED, persistedOrder.getStatus());
        assertNotNull(persistedOrder.getActualEndDate());
        assertEquals(BatchStatus.CANCELLED, batchRepository.findById(batch1).orElseThrow().getStatus());
        assertEquals(BatchStatus.COMPLETED, batchRepository.findById(batch2).orElseThrow().getStatus());
        assertEquals(BatchStatus.CANCELLED, batchRepository.findById(batch3).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("3. Mixture of completed, planned/in-progress, and cancelled batches -> order stays IN_PROGRESS and rejects manual completion")
    void shouldKeepOrderInProgressWhenNonCancelledBatchesRemainPlannedOrInProgress() throws Exception {
        String supBearer = bearerFor("occ.prod.sup", Role.PRODUCTION_SUPERVISOR);
        String opBearer = bearerFor("occ.operator", Role.OPERATOR);
        CreatedOrder created = createOrderViaApi("MIXPROG", 3);
        long orderId = created.orderId();
        long batch1 = created.batchIds().get(0);
        long batch2 = created.batchIds().get(1);
        long batch3 = created.batchIds().get(2);

        // Cancel batch1 while order is PLANNED
        mockMvc.perform(patch("/api/batches/{id}/status", batch1)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk());

        // Complete batch2 while batch3 stays PLANNED
        startAndCompleteBatchViaApi(batch2, "99.0000");

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.actualEndDate").isEmpty());

        // Manual completion must be rejected while batch3 is PLANNED
        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.COMPLETED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("PLANNED or IN_PROGRESS")));

        // Start batch3 (so mix is CANCELLED, COMPLETED, IN_PROGRESS) -> manual completion still rejected
        mockMvc.perform(patch("/api/batches/{id}/status", batch3)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.COMPLETED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("PLANNED or IN_PROGRESS")));

        assertEquals(OrderStatus.IN_PROGRESS, orderRepository.findById(orderId).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("4. All batches cancelled -> cancelling last PLANNED batch auto-cancels parent order, and RELEASE/COMPLETE remain blocked")
    void shouldAutoCancelOrderWhenAllBatchesCancelledAndRejectReleaseOrComplete() throws Exception {
        String supBearer = bearerFor("occ.prod.sup", Role.PRODUCTION_SUPERVISOR);
        CreatedOrder created = createOrderViaApi("ALLCANC", 2);
        long orderId = created.orderId();
        long batch1 = created.batchIds().get(0);
        long batch2 = created.batchIds().get(1);

        // Cancel first batch -> order stays PLANNED
        mockMvc.perform(patch("/api/batches/{id}/status", batch1)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNED"));

        // Cancel second (final) batch -> parent order auto-transitions to CANCELLED
        mockMvc.perform(patch("/api/batches/{id}/status", batch2)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/orders/{id}", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.actualEndDate").isEmpty());

        ManufacturingOrder cancelledOrder = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, cancelledOrder.getStatus());

        // Attempting to release or complete a CANCELLED order is rejected
        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("CANCELLED order")));

        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.COMPLETED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("CANCELLED order")));

        // Defensive check: an IN_PROGRESS order whose batches are all CANCELLED must NOT allow COMPLETED
        CreatedOrder defensiveOrder = createOrderViaApi("ALLCANCDEF", 1);
        long defOrderId = defensiveOrder.orderId();
        long defBatchId = defensiveOrder.batchIds().get(0);

        ManufacturingOrder mo = orderRepository.findById(defOrderId).orElseThrow();
        mo.setStatus(OrderStatus.IN_PROGRESS);
        orderRepository.saveAndFlush(mo);
        Batch b = batchRepository.findById(defBatchId).orElseThrow();
        b.setStatus(BatchStatus.CANCELLED);
        batchRepository.saveAndFlush(b);

        mockMvc.perform(patch("/api/orders/{id}/status", defOrderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.COMPLETED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("all child batches are CANCELLED")));

        assertEquals(OrderStatus.IN_PROGRESS, orderRepository.findById(defOrderId).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("4b. Released order with partial batch cancellations can still be cancelled as an order before execution starts")
    void shouldAllowCancellingReleasedOrderWithPreviouslyCancelledBatch() throws Exception {
        String supBearer = bearerFor("occ.prod.sup", Role.PRODUCTION_SUPERVISOR);
        CreatedOrder created = createOrderViaApi("RELCANC", 2);
        long orderId = created.orderId();
        long batch1 = created.batchIds().get(0);
        long batch2 = created.batchIds().get(1);

        mockMvc.perform(patch("/api/batches/{id}/status", batch1)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.CANCELLED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertEquals(OrderStatus.CANCELLED, orderRepository.findById(orderId).orElseThrow().getStatus());
        assertEquals(BatchStatus.CANCELLED, batchRepository.findById(batch1).orElseThrow().getStatus());
        assertEquals(BatchStatus.CANCELLED, batchRepository.findById(batch2).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("5. Invalid individual batch cancellations are rejected with HTTP 400 and leave database state unchanged")
    void shouldRejectInvalidBatchCancellationAttempts() throws Exception {
        String supBearer = bearerFor("occ.prod.sup", Role.PRODUCTION_SUPERVISOR);
        String opBearer = bearerFor("occ.operator", Role.OPERATOR);
        CreatedOrder created = createOrderViaApi("INVCANC", 2);
        long orderId = created.orderId();
        long batch1 = created.batchIds().get(0);
        long batch2 = created.batchIds().get(1);

        // Release order -> cancelling PLANNED batch1 must be rejected
        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/batches/{id}/status", batch1)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("permitted only while its parent order is PLANNED")));
        assertEquals(BatchStatus.PLANNED, batchRepository.findById(batch1).orElseThrow().getStatus());

        // Start batch1 -> parent order becomes IN_PROGRESS; cancelling IN_PROGRESS batch1 OR PLANNED batch2 must be rejected
        mockMvc.perform(patch("/api/batches/{id}/status", batch1)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/batches/{id}/status", batch1)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("already IN_PROGRESS")));
        assertEquals(BatchStatus.IN_PROGRESS, batchRepository.findById(batch1).orElseThrow().getStatus());

        mockMvc.perform(patch("/api/batches/{id}/status", batch2)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("permitted only while its parent order is PLANNED")));
        assertEquals(BatchStatus.PLANNED, batchRepository.findById(batch2).orElseThrow().getStatus());

        // Complete batch1's steps & complete batch1 -> cancelling COMPLETED batch1 must be rejected
        MvcResult stepsRes = mockMvc.perform(get("/api/batches/{batchId}/steps", batch1)
                        .header(HttpHeaders.AUTHORIZATION, opBearer))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode step : objectMapper.readTree(stepsRes.getResponse().getContentAsString()).get("executionSteps")) {
            long stepId = step.get("id").asLong();
            mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/start", batch1, stepId)
                            .header(HttpHeaders.AUTHORIZATION, opBearer))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/complete", batch1, stepId)
                            .header(HttpHeaders.AUTHORIZATION, opBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CompleteExecutionStepRequestDto("OK", null))))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(patch("/api/batches/{id}/status", batch1)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("99.5000")))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/batches/{id}/status", batch1)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("COMPLETED batch")));
        assertEquals(BatchStatus.COMPLETED, batchRepository.findById(batch1).orElseThrow().getStatus());

        // Also confirm an already CANCELLED batch cannot transition to PLANNED or IN_PROGRESS
        CreatedOrder cancelledFlow = createOrderViaApi("TERM", 2);
        long cancelledBatchId = cancelledFlow.batchIds().get(0);
        mockMvc.perform(patch("/api/batches/{id}/status", cancelledBatchId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/batches/{id}/status", cancelledBatchId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.PLANNED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("CANCELLED batch")));

        mockMvc.perform(patch("/api/batches/{id}/status", cancelledBatchId)
                        .header(HttpHeaders.AUTHORIZATION, opBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("CANCELLED batch")));
        assertEquals(BatchStatus.CANCELLED, batchRepository.findById(cancelledBatchId).orElseThrow().getStatus());
    }
}
