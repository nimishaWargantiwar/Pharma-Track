package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Data Transfer Object for raw material entries within a Recipe.
 */
public class RecipeMaterialDto {

    @NotBlank(message = "Material name is mandatory")
    @Size(max = 255, message = "Material name must not exceed 255 characters")
    private String materialName;

    @NotNull(message = "Quantity is mandatory")
    @Positive(message = "Quantity must be greater than zero")
    private BigDecimal quantity;

    @NotBlank(message = "Unit is mandatory")
    @Size(max = 50, message = "Unit must not exceed 50 characters")
    private String unit;

    public RecipeMaterialDto() {
    }

    public RecipeMaterialDto(String materialName, BigDecimal quantity, String unit) {
        this.materialName = materialName;
        this.quantity = quantity;
        this.unit = unit;
    }

    public String getMaterialName() {
        return materialName;
    }

    public void setMaterialName(String materialName) {
        this.materialName = materialName;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }
}

