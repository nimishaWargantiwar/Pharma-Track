package com.pharmatrack.repository;

import com.pharmatrack.entity.CopilotRequest;
import com.pharmatrack.entity.CopilotRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CopilotRequest} entity operations.
 */
@Repository
public interface CopilotRequestRepository extends JpaRepository<CopilotRequest, Long> {

    /**
     * Retrieve all requests belonging to a conversation ordered chronologically.
     */
    List<CopilotRequest> findByConversationIdOrderByStartedAtAsc(Long conversationId);

    /**
     * Find a request by conversation ID and unique client request identifier.
     */
    Optional<CopilotRequest> findByConversationIdAndClientRequestId(Long conversationId, String clientRequestId);

    /**
     * Find a request by conversation ID and request primary key.
     */
    Optional<CopilotRequest> findByConversationIdAndId(Long conversationId, Long id);

    /**
     * Acquire an exclusive pessimistic row lock (SELECT ... FOR UPDATE) on a request.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CopilotRequest r WHERE r.id = :id")
    Optional<CopilotRequest> findByIdWithLock(@Param("id") Long id);

    /**
     * Find all requests with a specific status whose lease has expired before the cutoff.
     */
    List<CopilotRequest> findByStatusAndLeaseExpiresAtBefore(CopilotRequestStatus status, LocalDateTime cutoff);

    /**
     * Find expired candidate request IDs with a specific status whose lease expired before the cutoff,
     * bounded by a pageable limit and ordered by expiration.
     */
    @Query("SELECT r.id FROM CopilotRequest r WHERE r.status = :status AND r.leaseExpiresAt < :cutoff ORDER BY r.leaseExpiresAt ASC")
    List<Long> findExpiredCandidateIds(@Param("status") CopilotRequestStatus status,
                                       @Param("cutoff") LocalDateTime cutoff,
                                       Pageable pageable);

    /**
     * Count requests belonging to a conversation.
     */
    long countByConversationId(Long conversationId);
}
