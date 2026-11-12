package com.pharmatrack.service;

import com.pharmatrack.dto.AuditLogResponseDto;
import com.pharmatrack.dto.BatchQaReviewResponseDto;
import com.pharmatrack.dto.BatchQualityDossierDto;
import com.pharmatrack.dto.DeviationSummaryDto;
import com.pharmatrack.dto.QaDispositionRequestDto;
import com.pharmatrack.dto.QualityCheckCorrectionRequestDto;
import com.pharmatrack.dto.QualityCheckRequestDto;
import com.pharmatrack.dto.QualityCheckResponseDto;
import com.pharmatrack.dto.QualityQueueItemDto;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchQaReview;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.Deviation;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.QualityCheck;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.exception.InvalidQualityOperationException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.BatchQaReviewRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.CapaActionRepository;
import com.pharmatrack.repository.DeviationRepository;
import com.pharmatrack.repository.QualityCheckRepository;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.util.AppTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Core service for Quality Management Phase 1:
 * - Quality Inspection Queue (completed batches awaiting/undergoing QA review)
 * - Quality Control (QC) Check creation and audited correction
 * - QA Batch Disposition decisions (PENDING_QA, ON_HOLD, RELEASED, REJECTED)
 * - Append-only audit trail integration attributed to the authenticated SecurityContext user
 */
@Service
@Transactional
public class QualityService {

    public static final String ENTITY_QUALITY_CHECK = "QUALITY_CHECK";
    public static final String ENTITY_BATCH_QA_DISPOSITION = "BATCH_QA_DISPOSITION";
    public static final ZoneId APP_ZONE_ID = AppTime.APP_ZONE_ID;

    private final BatchRepository batchRepository;
    private final QualityCheckRepository qualityCheckRepository;
    private final BatchQaReviewRepository batchQaReviewRepository;
    private final DeviationRepository deviationRepository;
    private final CapaActionRepository capaActionRepository;
    private final AuditTrailService auditTrailService;
    private final AuthenticatedActorResolver authenticatedActorResolver;

    public QualityService(BatchRepository batchRepository,
                          QualityCheckRepository qualityCheckRepository,
                          BatchQaReviewRepository batchQaReviewRepository,
                          DeviationRepository deviationRepository,
                          CapaActionRepository capaActionRepository,
                          AuditTrailService auditTrailService) {
        this(batchRepository, qualityCheckRepository, batchQaReviewRepository,
                deviationRepository, capaActionRepository, auditTrailService, new AuthenticatedActorResolver());
    }

    @Autowired
    public QualityService(BatchRepository batchRepository,
                          QualityCheckRepository qualityCheckRepository,
                          BatchQaReviewRepository batchQaReviewRepository,
                          DeviationRepository deviationRepository,
                          CapaActionRepository capaActionRepository,
                          AuditTrailService auditTrailService,
                          AuthenticatedActorResolver authenticatedActorResolver) {
        this.batchRepository = batchRepository;
        this.qualityCheckRepository = qualityCheckRepository;
        this.batchQaReviewRepository = batchQaReviewRepository;
        this.deviationRepository = deviationRepository;
        this.capaActionRepository = capaActionRepository;
        this.auditTrailService = auditTrailService;
        this.authenticatedActorResolver = authenticatedActorResolver != null
                ? authenticatedActorResolver
                : new AuthenticatedActorResolver();
    }

    // -------------------------------------------------------------------------
    // A. Quality Inspection Queue
    // -------------------------------------------------------------------------

