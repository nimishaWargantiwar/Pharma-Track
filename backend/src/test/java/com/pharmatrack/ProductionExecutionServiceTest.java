package com.pharmatrack;

import com.pharmatrack.dto.BatchExecutionQueueItemDto;
import com.pharmatrack.dto.BatchExecutionStepResponseDto;
import com.pharmatrack.dto.BatchExecutionWorkspaceDto;
import com.pharmatrack.dto.BatchResponseDto;
import com.pharmatrack.dto.BatchStatusUpdateDto;
import com.pharmatrack.dto.CompleteExecutionStepRequestDto;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchExecutionStep;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.exception.InvalidExecutionStepException;
import com.pharmatrack.exception.InvalidOrderException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.BatchExecutionStepRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.ManufacturingOrderService;
import com.pharmatrack.service.ProductionExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link ProductionExecutionService} and the execution
 * initialization / completion integration within {@link ManufacturingOrderService}.
 */
@ExtendWith(MockitoExtension.class)
class ProductionExecutionServiceTest {

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private BatchExecutionStepRepository executionStepRepository;

    @Mock
    private AuditTrailService auditTrailService;

    @Mock
    private ManufacturingOrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private RecipeRepository recipeRepository;

    @InjectMocks
    private ProductionExecutionService executionService;

    @InjectMocks
    private ManufacturingOrderService orderService;

    private Product activeProduct;
    private Recipe orderRecipe;
    private RecipeStep recipeStep1;
    private RecipeStep recipeStep2;
    private RecipeStep recipeStep3;
    private ManufacturingOrder inProgressOrder;
    private Batch inProgressBatch;
    private BatchExecutionStep execStep1;
    private BatchExecutionStep execStep2;
    private BatchExecutionStep execStep3;

    @BeforeEach
    void setUp() {
        activeProduct = new Product(
                "AMOX-250",
                "Amoxicillin Capsules",
                "250 mg",
                "Capsule",
                "Broad-spectrum antibiotic",
                ProductStatus.ACTIVE
        );
        activeProduct.setId(1L);

        orderRecipe = new Recipe(
                "RCP-AMOX-250-V3",
                activeProduct,
                3,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Approved V3 recipe"
        );
        orderRecipe.setId(10L);

        recipeStep1 = new RecipeStep(1, "Dispensing", "Dispense active ingredient and excipients.");
        recipeStep1.setId(201L);
        recipeStep2 = new RecipeStep(2, "Dry Blending", "Blend dry powders in V-blender at 25 RPM.");
        recipeStep2.setId(202L);
        recipeStep3 = new RecipeStep(3, "Encapsulation", "Fill hard gelatin capsules to 500 mg weight.");
        recipeStep3.setId(203L);

        orderRecipe.addStep(recipeStep1);
        orderRecipe.addStep(recipeStep2);
        orderRecipe.addStep(recipeStep3);

        inProgressOrder = new ManufacturingOrder(
                "MO-2026-0001",
                activeProduct,
                orderRecipe,
                3,
                new BigDecimal("500.0000"),
                "kg",
                2,
                OrderStatus.IN_PROGRESS,
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 15),
                "Two-batch order"
        );
        inProgressOrder.setId(100L);
        inProgressOrder.setActualStartDate(LocalDateTime.of(2026, 11, 10, 8, 0));

        inProgressBatch = new Batch(
                "BAT-2026-0001-01",
                inProgressOrder,
                new BigDecimal("250.0000"),
                "kg",
                BatchStatus.IN_PROGRESS,
                LocalDate.of(2026, 11, 10),
                null
        );
        inProgressBatch.setId(500L);
        inProgressBatch.setStartTime(LocalDateTime.of(2026, 11, 10, 8, 5));

        execStep1 = new BatchExecutionStep(
                inProgressBatch,
                recipeStep1,
                1,
                "Dispensing",
                "Dispense active ingredient and excipients.",
                ExecutionStepStatus.PENDING
        );
        execStep1.setId(1001L);

        execStep2 = new BatchExecutionStep(
                inProgressBatch,
                recipeStep2,
                2,
                "Dry Blending",
                "Blend dry powders in V-blender at 25 RPM.",
                ExecutionStepStatus.PENDING
        );
        execStep2.setId(1002L);

