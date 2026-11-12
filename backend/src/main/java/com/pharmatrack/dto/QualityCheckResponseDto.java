package com.pharmatrack.dto;

import com.pharmatrack.entity.QualityCheckResult;

import java.time.LocalDateTime;

/**
 * Response payload DTO representing a Quality Control (QC) check record.
 */
public class QualityCheckResponseDto {

    private Long id;
    private Long batchId;
    private String batchNumber;
    private String testName;
    private String specification;
    private String measuredValue;
    private String unit;
    private QualityCheckResult result;
    private boolean required;
    private String inspectorName;
    private LocalDateTime checkedAt;
    private String remarks;
    private String correctedBy;
    private LocalDateTime correctedAt;
    private String correctionReason;
    private int correctionCount;
    private boolean locked;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public QualityCheckResponseDto() {
    }

    public QualityCheckResponseDto(Long id, Long batchId, String batchNumber, String testName,
                                   String specification, String measuredValue, String unit,
                                   QualityCheckResult result, boolean required, String inspectorName,
                                   LocalDateTime checkedAt, String remarks, String correctedBy,
                                   LocalDateTime correctedAt, String correctionReason,
                                   int correctionCount, boolean locked,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.batchId = batchId;
        this.batchNumber = batchNumber;
        this.testName = testName;
        this.specification = specification;
        this.measuredValue = measuredValue;
        this.unit = unit;
        this.result = result;
        this.required = required;
        this.inspectorName = inspectorName;
        this.checkedAt = checkedAt;
        this.remarks = remarks;
        this.correctedBy = correctedBy;
        this.correctedAt = correctedAt;
        this.correctionReason = correctionReason;
        this.correctionCount = correctionCount;
        this.locked = locked;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
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

    public int getCorrectionCount() {
        return correctionCount;
    }

    public void setCorrectionCount(int correctionCount) {
        this.correctionCount = correctionCount;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
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
}

