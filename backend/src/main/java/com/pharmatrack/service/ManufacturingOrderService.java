package com.pharmatrack.service;

import com.pharmatrack.dto.BatchResponseDto;
import com.pharmatrack.dto.BatchStatusUpdateDto;
import com.pharmatrack.dto.OrderRequestDto;
import com.pharmatrack.dto.OrderResponseDto;
import com.pharmatrack.dto.OrderStatusUpdateDto;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchExecutionStep;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.exception.InvalidOrderException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.BatchExecutionStepRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.util.AppTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Unified service implementation managing Manufacturing Order & Batch lifecycles,
 * validation, batch generation, numbering, status transitions, and transactional
 * append-only audit trail recording attributed to the authenticated SecurityContext user.
 */
@Service
@Transactional
public class ManufacturingOrderService {

    public static final String ENTITY_MANUFACTURING_ORDER = "MANUFACTURING_ORDER";
    public static final String ENTITY_BATCH = "BATCH";
    public static final ZoneId APP_ZONE_ID = AppTime.APP_ZONE_ID;

    private final ManufacturingOrderRepository orderRepository;
    private final BatchRepository batchRepository;
    private final ProductRepository productRepository;
    private final RecipeRepository recipeRepository;
    private final BatchExecutionStepRepository executionStepRepository;
    private final AuditTrailService auditTrailService;
    private final AuthenticatedActorResolver authenticatedActorResolver;

    public ManufacturingOrderService(ManufacturingOrderRepository orderRepository,
                                     BatchRepository batchRepository,
                                     ProductRepository productRepository,
                                     RecipeRepository recipeRepository,
                                     BatchExecutionStepRepository executionStepRepository,
                                     AuditTrailService auditTrailService) {
        this(orderRepository, batchRepository, productRepository, recipeRepository,
                executionStepRepository, auditTrailService, new AuthenticatedActorResolver());
    }

    @Autowired
    public ManufacturingOrderService(ManufacturingOrderRepository orderRepository,
                                     BatchRepository batchRepository,
                                     ProductRepository productRepository,
                                     RecipeRepository recipeRepository,
                                     BatchExecutionStepRepository executionStepRepository,
                                     AuditTrailService auditTrailService,
                                     AuthenticatedActorResolver authenticatedActorResolver) {
        this.orderRepository = orderRepository;
        this.batchRepository = batchRepository;
        this.productRepository = productRepository;
        this.recipeRepository = recipeRepository;
        this.executionStepRepository = executionStepRepository;
        this.auditTrailService = auditTrailService;
        this.authenticatedActorResolver = authenticatedActorResolver != null
                ? authenticatedActorResolver
                : new AuthenticatedActorResolver();
    }

    // -------------------------------------------------------------------------
    // Order Creation
    // -------------------------------------------------------------------------

