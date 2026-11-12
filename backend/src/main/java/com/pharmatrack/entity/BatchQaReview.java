package com.pharmatrack.entity;

import com.pharmatrack.util.AppTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable historical record of every QA disposition decision made for a {@link Batch}.
 */
@Entity
@Table(name = "batch_qa_reviews", indexes = {
        @Index(name = "idx_batch_qa_reviews_batch_id", columnList = "batch_id")
})
public class BatchQaReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotNull(message = "Batch reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 30)
    private BatchQualityStatus previousStatus;

    @NotNull(message = "Decision status is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "decision_status", nullable = false, length = 30)
    private BatchQualityStatus decisionStatus;

    @NotBlank(message = "Reviewer name is mandatory")
    @Size(max = 100, message = "Reviewer name must not exceed 100 characters")
    @Column(name = "reviewer_name", nullable = false, length = 100)
    private String reviewerName;

    @NotBlank(message = "Decision rationale is mandatory")
    @Size(max = 2000, message = "Decision rationale must not exceed 2000 characters")
    @Column(name = "rationale", nullable = false, columnDefinition = "TEXT")
    private String rationale;

    @NotNull(message = "Review timestamp is mandatory")
    @Column(name = "reviewed_at", nullable = false)
    private LocalDateTime reviewedAt;

    @Column(name = "total_checks_count", nullable = false)
    private Long totalChecksCount = 0L;

    @Column(name = "passed_checks_count", nullable = false)
    private Long passedChecksCount = 0L;

    @Column(name = "failed_checks_count", nullable = false)
    private Long failedChecksCount = 0L;

    @Column(name = "pending_checks_count", nullable = false)
    private Long pendingChecksCount = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public BatchQaReview() {
    }

    public BatchQaReview(Batch batch, BatchQualityStatus previousStatus, BatchQualityStatus decisionStatus,
                         String reviewerName, String rationale, LocalDateTime reviewedAt,
                         long totalChecksCount, long passedChecksCount,
                         long failedChecksCount, long pendingChecksCount) {
        this.batch = batch;
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

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = AppTime.nowDateTime();
        this.createdAt = now;
        if (this.reviewedAt == null) {
            this.reviewedAt = now;
        }
        if (this.totalChecksCount == null) this.totalChecksCount = 0L;
        if (this.passedChecksCount == null) this.passedChecksCount = 0L;
        if (this.failedChecksCount == null) this.failedChecksCount = 0L;
        if (this.pendingChecksCount == null) this.pendingChecksCount = 0L;
    }

    @PreUpdate
    protected void onUpdate() {
        throw new UnsupportedOperationException("QA review records are immutable and cannot be modified");
    }

    @PreRemove
    protected void onRemove() {
        throw new UnsupportedOperationException("QA review records are immutable and cannot be deleted");
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
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

    public Long getTotalChecksCount() {
        return totalChecksCount;
    }

    public void setTotalChecksCount(Long totalChecksCount) {
        this.totalChecksCount = totalChecksCount;
    }

    public Long getPassedChecksCount() {
        return passedChecksCount;
    }

    public void setPassedChecksCount(Long passedChecksCount) {
        this.passedChecksCount = passedChecksCount;
    }

    public Long getFailedChecksCount() {
        return failedChecksCount;
    }

    public void setFailedChecksCount(Long failedChecksCount) {
        this.failedChecksCount = failedChecksCount;
    }

    public Long getPendingChecksCount() {
        return pendingChecksCount;
    }

    public void setPendingChecksCount(Long pendingChecksCount) {
        this.pendingChecksCount = pendingChecksCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BatchQaReview)) return false;
        BatchQaReview other = (BatchQaReview) o;
        if (getId() == null || other.getId() == null) {
            return false;
        }
        return Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return BatchQaReview.class.hashCode();
    }
}

