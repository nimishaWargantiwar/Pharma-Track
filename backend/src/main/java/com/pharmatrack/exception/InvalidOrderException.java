package com.pharmatrack.exception;

/**
 * Exception thrown when a Manufacturing Order or Batch fails domain, validation,
 * or lifecycle business rules (e.g., inactive product, unapproved recipe, mismatched
 * product/recipe, non-multiple planned quantity, or invalid status transition).
 */
public class InvalidOrderException extends RuntimeException {

    public InvalidOrderException(String message) {
        super(message);
    }

    public InvalidOrderException(String message, Throwable cause) {
        super(message, cause);
    }
}

