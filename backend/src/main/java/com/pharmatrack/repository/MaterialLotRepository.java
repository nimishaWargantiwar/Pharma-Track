package com.pharmatrack.repository;

import com.pharmatrack.entity.MaterialLot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link MaterialLot} traceability records,
 * supporting pessimistic row locking for concurrent stock mutations.
 */
@Repository
public interface MaterialLotRepository extends JpaRepository<MaterialLot, Long> {

    boolean existsByRawMaterialIdAndLotNumberIgnoreCase(Long rawMaterialId, String lotNumber);

    Optional<MaterialLot> findByRawMaterialIdAndLotNumberIgnoreCase(Long rawMaterialId, String lotNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ml FROM MaterialLot ml WHERE ml.id = :id")
    Optional<MaterialLot> findByIdWithLock(@Param("id") Long id);

    @Query("SELECT ml.rawMaterial.id FROM MaterialLot ml WHERE ml.id = :id")
    Optional<Long> findRawMaterialIdById(@Param("id") Long id);

    @Query("SELECT ml FROM MaterialLot ml " +
            "JOIN FETCH ml.rawMaterial " +
            "JOIN FETCH ml.warehouse " +
            "JOIN FETCH ml.storageLocation " +
            "ORDER BY ml.expiryDate ASC, ml.receivedDate ASC, ml.id ASC")
    List<MaterialLot> findAllWithDetails();

    @Query("SELECT ml FROM MaterialLot ml " +
            "JOIN FETCH ml.rawMaterial " +
            "JOIN FETCH ml.warehouse " +
            "JOIN FETCH ml.storageLocation " +
            "WHERE ml.rawMaterial.id = :materialId " +
            "ORDER BY ml.expiryDate ASC, ml.receivedDate ASC, ml.id ASC")
    List<MaterialLot> findByRawMaterialIdWithDetails(@Param("materialId") Long materialId);

    @Query("SELECT ml FROM MaterialLot ml " +
            "JOIN FETCH ml.rawMaterial " +
            "JOIN FETCH ml.warehouse " +
            "JOIN FETCH ml.storageLocation " +
            "WHERE ml.warehouse.id = :warehouseId " +
            "ORDER BY ml.expiryDate ASC, ml.receivedDate ASC, ml.id ASC")
    List<MaterialLot> findByWarehouseIdWithDetails(@Param("warehouseId") Long warehouseId);
}
