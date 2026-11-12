package com.pharmatrack.repository;

import com.pharmatrack.entity.RecipeMaterial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link RecipeMaterial} read-only linking with raw material master.
 */
@Repository
public interface RecipeMaterialRepository extends JpaRepository<RecipeMaterial, Long> {

    @Query("SELECT rm FROM RecipeMaterial rm JOIN FETCH rm.recipe r JOIN FETCH r.product ORDER BY rm.materialName ASC, r.recipeCode ASC")
    List<RecipeMaterial> findAllWithRecipeAndProduct();
}

