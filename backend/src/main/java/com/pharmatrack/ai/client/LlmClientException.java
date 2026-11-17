package com.pharmatrack.ai.client;

/**
 * Sanitized runtime exception thrown when an AI provider call fails, times out,
 * returns an HTTP error, or produces an invalid response.
 *
 * <p>Security guarantee: Exception messages and string representations are strictly sanitized
 * to ensure that API keys, Authorization headers, internal network addresses, or raw vendor
 * response dumps are never leaked into server logs or propagated to API clients.</p>
 */
public class LlmClientException extends RuntimeException {

    public enum ErrorCategory {
        UNAUTHORIZED,           // HTTP 401
        FORBIDDEN,              // HTTP 403
        BAD_REQUEST,            // HTTP 400
        RATE_LIMITED,           // HTTP 429
        SERVER_ERROR,           // HTTP 5xx
        TIMEOUT,                // Socket read/connect timeout
        CONNECTION_FAILURE,     // Network unreachable/DNS failure
        INVALID_RESPONSE,       // Malformed JSON or empty output text
        CONFIGURATION_ERROR     // Missing required settings or disabled
    }

    private final ErrorCategory category;
    private final Integer httpStatus;

    public LlmClientException(ErrorCategory category, String safeMessage) {
        super(safeMessage);
        this.category = category;
        this.httpStatus = null;
    }

    public LlmClientException(ErrorCategory category, Integer httpStatus, String safeMessage) {
        super(safeMessage);
        this.category = category;
        this.httpStatus = httpStatus;
    }

    public LlmClientException(ErrorCategory category, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.category = category;
        this.httpStatus = null;
    }

    public LlmClientException(ErrorCategory category, Integer httpStatus, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.category = category;
        this.httpStatus = httpStatus;
    }

    public ErrorCategory getCategory() {
        return category;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    @Override
    public String toString() {
        return "LlmClientException{" +
                "category=" + category +
                ", httpStatus=" + httpStatus +
                ", message=" + getMessage() +
                '}';
    }
}
