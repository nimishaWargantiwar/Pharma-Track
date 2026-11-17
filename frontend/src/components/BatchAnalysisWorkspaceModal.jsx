import React from 'react';
import BatchAnalysisWorkspace, {
  SECTION_GROUPS,
  SECTION_DEFINITIONS,
  HISTORICAL_COMPLIANCE_ADVISORY,
  hasUnsupportedComplianceClaim,
  formatSectionContentForDisplay,
  formatNarrativeMarkdownForDisplay,
} from './BatchAnalysisWorkspace.jsx';

export {
  SECTION_GROUPS,
  SECTION_DEFINITIONS,
  HISTORICAL_COMPLIANCE_ADVISORY,
  hasUnsupportedComplianceClaim,
  formatSectionContentForDisplay,
  formatNarrativeMarkdownForDisplay,
};

/**
 * Dedicated AI Batch Analysis Workspace Modal (or Full View container).
 * Preserves 100% backward compatibility with existing tests and component usages.
 */
export default function BatchAnalysisWorkspaceModal({
  isOpen = true,
  onClose,
  batchId,
  batchNumber,
  dossier = null,
  role = null,
}) {
  if (!isOpen) {
    return null;
  }

  return (
    <div data-testid="batch-analysis-workspace-modal" className="w-full">
      <BatchAnalysisWorkspace
        batchId={batchId}
        batchNumber={batchNumber}
        dossier={dossier}
        role={role}
        onClose={onClose}
      />
    </div>
  );
}