    /**
     * Creates a new Manufacturing Order and automatically generates its child batches,
     * recording append-only {@code MANUFACTURING_ORDER_CREATED} and {@code BATCH_CREATED}
     * audit entries attributed to the authenticated user transactionally.
     *
     * @param requestDto order creation payload
     * @return response DTO representing the created order with generated batches
     */
    public OrderResponseDto createOrder(OrderRequestDto requestDto) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null) {
            throw new InvalidOrderException("Order request payload cannot be null");
        }

        // Validate planned dates in application timezone (Asia/Kolkata)
        LocalDate today = LocalDate.now(APP_ZONE_ID);
        if (requestDto.getPlannedStartDate() != null
                && requestDto.getPlannedStartDate().isBefore(today)) {
            throw new InvalidOrderException("Planned start date cannot be earlier than today");
        }
        if (requestDto.getPlannedStartDate() != null && requestDto.getPlannedEndDate() != null
                && requestDto.getPlannedEndDate().isBefore(requestDto.getPlannedStartDate())) {
            throw new InvalidOrderException("Planned end date cannot be earlier than planned start date");
        }

        // 1. Validate Product
        if (requestDto.getProductId() == null) {
            throw new InvalidOrderException("Product ID is mandatory");
        }
        Product product = productRepository.findById(requestDto.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + requestDto.getProductId()));

        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw new InvalidOrderException("Cannot create manufacturing order for inactive product (code: " + product.getProductCode() + ")");
        }

        // 2. Validate Recipe
        if (requestDto.getRecipeId() == null) {
            throw new InvalidOrderException("Recipe ID is mandatory");
        }
        Recipe recipe = recipeRepository.findById(requestDto.getRecipeId())
                .orElseThrow(() -> new ResourceNotFoundException("Recipe not found with id: " + requestDto.getRecipeId()));

        if (recipe.getStatus() != RecipeStatus.APPROVED) {
            throw new InvalidOrderException("Cannot create manufacturing order with recipe in status " + recipe.getStatus() + ". Only APPROVED recipes can be used.");
        }

        if (!recipe.getProduct().getId().equals(product.getId())) {
            throw new InvalidOrderException("Recipe '" + recipe.getRecipeCode() + "' does not belong to product '" + product.getProductCode() + "'");
        }

        if (recipe.getBatchSize() == null || recipe.getBatchSize().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidOrderException("Recipe batch size must be greater than zero");
        }

        // 3. Validate Unit Matching
        if (requestDto.getQuantityUnit() == null || requestDto.getQuantityUnit().trim().isEmpty()) {
            throw new InvalidOrderException("Quantity unit is mandatory");
        }
        if (recipe.getBatchUnit() == null || !recipe.getBatchUnit().trim().equalsIgnoreCase(requestDto.getQuantityUnit().trim())) {
            throw new InvalidOrderException("Order quantity unit '" + requestDto.getQuantityUnit() + "' does not match recipe batch unit '" + recipe.getBatchUnit() + "'");
        }

        // 4. Validate Planned Quantity & Exact Multiple
        if (requestDto.getPlannedQuantity() == null || requestDto.getPlannedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidOrderException("Planned quantity must be greater than zero");
        }

        BigDecimal[] divRem = requestDto.getPlannedQuantity().divideAndRemainder(recipe.getBatchSize());
        if (divRem[1].compareTo(BigDecimal.ZERO) != 0) {
            throw new InvalidOrderException("Planned quantity (" + requestDto.getPlannedQuantity() + " " + recipe.getBatchUnit()
                    + ") must be an exact positive multiple of recipe batch size (" + recipe.getBatchSize() + " " + recipe.getBatchUnit() + ")");
        }

        int targetBatchCount;
        try {
            targetBatchCount = divRem[0].intValueExact();
        } catch (ArithmeticException ex) {
            throw new InvalidOrderException("Planned quantity results in an excessively large batch count (" + divRem[0]
                    + ") that exceeds allowable integer limits", ex);
        }

        if (targetBatchCount <= 0) {
            throw new InvalidOrderException("Planned quantity must yield at least one batch");
        }

        // 5. Initial Persist with Unique Temporary Order Number to Obtain Database-Generated ID
        String tempOrderNumber = "TEMP-" + UUID.randomUUID();
        ManufacturingOrder order = new ManufacturingOrder();
        order.setOrderNumber(tempOrderNumber);
        order.setProduct(product);
        order.setRecipe(recipe);
        order.setRecipeVersion(recipe.getVersion());
        order.setPlannedQuantity(requestDto.getPlannedQuantity());
        order.setQuantityUnit(requestDto.getQuantityUnit().trim());
        order.setTargetBatchCount(targetBatchCount);
        order.setStatus(OrderStatus.PLANNED);
        order.setPlannedStartDate(requestDto.getPlannedStartDate());
        order.setPlannedEndDate(requestDto.getPlannedEndDate());
        order.setNotes(requestDto.getNotes());

        ManufacturingOrder savedOrder = orderRepository.saveAndFlush(order);

        // 6. Assign Final Formatted Order Number and Automatically Generate Child Batches
        int currentYear = AppTime.nowDate().getYear();
        String finalOrderNumber = String.format("MO-%d-%04d", currentYear, savedOrder.getId());
        savedOrder.setOrderNumber(finalOrderNumber);

        for (int i = 1; i <= targetBatchCount; i++) {
            String batchNumber = String.format("BAT-%d-%04d-%02d", currentYear, savedOrder.getId(), i);
            Batch batch = new Batch(
                    batchNumber,
                    savedOrder,
                    recipe.getBatchSize(),
                    recipe.getBatchUnit(),
                    BatchStatus.PLANNED,
                    null, // manufactureDate recorded when batch execution starts
                    null  // expiryDate remains null as product currently lacks shelf-life
            );
            savedOrder.addBatch(batch);
        }

        ManufacturingOrder finalOrder = orderRepository.saveAndFlush(savedOrder);
        LocalDateTime actionTime = LocalDateTime.now(APP_ZONE_ID);
        List<Batch> createdBatches = sortBatchesByNumber(finalOrder.getBatches());
        Map<String, Object> orderAfterSnapshot = buildOrderSnapshot(finalOrder, createdBatches);

        auditTrailService.recordAudit(
                ENTITY_MANUFACTURING_ORDER,
                finalOrder.getId(),
                finalOrder.getOrderNumber(),
                null,
                "MANUFACTURING_ORDER_CREATED",
                resolvedActor,
                actionTime,
                truncateAuditReason(
                        "Created manufacturing order " + finalOrder.getOrderNumber()
                                + " for product " + product.getProductCode()
                                + " (recipe " + recipe.getRecipeCode()
                                + ", " + targetBatchCount + " batch(es))",
                        2000
                ),
                null,
                orderAfterSnapshot
        );

        for (Batch createdBatch : createdBatches) {
            auditTrailService.recordAudit(
                    ENTITY_BATCH,
                    createdBatch.getId(),
                    createdBatch.getBatchNumber(),
                    createdBatch.getId(),
                    "BATCH_CREATED",
                    resolvedActor,
                    actionTime,
                    truncateAuditReason(
                            "Created batch " + createdBatch.getBatchNumber()
                                    + " for manufacturing order " + finalOrder.getOrderNumber(),
                            2000
                    ),
                    null,
                    buildBatchSnapshot(createdBatch)
            );
        }

        return mapToOrderResponseDto(finalOrder);
    }

    // -------------------------------------------------------------------------
    // Order Queries
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public OrderResponseDto getOrderById(Long id) {
        ManufacturingOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Manufacturing order not found with id: " + id));
        return mapToOrderResponseDto(order);
    }

    @Transactional(readOnly = true)
    public OrderResponseDto getOrderByOrderNumber(String orderNumber) {
        ManufacturingOrder order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Manufacturing order not found with order number: " + orderNumber));
        return mapToOrderResponseDto(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponseDto> getAllOrders() {
        return orderRepository.findAll().stream()
                .map(this::mapToOrderResponseDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponseDto> getOrdersByStatus(OrderStatus status) {
        if (status == null) {
            throw new InvalidOrderException("Order status cannot be null");
        }
        return orderRepository.findByStatus(status).stream()
                .map(this::mapToOrderResponseDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponseDto> getOrdersByProductId(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Product not found with id: " + productId);
        }
        return orderRepository.findByProductId(productId).stream()
                .map(this::mapToOrderResponseDto)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Order Status Transitions
    // -------------------------------------------------------------------------

    /**
     * Updates a manufacturing order's lifecycle status, recording transactional append-only audit entries
     * ({@code MANUFACTURING_ORDER_RELEASED}, {@code MANUFACTURING_ORDER_UPDATED},
     * {@code MANUFACTURING_ORDER_CANCELLED}, {@code MANUFACTURING_ORDER_COMPLETED},
     * and cascading {@code BATCH_CANCELLED}) attributed to the authenticated user.
     */
    public OrderResponseDto updateOrderStatus(Long orderId, OrderStatusUpdateDto updateDto) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();

        if (updateDto == null || updateDto.getStatus() == null) {
            throw new InvalidOrderException("Target order status cannot be null");
        }

        ManufacturingOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Manufacturing order not found with id: " + orderId));

        OrderStatus currentStatus = order.getStatus();
        OrderStatus newStatus = updateDto.getStatus();

        if (currentStatus == newStatus) {
            return mapToOrderResponseDto(order);
        }

        if (currentStatus == OrderStatus.COMPLETED) {
            throw new InvalidOrderException("Cannot change status of a COMPLETED order");
        }

        if (currentStatus == OrderStatus.CANCELLED) {
            throw new InvalidOrderException("Cannot change status of a CANCELLED order");
        }

        // Validate transitions completely before capturing before-snapshots or mutating entities
        if (currentStatus == OrderStatus.PLANNED) {
            if (newStatus == OrderStatus.RELEASED) {
                Product orderProduct = order.getProduct();
                if (orderProduct == null || orderProduct.getStatus() != ProductStatus.ACTIVE) {
                    String productCode = orderProduct != null ? orderProduct.getProductCode() : "unknown";
                    throw new InvalidOrderException("Cannot release manufacturing order " + order.getOrderNumber()
                            + " because product " + productCode + " is not ACTIVE");
                }
                Recipe orderRecipe = order.getRecipe();
                if (orderRecipe == null || orderRecipe.getStatus() != RecipeStatus.APPROVED) {
                    String recipeCode = orderRecipe != null ? orderRecipe.getRecipeCode() : "unknown";
                    String recipeStatus = orderRecipe != null && orderRecipe.getStatus() != null
                            ? orderRecipe.getStatus().name()
                            : "unknown";
                    throw new InvalidOrderException("Cannot release manufacturing order " + order.getOrderNumber()
                            + " because recipe " + recipeCode + " is in " + recipeStatus
                            + " status. Only APPROVED recipes can be released.");
                }
                List<Batch> orderBatches = order.getBatches();
                if (orderBatches != null && !orderBatches.isEmpty()
                        && areAllBatchesCancelled(orderBatches, null, null)) {
                    throw new InvalidOrderException("Cannot release manufacturing order " + order.getOrderNumber()
                            + " because all child batches are CANCELLED");
                }
            } else if (newStatus != OrderStatus.CANCELLED) {
                throw new InvalidOrderException("Cannot transition order directly from PLANNED to " + newStatus);
            }
        } else if (currentStatus == OrderStatus.RELEASED) {
            if (newStatus == OrderStatus.CANCELLED) {
                List<Batch> orderBatches = order.getBatches();
                if (!hasOnlyPlannedOrCancelledBatches(orderBatches, null, null)) {
                    throw new InvalidOrderException("Cannot cancel order " + order.getOrderNumber()
                            + " because one or more batches have already started or completed");
                }
            } else if (newStatus == OrderStatus.IN_PROGRESS) {
                List<Batch> orderBatches = order.getBatches();
                if (orderBatches != null && !orderBatches.isEmpty()
                        && areAllBatchesCancelled(orderBatches, null, null)) {
                    throw new InvalidOrderException("Cannot start manufacturing order " + order.getOrderNumber()
                            + " because all child batches are CANCELLED");
                }
            } else {
                throw new InvalidOrderException("Invalid order status transition from RELEASED to " + newStatus);
            }
        } else if (currentStatus == OrderStatus.IN_PROGRESS) {
            if (newStatus == OrderStatus.CANCELLED) {
                throw new InvalidOrderException("Cannot cancel manufacturing order " + order.getOrderNumber()
                        + " once production has started");
            } else if (newStatus == OrderStatus.COMPLETED) {
                List<Batch> orderBatches = order.getBatches();
                if (orderBatches == null || orderBatches.isEmpty()) {
                    throw new InvalidOrderException("Cannot mark order " + order.getOrderNumber()
                            + " as COMPLETED without any completed batches");
                }
                if (areAllBatchesCancelled(orderBatches, null, null)) {
                    throw new InvalidOrderException("Cannot mark order " + order.getOrderNumber()
                            + " as COMPLETED because all child batches are CANCELLED");
                }
                if (!isOrderReadyForCompletion(orderBatches, null, null)) {
                    throw new InvalidOrderException("Cannot mark order " + order.getOrderNumber()
                            + " as COMPLETED while batches remain PLANNED or IN_PROGRESS; all non-cancelled batches must be COMPLETED");
                }
            } else {
                throw new InvalidOrderException("Invalid order status transition from IN_PROGRESS to " + newStatus);
            }
        } else {
            throw new InvalidOrderException("Invalid order status transition from " + currentStatus + " to " + newStatus);
        }

        List<Batch> preTransitionBatches = sortBatchesByNumber(order.getBatches());
        Map<String, Object> orderBeforeSnapshot = buildOrderSnapshot(order, preTransitionBatches);
        List<Batch> cascadedBatches = new ArrayList<>();
        Map<Batch, Map<String, Object>> batchBeforeSnapshots = new IdentityHashMap<>();

        String orderAction;
        String orderReason;

        if (currentStatus == OrderStatus.PLANNED && newStatus == OrderStatus.RELEASED) {
            order.setStatus(OrderStatus.RELEASED);
            orderAction = "MANUFACTURING_ORDER_RELEASED";
            orderReason = "Released manufacturing order " + order.getOrderNumber() + " for production";
        } else if ((currentStatus == OrderStatus.PLANNED || currentStatus == OrderStatus.RELEASED)
                && newStatus == OrderStatus.CANCELLED) {
            for (Batch childBatch : preTransitionBatches) {
                if (childBatch.getStatus() == BatchStatus.PLANNED) {
                    batchBeforeSnapshots.put(childBatch, buildBatchSnapshot(childBatch));
                    cascadedBatches.add(childBatch);
                }
            }
            order.setStatus(OrderStatus.CANCELLED);
            for (Batch childBatch : cascadedBatches) {
                childBatch.setStatus(BatchStatus.CANCELLED);
            }
            orderAction = "MANUFACTURING_ORDER_CANCELLED";
            orderReason = cascadedBatches.isEmpty()
                    ? "Cancelled manufacturing order " + order.getOrderNumber()
                    : "Cancelled manufacturing order " + order.getOrderNumber()
                            + " (cascaded cancellation to " + cascadedBatches.size() + " planned batch(es))";
        } else if (currentStatus == OrderStatus.RELEASED && newStatus == OrderStatus.IN_PROGRESS) {
            order.setStatus(OrderStatus.IN_PROGRESS);
            if (order.getActualStartDate() == null) {
                order.setActualStartDate(AppTime.nowDateTime());
            }
            orderAction = "MANUFACTURING_ORDER_UPDATED";
            orderReason = "Started manufacturing order " + order.getOrderNumber()
                    + " (status updated from RELEASED to IN_PROGRESS)";
        } else {
            order.setStatus(OrderStatus.COMPLETED);
            if (order.getActualEndDate() == null) {
                order.setActualEndDate(AppTime.nowDateTime());
            }
            orderAction = "MANUFACTURING_ORDER_COMPLETED";
            orderReason = "Completed manufacturing order " + order.getOrderNumber();
        }

        ManufacturingOrder persistedOrder = orderRepository.save(order);
        orderRepository.flush();
        ManufacturingOrder effectiveOrder = persistedOrder != null ? persistedOrder : order;

        LocalDateTime actionTime = LocalDateTime.now(APP_ZONE_ID);
        Map<String, Object> orderAfterSnapshot = buildOrderSnapshot(
                effectiveOrder,
                resolveOrderBatchesForSnapshot(effectiveOrder, effectiveOrder.getBatches())
        );

        auditTrailService.recordAudit(
                ENTITY_MANUFACTURING_ORDER,
                effectiveOrder.getId(),
                effectiveOrder.getOrderNumber(),
                null,
                orderAction,
                resolvedActor,
                actionTime,
                truncateAuditReason(orderReason, 2000),
                orderBeforeSnapshot,
                orderAfterSnapshot
        );

        for (Batch cancelledBatch : cascadedBatches) {
            auditTrailService.recordAudit(
                    ENTITY_BATCH,
                    cancelledBatch.getId(),
                    cancelledBatch.getBatchNumber(),
                    cancelledBatch.getId(),
                    "BATCH_CANCELLED",
                    resolvedActor,
                    actionTime,
                    truncateAuditReason(
                            "Cancelled batch " + cancelledBatch.getBatchNumber()
                                    + " due to cancellation of manufacturing order " + effectiveOrder.getOrderNumber(),
                            2000
                    ),
                    batchBeforeSnapshots.get(cancelledBatch),
                    buildBatchSnapshot(cancelledBatch)
            );
        }

        return mapToOrderResponseDto(effectiveOrder);
    }

    // -------------------------------------------------------------------------
    // Batch Queries
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BatchResponseDto getBatchById(Long batchId) {
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batchId));
        return mapToBatchResponseDto(batch);
    }

    @Transactional(readOnly = true)
    public BatchResponseDto getBatchByBatchNumber(String batchNumber) {
        Batch batch = batchRepository.findByBatchNumber(batchNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with batch number: " + batchNumber));
        return mapToBatchResponseDto(batch);
    }

    @Transactional(readOnly = true)
    public List<BatchResponseDto> getBatchesByOrderId(Long orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new ResourceNotFoundException("Manufacturing order not found with id: " + orderId);
        }
        return batchRepository.findByOrderId(orderId).stream()
                .map(this::mapToBatchResponseDto)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Batch Status Transitions
    // -------------------------------------------------------------------------

    /**
     * Updates a batch's lifecycle status, enforcing defense-in-depth transition authorization
     * and recording append-only audit entries ({@code BATCH_STARTED}, {@code BATCH_COMPLETED},
     * {@code BATCH_CANCELLED}, plus auto-triggered {@code MANUFACTURING_ORDER_UPDATED},
     * {@code MANUFACTURING_ORDER_CANCELLED}, and {@code MANUFACTURING_ORDER_COMPLETED} parent
     * transitions) attributed to the authenticated user.
     */
    public BatchResponseDto updateBatchStatus(Long batchId, BatchStatusUpdateDto updateDto) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();

        if (updateDto == null || updateDto.getStatus() == null) {
            throw new InvalidOrderException("Target batch status cannot be null");
        }
        enforceBatchTransitionAuthorization(updateDto.getStatus());

        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batchId));

        // Lock parent order with pessimistic write lock to protect against concurrent updates
        Long orderId = batch.getOrder().getId();
        ManufacturingOrder order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Manufacturing order not found with id: " + orderId));

        BatchStatus currentStatus = batch.getStatus();
        BatchStatus newStatus = updateDto.getStatus();

        if (currentStatus == newStatus) {
            return mapToBatchResponseDto(batch);
        }

        if (currentStatus == BatchStatus.COMPLETED) {
            throw new InvalidOrderException("Cannot change status of a COMPLETED batch");
        }

        if (currentStatus == BatchStatus.CANCELLED) {
            throw new InvalidOrderException("Cannot change status of a CANCELLED batch");
        }

        if (currentStatus == BatchStatus.PLANNED) {
            if (newStatus == BatchStatus.IN_PROGRESS) {
                if (order.getStatus() != OrderStatus.RELEASED && order.getStatus() != OrderStatus.IN_PROGRESS) {
                    throw new InvalidOrderException("Cannot start batch " + batch.getBatchNumber()
                            + " because parent order " + order.getOrderNumber() + " is in " + order.getStatus()
                            + " status. Order must be RELEASED or IN_PROGRESS.");
                }

                BatchExecutionStepInitializer.InitializationPlan stepPlan =
                        BatchExecutionStepInitializer.prepareExecutionSteps(
                                batch,
                                order,
                                executionStepRepository,
                                "start batch",
                                InvalidOrderException::new
                        );

                Map<String, Object> batchBeforeSnapshot = buildBatchSnapshot(batch);
                boolean orderStatusTransition = order.getStatus() == OrderStatus.RELEASED;
                boolean orderNeedsUpdate = orderStatusTransition || order.getActualStartDate() == null;
                List<Batch> preStartBatches = orderNeedsUpdate
                        ? mergeUpdatedBatchIntoList(resolveOrderBatchesForSnapshot(order, null), batch)
                        : List.of();
                Map<String, Object> orderBeforeSnapshot = orderNeedsUpdate
                        ? buildOrderSnapshot(order, preStartBatches)
                        : null;

                batch.setStatus(BatchStatus.IN_PROGRESS);
                if (batch.getStartTime() == null) {
                    batch.setStartTime(AppTime.nowDateTime());
                }
                if (batch.getManufactureDate() == null) {
                    batch.setManufactureDate(AppTime.nowDate());
                }

                // Auto-advance parent order to IN_PROGRESS if RELEASED
                if (orderStatusTransition) {
                    order.setStatus(OrderStatus.IN_PROGRESS);
                }
                if (order.getActualStartDate() == null) {
                    order.setActualStartDate(AppTime.nowDateTime());
                }
                ManufacturingOrder persistedOrder = orderRepository.save(order);
                orderRepository.flush();
                ManufacturingOrder effectiveOrder = persistedOrder != null ? persistedOrder : order;

                Batch persistedBatch = batchRepository.save(batch);
                batchRepository.flush();
                Batch effectiveBatch = persistedBatch != null ? persistedBatch : batch;

                if (stepPlan.hasStepsToCreate()) {
                    executionStepRepository.saveAll(stepPlan.getStepsToCreate());
                }

                LocalDateTime actionTime = LocalDateTime.now(APP_ZONE_ID);
                auditTrailService.recordAudit(
                        ENTITY_BATCH,
                        effectiveBatch.getId(),
                        effectiveBatch.getBatchNumber(),
                        effectiveBatch.getId(),
                        "BATCH_STARTED",
                        resolvedActor,
                        actionTime,
                        truncateAuditReason(
                                "Started batch " + effectiveBatch.getBatchNumber()
                                        + " under manufacturing order " + effectiveOrder.getOrderNumber(),
                                2000
                        ),
                        batchBeforeSnapshot,
                        buildBatchSnapshot(effectiveBatch)
                );

                if (orderNeedsUpdate) {
                    Map<String, Object> orderAfterSnapshot = buildOrderSnapshot(
                            effectiveOrder,
                            mergeUpdatedBatchIntoList(preStartBatches, effectiveBatch)
                    );
                    auditTrailService.recordAudit(
                            ENTITY_MANUFACTURING_ORDER,
                            effectiveOrder.getId(),
                            effectiveOrder.getOrderNumber(),
                            null,
                            "MANUFACTURING_ORDER_UPDATED",
                            resolvedActor,
                            actionTime,
                            truncateAuditReason(
                                    orderStatusTransition
                                            ? "Automatically updated manufacturing order " + effectiveOrder.getOrderNumber()
                                                    + " to IN_PROGRESS upon start of batch " + effectiveBatch.getBatchNumber()
                                            : "Updated manufacturing order " + effectiveOrder.getOrderNumber()
                                                    + " actual start timestamp upon start of batch " + effectiveBatch.getBatchNumber(),
                                    2000
                            ),
                            orderBeforeSnapshot,
                            orderAfterSnapshot
                    );
                }

                return mapToBatchResponseDto(effectiveBatch);
            } else if (newStatus == BatchStatus.CANCELLED) {
                // Individual batch cancellation permitted only while parent order is PLANNED
                if (order.getStatus() != OrderStatus.PLANNED) {
                    throw new InvalidOrderException("Individual batch cancellation is permitted only while its parent order is PLANNED. Current order status: " + order.getStatus());
                }

                List<Batch> orderBatches = batchRepository.findByOrderId(order.getId());
                boolean autoCancelOrder = order.getStatus() != OrderStatus.CANCELLED
                        && areAllBatchesCancelled(orderBatches, batch, BatchStatus.CANCELLED);

                Map<String, Object> batchBeforeSnapshot = buildBatchSnapshot(batch);
                Map<String, Object> orderBeforeSnapshot = autoCancelOrder
                        ? buildOrderSnapshot(order, mergeUpdatedBatchIntoList(resolveOrderBatchesForSnapshot(order, orderBatches), batch))
                        : null;

                batch.setStatus(BatchStatus.CANCELLED);
                Batch persistedBatch = batchRepository.save(batch);
                batchRepository.flush();
                Batch effectiveBatch = persistedBatch != null ? persistedBatch : batch;

                ManufacturingOrder effectiveOrder = order;
                if (autoCancelOrder) {
                    order.setStatus(OrderStatus.CANCELLED);
                    ManufacturingOrder persistedOrder = orderRepository.save(order);
                    orderRepository.flush();
                    effectiveOrder = persistedOrder != null ? persistedOrder : order;
                }

                LocalDateTime actionTime = LocalDateTime.now(APP_ZONE_ID);
                auditTrailService.recordAudit(
                        ENTITY_BATCH,
                        effectiveBatch.getId(),
                        effectiveBatch.getBatchNumber(),
                        effectiveBatch.getId(),
                        "BATCH_CANCELLED",
                        resolvedActor,
                        actionTime,
                        truncateAuditReason(
                                "Cancelled batch " + effectiveBatch.getBatchNumber()
                                        + " under manufacturing order " + effectiveOrder.getOrderNumber(),
                                2000
                        ),
                        batchBeforeSnapshot,
                        buildBatchSnapshot(effectiveBatch)
                );

                if (autoCancelOrder) {
                    Map<String, Object> orderAfterSnapshot = buildOrderSnapshot(
                            effectiveOrder,
                            mergeUpdatedBatchIntoList(resolveOrderBatchesForSnapshot(effectiveOrder, orderBatches), effectiveBatch)
                    );
                    auditTrailService.recordAudit(
                            ENTITY_MANUFACTURING_ORDER,
                            effectiveOrder.getId(),
                            effectiveOrder.getOrderNumber(),
                            null,
                            "MANUFACTURING_ORDER_CANCELLED",
                            resolvedActor,
                            actionTime,
                            truncateAuditReason(
                                    "Automatically cancelled manufacturing order " + effectiveOrder.getOrderNumber()
                                            + " because all child batches are CANCELLED",
                                    2000
                            ),
                            orderBeforeSnapshot,
                            orderAfterSnapshot
                    );
                }

                return mapToBatchResponseDto(effectiveBatch);
            } else {
                throw new InvalidOrderException("Cannot transition batch directly from PLANNED to " + newStatus);
            }
        } else if (currentStatus == BatchStatus.IN_PROGRESS) {
            if (newStatus == BatchStatus.COMPLETED) {
                if (updateDto.getActualYield() == null || updateDto.getActualYield().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new InvalidOrderException("Actual yield is mandatory and must be greater than zero when completing a batch");
                }
                if (!executionStepRepository.existsByBatchId(batch.getId())) {
                    throw new InvalidOrderException("Cannot complete batch " + batch.getBatchNumber()
                            + " because all production execution steps must be completed first (no execution records exist)");
                }
                long incompleteStepCount = executionStepRepository.countByBatchIdAndStatusNot(
                        batch.getId(), ExecutionStepStatus.COMPLETED);
                if (incompleteStepCount > 0) {
                    throw new InvalidOrderException("Cannot complete batch " + batch.getBatchNumber()
                            + " because all production execution steps must be completed first ("
                            + incompleteStepCount + " step(s) still PENDING or IN_PROGRESS)");
                }

                List<Batch> orderBatches = batchRepository.findByOrderId(order.getId());
                boolean autoCompleteOrder = order.getStatus() != OrderStatus.COMPLETED
                        && isOrderReadyForCompletion(orderBatches, batch, BatchStatus.COMPLETED);
                Map<String, Object> batchBeforeSnapshot = buildBatchSnapshot(batch);
                Map<String, Object> orderBeforeSnapshot = autoCompleteOrder
                        ? buildOrderSnapshot(order, mergeUpdatedBatchIntoList(resolveOrderBatchesForSnapshot(order, orderBatches), batch))
                        : null;

                batch.setStatus(BatchStatus.COMPLETED);
                if (batch.getQaStatus() == null) {
                    batch.setQaStatus(BatchQualityStatus.PENDING_QA);
                }
                batch.setActualYield(updateDto.getActualYield());
                if (batch.getEndTime() == null) {
                    batch.setEndTime(AppTime.nowDateTime());
                }

                Batch persistedBatch = batchRepository.save(batch);
                batchRepository.flush();
                Batch effectiveBatch = persistedBatch != null ? persistedBatch : batch;

                // Auto-complete parent order once at least one batch is COMPLETED and zero PLANNED/IN_PROGRESS batches remain
                // (Cancelled batches do not prevent parent order completion.)
                ManufacturingOrder effectiveOrder = order;
                if (autoCompleteOrder) {
                    order.setStatus(OrderStatus.COMPLETED);
                    if (order.getActualEndDate() == null) {
                        order.setActualEndDate(AppTime.nowDateTime());
                    }
                    ManufacturingOrder persistedOrder = orderRepository.save(order);
                    orderRepository.flush();
                    effectiveOrder = persistedOrder != null ? persistedOrder : order;
                }

                LocalDateTime actionTime = LocalDateTime.now(APP_ZONE_ID);
                BigDecimal normalizedYield = normalizeDecimal(effectiveBatch.getActualYield());
                String yieldSummary = (normalizedYield != null ? normalizedYield.toPlainString() : "0")
                        + (effectiveBatch.getBatchUnit() != null ? " " + effectiveBatch.getBatchUnit() : "");
                auditTrailService.recordAudit(
                        ENTITY_BATCH,
                        effectiveBatch.getId(),
                        effectiveBatch.getBatchNumber(),
                        effectiveBatch.getId(),
                        "BATCH_COMPLETED",
                        resolvedActor,
                        actionTime,
                        truncateAuditReason(
                                "Completed batch " + effectiveBatch.getBatchNumber()
                                        + " with actual yield " + yieldSummary,
                                2000
                        ),
                        batchBeforeSnapshot,
                        buildBatchSnapshot(effectiveBatch)
                );

                if (autoCompleteOrder) {
                    Map<String, Object> orderAfterSnapshot = buildOrderSnapshot(
                            effectiveOrder,
                            mergeUpdatedBatchIntoList(resolveOrderBatchesForSnapshot(effectiveOrder, orderBatches), effectiveBatch)
                    );
                    auditTrailService.recordAudit(
                            ENTITY_MANUFACTURING_ORDER,
                            effectiveOrder.getId(),
                            effectiveOrder.getOrderNumber(),
                            null,
                            "MANUFACTURING_ORDER_COMPLETED",
                            resolvedActor,
                            actionTime,
                            truncateAuditReason(
                                    "Automatically completed manufacturing order " + effectiveOrder.getOrderNumber()
                                            + " upon completion of batch " + effectiveBatch.getBatchNumber(),
                                    2000
                            ),
                            orderBeforeSnapshot,
                            orderAfterSnapshot
                    );
                }

                return mapToBatchResponseDto(effectiveBatch);
            } else if (newStatus == BatchStatus.CANCELLED) {
                throw new InvalidOrderException("Cannot cancel batch " + batch.getBatchNumber() + " because it is already IN_PROGRESS");
            } else {
                throw new InvalidOrderException("Invalid batch status transition from IN_PROGRESS to " + newStatus);
            }
        }

        throw new InvalidOrderException("Invalid batch status transition from " + currentStatus + " to " + newStatus);
    }

    // -------------------------------------------------------------------------
    // Lifecycle Rule Helpers
    // -------------------------------------------------------------------------

    private BatchStatus effectiveBatchStatus(Batch candidate, Batch updatedBatch, BatchStatus updatedStatus) {
        if (updatedBatch != null && (candidate == updatedBatch
                || (updatedBatch.getId() != null && Objects.equals(candidate.getId(), updatedBatch.getId())))) {
            return updatedStatus;
        }
        return candidate.getStatus();
    }

    private boolean areAllBatchesCancelled(List<Batch> batches, Batch updatedBatch, BatchStatus updatedStatus) {
        if (batches == null || batches.isEmpty()) {
            return false;
        }
        for (Batch candidate : batches) {
            if (effectiveBatchStatus(candidate, updatedBatch, updatedStatus) != BatchStatus.CANCELLED) {
                return false;
            }
        }
        return true;
    }

    private boolean hasOnlyPlannedOrCancelledBatches(List<Batch> batches, Batch updatedBatch, BatchStatus updatedStatus) {
        if (batches == null) {
            return false;
        }
        for (Batch candidate : batches) {
            BatchStatus status = effectiveBatchStatus(candidate, updatedBatch, updatedStatus);
            if (status != BatchStatus.PLANNED && status != BatchStatus.CANCELLED) {
                return false;
            }
        }
        return true;
    }

    private boolean isOrderReadyForCompletion(List<Batch> batches, Batch updatedBatch, BatchStatus updatedStatus) {
        if (batches == null || batches.isEmpty()) {
            return false;
        }
        boolean hasCompletedBatch = false;
        for (Batch candidate : batches) {
            BatchStatus status = effectiveBatchStatus(candidate, updatedBatch, updatedStatus);
            if (status == BatchStatus.COMPLETED) {
                hasCompletedBatch = true;
            } else if (status != BatchStatus.CANCELLED) {
                return false;
            }
        }
        return hasCompletedBatch;
    }

    // -------------------------------------------------------------------------
    // Security, Audit Trail & Snapshot Helpers
    // -------------------------------------------------------------------------

    private void enforceBatchTransitionAuthorization(BatchStatus targetStatus) {
        if (authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR")) {
            return;
        }
        if (targetStatus == BatchStatus.CANCELLED && authenticatedActorResolver.hasAnyRole("ADMIN")) {
            return;
        }
        if ((targetStatus == BatchStatus.IN_PROGRESS || targetStatus == BatchStatus.COMPLETED)
                && authenticatedActorResolver.hasAnyRole("OPERATOR")) {
            return;
        }
        throw new AccessDeniedException(
                "Current user role is not authorized to transition a batch to status " + targetStatus
        );
    }

    private List<Batch> resolveOrderBatchesForSnapshot(ManufacturingOrder order, List<Batch> loadedBatches) {
        if (loadedBatches != null && !loadedBatches.isEmpty()) {
            return loadedBatches;
        }
        if (order != null && order.getBatches() != null && !order.getBatches().isEmpty()) {
            return order.getBatches();
        }
        if (order != null && order.getId() != null) {
            List<Batch> queried = batchRepository.findByOrderId(order.getId());
            if (queried != null && !queried.isEmpty()) {
                return queried;
            }
        }
        return List.of();
    }

    private List<Batch> mergeUpdatedBatchIntoList(List<Batch> batches, Batch updatedBatch) {
        List<Batch> source = batches != null ? batches : List.of();
        if (updatedBatch == null) {
            return source;
        }
        if (source.isEmpty()) {
            return List.of(updatedBatch);
        }
        List<Batch> merged = new ArrayList<>(source.size());
        boolean replaced = false;
        for (Batch candidate : source) {
            if (candidate == updatedBatch
                    || (updatedBatch.getId() != null && candidate != null
                    && Objects.equals(updatedBatch.getId(), candidate.getId()))) {
                merged.add(updatedBatch);
                replaced = true;
            } else {
                merged.add(candidate);
            }
        }
        if (!replaced) {
            merged.add(updatedBatch);
        }
        return merged;
    }

    private List<Batch> sortBatchesByNumber(List<Batch> batches) {
        if (batches == null || batches.isEmpty()) {
            return List.of();
        }
        return batches.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Batch::getBatchNumber, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Batch::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    private Map<String, Object> buildOrderSnapshot(ManufacturingOrder order, List<Batch> batches) {
        Product product = order != null ? order.getProduct() : null;
        Recipe recipe = order != null ? order.getRecipe() : null;
        List<Batch> sortedBatches = sortBatchesByNumber(batches);

        int plannedCount = 0;
        int inProgressCount = 0;
        int completedCount = 0;
        int cancelledCount = 0;
        List<Map<String, Object>> batchSummaries = new ArrayList<>(sortedBatches.size());
        for (Batch batch : sortedBatches) {
            BatchStatus batchStatus = batch.getStatus();
            if (batchStatus == BatchStatus.PLANNED) {
                plannedCount++;
            } else if (batchStatus == BatchStatus.IN_PROGRESS) {
                inProgressCount++;
            } else if (batchStatus == BatchStatus.COMPLETED) {
                completedCount++;
            } else if (batchStatus == BatchStatus.CANCELLED) {
                cancelledCount++;
            }
            Map<String, Object> batchItem = new LinkedHashMap<>();
            batchItem.put("batchId", batch.getId());
            batchItem.put("batchNumber", batch.getBatchNumber());
            batchItem.put("batchSize", normalizeDecimal(batch.getBatchSize()));
            batchItem.put("batchUnit", batch.getBatchUnit());
            batchItem.put("status", batchStatus != null ? batchStatus.name() : null);
            batchItem.put("qaStatus", batch.getQaStatus() != null ? batch.getQaStatus().name() : null);
            batchItem.put("actualYield", normalizeDecimal(batch.getActualYield()));
            batchSummaries.add(batchItem);
        }

        Map<String, Object> batchSummary = new LinkedHashMap<>();
        batchSummary.put("totalBatches", batchSummaries.size());
        batchSummary.put("plannedBatches", plannedCount);
        batchSummary.put("inProgressBatches", inProgressCount);
        batchSummary.put("completedBatches", completedCount);
        batchSummary.put("cancelledBatches", cancelledCount);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("orderId", order != null ? order.getId() : null);
        snapshot.put("orderNumber", order != null ? order.getOrderNumber() : null);
        snapshot.put("productId", product != null ? product.getId() : null);
        snapshot.put("productCode", product != null ? product.getProductCode() : null);
        snapshot.put("productName", product != null ? product.getProductName() : null);
        snapshot.put("recipeId", recipe != null ? recipe.getId() : null);
        snapshot.put("recipeCode", recipe != null ? recipe.getRecipeCode() : null);
        snapshot.put("recipeVersion", order != null ? order.getRecipeVersion() : null);
        snapshot.put("standardBatchSize", recipe != null ? normalizeDecimal(recipe.getBatchSize()) : null);
        snapshot.put("plannedQuantity", order != null ? normalizeDecimal(order.getPlannedQuantity()) : null);
        snapshot.put("quantityUnit", order != null ? order.getQuantityUnit() : null);
        snapshot.put("targetBatchCount", order != null ? order.getTargetBatchCount() : null);
        snapshot.put("status", order != null && order.getStatus() != null ? order.getStatus().name() : null);
        snapshot.put("plannedStartDate", order != null ? toIsoString(order.getPlannedStartDate()) : null);
        snapshot.put("plannedEndDate", order != null ? toIsoString(order.getPlannedEndDate()) : null);
        snapshot.put("actualStartDate", order != null ? toIsoString(order.getActualStartDate()) : null);
        snapshot.put("actualEndDate", order != null ? toIsoString(order.getActualEndDate()) : null);
        snapshot.put("notes", order != null ? order.getNotes() : null);
        snapshot.put("batchSummary", batchSummary);
        snapshot.put("batches", batchSummaries);
        return snapshot;
    }

    private Map<String, Object> buildBatchSnapshot(Batch batch) {
        ManufacturingOrder order = batch != null ? batch.getOrder() : null;
        Product product = order != null ? order.getProduct() : null;
        Recipe recipe = order != null ? order.getRecipe() : null;

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("batchId", batch != null ? batch.getId() : null);
        snapshot.put("batchNumber", batch != null ? batch.getBatchNumber() : null);
        snapshot.put("orderId", order != null ? order.getId() : null);
        snapshot.put("orderNumber", order != null ? order.getOrderNumber() : null);
        snapshot.put("productId", product != null ? product.getId() : null);
        snapshot.put("productCode", product != null ? product.getProductCode() : null);
        snapshot.put("productName", product != null ? product.getProductName() : null);
        snapshot.put("recipeId", recipe != null ? recipe.getId() : null);
        snapshot.put("recipeCode", recipe != null ? recipe.getRecipeCode() : null);
        snapshot.put("recipeVersion", order != null ? order.getRecipeVersion() : null);
        snapshot.put("batchSize", batch != null ? normalizeDecimal(batch.getBatchSize()) : null);
        snapshot.put("batchUnit", batch != null ? batch.getBatchUnit() : null);
        snapshot.put("status", batch != null && batch.getStatus() != null ? batch.getStatus().name() : null);
        snapshot.put("qaStatus", batch != null && batch.getQaStatus() != null ? batch.getQaStatus().name() : null);
        snapshot.put("actualYield", batch != null ? normalizeDecimal(batch.getActualYield()) : null);
        snapshot.put("manufactureDate", batch != null ? toIsoString(batch.getManufactureDate()) : null);
        snapshot.put("expiryDate", batch != null ? toIsoString(batch.getExpiryDate()) : null);
        snapshot.put("startTime", batch != null ? toIsoString(batch.getStartTime()) : null);
        snapshot.put("endTime", batch != null ? toIsoString(batch.getEndTime()) : null);
        return snapshot;
    }

    private BigDecimal normalizeDecimal(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.stripTrailingZeros().toPlainString());
    }

    private String toIsoString(LocalDate date) {
        return date != null ? date.toString() : null;
    }

    private String toIsoString(LocalDateTime dateTime) {
        return AppTime.formatIsoTimestamp(dateTime);
    }

    private String truncateAuditReason(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    // -------------------------------------------------------------------------
    // DTO Mappings
    // -------------------------------------------------------------------------

    private OrderResponseDto mapToOrderResponseDto(ManufacturingOrder order) {
        List<BatchResponseDto> batchDtos = order.getBatches() != null ?
                order.getBatches().stream().map(this::mapToBatchResponseDto).toList() : new ArrayList<>();

        return new OrderResponseDto(
                order.getId(),
                order.getOrderNumber(),
                order.getProduct().getId(),
                order.getProduct().getProductCode(),
                order.getProduct().getProductName(),
                order.getRecipe().getId(),
                order.getRecipe().getRecipeCode(),
                order.getRecipeVersion(),
                order.getPlannedQuantity(),
                order.getQuantityUnit(),
                order.getTargetBatchCount(),
                order.getStatus(),
                order.getPlannedStartDate(),
                order.getPlannedEndDate(),
                order.getActualStartDate(),
                order.getActualEndDate(),
                order.getNotes(),
                batchDtos,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private BatchResponseDto mapToBatchResponseDto(Batch batch) {
        BatchResponseDto dto = new BatchResponseDto(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getOrder().getId(),
                batch.getOrder().getOrderNumber(),
                batch.getBatchSize(),
                batch.getBatchUnit(),
                batch.getActualYield(),
                batch.getStatus(),
                batch.getQaStatus(),
                batch.getManufactureDate(),
                batch.getExpiryDate(),
                batch.getStartTime(),
                batch.getEndTime(),
                batch.getCreatedAt(),
                batch.getUpdatedAt()
        );
        dto.setQrCode(batch.getQrCode() != null ? batch.getQrCode() : "QR-PT-" + batch.getBatchNumber());
        return dto;
    }
}

