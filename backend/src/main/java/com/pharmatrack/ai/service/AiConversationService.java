package com.pharmatrack.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.AiQueryRequestDto;
import com.pharmatrack.dto.AiQueryResponseDto;
import com.pharmatrack.dto.AiSourceReferenceDto;
import com.pharmatrack.dto.CopilotConversationCreateRequestDto;
import com.pharmatrack.dto.CopilotConversationDetailDto;
import com.pharmatrack.dto.CopilotConversationRenameRequestDto;
import com.pharmatrack.dto.CopilotConversationSummaryDto;
import com.pharmatrack.dto.CopilotMessageDto;
import com.pharmatrack.dto.CopilotMessageResponseDto;
import com.pharmatrack.dto.CopilotSubmissionRequestDto;
import com.pharmatrack.entity.CopilotConversation;
import com.pharmatrack.entity.CopilotMessage;
import com.pharmatrack.entity.CopilotMessageRole;
import com.pharmatrack.entity.CopilotRequest;
import com.pharmatrack.entity.CopilotRequestStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.exception.DuplicateResourceException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.exception.SensitiveDataSanitizer;
import com.pharmatrack.repository.CopilotConversationRepository;
import com.pharmatrack.repository.CopilotMessageRepository;
import com.pharmatrack.repository.CopilotRequestRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.security.CustomUserDetails;
import com.pharmatrack.util.AppTime;
import com.pharmatrack.util.RequestFingerprintUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Service managing AI Copilot conversation lifecycles.
 *
 * <p>Enforces strict server-side identity resolution and ownership checks for all operations.
 * Nonexistent or unauthorized conversation IDs always throw {@link ResourceNotFoundException}
 * to prevent leaking the existence of other users' conversation threads.</p>
 */
@Service
public class AiConversationService {

    private static final Logger log = LoggerFactory.getLogger(AiConversationService.class);

    /**
     * Default lease duration in seconds for in-flight Copilot request execution.
     * Provides 4x headroom over the 30-second provider read timeout, context lookup, and DB commits.
     */
    public static final int DEFAULT_LEASE_DURATION_SECONDS = 120;

    /**
     * Default user-facing placeholder message returned for in-flight ASSISTANT turns.
     */
    public static final String IN_PROGRESS_PLACEHOLDER = "Inquiry is currently in progress. Please check back shortly.";

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final CopilotConversationRepository conversationRepository;
    private final CopilotRequestRepository requestRepository;
    private final CopilotMessageRepository messageRepository;
    private final UserAccountRepository userAccountRepository;
    private final AiConversationTxHelper txHelper;
    private final AiCopilotService aiCopilotService;
    private final AuthenticatedActorResolver authenticatedActorResolver;
    private final ObjectMapper objectMapper;

    public AiConversationService(CopilotConversationRepository conversationRepository,
                                 CopilotRequestRepository requestRepository,
                                 CopilotMessageRepository messageRepository,
                                 UserAccountRepository userAccountRepository,
                                 AiConversationTxHelper txHelper,
                                 AiCopilotService aiCopilotService,
                                 AuthenticatedActorResolver authenticatedActorResolver,
                                 ObjectMapper objectMapper) {
        this.conversationRepository = conversationRepository;
        this.requestRepository = requestRepository;
        this.messageRepository = messageRepository;
        this.userAccountRepository = userAccountRepository;
        this.txHelper = txHelper;
        this.aiCopilotService = aiCopilotService;
        this.authenticatedActorResolver = authenticatedActorResolver;
        this.objectMapper = objectMapper != null ? objectMapper : AppTime.createObjectMapper();
    }

