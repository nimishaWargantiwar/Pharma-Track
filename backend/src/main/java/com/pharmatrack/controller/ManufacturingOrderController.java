package com.pharmatrack.controller;

import com.pharmatrack.dto.BatchResponseDto;
import com.pharmatrack.dto.OrderRequestDto;
import com.pharmatrack.dto.OrderResponseDto;
import com.pharmatrack.dto.OrderStatusUpdateDto;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.service.ManufacturingOrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing endpoints for Manufacturing Order planning, lifecycle,
 * and child batch querying.
 */
@RestController
@RequestMapping("/api/orders")
public class ManufacturingOrderController {

    private final ManufacturingOrderService orderService;

    public ManufacturingOrderController(ManufacturingOrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Create a new manufacturing order and automatically generate planned batches.
     * POST /api/orders
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<OrderResponseDto> createOrder(@Valid @RequestBody OrderRequestDto requestDto) {
        OrderResponseDto createdOrder = orderService.createOrder(requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdOrder);
    }

    /**
     * Retrieve all manufacturing orders, optionally filtered by status.
     * GET /api/orders
     * GET /api/orders?status={status}
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<OrderResponseDto>> getAllOrders(@RequestParam(required = false) OrderStatus status) {
        List<OrderResponseDto> orders = (status != null)
                ? orderService.getOrdersByStatus(status)
                : orderService.getAllOrders();
        return ResponseEntity.ok(orders);
    }

    /**
     * Retrieve a single manufacturing order by its primary key ID.
     * GET /api/orders/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<OrderResponseDto> getOrderById(@PathVariable Long id) {
        OrderResponseDto order = orderService.getOrderById(id);
        return ResponseEntity.ok(order);
    }

    /**
     * Retrieve a single manufacturing order by its unique order number (e.g., MO-2026-0001).
     * GET /api/orders/number/{orderNumber}
     */
    @GetMapping("/number/{orderNumber}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<OrderResponseDto> getOrderByOrderNumber(@PathVariable String orderNumber) {
        OrderResponseDto order = orderService.getOrderByOrderNumber(orderNumber);
        return ResponseEntity.ok(order);
    }

    /**
     * Retrieve all manufacturing orders for a specific product.
     * GET /api/orders/product/{productId}
     */
    @GetMapping("/product/{productId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<OrderResponseDto>> getOrdersByProductId(@PathVariable Long productId) {
        List<OrderResponseDto> orders = orderService.getOrdersByProductId(productId);
        return ResponseEntity.ok(orders);
    }

    /**
     * Update the lifecycle status of a manufacturing order.
     * PATCH /api/orders/{id}/status
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<OrderResponseDto> updateOrderStatus(
            @PathVariable Long id,
            @Valid @RequestBody OrderStatusUpdateDto updateDto) {
        OrderResponseDto updatedOrder = orderService.updateOrderStatus(id, updateDto);
        return ResponseEntity.ok(updatedOrder);
    }

    /**
     * Retrieve all child production batches belonging to a specific order.
     * GET /api/orders/{id}/batches
     */
    @GetMapping("/{id}/batches")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<BatchResponseDto>> getBatchesByOrderId(@PathVariable Long id) {
        List<BatchResponseDto> batches = orderService.getBatchesByOrderId(id);
        return ResponseEntity.ok(batches);
    }
}

