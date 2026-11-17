package com.pharmatrack.ai.entity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Authoritative lifecycle record types mapped explicitly to their corresponding focus areas.
 *
 * <p>Provides a stable, non-fragile mapping between deterministic GxP record assessments
 * and the {@link BatchAnalysisFocusArea} domains they belong to.</p>
 */
public enum LifecycleRecordType {

    MANUFACTURING_START_TIMESTAMP(
            "Manufacturing Start Timestamp",
            BatchAnalysisFocusArea.MANUFACTURING_YIELD,
            Set.of("manufacturing start timestamp", "manufacturing start time", "start timestamp")
    ),
    MANUFACTURING_COMPLETION_TIMESTAMP(
            "Manufacturing Completion Timestamp",
            BatchAnalysisFocusArea.MANUFACTURING_YIELD,
            Set.of("manufacturing completion timestamp", "manufacturing completion timestamp (end time)", "completion timestamp", "end timestamp")
    ),
    ACTUAL_YIELD(
            "Actual Yield Quantity",
            BatchAnalysisFocusArea.MANUFACTURING_YIELD,
            Set.of("actual yield quantity", "actual yield record", "actual yield", "yield record")
    ),
    MATERIAL_TRACEABILITY(
            "Material Issue & Lot Traceability",
            BatchAnalysisFocusArea.MATERIAL_TRACEABILITY,
            Set.of("material issue & lot traceability", "material traceability", "material traceability (issue / consumption)", "material issue and lot traceability")
    ),
    EBR_EXECUTION(
            "eBR Execution Steps",
            BatchAnalysisFocusArea.EBR_EXECUTION,
            Set.of("ebr execution steps", "electronic batch record (ebr) execution steps", "electronic batch record execution steps", "ebr steps")
    ),
    QC_INSPECTIONS(
            "Quality Control (QC) Inspections",
            BatchAnalysisFocusArea.QC_INSPECTION,
            Set.of("quality control (qc) inspections", "qc inspections", "quality control inspections", "qc inspection")
    ),
    DEVIATIONS_CAPA(
            "Deviations & CAPA",
            BatchAnalysisFocusArea.DEVIATIONS_CAPA,
            Set.of("deviations & capa", "deviations & capa records", "deviations and capa", "deviations and capa records")
    ),
    QA_DISPOSITION(
            "QA Disposition Review",
            BatchAnalysisFocusArea.QA_DISPOSITION,
            Set.of("qa disposition review", "qa batch release disposition", "qa disposition", "quality assurance disposition", "quality assurance (qa) batch release")
    );

    private final String canonicalName;
    private final BatchAnalysisFocusArea focusArea;
    private final Set<String> aliases;

    private static final Map<String, LifecycleRecordType> LOOKUP;

    static {
        Map<String, LifecycleRecordType> map = new HashMap<>();
        for (LifecycleRecordType type : values()) {
            map.put(type.canonicalName.toLowerCase(Locale.ROOT), type);
            for (String alias : type.aliases) {
                map.put(alias.toLowerCase(Locale.ROOT), type);
            }
        }
        LOOKUP = Collections.unmodifiableMap(map);
    }

    LifecycleRecordType(String canonicalName, BatchAnalysisFocusArea focusArea, Set<String> aliases) {
        this.canonicalName = canonicalName;
        this.focusArea = focusArea;
        this.aliases = aliases;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public BatchAnalysisFocusArea getFocusArea() {
        return focusArea;
    }

    public Set<String> getAliases() {
        return aliases;
    }

    /**
     * Resolves a recordType string to its canonical {@link LifecycleRecordType}.
     * Fails closed: returns {@code null} if the recordType is null, blank, or unrecognized.
     */
    public static LifecycleRecordType fromRecordType(String recordType) {
        if (recordType == null || recordType.isBlank()) {
            return null;
        }
        return LOOKUP.get(recordType.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves a recordType string to its associated {@link BatchAnalysisFocusArea}.
     * Fails closed: returns {@code null} if unrecognized.
     */
    public static BatchAnalysisFocusArea mapRecordTypeToFocusArea(String recordType) {
        LifecycleRecordType type = fromRecordType(recordType);
        return type != null ? type.getFocusArea() : null;
    }
}
