package com.pharmatrack.entity;

import com.pharmatrack.util.AppTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity representing a raw material master record in PharmaTrack inventory.
 */
@Entity
@Table(name = "raw_materials", uniqueConstraints = {
        @UniqueConstraint(name = "uk_raw_material_code", columnNames = "material_code")
}, indexes = {
        @Index(name = "idx_raw_materials_name", columnList = "material_name"),
        @Index(name = "idx_raw_materials_active", columnList = "active")
})
public class RawMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotBlank(message = "Material code is mandatory")
    @Size(max = 50, message = "Material code must not exceed 50 characters")
    @Column(name = "material_code", nullable = false, length = 50)
    private String materialCode;

    @NotBlank(message = "Material name is mandatory")
    @Size(max = 255, message = "Material name must not exceed 255 characters")
    @Column(name = "material_name", nullable = false, length = 255)
    private String materialName;

    @NotNull(message = "Material type is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "material_type", nullable = false, length = 20)
    private MaterialType materialType;

    @NotBlank(message = "Unit of measurement is mandatory")
    @Size(max = 50, message = "Unit must not exceed 50 characters")
    @Column(name = "unit", nullable = false, length = 50)
    private String unit;

    @NotNull(message = "Minimum stock level is mandatory")
    @DecimalMin(value = "0.0", inclusive = true, message = "Minimum stock level cannot be negative")
    @Column(name = "minimum_stock_level", nullable = false, precision = 14, scale = 4)
    private BigDecimal minimumStockLevel;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public RawMaterial() {
    }

    public RawMaterial(String materialCode,
                       String materialName,
                       MaterialType materialType,
                       String unit,
                       BigDecimal minimumStockLevel,
                       String description,
                       boolean active) {
        this.materialCode = materialCode;
        this.materialName = materialName;
        this.materialType = materialType;
        this.unit = unit;
        this.minimumStockLevel = minimumStockLevel;
        this.description = description;
        this.active = active;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = AppTime.nowDateTime();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.materialType == null) {
            this.materialType = MaterialType.OTHER;
        }
        if (this.minimumStockLevel == null) {
            this.minimumStockLevel = BigDecimal.ZERO;
        }
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
        if (!(o instanceof RawMaterial that)) return false;
        if (materialCode == null || that.materialCode == null) return false;
        return Objects.equals(materialCode, that.materialCode);
    }

    @Override
    public int hashCode() {
        return RawMaterial.class.hashCode();
    }
}

