package com.pharmatrack.controller;

import com.pharmatrack.dto.RecipeRequestDto;
import com.pharmatrack.dto.RecipeResponseDto;
import com.pharmatrack.dto.RecipeStatusUpdateDto;
import com.pharmatrack.service.RecipeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing endpoints for Master Recipe management.
 */
@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final RecipeService recipeService;

    public RecipeController(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    /**
     * Create a new master recipe.
     * POST /api/recipes
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<RecipeResponseDto> createRecipe(@Valid @RequestBody RecipeRequestDto requestDto) {
        RecipeResponseDto createdRecipe = recipeService.createRecipe(requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdRecipe);
    }

    /**
     * Retrieve all master recipes.
     * GET /api/recipes
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR', 'QC_ANALYST', 'QA_REVIEWER')")
    public ResponseEntity<List<RecipeResponseDto>> getAllRecipes() {
        List<RecipeResponseDto> recipes = recipeService.getAllRecipes();
        return ResponseEntity.ok(recipes);
    }

    /**
     * Retrieve a recipe by ID.
     * GET /api/recipes/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR', 'QC_ANALYST', 'QA_REVIEWER')")
    public ResponseEntity<RecipeResponseDto> getRecipeById(@PathVariable Long id) {
        RecipeResponseDto recipe = recipeService.getRecipeById(id);
        return ResponseEntity.ok(recipe);
    }

    /**
     * Retrieve all recipes for a specific product.
     * GET /api/recipes/product/{productId}
     */
    @GetMapping("/product/{productId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR', 'QC_ANALYST', 'QA_REVIEWER')")
    public ResponseEntity<List<RecipeResponseDto>> getRecipesByProductId(@PathVariable Long productId) {
        List<RecipeResponseDto> recipes = recipeService.getRecipesByProductId(productId);
        return ResponseEntity.ok(recipes);
    }

    /**
     * Update an existing DRAFT recipe.
     * PUT /api/recipes/{id}
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<RecipeResponseDto> updateRecipe(
            @PathVariable Long id,
            @Valid @RequestBody RecipeRequestDto requestDto) {
        RecipeResponseDto updatedRecipe = recipeService.updateRecipe(id, requestDto);
        return ResponseEntity.ok(updatedRecipe);
    }

    /**
     * Update recipe lifecycle status.
     * PATCH /api/recipes/{id}/status
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'QA_REVIEWER')")
    public ResponseEntity<RecipeResponseDto> updateRecipeStatus(
            @PathVariable Long id,
            @Valid @RequestBody RecipeStatusUpdateDto statusDto) {
        RecipeResponseDto updatedRecipe = recipeService.updateRecipeStatus(id, statusDto);
        return ResponseEntity.ok(updatedRecipe);
    }

    /**
     * Create a new version of an existing recipe.
     * POST /api/recipes/{id}/new-version
     */
    @PostMapping("/{id}/new-version")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<RecipeResponseDto> createNewVersion(@PathVariable Long id) {
        RecipeResponseDto newVersion = recipeService.createNewVersion(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(newVersion);
    }

    /**
     * Delete a DRAFT recipe.
     * DELETE /api/recipes/{id}
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRODUCTION_SUPERVISOR')")
    public ResponseEntity<Void> deleteRecipe(@PathVariable Long id) {
        recipeService.deleteRecipe(id);
        return ResponseEntity.noContent().build();
    }
}

