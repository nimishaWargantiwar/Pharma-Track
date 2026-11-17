package com.pharmatrack.security;

import org.springframework.security.authentication.BadCredentialsException;

/**
 * Specialized authentication exception carrying a stable, non-sensitive error code
 * (e.g. TOKEN_EXPIRED, TOKEN_INVALID, TOKEN_REVOKED, ACCOUNT_DISABLED, AUTHENTICATION_REQUIRED).
 */
public class JwtAuthenticationException extends BadCredentialsException {

    public static final String CODE_TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String CODE_TOKEN_INVALID = "TOKEN_INVALID";
    public static final String CODE_TOKEN_REVOKED = "TOKEN_REVOKED";
    public static final String CODE_ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    public static final String CODE_AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";

    private final String errorCode;

    public JwtAuthenticationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public JwtAuthenticationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
