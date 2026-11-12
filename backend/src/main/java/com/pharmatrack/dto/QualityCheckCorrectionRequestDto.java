package com.pharmatrack.dto;

import com.pharmatrack.entity.QualityCheckResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for performing an audited correction on an existing QC check record.
 * Requires a clear correction reason recorded into the append-only audit trail.
 */
public class QualityCheckCorrectionRequestDto {

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

    private Boolean required;

    @Size(max = 2000, message = "Remarks must not exceed 2000 characters")
    private String remarks;

    @NotBlank(message = "Correction reason is mandatory")
    @Size(max = 1000, message = "Correction reason must not exceed 1000 characters")
    private String correctionReason;

    public QualityCheckCorrectionRequestDto() {
    }

    public QualityCheckCorrectionRequestDto(String testName, String specification, String measuredValue,
                                            String unit, QualityCheckResult result, Boolean required,
                                            String remarks, String correctionReason) {
        this.testName = testName;
        this.specification = specification;
        this.measuredValue = measuredValue;
        this.unit = unit;
        this.result = result;
        this.required = required;
        this.remarks = remarks;
        this.correctionReason = correctionReason;
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
        this.required = required;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public String getCorrectionReason() {
        return correctionReason;
    }

    public void setCorrectionReason(String correctionReason) {
        this.correctionReason = correctionReason;
    }
}

