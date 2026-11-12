package com.pharmatrack.entity;

import com.pharmatrack.util.AppTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Append-only immutable stock movement ledger entry tracking every goods receipt,
 * material issue against a manufacturing order/batch, and controlled stock adjustment.
 * Updates and deletions are strictly forbidden.
 */
@Entity
@Table(name = "stock_movements", uniqueConstraints = {
        @UniqueConstraint(name = "uk_stock_movement_number", columnNames = "movement_number"),
        @UniqueConstraint(name = "uk_stock_movement_reference", columnNames = "reference_number")
}, indexes = {
        @Index(name = "idx_stock_movements_material_id", columnList = "raw_material_id"),
        @Index(name = "idx_stock_movements_lot_id", columnList = "material_lot_id"),
        @Index(name = "idx_stock_movements_order_id", columnList = "manufacturing_order_id"),
        @Index(name = "idx_stock_movements_batch_id", columnList = "batch_id"),
        @Index(name = "idx_stock_movements_occurred_at", columnList = "occurred_at")
})
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotBlank(message = "Movement number is mandatory")
    @Size(max = 60, message = "Movement number must not exceed 60 characters")
    @Column(name = "movement_number", nullable = false, updatable = false, length = 60)
    private String movementNumber;

    @Size(max = 100, message = "Reference number must not exceed 100 characters")
    @Column(name = "reference_number", updatable = false, length = 100)
    private String referenceNumber;

    @NotNull(message = "Movement type is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, updatable = false, length = 30)
    private StockMovementType movementType;

    @NotNull(message = "Raw material reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "raw_material_id", nullable = false, updatable = false)
    private RawMaterial rawMaterial;

    @NotNull(message = "Material lot reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_lot_id", nullable = false, updatable = false)
    private MaterialLot materialLot;

    @NotNull(message = "Warehouse reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false, updatable = false)
    private Warehouse warehouse;

    @NotNull(message = "Storage location reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_location_id", nullable = false, updatable = false)
    private StorageLocation storageLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manufacturing_order_id", updatable = false)
    private ManufacturingOrder manufacturingOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", updatable = false)
    private Batch batch;

    @NotNull(message = "Quantity delta is mandatory")
    @Column(name = "quantity_delta", nullable = false, updatable = false, precision = 14, scale = 4)
    private BigDecimal quantityDelta;

    @NotNull(message = "Quantity before is mandatory")
    @Column(name = "quantity_before", nullable = false, updatable = false, precision = 14, scale = 4)
    private BigDecimal quantityBefore;

    @NotNull(message = "Quantity after is mandatory")
    @Column(name = "quantity_after", nullable = false, updatable = false, precision = 14, scale = 4)
    private BigDecimal quantityAfter;

    @NotBlank(message = "Unit is mandatory")
    @Size(max = 50, message = "Unit must not exceed 50 characters")
    @Column(name = "unit", nullable = false, updatable = false, length = 50)
    private String unit;

    @Size(max = 150, message = "Supplier name must not exceed 150 characters")
    @Column(name = "supplier_name", updatable = false, length = 150)
    private String supplierName;

    @Size(max = 100, message = "Supplier reference must not exceed 100 characters")
    @Column(name = "supplier_reference", updatable = false, length = 100)
    private String supplierReference;

    @Column(name = "reason", updatable = false, columnDefinition = "TEXT")
    private String reason;

    @NotBlank(message = "Performed by actor is mandatory")
    @Size(max = 100, message = "Actor name must not exceed 100 characters")
    @Column(name = "performed_by", nullable = false, updatable = false, length = 100)
    private String performedBy;

    @NotNull(message = "Occurrence timestamp is mandatory")
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public StockMovement() {
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = AppTime.nowDateTime();
        if (this.occurredAt == null) {
            this.occurredAt = now;
        }
        this.createdAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        throw new UnsupportedOperationException("Stock movement entries are immutable and cannot be modified");
    }

    @PreRemove
    protected void onRemove() {
        throw new UnsupportedOperationException("Stock movement entries are immutable and cannot be deleted");
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMovementNumber() {
        return movementNumber;
    }

    public void setMovementNumber(String movementNumber) {
        this.movementNumber = movementNumber;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public StockMovementType getMovementType() {
        return movementType;
    }

    public void setMovementType(StockMovementType movementType) {
        this.movementType = movementType;
    }

    public RawMaterial getRawMaterial() {
        return rawMaterial;
    }

    public void setRawMaterial(RawMaterial rawMaterial) {
        this.rawMaterial = rawMaterial;
    }

    public MaterialLot getMaterialLot() {
        return materialLot;
    }

    public void setMaterialLot(MaterialLot materialLot) {
        this.materialLot = materialLot;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public StorageLocation getStorageLocation() {
        return storageLocation;
    }

    public void setStorageLocation(StorageLocation storageLocation) {
        this.storageLocation = storageLocation;
    }

    public ManufacturingOrder getManufacturingOrder() {
        return manufacturingOrder;
    }

    public void setManufacturingOrder(ManufacturingOrder manufacturingOrder) {
        this.manufacturingOrder = manufacturingOrder;
    }

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
    }

    public BigDecimal getQuantityDelta() {
        return quantityDelta;
    }

    public void setQuantityDelta(BigDecimal quantityDelta) {
        this.quantityDelta = quantityDelta;
    }

    public BigDecimal getQuantityBefore() {
        return quantityBefore;
    }

    public void setQuantityBefore(BigDecimal quantityBefore) {
        this.quantityBefore = quantityBefore;
    }

    public BigDecimal getQuantityAfter() {
        return quantityAfter;
    }

    public void setQuantityAfter(BigDecimal quantityAfter) {
        this.quantityAfter = quantityAfter;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public void setSupplierName(String supplierName) {
        this.supplierName = supplierName;
    }

    public String getSupplierReference() {
        return supplierReference;
    }

    public void setSupplierReference(String supplierReference) {
        this.supplierReference = supplierReference;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public void setPerformedBy(String performedBy) {
        this.performedBy = performedBy;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StockMovement that)) return false;
        if (id != null && that.id != null) {
            return Objects.equals(id, that.id);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return StockMovement.class.hashCode();
    }
}

