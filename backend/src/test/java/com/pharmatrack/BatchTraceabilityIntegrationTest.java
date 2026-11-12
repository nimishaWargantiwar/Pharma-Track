package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.LoginRequestDto;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import com.pharmatrack.repository.ProductRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BatchTraceabilityIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(100);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private ManufacturingOrderRepository orderRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String TEST_PASSWORD = "ValidTestPassword123!";

    @BeforeEach
    void setUpData() {
        ensureUserExists("trace_admin", Role.ADMIN);
        ensureUserExists("trace_supervisor", Role.PRODUCTION_SUPERVISOR);
        ensureUserExists("trace_operator", Role.OPERATOR);
        ensureUserExists("trace_qc", Role.QC_ANALYST);
        ensureUserExists("trace_qa", Role.QA_REVIEWER);
        ensureBatchExists();
    }

    private void ensureUserExists(String username, Role role) {
        if (!userAccountRepository.existsByUsernameIgnoreCase(username)) {
            UserAccount user = new UserAccount(
                    username,
                    passwordEncoder.encode(TEST_PASSWORD),
                    role.name() + " Test User",
                    role,
                    true
            );
            userAccountRepository.save(user);
        }
    }

    private Batch ensureBatchExists() {
        List<Batch> batches = batchRepository.findAll();
        if (!batches.isEmpty()) {
            return batches.get(0);
        }

        int n = SEQ.incrementAndGet();
        Product product = new Product(
                "PRD-TRACE-" + n,
                "Traceability Test Product " + n,
                "500 mg",
                "Tablet",
                "Traceability testing formulation",
                ProductStatus.ACTIVE
        );
        product = productRepository.save(product);

        Recipe recipe = new Recipe(
                "RCP-TRACE-" + n,
                product,
                1,
                new BigDecimal("100.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Traceability test recipe"
        );
        recipe = recipeRepository.save(recipe);

        LocalDate today = LocalDate.now();
        ManufacturingOrder order = new ManufacturingOrder(
                "MO-TRACE-" + n,
                product,
                recipe,
                1,
                new BigDecimal("100.0000"),
                "kg",
                1,
                OrderStatus.RELEASED,
                today,
                today.plusDays(7),
                "Traceability test order"
        );
        order = orderRepository.save(order);

        Batch batch = new Batch(
                "BAT-TRACE-" + n + "-01",
                order,
                new BigDecimal("100.0000"),
                "kg",
                BatchStatus.PLANNED,
                today,
                today.plusYears(2)
        );
        batch.setQrCode("QR-PT-BAT-TRACE-" + n + "-01");
        return batchRepository.save(batch);
    }

    private String authenticate(String username) throws Exception {
        LoginRequestDto loginDto = new LoginRequestDto();
        loginDto.setUsername(username);
        loginDto.setPassword(TEST_PASSWORD);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDto)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        return responseNode.get("accessToken").asText();
    }

    @Test
    @DisplayName("QR Generation returns stable QR code, image payload, and preserves batch records")
    void testQrGenerationStableAndPreservesBatch() throws Exception {
        String token = authenticate("trace_supervisor");

        Batch testBatch = ensureBatchExists();
        Long batchId = testBatch.getId();
        BatchStatus initialStatus = testBatch.getStatus();

        // 1. First fetch
        MvcResult result1 = mockMvc.perform(get("/api/batches/" + batchId + "/qr")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.batchNumber").value(testBatch.getBatchNumber()))
                .andExpect(jsonPath("$.qrCode").isNotEmpty())
                .andExpect(jsonPath("$.qrImageBase64").value(org.hamcrest.Matchers.startsWith("data:image/png;base64,")))
                .andExpect(jsonPath("$.dossierUrl").isNotEmpty())
                .andReturn();

        JsonNode node1 = objectMapper.readTree(result1.getResponse().getContentAsString());
        String qrCode1 = node1.get("qrCode").asText();

        // 2. Second fetch - verify idempotency & stability
        MvcResult result2 = mockMvc.perform(get("/api/batches/" + batchId + "/qr")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node2 = objectMapper.readTree(result2.getResponse().getContentAsString());
        String qrCode2 = node2.get("qrCode").asText();
        assertThat(qrCode1).isEqualTo(qrCode2);

        // 3. Verify batch status was completely untouched
        Batch reloaded = batchRepository.findById(batchId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(initialStatus);
    }

    @Test
    @DisplayName("QR Lookup resolves by QR code identifier or batch number")
    void testQrLookupByIdentifier() throws Exception {
        String token = authenticate("trace_operator");

        Batch testBatch = ensureBatchExists();
        String batchNumber = testBatch.getBatchNumber();
        String qrCode = testBatch.getQrCode() != null ? testBatch.getQrCode() : "QR-PT-" + batchNumber;

        // Lookup by QR code string
        mockMvc.perform(get("/api/batches/qr/" + qrCode)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNumber").value(batchNumber))
                .andExpect(jsonPath("$.qrCode").value(qrCode));

        // Lookup by Batch Number
        mockMvc.perform(get("/api/batches/qr/" + batchNumber)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNumber").value(batchNumber));

        // Lookup nonexistent returns 404
        mockMvc.perform(get("/api/batches/qr/NONEXISTENT-BATCH-12345")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Batch Traceability Dossier returns all sections (A through H) with completeness metrics")
    void testTraceabilityDossierRetrieval() throws Exception {
        String token = authenticate("trace_qc");

        Batch testBatch = ensureBatchExists();
        Long batchId = testBatch.getId();

        // Fetch dossier by ID
        mockMvc.perform(get("/api/batches/" + batchId + "/traceability")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.batchNumber").value(testBatch.getBatchNumber()))
                .andExpect(jsonPath("$.qrCode").isNotEmpty())
                .andExpect(jsonPath("$.orderNumber").isNotEmpty())
                .andExpect(jsonPath("$.productName").isNotEmpty())
                .andExpect(jsonPath("$.completenessPercent").isNumber())
                .andExpect(jsonPath("$.completenessStatus").isNotEmpty())
                .andExpect(jsonPath("$.completenessHighlights").isArray())
                .andExpect(jsonPath("$.materialRequirements").isArray())
                .andExpect(jsonPath("$.materialMovements").isArray())
                .andExpect(jsonPath("$.executionSteps").isArray())
                .andExpect(jsonPath("$.qualityChecks").isArray())
                .andExpect(jsonPath("$.qaReviews").isArray())
                .andExpect(jsonPath("$.deviations").isArray())
                .andExpect(jsonPath("$.auditHistory").isArray());

        // Fetch dossier by Identifier (batch number)
        mockMvc.perform(get("/api/batches/traceability/" + testBatch.getBatchNumber())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.batchNumber").value(testBatch.getBatchNumber()));
    }

    @Test
    @DisplayName("Batch Traceability Dossier is accessible to all 5 roles and rejects unauthenticated")
    void testTraceabilityRbacAllRoles() throws Exception {
        Batch testBatch = ensureBatchExists();
        Long batchId = testBatch.getId();

        // 1. Unauthenticated rejects with 401
        mockMvc.perform(get("/api/batches/" + batchId + "/traceability"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/batches/" + batchId + "/qr"))
                .andExpect(status().isUnauthorized());

        // 2. All 5 roles are permitted
        String[] users = {"trace_admin", "trace_supervisor", "trace_operator", "trace_qc", "trace_qa"};
        for (String user : users) {
            String token = authenticate(user);

            mockMvc.perform(get("/api/batches/" + batchId + "/traceability")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.batchId").value(batchId));

            mockMvc.perform(get("/api/batches/" + batchId + "/qr")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.batchId").value(batchId));
        }
    }
}
