package com.pharmatrack.ai.service;

import com.pharmatrack.entity.CopilotConversation;
import com.pharmatrack.entity.CopilotMessage;
import com.pharmatrack.entity.CopilotMessageRole;
import com.pharmatrack.entity.CopilotRequest;
import com.pharmatrack.entity.CopilotRequestStatus;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.CopilotConversationRepository;
import com.pharmatrack.repository.CopilotMessageRepository;
import com.pharmatrack.repository.CopilotRequestRepository;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.util.AppTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dedicated transactional helper managing isolated short-lived database transactions
 * for AI Copilot conversation persistence.
 *
 * <p>Architectural guarantees:
 * <ul>
 *   <li>All mutation methods use {@link Propagation#REQUIRES_NEW} through Spring proxy.</li>
 *   <li>Database connections and locks are never held during external LLM provider calls.</li>
 *   <li>Row-level pessimistic locking ({@code SELECT ... FOR UPDATE}) serializes completion and recovery.</li>
 *   <li>Exact affected-row count validation triggers immediate rollback on concurrency anomalies.</li>
 *   <li>Audit trail logging participates in the respective transaction without recording sensitive payloads.</li>
 * </ul>
 */
@Service
public class AiConversationTxHelper {

    private static final Logger log = LoggerFactory.getLogger(AiConversationTxHelper.class);

    public static final String AUDIT_ENTITY_TYPE = "AI_CONVERSATION";
    public static final String ACTION_CONVERSATION_CREATED = "AI_CONVERSATION_CREATED";
    public static final String ACTION_CONVERSATION_RENAMED = "AI_CONVERSATION_RENAMED";
    public static final String ACTION_CONVERSATION_DELETED = "AI_CONVERSATION_DELETED";
    public static final String ACTION_QUERY_SUBMITTED = "AI_QUERY_SUBMITTED";
    public static final String ACTION_QUERY_COMPLETED = "AI_QUERY_COMPLETED";
    public static final String ACTION_QUERY_FAILED = "AI_QUERY_FAILED";
    public static final String ACTION_REQUEST_RECOVERED = "AI_REQUEST_RECOVERED_TIMEOUT";

    private final CopilotConversationRepository conversationRepository;
    private final CopilotRequestRepository requestRepository;
    private final CopilotMessageRepository messageRepository;
    private final AuditTrailService auditTrailService;
    private final AuthenticatedActorResolver authenticatedActorResolver;

    public AiConversationTxHelper(CopilotConversationRepository conversationRepository,
                                  CopilotRequestRepository requestRepository,
                                  CopilotMessageRepository messageRepository,
                                  AuditTrailService auditTrailService,
                                  AuthenticatedActorResolver authenticatedActorResolver) {
        this.conversationRepository = conversationRepository;
        this.requestRepository = requestRepository;
        this.messageRepository = messageRepository;
        this.auditTrailService = auditTrailService;
        this.authenticatedActorResolver = authenticatedActorResolver;
    }

    /**
     * Transaction A: Atomically record user inquiry submission.
     * Inserts the PENDING request, exact 1 USER message, and exact 1 ASSISTANT placeholder.
     * Touches conversation updated_at and records an audit event.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CopilotRequest startRequestTx(Long conversationId, String clientRequestId,
                                         String requestHash, String queryText,
                                         int leaseDurationSeconds) {
        CopilotConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found with id: " + conversationId));

        LocalDateTime now = AppTime.nowDateTime();
        String leaseToken = UUID.randomUUID().toString();
        LocalDateTime leaseExpiresAt = now.plusSeconds(Math.max(leaseDurationSeconds, 30));

        CopilotRequest request = new CopilotRequest(
                conversation,
                clientRequestId,
                requestHash,
                CopilotRequestStatus.PENDING,
                leaseToken,
                leaseExpiresAt
        );
        request.setStartedAt(now);
        CopilotRequest savedRequest = requestRepository.saveAndFlush(request);

        // Insert exactly one USER message
        CopilotMessage userMessage = new CopilotMessage(savedRequest, CopilotMessageRole.USER, queryText);
        userMessage.setCreatedAt(now);
        messageRepository.saveAndFlush(userMessage);

        // Insert exactly one ASSISTANT placeholder message
        CopilotMessage assistantPlaceholder = new CopilotMessage(savedRequest, CopilotMessageRole.ASSISTANT, "");
        assistantPlaceholder.setCreatedAt(now);
        messageRepository.saveAndFlush(assistantPlaceholder);

        // Touch parent conversation recency
        int convRows = conversationRepository.touchUpdatedAt(conversationId, now);
        if (convRows != 1) {
            throw new IllegalStateException("Failed to touch conversation recency for id=" + conversationId);
        }

        // Audit submission safely (never log raw query prompt or lease token)
        String actor = resolveActorSafe("SYSTEM");
        auditTrailService.recordAudit(
                AUDIT_ENTITY_TYPE,
                conversationId,
                conversation.getTitle(),
                null,
                ACTION_QUERY_SUBMITTED,
                actor,
                now,
                "AI Copilot query submitted",
                null,
                Map.of("requestId", savedRequest.getId(), "clientRequestId", clientRequestId)
        );

        return savedRequest;
    }

    /**
     * Transaction B: Atomically complete request under exclusive pessimistic row lock.
     * Updates the assistant placeholder, transitions request to COMPLETED, clears lease,
     * touches conversation recency, and audits completion.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeRequestTx(Long requestId, String suppliedLeaseToken,
                                  String answerText, String modelName,
                                  String contextType, String contextIdentifier,
                                  String citationsJson) {
        CopilotRequest request = requestRepository.findByIdWithLock(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found for id: " + requestId));

        LocalDateTime dbNow = AppTime.nowDateTime();

        if (request.getStatus() != CopilotRequestStatus.PENDING) {
            throw new IllegalStateException("Cannot complete request: status is " + request.getStatus());
        }

        if (request.getLeaseToken() == null || !request.getLeaseToken().equals(suppliedLeaseToken)) {
            throw new IllegalStateException("Cannot complete request: lease token mismatch");
        }

        if (request.getLeaseExpiresAt() != null && dbNow.isAfter(request.getLeaseExpiresAt())) {
            throw new IllegalStateException("Cannot complete request: lease expired at " + request.getLeaseExpiresAt());
        }

        // Update the existing ASSISTANT placeholder entity
        CopilotMessage assistantMsg = messageRepository.findByRequestIdAndRole(requestId, CopilotMessageRole.ASSISTANT)
                .orElseThrow(() -> new ResourceNotFoundException("Assistant placeholder not found for requestId=" + requestId));
        assistantMsg.setContent(answerText);
        assistantMsg.setModel(modelName);
        assistantMsg.setContextType(contextType);
        assistantMsg.setContextIdentifier(contextIdentifier);
        assistantMsg.setSourcesJson(citationsJson);
        messageRepository.saveAndFlush(assistantMsg);

        // Transition request to COMPLETED
        request.setStatus(CopilotRequestStatus.COMPLETED);
        request.setCompletedAt(dbNow);
        request.setLeaseToken(null);
        request.setLeaseExpiresAt(null);
        requestRepository.saveAndFlush(request);

        // Touch parent conversation updated_at
        Long conversationId = request.getConversation().getId();
        int convRows = conversationRepository.touchUpdatedAt(conversationId, dbNow);
        if (convRows != 1) {
            throw new IllegalStateException("Failed to touch conversation recency for id=" + conversationId);
        }

        // Safe audit logging (never log answer text, internal model prompts or sources)
        String actor = resolveActorSafe("SYSTEM");
        auditTrailService.recordAudit(
                AUDIT_ENTITY_TYPE,
                conversationId,
                request.getConversation().getTitle(),
                null,
                ACTION_QUERY_COMPLETED,
                actor,
                dbNow,
                "AI Copilot query completed successfully",
                null,
                Map.of("requestId", requestId, "model", modelName != null ? modelName : "")
        );
    }

    /**
     * Transaction C: Atomically record request failure under exclusive row lock.
     * Updates the assistant placeholder with a user-friendly explanation, transitions status to FAILED,
     * clears lease, and touches conversation recency.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRequestTx(Long requestId, String suppliedLeaseToken,
                              String safeUserFailureMessage, String sanitizedErrorReason) {
        Optional<CopilotRequest> reqOpt = requestRepository.findByIdWithLock(requestId);
        if (reqOpt.isEmpty()) {
            log.warn("Cannot fail request: id={} not found", requestId);
            return;
        }

        CopilotRequest request = reqOpt.get();
        LocalDateTime dbNow = AppTime.nowDateTime();

        if (request.getStatus() != CopilotRequestStatus.PENDING) {
            log.warn("Cannot fail request id={}: already in terminal status {}", requestId, request.getStatus());
            return;
        }

        if (suppliedLeaseToken != null && request.getLeaseToken() != null
                && !request.getLeaseToken().equals(suppliedLeaseToken)) {
            log.warn("Cannot fail request id={}: lease token mismatch", requestId);
            return;
        }

        // Update assistant placeholder entity with safe user message
        CopilotMessage assistantMsg = messageRepository.findByRequestIdAndRole(requestId, CopilotMessageRole.ASSISTANT)
                .orElseThrow(() -> new ResourceNotFoundException("Assistant placeholder not found for requestId=" + requestId));
        assistantMsg.setContent(safeUserFailureMessage != null ? safeUserFailureMessage : "AI query processing was unsuccessful.");
        assistantMsg.setModel(null);
        assistantMsg.setContextType(null);
        assistantMsg.setContextIdentifier(null);
        assistantMsg.setSourcesJson(null);
        messageRepository.saveAndFlush(assistantMsg);

        request.setStatus(CopilotRequestStatus.FAILED);
        request.setCompletedAt(dbNow);
        request.setLeaseToken(null);
        request.setLeaseExpiresAt(null);
        if (sanitizedErrorReason != null) {
            request.setErrorMessage(sanitizedErrorReason.length() > 500
                    ? sanitizedErrorReason.substring(0, 500)
                    : sanitizedErrorReason);
        }
        requestRepository.saveAndFlush(request);

        Long conversationId = request.getConversation().getId();
        int convRows = conversationRepository.touchUpdatedAt(conversationId, dbNow);
        if (convRows != 1) {
            throw new IllegalStateException("Failed to touch conversation recency for id=" + conversationId);
        }

        String actor = resolveActorSafe("SYSTEM");
        auditTrailService.recordAudit(
                AUDIT_ENTITY_TYPE,
                conversationId,
                request.getConversation().getTitle(),
                null,
                ACTION_QUERY_FAILED,
                actor,
                dbNow,
                "AI Copilot query failed",
                null,
                Map.of("requestId", requestId)
        );
    }

    /**
     * Transaction D: Reclaim an expired PENDING request during recovery.
     * Locks the request, verifies expiration, transitions to FAILED, populates assistant placeholder,
     * touches conversation recency, and logs recovery audit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverExpiredRequestTx(Long requestId) {
        Optional<CopilotRequest> reqOpt = requestRepository.findByIdWithLock(requestId);
        if (reqOpt.isEmpty()) {
            return false;
        }

        CopilotRequest request = reqOpt.get();
        LocalDateTime dbNow = AppTime.nowDateTime();

        if (request.getStatus() != CopilotRequestStatus.PENDING) {
            return false;
        }

        if (request.getLeaseExpiresAt() == null || !dbNow.isAfter(request.getLeaseExpiresAt())) {
            return false; // Still active; lease has not expired
        }

        String timeoutNotice = "The previous query timed out or was interrupted before completion. Please retry your question.";
        CopilotMessage assistantMsg = messageRepository.findByRequestIdAndRole(requestId, CopilotMessageRole.ASSISTANT)
                .orElseThrow(() -> new ResourceNotFoundException("Assistant placeholder not found for requestId=" + requestId));
        assistantMsg.setContent(timeoutNotice);
        assistantMsg.setModel(null);
        assistantMsg.setContextType(null);
        assistantMsg.setContextIdentifier(null);
        assistantMsg.setSourcesJson(null);
        messageRepository.saveAndFlush(assistantMsg);

        request.setStatus(CopilotRequestStatus.FAILED);
        request.setCompletedAt(dbNow);
        request.setLeaseToken(null);
        request.setLeaseExpiresAt(null);
        request.setErrorMessage("Request processing timed out or was interrupted");
        requestRepository.saveAndFlush(request);

        Long conversationId = request.getConversation().getId();
        int convRows = conversationRepository.touchUpdatedAt(conversationId, dbNow);
        if (convRows != 1) {
            throw new IllegalStateException("Failed to update conversation timestamp during recovery for conversationId=" + conversationId);
        }

        auditTrailService.recordAudit(
                AUDIT_ENTITY_TYPE,
                conversationId,
                request.getConversation().getTitle(),
                null,
                ACTION_REQUEST_RECOVERED,
                "SYSTEM_RECOVERY",
                dbNow,
                "Recovered expired pending AI request",
                null,
                Map.of("requestId", requestId)
        );

        return true;
    }

    /**
     * Audit conversation creation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void auditConversationCreated(CopilotConversation conversation) {
        String actor = resolveActorSafe("SYSTEM");
        auditTrailService.recordAudit(
                AUDIT_ENTITY_TYPE,
                conversation.getId(),
                conversation.getTitle(),
                null,
                ACTION_CONVERSATION_CREATED,
                actor,
                conversation.getCreatedAt(),
                "AI Copilot conversation created",
                null,
                Map.of("title", conversation.getTitle())
        );
    }

    /**
     * Audit conversation rename.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void auditConversationRenamed(Long conversationId, String oldTitle, String newTitle) {
        String actor = resolveActorSafe("SYSTEM");
        auditTrailService.recordAudit(
                AUDIT_ENTITY_TYPE,
                conversationId,
                newTitle,
                null,
                ACTION_CONVERSATION_RENAMED,
                actor,
                AppTime.nowDateTime(),
                "AI Copilot conversation renamed",
                Map.of("title", oldTitle != null ? oldTitle : ""),
                Map.of("title", newTitle)
        );
    }

    /**
     * Audit conversation deletion.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void auditConversationDeleted(Long conversationId, String title, long messageCount) {
        String actor = resolveActorSafe("SYSTEM");
        auditTrailService.recordAudit(
                AUDIT_ENTITY_TYPE,
                conversationId,
                title,
                null,
                ACTION_CONVERSATION_DELETED,
                actor,
                AppTime.nowDateTime(),
                "AI Copilot conversation deleted",
                Map.of("title", title != null ? title : "", "messageCount", messageCount),
                null
        );
    }

    private String resolveActorSafe(String fallback) {
        try {
            return authenticatedActorResolver.requireCurrentActor();
        } catch (Exception ex) {
            return fallback;
        }
    }
}
