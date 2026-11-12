package com.pharmatrack.controller;

import com.pharmatrack.dto.BatchResponseDto;
import com.pharmatrack.dto.BatchStatusUpdateDto;
import com.pharmatrack.service.ManufacturingOrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing endpoints for physical production Batch queries
 * and shop-floor status transitions.
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final ManufacturingOrderService orderService;

    public BatchController(ManufacturingOrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Retrieve a production batch by its numeric primary key ID.
     * GET /api/batches/{id}
     */
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BatchResponseDto> getBatchById(@PathVariable Long id) {
        BatchResponseDto batch = orderService.getBatchById(id);
        return ResponseEntity.ok(batch);
    }

    /**
     * Retrieve a production batch by its unique batch number (e.g., BAT-2026-0001-01).
     * GET /api/batches/number/{batchNumber}
     */
    @GetMapping("/number/{batchNumber}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BatchResponseDto> getBatchByBatchNumber(@PathVariable String batchNumber) {
        BatchResponseDto batch = orderService.getBatchByBatchNumber(batchNumber);
        return ResponseEntity.ok(batch);
    }

    /**
     * Update the lifecycle execution status of a production batch (e.g., IN_PROGRESS, COMPLETED, CANCELLED).
     * When completing a batch, a positive actual yield is mandatory.
     * PATCH /api/batches/{id}/status
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('PRODUCTION_SUPERVISOR') or (#updateDto != null and ((#updateDto.status == null and hasAnyRole('ADMIN', 'OPERATOR')) or (#updateDto.status?.name() == 'CANCELLED' and hasRole('ADMIN')) or ((#updateDto.status?.name() == 'IN_PROGRESS' or #updateDto.status?.name() == 'COMPLETED') and hasRole('OPERATOR'))))")
    public ResponseEntity<BatchResponseDto> updateBatchStatus(
            @PathVariable Long id,
            @Valid @RequestBody BatchStatusUpdateDto updateDto) {
        BatchResponseDto updatedBatch = orderService.updateBatchStatus(id, updateDto);
        return ResponseEntity.ok(updatedBatch);
    }
}

