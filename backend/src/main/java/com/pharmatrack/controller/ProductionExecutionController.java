package com.pharmatrack.controller;

import com.pharmatrack.dto.BatchExecutionQueueItemDto;
import com.pharmatrack.dto.BatchExecutionStepResponseDto;
import com.pharmatrack.dto.BatchExecutionWorkspaceDto;
import com.pharmatrack.dto.CompleteExecutionStepRequestDto;
import com.pharmatrack.service.ProductionExecutionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing endpoints for shop-floor Production Execution queue,
 * batch execution workspace retrieval, and operation-level step transitions.
 */
@RestController
@RequestMapping("/api/batches")
public class ProductionExecutionController {

    private final ProductionExecutionService executionService;

    public ProductionExecutionController(ProductionExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * Retrieve active production batches eligible for shop-floor execution.
     * GET /api/batches/execution
     */
    @GetMapping("/execution")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<BatchExecutionQueueItemDto>> getExecutionQueue() {
        List<BatchExecutionQueueItemDto> queue = executionService.getExecutionQueue();
        return ResponseEntity.ok(queue);
    }

    /**
     * Retrieve the execution workspace and ordered execution steps for a specific batch.
     * GET /api/batches/{batchId}/steps
     */
    @GetMapping("/{batchId}/steps")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BatchExecutionWorkspaceDto> getBatchExecutionWorkspace(@PathVariable Long batchId) {
        BatchExecutionWorkspaceDto workspace = executionService.getBatchExecutionWorkspace(batchId);
        return ResponseEntity.ok(workspace);
    }

    /**
     * Explicitly initialize (or idempotently return existing) execution steps
     * for an already IN_PROGRESS production batch.
     * POST /api/batches/{batchId}/steps/initialize
     */
    @PostMapping("/{batchId}/steps/initialize")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<BatchExecutionWorkspaceDto> initializeExecutionSteps(@PathVariable Long batchId) {
        BatchExecutionWorkspaceDto workspace = executionService.initializeExecutionSteps(batchId);
        return ResponseEntity.ok(workspace);
    }

    /**
     * Start a pending execution step within an active production batch.
     * PATCH /api/batches/{batchId}/steps/{stepId}/start
     */
    @PatchMapping("/{batchId}/steps/{stepId}/start")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<BatchExecutionStepResponseDto> startExecutionStep(
            @PathVariable Long batchId,
            @PathVariable Long stepId) {
        BatchExecutionStepResponseDto updatedStep = executionService.startExecutionStep(batchId, stepId);
        return ResponseEntity.ok(updatedStep);
    }

    /**
     * Complete an in-progress execution step with actual recorded value and optional observations.
     * PATCH /api/batches/{batchId}/steps/{stepId}/complete
     */
    @PatchMapping("/{batchId}/steps/{stepId}/complete")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<BatchExecutionStepResponseDto> completeExecutionStep(
            @PathVariable Long batchId,
            @PathVariable Long stepId,
            @Valid @RequestBody CompleteExecutionStepRequestDto requestDto) {
        BatchExecutionStepResponseDto completedStep = executionService.completeExecutionStep(batchId, stepId, requestDto);
        return ResponseEntity.ok(completedStep);
    }
}

