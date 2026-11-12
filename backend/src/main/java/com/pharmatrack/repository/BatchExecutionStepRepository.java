package com.pharmatrack.repository;

import com.pharmatrack.entity.BatchExecutionStep;
import com.pharmatrack.entity.ExecutionStepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link BatchExecutionStep} entity operations.
 */
@Repository
public interface BatchExecutionStepRepository extends JpaRepository<BatchExecutionStep, Long> {

    /**
     * Retrieve all execution steps for a batch, ordered by step number ascending.
     *
     * @param batchId target batch identifier
     * @return ordered list of execution steps for the batch
     */
    List<BatchExecutionStep> findByBatchIdOrderByStepNumberAsc(Long batchId);

    /**
     * Find a specific execution step within a batch by batch ID and step number.
     *
     * @param batchId    target batch identifier
     * @param stepNumber step sequence number
     * @return an {@link Optional} containing the matching step if found
     */
    Optional<BatchExecutionStep> findByBatchIdAndStepNumber(Long batchId, Integer stepNumber);

    /**
     * Check whether any execution steps exist for the specified batch.
     *
     * @param batchId target batch identifier
     * @return true if at least one execution step exists for the batch
     */
    boolean existsByBatchId(Long batchId);

    /**
     * Check whether a batch has any execution step in the given status.
     *
     * @param batchId target batch identifier
     * @param status  execution step status
     * @return true if at least one step matches the batch and status
     */
    boolean existsByBatchIdAndStatus(Long batchId, ExecutionStepStatus status);

    /**
     * Count execution steps for a batch matching the given status.
     *
     * @param batchId target batch identifier
     * @param status  execution step status
     * @return count of matching steps
     */
    long countByBatchIdAndStatus(Long batchId, ExecutionStepStatus status);

    /**
     * Count execution steps for a batch whose status is not the supplied status.
     *
     * @param batchId target batch identifier
     * @param status  excluded execution step status
     * @return count of steps with a status different from the supplied status
     */
    long countByBatchIdAndStatusNot(Long batchId, ExecutionStepStatus status);
}

