package com.pharmatrack.repository;

import com.pharmatrack.entity.QualityCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link QualityCheck} records.
 */
@Repository
public interface QualityCheckRepository extends JpaRepository<QualityCheck, Long> {

    List<QualityCheck> findByBatchIdOrderByCheckedAtAscIdAsc(Long batchId);

    List<QualityCheck> findByBatchIdIn(List<Long> batchIds);

    long countByBatchId(Long batchId);

    @Query("SELECT q FROM QualityCheck q JOIN FETCH q.batch b WHERE q.id = :id")
    Optional<QualityCheck> findByIdWithBatch(@Param("id") Long id);
}

