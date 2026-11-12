package com.pharmatrack.repository;

import com.pharmatrack.entity.StockMovement;
import com.pharmatrack.entity.StockMovementType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Append-only repository for {@link StockMovement} entries.
 * Intentionally extends {@link Repository} (instead of CrudRepository/JpaRepository)
 * so update/delete and bulk-mutation operations are not exposed.
 */
@org.springframework.stereotype.Repository
public interface StockMovementRepository extends Repository<StockMovement, Long> {

    StockMovement save(StockMovement stockMovement);

    StockMovement saveAndFlush(StockMovement stockMovement);

    void flush();

    Optional<StockMovement> findById(Long id);

    boolean existsByReferenceNumberIgnoreCase(String referenceNumber);

    @Query("SELECT sm FROM StockMovement sm " +
            "JOIN FETCH sm.rawMaterial " +
            "JOIN FETCH sm.materialLot " +
            "JOIN FETCH sm.warehouse " +
            "JOIN FETCH sm.storageLocation " +
            "LEFT JOIN FETCH sm.manufacturingOrder " +
            "LEFT JOIN FETCH sm.batch " +
            "ORDER BY sm.occurredAt DESC, sm.id DESC")
    List<StockMovement> findAllWithDetailsOrderByOccurredAtDescIdDesc();

    @Query("SELECT sm FROM StockMovement sm " +
            "JOIN FETCH sm.rawMaterial " +
            "JOIN FETCH sm.materialLot " +
            "JOIN FETCH sm.warehouse " +
            "JOIN FETCH sm.storageLocation " +
            "LEFT JOIN FETCH sm.manufacturingOrder " +
            "LEFT JOIN FETCH sm.batch " +
            "WHERE sm.rawMaterial.id = :materialId " +
            "ORDER BY sm.occurredAt DESC, sm.id DESC")
    List<StockMovement> findByRawMaterialIdWithDetails(@Param("materialId") Long materialId);

    @Query("SELECT sm FROM StockMovement sm " +
            "JOIN FETCH sm.rawMaterial " +
            "JOIN FETCH sm.materialLot " +
            "JOIN FETCH sm.warehouse " +
            "JOIN FETCH sm.storageLocation " +
            "LEFT JOIN FETCH sm.manufacturingOrder " +
            "LEFT JOIN FETCH sm.batch " +
            "WHERE sm.manufacturingOrder.id = :orderId " +
            "ORDER BY sm.occurredAt DESC, sm.id DESC")
    List<StockMovement> findByManufacturingOrderIdWithDetails(@Param("orderId") Long orderId);

    @Query("SELECT sm FROM StockMovement sm " +
            "JOIN FETCH sm.rawMaterial " +
            "JOIN FETCH sm.materialLot " +
            "JOIN FETCH sm.warehouse " +
            "JOIN FETCH sm.storageLocation " +
            "LEFT JOIN FETCH sm.manufacturingOrder " +
            "LEFT JOIN FETCH sm.batch " +
            "WHERE sm.batch.id = :batchId " +
            "ORDER BY sm.occurredAt DESC, sm.id DESC")
    List<StockMovement> findByBatchIdWithDetails(@Param("batchId") Long batchId);

    @Query("SELECT sm FROM StockMovement sm " +
            "JOIN FETCH sm.rawMaterial " +
            "JOIN FETCH sm.materialLot " +
            "JOIN FETCH sm.warehouse " +
            "JOIN FETCH sm.storageLocation " +
            "LEFT JOIN FETCH sm.manufacturingOrder " +
            "LEFT JOIN FETCH sm.batch " +
            "WHERE sm.batch.id = :batchId OR (sm.batch IS NULL AND sm.manufacturingOrder.id = :orderId) " +
            "ORDER BY sm.occurredAt DESC, sm.id DESC")
    List<StockMovement> findByBatchIdOrManufacturingOrderIdWithDetails(@Param("batchId") Long batchId,
                                                                       @Param("orderId") Long orderId);

    @Query("SELECT COALESCE(SUM(ABS(sm.quantityDelta)), 0) FROM StockMovement sm " +
            "WHERE sm.manufacturingOrder.id = :orderId " +
            "AND sm.rawMaterial.id = :materialId " +
            "AND sm.movementType = :movementType")
    BigDecimal sumIssuedQuantityForOrderAndMaterial(@Param("orderId") Long orderId,
                                                    @Param("materialId") Long materialId,
                                                    @Param("movementType") StockMovementType movementType);

    long count();
}

