package com.pharmatrack.ai.service;

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
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic Java engine for evaluating and classifying batch records according to their
 * manufacturing lifecycle stage.
 *
 * <p>Enforces strict pharmaceutical GxP rules:
 * <ul>
 *   <li>Distinguishes GENUINELY_MISSING from NOT_YET_EXPECTED, NOT_APPLICABLE, and PRESENT.</li>
 *   <li>PLANNED batches are never penalized for unstarted steps, unrecorded yields, or pending QCs.</li>
 *   <li>Zero recorded deviations is classified as absence of recorded deviations, NOT proof of 100% compliance.</li>
 *   <li>QC requirements are not fabricated when process master data does not configure them.</li>
 *   <li>COMPLETED batches lacking actual yield or completion timestamp are explicitly marked GENUINELY_MISSING.</li>
 * </ul>
 */
@Component
public class BatchLifecycleRecordClassifier {

    public List<RecordAssessmentDto> classifyRecords(BatchTraceabilityDossierDto dossier) {
        if (dossier == null) {
            return Collections.emptyList();
        }

        List<RecordAssessmentDto> assessments = new ArrayList<>();
        BatchStatus status = dossier.getBatchStatus() != null ? dossier.getBatchStatus() : BatchStatus.PLANNED;
        BatchQualityStatus qaStatus = dossier.getQaStatus() != null ? dossier.getQaStatus() : BatchQualityStatus.PENDING_QA;
        BigDecimal actualYield = dossier.getActualYield();
        String unit = dossier.getBatchUnit() != null ? dossier.getBatchUnit().trim() : "";

        List<BatchExecutionStepResponseDto> steps = dossier.getExecutionSteps() != null
                ? dossier.getExecutionSteps()
                : Collections.emptyList();

        List<QualityCheckResponseDto> checks = dossier.getQualityChecks() != null
                ? dossier.getQualityChecks()
                : Collections.emptyList();

        List<DeviationDetailDto> deviations = dossier.getDeviations() != null
                ? dossier.getDeviations()
                : Collections.emptyList();

        List<StockMovementResponseDto> movements = dossier.getMaterialMovements() != null
                ? dossier.getMaterialMovements()
                : Collections.emptyList();

        List<OrderMaterialRequirementDto> requirements = dossier.getMaterialRequirements() != null
                ? dossier.getMaterialRequirements()
                : Collections.emptyList();

        // ---------------------------------------------------------------------
        // 1. Manufacturing Start Timestamp
        // ---------------------------------------------------------------------
        if (status == BatchStatus.PLANNED) {
            assessments.add(new RecordAssessmentDto(
                    "Manufacturing Start Timestamp",
                    RecordLifecycleClassification.NOT_YET_EXPECTED,
                    "Batch execution has not commenced. Start timestamp is recorded when execution begins.",
                    dossier.getBatchNumber()
            ));
        } else if (dossier.getStartTime() != null) {
            String expl = status == BatchStatus.CANCELLED
                    ? "Recorded start time: " + dossier.getStartTime() + " (Note: Batch was cancelled; start timestamp marks execution initiation prior to cancellation)."
                    : "Recorded start time: " + dossier.getStartTime();
            assessments.add(new RecordAssessmentDto(
                    "Manufacturing Start Timestamp",
                    RecordLifecycleClassification.PRESENT,
                    expl,
                    dossier.getBatchNumber()
            ));
        } else if (status == BatchStatus.CANCELLED) {
            assessments.add(new RecordAssessmentDto(
                    "Manufacturing Start Timestamp",
                    RecordLifecycleClassification.NOT_APPLICABLE,
                    "Batch was cancelled prior to execution initiation; production never started.",
                    dossier.getBatchNumber()
            ));
        } else {
            assessments.add(new RecordAssessmentDto(
                    "Manufacturing Start Timestamp",
                    RecordLifecycleClassification.GENUINELY_MISSING,
                    "Batch is marked " + status + ", but start timestamp is absent from records.",
                    dossier.getBatchNumber()
            ));
        }

        // ---------------------------------------------------------------------
        // 2. Manufacturing Completion Timestamp (End Time)
        // ---------------------------------------------------------------------
        if (status == BatchStatus.PLANNED) {
            assessments.add(new RecordAssessmentDto(
                    "Manufacturing Completion Timestamp",
                    RecordLifecycleClassification.NOT_YET_EXPECTED,
                    "Batch execution has not commenced; end timestamp is recorded upon batch completion.",
                    dossier.getBatchNumber()
            ));
        } else if (status == BatchStatus.IN_PROGRESS) {
            assessments.add(new RecordAssessmentDto(
                    "Manufacturing Completion Timestamp",
                    RecordLifecycleClassification.NOT_YET_EXPECTED,
                    "Manufacturing operations are currently in progress; completion timestamp is not yet expected.",
                    dossier.getBatchNumber()
            ));
        } else if (status == BatchStatus.COMPLETED) {
            if (dossier.getEndTime() != null) {
                assessments.add(new RecordAssessmentDto(
                        "Manufacturing Completion Timestamp",
                        RecordLifecycleClassification.PRESENT,
                        "Recorded completion time: " + dossier.getEndTime(),
                        dossier.getBatchNumber()
                ));
            } else {
                assessments.add(new RecordAssessmentDto(
                        "Manufacturing Completion Timestamp",
                        RecordLifecycleClassification.GENUINELY_MISSING,
                        "Batch status is COMPLETED, but manufacturing end timestamp is unrecorded.",
                        dossier.getBatchNumber()
                ));
            }
        } else if (status == BatchStatus.CANCELLED) {
            if (dossier.getEndTime() != null) {
                assessments.add(new RecordAssessmentDto(
                        "Manufacturing Completion Timestamp",
                        RecordLifecycleClassification.UNKNOWN,
                        "Batch is marked CANCELLED but contains a manufacturing completion timestamp ("
                                + dossier.getEndTime() + "). This is an inconsistent record state requiring investigation; completion timestamp cannot be reinterpreted as a cancellation timestamp.",
                        dossier.getBatchNumber(),
                        true
                ));
            } else {
                assessments.add(new RecordAssessmentDto(
                        "Manufacturing Completion Timestamp",
                        RecordLifecycleClassification.NOT_APPLICABLE,
                        "The batch was cancelled before successful production completion; a manufacturing completion timestamp is not applicable.",
                        dossier.getBatchNumber(),
                        false
                ));
            }
        }

        // ---------------------------------------------------------------------
        // 3. Actual Yield Quantity
        // ---------------------------------------------------------------------
        if (status == BatchStatus.PLANNED) {
            assessments.add(new RecordAssessmentDto(
                    "Actual Yield Quantity",
                    RecordLifecycleClassification.NOT_YET_EXPECTED,
                    "Production has not started; actual yield is recorded upon batch completion.",
                    dossier.getBatchNumber()
            ));
        } else if (status == BatchStatus.IN_PROGRESS) {
            assessments.add(new RecordAssessmentDto(
                    "Actual Yield Quantity",
                    RecordLifecycleClassification.NOT_YET_EXPECTED,
                    "Manufacturing operations are ongoing; final actual yield reconciliation is recorded upon completion.",
                    dossier.getBatchNumber()
            ));
        } else if (status == BatchStatus.COMPLETED) {
            if (actualYield != null) {
                assessments.add(new RecordAssessmentDto(
                        "Actual Yield Quantity",
                        RecordLifecycleClassification.PRESENT,
                        "Recorded actual yield: " + actualYield.toPlainString() + (unit.isEmpty() ? "" : " " + unit),
                        dossier.getBatchNumber()
                ));
            } else {
                assessments.add(new RecordAssessmentDto(
                        "Actual Yield Quantity",
                        RecordLifecycleClassification.GENUINELY_MISSING,
                        "Batch status is COMPLETED, but actual yield quantity is unrecorded in MES records.",
                        dossier.getBatchNumber()
                ));
            }
        } else if (status == BatchStatus.CANCELLED) {
            if (actualYield != null) {
                assessments.add(new RecordAssessmentDto(
                        "Actual Yield Quantity",
                        RecordLifecycleClassification.PRESENT,
                        "Recorded partial/terminal yield: " + actualYield.toPlainString() + (unit.isEmpty() ? "" : " " + unit)
                                + " (Note: Batch was cancelled prior to completion; yield value reflects partial or terminal output, not a successfully completed production batch; yield reconciliation not applicable).",
                        dossier.getBatchNumber()
                ));
            } else {
                assessments.add(new RecordAssessmentDto(
                        "Actual Yield Quantity",
                        RecordLifecycleClassification.NOT_APPLICABLE,
                        "Batch was cancelled; yield reconciliation is not applicable.",
                        dossier.getBatchNumber()
                ));
            }
        }

        // ---------------------------------------------------------------------
        // 4. Electronic Batch Record (eBR Execution Steps)
        // ---------------------------------------------------------------------
        if (status == BatchStatus.PLANNED) {
            if (steps.isEmpty()) {
                assessments.add(new RecordAssessmentDto(
                        "eBR Execution Steps",
                        RecordLifecycleClassification.NOT_YET_EXPECTED,
                        "Execution steps have not been initialized as batch execution has not started.",
                        dossier.getBatchNumber()
                ));
            } else {
                assessments.add(new RecordAssessmentDto(
                        "eBR Execution Steps",
                        RecordLifecycleClassification.PRESENT,
                        steps.size() + " step(s) pre-configured in master recipe.",
                        dossier.getBatchNumber()
                ));
            }
        } else if (status == BatchStatus.IN_PROGRESS) {
            if (steps.isEmpty()) {
                assessments.add(new RecordAssessmentDto(
                        "eBR Execution Steps",
                        RecordLifecycleClassification.GENUINELY_MISSING,
                        "Batch status is IN_PROGRESS, but zero execution steps are recorded or initialized.",
                        dossier.getBatchNumber()
                ));
            } else {
                long completed = steps.stream().filter(s -> s.getStatus() == ExecutionStepStatus.COMPLETED).count();
                assessments.add(new RecordAssessmentDto(
                        "eBR Execution Steps",
                        RecordLifecycleClassification.PRESENT,
                        completed + " of " + steps.size() + " step(s) completed in sequential progression.",
                        dossier.getBatchNumber()
                ));
            }
        } else if (status == BatchStatus.COMPLETED) {
            if (steps.isEmpty()) {
                assessments.add(new RecordAssessmentDto(
                        "eBR Execution Steps",
                        RecordLifecycleClassification.GENUINELY_MISSING,
                        "Batch is COMPLETED, but zero execution steps are recorded in the electronic dossier.",
                        dossier.getBatchNumber()
                ));
            } else {
                List<String> stepGaps = new ArrayList<>();
                for (BatchExecutionStepResponseDto s : steps) {
                    if (s.getStatus() != ExecutionStepStatus.COMPLETED) {
                        stepGaps.add("Step " + s.getStepNumber() + " remains in " + s.getStatus() + " status");
                    } else {
                        if (s.getExecutedBy() == null || s.getExecutedBy().isBlank()) {
                            stepGaps.add("Step " + s.getStepNumber() + " missing operator sign-off");
                        }
                        if (s.getCompletedAt() == null) {
                            stepGaps.add("Step " + s.getStepNumber() + " missing completion timestamp");
                        }
                    }
                }
                if (stepGaps.isEmpty()) {
                    assessments.add(new RecordAssessmentDto(
                            "eBR Execution Steps",
                            RecordLifecycleClassification.PRESENT,
                            "All " + steps.size() + " step(s) completed with verified operator sign-offs and timestamps.",
                            dossier.getBatchNumber()
                    ));
                } else {
                    assessments.add(new RecordAssessmentDto(
                            "eBR Execution Steps",
                            RecordLifecycleClassification.GENUINELY_MISSING,
                            steps.size() + " step(s) recorded with " + stepGaps.size() + " documentation gap(s): " + String.join(", ", stepGaps),
                            dossier.getBatchNumber()
                    ));
                }
            }
        } else if (status == BatchStatus.CANCELLED) {
            if (steps.isEmpty()) {
                if (dossier.getStartTime() == null) {
                    assessments.add(new RecordAssessmentDto(
                            "eBR Execution Steps",
                            RecordLifecycleClassification.NOT_APPLICABLE,
                            "Batch was cancelled prior to production initiation; execution steps were not initialized as manufacturing did not commence.",
                            dossier.getBatchNumber(),
                            false
                    ));
                } else {
                    assessments.add(new RecordAssessmentDto(
                            "eBR Execution Steps",
                            RecordLifecycleClassification.UNKNOWN,
                            "Batch was started prior to cancellation (start timestamp: " + dossier.getStartTime() + "), but zero execution steps are recorded in the electronic dossier; execution step history cannot be established.",
                            dossier.getBatchNumber(),
                            true
                    ));
                }
            } else {
                long completed = steps.stream().filter(s -> s.getStatus() == ExecutionStepStatus.COMPLETED).count();
                long inProgress = steps.stream().filter(s -> s.getStatus() == ExecutionStepStatus.IN_PROGRESS).count();
                long pending = steps.stream().filter(s -> s.getStatus() == ExecutionStepStatus.PENDING).count();

                List<String> stepGaps = new ArrayList<>();
                for (BatchExecutionStepResponseDto s : steps) {
                    String stepLabel = "Step " + (s.getStepNumber() != null ? s.getStepNumber() : "?");
                    if (s.getStatus() == ExecutionStepStatus.COMPLETED) {
                        if (s.getExecutedBy() == null || s.getExecutedBy().isBlank()) {
                            stepGaps.add(stepLabel + " missing operator sign-off");
                        }
                        if (s.getCompletedAt() == null) {
                            stepGaps.add(stepLabel + " missing completion timestamp");
                        }
                    } else if (s.getStatus() == ExecutionStepStatus.IN_PROGRESS) {
                        if (s.getStartedAt() == null) {
                            stepGaps.add(stepLabel + " in IN_PROGRESS status missing start timestamp");
                        }
                    } else if (s.getStatus() == null) {
                        stepGaps.add(stepLabel + " has null or unsupported execution status");
                    }
                    // For PENDING: legitimately unexecuted prior to cancellation; not a gap
                }

                if (!stepGaps.isEmpty()) {
                    assessments.add(new RecordAssessmentDto(
                            "eBR Execution Steps",
                            RecordLifecycleClassification.UNKNOWN,
                            steps.size() + " execution step(s) recorded prior to batch cancellation (" + completed + " completed, "
                                    + inProgress + " in-progress, " + pending + " pending), with " + stepGaps.size()
                                    + " documentation gap(s) in historical records: " + String.join(", ", stepGaps)
                                    + ". (Note: Batch was cancelled prior to full recipe execution; standard completion review not applicable).",
                            dossier.getBatchNumber(),
                            true
                    ));
                } else {
                    String explanation;
                    if (completed == steps.size()) {
                        explanation = "All " + steps.size() + " execution step(s) were completed prior to batch cancellation with verified operator sign-offs and timestamps. (Note: Batch was cancelled; execution records are preserved as historical evidence, not proof of successful overall batch completion; standard completion review not applicable).";
                    } else if (completed > 0) {
                        explanation = steps.size() + " execution step(s) recorded prior to batch cancellation (" + completed + " completed, "
                                + inProgress + " in-progress, " + pending + " pending). Historical execution evidence recorded prior to cancellation is preserved; unexecuted steps were not completed due to batch cancellation (standard completion review not applicable).";
                    } else {
                        explanation = steps.size() + " execution step(s) recorded prior to batch cancellation (" + completed + " completed, "
                                + inProgress + " in-progress, " + pending + " pending). Steps were not completed due to batch cancellation; standard completion review not applicable.";
                    }
                    assessments.add(new RecordAssessmentDto(
                            "eBR Execution Steps",
                            RecordLifecycleClassification.PRESENT,
                            explanation,
                            dossier.getBatchNumber(),
                            false
                    ));
                }
            }
        } else {
            assessments.add(new RecordAssessmentDto(
                    "eBR Execution Steps",
                    RecordLifecycleClassification.NOT_APPLICABLE,
                    "Batch is " + status + "; standard eBR execution review not applicable.",
                    dossier.getBatchNumber()
            ));
        }

        // ---------------------------------------------------------------------
        // 5. Quality Control (QC) Inspections
        // ---------------------------------------------------------------------
        assessments.add(classifyQualityControl(dossier, status, checks));

        // ---------------------------------------------------------------------
        // 6. Deviations & CAPA Records
        // ---------------------------------------------------------------------
        if (!deviations.isEmpty()) {
            assessments.add(classifyDeviations(dossier, deviations));
        }

        // ---------------------------------------------------------------------
        // 7. Material Issue & Lot Traceability
        // ---------------------------------------------------------------------
        assessments.add(classifyMaterialIssue(dossier, status, requirements, movements, steps));

        // ---------------------------------------------------------------------
        // 8. QA Disposition Review
        // ---------------------------------------------------------------------
        if (qaStatus == BatchQualityStatus.PENDING_QA) {
            String expl;
            RecordLifecycleClassification classification;
            if (status == BatchStatus.PLANNED) {
                expl = "Authoritative QA status is PENDING_QA. Batch execution status is PLANNED (manufacturing has not started). Available lifecycle data does not establish whether formal QA review is currently due.";
                classification = RecordLifecycleClassification.NOT_YET_EXPECTED;
            } else if (status == BatchStatus.CANCELLED) {
                expl = "Authoritative QA status is PENDING_QA. Batch was cancelled prior to QA disposition; formal QA review was not concluded and is not applicable.";
                classification = RecordLifecycleClassification.NOT_APPLICABLE;
            } else if (status == BatchStatus.IN_PROGRESS) {
                expl = "Authoritative QA status is PENDING_QA. Manufacturing operations are currently in progress; formal QA review occurs upon manufacturing completion.";
                classification = RecordLifecycleClassification.NOT_YET_EXPECTED;
            } else {
                expl = "Authoritative QA status is PENDING_QA. Awaiting formal QA review; timeliness cannot be determined because no authoritative QA review deadline or SLA is configured.";
                classification = RecordLifecycleClassification.NOT_YET_EXPECTED;
            }
            assessments.add(new RecordAssessmentDto(
                    "QA Disposition Review",
                    classification,
                    expl,
                    dossier.getBatchNumber(),
                    false
            ));
        } else {
            assessments.add(new RecordAssessmentDto(
                    "QA Disposition Review",
                    RecordLifecycleClassification.PRESENT,
                    "Formal QA disposition concluded: " + qaStatus + ".",
                    dossier.getBatchNumber(),
                    false
            ));
        }

        return assessments;
    }

