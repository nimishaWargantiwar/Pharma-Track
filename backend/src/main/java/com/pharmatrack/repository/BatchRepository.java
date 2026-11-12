package com.pharmatrack.repository;

import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Batch} entity operations.
 */
@Repository
public interface BatchRepository extends JpaRepository<Batch, Long> {

    /**
     * Retrieve a batch by its unique batch number.
     *
     * @param batchNumber unique batch identifier
     * @return an {@link Optional} containing the batch if found, or empty if not
     */
    Optional<Batch> findByBatchNumber(String batchNumber);

    Optional<Batch> findByBatchNumberIgnoreCase(String batchNumber);

    Optional<Batch> findByQrCode(String qrCode);

    Optional<Batch> findByQrCodeIgnoreCase(String qrCode);

    @Query("SELECT b FROM Batch b " +
            "JOIN FETCH b.order o " +
            "JOIN FETCH o.product p " +
            "JOIN FETCH o.recipe r " +
            "WHERE b.id = :id")
    Optional<Batch> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT b FROM Batch b " +
            "JOIN FETCH b.order o " +
            "JOIN FETCH o.product p " +
            "JOIN FETCH o.recipe r " +
            "WHERE LOWER(b.qrCode) = LOWER(:identifier) OR LOWER(b.batchNumber) = LOWER(:identifier)")
    Optional<Batch> findByIdentifierWithDetails(@Param("identifier") String identifier);

    /**
     * Check whether a batch with the given batch number exists.
     *
     * @param batchNumber unique batch identifier
     * @return true if a batch with the number exists, false otherwise
     */
    boolean existsByBatchNumber(String batchNumber);

    /**
     * Retrieve all batches belonging to a specific manufacturing order.
     *
     * @param orderId target manufacturing order identifier
     * @return list of matching batches
     */
    List<Batch> findByOrderId(Long orderId);

    /**
     * Retrieve all batches matching the specified lifecycle status.
     *
     * @param status lifecycle status
     * @return list of matching batches
     */
    List<Batch> findByStatus(BatchStatus status);

    /**
     * Retrieve batches belonging to a specific manufacturing order filtered by status.
     *
     * @param orderId target manufacturing order identifier
     * @param status  lifecycle status
     * @return list of matching batches
     */
    List<Batch> findByOrderIdAndStatus(Long orderId, BatchStatus status);

    /**
     * Retrieve a batch by ID with a pessimistic write lock (SELECT ... FOR UPDATE)
     * to help serialize concurrent production execution operations for the same batch.
     *
     * @param id batch primary key
     * @return an {@link Optional} containing the locked batch if found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Batch b WHERE b.id = :id")
    Optional<Batch> findByIdWithLock(@Param("id") Long id);

    /**
     * Retrieve batches matching the specified batch status and parent order status,
     * eagerly fetching the parent order, product, and recipe to avoid N+1 queries
     * when loading the production execution queue.
     *
     * @param batchStatus target batch lifecycle status
     * @param orderStatus target parent manufacturing order lifecycle status
     * @return list of matching batches with order, product, and recipe initialized
     */
    @Query("SELECT b FROM Batch b " +
            "JOIN FETCH b.order o " +
            "JOIN FETCH o.product " +
            "JOIN FETCH o.recipe " +
            "WHERE b.status = :batchStatus AND o.status = :orderStatus " +
            "ORDER BY b.startTime ASC, b.id ASC")
    List<Batch> findByStatusAndOrderStatusWithDetails(@Param("batchStatus") BatchStatus batchStatus,
                                                      @Param("orderStatus") OrderStatus orderStatus);

    /**
     * Retrieve completed production batches for the QA Inspection Queue, eagerly
     * initializing order, product, and recipe details to prevent N+1 queries.
     *
     * @param status target lifecycle status (typically {@link BatchStatus#COMPLETED})
     * @return list of matching batches ordered with most recently completed first
     */
    @Query("SELECT b FROM Batch b " +
            "JOIN FETCH b.order o " +
            "JOIN FETCH o.product " +
            "JOIN FETCH o.recipe " +
            "WHERE b.status = :status " +
            "ORDER BY b.endTime DESC, b.id DESC")
    List<Batch> findByStatusWithDetailsOrderByEndTimeDesc(@Param("status") BatchStatus status);

    /**
     * Retrieve batches matching both manufacturing status and QA disposition status
     * with parent order, product, and recipe eagerly initialized.
     *
     * @param status   target lifecycle status
     * @param qaStatus target QA status
     * @return list of matching batches ordered by completion time descending
     */
    @Query("SELECT b FROM Batch b " +
            "JOIN FETCH b.order o " +
            "JOIN FETCH o.product " +
            "JOIN FETCH o.recipe " +
            "WHERE b.status = :status AND b.qaStatus = :qaStatus " +
            "ORDER BY b.endTime DESC, b.id DESC")
    List<Batch> findByStatusAndQaStatusWithDetailsOrderByEndTimeDesc(@Param("status") BatchStatus status,
                                                                     @Param("qaStatus") BatchQualityStatus qaStatus);

    /**
     * Additive, timestamp-preserving backfill assigning {@link BatchQualityStatus#PENDING_QA}
     * to existing {@link BatchStatus#COMPLETED} batches whose {@code qa_status} is currently NULL.
     * Executes directly in SQL without triggering JPA {@code @PreUpdate} or modifying
     * {@code updated_at}, {@code actual_yield}, or manufacturing timestamps.
     *
     * @return number of batch rows initialized to PENDING_QA
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Batch b SET b.qaStatus = com.pharmatrack.entity.BatchQualityStatus.PENDING_QA " +
            "WHERE b.status = com.pharmatrack.entity.BatchStatus.COMPLETED AND b.qaStatus IS NULL")
    int initializePendingQaStatusForCompletedBatches();
}

