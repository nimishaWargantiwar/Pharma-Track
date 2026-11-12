package com.pharmatrack.dto;

import com.pharmatrack.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Response payload DTO representing a Manufacturing Order with associated
 * product summary, recipe summary, recipe version snapshot, and generated batches.
 */
public class OrderResponseDto {

    private Long id;
    private String orderNumber;

    // Product summary
    private Long productId;
    private String productCode;
    private String productName;

    // Recipe summary
    private Long recipeId;
    private String recipeCode;

    // Snapshot of recipe version at order creation
    private Integer recipeVersion;

    private BigDecimal plannedQuantity;
    private String quantityUnit;
    private Integer targetBatchCount;
    private OrderStatus status;

    private LocalDate plannedStartDate;
    private LocalDate plannedEndDate;
    private LocalDateTime actualStartDate;
    private LocalDateTime actualEndDate;
    private String notes;

    // Child batch summaries
    private List<BatchResponseDto> batches = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public OrderResponseDto() {
    }

    public OrderResponseDto(Long id, String orderNumber, Long productId, String productCode, String productName,
                            Long recipeId, String recipeCode, Integer recipeVersion,
                            BigDecimal plannedQuantity, String quantityUnit, Integer targetBatchCount,
                            OrderStatus status, LocalDate plannedStartDate, LocalDate plannedEndDate,
                            LocalDateTime actualStartDate, LocalDateTime actualEndDate, String notes,
                            List<BatchResponseDto> batches, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.orderNumber = orderNumber;
        this.productId = productId;
        this.productCode = productCode;
        this.productName = productName;
        this.recipeId = recipeId;
        this.recipeCode = recipeCode;
        this.recipeVersion = recipeVersion;
        this.plannedQuantity = plannedQuantity;
        this.quantityUnit = quantityUnit;
        this.targetBatchCount = targetBatchCount;
        this.status = status;
        this.plannedStartDate = plannedStartDate;
        this.plannedEndDate = plannedEndDate;
        this.actualStartDate = actualStartDate;
        this.actualEndDate = actualEndDate;
        this.notes = notes;
        this.batches = batches != null ? batches : new ArrayList<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
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

    public Long getRecipeId() {
        return recipeId;
    }

    public void setRecipeId(Long recipeId) {
        this.recipeId = recipeId;
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

    public Integer getTargetBatchCount() {
        return targetBatchCount;
    }

    public void setTargetBatchCount(Integer targetBatchCount) {
        this.targetBatchCount = targetBatchCount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public LocalDate getPlannedStartDate() {
        return plannedStartDate;
    }

    public void setPlannedStartDate(LocalDate plannedStartDate) {
        this.plannedStartDate = plannedStartDate;
    }

    public LocalDate getPlannedEndDate() {
        return plannedEndDate;
    }

    public void setPlannedEndDate(LocalDate plannedEndDate) {
        this.plannedEndDate = plannedEndDate;
    }

    public LocalDateTime getActualStartDate() {
        return actualStartDate;
    }

    public void setActualStartDate(LocalDateTime actualStartDate) {
        this.actualStartDate = actualStartDate;
    }

    public LocalDateTime getActualEndDate() {
        return actualEndDate;
    }

    public void setActualEndDate(LocalDateTime actualEndDate) {
        this.actualEndDate = actualEndDate;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<BatchResponseDto> getBatches() {
        return batches;
    }

    public void setBatches(List<BatchResponseDto> batches) {
        this.batches = batches != null ? batches : new ArrayList<>();
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

