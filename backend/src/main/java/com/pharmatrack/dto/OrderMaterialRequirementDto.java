package com.pharmatrack.dto;

import java.math.BigDecimal;

/**
 * Line-item breakdown of one recipe material required by a Manufacturing Order,
 * reconciled against the Raw Material master, available stock, and quantities already issued.
 */
public class OrderMaterialRequirementDto {

    private String recipeMaterialName;
    private BigDecimal quantityPerBatch;
    private BigDecimal totalRequiredQuantity;
    private String recipeUnit;

    private Long rawMaterialId;
    private String rawMaterialCode;
    private String rawMaterialName;
    private String inventoryUnit;
    private boolean registeredInInventory;
    private boolean materialActive;
    private boolean unitMismatch;

    private BigDecimal availableStock;
    private BigDecimal issuedQuantity;
    private BigDecimal remainingRequiredQuantity;
    private boolean fulfilled;

    public OrderMaterialRequirementDto() {
    }

    public String getRecipeMaterialName() {
        return recipeMaterialName;
    }

    public void setRecipeMaterialName(String recipeMaterialName) {
        this.recipeMaterialName = recipeMaterialName;
    }

    public BigDecimal getQuantityPerBatch() {
        return quantityPerBatch;
    }

    public void setQuantityPerBatch(BigDecimal quantityPerBatch) {
        this.quantityPerBatch = quantityPerBatch;
    }

    public BigDecimal getTotalRequiredQuantity() {
        return totalRequiredQuantity;
    }

    public void setTotalRequiredQuantity(BigDecimal totalRequiredQuantity) {
        this.totalRequiredQuantity = totalRequiredQuantity;
    }

    public String getRecipeUnit() {
        return recipeUnit;
    }

    public void setRecipeUnit(String recipeUnit) {
        this.recipeUnit = recipeUnit;
    }

    public Long getRawMaterialId() {
        return rawMaterialId;
    }

    public void setRawMaterialId(Long rawMaterialId) {
        this.rawMaterialId = rawMaterialId;
    }

    public String getRawMaterialCode() {
        return rawMaterialCode;
    }

    public void setRawMaterialCode(String rawMaterialCode) {
        this.rawMaterialCode = rawMaterialCode;
    }

    public String getRawMaterialName() {
        return rawMaterialName;
    }

    public void setRawMaterialName(String rawMaterialName) {
        this.rawMaterialName = rawMaterialName;
    }

    public String getInventoryUnit() {
        return inventoryUnit;
    }

    public void setInventoryUnit(String inventoryUnit) {
        this.inventoryUnit = inventoryUnit;
    }

    public boolean isRegisteredInInventory() {
        return registeredInInventory;
    }

    public void setRegisteredInInventory(boolean registeredInInventory) {
        this.registeredInInventory = registeredInInventory;
    }

    public boolean isMaterialActive() {
        return materialActive;
    }

    public void setMaterialActive(boolean materialActive) {
        this.materialActive = materialActive;
    }

    public boolean isUnitMismatch() {
        return unitMismatch;
    }

    public void setUnitMismatch(boolean unitMismatch) {
        this.unitMismatch = unitMismatch;
    }

    public BigDecimal getAvailableStock() {
        return availableStock;
    }

    public void setAvailableStock(BigDecimal availableStock) {
        this.availableStock = availableStock;
    }

    public BigDecimal getIssuedQuantity() {
        return issuedQuantity;
    }

    public void setIssuedQuantity(BigDecimal issuedQuantity) {
        this.issuedQuantity = issuedQuantity;
    }

    public BigDecimal getRemainingRequiredQuantity() {
        return remainingRequiredQuantity;
    }

    public void setRemainingRequiredQuantity(BigDecimal remainingRequiredQuantity) {
        this.remainingRequiredQuantity = remainingRequiredQuantity;
    }

    public boolean isFulfilled() {
        return fulfilled;
    }

    public void setFulfilled(boolean fulfilled) {
        this.fulfilled = fulfilled;
    }
}

