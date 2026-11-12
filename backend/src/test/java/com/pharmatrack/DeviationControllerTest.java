package com.pharmatrack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.controller.BatchController;
import com.pharmatrack.controller.DeviationController;
import com.pharmatrack.controller.ProductionExecutionController;
import com.pharmatrack.controller.QualityController;
import com.pharmatrack.dto.AuditLogResponseDto;
import com.pharmatrack.dto.BatchExecutionStepResponseDto;
import com.pharmatrack.dto.BatchLinkableRecordsDto;
import com.pharmatrack.dto.CancelCapaActionRequestDto;
import com.pharmatrack.dto.CapaActionResponseDto;
import com.pharmatrack.dto.CloseDeviationRequestDto;
import com.pharmatrack.dto.CompleteCapaActionRequestDto;
import com.pharmatrack.dto.CreateCapaActionRequestDto;
import com.pharmatrack.dto.CreateDeviationRequestDto;
import com.pharmatrack.dto.DeviationDetailDto;
import com.pharmatrack.dto.DeviationSummaryDto;
import com.pharmatrack.dto.QualityCheckResponseDto;
import com.pharmatrack.dto.QualityQueueItemDto;
import com.pharmatrack.dto.RecordRootCauseRequestDto;
import com.pharmatrack.dto.ReopenCapaRequestDto;
import com.pharmatrack.dto.StartCapaActionRequestDto;
import com.pharmatrack.dto.StartCapaRequestDto;
import com.pharmatrack.dto.StartInvestigationRequestDto;
import com.pharmatrack.dto.SubmitVerificationRequestDto;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.CapaStatus;
import com.pharmatrack.entity.CapaType;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.exception.GlobalExceptionHandler;
import com.pharmatrack.exception.InvalidDeviationOperationException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.security.SecurityConfig;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.DeviationService;
import com.pharmatrack.service.ManufacturingOrderService;
import com.pharmatrack.service.ProductionExecutionService;
import com.pharmatrack.service.QualityService;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WebMvc controller tests for {@link DeviationController} verifying all 15 endpoints,
 * Bean Validation error handling, invalid IDs and enum parameters, business-rule error responses,
 * blocked direct MUTATION HTTP methods, and route coexistence with Quality/Batch/Execution controllers.
 */
