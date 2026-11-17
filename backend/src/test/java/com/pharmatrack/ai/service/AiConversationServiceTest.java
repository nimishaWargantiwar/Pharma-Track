package com.pharmatrack.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.client.LlmClientException;
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
import com.pharmatrack.repository.CopilotConversationRepository;
import com.pharmatrack.repository.CopilotMessageRepository;
import com.pharmatrack.repository.CopilotRequestRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.security.CustomUserDetails;
import com.pharmatrack.util.AppTime;
import com.pharmatrack.util.RequestFingerprintUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AiConversationService} verifying server-side ownership enforcement,
 * conversation lifecycle state transitions, audit logging choreography, and isolation.
 */
@ExtendWith(MockitoExtension.class)
class AiConversationServiceTest {

    @Mock
    private CopilotConversationRepository conversationRepository;

    @Mock
    private CopilotMessageRepository messageRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private AiConversationTxHelper txHelper;

    @Mock
    private CopilotRequestRepository requestRepository;

    @Mock
    private AiCopilotService aiCopilotService;

    @Mock
    private AuthenticatedActorResolver authenticatedActorResolver;

    @Spy
    private ObjectMapper objectMapper = AppTime.createObjectMapper();

    @InjectMocks
    private AiConversationService conversationService;

    private UserAccount testUser;
    private UserAccount otherUser;
    private CopilotConversation ownedConversation;

