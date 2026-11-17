package com.pharmatrack.ai.entity;

/**
 * Lifecycle state for persistent AI Batch Analysis Reports.
 */
public enum BatchAnalysisReportStatus {
    /**
     * Report generation is in progress.
     */
    GENERATING,

    /**
     * Report generation completed successfully with structured analysis and rendered markdown.
     */
    COMPLETED,

    /**
     * Report generation failed due to timeout, external service unavailability, or processing error.
     */
    FAILED
}
