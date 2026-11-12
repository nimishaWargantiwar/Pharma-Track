package com.pharmatrack.repository;

import com.pharmatrack.entity.RawMaterial;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link RawMaterial} master records.
 */
@Repository
public interface RawMaterialRepository extends JpaRepository<RawMaterial, Long> {

    boolean existsByMaterialCodeIgnoreCase(String materialCode);

    Optional<RawMaterial> findByMaterialCodeIgnoreCase(String materialCode);

    boolean existsByMaterialNameIgnoreCase(String materialName);

    Optional<RawMaterial> findByMaterialNameIgnoreCase(String materialName);

    List<RawMaterial> findAllByOrderByMaterialNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rm FROM RawMaterial rm WHERE rm.id = :id")
    Optional<RawMaterial> findByIdWithLock(@Param("id") Long id);
}

