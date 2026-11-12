package com.pharmatrack;

import com.pharmatrack.dto.CapaActionResponseDto;
import com.pharmatrack.dto.CloseDeviationRequestDto;
import com.pharmatrack.dto.CompleteCapaActionRequestDto;
import com.pharmatrack.dto.CreateCapaActionRequestDto;
import com.pharmatrack.dto.CreateDeviationRequestDto;
import com.pharmatrack.dto.DeviationDetailDto;
import com.pharmatrack.dto.QaDispositionRequestDto;
import com.pharmatrack.dto.RecordRootCauseRequestDto;
import com.pharmatrack.dto.StartCapaActionRequestDto;
import com.pharmatrack.dto.StartCapaRequestDto;
import com.pharmatrack.dto.StartInvestigationRequestDto;
import com.pharmatrack.dto.SubmitVerificationRequestDto;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchQaReview;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.CapaAction;
import com.pharmatrack.entity.CapaStatus;
import com.pharmatrack.entity.CapaType;
import com.pharmatrack.entity.Deviation;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.QualityCheck;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchQaReviewRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.CapaActionRepository;
import com.pharmatrack.repository.DeviationRepository;
import com.pharmatrack.repository.ManufacturingOrderRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.QualityCheckRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.security.JwtService;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.DeviationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Isolated Spring Boot + H2 integration tests verifying full transaction rollback
 * when {@link AuditTrailService#recordAudit} fails during deviation, CAPA, or QA disposition operations.
 * Test methods intentionally run WITHOUT {@code @Transactional} so Spring service proxy
 * boundaries commit and roll back real database transactions.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_rollback_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeviationAuditRollbackIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeviationService deviationService;

    @SpyBean
    private AuditTrailService auditTrailService;

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

    @AfterEach
    void resetSpy() {
        Mockito.reset(auditTrailService);
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, String... roles) {
        String[] authorities = Arrays.stream(roles)
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                .toArray(String[]::new);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "N/A", AuthorityUtils.createAuthorityList(authorities))
        );
    }

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

    private Batch seedCompletedBatch(String tag) {
        int n = SEQ.getAndIncrement();
        Product product = productRepository.saveAndFlush(new Product(
                "RB-PRD-" + tag + "-" + n,
                "Rollback Test Product " + tag,
                "500 mg",
                "Tablet",
                "Analgesic",
                ProductStatus.ACTIVE
        ));
        Recipe recipe = recipeRepository.saveAndFlush(new Recipe(
                "RB-RCP-" + tag + "-" + n,
                product,
                1,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Master Recipe"
        ));
        ManufacturingOrder order = orderRepository.saveAndFlush(new ManufacturingOrder(
                "RB-MO-" + n,
                product,
                recipe,
                1,
                new BigDecimal("250.0000"),
                "kg",
                1,
                OrderStatus.COMPLETED,
                LocalDate.now(DeviationService.APP_ZONE_ID),
                LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(2),
                "Rollback seed order"
        ));
        Batch batch = new Batch(
                "RB-BAT-" + tag + "-" + n,
                order,
                new BigDecimal("250.0000"),
                "kg",
                BatchStatus.COMPLETED,
                LocalDate.now(DeviationService.APP_ZONE_ID),
                null
        );
        batch.setQaStatus(BatchQualityStatus.PENDING_QA);
        batch.setActualYield(new BigDecimal("248.0000"));
        batch.setStartTime(LocalDateTime.now(DeviationService.APP_ZONE_ID).minusHours(4));
        batch.setEndTime(LocalDateTime.now(DeviationService.APP_ZONE_ID).minusHours(1));
        batch = batchRepository.saveAndFlush(batch);

        qualityCheckRepository.saveAndFlush(new QualityCheck(
                batch, "Dissolution Test", "Minimum 80%", "65", "%",
                QualityCheckResult.FAIL, true, "QC Analyst",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(20),
                "OOS failure for rollback seed"
        ));
        return batch;
    }

    @Test
    @DisplayName("1. Audit failure during createDeviation rolls back flushed Deviation entity and leaves zero partial rows")
    void createDeviationAuditFailureRollsBackCompletely() {
        Batch batch = seedCompletedBatch("CREATE");
        long deviationsBefore = deviationRepository.count();
        long capasBefore = capaActionRepository.count();
        long auditsBefore = auditLogRepository.count();

        authenticateAs("A.Nair", "QC_ANALYST");
        doThrow(new RuntimeException("Injected audit failure during deviation creation"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        CreateDeviationRequestDto req = new CreateDeviationRequestDto(
                batch.getId(), null, null, null,
                "Rollback verification deviation",
                DeviationCategory.QC_OOS_FAILURE,
                DeviationSeverity.CRITICAL,
                DeviationPriority.HIGH,
                "Verify saveAndFlush rollback on audit exception.",
                "Batch held in quarantine.",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(5)
        );

        RuntimeException ex = assertThrows(RuntimeException.class, () -> deviationService.createDeviation(req));
        assertTrue(ex.getMessage().contains("Injected audit failure"));

        assertEquals(deviationsBefore, deviationRepository.count(),
                "Deviation saved via saveAndFlush before audit failure must roll back completely");
        assertEquals(capasBefore, capaActionRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());
        assertTrue(deviationRepository.findByBatchIdWithDetailsOrderByReportedAtDesc(batch.getId()).isEmpty());

        // Recovery check after clearing failure injection
        Mockito.reset(auditTrailService);
        DeviationDetailDto created = deviationService.createDeviation(req);
        assertEquals(DeviationStatus.OPEN, created.getStatus());
        assertEquals(deviationsBefore + 1, deviationRepository.count());
        assertEquals(auditsBefore + 1, auditLogRepository.count());
    }

    @Test
    @DisplayName("2. Audit failure during deviation lifecycle transition rolls back status and fields cleanly")
    void deviationLifecycleAuditFailureRollsBackStateAndFields() {
        Batch batch = seedCompletedBatch("TRANSITION");
        authenticateAs("A.Nair", "QC_ANALYST");
        DeviationDetailDto created = deviationService.createDeviation(new CreateDeviationRequestDto(
                batch.getId(), null, null, null,
                "Lifecycle rollback deviation",
                DeviationCategory.PROCESS_PARAMETER,
                DeviationSeverity.MAJOR,
                DeviationPriority.HIGH,
                "Tablet weight variability.",
                "Quarantined drums.",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(5)
        ));
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        deviationService.startInvestigation(
                created.getId(),
                new StartInvestigationRequestDto("Baseline committed investigation.")
        );

        long deviationsBefore = deviationRepository.count();
        long auditsBefore = auditLogRepository.count();

        doThrow(new IllegalStateException("Injected audit failure during recordRootCause"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                deviationService.recordRootCause(created.getId(), new RecordRootCauseRequestDto(
                        "Equipment", "Feeder frame misalignment", "Current lot only", null
                ))
        );
        assertTrue(ex.getMessage().contains("Injected audit failure"));

        Deviation persisted = deviationRepository.findById(created.getId()).orElseThrow();
        assertEquals(DeviationStatus.UNDER_INVESTIGATION, persisted.getStatus());
        assertEquals("dr.menon", persisted.getInvestigatedBy());
        assertEquals("Baseline committed investigation.", persisted.getInvestigationSummary());
        assertNull(persisted.getRootCauseCategory());
        assertNull(persisted.getRootCauseAnalysis());
        assertNull(persisted.getImpactAssessment());
        assertNull(persisted.getRootCauseIdentifiedBy());
        assertNull(persisted.getRootCauseIdentifiedAt());
        assertEquals(deviationsBefore, deviationRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());
    }

    @Test
    @DisplayName("3. Audit failures during CAPA creation, CAPA completion (via HTTP), and deviation closure roll back completely without altering CAPA or deviation state")
    void capaAndClosureAuditFailuresRollBackAllBusinessAndAuditChanges() throws Exception {
        Batch batch = seedCompletedBatch("CAPA");
        LocalDate dueDate = LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(4);

        authenticateAs("A.Nair", "QC_ANALYST");
        DeviationDetailDto dev = deviationService.createDeviation(new CreateDeviationRequestDto(
                batch.getId(), null, null, null,
                "CAPA & closure rollback test",
                DeviationCategory.EQUIPMENT_MALFUNCTION,
                DeviationSeverity.MAJOR,
                DeviationPriority.HIGH,
                "Press compression spike.",
                "Lot placed on hold.",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(5)
        ));
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        deviationService.startInvestigation(dev.getId(), new StartInvestigationRequestDto("Investigating press"));
        deviationService.recordRootCause(dev.getId(), new RecordRootCauseRequestDto(
                "Equipment", "Relief valve sticking", "Single batch impact", null
        ));
        CapaActionResponseDto capa1 = deviationService.createCapaAction(dev.getId(), new CreateCapaActionRequestDto(
                CapaType.CORRECTIVE, "Replace relief valve", "Install calibrated relief valve",
                "Eng. Kumar", dueDate
        ));
        deviationService.startCapa(dev.getId(), new StartCapaRequestDto("Approved remediation plan"));
        authenticateAs("Eng.Kumar", "PRODUCTION_SUPERVISOR");
        deviationService.startCapaAction(capa1.getId(), new StartCapaActionRequestDto("Work started"));

        long capasAtBaseline = capaActionRepository.count();
        long auditsAtBaseline = auditLogRepository.count();

        // A. Failure during createCapaAction -> no orphan/TEMP CAPA persisted
        authenticateAs("Dr.Menon", "QA_REVIEWER");
        doThrow(new RuntimeException("Injected audit failure during CAPA creation"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThrows(RuntimeException.class, () ->
                deviationService.createCapaAction(dev.getId(), new CreateCapaActionRequestDto(
                        CapaType.PREVENTIVE, "Orphan CAPA check", "Should roll back", "Eng. Kumar", dueDate.plusDays(1)
                ))
        );
        assertEquals(capasAtBaseline, capaActionRepository.count());
        assertEquals(auditsAtBaseline, auditLogRepository.count());
        assertEquals(DeviationStatus.CAPA_IN_PROGRESS, deviationRepository.findById(dev.getId()).orElseThrow().getStatus());
        assertEquals(CapaStatus.IN_PROGRESS, capaActionRepository.findById(capa1.getId()).orElseThrow().getStatus());

        // B. Failure during completeCapaAction via HTTP MockMvc -> CAPA stays IN_PROGRESS with null completion fields
        String engKumarBearer = bearerFor("Eng.Kumar", Role.PRODUCTION_SUPERVISOR);
        mockMvc.perform(post("/api/quality/capas/" + capa1.getId() + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, engKumarBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completedBy\":\"Spoofed Actor\",\"completionEvidence\":\"Certificate CV-01\"}"))
                .andExpect(status().isInternalServerError());

        CapaAction afterFailedComplete = capaActionRepository.findById(capa1.getId()).orElseThrow();
        assertEquals(CapaStatus.IN_PROGRESS, afterFailedComplete.getStatus());
        assertNull(afterFailedComplete.getCompletedBy());
        assertNull(afterFailedComplete.getCompletionEvidence());
        assertNull(afterFailedComplete.getCompletedAt());
        assertEquals(DeviationStatus.CAPA_IN_PROGRESS, deviationRepository.findById(dev.getId()).orElseThrow().getStatus());
        assertEquals(capasAtBaseline, capaActionRepository.count());
        assertEquals(auditsAtBaseline, auditLogRepository.count());

        // C. Advance cleanly to PENDING_VERIFICATION, then inject failure during closeDeviation
        Mockito.reset(auditTrailService);
        authenticateAs("Eng.Kumar", "PRODUCTION_SUPERVISOR");
        deviationService.completeCapaAction(capa1.getId(), new CompleteCapaActionRequestDto("Certificate CV-01"));
        deviationService.submitForVerification(dev.getId(), new SubmitVerificationRequestDto("Ready for QA sign-off"));

        long auditsBeforeClose = auditLogRepository.count();
        authenticateAs("QA.Head", "QA_REVIEWER");
        doThrow(new RuntimeException("Injected audit failure during closeDeviation"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThrows(RuntimeException.class, () ->
                deviationService.closeDeviation(dev.getId(), new CloseDeviationRequestDto("Effectiveness verified"))
        );

        Deviation afterFailedClose = deviationRepository.findById(dev.getId()).orElseThrow();
        assertEquals(DeviationStatus.PENDING_VERIFICATION, afterFailedClose.getStatus());
        assertNull(afterFailedClose.getVerifiedBy());
        assertNull(afterFailedClose.getVerificationNotes());
        assertNull(afterFailedClose.getClosedBy());
        assertNull(afterFailedClose.getClosedAt());
        assertEquals(CapaStatus.COMPLETED, capaActionRepository.findById(capa1.getId()).orElseThrow().getStatus());
        assertEquals(auditsBeforeClose, auditLogRepository.count());

        // Verify valid closure succeeds after recovery
        Mockito.reset(auditTrailService);
        DeviationDetailDto closed = deviationService.closeDeviation(
                dev.getId(), new CloseDeviationRequestDto("Effectiveness verified")
        );
        assertEquals(DeviationStatus.CLOSED, closed.getStatus());
        assertEquals(auditsBeforeClose + 1, auditLogRepository.count());
    }

    @Test
    @DisplayName("4. Audit failure during QA RELEASED disposition rolls back Batch.qaStatus and BatchQaReview completely")
    void qaDispositionAuditFailureRollsBackBatchAndReviewCleanly() throws Exception {
        String qaBearer = bearerFor("dr.menon", Role.QA_REVIEWER);
        int n = SEQ.getAndIncrement();
        Product product = productRepository.saveAndFlush(new Product(
                "RB-PRD-QA-" + n,
                "QA Rollback Product",
                "500 mg",
                "Tablet",
                "Analgesic",
                ProductStatus.ACTIVE
        ));
        Recipe recipe = recipeRepository.saveAndFlush(new Recipe(
                "RB-RCP-QA-" + n,
                product,
                1,
                new BigDecimal("250.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Master QA Recipe"
        ));
        ManufacturingOrder order = orderRepository.saveAndFlush(new ManufacturingOrder(
                "RB-MO-QA-" + n,
                product,
                recipe,
                1,
                new BigDecimal("250.0000"),
                "kg",
                1,
                OrderStatus.COMPLETED,
                LocalDate.now(DeviationService.APP_ZONE_ID),
                LocalDate.now(DeviationService.APP_ZONE_ID).plusDays(2),
                "QA rollback seed order"
        ));
        Batch batch = new Batch(
                "RB-BAT-QA-" + n,
                order,
                new BigDecimal("250.0000"),
                "kg",
                BatchStatus.COMPLETED,
                LocalDate.now(DeviationService.APP_ZONE_ID),
                null
        );
        batch.setQaStatus(BatchQualityStatus.PENDING_QA);
        batch.setActualYield(new BigDecimal("248.7500"));
        batch.setStartTime(LocalDateTime.now(DeviationService.APP_ZONE_ID).minusHours(4));
        batch.setEndTime(LocalDateTime.now(DeviationService.APP_ZONE_ID).minusHours(1));
        batch = batchRepository.saveAndFlush(batch);

        qualityCheckRepository.saveAndFlush(new QualityCheck(
                batch, "Assay (HPLC)", "95.0% - 105.0%", "99.4", "%",
                QualityCheckResult.PASS, true, "qc.analyst",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(20),
                "Required check PASS"
        ));
        qualityCheckRepository.saveAndFlush(new QualityCheck(
                batch, "Moisture Loss (Optional)", "Maximum 2.0%", "0.9", "%",
                QualityCheckResult.PASS, false, "qc.analyst",
                LocalDateTime.now(DeviationService.APP_ZONE_ID).minusMinutes(15),
                "Optional check PASS"
        ));

        long reviewsBefore = batchQaReviewRepository.count();
        long auditsBefore = auditLogRepository.count();
        LocalDateTime updatedAtBefore = batch.getUpdatedAt();

        doThrow(new RuntimeException("Injected audit failure during QA disposition"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/quality/batches/" + batch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Valid release rollback test"))))
                .andExpect(status().isInternalServerError());

        Batch afterRollback = batchRepository.findById(batch.getId()).orElseThrow();
        assertEquals(BatchQualityStatus.PENDING_QA, afterRollback.getQaStatus(),
                "Batch.qaStatus must roll back to PENDING_QA when audit persistence fails");
        assertEquals(BatchStatus.COMPLETED, afterRollback.getStatus());
        assertEquals(0, new BigDecimal("248.7500").compareTo(afterRollback.getActualYield()));
        assertEquals(updatedAtBefore, afterRollback.getUpdatedAt());
        assertEquals(reviewsBefore, batchQaReviewRepository.count(),
                "BatchQaReview saved prior to recordAudit failure must roll back completely");
        assertTrue(batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(batch.getId()).isEmpty());
        assertEquals(auditsBefore, auditLogRepository.count());

        // Clear failure injection and confirm clean RELEASED succeeds
        Mockito.reset(auditTrailService);
        mockMvc.perform(post("/api/quality/batches/" + batch.getId() + "/disposition")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Clean release after recovery"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaStatus").value("RELEASED"))
                .andExpect(jsonPath("$.qaReviews.length()").value(1));

        List<BatchQaReview> reviews = batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(batch.getId());
        assertEquals(1, reviews.size());
        assertEquals(2L, reviews.get(0).getTotalChecksCount());
        assertEquals(2L, reviews.get(0).getPassedChecksCount());
        assertEquals(0L, reviews.get(0).getFailedChecksCount());
        assertEquals(0L, reviews.get(0).getPendingChecksCount());
        assertEquals(auditsBefore + 1, auditLogRepository.count());
    }
}

