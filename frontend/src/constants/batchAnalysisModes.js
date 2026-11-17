/**
 * Batch Analysis Modes and Focus Areas configuration.
 *
 * <p>Supports FULL (comprehensive 12-section analysis) and FOCUSED
 * (targeted analysis of specific operational and compliance domains) modes.</p>
 */

export const BATCH_ANALYSIS_MODES = {
  FULL: 'FULL',
  FOCUSED: 'FOCUSED',
};

export const FOCUS_AREA_CATEGORIES = [
  {
    id: 'manufacturing',
    title: '1. Manufacturing & Yield Performance',
    description: 'Production yield metrics and material reconciliation',
    areaKeys: ['MANUFACTURING_YIELD', 'MATERIAL_TRACEABILITY'],
  },
  {
    id: 'execution',
    title: '2. Execution Integrity & Materials',
    description: 'eBR execution steps, QC lab testing, and QA batch release status',
    areaKeys: ['EBR_EXECUTION', 'QC_INSPECTION', 'QA_DISPOSITION'],
  },
  {
    id: 'quality',
    title: '3. Quality & Traceability',
    description: 'Deviations, CAPA, audit trail chronology, and record omissions',
    areaKeys: ['DEVIATIONS_CAPA', 'CHRONOLOGICAL_AUDIT', 'MISSING_RECORDS'],
  },
  {
    id: 'risk',
    title: '4. Risk Assessment & Recommendations',
    description: 'Risk severity scoring and operational follow-up recommendations',
    areaKeys: ['RISK_ASSESSMENT'],
  },
];

export const FOCUS_AREAS = [
  {
    key: 'MANUFACTURING_YIELD',
    label: 'Manufacturing & Yield',
    category: 'manufacturing',
    description: 'Evaluates planned vs actual production yield, variance, and batch size reconciliation.',
  },
  {
    key: 'MATERIAL_TRACEABILITY',
    label: 'Material Traceability',
    category: 'manufacturing',
    description: 'Analyzes bill-of-materials dispensing, material lot genealogy, and component usage.',
  },
  {
    key: 'EBR_EXECUTION',
    label: 'eBR Execution',
    category: 'execution',
    description: 'Audits electronic batch record step completion, timestamps, and operator signatures.',
  },
  {
    key: 'QC_INSPECTION',
    label: 'QC Inspection',
    category: 'execution',
    description: 'Evaluates in-process and finished quality control check specifications and test results.',
  },
  {
    key: 'QA_DISPOSITION',
    label: 'QA Disposition',
    category: 'execution',
    description: 'Audits authoritative quality assurance release gates, review history, and disposition.',
  },
  {
    key: 'DEVIATIONS_CAPA',
    label: 'Deviations & CAPA',
    category: 'quality',
    description: 'Reviews non-conformances, incident investigations, root causes, and corrective actions.',
  },
  {
    key: 'CHRONOLOGICAL_AUDIT',
    label: 'Chronological Audit',
    category: 'quality',
    description: 'Examines sequential audit trail log history, timestamps, and actor attributions.',
  },
  {
    key: 'MISSING_RECORDS',
    label: 'Missing Records',
    category: 'quality',
    description: 'Detects execution gaps, skipped steps, missing quality checks, and unrecorded data.',
  },
  {
    key: 'RISK_ASSESSMENT',
    label: 'Risk Assessment',
    category: 'risk',
    description: 'Assesses product quality risk severity, GMP compliance hazards, and mitigation priorities.',
  },
];

export const ALL_FOCUS_AREA_KEYS = FOCUS_AREAS.map((fa) => fa.key);
