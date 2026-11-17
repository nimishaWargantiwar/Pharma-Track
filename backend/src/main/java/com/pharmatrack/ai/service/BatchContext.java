package com.pharmatrack.ai.service;

import com.pharmatrack.dto.AiSourceReferenceDto;

import java.util.List;

/**
 * Immutable container for formatted, verified PharmaTrack batch analysis facts
 * and backend-generated verifiable source references.
 *
 * @param formattedFacts   compact, structured text block of verified batch records and calculations
 * @param sourceReferences list of programmatic {@link AiSourceReferenceDto} references
 */
public record BatchContext(
        String formattedFacts,
        List<AiSourceReferenceDto> sourceReferences
) {
    public BatchContext {
        sourceReferences = sourceReferences != null ? List.copyOf(sourceReferences) : List.of();
    }
}
