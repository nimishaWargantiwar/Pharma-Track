package com.pharmatrack.dto;

import com.pharmatrack.entity.OrderStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Payload DTO for updating a Manufacturing Order's lifecycle status.
 */
public class OrderStatusUpdateDto {

    @NotNull(message = "Order status is mandatory")
    private OrderStatus status;

    public OrderStatusUpdateDto() {
    }

    public OrderStatusUpdateDto(OrderStatus status) {
        this.status = status;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }
}

