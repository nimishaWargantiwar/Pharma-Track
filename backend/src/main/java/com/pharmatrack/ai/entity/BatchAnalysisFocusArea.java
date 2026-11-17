package com.pharmatrack.ai.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Domain focus areas for targeted AI Batch Analysis Reports.
 *
 * <p>Used in {@link BatchAnalysisMode#FOCUSED} mode to direct the analytical scope
 * to specific manufacturing, quality, or compliance topics.</p>
 */
public enum BatchAnalysisFocusArea {
    MANUFACTURING_YIELD("Manufacturing Yield"),
    MATERIAL_TRACEABILITY("Material Traceability"),
    EBR_EXECUTION("eBR Execution"),
    QC_INSPECTION("QC Inspection"),
    QA_DISPOSITION("QA Disposition"),
    DEVIATIONS_CAPA("Deviations & CAPA"),
    CHRONOLOGICAL_AUDIT("Chronological Audit Trail"),
    MISSING_RECORDS("Missing Records & Gaps"),
    RISK_ASSESSMENT("Risk Assessment");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String displayName;

    BatchAnalysisFocusArea(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Serializes a list of focus areas into a JSON array string for database persistence.
     *
     * @param areas list of focus areas
     * @return JSON string or null if empty
     */
    public static String toJson(List<BatchAnalysisFocusArea> areas) {
        if (areas == null || areas.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(areas);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize focus areas to JSON", e);
        }
    }

    /**
     * Deserializes a JSON array string back into a list of focus areas.
     *
     * @param json JSON array string
     * @return list of focus areas, never null
     */
    public static List<BatchAnalysisFocusArea> fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<BatchAnalysisFocusArea>>() {});
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Formats a concise human-readable summary of the focus areas.
     *
     * @param areas list of focus areas
     * @return human-readable summary (max 255 chars) or null if empty
     */
    public static String toSummary(List<BatchAnalysisFocusArea> areas) {
        if (areas == null || areas.isEmpty()) {
            return null;
        }
        String summary = areas.stream()
                .map(BatchAnalysisFocusArea::getDisplayName)
                .collect(Collectors.joining(", "));
        if (summary.length() > 255) {
            return summary.substring(0, 252) + "...";
        }
        return summary;
    }
}
