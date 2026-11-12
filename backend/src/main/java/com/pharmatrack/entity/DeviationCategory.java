package com.pharmatrack.entity;

/**
 * Classification category describing the source or domain of a {@link Deviation}.
 */
public enum DeviationCategory {
    QC_OOS_FAILURE,
    PROCESS_PARAMETER,
    IN_PROCESS_CONTROL,
    EQUIPMENT_MALFUNCTION,
    MATERIAL_ISSUE,
    DOCUMENTATION_ERROR,
    ENVIRONMENTAL_EXCURSION,
    OTHER
}

