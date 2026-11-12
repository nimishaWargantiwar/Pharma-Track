package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.BatchStatusUpdateDto;
import com.pharmatrack.dto.CancelCapaActionRequestDto;
import com.pharmatrack.dto.CloseDeviationRequestDto;
import com.pharmatrack.dto.CompleteCapaActionRequestDto;
import com.pharmatrack.dto.CompleteExecutionStepRequestDto;
import com.pharmatrack.dto.CreateCapaActionRequestDto;
import com.pharmatrack.dto.CreateDeviationRequestDto;
import com.pharmatrack.dto.LoginRequestDto;
import com.pharmatrack.dto.OrderRequestDto;
import com.pharmatrack.dto.OrderStatusUpdateDto;
import com.pharmatrack.dto.ProductCreateRequestDto;
import com.pharmatrack.dto.QaDispositionRequestDto;
import com.pharmatrack.dto.QualityCheckCorrectionRequestDto;
import com.pharmatrack.dto.QualityCheckRequestDto;
import com.pharmatrack.dto.RecipeMaterialDto;
import com.pharmatrack.dto.RecipeRequestDto;
import com.pharmatrack.dto.RecipeStatusUpdateDto;
import com.pharmatrack.dto.RecipeStepDto;
import com.pharmatrack.dto.RecordRootCauseRequestDto;
import com.pharmatrack.dto.ReopenCapaRequestDto;
import com.pharmatrack.dto.StartCapaActionRequestDto;
import com.pharmatrack.dto.StartCapaRequestDto;
import com.pharmatrack.dto.StartInvestigationRequestDto;
import com.pharmatrack.dto.StatusUpdateRequestDto;
import com.pharmatrack.dto.SubmitVerificationRequestDto;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.CapaType;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.CapaActionRepository;
import com.pharmatrack.repository.DeviationRepository;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.QualityCheckRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.repository.UserAccountRepository;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full Spring Boot + H2 + MockMvc RBAC authorization matrix integration suite verifying
 * every endpoint across Products, Recipes, Manufacturing Orders, Batches, Production Execution,
 * Quality Management, Deviations, CAPAs, and Audit Trail against real signed JWTs for all 5 roles.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_business_rbac_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BusinessRbacAuthorizationIntegrationTest {

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
    private ProductRepository productRepository;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private ManufacturingOrderRepository orderRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private QualityCheckRepository qualityCheckRepository;

    @Autowired
    private DeviationRepository deviationRepository;

    @Autowired
    private CapaActionRepository capaActionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private final Map<Role, String> roleTokens = new EnumMap<>(Role.class);

    @BeforeEach
    void setUpRoleUsersAndTokens() throws Exception {
        if (!roleTokens.isEmpty()) {
            return;
        }
        for (Role role : Role.values()) {
            String username = "rbac." + role.name().toLowerCase();
            String rawPassword = "RbacPass9!" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            UserAccount user = userAccountRepository.findByUsernameIgnoreCase(username)
                    .orElseGet(() -> userAccountRepository.saveAndFlush(new UserAccount(
                            username,
                            passwordEncoder.encode(rawPassword),
                            "RBAC " + role.name(),
                            role,
                            true
                    )));
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            user.setEnabled(true);
            user.setRole(role);
            userAccountRepository.saveAndFlush(user);

            MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequestDto(username, rawPassword))))
                    .andExpect(status().isOk())
                    .andReturn();
            String jwt = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("accessToken").asText();
            roleTokens.put(role, "Bearer " + jwt);
        }
    }

    private String auth(Role role) {
        return roleTokens.get(role);
    }

    private long createProductAsAdmin(String prefix) throws Exception {
        int n = SEQ.getAndIncrement();
        ProductCreateRequestDto dto = new ProductCreateRequestDto(
                "RBAC-" + prefix + "-" + n,
                "RBAC Product " + prefix + " " + n,
                "500 mg",
                "Tablet",
                "RBAC test formulation",
                ProductStatus.ACTIVE
        );
        MvcResult result = mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createApprovedRecipeViaHttp(long productId) throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/recipes")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRecipeRequest(productId, "Initial"))))
                .andExpect(status().isCreated())
                .andReturn();
        long recipeId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(patch("/api/recipes/{id}/status", recipeId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.QA_REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isOk());
        return recipeId;
    }

    private RecipeRequestDto sampleRecipeRequest(long productId, String suffix) {
        return new RecipeRequestDto(
                productId,
                new BigDecimal("100.0000"),
                "kg",
                "Recipe " + suffix,
                List.of(new RecipeMaterialDto("API Material " + suffix, new BigDecimal("60.0000"), "kg")),
                List.of(new RecipeStepDto(1, "Blending " + suffix, "Blend thoroughly at 25 RPM"))
        );
    }

    private JsonNode createOrderAsSupervisor(long productId, long recipeId, BigDecimal plannedQty) throws Exception {
        LocalDate today = LocalDate.now(com.pharmatrack.util.AppTime.APP_ZONE_ID);
        OrderRequestDto orderDto = new OrderRequestDto(
                productId, recipeId, plannedQty, "kg", today, today.plusDays(3), "RBAC test order"
        );
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderDto)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long createAndCompleteBatchViaHttp(String prefix) throws Exception {
        long productId = createProductAsAdmin(prefix);
        long recipeId = createApprovedRecipeViaHttp(productId);
        JsonNode orderNode = createOrderAsSupervisor(productId, recipeId, new BigDecimal("100.0000"));
        long orderId = orderNode.get("id").asLong();
        long batchId = orderNode.get("batches").get(0).get("id").asLong();

        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/batches/{id}/status", batchId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                .andExpect(status().isOk());

        MvcResult stepsRes = mockMvc.perform(get("/api/batches/{id}/steps", batchId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR)))
                .andExpect(status().isOk())
                .andReturn();
        long stepId = objectMapper.readTree(stepsRes.getResponse().getContentAsString())
                .get("executionSteps").get(0).get("id").asLong();

        mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/start", batchId, stepId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/complete", batchId, stepId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteExecutionStepRequestDto("25 RPM verified", "Within spec"))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/batches/{id}/status", batchId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("99.5000")))))
                .andExpect(status().isOk());

        return batchId;
    }

    @Test
    @DisplayName("1. Public permitAll routes succeed, unmapped routes deny by default, and unauthenticated requests return 401 without mutating state")
    void permitAllUnauthenticatedAndUnmappedRoutesBehaveSafely() throws Exception {
        long productsBefore = productRepository.count();
        long auditsBefore = auditLogRepository.count();

        // Public endpoints
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(options("/api/products")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Authorization")));

        // Unmapped routes fail closed: 401 unauthenticated, 403 even for ADMIN, without stack traces
        mockMvc.perform(get("/api/unknown-rbac-route"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(content().string(not(containsString("Exception"))));

        mockMvc.perform(get("/api/unknown-rbac-route")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(content().string(not(containsString("Exception"))));

        // Unauthenticated business GET & mutation endpoints return 401 JSON
        List<MvcResult> anonResults = List.of(
                mockMvc.perform(get("/api/products")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/recipes")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/batches/execution")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/quality/batches")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/quality/deviations")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(get("/api/quality/audit-logs")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(post("/api/recipes").contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(delete("/api/recipes/1")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(patch("/api/batches/1/status").contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(post("/api/batches/1/steps/initialize")).andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(post("/api/quality/batches/1/checks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(post("/api/quality/batches/1/disposition").contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isUnauthorized()).andReturn(),
                mockMvc.perform(post("/api/quality/deviations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isUnauthorized()).andReturn()
        );
        for (MvcResult r : anonResults) {
            JsonNode err = objectMapper.readTree(r.getResponse().getContentAsString());
            assertEquals(401, err.get("status").asInt());
        }

        assertEquals(productsBefore, productRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());
    }

    @Test
    @DisplayName("2. Product endpoints: all 5 roles can read, only ADMIN can create, update, and change status")
    void productEndpointsEnforceRoleMatrix() throws Exception {
        long productId = createProductAsAdmin("PRD");
        long productsCountAfterCreate = productRepository.count();

        // All 5 roles can GET list & by-id
        for (Role role : Role.values()) {
            mockMvc.perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, auth(role)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/products/{id}", productId).header(HttpHeaders.AUTHORIZATION, auth(role)))
                    .andExpect(status().isOk());
        }

        ProductCreateRequestDto forbiddenCreate = new ProductCreateRequestDto(
                "RBAC-PRD-DENY-" + SEQ.getAndIncrement(),
                "Denied Product",
                "100 mg",
                "Tablet",
                "Should not be created",
                ProductStatus.ACTIVE
        );
        Product updatePayload = new Product(
                "RBAC-PRD-UPD-" + SEQ.getAndIncrement(),
                "Updated Name",
                "250 mg",
                "Capsule",
                "Updated desc",
                ProductStatus.ACTIVE
        );
        StatusUpdateRequestDto statusPayload = new StatusUpdateRequestDto(ProductStatus.INACTIVE);

        // Non-ADMIN roles get 403 without leaking internals or mutating DB
        for (Role role : List.of(Role.PRODUCTION_SUPERVISOR, Role.OPERATOR, Role.QC_ANALYST, Role.QA_REVIEWER)) {
            mockMvc.perform(post("/api/products")
                            .header(HttpHeaders.AUTHORIZATION, auth(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(forbiddenCreate)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(content().string(not(containsString("Exception"))));

            mockMvc.perform(put("/api/products/{id}", productId)
                            .header(HttpHeaders.AUTHORIZATION, auth(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updatePayload)))
                    .andExpect(status().isForbidden());

            mockMvc.perform(patch("/api/products/{id}/status", productId)
                            .header(HttpHeaders.AUTHORIZATION, auth(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusPayload)))
                    .andExpect(status().isForbidden());
        }
        assertEquals(productsCountAfterCreate, productRepository.count());

        // ADMIN can PUT and PATCH status
        mockMvc.perform(put("/api/products/{id}", productId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatePayload)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/products/{id}/status", productId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    @Test
    @DisplayName("3. Recipe endpoints enforce read exclusion for OPERATOR, write restriction to ADMIN/SUPERVISOR, and approval by ADMIN/QA_REVIEWER")
    void recipeEndpointsEnforceRoleMatrix() throws Exception {
        long productId = createProductAsAdmin("RCP");

        // Create drafts as both ADMIN and PRODUCTION_SUPERVISOR
        MvcResult adminDraftRes = mockMvc.perform(post("/api/recipes")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRecipeRequest(productId, "AdminDraft"))))
                .andExpect(status().isCreated())
                .andReturn();
        long adminDraftId = objectMapper.readTree(adminDraftRes.getResponse().getContentAsString()).get("id").asLong();

        MvcResult supDraftRes = mockMvc.perform(post("/api/recipes")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRecipeRequest(productId, "SupDraft"))))
                .andExpect(status().isCreated())
                .andReturn();
        long supDraftId = objectMapper.readTree(supDraftRes.getResponse().getContentAsString()).get("id").asLong();

        long recipesBeforeDenied = recipeRepository.count();

        // Read endpoints: OPERATOR gets 403; ADMIN, PRODUCTION_SUPERVISOR, QC_ANALYST, QA_REVIEWER get 200
        mockMvc.perform(get("/api/recipes").header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/recipes/{id}", adminDraftId).header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/recipes/product/{productId}", productId).header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR)))
                .andExpect(status().isForbidden());

        for (Role reader : List.of(Role.ADMIN, Role.PRODUCTION_SUPERVISOR, Role.QC_ANALYST, Role.QA_REVIEWER)) {
            mockMvc.perform(get("/api/recipes").header(HttpHeaders.AUTHORIZATION, auth(reader)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/recipes/{id}", adminDraftId).header(HttpHeaders.AUTHORIZATION, auth(reader)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/recipes/product/{productId}", productId).header(HttpHeaders.AUTHORIZATION, auth(reader)))
                    .andExpect(status().isOk());
        }

        // Write endpoints (POST, PUT, new-version, DELETE): OPERATOR, QC_ANALYST, QA_REVIEWER get 403
        RecipeRequestDto updateDto = sampleRecipeRequest(productId, "Revised");
        for (Role deniedWriter : List.of(Role.OPERATOR, Role.QC_ANALYST, Role.QA_REVIEWER)) {
            mockMvc.perform(post("/api/recipes")
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedWriter))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put("/api/recipes/{id}", adminDraftId)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedWriter))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/recipes/{id}/new-version", adminDraftId)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedWriter)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/recipes/{id}", adminDraftId)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedWriter)))
                    .andExpect(status().isForbidden());
        }
        assertEquals(recipesBeforeDenied, recipeRepository.count());

        // ADMIN and PRODUCTION_SUPERVISOR can update DRAFT
        mockMvc.perform(put("/api/recipes/{id}", adminDraftId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRecipeRequest(productId, "AdminUpdated"))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/recipes/{id}", supDraftId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRecipeRequest(productId, "SupUpdated"))))
                .andExpect(status().isOk());

        // Status transition (APPROVE): PRODUCTION_SUPERVISOR, OPERATOR, QC_ANALYST get 403
        RecipeStatusUpdateDto approveDto = new RecipeStatusUpdateDto(RecipeStatus.APPROVED);
        for (Role deniedApprover : List.of(Role.PRODUCTION_SUPERVISOR, Role.OPERATOR, Role.QC_ANALYST)) {
            mockMvc.perform(patch("/api/recipes/{id}/status", adminDraftId)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedApprover))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(approveDto)))
                    .andExpect(status().isForbidden());
        }

        // ADMIN and QA_REVIEWER can approve
        mockMvc.perform(patch("/api/recipes/{id}/status", adminDraftId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(patch("/api/recipes/{id}/status", supDraftId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.QA_REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // ADMIN and PRODUCTION_SUPERVISOR can clone new version and delete DRAFT
        MvcResult clonedByAdminRes = mockMvc.perform(post("/api/recipes/{id}/new-version", supDraftId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN)))
                .andExpect(status().isCreated())
                .andReturn();
        long clonedByAdminId = objectMapper.readTree(clonedByAdminRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(delete("/api/recipes/{id}", clonedByAdminId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN)))
                .andExpect(status().isNoContent());

        MvcResult clonedBySupRes = mockMvc.perform(post("/api/recipes/{id}/new-version", supDraftId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR)))
                .andExpect(status().isCreated())
                .andReturn();
        long clonedBySupId = objectMapper.readTree(clonedBySupRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(delete("/api/recipes/{id}", clonedBySupId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("4. Manufacturing Order endpoints allow reads for all 5 roles and restrict create/status updates to ADMIN & PRODUCTION_SUPERVISOR")
    void orderEndpointsEnforceRoleMatrix() throws Exception {
        long productId = createProductAsAdmin("ORD");
        long recipeId = createApprovedRecipeViaHttp(productId);
        JsonNode supOrder = createOrderAsSupervisor(productId, recipeId, new BigDecimal("100.0000"));
        long supOrderId = supOrder.get("id").asLong();
        String supOrderNumber = supOrder.get("orderNumber").asText();

        // All 5 roles can read orders and order child batches
        for (Role role : Role.values()) {
            mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, auth(role)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/orders/{id}", supOrderId).header(HttpHeaders.AUTHORIZATION, auth(role)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/orders/number/{num}", supOrderNumber).header(HttpHeaders.AUTHORIZATION, auth(role)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/orders/product/{pid}", productId).header(HttpHeaders.AUTHORIZATION, auth(role)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/orders/{id}/batches", supOrderId).header(HttpHeaders.AUTHORIZATION, auth(role)))
                    .andExpect(status().isOk());
        }

        LocalDate today = LocalDate.now(com.pharmatrack.util.AppTime.APP_ZONE_ID);
        OrderRequestDto orderPayload = new OrderRequestDto(
                productId, recipeId, new BigDecimal("100.0000"), "kg", today, today.plusDays(2), "Role test"
        );
        OrderStatusUpdateDto releasePayload = new OrderStatusUpdateDto(OrderStatus.RELEASED);
        long ordersBeforeDenied = orderRepository.count();

        // OPERATOR, QC_ANALYST, QA_REVIEWER get 403 on create & status transition
        for (Role denied : List.of(Role.OPERATOR, Role.QC_ANALYST, Role.QA_REVIEWER)) {
            mockMvc.perform(post("/api/orders")
                            .header(HttpHeaders.AUTHORIZATION, auth(denied))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(orderPayload)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/orders/{id}/status", supOrderId)
                            .header(HttpHeaders.AUTHORIZATION, auth(denied))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(releasePayload)))
                    .andExpect(status().isForbidden());
        }
        assertEquals(ordersBeforeDenied, orderRepository.count());

        // ADMIN can create & release; PRODUCTION_SUPERVISOR can release
        MvcResult adminOrderRes = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderPayload)))
                .andExpect(status().isCreated())
                .andReturn();
        long adminOrderId = objectMapper.readTree(adminOrderRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(patch("/api/orders/{id}/status", adminOrderId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(releasePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        mockMvc.perform(patch("/api/orders/{id}/status", supOrderId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(releasePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    @DisplayName("5. Batch status & Production Execution enforce target-status defense-in-depth and OPERATOR-only step execution")
    void batchAndProductionExecutionEnforceRoleMatrix() throws Exception {
        long productId = createProductAsAdmin("EXE");
        long recipeId = createApprovedRecipeViaHttp(productId);

        // Order A (PLANNED, 2 batches) for cancellation RBAC verification
        JsonNode orderA = createOrderAsSupervisor(productId, recipeId, new BigDecimal("200.0000"));
        long batchA1 = orderA.get("batches").get(0).get("id").asLong();
        long batchA2 = orderA.get("batches").get(1).get("id").asLong();

        // OPERATOR, QC_ANALYST, QA_REVIEWER cannot cancel a batch
        BatchStatusUpdateDto cancelDto = new BatchStatusUpdateDto(BatchStatus.CANCELLED);
        for (Role deniedCancel : List.of(Role.OPERATOR, Role.QC_ANALYST, Role.QA_REVIEWER)) {
            mockMvc.perform(patch("/api/batches/{id}/status", batchA1)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedCancel))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(cancelDto)))
                    .andExpect(status().isForbidden());
        }

        // PRODUCTION_SUPERVISOR and ADMIN can cancel batches while order is PLANNED
        mockMvc.perform(patch("/api/batches/{id}/status", batchA1)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Validation check: ADMIN sending null status gets 400 (not 403)
        mockMvc.perform(patch("/api/batches/{id}/status", batchA2)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":null}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/batches/{id}/status", batchA2)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Order B (RELEASED, 2 batches) for IN_PROGRESS, steps, and COMPLETED verification
        JsonNode orderB = createOrderAsSupervisor(productId, recipeId, new BigDecimal("200.0000"));
        long orderBId = orderB.get("id").asLong();
        long batchB1 = orderB.get("batches").get(0).get("id").asLong();
        String batchB1Number = orderB.get("batches").get(0).get("batchNumber").asText();
        long batchB2 = orderB.get("batches").get(1).get("id").asLong();

        mockMvc.perform(patch("/api/orders/{id}/status", orderBId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk());

        // ADMIN, QC_ANALYST, QA_REVIEWER get 403 for IN_PROGRESS and COMPLETED
        BatchStatusUpdateDto startBatchDto = new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS);
        BatchStatusUpdateDto completeBatchDto = new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("98.0000"));
        for (Role deniedStart : List.of(Role.ADMIN, Role.QC_ANALYST, Role.QA_REVIEWER)) {
            mockMvc.perform(patch("/api/batches/{id}/status", batchB1)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedStart))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(startBatchDto)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/batches/{id}/status", batchB1)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedStart))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(completeBatchDto)))
                    .andExpect(status().isForbidden());
        }

        // OPERATOR starts batch B1 (200)
        mockMvc.perform(patch("/api/batches/{id}/status", batchB1)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startBatchDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // Read endpoints on batches & execution allow all 5 roles
        for (Role reader : Role.values()) {
            mockMvc.perform(get("/api/batches/execution").header(HttpHeaders.AUTHORIZATION, auth(reader)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/batches/{id}", batchB1).header(HttpHeaders.AUTHORIZATION, auth(reader)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/batches/number/{num}", batchB1Number).header(HttpHeaders.AUTHORIZATION, auth(reader)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/batches/{id}/steps", batchB1).header(HttpHeaders.AUTHORIZATION, auth(reader)))
                    .andExpect(status().isOk());
        }

        MvcResult b1StepsRes = mockMvc.perform(get("/api/batches/{id}/steps", batchB1)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR)))
                .andExpect(status().isOk())
                .andReturn();
        long stepB1Id = objectMapper.readTree(b1StepsRes.getResponse().getContentAsString())
                .get("executionSteps").get(0).get("id").asLong();

        // Step initialize, start, complete: strictly OPERATOR only (even PRODUCTION_SUPERVISOR and ADMIN get 403!)
        CompleteExecutionStepRequestDto completeStepDto = new CompleteExecutionStepRequestDto("25 RPM", "Nominal");
        for (Role nonOperator : List.of(Role.ADMIN, Role.PRODUCTION_SUPERVISOR, Role.QC_ANALYST, Role.QA_REVIEWER)) {
            mockMvc.perform(post("/api/batches/{batchId}/steps/initialize", batchB1)
                            .header(HttpHeaders.AUTHORIZATION, auth(nonOperator)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/start", batchB1, stepB1Id)
                            .header(HttpHeaders.AUTHORIZATION, auth(nonOperator)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/complete", batchB1, stepB1Id)
                            .header(HttpHeaders.AUTHORIZATION, auth(nonOperator))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(completeStepDto)))
                    .andExpect(status().isForbidden());
        }

        // OPERATOR executes initialize, start, complete and completes B1
        mockMvc.perform(post("/api/batches/{batchId}/steps/initialize", batchB1)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/start", batchB1, stepB1Id)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/complete", batchB1, stepB1Id)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completeStepDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(patch("/api/batches/{id}/status", batchB1)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completeBatchDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // PRODUCTION_SUPERVISOR can start & complete batch B2 (after OPERATOR executes its step)
        mockMvc.perform(patch("/api/batches/{id}/status", batchB2)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startBatchDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        MvcResult b2StepsRes = mockMvc.perform(get("/api/batches/{id}/steps", batchB2)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR)))
                .andExpect(status().isOk())
                .andReturn();
        long stepB2Id = objectMapper.readTree(b2StepsRes.getResponse().getContentAsString())
                .get("executionSteps").get(0).get("id").asLong();

        mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/start", batchB2, stepB2Id)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/batches/{batchId}/steps/{stepId}/complete", batchB2, stepB2Id)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completeStepDto)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/batches/{id}/status", batchB2)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completeBatchDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("6. Quality & Audit Log endpoints isolate QC checks to QC_ANALYST, disposition to QA_REVIEWER, and audit queries to ADMIN/QA_REVIEWER")
    void qualityAndAuditLogEndpointsEnforceRoleMatrix() throws Exception {
        long batchId = createAndCompleteBatchViaHttp("QUAL");

        // Quality queue & dossier readable by all 5 roles
        for (Role role : Role.values()) {
            mockMvc.perform(get("/api/quality/batches").header(HttpHeaders.AUTHORIZATION, auth(role)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/quality/batches/{batchId}", batchId).header(HttpHeaders.AUTHORIZATION, auth(role)))
                    .andExpect(status().isOk());
        }

        // Audit logs strictly restricted to ADMIN and QA_REVIEWER
        for (Role allowedAudit : List.of(Role.ADMIN, Role.QA_REVIEWER)) {
            mockMvc.perform(get("/api/quality/audit-logs").header(HttpHeaders.AUTHORIZATION, auth(allowedAudit)))
                    .andExpect(status().isOk());
        }
        for (Role deniedAudit : List.of(Role.PRODUCTION_SUPERVISOR, Role.OPERATOR, Role.QC_ANALYST)) {
            mockMvc.perform(get("/api/quality/audit-logs").header(HttpHeaders.AUTHORIZATION, auth(deniedAudit)))
                    .andExpect(status().isForbidden());
        }

        // QC Check creation & correction strictly QC_ANALYST only
        QualityCheckRequestDto qcPayload = new QualityCheckRequestDto(
                "Assay (HPLC)", "95.0% - 105.0%", "99.1", "%",
                QualityCheckResult.PASS, true, null, "Initial assay"
        );
        long checksBefore = qualityCheckRepository.countByBatchId(batchId);
        for (Role nonQc : List.of(Role.ADMIN, Role.PRODUCTION_SUPERVISOR, Role.OPERATOR, Role.QA_REVIEWER)) {
            mockMvc.perform(post("/api/quality/batches/{batchId}/checks", batchId)
                            .header(HttpHeaders.AUTHORIZATION, auth(nonQc))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(qcPayload)))
                    .andExpect(status().isForbidden());
        }
        assertEquals(checksBefore, qualityCheckRepository.countByBatchId(batchId));

        MvcResult qcRes = mockMvc.perform(post("/api/quality/batches/{batchId}/checks", batchId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.QC_ANALYST))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(qcPayload)))
                .andExpect(status().isCreated())
                .andReturn();
        long checkId = objectMapper.readTree(qcRes.getResponse().getContentAsString()).get("id").asLong();

        QualityCheckCorrectionRequestDto correctionPayload = new QualityCheckCorrectionRequestDto(
                "Assay (HPLC)", "95.0% - 105.0%", "99.4", "%",
                QualityCheckResult.PASS, true, "Re-read chromatogram", "Transcription rounding adjustment"
        );
        for (Role nonQc : List.of(Role.ADMIN, Role.PRODUCTION_SUPERVISOR, Role.OPERATOR, Role.QA_REVIEWER)) {
            mockMvc.perform(post("/api/quality/checks/{checkId}/correct", checkId)
                            .header(HttpHeaders.AUTHORIZATION, auth(nonQc))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(correctionPayload)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/quality/checks/{checkId}/correct", checkId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.QC_ANALYST))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctionPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correctionCount").value(1));

        // QA Disposition strictly QA_REVIEWER only
        QaDispositionRequestDto releasePayload = new QaDispositionRequestDto(
                BatchQualityStatus.RELEASED, "All QC checks and eBR steps verified"
        );
        for (Role nonQa : List.of(Role.ADMIN, Role.PRODUCTION_SUPERVISOR, Role.OPERATOR, Role.QC_ANALYST)) {
            mockMvc.perform(post("/api/quality/batches/{batchId}/disposition", batchId)
                            .header(HttpHeaders.AUTHORIZATION, auth(nonQa))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(releasePayload)))
                    .andExpect(status().isForbidden());
        }

        mockMvc.perform(post("/api/quality/batches/{batchId}/disposition", batchId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.QA_REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(releasePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("RELEASED"));
    }

    @Test
    @DisplayName("7. Deviation & CAPA endpoints enforce exact RBAC permissions across reporting, investigation, CAPA creation/execution, and QA closure gates")
    void deviationAndCapaEndpointsEnforceRoleMatrix() throws Exception {
        long batchId = createAndCompleteBatchViaHttp("DEVC");

        CreateDeviationRequestDto devReq = new CreateDeviationRequestDto(
                batchId, null, null, null,
                "Granulation moisture excursion",
                DeviationCategory.PROCESS_PARAMETER, DeviationSeverity.MAJOR, DeviationPriority.HIGH,
                "Moisture analyzer spiked to 4.1%", "Segregated Lot 1", null
        );

        // ADMIN gets 403 when reporting a deviation
        long devsBefore = deviationRepository.countByBatchId(batchId);
        mockMvc.perform(post("/api/quality/deviations")
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(devReq)))
                .andExpect(status().isForbidden());
        assertEquals(devsBefore, deviationRepository.countByBatchId(batchId));

        // OPERATOR, QC_ANALYST, PRODUCTION_SUPERVISOR, QA_REVIEWER can report deviations
        long dev1Id = postAndGetId("/api/quality/deviations", Role.OPERATOR, devReq);
        long dev2Id = postAndGetId("/api/quality/deviations", Role.QC_ANALYST, devReq);
        long dev3Id = postAndGetId("/api/quality/deviations", Role.PRODUCTION_SUPERVISOR, devReq);
        postAndGetId("/api/quality/deviations", Role.QA_REVIEWER, devReq);

        // Read endpoints allow all 5 roles (including ADMIN)
        for (Role role : Role.values()) {
            mockMvc.perform(get("/api/quality/deviations").header(HttpHeaders.AUTHORIZATION, auth(role)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/quality/deviations/{id}", dev1Id).header(HttpHeaders.AUTHORIZATION, auth(role)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/quality/batches/{batchId}/deviations", batchId).header(HttpHeaders.AUTHORIZATION, auth(role)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/quality/batches/{batchId}/linkable-records", batchId).header(HttpHeaders.AUTHORIZATION, auth(role)))
                    .andExpect(status().isOk());
        }

        // start-investigation: ADMIN & OPERATOR get 403; SUP, QC, QA succeed (200)
        StartInvestigationRequestDto invDto = new StartInvestigationRequestDto("Investigating dryer exhaust probe");
        for (Role deniedInv : List.of(Role.ADMIN, Role.OPERATOR)) {
            mockMvc.perform(post("/api/quality/deviations/{id}/start-investigation", dev1Id)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedInv))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invDto)))
                    .andExpect(status().isForbidden());
        }
        postExpectOk("/api/quality/deviations/{id}/start-investigation", dev1Id, Role.PRODUCTION_SUPERVISOR, invDto);
        postExpectOk("/api/quality/deviations/{id}/start-investigation", dev2Id, Role.QC_ANALYST, invDto);
        postExpectOk("/api/quality/deviations/{id}/start-investigation", dev3Id, Role.QA_REVIEWER, invDto);

        // record-root-cause: ADMIN & OPERATOR get 403; SUP, QC, QA succeed (200)
        RecordRootCauseRequestDto rcaDto = new RecordRootCauseRequestDto(
                "Sensor Drift", "Probe calibration drifted by +0.8%", "Contained to sublot", null
        );
        for (Role deniedRca : List.of(Role.ADMIN, Role.OPERATOR)) {
            mockMvc.perform(post("/api/quality/deviations/{id}/record-root-cause", dev1Id)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedRca))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rcaDto)))
                    .andExpect(status().isForbidden());
        }
        postExpectOk("/api/quality/deviations/{id}/record-root-cause", dev1Id, Role.PRODUCTION_SUPERVISOR, rcaDto);
        postExpectOk("/api/quality/deviations/{id}/record-root-cause", dev2Id, Role.QC_ANALYST, rcaDto);
        postExpectOk("/api/quality/deviations/{id}/record-root-cause", dev3Id, Role.QA_REVIEWER, rcaDto);

        // create CAPA: ADMIN, OPERATOR, QC_ANALYST get 403; PRODUCTION_SUPERVISOR & QA_REVIEWER get 201
        CreateCapaActionRequestDto capaReq = new CreateCapaActionRequestDto(
                CapaType.CORRECTIVE, "Recalibrate exhaust probe", "Perform 3-point calibration check",
                "Eng. Kumar", LocalDate.now(com.pharmatrack.util.AppTime.APP_ZONE_ID).plusDays(7)
        );
        long capasBefore = capaActionRepository.count();
        for (Role deniedCreateCapa : List.of(Role.ADMIN, Role.OPERATOR, Role.QC_ANALYST)) {
            mockMvc.perform(post("/api/quality/deviations/{id}/capas", dev1Id)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedCreateCapa))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(capaReq)))
                    .andExpect(status().isForbidden());
        }
        assertEquals(capasBefore, capaActionRepository.count());

        long capa1Id = postAndGetId("/api/quality/deviations/" + dev1Id + "/capas", Role.PRODUCTION_SUPERVISOR, capaReq);
        long capa1ExtraId = postAndGetId("/api/quality/deviations/" + dev1Id + "/capas", Role.QA_REVIEWER, capaReq);
        long capa2Id = postAndGetId("/api/quality/deviations/" + dev2Id + "/capas", Role.QA_REVIEWER, capaReq);
        long capa3Id = postAndGetId("/api/quality/deviations/" + dev3Id + "/capas", Role.PRODUCTION_SUPERVISOR, capaReq);

        // start-capa (deviation transition): strictly QA_REVIEWER only
        StartCapaRequestDto startCapaDto = new StartCapaRequestDto("Approved CAPA execution");
        for (Role deniedStartCapa : List.of(Role.ADMIN, Role.PRODUCTION_SUPERVISOR, Role.OPERATOR, Role.QC_ANALYST)) {
            mockMvc.perform(post("/api/quality/deviations/{id}/start-capa", dev1Id)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedStartCapa))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(startCapaDto)))
                    .andExpect(status().isForbidden());
        }
        postExpectOk("/api/quality/deviations/{id}/start-capa", dev1Id, Role.QA_REVIEWER, startCapaDto);
        postExpectOk("/api/quality/deviations/{id}/start-capa", dev2Id, Role.QA_REVIEWER, startCapaDto);
        postExpectOk("/api/quality/deviations/{id}/start-capa", dev3Id, Role.QA_REVIEWER, startCapaDto);

        // CAPA start & complete: ADMIN & OPERATOR get 403; SUP, QC, QA succeed (200)
        StartCapaActionRequestDto startActionDto = new StartCapaActionRequestDto("Calibration work order opened");
        CompleteCapaActionRequestDto completeActionDto = new CompleteCapaActionRequestDto("Cert CAL-881 attached");
        for (Role deniedCapaExec : List.of(Role.ADMIN, Role.OPERATOR)) {
            mockMvc.perform(post("/api/quality/capas/{capaId}/start", capa1Id)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedCapaExec))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(startActionDto)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/quality/capas/{capaId}/complete", capa1Id)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedCapaExec))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(completeActionDto)))
                    .andExpect(status().isForbidden());
        }
        postExpectOk("/api/quality/capas/{capaId}/start", capa1Id, Role.PRODUCTION_SUPERVISOR, startActionDto);
        postExpectOk("/api/quality/capas/{capaId}/start", capa2Id, Role.QC_ANALYST, startActionDto);
        postExpectOk("/api/quality/capas/{capaId}/start", capa3Id, Role.QA_REVIEWER, startActionDto);

        postExpectOk("/api/quality/capas/{capaId}/complete", capa1Id, Role.PRODUCTION_SUPERVISOR, completeActionDto);
        postExpectOk("/api/quality/capas/{capaId}/complete", capa2Id, Role.QC_ANALYST, completeActionDto);
        postExpectOk("/api/quality/capas/{capaId}/complete", capa3Id, Role.QA_REVIEWER, completeActionDto);

        // cancel CAPA action: strictly QA_REVIEWER only
        CancelCapaActionRequestDto cancelCapaDto = new CancelCapaActionRequestDto("Duplicate CAPA entry");
        for (Role deniedCancelCapa : List.of(Role.ADMIN, Role.PRODUCTION_SUPERVISOR, Role.OPERATOR, Role.QC_ANALYST)) {
            mockMvc.perform(post("/api/quality/capas/{capaId}/cancel", capa1ExtraId)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedCancelCapa))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(cancelCapaDto)))
                    .andExpect(status().isForbidden());
        }
        postExpectOk("/api/quality/capas/{capaId}/cancel", capa1ExtraId, Role.QA_REVIEWER, cancelCapaDto);

        // submit-verification: ADMIN & OPERATOR get 403; SUP, QC, QA succeed (200)
        SubmitVerificationRequestDto submitVerDto = new SubmitVerificationRequestDto("All CAPAs completed");
        for (Role deniedSubmitVer : List.of(Role.ADMIN, Role.OPERATOR)) {
            mockMvc.perform(post("/api/quality/deviations/{id}/submit-verification", dev1Id)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedSubmitVer))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(submitVerDto)))
                    .andExpect(status().isForbidden());
        }
        postExpectOk("/api/quality/deviations/{id}/submit-verification", dev1Id, Role.PRODUCTION_SUPERVISOR, submitVerDto);
        postExpectOk("/api/quality/deviations/{id}/submit-verification", dev2Id, Role.QC_ANALYST, submitVerDto);
        postExpectOk("/api/quality/deviations/{id}/submit-verification", dev3Id, Role.QA_REVIEWER, submitVerDto);

        // reopen-capa & close: strictly QA_REVIEWER only
        ReopenCapaRequestDto reopenDto = new ReopenCapaRequestDto("Require SOP revision proof");
        CloseDeviationRequestDto closeDto = new CloseDeviationRequestDto("Effectiveness verified and closed");
        for (Role deniedQaGate : List.of(Role.ADMIN, Role.PRODUCTION_SUPERVISOR, Role.OPERATOR, Role.QC_ANALYST)) {
            mockMvc.perform(post("/api/quality/deviations/{id}/reopen-capa", dev1Id)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedQaGate))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reopenDto)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/quality/deviations/{id}/close", dev1Id)
                            .header(HttpHeaders.AUTHORIZATION, auth(deniedQaGate))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(closeDto)))
                    .andExpect(status().isForbidden());
        }

        // QA_REVIEWER reopens dev1, resubmits verification, and closes dev1
        postExpectOk("/api/quality/deviations/{id}/reopen-capa", dev1Id, Role.QA_REVIEWER, reopenDto);
        postExpectOk("/api/quality/deviations/{id}/submit-verification", dev1Id, Role.PRODUCTION_SUPERVISOR, submitVerDto);
        mockMvc.perform(post("/api/quality/deviations/{id}/close", dev1Id)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.QA_REVIEWER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(closeDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    private long postAndGetId(String uri, Role role, Object payload) throws Exception {
        MvcResult res = mockMvc.perform(post(uri)
                        .header(HttpHeaders.AUTHORIZATION, auth(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private void postExpectOk(String uriTemplate, long id, Role role, Object payload) throws Exception {
        mockMvc.perform(post(uriTemplate, id)
                        .header(HttpHeaders.AUTHORIZATION, auth(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("8. CLOSED deviation enforces @PreAuthorize 403 precedence for unauthorized roles and 400 immutability for permitted roles across all 5 roles with zero DB/audit mutations")
    void closedDeviationEnforcesRolePrecedenceAndImmutabilityAcrossAllFiveRoles() throws Exception {
        long batchId = createAndCompleteBatchViaHttp("CLSD");

        CreateDeviationRequestDto devReq = new CreateDeviationRequestDto(
                batchId, null, null, null,
                "Terminal lock RBAC verification",
                DeviationCategory.PROCESS_PARAMETER, DeviationSeverity.MAJOR, DeviationPriority.HIGH,
                "Moisture spike", "Quarantined batch", null
        );
        long devId = postAndGetId("/api/quality/deviations", Role.OPERATOR, devReq);
        postExpectOk("/api/quality/deviations/{id}/start-investigation", devId, Role.QC_ANALYST,
                new StartInvestigationRequestDto("Reviewed logs"));
        postExpectOk("/api/quality/deviations/{id}/record-root-cause", devId, Role.QC_ANALYST,
                new RecordRootCauseRequestDto("Sensor Drift", "Drifted", "Contained", null));

        CreateCapaActionRequestDto capaReq = new CreateCapaActionRequestDto(
                CapaType.CORRECTIVE, "Recalibrate probe", "Run 3-point calibration",
                "Eng. Kumar", LocalDate.now(com.pharmatrack.util.AppTime.APP_ZONE_ID).plusDays(5)
        );
        long capaId = postAndGetId("/api/quality/deviations/" + devId + "/capas", Role.PRODUCTION_SUPERVISOR, capaReq);

        postExpectOk("/api/quality/deviations/{id}/start-capa", devId, Role.QA_REVIEWER,
                new StartCapaRequestDto("Approved CAPA"));
        postExpectOk("/api/quality/capas/{capaId}/start", capaId, Role.QC_ANALYST,
                new StartCapaActionRequestDto("Started work"));
        postExpectOk("/api/quality/capas/{capaId}/complete", capaId, Role.QC_ANALYST,
                new CompleteCapaActionRequestDto("Calibration cert CAL-900"));
        postExpectOk("/api/quality/deviations/{id}/submit-verification", devId, Role.QC_ANALYST,
                new SubmitVerificationRequestDto("Ready for QA verification"));
        postExpectOk("/api/quality/deviations/{id}/close", devId, Role.QA_REVIEWER,
                new CloseDeviationRequestDto("Effectiveness verified; closed"));

        long deviationsBefore = deviationRepository.count();
        long capasBefore = capaActionRepository.count();
        long auditsBefore = auditLogRepository.count();
        assertEquals(DeviationStatus.CLOSED, deviationRepository.findById(devId).orElseThrow().getStatus());

        StartInvestigationRequestDto invDto = new StartInvestigationRequestDto("Probe closed dev");
        RecordRootCauseRequestDto rcaDto = new RecordRootCauseRequestDto("Cat", "Analysis", "Impact", null);
        StartCapaRequestDto startCapaDto = new StartCapaRequestDto("Plan");
        StartCapaActionRequestDto startActionDto = new StartCapaActionRequestDto("Note");
        CompleteCapaActionRequestDto completeActionDto = new CompleteCapaActionRequestDto("Evidence");
        CancelCapaActionRequestDto cancelActionDto = new CancelCapaActionRequestDto("Reason");
        SubmitVerificationRequestDto submitVerDto = new SubmitVerificationRequestDto("Submit");
        ReopenCapaRequestDto reopenDto = new ReopenCapaRequestDto("Reopen");
        CloseDeviationRequestDto closeDto = new CloseDeviationRequestDto("Close again");

        for (Role role : Role.values()) {
            boolean canInvestigateOrRcaOrExecCapaOrSubmit =
                    role == Role.PRODUCTION_SUPERVISOR || role == Role.QC_ANALYST || role == Role.QA_REVIEWER;
            boolean canCreateCapa = role == Role.PRODUCTION_SUPERVISOR || role == Role.QA_REVIEWER;
            boolean isQa = role == Role.QA_REVIEWER;

            expectClosedRoleOrImmute("/api/quality/deviations/" + devId + "/start-investigation", role, invDto, canInvestigateOrRcaOrExecCapaOrSubmit);
            expectClosedRoleOrImmute("/api/quality/deviations/" + devId + "/record-root-cause", role, rcaDto, canInvestigateOrRcaOrExecCapaOrSubmit);
            expectClosedRoleOrImmute("/api/quality/deviations/" + devId + "/capas", role, capaReq, canCreateCapa);
            expectClosedRoleOrImmute("/api/quality/deviations/" + devId + "/start-capa", role, startCapaDto, isQa);
            expectClosedRoleOrImmute("/api/quality/capas/" + capaId + "/start", role, startActionDto, canInvestigateOrRcaOrExecCapaOrSubmit);
            expectClosedRoleOrImmute("/api/quality/capas/" + capaId + "/complete", role, completeActionDto, canInvestigateOrRcaOrExecCapaOrSubmit);
            expectClosedRoleOrImmute("/api/quality/capas/" + capaId + "/cancel", role, cancelActionDto, isQa);
            expectClosedRoleOrImmute("/api/quality/deviations/" + devId + "/submit-verification", role, submitVerDto, canInvestigateOrRcaOrExecCapaOrSubmit);
            expectClosedRoleOrImmute("/api/quality/deviations/" + devId + "/reopen-capa", role, reopenDto, isQa);
            expectClosedRoleOrImmute("/api/quality/deviations/" + devId + "/close", role, closeDto, isQa);
        }

        assertEquals(deviationsBefore, deviationRepository.count());
        assertEquals(capasBefore, capaActionRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());
        assertEquals(DeviationStatus.CLOSED, deviationRepository.findById(devId).orElseThrow().getStatus());
    }

    private void expectClosedRoleOrImmute(String uri, Role role, Object payload, boolean rolePermitted) throws Exception {
        var result = mockMvc.perform(post(uri)
                        .header(HttpHeaders.AUTHORIZATION, auth(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)));
        if (rolePermitted) {
            result.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("CLOSED and cannot be modified")));
        } else {
            result.andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    @Test
    @DisplayName("9. Method tampering probes, non-ADMIN user probes, and unauthorized batch transitions fail closed without DB or audit writes")
    void httpMethodProbesAndFullStackBatchAndAdminGuardsFailClosedWithoutMutation() throws Exception {
        long completedBatchId = createAndCompleteBatchViaHttp("PRB");
        MvcResult qcRes = mockMvc.perform(post("/api/quality/batches/{batchId}/checks", completedBatchId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.QC_ANALYST))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QualityCheckRequestDto(
                                "Water Content", "< 2.0%", "1.4", "%",
                                QualityCheckResult.PASS, true, null, "Nominal"))))
                .andExpect(status().isCreated())
                .andReturn();
        long checkId = objectMapper.readTree(qcRes.getResponse().getContentAsString()).get("id").asLong();

        long devId = postAndGetId("/api/quality/deviations", Role.OPERATOR, new CreateDeviationRequestDto(
                completedBatchId, null, null, null,
                "Method probe deviation",
                DeviationCategory.PROCESS_PARAMETER, DeviationSeverity.MINOR, DeviationPriority.LOW,
                "Minor excursion", "Noted", null
        ));
        postExpectOk("/api/quality/deviations/{id}/start-investigation", devId, Role.QC_ANALYST,
                new StartInvestigationRequestDto("Investigated"));
        postExpectOk("/api/quality/deviations/{id}/record-root-cause", devId, Role.QC_ANALYST,
                new RecordRootCauseRequestDto("Cat", "Analysis", "Low impact", null));
        long capaId = postAndGetId("/api/quality/deviations/" + devId + "/capas", Role.PRODUCTION_SUPERVISOR,
                new CreateCapaActionRequestDto(
                        CapaType.CORRECTIVE, "Inspect gauge", "Verify calibration",
                        "Maintenance", LocalDate.now(com.pharmatrack.util.AppTime.APP_ZONE_ID).plusDays(5)
                ));

        // Additional RELEASED order with a PLANNED batch for batch transition RBAC verification
        long productId = createProductAsAdmin("BTPRB");
        long recipeId = createApprovedRecipeViaHttp(productId);
        JsonNode orderNode = createOrderAsSupervisor(productId, recipeId, new BigDecimal("100.0000"));
        long orderId = orderNode.get("id").asLong();
        long plannedBatchId = orderNode.get("batches").get(0).get("id").asLong();
        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.PRODUCTION_SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isOk());

        long auditsBefore = auditLogRepository.count();
        long usersBefore = userAccountRepository.count();

        // 1. PUT / PATCH / DELETE method probes on append-only audit logs, QC checks, deviations, CAPAs
        for (Role role : Role.values()) {
            boolean auditAllowedRole = role == Role.ADMIN || role == Role.QA_REVIEWER;
            boolean checkAllowedRole = role == Role.QC_ANALYST;
            boolean qaOnlyRole = role == Role.QA_REVIEWER;

            for (var builder : List.of(
                    put("/api/quality/audit-logs"),
                    patch("/api/quality/audit-logs"),
                    delete("/api/quality/audit-logs"))) {
                mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, auth(role))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(auditAllowedRole ? status().isMethodNotAllowed() : status().isForbidden());
            }

            for (var builder : List.of(
                    put("/api/quality/checks/{id}", checkId),
                    patch("/api/quality/checks/{id}", checkId),
                    delete("/api/quality/checks/{id}", checkId))) {
                mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, auth(role))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(checkAllowedRole ? status().isNotFound() : status().isForbidden());
            }

            for (var builder : List.of(
                    put("/api/quality/deviations/{id}", devId),
                    patch("/api/quality/deviations/{id}", devId),
                    delete("/api/quality/deviations/{id}", devId))) {
                mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, auth(role))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(qaOnlyRole ? status().isMethodNotAllowed() : status().isForbidden());
            }

            for (var builder : List.of(
                    put("/api/quality/capas/{id}", capaId),
                    patch("/api/quality/capas/{id}", capaId),
                    delete("/api/quality/capas/{id}", capaId))) {
                mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, auth(role))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(qaOnlyRole ? status().isNotFound() : status().isForbidden());
            }

            // Unmapped sub-route HTTP methods hit SecurityConfig anyRequest().denyAll() -> 403 for all roles
            for (var builder : List.of(
                    put("/api/quality/checks/{id}/correct", checkId),
                    delete("/api/quality/checks/{id}/correct", checkId),
                    put("/api/quality/deviations/{id}/close", devId),
                    delete("/api/quality/deviations/{id}/close", devId))) {
                mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, auth(role))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isForbidden());
            }
        }

        // 2. Non-ADMIN roles blocked from /api/admin/users/**
        for (Role nonAdmin : List.of(Role.PRODUCTION_SUPERVISOR, Role.OPERATOR, Role.QC_ANALYST, Role.QA_REVIEWER)) {
            mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, auth(nonAdmin)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/admin/users")
                            .header(HttpHeaders.AUTHORIZATION, auth(nonAdmin))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"rogue\",\"password\":\"RoguePass9!\",\"fullName\":\"Rogue\",\"role\":\"ADMIN\"}"))
                    .andExpect(status().isForbidden());
        }

        // 3. Batch status transition target-status denial without state or audit mutation
        mockMvc.perform(patch("/api/batches/{id}/status", plannedBatchId)
                        .header(HttpHeaders.AUTHORIZATION, auth(Role.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.CANCELLED))))
                .andExpect(status().isForbidden());

        for (Role noStartRole : List.of(Role.ADMIN, Role.QC_ANALYST, Role.QA_REVIEWER)) {
            mockMvc.perform(patch("/api/batches/{id}/status", plannedBatchId)
                            .header(HttpHeaders.AUTHORIZATION, auth(noStartRole))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/batches/{id}/status", plannedBatchId)
                            .header(HttpHeaders.AUTHORIZATION, auth(noStartRole))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("100.0000")))))
                    .andExpect(status().isForbidden());
        }

        assertEquals(BatchStatus.PLANNED, batchRepository.findById(plannedBatchId).orElseThrow().getStatus());
        assertEquals(usersBefore, userAccountRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());
    }
}
