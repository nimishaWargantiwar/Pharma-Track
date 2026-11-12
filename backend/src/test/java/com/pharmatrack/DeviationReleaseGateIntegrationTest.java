package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.CancelCapaActionRequestDto;
import com.pharmatrack.dto.CloseDeviationRequestDto;
import com.pharmatrack.dto.CompleteCapaActionRequestDto;
import com.pharmatrack.dto.CreateCapaActionRequestDto;
import com.pharmatrack.dto.CreateDeviationRequestDto;
import com.pharmatrack.dto.QaDispositionRequestDto;
import com.pharmatrack.dto.RecordRootCauseRequestDto;
import com.pharmatrack.dto.ReopenCapaRequestDto;
import com.pharmatrack.dto.StartCapaActionRequestDto;
import com.pharmatrack.dto.StartCapaRequestDto;
import com.pharmatrack.dto.StartInvestigationRequestDto;
import com.pharmatrack.dto.SubmitVerificationRequestDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchExecutionStep;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.CapaStatus;
import com.pharmatrack.entity.CapaType;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.QualityCheck;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchExecutionStepRepository;
import com.pharmatrack.repository.BatchQaReviewRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.CapaActionRepository;
import com.pharmatrack.repository.DeviationRepository;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full Spring Boot + H2 + MockMvc end-to-end integration tests verifying:
 * - Complete HTTP REST workflows for Deviations & CAPA (DEV-YYYY-XXXX & CAPA-YYYY-XXXX,
 *   linkable records, 6-stage lifecycle transitions, CAPA actions, and reopen flow)
 * - Backend QA release gate independently enforcing that ANY non-CLOSED deviation blocks RELEASED
 * - Accurate counting of multiple unresolved deviations in disposition error & dossier/queue DTOs
 * - Zero Batch, BatchQaReview, or AuditLog mutations on rejected RELEASED attempts
 * - ON_HOLD and REJECTED dispositions remain permitted despite unresolved deviations
 * - Closing a deviation never auto-releases a batch and preserves historical batch/QC/eBR data
 * - Phase 1 QA protections (0 checks, PENDING check, FAILED required check) remain intact
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeviationReleaseGateIntegrationTest {

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
    private BatchExecutionStepRepository executionStepRepository;

    @Autowired
    private QualityCheckRepository qualityCheckRepository;

    @Autowired
    private BatchQaReviewRepository batchQaReviewRepository;

    @Autowired
    private DeviationRepository deviationRepository;

    @Autowired
    private CapaActionRepository capaActionRepository;

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

    private record SeedBundle(Batch batch, RecipeStep recipeStep) {
    }

    private SeedBundle seedCompletedBatch(String tag, BatchQualityStatus qaStatus) {
        int n = SEQ.getAndIncrement();
        Product product = productRepository.saveAndFlush(new Product(
                "PRD-" + tag + "-" + n,
                "Test Product " + tag,
                "500 mg",
                "Tablet",
                "Analgesic",
                ProductStatus.ACTIVE
        ));
        Recipe recipe = new Recipe(
                "RCP-" + tag + "-" + n,
                product,
                1,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Master Recipe " + tag
        );
        recipe.addStep(new RecipeStep(recipe, 1, "Granulation", "Blend 15 minutes at 120 RPM"));
        recipe = recipeRepository.saveAndFlush(recipe);

        ManufacturingOrder order = orderRepository.saveAndFlush(new ManufacturingOrder(
                "MO-INT-" + n,
                product,
                recipe,
                1,
                new BigDecimal("250.0000"),
                "kg",
                1,
                OrderStatus.COMPLETED,
                LocalDate.now(DeviationService.APP_ZONE_ID),
                LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(3),
                "Integration seed order"
        ));

        Batch batch = new Batch(
                "BAT-INT-" + tag + "-" + n,
                order,
                new BigDecimal("250.0000"),
                "kg",
                BatchStatus.COMPLETED,
                LocalDate.now(DeviationService.APP_ZONE_ID),
                null
        );
        batch.setQaStatus(qaStatus);
        batch.setActualYield(new BigDecimal("248.5000"));
        batch.setStartTime(LocalDateTime.now(DeviationService.APP_ZONE_ID).minusHours(4));
        batch.setEndTime(LocalDateTime.now(DeviationService.APP_ZONE_ID).minusHours(1));
        batch = batchRepository.saveAndFlush(batch);

        return new SeedBundle(batch, recipe.getSteps().get(0));
    }

    private QualityCheck addQcCheck(Batch batch, String testName, String spec, String measured,
                                    QualityCheckResult result, boolean required) {
        return qualityCheckRepository.saveAndFlush(new QualityCheck(
                batch, testName, spec, measured, "%",
                result, required, "QC Analyst",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(20),
                "Seed check"
        ));
    }

    private long postDeviationAndReturnId(CreateDeviationRequestDto req, String bearer) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/quality/deviations")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asLong();
    }

    private void closeDeviationViaHttp(long deviationId) throws Exception {
        String qaBearer = bearerFor("dr.r.menon", Role.QA_REVIEWER);
        String engKumarBearer = bearerFor("eng.kumar", Role.PRODUCTION_SUPERVISOR);
        String qaLeadBearer = bearerFor("qa.lead.patel", Role.QA_REVIEWER);
        LocalDate dueDate = LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(3);

        mockMvc.perform(post("/api/quality/deviations/" + deviationId + "/start-investigation")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartInvestigationRequestDto("Investigated batch logs and equipment calibration"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_INVESTIGATION"));

        mockMvc.perform(post("/api/quality/deviations/" + deviationId + "/record-root-cause")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RecordRootCauseRequestDto(
                                        "Equipment / Calibration",
                                        "Sensor signal cable loose inside junction box.",
                                        "Isolated to current batch; prior batches verified unaffected.",
                                        null
                                ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROOT_CAUSE_IDENTIFIED"));

        MvcResult capaRes = mockMvc.perform(post("/api/quality/deviations/" + deviationId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCapaActionRequestDto(
                                        CapaType.CORRECTIVE,
                                        "Secure cable and recalibrate",
                                        "Re-terminate junction cable and perform full sensor calibration.",
                                        "Eng. Kumar",
                                        dueDate
                                ))))
                .andExpect(status().isCreated())
                .andReturn();
        long capaId = objectMapper.readTree(capaRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/quality/deviations/" + deviationId + "/start-capa")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartCapaRequestDto("Proceeding with calibration work order"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPA_IN_PROGRESS"));

        mockMvc.perform(post("/api/quality/capas/" + capaId + "/start")
                        .header(HttpHeaders.AUTHORIZATION, engKumarBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartCapaActionRequestDto("Technician assigned"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mockMvc.perform(post("/api/quality/capas/" + capaId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, engKumarBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteCapaActionRequestDto("Calibration certificate CAL-9001 verified"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(post("/api/quality/deviations/" + deviationId + "/submit-verification")
                        .header(HttpHeaders.AUTHORIZATION, engKumarBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubmitVerificationRequestDto("All CAPA evidence verified"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.eligibleForClosure").value(true));

        mockMvc.perform(post("/api/quality/deviations/" + deviationId + "/close")
                        .header(HttpHeaders.AUTHORIZATION, qaLeadBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CloseDeviationRequestDto("Verified calibration and logs."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    @DisplayName("1. Full HTTP Deviation & CAPA lifecycle (linkable-records, sequential numbers, reopen, close, and filters)")
    void fullHttpDeviationLifecycleAndLinkableRecordsWorkEndToEnd() throws Exception {
        String qcBearer = bearerFor("qc.analyst", Role.QC_ANALYST);
        String qaBearer = bearerFor("dr.menon", Role.QA_REVIEWER);
        String engKumarBearer = bearerFor("eng.kumar", Role.PRODUCTION_SUPERVISOR);
        String qaLeadBearer = bearerFor("qa.lead.patel", Role.QA_REVIEWER);
        String qaHeadBearer = bearerFor("qa.head", Role.QA_REVIEWER);

        SeedBundle seed = seedCompletedBatch("E2E", BatchQualityStatus.PENDING_QA);
        Batch batch = seed.batch();
        QualityCheck qc = addQcCheck(batch, "Dissolution Test", "Minimum 80%", "65", QualityCheckResult.FAIL, true);
        BatchExecutionStep step = executionStepRepository.saveAndFlush(new BatchExecutionStep(
                batch, seed.recipeStep(), 1, "Granulation", "Blend 15 min", ExecutionStepStatus.COMPLETED
        ));
        AuditLog sourceAudit = auditLogRepository.save(new AuditLog(
                "QUALITY_CHECK", qc.getId(), batch.getBatchNumber(), batch.getId(),
                "QC_CHECK_CREATED", "qc.analyst", LocalDateTime.now(DeviationService.APP_ZONE_ID),
                "Recorded FAIL dissolution", null, "{\"result\":\"FAIL\"}"
        ));

        // 1. Fetch linkable records for batch
        mockMvc.perform(get("/api/quality/batches/" + batch.getId() + "/linkable-records")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNumber").value(batch.getBatchNumber()))
                .andExpect(jsonPath("$.qualityChecks.length()").value(1))
                .andExpect(jsonPath("$.qualityChecks[0].id").value(qc.getId()))
                .andExpect(jsonPath("$.executionSteps.length()").value(1))
                .andExpect(jsonPath("$.executionSteps[0].id").value(step.getId()))
                .andExpect(jsonPath("$.auditLogs.length()").value(1))
                .andExpect(jsonPath("$.auditLogs[0].id").value(sourceAudit.getId()));

        // 2. Report deviation via HTTP -> DEV-YYYY-XXXX
        int year = LocalDate.now(DeviationService.APP_ZONE_ID).getYear();
        CreateDeviationRequestDto createReq = new CreateDeviationRequestDto(
                batch.getId(), qc.getId(), step.getId(), sourceAudit.getId(),
                "Dissolution test below minimum 80% specification",
                DeviationCategory.QC_OOS_FAILURE,
                DeviationSeverity.CRITICAL,
                DeviationPriority.HIGH,
                "Dissolution measured at 65% against minimum 80% requirement.",
                "Finished pallets quarantined in Cage B.",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(5)
        );

        MvcResult createResult = mockMvc.perform(post("/api/quality/deviations")
                        .header(HttpHeaders.AUTHORIZATION, qcBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.qualityCheckId").value(qc.getId()))
                .andExpect(jsonPath("$.executionStepId").value(step.getId()))
                .andExpect(jsonPath("$.sourceAuditLogId").value(sourceAudit.getId()))
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        long devId = createdJson.get("id").asLong();
        String expectedDevNumber = String.format("DEV-%d-%04d", year, devId);
        assertEquals(expectedDevNumber, createdJson.get("deviationNumber").asText());

        // 3. List filters & batch deviations lookup
        mockMvc.perform(get("/api/quality/deviations")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .param("batchId", String.valueOf(batch.getId()))
                        .param("status", "OPEN")
                        .param("severity", "CRITICAL")
                        .param("category", "QC_OOS_FAILURE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deviationNumber").value(expectedDevNumber));

        mockMvc.perform(get("/api/quality/batches/" + batch.getId() + "/deviations")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 4. Investigation -> Root Cause -> CAPA 1 (complete) & CAPA 2 (cancel) -> Verification -> Reopen -> CAPA 3 -> Close
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-investigation")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartInvestigationRequestDto("Evaluating granulation and compression"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_INVESTIGATION"));

        mockMvc.perform(post("/api/quality/deviations/" + devId + "/record-root-cause")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RecordRootCauseRequestDto("Equipment", "Punch spring fatigue", "Single batch", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROOT_CAUSE_IDENTIFIED"));

        LocalDate today = LocalDate.now(DeviationService.APP_ZONE_ID);
        MvcResult capa1Res = mockMvc.perform(post("/api/quality/deviations/" + devId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCapaActionRequestDto(
                                        CapaType.CORRECTIVE, "Replace spring", "Replace worn spring and test compression",
                                        "Eng. Kumar", today))))
                .andExpect(status().isCreated())
                .andReturn();
        long capa1Id = objectMapper.readTree(capa1Res.getResponse().getContentAsString()).get("id").asLong();
        String expectedCapaNum = String.format("CAPA-%d-%04d", year, capa1Id);

        MvcResult capa2Res = mockMvc.perform(post("/api/quality/deviations/" + devId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCapaActionRequestDto(
                                        CapaType.PREVENTIVE, "Optional spare kit", "Duplicate kit order",
                                        "Eng. Kumar", today.plusDays(2)))))
                .andExpect(status().isCreated())
                .andReturn();
        long capa2Id = objectMapper.readTree(capa2Res.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-capa")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaRequestDto("Executing remediation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPA_IN_PROGRESS"));

        mockMvc.perform(post("/api/quality/capas/" + capa1Id + "/start")
                        .header(HttpHeaders.AUTHORIZATION, engKumarBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaActionRequestDto(null))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/quality/capas/" + capa1Id + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, engKumarBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteCapaActionRequestDto("Replaced spring; Cert CAL-120."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capaNumber").value(expectedCapaNum));
        mockMvc.perform(post("/api/quality/capas/" + capa2Id + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CancelCapaActionRequestDto("Duplicate spare inventory already stocked."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/quality/deviations/" + devId + "/submit-verification")
                        .header(HttpHeaders.AUTHORIZATION, engKumarBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitVerificationRequestDto("Submitted"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));

        // Reopen CAPA -> add preventive SOP CAPA -> complete -> resubmit -> close
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/reopen-capa")
                        .header(HttpHeaders.AUTHORIZATION, qaLeadBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReopenCapaRequestDto("Require SOP preventive inspection schedule"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPA_IN_PROGRESS"));

        MvcResult capa3Res = mockMvc.perform(post("/api/quality/deviations/" + devId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaLeadBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCapaActionRequestDto(
                                        CapaType.PREVENTIVE, "Revise SOP-PR-11", "Add bi-monthly spring fatigue check",
                                        "QA Spec", today.plusDays(4)))))
                .andExpect(status().isCreated())
                .andReturn();
        long capa3Id = objectMapper.readTree(capa3Res.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/quality/capas/" + capa3Id + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, qaLeadBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteCapaActionRequestDto("SOP-PR-11 Rev 2 approved."))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/quality/deviations/" + devId + "/submit-verification")
                        .header(HttpHeaders.AUTHORIZATION, qaLeadBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SubmitVerificationRequestDto("Preventive SOP complete."))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/quality/deviations/" + devId + "/close")
                        .header(HttpHeaders.AUTHORIZATION, qaHeadBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CloseDeviationRequestDto("Effectiveness verified."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(get("/api/quality/deviations/" + devId)
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.capaActions.length()").value(3))
                .andExpect(jsonPath("$.completedCapaCount").value(2))
                .andExpect(jsonPath("$.cancelledCapaCount").value(1));
    }

    @Test
    @DisplayName("2. OPEN, UNDER_INVESTIGATION, and multiple unresolved deviations block RELEASED over HTTP with exact count and zero DB writes")
    void openUnderInvestigationAndMultipleDeviationsBlockReleaseOverHttp() throws Exception {
        String op1Bearer = bearerFor("operator.1", Role.OPERATOR);
        String op2Bearer = bearerFor("operator.2", Role.OPERATOR);
        String qaBearer = bearerFor("dr.menon", Role.QA_REVIEWER);

        SeedBundle seed = seedCompletedBatch("GATE", BatchQualityStatus.PENDING_QA);
        Batch batch = seed.batch();
        addQcCheck(batch, "Assay", "95-105%", "99.0", QualityCheckResult.PASS, true);

        // 1. Report first deviation (OPEN)
        long dev1Id = postDeviationAndReturnId(new CreateDeviationRequestDto(
                batch.getId(), null, null, null,
                "Excursion #1 in granulation temp",
                DeviationCategory.PROCESS_PARAMETER, DeviationSeverity.MAJOR, DeviationPriority.HIGH,
                "Temperature spiked 3C above nominal.", "Verified cooling jacket valve.", null
        ), op1Bearer);

        long reviewsBefore = batchQaReviewRepository.count();
        long auditsBefore = auditLogRepository.count();

        // Attempt RELEASED while OPEN -> HTTP 400 + zero writes
        mockMvc.perform(post("/api/quality/batches/" + batch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Direct HTTP release"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("1 unresolved deviation(s)")));

        assertEquals(BatchQualityStatus.PENDING_QA, batchRepository.findById(batch.getId()).orElseThrow().getQaStatus());
        assertEquals(reviewsBefore, batchQaReviewRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());

        // 2. Move dev1 -> UNDER_INVESTIGATION and confirm still blocked
        mockMvc.perform(post("/api/quality/deviations/" + dev1Id + "/start-investigation")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartInvestigationRequestDto("Reviewing SCADA temperature trend"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_INVESTIGATION"));

        auditsBefore = auditLogRepository.count();
        mockMvc.perform(post("/api/quality/batches/" + batch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Release during investigation"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("1 unresolved deviation(s)")));

        assertEquals(BatchQualityStatus.PENDING_QA, batchRepository.findById(batch.getId()).orElseThrow().getQaStatus());
        assertEquals(reviewsBefore, batchQaReviewRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());

        // 3. Report a second deviation (OPEN) -> 2 unresolved deviations total
        postDeviationAndReturnId(new CreateDeviationRequestDto(
                batch.getId(), null, null, null,
                "Excursion #2 carton weigh scale drift",
                DeviationCategory.EQUIPMENT_MALFUNCTION, DeviationSeverity.MINOR, DeviationPriority.MEDIUM,
                "Checkweigher offset by 1.2g.", "Re-zeroed checkweigher.", null
        ), op2Bearer);

        auditsBefore = auditLogRepository.count();

        mockMvc.perform(get("/api/quality/batches/" + batch.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleForRelease").value(false))
                .andExpect(jsonPath("$.totalDeviationsCount").value(2))
                .andExpect(jsonPath("$.openDeviationsCount").value(2))
                .andExpect(jsonPath("$.deviations.length()").value(2));

        mockMvc.perform(post("/api/quality/batches/" + batch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Attempt release with 2 open"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("2 unresolved deviation(s)")));

        assertEquals(BatchQualityStatus.PENDING_QA, batchRepository.findById(batch.getId()).orElseThrow().getQaStatus());
        assertEquals(reviewsBefore, batchQaReviewRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());
    }

    @Test
    @DisplayName("3. ON_HOLD and REJECTED succeed despite unresolved deviations; closing never auto-releases; CLOSED permits RELEASED")
    void onHoldRejectedAllowedClosingNeverAutoReleasesAndClosedAllowsRelease() throws Exception {
        String opBearer = bearerFor("operator.1", Role.OPERATOR);
        String qaBearer = bearerFor("dr.menon", Role.QA_REVIEWER);

        SeedBundle holdSeed = seedCompletedBatch("HOLD", BatchQualityStatus.PENDING_QA);
        Batch holdBatch = holdSeed.batch();
        QualityCheck qc = addQcCheck(holdBatch, "Assay", "95-105%", "98.8", QualityCheckResult.PASS, true);
        BatchExecutionStep step = executionStepRepository.saveAndFlush(new BatchExecutionStep(
                holdBatch, holdSeed.recipeStep(), 1, "Granulation", "Blend 15 min", ExecutionStepStatus.COMPLETED
        ));

        long devId = postDeviationAndReturnId(new CreateDeviationRequestDto(
                holdBatch.getId(), qc.getId(), step.getId(), null,
                "Blender speed alarm during run",
                DeviationCategory.EQUIPMENT_MALFUNCTION, DeviationSeverity.MAJOR, DeviationPriority.HIGH,
                "Alarm triggered twice during blending.", "Paused and inspected drive belt.", null
        ), opBearer);

        // ON_HOLD permitted despite OPEN deviation
        mockMvc.perform(post("/api/quality/batches/" + holdBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Hold pending CAPA"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("ON_HOLD"))
                .andExpect(jsonPath("$.eligibleForRelease").value(false))
                .andExpect(jsonPath("$.openDeviationsCount").value(1));

        long reviewsAfterHold = batchQaReviewRepository.count();

        // Separate batch with OPEN deviation can be REJECTED
        SeedBundle rejectSeed = seedCompletedBatch("REJ", BatchQualityStatus.PENDING_QA);
        Batch rejectBatch = rejectSeed.batch();
        addQcCheck(rejectBatch, "Assay", "95-105%", "98.0", QualityCheckResult.PASS, true);
        postDeviationAndReturnId(new CreateDeviationRequestDto(
                rejectBatch.getId(), null, null, null,
                "Confirmed cross-contamination event",
                DeviationCategory.DOCUMENTATION_ERROR, DeviationSeverity.CRITICAL, DeviationPriority.HIGH,
                "Line clearance sign-off invalid.", "Quarantined full lot.", null
        ), qaBearer);

        mockMvc.perform(post("/api/quality/batches/" + rejectBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.REJECTED, "Rejected due to line clearance failure"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("REJECTED"));

        long reviewsBeforeClose = batchQaReviewRepository.count();

        // Close holdBatch's deviation via HTTP
        closeDeviationViaHttp(devId);

        // Verify closing deviation NEVER auto-released holdBatch and preserved manufacturing/QC/eBR data
        Batch reloadedHold = batchRepository.findById(holdBatch.getId()).orElseThrow();
        assertEquals(BatchQualityStatus.ON_HOLD, reloadedHold.getQaStatus(),
                "Closing a deviation must never auto-release the batch");
        assertEquals(BatchStatus.COMPLETED, reloadedHold.getStatus());
        assertEquals(0, new BigDecimal("248.5000").compareTo(reloadedHold.getActualYield()));
        assertEquals(QualityCheckResult.PASS, qualityCheckRepository.findById(qc.getId()).orElseThrow().getResult());
        assertEquals(ExecutionStepStatus.COMPLETED, executionStepRepository.findById(step.getId()).orElseThrow().getStatus());
        assertEquals(reviewsBeforeClose, batchQaReviewRepository.count());

        // Now dossier is eligibleForRelease and explicit RELEASED disposition succeeds
        mockMvc.perform(get("/api/quality/batches/" + holdBatch.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleForRelease").value(true))
                .andExpect(jsonPath("$.openDeviationsCount").value(0))
                .andExpect(jsonPath("$.releaseBlockers.length()").value(0));

        mockMvc.perform(post("/api/quality/batches/" + holdBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "CAPA closed and QC passed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("RELEASED"))
                .andExpect(jsonPath("$.eligibleForRelease").value(false));

        assertEquals(BatchQualityStatus.RELEASED, batchRepository.findById(holdBatch.getId()).orElseThrow().getQaStatus());
        assertEquals(reviewsAfterHold + 2, batchQaReviewRepository.count()); // +1 for REJECTED, +1 for RELEASED
    }

    @Test
    @DisplayName("4. Existing Phase 1 QC release protections remain intact with and without unresolved deviations")
    void existingPhase1ReleaseProtectionsRemainIntact() throws Exception {
        String qcBearer = bearerFor("qc.analyst", Role.QC_ANALYST);
        String qaBearer = bearerFor("dr.menon", Role.QA_REVIEWER);

        // Case A: 0 QC checks
        Batch zeroCheckBatch = seedCompletedBatch("P1ZERO", BatchQualityStatus.PENDING_QA).batch();
        long reviewsBefore = batchQaReviewRepository.count();
        long auditsBefore = auditLogRepository.count();

        mockMvc.perform(post("/api/quality/batches/" + zeroCheckBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Bypass 0 checks"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("at least one Quality Control (QC) check")));
        assertEquals(reviewsBefore, batchQaReviewRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());

        // Case B: PENDING QC check
        Batch pendingCheckBatch = seedCompletedBatch("P1PEND", BatchQualityStatus.PENDING_QA).batch();
        addQcCheck(pendingCheckBatch, "Assay", "95-105%", null, QualityCheckResult.PENDING, true);
        mockMvc.perform(post("/api/quality/batches/" + pendingCheckBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Bypass pending"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("PENDING")));
        assertEquals(reviewsBefore, batchQaReviewRepository.count());

        // Case C: FAILED required QC check + CLOSED deviation -> STILL blocked by failed required QC check
        Batch failedCheckBatch = seedCompletedBatch("P1FAIL", BatchQualityStatus.PENDING_QA).batch();
        QualityCheck failedQc = addQcCheck(failedCheckBatch, "Dissolution Test", "Minimum 80%", "65", QualityCheckResult.FAIL, true);
        long devId = postDeviationAndReturnId(new CreateDeviationRequestDto(
                failedCheckBatch.getId(), failedQc.getId(), null, null,
                "Failed dissolution", DeviationCategory.QC_OOS_FAILURE, DeviationSeverity.CRITICAL, DeviationPriority.HIGH,
                "Dissolution 65%", "Quarantined", null
        ), qcBearer);

        // Both failed QC check AND open deviation reported in blocking message
        mockMvc.perform(post("/api/quality/batches/" + failedCheckBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Bypass both"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("1 required QC check(s) have FAILED")))
                .andExpect(jsonPath("$.message").value(containsString("1 unresolved deviation(s)")));

        // Even after closing the deviation, failed required QC check STILL blocks RELEASED
        closeDeviationViaHttp(devId);
        long auditsAfterClose = auditLogRepository.count();

        mockMvc.perform(post("/api/quality/batches/" + failedCheckBatch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Try release after DEV closed"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("1 required QC check(s) have FAILED")));

        assertEquals(BatchQualityStatus.PENDING_QA, batchRepository.findById(failedCheckBatch.getId()).orElseThrow().getQaStatus());
        assertEquals(reviewsBefore, batchQaReviewRepository.count());
        assertEquals(auditsAfterClose, auditLogRepository.count());
    }

    @Test
    @DisplayName("5. HTTP CAPA lifecycle gates reject premature/last-active actions and allow post-disposition deviations without altering QA status")
    void httpCapaLifecycleGatesAndPostDispositionDeviationsWork() throws Exception {
        String opBearer = bearerFor("operator.1", Role.OPERATOR);
        String qaBearer = bearerFor("dr.menon", Role.QA_REVIEWER);
        String engRaoBearer = bearerFor("eng.rao", Role.PRODUCTION_SUPERVISOR);

        SeedBundle seed = seedCompletedBatch("CAPAGATE", BatchQualityStatus.PENDING_QA);
        Batch batch = seed.batch();
        addQcCheck(batch, "Assay", "95-105%", "99.1", QualityCheckResult.PASS, true);

        long devId = postDeviationAndReturnId(new CreateDeviationRequestDto(
                batch.getId(), null, null, null,
                "Blender speed drift during manufacturing",
                DeviationCategory.PROCESS_PARAMETER, DeviationSeverity.MAJOR, DeviationPriority.HIGH,
                "RPM dropped transiently.", "Verified blend uniformity.", null
        ), opBearer);
        LocalDate dueDate = LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(3);

        // Reject creating CAPA in OPEN
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCapaActionRequestDto(
                                CapaType.CORRECTIVE, "Early CAPA", "Too early",
                                "Eng. Rao", dueDate))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("ROOT_CAUSE_IDENTIFIED or CAPA_IN_PROGRESS")));

        // Move to UNDER_INVESTIGATION -> reject creating CAPA
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-investigation")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StartInvestigationRequestDto("Checking blender VFD"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/quality/deviations/" + devId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCapaActionRequestDto(
                                CapaType.CORRECTIVE, "Early CAPA 2", "Still too early",
                                "Eng. Rao", dueDate))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("UNDER_INVESTIGATION")));

        // Move to ROOT_CAUSE_IDENTIFIED -> create CAPA -> reject CAPA start/complete before CAPA_IN_PROGRESS
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/record-root-cause")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordRootCauseRequestDto(
                                "Equipment", "Loose VFD terminal", "Single batch", null))))
                .andExpect(status().isOk());

        MvcResult capaRes = mockMvc.perform(post("/api/quality/deviations/" + devId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCapaActionRequestDto(
                                CapaType.CORRECTIVE, "Tighten terminal", "Torque VFD lug and test run",
                                "Eng. Rao", dueDate))))
                .andExpect(status().isCreated())
                .andReturn();
        long capaId = objectMapper.readTree(capaRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/quality/capas/" + capaId + "/start")
                        .header(HttpHeaders.AUTHORIZATION, engRaoBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaActionRequestDto("Too early"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("expected CAPA_IN_PROGRESS")));

        mockMvc.perform(post("/api/quality/capas/" + capaId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, engRaoBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteCapaActionRequestDto("Early completion attempt"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("expected CAPA_IN_PROGRESS")));

        // Enter CAPA_IN_PROGRESS -> reject cancelling last active CAPA
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-capa")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaRequestDto("Execute"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/quality/capas/" + capaId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CancelCapaActionRequestDto("Cancel only active CAPA"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("last active CAPA action")));

        // Post-disposition deviation on an already RELEASED batch -> 201 and QA status stays RELEASED
        SeedBundle releasedSeed = seedCompletedBatch("POSTREL", BatchQualityStatus.RELEASED);
        Batch releasedBatch = releasedSeed.batch();
        long reviewsBeforePostRel = batchQaReviewRepository.count();

        MvcResult postRelCreate = mockMvc.perform(post("/api/quality/deviations")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDeviationRequestDto(
                                releasedBatch.getId(), null, null, null,
                                "Post-release customer label smudge report",
                                DeviationCategory.DOCUMENTATION_ERROR, DeviationSeverity.MINOR, DeviationPriority.LOW,
                                "Shipper outer label smudge reported post-release.", "Investigating warehouse scanner.",
                                null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchQaStatus").value("RELEASED"))
                .andReturn();
        long postRelDevId = objectMapper.readTree(postRelCreate.getResponse().getContentAsString()).get("id").asLong();

        assertEquals(BatchQualityStatus.RELEASED,
                batchRepository.findById(releasedBatch.getId()).orElseThrow().getQaStatus());
        closeDeviationViaHttp(postRelDevId);
        assertEquals(BatchQualityStatus.RELEASED,
                batchRepository.findById(releasedBatch.getId()).orElseThrow().getQaStatus());
        assertEquals(reviewsBeforePostRel, batchQaReviewRepository.count());
    }

    @Test
    @DisplayName("6. HTTP endpoints reject duplicate/backward deviation transitions, invalid CAPA state transitions, verification-stage edits, and last-active CAPA cancellation with zero DB writes")
    void httpNegativeLifecycleAndCapaStateRestrictionsRejectWithoutSideEffects() throws Exception {
        String qaBearer = bearerFor("dr.menon", Role.QA_REVIEWER);
        String qaIntruderBearer = bearerFor("qa.intruder", Role.QA_REVIEWER);
        String engRaoBearer = bearerFor("eng.rao", Role.PRODUCTION_SUPERVISOR);

        SeedBundle seed = seedCompletedBatch("NEGHTTP", BatchQualityStatus.PENDING_QA);
        Batch batch = seed.batch();
        addQcCheck(batch, "Assay", "95-105%", "98.9", QualityCheckResult.PASS, true);
        LocalDate dueDate = LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(4);

        long devId = postDeviationAndReturnId(new CreateDeviationRequestDto(
                batch.getId(), null, null, null,
                "HTTP negative lifecycle restriction verification",
                DeviationCategory.PROCESS_PARAMETER, DeviationSeverity.MAJOR, DeviationPriority.HIGH,
                "Testing all HTTP negative transition paths.", "Lot held for test.", null
        ), qaBearer);
        long auditsAtOpen = auditLogRepository.count();

        // 1. Out-of-order jumps from OPEN -> HTTP 400 + zero writes
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/record-root-cause")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordRootCauseRequestDto("Method", "RCA", "Impact", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("OPEN")));
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-capa")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaRequestDto("Plan"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/submit-verification")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitVerificationRequestDto("Notes"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/reopen-capa")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReopenCapaRequestDto("Reopen"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/close")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CloseDeviationRequestDto("Verified"))))
                .andExpect(status().isBadRequest());
        assertEquals(auditsAtOpen, auditLogRepository.count());

        // 2. Move OPEN -> UNDER_INVESTIGATION -> reject duplicate start-investigation & premature CAPA create
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-investigation")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartInvestigationRequestDto("Original inv"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_INVESTIGATION"));
        long auditsAtInv = auditLogRepository.count();
        long capasAtInv = capaActionRepository.count();

        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-investigation")
                        .header(HttpHeaders.AUTHORIZATION, qaIntruderBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartInvestigationRequestDto("Duplicate inv"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("UNDER_INVESTIGATION")));
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCapaActionRequestDto(
                                CapaType.CORRECTIVE, "Early", "Plan", "Eng", dueDate))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("UNDER_INVESTIGATION")));
        assertEquals(auditsAtInv, auditLogRepository.count());
        assertEquals(capasAtInv, capaActionRepository.count());
        assertEquals("dr.menon", deviationRepository.findById(devId).orElseThrow().getInvestigatedBy());

        // 3. Move -> ROOT_CAUSE_IDENTIFIED -> reject backward/duplicate transitions; create 3 CAPAs and cancel 1 cleanly
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/record-root-cause")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordRootCauseRequestDto(
                                "Equipment", "Loose coupling", "Single batch", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROOT_CAUSE_IDENTIFIED"));
        long auditsAtRca = auditLogRepository.count();

        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-investigation")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartInvestigationRequestDto("Backward"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/record-root-cause")
                        .header(HttpHeaders.AUTHORIZATION, qaIntruderBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordRootCauseRequestDto(
                                "Other", "Duplicate", "Duplicate", null))))
                .andExpect(status().isBadRequest());
        assertEquals(auditsAtRca, auditLogRepository.count());

        MvcResult rcaCap1Res = mockMvc.perform(post("/api/quality/deviations/" + devId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCapaActionRequestDto(
                                CapaType.CORRECTIVE, "Tighten coupling", "Replace bolts & torque", "Eng. Rao", dueDate))))
                .andExpect(status().isCreated()).andReturn();
        long c1Id = objectMapper.readTree(rcaCap1Res.getResponse().getContentAsString()).get("id").asLong();

        MvcResult rcaCap2Res = mockMvc.perform(post("/api/quality/deviations/" + devId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCapaActionRequestDto(
                                CapaType.PREVENTIVE, "Torque PM step", "Add quarterly check", "Eng. Rao", dueDate.plusDays(2)))))
                .andExpect(status().isCreated()).andReturn();
        long c2Id = objectMapper.readTree(rcaCap2Res.getResponse().getContentAsString()).get("id").asLong();

        MvcResult rcaCap3Res = mockMvc.perform(post("/api/quality/deviations/" + devId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCapaActionRequestDto(
                                CapaType.PREVENTIVE, "Draft duplicate", "Draft extra check", "Eng. Rao", dueDate.plusDays(3)))))
                .andExpect(status().isCreated()).andReturn();
        long c3Id = objectMapper.readTree(rcaCap3Res.getResponse().getContentAsString()).get("id").asLong();

        // Cancel c3 while in ROOT_CAUSE_IDENTIFIED -> 200 CANCELLED
        mockMvc.perform(post("/api/quality/capas/" + c3Id + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelCapaActionRequestDto("Trimmed duplicate"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Enter CAPA_IN_PROGRESS with active CAPAs c1 & c2
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-capa")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaRequestDto("Execute plan"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPA_IN_PROGRESS"));
        long auditsInCapa = auditLogRepository.count();

        // Duplicate start-capa + backward transitions + premature close -> HTTP 400 + zero writes
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-capa")
                        .header(HttpHeaders.AUTHORIZATION, qaIntruderBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaRequestDto("Duplicate"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-investigation")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartInvestigationRequestDto("Backward"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/record-root-cause")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordRootCauseRequestDto("Other", "Back", "Back", null))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/close")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CloseDeviationRequestDto("Skip verify"))))
                .andExpect(status().isBadRequest());
        assertEquals(auditsInCapa, auditLogRepository.count());

        // Start c1 -> IN_PROGRESS; duplicate start on c1 -> HTTP 400
        mockMvc.perform(post("/api/quality/capas/" + c1Id + "/start")
                        .header(HttpHeaders.AUTHORIZATION, engRaoBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaActionRequestDto("Working"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        long auditsAfterC1Start = auditLogRepository.count();

        mockMvc.perform(post("/api/quality/capas/" + c1Id + "/start")
                        .header(HttpHeaders.AUTHORIZATION, engRaoBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaActionRequestDto("Duplicate start"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("IN_PROGRESS")));
        // Repeat start / complete / cancel on already CANCELLED c3 -> HTTP 400
        mockMvc.perform(post("/api/quality/capas/" + c3Id + "/start")
                        .header(HttpHeaders.AUTHORIZATION, engRaoBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaActionRequestDto("Start cancelled"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/quality/capas/" + c3Id + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, engRaoBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CompleteCapaActionRequestDto("Complete cancelled"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/quality/capas/" + c3Id + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelCapaActionRequestDto("Double cancel"))))
                .andExpect(status().isBadRequest());
        assertEquals(auditsAfterC1Start, auditLogRepository.count());

        // Cancel c2 (permitted because 2 active CAPAs c1 & c2 exist) -> 200 CANCELLED
        mockMvc.perform(post("/api/quality/capas/" + c2Id + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelCapaActionRequestDto("Covered under master PM"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Now c1 is last active CAPA in CAPA_IN_PROGRESS -> cancel rejected (HTTP 400)
        long auditsBeforeLastCancel = auditLogRepository.count();
        mockMvc.perform(post("/api/quality/capas/" + c1Id + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelCapaActionRequestDto("Cancel last active"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("last active CAPA action")));
        assertEquals(CapaStatus.IN_PROGRESS, capaActionRepository.findById(c1Id).orElseThrow().getStatus());
        assertEquals(auditsBeforeLastCancel, auditLogRepository.count());

        // Complete c1 -> 200 COMPLETED; repeat start/complete/cancel on COMPLETED c1 -> HTTP 400
        mockMvc.perform(post("/api/quality/capas/" + c1Id + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, engRaoBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CompleteCapaActionRequestDto("Torque report TQ-08 passed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        long auditsAfterC1Complete = auditLogRepository.count();

        mockMvc.perform(post("/api/quality/capas/" + c1Id + "/start")
                        .header(HttpHeaders.AUTHORIZATION, engRaoBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaActionRequestDto("Restart completed"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/quality/capas/" + c1Id + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, engRaoBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CompleteCapaActionRequestDto("Overwrite evidence"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/quality/capas/" + c1Id + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelCapaActionRequestDto("Cancel completed"))))
                .andExpect(status().isBadRequest());
        assertEquals(auditsAfterC1Complete, auditLogRepository.count());

        // 4. Submit for verification -> PENDING_VERIFICATION locks ALL CAPA mutations & duplicate submit
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/submit-verification")
                        .header(HttpHeaders.AUTHORIZATION, engRaoBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitVerificationRequestDto("Submitted"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
        long auditsAtPendingVerify = auditLogRepository.count();

        mockMvc.perform(post("/api/quality/deviations/" + devId + "/submit-verification")
                        .header(HttpHeaders.AUTHORIZATION, engRaoBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitVerificationRequestDto("Duplicate submit"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/start-capa")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaRequestDto("Backward"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/capas")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCapaActionRequestDto(
                                CapaType.PREVENTIVE, "Locked", "Locked", "Eng. Rao", dueDate))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("PENDING_VERIFICATION")));
        mockMvc.perform(post("/api/quality/capas/" + c1Id + "/start")
                        .header(HttpHeaders.AUTHORIZATION, engRaoBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartCapaActionRequestDto("Locked"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("PENDING_VERIFICATION")));
        mockMvc.perform(post("/api/quality/capas/" + c1Id + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, engRaoBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CompleteCapaActionRequestDto("Locked"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("PENDING_VERIFICATION")));
        mockMvc.perform(post("/api/quality/capas/" + c1Id + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelCapaActionRequestDto("Locked"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("PENDING_VERIFICATION")));
        assertEquals(auditsAtPendingVerify, auditLogRepository.count());

        // Close cleanly via HTTP -> 200 CLOSED
        mockMvc.perform(post("/api/quality/deviations/" + devId + "/close")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CloseDeviationRequestDto("Effectiveness verified"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        assertEquals(DeviationStatus.CLOSED, deviationRepository.findById(devId).orElseThrow().getStatus());
    }
}
