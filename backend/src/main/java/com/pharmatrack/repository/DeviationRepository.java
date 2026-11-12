package com.pharmatrack.repository;

import com.pharmatrack.entity.Deviation;
import com.pharmatrack.entity.DeviationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Deviation} records.
 */
@Repository
public interface DeviationRepository extends JpaRepository<Deviation, Long> {

    Optional<Deviation> findByDeviationNumber(String deviationNumber);

    boolean existsByDeviationNumber(String deviationNumber);

    List<Deviation> findByBatchIdOrderByReportedAtDescIdDesc(Long batchId);

    List<Deviation> findByBatchIdIn(List<Long> batchIds);

    List<Deviation> findByBatchIdAndStatusNot(Long batchId, DeviationStatus status);

    List<Deviation> findByBatchIdAndStatusNotOrderByReportedAtDescIdDesc(Long batchId, DeviationStatus status);

    boolean existsByBatchIdAndStatusNot(Long batchId, DeviationStatus status);

    long countByBatchIdAndStatusNot(Long batchId, DeviationStatus status);

    long countByBatchId(Long batchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Deviation d WHERE d.id = :id")
    Optional<Deviation> findByIdWithLock(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Deviation d WHERE d.batch.id = :batchId AND d.status <> :closedStatus ORDER BY d.id ASC")
    List<Deviation> findByBatchIdAndStatusNotWithLock(@Param("batchId") Long batchId,
                                                     @Param("closedStatus") DeviationStatus closedStatus);

    @Query("SELECT d FROM Deviation d " +
            "JOIN FETCH d.batch b " +
            "JOIN FETCH b.order o " +
            "JOIN FETCH o.product p " +
            "LEFT JOIN FETCH d.qualityCheck qc " +
            "LEFT JOIN FETCH d.executionStep es " +
            "ORDER BY d.reportedAt DESC, d.id DESC")
    List<Deviation> findAllWithDetailsOrderByReportedAtDesc();

    @Query("SELECT d FROM Deviation d " +
            "JOIN FETCH d.batch b " +
            "JOIN FETCH b.order o " +
            "JOIN FETCH o.product p " +
            "LEFT JOIN FETCH d.qualityCheck qc " +
            "LEFT JOIN FETCH d.executionStep es " +
            "WHERE d.id = :id")
    Optional<Deviation> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT d FROM Deviation d " +
            "JOIN FETCH d.batch b " +
            "JOIN FETCH b.order o " +
            "JOIN FETCH o.product p " +
            "LEFT JOIN FETCH d.qualityCheck qc " +
            "LEFT JOIN FETCH d.executionStep es " +
            "WHERE b.id = :batchId " +
            "ORDER BY d.reportedAt DESC, d.id DESC")
    List<Deviation> findByBatchIdWithDetailsOrderByReportedAtDesc(@Param("batchId") Long batchId);
}

