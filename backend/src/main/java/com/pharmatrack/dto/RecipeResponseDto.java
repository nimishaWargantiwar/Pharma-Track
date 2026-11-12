package com.pharmatrack.dto;

import com.pharmatrack.entity.RecipeStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Response payload DTO representing a Master Recipe with associated product summary,
 * materials, and sequential manufacturing steps.
 */
public class RecipeResponseDto {

    private Long id;
    private String recipeCode;
    private Long productId;
    private String productCode;
    private String productName;
    private Integer version;
    private BigDecimal batchSize;
    private String batchUnit;
    private RecipeStatus status;
    private String description;
    private List<RecipeMaterialDto> materials = new ArrayList<>();
    private List<RecipeStepDto> steps = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public RecipeResponseDto() {
    }

    public RecipeResponseDto(Long id, String recipeCode, Long productId, String productCode,
                             String productName, Integer version, BigDecimal batchSize,
                             String batchUnit, RecipeStatus status, String description,
                             List<RecipeMaterialDto> materials, List<RecipeStepDto> steps,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.recipeCode = recipeCode;
        this.productId = productId;
        this.productCode = productCode;
        this.productName = productName;
        this.version = version;
        this.batchSize = batchSize;
        this.batchUnit = batchUnit;
        this.status = status;
        this.description = description;
        this.materials = materials != null ? materials : new ArrayList<>();
        this.steps = steps != null ? steps : new ArrayList<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRecipeCode() {
        return recipeCode;
    }

    public void setRecipeCode(String recipeCode) {
        this.recipeCode = recipeCode;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
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

    public RecipeStatus getStatus() {
        return status;
    }

    public void setStatus(RecipeStatus status) {
        this.status = status;
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

