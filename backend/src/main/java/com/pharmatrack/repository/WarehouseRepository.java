package com.pharmatrack.repository;

import com.pharmatrack.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Warehouse} facilities.
 */
@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    boolean existsByWarehouseCodeIgnoreCase(String warehouseCode);

    Optional<Warehouse> findByWarehouseCodeIgnoreCase(String warehouseCode);

    List<Warehouse> findAllByOrderByWarehouseNameAsc();
}

