package com.pharmatrack.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Utility helpers for sanitizing client-facing error payloads and server-side diagnostic logs
 * so secrets, credentials, JWTs, JDBC URLs, SQL statements, filesystem paths, and raw stack
 * details are never leaked.
 */
public final class SensitiveDataSanitizer {

    public static final String GENERIC_SERVER_ERROR_MESSAGE = "An unexpected internal server error occurred";
    public static final String GENERIC_AUTH_FAILURE_MESSAGE = "Authentication failed";
    public static final String DEFAULT_AUTHENTICATION_REQUIRED_MESSAGE = "Authentication is required to access this resource";

    private static final int MAX_LOG_TEXT_LENGTH = 500;
    private static final int MAX_METHOD_LENGTH = 16;
    private static final int MAX_PARAM_NAME_LENGTH = 64;

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}]+");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern JWT_PATTERN = Pattern.compile("\\beyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]*");
    private static final Pattern JDBC_URL_PATTERN = Pattern.compile("(?i)jdbc:[^\\s\"'`;,\\]\\)]+");
    private static final Pattern URL_CREDENTIALS_PATTERN = Pattern.compile("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@");
    private static final Pattern SECRET_KV_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|jwt[_-]?secret|db[_-]?password|token|access[_-]?token|refresh[_-]?token|api[_-]?key|apikey|authorization|trustCertificateKeyStorePassword)\\s*([:=])\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;'\"}]+)");
    private static final Pattern SAFE_HTTP_METHOD = Pattern.compile("^[A-Z]{1," + MAX_METHOD_LENGTH + "}$");

    private static final Set<Class<? extends AuthenticationException>> SAFE_CLIENT_AUTH_EXCEPTIONS = Set.of(
            BadCredentialsException.class,
            DisabledException.class,
            InsufficientAuthenticationException.class,
            LockedException.class,
            AccountExpiredException.class,
            CredentialsExpiredException.class,
            UsernameNotFoundException.class
    );

    private SensitiveDataSanitizer() {
    }

    /**
     * Generates a short, opaque correlation reference for correlating client 5xx responses
     * with sanitized server-side error logs without exposing internal details.
     */
    public static String generateErrorReference() {
        return "ERR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /**
     * Formats an internal-server-error client message containing zero internal diagnostics
     * while preserving an opaque support correlation reference.
     */
    public static String buildGenericServerErrorMessage(String errorReference) {
        if (errorReference == null || errorReference.isBlank()) {
            return GENERIC_SERVER_ERROR_MESSAGE;
        }
        return GENERIC_SERVER_ERROR_MESSAGE + " (reference: " + errorReference + ")";
    }

    /**
     * Strips control characters and redacts tokens, passwords, secrets, and JDBC URLs
     * before writing diagnostic context to server logs.
     */
    public static String sanitizeForLog(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String sanitized = CONTROL_CHARS.matcher(raw).replaceAll(" ").trim();
        sanitized = BEARER_PATTERN.matcher(sanitized).replaceAll("Bearer [REDACTED]");
        sanitized = JWT_PATTERN.matcher(sanitized).replaceAll("[REDACTED_JWT]");
        sanitized = JDBC_URL_PATTERN.matcher(sanitized).replaceAll("jdbc:[REDACTED]");
        sanitized = URL_CREDENTIALS_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]@");
        sanitized = SECRET_KV_PATTERN.matcher(sanitized).replaceAll("$1$2[REDACTED]");
        if (sanitized.length() > MAX_LOG_TEXT_LENGTH) {
            return sanitized.substring(0, MAX_LOG_TEXT_LENGTH) + "...(truncated)";
        }
        return sanitized;
    }

    /**
     * Formats a concise, secret-redacted exception chain summary + top stack frames for server logs.
     */
    public static String formatSafeExceptionForLog(Throwable ex) {
        if (ex == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < 5) {
            if (depth > 0) {
                sb.append(" | causedBy=");
            }
            sb.append(current.getClass().getName());
            String msg = sanitizeForLog(current.getMessage());
            if (!msg.isEmpty()) {
                sb.append(": ").append(msg);
            }
            current = current.getCause();
            depth++;
        }

        StackTraceElement[] stack = ex.getStackTrace();
        if (stack != null && stack.length > 0) {
            sb.append(" | at=");
            int appended = 0;
            for (StackTraceElement frame : stack) {
                String cls = frame.getClassName();
                if (appended == 0 || cls.startsWith("com.pharmatrack.")) {
                    if (appended > 0) {
                        sb.append(" <- ");
                    }
                    sb.append(frame.getClassName())
                            .append(".")
                            .append(frame.getMethodName())
                            .append("(")
                            .append(frame.getFileName() != null ? frame.getFileName() : "UnknownSource")
                            .append(":")
                            .append(frame.getLineNumber())
                            .append(")");
                    appended++;
                    if (appended >= 5) {
                        break;
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Formats HTTP method + URI cleanly without logging query parameters, headers, cookies, or bodies.
     */
    public static String sanitizeRequestSummary(HttpServletRequest request) {
        if (request == null) {
            return "[NO_HTTP_REQUEST]";
        }
        String method = sanitizeSingleLine(request.getMethod(), MAX_METHOD_LENGTH, "UNKNOWN");
        String uri = sanitizeSingleLine(request.getRequestURI(), 200, "/");
        return method + " " + uri;
    }

    /**
     * Masks embedded credentials and query parameters in a JDBC URL prior to startup logging.
     */
    public static String maskJdbcUrl(String url) {
        if (url == null || url.isBlank()) {
            return "[UNAVAILABLE]";
        }
        String sanitized = CONTROL_CHARS.matcher(url).replaceAll("").trim();
        int queryIdx = sanitized.indexOf('?');
        if (queryIdx >= 0) {
            sanitized = sanitized.substring(0, queryIdx);
        }
        sanitized = sanitized.replaceAll("(?i)(jdbc:[a-z0-9:_-]+://)[^/@\\s]+@", "$1[REDACTED]@");
        return sanitized;
    }

    /**
     * Resolves a safe client-facing message from an {@link AuthenticationException}.
     * Only explicit safe authentication exceptions with clean short messages pass through;
     * service-layer or technical messages collapse to a generic failure string.
     */
    public static String sanitizeAuthenticationMessage(AuthenticationException ex) {
        if (ex == null) {
            return GENERIC_AUTH_FAILURE_MESSAGE;
        }
        if (!SAFE_CLIENT_AUTH_EXCEPTIONS.contains(ex.getClass())) {
            return GENERIC_AUTH_FAILURE_MESSAGE;
        }
        return sanitizeClientAuthText(ex.getMessage(), GENERIC_AUTH_FAILURE_MESSAGE);
    }

    /**
     * Guards pre-set authentication error messages (e.g. from request attributes) against
     * accidental internal text exposure.
     */
    public static String sanitizeClientAuthText(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank() || candidate.length() > 160) {
            return fallback;
        }
        if (containsSensitiveTechnicalMarkers(candidate)) {
            return fallback;
        }
        return CONTROL_CHARS.matcher(candidate).replaceAll(" ").trim();
    }

    /**
     * Returns true if text contains SQL, JDBC, secret, token, stack-trace, or filesystem-path indicators
     * that must never be returned to API clients.
     */
    public static boolean containsSensitiveTechnicalMarkers(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("jdbc:")
                || lower.contains("sqlexception")
                || lower.contains("sqlstate")
                || lower.contains("java.sql.")
                || lower.contains("jakarta.persistence.")
                || lower.contains("hibernate")
                || lower.contains("org.springframework.")
                || lower.contains("com.pharmatrack.")
                || lower.contains("java.lang.")
                || lower.contains("caused by:")
                || lower.contains("insert into ")
                || lower.contains("delete from ")
                || lower.contains("foreign key constraint")
                || lower.contains("password=")
                || lower.contains("secret=")
                || lower.contains("jwt_secret")
                || lower.contains("db_url")
                || lower.contains("db_password")
                || lower.contains("bearer ")
                || lower.contains("/users/")
                || lower.contains("/home/")
                || lower.contains("/var/")
                || lower.contains("/etc/")
                || lower.contains("c:\\")) {
            return true;
        }
        return JWT_PATTERN.matcher(text).find();
    }

    public static String sanitizeSafeHttpMethod(String rawMethod) {
        if (rawMethod == null) {
            return null;
        }
        String upper = rawMethod.trim().toUpperCase(Locale.ROOT);
        return SAFE_HTTP_METHOD.matcher(upper).matches() ? upper : null;
    }

    public static String sanitizeParameterName(String rawName) {
        String safe = sanitizeSingleLine(rawName, MAX_PARAM_NAME_LENGTH, "parameter");
        return safe.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String sanitizeSingleLine(String raw, int maxLen, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String cleaned = CONTROL_CHARS.matcher(raw).replaceAll(" ").trim();
        if (cleaned.isEmpty()) {
            return fallback;
        }
        return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) : cleaned;
    }
}
