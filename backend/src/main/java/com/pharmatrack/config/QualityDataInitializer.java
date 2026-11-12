package com.pharmatrack.config;

import com.pharmatrack.repository.BatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Safely initializes {@code qa_status = PENDING_QA} for any existing COMPLETED batches
 * created prior to Quality Management Phase 1 deployment.
 * Uses a direct SQL bulk update without modifying {@code updated_at}, {@code actual_yield},
 * {@code status}, or execution history timestamps.
 */
@Component
public class QualityDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QualityDataInitializer.class);

    private final BatchRepository batchRepository;

    public QualityDataInitializer(BatchRepository batchRepository) {
        this.batchRepository = batchRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int initializedCount = batchRepository.initializePendingQaStatusForCompletedBatches();
        if (initializedCount > 0) {
            log.info("Quality Management initialization: set qa_status=PENDING_QA on {} historical COMPLETED batch(es)",
                    initializedCount);
        }
    }
}

