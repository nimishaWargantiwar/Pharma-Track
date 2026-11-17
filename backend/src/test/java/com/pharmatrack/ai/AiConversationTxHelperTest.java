package com.pharmatrack.ai;

import com.pharmatrack.ai.service.AiConversationTxHelper;
import com.pharmatrack.entity.CopilotConversation;
import com.pharmatrack.entity.CopilotMessage;
import com.pharmatrack.entity.CopilotMessageRole;
import com.pharmatrack.entity.CopilotRequest;
import com.pharmatrack.entity.CopilotRequestStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.CopilotConversationRepository;
import com.pharmatrack.repository.CopilotMessageRepository;
import com.pharmatrack.repository.CopilotRequestRepository;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.service.AuditTrailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AiConversationTxHelper} verifying atomic transaction orchestration,
 * row-locking enforcement, lease checks, affected row count validations, and safe audit logging.
 */
@ExtendWith(MockitoExtension.class)
class AiConversationTxHelperTest {

    @Mock
    private CopilotConversationRepository conversationRepository;

    @Mock
    private CopilotRequestRepository requestRepository;

    @Mock
    private CopilotMessageRepository messageRepository;

    @Mock
    private AuditTrailService auditTrailService;

    @Mock
    private AuthenticatedActorResolver authenticatedActorResolver;

    @InjectMocks
    private AiConversationTxHelper txHelper;

    private UserAccount testUser;
    private CopilotConversation testConversation;

    @BeforeEach
    void setUp() {
        testUser = new UserAccount("operator_test", "hash", "Test Operator", Role.OPERATOR, true);
        testUser.setId(10L);

        testConversation = new CopilotConversation(testUser, "Test Conversation Thread");
        testConversation.setId(100L);
    }

