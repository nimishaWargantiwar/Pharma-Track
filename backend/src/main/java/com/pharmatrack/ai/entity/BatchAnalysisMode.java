package com.pharmatrack.ai.entity;

/**
 * Analysis mode for AI Batch Analysis Reports.
 *
 * <p>Determines whether a report executes an exhaustive 12-section investigation
 * across all manufacturing aspects ({@link #FULL}) or a targeted, modular deep dive
 * focused on specific operational risk areas ({@link #FOCUSED}).</p>
 */
public enum BatchAnalysisMode {
    /**
     * Standard comprehensive 12-section batch analysis report.
     */
    FULL,

    /**
     * Targeted analysis focusing on one or more selected manufacturing or quality domains.
     */
    FOCUSED
}
