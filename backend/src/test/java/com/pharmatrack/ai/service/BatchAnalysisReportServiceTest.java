package com.pharmatrack.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.client.LlmClient;
import com.pharmatrack.ai.client.LlmClientException;
import com.pharmatrack.ai.client.LlmResponse;
import com.pharmatrack.ai.config.AiCopilotProperties;
import com.pharmatrack.ai.dto.ArchiveReportRequestDto;
import com.pharmatrack.ai.dto.BatchAnalysisMetricsDto;
import com.pharmatrack.ai.dto.BatchAnalysisReportRequestDto;
import com.pharmatrack.ai.dto.BatchAnalysisAreaFindingDto;
import com.pharmatrack.ai.dto.BatchAnalysisReportResponseDto;
import com.pharmatrack.ai.dto.BatchAnalysisSectionsDto;
import com.pharmatrack.ai.dto.LinkedRecommendationDto;
import com.pharmatrack.ai.dto.PermanentDeleteReportRequestDto;
import com.pharmatrack.ai.dto.RecordAssessmentDto;
import com.pharmatrack.ai.entity.BatchAnalysisFocusArea;
import com.pharmatrack.ai.entity.BatchAnalysisMode;
import com.pharmatrack.ai.entity.BatchAnalysisReport;
import com.pharmatrack.ai.entity.RecordLifecycleClassification;
import com.pharmatrack.ai.entity.BatchAnalysisReportStatus;
import com.pharmatrack.ai.exception.BatchAnalysisConflictException;
import com.pharmatrack.ai.exception.BatchAnalysisValidationException;
import com.pharmatrack.ai.repository.BatchAnalysisReportRepository;
import com.pharmatrack.dto.AiSourceReferenceDto;
import com.pharmatrack.dto.AuditLogResponseDto;
import com.pharmatrack.dto.BatchExecutionStepResponseDto;
import com.pharmatrack.dto.BatchTraceabilityDossierDto;
import com.pharmatrack.dto.DeviationDetailDto;
import com.pharmatrack.dto.OrderMaterialRequirementDto;
import com.pharmatrack.dto.QualityCheckResponseDto;
import com.pharmatrack.dto.StockMovementResponseDto;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.StockMovementType;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.BatchTraceabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BatchAnalysisReportService Unit Tests")
class BatchAnalysisReportServiceTest {

    @Mock
    private BatchAnalysisReportRepository reportRepository;

    @Mock
    private BatchAnalysisReportTxHelper txHelper;

    @Mock
    private BatchTraceabilityService batchTraceabilityService;

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private AuthenticatedActorResolver authenticatedActorResolver;

    @Mock
    private BatchContextBuilder batchContextBuilder;

    @Mock
    private LlmClient llmClient;

    @Mock
    private AuditTrailService auditTrailService;

    @Mock
    private LocalEnvironmentDeletionGuard deletionGuard;

    private ObjectMapper objectMapper;
    private BatchAnalysisReportService service;

    private UserAccount supervisor;
    private Batch testBatch;
    private BatchTraceabilityDossierDto testDossier;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        service = new BatchAnalysisReportService(
                reportRepository,
                txHelper,
                batchTraceabilityService,
                batchRepository,
                userAccountRepository,
                authenticatedActorResolver,
                batchContextBuilder,
                llmClient,
                objectMapper,
                auditTrailService,
                new BatchLifecycleRecordClassifier(),
                new AiCopilotProperties(),
                deletionGuard
        );

        supervisor = new UserAccount(
                "supervisor_alice",
                "hash",
                "Alice Supervisor",
                Role.PRODUCTION_SUPERVISOR,
                true
        );

        testBatch = new Batch();
        testBatch.setBatchNumber("BAT-2026-0001-01");
        testBatch.setBatchSize(new BigDecimal("1000.0000"));
        testBatch.setActualYield(new BigDecimal("985.5000"));
        testBatch.setBatchUnit("kg");
        testBatch.setStatus(BatchStatus.COMPLETED);
        testBatch.setQaStatus(BatchQualityStatus.PENDING_QA);

        testDossier = new BatchTraceabilityDossierDto();
        testDossier.setBatchId(10L);
        testDossier.setBatchNumber("BAT-2026-0001-01");
        testDossier.setOrderNumber("MO-2026-0001");
        testDossier.setProductName("Amoxicillin 500mg");
        testDossier.setProductCode("AMOX-500");
        testDossier.setBatchSize(new BigDecimal("1000.0000"));
        testDossier.setActualYield(new BigDecimal("985.5000"));
        testDossier.setBatchUnit("kg");
        testDossier.setBatchStatus(BatchStatus.COMPLETED);
        testDossier.setQaStatus(BatchQualityStatus.PENDING_QA);

        BatchExecutionStepResponseDto step = new BatchExecutionStepResponseDto();
        step.setStepNumber(1);
        testDossier.setExecutionSteps(List.of(step));

        QualityCheckResponseDto qc = new QualityCheckResponseDto();
        qc.setTestName("Assay");
        testDossier.setQualityChecks(List.of(qc));

