package com.pharmatrack.entity;

import com.pharmatrack.util.AppTime;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JPA entity representing a Manufacturing Order (MO) in PharmaTrack.
 * Serves as the parent aggregate for planned/executed production batches.
 */
@Entity
@Table(name = "orders", uniqueConstraints = {
        @UniqueConstraint(name = "uk_order_number", columnNames = "order_number")
})
public class ManufacturingOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotBlank(message = "Order number is mandatory")
    @Size(max = 50, message = "Order number must not exceed 50 characters")
    @Column(name = "order_number", nullable = false, unique = true, length = 50)
    private String orderNumber;

    @NotNull(message = "Product reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @NotNull(message = "Recipe reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @NotNull(message = "Recipe version is mandatory")
    @Min(value = 1, message = "Recipe version must be at least 1")
    @Column(name = "recipe_version", nullable = false, updatable = false)
    private Integer recipeVersion;

    @NotNull(message = "Planned quantity is mandatory")
    @Positive(message = "Planned quantity must be greater than zero")
    @Column(name = "planned_quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal plannedQuantity;

    @NotBlank(message = "Quantity unit is mandatory")
    @Size(max = 50, message = "Quantity unit must not exceed 50 characters")
    @Column(name = "quantity_unit", nullable = false, length = 50)
    private String quantityUnit;

    @NotNull(message = "Target batch count is mandatory")
    @Min(value = 1, message = "Target batch count must be at least 1")
    @Column(name = "target_batch_count", nullable = false)
    private Integer targetBatchCount;

    @NotNull(message = "Status is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "planned_start_date")
    private LocalDate plannedStartDate;

    @Column(name = "planned_end_date")
    private LocalDate plannedEndDate;

    @Column(name = "actual_start_date")
    private LocalDateTime actualStartDate;

    @Column(name = "actual_end_date")
    private LocalDateTime actualEndDate;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "order", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @OrderBy("batchNumber ASC")
    private List<Batch> batches = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ManufacturingOrder() {
    }

    public ManufacturingOrder(String orderNumber, Product product, Recipe recipe, Integer recipeVersion,
                              BigDecimal plannedQuantity, String quantityUnit, Integer targetBatchCount,
                              OrderStatus status, LocalDate plannedStartDate, LocalDate plannedEndDate, String notes) {
        this.orderNumber = orderNumber;
        this.product = product;
        this.recipe = recipe;
        this.recipeVersion = recipeVersion;
        this.plannedQuantity = plannedQuantity;
        this.quantityUnit = quantityUnit;
        this.targetBatchCount = targetBatchCount;
        this.status = status;
        this.plannedStartDate = plannedStartDate;
        this.plannedEndDate = plannedEndDate;
        this.notes = notes;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = AppTime.nowDateTime();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = OrderStatus.PLANNED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = AppTime.nowDateTime();
    }

    // Bidirectional helper methods

    public void addBatch(Batch batch) {
        if (batch != null) {
            this.batches.add(batch);
            batch.setOrder(this);
        }
    }

    // Getters and Setters

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

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public Recipe getRecipe() {
        return recipe;
    }

    public void setRecipe(Recipe recipe) {
        this.recipe = recipe;
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

    public List<Batch> getBatches() {
        return batches;
    }

    public void setBatches(List<Batch> batches) {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ManufacturingOrder)) return false;
        ManufacturingOrder that = (ManufacturingOrder) o;
        String thisOrderNumber = getOrderNumber();
        String otherOrderNumber = that.getOrderNumber();
        if (thisOrderNumber == null || otherOrderNumber == null) {
            return false;
        }
        return Objects.equals(thisOrderNumber, otherOrderNumber);
    }

    @Override
    public int hashCode() {
        return ManufacturingOrder.class.hashCode();
    }

    @Override
    public String toString() {
        return "ManufacturingOrder{" +
                "id=" + id +
                ", orderNumber='" + orderNumber + '\'' +
                ", recipeVersion=" + recipeVersion +
                ", plannedQuantity=" + plannedQuantity +
                ", quantityUnit='" + quantityUnit + '\'' +
                ", targetBatchCount=" + targetBatchCount +
                ", status=" + status +
                ", plannedStartDate=" + plannedStartDate +
                ", plannedEndDate=" + plannedEndDate +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

