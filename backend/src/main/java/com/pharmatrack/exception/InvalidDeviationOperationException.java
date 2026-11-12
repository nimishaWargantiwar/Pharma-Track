package com.pharmatrack.exception;

/**
 * Domain exception thrown when a requested Deviation or CAPA lifecycle operation
 * violates stage-progression rules, link consistency, closure gates, or immutability constraints.
 */
public class InvalidDeviationOperationException extends RuntimeException {

    public InvalidDeviationOperationException(String message) {
        super(message);
    }

    public InvalidDeviationOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}

