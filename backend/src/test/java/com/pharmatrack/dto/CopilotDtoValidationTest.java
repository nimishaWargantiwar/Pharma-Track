package com.pharmatrack.dto;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.util.AppTime;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation and security tests for AI Copilot persistence DTOs.
 */
class CopilotDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Should accept valid CopilotSubmissionRequestDto")
    void shouldAcceptValidSubmissionDto() {
        CopilotSubmissionRequestDto dto = new CopilotSubmissionRequestDto(
                "req-uuid-12345",
                "What is the actual yield of batch 1 in MO-2026-0001?",
                "orders",
                "MANUFACTURING_ORDER",
                "MO-2026-0001"
        );
        Set<ConstraintViolation<CopilotSubmissionRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Expected zero validation violations for valid DTO");
    }

    @Test
    @DisplayName("Should reject blank or missing clientRequestId")
    void shouldRejectBlankOrMissingClientRequestId() {
        CopilotSubmissionRequestDto nullReqId = new CopilotSubmissionRequestDto(null, "Valid query");
        Set<ConstraintViolation<CopilotSubmissionRequestDto>> violations = validator.validate(nullReqId);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("clientRequestId")));

        CopilotSubmissionRequestDto blankReqId = new CopilotSubmissionRequestDto("   ", "Valid query");
        violations = validator.validate(blankReqId);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("clientRequestId")));
    }

    @Test
    @DisplayName("Should reject blank or missing query")
    void shouldRejectBlankOrMissingQuery() {
        CopilotSubmissionRequestDto nullQuery = new CopilotSubmissionRequestDto("req-123", null);
        Set<ConstraintViolation<CopilotSubmissionRequestDto>> violations = validator.validate(nullQuery);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("query")));

        CopilotSubmissionRequestDto blankQuery = new CopilotSubmissionRequestDto("req-123", "\t  \n ");
        violations = validator.validate(blankQuery);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("query")));
    }

    @Test
    @DisplayName("Should reject oversized fields")
    void shouldRejectOversizedFields() {
        // Query max 2000 chars
        String oversizedQuery = "A".repeat(2001);
        CopilotSubmissionRequestDto dto = new CopilotSubmissionRequestDto("req-123", oversizedQuery);
        Set<ConstraintViolation<CopilotSubmissionRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("query")));

        // ClientRequestId max 64 chars
        String oversizedReqId = "R".repeat(65);
        dto = new CopilotSubmissionRequestDto(oversizedReqId, "Valid query");
        violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("clientRequestId")));

        // Module max 50 chars
        dto = new CopilotSubmissionRequestDto("req-123", "Valid query", "M".repeat(51), "TYPE", "ID");
        violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("activeModule")));
    }

    @Test
    @DisplayName("Should not expose or accept trusted identity fields on CopilotSubmissionRequestDto")
    void shouldNotExposeOrAcceptTrustedIdentityFields() {
        Field[] fields = CopilotSubmissionRequestDto.class.getDeclaredFields();
        for (Field field : fields) {
            String name = field.getName().toLowerCase();
            assertFalse(name.contains("userid"), "DTO must not declare userId: " + field.getName());
            assertFalse(name.contains("useraccount"), "DTO must not declare userAccount: " + field.getName());
            assertFalse(name.contains("role"), "DTO must not declare role: " + field.getName());
            assertFalse(name.contains("authority"), "DTO must not declare authority: " + field.getName());
            assertFalse(name.contains("jwt"), "DTO must not declare jwt: " + field.getName());
        }
    }

    @Test
    @DisplayName("Should validate CopilotConversationCreateRequestDto constraints")
    void shouldValidateConversationCreateRequestDto() {
        // Valid
        CopilotConversationCreateRequestDto valid = new CopilotConversationCreateRequestDto(
                "Valid Title", "orders", "MANUFACTURING_ORDER", "MO-2026-0001"
        );
        assertTrue(validator.validate(valid).isEmpty());

        // Blank title
        CopilotConversationCreateRequestDto blank = new CopilotConversationCreateRequestDto("   ");
        Set<ConstraintViolation<CopilotConversationCreateRequestDto>> violations = validator.validate(blank);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("title")));

        // Oversized title (>150 chars)
        CopilotConversationCreateRequestDto oversized = new CopilotConversationCreateRequestDto("T".repeat(151));
        violations = validator.validate(oversized);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("title")));
    }

    @Test
    @DisplayName("Should validate CopilotConversationRenameRequestDto constraints")
    void shouldValidateConversationRenameRequestDto() {
        // Valid
        CopilotConversationRenameRequestDto valid = new CopilotConversationRenameRequestDto("Renamed Title");
        assertTrue(validator.validate(valid).isEmpty());

        // Blank title
        CopilotConversationRenameRequestDto blank = new CopilotConversationRenameRequestDto("  \t ");
        Set<ConstraintViolation<CopilotConversationRenameRequestDto>> violations = validator.validate(blank);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("title")));

        // Oversized title (>150 chars)
        CopilotConversationRenameRequestDto oversized = new CopilotConversationRenameRequestDto("R".repeat(151));
        violations = validator.validate(oversized);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("title")));
    }

    @Test
    @DisplayName("Should reject unexpected JSON properties via @JsonAnySetter on all Copilot request DTOs")
    void shouldRejectUnexpectedJsonPropertiesViaJsonAnySetter() {
        ObjectMapper mapper = AppTime.createObjectMapper();

        // 1. CopilotSubmissionRequestDto
        String jsonSubmission = "{\"clientRequestId\":\"req-1\",\"query\":\"Valid\",\"userId\":999}";
        assertThrows(JsonMappingException.class, () ->
                mapper.readValue(jsonSubmission, CopilotSubmissionRequestDto.class)
        );

        // 2. CopilotConversationCreateRequestDto
        String jsonCreate = "{\"title\":\"Valid Title\",\"role\":\"ADMIN\"}";
        assertThrows(JsonMappingException.class, () ->
                mapper.readValue(jsonCreate, CopilotConversationCreateRequestDto.class)
        );

        // 3. CopilotConversationRenameRequestDto
        String jsonRename = "{\"title\":\"New Title\",\"actorId\":\"malicious\"}";
        assertThrows(JsonMappingException.class, () ->
                mapper.readValue(jsonRename, CopilotConversationRenameRequestDto.class)
        );
    }
}
