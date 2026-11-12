package com.pharmatrack.exception;

/**
 * Domain exception thrown when a requested Quality Control (QC) or Quality Assurance (QA)
 * operation violates GMP workflow rules or batch disposition constraints.
 */
public class InvalidQualityOperationException extends RuntimeException {

    public InvalidQualityOperationException(String message) {
        super(message);
    }

    public InvalidQualityOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}

