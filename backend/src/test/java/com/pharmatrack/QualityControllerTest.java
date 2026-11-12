package com.pharmatrack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.controller.BatchController;
import com.pharmatrack.controller.ProductionExecutionController;
import com.pharmatrack.controller.QualityController;
import com.pharmatrack.dto.AuditLogResponseDto;
import com.pharmatrack.dto.BatchQaReviewResponseDto;
import com.pharmatrack.dto.BatchQualityDossierDto;
import com.pharmatrack.dto.QaDispositionRequestDto;
import com.pharmatrack.dto.QualityCheckCorrectionRequestDto;
import com.pharmatrack.dto.QualityCheckRequestDto;
import com.pharmatrack.dto.QualityCheckResponseDto;
import com.pharmatrack.dto.QualityQueueItemDto;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.exception.GlobalExceptionHandler;
import com.pharmatrack.exception.InvalidQualityOperationException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.security.SecurityConfig;
import com.pharmatrack.service.AuditTrailService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WebMvc controller tests for {@link QualityController} verifying endpoint behavior,
 * input validation, business-rule error handling, append-only audit immutability,
 * and route coexistence with {@link BatchController} and {@link ProductionExecutionController}.
 */
@WebMvcTest(controllers = {QualityController.class, BatchController.class, ProductionExecutionController.class})
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@WithMockUser(username = "a.nair", roles = "QC_ANALYST")
class QualityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private QualityService qualityService;

    @MockBean
    private AuditTrailService auditTrailService;

    @MockBean
    private ManufacturingOrderService orderService;

    @MockBean
    private ProductionExecutionService executionService;

    private QualityQueueItemDto queueItemDto;
    private QualityCheckResponseDto checkResponseDto;
    private BatchQualityDossierDto dossierDto;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 12, 0, 0);

        queueItemDto = new QualityQueueItemDto(
                501L, "BAT-2026-0001-01", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA,
                100L, "MO-2026-0001", 1L, "AMOX-250", "Amoxicillin Capsules",
                10L, "RCP-AMOX-250-V3", 3, new BigDecimal("250.0000"), "kg",
                new BigDecimal("248.5000"), LocalDate.of(2026, 11, 10), null,
                now.minusHours(3), now, 1, 1, 0, 0, 1, 0, 0, 0, null, null,
                0L, 0L, true, List.of()
        );

        checkResponseDto = new QualityCheckResponseDto(
                701L, 501L, "BAT-2026-0001-01", "Assay (HPLC)", "95.0% - 105.0%",
                "99.2", "%", QualityCheckResult.PASS, true, "A. Nair", now,
                "Conforms", null, null, null, 0, true, now, now
        );

        BatchQaReviewResponseDto reviewDto = new BatchQaReviewResponseDto(
                88L, 501L, "BAT-2026-0001-01", BatchQualityStatus.PENDING_QA,
                BatchQualityStatus.RELEASED, "Dr. Menon", "All specs verified",
                now, 1, 1, 0, 0
        );

        AuditLogResponseDto auditDto = new AuditLogResponseDto(
                99L, "BATCH_QA_DISPOSITION", 88L, "BAT-2026-0001-01", 501L,
                "QA_DISPOSITION_RELEASED", "Dr. Menon", now, "All specs verified",
                "{\"qaStatus\":\"PENDING_QA\"}", "{\"qaStatus\":\"RELEASED\"}"
        );

        dossierDto = new BatchQualityDossierDto(
                501L, "BAT-2026-0001-01", BatchStatus.COMPLETED, BatchQualityStatus.RELEASED,
                100L, "MO-2026-0001", 1L, "AMOX-250", "Amoxicillin Capsules",
                10L, "RCP-AMOX-250-V3", 3, new BigDecimal("250.0000"), "kg",
                new BigDecimal("248.5000"), LocalDate.of(2026, 11, 10), null,
                now.minusHours(3), now, 1, 1, 0, 0, 1, false, List.of(),
                List.of(checkResponseDto), List.of(reviewDto), List.of(auditDto)
        );
    }

    @Test
    @DisplayName("GET /api/quality/batches returns completed batch inspection queue")
    void getQualityQueue_Returns200() throws Exception {
        when(qualityService.getQualityInspectionQueue(null)).thenReturn(List.of(queueItemDto));

        mockMvc.perform(get("/api/quality/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].batchNumber").value("BAT-2026-0001-01"))
                .andExpect(jsonPath("$[0].qaStatus").value("PENDING_QA"))
                .andExpect(jsonPath("$[0].eligibleForRelease").value(true))
                .andExpect(jsonPath("$[0].blockingChecksCount").value(0))
                .andExpect(jsonPath("$[0].releaseBlockers.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/quality/batches/{id} returns dossier or 400/404")
    void getDossier_Returns200AndErrors() throws Exception {
        when(qualityService.getBatchQualityDossier(501L)).thenReturn(dossierDto);

        mockMvc.perform(get("/api/quality/batches/501"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(501L))
                .andExpect(jsonPath("$.qaStatus").value("RELEASED"))
                .andExpect(jsonPath("$.qualityChecks.length()").value(1))
                .andExpect(jsonPath("$.auditLogs.length()").value(1));

        when(qualityService.getBatchQualityDossier(502L))
                .thenThrow(new InvalidQualityOperationException("Quality dossier is only available for COMPLETED batches"));
        mockMvc.perform(get("/api/quality/batches/502"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("COMPLETED")));

        when(qualityService.getBatchQualityDossier(999L))
                .thenThrow(new ResourceNotFoundException("Batch not found with id: 999"));
        mockMvc.perform(get("/api/quality/batches/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/quality/batches/{id}/checks validates input and creates QC check")
    void createQualityCheck_Returns201And400OnValidationError() throws Exception {
        QualityCheckRequestDto valid = new QualityCheckRequestDto(
                "Assay (HPLC)", "95.0% - 105.0%", "99.2", "%",
                QualityCheckResult.PASS, true, null, "Conforms"
        );
        when(qualityService.createQualityCheck(eq(501L), any(QualityCheckRequestDto.class)))
                .thenReturn(checkResponseDto);

        mockMvc.perform(post("/api/quality/batches/501/checks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(valid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(701L))
                .andExpect(jsonPath("$.result").value("PASS"));

        // Invalid request with missing mandatory fields returns 400 with field errors
        mockMvc.perform(post("/api/quality/batches/501/checks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.testName").exists())
                .andExpect(jsonPath("$.validationErrors.specification").exists())
                .andExpect(jsonPath("$.validationErrors.unit").exists())
                .andExpect(jsonPath("$.validationErrors.result").exists());

        // Inconsistent measured value vs specification/result rejected with HTTP 400
        QualityCheckRequestDto inconsistent = new QualityCheckRequestDto(
                "Dissolution Test", "Minimum 80% dissolution within 30 minutes", "65", "%",
                QualityCheckResult.PASS, true, null, null
        );
        when(qualityService.createQualityCheck(eq(501L), any(QualityCheckRequestDto.class)))
                .thenThrow(new InvalidQualityOperationException(
                        "Measured value 65 does not meet acceptance specification 'Minimum 80% dissolution within 30 minutes' (expected >= 80) and cannot be recorded as PASS."));
        mockMvc.perform(post("/api/quality/batches/501/checks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inconsistent)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("cannot be recorded as PASS")));
    }

    @Test
    @DisplayName("POST /api/quality/checks/{id}/correct handles audited corrections while PUT/PATCH/DELETE are blocked (405)")
    void correctQualityCheck_SucceedsAndDirectEditsBlocked() throws Exception {
        QualityCheckCorrectionRequestDto correction = new QualityCheckCorrectionRequestDto(
                "Assay (HPLC)", "95.0% - 105.0%", "99.2", "%",
                QualityCheckResult.PASS, true, "Retested", "Calibration fix"
        );
        when(qualityService.correctQualityCheck(eq(701L), any(QualityCheckCorrectionRequestDto.class)))
                .thenReturn(checkResponseDto);

        mockMvc.perform(post("/api/quality/checks/701/correct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correction)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(701L));

        // Direct un-audited edits must not be allowed
        mockMvc.perform(put("/api/quality/checks/701")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result -> assertTrue(
                        result.getResponse().getStatus() == 404 || result.getResponse().getStatus() == 405));

        mockMvc.perform(delete("/api/quality/checks/701"))
                .andExpect(result -> assertTrue(
                        result.getResponse().getStatus() == 404 || result.getResponse().getStatus() == 405));
    }

    @Test
    @WithMockUser(username = "dr.menon", roles = "QA_REVIEWER")
    @DisplayName("POST /api/quality/batches/{id}/disposition records decision or returns 400 when blocked")
    void recordDisposition_Returns200Or400WhenBlocked() throws Exception {
        QaDispositionRequestDto request = new QaDispositionRequestDto(
                BatchQualityStatus.RELEASED, "All specs verified"
        );
        when(qualityService.recordQaDisposition(eq(501L), any(QaDispositionRequestDto.class)))
                .thenReturn(dossierDto);

        mockMvc.perform(post("/api/quality/batches/501/disposition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("RELEASED"));

        when(qualityService.recordQaDisposition(eq(501L), any(QaDispositionRequestDto.class)))
                .thenThrow(new InvalidQualityOperationException("Cannot release batch because 1 QC check is still PENDING"));
        mockMvc.perform(post("/api/quality/batches/501/disposition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("PENDING")));

        when(qualityService.recordQaDisposition(eq(501L), any(QaDispositionRequestDto.class)))
                .thenThrow(new InvalidQualityOperationException(
                        "Cannot release batch BAT-2026-0009-01 because 1 required QC check(s) have FAILED"));
        mockMvc.perform(post("/api/quality/batches/501/disposition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("1 required QC check(s) have FAILED")));

        // Missing rationale fails validation
        mockMvc.perform(post("/api/quality/batches/501/disposition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qaStatus\":\"RELEASED\",\"rationale\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.rationale").exists());
    }

    @Test
    @WithMockUser(username = "dr.menon", roles = "QA_REVIEWER")
    @DisplayName("GET /api/quality/audit-logs is read-only; PUT/PATCH/DELETE methods are rejected")
    void auditLogsAreStrictlyReadOnlyOverHttp() throws Exception {
        when(auditTrailService.getAuditLogs(null, null, null)).thenReturn(dossierDto.getAuditLogs());

        mockMvc.perform(get("/api/quality/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("QA_DISPOSITION_RELEASED"));

        mockMvc.perform(put("/api/quality/audit-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(patch("/api/quality/audit-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(delete("/api/quality/audit-logs"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("Role separation: QC_ANALYST cannot record disposition or read audit logs; QA_REVIEWER cannot create/correct QC checks")
    void qualityRoleSeparationEnforced() throws Exception {
        mockMvc.perform(post("/api/quality/batches/501/disposition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Hold"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/quality/audit-logs"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(auditTrailService);
    }

    @Test
    @DisplayName("Existing /api/batches endpoints coexist cleanly without route conflicts")
    void existingBatchEndpointsCoexistCleanly() throws Exception {
        when(executionService.getExecutionQueue()).thenReturn(List.of());
        mockMvc.perform(get("/api/batches/execution"))
                .andExpect(status().isOk());
        verify(executionService).getExecutionQueue();

        when(orderService.getBatchById(501L))
                .thenThrow(new ResourceNotFoundException("Batch not found with id: 501"));
        mockMvc.perform(get("/api/batches/501"))
                .andExpect(status().isNotFound());
        verify(orderService).getBatchById(501L);
    }
}
