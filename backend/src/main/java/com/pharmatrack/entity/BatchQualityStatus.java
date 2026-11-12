package com.pharmatrack.entity;

/**
 * Independent Quality Assurance (QA) disposition lifecycle status for a completed batch.
 * Kept strictly separate from manufacturing {@link BatchStatus}.
 */
public enum BatchQualityStatus {
    PENDING_QA,
    ON_HOLD,
    RELEASED,
    REJECTED
}

