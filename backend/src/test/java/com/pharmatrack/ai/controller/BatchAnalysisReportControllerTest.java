package com.pharmatrack.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.dto.ArchiveReportRequestDto;
import com.pharmatrack.ai.dto.BatchAnalysisMetricsDto;
import com.pharmatrack.ai.dto.BatchAnalysisReportRequestDto;
import com.pharmatrack.ai.dto.BatchAnalysisReportResponseDto;
import com.pharmatrack.ai.dto.BatchAnalysisReportSummaryDto;
import com.pharmatrack.ai.dto.BatchAnalysisSectionsDto;
import com.pharmatrack.ai.dto.PermanentDeleteReportRequestDto;
import com.pharmatrack.ai.entity.BatchAnalysisReportStatus;
import com.pharmatrack.ai.exception.BatchAnalysisConflictException;
import com.pharmatrack.ai.exception.BatchAnalysisValidationException;
import com.pharmatrack.ai.service.BatchAnalysisReportService;
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

import java.math.BigDecimal;
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
 * Unit and security tests for {@link BatchAnalysisReportController} testing authentication boundaries,
 * RBAC authorization, DTO validation, unknown field rejection, and HTTP status codes.
 */
@WebMvcTest(BatchAnalysisReportController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@DisplayName("BatchAnalysisReportController REST & Security Tests")
class BatchAnalysisReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BatchAnalysisReportService reportService;

    // =========================================================================
    // 1. Authentication boundaries (401 for unauthenticated)
    // =========================================================================

    @Test
    @DisplayName("POST /api/batches/{id}/analysis-reports: Unauthenticated request is rejected with 401")
    void generate_unauthenticated_returns401() throws Exception {
        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("req-client-12345");

        mockMvc.perform(post("/api/batches/1/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("GET /api/batches/{id}/analysis-reports: Unauthenticated request is rejected with 401")
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/batches/1/analysis-reports"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("GET /api/batches/{id}/analysis-reports/{reportId}: Unauthenticated request is rejected with 401")
    void getDetail_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/batches/1/analysis-reports/10"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reportService);
    }

    // =========================================================================
    // 2. Role-Based Access Control (RBAC)
    // =========================================================================

    @Test
    @DisplayName("POST generate: PRODUCTION_SUPERVISOR is authorized (200 OK)")
    @WithMockUser(username = "supervisor_1", roles = "PRODUCTION_SUPERVISOR")
    void generate_supervisor_returns200() throws Exception {
        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("req-client-12345");
        BatchAnalysisReportResponseDto response = createSampleResponseDto(1L, "BAT-001");

        when(reportService.generateReport(eq("1"), any(BatchAnalysisReportRequestDto.class))).thenReturn(response);

        mockMvc.perform(post("/api/batches/1/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.batchNumber").value("BAT-001"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(reportService).generateReport(eq("1"), any(BatchAnalysisReportRequestDto.class));
    }

    @Test
    @DisplayName("POST generate: QA_REVIEWER is forbidden (403)")
    @WithMockUser(username = "qa_1", roles = "QA_REVIEWER")
    void generate_qaReviewer_returns403() throws Exception {
        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("req-client-12345");

        mockMvc.perform(post("/api/batches/1/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("POST generate: ADMIN is forbidden (403)")
    @WithMockUser(username = "admin_1", roles = "ADMIN")
    void generate_admin_returns403() throws Exception {
        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("req-client-12345");

        mockMvc.perform(post("/api/batches/1/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("POST generate: OPERATOR is forbidden (403)")
    @WithMockUser(username = "op_1", roles = "OPERATOR")
    void generate_operator_returns403() throws Exception {
        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("req-client-12345");

        mockMvc.perform(post("/api/batches/1/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("POST generate: QC_ANALYST is forbidden (403)")
    @WithMockUser(username = "qc_1", roles = "QC_ANALYST")
    void generate_qcAnalyst_returns403() throws Exception {
        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("req-client-12345");

        mockMvc.perform(post("/api/batches/1/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("GET history: PRODUCTION_SUPERVISOR, QA_REVIEWER, ADMIN can list; OPERATOR and QC_ANALYST are forbidden")
    @WithMockUser(username = "qa_1", roles = "QA_REVIEWER")
    void list_qaReviewer_returns200() throws Exception {
        BatchAnalysisReportSummaryDto summary = new BatchAnalysisReportSummaryDto(
                1L, 1L, "BAT-001", BatchAnalysisReportStatus.COMPLETED, "gpt-4", "req-123",
                "supervisor_1", LocalDateTime.now(), LocalDateTime.now(), new BigDecimal("98.50"), 0
        );
        when(reportService.getReportsForBatch(eq("1"), any(), any())).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/batches/1/analysis-reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].batchNumber").value("BAT-001"));
    }

    @Test
    @DisplayName("GET history: ADMIN can list reports")
    @WithMockUser(username = "admin_1", roles = "ADMIN")
    void list_admin_returns200() throws Exception {
        when(reportService.getReportsForBatch(eq("1"), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/batches/1/analysis-reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET history: OPERATOR is forbidden (403)")
    @WithMockUser(username = "op_1", roles = "OPERATOR")
    void list_operator_returns403() throws Exception {
        mockMvc.perform(get("/api/batches/1/analysis-reports"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("GET history: QC_ANALYST is forbidden (403)")
    @WithMockUser(username = "qc_1", roles = "QC_ANALYST")
    void list_qcAnalyst_returns403() throws Exception {
        mockMvc.perform(get("/api/batches/1/analysis-reports"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("GET detail: QA_REVIEWER and ADMIN can view details; OPERATOR and QC_ANALYST are forbidden")
    @WithMockUser(username = "qa_1", roles = "QA_REVIEWER")
    void getDetail_qaReviewer_returns200() throws Exception {
        BatchAnalysisReportResponseDto response = createSampleResponseDto(10L, "BAT-001");
        when(reportService.getReportById("1", 10L)).thenReturn(response);

        mockMvc.perform(get("/api/batches/1/analysis-reports/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.batchNumber").value("BAT-001"));
    }

    @Test
    @DisplayName("GET detail: OPERATOR is forbidden (403)")
    @WithMockUser(username = "op_1", roles = "OPERATOR")
    void getDetail_operator_returns403() throws Exception {
        mockMvc.perform(get("/api/batches/1/analysis-reports/10"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("GET detail: QC_ANALYST is forbidden (403)")
    @WithMockUser(username = "qc_1", roles = "QC_ANALYST")
    void getDetail_qcAnalyst_returns403() throws Exception {
        mockMvc.perform(get("/api/batches/1/analysis-reports/10"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    // =========================================================================
    // 3. Request validation and unexpected property rejection
    // =========================================================================

    @Test
    @DisplayName("POST generate: Rejects blank clientRequestId with 400 Bad Request")
    @WithMockUser(username = "sup_1", roles = "PRODUCTION_SUPERVISOR")
    void generate_blankClientRequestId_returns400() throws Exception {
        String json = "{\"clientRequestId\":\"\"}";

        mockMvc.perform(post("/api/batches/1/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    @DisplayName("POST generate: Rejects short clientRequestId (< 8 chars) with 400 Bad Request")
    @WithMockUser(username = "sup_1", roles = "PRODUCTION_SUPERVISOR")
    void generate_shortClientRequestId_returns400() throws Exception {
        String json = "{\"clientRequestId\":\"short\"}";

        mockMvc.perform(post("/api/batches/1/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    @DisplayName("POST generate: Rejects unexpected JSON properties (e.g., userId, role) via @JsonAnySetter with 400")
    @WithMockUser(username = "sup_1", roles = "PRODUCTION_SUPERVISOR")
    void generate_unexpectedProperty_returns400() throws Exception {
        String json = "{\"clientRequestId\":\"valid-client-id\",\"userId\":999}";

        mockMvc.perform(post("/api/batches/1/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST generate: Rejects supervisorNotes via @JsonAnySetter in V1 with 400")
    @WithMockUser(username = "sup_1", roles = "PRODUCTION_SUPERVISOR")
    void generate_supervisorNotesRejectedInV1_returns400() throws Exception {
        String json = "{\"clientRequestId\":\"valid-client-id\",\"supervisorNotes\":\"Extraneous notes\"}";

        mockMvc.perform(post("/api/batches/1/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST generate: Non-numeric batchId returns 400 Bad Request")
    @WithMockUser(username = "sup_1", roles = "PRODUCTION_SUPERVISOR")
    void generate_nonNumericBatchId_returns400() throws Exception {
        String json = "{\"clientRequestId\":\"valid-client-id\"}";

        mockMvc.perform(post("/api/batches/invalid-batch/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("batchId")));
    }

    // =========================================================================
    // 4. Resource isolation and error mapping
    // =========================================================================

    @Test
    @DisplayName("POST generate: Unknown batch returns 404 Not Found")
    @WithMockUser(username = "sup_1", roles = "PRODUCTION_SUPERVISOR")
    void generate_unknownBatch_returns404() throws Exception {
        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("req-client-12345");
        when(reportService.generateReport(eq("999"), any(BatchAnalysisReportRequestDto.class)))
                .thenThrow(new ResourceNotFoundException("Batch not found with id: 999"));

        mockMvc.perform(post("/api/batches/999/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Batch not found with id: 999"));
    }

    @Test
    @DisplayName("GET detail: Unknown report returns 404 Not Found")
    @WithMockUser(username = "sup_1", roles = "PRODUCTION_SUPERVISOR")
    void getDetail_unknownReport_returns404() throws Exception {
        when(reportService.getReportById("1", 999L))
                .thenThrow(new ResourceNotFoundException("Report not found with id: 999 for batch: 1"));

        mockMvc.perform(get("/api/batches/1/analysis-reports/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Report not found with id: 999 for batch: 1"));
    }

    @Test
    @DisplayName("POST generate: Concurrent conflict returns 409 Conflict")
    @WithMockUser(username = "sup_1", roles = "PRODUCTION_SUPERVISOR")
    void generate_concurrencyConflict_returns409() throws Exception {
        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("req-client-12345");
        when(reportService.generateReport(eq("1"), any(BatchAnalysisReportRequestDto.class)))
                .thenThrow(new BatchAnalysisConflictException("Another report generation is currently active for batch BAT-001"));

        mockMvc.perform(post("/api/batches/1/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Another report generation is currently active")));
    }

    @Test
    @DisplayName("POST generate: Model output compliance validation failure returns 502 Bad Gateway")
    @WithMockUser(username = "sup_1", roles = "PRODUCTION_SUPERVISOR")
    void generate_modelValidationFailure_returns502() throws Exception {
        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("req-client-12345");
        when(reportService.generateReport(eq("1"), any(BatchAnalysisReportRequestDto.class)))
                .thenThrow(new BatchAnalysisValidationException("Model output asserts unauthorized QA approval"));

        mockMvc.perform(post("/api/batches/1/analysis-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("AI batch analysis report generation failed output compliance validation."));
    }

    // =========================================================================
    // 5. Archival endpoint tests
    // =========================================================================

    @Test
    @DisplayName("PATCH archive: Unauthenticated request is rejected with 401")
    void archive_unauthenticated_returns401() throws Exception {
        ArchiveReportRequestDto request = new ArchiveReportRequestDto("Superseded by newer analysis");

        mockMvc.perform(patch("/api/batches/1/analysis-reports/10/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("PATCH archive: PRODUCTION_SUPERVISOR is authorized (200 OK)")
    @WithMockUser(username = "supervisor_1", roles = "PRODUCTION_SUPERVISOR")
    void archive_supervisor_returns200() throws Exception {
        ArchiveReportRequestDto request = new ArchiveReportRequestDto("Superseded by newer analysis");
        BatchAnalysisReportResponseDto response = createSampleResponseDto(10L, "BAT-001");
        response.setArchived(true);
        response.setArchivedAt(LocalDateTime.now());
        response.setArchivedByUsername("supervisor_1");
        response.setArchiveReason("Superseded by newer analysis");

        when(reportService.archiveReport(eq("1"), eq(10L), any(ArchiveReportRequestDto.class))).thenReturn(response);

        mockMvc.perform(patch("/api/batches/1/analysis-reports/10/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.archiveReason").value("Superseded by newer analysis"));

        verify(reportService).archiveReport(eq("1"), eq(10L), any(ArchiveReportRequestDto.class));
    }

    @Test
    @DisplayName("PATCH archive: ADMIN is authorized (200 OK)")
    @WithMockUser(username = "admin_1", roles = "ADMIN")
    void archive_admin_returns200() throws Exception {
        ArchiveReportRequestDto request = new ArchiveReportRequestDto("Superseded by admin");
        BatchAnalysisReportResponseDto response = createSampleResponseDto(10L, "BAT-001");
        response.setArchived(true);

        when(reportService.archiveReport(eq("1"), eq(10L), any(ArchiveReportRequestDto.class))).thenReturn(response);

        mockMvc.perform(patch("/api/batches/1/analysis-reports/10/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10));

        verify(reportService).archiveReport(eq("1"), eq(10L), any(ArchiveReportRequestDto.class));
    }

    @Test
    @DisplayName("PATCH archive: OPERATOR is forbidden (403)")
    @WithMockUser(username = "op_1", roles = "OPERATOR")
    void archive_operator_returns403() throws Exception {
        ArchiveReportRequestDto request = new ArchiveReportRequestDto("Valid reason");

        mockMvc.perform(patch("/api/batches/1/analysis-reports/10/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("PATCH archive: QC_ANALYST is forbidden (403)")
    @WithMockUser(username = "qc_1", roles = "QC_ANALYST")
    void archive_qcAnalyst_returns403() throws Exception {
        ArchiveReportRequestDto request = new ArchiveReportRequestDto("Valid reason");

        mockMvc.perform(patch("/api/batches/1/analysis-reports/10/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("PATCH archive: QA_REVIEWER is forbidden (403)")
    @WithMockUser(username = "qa_1", roles = "QA_REVIEWER")
    void archive_qaReviewer_returns403() throws Exception {
        ArchiveReportRequestDto request = new ArchiveReportRequestDto("Valid reason");

        mockMvc.perform(patch("/api/batches/1/analysis-reports/10/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("PATCH archive: Missing request body returns 400 Bad Request")
    @WithMockUser(username = "supervisor_1", roles = "PRODUCTION_SUPERVISOR")
    void archive_missingBody_returns400() throws Exception {
        mockMvc.perform(patch("/api/batches/1/analysis-reports/10/archive")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("PATCH archive: Blank or whitespace-only reason returns 400 Bad Request")
    @WithMockUser(username = "supervisor_1", roles = "PRODUCTION_SUPERVISOR")
    void archive_blankReason_returns400() throws Exception {
        String json = "{\"reason\":\"   \"}";

        mockMvc.perform(patch("/api/batches/1/analysis-reports/10/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("PATCH archive: Reason exceeding 500 characters returns 400 Bad Request")
    @WithMockUser(username = "supervisor_1", roles = "PRODUCTION_SUPERVISOR")
    void archive_excessiveReasonLength_returns400() throws Exception {
        String longReason = "A".repeat(501);
        String json = objectMapper.writeValueAsString(new ArchiveReportRequestDto(longReason));

        mockMvc.perform(patch("/api/batches/1/analysis-reports/10/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));

        verifyNoInteractions(reportService);
    }

    // =========================================================================
    // 6. Permanent Deletion Tests (DELETE /api/batches/{id}/analysis-reports/{reportId})
    // =========================================================================

    @Test
    @DisplayName("DELETE permanentDelete: Unauthenticated request is rejected with 401")
    void permanentDelete_unauthenticated_returns401() throws Exception {
        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto("Valid reason", "PERMANENTLY DELETE");

        mockMvc.perform(delete("/api/batches/1/analysis-reports/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("DELETE permanentDelete: ADMIN is authorized (204 No Content)")
    @WithMockUser(username = "admin_1", roles = "ADMIN")
    void permanentDelete_admin_returns204() throws Exception {
        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto("Local cleanup", "PERMANENTLY DELETE");

        mockMvc.perform(delete("/api/batches/1/analysis-reports/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        verify(reportService).permanentlyDeleteReport(eq("1"), eq(10L), any(PermanentDeleteReportRequestDto.class));
    }

    @Test
    @DisplayName("DELETE permanentDelete: PRODUCTION_SUPERVISOR is forbidden (403)")
    @WithMockUser(username = "supervisor_1", roles = "PRODUCTION_SUPERVISOR")
    void permanentDelete_supervisor_returns403() throws Exception {
        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto("Local cleanup", "PERMANENTLY DELETE");

        mockMvc.perform(delete("/api/batches/1/analysis-reports/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("DELETE permanentDelete: QA_REVIEWER is forbidden (403)")
    @WithMockUser(username = "qa_1", roles = "QA_REVIEWER")
    void permanentDelete_qaReviewer_returns403() throws Exception {
        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto("Local cleanup", "PERMANENTLY DELETE");

        mockMvc.perform(delete("/api/batches/1/analysis-reports/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("DELETE permanentDelete: OPERATOR is forbidden (403)")
    @WithMockUser(username = "op_1", roles = "OPERATOR")
    void permanentDelete_operator_returns403() throws Exception {
        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto("Local cleanup", "PERMANENTLY DELETE");

        mockMvc.perform(delete("/api/batches/1/analysis-reports/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("DELETE permanentDelete: QC_ANALYST is forbidden (403)")
    @WithMockUser(username = "qc_1", roles = "QC_ANALYST")
    void permanentDelete_qcAnalyst_returns403() throws Exception {
        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto("Local cleanup", "PERMANENTLY DELETE");

        mockMvc.perform(delete("/api/batches/1/analysis-reports/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("DELETE permanentDelete: Missing request body returns 400 Bad Request")
    @WithMockUser(username = "admin_1", roles = "ADMIN")
    void permanentDelete_missingBody_returns400() throws Exception {
        mockMvc.perform(delete("/api/batches/1/analysis-reports/10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("DELETE permanentDelete: Blank reason returns 400 Bad Request")
    @WithMockUser(username = "admin_1", roles = "ADMIN")
    void permanentDelete_blankReason_returns400() throws Exception {
        String json = "{\"reason\":\"   \",\"confirmationKeyword\":\"PERMANENTLY DELETE\"}";

        mockMvc.perform(delete("/api/batches/1/analysis-reports/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("DELETE permanentDelete: Reason exceeding 500 characters returns 400 Bad Request")
    @WithMockUser(username = "admin_1", roles = "ADMIN")
    void permanentDelete_excessiveReasonLength_returns400() throws Exception {
        String longReason = "A".repeat(501);
        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto(longReason, "PERMANENTLY DELETE");

        mockMvc.perform(delete("/api/batches/1/analysis-reports/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("DELETE permanentDelete: Invalid confirmation keyword returns 400 Bad Request")
    @WithMockUser(username = "admin_1", roles = "ADMIN")
    void permanentDelete_invalidKeyword_returns400() throws Exception {
        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto("Valid reason", "DELETE");

        mockMvc.perform(delete("/api/batches/1/analysis-reports/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));

        verifyNoInteractions(reportService);
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    private BatchAnalysisReportResponseDto createSampleResponseDto(Long id, String batchNumber) {
        BatchAnalysisReportResponseDto dto = new BatchAnalysisReportResponseDto();
        dto.setId(id);
        dto.setBatchId(1L);
        dto.setBatchNumber(batchNumber);
        dto.setStatus(BatchAnalysisReportStatus.COMPLETED);
        dto.setModel("gpt-4");
        dto.setClientRequestId("req-client-12345");
        dto.setGeneratedByUsername("supervisor_1");
        dto.setCreatedAt(LocalDateTime.now());
        dto.setCompletedAt(LocalDateTime.now());
        dto.setDeterministicMetrics(new BatchAnalysisMetricsDto(
                new BigDecimal("100.00"), new BigDecimal("98.50"), new BigDecimal("98.5000"),
                new BigDecimal("-1.5000"), 5, 2, 0
        ));
        dto.setStructuredAnalysis(new BatchAnalysisSectionsDto(
                "Executive Summary", "Batch Status", "Yield Metrics", "Materials", "EBR",
                "QC", "Authoritative QA Disposition: PENDING_QA", "Deviations", "Audit",
                "Missing Records", "Risks", "Recommendations"
        ));
        dto.setReportMarkdown("# Batch Analysis Report\n\nContent");
        return dto;
    }
}
