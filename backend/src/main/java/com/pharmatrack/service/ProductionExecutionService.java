package com.pharmatrack.service;

import com.pharmatrack.dto.BatchExecutionQueueItemDto;
import com.pharmatrack.dto.BatchExecutionStepResponseDto;
import com.pharmatrack.dto.BatchExecutionWorkspaceDto;
import com.pharmatrack.dto.CompleteExecutionStepRequestDto;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchExecutionStep;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.exception.InvalidExecutionStepException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.BatchExecutionStepRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.util.AppTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Service managing shop-floor Production Execution queue, workspace retrieval,
 * and sequential operation-level step transitions within active batches.
 */
@Service
@Transactional
public class ProductionExecutionService {

    public static final ZoneId APP_ZONE_ID = AppTime.APP_ZONE_ID;

    private final BatchRepository batchRepository;
    private final BatchExecutionStepRepository executionStepRepository;
    private final AuthenticatedActorResolver authenticatedActorResolver;

    public ProductionExecutionService(BatchRepository batchRepository,
                                      BatchExecutionStepRepository executionStepRepository) {
        this(batchRepository, executionStepRepository, new AuthenticatedActorResolver());
    }

    @Autowired
    public ProductionExecutionService(BatchRepository batchRepository,
                                      BatchExecutionStepRepository executionStepRepository,
                                      AuthenticatedActorResolver authenticatedActorResolver) {
        this.batchRepository = batchRepository;
        this.executionStepRepository = executionStepRepository;
        this.authenticatedActorResolver = authenticatedActorResolver != null
                ? authenticatedActorResolver
                : new AuthenticatedActorResolver();
    }

    // -------------------------------------------------------------------------
    // A. Execution Queue
    // -------------------------------------------------------------------------

    /**
     * Retrieve active production batches eligible for execution
     * (both Batch and parent ManufacturingOrder must be IN_PROGRESS).
     *
     * @return list of execution queue item DTOs
     */
    @Transactional(readOnly = true)
    public List<BatchExecutionQueueItemDto> getExecutionQueue() {
        List<Batch> activeBatches = batchRepository.findByStatusAndOrderStatusWithDetails(
                BatchStatus.IN_PROGRESS, OrderStatus.IN_PROGRESS);
        if (activeBatches == null || activeBatches.isEmpty()) {
            return List.of();
        }
        return activeBatches.stream()
                .filter(batch -> batch.getStatus() == BatchStatus.IN_PROGRESS
                        && batch.getOrder() != null
                        && batch.getOrder().getStatus() == OrderStatus.IN_PROGRESS)
                .map(this::mapToQueueItemDto)
                .toList();
    }

    // -------------------------------------------------------------------------
    // B. Batch Execution Workspace
    // -------------------------------------------------------------------------