    /**
     * Retrieve completed manufacturing batches for the QA Inspection Queue,
     * optionally filtered by {@link BatchQualityStatus}.
     * Non-completed batches (PLANNED, IN_PROGRESS, CANCELLED) are strictly excluded.
     *
     * @param qaStatusFilter optional QA status filter
     * @return ordered list of completed batches with QC summary counts and latest QA review info
     */
    @Transactional(readOnly = true)
    public List<QualityQueueItemDto> getQualityInspectionQueue(BatchQualityStatus qaStatusFilter) {
        List<Batch> completedBatches = batchRepository.findByStatusWithDetailsOrderByEndTimeDesc(BatchStatus.COMPLETED);
        if (completedBatches == null || completedBatches.isEmpty()) {
            return List.of();
        }

        List<Batch> eligibleBatches = completedBatches.stream()
                .filter(batch -> batch.getStatus() == BatchStatus.COMPLETED)
                .filter(batch -> qaStatusFilter == null || resolveEffectiveQaStatus(batch) == qaStatusFilter)
                .toList();

        if (eligibleBatches.isEmpty()) {
            return List.of();
        }

        List<Long> batchIds = eligibleBatches.stream()
                .map(Batch::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, List<QualityCheck>> checksByBatchId = batchIds.isEmpty()
                ? Collections.emptyMap()
                : safeList(qualityCheckRepository.findByBatchIdIn(batchIds)).stream()
                .filter(qc -> qc.getBatch() != null && qc.getBatch().getId() != null)
                .collect(Collectors.groupingBy(qc -> qc.getBatch().getId()));

        Map<Long, List<BatchQaReview>> reviewsByBatchId = batchIds.isEmpty()
                ? Collections.emptyMap()
                : safeList(batchQaReviewRepository.findByBatchIdInOrderByReviewedAtDescIdDesc(batchIds)).stream()
                .filter(rev -> rev.getBatch() != null && rev.getBatch().getId() != null)
                .collect(Collectors.groupingBy(rev -> rev.getBatch().getId()));

        Map<Long, List<Deviation>> deviationsByBatchId = batchIds.isEmpty()
                ? Collections.emptyMap()
                : safeList(deviationRepository.findByBatchIdIn(batchIds)).stream()
                .filter(dev -> dev.getBatch() != null && dev.getBatch().getId() != null)
                .collect(Collectors.groupingBy(dev -> dev.getBatch().getId()));

        return eligibleBatches.stream()
                .sorted(Comparator
                        .comparingInt((Batch b) -> getQueuePriority(resolveEffectiveQaStatus(b)))
                        .thenComparing(Batch::getEndTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Batch::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(batch -> mapToQueueItemDto(
                        batch,
                        checksByBatchId.getOrDefault(batch.getId(), List.of()),
                        reviewsByBatchId.getOrDefault(batch.getId(), List.of()),
                        deviationsByBatchId.getOrDefault(batch.getId(), List.of())
                ))
                .toList();
    }

    // -------------------------------------------------------------------------
    // B. Batch Quality Dossier
    // -------------------------------------------------------------------------

    /**
     * Retrieve the full Quality Dossier for a completed manufacturing batch, including
     * release eligibility status, blockers, QC checks, QA disposition reviews, and audit logs.
     *
     * @param batchId target batch primary key
     * @return full dossier DTO
     */
    @Transactional(readOnly = true)
    public BatchQualityDossierDto getBatchQualityDossier(Long batchId) {
        if (batchId == null) {
            throw new ResourceNotFoundException("Batch not found with id: null");
        }

        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batchId));

        if (batch.getStatus() != BatchStatus.COMPLETED) {
            throw new InvalidQualityOperationException(
                    "Quality dossier is only available for COMPLETED batches. Batch "
                            + batch.getBatchNumber() + " is currently " + batch.getStatus() + ".");
        }

        List<QualityCheck> checks = safeList(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(batch.getId()));
        List<BatchQaReview> reviews = safeList(batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(batch.getId()));
        List<Deviation> deviations = safeList(deviationRepository.findByBatchIdWithDetailsOrderByReportedAtDesc(batch.getId()));
        List<AuditLogResponseDto> auditLogs = safeList(auditTrailService.getAuditLogs(batch.getId(), null, null));

        return buildDossierDto(batch, checks, reviews, deviations, auditLogs);
    }

    // -------------------------------------------------------------------------
    // C. QC Check Management
    // -------------------------------------------------------------------------

    /**
     * Record a new Quality Control (QC) check against a COMPLETED batch.
     * Appends an immutable {@code QC_CHECK_CREATED} audit log entry.
     *
     * @param batchId    target batch primary key
     * @param requestDto QC check input payload
     * @return created QC check DTO
     */
    public QualityCheckResponseDto createQualityCheck(Long batchId, QualityCheckRequestDto requestDto) {
        String inspectorName = authenticatedActorResolver.requireCurrentActor();
        if (batchId == null) {
            throw new InvalidQualityOperationException("Batch ID is mandatory");
        }
        if (requestDto == null) {
            throw new InvalidQualityOperationException("Quality check payload cannot be null");
        }

        Batch batch = batchRepository.findByIdWithLock(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batchId));

        validateCompletedBatchForQcModification(batch, "create QC check");

        String testName = requireNonBlank(requestDto.getTestName(), "Test name", 150);
        String specification = requireNonBlank(requestDto.getSpecification(), "Specification", 255);
        String unit = requireNonBlank(requestDto.getUnit(), "Unit", 50);
        QualityCheckResult result = requestDto.getResult();
        if (result == null) {
            throw new InvalidQualityOperationException("QC result is mandatory");
        }

        String measuredValue = validateMeasuredValue(requestDto.getMeasuredValue(), result);
        QualitySpecificationEvaluator.validateConsistency(specification, measuredValue, result);
        boolean required = requestDto.getRequired() == null || requestDto.getRequired();
        String remarks = normalizeOptional(requestDto.getRemarks(), "Remarks", 2000);

        LocalDateTime now = AppTime.nowDateTime();
        LocalDateTime checkedAt = requestDto.getCheckedAt() != null ? requestDto.getCheckedAt() : now;
        if (checkedAt.isAfter(now.plusMinutes(5))) {
            throw new InvalidQualityOperationException("QC check timestamp cannot be in the future (Asia/Kolkata)");
        }

        QualityCheck entity = new QualityCheck(
                batch,
                testName,
                specification,
                measuredValue,
                unit,
                result,
                required,
                inspectorName,
                checkedAt,
                remarks
        );

        QualityCheck saved = qualityCheckRepository.save(entity);
        Long savedId = saved.getId() != null ? saved.getId() : 0L;

        auditTrailService.recordAudit(
                ENTITY_QUALITY_CHECK,
                savedId,
                batch.getBatchNumber(),
                batch.getId(),
                "QC_CHECK_CREATED",
                inspectorName,
                checkedAt,
                "Recorded QC check: " + testName + " (" + result + ")",
                null,
                buildQcSnapshotMap(saved)
        );

        return mapToQualityCheckDto(saved);
    }

