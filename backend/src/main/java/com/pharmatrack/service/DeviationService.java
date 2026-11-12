package com.pharmatrack.service;

import com.pharmatrack.dto.AuditLogResponseDto;
import com.pharmatrack.dto.BatchExecutionStepResponseDto;
import com.pharmatrack.dto.BatchLinkableRecordsDto;
import com.pharmatrack.dto.CancelCapaActionRequestDto;
import com.pharmatrack.dto.CapaActionResponseDto;
import com.pharmatrack.dto.CloseDeviationRequestDto;
import com.pharmatrack.dto.CompleteCapaActionRequestDto;
import com.pharmatrack.dto.CreateCapaActionRequestDto;
import com.pharmatrack.dto.CreateDeviationRequestDto;
import com.pharmatrack.dto.DeviationDetailDto;
import com.pharmatrack.dto.DeviationSummaryDto;
import com.pharmatrack.dto.QualityCheckResponseDto;
import com.pharmatrack.dto.RecordRootCauseRequestDto;
import com.pharmatrack.dto.ReopenCapaRequestDto;
import com.pharmatrack.dto.StartCapaActionRequestDto;
import com.pharmatrack.dto.StartCapaRequestDto;
import com.pharmatrack.dto.StartInvestigationRequestDto;
import com.pharmatrack.dto.SubmitVerificationRequestDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchExecutionStep;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.CapaAction;
import com.pharmatrack.entity.CapaStatus;
import com.pharmatrack.entity.CapaType;
import com.pharmatrack.entity.Deviation;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.QualityCheck;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.exception.InvalidDeviationOperationException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchExecutionStepRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.CapaActionRepository;
import com.pharmatrack.repository.DeviationRepository;
import com.pharmatrack.repository.QualityCheckRepository;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.util.AppTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core service managing Quality & Manufacturing Deviations and CAPA (Corrective and Preventive Actions):
 * - Sequential DEV-YYYY-XXXX and CAPA-YYYY-XXXX number generation
 * - Batch eligibility and linked QC check / eBR step / audit log validation
 * - Strict 6-stage lifecycle transitions (OPEN -> UNDER_INVESTIGATION -> ROOT_CAUSE_IDENTIFIED
 *   -> CAPA_IN_PROGRESS -> PENDING_VERIFICATION -> CLOSED)
 * - CAPA creation, execution, completion evidence, cancellation, and reopen workflows
 * - Mandatory closure gates and terminal CLOSED immutability
 * - Append-only audit log recording for all creations, transitions, and CAPA modifications
 *   attributed to the authenticated SecurityContext user
 */
@Service
@Transactional
public class DeviationService {

    public static final String ENTITY_DEVIATION = "DEVIATION";
    public static final String ENTITY_CAPA_ACTION = "CAPA_ACTION";
    public static final ZoneId APP_ZONE_ID = AppTime.APP_ZONE_ID;

    private final DeviationRepository deviationRepository;
    private final CapaActionRepository capaActionRepository;
    private final BatchRepository batchRepository;
    private final QualityCheckRepository qualityCheckRepository;
    private final BatchExecutionStepRepository executionStepRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditTrailService auditTrailService;
    private final AuthenticatedActorResolver authenticatedActorResolver;

    public DeviationService(DeviationRepository deviationRepository,
                            CapaActionRepository capaActionRepository,
                            BatchRepository batchRepository,
                            QualityCheckRepository qualityCheckRepository,
                            BatchExecutionStepRepository executionStepRepository,
                            AuditLogRepository auditLogRepository,
                            AuditTrailService auditTrailService) {
        this(deviationRepository, capaActionRepository, batchRepository, qualityCheckRepository,
                executionStepRepository, auditLogRepository, auditTrailService, new AuthenticatedActorResolver());
    }

    @Autowired
    public DeviationService(DeviationRepository deviationRepository,
                            CapaActionRepository capaActionRepository,
                            BatchRepository batchRepository,
                            QualityCheckRepository qualityCheckRepository,
                            BatchExecutionStepRepository executionStepRepository,
                            AuditLogRepository auditLogRepository,
                            AuditTrailService auditTrailService,
                            AuthenticatedActorResolver authenticatedActorResolver) {
        this.deviationRepository = deviationRepository;
        this.capaActionRepository = capaActionRepository;
        this.batchRepository = batchRepository;
        this.qualityCheckRepository = qualityCheckRepository;
        this.executionStepRepository = executionStepRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditTrailService = auditTrailService;
        this.authenticatedActorResolver = authenticatedActorResolver != null
                ? authenticatedActorResolver
                : new AuthenticatedActorResolver();
    }

    // -------------------------------------------------------------------------
    // A. Deviation Queries
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DeviationSummaryDto> getDeviations(DeviationStatus status,
                                                   DeviationSeverity severity,
                                                   DeviationPriority priority,
                                                   DeviationCategory category,
                                                   Long batchId) {
        List<Deviation> deviations = batchId != null
                ? safeList(deviationRepository.findByBatchIdWithDetailsOrderByReportedAtDesc(batchId))
                : safeList(deviationRepository.findAllWithDetailsOrderByReportedAtDesc());

        List<Deviation> filtered = deviations.stream()
                .filter(d -> status == null || d.getStatus() == status)
                .filter(d -> severity == null || d.getSeverity() == severity)
                .filter(d -> priority == null || d.getPriority() == priority)
                .filter(d -> category == null || d.getCategory() == category)
                .toList();

        return mapSummaryListWithCapas(filtered);
    }

    @Transactional(readOnly = true)
    public List<DeviationSummaryDto> getBatchDeviations(Long batchId) {
        if (batchId == null) {
            throw new ResourceNotFoundException("Batch not found with id: null");
        }
        if (!batchRepository.existsById(batchId)) {
            throw new ResourceNotFoundException("Batch not found with id: " + batchId);
        }
        List<Deviation> deviations = safeList(deviationRepository.findByBatchIdWithDetailsOrderByReportedAtDesc(batchId));
        return mapSummaryListWithCapas(deviations);
    }

