package com.pharmatrack.exception;

/**
 * Exception thrown when an attempt is made to persist a duplicate unique resource.
 */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}