    /**
     * Auditable correction / completion mechanism for existing QC check records.
     * Derives {@code correctedBy} from the authenticated principal, enforces a mandatory
     * correction reason, and appends before/after values to the append-only audit trail.
     * Un-audited direct edits are not permitted.
     *
     * @param checkId    target QC check ID
     * @param requestDto correction payload including mandatory {@code correctionReason}
     * @return updated QC check DTO
     */
    public QualityCheckResponseDto correctQualityCheck(Long checkId, QualityCheckCorrectionRequestDto requestDto) {
        String correctedBy = authenticatedActorResolver.requireCurrentActor();
        if (checkId == null) {
            throw new InvalidQualityOperationException("QC check ID is mandatory");
        }
        if (requestDto == null) {
            throw new InvalidQualityOperationException("QC correction payload cannot be null");
        }

        QualityCheck check = qualityCheckRepository.findByIdWithBatch(checkId)
                .orElseThrow(() -> new ResourceNotFoundException("Quality check not found with id: " + checkId));

        Batch lockedBatch = batchRepository.findByIdWithLock(check.getBatch().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + check.getBatch().getId()));

        validateCompletedBatchForQcModification(lockedBatch, "correct QC check");

        String correctionReason = requireNonBlank(requestDto.getCorrectionReason(), "Correction reason", 1000);

        String newTestName = requireNonBlank(requestDto.getTestName(), "Test name", 150);
        String newSpecification = requireNonBlank(requestDto.getSpecification(), "Specification", 255);
        String newUnit = requireNonBlank(requestDto.getUnit(), "Unit", 50);
        QualityCheckResult newResult = requestDto.getResult();
        if (newResult == null) {
            throw new InvalidQualityOperationException("QC result is mandatory");
        }
        String newMeasuredValue = validateMeasuredValue(requestDto.getMeasuredValue(), newResult);
        QualitySpecificationEvaluator.validateConsistency(newSpecification, newMeasuredValue, newResult);
        boolean newRequired = requestDto.getRequired() != null ? requestDto.getRequired() : check.isRequired();
        String newRemarks = normalizeOptional(requestDto.getRemarks(), "Remarks", 2000);

        boolean changed = !Objects.equals(check.getTestName(), newTestName)
                || !Objects.equals(check.getSpecification(), newSpecification)
                || !Objects.equals(check.getMeasuredValue(), newMeasuredValue)
                || !Objects.equals(check.getUnit(), newUnit)
                || check.getResult() != newResult
                || check.isRequired() != newRequired
                || !Objects.equals(check.getRemarks(), newRemarks);

        if (!changed) {
            throw new InvalidQualityOperationException(
                    "QC correction must change at least one field on QC check #" + checkId);
        }

        Map<String, Object> beforeSnapshot = buildQcSnapshotMap(check);
        QualityCheckResult previousResult = check.getResult();
        LocalDateTime now = AppTime.nowDateTime();

