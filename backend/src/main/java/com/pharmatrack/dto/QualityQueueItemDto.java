package com.pharmatrack.dto;

import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Summary item DTO for completed manufacturing batches listed in the QA Inspection Queue.
 */
public class QualityQueueItemDto {

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
    private long optionalChecksCount;
    private long requiredBlockingChecksCount;
    private long blockingChecksCount;
    private String lastReviewedBy;
    private LocalDateTime lastReviewedAt;
    private long totalDeviationsCount;
    private long openDeviationsCount;
    private boolean eligibleForRelease;
    private List<String> releaseBlockers;

    public QualityQueueItemDto() {
        this.releaseBlockers = List.of();
    }

    public QualityQueueItemDto(Long batchId, String batchNumber, BatchStatus batchStatus,
                               BatchQualityStatus qaStatus, Long orderId, String orderNumber,
                               Long productId, String productCode, String productName,
                               Long recipeId, String recipeCode, Integer recipeVersion,
                               BigDecimal batchSize, String batchUnit, BigDecimal actualYield,
                               LocalDate manufactureDate, LocalDate expiryDate,
                               LocalDateTime startTime, LocalDateTime endTime,
                               long totalChecksCount, long passedChecksCount,
                               long failedChecksCount, long pendingChecksCount,
                               long requiredChecksCount, long requiredBlockingChecksCount,
                               String lastReviewedBy, LocalDateTime lastReviewedAt) {
        this(batchId, batchNumber, batchStatus, qaStatus, orderId, orderNumber, productId,
                productCode, productName, recipeId, recipeCode, recipeVersion, batchSize,
                batchUnit, actualYield, manufactureDate, expiryDate, startTime, endTime,
                totalChecksCount, passedChecksCount, failedChecksCount, pendingChecksCount,
                requiredChecksCount, requiredBlockingChecksCount, lastReviewedBy, lastReviewedAt,
                0L, 0L);
    }

    public QualityQueueItemDto(Long batchId, String batchNumber, BatchStatus batchStatus,
                               BatchQualityStatus qaStatus, Long orderId, String orderNumber,
                               Long productId, String productCode, String productName,
                               Long recipeId, String recipeCode, Integer recipeVersion,
                               BigDecimal batchSize, String batchUnit, BigDecimal actualYield,
                               LocalDate manufactureDate, LocalDate expiryDate,
                               LocalDateTime startTime, LocalDateTime endTime,
                               long totalChecksCount, long passedChecksCount,
                               long failedChecksCount, long pendingChecksCount,
                               long requiredChecksCount, long requiredBlockingChecksCount,
                               String lastReviewedBy, LocalDateTime lastReviewedAt,
                               long totalDeviationsCount, long openDeviationsCount) {
        this(batchId, batchNumber, batchStatus, qaStatus, orderId, orderNumber, productId,
                productCode, productName, recipeId, recipeCode, recipeVersion, batchSize,
                batchUnit, actualYield, manufactureDate, expiryDate, startTime, endTime,
                totalChecksCount, passedChecksCount, failedChecksCount, pendingChecksCount,
                requiredChecksCount, Math.max(0L, totalChecksCount - requiredChecksCount),
                requiredBlockingChecksCount, failedChecksCount + pendingChecksCount,
                lastReviewedBy, lastReviewedAt, totalDeviationsCount, openDeviationsCount,
                false, List.of());
    }

    public QualityQueueItemDto(Long batchId, String batchNumber, BatchStatus batchStatus,
                               BatchQualityStatus qaStatus, Long orderId, String orderNumber,
                               Long productId, String productCode, String productName,
                               Long recipeId, String recipeCode, Integer recipeVersion,
                               BigDecimal batchSize, String batchUnit, BigDecimal actualYield,
                               LocalDate manufactureDate, LocalDate expiryDate,
                               LocalDateTime startTime, LocalDateTime endTime,
                               long totalChecksCount, long passedChecksCount,
                               long failedChecksCount, long pendingChecksCount,
                               long requiredChecksCount, long optionalChecksCount,
                               long requiredBlockingChecksCount, long blockingChecksCount,
                               String lastReviewedBy, LocalDateTime lastReviewedAt,
                               long totalDeviationsCount, long openDeviationsCount,
                               boolean eligibleForRelease, List<String> releaseBlockers) {
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
        this.optionalChecksCount = optionalChecksCount;
        this.requiredBlockingChecksCount = requiredBlockingChecksCount;
        this.blockingChecksCount = blockingChecksCount;
        this.lastReviewedBy = lastReviewedBy;
        this.lastReviewedAt = lastReviewedAt;
        this.totalDeviationsCount = totalDeviationsCount;
        this.openDeviationsCount = openDeviationsCount;
        this.eligibleForRelease = eligibleForRelease;
        this.releaseBlockers = releaseBlockers != null ? releaseBlockers : List.of();
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

    public long getRequiredBlockingChecksCount() {
        return requiredBlockingChecksCount;
    }

    public void setRequiredBlockingChecksCount(long requiredBlockingChecksCount) {
        this.requiredBlockingChecksCount = requiredBlockingChecksCount;
    }

    public long getOptionalChecksCount() {
        return optionalChecksCount;
    }

    public void setOptionalChecksCount(long optionalChecksCount) {
        this.optionalChecksCount = optionalChecksCount;
    }

    public long getBlockingChecksCount() {
        return blockingChecksCount;
    }

    public void setBlockingChecksCount(long blockingChecksCount) {
        this.blockingChecksCount = blockingChecksCount;
    }

    public String getLastReviewedBy() {
        return lastReviewedBy;
    }

    public void setLastReviewedBy(String lastReviewedBy) {
        this.lastReviewedBy = lastReviewedBy;
    }

    public LocalDateTime getLastReviewedAt() {
        return lastReviewedAt;
    }

    public void setLastReviewedAt(LocalDateTime lastReviewedAt) {
        this.lastReviewedAt = lastReviewedAt;
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
}

