package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.BatchResponseDto;
import com.pharmatrack.dto.BatchStatusUpdateDto;
import com.pharmatrack.dto.OrderRequestDto;
import com.pharmatrack.dto.OrderResponseDto;
import com.pharmatrack.dto.OrderStatusUpdateDto;
import com.pharmatrack.entity.AuditLog;
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
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.exception.InvalidOrderException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchExecutionStepRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.ManufacturingOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
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
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ManufacturingOrderService} verifying business logic,
 * order creation, batch generation, numbering, status transitions, lifecycle rules,
 * and append-only audit trail recording.
 */
@ExtendWith(MockitoExtension.class)
class ManufacturingOrderServiceTest {

    @Mock
    private ManufacturingOrderRepository orderRepository;

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private RecipeRepository recipeRepository;

    @Mock
    private BatchExecutionStepRepository executionStepRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    private ObjectMapper objectMapper;
    private AuditTrailService auditTrailService;
    private ManufacturingOrderService orderService;

    private Product activeProduct;
    private Product inactiveProduct;
    private Recipe approvedRecipe;
    private Recipe draftRecipe;
    private ManufacturingOrder sampleOrder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        auditTrailService = new AuditTrailService(auditLogRepository, objectMapper);
        orderService = new ManufacturingOrderService(
                orderRepository,
                batchRepository,
                productRepository,
                recipeRepository,
                executionStepRepository,
                auditTrailService
        );

