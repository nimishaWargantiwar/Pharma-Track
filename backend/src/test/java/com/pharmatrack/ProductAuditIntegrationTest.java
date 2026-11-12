package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.ProductCreateRequestDto;
import com.pharmatrack.dto.StatusUpdateRequestDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.JwtService;
import com.pharmatrack.service.AuditTrailService;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Isolated Spring Boot + H2 + MockMvc integration tests verifying comprehensive
 * append-only audit trail coverage, before/after snapshots, queryability through
 * {@code /api/quality/audit-logs}, error non-auditing, and full transaction rollback
 * for Product Master operations.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_product_audit_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductAuditIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @SpyBean
    private AuditTrailService auditTrailService;

    @AfterEach
    void resetSpy() {
        Mockito.reset(auditTrailService);
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

    private Product newSampleProduct(String tag, ProductStatus status) {
        int n = SEQ.getAndIncrement();
        return new Product(
                "PA-PRD-" + tag + "-" + n,
                "Audit Test Product " + tag + " " + n,
                "500 mg",
                "Tablet",
                "Initial formulation " + n,
                status
        );
    }

    @Test
    @DisplayName("1. Full Product Master lifecycle (create, update, deactivate, activate) persists accurate audit logs queryable via Quality Audit API")
    void fullProductLifecycleRecordsAccurateAuditTrail() throws Exception {
        String creatorBearer = bearerFor("admin.creator", Role.ADMIN);
        String patelBearer = bearerFor("Dr.Patel", Role.ADMIN);
        String qaAdminBearer = bearerFor("admin.deactivator", Role.ADMIN);
        String opsBearer = bearerFor("ops.admin", Role.ADMIN);
        String qaViewerBearer = bearerFor("qa.reviewer", Role.QA_REVIEWER);

        // 1. Create product -> attributed to authenticated admin user
        Product createPayload = newSampleProduct("LIFE", ProductStatus.ACTIVE);
        String originalCode = createPayload.getProductCode();

        MvcResult createResult = mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, creatorBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productCode").value(originalCode))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        Long productId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        // 2. Update product details & code with forged X-Actor-Name header -> header ignored, JWT user wins
        String updatedCode = originalCode + "-REV";
        Product updatePayload = new Product(
                updatedCode,
                "Audit Test Product Revised",
                "650 mg",
                "Capsule",
                "Updated capsule formulation",
                ProductStatus.ACTIVE
        );

        mockMvc.perform(put("/api/products/{id}", productId)
                        .header(HttpHeaders.AUTHORIZATION, patelBearer)
                        .header("X-Actor-Name", "Spoofed Header")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value(updatedCode))
                .andExpect(jsonPath("$.productName").value("Audit Test Product Revised"))
                .andExpect(jsonPath("$.strength").value("650 mg"))
                .andExpect(jsonPath("$.dosageForm").value("Capsule"));

        // 3. No-op update should keep HTTP 200 and add zero audit entries
        mockMvc.perform(put("/api/products/{id}", productId)
                        .header(HttpHeaders.AUTHORIZATION, patelBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatePayload)))
                .andExpect(status().isOk());

        // 4. Deactivate product via PATCH (even with forged body actorName -> ignored, JWT user wins)
        mockMvc.perform(patch("/api/products/{id}/status", productId)
                        .header(HttpHeaders.AUTHORIZATION, qaAdminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\",\"actorName\":\"Forged Body Actor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        // 5. Idempotent no-op status PATCH should keep HTTP 200 and add zero audit entries
        mockMvc.perform(patch("/api/products/{id}/status", productId)
                        .header(HttpHeaders.AUTHORIZATION, qaAdminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StatusUpdateRequestDto(ProductStatus.INACTIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        // 6. Reactivate product via PATCH even with oversized forged X-Actor-Name -> succeeds and records normalized JWT user
        mockMvc.perform(patch("/api/products/{id}/status", productId)
                        .header(HttpHeaders.AUTHORIZATION, opsBearer)
                        .header("X-Actor-Name", "X".repeat(120))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StatusUpdateRequestDto(ProductStatus.ACTIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Query via existing Quality audit-logs endpoint
        MvcResult auditResult = mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaViewerBearer)
                        .param("entityType", "PRODUCT")
                        .param("entityId", String.valueOf(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andReturn();

        JsonNode logs = objectMapper.readTree(auditResult.getResponse().getContentAsString());

        // Newest-first order: PRODUCT_ACTIVATED -> PRODUCT_DEACTIVATED -> PRODUCT_UPDATED -> PRODUCT_CREATED
        JsonNode activatedLog = logs.get(0);
        assertEquals("PRODUCT", activatedLog.get("entityType").asText());
        assertEquals(productId, activatedLog.get("entityId").asLong());
        assertEquals(updatedCode, activatedLog.get("entityReference").asText());
        assertTrue(activatedLog.get("batchId").isNull());
        assertEquals("PRODUCT_ACTIVATED", activatedLog.get("action").asText());
        assertEquals("ops.admin", activatedLog.get("actorName").asText());
        assertFalse(activatedLog.get("occurredAt").isNull());
        assertEquals("INACTIVE", objectMapper.readTree(activatedLog.get("beforeValues").asText()).get("status").asText());
        assertEquals("ACTIVE", objectMapper.readTree(activatedLog.get("afterValues").asText()).get("status").asText());

        JsonNode deactivatedLog = logs.get(1);
        assertEquals("PRODUCT_DEACTIVATED", deactivatedLog.get("action").asText());
        assertEquals("admin.deactivator", deactivatedLog.get("actorName").asText());
        assertEquals("ACTIVE", objectMapper.readTree(deactivatedLog.get("beforeValues").asText()).get("status").asText());
        assertEquals("INACTIVE", objectMapper.readTree(deactivatedLog.get("afterValues").asText()).get("status").asText());

        JsonNode updatedLog = logs.get(2);
        assertEquals("PRODUCT_UPDATED", updatedLog.get("action").asText());
        assertEquals("dr.patel", updatedLog.get("actorName").asText());
        assertEquals(updatedCode, updatedLog.get("entityReference").asText());
        JsonNode updateBefore = objectMapper.readTree(updatedLog.get("beforeValues").asText());
        JsonNode updateAfter = objectMapper.readTree(updatedLog.get("afterValues").asText());
        assertEquals(originalCode, updateBefore.get("productCode").asText());
        assertEquals("500 mg", updateBefore.get("strength").asText());
        assertEquals("Tablet", updateBefore.get("dosageForm").asText());
        assertEquals(updatedCode, updateAfter.get("productCode").asText());
        assertEquals("Audit Test Product Revised", updateAfter.get("productName").asText());
        assertEquals("650 mg", updateAfter.get("strength").asText());
        assertEquals("Capsule", updateAfter.get("dosageForm").asText());
        assertEquals("Updated capsule formulation", updateAfter.get("description").asText());

        JsonNode createdLog = logs.get(3);
        assertEquals("PRODUCT_CREATED", createdLog.get("action").asText());
        assertEquals("admin.creator", createdLog.get("actorName").asText());
        assertEquals(originalCode, createdLog.get("entityReference").asText());
        assertTrue(createdLog.get("beforeValues").isNull());
        JsonNode createdAfter = objectMapper.readTree(createdLog.get("afterValues").asText());
        assertEquals(originalCode, createdAfter.get("productCode").asText());
        assertEquals("ACTIVE", createdAfter.get("status").asText());
    }

    @Test
    @DisplayName("2. PUT changing details and status simultaneously records chained update and deactivation audit events")
    void updateChangingDetailsAndStatusRecordsContinuousSnapshotChain() throws Exception {
        String regAdminBearer = bearerFor("Reg.Admin", Role.ADMIN);
        Product initial = productRepository.saveAndFlush(newSampleProduct("MIXED", ProductStatus.ACTIVE));

        Product mixedUpdate = new Product(
                initial.getProductCode(),
                "Updated Mixed Product",
                "750 mg",
                "Tablet",
                "Deactivated during revision",
                ProductStatus.INACTIVE
        );

        mockMvc.perform(put("/api/products/{id}", initial.getId())
                        .header(HttpHeaders.AUTHORIZATION, regAdminBearer)
                        .header("X-Actor-Name", "Regulatory QA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mixedUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("Updated Mixed Product"))
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        List<AuditLog> logs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                "PRODUCT", initial.getId());
        assertEquals(2, logs.size());

        AuditLog latest = logs.get(0);
        AuditLog earlier = logs.get(1);
        assertEquals("PRODUCT_DEACTIVATED", latest.getAction());
        assertEquals("PRODUCT_UPDATED", earlier.getAction());
        assertEquals("reg.admin", latest.getActorName());
        assertEquals("reg.admin", earlier.getActorName());

        JsonNode updateBefore = objectMapper.readTree(earlier.getBeforeValues());
        JsonNode updateAfter = objectMapper.readTree(earlier.getAfterValues());
        JsonNode deactivateBefore = objectMapper.readTree(latest.getBeforeValues());
        JsonNode deactivateAfter = objectMapper.readTree(latest.getAfterValues());

        assertEquals("ACTIVE", updateBefore.get("status").asText());
        assertEquals("ACTIVE", updateAfter.get("status").asText());
        assertEquals("Updated Mixed Product", updateAfter.get("productName").asText());
        assertEquals(updateAfter, deactivateBefore, "After-update snapshot must match before-deactivation snapshot");
        assertEquals("INACTIVE", deactivateAfter.get("status").asText());
    }

    @Test
    @DisplayName("3. Failed or unauthenticated create, update, and status operations produce zero audit records and keep DB unchanged")
    void failedProductOperationsLeaveZeroAuditLogs() throws Exception {
        String adminBearer = bearerFor("admin.failcheck", Role.ADMIN);
        Product existing1 = productRepository.saveAndFlush(newSampleProduct("FAIL1", ProductStatus.ACTIVE));
        Product existing2 = productRepository.saveAndFlush(newSampleProduct("FAIL2", ProductStatus.ACTIVE));
        long productsBefore = productRepository.count();
        long auditsBefore = auditLogRepository.count();

        // A. Validation failure on create (400)
        mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // B. Duplicate code on create (409)
        Product dupCreate = new Product(existing1.getProductCode(), "Duplicate", "100 mg", "Tablet", null, ProductStatus.ACTIVE);
        mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupCreate)))
                .andExpect(status().isConflict());

        // C. Unauthenticated create with forged X-Actor-Name (401)
        Product validNew = newSampleProduct("FAILANON", ProductStatus.ACTIVE);
        mockMvc.perform(post("/api/products")
                        .header("X-Actor-Name", "Spoofed Unauthenticated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validNew)))
                .andExpect(status().isUnauthorized());

        // D. Duplicate code collision on update (409)
        Product dupUpdate = new Product(existing2.getProductCode(), "Stolen Code Name", "250 mg", "Capsule", null, ProductStatus.INACTIVE);
        mockMvc.perform(put("/api/products/{id}", existing1.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupUpdate)))
                .andExpect(status().isConflict());

        // E. Missing product on update & status change (404)
        mockMvc.perform(put("/api/products/{id}", 999999L)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validNew)))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/products/{id}/status", 999999L)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StatusUpdateRequestDto(ProductStatus.INACTIVE))))
                .andExpect(status().isNotFound());

        // F. Invalid status validation failure on PATCH (400)
        mockMvc.perform(patch("/api/products/{id}/status", existing1.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": null}"))
                .andExpect(status().isBadRequest());

        assertEquals(productsBefore, productRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());

        Product reloaded1 = productRepository.findById(existing1.getId()).orElseThrow();
        assertEquals(existing1.getProductCode(), reloaded1.getProductCode());
        assertEquals(existing1.getProductName(), reloaded1.getProductName());
        assertEquals(ProductStatus.ACTIVE, reloaded1.getStatus());
    }

    @Test
    @DisplayName("4. Audit persistence failure rolls back product create, update, mixed update, and status change completely")
    void auditPersistenceFailureRollsBackProductTransactions() throws Exception {
        String adminBearer = bearerFor("admin.rollback", Role.ADMIN);

        // Baseline product persisted cleanly
        Product baseline = productRepository.saveAndFlush(newSampleProduct("RB", ProductStatus.ACTIVE));
        String originalCode = baseline.getProductCode();
        String originalName = baseline.getProductName();
        String originalStrength = baseline.getStrength();
        LocalDateTime originalUpdatedAt = baseline.getUpdatedAt();

        long productsBaseline = productRepository.count();
        long auditsBaseline = auditLogRepository.count();

        // A. Rollback on create failure
        doThrow(new RuntimeException("Injected audit failure on create"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        Product createTarget = newSampleProduct("RBPOTENTIAL", ProductStatus.ACTIVE);
        mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createTarget)))
                .andExpect(status().isInternalServerError());

        assertEquals(productsBaseline, productRepository.count());
        assertEquals(auditsBaseline, auditLogRepository.count());
        assertTrue(productRepository.findByProductCode(createTarget.getProductCode()).isEmpty());

        // B. Rollback on update failure
        Product updateAttempt = new Product(
                originalCode + "-ROLLEDBACK",
                "Rolled Back Name",
                "999 mg",
                "Syrup",
                "Should never persist",
                ProductStatus.ACTIVE
        );
        mockMvc.perform(put("/api/products/{id}", baseline.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateAttempt)))
                .andExpect(status().isInternalServerError());

        Product afterFailedUpdate = productRepository.findById(baseline.getId()).orElseThrow();
        assertEquals(originalCode, afterFailedUpdate.getProductCode());
        assertEquals(originalName, afterFailedUpdate.getProductName());
        assertEquals(originalStrength, afterFailedUpdate.getStrength());
        assertEquals(ProductStatus.ACTIVE, afterFailedUpdate.getStatus());
        assertEquals(originalUpdatedAt, afterFailedUpdate.getUpdatedAt());
        assertEquals(auditsBaseline, auditLogRepository.count());

        // C. Rollback on mixed update when 2nd audit call fails (1st audit + product mutation both roll back)
        Mockito.reset(auditTrailService);
        doCallRealMethod()
                .doThrow(new RuntimeException("Injected audit failure on second audit write"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        Product mixedRollbackAttempt = new Product(
                originalCode,
                "Mixed Rollback Name",
                "888 mg",
                "Tablet",
                "Mixed failure",
                ProductStatus.INACTIVE
        );
        mockMvc.perform(put("/api/products/{id}", baseline.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mixedRollbackAttempt)))
                .andExpect(status().isInternalServerError());

        Product afterFailedMixedUpdate = productRepository.findById(baseline.getId()).orElseThrow();
        assertEquals(originalName, afterFailedMixedUpdate.getProductName());
        assertEquals(ProductStatus.ACTIVE, afterFailedMixedUpdate.getStatus());
        assertEquals(auditsBaseline, auditLogRepository.count());

        // D. Rollback on PATCH status change failure
        Mockito.reset(auditTrailService);
        doThrow(new RuntimeException("Injected audit failure on status change"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(patch("/api/products/{id}/status", baseline.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StatusUpdateRequestDto(ProductStatus.INACTIVE))))
                .andExpect(status().isInternalServerError());

        Product afterFailedStatusChange = productRepository.findById(baseline.getId()).orElseThrow();
        assertEquals(ProductStatus.ACTIVE, afterFailedStatusChange.getStatus());
        assertEquals(auditsBaseline, auditLogRepository.count());

        // E. Recovery succeeds cleanly once audit failure clears
        Mockito.reset(auditTrailService);
        mockMvc.perform(patch("/api/products/{id}/status", baseline.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StatusUpdateRequestDto(ProductStatus.INACTIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        assertEquals(auditsBaseline + 1, auditLogRepository.count());
        List<AuditLog> productLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                "PRODUCT", baseline.getId());
        assertEquals(1, productLogs.size());
        assertEquals("PRODUCT_DEACTIVATED", productLogs.get(0).getAction());
    }

    @Test
    @DisplayName("5. Product audit records remain append-only and read-only over HTTP")
    void productAuditLogsAreAppendOnlyOverHttp() throws Exception {
        String adminBearer = bearerFor("admin.immut", Role.ADMIN);
        String qaViewerBearer = bearerFor("qa.viewer.immut", Role.QA_REVIEWER);

        Product created = productRepository.saveAndFlush(newSampleProduct("IMMUT", ProductStatus.ACTIVE));
        mockMvc.perform(patch("/api/products/{id}/status", created.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StatusUpdateRequestDto(ProductStatus.INACTIVE))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaViewerBearer)
                        .param("entityType", "PRODUCT")
                        .param("entityId", String.valueOf(created.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("PRODUCT_DEACTIVATED"));

        mockMvc.perform(put("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("6. POST /api/products supplying an existing product ID is rejected with HTTP 400 and never overwrites existing record or writes audit")
    void postProductWithExistingIdCannotOverwriteExistingProduct() throws Exception {
        String adminBearer = bearerFor("admin.merge", Role.ADMIN);
        Product baseline = productRepository.saveAndFlush(newSampleProduct("NO_MERGE", ProductStatus.ACTIVE));
        String originalCode = baseline.getProductCode();
        String originalName = baseline.getProductName();
        String originalStrength = baseline.getStrength();
        String originalDosageForm = baseline.getDosageForm();
        String originalDescription = baseline.getDescription();
        LocalDateTime originalUpdatedAt = baseline.getUpdatedAt();

        long productsBefore = productRepository.count();
        long auditsBefore = auditLogRepository.count();

        // Attempt 1: Existing ID + brand-new product code (classic JPA merge overwrite vector)
        ProductCreateRequestDto hijackWithNewCode = new ProductCreateRequestDto(
                baseline.getId(),
                originalCode + "-HIJACKED",
                "Hijacked Product Name",
                "999 mg",
                "Injection",
                "Overwritten via merge",
                ProductStatus.INACTIVE
        );

        mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Actor-Name", "Attacker")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hijackWithNewCode)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.id").exists());

        // Attempt 2: Existing ID + same product code
        ProductCreateRequestDto hijackSameCode = new ProductCreateRequestDto(
                baseline.getId(),
                originalCode,
                "Overwritten Same Code",
                "250 mg",
                "Syrup",
                "Overwritten details",
                ProductStatus.INACTIVE
        );

        mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hijackSameCode)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.id").exists());

        // Verify baseline record is completely untouched, count unchanged, and zero audits added
        assertEquals(productsBefore, productRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());

        Product reloaded = productRepository.findById(baseline.getId()).orElseThrow();
        assertEquals(originalCode, reloaded.getProductCode());
        assertEquals(originalName, reloaded.getProductName());
        assertEquals(originalStrength, reloaded.getStrength());
        assertEquals(originalDosageForm, reloaded.getDosageForm());
        assertEquals(originalDescription, reloaded.getDescription());
        assertEquals(ProductStatus.ACTIVE, reloaded.getStatus());
        assertEquals(originalUpdatedAt, reloaded.getUpdatedAt());
        assertTrue(auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                "PRODUCT", baseline.getId()).isEmpty());
    }
}

