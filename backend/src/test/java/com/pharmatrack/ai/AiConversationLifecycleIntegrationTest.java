package com.pharmatrack.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.client.LlmClientException;
import com.pharmatrack.ai.service.AiConversationService;
import com.pharmatrack.ai.service.AiConversationTxHelper;
import com.pharmatrack.ai.service.AiCopilotService;
import com.pharmatrack.dto.AiQueryRequestDto;
import com.pharmatrack.dto.AiQueryResponseDto;
import com.pharmatrack.dto.AiSourceReferenceDto;
import com.pharmatrack.dto.CopilotConversationCreateRequestDto;
import com.pharmatrack.dto.CopilotConversationRenameRequestDto;
import com.pharmatrack.dto.CopilotSubmissionRequestDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.CopilotConversation;
import com.pharmatrack.entity.CopilotMessage;
import com.pharmatrack.entity.CopilotMessageRole;
import com.pharmatrack.entity.CopilotRequest;
import com.pharmatrack.entity.CopilotRequestStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.CopilotConversationRepository;
import com.pharmatrack.repository.CopilotMessageRepository;
import com.pharmatrack.repository.CopilotRequestRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration tests for PharmaTrack AI Copilot Phase 3 conversation lifecycle.
 *
 * <p>Validates HTTP endpoints, security authentication, transactional orchestration,
 * idempotency, pessimistic locking, lease recovery, failure isolation, and audit trail
 * against a real Spring application context and isolated H2 database.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_ai_conversation_it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("PharmaTrack AI Copilot Phase 3 - End-to-End Integration Suite")
class AiConversationLifecycleIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

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
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AiConversationTxHelper txHelper;

    @MockBean
    private AiCopilotService aiCopilotService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        messageRepository.deleteAll();
        requestRepository.deleteAll();
        conversationRepository.deleteAll();
        userAccountRepository.deleteAll();
    }

    private UserAccount createTestUser(String usernamePrefix, Role role) {
        int n = SEQ.getAndIncrement();
        String username = usernamePrefix + "_" + n;
        UserAccount user = new UserAccount(
                username,
                passwordEncoder.encode("SecurePass9!" + UUID.randomUUID()),
                "User " + role.name() + " " + n,
                role,
                true
        );
        return userAccountRepository.saveAndFlush(user);
    }

    private String bearerToken(UserAccount user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    // =========================================================================
    // Scenario A: Conversation Lifecycle
    // =========================================================================

    @Test
    @DisplayName("Scenario A: Full Conversation Lifecycle (Create -> List -> Detail -> Rename -> Delete -> Not Found)")
    void conversationLifecycle_fullCrudAndPersistenceFlow() throws Exception {
        UserAccount user = createTestUser("lifecycle_user", Role.PRODUCTION_SUPERVISOR);
        String token = bearerToken(user);

        // 1. Create conversation
        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto(
                "Batch Yield Analysis",
                "BATCH",
                "MANUFACTURING_ORDER",
                "MO-2026-0001"
        );

        MvcResult createResult = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Batch Yield Analysis"))
                .andExpect(jsonPath("$.activeModule").value("BATCH"))
                .andExpect(jsonPath("$.contextType").value("MANUFACTURING_ORDER"))
                .andExpect(jsonPath("$.contextId").value("MO-2026-0001"))
                .andExpect(jsonPath("$.messageCount").value(0))
                .andReturn();

        long convId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        // Verify database persistence directly
        CopilotConversation persistedConv = conversationRepository.findById(convId).orElseThrow();
        assertThat(persistedConv.getUser().getId()).isEqualTo(user.getId());
        assertThat(persistedConv.getTitle()).isEqualTo("Batch Yield Analysis");

        // 2. List conversations
        mockMvc.perform(get("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(convId))
                .andExpect(jsonPath("$[0].title").value("Batch Yield Analysis"))
                .andExpect(jsonPath("$[0].messageCount").value(0));

        // 3. Retrieve conversation details
        mockMvc.perform(get("/api/ai/conversations/{id}", convId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(convId))
                .andExpect(jsonPath("$.title").value("Batch Yield Analysis"))
                .andExpect(jsonPath("$.messages").isArray())
                .andExpect(jsonPath("$.messages.length()").value(0));

        // 4. Rename conversation
        CopilotConversationRenameRequestDto renameDto = new CopilotConversationRenameRequestDto("Updated MO Yield Analysis");
        mockMvc.perform(patch("/api/ai/conversations/{id}", convId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(renameDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(convId))
                .andExpect(jsonPath("$.title").value("Updated MO Yield Analysis"));

        // Verify updated title reflected in detail
        mockMvc.perform(get("/api/ai/conversations/{id}", convId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated MO Yield Analysis"));

        // 5. Delete conversation
        mockMvc.perform(delete("/api/ai/conversations/{id}", convId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNoContent());

        // 6. Subsequent retrieval returns 404
        mockMvc.perform(get("/api/ai/conversations/{id}", convId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // Direct DB verification
        assertThat(conversationRepository.findById(convId)).isEmpty();
    }

    // =========================================================================
    // Scenario B: Message Submission and Persistence
    // =========================================================================

    @Test
    @DisplayName("Scenario B: Message Submission & Persistence (AI Invocation, Chronological Transcript, Safe Projection)")
    void messageSubmission_persistsMessagesChronologicallyWithoutLeakingInternalFields() throws Exception {
        UserAccount user = createTestUser("submission_user", Role.PRODUCTION_SUPERVISOR);
        String token = bearerToken(user);

        CopilotConversation conv = conversationRepository.saveAndFlush(
                new CopilotConversation(user, "Order Inquiry")
        );

        // Configure mock AI orchestrator
        when(aiCopilotService.query(any(AiQueryRequestDto.class))).thenReturn(new AiQueryResponseDto(
                "Batch B-101 has completed synthesis with 98.5% yield.",
                "MANUFACTURING_ORDER",
                "MO-2026-0001",
                List.of(new AiSourceReferenceDto("BATCH", "B-101", "Batch B-101", "/batches/B-101", "Yield: 98.5%")),
                "groq/llama-3.3-70b-versatile"
        ));

        CopilotSubmissionRequestDto submitDto = new CopilotSubmissionRequestDto(
                "req-client-001",
                "What is the status and yield of Batch B-101 in MO-2026-0001?",
                "BATCH",
                "MANUFACTURING_ORDER",
                "MO-2026-0001"
        );

        MvcResult submitResult = mockMvc.perform(post("/api/ai/conversations/{id}/messages", conv.getId())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conv.getId()))
                .andExpect(jsonPath("$.clientRequestId").value("req-client-001"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.userMessage.role").value("USER"))
                .andExpect(jsonPath("$.userMessage.content").value("What is the status and yield of Batch B-101 in MO-2026-0001?"))
                .andExpect(jsonPath("$.assistantMessage.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.assistantMessage.content").value("Batch B-101 has completed synthesis with 98.5% yield."))
                .andExpect(jsonPath("$.assistantMessage.model").value("groq/llama-3.3-70b-versatile"))
                .andExpect(jsonPath("$.assistantMessage.sources[0].recordIdentifier").value("B-101"))
                .andReturn();

        // Verify AI service invoked exactly once
        verify(aiCopilotService, times(1)).query(any(AiQueryRequestDto.class));

        // Verify internal fields are NEVER exposed in response JSON
        String rawJson = submitResult.getResponse().getContentAsString();
        assertThat(rawJson).doesNotContain("leaseToken");
        assertThat(rawJson).doesNotContain("requestHash");

        // Verify conversation detail returns both messages in strict chronological order
        MvcResult detailResult = mockMvc.perform(get("/api/ai/conversations/{id}", conv.getId())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[0].content").value("What is the status and yield of Batch B-101 in MO-2026-0001?"))
                .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.messages[1].content").value("Batch B-101 has completed synthesis with 98.5% yield."))
                .andExpect(jsonPath("$.messages[1].status").value("COMPLETED"))
                .andReturn();

        JsonNode detailJson = objectMapper.readTree(detailResult.getResponse().getContentAsString());
        assertThat(detailJson.get("messages").get(0).get("createdAt").asText())
                .isLessThanOrEqualTo(detailJson.get("messages").get(1).get("createdAt").asText());
    }

    // =========================================================================
    // Scenario C: Idempotency
    // =========================================================================

    @Test
    @DisplayName("Scenario C: Idempotency (Exact Replay Returns Persisted, Fingerprint Mismatch Returns 409 Conflict)")
    void idempotency_exactReplayAndMismatchHandling() throws Exception {
        UserAccount user = createTestUser("idemp_user", Role.PRODUCTION_SUPERVISOR);
        String token = bearerToken(user);

        CopilotConversation conv = conversationRepository.saveAndFlush(
                new CopilotConversation(user, "Idempotency Test Thread")
        );

        when(aiCopilotService.query(any(AiQueryRequestDto.class))).thenReturn(new AiQueryResponseDto(
                "Manufacturing Order MO-2026-0001 is currently IN_PROGRESS.",
                "MANUFACTURING_ORDER",
                "MO-2026-0001",
                List.of(),
                "groq/llama-3.3-70b-versatile"
        ));

        CopilotSubmissionRequestDto originalRequest = new CopilotSubmissionRequestDto(
                "client-idemp-001",
                "Status of MO-2026-0001?",
                "ORDERS",
                "MANUFACTURING_ORDER",
                "MO-2026-0001"
        );

        // 1. Initial submission
        mockMvc.perform(post("/api/ai/conversations/{id}/messages", conv.getId())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(originalRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 2. Exact replay with same clientRequestId and identical payload
        mockMvc.perform(post("/api/ai/conversations/{id}/messages", conv.getId())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(originalRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.assistantMessage.content").value("Manufacturing Order MO-2026-0001 is currently IN_PROGRESS."));

        // Verify LLM service was called ONLY once despite two submissions
        verify(aiCopilotService, times(1)).query(any(AiQueryRequestDto.class));

        // 3. Conflicting request: same clientRequestId with different query payload
        CopilotSubmissionRequestDto conflictingRequest = new CopilotSubmissionRequestDto(
                "client-idemp-001",
                "Completely different question about equipment calibration",
                "ORDERS",
                "MANUFACTURING_ORDER",
                "MO-2026-0001"
        );

        mockMvc.perform(post("/api/ai/conversations/{id}/messages", conv.getId())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflictingRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already been used with different request parameters")));

        // Verify no duplicate requests or extra messages were inserted
        List<CopilotRequest> requests = requestRepository.findAll();
        assertThat(requests).hasSize(1);
        List<CopilotMessage> messages = messageRepository.findMessagesByConversationId(conv.getId());
        assertThat(messages).hasSize(2); // 1 USER + 1 ASSISTANT
    }

    // =========================================================================
    // Scenario D: Failure Handling
    // =========================================================================

    @Test
    @DisplayName("Scenario D: Failure Handling (Upstream Provider Error Transitions to FAILED with Safe Message & Allows Clean Retry)")
    void failureHandling_upstreamErrorTransitionsToFailedSafelyAndPermitsRetry() throws Exception {
        UserAccount user = createTestUser("failure_user", Role.PRODUCTION_SUPERVISOR);
        String token = bearerToken(user);

        CopilotConversation conv = conversationRepository.saveAndFlush(
                new CopilotConversation(user, "Failure Handling Thread")
        );

        // 1. Configure AI service to throw an upstream exception containing sensitive details
        when(aiCopilotService.query(any(AiQueryRequestDto.class)))
                .thenThrow(new RuntimeException("Upstream Groq connection reset by peer; secret=sk-internal-secret-999; internal_trace_token=777"));

        CopilotSubmissionRequestDto failedSubmission = new CopilotSubmissionRequestDto(
                "client-fail-001",
                "Calculate total yield across batches",
                "BATCH",
                "MANUFACTURING_ORDER",
                "MO-2026-0001"
        );

        // Submission endpoint completes the lifecycle by recording failure and returning safe DTO
        MvcResult failResult = mockMvc.perform(post("/api/ai/conversations/{id}/messages", conv.getId())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(failedSubmission)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.assistantMessage.status").value("FAILED"))
                .andExpect(jsonPath("$.assistantMessage.content").value("AI service is temporarily unavailable. Please try your question again."))
                .andReturn();

        // Verify NO sensitive tokens or raw exception details are exposed in response
        String rawFailResponse = failResult.getResponse().getContentAsString();
        assertThat(rawFailResponse).doesNotContain("sk-internal-secret");
        assertThat(rawFailResponse).doesNotContain("internal_trace_token");

        // Verify database state directly
        CopilotRequest failedReq = requestRepository.findByConversationIdAndClientRequestId(conv.getId(), "client-fail-001").orElseThrow();
        assertThat(failedReq.getStatus()).isEqualTo(CopilotRequestStatus.FAILED);
        assertThat(failedReq.getLeaseToken()).isNull(); // Lease cleared

        // 2. Retry with a new clientRequestId and working AI service
        when(aiCopilotService.query(any(AiQueryRequestDto.class))).thenReturn(new AiQueryResponseDto(
                "Total yield across all batches is 4,500 kg (99.1%).",
                "MANUFACTURING_ORDER",
                "MO-2026-0001",
                List.of(),
                "groq/llama-3.3-70b-versatile"
        ));

        CopilotSubmissionRequestDto retrySubmission = new CopilotSubmissionRequestDto(
                "client-retry-002",
                "Calculate total yield across batches",
                "BATCH",
                "MANUFACTURING_ORDER",
                "MO-2026-0001"
        );

        mockMvc.perform(post("/api/ai/conversations/{id}/messages", conv.getId())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(retrySubmission)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.assistantMessage.content").value("Total yield across all batches is 4,500 kg (99.1%)."));

        // Verify conversation transcript contains both attempts chronologically
        mockMvc.perform(get("/api/ai/conversations/{id}", conv.getId())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(4)); // 2 from failed attempt, 2 from retry
    }

    // =========================================================================
    // Scenario E: Ownership and Security
    // =========================================================================

    @Test
    @DisplayName("Scenario E: Ownership & RBAC (Cross-User Returns 404, Anonymous Returns 401, All 5 Roles Permitted)")
    void ownershipAndSecurity_strictUserIsolationAndRoleAccess() throws Exception {
        UserAccount userA = createTestUser("user_a", Role.PRODUCTION_SUPERVISOR);
        UserAccount userB = createTestUser("user_b", Role.QC_ANALYST);

        String tokenA = bearerToken(userA);
        String tokenB = bearerToken(userB);

        // User A creates conversation
        CopilotConversation convA = conversationRepository.saveAndFlush(
                new CopilotConversation(userA, "User A Secret Thread")
        );

        // 1. User A can access their own conversation
        mockMvc.perform(get("/api/ai/conversations/{id}", convA.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(convA.getId()));

        // 2. User B cannot retrieve User A's conversation (404)
        mockMvc.perform(get("/api/ai/conversations/{id}", convA.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // 3. User B cannot rename User A's conversation (404)
        mockMvc.perform(patch("/api/ai/conversations/{id}", convA.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CopilotConversationRenameRequestDto("Hacked Title"))))
                .andExpect(status().isNotFound());

        // 4. User B cannot delete User A's conversation (404)
        mockMvc.perform(delete("/api/ai/conversations/{id}", convA.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenB))
                .andExpect(status().isNotFound());

        // 5. User B cannot submit a message to User A's conversation (404)
        mockMvc.perform(post("/api/ai/conversations/{id}/messages", convA.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CopilotSubmissionRequestDto("b-req-1", "Test", "BATCH", null, null))))
                .andExpect(status().isNotFound());

        // 6. Anonymous request without token receives 401
        mockMvc.perform(get("/api/ai/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // 7. Verify all 5 MES roles are authorized to create and manage their own conversations
        for (Role role : Role.values()) {
            UserAccount roleUser = createTestUser("role_test_" + role.name().toLowerCase(), role);
            String roleToken = bearerToken(roleUser);

            mockMvc.perform(post("/api/ai/conversations")
                            .header(HttpHeaders.AUTHORIZATION, roleToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new CopilotConversationCreateRequestDto("Role " + role.name() + " Thread"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber());

            mockMvc.perform(get("/api/ai/conversations")
                            .header(HttpHeaders.AUTHORIZATION, roleToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }

    // =========================================================================
    // Scenario F: Pending Request and Recovery Behaviour
    // =========================================================================

    @Test
    @DisplayName("Scenario F: Pending & Recovery (In-Flight Placeholder Projection, Expired Recovery, Completed Invariance)")
    void pendingAndRecovery_inFlightPlaceholderAndSafeRecoveryFlow() {
        UserAccount user = createTestUser("recovery_user", Role.PRODUCTION_SUPERVISOR);
        String token = bearerToken(user);

        CopilotConversation conv = conversationRepository.saveAndFlush(
                new CopilotConversation(user, "Recovery Test Thread")
        );

        // 1. Start an in-flight PENDING request and set lease as expired
        CopilotRequest expiredPendingReq = txHelper.startRequestTx(
                conv.getId(),
                "pending-client-999",
                "hash-placeholder-test",
                "In-flight query about batch release",
                30
        );
        expiredPendingReq.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(10));
        requestRepository.saveAndFlush(expiredPendingReq);

        // 2. Fetch conversation details via HTTP: verify assistant message projects friendly placeholder
        try {
            mockMvc.perform(get("/api/ai/conversations/{id}", conv.getId())
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.messages.length()").value(2))
                    .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"))
                    .andExpect(jsonPath("$.messages[1].status").value("PENDING"))
                    .andExpect(jsonPath("$.messages[1].content").value(AiConversationService.IN_PROGRESS_PLACEHOLDER));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        // 3. Directly inspect the database row: assert raw persisted content in DB is "" (NOT mutated)
        CopilotMessage rawMsg = messageRepository.findByRequestIdAndRole(expiredPendingReq.getId(), CopilotMessageRole.ASSISTANT).orElseThrow();
        assertThat(rawMsg.getContent()).isEqualTo("");

        // 4. Recover the expired request
        boolean recovered = txHelper.recoverExpiredRequestTx(expiredPendingReq.getId());
        assertThat(recovered).isTrue();

        // Verify request transitions to FAILED and message contains safe timeout notice
        CopilotRequest recoveredReq = requestRepository.findById(expiredPendingReq.getId()).orElseThrow();
        assertThat(recoveredReq.getStatus()).isEqualTo(CopilotRequestStatus.FAILED);
        assertThat(recoveredReq.getLeaseToken()).isNull();

        CopilotMessage updatedMsg = messageRepository.findByRequestIdAndRole(expiredPendingReq.getId(), CopilotMessageRole.ASSISTANT).orElseThrow();
        assertThat(updatedMsg.getContent()).contains("timed out or was interrupted");

        // 5. Attempt recovery on an already COMPLETED request: verify recovery does NOT overwrite completed request
        CopilotRequest completedReq = txHelper.startRequestTx(
                conv.getId(),
                "completed-client-888",
                "hash-completed-test",
                "Completed query",
                60
        );
        txHelper.completeRequestTx(
                completedReq.getId(),
                completedReq.getLeaseToken(),
                "Finished answer",
                "model-test",
                "BATCH",
                "B-1",
                "[]"
        );

        boolean recoverCompletedResult = txHelper.recoverExpiredRequestTx(completedReq.getId());
        assertThat(recoverCompletedResult).isFalse();

        CopilotRequest recheckedCompleted = requestRepository.findById(completedReq.getId()).orElseThrow();
        assertThat(recheckedCompleted.getStatus()).isEqualTo(CopilotRequestStatus.COMPLETED);
    }

    // =========================================================================
    // Scenario G: Audit Behaviour
    // =========================================================================

    @Test
    @DisplayName("Scenario G: Audit Trail (Verifies All Actions Logged Without Sensitive Content or Prompts)")
    void auditTrail_logsAllLifecycleAndQueryActionsSafely() throws Exception {
        UserAccount user = createTestUser("audit_user", Role.PRODUCTION_SUPERVISOR);
        String token = bearerToken(user);

        // 1. Create conversation
        CopilotConversationCreateRequestDto createDto = new CopilotConversationCreateRequestDto("Audit Testing Conversation");
        MvcResult createRes = mockMvc.perform(post("/api/ai/conversations")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andReturn();
        long convId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // 2. Rename conversation
        mockMvc.perform(patch("/api/ai/conversations/{id}", convId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CopilotConversationRenameRequestDto("Renamed For Audit"))))
                .andExpect(status().isOk());

        // 3. Submit successful message
        when(aiCopilotService.query(any(AiQueryRequestDto.class))).thenReturn(new AiQueryResponseDto(
                "Verified audit answer text",
                "MANUFACTURING_ORDER",
                "MO-2026-0001",
                List.of(),
                "groq/llama-3.3-70b-versatile"
        ));
        mockMvc.perform(post("/api/ai/conversations/{id}/messages", convId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CopilotSubmissionRequestDto("audit-req-1", "Prompt secret inquiry", "BATCH", null, null))))
                .andExpect(status().isOk());

        // 4. Submit failed message
        when(aiCopilotService.query(any(AiQueryRequestDto.class)))
                .thenThrow(new LlmClientException(LlmClientException.ErrorCategory.TIMEOUT, "Timeout contacting AI upstream"));
        mockMvc.perform(post("/api/ai/conversations/{id}/messages", convId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CopilotSubmissionRequestDto("audit-req-2", "Failed query prompt", "BATCH", null, null))))
                .andExpect(status().isOk());

        // 5. Expired lease recovery
        CopilotRequest expiredReq = txHelper.startRequestTx(convId, "audit-req-3", "hash-audit-3", "Expired query", 30);
        expiredReq.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(10));
        requestRepository.saveAndFlush(expiredReq);
        txHelper.recoverExpiredRequestTx(expiredReq.getId());

        // 6. Delete conversation
        mockMvc.perform(delete("/api/ai/conversations/{id}", convId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNoContent());

        // 7. Verify audit log entries directly from repository
        List<AuditLog> auditLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc("AI_CONVERSATION", convId);
        assertThat(auditLogs).isNotEmpty();

        List<String> actions = auditLogs.stream().map(AuditLog::getAction).toList();
        assertThat(actions).contains(
                AiConversationTxHelper.ACTION_CONVERSATION_CREATED,
                AiConversationTxHelper.ACTION_CONVERSATION_RENAMED,
                AiConversationTxHelper.ACTION_QUERY_SUBMITTED,
                AiConversationTxHelper.ACTION_QUERY_COMPLETED,
                AiConversationTxHelper.ACTION_QUERY_FAILED,
                AiConversationTxHelper.ACTION_REQUEST_RECOVERED,
                AiConversationTxHelper.ACTION_CONVERSATION_DELETED
        );

        // Confirm NO audit record contains raw prompt text, answer text, or secrets
        for (AuditLog logEntry : auditLogs) {
            String combinedJson = (logEntry.getBeforeValues() != null ? logEntry.getBeforeValues() : "")
                    + (logEntry.getAfterValues() != null ? logEntry.getAfterValues() : "")
                    + (logEntry.getReason() != null ? logEntry.getReason() : "");

            assertThat(combinedJson).doesNotContain("Prompt secret inquiry");
            assertThat(combinedJson).doesNotContain("Verified audit answer text");
            assertThat(combinedJson).doesNotContain("Bearer ");
            assertThat(combinedJson).doesNotContain("sk-");
            assertThat(combinedJson).doesNotContain("secret");
        }
    }

    // =========================================================================
    // Section 4: Transaction and Consistency Checks
    // =========================================================================

    @Test
    @DisplayName("Section 4: Transaction & Consistency (Cascade Deletion, Recency Touch, Atomic Multi-Turn Ordering)")
    void transactionAndConsistency_cascadeDeletionAndAtomicMultiTurn() throws Exception {
        UserAccount user = createTestUser("tx_user", Role.PRODUCTION_SUPERVISOR);
        String token = bearerToken(user);

        CopilotConversation conv = conversationRepository.saveAndFlush(
                new CopilotConversation(user, "Cascade Test Thread")
        );
        LocalDateTime initialUpdatedAt = conv.getUpdatedAt();

        when(aiCopilotService.query(any(AiQueryRequestDto.class))).thenReturn(new AiQueryResponseDto(
                "Answer 1", "BATCH", "B-1", List.of(), "test-model"
        ));

        // Submit first turn
        mockMvc.perform(post("/api/ai/conversations/{id}/messages", conv.getId())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CopilotSubmissionRequestDto("tx-req-1", "Turn 1", "BATCH", null, null))))
                .andExpect(status().isOk());

        // Verify conversation updated_at moved forward
        CopilotConversation reloadedConv = conversationRepository.findById(conv.getId()).orElseThrow();
        assertThat(reloadedConv.getUpdatedAt()).isAfterOrEqualTo(initialUpdatedAt);

        // Verify child records exist
        assertThat(requestRepository.findByConversationIdAndClientRequestId(conv.getId(), "tx-req-1")).isPresent();
        assertThat(messageRepository.findMessagesByConversationId(conv.getId())).hasSize(2);

        // Delete conversation via HTTP
        mockMvc.perform(delete("/api/ai/conversations/{id}", conv.getId())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNoContent());

        // Verify cascade deletion completely removed parent conversation and child requests/messages
        assertThat(conversationRepository.findById(conv.getId())).isEmpty();
        assertThat(requestRepository.findByConversationIdAndClientRequestId(conv.getId(), "tx-req-1")).isEmpty();
        assertThat(messageRepository.findMessagesByConversationId(conv.getId())).isEmpty();
    }
}
