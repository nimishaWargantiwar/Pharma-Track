package com.pharmatrack.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.dto.BatchAnalysisReportRequestDto;
import com.pharmatrack.ai.dto.BatchAnalysisReportResponseDto;
import com.pharmatrack.ai.dto.BatchAnalysisReportSummaryDto;
import com.pharmatrack.ai.entity.BatchAnalysisFocusArea;
import com.pharmatrack.ai.entity.BatchAnalysisMode;
import com.pharmatrack.ai.entity.BatchAnalysisReport;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.UserAccount;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 4F.1 - Domain Models, Enums, DTOs, and Validation")
class BatchAnalysisDomainTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("BatchAnalysisMode contains exactly FULL and FOCUSED")
    void testBatchAnalysisModeEnumValues() {
        assertThat(BatchAnalysisMode.values())
                .containsExactly(BatchAnalysisMode.FULL, BatchAnalysisMode.FOCUSED);
    }

    @Test
    @DisplayName("BatchAnalysisFocusArea contains all 9 required areas with valid display names")
    void testBatchAnalysisFocusAreaEnumValues() {
        assertThat(BatchAnalysisFocusArea.values()).containsExactly(
                BatchAnalysisFocusArea.MANUFACTURING_YIELD,
                BatchAnalysisFocusArea.MATERIAL_TRACEABILITY,
                BatchAnalysisFocusArea.EBR_EXECUTION,
                BatchAnalysisFocusArea.QC_INSPECTION,
                BatchAnalysisFocusArea.QA_DISPOSITION,
                BatchAnalysisFocusArea.DEVIATIONS_CAPA,
                BatchAnalysisFocusArea.CHRONOLOGICAL_AUDIT,
                BatchAnalysisFocusArea.MISSING_RECORDS,
                BatchAnalysisFocusArea.RISK_ASSESSMENT
        );

        for (BatchAnalysisFocusArea area : BatchAnalysisFocusArea.values()) {
            assertThat(area.getDisplayName()).isNotBlank();
        }
    }

    @Test
    @DisplayName("BatchAnalysisFocusArea JSON serialization and deserialization")
    void testFocusAreaJsonRoundTrip() {
        List<BatchAnalysisFocusArea> areas = List.of(
                BatchAnalysisFocusArea.MANUFACTURING_YIELD,
                BatchAnalysisFocusArea.QC_INSPECTION,
                BatchAnalysisFocusArea.DEVIATIONS_CAPA
        );

        String json = BatchAnalysisFocusArea.toJson(areas);
        assertThat(json).contains("MANUFACTURING_YIELD", "QC_INSPECTION", "DEVIATIONS_CAPA");

        List<BatchAnalysisFocusArea> deserialized = BatchAnalysisFocusArea.fromJson(json);
        assertThat(deserialized).isEqualTo(areas);

        assertThat(BatchAnalysisFocusArea.toJson(null)).isNull();
        assertThat(BatchAnalysisFocusArea.toJson(List.of())).isNull();
        assertThat(BatchAnalysisFocusArea.fromJson(null)).isEmpty();
        assertThat(BatchAnalysisFocusArea.fromJson("")).isEmpty();
        assertThat(BatchAnalysisFocusArea.fromJson("invalid-json")).isEmpty();
    }

    @Test
    @DisplayName("BatchAnalysisFocusArea summary formatting and length safety")
    void testFocusAreaSummary() {
        List<BatchAnalysisFocusArea> areas = List.of(
                BatchAnalysisFocusArea.MANUFACTURING_YIELD,
                BatchAnalysisFocusArea.MATERIAL_TRACEABILITY
        );
        String summary = BatchAnalysisFocusArea.toSummary(areas);
        assertThat(summary).isEqualTo("Manufacturing Yield, Material Traceability");

        assertThat(BatchAnalysisFocusArea.toSummary(null)).isNull();
        assertThat(BatchAnalysisFocusArea.toSummary(List.of())).isNull();

        // Check long list truncation safety (max 255 chars)
        List<BatchAnalysisFocusArea> allAreas = List.of(BatchAnalysisFocusArea.values());
        String allSummary = BatchAnalysisFocusArea.toSummary(allAreas);
        assertThat(allSummary.length()).isLessThanOrEqualTo(255);
    }

    @Test
    @DisplayName("BatchAnalysisReportRequestDto defaults to FULL mode when unspecified")
    void testRequestDtoDefaultsToFullMode() throws Exception {
        String json = "{\"clientRequestId\": \"REQ-12345678\"}";
        BatchAnalysisReportRequestDto dto = objectMapper.readValue(json, BatchAnalysisReportRequestDto.class);

        assertThat(dto.getAnalysisMode()).isEqualTo(BatchAnalysisMode.FULL);
        assertThat(dto.getFocusAreas()).isEmpty();
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("BatchAnalysisReportRequestDto supports 'mode' alias for 'analysisMode'")
    void testRequestDtoModeAlias() throws Exception {
        String json = "{\"clientRequestId\": \"REQ-12345678\", \"mode\": \"FULL\"}";
        BatchAnalysisReportRequestDto dto = objectMapper.readValue(json, BatchAnalysisReportRequestDto.class);

        assertThat(dto.getAnalysisMode()).isEqualTo(BatchAnalysisMode.FULL);
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("BatchAnalysisReportRequestDto accepts valid FOCUSED mode with focus areas")
    void testRequestDtoFocusedModeValid() throws Exception {
        String json = """
                {
                  "clientRequestId": "REQ-12345678",
                  "analysisMode": "FOCUSED",
                  "focusAreas": ["MANUFACTURING_YIELD", "QC_INSPECTION"]
                }
                """;
        BatchAnalysisReportRequestDto dto = objectMapper.readValue(json, BatchAnalysisReportRequestDto.class);

        assertThat(dto.getAnalysisMode()).isEqualTo(BatchAnalysisMode.FOCUSED);
        assertThat(dto.getFocusAreas()).containsExactly(
                BatchAnalysisFocusArea.MANUFACTURING_YIELD,
                BatchAnalysisFocusArea.QC_INSPECTION
        );
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("BatchAnalysisReportRequestDto rejects FOCUSED mode with empty focus areas")
    void testRequestDtoFocusedModeEmptyAreas() throws Exception {
        String json = """
                {
                  "clientRequestId": "REQ-12345678",
                  "analysisMode": "FOCUSED",
                  "focusAreas": []
                }
                """;
        BatchAnalysisReportRequestDto dto = objectMapper.readValue(json, BatchAnalysisReportRequestDto.class);

        var violations = validator.validate(dto);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("At least one focus area is required"));
    }

    @Test
    @DisplayName("BatchAnalysisReportRequestDto rejects FULL mode with focus areas")
    void testRequestDtoFullModeWithAreas() throws Exception {
        String json = """
                {
                  "clientRequestId": "REQ-12345678",
                  "analysisMode": "FULL",
                  "focusAreas": ["MANUFACTURING_YIELD"]
                }
                """;
        BatchAnalysisReportRequestDto dto = objectMapper.readValue(json, BatchAnalysisReportRequestDto.class);

        var violations = validator.validate(dto);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Focus areas are not permitted for FULL"));
    }

    @Test
    @DisplayName("BatchAnalysisReportRequestDto rejects duplicate focus areas")
    void testRequestDtoDuplicateAreas() throws Exception {
        String json = """
                {
                  "clientRequestId": "REQ-12345678",
                  "analysisMode": "FOCUSED",
                  "focusAreas": ["MANUFACTURING_YIELD", "MANUFACTURING_YIELD"]
                }
                """;
        BatchAnalysisReportRequestDto dto = objectMapper.readValue(json, BatchAnalysisReportRequestDto.class);

        var violations = validator.validate(dto);
        assertThat(violations).anyMatch(v -> v.getMessage().contains("Duplicate focus areas are not permitted"));
    }

    @Test
    @DisplayName("BatchAnalysisReportRequestDto rejects unknown fields")
    void testRequestDtoUnknownFieldsRejected() {
        String json = """
                {
                  "clientRequestId": "REQ-12345678",
                  "unexpectedProperty": "malicious"
                }
                """;
        assertThatThrownBy(() -> objectMapper.readValue(json, BatchAnalysisReportRequestDto.class))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized field: unexpectedProperty");
    }

    @Test
    @DisplayName("BatchAnalysisReport entity defaults to FULL mode and persists focus areas")
    void testEntityModeAndFocusAreas() {
        Batch batch = new Batch();
        batch.setId(10L);
        batch.setBatchNumber("BAT-TEST-001");
        UserAccount user = new UserAccount();
        user.setId(5L);

        BatchAnalysisReport report = new BatchAnalysisReport(batch, user, "REQ-ENTITY-01");
        assertThat(report.getAnalysisMode()).isEqualTo(BatchAnalysisMode.FULL);
        assertThat(report.getFocusAreas()).isEmpty();
        assertThat(report.getFocusSummary()).isNull();

        report.setAnalysisMode(BatchAnalysisMode.FOCUSED);
        report.setFocusAreas(List.of(BatchAnalysisFocusArea.QC_INSPECTION, BatchAnalysisFocusArea.DEVIATIONS_CAPA));

        assertThat(report.getAnalysisMode()).isEqualTo(BatchAnalysisMode.FOCUSED);
        assertThat(report.getFocusAreas()).containsExactly(
                BatchAnalysisFocusArea.QC_INSPECTION,
                BatchAnalysisFocusArea.DEVIATIONS_CAPA
        );
        assertThat(report.getFocusSummary()).isEqualTo("QC Inspection, Deviations & CAPA");
        assertThat(report.getFocusAreasJson()).contains("QC_INSPECTION", "DEVIATIONS_CAPA");
    }

    @Test
    @DisplayName("BatchAnalysisReportResponseDto and SummaryDto preserve mode and focus areas")
    void testResponseAndSummaryDtos() {
        BatchAnalysisReportResponseDto responseDto = new BatchAnalysisReportResponseDto();
        responseDto.setAnalysisMode(BatchAnalysisMode.FOCUSED);
        responseDto.setFocusAreas(List.of(BatchAnalysisFocusArea.EBR_EXECUTION));
        responseDto.setFocusSummary("eBR Execution");

        assertThat(responseDto.getAnalysisMode()).isEqualTo(BatchAnalysisMode.FOCUSED);
        assertThat(responseDto.getFocusAreas()).containsExactly(BatchAnalysisFocusArea.EBR_EXECUTION);
        assertThat(responseDto.getFocusSummary()).isEqualTo("eBR Execution");

        BatchAnalysisReportSummaryDto summaryDto = new BatchAnalysisReportSummaryDto();
        summaryDto.setAnalysisMode(BatchAnalysisMode.FOCUSED);
        summaryDto.setFocusAreas(List.of(BatchAnalysisFocusArea.EBR_EXECUTION));
        summaryDto.setFocusSummary("eBR Execution");

        assertThat(summaryDto.getAnalysisMode()).isEqualTo(BatchAnalysisMode.FOCUSED);
        assertThat(summaryDto.getFocusAreas()).containsExactly(BatchAnalysisFocusArea.EBR_EXECUTION);
        assertThat(summaryDto.getFocusSummary()).isEqualTo("eBR Execution");
    }
}