        check.setTestName(newTestName);
        check.setSpecification(newSpecification);
        check.setMeasuredValue(newMeasuredValue);
        check.setUnit(newUnit);
        check.setResult(newResult);
        check.setRequired(newRequired);
        check.setRemarks(newRemarks);
        check.setCorrectedBy(correctedBy);
        check.setCorrectedAt(now);
        check.setCorrectionReason(correctionReason);
        check.setCorrectionCount((check.getCorrectionCount() != null ? check.getCorrectionCount() : 0) + 1);

        QualityCheck saved = qualityCheckRepository.save(check);

        String auditAction = previousResult == QualityCheckResult.PENDING && newResult != QualityCheckResult.PENDING
                ? "QC_CHECK_RESULT_RECORDED"
                : "QC_CHECK_CORRECTED";

        auditTrailService.recordAudit(
                ENTITY_QUALITY_CHECK,
                saved.getId() != null ? saved.getId() : checkId,
                lockedBatch.getBatchNumber(),
                lockedBatch.getId(),
                auditAction,
                correctedBy,
                now,
                correctionReason,
                beforeSnapshot,
                buildQcSnapshotMap(saved)
        );

        return mapToQualityCheckDto(saved);
    }

    // -------------------------------------------------------------------------
    // D. QA Batch Disposition
    // -------------------------------------------------------------------------

    /**
     * Record a QA disposition decision ({@code ON_HOLD}, {@code RELEASED}, {@code REJECTED})
     * against a completed batch, enforce release prerequisites, persist an immutable
     * {@link BatchQaReview}, update {@link Batch#getQaStatus()}, and append an audit log entry.
     * Does NOT alter {@link BatchStatus}, {@code actualYield}, or execution history.
     *
     * @param batchId    target batch primary key
     * @param requestDto disposition decision payload
     * @return updated {@link BatchQualityDossierDto}
     */
    public BatchQualityDossierDto recordQaDisposition(Long batchId, QaDispositionRequestDto requestDto) {
        String reviewerName = authenticatedActorResolver.requireCurrentActor();
        if (batchId == null) {
            throw new InvalidQualityOperationException("Batch ID is mandatory");
        }
        if (requestDto == null) {
            throw new InvalidQualityOperationException("QA disposition payload cannot be null");
        }

        Batch batch = batchRepository.findByIdWithLock(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batchId));

        // Rule a: Manufacturing batch status must be COMPLETED
        if (batch.getStatus() != BatchStatus.COMPLETED) {
            throw new InvalidQualityOperationException(
                    "Cannot record QA disposition for batch " + batch.getBatchNumber()
                            + " because manufacturing batch status is " + batch.getStatus()
                            + ". Only COMPLETED batches are eligible for QA disposition.");
        }

        BatchQualityStatus targetStatus = requestDto.getQaStatus();
        if (targetStatus == null || targetStatus == BatchQualityStatus.PENDING_QA) {
            throw new InvalidQualityOperationException(
                    "Target QA disposition status must be RELEASED, ON_HOLD, or REJECTED");
        }

        BatchQualityStatus previousStatus = resolveEffectiveQaStatus(batch);
        if (previousStatus == BatchQualityStatus.RELEASED || previousStatus == BatchQualityStatus.REJECTED) {
            throw new InvalidQualityOperationException(
                    "QA disposition for batch " + batch.getBatchNumber()
                            + " is already finalized as " + previousStatus + " and cannot be modified");
        }

        if (previousStatus == targetStatus) {
            throw new InvalidQualityOperationException(
                    "Batch " + batch.getBatchNumber() + " is already in " + targetStatus + " QA status");
        }

        String rationale = requireNonBlank(requestDto.getRationale(), "Decision rationale", 2000);

        List<QualityCheck> checks = safeList(qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(batch.getId()));
        QcSummary summary = summarizeChecks(checks);

        // Rules b, c, d when target is RELEASED, plus Phase 2 unresolved deviation release gate
        if (targetStatus == BatchQualityStatus.RELEASED) {
            List<String> blockingReasons = new ArrayList<>();
            if (summary.total == 0) {
                blockingReasons.add(
                        "at least one Quality Control (QC) check must be recorded first (at least one required QC check must exist and PASS)");
            } else {
                if (summary.requiredCount == 0) {
                    blockingReasons.add("at least one required QC check must exist and PASS");
                }
                long optionalFailed = Math.max(0L, summary.failed - summary.requiredFailed);
                if (summary.requiredFailed > 0) {
                    blockingReasons.add(summary.requiredFailed + " required QC check(s) have FAILED");
                }
                if (optionalFailed > 0) {
                    blockingReasons.add(optionalFailed + " optional QC check(s) have FAILED");
                }
                long optionalPending = Math.max(0L, summary.pending - summary.requiredPending);
                if (summary.requiredPending > 0 && optionalPending == 0) {
                    blockingReasons.add(summary.requiredPending + " QC check(s) are still PENDING");
                } else {
                    if (summary.requiredPending > 0) {
                        blockingReasons.add(summary.requiredPending + " required QC check(s) are still PENDING");
                    }
                    if (optionalPending > 0) {
                        blockingReasons.add(optionalPending + " optional QC check(s) are still PENDING");
                    }
                }
            }

            List<Deviation> unresolvedDeviations = safeList(
                    deviationRepository.findByBatchIdAndStatusNotWithLock(batch.getId(), DeviationStatus.CLOSED));
            if (!unresolvedDeviations.isEmpty()) {
                long unresolvedCount = unresolvedDeviations.size();
                String numbersPreview = unresolvedDeviations.stream()
                        .map(d -> d.getDeviationNumber() + " [" + d.getStatus() + "]")
                        .limit(3)
                        .collect(Collectors.joining(", "));
                if (unresolvedCount > 3) {
                    numbersPreview += ", ...";
                }
                blockingReasons.add(unresolvedCount
                        + " unresolved deviation(s) are not CLOSED (" + numbersPreview + ")");
            }

            if (!blockingReasons.isEmpty()) {
                throw new InvalidQualityOperationException(
                        "Cannot release batch " + batch.getBatchNumber()
                                + " because " + String.join("; and ", blockingReasons));
            }
        }

        LocalDateTime reviewedAt = AppTime.nowDateTime();

        Map<String, Object> beforeMap = new LinkedHashMap<>();
        beforeMap.put("batchNumber", batch.getBatchNumber());
        beforeMap.put("batchStatus", batch.getStatus().name());
        beforeMap.put("qaStatus", previousStatus.name());

        batch.setQaStatus(targetStatus);
        Batch savedBatch = batchRepository.save(batch);

        BatchQaReview review = new BatchQaReview(
                savedBatch,
                previousStatus,
                targetStatus,
                reviewerName,
                rationale,
                reviewedAt,
                summary.total,
                summary.passed,
                summary.failed,
                summary.pending
        );
        BatchQaReview savedReview = batchQaReviewRepository.save(review);

        Map<String, Object> afterMap = new LinkedHashMap<>();
        afterMap.put("batchNumber", savedBatch.getBatchNumber());
        afterMap.put("batchStatus", savedBatch.getStatus().name());
        afterMap.put("qaStatus", targetStatus.name());
        afterMap.put("reviewId", savedReview.getId());
        afterMap.put("totalChecks", summary.total);
        afterMap.put("passedChecks", summary.passed);
        afterMap.put("failedChecks", summary.failed);
        afterMap.put("pendingChecks", summary.pending);

        auditTrailService.recordAudit(
                ENTITY_BATCH_QA_DISPOSITION,
                savedReview.getId() != null ? savedReview.getId() : savedBatch.getId(),
                savedBatch.getBatchNumber(),
                savedBatch.getId(),
                "QA_DISPOSITION_" + targetStatus.name(),
                reviewerName,
                reviewedAt,
                rationale,
                beforeMap,
                afterMap
        );

        List<BatchQaReview> reviews = safeList(batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(savedBatch.getId()));
        List<Deviation> deviations = safeList(deviationRepository.findByBatchIdWithDetailsOrderByReportedAtDesc(savedBatch.getId()));
        List<AuditLogResponseDto> auditLogs = safeList(auditTrailService.getAuditLogs(savedBatch.getId(), null, null));

        return buildDossierDto(savedBatch, checks, reviews, deviations, auditLogs);
    }

    // -------------------------------------------------------------------------
    // Internal Validation, Summary & Mapping Helpers
    // -------------------------------------------------------------------------

    private void validateCompletedBatchForQcModification(Batch batch, String action) {
        if (batch.getStatus() != BatchStatus.COMPLETED) {
            throw new InvalidQualityOperationException(
                    "Cannot " + action + " for batch " + batch.getBatchNumber()
                            + " because manufacturing batch status is " + batch.getStatus()
                            + ". Only COMPLETED batches can undergo QC inspection.");
        }
        BatchQualityStatus currentQaStatus = resolveEffectiveQaStatus(batch);
        if (currentQaStatus == BatchQualityStatus.RELEASED || currentQaStatus == BatchQualityStatus.REJECTED) {
            throw new InvalidQualityOperationException(
                    "Cannot " + action + " for batch " + batch.getBatchNumber()
                            + " because QA disposition is already finalized as " + currentQaStatus + ".");
        }
    }

    private String validateMeasuredValue(String rawValue, QualityCheckResult result) {
        String trimmed = rawValue != null ? rawValue.trim() : null;
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }

        if ((result == QualityCheckResult.PASS || result == QualityCheckResult.FAIL) && trimmed == null) {
            throw new InvalidQualityOperationException(
                    "Measured value is mandatory when QC result is " + result.name());
        }

        if (trimmed != null) {
            if (trimmed.length() > 150) {
                throw new InvalidQualityOperationException("Measured value must not exceed 150 characters");
            }
            String lower = trimmed.toLowerCase();
            if ("nan".equals(lower) || "infinity".equals(lower) || "-infinity".equals(lower) || "+infinity".equals(lower)) {
                throw new InvalidQualityOperationException("Measured value contains an invalid value: " + trimmed);
            }
        }
        return trimmed;
    }

    private String requireNonBlank(String value, String fieldLabel, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidQualityOperationException(fieldLabel + " is mandatory and must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new InvalidQualityOperationException(fieldLabel + " must not exceed " + maxLength + " characters");
        }
        return trimmed;
    }

    private String normalizeOptional(String value, String fieldLabel, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new InvalidQualityOperationException(fieldLabel + " must not exceed " + maxLength + " characters");
        }
        return trimmed;
    }

    private BatchQualityStatus resolveEffectiveQaStatus(Batch batch) {
        if (batch.getQaStatus() != null) {
            return batch.getQaStatus();
        }
        return batch.getStatus() == BatchStatus.COMPLETED ? BatchQualityStatus.PENDING_QA : null;
    }

    private int getQueuePriority(BatchQualityStatus qaStatus) {
        if (qaStatus == BatchQualityStatus.ON_HOLD) return 0;
        if (qaStatus == BatchQualityStatus.PENDING_QA) return 1;
        if (qaStatus == BatchQualityStatus.RELEASED) return 2;
        if (qaStatus == BatchQualityStatus.REJECTED) return 3;
        return 4;
    }

    private QcSummary summarizeChecks(List<QualityCheck> checks) {
        long total = checks.size();
        long passed = 0;
        long failed = 0;
        long pending = 0;
        long requiredCount = 0;
        long requiredFailed = 0;
        long requiredPending = 0;

        for (QualityCheck check : checks) {
            boolean isRequired = check.isRequired();
            if (isRequired) {
                requiredCount++;
            }
            if (check.getResult() == QualityCheckResult.PASS) {
                passed++;
            } else if (check.getResult() == QualityCheckResult.FAIL) {
                failed++;
                if (isRequired) requiredFailed++;
            } else {
                pending++;
                if (isRequired) requiredPending++;
            }
        }

        return new QcSummary(total, passed, failed, pending, requiredCount, requiredFailed, requiredPending);
    }

    private List<String> evaluateReleaseBlockers(Batch batch,
                                                 BatchQualityStatus qaStatus,
                                                 QcSummary summary,
                                                 long openDeviationsCount) {
        List<String> blockers = new ArrayList<>();
        if (batch.getStatus() != BatchStatus.COMPLETED) {
            blockers.add("Manufacturing batch status must be COMPLETED (current: " + batch.getStatus() + ")");
        }
        if (qaStatus == BatchQualityStatus.RELEASED) {
            blockers.add("Batch is already RELEASED");
        } else if (qaStatus == BatchQualityStatus.REJECTED) {
            blockers.add("Batch is finalized as REJECTED");
        }
        if (summary.total == 0) {
            blockers.add("At least one QC check must be recorded (at least one required QC check must exist and PASS)");
        } else {
            if (summary.requiredCount == 0) {
                blockers.add("At least one required QC check must exist and PASS");
            }
            long optionalFailed = Math.max(0L, summary.failed - summary.requiredFailed);
            if (summary.requiredFailed > 0) {
                blockers.add(summary.requiredFailed + " required QC check(s) have FAILED");
            }
            if (optionalFailed > 0) {
                blockers.add(optionalFailed + " optional QC check(s) have FAILED");
            }
            long optionalPending = Math.max(0L, summary.pending - summary.requiredPending);
            if (summary.requiredPending > 0 && optionalPending == 0) {
                blockers.add(summary.requiredPending + " QC check(s) are still PENDING");
            } else {
                if (summary.requiredPending > 0) {
                    blockers.add(summary.requiredPending + " required QC check(s) are still PENDING");
                }
                if (optionalPending > 0) {
                    blockers.add(optionalPending + " optional QC check(s) are still PENDING");
                }
            }
        }
        if (openDeviationsCount > 0) {
            blockers.add(openDeviationsCount + " unresolved deviation(s) must be CLOSED before QA release");
        }
        return blockers;
    }

    private Map<String, Object> buildQcSnapshotMap(QualityCheck qc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("testName", qc.getTestName());
        map.put("specification", qc.getSpecification());
        map.put("measuredValue", qc.getMeasuredValue());
        map.put("unit", qc.getUnit());
        map.put("result", qc.getResult() != null ? qc.getResult().name() : null);
        map.put("required", qc.isRequired());
        map.put("inspectorName", qc.getInspectorName());
        map.put("checkedAt", AppTime.formatIsoTimestamp(qc.getCheckedAt()));
        map.put("remarks", qc.getRemarks());
        return map;
    }

    private QualityQueueItemDto mapToQueueItemDto(Batch batch,
                                                List<QualityCheck> checks,
                                                List<BatchQaReview> reviews,
                                                List<Deviation> deviations) {
        ManufacturingOrder order = batch.getOrder();
        Product product = order != null ? order.getProduct() : null;
        Recipe recipe = order != null ? order.getRecipe() : null;
        Integer recipeVersion = order != null && order.getRecipeVersion() != null
                ? order.getRecipeVersion()
                : (recipe != null ? recipe.getVersion() : null);

        BatchQualityStatus effectiveQaStatus = resolveEffectiveQaStatus(batch);
        QcSummary summary = summarizeChecks(checks);
        BatchQaReview latestReview = reviews != null && !reviews.isEmpty() ? reviews.get(0) : null;
        long totalDeviationsCount = deviations != null ? deviations.size() : 0L;
        long openDeviationsCount = deviations != null
                ? deviations.stream().filter(d -> d.getStatus() != DeviationStatus.CLOSED).count()
                : 0L;

        List<String> releaseBlockers = evaluateReleaseBlockers(batch, effectiveQaStatus, summary, openDeviationsCount);
        boolean eligibleForRelease = releaseBlockers.isEmpty()
                && (effectiveQaStatus == BatchQualityStatus.PENDING_QA || effectiveQaStatus == BatchQualityStatus.ON_HOLD);
        long optionalChecksCount = Math.max(0L, summary.total - summary.requiredCount);
        long blockingChecksCount = summary.failed + summary.pending;

        return new QualityQueueItemDto(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getStatus(),
                effectiveQaStatus,
                order != null ? order.getId() : null,
                order != null ? order.getOrderNumber() : null,
                product != null ? product.getId() : null,
                product != null ? product.getProductCode() : null,
                product != null ? product.getProductName() : null,
                recipe != null ? recipe.getId() : null,
                recipe != null ? recipe.getRecipeCode() : null,
                recipeVersion,
                batch.getBatchSize(),
                batch.getBatchUnit(),
                batch.getActualYield(),
                batch.getManufactureDate(),
                batch.getExpiryDate(),
                batch.getStartTime(),
                batch.getEndTime(),
                summary.total,
                summary.passed,
                summary.failed,
                summary.pending,
                summary.requiredCount,
                optionalChecksCount,
                summary.requiredFailed + summary.requiredPending,
                blockingChecksCount,
                latestReview != null ? latestReview.getReviewerName() : null,
                latestReview != null ? latestReview.getReviewedAt() : null,
                totalDeviationsCount,
                openDeviationsCount,
                eligibleForRelease,
                releaseBlockers
        );
    }

    private BatchQualityDossierDto buildDossierDto(Batch batch,
                                                   List<QualityCheck> checks,
                                                   List<BatchQaReview> reviews,
                                                   List<Deviation> deviations,
                                                   List<AuditLogResponseDto> auditLogs) {
        ManufacturingOrder order = batch.getOrder();
        Product product = order != null ? order.getProduct() : null;
        Recipe recipe = order != null ? order.getRecipe() : null;
        Integer recipeVersion = order != null && order.getRecipeVersion() != null
                ? order.getRecipeVersion()
                : (recipe != null ? recipe.getVersion() : null);

        BatchQualityStatus effectiveQaStatus = resolveEffectiveQaStatus(batch);
        QcSummary summary = summarizeChecks(checks);

        List<Deviation> safeDeviations = safeList(deviations);
        long totalDeviationsCount = safeDeviations.size();
        long openDeviationsCount = safeDeviations.stream()
                .filter(d -> d.getStatus() != DeviationStatus.CLOSED)
                .count();

        List<String> blockers = evaluateReleaseBlockers(batch, effectiveQaStatus, summary, openDeviationsCount);
        boolean eligibleForRelease = blockers.isEmpty()
                && (effectiveQaStatus == BatchQualityStatus.PENDING_QA || effectiveQaStatus == BatchQualityStatus.ON_HOLD);

        List<QualityCheckResponseDto> checkDtos = checks.stream()
                .map(this::mapToQualityCheckDto)
                .toList();

        List<BatchQaReviewResponseDto> reviewDtos = reviews.stream()
                .map(this::mapToReviewDto)
                .toList();

        List<DeviationSummaryDto> deviationDtos =
                DeviationService.mapSummaryListWithCapas(safeDeviations, capaActionRepository);

        return new BatchQualityDossierDto(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getStatus(),
                effectiveQaStatus,
                order != null ? order.getId() : null,
                order != null ? order.getOrderNumber() : null,
                product != null ? product.getId() : null,
                product != null ? product.getProductCode() : null,
                product != null ? product.getProductName() : null,
                recipe != null ? recipe.getId() : null,
                recipe != null ? recipe.getRecipeCode() : null,
                recipeVersion,
                batch.getBatchSize(),
                batch.getBatchUnit(),
                batch.getActualYield(),
                batch.getManufactureDate(),
                batch.getExpiryDate(),
                batch.getStartTime(),
                batch.getEndTime(),
                summary.total,
                summary.passed,
                summary.failed,
                summary.pending,
                summary.requiredCount,
                eligibleForRelease,
                blockers,
                checkDtos,
                reviewDtos,
                auditLogs,
                totalDeviationsCount,
                openDeviationsCount,
                deviationDtos
        );
    }

    private QualityCheckResponseDto mapToQualityCheckDto(QualityCheck check) {
        return toQualityCheckDto(check);
    }

    public static QualityCheckResponseDto toQualityCheckDto(QualityCheck check) {
        Batch batch = check.getBatch();
        boolean locked = check.getResult() != QualityCheckResult.PENDING;
        return new QualityCheckResponseDto(
                check.getId(),
                batch != null ? batch.getId() : null,
                batch != null ? batch.getBatchNumber() : null,
                check.getTestName(),
                check.getSpecification(),
                check.getMeasuredValue(),
                check.getUnit(),
                check.getResult(),
                check.isRequired(),
                check.getInspectorName(),
                check.getCheckedAt(),
                check.getRemarks(),
                check.getCorrectedBy(),
                check.getCorrectedAt(),
                check.getCorrectionReason(),
                check.getCorrectionCount() != null ? check.getCorrectionCount() : 0,
                locked,
                check.getCreatedAt(),
                check.getUpdatedAt()
        );
    }

    private BatchQaReviewResponseDto mapToReviewDto(BatchQaReview review) {
        return toQaReviewDto(review);
    }

    public static BatchQaReviewResponseDto toQaReviewDto(BatchQaReview review) {
        Batch batch = review.getBatch();
        return new BatchQaReviewResponseDto(
                review.getId(),
                batch != null ? batch.getId() : null,
                batch != null ? batch.getBatchNumber() : null,
                review.getPreviousStatus(),
                review.getDecisionStatus(),
                review.getReviewerName(),
                review.getRationale(),
                review.getReviewedAt(),
                review.getTotalChecksCount() != null ? review.getTotalChecksCount() : 0L,
                review.getPassedChecksCount() != null ? review.getPassedChecksCount() : 0L,
                review.getFailedChecksCount() != null ? review.getFailedChecksCount() : 0L,
                review.getPendingChecksCount() != null ? review.getPendingChecksCount() : 0L
        );
    }

    private static <T> List<T> safeList(List<T> input) {
        return input != null ? input : List.of();
    }

    private record QcSummary(long total, long passed, long failed, long pending,
                             long requiredCount, long requiredFailed, long requiredPending) {
    }
}