        lenient().when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> {
            AuditLog log = invocation.getArgument(0);
            if (log.getId() == null) {
                log.setId(1L);
            }
            return log;
        });

        activeProduct = new Product(
                "AMOX-250",
                "Amoxicillin Capsules",
                "250 mg",
                "Capsule",
                "Broad-spectrum antibiotic",
                ProductStatus.ACTIVE
        );
        activeProduct.setId(1L);

        inactiveProduct = new Product(
                "DISC-100",
                "Discontinued Drug",
                "100 mg",
                "Tablet",
                "Inactive product line",
                ProductStatus.INACTIVE
        );
        inactiveProduct.setId(2L);

        approvedRecipe = new Recipe(
                "RCP-AMOX-250-V3",
                activeProduct,
                3,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Approved manufacturing recipe for Amoxicillin"
        );
        approvedRecipe.setId(10L);
        RecipeStep step1 = new RecipeStep(1, "Dispensing", "Dispense API and excipients accurately.");
        step1.setId(201L);
        RecipeStep step2 = new RecipeStep(2, "Dry Blending", "Blend dry powders in V-blender for 20 minutes.");
        step2.setId(202L);
        approvedRecipe.addStep(step1);
        approvedRecipe.addStep(step2);

        draftRecipe = new Recipe(
                "RCP-AMOX-250-V4",
                activeProduct,
                4,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.DRAFT,
                "Draft experimental recipe"
        );
        draftRecipe.setId(11L);

        sampleOrder = new ManufacturingOrder(
                "MO-2026-0001",
                activeProduct,
                approvedRecipe,
                3,
                new BigDecimal("500.0000"),
                "kg",
                2,
                OrderStatus.PLANNED,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 5),
                "Production batch run"
        );
        sampleOrder.setId(1L);
        authenticateAs("planner.maria", "PRODUCTION_SUPERVISOR");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, String... roles) {
        String[] authorities = new String[roles.length];
        for (int i = 0; i < roles.length; i++) {
            authorities[i] = roles[i].startsWith("ROLE_") ? roles[i] : "ROLE_" + roles[i];
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a", AuthorityUtils.createAuthorityList(authorities))
        );
    }

    private ManufacturingOrder assignSyntheticIds(ManufacturingOrder order, long orderId) {
        if (order.getId() == null) {
            order.setId(orderId);
        }
        long nextBatchId = 100L;
        if (order.getBatches() != null) {
            for (Batch batch : order.getBatches()) {
                if (batch.getId() == null) {
                    batch.setId(nextBatchId++);
                }
            }
        }
        return order;
    }

    // -------------------------------------------------------------------------
    // 1. Order Creation - Positive Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should successfully create order, snapshot recipe version, and generate planned batches")
    void shouldCreateOrderAndGenerateBatchesSuccessfully() {
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("500.0000"),
                "kg",
                today,
                today.plusDays(4),
                "Amoxicillin 500kg run"
        );

        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));

        // Mock first saveAndFlush to simulate DB ID generation, and second to return finalized order
        when(orderRepository.saveAndFlush(any(ManufacturingOrder.class)))
                .thenAnswer(invocation -> assignSyntheticIds(invocation.getArgument(0), 1L));

        OrderResponseDto response = orderService.createOrder(requestDto);

        assertNotNull(response);
        assertEquals(1L, response.getId());
        assertEquals("MO-2026-0001", response.getOrderNumber());
        assertEquals(1L, response.getProductId());
        assertEquals("AMOX-250", response.getProductCode());
        assertEquals("Amoxicillin Capsules", response.getProductName());
        assertEquals(10L, response.getRecipeId());
        assertEquals("RCP-AMOX-250-V3", response.getRecipeCode());
        assertEquals(3, response.getRecipeVersion(), "Recipe version must be snapshotted from the approved recipe");
        assertEquals(new BigDecimal("500.0000"), response.getPlannedQuantity());
        assertEquals("kg", response.getQuantityUnit());
        assertEquals(2, response.getTargetBatchCount(), "Target batch count must be 500 / 250 = 2");
        assertEquals(OrderStatus.PLANNED, response.getStatus());

        // Verify child batches
        assertEquals(2, response.getBatches().size());
        BatchResponseDto batch1 = response.getBatches().get(0);
        BatchResponseDto batch2 = response.getBatches().get(1);

        assertEquals("BAT-2026-0001-01", batch1.getBatchNumber());
        assertEquals(new BigDecimal("250.0000"), batch1.getBatchSize());
        assertEquals("kg", batch1.getBatchUnit());
        assertEquals(BatchStatus.PLANNED, batch1.getStatus());
        assertNull(batch1.getActualYield());

        assertEquals("BAT-2026-0001-02", batch2.getBatchNumber());
        assertEquals(new BigDecimal("250.0000"), batch2.getBatchSize());
        assertEquals("kg", batch2.getBatchUnit());
        assertEquals(BatchStatus.PLANNED, batch2.getStatus());

        verify(orderRepository, times(2)).saveAndFlush(any(ManufacturingOrder.class));
    }

    @Test
    @DisplayName("Should generate order number that expands beyond four digits when ID is large")
    void shouldExpandOrderNumberBeyondFourDigitsWhenIdIsLarge() {
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("250.0000"),
                "kg",
                null,
                null,
                null
        );

        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));
        when(orderRepository.saveAndFlush(any(ManufacturingOrder.class)))
                .thenAnswer(invocation -> assignSyntheticIds(invocation.getArgument(0), 12345L));

        OrderResponseDto response = orderService.createOrder(requestDto);

        assertNotNull(response);
        assertEquals("MO-2026-12345", response.getOrderNumber());
        assertEquals(1, response.getBatches().size());
        assertEquals("BAT-2026-12345-01", response.getBatches().get(0).getBatchNumber());
    }

    @Test
    @DisplayName("Should match quantity unit case-insensitively with trimming")
    void shouldMatchQuantityUnitCaseInsensitiveWithTrimming() {
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("250.0000"),
                "  KG  ",
                null,
                null,
                null
        );

        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));
        when(orderRepository.saveAndFlush(any(ManufacturingOrder.class)))
                .thenAnswer(invocation -> assignSyntheticIds(invocation.getArgument(0), 2L));

        OrderResponseDto response = orderService.createOrder(requestDto);

        assertNotNull(response);
        assertEquals("KG", response.getQuantityUnit());
    }

    // -------------------------------------------------------------------------
    // 2. Order Creation - Negative & Validation Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should throw when order request payload is null")
    void shouldThrowWhenRequestPayloadIsNull() {
        assertThrows(InvalidOrderException.class, () -> orderService.createOrder(null));
    }

    @Test
    @DisplayName("Should throw when plannedStartDate is yesterday in Asia/Kolkata")
    void shouldThrowWhenPlannedStartDateIsYesterday() {
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("250.0000"),
                "kg",
                today.minusDays(1),
                today.plusDays(3),
                "Past start date"
        );

        InvalidOrderException ex = assertThrows(InvalidOrderException.class, () -> orderService.createOrder(requestDto));
        assertEquals("Planned start date cannot be earlier than today", ex.getMessage());
    }

    @Test
    @DisplayName("Should create order when plannedStartDate is today in Asia/Kolkata")
    void shouldCreateOrderWhenPlannedStartDateIsToday() {
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("250.0000"),
                "kg",
                today,
                today.plusDays(2),
                null
        );

        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));
        when(orderRepository.saveAndFlush(any(ManufacturingOrder.class)))
                .thenAnswer(invocation -> assignSyntheticIds(invocation.getArgument(0), 7L));

        OrderResponseDto response = orderService.createOrder(requestDto);
        assertEquals(today, response.getPlannedStartDate());
        assertEquals(today.plusDays(2), response.getPlannedEndDate());
    }

    @Test
    @DisplayName("Should create order when plannedStartDate is tomorrow in Asia/Kolkata")
    void shouldCreateOrderWhenPlannedStartDateIsTomorrow() {
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("250.0000"),
                "kg",
                today.plusDays(1),
                today.plusDays(3),
                null
        );

        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));
        when(orderRepository.saveAndFlush(any(ManufacturingOrder.class)))
                .thenAnswer(invocation -> assignSyntheticIds(invocation.getArgument(0), 8L));

        OrderResponseDto response = orderService.createOrder(requestDto);
        assertEquals(today.plusDays(1), response.getPlannedStartDate());
        assertEquals(today.plusDays(3), response.getPlannedEndDate());
    }

    @Test
    @DisplayName("Should create order when plannedStartDate is empty (null)")
    void shouldCreateOrderWhenPlannedStartDateIsNull() {
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("250.0000"),
                "kg",
                null,
                null,
                null
        );

        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));
        when(orderRepository.saveAndFlush(any(ManufacturingOrder.class)))
                .thenAnswer(invocation -> assignSyntheticIds(invocation.getArgument(0), 9L));

        OrderResponseDto response = orderService.createOrder(requestDto);
        assertNull(response.getPlannedStartDate());
        assertNull(response.getPlannedEndDate());
    }

    @Test
    @DisplayName("Should throw when plannedEndDate is earlier than plannedStartDate")
    void shouldThrowWhenPlannedEndDateIsEarlierThanPlannedStartDate() {
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("250.0000"),
                "kg",
                today.plusDays(5),
                today.plusDays(1),
                "Invalid dates"
        );

        InvalidOrderException ex = assertThrows(InvalidOrderException.class, () -> orderService.createOrder(requestDto));
        assertTrue(ex.getMessage().contains("Planned end date cannot be earlier than planned start date"));
    }

    @Test
    @DisplayName("Should throw when end date is 1 day before start date")
    void shouldThrowWhenPlannedEndDateNov14IsEarlierThanNov15() {
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("250.0000"),
                "kg",
                today.plusDays(5),
                today.plusDays(4),
                null
        );

        InvalidOrderException ex = assertThrows(InvalidOrderException.class, () -> orderService.createOrder(requestDto));
        assertEquals("Planned end date cannot be earlier than planned start date", ex.getMessage());
    }

    @Test
    @DisplayName("Should create order when start and end dates are same day")
    void shouldCreateOrderWhenSameStartAndEndDate() {
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("250.0000"),
                "kg",
                today.plusDays(5),
                today.plusDays(5),
                null
        );

        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));
        when(orderRepository.saveAndFlush(any(ManufacturingOrder.class)))
                .thenAnswer(invocation -> assignSyntheticIds(invocation.getArgument(0), 3L));

        OrderResponseDto response = orderService.createOrder(requestDto);
        assertEquals(today.plusDays(5), response.getPlannedStartDate());
        assertEquals(today.plusDays(5), response.getPlannedEndDate());
    }

    @Test
    @DisplayName("Should create order when end date is after start date")
    void shouldCreateOrderWhenEndDateAfterStartDate() {
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("250.0000"),
                "kg",
                today.plusDays(5),
                today.plusDays(6),
                null
        );

        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));
        when(orderRepository.saveAndFlush(any(ManufacturingOrder.class)))
                .thenAnswer(invocation -> assignSyntheticIds(invocation.getArgument(0), 4L));

        OrderResponseDto response = orderService.createOrder(requestDto);
        assertEquals(today.plusDays(5), response.getPlannedStartDate());
        assertEquals(today.plusDays(6), response.getPlannedEndDate());
    }

    @Test
    @DisplayName("Should create order when only plannedStartDate is provided")
    void shouldCreateOrderWhenOnlyStartDateProvided() {
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("250.0000"),
                "kg",
                today.plusDays(5),
                null,
                null
        );

        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));
        when(orderRepository.saveAndFlush(any(ManufacturingOrder.class)))
                .thenAnswer(invocation -> assignSyntheticIds(invocation.getArgument(0), 5L));

        OrderResponseDto response = orderService.createOrder(requestDto);
        assertEquals(today.plusDays(5), response.getPlannedStartDate());
        assertNull(response.getPlannedEndDate());
    }

    @Test
    @DisplayName("Should create order when only plannedEndDate is provided")
    void shouldCreateOrderWhenOnlyEndDateProvided() {
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("250.0000"),
                "kg",
                null,
                today.plusDays(4),
                null
        );

        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));
        when(orderRepository.saveAndFlush(any(ManufacturingOrder.class)))
                .thenAnswer(invocation -> assignSyntheticIds(invocation.getArgument(0), 6L));

        OrderResponseDto response = orderService.createOrder(requestDto);
        assertNull(response.getPlannedStartDate());
        assertEquals(today.plusDays(4), response.getPlannedEndDate());
    }

    @Test
    @DisplayName("Should throw when productId is null")
    void shouldThrowWhenProductIdIsNull() {
        OrderRequestDto requestDto = new OrderRequestDto(null, 10L, new BigDecimal("250.0000"), "kg", null, null, null);
        assertThrows(InvalidOrderException.class, () -> orderService.createOrder(requestDto));
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when product does not exist")
    void shouldThrowWhenProductDoesNotExist() {
        OrderRequestDto requestDto = new OrderRequestDto(999L, 10L, new BigDecimal("250.0000"), "kg", null, null, null);
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.createOrder(requestDto));
    }

    @Test
    @DisplayName("Should throw InvalidOrderException when product is INACTIVE")
    void shouldThrowWhenProductIsInactive() {
        OrderRequestDto requestDto = new OrderRequestDto(2L, 10L, new BigDecimal("250.0000"), "kg", null, null, null);
        when(productRepository.findById(2L)).thenReturn(Optional.of(inactiveProduct));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class, () -> orderService.createOrder(requestDto));
        assertTrue(ex.getMessage().contains("Cannot create manufacturing order for inactive product"));
    }

    @Test
    @DisplayName("Should throw when recipeId is null")
    void shouldThrowWhenRecipeIdIsNull() {
        OrderRequestDto requestDto = new OrderRequestDto(1L, null, new BigDecimal("250.0000"), "kg", null, null, null);
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));

        assertThrows(InvalidOrderException.class, () -> orderService.createOrder(requestDto));
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when recipe does not exist")
    void shouldThrowWhenRecipeDoesNotExist() {
        OrderRequestDto requestDto = new OrderRequestDto(1L, 999L, new BigDecimal("250.0000"), "kg", null, null, null);
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.createOrder(requestDto));
    }

    @Test
    @DisplayName("Should throw InvalidOrderException when recipe is not APPROVED")
    void shouldThrowWhenRecipeIsNotApproved() {
        OrderRequestDto requestDto = new OrderRequestDto(1L, 11L, new BigDecimal("250.0000"), "kg", null, null, null);
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(11L)).thenReturn(Optional.of(draftRecipe));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class, () -> orderService.createOrder(requestDto));
        assertTrue(ex.getMessage().contains("Only APPROVED recipes can be used"));
    }

    @Test
    @DisplayName("Should throw when recipe does not belong to the selected product")
    void shouldThrowWhenRecipeDoesNotBelongToProduct() {
        Product otherProduct = new Product("PARA-500", "Paracetamol", "500 mg", "Tablet", "Pain relief", ProductStatus.ACTIVE);
        otherProduct.setId(99L);
        Recipe mismatchedRecipe = new Recipe("RCP-PARA-V1", otherProduct, 1, new BigDecimal("250.0000"), "kg", RecipeStatus.APPROVED, "Paracetamol recipe");
        mismatchedRecipe.setId(50L);

        OrderRequestDto requestDto = new OrderRequestDto(1L, 50L, new BigDecimal("250.0000"), "kg", null, null, null);
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(50L)).thenReturn(Optional.of(mismatchedRecipe));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class, () -> orderService.createOrder(requestDto));
        assertTrue(ex.getMessage().contains("does not belong to product"));
    }

    @Test
    @DisplayName("Should throw when quantity unit is blank or missing")
    void shouldThrowWhenQuantityUnitIsBlank() {
        OrderRequestDto requestDto = new OrderRequestDto(1L, 10L, new BigDecimal("250.0000"), "   ", null, null, null);
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));

        assertThrows(InvalidOrderException.class, () -> orderService.createOrder(requestDto));
    }

    @Test
    @DisplayName("Should throw when quantity unit does not match recipe batch unit")
    void shouldThrowWhenQuantityUnitDoesNotMatchRecipe() {
        OrderRequestDto requestDto = new OrderRequestDto(1L, 10L, new BigDecimal("250.0000"), "liters", null, null, null);
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class, () -> orderService.createOrder(requestDto));
        assertTrue(ex.getMessage().contains("does not match recipe batch unit"));
    }

    @Test
    @DisplayName("Should throw when planned quantity is null or non-positive")
    void shouldThrowWhenPlannedQuantityIsNonPositive() {
        OrderRequestDto requestDto = new OrderRequestDto(1L, 10L, BigDecimal.ZERO, "kg", null, null, null);
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));

        assertThrows(InvalidOrderException.class, () -> orderService.createOrder(requestDto));
    }

    @Test
    @DisplayName("Should throw when planned quantity is not an exact positive multiple of recipe batch size")
    void shouldThrowWhenPlannedQuantityIsNotExactMultiple() {
        // Recipe batch size is 250 kg; planned quantity 600 kg has remainder 100 kg
        OrderRequestDto requestDto = new OrderRequestDto(1L, 10L, new BigDecimal("600.0000"), "kg", null, null, null);
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class, () -> orderService.createOrder(requestDto));
        assertTrue(ex.getMessage().contains("must be an exact positive multiple of recipe batch size"));
    }

    @Test
    @DisplayName("Should throw InvalidOrderException when planned quantity causes target batch count integer overflow")
    void shouldThrowWhenPlannedQuantityCausesBatchCountOverflow() {
        BigDecimal hugeQuantity = BigDecimal.valueOf(Long.MAX_VALUE).multiply(new BigDecimal("250.0000"));
        OrderRequestDto requestDto = new OrderRequestDto(1L, 10L, hugeQuantity, "kg", null, null, null);
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class, () -> orderService.createOrder(requestDto));
        assertTrue(ex.getMessage().contains("exceeds allowable integer limits"));
    }

    // -------------------------------------------------------------------------
    // 3. Order Queries Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should find order by ID when exists")
    void shouldGetOrderByIdWhenExists() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        OrderResponseDto response = orderService.getOrderById(1L);

        assertNotNull(response);
        assertEquals("MO-2026-0001", response.getOrderNumber());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when order not found by ID")
    void shouldThrowWhenOrderNotFoundById() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.getOrderById(999L));
    }

    @Test
    @DisplayName("Should find order by order number when exists")
    void shouldGetOrderByOrderNumberWhenExists() {
        when(orderRepository.findByOrderNumber("MO-2026-0001")).thenReturn(Optional.of(sampleOrder));

        OrderResponseDto response = orderService.getOrderByOrderNumber("MO-2026-0001");

        assertNotNull(response);
        assertEquals(1L, response.getId());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when order not found by order number")
    void shouldThrowWhenOrderNotFoundByOrderNumber() {
        when(orderRepository.findByOrderNumber("MO-UNKNOWN")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.getOrderByOrderNumber("MO-UNKNOWN"));
    }

    @Test
    @DisplayName("Should get all orders")
    void shouldGetAllOrders() {
        when(orderRepository.findAll()).thenReturn(List.of(sampleOrder));

        List<OrderResponseDto> orders = orderService.getAllOrders();

        assertEquals(1, orders.size());
        assertEquals("MO-2026-0001", orders.get(0).getOrderNumber());
    }

    @Test
    @DisplayName("Should get orders by status")
    void shouldGetOrdersByStatus() {
        when(orderRepository.findByStatus(OrderStatus.PLANNED)).thenReturn(List.of(sampleOrder));

        List<OrderResponseDto> orders = orderService.getOrdersByStatus(OrderStatus.PLANNED);

        assertEquals(1, orders.size());
        assertEquals(OrderStatus.PLANNED, orders.get(0).getStatus());
    }

    @Test
    @DisplayName("Should throw when getOrdersByStatus with null status")
    void shouldThrowWhenGetOrdersByStatusWithNull() {
        assertThrows(InvalidOrderException.class, () -> orderService.getOrdersByStatus(null));
    }

    @Test
    @DisplayName("Should get orders by productId")
    void shouldGetOrdersByProductId() {
        when(productRepository.existsById(1L)).thenReturn(true);
        when(orderRepository.findByProductId(1L)).thenReturn(List.of(sampleOrder));

        List<OrderResponseDto> orders = orderService.getOrdersByProductId(1L);

        assertEquals(1, orders.size());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when getting orders for non-existent product")
    void shouldThrowWhenGetOrdersByProductIdWithNonExistentProduct() {
        when(productRepository.existsById(999L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> orderService.getOrdersByProductId(999L));
    }

    // -------------------------------------------------------------------------
    // 4. Order Status Transition Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should release a PLANNED order")
    void shouldReleasePlannedOrder() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(ManufacturingOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponseDto response = orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.RELEASED));

        assertEquals(OrderStatus.RELEASED, response.getStatus());
    }

    @Test
    @DisplayName("Should cancel a PLANNED order and cascade cancellation to all child batches")
    void shouldCancelPlannedOrderAndAllBatches() {
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        b1.setId(101L);
        Batch b2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        b2.setId(102L);
        sampleOrder.addBatch(b1);
        sampleOrder.addBatch(b2);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(ManufacturingOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponseDto response = orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.CANCELLED));

        assertEquals(OrderStatus.CANCELLED, response.getStatus());
        assertEquals(BatchStatus.CANCELLED, b1.getStatus());
        assertEquals(BatchStatus.CANCELLED, b2.getStatus());
    }

    @Test
    @DisplayName("Should cancel a RELEASED order if batches are PLANNED or CANCELLED")
    void shouldCancelReleasedOrderWhenMixedPlannedAndCancelledBatches() {
        sampleOrder.setStatus(OrderStatus.RELEASED);
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        b1.setId(101L);
        Batch b2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        b2.setId(102L);
        sampleOrder.addBatch(b1);
        sampleOrder.addBatch(b2);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(ManufacturingOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponseDto response = orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.CANCELLED));

        assertEquals(OrderStatus.CANCELLED, response.getStatus());
        assertEquals(BatchStatus.CANCELLED, b1.getStatus());
        assertEquals(BatchStatus.CANCELLED, b2.getStatus());
    }

    @Test
    @DisplayName("Should cancel a RELEASED order if all child batches are still PLANNED")
    void shouldCancelReleasedOrderIfAllBatchesArePlanned() {
        sampleOrder.setStatus(OrderStatus.RELEASED);
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        b1.setId(101L);
        sampleOrder.addBatch(b1);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(ManufacturingOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponseDto response = orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.CANCELLED));

        assertEquals(OrderStatus.CANCELLED, response.getStatus());
        assertEquals(BatchStatus.CANCELLED, b1.getStatus());
    }

    @Test
    @DisplayName("Should throw when attempting to cancel a RELEASED order if a batch is IN_PROGRESS")
    void shouldThrowWhenCancellingReleasedOrderWithStartedBatch() {
        sampleOrder.setStatus(OrderStatus.RELEASED);
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        sampleOrder.addBatch(b1);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.CANCELLED)));
        assertTrue(ex.getMessage().contains("Cannot cancel order"));
    }

    @Test
    @DisplayName("Should throw when attempting to cancel an IN_PROGRESS order")
    void shouldThrowWhenCancellingInProgressOrder() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.CANCELLED)));
        assertTrue(ex.getMessage().contains("once production has started"));
    }

    @Test
    @DisplayName("Should throw when transitioning a COMPLETED or CANCELLED order")
    void shouldThrowWhenModifyingTerminalOrder() {
        sampleOrder.setStatus(OrderStatus.COMPLETED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        assertThrows(InvalidOrderException.class,
                () -> orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.IN_PROGRESS)));

        sampleOrder.setStatus(OrderStatus.CANCELLED);
        assertThrows(InvalidOrderException.class,
                () -> orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.PLANNED)));
    }

    @Test
    @DisplayName("Should throw when manually marking order as COMPLETED if any non-cancelled batch remains incomplete")
    void shouldThrowWhenMarkingOrderCompletedWhileBatchesIncomplete() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        sampleOrder.addBatch(b1);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.COMPLETED)));
        assertTrue(ex.getMessage().contains("all non-cancelled batches must be COMPLETED"));
    }

    @Test
    @DisplayName("Should allow manual order completion when remaining batches are COMPLETED and others are CANCELLED")
    void shouldAllowManualOrderCompletionWhenRemainingBatchesCompletedAndOthersCancelled() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        b1.setId(101L);
        Batch b2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.COMPLETED, null, null);
        b2.setId(102L);
        sampleOrder.addBatch(b1);
        sampleOrder.addBatch(b2);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(ManufacturingOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponseDto response = orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.COMPLETED));

        assertEquals(OrderStatus.COMPLETED, response.getStatus());
        assertNotNull(response.getActualEndDate());
    }

    @Test
    @DisplayName("Should throw when manually marking order COMPLETED if a PLANNED batch remains alongside CANCELLED/COMPLETED batches")
    void shouldThrowWhenManuallyMarkingOrderCompletedWithPlannedBatchRemaining() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        Batch b2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.COMPLETED, null, null);
        Batch b3 = new Batch("BAT-2026-0001-03", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        sampleOrder.addBatch(b1);
        sampleOrder.addBatch(b2);
        sampleOrder.addBatch(b3);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.COMPLETED)));
        assertTrue(ex.getMessage().contains("PLANNED or IN_PROGRESS"));
    }

    @Test
    @DisplayName("Should throw when manually marking order COMPLETED if all batches are CANCELLED")
    void shouldThrowWhenManuallyMarkingOrderCompletedIfAllBatchesCancelled() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        Batch b2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        sampleOrder.addBatch(b1);
        sampleOrder.addBatch(b2);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.COMPLETED)));
        assertTrue(ex.getMessage().contains("all child batches are CANCELLED"));
    }

    @Test
    @DisplayName("Should throw when attempting to release a PLANNED order whose batches are all CANCELLED")
    void shouldThrowWhenReleasingOrderWithAllBatchesCancelled() {
        sampleOrder.setStatus(OrderStatus.PLANNED);
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        sampleOrder.addBatch(b1);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.RELEASED)));
        assertTrue(ex.getMessage().contains("all child batches are CANCELLED"));
    }

    @Test
    @DisplayName("Should reject releasing a PLANNED order when its product is INACTIVE without persisting or auditing")
    void shouldThrowWhenReleasingOrderWhoseProductIsInactive() {
        sampleOrder.setStatus(OrderStatus.PLANNED);
        activeProduct.setStatus(ProductStatus.INACTIVE);
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        sampleOrder.addBatch(b1);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.RELEASED)));
        assertTrue(ex.getMessage().contains("is not ACTIVE"));
        assertEquals(OrderStatus.PLANNED, sampleOrder.getStatus());
        verify(orderRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should reject releasing a PLANNED order when its recipe is OBSOLETE or DRAFT without persisting or auditing")
    void shouldThrowWhenReleasingOrderWhoseRecipeIsNotApproved() {
        sampleOrder.setStatus(OrderStatus.PLANNED);
        approvedRecipe.setStatus(RecipeStatus.OBSOLETE);
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        sampleOrder.addBatch(b1);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException obsoleteEx = assertThrows(InvalidOrderException.class,
                () -> orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.RELEASED)));
        assertTrue(obsoleteEx.getMessage().contains("OBSOLETE"));
        assertTrue(obsoleteEx.getMessage().contains("Only APPROVED recipes can be released"));
        assertEquals(OrderStatus.PLANNED, sampleOrder.getStatus());

        approvedRecipe.setStatus(RecipeStatus.DRAFT);
        InvalidOrderException draftEx = assertThrows(InvalidOrderException.class,
                () -> orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.RELEASED)));
        assertTrue(draftEx.getMessage().contains("DRAFT"));
        assertTrue(draftEx.getMessage().contains("Only APPROVED recipes can be released"));
        assertEquals(OrderStatus.PLANNED, sampleOrder.getStatus());

        verify(orderRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should still allow cancelling a PLANNED order even if its product is INACTIVE or recipe is OBSOLETE")
    void shouldAllowCancellingPlannedOrderWhenProductInactiveAndRecipeObsolete() {
        sampleOrder.setStatus(OrderStatus.PLANNED);
        activeProduct.setStatus(ProductStatus.INACTIVE);
        approvedRecipe.setStatus(RecipeStatus.OBSOLETE);
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        b1.setId(101L);
        sampleOrder.addBatch(b1);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(ManufacturingOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        authenticateAs("qa.manager", "PRODUCTION_SUPERVISOR");
        OrderResponseDto response = orderService.updateOrderStatus(
                1L, new OrderStatusUpdateDto(OrderStatus.CANCELLED));

        assertEquals(OrderStatus.CANCELLED, response.getStatus());
        assertEquals(BatchStatus.CANCELLED, b1.getStatus());
        verify(auditLogRepository, times(2)).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should transition RELEASED order to IN_PROGRESS and populate actualStartDate")
    void shouldTransitionReleasedOrderToInProgress() {
        sampleOrder.setStatus(OrderStatus.RELEASED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(ManufacturingOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponseDto response = orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.IN_PROGRESS));

        assertEquals(OrderStatus.IN_PROGRESS, response.getStatus());
        assertNotNull(response.getActualStartDate());
    }

    @Test
    @DisplayName("Should return unchanged order if target status is same as current status")
    void shouldReturnSameOrderWhenTargetStatusIsUnchanged() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        OrderResponseDto response = orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.PLANNED));

        assertEquals(OrderStatus.PLANNED, response.getStatus());
        verify(orderRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 5. Batch Queries Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should find batch by ID when exists")
    void shouldGetBatchByIdWhenExists() {
        Batch batch = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch.setId(100L);
        when(batchRepository.findById(100L)).thenReturn(Optional.of(batch));

        BatchResponseDto response = orderService.getBatchById(100L);

        assertNotNull(response);
        assertEquals("BAT-2026-0001-01", response.getBatchNumber());
        assertEquals("MO-2026-0001", response.getOrderNumber());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when batch not found by ID")
    void shouldThrowWhenBatchNotFoundById() {
        when(batchRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.getBatchById(999L));
    }

    @Test
    @DisplayName("Should find batch by batch number when exists")
    void shouldGetBatchByBatchNumberWhenExists() {
        Batch batch = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch.setId(100L);
        when(batchRepository.findByBatchNumber("BAT-2026-0001-01")).thenReturn(Optional.of(batch));

        BatchResponseDto response = orderService.getBatchByBatchNumber("BAT-2026-0001-01");

        assertNotNull(response);
        assertEquals(100L, response.getId());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when batch not found by batch number")
    void shouldThrowWhenBatchNotFoundByBatchNumber() {
        when(batchRepository.findByBatchNumber("BAT-UNKNOWN")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.getBatchByBatchNumber("BAT-UNKNOWN"));
    }

    @Test
    @DisplayName("Should get all batches for an order")
    void shouldGetBatchesByOrderId() {
        Batch batch = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        when(orderRepository.existsById(1L)).thenReturn(true);
        when(batchRepository.findByOrderId(1L)).thenReturn(List.of(batch));

        List<BatchResponseDto> batches = orderService.getBatchesByOrderId(1L);

        assertEquals(1, batches.size());
        assertEquals("BAT-2026-0001-01", batches.get(0).getBatchNumber());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when getting batches for non-existent order")
    void shouldThrowWhenGetBatchesByOrderIdForNonExistentOrder() {
        when(orderRepository.existsById(999L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> orderService.getBatchesByOrderId(999L));
    }

    // -------------------------------------------------------------------------
    // 6. Batch Status Transition Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should start batch, populate startTime and manufactureDate, and advance parent order to IN_PROGRESS")
    void shouldStartBatchAndAdvanceParentOrderToInProgress() {
        sampleOrder.setStatus(OrderStatus.RELEASED);
        Batch batch = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch.setId(101L);

        when(batchRepository.findById(101L)).thenReturn(Optional.of(batch));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(batchRepository.save(any(Batch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(101L)).thenReturn(List.of());
        when(executionStepRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        BatchResponseDto response = orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS));

        assertEquals(BatchStatus.IN_PROGRESS, response.getStatus());
        assertNotNull(response.getStartTime(), "Start time must be populated");
        assertNotNull(response.getManufactureDate(), "Manufacture date must be populated");
        assertEquals(OrderStatus.IN_PROGRESS, sampleOrder.getStatus(), "Parent order must auto-advance to IN_PROGRESS");
        assertNotNull(sampleOrder.getActualStartDate(), "Order actualStartDate must be populated");

        verify(orderRepository).findByIdWithLock(1L);
        verify(orderRepository).save(sampleOrder);
        verify(executionStepRepository).saveAll(argThat(iterable -> {
            List<BatchExecutionStep> list = new ArrayList<>();
            iterable.forEach(list::add);
            return list.size() == 2
                    && list.get(0).getStepNumber() == 1
                    && list.get(0).getStatus() == ExecutionStepStatus.PENDING
                    && list.get(1).getStepNumber() == 2
                    && list.get(1).getStatus() == ExecutionStepStatus.PENDING;
        }));
    }

    @Test
    @DisplayName("Should throw when starting batch if parent order is still in PLANNED status")
    void shouldThrowWhenStartingBatchIfParentOrderIsPlanned() {
        sampleOrder.setStatus(OrderStatus.PLANNED);
        Batch batch = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch.setId(101L);

        when(batchRepository.findById(101L)).thenReturn(Optional.of(batch));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS)));
        assertTrue(ex.getMessage().contains("Order must be RELEASED or IN_PROGRESS"));
    }

    @Test
    @DisplayName("Should allow individual batch cancellation only while parent order is PLANNED")
    void shouldCancelBatchWhileParentOrderIsPlanned() {
        sampleOrder.setStatus(OrderStatus.PLANNED);
        Batch batch = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch.setId(101L);

        when(batchRepository.findById(101L)).thenReturn(Optional.of(batch));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(batchRepository.save(any(Batch.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BatchResponseDto response = orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.CANCELLED));

        assertEquals(BatchStatus.CANCELLED, response.getStatus());
    }

    @Test
    @DisplayName("Should keep parent order PLANNED when cancelling one batch while other PLANNED batches remain")
    void shouldKeepParentOrderPlannedWhenOtherPlannedBatchesRemain() {
        sampleOrder.setStatus(OrderStatus.PLANNED);
        Batch batch1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch1.setId(101L);
        Batch batch2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch2.setId(102L);

        when(batchRepository.findById(101L)).thenReturn(Optional.of(batch1));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(batchRepository.save(any(Batch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(batchRepository.findByOrderId(1L)).thenReturn(List.of(batch1, batch2));

        BatchResponseDto response = orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.CANCELLED));

        assertEquals(BatchStatus.CANCELLED, response.getStatus());
        assertEquals(OrderStatus.PLANNED, sampleOrder.getStatus());
        verify(orderRepository, never()).save(any(ManufacturingOrder.class));
    }

    @Test
    @DisplayName("Should auto-cancel parent PLANNED order when its last remaining PLANNED batch is cancelled")
    void shouldAutoCancelParentOrderWhenAllBatchesBecomeCancelled() {
        sampleOrder.setStatus(OrderStatus.PLANNED);
        Batch batch1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        batch1.setId(101L);
        Batch batch2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch2.setId(102L);

        when(batchRepository.findById(102L)).thenReturn(Optional.of(batch2));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(batchRepository.save(any(Batch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(batchRepository.findByOrderId(1L)).thenReturn(List.of(batch1, batch2));

        BatchResponseDto response = orderService.updateBatchStatus(102L, new BatchStatusUpdateDto(BatchStatus.CANCELLED));

        assertEquals(BatchStatus.CANCELLED, response.getStatus());
        assertEquals(OrderStatus.CANCELLED, sampleOrder.getStatus(),
                "Parent order must transition to CANCELLED when all child batches are CANCELLED");
        verify(orderRepository).save(sampleOrder);
    }

    @Test
    @DisplayName("Should throw when attempting individual batch cancellation if parent order is RELEASED")
    void shouldThrowWhenCancellingBatchIfParentOrderIsReleased() {
        sampleOrder.setStatus(OrderStatus.RELEASED);
        Batch batch = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch.setId(101L);

        when(batchRepository.findById(101L)).thenReturn(Optional.of(batch));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.CANCELLED)));
        assertTrue(ex.getMessage().contains("permitted only while its parent order is PLANNED"));
    }

    @Test
    @DisplayName("Should throw when attempting individual batch cancellation if parent order is IN_PROGRESS")
    void shouldThrowWhenCancellingBatchIfParentOrderIsInProgress() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);
        Batch batch = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch.setId(102L);

        when(batchRepository.findById(102L)).thenReturn(Optional.of(batch));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(102L, new BatchStatusUpdateDto(BatchStatus.CANCELLED)));
        assertTrue(ex.getMessage().contains("permitted only while its parent order is PLANNED"));
    }

    @Test
    @DisplayName("Should throw when attempting to cancel a COMPLETED batch")
    void shouldThrowWhenCancellingCompletedBatch() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);
        Batch batch = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.COMPLETED, null, null);
        batch.setId(101L);

        when(batchRepository.findById(101L)).thenReturn(Optional.of(batch));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.CANCELLED)));
        assertTrue(ex.getMessage().contains("COMPLETED batch"));
    }

    @Test
    @DisplayName("Should complete batch with positive actualYield, populate endTime, and not auto-complete order if other batches remain incomplete")
    void shouldCompleteBatchWithPositiveYieldWithoutAutoCompletingOrder() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);
        Batch batch1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        batch1.setId(101L);
        Batch batch2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch2.setId(102L);

        when(batchRepository.findById(101L)).thenReturn(Optional.of(batch1));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(executionStepRepository.existsByBatchId(101L)).thenReturn(true);
        when(executionStepRepository.countByBatchIdAndStatusNot(101L, ExecutionStepStatus.COMPLETED)).thenReturn(0L);
        when(batchRepository.save(any(Batch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(batchRepository.findByOrderId(1L)).thenReturn(List.of(batch1, batch2));

        BatchStatusUpdateDto updateDto = new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("248.5000"));
        BatchResponseDto response = orderService.updateBatchStatus(101L, updateDto);

        assertEquals(BatchStatus.COMPLETED, response.getStatus());
        assertEquals(BatchQualityStatus.PENDING_QA, response.getQaStatus());
        assertEquals(new BigDecimal("248.5000"), response.getActualYield());
        assertNotNull(response.getEndTime());
        assertEquals(OrderStatus.IN_PROGRESS, sampleOrder.getStatus(), "Order must remain IN_PROGRESS because batch 2 is still PLANNED");
        assertNull(sampleOrder.getActualEndDate());
    }

    @Test
    @DisplayName("Should auto-complete parent order and set actualEndDate when final batch completes")
    void shouldAutoCompleteOrderWhenFinalBatchCompletes() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);
        sampleOrder.setActualStartDate(LocalDateTime.of(2026, 3, 1, 8, 0));

        Batch batch1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.COMPLETED, null, null);
        batch1.setId(101L);
        batch1.setActualYield(new BigDecimal("249.0000"));

        Batch batch2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        batch2.setId(102L);

        when(batchRepository.findById(102L)).thenReturn(Optional.of(batch2));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(executionStepRepository.existsByBatchId(102L)).thenReturn(true);
        when(executionStepRepository.countByBatchIdAndStatusNot(102L, ExecutionStepStatus.COMPLETED)).thenReturn(0L);
        when(batchRepository.save(any(Batch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(batchRepository.findByOrderId(1L)).thenReturn(List.of(batch1, batch2));

        BatchStatusUpdateDto updateDto = new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("247.8000"));
        BatchResponseDto response = orderService.updateBatchStatus(102L, updateDto);

        assertEquals(BatchStatus.COMPLETED, response.getStatus());
        assertEquals(BatchQualityStatus.PENDING_QA, response.getQaStatus());
        assertEquals(OrderStatus.COMPLETED, sampleOrder.getStatus(), "Parent order must auto-advance to COMPLETED");
        assertNotNull(sampleOrder.getActualEndDate(), "Parent order actualEndDate must be populated");
        verify(orderRepository).save(sampleOrder);
    }

    @Test
    @DisplayName("Should auto-complete parent order when all remaining batches are COMPLETED and one batch was CANCELLED")
    void shouldAutoCompleteOrderWhenRemainingBatchesCompleteAndOneCancelled() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);
        sampleOrder.setActualStartDate(LocalDateTime.of(2026, 3, 1, 8, 0));

        Batch batch1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        batch1.setId(101L);

        Batch batch2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        batch2.setId(102L);

        when(batchRepository.findById(102L)).thenReturn(Optional.of(batch2));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(executionStepRepository.existsByBatchId(102L)).thenReturn(true);
        when(executionStepRepository.countByBatchIdAndStatusNot(102L, ExecutionStepStatus.COMPLETED)).thenReturn(0L);
        when(batchRepository.save(any(Batch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(batchRepository.findByOrderId(1L)).thenReturn(List.of(batch1, batch2));

        BatchStatusUpdateDto updateDto = new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("247.8000"));
        BatchResponseDto response = orderService.updateBatchStatus(102L, updateDto);

        assertEquals(BatchStatus.COMPLETED, response.getStatus());
        assertEquals(OrderStatus.COMPLETED, sampleOrder.getStatus(),
                "Order must auto-complete when one batch is CANCELLED and all remaining batches are COMPLETED");
        assertNotNull(sampleOrder.getActualEndDate());
        verify(orderRepository).save(sampleOrder);
    }

    @Test
    @DisplayName("Should auto-complete parent order when multiple batches are CANCELLED and all remaining batches are COMPLETED")
    void shouldAutoCompleteOrderWhenMultipleBatchesCancelledAndRemainingCompleted() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);

        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        b1.setId(101L);
        Batch b2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.COMPLETED, null, null);
        b2.setId(102L);
        Batch b3 = new Batch("BAT-2026-0001-03", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        b3.setId(103L);
        Batch b4 = new Batch("BAT-2026-0001-04", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        b4.setId(104L);

        when(batchRepository.findById(104L)).thenReturn(Optional.of(b4));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(executionStepRepository.existsByBatchId(104L)).thenReturn(true);
        when(executionStepRepository.countByBatchIdAndStatusNot(104L, ExecutionStepStatus.COMPLETED)).thenReturn(0L);
        when(batchRepository.save(any(Batch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(batchRepository.findByOrderId(1L)).thenReturn(List.of(b1, b2, b3, b4));

        orderService.updateBatchStatus(104L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("248.0000")));

        assertEquals(OrderStatus.COMPLETED, sampleOrder.getStatus());
        assertNotNull(sampleOrder.getActualEndDate());
        verify(orderRepository).save(sampleOrder);
    }

    @Test
    @DisplayName("Should NOT auto-complete parent order when COMPLETED and IN_PROGRESS batches coexist alongside CANCELLED")
    void shouldNotAutoCompleteOrderWhenInProgressBatchRemainsAlongsideCancelled() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);

        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        b1.setId(101L);
        Batch b2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        b2.setId(102L);
        Batch b3 = new Batch("BAT-2026-0001-03", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        b3.setId(103L);

        when(batchRepository.findById(102L)).thenReturn(Optional.of(b2));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(executionStepRepository.existsByBatchId(102L)).thenReturn(true);
        when(executionStepRepository.countByBatchIdAndStatusNot(102L, ExecutionStepStatus.COMPLETED)).thenReturn(0L);
        when(batchRepository.save(any(Batch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(batchRepository.findByOrderId(1L)).thenReturn(List.of(b1, b2, b3));

        orderService.updateBatchStatus(102L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("247.5000")));

        assertEquals(OrderStatus.IN_PROGRESS, sampleOrder.getStatus(),
                "Order must stay IN_PROGRESS while another batch is still IN_PROGRESS");
        assertNull(sampleOrder.getActualEndDate());
        verify(orderRepository, never()).save(sampleOrder);
    }

    @Test
    @DisplayName("Should NOT auto-complete parent order when COMPLETED and PLANNED batches coexist alongside CANCELLED")
    void shouldNotAutoCompleteOrderWhenPlannedBatchRemainsAlongsideCancelled() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);

        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        b1.setId(101L);
        Batch b2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        b2.setId(102L);
        Batch b3 = new Batch("BAT-2026-0001-03", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        b3.setId(103L);

        when(batchRepository.findById(102L)).thenReturn(Optional.of(b2));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(executionStepRepository.existsByBatchId(102L)).thenReturn(true);
        when(executionStepRepository.countByBatchIdAndStatusNot(102L, ExecutionStepStatus.COMPLETED)).thenReturn(0L);
        when(batchRepository.save(any(Batch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(batchRepository.findByOrderId(1L)).thenReturn(List.of(b1, b2, b3));

        orderService.updateBatchStatus(102L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("247.5000")));

        assertEquals(OrderStatus.IN_PROGRESS, sampleOrder.getStatus(),
                "Order must stay IN_PROGRESS while another batch is still PLANNED");
        assertNull(sampleOrder.getActualEndDate());
        verify(orderRepository, never()).save(sampleOrder);
    }

    @Test
    @DisplayName("Should throw when completing batch without actualYield or with non-positive yield")
    void shouldThrowWhenCompletingBatchWithoutActualYield() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);
        Batch batch = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        batch.setId(101L);

        when(batchRepository.findById(101L)).thenReturn(Optional.of(batch));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));

        // Test with null actualYield
        assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, null)));

        // Test with zero actualYield
        assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, BigDecimal.ZERO)));

        // Test with negative actualYield
        assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("-10.0000"))));
    }

    @Test
    @DisplayName("Should throw when attempting to cancel an IN_PROGRESS batch")
    void shouldThrowWhenCancellingInProgressBatch() {
        sampleOrder.setStatus(OrderStatus.IN_PROGRESS);
        Batch batch = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        batch.setId(101L);

        when(batchRepository.findById(101L)).thenReturn(Optional.of(batch));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.CANCELLED)));
        assertTrue(ex.getMessage().contains("because it is already IN_PROGRESS"));
    }

    @Test
    @DisplayName("Should throw when attempting to transition a COMPLETED or CANCELLED batch")
    void shouldThrowWhenModifyingTerminalBatch() {
        sampleOrder.setStatus(OrderStatus.COMPLETED);
        Batch completedBatch = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.COMPLETED, null, null);
        completedBatch.setId(101L);

        when(batchRepository.findById(101L)).thenReturn(Optional.of(completedBatch));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));

        assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS)));

        completedBatch.setStatus(BatchStatus.CANCELLED);
        assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.PLANNED)));
    }

    @Test
    @DisplayName("Should throw when attempting to complete a batch directly from PLANNED")
    void shouldThrowWhenCompletingBatchDirectlyFromPlanned() {
        sampleOrder.setStatus(OrderStatus.RELEASED);
        Batch batch = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        batch.setId(101L);

        when(batchRepository.findById(101L)).thenReturn(Optional.of(batch));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));

        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("250.0000"))));
        assertTrue(ex.getMessage().contains("Cannot transition batch directly from PLANNED to COMPLETED"));
    }

    // -------------------------------------------------------------------------
    // 7. Audit Trail Unit Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should record MANUFACTURING_ORDER_CREATED and BATCH_CREATED audits with complete snapshots and normalized actor")
    void shouldRecordOrderCreatedAndBatchCreatedAudits() throws Exception {
        authenticateAs("  Planner.Maria  ", "PRODUCTION_SUPERVISOR");
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);
        OrderRequestDto requestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("500.0000"),
                "kg",
                today,
                today.plusDays(4),
                "Scheduled production run"
        );

        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(approvedRecipe));
        when(orderRepository.saveAndFlush(any(ManufacturingOrder.class)))
                .thenAnswer(invocation -> assignSyntheticIds(invocation.getArgument(0), 1L));

        OrderResponseDto response = orderService.createOrder(requestDto);
        assertEquals(2, response.getBatches().size());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(3)).save(captor.capture());
        List<AuditLog> logs = captor.getAllValues();

        AuditLog orderLog = logs.get(0);
        assertEquals("MANUFACTURING_ORDER", orderLog.getEntityType());
        assertEquals(1L, orderLog.getEntityId());
        assertEquals(response.getOrderNumber(), orderLog.getEntityReference());
        assertNull(orderLog.getBatchId());
        assertEquals("MANUFACTURING_ORDER_CREATED", orderLog.getAction());
        assertEquals("planner.maria", orderLog.getActorName());
        assertNotNull(orderLog.getOccurredAt());
        assertNull(orderLog.getBeforeValues());

        JsonNode orderAfter = objectMapper.readTree(orderLog.getAfterValues());
        assertEquals(1L, orderAfter.get("orderId").asLong());
        assertEquals(response.getOrderNumber(), orderAfter.get("orderNumber").asText());
        assertEquals(1L, orderAfter.get("productId").asLong());
        assertEquals("AMOX-250", orderAfter.get("productCode").asText());
        assertEquals("Amoxicillin Capsules", orderAfter.get("productName").asText());
        assertEquals(10L, orderAfter.get("recipeId").asLong());
        assertEquals("RCP-AMOX-250-V3", orderAfter.get("recipeCode").asText());
        assertEquals(3, orderAfter.get("recipeVersion").asInt());
        assertEquals(0, new BigDecimal("250").compareTo(orderAfter.get("standardBatchSize").decimalValue()));
        assertEquals(0, new BigDecimal("500").compareTo(orderAfter.get("plannedQuantity").decimalValue()));
        assertEquals("kg", orderAfter.get("quantityUnit").asText());
        assertEquals(2, orderAfter.get("targetBatchCount").asInt());
        assertEquals("PLANNED", orderAfter.get("status").asText());
        assertEquals(today.toString(), orderAfter.get("plannedStartDate").asText());
        assertEquals(today.plusDays(4).toString(), orderAfter.get("plannedEndDate").asText());
        assertEquals("Scheduled production run", orderAfter.get("notes").asText());
        assertEquals(2, orderAfter.get("batchSummary").get("totalBatches").asInt());
        assertEquals(2, orderAfter.get("batchSummary").get("plannedBatches").asInt());
        assertEquals(2, orderAfter.get("batches").size());

        AuditLog batch1Log = logs.get(1);
        assertEquals("BATCH", batch1Log.getEntityType());
        assertEquals(100L, batch1Log.getEntityId());
        assertEquals(100L, batch1Log.getBatchId());
        assertEquals(response.getBatches().get(0).getBatchNumber(), batch1Log.getEntityReference());
        assertEquals("BATCH_CREATED", batch1Log.getAction());
        assertEquals("planner.maria", batch1Log.getActorName());
        assertNull(batch1Log.getBeforeValues());

        JsonNode batch1After = objectMapper.readTree(batch1Log.getAfterValues());
        assertEquals(100L, batch1After.get("batchId").asLong());
        assertEquals(1L, batch1After.get("orderId").asLong());
        assertEquals(response.getOrderNumber(), batch1After.get("orderNumber").asText());
        assertEquals(1L, batch1After.get("productId").asLong());
        assertEquals(10L, batch1After.get("recipeId").asLong());
        assertEquals(3, batch1After.get("recipeVersion").asInt());
        assertEquals(0, new BigDecimal("250").compareTo(batch1After.get("batchSize").decimalValue()));
        assertEquals("kg", batch1After.get("batchUnit").asText());
        assertEquals("PLANNED", batch1After.get("status").asText());

        AuditLog batch2Log = logs.get(2);
        assertEquals("BATCH", batch2Log.getEntityType());
        assertEquals(101L, batch2Log.getEntityId());
        assertEquals(101L, batch2Log.getBatchId());
        assertEquals("BATCH_CREATED", batch2Log.getAction());
        assertEquals("planner.maria", batch2Log.getActorName());
    }

    @Test
    @DisplayName("Should record RELEASED, UPDATED, COMPLETED, and CANCELLED+cascade audits with accurate before/after snapshots")
    void shouldRecordOrderLifecycleAuditTransitionsWithSnapshots() throws Exception {
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        b1.setId(101L);
        Batch b2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.CANCELLED, null, null);
        b2.setId(102L);
        sampleOrder.addBatch(b1);
        sampleOrder.addBatch(b2);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(ManufacturingOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 1. PLANNED -> RELEASED
        authenticateAs("  Plant.Lead  ", "PRODUCTION_SUPERVISOR");
        orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.RELEASED));
        // 2. RELEASED -> IN_PROGRESS
        authenticateAs("Supervisor.Amy", "PRODUCTION_SUPERVISOR");
        orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.IN_PROGRESS));
        // Simulate batch completion so manual COMPLETED is valid
        b1.setStatus(BatchStatus.COMPLETED);
        b1.setActualYield(new BigDecimal("248.5000"));
        // 3. IN_PROGRESS -> COMPLETED
        authenticateAs("Prod.Manager", "PRODUCTION_SUPERVISOR");
        orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.COMPLETED));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(3)).save(captor.capture());
        List<AuditLog> logs = captor.getAllValues();

        assertEquals("MANUFACTURING_ORDER_RELEASED", logs.get(0).getAction());
        assertEquals("plant.lead", logs.get(0).getActorName());
        assertEquals("PLANNED", objectMapper.readTree(logs.get(0).getBeforeValues()).get("status").asText());
        assertEquals("RELEASED", objectMapper.readTree(logs.get(0).getAfterValues()).get("status").asText());

        assertEquals("MANUFACTURING_ORDER_UPDATED", logs.get(1).getAction());
        assertEquals("supervisor.amy", logs.get(1).getActorName());
        assertEquals("RELEASED", objectMapper.readTree(logs.get(1).getBeforeValues()).get("status").asText());
        assertTrue(objectMapper.readTree(logs.get(1).getBeforeValues()).get("actualStartDate").isNull());
        JsonNode updatedAfter = objectMapper.readTree(logs.get(1).getAfterValues());
        assertEquals("IN_PROGRESS", updatedAfter.get("status").asText());
        assertFalse(updatedAfter.get("actualStartDate").isNull());

        assertEquals("MANUFACTURING_ORDER_COMPLETED", logs.get(2).getAction());
        assertEquals("prod.manager", logs.get(2).getActorName());
        assertEquals("IN_PROGRESS", objectMapper.readTree(logs.get(2).getBeforeValues()).get("status").asText());
        JsonNode completedAfter = objectMapper.readTree(logs.get(2).getAfterValues());
        assertEquals("COMPLETED", completedAfter.get("status").asText());
        assertFalse(completedAfter.get("actualEndDate").isNull());

        // 4. Separate cancellation cascade check: skips already-CANCELLED b2, audits only order + b1
        reset(auditLogRepository);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        sampleOrder.setStatus(OrderStatus.RELEASED);
        sampleOrder.setActualStartDate(null);
        sampleOrder.setActualEndDate(null);
        b1.setStatus(BatchStatus.PLANNED);
        b1.setActualYield(null);

        authenticateAs("Ops.Director", "ADMIN");
        orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.CANCELLED));

        ArgumentCaptor<AuditLog> cancelCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(cancelCaptor.capture());
        List<AuditLog> cancelLogs = cancelCaptor.getAllValues();

        assertEquals("MANUFACTURING_ORDER_CANCELLED", cancelLogs.get(0).getAction());
        assertEquals("ops.director", cancelLogs.get(0).getActorName());
        assertEquals("RELEASED", objectMapper.readTree(cancelLogs.get(0).getBeforeValues()).get("status").asText());
        assertEquals("CANCELLED", objectMapper.readTree(cancelLogs.get(0).getAfterValues()).get("status").asText());

        assertEquals("BATCH", cancelLogs.get(1).getEntityType());
        assertEquals(101L, cancelLogs.get(1).getEntityId());
        assertEquals(101L, cancelLogs.get(1).getBatchId());
        assertEquals("BATCH_CANCELLED", cancelLogs.get(1).getAction());
        assertEquals("ops.director", cancelLogs.get(1).getActorName());
        assertEquals("PLANNED", objectMapper.readTree(cancelLogs.get(1).getBeforeValues()).get("status").asText());
        assertEquals("CANCELLED", objectMapper.readTree(cancelLogs.get(1).getAfterValues()).get("status").asText());
    }

    @Test
    @DisplayName("Should record BATCH_STARTED, BATCH_COMPLETED, and auto-triggered order audits with snapshots and without duplicates")
    void shouldRecordBatchTriggeredOrderAuditsWithoutDuplicates() throws Exception {
        sampleOrder.setStatus(OrderStatus.RELEASED);
        Batch b1 = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        b1.setId(101L);
        Batch b2 = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        b2.setId(102L);
        sampleOrder.addBatch(b1);
        sampleOrder.addBatch(b2);

        when(batchRepository.findById(101L)).thenReturn(Optional.of(b1));
        when(batchRepository.findById(102L)).thenReturn(Optional.of(b2));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));
        when(executionStepRepository.findByBatchIdOrderByStepNumberAsc(any())).thenReturn(List.of());
        when(executionStepRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(batchRepository.save(any(Batch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchRepository.findByOrderId(1L)).thenReturn(List.of(b1, b2));

        // 1st batch start emits BATCH_STARTED + auto-advances parent order (MANUFACTURING_ORDER_UPDATED)
        authenticateAs("  Operator.One  ", "OPERATOR");
        orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS));
        ArgumentCaptor<AuditLog> start1Captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(start1Captor.capture());

        AuditLog batch1StartedLog = start1Captor.getAllValues().get(0);
        assertEquals("BATCH", batch1StartedLog.getEntityType());
        assertEquals(101L, batch1StartedLog.getEntityId());
        assertEquals(101L, batch1StartedLog.getBatchId());
        assertEquals("BAT-2026-0001-01", batch1StartedLog.getEntityReference());
        assertEquals("BATCH_STARTED", batch1StartedLog.getAction());
        assertEquals("operator.one", batch1StartedLog.getActorName());
        JsonNode b1StartBefore = objectMapper.readTree(batch1StartedLog.getBeforeValues());
        JsonNode b1StartAfter = objectMapper.readTree(batch1StartedLog.getAfterValues());
        assertEquals("PLANNED", b1StartBefore.get("status").asText());
        assertTrue(b1StartBefore.get("startTime").isNull());
        assertTrue(b1StartBefore.get("manufactureDate").isNull());
        assertEquals("IN_PROGRESS", b1StartAfter.get("status").asText());
        assertFalse(b1StartAfter.get("startTime").isNull());
        assertFalse(b1StartAfter.get("manufactureDate").isNull());
        assertEquals(3, b1StartAfter.get("recipeVersion").asInt());
        assertEquals("MO-2026-0001", b1StartAfter.get("orderNumber").asText());

        AuditLog orderUpdatedLog = start1Captor.getAllValues().get(1);
        assertEquals("MANUFACTURING_ORDER", orderUpdatedLog.getEntityType());
        assertEquals("MANUFACTURING_ORDER_UPDATED", orderUpdatedLog.getAction());
        assertEquals("operator.one", orderUpdatedLog.getActorName());
        assertEquals("RELEASED", objectMapper.readTree(orderUpdatedLog.getBeforeValues()).get("status").asText());
        assertEquals("IN_PROGRESS", objectMapper.readTree(orderUpdatedLog.getAfterValues()).get("status").asText());

        // 2nd batch start emits ONLY BATCH_STARTED (no duplicate MANUFACTURING_ORDER_UPDATED)
        reset(auditLogRepository);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        authenticateAs("Operator.Two", "OPERATOR");
        orderService.updateBatchStatus(102L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS));
        ArgumentCaptor<AuditLog> start2Captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(start2Captor.capture());
        assertEquals("BATCH", start2Captor.getValue().getEntityType());
        assertEquals(102L, start2Captor.getValue().getEntityId());
        assertEquals(102L, start2Captor.getValue().getBatchId());
        assertEquals("BATCH_STARTED", start2Captor.getValue().getAction());
        assertEquals("operator.two", start2Captor.getValue().getActorName());
        assertEquals("IN_PROGRESS", objectMapper.readTree(start2Captor.getValue().getAfterValues()).get("status").asText());

        // Completing 1st batch emits ONLY BATCH_COMPLETED (zero MANUFACTURING_ORDER_COMPLETED while b2 is IN_PROGRESS)
        reset(auditLogRepository);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(executionStepRepository.existsByBatchId(101L)).thenReturn(true);
        when(executionStepRepository.existsByBatchId(102L)).thenReturn(true);
        when(executionStepRepository.countByBatchIdAndStatusNot(101L, ExecutionStepStatus.COMPLETED)).thenReturn(0L);
        when(executionStepRepository.countByBatchIdAndStatusNot(102L, ExecutionStepStatus.COMPLETED)).thenReturn(0L);
        orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("249.0000")));
        ArgumentCaptor<AuditLog> complete1Captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(complete1Captor.capture());
        AuditLog batch1CompletedLog = complete1Captor.getValue();
        assertEquals("BATCH", batch1CompletedLog.getEntityType());
        assertEquals(101L, batch1CompletedLog.getEntityId());
        assertEquals(101L, batch1CompletedLog.getBatchId());
        assertEquals("BAT-2026-0001-01", batch1CompletedLog.getEntityReference());
        assertEquals("BATCH_COMPLETED", batch1CompletedLog.getAction());
        assertEquals("operator.two", batch1CompletedLog.getActorName());
        JsonNode b1CompBefore = objectMapper.readTree(batch1CompletedLog.getBeforeValues());
        JsonNode b1CompAfter = objectMapper.readTree(batch1CompletedLog.getAfterValues());
        assertEquals("IN_PROGRESS", b1CompBefore.get("status").asText());
        assertTrue(b1CompBefore.get("qaStatus").isNull());
        assertTrue(b1CompBefore.get("actualYield").isNull());
        assertTrue(b1CompBefore.get("endTime").isNull());
        assertEquals("COMPLETED", b1CompAfter.get("status").asText());
        assertEquals("PENDING_QA", b1CompAfter.get("qaStatus").asText());
        assertEquals(0, new BigDecimal("249").compareTo(b1CompAfter.get("actualYield").decimalValue()));
        assertFalse(b1CompAfter.get("endTime").isNull());

        // Completing 2nd (final) batch emits BATCH_COMPLETED + MANUFACTURING_ORDER_COMPLETED
        reset(auditLogRepository);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        authenticateAs("Operator.Finisher", "OPERATOR");
        orderService.updateBatchStatus(102L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("248.0000")));
        ArgumentCaptor<AuditLog> complete2Captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(complete2Captor.capture());

        AuditLog batch2CompletedLog = complete2Captor.getAllValues().get(0);
        assertEquals("BATCH", batch2CompletedLog.getEntityType());
        assertEquals(102L, batch2CompletedLog.getEntityId());
        assertEquals(102L, batch2CompletedLog.getBatchId());
        assertEquals("BATCH_COMPLETED", batch2CompletedLog.getAction());
        assertEquals("operator.finisher", batch2CompletedLog.getActorName());
        assertEquals("COMPLETED", objectMapper.readTree(batch2CompletedLog.getAfterValues()).get("status").asText());
        assertEquals(0, new BigDecimal("248").compareTo(objectMapper.readTree(batch2CompletedLog.getAfterValues()).get("actualYield").decimalValue()));

        AuditLog orderCompletedLog = complete2Captor.getAllValues().get(1);
        assertEquals("MANUFACTURING_ORDER", orderCompletedLog.getEntityType());
        assertEquals("MANUFACTURING_ORDER_COMPLETED", orderCompletedLog.getAction());
        assertEquals("operator.finisher", orderCompletedLog.getActorName());
        assertEquals("IN_PROGRESS", objectMapper.readTree(orderCompletedLog.getBeforeValues()).get("status").asText());
        assertEquals("COMPLETED", objectMapper.readTree(orderCompletedLog.getAfterValues()).get("status").asText());
    }

    @Test
    @DisplayName("Should fail closed without valid authentication, enforce batch transition role checks, and emit zero audits on no-ops")
    void shouldFailClosedWithoutValidAuthenticationAndEnforceBatchRoleAuthorizationAndSkipNoOpAudits() {
        LocalDate today = LocalDate.now(ManufacturingOrderService.APP_ZONE_ID);
        OrderRequestDto validRequest = new OrderRequestDto(1L, 10L, new BigDecimal("250.0000"), "kg", today, today.plusDays(1), null);

        // Missing authentication fails closed before repository writes
        SecurityContextHolder.clearContext();
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> orderService.createOrder(validRequest));
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.RELEASED)));
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.CANCELLED)));
        verifyNoInteractions(productRepository, recipeRepository, orderRepository, batchRepository);

        // Oversized principal (>100 chars) fails closed with InsufficientAuthenticationException
        authenticateAs("x".repeat(101), "PRODUCTION_SUPERVISOR");
        assertThrows(InsufficientAuthenticationException.class, () -> orderService.createOrder(validRequest));
        assertThrows(InsufficientAuthenticationException.class,
                () -> orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.RELEASED)));
        assertThrows(InsufficientAuthenticationException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.CANCELLED)));
        verifyNoInteractions(productRepository, recipeRepository, orderRepository, batchRepository);

        // Defense-in-depth batch target-status role authorization
        authenticateAs("sys.admin", "ADMIN");
        assertThrows(AccessDeniedException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS)));
        assertThrows(AccessDeniedException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("245.0000"))));

        authenticateAs("line.operator", "OPERATOR");
        assertThrows(AccessDeniedException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.CANCELLED)));

        authenticateAs("qc.analyst", "QC_ANALYST");
        assertThrows(AccessDeniedException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS)));

        authenticateAs("qa.reviewer", "QA_REVIEWER");
        assertThrows(AccessDeniedException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS)));

        // Idempotent no-ops emit zero audits
        authenticateAs("planner.maria", "PRODUCTION_SUPERVISOR");
        sampleOrder.setStatus(OrderStatus.PLANNED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        orderService.updateOrderStatus(1L, new OrderStatusUpdateDto(OrderStatus.PLANNED));

        Batch plannedBatch = new Batch("BAT-2026-0001-01", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.PLANNED, null, null);
        plannedBatch.setId(101L);
        when(batchRepository.findById(101L)).thenReturn(Optional.of(plannedBatch));
        when(orderRepository.findByIdWithLock(1L)).thenReturn(Optional.of(sampleOrder));
        orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.PLANNED));

        // Failed start under PLANNED order + idempotent IN_PROGRESS/COMPLETED retries emit zero audits
        assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(101L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS)));

        Batch inProgressBatch = new Batch("BAT-2026-0001-02", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.IN_PROGRESS, null, null);
        inProgressBatch.setId(102L);
        when(batchRepository.findById(102L)).thenReturn(Optional.of(inProgressBatch));
        orderService.updateBatchStatus(102L, new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS));
        assertThrows(InvalidOrderException.class,
                () -> orderService.updateBatchStatus(102L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, BigDecimal.ZERO)));

        Batch completedBatch = new Batch("BAT-2026-0001-03", sampleOrder, new BigDecimal("250.0000"), "kg", BatchStatus.COMPLETED, null, null);
        completedBatch.setId(103L);
        when(batchRepository.findById(103L)).thenReturn(Optional.of(completedBatch));
        orderService.updateBatchStatus(103L, new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("245.0000")));

        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }
}