    private RecordAssessmentDto classifyQualityControl(BatchTraceabilityDossierDto dossier,
                                                       BatchStatus status,
                                                       List<QualityCheckResponseDto> checks) {
        String batchRef = dossier.getBatchNumber();

        if (status == BatchStatus.CANCELLED) {
            if (checks.isEmpty()) {
                return new RecordAssessmentDto(
                        "Quality Control (QC) Inspections",
                        RecordLifecycleClassification.NOT_APPLICABLE,
                        "Batch was cancelled; no QC inspections were recorded prior to cancellation and testing is not applicable.",
                        batchRef,
                        false
                );
            }

            long passCount = checks.stream().filter(c -> c.getResult() == QualityCheckResult.PASS).count();
            long failCount = checks.stream().filter(c -> c.getResult() == QualityCheckResult.FAIL).count();
            long pendingCount = checks.stream().filter(c -> c.getResult() == QualityCheckResult.PENDING).count();
            long unknownResultCount = checks.stream().filter(c -> c.getResult() == null).count();

            List<String> qcGaps = new ArrayList<>();
            for (QualityCheckResponseDto qc : checks) {
                String testName = (qc.getTestName() != null && !qc.getTestName().isBlank())
                        ? qc.getTestName()
                        : "Unnamed Test";

                // 1. Result status validation
                if (qc.getResult() == null) {
                    qcGaps.add("Test '" + testName + "' has null or unsupported result status");
                } else if (qc.getResult() == QualityCheckResult.PASS || qc.getResult() == QualityCheckResult.FAIL) {
                    // 2. Analytical measured value validation (mandatory for completed PASS/FAIL checks)
                    if (qc.getMeasuredValue() == null || qc.getMeasuredValue().isBlank()) {
                        qcGaps.add("Test '" + testName + "' (" + qc.getResult() + ") missing measured value");
                    }
                }
                // For PENDING checks: null or blank measuredValue is legitimate and permitted (measurement not completed prior to cancellation)

                // 3. Inspector sign-off validation (mandatory for all recorded checks)
                if (qc.getInspectorName() == null || qc.getInspectorName().isBlank()) {
                    qcGaps.add("Test '" + testName + "' missing inspector sign-off");
                }
            }

            String countsSummary = passCount + " PASS, " + failCount + " FAIL, " + pendingCount + " PENDING"
                    + (unknownResultCount > 0 ? ", " + unknownResultCount + " UNKNOWN_STATUS" : "");

            if (!qcGaps.isEmpty()) {
                return new RecordAssessmentDto(
                        "Quality Control (QC) Inspections",
                        RecordLifecycleClassification.UNKNOWN,
                        checks.size() + " inspection(s) recorded prior to batch cancellation ("
                                + countsSummary + "), with "
                                + qcGaps.size() + " documentation gap(s) in historical records: " + String.join(", ", qcGaps)
                                + ". (Note: Batch was cancelled; evaluation reflects historical testing performed prior to cancellation).",
                        batchRef,
                        true
                );
            }

            return new RecordAssessmentDto(
                    "Quality Control (QC) Inspections",
                    RecordLifecycleClassification.PRESENT,
                    checks.size() + " inspection(s) recorded prior to batch cancellation ("
                            + countsSummary + "). "
                            + "(Note: Batch was cancelled; evaluation reflects historical testing performed prior to cancellation; not inferred as full release testing).",
                    batchRef,
                    false
            );
        }

        if (checks.isEmpty()) {
            // Missing or inconclusive QC configuration
            return new RecordAssessmentDto(
                    "Quality Control (QC) Inspections",
                    RecordLifecycleClassification.UNKNOWN,
                    "Zero QC inspection records retrieved. Master recipe QC configuration is absent or unknown; applicability cannot be deterministically verified from available records alone (data insufficient; cannot infer QC exemption from absence of records. Note: QA release policy mandates passing required QC prior to release).",
                    batchRef,
                    true
            );
        }

        boolean allExplicitlyOptional = checks.stream().noneMatch(QualityCheckResponseDto::isRequired);
        if (allExplicitlyOptional) {
            return new RecordAssessmentDto(
                    "Quality Control (QC) Inspections",
                    RecordLifecycleClassification.NOT_APPLICABLE,
                    "All " + checks.size() + " configured QC check(s) are explicitly designated as optional in master recipe; no mandatory QC testing is required for this batch.",
                    batchRef,
                    false
            );
        }

        long requiredCount = checks.stream().filter(QualityCheckResponseDto::isRequired).count();

        if (status == BatchStatus.PLANNED) {
            return new RecordAssessmentDto(
                    "Quality Control (QC) Inspections",
                    RecordLifecycleClassification.NOT_YET_EXPECTED,
                    requiredCount + " required QC inspection(s) configured in recipe; testing is not yet expected for a PLANNED batch awaiting production initiation.",
                    batchRef,
                    false
            );
        } else if (status == BatchStatus.IN_PROGRESS) {
            return new RecordAssessmentDto(
                    "Quality Control (QC) Inspections",
                    RecordLifecycleClassification.NOT_YET_EXPECTED,
                    requiredCount + " required QC inspection(s) configured in recipe; testing is not yet expected while manufacturing operations remain in progress.",
                    batchRef,
                    false
            );
        } else if (status == BatchStatus.COMPLETED) {
            List<String> qcGaps = new ArrayList<>();
            for (QualityCheckResponseDto qc : checks) {
                if (qc.isRequired()) {
                    if (qc.getResult() == QualityCheckResult.PENDING) {
                        qcGaps.add("Required test '" + qc.getTestName() + "' remains PENDING");
                    }
                    if (qc.getMeasuredValue() == null || qc.getMeasuredValue().isBlank()) {
                        qcGaps.add("Required test '" + qc.getTestName() + "' missing measured value");
                    }
                    if (qc.getInspectorName() == null || qc.getInspectorName().isBlank()) {
                        qcGaps.add("Required test '" + qc.getTestName() + "' missing inspector sign-off");
                    }
                }
            }
            if (qcGaps.isEmpty()) {
                return new RecordAssessmentDto(
                        "Quality Control (QC) Inspections",
                        RecordLifecycleClassification.PRESENT,
                        checks.size() + " inspection(s) recorded (" + requiredCount + " required) with passing analytical results and inspector sign-offs.",
                        batchRef,
                        false
                );
            } else {
                return new RecordAssessmentDto(
                        "Quality Control (QC) Inspections",
                        RecordLifecycleClassification.GENUINELY_MISSING,
                        requiredCount + " required QC inspection(s) configured with " + qcGaps.size() + " documentation gap(s): " + String.join(", ", qcGaps),
                        batchRef,
                        false
                );
            }
        } else {
            return new RecordAssessmentDto(
                    "Quality Control (QC) Inspections",
                    RecordLifecycleClassification.NOT_APPLICABLE,
                    "Batch is " + status + "; standard QC inspection evaluation not applicable.",
                    batchRef,
                    false
            );
        }
    }