    /**
     * Retrieve the read-only execution workspace for a specific batch,
     * including persisted execution steps ordered by stepNumber ascending.
     * Supports viewing completed batches read-only and never creates or modifies records.
     *
     * @param batchId batch primary key
     * @return workspace DTO containing batch metadata and snapshotted execution steps
     */
    @Transactional(readOnly = true)
    public BatchExecutionWorkspaceDto getBatchExecutionWorkspace(Long batchId) {
        if (batchId == null) {
            throw new ResourceNotFoundException("Batch not found with id: null");
        }

        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batchId));

        List<BatchExecutionStep> steps = executionStepRepository.findByBatchIdOrderByStepNumberAsc(batch.getId());
        return mapToWorkspaceDto(batch, steps);
    }

    // -------------------------------------------------------------------------
    // B2. Explicit Initialization / Backfill for IN_PROGRESS Batch
    // -------------------------------------------------------------------------

    /**
     * Explicitly initialize (or idempotently verify and return) execution steps for an already
     * IN_PROGRESS production batch using the authoritative recipe associated with its parent order.
     * Acquires a pessimistic write lock on the Batch before inspecting or creating steps.
     * Never alters batch status, order status, yield, or timestamps.
     *
     * @param batchId target batch primary key
     * @return workspace DTO containing batch metadata and initialized/existing execution steps
     */
    public BatchExecutionWorkspaceDto initializeExecutionSteps(Long batchId) {
        authenticatedActorResolver.requireCurrentActor();
        if (batchId == null) {
            throw new InvalidExecutionStepException("Batch ID is mandatory");
        }

        // 1. Acquire pessimistic write lock on the batch before inspecting or creating step records
        Batch batch = batchRepository.findByIdWithLock(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batchId));

        // 2. Verify both batch and parent order are IN_PROGRESS
        validateBatchAndOrderInProgress(batch, "initialize execution steps");

        // 3. Validate recipe, check existing steps for idempotency/consistency, or prepare new PENDING steps
        BatchExecutionStepInitializer.InitializationPlan stepPlan =
                BatchExecutionStepInitializer.prepareExecutionSteps(
                        batch,
                        batch.getOrder(),
                        executionStepRepository,
                        "initialize execution steps for batch",
                        InvalidExecutionStepException::new
                );

        List<BatchExecutionStep> resultSteps;
        if (stepPlan.hasStepsToCreate()) {
            List<BatchExecutionStep> saved = executionStepRepository.saveAll(stepPlan.getStepsToCreate());
            resultSteps = (saved != null && !saved.isEmpty()) ? saved : stepPlan.getStepsToCreate();
        } else {
            resultSteps = stepPlan.getExistingSteps();
        }

        return mapToWorkspaceDto(batch, resultSteps);
    }

    // -------------------------------------------------------------------------
    // C. Start Execution Step
    // -------------------------------------------------------------------------

    /**
     * Start a single manufacturing execution step within an active batch.
     * Acquires a pessimistic write lock on the parent Batch before inspecting or
     * mutating execution step state to help serialize concurrent operations on the same batch.
     *
     * @param batchId parent batch primary key
     * @param stepId  target execution step primary key
     * @return updated execution step response DTO
     */
    public BatchExecutionStepResponseDto startExecutionStep(Long batchId, Long stepId) {
        authenticatedActorResolver.requireCurrentActor();
        if (batchId == null) {
            throw new InvalidExecutionStepException("Batch ID is mandatory");
        }
        if (stepId == null) {
            throw new InvalidExecutionStepException("Execution step ID is mandatory");
        }

        // 1 & 2. Lock parent batch row first
        Batch batch = batchRepository.findByIdWithLock(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batchId));

        // 3 & 4. Confirm batch and parent order are both IN_PROGRESS
        validateBatchAndOrderInProgress(batch, "start execution step");

        // 5 & 6. Retrieve requested step and verify ownership by locked batch
        BatchExecutionStep targetStep = executionStepRepository.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution step not found with id: " + stepId));

        if (targetStep.getBatch() == null || !Objects.equals(targetStep.getBatch().getId(), batch.getId())) {
            throw new InvalidExecutionStepException("Execution step " + stepId
                    + " does not belong to batch " + batch.getBatchNumber());
        }

        // 7. Reject if already IN_PROGRESS or COMPLETED
        if (targetStep.getStatus() == ExecutionStepStatus.IN_PROGRESS) {
            throw new InvalidExecutionStepException("Execution step " + targetStep.getStepNumber()
                    + " (" + targetStep.getOperationName() + ") is already IN_PROGRESS");
        }
        if (targetStep.getStatus() == ExecutionStepStatus.COMPLETED) {
            throw new InvalidExecutionStepException("Execution step " + targetStep.getStepNumber()
                    + " (" + targetStep.getOperationName() + ") is already COMPLETED and cannot be restarted");
        }
        if (targetStep.getStatus() != ExecutionStepStatus.PENDING) {
            throw new InvalidExecutionStepException("Execution step " + targetStep.getStepNumber()
                    + " cannot be started from status " + targetStep.getStatus());
        }

        // 8 & 9. Enforce single active step and sequential order within the batch
        List<BatchExecutionStep> batchSteps = executionStepRepository.findByBatchIdOrderByStepNumberAsc(batch.getId());
        if (batchSteps != null) {
            for (BatchExecutionStep step : batchSteps) {
                if (step.getStatus() == ExecutionStepStatus.IN_PROGRESS) {
                    throw new InvalidExecutionStepException("Cannot start step " + targetStep.getStepNumber()
                            + " (" + targetStep.getOperationName() + ") because step " + step.getStepNumber()
                            + " (" + step.getOperationName() + ") is already IN_PROGRESS");
                }
            }
            for (BatchExecutionStep step : batchSteps) {
                if (step.getStepNumber() != null && targetStep.getStepNumber() != null
                        && step.getStepNumber() < targetStep.getStepNumber()
                        && step.getStatus() != ExecutionStepStatus.COMPLETED) {
                    throw new InvalidExecutionStepException("Cannot start step " + targetStep.getStepNumber()
                            + " (" + targetStep.getOperationName() + ") before earlier step " + step.getStepNumber()
                            + " (" + step.getOperationName() + ") is COMPLETED");
                }
            }
        }

        // 10, 11 & 12. Transition status, record startedAt, and persist
        targetStep.setStatus(ExecutionStepStatus.IN_PROGRESS);
        targetStep.setStartedAt(AppTime.nowDateTime());
        BatchExecutionStep saved = executionStepRepository.save(targetStep);

        return mapToStepResponseDto(saved);
    }

    // -------------------------------------------------------------------------
    // D. Complete Execution Step
    // -------------------------------------------------------------------------

    /**
     * Complete an IN_PROGRESS execution step within an active batch.
     * Completed steps become immutable and cannot be completed or started again.
     * Attributes {@code executedBy} strictly from the authenticated user principal.
     *
     * @param batchId    parent batch primary key
     * @param stepId     target execution step primary key
     * @param requestDto operator-submitted actual value and optional observations
     * @return updated execution step response DTO
     */
    public BatchExecutionStepResponseDto completeExecutionStep(Long batchId,
                                                               Long stepId,
                                                               CompleteExecutionStepRequestDto requestDto) {
        String resolvedExecutedBy = authenticatedActorResolver.requireCurrentActor();
        if (batchId == null) {
            throw new InvalidExecutionStepException("Batch ID is mandatory");
        }
        if (stepId == null) {
            throw new InvalidExecutionStepException("Execution step ID is mandatory");
        }

        // 1 & 2. Lock parent batch row first
        Batch batch = batchRepository.findByIdWithLock(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batchId));

        // 3 & 4. Confirm batch and parent order are both IN_PROGRESS
        validateBatchAndOrderInProgress(batch, "complete execution step");

        // 5. Retrieve step and confirm ownership by locked batch
        BatchExecutionStep targetStep = executionStepRepository.findById(stepId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution step not found with id: " + stepId));

        if (targetStep.getBatch() == null || !Objects.equals(targetStep.getBatch().getId(), batch.getId())) {
            throw new InvalidExecutionStepException("Execution step " + stepId
                    + " does not belong to batch " + batch.getBatchNumber());
        }

        // 6. Confirm current status is IN_PROGRESS (completed records are immutable)
        if (targetStep.getStatus() == ExecutionStepStatus.COMPLETED) {
            throw new InvalidExecutionStepException("Execution step " + targetStep.getStepNumber()
                    + " (" + targetStep.getOperationName() + ") is already COMPLETED and cannot be modified or completed again");
        }
        if (targetStep.getStatus() != ExecutionStepStatus.IN_PROGRESS) {
            throw new InvalidExecutionStepException("Cannot complete execution step " + targetStep.getStepNumber()
                    + " (" + targetStep.getOperationName() + ") from status " + targetStep.getStatus()
                    + ". Step must be IN_PROGRESS before completion.");
        }

        // 7. Validate mandatory non-blank actualValue
        if (requestDto == null || requestDto.getActualValue() == null || requestDto.getActualValue().trim().isEmpty()) {
            throw new InvalidExecutionStepException("Actual value is mandatory and must not be blank when completing an execution step");
        }

        String normalizedActualValue = requestDto.getActualValue().trim();
        if (normalizedActualValue.length() > 255) {
            throw new InvalidExecutionStepException("Actual value must not exceed 255 characters");
        }

        String normalizedObservations = null;
        if (requestDto.getObservations() != null && !requestDto.getObservations().trim().isEmpty()) {
            normalizedObservations = requestDto.getObservations().trim();
            if (normalizedObservations.length() > 2000) {
                throw new InvalidExecutionStepException("Observations must not exceed 2000 characters");
            }
        }

        // 8, 9, 10, 11 & 12. Save execution result, trusted executedBy, completedAt, transition to COMPLETED, persist
        targetStep.setActualValue(normalizedActualValue);
        targetStep.setObservations(normalizedObservations);
        targetStep.setExecutedBy(resolvedExecutedBy);
        targetStep.setCompletedAt(AppTime.nowDateTime());
        targetStep.setStatus(ExecutionStepStatus.COMPLETED);

        BatchExecutionStep saved = executionStepRepository.save(targetStep);
        return mapToStepResponseDto(saved);
    }

    // -------------------------------------------------------------------------
    // Internal Validation & Mapping Helpers
    // -------------------------------------------------------------------------

    private void validateBatchAndOrderInProgress(Batch batch, String action) {
        if (batch.getStatus() != BatchStatus.IN_PROGRESS) {
            throw new InvalidExecutionStepException("Cannot " + action + " because batch "
                    + batch.getBatchNumber() + " is in " + batch.getStatus() + " status. Batch must be IN_PROGRESS.");
        }
        ManufacturingOrder order = batch.getOrder();
        if (order == null || order.getStatus() != OrderStatus.IN_PROGRESS) {
            String orderNumber = order != null ? order.getOrderNumber() : "unknown";
            String orderStatus = order != null && order.getStatus() != null ? order.getStatus().name() : "unknown";
            throw new InvalidExecutionStepException("Cannot " + action + " because parent order "
                    + orderNumber + " is in " + orderStatus + " status. Order must be IN_PROGRESS.");
        }
    }

    private BatchExecutionWorkspaceDto mapToWorkspaceDto(Batch batch, List<BatchExecutionStep> steps) {
        ManufacturingOrder order = batch.getOrder();
        Product product = order != null ? order.getProduct() : null;
        Recipe recipe = order != null ? order.getRecipe() : null;
        Integer recipeVersion = order != null && order.getRecipeVersion() != null
                ? order.getRecipeVersion()
                : (recipe != null ? recipe.getVersion() : null);

        List<BatchExecutionStepResponseDto> stepDtos = (steps != null ? steps : List.<BatchExecutionStep>of())
                .stream()
                .map(this::mapToStepResponseDto)
                .toList();

        return new BatchExecutionWorkspaceDto(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getStatus(),
                order != null ? order.getOrderNumber() : null,
                product != null ? product.getProductName() : null,
                recipe != null ? recipe.getRecipeCode() : null,
                recipeVersion,
                batch.getBatchSize(),
                batch.getBatchUnit(),
                stepDtos
        );
    }

    private BatchExecutionQueueItemDto mapToQueueItemDto(Batch batch) {
        ManufacturingOrder order = batch.getOrder();
        Product product = order != null ? order.getProduct() : null;
        Recipe recipe = order != null ? order.getRecipe() : null;
        Integer recipeVersion = order != null && order.getRecipeVersion() != null
                ? order.getRecipeVersion()
                : (recipe != null ? recipe.getVersion() : null);

        return new BatchExecutionQueueItemDto(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getStatus(),
                order != null ? order.getOrderNumber() : null,
                product != null ? product.getProductName() : null,
                recipe != null ? recipe.getRecipeCode() : null,
                recipeVersion,
                batch.getBatchSize(),
                batch.getBatchUnit(),
                batch.getStartTime()
        );
    }

    private BatchExecutionStepResponseDto mapToStepResponseDto(BatchExecutionStep step) {
        return new BatchExecutionStepResponseDto(
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
        );
    }
}

