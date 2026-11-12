package com.pharmatrack.dto;

import com.pharmatrack.entity.QualityCheckResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Request payload DTO for recording a Quality Control (QC) check against a completed batch.
 */
public class QualityCheckRequestDto {

    @NotBlank(message = "Test name is mandatory")
    @Size(max = 150, message = "Test name must not exceed 150 characters")
    private String testName;

    @NotBlank(message = "Specification is mandatory")
    @Size(max = 255, message = "Specification must not exceed 255 characters")
    private String specification;

    @Size(max = 150, message = "Measured value must not exceed 150 characters")
    private String measuredValue;

    @NotBlank(message = "Unit is mandatory")
    @Size(max = 50, message = "Unit must not exceed 50 characters")
    private String unit;

    @NotNull(message = "QC result is mandatory")
    private QualityCheckResult result;

    private Boolean required = Boolean.TRUE;

    private LocalDateTime checkedAt;

    @Size(max = 2000, message = "Remarks must not exceed 2000 characters")
    private String remarks;

    public QualityCheckRequestDto() {
    }

    public QualityCheckRequestDto(String testName, String specification, String measuredValue,
                                  String unit, QualityCheckResult result, Boolean required,
                                  LocalDateTime checkedAt, String remarks) {
        this.testName = testName;
        this.specification = specification;
        this.measuredValue = measuredValue;
        this.unit = unit;
        this.result = result;
        this.required = required != null ? required : Boolean.TRUE;
        this.checkedAt = checkedAt;
        this.remarks = remarks;
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
}

