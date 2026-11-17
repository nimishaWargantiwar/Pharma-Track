package com.pharmatrack.repository;

import com.pharmatrack.entity.CopilotConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CopilotConversation} entity operations.
 */
@Repository
public interface CopilotConversationRepository extends JpaRepository<CopilotConversation, Long> {

    /**
     * Retrieve paginated conversations belonging to a user, ordered by most recently updated first,
     * with ID descending as a deterministic secondary sort.
     */
    Page<CopilotConversation> findByUserIdOrderByUpdatedAtDescIdDesc(Long userId, Pageable pageable);

    /**
     * Retrieve all conversations belonging to a user, ordered by most recently updated first,
     * with ID descending as a deterministic secondary sort.
     */
    List<CopilotConversation> findByUserIdOrderByUpdatedAtDescIdDesc(Long userId);

    /**
     * Backward-compatible alias delegating to deterministic secondary sort.
     */
    default Page<CopilotConversation> findByUserIdOrderByUpdatedAtDesc(Long userId, Pageable pageable) {
        return findByUserIdOrderByUpdatedAtDescIdDesc(userId, pageable);
    }

    /**
     * Backward-compatible alias delegating to deterministic secondary sort.
     */
    default List<CopilotConversation> findByUserIdOrderByUpdatedAtDesc(Long userId) {
        return findByUserIdOrderByUpdatedAtDescIdDesc(userId);
    }

    /**
     * Retrieve a specific conversation by ID ensuring it belongs to the authenticated user.
     */
    Optional<CopilotConversation> findByIdAndUserId(Long id, Long userId);

    /**
     * Check whether a conversation exists and belongs to the specified user.
     */
    boolean existsByIdAndUserId(Long id, Long userId);

    /**
     * Delete a conversation by ID ensuring ownership check.
     */
    void deleteByIdAndUserId(Long id, Long userId);

    /**
     * Touch conversation updated_at timestamp atomically.
     */
    @Modifying
    @Query("UPDATE CopilotConversation c SET c.updatedAt = :now WHERE c.id = :id")
    int touchUpdatedAt(@Param("id") Long id, @Param("now") LocalDateTime now);
}
