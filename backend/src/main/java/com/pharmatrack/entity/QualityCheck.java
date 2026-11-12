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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity representing an individual Quality Control (QC) inspection check
 * executed against a completed production {@link Batch}.
 */
@Entity
@Table(name = "quality_checks", indexes = {
        @Index(name = "idx_quality_checks_batch_id", columnList = "batch_id")
})
public class QualityCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotNull(message = "Batch reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false, updatable = false)
    private Batch batch;

    @NotBlank(message = "Test name is mandatory")
    @Size(max = 150, message = "Test name must not exceed 150 characters")
    @Column(name = "test_name", nullable = false, length = 150)
    private String testName;

    @NotBlank(message = "Specification is mandatory")
    @Size(max = 255, message = "Specification must not exceed 255 characters")
    @Column(name = "specification", nullable = false, length = 255)
    private String specification;

    @Size(max = 150, message = "Measured value must not exceed 150 characters")
    @Column(name = "measured_value", length = 150)
    private String measuredValue;

    @NotBlank(message = "Unit is mandatory")
    @Size(max = 50, message = "Unit must not exceed 50 characters")
    @Column(name = "unit", nullable = false, length = 50)
    private String unit;

    @NotNull(message = "QC result is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 20)
    private QualityCheckResult result;

    @NotNull(message = "Required indicator is mandatory")
    @Column(name = "is_required", nullable = false)
    private Boolean required = Boolean.TRUE;

    @NotBlank(message = "Inspector name is mandatory")
    @Size(max = 100, message = "Inspector name must not exceed 100 characters")
    @Column(name = "inspector_name", nullable = false, length = 100)
    private String inspectorName;

    @NotNull(message = "Check timestamp is mandatory")
    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    @Size(max = 2000, message = "Remarks must not exceed 2000 characters")
    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    @Size(max = 100, message = "Corrected by must not exceed 100 characters")
    @Column(name = "corrected_by", length = 100)
    private String correctedBy;

    @Column(name = "corrected_at")
    private LocalDateTime correctedAt;

    @Size(max = 1000, message = "Correction reason must not exceed 1000 characters")
    @Column(name = "correction_reason", columnDefinition = "TEXT")
    private String correctionReason;

    @Column(name = "correction_count", nullable = false)
    private Integer correctionCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public QualityCheck() {
    }

    public QualityCheck(Batch batch, String testName, String specification, String measuredValue,
                        String unit, QualityCheckResult result, Boolean required,
                        String inspectorName, LocalDateTime checkedAt, String remarks) {
        this.batch = batch;
        this.testName = testName;
        this.specification = specification;
        this.measuredValue = measuredValue;
        this.unit = unit;
        this.result = result;
        this.required = required != null ? required : Boolean.TRUE;
        this.inspectorName = inspectorName;
        this.checkedAt = checkedAt;
        this.remarks = remarks;
        this.correctionCount = 0;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = AppTime.nowDateTime();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.checkedAt == null) {
            this.checkedAt = now;
        }
        if (this.required == null) {
            this.required = Boolean.TRUE;
        }
        if (this.correctionCount == null) {
            this.correctionCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = AppTime.nowDateTime();
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

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public String getSpecification() {
        return specification;
    }

    public void setSpecification(String specification) {
        this.specification = specification;
    }

    public String getMeasuredValue() {
        return measuredValue;
    }

    public void setMeasuredValue(String measuredValue) {
        this.measuredValue = measuredValue;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public QualityCheckResult getResult() {
        return result;
    }

    public void setResult(QualityCheckResult result) {
        this.result = result;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required != null ? required : Boolean.TRUE;
    }

    public boolean isRequired() {
        return !Boolean.FALSE.equals(this.required);
    }

    public String getInspectorName() {
        return inspectorName;
    }

    public void setInspectorName(String inspectorName) {
        this.inspectorName = inspectorName;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(LocalDateTime checkedAt) {
        this.checkedAt = checkedAt;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public String getCorrectedBy() {
        return correctedBy;
    }

    public void setCorrectedBy(String correctedBy) {
        this.correctedBy = correctedBy;
    }

    public LocalDateTime getCorrectedAt() {
        return correctedAt;
    }

    public void setCorrectedAt(LocalDateTime correctedAt) {
        this.correctedAt = correctedAt;
    }

    public String getCorrectionReason() {
        return correctionReason;
    }

    public void setCorrectionReason(String correctionReason) {
        this.correctionReason = correctionReason;
    }

    public Integer getCorrectionCount() {
        return correctionCount;
    }

    public void setCorrectionCount(Integer correctionCount) {
        this.correctionCount = correctionCount != null ? correctionCount : 0;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QualityCheck)) return false;
        QualityCheck other = (QualityCheck) o;
        if (getId() == null || other.getId() == null) {
            return false;
        }
        return Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return QualityCheck.class.hashCode();
    }
}

