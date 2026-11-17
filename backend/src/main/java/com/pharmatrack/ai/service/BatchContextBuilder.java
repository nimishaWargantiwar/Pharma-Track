package com.pharmatrack.ai.service;

import com.pharmatrack.ai.dto.RecordAssessmentDto;
import com.pharmatrack.ai.entity.BatchAnalysisFocusArea;
import com.pharmatrack.ai.entity.BatchAnalysisMode;
import com.pharmatrack.dto.AiSourceReferenceDto;
import com.pharmatrack.dto.AuditLogResponseDto;
import com.pharmatrack.dto.BatchExecutionStepResponseDto;
import com.pharmatrack.dto.BatchQaReviewResponseDto;
import com.pharmatrack.dto.BatchTraceabilityDossierDto;
import com.pharmatrack.dto.CapaActionResponseDto;
import com.pharmatrack.dto.DeviationDetailDto;
import com.pharmatrack.dto.OrderMaterialRequirementDto;
import com.pharmatrack.dto.QualityCheckResponseDto;
import com.pharmatrack.dto.StockMovementResponseDto;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.QualityCheckResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Transforms verified {@link BatchTraceabilityDossierDto} records into a compact, deterministic,
 * injection-resistant factual context block accompanied by backend-attributed source references.
 *
 * <p>Supports both {@link BatchAnalysisMode#FULL} (comprehensive 12-section context)
 * and {@link BatchAnalysisMode#FOCUSED} (tailored context containing only relevant domain data).</p>
 */
@Component
public class BatchContextBuilder {

    public static final String DATA_START_DELIMITER = "<<<VERIFIED_PHARMATRACK_DATA>>>";
    public static final String DATA_END_DELIMITER = "<<</VERIFIED_PHARMATRACK_DATA>>>";
    public static final String ESCAPED_START_DELIMITER = "[ESCAPED_DATA_START_DELIMITER]";
    public static final String ESCAPED_END_DELIMITER = "[ESCAPED_DATA_END_DELIMITER]";

    private static final DateTimeFormatter TIME_12H_FORMATTER = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);

    private final BatchLifecycleRecordClassifier recordClassifier;

    public BatchContextBuilder() {
        this(new BatchLifecycleRecordClassifier());
    }

    public BatchContextBuilder(BatchLifecycleRecordClassifier recordClassifier) {
        this.recordClassifier = recordClassifier != null ? recordClassifier : new BatchLifecycleRecordClassifier();
    }

    /**
     * Builds full batch context for standard 12-section reports.
     */
    public BatchContext buildBatchContext(BatchTraceabilityDossierDto dossier) {
        return buildBatchContext(dossier, BatchAnalysisMode.FULL, Collections.emptyList());
    }

    /**
     * Builds tailored factual context and source reference list according to the requested analysis mode
     * and selected focus areas.
     *
     * @param dossier    retrieved batch traceability dossier DTO
     * @param mode       FULL or FOCUSED mode
     * @param focusAreas selected focus areas if in FOCUSED mode
     * @return immutable {@link BatchContext} containing formatted facts and source references
     */
    public BatchContext buildBatchContext(BatchTraceabilityDossierDto dossier,
                                          BatchAnalysisMode mode,
                                          List<BatchAnalysisFocusArea> focusAreas) {
        if (dossier == null) {
            throw new IllegalArgumentException("BatchTraceabilityDossierDto must not be null");
        }

        BatchAnalysisMode effectiveMode = mode != null ? mode : BatchAnalysisMode.FULL;
        List<BatchAnalysisFocusArea> effectiveAreas = focusAreas != null ? focusAreas : Collections.emptyList();

        StringBuilder sb = new StringBuilder();
        sb.append(DATA_START_DELIMITER).append("\n");

        String unit = dossier.getBatchUnit() != null ? dossier.getBatchUnit() : "";

        // ---------------------------------------------------------------------
        // Section A: Batch Overview (Included in both Full and Focused modes)
        // ---------------------------------------------------------------------
        sb.append("[BATCH_OVERVIEW]\n");
        sb.append("Batch Number: ").append(escapeDelimiters(dossier.getBatchNumber())).append("\n");
        sb.append("QR Code: ").append(escapeDelimiters(dossier.getQrCode() != null ? dossier.getQrCode() : "N/A")).append("\n");
        sb.append("Parent Manufacturing Order: ").append(escapeDelimiters(dossier.getOrderNumber() != null ? dossier.getOrderNumber() : "N/A"));
        if (dossier.getOrderStatus() != null) {
            sb.append(" (Status: ").append(dossier.getOrderStatus()).append(")");
        }
        sb.append("\n");

        sb.append("Product: ").append(escapeDelimiters(dossier.getProductName() != null ? dossier.getProductName() : "N/A"))
                .append(" (Code: ").append(escapeDelimiters(dossier.getProductCode() != null ? dossier.getProductCode() : "N/A")).append(")\n");
        sb.append("Recipe: ").append(escapeDelimiters(dossier.getRecipeCode() != null ? dossier.getRecipeCode() : "N/A"))
                .append(" (Version: ").append(dossier.getRecipeVersion() != null ? dossier.getRecipeVersion() : "N/A").append(")\n");
        sb.append("Batch Status: ").append(dossier.getBatchStatus() != null ? dossier.getBatchStatus() : "N/A").append("\n");
        sb.append("QA Disposition Status: ").append(dossier.getQaStatus() != null ? dossier.getQaStatus() : "PENDING_QA").append("\n");

        sb.append("Planned Batch Size: ")
                .append(dossier.getBatchSize() != null ? dossier.getBatchSize().toPlainString() : "N/A")
                .append(unit.isEmpty() ? "" : " " + escapeDelimiters(unit))
                .append("\n");

        sb.append("Actual Yield: ");
        if (dossier.getActualYield() != null) {
            sb.append(dossier.getActualYield().toPlainString()).append(unit.isEmpty() ? "" : " " + escapeDelimiters(unit));
        } else {
            sb.append("Not completed / Unrecorded");
        }
        sb.append("\n");

        sb.append("Manufacture Date: ").append(dossier.getManufactureDate() != null ? dossier.getManufactureDate().toString() : "Not set").append("\n");
        sb.append("Expiry Date: ").append(dossier.getExpiryDate() != null ? dossier.getExpiryDate().toString() : "Not set").append("\n");
        if (dossier.getBatchStatus() == BatchStatus.PLANNED) {
            sb.append("Batch Start Time: ").append(formatTimestamp(dossier.getStartTime(), "Manufacturing has not started; timestamp is not yet expected")).append("\n");
            sb.append("Batch End Time: ").append(formatTimestamp(dossier.getEndTime(), "Completion has not occurred; end timestamp is not yet expected")).append("\n");
        } else if (dossier.getBatchStatus() == BatchStatus.IN_PROGRESS) {
            sb.append("Batch Start Time: ").append(formatTimestamp(dossier.getStartTime(), "Not recorded")).append("\n");
            sb.append("Batch End Time: ").append(formatTimestamp(dossier.getEndTime(), "In progress (completion has not occurred; end timestamp not yet expected)")).append("\n");
        } else {
            sb.append("Batch Start Time: ").append(formatTimestamp(dossier.getStartTime(), "Not started")).append("\n");
            sb.append("Batch End Time: ").append(formatTimestamp(dossier.getEndTime(), "In progress / Not completed")).append("\n");
        }

        if (effectiveMode == BatchAnalysisMode.FULL) {
            // Full 12-section context
            sb.append("\n[PRODUCTION_METRICS]\n");
            buildProductionMetrics(sb, dossier, unit);

            sb.append("\n[EBR_EXECUTION_SUMMARY]\n");
            buildExecutionSummary(sb, dossier);

            sb.append("\n[QUALITY_CONTROL_SUMMARY]\n");
            buildQualitySummary(sb, dossier);

            sb.append("\n[DEVIATIONS_AND_CAPA]\n");
            buildDeviationSummary(sb, dossier);

            sb.append("\n[MATERIAL_CONSUMPTION]\n");
            buildMaterialSummary(sb, dossier);

            sb.append("\n[DOCUMENTATION_COMPLETENESS_EVALUATION]\n");
            buildDocumentationCompletenessEvaluation(sb, dossier);
        } else {
            // Focused mode: append ONLY the selected domain contexts
            if (effectiveAreas.contains(BatchAnalysisFocusArea.MANUFACTURING_YIELD)) {
                sb.append("\n[PRODUCTION_METRICS]\n");
                buildProductionMetrics(sb, dossier, unit);
            }
            if (effectiveAreas.contains(BatchAnalysisFocusArea.MATERIAL_TRACEABILITY)) {
                sb.append("\n[MATERIAL_CONSUMPTION]\n");
                buildMaterialSummary(sb, dossier);
            }
            if (effectiveAreas.contains(BatchAnalysisFocusArea.EBR_EXECUTION)) {
                sb.append("\n[EBR_EXECUTION_SUMMARY]\n");
                buildExecutionSummary(sb, dossier);
            }
            if (effectiveAreas.contains(BatchAnalysisFocusArea.QC_INSPECTION)) {
                sb.append("\n[QUALITY_CONTROL_SUMMARY]\n");
                buildQualitySummary(sb, dossier);
            }
            if (effectiveAreas.contains(BatchAnalysisFocusArea.QA_DISPOSITION)) {
                sb.append("\n[QA_DISPOSITION_SUMMARY]\n");
                buildQaDispositionSummary(sb, dossier);
            }
            if (effectiveAreas.contains(BatchAnalysisFocusArea.DEVIATIONS_CAPA)) {
                sb.append("\n[DEVIATIONS_AND_CAPA]\n");
                buildDeviationSummary(sb, dossier);
            }
            if (effectiveAreas.contains(BatchAnalysisFocusArea.CHRONOLOGICAL_AUDIT)) {
                sb.append("\n[CHRONOLOGICAL_AUDIT_SUMMARY]\n");
                buildChronologicalAuditSummary(sb, dossier);
            }
            if (effectiveAreas.contains(BatchAnalysisFocusArea.MISSING_RECORDS)) {
                sb.append("\n[DOCUMENTATION_COMPLETENESS_EVALUATION]\n");
                buildDocumentationCompletenessEvaluation(sb, dossier);
            }
            if (effectiveAreas.contains(BatchAnalysisFocusArea.RISK_ASSESSMENT)) {
                sb.append("\n[OPERATIONAL_RISK_FACTS]\n");
                buildOperationalRiskFacts(sb, dossier);
            }
        }

        sb.append(DATA_END_DELIMITER);

        List<AiSourceReferenceDto> sourceReferences = buildSourceReferences(dossier, unit, effectiveMode, effectiveAreas);
        return new BatchContext(sb.toString(), sourceReferences);
    }

    private void buildProductionMetrics(StringBuilder sb, BatchTraceabilityDossierDto dossier, String unit) {
        BigDecimal batchSize = dossier.getBatchSize();
        BigDecimal actualYield = dossier.getActualYield();

        if (batchSize == null || batchSize.compareTo(BigDecimal.ZERO) <= 0) {
            sb.append("Yield Percentage: Calculation unavailable (invalid or non-positive batch size)\n");
            sb.append("Yield Variance: Calculation unavailable\n");
        } else if (actualYield == null) {
            if (dossier.getBatchStatus() == BatchStatus.PLANNED) {
                sb.append("Yield Percentage: Not yet expected (production has not commenced)\n");
                sb.append("Yield Variance: Not yet expected (production has not commenced)\n");
            } else if (dossier.getBatchStatus() == BatchStatus.CANCELLED) {
                sb.append("Yield Percentage: Not applicable (batch cancelled)\n");
                sb.append("Yield Variance: Not applicable (batch cancelled)\n");
            } else if (dossier.getBatchStatus() == BatchStatus.COMPLETED) {
                sb.append("Yield Percentage: Unavailable (actual yield unrecorded on completed batch)\n");
                sb.append("Yield Variance: Unavailable (actual yield unrecorded on completed batch)\n");
            } else {
                sb.append("Yield Percentage: Not completed / In progress (actual yield not recorded)\n");
                sb.append("Yield Variance: Not completed / In progress\n");
            }
        } else {
            BigDecimal yieldPct = actualYield.divide(batchSize, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal yieldVar = actualYield.subtract(batchSize).setScale(4, RoundingMode.HALF_UP);

            sb.append("Yield Percentage: ").append(yieldPct.toPlainString()).append("%\n");
            sb.append("Yield Variance: ");
            if (yieldVar.compareTo(BigDecimal.ZERO) > 0) {
                sb.append("+");
            }
            sb.append(yieldVar.stripTrailingZeros().toPlainString());
            if (!unit.isEmpty()) {
                sb.append(" ").append(escapeDelimiters(unit));
            }
            sb.append("\n");
        }

        if (dossier.getStartTime() != null && dossier.getEndTime() != null) {
            if (dossier.getEndTime().isBefore(dossier.getStartTime())) {
                sb.append("Production Duration: Chronology anomaly — recorded end time is before start time\n");
            } else {
                Duration duration = Duration.between(dossier.getStartTime(), dossier.getEndTime());
                sb.append("Production Duration: ").append(formatDuration(duration)).append("\n");
            }
        } else if (dossier.getStartTime() != null && dossier.getEndTime() == null) {
            sb.append("Production Duration: In progress (started at ")
                    .append(dossier.getStartTime())
                    .append(" (").append(dossier.getStartTime().format(TIME_12H_FORMATTER)).append(" IST))\n");
        } else {
            sb.append("Production Duration: Not started\n");
        }
    }

    private void buildExecutionSummary(StringBuilder sb, BatchTraceabilityDossierDto dossier) {
        List<BatchExecutionStepResponseDto> steps = dossier.getExecutionSteps() != null
                ? dossier.getExecutionSteps()
                : List.of();

        sb.append("Total Execution Steps: ").append(steps.size()).append("\n");
        sb.append("NOTE: Execution steps reflect current persisted step state and single recorded timestamps from batch_execution_steps; they do not represent an exhaustive transaction history or append-only state-transition sequence.\n");

        if (steps.isEmpty()) {
            if (dossier.getBatchStatus() == BatchStatus.CANCELLED) {
                if (dossier.getStartTime() == null) {
                    sb.append("Execution Steps: None recorded (Batch was cancelled prior to production initiation; execution steps were not initialized)\n");
                } else {
                    sb.append("Execution Steps: None recorded (Batch was started prior to cancellation, but zero execution steps are recorded in the dossier)\n");
                }
            } else {
                sb.append("Execution Steps: None recorded / Not initialized\n");
            }
            return;
        }

        long completedSteps = steps.stream().filter(s -> s.getStatus() == ExecutionStepStatus.COMPLETED).count();
        long inProgressSteps = steps.stream().filter(s -> s.getStatus() == ExecutionStepStatus.IN_PROGRESS).count();
        long pendingSteps = steps.stream().filter(s -> s.getStatus() == ExecutionStepStatus.PENDING).count();

        sb.append("Step Progress: Completed=").append(completedSteps)
                .append(", In-Progress=").append(inProgressSteps)
                .append(", Pending=").append(pendingSteps);
        if (dossier.getBatchStatus() == BatchStatus.CANCELLED) {
            sb.append(" (Batch was cancelled; historical execution records are preserved; unexecuted steps were not executed due to cancellation)");
        }
        sb.append("\n");

        for (BatchExecutionStepResponseDto step : steps) {
            Duration stepDuration = null;
            boolean reversedStep = false;
            if (step.getStartedAt() != null && step.getCompletedAt() != null) {
                if (step.getCompletedAt().isBefore(step.getStartedAt())) {
                    reversedStep = true;
                } else {
                    stepDuration = Duration.between(step.getStartedAt(), step.getCompletedAt());
                }
            }

            String durationStr = reversedStep
                    ? "Chronology anomaly — completed before started"
                    : (stepDuration != null
                            ? formatDuration(stepDuration)
                            : (step.getStartedAt() != null ? "In progress" : "Not started"));

            sb.append("- Step ").append(step.getStepNumber() != null ? step.getStepNumber() : "?")
                    .append(": ").append(escapeDelimiters(step.getOperationName() != null ? step.getOperationName() : "Unnamed Operation"))
                    .append(" | Status: ").append(step.getStatus() != null ? step.getStatus() : "N/A")
                    .append(" | Duration: ").append(durationStr)
                    .append(" | Actual Value: ").append(escapeDelimiters(step.getActualValue() != null && !step.getActualValue().isBlank() ? step.getActualValue() : "None recorded"))
                    .append(" | Operator: ").append(step.getExecutedBy() != null && !step.getExecutedBy().isBlank() ? "[Operator]" : "Not assigned")
                    .append(" | Started: ").append(formatTimestamp(step.getStartedAt(), "Not started"))
                    .append(" | Completed: ").append(formatTimestamp(step.getCompletedAt(), "In progress / Not completed"))
                    .append(" | Observations: ").append(escapeDelimiters(step.getObservations() != null && !step.getObservations().isBlank() ? step.getObservations() : "None"))
                    .append("\n");
        }

        for (int i = 0; i < steps.size() - 1; i++) {
            BatchExecutionStepResponseDto current = steps.get(i);
            BatchExecutionStepResponseDto next = steps.get(i + 1);
            if (current.getCompletedAt() != null && next.getStartedAt() != null) {
                Duration gap = Duration.between(current.getCompletedAt(), next.getStartedAt());
                if (gap.isNegative()) {
                    sb.append("  * Transition between Step ").append(current.getStepNumber())
                            .append(" and Step ").append(next.getStepNumber())
                            .append(": Chronology anomaly — overlapping timestamps observed (Step ")
                            .append(next.getStepNumber()).append(" started ")
                            .append(formatDuration(gap.abs())).append(" before Step ")
                            .append(current.getStepNumber()).append(" completed) (recorded chronology anomaly without inferring equipment failure or process hold)\n");
                } else if (!gap.isZero()) {
                    sb.append("  * Observed elapsed gap between Step ").append(current.getStepNumber())
                            .append(" and Step ").append(next.getStepNumber())
                            .append(": ").append(formatDuration(gap))
                            .append(" (Observed timestamp interval; equipment idle state or hold reason not verified)\n");
                }
            }
        }
    }

    private void buildQualitySummary(StringBuilder sb, BatchTraceabilityDossierDto dossier) {
        List<QualityCheckResponseDto> checks = dossier.getQualityChecks() != null
                ? dossier.getQualityChecks()
                : List.of();

        sb.append("Total Recorded QC Checks: ").append(checks.size()).append("\n");

        if (checks.isEmpty()) {
            sb.append("QC Status: No quality control checks recorded for this batch.\n");
            return;
        }

        long passCount = checks.stream().filter(c -> c.getResult() == QualityCheckResult.PASS).count();
        long failCount = checks.stream().filter(c -> c.getResult() == QualityCheckResult.FAIL).count();
        long otherCount = checks.size() - passCount - failCount;
        int totalCorrections = checks.stream().mapToInt(QualityCheckResponseDto::getCorrectionCount).sum();

        sb.append("QC Results: PASS=").append(passCount).append(" | FAIL=").append(failCount);
        if (otherCount > 0) {
            sb.append(" | OTHER=").append(otherCount);
        }
        sb.append(" | Total Second-Person Corrections=").append(totalCorrections).append("\n");

        for (QualityCheckResponseDto check : checks) {
            sb.append("- ").append(escapeDelimiters(check.getTestName()))
                    .append(" | Result: ").append(check.getResult() != null ? check.getResult() : "N/A")
                    .append(" | Specification: ").append(escapeDelimiters(check.getSpecification() != null ? check.getSpecification() : "N/A"))
                    .append(" | Measured: ").append(escapeDelimiters(check.getMeasuredValue() != null ? check.getMeasuredValue() : "None recorded"));

            if (check.getUnit() != null && !check.getUnit().isBlank()) {
                sb.append(" ").append(escapeDelimiters(check.getUnit()));
            }

            sb.append(" | Inspector: ").append(check.getInspectorName() != null && !check.getInspectorName().isBlank() ? "[QC Analyst]" : "Unknown")
                    .append(" | Inspected: ").append(formatTimestamp(check.getCheckedAt(), "Not set"));

            if (check.getCorrectionCount() > 0) {
                sb.append(" | Corrected: ").append(check.getCorrectionCount()).append(" time(s) by ")
                        .append(check.getCorrectedBy() != null && !check.getCorrectedBy().isBlank() ? "[QC Analyst]" : "Unknown")
                        .append(" (Reason: ").append(escapeDelimiters(check.getCorrectionReason() != null ? check.getCorrectionReason() : "None stated")).append(")");
            }
            sb.append("\n");
        }
    }

    private void buildQaDispositionSummary(StringBuilder sb, BatchTraceabilityDossierDto dossier) {
        sb.append("Authoritative QA Disposition Status: ")
                .append(dossier.getQaStatus() != null ? dossier.getQaStatus() : "PENDING_QA").append("\n");

        List<BatchQaReviewResponseDto> reviews = dossier.getQaReviews() != null
                ? dossier.getQaReviews()
                : List.of();

        sb.append("Total QA Review Records: ").append(reviews.size()).append("\n");
        if (reviews.isEmpty()) {
            sb.append("- No formal QA disposition review has been recorded to date.\n");
        } else {
            for (BatchQaReviewResponseDto r : reviews) {
                sb.append("- Decision: ").append(r.getDecisionStatus() != null ? r.getDecisionStatus() : "N/A")
                        .append(" | Reviewer: ").append(r.getReviewerName() != null ? "[QA Reviewer]" : "Unknown")
                        .append(" | Reviewed: ").append(formatTimestamp(r.getReviewedAt(), "Not set"))
                        .append(" | Rationale: ").append(escapeDelimiters(r.getRationale() != null ? r.getRationale() : "None stated"))
                        .append("\n");
            }
        }
        sb.append("QA Disposition Rule: Formal disposition requires qualified QP review. AI cannot approve, reject, or release a batch.\n");
    }

    private void buildDeviationSummary(StringBuilder sb, BatchTraceabilityDossierDto dossier) {
        List<DeviationDetailDto> deviations = dossier.getDeviations() != null
                ? dossier.getDeviations()
                : List.of();

        sb.append("Total Linked Deviations: ").append(deviations.size()).append("\n");

        if (deviations.isEmpty()) {
            sb.append("Deviation Status: Zero deviations recorded for this batch. Note: Absence of recorded deviations is not proof of compliance.\n");
            return;
        }

        for (DeviationDetailDto dev : deviations) {
            sb.append("- Deviation ").append(escapeDelimiters(dev.getDeviationNumber()))
                    .append(" | Title: ").append(escapeDelimiters(dev.getTitle()))
                    .append(" | Severity: ").append(dev.getSeverity() != null ? dev.getSeverity() : "N/A")
                    .append(" | Status: ").append(dev.getStatus() != null ? dev.getStatus() : "N/A")
                    .append(" | Category: ").append(dev.getCategory() != null ? dev.getCategory() : "N/A")
                    .append("\n");

            String rootCause = (dev.getRootCauseAnalysis() != null && !dev.getRootCauseAnalysis().isBlank())
                    ? dev.getRootCauseAnalysis()
                    : "Not recorded / Under investigation";

            sb.append("  * Root Cause: ").append(escapeDelimiters(rootCause));
            if (dev.getRootCauseCategory() != null && !dev.getRootCauseCategory().isBlank()) {
                sb.append(" (Category: ").append(escapeDelimiters(dev.getRootCauseCategory())).append(")");
            }
            sb.append("\n");

            List<CapaActionResponseDto> capas = dev.getCapaActions() != null ? dev.getCapaActions() : List.of();
            if (capas.isEmpty()) {
                sb.append("  * CAPA Actions: None associated\n");
            } else {
                sb.append("  * CAPA Actions (").append(capas.size()).append("):\n");
                for (CapaActionResponseDto capa : capas) {
                    sb.append("    - CAPA ").append(escapeDelimiters(capa.getCapaNumber()))
                            .append(" (").append(capa.getCapaType() != null ? capa.getCapaType() : "CAPA").append("): ")
                            .append(escapeDelimiters(capa.getTitle()))
                            .append(" | Status: ").append(capa.getStatus() != null ? capa.getStatus() : "N/A")
                            .append(" | Owner: ").append(capa.getOwnerName() != null && !capa.getOwnerName().isBlank() ? "[CAPA Owner]" : "Unassigned")
                            .append(" | Due Date: ").append(capa.getDueDate() != null ? capa.getDueDate().toString() : "None")
                            .append("\n");
                }
            }
        }
    }

    private void buildMaterialSummary(StringBuilder sb, BatchTraceabilityDossierDto dossier) {
        List<StockMovementResponseDto> movements = dossier.getMaterialMovements() != null
                ? dossier.getMaterialMovements()
                : List.of();

        Long batchId = dossier.getBatchId();
        String batchNumber = dossier.getBatchNumber();

        List<StockMovementResponseDto> batchMovements = movements.stream()
                .filter(m -> (batchId != null && Objects.equals(m.getBatchId(), batchId))
                        || (batchNumber != null && batchNumber.equalsIgnoreCase(m.getBatchNumber())))
                .toList();

        List<StockMovementResponseDto> orderMovements = movements.stream()
                .filter(m -> !((batchId != null && Objects.equals(m.getBatchId(), batchId))
                        || (batchNumber != null && batchNumber.equalsIgnoreCase(m.getBatchNumber()))))
                .toList();

        sb.append("Batch-Specific Material Movements: ").append(batchMovements.size()).append("\n");

        if (batchMovements.isEmpty()) {
            sb.append("- No direct material issues recorded specifically for this batch.\n");
        } else {
            for (StockMovementResponseDto m : batchMovements) {
                BigDecimal qty = m.getQuantityDelta() != null ? m.getQuantityDelta().abs() : null;
                sb.append("- Material: ").append(escapeDelimiters(m.getMaterialCode()))
                        .append(" (").append(escapeDelimiters(m.getMaterialName())).append(")")
                        .append(" | Lot: ").append(escapeDelimiters(m.getLotNumber()))
                        .append(" | Quantity Issued: ").append(qty != null ? qty.stripTrailingZeros().toPlainString() : "N/A")
                        .append(m.getUnit() != null ? " " + escapeDelimiters(m.getUnit()) : "")
                        .append(" | Warehouse: ").append(escapeDelimiters(m.getWarehouseCode() != null ? m.getWarehouseCode() : "N/A"))
                        .append(" (Location: ").append(escapeDelimiters(m.getLocationCode() != null ? m.getLocationCode() : "N/A")).append(")")
                        .append(" | Performed By: ").append(m.getPerformedBy() != null && !m.getPerformedBy().isBlank() ? "[Operator]" : "Unknown")
                        .append(" | Timestamp: ").append(formatTimestamp(m.getOccurredAt(), "Not set"))
                        .append("\n");
            }
        }

        if (!orderMovements.isEmpty()) {
            sb.append("Order-Level Material Issues Note: ").append(orderMovements.size())
                    .append(" material movement(s) recorded against parent Manufacturing Order ")
                    .append(escapeDelimiters(dossier.getOrderNumber() != null ? dossier.getOrderNumber() : "N/A"))
                    .append(" without individual batch attribution. These cannot be attributed to Batch ")
                    .append(escapeDelimiters(dossier.getBatchNumber())).append(".\n");
        }

        List<OrderMaterialRequirementDto> requirements = dossier.getMaterialRequirements() != null
                ? dossier.getMaterialRequirements()
                : List.of();

        if (requirements.isEmpty()) {
            sb.append("BOM Requirements: None available in dossier.\n");
            sb.append("Material Reconciliation: Material reconciliation is unavailable because BOM requirements are missing.\n");
        } else {
            sb.append("BOM Requirements per Batch:\n");
            for (OrderMaterialRequirementDto req : requirements) {
                sb.append("- ").append(escapeDelimiters(req.getRecipeMaterialName()))
                        .append(" (").append(escapeDelimiters(req.getRawMaterialCode() != null ? req.getRawMaterialCode() : "N/A")).append(")")
                        .append(": Quantity Per Batch = ")
                        .append(req.getQuantityPerBatch() != null ? req.getQuantityPerBatch().stripTrailingZeros().toPlainString() : "N/A")
                        .append(req.getRecipeUnit() != null ? " " + escapeDelimiters(req.getRecipeUnit()) : "")
                        .append("\n");
            }

            if (batchMovements.isEmpty()) {
                sb.append("Material Reconciliation: Batch-specific quantity reconciliation is incomplete or unavailable because material issues were not assigned to this specific batch. NOTE: Absence of batch-attributed movement records does not prove materials were not consumed outside tracked workflows; unlinked or order-level movements cannot be attributed to this batch without authoritative linkage.\n");
            } else {
                sb.append("Material Reconciliation: Batch-specific issues recorded above; verify against planned per-batch quantities. NOTE: Only batch-attributed movements are included; unlinked or order-level inventory movements are not attributed without explicit linkage. Absence of movement records does not prove materials were not consumed outside tracked workflows.\n");
            }
        }
    }

    private void buildChronologicalAuditSummary(StringBuilder sb, BatchTraceabilityDossierDto dossier) {
        sb.append("Batch Start: ").append(formatTimestamp(dossier.getStartTime(), "Not started")).append("\n");
        sb.append("Batch End: ").append(formatTimestamp(dossier.getEndTime(), "In progress / Not completed")).append("\n");

        List<BatchExecutionStepResponseDto> steps = dossier.getExecutionSteps() != null ? dossier.getExecutionSteps() : List.of();
        sb.append("Execution Step Sequence: ").append(steps.size()).append(" step(s) recorded (sourced from batch_execution_steps entity records; absence of step events in audit_logs is normal and does NOT indicate the step did not occur).\n");

        for (int i = 0; i < steps.size(); i++) {
            BatchExecutionStepResponseDto step = steps.get(i);
            sb.append("- Step ").append(step.getStepNumber() != null ? step.getStepNumber() : (i + 1))
                    .append(" (").append(escapeDelimiters(step.getOperationName() != null ? step.getOperationName() : "Operation")).append(")")
                    .append(" | Status: ").append(step.getStatus())
                    .append(" | Started: ").append(formatTimestamp(step.getStartedAt(), "N/A"))
                    .append(" | Completed: ").append(formatTimestamp(step.getCompletedAt(), "N/A"))
                    .append("\n");
        }

        List<AuditLogResponseDto> auditLogs = dossier.getAuditHistory() != null ? dossier.getAuditHistory() : List.of();
        sb.append("Audit Trail Log Entries: ").append(auditLogs.size()).append(" entry/entries recorded (reflects available discrete audit events; not an exhaustive event-sourcing ledger).\n");
        if (!auditLogs.isEmpty()) {
            for (AuditLogResponseDto al : auditLogs) {
                sb.append("  * ").append(formatTimestamp(al.getOccurredAt(), "N/A"))
                        .append(" | Action: ").append(escapeDelimiters(al.getAction()))
                        .append(" | Entity: ").append(escapeDelimiters(al.getEntityType()))
                        .append(" | Actor: ").append(al.getActorName() != null ? "[User]" : "System")
                        .append("\n");
            }
        }
    }

    private void buildOperationalRiskFacts(StringBuilder sb, BatchTraceabilityDossierDto dossier) {
        sb.append("Batch Status: ").append(dossier.getBatchStatus()).append("\n");
        sb.append("QA Disposition Status: ").append(dossier.getQaStatus() != null ? dossier.getQaStatus() : "PENDING_QA").append("\n");

        List<DeviationDetailDto> devs = dossier.getDeviations() != null ? dossier.getDeviations() : List.of();
        if (devs.isEmpty()) {
            sb.append("- Deviations: 0 deviations recorded in available MES records. NOTE: Zero recorded deviations represents current database state, NOT proof of 100% compliance or absence of risk.\n");
        } else {
            long open = devs.stream().filter(d -> d.getStatus() != DeviationStatus.CLOSED).count();
            sb.append("- Deviations: ").append(devs.size()).append(" total (").append(open).append(" open/investigating).\n");
        }

        List<QualityCheckResponseDto> checks = dossier.getQualityChecks() != null ? dossier.getQualityChecks() : List.of();
        long failCount = checks.stream().filter(c -> c.getResult() == QualityCheckResult.FAIL).count();
        long pendingCount = checks.stream().filter(c -> c.getResult() == QualityCheckResult.PENDING).count();
        sb.append("- Quality Checks: ").append(checks.size()).append(" total (").append(failCount).append(" FAIL, ").append(pendingCount).append(" PENDING).\n");

        List<RecordAssessmentDto> assessments = recordClassifier.classifyRecords(dossier);
        List<String> gaps = recordClassifier.extractGenuinelyMissingGaps(assessments);
        sb.append("- Verified Missing Records: ").append(gaps.isEmpty() ? "None for current lifecycle stage." : String.join("; ", gaps)).append("\n");
    }

    private void buildDocumentationCompletenessEvaluation(StringBuilder sb, BatchTraceabilityDossierDto dossier) {
        List<RecordAssessmentDto> assessments = recordClassifier.classifyRecords(dossier);
        sb.append(recordClassifier.renderMissingRecordsEvaluation(dossier, assessments));
    }

    private List<AiSourceReferenceDto> buildSourceReferences(BatchTraceabilityDossierDto dossier,
                                                            String unit,
                                                            BatchAnalysisMode mode,
                                                            List<BatchAnalysisFocusArea> focusAreas) {
        List<AiSourceReferenceDto> sourceReferences = new ArrayList<>();

        // 1. Primary BATCH reference
        String batchSummary = "Status: " + (dossier.getBatchStatus() != null ? dossier.getBatchStatus() : "N/A")
                + " | QA: " + (dossier.getQaStatus() != null ? dossier.getQaStatus() : "PENDING_QA")
                + " | Size: " + (dossier.getBatchSize() != null ? dossier.getBatchSize().toPlainString() : "N/A")
                + (unit.isEmpty() ? "" : " " + unit)
                + (dossier.getActualYield() != null ? " | Yield: " + dossier.getActualYield().toPlainString() : "")
                + (dossier.getEndTime() != null ? " | Completed: " + formatSummaryTime(dossier.getEndTime()) : "");

        sourceReferences.add(new AiSourceReferenceDto(
                "BATCH",
                dossier.getBatchNumber(),
                dossier.getBatchNumber() + " (" + (dossier.getBatchStatus() != null ? dossier.getBatchStatus() : "Batch") + ")",
                "Production Execution",
                batchSummary
        ));

        // 2. Parent MANUFACTURING_ORDER reference
        if (dossier.getOrderNumber() != null && !dossier.getOrderNumber().isBlank()) {
            String orderSummary = "Status: " + (dossier.getOrderStatus() != null ? dossier.getOrderStatus() : "N/A")
                    + " | Product: " + (dossier.getProductCode() != null ? dossier.getProductCode() : "")
                    + " | Planned: " + (dossier.getPlannedQuantity() != null ? dossier.getPlannedQuantity().toPlainString() : "");

            sourceReferences.add(new AiSourceReferenceDto(
                    "MANUFACTURING_ORDER",
                    dossier.getOrderNumber(),
                    dossier.getOrderNumber() + " (" + (dossier.getProductName() != null ? dossier.getProductName() : "Order") + ")",
                    "Manufacturing Orders",
                    orderSummary
            ));
        }

        // 3. Linked DEVIATION references (included for FULL mode or when DEVIATIONS_CAPA / RISK_ASSESSMENT is selected)
        boolean includeDeviations = (mode == BatchAnalysisMode.FULL)
                || focusAreas.contains(BatchAnalysisFocusArea.DEVIATIONS_CAPA)
                || focusAreas.contains(BatchAnalysisFocusArea.RISK_ASSESSMENT);

        if (includeDeviations && dossier.getDeviations() != null) {
            for (DeviationDetailDto dev : dossier.getDeviations()) {
                if (dev.getDeviationNumber() != null && !dev.getDeviationNumber().isBlank()) {
                    String devSummary = "Status: " + dev.getStatus()
                            + " | " + (dev.getTitle() != null ? dev.getTitle() : "");
                    sourceReferences.add(new AiSourceReferenceDto(
                            "DEVIATION",
                            dev.getDeviationNumber(),
                            dev.getDeviationNumber() + " (" + (dev.getSeverity() != null ? dev.getSeverity() : "Deviation") + ")",
                            "Quality Management",
                            devSummary
                    ));
                }
            }
        }

        return sourceReferences;
    }

    public static String formatDuration(Duration duration) {
        if (duration == null) {
            return "N/A";
        }
        long totalSeconds = duration.getSeconds();
        if (totalSeconds < 0) {
            return "-" + formatDuration(duration.abs());
        }
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            long totalMinutes = totalSeconds / 60;
            return String.format(Locale.US, "%d hour%s %d minute%s (%d minutes)",
                    hours, hours == 1 ? "" : "s",
                    minutes, minutes == 1 ? "" : "s",
                    totalMinutes);
        } else if (minutes > 0) {
            return String.format(Locale.US, "%d minute%s (%d minutes)",
                    minutes, minutes == 1 ? "" : "s", minutes);
        } else {
            return String.format(Locale.US, "%d second%s", seconds, seconds == 1 ? "" : "s");
        }
    }

    public static String escapeDelimiters(String input) {
        if (input == null) {
            return "";
        }
        return input.replace(DATA_START_DELIMITER, ESCAPED_START_DELIMITER)
                .replace(DATA_END_DELIMITER, ESCAPED_END_DELIMITER);
    }

    private static String formatTimestamp(LocalDateTime dateTime, String fallback) {
        if (dateTime == null) {
            return fallback;
        }
        return dateTime.toString();
    }

    private static String formatSummaryTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        try {
            return dateTime.format(TIME_12H_FORMATTER);
        } catch (Exception e) {
            return dateTime.toString();
        }
    }
}