    @Transactional(readOnly = true)
    public List<DeviationDetailDto> getDeviationDetailsForBatch(Long batchId) {
        if (batchId == null) {
            return List.of();
        }
        List<Deviation> deviations = safeList(deviationRepository.findByBatchIdWithDetailsOrderByReportedAtDesc(batchId));
        return deviations.stream()
                .map(this::buildDetailDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviationDetailDto getDeviationById(Long deviationId) {
        if (deviationId == null) {
            throw new ResourceNotFoundException("Deviation not found with id: null");
        }
        Deviation deviation = deviationRepository.findByIdWithDetails(deviationId)
                .orElseThrow(() -> new ResourceNotFoundException("Deviation not found with id: " + deviationId));
        return buildDetailDto(deviation);
    }

    @Transactional(readOnly = true)
    public BatchLinkableRecordsDto getLinkableRecordsForBatch(Long batchId) {
        if (batchId == null) {
            throw new ResourceNotFoundException("Batch not found with id: null");
        }
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batchId));

        if (batch.getStatus() != BatchStatus.IN_PROGRESS && batch.getStatus() != BatchStatus.COMPLETED) {
            throw new InvalidDeviationOperationException(
                    "Deviations can only be reported against IN_PROGRESS or COMPLETED batches. Batch "
                            + batch.getBatchNumber() + " is currently " + batch.getStatus() + ".");
        }

        ManufacturingOrder order = batch.getOrder();
        Product product = order != null ? order.getProduct() : null;

        List<QualityCheckResponseDto> checks = safeList(
                qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(batch.getId())).stream()
                .map(QualityService::toQualityCheckDto)
                .toList();

        List<BatchExecutionStepResponseDto> steps = safeList(
                executionStepRepository.findByBatchIdOrderByStepNumberAsc(batch.getId())).stream()
                .map(step -> new BatchExecutionStepResponseDto(
                        step.getId(),
                        step.getStepNumber(),
                        step.getOperationName(),
                        step.getInstructions(),
                        step.getStatus(),
                        step.getActualValue(),
                        step.getObservations(),
                        step.getExecutedBy(),
                        step.getStartedAt(),
                        step.getCompletedAt()
                ))
                .toList();

        List<AuditLogResponseDto> auditLogs = safeList(
                auditTrailService.getAuditLogs(batch.getId(), null, null));

        return new BatchLinkableRecordsDto(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getStatus(),
                batch.getQaStatus(),
                order != null ? order.getOrderNumber() : null,
                product != null ? product.getProductCode() : null,
                product != null ? product.getProductName() : null,
                checks,
                steps,
                auditLogs
        );
    }

    // -------------------------------------------------------------------------
    // B. Deviation Creation & Lifecycle Transitions
    // -------------------------------------------------------------------------

