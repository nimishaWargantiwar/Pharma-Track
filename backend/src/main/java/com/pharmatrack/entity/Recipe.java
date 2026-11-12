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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JPA entity representing a Master Manufacturing Recipe in PharmaTrack.
 * Serves as the aggregate root for recipe materials and manufacturing steps.
 */
@Entity
@Table(name = "recipes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_recipe_code", columnNames = "recipe_code"),
        @UniqueConstraint(name = "uk_recipe_product_version", columnNames = {"product_id", "version"})
})
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotBlank(message = "Recipe code is mandatory")
    @Size(max = 50, message = "Recipe code must not exceed 50 characters")
    @Column(name = "recipe_code", nullable = false, unique = true, length = 50)
    private String recipeCode;

    @NotNull(message = "Product reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @NotNull(message = "Version is mandatory")
    @Min(value = 1, message = "Version must be at least 1")
    @Column(name = "version", nullable = false)
    private Integer version;

    @NotNull(message = "Batch size is mandatory")
    @Positive(message = "Batch size must be greater than zero")
    @Column(name = "batch_size", nullable = false, precision = 12, scale = 4)
    private BigDecimal batchSize;

    @NotBlank(message = "Batch unit is mandatory")
    @Size(max = 50, message = "Batch unit must not exceed 50 characters")
    @Column(name = "batch_unit", nullable = false, length = 50)
    private String batchUnit;

    @NotNull(message = "Status is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RecipeStatus status;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RecipeMaterial> materials = new ArrayList<>();

    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepNumber ASC")
    private List<RecipeStep> steps = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Recipe() {
    }

    public Recipe(String recipeCode, Product product, Integer version, BigDecimal batchSize,
                  String batchUnit, RecipeStatus status, String description) {
        this.recipeCode = recipeCode;
        this.product = product;
        this.version = version;
        this.batchSize = batchSize;
        this.batchUnit = batchUnit;
        this.status = status;
        this.description = description;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = AppTime.nowDateTime();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = RecipeStatus.DRAFT;
        }
        if (this.version == null) {
            this.version = 1;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = AppTime.nowDateTime();
    }

    // Bidirectional helper methods

    public void addMaterial(RecipeMaterial material) {
        if (material != null) {
            this.materials.add(material);
            material.setRecipe(this);
        }
    }

    public void removeMaterial(RecipeMaterial material) {
        if (material != null) {
            this.materials.remove(material);
            material.setRecipe(null);
        }
    }

    public void addStep(RecipeStep step) {
        if (step != null) {
            this.steps.add(step);
            step.setRecipe(this);
        }
    }

    public void removeStep(RecipeStep step) {
        if (step != null) {
            this.steps.remove(step);
            step.setRecipe(null);
        }
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRecipeCode() {
        return recipeCode;
    }

    public void setRecipeCode(String recipeCode) {
        this.recipeCode = recipeCode;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public BigDecimal getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(BigDecimal batchSize) {
        this.batchSize = batchSize;
    }

    public String getBatchUnit() {
        return batchUnit;
    }

    public void setBatchUnit(String batchUnit) {
        this.batchUnit = batchUnit;
    }

    public RecipeStatus getStatus() {
        return status;
    }

    public void setStatus(RecipeStatus status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<RecipeMaterial> getMaterials() {
        return materials;
    }

    public void setMaterials(List<RecipeMaterial> materials) {
        this.materials = (materials != null) ? materials : new ArrayList<>();
    }

    public List<RecipeStep> getSteps() {
        return steps;
    }

    public void setSteps(List<RecipeStep> steps) {
        this.steps = (steps != null) ? steps : new ArrayList<>();
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
        if (o == null || getClass() != o.getClass()) return false;
        Recipe recipe = (Recipe) o;
        return Objects.equals(recipeCode, recipe.recipeCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recipeCode);
    }

    @Override
    public String toString() {
        return "Recipe{" +
                "id=" + id +
                ", recipeCode='" + recipeCode + '\'' +
                ", version=" + version +
                ", batchSize=" + batchSize +
                ", batchUnit='" + batchUnit + '\'' +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