        DeviationDetailDto dev = new DeviationDetailDto();
        dev.setDeviationNumber("DEV-2026-001");
        testDossier.setDeviations(List.of(dev));
    }

    private void mockSupervisorAuth() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
    }

    private String createValid12SectionJson() {
        return """
                {
                  "executiveSummary": "Executive summary content here.",
                  "batchStatusAndProgress": "Batch completed manufacturing on schedule.",
                  "plannedVsActualMetrics": "Yield is 98.55% with -14.5 kg variance within tolerance.",
                  "materialTraceability": "All raw materials dispensed and reconciled.",
                  "ebrStepEvaluation": "Step 1 dispensing completed successfully.",
                  "qcInspectionAnalysis": "All in-process quality checks passed.",
                  "qaDispositionStatus": "Batch is currently in PENDING_QA status awaiting QA disposition.",
                  "deviationsAndCapa": "Minor deviation DEV-2026-001 closed with no impact.",
                  "chronologicalAuditAnalysis": "No chronology anomalies observed.",
                  "missingRecords": "No documentation gaps identified.",
                  "riskObservations": "Low operational risk.",
                  "followUpRecommendations": "Submit dossier to QA for formal disposition review."
                }
                """;
    }

    @Test
    @DisplayName("Should successfully generate report when authorized with valid LLM output")
    void shouldGenerateReportSuccessfully() {
        mockSupervisorAuth();

        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);
        when(batchRepository.findById(10L)).thenReturn(Optional.of(testBatch));

        BatchAnalysisReport generatingReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-001");
        when(txHelper.getOrCreateInitialReportTx(eq(testBatch), eq(supervisor), eq("REQ-001"), any(), any())).thenReturn(generatingReport);

        BatchContext batchContext = new BatchContext(
                "<<<VERIFIED_PHARMATRACK_DATA>>>\nFacts\n<<</VERIFIED_PHARMATRACK_DATA>>>",
                List.of(new AiSourceReferenceDto("BATCH", "BAT-2026-0001-01", "Batch Record", "traceability"))
        );
        when(batchContextBuilder.buildBatchContext(testDossier)).thenReturn(batchContext);

        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.generate(anyString(), anyString(), anyInt())).thenReturn(
                new LlmResponse(createValid12SectionJson(), "gpt-4o-mini", "resp-123", 100, 200, 300)
        );

        BatchAnalysisReport completedReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-001");
        completedReport.complete(
                "gpt-4o-mini",
                new BigDecimal("1000.0000"),
                new BigDecimal("985.5000"),
                new BigDecimal("98.5500"),
                new BigDecimal("-14.5000"),
                1,
                1,
                1,
                createValid12SectionJson(),
                "# Markdown",
                "[]"
        );
        when(txHelper.completeReportTx(any(), any(), any(), any(), any(), any(), any())).thenReturn(completedReport);

        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("REQ-001");
        BatchAnalysisReportResponseDto response = service.generateReport("BAT-2026-0001-01", request);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);
        assertThat(response.getBatchNumber()).isEqualTo("BAT-2026-0001-01");
        assertThat(response.getDeterministicMetrics().getYieldPercent()).isEqualTo(new BigDecimal("98.5500"));
        assertThat(response.getDeterministicMetrics().getYieldVariance()).isEqualTo(new BigDecimal("-14.5000"));
        assertThat(response.getDeterministicMetrics().getExecutionStepCount()).isEqualTo(1);
        assertThat(response.getDeterministicMetrics().getQcCheckCount()).isEqualTo(1);
        assertThat(response.getDeterministicMetrics().getDeviationCount()).isEqualTo(1);
        assertThat(response.getStructuredAnalysis().getExecutiveSummary()).isEqualTo("Executive summary content here.");
        assertThat(response.getRegulatoryDisclaimer()).contains("ADVISORY ONLY");
    }

    @Test
    @DisplayName("Should return existing report without calling LLM if clientRequestId is already COMPLETED")
    void shouldReturnExistingReportWhenAlreadyCompleted() {
        mockSupervisorAuth();

        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);
        when(batchRepository.findById(10L)).thenReturn(Optional.of(testBatch));

        BatchAnalysisReport completedReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-ALREADY-DONE");
        completedReport.complete(
                "gpt-4o-mini",
                new BigDecimal("1000.0000"),
                new BigDecimal("985.5000"),
                new BigDecimal("98.5500"),
                new BigDecimal("-14.5000"),
                1,
                1,
                1,
                createValid12SectionJson(),
                "# Historical Report",
                "[]"
        );
        when(txHelper.getOrCreateInitialReportTx(eq(testBatch), eq(supervisor), eq("REQ-ALREADY-DONE"), any(), any()))
                .thenReturn(completedReport);

        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("REQ-ALREADY-DONE");
        BatchAnalysisReportResponseDto response = service.generateReport("BAT-2026-0001-01", request);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);
        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Should calculate deterministic KPIs accurately with proper null handling")
    void shouldCalculateDeterministicMetricsCorrectly() {
        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(testDossier);

        assertThat(metrics.getPlannedBatchSize()).isEqualTo(new BigDecimal("1000.0000"));
        assertThat(metrics.getActualYield()).isEqualTo(new BigDecimal("985.5000"));
        assertThat(metrics.getYieldPercent()).isEqualTo(new BigDecimal("98.5500"));
        assertThat(metrics.getYieldVariance()).isEqualTo(new BigDecimal("-14.5000"));
        assertThat(metrics.getExecutionStepCount()).isEqualTo(1);
        assertThat(metrics.getQcCheckCount()).isEqualTo(1);
        assertThat(metrics.getDeviationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should preserve nulls for incomplete batch data without converting to zero")
    void shouldHandleMissingYieldAndMissingRecordsWithNulls() {
        BatchTraceabilityDossierDto incompleteDossier = new BatchTraceabilityDossierDto();
        incompleteDossier.setBatchSize(new BigDecimal("500.0000"));
        incompleteDossier.setActualYield(null);
        incompleteDossier.setExecutionSteps(null);
        incompleteDossier.setQualityChecks(null);
        incompleteDossier.setDeviations(null);

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(incompleteDossier);

        assertThat(metrics.getPlannedBatchSize()).isEqualTo(new BigDecimal("500.0000"));
        assertThat(metrics.getActualYield()).isNull();
        assertThat(metrics.getYieldPercent()).isNull();
        assertThat(metrics.getYieldVariance()).isNull();
        assertThat(metrics.getExecutionStepCount()).isNull();
        assertThat(metrics.getQcCheckCount()).isNull();
        assertThat(metrics.getDeviationCount()).isNull();
    }

    @Test
    @DisplayName("Should prevent division by zero when batchSize is zero or negative")
    void shouldPreventDivisionByZero() {
        BatchTraceabilityDossierDto zeroSizeDossier = new BatchTraceabilityDossierDto();
        zeroSizeDossier.setBatchSize(BigDecimal.ZERO);
        zeroSizeDossier.setActualYield(new BigDecimal("100.0000"));

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(zeroSizeDossier);
        assertThat(metrics.getYieldPercent()).isNull();
        assertThat(metrics.getYieldVariance()).isNull();
    }

    @Test
    @DisplayName("Should reject invalid JSON from LLM and record FAILED state")
    void shouldRejectInvalidJsonFromLlm() {
        mockSupervisorAuth();

        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);
        when(batchRepository.findById(10L)).thenReturn(Optional.of(testBatch));

        BatchAnalysisReport generatingReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-INVALID-JSON");
        when(txHelper.getOrCreateInitialReportTx(any(), any(), any(), any(), any())).thenReturn(generatingReport);

        when(batchContextBuilder.buildBatchContext(testDossier)).thenReturn(new BatchContext("Facts", List.of()));
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.generate(anyString(), anyString(), anyInt())).thenReturn(
                new LlmResponse("This is not valid JSON at all", "gpt-4o-mini", "resp-err", 10, 10, 20)
        );

        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("REQ-INVALID-JSON");

        assertThatThrownBy(() -> service.generateReport("BAT-2026-0001-01", request))
                .isInstanceOf(BatchAnalysisValidationException.class)
                .hasMessageContaining("Failed to parse AI response as valid JSON");

        verify(txHelper).failReportTx(any(), contains("Failed to parse AI response as valid JSON"), eq("supervisor_alice"));
    }

    @Test
    @DisplayName("Should reject model response when required sections are missing")
    void shouldRejectMissingRequiredSections() {
        String incompleteJson = """
                {
                  "executiveSummary": "Summary here",
                  "batchStatusAndProgress": "Progress here"
                }
                """;

        assertThatThrownBy(() -> service.parseAndValidateSections(incompleteJson, testDossier))
                .isInstanceOf(BatchAnalysisValidationException.class)
                .hasMessageContaining("Missing, non-textual, or blank required report section");
    }

    @Test
    @DisplayName("Should reject model output if it contains unauthorized QA release claims for unreleased batch")
    void shouldRejectUnsupportedClaimOfCompletedQaRelease() {
        testDossier.setQaStatus(BatchQualityStatus.PENDING_QA);

        List<String> alternativeForbiddenPhrases = List.of(
                "The batch has been released for commercial distribution.",
                "The batch is formally approved for market dispatch.",
                "The batch has passed QA disposition and is ready for release.",
                "Product release has been granted by quality assurance.",
                "The batch is authorized for distribution.",
                "The batch is cleared for commercial use."
        );

        for (String forbiddenPhrase : alternativeForbiddenPhrases) {
            String jsonWithClaim = String.format("""
                    {
                      "executiveSummary": "Executive summary with claim: %s",
                      "batchStatusAndProgress": "Progress.",
                      "plannedVsActualMetrics": "Metrics.",
                      "materialTraceability": "Traceability.",
                      "ebrStepEvaluation": "eBR evaluation.",
                      "qcInspectionAnalysis": "QC inspection.",
                      "qaDispositionStatus": "Awaiting disposition.",
                      "deviationsAndCapa": "None.",
                      "chronologicalAuditAnalysis": "Audit.",
                      "missingRecords": "None.",
                      "riskObservations": "None.",
                      "followUpRecommendations": "None."
                    }
                    """, forbiddenPhrase);

            assertThatThrownBy(() -> service.parseAndValidateSections(jsonWithClaim, testDossier))
                    .isInstanceOf(BatchAnalysisValidationException.class);
        }
    }

    @Test
    @DisplayName("Should reject compound sentences attempting to bypass release checks with pending clauses")
    void shouldRejectCompoundSentencesAttemptingToWhitelistReleaseClaims() {
        testDossier.setQaStatus(BatchQualityStatus.PENDING_QA);

        List<String> compoundSentencesWithClaims = List.of(
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

        for (String compoundClaim : compoundSentencesWithClaims) {
            String jsonWithClaim = String.format("""
                    {
                      "executiveSummary": "Analysis note: %s",
                      "batchStatusAndProgress": "Progress.",
                      "plannedVsActualMetrics": "Metrics.",
                      "materialTraceability": "Traceability.",
                      "ebrStepEvaluation": "eBR evaluation.",
                      "qcInspectionAnalysis": "QC inspection.",
                      "qaDispositionStatus": "Awaiting disposition.",
                      "deviationsAndCapa": "None.",
                      "chronologicalAuditAnalysis": "Audit.",
                      "missingRecords": "None.",
                      "riskObservations": "None.",
                      "followUpRecommendations": "None."
                    }
                    """, compoundClaim);

            assertThatThrownBy(() -> service.parseAndValidateSections(jsonWithClaim, testDossier))
                    .isInstanceOf(BatchAnalysisValidationException.class);
        }
    }

    @Test
    @DisplayName("Should permit legitimate negative and pending statements without false-positive rejection")
    void shouldPermitLegitimateNegativeAndPendingStatements() {
        testDossier.setQaStatus(BatchQualityStatus.PENDING_QA);

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

        for (String statement : legitimateStatements) {
            String json = String.format("""
                    {
                      "executiveSummary": "Factual status: %s",
                      "batchStatusAndProgress": "Progress.",
                      "plannedVsActualMetrics": "Metrics.",
                      "materialTraceability": "Traceability.",
                      "ebrStepEvaluation": "eBR evaluation.",
                      "qcInspectionAnalysis": "QC inspection.",
                      "qaDispositionStatus": "Awaiting disposition.",
                      "deviationsAndCapa": "None.",
                      "chronologicalAuditAnalysis": "Audit.",
                      "missingRecords": "None.",
                      "riskObservations": "None.",
                      "followUpRecommendations": "None."
                    }
                    """, statement);

            BatchAnalysisSectionsDto parsed = service.parseAndValidateSections(json, testDossier);
            assertThat(parsed).isNotNull();
            assertThat(parsed.getQaDispositionStatus())
                    .contains("Authoritative QA Disposition: PENDING_QA (Not Released)")
                    .contains("formal batch release has NOT been granted");
        }
    }

    @Test
    @DisplayName("Should permit factual statements of pending or unreleased QA status without rejection")
    void shouldPermitFactualUnreleasedStatements() {
        testDossier.setQaStatus(BatchQualityStatus.PENDING_QA);

        String legitimatePendingJson = """
                {
                  "executiveSummary": "Manufacturing complete, but batch is not released and awaiting QA review.",
                  "batchStatusAndProgress": "Production completed, prior to release by Quality.",
                  "plannedVsActualMetrics": "Metrics evaluated.",
                  "materialTraceability": "All lots verified.",
                  "ebrStepEvaluation": "All steps signed off.",
                  "qcInspectionAnalysis": "Tests performed pending final sign-off.",
                  "qaDispositionStatus": "Batch is unreleased in PENDING_QA status.",
                  "deviationsAndCapa": "No critical deviations.",
                  "chronologicalAuditAnalysis": "No anomalies.",
                  "missingRecords": "None.",
                  "riskObservations": "No release can occur until QA approves.",
                  "followUpRecommendations": "Submit to QA."
                }
                """;

        BatchAnalysisSectionsDto parsed = service.parseAndValidateSections(legitimatePendingJson, testDossier);
        assertThat(parsed).isNotNull();
        // Section 7 must be programmatically set to authoritative statement
        assertThat(parsed.getQaDispositionStatus())
                .contains("Authoritative QA Disposition: PENDING_QA (Not Released)")
                .contains("formal batch release has NOT been granted");
    }

    @Test
    @DisplayName("Should accurately represent legitimate RELEASED status from authoritative MES dossier")
    void shouldAccuratelyRepresentLegitimateReleasedStatus() {
        testDossier.setQaStatus(BatchQualityStatus.RELEASED);
        testDossier.setBatchStatus(BatchStatus.COMPLETED);

        String releasedJson = """
                {
                  "executiveSummary": "Batch successfully completed and released.",
                  "batchStatusAndProgress": "Batch is fully released in MES.",
                  "plannedVsActualMetrics": "Metrics verified.",
                  "materialTraceability": "All materials reconciled.",
                  "ebrStepEvaluation": "Executed properly.",
                  "qcInspectionAnalysis": "All QC tests passed specifications.",
                  "qaDispositionStatus": "QA released.",
                  "deviationsAndCapa": "No open deviations.",
                  "chronologicalAuditAnalysis": "Orderly execution.",
                  "missingRecords": "None.",
                  "riskObservations": "Low risk.",
                  "followUpRecommendations": "Archival."
                }
                """;

        BatchAnalysisSectionsDto parsed = service.parseAndValidateSections(releasedJson, testDossier);
        assertThat(parsed).isNotNull();
        assertThat(parsed.getQaDispositionStatus())
                .contains("Authoritative QA Disposition: RELEASED")
                .contains("formal Quality Assurance release disposition in MES records");
    }

    @Test
    @DisplayName("Should handle LLM provider exception and persist sanitized FAILED status")
    void shouldHandleProviderTimeoutOrFailure() {
        mockSupervisorAuth();

        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);
        when(batchRepository.findById(10L)).thenReturn(Optional.of(testBatch));

        BatchAnalysisReport generatingReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-TIMEOUT");
        when(txHelper.getOrCreateInitialReportTx(any(), any(), any(), any(), any())).thenReturn(generatingReport);

        when(batchContextBuilder.buildBatchContext(testDossier)).thenReturn(new BatchContext("Facts", List.of()));
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.generate(anyString(), anyString(), anyInt())).thenThrow(
                new LlmClientException(LlmClientException.ErrorCategory.TIMEOUT, "Gateway Timeout with Bearer secretToken1234567890")
        );

        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("REQ-TIMEOUT");

        assertThatThrownBy(() -> service.generateReport("BAT-2026-0001-01", request))
                .isInstanceOf(LlmClientException.class);

        // Verify sensitive token was redacted
        verify(txHelper).failReportTx(any(), argThat(msg -> msg != null && !msg.contains("secretToken1234567890")), eq("supervisor_alice"));
    }

    @Test
    @DisplayName("Should reject non-supervisor roles from initiating batch report generation")
    void shouldEnforceProductionSupervisorRole() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR")).thenReturn(false);

        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("REQ-ROLE-TEST");

        assertThatThrownBy(() -> service.generateReport("BAT-2026-0001-01", request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("restricted to Production Supervisors");

        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Should reject generation if user was demoted in database even if security token claims supervisor")
    void shouldEnforceRoleAgainstDatabaseRecord() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");

        UserAccount demotedUser = new UserAccount("supervisor_alice", "hash", "Alice", Role.OPERATOR, true);
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(demotedUser));

        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("REQ-DEMOTED-TEST");

        assertThatThrownBy(() -> service.generateReport("BAT-2026-0001-01", request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("restricted to Production Supervisors");

        verify(llmClient, never()).generate(anyString(), anyString());
    }

    @Test
    @DisplayName("Should enforce completed report immutability at entity level")
    void shouldPreventMutationOfCompletedReport() {
        BatchAnalysisReport report = new BatchAnalysisReport(testBatch, supervisor, "REQ-IMMUTABLE");
        report.complete("model-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, 1, 1, 0, "{}", "#", "[]");

        assertThatThrownBy(() -> report.complete("model-2", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, "{}", "#", "[]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already COMPLETED and is immutable");

        assertThatThrownBy(() -> report.fail("Should not fail"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already COMPLETED and immutable");
    }

    @Test
    @DisplayName("Should prevent marking a FAILED report as COMPLETED")
    void shouldPreventTransitionFromFailedToCompleted() {
        BatchAnalysisReport report = new BatchAnalysisReport(testBatch, supervisor, "REQ-FAILED");
        report.fail("Initial failure");

        assertThatThrownBy(() -> report.complete("model-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, 1, 1, 0, "{}", "#", "[]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot complete report");
    }

    @Test
    @DisplayName("Section 10: Should generate deterministic missing records evaluation for PLANNED batch without false gaps")
    void shouldGenerateDeterministicMissingRecordsEvaluationForPlannedBatch() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);
        plannedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        plannedDossier.setBatchSize(new BigDecimal("250.0000"));
        plannedDossier.setActualYield(null);
        plannedDossier.setExecutionSteps(List.of());
        plannedDossier.setQualityChecks(List.of());
        plannedDossier.setDeviations(List.of());

        String section10 = service.generateDeterministicMissingRecordsEvaluation(plannedDossier);

        assertThat(section10).isNotBlank();
        assertThat(section10)
                .contains("Batch execution status is PLANNED")
                .contains("Not yet expected. Production start time, end time, and actual yield are recorded during and upon completion")
                .contains("0 steps recorded. Execution steps are not yet initialized because production has not started")
                .contains("QC applicability is UNKNOWN because the required configuration is unavailable in the records examined. No QC inspections are recorded. Available information does not establish whether QC testing is applicable to this batch. QC testing must not be assumed to be exempt or unnecessary (under authoritative QA disposition rules, at least one passing required QC check must be recorded prior to batch release).")
                .contains("No deviations are recorded in the available MES records. Zero recorded deviations is NOT a missing record")
                .contains("PENDING_QA. Manufacturing execution status is PLANNED (manufacturing has not started). Available lifecycle data does not establish whether formal QA review is currently due.")
                .contains("No confirmed missing execution records identified for the current lifecycle stage. Note: Master recipe QC configuration is unavailable in recorded data; QC applicability remains unverified and is classified as UNKNOWN rather than confirmed complete")
                .doesNotContain("not overdue")
                .doesNotContain("overdue approval")
                .doesNotContain("within SLA");

        // Must strictly NOT contain false error markers or compliance assumptions
        assertThat(section10).doesNotContain("MISSING RECORD");
        assertThat(section10).doesNotContain("MISSING RECORDS");
        assertThat(section10).doesNotContain("100% compliant");
        assertThat(section10).doesNotContain("Conforming execution state");
        assertThat(section10).doesNotContain("compliant operational outcome");
    }

    @Test
    @DisplayName("Section 10: Should identify and enumerate actual verified gaps for COMPLETED batch")
    void shouldIdentifyVerifiedGapsForCompletedBatch() {
        BatchTraceabilityDossierDto completedDossier = new BatchTraceabilityDossierDto();
        completedDossier.setBatchNumber("BAT-2026-0005-01");
        completedDossier.setBatchStatus(BatchStatus.COMPLETED);
        completedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        completedDossier.setBatchSize(new BigDecimal("1000.0000"));
        completedDossier.setActualYield(null); // Missing actual yield on COMPLETED batch
        completedDossier.setEndTime(null);     // Missing completion timestamp on COMPLETED batch
        completedDossier.setQualityChecks(List.of()); // 0 QC on COMPLETED batch - applicability cannot be determined
        completedDossier.setDeviations(List.of()); // Zero deviations

        String section10 = service.generateDeterministicMissingRecordsEvaluation(completedDossier);

        assertThat(section10).isNotBlank();
        assertThat(section10)
                .contains("Batch execution status is COMPLETED")
                .contains("**Actual Yield:** MISSING RECORD")
                .contains("**Completion Timestamp:** MISSING RECORD")
                .contains("**Quality Control (QC):** 0 inspections recorded. Specific QC check requirements are not configured in available recipe or master data; QC applicability cannot be determined from available records alone")
                .contains("**Deviations & CAPA:** No deviations are recorded in the available MES records. Zero recorded deviations is NOT a missing record")
                .doesNotContain("100% compliant")
                .doesNotContain("Conforming execution state")
                .contains("The following 3 verified documentation gap(s) require attention:");
    }

    @Test
    @DisplayName("Section 10: Should report missing QC records when required QC checks are established and incomplete on COMPLETED batch")
    void shouldReportMissingQcWhenRequiredChecksAreIncompleteOnCompletedBatch() {
        BatchTraceabilityDossierDto completedDossier = new BatchTraceabilityDossierDto();
        completedDossier.setBatchNumber("BAT-2026-0005-02");
        completedDossier.setBatchStatus(BatchStatus.COMPLETED);
        completedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        completedDossier.setBatchSize(new BigDecimal("1000.0000"));
        completedDossier.setActualYield(new BigDecimal("980.0000"));
        completedDossier.setEndTime(java.time.LocalDateTime.now());
        completedDossier.setDeviations(List.of());

        com.pharmatrack.dto.BatchExecutionStepResponseDto step = new com.pharmatrack.dto.BatchExecutionStepResponseDto();
        step.setStepNumber(1);
        step.setStatus(com.pharmatrack.entity.ExecutionStepStatus.COMPLETED);
        completedDossier.setExecutionSteps(List.of(step));

        com.pharmatrack.dto.QualityCheckResponseDto requiredQc = new com.pharmatrack.dto.QualityCheckResponseDto();
        requiredQc.setTestName("Assay Testing");
        requiredQc.setRequired(true);
        requiredQc.setResult(com.pharmatrack.entity.QualityCheckResult.PENDING);
        requiredQc.setMeasuredValue(null);
        requiredQc.setInspectorName(null);

        com.pharmatrack.dto.QualityCheckResponseDto passQcWithoutMeasurement = new com.pharmatrack.dto.QualityCheckResponseDto();
        passQcWithoutMeasurement.setTestName("Dissolution");
        passQcWithoutMeasurement.setRequired(true);
        passQcWithoutMeasurement.setResult(com.pharmatrack.entity.QualityCheckResult.PASS);
        passQcWithoutMeasurement.setMeasuredValue(null);
        passQcWithoutMeasurement.setInspectorName("analyst_bob");

        completedDossier.setQualityChecks(List.of(requiredQc, passQcWithoutMeasurement));

        String section10 = service.generateDeterministicMissingRecordsEvaluation(completedDossier);

        assertThat(section10).isNotBlank();
        assertThat(section10)
                .contains("2 inspection(s) recorded (2 required, 0 optional")
                .contains("Required QC check 'Dissolution' recorded without measured analytical value.")
                .doesNotContain("Required QC check 'Assay Testing' recorded without measured analytical value.")
                .contains("Required QC check 'Assay Testing' missing inspector sign-off.")
                .contains("Required QC check 'Assay Testing' remains PENDING on completed batch.")
                .contains("verified documentation gap(s) require attention");
    }

    @Test
    @DisplayName("Section 10 QC: CANCELLED batch with PENDING check (null measured value, valid inspector) does not report measured-value gap")
    void section10_cancelledBatch_pendingCheck_doesNotReportMeasuredValueGap() {
        BatchTraceabilityDossierDto cancelledDossier = new BatchTraceabilityDossierDto();
        cancelledDossier.setBatchNumber("BAT-CANCELLED-01");
        cancelledDossier.setBatchStatus(BatchStatus.CANCELLED);
        cancelledDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        cancelledDossier.setBatchSize(new BigDecimal("500.0000"));
        cancelledDossier.setStartTime(LocalDateTime.of(2026, 11, 10, 8, 0, 0));
        cancelledDossier.setEndTime(null);
        cancelledDossier.setActualYield(null);
        cancelledDossier.setExecutionSteps(List.of());
        cancelledDossier.setDeviations(List.of());

        QualityCheckResponseDto pendingCheck = new QualityCheckResponseDto();
        pendingCheck.setTestName("In-Process pH");
        pendingCheck.setRequired(true);
        pendingCheck.setResult(QualityCheckResult.PENDING);
        pendingCheck.setMeasuredValue(null);
        pendingCheck.setInspectorName("inspector_alice");
        cancelledDossier.setQualityChecks(List.of(pendingCheck));

        String section10 = service.generateDeterministicMissingRecordsEvaluation(cancelledDossier);

        assertThat(section10)
                .contains("1 inspection(s) recorded (1 required, 0 optional: 0 PASS, 0 FAIL, 1 PENDING)")
                .contains("No missing required fields were identified for the current batch lifecycle stage. 1 QC check(s) remain PENDING; their measured values have not yet been recorded because testing is incomplete.")
                .doesNotContain("All records contain required values.")
                .doesNotContain("recorded without measured analytical value")
                .doesNotContain("missing inspector sign-off")
                .doesNotContain("remains PENDING on completed batch")
                .contains("No missing records or documentation gaps identified for the current lifecycle stage.");
    }

    @Test
    @DisplayName("Section 10 QC: CANCELLED batch with PASS check (valid measured value, valid inspector) reports no QC gap")
    void section10_cancelledBatch_passCheck_withMeasuredValue_noGap() {
        BatchTraceabilityDossierDto cancelledDossier = new BatchTraceabilityDossierDto();
        cancelledDossier.setBatchNumber("BAT-CANCELLED-02");
        cancelledDossier.setBatchStatus(BatchStatus.CANCELLED);
        cancelledDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        cancelledDossier.setBatchSize(new BigDecimal("500.0000"));
        cancelledDossier.setStartTime(LocalDateTime.of(2026, 11, 10, 8, 0, 0));
        cancelledDossier.setDeviations(List.of());

        QualityCheckResponseDto passCheck = new QualityCheckResponseDto();
        passCheck.setTestName("Bioburden Test");
        passCheck.setRequired(true);
        passCheck.setResult(QualityCheckResult.PASS);
        passCheck.setMeasuredValue("0 CFU/100ml");
        passCheck.setInspectorName("inspector_alice");
        cancelledDossier.setQualityChecks(List.of(passCheck));

        String section10 = service.generateDeterministicMissingRecordsEvaluation(cancelledDossier);

        assertThat(section10)
                .contains("1 inspection(s) recorded (1 required, 0 optional: 1 PASS, 0 FAIL, 0 PENDING)")
                .contains("All records contain required values.")
                .doesNotContain("remain PENDING")
                .doesNotContain("recorded without measured analytical value")
                .contains("No missing records or documentation gaps identified for the current lifecycle stage.");
    }

    @Test
    @DisplayName("Section 10 QC: CANCELLED batch with FAIL check (valid measured value, valid inspector) reports no measured-value gap")
    void section10_cancelledBatch_failCheck_withMeasuredValue_noMeasuredValueGap() {
        BatchTraceabilityDossierDto cancelledDossier = new BatchTraceabilityDossierDto();
        cancelledDossier.setBatchNumber("BAT-CANCELLED-03");
        cancelledDossier.setBatchStatus(BatchStatus.CANCELLED);
        cancelledDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        cancelledDossier.setBatchSize(new BigDecimal("500.0000"));
        cancelledDossier.setStartTime(LocalDateTime.of(2026, 11, 10, 8, 0, 0));
        cancelledDossier.setDeviations(List.of());

        QualityCheckResponseDto failCheck = new QualityCheckResponseDto();
        failCheck.setTestName("Assay Potency");
        failCheck.setRequired(true);
        failCheck.setResult(QualityCheckResult.FAIL);
        failCheck.setMeasuredValue("82.4%");
        failCheck.setInspectorName("inspector_alice");
        cancelledDossier.setQualityChecks(List.of(failCheck));

        String section10 = service.generateDeterministicMissingRecordsEvaluation(cancelledDossier);

        assertThat(section10)
                .contains("1 inspection(s) recorded (1 required, 0 optional: 0 PASS, 1 FAIL, 0 PENDING)")
                .contains("All records contain required values.")
                .doesNotContain("recorded without measured analytical value")
                .contains("No missing records or documentation gaps identified for the current lifecycle stage.");
    }

    @Test
    @DisplayName("Section 10 QC: CANCELLED batch with PASS check and null measured value reports documentation gap")
    void section10_cancelledBatch_passCheck_withNullMeasuredValue_reportsGap() {
        BatchTraceabilityDossierDto cancelledDossier = new BatchTraceabilityDossierDto();
        cancelledDossier.setBatchNumber("BAT-CANCELLED-04");
        cancelledDossier.setBatchStatus(BatchStatus.CANCELLED);
        cancelledDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        cancelledDossier.setBatchSize(new BigDecimal("500.0000"));
        cancelledDossier.setStartTime(LocalDateTime.of(2026, 11, 10, 8, 0, 0));
        cancelledDossier.setDeviations(List.of());

        QualityCheckResponseDto passCheck = new QualityCheckResponseDto();
        passCheck.setTestName("Moisture Content");
        passCheck.setRequired(true);
        passCheck.setResult(QualityCheckResult.PASS);
        passCheck.setMeasuredValue(null);
        passCheck.setInspectorName("inspector_alice");
        cancelledDossier.setQualityChecks(List.of(passCheck));

        String section10 = service.generateDeterministicMissingRecordsEvaluation(cancelledDossier);

        assertThat(section10)
                .contains("1 inspection(s) recorded (1 required, 0 optional: 1 PASS, 0 FAIL, 0 PENDING)")
                .contains("1 check(s) have incomplete fields or pending results.")
                .contains("Required QC check 'Moisture Content' recorded without measured analytical value.")
                .contains("The following 1 verified documentation gap(s) require attention:");
    }

    @Test
    @DisplayName("Section 10 QC: CANCELLED batch with FAIL check and blank measured value reports documentation gap")
    void section10_cancelledBatch_failCheck_withBlankMeasuredValue_reportsGap() {
        BatchTraceabilityDossierDto cancelledDossier = new BatchTraceabilityDossierDto();
        cancelledDossier.setBatchNumber("BAT-CANCELLED-05");
        cancelledDossier.setBatchStatus(BatchStatus.CANCELLED);
        cancelledDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        cancelledDossier.setBatchSize(new BigDecimal("500.0000"));
        cancelledDossier.setStartTime(LocalDateTime.of(2026, 11, 10, 8, 0, 0));
        cancelledDossier.setDeviations(List.of());

        QualityCheckResponseDto failCheck = new QualityCheckResponseDto();
        failCheck.setTestName("Hardness Test");
        failCheck.setRequired(false);
        failCheck.setResult(QualityCheckResult.FAIL);
        failCheck.setMeasuredValue("   ");
        failCheck.setInspectorName("inspector_alice");
        cancelledDossier.setQualityChecks(List.of(failCheck));

        String section10 = service.generateDeterministicMissingRecordsEvaluation(cancelledDossier);

        assertThat(section10)
                .contains("1 inspection(s) recorded (0 required, 1 optional: 0 PASS, 1 FAIL, 0 PENDING)")
                .contains("1 check(s) have incomplete fields or pending results.")
                .contains("Optional QC check 'Hardness Test' recorded without measured analytical value.")
                .contains("The following 1 verified documentation gap(s) require attention:");
    }

    @Test
    @DisplayName("Section 10 QC: CANCELLED batch with mixed PENDING (null value) and PASS (valid value) checks distinguishes states")
    void section10_cancelledBatch_mixedPendingAndPass_distinguishesStates() {
        BatchTraceabilityDossierDto cancelledDossier = new BatchTraceabilityDossierDto();
        cancelledDossier.setBatchNumber("BAT-CANCELLED-06");
        cancelledDossier.setBatchStatus(BatchStatus.CANCELLED);
        cancelledDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        cancelledDossier.setBatchSize(new BigDecimal("500.0000"));
        cancelledDossier.setStartTime(LocalDateTime.of(2026, 11, 10, 8, 0, 0));
        cancelledDossier.setDeviations(List.of());

        QualityCheckResponseDto passCheck = new QualityCheckResponseDto();
        passCheck.setTestName("Visual Clarity");
        passCheck.setRequired(true);
        passCheck.setResult(QualityCheckResult.PASS);
        passCheck.setMeasuredValue("Clear liquid");
        passCheck.setInspectorName("inspector_alice");

        QualityCheckResponseDto pendingCheck = new QualityCheckResponseDto();
        pendingCheck.setTestName("Sterility Test");
        pendingCheck.setRequired(true);
        pendingCheck.setResult(QualityCheckResult.PENDING);
        pendingCheck.setMeasuredValue(null);
        pendingCheck.setInspectorName("inspector_alice");

        cancelledDossier.setQualityChecks(List.of(passCheck, pendingCheck));

        String section10 = service.generateDeterministicMissingRecordsEvaluation(cancelledDossier);

        assertThat(section10)
                .contains("2 inspection(s) recorded (2 required, 0 optional: 1 PASS, 0 FAIL, 1 PENDING)")
                .contains("No missing required fields were identified for the current batch lifecycle stage. 1 QC check(s) remain PENDING; their measured values have not yet been recorded because testing is incomplete.")
                .doesNotContain("All records contain required values.")
                .doesNotContain("recorded without measured analytical value")
                .doesNotContain("remains PENDING on completed batch")
                .contains("No missing records or documentation gaps identified for the current lifecycle stage.");
    }

    @Test
    @DisplayName("Section 10 QC: Check with null / unsupported result is flagged as indeterminate gap")
    void section10_checkWithNullResult_flaggedAsIndeterminateGap() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-TEST-07");
        dossier.setBatchStatus(BatchStatus.IN_PROGRESS);
        dossier.setStartTime(LocalDateTime.of(2026, 11, 10, 8, 0, 0));
        dossier.setDeviations(List.of());

        BatchExecutionStepResponseDto step = new BatchExecutionStepResponseDto();
        step.setStepNumber(1);
        step.setStatus(ExecutionStepStatus.COMPLETED);
        step.setExecutedBy("operator_john");
        step.setCompletedAt(LocalDateTime.of(2026, 11, 10, 9, 0, 0));
        dossier.setExecutionSteps(List.of(step));

        QualityCheckResponseDto nullResultCheck = new QualityCheckResponseDto();
        nullResultCheck.setTestName("Dissolution Rate");
        nullResultCheck.setRequired(true);
        nullResultCheck.setResult(null);
        nullResultCheck.setMeasuredValue("95%");
        nullResultCheck.setInspectorName("inspector_alice");

        dossier.setQualityChecks(List.of(nullResultCheck));

        String section10 = service.generateDeterministicMissingRecordsEvaluation(dossier);

        assertThat(section10)
                .contains("1 inspection(s) recorded (1 required, 0 optional: 0 PASS, 0 FAIL, 0 PENDING)")
                .contains("1 check(s) have incomplete fields or pending results.")
                .contains("Required QC check 'Dissolution Rate' has null or unsupported result status.")
                .contains("The following 1 verified documentation gap(s) require attention:");
    }

    @Test
    @DisplayName("Section 10 QC: Check with missing inspector is flagged as missing inspector sign-off gap")
    void section10_checkWithMissingInspector_flaggedAsMissingSignOff() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-TEST-08");
        dossier.setBatchStatus(BatchStatus.IN_PROGRESS);
        dossier.setStartTime(LocalDateTime.of(2026, 11, 10, 8, 0, 0));
        dossier.setDeviations(List.of());

        BatchExecutionStepResponseDto step = new BatchExecutionStepResponseDto();
        step.setStepNumber(1);
        step.setStatus(ExecutionStepStatus.COMPLETED);
        step.setExecutedBy("operator_john");
        step.setCompletedAt(LocalDateTime.of(2026, 11, 10, 9, 0, 0));
        dossier.setExecutionSteps(List.of(step));

        QualityCheckResponseDto checkWithoutInspector = new QualityCheckResponseDto();
        checkWithoutInspector.setTestName("Uniformity of Dosage");
        checkWithoutInspector.setRequired(true);
        checkWithoutInspector.setResult(QualityCheckResult.PASS);
        checkWithoutInspector.setMeasuredValue("99.1%");
        checkWithoutInspector.setInspectorName("  "); // Blank inspector

        dossier.setQualityChecks(List.of(checkWithoutInspector));

        String section10 = service.generateDeterministicMissingRecordsEvaluation(dossier);

        assertThat(section10)
                .contains("Required QC check 'Uniformity of Dosage' missing inspector sign-off.")
                .contains("1 check(s) have incomplete fields or pending results.")
                .contains("The following 1 verified documentation gap(s) require attention:");
    }

    @Test
    @DisplayName("Section 10 QC: COMPLETED batch with PENDING check is flagged as remains PENDING on completed batch")
    void section10_completedBatch_withPendingCheck_flaggedAsRemainsPending() {
        BatchTraceabilityDossierDto completedDossier = new BatchTraceabilityDossierDto();
        completedDossier.setBatchNumber("BAT-TEST-09");
        completedDossier.setBatchStatus(BatchStatus.COMPLETED);
        completedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        completedDossier.setActualYield(new BigDecimal("100.0"));
        completedDossier.setEndTime(LocalDateTime.now());
        completedDossier.setDeviations(List.of());

        BatchExecutionStepResponseDto step = new BatchExecutionStepResponseDto();
        step.setStepNumber(1);
        step.setStatus(ExecutionStepStatus.COMPLETED);
        step.setExecutedBy("operator_john");
        step.setCompletedAt(LocalDateTime.of(2026, 11, 10, 9, 0, 0));
        completedDossier.setExecutionSteps(List.of(step));

        QualityCheckResponseDto pendingCheck = new QualityCheckResponseDto();
        pendingCheck.setTestName("Endotoxin Test");
        pendingCheck.setRequired(true);
        pendingCheck.setResult(QualityCheckResult.PENDING);
        pendingCheck.setMeasuredValue(null);
        pendingCheck.setInspectorName("inspector_alice");

        completedDossier.setQualityChecks(List.of(pendingCheck));

        String section10 = service.generateDeterministicMissingRecordsEvaluation(completedDossier);

        assertThat(section10)
                .contains("Required QC check 'Endotoxin Test' remains PENDING on completed batch.")
                .doesNotContain("recorded without measured analytical value")
                .doesNotContain("missing inspector sign-off")
                .contains("1 check(s) have incomplete fields or pending results.")
                .contains("The following 1 verified documentation gap(s) require attention:");
    }

    @Test
    @DisplayName("Section 10: Should never classify zero deviations as missing record or infer compliance")
    void shouldNeverClassifyZeroDeviationsOrPendingQaAsMissingRecords() {
        for (BatchStatus status : List.of(BatchStatus.PLANNED, BatchStatus.IN_PROGRESS, BatchStatus.COMPLETED)) {
            BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
            dossier.setBatchNumber("BAT-TEST");
            dossier.setBatchStatus(status);
            dossier.setQaStatus(BatchQualityStatus.PENDING_QA);
            dossier.setDeviations(List.of()); // 0 deviations

            String evaluation = service.generateDeterministicMissingRecordsEvaluation(dossier);
            assertThat(evaluation)
                    .contains("No deviations are recorded in the available MES records")
                    .contains("NOT a missing record")
                    .contains("PENDING_QA")
                    .doesNotContain("not overdue")
                    .doesNotContain("overdue approval")
                    .doesNotContain("within SLA")
                    .doesNotContain("100% compliant")
                    .doesNotContain("Conforming execution state")
                    .doesNotContain("compliant operational outcome");

            if (status == BatchStatus.PLANNED) {
                assertThat(evaluation).contains("Available lifecycle data does not establish whether formal QA review is currently due");
            } else {
                assertThat(evaluation).contains("timeliness cannot be determined");
            }
        }
    }

    @Test
    @DisplayName("Section 12: Should format run-on inline numbered recommendations into separate newline-delimited lines")
    void shouldFormatNumberedRecommendations() {
        String runOn = "1. Initiate batch production and record Batch Start Time. 2. Complete all EBR steps with operator sign-offs. 3. Perform required QC in-process tests; record results. 4. Capture actual yield.";
        String formatted = BatchAnalysisReportService.formatNumberedRecommendations(runOn);

        assertThat(formatted).isEqualTo(
                "1. Initiate batch production and record Batch Start Time.\n" +
                "2. Complete all EBR steps with operator sign-offs.\n" +
                "3. Perform required QC in-process tests; record results.\n" +
                "4. Capture actual yield."
        );
    }

    @Test
    @DisplayName("Section 12: Should handle recommendations where leading '1.' was omitted by LLM")
    void shouldFormatNumberedRecommendationsWhenFirstItemLacksNumber() {
        String missingFirst = "Initiate batch production and record Batch Start Time.\n2. Complete all EBR steps with operator sign-offs.\n3. Perform QC checks.";
        String formatted = BatchAnalysisReportService.formatNumberedRecommendations(missingFirst);

        assertThat(formatted).isEqualTo(
                "1. Initiate batch production and record Batch Start Time.\n" +
                "2. Complete all EBR steps with operator sign-offs.\n" +
                "3. Perform QC checks."
        );
    }

    @Test
    @DisplayName("Section 12: Should extract recommendations as independent list elements")
    void shouldExtractRecommendationList() {
        String text = "1. First action. 2. Second action. 3. Third action.";
        List<String> list = BatchAnalysisReportService.extractRecommendationList(text);

        assertThat(list).containsExactly(
                "First action.",
                "Second action.",
                "Third action."
        );
    }

    @Test
    @DisplayName("Section 12: Should format unnumbered newline-separated recommendations into sequential Markdown list")
    void shouldFormatUnnumberedNewlineSeparatedRecommendations() {
        String rawUnnumbered = """
                Initiate manufacturing execution by staging required raw materials at cleanroom.
                Verify equipment calibration and line clearance before dispensing active ingredients.
                Ensure electronic signatures are captured for all critical process steps.
                Stage primary packaging materials and verify lot reconciliation.
                Notify Quality Assurance upon completion for review queue routing.
                """;

        String formatted = BatchAnalysisReportService.formatNumberedRecommendations(rawUnnumbered);

        assertThat(formatted).isEqualTo(
                "1. Initiate manufacturing execution by staging required raw materials at cleanroom.\n" +
                "2. Verify equipment calibration and line clearance before dispensing active ingredients.\n" +
                "3. Ensure electronic signatures are captured for all critical process steps.\n" +
                "4. Stage primary packaging materials and verify lot reconciliation.\n" +
                "5. Notify Quality Assurance upon completion for review queue routing."
        );

        List<String> list = BatchAnalysisReportService.extractRecommendationList(rawUnnumbered);
        assertThat(list).hasSize(5);
        assertThat(list.get(0)).isEqualTo("Initiate manufacturing execution by staging required raw materials at cleanroom.");
        assertThat(list.get(4)).isEqualTo("Notify Quality Assurance upon completion for review queue routing.");
    }

    @Test
    @DisplayName("Section 12: Should preserve multi-sentence recommendations without splitting them")
    void shouldPreserveMultiSentenceRecommendations() {
        String multiSentence = "1. Verify equipment calibration before dispensing. Record calibration timestamps in the log. 2. Conduct line clearance. Ensure suite is clean.";
        String formatted = BatchAnalysisReportService.formatNumberedRecommendations(multiSentence);

        assertThat(formatted).isEqualTo(
                "1. Verify equipment calibration before dispensing. Record calibration timestamps in the log.\n" +
                "2. Conduct line clearance. Ensure suite is clean."
        );
    }

    @Test
    @DisplayName("Section 11: Should normalize risk observations inferring low risk from zero deviations")
    void shouldNormalizeRiskObservationsInferringLowRiskFromZeroDeviations() {
        String rawRisk = "No deviations or quality issues have been logged, so compliance risk remains low.";
        String normalized = BatchAnalysisReportService.normalizeRiskObservations(rawRisk, null);

        assertThat(normalized).contains("No deviations are recorded in the available MES records; a comprehensive compliance-risk conclusion cannot be established from deviation records alone.");
        assertThat(normalized).doesNotContain("compliance risk remains low");
        assertThat(normalized).doesNotContain("so compliance risk remains low");
    }

    @Test
    @DisplayName("Section 12: Should handle inline numbered recommendations with parentheses format '1)'")
    void shouldFormatInlineNumberedRecommendationsWithParentheses() {
        String input = "1) Stage materials at cleanroom. 2) Verify equipment calibration. 3) Complete all eBR steps.";
        String formatted = BatchAnalysisReportService.formatNumberedRecommendations(input);

        assertThat(formatted).isEqualTo(
                "1. Stage materials at cleanroom.\n" +
                "2. Verify equipment calibration.\n" +
                "3. Complete all eBR steps."
        );

        List<String> list = BatchAnalysisReportService.extractRecommendationList(input);
        assertThat(list).containsExactly(
                "Stage materials at cleanroom.",
                "Verify equipment calibration.",
                "Complete all eBR steps."
        );
    }

    @Test
    @DisplayName("Section 12: Should preserve introductory prose and exclude it from extracted recommendation items")
    void shouldPreserveIntroductoryProseWithoutConvertingToRecommendation() {
        String input = """
                Based on the batch analysis, the following actions are recommended:
                Stage raw materials at cleanroom suite.
                Verify equipment calibration before dispensing.
                """;

        String formatted = BatchAnalysisReportService.formatNumberedRecommendations(input);

        assertThat(formatted).contains("Based on the batch analysis, the following actions are recommended:");
        assertThat(formatted).doesNotContain("1. Based on the batch analysis");
        assertThat(formatted).contains("1. Stage raw materials at cleanroom suite.");
        assertThat(formatted).contains("2. Verify equipment calibration before dispensing.");

        List<String> list = BatchAnalysisReportService.extractRecommendationList(input);
        assertThat(list).containsExactly(
                "Stage raw materials at cleanroom suite.",
                "Verify equipment calibration before dispensing."
        );
        assertThat(list).doesNotContain("Based on the batch analysis, the following actions are recommended:");
    }

    @Test
    @DisplayName("Section 12: Should preserve Markdown headings and exclude them from extracted recommendation items")
    void shouldPreserveMarkdownHeadingsWithoutConvertingToRecommendation() {
        String input = """
                ### Immediate Actions
                1. Stage raw materials at cleanroom suite.
                2. Verify equipment calibration.
                """;

        String formatted = BatchAnalysisReportService.formatNumberedRecommendations(input);

        assertThat(formatted).contains("### Immediate Actions");
        assertThat(formatted).doesNotContain("1. ### Immediate Actions");
        assertThat(formatted).contains("1. Stage raw materials at cleanroom suite.");
        assertThat(formatted).contains("2. Verify equipment calibration.");

        List<String> list = BatchAnalysisReportService.extractRecommendationList(input);
        assertThat(list).containsExactly(
                "Stage raw materials at cleanroom suite.",
                "Verify equipment calibration."
        );
        assertThat(list).doesNotContain("### Immediate Actions");
    }

    @Test
    @DisplayName("Section 12: Should not split normal paragraphs merely because they contain numbers")
    void shouldNotSplitNormalParagraphsContainingNumbers() {
        String input = "The batch requires 250 kg of material for order 1. Cleanroom operations must follow SOP 4.";
        String formatted = BatchAnalysisReportService.formatNumberedRecommendations(input);

        assertThat(formatted).isEqualTo(input);
        assertThat(formatted).doesNotContain("1. The batch requires 250 kg");

        List<String> list = BatchAnalysisReportService.extractRecommendationList(input);
        assertThat(list).containsExactly(input);
    }

    @Test
    @DisplayName("Section 12: Should handle bullet lists and blank lines gracefully")
    void shouldHandleBulletsAndBlankLines() {
        String input = """
                * Stage primary packaging materials.

                - Verify equipment calibration.

                • Notify QA upon completion.
                """;

        String formatted = BatchAnalysisReportService.formatNumberedRecommendations(input);

        assertThat(formatted).isEqualTo(
                "1. Stage primary packaging materials.\n" +
                "2. Verify equipment calibration.\n" +
                "3. Notify QA upon completion."
        );

        List<String> list = BatchAnalysisReportService.extractRecommendationList(input);
        assertThat(list).containsExactly(
                "Stage primary packaging materials.",
                "Verify equipment calibration.",
                "Notify QA upon completion."
        );
    }

    @Test
    @DisplayName("Section 11: Should neutralize all variations inferring compliance or low risk from zero deviations")
    void shouldNeutralizeAllZeroDeviationComplianceAndRiskVariants() {
        // Variant 1: "Zero deviations recorded, indicating a fully compliant batch."
        String v1 = "Zero deviations recorded, indicating a fully compliant batch.";
        String n1 = BatchAnalysisReportService.normalizeRiskObservations(v1, null);
        assertThat(n1).contains("No deviations are recorded in the available MES records; a comprehensive compliance-risk conclusion cannot be established from deviation records alone.");
        assertThat(n1).doesNotContain("indicating a fully compliant batch");

        // Variant 2: "No significant compliance concerns."
        String v2 = "No deviations or quality issues have been logged, so there are no significant compliance concerns.";
        String n2 = BatchAnalysisReportService.normalizeRiskObservations(v2, null);
        assertThat(n2).contains("No deviations are recorded in the available MES records; a comprehensive compliance-risk conclusion cannot be established from deviation records alone.");
        assertThat(n2).doesNotContain("no significant compliance concerns");

        // Variant 3: "Risk remains low."
        String v3 = "No deviations recorded, so risk remains low.";
        String n3 = BatchAnalysisReportService.normalizeRiskObservations(v3, null);
        assertThat(n3).contains("No deviations are recorded in the available MES records; a comprehensive compliance-risk conclusion cannot be established from deviation records alone.");
        assertThat(n3).doesNotContain("so risk remains low");

        // Variant 4: "Compliance risk is minimal."
        String v4 = "Compliance risk is minimal because zero deviations occurred.";
        String n4 = BatchAnalysisReportService.normalizeRiskObservations(v4, null);
        assertThat(n4).contains("A comprehensive compliance-risk conclusion cannot be established from deviation records alone, though no deviations are recorded in available MES records.");
        assertThat(n4).doesNotContain("Compliance risk is minimal");

        // Variant 5: "No deviations were found, therefore the batch is compliant."
        String v5 = "No deviations were found, therefore the batch is compliant.";
        String n5 = BatchAnalysisReportService.normalizeRiskObservations(v5, null);
        assertThat(n5).contains("No deviations are recorded in the available MES records; a comprehensive compliance-risk conclusion cannot be established from deviation records alone.");
        assertThat(n5).doesNotContain("therefore the batch is compliant");

        // Variant 6: "The batch is GMP compliant."
        String v6 = "Zero deviations were observed; hence the batch is GMP compliant.";
        String n6 = BatchAnalysisReportService.normalizeRiskObservations(v6, null);
        assertThat(n6).contains("No deviations are recorded in the available MES records; a comprehensive compliance-risk conclusion cannot be established from deviation records alone.");
        assertThat(n6).doesNotContain("the batch is GMP compliant");
    }

    @Test
    @DisplayName("Section 11: Should preserve valid operational risk observations based on actual MES facts")
    void shouldPreserveValidOperationalRiskObservations() {
        String validRisk1 = "A temperature excursion was noted during step 3, posing an operational risk to product stability.";
        assertThat(BatchAnalysisReportService.normalizeRiskObservations(validRisk1, null)).isEqualTo(validRisk1);

        String validRisk2 = "Material dispensing for API completed within limits. Operational risk remains moderate until dissolution testing concludes.";
        assertThat(BatchAnalysisReportService.normalizeRiskObservations(validRisk2, null)).isEqualTo(validRisk2);

        String validRisk3 = "1 critical deviation was recorded for agitation speed; root cause investigation is in progress.";
        assertThat(BatchAnalysisReportService.normalizeRiskObservations(validRisk3, null)).isEqualTo(validRisk3);
    }

    @Test
    @DisplayName("Section 11: Should preserve factually grounded risk observations without zero-deviation inference")
    void shouldPreserveFactuallyGroundedRiskObservations() {
        String factualRisk = "Physical execution is currently in progress. 1 of 8 steps completed. Risk cannot be fully assessed until in-process QC tests are recorded.";
        String normalized = BatchAnalysisReportService.normalizeRiskObservations(factualRisk, null);

        assertThat(normalized).isEqualTo(factualRisk);
    }

    @Test
    @DisplayName("Archival: getReportsForBatch filters out archived reports by default")
    void getReportsForBatch_filtersArchivedByDefault() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "QA_REVIEWER", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);

        BatchAnalysisReport activeReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-1");
        activeReport.setStatus(BatchAnalysisReportStatus.COMPLETED);
        activeReport.setArchived(false);

        when(reportRepository.findByBatchIdAndArchivedOrderByCreatedAtDesc(testDossier.getBatchId(), false))
                .thenReturn(List.of(activeReport));

        List<com.pharmatrack.ai.dto.BatchAnalysisReportSummaryDto> summaries =
                service.getReportsForBatch("BAT-2026-0001-01", null, null, false);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).isArchived()).isFalse();
        verify(reportRepository).findByBatchIdAndArchivedOrderByCreatedAtDesc(testDossier.getBatchId(), false);
    }

    @Test
    @DisplayName("Archival: getReportsForBatch includes archived reports when includeArchived is true")
    void getReportsForBatch_includesArchivedWhenRequested() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "QA_REVIEWER", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);

        BatchAnalysisReport activeReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-1");
        activeReport.setStatus(BatchAnalysisReportStatus.COMPLETED);
        activeReport.setArchived(false);

        BatchAnalysisReport archivedReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-2");
        archivedReport.setStatus(BatchAnalysisReportStatus.COMPLETED);
        archivedReport.setArchived(true);
        archivedReport.setArchiveReason("Superceded");

        when(reportRepository.findByBatchIdOrderByCreatedAtDesc(testDossier.getBatchId()))
                .thenReturn(List.of(activeReport, archivedReport));

        List<com.pharmatrack.ai.dto.BatchAnalysisReportSummaryDto> summaries =
                service.getReportsForBatch("BAT-2026-0001-01", null, null, true);

        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(1).isArchived()).isTrue();
        assertThat(summaries.get(1).getArchiveReason()).isEqualTo("Superceded");
        verify(reportRepository).findByBatchIdOrderByCreatedAtDesc(testDossier.getBatchId());
    }

    @Test
    @DisplayName("Archival: archiveReport successfully marks report archived and records audit trail")
    void archiveReport_archivesAndLogsAuditTrail() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);

        BatchAnalysisReport report = new BatchAnalysisReport(testBatch, supervisor, "REQ-1");
        report.setStatus(BatchAnalysisReportStatus.COMPLETED);
        report.setArchived(false);

        when(reportRepository.findByIdAndBatchId(101L, testDossier.getBatchId())).thenReturn(Optional.of(report));
        when(reportRepository.save(any(BatchAnalysisReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ArchiveReportRequestDto requestDto = new ArchiveReportRequestDto("Superseded by Report #2");
        BatchAnalysisReportResponseDto response = service.archiveReport("BAT-2026-0001-01", 101L, requestDto);

        assertThat(response.isArchived()).isTrue();
        assertThat(response.getArchiveReason()).isEqualTo("Superseded by Report #2");
        assertThat(response.getArchivedByUsername()).isEqualTo("supervisor_alice");

        verify(auditTrailService).recordAudit(
                eq("BATCH_ANALYSIS_REPORT"),
                any(),
                eq(testBatch.getBatchNumber()),
                eq(testBatch.getId()),
                eq("ARCHIVE"),
                eq("supervisor_alice"),
                any(),
                eq("Superseded by Report #2"),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("Archival: archiveReport rejects unauthorized role (e.g. OPERATOR)")
    void archiveReport_rejectsUnauthorizedRole() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "ADMIN")).thenReturn(false);

        assertThatThrownBy(() -> service.archiveReport("BAT-2026-0001-01", 101L, new ArchiveReportRequestDto("Reason")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("Batch analysis report archival is restricted");
    }

    @Test
    @DisplayName("Archival: archiveReport rejects QA_REVIEWER role")
    void archiveReport_rejectsQaReviewer() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "ADMIN")).thenReturn(false);

        assertThatThrownBy(() -> service.archiveReport("BAT-2026-0001-01", 101L, new ArchiveReportRequestDto("Valid reason")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("Batch analysis report archival is restricted");
    }

    @Test
    @DisplayName("Archival: archiveReport rejects cross-batch report IDs with ResourceNotFoundException")
    void archiveReport_rejectsCrossBatchReportId() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);

        // Report 999 belongs to another batch, so findByIdAndBatchId returns empty
        when(reportRepository.findByIdAndBatchId(999L, testDossier.getBatchId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.archiveReport("BAT-2026-0001-01", 999L, new ArchiveReportRequestDto("Valid reason")))
                .isInstanceOf(com.pharmatrack.exception.ResourceNotFoundException.class)
                .hasMessageContaining("Report not found with id: 999 for batch: BAT-2026-0001-01");

        verify(reportRepository, never()).save(any());
        verifyNoInteractions(auditTrailService);
    }

    @Test
    @DisplayName("Archival: archiveReport is idempotent when report is already archived")
    void archiveReport_isIdempotentWhenAlreadyArchived() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);

        LocalDateTime originalArchivedAt = LocalDateTime.of(2026, 11, 10, 14, 0, 0);
        BatchAnalysisReport alreadyArchivedReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-1");
        alreadyArchivedReport.setStatus(BatchAnalysisReportStatus.COMPLETED);
        alreadyArchivedReport.setArchived(true);
        alreadyArchivedReport.setArchivedAt(originalArchivedAt);
        alreadyArchivedReport.setArchivedByUser(supervisor);
        alreadyArchivedReport.setArchiveReason("Original archive reason");

        when(reportRepository.findByIdAndBatchId(101L, testDossier.getBatchId())).thenReturn(Optional.of(alreadyArchivedReport));

        BatchAnalysisReportResponseDto response = service.archiveReport("BAT-2026-0001-01", 101L, new ArchiveReportRequestDto("Attempted new reason"));

        assertThat(response.isArchived()).isTrue();
        assertThat(response.getArchivedAt()).isEqualTo(originalArchivedAt);
        assertThat(response.getArchiveReason()).isEqualTo("Original archive reason");

        verify(reportRepository, never()).save(any());
        verifyNoInteractions(auditTrailService);
    }

    @Test
    @DisplayName("Archival: archiveReport rejects null, blank or whitespace-only reason with IllegalArgumentException")
    void archiveReport_rejectsBlankOrWhitespaceReason() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));

        assertThatThrownBy(() -> service.archiveReport("BAT-2026-0001-01", 101L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Archive reason is mandatory and cannot be blank");

        assertThatThrownBy(() -> service.archiveReport("BAT-2026-0001-01", 101L, new ArchiveReportRequestDto(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Archive reason is mandatory and cannot be blank");

        assertThatThrownBy(() -> service.archiveReport("BAT-2026-0001-01", 101L, new ArchiveReportRequestDto("   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Archive reason is mandatory and cannot be blank");

        verify(reportRepository, never()).save(any());
        verifyNoInteractions(auditTrailService);
    }

    @Test
    @DisplayName("Archival: archiveReport rejects reason exceeding 500 characters")
    void archiveReport_rejectsReasonExceeding500Chars() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));

        String excessiveReason = "A".repeat(501);
        assertThatThrownBy(() -> service.archiveReport("BAT-2026-0001-01", 101L, new ArchiveReportRequestDto(excessiveReason)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Archive reason must not exceed 500 characters");

        verify(reportRepository, never()).save(any());
        verifyNoInteractions(auditTrailService);
    }

    @Test
    @DisplayName("Archival: archiveReport strictly preserves original report content immutably")
    void archiveReport_preservesOriginalReportContentImmutably() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);

        String originalMarkdown = "# Original Batch Report\n\nPreserved verbatim.";
        String originalJson = "{\"executiveSummary\":\"Original summary\"}";
        BatchAnalysisReport report = new BatchAnalysisReport(testBatch, supervisor, "REQ-ORIGINAL");
        report.setStatus(BatchAnalysisReportStatus.COMPLETED);
        report.setModel("gpt-4");
        report.setReportMarkdown(originalMarkdown);
        report.setStructuredAnalysisJson(originalJson);
        report.setArchived(false);

        when(reportRepository.findByIdAndBatchId(101L, testDossier.getBatchId())).thenReturn(Optional.of(report));
        when(reportRepository.save(any(BatchAnalysisReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BatchAnalysisReportResponseDto response = service.archiveReport("BAT-2026-0001-01", 101L, new ArchiveReportRequestDto("Archived cleanly"));

        assertThat(response.isArchived()).isTrue();
        assertThat(report.getReportMarkdown()).isEqualTo(originalMarkdown);
        assertThat(report.getStructuredAnalysisJson()).isEqualTo(originalJson);
        assertThat(report.getModel()).isEqualTo("gpt-4");
        assertThat(report.getClientRequestId()).isEqualTo("REQ-ORIGINAL");
        assertThat(report.getStatus()).isEqualTo(BatchAnalysisReportStatus.COMPLETED);
    }

    @Test
    @DisplayName("Phase 4F.2.5.5: PENDING_QA does not produce a 'not overdue' or 'within SLA' claim in Section 10")
    void pendingQa_doesNotProduceNotOverdueClaim() {
        BatchTraceabilityDossierDto completedDossier = new BatchTraceabilityDossierDto();
        completedDossier.setBatchNumber("BAT-QA-01");
        completedDossier.setBatchStatus(BatchStatus.COMPLETED);
        completedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        completedDossier.setBatchSize(new BigDecimal("1000.0000"));
        completedDossier.setActualYield(new BigDecimal("990.0000"));
        completedDossier.setEndTime(LocalDateTime.now());
        completedDossier.setExecutionSteps(List.of());
        completedDossier.setQualityChecks(List.of());
        completedDossier.setDeviations(List.of());

        String section10 = service.generateDeterministicMissingRecordsEvaluation(completedDossier);

        assertThat(section10)
                .contains("- **QA Disposition Status:** PENDING_QA. Awaiting formal QA review following production completion. This indicates a pending review state; review timeliness cannot be determined because no authoritative QA review deadline or SLA is configured.")
                .doesNotContain("not overdue")
                .doesNotContain("overdue approval")
                .doesNotContain("within SLA")
                .doesNotContain("QA review is on time");
    }

    @Test
    @DisplayName("Phase 4F.4.5: PENDING_QA preserves authoritative status and lifecycle-aware review wording across lifecycle stages")
    void pendingQa_lifecycleAwareReviewWording() {
        // 1. PLANNED batch: does not claim production has completed or QA review is currently due
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-QA-PLANNED");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);
        plannedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        plannedDossier.setDeviations(List.of());

        String plannedEval = service.generateDeterministicMissingRecordsEvaluation(plannedDossier);
        assertThat(plannedEval)
                .contains("PENDING_QA")
                .contains("Manufacturing execution status is PLANNED (manufacturing has not started)")
                .contains("Available lifecycle data does not establish whether formal QA review is currently due")
                .doesNotContain("Awaiting formal QA review following production completion")
                .doesNotContain("not overdue")
                .doesNotContain("within SLA");

        String plannedQaStatement = service.generateDeterministicQaDispositionStatement(plannedDossier);
        assertThat(plannedQaStatement)
                .contains("Authoritative QA Disposition: PENDING_QA (Not Released)")
                .contains("Batch BAT-QA-PLANNED is in PLANNED status; manufacturing has not started")
                .contains("Available lifecycle data does not establish whether formal QA review is currently due")
                .contains("formal batch release has NOT been granted")
                .doesNotContain("production has completed")
                .doesNotContain("within SLA");

        // 2. IN_PROGRESS batch: review occurs upon completion
        BatchTraceabilityDossierDto inProgressDossier = new BatchTraceabilityDossierDto();
        inProgressDossier.setBatchNumber("BAT-QA-IN_PROGRESS");
        inProgressDossier.setBatchStatus(BatchStatus.IN_PROGRESS);
        inProgressDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        inProgressDossier.setDeviations(List.of());

        String inProgressEval = service.generateDeterministicMissingRecordsEvaluation(inProgressDossier);
        assertThat(inProgressEval)
                .contains("PENDING_QA")
                .contains("Manufacturing is currently in progress; formal QA review occurs upon manufacturing completion")
                .doesNotContain("Awaiting formal QA review following production completion");

        String inProgressQaStatement = service.generateDeterministicQaDispositionStatement(inProgressDossier);
        assertThat(inProgressQaStatement)
                .contains("Authoritative QA Disposition: PENDING_QA (Not Released)")
                .contains("manufacturing operations are active. Formal QA review occurs upon manufacturing completion");

        // 3. COMPLETED batch: awaiting formal review following production completion
        BatchTraceabilityDossierDto completedDossier = new BatchTraceabilityDossierDto();
        completedDossier.setBatchNumber("BAT-QA-COMPLETED");
        completedDossier.setBatchStatus(BatchStatus.COMPLETED);
        completedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        completedDossier.setDeviations(List.of());

        String completedEval = service.generateDeterministicMissingRecordsEvaluation(completedDossier);
        assertThat(completedEval)
                .contains("PENDING_QA")
                .contains("Awaiting formal QA review following production completion")
                .contains("timeliness cannot be determined")
                .doesNotContain("not overdue")
                .doesNotContain("within SLA");

        String completedQaStatement = service.generateDeterministicQaDispositionStatement(completedDossier);
        assertThat(completedQaStatement)
                .contains("Authoritative QA Disposition: PENDING_QA (Not Released)")
                .contains("awaiting formal Quality Assurance review and disposition following production completion");
    }

    @Test
    @DisplayName("Phase 4F.2.5.5: No unsupported overdue or SLA assertion appears in FULL report instructions or report")
    void noUnsupportedOverdueOrSlaAssertionInFullReports() {
        String fullInstructions = BatchAnalysisReportService.REPORT_SYSTEM_INSTRUCTIONS;
        assertThat(fullInstructions)
                .contains("QA status PENDING_QA indicates an awaiting review state and is NOT a missing record or documentation gap")
                .contains("do NOT state or imply that QA review is on time, overdue, late, or within SLA")
                .doesNotContain("normal review queue progression and is NEVER a missing approval");

        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-FULL-01");
        dossier.setBatchStatus(BatchStatus.COMPLETED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);

        String qaDisposition = service.generateDeterministicQaDispositionStatement(dossier);
        assertThat(qaDisposition)
                .contains("Authoritative QA Disposition: PENDING_QA (Not Released)")
                .contains("awaiting formal Quality Assurance review and disposition")
                .doesNotContain("not overdue")
                .doesNotContain("within SLA");
    }

    @Test
    @DisplayName("Phase 4F.2.5.5: Same overdue/SLA restriction applies to FOCUSED reports and instructions")
    void sameRestrictionAppliesToFocusedReports() {
        String focusedInstructions = service.constructSystemInstructions(
                BatchAnalysisMode.FOCUSED,
                List.of(BatchAnalysisFocusArea.QA_DISPOSITION, BatchAnalysisFocusArea.MISSING_RECORDS)
        );
        assertThat(focusedInstructions)
                .contains("Do NOT state or imply that QA review is overdue, late, on time, or within SLA, as no authoritative QA review deadline or SLA is configured.")
                .doesNotContain("review queue progression, NOT a missing or overdue approval");

        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-FOCUSED-01");
        dossier.setBatchStatus(BatchStatus.COMPLETED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);

        String qaDisposition = service.generateDeterministicQaDispositionStatement(dossier);
        assertThat(qaDisposition)
                .contains("Authoritative QA Disposition: PENDING_QA (Not Released)")
                .doesNotContain("not overdue")
                .doesNotContain("overdue approval")
                .doesNotContain("within SLA");

        String section10 = service.generateDeterministicMissingRecordsEvaluation(dossier);
        assertThat(section10)
                .contains("Awaiting formal QA review following production completion")
                .contains("timeliness cannot be determined")
                .doesNotContain("not overdue")
                .doesNotContain("overdue approval")
                .doesNotContain("within SLA");
    }

    @Test
    @DisplayName("Phase 4F.2.5.5: Existing QA disposition classifications remain unchanged in BatchLifecycleRecordClassifier")
    void existingQaDispositionClassificationsRemainUnchanged() {
        BatchLifecycleRecordClassifier classifier = new BatchLifecycleRecordClassifier();

        // 1. CANCELLED batch with PENDING_QA -> NOT_APPLICABLE
        BatchTraceabilityDossierDto cancelledDossier = new BatchTraceabilityDossierDto();
        cancelledDossier.setBatchNumber("BAT-CANC-01");
        cancelledDossier.setBatchStatus(BatchStatus.CANCELLED);
        cancelledDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        List<RecordAssessmentDto> cancelledAssessments = classifier.classifyRecords(cancelledDossier);
        RecordAssessmentDto cancelledQa = cancelledAssessments.stream()
                .filter(a -> "QA Disposition Review".equals(a.getRecordType()))
                .findFirst().orElseThrow();
        assertThat(cancelledQa.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);
        assertThat(cancelledQa.getExplanation()).contains("Batch was cancelled prior to QA disposition");
        assertThat(cancelledQa.getExplanation()).doesNotContain("not overdue");

        // 2. COMPLETED batch with PENDING_QA -> NOT_YET_EXPECTED
        BatchTraceabilityDossierDto completedDossier = new BatchTraceabilityDossierDto();
        completedDossier.setBatchNumber("BAT-COMP-01");
        completedDossier.setBatchStatus(BatchStatus.COMPLETED);
        completedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        List<RecordAssessmentDto> completedAssessments = classifier.classifyRecords(completedDossier);
        RecordAssessmentDto completedQa = completedAssessments.stream()
                .filter(a -> "QA Disposition Review".equals(a.getRecordType()))
                .findFirst().orElseThrow();
        assertThat(completedQa.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_YET_EXPECTED);
        assertThat(completedQa.getExplanation())
                .contains("Authoritative QA status is PENDING_QA. Awaiting formal QA review; timeliness cannot be determined because no authoritative QA review deadline or SLA is configured.")
                .doesNotContain("not overdue")
                .doesNotContain("not a missing approval")
                .doesNotContain("within SLA");

        // 3. RELEASED batch -> PRESENT
        BatchTraceabilityDossierDto releasedDossier = new BatchTraceabilityDossierDto();
        releasedDossier.setBatchNumber("BAT-REL-01");
        releasedDossier.setBatchStatus(BatchStatus.COMPLETED);
        releasedDossier.setQaStatus(BatchQualityStatus.RELEASED);
        List<RecordAssessmentDto> releasedAssessments = classifier.classifyRecords(releasedDossier);
        RecordAssessmentDto releasedQa = releasedAssessments.stream()
                .filter(a -> "QA Disposition Review".equals(a.getRecordType()))
                .findFirst().orElseThrow();
        assertThat(releasedQa.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(releasedQa.getExplanation()).isEqualTo("Formal QA disposition concluded: RELEASED.");

        // 4. REJECTED batch -> PRESENT
        BatchTraceabilityDossierDto rejectedDossier = new BatchTraceabilityDossierDto();
        rejectedDossier.setBatchNumber("BAT-REJ-01");
        rejectedDossier.setBatchStatus(BatchStatus.COMPLETED);
        rejectedDossier.setQaStatus(BatchQualityStatus.REJECTED);
        List<RecordAssessmentDto> rejectedAssessments = classifier.classifyRecords(rejectedDossier);
        RecordAssessmentDto rejectedQa = rejectedAssessments.stream()
                .filter(a -> "QA Disposition Review".equals(a.getRecordType()))
                .findFirst().orElseThrow();
        assertThat(rejectedQa.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(rejectedQa.getExplanation()).isEqualTo("Formal QA disposition concluded: REJECTED.");

        // 5. ON_HOLD batch -> PRESENT
        BatchTraceabilityDossierDto holdDossier = new BatchTraceabilityDossierDto();
        holdDossier.setBatchNumber("BAT-HOLD-01");
        holdDossier.setBatchStatus(BatchStatus.COMPLETED);
        holdDossier.setQaStatus(BatchQualityStatus.ON_HOLD);
        List<RecordAssessmentDto> holdAssessments = classifier.classifyRecords(holdDossier);
        RecordAssessmentDto holdQa = holdAssessments.stream()
                .filter(a -> "QA Disposition Review".equals(a.getRecordType()))
                .findFirst().orElseThrow();
        assertThat(holdQa.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(holdQa.getExplanation()).isEqualTo("Formal QA disposition concluded: ON_HOLD.");
    }

    @Test
    @DisplayName("Phase 4F.2.5.6: Test 1 - Audit events are described as recorded events, not exhaustive history")
    void auditEventsDescribedAsRecordedEventsNotExhaustiveHistory() {
        BatchContextBuilder contextBuilder = new BatchContextBuilder();
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(101L);
        dossier.setBatchNumber("BAT-AUD-01");
        dossier.setBatchStatus(BatchStatus.IN_PROGRESS);

        AuditLogResponseDto auditLog = new AuditLogResponseDto();
        auditLog.setOccurredAt(LocalDateTime.of(2026, 3, 1, 9, 0));
        auditLog.setAction("BATCH_STARTED");
        auditLog.setEntityType("BATCH");
        auditLog.setActorName("sys_operator");
        dossier.setAuditHistory(List.of(auditLog));

        // 1. Verify BatchContextBuilder summary in CHRONOLOGICAL_AUDIT focused mode
        BatchContext context = contextBuilder.buildBatchContext(
                dossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.CHRONOLOGICAL_AUDIT)
        );
        assertThat(context.formattedFacts())
                .contains("Audit Trail Log Entries: 1 entry/entries recorded (reflects available discrete audit events; not an exhaustive event-sourcing ledger).")
                .contains("BATCH_STARTED");

        // 2. Verify deterministic finding for CHRONOLOGICAL_AUDIT
        List<BatchAnalysisAreaFindingDto> findings = service.buildStructuredFindings(
                dossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.CHRONOLOGICAL_AUDIT), List.of()
        );
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getFocusArea()).isEqualTo(BatchAnalysisFocusArea.CHRONOLOGICAL_AUDIT);
        assertThat(findings.get(0).getTitle()).isEqualTo("Audit Trail Verification");
        assertThat(findings.get(0).getStatus()).isEqualTo("AUDITED");
        assertThat(findings.get(0).getExplanation())
                .isEqualTo("1 discrete recorded audit event(s) evaluated from available audit_logs");
    }

    @Test
    @DisplayName("Phase 4F.2.5.6: Test 2 - An eBR step completion absent from audit_logs is not reported as a missing execution event")
    void ebrStepCompletionAbsentFromAuditLogs_notReportedAsMissingExecutionEvent() {
        BatchContextBuilder contextBuilder = new BatchContextBuilder();
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(102L);
        dossier.setBatchNumber("BAT-EBR-01");
        dossier.setBatchStatus(BatchStatus.COMPLETED);

        // eBR step is COMPLETED with sign-off and timestamp in batch_execution_steps
        BatchExecutionStepResponseDto step = new BatchExecutionStepResponseDto();
        step.setStepNumber(1);
        step.setOperationName("Dispensing");
        step.setStatus(ExecutionStepStatus.COMPLETED);
        step.setExecutedBy("Operator Smith");
        step.setStartedAt(LocalDateTime.of(2026, 3, 1, 8, 0));
        step.setCompletedAt(LocalDateTime.of(2026, 3, 1, 9, 30));
        dossier.setExecutionSteps(List.of(step));

        // audit_logs contains ZERO eBR step events (only batch creation/start)
        AuditLogResponseDto auditLog = new AuditLogResponseDto();
        auditLog.setAction("BATCH_STARTED");
        auditLog.setEntityType("BATCH");
        auditLog.setOccurredAt(LocalDateTime.of(2026, 3, 1, 8, 0));
        dossier.setAuditHistory(List.of(auditLog));

        // Evaluate deterministic missing records
        String missingRecords = service.generateDeterministicMissingRecordsEvaluation(dossier);
        assertThat(missingRecords)
                .contains("- **eBR Execution Steps:** All 1 completed step(s) have verified operator sign-offs and timestamps.")
                .doesNotContain("missing execution event")
                .doesNotContain("Step 1 missing");

        // Verify context reflects step source and clarifies normal absence from audit_logs in chronological audit
        BatchContext chronoContext = contextBuilder.buildBatchContext(
                dossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.CHRONOLOGICAL_AUDIT)
        );
        assertThat(chronoContext.formattedFacts())
                .contains("Execution Step Sequence: 1 step(s) recorded (sourced from batch_execution_steps entity records; absence of step events in audit_logs is normal and does NOT indicate the step did not occur).");

        // Verify FULL mode context reflects persisted entity state boundary
        BatchContext fullContext = contextBuilder.buildBatchContext(dossier);
        assertThat(fullContext.formattedFacts())
                .contains("NOTE: Execution steps reflect current persisted step state and single recorded timestamps from batch_execution_steps; they do not represent an exhaustive transaction history or append-only state-transition sequence.");
    }

    @Test
    @DisplayName("Phase 4F.2.5.6: Test 3 - Current eBR step state is not described as a complete historical transition sequence")
    void currentEbrStepState_notDescribedAsCompleteHistoricalTransitionSequence() {
        BatchContextBuilder contextBuilder = new BatchContextBuilder();
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(103L);
        dossier.setBatchNumber("BAT-EBR-02");
        dossier.setBatchStatus(BatchStatus.IN_PROGRESS);

        BatchExecutionStepResponseDto step = new BatchExecutionStepResponseDto();
        step.setStepNumber(1);
        step.setOperationName("Granulation");
        step.setStatus(ExecutionStepStatus.IN_PROGRESS);
        step.setExecutedBy("Operator Doe");
        step.setStartedAt(LocalDateTime.of(2026, 3, 1, 10, 0));
        dossier.setExecutionSteps(List.of(step));

        BatchContext context = contextBuilder.buildBatchContext(dossier);
        assertThat(context.formattedFacts())
                .contains("NOTE: Execution steps reflect current persisted step state and single recorded timestamps from batch_execution_steps; they do not represent an exhaustive transaction history or append-only state-transition sequence.");
    }

    @Test
    @DisplayName("Phase 4F.2.5.6: Test 4 - Batch-attributed and order-level/unlinked inventory movements remain distinct")
    void batchAttributedAndOrderLevelInventoryMovementsRemainDistinct() {
        BatchContextBuilder contextBuilder = new BatchContextBuilder();
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(104L);
        dossier.setBatchNumber("BAT-MAT-01");
        dossier.setOrderNumber("MO-2026-0099");
        dossier.setBatchStatus(BatchStatus.IN_PROGRESS);

        // 1. Batch-attributed movement
        StockMovementResponseDto batchMove = new StockMovementResponseDto();
        batchMove.setId(501L);
        batchMove.setBatchId(104L);
        batchMove.setBatchNumber("BAT-MAT-01");
        batchMove.setMaterialCode("RM-ACT-01");
        batchMove.setMaterialName("Active API");
        batchMove.setLotNumber("LOT-API-01");
        batchMove.setQuantityDelta(new BigDecimal("-50.0000"));
        batchMove.setUnit("kg");

        // 2. Order-level movement (batchId = null)
        StockMovementResponseDto orderMove = new StockMovementResponseDto();
        orderMove.setId(502L);
        orderMove.setBatchId(null);
        orderMove.setBatchNumber(null);
        orderMove.setMaterialCode("RM-EXC-01");
        orderMove.setMaterialName("Excipient Starch");
        orderMove.setLotNumber("LOT-STR-01");
        orderMove.setQuantityDelta(new BigDecimal("-100.0000"));
        orderMove.setUnit("kg");

        dossier.setMaterialMovements(List.of(batchMove, orderMove));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRawMaterialCode("RM-ACT-01");
        req.setRecipeMaterialName("Active API");
        req.setQuantityPerBatch(new BigDecimal("50.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        BatchContext context = contextBuilder.buildBatchContext(dossier);
        String facts = context.formattedFacts();

        // Batch movement is listed in batch section
        assertThat(facts)
                .contains("Batch-Specific Material Movements: 1")
                .contains("RM-ACT-01 (Active API)");

        // Order-level movement is segregated
        assertThat(facts)
                .contains("Order-Level Material Issues Note: 1 material movement(s) recorded against parent Manufacturing Order MO-2026-0099 without individual batch attribution. These cannot be attributed to Batch BAT-MAT-01.");

        // Reconciliation clarifies boundaries
        assertThat(facts)
                .contains("Material Reconciliation: Batch-specific issues recorded above; verify against planned per-batch quantities. NOTE: Only batch-attributed movements are included; unlinked or order-level inventory movements are not attributed without explicit linkage. Absence of movement records does not prove materials were not consumed outside tracked workflows.");

        // Deterministic Section 10 verifies segregation
        String section10 = service.generateDeterministicMissingRecordsEvaluation(dossier);
        assertThat(section10)
                .contains("with 1 batch-attributed material movement(s) recorded (plus 1 order-level movement(s) without batch attribution).");
    }

    @Test
    @DisplayName("Phase 4F.2.5.6: Test 5 - FULL report instructions preserve event and evidence boundaries")
    void fullReportInstructions_preserveEventAndEvidenceBoundaries() {
        String instructions = BatchAnalysisReportService.REPORT_SYSTEM_INSTRUCTIONS;

        assertThat(instructions)
                .contains("Do NOT treat audit_logs as an exhaustive event-sourcing ledger.")
                .contains("eBR execution facts are sourced from batch_execution_steps; do NOT report eBR step execution as missing merely because it is absent from audit_logs.")
                .contains("Sourced directly from persisted batch_execution_steps records, which reflect current step state, NOT an exhaustive historical state-transition sequence.")
                .contains("Distinguish batch-attributed inventory movements from unlinked or order-level movements, and do not assert material was not consumed outside tracked workflows based solely on absence of movement records.")
                .contains("audit_logs provides available recorded discrete audit events, not an exhaustive event-sourcing ledger. Distinguish recorded timestamps from inferred sequence ordering. Never invent missing audit events or assert confirmed missing actions based solely on their absence from audit_logs.");
    }

    @Test
    @DisplayName("Phase 4F.2.5.6: Test 6 - FOCUSED CHRONOLOGICAL_AUDIT and EBR_EXECUTION instructions preserve boundaries")
    void focusedChronologicalAuditAndEbrExecutionInstructions_preserveBoundaries() {
        String focusedInstructions = service.constructSystemInstructions(BatchAnalysisMode.FOCUSED, List.of(
                BatchAnalysisFocusArea.CHRONOLOGICAL_AUDIT,
                BatchAnalysisFocusArea.EBR_EXECUTION,
                BatchAnalysisFocusArea.MATERIAL_TRACEABILITY
        ));

        // Rule 12 presence
        assertThat(focusedInstructions)
                .contains("12. Do NOT treat audit_logs as an exhaustive event-sourcing ledger. Sourced eBR execution state and timestamps are from batch_execution_steps; absence of step events in audit_logs is expected and must NOT be reported as missing execution. Current eBR step state does not represent an exhaustive historical state-transition sequence.")
                .contains("Distinguish recorded timestamps from inferred sequence ordering, and do NOT attribute unlinked or order-level inventory movements to a batch or assert materials were not consumed outside tracked workflows based solely on absent movement records.");

        // CHRONOLOGICAL_AUDIT focus area description
        assertThat(focusedInstructions)
                .contains("audit_logs provides available recorded discrete audit events, not an exhaustive event-sourcing ledger; distinguish recorded timestamps from inferred sequence ordering and do not invent missing audit events.");

        // EBR_EXECUTION focus area description
        assertThat(focusedInstructions)
                .contains("Sourced directly from persisted batch_execution_steps entity records (which reflect current step state, not an exhaustive state-transition history);")
                .contains("absence of step events in audit_logs is normal and must NOT be treated as missing execution.");

        // MATERIAL_TRACEABILITY focus area description
        assertThat(focusedInstructions)
                .contains("Distinguish batch-attributed movements from unlinked or order-level movements; absence of movement records does not prove material was not consumed outside tracked workflows.");

        // Other focus area (e.g. MANUFACTURING_YIELD) does not inherit misleading audit assumptions
        String yieldInstructions = service.constructSystemInstructions(BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD));
        assertThat(yieldInstructions)
                .contains("Focused evaluation of Manufacturing Yield based strictly on verified records.")
                .doesNotContain("audit_logs provides available recorded discrete audit events");
    }

    @Test
    @DisplayName("Phase 4F.2.5.6: Test 7 - Existing chronological ordering and timezone behaviour remain unchanged")
    void existingChronologicalOrderingAndTimezoneBehaviourRemainUnchanged() {
        BatchContextBuilder contextBuilder = new BatchContextBuilder();
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(105L);
        dossier.setBatchNumber("BAT-CHRONO-01");
        dossier.setBatchStatus(BatchStatus.IN_PROGRESS);

        // Step 1: 08:00 to 09:00
        BatchExecutionStepResponseDto step1 = new BatchExecutionStepResponseDto();
        step1.setStepNumber(1);
        step1.setOperationName("Mixing");
        step1.setStatus(ExecutionStepStatus.COMPLETED);
        step1.setStartedAt(LocalDateTime.of(2026, 3, 1, 8, 0));
        step1.setCompletedAt(LocalDateTime.of(2026, 3, 1, 9, 0));
        step1.setExecutedBy("Operator 1");

        // Step 2: 08:45 to 10:00 (overlapping with Step 1 -> chronological anomaly)
        BatchExecutionStepResponseDto step2 = new BatchExecutionStepResponseDto();
        step2.setStepNumber(2);
        step2.setOperationName("Granulation");
        step2.setStatus(ExecutionStepStatus.COMPLETED);
        step2.setStartedAt(LocalDateTime.of(2026, 3, 1, 8, 45));
        step2.setCompletedAt(LocalDateTime.of(2026, 3, 1, 10, 0));
        step2.setExecutedBy("Operator 2");

        dossier.setExecutionSteps(List.of(step1, step2));

        BatchContext context = contextBuilder.buildBatchContext(dossier);
        String facts = context.formattedFacts();

        // Chronological anomaly correctly detected and formatted
        assertThat(facts)
                .contains("Transition between Step 1 and Step 2: Chronology anomaly — overlapping timestamps observed (Step 2 started 15 minutes (15 minutes) before Step 1 completed) (recorded chronology anomaly without inferring equipment failure or process hold)");

        // System instructions maintain plant timestamps requirement in Asia/Kolkata IST
        assertThat(BatchAnalysisReportService.REPORT_SYSTEM_INSTRUCTIONS)
                .contains("plant timestamps (Asia/Kolkata IST)");
    }

    // =========================================================================
    // Phase 4F.2.5.7: CANCELLED Batch eBR Evidence Preservation Regression Tests
    // =========================================================================

    @Test
    @DisplayName("Phase 4F.2.5.7: Section 10 eBR evaluation preserves completed and pending steps on CANCELLED batch without calling pending missing")
    void section10_cancelledBatch_completedAndPendingSteps_preservesEvidenceWithoutPendingGap() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-CAN-SEC10-EBR");
        dossier.setBatchStatus(BatchStatus.CANCELLED);
        dossier.setStartTime(LocalDateTime.now().minusHours(4));

        BatchExecutionStepResponseDto s1 = new BatchExecutionStepResponseDto();
        s1.setId(1L);
        s1.setStepNumber(1);
        s1.setOperationName("Dispensing");
        s1.setStatus(ExecutionStepStatus.COMPLETED);
        s1.setActualValue("100.0 kg");
        s1.setExecutedBy("operator_alice");
        s1.setStartedAt(LocalDateTime.now().minusHours(4));
        s1.setCompletedAt(LocalDateTime.now().minusHours(3));

        BatchExecutionStepResponseDto s2 = new BatchExecutionStepResponseDto();
        s2.setId(2L);
        s2.setStepNumber(2);
        s2.setOperationName("Blending");
        s2.setStatus(ExecutionStepStatus.PENDING);

        dossier.setExecutionSteps(List.of(s1, s2));

        String section10 = service.generateDeterministicMissingRecordsEvaluation(dossier);

        assertThat(section10)
                .contains("- **eBR Execution Steps:** 1 of 2 step(s) completed prior to cancellation (0 in-progress, 1 pending)")
                .contains("Historical execution evidence is preserved; remaining steps were not executed due to batch cancellation")
                .doesNotContain("Step 2 remains in PENDING status on a COMPLETED batch")
                .doesNotContain("No eBR execution steps recorded");
    }

    @Test
    @DisplayName("Phase 4F.2.5.7: Section 10 eBR evaluation preserves IN_PROGRESS step start timestamp on CANCELLED batch without calling it completed")
    void section10_cancelledBatch_inProgressStep_preservesStartTimestampWithoutCallingCompleted() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-CAN-SEC10-INPROG");
        dossier.setBatchStatus(BatchStatus.CANCELLED);
        dossier.setStartTime(LocalDateTime.now().minusHours(3));

        BatchExecutionStepResponseDto s1 = new BatchExecutionStepResponseDto();
        s1.setId(1L);
        s1.setStepNumber(1);
        s1.setOperationName("Dispensing");
        s1.setStatus(ExecutionStepStatus.COMPLETED);
        s1.setActualValue("50.0 kg");
        s1.setExecutedBy("operator_alice");
        s1.setStartedAt(LocalDateTime.now().minusHours(3));
        s1.setCompletedAt(LocalDateTime.now().minusHours(2));

        BatchExecutionStepResponseDto s2 = new BatchExecutionStepResponseDto();
        s2.setId(2L);
        s2.setStepNumber(2);
        s2.setOperationName("Granulation");
        s2.setStatus(ExecutionStepStatus.IN_PROGRESS);
        s2.setStartedAt(LocalDateTime.now().minusHours(2));

        dossier.setExecutionSteps(List.of(s1, s2));

        String section10 = service.generateDeterministicMissingRecordsEvaluation(dossier);

        assertThat(section10)
                .contains("- **eBR Execution Steps:** 1 of 2 step(s) completed prior to cancellation (1 in-progress, 0 pending)")
                .contains("Historical execution evidence is preserved")
                .doesNotContain("All 2 completed step(s)");
    }

    @Test
    @DisplayName("Phase 4F.2.5.7: Section 10 eBR evaluation identifies documentation gap on completed step of CANCELLED batch")
    void section10_cancelledBatch_completedStepMissingSignOff_flagsDocumentationGap() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-CAN-SEC10-GAP");
        dossier.setBatchStatus(BatchStatus.CANCELLED);
        dossier.setStartTime(LocalDateTime.now().minusHours(3));

        BatchExecutionStepResponseDto s1 = new BatchExecutionStepResponseDto();
        s1.setId(1L);
        s1.setStepNumber(1);
        s1.setOperationName("Dispensing");
        s1.setStatus(ExecutionStepStatus.COMPLETED);
        s1.setActualValue("50.0 kg");
        s1.setExecutedBy(null); // Missing required sign-off
        s1.setCompletedAt(LocalDateTime.now().minusHours(2));

        dossier.setExecutionSteps(List.of(s1));

        String section10 = service.generateDeterministicMissingRecordsEvaluation(dossier);

        assertThat(section10)
                .contains("1 documentation gap(s) identified in historical records")
                .contains("Step 1 (Dispensing) completed without recorded operator sign-off");
    }

    @Test
    @DisplayName("Phase 4F.2.5.7: Section 10 eBR evaluation distinguishes zero steps with null vs non-null start time on CANCELLED batch")
    void section10_cancelledBatch_zeroSteps_distinguishesNullVsNonNullStartTime() {
        // Case 1: Cancelled prior to production initiation (startTime == null)
        BatchTraceabilityDossierDto cancelledPlanned = new BatchTraceabilityDossierDto();
        cancelledPlanned.setBatchNumber("BAT-CAN-SEC10-NO-START");
        cancelledPlanned.setBatchStatus(BatchStatus.CANCELLED);
        cancelledPlanned.setStartTime(null);
        cancelledPlanned.setExecutionSteps(List.of());

        String section10NullStart = service.generateDeterministicMissingRecordsEvaluation(cancelledPlanned);
        assertThat(section10NullStart)
                .contains("- **eBR Execution Steps:** 0 steps recorded. Batch was cancelled prior to production initiation; execution steps were not initialized.");

        // Case 2: Started prior to cancellation (startTime != null), but steps missing
        BatchTraceabilityDossierDto cancelledStarted = new BatchTraceabilityDossierDto();
        cancelledStarted.setBatchNumber("BAT-CAN-SEC10-STARTED");
        cancelledStarted.setBatchStatus(BatchStatus.CANCELLED);
        cancelledStarted.setStartTime(LocalDateTime.of(2026, 3, 1, 9, 0));
        cancelledStarted.setExecutionSteps(List.of());

        String section10NonNullStart = service.generateDeterministicMissingRecordsEvaluation(cancelledStarted);
        assertThat(section10NonNullStart)
                .contains("- **eBR Execution Steps:** 0 steps recorded. Batch was started prior to cancellation (start timestamp: 2026-03-01T09:00), but zero execution steps are recorded in the electronic dossier; execution step history cannot be established.");
    }

    @Test
    @DisplayName("Phase 4F.2.5.7: FULL and FOCUSED instructions and structured findings preserve eBR cancellation boundaries")
    void reportInstructionsAndStructuredFindings_preserveEbrCancellationBoundaries() {
        // 1. FULL report system instructions
        assertThat(BatchAnalysisReportService.REPORT_SYSTEM_INSTRUCTIONS)
                .contains("For CANCELLED batches, preserve completed steps, in-progress steps, actual values, and timestamps as recorded evidence; cancellation does NOT erase earlier execution evidence, but do NOT infer full recipe completion from partial execution or describe unexecuted pending steps as missing records.");

        // 2. FOCUSED EBR_EXECUTION system instructions
        String focusedInstructions = service.constructSystemInstructions(
                BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.EBR_EXECUTION)
        );
        assertThat(focusedInstructions)
                .contains("for CANCELLED batches, preserve historical execution records (completed, in-progress, actual values, sign-offs) without inferring complete execution or treating unexecuted steps as missing");

        // 3. Structured findings for CANCELLED batch with execution steps
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-CAN-FINDING-01");
        dossier.setBatchStatus(BatchStatus.CANCELLED);
        BatchExecutionStepResponseDto s1 = new BatchExecutionStepResponseDto();
        s1.setStepNumber(1);
        s1.setStatus(ExecutionStepStatus.COMPLETED);
        dossier.setExecutionSteps(List.of(s1));

        List<BatchAnalysisAreaFindingDto> findings = service.buildStructuredFindings(
                dossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.EBR_EXECUTION), List.of()
        );
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getFocusArea()).isEqualTo(BatchAnalysisFocusArea.EBR_EXECUTION);
        assertThat(findings.get(0).getStatus()).isEqualTo("CANCELLED_EVIDENCE_PRESERVED");
        assertThat(findings.get(0).getExplanation())
                .contains("1 execution step(s) recorded prior to cancellation (1 completed, 0 in-progress, 0 pending); historical evidence preserved");
    }

    // ======================================================================================
    // Phase 4F.3.2: Strict FOCUSED Report Structured-Data Isolation Tests (Scenarios 1-10)
    // ======================================================================================

    @Test
    @DisplayName("Phase 4F.3.2 (1): FOCUSED EBR_EXECUTION response containing unexpected QC section is nullified")
    void test1_focusedEbrExecution_unexpectedQcSection_isNullified() {
        String json = """
                {
                  "executiveSummary": "Focused evaluation on eBR execution.",
                  "ebrStepEvaluation": "All execution steps completed with valid operator sign-offs.",
                  "qcInspectionAnalysis": "In-process quality testing passed all acceptance criteria.",
                  "followUpRecommendations": "1. Verify step completion records."
                }
                """;

        BatchAnalysisSectionsDto sections = service.parseAndValidateSections(
                json, testDossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.EBR_EXECUTION)
        );

        assertThat(sections).isNotNull();
        assertThat(sections.getEbrStepEvaluation()).isEqualTo("All execution steps completed with valid operator sign-offs.");
        assertThat(sections.getQcInspectionAnalysis()).isNull();
    }

    @Test
    @DisplayName("Phase 4F.3.2 (2): FOCUSED EBR_EXECUTION response containing multiple unexpected analytical sections is nullified")
    void test2_focusedEbrExecution_multipleUnexpectedSections_areNullified() {
        String json = """
                {
                  "executiveSummary": "Focused evaluation on eBR execution.",
                  "batchStatusAndProgress": "Manufacturing status is COMPLETED.",
                  "plannedVsActualMetrics": "Planned size was 100 kg, actual yield 98.5 kg.",
                  "materialTraceability": "All active lots consumed in compliance with recipe.",
                  "ebrStepEvaluation": "Step 1 executed successfully.",
                  "qcInspectionAnalysis": "QC testing passed.",
                  "deviationsAndCapa": "Zero deviations logged.",
                  "chronologicalAuditAnalysis": "Sequential progression verified.",
                  "missingRecords": "None. All mandatory fields recorded.",
                  "riskObservations": "Low operational risk.",
                  "followUpRecommendations": "1. Review execution logs."
                }
                """;

        BatchAnalysisSectionsDto sections = service.parseAndValidateSections(
                json, testDossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.EBR_EXECUTION)
        );

        assertThat(sections).isNotNull();
        assertThat(sections.getEbrStepEvaluation()).isEqualTo("Step 1 executed successfully.");
        assertThat(sections.getBatchStatusAndProgress()).isNull();
        assertThat(sections.getPlannedVsActualMetrics()).isNull();
        assertThat(sections.getMaterialTraceability()).isNull();
        assertThat(sections.getQcInspectionAnalysis()).isNull();
        assertThat(sections.getDeviationsAndCapa()).isNull();
        assertThat(sections.getChronologicalAuditAnalysis()).isNull();
        assertThat(sections.getMissingRecords()).isNull();
        assertThat(sections.getRiskObservations()).isNull();
        assertThat(sections.getQaDispositionStatus()).isNull();
    }

    @Test
    @DisplayName("Phase 4F.3.2 (3): FOCUSED QC_INSPECTION response containing unexpected eBR content is nullified")
    void test3_focusedQcInspection_unexpectedEbrContent_isNullified() {
        String json = """
                {
                  "executiveSummary": "Focused evaluation on QC testing.",
                  "qcInspectionAnalysis": "Assay testing within 98-102% limits.",
                  "ebrStepEvaluation": "Dispensing step completed on 2026-11-14.",
                  "followUpRecommendations": "1. Verify analytical calibration."
                }
                """;

        BatchAnalysisSectionsDto sections = service.parseAndValidateSections(
                json, testDossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.QC_INSPECTION)
        );

        assertThat(sections).isNotNull();
        assertThat(sections.getQcInspectionAnalysis()).isEqualTo("Assay testing within 98-102% limits.");
        assertThat(sections.getEbrStepEvaluation()).isNull();
    }

    @Test
    @DisplayName("Phase 4F.3.2 (4): Multi-area FOCUSED request preserves every selected section")
    void test4_multiAreaFocused_preservesEverySelectedSection() {
        List<BatchAnalysisFocusArea> areas = List.of(
                BatchAnalysisFocusArea.MANUFACTURING_YIELD,
                BatchAnalysisFocusArea.QC_INSPECTION,
                BatchAnalysisFocusArea.DEVIATIONS_CAPA
        );

        String json = """
                {
                  "executiveSummary": "Multi-area focused evaluation.",
                  "plannedVsActualMetrics": "Yield is 98.55% against target 1000 kg.",
                  "qcInspectionAnalysis": "In-process quality testing passed all acceptance criteria.",
                  "deviationsAndCapa": "One linked minor deviation DEV-2026-001 investigated.",
                  "followUpRecommendations": "1. Complete CAPA effectiveness check."
                }
                """;

        BatchAnalysisSectionsDto sections = service.parseAndValidateSections(
                json, testDossier, BatchAnalysisMode.FOCUSED, areas
        );

        assertThat(sections).isNotNull();
        assertThat(sections.getPlannedVsActualMetrics()).contains("98.55%");
        assertThat(sections.getQcInspectionAnalysis()).contains("In-process quality testing");
        assertThat(sections.getDeviationsAndCapa()).contains("DEV-2026-001");
    }

    @Test
    @DisplayName("Phase 4F.3.2 (5): Multi-area FOCUSED request excludes every unselected section")
    void test5_multiAreaFocused_excludesEveryUnselectedSection() {
        List<BatchAnalysisFocusArea> areas = List.of(
                BatchAnalysisFocusArea.MANUFACTURING_YIELD,
                BatchAnalysisFocusArea.QC_INSPECTION,
                BatchAnalysisFocusArea.DEVIATIONS_CAPA
        );

        // JSON contains selected sections PLUS all 7 unselected sections
        String json = """
                {
                  "executiveSummary": "Multi-area focused evaluation.",
                  "batchStatusAndProgress": "Manufacturing status is COMPLETED.",
                  "plannedVsActualMetrics": "Yield is 98.55% against target 1000 kg.",
                  "materialTraceability": "Material lot LOT-A consumed.",
                  "ebrStepEvaluation": "All execution steps signed off.",
                  "qcInspectionAnalysis": "In-process quality testing passed.",
                  "qaDispositionStatus": "Authoritative QA Disposition: PENDING_QA.",
                  "deviationsAndCapa": "One linked deviation investigated.",
                  "chronologicalAuditAnalysis": "Audit entries logged.",
                  "missingRecords": "No missing records.",
                  "riskObservations": "Operational risk acceptable.",
                  "followUpRecommendations": "1. Action item."
                }
                """;

        BatchAnalysisSectionsDto sections = service.parseAndValidateSections(
                json, testDossier, BatchAnalysisMode.FOCUSED, areas
        );

        assertThat(sections).isNotNull();
        // Selected sections preserved
        assertThat(sections.getPlannedVsActualMetrics()).isNotNull();
        assertThat(sections.getQcInspectionAnalysis()).isNotNull();
        assertThat(sections.getDeviationsAndCapa()).isNotNull();

        // All 7 unselected sections strictly excluded (null)
        assertThat(sections.getBatchStatusAndProgress()).isNull();
        assertThat(sections.getMaterialTraceability()).isNull();
        assertThat(sections.getEbrStepEvaluation()).isNull();
        assertThat(sections.getQaDispositionStatus()).isNull();
        assertThat(sections.getChronologicalAuditAnalysis()).isNull();
        assertThat(sections.getMissingRecords()).isNull();
        assertThat(sections.getRiskObservations()).isNull();
    }

    @Test
    @DisplayName("Phase 4F.3.2 (6): FULL mode still preserves all 12 sections")
    void test6_fullMode_preservesAll12Sections() {
        String json = createValid12SectionJson();

        BatchAnalysisSectionsDto sections = service.parseAndValidateSections(
                json, testDossier, BatchAnalysisMode.FULL, null
        );

        assertThat(sections).isNotNull();
        assertThat(sections.getExecutiveSummary()).isNotNull();
        assertThat(sections.getBatchStatusAndProgress()).isNotNull();
        assertThat(sections.getPlannedVsActualMetrics()).isNotNull();
        assertThat(sections.getMaterialTraceability()).isNotNull();
        assertThat(sections.getEbrStepEvaluation()).isNotNull();
        assertThat(sections.getQcInspectionAnalysis()).isNotNull();
        assertThat(sections.getQaDispositionStatus()).isNotNull();
        assertThat(sections.getDeviationsAndCapa()).isNotNull();
        assertThat(sections.getChronologicalAuditAnalysis()).isNotNull();
        assertThat(sections.getMissingRecords()).isNotNull();
        assertThat(sections.getRiskObservations()).isNotNull();
        assertThat(sections.getFollowUpRecommendations()).isNotNull();
    }

    @Test
    @DisplayName("Phase 4F.3.2 (7): Executive summary and follow-up recommendations remain available in FOCUSED mode")
    void test7_focusedMode_executiveSummaryAndFollowUpRecommendations_remainAvailable() {
        String json = """
                {
                  "executiveSummary": "Focused evaluation on material traceability.",
                  "materialTraceability": "Raw materials RM-001 and RM-002 fully issued and verified.",
                  "followUpRecommendations": "1. Reconcile warehouse staging buffer.\\n2. Verify batch dispensing records."
                }
                """;

        BatchAnalysisSectionsDto sections = service.parseAndValidateSections(
                json, testDossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MATERIAL_TRACEABILITY)
        );

        assertThat(sections).isNotNull();
        assertThat(sections.getExecutiveSummary()).isEqualTo("Focused evaluation on material traceability.");
        assertThat(sections.getMaterialTraceability()).isEqualTo("Raw materials RM-001 and RM-002 fully issued and verified.");
        assertThat(sections.getFollowUpRecommendations())
                .contains("1. Reconcile warehouse staging buffer.")
                .contains("2. Verify batch dispensing records.");
    }

    @Test
    @DisplayName("Phase 4F.3.2 (8): Structured findings and linked recommendations respect selected areas")
    void test8_focusedMode_structuredFindingsAndLinkedRecommendations_respectSelectedAreas() {
        List<BatchAnalysisFocusArea> areas = List.of(BatchAnalysisFocusArea.EBR_EXECUTION);

        // Pure eBR recommendations matching strictly EBR_EXECUTION
        String json = """
                {
                  "executiveSummary": "Focused eBR execution evaluation.",
                  "ebrStepEvaluation": "Step 1 executed successfully.",
                  "followUpRecommendations": "1. Verify eBR operator sign-off for execution step 1.\\n2. Ensure execution step verification logs are recorded in eBR."
                }
                """;

        BatchAnalysisSectionsDto sections = service.parseAndValidateSections(
                json, testDossier, BatchAnalysisMode.FOCUSED, areas
        );

        assertThat(sections).isNotNull();
        // Structured findings strictly restricted to selected area
        assertThat(sections.getStructuredFindings()).hasSize(1);
        assertThat(sections.getStructuredFindings().get(0).getFocusArea()).isEqualTo(BatchAnalysisFocusArea.EBR_EXECUTION);

        // Linked recommendations must only map to the selected focus area
        assertThat(sections.getLinkedRecommendations()).hasSize(2);
        for (var lr : sections.getLinkedRecommendations()) {
            assertThat(lr.getFocusArea()).isEqualTo(BatchAnalysisFocusArea.EBR_EXECUTION);
        }
    }

    @Test
    @DisplayName("Phase 4F.3.2 (9): Saved report retrieval does not reintroduce unselected sections")
    void test9_savedReportRetrieval_doesNotReintroduceUnselectedSections() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "QA_REVIEWER", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);

        // Report persisted with FOCUSED mode on EBR_EXECUTION, but structured JSON had leaked QC analysis
        BatchAnalysisReport report = new BatchAnalysisReport(
                testBatch, supervisor, "REQ-RETRIEVAL-1", BatchAnalysisMode.FOCUSED,
                "[\"EBR_EXECUTION\"]", "eBR Execution"
        );
        report.setStatus(BatchAnalysisReportStatus.COMPLETED);
        report.setStructuredAnalysisJson("""
                {
                  "executiveSummary": "Focused summary.",
                  "ebrStepEvaluation": "Step 1 completed.",
                  "qcInspectionAnalysis": "Leaked QC analysis in database.",
                  "followUpRecommendations": "1. Check step logs."
                }
                """);
        report.setSourcesJson("[]");

        when(reportRepository.findByIdAndBatchId(101L, testDossier.getBatchId())).thenReturn(Optional.of(report));

        BatchAnalysisReportResponseDto response = service.getReportById("BAT-2026-0001-01", 101L);

        assertThat(response).isNotNull();
        assertThat(response.getAnalysisMode()).isEqualTo(BatchAnalysisMode.FOCUSED);
        assertThat(response.getStructuredAnalysis().getEbrStepEvaluation()).isEqualTo("Step 1 completed.");
        // Leaked QC analysis must be strictly null in retrieved DTO
        assertThat(response.getStructuredAnalysis().getQcInspectionAnalysis()).isNull();
    }

    @Test
    @DisplayName("Phase 4F.3.2 (10): Existing historical FULL reports remain unchanged on retrieval")
    void test10_existingHistoricalFullReports_remainUnchanged() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "QA_REVIEWER", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);

        // Historical FULL report with all 12 sections
        BatchAnalysisReport report = new BatchAnalysisReport(
                testBatch, supervisor, "REQ-HIST-1", BatchAnalysisMode.FULL, null, null
        );
        report.setStatus(BatchAnalysisReportStatus.COMPLETED);
        report.setStructuredAnalysisJson(createValid12SectionJson());
        report.setSourcesJson("[]");

        when(reportRepository.findByIdAndBatchId(102L, testDossier.getBatchId())).thenReturn(Optional.of(report));

        BatchAnalysisReportResponseDto response = service.getReportById("BAT-2026-0001-01", 102L);

        assertThat(response).isNotNull();
        assertThat(response.getAnalysisMode()).isEqualTo(BatchAnalysisMode.FULL);
        // All 12 sections remain intact
        assertThat(response.getStructuredAnalysis().getExecutiveSummary()).isNotNull();
        assertThat(response.getStructuredAnalysis().getBatchStatusAndProgress()).isNotNull();
        assertThat(response.getStructuredAnalysis().getPlannedVsActualMetrics()).isNotNull();
        assertThat(response.getStructuredAnalysis().getMaterialTraceability()).isNotNull();
        assertThat(response.getStructuredAnalysis().getEbrStepEvaluation()).isNotNull();
        assertThat(response.getStructuredAnalysis().getQcInspectionAnalysis()).isNotNull();
        assertThat(response.getStructuredAnalysis().getQaDispositionStatus()).isNotNull();
        assertThat(response.getStructuredAnalysis().getDeviationsAndCapa()).isNotNull();
        assertThat(response.getStructuredAnalysis().getChronologicalAuditAnalysis()).isNotNull();
        assertThat(response.getStructuredAnalysis().getMissingRecords()).isNotNull();
        assertThat(response.getStructuredAnalysis().getRiskObservations()).isNotNull();
        assertThat(response.getStructuredAnalysis().getFollowUpRecommendations()).isNotNull();
    }

    // =========================================================================
    // Phase 4F.3.2.2 — Complete FOCUSED Report Auxiliary Data Isolation Tests
    // =========================================================================

    @Test
    @DisplayName("Phase 4F.3.2.2 (A): Null and unselected structured findings are removed in FOCUSED mode")
    void testA_isolateStructuredSections_removesNullAndUnselectedFindings() {
        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto();
        sections.setEbrStepEvaluation("eBR step 1 completed");

        List<BatchAnalysisAreaFindingDto> findings = new ArrayList<>();
        findings.add(new BatchAnalysisAreaFindingDto(null, "Untagged finding", "UNKNOWN", "No area", List.of()));
        findings.add(new BatchAnalysisAreaFindingDto(BatchAnalysisFocusArea.QC_INSPECTION, "QC Out of Spec", "FAIL", "Assay failed", List.of()));
        findings.add(new BatchAnalysisAreaFindingDto(BatchAnalysisFocusArea.EBR_EXECUTION, "Step Logged", "PASS", "Step 1 ok", List.of()));
        findings.add(null);
        sections.setStructuredFindings(findings);

        BatchAnalysisReportService.isolateStructuredSections(sections, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.EBR_EXECUTION));

        assertThat(sections.getStructuredFindings()).hasSize(1);
        assertThat(sections.getStructuredFindings().get(0).getFocusArea()).isEqualTo(BatchAnalysisFocusArea.EBR_EXECUTION);
        assertThat(sections.getStructuredFindings().get(0).getTitle()).isEqualTo("Step Logged");
    }

    @Test
    @DisplayName("Phase 4F.3.2.2 (B): QC recommendations cannot be misattributed to eBR, and vice versa")
    void testB_linkedRecommendations_qcCannotBeMisattributedToEbrAndViceVersa() {
        String qcRecText = "1. Re-test QC assay sample in analytical lab.";
        List<LinkedRecommendationDto> ebrReportRecs = service.buildLinkedRecommendations(
                qcRecText, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.EBR_EXECUTION)
        );
        // Out-of-scope QC recommendation must NOT be forced into EBR_EXECUTION
        assertThat(ebrReportRecs).isEmpty();

        String ebrRecText = "1. Verify eBR step sign-off by line operator.";
        List<LinkedRecommendationDto> qcReportRecs = service.buildLinkedRecommendations(
                ebrRecText, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.QC_INSPECTION)
        );
        // Out-of-scope eBR recommendation must NOT be forced into QC_INSPECTION
        assertThat(qcReportRecs).isEmpty();
    }

    @Test
    @DisplayName("Phase 4F.3.2.3.1 (C): Multi-area recommendations fail closed in FOCUSED mode")
    void testC_linkedRecommendations_multiAreaHandling() {
        // C1: Spans two selected areas -> fail closed (excluded)
        String multiSelectedText = "1. Verify eBR step 3 operator sign-off and review QC analytical assay results.";
        List<LinkedRecommendationDto> twoSelectedRecs = service.buildLinkedRecommendations(
                multiSelectedText, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.EBR_EXECUTION, BatchAnalysisFocusArea.QC_INSPECTION)
        );
        assertThat(twoSelectedRecs).isEmpty();

        // C2: Spans one selected and one unselected area -> fail closed (excluded)
        String oneSelectedText = "1. Verify eBR step 3 operator sign-off and review QC analytical assay results.";
        List<LinkedRecommendationDto> oneSelectedRecs = service.buildLinkedRecommendations(
                oneSelectedText, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.EBR_EXECUTION)
        );
        assertThat(oneSelectedRecs).isEmpty();
    }

    @Test
    @DisplayName("Phase 4F.3.2.2 (D): Unrecognized recommendation classifications fail closed")
    void testD_linkedRecommendations_unrecognizedFailClosed() {
        String ambiguousText = "1. Ensure general operational cleanliness across the facility.";

        // FOCUSED mode: fails closed, not linked to the selected focus area
        List<LinkedRecommendationDto> focusedRecs = service.buildLinkedRecommendations(
                ambiguousText, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.EBR_EXECUTION)
        );
        assertThat(focusedRecs).isEmpty();

        // FULL mode: preserved as RISK_ASSESSMENT
        List<LinkedRecommendationDto> fullRecs = service.buildLinkedRecommendations(
                ambiguousText, BatchAnalysisMode.FULL, null
        );
        assertThat(fullRecs).hasSize(1);
        assertThat(fullRecs.get(0).getFocusArea()).isEqualTo(BatchAnalysisFocusArea.RISK_ASSESSMENT);
    }

    @Test
    @DisplayName("Phase 4F.3.2.2 (E): Missing-record assessments are filtered correctly for each single-area focus")
    void testE_missingRecordAssessments_filteredCorrectlyForEachSingleAreaFocus() {
        List<RecordAssessmentDto> all8 = createAllEightAssessments();

        // Test MATERIAL_TRACEABILITY
        BatchAnalysisSectionsDto matSections = new BatchAnalysisSectionsDto();
        matSections.setMissingRecordAssessments(new ArrayList<>(all8));
        BatchAnalysisReportService.isolateStructuredSections(matSections, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MATERIAL_TRACEABILITY));
        assertThat(matSections.getMissingRecordAssessments()).hasSize(1);
        assertThat(matSections.getMissingRecordAssessments().get(0).getRecordType()).isEqualTo("Material Issue & Lot Traceability");

        // Test MANUFACTURING_YIELD (maps 3 assessments)
        BatchAnalysisSectionsDto yieldSections = new BatchAnalysisSectionsDto();
        yieldSections.setMissingRecordAssessments(new ArrayList<>(all8));
        BatchAnalysisReportService.isolateStructuredSections(yieldSections, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD));
        assertThat(yieldSections.getMissingRecordAssessments()).hasSize(3);
        assertThat(yieldSections.getMissingRecordAssessments()).extracting(RecordAssessmentDto::getRecordType)
                .containsExactlyInAnyOrder("Manufacturing Start Timestamp", "Manufacturing Completion Timestamp", "Actual Yield Quantity");

        // Test EBR_EXECUTION
        BatchAnalysisSectionsDto ebrSections = new BatchAnalysisSectionsDto();
        ebrSections.setMissingRecordAssessments(new ArrayList<>(all8));
        BatchAnalysisReportService.isolateStructuredSections(ebrSections, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.EBR_EXECUTION));
        assertThat(ebrSections.getMissingRecordAssessments()).hasSize(1);
        assertThat(ebrSections.getMissingRecordAssessments().get(0).getRecordType()).isEqualTo("eBR Execution Steps");

        // Test QC_INSPECTION
        BatchAnalysisSectionsDto qcSections = new BatchAnalysisSectionsDto();
        qcSections.setMissingRecordAssessments(new ArrayList<>(all8));
        BatchAnalysisReportService.isolateStructuredSections(qcSections, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.QC_INSPECTION));
        assertThat(qcSections.getMissingRecordAssessments()).hasSize(1);
        assertThat(qcSections.getMissingRecordAssessments().get(0).getRecordType()).isEqualTo("Quality Control (QC) Inspections");

        // Test DEVIATIONS_CAPA
        BatchAnalysisSectionsDto devSections = new BatchAnalysisSectionsDto();
        devSections.setMissingRecordAssessments(new ArrayList<>(all8));
        BatchAnalysisReportService.isolateStructuredSections(devSections, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.DEVIATIONS_CAPA));
        assertThat(devSections.getMissingRecordAssessments()).hasSize(1);
        assertThat(devSections.getMissingRecordAssessments().get(0).getRecordType()).isEqualTo("Deviations & CAPA");

        // Test QA_DISPOSITION
        BatchAnalysisSectionsDto qaSections = new BatchAnalysisSectionsDto();
        qaSections.setMissingRecordAssessments(new ArrayList<>(all8));
        BatchAnalysisReportService.isolateStructuredSections(qaSections, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.QA_DISPOSITION));
        assertThat(qaSections.getMissingRecordAssessments()).hasSize(1);
        assertThat(qaSections.getMissingRecordAssessments().get(0).getRecordType()).isEqualTo("QA Disposition Review");
    }

    @Test
    @DisplayName("Phase 4F.3.2.2 (F): MISSING_RECORDS alone preserves all eight assessments")
    void testF_missingRecordAssessments_missingRecordsAlonePreservesAllEight() {
        List<RecordAssessmentDto> all8 = createAllEightAssessments();
        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto();
        sections.setMissingRecordAssessments(new ArrayList<>(all8));

        BatchAnalysisReportService.isolateStructuredSections(sections, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MISSING_RECORDS));
        assertThat(sections.getMissingRecordAssessments()).hasSize(8);
    }

    @Test
    @DisplayName("Phase 4F.3.2.2 (G): MISSING_RECORDS combined with another area preserves all eight assessments")
    void testG_missingRecordAssessments_missingRecordsCombinedPreservesAllEight() {
        List<RecordAssessmentDto> all8 = createAllEightAssessments();
        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto();
        sections.setMissingRecordAssessments(new ArrayList<>(all8));

        BatchAnalysisReportService.isolateStructuredSections(sections, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MISSING_RECORDS, BatchAnalysisFocusArea.EBR_EXECUTION));
        assertThat(sections.getMissingRecordAssessments()).hasSize(8);
    }

    @Test
    @DisplayName("Phase 4F.3.2.2 (H): Missing-data warnings are generated only from filtered assessments")
    void testH_missingDataWarnings_generatedOnlyFromFilteredAssessments() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "QA_REVIEWER", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);

        // BatchAnalysisReport in FOCUSED mode on MATERIAL_TRACEABILITY
        BatchAnalysisReport report = new BatchAnalysisReport(
                testBatch, supervisor, "REQ-WARN-1", BatchAnalysisMode.FOCUSED,
                "[\"MATERIAL_TRACEABILITY\"]", "Material Traceability"
        );
        report.setStatus(BatchAnalysisReportStatus.COMPLETED);

        // JSON has missing assessments for Material (gap), QC (gap), and eBR (gap)
        report.setStructuredAnalysisJson("""
                {
                  "executiveSummary": "Material summary.",
                  "materialTraceability": "Material evaluation.",
                  "followUpRecommendations": "1. Verify material dispensing records.",
                  "missingRecordAssessments": [
                    {
                      "recordType": "Material Issue & Lot Traceability",
                      "classification": "GENUINELY_MISSING",
                      "explanation": "Critical raw material RM-001 has no consumption record."
                    },
                    {
                      "recordType": "Quality Control (QC) Inspections",
                      "classification": "GENUINELY_MISSING",
                      "explanation": "QC assay result is absent from batch records."
                    },
                    {
                      "recordType": "eBR Execution Steps",
                      "classification": "GENUINELY_MISSING",
                      "explanation": "Execution step 2 sign-off is absent."
                    }
                  ]
                }
                """);
        report.setSourcesJson("[]");

        when(reportRepository.findByIdAndBatchId(105L, testDossier.getBatchId())).thenReturn(Optional.of(report));

        BatchAnalysisReportResponseDto response = service.getReportById("BAT-2026-0001-01", 105L);

        assertThat(response).isNotNull();
        assertThat(response.getMissingRecordAssessments()).hasSize(1);
        assertThat(response.getMissingRecordAssessments().get(0).getRecordType()).isEqualTo("Material Issue & Lot Traceability");

        // missingDataWarnings must ONLY contain the Material gap warning; QC and eBR gaps must NOT appear
        assertThat(response.getMissingDataWarnings()).hasSize(1);
        assertThat(response.getMissingDataWarnings().get(0)).contains("Critical raw material RM-001 has no consumption record");
        assertThat(response.getMissingDataWarnings().get(0)).doesNotContain("QC assay");
        assertThat(response.getMissingDataWarnings().get(0)).doesNotContain("Execution step 2");
    }

    @Test
    @DisplayName("Phase 4F.3.2.2 (I): Focused report cannot expose unselected-domain findings, recommendations or warnings after DB retrieval")
    void testI_retrievalIsolation_doesNotExposeUnselectedFindingsOrWarnings() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "QA_REVIEWER", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);

        // Report in FOCUSED mode on EBR_EXECUTION with leaked QC data in DB
        BatchAnalysisReport report = new BatchAnalysisReport(
                testBatch, supervisor, "REQ-RETRIEVAL-ISOLATION-1", BatchAnalysisMode.FOCUSED,
                "[\"EBR_EXECUTION\"]", "eBR Execution"
        );
        report.setStatus(BatchAnalysisReportStatus.COMPLETED);
        report.setStructuredAnalysisJson("""
                {
                  "executiveSummary": "Focused eBR summary.",
                  "ebrStepEvaluation": "Step 1 completed successfully.",
                  "qcInspectionAnalysis": "Leaked QC inspection data in DB.",
                  "followUpRecommendations": "1. Verify step logs.\\n2. Re-test QC assay sample in laboratory.",
                  "structuredFindings": [
                    {
                      "focusArea": "QC_INSPECTION",
                      "title": "Leaked QC Finding",
                      "status": "FAIL",
                      "explanation": "QC assay OOS"
                    },
                    {
                      "focusArea": "EBR_EXECUTION",
                      "title": "Valid eBR Finding",
                      "status": "PASS",
                      "explanation": "Step verified"
                    }
                  ],
                  "linkedRecommendations": [
                    {
                      "number": 1,
                      "recommendation": "Re-test QC assay sample in laboratory.",
                      "focusArea": "QC_INSPECTION"
                    },
                    {
                      "number": 2,
                      "recommendation": "Verify step logs.",
                      "focusArea": "EBR_EXECUTION"
                    }
                  ],
                  "missingRecordAssessments": [
                    {
                      "recordType": "Quality Control (QC) Inspections",
                      "classification": "GENUINELY_MISSING",
                      "explanation": "QC assay absent"
                    },
                    {
                      "recordType": "eBR Execution Steps",
                      "classification": "PRESENT",
                      "explanation": "All steps recorded"
                    }
                  ]
                }
                """);
        report.setSourcesJson("[]");

        when(reportRepository.findByIdAndBatchId(106L, testDossier.getBatchId())).thenReturn(Optional.of(report));

        BatchAnalysisReportResponseDto response = service.getReportById("BAT-2026-0001-01", 106L);

        assertThat(response).isNotNull();
        // Section isolation
        assertThat(response.getStructuredAnalysis().getEbrStepEvaluation()).isEqualTo("Step 1 completed successfully.");
        assertThat(response.getStructuredAnalysis().getQcInspectionAnalysis()).isNull();

        // Structured findings isolation
        assertThat(response.getStructuredFindings()).hasSize(1);
        assertThat(response.getStructuredFindings().get(0).getFocusArea()).isEqualTo(BatchAnalysisFocusArea.EBR_EXECUTION);

        // Linked recommendations isolation
        assertThat(response.getLinkedRecommendations()).hasSize(1);
        assertThat(response.getLinkedRecommendations().get(0).getFocusArea()).isEqualTo(BatchAnalysisFocusArea.EBR_EXECUTION);
        assertThat(response.getLinkedRecommendations().get(0).getNumber()).isEqualTo(1);

        // Missing record assessments isolation
        assertThat(response.getMissingRecordAssessments()).hasSize(1);
        assertThat(response.getMissingRecordAssessments().get(0).getRecordType()).isEqualTo("eBR Execution Steps");

        // Missing data warnings isolation: QC gap must NOT be exposed
        assertThat(response.getMissingDataWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Phase 4F.3.2.2 (J): FULL reports preserve all sections and applicable auxiliary data")
    void testJ_fullReports_preserveAllSectionsAndAuxiliaryData() {
        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto();
        sections.setExecutiveSummary("Full summary");
        sections.setBatchStatusAndProgress("In progress");
        sections.setPlannedVsActualMetrics("Yield ok");
        sections.setMaterialTraceability("Materials ok");
        sections.setEbrStepEvaluation("Steps ok");
        sections.setQcInspectionAnalysis("QC ok");
        sections.setQaDispositionStatus("QA pending");
        sections.setDeviationsAndCapa("No deviations");
        sections.setChronologicalAuditAnalysis("Audit ok");
        sections.setMissingRecords("No missing records");
        sections.setRiskObservations("Low risk");
        sections.setFollowUpRecommendations("1. Follow SOP.\n2. Re-test QC assay sample.");

        List<RecordAssessmentDto> all8 = createAllEightAssessments();
        sections.setMissingRecordAssessments(new ArrayList<>(all8));

        List<BatchAnalysisAreaFindingDto> findings = new ArrayList<>();
        findings.add(new BatchAnalysisAreaFindingDto(BatchAnalysisFocusArea.QC_INSPECTION, "QC Check", "PASS", "ok", List.of()));
        findings.add(new BatchAnalysisAreaFindingDto(BatchAnalysisFocusArea.EBR_EXECUTION, "Step Check", "PASS", "ok", List.of()));
        sections.setStructuredFindings(findings);

        List<LinkedRecommendationDto> recs = service.buildLinkedRecommendations(sections.getFollowUpRecommendations(), BatchAnalysisMode.FULL, null);
        sections.setLinkedRecommendations(recs);

        BatchAnalysisReportService.isolateStructuredSections(sections, BatchAnalysisMode.FULL, null);

        assertThat(sections.getExecutiveSummary()).isNotNull();
        assertThat(sections.getBatchStatusAndProgress()).isNotNull();
        assertThat(sections.getPlannedVsActualMetrics()).isNotNull();
        assertThat(sections.getMaterialTraceability()).isNotNull();
        assertThat(sections.getEbrStepEvaluation()).isNotNull();
        assertThat(sections.getQcInspectionAnalysis()).isNotNull();
        assertThat(sections.getQaDispositionStatus()).isNotNull();
        assertThat(sections.getDeviationsAndCapa()).isNotNull();
        assertThat(sections.getChronologicalAuditAnalysis()).isNotNull();
        assertThat(sections.getMissingRecords()).isNotNull();
        assertThat(sections.getRiskObservations()).isNotNull();
        assertThat(sections.getFollowUpRecommendations()).isNotNull();

        assertThat(sections.getMissingRecordAssessments()).hasSize(8);
        assertThat(sections.getStructuredFindings()).hasSize(2);
        assertThat(sections.getLinkedRecommendations()).hasSize(2);
    }

    @Test
    @DisplayName("Phase 4F.3.2.2 (L): Null and unknown assessment types fail closed in FOCUSED mode")
    void testL_nullAndUnknownAssessmentTypes_failClosed() {
        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto();
        List<RecordAssessmentDto> list = new ArrayList<>();
        list.add(null);
        list.add(new RecordAssessmentDto(null, RecordLifecycleClassification.GENUINELY_MISSING, "Null recordType", "REF-1"));
        list.add(new RecordAssessmentDto("Unknown Custom Non-GxP Record", RecordLifecycleClassification.GENUINELY_MISSING, "Custom record", "REF-2"));
        list.add(new RecordAssessmentDto("eBR Execution Steps", RecordLifecycleClassification.PRESENT, "Valid eBR record", "REF-3"));
        sections.setMissingRecordAssessments(list);

        // In EBR_EXECUTION focus:
        BatchAnalysisReportService.isolateStructuredSections(sections, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.EBR_EXECUTION));
        assertThat(sections.getMissingRecordAssessments()).hasSize(1);
        assertThat(sections.getMissingRecordAssessments().get(0).getRecordType()).isEqualTo("eBR Execution Steps");

        // In MISSING_RECORDS focus:
        BatchAnalysisSectionsDto missingRecSections = new BatchAnalysisSectionsDto();
        List<RecordAssessmentDto> list2 = new ArrayList<>();
        list2.add(null);
        list2.add(new RecordAssessmentDto(null, RecordLifecycleClassification.GENUINELY_MISSING, "Null recordType", "REF-1"));
        list2.add(new RecordAssessmentDto("Unknown Custom Non-GxP Record", RecordLifecycleClassification.GENUINELY_MISSING, "Custom record", "REF-2"));
        list2.add(new RecordAssessmentDto("Quality Control (QC) Inspections", RecordLifecycleClassification.PRESENT, "Valid QC record", "REF-4"));
        missingRecSections.setMissingRecordAssessments(list2);

        BatchAnalysisReportService.isolateStructuredSections(missingRecSections, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MISSING_RECORDS));
        // Null and unknown entries must fail closed even in MISSING_RECORDS
        assertThat(missingRecSections.getMissingRecordAssessments()).hasSize(1);
        assertThat(missingRecSections.getMissingRecordAssessments().get(0).getRecordType()).isEqualTo("Quality Control (QC) Inspections");
    }

    private List<RecordAssessmentDto> createAllEightAssessments() {
        List<RecordAssessmentDto> list = new ArrayList<>();
        list.add(new RecordAssessmentDto("Manufacturing Start Timestamp", RecordLifecycleClassification.PRESENT, "Recorded start", "BATCH"));
        list.add(new RecordAssessmentDto("Manufacturing Completion Timestamp", RecordLifecycleClassification.PRESENT, "Recorded end", "BATCH"));
        list.add(new RecordAssessmentDto("Actual Yield Quantity", RecordLifecycleClassification.PRESENT, "Yield recorded", "BATCH"));
        list.add(new RecordAssessmentDto("Material Issue & Lot Traceability", RecordLifecycleClassification.PRESENT, "Materials allocated", "BATCH"));
        list.add(new RecordAssessmentDto("eBR Execution Steps", RecordLifecycleClassification.PRESENT, "Steps executed", "BATCH"));
        list.add(new RecordAssessmentDto("Quality Control (QC) Inspections", RecordLifecycleClassification.PRESENT, "QC passed", "BATCH"));
        list.add(new RecordAssessmentDto("Deviations & CAPA", RecordLifecycleClassification.PRESENT, "Deviations logged", "BATCH"));
        list.add(new RecordAssessmentDto("QA Disposition Review", RecordLifecycleClassification.PRESENT, "QA pending", "BATCH"));
        return list;
    }

    // =========================================================================
    // Phase 4F.3.2.3.1 — Close Focused Recommendation Leakage & Attribution Ambiguity
    // =========================================================================

    @Test
    @DisplayName("Phase 4F.3.2.3.1 (1): detectRecommendationFocusAreas - Positive and negative keyword matching rules")
    void testKeywordMatchingRules_positiveAndNegative() {
        // Negative: "a lot of" must NOT match MATERIAL_TRACEABILITY, but must match DEVIATIONS_CAPA
        Set<BatchAnalysisFocusArea> lotOfDevs = BatchAnalysisReportService.detectRecommendationFocusAreas(
                "There are a lot of deviations to review."
        );
        assertThat(lotOfDevs).containsExactly(BatchAnalysisFocusArea.DEVIATIONS_CAPA);
        assertThat(lotOfDevs).doesNotContain(BatchAnalysisFocusArea.MATERIAL_TRACEABILITY);

        // Negative: "next steps to" must NOT match EBR_EXECUTION, but must match MISSING_RECORDS
        Set<BatchAnalysisFocusArea> nextSteps = BatchAnalysisReportService.detectRecommendationFocusAreas(
                "Take the next steps to improve documentation."
        );
        assertThat(nextSteps).containsExactly(BatchAnalysisFocusArea.MISSING_RECORDS);
        assertThat(nextSteps).doesNotContain(BatchAnalysisFocusArea.EBR_EXECUTION);

        // Negative: "modified-release" must NOT match QA_DISPOSITION
        Set<BatchAnalysisFocusArea> modRelease = BatchAnalysisReportService.detectRecommendationFocusAreas(
                "Modified-release formulation requires review."
        );
        assertThat(modRelease).isEmpty();

        // Positive: Genuine material lot recommendation matches MATERIAL_TRACEABILITY
        Set<BatchAnalysisFocusArea> genuineLot = BatchAnalysisReportService.detectRecommendationFocusAreas(
                "Verify lot MAT-001 consumption records."
        );
        assertThat(genuineLot).containsExactly(BatchAnalysisFocusArea.MATERIAL_TRACEABILITY);

        // Positive: Genuine eBR execution-step matches EBR_EXECUTION
        Set<BatchAnalysisFocusArea> genuineEbr = BatchAnalysisReportService.detectRecommendationFocusAreas(
                "Verify eBR execution step 3 sign-off."
        );
        assertThat(genuineEbr).containsExactly(BatchAnalysisFocusArea.EBR_EXECUTION);

        // Positive: Genuine QA disposition matches QA_DISPOSITION
        Set<BatchAnalysisFocusArea> genuineQa = BatchAnalysisReportService.detectRecommendationFocusAreas(
                "Submit completed dossier to QA for formal disposition review."
        );
        assertThat(genuineQa).containsExactly(BatchAnalysisFocusArea.QA_DISPOSITION);
    }

    @Test
    @DisplayName("Phase 4F.3.2.3.1 (2): Multi-domain attribution fail-closed rules in FOCUSED mode")
    void testMultiDomainAttribution_failClosedRules() {
        List<BatchAnalysisFocusArea> selected = List.of(BatchAnalysisFocusArea.EBR_EXECUTION, BatchAnalysisFocusArea.QC_INSPECTION);
        List<BatchAnalysisFocusArea> reversed = List.of(BatchAnalysisFocusArea.QC_INSPECTION, BatchAnalysisFocusArea.EBR_EXECUTION);

        // 1. One selected-area match -> Retained
        String pureEbr = "1. Verify eBR execution step 3 sign-off.";
        List<LinkedRecommendationDto> ebrRecs = service.buildLinkedRecommendations(pureEbr, BatchAnalysisMode.FOCUSED, selected);
        assertThat(ebrRecs).hasSize(1);
        assertThat(ebrRecs.get(0).getFocusArea()).isEqualTo(BatchAnalysisFocusArea.EBR_EXECUTION);

        // 2. One unselected-area match -> Excluded
        String pureYield = "1. Reconcile mass balance recovery percentage.";
        List<LinkedRecommendationDto> yieldRecs = service.buildLinkedRecommendations(pureYield, BatchAnalysisMode.FOCUSED, selected);
        assertThat(yieldRecs).isEmpty();

        // 3. One selected plus one unselected match -> Excluded (multi-domain fails closed)
        String ebrPlusYield = "1. Verify eBR execution step 3 sign-off and reconcile mass balance yield.";
        List<LinkedRecommendationDto> ebrYieldRecs = service.buildLinkedRecommendations(ebrPlusYield, BatchAnalysisMode.FOCUSED, selected);
        assertThat(ebrYieldRecs).isEmpty();

        // 4. Two selected-area matches -> Excluded (multi-domain fails closed)
        String twoSelected = "1. Verify eBR execution step 3 sign-off and check QC analytical assay results.";
        List<LinkedRecommendationDto> twoSelectedRecs = service.buildLinkedRecommendations(twoSelected, BatchAnalysisMode.FOCUSED, selected);
        assertThat(twoSelectedRecs).isEmpty();

        // 5. Three-area matches -> Excluded
        String threeAreas = "1. Verify eBR execution step 3, check raw material lot MAT-01, and review QC assay.";
        List<LinkedRecommendationDto> threeAreaRecs = service.buildLinkedRecommendations(threeAreas, BatchAnalysisMode.FOCUSED, selected);
        assertThat(threeAreaRecs).isEmpty();

        // 6. Reversed focus-area input order -> Invariant results
        List<LinkedRecommendationDto> normalOrder = service.buildLinkedRecommendations(pureEbr, BatchAnalysisMode.FOCUSED, selected);
        List<LinkedRecommendationDto> reverseOrder = service.buildLinkedRecommendations(pureEbr, BatchAnalysisMode.FOCUSED, reversed);
        assertThat(normalOrder).hasSize(1);
        assertThat(reverseOrder).hasSize(1);
        assertThat(normalOrder.get(0).getFocusArea()).isEqualTo(reverseOrder.get(0).getFocusArea());

        // 7. Ambiguous text with no reliable match -> Excluded
        String ambiguous = "1. Ensure proper operational housekeeping across facility.";
        List<LinkedRecommendationDto> ambRecs = service.buildLinkedRecommendations(ambiguous, BatchAnalysisMode.FOCUSED, selected);
        assertThat(ambRecs).isEmpty();

        // 8. Null and blank recommendation text -> Excluded
        assertThat(service.buildLinkedRecommendations(null, BatchAnalysisMode.FOCUSED, selected)).isEmpty();
        assertThat(service.buildLinkedRecommendations("   ", BatchAnalysisMode.FOCUSED, selected)).isEmpty();
    }

    @Test
    @DisplayName("Phase 4F.3.2.3.1 (3): Response DTO serialization - advisoryRecommendations strictly derived from filtered linkedRecommendations")
    void testResponseDtoSerialization_advisoryRecommendationsDerivedFromFilteredLinkedRecommendations() throws Exception {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "QA_REVIEWER", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);

        // Report in FOCUSED mode on EBR_EXECUTION
        BatchAnalysisReport report = new BatchAnalysisReport(
                testBatch, supervisor, "REQ-ADVISORY-SYNC-1", BatchAnalysisMode.FOCUSED,
                "[\"EBR_EXECUTION\"]", "eBR Execution"
        );
        report.setStatus(BatchAnalysisReportStatus.COMPLETED);

        // AI generated 3 recommendations: #1 is QC (unselected), #2 is eBR (selected), #3 is Yield (unselected)
        report.setStructuredAnalysisJson("""
                {
                  "executiveSummary": "Focused eBR execution evaluation.",
                  "ebrStepEvaluation": "Step 1 executed successfully.",
                  "followUpRecommendations": "1. Re-test QC assay sample in laboratory.\\n2. Verify eBR execution step 2 sign-off.\\n3. Reconcile mass balance recovery percentage.",
                  "linkedRecommendations": [
                    {
                      "number": 1,
                      "recommendation": "Verify eBR execution step 2 sign-off.",
                      "focusArea": "EBR_EXECUTION"
                    }
                  ]
                }
                """);
        report.setSourcesJson("[]");

        when(reportRepository.findByIdAndBatchId(201L, testDossier.getBatchId())).thenReturn(Optional.of(report));

        BatchAnalysisReportResponseDto response = service.getReportById("BAT-2026-0001-01", 201L);

        // 1. Unselected recommendations appear in NEITHER field
        assertThat(response.getLinkedRecommendations()).hasSize(1);
        assertThat(response.getAdvisoryRecommendations()).hasSize(1);

        // 2. Selected recommendation appears in BOTH fields with identical text and order
        assertThat(response.getLinkedRecommendations().get(0).getRecommendation())
                .isEqualTo("Verify eBR execution step 2 sign-off.");
        assertThat(response.getAdvisoryRecommendations().get(0))
                .isEqualTo("Verify eBR execution step 2 sign-off.");

        // 3. Numbering is consistent (1-based consecutive)
        assertThat(response.getLinkedRecommendations().get(0).getNumber()).isEqualTo(1);

        // 4. Test actual JSON serialization of DTO
        String serializedJson = objectMapper.writeValueAsString(response);
        assertThat(serializedJson).doesNotContain("Re-test QC assay sample in laboratory.");
        assertThat(serializedJson).doesNotContain("Reconcile mass balance recovery percentage.");
        assertThat(serializedJson).contains("Verify eBR execution step 2 sign-off.");
    }

    @Test
    @DisplayName("Phase 4F.3.2.3.1 (4): Empty filtered recommendations list returns empty advisoryRecommendations without fallback")
    void testResponseDtoSerialization_emptyFilteredListDoesNotTriggerFallback() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "QA_REVIEWER", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);

        // Report in FOCUSED mode on EBR_EXECUTION where ALL recommendations belong to unselected domains
        BatchAnalysisReport report = new BatchAnalysisReport(
                testBatch, supervisor, "REQ-EMPTY-FALLBACK-1", BatchAnalysisMode.FOCUSED,
                "[\"EBR_EXECUTION\"]", "eBR Execution"
        );
        report.setStatus(BatchAnalysisReportStatus.COMPLETED);
        report.setStructuredAnalysisJson("""
                {
                  "executiveSummary": "Focused eBR execution evaluation.",
                  "ebrStepEvaluation": "Step 1 executed successfully.",
                  "followUpRecommendations": "1. Re-test QC assay sample in laboratory.\\n2. Reconcile mass balance recovery percentage.",
                  "linkedRecommendations": []
                }
                """);
        report.setSourcesJson("[]");

        when(reportRepository.findByIdAndBatchId(202L, testDossier.getBatchId())).thenReturn(Optional.of(report));

        BatchAnalysisReportResponseDto response = service.getReportById("BAT-2026-0001-01", 202L);

        // Both must be strictly empty; no fallback to raw text
        assertThat(response.getLinkedRecommendations()).isEmpty();
        assertThat(response.getAdvisoryRecommendations()).isEmpty();
    }

    @Test
    @DisplayName("Phase 4F.3.2.3.1 (5): FULL mode preserves all recommendations in both fields")
    void testResponseDtoSerialization_fullModePreservesAllRecommendations() {
        when(authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR", "QA_REVIEWER", "ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);

        BatchAnalysisReport report = new BatchAnalysisReport(
                testBatch, supervisor, "REQ-FULL-SYNC-1", BatchAnalysisMode.FULL, null, null
        );
        report.setStatus(BatchAnalysisReportStatus.COMPLETED);
        report.setStructuredAnalysisJson(createValid12SectionJson());
        report.setSourcesJson("[]");

        when(reportRepository.findByIdAndBatchId(203L, testDossier.getBatchId())).thenReturn(Optional.of(report));

        BatchAnalysisReportResponseDto response = service.getReportById("BAT-2026-0001-01", 203L);

        assertThat(response).isNotNull();
        assertThat(response.getAnalysisMode()).isEqualTo(BatchAnalysisMode.FULL);
        assertThat(response.getAdvisoryRecommendations()).isNotEmpty();
    }

    // =========================================================================
    // Phase 4F.3.3.4 — Dedicated Batch Analysis Token Budget Tests
    // =========================================================================

    @Test
    @DisplayName("Phase 4F.3.3.4 (1): Default configured value 4096 is passed exactly to the three-argument LLM method")
    void testReportGeneration_passesConfiguredTokenBudget4096ToLlmClient() {
        mockSupervisorAuth();
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);
        when(batchRepository.findById(10L)).thenReturn(Optional.of(testBatch));

        BatchAnalysisReport generatingReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-TOKEN-4096");
        when(txHelper.getOrCreateInitialReportTx(any(), any(), any(), any(), any())).thenReturn(generatingReport);
        when(batchContextBuilder.buildBatchContext(testDossier)).thenReturn(new BatchContext("Facts", List.of()));
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.generate(anyString(), anyString(), eq(4096))).thenReturn(
                new LlmResponse(createValid12SectionJson(), "gpt-4o-mini", "resp-4096", 100, 200, 300)
        );

        BatchAnalysisReport completedReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-TOKEN-4096");
        completedReport.complete("gpt-4o-mini", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 1, 1, 1, createValid12SectionJson(), "# MD", "[]");
        when(txHelper.completeReportTx(any(), any(), any(), any(), any(), any(), any())).thenReturn(completedReport);

        service.generateReport("BAT-2026-0001-01", new BatchAnalysisReportRequestDto("REQ-TOKEN-4096"));

        verify(llmClient).generate(anyString(), anyString(), eq(4096));
    }

    @Test
    @DisplayName("Phase 4F.3.3.4 (2): Custom configured value 2048 is passed unchanged without Math.max override")
    void testReportGeneration_passesCustomTokenBudget2048ToLlmClient() {
        mockSupervisorAuth();
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);
        when(batchRepository.findById(10L)).thenReturn(Optional.of(testBatch));

        AiCopilotProperties customProps = new AiCopilotProperties();
        customProps.setBatchAnalysisMaxOutputTokens(2048);

        BatchAnalysisReportService customService = new BatchAnalysisReportService(
                reportRepository, txHelper, batchTraceabilityService, batchRepository,
                userAccountRepository, authenticatedActorResolver, batchContextBuilder,
                llmClient, objectMapper, auditTrailService, new BatchLifecycleRecordClassifier(), customProps
        );

        BatchAnalysisReport generatingReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-TOKEN-2048");
        when(txHelper.getOrCreateInitialReportTx(any(), any(), any(), any(), any())).thenReturn(generatingReport);
        when(batchContextBuilder.buildBatchContext(testDossier)).thenReturn(new BatchContext("Facts", List.of()));
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.generate(anyString(), anyString(), eq(2048))).thenReturn(
                new LlmResponse(createValid12SectionJson(), "gpt-4o-mini", "resp-2048", 100, 200, 300)
        );

        BatchAnalysisReport completedReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-TOKEN-2048");
        completedReport.complete("gpt-4o-mini", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 1, 1, 1, createValid12SectionJson(), "# MD", "[]");
        when(txHelper.completeReportTx(any(), any(), any(), any(), any(), any(), any())).thenReturn(completedReport);

        customService.generateReport("BAT-2026-0001-01", new BatchAnalysisReportRequestDto("REQ-TOKEN-2048"));

        verify(llmClient).generate(anyString(), anyString(), eq(2048));
    }

    @Test
    @DisplayName("Phase 4F.3.3.4 (3): Custom configured value 6000 is passed unchanged")
    void testReportGeneration_passesCustomTokenBudget6000ToLlmClient() {
        mockSupervisorAuth();
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);
        when(batchRepository.findById(10L)).thenReturn(Optional.of(testBatch));

        AiCopilotProperties customProps = new AiCopilotProperties();
        customProps.setBatchAnalysisMaxOutputTokens(6000);

        BatchAnalysisReportService customService = new BatchAnalysisReportService(
                reportRepository, txHelper, batchTraceabilityService, batchRepository,
                userAccountRepository, authenticatedActorResolver, batchContextBuilder,
                llmClient, objectMapper, auditTrailService, new BatchLifecycleRecordClassifier(), customProps
        );

        BatchAnalysisReport generatingReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-TOKEN-6000");
        when(txHelper.getOrCreateInitialReportTx(any(), any(), any(), any(), any())).thenReturn(generatingReport);
        when(batchContextBuilder.buildBatchContext(testDossier)).thenReturn(new BatchContext("Facts", List.of()));
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.generate(anyString(), anyString(), eq(6000))).thenReturn(
                new LlmResponse(createValid12SectionJson(), "gpt-4o-mini", "resp-6000", 100, 200, 300)
        );

        BatchAnalysisReport completedReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-TOKEN-6000");
        completedReport.complete("gpt-4o-mini", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 1, 1, 1, createValid12SectionJson(), "# MD", "[]");
        when(txHelper.completeReportTx(any(), any(), any(), any(), any(), any(), any())).thenReturn(completedReport);

        customService.generateReport("BAT-2026-0001-01", new BatchAnalysisReportRequestDto("REQ-TOKEN-6000"));

        verify(llmClient).generate(anyString(), anyString(), eq(6000));
    }

    @Test
    @DisplayName("Phase 4F.3.3.4 (4): Incomplete provider response (e.g. token limit hit) fails closed and marks report FAILED")
    void testReportGeneration_incompleteProviderResponseFailsClosed() {
        mockSupervisorAuth();
        when(batchTraceabilityService.getTraceabilityDossierByIdentifier("BAT-2026-0001-01")).thenReturn(testDossier);
        when(batchRepository.findById(10L)).thenReturn(Optional.of(testBatch));

        BatchAnalysisReport generatingReport = new BatchAnalysisReport(testBatch, supervisor, "REQ-INCOMPLETE");
        when(txHelper.getOrCreateInitialReportTx(any(), any(), any(), any(), any())).thenReturn(generatingReport);
        when(batchContextBuilder.buildBatchContext(testDossier)).thenReturn(new BatchContext("Facts", List.of()));
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.generate(anyString(), anyString(), anyInt())).thenThrow(
                new LlmClientException(LlmClientException.ErrorCategory.INVALID_RESPONSE, "AI provider returned incomplete output (reason: max_output_tokens).")
        );

        BatchAnalysisReportRequestDto request = new BatchAnalysisReportRequestDto("REQ-INCOMPLETE");

        assertThatThrownBy(() -> service.generateReport("BAT-2026-0001-01", request))
                .isInstanceOf(LlmClientException.class)
                .hasMessageContaining("AI provider returned incomplete output (reason: max_output_tokens).");

        verify(txHelper).failReportTx(any(), contains("AI provider returned incomplete output"), eq("supervisor_alice"));
    }

    // ======================================================================================
    // Phase 4F.4.2: Lifecycle-Aware Manufacturing Yield and Markdown Tests
    // ======================================================================================

    @Test
    @DisplayName("Phase 4F.4.2 (1): buildStructuredFindings classifies PLANNED batch yield as NOT_YET_EXPECTED")
    void buildStructuredFindings_plannedBatchYield_classifiedAsNotYetExpected() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(10L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setBatchSize(new BigDecimal("500.00"));
        dossier.setBatchUnit("kg");
        dossier.setActualYield(null);

        List<BatchAnalysisAreaFindingDto> findings = service.buildStructuredFindings(
                dossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD), List.of()
        );

        assertThat(findings).hasSize(1);
        BatchAnalysisAreaFindingDto finding = findings.get(0);
        assertThat(finding.getFocusArea()).isEqualTo(BatchAnalysisFocusArea.MANUFACTURING_YIELD);
        assertThat(finding.getStatus()).isEqualTo("NOT_YET_EXPECTED");
        assertThat(finding.getExplanation())
                .isEqualTo("Planned: 500.00 kg, Actual: Not yet expected (Batch execution has not commenced)")
                .doesNotContain("UNRECORDED")
                .doesNotContain("Pending")
                .doesNotContain("Missing record");
    }

    @Test
    @DisplayName("Phase 4F.4.2 (2): buildStructuredFindings classifies IN_PROGRESS batch yield as NOT_YET_EXPECTED")
    void buildStructuredFindings_inProgressBatchYield_classifiedAsNotYetExpected() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(10L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.IN_PROGRESS);
        dossier.setBatchSize(new BigDecimal("500.00"));
        dossier.setBatchUnit("kg");
        dossier.setActualYield(null);

        List<BatchAnalysisAreaFindingDto> findings = service.buildStructuredFindings(
                dossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD), List.of()
        );

        assertThat(findings).hasSize(1);
        BatchAnalysisAreaFindingDto finding = findings.get(0);
        assertThat(finding.getFocusArea()).isEqualTo(BatchAnalysisFocusArea.MANUFACTURING_YIELD);
        assertThat(finding.getStatus()).isEqualTo("NOT_YET_EXPECTED");
        assertThat(finding.getExplanation())
                .isEqualTo("Planned: 500.00 kg, Actual: In progress (Recorded upon batch completion)");
    }

    @Test
    @DisplayName("Phase 4F.4.2 (3): buildStructuredFindings classifies CANCELLED batch without yield as NOT_APPLICABLE")
    void buildStructuredFindings_cancelledBatchWithoutYield_classifiedAsNotApplicable() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(10L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.CANCELLED);
        dossier.setBatchSize(new BigDecimal("500.00"));
        dossier.setBatchUnit("kg");
        dossier.setActualYield(null);

        List<BatchAnalysisAreaFindingDto> findings = service.buildStructuredFindings(
                dossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD), List.of()
        );

        assertThat(findings).hasSize(1);
        BatchAnalysisAreaFindingDto finding = findings.get(0);
        assertThat(finding.getFocusArea()).isEqualTo(BatchAnalysisFocusArea.MANUFACTURING_YIELD);
        assertThat(finding.getStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.getExplanation())
                .isEqualTo("Planned: 500.00 kg, Actual: Not applicable (Batch cancelled; yield reconciliation not applicable)");
    }

    @Test
    @DisplayName("Phase 4F.4.2 (4): buildStructuredFindings classifies COMPLETED batch without yield as GENUINELY_MISSING")
    void buildStructuredFindings_completedBatchWithoutYield_classifiedAsGenuinelyMissing() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(10L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.COMPLETED);
        dossier.setBatchSize(new BigDecimal("500.00"));
        dossier.setBatchUnit("kg");
        dossier.setActualYield(null);

        List<BatchAnalysisAreaFindingDto> findings = service.buildStructuredFindings(
                dossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD), List.of()
        );

        assertThat(findings).hasSize(1);
        BatchAnalysisAreaFindingDto finding = findings.get(0);
        assertThat(finding.getFocusArea()).isEqualTo(BatchAnalysisFocusArea.MANUFACTURING_YIELD);
        assertThat(finding.getStatus()).isEqualTo("GENUINELY_MISSING");
        assertThat(finding.getExplanation())
                .isEqualTo("Planned: 500.00 kg, Actual: Missing record (Unrecorded on completed batch)");
    }

    @Test
    @DisplayName("Phase 4F.4.2 (5): renderMarkdownReport renders lifecycle-aware yield text for PLANNED batch in FOCUSED and FULL modes")
    void renderMarkdownReport_plannedBatch_rendersNotYetExpectedForYieldAndMetrics() {
        Batch batch = new Batch();
        batch.setBatchNumber("BAT-2026-0010-01");
        batch.setStatus(BatchStatus.PLANNED);

        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setBatchSize(new BigDecimal("500.00"));
        dossier.setBatchUnit("kg");

        BatchAnalysisMetricsDto metrics = new BatchAnalysisMetricsDto(
                new BigDecimal("500.00"), null, null, null, 0, 0, 0
        );

        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto();
        sections.setExecutiveSummary("Executive summary for planned batch.");
        sections.setPlannedVsActualMetrics("Target yield 500 kg.");
        sections.setFollowUpRecommendations("1. Begin manufacturing execution.");

        // 1. FOCUSED mode
        String focusedMarkdown = service.renderMarkdownReport(
                batch, dossier, metrics, sections, "gpt-4o",
                BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD)
        );

        assertThat(focusedMarkdown)
                .contains("- **Planned Batch Size:** 500.00 kg")
                .contains("- **Actual Yield:** Not yet expected")
                .contains("- **Yield Percentage:** Not yet expected")
                .contains("- **Yield Variance:** Not yet expected")
                .doesNotContain("Unrecorded / In progress")
                .doesNotContain("Missing record");

        // 2. FULL mode
        sections.setBatchStatusAndProgress("Batch planned.");
        sections.setMaterialTraceability("Materials staged.");
        sections.setEbrStepEvaluation("Steps pending.");
        sections.setQcInspectionAnalysis("QC pending.");
        sections.setQaDispositionStatus("Pending QA.");
        sections.setDeviationsAndCapa("Zero deviations.");
        sections.setChronologicalAuditAnalysis("Audit clear.");
        sections.setMissingRecords("No gaps.");
        sections.setRiskObservations("Standard risk.");

        String fullMarkdown = service.renderMarkdownReport(
                batch, dossier, metrics, sections, "gpt-4o",
                BatchAnalysisMode.FULL, null
        );

        assertThat(fullMarkdown)
                .contains("- **Planned Batch Size:** 500.00 kg")
                .contains("- **Actual Yield:** Not yet expected")
                .contains("- **Yield Percentage:** Not yet expected")
                .contains("- **Yield Variance:** Not yet expected")
                .doesNotContain("Unrecorded / In progress");
    }

    @Test
    @DisplayName("Phase 4F.4.3 (1): PLANNED FULL and FOCUSED narratives contain no contradictory missing/unrecorded/in-progress yield statements")
    void phase4F43_plannedFullAndFocusedNarratives_noContradictoryYieldStatements() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setBatchSize(new BigDecimal("250.0000"));
        dossier.setBatchUnit("kg");

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(dossier);

        // Raw LLM output with unrecorded / missing yield text and duplicate pseudo-lines
        String rawLlmMetrics = "Planned Batch Size: 250.0000 kg\n" +
                "Actual Yield: Not recorded (manufacturing not yet started)\n" +
                "Yield Percentage: Not recorded\n" +
                "Yield Variance: Not recorded\n" +
                "Production Duration: Not started\n\n" +
                "Actual yield is unrecorded because physical manufacturing operations have not commenced.";

        String normalizedMetrics = BatchAnalysisReportService.normalizePlannedVsActualMetrics(rawLlmMetrics, dossier, metrics);

        // Must contain authoritative deterministic block
        assertThat(normalizedMetrics)
                .contains("- **Planned Batch Size:** 250.0000 kg")
                .contains("- **Actual Yield:** Not yet expected")
                .contains("- **Yield Percentage:** Not yet expected")
                .contains("- **Yield Variance:** Not yet expected");

        // Must NOT contain unrecorded, in progress, or missing
        assertThat(normalizedMetrics)
                .doesNotContain("Not recorded")
                .doesNotContain("Unrecorded")
                .doesNotContain("Missing record")
                .doesNotContain("In progress");

        // Executive summary normalization
        String rawExec = "All manufacturing yield metrics — including actual yield quantity, yield percentage, and yield variance — are unrecorded due to the batch not yet having progressed beyond the planning stage.";
        String normalizedExec = BatchAnalysisReportService.normalizeExecutiveSummary(rawExec, dossier);
        assertThat(normalizedExec)
                .doesNotContain("unrecorded")
                .contains("not yet expected");
    }

    @Test
    @DisplayName("Phase 4F.4.3 (2): IN_PROGRESS wording remains lifecycle-appropriate")
    void phase4F43_inProgressWording_remainsLifecycleAppropriate() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-2026-0011-01");
        dossier.setBatchStatus(BatchStatus.IN_PROGRESS);
        dossier.setBatchSize(new BigDecimal("500.00"));
        dossier.setBatchUnit("kg");
        dossier.setActualYield(null);

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(dossier);

        String normalizedMetrics = BatchAnalysisReportService.normalizePlannedVsActualMetrics("", dossier, metrics);
        assertThat(normalizedMetrics)
                .contains("- **Actual Yield:** In progress")
                .contains("- **Yield Percentage:** Not yet available until completion")
                .contains("- **Yield Variance:** Not yet available until completion")
                .doesNotContain("Missing record")
                .doesNotContain("Not yet expected");
    }

    @Test
    @DisplayName("Phase 4F.4.3 (3): COMPLETED with absent yield is still identified as genuinely missing")
    void phase4F43_completedWithAbsentYield_identifiedAsGenuinelyMissing() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-2026-0012-01");
        dossier.setBatchStatus(BatchStatus.COMPLETED);
        dossier.setBatchSize(new BigDecimal("1000.00"));
        dossier.setBatchUnit("kg");
        dossier.setActualYield(null);

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(dossier);

        String normalizedMetrics = BatchAnalysisReportService.normalizePlannedVsActualMetrics("", dossier, metrics);
        assertThat(normalizedMetrics)
                .contains("- **Actual Yield:** Missing record (Unrecorded on completed batch)")
                .contains("- **Yield Percentage:** Unavailable (Actual yield unrecorded)")
                .contains("- **Yield Variance:** Unavailable (Actual yield unrecorded)");

        List<BatchAnalysisAreaFindingDto> findings = service.buildStructuredFindings(dossier, BatchAnalysisMode.FULL, null, List.of());
        BatchAnalysisAreaFindingDto yieldFinding = findings.stream()
                .filter(f -> f.getFocusArea() == BatchAnalysisFocusArea.MANUFACTURING_YIELD)
                .findFirst()
                .orElseThrow();
        assertThat(yieldFinding.getStatus()).isEqualTo("GENUINELY_MISSING");
        assertThat(yieldFinding.getExplanation()).contains("Missing record (Unrecorded on completed batch)");
    }

    @Test
    @DisplayName("Phase 4F.4.3 (4): CANCELLED wording remains not applicable")
    void phase4F43_cancelledWording_remainsNotApplicable() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-2026-0013-01");
        dossier.setBatchStatus(BatchStatus.CANCELLED);
        dossier.setBatchSize(new BigDecimal("300.00"));
        dossier.setBatchUnit("kg");
        dossier.setActualYield(null);

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(dossier);

        String normalizedMetrics = BatchAnalysisReportService.normalizePlannedVsActualMetrics("", dossier, metrics);
        assertThat(normalizedMetrics)
                .contains("- **Actual Yield:** Not applicable (Batch cancelled)")
                .contains("- **Yield Percentage:** Not applicable")
                .contains("- **Yield Variance:** Not applicable");

        List<BatchAnalysisAreaFindingDto> findings = service.buildStructuredFindings(dossier, BatchAnalysisMode.FULL, null, List.of());
        BatchAnalysisAreaFindingDto yieldFinding = findings.stream()
                .filter(f -> f.getFocusArea() == BatchAnalysisFocusArea.MANUFACTURING_YIELD)
                .findFirst()
                .orElseThrow();
        assertThat(yieldFinding.getStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(yieldFinding.getExplanation()).contains("Not applicable (Batch cancelled");
    }

    @Test
    @DisplayName("Phase 4F.4.3 (5): Duplicate metrics sections cannot contradict one another")
    void phase4F43_duplicateMetricsSections_cannotContradictOneAnother() {
        Batch batch = new Batch();
        batch.setBatchNumber("BAT-2026-0010-01");
        batch.setStatus(BatchStatus.PLANNED);

        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setBatchSize(new BigDecimal("250.0000"));
        dossier.setBatchUnit("kg");

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(dossier);

        // Sections already normalized with deterministic block
        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto();
        sections.setExecutiveSummary("Executive summary text.");
        sections.setPlannedVsActualMetrics(BatchAnalysisReportService.normalizePlannedVsActualMetrics(
                "Planned Batch Size: 250.0000 kg\nActual Yield: Not recorded\nYield Percentage: Not recorded\nYield Variance: Not recorded",
                dossier, metrics
        ));
        sections.setFollowUpRecommendations("1. Prepare dispensing equipment.");

        String fullMarkdown = service.renderMarkdownReport(batch, dossier, metrics, sections, "model", BatchAnalysisMode.FULL, null);

        // Ensure "- **Planned Batch Size:**" appears EXACTLY ONCE
        int firstIndex = fullMarkdown.indexOf("- **Planned Batch Size:**");
        int lastIndex = fullMarkdown.lastIndexOf("- **Planned Batch Size:**");
        assertThat(firstIndex).isNotEqualTo(-1);
        assertThat(firstIndex).isEqualTo(lastIndex);

        // Ensure no contradictory statements exist
        assertThat(fullMarkdown)
                .doesNotContain("Unrecorded / In progress")
                .doesNotContain("Actual Yield: Not recorded");
    }

    @Test
    @DisplayName("Phase 4F.4.3 (6): Recommendations for a PLANNED batch do not incorrectly call for remediation of yield records")
    void phase4F43_plannedBatchRecommendations_doNotCallForYieldRecordRemediation() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);

        String modelRecs = "1. Initiate manufacturing process to capture actual yield data.\n" +
                "2. Ensure real-time yield tracking is enabled and verify that the MES records the actual weight of produced product.\n" +
                "3. Recalculate yield percentage and variance once actual yield is available.";

        String normalized = BatchAnalysisReportService.normalizeFollowUpRecommendations(
                modelRecs, dossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD)
        );

        // Recommendation 1 must NOT treat starting production as a remediation to capture yield data
        assertThat(normalized).doesNotContain("Initiate manufacturing process to capture actual yield data");
        assertThat(normalized).contains("No immediate yield-related corrective action is required before manufacturing begins");

        // Linked recommendations must link valid item to MANUFACTURING_YIELD
        List<LinkedRecommendationDto> linked = service.buildLinkedRecommendations(
                normalized, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD)
        );
        assertThat(linked).isNotEmpty();
        assertThat(linked.get(0).getFocusArea()).isEqualTo(BatchAnalysisFocusArea.MANUFACTURING_YIELD);
        assertThat(linked.get(0).getRecommendation()).doesNotContain("to capture actual yield data");
    }

    @Test
    @DisplayName("Phase 4F.4.3.1: Historical reports are returned without content modification (verbatim Markdown and structured analysis)")
    void phase4F431_historicalReportsReturnedVerbatimWithoutReadTimeRewriting() throws Exception {
        Batch batch = new Batch();
        batch.setId(10L);
        batch.setBatchNumber("BAT-2026-0010-01");
        batch.setStatus(BatchStatus.PLANNED);

        String legacyMarkdown = "## 2. Planned vs Actual Production Metrics\n" +
                "Actual Yield: Not recorded (manufacturing not yet started)\n\n" +
                "- **Planned Batch Size:** 250.0000 kg\n" +
                "- **Actual Yield:** Unrecorded / In progress\n" +
                "- **Yield Percentage:** N/A\n" +
                "- **Yield Variance:** N/A\n\n" +
                "## 3. Recommended Follow-Up Actions\n" +
                "1. Initiate manufacturing process to capture actual yield data.";

        String legacyJson = "{\"executiveSummary\":\"All manufacturing yield metrics are unrecorded due to planning stage.\"," +
                "\"plannedVsActualMetrics\":\"Actual Yield: Not recorded (manufacturing not yet started)\"," +
                "\"followUpRecommendations\":\"1. Initiate manufacturing process to capture actual yield data.\"," +
                "\"linkedRecommendations\":[{\"number\":1,\"recommendation\":\"Initiate manufacturing process to capture actual yield data.\",\"focusArea\":\"MANUFACTURING_YIELD\"}]}";

        BatchAnalysisReport historicalReport = new BatchAnalysisReport();
        historicalReport.setBatch(batch);
        historicalReport.setBatchNumber(batch.getBatchNumber());
        historicalReport.setClientRequestId("REQ-HISTORICAL-8");
        historicalReport.setAnalysisMode(BatchAnalysisMode.FOCUSED);
        historicalReport.setFocusAreas(List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD));
        historicalReport.setStatus(com.pharmatrack.ai.entity.BatchAnalysisReportStatus.COMPLETED);
        historicalReport.setModel("openai/gpt-oss-20b");
        historicalReport.setReportMarkdown(legacyMarkdown);
        historicalReport.setStructuredAnalysisJson(legacyJson);

        // Call the service mapper for existing reports
        java.lang.reflect.Method mapMethod = BatchAnalysisReportService.class.getDeclaredMethod("mapExistingReportToResponseDto", BatchAnalysisReport.class);
        mapMethod.setAccessible(true);
        BatchAnalysisReportResponseDto dto = (BatchAnalysisReportResponseDto) mapMethod.invoke(service, historicalReport);

        // 1. Report Markdown must be returned VERBATIM without read-time rewriting
        assertThat(dto.getReportMarkdown()).isEqualTo(legacyMarkdown);
        assertThat(dto.getReportMarkdown()).contains("Unrecorded / In progress");
        assertThat(dto.getReportMarkdown()).contains("Actual Yield: Not recorded");
        assertThat(dto.getReportMarkdown()).contains("Initiate manufacturing process to capture actual yield data.");

        // 2. Structured Analysis sections must be returned VERBATIM without read-time rewriting
        assertThat(dto.getStructuredAnalysis()).isNotNull();
        assertThat(dto.getStructuredAnalysis().getExecutiveSummary())
                .isEqualTo("All manufacturing yield metrics are unrecorded due to planning stage.");
        assertThat(dto.getStructuredAnalysis().getPlannedVsActualMetrics())
                .isEqualTo("Actual Yield: Not recorded (manufacturing not yet started)");
        assertThat(dto.getStructuredAnalysis().getFollowUpRecommendations())
                .contains("Initiate manufacturing process to capture actual yield data.");
    }

    @Test
    @DisplayName("Phase 4F.4.3.2 (1): PLANNED report (FULL & FOCUSED) final Markdown string has zero missing/unrecorded yield wording and no gap in Executive Summary")
    void phase4F432_plannedReport_fullAndFocused_noContradictoryYieldWordingInFinalMarkdown() {
        Batch batch = new Batch();
        batch.setId(12L);
        batch.setBatchNumber("BAT-2026-0010-01");
        batch.setStatus(BatchStatus.PLANNED);
        batch.setQaStatus(BatchQualityStatus.PENDING_QA);

        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(12L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setOrderNumber("MO-2026-0010");
        dossier.setProductName("Amoxicillin Capsules");
        dossier.setProductCode("AMOX-250");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        dossier.setBatchSize(new BigDecimal("250.0000"));
        dossier.setBatchUnit("kg");
        dossier.setActualYield(null);

        // Report #9 style raw LLM output with contradictory yield assertions
        String report9RawJson = "{\n" +
                "  \"executiveSummary\": \"Batch BAT-2026-0010-01 is currently in PLANNED status with no recorded production start or completion times. Verified MES records show that actual yield, yield percentage, and yield variance are not yet captured; therefore, no quantitative yield assessment can be performed at this time. The absence of yield data is a noted gap in the current batch execution records.\",\n" +
                "  \"batchStatusAndProgress\": \"Batch status is PLANNED. Execution steps are not yet started.\",\n" +
                "  \"plannedVsActualMetrics\": \"Yield Percentage: Not completed / In progress (actual yield not recorded)\\nYield Variance: Not completed / In progress\\nActual Yield: Unrecorded\",\n" +
                "  \"materialTraceability\": \"Material allocations are verified.\",\n" +
                "  \"ebrStepEvaluation\": \"0 of 8 steps recorded. Execution has not commenced.\",\n" +
                "  \"qcInspectionAnalysis\": \"0 QC checks recorded. Testing not yet expected.\",\n" +
                "  \"qaDispositionStatus\": \"Authoritative QA Disposition: PENDING_QA (Not Released).\",\n" +
                "  \"deviationsAndCapa\": \"No linked deviations recorded.\",\n" +
                "  \"chronologicalAuditAnalysis\": \"Audit trail shows batch creation.\",\n" +
                "  \"missingRecords\": \"No documentation gaps identified for the current lifecycle stage.\",\n" +
                "  \"riskObservations\": \"Zero deviations recorded; compliance risk conclusion cannot be established from deviation records alone.\",\n" +
                "  \"followUpRecommendations\": \"1. Update MES to record actual yield weight upon batch closure, ensuring that yield percentage and variance are automatically computed.\\n2. Review SOPs for yield calculation and confirm that all relevant equipment and processes are calibrated to provide accurate mass measurements.\\n3. Assign responsibility to the Production Supervisor to verify that yield data is entered into the MES within 24 hours of batch completion.\"\n" +
                "}";

        // 1. Test FOCUSED mode (Manufacturing Yield)
        BatchAnalysisSectionsDto focusedSections = service.parseAndValidateSections(
                report9RawJson, dossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD)
        );
        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(dossier);

        String focusedMarkdown = service.renderMarkdownReport(
                batch, dossier, metrics, focusedSections, "openai/gpt-oss-20b",
                BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD)
        );

        // Assert Executive Summary in FOCUSED Markdown
        assertThat(focusedMarkdown)
                .contains("Physical manufacturing operations have not commenced")
                .contains("not yet expected")
                .doesNotContain("noted gap")
                .doesNotContain("absence of yield data")
                .doesNotContain("not yet captured");

        // Assert Section 2 (Planned vs Actual Production Metrics) in FOCUSED Markdown
        assertThat(focusedMarkdown)
                .contains("- **Planned Batch Size:** 250.0000 kg")
                .contains("- **Actual Yield:** Not yet expected")
                .contains("- **Yield Percentage:** Not yet expected")
                .contains("- **Yield Variance:** Not yet expected")
                .doesNotContain("Unrecorded")
                .doesNotContain("In progress")
                .doesNotContain("Not completed");

        // Assert Recommendations in FOCUSED Markdown
        assertThat(focusedMarkdown)
                .contains("No immediate yield-related corrective action is required before manufacturing begins")
                .doesNotContain("Review SOPs for yield calculation")
                .doesNotContain("calibrated to provide accurate mass measurements")
                .doesNotContain("within 24 hours of batch completion")
                .doesNotContain("Update MES to record actual yield weight");

        // Exactly one metrics block
        int firstIdx = focusedMarkdown.indexOf("- **Planned Batch Size:**");
        int lastIdx = focusedMarkdown.lastIndexOf("- **Planned Batch Size:**");
        assertThat(firstIdx).isNotEqualTo(-1);
        assertThat(firstIdx).isEqualTo(lastIdx);

        // 2. Test FULL mode
        BatchAnalysisSectionsDto fullSections = service.parseAndValidateSections(
                report9RawJson, dossier, BatchAnalysisMode.FULL, null
        );

        String fullMarkdown = service.renderMarkdownReport(
                batch, dossier, metrics, fullSections, "openai/gpt-oss-20b",
                BatchAnalysisMode.FULL, null
        );

        assertThat(fullMarkdown)
                .contains("- **Planned Batch Size:** 250.0000 kg")
                .contains("- **Actual Yield:** Not yet expected")
                .contains("- **Yield Percentage:** Not yet expected")
                .contains("- **Yield Variance:** Not yet expected")
                .doesNotContain("noted gap in the current batch execution records")
                .doesNotContain("Unrecorded / In progress");

        int fullFirst = fullMarkdown.indexOf("- **Planned Batch Size:**");
        int fullLast = fullMarkdown.lastIndexOf("- **Planned Batch Size:**");
        assertThat(fullFirst).isNotEqualTo(-1);
        assertThat(fullFirst).isEqualTo(fullLast);
    }

    @Test
    @DisplayName("Phase 4F.4.3.2 (2): IN_PROGRESS report final Markdown string has lifecycle-appropriate yield wording")
    void phase4F432_inProgressReport_lifecycleAppropriateWordingInFinalMarkdown() {
        Batch batch = new Batch();
        batch.setBatchNumber("BAT-2026-0020-01");
        batch.setStatus(BatchStatus.IN_PROGRESS);

        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-2026-0020-01");
        dossier.setBatchStatus(BatchStatus.IN_PROGRESS);
        dossier.setBatchSize(new BigDecimal("500.00"));
        dossier.setBatchUnit("kg");
        dossier.setActualYield(null);

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(dossier);
        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto();
        sections.setExecutiveSummary("Manufacturing is underway on the plant floor.");
        sections.setPlannedVsActualMetrics(service.generateDeterministicPlannedVsActualMetrics(dossier, metrics));
        sections.setFollowUpRecommendations("1. Monitor granulator temperature.");

        String markdown = service.renderMarkdownReport(
                batch, dossier, metrics, sections, "test-model", BatchAnalysisMode.FULL, null
        );

        assertThat(markdown)
                .contains("- **Planned Batch Size:** 500.00 kg")
                .contains("- **Actual Yield:** In progress")
                .contains("- **Yield Percentage:** Not yet available until completion")
                .contains("- **Yield Variance:** Not yet available until completion")
                .contains("Manufacturing execution is currently in progress")
                .doesNotContain("Missing record")
                .doesNotContain("Not yet expected");
    }

    @Test
    @DisplayName("Phase 4F.4.3.2 (3): COMPLETED with absent yield explicitly identifies genuinely missing record in final Markdown")
    void phase4F432_completedWithAbsentYield_genuinelyMissingInFinalMarkdown() {
        Batch batch = new Batch();
        batch.setBatchNumber("BAT-2026-0030-01");
        batch.setStatus(BatchStatus.COMPLETED);

        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-2026-0030-01");
        dossier.setBatchStatus(BatchStatus.COMPLETED);
        dossier.setBatchSize(new BigDecimal("1000.00"));
        dossier.setBatchUnit("kg");
        dossier.setActualYield(null);

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(dossier);
        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto();
        sections.setExecutiveSummary("Physical production is complete.");
        sections.setPlannedVsActualMetrics(service.generateDeterministicPlannedVsActualMetrics(dossier, metrics));
        sections.setFollowUpRecommendations("1. Locate physical yield logsheet.");

        String markdown = service.renderMarkdownReport(
                batch, dossier, metrics, sections, "test-model", BatchAnalysisMode.FULL, null
        );

        assertThat(markdown)
                .contains("- **Planned Batch Size:** 1000.00 kg")
                .contains("- **Actual Yield:** Missing record (Unrecorded on completed batch)")
                .contains("- **Yield Percentage:** Unavailable (Actual yield unrecorded)")
                .contains("- **Yield Variance:** Unavailable (Actual yield unrecorded)")
                .contains("Physical manufacturing execution has concluded, but actual yield quantity is not recorded in MES records");
    }

    @Test
    @DisplayName("Phase 4F.4.3.2 (4): COMPLETED with actual yield renders deterministic numerical values in final Markdown")
    void phase4F432_completedWithActualYield_deterministicNumericalValuesInFinalMarkdown() {
        Batch batch = new Batch();
        batch.setBatchNumber("BAT-2026-0040-01");
        batch.setStatus(BatchStatus.COMPLETED);

        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-2026-0040-01");
        dossier.setBatchStatus(BatchStatus.COMPLETED);
        dossier.setBatchSize(new BigDecimal("250.0000"));
        dossier.setActualYield(new BigDecimal("248.5000"));
        dossier.setBatchUnit("kg");

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(dossier);
        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto();
        sections.setExecutiveSummary("Production completed with 99.40% yield.");
        sections.setPlannedVsActualMetrics(service.generateDeterministicPlannedVsActualMetrics(dossier, metrics));
        sections.setFollowUpRecommendations("1. Submit batch dossier for QA review.");

        String markdown = service.renderMarkdownReport(
                batch, dossier, metrics, sections, "test-model", BatchAnalysisMode.FULL, null
        );

        assertThat(markdown)
                .contains("- **Planned Batch Size:** 250.0000 kg")
                .contains("- **Actual Yield:** 248.5000 kg")
                .contains("- **Yield Percentage:** 99.40")
                .contains("- **Yield Variance:** -1.5")
                .contains("Physical manufacturing execution has concluded. Operational yield metrics reflect recorded MES execution data.")
                .doesNotContain("Missing record")
                .doesNotContain("Not yet expected");
    }

    @Test
    @DisplayName("Phase 4F.4.3.2 (5): CANCELLED batch renders yield metrics as not applicable in final Markdown")
    void phase4F432_cancelledReport_yieldNotApplicableInFinalMarkdown() {
        Batch batch = new Batch();
        batch.setBatchNumber("BAT-2026-0050-01");
        batch.setStatus(BatchStatus.CANCELLED);

        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-2026-0050-01");
        dossier.setBatchStatus(BatchStatus.CANCELLED);
        dossier.setBatchSize(new BigDecimal("300.00"));
        dossier.setBatchUnit("kg");
        dossier.setActualYield(null);

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(dossier);
        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto();
        sections.setExecutiveSummary("Batch was cancelled prior to execution.");
        sections.setPlannedVsActualMetrics(service.generateDeterministicPlannedVsActualMetrics(dossier, metrics));
        sections.setFollowUpRecommendations("1. Confirm material de-allocation.");

        String markdown = service.renderMarkdownReport(
                batch, dossier, metrics, sections, "test-model", BatchAnalysisMode.FULL, null
        );

        assertThat(markdown)
                .contains("- **Planned Batch Size:** 300.00 kg")
                .contains("- **Actual Yield:** Not applicable")
                .contains("- **Yield Percentage:** Not applicable")
                .contains("- **Yield Variance:** Not applicable")
                .contains("Batch execution was cancelled prior to completion. Standard yield reconciliation and variance calculations are not applicable.")
                .doesNotContain("Missing record");
    }

    // =========================================================================
    // Phase 4F.4.5 Tests — Lifecycle Narrative Consistency for Section 2, QA, & Recommendations
    // =========================================================================

    @Test
    @DisplayName("Phase 4F.4.5 (1): PLANNED batch Section 2 clearly states manufacturing has not started and end time is not yet expected")
    void phase4F45_plannedBatch_section2_startAndEndTimestampsNotYetExpected() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setOrderNumber("MO-2026-0010");
        plannedDossier.setOrderStatus(OrderStatus.RELEASED);
        plannedDossier.setBatchSize(new BigDecimal("250.0000"));
        plannedDossier.setBatchUnit("kg");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);
        plannedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        plannedDossier.setStartTime(null);
        plannedDossier.setEndTime(null);

        String rawSection2 = "Batch Status: PLANNED. Parent Manufacturing Order: MO-2026-0010 (Status: RELEASED). " +
                "Batch Start Time: Not started (timestamp pending). Batch End Time: In progress / Not completed (no completion timestamp).";

        String normalized = BatchAnalysisReportService.normalizeBatchStatusAndProgress(rawSection2, plannedDossier);

        assertThat(normalized)
                .contains("Batch Status: PLANNED")
                .contains("Batch Start Time: Manufacturing has not started; timestamp is not yet expected")
                .contains("Batch End Time: Completion has not occurred; end timestamp is not yet expected")
                .doesNotContain("In progress / Not completed")
                .doesNotContain("timestamp pending");
    }

    @Test
    @DisplayName("Phase 4F.4.5 (2): IN_PROGRESS batch Section 2 normalizes end time without contradictory 'In progress / Not completed'")
    void phase4F45_inProgressBatch_section2_normalizesEndTimeCleanly() {
        BatchTraceabilityDossierDto inProgressDossier = new BatchTraceabilityDossierDto();
        inProgressDossier.setBatchNumber("BAT-2026-0015-01");
        inProgressDossier.setBatchStatus(BatchStatus.IN_PROGRESS);

        String rawSection2 = "Batch Status: IN_PROGRESS. Batch Start Time: 2026-11-16T10:00:00. Batch End Time: In progress / Not completed (no completion timestamp).";
        String normalized = BatchAnalysisReportService.normalizeBatchStatusAndProgress(rawSection2, inProgressDossier);

        assertThat(normalized)
                .contains("Batch Status: IN_PROGRESS")
                .contains("Batch End Time: In progress (completion has not occurred; end timestamp not yet expected)")
                .doesNotContain("In progress / Not completed");
    }

    @Test
    @DisplayName("Phase 4F.4.5 (3): QA Disposition wording for PLANNED batch preserves PENDING_QA without claiming production completed or review due")
    void phase4F45_plannedBatch_qaDispositionStatement_and_missingRecords() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);
        plannedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);

        // 1. Section 7 QA Statement
        String qaStatement = service.generateDeterministicQaDispositionStatement(plannedDossier);
        assertThat(qaStatement)
                .contains("Authoritative QA Disposition: PENDING_QA (Not Released)")
                .contains("Batch BAT-2026-0010-01 is in PLANNED status; manufacturing has not started")
                .contains("Available lifecycle data does not establish whether formal QA review is currently due")
                .contains("formal batch release has NOT been granted")
                .doesNotContain("production has completed")
                .doesNotContain("within SLA");

        // 2. Section 10 Missing Records
        String missingRecords = service.generateDeterministicMissingRecordsEvaluation(plannedDossier);
        assertThat(missingRecords)
                .contains("- **QA Disposition Status:** PENDING_QA. Manufacturing execution status is PLANNED (manufacturing has not started)")
                .contains("Available lifecycle data does not establish whether formal QA review is currently due")
                .doesNotContain("following production completion")
                .doesNotContain("within SLA");
    }

    @Test
    @DisplayName("Phase 4F.4.5 (4): PLANNED batch recommendations replace premature QA reviews with lifecycle-appropriate guidance")
    void phase4F45_plannedBatch_recommendations_normalizePrematureQaReviews() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);

        String rawRecs = "1. Verify the recipe's QC requirements.\n" +
                "2. Ensure all required raw material lots are available.\n" +
                "3. Initialize the electronic Batch Record.\n" +
                "4. Await formal QA review and record the disposition outcome once the QA team completes the assessment.";

        String normalized = BatchAnalysisReportService.normalizeFollowUpRecommendations(
                rawRecs, plannedDossier, BatchAnalysisMode.FULL, null
        );

        assertThat(normalized)
                .contains("1. Verify the recipe's QC requirements.")
                .contains("2. Ensure all required raw material lots are available.")
                .contains("3. Initialize the electronic Batch Record.")
                .contains("Batch is in PLANNED status (manufacturing has not started); available lifecycle data does not establish whether formal QA review is currently due, and formal batch release has NOT been granted.")
                .doesNotContain("Await formal QA review and record the disposition outcome");
    }

    @Test
    @DisplayName("Phase 4F.4.5 (5): Executive Summary for PLANNED batch sanitizes premature QA review assertions")
    void phase4F45_plannedBatch_executiveSummary_sanitizesPrematureQaReview() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);

        String rawExec = "Batch BAT-2026-0010-01 is in the PLANNED stage with no manufacturing activity initiated. " +
                "The batch remains pending QA review, awaiting formal assessment before release. " +
                "Physical manufacturing operations have not commenced.";

        String normalized = BatchAnalysisReportService.normalizeExecutiveSummary(rawExec, plannedDossier);

        assertThat(normalized)
                .contains("Batch BAT-2026-0010-01 is in the PLANNED stage with no manufacturing activity initiated.")
                .contains("Authoritative QA status is PENDING_QA; manufacturing has not started, and available lifecycle data does not establish whether formal QA review is currently due.")
                .contains("Physical manufacturing operations have not commenced")
                .doesNotContain("awaiting formal assessment before release");
    }

    @Test
    @DisplayName("Phase 4F.4.5 (6): Full Report rendering for PLANNED batch renders consistent Section 2, Section 7, and recommendations")
    void phase4F45_fullReport_rendersConsistentLifecycleNarrative() {
        Batch batch = new Batch();
        batch.setBatchNumber("BAT-2026-0010-01");
        batch.setStatus(BatchStatus.PLANNED);
        batch.setQaStatus(BatchQualityStatus.PENDING_QA);

        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setOrderNumber("MO-2026-0010");
        plannedDossier.setOrderStatus(OrderStatus.RELEASED);
        plannedDossier.setBatchSize(new BigDecimal("250.0000"));
        plannedDossier.setBatchUnit("kg");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);
        plannedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(plannedDossier);

        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto();
        sections.setExecutiveSummary("Batch BAT-2026-0010-01 is in PLANNED stage. Physical manufacturing operations have not commenced. Actual yield, yield percentage, and yield variance are not yet expected at this lifecycle stage.");
        sections.setBatchStatusAndProgress("Batch Status: PLANNED. Batch Start Time: Not started. Batch End Time: In progress / Not completed.");
        sections.setPlannedVsActualMetrics(service.generateDeterministicPlannedVsActualMetrics(plannedDossier, metrics));
        sections.setMaterialTraceability("Raw material lots allocated.");
        sections.setEbrStepEvaluation("0 steps executed.");
        sections.setQcInspectionAnalysis("0 QC checks recorded.");
        sections.setQaDispositionStatus(service.generateDeterministicQaDispositionStatement(plannedDossier));
        sections.setDeviationsAndCapa("0 deviations recorded.");
        sections.setChronologicalAuditAnalysis("Audit history verified.");
        sections.setMissingRecords(service.generateDeterministicMissingRecordsEvaluation(plannedDossier));
        sections.setRiskObservations("Standard operational risk.");
        sections.setFollowUpRecommendations("1. Complete QA review before release.");

        // Apply normalizations
        sections.setBatchStatusAndProgress(BatchAnalysisReportService.normalizeBatchStatusAndProgress(sections.getBatchStatusAndProgress(), plannedDossier));
        sections.setFollowUpRecommendations(BatchAnalysisReportService.normalizeFollowUpRecommendations(sections.getFollowUpRecommendations(), plannedDossier, BatchAnalysisMode.FULL, null));

        String markdown = service.renderMarkdownReport(
                batch, plannedDossier, metrics, sections, "test-model", BatchAnalysisMode.FULL, null
        );

        assertThat(markdown)
                .contains("## 2. Batch Status and Manufacturing Progress")
                .contains("Batch Start Time: Manufacturing has not started; timestamp is not yet expected")
                .contains("Batch End Time: Completion has not occurred; end timestamp is not yet expected")
                .doesNotContain("In progress / Not completed")
                .contains("## 7. QA Disposition Status")
                .contains("Authoritative QA Disposition: PENDING_QA (Not Released)")
                .contains("Batch is in PLANNED status (manufacturing has not started); available lifecycle data does not establish whether formal QA review is currently due, and formal batch release has NOT been granted.")
                .doesNotContain("Complete QA review before release");
    }

    @Test
    @DisplayName("Phase 4F.4.5.1: PLANNED batch with PENDING_QA maintains precise non-speculative QA wording across all sections")
    void phase4F451_plannedBatch_qaPrecisionWording_acrossAllNarratives() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-QA-PRECISION-01");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);
        plannedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        plannedDossier.setDeviations(List.of());

        // 1. Section 7 QA Statement
        String qaStatement = service.generateDeterministicQaDispositionStatement(plannedDossier);
        assertThat(qaStatement)
                .isEqualTo("Authoritative QA Disposition: PENDING_QA (Not Released). Batch BAT-QA-PRECISION-01 is in PLANNED status; manufacturing has not started. Available lifecycle data does not establish whether formal QA review is currently due, but formal batch release has NOT been granted.")
                .contains("formal batch release has NOT been granted")
                .doesNotContain("is not currently due or overdue")
                .doesNotContain("not applicable")
                .doesNotContain("following production completion")
                .doesNotContain("within SLA");

        // 2. Section 10 Missing Records
        String missingRecords = service.generateDeterministicMissingRecordsEvaluation(plannedDossier);
        assertThat(missingRecords)
                .contains("- **QA Disposition Status:** PENDING_QA. Manufacturing execution status is PLANNED (manufacturing has not started). Available lifecycle data does not establish whether formal QA review is currently due.")
                .doesNotContain("is not currently due or overdue")
                .doesNotContain("not applicable")
                .doesNotContain("within SLA");

        // 3. Executive Summary
        String rawExec = "Batch BAT-QA-PRECISION-01 is in PLANNED status. The batch remains pending QA review.";
        String execSummary = BatchAnalysisReportService.normalizeExecutiveSummary(rawExec, plannedDossier);
        assertThat(execSummary)
                .contains("Authoritative QA status is PENDING_QA; manufacturing has not started, and available lifecycle data does not establish whether formal QA review is currently due.")
                .doesNotContain("is not currently due while")
                .doesNotContain("is not currently due or overdue");

        // 4. Follow-up Recommendations
        String rawRecs = "1. Initiate manufacturing.\n2. Await formal QA review and record disposition.";
        String recs = BatchAnalysisReportService.normalizeFollowUpRecommendations(rawRecs, plannedDossier, BatchAnalysisMode.FULL, null);
        assertThat(recs)
                .contains("Batch is in PLANNED status (manufacturing has not started); available lifecycle data does not establish whether formal QA review is currently due, and formal batch release has NOT been granted.")
                .doesNotContain("Standard Quality Assurance disposition review occurs")
                .doesNotContain("no premature QA disposition action is required");
    }

    // =========================================================================
    // Phase 4F.4.6 Tests — Final QC Formatting & Evidence-Aware Recommendations
    // =========================================================================

    @Test
    @DisplayName("Phase 4F.4.6 (1): Zero QC checks renders deterministic statement starting with 'Total recorded QC checks: 0.'")
    void phase4F46_zeroQcChecks_rendersDeterministicStatement() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);
        plannedDossier.setQualityChecks(List.of());

        String qcSection = BatchAnalysisReportService.normalizeQcInspectionAnalysis(
                "Quality Control: Total recorded QC checks –", plannedDossier
        );

        assertThat(qcSection)
                .startsWith("Total recorded QC checks: 0.")
                .contains("No quality control inspections have been performed or recorded for this batch in available MES records.")
                .contains("For a batch in PLANNED status, manufacturing operations and in-process/finished testing have not commenced.")
                .contains("Master recipe QC test specifications are unavailable in the records examined; QC applicability cannot be deterministically verified from available records alone")
                .contains("and QC testing must not be inferred as exempt or unnecessary.")
                .doesNotContain("Total recorded QC checks –");
    }

    @Test
    @DisplayName("Phase 4F.4.6 (2): Present QC checks normalizes malformed dash punctuation to colon")
    void phase4F46_qcChecksPresent_normalizesMalformedPunctuation() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchNumber("BAT-QC-001");
        dossier.setBatchStatus(BatchStatus.IN_PROGRESS);

        QualityCheckResponseDto check = new QualityCheckResponseDto();
        check.setId(1L);
        check.setTestName("Assay");
        check.setResult(QualityCheckResult.PASS);
        dossier.setQualityChecks(List.of(check));

        String rawQc = "Quality Control: Total recorded QC checks – 1. 1 inspection passed.";
        String normalized = BatchAnalysisReportService.normalizeQcInspectionAnalysis(rawQc, dossier);

        assertThat(normalized)
                .contains("Total recorded QC checks: 1.")
                .doesNotContain("Total recorded QC checks –");
    }

    @Test
    @DisplayName("Phase 4F.4.6 (3): Concrete Report #14 test case suppresses fulfilled materials, configured recipe, and invented QC/SOP")
    void phase4F46_concreteReport14_suppressesFulfilledMaterialsAndConfiguredRecipe() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(12L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setOrderNumber("MO-2026-0010");
        dossier.setOrderStatus(OrderStatus.RELEASED);
        dossier.setRecipeCode("RCP-AMOX-250-V4");
        dossier.setRecipeVersion(4);
        dossier.setQualityChecks(List.of()); // 0 QC checks

        // 1 Material requirement fulfilled by batch-attributed MATERIAL_ISSUE
        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRawMaterialCode("RM-DEMO");
        req.setRawMaterialName("Demo Raw Material");
        req.setRecipeMaterialName("Demo Active");
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setIssuedQuantity(new BigDecimal("100.0000"));
        req.setRemainingRequiredQuantity(BigDecimal.ZERO);
        req.setFulfilled(true);
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setBatchId(12L);
        movement.setMaterialCode("RM-DEMO");
        movement.setLotNumber("LOT-DEMO-001");
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        dossier.setMaterialMovements(List.of(movement));

        // Exact 4 recommendations from Report #14
        String report14Recs = "1. Verify and approve the recipe RCP-AMOX-250-V4 for batch BAT-2026-0010-01.\n" +
                "2. Confirm material availability and issue the RM-DEMO lot (LOT-DEMO-001) to the batch per BOM.\n" +
                "3. Initiate batch execution and start the eBR steps according to SOP.\n" +
                "4. Schedule and perform the required QC inspections as defined by the recipe before release.";

        String normalized = BatchAnalysisReportService.normalizeFollowUpRecommendations(
                report14Recs, dossier, BatchAnalysisMode.FULL, null
        );

        // 1. Must NOT recommend verifying/approving configured recipe
        assertThat(normalized).doesNotContain("Verify and approve the recipe RCP-AMOX-250-V4");

        // 2. Must NOT recommend issuing already-fulfilled RM-DEMO / LOT-DEMO-001
        assertThat(normalized).doesNotContain("issue the RM-DEMO lot");
        assertThat(normalized).doesNotContain("Confirm material availability");

        // 3. Must NOT invent mandatory QC schedule when 0 QC checks are configured
        assertThat(normalized).doesNotContain("Schedule and perform the required QC inspections");

        // 4. Must NOT invent SOP requirement
        assertThat(normalized).doesNotContain("according to SOP");

        // 5. Must retain valid next-step guidance
        assertThat(normalized).contains("1. Initiate batch execution and start the eBR steps.");
    }

    @Test
    @DisplayName("Phase 4F.4.6 (4): Unfulfilled material is NOT suppressed from recommendations")
    void phase4F46_unfulfilledMaterial_retainsMaterialIssueRecommendation() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(15L);
        dossier.setBatchNumber("BAT-UNFULFILLED");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setRecipeCode("RCP-TEST");

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRawMaterialCode("RM-ACTIVE");
        req.setRawMaterialName("Active Ingredient");
        req.setTotalRequiredQuantity(new BigDecimal("50.0000"));
        req.setIssuedQuantity(BigDecimal.ZERO);
        req.setRemainingRequiredQuantity(new BigDecimal("50.0000"));
        req.setFulfilled(false); // NOT fulfilled
        dossier.setMaterialRequirements(List.of(req));
        dossier.setMaterialMovements(List.of());

        String rawRecs = "1. Confirm material availability and issue the RM-ACTIVE lot to the batch per BOM.\n" +
                "2. Initiate batch execution and start the eBR steps.";

        String normalized = BatchAnalysisReportService.normalizeFollowUpRecommendations(
                rawRecs, dossier, BatchAnalysisMode.FULL, null
        );

        assertThat(normalized).contains("Confirm material availability and issue the RM-ACTIVE lot to the batch per BOM.");
        assertThat(normalized).contains("Initiate batch execution and start the eBR steps.");
    }

    @Test
    @DisplayName("Report #15 Fix (1): Deterministic count rendering in Sections 5, 6, and 8 for zero counts")
    void report15_deterministicCountRendering_sections5_6_8_structuredAndMarkdown() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchId(10L);
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);
        plannedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        plannedDossier.setRecipeCode("RCP-AMOX-250-V4");
        plannedDossier.setExecutionSteps(List.of());
        plannedDossier.setQualityChecks(List.of());
        plannedDossier.setDeviations(List.of());

        String rawJson = """
                {
                  "executiveSummary": "Planned batch summary.",
                  "batchStatusAndProgress": "PLANNED batch progress.",
                  "plannedVsActualMetrics": "Yield metrics.",
                  "materialTraceability": "Material traceability.",
                  "ebrStepEvaluation": "Arbitrary LLM narrative about steps without counts.",
                  "qcInspectionAnalysis": "Total recorded QC checks – arbitrary text.",
                  "qaDispositionStatus": "PENDING_QA disposition.",
                  "deviationsAndCapa": "Arbitrary deviation commentary.",
                  "chronologicalAuditAnalysis": "Audit logs.",
                  "missingRecords": "Missing records.",
                  "riskObservations": "Risk observations.",
                  "followUpRecommendations": "1. Execution step."
                }
                """;

        BatchAnalysisSectionsDto sections = service.parseAndValidateSections(
                rawJson, plannedDossier, BatchAnalysisMode.FULL, null
        );

        // 1. Structured sections must contain deterministic count statements
        assertThat(sections.getEbrStepEvaluation())
                .startsWith("Total Execution Steps recorded: 0.")
                .contains("No electronic batch record (eBR) execution steps have been initialized");
        assertThat(sections.getQcInspectionAnalysis())
                .startsWith("Total recorded QC checks: 0.")
                .contains("No quality control inspections have been performed or recorded");
        assertThat(sections.getDeviationsAndCapa())
                .startsWith("Total linked deviations: 0.")
                .contains("Zero deviations recorded for this batch in available MES records");

        // 2. Rendered Markdown must contain the matching deterministic counts
        Batch batch = new Batch();
        batch.setId(10L);
        batch.setBatchNumber("BAT-2026-0010-01");
        batch.setStatus(BatchStatus.PLANNED);
        batch.setQaStatus(BatchQualityStatus.PENDING_QA);

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(plannedDossier);
        String markdown = service.renderMarkdownReport(batch, plannedDossier, metrics, sections, "test-model");

        assertThat(markdown)
                .contains("## 5. Electronic Batch Record (eBR) Execution Analysis\nTotal Execution Steps recorded: 0.")
                .contains("## 6. QC Inspection Analysis\nTotal recorded QC checks: 0.")
                .contains("## 8. Deviations and CAPA Traceability\nTotal linked deviations: 0.");
    }

    @Test
    @DisplayName("Report #15 Fix (2): Concrete Report #15 recommendations suppress redundant recipe, material, QC, and premature QA")
    void report15_concreteRecommendations_suppressesContradictoryAndRetainsActionableNextSteps() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(15L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        dossier.setRecipeCode("RCP-AMOX-250-V4");

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRawMaterialCode("RM-DEMO");
        req.setRawMaterialName("Demo Raw Material");
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setIssuedQuantity(new BigDecimal("100.0000"));
        req.setRemainingRequiredQuantity(BigDecimal.ZERO);
        req.setFulfilled(true);
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setBatchId(15L);
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialCode("RM-DEMO");
        movement.setLotNumber("LOT-DEMO-001");
        dossier.setMaterialMovements(List.of(movement));
        dossier.setQualityChecks(List.of());

        // The exact 5 recommendations from Report #15
        String report15Recs = "1. Verify that recipe RCP-AMOX-250-V4 is correctly linked and fully configured for batch BAT-2026-0010-01.\n" +
                "2. Confirm that the issued material RM-DEMO (LOT-DEMO-001) aligns with BOM requirements and is available for the upcoming manufacturing run.\n" +
                "3. Review the master recipe to confirm that QC inspections are defined and scheduled for this batch prior to manufacturing.\n" +
                "4. Await completion of the pending QA review before initiating manufacturing.\n" +
                "5. When manufacturing commences, monitor eBR step initialization and operator sign-offs to ensure adherence to the configured recipe.";

        String normalized = BatchAnalysisReportService.normalizeFollowUpRecommendations(
                report15Recs, dossier, BatchAnalysisMode.FULL, null
        );

        // 1. Must NOT recommend verifying or linking recipe RCP-AMOX-250-V4
        assertThat(normalized).doesNotContain("Verify that recipe RCP-AMOX-250-V4");

        // 2. Must NOT recommend reissuing or reconfirming availability of fulfilled RM-DEMO / LOT-DEMO-001
        assertThat(normalized).doesNotContain("Confirm that the issued material RM-DEMO");
        assertThat(normalized).doesNotContain("LOT-DEMO-001");

        // 3. Must NOT recommend reviewing master recipe to confirm QC inspections when none configured
        assertThat(normalized).doesNotContain("Review the master recipe to confirm that QC inspections");

        // 4. Must NOT recommend awaiting completion of pending QA review before manufacturing
        assertThat(normalized).doesNotContain("Await completion of the pending QA review before initiating manufacturing");

        // 5. Must retain the actionable next step
        assertThat(normalized).contains("1. When manufacturing commences, monitor eBR step initialization and operator sign-offs to ensure adherence to the configured recipe.");
    }

    @Test
    @DisplayName("Report #15 Fix (3): When all candidate recommendations are filtered, fallback default is assigned")
    void report15_allRecommendationsFiltered_fallsBackToDefaultExecutionStep() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(15L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        dossier.setRecipeCode("RCP-AMOX-250-V4");

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRawMaterialCode("RM-DEMO");
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setIssuedQuantity(new BigDecimal("100.0000"));
        req.setFulfilled(true);
        dossier.setMaterialRequirements(List.of(req));
        dossier.setMaterialMovements(List.of());
        dossier.setQualityChecks(List.of());

        // Only items 1-4 from Report #15 (all 4 should be filtered out)
        String inputRecs = "1. Verify that recipe RCP-AMOX-250-V4 is correctly linked and fully configured for batch BAT-2026-0010-01.\n" +
                "2. Confirm that the issued material RM-DEMO (LOT-DEMO-001) aligns with BOM requirements and is available for the upcoming manufacturing run.\n" +
                "3. Review the master recipe to confirm that QC inspections are defined and scheduled for this batch prior to manufacturing.\n" +
                "4. Await completion of the pending QA review before initiating manufacturing.";

        String normalized = BatchAnalysisReportService.normalizeFollowUpRecommendations(
                inputRecs, dossier, BatchAnalysisMode.FULL, null
        );

        assertThat(normalized).isEmpty();
    }

    @Test
    @DisplayName("Report #16 Fix (1): All four Report #16 recommendations are filtered appropriately with no generic fallback inserted")
    void report16_exactRecommendations_allFourFilteredAndNoGenericFallbackInserted() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(16L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        dossier.setRecipeCode("RCP-AMOX-250-V4");

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRawMaterialCode("RM-DEMO");
        req.setRawMaterialName("Demo Raw Material");
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setIssuedQuantity(new BigDecimal("100.0000"));
        req.setRemainingRequiredQuantity(BigDecimal.ZERO);
        req.setFulfilled(true);
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setBatchId(16L);
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialCode("RM-DEMO");
        movement.setLotNumber("LOT-DEMO-001");
        dossier.setMaterialMovements(List.of(movement));
        dossier.setQualityChecks(List.of());
        dossier.setExecutionSteps(List.of());

        // Exact 4 recommendations from Report #16
        String report16Recs = "1. Verify that the recipe RCP-AMOX-250-V4 is fully configured and linked to batch BAT-2026-0010-01.\n" +
                "2. Confirm the QC inspection schedule defined in the recipe or master data before initiating manufacturing.\n" +
                "3. Ensure all required raw material lots, including RM-DEMO lot LOT-DEMO-001, are available and documented prior to starting batch execution.\n" +
                "4. Plan and schedule eBR execution steps according to the recipe workflow once manufacturing begins.";

        String normalized = BatchAnalysisReportService.normalizeFollowUpRecommendations(
                report16Recs, dossier, BatchAnalysisMode.FULL, null
        );

        // Redundant recipe, fulfilled material, and eBR scheduling must be filtered out
        assertThat(normalized).doesNotContain("Verify that the recipe RCP-AMOX-250-V4");
        assertThat(normalized).doesNotContain("Confirm the QC inspection schedule defined");
        assertThat(normalized).doesNotContain("Ensure all required raw material lots");
        assertThat(normalized).doesNotContain("Plan and schedule eBR execution steps");

        // QC configuration check may remain without implying schedule exists or QC is exempt
        assertThat(normalized).contains("Confirm whether QC testing requirements or inspection specifications are defined for this batch in the recipe or master data prior to manufacturing initiation (QC testing is not exempt).");

        // No unsupported generic fallback must be inserted
        assertThat(normalized).doesNotContain("Initiate batch execution");
    }

    @Test
    @DisplayName("Report #16 Fix (2): QC counts (zero and non-zero) render deterministically in structured JSON and Markdown")
    void report16_qcCountZero_andNonZero_rendersMatchingCountsInStructuredAndMarkdown() {
        // Zero QC checks
        BatchTraceabilityDossierDto zeroDossier = new BatchTraceabilityDossierDto();
        zeroDossier.setBatchId(16L);
        zeroDossier.setBatchNumber("BAT-2026-0010-01");
        zeroDossier.setBatchStatus(BatchStatus.PLANNED);
        zeroDossier.setQualityChecks(List.of());

        String zeroQcNormalized = BatchAnalysisReportService.normalizeQcInspectionAnalysis(
                "Total recorded QC checks – arbitrary text without number", zeroDossier
        );
        assertThat(zeroQcNormalized)
                .startsWith("Total recorded QC checks: 0.")
                .contains("No quality control inspections have been performed or recorded");

        // Non-zero QC checks
        BatchTraceabilityDossierDto nonZeroDossier = new BatchTraceabilityDossierDto();
        nonZeroDossier.setBatchId(17L);
        nonZeroDossier.setBatchNumber("BAT-2026-0011-01");
        nonZeroDossier.setBatchStatus(BatchStatus.IN_PROGRESS);

        QualityCheckResponseDto check1 = new QualityCheckResponseDto();
        check1.setId(101L);
        check1.setTestName("Assay");
        check1.setResult(QualityCheckResult.PASS);

        QualityCheckResponseDto check2 = new QualityCheckResponseDto();
        check2.setId(102L);
        check2.setTestName("Dissolution");
        check2.setResult(QualityCheckResult.PENDING);

        nonZeroDossier.setQualityChecks(List.of(check1, check2));

        String nonZeroQcNormalized = BatchAnalysisReportService.normalizeQcInspectionAnalysis(
                "Quality Control: Total recorded QC checks – In-process testing ongoing.", nonZeroDossier
        );
        assertThat(nonZeroQcNormalized)
                .startsWith("Total recorded QC checks: 2.")
                .contains("In-process testing ongoing.");

        // Verify Markdown rendering contains matching counts
        Batch batchZero = new Batch();
        batchZero.setId(16L);
        batchZero.setBatchNumber("BAT-2026-0010-01");
        batchZero.setStatus(BatchStatus.PLANNED);

        BatchAnalysisSectionsDto sectionsZero = new BatchAnalysisSectionsDto();
        sectionsZero.setQcInspectionAnalysis(zeroQcNormalized);
        sectionsZero.setExecutiveSummary("Summary");
        sectionsZero.setBatchStatusAndProgress("Progress");
        sectionsZero.setPlannedVsActualMetrics("Metrics");
        sectionsZero.setMaterialTraceability("Traceability");
        sectionsZero.setEbrStepEvaluation("Steps");
        sectionsZero.setQaDispositionStatus("QA");
        sectionsZero.setDeviationsAndCapa("Deviations");
        sectionsZero.setChronologicalAuditAnalysis("Audit");
        sectionsZero.setMissingRecords("Missing");
        sectionsZero.setRiskObservations("Risk");
        sectionsZero.setFollowUpRecommendations("");

        String mdZero = service.renderMarkdownReport(batchZero, zeroDossier, new BatchAnalysisMetricsDto(), sectionsZero, "model");
        assertThat(mdZero).contains("## 6. QC Inspection Analysis\nTotal recorded QC checks: 0.");

        Batch batchNonZero = new Batch();
        batchNonZero.setId(17L);
        batchNonZero.setBatchNumber("BAT-2026-0011-01");
        batchNonZero.setStatus(BatchStatus.IN_PROGRESS);

        BatchAnalysisSectionsDto sectionsNonZero = new BatchAnalysisSectionsDto();
        sectionsNonZero.setQcInspectionAnalysis(nonZeroQcNormalized);
        sectionsNonZero.setExecutiveSummary("Summary");
        sectionsNonZero.setBatchStatusAndProgress("Progress");
        sectionsNonZero.setPlannedVsActualMetrics("Metrics");
        sectionsNonZero.setMaterialTraceability("Traceability");
        sectionsNonZero.setEbrStepEvaluation("Steps");
        sectionsNonZero.setQaDispositionStatus("QA");
        sectionsNonZero.setDeviationsAndCapa("Deviations");
        sectionsNonZero.setChronologicalAuditAnalysis("Audit");
        sectionsNonZero.setMissingRecords("Missing");
        sectionsNonZero.setRiskObservations("Risk");
        sectionsNonZero.setFollowUpRecommendations("");

        String mdNonZero = service.renderMarkdownReport(batchNonZero, nonZeroDossier, new BatchAnalysisMetricsDto(), sectionsNonZero, "model");
        assertThat(mdNonZero).contains("## 6. QC Inspection Analysis\nTotal recorded QC checks: 2.");
    }

    @Test
    @DisplayName("Report #15 Fix (4): BatchAnalysisSectionsDto serializes qcInspectionAnalysis without duplicate qualitySummary key")
    void report15_dtoSerialization_includesQcInspectionAnalysisAndExcludesQualitySummary() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        BatchAnalysisSectionsDto dto = new BatchAnalysisSectionsDto();
        dto.setQcInspectionAnalysis("Total recorded QC checks: 0.");

        String json = mapper.writeValueAsString(dto);

        assertThat(json).contains("\"qcInspectionAnalysis\":\"Total recorded QC checks: 0.\"");
        assertThat(json).doesNotContain("\"qualitySummary\"");
    }

    // =========================================================================
    // Report #17 QA Release Hallucination Regression Tests
    // =========================================================================

    @Test
    @DisplayName("Report #17 (1): PLANNED batch with release claim in executiveSummary is normalized to authoritative PENDING_QA and NOT RELEASED")
    void report17_plannedBatch_qaReleaseClaimInExecutiveSummary_isNormalizedToAuthoritativePendingQa() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);
        plannedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        plannedDossier.setOrderNumber("MO-2026-0010");
        plannedDossier.setExecutionSteps(List.of());
        plannedDossier.setQualityChecks(List.of());
        plannedDossier.setDeviations(List.of());

        String jsonWithClaim = """
                {
                  "executiveSummary": "Batch BAT-2026-0010-01 is in PLANNED status. The batch is approved for release under manufacturing order MO-2026-0010.",
                  "batchStatusAndProgress": "Manufacturing has not started.",
                  "plannedVsActualMetrics": "Actual yield is not yet expected.",
                  "materialTraceability": "Material dispensed.",
                  "ebrStepEvaluation": "Execution steps recorded: 0.",
                  "qcInspectionAnalysis": "Total recorded QC checks: 0.",
                  "qaDispositionStatus": "Authoritative QA Disposition: PENDING_QA.",
                  "deviationsAndCapa": "Total linked deviations: 0.",
                  "chronologicalAuditAnalysis": "Audit trail entries: 0.",
                  "missingRecords": "Documentation complete.",
                  "riskObservations": "Zero deviations recorded.",
                  "followUpRecommendations": "1. Verify requirements."
                }
                """;

        BatchAnalysisSectionsDto sections = service.parseAndValidateSections(jsonWithClaim, plannedDossier);
        assertThat(sections).isNotNull();
        assertThat(sections.getExecutiveSummary())
                .contains("Authoritative QA status is PENDING_QA; manufacturing has not started, and available lifecycle data does not establish whether formal QA review is currently due.")
                .contains("Formal batch release has NOT been granted")
                .doesNotContain("approved for release");
    }

    @Test
    @DisplayName("Report #17 (2): COMPLETED unreleased batch with unsupported release claim in executiveSummary is strictly rejected")
    void report17_completedBatch_unsupportedReleaseClaim_isStrictlyRejected() {
        BatchTraceabilityDossierDto completedDossier = new BatchTraceabilityDossierDto();
        completedDossier.setBatchNumber("BAT-2026-0001-01");
        completedDossier.setBatchStatus(BatchStatus.COMPLETED);
        completedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        completedDossier.setExecutionSteps(List.of());
        completedDossier.setQualityChecks(List.of());
        completedDossier.setDeviations(List.of());

        String jsonWithClaim = """
                {
                  "executiveSummary": "The batch has completed production and is approved for release.",
                  "batchStatusAndProgress": "Production completed.",
                  "plannedVsActualMetrics": "Yield is 98.5%.",
                  "materialTraceability": "Material dispensed.",
                  "ebrStepEvaluation": "Execution steps recorded: 1.",
                  "qcInspectionAnalysis": "Total recorded QC checks: 1.",
                  "qaDispositionStatus": "Authoritative QA Disposition: PENDING_QA.",
                  "deviationsAndCapa": "Total linked deviations: 0.",
                  "chronologicalAuditAnalysis": "Audit trail entries: 1.",
                  "missingRecords": "Documentation complete.",
                  "riskObservations": "Zero deviations recorded.",
                  "followUpRecommendations": "1. Verify requirements."
                }
                """;

        assertThatThrownBy(() -> service.parseAndValidateSections(jsonWithClaim, completedDossier))
                .isInstanceOf(BatchAnalysisValidationException.class)
                .hasMessageContaining("unsupported claim of completed QA batch release/approval in section 'executiveSummary'");
    }

    @Test
    @DisplayName("Report #17 (3): PLANNED batch with unsupported release claim in non-normalized section is strictly rejected")
    void report17_plannedBatch_releaseClaimInOtherSection_isStrictlyRejected() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);
        plannedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        plannedDossier.setExecutionSteps(List.of());
        plannedDossier.setQualityChecks(List.of());
        plannedDossier.setDeviations(List.of());

        String jsonWithClaim = """
                {
                  "executiveSummary": "Batch BAT-2026-0010-01 is in PLANNED status.",
                  "batchStatusAndProgress": "Manufacturing has not started.",
                  "plannedVsActualMetrics": "Actual yield is not yet expected.",
                  "materialTraceability": "Raw material was cleared for commercial release and batch release has been granted.",
                  "ebrStepEvaluation": "Execution steps recorded: 0.",
                  "qcInspectionAnalysis": "Total recorded QC checks: 0.",
                  "qaDispositionStatus": "Authoritative QA Disposition: PENDING_QA.",
                  "deviationsAndCapa": "Total linked deviations: 0.",
                  "chronologicalAuditAnalysis": "Audit trail entries: 0.",
                  "missingRecords": "Documentation complete.",
                  "riskObservations": "Zero deviations recorded.",
                  "followUpRecommendations": "1. Verify requirements."
                }
                """;

        assertThatThrownBy(() -> service.parseAndValidateSections(jsonWithClaim, plannedDossier))
                .isInstanceOf(BatchAnalysisValidationException.class)
                .hasMessageContaining("unsupported claim of completed QA batch release/approval in section 'materialTraceability'");
    }

    @Test
    @DisplayName("Report #17 (4): Legitimate negative phrasing 'No formal release has been granted' passes validation")
    void report17_legitimateNegativeStatements_passValidation() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);
        plannedDossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        plannedDossier.setExecutionSteps(List.of());
        plannedDossier.setQualityChecks(List.of());
        plannedDossier.setDeviations(List.of());

        String json = """
                {
                  "executiveSummary": "Batch is in PLANNED status. No formal release has been granted.",
                  "batchStatusAndProgress": "Manufacturing has not started.",
                  "plannedVsActualMetrics": "Actual yield is not yet expected.",
                  "materialTraceability": "Material dispensed.",
                  "ebrStepEvaluation": "Execution steps recorded: 0.",
                  "qcInspectionAnalysis": "Total recorded QC checks: 0.",
                  "qaDispositionStatus": "Authoritative QA Disposition: PENDING_QA.",
                  "deviationsAndCapa": "Total linked deviations: 0.",
                  "chronologicalAuditAnalysis": "Audit trail entries: 0.",
                  "missingRecords": "Documentation complete.",
                  "riskObservations": "Zero deviations recorded.",
                  "followUpRecommendations": "1. Verify requirements."
                }
                """;

        BatchAnalysisSectionsDto parsed = service.parseAndValidateSections(json, plannedDossier);
        assertThat(parsed).isNotNull();
        assertThat(parsed.getExecutiveSummary()).contains("Formal batch release has NOT been granted");
    }

    @Test
    @DisplayName("Report #19 Fix (1): Section 10 QC wording distinguishes 0 checks, unverified applicability, and non-exemption")
    void report19_section10_qcLifecycleConsistency_distinguishesStatesAndAvoidsNotYetExpectedWhenApplicabilityUnknown() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(19L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        dossier.setQualityChecks(List.of()); // 0 QC checks, unconfigured

        String section10 = service.generateDeterministicMissingRecordsEvaluation(dossier);

        // Must distinguish 0 checks, unverified applicability, and not exempt
        assertThat(section10)
                .contains("- **Quality Control (QC):** QC applicability is UNKNOWN because the required configuration is unavailable in the records examined. No QC inspections are recorded.")
                .contains("Available information does not establish whether QC testing is applicable to this batch")
                .contains("QC testing must not be assumed to be exempt or unnecessary (under authoritative QA disposition rules, at least one passing required QC check must be recorded prior to batch release)")
                .doesNotContain("must not be stated as not yet expected")
                // Must NOT claim QC testing is "not yet expected" when applicability is unknown
                .doesNotContain("QC testing is not yet expected");
    }

    @Test
    @DisplayName("Report #19 Fix (2): Section 9 Chronological Audit sanitizes unsupported negative conclusions when audit trail is empty")
    void report19_section9_chronologicalAudit_sanitizesUnsupportedNegativeConclusion() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(19L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setAuditHistory(List.of()); // 0 audit logs in dossier

        String rawAnalysis = "Audit logs for this batch are not available in the provided data. " +
                "No audit events have been recorded. Therefore, no chronological sequence of events can be presented. " +
                "The current persisted state reflects the PLANNED lifecycle stage.";

        String normalized = BatchAnalysisReportService.normalizeChronologicalAuditAnalysis(rawAnalysis, dossier);

        // Must remove unsupported conclusion that no audit events occurred
        assertThat(normalized).doesNotContain("No audit events have been recorded");
        assertThat(normalized).contains("the chronological audit sequence cannot be assessed from available evidence alone");
        assertThat(normalized).contains("The absence of execution audit records in the available data does not establish that no operational events occurred");
        assertThat(normalized).contains("Zero recorded eBR steps alone cannot establish audit completeness");
        assertThat(normalized).doesNotContain("must never be inferred");
        assertThat(normalized).contains("PLANNED lifecycle stage");
    }

    @Test
    @DisplayName("Report #19 Fix (3): Section 12 suppresses fulfilled materials and invented QA timelines, normalizes QC config check")
    void report19_section12_evidenceAwareRecommendations_filtersFulfilledMaterialAndInventedQaTimeline() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(19L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        dossier.setRecipeCode("RCP-AMOX-250-V4");

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRawMaterialCode("RM-DEMO");
        req.setRawMaterialName("Demo Raw Material");
        req.setRecipeMaterialName("Amoxicillin Trihydrate");
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setIssuedQuantity(new BigDecimal("100.0000"));
        req.setRemainingRequiredQuantity(BigDecimal.ZERO);
        req.setFulfilled(true);
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setBatchId(19L);
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialCode("RM-DEMO");
        movement.setLotNumber("LOT-DEMO-001");
        dossier.setMaterialMovements(List.of(movement));
        dossier.setQualityChecks(List.of());
        dossier.setExecutionSteps(List.of());

        // Exact 4 recommendations from Report #19
        String report19Recs = "1. Validate that all required raw material lots, notably RM-DEMO Lot LOT-DEMO-001, are fully issued and traceable before initiating manufacturing.\n" +
                "2. Confirm that the QC testing schedule is defined for this batch in the recipe or master data and plan to execute the tests once production starts.\n" +
                "3. No immediate yield-related corrective action is required before manufacturing begins; standard yield reconciliation will occur upon production completion.\n" +
                "4. Coordinate with QA to clarify the review timeline once manufacturing and QC data become available.";

        String normalized = BatchAnalysisReportService.normalizeFollowUpRecommendations(
                report19Recs, dossier, BatchAnalysisMode.FULL, null
        );

        // 1. Must NOT re-validate fulfilled RM-DEMO / LOT-DEMO-001
        assertThat(normalized).doesNotContain("Validate that all required raw material lots");
        assertThat(normalized).doesNotContain("LOT-DEMO-001");
        assertThat(normalized).doesNotContain("RM-DEMO");

        // 2. QC configuration check may remain without implying schedule exists or QC is exempt
        assertThat(normalized).contains("Confirm whether QC testing requirements or inspection specifications are defined for this batch in the recipe or master data prior to manufacturing initiation (QC testing is not exempt).");
        assertThat(normalized).doesNotContain("plan to execute the tests once production starts");
        assertThat(normalized).doesNotContain("QC testing schedule is defined");

        // 3. Must retain valid yield conclusion
        assertThat(normalized).contains("No immediate yield-related corrective action is required before manufacturing begins");

        // 4. Must NOT invent QA review timeline or deadlines
        assertThat(normalized).doesNotContain("Coordinate with QA to clarify the review timeline");
        assertThat(normalized).doesNotContain("review timeline");
    }

    @Test
    @DisplayName("Report #19 Fix (4): Section 10 documentation gaps summary does not make absolute claim of no gaps when config is unknown")
    void report19_section10_missingRecordsSummary_doesNotClaimNoGapsWhenConfigUnknown() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(19L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        dossier.setQualityChecks(List.of()); // QC configuration unverified / unknown

        String section10 = service.generateDeterministicMissingRecordsEvaluation(dossier);

        // When QC configuration is unknown, summary must NOT make absolute claim of no documentation gaps
        assertThat(section10)
                .contains("No confirmed missing execution records identified for the current lifecycle stage.")
                .contains("Note: Master recipe QC configuration is unavailable in recorded data; QC applicability remains unverified and is classified as UNKNOWN rather than confirmed complete.")
                .doesNotContain("No missing records or documentation gaps identified for the current lifecycle stage.");
    }

    @Test
    @DisplayName("Report #20 Fix (1): Section 10 QC explicitly states applicability is UNKNOWN and avoids 'not yet expected'")
    void report20_section10_qcExplicitlyUnknown_andAvoidsNotYetExpected() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(20L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        dossier.setQualityChecks(List.of());

        String section10 = service.generateDeterministicMissingRecordsEvaluation(dossier);

        assertThat(section10)
                .contains("- **Quality Control (QC):** QC applicability is UNKNOWN because the required configuration is unavailable in the records examined. No QC inspections are recorded.")
                .contains("Available information does not establish whether QC testing is applicable to this batch")
                .contains("QC testing must not be assumed to be exempt or unnecessary (under authoritative QA disposition rules, at least one passing required QC check must be recorded prior to batch release)")
                .doesNotContain("must not be stated as not yet expected")
                .doesNotContain("QC testing is not yet expected for a planned batch");
    }

    @Test
    @DisplayName("Report #20 Fix (2): Section 9 Audit Analysis removes inference of no events from zero eBR steps")
    void report20_section9_auditAnalysis_neverInfersNoEventsFromZeroEbrSteps() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(20L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setAuditHistory(List.of());

        String report20RawAudit = "Audit logs for this batch contain no recorded events. " +
                "As no eBR execution steps have been initialized, there are no step gaps or sequence anomalies to report. " +
                "The available audit data simply reflects the planned status with no execution history.";

        String normalized = BatchAnalysisReportService.normalizeChronologicalAuditAnalysis(report20RawAudit, dossier);

        assertThat(normalized)
                .contains("Audit trail records for this batch are not available in the provided data")
                .contains("the chronological audit sequence cannot be assessed from available evidence alone")
                .contains("The absence of execution audit records in the available data does not establish that no operational events occurred")
                .contains("Zero recorded eBR steps alone cannot establish audit completeness")
                .doesNotContain("must never be inferred")
                .doesNotContain("Audit logs for this batch contain no recorded events")
                .doesNotContain("As no eBR execution steps have been initialized")
                .doesNotContain("no execution history");
    }

    @Test
    @DisplayName("Report #20 Fix (3): Section 12 filters fulfilled materials and 'Schedule the pending QA review'")
    void report20_section12_recommendations_filtersFulfilledMaterialAndSchedulePendingQaReview() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(20L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        dossier.setRecipeCode("RCP-AMOX-250-V4");

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRawMaterialCode("RM-DEMO");
        req.setRawMaterialName("Demo Raw Material");
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setIssuedQuantity(new BigDecimal("100.0000"));
        req.setRemainingRequiredQuantity(BigDecimal.ZERO);
        req.setFulfilled(true);
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setBatchId(20L);
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialCode("RM-DEMO");
        movement.setLotNumber("LOT-DEMO-001");
        dossier.setMaterialMovements(List.of(movement));
        dossier.setQualityChecks(List.of());
        dossier.setExecutionSteps(List.of());

        // Exact 4 recommendations from Report #20
        String report20Recs = "1. Initiate batch manufacturing execution to create the start timestamp and begin eBR step recording.\n" +
                "2. Verify that all required raw materials are issued and that lot numbers match BOM requirements before production starts.\n" +
                "3. Confirm or define QC inspection requirements in the recipe master to ensure compliance with release criteria.\n" +
                "4. Schedule the pending QA review once manufacturing data and QC results become available.";

        String normalized = BatchAnalysisReportService.normalizeFollowUpRecommendations(
                report20Recs, dossier, BatchAnalysisMode.FULL, null
        );

        // Item 1: Valid execution initiation guidance is preserved
        assertThat(normalized).contains("Initiate batch manufacturing execution");

        // Item 2: Redundant material verification is suppressed (material already fulfilled)
        assertThat(normalized).doesNotContain("Verify that all required raw materials are issued");
        assertThat(normalized).doesNotContain("lot numbers match BOM requirements");

        // Item 3: QC configuration check remains without implying schedule exists or QC is exempt
        assertThat(normalized).contains("Confirm or define QC inspection requirements in the recipe master to ensure compliance with release criteria.");
        assertThat(normalized).doesNotContain("QC testing is exempt");

        // Item 4: Invented QA review schedule and prerequisites are suppressed
        assertThat(normalized).doesNotContain("Schedule the pending QA review");
        assertThat(normalized).doesNotContain("once manufacturing data and QC results become available");
    }

    @Test
    @DisplayName("Report #21 Fix (1): Section 9 Audit Analysis summarizes operational logs and rejects negative existence and data omission claims")
    void report21_section9_auditAnalysis_summarizesOperationalLogsAndRejectsNegativeOrOmissionClaims() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(21L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);

        com.pharmatrack.dto.AuditLogResponseDto log1 = new com.pharmatrack.dto.AuditLogResponseDto();
        log1.setAction("BATCH_CREATED");
        log1.setEntityType("BATCH");

        com.pharmatrack.dto.AuditLogResponseDto log2 = new com.pharmatrack.dto.AuditLogResponseDto();
        log2.setAction("MATERIAL_ISSUED");
        log2.setEntityType("INVENTORY");

        com.pharmatrack.dto.AuditLogResponseDto logAi = new com.pharmatrack.dto.AuditLogResponseDto();
        logAi.setAction("AI_BATCH_ANALYSIS_REQUESTED");
        logAi.setEntityType("REPORT");

        dossier.setAuditHistory(List.of(log1, log2, logAi));

        String report21RawAudit = "Audit log events for this batch are not present in the current data set. " +
                "No recorded audit events exist, and no sequence anomalies or step gaps can be identified from the available information. " +
                "The absence of audit entries is a data omission, not an inferred missing execution event.";

        String normalized = BatchAnalysisReportService.normalizeChronologicalAuditAnalysis(report21RawAudit, dossier);

        assertThat(normalized).doesNotContain("No recorded audit events exist");
        assertThat(normalized).doesNotContain("data omission");
        assertThat(normalized).doesNotContain("not present in the current data set");
        assertThat(normalized).contains("Chronological audit records reflect 2 operational event(s) recorded prior to manufacturing (actions: BATCH_CREATED, MATERIAL_ISSUED)");
        assertThat(normalized).contains("Physical manufacturing execution has not started; the chronological audit sequence of manufacturing execution cannot be assessed from available evidence alone");
        assertThat(normalized).contains("The absence of execution audit records in the available data does not establish that no operational events occurred");
        assertThat(normalized).contains("Zero recorded eBR steps alone cannot establish audit completeness");
        assertThat(normalized).doesNotContain("must never be inferred");
        assertThat(normalized).contains("PLANNED lifecycle stage");
    }

    @Test
    @DisplayName("Report #21 Fix (2): Section 10 QC uses factual audit-ready phrasing without leaked prompt instructions")
    void report21_section10_qcWording_factualAuditReadyWithoutInstructionDirectives() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(21L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        dossier.setQualityChecks(List.of());

        String section10 = service.generateDeterministicMissingRecordsEvaluation(dossier);

        // Strict verification of professional phrasing without leaked prompt directives
        assertThat(section10)
                .contains("- **Quality Control (QC):** QC applicability is UNKNOWN because the required configuration is unavailable in the records examined. No QC inspections are recorded. Available information does not establish whether QC testing is applicable to this batch. QC testing must not be assumed to be exempt or unnecessary (under authoritative QA disposition rules, at least one passing required QC check must be recorded prior to batch release).")
                .doesNotContain("must not be stated as not yet expected")
                .doesNotContain("must never be inferred as exempt or unnecessary");
    }

    @Test
    @DisplayName("Report #21 Fix (3): Both structured_analysis_json and report_markdown receive identical normalized Section 9 audit analysis")
    void report21_section9_structuredJsonAndMarkdownConsistency_bothPersistNormalizedContent() throws Exception {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(21L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);

        com.pharmatrack.dto.AuditLogResponseDto log1 = new com.pharmatrack.dto.AuditLogResponseDto();
        log1.setAction("BATCH_CREATED");
        log1.setEntityType("BATCH");

        com.pharmatrack.dto.AuditLogResponseDto log2 = new com.pharmatrack.dto.AuditLogResponseDto();
        log2.setAction("MATERIAL_ISSUED");
        log2.setEntityType("INVENTORY");

        com.pharmatrack.dto.AuditLogResponseDto logAi = new com.pharmatrack.dto.AuditLogResponseDto();
        logAi.setAction("AI_BATCH_ANALYSIS_REQUESTED");
        logAi.setEntityType("REPORT");

        dossier.setAuditHistory(List.of(log1, log2, logAi));

        String rawLlmJson = """
                {
                  "executiveSummary": "Executive summary for planned batch.",
                  "batchStatusAndProgress": "Planned batch awaiting execution.",
                  "plannedVsActualMetrics": "Planned yield evaluation.",
                  "materialTraceability": "Material dispensed.",
                  "ebrStepEvaluation": "eBR steps evaluation.",
                  "qcInspectionAnalysis": "Total recorded QC checks: 0.",
                  "qaDispositionStatus": "PENDING_QA.",
                  "deviationsAndCapa": "Total linked deviations: 0.",
                  "chronologicalAuditAnalysis": "Audit log events for this batch are not present in the current data set. No recorded audit events exist, and no sequence anomalies or step gaps can be identified from the available information. The absence of audit entries is a data omission, not an inferred missing execution event.",
                  "missingRecords": "No missing records.",
                  "riskObservations": "Risk evaluation.",
                  "followUpRecommendations": "1. Initiate batch manufacturing execution."
                }
                """;

        // 1. parseAndValidateSections normalizes sections.setChronologicalAuditAnalysis directly on DTO
        BatchAnalysisSectionsDto sections = service.parseAndValidateSections(rawLlmJson, dossier, BatchAnalysisMode.FULL, null);

        // Verify DTO / structured JSON representation
        String structuredJson = objectMapper.writeValueAsString(sections);
        assertThat(sections.getChronologicalAuditAnalysis())
                .doesNotContain("No recorded audit events exist")
                .doesNotContain("data omission")
                .contains("Chronological audit records reflect 2 operational event(s) recorded prior to manufacturing (actions: BATCH_CREATED, MATERIAL_ISSUED)")
                .contains("Physical manufacturing execution has not started; the chronological audit sequence of manufacturing execution cannot be assessed from available evidence alone");

        assertThat(structuredJson)
                .doesNotContain("No recorded audit events exist")
                .doesNotContain("data omission")
                .contains("Chronological audit records reflect 2 operational event(s) recorded prior to manufacturing (actions: BATCH_CREATED, MATERIAL_ISSUED)");

        // 2. renderMarkdownReport normalizes and renders reportMarkdown
        Batch batch = new Batch();
        batch.setId(21L);
        batch.setBatchNumber("BAT-2026-0010-01");
        batch.setStatus(BatchStatus.PLANNED);
        batch.setBatchSize(new BigDecimal("100.0000"));
        batch.setBatchUnit("KG");

        BatchAnalysisMetricsDto metrics = service.calculateDeterministicMetrics(dossier);
        String markdown = service.renderMarkdownReport(batch, dossier, metrics, sections, "test-model", BatchAnalysisMode.FULL, null);

        // Verify Markdown representation
        assertThat(markdown)
                .doesNotContain("No recorded audit events exist")
                .doesNotContain("data omission")
                .contains("## 9. Chronological Audit Analysis\n" + sections.getChronologicalAuditAnalysis());

        // 3. Strict equality: the content in structured DTO is identical to Section 9 in Markdown
        assertThat(markdown).contains(sections.getChronologicalAuditAnalysis());
    }

    @Test
    @DisplayName("Report #22 Polish (1 & 2): Chronological event ordering derived from timestamps & factual professional wording")
    void report22_section9_chronologicalAudit_ordersEventsByTimestampAscending() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(22L);
        dossier.setBatchNumber("BAT-2026-0010-01");
        dossier.setBatchStatus(BatchStatus.PLANNED);

        // Case A: MATERIAL_ISSUED was added first to list (e.g. desc order from DB), but occurred LATER than BATCH_CREATED
        com.pharmatrack.dto.AuditLogResponseDto laterEvent = new com.pharmatrack.dto.AuditLogResponseDto();
        laterEvent.setId(102L);
        laterEvent.setAction("MATERIAL_ISSUED");
        laterEvent.setEntityType("INVENTORY");
        laterEvent.setOccurredAt(java.time.LocalDateTime.of(2026, 11, 17, 10, 15, 0));

        com.pharmatrack.dto.AuditLogResponseDto earlierEvent = new com.pharmatrack.dto.AuditLogResponseDto();
        earlierEvent.setId(101L);
        earlierEvent.setAction("BATCH_CREATED");
        earlierEvent.setEntityType("BATCH");
        earlierEvent.setOccurredAt(java.time.LocalDateTime.of(2026, 11, 17, 10, 0, 0));

        com.pharmatrack.dto.AuditLogResponseDto metadataEvent = new com.pharmatrack.dto.AuditLogResponseDto();
        metadataEvent.setId(103L);
        metadataEvent.setAction("AI_BATCH_ANALYSIS_REQUESTED");
        metadataEvent.setEntityType("REPORT");
        metadataEvent.setOccurredAt(java.time.LocalDateTime.of(2026, 11, 17, 10, 30, 0));

        // List has laterEvent first: [MATERIAL_ISSUED, BATCH_CREATED, AI_BATCH_ANALYSIS_REQUESTED]
        dossier.setAuditHistory(List.of(laterEvent, earlierEvent, metadataEvent));

        String rawAnalysis = "No recorded audit events exist.";
        String normalized = BatchAnalysisReportService.normalizeChronologicalAuditAnalysis(rawAnalysis, dossier);

        // 1. Must dynamically sort chronologically ascending: BATCH_CREATED before MATERIAL_ISSUED
        assertThat(normalized).contains("(actions: BATCH_CREATED, MATERIAL_ISSUED)");
        assertThat(normalized).doesNotContain("(actions: MATERIAL_ISSUED, BATCH_CREATED)");

        // 2. Metadata event is excluded
        assertThat(normalized).doesNotContain("AI_BATCH_ANALYSIS_REQUESTED");

        // 3. Professional factual wording without leaked instruction directives
        assertThat(normalized).contains("The absence of execution audit records in the available data does not establish that no operational events occurred. Zero recorded eBR steps alone cannot establish audit completeness.");
        assertThat(normalized).doesNotContain("must never be inferred");
        assertThat(normalized).doesNotContain("No recorded audit events exist");

        // Case B: Verify order is derived dynamically from timestamps rather than hardcoded
        com.pharmatrack.dto.AuditLogResponseDto eventA = new com.pharmatrack.dto.AuditLogResponseDto();
        eventA.setId(201L);
        eventA.setAction("STEP_STARTED");
        eventA.setOccurredAt(java.time.LocalDateTime.of(2026, 11, 17, 9, 0, 0));

        com.pharmatrack.dto.AuditLogResponseDto eventB = new com.pharmatrack.dto.AuditLogResponseDto();
        eventB.setId(202L);
        eventB.setAction("BATCH_CREATED");
        eventB.setOccurredAt(java.time.LocalDateTime.of(2026, 11, 17, 9, 30, 0));

        dossier.setAuditHistory(List.of(eventB, eventA));
        String normalizedDynamic = BatchAnalysisReportService.normalizeChronologicalAuditAnalysis(rawAnalysis, dossier);

        // Dynamically orders by timestamp: STEP_STARTED before BATCH_CREATED
        assertThat(normalizedDynamic).contains("(actions: STEP_STARTED, BATCH_CREATED)");
    }

    // =========================================================================
    // Permanent Deletion Tests
    // =========================================================================

    @Test
    @DisplayName("permanentlyDeleteReport: ADMIN succeeds with atomic audit and delete")
    void permanentlyDeleteReport_admin_success() {
        UserAccount admin = new UserAccount("admin_bob", "hash", "Bob Admin", Role.ADMIN, true);
        when(authenticatedActorResolver.hasAnyRole("ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("admin_bob");
        when(userAccountRepository.findByUsernameIgnoreCase("admin_bob")).thenReturn(Optional.of(admin));

        when(batchTraceabilityService.getTraceabilityDossier(10L)).thenReturn(testDossier);

        testBatch.setId(10L);
        BatchAnalysisReport report = new BatchAnalysisReport(
                testBatch,
                admin,
                "req-client-del-1"
        );
        report.setId(100L);
        report.setStatus(BatchAnalysisReportStatus.COMPLETED);

        when(reportRepository.findByIdAndBatchId(100L, 10L)).thenReturn(Optional.of(report));

        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto("Local test cleanup", "PERMANENTLY DELETE");
        service.permanentlyDeleteReport("10", 100L, request);

        // Verify guard was checked
        verify(deletionGuard).verifyPermanentDeletionAllowed();

        // Verify audit trail recorded atomically with "PERMANENT_DELETE"
        verify(auditTrailService).recordAudit(
                eq("BATCH_ANALYSIS_REPORT"),
                eq(100L),
                eq("BAT-2026-0001-01"),
                eq(10L),
                eq("PERMANENT_DELETE"),
                eq("admin_bob"),
                any(),
                eq("Local test cleanup"),
                any(),
                any()
        );

        // Verify report was deleted and flushed
        verify(reportRepository).delete(report);
        verify(reportRepository).flush();
    }

    @Test
    @DisplayName("permanentlyDeleteReport: Non-ADMIN in security context throws AccessDeniedException")
    void permanentlyDeleteReport_nonAdminContext_throwsAccessDenied() {
        when(authenticatedActorResolver.hasAnyRole("ADMIN")).thenReturn(false);

        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto("Reason", "PERMANENTLY DELETE");

        assertThatThrownBy(() -> service.permanentlyDeleteReport("10", 100L, request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("strictly restricted to Administrators");

        verifyNoInteractions(reportRepository);
        verifyNoInteractions(auditTrailService);
    }

    @Test
    @DisplayName("permanentlyDeleteReport: DB User role not ADMIN throws AccessDeniedException")
    void permanentlyDeleteReport_dbUserNotAdmin_throwsAccessDenied() {
        when(authenticatedActorResolver.hasAnyRole("ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("supervisor_alice");
        when(userAccountRepository.findByUsernameIgnoreCase("supervisor_alice")).thenReturn(Optional.of(supervisor));

        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto("Reason", "PERMANENTLY DELETE");

        assertThatThrownBy(() -> service.permanentlyDeleteReport("10", 100L, request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("strictly restricted to Administrators");

        verifyNoInteractions(reportRepository);
        verifyNoInteractions(auditTrailService);
    }

    @Test
    @DisplayName("permanentlyDeleteReport: Rejects deletion if report is in GENERATING status")
    void permanentlyDeleteReport_generatingStatus_throwsConflict() {
        UserAccount admin = new UserAccount("admin_bob", "hash", "Bob Admin", Role.ADMIN, true);
        when(authenticatedActorResolver.hasAnyRole("ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("admin_bob");
        when(userAccountRepository.findByUsernameIgnoreCase("admin_bob")).thenReturn(Optional.of(admin));

        when(batchTraceabilityService.getTraceabilityDossier(10L)).thenReturn(testDossier);

        testBatch.setId(10L);
        BatchAnalysisReport report = new BatchAnalysisReport(
                testBatch,
                admin,
                "req-client-del-1"
        );
        report.setId(100L);
        report.setStatus(BatchAnalysisReportStatus.GENERATING);

        when(reportRepository.findByIdAndBatchId(100L, 10L)).thenReturn(Optional.of(report));

        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto("Local test cleanup", "PERMANENTLY DELETE");

        assertThatThrownBy(() -> service.permanentlyDeleteReport("10", 100L, request))
                .isInstanceOf(BatchAnalysisConflictException.class)
                .hasMessageContaining("Cannot permanently delete a report that is currently in GENERATING status");

        verify(reportRepository, never()).delete(any());
        verifyNoInteractions(auditTrailService);
    }

    @Test
    @DisplayName("permanentlyDeleteReport: Rejects invalid confirmation keyword")
    void permanentlyDeleteReport_invalidKeyword_throwsIllegalArgument() {
        UserAccount admin = new UserAccount("admin_bob", "hash", "Bob Admin", Role.ADMIN, true);
        when(authenticatedActorResolver.hasAnyRole("ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("admin_bob");
        when(userAccountRepository.findByUsernameIgnoreCase("admin_bob")).thenReturn(Optional.of(admin));

        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto("Local cleanup", "DELETE");

        assertThatThrownBy(() -> service.permanentlyDeleteReport("10", 100L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Confirmation keyword must be exactly 'PERMANENTLY DELETE'");

        verifyNoInteractions(reportRepository);
        verifyNoInteractions(auditTrailService);
    }

    @Test
    @DisplayName("permanentlyDeleteReport: Guard throws exception and fails closed without deleting")
    void permanentlyDeleteReport_guardFailsClosed_abortsOperation() {
        UserAccount admin = new UserAccount("admin_bob", "hash", "Bob Admin", Role.ADMIN, true);
        when(authenticatedActorResolver.hasAnyRole("ADMIN")).thenReturn(true);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("admin_bob");
        when(userAccountRepository.findByUsernameIgnoreCase("admin_bob")).thenReturn(Optional.of(admin));

        doThrow(new AccessDeniedException("Permanent deletion is strictly prohibited in production."))
                .when(deletionGuard).verifyPermanentDeletionAllowed();

        PermanentDeleteReportRequestDto request = new PermanentDeleteReportRequestDto("Local cleanup", "PERMANENTLY DELETE");

        assertThatThrownBy(() -> service.permanentlyDeleteReport("10", 100L, request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("strictly prohibited in production");

        verifyNoInteractions(reportRepository);
        verifyNoInteractions(auditTrailService);
    }

    // =========================================================================
    // Regression Tests — Final Batch Analysis Report-Quality Fixes
    // =========================================================================

    @Test
    @DisplayName("Report Quality Fix 1A: Suppresses premature QA recommendations in PLANNED status across natural-language variations")
    void reportQualityFix1A_plannedStatus_suppressesPrematureQaRecommendations() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);

        // Variation 1: Exact phrasing from Report #22
        String rawRecs1 = "1. Initiate batch execution and start the eBR steps.\n" +
                "2. Record process parameters and environmental conditions.\n" +
                "3. Ensure that the QA review team completes the pending QA assessment, documenting any findings, before granting release.";
        String normalized1 = BatchAnalysisReportService.normalizeFollowUpRecommendations(rawRecs1, plannedDossier, BatchAnalysisMode.FULL, null);

        assertThat(normalized1)
                .doesNotContain("Ensure that the QA review team completes the pending QA assessment")
                .doesNotContain("granting release")
                .contains("Initiate batch execution and start the eBR steps.")
                .contains("Record process parameters and environmental conditions.")
                .contains("Batch is in PLANNED status (manufacturing has not started); available lifecycle data does not establish whether formal QA review is currently due, and formal batch release has NOT been granted.");

        // Variation 2: Passive / alternate auxiliary verb forms
        String rawRecs2 = "1. Initiate batch execution.\n" +
                "2. Verify that the QA team completes the pending assessment.\n" +
                "3. Confirm that the QA review team conducts the formal review.\n" +
                "4. Require QA to complete the pending disposition review.\n" +
                "5. Ensure that the pending QA assessment is completed before release.";
        String normalized2 = BatchAnalysisReportService.normalizeFollowUpRecommendations(rawRecs2, plannedDossier, BatchAnalysisMode.FULL, null);

        assertThat(normalized2)
                .doesNotContain("Verify that the QA team completes")
                .doesNotContain("Confirm that the QA review team conducts")
                .doesNotContain("Require QA to complete")
                .doesNotContain("Ensure that the pending QA assessment is completed")
                .contains("Initiate batch execution.")
                .contains("Batch is in PLANNED status (manufacturing has not started)");
    }

    @Test
    @DisplayName("Report Quality Fix 1B: Preserves legitimate QA recommendations for batches at later lifecycle stages")
    void reportQualityFix1B_completedStatus_preservesLegitimateQaRecommendations() {
        BatchTraceabilityDossierDto completedDossier = new BatchTraceabilityDossierDto();
        completedDossier.setBatchNumber("BAT-2026-0009-01");
        completedDossier.setBatchStatus(BatchStatus.COMPLETED);

        String rawRecs = "1. Ensure that the QA review team completes the pending QA assessment, documenting any findings, before granting release.\n" +
                "2. Review final yield reconciliation and file batch record.";
        String normalized = BatchAnalysisReportService.normalizeFollowUpRecommendations(rawRecs, completedDossier, BatchAnalysisMode.FULL, null);

        assertThat(normalized)
                .contains("1. Ensure that the QA review team completes the pending QA assessment, documenting any findings, before granting release.")
                .contains("2. Review final yield reconciliation and file batch record.")
                .doesNotContain("available lifecycle data does not establish whether formal QA review is currently due");
    }

    @Test
    @DisplayName("Report Quality Fix 2: Corrects QC configuration wording in Section 6 and maintains consistency with Section 10")
    void reportQualityFix2_qcConfigurationWording_accurateLimitationCommunication() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);
        plannedDossier.setQualityChecks(List.of());

        // Section 6 validation
        String qcSection = BatchAnalysisReportService.normalizeQcInspectionAnalysis(null, plannedDossier);
        assertThat(qcSection)
                .startsWith("Total recorded QC checks: 0.")
                .contains("Master recipe QC test specifications are unavailable in the records examined; QC applicability cannot be deterministically verified from available records alone")
                .contains("and QC testing must not be inferred as exempt or unnecessary.")
                .doesNotContain("Master recipe QC configuration is not defined in available records");

        // Section 10 validation
        BatchAnalysisReportService service = new BatchAnalysisReportService(null, null, null, null, null, null, null, null, null, null, null, null, null);
        String missingRecords = service.generateDeterministicMissingRecordsEvaluation(plannedDossier);
        assertThat(missingRecords)
                .contains("QC applicability is UNKNOWN because the required configuration is unavailable in the records examined")
                .contains("QC testing must not be assumed to be exempt or unnecessary");
    }

    @Test
    @DisplayName("Report Quality Fix 3: Strips #1, #4, #5 and variations, assigning clean sequential numbers")
    void reportQualityFix3_stripsHashNumberedPrefixes_sequentialNumbering() {
        // Test 3A: Input with #1, #4, #5 (as seen in Report #22)
        String inputA = "#1 Initiate batch execution and start the eBR steps.\n" +
                "#4 Record process parameters and environmental conditions.\n" +
                "#5 Calibrate weighing instruments.";
        String formattedA = BatchAnalysisReportService.formatNumberedRecommendations(inputA);
        assertThat(formattedA)
                .isEqualTo("1. Initiate batch execution and start the eBR steps.\n" +
                        "2. Record process parameters and environmental conditions.\n" +
                        "3. Calibrate weighing instruments.");

        // Test 3B: Variations including # 1, 1. #1, * #4, #5.
        String inputB = "1. #1 Initiate batch execution and start the eBR steps.\n" +
                "* #4 Record process parameters.\n" +
                "# 5. Check environmental controls.";
        String formattedB = BatchAnalysisReportService.formatNumberedRecommendations(inputB);
        assertThat(formattedB)
                .isEqualTo("1. Initiate batch execution and start the eBR steps.\n" +
                        "2. Record process parameters.\n" +
                        "3. Check environmental controls.");

        // Test 3C: Genuine Markdown headings in recommendations are preserved as preambles
        String inputC = "### Recommended Actions\n" +
                "#1 Initiate batch execution and start the eBR steps.\n" +
                "#4 Record process parameters.";
        String formattedC = BatchAnalysisReportService.formatNumberedRecommendations(inputC);
        assertThat(formattedC)
                .isEqualTo("### Recommended Actions\n\n" +
                        "1. Initiate batch execution and start the eBR steps.\n" +
                        "2. Record process parameters.");
    }

    @Test
    @DisplayName("Report Quality Fix 4: Removes unsupported administrative-completeness claim while preserving summary")
    void reportQualityFix4_removesUnsupportedAdministrativeCompletenessClaim() {
        BatchTraceabilityDossierDto plannedDossier = new BatchTraceabilityDossierDto();
        plannedDossier.setBatchNumber("BAT-2026-0010-01");
        plannedDossier.setBatchStatus(BatchStatus.PLANNED);

        // Case 1: Trailing clause in compound sentence (from Report #22)
        String rawSummary1 = "Batch BAT-2026-0010-01 is currently in PLANNED status, with planned production scheduled under manufacturing order MO-2026-0010 for product AMOX-250 (250 mg Capsule) according to recipe RCP-AMOX-250-V4, and all key administrative records are present. Physical manufacturing operations have not commenced.";
        String normalized1 = BatchAnalysisReportService.normalizeExecutiveSummary(rawSummary1, plannedDossier);

        assertThat(normalized1)
                .doesNotContain("all key administrative records are present")
                .contains("Batch BAT-2026-0010-01 is currently in PLANNED status, with planned production scheduled under manufacturing order MO-2026-0010 for product AMOX-250 (250 mg Capsule) according to recipe RCP-AMOX-250-V4.")
                .contains("Physical manufacturing operations have not commenced");

        // Case 2: Standalone sentence
        String rawSummary2 = "Batch BAT-2026-0010-01 is currently in PLANNED status. All key administrative records are present. Physical manufacturing operations have not commenced.";
        String normalized2 = BatchAnalysisReportService.normalizeExecutiveSummary(rawSummary2, plannedDossier);

        assertThat(normalized2)
                .doesNotContain("all key administrative records are present")
                .doesNotContain("All key administrative records are present")
                .contains("Batch BAT-2026-0010-01 is currently in PLANNED status.")
                .contains("Physical manufacturing operations have not commenced");

        // Case 3: Leading clause
        String rawSummary3 = "All key administrative records are present, but physical manufacturing operations have not commenced.";
        String normalized3 = BatchAnalysisReportService.normalizeExecutiveSummary(rawSummary3, plannedDossier);

        assertThat(normalized3)
                .doesNotContain("all key administrative records are present")
                .contains("Physical manufacturing operations have not commenced.");
    }
}
