package com.pharmatrack.repository;

import com.pharmatrack.entity.CopilotMessage;
import com.pharmatrack.entity.CopilotMessageRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CopilotMessage} entity operations.
 */
@Repository
public interface CopilotMessageRepository extends JpaRepository<CopilotMessage, Long> {

    /**
     * Retrieve all messages for a specific request ordered chronologically.
     */
    List<CopilotMessage> findByRequestIdOrderByCreatedAtAsc(Long requestId);

    /**
     * Find a message for a specific request and role.
     */
    Optional<CopilotMessage> findByRequestIdAndRole(Long requestId, CopilotMessageRole role);

    /**
     * Retrieve all messages for an entire conversation ordered chronologically by request start time and message ID,
     * eagerly fetching the parent request to avoid N+1 query overhead.
     */
    @Query("SELECT m FROM CopilotMessage m JOIN FETCH m.request WHERE m.request.conversation.id = :conversationId ORDER BY m.request.startedAt ASC, m.id ASC")
    List<CopilotMessage> findMessagesByConversationId(@Param("conversationId") Long conversationId);

    /**
     * Update the content and verified metadata of an existing message placeholder.
     */
    @Modifying
    @Query("UPDATE CopilotMessage m SET m.content = :content, m.model = :model, m.contextType = :contextType, m.contextIdentifier = :contextIdentifier, m.sourcesJson = :sourcesJson WHERE m.request.id = :requestId AND m.role = :role")
    int updateMessageContent(
            @Param("requestId") Long requestId,
            @Param("role") CopilotMessageRole role,
            @Param("content") String content,
            @Param("model") String model,
            @Param("contextType") String contextType,
            @Param("contextIdentifier") String contextIdentifier,
            @Param("sourcesJson") String sourcesJson
    );

    /**
     * Count messages belonging to a request.
     */
    long countByRequestId(Long requestId);

    /**
     * Count messages belonging to an entire conversation.
     */
    @Query("SELECT COUNT(m) FROM CopilotMessage m WHERE m.request.conversation.id = :conversationId")
    long countByConversationId(@Param("conversationId") Long conversationId);
}