@WebMvcTest(controllers = {
        DeviationController.class,
        QualityController.class,
        BatchController.class,
        ProductionExecutionController.class
})
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@WithMockUser(username = "qa.reviewer", roles = "QA_REVIEWER")
class DeviationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DeviationService deviationService;

    @MockBean
    private QualityService qualityService;

    @MockBean
    private AuditTrailService auditTrailService;

    @MockBean
    private ManufacturingOrderService orderService;

    @MockBean
    private ProductionExecutionService executionService;

    private DeviationSummaryDto summaryDto;
    private DeviationDetailDto detailDto;
    private CapaActionResponseDto capaDto;
    private BatchLinkableRecordsDto linkableRecordsDto;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 12, 0, 0);
        LocalDate dueDate = LocalDate.of(2026, 11, 15);

        capaDto = new CapaActionResponseDto(
                301L, "CAPA-2026-0301", 101L, "DEV-2026-0101",
                CapaType.CORRECTIVE, "Recalibrate press turret",
                "Perform load-cell calibration and replace worn punch spring",
                "Eng. Kumar", dueDate, false, CapaStatus.OPEN,
                null, null, null, null, "Dr. Menon", now, now
        );

        AuditLogResponseDto devAuditDto = new AuditLogResponseDto(
                401L, "DEVIATION", 101L, "DEV-2026-0101", 501L,
                "DEVIATION_REPORTED", "A. Nair", now, "Reported OOS dissolution", null, "{}"
        );

        summaryDto = new DeviationSummaryDto(
                101L, "DEV-2026-0101", 501L, "BAT-2026-0001-01",
                BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA,
                "MO-2026-0001", "AMOX-250", "Amoxicillin Capsules",
                701L, "Dissolution Test", QualityCheckResult.FAIL,
                801L, 2, "Tablet Compression",
                401L, "Dissolution OOS (65% vs 80%)",
                DeviationCategory.QC_OOS_FAILURE, DeviationSeverity.CRITICAL, DeviationPriority.HIGH,
                DeviationStatus.OPEN, "A. Nair", now.minusMinutes(15), now, null,
                1, 1, 0, 0, 0, 0
        );

        detailDto = new DeviationDetailDto(
                101L, "DEV-2026-0101", 501L, "BAT-2026-0001-01",
                BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA,
                100L, "MO-2026-0001", 1L, "AMOX-250", "Amoxicillin Capsules",
                "RCP-AMOX-250-V3", 3,
                701L, "Dissolution Test", "Minimum 80%", "65", "%", QualityCheckResult.FAIL,
                801L, 2, "Tablet Compression", ExecutionStepStatus.COMPLETED,
                401L, "Dissolution OOS (65% vs 80%)",
                DeviationCategory.QC_OOS_FAILURE, DeviationSeverity.CRITICAL, DeviationPriority.HIGH,
                DeviationStatus.OPEN,
                "Observed 65% dissolution at 30 min.",
                "Quarantined finished pallets.",
                "A. Nair", now.minusMinutes(15), now,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                1, 1, 0, 0, 0, 0,
                false, List.of("Deviation must be in PENDING_VERIFICATION stage before closure"),
                List.of(capaDto), List.of(devAuditDto), now, now
        );

        QualityCheckResponseDto qcDto = new QualityCheckResponseDto(
                701L, 501L, "BAT-2026-0001-01", "Dissolution Test", "Minimum 80%", "65", "%",
                QualityCheckResult.FAIL, true, "A. Nair", now, "OOS", null, null, null, 0, true, now, now
        );
        BatchExecutionStepResponseDto stepDto = new BatchExecutionStepResponseDto(
                801L, 2, "Tablet Compression", "Target 12 kN", ExecutionStepStatus.COMPLETED,
                "12.1 kN", "Normal", "Operator 1", now.minusHours(2), now.minusHours(1)
        );
        linkableRecordsDto = new BatchLinkableRecordsDto(
                501L, "BAT-2026-0001-01", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA,
                "MO-2026-0001", "AMOX-250", "Amoxicillin Capsules",
                List.of(qcDto), List.of(stepDto), List.of(devAuditDto)
        );
    }

    @Test
    @DisplayName("GET /api/quality/deviations lists deviations with optional filters and rejects invalid enum filter")
    void getDeviations_ListsAndFilters() throws Exception {
        when(deviationService.getDeviations(null, null, null, null, null))
                .thenReturn(List.of(summaryDto));
        when(deviationService.getDeviations(
                DeviationStatus.OPEN, DeviationSeverity.CRITICAL,
                DeviationPriority.HIGH, DeviationCategory.QC_OOS_FAILURE, 501L))
                .thenReturn(List.of(summaryDto));

        mockMvc.perform(get("/api/quality/deviations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deviationNumber").value("DEV-2026-0101"))
                .andExpect(jsonPath("$[0].status").value("OPEN"));

        mockMvc.perform(get("/api/quality/deviations")
                        .param("status", "OPEN")
                        .param("severity", "CRITICAL")
                        .param("priority", "HIGH")
                        .param("category", "QC_OOS_FAILURE")
                        .param("batchId", "501"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batchId").value(501L));

        mockMvc.perform(get("/api/quality/deviations").param("status", "INVALID_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Invalid value")));
    }

    @Test
    @DisplayName("GET /api/quality/batches/{batchId}/deviations and linkable-records return 200 and validate IDs/status")
    void getBatchDeviationsAndLinkableRecords() throws Exception {
        when(deviationService.getBatchDeviations(501L)).thenReturn(List.of(summaryDto));
        when(deviationService.getLinkableRecordsForBatch(501L)).thenReturn(linkableRecordsDto);

        mockMvc.perform(get("/api/quality/batches/501/deviations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deviationNumber").value("DEV-2026-0101"));

        mockMvc.perform(get("/api/quality/batches/501/linkable-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNumber").value("BAT-2026-0001-01"))
                .andExpect(jsonPath("$.qualityChecks.length()").value(1))
                .andExpect(jsonPath("$.executionSteps.length()").value(1))
                .andExpect(jsonPath("$.auditLogs.length()").value(1));

        when(deviationService.getBatchDeviations(999L))
                .thenThrow(new ResourceNotFoundException("Batch not found with id: 999"));
        mockMvc.perform(get("/api/quality/batches/999/deviations"))
                .andExpect(status().isNotFound());

        when(deviationService.getLinkableRecordsForBatch(502L))
                .thenThrow(new InvalidDeviationOperationException("Deviations can only be reported against IN_PROGRESS or COMPLETED batches"));
        mockMvc.perform(get("/api/quality/batches/502/linkable-records"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("IN_PROGRESS or COMPLETED")));

        mockMvc.perform(get("/api/quality/batches/abc/deviations"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/quality/batches/abc/linkable-records"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/quality/deviations/{id} returns full detail or 404/400")
    void getDeviationById() throws Exception {
        when(deviationService.getDeviationById(101L)).thenReturn(detailDto);

        mockMvc.perform(get("/api/quality/deviations/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviationNumber").value("DEV-2026-0101"))
                .andExpect(jsonPath("$.capaActions.length()").value(1))
                .andExpect(jsonPath("$.auditLogs.length()").value(1));

        when(deviationService.getDeviationById(999L))
                .thenThrow(new ResourceNotFoundException("Deviation not found with id: 999"));
        mockMvc.perform(get("/api/quality/deviations/999"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/quality/deviations/not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/quality/deviations creates deviation (201) and validates request body (400)")
    void createDeviation_Returns201Or400() throws Exception {
        CreateDeviationRequestDto request = new CreateDeviationRequestDto(
                501L, 701L, 801L, 401L,
                "Dissolution OOS (65% vs 80%)",
                DeviationCategory.QC_OOS_FAILURE, DeviationSeverity.CRITICAL, DeviationPriority.HIGH,
                "Observed 65% dissolution at 30 minutes.", "Quarantined finished pallets.", null
        );
        when(deviationService.createDeviation(any(CreateDeviationRequestDto.class))).thenReturn(detailDto);

        mockMvc.perform(post("/api/quality/deviations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviationNumber").value("DEV-2026-0101"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        // Bean validation failure on empty payload
        mockMvc.perform(post("/api/quality/deviations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.batchId").exists())
                .andExpect(jsonPath("$.validationErrors.title").exists())
                .andExpect(jsonPath("$.validationErrors.category").exists())
                .andExpect(jsonPath("$.validationErrors.severity").exists())
                .andExpect(jsonPath("$.validationErrors.priority").exists())
                .andExpect(jsonPath("$.validationErrors.description").exists());

        // Invalid enum value
        mockMvc.perform(post("/api/quality/deviations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":501,\"category\":\"NOT_VALID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Malformed JSON")));
    }

    @Test
    @DisplayName("All 6 deviation lifecycle transition endpoints succeed and enforce Bean Validation / business rules")
    void allDeviationLifecycleEndpointsWorkAndValidate() throws Exception {
        // 1. start-investigation
        detailDto.setStatus(DeviationStatus.UNDER_INVESTIGATION);
        when(deviationService.startInvestigation(eq(101L), any(StartInvestigationRequestDto.class)))
                .thenReturn(detailDto);

        mockMvc.perform(post("/api/quality/deviations/101/start-investigation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartInvestigationRequestDto("Checking equipment telemetry"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_INVESTIGATION"));

        mockMvc.perform(post("/api/quality/deviations/101/start-investigation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.investigationSummary").exists());

        // 2. record-root-cause
        detailDto.setStatus(DeviationStatus.ROOT_CAUSE_IDENTIFIED);
        when(deviationService.recordRootCause(eq(101L), any(RecordRootCauseRequestDto.class)))
                .thenReturn(detailDto);

        mockMvc.perform(post("/api/quality/deviations/101/record-root-cause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RecordRootCauseRequestDto("Equipment", "Worn punch spring", "Single batch", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROOT_CAUSE_IDENTIFIED"));

        mockMvc.perform(post("/api/quality/deviations/101/record-root-cause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.rootCauseCategory").exists())
                .andExpect(jsonPath("$.validationErrors.rootCauseAnalysis").exists())
                .andExpect(jsonPath("$.validationErrors.impactAssessment").exists());

        // 3. start-capa
        detailDto.setStatus(DeviationStatus.CAPA_IN_PROGRESS);
        when(deviationService.startCapa(eq(101L), any(StartCapaRequestDto.class))).thenReturn(detailDto);

        mockMvc.perform(post("/api/quality/deviations/101/start-capa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaRequestDto("Executing CAPA plan"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPA_IN_PROGRESS"));

        mockMvc.perform(post("/api/quality/deviations/101/start-capa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capaPlanSummary\":\"" + "P".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.capaPlanSummary").exists());

        // 4. submit-verification
        detailDto.setStatus(DeviationStatus.PENDING_VERIFICATION);
        when(deviationService.submitForVerification(eq(101L), any(SubmitVerificationRequestDto.class)))
                .thenReturn(detailDto);

        mockMvc.perform(post("/api/quality/deviations/101/submit-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitVerificationRequestDto("All CAPAs complete"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));

        mockMvc.perform(post("/api/quality/deviations/101/submit-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submissionNotes\":\"" + "N".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.submissionNotes").exists());

        // 5. reopen-capa
        detailDto.setStatus(DeviationStatus.CAPA_IN_PROGRESS);
        when(deviationService.reopenCapa(eq(101L), any(ReopenCapaRequestDto.class))).thenReturn(detailDto);

        mockMvc.perform(post("/api/quality/deviations/101/reopen-capa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReopenCapaRequestDto("Need preventive SOP update"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPA_IN_PROGRESS"));

        mockMvc.perform(post("/api/quality/deviations/101/reopen-capa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.reason").exists());

        // 6. close
        detailDto.setStatus(DeviationStatus.CLOSED);
        when(deviationService.closeDeviation(eq(101L), any(CloseDeviationRequestDto.class)))
                .thenReturn(detailDto);

        mockMvc.perform(post("/api/quality/deviations/101/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CloseDeviationRequestDto("Effectiveness verified"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(post("/api/quality/deviations/101/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.verificationNotes").exists());

        when(deviationService.closeDeviation(eq(102L), any(CloseDeviationRequestDto.class)))
                .thenThrow(new InvalidDeviationOperationException("Cannot close deviation DEV-2026-0102: 1 active CAPA action(s) are still OPEN"));
        mockMvc.perform(post("/api/quality/deviations/102/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CloseDeviationRequestDto("Verified"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot close deviation")));
    }

    @Test
    @DisplayName("All CAPA creation and lifecycle endpoints succeed, validate inputs, and reject invalid IDs")
    void capaEndpointsWorkAndValidate() throws Exception {
        CreateCapaActionRequestDto createCapa = new CreateCapaActionRequestDto(
                CapaType.CORRECTIVE, "Recalibrate press turret", "Replace spring and calibrate",
                "Eng. Kumar", LocalDate.of(2026, 11, 15)
        );
        when(deviationService.createCapaAction(eq(101L), any(CreateCapaActionRequestDto.class)))
                .thenReturn(capaDto);

        mockMvc.perform(post("/api/quality/deviations/101/capas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCapa)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capaNumber").value("CAPA-2026-0301"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        mockMvc.perform(post("/api/quality/deviations/101/capas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.capaType").exists())
                .andExpect(jsonPath("$.validationErrors.title").exists())
                .andExpect(jsonPath("$.validationErrors.actionPlan").exists())
                .andExpect(jsonPath("$.validationErrors.ownerName").exists())
                .andExpect(jsonPath("$.validationErrors.dueDate").exists());

        // Start CAPA
        capaDto.setStatus(CapaStatus.IN_PROGRESS);
        when(deviationService.startCapaAction(eq(301L), any(StartCapaActionRequestDto.class))).thenReturn(capaDto);
        mockMvc.perform(post("/api/quality/capas/301/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaActionRequestDto("Work order opened"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mockMvc.perform(post("/api/quality/capas/301/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"" + "N".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.notes").exists());

        // Complete CAPA
        capaDto.setStatus(CapaStatus.COMPLETED);
        when(deviationService.completeCapaAction(eq(301L), any(CompleteCapaActionRequestDto.class))).thenReturn(capaDto);
        mockMvc.perform(post("/api/quality/capas/301/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteCapaActionRequestDto("Calibration cert CAL-411 uploaded"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(post("/api/quality/capas/301/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.completionEvidence").exists());

        // Cancel CAPA
        capaDto.setStatus(CapaStatus.CANCELLED);
        when(deviationService.cancelCapaAction(eq(301L), any(CancelCapaActionRequestDto.class))).thenReturn(capaDto);
        mockMvc.perform(post("/api/quality/capas/301/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelCapaActionRequestDto("Duplicate action"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/quality/capas/301/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.cancellationReason").exists());

        when(deviationService.startCapaAction(eq(999L), any(StartCapaActionRequestDto.class)))
                .thenThrow(new ResourceNotFoundException("CAPA action not found with id: 999"));
        mockMvc.perform(post("/api/quality/capas/999/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/quality/capas/xyz/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completionEvidence\":\"Done\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Direct un-audited PUT/PATCH/DELETE methods are rejected and existing Phase 1 / Batch routes coexist")
    void directMutationsBlockedAndExistingRoutesCoexist() throws Exception {
        mockMvc.perform(put("/api/quality/deviations/101")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch("/api/quality/deviations/101")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/quality/deviations/101"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(put("/api/quality/capas/301")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result -> assertTrue(
                        result.getResponse().getStatus() == 404 || result.getResponse().getStatus() == 405));
        mockMvc.perform(delete("/api/quality/capas/301"))
                .andExpect(result -> assertTrue(
                        result.getResponse().getStatus() == 404 || result.getResponse().getStatus() == 405));

        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 12, 0);
        QualityQueueItemDto qItem = new QualityQueueItemDto(
                501L, "BAT-2026-0001-01", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA,
                100L, "MO-2026-0001", 1L, "AMOX-250", "Amoxicillin Capsules",
                10L, "RCP-AMOX-250-V3", 3, new BigDecimal("250.0000"), "kg",
                new BigDecimal("248.5000"), LocalDate.of(2026, 11, 10), null,
                now.minusHours(3), now, 1, 1, 0, 0, 1, 0, null, null, 1, 1
        );
        when(qualityService.getQualityInspectionQueue(null)).thenReturn(List.of(qItem));
        mockMvc.perform(get("/api/quality/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].openDeviationsCount").value(1))
                .andExpect(jsonPath("$[0].totalDeviationsCount").value(1));

        when(executionService.getExecutionQueue()).thenReturn(List.of());
        mockMvc.perform(get("/api/batches/execution")).andExpect(status().isOk());
        verify(executionService).getExecutionQueue();

        when(orderService.getBatchById(501L)).thenThrow(new ResourceNotFoundException("Batch not found with id: 501"));
        mockMvc.perform(get("/api/batches/501")).andExpect(status().isNotFound());
        verify(orderService).getBatchById(501L);
    }

    @Test
    @DisplayName("Deviation and CAPA endpoints enforce Phase 6B RBAC matrix across all 5 roles and fail closed when unauthenticated")
    void deviationAndCapaEndpointsEnforceRbacMatrix() throws Exception {
        CreateDeviationRequestDto createDeviation = new CreateDeviationRequestDto(
                501L, 701L, 801L, 401L,
                "Dissolution OOS",
                DeviationCategory.QC_OOS_FAILURE, DeviationSeverity.CRITICAL, DeviationPriority.HIGH,
                "Observed 65% dissolution at 30 minutes.", "Quarantined finished pallets.", null
        );
        CreateCapaActionRequestDto createCapa = new CreateCapaActionRequestDto(
                CapaType.CORRECTIVE, "Recalibrate press turret", "Replace spring and calibrate",
                "Eng. Kumar", LocalDate.now().plusDays(7)
        );
        when(deviationService.createDeviation(any(CreateDeviationRequestDto.class))).thenReturn(detailDto);
        when(deviationService.createCapaAction(eq(101L), any(CreateCapaActionRequestDto.class))).thenReturn(capaDto);

        // Anonymous request -> 401 without hitting service
        mockMvc.perform(get("/api/quality/deviations").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/quality/deviations")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDeviation)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(deviationService);

        // OPERATOR can report a deviation (201) but gets 403 on investigation/RCA/CAPA/verification/closure
        mockMvc.perform(post("/api/quality/deviations")
                        .with(user("op1").roles("OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDeviation)))
                .andExpect(status().isCreated());
        verify(deviationService, times(1)).createDeviation(any(CreateDeviationRequestDto.class));

        mockMvc.perform(post("/api/quality/deviations/101/start-investigation")
                        .with(user("op1").roles("OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartInvestigationRequestDto("Summary"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/quality/deviations/101/capas")
                        .with(user("op1").roles("OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCapa)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/quality/deviations/101/start-capa")
                        .with(user("op1").roles("OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaRequestDto("Plan"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/quality/deviations/101/close")
                        .with(user("op1").roles("OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CloseDeviationRequestDto("Closed"))))
                .andExpect(status().isForbidden());

        // ADMIN gets 403 on deviation reporting and CAPA creation (strictly non-quality role)
        mockMvc.perform(post("/api/quality/deviations")
                        .with(user("admin1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDeviation)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/quality/deviations/101/capas")
                        .with(user("admin1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCapa)))
                .andExpect(status().isForbidden());

        // PRODUCTION_SUPERVISOR can create CAPAs (201), but PRODUCTION_SUPERVISOR & QC_ANALYST get 403 on QA-only gates
        mockMvc.perform(post("/api/quality/deviations/101/capas")
                        .with(user("sup1").roles("PRODUCTION_SUPERVISOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCapa)))
                .andExpect(status().isCreated());
        verify(deviationService, times(1)).createCapaAction(eq(101L), any(CreateCapaActionRequestDto.class));

        mockMvc.perform(post("/api/quality/deviations/101/capas")
                        .with(user("qc1").roles("QC_ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCapa)))
                .andExpect(status().isForbidden());

        for (String role : List.of("PRODUCTION_SUPERVISOR", "QC_ANALYST")) {
            mockMvc.perform(post("/api/quality/deviations/101/start-capa")
                            .with(user("u." + role).roles(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new StartCapaRequestDto("Plan"))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/quality/deviations/101/reopen-capa")
                            .with(user("u." + role).roles(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ReopenCapaRequestDto("Reopen"))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/quality/deviations/101/close")
                            .with(user("u." + role).roles(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new CloseDeviationRequestDto("Verify"))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/quality/capas/301/cancel")
                            .with(user("u." + role).roles(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new CancelCapaActionRequestDto("Cancel"))))
                    .andExpect(status().isForbidden());
        }

        verify(deviationService, never()).startInvestigation(any(), any());
        verify(deviationService, never()).startCapa(any(), any());
        verify(deviationService, never()).reopenCapa(any(), any());
        verify(deviationService, never()).closeDeviation(any(), any());
        verify(deviationService, never()).cancelCapaAction(any(), any());
    }
}

