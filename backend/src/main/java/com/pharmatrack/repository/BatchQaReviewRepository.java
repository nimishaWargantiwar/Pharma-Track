package com.pharmatrack.repository;

import com.pharmatrack.entity.BatchQaReview;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Append-only Spring Data repository for immutable {@link BatchQaReview} disposition records.
 * Intentionally extends {@link Repository} (instead of CrudRepository/JpaRepository)
 * so delete and bulk-mutation operations are not exposed.
 */
@org.springframework.stereotype.Repository
public interface BatchQaReviewRepository extends Repository<BatchQaReview, Long> {

    BatchQaReview save(BatchQaReview review);

    BatchQaReview saveAndFlush(BatchQaReview review);

    Optional<BatchQaReview> findById(Long id);

    List<BatchQaReview> findByBatchIdOrderByReviewedAtDescIdDesc(Long batchId);

    List<BatchQaReview> findByBatchIdInOrderByReviewedAtDescIdDesc(List<Long> batchIds);

    long count();
}

