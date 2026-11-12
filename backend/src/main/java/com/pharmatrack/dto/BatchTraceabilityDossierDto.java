package com.pharmatrack.dto;

import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only Batch Traceability Dossier connecting manufacturing execution,
 * raw material consumption, electronic batch records (eBR), quality control (QC),
 * quality assurance (QA) disposition, deviations, CAPA actions, and audit trail.
 */
public class BatchTraceabilityDossierDto {

    // -------------------------------------------------------------------------
    // A. Batch Overview
    // -------------------------------------------------------------------------
    private Long batchId;
    private String batchNumber;
    private String qrCode;
    private Long orderId;
    private String orderNumber;
    private OrderStatus orderStatus;
    private Long productId;
    private String productCode;
    private String productName;
    private Long recipeId;
    private String recipeCode;
    private Integer recipeVersion;
    private BigDecimal batchSize;
    private String batchUnit;
    private BigDecimal actualYield;
    private BatchStatus batchStatus;
    private BatchQualityStatus qaStatus;
    private LocalDate manufactureDate;
    private LocalDate expiryDate;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    // Completeness Indicator
    private int completenessPercent;
    private String completenessStatus; // e.g. "COMPLETE", "IN_PROGRESS", "NEEDS_ATTENTION", "PLANNED"
    private List<String> completenessHighlights = new ArrayList<>();

    // -------------------------------------------------------------------------
    // B. Manufacturing Traceability
    // -------------------------------------------------------------------------
    private BigDecimal plannedQuantity;
    private Integer targetBatchCount;
    private LocalDate orderPlannedStartDate;
    private LocalDate orderPlannedEndDate;
    private LocalDateTime orderActualStartDate;
    private LocalDateTime orderActualEndDate;

    // -------------------------------------------------------------------------
    // C. Raw Material Traceability
    // -------------------------------------------------------------------------
    private List<OrderMaterialRequirementDto> materialRequirements = new ArrayList<>();
    private List<StockMovementResponseDto> materialMovements = new ArrayList<>();

    // -------------------------------------------------------------------------
    // D. Electronic Batch Record (eBR)
    // -------------------------------------------------------------------------
    private List<BatchExecutionStepResponseDto> executionSteps = new ArrayList<>();

    // -------------------------------------------------------------------------
    // E. Quality Control (QC)
    // -------------------------------------------------------------------------
    private List<QualityCheckResponseDto> qualityChecks = new ArrayList<>();

    // -------------------------------------------------------------------------
    // F. Quality Assurance (QA)
    // -------------------------------------------------------------------------
    private List<BatchQaReviewResponseDto> qaReviews = new ArrayList<>();

    // -------------------------------------------------------------------------
    // G. Deviations & CAPA
    // -------------------------------------------------------------------------
    private List<DeviationDetailDto> deviations = new ArrayList<>();

    // -------------------------------------------------------------------------
    // H. Audit History
    // -------------------------------------------------------------------------
    private List<AuditLogResponseDto> auditHistory = new ArrayList<>();

