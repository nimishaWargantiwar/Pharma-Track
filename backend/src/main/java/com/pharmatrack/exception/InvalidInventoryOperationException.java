package com.pharmatrack.exception;

/**
 * Exception thrown when an inventory or warehouse operation violates business,
 * stock, expiry, unit, or manufacturing order validation rules.
 */
public class InvalidInventoryOperationException extends RuntimeException {

    public InvalidInventoryOperationException(String message) {
        super(message);
    }

    public InvalidInventoryOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}