    /**
     * Create a new persistent AI Copilot conversation thread for the authenticated user.
     */
    @Transactional
    public CopilotConversationSummaryDto createConversation(CopilotConversationCreateRequestDto requestDto) {
        if ("BATCH".equalsIgnoreCase(requestDto.getContextType())) {
            enforceProductionSupervisorRole("Batch analysis is restricted to Production Supervisors.");
        }

        UserAccount currentUser = requireCurrentUser();

        CopilotConversation conversation = new CopilotConversation(currentUser, requestDto.getTitle().trim());
        if (requestDto.getActiveModule() != null && !requestDto.getActiveModule().isBlank()) {
            conversation.setActiveModule(requestDto.getActiveModule().trim());
        }
        if (requestDto.getContextType() != null && !requestDto.getContextType().isBlank()) {
            conversation.setContextType(requestDto.getContextType().trim());
        }
        if (requestDto.getContextId() != null && !requestDto.getContextId().isBlank()) {
            conversation.setContextId(requestDto.getContextId().trim());
        }

        CopilotConversation savedConversation = conversationRepository.saveAndFlush(conversation);
        txHelper.auditConversationCreated(savedConversation);

        return toSummaryDto(savedConversation, 0L);
    }

    /**
     * List conversations belonging exclusively to the authenticated user, ordered by most recently updated.
     */
    @Transactional(readOnly = true)
    public List<CopilotConversationSummaryDto> listConversations(Integer page, Integer size) {
        Long currentUserId = getCurrentUserId();
        boolean isSupervisor = isProductionSupervisor();

        if (page != null || size != null) {
            int pageNum = page != null ? Math.max(page, 0) : 0;
            int pageSize = size != null ? Math.min(Math.max(size, 1), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
            Pageable pageable = PageRequest.of(pageNum, pageSize);
            Page<CopilotConversation> convPage = conversationRepository.findByUserIdOrderByUpdatedAtDescIdDesc(currentUserId, pageable);
            return convPage.stream()
                    .filter(conv -> isSupervisor || conv.getContextType() == null || !"BATCH".equalsIgnoreCase(conv.getContextType()))
                    .map(conv -> toSummaryDto(conv, messageRepository.countByConversationId(conv.getId())))
                    .toList();
        }

        List<CopilotConversation> conversations = conversationRepository.findByUserIdOrderByUpdatedAtDescIdDesc(currentUserId);
        return conversations.stream()
                .filter(conv -> isSupervisor || conv.getContextType() == null || !"BATCH".equalsIgnoreCase(conv.getContextType()))
                .map(conv -> toSummaryDto(conv, messageRepository.countByConversationId(conv.getId())))
                .toList();
    }

    /**
     * Retrieve full conversation details and chronological message transcript for an owned thread.
     */
    @Transactional(readOnly = true)
    public CopilotConversationDetailDto getConversationDetail(Long conversationId) {
        Long currentUserId = getCurrentUserId();

        CopilotConversation conversation = conversationRepository.findByIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found with id: " + conversationId));

        if ("BATCH".equalsIgnoreCase(conversation.getContextType())) {
            enforceProductionSupervisorRole("Batch analysis is restricted to Production Supervisors.");
        }

        List<CopilotMessage> messages = messageRepository.findMessagesByConversationId(conversationId);
        List<CopilotMessageDto> messageDtos = messages.stream()
                .map(this::toMessageDto)
                .toList();

        return new CopilotConversationDetailDto(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getActiveModule(),
                conversation.getContextType(),
                conversation.getContextId(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                messageDtos
        );
    }

    /**
     * Rename an owned conversation thread.
     */
    @Transactional
    public CopilotConversationSummaryDto renameConversation(Long conversationId, CopilotConversationRenameRequestDto requestDto) {
        Long currentUserId = getCurrentUserId();

        CopilotConversation conversation = conversationRepository.findByIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found with id: " + conversationId));

        if ("BATCH".equalsIgnoreCase(conversation.getContextType())) {
            enforceProductionSupervisorRole("Batch analysis is restricted to Production Supervisors.");
        }

        String oldTitle = conversation.getTitle();
        String newTitle = requestDto.getTitle().trim();

        conversation.setTitle(newTitle);
        conversation.setUpdatedAt(AppTime.nowDateTime());
        CopilotConversation updatedConversation = conversationRepository.saveAndFlush(conversation);

        txHelper.auditConversationRenamed(conversationId, oldTitle, newTitle);

        long messageCount = messageRepository.countByConversationId(conversationId);
        return toSummaryDto(updatedConversation, messageCount);
    }

    /**
     * Delete an owned conversation thread and its associated requests/messages.
     */
    @Transactional
    public void deleteConversation(Long conversationId) {
        Long currentUserId = getCurrentUserId();

        CopilotConversation conversation = conversationRepository.findByIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found with id: " + conversationId));

        if ("BATCH".equalsIgnoreCase(conversation.getContextType())) {
            enforceProductionSupervisorRole("Batch analysis is restricted to Production Supervisors.");
        }

        long messageCount = messageRepository.countByConversationId(conversationId);
        txHelper.auditConversationDeleted(conversationId, conversation.getTitle(), messageCount);

        conversationRepository.delete(conversation);
    }

    /**
     * Submit an inquiry to an owned AI Copilot conversation thread.
     *
     * <p>Idempotent orchestration:
     * <ul>
     *   <li>Verifies conversation ownership by authenticated user.</li>
     *   <li>Computes Unicode-aware deterministic request fingerprint.</li>
     *   <li>Checks for duplicate submissions; identical completed returns stored answer, active returns pending status, different fingerprint returns 409 Conflict.</li>
     *   <li>Atomically inserts request and message placeholders in an isolated short-lived transaction.</li>
     *   <li>Executes {@link AiCopilotService} OUTSIDE of any database transaction.</li>
     *   <li>Atomically completes or safely fails request under pessimistic lock in a separate transaction.</li>
     * </ul>
     */
    public CopilotMessageResponseDto submitMessage(Long conversationId, CopilotSubmissionRequestDto requestDto) {
        if (conversationId == null) {
            throw new IllegalArgumentException("Conversation ID must not be null");
        }
        if (requestDto == null) {
            throw new IllegalArgumentException("Submission request must not be null");
        }
        String clientRequestId = requestDto.getClientRequestId() != null ? requestDto.getClientRequestId().trim() : null;
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new IllegalArgumentException("Client request ID is mandatory");
        }
        String query = requestDto.getQuery() != null ? requestDto.getQuery().trim() : null;
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query is mandatory");
        }

        // 1. Verify authenticated user identity and ownership
        Long currentUserId = getCurrentUserId();
        CopilotConversation conversation = conversationRepository.findByIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found with id: " + conversationId));

        // 1b. Enforce role authorization for BATCH context
        String effectiveContextType = requestDto.getContextType() != null && !requestDto.getContextType().isBlank()
                ? requestDto.getContextType().trim()
                : conversation.getContextType();

        if ("BATCH".equalsIgnoreCase(effectiveContextType) || "BATCH".equalsIgnoreCase(conversation.getContextType())) {
            enforceProductionSupervisorRole("Batch analysis is restricted to Production Supervisors.");
        }

        // 2. Compute deterministic request fingerprint
        String requestHash = RequestFingerprintUtil.computeHash(
                requestDto.getQuery(),
                requestDto.getActiveModule(),
                requestDto.getContextType(),
                requestDto.getContextId()
        );

        // 3. Pre-check idempotency for existing request in this conversation
        Optional<CopilotRequest> existingOpt = requestRepository.findByConversationIdAndClientRequestId(conversationId, clientRequestId);
        if (existingOpt.isPresent()) {
            return handleExistingRequest(conversationId, existingOpt.get(), requestHash);
        }

        // 4. Atomically insert PENDING request, USER message, and ASSISTANT placeholder in isolated transaction
        CopilotRequest pendingRequest;
        try {
            pendingRequest = txHelper.startRequestTx(
                    conversationId,
                    clientRequestId,
                    requestHash,
                    query,
                    DEFAULT_LEASE_DURATION_SECONDS
            );
        } catch (DataIntegrityViolationException ex) {
            log.info("Concurrent submission collision for conversationId={} clientRequestId={}", conversationId, clientRequestId);
            CopilotRequest winner = requestRepository.findByConversationIdAndClientRequestId(conversationId, clientRequestId)
                    .orElseThrow(() -> ex);
            return handleExistingRequest(conversationId, winner, requestHash);
        }

        // 5. Execute AI Copilot service OUTSIDE of any database transaction
        String effectiveContextId = requestDto.getContextId() != null && !requestDto.getContextId().isBlank()
                ? requestDto.getContextId().trim()
                : conversation.getContextId();
        String effectiveModule = requestDto.getActiveModule() != null && !requestDto.getActiveModule().isBlank()
                ? requestDto.getActiveModule().trim()
                : conversation.getActiveModule();

        AiQueryRequestDto aiRequest = new AiQueryRequestDto(
                requestDto.getQuery(),
                effectiveModule,
                effectiveContextType,
                effectiveContextId
        );

        AiQueryResponseDto aiResponse;
        try {
            aiResponse = aiCopilotService.query(aiRequest);
        } catch (Exception ex) {
            log.warn("AI Copilot query execution failed for requestId={} conversationId={}: {}",
                    pendingRequest.getId(), conversationId, ex.getMessage());
            return handleExecutionFailure(conversationId, pendingRequest, ex);
        }

        // 6. Atomically complete the request under pessimistic lock in isolated transaction
        String sourcesJson = serializeSources(aiResponse.getSources());
        try {
            txHelper.completeRequestTx(
                    pendingRequest.getId(),
                    pendingRequest.getLeaseToken(),
                    aiResponse.getAnswer(),
                    aiResponse.getModel(),
                    aiResponse.getContextType(),
                    aiResponse.getContextIdentifier(),
                    sourcesJson
            );
        } catch (IllegalStateException ex) {
            log.warn("Failed to complete request id={} due to state/lease check: {}",
                    pendingRequest.getId(), ex.getMessage());
            return buildResponseFromPersistedMessages(conversationId, pendingRequest.getId(), clientRequestId);
        }

        // 7. Return persisted message response DTO
        return buildResponseFromPersistedMessages(conversationId, pendingRequest.getId(), clientRequestId);
    }