    public BatchTraceabilityDossierDto() {
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public String getBatchNumber() {
        return batchNumber;
    }

    public void setBatchNumber(String batchNumber) {
        this.batchNumber = batchNumber;
    }

    public String getQrCode() {
        return qrCode;
    }

    public void setQrCode(String qrCode) {
        this.qrCode = qrCode;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(OrderStatus orderStatus) {
        this.orderStatus = orderStatus;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public Long getRecipeId() {
        return recipeId;
    }

    public void setRecipeId(Long recipeId) {
        this.recipeId = recipeId;
    }

    public String getRecipeCode() {
        return recipeCode;
    }

    public void setRecipeCode(String recipeCode) {
        this.recipeCode = recipeCode;
    }

    public Integer getRecipeVersion() {
        return recipeVersion;
    }

    public void setRecipeVersion(Integer recipeVersion) {
        this.recipeVersion = recipeVersion;
    }

    public BigDecimal getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(BigDecimal batchSize) {
        this.batchSize = batchSize;
    }

    public String getBatchUnit() {
        return batchUnit;
    }

    public void setBatchUnit(String batchUnit) {
        this.batchUnit = batchUnit;
    }

    public BigDecimal getActualYield() {
        return actualYield;
    }

    public void setActualYield(BigDecimal actualYield) {
        this.actualYield = actualYield;
    }

    public BatchStatus getBatchStatus() {
        return batchStatus;
    }

    public void setBatchStatus(BatchStatus batchStatus) {
        this.batchStatus = batchStatus;
    }

    public BatchQualityStatus getQaStatus() {
        return qaStatus;
    }

    public void setQaStatus(BatchQualityStatus qaStatus) {
        this.qaStatus = qaStatus;
    }

    public LocalDate getManufactureDate() {
        return manufactureDate;
    }

    public void setManufactureDate(LocalDate manufactureDate) {
        this.manufactureDate = manufactureDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public int getCompletenessPercent() {
        return completenessPercent;
    }

    public void setCompletenessPercent(int completenessPercent) {
        this.completenessPercent = completenessPercent;
    }

    public String getCompletenessStatus() {
        return completenessStatus;
    }

    public void setCompletenessStatus(String completenessStatus) {
        this.completenessStatus = completenessStatus;
    }

    public List<String> getCompletenessHighlights() {
        return completenessHighlights;
    }

    public void setCompletenessHighlights(List<String> completenessHighlights) {
        this.completenessHighlights = completenessHighlights;
    }

    public BigDecimal getPlannedQuantity() {
        return plannedQuantity;
    }

    public void setPlannedQuantity(BigDecimal plannedQuantity) {
        this.plannedQuantity = plannedQuantity;
    }

    public Integer getTargetBatchCount() {
        return targetBatchCount;
    }

    public void setTargetBatchCount(Integer targetBatchCount) {
        this.targetBatchCount = targetBatchCount;
    }

    public LocalDate getOrderPlannedStartDate() {
        return orderPlannedStartDate;
    }

    public void setOrderPlannedStartDate(LocalDate orderPlannedStartDate) {
        this.orderPlannedStartDate = orderPlannedStartDate;
    }

    public LocalDate getOrderPlannedEndDate() {
        return orderPlannedEndDate;
    }

    public void setOrderPlannedEndDate(LocalDate orderPlannedEndDate) {
        this.orderPlannedEndDate = orderPlannedEndDate;
    }

    public LocalDateTime getOrderActualStartDate() {
        return orderActualStartDate;
    }

    public void setOrderActualStartDate(LocalDateTime orderActualStartDate) {
        this.orderActualStartDate = orderActualStartDate;
    }

    public LocalDateTime getOrderActualEndDate() {
        return orderActualEndDate;
    }

    public void setOrderActualEndDate(LocalDateTime orderActualEndDate) {
        this.orderActualEndDate = orderActualEndDate;
    }

    public List<OrderMaterialRequirementDto> getMaterialRequirements() {
        return materialRequirements;
    }

    public void setMaterialRequirements(List<OrderMaterialRequirementDto> materialRequirements) {
        this.materialRequirements = materialRequirements;
    }

    public List<StockMovementResponseDto> getMaterialMovements() {
        return materialMovements;
    }

    public void setMaterialMovements(List<StockMovementResponseDto> materialMovements) {
        this.materialMovements = materialMovements;
    }

    public List<BatchExecutionStepResponseDto> getExecutionSteps() {
        return executionSteps;
    }

    public void setExecutionSteps(List<BatchExecutionStepResponseDto> executionSteps) {
        this.executionSteps = executionSteps;
    }

    public List<QualityCheckResponseDto> getQualityChecks() {
        return qualityChecks;
    }

    public void setQualityChecks(List<QualityCheckResponseDto> qualityChecks) {
        this.qualityChecks = qualityChecks;
    }

    public List<BatchQaReviewResponseDto> getQaReviews() {
        return qaReviews;
    }

    public void setQaReviews(List<BatchQaReviewResponseDto> qaReviews) {
        this.qaReviews = qaReviews;
    }

    public List<DeviationDetailDto> getDeviations() {
        return deviations;
    }

    public void setDeviations(List<DeviationDetailDto> deviations) {
        this.deviations = deviations;
    }

    public List<AuditLogResponseDto> getAuditHistory() {
        return auditHistory;
    }

    public void setAuditHistory(List<AuditLogResponseDto> auditHistory) {
        this.auditHistory = auditHistory;
    }
}
