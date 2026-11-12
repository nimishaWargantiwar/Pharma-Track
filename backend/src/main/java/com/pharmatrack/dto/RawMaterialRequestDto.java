package com.pharmatrack.dto;

import com.pharmatrack.entity.MaterialType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request payload DTO for creating or updating a Raw Material master record.
 */
public class RawMaterialRequestDto {

    @NotBlank(message = "Material code is mandatory")
    @Size(max = 50, message = "Material code must not exceed 50 characters")
    private String materialCode;

    @NotBlank(message = "Material name is mandatory")
    @Size(max = 255, message = "Material name must not exceed 255 characters")
    private String materialName;

    @NotNull(message = "Material type is mandatory")
    private MaterialType materialType;

    @NotBlank(message = "Unit of measurement is mandatory")
    @Size(max = 50, message = "Unit must not exceed 50 characters")
    private String unit;

    @NotNull(message = "Minimum stock level is mandatory")
    @DecimalMin(value = "0.0", inclusive = true, message = "Minimum stock level cannot be negative")
    private BigDecimal minimumStockLevel;

    @Size(max = 2000, message = "Description must not exceed 2000 characters")
    private String description;

    private Boolean active = Boolean.TRUE;

    public RawMaterialRequestDto() {
    }

    public String getMaterialCode() {
        return materialCode;
    }

    public void setMaterialCode(String materialCode) {
        this.materialCode = materialCode;
    }

    public String getMaterialName() {
        return materialName;
    }

    public void setMaterialName(String materialName) {
        this.materialName = materialName;
    }

    public MaterialType getMaterialType() {
        return materialType;
    }

    public void setMaterialType(MaterialType materialType) {
        this.materialType = materialType;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getMinimumStockLevel() {
        return minimumStockLevel;
    }

    public void setMinimumStockLevel(BigDecimal minimumStockLevel) {
        this.minimumStockLevel = minimumStockLevel;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}

