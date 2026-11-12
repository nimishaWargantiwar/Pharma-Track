package com.pharmatrack.dto;

import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Comprehensive Batch Quality Dossier response containing manufacturing summary,
 * release eligibility assessment, QC check records, QA review history, and audit trail.
 */
public class BatchQualityDossierDto {

    private Long batchId;
    private String batchNumber;
    private BatchStatus batchStatus;
    private BatchQualityStatus qaStatus;
    private Long orderId;
    private String orderNumber;
    private Long productId;
    private String productCode;
    private String productName;
    private Long recipeId;
    private String recipeCode;
    private Integer recipeVersion;
    private BigDecimal batchSize;
    private String batchUnit;
    private BigDecimal actualYield;
    private LocalDate manufactureDate;
    private LocalDate expiryDate;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private long totalChecksCount;
    private long passedChecksCount;
    private long failedChecksCount;
    private long pendingChecksCount;
    private long requiredChecksCount;
    private boolean eligibleForRelease;
    private List<String> releaseBlockers;
    private List<QualityCheckResponseDto> qualityChecks;
    private List<BatchQaReviewResponseDto> qaReviews;
    private List<AuditLogResponseDto> auditLogs;
    private long totalDeviationsCount;
    private long openDeviationsCount;
    private List<DeviationSummaryDto> deviations;

    public BatchQualityDossierDto() {
        this.releaseBlockers = List.of();
        this.qualityChecks = List.of();
        this.qaReviews = List.of();
        this.auditLogs = List.of();
        this.deviations = List.of();
    }

    public BatchQualityDossierDto(Long batchId, String batchNumber, BatchStatus batchStatus,
                                  BatchQualityStatus qaStatus, Long orderId, String orderNumber,
                                  Long productId, String productCode, String productName,
                                  Long recipeId, String recipeCode, Integer recipeVersion,
                                  BigDecimal batchSize, String batchUnit, BigDecimal actualYield,
                                  LocalDate manufactureDate, LocalDate expiryDate,
                                  LocalDateTime startTime, LocalDateTime endTime,
                                  long totalChecksCount, long passedChecksCount,
                                  long failedChecksCount, long pendingChecksCount,
                                  long requiredChecksCount, boolean eligibleForRelease,
                                  List<String> releaseBlockers,
                                  List<QualityCheckResponseDto> qualityChecks,
                                  List<BatchQaReviewResponseDto> qaReviews,
                                  List<AuditLogResponseDto> auditLogs) {
        this(batchId, batchNumber, batchStatus, qaStatus, orderId, orderNumber, productId,
                productCode, productName, recipeId, recipeCode, recipeVersion, batchSize,
                batchUnit, actualYield, manufactureDate, expiryDate, startTime, endTime,
                totalChecksCount, passedChecksCount, failedChecksCount, pendingChecksCount,
                requiredChecksCount, eligibleForRelease, releaseBlockers, qualityChecks,
                qaReviews, auditLogs, 0L, 0L, List.of());
    }

