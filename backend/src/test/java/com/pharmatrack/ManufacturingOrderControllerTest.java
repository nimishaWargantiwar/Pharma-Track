package com.pharmatrack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.controller.ManufacturingOrderController;
import com.pharmatrack.dto.BatchResponseDto;
import com.pharmatrack.dto.OrderRequestDto;
import com.pharmatrack.dto.OrderResponseDto;
import com.pharmatrack.dto.OrderStatusUpdateDto;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.exception.GlobalExceptionHandler;
import com.pharmatrack.exception.InvalidOrderException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.security.SecurityConfig;
import com.pharmatrack.service.ManufacturingOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ManufacturingOrderController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@WithMockUser(username = "supervisor.user", roles = "PRODUCTION_SUPERVISOR")
class ManufacturingOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ManufacturingOrderService orderService;

    private OrderRequestDto sampleRequestDto;
    private OrderResponseDto sampleResponseDto;
    private BatchResponseDto sampleBatchDto;

    @BeforeEach
    void setUp() {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        sampleRequestDto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("500.0000"),
                "kg",
                today,
                today.plusDays(4),
                "Production order notes"
        );

        sampleBatchDto = new BatchResponseDto(
                101L,
                "BAT-2026-0001-01",
                1L,
                "MO-2026-0001",
                new BigDecimal("250.0000"),
                "kg",
                null,
                BatchStatus.PLANNED,
                null,
                null,
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        sampleResponseDto = new OrderResponseDto(
                1L,
                "MO-2026-0001",
                1L,
                "AMOX-250",
                "Amoxicillin Capsules",
                10L,
                "RCP-AMOX-250-V3",
                3,
                new BigDecimal("500.0000"),
                "kg",
                2,
                OrderStatus.PLANNED,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 5),
                null,
                null,
                "Production order notes",
                List.of(sampleBatchDto),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    // -------------------------------------------------------------------------
    // 1. POST /api/orders - Create Order
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/orders - Should create order and return 201 Created")
    void createOrder_Success_Returns201() throws Exception {
        when(orderService.createOrder(any(OrderRequestDto.class))).thenReturn(sampleResponseDto);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.orderNumber").value("MO-2026-0001"))
                .andExpect(jsonPath("$.productCode").value("AMOX-250"))
                .andExpect(jsonPath("$.recipeCode").value("RCP-AMOX-250-V3"))
                .andExpect(jsonPath("$.recipeVersion").value(3))
                .andExpect(jsonPath("$.targetBatchCount").value(2))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.batches[0].batchNumber").value("BAT-2026-0001-01"));

        verify(orderService).createOrder(any(OrderRequestDto.class));
    }

    @Test
    @DisplayName("POST /api/orders - Should return 400 Bad Request when mandatory fields are missing")
    void createOrder_MissingFields_Returns400() throws Exception {
        OrderRequestDto invalidDto = new OrderRequestDto(null, null, null, "", null, null, null);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.productId").exists())
                .andExpect(jsonPath("$.validationErrors.recipeId").exists())
                .andExpect(jsonPath("$.validationErrors.plannedQuantity").exists())
                .andExpect(jsonPath("$.validationErrors.quantityUnit").exists());

        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("POST /api/orders - Should return 400 Bad Request when planned end date earlier than start date")
    void createOrder_InvalidDates_Returns400() throws Exception {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto invalidDates = new OrderRequestDto(
                1L, 10L, new BigDecimal("250.0000"), "kg",
                today.plusDays(5), today.plusDays(1), null
        );

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDates)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.plannedEndDateValid").exists());

        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("POST /api/orders - Should return 400 Bad Request when planned start date is yesterday")
    void createOrder_StartDateYesterday_Returns400() throws Exception {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto pastStart = new OrderRequestDto(
                1L, 10L, new BigDecimal("250.0000"), "kg",
                today.minusDays(1), today.plusDays(3), null
        );

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pastStart)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.plannedStartDateValid")
                        .value("Planned start date cannot be earlier than today"));

        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("POST /api/orders - Should return 201 Created when planned start date is today")
    void createOrder_StartDateToday_Returns201() throws Exception {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto todayStart = new OrderRequestDto(
                1L, 10L, new BigDecimal("250.0000"), "kg",
                today, today.plusDays(2), null
        );
        when(orderService.createOrder(any(OrderRequestDto.class))).thenReturn(sampleResponseDto);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(todayStart)))
                .andExpect(status().isCreated());

        verify(orderService).createOrder(any(OrderRequestDto.class));
    }

    @Test
    @DisplayName("POST /api/orders - Should return 201 Created when planned start date is tomorrow")
    void createOrder_StartDateTomorrow_Returns201() throws Exception {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto tomorrowStart = new OrderRequestDto(
                1L, 10L, new BigDecimal("250.0000"), "kg",
                today.plusDays(1), today.plusDays(3), null
        );
        when(orderService.createOrder(any(OrderRequestDto.class))).thenReturn(sampleResponseDto);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tomorrowStart)))
                .andExpect(status().isCreated());

        verify(orderService).createOrder(any(OrderRequestDto.class));
    }

    @Test
    @DisplayName("POST /api/orders - Should return 400 Bad Request when end date is 1 day before start date")
    void createOrder_Nov15ToNov14InvalidDates_Returns400() throws Exception {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto invalidDates = new OrderRequestDto(
                1L, 10L, new BigDecimal("250.0000"), "kg",
                today.plusDays(5), today.plusDays(4), null
        );

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDates)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.plannedEndDateValid").exists());

        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("POST /api/orders - Should return 201 Created when start and end dates are same day")
    void createOrder_SameStartAndEndDate_Returns201() throws Exception {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto sameDayDates = new OrderRequestDto(
                1L, 10L, new BigDecimal("250.0000"), "kg",
                today.plusDays(5), today.plusDays(5), null
        );
        when(orderService.createOrder(any(OrderRequestDto.class))).thenReturn(sampleResponseDto);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sameDayDates)))
                .andExpect(status().isCreated());

        verify(orderService).createOrder(any(OrderRequestDto.class));
    }

    @Test
    @DisplayName("POST /api/orders - Should return 201 Created when end date is after start date")
    void createOrder_EndDateAfterStartDate_Returns201() throws Exception {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto validRange = new OrderRequestDto(
                1L, 10L, new BigDecimal("250.0000"), "kg",
                today.plusDays(5), today.plusDays(6), null
        );
        when(orderService.createOrder(any(OrderRequestDto.class))).thenReturn(sampleResponseDto);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRange)))
                .andExpect(status().isCreated());

        verify(orderService).createOrder(any(OrderRequestDto.class));
    }

    @Test
    @DisplayName("POST /api/orders - Should return 201 Created when only plannedStartDate is provided")
    void createOrder_OnlyStartDateProvided_Returns201() throws Exception {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto startOnly = new OrderRequestDto(
                1L, 10L, new BigDecimal("250.0000"), "kg",
                today.plusDays(5), null, null
        );
        when(orderService.createOrder(any(OrderRequestDto.class))).thenReturn(sampleResponseDto);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startOnly)))
                .andExpect(status().isCreated());

        verify(orderService).createOrder(any(OrderRequestDto.class));
    }

    @Test
    @DisplayName("POST /api/orders - Should return 201 Created when only plannedEndDate is provided (empty start date)")
    void createOrder_OnlyEndDateProvided_Returns201() throws Exception {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto endOnly = new OrderRequestDto(
                1L, 10L, new BigDecimal("250.0000"), "kg",
                null, today.plusDays(4), null
        );
        when(orderService.createOrder(any(OrderRequestDto.class))).thenReturn(sampleResponseDto);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(endOnly)))
                .andExpect(status().isCreated());

        verify(orderService).createOrder(any(OrderRequestDto.class));
    }

    @Test
    @DisplayName("POST /api/orders - Should return 201 Created when neither planned date is provided")
    void createOrder_NeitherDateProvided_Returns201() throws Exception {
        OrderRequestDto noDates = new OrderRequestDto(
                1L, 10L, new BigDecimal("250.0000"), "kg",
                null, null, null
        );
        when(orderService.createOrder(any(OrderRequestDto.class))).thenReturn(sampleResponseDto);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noDates)))
                .andExpect(status().isCreated());

        verify(orderService).createOrder(any(OrderRequestDto.class));
    }

    @Test
    @DisplayName("POST /api/orders - Should return 400 Bad Request when service throws InvalidOrderException")
    void createOrder_ServiceInvalidOrder_Returns400() throws Exception {
        when(orderService.createOrder(any(OrderRequestDto.class)))
                .thenThrow(new InvalidOrderException("Planned quantity must be an exact multiple of recipe batch size"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("exact multiple")));
    }

    @Test
    @DisplayName("POST /api/orders - Should return 404 Not Found when service throws ResourceNotFoundException")
    void createOrder_ServiceResourceNotFound_Returns404() throws Exception {
        when(orderService.createOrder(any(OrderRequestDto.class)))
                .thenThrow(new ResourceNotFoundException("Product not found with id: 1"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Product not found with id: 1"));
    }

    // -------------------------------------------------------------------------
    // 2. GET /api/orders - Retrieve Orders
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/orders - Should return all orders when no status filter is provided")
    void getAllOrders_NoFilter_Returns200() throws Exception {
        when(orderService.getAllOrders()).thenReturn(List.of(sampleResponseDto));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderNumber").value("MO-2026-0001"));

        verify(orderService).getAllOrders();
        verify(orderService, never()).getOrdersByStatus(any());
    }

    @Test
    @DisplayName("GET /api/orders?status=PLANNED - Should filter orders by status")
    void getAllOrders_WithStatusFilter_Returns200() throws Exception {
        when(orderService.getOrdersByStatus(OrderStatus.PLANNED)).thenReturn(List.of(sampleResponseDto));

        mockMvc.perform(get("/api/orders").param("status", "PLANNED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PLANNED"));

        verify(orderService).getOrdersByStatus(OrderStatus.PLANNED);
        verify(orderService, never()).getAllOrders();
    }

    @Test
    @DisplayName("GET /api/orders?status=INVALID - Should return 400 Bad Request for invalid enum")
    void getAllOrders_InvalidStatusFilter_Returns400() throws Exception {
        mockMvc.perform(get("/api/orders").param("status", "INVALID_STATUS"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // 3. GET /api/orders/{id} - Retrieve by ID
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/orders/{id} - Should return order and 200 OK when found")
    void getOrderById_Success_Returns200() throws Exception {
        when(orderService.getOrderById(1L)).thenReturn(sampleResponseDto);

        mockMvc.perform(get("/api/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.orderNumber").value("MO-2026-0001"));

        verify(orderService).getOrderById(1L);
    }

    @Test
    @DisplayName("GET /api/orders/{id} - Should return 404 Not Found when ID does not exist")
    void getOrderById_NotFound_Returns404() throws Exception {
        when(orderService.getOrderById(999L))
                .thenThrow(new ResourceNotFoundException("Manufacturing order not found with id: 999"));

        mockMvc.perform(get("/api/orders/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    // -------------------------------------------------------------------------
    // 4. GET /api/orders/number/{orderNumber} - Retrieve by Order Number
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/orders/number/{orderNumber} - Should return order and 200 OK when found")
    void getOrderByOrderNumber_Success_Returns200() throws Exception {
        when(orderService.getOrderByOrderNumber("MO-2026-0001")).thenReturn(sampleResponseDto);

        mockMvc.perform(get("/api/orders/number/MO-2026-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("MO-2026-0001"));

        verify(orderService).getOrderByOrderNumber("MO-2026-0001");
    }

    @Test
    @DisplayName("GET /api/orders/number/{orderNumber} - Should return 404 Not Found when number does not exist")
    void getOrderByOrderNumber_NotFound_Returns404() throws Exception {
        when(orderService.getOrderByOrderNumber("MO-UNKNOWN"))
                .thenThrow(new ResourceNotFoundException("Manufacturing order not found with order number: MO-UNKNOWN"));

        mockMvc.perform(get("/api/orders/number/MO-UNKNOWN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    // -------------------------------------------------------------------------
    // 5. GET /api/orders/product/{productId} - Retrieve by Product ID
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/orders/product/{productId} - Should return orders for product and 200 OK")
    void getOrdersByProductId_Success_Returns200() throws Exception {
        when(orderService.getOrdersByProductId(1L)).thenReturn(List.of(sampleResponseDto));

        mockMvc.perform(get("/api/orders/product/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(1L));

        verify(orderService).getOrdersByProductId(1L);
    }

    @Test
    @DisplayName("GET /api/orders/product/{productId} - Should return 404 Not Found when product does not exist")
    void getOrdersByProductId_NotFound_Returns404() throws Exception {
        when(orderService.getOrdersByProductId(999L))
                .thenThrow(new ResourceNotFoundException("Product not found with id: 999"));

        mockMvc.perform(get("/api/orders/product/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    // -------------------------------------------------------------------------
    // 6. PATCH /api/orders/{id}/status - Update Order Status
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /api/orders/{id}/status - Should update status and return 200 OK")
    void updateOrderStatus_Success_Returns200() throws Exception {
        OrderStatusUpdateDto statusDto = new OrderStatusUpdateDto(OrderStatus.RELEASED);
        OrderResponseDto updatedResponse = new OrderResponseDto(
                1L, "MO-2026-0001", 1L, "AMOX-250", "Amoxicillin Capsules",
                10L, "RCP-AMOX-250-V3", 3, new BigDecimal("500.0000"), "kg", 2,
                OrderStatus.RELEASED, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5),
                null, null, "Notes", List.of(sampleBatchDto), LocalDateTime.now(), LocalDateTime.now()
        );

        when(orderService.updateOrderStatus(eq(1L), any(OrderStatusUpdateDto.class))).thenReturn(updatedResponse);

        mockMvc.perform(patch("/api/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        verify(orderService).updateOrderStatus(eq(1L), any(OrderStatusUpdateDto.class));
    }

    @Test
    @DisplayName("PATCH /api/orders/{id}/status - Should return 400 Bad Request when status is null")
    void updateOrderStatus_NullStatus_Returns400() throws Exception {
        mockMvc.perform(patch("/api/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.status").exists());

        verify(orderService, never()).updateOrderStatus(any(), any());
    }

    @Test
    @DisplayName("PATCH /api/orders/{id}/status - Should return 400 Bad Request for invalid enum value")
    void updateOrderStatus_InvalidEnum_Returns400() throws Exception {
        mockMvc.perform(patch("/api/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"INVALID_STATUS\"}"))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).updateOrderStatus(any(), any());
    }

    @Test
    @DisplayName("PATCH /api/orders/{id}/status - Should return 400 Bad Request when transition is illegal")
    void updateOrderStatus_IllegalTransition_Returns400() throws Exception {
        OrderStatusUpdateDto statusDto = new OrderStatusUpdateDto(OrderStatus.CANCELLED);
        when(orderService.updateOrderStatus(eq(1L), any(OrderStatusUpdateDto.class)))
                .thenThrow(new InvalidOrderException("Cannot cancel manufacturing order once production has started"));

        mockMvc.perform(patch("/api/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("once production has started")));
    }

    // -------------------------------------------------------------------------
    // 7. GET /api/orders/{id}/batches - Retrieve Batches by Order ID
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/orders/{id}/batches - Should return batches and 200 OK")
    void getBatchesByOrderId_Success_Returns200() throws Exception {
        when(orderService.getBatchesByOrderId(1L)).thenReturn(List.of(sampleBatchDto));

        mockMvc.perform(get("/api/orders/1/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].batchNumber").value("BAT-2026-0001-01"))
                .andExpect(jsonPath("$[0].orderNumber").value("MO-2026-0001"));

        verify(orderService).getBatchesByOrderId(1L);
    }

    @Test
    @DisplayName("GET /api/orders/{id}/batches - Should return 404 Not Found when order does not exist")
    void getBatchesByOrderId_NotFound_Returns404() throws Exception {
        when(orderService.getBatchesByOrderId(999L))
                .thenThrow(new ResourceNotFoundException("Manufacturing order not found with id: 999"));

        mockMvc.perform(get("/api/orders/999/batches"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    // -------------------------------------------------------------------------
    // 8. Ignored Forged Actor & RBAC Enforcement
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/orders and PATCH /api/orders/{id}/status ignore forged X-Actor-Name and JSON actorName")
    void orderMutationsIgnoreForgedActorAndInvokeTrustedService() throws Exception {
        when(orderService.createOrder(any(OrderRequestDto.class))).thenReturn(sampleResponseDto);

        mockMvc.perform(post("/api/orders")
                        .header("X-Actor-Name", "Forged Planner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").value("MO-2026-0001"));
        verify(orderService).createOrder(any(OrderRequestDto.class));

        when(orderService.updateOrderStatus(eq(1L), any(OrderStatusUpdateDto.class)))
                .thenReturn(sampleResponseDto);
        mockMvc.perform(patch("/api/orders/1/status")
                        .header("X-Actor-Name", "Forged Lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RELEASED\",\"actorName\":\"Forged Body Actor\"}"))
                .andExpect(status().isOk());
        verify(orderService).updateOrderStatus(eq(1L), any(OrderStatusUpdateDto.class));
    }

    @Test
    @WithMockUser(username = "operator.user", roles = "OPERATOR")
    @DisplayName("OPERATOR can read orders and batches but receives 403 Forbidden on createOrder and updateOrderStatus")
    void operatorAllowedReadButForbiddenOrderMutations() throws Exception {
        when(orderService.getAllOrders()).thenReturn(List.of(sampleResponseDto));
        mockMvc.perform(get("/api/orders")).andExpect(status().isOk());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderStatusUpdateDto(OrderStatus.RELEASED))))
                .andExpect(status().isForbidden());

        verify(orderService, never()).createOrder(any());
        verify(orderService, never()).updateOrderStatus(any(), any());
    }
}

