package com.pharmatrack.exception;

/**
 * Exception thrown when a Recipe fails domain or business rule validations
 * (e.g., non-sequential steps, inactive product, or attempting to modify an approved recipe).
 */
public class InvalidRecipeException extends RuntimeException {

    public InvalidRecipeException(String message) {
        super(message);
    }

    public InvalidRecipeException(String message, Throwable cause) {
        super(message, cause);
    }
}

