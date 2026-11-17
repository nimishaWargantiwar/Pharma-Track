package com.pharmatrack.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.client.LlmClient;
import com.pharmatrack.ai.client.LlmResponse;
import com.pharmatrack.ai.dto.BatchAnalysisReportRequestDto;
import com.pharmatrack.ai.dto.BatchAnalysisReportResponseDto;
import com.pharmatrack.ai.entity.BatchAnalysisReport;
import com.pharmatrack.ai.entity.BatchAnalysisReportStatus;
import com.pharmatrack.ai.exception.BatchAnalysisConflictException;
import com.pharmatrack.ai.exception.BatchAnalysisValidationException;
import com.pharmatrack.ai.repository.BatchAnalysisReportRepository;
import com.pharmatrack.ai.service.BatchAnalysisReportService;
import com.pharmatrack.ai.service.BatchAnalysisReportTxHelper;
import com.pharmatrack.entity.*;
import com.pharmatrack.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_ai_concurrency_safety_it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        "app.ai.enabled=true",
        "app.ai.api-key=sk-mock-integration-key",
        "app.ai.model=gpt-6-luna"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("PharmaTrack AI - Phase 2.1 Concurrency and QA Release Safety Integration Test")
class BatchAnalysisConcurrencyAndSafetyIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1000);

    @Autowired
    private BatchAnalysisReportService reportService;

    @Autowired
    private BatchAnalysisReportTxHelper txHelper;

    @Autowired
    private BatchAnalysisReportRepository reportRepository;

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
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CopilotConversationRepository conversationRepository;

    @MockBean
    private LlmClient llmClient;

    private UserAccount testSupervisor;
    private Batch testBatch1;
    private Batch testBatch2;

    @BeforeEach
    void setUp() {
        int n = SEQ.incrementAndGet();

        // 1. Create Supervisor user
        testSupervisor = userAccountRepository.save(new UserAccount(
                "sup_concurrency_" + n,
                "$2a$12$dummyHashForTestingPurposesOnly12345678901234567890",
                "Supervisor Concurrency " + n,
                Role.PRODUCTION_SUPERVISOR,
                true
        ));

        // 2. Set Security Context to supervisor
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        testSupervisor.getUsername(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_PRODUCTION_SUPERVISOR"))
                )
        );

        // 3. Create Product & Recipe
        Product product = productRepository.save(new Product(
                "PRD-CONC-" + n, "Concurrency Amoxicillin " + n, "500mg", "Capsule", "Desc", ProductStatus.ACTIVE
        ));

        Recipe recipe = new Recipe(
                "RCP-CONC-" + n, product, 1, new BigDecimal("100.0000"), "kg", RecipeStatus.APPROVED, "Desc"
        );
        RecipeStep step1 = new RecipeStep(1, "Dispensing", "Dispense ingredients");
        recipe.addStep(step1);
        recipe = recipeRepository.save(recipe);

        LocalDate today = LocalDate.now();
        ManufacturingOrder order = orderRepository.save(new ManufacturingOrder(
                "MO-CONC-" + n,
                product,
                recipe,
                1,
                new BigDecimal("100.0000"),
                "kg",
                1,
                OrderStatus.IN_PROGRESS,
                today,
                today.plusDays(5),
                "Order notes"
        ));

        // 4. Create Batch 1 (PENDING_QA)
        testBatch1 = new Batch(
                "BAT-CONC-" + n + "-01",
                order,
                new BigDecimal("100.0000"),
                "kg",
                BatchStatus.COMPLETED,
                today,
                today.plusYears(2)
        );
        testBatch1.setActualYield(new BigDecimal("98.5000"));
        testBatch1.setQaStatus(BatchQualityStatus.PENDING_QA);
        testBatch1.setQrCode("QR-BAT-CONC-" + n + "-01");
        testBatch1 = batchRepository.saveAndFlush(testBatch1);

        // Seed an execution step and quality check for batch 1
        BatchExecutionStep execStep = new BatchExecutionStep(
                testBatch1, recipe.getSteps().get(0), 1, "Dispensing", "Dispense ingredients", ExecutionStepStatus.COMPLETED
        );
        execStep.setActualValue("50.0 kg");
        executionStepRepository.saveAndFlush(execStep);

        QualityCheck qc = new QualityCheck(
                testBatch1, "Assay", "98.0% - 102.0%", "99.1%", "%", QualityCheckResult.PASS,
                true, "qc_analyst", LocalDateTime.now(), "Pass"
        );
        qualityCheckRepository.saveAndFlush(qc);

        // 5. Create Batch 2 (PENDING_QA)
        testBatch2 = new Batch(
                "BAT-CONC-" + n + "-02",
                order,
                new BigDecimal("100.0000"),
                "kg",
                BatchStatus.COMPLETED,
                today,
                today.plusYears(2)
        );
        testBatch2.setActualYield(new BigDecimal("99.1000"));
        testBatch2.setQaStatus(BatchQualityStatus.PENDING_QA);
        testBatch2.setQrCode("QR-BAT-CONC-" + n + "-02");
        testBatch2 = batchRepository.saveAndFlush(testBatch2);

        // Configure default LLM mock
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.generate(any(), any(), anyInt())).thenReturn(new LlmResponse(createValid12SectionJson(), "gpt-6-luna", "resp-test-1", 500, 1000, 1500));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --------------------------------------------------------------------------------------
    // Test 1: Two simultaneous requests with different IDs for same batch
    // Exactly one enters GENERATING, the second is rejected with BatchAnalysisConflictException
    // --------------------------------------------------------------------------------------
    @Test
    @DisplayName("1. Concurrent requests with different IDs for same batch: exactly one GENERATING, one rejected")
    void testConcurrentRequestsForSameBatchSerializedAndSecondRejected() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        String reqIdA = "req-concurrent-A-" + UUID.randomUUID();
        String reqIdB = "req-concurrent-B-" + UUID.randomUUID();

        CountDownLatch startSignal = new CountDownLatch(1);

        Callable<BatchAnalysisReport> taskA = () -> {
            startSignal.await();
            return txHelper.getOrCreateInitialReportTx(testBatch1, testSupervisor, reqIdA);
        };

        Callable<BatchAnalysisReport> taskB = () -> {
            startSignal.await();
            return txHelper.getOrCreateInitialReportTx(testBatch1, testSupervisor, reqIdB);
        };

        Future<BatchAnalysisReport> futureA = executor.submit(taskA);
        Future<BatchAnalysisReport> futureB = executor.submit(taskB);

        // Trigger both tasks simultaneously
        startSignal.countDown();

        BatchAnalysisReport reportA = null;
        Throwable errorA = null;
        try {
            reportA = futureA.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            errorA = ex.getCause();
        }

        BatchAnalysisReport reportB = null;
        Throwable errorB = null;
        try {
            reportB = futureB.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            errorB = ex.getCause();
        }

        executor.shutdown();

        // Exactly one task must succeed with GENERATING
        // The other task must throw BatchAnalysisConflictException
        boolean aSucceeded = (reportA != null && reportA.getStatus() == BatchAnalysisReportStatus.GENERATING);
        boolean bSucceeded = (reportB != null && reportB.getStatus() == BatchAnalysisReportStatus.GENERATING);

        assertThat(aSucceeded ^ bSucceeded)
                .as("Exactly one of the concurrent requests must succeed in acquiring GENERATING status")
                .isTrue();

        if (aSucceeded) {
            assertThat(errorB)
                    .isNotNull()
                    .isInstanceOf(BatchAnalysisConflictException.class)
                    .hasMessageContaining("Another batch analysis report generation is already in progress for batch " + testBatch1.getBatchNumber());
        } else {
            assertThat(errorA)
                    .isNotNull()
                    .isInstanceOf(BatchAnalysisConflictException.class)
                    .hasMessageContaining("Another batch analysis report generation is already in progress for batch " + testBatch1.getBatchNumber());
        }

        // Database must have exactly 1 report for testBatch1
        List<BatchAnalysisReport> persistedReports = reportRepository.findByBatchIdOrderByCreatedAtDesc(testBatch1.getId());
        assertThat(persistedReports).hasSize(1);
        assertThat(persistedReports.get(0).getStatus()).isEqualTo(BatchAnalysisReportStatus.GENERATING);
    }

    // --------------------------------------------------------------------------------------
    // Test 2: Two requests for different batches: both enter GENERATING
    // --------------------------------------------------------------------------------------
    @Test
    @DisplayName("2. Two requests for different batches: both enter GENERATING without interference")
    void testDifferentBatchesEnterGeneratingWithoutInterference() {
        String reqId1 = "req-diff-batch-1-" + UUID.randomUUID();
        String reqId2 = "req-diff-batch-2-" + UUID.randomUUID();

        BatchAnalysisReport report1 = txHelper.getOrCreateInitialReportTx(testBatch1, testSupervisor, reqId1);
        BatchAnalysisReport report2 = txHelper.getOrCreateInitialReportTx(testBatch2, testSupervisor, reqId2);

        assertThat(report1).isNotNull();
        assertThat(report1.getStatus()).isEqualTo(BatchAnalysisReportStatus.GENERATING);
        assertThat(report1.getBatch().getId()).isEqualTo(testBatch1.getId());

        assertThat(report2).isNotNull();
        assertThat(report2.getStatus()).isEqualTo(BatchAnalysisReportStatus.GENERATING);
        assertThat(report2.getBatch().getId()).isEqualTo(testBatch2.getId());

        // Both exist in database
        assertThat(reportRepository.findByClientRequestId(reqId1)).isPresent();
        assertThat(reportRepository.findByClientRequestId(reqId2)).isPresent();
    }

    // --------------------------------------------------------------------------------------
    // Test 3: Same-request idempotency remains intact
    // --------------------------------------------------------------------------------------
    @Test
    @DisplayName("3. Same-request idempotency rejects duplicate in-flight and returns existing COMPLETED report")
    void testSameRequestReturnsExistingReportIdempotently() {
        String reqId = "req-idempotent-" + UUID.randomUUID();

        // First call creates report in GENERATING
        BatchAnalysisReport initial = txHelper.getOrCreateInitialReportTx(testBatch1, testSupervisor, reqId);
        assertThat(initial.getStatus()).isEqualTo(BatchAnalysisReportStatus.GENERATING);

        // Immediate replay with same clientRequestId while in-flight throws conflict
        assertThatThrownBy(() -> txHelper.getOrCreateInitialReportTx(testBatch1, testSupervisor, reqId))
                .isInstanceOf(BatchAnalysisConflictException.class)
                .hasMessageContaining("Report generation is currently in progress for clientRequestId");

        // Complete the report
        txHelper.completeReportTx(
                initial.getId(),
                "gpt-6-luna",
                reportService.calculateDeterministicMetrics(null),
                "{}",
                "# Markdown Report",
                "[]",
                testSupervisor.getUsername()
        );

        // Replay after completion returns COMPLETED report idempotently
        BatchAnalysisReport completedReplay = txHelper.getOrCreateInitialReportTx(testBatch1, testSupervisor, reqId);
        assertThat(completedReplay.getId()).isEqualTo(initial.getId());
        assertThat(completedReplay.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);
    }

    // --------------------------------------------------------------------------------------
    // Test 4: Failed report does not block a fresh request ID
    // --------------------------------------------------------------------------------------
    @Test
    @DisplayName("4. Failed report does not block a fresh request ID for the same batch")
    void testFailedReportDoesNotBlockFreshRequestId() {
        String failedReqId = "req-failed-" + UUID.randomUUID();
        BatchAnalysisReport initial = txHelper.getOrCreateInitialReportTx(testBatch1, testSupervisor, failedReqId);
        txHelper.failReportTx(initial.getId(), "Provider timeout failure", testSupervisor.getUsername());

        // Verify status is FAILED
        BatchAnalysisReport failedReport = reportRepository.findById(initial.getId()).orElseThrow();
        assertThat(failedReport.getStatus()).isEqualTo(BatchAnalysisReportStatus.FAILED);

        // Fresh request with new ID for the same batch must succeed and enter GENERATING
        String freshReqId = "req-fresh-" + UUID.randomUUID();
        BatchAnalysisReport freshReport = txHelper.getOrCreateInitialReportTx(testBatch1, testSupervisor, freshReqId);

        assertThat(freshReport).isNotNull();
        assertThat(freshReport.getId()).isNotEqualTo(initial.getId());
        assertThat(freshReport.getStatus()).isEqualTo(BatchAnalysisReportStatus.GENERATING);
        assertThat(freshReport.getClientRequestId()).isEqualTo(freshReqId);
    }

    // --------------------------------------------------------------------------------------
    // Test 5: Batch row lock is released after initial transaction
    // --------------------------------------------------------------------------------------
    @Test
    @DisplayName("5. Batch row lock is immediately released after initial transaction commits")
    void testBatchRowLockIsReleasedAfterTxHelper() {
        String reqId = "req-lock-release-" + UUID.randomUUID();
        txHelper.getOrCreateInitialReportTx(testBatch1, testSupervisor, reqId);

        // A separate transaction should immediately be able to acquire lock and modify the batch
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.execute(status -> {
            Batch lockedBatch = batchRepository.findByIdWithLock(testBatch1.getId()).orElseThrow();
            lockedBatch.setQrCode("QR-UPDATED-" + testBatch1.getId());
            batchRepository.saveAndFlush(lockedBatch);
            return true;
        });

        Batch updated = batchRepository.findById(testBatch1.getId()).orElseThrow();
        assertThat(updated.getQrCode()).isEqualTo("QR-UPDATED-" + testBatch1.getId());
    }

    // --------------------------------------------------------------------------------------
    // Test 6: QA status generated from authoritative dossier
    // --------------------------------------------------------------------------------------
    @Test
    @DisplayName("6. QA status section is deterministically populated from authoritative dossier")
    void testQaStatusGeneratedFromAuthoritativeDossier() {
        String reqId = "req-authoritative-qa-" + UUID.randomUUID();

        // Model attempts to hallucinate its own qaDispositionStatus
        String hallucinatedModelOutput = createValid12SectionJsonWithQaSection(
                "The AI system has reviewed all records and formally approves this batch for release."
        );
        when(llmClient.generate(any(), any(), anyInt())).thenReturn(new LlmResponse(hallucinatedModelOutput, "gpt-6-luna", "resp-test-qa", 500, 1000, 1500));

        // When generating the report for testBatch1 (PENDING_QA), the model's claim in section 7 is overwritten
        // Note: other sections must not contain forbidden phrases
        BatchAnalysisReportResponseDto response = reportService.generateReport(
                testBatch1.getBatchNumber(),
                new BatchAnalysisReportRequestDto(reqId)
        );

        assertThat(response.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);
        String qaSection = response.getStructuredAnalysis().getQaDispositionStatus();

        // Programmatically anchored to authoritative MES statement
        assertThat(qaSection)
                .startsWith("Authoritative QA Disposition: PENDING_QA (Not Released)")
                .contains(testBatch1.getBatchNumber())
                .contains("formal batch release has NOT been granted");

        // The AI's hallucinated statement must NOT be in section 7
        assertThat(qaSection).doesNotContain("The AI system has reviewed all records and formally approves");
    }

    // --------------------------------------------------------------------------------------
    // Test 7: Alternative release claims and compound sentences rejected
    // --------------------------------------------------------------------------------------
    @Test
    @DisplayName("7. Alternative release claims and compound sentences in model output are strictly rejected when batch is not released")
    void testAlternativeReleaseClaimsRejected() {
        List<String> alternativeForbiddenClaims = List.of(
                "The batch is formally approved for market dispatch.",
                "The batch has passed QA disposition and is ready for release.",
                "Product release has been granted by quality assurance.",
                "The batch is authorized for distribution.",
                "The batch is cleared for commercial use.",
                "The formal sign-off is pending but the batch is approved for release.",
                "QA review is pending; however, the product is authorized for distribution.",
                "The batch is awaiting review, but commercial dispatch is now permitted.",
                "Approval is not yet recorded, although the batch is cleared for market dispatch.",
                "The batch is pending QA review and is now free to ship to retail pharmacies.",
                "The product is awaiting final review, but sale of this product can proceed.",
                "The batch is cleared for shipment, although final QA review remains pending.",
                "The batch is authorized for shipment.",
                "The batch is ready for shipment."
        );

        for (int i = 0; i < alternativeForbiddenClaims.size(); i++) {
            String claim = alternativeForbiddenClaims.get(i);
            String reqId = "req-forbidden-" + i + "-" + UUID.randomUUID();

            // Inject the forbidden claim into executiveSummary
            String maliciousOutput = createValid12SectionJsonWithExecutiveSummary(
                    "Executive Summary: " + claim
            );
            when(llmClient.generate(any(), any(), anyInt())).thenReturn(new LlmResponse(maliciousOutput, "gpt-6-luna", "resp-forbidden-" + i, 500, 1000, 1500));

            assertThatThrownBy(() -> reportService.generateReport(
                    testBatch1.getBatchNumber(),
                    new BatchAnalysisReportRequestDto(reqId)
            ))
                    .isInstanceOf(BatchAnalysisValidationException.class);

            // Verify the report was marked as FAILED in database
            Optional<BatchAnalysisReport> failedReportOpt = reportRepository.findByClientRequestId(reqId);
            assertThat(failedReportOpt).isPresent();
            assertThat(failedReportOpt.get().getStatus()).isEqualTo(BatchAnalysisReportStatus.FAILED);
            assertThat(failedReportOpt.get().getErrorMessage()).contains("executiveSummary");
        }
    }

    // --------------------------------------------------------------------------------------
    // Test 7b: Legitimate negative and pending statements accepted
    // --------------------------------------------------------------------------------------
    @Test
    @DisplayName("7b. Legitimate negative and pending statements are accepted without false-positive rejection")
    void testLegitimateNegativeStatementsAccepted() {
        List<String> legitimateStatements = List.of(
                "The batch has not been released and is awaiting QA review.",
                "The batch is not approved for commercial distribution.",
                "Release must not be assumed from production completion.",
                "The batch cannot be dispatched until QA approval is recorded.",
                "The batch remains pending QA disposition.",
                "The batch is not cleared for shipment.",
                "Shipment is prohibited until QA approval.",
                "The batch cannot be shipped before QA release."
        );

        for (int i = 0; i < legitimateStatements.size(); i++) {
            String stmt = legitimateStatements.get(i);
            String reqId = "req-legit-pending-" + i + "-" + UUID.randomUUID();

            String validOutput = createValid12SectionJsonWithExecutiveSummary(
                    "Executive Summary: " + stmt
            );
            when(llmClient.generate(any(), any(), anyInt())).thenReturn(new LlmResponse(validOutput, "gpt-6-luna", "resp-legit-" + i, 500, 1000, 1500));

            BatchAnalysisReportResponseDto response = reportService.generateReport(
                    testBatch1.getBatchNumber(),
                    new BatchAnalysisReportRequestDto(reqId)
            );

            assertThat(response.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);
            assertThat(response.getStructuredAnalysis().getQaDispositionStatus())
                    .startsWith("Authoritative QA Disposition: PENDING_QA (Not Released)")
                    .contains("formal batch release has NOT been granted");
        }
    }

    // --------------------------------------------------------------------------------------
    // Test 8: Legitimate released status accurately represented
    // --------------------------------------------------------------------------------------
    @Test
    @DisplayName("8. Legitimate released status accurately represented and factual descriptions accepted")
    void testLegitimateReleasedStatusAccuratelyRepresented() {
        // Set testBatch2 QA status to RELEASED
        testBatch2.setQaStatus(BatchQualityStatus.RELEASED);
        batchRepository.saveAndFlush(testBatch2);

        String reqId = "req-legitimate-release-" + UUID.randomUUID();
        String validOutput = createValid12SectionJsonWithExecutiveSummary(
                "Executive Summary: The batch has been formally approved for market dispatch following complete QA review."
        );
        when(llmClient.generate(any(), any(), anyInt())).thenReturn(new LlmResponse(validOutput, "gpt-6-luna", "resp-legit", 500, 1000, 1500));

        BatchAnalysisReportResponseDto response = reportService.generateReport(
                testBatch2.getBatchNumber(),
                new BatchAnalysisReportRequestDto(reqId)
        );

        assertThat(response.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);
        assertThat(response.getStructuredAnalysis().getQaDispositionStatus())
                .startsWith("Authoritative QA Disposition: RELEASED")
                .contains(testBatch2.getBatchNumber())
                .contains("formal Quality Assurance release disposition");
    }

    // --------------------------------------------------------------------------------------
    // Test 9: Existing Copilot functionality remains unchanged
    // --------------------------------------------------------------------------------------
    @Test
    @DisplayName("9. Existing Copilot functionality operates completely independent and unaffected")
    void testExistingCopilotFunctionalityRemainsUnchanged() {
        // Verify Copilot conversation creation and isolation
        CopilotConversation conversation = new CopilotConversation(
                testSupervisor,
                "Independent Copilot Conversation",
                "BATCH_TRACEABILITY",
                "BATCH",
                testBatch1.getBatchNumber()
        );
        conversation = conversationRepository.saveAndFlush(conversation);

        assertThat(conversation.getId()).isNotNull();
        assertThat(conversation.getTitle()).isEqualTo("Independent Copilot Conversation");
        assertThat(conversation.getContextId()).isEqualTo(testBatch1.getBatchNumber());

        // BatchAnalysisReport table should not have any conversation records
        List<BatchAnalysisReport> reports = reportRepository.findByBatchIdOrderByCreatedAtDesc(testBatch1.getId());
        for (BatchAnalysisReport r : reports) {
            assertThat(r.getClientRequestId()).doesNotStartWith("COPILOT-");
        }
    }

    // --------------------------------------------------------------------------------------
    // Helper Methods
    // --------------------------------------------------------------------------------------
    private String createValid12SectionJson() {
        return """
                {
                  "executiveSummary": "Executive summary content here.",
                  "batchStatusAndProgress": "Batch completed manufacturing on schedule.",
                  "plannedVsActualMetrics": "Yield is 98.50% with -1.5 kg variance within tolerance.",
                  "materialTraceability": "All raw materials dispensed and reconciled.",
                  "ebrStepEvaluation": "Step 1 dispensing completed successfully.",
                  "qcInspectionAnalysis": "All in-process quality checks passed.",
                  "qaDispositionStatus": "Batch is awaiting formal QA review.",
                  "deviationsAndCapa": "No deviations recorded.",
                  "chronologicalAuditAnalysis": "No chronology anomalies observed.",
                  "missingRecords": "No documentation gaps identified.",
                  "riskObservations": "Low operational risk.",
                  "followUpRecommendations": "Submit dossier to QA for formal disposition review."
                }
                """;
    }

    private String createValid12SectionJsonWithQaSection(String qaSectionContent) {
        String base = """
                {
                  "executiveSummary": "Executive summary content here.",
                  "batchStatusAndProgress": "Batch completed manufacturing on schedule.",
                  "plannedVsActualMetrics": "Yield is 98.50% with -1.5 kg variance within tolerance.",
                  "materialTraceability": "All raw materials dispensed and reconciled.",
                  "ebrStepEvaluation": "Step 1 dispensing completed successfully.",
                  "qcInspectionAnalysis": "All in-process quality checks passed.",
                  "qaDispositionStatus": "__QA_PLACEHOLDER__",
                  "deviationsAndCapa": "No deviations recorded.",
                  "chronologicalAuditAnalysis": "No chronology anomalies observed.",
                  "missingRecords": "No documentation gaps identified.",
                  "riskObservations": "Low operational risk.",
                  "followUpRecommendations": "Submit dossier to QA for formal disposition review."
                }
                """;
        return base.replace("__QA_PLACEHOLDER__", qaSectionContent.replace("\"", "\\\""));
    }

    private String createValid12SectionJsonWithExecutiveSummary(String executiveSummaryContent) {
        String base = """
                {
                  "executiveSummary": "__EXEC_PLACEHOLDER__",
                  "batchStatusAndProgress": "Batch completed manufacturing on schedule.",
                  "plannedVsActualMetrics": "Yield is 98.50% with -1.5 kg variance within tolerance.",
                  "materialTraceability": "All raw materials dispensed and reconciled.",
                  "ebrStepEvaluation": "Step 1 dispensing completed successfully.",
                  "qcInspectionAnalysis": "All in-process quality checks passed.",
                  "qaDispositionStatus": "Batch is awaiting formal QA review.",
                  "deviationsAndCapa": "No deviations recorded.",
                  "chronologicalAuditAnalysis": "No chronology anomalies observed.",
                  "missingRecords": "No documentation gaps identified.",
                  "riskObservations": "Low operational risk.",
                  "followUpRecommendations": "Submit dossier to QA for formal disposition review."
                }
                """;
        return base.replace("__EXEC_PLACEHOLDER__", executiveSummaryContent.replace("\"", "\\\""));
    }
}