    @Test
    @DisplayName("Transaction A: startRequestTx should atomically create request, USER message, ASSISTANT placeholder, touch recency, and audit")
    void shouldAtomicallyStartRequest() {
        when(conversationRepository.findById(100L)).thenReturn(Optional.of(testConversation));
        when(requestRepository.saveAndFlush(any(CopilotRequest.class))).thenAnswer(invocation -> {
            CopilotRequest req = invocation.getArgument(0);
            req.setId(500L);
            return req;
        });
        when(conversationRepository.touchUpdatedAt(eq(100L), any(LocalDateTime.class))).thenReturn(1);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("operator_test");

        CopilotRequest result = txHelper.startRequestTx(
                100L,
                "req-uuid-1",
                "hash-abc",
                "What is the status of MO-2026-0001?",
                60
        );

        assertNotNull(result);
        assertEquals(500L, result.getId());
        assertEquals("req-uuid-1", result.getClientRequestId());
        assertEquals(CopilotRequestStatus.PENDING, result.getStatus());
        assertNotNull(result.getLeaseToken());
        assertNotNull(result.getLeaseExpiresAt());

        // Verify exactly one USER message and one ASSISTANT placeholder were inserted
        ArgumentCaptor<CopilotMessage> messageCaptor = ArgumentCaptor.forClass(CopilotMessage.class);
        verify(messageRepository, times(2)).saveAndFlush(messageCaptor.capture());

        CopilotMessage userMsg = messageCaptor.getAllValues().get(0);
        assertEquals(CopilotMessageRole.USER, userMsg.getRole());
        assertEquals("What is the status of MO-2026-0001?", userMsg.getContent());

        CopilotMessage assistantMsg = messageCaptor.getAllValues().get(1);
        assertEquals(CopilotMessageRole.ASSISTANT, assistantMsg.getRole());
        assertEquals("", assistantMsg.getContent());

        // Verify recency was touched
        verify(conversationRepository).touchUpdatedAt(eq(100L), any(LocalDateTime.class));

        // Verify audit logging without sensitive prompt or lease token
        ArgumentCaptor<Map<String, Object>> auditSnapshotCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditTrailService).recordAudit(
                eq(AiConversationTxHelper.AUDIT_ENTITY_TYPE),
                eq(100L),
                eq("Test Conversation Thread"),
                isNull(),
                eq(AiConversationTxHelper.ACTION_QUERY_SUBMITTED),
                eq("operator_test"),
                any(LocalDateTime.class),
                anyString(),
                isNull(),
                auditSnapshotCaptor.capture()
        );
        Map<String, Object> snapshot = auditSnapshotCaptor.getValue();
        assertEquals(500L, snapshot.get("requestId"));
        assertEquals("req-uuid-1", snapshot.get("clientRequestId"));
        assertFalse(snapshot.containsKey("leaseToken"));
        assertFalse(snapshot.containsKey("prompt"));
    }

    @Test
    @DisplayName("Transaction B: completeRequestTx should update placeholder under lock, set COMPLETED, clear lease, and touch recency")
    void shouldCompleteRequestUnderLock() {
        LocalDateTime now = LocalDateTime.now();
        CopilotRequest lockedRequest = new CopilotRequest(
                testConversation,
                "req-uuid-2",
                "hash-2",
                CopilotRequestStatus.PENDING,
                "token-valid",
                now.plusSeconds(30)
        );
        lockedRequest.setId(600L);

        CopilotMessage assistantPlaceholder = new CopilotMessage(lockedRequest, CopilotMessageRole.ASSISTANT, "");
        when(requestRepository.findByIdWithLock(600L)).thenReturn(Optional.of(lockedRequest));
        when(messageRepository.findByRequestIdAndRole(600L, CopilotMessageRole.ASSISTANT)).thenReturn(Optional.of(assistantPlaceholder));
        when(conversationRepository.touchUpdatedAt(eq(100L), any(LocalDateTime.class))).thenReturn(1);
        when(authenticatedActorResolver.requireCurrentActor()).thenReturn("operator_test");

        txHelper.completeRequestTx(
                600L,
                "token-valid",
                "Verified answer",
                "gpt-4o-mini",
                "MANUFACTURING_ORDER",
                "MO-2026-0001",
                "[]"
        );

        assertEquals(CopilotRequestStatus.COMPLETED, lockedRequest.getStatus());
        assertEquals("Verified answer", assistantPlaceholder.getContent());
        assertEquals("gpt-4o-mini", assistantPlaceholder.getModel());
        verify(messageRepository).saveAndFlush(assistantPlaceholder);
        assertNull(lockedRequest.getLeaseToken());
        assertNull(lockedRequest.getLeaseExpiresAt());
        assertNotNull(lockedRequest.getCompletedAt());
        verify(requestRepository).saveAndFlush(lockedRequest);

        // Verify audit logging
        verify(auditTrailService).recordAudit(
                eq(AiConversationTxHelper.AUDIT_ENTITY_TYPE),
                eq(100L),
                eq("Test Conversation Thread"),
                isNull(),
                eq(AiConversationTxHelper.ACTION_QUERY_COMPLETED),
                eq("operator_test"),
                any(LocalDateTime.class),
                anyString(),
                isNull(),
                anyMap()
        );
    }

    @Test
    @DisplayName("Transaction B: completeRequestTx should reject when lease token mismatches")
    void shouldRejectCompletionWhenLeaseTokenMismatches() {
        CopilotRequest lockedRequest = new CopilotRequest(
                testConversation,
                "req-uuid-3",
                "hash-3",
                CopilotRequestStatus.PENDING,
                "token-correct",
                LocalDateTime.now().plusSeconds(30)
        );
        lockedRequest.setId(700L);
        when(requestRepository.findByIdWithLock(700L)).thenReturn(Optional.of(lockedRequest));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            txHelper.completeRequestTx(
                    700L,
                    "token-WRONG",
                    "Answer",
                    "model",
                    null,
                    null,
                    null
            );
        });
        assertTrue(ex.getMessage().contains("lease token mismatch"));
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Transaction B: completeRequestTx should reject when lease has expired")
    void shouldRejectCompletionWhenLeaseExpired() {
        CopilotRequest lockedRequest = new CopilotRequest(
                testConversation,
                "req-uuid-4",
                "hash-4",
                CopilotRequestStatus.PENDING,
                "token-exp",
                LocalDateTime.now().minusSeconds(10) // Already expired
        );
        lockedRequest.setId(800L);
        when(requestRepository.findByIdWithLock(800L)).thenReturn(Optional.of(lockedRequest));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            txHelper.completeRequestTx(
                    800L,
                    "token-exp",
                    "Answer",
                    "model",
                    null,
                    null,
                    null
            );
        });
        assertTrue(ex.getMessage().contains("lease expired"));
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Transaction C: failRequestTx should update placeholder with safe failure message and mark FAILED")
    void shouldFailRequestSafely() {
        CopilotRequest lockedRequest = new CopilotRequest(
                testConversation,
                "req-uuid-5",
                "hash-5",
                CopilotRequestStatus.PENDING,
                "token-5",
                LocalDateTime.now().plusSeconds(30)
        );
        lockedRequest.setId(900L);

        CopilotMessage assistantPlaceholder = new CopilotMessage(lockedRequest, CopilotMessageRole.ASSISTANT, "");
        when(requestRepository.findByIdWithLock(900L)).thenReturn(Optional.of(lockedRequest));
        when(messageRepository.findByRequestIdAndRole(900L, CopilotMessageRole.ASSISTANT)).thenReturn(Optional.of(assistantPlaceholder));
        when(conversationRepository.touchUpdatedAt(eq(100L), any(LocalDateTime.class))).thenReturn(1);

        txHelper.failRequestTx(
                900L,
                "token-5",
                "The AI service was temporarily unavailable. Please retry.",
                "SocketTimeoutException during LLM call"
        );

        assertEquals(CopilotRequestStatus.FAILED, lockedRequest.getStatus());
        assertEquals("The AI service was temporarily unavailable. Please retry.", assistantPlaceholder.getContent());
        assertNull(lockedRequest.getLeaseToken());
        assertNotNull(lockedRequest.getCompletedAt());
        assertEquals("SocketTimeoutException during LLM call", lockedRequest.getErrorMessage());
        verify(requestRepository).saveAndFlush(lockedRequest);
        verify(messageRepository).saveAndFlush(assistantPlaceholder);
    }

    @Test
    @DisplayName("Transaction D: recoverExpiredRequestTx should not fail a request whose lease is still active")
    void shouldNotRecoverActivePendingRequest() {
        CopilotRequest activeRequest = new CopilotRequest(
                testConversation,
                "req-active",
                "hash-act",
                CopilotRequestStatus.PENDING,
                "token-act",
                LocalDateTime.now().plusSeconds(45) // Active
        );
        activeRequest.setId(1000L);
        when(requestRepository.findByIdWithLock(1000L)).thenReturn(Optional.of(activeRequest));

        boolean recovered = txHelper.recoverExpiredRequestTx(1000L);
        assertFalse(recovered, "Must not recover an active request whose lease is valid");
        assertEquals(CopilotRequestStatus.PENDING, activeRequest.getStatus());
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Transaction D: recoverExpiredRequestTx should successfully recover a genuinely expired pending request")
    void shouldRecoverGenuinelyExpiredPendingRequest() {
        CopilotRequest expiredRequest = new CopilotRequest(
                testConversation,
                "req-expired",
                "hash-exp",
                CopilotRequestStatus.PENDING,
                "token-old",
                LocalDateTime.now().minusSeconds(120) // Expired 2 minutes ago
        );
        expiredRequest.setId(1100L);

        CopilotMessage assistantPlaceholder = new CopilotMessage(expiredRequest, CopilotMessageRole.ASSISTANT, "");
        when(requestRepository.findByIdWithLock(1100L)).thenReturn(Optional.of(expiredRequest));
        when(messageRepository.findByRequestIdAndRole(1100L, CopilotMessageRole.ASSISTANT)).thenReturn(Optional.of(assistantPlaceholder));
        when(conversationRepository.touchUpdatedAt(eq(100L), any(LocalDateTime.class))).thenReturn(1);

        boolean recovered = txHelper.recoverExpiredRequestTx(1100L);
        assertTrue(recovered, "Must recover expired pending request");
        assertEquals(CopilotRequestStatus.FAILED, expiredRequest.getStatus());
        assertTrue(assistantPlaceholder.getContent().contains("timed out"));
        assertNull(expiredRequest.getLeaseToken());
        verify(requestRepository).saveAndFlush(expiredRequest);
        verify(messageRepository).saveAndFlush(assistantPlaceholder);

        // Verify audit logging
        verify(auditTrailService).recordAudit(
                eq(AiConversationTxHelper.AUDIT_ENTITY_TYPE),
                eq(100L),
                eq("Test Conversation Thread"),
                isNull(),
                eq(AiConversationTxHelper.ACTION_REQUEST_RECOVERED),
                eq("SYSTEM_RECOVERY"),
                any(LocalDateTime.class),
                anyString(),
                isNull(),
                anyMap()
        );
    }
}
