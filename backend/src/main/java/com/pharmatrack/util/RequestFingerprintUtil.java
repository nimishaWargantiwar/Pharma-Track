package com.pharmatrack.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic request fingerprint utility for AI Copilot inquiries.
 *
 * <p>Enforces Unicode NFC normalization, Unicode-aware whitespace collapsing,
 * unambiguous length-prefixed canonical encoding, and cryptographic SHA-256 hashing.</p>
 */
public final class RequestFingerprintUtil {

    private static final Pattern UNICODE_WHITESPACE = Pattern.compile("(?U)\\s+");
    private static final Pattern LEADING_TRAILING_UNICODE_WHITESPACE = Pattern.compile("(?U)^\\s+|\\s+$");

    private RequestFingerprintUtil() {
    }

    /**
     * Compute a deterministic 64-character hexadecimal SHA-256 hash of the normalized request attributes.
     *
     * @param query natural language inquiry
     * @param activeModule active frontend module (e.g. "orders")
     * @param contextType entity context type (e.g. "MANUFACTURING_ORDER")
     * @param contextId entity context identifier (e.g. "MO-2026-0001")
     * @return 64-character lowercase hex string
     */
    public static String computeHash(String query, String activeModule, String contextType, String contextId) {
        String nQuery = normalizeNfc(query);
        String nModule = normalizeLowerNfc(activeModule);
        String nType = normalizeUpperNfc(contextType);
        String nId = normalizeUpperNfc(contextId);

        StringBuilder canonical = new StringBuilder(
                nQuery.length() + nModule.length() + nType.length() + nId.length() + 32
        );
        appendField(canonical, "q", nQuery);
        appendField(canonical, "m", nModule);
        appendField(canonical, "t", nType);
        appendField(canonical, "i", nId);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 cryptographic algorithm unavailable", ex);
        }
    }

    /**
     * Collapse all sequences of Unicode whitespace characters into a single ASCII space
     * and strip leading and trailing Unicode whitespace.
     */
    public static String cleanWhitespace(String s) {
        if (s == null) {
            return "";
        }
        String collapsed = UNICODE_WHITESPACE.matcher(s).replaceAll(" ");
        return LEADING_TRAILING_UNICODE_WHITESPACE.matcher(collapsed).replaceAll("");
    }

    private static void appendField(StringBuilder sb, String tag, String val) {
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        sb.append(tag).append(':').append(bytes.length).append(':').append(val);
    }

    private static String normalizeNfc(String s) {
        return Normalizer.normalize(cleanWhitespace(s), Normalizer.Form.NFC);
    }

    private static String normalizeLowerNfc(String s) {
        String cleaned = cleanWhitespace(s);
        return Normalizer.normalize(cleaned.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }

    private static String normalizeUpperNfc(String s) {
        String cleaned = cleanWhitespace(s);
        return Normalizer.normalize(cleaned.toUpperCase(Locale.ROOT), Normalizer.Form.NFC);
    }
}
