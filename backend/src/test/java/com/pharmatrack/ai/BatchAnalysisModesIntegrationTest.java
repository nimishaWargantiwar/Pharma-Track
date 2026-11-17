package com.pharmatrack.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.client.LlmClient;
import com.pharmatrack.ai.client.LlmResponse;
import com.pharmatrack.ai.dto.BatchAnalysisReportRequestDto;
import com.pharmatrack.ai.entity.BatchAnalysisFocusArea;
import com.pharmatrack.ai.entity.BatchAnalysisMode;
import com.pharmatrack.ai.entity.BatchAnalysisReport;
import com.pharmatrack.ai.repository.BatchAnalysisReportRepository;
import com.pharmatrack.entity.*;
import com.pharmatrack.repository.*;
import com.pharmatrack.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_modes_it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DisplayName("Phase 4F.1 - Batch Analysis Modes & Focus Areas Integration Suite")
class BatchAnalysisModesIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(500);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private ManufacturingOrderRepository orderRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private BatchExecutionStepRepository executionStepRepository;

    @Autowired
    private QualityCheckRepository qualityCheckRepository;

    @Autowired
    private BatchAnalysisReportRepository reportRepository;

    @MockBean
    private LlmClient llmClient;

    private UserAccount supervisorUser;
    private String supervisorToken;
    private Batch testBatch;

    @BeforeEach
    void setUp() {
        int n = SEQ.incrementAndGet();

        supervisorUser = userAccountRepository.save(new UserAccount(
                "sup_modes_" + n,
                passwordEncoder.encode("Password123!"),
                "Supervisor Modes " + n,
                Role.PRODUCTION_SUPERVISOR,
                true
        ));
        supervisorToken = jwtService.generateToken(supervisorUser);

        Product product = productRepository.save(new Product(
                "PRD-MODE-" + n, "Paracetamol Modes " + n, "500mg", "Tablet", "Desc", ProductStatus.ACTIVE
        ));

        Recipe recipe = new Recipe(
                "RCP-MODE-" + n, product, 1, new BigDecimal("100.0000"), "kg", RecipeStatus.APPROVED, "Desc"
        );
        RecipeStep step1 = new RecipeStep(1, "Dispensing", "Dispense ingredients");
        recipe.addStep(step1);
        recipe = recipeRepository.save(recipe);

        LocalDate today = LocalDate.now();
        ManufacturingOrder order = orderRepository.save(new ManufacturingOrder(
                "MO-MODE-" + n, product, recipe, 1, new BigDecimal("100.0000"), "kg", 1,
                OrderStatus.IN_PROGRESS, today, today.plusDays(7), "Supervisor Notes"
        ));

        testBatch = new Batch(
                "BAT-MODE-" + n + "-01", order, new BigDecimal("100.0000"), "kg",
                BatchStatus.COMPLETED, today, today.plusYears(2)
        );
        testBatch.setActualYield(new BigDecimal("98.5000"));
        testBatch.setQaStatus(BatchQualityStatus.PENDING_QA);
        testBatch.setQrCode("QR-BAT-MODE-" + n + "-01");
        testBatch = batchRepository.saveAndFlush(testBatch);

        BatchExecutionStep execStep = new BatchExecutionStep(
                testBatch, recipe.getSteps().get(0), 1, "Dispensing", "Dispense ingredients", ExecutionStepStatus.COMPLETED
        );
        execStep.setActualValue("50.0 kg");
        executionStepRepository.saveAndFlush(execStep);

        QualityCheck qc = new QualityCheck(
                testBatch, "Assay", "98.0% - 102.0%", "99.2%", "%", QualityCheckResult.PASS,
                true, "qc_analyst", LocalDateTime.now(), "Pass"
        );
        qualityCheckRepository.saveAndFlush(qc);

        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.generate(any(), any(), anyInt())).thenReturn(
                new LlmResponse(createValid12SectionJson(), "gpt-4o", "resp-modes-it", 400, 800, 1200)
        );
        when(llmClient.generate(any(), any())).thenReturn(
                new LlmResponse(createValid12SectionJson(), "gpt-4o", "resp-modes-it", 400, 800, 1200)
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Default request without mode fields defaults to FULL mode and persists correctly")
    void testDefaultRequestYieldsFullMode() throws Exception {
        String clientRequestId = "req-def-" + UUID.randomUUID();
        String jsonPayload = "{\"clientRequestId\": \"" + clientRequestId + "\"}";

        MvcResult result = mockMvc.perform(post("/api/batches/{batchId}/analysis-reports", testBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisMode").value("FULL"))
                .andExpect(jsonPath("$.focusAreas", hasSize(0)))
                .andExpect(jsonPath("$.focusSummary").doesNotExist())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        Long reportId = responseNode.get("id").asLong();

        BatchAnalysisReport saved = reportRepository.findById(reportId).orElseThrow();
        assertThat(saved.getAnalysisMode()).isEqualTo(BatchAnalysisMode.FULL);
        assertThat(saved.getFocusAreasJson()).isNull();
        assertThat(saved.getFocusSummary()).isNull();
        assertThat(saved.getFocusAreas()).isEmpty();
    }

    @Test
    @DisplayName("Explicit FULL mode succeeds and rejects focus areas if supplied")
    void testFullModeValidation() throws Exception {
        // Explicit FULL mode without focus areas -> 200 OK
        String clientReq1 = "req-full-" + UUID.randomUUID();
        String validFullJson = """
                {
                  "clientRequestId": "%s",
                  "analysisMode": "FULL"
                }
                """.formatted(clientReq1);

        mockMvc.perform(post("/api/batches/{batchId}/analysis-reports", testBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validFullJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisMode").value("FULL"));

        // FULL mode with focus areas -> 400 Bad Request
        String clientReq2 = "req-full-err-" + UUID.randomUUID();
        String invalidFullJson = """
                {
                  "clientRequestId": "%s",
                  "analysisMode": "FULL",
                  "focusAreas": ["MANUFACTURING_YIELD"]
                }
                """.formatted(clientReq2);

        mockMvc.perform(post("/api/batches/{batchId}/analysis-reports", testBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidFullJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Valid FOCUSED mode persists focus areas and summary correctly")
    void testFocusedModeGenerationAndRetrieval() throws Exception {
        String clientRequestId = "req-foc-" + UUID.randomUUID();
        String jsonPayload = """
                {
                  "clientRequestId": "%s",
                  "analysisMode": "FOCUSED",
                  "focusAreas": ["MANUFACTURING_YIELD", "QC_INSPECTION"]
                }
                """.formatted(clientRequestId);

        MvcResult result = mockMvc.perform(post("/api/batches/{batchId}/analysis-reports", testBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisMode").value("FOCUSED"))
                .andExpect(jsonPath("$.focusAreas", hasSize(2)))
                .andExpect(jsonPath("$.focusAreas[0]").value("MANUFACTURING_YIELD"))
                .andExpect(jsonPath("$.focusAreas[1]").value("QC_INSPECTION"))
                .andExpect(jsonPath("$.focusSummary").value("Manufacturing Yield, QC Inspection"))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        Long reportId = responseNode.get("id").asLong();

        // Verify GET report detail with strict auxiliary data isolation
        mockMvc.perform(get("/api/batches/{batchId}/analysis-reports/{reportId}", testBatch.getId(), reportId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisMode").value("FOCUSED"))
                .andExpect(jsonPath("$.focusSummary").value("Manufacturing Yield, QC Inspection"))
                .andExpect(jsonPath("$.structuredAnalysis.plannedVsActualMetrics").isNotEmpty())
                .andExpect(jsonPath("$.structuredAnalysis.qcInspectionAnalysis").isNotEmpty())
                .andExpect(jsonPath("$.structuredAnalysis.ebrStepEvaluation").doesNotExist())
                .andExpect(jsonPath("$.structuredAnalysis.materialTraceability").doesNotExist())
                .andExpect(jsonPath("$.structuredAnalysis.deviationsAndCapa").doesNotExist())
                .andExpect(jsonPath("$.structuredFindings", hasSize(2)))
                .andExpect(jsonPath("$.structuredFindings[0].focusArea").value("MANUFACTURING_YIELD"))
                .andExpect(jsonPath("$.structuredFindings[1].focusArea").value("QC_INSPECTION"))
                .andExpect(jsonPath("$.missingRecordAssessments", hasSize(4)))
                .andExpect(jsonPath("$.missingRecordAssessments[*].recordType", not(hasItem("eBR Execution Steps"))))
                .andExpect(jsonPath("$.missingRecordAssessments[*].recordType", not(hasItem("Material Issue & Lot Traceability"))))
                .andExpect(jsonPath("$.linkedRecommendations", hasSize(0)))
                .andExpect(jsonPath("$.advisoryRecommendations", hasSize(0)));

        // Verify GET report history summaries
        mockMvc.perform(get("/api/batches/{batchId}/analysis-reports", testBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].analysisMode").value("FOCUSED"))
                .andExpect(jsonPath("$[0].focusSummary").value("Manufacturing Yield, QC Inspection"))
                .andExpect(jsonPath("$[0].focusAreas", hasSize(2)));
    }

    @Test
    @DisplayName("Rejects FOCUSED mode with empty, duplicate, or invalid focus areas")
    void testFocusedModeRejections() throws Exception {
        // Empty focus areas -> 400
        String reqEmpty = "req-empty-" + UUID.randomUUID();
        mockMvc.perform(post("/api/batches/{batchId}/analysis-reports", testBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId": "%s", "analysisMode": "FOCUSED", "focusAreas": []}
                                """.formatted(reqEmpty)))
                .andExpect(status().isBadRequest());

        // Duplicate focus areas -> 400
        String reqDup = "req-dup-" + UUID.randomUUID();
        mockMvc.perform(post("/api/batches/{batchId}/analysis-reports", testBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId": "%s", "analysisMode": "FOCUSED", "focusAreas": ["QC_INSPECTION", "QC_INSPECTION"]}
                                """.formatted(reqDup)))
                .andExpect(status().isBadRequest());

        // Invalid enum string -> 400
        String reqInvalid = "req-inv-" + UUID.randomUUID();
        mockMvc.perform(post("/api/batches/{batchId}/analysis-reports", testBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientRequestId": "%s", "analysisMode": "FOCUSED", "focusAreas": ["UNKNOWN_AREA"]}
                                """.formatted(reqInvalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request or invalid enum value"));
    }

    @Test
    @DisplayName("Scope mismatch on reused clientRequestId triggers 409 Conflict")
    void testScopeMismatchConflict() throws Exception {
        String sharedClientRequestId = "req-scope-conflict-" + UUID.randomUUID();

        // 1. Initial successful generation with FOCUSED mode
        String firstPayload = """
                {
                  "clientRequestId": "%s",
                  "analysisMode": "FOCUSED",
                  "focusAreas": ["MANUFACTURING_YIELD"]
                }
                """.formatted(sharedClientRequestId);

        mockMvc.perform(post("/api/batches/{batchId}/analysis-reports", testBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisMode").value("FOCUSED"));

        // 2. Replay with same clientRequestId and SAME scope -> returns 200 OK (idempotent replay)
        mockMvc.perform(post("/api/batches/{batchId}/analysis-reports", testBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisMode").value("FOCUSED"));

        // 3. Replay with same clientRequestId but DIFFERENT mode (FULL) -> 409 Conflict
        String modeMismatchPayload = """
                {
                  "clientRequestId": "%s",
                  "analysisMode": "FULL"
                }
                """.formatted(sharedClientRequestId);

        mockMvc.perform(post("/api/batches/{batchId}/analysis-reports", testBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modeMismatchPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("was already used for analysis mode FOCUSED, but requested mode is FULL")));

        // 4. Replay with same clientRequestId, same mode (FOCUSED), but DIFFERENT focus areas -> 409 Conflict
        String areaMismatchPayload = """
                {
                  "clientRequestId": "%s",
                  "analysisMode": "FOCUSED",
                  "focusAreas": ["QC_INSPECTION"]
                }
                """.formatted(sharedClientRequestId);

        mockMvc.perform(post("/api/batches/{batchId}/analysis-reports", testBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(areaMismatchPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("was already used with different focus areas")));
    }

    private static String createValid12SectionJson() {
        return """
                {
                  "executiveSummary": "Batch executed within standard operational limits with a 98.5% yield.",
                  "batchStatusAndProgress": "Manufacturing status is COMPLETED. Execution finalized without deviations.",
                  "plannedVsActualMetrics": "Planned size was 100 kg, actual yield 98.5 kg, variance -1.5 kg.",
                  "materialTraceability": "All active lots consumed in compliance with recipe specifications.",
                  "ebrStepEvaluation": "All execution steps were signed off by authorized personnel.",
                  "qcInspectionAnalysis": "In-process quality testing passed all acceptance criteria.",
                  "qaDispositionStatus": "Authoritative QA Disposition: PENDING_QA (Not Released).",
                  "deviationsAndCapa": "Zero deviations recorded during production.",
                  "chronologicalAuditAnalysis": "Sequential progression verified.",
                  "missingRecords": "None. All mandatory fields recorded in MES.",
                  "riskObservations": "Low operational risk based on verified records.",
                  "followUpRecommendations": "Submit completed dossier to QA for formal disposition review."
                }
                """;
    }
}