    public BatchQualityDossierDto(Long batchId, String batchNumber, BatchStatus batchStatus,
                                  BatchQualityStatus qaStatus, Long orderId, String orderNumber,
                                  Long productId, String productCode, String productName,
                                  Long recipeId, String recipeCode, Integer recipeVersion,
                                  BigDecimal batchSize, String batchUnit, BigDecimal actualYield,
                                  LocalDate manufactureDate, LocalDate expiryDate,
                                  LocalDateTime startTime, LocalDateTime endTime,
                                  long totalChecksCount, long passedChecksCount,
                                  long failedChecksCount, long pendingChecksCount,
                                  long requiredChecksCount, boolean eligibleForRelease,
                                  List<String> releaseBlockers,
                                  List<QualityCheckResponseDto> qualityChecks,
                                  List<BatchQaReviewResponseDto> qaReviews,
                                  List<AuditLogResponseDto> auditLogs,
                                  long totalDeviationsCount,
                                  long openDeviationsCount,
                                  List<DeviationSummaryDto> deviations) {
        this.batchId = batchId;
        this.batchNumber = batchNumber;
        this.batchStatus = batchStatus;
        this.qaStatus = qaStatus;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.productId = productId;
        this.productCode = productCode;
        this.productName = productName;
        this.recipeId = recipeId;
        this.recipeCode = recipeCode;
        this.recipeVersion = recipeVersion;
        this.batchSize = batchSize;
        this.batchUnit = batchUnit;
        this.actualYield = actualYield;
        this.manufactureDate = manufactureDate;
        this.expiryDate = expiryDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.totalChecksCount = totalChecksCount;
        this.passedChecksCount = passedChecksCount;
        this.failedChecksCount = failedChecksCount;
        this.pendingChecksCount = pendingChecksCount;
        this.requiredChecksCount = requiredChecksCount;
        this.eligibleForRelease = eligibleForRelease;
        this.releaseBlockers = releaseBlockers != null ? releaseBlockers : List.of();
        this.qualityChecks = qualityChecks != null ? qualityChecks : List.of();
        this.qaReviews = qaReviews != null ? qaReviews : List.of();
        this.auditLogs = auditLogs != null ? auditLogs : List.of();
        this.totalDeviationsCount = totalDeviationsCount;
        this.openDeviationsCount = openDeviationsCount;
        this.deviations = deviations != null ? deviations : List.of();
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

    public long getTotalChecksCount() {
        return totalChecksCount;
    }

    public void setTotalChecksCount(long totalChecksCount) {
        this.totalChecksCount = totalChecksCount;
    }

    public long getPassedChecksCount() {
        return passedChecksCount;
    }

    public void setPassedChecksCount(long passedChecksCount) {
        this.passedChecksCount = passedChecksCount;
    }

    public long getFailedChecksCount() {
        return failedChecksCount;
    }

    public void setFailedChecksCount(long failedChecksCount) {
        this.failedChecksCount = failedChecksCount;
    }

    public long getPendingChecksCount() {
        return pendingChecksCount;
    }

    public void setPendingChecksCount(long pendingChecksCount) {
        this.pendingChecksCount = pendingChecksCount;
    }

    public long getRequiredChecksCount() {
        return requiredChecksCount;
    }

    public void setRequiredChecksCount(long requiredChecksCount) {
        this.requiredChecksCount = requiredChecksCount;
    }

    public boolean isEligibleForRelease() {
        return eligibleForRelease;
    }

    public void setEligibleForRelease(boolean eligibleForRelease) {
        this.eligibleForRelease = eligibleForRelease;
    }

    public List<String> getReleaseBlockers() {
        return releaseBlockers;
    }

    public void setReleaseBlockers(List<String> releaseBlockers) {
        this.releaseBlockers = releaseBlockers != null ? releaseBlockers : List.of();
    }

    public List<QualityCheckResponseDto> getQualityChecks() {
        return qualityChecks;
    }

    public void setQualityChecks(List<QualityCheckResponseDto> qualityChecks) {
        this.qualityChecks = qualityChecks != null ? qualityChecks : List.of();
    }

    public List<BatchQaReviewResponseDto> getQaReviews() {
        return qaReviews;
    }

    public void setQaReviews(List<BatchQaReviewResponseDto> qaReviews) {
        this.qaReviews = qaReviews != null ? qaReviews : List.of();
    }

    public List<AuditLogResponseDto> getAuditLogs() {
        return auditLogs;
    }

    public void setAuditLogs(List<AuditLogResponseDto> auditLogs) {
        this.auditLogs = auditLogs != null ? auditLogs : List.of();
    }

    public long getTotalDeviationsCount() {
        return totalDeviationsCount;
    }

    public void setTotalDeviationsCount(long totalDeviationsCount) {
        this.totalDeviationsCount = totalDeviationsCount;
    }

    public long getOpenDeviationsCount() {
        return openDeviationsCount;
    }

    public void setOpenDeviationsCount(long openDeviationsCount) {
        this.openDeviationsCount = openDeviationsCount;
    }

    public List<DeviationSummaryDto> getDeviations() {
        return deviations;
    }

    public void setDeviations(List<DeviationSummaryDto> deviations) {
        this.deviations = deviations != null ? deviations : List.of();
    }
}

