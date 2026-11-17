package com.pharmatrack.ai.exception;

/**
 * Thrown when an AI-generated batch analysis report fails structural JSON parsing,
 * required section validation, or GxP compliance rules.
 */
public class BatchAnalysisValidationException extends RuntimeException {

    public BatchAnalysisValidationException(String message) {
        super(message);
    }

    public BatchAnalysisValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
