package com.pharmatrack.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.client.LlmClientException;
import com.pharmatrack.ai.service.AiCopilotService;
import com.pharmatrack.dto.AiQueryRequestDto;
import com.pharmatrack.dto.AiQueryResponseDto;
import com.pharmatrack.dto.AiSourceReferenceDto;
import com.pharmatrack.exception.GlobalExceptionHandler;
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

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiCopilotController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@DisplayName("AiCopilotController REST & Security Tests")
class AiCopilotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiCopilotService aiCopilotService;

    private AiQueryResponseDto createSampleResponse(String orderNumber) {
        return new AiQueryResponseDto(
                "Order " + orderNumber + " is currently IN_PROGRESS.",
                "MANUFACTURING_ORDER",
                orderNumber,
                List.of(new AiSourceReferenceDto("MANUFACTURING_ORDER", orderNumber, orderNumber, "Manufacturing Orders")),
                "gpt-6-luna"
        );
    }

    @Test
    @DisplayName("Unauthenticated request is rejected with 401 Unauthorized")
    void query_unauthenticated_returns401() throws Exception {
        AiQueryRequestDto request = new AiQueryRequestDto("What is the status of MO-2026-0001?");

        mockMvc.perform(post("/api/ai/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ADMIN role can successfully submit AI query")
    @WithMockUser(username = "admin_user", roles = "ADMIN")
    void query_asAdmin_returns200() throws Exception {
        when(aiCopilotService.query(any(AiQueryRequestDto.class)))
                .thenReturn(createSampleResponse("MO-2026-0001"));

        AiQueryRequestDto request = new AiQueryRequestDto("What is the status of MO-2026-0001?");

        mockMvc.perform(post("/api/ai/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", containsString("MO-2026-0001 is currently IN_PROGRESS")))
                .andExpect(jsonPath("$.contextIdentifier").value("MO-2026-0001"))
                .andExpect(jsonPath("$.model").value("gpt-6-luna"));
    }

    @Test
    @DisplayName("PRODUCTION_SUPERVISOR role can successfully submit AI query")
    @WithMockUser(username = "supervisor_user", roles = "PRODUCTION_SUPERVISOR")
    void query_asProductionSupervisor_returns200() throws Exception {
        when(aiCopilotService.query(any(AiQueryRequestDto.class)))
                .thenReturn(createSampleResponse("MO-2026-0001"));

        AiQueryRequestDto request = new AiQueryRequestDto("What is the status of MO-2026-0001?");

        mockMvc.perform(post("/api/ai/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextIdentifier").value("MO-2026-0001"));
    }

    @Test
    @DisplayName("OPERATOR role can successfully submit AI query")
    @WithMockUser(username = "operator_user", roles = "OPERATOR")
    void query_asOperator_returns200() throws Exception {
        when(aiCopilotService.query(any(AiQueryRequestDto.class)))
                .thenReturn(createSampleResponse("MO-2026-0001"));

        AiQueryRequestDto request = new AiQueryRequestDto("What is the status of MO-2026-0001?");

        mockMvc.perform(post("/api/ai/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextIdentifier").value("MO-2026-0001"));
    }

    @Test
    @DisplayName("QC_ANALYST role can successfully submit AI query")
    @WithMockUser(username = "qc_analyst", roles = "QC_ANALYST")
    void query_asQcAnalyst_returns200() throws Exception {
        when(aiCopilotService.query(any(AiQueryRequestDto.class)))
                .thenReturn(createSampleResponse("MO-2026-0001"));

        AiQueryRequestDto request = new AiQueryRequestDto("What is the status of MO-2026-0001?");

        mockMvc.perform(post("/api/ai/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextIdentifier").value("MO-2026-0001"));
    }

    @Test
    @DisplayName("QA_REVIEWER role can successfully submit AI query")
    @WithMockUser(username = "qa_reviewer", roles = "QA_REVIEWER")
    void query_asQaReviewer_returns200() throws Exception {
        when(aiCopilotService.query(any(AiQueryRequestDto.class)))
                .thenReturn(createSampleResponse("MO-2026-0001"));

        AiQueryRequestDto request = new AiQueryRequestDto("What is the status of MO-2026-0001?");

        mockMvc.perform(post("/api/ai/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextIdentifier").value("MO-2026-0001"));
    }

    @Test
    @DisplayName("Blank query is rejected with 400 Bad Request by validation")
    @WithMockUser(username = "operator_user", roles = "OPERATOR")
    void query_blankQuery_returns400BadRequest() throws Exception {
        AiQueryRequestDto request = new AiQueryRequestDto("    ");

        mockMvc.perform(post("/api/ai/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.query", containsString("Query is mandatory")));
    }

    @Test
    @DisplayName("Oversized query exceeding 2000 characters is rejected with 400 Bad Request")
    @WithMockUser(username = "operator_user", roles = "OPERATOR")
    void query_oversizedQuery_returns400BadRequest() throws Exception {
        String longText = "A".repeat(2005);
        AiQueryRequestDto request = new AiQueryRequestDto(longText);

        mockMvc.perform(post("/api/ai/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.query", containsString("Query must not exceed 2000 characters")));
    }

    @Test
    @DisplayName("When AI Copilot is disabled, endpoint returns 503 Service Unavailable")
    @WithMockUser(username = "operator_user", roles = "OPERATOR")
    void query_aiDisabled_returns503ServiceUnavailable() throws Exception {
        when(aiCopilotService.query(any(AiQueryRequestDto.class)))
                .thenThrow(new LlmClientException(
                        LlmClientException.ErrorCategory.CONFIGURATION_ERROR,
                        "PharmaTrack AI Copilot is currently disabled in application configuration."
                ));

        AiQueryRequestDto request = new AiQueryRequestDto("Status of MO-2026-0001?");

        mockMvc.perform(post("/api/ai/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("PharmaTrack AI Copilot is currently unavailable."));
    }

    @Test
    @DisplayName("Provider timeout returns 504 Gateway Timeout")
    @WithMockUser(username = "operator_user", roles = "OPERATOR")
    void query_providerTimeout_returns504GatewayTimeout() throws Exception {
        when(aiCopilotService.query(any(AiQueryRequestDto.class)))
                .thenThrow(new LlmClientException(
                        LlmClientException.ErrorCategory.TIMEOUT,
                        "AI Copilot request timed out"
                ));

        AiQueryRequestDto request = new AiQueryRequestDto("Status of MO-2026-0001?");

        mockMvc.perform(post("/api/ai/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.status").value(504))
                .andExpect(jsonPath("$.message").value("AI service timed out while generating response."));
    }

    @Test
    @DisplayName("Provider rate limit returns 429 Too Many Requests")
    @WithMockUser(username = "operator_user", roles = "OPERATOR")
    void query_providerRateLimit_returns429TooManyRequests() throws Exception {
        when(aiCopilotService.query(any(AiQueryRequestDto.class)))
                .thenThrow(new LlmClientException(
                        LlmClientException.ErrorCategory.RATE_LIMITED,
                        429,
                        "Rate limit reached"
                ));

        AiQueryRequestDto request = new AiQueryRequestDto("Status of MO-2026-0001?");

        mockMvc.perform(post("/api/ai/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("AI service is temporarily busy. Please retry shortly."));
    }

    @Test
    @DisplayName("Provider server error returns 502 Bad Gateway")
    @WithMockUser(username = "operator_user", roles = "OPERATOR")
    void query_providerServerError_returns502BadGateway() throws Exception {
        when(aiCopilotService.query(any(AiQueryRequestDto.class)))
                .thenThrow(new LlmClientException(
                        LlmClientException.ErrorCategory.SERVER_ERROR,
                        500,
                        "Provider unavailable"
                ));

        AiQueryRequestDto request = new AiQueryRequestDto("Status of MO-2026-0001?");

        mockMvc.perform(post("/api/ai/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.message").value("AI service is temporarily unavailable. Please retry later."));
    }
}
