package com.pharmatrack.ai.exception;

import com.pharmatrack.exception.DuplicateResourceException;

/**
 * Domain conflict exception thrown when a client request ID is reused across
 * different batches, different users, or concurrent generation attempts.
 */
public class BatchAnalysisConflictException extends DuplicateResourceException {

    public BatchAnalysisConflictException(String message) {
        super(message);
    }
}
