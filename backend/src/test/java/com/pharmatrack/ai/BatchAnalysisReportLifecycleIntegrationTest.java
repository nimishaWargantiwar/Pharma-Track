package com.pharmatrack.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.client.LlmClient;
import com.pharmatrack.ai.client.LlmClientException;
import com.pharmatrack.ai.client.LlmResponse;
import com.pharmatrack.ai.dto.BatchAnalysisReportRequestDto;
import com.pharmatrack.ai.dto.PermanentDeleteReportRequestDto;
import com.pharmatrack.ai.entity.BatchAnalysisReport;
import com.pharmatrack.ai.entity.BatchAnalysisReportStatus;
import com.pharmatrack.ai.repository.BatchAnalysisReportRepository;
import com.pharmatrack.entity.*;
import com.pharmatrack.repository.*;
import com.pharmatrack.security.JwtService;
import com.pharmatrack.service.AuditTrailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end integration tests for dedicated AI Batch Analysis Report REST APIs.
 *
 * <p>Tests full HTTP layer, Spring Security JWT authentication, RBAC authorization,
 * idempotency, cross-batch isolation, pagination, failure sanitization, and database persistence.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_batch_report_it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DisplayName("Batch Analysis Report - Lifecycle & REST Integration Suite")
class BatchAnalysisReportLifecycleIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(100);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @SpyBean
    private BatchAnalysisReportRepository reportRepository;

    @SpyBean
    private AuditTrailService auditTrailService;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private ManufacturingOrderRepository orderRepository;

    @Autowired
    private BatchExecutionStepRepository executionStepRepository;

    @Autowired
    private QualityCheckRepository qualityCheckRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @MockBean
    private LlmClient llmClient;

    private UserAccount supervisorUser;
    private UserAccount qaUser;
    private UserAccount adminUser;
    private UserAccount operatorUser;
    private UserAccount qcUser;

    private Batch testBatch1;
    private Batch testBatch2;

    @BeforeEach
    void setUp() {
        int n = SEQ.incrementAndGet();

        supervisorUser = createTestUser("sup_it_" + n, Role.PRODUCTION_SUPERVISOR);
        qaUser = createTestUser("qa_it_" + n, Role.QA_REVIEWER);
        adminUser = createTestUser("admin_it_" + n, Role.ADMIN);
        operatorUser = createTestUser("op_it_" + n, Role.OPERATOR);
        qcUser = createTestUser("qc_it_" + n, Role.QC_ANALYST);

        // Seed product and recipe
        Product product = productRepository.save(new Product(
                "PRD-REP-" + n, "Report Paracetamol " + n, "500mg", "Tablet", "Desc", ProductStatus.ACTIVE
        ));

        Recipe recipe = new Recipe(
                "RCP-REP-" + n, product, 1, new BigDecimal("100.0000"), "kg", RecipeStatus.APPROVED, "Desc"
        );
        RecipeStep step1 = new RecipeStep(1, "Dispensing", "Dispense ingredients");
        recipe.addStep(step1);
        recipe = recipeRepository.save(recipe);

        LocalDate today = LocalDate.now();
        ManufacturingOrder order = orderRepository.save(new ManufacturingOrder(
                "MO-REP-" + n, product, recipe, 1, new BigDecimal("100.0000"), "kg", 1,
                OrderStatus.IN_PROGRESS, today, today.plusDays(7), "Supervisor Notes"
        ));

        // Create Batch 1
        testBatch1 = new Batch(
                "BAT-REP-" + n + "-01", order, new BigDecimal("100.0000"), "kg",
                BatchStatus.COMPLETED, today, today.plusYears(2)
        );
        testBatch1.setActualYield(new BigDecimal("98.5000"));
        testBatch1.setQaStatus(BatchQualityStatus.PENDING_QA);
        testBatch1.setQrCode("QR-BAT-REP-" + n + "-01");
        testBatch1 = batchRepository.saveAndFlush(testBatch1);

        BatchExecutionStep execStep = new BatchExecutionStep(
                testBatch1, recipe.getSteps().get(0), 1, "Dispensing", "Dispense ingredients", ExecutionStepStatus.COMPLETED
        );
        execStep.setActualValue("50.0 kg");
        executionStepRepository.saveAndFlush(execStep);

        QualityCheck qc = new QualityCheck(
                testBatch1, "Assay", "98.0% - 102.0%", "99.2%", "%", QualityCheckResult.PASS,
                true, "qc_analyst", LocalDateTime.now(), "Pass"
        );
        qualityCheckRepository.saveAndFlush(qc);

        // Create Batch 2
        testBatch2 = new Batch(
                "BAT-REP-" + n + "-02", order, new BigDecimal("100.0000"), "kg",
                BatchStatus.COMPLETED, today, today.plusYears(2)
        );
        testBatch2.setActualYield(new BigDecimal("99.1000"));
        testBatch2.setQaStatus(BatchQualityStatus.PENDING_QA);
        testBatch2.setQrCode("QR-BAT-REP-" + n + "-02");
        testBatch2 = batchRepository.saveAndFlush(testBatch2);

        // Configure default mock LLM response
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.generate(any(), any(), anyInt())).thenReturn(
                new LlmResponse(createValid12SectionJson(), "gpt-4o", "resp-test-lifecycle", 400, 800, 1200)
        );
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(reportRepository, auditTrailService);
        SecurityContextHolder.clearContext();
    }

    private UserAccount createTestUser(String username, Role role) {
        UserAccount user = new UserAccount(
                username,
                passwordEncoder.encode("Password123!"),
                "Full Name " + username,
                role,
                true
        );
        return userAccountRepository.saveAndFlush(user);
    }

    private String bearer(UserAccount user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    // =========================================================================
    // 1. Generation Lifecycle & Idempotency
    // =========================================================================

    @Test
    @DisplayName("Lifecycle: Production supervisor successfully generates report; repeated request returns cached report")
    void testSuccessfulGenerationAndIdempotentReplay() throws Exception {
        String clientRequestId = "req-lifecycle-" + UUID.randomUUID();
        BatchAnalysisReportRequestDto requestDto = new BatchAnalysisReportRequestDto(clientRequestId);

        // 1. First POST: Generates and persists report
        MvcResult firstResult = mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.batchId").value(testBatch1.getId()))
                .andExpect(jsonPath("$.batchNumber").value(testBatch1.getBatchNumber()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.model").value("gpt-4o"))
                .andExpect(jsonPath("$.deterministicMetrics.actualYield").value(98.5))
                .andExpect(jsonPath("$.deterministicMetrics.yieldPercent").value(98.5))
                .andExpect(jsonPath("$.structuredAnalysis.executiveSummary").isNotEmpty())
                .andExpect(jsonPath("$.structuredAnalysis.qaDispositionStatus", containsString("Authoritative QA Disposition: PENDING_QA")))
                .andExpect(jsonPath("$.reportMarkdown", containsString("# AI Batch Analysis Report:")))
                .andExpect(jsonPath("$.sourceReferences", not(empty())))
                .andReturn();

        Long reportId = objectMapper.readTree(firstResult.getResponse().getContentAsString()).get("id").asLong();

        // Verify LLM client was called once
        verify(llmClient, times(1)).generate(any(), any(), anyInt());

        // 2. Second POST: Exact same clientRequestId returns existing completed report without calling LLM again
        mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reportId))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // Verify LLM was NOT called again
        verify(llmClient, times(1)).generate(any(), any(), anyInt());

        // Verify report is in database
        BatchAnalysisReport saved = reportRepository.findById(reportId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);
        assertThat(saved.getGeneratedByUser().getId()).isEqualTo(supervisorUser.getId());
    }

    // =========================================================================
    // 2. History & Pagination
    // =========================================================================

    @Test
    @DisplayName("History: GET history returns only reports for the specified batch, newest first")
    void testBatchHistoryIsolationAndPagination() throws Exception {
        String reqId1 = "req-hist-1-" + UUID.randomUUID();
        String reqId2 = "req-hist-2-" + UUID.randomUUID();
        String reqIdOther = "req-hist-other-" + UUID.randomUUID();

        // Generate 2 reports for Batch 1
        mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchAnalysisReportRequestDto(reqId1))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchAnalysisReportRequestDto(reqId2))))
                .andExpect(status().isOk());

        // Generate 1 report for Batch 2
        mockMvc.perform(post("/api/batches/" + testBatch2.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchAnalysisReportRequestDto(reqIdOther))))
                .andExpect(status().isOk());

        // Query history for Batch 1 -> Should return exactly 2 reports, not 3
        mockMvc.perform(get("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].batchNumber").value(testBatch1.getBatchNumber()))
                .andExpect(jsonPath("$[1].batchNumber").value(testBatch1.getBatchNumber()))
                // Verify summary does NOT expose full markdown or structured analysis
                .andExpect(jsonPath("$[0].reportMarkdown").doesNotExist())
                .andExpect(jsonPath("$[0].structuredAnalysis").doesNotExist());

        // Test pagination: page=0, size=1 -> exactly 1 report
        mockMvc.perform(get("/api/batches/" + testBatch1.getId() + "/analysis-reports?page=0&size=1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // =========================================================================
    // 3. Detail Scoping & Cross-Batch Protection
    // =========================================================================

    @Test
    @DisplayName("Detail: Scoped to parent batch; cross-batch report access returns 404")
    void testDetailScopingAndCrossBatchProtection() throws Exception {
        String reqId = "req-scope-" + UUID.randomUUID();

        // Generate report for Batch 1
        MvcResult res = mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchAnalysisReportRequestDto(reqId))))
                .andExpect(status().isOk())
                .andReturn();

        Long report1Id = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        // 1. Valid detail query on Batch 1 -> 200 OK
        mockMvc.perform(get("/api/batches/" + testBatch1.getId() + "/analysis-reports/" + report1Id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(report1Id))
                .andExpect(jsonPath("$.batchId").value(testBatch1.getId()));

        // 2. Querying Batch 1's report under Batch 2 URL -> 404 Not Found
        mockMvc.perform(get("/api/batches/" + testBatch2.getId() + "/analysis-reports/" + report1Id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser)))
                .andExpect(status().isNotFound());

        // 3. Querying nonexistent report ID -> 404 Not Found
        mockMvc.perform(get("/api/batches/" + testBatch1.getId() + "/analysis-reports/999999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser)))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 4. Role Authorization Matrix
    // =========================================================================

    @Test
    @DisplayName("RBAC: QA_REVIEWER and ADMIN can view history & details, but CANNOT generate (403)")
    void testQaReviewerAndAdminReadPermissions() throws Exception {
        String reqId = "req-rbac-" + UUID.randomUUID();

        // Supervisor generates report
        MvcResult res = mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchAnalysisReportRequestDto(reqId))))
                .andExpect(status().isOk())
                .andReturn();
        Long reportId = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        // QA Reviewer views history -> 200
        mockMvc.perform(get("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(qaUser)))
                .andExpect(status().isOk());

        // QA Reviewer views detail -> 200
        mockMvc.perform(get("/api/batches/" + testBatch1.getId() + "/analysis-reports/" + reportId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(qaUser)))
                .andExpect(status().isOk());

        // QA Reviewer attempts to generate -> 403 Forbidden
        mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(qaUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchAnalysisReportRequestDto("req-qa-gen-" + UUID.randomUUID()))))
                .andExpect(status().isForbidden());

        // Administrator views history -> 200
        mockMvc.perform(get("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminUser)))
                .andExpect(status().isOk());

        // Administrator views detail -> 200
        mockMvc.perform(get("/api/batches/" + testBatch1.getId() + "/analysis-reports/" + reportId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminUser)))
                .andExpect(status().isOk());

        // Administrator attempts to generate -> 403 Forbidden
        mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchAnalysisReportRequestDto("req-admin-gen-" + UUID.randomUUID()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RBAC: OPERATOR and QC_ANALYST cannot generate, view history, or view details (403)")
    void testOperatorAndQcAnalystExcluded() throws Exception {
        // OPERATOR
        mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchAnalysisReportRequestDto("req-op-" + UUID.randomUUID()))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorUser)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/batches/" + testBatch1.getId() + "/analysis-reports/1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorUser)))
                .andExpect(status().isForbidden());

        // QC_ANALYST
        mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(qcUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchAnalysisReportRequestDto("req-qc-" + UUID.randomUUID()))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(qcUser)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/batches/" + testBatch1.getId() + "/analysis-reports/1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(qcUser)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 5. Failure Handling & Exception Sanitization
    // =========================================================================

    @Test
    @DisplayName("Failure handling: Failed report generation records FAILED and does not leak stack trace or internal details")
    void testFailedReportDoesNotLeakInternalDetails() throws Exception {
        String reqId = "req-fail-" + UUID.randomUUID();

        // Simulate provider connection failure containing sensitive marker
        when(llmClient.generate(any(), any(), anyInt())).thenThrow(
                new LlmClientException(
                        LlmClientException.ErrorCategory.CONNECTION_FAILURE,
                        "Failed to connect to https://internal-ai-host.corp:8443 with key=sk-secretKey12345"
                )
        );

        // POST generate fails with 502 Bad Gateway
        mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchAnalysisReportRequestDto(reqId))))
                .andExpect(status().isBadGateway());

        // Verify report was persisted in FAILED status
        BatchAnalysisReport failedReport = reportRepository.findByClientRequestId(reqId).orElseThrow();
        assertThat(failedReport.getStatus()).isEqualTo(BatchAnalysisReportStatus.FAILED);

        // Retrieve failed report details -> verify error message is sanitized (no URL, no secret key leaked)
        MvcResult detailRes = mockMvc.perform(get("/api/batches/" + testBatch1.getId() + "/analysis-reports/" + failedReport.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(detailRes.getResponse().getContentAsString());
        String returnedErrorMessage = responseNode.get("errorMessage").asText();

        assertThat(returnedErrorMessage).doesNotContain("sk-secretKey12345");
        assertThat(returnedErrorMessage).doesNotContain("internal-ai-host.corp");
    }

    // =========================================================================
    // 6. Permanent Delete Integration Tests
    // =========================================================================

    @Test
    @DisplayName("Permanent Delete: ADMIN can permanently delete report with atomic durable audit log, non-ADMIN is forbidden")
    void testPermanentDeleteReportByAdmin() throws Exception {
        String reqId = "req-del-" + UUID.randomUUID();

        // 1. Generate a valid report
        when(llmClient.generate(any(), any(), anyInt())).thenReturn(
                new LlmResponse(createValid12SectionJson(), "gpt-4", "resp-del-123", 100, 200, 300)
        );

        mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchAnalysisReportRequestDto(reqId))))
                .andExpect(status().isOk());

        BatchAnalysisReport report = reportRepository.findByClientRequestId(reqId).orElseThrow();
        Long reportId = report.getId();
        assertThat(report.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);

        PermanentDeleteReportRequestDto validRequest = new PermanentDeleteReportRequestDto(
                "Local database cleanup after testing",
                "PERMANENTLY DELETE"
        );

        // 2. Non-ADMIN (SUPERVISOR, QA) are forbidden (403)
        mockMvc.perform(delete("/api/batches/" + testBatch1.getId() + "/analysis-reports/" + reportId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/batches/" + testBatch1.getId() + "/analysis-reports/" + reportId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(qaUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isForbidden());

        // Report must still exist
        assertThat(reportRepository.findById(reportId)).isPresent();

        // 3. ADMIN with invalid keyword -> 400 Bad Request
        PermanentDeleteReportRequestDto invalidKeywordReq = new PermanentDeleteReportRequestDto(
                "Local cleanup", "DELETE"
        );
        mockMvc.perform(delete("/api/batches/" + testBatch1.getId() + "/analysis-reports/" + reportId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidKeywordReq)))
                .andExpect(status().isBadRequest());

        // Report must still exist
        assertThat(reportRepository.findById(reportId)).isPresent();

        // 4. ADMIN with valid request -> 204 No Content
        mockMvc.perform(delete("/api/batches/" + testBatch1.getId() + "/analysis-reports/" + reportId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isNoContent());

        // 5. Verify report is physically deleted from database
        assertThat(reportRepository.findById(reportId)).isEmpty();

        // 6. Verify audit log entry exists in audit_logs, preserving action, actor, reason, and before snapshot
        List<AuditLog> auditLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                "BATCH_ANALYSIS_REPORT", reportId
        );
        assertThat(auditLogs).isNotEmpty();
        AuditLog deleteAudit = auditLogs.stream()
                .filter(a -> "PERMANENT_DELETE".equals(a.getAction()))
                .findFirst()
                .orElseThrow();
        assertThat(deleteAudit.getActorName()).isEqualTo(adminUser.getUsername());
        assertThat(deleteAudit.getReason()).isEqualTo("Local database cleanup after testing");
        assertThat(deleteAudit.getBeforeValues()).contains(report.getBatchNumber());
        assertThat(deleteAudit.getBeforeValues()).contains(reqId);
        assertThat(deleteAudit.getAfterValues()).contains("PERMANENTLY DELETE");
    }

    @Test
    @DisplayName("Rollback Case 1: Injected audit failure prevents physical report deletion and leaves zero audit records")
    void testPermanentDeleteRollbackOnAuditFailure() throws Exception {
        String reqId = "req-rb-audit-" + UUID.randomUUID();

        // 1. Generate an ephemeral completed report
        when(llmClient.generate(any(), any(), anyInt())).thenReturn(
                new LlmResponse(createValid12SectionJson(), "gpt-4", "resp-del-rb1", 100, 200, 300)
        );

        mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchAnalysisReportRequestDto(reqId))))
                .andExpect(status().isOk());

        BatchAnalysisReport report = reportRepository.findByClientRequestId(reqId).orElseThrow();
        Long reportId = report.getId();
        assertThat(report.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);

        // 2. Inject failure on auditTrailService.recordAudit()
        doThrow(new RuntimeException("Injected audit failure for permanent delete rollback test"))
                .when(auditTrailService)
                .recordAudit(eq("BATCH_ANALYSIS_REPORT"), eq(reportId), any(), any(), eq("PERMANENT_DELETE"), any(), any(), any(), any(), any());

        PermanentDeleteReportRequestDto deleteRequest = new PermanentDeleteReportRequestDto(
                "Rollback audit failure test",
                "PERMANENTLY DELETE"
        );

        // 3. Attempt permanent deletion as ADMIN -> must fail (500 Internal Server Error on unhandled RuntimeException)
        mockMvc.perform(delete("/api/batches/" + testBatch1.getId() + "/analysis-reports/" + reportId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteRequest)))
                .andExpect(status().isInternalServerError());

        // 4. Verify report still exists in the real H2 database
        assertThat(reportRepository.findById(reportId)).isPresent();
        assertThat(reportRepository.findById(reportId).get().getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);

        // 5. Verify no PERMANENT_DELETE audit record exists in the real H2 database
        List<AuditLog> auditLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                "BATCH_ANALYSIS_REPORT", reportId
        );
        assertThat(auditLogs).noneMatch(a -> "PERMANENT_DELETE".equals(a.getAction()));
    }

    @Test
    @DisplayName("Rollback Case 2: Injected report deletion failure rolls back the inserted audit record and keeps report intact")
    void testPermanentDeleteRollbackOnDeletionFailure() throws Exception {
        String reqId = "req-rb-del-" + UUID.randomUUID();

        // 1. Generate an ephemeral completed report
        when(llmClient.generate(any(), any(), anyInt())).thenReturn(
                new LlmResponse(createValid12SectionJson(), "gpt-4", "resp-del-rb2", 100, 200, 300)
        );

        mockMvc.perform(post("/api/batches/" + testBatch1.getId() + "/analysis-reports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchAnalysisReportRequestDto(reqId))))
                .andExpect(status().isOk());

        BatchAnalysisReport report = reportRepository.findByClientRequestId(reqId).orElseThrow();
        Long reportId = report.getId();
        assertThat(report.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);

        // 2. Allow auditTrailService.recordAudit() to run, but inject database failure on reportRepository.delete()
        doThrow(new DataIntegrityViolationException("Injected DB failure during report physical delete"))
                .when(reportRepository)
                .delete(any(BatchAnalysisReport.class));

        PermanentDeleteReportRequestDto deleteRequest = new PermanentDeleteReportRequestDto(
                "Rollback deletion failure test",
                "PERMANENTLY DELETE"
        );

        // 3. Attempt permanent deletion as ADMIN -> must fail (409 Conflict via DataIntegrityViolationException handler)
        mockMvc.perform(delete("/api/batches/" + testBatch1.getId() + "/analysis-reports/" + reportId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteRequest)))
                .andExpect(status().isConflict());

        // 4. Verify report still exists in the real H2 database
        assertThat(reportRepository.findById(reportId)).isPresent();
        assertThat(reportRepository.findById(reportId).get().getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);

        // 5. Verify the PERMANENT_DELETE audit record that was inserted before delete() failed was ROLLED BACK
        List<AuditLog> auditLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                "BATCH_ANALYSIS_REPORT", reportId
        );
        assertThat(auditLogs).noneMatch(a -> "PERMANENT_DELETE".equals(a.getAction()));
    }

    private String createValid12SectionJson() {
        return """
                {
                  "executiveSummary": "Batch BAT-01 executed within standard operational limits with a 98.5% yield.",
                  "batchStatusAndProgress": "Manufacturing status is COMPLETED. Execution finalized without deviations.",
                  "plannedVsActualMetrics": "Planned size was 100 kg, actual yield 98.5 kg, variance -1.5 kg.",
                  "materialTraceability": "All active lots consumed in compliance with recipe specifications.",
                  "ebrStepEvaluation": "All 1 execution steps were signed off by authorized personnel.",
                  "qcInspectionAnalysis": "In-process quality testing passed all acceptance criteria.",
                  "qaDispositionStatus": "Authoritative QA Disposition: PENDING_QA (Not Released).",
                  "deviationsAndCapa": "Zero deviations recorded during production.",
                  "chronologicalAuditAnalysis": "Sequential progression from dispensing to final yield verified.",
                  "missingRecords": "None. All mandatory fields recorded in MES.",
                  "riskObservations": "Low operational risk based on verified records.",
                  "followUpRecommendations": "Submit completed dossier to QA for formal disposition review."
                }
                """;
    }
}
