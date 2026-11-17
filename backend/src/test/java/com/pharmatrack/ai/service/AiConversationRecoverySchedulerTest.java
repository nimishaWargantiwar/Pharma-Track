package com.pharmatrack.ai.service;

import com.pharmatrack.ai.config.AiCopilotProperties;
import com.pharmatrack.entity.CopilotRequestStatus;
import com.pharmatrack.repository.CopilotRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiConversationRecoverySchedulerTest {

    @Mock
    private CopilotRequestRepository requestRepository;

    @Mock
    private AiConversationTxHelper txHelper;

    private AiConversationRecoveryScheduler recoveryScheduler;

    @BeforeEach
    void setUp() {
        recoveryScheduler = new AiConversationRecoveryScheduler(requestRepository, txHelper);
    }

    @Test
    @DisplayName("1. Expired PENDING requests are discovered and recovered via the transactional helper")
    void shouldDiscoverAndRecoverExpiredPendingRequests() {
        List<Long> expiredIds = List.of(101L, 102L);
        when(requestRepository.findExpiredCandidateIds(eq(CopilotRequestStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(expiredIds);
        when(txHelper.recoverExpiredRequestTx(101L)).thenReturn(true);
        when(txHelper.recoverExpiredRequestTx(102L)).thenReturn(true);

        int recovered = recoveryScheduler.recoverExpiredRequests();

        assertEquals(2, recovered);
        verify(txHelper).recoverExpiredRequestTx(101L);
        verify(txHelper).recoverExpiredRequestTx(102L);
    }

    @Test
    @DisplayName("2. Non-expired PENDING requests remain untouched (cutoff filter excludes active leases)")
    void shouldLeaveNonExpiredPendingRequestsUntouched() {
        when(requestRepository.findExpiredCandidateIds(eq(CopilotRequestStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        int recovered = recoveryScheduler.recoverExpiredRequests();

        assertEquals(0, recovered);
        verify(txHelper, never()).recoverExpiredRequestTx(any());
    }

    @Test
    @DisplayName("3. Already COMPLETED and FAILED requests remain unchanged (query queries only PENDING)")
    void shouldIgnoreCompletedAndFailedRequests() {
        ArgumentCaptor<CopilotRequestStatus> statusCaptor = ArgumentCaptor.forClass(CopilotRequestStatus.class);
        when(requestRepository.findExpiredCandidateIds(statusCaptor.capture(), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        recoveryScheduler.recoverExpiredRequests();

        assertEquals(CopilotRequestStatus.PENDING, statusCaptor.getValue());
        verify(txHelper, never()).recoverExpiredRequestTx(any());
    }

    @Test
    @DisplayName("4. A recovery failure for one candidate does not prevent processing other candidates in the batch")
    void shouldContinueBatchProcessingWhenIndividualCandidateRecoveryFails() {
        List<Long> candidateIds = List.of(201L, 202L, 203L);
        when(requestRepository.findExpiredCandidateIds(eq(CopilotRequestStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(candidateIds);

        // First candidate throws unexpected exception (e.g. transient DB lock timeout)
        doThrow(new RuntimeException("Lock acquisition failure"))
                .when(txHelper).recoverExpiredRequestTx(201L);
        // Second and third candidates succeed
        when(txHelper.recoverExpiredRequestTx(202L)).thenReturn(true);
        when(txHelper.recoverExpiredRequestTx(203L)).thenReturn(true);

        int recovered = recoveryScheduler.recoverExpiredRequests();

        assertEquals(2, recovered);
        verify(txHelper).recoverExpiredRequestTx(201L);
        verify(txHelper).recoverExpiredRequestTx(202L);
        verify(txHelper).recoverExpiredRequestTx(203L);
    }

    @Test
    @DisplayName("5. Concurrent recovery attempts cannot duplicate state transitions (recoverExpiredRequestTx returns false)")
    void shouldHandleConcurrentRecoveryGracefullyWhenCandidateAlreadyClaimed() {
        List<Long> candidateIds = List.of(301L);
        when(requestRepository.findExpiredCandidateIds(eq(CopilotRequestStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(candidateIds);

        // Another instance or thread claimed and transitioned the request first, so helper safely returns false
        when(txHelper.recoverExpiredRequestTx(301L)).thenReturn(false);

        int recovered = recoveryScheduler.recoverExpiredRequests();

        assertEquals(0, recovered);
        verify(txHelper).recoverExpiredRequestTx(301L);
    }

    @Test
    @DisplayName("6. Scheduled worker uses bounded pagination and delegates to existing transactional helper")
    void shouldScheduleRecoveryWithBoundedBatchSize() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(requestRepository.findExpiredCandidateIds(eq(CopilotRequestStatus.PENDING), any(LocalDateTime.class), pageableCaptor.capture()))
                .thenReturn(Collections.emptyList());

        recoveryScheduler.scheduleRecovery();

        Pageable pageable = pageableCaptor.getValue();
        assertEquals(0, pageable.getPageNumber());
        assertEquals(AiConversationRecoveryScheduler.DEFAULT_BATCH_SIZE, pageable.getPageSize());
    }

    @Test
    @DisplayName("7. Configured lease duration (120s) provides sufficient headroom over provider timeout (60s)")
    void shouldVerifyLeaseDurationConsistencyWithProviderTimeout() {
        AiCopilotProperties properties = new AiCopilotProperties();

        int defaultReadTimeoutSeconds = properties.getReadTimeoutMs() / 1000;
        int defaultConnectTimeoutSeconds = properties.getConnectTimeoutMs() / 1000;
        int totalProviderTimeoutSeconds = defaultReadTimeoutSeconds + defaultConnectTimeoutSeconds;

        assertEquals(60, defaultReadTimeoutSeconds);
        assertEquals(3, defaultConnectTimeoutSeconds);
        assertEquals(120, AiConversationService.DEFAULT_LEASE_DURATION_SECONDS);

        // Headroom assertion: 120s lease must provide sufficient headroom over HTTP provider timeout
        assertTrue(AiConversationService.DEFAULT_LEASE_DURATION_SECONDS >= totalProviderTimeoutSeconds,
                "120-second lease should provide headroom over HTTP provider timeout");
    }
}
