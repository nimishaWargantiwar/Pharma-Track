package com.pharmatrack.exception;

/**
 * Exception thrown when a Production Execution step operation violates business rules
 * or lifecycle state transitions (e.g., starting steps out of order, executing steps on
 * a non-running batch, or completing a step without required execution values).
 */
public class InvalidExecutionStepException extends RuntimeException {

    public InvalidExecutionStepException(String message) {
        super(message);
    }

    public InvalidExecutionStepException(String message, Throwable cause) {
        super(message, cause);
    }
}

