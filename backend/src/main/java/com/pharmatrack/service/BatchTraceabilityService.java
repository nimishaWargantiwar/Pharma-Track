package com.pharmatrack.service;

import com.pharmatrack.dto.AuditLogResponseDto;
import com.pharmatrack.dto.BatchExecutionStepResponseDto;
import com.pharmatrack.dto.BatchQaReviewResponseDto;
import com.pharmatrack.dto.BatchTraceabilityDossierDto;
import com.pharmatrack.dto.DeviationDetailDto;
import com.pharmatrack.dto.OrderMaterialRequirementDto;
import com.pharmatrack.dto.OrderMaterialRequirementsResponseDto;
import com.pharmatrack.dto.QualityCheckResponseDto;
import com.pharmatrack.dto.StockMovementResponseDto;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchExecutionStep;
import com.pharmatrack.entity.BatchQaReview;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.QualityCheck;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.StockMovement;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.BatchExecutionStepRepository;
import com.pharmatrack.repository.BatchQaReviewRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.QualityCheckRepository;
import com.pharmatrack.repository.StockMovementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Service orchestrating the complete read-only Batch Traceability Dossier.
 * Connects Manufacturing Order, Raw Material consumption, eBR Execution Steps,
 * QC Inspections, QA Disposition Reviews, Deviations & CAPA, and append-only Audit History.
 */
@Service
public class BatchTraceabilityService {

    private final BatchRepository batchRepository;
    private final InventoryService inventoryService;
    private final StockMovementRepository stockMovementRepository;
    private final BatchExecutionStepRepository executionStepRepository;
    private final QualityCheckRepository qualityCheckRepository;
    private final BatchQaReviewRepository qaReviewRepository;
    private final DeviationService deviationService;
    private final AuditTrailService auditTrailService;

    public BatchTraceabilityService(BatchRepository batchRepository,
                                    InventoryService inventoryService,
                                    StockMovementRepository stockMovementRepository,
                                    BatchExecutionStepRepository executionStepRepository,
                                    QualityCheckRepository qualityCheckRepository,
                                    BatchQaReviewRepository qaReviewRepository,
                                    DeviationService deviationService,
                                    AuditTrailService auditTrailService) {
        this.batchRepository = batchRepository;
        this.inventoryService = inventoryService;
        this.stockMovementRepository = stockMovementRepository;
        this.executionStepRepository = executionStepRepository;
        this.qualityCheckRepository = qualityCheckRepository;
        this.qaReviewRepository = qaReviewRepository;
        this.deviationService = deviationService;
        this.auditTrailService = auditTrailService;
    }

    /**
     * Retrieve the comprehensive traceability dossier by batch primary key ID.
     */
    @Transactional(readOnly = true)
    public BatchTraceabilityDossierDto getTraceabilityDossier(Long batchId) {
        if (batchId == null) {
            throw new ResourceNotFoundException("Batch not found with id: null");
        }
        Batch batch = batchRepository.findByIdWithDetails(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batchId));

