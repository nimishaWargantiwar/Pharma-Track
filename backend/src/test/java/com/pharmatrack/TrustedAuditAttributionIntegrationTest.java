package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pharmatrack.dto.BatchStatusUpdateDto;
import com.pharmatrack.dto.CancelCapaActionRequestDto;
import com.pharmatrack.dto.CloseDeviationRequestDto;
import com.pharmatrack.dto.CompleteCapaActionRequestDto;
import com.pharmatrack.dto.CompleteExecutionStepRequestDto;
import com.pharmatrack.dto.CreateCapaActionRequestDto;
import com.pharmatrack.dto.CreateDeviationRequestDto;
import com.pharmatrack.dto.LoginRequestDto;
import com.pharmatrack.dto.OrderRequestDto;
import com.pharmatrack.dto.OrderStatusUpdateDto;
import com.pharmatrack.dto.ProductCreateRequestDto;
import com.pharmatrack.dto.QaDispositionRequestDto;
import com.pharmatrack.dto.QualityCheckCorrectionRequestDto;
import com.pharmatrack.dto.QualityCheckRequestDto;
import com.pharmatrack.dto.RecipeMaterialDto;
import com.pharmatrack.dto.RecipeRequestDto;
import com.pharmatrack.dto.RecipeStatusUpdateDto;
import com.pharmatrack.dto.RecipeStepDto;
import com.pharmatrack.dto.RecordRootCauseRequestDto;
import com.pharmatrack.dto.ReopenCapaRequestDto;
import com.pharmatrack.dto.StartCapaActionRequestDto;
import com.pharmatrack.dto.StartCapaRequestDto;
import com.pharmatrack.dto.StartInvestigationRequestDto;
import com.pharmatrack.dto.StatusUpdateRequestDto;
import com.pharmatrack.dto.SubmitVerificationRequestDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchExecutionStep;
import com.pharmatrack.entity.BatchQaReview;
import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.CapaAction;
import com.pharmatrack.entity.CapaType;
import com.pharmatrack.entity.Deviation;
import com.pharmatrack.entity.DeviationCategory;
import com.pharmatrack.entity.DeviationPriority;
import com.pharmatrack.entity.DeviationSeverity;
import com.pharmatrack.entity.DeviationStatus;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.QualityCheck;
import com.pharmatrack.entity.QualityCheckResult;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.BatchExecutionStepRepository;
import com.pharmatrack.repository.BatchQaReviewRepository;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.repository.CapaActionRepository;
import com.pharmatrack.repository.DeviationRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.QualityCheckRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.util.AppTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full Spring Boot + H2 + MockMvc end-to-end integration test verifying that every audited
 * action and domain attribution column resolves strictly from the authenticated JWT principal
 * (normalized lowercase), ignores forged {@code X-Actor-Name} headers and forged JSON actor fields,
 * preserves {@code CapaAction.ownerName}, enforces QA release/closure gates, and preserves
 * full atomic rollback when {@link AuditTrailService} fails.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_trusted_audit_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TrustedAuditAttributionIntegrationTest {

    private static final String FORGED_HEADER = "Spoofed.Header.Actor";
    private static final String FORGED_BODY = "Forged.Body.Actor";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ProductRepository productRepository;

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

    @SpyBean
    private AuditTrailService auditTrailService;

    private String adminBearer;
    private String supBearer;
    private String qaBearer;
    private String operatorBearer;
    private String qcBearer;

    @BeforeEach
    void setUpUsersAndLogins() throws Exception {
        if (adminBearer != null) {
            return;
        }
        adminBearer = loginUser("Admin.User", "System Admin", Role.ADMIN);
        supBearer = loginUser("Prod.Sup", "Production Supervisor", Role.PRODUCTION_SUPERVISOR);
        qaBearer = loginUser("Qa.Reviewer", "QA Reviewer", Role.QA_REVIEWER);
        operatorBearer = loginUser("Line.Operator", "Line Operator", Role.OPERATOR);
        qcBearer = loginUser("Qc.Analyst", "QC Analyst", Role.QC_ANALYST);
    }

    @AfterEach
    void resetSpy() {
        Mockito.reset(auditTrailService);
    }

    private String loginUser(String mixedCaseUsername, String fullName, Role role) throws Exception {
        String normalized = UserAccount.normalizeUsername(mixedCaseUsername);
        String rawPassword = "TrustPass9!" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        UserAccount user = userAccountRepository.findByUsernameIgnoreCase(normalized)
                .orElseGet(() -> userAccountRepository.saveAndFlush(new UserAccount(
                        normalized,
                        passwordEncoder.encode(rawPassword),
                        fullName,
                        role,
                        true
                )));
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setEnabled(true);
        user.setRole(role);
        userAccountRepository.saveAndFlush(user);

        // Login with mixed case to verify case-insensitive lookup + lowercase JWT subject claim
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(mixedCaseUsername, rawPassword))))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String withForgedActorFields(Object payload) throws Exception {
        ObjectNode node = payload != null
                ? objectMapper.valueToTree(payload)
                : objectMapper.createObjectNode();
        for (String forgedField : List.of(
                "actorName", "reportedBy", "investigatedBy", "rootCauseIdentifiedBy",
                "capaStartedBy", "verificationSubmittedBy", "reopenedBy", "verifiedBy",
                "closedBy", "createdBy", "completedBy", "cancelledBy", "checkedBy",
                "correctedBy", "reviewedBy", "inspectorName", "reviewerName", "executedBy"
        )) {
            node.put(forgedField, FORGED_BODY);
        }
        return objectMapper.writeValueAsString(node);
    }

    private JsonNode performWithSpoofedAttribution(HttpMethod method, String uri, String bearer,
                                                   Object payload, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, uri)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .header("X-Actor-Name", FORGED_HEADER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(withForgedActorFields(payload));

        MvcResult result = mockMvc.perform(builder)
                .andExpect(status().is(expectedStatus))
                .andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
    }

    private void assertSingleEntityAuditActor(String entityType, long entityId, String action, String expectedActor) {
        List<AuditLog> matches = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(entityType, entityId)
                .stream()
                .filter(log -> action.equals(log.getAction()))
                .toList();
        assertFalse(matches.isEmpty(), "Expected audit log " + action + " for " + entityType + "#" + entityId);
        assertEquals(expectedActor, matches.get(0).getActorName(),
                "Wrong actorName on " + action + " for " + entityType + "#" + entityId);
    }

    @Test
    @DisplayName("1. Cross-module workflow attributes every AuditLog and domain column to authenticated JWT user, ignoring forged headers/fields while preserving CAPA ownerName")
    void endToEndWorkflowAttributesEveryEventAndColumnToJwtPrincipal() throws Exception {
        // 1. Product Master (Admin.User -> admin.user)
        ProductCreateRequestDto createProduct = new ProductCreateRequestDto(
                "TR-AT-500", "Trusted Attribution Tablet", "500 mg", "Tablet", "Initial", ProductStatus.ACTIVE
        );
        JsonNode createdProd = performWithSpoofedAttribution(
                HttpMethod.POST, "/api/products", adminBearer, createProduct, 201);
        long productId = createdProd.get("id").asLong();
        assertSingleEntityAuditActor("PRODUCT", productId, "PRODUCT_CREATED", "admin.user");

        Product updateProduct = new Product(
                "TR-AT-500", "Trusted Attribution Tablet Rev", "500 mg", "Tablet", "Updated desc", ProductStatus.ACTIVE
        );
        performWithSpoofedAttribution(
                HttpMethod.PUT, "/api/products/" + productId, adminBearer, updateProduct, 200);
        assertSingleEntityAuditActor("PRODUCT", productId, "PRODUCT_UPDATED", "admin.user");

        // 2. Recipe Management (Prod.Sup -> prod.sup; Qa.Reviewer -> qa.reviewer)
        RecipeRequestDto createRecipeDto = new RecipeRequestDto(
                productId, new BigDecimal("100.0000"), "kg", "V1 formulation",
                List.of(new RecipeMaterialDto("API Powder", new BigDecimal("60.0000"), "kg")),
                List.of(new RecipeStepDto(1, "Blending", "Blend for 20 min at 25 RPM"))
        );
        JsonNode r1 = performWithSpoofedAttribution(
                HttpMethod.POST, "/api/recipes", supBearer, createRecipeDto, 201);
        long r1Id = r1.get("id").asLong();
        assertSingleEntityAuditActor("RECIPE", r1Id, "RECIPE_CREATED", "prod.sup");

        RecipeRequestDto updateRecipeDto = new RecipeRequestDto(
                productId, new BigDecimal("100.0000"), "kg", "V1 revised formulation",
                List.of(new RecipeMaterialDto("API Powder", new BigDecimal("65.0000"), "kg")),
                List.of(new RecipeStepDto(1, "Blending", "Blend for 25 min at 25 RPM"))
        );
        performWithSpoofedAttribution(
                HttpMethod.PUT, "/api/recipes/" + r1Id, supBearer, updateRecipeDto, 200);
        assertSingleEntityAuditActor("RECIPE", r1Id, "RECIPE_UPDATED", "prod.sup");

        performWithSpoofedAttribution(
                HttpMethod.PATCH, "/api/recipes/" + r1Id + "/status", qaBearer,
                new RecipeStatusUpdateDto(RecipeStatus.APPROVED), 200);
        assertSingleEntityAuditActor("RECIPE", r1Id, "RECIPE_APPROVED", "qa.reviewer");

        JsonNode r2 = performWithSpoofedAttribution(
                HttpMethod.POST, "/api/recipes/" + r1Id + "/new-version", supBearer, null, 201);
        long r2Id = r2.get("id").asLong();
        assertSingleEntityAuditActor("RECIPE", r2Id, "RECIPE_VERSION_CREATED", "prod.sup");

        performWithSpoofedAttribution(
                HttpMethod.PATCH, "/api/recipes/" + r2Id + "/status", qaBearer,
                new RecipeStatusUpdateDto(RecipeStatus.APPROVED), 200);
        assertSingleEntityAuditActor("RECIPE", r1Id, "RECIPE_OBSOLETED", "qa.reviewer");
        assertSingleEntityAuditActor("RECIPE", r2Id, "RECIPE_APPROVED", "qa.reviewer");

        JsonNode r3 = performWithSpoofedAttribution(
                HttpMethod.POST, "/api/recipes/" + r2Id + "/new-version", supBearer, null, 201);
        long r3Id = r3.get("id").asLong();
        performWithSpoofedAttribution(
                HttpMethod.DELETE, "/api/recipes/" + r3Id, supBearer, null, 204);
        assertSingleEntityAuditActor("RECIPE", r3Id, "RECIPE_DELETED", "prod.sup");

        // 3. Manufacturing Order & Batch Cancellation / Release Lifecycle
        LocalDate today = LocalDate.now(AppTime.APP_ZONE_ID);
        OrderRequestDto cancelOrderDto = new OrderRequestDto(
                productId, r2Id, new BigDecimal("100.0000"), "kg", today, today.plusDays(2), "Cancel attribution test"
        );
        JsonNode cancelledOrder = performWithSpoofedAttribution(
                HttpMethod.POST, "/api/orders", supBearer, cancelOrderDto, 201);
        long cancelOrderId = cancelledOrder.get("id").asLong();
        long cancelledBatchId = cancelledOrder.get("batches").get(0).get("id").asLong();
        assertSingleEntityAuditActor("MANUFACTURING_ORDER", cancelOrderId, "MANUFACTURING_ORDER_CREATED", "prod.sup");
        assertSingleEntityAuditActor("BATCH", cancelledBatchId, "BATCH_CREATED", "prod.sup");

        performWithSpoofedAttribution(
                HttpMethod.PATCH, "/api/batches/" + cancelledBatchId + "/status", adminBearer,
                new BatchStatusUpdateDto(BatchStatus.CANCELLED), 200);
        assertSingleEntityAuditActor("BATCH", cancelledBatchId, "BATCH_CANCELLED", "admin.user");
        assertSingleEntityAuditActor("MANUFACTURING_ORDER", cancelOrderId, "MANUFACTURING_ORDER_CANCELLED", "admin.user");

        // Active 2-batch Manufacturing Order
        OrderRequestDto mainOrderDto = new OrderRequestDto(
                productId, r2Id, new BigDecimal("200.0000"), "kg", today, today.plusDays(4), "Main attribution order"
        );
        JsonNode mainOrder = performWithSpoofedAttribution(
                HttpMethod.POST, "/api/orders", supBearer, mainOrderDto, 201);
        long orderId = mainOrder.get("id").asLong();
        long batch1Id = mainOrder.get("batches").get(0).get("id").asLong();
        long batch2Id = mainOrder.get("batches").get(1).get("id").asLong();

        performWithSpoofedAttribution(
                HttpMethod.PATCH, "/api/orders/" + orderId + "/status", supBearer,
                new OrderStatusUpdateDto(OrderStatus.RELEASED), 200);
        assertSingleEntityAuditActor("MANUFACTURING_ORDER", orderId, "MANUFACTURING_ORDER_RELEASED", "prod.sup");

        // 4. Production Execution (Line.Operator -> line.operator; auto-complete by Prod.Sup -> prod.sup)
        performWithSpoofedAttribution(
                HttpMethod.PATCH, "/api/batches/" + batch1Id + "/status", operatorBearer,
                new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS), 200);
        assertSingleEntityAuditActor("BATCH", batch1Id, "BATCH_STARTED", "line.operator");
        assertSingleEntityAuditActor("MANUFACTURING_ORDER", orderId, "MANUFACTURING_ORDER_UPDATED", "line.operator");

        BatchExecutionStep step1 = executionStepRepository.findByBatchIdOrderByStepNumberAsc(batch1Id).get(0);
        performWithSpoofedAttribution(
                HttpMethod.PATCH, "/api/batches/" + batch1Id + "/steps/" + step1.getId() + "/start",
                operatorBearer, null, 200);
        performWithSpoofedAttribution(
                HttpMethod.PATCH, "/api/batches/" + batch1Id + "/steps/" + step1.getId() + "/complete",
                operatorBearer, new CompleteExecutionStepRequestDto("25 RPM confirmed", "Smooth blend"), 200);

        BatchExecutionStep completedStep1 = executionStepRepository.findById(step1.getId()).orElseThrow();
        assertEquals("line.operator", completedStep1.getExecutedBy());

        performWithSpoofedAttribution(
                HttpMethod.PATCH, "/api/batches/" + batch1Id + "/status", operatorBearer,
                new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("99.4000")), 200);
        assertSingleEntityAuditActor("BATCH", batch1Id, "BATCH_COMPLETED", "line.operator");

        performWithSpoofedAttribution(
                HttpMethod.PATCH, "/api/batches/" + batch2Id + "/status", operatorBearer,
                new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS), 200);
        assertSingleEntityAuditActor("BATCH", batch2Id, "BATCH_STARTED", "line.operator");
        BatchExecutionStep step2 = executionStepRepository.findByBatchIdOrderByStepNumberAsc(batch2Id).get(0);
        performWithSpoofedAttribution(
                HttpMethod.PATCH, "/api/batches/" + batch2Id + "/steps/" + step2.getId() + "/start",
                operatorBearer, null, 200);
        performWithSpoofedAttribution(
                HttpMethod.PATCH, "/api/batches/" + batch2Id + "/steps/" + step2.getId() + "/complete",
                operatorBearer, new CompleteExecutionStepRequestDto("25 RPM confirmed", "Verified"), 200);
        BatchExecutionStep completedStep2 = executionStepRepository.findById(step2.getId()).orElseThrow();
        assertEquals("line.operator", completedStep2.getExecutedBy());
        performWithSpoofedAttribution(
                HttpMethod.PATCH, "/api/batches/" + batch2Id + "/status", supBearer,
                new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("99.1000")), 200);
        assertSingleEntityAuditActor("BATCH", batch2Id, "BATCH_COMPLETED", "prod.sup");
        assertSingleEntityAuditActor("MANUFACTURING_ORDER", orderId, "MANUFACTURING_ORDER_COMPLETED", "prod.sup");

        // 5. Quality Control (Qc.Analyst -> qc.analyst)
        JsonNode createdQc = performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/batches/" + batch1Id + "/checks", qcBearer,
                new QualityCheckRequestDto("Assay", "95.0% - 105.0%", "99.0", "%", QualityCheckResult.PASS, true, null, "OK"),
                201);
        long checkId = createdQc.get("id").asLong();
        QualityCheck savedQc1 = qualityCheckRepository.findById(checkId).orElseThrow();
        assertEquals("qc.analyst", savedQc1.getInspectorName());
        assertSingleEntityAuditActor("QUALITY_CHECK", checkId, "QC_CHECK_CREATED", "qc.analyst");

        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/checks/" + checkId + "/correct", qcBearer,
                new QualityCheckCorrectionRequestDto(
                        "Assay", "95.0% - 105.0%", "99.3", "%", QualityCheckResult.PASS, true,
                        "Chromatogram recalc", "Corrected rounding"),
                200);
        QualityCheck correctedQc = qualityCheckRepository.findById(checkId).orElseThrow();
        assertEquals("qc.analyst", correctedQc.getCorrectedBy());
        assertSingleEntityAuditActor("QUALITY_CHECK", checkId, "QC_CHECK_CORRECTED", "qc.analyst");

        // 6. Deviation & CAPA Lifecycle + Preserved CapaAction.ownerName + Release Gate Integrity
        JsonNode createdDev = performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/deviations", operatorBearer,
                new CreateDeviationRequestDto(
                        batch1Id, checkId, step1.getId(), null,
                        "Blender torque alarm",
                        DeviationCategory.EQUIPMENT_MALFUNCTION, DeviationSeverity.MAJOR, DeviationPriority.HIGH,
                        "Momentary torque spike at minute 12", "Inspected impeller", null),
                201);
        long devId = createdDev.get("id").asLong();
        assertEquals("line.operator", deviationRepository.findById(devId).orElseThrow().getReportedBy());
        assertSingleEntityAuditActor("DEVIATION", devId, "DEVIATION_REPORTED", "line.operator");

        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/deviations/" + devId + "/start-investigation", qcBearer,
                new StartInvestigationRequestDto("Inspecting VFD drive logs"), 200);
        assertEquals("qc.analyst", deviationRepository.findById(devId).orElseThrow().getInvestigatedBy());
        assertSingleEntityAuditActor("DEVIATION", devId, "DEVIATION_INVESTIGATION_STARTED", "qc.analyst");

        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/deviations/" + devId + "/record-root-cause", supBearer,
                new RecordRootCauseRequestDto("Bearing Wear", "Drive bearing slightly stiff", "No product contamination", null),
                200);
        assertEquals("prod.sup", deviationRepository.findById(devId).orElseThrow().getRootCauseIdentifiedBy());
        assertSingleEntityAuditActor("DEVIATION", devId, "DEVIATION_ROOT_CAUSE_RECORDED", "prod.sup");

        // Create two CAPAs: business domain field ownerName ("Eng. Kumar") must be preserved while createdBy is JWT user
        LocalDate dueDate = today.plusDays(10);
        JsonNode capa1Node = performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/deviations/" + devId + "/capas", supBearer,
                new CreateCapaActionRequestDto(
                        CapaType.CORRECTIVE, "Replace drive bearing", "Install new SKF bearing and test run",
                        "Eng. Kumar", dueDate),
                201);
        long capa1Id = capa1Node.get("id").asLong();
        CapaAction savedCapa1 = capaActionRepository.findById(capa1Id).orElseThrow();
        assertEquals("prod.sup", savedCapa1.getCreatedBy());
        assertEquals("Eng. Kumar", savedCapa1.getOwnerName(), "Domain ownerName must remain editable/preserved");
        assertSingleEntityAuditActor("CAPA_ACTION", capa1Id, "CAPA_CREATED", "prod.sup");

        JsonNode capa2Node = performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/deviations/" + devId + "/capas", supBearer,
                new CreateCapaActionRequestDto(
                        CapaType.PREVENTIVE, "Redundant grease check", "Temporary daily check",
                        "Eng. Kumar", dueDate),
                201);
        long capa2Id = capa2Node.get("id").asLong();

        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/deviations/" + devId + "/start-capa", qaBearer,
                new StartCapaRequestDto("Proceed with CAPA execution"), 200);
        assertSingleEntityAuditActor("DEVIATION", devId, "DEVIATION_CAPA_STARTED", "qa.reviewer");

        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/capas/" + capa1Id + "/start", qcBearer,
                new StartCapaActionRequestDto("Spare parts issued"), 200);
        assertSingleEntityAuditActor("CAPA_ACTION", capa1Id, "CAPA_STARTED", "qc.analyst");

        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/capas/" + capa1Id + "/complete", supBearer,
                new CompleteCapaActionRequestDto("Bearing replaced, 30-min trial normal"), 200);
        CapaAction completedCapa1 = capaActionRepository.findById(capa1Id).orElseThrow();
        assertEquals("prod.sup", completedCapa1.getCompletedBy());
        assertSingleEntityAuditActor("CAPA_ACTION", capa1Id, "CAPA_COMPLETED", "prod.sup");

        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/capas/" + capa2Id + "/cancel", qaBearer,
                new CancelCapaActionRequestDto("Covered by monthly PM schedule"), 200);
        assertSingleEntityAuditActor("CAPA_ACTION", capa2Id, "CAPA_CANCELLED", "qa.reviewer");

        // Open/unclosed deviation MUST still block QA batch release
        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/batches/" + batch1Id + "/disposition", qaBearer,
                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Attempt before closure"), 400);
        assertEquals(0, batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(batch1Id).size());

        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/deviations/" + devId + "/submit-verification", qcBearer,
                new SubmitVerificationRequestDto("All CAPAs complete and documented"), 200);
        assertSingleEntityAuditActor("DEVIATION", devId, "DEVIATION_SUBMITTED_FOR_VERIFICATION", "qc.analyst");

        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/deviations/" + devId + "/reopen-capa", qaBearer,
                new ReopenCapaRequestDto("Attach vibration baseline graph"), 200);
        assertSingleEntityAuditActor("DEVIATION", devId, "DEVIATION_CAPA_REOPENED", "qa.reviewer");

        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/deviations/" + devId + "/submit-verification", supBearer,
                new SubmitVerificationRequestDto("Vibration baseline attached"), 200);
        assertSingleEntityAuditActor("DEVIATION", devId, "DEVIATION_SUBMITTED_FOR_VERIFICATION", "prod.sup");

        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/deviations/" + devId + "/close", qaBearer,
                new CloseDeviationRequestDto("CAPA effectiveness confirmed"), 200);

        Deviation closedDev = deviationRepository.findById(devId).orElseThrow();
        assertEquals(DeviationStatus.CLOSED, closedDev.getStatus());
        assertEquals("qa.reviewer", closedDev.getVerifiedBy());
        assertEquals("qa.reviewer", closedDev.getClosedBy());
        assertSingleEntityAuditActor("DEVIATION", devId, "DEVIATION_CLOSED", "qa.reviewer");

        // Closing deviation NEVER auto-releases the batch
        Batch batchAfterDevClose = batchRepository.findById(batch1Id).orElseThrow();
        assertTrue(batchAfterDevClose.getQaStatus() == null || batchAfterDevClose.getQaStatus() == BatchQualityStatus.PENDING_QA);

        // 7. QA Batch Disposition (Qa.Reviewer -> qa.reviewer): ON_HOLD then RELEASED
        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/batches/" + batch1Id + "/disposition", qaBearer,
                new QaDispositionRequestDto(BatchQualityStatus.ON_HOLD, "Holding pending final pack check"), 200);
        performWithSpoofedAttribution(
                HttpMethod.POST, "/api/quality/batches/" + batch1Id + "/disposition", qaBearer,
                new QaDispositionRequestDto(BatchQualityStatus.RELEASED, "Pack check verified and approved"), 200);

        Batch releasedBatch = batchRepository.findById(batch1Id).orElseThrow();
        assertEquals(BatchQualityStatus.RELEASED, releasedBatch.getQaStatus());

        List<AuditLog> dispositionAudits = auditLogRepository.findByBatchIdOrderByOccurredAtDescIdDesc(batch1Id)
                .stream()
                .filter(log -> "BATCH_QA_DISPOSITION".equals(log.getEntityType()))
                .toList();
        assertEquals(2, dispositionAudits.size());
        assertEquals("qa.reviewer", dispositionAudits.get(0).getActorName());
        assertEquals("qa.reviewer", dispositionAudits.get(1).getActorName());

        List<BatchQaReview> reviews = batchQaReviewRepository.findByBatchIdOrderByReviewedAtDescIdDesc(batch1Id);
        assertEquals(2, reviews.size());
        assertEquals("qa.reviewer", reviews.get(0).getReviewerName());
        assertEquals("qa.reviewer", reviews.get(1).getReviewerName());

        // Global verification: NO AuditLog anywhere in database ever recorded spoofed, un-normalized, or SYSTEM actor names
        List<AuditLog> allLogs = auditLogRepository.findAllByOrderByOccurredAtDescIdDesc();
        assertFalse(allLogs.isEmpty());
        for (AuditLog log : allLogs) {
            assertNotNull(log.getActorName());
            assertFalse(log.getActorName().contains(FORGED_HEADER), "Header spoof leaked into " + log.getAction());
            assertFalse(log.getActorName().contains(FORGED_BODY), "Body spoof leaked into " + log.getAction());
            assertNotEquals("SYSTEM", log.getActorName(), "SYSTEM placeholder leaked into " + log.getAction());
            assertEquals(log.getActorName().toLowerCase(), log.getActorName(),
                    "Actor name must be normalized lowercase on " + log.getAction());
        }
    }

    @Test
    @DisplayName("2. Transaction atomicity: AuditTrailService failure rolls back both entity mutation and audit entry across HTTP requests")
    void auditFailureRollsBackTransactionAtomically() throws Exception {
        // Seed a baseline product as Admin.User
        ProductCreateRequestDto createDto = new ProductCreateRequestDto(
                "TR-RB-100", "Rollback Verification Product", "100 mg", "Capsule", "Baseline", ProductStatus.ACTIVE
        );
        JsonNode created = performWithSpoofedAttribution(
                HttpMethod.POST, "/api/products", adminBearer, createDto, 201);
        long productId = created.get("id").asLong();

        long auditsBefore = auditLogRepository.count();

        doThrow(new RuntimeException("Simulated audit log persistence outage"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(request(HttpMethod.PATCH, "/api/products/" + productId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Actor-Name", FORGED_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withForgedActorFields(new StatusUpdateRequestDto(ProductStatus.INACTIVE))))
                .andExpect(status().isInternalServerError());

        Product unchanged = productRepository.findById(productId).orElseThrow();
        assertEquals(ProductStatus.ACTIVE, unchanged.getStatus());
        assertEquals(auditsBefore, auditLogRepository.count());
    }
}
