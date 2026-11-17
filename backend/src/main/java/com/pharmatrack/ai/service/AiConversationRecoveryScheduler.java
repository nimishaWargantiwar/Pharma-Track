package com.pharmatrack.ai.service;

import com.pharmatrack.entity.CopilotRequestStatus;
import com.pharmatrack.repository.CopilotRequestRepository;
import com.pharmatrack.util.AppTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled background recovery worker for persistent AI Copilot inquiries.
 *
 * <p>Identifies abandoned or interrupted requests in {@link CopilotRequestStatus#PENDING}
 * status whose leases have expired, and reclaims them in bounded batches.
 *
 * <p>Concurrency & Resilience:
 * <ul>
 *   <li>Processes requests individually using isolated {@code Propagation.REQUIRES_NEW} transactions.</li>
 *   <li>Relies on row-level pessimistic locking and lease checks in {@link AiConversationTxHelper#recoverExpiredRequestTx(Long)}.</li>
 *   <li>Safe for multiple application instances running concurrently.</li>
 *   <li>Failures on single requests do not abort batch processing.</li>
 *   <li>Never logs sensitive inquiry content, answers, or credentials.</li>
 * </ul>
 */
@Component
public class AiConversationRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiConversationRecoveryScheduler.class);

    public static final int DEFAULT_BATCH_SIZE = 100;

    private final CopilotRequestRepository requestRepository;
    private final AiConversationTxHelper txHelper;

    public AiConversationRecoveryScheduler(CopilotRequestRepository requestRepository,
                                           AiConversationTxHelper txHelper) {
        this.requestRepository = requestRepository;
        this.txHelper = txHelper;
    }

    /**
     * Periodic scheduled recovery execution approximately every 30 seconds.
     */
    @Scheduled(fixedDelayString = "${app.ai.recovery.fixed-delay-ms:30000}", initialDelayString = "${app.ai.recovery.initial-delay-ms:15000}")
    public void scheduleRecovery() {
        recoverExpiredRequests();
    }

    /**
     * Finds and recovers expired PENDING requests in a bounded default batch (up to 100).
     *
     * @return count of successfully recovered requests in this execution
     */
    public int recoverExpiredRequests() {
        return recoverExpiredRequestsBatch(DEFAULT_BATCH_SIZE);
    }

    /**
     * Finds and recovers expired PENDING requests with a custom batch bound.
     *
     * @param batchSize maximum number of expired candidate requests to process (bounded between 1 and 500)
     * @return count of successfully recovered requests in this execution
     */
    public int recoverExpiredRequestsBatch(int batchSize) {
        int boundedBatchSize = Math.max(1, Math.min(batchSize, 500));
        LocalDateTime cutoff = AppTime.nowDateTime();
        Pageable pageable = PageRequest.of(0, boundedBatchSize);

        List<Long> expiredCandidateIds;
        try {
            expiredCandidateIds = requestRepository.findExpiredCandidateIds(
                    CopilotRequestStatus.PENDING,
                    cutoff,
                    pageable
            );
        } catch (Exception ex) {
            log.error("Failed to query expired Copilot request candidates: {}", ex.getMessage());
            return 0;
        }

        if (expiredCandidateIds.isEmpty()) {
            return 0;
        }

        log.info("Found {} expired PENDING request candidates for recovery", expiredCandidateIds.size());

        int recoveredCount = 0;
        for (Long requestId : expiredCandidateIds) {
            try {
                boolean recovered = txHelper.recoverExpiredRequestTx(requestId);
                if (recovered) {
                    recoveredCount++;
                    log.debug("Successfully recovered expired Copilot request id={}", requestId);
                } else {
                    log.debug("Copilot request id={} was no longer eligible for recovery (already transitioned or active)", requestId);
                }
            } catch (Exception ex) {
                // Catch any exception for this specific request so the rest of the batch continues
                log.warn("Recovery failed for Copilot request id={}: {}", requestId, ex.getMessage());
            }
        }

        if (recoveredCount > 0) {
            log.info("Completed recovery run: successfully recovered {}/{} expired Copilot requests",
                    recoveredCount, expiredCandidateIds.size());
        }

        return recoveredCount;
    }
}
