package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO submitted by an operator when completing a BatchExecutionStep operation.
 */
public class CompleteExecutionStepRequestDto {

    @NotBlank(message = "Actual value is mandatory")
    @Size(max = 255, message = "Actual value must not exceed 255 characters")
    private String actualValue;

    @Size(max = 2000, message = "Observations must not exceed 2000 characters")
    private String observations;

    public CompleteExecutionStepRequestDto() {
    }

    public CompleteExecutionStepRequestDto(String actualValue, String observations) {
        this.actualValue = actualValue;
        this.observations = observations;
    }

    public String getActualValue() {
        return actualValue;
    }

    public void setActualValue(String actualValue) {
        this.actualValue = actualValue;
    }

    public String getObservations() {
        return observations;
    }

    public void setObservations(String observations) {
        this.observations = observations;
    }
}

