package com.pharmatrack.dto;

import com.pharmatrack.entity.OrderStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Response payload DTO summarizing recipe material requirements and issue progress
 * for a specific Manufacturing Order.
 */
public class OrderMaterialRequirementsResponseDto {

    private Long orderId;
    private String orderNumber;
    private OrderStatus orderStatus;
    private boolean eligibleForIssue;
    private String productCode;
    private String productName;
    private String recipeCode;
    private Integer recipeVersion;
    private BigDecimal plannedQuantity;
    private String quantityUnit;
    private int targetBatchCount;
    private int activeBatchCount;
    private List<OrderMaterialRequirementDto> requirements = new ArrayList<>();

    public OrderMaterialRequirementsResponseDto() {
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(OrderStatus orderStatus) {
        this.orderStatus = orderStatus;
    }

    public boolean isEligibleForIssue() {
        return eligibleForIssue;
    }

    public void setEligibleForIssue(boolean eligibleForIssue) {
        this.eligibleForIssue = eligibleForIssue;
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

    public String getRecipeCode() {
        return recipeCode;
    }

    public void setRecipeCode(String recipeCode) {
        this.recipeCode = recipeCode;
    }

    public Integer getRecipeVersion() {
        return recipeVersion;
    }

    public void setRecipeVersion(Integer recipeVersion) {
        this.recipeVersion = recipeVersion;
    }

    public BigDecimal getPlannedQuantity() {
        return plannedQuantity;
    }

    public void setPlannedQuantity(BigDecimal plannedQuantity) {
        this.plannedQuantity = plannedQuantity;
    }

    public String getQuantityUnit() {
        return quantityUnit;
    }

    public void setQuantityUnit(String quantityUnit) {
        this.quantityUnit = quantityUnit;
    }

    public int getTargetBatchCount() {
        return targetBatchCount;
    }

    public void setTargetBatchCount(int targetBatchCount) {
        this.targetBatchCount = targetBatchCount;
    }

    public int getActiveBatchCount() {
        return activeBatchCount;
    }

    public void setActiveBatchCount(int activeBatchCount) {
        this.activeBatchCount = activeBatchCount;
    }

    public List<OrderMaterialRequirementDto> getRequirements() {
        return requirements;
    }

    public void setRequirements(List<OrderMaterialRequirementDto> requirements) {
        this.requirements = requirements != null ? requirements : new ArrayList<>();
    }
}

