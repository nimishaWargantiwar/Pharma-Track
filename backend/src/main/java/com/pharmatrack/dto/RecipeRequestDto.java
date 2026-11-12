package com.pharmatrack.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Request payload DTO for creating or updating a Master Recipe.
 * Recipe code, version, status, and audit timestamps are managed strictly by the backend.
 */
public class RecipeRequestDto {

    @NotNull(message = "Product ID is mandatory")
    private Long productId;

    @NotNull(message = "Batch size is mandatory")
    @Positive(message = "Batch size must be greater than zero")
    private BigDecimal batchSize;

    @NotBlank(message = "Batch unit is mandatory")
    @Size(max = 50, message = "Batch unit must not exceed 50 characters")
    private String batchUnit;

    private String description;

    @NotEmpty(message = "Recipe must contain at least one material")
    @Valid
    private List<RecipeMaterialDto> materials = new ArrayList<>();

    @NotEmpty(message = "Recipe must contain at least one manufacturing step")
    @Valid
    private List<RecipeStepDto> steps = new ArrayList<>();

    public RecipeRequestDto() {
    }

    public RecipeRequestDto(Long productId, BigDecimal batchSize, String batchUnit, String description,
                            List<RecipeMaterialDto> materials, List<RecipeStepDto> steps) {
        this.productId = productId;
        this.batchSize = batchSize;
        this.batchUnit = batchUnit;
        this.description = description;
        this.materials = materials != null ? materials : new ArrayList<>();
        this.steps = steps != null ? steps : new ArrayList<>();
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<RecipeMaterialDto> getMaterials() {
        return materials;
    }

    public void setMaterials(List<RecipeMaterialDto> materials) {
        this.materials = materials != null ? materials : new ArrayList<>();
    }

    public List<RecipeStepDto> getSteps() {
        return steps;
    }

    public void setSteps(List<RecipeStepDto> steps) {
        this.steps = steps != null ? steps : new ArrayList<>();
    }
}