    private CopilotMessageResponseDto handleExistingRequest(Long conversationId, CopilotRequest existing, String currentHash) {
        // Check fingerprint match
        if (!existing.getRequestHash().equals(currentHash)) {
            throw new DuplicateResourceException(
                    "Client request ID '" + existing.getClientRequestId() + "' has already been used with different request parameters"
            );
        }

        // Handle completed request: return existing persisted response without invoking LLM
        if (existing.getStatus() == CopilotRequestStatus.COMPLETED) {
            log.debug("Returning existing completed result for conversationId={} clientRequestId={}",
                    conversationId, existing.getClientRequestId());
            return buildResponseFromPersistedMessages(conversationId, existing.getId(), existing.getClientRequestId());
        }

        // Handle failed request: retries must use a new clientRequestId
        if (existing.getStatus() == CopilotRequestStatus.FAILED) {
            throw new DuplicateResourceException(
                    "Client request ID '" + existing.getClientRequestId() + "' previously failed and cannot be re-executed. Please retry with a new clientRequestId."
            );
        }

        // Handle active PENDING request: check lease validity
        LocalDateTime now = AppTime.nowDateTime();
        if (existing.getLeaseExpiresAt() != null && now.isAfter(existing.getLeaseExpiresAt())) {
            // Lease expired; recover it safely
            txHelper.recoverExpiredRequestTx(existing.getId());
            throw new DuplicateResourceException(
                    "Client request ID '" + existing.getClientRequestId() + "' timed out and cannot be re-executed. Please retry with a new clientRequestId."
            );
        }

        // Still active under valid lease: return safe response without invoking LLM
        log.debug("Active duplicate request detected for conversationId={} clientRequestId={}; returning active placeholder",
                conversationId, existing.getClientRequestId());
        return buildResponseFromPersistedMessages(conversationId, existing.getId(), existing.getClientRequestId());
    }