    @BeforeEach
    void setUp() {
        testUser = new UserAccount("operator_john", "hash", "John Operator", Role.OPERATOR, true);
        testUser.setId(10L);

        otherUser = new UserAccount("supervisor_jane", "hash", "Jane Supervisor", Role.PRODUCTION_SUPERVISOR, true);
        otherUser.setId(20L);

        ownedConversation = new CopilotConversation(testUser, "Batch Investigation Thread");
        ownedConversation.setId(100L);

        CustomUserDetails userDetails = CustomUserDetails.fromEntity(testUser);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("createConversation: should save thread for authenticated user and trigger audit")
    void shouldCreateConversationSuccessfully() {
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("operator_john");
        when(userAccountRepository.findByUsernameIgnoreCase("operator_john")).thenReturn(Optional.of(testUser));
        when(conversationRepository.saveAndFlush(any(CopilotConversation.class))).thenAnswer(inv -> {
            CopilotConversation c = inv.getArgument(0);
            c.setId(101L);
            return c;
        });

        CopilotConversationCreateRequestDto requestDto =
                new CopilotConversationCreateRequestDto("MO-2026-0001 Analysis", "orders", "MANUFACTURING_ORDER", "MO-2026-0001");

        CopilotConversationSummaryDto result = conversationService.createConversation(requestDto);

        assertNotNull(result);
        assertEquals(101L, result.getId());
        assertEquals("MO-2026-0001 Analysis", result.getTitle());
        assertEquals("orders", result.getActiveModule());
        assertEquals(0L, result.getMessageCount());

        verify(conversationRepository).saveAndFlush(any(CopilotConversation.class));
        verify(txHelper).auditConversationCreated(any(CopilotConversation.class));
    }

    @Test
    @DisplayName("listConversations: should query conversations scoped strictly to current user ID with updatedAt desc, id desc")
    void shouldListUserConversationsOnly() {
        when(conversationRepository.findByUserIdOrderByUpdatedAtDescIdDesc(10L))
                .thenReturn(List.of(ownedConversation));
        when(messageRepository.countByConversationId(100L)).thenReturn(2L);

        List<CopilotConversationSummaryDto> results = conversationService.listConversations(null, null);

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals(100L, results.get(0).getId());
        assertEquals("Batch Investigation Thread", results.get(0).getTitle());
        assertEquals(2L, results.get(0).getMessageCount());

        verify(conversationRepository).findByUserIdOrderByUpdatedAtDescIdDesc(10L);
        verify(conversationRepository, never()).findByUserIdOrderByUpdatedAtDescIdDesc(eq(20L));
    }

    @Test
    @DisplayName("listConversations: should support pagination with bounds and pass pageable to repository")
    void shouldListUserConversationsWithPaginationAndBounds() {
        CopilotConversation conv2 = new CopilotConversation(testUser, "Second Thread");
        conv2.setId(102L);

        Page<CopilotConversation> pagedResult = new PageImpl<>(List.of(ownedConversation, conv2));
        when(conversationRepository.findByUserIdOrderByUpdatedAtDescIdDesc(eq(10L), any(Pageable.class)))
                .thenReturn(pagedResult);
        when(messageRepository.countByConversationId(100L)).thenReturn(2L);
        when(messageRepository.countByConversationId(102L)).thenReturn(0L);

        List<CopilotConversationSummaryDto> results = conversationService.listConversations(0, 10);

        assertNotNull(results);
        assertEquals(2, results.size());
        assertEquals(100L, results.get(0).getId());
        assertEquals(102L, results.get(1).getId());

        verify(conversationRepository).findByUserIdOrderByUpdatedAtDescIdDesc(eq(10L), eq(PageRequest.of(0, 10)));
    }

    @Test
    @DisplayName("listConversations: should use default page size 20 when only page is supplied")
    void shouldUseDefaultPageSizeWhenOnlyPageSupplied() {
        Page<CopilotConversation> pagedResult = new PageImpl<>(List.of(ownedConversation));
        when(conversationRepository.findByUserIdOrderByUpdatedAtDescIdDesc(eq(10L), any(Pageable.class)))
                .thenReturn(pagedResult);
        when(messageRepository.countByConversationId(100L)).thenReturn(1L);

        List<CopilotConversationSummaryDto> results = conversationService.listConversations(2, null);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(conversationRepository).findByUserIdOrderByUpdatedAtDescIdDesc(eq(10L), eq(PageRequest.of(2, 20)));
    }

    @Test
    @DisplayName("listConversations: should use page 0 when only size is supplied")
    void shouldUsePageZeroWhenOnlySizeSupplied() {
        Page<CopilotConversation> pagedResult = new PageImpl<>(List.of(ownedConversation));
        when(conversationRepository.findByUserIdOrderByUpdatedAtDescIdDesc(eq(10L), any(Pageable.class)))
                .thenReturn(pagedResult);
        when(messageRepository.countByConversationId(100L)).thenReturn(1L);

        List<CopilotConversationSummaryDto> results = conversationService.listConversations(null, 15);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(conversationRepository).findByUserIdOrderByUpdatedAtDescIdDesc(eq(10L), eq(PageRequest.of(0, 15)));
    }

    @Test
    @DisplayName("listConversations: should clamp negative page and zero/negative size values to minimum bounds")
    void shouldClampNegativePageAndSizeBoundsProperly() {
        Page<CopilotConversation> pagedResult = new PageImpl<>(List.of(ownedConversation));
        when(conversationRepository.findByUserIdOrderByUpdatedAtDescIdDesc(eq(10L), any(Pageable.class)))
                .thenReturn(pagedResult);
        when(messageRepository.countByConversationId(100L)).thenReturn(1L);

        List<CopilotConversationSummaryDto> results = conversationService.listConversations(-3, -10);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(conversationRepository).findByUserIdOrderByUpdatedAtDescIdDesc(eq(10L), eq(PageRequest.of(0, 1)));
    }

    @Test
    @DisplayName("listConversations: should clamp oversized page size to MAX_PAGE_SIZE (100)")
    void shouldClampOversizedPageSizeToMaxLimit() {
        Page<CopilotConversation> pagedResult = new PageImpl<>(List.of(ownedConversation));
        when(conversationRepository.findByUserIdOrderByUpdatedAtDescIdDesc(eq(10L), any(Pageable.class)))
                .thenReturn(pagedResult);
        when(messageRepository.countByConversationId(100L)).thenReturn(1L);

        List<CopilotConversationSummaryDto> results = conversationService.listConversations(1, 500);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(conversationRepository).findByUserIdOrderByUpdatedAtDescIdDesc(eq(10L), eq(PageRequest.of(1, 100)));
    }

    @Test
    @DisplayName("getConversationDetail: should return detail and chronological messages when owned")
    void shouldReturnDetailForOwnedConversation() {
        when(conversationRepository.findByIdAndUserId(100L, 10L))
                .thenReturn(Optional.of(ownedConversation));

        CopilotRequest req = new CopilotRequest(ownedConversation, "client-req-1", "hash-1",
                CopilotRequestStatus.COMPLETED, null, null);
        req.setId(500L);

        CopilotMessage userMsg = new CopilotMessage(req, CopilotMessageRole.USER, "What is the yield?");
        userMsg.setId(1001L);

        CopilotMessage assistantMsg = new CopilotMessage(req, CopilotMessageRole.ASSISTANT, "The yield is 98.5%.");
        assistantMsg.setId(1002L);
        assistantMsg.setModel("gpt-4o-mini");
        assistantMsg.setSourcesJson("[{\"recordType\":\"MANUFACTURING_ORDER\",\"recordIdentifier\":\"MO-2026-0001\",\"title\":\"MO-2026-0001\",\"module\":\"orders\"}]");

        when(messageRepository.findMessagesByConversationId(100L))
                .thenReturn(List.of(userMsg, assistantMsg));

        CopilotConversationDetailDto result = conversationService.getConversationDetail(100L);

        assertNotNull(result);
        assertEquals(100L, result.getId());
        assertEquals("Batch Investigation Thread", result.getTitle());
        assertEquals(2, result.getMessages().size());
        assertEquals("What is the yield?", result.getMessages().get(0).getContent());
        assertEquals("The yield is 98.5%.", result.getMessages().get(1).getContent());
        assertEquals(1, result.getMessages().get(1).getSources().size());
        assertEquals("MO-2026-0001", result.getMessages().get(1).getSources().get(0).getRecordIdentifier());
    }

    @Test
    @DisplayName("getConversationDetail: should throw ResourceNotFoundException when conversation belongs to another user")
    void shouldRejectAccessToOtherUserConversation() {
        when(conversationRepository.findByIdAndUserId(200L, 10L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> {
            conversationService.getConversationDetail(200L);
        });

        verify(messageRepository, never()).findMessagesByConversationId(anyLong());
    }

    @Test
    @DisplayName("P1: getConversationDetail should return in-progress placeholder for PENDING assistant with blank content")
    void shouldMapPendingAssistantWithBlankContentToPlaceholderInDetail() {
        when(conversationRepository.findByIdAndUserId(100L, 10L)).thenReturn(Optional.of(ownedConversation));

        CopilotRequest pendingReq = new CopilotRequest(ownedConversation, "client-req-pending", "hash-p",
                CopilotRequestStatus.PENDING, "lease-tok", LocalDateTime.now().plusSeconds(120));
        pendingReq.setId(601L);

        CopilotMessage userMsg = new CopilotMessage(pendingReq, CopilotMessageRole.USER, "Ongoing question?");
        userMsg.setId(1011L);
        CopilotMessage pendingAssistantMsg = new CopilotMessage(pendingReq, CopilotMessageRole.ASSISTANT, "");
        pendingAssistantMsg.setId(1012L);

        when(messageRepository.findMessagesByConversationId(100L)).thenReturn(List.of(userMsg, pendingAssistantMsg));

        CopilotConversationDetailDto result = conversationService.getConversationDetail(100L);

        assertNotNull(result);
        assertEquals(2, result.getMessages().size());
        assertEquals("Ongoing question?", result.getMessages().get(0).getContent());
        assertEquals(CopilotMessageRole.USER, result.getMessages().get(0).getRole());
        assertEquals(AiConversationService.IN_PROGRESS_PLACEHOLDER, result.getMessages().get(1).getContent());
        assertEquals(CopilotMessageRole.ASSISTANT, result.getMessages().get(1).getRole());
        assertEquals(CopilotRequestStatus.PENDING, result.getMessages().get(1).getStatus());
    }

    @Test
    @DisplayName("P1: getConversationDetail should retain actual content for PENDING assistant when content is non-blank")
    void shouldRetainNonBlankPendingAssistantContentInDetail() {
        when(conversationRepository.findByIdAndUserId(100L, 10L)).thenReturn(Optional.of(ownedConversation));

        CopilotRequest pendingReq = new CopilotRequest(ownedConversation, "client-req-stream", "hash-s",
                CopilotRequestStatus.PENDING, "lease-tok", LocalDateTime.now().plusSeconds(120));
        pendingReq.setId(602L);

        CopilotMessage assistantMsg = new CopilotMessage(pendingReq, CopilotMessageRole.ASSISTANT, "Partial in-flight response");
        assistantMsg.setId(1022L);

        when(messageRepository.findMessagesByConversationId(100L)).thenReturn(List.of(assistantMsg));

        CopilotConversationDetailDto result = conversationService.getConversationDetail(100L);

        assertNotNull(result);
        assertEquals(1, result.getMessages().size());
        assertEquals("Partial in-flight response", result.getMessages().get(0).getContent());
    }

    @Test
    @DisplayName("P1: getConversationDetail should retain actual content for COMPLETED assistant message")
    void shouldRetainActualContentForCompletedAssistantInDetail() {
        when(conversationRepository.findByIdAndUserId(100L, 10L)).thenReturn(Optional.of(ownedConversation));

        CopilotRequest completedReq = new CopilotRequest(ownedConversation, "client-req-done", "hash-d",
                CopilotRequestStatus.COMPLETED, null, null);
        completedReq.setId(603L);

        CopilotMessage assistantMsg = new CopilotMessage(completedReq, CopilotMessageRole.ASSISTANT, "Verified manufacturing advice.");
        assistantMsg.setId(1032L);

        when(messageRepository.findMessagesByConversationId(100L)).thenReturn(List.of(assistantMsg));

        CopilotConversationDetailDto result = conversationService.getConversationDetail(100L);

        assertNotNull(result);
        assertEquals(1, result.getMessages().size());
        assertEquals("Verified manufacturing advice.", result.getMessages().get(0).getContent());
        assertEquals(CopilotRequestStatus.COMPLETED, result.getMessages().get(0).getStatus());
    }

    @Test
    @DisplayName("P1: getConversationDetail should retain actual content for FAILED assistant message")
    void shouldRetainActualContentForFailedAssistantInDetail() {
        when(conversationRepository.findByIdAndUserId(100L, 10L)).thenReturn(Optional.of(ownedConversation));

        CopilotRequest failedReq = new CopilotRequest(ownedConversation, "client-req-err", "hash-e",
                CopilotRequestStatus.FAILED, null, null);
        failedReq.setId(604L);

        CopilotMessage assistantMsg = new CopilotMessage(failedReq, CopilotMessageRole.ASSISTANT, "AI service timed out while processing your request. Please try again.");
        assistantMsg.setId(1042L);

        when(messageRepository.findMessagesByConversationId(100L)).thenReturn(List.of(assistantMsg));

        CopilotConversationDetailDto result = conversationService.getConversationDetail(100L);

        assertNotNull(result);
        assertEquals(1, result.getMessages().size());
        assertEquals("AI service timed out while processing your request. Please try again.", result.getMessages().get(0).getContent());
        assertEquals(CopilotRequestStatus.FAILED, result.getMessages().get(0).getStatus());
    }

    @Test
    @DisplayName("P1: getConversationDetail should retain blank content for USER message without substituting placeholder")
    void shouldRetainBlankContentForUserMessage() {
        when(conversationRepository.findByIdAndUserId(100L, 10L)).thenReturn(Optional.of(ownedConversation));

        CopilotRequest req = new CopilotRequest(ownedConversation, "client-req-blank", "hash-b",
                CopilotRequestStatus.PENDING, "lease-tok", LocalDateTime.now().plusSeconds(120));
        req.setId(605L);

        CopilotMessage userMsg = new CopilotMessage(req, CopilotMessageRole.USER, "");
        userMsg.setId(1051L);

        when(messageRepository.findMessagesByConversationId(100L)).thenReturn(List.of(userMsg));

        CopilotConversationDetailDto result = conversationService.getConversationDetail(100L);

        assertNotNull(result);
        assertEquals(1, result.getMessages().size());
        assertEquals("", result.getMessages().get(0).getContent());
        assertEquals(CopilotMessageRole.USER, result.getMessages().get(0).getRole());
    }

    @Test
    @DisplayName("renameConversation: should rename owned thread, update timestamp, and audit")
    void shouldRenameOwnedConversation() {
        when(conversationRepository.findByIdAndUserId(100L, 10L))
                .thenReturn(Optional.of(ownedConversation));
        when(conversationRepository.saveAndFlush(any(CopilotConversation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.countByConversationId(100L)).thenReturn(4L);

        CopilotConversationRenameRequestDto renameDto = new CopilotConversationRenameRequestDto("Updated Investigation Title");

        CopilotConversationSummaryDto result = conversationService.renameConversation(100L, renameDto);

        assertNotNull(result);
        assertEquals("Updated Investigation Title", result.getTitle());
        assertEquals(4L, result.getMessageCount());

        verify(txHelper).auditConversationRenamed(eq(100L), eq("Batch Investigation Thread"), eq("Updated Investigation Title"));
        verify(conversationRepository).saveAndFlush(ownedConversation);
    }

    @Test
    @DisplayName("renameConversation: should throw ResourceNotFoundException when thread belongs to another user")
    void shouldRejectRenameOfOtherUserConversation() {
        when(conversationRepository.findByIdAndUserId(300L, 10L)).thenReturn(Optional.empty());

        CopilotConversationRenameRequestDto renameDto = new CopilotConversationRenameRequestDto("Malicious Rename");

        assertThrows(ResourceNotFoundException.class, () -> {
            conversationService.renameConversation(300L, renameDto);
        });

        verify(conversationRepository, never()).saveAndFlush(any());
        verify(txHelper, never()).auditConversationRenamed(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("deleteConversation: should audit and delete owned conversation")
    void shouldDeleteOwnedConversation() {
        when(conversationRepository.findByIdAndUserId(100L, 10L))
                .thenReturn(Optional.of(ownedConversation));
        when(messageRepository.countByConversationId(100L)).thenReturn(5L);

        conversationService.deleteConversation(100L);

        verify(txHelper).auditConversationDeleted(100L, "Batch Investigation Thread", 5L);
        verify(conversationRepository).delete(ownedConversation);
    }

    @Test
    @DisplayName("deleteConversation: should throw ResourceNotFoundException when thread belongs to another user")
    void shouldRejectDeleteOfOtherUserConversation() {
        when(conversationRepository.findByIdAndUserId(400L, 10L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> {
            conversationService.deleteConversation(400L);
        });

        verify(txHelper, never()).auditConversationDeleted(anyLong(), anyString(), anyLong());
        verify(conversationRepository, never()).delete(any());
    }

    // =========================================================================
    // POST /api/ai/conversations/{conversationId}/messages (Message Submission)
    // =========================================================================

    @Test
    @DisplayName("submitMessage: Successful message submission persists request, messages in order, and returns assistant response")
    void shouldSubmitMessageSuccessfullyWithAssistantResponseAndOrderedMessages() {
        when(conversationRepository.findByIdAndUserId(100L, 10L)).thenReturn(Optional.of(ownedConversation));
        when(requestRepository.findByConversationIdAndClientRequestId(100L, "client-req-001"))
                .thenReturn(Optional.empty());

        CopilotRequest pendingReq = new CopilotRequest(ownedConversation, "client-req-001", "hash-val",
                CopilotRequestStatus.PENDING, "lease-token-123", LocalDateTime.now().plusSeconds(60));
        pendingReq.setId(50L);

        when(txHelper.startRequestTx(eq(100L), eq("client-req-001"), anyString(), eq("What is the yield of MO-2026-0001?"), eq(120)))
                .thenReturn(pendingReq);

        AiSourceReferenceDto source = new AiSourceReferenceDto("MANUFACTURING_ORDER", "MO-2026-0001", "Order MO-2026-0001", "orders", "Grounded record");
        AiQueryResponseDto aiResponse = new AiQueryResponseDto(
                "The yield for MO-2026-0001 is 98.5%.",
                "MANUFACTURING_ORDER",
                "MO-2026-0001",
                List.of(source),
                "gpt-6-luna"
        );
        when(aiCopilotService.query(any(AiQueryRequestDto.class))).thenReturn(aiResponse);

        CopilotRequest completedReq = new CopilotRequest(ownedConversation, "client-req-001", "hash-val",
                CopilotRequestStatus.COMPLETED, null, null);
        completedReq.setId(50L);
        when(requestRepository.findById(50L)).thenReturn(Optional.of(completedReq));

        CopilotMessage userMsg = new CopilotMessage(completedReq, CopilotMessageRole.USER, "What is the yield of MO-2026-0001?");
        userMsg.setId(101L);
        CopilotMessage assistantMsg = new CopilotMessage(completedReq, CopilotMessageRole.ASSISTANT, "The yield for MO-2026-0001 is 98.5%.");
        assistantMsg.setId(102L);
        assistantMsg.setModel("gpt-6-luna");
        assistantMsg.setContextType("MANUFACTURING_ORDER");
        assistantMsg.setContextIdentifier("MO-2026-0001");
        assistantMsg.setSourcesJson("[{\"recordType\":\"MANUFACTURING_ORDER\",\"recordIdentifier\":\"MO-2026-0001\",\"title\":\"Order MO-2026-0001\",\"module\":\"orders\",\"summary\":\"Grounded record\"}]");

        when(messageRepository.findByRequestIdOrderByCreatedAtAsc(50L))
                .thenReturn(List.of(userMsg, assistantMsg));

        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto(
                "client-req-001", "What is the yield of MO-2026-0001?", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );

        CopilotMessageResponseDto response = conversationService.submitMessage(100L, submission);

        assertNotNull(response);
        assertEquals(100L, response.getConversationId());
        assertEquals(50L, response.getRequestId());
        assertEquals("client-req-001", response.getClientRequestId());
        assertEquals(CopilotRequestStatus.COMPLETED, response.getStatus());

        // Message order assertion
        assertNotNull(response.getUserMessage());
        assertEquals(101L, response.getUserMessage().getId());
        assertEquals(CopilotMessageRole.USER, response.getUserMessage().getRole());
        assertEquals("What is the yield of MO-2026-0001?", response.getUserMessage().getContent());

        assertNotNull(response.getAssistantMessage());
        assertEquals(102L, response.getAssistantMessage().getId());
        assertEquals(CopilotMessageRole.ASSISTANT, response.getAssistantMessage().getRole());
        assertEquals("The yield for MO-2026-0001 is 98.5%.", response.getAssistantMessage().getContent());
        assertEquals("gpt-6-luna", response.getAssistantMessage().getModel());
        assertEquals(1, response.getAssistantMessage().getSources().size());
        assertEquals("MO-2026-0001", response.getAssistantMessage().getSources().get(0).getRecordIdentifier());

        verify(txHelper).startRequestTx(eq(100L), eq("client-req-001"), anyString(), eq("What is the yield of MO-2026-0001?"), eq(120));
        verify(aiCopilotService).query(any(AiQueryRequestDto.class));
        verify(txHelper).completeRequestTx(eq(50L), eq("lease-token-123"), eq("The yield for MO-2026-0001 is 98.5%."),
                eq("gpt-6-luna"), eq("MANUFACTURING_ORDER"), eq("MO-2026-0001"), anyString());
    }

    @Test
    @DisplayName("submitMessage: Should reject message submission with 404 when conversation belongs to another user")
    void shouldRejectMessageSubmissionWhenConversationNotOwned() {
        when(conversationRepository.findByIdAndUserId(999L, 10L)).thenReturn(Optional.empty());

        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto("client-req-999", "Inquiry");

        assertThrows(ResourceNotFoundException.class, () -> {
            conversationService.submitMessage(999L, submission);
        });

        verify(txHelper, never()).startRequestTx(anyLong(), anyString(), anyString(), anyString(), anyInt());
        verify(aiCopilotService, never()).query(any());
    }

    @Test
    @DisplayName("submitMessage: Same clientRequestId and fingerprint returns existing completed result without second LLM call")
    void shouldReturnExistingCompletedResultWhenSameClientRequestIdAndFingerprint() {
        when(conversationRepository.findByIdAndUserId(100L, 10L)).thenReturn(Optional.of(ownedConversation));

        String fingerprint = RequestFingerprintUtil.computeHash("What is MO-2026-0001?", "orders", "MANUFACTURING_ORDER", "MO-2026-0001");
        CopilotRequest existingReq = new CopilotRequest(ownedConversation, "client-req-idem-1", fingerprint,
                CopilotRequestStatus.COMPLETED, null, null);
        existingReq.setId(77L);

        when(requestRepository.findByConversationIdAndClientRequestId(100L, "client-req-idem-1"))
                .thenReturn(Optional.of(existingReq));
        when(requestRepository.findById(77L)).thenReturn(Optional.of(existingReq));

        CopilotMessage userMsg = new CopilotMessage(existingReq, CopilotMessageRole.USER, "What is MO-2026-0001?");
        userMsg.setId(201L);
        CopilotMessage assistantMsg = new CopilotMessage(existingReq, CopilotMessageRole.ASSISTANT, "Stored result from first execution.");
        assistantMsg.setId(202L);

        when(messageRepository.findByRequestIdOrderByCreatedAtAsc(77L))
                .thenReturn(List.of(userMsg, assistantMsg));

        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto(
                "client-req-idem-1", "What is MO-2026-0001?", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );

        CopilotMessageResponseDto response = conversationService.submitMessage(100L, submission);

        assertNotNull(response);
        assertEquals(77L, response.getRequestId());
        assertEquals("Stored result from first execution.", response.getAssistantMessage().getContent());

        // Zero LLM calls and zero new transactions
        verify(aiCopilotService, never()).query(any());
        verify(txHelper, never()).startRequestTx(anyLong(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("submitMessage: Same clientRequestId with different fingerprint throws DuplicateResourceException (409)")
    void shouldRejectWith409WhenSameClientRequestIdWithDifferentFingerprint() {
        when(conversationRepository.findByIdAndUserId(100L, 10L)).thenReturn(Optional.of(ownedConversation));

        CopilotRequest existingReq = new CopilotRequest(ownedConversation, "client-req-conflict", "different-hash-1234567890",
                CopilotRequestStatus.COMPLETED, null, null);
        existingReq.setId(88L);

        when(requestRepository.findByConversationIdAndClientRequestId(100L, "client-req-conflict"))
                .thenReturn(Optional.of(existingReq));

        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto(
                "client-req-conflict", "New modified question with reused key", "orders", null, null
        );

        DuplicateResourceException ex = assertThrows(DuplicateResourceException.class, () -> {
            conversationService.submitMessage(100L, submission);
        });

        assertTrue(ex.getMessage().contains("already been used with different request parameters"));
        verify(aiCopilotService, never()).query(any());
    }

    @Test
    @DisplayName("submitMessage: Active duplicate request returns safe pending response without second LLM call")
    void shouldReturnSafePlaceholderWithoutLlmCallWhenDuplicateRequestIsActive() {
        when(conversationRepository.findByIdAndUserId(100L, 10L)).thenReturn(Optional.of(ownedConversation));

        String fingerprint = RequestFingerprintUtil.computeHash("Active query?", null, null, null);
        CopilotRequest activeReq = new CopilotRequest(ownedConversation, "client-req-active", fingerprint,
                CopilotRequestStatus.PENDING, "active-lease-tok", LocalDateTime.now().plusSeconds(45));
        activeReq.setId(99L);

        when(requestRepository.findByConversationIdAndClientRequestId(100L, "client-req-active"))
                .thenReturn(Optional.of(activeReq));
        when(requestRepository.findById(99L)).thenReturn(Optional.of(activeReq));

        CopilotMessage userMsg = new CopilotMessage(activeReq, CopilotMessageRole.USER, "Active query?");
        userMsg.setId(301L);
        CopilotMessage assistantPlaceholder = new CopilotMessage(activeReq, CopilotMessageRole.ASSISTANT, "");
        assistantPlaceholder.setId(302L);

        when(messageRepository.findByRequestIdOrderByCreatedAtAsc(99L))
                .thenReturn(List.of(userMsg, assistantPlaceholder));

        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto("client-req-active", "Active query?");

        CopilotMessageResponseDto response = conversationService.submitMessage(100L, submission);

        assertNotNull(response);
        assertEquals(CopilotRequestStatus.PENDING, response.getStatus());
        assertTrue(response.getAssistantMessage().getContent().contains("in progress"));

        verify(aiCopilotService, never()).query(any());
    }

    @Test
    @DisplayName("submitMessage: Retrying a failed request with the same clientRequestId is rejected with 409")
    void shouldRejectRetryOnFailedRequestRequiringNewClientRequestId() {
        when(conversationRepository.findByIdAndUserId(100L, 10L)).thenReturn(Optional.of(ownedConversation));

        String fingerprint = RequestFingerprintUtil.computeHash("Failed query?", null, null, null);
        CopilotRequest failedReq = new CopilotRequest(ownedConversation, "client-req-failed", fingerprint,
                CopilotRequestStatus.FAILED, null, null);
        failedReq.setId(111L);

        when(requestRepository.findByConversationIdAndClientRequestId(100L, "client-req-failed"))
                .thenReturn(Optional.of(failedReq));

        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto("client-req-failed", "Failed query?");

        DuplicateResourceException ex = assertThrows(DuplicateResourceException.class, () -> {
            conversationService.submitMessage(100L, submission);
        });

        assertTrue(ex.getMessage().contains("previously failed and cannot be re-executed"));
        verify(aiCopilotService, never()).query(any());
    }

    @Test
    @DisplayName("submitMessage: Concurrent duplicate insertion collision recovers safely without duplicate LLM call")
    void shouldHandleConcurrentDuplicateSubmissionCollisionSafelyWithoutDuplicateLlmCall() {
        when(conversationRepository.findByIdAndUserId(100L, 10L)).thenReturn(Optional.of(ownedConversation));

        String fingerprint = RequestFingerprintUtil.computeHash("Concurrent query?", null, null, null);

        CopilotRequest winnerReq = new CopilotRequest(ownedConversation, "client-req-race", fingerprint,
                CopilotRequestStatus.COMPLETED, null, null);
        winnerReq.setId(222L);

        // Pre-check finds empty (simulating simultaneous arrival), then find winner
        when(requestRepository.findByConversationIdAndClientRequestId(100L, "client-req-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerReq));

        // Loser thread throws DataIntegrityViolationException on startRequestTx
        when(txHelper.startRequestTx(eq(100L), eq("client-req-race"), eq(fingerprint), eq("Concurrent query?"), eq(120)))
                .thenThrow(new DataIntegrityViolationException("Duplicate key uk_copilot_req_client"));

        when(requestRepository.findById(222L)).thenReturn(Optional.of(winnerReq));

        CopilotMessage userMsg = new CopilotMessage(winnerReq, CopilotMessageRole.USER, "Concurrent query?");
        CopilotMessage assistantMsg = new CopilotMessage(winnerReq, CopilotMessageRole.ASSISTANT, "Winner LLM answer");
        when(messageRepository.findByRequestIdOrderByCreatedAtAsc(222L)).thenReturn(List.of(userMsg, assistantMsg));

        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto("client-req-race", "Concurrent query?");

        CopilotMessageResponseDto response = conversationService.submitMessage(100L, submission);

        assertNotNull(response);
        assertEquals("Winner LLM answer", response.getAssistantMessage().getContent());
        // Crucial guarantee: Loser thread NEVER invokes LLM
        verify(aiCopilotService, never()).query(any());
    }

    @Test
    @DisplayName("submitMessage: Provider timeout transitions request to FAILED with user-safe message and sanitized error")
    void shouldHandleLlmTimeoutAndPersistSafeFailureWithoutLeakingDiagnostics() {
        when(conversationRepository.findByIdAndUserId(100L, 10L)).thenReturn(Optional.of(ownedConversation));
        when(requestRepository.findByConversationIdAndClientRequestId(100L, "client-req-timeout"))
                .thenReturn(Optional.empty());

        CopilotRequest pendingReq = new CopilotRequest(ownedConversation, "client-req-timeout", "hash-val",
                CopilotRequestStatus.PENDING, "lease-tok-t", LocalDateTime.now().plusSeconds(120));
        pendingReq.setId(333L);
        when(txHelper.startRequestTx(eq(100L), eq("client-req-timeout"), anyString(), anyString(), eq(120)))
                .thenReturn(pendingReq);

        // Simulate provider timeout containing raw sensitive credentials in exception message
        when(aiCopilotService.query(any(AiQueryRequestDto.class)))
                .thenThrow(new LlmClientException(
                        LlmClientException.ErrorCategory.TIMEOUT,
                        "HTTP 504 Gateway Timeout contacting https://api.openai.com/v1 with Bearer sk-pharma-secret-key-12345"
                ));

        CopilotRequest failedReq = new CopilotRequest(ownedConversation, "client-req-timeout", "hash-val",
                CopilotRequestStatus.FAILED, null, null);
        failedReq.setId(333L);
        when(requestRepository.findById(333L)).thenReturn(Optional.of(failedReq));

        CopilotMessage userMsg = new CopilotMessage(failedReq, CopilotMessageRole.USER, "Query");
        CopilotMessage assistantFailedMsg = new CopilotMessage(failedReq, CopilotMessageRole.ASSISTANT, "The AI service timed out while processing your request. Please try again.");
        when(messageRepository.findByRequestIdOrderByCreatedAtAsc(333L)).thenReturn(List.of(userMsg, assistantFailedMsg));

        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto("client-req-timeout", "Query");

        CopilotMessageResponseDto response = conversationService.submitMessage(100L, submission);

        assertNotNull(response);
        assertEquals(CopilotRequestStatus.FAILED, response.getStatus());

        // Verify failRequestTx called with safe message and sanitized error (raw secret redacted)
        ArgumentCaptor<String> safeUserMsgCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sanitizedErrCaptor = ArgumentCaptor.forClass(String.class);
        verify(txHelper).failRequestTx(eq(333L), eq("lease-tok-t"), safeUserMsgCaptor.capture(), sanitizedErrCaptor.capture());

        assertEquals("The AI service timed out while processing your request. Please try again.", safeUserMsgCaptor.getValue());
        assertFalse(sanitizedErrCaptor.getValue().contains("sk-pharma-secret-key-12345"));
        assertTrue(sanitizedErrCaptor.getValue().contains("Bearer [REDACTED]"));
        verify(txHelper, never()).completeRequestTx(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("submitMessage: Stale lease expiration prevents stale completion and returns safe response")
    void shouldHandleLeaseExpiryDuringCompletionWithoutCommittingStaleAnswer() {
        when(conversationRepository.findByIdAndUserId(100L, 10L)).thenReturn(Optional.of(ownedConversation));
        when(requestRepository.findByConversationIdAndClientRequestId(100L, "client-req-stale"))
                .thenReturn(Optional.empty());

        CopilotRequest pendingReq = new CopilotRequest(ownedConversation, "client-req-stale", "hash-val",
                CopilotRequestStatus.PENDING, "lease-tok-stale", LocalDateTime.now().plusSeconds(120));
        pendingReq.setId(444L);
        when(txHelper.startRequestTx(eq(100L), eq("client-req-stale"), anyString(), anyString(), eq(120)))
                .thenReturn(pendingReq);

        AiQueryResponseDto aiResponse = new AiQueryResponseDto("Late answer", "gpt-6-luna");
        when(aiCopilotService.query(any(AiQueryRequestDto.class))).thenReturn(aiResponse);

        // completeRequestTx throws IllegalStateException because sweeper/lease expired
        doThrow(new IllegalStateException("Cannot complete request: lease expired"))
                .when(txHelper).completeRequestTx(eq(444L), eq("lease-tok-stale"), anyString(), anyString(), any(), any(), anyString());

        CopilotRequest expiredReq = new CopilotRequest(ownedConversation, "client-req-stale", "hash-val",
                CopilotRequestStatus.FAILED, null, null);
        expiredReq.setId(444L);
        when(requestRepository.findById(444L)).thenReturn(Optional.of(expiredReq));

        CopilotMessage userMsg = new CopilotMessage(expiredReq, CopilotMessageRole.USER, "Stale query");
        CopilotMessage assistantMsg = new CopilotMessage(expiredReq, CopilotMessageRole.ASSISTANT, "Timed out");
        when(messageRepository.findByRequestIdOrderByCreatedAtAsc(444L)).thenReturn(List.of(userMsg, assistantMsg));

        CopilotSubmissionRequestDto submission = new CopilotSubmissionRequestDto("client-req-stale", "Stale query");

        CopilotMessageResponseDto response = conversationService.submitMessage(100L, submission);

        assertNotNull(response);
        assertEquals(CopilotRequestStatus.FAILED, response.getStatus());
    }
}
