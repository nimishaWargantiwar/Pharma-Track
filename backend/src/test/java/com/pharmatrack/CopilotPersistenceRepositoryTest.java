package com.pharmatrack;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AI Copilot Conversation Persistence entities and repositories
 * using @DataJpaTest and an isolated in-memory H2 database.
 */
@DataJpaTest
class CopilotPersistenceRepositoryTest {

    @Autowired
    private CopilotConversationRepository conversationRepository;

    @Autowired
    private CopilotRequestRepository requestRepository;

    @Autowired
    private CopilotMessageRepository messageRepository;

    @Autowired
    private TestEntityManager entityManager;

    private UserAccount createAndPersistUser(String username, Role role) {
        UserAccount user = new UserAccount(
                username,
                "$2a$12$dummyHashForTestingPurposesOnly12345678901234567890",
                "Test User " + username,
                role,
                true
        );
        return entityManager.persistAndFlush(user);
    }

    @Test
    @DisplayName("Should persist and retrieve CopilotConversation with correct owner and field mappings")
    void shouldPersistAndRetrieveConversation() {
        UserAccount user = createAndPersistUser("operator_copilot_1", Role.OPERATOR);

        CopilotConversation conversation = new CopilotConversation(
                user,
                "Batch Yield Inquiries MO-2026-0001",
                "orders",
                "MANUFACTURING_ORDER",
                "MO-2026-0001"
        );
        CopilotConversation saved = conversationRepository.saveAndFlush(conversation);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        entityManager.clear();

        Optional<CopilotConversation> retrieved = conversationRepository.findById(saved.getId());
        assertTrue(retrieved.isPresent());
        assertEquals("Batch Yield Inquiries MO-2026-0001", retrieved.get().getTitle());
        assertEquals("orders", retrieved.get().getActiveModule());
        assertEquals("MANUFACTURING_ORDER", retrieved.get().getContextType());
        assertEquals("MO-2026-0001", retrieved.get().getContextId());
        assertEquals(user.getId(), retrieved.get().getUser().getId());
    }

    @Test
    @DisplayName("Should enforce owner scoping: findByIdAndUserId and existsByIdAndUserId")
    void shouldEnforceOwnerScoping() {
        UserAccount userA = createAndPersistUser("user_a", Role.OPERATOR);
        UserAccount userB = createAndPersistUser("user_b", Role.PRODUCTION_SUPERVISOR);

        CopilotConversation convA = new CopilotConversation(userA, "User A Conversation");
        CopilotConversation savedConvA = conversationRepository.saveAndFlush(convA);

        entityManager.clear();

        // User A can access
        Optional<CopilotConversation> foundA = conversationRepository.findByIdAndUserId(savedConvA.getId(), userA.getId());
        assertTrue(foundA.isPresent());
        assertTrue(conversationRepository.existsByIdAndUserId(savedConvA.getId(), userA.getId()));

        // User B cannot access User A's conversation
        Optional<CopilotConversation> foundB = conversationRepository.findByIdAndUserId(savedConvA.getId(), userB.getId());
        assertFalse(foundB.isPresent());
        assertFalse(conversationRepository.existsByIdAndUserId(savedConvA.getId(), userB.getId()));
    }

