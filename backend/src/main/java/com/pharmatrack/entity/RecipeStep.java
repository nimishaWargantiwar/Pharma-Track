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
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/**
 * JPA entity representing a sequential Manufacturing Step in a Recipe.
 */
@Entity
@Table(name = "recipe_steps", uniqueConstraints = {
        @UniqueConstraint(name = "uk_recipe_step", columnNames = {"recipe_id", "step_number"})
})
public class RecipeStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotNull(message = "Recipe reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @NotNull(message = "Step number is mandatory")
    @Min(value = 1, message = "Step number must be at least 1")
    @Column(name = "step_number", nullable = false)
    private Integer stepNumber;

    @NotBlank(message = "Operation name is mandatory")
    @Size(max = 255, message = "Operation name must not exceed 255 characters")
    @Column(name = "operation_name", nullable = false, length = 255)
    private String operationName;

    @NotBlank(message = "Instructions are mandatory")
    @Column(name = "instructions", nullable = false, columnDefinition = "TEXT")
    private String instructions;

    public RecipeStep() {
    }

    public RecipeStep(Integer stepNumber, String operationName, String instructions) {
        this.stepNumber = stepNumber;
        this.operationName = operationName;
        this.instructions = instructions;
    }

    public RecipeStep(Recipe recipe, Integer stepNumber, String operationName, String instructions) {
        this.recipe = recipe;
        this.stepNumber = stepNumber;
        this.operationName = operationName;
        this.instructions = instructions;
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

    public Integer getStepNumber() {
        return stepNumber;
    }

    public void setStepNumber(Integer stepNumber) {
        this.stepNumber = stepNumber;
    }

    public String getOperationName() {
        return operationName;
    }

    public void setOperationName(String operationName) {
        this.operationName = operationName;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecipeStep that = (RecipeStep) o;
        if (id != null && that.id != null) {
            return Objects.equals(id, that.id);
        }
        return Objects.equals(stepNumber, that.stepNumber) &&
                Objects.equals(operationName, that.operationName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, stepNumber, operationName);
    }

    @Override
    public String toString() {
        return "RecipeStep{" +
                "id=" + id +
                ", stepNumber=" + stepNumber +
                ", operationName='" + operationName + '\'' +
                '}';
    }
}

