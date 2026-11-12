package com.pharmatrack.repository;

import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Product} entity operations.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Retrieve a product by its unique product code.
     *
     * @param productCode unique product identifier
     * @return an {@link Optional} containing the product if found, or empty if not
     */
    Optional<Product> findByProductCode(String productCode);

    /**
     * Check whether a product with the given product code exists.
     *
     * @param productCode unique product identifier
     * @return true if a product with the code exists, false otherwise
     */
    boolean existsByProductCode(String productCode);

    /**
     * Retrieve all products matching the specified lifecycle status.
     *
     * @param status lifecycle status (ACTIVE or INACTIVE)
     * @return list of matching products
     */
    List<Product> findByStatus(ProductStatus status);
}

