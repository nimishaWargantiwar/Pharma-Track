package com.pharmatrack.repository;

import com.pharmatrack.entity.StorageLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link StorageLocation} records within warehouses.
 */
@Repository
public interface StorageLocationRepository extends JpaRepository<StorageLocation, Long> {

    boolean existsByWarehouseIdAndLocationCodeIgnoreCase(Long warehouseId, String locationCode);

    Optional<StorageLocation> findByWarehouseIdAndLocationCodeIgnoreCase(Long warehouseId, String locationCode);

    List<StorageLocation> findByWarehouseIdOrderByLocationCodeAsc(Long warehouseId);

    @Query("SELECT sl FROM StorageLocation sl JOIN FETCH sl.warehouse ORDER BY sl.warehouse.warehouseName ASC, sl.locationCode ASC")
    List<StorageLocation> findAllWithWarehouse();
}