        execStep3 = new BatchExecutionStep(
                inProgressBatch,
                recipeStep3,
                3,
                "Encapsulation",
                "Fill hard gelatin capsules to 500 mg weight.",
                ExecutionStepStatus.PENDING
        );
        execStep3.setId(1003L);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "J.Doe",
                        "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_PRODUCTION_SUPERVISOR")
                )
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // 1. Execution Queue Tests
    // =========================================================================

    @Test
    @DisplayName("Queue: returns IN_PROGRESS batches with associated order, product, and recipe snapshot info")
    void queueReturnsInProgressBatchesWithCorrectDetails() {
        when(batchRepository.findByStatusAndOrderStatusWithDetails(BatchStatus.IN_PROGRESS, OrderStatus.IN_PROGRESS))
                .thenReturn(List.of(inProgressBatch));

        List<BatchExecutionQueueItemDto> queue = executionService.getExecutionQueue();

        assertEquals(1, queue.size());
        BatchExecutionQueueItemDto item = queue.get(0);
        assertEquals(500L, item.getBatchId());
        assertEquals("BAT-2026-0001-01", item.getBatchNumber());
        assertEquals(BatchStatus.IN_PROGRESS, item.getBatchStatus());
        assertEquals("MO-2026-0001", item.getOrderNumber());
        assertEquals("Amoxicillin Capsules", item.getProductName());
        assertEquals("RCP-AMOX-250-V3", item.getRecipeCode());
        assertEquals(3, item.getRecipeVersion());
        assertEquals(new BigDecimal("250.0000"), item.getBatchSize());
        assertEquals("kg", item.getBatchUnit());
        assertEquals(inProgressBatch.getStartTime(), item.getStartTime());
        verifyNoInteractions(recipeRepository);
    }

    @Test
    @DisplayName("Queue: excludes PLANNED, COMPLETED, CANCELLED batches and batches whose parent order is not IN_PROGRESS")
    void queueExcludesIneligibleBatchesAndNonInProgressOrders() {
        Batch plannedBatch = new Batch("BAT-PLANNED", inProgressOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        plannedBatch.setId(501L);

        Batch completedBatch = new Batch("BAT-COMPLETED", inProgressOrder, new BigDecimal("250.0000"), "kg", BatchStatus.COMPLETED, null, null);
        completedBatch.setId(502L);

        Batch cancelledBatch = new Batch("BAT-CANCELLED", inProgressOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        cancelledBatch.setId(503L);

        ManufacturingOrder releasedOrder = new ManufacturingOrder(
                "MO-RELEASED", activeProduct, orderRecipe, 3, new BigDecimal("250.0000"),
                "kg", 1, OrderStatus.RELEASED, null, null, null);
        Batch batchUnderReleasedOrder = new Batch("BAT-REL-ORD", releasedOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        batchUnderReleasedOrder.setId(504L);

        when(batchRepository.findByStatusAndOrderStatusWithDetails(BatchStatus.IN_PROGRESS, OrderStatus.IN_PROGRESS))
                .thenReturn(List.of(inProgressBatch, plannedBatch, completedBatch, cancelledBatch, batchUnderReleasedOrder));

        List<BatchExecutionQueueItemDto> queue = executionService.getExecutionQueue();

        assertEquals(1, queue.size());
        assertEquals(500L, queue.get(0).getBatchId());
        verify(batchRepository).findByStatusAndOrderStatusWithDetails(BatchStatus.IN_PROGRESS, OrderStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Queue: returns empty list when no batches are eligible")
    void queueReturnsEmptyListWhenNoActiveBatches() {
        when(batchRepository.findByStatusAndOrderStatusWithDetails(BatchStatus.IN_PROGRESS, OrderStatus.IN_PROGRESS))
                .thenReturn(List.of());

        List<BatchExecutionQueueItemDto> queue = executionService.getExecutionQueue();

        assertNotNull(queue);
        assertTrue(queue.isEmpty());
    }

    // =========================================================================
    // 2. Execution Workspace Tests
    // =========================================================================

    @Test
    @DisplayName("Workspace: returns ordered steps using snapshot fields and order recipe version without mutating records")
    void workspaceReturnsOrderedStepsUsingSnapshotsWithoutMutation() {
        // Simulate snapshotted step wording differing from any future recipe change
        execStep1.setOperationName("Snapshotted Dispensing V3");
        execStep1.setInstructions("Snapshotted V3 dispensing instruction");
        execStep1.setStatus(ExecutionStepStatus.COMPLETED);
        execStep1.setActualValue("250.0 kg");
        execStep1.setExecutedBy("A. Smith");
        execStep1.setStartedAt(LocalDateTime.of(2026, 11, 10, 8, 10));
        execStep1.setCompletedAt(LocalDateTime.of(2026, 11, 10, 8, 30));

        when(batchRepository.findById(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(500L))
                .thenReturn(List.of(execStep1, execStep2, execStep3));

        BatchExecutionWorkspaceDto workspace = executionService.getBatchExecutionWorkspace(500L);

        assertEquals(500L, workspace.getBatchId());
        assertEquals("BAT-2026-0001-01", workspace.getBatchNumber());
        assertEquals(BatchStatus.IN_PROGRESS, workspace.getBatchStatus());
        assertEquals("MO-2026-0001", workspace.getOrderNumber());
        assertEquals("Amoxicillin Capsules", workspace.getProductName());
        assertEquals("RCP-AMOX-250-V3", workspace.getRecipeCode());
        assertEquals(3, workspace.getRecipeVersion());
        assertEquals(new BigDecimal("250.0000"), workspace.getBatchSize());
        assertEquals("kg", workspace.getBatchUnit());

        assertEquals(3, workspace.getExecutionSteps().size());
        BatchExecutionStepResponseDto first = workspace.getExecutionSteps().get(0);
        assertEquals(1, first.getStepNumber());
        assertEquals("Snapshotted Dispensing V3", first.getOperationName());
        assertEquals("Snapshotted V3 dispensing instruction", first.getInstructions());
        assertEquals(ExecutionStepStatus.COMPLETED, first.getStatus());
        assertEquals("250.0 kg", first.getActualValue());
        assertEquals("A. Smith", first.getExecutedBy());
        assertEquals(2, workspace.getExecutionSteps().get(1).getStepNumber());
        assertEquals(3, workspace.getExecutionSteps().get(2).getStepNumber());

        verify(executionStepRepository, never()).save(any());
        verify(executionStepRepository, never()).saveAll(any());
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("Workspace: allows COMPLETED batch workspace to be viewed read-only")
    void workspaceAllowsViewingCompletedBatchReadOnly() {
        inProgressBatch.setStatus(BatchStatus.COMPLETED);
        execStep1.setStatus(ExecutionStepStatus.COMPLETED);
        execStep2.setStatus(ExecutionStepStatus.COMPLETED);
        execStep3.setStatus(ExecutionStepStatus.COMPLETED);

        when(batchRepository.findById(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(500L))
                .thenReturn(List.of(execStep1, execStep2, execStep3));

        BatchExecutionWorkspaceDto workspace = executionService.getBatchExecutionWorkspace(500L);

        assertEquals(BatchStatus.COMPLETED, workspace.getBatchStatus());
        assertEquals(3, workspace.getExecutionSteps().size());
        verify(executionStepRepository, never()).save(any());
        verify(executionStepRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Workspace: throws ResourceNotFoundException when batch does not exist")
    void workspaceThrowsWhenBatchNotFound() {
        when(batchRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> executionService.getBatchExecutionWorkspace(999L));
        assertThrows(ResourceNotFoundException.class, () -> executionService.getBatchExecutionWorkspace(null));
        verifyNoInteractions(executionStepRepository);
    }

    // =========================================================================
    // 3. Batch Start Initialization Tests (via ManufacturingOrderService)
    // =========================================================================

    @Test
    @DisplayName("Initialization: starting a batch creates one ordered PENDING execution step per recipe step from order recipe")
    void batchStartCreatesOrderedPendingStepsFromExactOrderRecipe() {
        ManufacturingOrder releasedOrder = new ManufacturingOrder(
                "MO-2026-0002", activeProduct, orderRecipe, 3,
                new BigDecimal("250.0000"), "kg", 1, OrderStatus.RELEASED,
                null, null, null
        );
        releasedOrder.setId(101L);

        // Put recipe steps out of order to prove ascending sort during initialization
        Recipe unorderedRecipe = new Recipe(
                "RCP-AMOX-250-V3", activeProduct, 3,
                new BigDecimal("250.0000"), "kg", RecipeStatus.APPROVED, "Recipe"
        );
        unorderedRecipe.setId(10L);
        unorderedRecipe.addStep(recipeStep3);
        unorderedRecipe.addStep(recipeStep1);
        unorderedRecipe.addStep(recipeStep2);
        releasedOrder.setRecipe(unorderedRecipe);

        Batch plannedBatch = new Batch(
                "BAT-2026-0002-01", releasedOrder, new BigDecimal("250.0000"),
                "kg", BatchStatus.PLANNED, null, null
        );
        plannedBatch.setId(501L);

        when(batchRepository.findById(501L)).thenReturn(Optional.of(plannedBatch));
        when(orderRepository.findByIdWithLock(101L)).thenReturn(Optional.of(releasedOrder));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(501L)).thenReturn(List.of());
        when(batchRepository.save(any(Batch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(executionStepRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        BatchResponseDto response = orderService.updateBatchStatus(501L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS));

        assertEquals(BatchStatus.IN_PROGRESS, response.getStatus());
        assertEquals(OrderStatus.IN_PROGRESS, releasedOrder.getStatus());

        verifyNoInteractions(recipeRepository);
        verify(executionStepRepository).saveAll(argThat(iterable -> {
            List<BatchExecutionStep> saved = new ArrayList<>();
            iterable.forEach(saved::add);
            if (saved.size() != 3) {
                return false;
            }
            return saved.get(0).getStepNumber() == 1
                    && "Dispensing".equals(saved.get(0).getOperationName())
                    && "Dispense active ingredient and excipients.".equals(saved.get(0).getInstructions())
                    && saved.get(0).getStatus() == ExecutionStepStatus.PENDING
                    && saved.get(0).getRecipeStep() == recipeStep1
                    && saved.get(0).getBatch() == plannedBatch
                    && saved.get(1).getStepNumber() == 2
                    && saved.get(1).getRecipeStep() == recipeStep2
                    && saved.get(1).getStatus() == ExecutionStepStatus.PENDING
                    && saved.get(2).getStepNumber() == 3
                    && saved.get(2).getRecipeStep() == recipeStep3
                    && saved.get(2).getStatus() == ExecutionStepStatus.PENDING;
        }));
    }

    @Test
    @DisplayName("Initialization: repeated initialization does not create duplicates when consistent steps already exist or batch is already IN_PROGRESS")
    void repeatedInitializationDoesNotCreateDuplicates() {
        ManufacturingOrder releasedOrder = new ManufacturingOrder(
                "MO-2026-0003", activeProduct, orderRecipe, 3,
                new BigDecimal("250.0000"), "kg", 1, OrderStatus.RELEASED,
                null, null, null
        );
        releasedOrder.setId(102L);

        Batch plannedBatch = new Batch(
                "BAT-2026-0003-01", releasedOrder, new BigDecimal("250.0000"),
                "kg", BatchStatus.PLANNED, null, null
        );
        plannedBatch.setId(502L);

        BatchExecutionStep existing1 = new BatchExecutionStep(
                plannedBatch, recipeStep1, 1, recipeStep1.getOperationName(), recipeStep1.getInstructions());
        BatchExecutionStep existing2 = new BatchExecutionStep(
                plannedBatch, recipeStep2, 2, recipeStep2.getOperationName(), recipeStep2.getInstructions());
        BatchExecutionStep existing3 = new BatchExecutionStep(
                plannedBatch, recipeStep3, 3, recipeStep3.getOperationName(), recipeStep3.getInstructions());

        when(batchRepository.findById(502L)).thenReturn(Optional.of(plannedBatch));
        when(orderRepository.findByIdWithLock(102L)).thenReturn(Optional.of(releasedOrder));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(502L))
                .thenReturn(List.of(existing1, existing2, existing3));
        when(batchRepository.save(any(Batch.class))).thenAnswer(inv -> inv.getArgument(0));

        // First call with pre-existing consistent records should not duplicate
        BatchResponseDto firstCall = orderService.updateBatchStatus(502L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS));
        assertEquals(BatchStatus.IN_PROGRESS, firstCall.getStatus());

        // Second call when batch is already IN_PROGRESS returns idempotently
        BatchResponseDto retryCall = orderService.updateBatchStatus(502L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS));
        assertEquals(BatchStatus.IN_PROGRESS, retryCall.getStatus());

        verify(executionStepRepository, never()).saveAll(any());
        verify(executionStepRepository, never()).save(any());
    }

    @Test
    @DisplayName("Initialization: rejects start if existing execution records are inconsistent with order recipe")
    void initializationRejectsInconsistentExistingRecords() {
        ManufacturingOrder releasedOrder = new ManufacturingOrder(
                "MO-2026-0004", activeProduct, orderRecipe, 3,
                new BigDecimal("250.0000"), "kg", 1, OrderStatus.RELEASED,
                null, null, null
        );
        releasedOrder.setId(103L);

        Batch plannedBatch = new Batch(
                "BAT-2026-0004-01", releasedOrder, new BigDecimal("250.0000"),
                "kg", BatchStatus.PLANNED, null, null
        );
        plannedBatch.setId(503L);

        // Only 1 of 3 steps exists (inconsistent partial state)
        BatchExecutionStep partialStep = new BatchExecutionStep(
                plannedBatch, recipeStep1, 1, recipeStep1.getOperationName(), recipeStep1.getInstructions());

        when(batchRepository.findById(503L)).thenReturn(Optional.of(plannedBatch));
        when(orderRepository.findByIdWithLock(103L)).thenReturn(Optional.of(releasedOrder));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(503L))
                .thenReturn(List.of(partialStep));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(503L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS)));

        assertTrue(ex.getMessage().contains("does not match order recipe"));
        assertEquals(BatchStatus.PLANNED, plannedBatch.getStatus());
        verify(executionStepRepository, never()).saveAll(any());
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("Initialization: rejects starting batch safely when recipe has no manufacturing steps")
    void initializationHandlesEmptyRecipeStepsSafely() {
        Recipe emptyRecipe = new Recipe(
                "RCP-EMPTY-V1", activeProduct, 1,
                new BigDecimal("250.0000"), "kg", RecipeStatus.APPROVED, "No steps"
        );
        emptyRecipe.setId(12L);

        ManufacturingOrder emptyStepOrder = new ManufacturingOrder(
                "MO-2026-EMPTY", activeProduct, emptyRecipe, 1,
                new BigDecimal("250.0000"), "kg", 1, OrderStatus.RELEASED,
                null, null, null
        );
        emptyStepOrder.setId(104L);

        Batch plannedBatch = new Batch(
                "BAT-EMPTY-01", emptyStepOrder, new BigDecimal("250.0000"),
                "kg", BatchStatus.PLANNED, null, null
        );
        plannedBatch.setId(504L);

        when(batchRepository.findById(504L)).thenReturn(Optional.of(plannedBatch));
        when(orderRepository.findByIdWithLock(104L)).thenReturn(Optional.of(emptyStepOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(504L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS)));

        assertTrue(ex.getMessage().contains("no manufacturing steps"));
        assertEquals(BatchStatus.PLANNED, plannedBatch.getStatus());
        verify(batchRepository, never()).save(any());
        verify(executionStepRepository, never()).saveAll(any());
    }

    // =========================================================================
    // 3B. Explicit Initialization / Backfill Tests (IN_PROGRESS Batches)
    // =========================================================================

    @Test
    @DisplayName("Backfill init: locks batch, creates ordered PENDING steps from 2-step order recipe without altering batch/order state")
    void explicitInitializationBackfillsInProgressBatchWithTwoRecipeSteps() {
        Recipe twoStepRecipe = new Recipe(
                "RCP-AMOX-250-V2",
                activeProduct,
                2,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Two-step legacy recipe"
        );
        twoStepRecipe.setId(20L);
        RecipeStep step1 = new RecipeStep(1, "Weighing", "Weigh active ingredient accurately.");
        step1.setId(301L);
        RecipeStep step2 = new RecipeStep(2, "Blending", "Blend powders for 15 minutes.");
        step2.setId(302L);
        // Add in reverse order to prove ascending sort during backfill
        twoStepRecipe.addStep(step2);
        twoStepRecipe.addStep(step1);

        LocalDateTime orderStart = LocalDateTime.of(2026, 11, 8, 8, 0);
        LocalDateTime batchStart = LocalDateTime.of(2026, 11, 8, 8, 15);
        LocalDate manufactureDate = LocalDate.of(2026, 11, 8);

        ManufacturingOrder legacyOrder = new ManufacturingOrder(
                "MO-2026-0004",
                activeProduct,
                twoStepRecipe,
                2,
                new BigDecimal("250.0000"),
                "kg",
                1,
                OrderStatus.IN_PROGRESS,
                LocalDate.of(2026, 11, 8),
                LocalDate.of(2026, 11, 10),
                "Started under older backend"
        );
        legacyOrder.setId(4L);
        legacyOrder.setActualStartDate(orderStart);

        Batch legacyBatch = new Batch(
                "BAT-2026-0004-01",
                legacyOrder,
                new BigDecimal("250.0000"),
                "kg",
                BatchStatus.IN_PROGRESS,
                manufactureDate,
                null
        );
        legacyBatch.setId(5L);
        legacyBatch.setStartTime(batchStart);

        when(batchRepository.findByIdWithLock(5L)).thenReturn(Optional.of(legacyBatch));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(5L)).thenReturn(List.of());
        when(executionStepRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<BatchExecutionStep> steps = inv.getArgument(0);
            for (int i = 0; i < steps.size(); i++) {
                steps.get(i).setId(2001L + i);
            }
            return steps;
        });

        BatchExecutionWorkspaceDto workspace = executionService.initializeExecutionSteps(5L);

        assertNotNull(workspace);
        assertEquals(5L, workspace.getBatchId());
        assertEquals("BAT-2026-0004-01", workspace.getBatchNumber());
        assertEquals(BatchStatus.IN_PROGRESS, workspace.getBatchStatus());
        assertEquals("MO-2026-0004", workspace.getOrderNumber());
        assertEquals("RCP-AMOX-250-V2", workspace.getRecipeCode());
        assertEquals(2, workspace.getRecipeVersion());
        assertEquals(2, workspace.getExecutionSteps().size());

        BatchExecutionStepResponseDto first = workspace.getExecutionSteps().get(0);
        assertEquals(1, first.getStepNumber());
        assertEquals("Weighing", first.getOperationName());
        assertEquals("Weigh active ingredient accurately.", first.getInstructions());
        assertEquals(ExecutionStepStatus.PENDING, first.getStatus());

        BatchExecutionStepResponseDto second = workspace.getExecutionSteps().get(1);
        assertEquals(2, second.getStepNumber());
        assertEquals("Blending", second.getOperationName());
        assertEquals("Blend powders for 15 minutes.", second.getInstructions());
        assertEquals(ExecutionStepStatus.PENDING, second.getStatus());

        // Lock acquired before checking and creating steps
        InOrder inOrder = inOrder(batchRepository, executionStepRepository);
        inOrder.verify(batchRepository).findByIdWithLock(5L);
        inOrder.verify(executionStepRepository).findByBatchIdOrderByStepNumberAsc(5L);
        inOrder.verify(executionStepRepository).saveAll(argThat(iterable -> {
            List<BatchExecutionStep> saved = new ArrayList<>();
            iterable.forEach(saved::add);
            return saved.size() == 2
                    && saved.get(0).getBatch() == legacyBatch
                    && saved.get(0).getRecipeStep() == step1
                    && saved.get(0).getStatus() == ExecutionStepStatus.PENDING
                    && saved.get(1).getBatch() == legacyBatch
                    && saved.get(1).getRecipeStep() == step2
                    && saved.get(1).getStatus() == ExecutionStepStatus.PENDING;
        }));

        // Batch/order status, yield, and timestamps untouched
        assertEquals(BatchStatus.IN_PROGRESS, legacyBatch.getStatus());
        assertEquals(OrderStatus.IN_PROGRESS, legacyOrder.getStatus());
        assertNull(legacyBatch.getActualYield());
        assertEquals(batchStart, legacyBatch.getStartTime());
        assertNull(legacyBatch.getEndTime());
        assertEquals(manufactureDate, legacyBatch.getManufactureDate());
        assertEquals(orderStart, legacyOrder.getActualStartDate());
        assertNull(legacyOrder.getActualEndDate());
        verify(batchRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(recipeRepository);
    }

    @Test
    @DisplayName("Backfill init: repeated initialization returns existing consistent records without creating duplicates")
    void explicitInitializationIsIdempotentWhenConsistentRecordsAlreadyExist() {
        Recipe twoStepRecipe = new Recipe(
                "RCP-AMOX-250-V2",
                activeProduct,
                2,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Two-step recipe"
        );
        twoStepRecipe.setId(20L);
        RecipeStep step1 = new RecipeStep(1, "Weighing", "Weigh active ingredient accurately.");
        step1.setId(301L);
        RecipeStep step2 = new RecipeStep(2, "Blending", "Blend powders for 15 minutes.");
        step2.setId(302L);
        twoStepRecipe.addStep(step1);
        twoStepRecipe.addStep(step2);

        inProgressOrder.setRecipe(twoStepRecipe);
        inProgressOrder.setRecipeVersion(2);
        inProgressBatch.setId(5L);

        BatchExecutionStep existing1 = new BatchExecutionStep(
                inProgressBatch, step1, 1, step1.getOperationName(), step1.getInstructions(), ExecutionStepStatus.IN_PROGRESS);
        existing1.setId(2001L);
        BatchExecutionStep existing2 = new BatchExecutionStep(
                inProgressBatch, step2, 2, step2.getOperationName(), step2.getInstructions(), ExecutionStepStatus.PENDING);
        existing2.setId(2002L);

        when(batchRepository.findByIdWithLock(5L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(5L))
                .thenReturn(List.of(existing1, existing2));

        BatchExecutionWorkspaceDto firstCall = executionService.initializeExecutionSteps(5L);
        BatchExecutionWorkspaceDto secondCall = executionService.initializeExecutionSteps(5L);

        assertEquals(2, firstCall.getExecutionSteps().size());
        assertEquals(2, secondCall.getExecutionSteps().size());
        assertEquals(2001L, secondCall.getExecutionSteps().get(0).getId());
        assertEquals(ExecutionStepStatus.IN_PROGRESS, secondCall.getExecutionSteps().get(0).getStatus());
        assertEquals(2002L, secondCall.getExecutionSteps().get(1).getId());
        assertEquals(ExecutionStepStatus.PENDING, secondCall.getExecutionSteps().get(1).getStatus());

        verify(executionStepRepository, never()).save(any());
        verify(executionStepRepository, never()).saveAll(any());
        verify(batchRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("Backfill init: throws ResourceNotFoundException when batch does not exist")
    void explicitInitializationThrowsWhenBatchNotFound() {
        when(batchRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> executionService.initializeExecutionSteps(999L));
        assertThrows(InvalidExecutionStepException.class, () -> executionService.initializeExecutionSteps(null));
        verifyNoInteractions(executionStepRepository);
    }

    @Test
    @DisplayName("Backfill init: rejects initialization when batch or parent order is not IN_PROGRESS")
    void explicitInitializationRejectsNonInProgressBatchOrOrder() {
        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));

        inProgressBatch.setStatus(BatchStatus.PLANNED);
        InvalidExecutionStepException plannedBatchEx = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.initializeExecutionSteps(500L));
        assertTrue(plannedBatchEx.getMessage().contains("Batch must be IN_PROGRESS"));

        inProgressBatch.setStatus(BatchStatus.COMPLETED);
        assertThrows(InvalidExecutionStepException.class,
                () -> executionService.initializeExecutionSteps(500L));

        inProgressBatch.setStatus(BatchStatus.CANCELLED);
        assertThrows(InvalidExecutionStepException.class,
                () -> executionService.initializeExecutionSteps(500L));

        inProgressBatch.setStatus(BatchStatus.IN_PROGRESS);
        inProgressOrder.setStatus(OrderStatus.RELEASED);
        InvalidExecutionStepException releasedOrderEx = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.initializeExecutionSteps(500L));
        assertTrue(releasedOrderEx.getMessage().contains("Order must be IN_PROGRESS"));

        inProgressOrder.setStatus(OrderStatus.COMPLETED);
        assertThrows(InvalidExecutionStepException.class,
                () -> executionService.initializeExecutionSteps(500L));

        verifyNoInteractions(executionStepRepository);
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("Backfill init: rejects initialization when order recipe has no steps or is missing")
    void explicitInitializationRejectsRecipeWithNoSteps() {
        Recipe emptyRecipe = new Recipe(
                "RCP-EMPTY-V1", activeProduct, 1,
                new BigDecimal("250.0000"), "kg", RecipeStatus.APPROVED, "No steps"
        );
        emptyRecipe.setId(12L);
        inProgressOrder.setRecipe(emptyRecipe);

        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));

        InvalidExecutionStepException emptyStepsEx = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.initializeExecutionSteps(500L));
        assertTrue(emptyStepsEx.getMessage().contains("no manufacturing steps"));

        inProgressOrder.setRecipe(null);
        InvalidExecutionStepException nullRecipeEx = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.initializeExecutionSteps(500L));
        assertTrue(nullRecipeEx.getMessage().contains("no associated recipe"));

        verify(executionStepRepository, never()).save(any());
        verify(executionStepRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Backfill init: rejects existing partial or inconsistent execution records without modifying or deleting history")
    void explicitInitializationRejectsPartialOrInconsistentExistingRecords() {
        Recipe twoStepRecipe = new Recipe(
                "RCP-AMOX-250-V2",
                activeProduct,
                2,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Two-step recipe"
        );
        twoStepRecipe.setId(20L);
        RecipeStep step1 = new RecipeStep(1, "Weighing", "Weigh active ingredient accurately.");
        step1.setId(301L);
        RecipeStep step2 = new RecipeStep(2, "Blending", "Blend powders for 15 minutes.");
        step2.setId(302L);
        twoStepRecipe.addStep(step1);
        twoStepRecipe.addStep(step2);

        inProgressOrder.setRecipe(twoStepRecipe);
        inProgressOrder.setRecipeVersion(2);

        // Partial case: only 1 of 2 steps exists
        BatchExecutionStep partialOnlyStep1 = new BatchExecutionStep(
                inProgressBatch, step1, 1, step1.getOperationName(), step1.getInstructions(), ExecutionStepStatus.IN_PROGRESS);
        partialOnlyStep1.setId(2001L);

        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(500L))
                .thenReturn(List.of(partialOnlyStep1));

        InvalidExecutionStepException partialEx = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.initializeExecutionSteps(500L));
        assertTrue(partialEx.getMessage().contains("does not match order recipe"));
        assertEquals(ExecutionStepStatus.IN_PROGRESS, partialOnlyStep1.getStatus());

        // Inconsistent case: step count matches (2), but step 2 snapshot does not match recipe step
        BatchExecutionStep mismatchedStep2 = new BatchExecutionStep(
                inProgressBatch, step2, 2, "Different Operation", step2.getInstructions(), ExecutionStepStatus.PENDING);
        mismatchedStep2.setId(2002L);

        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(500L))
                .thenReturn(List.of(partialOnlyStep1, mismatchedStep2));

        InvalidExecutionStepException mismatchEx = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.initializeExecutionSteps(500L));
        assertTrue(mismatchEx.getMessage().contains("inconsistent with order recipe"));

        verify(executionStepRepository, never()).save(any());
        verify(executionStepRepository, never()).saveAll(any());
        verify(executionStepRepository, never()).delete(any());
        verify(executionStepRepository, never()).deleteAll(any());
        verify(executionStepRepository, never()).deleteAll();
    }

    // =========================================================================
    // 4. Starting Operations Tests
    // =========================================================================

    @Test
    @DisplayName("Start step: locks batch first, starts first PENDING step, populates startedAt, and saves")
    void startFirstStepSucceedsAndAcquiresLockFirst() {
        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findById(1001L)).thenReturn(Optional.of(execStep1));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(500L))
                .thenReturn(List.of(execStep1, execStep2, execStep3));
        when(executionStepRepository.save(any(BatchExecutionStep.class))).thenAnswer(inv -> inv.getArgument(0));

        BatchExecutionStepResponseDto result = executionService.startExecutionStep(500L, 1001L);

        assertEquals(ExecutionStepStatus.IN_PROGRESS, result.getStatus());
        assertNotNull(result.getStartedAt());
        assertEquals(1, result.getStepNumber());

        InOrder inOrder = inOrder(batchRepository, executionStepRepository);
        inOrder.verify(batchRepository).findByIdWithLock(500L);
        inOrder.verify(executionStepRepository).findById(1001L);
        inOrder.verify(executionStepRepository).findByBatchIdOrderByStepNumberAsc(500L);
        inOrder.verify(executionStepRepository).save(execStep1);
    }

    @Test
    @DisplayName("Start step: rejects starting second step before first step is COMPLETED")
    void cannotStartSecondStepBeforeFirstIsCompleted() {
        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findById(1002L)).thenReturn(Optional.of(execStep2));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(500L))
                .thenReturn(List.of(execStep1, execStep2, execStep3));

        InvalidExecutionStepException ex = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.startExecutionStep(500L, 1002L));

        assertTrue(ex.getMessage().contains("before earlier step 1"));
        assertEquals(ExecutionStepStatus.PENDING, execStep2.getStatus());
        verify(executionStepRepository, never()).save(any());
    }

    @Test
    @DisplayName("Start step: rejects starting another step while a step is already IN_PROGRESS")
    void cannotStartStepWhileAnotherStepIsInProgress() {
        execStep1.setStatus(ExecutionStepStatus.IN_PROGRESS);
        execStep1.setStartedAt(LocalDateTime.now());

        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findById(1002L)).thenReturn(Optional.of(execStep2));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(500L))
                .thenReturn(List.of(execStep1, execStep2, execStep3));

        InvalidExecutionStepException ex = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.startExecutionStep(500L, 1002L));

        assertTrue(ex.getMessage().contains("already IN_PROGRESS"));
        verify(executionStepRepository, never()).save(any());
    }

    @Test
    @DisplayName("Start step: rejects restarting a COMPLETED or already IN_PROGRESS step")
    void cannotRestartCompletedOrInProgressStep() {
        execStep1.setStatus(ExecutionStepStatus.COMPLETED);
        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findById(1001L)).thenReturn(Optional.of(execStep1));

        InvalidExecutionStepException completedEx = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.startExecutionStep(500L, 1001L));
        assertTrue(completedEx.getMessage().contains("already COMPLETED"));

        execStep1.setStatus(ExecutionStepStatus.IN_PROGRESS);
        InvalidExecutionStepException inProgressEx = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.startExecutionStep(500L, 1001L));
        assertTrue(inProgressEx.getMessage().contains("already IN_PROGRESS"));

        verify(executionStepRepository, never()).save(any());
    }

    @Test
    @DisplayName("Start step: rejects step belonging to a different batch")
    void startStepRejectsWrongBatchId() {
        Batch otherBatch = new Batch("BAT-OTHER", inProgressOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        otherBatch.setId(599L);
        BatchExecutionStep otherStep = new BatchExecutionStep(otherBatch, recipeStep1, 1, "Dispensing", "Instructions");
        otherStep.setId(2001L);

        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findById(2001L)).thenReturn(Optional.of(otherStep));

        InvalidExecutionStepException ex = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.startExecutionStep(500L, 2001L));

        assertTrue(ex.getMessage().contains("does not belong to batch"));
        verify(executionStepRepository, never()).save(any());
    }

    @Test
    @DisplayName("Start step: throws ResourceNotFoundException when batch or step does not exist")
    void startStepThrowsWhenBatchOrStepNotFound() {
        when(batchRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> executionService.startExecutionStep(999L, 1001L));

        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findById(9999L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> executionService.startExecutionStep(500L, 9999L));
    }

    @Test
    @DisplayName("Start step: rejects when batch is not IN_PROGRESS or parent order is not IN_PROGRESS")
    void startStepRejectsNonInProgressBatchOrOrder() {
        inProgressBatch.setStatus(BatchStatus.PLANNED);
        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));

        InvalidExecutionStepException batchPlannedEx = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.startExecutionStep(500L, 1001L));
        assertTrue(batchPlannedEx.getMessage().contains("Batch must be IN_PROGRESS"));

        inProgressBatch.setStatus(BatchStatus.COMPLETED);
        assertThrows(InvalidExecutionStepException.class,
                () -> executionService.startExecutionStep(500L, 1001L));

        inProgressBatch.setStatus(BatchStatus.IN_PROGRESS);
        inProgressOrder.setStatus(OrderStatus.RELEASED);
        InvalidExecutionStepException orderEx = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.startExecutionStep(500L, 1001L));
        assertTrue(orderEx.getMessage().contains("Order must be IN_PROGRESS"));

        verify(executionStepRepository, never()).save(any());
    }

    // =========================================================================
    // 5. Completing Operations Tests
    // =========================================================================

    @Test
    @DisplayName("Complete step: locks batch first, completes IN_PROGRESS step, saves values, normalized actor, and completedAt")
    void completeInProgressStepSucceedsAndPersistsExecutionData() {
        execStep1.setStatus(ExecutionStepStatus.IN_PROGRESS);
        execStep1.setStartedAt(LocalDateTime.of(2026, 11, 10, 8, 10));

        CompleteExecutionStepRequestDto request = new CompleteExecutionStepRequestDto(
                "  25.0 kg verified  ",
                "  Balance calibrated cleanly  "
        );

        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findById(1001L)).thenReturn(Optional.of(execStep1));
        when(executionStepRepository.save(any(BatchExecutionStep.class))).thenAnswer(inv -> inv.getArgument(0));

        BatchExecutionStepResponseDto result = executionService.completeExecutionStep(500L, 1001L, request);

        assertEquals(ExecutionStepStatus.COMPLETED, result.getStatus());
        assertEquals("25.0 kg verified", result.getActualValue());
        assertEquals("Balance calibrated cleanly", result.getObservations());
        assertEquals("j.doe", result.getExecutedBy());
        assertNotNull(result.getCompletedAt());

        InOrder inOrder = inOrder(batchRepository, executionStepRepository);
        inOrder.verify(batchRepository).findByIdWithLock(500L);
        inOrder.verify(executionStepRepository).findById(1001L);
        inOrder.verify(executionStepRepository).save(execStep1);
    }

    @Test
    @DisplayName("Complete step: allows null or blank optional observations and populates authenticated actor")
    void completeStepAllowsNullOptionalFields() {
        execStep1.setStatus(ExecutionStepStatus.IN_PROGRESS);
        CompleteExecutionStepRequestDto request = new CompleteExecutionStepRequestDto("250.0 kg", "   ");

        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findById(1001L)).thenReturn(Optional.of(execStep1));
        when(executionStepRepository.save(any(BatchExecutionStep.class))).thenAnswer(inv -> inv.getArgument(0));

        BatchExecutionStepResponseDto result = executionService.completeExecutionStep(500L, 1001L, request);

        assertEquals(ExecutionStepStatus.COMPLETED, result.getStatus());
        assertEquals("250.0 kg", result.getActualValue());
        assertNull(result.getObservations());
        assertEquals("j.doe", result.getExecutedBy());
        assertNotNull(result.getCompletedAt());
    }

    @Test
    @DisplayName("Complete step: fails closed when SecurityContext has no authenticated user")
    void completeStepFailsClosedWithoutAuthenticatedActor() {
        SecurityContextHolder.clearContext();

        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> executionService.initializeExecutionSteps(500L));
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> executionService.startExecutionStep(500L, 1001L));
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> executionService.completeExecutionStep(500L, 1001L,
                        new CompleteExecutionStepRequestDto("25.0 kg verified", "Balanced")));
        verifyNoInteractions(batchRepository, executionStepRepository);
    }

    @Test
    @DisplayName("Complete step: normalizes executedBy strictly from authenticated principal case/whitespace")
    void completeStepNormalizesAuthenticatedPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "  Line.Operator.07  ",
                        "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_OPERATOR")
                )
        );
        execStep1.setStatus(ExecutionStepStatus.IN_PROGRESS);
        execStep1.setStartedAt(LocalDateTime.of(2026, 11, 10, 8, 10));

        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findById(1001L)).thenReturn(Optional.of(execStep1));
        when(executionStepRepository.save(any(BatchExecutionStep.class))).thenAnswer(inv -> inv.getArgument(0));

        BatchExecutionStepResponseDto result = executionService.completeExecutionStep(
                500L, 1001L, new CompleteExecutionStepRequestDto("250.0 kg", "Verified"));

        assertEquals("line.operator.07", result.getExecutedBy());
        assertEquals("line.operator.07", execStep1.getExecutedBy());
    }

    @Test
    @DisplayName("Complete step: rejects missing, null, or blank actualValue")
    void completeStepRejectsMissingOrBlankActualValue() {
        execStep1.setStatus(ExecutionStepStatus.IN_PROGRESS);
        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findById(1001L)).thenReturn(Optional.of(execStep1));

        assertThrows(InvalidExecutionStepException.class,
                () -> executionService.completeExecutionStep(500L, 1001L, null));
        assertThrows(InvalidExecutionStepException.class,
                () -> executionService.completeExecutionStep(500L, 1001L, new CompleteExecutionStepRequestDto(null, "obs")));
        assertThrows(InvalidExecutionStepException.class,
                () -> executionService.completeExecutionStep(500L, 1001L, new CompleteExecutionStepRequestDto("   ", "obs")));

        assertEquals(ExecutionStepStatus.IN_PROGRESS, execStep1.getStatus());
        verify(executionStepRepository, never()).save(any());
    }

    @Test
    @DisplayName("Complete step: rejects completing a PENDING step")
    void completeStepRejectsPendingStep() {
        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findById(1001L)).thenReturn(Optional.of(execStep1));

        InvalidExecutionStepException ex = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.completeExecutionStep(500L, 1001L,
                        new CompleteExecutionStepRequestDto("25 RPM", null)));

        assertTrue(ex.getMessage().contains("must be IN_PROGRESS before completion"));
        verify(executionStepRepository, never()).save(any());
    }

    @Test
    @DisplayName("Complete step: completed step is immutable and cannot be modified or completed again")
    void completedStepCannotBeCompletedOrModifiedAgain() {
        LocalDateTime originalCompletedAt = LocalDateTime.of(2026, 11, 10, 9, 0);
        execStep1.setStatus(ExecutionStepStatus.COMPLETED);
        execStep1.setActualValue("249.8 kg");
        execStep1.setObservations("Initial observation");
        execStep1.setExecutedBy("Original Operator");
        execStep1.setCompletedAt(originalCompletedAt);

        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findById(1001L)).thenReturn(Optional.of(execStep1));

        InvalidExecutionStepException ex = assertThrows(InvalidExecutionStepException.class,
                () -> executionService.completeExecutionStep(500L, 1001L,
                        new CompleteExecutionStepRequestDto("999.9 kg", "Tampered")));

        assertTrue(ex.getMessage().contains("already COMPLETED"));
        assertEquals("249.8 kg", execStep1.getActualValue());
        assertEquals("Initial observation", execStep1.getObservations());
        assertEquals("Original Operator", execStep1.getExecutedBy());
        assertEquals(originalCompletedAt, execStep1.getCompletedAt());
        verify(executionStepRepository, never()).save(any());
    }

    @Test
    @DisplayName("Complete step: rejects wrong batch ID, non-IN_PROGRESS batch, or non-IN_PROGRESS order")
    void completeStepRejectsWrongBatchOrNonInProgressParent() {
        Batch otherBatch = new Batch("BAT-OTHER", inProgressOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        otherBatch.setId(599L);
        BatchExecutionStep otherStep = new BatchExecutionStep(otherBatch, recipeStep1, 1, "Dispensing", "Instructions", ExecutionStepStatus.IN_PROGRESS);
        otherStep.setId(2001L);

        when(batchRepository.findByIdWithLock(500L)).thenReturn(Optional.of(inProgressBatch));
        when(executionStepRepository.findById(2001L)).thenReturn(Optional.of(otherStep));

        assertThrows(InvalidExecutionStepException.class,
                () -> executionService.completeExecutionStep(500L, 2001L,
                        new CompleteExecutionStepRequestDto("25 RPM", null)));

        inProgressBatch.setStatus(BatchStatus.COMPLETED);
        assertThrows(InvalidExecutionStepException.class,
                () -> executionService.completeExecutionStep(500L, 1001L,
                        new CompleteExecutionStepRequestDto("25 RPM", null)));

        inProgressBatch.setStatus(BatchStatus.IN_PROGRESS);
        inProgressOrder.setStatus(OrderStatus.PLANNED);
        assertThrows(InvalidExecutionStepException.class,
                () -> executionService.completeExecutionStep(500L, 1001L,
                        new CompleteExecutionStepRequestDto("25 RPM", null)));

        verify(executionStepRepository, never()).save(any());
    }

    // =========================================================================
    // 6. Batch Completion Validation Tests (via ManufacturingOrderService)
    // =========================================================================

    @Test
    @DisplayName("Batch completion: rejects completion when execution records are missing")
    void batchCannotCompleteWhenExecutionRecordsMissing() {
        when(batchRepository.findById(500L)).thenReturn(Optional.of(inProgressBatch));
        when(orderRepository.findByIdWithLock(100L)).thenReturn(Optional.of(inProgressOrder));
        when(executionStepRepository.existsByBatchId(500L)).thenReturn(false);

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(500L,
                        new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("248.0000"))));

        assertTrue(ex.getMessage().contains("all production execution steps must be completed first"));
        assertEquals(BatchStatus.IN_PROGRESS, inProgressBatch.getStatus());
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("Batch completion: rejects completion when any execution step is PENDING or IN_PROGRESS")
    void batchCannotCompleteWhenAnyStepPendingOrInProgress() {
        when(batchRepository.findById(500L)).thenReturn(Optional.of(inProgressBatch));
        when(orderRepository.findByIdWithLock(100L)).thenReturn(Optional.of(inProgressOrder));
        when(executionStepRepository.existsByBatchId(500L)).thenReturn(true);
        when(executionStepRepository.countByBatchIdAndStatusNot(500L, ExecutionStepStatus.COMPLETED)).thenReturn(2L);

        InvalidOrderException pendingEx = assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(500L,
                        new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("248.0000"))));
        assertTrue(pendingEx.getMessage().contains("all production execution steps must be completed first"));

        when(executionStepRepository.countByBatchIdAndStatusNot(500L, ExecutionStepStatus.COMPLETED)).thenReturn(1L);
        InvalidOrderException inProgressEx = assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(500L,
                        new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("248.0000"))));
        assertTrue(inProgressEx.getMessage().contains("all production execution steps must be completed first"));

        assertEquals(BatchStatus.IN_PROGRESS, inProgressBatch.getStatus());
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("Batch completion: allows batch completion when all steps are COMPLETED and preserves yield and order lifecycle")
    void batchCompletesWhenAllStepsCompletedPreservingOrderLifecycle() {
        Batch secondBatch = new Batch(
                "BAT-2026-0001-02", inProgressOrder, new BigDecimal("250.0000"),
                "kg", BatchStatus.COMPLETED, LocalDate.of(2026, 11, 10), null
        );
        secondBatch.setId(505L);
        secondBatch.setActualYield(new BigDecimal("249.0000"));

        when(batchRepository.findById(500L)).thenReturn(Optional.of(inProgressBatch));
        when(orderRepository.findByIdWithLock(100L)).thenReturn(Optional.of(inProgressOrder));
        when(executionStepRepository.existsByBatchId(500L)).thenReturn(true);
        when(executionStepRepository.countByBatchIdAndStatusNot(500L, ExecutionStepStatus.COMPLETED)).thenReturn(0L);
        when(batchRepository.save(any(Batch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchRepository.findByOrderId(100L)).thenReturn(List.of(inProgressBatch, secondBatch));

        BatchResponseDto response = orderService.updateBatchStatus(500L,
                new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("248.5000")));

        assertEquals(BatchStatus.COMPLETED, response.getStatus());
        assertEquals(new BigDecimal("248.5000"), response.getActualYield());
        assertNotNull(response.getEndTime());
        assertEquals(OrderStatus.COMPLETED, inProgressOrder.getStatus());
        assertNotNull(inProgressOrder.getActualEndDate());
        verify(orderRepository).save(inProgressOrder);
    }

    @Test
    @DisplayName("Batch completion: preserves existing Module 3 non-positive actualYield rejection")
    void batchCompletionStillRejectsNonPositiveActualYield() {
        when(batchRepository.findById(500L)).thenReturn(Optional.of(inProgressBatch));
        when(orderRepository.findByIdWithLock(100L)).thenReturn(Optional.of(inProgressOrder));

        assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(500L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, null)));
        assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(500L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, BigDecimal.ZERO)));
    }
}

