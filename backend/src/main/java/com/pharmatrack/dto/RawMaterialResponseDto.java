package com.pharmatrack.dto;

import com.pharmatrack.entity.MaterialStockStatus;
import com.pharmatrack.entity.MaterialType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Response payload DTO representing a Raw Material master record enriched with
 * live available/quarantined/expired stock metrics and linked recipe usage.
 */
public class RawMaterialResponseDto {

    private Long id;
    private String materialCode;
    private String materialName;
    private MaterialType materialType;
    private String unit;
    private BigDecimal minimumStockLevel;
    private String description;
    private boolean active;

    private BigDecimal availableStock;
    private BigDecimal quarantinedStock;
    private BigDecimal expiredStock;
    private int activeLotCount;
    private int expiringSoonLotCount;
    private int expiredLotCount;
    private MaterialStockStatus stockStatus;

    private int linkedRecipeCount;
    private List<String> linkedRecipeCodes = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public RawMaterialResponseDto() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public BigDecimal getAvailableStock() {
        return availableStock;
    }

    public void setAvailableStock(BigDecimal availableStock) {
        this.availableStock = availableStock;
    }

    public BigDecimal getQuarantinedStock() {
        return quarantinedStock;
    }

    public void setQuarantinedStock(BigDecimal quarantinedStock) {
        this.quarantinedStock = quarantinedStock;
    }

    public BigDecimal getExpiredStock() {
        return expiredStock;
    }

    public void setExpiredStock(BigDecimal expiredStock) {
        this.expiredStock = expiredStock;
    }

    public int getActiveLotCount() {
        return activeLotCount;
    }

    public void setActiveLotCount(int activeLotCount) {
        this.activeLotCount = activeLotCount;
    }

    public int getExpiringSoonLotCount() {
        return expiringSoonLotCount;
    }

    public void setExpiringSoonLotCount(int expiringSoonLotCount) {
        this.expiringSoonLotCount = expiringSoonLotCount;
    }

    public int getExpiredLotCount() {
        return expiredLotCount;
    }

    public void setExpiredLotCount(int expiredLotCount) {
        this.expiredLotCount = expiredLotCount;
    }

    public MaterialStockStatus getStockStatus() {
        return stockStatus;
    }

    public void setStockStatus(MaterialStockStatus stockStatus) {
        this.stockStatus = stockStatus;
    }

    public int getLinkedRecipeCount() {
        return linkedRecipeCount;
    }

    public void setLinkedRecipeCount(int linkedRecipeCount) {
        this.linkedRecipeCount = linkedRecipeCount;
    }

    public List<String> getLinkedRecipeCodes() {
        return linkedRecipeCodes;
    }

    public void setLinkedRecipeCodes(List<String> linkedRecipeCodes) {
        this.linkedRecipeCodes = linkedRecipeCodes != null ? linkedRecipeCodes : new ArrayList<>();
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

