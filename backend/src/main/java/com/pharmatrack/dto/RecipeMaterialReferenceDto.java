package com.pharmatrack.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Summary of distinct raw material references used across Master Recipes so inventory
 * records can be cleanly linked to existing product formulations.
 */
public class RecipeMaterialReferenceDto {

    private String materialName;
    private String unit;
    private List<String> recipeCodes = new ArrayList<>();
    private List<String> productCodes = new ArrayList<>();
    private boolean registeredInInventory;
    private Long rawMaterialId;
    private String rawMaterialCode;

    public RecipeMaterialReferenceDto() {
    }

    public String getMaterialName() {
        return materialName;
    }

    public void setMaterialName(String materialName) {
        this.materialName = materialName;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public List<String> getRecipeCodes() {
        return recipeCodes;
    }

    public void setRecipeCodes(List<String> recipeCodes) {
        this.recipeCodes = recipeCodes != null ? recipeCodes : new ArrayList<>();
    }

    public List<String> getProductCodes() {
        return productCodes;
    }

    public void setProductCodes(List<String> productCodes) {
        this.productCodes = productCodes != null ? productCodes : new ArrayList<>();
    }

    public boolean isRegisteredInInventory() {
        return registeredInInventory;
    }

    public void setRegisteredInInventory(boolean registeredInInventory) {
        this.registeredInInventory = registeredInInventory;
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
}

