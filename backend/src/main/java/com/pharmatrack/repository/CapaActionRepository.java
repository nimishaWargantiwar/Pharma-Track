package com.pharmatrack.repository;

import com.pharmatrack.entity.CapaAction;
import com.pharmatrack.entity.CapaStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CapaAction} records.
 */
@Repository
public interface CapaActionRepository extends JpaRepository<CapaAction, Long> {

    Optional<CapaAction> findByCapaNumber(String capaNumber);

    boolean existsByCapaNumber(String capaNumber);

    List<CapaAction> findByDeviationIdOrderByIdAsc(Long deviationId);

    List<CapaAction> findByDeviationIdInOrderByIdAsc(List<Long> deviationIds);

    long countByDeviationId(Long deviationId);

    long countByDeviationIdAndStatus(Long deviationId, CapaStatus status);

    long countByDeviationIdAndStatusNot(Long deviationId, CapaStatus status);

    long countByDeviationIdAndStatusIn(Long deviationId, List<CapaStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CapaAction c WHERE c.id = :id")
    Optional<CapaAction> findByIdWithLock(@Param("id") Long id);

    @Query("SELECT c FROM CapaAction c " +
            "JOIN FETCH c.deviation d " +
            "JOIN FETCH d.batch b " +
            "WHERE c.id = :id")
    Optional<CapaAction> findByIdWithDeviationAndBatch(@Param("id") Long id);
}