    private RecordAssessmentDto classifyDeviations(BatchTraceabilityDossierDto dossier,
                                                  List<DeviationDetailDto> deviations) {
        String batchRef = dossier.getBatchNumber();
        long openCount = deviations.stream().filter(d -> d.getStatus() != DeviationStatus.CLOSED).count();
        return new RecordAssessmentDto(
                "Deviations & CAPA",
                RecordLifecycleClassification.PRESENT,
                deviations.size() + " deviation(s) recorded in MES (" + (deviations.size() - openCount) + " CLOSED, " + openCount + " OPEN/INVESTIGATING).",
                batchRef,
                false
        );
    }

    /**
     * Produces deterministic observation note for deviations.
     */
    public String getDeviationsObservation(BatchTraceabilityDossierDto dossier) {
        if (dossier == null || dossier.getDeviations() == null || dossier.getDeviations().isEmpty()) {
            return "No linked deviations recorded in retrieved MES records (ZERO_RECORDED). Note: Absence of recorded deviations is an operational observation and does not constitute proof that no deviations occurred or that the batch is compliant or free of operational risk.";
        }
        long openCount = dossier.getDeviations().stream()
                .filter(d -> d.getStatus() != DeviationStatus.CLOSED)
                .count();
        return dossier.getDeviations().size() + " deviation(s) recorded in MES ("
                + (dossier.getDeviations().size() - openCount) + " CLOSED, "
                + openCount + " OPEN/INVESTIGATING).";
    }