        return assembleDossier(batch);
    }

    /**
     * Retrieve the comprehensive traceability dossier by batch number or QR code identifier.
     */
    @Transactional(readOnly = true)
    public BatchTraceabilityDossierDto getTraceabilityDossierByIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new ResourceNotFoundException("Batch identifier must not be blank");
        }
        String trimmed = identifier.trim();
        Batch batch = batchRepository.findByIdentifierWithDetails(trimmed)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with identifier: " + trimmed));

        return assembleDossier(batch);
    }

    private BatchTraceabilityDossierDto assembleDossier(Batch batch) {
        BatchTraceabilityDossierDto dossier = new BatchTraceabilityDossierDto();

        // ---------------------------------------------------------------------
        // Section A: Batch Overview
        // ---------------------------------------------------------------------
        dossier.setBatchId(batch.getId());
        dossier.setBatchNumber(batch.getBatchNumber());
        dossier.setQrCode(batch.getQrCode() != null ? batch.getQrCode() : "QR-PT-" + batch.getBatchNumber());
        dossier.setBatchSize(batch.getBatchSize());
        dossier.setBatchUnit(batch.getBatchUnit());
        dossier.setActualYield(batch.getActualYield());
        dossier.setBatchStatus(batch.getStatus());
        dossier.setQaStatus(batch.getQaStatus());
        dossier.setManufactureDate(batch.getManufactureDate());
        dossier.setExpiryDate(batch.getExpiryDate());
        dossier.setStartTime(batch.getStartTime());
        dossier.setEndTime(batch.getEndTime());

        // ---------------------------------------------------------------------
        // Section B: Manufacturing Traceability
        // ---------------------------------------------------------------------
        ManufacturingOrder order = batch.getOrder();
        if (order != null) {
            dossier.setOrderId(order.getId());
            dossier.setOrderNumber(order.getOrderNumber());
            dossier.setOrderStatus(order.getStatus());
            dossier.setPlannedQuantity(order.getPlannedQuantity());
            dossier.setTargetBatchCount(order.getTargetBatchCount());
            dossier.setOrderPlannedStartDate(order.getPlannedStartDate());
            dossier.setOrderPlannedEndDate(order.getPlannedEndDate());
            dossier.setOrderActualStartDate(order.getActualStartDate());
            dossier.setOrderActualEndDate(order.getActualEndDate());

            Product product = order.getProduct();
            if (product != null) {
                dossier.setProductId(product.getId());
                dossier.setProductCode(product.getProductCode());
                dossier.setProductName(product.getProductName());
            }

            Recipe recipe = order.getRecipe();
            if (recipe != null) {
                dossier.setRecipeId(recipe.getId());
                dossier.setRecipeCode(recipe.getRecipeCode());
                dossier.setRecipeVersion(order.getRecipeVersion() != null ? order.getRecipeVersion() : recipe.getVersion());
            }
        }

        // ---------------------------------------------------------------------
        // Section C: Raw Material Traceability (Requirements vs Actual Issues)
        // ---------------------------------------------------------------------
        if (order != null) {
            try {
                OrderMaterialRequirementsResponseDto reqDto = inventoryService.getOrderMaterialRequirements(order.getId());
                if (reqDto != null && reqDto.getRequirements() != null) {
                    dossier.setMaterialRequirements(reqDto.getRequirements());
                }
            } catch (Exception e) {
                // If inventory requirements fail, keep empty list
            }

            List<StockMovement> movements = stockMovementRepository
                    .findByBatchIdOrManufacturingOrderIdWithDetails(batch.getId(), order.getId());
            List<StockMovementResponseDto> movementDtos = safeList(movements).stream()
                    .map(this::mapStockMovementDto)
                    .toList();
            dossier.setMaterialMovements(movementDtos);
        }

        // ---------------------------------------------------------------------
        // Section D: Electronic Batch Record (eBR Execution Steps)
        // ---------------------------------------------------------------------
        List<BatchExecutionStep> steps = executionStepRepository.findByBatchIdOrderByStepNumberAsc(batch.getId());
        List<BatchExecutionStepResponseDto> stepDtos = safeList(steps).stream()
                .map(s -> new BatchExecutionStepResponseDto(
                        s.getId(),
                        s.getStepNumber(),
                        s.getOperationName(),
                        s.getInstructions(),
                        s.getStatus(),
                        s.getActualValue(),
                        s.getObservations(),
                        s.getExecutedBy(),
                        s.getStartedAt(),
                        s.getCompletedAt()
                ))
                .toList();
        dossier.setExecutionSteps(stepDtos);

        // ---------------------------------------------------------------------
        // Section E: Quality Control (QC Inspections)
        // ---------------------------------------------------------------------
        List<QualityCheck> checks = qualityCheckRepository.findByBatchIdOrderByCheckedAtAscIdAsc(batch.getId());
        List<QualityCheckResponseDto> checkDtos = safeList(checks).stream()
                .map(QualityService::toQualityCheckDto)
                .toList();
        dossier.setQualityChecks(checkDtos);

        // ---------------------------------------------------------------------
        // Section F: Quality Assurance (QA Disposition Reviews)
        // ---------------------------------------------------------------------
        List<BatchQaReview> reviews = qaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(batch.getId());
        List<BatchQaReviewResponseDto> reviewDtos = safeList(reviews).stream()
                .map(QualityService::toQaReviewDto)
                .toList();
        dossier.setQaReviews(reviewDtos);

        // ---------------------------------------------------------------------
        // Section G: Deviations & CAPA
        // ---------------------------------------------------------------------
        List<DeviationDetailDto> deviationDtos = deviationService.getDeviationDetailsForBatch(batch.getId());
        dossier.setDeviations(safeList(deviationDtos));

        // ---------------------------------------------------------------------
        // Section H: Audit History (Chronological)
        // ---------------------------------------------------------------------
        List<AuditLogResponseDto> auditLogs = auditTrailService.getAuditLogs(batch.getId(), null, null);
        dossier.setAuditHistory(safeList(auditLogs));

        // ---------------------------------------------------------------------
        // Traceability Completeness Calculation
        // ---------------------------------------------------------------------
        calculateCompleteness(dossier);

        return dossier;
    }

    private void calculateCompleteness(BatchTraceabilityDossierDto dossier) {
        int totalScore = 0;
        List<String> highlights = new ArrayList<>();

        // 1. Manufacturing Order & Master Data (20 pts)
        if (dossier.getOrderId() != null && dossier.getProductCode() != null && dossier.getRecipeCode() != null) {
            totalScore += 20;
            highlights.add("Manufacturing Order & Recipe configuration linked");
        }

        // 2. Raw Material Issues (20 pts)
        long issuedMovementsCount = dossier.getMaterialMovements().stream()
                .filter(m -> m.getQuantityDelta() != null && m.getQuantityDelta().compareTo(BigDecimal.ZERO) < 0)
                .count();
        boolean requirementsFulfilled = !dossier.getMaterialRequirements().isEmpty() &&
                dossier.getMaterialRequirements().stream().allMatch(OrderMaterialRequirementDto::isFulfilled);

        if (requirementsFulfilled || issuedMovementsCount > 0) {
            totalScore += 20;
            highlights.add(issuedMovementsCount + " material issue transaction(s) recorded against batch");
        } else {
            highlights.add("No material issues linked to batch yet");
        }

        // 3. eBR Execution Steps (20 pts)
        long totalSteps = dossier.getExecutionSteps().size();
        long completedSteps = dossier.getExecutionSteps().stream()
                .filter(s -> s.getStatus() == ExecutionStepStatus.COMPLETED)
                .count();

        if (totalSteps > 0 && completedSteps == totalSteps) {
            totalScore += 20;
            highlights.add("All " + totalSteps + " eBR execution step(s) completed");
        } else if (totalSteps > 0) {
            int stepScore = (int) Math.round((20.0 * completedSteps) / totalSteps);
            totalScore += stepScore;
            highlights.add(completedSteps + " of " + totalSteps + " eBR step(s) completed");
        } else {
            highlights.add("Execution steps not initialized");
        }

        // 4. Quality Control (20 pts)
        long totalQc = dossier.getQualityChecks().size();
        long passedQc = dossier.getQualityChecks().stream()
                .filter(q -> q.getResult() == QualityCheckResult.PASS)
                .count();
        long failedQc = dossier.getQualityChecks().stream()
                .filter(q -> q.getResult() == QualityCheckResult.FAIL)
                .count();

        if (totalQc > 0 && passedQc == totalQc) {
            totalScore += 20;
            highlights.add("All " + totalQc + " QC inspection(s) passed");
        } else if (totalQc > 0) {
            int qcScore = (int) Math.round((20.0 * passedQc) / totalQc);
            totalScore += qcScore;
            highlights.add(passedQc + " passed, " + failedQc + " failed of " + totalQc + " QC check(s)");
        } else {
            highlights.add("No QC inspections recorded");
        }

        // 5. QA Disposition & Deviations (20 pts)
        long openDeviations = dossier.getDeviations().stream()
                .filter(d -> d.getStatus() != DeviationStatus.CLOSED)
                .count();

        boolean qaDispositioned = !dossier.getQaReviews().isEmpty();
        if (qaDispositioned && openDeviations == 0) {
            totalScore += 20;
            highlights.add("QA disposition recorded: " + dossier.getQaReviews().get(0).getDecisionStatus());
        } else if (qaDispositioned) {
            totalScore += 10;
            highlights.add("QA disposition recorded, with " + openDeviations + " open deviation(s)");
        } else if (openDeviations == 0) {
            totalScore += 10;
            highlights.add("No open deviations; awaiting QA disposition");
        } else {
            highlights.add(openDeviations + " open deviation(s) pending resolution");
        }

        dossier.setCompletenessPercent(Math.min(100, Math.max(0, totalScore)));
        if (totalScore >= 95) {
            dossier.setCompletenessStatus("COMPLETE");
        } else if (totalScore >= 50) {
            dossier.setCompletenessStatus("IN_PROGRESS");
        } else if (totalScore > 0) {
            dossier.setCompletenessStatus("PARTIAL");
        } else {
            dossier.setCompletenessStatus("PLANNED");
        }
        dossier.setCompletenessHighlights(highlights);
    }

    private StockMovementResponseDto mapStockMovementDto(StockMovement sm) {
        StockMovementResponseDto dto = new StockMovementResponseDto();
        dto.setId(sm.getId());
        dto.setMovementNumber(sm.getMovementNumber());
        dto.setReferenceNumber(sm.getReferenceNumber());
        dto.setMovementType(sm.getMovementType());
        if (sm.getRawMaterial() != null) {
            dto.setRawMaterialId(sm.getRawMaterial().getId());
            dto.setMaterialCode(sm.getRawMaterial().getMaterialCode());
            dto.setMaterialName(sm.getRawMaterial().getMaterialName());
        }
        if (sm.getMaterialLot() != null) {
            dto.setMaterialLotId(sm.getMaterialLot().getId());
            dto.setLotNumber(sm.getMaterialLot().getLotNumber());
        }
        if (sm.getWarehouse() != null) {
            dto.setWarehouseId(sm.getWarehouse().getId());
            dto.setWarehouseCode(sm.getWarehouse().getWarehouseCode());
            dto.setWarehouseName(sm.getWarehouse().getWarehouseName());
        }
        if (sm.getStorageLocation() != null) {
            dto.setStorageLocationId(sm.getStorageLocation().getId());
            dto.setLocationCode(sm.getStorageLocation().getLocationCode());
            dto.setZoneName(sm.getStorageLocation().getZoneName());
        }
        if (sm.getManufacturingOrder() != null) {
            dto.setManufacturingOrderId(sm.getManufacturingOrder().getId());
            dto.setOrderNumber(sm.getManufacturingOrder().getOrderNumber());
        }
        if (sm.getBatch() != null) {
            dto.setBatchId(sm.getBatch().getId());
            dto.setBatchNumber(sm.getBatch().getBatchNumber());
        }
        dto.setQuantityDelta(sm.getQuantityDelta());
        dto.setQuantityBefore(sm.getQuantityBefore());
        dto.setQuantityAfter(sm.getQuantityAfter());
        dto.setUnit(sm.getUnit());
        dto.setSupplierName(sm.getSupplierName());
        dto.setSupplierReference(sm.getSupplierReference());
        dto.setReason(sm.getReason());
        dto.setPerformedBy(sm.getPerformedBy());
        dto.setOccurredAt(sm.getOccurredAt());
        return dto;
    }

    private static <T> List<T> safeList(List<T> list) {
        return list != null ? list : List.of();
    }
}

