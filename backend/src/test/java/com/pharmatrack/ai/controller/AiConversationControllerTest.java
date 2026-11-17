package com.pharmatrack.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.service.AiConversationService;
import com.pharmatrack.dto.AiSourceReferenceDto;
import com.pharmatrack.dto.CopilotConversationCreateRequestDto;
import com.pharmatrack.dto.CopilotConversationDetailDto;
import com.pharmatrack.dto.CopilotConversationRenameRequestDto;
import com.pharmatrack.dto.CopilotConversationSummaryDto;
import com.pharmatrack.dto.CopilotMessageDto;
import com.pharmatrack.dto.CopilotMessageResponseDto;
import com.pharmatrack.dto.CopilotSubmissionRequestDto;
import com.pharmatrack.entity.CopilotMessageRole;
import com.pharmatrack.entity.CopilotRequestStatus;
import com.pharmatrack.exception.DuplicateResourceException;
import com.pharmatrack.exception.GlobalExceptionHandler;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller tests for {@link AiConversationController} verifying authentication boundaries,
 * RBAC authorization, DTO validation, unknown field rejection, and proper HTTP response status codes.
 */
@WebMvcTest(AiConversationController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@DisplayName("AiConversationController REST & Security Tests")
class AiConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiConversationService conversationService;

    // =========================================================================
    // POST /api/ai/conversations
    // =========================================================================

    @Test
    @DisplayName("POST /api/ai/conversations: Unauthenticated request is rejected with 401")
    void create_unauthenticated_returns401() throws Exception {
        CopilotConversationCreateRequestDto request = new CopilotConversationCreateRequestDto("Valid Title");

        mockMvc.perform(post("/api/ai/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/ai/conversations: Authenticated user creates conversation successfully (201 Created)")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void create_authenticated_returns201() throws Exception {
        CopilotConversationSummaryDto summary = new CopilotConversationSummaryDto(
                100L, "Batch Yield Inquiry", "orders", "MANUFACTURING_ORDER", "MO-2026-0001",
                LocalDateTime.now(), LocalDateTime.now(), 0L
        );
        when(conversationService.createConversation(any(CopilotConversationCreateRequestDto.class)))
                .thenReturn(summary);

        CopilotConversationCreateRequestDto request = new CopilotConversationCreateRequestDto(
                "Batch Yield Inquiry", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );

        mockMvc.perform(post("/api/ai/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.title").value("Batch Yield Inquiry"))
                .andExpect(jsonPath("$.activeModule").value("orders"))
                .andExpect(jsonPath("$.messageCount").value(0));
    }

    @Test
    @DisplayName("POST /api/ai/conversations: Blank title is rejected with 400 Bad Request")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void create_blankTitle_returns400() throws Exception {
        CopilotConversationCreateRequestDto request = new CopilotConversationCreateRequestDto("   ");

        mockMvc.perform(post("/api/ai/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.title", containsString("Conversation title is mandatory")));
    }

    @Test
    @DisplayName("POST /api/ai/conversations: Oversized title (>150 chars) is rejected with 400 Bad Request")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void create_oversizedTitle_returns400() throws Exception {
        String longTitle = "T".repeat(151);
        CopilotConversationCreateRequestDto request = new CopilotConversationCreateRequestDto(longTitle);

        mockMvc.perform(post("/api/ai/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.title", containsString("Conversation title must not exceed 150 characters")));
    }

    @Test
    @DisplayName("POST /api/ai/conversations: Unexpected JSON properties (e.g. userId) are rejected with 400 Bad Request")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void create_unexpectedProperties_returns400() throws Exception {
        String payloadWithUnknownFields = """
                {
                    "title": "Legitimate Title",
                    "userId": 999,
                    "role": "ADMIN"
                }
                """;

        mockMvc.perform(post("/api/ai/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadWithUnknownFields))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed JSON request or invalid enum value"));
    }

    // =========================================================================
    // GET /api/ai/conversations
    // =========================================================================

    @Test
    @DisplayName("GET /api/ai/conversations: Unauthenticated request is rejected with 401")
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/ai/conversations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/ai/conversations: Authenticated user retrieves their conversation list (200 OK)")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void list_authenticated_returns200() throws Exception {
        CopilotConversationSummaryDto summary1 = new CopilotConversationSummaryDto(
                100L, "First Thread", "orders", null, null, LocalDateTime.now(), LocalDateTime.now(), 2L
        );
        CopilotConversationSummaryDto summary2 = new CopilotConversationSummaryDto(
                101L, "Second Thread", "batch_traceability", null, null, LocalDateTime.now(), LocalDateTime.now(), 5L
        );
        when(conversationService.listConversations(null, null)).thenReturn(List.of(summary1, summary2));

        mockMvc.perform(get("/api/ai/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(100L))
                .andExpect(jsonPath("$[1].id").value(101L));
    }

    // =========================================================================
    // GET /api/ai/conversations/{id}
    // =========================================================================

    @Test
    @DisplayName("GET /api/ai/conversations/{id}: Unauthenticated request is rejected with 401")
    void getDetail_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/ai/conversations/100"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/ai/conversations/{id}: Authenticated owner retrieves conversation detail (200 OK)")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void getDetail_owned_returns200() throws Exception {
        CopilotMessageDto userMsg = new CopilotMessageDto(
                1L, 10L, CopilotMessageRole.USER, "What is the status of batch 1?",
                null, null, null, List.of(), CopilotRequestStatus.COMPLETED, LocalDateTime.now()
        );
        CopilotMessageDto assistantMsg = new CopilotMessageDto(
                2L, 10L, CopilotMessageRole.ASSISTANT, "Batch 1 is COMPLETED.",
                "gpt-4o-mini", "MANUFACTURING_ORDER", "MO-2026-0001",
                List.of(new AiSourceReferenceDto("MANUFACTURING_ORDER", "MO-2026-0001", "MO-2026-0001", "orders")),
                CopilotRequestStatus.COMPLETED, LocalDateTime.now()
        );
        CopilotConversationDetailDto detail = new CopilotConversationDetailDto(
                100L, "Batch Inquiry", "orders", "MANUFACTURING_ORDER", "MO-2026-0001",
                LocalDateTime.now(), LocalDateTime.now(), List.of(userMsg, assistantMsg)
        );
        when(conversationService.getConversationDetail(100L)).thenReturn(detail);

        mockMvc.perform(get("/api/ai/conversations/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.title").value("Batch Inquiry"))
                .andExpect(jsonPath("$.messages", hasSize(2)))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.messages[1].sources[0].recordIdentifier").value("MO-2026-0001"));
    }

    @Test
    @DisplayName("GET /api/ai/conversations/{id}: Access to nonexistent or unowned thread returns 404 Not Found")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void getDetail_unownedOrMissing_returns404() throws Exception {
        when(conversationService.getConversationDetail(999L))
                .thenThrow(new ResourceNotFoundException("Conversation not found with id: 999"));

        mockMvc.perform(get("/api/ai/conversations/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Conversation not found with id: 999"));
    }

    // =========================================================================
    // PATCH /api/ai/conversations/{id}
    // =========================================================================

    @Test
    @DisplayName("PATCH /api/ai/conversations/{id}: Authenticated owner renames conversation (200 OK)")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void rename_owned_returns200() throws Exception {
        CopilotConversationSummaryDto updated = new CopilotConversationSummaryDto(
                100L, "New Title", "orders", null, null, LocalDateTime.now(), LocalDateTime.now(), 3L
        );
        when(conversationService.renameConversation(eq(100L), any(CopilotConversationRenameRequestDto.class)))
                .thenReturn(updated);

        CopilotConversationRenameRequestDto request = new CopilotConversationRenameRequestDto("New Title");

        mockMvc.perform(patch("/api/ai/conversations/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.title").value("New Title"));
    }

    @Test
    @DisplayName("PATCH /api/ai/conversations/{id}: Blank title is rejected with 400 Bad Request")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void rename_blankTitle_returns400() throws Exception {
        CopilotConversationRenameRequestDto request = new CopilotConversationRenameRequestDto("   ");

        mockMvc.perform(patch("/api/ai/conversations/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.title", containsString("Conversation title is mandatory")));
    }

    @Test
    @DisplayName("PATCH /api/ai/conversations/{id}: Oversized title (>150 chars) is rejected with 400 Bad Request")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void rename_oversizedTitle_returns400() throws Exception {
        String longTitle = "A".repeat(151);
        CopilotConversationRenameRequestDto request = new CopilotConversationRenameRequestDto(longTitle);

        mockMvc.perform(patch("/api/ai/conversations/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.title", containsString("Conversation title must not exceed 150 characters")));
    }

    @Test
    @DisplayName("PATCH /api/ai/conversations/{id}: Unexpected JSON properties are rejected with 400 Bad Request")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void rename_unexpectedProperties_returns400() throws Exception {
        String payload = """
                {
                    "title": "Renamed Title",
                    "actorId": 123
                }
                """;

        mockMvc.perform(patch("/api/ai/conversations/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed JSON request or invalid enum value"));
    }

    @Test
    @DisplayName("PATCH /api/ai/conversations/{id}: Renaming unowned thread returns 404 Not Found")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void rename_unowned_returns404() throws Exception {
        when(conversationService.renameConversation(eq(999L), any(CopilotConversationRenameRequestDto.class)))
                .thenThrow(new ResourceNotFoundException("Conversation not found with id: 999"));

        CopilotConversationRenameRequestDto request = new CopilotConversationRenameRequestDto("Attempted Rename");

        mockMvc.perform(patch("/api/ai/conversations/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // =========================================================================
    // DELETE /api/ai/conversations/{id}
    // =========================================================================

    @Test
    @DisplayName("DELETE /api/ai/conversations/{id}: Unauthenticated request is rejected with 401")
    void delete_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/api/ai/conversations/100"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /api/ai/conversations/{id}: Authenticated owner deletes thread (204 No Content)")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void delete_owned_returns204() throws Exception {
        doNothing().when(conversationService).deleteConversation(100L);

        mockMvc.perform(delete("/api/ai/conversations/100"))
                .andExpect(status().isNoContent());

        verify(conversationService).deleteConversation(100L);
    }

    @Test
    @DisplayName("DELETE /api/ai/conversations/{id}: Deleting unowned thread returns 404 Not Found")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void delete_unowned_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Conversation not found with id: 999"))
                .when(conversationService).deleteConversation(999L);

        mockMvc.perform(delete("/api/ai/conversations/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // =========================================================================
    // POST /api/ai/conversations/{conversationId}/messages (Message Submission)
    // =========================================================================

    @Test
    @DisplayName("POST /api/ai/conversations/{id}/messages: Unauthenticated submission returns 401")
    void submitMessage_unauthenticated_returns401() throws Exception {
        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto("req-001", "What is MO-2026-0001?");

        mockMvc.perform(post("/api/ai/conversations/100/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/ai/conversations/{id}/messages: Authenticated submission returns 200 with response DTO")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void submitMessage_authenticated_returns200() throws Exception {
        CopilotMessageDto userMsg = new CopilotMessageDto(1L, 10L, CopilotMessageRole.USER, "What is MO-2026-0001?",
                null, null, null, List.of(), CopilotRequestStatus.COMPLETED, LocalDateTime.now());
        CopilotMessageDto assistantMsg = new CopilotMessageDto(2L, 10L, CopilotMessageRole.ASSISTANT, "MO-2026-0001 is on schedule.",
                "gpt-6-luna", "MANUFACTURING_ORDER", "MO-2026-0001",
                List.of(new AiSourceReferenceDto("MANUFACTURING_ORDER", "MO-2026-0001", "Order MO-2026-0001", "orders", "Grounded record")),
                CopilotRequestStatus.COMPLETED, LocalDateTime.now());

        CopilotMessageResponseDto responseDto = new CopilotMessageResponseDto(100L, 10L, "req-001", userMsg, assistantMsg);

        when(conversationService.submitMessage(eq(100L), any(CopilotSubmissionRequestDto.class)))
                .thenReturn(responseDto);

        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto("req-001", "What is MO-2026-0001?",
                "orders", "MANUFACTURING_ORDER", "MO-2026-0001");

        mockMvc.perform(post("/api/ai/conversations/100/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(100L))
                .andExpect(jsonPath("$.requestId").value(10L))
                .andExpect(jsonPath("$.clientRequestId").value("req-001"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.userMessage.content").value("What is MO-2026-0001?"))
                .andExpect(jsonPath("$.assistantMessage.content").value("MO-2026-0001 is on schedule."))
                .andExpect(jsonPath("$.assistantMessage.sources[0].recordIdentifier").value("MO-2026-0001"));
    }

    @Test
    @DisplayName("POST /api/ai/conversations/{id}/messages: Blank query returns 400 Bad Request")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void submitMessage_blankQuery_returns400() throws Exception {
        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto("req-001", "   ");

        mockMvc.perform(post("/api/ai/conversations/100/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/ai/conversations/{id}/messages: Blank clientRequestId returns 400 Bad Request")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void submitMessage_blankClientRequestId_returns400() throws Exception {
        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto("   ", "Valid query");

        mockMvc.perform(post("/api/ai/conversations/100/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/ai/conversations/{id}/messages: Unknown JSON fields are rejected with 400 Bad Request")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void submitMessage_unknownField_returns400() throws Exception {
        String payloadWithUnknownField = """
                {
                    "clientRequestId": "req-001",
                    "query": "Valid query",
                    "userId": 999,
                    "role": "ADMIN"
                }
                """;

        mockMvc.perform(post("/api/ai/conversations/100/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadWithUnknownField))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/ai/conversations/{id}/messages: Submitting to unowned conversation returns 404 Not Found")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void submitMessage_notOwned_returns404() throws Exception {
        when(conversationService.submitMessage(eq(999L), any(CopilotSubmissionRequestDto.class)))
                .thenThrow(new ResourceNotFoundException("Conversation not found with id: 999"));

        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto("req-001", "Valid query");

        mockMvc.perform(post("/api/ai/conversations/999/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("POST /api/ai/conversations/{id}/messages: Reused clientRequestId with different fingerprint returns 409 Conflict")
    @WithMockUser(username = "operator_1", roles = "OPERATOR")
    void submitMessage_conflictFingerprint_returns409() throws Exception {
        when(conversationService.submitMessage(eq(100L), any(CopilotSubmissionRequestDto.class)))
                .thenThrow(new DuplicateResourceException("Client request ID 'req-conflict' has already been used with different request parameters"));

        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto("req-conflict", "Different query text");

        mockMvc.perform(post("/api/ai/conversations/100/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST /api/ai/conversations/{id}/messages: All 5 MES roles retain access")
    @WithMockUser(username = "admin_user", roles = "ADMIN")
    void submitMessage_adminRole_returns200() throws Exception {
        assertRoleHasAccess(100L);
    }

    @Test
    @DisplayName("POST /api/ai/conversations/{id}/messages: PRODUCTION_SUPERVISOR has access")
    @WithMockUser(username = "supervisor_user", roles = "PRODUCTION_SUPERVISOR")
    void submitMessage_supervisorRole_returns200() throws Exception {
        assertRoleHasAccess(100L);
    }

    @Test
    @DisplayName("POST /api/ai/conversations/{id}/messages: OPERATOR has access")
    @WithMockUser(username = "operator_user", roles = "OPERATOR")
    void submitMessage_operatorRole_returns200() throws Exception {
        assertRoleHasAccess(100L);
    }

    @Test
    @DisplayName("POST /api/ai/conversations/{id}/messages: QC_ANALYST has access")
    @WithMockUser(username = "qc_user", roles = "QC_ANALYST")
    void submitMessage_qcRole_returns200() throws Exception {
        assertRoleHasAccess(100L);
    }

    @Test
    @DisplayName("POST /api/ai/conversations/{id}/messages: QA_REVIEWER has access")
    @WithMockUser(username = "qa_user", roles = "QA_REVIEWER")
    void submitMessage_qaRole_returns200() throws Exception {
        assertRoleHasAccess(100L);
    }

    private void assertRoleHasAccess(Long conversationId) throws Exception {
        CopilotMessageDto userMsg = new CopilotMessageDto(1L, 10L, CopilotMessageRole.USER, "Question",
                null, null, null, List.of(), CopilotRequestStatus.COMPLETED, LocalDateTime.now());
        CopilotMessageDto assistantMsg = new CopilotMessageDto(2L, 10L, CopilotMessageRole.ASSISTANT, "Answer",
                "gpt-6-luna", null, null, List.of(), CopilotRequestStatus.COMPLETED, LocalDateTime.now());
        CopilotMessageResponseDto responseDto = new CopilotMessageResponseDto(conversationId, 10L, "req-role", userMsg, assistantMsg);

        when(conversationService.submitMessage(eq(conversationId), any(CopilotSubmissionRequestDto.class)))
                .thenReturn(responseDto);

        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto("req-role", "Question");

        mockMvc.perform(post("/api/ai/conversations/" + conversationId + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isOk());
    }
}
