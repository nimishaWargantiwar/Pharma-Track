package com.pharmatrack.entity;

import com.pharmatrack.util.AppTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity representing a physical warehouse facility storing raw material lots.
 */
@Entity
@Table(name = "warehouses", uniqueConstraints = {
        @UniqueConstraint(name = "uk_warehouse_code", columnNames = "warehouse_code")
}, indexes = {
        @Index(name = "idx_warehouses_active", columnList = "active")
})
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotBlank(message = "Warehouse code is mandatory")
    @Size(max = 50, message = "Warehouse code must not exceed 50 characters")
    @Column(name = "warehouse_code", nullable = false, length = 50)
    private String warehouseCode;

    @NotBlank(message = "Warehouse name is mandatory")
    @Size(max = 150, message = "Warehouse name must not exceed 150 characters")
    @Column(name = "warehouse_name", nullable = false, length = 150)
    private String warehouseName;

    @Size(max = 255, message = "Location summary must not exceed 255 characters")
    @Column(name = "location_summary", length = 255)
    private String locationSummary;

    @Size(max = 100, message = "Storage condition must not exceed 100 characters")
    @Column(name = "storage_condition", length = 100)
    private String storageCondition;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Warehouse() {
    }

    public Warehouse(String warehouseCode,
                     String warehouseName,
                     String locationSummary,
                     String storageCondition,
                     boolean active) {
        this.warehouseCode = warehouseCode;
        this.warehouseName = warehouseName;
        this.locationSummary = locationSummary;
        this.storageCondition = storageCondition;
        this.active = active;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = AppTime.nowDateTime();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = AppTime.nowDateTime();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getWarehouseCode() {
        return warehouseCode;
    }

    public void setWarehouseCode(String warehouseCode) {
        this.warehouseCode = warehouseCode;
    }

    public String getWarehouseName() {
        return warehouseName;
    }

    public void setWarehouseName(String warehouseName) {
        this.warehouseName = warehouseName;
    }

    public String getLocationSummary() {
        return locationSummary;
    }

    public void setLocationSummary(String locationSummary) {
        this.locationSummary = locationSummary;
    }

    public String getStorageCondition() {
        return storageCondition;
    }

    public void setStorageCondition(String storageCondition) {
        this.storageCondition = storageCondition;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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
        if (!(o instanceof Warehouse that)) return false;
        if (warehouseCode == null || that.warehouseCode == null) return false;
        return Objects.equals(warehouseCode, that.warehouseCode);
    }

    @Override
    public int hashCode() {
        return Warehouse.class.hashCode();
    }
}

