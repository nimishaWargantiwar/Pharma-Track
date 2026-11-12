package com.pharmatrack.exception;

/**
 * Exception thrown when an administrative user-management or authentication
 * policy validation fails (e.g., invalid input, self-disable, or last-admin protection).
 */
public class InvalidUserOperationException extends RuntimeException {

    public InvalidUserOperationException(String message) {
        super(message);
    }
}