    public DeviationDetailDto createDeviation(CreateDeviationRequestDto request) {
        String reportedBy = authenticatedActorResolver.requireCurrentActor();
        if (request == null) {
            throw new InvalidDeviationOperationException("Deviation request payload cannot be null");
        }
        if (request.getBatchId() == null) {
            throw new InvalidDeviationOperationException("Affected batch ID is mandatory");
        }

        Batch batch = batchRepository.findByIdWithLock(request.getBatchId())
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + request.getBatchId()));

        if (batch.getStatus() != BatchStatus.IN_PROGRESS && batch.getStatus() != BatchStatus.COMPLETED) {
            throw new InvalidDeviationOperationException(
                    "Deviations can only be reported against IN_PROGRESS or COMPLETED batches. Batch "
                            + batch.getBatchNumber() + " is currently " + batch.getStatus() + ".");
        }

        String title = requireNonBlank(request.getTitle(), "Deviation title", 200);
        DeviationCategory category = requireNonNull(request.getCategory(), "Deviation category");
        DeviationSeverity severity = requireNonNull(request.getSeverity(), "Deviation severity");
        DeviationPriority priority = requireNonNull(request.getPriority(), "Deviation priority");
        String description = requireNonBlank(request.getDescription(), "Deviation description", 3000);
        String immediateContainment = normalizeOptional(request.getImmediateContainment(), "Immediate containment action", 2000);

        LocalDateTime now = LocalDateTime.now(APP_ZONE_ID);
        LocalDateTime occurredAt = request.getOccurredAt() != null ? request.getOccurredAt() : now;
        if (occurredAt.isAfter(now.plusMinutes(5))) {
            throw new InvalidDeviationOperationException("Deviation occurrence timestamp cannot be in the future");
        }

        QualityCheck linkedCheck = resolveAndValidateQualityCheck(request.getQualityCheckId(), batch);
        BatchExecutionStep linkedStep = resolveAndValidateExecutionStep(request.getExecutionStepId(), batch);
        Long sourceAuditLogId = resolveAndValidateAuditLogId(request.getSourceAuditLogId(), batch);

        String tempNumber = "TEMP-" + UUID.randomUUID();
        Deviation entity = new Deviation(
                tempNumber,
                batch,
                linkedCheck,
                linkedStep,
                sourceAuditLogId,
                title,
                category,
                severity,
                priority,
                DeviationStatus.OPEN,
                description,
                immediateContainment,
                reportedBy,
                occurredAt,
                now
        );

        Deviation persisted = deviationRepository.saveAndFlush(entity);
        Long generatedId = persisted.getId() != null ? persisted.getId() : 0L;
        int currentYear = LocalDate.now(APP_ZONE_ID).getYear();
        persisted.setDeviationNumber(String.format("DEV-%d-%04d", currentYear, generatedId));
        Deviation finalSaved = deviationRepository.saveAndFlush(persisted);

        auditTrailService.recordAudit(
                ENTITY_DEVIATION,
                generatedId,
                finalSaved.getDeviationNumber(),
                batch.getId(),
                "DEVIATION_REPORTED",
                reportedBy,
                now,
                "Reported deviation: " + title,
                null,
                buildDeviationSnapshot(finalSaved)
        );

        Deviation reloaded = deviationRepository.findByIdWithDetails(generatedId).orElse(finalSaved);
        return buildDetailDto(reloaded);
    }

    public DeviationDetailDto startInvestigation(Long deviationId, StartInvestigationRequestDto request) {
        String investigatedBy = authenticatedActorResolver.requireCurrentActor();
        if (request == null) {
            throw new InvalidDeviationOperationException("Investigation payload cannot be null");
        }
        Deviation deviation = lockDeviationOrThrow(deviationId);
        ensureNotClosed(deviation);
        requireExpectedStatus(deviation, DeviationStatus.OPEN, "start investigation");

        String investigationSummary = requireNonBlank(request.getInvestigationSummary(), "Investigation summary", 3000);

        Map<String, Object> beforeMap = buildDeviationSnapshot(deviation);
        LocalDateTime now = LocalDateTime.now(APP_ZONE_ID);

        deviation.setStatus(DeviationStatus.UNDER_INVESTIGATION);
        deviation.setInvestigatedBy(investigatedBy);
        deviation.setInvestigationSummary(investigationSummary);
        deviation.setInvestigationStartedAt(now);

        Deviation saved = deviationRepository.saveAndFlush(deviation);

        auditTrailService.recordAudit(
                ENTITY_DEVIATION,
                saved.getId(),
                saved.getDeviationNumber(),
                getBatchIdSafely(saved),
                "DEVIATION_INVESTIGATION_STARTED",
                investigatedBy,
                now,
                truncateAuditReason(investigationSummary, 2000),
                beforeMap,
                buildDeviationSnapshot(saved)
        );

        Deviation reloaded = deviationRepository.findByIdWithDetails(saved.getId()).orElse(saved);
        return buildDetailDto(reloaded);
    }

    public DeviationDetailDto recordRootCause(Long deviationId, RecordRootCauseRequestDto request) {
        String identifiedBy = authenticatedActorResolver.requireCurrentActor();
        if (request == null) {
            throw new InvalidDeviationOperationException("Root cause payload cannot be null");
        }
        Deviation deviation = lockDeviationOrThrow(deviationId);
        ensureNotClosed(deviation);
        requireExpectedStatus(deviation, DeviationStatus.UNDER_INVESTIGATION, "record root cause");

        String rootCauseCategory = requireNonBlank(request.getRootCauseCategory(), "Root cause category", 100);
        String rootCauseAnalysis = requireNonBlank(request.getRootCauseAnalysis(), "Root cause analysis", 3000);
        String impactAssessment = requireNonBlank(request.getImpactAssessment(), "Impact assessment", 2000);
        String updatedInvestigationSummary = normalizeOptional(request.getInvestigationSummary(), "Investigation summary", 3000);

        String effectiveInvestigationSummary = updatedInvestigationSummary != null
                ? updatedInvestigationSummary
                : deviation.getInvestigationSummary();
        if (isBlank(effectiveInvestigationSummary)) {
            throw new InvalidDeviationOperationException("Investigation summary is mandatory before recording root cause");
        }

        Map<String, Object> beforeMap = buildDeviationSnapshot(deviation);
        LocalDateTime now = LocalDateTime.now(APP_ZONE_ID);

        deviation.setStatus(DeviationStatus.ROOT_CAUSE_IDENTIFIED);
        deviation.setInvestigationSummary(effectiveInvestigationSummary);
        deviation.setRootCauseIdentifiedBy(identifiedBy);
        deviation.setRootCauseCategory(rootCauseCategory);
        deviation.setRootCauseAnalysis(rootCauseAnalysis);
        deviation.setImpactAssessment(impactAssessment);
        deviation.setRootCauseIdentifiedAt(now);

        Deviation saved = deviationRepository.saveAndFlush(deviation);

        auditTrailService.recordAudit(
                ENTITY_DEVIATION,
                saved.getId(),
                saved.getDeviationNumber(),
                getBatchIdSafely(saved),
                "DEVIATION_ROOT_CAUSE_RECORDED",
                identifiedBy,
                now,
                "Root cause identified (" + rootCauseCategory + ")",
                beforeMap,
                buildDeviationSnapshot(saved)
        );

        Deviation reloaded = deviationRepository.findByIdWithDetails(saved.getId()).orElse(saved);
        return buildDetailDto(reloaded);
    }

    public DeviationDetailDto startCapa(Long deviationId, StartCapaRequestDto request) {
        String actorName = authenticatedActorResolver.requireCurrentActor();
        if (request == null) {
            throw new InvalidDeviationOperationException("Start CAPA payload cannot be null");
        }
        Deviation deviation = lockDeviationOrThrow(deviationId);
        ensureNotClosed(deviation);
        requireExpectedStatus(deviation, DeviationStatus.ROOT_CAUSE_IDENTIFIED, "start CAPA execution");

        String capaPlanSummary = normalizeOptional(request.getCapaPlanSummary(), "CAPA plan summary", 2000);

        List<CapaAction> capas = safeList(capaActionRepository.findByDeviationIdOrderByIdAsc(deviation.getId()));
        long activeCapaCount = capas.stream()
                .filter(c -> c.getStatus() != CapaStatus.CANCELLED)
                .count();
        if (activeCapaCount == 0) {
            throw new InvalidDeviationOperationException(
                    "Cannot start CAPA execution for deviation " + deviation.getDeviationNumber()
                            + " because at least one active CAPA action must exist first.");
        }

        Map<String, Object> beforeMap = buildDeviationSnapshot(deviation);
        LocalDateTime now = LocalDateTime.now(APP_ZONE_ID);

        deviation.setStatus(DeviationStatus.CAPA_IN_PROGRESS);
        if (capaPlanSummary != null) {
            deviation.setCapaPlanSummary(capaPlanSummary);
        }

        Deviation saved = deviationRepository.saveAndFlush(deviation);

        auditTrailService.recordAudit(
                ENTITY_DEVIATION,
                saved.getId(),
                saved.getDeviationNumber(),
                getBatchIdSafely(saved),
                "DEVIATION_CAPA_STARTED",
                actorName,
                now,
                capaPlanSummary != null ? capaPlanSummary : "Entered CAPA_IN_PROGRESS stage",
                beforeMap,
                buildDeviationSnapshot(saved)
        );

        Deviation reloaded = deviationRepository.findByIdWithDetails(saved.getId()).orElse(saved);
        return buildDetailDto(reloaded);
    }

    public DeviationDetailDto submitForVerification(Long deviationId, SubmitVerificationRequestDto request) {
        String submittedBy = authenticatedActorResolver.requireCurrentActor();
        if (request == null) {
            throw new InvalidDeviationOperationException("Verification submission payload cannot be null");
        }
        Deviation deviation = lockDeviationOrThrow(deviationId);
        ensureNotClosed(deviation);
        requireExpectedStatus(deviation, DeviationStatus.CAPA_IN_PROGRESS, "submit for verification");

        String submissionNotes = normalizeOptional(request.getSubmissionNotes(), "Submission notes", 2000);

        List<CapaAction> capas = safeList(capaActionRepository.findByDeviationIdOrderByIdAsc(deviation.getId()));
        List<String> capaErrors = validateCapaCompletionForVerification(capas);
        if (!capaErrors.isEmpty()) {
            throw new InvalidDeviationOperationException(String.join("; ", capaErrors));
        }

        Map<String, Object> beforeMap = buildDeviationSnapshot(deviation);
        LocalDateTime now = LocalDateTime.now(APP_ZONE_ID);

        deviation.setStatus(DeviationStatus.PENDING_VERIFICATION);
        Deviation saved = deviationRepository.saveAndFlush(deviation);

        auditTrailService.recordAudit(
                ENTITY_DEVIATION,
                saved.getId(),
                saved.getDeviationNumber(),
                getBatchIdSafely(saved),
                "DEVIATION_SUBMITTED_FOR_VERIFICATION",
                submittedBy,
                now,
                submissionNotes != null ? submissionNotes : "All active CAPAs completed and submitted for QA verification",
                beforeMap,
                buildDeviationSnapshot(saved)
        );

        Deviation reloaded = deviationRepository.findByIdWithDetails(saved.getId()).orElse(saved);
        return buildDetailDto(reloaded);
    }

    public DeviationDetailDto reopenCapa(Long deviationId, ReopenCapaRequestDto request) {
        String actorName = authenticatedActorResolver.requireCurrentActor();
        if (request == null) {
            throw new InvalidDeviationOperationException("Reopen CAPA payload cannot be null");
        }
        Deviation deviation = lockDeviationOrThrow(deviationId);
        ensureNotClosed(deviation);
        requireExpectedStatus(deviation, DeviationStatus.PENDING_VERIFICATION, "reopen CAPA");

        String reason = requireNonBlank(request.getReason(), "Reopen reason", 2000);

        Map<String, Object> beforeMap = buildDeviationSnapshot(deviation);
        LocalDateTime now = LocalDateTime.now(APP_ZONE_ID);

        deviation.setStatus(DeviationStatus.CAPA_IN_PROGRESS);
        Deviation saved = deviationRepository.saveAndFlush(deviation);

        auditTrailService.recordAudit(
                ENTITY_DEVIATION,
                saved.getId(),
                saved.getDeviationNumber(),
                getBatchIdSafely(saved),
                "DEVIATION_CAPA_REOPENED",
                actorName,
                now,
                reason,
                beforeMap,
                buildDeviationSnapshot(saved)
        );

        Deviation reloaded = deviationRepository.findByIdWithDetails(saved.getId()).orElse(saved);
        return buildDetailDto(reloaded);
    }

    public DeviationDetailDto closeDeviation(Long deviationId, CloseDeviationRequestDto request) {
        String actorName = authenticatedActorResolver.requireCurrentActor();
        if (request == null) {
            throw new InvalidDeviationOperationException("Deviation closure payload cannot be null");
        }
        Deviation deviation = lockDeviationOrThrow(deviationId);
        ensureNotClosed(deviation);
        requireExpectedStatus(deviation, DeviationStatus.PENDING_VERIFICATION, "close deviation");

        String verificationNotes = requireNonBlank(request.getVerificationNotes(), "Verification notes", 2000);

        List<CapaAction> capas = safeList(capaActionRepository.findByDeviationIdOrderByIdAsc(deviation.getId()));
        List<String> blockers = evaluateClosureBlockers(deviation, capas);
        if (!blockers.isEmpty()) {
            throw new InvalidDeviationOperationException(
                    "Cannot close deviation " + deviation.getDeviationNumber() + ": " + String.join("; ", blockers));
        }

        Map<String, Object> beforeMap = buildDeviationSnapshot(deviation);
        LocalDateTime now = LocalDateTime.now(APP_ZONE_ID);

        deviation.setStatus(DeviationStatus.CLOSED);
        deviation.setVerifiedBy(actorName);
        deviation.setVerificationNotes(verificationNotes);
        deviation.setClosedBy(actorName);
        deviation.setClosedAt(now);

        Deviation saved = deviationRepository.saveAndFlush(deviation);

        auditTrailService.recordAudit(
                ENTITY_DEVIATION,
                saved.getId(),
                saved.getDeviationNumber(),
                getBatchIdSafely(saved),
                "DEVIATION_CLOSED",
                actorName,
                now,
                verificationNotes,
                beforeMap,
                buildDeviationSnapshot(saved)
        );

        Deviation reloaded = deviationRepository.findByIdWithDetails(saved.getId()).orElse(saved);
        return buildDetailDto(reloaded);
    }

    // -------------------------------------------------------------------------
    // C. CAPA Action Lifecycle Management
    // -------------------------------------------------------------------------

    public CapaActionResponseDto createCapaAction(Long deviationId, CreateCapaActionRequestDto request) {
        String createdBy = authenticatedActorResolver.requireCurrentActor();
        if (request == null) {
            throw new InvalidDeviationOperationException("CAPA creation payload cannot be null");
        }
        Deviation deviation = lockDeviationOrThrow(deviationId);
        ensureCapaCreationOrCancellationAllowed(deviation, "create CAPA action");

        CapaType capaType = requireNonNull(request.getCapaType(), "CAPA type");
        String title = requireNonBlank(request.getTitle(), "CAPA title", 200);
        String actionPlan = requireNonBlank(request.getActionPlan(), "CAPA action plan", 2000);
        String ownerName = requireNonBlank(request.getOwnerName(), "Action owner name", 100);

        LocalDate today = LocalDate.now(APP_ZONE_ID);
        LocalDate dueDate = request.getDueDate();
        if (dueDate == null) {
            throw new InvalidDeviationOperationException("CAPA due date is mandatory");
        }
        if (dueDate.isBefore(today)) {
            throw new InvalidDeviationOperationException(
                    "CAPA due date (" + dueDate + ") cannot be earlier than today (" + today + ")");
        }

        String tempCapaNumber = "TEMP-" + UUID.randomUUID();
        CapaAction entity = new CapaAction(
                tempCapaNumber,
                deviation,
                capaType,
                title,
                actionPlan,
                ownerName,
                dueDate,
                CapaStatus.OPEN,
                createdBy
        );

        CapaAction persisted = capaActionRepository.saveAndFlush(entity);
        Long generatedId = persisted.getId() != null ? persisted.getId() : 0L;
        int currentYear = LocalDate.now(APP_ZONE_ID).getYear();
        persisted.setCapaNumber(String.format("CAPA-%d-%04d", currentYear, generatedId));
        CapaAction finalSaved = capaActionRepository.saveAndFlush(persisted);

        LocalDateTime now = LocalDateTime.now(APP_ZONE_ID);
        auditTrailService.recordAudit(
                ENTITY_CAPA_ACTION,
                generatedId,
                finalSaved.getCapaNumber(),
                getBatchIdSafely(deviation),
                "CAPA_CREATED",
                createdBy,
                now,
                "Added " + capaType + " CAPA under " + deviation.getDeviationNumber() + ": " + title,
                null,
                buildCapaSnapshot(finalSaved, deviation)
        );

        return mapToCapaDto(finalSaved, today);
    }

    public CapaActionResponseDto startCapaAction(Long capaId, StartCapaActionRequestDto request) {
        String actorName = authenticatedActorResolver.requireCurrentActor();
        if (request == null) {
            throw new InvalidDeviationOperationException("Start CAPA action payload cannot be null");
        }
        if (capaId == null) {
            throw new ResourceNotFoundException("CAPA action not found with id: null");
        }

        CapaAction lookup = capaActionRepository.findByIdWithDeviationAndBatch(capaId)
                .orElseThrow(() -> new ResourceNotFoundException("CAPA action not found with id: " + capaId));
        Deviation deviation = lockDeviationOrThrow(lookup.getDeviation().getId());
        ensureCapaExecutionAllowed(deviation, "start CAPA action");

        CapaAction capa = lockCapaOrThrow(capaId);
        if (capa.getStatus() != CapaStatus.OPEN) {
            throw new InvalidDeviationOperationException(
                    "Cannot start CAPA action " + capa.getCapaNumber()
                            + " because its status is " + capa.getStatus() + " (expected OPEN).");
        }

        String notes = normalizeOptional(request.getNotes(), "Notes", 1000);

        Map<String, Object> beforeMap = buildCapaSnapshot(capa, deviation);
        LocalDateTime now = LocalDateTime.now(APP_ZONE_ID);

        capa.setStatus(CapaStatus.IN_PROGRESS);
        CapaAction saved = capaActionRepository.saveAndFlush(capa);

        auditTrailService.recordAudit(
                ENTITY_CAPA_ACTION,
                saved.getId(),
                saved.getCapaNumber(),
                getBatchIdSafely(deviation),
                "CAPA_STARTED",
                actorName,
                now,
                notes != null ? notes : "Started CAPA action " + saved.getCapaNumber(),
                beforeMap,
                buildCapaSnapshot(saved, deviation)
        );

        return mapToCapaDto(saved, LocalDate.now(APP_ZONE_ID));
    }

    public CapaActionResponseDto completeCapaAction(Long capaId, CompleteCapaActionRequestDto request) {
        String completedBy = authenticatedActorResolver.requireCurrentActor();
        if (request == null) {
            throw new InvalidDeviationOperationException("CAPA completion payload cannot be null");
        }
        if (capaId == null) {
            throw new ResourceNotFoundException("CAPA action not found with id: null");
        }

        CapaAction lookup = capaActionRepository.findByIdWithDeviationAndBatch(capaId)
                .orElseThrow(() -> new ResourceNotFoundException("CAPA action not found with id: " + capaId));
        Deviation deviation = lockDeviationOrThrow(lookup.getDeviation().getId());
        ensureCapaExecutionAllowed(deviation, "complete CAPA action");

        CapaAction capa = lockCapaOrThrow(capaId);
        if (capa.getStatus() != CapaStatus.OPEN && capa.getStatus() != CapaStatus.IN_PROGRESS) {
            throw new InvalidDeviationOperationException(
                    "Cannot complete CAPA action " + capa.getCapaNumber()
                            + " because its status is " + capa.getStatus() + ".");
        }

        String completionEvidence = requireNonBlank(request.getCompletionEvidence(), "Completion evidence", 2000);

        Map<String, Object> beforeMap = buildCapaSnapshot(capa, deviation);
        LocalDateTime now = LocalDateTime.now(APP_ZONE_ID);

        capa.setStatus(CapaStatus.COMPLETED);
        capa.setCompletedBy(completedBy);
        capa.setCompletionEvidence(completionEvidence);
        capa.setCompletedAt(now);

        CapaAction saved = capaActionRepository.saveAndFlush(capa);

        auditTrailService.recordAudit(
                ENTITY_CAPA_ACTION,
                saved.getId(),
                saved.getCapaNumber(),
                getBatchIdSafely(deviation),
                "CAPA_COMPLETED",
                completedBy,
                now,
                completionEvidence,
                beforeMap,
                buildCapaSnapshot(saved, deviation)
        );

        return mapToCapaDto(saved, LocalDate.now(APP_ZONE_ID));
    }

    public CapaActionResponseDto cancelCapaAction(Long capaId, CancelCapaActionRequestDto request) {
        String actorName = authenticatedActorResolver.requireCurrentActor();
        if (request == null) {
            throw new InvalidDeviationOperationException("CAPA cancellation payload cannot be null");
        }
        if (capaId == null) {
            throw new ResourceNotFoundException("CAPA action not found with id: null");
        }

        CapaAction lookup = capaActionRepository.findByIdWithDeviationAndBatch(capaId)
                .orElseThrow(() -> new ResourceNotFoundException("CAPA action not found with id: " + capaId));
        Deviation deviation = lockDeviationOrThrow(lookup.getDeviation().getId());
        ensureCapaCreationOrCancellationAllowed(deviation, "cancel CAPA action");

        CapaAction capa = lockCapaOrThrow(capaId);
        if (capa.getStatus() != CapaStatus.OPEN && capa.getStatus() != CapaStatus.IN_PROGRESS) {
            throw new InvalidDeviationOperationException(
                    "Cannot cancel CAPA action " + capa.getCapaNumber()
                            + " because its status is already " + capa.getStatus() + ".");
        }

        if (deviation.getStatus() == DeviationStatus.CAPA_IN_PROGRESS) {
            List<CapaAction> deviationCapas = safeList(capaActionRepository.findByDeviationIdOrderByIdAsc(deviation.getId()));
            long activeCount = deviationCapas.stream()
                    .filter(c -> c.getStatus() != CapaStatus.CANCELLED)
                    .count();
            if (activeCount <= 1) {
                throw new InvalidDeviationOperationException(
                        "Cannot cancel the last active CAPA action (" + capa.getCapaNumber()
                                + ") while deviation " + deviation.getDeviationNumber()
                                + " is in CAPA_IN_PROGRESS status. Add a replacement CAPA action first.");
            }
        }

        String cancellationReason = requireNonBlank(request.getCancellationReason(), "Cancellation reason", 1000);

        Map<String, Object> beforeMap = buildCapaSnapshot(capa, deviation);
        LocalDateTime now = LocalDateTime.now(APP_ZONE_ID);

        capa.setStatus(CapaStatus.CANCELLED);
        capa.setCancellationReason(cancellationReason);

        CapaAction saved = capaActionRepository.saveAndFlush(capa);

        auditTrailService.recordAudit(
                ENTITY_CAPA_ACTION,
                saved.getId(),
                saved.getCapaNumber(),
                getBatchIdSafely(deviation),
                "CAPA_CANCELLED",
                actorName,
                now,
                cancellationReason,
                beforeMap,
                buildCapaSnapshot(saved, deviation)
        );

        return mapToCapaDto(saved, LocalDate.now(APP_ZONE_ID));
    }

    // -------------------------------------------------------------------------
    // Internal Validation, Gate Checks & Lock Helpers
    // -------------------------------------------------------------------------

    private Deviation lockDeviationOrThrow(Long deviationId) {
        if (deviationId == null) {
            throw new ResourceNotFoundException("Deviation not found with id: null");
        }
        return deviationRepository.findByIdWithLock(deviationId)
                .orElseThrow(() -> new ResourceNotFoundException("Deviation not found with id: " + deviationId));
    }

    private CapaAction lockCapaOrThrow(Long capaId) {
        return capaActionRepository.findByIdWithLock(capaId)
                .orElseThrow(() -> new ResourceNotFoundException("CAPA action not found with id: " + capaId));
    }

    private void ensureNotClosed(Deviation deviation) {
        if (deviation.getStatus() == DeviationStatus.CLOSED) {
            throw new InvalidDeviationOperationException(
                    "Deviation " + deviation.getDeviationNumber()
                            + " is CLOSED and cannot be modified.");
        }
    }

    private void ensureCapaCreationOrCancellationAllowed(Deviation deviation, String actionLabel) {
        ensureNotClosed(deviation);
        DeviationStatus status = deviation.getStatus();
        if (status == DeviationStatus.PENDING_VERIFICATION) {
            throw new InvalidDeviationOperationException(
                    "Cannot " + actionLabel + " while deviation " + deviation.getDeviationNumber()
                            + " is in PENDING_VERIFICATION status. Reopen CAPA to make changes.");
        }
        if (status != DeviationStatus.ROOT_CAUSE_IDENTIFIED && status != DeviationStatus.CAPA_IN_PROGRESS) {
            throw new InvalidDeviationOperationException(
                    "Cannot " + actionLabel + " while deviation " + deviation.getDeviationNumber()
                            + " is in " + status + " status (expected ROOT_CAUSE_IDENTIFIED or CAPA_IN_PROGRESS).");
        }
    }

    private void ensureCapaExecutionAllowed(Deviation deviation, String actionLabel) {
        ensureNotClosed(deviation);
        DeviationStatus status = deviation.getStatus();
        if (status == DeviationStatus.PENDING_VERIFICATION) {
            throw new InvalidDeviationOperationException(
                    "Cannot " + actionLabel + " while deviation " + deviation.getDeviationNumber()
                            + " is in PENDING_VERIFICATION status. Reopen CAPA to make changes.");
        }
        if (status != DeviationStatus.CAPA_IN_PROGRESS) {
            throw new InvalidDeviationOperationException(
                    "Cannot " + actionLabel + " while deviation " + deviation.getDeviationNumber()
                            + " is in " + status + " status (expected CAPA_IN_PROGRESS).");
        }
    }

    private void requireExpectedStatus(Deviation deviation, DeviationStatus expectedStatus, String actionLabel) {
        if (deviation.getStatus() != expectedStatus) {
            throw new InvalidDeviationOperationException(
                    "Cannot " + actionLabel + " because deviation " + deviation.getDeviationNumber()
                            + " is currently in " + deviation.getStatus()
                            + " status (expected " + expectedStatus + ").");
        }
    }

    private QualityCheck resolveAndValidateQualityCheck(Long qualityCheckId, Batch batch) {
        if (qualityCheckId == null) {
            return null;
        }
        QualityCheck check = qualityCheckRepository.findByIdWithBatch(qualityCheckId)
                .orElseThrow(() -> new ResourceNotFoundException("Quality check not found with id: " + qualityCheckId));
        if (check.getBatch() == null || !Objects.equals(check.getBatch().getId(), batch.getId())) {
            throw new InvalidDeviationOperationException(
                    "Quality check #" + qualityCheckId + " does not belong to batch " + batch.getBatchNumber() + ".");
        }
        return check;
    }

    private BatchExecutionStep resolveAndValidateExecutionStep(Long executionStepId, Batch batch) {
        if (executionStepId == null) {
            return null;
        }
        BatchExecutionStep step = executionStepRepository.findById(executionStepId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution step not found with id: " + executionStepId));
        if (step.getBatch() == null || !Objects.equals(step.getBatch().getId(), batch.getId())) {
            throw new InvalidDeviationOperationException(
                    "Execution step #" + executionStepId + " does not belong to batch " + batch.getBatchNumber() + ".");
        }
        return step;
    }

    private Long resolveAndValidateAuditLogId(Long sourceAuditLogId, Batch batch) {
        if (sourceAuditLogId == null) {
            return null;
        }
        AuditLog auditLog = auditLogRepository.findById(sourceAuditLogId)
                .orElseThrow(() -> new ResourceNotFoundException("Audit log entry not found with id: " + sourceAuditLogId));
        if (!Objects.equals(auditLog.getBatchId(), batch.getId())) {
            throw new InvalidDeviationOperationException(
                    "Audit log #" + sourceAuditLogId + " does not belong to batch " + batch.getBatchNumber() + ".");
        }
        return auditLog.getId();
    }

    private List<String> validateCapaCompletionForVerification(List<CapaAction> capas) {
        List<String> errors = new ArrayList<>();
        List<CapaAction> activeCapas = capas.stream()
                .filter(c -> c.getStatus() != CapaStatus.CANCELLED)
                .toList();

        if (activeCapas.isEmpty()) {
            errors.add("At least one non-cancelled CAPA action must be defined and completed before verification");
            return errors;
        }

        long unfinishedCount = activeCapas.stream()
                .filter(c -> c.getStatus() == CapaStatus.OPEN || c.getStatus() == CapaStatus.IN_PROGRESS)
                .count();
        if (unfinishedCount > 0) {
            errors.add(unfinishedCount + " active CAPA action(s) are still OPEN or IN_PROGRESS");
        }

        long completedCount = activeCapas.stream()
                .filter(c -> c.getStatus() == CapaStatus.COMPLETED)
                .count();
        if (completedCount == 0) {
            errors.add("At least one CAPA action must reach COMPLETED status");
        }

        long missingEvidenceCount = activeCapas.stream()
                .filter(c -> c.getStatus() == CapaStatus.COMPLETED)
                .filter(c -> isBlank(c.getCompletionEvidence())
                        || isBlank(c.getCompletedBy())
                        || c.getCompletedAt() == null)
                .count();
        if (missingEvidenceCount > 0) {
            errors.add(missingEvidenceCount + " completed CAPA action(s) are missing completion evidence or actor sign-off");
        }

        return errors;
    }

    private List<String> evaluateClosureBlockers(Deviation deviation, List<CapaAction> capas) {
        List<String> blockers = new ArrayList<>();

        if (deviation.getStatus() == DeviationStatus.CLOSED) {
            blockers.add("Deviation is already CLOSED");
            return blockers;
        }

        if (deviation.getStatus() != DeviationStatus.PENDING_VERIFICATION) {
            blockers.add("Deviation must be in PENDING_VERIFICATION stage before closure (current: "
                    + deviation.getStatus() + ")");
        }

        if (isBlank(deviation.getInvestigatedBy()) || isBlank(deviation.getInvestigationSummary())) {
            blockers.add("Investigation summary and investigator must be documented");
        }

        if (isBlank(deviation.getRootCauseCategory())
                || isBlank(deviation.getRootCauseAnalysis())
                || isBlank(deviation.getRootCauseIdentifiedBy())) {
            blockers.add("Root cause category, analysis, and identifier must be documented");
        }

        if (isBlank(deviation.getImpactAssessment())) {
            blockers.add("Impact assessment must be documented");
        }

        blockers.addAll(validateCapaCompletionForVerification(capas));
        return blockers;
    }

    // -------------------------------------------------------------------------
    // Snapshot & Mapping Helpers
    // -------------------------------------------------------------------------

    private List<DeviationSummaryDto> mapSummaryListWithCapas(List<Deviation> deviations) {
        return mapSummaryListWithCapas(deviations, capaActionRepository);
    }

    public static List<DeviationSummaryDto> mapSummaryListWithCapas(List<Deviation> deviations,
                                                                    CapaActionRepository capaActionRepository) {
        if (deviations == null || deviations.isEmpty()) {
            return List.of();
        }
        List<Long> ids = deviations.stream()
                .map(Deviation::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, List<CapaAction>> capasByDevId = ids.isEmpty() || capaActionRepository == null
                ? Collections.emptyMap()
                : safeList(capaActionRepository.findByDeviationIdInOrderByIdAsc(ids)).stream()
                .filter(c -> c.getDeviation() != null && c.getDeviation().getId() != null)
                .collect(Collectors.groupingBy(c -> c.getDeviation().getId()));

        LocalDate today = LocalDate.now(APP_ZONE_ID);
        return deviations.stream()
                .map(d -> mapToSummaryDto(d, capasByDevId.getOrDefault(d.getId(), List.of()), today))
                .toList();
    }

    private static DeviationSummaryDto mapToSummaryDto(Deviation d, List<CapaAction> capas, LocalDate today) {
        Batch batch = d.getBatch();
        ManufacturingOrder order = batch != null ? batch.getOrder() : null;
        Product product = order != null ? order.getProduct() : null;
        QualityCheck qc = d.getQualityCheck();
        BatchExecutionStep step = d.getExecutionStep();
        CapaSummary summary = summarizeCapas(capas, today);

        return new DeviationSummaryDto(
                d.getId(),
                d.getDeviationNumber(),
                batch != null ? batch.getId() : null,
                batch != null ? batch.getBatchNumber() : null,
                batch != null ? batch.getStatus() : null,
                batch != null ? batch.getQaStatus() : null,
                order != null ? order.getOrderNumber() : null,
                product != null ? product.getProductCode() : null,
                product != null ? product.getProductName() : null,
                qc != null ? qc.getId() : null,
                qc != null ? qc.getTestName() : null,
                qc != null ? qc.getResult() : null,
                step != null ? step.getId() : null,
                step != null ? step.getStepNumber() : null,
                step != null ? step.getOperationName() : null,
                d.getSourceAuditLogId(),
                d.getTitle(),
                d.getCategory(),
                d.getSeverity(),
                d.getPriority(),
                d.getStatus(),
                d.getReportedBy(),
                d.getOccurredAt(),
                d.getReportedAt(),
                d.getClosedAt(),
                summary.total,
                summary.open,
                summary.inProgress,
                summary.completed,
                summary.cancelled,
                summary.overdue
        );
    }

    private DeviationDetailDto buildDetailDto(Deviation d) {
        Batch batch = d.getBatch();
        ManufacturingOrder order = batch != null ? batch.getOrder() : null;
        Product product = order != null ? order.getProduct() : null;
        Recipe recipe = order != null ? order.getRecipe() : null;
        Integer recipeVersion = order != null && order.getRecipeVersion() != null
                ? order.getRecipeVersion()
                : (recipe != null ? recipe.getVersion() : null);

        QualityCheck qc = d.getQualityCheck();
        BatchExecutionStep step = d.getExecutionStep();

        List<CapaAction> capas = d.getId() != null
                ? safeList(capaActionRepository.findByDeviationIdOrderByIdAsc(d.getId()))
                : List.of();
        LocalDate today = LocalDate.now(APP_ZONE_ID);
        CapaSummary summary = summarizeCapas(capas, today);

        List<String> blockers = evaluateClosureBlockers(d, capas);
        boolean eligibleForClosure = d.getStatus() == DeviationStatus.PENDING_VERIFICATION && blockers.isEmpty();

        List<CapaActionResponseDto> capaDtos = capas.stream()
                .map(c -> mapToCapaDto(c, today))
                .toList();

        Set<Long> capaIds = capas.stream()
                .map(CapaAction::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<AuditLogResponseDto> auditLogs = fetchScopedAuditLogs(getBatchIdSafely(d), d.getId(), capaIds);

        return new DeviationDetailDto(
                d.getId(),
                d.getDeviationNumber(),
                batch != null ? batch.getId() : null,
                batch != null ? batch.getBatchNumber() : null,
                batch != null ? batch.getStatus() : null,
                batch != null ? batch.getQaStatus() : null,
                order != null ? order.getId() : null,
                order != null ? order.getOrderNumber() : null,
                product != null ? product.getId() : null,
                product != null ? product.getProductCode() : null,
                product != null ? product.getProductName() : null,
                recipe != null ? recipe.getRecipeCode() : null,
                recipeVersion,
                qc != null ? qc.getId() : null,
                qc != null ? qc.getTestName() : null,
                qc != null ? qc.getSpecification() : null,
                qc != null ? qc.getMeasuredValue() : null,
                qc != null ? qc.getUnit() : null,
                qc != null ? qc.getResult() : null,
                step != null ? step.getId() : null,
                step != null ? step.getStepNumber() : null,
                step != null ? step.getOperationName() : null,
                step != null ? step.getStatus() : null,
                d.getSourceAuditLogId(),
                d.getTitle(),
                d.getCategory(),
                d.getSeverity(),
                d.getPriority(),
                d.getStatus(),
                d.getDescription(),
                d.getImmediateContainment(),
                d.getReportedBy(),
                d.getOccurredAt(),
                d.getReportedAt(),
                d.getInvestigationSummary(),
                d.getInvestigatedBy(),
                d.getInvestigationStartedAt(),
                d.getRootCauseCategory(),
                d.getRootCauseAnalysis(),
                d.getImpactAssessment(),
                d.getRootCauseIdentifiedBy(),
                d.getRootCauseIdentifiedAt(),
                d.getCapaPlanSummary(),
                d.getVerifiedBy(),
                d.getVerificationNotes(),
                d.getClosedBy(),
                d.getClosedAt(),
                summary.total,
                summary.open,
                summary.inProgress,
                summary.completed,
                summary.cancelled,
                summary.overdue,
                eligibleForClosure,
                blockers,
                capaDtos,
                auditLogs,
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }

    private List<AuditLogResponseDto> fetchScopedAuditLogs(Long batchId, Long deviationId, Set<Long> capaIds) {
        if (batchId == null || deviationId == null) {
            return List.of();
        }
        return safeList(auditTrailService.getAuditLogs(batchId, null, null)).stream()
                .filter(log -> (ENTITY_DEVIATION.equals(log.getEntityType()) && Objects.equals(log.getEntityId(), deviationId))
                        || (ENTITY_CAPA_ACTION.equals(log.getEntityType()) && log.getEntityId() != null && capaIds.contains(log.getEntityId())))
                .toList();
    }

    private CapaActionResponseDto mapToCapaDto(CapaAction c, LocalDate today) {
        Deviation dev = c.getDeviation();
        boolean overdue = isOverdue(c, today);
        return new CapaActionResponseDto(
                c.getId(),
                c.getCapaNumber(),
                dev != null ? dev.getId() : null,
                dev != null ? dev.getDeviationNumber() : null,
                c.getCapaType(),
                c.getTitle(),
                c.getActionPlan(),
                c.getOwnerName(),
                c.getDueDate(),
                overdue,
                c.getStatus(),
                c.getCompletionEvidence(),
                c.getCompletedBy(),
                c.getCompletedAt(),
                c.getCancellationReason(),
                c.getCreatedBy(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }

    private static CapaSummary summarizeCapas(List<CapaAction> capas, LocalDate today) {
        long total = capas.size();
        long open = 0;
        long inProgress = 0;
        long completed = 0;
        long cancelled = 0;
        long overdue = 0;

        for (CapaAction c : capas) {
            if (c.getStatus() == CapaStatus.OPEN) {
                open++;
            } else if (c.getStatus() == CapaStatus.IN_PROGRESS) {
                inProgress++;
            } else if (c.getStatus() == CapaStatus.COMPLETED) {
                completed++;
            } else if (c.getStatus() == CapaStatus.CANCELLED) {
                cancelled++;
            }
            if (isOverdue(c, today)) {
                overdue++;
            }
        }
        return new CapaSummary(total, open, inProgress, completed, cancelled, overdue);
    }

    private static boolean isOverdue(CapaAction c, LocalDate today) {
        return c.getDueDate() != null
                && (c.getStatus() == CapaStatus.OPEN || c.getStatus() == CapaStatus.IN_PROGRESS)
                && c.getDueDate().isBefore(today);
    }

    private Map<String, Object> buildDeviationSnapshot(Deviation d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("deviationNumber", d.getDeviationNumber());
        map.put("batchId", getBatchIdSafely(d));
        map.put("batchNumber", d.getBatch() != null ? d.getBatch().getBatchNumber() : null);
        map.put("qualityCheckId", d.getQualityCheck() != null ? d.getQualityCheck().getId() : null);
        map.put("executionStepId", d.getExecutionStep() != null ? d.getExecutionStep().getId() : null);
        map.put("sourceAuditLogId", d.getSourceAuditLogId());
        map.put("title", d.getTitle());
        map.put("category", d.getCategory() != null ? d.getCategory().name() : null);
        map.put("severity", d.getSeverity() != null ? d.getSeverity().name() : null);
        map.put("priority", d.getPriority() != null ? d.getPriority().name() : null);
        map.put("status", d.getStatus() != null ? d.getStatus().name() : null);
        map.put("description", d.getDescription());
        map.put("immediateContainment", d.getImmediateContainment());
        map.put("reportedBy", d.getReportedBy());
        map.put("occurredAt", AppTime.formatIsoTimestamp(d.getOccurredAt()));
        map.put("reportedAt", AppTime.formatIsoTimestamp(d.getReportedAt()));
        map.put("investigatedBy", d.getInvestigatedBy());
        map.put("investigationSummary", d.getInvestigationSummary());
        map.put("investigationStartedAt", AppTime.formatIsoTimestamp(d.getInvestigationStartedAt()));
        map.put("rootCauseCategory", d.getRootCauseCategory());
        map.put("rootCauseAnalysis", d.getRootCauseAnalysis());
        map.put("impactAssessment", d.getImpactAssessment());
        map.put("rootCauseIdentifiedBy", d.getRootCauseIdentifiedBy());
        map.put("rootCauseIdentifiedAt", AppTime.formatIsoTimestamp(d.getRootCauseIdentifiedAt()));
        map.put("capaPlanSummary", d.getCapaPlanSummary());
        map.put("verifiedBy", d.getVerifiedBy());
        map.put("verificationNotes", d.getVerificationNotes());
        map.put("closedBy", d.getClosedBy());
        map.put("closedAt", AppTime.formatIsoTimestamp(d.getClosedAt()));
        return map;
    }

    private Map<String, Object> buildCapaSnapshot(CapaAction c, Deviation dev) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("capaNumber", c.getCapaNumber());
        map.put("deviationId", dev != null ? dev.getId() : null);
        map.put("deviationNumber", dev != null ? dev.getDeviationNumber() : null);
        map.put("capaType", c.getCapaType() != null ? c.getCapaType().name() : null);
        map.put("title", c.getTitle());
        map.put("actionPlan", c.getActionPlan());
        map.put("ownerName", c.getOwnerName());
        map.put("dueDate", c.getDueDate() != null ? c.getDueDate().toString() : null);
        map.put("status", c.getStatus() != null ? c.getStatus().name() : null);
        map.put("createdBy", c.getCreatedBy());
        map.put("completedBy", c.getCompletedBy());
        map.put("completedAt", AppTime.formatIsoTimestamp(c.getCompletedAt()));
        map.put("completionEvidence", c.getCompletionEvidence());
        map.put("cancellationReason", c.getCancellationReason());
        return map;
    }

    private static String truncateAuditReason(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private Long getBatchIdSafely(Deviation d) {
        return d != null && d.getBatch() != null ? d.getBatch().getId() : null;
    }

    private String requireNonBlank(String value, String fieldLabel, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidDeviationOperationException(fieldLabel + " is mandatory and must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new InvalidDeviationOperationException(fieldLabel + " must not exceed " + maxLength + " characters");
        }
        return trimmed;
    }

    private String normalizeOptional(String value, String fieldLabel, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new InvalidDeviationOperationException(fieldLabel + " must not exceed " + maxLength + " characters");
        }
        return trimmed;
    }

    private <T> T requireNonNull(T value, String fieldLabel) {
        if (value == null) {
            throw new InvalidDeviationOperationException(fieldLabel + " is mandatory");
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static <T> List<T> safeList(List<T> input) {
        return input != null ? input : List.of();
    }

    private record CapaSummary(long total, long open, long inProgress, long completed, long cancelled, long overdue) {
    }
}

