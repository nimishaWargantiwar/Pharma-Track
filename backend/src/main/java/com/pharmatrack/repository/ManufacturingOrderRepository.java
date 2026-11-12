package com.pharmatrack.repository;

import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link ManufacturingOrder} entity operations.
 */
@Repository
public interface ManufacturingOrderRepository extends JpaRepository<ManufacturingOrder, Long> {

    /**
     * Retrieve a manufacturing order by its unique order number.
     *
     * @param orderNumber unique order identifier
     * @return an {@link Optional} containing the order if found, or empty if not
     */
    Optional<ManufacturingOrder> findByOrderNumber(String orderNumber);

    /**
     * Check whether an order with the given order number exists.
     *
     * @param orderNumber unique order identifier
     * @return true if an order with the number exists, false otherwise
     */
    boolean existsByOrderNumber(String orderNumber);

    /**
     * Retrieve all manufacturing orders matching the specified lifecycle status.
     *
     * @param status lifecycle status
     * @return list of matching orders
     */
    List<ManufacturingOrder> findByStatus(OrderStatus status);

    /**
     * Retrieve all manufacturing orders associated with a product ID.
     *
     * @param productId target product identifier
     * @return list of matching orders
     */
    List<ManufacturingOrder> findByProductId(Long productId);

    /**
     * Retrieve all manufacturing orders associated with a recipe ID.
     *
     * @param recipeId target recipe identifier
     * @return list of matching orders
     */
    List<ManufacturingOrder> findByRecipeId(Long recipeId);

    /**
     * Retrieve manufacturing orders for a product filtered by lifecycle status.
     *
     * @param productId target product identifier
     * @param status    lifecycle status
     * @return list of matching orders
     */
    List<ManufacturingOrder> findByProductIdAndStatus(Long productId, OrderStatus status);

    /**
     * Retrieve a manufacturing order by ID with a pessimistic write lock (SELECT ... FOR UPDATE)
     * to prevent race conditions during concurrent batch status updates.
     *
     * @param id order primary key
     * @return an {@link Optional} containing the locked order if found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM ManufacturingOrder o WHERE o.id = :id")
    Optional<ManufacturingOrder> findByIdWithLock(@Param("id") Long id);
}