    private CopilotMessageResponseDto handleExecutionFailure(Long conversationId, CopilotRequest pendingRequest, Exception ex) {
        String safeUserMessage = "AI service is temporarily unavailable. Please try your question again.";
        if (ex instanceof com.pharmatrack.ai.client.LlmClientException llmEx) {
            if (llmEx.getCategory() == com.pharmatrack.ai.client.LlmClientException.ErrorCategory.TIMEOUT) {
                safeUserMessage = "The AI service timed out while processing your request. Please try again.";
            } else if (llmEx.getCategory() == com.pharmatrack.ai.client.LlmClientException.ErrorCategory.CONFIGURATION_ERROR) {
                safeUserMessage = "PharmaTrack AI Copilot is currently disabled or not configured.";
            }
        }
        String sanitizedError = SensitiveDataSanitizer.sanitizeForLog(ex.getMessage());
        try {
            txHelper.failRequestTx(
                    pendingRequest.getId(),
                    pendingRequest.getLeaseToken(),
                    safeUserMessage,
                    sanitizedError
            );
        } catch (Exception failEx) {
            log.error("Failed to mark request id={} as failed: {}", pendingRequest.getId(), failEx.getMessage());
        }
        return buildResponseFromPersistedMessages(conversationId, pendingRequest.getId(), pendingRequest.getClientRequestId());
    }

