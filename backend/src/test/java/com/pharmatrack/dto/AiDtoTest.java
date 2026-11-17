package com.pharmatrack.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI Copilot DTO Validation and Lifecycle Tests")
class AiDtoTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("AiQueryRequestDto validates mandatory non-blank query within 2000 chars")
    void aiQueryRequestDto_validatesMandatoryQuery() {
        AiQueryRequestDto validDto = new AiQueryRequestDto(
                "What is the status of batch BAT-2026-001?",
                "traceability",
                "BATCH",
                "BAT-2026-001"
        );

        Set<ConstraintViolation<AiQueryRequestDto>> violations = validator.validate(validDto);
        assertThat(violations).isEmpty();

        // Blank query must violate validation
        AiQueryRequestDto blankDto = new AiQueryRequestDto("   ");
        Set<ConstraintViolation<AiQueryRequestDto>> blankViolations = validator.validate(blankDto);
        assertThat(blankViolations).isNotEmpty();
        assertThat(blankViolations.iterator().next().getMessage()).contains("Query is mandatory");

        // Query exceeding 2000 characters must violate validation
        String oversizedQuery = "A".repeat(2001);
        AiQueryRequestDto oversizedDto = new AiQueryRequestDto(oversizedQuery);
        Set<ConstraintViolation<AiQueryRequestDto>> sizeViolations = validator.validate(oversizedDto);
        assertThat(sizeViolations).isNotEmpty();
        assertThat(sizeViolations.iterator().next().getMessage()).contains("must not exceed 2000 characters");
    }

    @Test
    @DisplayName("AiQueryRequestDto enforces size bounds on context fields")
    void aiQueryRequestDto_enforcesContextBounds() {
        AiQueryRequestDto dto = new AiQueryRequestDto("Valid query");
        dto.setActiveModule("A".repeat(51));
        dto.setContextType("B".repeat(51));
        dto.setContextId("C".repeat(101));

        Set<ConstraintViolation<AiQueryRequestDto>> violations = validator.validate(dto);
        assertThat(violations).hasSize(3);
    }

    @Test
    @DisplayName("AiSourceReferenceDto correctly stores and exposes citation attributes")
    void aiSourceReferenceDto_holdsAttributes() {
        AiSourceReferenceDto citation = new AiSourceReferenceDto(
                "BATCH",
                "BAT-2026-001",
                "Batch BAT-2026-001 Dossier",
                "traceability",
                "Paracetamol 500mg Batch completed with 98.4% yield"
        );

        assertThat(citation.getRecordType()).isEqualTo("BATCH");
        assertThat(citation.getRecordIdentifier()).isEqualTo("BAT-2026-001");
        assertThat(citation.getTitle()).isEqualTo("Batch BAT-2026-001 Dossier");
        assertThat(citation.getModule()).isEqualTo("traceability");
        assertThat(citation.getSummary()).contains("98.4% yield");
        assertThat(citation.toString()).contains("BAT-2026-001");
    }

    @Test
    @DisplayName("AiQueryResponseDto initializes with default advisory disclaimer, timestamp and empty sources")
    void aiQueryResponseDto_initializesDefaults() {
        AiQueryResponseDto response = new AiQueryResponseDto();

        assertThat(response.getDisclaimer()).isEqualTo(AiQueryResponseDto.DEFAULT_DISCLAIMER);
        assertThat(response.getTimestamp()).isNotNull();
        assertThat(response.getSources()).isNotNull().isEmpty();

        AiSourceReferenceDto source = new AiSourceReferenceDto("DEVIATION", "DEV-2026-001", "Temp Excursion", "quality");
        AiQueryResponseDto populated = new AiQueryResponseDto(
                "Batch BAT-2026-001 is completed with 98.4% yield.",
                "BATCH",
                "BAT-2026-001",
                List.of(source),
                "gpt-6-luna"
        );

        assertThat(populated.getAnswer()).contains("98.4% yield");
        assertThat(populated.getContextType()).isEqualTo("BATCH");
        assertThat(populated.getContextIdentifier()).isEqualTo("BAT-2026-001");
        assertThat(populated.getSources()).hasSize(1);
        assertThat(populated.getModel()).isEqualTo("gpt-6-luna");
        assertThat(populated.getDisclaimer()).isEqualTo(AiQueryResponseDto.DEFAULT_DISCLAIMER);
        assertThat(populated.toString()).contains("gpt-6-luna");
    }
}
