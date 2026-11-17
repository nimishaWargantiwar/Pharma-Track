package com.pharmatrack.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RequestFingerprintUtil} verifying Unicode NFC normalization,
 * whitespace collapsing, deterministic SHA-256 generation, and boundary collision resistance.
 */
class RequestFingerprintUtilTest {

    @Test
    @DisplayName("Should produce identical hash for repeated identical calls (deterministic)")
    void shouldProduceIdenticalHashForRepeatedCalls() {
        String hash1 = RequestFingerprintUtil.computeHash(
                "Yield of MO-2026-0001", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        String hash2 = RequestFingerprintUtil.computeHash(
                "Yield of MO-2026-0001", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("Should collapse ordinary whitespace, tabs, and newlines into single spaces")
    void shouldCollapseOrdinaryWhitespaceTabsAndNewlines() {
        String hash1 = RequestFingerprintUtil.computeHash(
                "Yield of MO-2026-0001", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        String hash2 = RequestFingerprintUtil.computeHash(
                "  Yield \t\n  of \r\n MO-2026-0001  \t", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("Should strip leading and trailing non-breaking spaces (\\u00A0)")
    void shouldStripLeadingAndTrailingNonBreakingSpaces() {
        String standard = RequestFingerprintUtil.computeHash(
                "Yield of MO-2026-0001", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        String nbSpace = RequestFingerprintUtil.computeHash(
                "\u00A0\u00A0Yield of MO-2026-0001\u00A0", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        assertEquals(standard, nbSpace);
    }

    @Test
    @DisplayName("Should collapse em spaces (\\u2003) and en spaces (\\u2002)")
    void shouldCollapseEmAndEnSpaces() {
        String standard = RequestFingerprintUtil.computeHash(
                "Yield of batch 1", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        String emSpace = RequestFingerprintUtil.computeHash(
                "\u2003Yield\u2002of\u2003batch\u20021\u2003", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        assertEquals(standard, emSpace);
    }

    @Test
    @DisplayName("Should produce identical hash for NFC-equivalent Unicode strings")
    void shouldProduceIdenticalHashForNfcEquivalentStrings() {
        // "café" composed (é is \u00E9) vs decomposed (e + \u0301)
        String composed = "Yield of caf\u00E9";
        String decomposed = "Yield of cafe\u0301";

        assertNotEquals(composed, decomposed); // Different raw code points

        String hash1 = RequestFingerprintUtil.computeHash(composed, "orders", "MANUFACTURING_ORDER", "MO-01");
        String hash2 = RequestFingerprintUtil.computeHash(decomposed, "orders", "MANUFACTURING_ORDER", "MO-01");
        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("Should produce different hashes when queries differ")
    void shouldProduceDifferentHashesWhenQueriesDiffer() {
        String hash1 = RequestFingerprintUtil.computeHash(
                "What is the status of batch 1?", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        String hash2 = RequestFingerprintUtil.computeHash(
                "What is the status of batch 2?", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        assertNotEquals(hash1, hash2);
    }

    @Test
    @DisplayName("Should produce different hashes when operational context differs")
    void shouldProduceDifferentHashesWhenContextDiffers() {
        String hash1 = RequestFingerprintUtil.computeHash(
                "Check batch yields", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        String hash2 = RequestFingerprintUtil.computeHash(
                "Check batch yields", "orders", "MANUFACTURING_ORDER", "MO-2026-0002"
        );
        String hash3 = RequestFingerprintUtil.computeHash(
                "Check batch yields", "batch_traceability", "BATCH", "BAT-2026-0001-01"
        );
        assertNotEquals(hash1, hash2);
        assertNotEquals(hash1, hash3);
        assertNotEquals(hash2, hash3);
    }

    @Test
    @DisplayName("Should resist ambiguous delimiter collision via length-prefixed encoding")
    void shouldResistDelimiterCollisionViaLengthPrefixedEncoding() {
        // With simple delimiter concatenation (e.g. q|m|t|i), "a|b" and "c" could collide with "a" and "b|c".
        // Length-prefixed encoding makes boundaries strictly unambiguous.
        String hash1 = RequestFingerprintUtil.computeHash("part1:extra", "mod", "TYPE", "ID");
        String hash2 = RequestFingerprintUtil.computeHash("part1", "extra:mod", "TYPE", "ID");
        assertNotEquals(hash1, hash2);
    }

    @Test
    @DisplayName("Should normalize case on module and context types while preserving query case")
    void shouldNormalizeCaseOnModuleAndContextTypes() {
        String hash1 = RequestFingerprintUtil.computeHash(
                "Yield of MO-2026-0001", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        String hash2 = RequestFingerprintUtil.computeHash(
                "Yield of MO-2026-0001", "ORDERS", "manufacturing_order", "mo-2026-0001"
        );
        assertEquals(hash1, hash2);

        // Case difference in inquiry query must produce different hash
        String hashDifferentCaseQuery = RequestFingerprintUtil.computeHash(
                "YIELD OF MO-2026-0001", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        assertNotEquals(hash1, hashDifferentCaseQuery);
    }
}
