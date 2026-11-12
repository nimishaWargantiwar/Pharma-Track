package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.CloseDeviationRequestDto;
import com.pharmatrack.dto.CompleteCapaActionRequestDto;
import com.pharmatrack.dto.CreateCapaActionRequestDto;
import com.pharmatrack.dto.CreateDeviationRequestDto;
import com.pharmatrack.dto.QaDispositionRequestDto;
import com.pharmatrack.dto.QualityCheckCorrectionRequestDto;
import com.pharmatrack.dto.QualityCheckRequestDto;
import com.pharmatrack.dto.RecordRootCauseRequestDto;
import com.pharmatrack.dto.ReopenCapaRequestDto;
import com.pharmatrack.dto.StartCapaActionRequestDto;
import com.pharmatrack.dto.StartCapaRequestDto;
import com.pharmatrack.dto.StartInvestigationRequestDto;
import com.pharmatrack.dto.SubmitVerificationRequestDto;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchQaReview;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.CapaType;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchQaReviewRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.QualityCheckRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.JwtService;
import com.pharmatrack.service.DeviationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 2 Negative Testing – Step 4: QA Release Gate Integrity (Full Spring Boot + H2 + MockMvc HTTP Integration).
 * Each HTTP call executes in its own committed/rolled-back Spring transaction against an isolated
 * in-memory H2 database to verify release gates, error responses, and zero persistence on rejected attempts.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_qagate_http_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QaReleaseGateHttpIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RecipeRepository recipeRepository;

    @Autowired
    private ManufacturingOrderRepository orderRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private QualityCheckRepository qualityCheckRepository;

    @Autowired
    private BatchQaReviewRepository batchQaReviewRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private String bearerFor(String username, Role role) {
        String normalized = UserAccount.normalizeUsername(username);
        UserAccount user = userAccountRepository.findByUsernameIgnoreCase(normalized)
                .orElseGet(() -> userAccountRepository.saveAndFlush(new UserAccount(
                        normalized,
                        passwordEncoder.encode("TestPass#" + UUID.randomUUID()),
                        username.trim(),
                        role,
                        true
                )));
        assertEquals(role, user.getRole());
        return "Bearer " + jwtService.generateToken(user);
    }

    private Batch seedBatch(String tag, BatchStatus status, BatchQualityStatus qaStatus) {
        int n = SEQ.getAndIncrement();
        Product product = productRepository.saveAndFlush(new Product(
                "PRD-HG-" + tag + "-" + n,
                "HTTP Gate Product " + tag,
                "500 mg",
                "Tablet",
                "Analgesic",
                ProductStatus.ACTIVE
        ));
        Recipe recipe = recipeRepository.saveAndFlush(new Recipe(
                "RCP-HG-" + tag + "-" + n,
                product,
                1,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Master Recipe " + tag
        ));
        ManufacturingOrder order = orderRepository.saveAndFlush(new ManufacturingOrder(
                "MO-HG-" + tag + "-" + n,
                product,
                recipe,
                1,
                new BigDecimal("250.0000"),
                "kg",
                1,
                OrderStatus.IN_PROGRESS,
                LocalDate.now(DeviationService.APP_ZONE_ID),
                LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(4),
                "HTTP release gate seed order"
        ));
        Batch batch = new Batch(
                "BAT-HG-" + tag + "-" + n,
                order,
                new BigDecimal("250.0000"),
                "kg",
                status,
                LocalDate.now(DeviationService.APP_ZONE_ID),
                null
        );
        batch.setQaStatus(qaStatus);
        if (status == BatchStatus.COMPLETED) {
            batch.setActualYield(new BigDecimal("248.5000"));
            batch.setStartTime(LocalDateTime.now(DeviationService.APP_ZONE_ID).minusHours(4));
            batch.setEndTime(LocalDateTime.now(DeviationService.APP_ZONE_ID).minusHours(1));
        }
        return batchRepository.saveAndFlush(batch);
    }

    private long postQcCheck(Long batchId, String testName, String spec, String measured,
                             QualityCheckResult result, boolean required) throws Exception {
        String qcBearer = bearerFor("qc.analyst", Role.QC_ANALYST);
        QualityCheckRequestDto req = new QualityCheckRequestDto(
                testName, spec, measured, "%", result, required, null, "HTTP seed check"
        );
        MvcResult res = mockMvc.perform(post("/api/quality/batches/" + batchId + "/checks")
                        .header(HttpHeaders.AUTHORIZATION, qcBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private long postDeviation(Long batchId, String title) throws Exception {
        String qaBearer = bearerFor("dr.menon", Role.QA_REVIEWER);
        CreateDeviationRequestDto req = new CreateDeviationRequestDto(
                batchId, null, null, null,
                title,
                DeviationCategory.PROCESS_PARAMETER,
                DeviationSeverity.MAJOR,
                DeviationPriority.HIGH,
                "Excursion details for " + title,
                "Lot isolated for QA review.",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(5)
        );
        MvcResult res = mockMvc.perform(post("/api/quality/deviations")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private void assertHttpReleaseRejectedWithZeroWrites(Long batchId,
                                                         BatchQualityStatus expectedQaStatus,
                                                         String expectedMessageFragment) throws Exception {
        String qaBearer = bearerFor("dr.menon", Role.QA_REVIEWER);
        Batch batchBefore = batchRepository.findById(batchId).orElseThrow();
        BigDecimal yieldBefore = batchBefore.getActualYield();
        LocalDateTime updatedAtBefore = batchBefore.getUpdatedAt();
        long reviewsBefore = batchQaReviewRepository.count();
        long auditsBefore = auditLogRepository.count();
        long qcCountBefore = qualityCheckRepository.count();

        mockMvc.perform(post("/api/quality/batches/" + batchId + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(
                                        BatchQualityStatus.RELEASED,
                                        "Attempt HTTP RELEASED bypass"
                                ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(expectedMessageFragment)));

        Batch reloaded = batchRepository.findById(batchId).orElseThrow();
        assertEquals(expectedQaStatus, reloaded.getQaStatus(),
                "Batch qaStatus must remain unchanged after rejected HTTP RELEASED attempt");
        assertEquals(BatchStatus.COMPLETED, reloaded.getStatus());
        assertEquals(0, yieldBefore.compareTo(reloaded.getActualYield()),
                "Batch actualYield must remain unchanged after rejected HTTP RELEASED attempt");
        assertEquals(updatedAtBefore, reloaded.getUpdatedAt(),
                "Batch updatedAt must remain unchanged after rejected HTTP RELEASED attempt");
        assertEquals(qcCountBefore, qualityCheckRepository.count(),
                "No QualityCheck row may be created or removed on rejected HTTP RELEASED attempt");
        assertEquals(reviewsBefore, batchQaReviewRepository.count(),
                "No BatchQaReview row may be created on rejected HTTP RELEASED attempt");
        assertEquals(auditsBefore, auditLogRepository.count(),
                "No AuditLog row may be created on rejected HTTP RELEASED attempt");
    }

    @Test
    @DisplayName("1. HTTP QA RELEASED blocked in ROOT_CAUSE_IDENTIFIED, CAPA_IN_PROGRESS, and PENDING_VERIFICATION with zero DB writes; closing never auto-releases")
    void httpReleaseBlockedAcrossLaterDeviationStagesAndClosingNeverAutoReleases() throws Exception {
        String qaBearer = bearerFor("dr.menon", Role.QA_REVIEWER);
        String engKumarBearer = bearerFor("eng.kumar", Role.PRODUCTION_SUPERVISOR);
        Batch batch = seedBatch("DEVSTAGE", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        postQcCheck(batch.getId(), "Assay (HPLC)", "95.0% - 105.0%", "99.4", QualityCheckResult.PASS, true);

        long devId = postDeviation(batch.getId(), "Dryer inlet temperature excursion");
        LocalDate dueDate = LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(3);

        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-investigation")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartInvestigationRequestDto("Checking inlet PID logs"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_INVESTIGATION"));

        // Stage: ROOT_CAUSE_IDENTIFIED -> HTTP 400 + zero writes
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/record-root-cause")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RecordRootCauseRequestDto(
                                        "Equipment", "Sticking steam control valve",
                                        "Single batch affected", "Rebuild actuator"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROOT_CAUSE_IDENTIFIED"));

        mockMvc.perform(get("/api/quality/batches/" + batch.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleForRelease").value(false))
                .andExpect(jsonPath("$.openDeviationsCount").value(1));
        assertHttpReleaseRejectedWithZeroWrites(batch.getId(), BatchQualityStatus.PENDING_QA, "[ROOT_CAUSE_IDENTIFIED]");

        // Stage: CAPA_IN_PROGRESS -> HTTP 400 + zero writes
        MvcResult capaRes = mockMvc.perform(post("/api/quality/deviations/" + devId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCapaActionRequestDto(
                                CapaType.CORRECTIVE, "Rebuild steam valve", "Replace diaphragm and stroke test",
                                "Eng. Kumar", dueDate))))
                .andExpect(status().isCreated())
                .andReturn();
        long capaId = objectMapper.readTree(capaRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-capa")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartCapaRequestDto("Executing valve rebuild"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPA_IN_PROGRESS"));

        assertHttpReleaseRejectedWithZeroWrites(batch.getId(), BatchQualityStatus.PENDING_QA, "[CAPA_IN_PROGRESS]");

        // Stage: PENDING_VERIFICATION -> HTTP 400 + zero writes
        mockMvc.perform(post("/api/quality/capas/" + capaId + "/start")
                        .header(HttpHeaders.AUTHORIZATION, engKumarBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaActionRequestDto("Working"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/quality/capas/" + capaId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, engKumarBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteCapaActionRequestDto("Valve stroke test VLV-12 passed"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/submit-verification")
                        .header(HttpHeaders.AUTHORIZATION, engKumarBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubmitVerificationRequestDto("All CAPA work finished"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));

        assertHttpReleaseRejectedWithZeroWrites(batch.getId(), BatchQualityStatus.PENDING_QA, "[PENDING_VERIFICATION]");

        // Close deviation -> HTTP 200 CLOSED -> batch MUST stay PENDING_QA (no auto-release!)
        long reviewsBeforeClose = batchQaReviewRepository.count();
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/close")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CloseDeviationRequestDto("Effectiveness verified"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        assertEquals(reviewsBeforeClose, batchQaReviewRepository.count(),
                "Closing a deviation must never create a BatchQaReview");

        mockMvc.perform(get("/api/quality/batches/" + batch.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("PENDING_QA"))
                .andExpect(jsonPath("$.eligibleForRelease").value(true))
                .andExpect(jsonPath("$.openDeviationsCount").value(0))
                .andExpect(jsonPath("$.qaReviews.length()").value(0));

        // Explicit QA RELEASED disposition now succeeds -> HTTP 200
        mockMvc.perform(post("/api/quality/batches/" + batch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Explicit QA release after closure"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("RELEASED"))
                .andExpect(jsonPath("$.batchStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.qaReviews.length()").value(1))
                .andExpect(jsonPath("$.qaReviews[0].decisionStatus").value("RELEASED"));

        List<BatchQaReview> reviews = batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(batch.getId());
        assertEquals(1, reviews.size());
        assertEquals(BatchQualityStatus.RELEASED, reviews.get(0).getDecisionStatus());
    }

    @Test
    @DisplayName("2. HTTP QA RELEASED blocks 0 checks, optional-only PASS checks (no required checks), required FAIL, required PENDING, and optional FAIL/PENDING")
    void httpReleaseBlockedAcrossQcCheckGatePermutations() throws Exception {
        String qcBearer = bearerFor("qc.analyst", Role.QC_ANALYST);
        String qaBearer = bearerFor("dr.menon", Role.QA_REVIEWER);

        // Case A: 0 QC checks
        Batch zeroBatch = seedBatch("ZERO", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        mockMvc.perform(get("/api/quality/batches/" + zeroBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleForRelease").value(false))
                .andExpect(jsonPath("$.releaseBlockers", hasItem(containsString("At least one QC check must be recorded"))));
        assertHttpReleaseRejectedWithZeroWrites(
                zeroBatch.getId(), BatchQualityStatus.PENDING_QA,
                "at least one Quality Control (QC) check must be recorded first");

        // Case B: Optional-only PASS check (required=false, requiredChecksCount=0)
        Batch optOnlyBatch = seedBatch("OPTONLY", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        postQcCheck(optOnlyBatch.getId(), "Visual Inspection (Optional)", "Uniform appearance",
                "Conforms", QualityCheckResult.PASS, false);
        mockMvc.perform(get("/api/quality/batches/" + optOnlyBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredChecksCount").value(0))
                .andExpect(jsonPath("$.passedChecksCount").value(1))
                .andExpect(jsonPath("$.eligibleForRelease").value(false))
                .andExpect(jsonPath("$.releaseBlockers", hasItem(containsString("At least one required QC check must exist and PASS"))));
        assertHttpReleaseRejectedWithZeroWrites(
                optOnlyBatch.getId(), BatchQualityStatus.PENDING_QA,
                "at least one required QC check must exist and PASS");

        // Case C: Required FAIL check
        Batch reqFailBatch = seedBatch("REQFAIL", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        postQcCheck(reqFailBatch.getId(), "Assay", "95-105%", "99.0", QualityCheckResult.PASS, true);
        postQcCheck(reqFailBatch.getId(), "Dissolution", "Minimum 80%", "72.0", QualityCheckResult.FAIL, true);
        assertHttpReleaseRejectedWithZeroWrites(
                reqFailBatch.getId(), BatchQualityStatus.PENDING_QA,
                "1 required QC check(s) have FAILED");

        // Case D: Required PENDING check
        Batch reqPendBatch = seedBatch("REQPEND", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        postQcCheck(reqPendBatch.getId(), "Assay", "95-105%", "99.0", QualityCheckResult.PASS, true);
        postQcCheck(reqPendBatch.getId(), "Bioburden", "< 10 CFU", null, QualityCheckResult.PENDING, true);
        assertHttpReleaseRejectedWithZeroWrites(
                reqPendBatch.getId(), BatchQualityStatus.PENDING_QA,
                "1 QC check(s) are still PENDING");

        // Case E: Required PASS + Optional FAIL -> implemented rule blocks release across Queue & Dossier
        Batch optFailBatch = seedBatch("OPTFAIL", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        postQcCheck(optFailBatch.getId(), "Assay", "95-105%", "99.2", QualityCheckResult.PASS, true);
        long optFailId = postQcCheck(optFailBatch.getId(), "Weight Variation (Optional)",
                "Maximum 2.0%", "2.8", QualityCheckResult.FAIL, false);

        mockMvc.perform(get("/api/quality/batches/" + optFailBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleForRelease").value(false))
                .andExpect(jsonPath("$.releaseBlockers", hasItem("1 optional QC check(s) have FAILED")));
        mockMvc.perform(get("/api/quality/batches")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.batchId==" + optFailBatch.getId() + ")].eligibleForRelease", hasItem(false)))
                .andExpect(jsonPath("$[?(@.batchId==" + optFailBatch.getId() + ")].blockingChecksCount", hasItem(1)))
                .andExpect(jsonPath("$[?(@.batchId==" + optFailBatch.getId() + ")].optionalChecksCount", hasItem(1)))
                .andExpect(jsonPath("$[?(@.batchId==" + optFailBatch.getId() + ")].releaseBlockers[0]", hasItem("1 optional QC check(s) have FAILED")));
        assertHttpReleaseRejectedWithZeroWrites(
                optFailBatch.getId(), BatchQualityStatus.PENDING_QA, "1 optional QC check(s) have FAILED");

        // Case F: Required PASS + Optional PENDING -> implemented rule blocks release across Queue & Dossier; correcting to PASS unblocks
        Batch optPendBatch = seedBatch("OPTPEND", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        postQcCheck(optPendBatch.getId(), "Assay", "95-105%", "99.2", QualityCheckResult.PASS, true);
        long optPendId = postQcCheck(optPendBatch.getId(), "Moisture Loss (Optional)",
                "Maximum 1.5%", null, QualityCheckResult.PENDING, false);

        mockMvc.perform(get("/api/quality/batches/" + optPendBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleForRelease").value(false))
                .andExpect(jsonPath("$.releaseBlockers", hasItem("1 optional QC check(s) are still PENDING")));
        mockMvc.perform(get("/api/quality/batches")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.batchId==" + optPendBatch.getId() + ")].eligibleForRelease", hasItem(false)))
                .andExpect(jsonPath("$[?(@.batchId==" + optPendBatch.getId() + ")].releaseBlockers[0]", hasItem("1 optional QC check(s) are still PENDING")));
        assertHttpReleaseRejectedWithZeroWrites(
                optPendBatch.getId(), BatchQualityStatus.PENDING_QA, "1 optional QC check(s) are still PENDING");

        // Verify optional-only PASS queue item also reports blocked due to missing required check
        mockMvc.perform(get("/api/quality/batches")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.batchId==" + optOnlyBatch.getId() + ")].eligibleForRelease", hasItem(false)))
                .andExpect(jsonPath("$[?(@.batchId==" + optOnlyBatch.getId() + ")].requiredChecksCount", hasItem(0)))
                .andExpect(jsonPath("$[?(@.batchId==" + optOnlyBatch.getId() + ")].optionalChecksCount", hasItem(1)))
                .andExpect(jsonPath("$[?(@.batchId==" + optOnlyBatch.getId() + ")].releaseBlockers[0]", hasItem("At least one required QC check must exist and PASS")));

        // Record passing optional result via audited correction -> now eligible and explicit RELEASED succeeds
        mockMvc.perform(post("/api/quality/checks/" + optPendId + "/correct")
                        .header(HttpHeaders.AUTHORIZATION, qcBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QualityCheckCorrectionRequestDto(
                                "Moisture Loss (Optional)", "Maximum 1.5%", "0.8", "%",
                                QualityCheckResult.PASS, false, "Completed optional test",
                                "Recorded optional moisture reading"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/quality/batches/" + optPendBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "All QC checks satisfied"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("RELEASED"));

        // Verify optional FAIL check can still be placed ON_HOLD -> audited correction -> explicit RELEASED
        mockMvc.perform(post("/api/quality/batches/" + optFailBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Holding for optional check retest"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("ON_HOLD"));

        mockMvc.perform(post("/api/quality/checks/" + optFailId + "/correct")
                        .header(HttpHeaders.AUTHORIZATION, qcBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QualityCheckCorrectionRequestDto(
                                "Weight Variation (Optional)", "Maximum 2.0%", "0.9", "%",
                                QualityCheckResult.PASS, false, "Retested verified sample",
                                "Sampling outlier resolved with retest"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/quality/batches/" + optFailBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Released after audited retest"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("RELEASED"));
    }

    @Test
    @DisplayName("3. HTTP ON_HOLD and REJECTED succeed despite blockers (0 QC checks / failed QC / open deviations); REJECTED cannot later RELEASED; non-COMPLETED batch blocked")
    void httpOnHoldAndRejectedWorkWithBlockersAndFinalizedRejectedBlocksRelease() throws Exception {
        String qaBearer = bearerFor("dr.menon", Role.QA_REVIEWER);

        // Blocked batch with 0 QC checks + OPEN deviation -> ON_HOLD -> REJECTED works
        Batch holdThenRejectBatch = seedBatch("HR", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        postDeviation(holdThenRejectBatch.getId(), "Line clearance discrepancy");

        mockMvc.perform(post("/api/quality/batches/" + holdThenRejectBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Holding with 0 checks + OPEN dev"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("ON_HOLD"));

        mockMvc.perform(post("/api/quality/batches/" + holdThenRejectBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.REJECTED, "Final rejection due to clearance failure"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("REJECTED"));

        // Finalized REJECTED batch rejects RELEASED with HTTP 400 and zero DB writes
        assertHttpReleaseRejectedWithZeroWrites(
                holdThenRejectBatch.getId(), BatchQualityStatus.REJECTED, "already finalized as REJECTED");

        // Non-COMPLETED batch (IN_PROGRESS) cannot bypass QA disposition
        Batch inProgressBatch = seedBatch("INP", BatchStatus.IN_PROGRESS, null);
        long reviewsBefore = batchQaReviewRepository.count();
        long auditsBefore = auditLogRepository.count();

        mockMvc.perform(post("/api/quality/batches/" + inProgressBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Bypass incomplete batch"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Only COMPLETED batches are eligible for QA disposition")));

        assertNull(batchRepository.findById(inProgressBatch.getId()).orElseThrow().getQaStatus());
        assertEquals(reviewsBefore, batchQaReviewRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());

        // Validation failure (blank rationale) rejects RELEASED cleanly with HTTP 400 and zero DB writes
        Batch readyBatch = seedBatch("VAL", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        postQcCheck(readyBatch.getId(), "Assay", "95-105%", "99.1", QualityCheckResult.PASS, true);
        reviewsBefore = batchQaReviewRepository.count();
        auditsBefore = auditLogRepository.count();

        mockMvc.perform(post("/api/quality/batches/" + readyBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.rationale").exists());

        assertEquals(BatchQualityStatus.PENDING_QA, batchRepository.findById(readyBatch.getId()).orElseThrow().getQaStatus());
        assertEquals(reviewsBefore, batchQaReviewRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());

        // Valid explicit RELEASED on satisfied batch succeeds
        MvcResult relRes = mockMvc.perform(post("/api/quality/batches/" + readyBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "All QC checks passed and no unresolved deviations"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("RELEASED"))
                .andExpect(jsonPath("$.eligibleForRelease").value(false))
                .andReturn();
        JsonNode relJson = objectMapper.readTree(relRes.getResponse().getContentAsString());
        assertEquals(1, relJson.get("qaReviews").size());
        assertEquals("QA_DISPOSITION_RELEASED",
                auditLogRepository.findByBatchIdOrderByOccurredAtDescIdDesc(readyBatch.getId()).get(0).getAction());
    }

    @Test
    @DisplayName("4. HTTP Queue parity across required-FAIL correction, ON_HOLD with reopened deviation, closure non-auto-release, valid RELEASED, and post-release finality")
    void httpQueueParityRequiredFailCorrectionReopenHoldToReleasedAndFinality() throws Exception {
        String qcBearer = bearerFor("qc.analyst", Role.QC_ANALYST);
        String qaBearer = bearerFor("dr.menon", Role.QA_REVIEWER);
        String engKumarBearer = bearerFor("eng.kumar", Role.PRODUCTION_SUPERVISOR);

        Batch batch = seedBatch("FULLPARITY", BatchStatus.COMPLETED, BatchQualityStatus.PENDING_QA);
        long reqCheckId = postQcCheck(batch.getId(), "Dissolution Test", "Minimum 80%", "68.0", QualityCheckResult.FAIL, true);
        postQcCheck(batch.getId(), "Appearance Monitor (Optional)", "Uniform tablets", "Conforms", QualityCheckResult.PASS, false);
        long devId = postDeviation(batch.getId(), "Compression turret vibration alert");
        LocalDate dueDate = LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(3);

        // Queue reports both failed required QC check and unresolved deviation
        mockMvc.perform(get("/api/quality/batches")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.batchId==" + batch.getId() + ")].eligibleForRelease", hasItem(false)))
                .andExpect(jsonPath("$[?(@.batchId==" + batch.getId() + ")].requiredChecksCount", hasItem(1)))
                .andExpect(jsonPath("$[?(@.batchId==" + batch.getId() + ")].optionalChecksCount", hasItem(1)))
                .andExpect(jsonPath("$[?(@.batchId==" + batch.getId() + ")].blockingChecksCount", hasItem(1)))
                .andExpect(jsonPath("$[?(@.batchId==" + batch.getId() + ")].openDeviationsCount", hasItem(1)));

        // Move to ON_HOLD despite blockers -> HTTP 200 and accurate snapshot counts on BatchQaReview
        mockMvc.perform(post("/api/quality/batches/" + batch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Hold during OOS and deviation review"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("ON_HOLD"));

        BatchQaReview holdReview = batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(batch.getId()).get(0);
        assertEquals(2L, holdReview.getTotalChecksCount());
        assertEquals(1L, holdReview.getPassedChecksCount());
        assertEquals(1L, holdReview.getFailedChecksCount());
        assertEquals(0L, holdReview.getPendingChecksCount());

        // Advance deviation to PENDING_VERIFICATION and reopen -> returns to CAPA_IN_PROGRESS
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-investigation")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartInvestigationRequestDto("Checking turret logs"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/record-root-cause")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordRootCauseRequestDto(
                                "Equipment", "Feeder cam worn", "Single lot impact", null))))
                .andExpect(status().isOk());
        MvcResult c1Res = mockMvc.perform(post("/api/quality/deviations/" + devId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCapaActionRequestDto(
                                CapaType.CORRECTIVE, "Replace feeder cam", "Install OEM cam and inspect", "Eng. Kumar", dueDate))))
                .andExpect(status().isCreated()).andReturn();
        long c1Id = objectMapper.readTree(c1Res.getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-capa")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaRequestDto("Executing"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/quality/capas/" + c1Id + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, engKumarBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CompleteCapaActionRequestDto("Cam replaced"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/submit-verification")
                        .header(HttpHeaders.AUTHORIZATION, engKumarBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitVerificationRequestDto("Ready"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/reopen-capa")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReopenCapaRequestDto("Verify post-repair composite dissolution"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPA_IN_PROGRESS"));

        // Correct failed required Dissolution check to PASS -> still blocked solely by CAPA_IN_PROGRESS deviation!
        mockMvc.perform(post("/api/quality/checks/" + reqCheckId + "/correct")
                        .header(HttpHeaders.AUTHORIZATION, qcBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QualityCheckCorrectionRequestDto(
                                "Dissolution Test", "Minimum 80%", "88.5", "%",
                                QualityCheckResult.PASS, true, "Composite retest after cam adjustment",
                                "Investigated initial outlier and completed retest"))))
                .andExpect(status().isOk());

        assertHttpReleaseRejectedWithZeroWrites(batch.getId(), BatchQualityStatus.ON_HOLD, "[CAPA_IN_PROGRESS]");

        // Complete CAPA follow-up, re-submit, and close deviation -> stays ON_HOLD and flips Queue eligibleForRelease=true
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/submit-verification")
                        .header(HttpHeaders.AUTHORIZATION, engKumarBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitVerificationRequestDto("Resubmitted with retest data"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/close")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CloseDeviationRequestDto("Effectiveness confirmed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(get("/api/quality/batches")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.batchId==" + batch.getId() + ")].qaStatus", hasItem("ON_HOLD")))
                .andExpect(jsonPath("$[?(@.batchId==" + batch.getId() + ")].eligibleForRelease", hasItem(true)))
                .andExpect(jsonPath("$[?(@.batchId==" + batch.getId() + ")].openDeviationsCount", hasItem(0)))
                .andExpect(jsonPath("$[?(@.batchId==" + batch.getId() + ")].totalDeviationsCount", hasItem(1)));

        // Explicit RELEASED disposition from ON_HOLD with required PASS + optional PASS + CLOSED deviation succeeds
        mockMvc.perform(post("/api/quality/batches/" + batch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "All prerequisites passed and deviation closed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("RELEASED"))
                .andExpect(jsonPath("$.batchStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.qaReviews.length()").value(2));

        // Verify Queue updated with RELEASED status, reviewer metadata, and exact check snapshot on BatchQaReview
        mockMvc.perform(get("/api/quality/batches")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.batchId==" + batch.getId() + ")].qaStatus", hasItem("RELEASED")))
                .andExpect(jsonPath("$[?(@.batchId==" + batch.getId() + ")].eligibleForRelease", hasItem(false)))
                .andExpect(jsonPath("$[?(@.batchId==" + batch.getId() + ")].lastReviewedBy", hasItem("dr.menon")))
                .andExpect(jsonPath("$[?(@.batchId==" + batch.getId() + ")].lastReviewedAt", hasItem(notNullValue())));

        BatchQaReview releaseReview = batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(batch.getId()).get(0);
        assertEquals(BatchQualityStatus.ON_HOLD, releaseReview.getPreviousStatus());
        assertEquals(BatchQualityStatus.RELEASED, releaseReview.getDecisionStatus());
        assertEquals(2L, releaseReview.getTotalChecksCount());
        assertEquals(2L, releaseReview.getPassedChecksCount());
        assertEquals(0L, releaseReview.getFailedChecksCount());
        assertEquals(0L, releaseReview.getPendingChecksCount());

        // Post-release modification attempt -> HTTP 400 with zero DB changes
        long reviewsBeforeFinalCheck = batchQaReviewRepository.count();
        long auditsBeforeFinalCheck = auditLogRepository.count();
        mockMvc.perform(post("/api/quality/batches/" + batch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Post-release rollback attempt"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already finalized as RELEASED")));
        assertEquals(BatchQualityStatus.RELEASED, batchRepository.findById(batch.getId()).orElseThrow().getQaStatus());
        assertEquals(reviewsBeforeFinalCheck, batchQaReviewRepository.count());
        assertEquals(auditsBeforeFinalCheck, auditLogRepository.count());
    }
}

