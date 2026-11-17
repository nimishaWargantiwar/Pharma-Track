package com.pharmatrack.ai.service;

import com.pharmatrack.dto.AiSourceReferenceDto;

import java.util.List;

/**
 * Immutable container for formatted, untrusted PharmaTrack manufacturing order facts
 * and backend-generated verifiable source references.
 *
 * @param formattedFacts   compact, structured markdown/text block of verified order and batch facts
 * @param sourceReferences list of programmatic {@link AiSourceReferenceDto} references
 */
public record OrderContext(
        String formattedFacts,
        List<AiSourceReferenceDto> sourceReferences
) {
    public OrderContext {
        sourceReferences = sourceReferences != null ? List.copyOf(sourceReferences) : List.of();
    }
}
