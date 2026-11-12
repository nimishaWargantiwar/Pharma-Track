package com.pharmatrack.repository;

import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Recipe} aggregate root operations.
 */
@Repository
public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    /**
     * Retrieve a recipe by its unique recipe code.
     *
     * @param recipeCode unique business code
     * @return an {@link Optional} containing the recipe if found
     */
    Optional<Recipe> findByRecipeCode(String recipeCode);

    /**
     * Check whether a recipe with the specified recipe code exists.
     *
     * @param recipeCode unique business code
     * @return true if it exists, false otherwise
     */
    boolean existsByRecipeCode(String recipeCode);

    /**
     * Retrieve all recipes associated with a product ID, ordered by version descending.
     *
     * @param productId target product identifier
     * @return list of matching recipes
     */
    List<Recipe> findByProductId(Long productId);

    /**
     * Retrieve recipes for a product filtered by lifecycle status.
     *
     * @param productId target product identifier
     * @param status    lifecycle status (DRAFT, APPROVED, OBSOLETE)
     * @return list of matching recipes
     */
    List<Recipe> findByProductIdAndStatus(Long productId, RecipeStatus status);

    /**
     * Find the recipe with the highest version number for a given product.
     *
     * @param productId target product identifier
     * @return an {@link Optional} containing the latest version recipe if one exists
     */
    Optional<Recipe> findFirstByProductIdOrderByVersionDesc(Long productId);

    /**
     * Query the maximum version number existing for a product.
     *
     * @param productId target product identifier
     * @return an {@link Optional} containing the maximum version number, or empty if no recipes exist
     */
    @Query("SELECT MAX(r.version) FROM Recipe r WHERE r.product.id = :productId")
    Optional<Integer> findMaxVersionByProductId(@Param("productId") Long productId);

    /**
     * Find a recipe by product ID and version.
     *
     * @param productId target product identifier
     * @param version   recipe version number
     * @return an {@link Optional} containing the matching recipe
     */
    Optional<Recipe> findByProductIdAndVersion(Long productId, Integer version);
}

