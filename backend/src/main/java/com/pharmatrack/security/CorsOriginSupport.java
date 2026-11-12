package com.pharmatrack.security;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared origin normalization and strict validation rules for CORS configuration
 * across startup guards and Spring Security configuration.
 */
public final class CorsOriginSupport {

    private CorsOriginSupport() {
    }

    /**
     * Trims whitespace, strips trailing slashes, deduplicates preserving order, and validates
     * strict RFC 6454 origin syntax when {@code strictValidation} is true.
     */
    public static List<String> normalizeAndValidateOrigins(Collection<String> rawOrigins, boolean strictValidation) {
        if (rawOrigins == null || rawOrigins.isEmpty()) {
            return List.of();
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : rawOrigins) {
            if (raw == null) {
                continue;
            }
            String candidate = stripTrailingSlashes(raw.trim());
            if (candidate.isEmpty()) {
                continue;
            }

            if (strictValidation) {
                normalized.add(validateAndNormalizeStrictOrigin(candidate));
            } else {
                if ("*".equals(candidate) || candidate.contains("*")) {
                    throw new IllegalStateException(
                            "CORS configuration error: wildcard origin '" + candidate + "' is not permitted.");
                }
                normalized.add(normalizeIfValidUri(candidate));
            }
        }
        return List.copyOf(normalized);
    }

    public static String validateAndNormalizeStrictOrigin(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException(
                    "Production configuration error: CORS_ALLOWED_ORIGINS cannot contain blank entries.");
        }
        String stripped = stripTrailingSlashes(candidate.trim());
        if ("*".equals(stripped) || stripped.contains("*")) {
            throw new IllegalStateException(
                    "Production configuration error: Invalid CORS_ALLOWED_ORIGINS entry '" + candidate
                            + "'. Specify exact http:// or https:// origins (wildcard '*' is not permitted).");
        }

        URI uri;
        try {
            uri = URI.create(stripped);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Production configuration error: Invalid CORS_ALLOWED_ORIGINS URL '" + candidate + "'.", ex);
        }

        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalStateException(
                    "Production configuration error: Invalid CORS_ALLOWED_ORIGINS entry '" + candidate
                            + "'. Specify exact http:// or https:// origins (wildcard '*' is not permitted).");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getRawUserInfo() != null) {
            throw new IllegalStateException(
                    "Production configuration error: Invalid CORS_ALLOWED_ORIGINS host in '" + candidate + "'.");
        }

        String rawPath = uri.getRawPath();
        if ((rawPath != null && !rawPath.isEmpty())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalStateException(
                    "Production configuration error: CORS_ALLOWED_ORIGINS entry '" + candidate
                            + "' must be an origin only (scheme + host + optional port) without path, query, or fragment.");
        }

        StringBuilder canonical = new StringBuilder(scheme)
                .append("://")
                .append(host.toLowerCase(Locale.ROOT));
        if (uri.getPort() != -1) {
            canonical.append(":").append(uri.getPort());
        }
        return canonical.toString();
    }

    private static String normalizeIfValidUri(String candidate) {
        try {
            URI uri = URI.create(candidate);
            if (uri.getScheme() != null && uri.getHost() != null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null) {
                StringBuilder canonical = new StringBuilder(uri.getScheme().toLowerCase(Locale.ROOT))
                        .append("://")
                        .append(uri.getHost().toLowerCase(Locale.ROOT));
                if (uri.getPort() != -1) {
                    canonical.append(":").append(uri.getPort());
                }
                return canonical.toString();
            }
        } catch (IllegalArgumentException ignored) {
            // Preserve non-URI local placeholder if needed in non-prod unit tests
        }
        return candidate;
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}

