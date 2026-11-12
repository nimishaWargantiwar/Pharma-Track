package com.pharmatrack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity representing a Raw Material required by a Recipe.
 */
@Entity
@Table(name = "recipe_materials")
public class RecipeMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotNull(message = "Recipe reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @NotBlank(message = "Material name is mandatory")
    @Size(max = 255, message = "Material name must not exceed 255 characters")
    @Column(name = "material_name", nullable = false, length = 255)
    private String materialName;

    @NotNull(message = "Quantity is mandatory")
    @Positive(message = "Quantity must be greater than zero")
    @Column(name = "quantity", nullable = false, precision = 12, scale = 4)
    private BigDecimal quantity;

    @NotBlank(message = "Unit is mandatory")
    @Size(max = 50, message = "Unit must not exceed 50 characters")
    @Column(name = "unit", nullable = false, length = 50)
    private String unit;

    public RecipeMaterial() {
    }

    public RecipeMaterial(String materialName, BigDecimal quantity, String unit) {
        this.materialName = materialName;
        this.quantity = quantity;
        this.unit = unit;
    }

    public RecipeMaterial(Recipe recipe, String materialName, BigDecimal quantity, String unit) {
        this.recipe = recipe;
        this.materialName = materialName;
        this.quantity = quantity;
        this.unit = unit;
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Recipe getRecipe() {
        return recipe;
    }

    public void setRecipe(Recipe recipe) {
        this.recipe = recipe;
    }

    public String getMaterialName() {
        return materialName;
    }

    public void setMaterialName(String materialName) {
        this.materialName = materialName;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecipeMaterial that = (RecipeMaterial) o;
        if (id != null && that.id != null) {
            return Objects.equals(id, that.id);
        }
        return Objects.equals(materialName, that.materialName) &&
                Objects.equals(quantity, that.quantity) &&
                Objects.equals(unit, that.unit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, materialName, quantity, unit);
    }

    @Override
    public String toString() {
        return "RecipeMaterial{" +
                "id=" + id +
                ", materialName='" + materialName + '\'' +
                ", quantity=" + quantity +
                ", unit='" + unit + '\'' +
                '}';
    }
}

