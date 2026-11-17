package com.pharmatrack.ai.entity;

/**
 * Authoritative lifecycle-aware classification for batch records and documentation.
 *
 * <p>Prevents premature or inaccurate documentation gap findings by explicitly
 * distinguishing between records that are genuinely absent and those that are
 * not yet expected, not applicable, or verified present.</p>
 */
public enum RecordLifecycleClassification {
    /**
     * Record exists and is verified in authoritative MES database records.
     */
    PRESENT,

    /**
     * Record is expected for the current batch lifecycle stage but is absent from authoritative records.
     */
    GENUINELY_MISSING,

    /**
     * Record is not expected at the current manufacturing lifecycle stage (e.g. yield for a PLANNED batch).
     */
    NOT_YET_EXPECTED,

    /**
     * Record does not apply to this batch or process recipe configuration.
     */
    NOT_APPLICABLE,

    /**
     * Authoritative records or master configuration are inconclusive or absent,
     * preventing deterministic verification of requirement applicability.
     */
    UNKNOWN
}
