package com.pharmatrack.entity;

/**
 * Six-stage lifecycle status of a quality or manufacturing {@link Deviation}.
 */
public enum DeviationStatus {
    OPEN,
    UNDER_INVESTIGATION,
    ROOT_CAUSE_IDENTIFIED,
    CAPA_IN_PROGRESS,
    PENDING_VERIFICATION,
    CLOSED
}