    @Test
    @DisplayName("Should order conversations by updatedAt descending")
    void shouldOrderConversationsByUpdatedAtDesc() {
        UserAccount user = createAndPersistUser("user_order_test", Role.OPERATOR);

        CopilotConversation conv1 = new CopilotConversation(user, "Old Conversation");
        CopilotConversation saved1 = conversationRepository.saveAndFlush(conv1);

        CopilotConversation conv2 = new CopilotConversation(user, "New Conversation");
        CopilotConversation saved2 = conversationRepository.saveAndFlush(conv2);

        // Explicitly set older timestamp on conv1
        saved1.setUpdatedAt(LocalDateTime.now().minusHours(2));
        saved2.setUpdatedAt(LocalDateTime.now());
        conversationRepository.saveAndFlush(saved1);
        conversationRepository.saveAndFlush(saved2);

        entityManager.clear();

        List<CopilotConversation> list = conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.getId());
        assertEquals(2, list.size());
        assertEquals("New Conversation", list.get(0).getTitle());
        assertEquals("Old Conversation", list.get(1).getTitle());
    }

    @Test
    @DisplayName("Should order conversations with equal updatedAt by descending ID deterministically across page boundaries")
    void shouldOrderConversationsWithEqualUpdatedAtByDescendingIdAcrossPages() {
        UserAccount user = createAndPersistUser("user_page_sort_test", Role.OPERATOR);
        LocalDateTime sameTimestamp = LocalDateTime.of(2026, 11, 14, 10, 0, 0);

        CopilotConversation conv1 = conversationRepository.saveAndFlush(new CopilotConversation(user, "Thread 1"));
        CopilotConversation conv2 = conversationRepository.saveAndFlush(new CopilotConversation(user, "Thread 2"));
        CopilotConversation conv3 = conversationRepository.saveAndFlush(new CopilotConversation(user, "Thread 3"));
        CopilotConversation conv4 = conversationRepository.saveAndFlush(new CopilotConversation(user, "Thread 4"));

        conv1.setUpdatedAt(sameTimestamp);
        conv2.setUpdatedAt(sameTimestamp);
        conv3.setUpdatedAt(sameTimestamp);
        conv4.setUpdatedAt(sameTimestamp);

        conversationRepository.saveAndFlush(conv1);
        conversationRepository.saveAndFlush(conv2);
        conversationRepository.saveAndFlush(conv3);
        conversationRepository.saveAndFlush(conv4);

        entityManager.clear();

        // Unpaginated query verification
        List<CopilotConversation> all = conversationRepository.findByUserIdOrderByUpdatedAtDescIdDesc(user.getId());
        assertEquals(4, all.size());
        assertTrue(all.get(0).getId() > all.get(1).getId());
        assertTrue(all.get(1).getId() > all.get(2).getId());
        assertTrue(all.get(2).getId() > all.get(3).getId());
        assertEquals(conv4.getId(), all.get(0).getId());
        assertEquals(conv3.getId(), all.get(1).getId());
        assertEquals(conv2.getId(), all.get(2).getId());
        assertEquals(conv1.getId(), all.get(3).getId());

        // Paginated query verification across page boundaries
        Page<CopilotConversation> page0 = conversationRepository.findByUserIdOrderByUpdatedAtDescIdDesc(
                user.getId(), PageRequest.of(0, 2));
        assertEquals(4, page0.getTotalElements());
        assertEquals(2, page0.getTotalPages());
        assertEquals(2, page0.getContent().size());
        assertEquals(conv4.getId(), page0.getContent().get(0).getId());
        assertEquals(conv3.getId(), page0.getContent().get(1).getId());

        Page<CopilotConversation> page1 = conversationRepository.findByUserIdOrderByUpdatedAtDescIdDesc(
                user.getId(), PageRequest.of(1, 2));
        assertEquals(2, page1.getContent().size());
        assertEquals(conv2.getId(), page1.getContent().get(0).getId());
        assertEquals(conv1.getId(), page1.getContent().get(1).getId());
    }

    @Test
    @DisplayName("Should persist CopilotRequest and enforce unique (conversation_id, client_request_id)")
    void shouldPersistRequestAndEnforceUniqueClientRequestId() {
        UserAccount user = createAndPersistUser("user_req_test", Role.OPERATOR);
        CopilotConversation conv = conversationRepository.saveAndFlush(new CopilotConversation(user, "Request Test Thread"));

        CopilotRequest req1 = new CopilotRequest(conv, "req-uuid-0001", "hash-abcdef1234567890");
        req1.setStatus(CopilotRequestStatus.PENDING);
        req1.setLeaseToken("token-xyz");
        CopilotRequest savedReq = requestRepository.saveAndFlush(req1);
        assertNotNull(savedReq.getId());
        assertNotNull(savedReq.getStartedAt());
        assertEquals(CopilotRequestStatus.PENDING, savedReq.getStatus());

        // Duplicate submission with same client_request_id in same conversation must throw DataIntegrityViolationException
        CopilotRequest reqDuplicate = new CopilotRequest(conv, "req-uuid-0001", "hash-different");
        assertThrows(DataIntegrityViolationException.class, () -> {
            requestRepository.saveAndFlush(reqDuplicate);
        });
    }

    @Test
    @DisplayName("Should persist CopilotMessage and enforce unique (request_id, role)")
    void shouldPersistMessageAndEnforceUniqueRequestRole() {
        UserAccount user = createAndPersistUser("user_msg_test", Role.OPERATOR);
        CopilotConversation conv = conversationRepository.saveAndFlush(new CopilotConversation(user, "Message Test Thread"));
        CopilotRequest req = requestRepository.saveAndFlush(new CopilotRequest(conv, "req-uuid-0002", "hash-999"));

        CopilotMessage userMsg = new CopilotMessage(req, CopilotMessageRole.USER, "What is the yield of batch 1?");
        CopilotMessage savedUserMsg = messageRepository.saveAndFlush(userMsg);
        assertNotNull(savedUserMsg.getId());
        assertEquals(CopilotMessageRole.USER, savedUserMsg.getRole());

        CopilotMessage assistantMsg = new CopilotMessage(
                req,
                CopilotMessageRole.ASSISTANT,
                "Batch 1 yield is 500 kg.",
                "gpt-4o-mini",
                "MANUFACTURING_ORDER",
                "MO-2026-0001",
                "[{\"recordType\":\"BATCH\",\"recordIdentifier\":\"BAT-01\"}]"
        );
        CopilotMessage savedAssistantMsg = messageRepository.saveAndFlush(assistantMsg);
        assertNotNull(savedAssistantMsg.getId());
        assertEquals(CopilotMessageRole.ASSISTANT, savedAssistantMsg.getRole());

        // Second USER message for same request must throw DataIntegrityViolationException
        CopilotMessage duplicateUserMsg = new CopilotMessage(req, CopilotMessageRole.USER, "Duplicate question");
        assertThrows(DataIntegrityViolationException.class, () -> {
            messageRepository.saveAndFlush(duplicateUserMsg);
        });
    }

    @Test
    @DisplayName("Should retrieve messages chronologically across a conversation")
    void shouldRetrieveMessagesChronologicallyAcrossConversation() {
        UserAccount user = createAndPersistUser("user_transcript_test", Role.OPERATOR);
        CopilotConversation conv = conversationRepository.saveAndFlush(new CopilotConversation(user, "Multi-Turn Thread"));

        // Request 1
        CopilotRequest req1 = requestRepository.saveAndFlush(new CopilotRequest(conv, "req-turn-1", "hash-1"));
        messageRepository.saveAndFlush(new CopilotMessage(req1, CopilotMessageRole.USER, "First query"));
        messageRepository.saveAndFlush(new CopilotMessage(req1, CopilotMessageRole.ASSISTANT, "First answer"));

        // Request 2
        CopilotRequest req2 = requestRepository.saveAndFlush(new CopilotRequest(conv, "req-turn-2", "hash-2"));
        messageRepository.saveAndFlush(new CopilotMessage(req2, CopilotMessageRole.USER, "Second query"));
        messageRepository.saveAndFlush(new CopilotMessage(req2, CopilotMessageRole.ASSISTANT, "Second answer"));

        entityManager.clear();

        List<CopilotMessage> transcript = messageRepository.findMessagesByConversationId(conv.getId());
        assertEquals(4, transcript.size());
        assertEquals("First query", transcript.get(0).getContent());
        assertEquals(CopilotMessageRole.USER, transcript.get(0).getRole());
        assertEquals("First answer", transcript.get(1).getContent());
        assertEquals(CopilotMessageRole.ASSISTANT, transcript.get(1).getRole());
        assertEquals("Second query", transcript.get(2).getContent());
        assertEquals("Second answer", transcript.get(3).getContent());

        // Verify request metadata was eagerly fetched via JOIN FETCH without LazyInitializationException
        assertNotNull(transcript.get(0).getRequest());
        assertEquals(req1.getId(), transcript.get(0).getRequest().getId());
        assertEquals("req-turn-1", transcript.get(0).getRequest().getClientRequestId());
        assertEquals(CopilotRequestStatus.PENDING, transcript.get(0).getRequest().getStatus());

        assertNotNull(transcript.get(2).getRequest());
        assertEquals(req2.getId(), transcript.get(2).getRequest().getId());
        assertEquals("req-turn-2", transcript.get(2).getRequest().getClientRequestId());
        assertEquals(CopilotRequestStatus.PENDING, transcript.get(2).getRequest().getStatus());
    }

    @Test
    @DisplayName("Should cascade delete requests and messages when conversation is deleted")
    void shouldCascadeDeleteRequestsAndMessagesOnConversationDelete() {
        UserAccount user = createAndPersistUser("user_cascade_test", Role.OPERATOR);
        CopilotConversation conv = conversationRepository.saveAndFlush(new CopilotConversation(user, "Cascade Thread"));
        CopilotRequest req = requestRepository.saveAndFlush(new CopilotRequest(conv, "req-cascade", "hash-c"));
        messageRepository.saveAndFlush(new CopilotMessage(req, CopilotMessageRole.USER, "Query"));
        messageRepository.saveAndFlush(new CopilotMessage(req, CopilotMessageRole.ASSISTANT, "Answer"));

        assertEquals(1, requestRepository.countByConversationId(conv.getId()));
        assertEquals(2, messageRepository.countByRequestId(req.getId()));

        // Delete conversation
        conversationRepository.delete(conv);
        conversationRepository.flush();
        entityManager.clear();

        assertFalse(conversationRepository.findById(conv.getId()).isPresent());
        // Verify requests and messages are deleted by cascade in persistence context
        assertEquals(0, requestRepository.findByConversationIdOrderByStartedAtAsc(conv.getId()).size());
        assertEquals(0, messageRepository.findByRequestIdOrderByCreatedAtAsc(req.getId()).size());
    }

    @Test
    @DisplayName("Should find only expired PENDING requests within bounded batch limit")
    void shouldFindExpiredCandidateIdsInBoundedBatch() {
        UserAccount user = createAndPersistUser("user_expired_query", Role.OPERATOR);
        CopilotConversation conv = conversationRepository.saveAndFlush(new CopilotConversation(user, "Expired Search Thread"));

        LocalDateTime now = LocalDateTime.now();

        // 1. Expired PENDING request (older expiration)
        CopilotRequest expired1 = new CopilotRequest(conv, "req-exp-1", "hash-1", CopilotRequestStatus.PENDING, "tok-1", now.minusMinutes(10));
        expired1.setStartedAt(now.minusMinutes(12));
        requestRepository.saveAndFlush(expired1);

        // 2. Expired PENDING request (newer expiration)
        CopilotRequest expired2 = new CopilotRequest(conv, "req-exp-2", "hash-2", CopilotRequestStatus.PENDING, "tok-2", now.minusMinutes(2));
        expired2.setStartedAt(now.minusMinutes(4));
        requestRepository.saveAndFlush(expired2);

        // 3. Active PENDING request (lease expires in future)
        CopilotRequest active = new CopilotRequest(conv, "req-active", "hash-3", CopilotRequestStatus.PENDING, "tok-3", now.plusMinutes(5));
        active.setStartedAt(now);
        requestRepository.saveAndFlush(active);

        // 4. Expired COMPLETED request
        CopilotRequest completed = new CopilotRequest(conv, "req-completed", "hash-4", CopilotRequestStatus.COMPLETED, null, now.minusMinutes(10));
        completed.setStartedAt(now.minusMinutes(12));
        completed.setCompletedAt(now.minusMinutes(11));
        requestRepository.saveAndFlush(completed);

        // 5. Expired FAILED request
        CopilotRequest failed = new CopilotRequest(conv, "req-failed", "hash-5", CopilotRequestStatus.FAILED, null, now.minusMinutes(10));
        failed.setStartedAt(now.minusMinutes(12));
        failed.setCompletedAt(now.minusMinutes(11));
        requestRepository.saveAndFlush(failed);

        entityManager.clear();

        // Query candidates with batch size 10
        List<Long> candidates = requestRepository.findExpiredCandidateIds(
                CopilotRequestStatus.PENDING,
                now,
                PageRequest.of(0, 10)
        );

        assertEquals(2, candidates.size());
        assertEquals(expired1.getId(), candidates.get(0), "Should order oldest expired lease first");
        assertEquals(expired2.getId(), candidates.get(1));

        // Query candidates with bounded batch size 1
        List<Long> boundedCandidates = requestRepository.findExpiredCandidateIds(
                CopilotRequestStatus.PENDING,
                now,
                PageRequest.of(0, 1)
        );
        assertEquals(1, boundedCandidates.size());
        assertEquals(expired1.getId(), boundedCandidates.get(0));
    }
}