    private static class MaterialReconciliationResult {
        final String materialName;
        final BigDecimal requiredQuantity;
        final String requiredUnit;
        final BigDecimal issuedQuantity;
        final String issuedUnit;
        final boolean fulfilled;
        final boolean partial;
        final boolean indeterminate;
        final String indeterminateReason;

        MaterialReconciliationResult(String materialName,
                                     BigDecimal requiredQuantity,
                                     String requiredUnit,
                                     BigDecimal issuedQuantity,
                                     String issuedUnit,
                                     boolean fulfilled,
                                     boolean partial,
                                     boolean indeterminate,
                                     String indeterminateReason) {
            this.materialName = materialName;
            this.requiredQuantity = requiredQuantity;
            this.requiredUnit = requiredUnit;
            this.issuedQuantity = issuedQuantity;
            this.issuedUnit = issuedUnit;
            this.fulfilled = fulfilled;
            this.partial = partial;
            this.indeterminate = indeterminate;
            this.indeterminateReason = indeterminateReason;
        }
    }

    private String normalizeKey(String val) {
        if (val == null) {
            return "";
        }
        return val.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String resolveRequirementGroupKey(OrderMaterialRequirementDto req) {
        if (req.getRawMaterialId() != null) {
            return "ID:" + req.getRawMaterialId();
        }
        if (req.getRawMaterialCode() != null && !req.getRawMaterialCode().isBlank()) {
            return "CODE:" + req.getRawMaterialCode().trim().toUpperCase(Locale.ROOT);
        }
        String name = req.getRecipeMaterialName() != null && !req.getRecipeMaterialName().isBlank()
                ? req.getRecipeMaterialName() : req.getRawMaterialName();
        if (name != null && !name.isBlank()) {
            return "NAME:" + normalizeKey(name);
        }
        return "UNKNOWN:" + System.identityHashCode(req);
    }

    private boolean matchesMaterial(OrderMaterialRequirementDto req, StockMovementResponseDto m, int totalRequirementsCount) {
        if (req.getRawMaterialId() != null && m.getRawMaterialId() != null) {
            return Objects.equals(req.getRawMaterialId(), m.getRawMaterialId());
        }
        if (req.getRawMaterialCode() != null && !req.getRawMaterialCode().isBlank()
                && m.getMaterialCode() != null && !m.getMaterialCode().isBlank()) {
            return req.getRawMaterialCode().trim().equalsIgnoreCase(m.getMaterialCode().trim());
        }
        String reqName = req.getRecipeMaterialName() != null && !req.getRecipeMaterialName().isBlank()
                ? req.getRecipeMaterialName() : req.getRawMaterialName();
        String moveName = m.getMaterialName();
        if (reqName != null && moveName != null && !moveName.isBlank()) {
            return normalizeKey(reqName).equals(normalizeKey(moveName));
        }
        // If there is only 1 requirement in the recipe, and the movement has no material identity set on the DTO
        if (totalRequirementsCount == 1
                && m.getRawMaterialId() == null
                && (m.getMaterialCode() == null || m.getMaterialCode().isBlank())
                && (m.getMaterialName() == null || m.getMaterialName().isBlank())) {
            return true;
        }
        return false;
    }

    private RecordAssessmentDto classifyMaterialIssue(BatchTraceabilityDossierDto dossier,
                                                     BatchStatus status,
                                                     List<OrderMaterialRequirementDto> requirements,
                                                     List<StockMovementResponseDto> movements,
                                                     List<BatchExecutionStepResponseDto> steps) {
        String batchRef = dossier.getBatchNumber();
        Long batchId = dossier.getBatchId();

        // 1. Filter batch-specific movements with explicit batch attribution only
        List<StockMovementResponseDto> candidateBatchMovements = movements.stream()
                .filter(m -> (batchId != null && Objects.equals(m.getBatchId(), batchId))
                        || (batchRef != null && batchRef.equalsIgnoreCase(m.getBatchNumber())))
                .toList();

        // Deduplicate movements based on actual movement identity (ID or movementNumber).
        // Upstream service guarantee: Every StockMovement database entity has an authoritative primary key ID
        // and unique movementNumber. Fallback: If both ID and movementNumber are null (e.g. test stubs),
        // distinct DTO object instances are preserved via reference identity (m) to avoid collapsing
        // legitimate separate movements.
        Map<Object, StockMovementResponseDto> distinctMovements = new LinkedHashMap<>();
        for (StockMovementResponseDto m : candidateBatchMovements) {
            Object key = m.getId() != null ? m.getId()
                    : (m.getMovementNumber() != null && !m.getMovementNumber().isBlank() ? m.getMovementNumber() : m);
            distinctMovements.putIfAbsent(key, m);
        }
        List<StockMovementResponseDto> batchMovements = new ArrayList<>(distinctMovements.values());

        // Global validation of batch-attributed movement types:
        // Inspect every movement in batchMovements before filtering down to MATERIAL_ISSUE.
        // - Null or unsupported/unrecognized movement types mark assessment as indeterminate.
        // - Recognized GOODS_RECEIPT and STOCK_ADJUSTMENT do not count as issues and are not flagged as unknown.
        // - Recognized MATERIAL_ISSUE movements proceed to reconciliation.
        boolean batchHasUnknownMovementType = false;
        for (StockMovementResponseDto m : batchMovements) {
            if (m.getMovementType() == null
                    || (m.getMovementType() != StockMovementType.MATERIAL_ISSUE
                    && m.getMovementType() != StockMovementType.GOODS_RECEIPT
                    && m.getMovementType() != StockMovementType.STOCK_ADJUSTMENT)) {
                batchHasUnknownMovementType = true;
                break;
            }
        }

        // Separate MATERIAL_ISSUE movements from other movement types:
        // - Only MATERIAL_ISSUE movements may contribute to issued quantities
        // - GOODS_RECEIPT and STOCK_ADJUSTMENT must never contribute to material issued
        List<StockMovementResponseDto> issueMovements = batchMovements.stream()
                .filter(m -> m.getMovementType() == StockMovementType.MATERIAL_ISSUE)
                .toList();

        // 2. Lifecycle stages where production has not commenced or was cancelled
        if (status == BatchStatus.PLANNED) {
            String recordEx = issueMovements.isEmpty()
                    ? "Record existence: No batch-specific issue record found."
                    : "Record existence: " + issueMovements.size() + " batch-specific issue record(s) found.";
            return new RecordAssessmentDto(
                    "Material Issue & Lot Traceability",
                    RecordLifecycleClassification.NOT_YET_EXPECTED,
                    "Batch execution has not commenced. Raw material dispensing and lot issue occur upon or during execution initiation. "
                            + recordEx + " (Consumed: tracked via step execution / yield reconciliation upon completion; not inferred from inventory movements)",
                    batchRef,
                    false
            );
        }

        if (status == BatchStatus.CANCELLED && issueMovements.isEmpty()) {
            if (batchHasUnknownMovementType) {
                return new RecordAssessmentDto(
                        "Material Issue & Lot Traceability",
                        RecordLifecycleClassification.UNKNOWN,
                        "Batch was cancelled, but batch-attributed movement records contain an unknown or unsupported movement type; material issue history cannot be reliably determined. Material consumption cannot be established from the available movement records.",
                        batchRef,
                        true
                );
            }
            return new RecordAssessmentDto(
                    "Material Issue & Lot Traceability",
                    RecordLifecycleClassification.NOT_APPLICABLE,
                    "Batch was cancelled; no material issue movements were recorded prior to cancellation.",
                    batchRef,
                    false
            );
        }

        // 3. Both requirements and movements empty
        if (requirements.isEmpty() && issueMovements.isEmpty()) {
            return new RecordAssessmentDto(
                    "Material Issue & Lot Traceability",
                    RecordLifecycleClassification.UNKNOWN,
                    "No BOM requirements or material movements configured for this batch in available records. Record existence: No batch-specific issue record found. Quantity fulfilment indeterminate (recipe BOM master data not retrieved). (Consumed: tracked via step execution / yield reconciliation upon completion; not inferred from inventory movements)",
                    batchRef,
                    true
            );
        }

        // 4. Requirements empty but issue movements exist: cannot verify fulfilment without BOM master data
        if (requirements.isEmpty() && !issueMovements.isEmpty()) {
            return new RecordAssessmentDto(
                    "Material Issue & Lot Traceability",
                    RecordLifecycleClassification.UNKNOWN,
                    "Record existence: " + issueMovements.size() + " batch-specific issue record(s) found with assigned lots. Quantity fulfilment indeterminate: BOM requirements master data not retrieved; cannot verify fulfilment without authoritative recipe requirements. (Consumed: tracked via step execution / yield reconciliation upon completion; not inferred from inventory movements)",
                    batchRef,
                    true
            );
        }

        // 5. Pre-aggregate/group duplicate requirement rows by authoritative material identity
        Map<String, OrderMaterialRequirementDto> groupedReqMap = new LinkedHashMap<>();
        boolean duplicateRequirementUnitMismatch = false;
        String duplicateRequirementMismatchDetails = null;

        for (OrderMaterialRequirementDto req : requirements) {
            String groupKey = resolveRequirementGroupKey(req);
            if (groupKey.startsWith("UNKNOWN:")) {
                groupedReqMap.put(groupKey, req);
                continue;
            }

            OrderMaterialRequirementDto existing = groupedReqMap.get(groupKey);
            if (existing == null) {
                OrderMaterialRequirementDto copy = new OrderMaterialRequirementDto();
                copy.setRawMaterialId(req.getRawMaterialId());
                copy.setRawMaterialCode(req.getRawMaterialCode());
                copy.setRawMaterialName(req.getRawMaterialName());
                copy.setRecipeMaterialName(req.getRecipeMaterialName());
                copy.setRecipeUnit(req.getRecipeUnit());
                copy.setInventoryUnit(req.getInventoryUnit());
                copy.setQuantityPerBatch(req.getQuantityPerBatch());
                copy.setTotalRequiredQuantity(req.getTotalRequiredQuantity());
                copy.setIssuedQuantity(req.getIssuedQuantity());
                copy.setFulfilled(req.isFulfilled());
                groupedReqMap.put(groupKey, copy);
            } else {
                // Duplicate row for same material identity: validate unit compatibility before summing
                String unit1 = existing.getRecipeUnit() != null && !existing.getRecipeUnit().isBlank()
                        ? existing.getRecipeUnit().trim()
                        : (existing.getInventoryUnit() != null ? existing.getInventoryUnit().trim() : null);
                String unit2 = req.getRecipeUnit() != null && !req.getRecipeUnit().isBlank()
                        ? req.getRecipeUnit().trim()
                        : (req.getInventoryUnit() != null ? req.getInventoryUnit().trim() : null);

                if (unit1 == null || unit2 == null || !unit1.equalsIgnoreCase(unit2)) {
                    duplicateRequirementUnitMismatch = true;
                    duplicateRequirementMismatchDetails = "duplicate requirement rows for material '"
                            + (existing.getRecipeMaterialName() != null ? existing.getRecipeMaterialName() : groupKey)
                            + "' have conflicting units ('" + unit1 + "' vs '" + unit2 + "')";
                    break;
                }

                // Aggregate required quantities using BigDecimal
                if (existing.getQuantityPerBatch() != null && req.getQuantityPerBatch() != null) {
                    existing.setQuantityPerBatch(existing.getQuantityPerBatch().add(req.getQuantityPerBatch()));
                } else if (req.getQuantityPerBatch() != null) {
                    existing.setQuantityPerBatch(req.getQuantityPerBatch());
                }

                if (existing.getTotalRequiredQuantity() != null && req.getTotalRequiredQuantity() != null) {
                    existing.setTotalRequiredQuantity(existing.getTotalRequiredQuantity().add(req.getTotalRequiredQuantity()));
                } else if (req.getTotalRequiredQuantity() != null) {
                    existing.setTotalRequiredQuantity(req.getTotalRequiredQuantity());
                }
            }
        }

        if (duplicateRequirementUnitMismatch) {
            String recordEx = issueMovements.isEmpty()
                    ? "Record existence: No batch-specific issue record found."
                    : "Record existence: " + issueMovements.size() + " batch-specific issue record(s) found with assigned lots.";
            return new RecordAssessmentDto(
                    "Material Issue & Lot Traceability",
                    RecordLifecycleClassification.UNKNOWN,
                    recordEx + " Quantity fulfilment indeterminate: " + duplicateRequirementMismatchDetails
                            + "; cannot aggregate requirements with conflicting units. (Consumed: tracked via step execution / yield reconciliation upon completion; not inferred from inventory movements)",
                    batchRef,
                    true
            );
        }

        List<OrderMaterialRequirementDto> effectiveRequirements = new ArrayList<>(groupedReqMap.values());

        // 6. Per-material reconciliation across individual grouped BOM requirements
        Integer targetBatches = dossier.getTargetBatchCount();
        boolean isMultiBatchOrder = targetBatches != null && targetBatches > 1;

        List<MaterialReconciliationResult> results = new ArrayList<>();
        boolean anyIndeterminate = false;
        String primaryIndeterminateReason = null;
        boolean anyUnfulfilled = false;
        boolean allFulfilled = true;

        if (batchHasUnknownMovementType) {
            anyIndeterminate = true;
            allFulfilled = false;
            primaryIndeterminateReason = "movement record has null or unknown movement type; cannot verify as MATERIAL_ISSUE";
        }

        for (OrderMaterialRequirementDto req : effectiveRequirements) {
            String matName = req.getRecipeMaterialName() != null && !req.getRecipeMaterialName().isBlank()
                    ? req.getRecipeMaterialName()
                    : (req.getRawMaterialName() != null ? req.getRawMaterialName() : "Material");

            String reqUnit = req.getRecipeUnit() != null && !req.getRecipeUnit().isBlank()
                    ? req.getRecipeUnit().trim()
                    : (req.getInventoryUnit() != null && !req.getInventoryUnit().isBlank() ? req.getInventoryUnit().trim() : null);

            // Determine batch-level required quantity:
            // Prefer quantityPerBatch. If quantityPerBatch is null:
            //   - For single-batch order (or unspecified): totalRequiredQuantity represents this batch's requirement.
            //   - For multi-batch order: order-level total cannot be used as batch requirement.
            BigDecimal batchRequired = req.getQuantityPerBatch();
            boolean multiBatchMissingPerBatch = false;
            if (batchRequired == null) {
                if (isMultiBatchOrder) {
                    multiBatchMissingPerBatch = true;
                } else {
                    batchRequired = req.getTotalRequiredQuantity();
                }
            }

            // Find matching batch movements for this material identity
            List<StockMovementResponseDto> matMovements = batchMovements.stream()
                    .filter(m -> matchesMaterial(req, m, effectiveRequirements.size()))
                    .toList();

            boolean matHasNullDelta = false;
            boolean matHasPositiveOrReversalDelta = false;
            boolean matHasUnitMismatch = false;
            boolean matHasUnknownMovementType = false;
            String mismatchedUnitReason = null;
            BigDecimal issuedSum = BigDecimal.ZERO;

            for (StockMovementResponseDto m : matMovements) {
                // Movement type validation:
                // Only MATERIAL_ISSUE movements contribute to issued material quantities.
                // GOODS_RECEIPT and STOCK_ADJUSTMENT are not issues and do not trigger reversal safeguards.
                if (m.getMovementType() == null) {
                    matHasUnknownMovementType = true;
                    continue;
                }
                if (m.getMovementType() != StockMovementType.MATERIAL_ISSUE) {
                    // Non-issue movements (e.g. GOODS_RECEIPT, STOCK_ADJUSTMENT) do not count as issues
                    continue;
                }

                // Quantity delta validation for MATERIAL_ISSUE
                if (m.getQuantityDelta() == null) {
                    matHasNullDelta = true;
                } else if (m.getQuantityDelta().compareTo(BigDecimal.ZERO) >= 0) {
                    // Positive delta under MATERIAL_ISSUE represents an invalid issue or unsupported reversal:
                    // PharmaTrack MES schema lacks reversal pairing and return movement types;
                    // positive deltas cannot be reliably netted or verified.
                    matHasPositiveOrReversalDelta = true;
                } else {
                    issuedSum = issuedSum.add(m.getQuantityDelta().negate());
                }

                // Strict unit validation:
                // - Both missing -> UNKNOWN (measurement unit cannot be established)
                // - One missing and one present -> UNKNOWN
                // - Different units -> UNKNOWN (no automatic conversion)
                // - Case-insensitive match (kg vs KG) -> valid
                String moveUnit = m.getUnit() != null && !m.getUnit().isBlank() ? m.getUnit().trim() : null;
                if (reqUnit == null && moveUnit == null) {
                    matHasUnitMismatch = true;
                    mismatchedUnitReason = "both requirement and movement units are missing; measurement unit cannot be established";
                } else if (reqUnit != null && moveUnit == null) {
                    matHasUnitMismatch = true;
                    mismatchedUnitReason = "requirement specifies unit '" + reqUnit + "' but movement record lacks unit";
                } else if (reqUnit == null && moveUnit != null) {
                    matHasUnitMismatch = true;
                    mismatchedUnitReason = "movement specifies unit '" + moveUnit + "' but requirement record lacks unit";
                } else if (!reqUnit.equalsIgnoreCase(moveUnit)) {
                    matHasUnitMismatch = true;
                    mismatchedUnitReason = "unit mismatch between requirement (" + reqUnit + ") and movement (" + moveUnit + "); no unit conversion mechanism exists in MES";
                }
            }

            // Evaluate result for this material
            if (multiBatchMissingPerBatch) {
                anyIndeterminate = true;
                allFulfilled = false;
                if (primaryIndeterminateReason == null) {
                    primaryIndeterminateReason = "order contains multiple batches (" + targetBatches
                            + ") and batch requirement cannot be established from order total without quantityPerBatch";
                }
                results.add(new MaterialReconciliationResult(matName, null, reqUnit, issuedSum, reqUnit,
                        false, false, true, "batch requirement indeterminate from multi-batch order"));
            } else if (matHasUnknownMovementType) {
                anyIndeterminate = true;
                allFulfilled = false;
                if (primaryIndeterminateReason == null) {
                    primaryIndeterminateReason = "movement record has null or unknown movement type; cannot verify as MATERIAL_ISSUE";
                }
                results.add(new MaterialReconciliationResult(matName, batchRequired, reqUnit, null, reqUnit,
                        false, false, true, "unknown movement type"));
            } else if (matHasNullDelta) {
                anyIndeterminate = true;
                allFulfilled = false;
                if (primaryIndeterminateReason == null) {
                    primaryIndeterminateReason = "stock movement record has null/missing quantity delta";
                }
                results.add(new MaterialReconciliationResult(matName, batchRequired, reqUnit, null, reqUnit,
                        false, false, true, "null/missing quantity delta in movement record"));
            } else if (matHasPositiveOrReversalDelta) {
                anyIndeterminate = true;
                allFulfilled = false;
                if (primaryIndeterminateReason == null) {
                    primaryIndeterminateReason = "MATERIAL_ISSUE movements contain positive or reversal deltas without authoritative reversal linkage; PharmaTrack MES schema does not support reversal pairing or return movement reconciliation; reliable net issue quantity cannot be established from records alone";
                }
                results.add(new MaterialReconciliationResult(matName, batchRequired, reqUnit, null, reqUnit,
                        false, false, true, "positive or reversal delta without authoritative reversal linkage"));
            } else if (matHasUnitMismatch) {
                anyIndeterminate = true;
                allFulfilled = false;
                if (primaryIndeterminateReason == null) {
                    primaryIndeterminateReason = mismatchedUnitReason;
                }
                results.add(new MaterialReconciliationResult(matName, batchRequired, reqUnit, issuedSum, reqUnit,
                        false, false, true, mismatchedUnitReason));
            } else if (batchRequired == null) {
                anyIndeterminate = true;
                allFulfilled = false;
                if (primaryIndeterminateReason == null) {
                    primaryIndeterminateReason = "requirement quantity is missing from recipe master data";
                }
                results.add(new MaterialReconciliationResult(matName, null, reqUnit, issuedSum, reqUnit,
                        false, false, true, "requirement quantity missing"));
            } else if (issuedSum.compareTo(batchRequired) >= 0) {
                results.add(new MaterialReconciliationResult(matName, batchRequired, reqUnit, issuedSum, reqUnit,
                        true, false, false, null));
            } else {
                anyUnfulfilled = true;
                allFulfilled = false;
                results.add(new MaterialReconciliationResult(matName, batchRequired, reqUnit, issuedSum, reqUnit,
                        false, true, false, null));
            }
        }

        // Check for batch issue movements belonging to materials NOT in effectiveRequirements
        boolean hasUnmatchedMovements = false;
        for (StockMovementResponseDto m : issueMovements) {
            boolean matched = false;
            for (OrderMaterialRequirementDto req : effectiveRequirements) {
                if (matchesMaterial(req, m, effectiveRequirements.size())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                hasUnmatchedMovements = true;
                break;
            }
        }
        if (hasUnmatchedMovements) {
            anyIndeterminate = true;
            allFulfilled = false;
            if (primaryIndeterminateReason == null) {
                primaryIndeterminateReason = "batch issue movements recorded for materials not present in BOM requirements";
            }
        }

        // 7. Build deterministic outputs distinguishing existence from quantity fulfilment
        String recordExistenceStr = issueMovements.isEmpty()
                ? "Record existence: No batch-specific issue record found."
                : "Record existence: " + issueMovements.size() + " batch-specific issue record(s) found with assigned lots.";

        List<String> breakdowns = new ArrayList<>();
        List<String> unfulfilledList = new ArrayList<>();
        for (MaterialReconciliationResult res : results) {
            String unitSuffix = res.requiredUnit != null && !res.requiredUnit.isBlank() ? " " + res.requiredUnit : "";
            String reqStr = res.requiredQuantity != null ? res.requiredQuantity.toPlainString() + unitSuffix : "N/A";
            String issStr = res.issuedQuantity != null ? res.issuedQuantity.toPlainString() + unitSuffix : "0" + unitSuffix;
            breakdowns.add(res.materialName + " (Required: " + reqStr + ", Issued: " + issStr + ")");
            if (res.partial) {
                unfulfilledList.add(res.materialName + " (Required: " + reqStr + ", Issued: " + issStr + ")");
            }
        }
        String breakdownSummary = String.join("; ", breakdowns);

        // A. Indeterminate condition
        if (anyIndeterminate) {
            String expl = recordExistenceStr + " Quantity fulfilment indeterminate: " + primaryIndeterminateReason
                    + " (" + breakdownSummary + "; Consumed: tracked via step execution / yield reconciliation upon completion; not inferred from inventory movements).";
            if (status == BatchStatus.COMPLETED) {
                expl += " (limitation: MES batch completion workflow does not enforce stock movement reconciliation)";
            } else if (status == BatchStatus.IN_PROGRESS) {
                expl += " (limitation: recipe execution steps lack BOM foreign keys; generic step status cannot establish individual material milestones)";
            } else if (status == BatchStatus.CANCELLED) {
                expl += " (Note: Batch was cancelled; evaluation reflects material movements recorded prior to cancellation)";
            }
            return new RecordAssessmentDto(
                    "Material Issue & Lot Traceability",
                    RecordLifecycleClassification.UNKNOWN,
                    expl,
                    batchRef,
                    true
            );
        }

        // B. Unfulfilled / Partial condition
        if (anyUnfulfilled || !allFulfilled) {
            String unfulfilledSummary = String.join(", ", unfulfilledList);
            if (status == BatchStatus.CANCELLED) {
                return new RecordAssessmentDto(
                        "Material Issue & Lot Traceability",
                        RecordLifecycleClassification.UNKNOWN,
                        issueMovements.size() + " batch-specific material issue(s) recorded prior to batch cancellation ("
                                + breakdownSummary + "). " + recordExistenceStr
                                + " Partial material issue recorded prior to cancellation (" + unfulfilledSummary + "). "
                                + "(Note: Batch was cancelled prior to completion; material issue records are preserved as historical evidence; not inferred as consumed).",
                        batchRef,
                        true
                );
            }

            if (status == BatchStatus.COMPLETED) {
                return new RecordAssessmentDto(
                    "Material Issue & Lot Traceability",
                    RecordLifecycleClassification.UNKNOWN,
                    "Batch status is COMPLETED with " + (issueMovements.isEmpty() ? "zero" : "incomplete")
                            + " material issue movements recorded in MES. " + recordExistenceStr
                            + " Quantity requirement unfulfilled: one or more materials remain unissued or partially issued ("
                            + unfulfilledSummary + "). Over-issue of one material does not fulfill separate material requirements. "
                            + "The batch completion workflow does not enforce inventory issue ledger reconciliation (materials may be issued at order level, managed externally, or unregistered in inventory). "
                            + "Whether material issue movements were mandatory for this batch cannot be deterministically proven from available MES records alone (limitation: MES batch completion workflow does not enforce stock movement reconciliation). "
                            + "(" + breakdownSummary + "; Consumed: tracked via step execution / yield reconciliation upon completion; not inferred from inventory movements)",
                    batchRef,
                    true
                );
            }

            // status == BatchStatus.IN_PROGRESS
            if (issueMovements.isEmpty()) {
                return new RecordAssessmentDto(
                        "Material Issue & Lot Traceability",
                        RecordLifecycleClassification.UNKNOWN,
                        "Batch is IN_PROGRESS with BOM requirements, but available execution records do not establish an authoritative structured BOM-to-step relationship (limitation: recipe execution steps lack BOM foreign keys; generic step status cannot establish individual material milestones). "
                                + recordExistenceStr + " Expected timing for specific material issues cannot be deterministically established from available MES records alone. ("
                                + breakdownSummary + "; Consumed: tracked via step execution / yield reconciliation upon completion; not inferred from inventory movements)",
                        batchRef,
                        true
                );
            }

            return new RecordAssessmentDto(
                    "Material Issue & Lot Traceability",
                    RecordLifecycleClassification.UNKNOWN,
                    "Batch is IN_PROGRESS with BOM requirements. " + recordExistenceStr
                            + " Quantity requirement unfulfilled: partial issue recorded (" + unfulfilledSummary
                            + "). Full quantity reconciliation is recorded upon batch completion (limitation: recipe execution steps lack BOM foreign keys; generic step status cannot establish individual material milestones). ("
                            + breakdownSummary + "; Consumed: tracked via step execution / yield reconciliation upon completion; not inferred from inventory movements)",
                    batchRef,
                    true
            );
        }

        // C. Quantity fulfilment established
        if (!issueMovements.isEmpty()) {
            if (status == BatchStatus.CANCELLED) {
                return new RecordAssessmentDto(
                        "Material Issue & Lot Traceability",
                        RecordLifecycleClassification.PRESENT,
                        issueMovements.size() + " batch-specific material issue(s) recorded prior to batch cancellation with assigned lots. "
                                + recordExistenceStr + " All required materials issued prior to cancellation ("
                                + breakdownSummary + "). (Note: Batch was cancelled prior to completion; material issue records are preserved as historical evidence; not inferred as consumed).",
                        batchRef,
                        false
                );
            }
            return new RecordAssessmentDto(
                    "Material Issue & Lot Traceability",
                    RecordLifecycleClassification.PRESENT,
                    issueMovements.size() + " batch-specific material issue(s) recorded with assigned lots. "
                            + recordExistenceStr + " Quantity fulfilment established: all required materials fulfilled with verified matching units ("
                            + breakdownSummary + "). (Consumed: tracked via step execution / yield reconciliation upon completion; not inferred from inventory movements)",
                    batchRef,
                    false
            );
        }

        // Zero movements with empty requirement fulfilment
        return new RecordAssessmentDto(
                "Material Issue & Lot Traceability",
                RecordLifecycleClassification.UNKNOWN,
                "Record existence: No batch-specific issue record found. Quantity fulfilment indeterminate. ("
                        + breakdownSummary + "; Consumed: tracked via step execution / yield reconciliation upon completion; not inferred from inventory movements)",
                batchRef,
                true
        );
    }

    /**
     * Extracts list of verified genuinely missing gaps for operational warnings.
     */
    public List<String> extractGenuinelyMissingGaps(List<RecordAssessmentDto> assessments) {
        if (assessments == null) {
            return Collections.emptyList();
        }
        return assessments.stream()
                .filter(a -> a.getClassification() == RecordLifecycleClassification.GENUINELY_MISSING)
                .map(a -> a.getRecordType() + ": " + a.getExplanation())
                .toList();
    }

    /**
     * Renders Section 10 documentation completeness markdown deterministically.
     */
    public String renderMissingRecordsEvaluation(BatchTraceabilityDossierDto dossier,
                                                List<RecordAssessmentDto> assessments) {
        if (dossier == null || assessments == null || assessments.isEmpty()) {
            return "No documentation assessment available.";
        }

        StringBuilder sb = new StringBuilder();
        BatchStatus status = dossier.getBatchStatus() != null ? dossier.getBatchStatus() : BatchStatus.PLANNED;

        sb.append("**Lifecycle Stage Assessment:** Batch execution status is `").append(status).append("`.\n\n");
        sb.append("**Authoritative Record Completeness Matrix:**\n\n");

        for (RecordAssessmentDto a : assessments) {
            sb.append("- **").append(a.getRecordType()).append(":** `")
                    .append(a.getClassification()).append("` — ")
                    .append(a.getExplanation()).append("\n");
        }

        List<String> genuineGaps = extractGenuinelyMissingGaps(assessments);
        sb.append("\n**Verified Documentation Gaps:**\n");
        if (genuineGaps.isEmpty()) {
            boolean hasUnknown = assessments.stream().anyMatch(a -> a.getClassification() == RecordLifecycleClassification.UNKNOWN);
            if (hasUnknown) {
                sb.append("No confirmed missing execution records identified for batch lifecycle stage `").append(status)
                        .append("`. Note: Relevant configuration or evidence is unverified/classified as UNKNOWN rather than confirmed complete.\n");
            } else {
                sb.append("None. All records are consistent with batch lifecycle stage `").append(status).append("`.\n");
            }
        } else {
            for (String gap : genuineGaps) {
                sb.append("- ").append(gap).append("\n");
            }
        }

        sb.append("\n**Deterministic Record Observations:**\n");
        sb.append("- **Deviations & CAPA:** ").append(getDeviationsObservation(dossier)).append("\n");

        return sb.toString();
    }
}
