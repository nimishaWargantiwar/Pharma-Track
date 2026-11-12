package com.pharmatrack.dto;

import com.pharmatrack.entity.RecipeStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Payload DTO for updating a Master Recipe's lifecycle status.
 */
public class RecipeStatusUpdateDto {

    @NotNull(message = "Recipe status is mandatory")
    private RecipeStatus status;

    public RecipeStatusUpdateDto() {
    }

    public RecipeStatusUpdateDto(RecipeStatus status) {
        this.status = status;
    }

    public RecipeStatus getStatus() {
        return status;
    }

    public void setStatus(RecipeStatus status) {
        this.status = status;
    }
}

