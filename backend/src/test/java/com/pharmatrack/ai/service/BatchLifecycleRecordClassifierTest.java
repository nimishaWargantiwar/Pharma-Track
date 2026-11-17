package com.pharmatrack.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.dto.BatchAnalysisSectionsDto;
import com.pharmatrack.ai.dto.RecordAssessmentDto;
import com.pharmatrack.ai.entity.RecordLifecycleClassification;
import com.pharmatrack.dto.BatchExecutionStepResponseDto;
import com.pharmatrack.dto.BatchTraceabilityDossierDto;
import com.pharmatrack.dto.DeviationDetailDto;
import com.pharmatrack.dto.OrderMaterialRequirementDto;
import com.pharmatrack.dto.QualityCheckResponseDto;
import com.pharmatrack.dto.StockMovementResponseDto;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.entity.StockMovementType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchLifecycleRecordClassifierTest {

    private BatchLifecycleRecordClassifier classifier;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        classifier = new BatchLifecycleRecordClassifier();
        objectMapper = new ObjectMapper();
    }

    private BatchTraceabilityDossierDto createBaseDossier(String batchNumber, BatchStatus status) {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();
        dossier.setBatchId(100L);
        dossier.setBatchNumber(batchNumber);
        dossier.setBatchStatus(status);
        dossier.setQaStatus(BatchQualityStatus.PENDING_QA);
        dossier.setBatchSize(new BigDecimal("1000.0000"));
        dossier.setBatchUnit("kg");
        return dossier;
    }

    @Test
    @DisplayName("1. Zero linked deviations represented as structured observation, not PRESENT or NOT_APPLICABLE record")
    void testZeroLinkedDeviationsRepresentedAsStructuredObservation() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-DEV-ZERO", BatchStatus.COMPLETED);
        dossier.setDeviations(List.of()); // Zero linked deviations

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);

        // Absence of deviations must NOT be represented as a RecordAssessmentDto (neither PRESENT nor NOT_APPLICABLE)
        assertThat(assessments).noneMatch(a -> "Deviations & CAPA".equalsIgnoreCase(a.getRecordType()));

        // Separate deterministic observation note
        String observation = classifier.getDeviationsObservation(dossier);
        assertThat(observation)
                .contains("ZERO_RECORDED")
                .contains("No linked deviations recorded in retrieved MES records")
                .contains("does not constitute proof that no deviations occurred or that the batch is compliant or free of operational risk");

        // Section 10 markdown rendering contains the deterministic observation without compliance claims
        String renderedMarkdown = classifier.renderMissingRecordsEvaluation(dossier, assessments);
        assertThat(renderedMarkdown)
                .contains("ZERO_RECORDED")
                .contains("No linked deviations recorded in retrieved MES records");

        // Gaps must not penalize zero deviations
        List<String> genuineGaps = classifier.extractGenuinelyMissingGaps(assessments);
        assertThat(genuineGaps).noneMatch(g -> g.contains("Deviations"));
    }

    @Test
    @DisplayName("2. Zero deviations without unsupported compliance claims")
    void testZeroDeviationsWithoutComplianceClaim() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-DEV-NO-CLAIM", BatchStatus.IN_PROGRESS);
        dossier.setDeviations(List.of());

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        assertThat(assessments).noneMatch(a -> "Deviations & CAPA".equalsIgnoreCase(a.getRecordType()));

        String observation = classifier.getDeviationsObservation(dossier);
        assertThat(observation)
                .contains("does not constitute proof that no deviations occurred or that the batch is compliant or free of operational risk")
                .doesNotContain("100% compliant")
                .doesNotContain("fully compliant");

        String renderedMarkdown = classifier.renderMissingRecordsEvaluation(dossier, assessments);
        assertThat(renderedMarkdown)
                .doesNotContain("100% compliant")
                .doesNotContain("fully compliant");
    }

    @Test
    @DisplayName("3. Deviations present classified accurately as PRESENT")
    void testDeviationsPresentClassifiedAccurately() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-DEV-PRESENT", BatchStatus.IN_PROGRESS);
        DeviationDetailDto dev = new DeviationDetailDto();
        dev.setId(1L);
        dev.setDeviationNumber("DEV-2026-001");
        dev.setStatus(DeviationStatus.OPEN);
        dossier.setDeviations(List.of(dev));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto devAssessment = findAssessment(assessments, "Deviations & CAPA");
        assertThat(devAssessment).isNotNull();
        assertThat(devAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(devAssessment.getExplanation()).contains("1 deviation(s) recorded");
    }

    @Test
    @DisplayName("4. Generic PENDING dispensing step with multiple BOM materials yields UNKNOWN")
    void testGenericPendingDispensingStepWithMultipleMaterialsYieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-MAT-GEN-PEND", BatchStatus.IN_PROGRESS);
        dossier.setStartTime(LocalDateTime.now().minusHours(1));

        // Step 1: Line Clearance (IN_PROGRESS)
        BatchExecutionStepResponseDto step1 = new BatchExecutionStepResponseDto();
        step1.setStepNumber(1);
        step1.setOperationName("Line Clearance & Sanitization");
        step1.setStatus(ExecutionStepStatus.IN_PROGRESS);

        // Step 2: Generic Dispensing (PENDING)
        BatchExecutionStepResponseDto step2 = new BatchExecutionStepResponseDto();
        step2.setStepNumber(2);
        step2.setOperationName("Raw Material Dispensing");
        step2.setStatus(ExecutionStepStatus.PENDING);

        dossier.setExecutionSteps(List.of(step1, step2));

        // Multiple BOM materials
        OrderMaterialRequirementDto req1 = new OrderMaterialRequirementDto();
        req1.setRecipeMaterialName("API Paracetamol 500mg");
        req1.setTotalRequiredQuantity(new BigDecimal("500.0000"));
        req1.setIssuedQuantity(BigDecimal.ZERO);

        OrderMaterialRequirementDto req2 = new OrderMaterialRequirementDto();
        req2.setRecipeMaterialName("Microcrystalline Cellulose");
        req2.setTotalRequiredQuantity(new BigDecimal("200.0000"));
        req2.setIssuedQuantity(BigDecimal.ZERO);

        dossier.setMaterialRequirements(List.of(req1, req2));
        dossier.setMaterialMovements(List.of()); // Zero movements recorded

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        // A generic pending dispensing step cannot prove individual BOM material milestones without structured linkage
        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("recipe execution steps lack BOM foreign keys")
                .contains("generic step status cannot establish individual material milestones");

        List<String> genuineGaps = classifier.extractGenuinelyMissingGaps(assessments);
        assertThat(genuineGaps).noneMatch(g -> g.contains("Material Issue"));
    }

    @Test
    @DisplayName("5. Generic IN_PROGRESS dispensing step with multiple BOM materials yields UNKNOWN")
    void testGenericInProgressDispensingStepWithMultipleMaterialsYieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-MAT-GEN-INPROG", BatchStatus.IN_PROGRESS);
        dossier.setStartTime(LocalDateTime.now().minusHours(2));

        // Step 1: Dispensing is currently IN_PROGRESS
        BatchExecutionStepResponseDto step1 = new BatchExecutionStepResponseDto();
        step1.setStepNumber(1);
        step1.setOperationName("Raw Material Dispensing");
        step1.setStatus(ExecutionStepStatus.IN_PROGRESS);

        // Step 2: Granulation is PENDING
        BatchExecutionStepResponseDto step2 = new BatchExecutionStepResponseDto();
        step2.setStepNumber(2);
        step2.setOperationName("Granulation");
        step2.setStatus(ExecutionStepStatus.PENDING);

        dossier.setExecutionSteps(List.of(step1, step2));

        OrderMaterialRequirementDto req1 = new OrderMaterialRequirementDto();
        req1.setRecipeMaterialName("Active Drug Substance");
        req1.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req1.setIssuedQuantity(BigDecimal.ZERO);

        OrderMaterialRequirementDto req2 = new OrderMaterialRequirementDto();
        req2.setRecipeMaterialName("Starch Binder");
        req2.setTotalRequiredQuantity(new BigDecimal("50.0000"));
        req2.setIssuedQuantity(BigDecimal.ZERO);

        dossier.setMaterialRequirements(List.of(req1, req2));
        dossier.setMaterialMovements(List.of());

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        // Generic in-progress dispensing cannot prove every BOM material's milestone
        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("recipe execution steps lack BOM foreign keys");
    }

    @Test
    @DisplayName("6. No dispensing step with unrelated pending execution steps yields UNKNOWN")
    void testNoDispensingStepWithUnrelatedPendingStepsYieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-MAT-UNRELATED", BatchStatus.IN_PROGRESS);
        dossier.setStartTime(LocalDateTime.now().minusHours(3));

        // Steps without any dispensing operation
        BatchExecutionStepResponseDto step1 = new BatchExecutionStepResponseDto();
        step1.setStepNumber(1);
        step1.setOperationName("Granulation");
        step1.setStatus(ExecutionStepStatus.COMPLETED);

        BatchExecutionStepResponseDto step2 = new BatchExecutionStepResponseDto();
        step2.setStepNumber(2);
        step2.setOperationName("Compression & Tableting");
        step2.setStatus(ExecutionStepStatus.PENDING); // Unrelated pending step

        dossier.setExecutionSteps(List.of(step1, step2));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("Active Drug Substance");
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setIssuedQuantity(BigDecimal.ZERO);
        dossier.setMaterialRequirements(List.of(req));
        dossier.setMaterialMovements(List.of());

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        // Must NOT infer NOT_YET_EXPECTED merely from unrelated pending steps like Compression
        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("recipe execution steps lack BOM foreign keys")
                .contains("generic step status cannot establish individual material milestones");
    }

    @Test
    @DisplayName("7. Completed batch with no material movements yields UNKNOWN with dataInsufficient = true")
    void testCompletedBatchWithNoMaterialMovementsYieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-MAT-COMP-NO-MOV", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setTotalRequiredQuantity(new BigDecimal("500.0000"));
        req.setIssuedQuantity(BigDecimal.ZERO);
        dossier.setMaterialRequirements(List.of(req));
        dossier.setMaterialMovements(List.of()); // Zero movements recorded

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        // Batch completion workflow does not enforce stock movement reconciliation; cannot classify as GENUINELY_MISSING
        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("batch completion workflow does not enforce inventory issue ledger reconciliation")
                .contains("MES batch completion workflow does not enforce stock movement reconciliation");

        List<String> genuineGaps = classifier.extractGenuinelyMissingGaps(assessments);
        assertThat(genuineGaps).noneMatch(g -> g.contains("Material Issue"));
    }

    @Test
    @DisplayName("8. Completed batch with partial material movements yields UNKNOWN with dataInsufficient = true")
    void testCompletedBatchWithPartialMaterialMovementsYieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-MAT-COMP-PARTIAL", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("500.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("500.0000"));
        req.setIssuedQuantity(new BigDecimal("200.0000")); // Partial issue recorded
        req.setRecipeUnit("kg");
        req.setFulfilled(false);
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-MAT-COMP-PARTIAL");
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setQuantityDelta(new BigDecimal("-200.0000"));
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        // Partial movement on completed batch does not prove remaining quantity is genuinely missing in domain workflow
        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("incomplete material issue movements recorded in MES");

        List<String> genuineGaps = classifier.extractGenuinelyMissingGaps(assessments);
        assertThat(genuineGaps).noneMatch(g -> g.contains("Material Issue"));
    }

    @Test
    @DisplayName("9. Completed batch with fully fulfilled material movements yields PRESENT")
    void testCompletedBatchWithFulfilledMaterialMovementsYieldsPresent() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-MAT-COMP-FULFILLED", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("500.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("500.0000"));
        req.setIssuedQuantity(new BigDecimal("500.0000"));
        req.setRecipeUnit("kg");
        req.setFulfilled(true);
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-MAT-COMP-FULFILLED");
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setQuantityDelta(new BigDecimal("-500.0000"));
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("1 batch-specific material issue(s) recorded with assigned lots");
    }

    @Test
    @DisplayName("10. PLANNED batch yields NOT_YET_EXPECTED based on authoritative uncommenced lifecycle stage")
    void testPlannedBatchYieldsNotYetExpected() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-MAT-PLAN", BatchStatus.PLANNED);

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setTotalRequiredQuantity(new BigDecimal("500.0000"));
        req.setIssuedQuantity(BigDecimal.ZERO);
        dossier.setMaterialRequirements(List.of(req));
        dossier.setMaterialMovements(List.of());

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_YET_EXPECTED);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("Batch execution has not commenced")
                .contains("occur upon or during execution initiation");
    }

    @Test
    @DisplayName("11. Exact material name in instructions alone does not produce GENUINELY_MISSING")
    void testExactMaterialNameInInstructionsAloneDoesNotProduceGenuinelyMissing() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-MAT-INSTR-MATCH", BatchStatus.IN_PROGRESS);
        dossier.setStartTime(LocalDateTime.now().minusHours(4));

        // Step 1: Instructions mention exact material name "API Paracetamol", but free text is not a structured link
        BatchExecutionStepResponseDto step1 = new BatchExecutionStepResponseDto();
        step1.setStepNumber(1);
        step1.setOperationName("Dispense Active Raw Material");
        step1.setInstructions("Dispense 500kg of API Paracetamol into clean stainless steel containers");
        step1.setStatus(ExecutionStepStatus.COMPLETED);
        step1.setExecutedBy("op_john");
        step1.setCompletedAt(LocalDateTime.now().minusHours(3));

        dossier.setExecutionSteps(List.of(step1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setTotalRequiredQuantity(new BigDecimal("500.0000"));
        req.setIssuedQuantity(BigDecimal.ZERO);
        dossier.setMaterialRequirements(List.of(req));
        dossier.setMaterialMovements(List.of()); // Zero movements recorded

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("recipe execution steps lack BOM foreign keys");

        List<String> genuineGaps = classifier.extractGenuinelyMissingGaps(assessments);
        assertThat(genuineGaps).noneMatch(g -> g.contains("Material Issue"));
    }

    @Test
    @DisplayName("12. Exact material code in observations alone does not produce GENUINELY_MISSING")
    void testExactMaterialCodeInObservationsAloneDoesNotProduceGenuinelyMissing() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-MAT-OBS-MATCH", BatchStatus.IN_PROGRESS);
        dossier.setStartTime(LocalDateTime.now().minusHours(3));

        // Step 1: Observations contain exact material code "RAW-PAR-001"
        BatchExecutionStepResponseDto step1 = new BatchExecutionStepResponseDto();
        step1.setStepNumber(1);
        step1.setOperationName("Weighing & Verification");
        step1.setInstructions("Verify container tare weights");
        step1.setObservations("Operator verified lot staging for material RAW-PAR-001");
        step1.setStatus(ExecutionStepStatus.COMPLETED);
        step1.setExecutedBy("op_jane");
        step1.setCompletedAt(LocalDateTime.now().minusHours(2));

        dossier.setExecutionSteps(List.of(step1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("Paracetamol Powder");
        req.setRawMaterialCode("RAW-PAR-001");
        req.setTotalRequiredQuantity(new BigDecimal("250.0000"));
        req.setIssuedQuantity(BigDecimal.ZERO);
        dossier.setMaterialRequirements(List.of(req));
        dossier.setMaterialMovements(List.of());

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("recipe execution steps lack BOM foreign keys");

        List<String> genuineGaps = classifier.extractGenuinelyMissingGaps(assessments);
        assertThat(genuineGaps).noneMatch(g -> g.contains("Material Issue"));
    }

    @Test
    @DisplayName("13. Material quantity distinction: required vs issued vs consumed")
    void testMaterialQuantityDistinction() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-MAT-QTY", BatchStatus.IN_PROGRESS);
        dossier.setStartTime(LocalDateTime.now().minusHours(2));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("Lactose Monohydrate");
        req.setQuantityPerBatch(new BigDecimal("250.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("250.0000"));
        req.setIssuedQuantity(new BigDecimal("250.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-MAT-QTY");
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setQuantityDelta(new BigDecimal("-250.0000"));
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        // Explicit distinction between required, issued, and consumed
        assertThat(matAssessment.getExplanation())
                .contains("Required: 250.0000")
                .contains("Issued: 250.0000")
                .contains("Consumed: tracked via step execution / yield reconciliation upon completion; not inferred from inventory movements");
    }

    @Test
    @DisplayName("14. Explicitly optional QC checks yield NOT_APPLICABLE")
    void testExplicitQcNotRequiredConfiguration() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-QC-EXPLICIT-OPT", BatchStatus.COMPLETED);

        // All configured QC checks have is_required = false
        QualityCheckResponseDto optionalCheck = new QualityCheckResponseDto();
        optionalCheck.setTestName("Optional Appearance Check");
        optionalCheck.setRequired(false);
        optionalCheck.setResult(QualityCheckResult.PASS);
        optionalCheck.setMeasuredValue("Conforms");
        dossier.setQualityChecks(List.of(optionalCheck));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");

        // Explicit optional-only configuration results in NOT_APPLICABLE for mandatory QC
        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);
        assertThat(qcAssessment.getExplanation())
                .contains("explicitly designated as optional in master recipe; no mandatory QC testing is required");
    }

    @Test
    @DisplayName("15. Missing QC configuration yields UNKNOWN with dataInsufficient = true")
    void testQcConfigurationAbsentOrUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-QC-UNKNOWN", BatchStatus.COMPLETED);
        dossier.setQualityChecks(List.of()); // 0 QC checks in dossier and no explicit exemption

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");

        // Must be UNKNOWN (not NOT_APPLICABLE), because absence of records != proof of exemption
        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(qcAssessment.getDataInsufficient()).isTrue();
        assertThat(qcAssessment.getExplanation())
                .contains("Master recipe QC configuration is absent or unknown")
                .contains("data insufficient; cannot infer QC exemption from absence of records");

        // UNKNOWN is not a genuine missing gap (it is an informational limitation)
        List<String> genuineGaps = classifier.extractGenuinelyMissingGaps(assessments);
        assertThat(genuineGaps).noneMatch(g -> g.contains("Quality Control"));
    }

    @Test
    @DisplayName("16. Mandatory QC checks before and after expected milestones")
    void testMandatoryQcChecksBeforeAndAfterMilestones() {
        // Milestone 1: PLANNED batch with configured mandatory check -> NOT_YET_EXPECTED
        BatchTraceabilityDossierDto plannedDossier = createBaseDossier("BAT-QC-MILE-PLAN", BatchStatus.PLANNED);
        QualityCheckResponseDto reqCheck = new QualityCheckResponseDto();
        reqCheck.setTestName("Assay Potency");
        reqCheck.setRequired(true);
        reqCheck.setResult(QualityCheckResult.PENDING);
        plannedDossier.setQualityChecks(List.of(reqCheck));

        List<RecordAssessmentDto> plannedAssessments = classifier.classifyRecords(plannedDossier);
        RecordAssessmentDto plannedQc = findAssessment(plannedAssessments, "Quality Control (QC) Inspections");
        assertThat(plannedQc.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_YET_EXPECTED);
        assertThat(plannedQc.getExplanation()).contains("testing is not yet expected for a PLANNED batch");

        // Milestone 2: COMPLETED batch with pending check -> GENUINELY_MISSING
        BatchTraceabilityDossierDto completedDossier = createBaseDossier("BAT-QC-MILE-COMP", BatchStatus.COMPLETED);
        completedDossier.setEndTime(LocalDateTime.now().minusHours(1));
        completedDossier.setQualityChecks(List.of(reqCheck)); // Still PENDING on completed batch

        List<RecordAssessmentDto> completedAssessments = classifier.classifyRecords(completedDossier);
        RecordAssessmentDto completedQc = findAssessment(completedAssessments, "Quality Control (QC) Inspections");
        assertThat(completedQc.getClassification()).isEqualTo(RecordLifecycleClassification.GENUINELY_MISSING);
        assertThat(completedQc.getExplanation()).contains("remains PENDING");
        assertThat(classifier.extractGenuinelyMissingGaps(completedAssessments)).anyMatch(g -> g.contains("Quality Control"));

        // Milestone 3: COMPLETED batch with passed check -> PRESENT
        QualityCheckResponseDto passedCheck = new QualityCheckResponseDto();
        passedCheck.setTestName("Assay Potency");
        passedCheck.setRequired(true);
        passedCheck.setResult(QualityCheckResult.PASS);
        passedCheck.setMeasuredValue("99.8%");
        passedCheck.setInspectorName("qc_analyst");
        completedDossier.setQualityChecks(List.of(passedCheck));

        List<RecordAssessmentDto> passedAssessments = classifier.classifyRecords(completedDossier);
        RecordAssessmentDto passedQc = findAssessment(passedAssessments, "Quality Control (QC) Inspections");
        assertThat(passedQc.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(passedQc.getExplanation()).contains("passing analytical results and inspector sign-offs");
    }

    @Test
    @DisplayName("17. Legacy report deserialization remains intact")
    void testLegacyReportDeserializationRemainsIntact() throws Exception {
        // Historical JSON without UNKNOWN enum or dataInsufficient field
        String legacyJson = """
                {
                  "executiveSummary": "Historical executive summary from Phase 4E.",
                  "plannedVsActualMetrics": "Planned 1000 kg vs 980 kg.",
                  "ebrStepEvaluation": "All steps executed.",
                  "qcInspectionAnalysis": "QC inspection completed.",
                  "qaDispositionStatus": "PENDING_QA",
                  "deviationsAndCapa": "Zero deviations.",
                  "missingRecords": "No documentation gaps.",
                  "followUpRecommendations": "1. Review batch records."
                }
                """;

        BatchAnalysisSectionsDto sections = objectMapper.readValue(legacyJson, BatchAnalysisSectionsDto.class);

        assertThat(sections).isNotNull();
        assertThat(sections.getExecutiveSummary()).isEqualTo("Historical executive summary from Phase 4E.");
        assertThat(sections.getMissingRecordAssessments()).isEmpty();
        assertThat(sections.getStructuredFindings()).isEmpty();
        assertThat(sections.getLinkedRecommendations()).isEmpty();
        assertThat(sections.getBatchContextSummary()).isNull();
    }

    @Test
    @DisplayName("18. Scenario 1: Required 100 kg, issue 100 kg yields PRESENT with verified quantity fulfilment")
    void testScenario1_Required100_Issued100_YieldsPresent() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-S1-100-100", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(101L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-S1-100-100");
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialName("API Paracetamol");
        movement.setQuantityDelta(new BigDecimal("-100.0000"));
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("Record existence: 1 batch-specific issue record(s) found")
                .contains("Quantity fulfilment established")
                .contains("Required: 100.0000 kg, Issued: 100.0000 kg");
    }

    @Test
    @DisplayName("19. Scenario 2: Required 100 kg, issue 30 kg yields UNKNOWN with dataInsufficient = true")
    void testScenario2_Required100_Issued30_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-S2-100-30", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(102L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-S2-100-30");
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialName("API Paracetamol");
        movement.setQuantityDelta(new BigDecimal("-30.0000"));
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Record existence: 1 batch-specific issue record(s) found")
                .contains("Quantity requirement unfulfilled")
                .contains("incomplete material issue movements recorded in MES")
                .contains("Required: 100.0000 kg, Issued: 30.0000 kg");
    }

    @Test
    @DisplayName("20. Scenario 3: Required 100 kg, issue 120 kg yields PRESENT with over-issue fulfilled")
    void testScenario3_Required100_Issued120_YieldsPresent() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-S3-100-120", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(103L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-S3-100-120");
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialName("API Paracetamol");
        movement.setQuantityDelta(new BigDecimal("-120.0000"));
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("Record existence: 1 batch-specific issue record(s) found")
                .contains("Quantity fulfilment established")
                .contains("Required: 100.0000 kg, Issued: 120.0000 kg");
    }

    @Test
    @DisplayName("21. Scenario 4: Required 100 kg, issue 100 kg plus full reversal yields UNKNOWN with indeterminate net quantity")
    void testScenario4_Required100_IssuePlusReversal_YieldsUnknownIndeterminate() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-S4-REVERSAL", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto issueMvt = new StockMovementResponseDto();
        issueMvt.setId(104L);
        issueMvt.setBatchId(100L);
        issueMvt.setBatchNumber("BAT-S4-REVERSAL");
        issueMvt.setMovementType(StockMovementType.MATERIAL_ISSUE);
        issueMvt.setMaterialName("API Paracetamol");
        issueMvt.setQuantityDelta(new BigDecimal("-100.0000"));
        issueMvt.setUnit("kg");

        StockMovementResponseDto reversalMvt = new StockMovementResponseDto();
        reversalMvt.setId(105L);
        reversalMvt.setBatchId(100L);
        reversalMvt.setBatchNumber("BAT-S4-REVERSAL");
        reversalMvt.setMovementType(StockMovementType.MATERIAL_ISSUE);
        reversalMvt.setMaterialName("API Paracetamol");
        reversalMvt.setQuantityDelta(new BigDecimal("100.0000")); // Positive / reversal delta
        reversalMvt.setUnit("kg");

        dossier.setMaterialMovements(List.of(issueMvt, reversalMvt));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Record existence: 2 batch-specific issue record(s) found")
                .contains("Quantity fulfilment indeterminate")
                .contains("reversal");
    }

    @Test
    @DisplayName("22. Scenario 5: Two different materials with different units reconciled separately yields PRESENT")
    void testScenario5_TwoMaterialsDifferentUnits_YieldsPresent() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-S5-MULTI-UNIT", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req1 = new OrderMaterialRequirementDto();
        req1.setRecipeMaterialName("Active Drug Substance");
        req1.setQuantityPerBatch(new BigDecimal("100.0000"));
        req1.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req1.setRecipeUnit("kg");

        OrderMaterialRequirementDto req2 = new OrderMaterialRequirementDto();
        req2.setRecipeMaterialName("Purified Solvent");
        req2.setQuantityPerBatch(new BigDecimal("50.0000"));
        req2.setTotalRequiredQuantity(new BigDecimal("50.0000"));
        req2.setRecipeUnit("L");

        dossier.setMaterialRequirements(List.of(req1, req2));

        StockMovementResponseDto m1 = new StockMovementResponseDto();
        m1.setId(106L);
        m1.setBatchId(100L);
        m1.setBatchNumber("BAT-S5-MULTI-UNIT");
        m1.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m1.setMaterialName("Active Drug Substance");
        m1.setQuantityDelta(new BigDecimal("-100.0000"));
        m1.setUnit("kg");

        StockMovementResponseDto m2 = new StockMovementResponseDto();
        m2.setId(107L);
        m2.setBatchId(100L);
        m2.setBatchNumber("BAT-S5-MULTI-UNIT");
        m2.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m2.setMaterialName("Purified Solvent");
        m2.setQuantityDelta(new BigDecimal("-50.0000"));
        m2.setUnit("L");

        dossier.setMaterialMovements(List.of(m1, m2));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("Record existence: 2 batch-specific issue record(s) found")
                .contains("Quantity fulfilment established")
                .contains("Active Drug Substance (Required: 100.0000 kg, Issued: 100.0000 kg)")
                .contains("Purified Solvent (Required: 50.0000 L, Issued: 50.0000 L)");
    }

    @Test
    @DisplayName("23. Scenario 6: One over-issued material does not mask unissued material; yields UNKNOWN")
    void testScenario6_OverIssueDoesNotMaskUnissuedMaterial_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-S6-MASKING", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req1 = new OrderMaterialRequirementDto();
        req1.setRecipeMaterialName("Active Drug Substance");
        req1.setQuantityPerBatch(new BigDecimal("10.0000"));
        req1.setTotalRequiredQuantity(new BigDecimal("10.0000"));
        req1.setRecipeUnit("kg");

        OrderMaterialRequirementDto req2 = new OrderMaterialRequirementDto();
        req2.setRecipeMaterialName("Binder Excipient");
        req2.setQuantityPerBatch(new BigDecimal("50.0000"));
        req2.setTotalRequiredQuantity(new BigDecimal("50.0000"));
        req2.setRecipeUnit("kg");

        dossier.setMaterialRequirements(List.of(req1, req2));

        // Over-issue of 100 kg on req1 (required 10 kg). Req2 has 0 issued.
        // Old logic: 100 >= 60 -> true. New logic: req2 is unfulfilled -> UNKNOWN.
        StockMovementResponseDto m1 = new StockMovementResponseDto();
        m1.setId(108L);
        m1.setBatchId(100L);
        m1.setBatchNumber("BAT-S6-MASKING");
        m1.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m1.setMaterialName("Active Drug Substance");
        m1.setQuantityDelta(new BigDecimal("-100.0000"));
        m1.setUnit("kg");

        dossier.setMaterialMovements(List.of(m1));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity requirement unfulfilled")
                .contains("Over-issue of one material does not fulfill separate material requirements")
                .contains("Active Drug Substance (Required: 10.0000 kg, Issued: 100.0000 kg)")
                .contains("Binder Excipient (Required: 50.0000 kg, Issued: 0 kg)");
    }

    @Test
    @DisplayName("24. Scenario 7: Multiple partial movements for the same material aggregate correctly to PRESENT")
    void testScenario7_MultiplePartialMovementsSameMaterial_YieldsPresent() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-S7-PARTIAL-AGG", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto m1 = new StockMovementResponseDto();
        m1.setId(109L);
        m1.setBatchId(100L);
        m1.setBatchNumber("BAT-S7-PARTIAL-AGG");
        m1.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m1.setMaterialName("API Paracetamol");
        m1.setQuantityDelta(new BigDecimal("-40.0000"));
        m1.setUnit("kg");

        StockMovementResponseDto m2 = new StockMovementResponseDto();
        m2.setId(110L);
        m2.setBatchId(100L);
        m2.setBatchNumber("BAT-S7-PARTIAL-AGG");
        m2.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m2.setMaterialName("API Paracetamol");
        m2.setQuantityDelta(new BigDecimal("-60.0000"));
        m2.setUnit("kg");

        dossier.setMaterialMovements(List.of(m1, m2));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("Record existence: 2 batch-specific issue record(s) found")
                .contains("Quantity fulfilment established")
                .contains("Required: 100.0000 kg, Issued: 100.0000 kg");
    }

    @Test
    @DisplayName("25. Scenario 8: Null movement quantity yields UNKNOWN with dataInsufficient = true")
    void testScenario8_NullMovementQuantity_YieldsUnknownIndeterminate() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-S8-NULL-QTY", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(111L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-S8-NULL-QTY");
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialName("API Paracetamol");
        movement.setQuantityDelta(null); // Explicit null quantity
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Record existence: 1 batch-specific issue record(s) found")
                .contains("Quantity fulfilment indeterminate")
                .contains("null/missing quantity delta");
    }

    @Test
    @DisplayName("26. Scenario 9: Order-level movement without batch attribution yields UNKNOWN with zero batch records")
    void testScenario9_OrderLevelMovementWithoutBatchAttribution_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-S9-ORDER-LEVEL", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        // Movement has orderId but batchId and batchNumber are NULL
        StockMovementResponseDto orderMvt = new StockMovementResponseDto();
        orderMvt.setId(112L);
        orderMvt.setManufacturingOrderId(200L);
        orderMvt.setOrderNumber("ORD-2026-001");
        orderMvt.setBatchId(null);
        orderMvt.setBatchNumber(null);
        orderMvt.setMovementType(StockMovementType.MATERIAL_ISSUE);
        orderMvt.setMaterialName("API Paracetamol");
        orderMvt.setQuantityDelta(new BigDecimal("-100.0000"));
        orderMvt.setUnit("kg");
        dossier.setMaterialMovements(List.of(orderMvt));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Record existence: No batch-specific issue record found");
    }

    @Test
    @DisplayName("27. Scenario 10: Multi-batch order uses quantityPerBatch and does not use order total as batch requirement")
    void testScenario10_MultiBatchOrder_UsesQuantityPerBatch() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-S10-MULTI-BATCH", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));
        dossier.setTargetBatchCount(3); // 3 batches in order

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000")); // Batch requirement: 100 kg
        req.setTotalRequiredQuantity(new BigDecimal("300.0000")); // Order total: 300 kg
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(113L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-S10-MULTI-BATCH");
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialName("API Paracetamol");
        movement.setQuantityDelta(new BigDecimal("-100.0000")); // 100 kg issued to this batch
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        // Batch 1 requirement (100 kg) is fulfilled, even though order total (300 kg) is not
        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity fulfilment established")
                .contains("Required: 100.0000 kg, Issued: 100.0000 kg");

        // Sub-case: multi-batch order without quantityPerBatch yields indeterminate
        req.setQuantityPerBatch(null); // Missing per-batch requirement
        List<RecordAssessmentDto> indeterminateAssessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto indetAssessment = findAssessment(indeterminateAssessments, "Material Issue & Lot Traceability");
        assertThat(indetAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(indetAssessment.getDataInsufficient()).isTrue();
        assertThat(indetAssessment.getExplanation())
                .contains("Quantity fulfilment indeterminate")
                .contains("order contains multiple batches");
    }

    @Test
    @DisplayName("28. Scenario 11: Duplicate movements with same ID are deduplicated and not double-counted")
    void testScenario11_DuplicateMovementsDeduplicated() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-S11-DEDUP", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        // Two movement instances with identical ID (114L) and movementNumber ("ISS-001")
        StockMovementResponseDto m1 = new StockMovementResponseDto();
        m1.setId(114L);
        m1.setMovementNumber("ISS-001");
        m1.setBatchId(100L);
        m1.setBatchNumber("BAT-S11-DEDUP");
        m1.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m1.setMaterialName("API Paracetamol");
        m1.setQuantityDelta(new BigDecimal("-100.0000"));
        m1.setUnit("kg");

        StockMovementResponseDto m2 = new StockMovementResponseDto();
        m2.setId(114L);
        m2.setMovementNumber("ISS-001");
        m2.setBatchId(100L);
        m2.setBatchNumber("BAT-S11-DEDUP");
        m2.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m2.setMaterialName("API Paracetamol");
        m2.setQuantityDelta(new BigDecimal("-100.0000"));
        m2.setUnit("kg");

        dossier.setMaterialMovements(List.of(m1, m2));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        // Exactly 1 unique movement recognized
        assertThat(matAssessment.getExplanation())
                .contains("Record existence: 1 batch-specific issue record(s) found")
                .contains("Required: 100.0000 kg, Issued: 100.0000 kg");
    }

    @Test
    @DisplayName("29. Scenario 12: Zero-deviation and QC classifications remain strictly accurate")
    void testScenario12_ZeroDeviationAndQcClassificationsPreserved() {
        // Zero deviations is a separate structured observation, not in assessments
        BatchTraceabilityDossierDto zeroDevDossier = createBaseDossier("BAT-S12-ZERO-DEV", BatchStatus.COMPLETED);
        zeroDevDossier.setDeviations(List.of());
        List<RecordAssessmentDto> devAssessments = classifier.classifyRecords(zeroDevDossier);
        assertThat(devAssessments).noneMatch(a -> "Deviations & CAPA".equalsIgnoreCase(a.getRecordType()));
        assertThat(classifier.getDeviationsObservation(zeroDevDossier))
                .contains("ZERO_RECORDED")
                .contains("No linked deviations recorded in retrieved MES records");

        // Mandatory QC passing yields PRESENT
        QualityCheckResponseDto qc = new QualityCheckResponseDto();
        qc.setTestName("Assay Potency");
        qc.setRequired(true);
        qc.setResult(QualityCheckResult.PASS);
        qc.setMeasuredValue("99.9%");
        qc.setInspectorName("qc_inspector");
        zeroDevDossier.setQualityChecks(List.of(qc));
        zeroDevDossier.setEndTime(LocalDateTime.now().minusHours(1));

        List<RecordAssessmentDto> qcAssessments = classifier.classifyRecords(zeroDevDossier);
        RecordAssessmentDto qcAssessment = findAssessment(qcAssessments, "Quality Control (QC) Inspections");
        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(qcAssessment.getDataInsufficient()).isFalse();
    }

    @Test
    @DisplayName("30. Negative STOCK_ADJUSTMENT does not count as material issued")
    void testNegativeStockAdjustmentDoesNotCountAsIssued() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-ADJ-NEG", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto adjMvt = new StockMovementResponseDto();
        adjMvt.setId(301L);
        adjMvt.setBatchId(100L);
        adjMvt.setBatchNumber("BAT-ADJ-NEG");
        adjMvt.setMovementType(StockMovementType.STOCK_ADJUSTMENT);
        adjMvt.setMaterialName("API Paracetamol");
        adjMvt.setQuantityDelta(new BigDecimal("-100.0000"));
        adjMvt.setUnit("kg");
        dossier.setMaterialMovements(List.of(adjMvt));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Record existence: No batch-specific issue record found")
                .contains("Quantity requirement unfulfilled")
                .contains("Issued: 0 kg");
    }

    @Test
    @DisplayName("31. GOODS_RECEIPT does not count as material issued")
    void testGoodsReceiptDoesNotCountAsIssued() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-GR-POS", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto grMvt = new StockMovementResponseDto();
        grMvt.setId(302L);
        grMvt.setBatchId(100L);
        grMvt.setBatchNumber("BAT-GR-POS");
        grMvt.setMovementType(StockMovementType.GOODS_RECEIPT);
        grMvt.setMaterialName("API Paracetamol");
        grMvt.setQuantityDelta(new BigDecimal("100.0000"));
        grMvt.setUnit("kg");
        dossier.setMaterialMovements(List.of(grMvt));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Record existence: No batch-specific issue record found")
                .doesNotContain("reversal");
    }

    @Test
    @DisplayName("32. Positive STOCK_ADJUSTMENT does not trigger invalid issue reversal safeguard")
    void testPositiveStockAdjustmentDoesNotTriggerReversalSafeguard() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-ADJ-POS", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto posAdj = new StockMovementResponseDto();
        posAdj.setId(303L);
        posAdj.setBatchId(100L);
        posAdj.setBatchNumber("BAT-ADJ-POS");
        posAdj.setMovementType(StockMovementType.STOCK_ADJUSTMENT);
        posAdj.setMaterialName("API Paracetamol");
        posAdj.setQuantityDelta(new BigDecimal("50.0000"));
        posAdj.setUnit("kg");
        dossier.setMaterialMovements(List.of(posAdj));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Record existence: No batch-specific issue record found")
                .doesNotContain("reversal");
    }

    @Test
    @DisplayName("33. Positive MATERIAL_ISSUE remains UNKNOWN with indeterminate net quantity")
    void testPositiveMaterialIssueRemainsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-ISS-POS", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto posIssue = new StockMovementResponseDto();
        posIssue.setId(304L);
        posIssue.setBatchId(100L);
        posIssue.setBatchNumber("BAT-ISS-POS");
        posIssue.setMovementType(StockMovementType.MATERIAL_ISSUE);
        posIssue.setMaterialName("API Paracetamol");
        posIssue.setQuantityDelta(new BigDecimal("100.0000"));
        posIssue.setUnit("kg");
        dossier.setMaterialMovements(List.of(posIssue));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity fulfilment indeterminate")
                .contains("reversal");
    }

    @Test
    @DisplayName("34. Missing movement unit with one requirement yields UNKNOWN with dataInsufficient = true")
    void testMissingMovementUnitWithOneRequirementYieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-NO-MVT-UNIT", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(305L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-NO-MVT-UNIT");
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialName("API Paracetamol");
        movement.setQuantityDelta(new BigDecimal("-100.0000"));
        movement.setUnit(null); // Missing movement unit
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity fulfilment indeterminate")
                .contains("movement record lacks unit");
    }

    @Test
    @DisplayName("35. Missing requirement unit with known movement unit yields UNKNOWN with dataInsufficient = true")
    void testMissingRequirementUnitWithKnownMovementUnitYieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-NO-REQ-UNIT", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit(null); // Missing requirement unit
        req.setInventoryUnit(null);
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(306L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-NO-REQ-UNIT");
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialName("API Paracetamol");
        movement.setQuantityDelta(new BigDecimal("-100.0000"));
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity fulfilment indeterminate")
                .contains("requirement record lacks unit");
    }

    @Test
    @DisplayName("36. Both requirement and movement units missing yields UNKNOWN with dataInsufficient = true")
    void testBothUnitsMissingYieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-BOTH-NO-UNIT", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit(null);
        req.setInventoryUnit(null);
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(307L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-BOTH-NO-UNIT");
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialName("API Paracetamol");
        movement.setQuantityDelta(new BigDecimal("-100.0000"));
        movement.setUnit(null);
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity fulfilment indeterminate")
                .contains("both requirement and movement units are missing; measurement unit cannot be established");
    }

    @Test
    @DisplayName("37. Compatible case-insensitive units pass and reconcile to PRESENT")
    void testCompatibleCaseInsensitiveUnitsPass() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CASE-UNITS", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(308L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-CASE-UNITS");
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialName("API Paracetamol");
        movement.setQuantityDelta(new BigDecimal("-100.0000"));
        movement.setUnit("KG"); // Uppercase KG matching lowercase kg
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity fulfilment established")
                .contains("Required: 100.0000 kg, Issued: 100.0000 kg");
    }

    @Test
    @DisplayName("38. Duplicate requirement rows for same material aggregate required quantities")
    void testDuplicateRequirementRowsForSameMaterialAggregateQuantities() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-DUP-REQ-AGG", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req1 = new OrderMaterialRequirementDto();
        req1.setRawMaterialId(55L);
        req1.setRecipeMaterialName("API Paracetamol");
        req1.setQuantityPerBatch(new BigDecimal("50.0000"));
        req1.setTotalRequiredQuantity(new BigDecimal("50.0000"));
        req1.setRecipeUnit("kg");

        OrderMaterialRequirementDto req2 = new OrderMaterialRequirementDto();
        req2.setRawMaterialId(55L);
        req2.setRecipeMaterialName("API Paracetamol");
        req2.setQuantityPerBatch(new BigDecimal("50.0000"));
        req2.setTotalRequiredQuantity(new BigDecimal("50.0000"));
        req2.setRecipeUnit("kg");

        dossier.setMaterialRequirements(List.of(req1, req2));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(309L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-DUP-REQ-AGG");
        movement.setRawMaterialId(55L);
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialName("API Paracetamol");
        movement.setQuantityDelta(new BigDecimal("-100.0000"));
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity fulfilment established")
                .contains("Required: 100.0000 kg, Issued: 100.0000 kg");
    }

    @Test
    @DisplayName("39. One movement cannot satisfy two duplicate requirement rows independently")
    void testOneMovementCannotSatisfyTwoDuplicateRequirementRowsIndependently() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-DUP-REQ-PARTIAL", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req1 = new OrderMaterialRequirementDto();
        req1.setRawMaterialId(55L);
        req1.setRecipeMaterialName("API Paracetamol");
        req1.setQuantityPerBatch(new BigDecimal("50.0000"));
        req1.setTotalRequiredQuantity(new BigDecimal("50.0000"));
        req1.setRecipeUnit("kg");

        OrderMaterialRequirementDto req2 = new OrderMaterialRequirementDto();
        req2.setRawMaterialId(55L);
        req2.setRecipeMaterialName("API Paracetamol");
        req2.setQuantityPerBatch(new BigDecimal("50.0000"));
        req2.setTotalRequiredQuantity(new BigDecimal("50.0000"));
        req2.setRecipeUnit("kg");

        dossier.setMaterialRequirements(List.of(req1, req2));

        // Only 50 kg issued (aggregating requirement to 100 kg means 50 kg is partial)
        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(310L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-DUP-REQ-PARTIAL");
        movement.setRawMaterialId(55L);
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialName("API Paracetamol");
        movement.setQuantityDelta(new BigDecimal("-50.0000"));
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity requirement unfulfilled")
                .contains("Required: 100.0000 kg, Issued: 50.0000 kg");
    }

    @Test
    @DisplayName("40. Distinct raw material IDs remain separate even when names match")
    void testDistinctRawMaterialIdsRemainSeparateEvenWhenNamesMatch() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-DIFF-IDS", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req1 = new OrderMaterialRequirementDto();
        req1.setRawMaterialId(10L);
        req1.setRecipeMaterialName("Sodium Chloride");
        req1.setQuantityPerBatch(new BigDecimal("50.0000"));
        req1.setTotalRequiredQuantity(new BigDecimal("50.0000"));
        req1.setRecipeUnit("kg");

        OrderMaterialRequirementDto req2 = new OrderMaterialRequirementDto();
        req2.setRawMaterialId(20L); // Different raw material ID
        req2.setRecipeMaterialName("Sodium Chloride"); // Same name
        req2.setQuantityPerBatch(new BigDecimal("50.0000"));
        req2.setTotalRequiredQuantity(new BigDecimal("50.0000"));
        req2.setRecipeUnit("kg");

        dossier.setMaterialRequirements(List.of(req1, req2));

        // Movement issued only to rawMaterialId 10L
        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(311L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-DIFF-IDS");
        movement.setRawMaterialId(10L);
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialName("Sodium Chloride");
        movement.setQuantityDelta(new BigDecimal("-50.0000"));
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        // rawMaterialId 20L has 0 issued, so overall requirement is unfulfilled
        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity requirement unfulfilled")
                .contains("Issued: 0 kg");
    }

    @Test
    @DisplayName("41. Duplicate rows with incompatible units yield UNKNOWN with dataInsufficient = true")
    void testDuplicateRowsWithIncompatibleUnitsYieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-DUP-CONFLICT-UNITS", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req1 = new OrderMaterialRequirementDto();
        req1.setRawMaterialId(55L);
        req1.setRecipeMaterialName("Purified Solvent");
        req1.setQuantityPerBatch(new BigDecimal("50.0000"));
        req1.setTotalRequiredQuantity(new BigDecimal("50.0000"));
        req1.setRecipeUnit("kg");

        OrderMaterialRequirementDto req2 = new OrderMaterialRequirementDto();
        req2.setRawMaterialId(55L);
        req2.setRecipeMaterialName("Purified Solvent");
        req2.setQuantityPerBatch(new BigDecimal("30.0000"));
        req2.setTotalRequiredQuantity(new BigDecimal("30.0000"));
        req2.setRecipeUnit("L"); // Incompatible unit with kg

        dossier.setMaterialRequirements(List.of(req1, req2));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(312L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-DUP-CONFLICT-UNITS");
        movement.setRawMaterialId(55L);
        movement.setMovementType(StockMovementType.MATERIAL_ISSUE);
        movement.setMaterialName("Purified Solvent");
        movement.setQuantityDelta(new BigDecimal("-80.0000"));
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity fulfilment indeterminate")
                .contains("conflicting units");
    }

    @Test
    @DisplayName("42. Distinct movements with missing identifiers are not accidentally collapsed")
    void testDistinctMovementsWithMissingIdentifiersNotAccidentallyCollapsed() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-NULL-ID-MVTS", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        // Two distinct DTO instances with null id and null movementNumber
        StockMovementResponseDto m1 = new StockMovementResponseDto();
        m1.setId(null);
        m1.setMovementNumber(null);
        m1.setBatchId(100L);
        m1.setBatchNumber("BAT-NULL-ID-MVTS");
        m1.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m1.setMaterialName("API Paracetamol");
        m1.setQuantityDelta(new BigDecimal("-50.0000"));
        m1.setUnit("kg");

        StockMovementResponseDto m2 = new StockMovementResponseDto();
        m2.setId(null);
        m2.setMovementNumber(null);
        m2.setBatchId(100L);
        m2.setBatchNumber("BAT-NULL-ID-MVTS");
        m2.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m2.setMaterialName("API Paracetamol");
        m2.setQuantityDelta(new BigDecimal("-50.0000"));
        m2.setUnit("kg");

        dossier.setMaterialMovements(List.of(m1, m2));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("Record existence: 2 batch-specific issue record(s) found")
                .contains("Quantity fulfilment established")
                .contains("Required: 100.0000 kg, Issued: 100.0000 kg");
    }

    @Test
    @DisplayName("43. Test A: Unknown movement type with unmatched material identity yields UNKNOWN")
    void testUnknownMovementTypeWithUnmatchedMaterialYieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-UNKNOWN-UNMATCHED", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        // Matching movement: MATERIAL_ISSUE, -100 kg
        StockMovementResponseDto m1 = new StockMovementResponseDto();
        m1.setId(401L);
        m1.setBatchId(100L);
        m1.setBatchNumber("BAT-UNKNOWN-UNMATCHED");
        m1.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m1.setMaterialName("API Paracetamol");
        m1.setQuantityDelta(new BigDecimal("-100.0000"));
        m1.setUnit("kg");

        // Additional batch-attributed movement: Excipient X, null movement type
        StockMovementResponseDto m2 = new StockMovementResponseDto();
        m2.setId(402L);
        m2.setBatchId(100L);
        m2.setBatchNumber("BAT-UNKNOWN-UNMATCHED");
        m2.setMovementType(null); // Unknown movement type
        m2.setMaterialName("Excipient X");
        m2.setQuantityDelta(new BigDecimal("-20.0000"));
        m2.setUnit("kg");

        dossier.setMaterialMovements(List.of(m1, m2));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity fulfilment indeterminate")
                .contains("movement record has null or unknown movement type; cannot verify as MATERIAL_ISSUE");
    }

    @Test
    @DisplayName("44. Test B: Recognized MATERIAL_ISSUE outside BOM yields UNKNOWN")
    void testRecognizedMaterialIssueOutsideBomYieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-ISSUE-OUTSIDE-BOM", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        // Matching movement: MATERIAL_ISSUE, -100 kg
        StockMovementResponseDto m1 = new StockMovementResponseDto();
        m1.setId(403L);
        m1.setBatchId(100L);
        m1.setBatchNumber("BAT-ISSUE-OUTSIDE-BOM");
        m1.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m1.setMaterialName("API Paracetamol");
        m1.setQuantityDelta(new BigDecimal("-100.0000"));
        m1.setUnit("kg");

        // Additional batch-attributed movement: Excipient X, MATERIAL_ISSUE, valid quantity
        StockMovementResponseDto m2 = new StockMovementResponseDto();
        m2.setId(404L);
        m2.setBatchId(100L);
        m2.setBatchNumber("BAT-ISSUE-OUTSIDE-BOM");
        m2.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m2.setMaterialName("Excipient X");
        m2.setQuantityDelta(new BigDecimal("-25.0000"));
        m2.setUnit("kg");

        dossier.setMaterialMovements(List.of(m1, m2));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity fulfilment indeterminate")
                .contains("batch issue movements recorded for materials not present in BOM requirements");
    }

    @Test
    @DisplayName("45. Null movement type matching a BOM requirement yields UNKNOWN")
    void testNullMovementTypeMatchingBomRequirementYieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-UNKNOWN-MATCHING", BatchStatus.COMPLETED);
        dossier.setEndTime(LocalDateTime.now().minusHours(1));

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto movement = new StockMovementResponseDto();
        movement.setId(405L);
        movement.setBatchId(100L);
        movement.setBatchNumber("BAT-UNKNOWN-MATCHING");
        movement.setMovementType(null); // Unknown movement type matching BOM requirement
        movement.setMaterialName("API Paracetamol");
        movement.setQuantityDelta(new BigDecimal("-100.0000"));
        movement.setUnit("kg");
        dossier.setMaterialMovements(List.of(movement));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity fulfilment indeterminate")
                .contains("movement record has null or unknown movement type; cannot verify as MATERIAL_ISSUE");
    }

    // =========================================================================
    // Phase 4F.2.5 CANCELLED Batch Historical Evidence Preservation Tests
    // =========================================================================

    @Test
    @DisplayName("46. Scenario A: CANCELLED before production start, with no records")
    void testScenarioA_CancelledBeforeProductionStart_NoRecords() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CANCELLED-A", BatchStatus.CANCELLED);
        dossier.setStartTime(null);
        dossier.setEndTime(null);
        dossier.setActualYield(null);
        dossier.setExecutionSteps(List.of());
        dossier.setQualityChecks(List.of());
        dossier.setMaterialMovements(List.of());
        dossier.setMaterialRequirements(List.of());

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);

        RecordAssessmentDto startAssessment = findAssessment(assessments, "Manufacturing Start Timestamp");
        assertThat(startAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);
        assertThat(startAssessment.getExplanation()).contains("Batch was cancelled prior to execution initiation; production never started.");

        RecordAssessmentDto endAssessment = findAssessment(assessments, "Manufacturing Completion Timestamp");
        assertThat(endAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);
        assertThat(endAssessment.getDataInsufficient()).isFalse();
        assertThat(endAssessment.getExplanation())
                .contains("The batch was cancelled before successful production completion; a manufacturing completion timestamp is not applicable.");

        RecordAssessmentDto yieldAssessment = findAssessment(assessments, "Actual Yield Quantity");
        assertThat(yieldAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);

        RecordAssessmentDto stepAssessment = findAssessment(assessments, "eBR Execution Steps");
        assertThat(stepAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);

        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");
        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);

        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");
        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);

        List<String> genuineGaps = classifier.extractGenuinelyMissingGaps(assessments);
        assertThat(genuineGaps).isEmpty();
    }

    @Test
    @DisplayName("47. Scenario B: CANCELLED after production start, with start timestamp")
    void testScenarioB_CancelledAfterProductionStart_WithTimestamps() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CANCELLED-B", BatchStatus.CANCELLED);
        LocalDateTime start = LocalDateTime.now().minusHours(3);
        LocalDateTime termination = LocalDateTime.now().minusHours(1);
        dossier.setStartTime(start);
        dossier.setEndTime(termination);

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);

        RecordAssessmentDto startAssessment = findAssessment(assessments, "Manufacturing Start Timestamp");
        assertThat(startAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(startAssessment.getExplanation())
                .contains("Recorded start time: " + start)
                .contains("marks execution initiation prior to cancellation");

        RecordAssessmentDto endAssessment = findAssessment(assessments, "Manufacturing Completion Timestamp");
        assertThat(endAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(endAssessment.getDataInsufficient()).isTrue();
        assertThat(endAssessment.getExplanation())
                .contains("Batch is marked CANCELLED but contains a manufacturing completion timestamp (" + termination + ")")
                .contains("inconsistent record state requiring investigation")
                .contains("completion timestamp cannot be reinterpreted as a cancellation timestamp");

        List<String> genuineGaps = classifier.extractGenuinelyMissingGaps(assessments);
        assertThat(genuineGaps).isEmpty();
    }

    @Test
    @DisplayName("48. Scenario C: CANCELLED after two of several eBR steps have been completed")
    void testScenarioC_CancelledAfterPartialEbrExecution() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CANCELLED-C", BatchStatus.CANCELLED);
        dossier.setStartTime(LocalDateTime.now().minusHours(4));

        BatchExecutionStepResponseDto s1 = new BatchExecutionStepResponseDto();
        s1.setId(1L);
        s1.setStepNumber(1);
        s1.setOperationName("Dispense Materials");
        s1.setStatus(ExecutionStepStatus.COMPLETED);
        s1.setExecutedBy("operator1");
        s1.setCompletedAt(LocalDateTime.now().minusHours(3));

        BatchExecutionStepResponseDto s2 = new BatchExecutionStepResponseDto();
        s2.setId(2L);
        s2.setStepNumber(2);
        s2.setOperationName("Mix Granulation");
        s2.setStatus(ExecutionStepStatus.COMPLETED);
        s2.setExecutedBy("operator2");
        s2.setCompletedAt(LocalDateTime.now().minusHours(2));

        BatchExecutionStepResponseDto s3 = new BatchExecutionStepResponseDto();
        s3.setId(3L);
        s3.setStepNumber(3);
        s3.setOperationName("Tablet Compression");
        s3.setStatus(ExecutionStepStatus.PENDING);

        dossier.setExecutionSteps(List.of(s1, s2, s3));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto stepAssessment = findAssessment(assessments, "eBR Execution Steps");

        assertThat(stepAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(stepAssessment.getDataInsufficient()).isFalse();
        assertThat(stepAssessment.getExplanation())
                .contains("3 execution step(s) recorded prior to batch cancellation (2 completed, 0 in-progress, 1 pending)")
                .contains("standard completion review not applicable");
    }

    @Test
    @DisplayName("49. Scenario D: CANCELLED after material issue movements have been recorded")
    void testScenarioD_CancelledAfterMaterialIssueRecorded() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CANCELLED-D", BatchStatus.CANCELLED);

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto m1 = new StockMovementResponseDto();
        m1.setId(501L);
        m1.setBatchId(100L);
        m1.setBatchNumber("BAT-CANCELLED-D");
        m1.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m1.setMaterialName("API Paracetamol");
        m1.setQuantityDelta(new BigDecimal("-100.0000"));
        m1.setUnit("kg");
        dossier.setMaterialMovements(List.of(m1));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("All required materials issued prior to cancellation")
                .contains("Batch was cancelled prior to completion; material issue records are preserved as historical evidence; not inferred as consumed");
    }

    @Test
    @DisplayName("50. Scenario E: CANCELLED after QC PASS and QC FAIL results have been recorded")
    void testScenarioE_CancelledAfterQcPassAndFailRecorded() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CANCELLED-E", BatchStatus.CANCELLED);

        QualityCheckResponseDto qc1 = new QualityCheckResponseDto();
        qc1.setId(1L);
        qc1.setTestName("Appearance");
        qc1.setResult(QualityCheckResult.PASS);
        qc1.setMeasuredValue("Clear Liquid");
        qc1.setInspectorName("qc_analyst1");

        QualityCheckResponseDto qc2 = new QualityCheckResponseDto();
        qc2.setId(2L);
        qc2.setTestName("Assay Potency");
        qc2.setResult(QualityCheckResult.FAIL);
        qc2.setMeasuredValue("88.5%");
        qc2.setInspectorName("qc_analyst2");

        dossier.setQualityChecks(List.of(qc1, qc2));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");

        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(qcAssessment.getDataInsufficient()).isFalse();
        assertThat(qcAssessment.getExplanation())
                .contains("2 inspection(s) recorded prior to batch cancellation (1 PASS, 1 FAIL, 0 PENDING)")
                .contains("evaluation reflects historical testing performed prior to cancellation");
    }

    @Test
    @DisplayName("51. Scenario F: CANCELLED after partial execution and partial material issue")
    void testScenarioF_CancelledAfterPartialExecutionAndPartialMaterialIssue() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CANCELLED-F", BatchStatus.CANCELLED);
        dossier.setStartTime(LocalDateTime.now().minusHours(3));

        BatchExecutionStepResponseDto s1 = new BatchExecutionStepResponseDto();
        s1.setId(10L);
        s1.setStepNumber(1);
        s1.setOperationName("Dispensing");
        s1.setStatus(ExecutionStepStatus.COMPLETED);
        s1.setExecutedBy("operator1");
        s1.setCompletedAt(LocalDateTime.now().minusHours(2));
        dossier.setExecutionSteps(List.of(s1));

        OrderMaterialRequirementDto req1 = new OrderMaterialRequirementDto();
        req1.setRecipeMaterialName("API Paracetamol");
        req1.setQuantityPerBatch(new BigDecimal("100.0000"));
        req1.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req1.setRecipeUnit("kg");

        OrderMaterialRequirementDto req2 = new OrderMaterialRequirementDto();
        req2.setRecipeMaterialName("Binder Starch");
        req2.setQuantityPerBatch(new BigDecimal("20.0000"));
        req2.setTotalRequiredQuantity(new BigDecimal("20.0000"));
        req2.setRecipeUnit("kg");

        dossier.setMaterialRequirements(List.of(req1, req2));

        StockMovementResponseDto m1 = new StockMovementResponseDto();
        m1.setId(601L);
        m1.setBatchId(100L);
        m1.setBatchNumber("BAT-CANCELLED-F");
        m1.setMovementType(StockMovementType.MATERIAL_ISSUE);
        m1.setMaterialName("API Paracetamol");
        m1.setQuantityDelta(new BigDecimal("-100.0000"));
        m1.setUnit("kg");
        dossier.setMaterialMovements(List.of(m1));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);

        RecordAssessmentDto stepAssessment = findAssessment(assessments, "eBR Execution Steps");
        assertThat(stepAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);

        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");
        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Partial material issue recorded prior to cancellation")
                .contains("Binder Starch")
                .contains("Batch was cancelled prior to completion; material issue records are preserved as historical evidence; not inferred as consumed");
    }

    @Test
    @DisplayName("52. Scenario G: CANCELLED with existing records but missing expected historical information")
    void testScenarioG_CancelledWithGapsInExistingRecords() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CANCELLED-G", BatchStatus.CANCELLED);

        // Step 1 completed without operator sign-off
        BatchExecutionStepResponseDto s1 = new BatchExecutionStepResponseDto();
        s1.setId(11L);
        s1.setStepNumber(1);
        s1.setOperationName("Dispensing");
        s1.setStatus(ExecutionStepStatus.COMPLETED);
        s1.setExecutedBy(null); // missing sign-off
        s1.setCompletedAt(LocalDateTime.now().minusHours(2));
        dossier.setExecutionSteps(List.of(s1));

        // QC check without measured value
        QualityCheckResponseDto qc1 = new QualityCheckResponseDto();
        qc1.setId(12L);
        qc1.setTestName("pH");
        qc1.setResult(QualityCheckResult.PASS);
        qc1.setMeasuredValue(null); // missing measured value
        qc1.setInspectorName("analyst1");
        dossier.setQualityChecks(List.of(qc1));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);

        RecordAssessmentDto stepAssessment = findAssessment(assessments, "eBR Execution Steps");
        assertThat(stepAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(stepAssessment.getDataInsufficient()).isTrue();
        assertThat(stepAssessment.getExplanation())
                .contains("documentation gap(s) in historical records")
                .contains("missing operator sign-off");

        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");
        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(qcAssessment.getDataInsufficient()).isTrue();
        assertThat(qcAssessment.getExplanation())
                .contains("documentation gap(s) in historical records")
                .contains("missing measured value");
    }

    @Test
    @DisplayName("53. Scenario H: CANCELLED with no evidence that execution ever began")
    void testScenarioH_CancelledWithNoEvidenceOfExecution() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CANCELLED-H", BatchStatus.CANCELLED);
        dossier.setStartTime(null);
        dossier.setEndTime(null);
        dossier.setActualYield(null);
        dossier.setExecutionSteps(null);
        dossier.setQualityChecks(null);
        dossier.setMaterialMovements(null);
        dossier.setMaterialRequirements(null);

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);

        for (RecordAssessmentDto a : assessments) {
            assertThat(a.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);
            assertThat(a.getDataInsufficient()).isFalse();
        }

        List<String> gaps = classifier.extractGenuinelyMissingGaps(assessments);
        assertThat(gaps).isEmpty();
    }

    // =========================================================================
    // Phase 4F.2.5.2 CANCELLED Batch Completion Timestamp Semantics Regression Tests
    // =========================================================================

    @Test
    @DisplayName("54. CANCELLED batch with null endTime yields NOT_APPLICABLE while start time and partial yield are preserved")
    void testCancelledBatch_NullEndTime_YieldsNotApplicable_WithPreservedStartAndYield() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CANCELLED-NULL-END", BatchStatus.CANCELLED);
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        dossier.setStartTime(start);
        dossier.setEndTime(null);
        dossier.setActualYield(new BigDecimal("150.5000"));
        dossier.setBatchUnit("kg");

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);

        // Completion timestamp: NOT_APPLICABLE
        RecordAssessmentDto endAssessment = findAssessment(assessments, "Manufacturing Completion Timestamp");
        assertThat(endAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);
        assertThat(endAssessment.getDataInsufficient()).isFalse();
        assertThat(endAssessment.getExplanation())
                .contains("The batch was cancelled before successful production completion; a manufacturing completion timestamp is not applicable.");

        // Start timestamp preserved: PRESENT
        RecordAssessmentDto startAssessment = findAssessment(assessments, "Manufacturing Start Timestamp");
        assertThat(startAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(startAssessment.getDataInsufficient()).isFalse();
        assertThat(startAssessment.getExplanation()).contains("Recorded start time: " + start);

        // Actual yield preserved: PRESENT
        RecordAssessmentDto yieldAssessment = findAssessment(assessments, "Actual Yield Quantity");
        assertThat(yieldAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(yieldAssessment.getDataInsufficient()).isFalse();
        assertThat(yieldAssessment.getExplanation()).contains("150.5000 kg");
    }

    @Test
    @DisplayName("55. CANCELLED batch with non-null endTime yields UNKNOWN and dataInsufficient=true without reinterpreting as cancellation time")
    void testCancelledBatch_NonNullEndTime_YieldsUnknownAndDataInsufficient() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CANCELLED-NON-NULL-END", BatchStatus.CANCELLED);
        LocalDateTime endTime = LocalDateTime.now().minusHours(1);
        dossier.setEndTime(endTime);

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);

        RecordAssessmentDto endAssessment = findAssessment(assessments, "Manufacturing Completion Timestamp");
        assertThat(endAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(endAssessment.getDataInsufficient()).isTrue();
        assertThat(endAssessment.getExplanation())
                .contains("Batch is marked CANCELLED but contains a manufacturing completion timestamp (" + endTime + ")")
                .contains("inconsistent record state requiring investigation")
                .contains("completion timestamp cannot be reinterpreted as a cancellation timestamp")
                .doesNotContain("Recorded termination/end time");
    }

    @Test
    @DisplayName("56. COMPLETED batch with non-null endTime remains PRESENT and dataInsufficient=false")
    void testCompletedBatch_NonNullEndTime_RemainsPresent() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-COMPLETED-END", BatchStatus.COMPLETED);
        LocalDateTime endTime = LocalDateTime.now().minusMinutes(30);
        dossier.setEndTime(endTime);

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);

        RecordAssessmentDto endAssessment = findAssessment(assessments, "Manufacturing Completion Timestamp");
        assertThat(endAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(endAssessment.getDataInsufficient()).isFalse();
        assertThat(endAssessment.getExplanation()).contains("Recorded completion time: " + endTime);
    }

    @Test
    @DisplayName("57. PLANNED and IN_PROGRESS completion timestamp behavior remains unchanged (NOT_YET_EXPECTED)")
    void testPlannedAndInProgress_CompletionTimestamp_RemainsNotYetExpected() {
        // PLANNED
        BatchTraceabilityDossierDto plannedDossier = createBaseDossier("BAT-PLAN-END", BatchStatus.PLANNED);
        List<RecordAssessmentDto> plannedAssessments = classifier.classifyRecords(plannedDossier);
        RecordAssessmentDto plannedEnd = findAssessment(plannedAssessments, "Manufacturing Completion Timestamp");
        assertThat(plannedEnd.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_YET_EXPECTED);
        assertThat(plannedEnd.getDataInsufficient()).isFalse();

        // IN_PROGRESS
        BatchTraceabilityDossierDto inProgDossier = createBaseDossier("BAT-INPROG-END", BatchStatus.IN_PROGRESS);
        List<RecordAssessmentDto> inProgAssessments = classifier.classifyRecords(inProgDossier);
        RecordAssessmentDto inProgEnd = findAssessment(inProgAssessments, "Manufacturing Completion Timestamp");
        assertThat(inProgEnd.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_YET_EXPECTED);
        assertThat(inProgEnd.getDataInsufficient()).isFalse();
    }

    // =========================================================================
    // Phase 4F.2.5.3 CANCELLED Batch Material Movement Integrity Tests
    // =========================================================================

    @Test
    @DisplayName("58. CANCELLED batch with no movements yields NOT_APPLICABLE")
    void testCancelledBatch_NoMovements_YieldsNotApplicable() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-NO-MVT", BatchStatus.CANCELLED);
        dossier.setMaterialMovements(List.of());

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("Batch was cancelled; no material issue movements were recorded prior to cancellation");
    }

    @Test
    @DisplayName("59. CANCELLED batch with only known GOODS_RECEIPT yields NOT_APPLICABLE")
    void testCancelledBatch_OnlyGoodsReceipt_YieldsNotApplicable() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-GR-ONLY", BatchStatus.CANCELLED);

        StockMovementResponseDto gr = new StockMovementResponseDto();
        gr.setId(701L);
        gr.setBatchId(100L);
        gr.setBatchNumber("BAT-CAN-GR-ONLY");
        gr.setMovementType(StockMovementType.GOODS_RECEIPT);
        gr.setMaterialName("API Paracetamol");
        gr.setQuantityDelta(new BigDecimal("100.0000"));
        gr.setUnit("kg");
        dossier.setMaterialMovements(List.of(gr));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("Batch was cancelled; no material issue movements were recorded prior to cancellation");
    }

    @Test
    @DisplayName("60. CANCELLED batch with only known STOCK_ADJUSTMENT yields NOT_APPLICABLE")
    void testCancelledBatch_OnlyStockAdjustment_YieldsNotApplicable() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-ADJ-ONLY", BatchStatus.CANCELLED);

        StockMovementResponseDto adj = new StockMovementResponseDto();
        adj.setId(702L);
        adj.setBatchId(100L);
        adj.setBatchNumber("BAT-CAN-ADJ-ONLY");
        adj.setMovementType(StockMovementType.STOCK_ADJUSTMENT);
        adj.setMaterialName("API Paracetamol");
        adj.setQuantityDelta(new BigDecimal("-10.0000"));
        adj.setUnit("kg");
        dossier.setMaterialMovements(List.of(adj));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("Batch was cancelled; no material issue movements were recorded prior to cancellation");
    }

    @Test
    @DisplayName("61. CANCELLED batch with a null movement type and no MATERIAL_ISSUE yields UNKNOWN")
    void testCancelledBatch_NullMovementType_NoMaterialIssue_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-NULL-MVT", BatchStatus.CANCELLED);

        StockMovementResponseDto unknownMvt = new StockMovementResponseDto();
        unknownMvt.setId(703L);
        unknownMvt.setBatchId(100L);
        unknownMvt.setBatchNumber("BAT-CAN-NULL-MVT");
        unknownMvt.setMovementType(null); // Null movement type
        unknownMvt.setMaterialName("API Paracetamol");
        unknownMvt.setQuantityDelta(new BigDecimal("-50.0000"));
        unknownMvt.setUnit("kg");
        dossier.setMaterialMovements(List.of(unknownMvt));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Batch was cancelled, but batch-attributed movement records contain an unknown or unsupported movement type")
                .contains("material issue history cannot be reliably determined")
                .contains("Material consumption cannot be established from the available movement records.")
                .doesNotContain("Consumed: tracked via step execution / yield reconciliation upon completion");
    }

    @Test
    @DisplayName("62. CANCELLED batch with an unsupported movement type and no MATERIAL_ISSUE yields UNKNOWN")
    void testCancelledBatch_UnsupportedMovementType_NoMaterialIssue_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-UNSUPPORTED-MVT", BatchStatus.CANCELLED);

        StockMovementResponseDto unsupportedMvt = new StockMovementResponseDto();
        unsupportedMvt.setId(704L);
        unsupportedMvt.setBatchId(100L);
        unsupportedMvt.setBatchNumber("BAT-CAN-UNSUPPORTED-MVT");
        unsupportedMvt.setMovementType(null);
        unsupportedMvt.setMaterialName("Unknown Excipient");
        unsupportedMvt.setQuantityDelta(new BigDecimal("-15.0000"));
        unsupportedMvt.setUnit("kg");
        dossier.setMaterialMovements(List.of(unsupportedMvt));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("unknown or unsupported movement type")
                .contains("material issue history cannot be reliably determined")
                .contains("Material consumption cannot be established from the available movement records.")
                .doesNotContain("Consumed: tracked via step execution / yield reconciliation upon completion");
    }

    @Test
    @DisplayName("63. CANCELLED batch with valid MATERIAL_ISSUE and fulfilled requirements yields PRESENT")
    void testCancelledBatch_ValidMaterialIssue_Fulfilled_YieldsPresent() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-FULFILLED", BatchStatus.CANCELLED);

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto mvt = new StockMovementResponseDto();
        mvt.setId(705L);
        mvt.setBatchId(100L);
        mvt.setBatchNumber("BAT-CAN-FULFILLED");
        mvt.setMovementType(StockMovementType.MATERIAL_ISSUE);
        mvt.setMaterialName("API Paracetamol");
        mvt.setQuantityDelta(new BigDecimal("-100.0000"));
        mvt.setUnit("kg");
        dossier.setMaterialMovements(List.of(mvt));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(matAssessment.getDataInsufficient()).isFalse();
        assertThat(matAssessment.getExplanation())
                .contains("All required materials issued prior to cancellation")
                .contains("Batch was cancelled prior to completion; material issue records are preserved as historical evidence; not inferred as consumed");
    }

    @Test
    @DisplayName("64. CANCELLED batch with partial material issue yields UNKNOWN")
    void testCancelledBatch_PartialMaterialIssue_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-PARTIAL", BatchStatus.CANCELLED);

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto mvt = new StockMovementResponseDto();
        mvt.setId(706L);
        mvt.setBatchId(100L);
        mvt.setBatchNumber("BAT-CAN-PARTIAL");
        mvt.setMovementType(StockMovementType.MATERIAL_ISSUE);
        mvt.setMaterialName("API Paracetamol");
        mvt.setQuantityDelta(new BigDecimal("-40.0000")); // Only 40 of 100 kg issued
        mvt.setUnit("kg");
        dossier.setMaterialMovements(List.of(mvt));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Partial material issue recorded prior to cancellation")
                .contains("Required: 100.0000 kg, Issued: 40.0000 kg");
    }

    @Test
    @DisplayName("65. CANCELLED batch with missing units or incompatible units yields UNKNOWN")
    void testCancelledBatch_MissingOrIncompatibleUnits_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-UNIT-MISMATCH", BatchStatus.CANCELLED);

        OrderMaterialRequirementDto req = new OrderMaterialRequirementDto();
        req.setRecipeMaterialName("API Paracetamol");
        req.setQuantityPerBatch(new BigDecimal("100.0000"));
        req.setTotalRequiredQuantity(new BigDecimal("100.0000"));
        req.setRecipeUnit("kg");
        dossier.setMaterialRequirements(List.of(req));

        StockMovementResponseDto mvt = new StockMovementResponseDto();
        mvt.setId(707L);
        mvt.setBatchId(100L);
        mvt.setBatchNumber("BAT-CAN-UNIT-MISMATCH");
        mvt.setMovementType(StockMovementType.MATERIAL_ISSUE);
        mvt.setMaterialName("API Paracetamol");
        mvt.setQuantityDelta(new BigDecimal("-100.0000"));
        mvt.setUnit("g"); // Incompatible unit ('g' vs 'kg')
        dossier.setMaterialMovements(List.of(mvt));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto matAssessment = findAssessment(assessments, "Material Issue & Lot Traceability");

        assertThat(matAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(matAssessment.getDataInsufficient()).isTrue();
        assertThat(matAssessment.getExplanation())
                .contains("Quantity fulfilment indeterminate")
                .contains("unit mismatch between requirement (kg) and movement (g)")
                .contains("Note: Batch was cancelled; evaluation reflects material movements recorded prior to cancellation");
    }

    // =========================================================================
    // Phase 4F.2.5.4 CANCELLED Batch QC Evidence Classification Tests
    // =========================================================================

    @Test
    @DisplayName("66. CANCELLED batch with no QC checks yields NOT_APPLICABLE")
    void testCancelledBatch_NoQcChecks_YieldsNotApplicable() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-NO-QC", BatchStatus.CANCELLED);
        dossier.setQualityChecks(List.of());

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");

        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);
        assertThat(qcAssessment.getDataInsufficient()).isFalse();
        assertThat(qcAssessment.getExplanation())
                .contains("Batch was cancelled; no QC inspections were recorded prior to cancellation");
    }

    @Test
    @DisplayName("67. CANCELLED batch with PENDING check and null measured value preserves as PRESENT without flagging missing measurement")
    void testCancelledBatch_PendingCheck_NullMeasuredValue_YieldsPresent() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-QC-PEND", BatchStatus.CANCELLED);

        QualityCheckResponseDto qc = new QualityCheckResponseDto();
        qc.setId(801L);
        qc.setTestName("Assay Potency");
        qc.setResult(QualityCheckResult.PENDING);
        qc.setMeasuredValue(null); // Legitimate for PENDING check
        qc.setInspectorName("analyst1");
        dossier.setQualityChecks(List.of(qc));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");

        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(qcAssessment.getDataInsufficient()).isFalse();
        assertThat(qcAssessment.getExplanation())
                .contains("1 inspection(s) recorded prior to batch cancellation (0 PASS, 0 FAIL, 1 PENDING)")
                .contains("evaluation reflects historical testing performed prior to cancellation; not inferred as full release testing")
                .doesNotContain("missing measured value")
                .doesNotContain("documentation gap");
    }

    @Test
    @DisplayName("68. CANCELLED batch with valid PASS check yields PRESENT")
    void testCancelledBatch_ValidPassCheck_YieldsPresent() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-QC-PASS", BatchStatus.CANCELLED);

        QualityCheckResponseDto qc = new QualityCheckResponseDto();
        qc.setId(802L);
        qc.setTestName("Dissolution");
        qc.setResult(QualityCheckResult.PASS);
        qc.setMeasuredValue("98.2%");
        qc.setInspectorName("analyst1");
        dossier.setQualityChecks(List.of(qc));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");

        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(qcAssessment.getDataInsufficient()).isFalse();
        assertThat(qcAssessment.getExplanation())
                .contains("1 inspection(s) recorded prior to batch cancellation (1 PASS, 0 FAIL, 0 PENDING)");
    }

    @Test
    @DisplayName("69. CANCELLED batch with valid FAIL check yields PRESENT")
    void testCancelledBatch_ValidFailCheck_YieldsPresent() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-QC-FAIL", BatchStatus.CANCELLED);

        QualityCheckResponseDto qc = new QualityCheckResponseDto();
        qc.setId(803L);
        qc.setTestName("Microbial Limits");
        qc.setResult(QualityCheckResult.FAIL);
        qc.setMeasuredValue("Exceeded Limit");
        qc.setInspectorName("analyst2");
        dossier.setQualityChecks(List.of(qc));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");

        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(qcAssessment.getDataInsufficient()).isFalse();
        assertThat(qcAssessment.getExplanation())
                .contains("1 inspection(s) recorded prior to batch cancellation (0 PASS, 1 FAIL, 0 PENDING)");
    }

    @Test
    @DisplayName("70. CANCELLED batch with PASS and missing measured value yields UNKNOWN")
    void testCancelledBatch_PassCheck_MissingMeasuredValue_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-QC-PASS-NOMV", BatchStatus.CANCELLED);

        QualityCheckResponseDto qc = new QualityCheckResponseDto();
        qc.setId(804L);
        qc.setTestName("Hardness");
        qc.setResult(QualityCheckResult.PASS);
        qc.setMeasuredValue(null); // Missing required measured value for PASS
        qc.setInspectorName("analyst1");
        dossier.setQualityChecks(List.of(qc));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");

        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(qcAssessment.getDataInsufficient()).isTrue();
        assertThat(qcAssessment.getExplanation())
                .contains("documentation gap(s) in historical records")
                .contains("Test 'Hardness' (PASS) missing measured value");
    }

    @Test
    @DisplayName("71. CANCELLED batch with FAIL and missing measured value yields UNKNOWN")
    void testCancelledBatch_FailCheck_MissingMeasuredValue_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-QC-FAIL-NOMV", BatchStatus.CANCELLED);

        QualityCheckResponseDto qc = new QualityCheckResponseDto();
        qc.setId(805L);
        qc.setTestName("Friability");
        qc.setResult(QualityCheckResult.FAIL);
        qc.setMeasuredValue("   "); // Blank required measured value for FAIL
        qc.setInspectorName("analyst1");
        dossier.setQualityChecks(List.of(qc));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");

        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(qcAssessment.getDataInsufficient()).isTrue();
        assertThat(qcAssessment.getExplanation())
                .contains("documentation gap(s) in historical records")
                .contains("Test 'Friability' (FAIL) missing measured value");
    }

    @Test
    @DisplayName("72. CANCELLED batch with null result yields UNKNOWN")
    void testCancelledBatch_NullResult_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-QC-NULL-RES", BatchStatus.CANCELLED);

        QualityCheckResponseDto qc = new QualityCheckResponseDto();
        qc.setId(806L);
        qc.setTestName("Disintegration");
        qc.setResult(null); // Null result
        qc.setMeasuredValue("15 min");
        qc.setInspectorName("analyst1");
        dossier.setQualityChecks(List.of(qc));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");

        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(qcAssessment.getDataInsufficient()).isTrue();
        assertThat(qcAssessment.getExplanation())
                .contains("documentation gap(s) in historical records")
                .contains("Test 'Disintegration' has null or unsupported result status");
    }

    @Test
    @DisplayName("73. CANCELLED batch with an unsupported result yields UNKNOWN")
    void testCancelledBatch_UnsupportedResult_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-QC-UNSUPP-RES", BatchStatus.CANCELLED);

        QualityCheckResponseDto qc = new QualityCheckResponseDto();
        qc.setId(807L);
        qc.setTestName("Appearance");
        qc.setResult(null); // Represents unsupported or corrupted result state
        qc.setMeasuredValue("Passes visually");
        qc.setInspectorName("analyst1");
        dossier.setQualityChecks(List.of(qc));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");

        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(qcAssessment.getDataInsufficient()).isTrue();
        assertThat(qcAssessment.getExplanation())
                .contains("has null or unsupported result status");
    }

    @Test
    @DisplayName("74. CANCELLED batch with mixed PENDING and completed checks preserves actual state without inventing missing measurements")
    void testCancelledBatch_MixedPendingAndCompletedChecks_YieldsPresent() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-QC-MIXED", BatchStatus.CANCELLED);

        QualityCheckResponseDto qc1 = new QualityCheckResponseDto();
        qc1.setId(808L);
        qc1.setTestName("Identification HPLC");
        qc1.setResult(QualityCheckResult.PASS);
        qc1.setMeasuredValue("Conforms to reference standard");
        qc1.setInspectorName("analyst1");

        QualityCheckResponseDto qc2 = new QualityCheckResponseDto();
        qc2.setId(809L);
        qc2.setTestName("Uniformity of Dosage Units");
        qc2.setResult(QualityCheckResult.PENDING);
        qc2.setMeasuredValue(null); // Legitimate null for pending check
        qc2.setInspectorName("analyst2");

        dossier.setQualityChecks(List.of(qc1, qc2));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");

        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(qcAssessment.getDataInsufficient()).isFalse();
        assertThat(qcAssessment.getExplanation())
                .contains("2 inspection(s) recorded prior to batch cancellation (1 PASS, 0 FAIL, 1 PENDING)")
                .contains("evaluation reflects historical testing performed prior to cancellation; not inferred as full release testing")
                .doesNotContain("missing measured value");
    }

    @Test
    @DisplayName("75. CANCELLED batch with missing inspector identity yields UNKNOWN")
    void testCancelledBatch_MissingInspectorIdentity_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-QC-NO-INSP", BatchStatus.CANCELLED);

        QualityCheckResponseDto qc = new QualityCheckResponseDto();
        qc.setId(810L);
        qc.setTestName("Content Uniformity");
        qc.setResult(QualityCheckResult.PASS);
        qc.setMeasuredValue("99.4%");
        qc.setInspectorName(null); // Missing required inspector identity
        dossier.setQualityChecks(List.of(qc));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto qcAssessment = findAssessment(assessments, "Quality Control (QC) Inspections");

        assertThat(qcAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(qcAssessment.getDataInsufficient()).isTrue();
        assertThat(qcAssessment.getExplanation())
                .contains("documentation gap(s) in historical records")
                .contains("Test 'Content Uniformity' missing inspector sign-off");
    }

    // =========================================================================
    // Phase 4F.2.5.7: Preserve eBR Evidence for CANCELLED Batches Regression Tests
    // =========================================================================

    @Test
    @DisplayName("76. CANCELLED batch with completed steps preserves completed evidence without claiming entire eBR succeeded")
    void testCancelledBatch_CompletedSteps_PreservesCompletedEvidence() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-EBR-COMP", BatchStatus.CANCELLED);
        dossier.setStartTime(LocalDateTime.now().minusHours(4));

        BatchExecutionStepResponseDto s1 = new BatchExecutionStepResponseDto();
        s1.setId(101L);
        s1.setStepNumber(1);
        s1.setOperationName("Dispensing");
        s1.setStatus(ExecutionStepStatus.COMPLETED);
        s1.setActualValue("250.0 kg");
        s1.setObservations("Dispensed within tolerance");
        s1.setExecutedBy("operator_alice");
        s1.setStartedAt(LocalDateTime.now().minusHours(4));
        s1.setCompletedAt(LocalDateTime.now().minusHours(3));

        BatchExecutionStepResponseDto s2 = new BatchExecutionStepResponseDto();
        s2.setId(102L);
        s2.setStepNumber(2);
        s2.setOperationName("Granulation");
        s2.setStatus(ExecutionStepStatus.COMPLETED);
        s2.setActualValue("45.2 °C / 15 min");
        s2.setObservations("Granulation optimal");
        s2.setExecutedBy("operator_bob");
        s2.setStartedAt(LocalDateTime.now().minusHours(3));
        s2.setCompletedAt(LocalDateTime.now().minusHours(2));

        dossier.setExecutionSteps(List.of(s1, s2));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto stepAssessment = findAssessment(assessments, "eBR Execution Steps");

        assertThat(stepAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(stepAssessment.getDataInsufficient()).isFalse();
        assertThat(stepAssessment.getExplanation())
                .contains("All 2 execution step(s) were completed prior to batch cancellation with verified operator sign-offs and timestamps")
                .contains("execution records are preserved as historical evidence, not proof of successful overall batch completion");

        List<String> gaps = classifier.extractGenuinelyMissingGaps(assessments);
        assertThat(gaps).isEmpty();
    }

    @Test
    @DisplayName("77. CANCELLED batch with completed and pending steps preserves both without calling pending steps missing")
    void testCancelledBatch_CompletedAndPendingSteps_PreservesBothWithoutGaps() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-EBR-PEND", BatchStatus.CANCELLED);
        dossier.setStartTime(LocalDateTime.now().minusHours(5));

        BatchExecutionStepResponseDto s1 = new BatchExecutionStepResponseDto();
        s1.setId(201L);
        s1.setStepNumber(1);
        s1.setOperationName("Dispensing");
        s1.setStatus(ExecutionStepStatus.COMPLETED);
        s1.setActualValue("100.0 kg");
        s1.setExecutedBy("operator_charlie");
        s1.setStartedAt(LocalDateTime.now().minusHours(5));
        s1.setCompletedAt(LocalDateTime.now().minusHours(4));

        BatchExecutionStepResponseDto s2 = new BatchExecutionStepResponseDto();
        s2.setId(202L);
        s2.setStepNumber(2);
        s2.setOperationName("Blending");
        s2.setStatus(ExecutionStepStatus.PENDING);

        BatchExecutionStepResponseDto s3 = new BatchExecutionStepResponseDto();
        s3.setId(203L);
        s3.setStepNumber(3);
        s3.setOperationName("Compression");
        s3.setStatus(ExecutionStepStatus.PENDING);

        dossier.setExecutionSteps(List.of(s1, s2, s3));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto stepAssessment = findAssessment(assessments, "eBR Execution Steps");

        assertThat(stepAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(stepAssessment.getDataInsufficient()).isFalse();
        assertThat(stepAssessment.getExplanation())
                .contains("3 execution step(s) recorded prior to batch cancellation (1 completed, 0 in-progress, 2 pending)")
                .contains("unexecuted steps were not completed due to batch cancellation");

        List<String> gaps = classifier.extractGenuinelyMissingGaps(assessments);
        assertThat(gaps).isEmpty();
    }

    @Test
    @DisplayName("78. CANCELLED batch with IN_PROGRESS step preserves start timestamp and is not called completed")
    void testCancelledBatch_InProgressStep_PreservesStartTimestampWithoutCompletionClaim() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-EBR-INPROG", BatchStatus.CANCELLED);
        LocalDateTime stepStart = LocalDateTime.now().minusHours(2);
        dossier.setStartTime(LocalDateTime.now().minusHours(3));

        BatchExecutionStepResponseDto s1 = new BatchExecutionStepResponseDto();
        s1.setId(301L);
        s1.setStepNumber(1);
        s1.setOperationName("Dispensing");
        s1.setStatus(ExecutionStepStatus.COMPLETED);
        s1.setActualValue("50.0 kg");
        s1.setExecutedBy("operator_alice");
        s1.setStartedAt(LocalDateTime.now().minusHours(3));
        s1.setCompletedAt(stepStart);

        BatchExecutionStepResponseDto s2 = new BatchExecutionStepResponseDto();
        s2.setId(302L);
        s2.setStepNumber(2);
        s2.setOperationName("Granulation");
        s2.setStatus(ExecutionStepStatus.IN_PROGRESS);
        s2.setStartedAt(stepStart);
        s2.setCompletedAt(null);
        s2.setExecutedBy(null);

        dossier.setExecutionSteps(List.of(s1, s2));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto stepAssessment = findAssessment(assessments, "eBR Execution Steps");

        assertThat(stepAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.PRESENT);
        assertThat(stepAssessment.getDataInsufficient()).isFalse();
        assertThat(stepAssessment.getExplanation())
                .contains("2 execution step(s) recorded prior to batch cancellation (1 completed, 1 in-progress, 0 pending)")
                .doesNotContain("2 completed")
                .doesNotContain("All 2");

        List<String> gaps = classifier.extractGenuinelyMissingGaps(assessments);
        assertThat(gaps).isEmpty();
    }

    @Test
    @DisplayName("79. CANCELLED batch with completed step missing mandatory operator sign-off yields UNKNOWN")
    void testCancelledBatch_CompletedStepMissingSignOff_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-EBR-GAP-SIGN", BatchStatus.CANCELLED);
        dossier.setStartTime(LocalDateTime.now().minusHours(3));

        BatchExecutionStepResponseDto s1 = new BatchExecutionStepResponseDto();
        s1.setId(401L);
        s1.setStepNumber(1);
        s1.setOperationName("Dispensing");
        s1.setStatus(ExecutionStepStatus.COMPLETED);
        s1.setActualValue("50.0 kg");
        s1.setExecutedBy(null); // Missing required sign-off
        s1.setCompletedAt(LocalDateTime.now().minusHours(2));

        dossier.setExecutionSteps(List.of(s1));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto stepAssessment = findAssessment(assessments, "eBR Execution Steps");

        assertThat(stepAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(stepAssessment.getDataInsufficient()).isTrue();
        assertThat(stepAssessment.getExplanation())
                .contains("1 execution step(s) recorded prior to batch cancellation (1 completed, 0 in-progress, 0 pending)")
                .contains("1 documentation gap(s) in historical records")
                .contains("Step 1 missing operator sign-off");
    }

    @Test
    @DisplayName("80. CANCELLED batch with completed step missing mandatory completion timestamp yields UNKNOWN")
    void testCancelledBatch_CompletedStepMissingTimestamp_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-EBR-GAP-TIME", BatchStatus.CANCELLED);
        dossier.setStartTime(LocalDateTime.now().minusHours(3));

        BatchExecutionStepResponseDto s1 = new BatchExecutionStepResponseDto();
        s1.setId(501L);
        s1.setStepNumber(1);
        s1.setOperationName("Milling");
        s1.setStatus(ExecutionStepStatus.COMPLETED);
        s1.setExecutedBy("operator_dan");
        s1.setCompletedAt(null); // Missing required completion timestamp

        dossier.setExecutionSteps(List.of(s1));

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto stepAssessment = findAssessment(assessments, "eBR Execution Steps");

        assertThat(stepAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(stepAssessment.getDataInsufficient()).isTrue();
        assertThat(stepAssessment.getExplanation())
                .contains("1 documentation gap(s) in historical records")
                .contains("Step 1 missing completion timestamp");
    }

    @Test
    @DisplayName("81. CANCELLED batch with zero execution steps and null startTime yields NOT_APPLICABLE (cancelled while PLANNED)")
    void testCancelledBatch_ZeroSteps_NullStartTime_YieldsNotApplicable() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-EBR-NO-START", BatchStatus.CANCELLED);
        dossier.setStartTime(null);
        dossier.setExecutionSteps(List.of());

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto stepAssessment = findAssessment(assessments, "eBR Execution Steps");

        assertThat(stepAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.NOT_APPLICABLE);
        assertThat(stepAssessment.getDataInsufficient()).isFalse();
        assertThat(stepAssessment.getExplanation())
                .contains("Batch was cancelled prior to production initiation; execution steps were not initialized as manufacturing did not commence");
    }

    @Test
    @DisplayName("82. CANCELLED batch with zero execution steps and non-null startTime yields UNKNOWN (started but steps missing)")
    void testCancelledBatch_ZeroSteps_NonNullStartTime_YieldsUnknown() {
        BatchTraceabilityDossierDto dossier = createBaseDossier("BAT-CAN-EBR-START-NO-STEPS", BatchStatus.CANCELLED);
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        dossier.setStartTime(start);
        dossier.setExecutionSteps(List.of());

        List<RecordAssessmentDto> assessments = classifier.classifyRecords(dossier);
        RecordAssessmentDto stepAssessment = findAssessment(assessments, "eBR Execution Steps");

        assertThat(stepAssessment.getClassification()).isEqualTo(RecordLifecycleClassification.UNKNOWN);
        assertThat(stepAssessment.getDataInsufficient()).isTrue();
        assertThat(stepAssessment.getExplanation())
                .contains("Batch was started prior to cancellation (start timestamp: " + start + "), but zero execution steps are recorded in the electronic dossier; execution step history cannot be established");
    }

    private RecordAssessmentDto findAssessment(List<RecordAssessmentDto> assessments, String recordType) {
        return assessments.stream()
                .filter(a -> a.getRecordType().equalsIgnoreCase(recordType))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Assessment not found for recordType: " + recordType));
    }
}
