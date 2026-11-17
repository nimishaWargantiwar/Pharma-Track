package com.pharmatrack.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.dto.BatchAnalysisMetricsDto;
import com.pharmatrack.ai.dto.BatchAnalysisSectionsDto;
import com.pharmatrack.ai.entity.BatchAnalysisFocusArea;
import com.pharmatrack.ai.entity.BatchAnalysisMode;
import com.pharmatrack.ai.exception.BatchAnalysisValidationException;
import com.pharmatrack.dto.AiSourceReferenceDto;
import com.pharmatrack.dto.BatchExecutionStepResponseDto;
import com.pharmatrack.dto.BatchTraceabilityDossierDto;
import com.pharmatrack.dto.DeviationDetailDto;
import com.pharmatrack.dto.QualityCheckResponseDto;
import com.pharmatrack.dto.StockMovementResponseDto;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchAnalysisFocusedPromptAndContextTest {

    private BatchContextBuilder contextBuilder;
    private BatchLifecycleRecordClassifier classifier;
    private BatchAnalysisReportService reportService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        classifier = new BatchLifecycleRecordClassifier();
        contextBuilder = new BatchContextBuilder(classifier);
        objectMapper = new ObjectMapper();
        reportService = new BatchAnalysisReportService(
                null, null, null, null, null, null,
                contextBuilder, null, objectMapper, null, classifier
        );
    }

    private BatchTraceabilityDossierDto createSampleDossier() {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(10L);
        dossier.setBatchNumber("BAT-2026-FOCUSED-01");
        dossier.setOrderNumber("ORD-1001");
        dossier.setProductName("Paracetamol 500mg");
        dossier.setProductCode("PRD-PCM");
        dossier.setBatchStatus(BatchStatus.COMPLETED);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        dossier.setBatchSize(new BigDecimal("1000.0000"));
        dossier.setActualYield(new BigDecimal("985.0000"));
        dossier.setBatchUnit("kg");
        dossier.setStartTime(LocalDateTime.now().minusHours(12));
        dossier.setEndTime(LocalDateTime.now().minusHours(2));

        BatchExecutionStepResponseDto step = new BatchExecutionStepResponseDto();
        step.setStepNumber(1);
        step.setOperationName("Dispensing");
        step.setExecutedBy("John Doe");
        step.setStatus(com.pharmatrack.entity.ExecutionStepStatus.COMPLETED);
        step.setCompletedAt(LocalDateTime.now().minusHours(11));
        dossier.setExecutionSteps(List.of(step));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setBatchId(10L);
        movement.setBatchNumber("BAT-2026-FOCUSED-01");
        movement.setLotNumber("LOT-RAW-001");
        movement.setMaterialName("Active API Paracetamol");
        movement.setQuantityDelta(new BigDecimal("500.0000"));
        dossier.setMaterialMovements(List.of(movement));

        QualityCheckResponseDto qc = new QualityCheckResponseDto();
        qc.setTestName("Assay Test");
        qc.setResult(com.pharmatrack.entity.QualityCheckResult.PASS);
        qc.setMeasuredValue("99.4%");
        dossier.setQualityChecks(List.of(qc));

        DeviationDetailDto deviation = new DeviationDetailDto();
        deviation.setDeviationNumber("DEV-2026-001");
        deviation.setTitle("Temperature Spike");
        deviation.setStatus(com.pharmatrack.entity.DeviationStatus.CLOSED);
        dossier.setDeviations(List.of(deviation));

        return dossier;
    }

    @Test
    @DisplayName("Focused Context Builder: Includes ONLY requested domains and filters source references")
    void testFocusedContextBuilderScoping() {
        BatchTraceabilityDossierDto dossier = createSampleDossier();

        // Focused on MANUFACTURING_YIELD only
        BatchContext yieldContext = contextBuilder.buildBatchContext(
                dossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD)
        );

        String yieldFacts = yieldContext.formattedFacts();
        assertThat(yieldFacts).contains("[BATCH_OVERVIEW]");
        assertThat(yieldFacts).contains("[PRODUCTION_METRICS]");
        assertThat(yieldFacts).doesNotContain("[MATERIAL_CONSUMPTION]");
        assertThat(yieldFacts).doesNotContain("[EBR_EXECUTION_SUMMARY]");
        assertThat(yieldFacts).doesNotContain("[QUALITY_CONTROL_SUMMARY]");
        assertThat(yieldFacts).doesNotContain("[DEVIATIONS_AND_CAPA]");
        assertThat(yieldFacts).doesNotContain("[CHRONOLOGICAL_AUDIT_SUMMARY]");

        // Sources for yield should only contain Batch/Order, not Deviations
        List<AiSourceReferenceDto> sources = yieldContext.sourceReferences();
        assertThat(sources).allMatch(s -> "BATCH".equalsIgnoreCase(s.getRecordType())
                || "MANUFACTURING_ORDER".equalsIgnoreCase(s.getRecordType()));
        assertThat(sources).noneMatch(s -> "DEVIATION".equalsIgnoreCase(s.getRecordType()));

        // Focused on DEVIATIONS_CAPA: sources must contain DEVIATION citations
        BatchContext devContext = contextBuilder.buildBatchContext(
                dossier, BatchAnalysisMode.FOCUSED, List.of(BatchAnalysisFocusArea.DEVIATIONS_CAPA)
        );
        String devFacts = devContext.formattedFacts();
        assertThat(devFacts).contains("[DEVIATIONS_AND_CAPA]");
        assertThat(devFacts).doesNotContain("[PRODUCTION_METRICS]");
        assertThat(devFacts).doesNotContain("[MATERIAL_CONSUMPTION]");
        assertThat(devContext.sourceReferences()).anyMatch(s -> "DEVIATION".equalsIgnoreCase(s.getRecordType()));
    }

    @Test
    @DisplayName("Prompt Construction: Generates targeted system instructions and user prompt for FOCUSED mode")
    void testFocusedPromptConstruction() {
        List<BatchAnalysisFocusArea> areas = List.of(
                BatchAnalysisFocusArea.MANUFACTURING_YIELD,
                BatchAnalysisFocusArea.DEVIATIONS_CAPA
        );

        String systemInstructions = reportService.constructSystemInstructions(BatchAnalysisMode.FOCUSED, areas);
        assertThat(systemInstructions)
                .contains("focused AI Batch Analysis Report")
                .contains("\"executiveSummary\"")
                .contains("\"plannedVsActualMetrics\"")
                .contains("\"deviationsAndCapa\"")
                .contains("\"followUpRecommendations\"")
                .contains("Do NOT include unselected sections in your JSON output")
                .doesNotContain("\"materialTraceability\"");

        String userPrompt = reportService.constructUserPrompt("Facts...", BatchAnalysisMode.FOCUSED, areas);
        assertThat(userPrompt)
                .contains("Manufacturing Yield")
                .contains("Deviations & CAPA")
                .contains("Facts...");
    }

    @Test
    @DisplayName("Focused Report Validation: Accepts minimal JSON with only requested fields")
    void testFocusedValidationSuccess() {
        BatchTraceabilityDossierDto dossier = createSampleDossier();
        List<BatchAnalysisFocusArea> areas = List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD);

        String validFocusedJson = """
                {
                  "executiveSummary": "Focused evaluation on manufacturing yield for batch BAT-2026-FOCUSED-01.",
                  "plannedVsActualMetrics": "Planned batch size 1000 kg vs actual yield 985 kg (98.5% recovery).",
                  "followUpRecommendations": "1. Reconcile final granulation yields against standard batch limits.\\n2. Monitor yield loss during granulation compression."
                }
                """;

        BatchAnalysisSectionsDto sections = reportService.parseAndValidateSections(
                validFocusedJson, dossier, BatchAnalysisMode.FOCUSED, areas
        );

        assertThat(sections).isNotNull();
        assertThat(sections.getExecutiveSummary()).contains("Focused evaluation on manufacturing yield");
        assertThat(sections.getPlannedVsActualMetrics()).contains("985 kg");
        assertThat(sections.getFollowUpRecommendations()).contains("1. Reconcile final granulation yields");

        // Unselected fields should remain null in DTO
        assertThat(sections.getMaterialTraceability()).isNull();
        assertThat(sections.getEbrStepEvaluation()).isNull();
        assertThat(sections.getDeviationsAndCapa()).isNull();

        // Deterministic lifecycle assessments and structured findings must be populated
        assertThat(sections.getMissingRecordAssessments()).isNotEmpty();
        assertThat(sections.getStructuredFindings()).hasSize(1);
        assertThat(sections.getStructuredFindings().get(0).getFocusArea()).isEqualTo(BatchAnalysisFocusArea.MANUFACTURING_YIELD);
        assertThat(sections.getLinkedRecommendations()).hasSize(2);
        assertThat(sections.getBatchContextSummary()).contains("BAT-2026-FOCUSED-01");
    }

    @Test
    @DisplayName("Focused Report Validation: Rejects JSON when a requested focus area field is missing")
    void testFocusedValidationMissingRequestedArea() {
        BatchTraceabilityDossierDto dossier = createSampleDossier();
        List<BatchAnalysisFocusArea> areas = List.of(
                BatchAnalysisFocusArea.MANUFACTURING_YIELD,
                BatchAnalysisFocusArea.DEVIATIONS_CAPA
        );

        // Missing deviationsAndCapa
        String incompleteJson = """
                {
                  "executiveSummary": "Summary...",
                  "plannedVsActualMetrics": "Yield metrics...",
                  "followUpRecommendations": "1. Rec item."
                }
                """;

        assertThatThrownBy(() -> reportService.parseAndValidateSections(
                incompleteJson, dossier, BatchAnalysisMode.FOCUSED, areas
        ))
                .isInstanceOf(BatchAnalysisValidationException.class)
                .hasMessageContaining("Missing, non-textual, or blank required focused report section: deviationsAndCapa");
    }

    @Test
    @DisplayName("GxP Compliance Guard: Rejects model output asserting unauthorized release in FOCUSED mode")
    void testGxPReleaseClaimRejectionInFocusedMode() {
        BatchTraceabilityDossierDto dossier = createSampleDossier();
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA); // Not released!

        List<BatchAnalysisFocusArea> areas = List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD);

        String violationJson = """
                {
                  "executiveSummary": "Batch BAT-2026-FOCUSED-01 is approved for release and ready for shipment to commercial markets.",
                  "plannedVsActualMetrics": "Yield is conforming.",
                  "followUpRecommendations": "1. Ship the batch immediately."
                }
                """;

        assertThatThrownBy(() -> reportService.parseAndValidateSections(
                violationJson, dossier, BatchAnalysisMode.FOCUSED, areas
        ))
                .isInstanceOf(BatchAnalysisValidationException.class)
                .hasMessageContaining("unsupported claim of completed QA batch release/approval");
    }

    @Test
    @DisplayName("Markdown Rendering: Renders focused title and includes only requested sections")
    void testFocusedMarkdownRendering() {
        BatchTraceabilityDossierDto dossier = createSampleDossier();
        Batch batch = new Batch();
        batch.setId(10L);
        batch.setBatchNumber("BAT-2026-FOCUSED-01");
        batch.setStatus(BatchStatus.COMPLETED);
        batch.setQaStatus(BatchQualityStatus.PENDING_QA);

        BatchAnalysisMetricsDto metrics = new BatchAnalysisMetricsDto(
                new BigDecimal("1000.0000"),
                new BigDecimal("985.0000"),
                new BigDecimal("98.5000"),
                new BigDecimal("-15.0000"),
                1, 1, 1
        );

        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto();
        sections.setExecutiveSummary("Focused executive summary.");
        sections.setPlannedVsActualMetrics("Yield details.");
        sections.setFollowUpRecommendations("1. Follow up recommendation 1.");

        List<BatchAnalysisFocusArea> areas = List.of(BatchAnalysisFocusArea.MANUFACTURING_YIELD);

        String markdown = reportService.renderMarkdownReport(
                batch, dossier, metrics, sections, "gpt-4o-mini",
                BatchAnalysisMode.FOCUSED, areas
        );

        assertThat(markdown)
                .contains("# Focused AI Batch Analysis: BAT-2026-FOCUSED-01")
                .contains("**Analysis Mode:** `FOCUSED`")
                .contains("**Focus Areas:** Manufacturing Yield")
                .contains("## 1. Executive Summary")
                .contains("## 2. Planned vs Actual Production Metrics")
                .contains("- **Planned Batch Size:** 1000.0000 kg")
                .contains("- **Actual Yield:** 985.0000 kg")
                .contains("- **Yield Percentage:** 98.5000%")
                .contains("## 3. Recommended Follow-Up Actions")
                .doesNotContain("## 4. Raw Material and Lot Traceability")
                .doesNotContain("## 5. Electronic Batch Record")
                .doesNotContain("## 8. Deviations and CAPA Traceability")
                .contains(BatchAnalysisReportService.REPORT_SYSTEM_INSTRUCTIONS != null ? "ADVISORY ONLY" : "");
    }
}
