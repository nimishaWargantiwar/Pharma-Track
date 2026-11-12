package com.pharmatrack.dto;

import com.pharmatrack.entity.BatchQualityStatus;

import java.time.LocalDateTime;

/**
 * Response payload DTO representing a historical QA disposition review decision.
 */
public class BatchQaReviewResponseDto {

    private Long id;
    private Long batchId;
    private String batchNumber;
    private BatchQualityStatus previousStatus;
    private BatchQualityStatus decisionStatus;
    private String reviewerName;
    private String rationale;
    private LocalDateTime reviewedAt;
    private long totalChecksCount;
    private long passedChecksCount;
    private long failedChecksCount;
    private long pendingChecksCount;

    public BatchQaReviewResponseDto() {
    }

    public BatchQaReviewResponseDto(Long id, Long batchId, String batchNumber,
                                    BatchQualityStatus previousStatus, BatchQualityStatus decisionStatus,
                                    String reviewerName, String rationale, LocalDateTime reviewedAt,
                                    long totalChecksCount, long passedChecksCount,
                                    long failedChecksCount, long pendingChecksCount) {
        this.id = id;
        this.batchId = batchId;
        this.batchNumber = batchNumber;
        this.previousStatus = previousStatus;
        this.decisionStatus = decisionStatus;
        this.reviewerName = reviewerName;
        this.rationale = rationale;
        this.reviewedAt = reviewedAt;
        this.totalChecksCount = totalChecksCount;
        this.passedChecksCount = passedChecksCount;
        this.failedChecksCount = failedChecksCount;
        this.pendingChecksCount = pendingChecksCount;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public BatchQualityStatus getPreviousStatus() {
        return previousStatus;
    }

    public void setPreviousStatus(BatchQualityStatus previousStatus) {
        this.previousStatus = previousStatus;
    }

    public BatchQualityStatus getDecisionStatus() {
        return decisionStatus;
    }

    public void setDecisionStatus(BatchQualityStatus decisionStatus) {
        this.decisionStatus = decisionStatus;
    }

    public String getReviewerName() {
        return reviewerName;
    }

    public void setReviewerName(String reviewerName) {
        this.reviewerName = reviewerName;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
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
}