    private CopilotMessageResponseDto buildResponseFromPersistedMessages(Long conversationId, Long requestId, String clientRequestId) {
        CopilotRequest request = requestRepository.findById(requestId).orElse(null);
        CopilotRequestStatus status = request != null ? request.getStatus() : null;

        List<CopilotMessage> messages = messageRepository.findByRequestIdOrderByCreatedAtAsc(requestId);
        CopilotMessageDto userDto = null;
        CopilotMessageDto assistantDto = null;

        for (CopilotMessage msg : messages) {
            if (msg.getRole() == CopilotMessageRole.USER) {
                userDto = toMessageDto(msg, requestId, status);
            } else if (msg.getRole() == CopilotMessageRole.ASSISTANT) {
                assistantDto = toMessageDto(msg, requestId, status);
            }
        }

        return new CopilotMessageResponseDto(conversationId, requestId, clientRequestId, userDto, assistantDto);
    }

    private String serializeSources(List<AiSourceReferenceDto> sources) {
        if (sources == null || sources.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception ex) {
            log.warn("Failed to serialize source references to JSON: {}", ex.getMessage());
            return "[]";
        }
    }

    public CopilotConversationSummaryDto toSummaryDto(CopilotConversation conv, long messageCount) {
        return new CopilotConversationSummaryDto(
                conv.getId(),
                conv.getTitle(),
                conv.getActiveModule(),
                conv.getContextType(),
                conv.getContextId(),
                conv.getCreatedAt(),
                conv.getUpdatedAt(),
                messageCount
        );
    }

    private CopilotMessageDto toMessageDto(CopilotMessage msg) {
        Long reqId = null;
        CopilotRequestStatus status = null;
        try {
            if (msg.getRequest() != null) {
                reqId = msg.getRequest().getId();
                status = msg.getRequest().getStatus();
            }
        } catch (Exception ex) {
            // Lazy proxy fallback
        }
        return toMessageDto(msg, reqId, status);
    }

    private CopilotMessageDto toMessageDto(CopilotMessage msg, Long requestId, CopilotRequestStatus status) {
        List<AiSourceReferenceDto> sources = parseSourcesJson(msg.getSourcesJson());
        String content = msg.getContent();
        if (msg.getRole() == CopilotMessageRole.ASSISTANT
                && status == CopilotRequestStatus.PENDING
                && (content == null || content.isBlank())) {
            content = IN_PROGRESS_PLACEHOLDER;
        }
        return new CopilotMessageDto(
                msg.getId(),
                requestId,
                msg.getRole(),
                content,
                msg.getModel(),
                msg.getContextType(),
                msg.getContextIdentifier(),
                sources,
                status,
                msg.getCreatedAt()
        );
    }

    private List<AiSourceReferenceDto> parseSourcesJson(String json) {
        if (json == null || json.trim().isEmpty() || "[]".equals(json.trim())) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AiSourceReferenceDto>>() {});
        } catch (Exception ex) {
            log.warn("Failed to deserialize message sources JSON: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    private UserAccount requireCurrentUser() {
        String username = authenticatedActorResolver.requireCurrentActor();
        return userAccountRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails userDetails) {
            return userDetails.getUserId();
        }
        return requireCurrentUser().getId();
    }

    public boolean isProductionSupervisor() {
        if (authenticatedActorResolver != null && !authenticatedActorResolver.hasAnyRole("PRODUCTION_SUPERVISOR")) {
            return false;
        }
        try {
            UserAccount user = requireCurrentUser();
            return user.getRole() == Role.PRODUCTION_SUPERVISOR;
        } catch (Exception ex) {
            return false;
        }
    }

    private void enforceProductionSupervisorRole(String message) {
        if (!isProductionSupervisor()) {
            throw new AccessDeniedException(message);
        }
    }
}
