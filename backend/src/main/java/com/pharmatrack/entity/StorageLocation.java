package com.pharmatrack.entity;

import com.pharmatrack.util.AppTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity representing a storage bin/rack/zone inside a {@link Warehouse}.
 */
@Entity
@Table(name = "storage_locations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_storage_location_warehouse_code", columnNames = {"warehouse_id", "location_code"})
}, indexes = {
        @Index(name = "idx_storage_locations_active", columnList = "active")
})
public class StorageLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotNull(message = "Warehouse reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @NotBlank(message = "Location code is mandatory")
    @Size(max = 60, message = "Location code must not exceed 60 characters")
    @Column(name = "location_code", nullable = false, length = 60)
    private String locationCode;

    @Size(max = 100, message = "Zone name must not exceed 100 characters")
    @Column(name = "zone_name", length = 100)
    private String zoneName;

    @Size(max = 40, message = "Aisle must not exceed 40 characters")
    @Column(name = "aisle", length = 40)
    private String aisle;

    @Size(max = 40, message = "Rack must not exceed 40 characters")
    @Column(name = "rack", length = 40)
    private String rack;

    @Size(max = 40, message = "Bin must not exceed 40 characters")
    @Column(name = "bin", length = 40)
    private String bin;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public StorageLocation() {
    }

    public StorageLocation(Warehouse warehouse,
                           String locationCode,
                           String zoneName,
                           String aisle,
                           String rack,
                           String bin,
                           boolean active) {
        this.warehouse = warehouse;
        this.locationCode = locationCode;
        this.zoneName = zoneName;
        this.aisle = aisle;
        this.rack = rack;
        this.bin = bin;
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

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public void setLocationCode(String locationCode) {
        this.locationCode = locationCode;
    }

    public String getZoneName() {
        return zoneName;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    public String getAisle() {
        return aisle;
    }

    public void setAisle(String aisle) {
        this.aisle = aisle;
    }

    public String getRack() {
        return rack;
    }

    public void setRack(String rack) {
        this.rack = rack;
    }

    public String getBin() {
        return bin;
    }

    public void setBin(String bin) {
        this.bin = bin;
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
        if (!(o instanceof StorageLocation that)) return false;
        if (id != null && that.id != null) {
            return Objects.equals(id, that.id);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return StorageLocation.class.hashCode();
    }
}

