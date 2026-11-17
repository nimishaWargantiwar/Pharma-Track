package com.pharmatrack.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.client.LlmClient;
import com.pharmatrack.ai.client.LlmClientException;
import com.pharmatrack.ai.client.LlmResponse;
import com.pharmatrack.ai.service.AiCopilotService;
import com.pharmatrack.ai.service.BatchContextBuilder;
import com.pharmatrack.dto.CopilotConversationCreateRequestDto;
import com.pharmatrack.dto.CopilotSubmissionRequestDto;
import com.pharmatrack.entity.*;
import com.pharmatrack.repository.*;
import com.pharmatrack.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_ai_batch_it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        "app.ai.enabled=true",
        "app.ai.api-key=sk-test-mock-key",
        "app.ai.model=gpt-6-luna"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("PharmaTrack AI Batch Analysis Report - Stage 2A Integration Suite")
class AiBatchAnalysisIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(100);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private CopilotConversationRepository conversationRepository;

    @Autowired
    private CopilotRequestRepository requestRepository;

    @Autowired
    private CopilotMessageRepository messageRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private ManufacturingOrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private BatchExecutionStepRepository executionStepRepository;

    @Autowired
    private QualityCheckRepository qualityCheckRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private LlmClient llmClient;

    private UserAccount supervisorUser;
    private UserAccount operatorUser;
    private UserAccount adminUser;
    private UserAccount qcUser;
    private UserAccount qaUser;

    private Batch testBatch;
    private ManufacturingOrder testOrder;

    @BeforeEach
    void setUp() {
        // Seed users
        supervisorUser = createTestUser("sup", Role.PRODUCTION_SUPERVISOR);
        operatorUser = createTestUser("op", Role.OPERATOR);
        adminUser = createTestUser("adm", Role.ADMIN);
        qcUser = createTestUser("qc", Role.QC_ANALYST);
        qaUser = createTestUser("qa", Role.QA_REVIEWER);

        // Seed product, recipe, order, batch
        int n = SEQ.incrementAndGet();
        Product product = productRepository.save(new Product(
                "PRD-TEST-" + n, "Test Amoxicillin " + n, "500mg", "Capsule", "Desc", ProductStatus.ACTIVE
        ));

        Recipe recipe = new Recipe(
                "RCP-TEST-" + n, product, 1, new BigDecimal("100.0000"), "kg", RecipeStatus.APPROVED, "Recipe"
        );
        RecipeStep recipeStep = new RecipeStep(1, "Dispensing", "Dispense materials");
        recipe.addStep(recipeStep);
        recipe = recipeRepository.save(recipe);
        RecipeStep persistedRecipeStep = recipe.getSteps().get(0);

        LocalDate today = LocalDate.now();
        testOrder = orderRepository.save(new ManufacturingOrder(
                "MO-2026-" + String.format("%04d", n),
                product,
                recipe,
                1,
                new BigDecimal("100.0000"),
                "kg",
                1,
                OrderStatus.IN_PROGRESS,
                today,
                today.plusDays(5),
                "Batch Analysis Test Order"
        ));

        testBatch = new Batch(
                "BAT-2026-" + String.format("%04d", n) + "-01",
                testOrder,
                new BigDecimal("100.0000"),
                "kg",
                BatchStatus.COMPLETED,
                today,
                today.plusYears(2)
        );
        testBatch.setActualYield(new BigDecimal("98.5000"));
        testBatch.setQaStatus(BatchQualityStatus.PENDING_QA);
        testBatch.setStartTime(LocalDateTime.of(2026, 3, 1, 8, 30));
        testBatch.setEndTime(LocalDateTime.of(2026, 3, 1, 14, 45));
        testBatch.setQrCode("QR-PT-" + testBatch.getBatchNumber());
        testBatch = batchRepository.saveAndFlush(testBatch);

        // Seed an execution step
        BatchExecutionStep step = new BatchExecutionStep(
                testBatch, persistedRecipeStep, 1, "Dispensing", "Dispense materials", ExecutionStepStatus.COMPLETED
        );
        step.setActualValue("50.0 kg");
        step.setExecutedBy("op_john");
        step.setStartedAt(LocalDateTime.of(2026, 3, 1, 9, 0));
        step.setCompletedAt(LocalDateTime.of(2026, 3, 1, 9, 45));
        step.setObservations("Clean dispensing");
        executionStepRepository.saveAndFlush(step);

        // Seed a QC check
        QualityCheck qc = new QualityCheck(
                testBatch, "Assay", "98.0% - 102.0%", "99.2%", "%", QualityCheckResult.PASS,
                true, "qc_analyst1", LocalDateTime.of(2026, 3, 1, 15, 0), "Good assay"
        );
        qualityCheckRepository.saveAndFlush(qc);

        // Default LLM mock answer
        when(llmClient.generate(anyString(), anyString())).thenReturn(
                new LlmResponse("### AI Batch Analysis Report\n**Batch**: " + testBatch.getBatchNumber() + "\nYield: 98.50%", "test-model", "resp-1", 100, 50, 150)
        );
    }

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
        requestRepository.deleteAll();
        conversationRepository.deleteAll();
        qualityCheckRepository.deleteAll();
        executionStepRepository.deleteAll();
        batchRepository.deleteAll();
        orderRepository.deleteAll();
        recipeRepository.deleteAll();
        productRepository.deleteAll();
        userAccountRepository.deleteAll();
    }

    private UserAccount createTestUser(String prefix, Role role) {
        int n = SEQ.incrementAndGet();
        UserAccount u = new UserAccount(
                prefix + "_" + n,
                passwordEncoder.encode("SecretPass123!"),
                "Test " + prefix,
                role,
                true
        );
        return userAccountRepository.saveAndFlush(u);
    }

    private String bearerToken(UserAccount user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    // =========================================================================
    // 1-4: Valid Batch Request, Dossier Retrieval, Prompt Grounding, Source References
    // =========================================================================

    @Test
    @DisplayName("1-4: Valid batch analysis by Production Supervisor verifies dossier retrieval, prompt grounding, and programmatic source references")
    void validBatchAnalysis_productionSupervisor_verifiesEndToEndFlow() throws Exception {
        String token = bearerToken(supervisorUser);

        // Create BATCH conversation
        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Batch Analysis Thread",
                "Batch Traceability",
                "BATCH",
                testBatch.getBatchNumber()
        );

        MvcResult createRes = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contextType").value("BATCH"))
                .andExpect(jsonPath("$.contextId").value(testBatch.getBatchNumber()))
                .andReturn();

        long convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Submit message
        String clientReqId = "req-" + UUID.randomUUID();
        CopilotSubmissionRequestDto submitDto = new CopilotSubmissionRequestDto(
                clientReqId,
                "Please generate a complete AI Batch Analysis Report for " + testBatch.getBatchNumber(),
                "Batch Traceability",
                "BATCH",
                testBatch.getBatchNumber()
        );

        MvcResult msgRes = mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantMessage.contextType").value("BATCH"))
                .andExpect(jsonPath("$.assistantMessage.contextIdentifier").value(testBatch.getBatchNumber()))
                .andExpect(jsonPath("$.assistantMessage.sources").isArray())
                .andReturn();

        // 3. Verify Batch context reached the LLM prompt
        ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient, atLeastOnce()).generate(sysCaptor.capture(), promptCaptor.capture());

        assertThat(sysCaptor.getValue()).contains("AI Batch Analysis Report for a Production Supervisor");
        assertThat(sysCaptor.getValue()).contains("<<<VERIFIED_PHARMATRACK_DATA>>>");
        assertThat(promptCaptor.getValue()).contains(BatchContextBuilder.DATA_START_DELIMITER);
        assertThat(promptCaptor.getValue()).contains("Batch Number: " + testBatch.getBatchNumber());
        assertThat(promptCaptor.getValue()).contains("Planned Batch Size: 100.0000 kg");
        assertThat(promptCaptor.getValue()).contains("Actual Yield: 98.5000 kg");
        assertThat(promptCaptor.getValue()).contains("Yield Percentage: 98.50%");
        assertThat(promptCaptor.getValue()).contains("Yield Variance: -1.5 kg");
        assertThat(promptCaptor.getValue()).contains("Operator: [Operator]"); // PII minimization
        assertThat(promptCaptor.getValue()).contains("Inspector: [QC Analyst]"); // PII minimization
        assertThat(promptCaptor.getValue()).contains(BatchContextBuilder.DATA_END_DELIMITER);

        // 4. Verify programmatic source references returned
        JsonNode assistantNode = objectMapper.readTree(msgRes.getResponse().getContentAsString()).get("assistantMessage");
        JsonNode sources = assistantNode.get("sources");
        assertThat(sources.size()).isGreaterThanOrEqualTo(2);
        assertThat(sources.get(0).get("recordType").asText()).isEqualTo("BATCH");
        assertThat(sources.get(0).get("recordIdentifier").asText()).isEqualTo(testBatch.getBatchNumber());
        assertThat(sources.get(1).get("recordType").asText()).isEqualTo("MANUFACTURING_ORDER");
        assertThat(sources.get(1).get("recordIdentifier").asText()).isEqualTo(testOrder.getOrderNumber());
    }

    // =========================================================================
    // 5-6: Invalid Batch Number & Batch Not Found
    // =========================================================================

    @Test
    @DisplayName("5: Invalid batch number returns clarification without invoking LLM")
    void invalidBatchNumber_returnsClarificationWithoutLlmCall() throws Exception {
        String token = bearerToken(supervisorUser);

        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Invalid Batch Thread", "Batch Traceability", "BATCH", null
        );
        MvcResult createRes = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        reset(llmClient);

        CopilotSubmissionRequestDto submitDto = new CopilotSubmissionRequestDto(
                "req-" + UUID.randomUUID(),
                "Analyze batch without a number",
                "Batch Traceability",
                "BATCH",
                ""
        );

        mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantMessage.content").value(org.hamcrest.Matchers.containsString("Please specify a valid batch number")));

        verifyNoInteractions(llmClient);
    }

    @Test
    @DisplayName("6: Batch not found returns safe notification without invoking LLM")
    void batchNotFound_returnsSafeNotFoundMessageWithoutLlmCall() throws Exception {
        String token = bearerToken(supervisorUser);

        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Nonexistent Batch Thread", "Batch Traceability", "BATCH", "BAT-2026-9999-99"
        );
        MvcResult createRes = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        reset(llmClient);

        CopilotSubmissionRequestDto submitDto = new CopilotSubmissionRequestDto(
                "req-" + UUID.randomUUID(),
                "Analyze batch BAT-2026-9999-99",
                "Batch Traceability",
                "BATCH",
                "BAT-2026-9999-99"
        );

        mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantMessage.content").value(org.hamcrest.Matchers.containsString("was not found in PharmaTrack records")));

        verifyNoInteractions(llmClient);
    }

    // =========================================================================
    // 7: Missing / Incomplete Dossier Data
    // =========================================================================

    @Test
    @DisplayName("7: Missing or incomplete dossier data handled safely with factual fallbacks")
    void sparseDossier_handlesMissingDataGracefully() throws Exception {
        String token = bearerToken(supervisorUser);

        // Create a sparse batch without steps, QC, yield, or times
        int n = SEQ.incrementAndGet();
        Batch sparseBatch = new Batch(
                "BAT-2026-" + String.format("%04d", n) + "-02",
                testOrder,
                new BigDecimal("50.0000"),
                "kg",
                BatchStatus.PLANNED,
                LocalDate.now(),
                LocalDate.now().plusYears(1)
        );
        sparseBatch = batchRepository.saveAndFlush(sparseBatch);

        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Sparse Batch Thread", "Batch Traceability", "BATCH", sparseBatch.getBatchNumber()
        );
        MvcResult createRes = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        CopilotSubmissionRequestDto submitDto = new CopilotSubmissionRequestDto(
                "req-" + UUID.randomUUID(),
                "Analyze batch " + sparseBatch.getBatchNumber(),
                "Batch Traceability",
                "BATCH",
                sparseBatch.getBatchNumber()
        );

        mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDto)))
                .andExpect(status().isOk());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient, atLeastOnce()).generate(anyString(), promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("Actual Yield: Not completed / Unrecorded");
        assertThat(promptCaptor.getValue()).contains("Total Execution Steps: 0");
        assertThat(promptCaptor.getValue()).contains("Total Recorded QC Checks: 0");
    }

    // =========================================================================
    // 8: AI Provider Failure
    // =========================================================================

    @Test
    @DisplayName("8: AI provider failure handled safely without losing conversation")
    void providerFailure_marksRequestFailedAndReturnsFriendlyMessage() throws Exception {
        String token = bearerToken(supervisorUser);

        when(llmClient.generate(anyString(), anyString())).thenThrow(
                new LlmClientException(LlmClientException.ErrorCategory.TIMEOUT, "HTTP 504 Gateway Timeout")
        );

        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Failing Provider Thread", "Batch Traceability", "BATCH", testBatch.getBatchNumber()
        );
        MvcResult createRes = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        CopilotSubmissionRequestDto submitDto = new CopilotSubmissionRequestDto(
                "req-" + UUID.randomUUID(),
                "Analyze batch " + testBatch.getBatchNumber(),
                "Batch Traceability",
                "BATCH",
                testBatch.getBatchNumber()
        );

        mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantMessage.content").value(org.hamcrest.Matchers.containsString("timed out while processing your request")))
                .andExpect(jsonPath("$.assistantMessage.status").value("FAILED"));
    }

    // =========================================================================
    // 9: Non-Supervisor Role Denial (ADMIN, OPERATOR, QC_ANALYST, QA_REVIEWER)
    // =========================================================================

    @Test
    @DisplayName("9a: ADMIN is denied BATCH context conversation creation (HTTP 403)")
    void adminUser_deniedBatchConversationCreation() throws Exception {
        String token = bearerToken(adminUser);
        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Admin Batch Thread", "Batch Traceability", "BATCH", testBatch.getBatchNumber()
        );
        mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("9b: OPERATOR is denied BATCH context conversation creation (HTTP 403)")
    void operatorUser_deniedBatchConversationCreation() throws Exception {
        String token = bearerToken(operatorUser);
        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Operator Batch Thread", "Batch Traceability", "BATCH", testBatch.getBatchNumber()
        );
        mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("9c: QC_ANALYST is denied BATCH context conversation creation (HTTP 403)")
    void qcAnalystUser_deniedBatchConversationCreation() throws Exception {
        String token = bearerToken(qcUser);
        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "QC Batch Thread", "Batch Traceability", "BATCH", testBatch.getBatchNumber()
        );
        mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("9d: QA_REVIEWER is denied BATCH context conversation creation (HTTP 403)")
    void qaReviewerUser_deniedBatchConversationCreation() throws Exception {
        String token = bearerToken(qaUser);
        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "QA Batch Thread", "Batch Traceability", "BATCH", testBatch.getBatchNumber()
        );
        mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 10: Unauthenticated Request (HTTP 401)
    // =========================================================================

    @Test
    @DisplayName("10: Unauthenticated request is rejected with HTTP 401")
    void unauthenticatedRequest_returns401() throws Exception {
        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Unauth Thread", "Batch Traceability", "BATCH", testBatch.getBatchNumber()
        );
        mockMvc.perform(post("/api/ai/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 11: Cross-User Conversation Access (HTTP 404)
    // =========================================================================

    @Test
    @DisplayName("11: Cross-user conversation access returns HTTP 404 to prevent info leak")
    void crossUserConversationAccess_returns404() throws Exception {
        String supervisorToken = bearerToken(supervisorUser);
        UserAccount otherSupervisor = createTestUser("other_sup", Role.PRODUCTION_SUPERVISOR);
        String otherSupervisorToken = bearerToken(otherSupervisor);

        // Supervisor 1 creates conversation
        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Supervisor 1 Thread", "Batch Traceability", "BATCH", testBatch.getBatchNumber()
        );
        MvcResult createRes = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Supervisor 2 attempts to get conversation detail -> 404
        mockMvc.perform(get("/api/ai/conversations/" + convId)
                        .header(HttpHeaders.AUTHORIZATION, otherSupervisorToken))
                .andExpect(status().isNotFound());

        // Supervisor 2 attempts to submit message -> 404
        CopilotSubmissionRequestDto submitDto = new CopilotSubmissionRequestDto(
                "req-" + UUID.randomUUID(),
                "Analyze batch " + testBatch.getBatchNumber(),
                "Batch Traceability",
                "BATCH",
                testBatch.getBatchNumber()
        );
        mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, otherSupervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDto)))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 12: Role Changed After Conversation Creation (HTTP 403)
    // =========================================================================

    @Test
    @DisplayName("12: Demoting user from PRODUCTION_SUPERVISOR immediately denies BATCH conversation access (HTTP 403)")
    void roleChangedAfterCreation_deniesAccess() throws Exception {
        UserAccount demotableUser = createTestUser("demotable", Role.PRODUCTION_SUPERVISOR);
        String token = bearerToken(demotableUser);

        // Create BATCH conversation while still a supervisor
        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Demotion Test Thread", "Batch Traceability", "BATCH", testBatch.getBatchNumber()
        );
        MvcResult createRes = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Demote user to OPERATOR in database
        demotableUser.setRole(Role.OPERATOR);
        userAccountRepository.saveAndFlush(demotableUser);
        String demotedToken = bearerToken(demotableUser);

        // Fetching detail now returns 403 Forbidden!
        mockMvc.perform(get("/api/ai/conversations/" + convId)
                        .header(HttpHeaders.AUTHORIZATION, demotedToken))
                .andExpect(status().isForbidden());

        // Submitting a message now returns 403 Forbidden!
        CopilotSubmissionRequestDto submitDto = new CopilotSubmissionRequestDto(
                "req-" + UUID.randomUUID(),
                "Analyze batch " + testBatch.getBatchNumber(),
                "Batch Traceability",
                "BATCH",
                testBatch.getBatchNumber()
        );
        mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, demotedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDto)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 13: Existing Manufacturing Order Copilot Behavior Unchanged
    // =========================================================================

    @Test
    @DisplayName("13: Existing Manufacturing Order Copilot behavior remains fully functional for non-supervisors")
    void existingManufacturingOrderCopilot_remainsAccessible() throws Exception {
        String token = bearerToken(operatorUser);

        // Operator creates order conversation
        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Order Chat", "Manufacturing Orders", "MANUFACTURING_ORDER", testOrder.getOrderNumber()
        );
        MvcResult createRes = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contextType").value("MANUFACTURING_ORDER"))
                .andReturn();
        long convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        CopilotSubmissionRequestDto submitDto = new CopilotSubmissionRequestDto(
                "req-" + UUID.randomUUID(),
                "What is the status of order " + testOrder.getOrderNumber() + "?",
                "Manufacturing Orders",
                "MANUFACTURING_ORDER",
                testOrder.getOrderNumber()
        );

        mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantMessage.contextType").value("MANUFACTURING_ORDER"))
                .andExpect(jsonPath("$.assistantMessage.contextIdentifier").value(testOrder.getOrderNumber()));
    }

    // =========================================================================
    // 14: Idempotent Duplicate Request
    // =========================================================================

    @Test
    @DisplayName("14: Idempotent duplicate submission returns cached assistant answer without second LLM call")
    void duplicateSubmission_returnsCachedResultIdempotently() throws Exception {
        String token = bearerToken(supervisorUser);

        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Idempotency Thread", "Batch Traceability", "BATCH", testBatch.getBatchNumber()
        );
        MvcResult createRes = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        String clientReqId = "req-idempotent-001";
        CopilotSubmissionRequestDto submitDto = new CopilotSubmissionRequestDto(
                clientReqId,
                "Analyze batch " + testBatch.getBatchNumber(),
                "Batch Traceability",
                "BATCH",
                testBatch.getBatchNumber()
        );

        // First submission -> invokes LLM
        mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDto)))
                .andExpect(status().isOk());

        verify(llmClient, times(1)).generate(anyString(), anyString());

        // Second identical submission -> returns cached answer, zero additional LLM calls
        mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientRequestId").value(clientReqId));

        verify(llmClient, times(1)).generate(anyString(), anyString());
    }

    // =========================================================================
    // 15: Failed Request and Retry Behavior
    // =========================================================================

    @Test
    @DisplayName("15: Failed request can be retried with a new clientRequestId and succeeds")
    void failedRequest_retryWithNewClientRequestIdSucceeds() throws Exception {
        String token = bearerToken(supervisorUser);

        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Retry Thread", "Batch Traceability", "BATCH", testBatch.getBatchNumber()
        );
        MvcResult createRes = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // 1. LLM fails on first attempt
        when(llmClient.generate(anyString(), anyString())).thenThrow(
                new LlmClientException(LlmClientException.ErrorCategory.SERVER_ERROR, "Server busy")
        );

        CopilotSubmissionRequestDto failDto = new CopilotSubmissionRequestDto(
                "req-fail-001",
                "Analyze batch " + testBatch.getBatchNumber(),
                "Batch Traceability",
                "BATCH",
                testBatch.getBatchNumber()
        );

        mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(failDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantMessage.status").value("FAILED"));

        // 2. LLM recovers
        reset(llmClient);
        when(llmClient.generate(anyString(), anyString())).thenReturn(
                new LlmResponse("### Recovered Report\nAll clear.", "test-model", "resp-recovered", 100, 50, 150)
        );

        // 3. Retry with new clientRequestId succeeds
        CopilotSubmissionRequestDto retryDto = new CopilotSubmissionRequestDto(
                "req-retry-002",
                "Analyze batch " + testBatch.getBatchNumber(),
                "Batch Traceability",
                "BATCH",
                testBatch.getBatchNumber()
        );

        mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(retryDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantMessage.status").value("COMPLETED"))
                .andExpect(jsonPath("$.assistantMessage.content").value(org.hamcrest.Matchers.containsString("Recovered Report")));
    }

    // =========================================================================
    // 16: No MES Mutation During Analysis
    // =========================================================================

    @Test
    @DisplayName("16: AI analysis strictly performs read-only queries with zero mutations to MES batches or orders")
    void aiAnalysis_causesZeroMesMutations() throws Exception {
        String token = bearerToken(supervisorUser);

        Batch beforeBatch = batchRepository.findById(testBatch.getId()).orElseThrow();
        ManufacturingOrder beforeOrder = orderRepository.findById(testOrder.getId()).orElseThrow();
        long stepCountBefore = executionStepRepository.count();
        long qcCountBefore = qualityCheckRepository.count();

        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Read-only Verification", "Batch Traceability", "BATCH", testBatch.getBatchNumber()
        );
        MvcResult createRes = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        CopilotSubmissionRequestDto submitDto = new CopilotSubmissionRequestDto(
                "req-" + UUID.randomUUID(),
                "Analyze batch " + testBatch.getBatchNumber(),
                "Batch Traceability",
                "BATCH",
                testBatch.getBatchNumber()
        );

        mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDto)))
                .andExpect(status().isOk());

        Batch afterBatch = batchRepository.findById(testBatch.getId()).orElseThrow();
        ManufacturingOrder afterOrder = orderRepository.findById(testOrder.getId()).orElseThrow();

        assertThat(afterBatch.getStatus()).isEqualTo(beforeBatch.getStatus());
        assertThat(afterBatch.getQaStatus()).isEqualTo(beforeBatch.getQaStatus());
        assertThat(afterBatch.getActualYield()).isEqualByComparingTo(beforeBatch.getActualYield());
        assertThat(afterOrder.getStatus()).isEqualTo(beforeOrder.getStatus());
        assertThat(executionStepRepository.count()).isEqualTo(stepCountBefore);
        assertThat(qualityCheckRepository.count()).isEqualTo(qcCountBefore);
    }

    // =========================================================================
    // 17: No Raw Reasoning or Sensitive Credentials in Messages
    // =========================================================================

    @Test
    @DisplayName("17: Persisted messages contain no raw reasoning or sensitive API tokens")
    void persistedMessages_containNoSensitiveInformation() throws Exception {
        String token = bearerToken(supervisorUser);

        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Sanitization Thread", "Batch Traceability", "BATCH", testBatch.getBatchNumber()
        );
        MvcResult createRes = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        CopilotSubmissionRequestDto submitDto = new CopilotSubmissionRequestDto(
                "req-" + UUID.randomUUID(),
                "Analyze batch " + testBatch.getBatchNumber(),
                "Batch Traceability",
                "BATCH",
                testBatch.getBatchNumber()
        );

        mockMvc.perform(post("/api/ai/conversations/" + convId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDto)))
                .andExpect(status().isOk());

        List<CopilotMessage> messages = messageRepository.findMessagesByConversationId(convId);
        assertThat(messages).isNotEmpty();

        for (CopilotMessage msg : messages) {
            String content = msg.getContent();
            assertThat(content).doesNotContain("sk-");
            assertThat(content).doesNotContain("Bearer ");
            assertThat(content).doesNotContain("password");
        }
    }
}
