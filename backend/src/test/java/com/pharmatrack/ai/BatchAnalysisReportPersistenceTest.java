package com.pharmatrack.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.dto.BatchAnalysisMetricsDto;
import com.pharmatrack.ai.dto.BatchAnalysisSectionsDto;
import com.pharmatrack.ai.entity.BatchAnalysisReport;
import com.pharmatrack.ai.entity.BatchAnalysisReportStatus;
import com.pharmatrack.ai.repository.BatchAnalysisReportRepository;
import com.pharmatrack.dto.AiSourceReferenceDto;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Persistence integration tests for {@link BatchAnalysisReport} entity and
 * {@link BatchAnalysisReportRepository} using isolated in-memory H2.
 */
@DataJpaTest
class BatchAnalysisReportPersistenceTest {

    @Autowired
    private BatchAnalysisReportRepository reportRepository;

    @Autowired
    private com.pharmatrack.repository.BatchRepository batchRepository;

    @Autowired
    private TestEntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UserAccount testSupervisor;
    private Batch testBatch;

    @BeforeEach
    void setUp() {
        testSupervisor = new UserAccount(
                "supervisor_persistence_test",
                "$2a$12$dummyHashForTestingPurposesOnly12345678901234567890",
                "Jane Doe Supervisor",
                Role.PRODUCTION_SUPERVISOR,
                true
        );
        entityManager.persistAndFlush(testSupervisor);

        Product product = new Product(
                "AMOX-500",
                "Amoxicillin 500mg Trihydrate",
                "500 mg",
                "Capsule",
                "Antibiotic formulation",
                ProductStatus.ACTIVE
        );
        entityManager.persistAndFlush(product);

        Recipe recipe = new Recipe(
                "RCP-AMOX-500-V1",
                product,
                1,
                new BigDecimal("500.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Standard approved recipe"
        );
        entityManager.persistAndFlush(recipe);

        ManufacturingOrder order = new ManufacturingOrder(
                "MO-2026-9001",
                product,
                recipe,
                1,
                new BigDecimal("500.0000"),
                "kg",
                1,
                OrderStatus.IN_PROGRESS,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 10),
                "Production order for report persistence test"
        );
        entityManager.persistAndFlush(order);

        testBatch = new Batch(
                "BAT-2026-9001-01",
                order,
                new BigDecimal("500.0000"),
                "kg",
                BatchStatus.IN_PROGRESS,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2028, 3, 1)
        );
        entityManager.persistAndFlush(testBatch);
    }

    @Test
    @DisplayName("Should save a report in GENERATING state and retrieve with correct mappings")
    void shouldPersistAndRetrieveInGeneratingState() {
        String reqId = UUID.randomUUID().toString();
        BatchAnalysisReport report = new BatchAnalysisReport(testBatch, testSupervisor, reqId);

        BatchAnalysisReport saved = reportRepository.saveAndFlush(report);
        assertNotNull(saved.getId(), "Report ID must be generated");
        assertEquals(testBatch.getId(), saved.getBatch().getId());
        assertEquals(testBatch.getBatchNumber(), saved.getBatchNumber());
        assertEquals(testSupervisor.getId(), saved.getGeneratedByUser().getId());
        assertEquals(reqId, saved.getClientRequestId());
        assertEquals(BatchAnalysisReportStatus.GENERATING, saved.getStatus());
        assertNotNull(saved.getCreatedAt());
        assertNull(saved.getCompletedAt());

        entityManager.clear();

        Optional<BatchAnalysisReport> retrieved = reportRepository.findById(saved.getId());
        assertTrue(retrieved.isPresent());
        assertEquals(BatchAnalysisReportStatus.GENERATING, retrieved.get().getStatus());
        assertEquals("BAT-2026-9001-01", retrieved.get().getBatchNumber());
    }

    @Test
    @DisplayName("Should update report to COMPLETED with metrics, markdown, and JSON payloads")
    void shouldUpdateReportToCompletedWithFullPayload() throws Exception {
        String reqId = UUID.randomUUID().toString();
        BatchAnalysisReport report = new BatchAnalysisReport(testBatch, testSupervisor, reqId);
        BatchAnalysisReport saved = reportRepository.saveAndFlush(report);

        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto(
                "Executive Summary: Batch BAT-2026-9001-01 produced 492.5 kg at 98.5% yield.",
                "Yield variance is -7.5 kg within acceptable tolerance.",
                "All 4 eBR execution steps completed without critical deviation.",
                "Quality in-process controls passed dissolution and assay limits.",
                "No open deviations or CAPAs recorded.",
                "No pending records detected.",
                "Recommended for standard QA review."
        );
        String structuredJson = objectMapper.writeValueAsString(sections);

        List<AiSourceReferenceDto> sources = List.of(
                new AiSourceReferenceDto("BATCH", "BAT-2026-9001-01", "Batch Record", "traceability"),
                new AiSourceReferenceDto("EXECUTION_STEP", "101", "Step 1: Dispensing", "execution")
        );
        String sourcesJson = objectMapper.writeValueAsString(sources);

        String markdown = "# AI Batch Analysis Report: BAT-2026-9001-01\n\nOperational yield 98.5%.";

        saved.complete(
                "gpt-4o-mini",
                new BigDecimal("500.0000"),
                new BigDecimal("492.5000"),
                new BigDecimal("98.5000"),
                new BigDecimal("-7.5000"),
                4,
                3,
                0,
                structuredJson,
                markdown,
                sourcesJson
        );

        reportRepository.saveAndFlush(saved);
        entityManager.clear();

        Optional<BatchAnalysisReport> reloaded = reportRepository.findById(saved.getId());
        assertTrue(reloaded.isPresent());
        BatchAnalysisReport completedReport = reloaded.get();

        assertEquals(BatchAnalysisReportStatus.COMPLETED, completedReport.getStatus());
        assertEquals("gpt-4o-mini", completedReport.getModel());
        assertEquals(new BigDecimal("500.0000"), completedReport.getPlannedBatchSize());
        assertEquals(new BigDecimal("492.5000"), completedReport.getActualYield());
        assertEquals(new BigDecimal("98.5000"), completedReport.getYieldPercent());
        assertEquals(new BigDecimal("-7.5000"), completedReport.getYieldVariance());
        assertEquals(4, completedReport.getExecutionStepCount());
        assertEquals(3, completedReport.getQcCheckCount());
        assertEquals(0, completedReport.getDeviationCount());
        assertNotNull(completedReport.getCompletedAt());
        assertNull(completedReport.getErrorMessage());
        assertEquals(markdown, completedReport.getReportMarkdown());
        assertEquals(structuredJson, completedReport.getStructuredAnalysisJson());
        assertEquals(sourcesJson, completedReport.getSourcesJson());
    }

    @Test
    @DisplayName("Should save a FAILED report with error message")
    void shouldSaveFailedReportWithErrorMessage() {
        String reqId = UUID.randomUUID().toString();
        BatchAnalysisReport report = new BatchAnalysisReport(testBatch, testSupervisor, reqId);
        report.fail("LLM Gateway Timeout: External provider unreachable after 30 seconds.");

        BatchAnalysisReport saved = reportRepository.saveAndFlush(report);
        entityManager.clear();

        Optional<BatchAnalysisReport> retrieved = reportRepository.findById(saved.getId());
        assertTrue(retrieved.isPresent());
        assertEquals(BatchAnalysisReportStatus.FAILED, retrieved.get().getStatus());
        assertTrue(retrieved.get().getErrorMessage().contains("LLM Gateway Timeout"));
        assertNotNull(retrieved.get().getCompletedAt());
    }

    @Test
    @DisplayName("Should return reports in reverse chronological order (newest first)")
    void shouldReturnReportsOrderedByCreatedAtDesc() throws InterruptedException {
        String req1 = UUID.randomUUID().toString();
        BatchAnalysisReport r1 = new BatchAnalysisReport(testBatch, testSupervisor, req1);
        r1.setCreatedAt(LocalDateTime.of(2026, 3, 1, 10, 0, 0));
        reportRepository.saveAndFlush(r1);

        String req2 = UUID.randomUUID().toString();
        BatchAnalysisReport r2 = new BatchAnalysisReport(testBatch, testSupervisor, req2);
        r2.setCreatedAt(LocalDateTime.of(2026, 3, 1, 14, 30, 0));
        reportRepository.saveAndFlush(r2);

        String req3 = UUID.randomUUID().toString();
        BatchAnalysisReport r3 = new BatchAnalysisReport(testBatch, testSupervisor, req3);
        r3.setCreatedAt(LocalDateTime.of(2026, 3, 1, 12, 15, 0));
        reportRepository.saveAndFlush(r3);

        entityManager.clear();

        List<BatchAnalysisReport> reports = reportRepository.findByBatchIdOrderByCreatedAtDesc(testBatch.getId());
        assertEquals(3, reports.size());
        assertEquals(req2, reports.get(0).getClientRequestId(), "Newest (14:30) must be first");
        assertEquals(req3, reports.get(1).getClientRequestId(), "Middle (12:15) must be second");
        assertEquals(req1, reports.get(2).getClientRequestId(), "Oldest (10:00) must be third");
    }

    @Test
    @DisplayName("Should enforce uniqueness constraint on clientRequestId to prevent duplicate runs")
    void shouldRejectDuplicateClientRequestId() {
        String duplicateReqId = "REQ-IDEMPOTENCY-FIXED-KEY-001";
        BatchAnalysisReport report1 = new BatchAnalysisReport(testBatch, testSupervisor, duplicateReqId);
        reportRepository.saveAndFlush(report1);

        BatchAnalysisReport report2 = new BatchAnalysisReport(testBatch, testSupervisor, duplicateReqId);
        assertThrows(DataIntegrityViolationException.class, () -> {
            reportRepository.saveAndFlush(report2);
        }, "Duplicate clientRequestId must be rejected by unique constraint");
    }

    @Test
    @DisplayName("Should enforce foreign key integrity and prevent batch deletion when report history exists")
    void shouldPreventBatchDeletionWhenReportsExist() {
        String reqId = UUID.randomUUID().toString();
        BatchAnalysisReport report = new BatchAnalysisReport(testBatch, testSupervisor, reqId);
        reportRepository.saveAndFlush(report);
        entityManager.clear();

        // Attempting to delete the parent batch must fail because reports reference it (ON DELETE NO ACTION)
        Batch batchToDelete = batchRepository.findById(testBatch.getId()).orElseThrow();

        assertThrows(DataIntegrityViolationException.class, () -> {
            batchRepository.delete(batchToDelete);
            batchRepository.flush();
        }, "Historical batch deletion must be rejected because reports are permanently linked");
    }

    @Test
    @DisplayName("Should allow nullable KPIs for incomplete batches without database violation")
    void shouldPersistReportWithNullableKpis() {
        String reqId = UUID.randomUUID().toString();
        BatchAnalysisReport report = new BatchAnalysisReport(testBatch, testSupervisor, reqId);
        report.setPlannedBatchSize(new BigDecimal("500.0000"));
        // actualYield, yieldPercent, yieldVariance, step counts, qc counts are deliberately null
        report.setActualYield(null);
        report.setYieldPercent(null);
        report.setYieldVariance(null);
        report.setExecutionStepCount(null);
        report.setQcCheckCount(null);
        report.setDeviationCount(null);

        BatchAnalysisReport saved = reportRepository.saveAndFlush(report);
        assertNotNull(saved.getId());

        entityManager.clear();

        Optional<BatchAnalysisReport> retrieved = reportRepository.findById(saved.getId());
        assertTrue(retrieved.isPresent());
        assertNull(retrieved.get().getActualYield());
        assertNull(retrieved.get().getYieldPercent());
        assertNull(retrieved.get().getYieldVariance());
        assertNull(retrieved.get().getExecutionStepCount());
        assertNull(retrieved.get().getQcCheckCount());
        assertNull(retrieved.get().getDeviationCount());
    }

    @Test
    @DisplayName("Should serialize and deserialize structured analysis JSON and sources JSON correctly")
    void shouldSerializeAndDeserializeJson() throws Exception {
        BatchAnalysisSectionsDto sections = new BatchAnalysisSectionsDto(
                "Executive summary text",
                "Planned vs actual metric evaluation",
                "eBR step evaluation",
                "Quality control summary",
                "Deviations and CAPA summary",
                "Missing records warning",
                "Follow-up advisory recommendations"
        );
        String json = objectMapper.writeValueAsString(sections);
        assertNotNull(json);

        BatchAnalysisSectionsDto deserialized = objectMapper.readValue(json, BatchAnalysisSectionsDto.class);
        assertEquals("Executive summary text", deserialized.getExecutiveSummary());
        assertEquals("Missing records warning", deserialized.getMissingRecords());

        List<AiSourceReferenceDto> sources = List.of(
                new AiSourceReferenceDto("BATCH", "BAT-2026-001", "Batch BAT-2026-001", "traceability"),
                new AiSourceReferenceDto("DEVIATION", "DEV-2026-005", "Deviation 005", "deviations")
        );
        String sourcesJson = objectMapper.writeValueAsString(sources);

        List<AiSourceReferenceDto> deserializedSources = objectMapper.readValue(
                sourcesJson,
                new TypeReference<List<AiSourceReferenceDto>>() {}
        );
        assertEquals(2, deserializedSources.size());
        assertEquals("BATCH", deserializedSources.get(0).getRecordType());
        assertEquals("DEVIATION", deserializedSources.get(1).getRecordType());
    }

    @Test
    @DisplayName("Should find report by physical batchId and by clientRequestId")
    void shouldFindReportByBatchIdAndByClientRequestId() {
        String reqId = UUID.randomUUID().toString();
        BatchAnalysisReport report = new BatchAnalysisReport(testBatch, testSupervisor, reqId);
        reportRepository.saveAndFlush(report);
        entityManager.clear();

        Optional<BatchAnalysisReport> byClientReq = reportRepository.findByClientRequestId(reqId);
        assertTrue(byClientReq.isPresent());
        assertEquals(testBatch.getBatchNumber(), byClientReq.get().getBatchNumber());

        Optional<BatchAnalysisReport> byIdAndBatchId = reportRepository.findByIdAndBatchId(
                byClientReq.get().getId(),
                testBatch.getId()
        );
        assertTrue(byIdAndBatchId.isPresent());

        // Negative check: wrong batchId
        Optional<BatchAnalysisReport> wrongBatch = reportRepository.findByIdAndBatchId(
                byClientReq.get().getId(),
                999999L
        );
        assertTrue(wrongBatch.isEmpty());
    }
}
