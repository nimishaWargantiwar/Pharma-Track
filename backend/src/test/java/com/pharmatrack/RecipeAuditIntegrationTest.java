package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.RecipeMaterialDto;
import com.pharmatrack.dto.RecipeRequestDto;
import com.pharmatrack.dto.RecipeStatusUpdateDto;
import com.pharmatrack.dto.RecipeStepDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeMaterial;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.RecipeRepository;
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

import java.math.BigDecimal;
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
 * append-only audit trail coverage, full recipe snapshots (materials & steps),
 * queryability through {@code /api/quality/audit-logs}, failure non-auditing,
 * and complete transaction rollback for Recipe Management operations.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_recipe_audit_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecipeAuditIntegrationTest {

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

    private Product seedProduct(String tag, ProductStatus status) {
        int n = SEQ.getAndIncrement();
        return productRepository.saveAndFlush(new Product(
                "RA-PRD-" + tag + "-" + n,
                "Recipe Audit Product " + tag + " " + n,
                "500 mg",
                "Tablet",
                "Formulation audit test product " + n,
                status
        ));
    }

    private Recipe seedRecipe(Product product, int version, RecipeStatus status, String tag) {
        Recipe recipe = new Recipe(
                "RCP-" + product.getProductCode() + "-V" + version,
                product,
                version,
                new BigDecimal("500.0000"),
                "kg",
                status,
                "Seeded recipe " + tag
        );
        recipe.addMaterial(new RecipeMaterial("Seed API " + tag, new BigDecimal("250.0000"), "kg"));
        recipe.addMaterial(new RecipeMaterial("Seed Binder " + tag, new BigDecimal("50.0000"), "kg"));
        recipe.addStep(new RecipeStep(1, "Dispensing", "Dispense materials for " + tag));
        recipe.addStep(new RecipeStep(2, "Blending", "Blend dry powders for " + tag));
        return recipeRepository.saveAndFlush(recipe);
    }

    @Test
    @DisplayName("1. Full Recipe lifecycle (create, update, approve, clone version, supersede/obsolete, direct obsolete, delete) records accurate audit trail")
    void fullRecipeLifecycleRecordsAccurateAuditTrail() throws Exception {
        String supBearer = bearerFor("prod.sup", Role.PRODUCTION_SUPERVISOR);
        String kapoorBearer = bearerFor("Dr.Kapoor", Role.PRODUCTION_SUPERVISOR);
        String releaseLeadBearer = bearerFor("qa.release.lead", Role.QA_REVIEWER);
        String processEngBearer = bearerFor("process.eng", Role.PRODUCTION_SUPERVISOR);
        String formulationLeadBearer = bearerFor("formulation.lead", Role.PRODUCTION_SUPERVISOR);
        String qaDirectorBearer = bearerFor("qa.director", Role.QA_REVIEWER);
        String complianceBearer = bearerFor("compliance.qa", Role.QA_REVIEWER);
        String rdChemistBearer = bearerFor("rd.chemist", Role.PRODUCTION_SUPERVISOR);
        String cleanupSupBearer = bearerFor("prod.cleanup", Role.PRODUCTION_SUPERVISOR);

        Product product = seedProduct("LIFE", ProductStatus.ACTIVE);

        // 1. Create v1 -> attributed to authenticated supervisor
        RecipeRequestDto createV1 = new RecipeRequestDto(
                product.getId(),
                new BigDecimal("1000.0000"),
                "kg",
                "Initial v1 recipe",
                List.of(new RecipeMaterialDto("Paracetamol API", new BigDecimal("500.0000"), "kg")),
                List.of(
                        new RecipeStepDto(1, "Dispensing", "Weigh API accurately."),
                        new RecipeStepDto(2, "Blending", "Blend powders for 15 minutes.")
                )
        );

        MvcResult createResult = mockMvc.perform(post("/api/recipes")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createV1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();

        JsonNode createdV1Json = objectMapper.readTree(createResult.getResponse().getContentAsString());
        long v1Id = createdV1Json.get("id").asLong();
        String v1Code = createdV1Json.get("recipeCode").asText();

        // 2. Update v1 materials, steps (out-of-order in payload to test deterministic step sorting); forged X-Actor-Name ignored
        RecipeRequestDto updateV1 = new RecipeRequestDto(
                product.getId(),
                new BigDecimal("1500.0000"),
                "kg",
                "Updated v1 master recipe",
                List.of(
                        new RecipeMaterialDto("Paracetamol API", new BigDecimal("750.0000"), "kg"),
                        new RecipeMaterialDto("Microcrystalline Cellulose", new BigDecimal("250.5000"), "kg")
                ),
                List.of(
                        new RecipeStepDto(3, "Compression", "Compress into 500mg tablets."),
                        new RecipeStepDto(1, "Dispensing", "Dispense API and MCC under cleanroom airflow."),
                        new RecipeStepDto(2, "Granulation", "Wet granulate for 20 minutes.")
                )
        );

        mockMvc.perform(put("/api/recipes/{id}", v1Id)
                        .header(HttpHeaders.AUTHORIZATION, kapoorBearer)
                        .header("X-Actor-Name", "Forged Kapoor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateV1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materials.length()").value(2))
                .andExpect(jsonPath("$.steps.length()").value(3));

        // 3. No-op update with equivalent scale (1500 vs 1500.0000) and reordered step array emits zero extra audits
        RecipeRequestDto noOpUpdateV1 = new RecipeRequestDto(
                product.getId(),
                new BigDecimal("1500"),
                "kg",
                "Updated v1 master recipe",
                List.of(
                        new RecipeMaterialDto("Paracetamol API", new BigDecimal("750"), "kg"),
                        new RecipeMaterialDto("Microcrystalline Cellulose", new BigDecimal("250.5"), "kg")
                ),
                List.of(
                        new RecipeStepDto(2, "Granulation", "Wet granulate for 20 minutes."),
                        new RecipeStepDto(3, "Compression", "Compress into 500mg tablets."),
                        new RecipeStepDto(1, "Dispensing", "Dispense API and MCC under cleanroom airflow.")
                )
        );

        mockMvc.perform(put("/api/recipes/{id}", v1Id)
                        .header(HttpHeaders.AUTHORIZATION, kapoorBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noOpUpdateV1)))
                .andExpect(status().isOk());

        // 4. Approve v1 with forged body actorName -> ignored, JWT QA user wins
        mockMvc.perform(patch("/api/recipes/{id}/status", v1Id)
                        .header(HttpHeaders.AUTHORIZATION, releaseLeadBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\",\"actorName\":\"Forged Body Lead\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 5. No-op PATCH APPROVED -> APPROVED keeps 200 OK and writes zero extra audit logs
        mockMvc.perform(patch("/api/recipes/{id}/status", v1Id)
                        .header(HttpHeaders.AUTHORIZATION, releaseLeadBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 6. Create new version v2 from approved v1; forged X-Actor-Name ignored
        MvcResult newVerResult = mockMvc.perform(post("/api/recipes/{id}/new-version", v1Id)
                        .header(HttpHeaders.AUTHORIZATION, processEngBearer)
                        .header("X-Actor-Name", "Process Engineer"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();

        JsonNode v2Json = objectMapper.readTree(newVerResult.getResponse().getContentAsString());
        long v2Id = v2Json.get("id").asLong();
        String v2Code = v2Json.get("recipeCode").asText();

        // 7. Edit v2
        RecipeRequestDto updateV2 = new RecipeRequestDto(
                product.getId(),
                new BigDecimal("2000.0000"),
                "kg",
                "Scaled up v2 formulation",
                List.of(
                        new RecipeMaterialDto("Paracetamol API", new BigDecimal("1000.0000"), "kg"),
                        new RecipeMaterialDto("Starch Binder", new BigDecimal("150.0000"), "kg")
                ),
                List.of(
                        new RecipeStepDto(1, "Dispensing", "Dispense scaled quantities."),
                        new RecipeStepDto(2, "Blending", "Blend at 30 RPM for 25 minutes.")
                )
        );

        mockMvc.perform(put("/api/recipes/{id}", v2Id)
                        .header(HttpHeaders.AUTHORIZATION, formulationLeadBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateV2)))
                .andExpect(status().isOk());

        // 8. Approve v2 -> v1 becomes OBSOLETE (RECIPE_OBSOLETED) and v2 becomes APPROVED (RECIPE_APPROVED)
        mockMvc.perform(patch("/api/recipes/{id}/status", v2Id)
                        .header(HttpHeaders.AUTHORIZATION, qaDirectorBearer)
                        .header("X-Actor-Name", "Forged QA Director")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertEquals(RecipeStatus.OBSOLETE, recipeRepository.findById(v1Id).orElseThrow().getStatus());

        // 9. Transition v2 directly from APPROVED to OBSOLETE (oversized forged X-Actor-Name ignored cleanly)
        mockMvc.perform(patch("/api/recipes/{id}/status", v2Id)
                        .header(HttpHeaders.AUTHORIZATION, complianceBearer)
                        .header("X-Actor-Name", "X".repeat(120))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RecipeStatusUpdateDto(RecipeStatus.OBSOLETE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OBSOLETE"));

        // 10. Create draft v3 and delete it -> RECIPE_DELETED survives deletion with pre-deletion snapshot
        MvcResult v3Result = mockMvc.perform(post("/api/recipes/{id}/new-version", v2Id)
                        .header(HttpHeaders.AUTHORIZATION, rdChemistBearer)
                        .header("X-Actor-Name", "R&D Chemist"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(3))
                .andReturn();
        long v3Id = objectMapper.readTree(v3Result.getResponse().getContentAsString()).get("id").asLong();
        String v3Code = objectMapper.readTree(v3Result.getResponse().getContentAsString()).get("recipeCode").asText();

        mockMvc.perform(delete("/api/recipes/{id}", v3Id)
                        .header(HttpHeaders.AUTHORIZATION, cleanupSupBearer)
                        .header("X-Actor-Name", "QA Cleanup"))
                .andExpect(status().isNoContent());

        assertTrue(recipeRepository.findById(v3Id).isEmpty());

        // Verify v1 audit trail via /api/quality/audit-logs: OBSOLETED -> APPROVED -> UPDATED -> CREATED (4 logs)
        MvcResult v1AuditRes = mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaDirectorBearer)
                        .param("entityType", "RECIPE")
                        .param("entityId", String.valueOf(v1Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andReturn();

        JsonNode v1Logs = objectMapper.readTree(v1AuditRes.getResponse().getContentAsString());

        JsonNode v1Obsoleted = v1Logs.get(0);
        assertEquals("RECIPE_OBSOLETED", v1Obsoleted.get("action").asText());
        assertEquals("qa.director", v1Obsoleted.get("actorName").asText());
        assertEquals(v1Code, v1Obsoleted.get("entityReference").asText());
        assertTrue(v1Obsoleted.get("reason").asText().contains(v2Code));
        JsonNode v1ObsBefore = objectMapper.readTree(v1Obsoleted.get("beforeValues").asText());
        JsonNode v1ObsAfter = objectMapper.readTree(v1Obsoleted.get("afterValues").asText());
        assertEquals("APPROVED", v1ObsBefore.get("status").asText());
        assertEquals("OBSOLETE", v1ObsAfter.get("status").asText());
        assertEquals(2, v1ObsAfter.get("materials").size());
        assertEquals(3, v1ObsAfter.get("steps").size());

        JsonNode v1Approved = v1Logs.get(1);
        assertEquals("RECIPE_APPROVED", v1Approved.get("action").asText());
        assertEquals("qa.release.lead", v1Approved.get("actorName").asText());
        JsonNode v1AppBefore = objectMapper.readTree(v1Approved.get("beforeValues").asText());
        JsonNode v1AppAfter = objectMapper.readTree(v1Approved.get("afterValues").asText());
        assertEquals("DRAFT", v1AppBefore.get("status").asText());
        assertEquals("APPROVED", v1AppAfter.get("status").asText());
        assertEquals(v1AppAfter, v1ObsBefore, "v1 APPROVED after-snapshot must match v1 OBSOLETED before-snapshot");

        JsonNode v1Updated = v1Logs.get(2);
        assertEquals("RECIPE_UPDATED", v1Updated.get("action").asText());
        assertEquals("dr.kapoor", v1Updated.get("actorName").asText());
        JsonNode v1UpdBefore = objectMapper.readTree(v1Updated.get("beforeValues").asText());
        JsonNode v1UpdAfter = objectMapper.readTree(v1Updated.get("afterValues").asText());
        assertEquals(1, v1UpdBefore.get("materials").size());
        assertEquals(2, v1UpdBefore.get("steps").size());
        assertEquals(2, v1UpdAfter.get("materials").size());
        assertEquals(3, v1UpdAfter.get("steps").size());
        assertEquals(1, v1UpdAfter.get("steps").get(0).get("stepNumber").asInt());
        assertEquals(2, v1UpdAfter.get("steps").get(1).get("stepNumber").asInt());
        assertEquals(3, v1UpdAfter.get("steps").get(2).get("stepNumber").asInt());
        assertEquals(v1UpdAfter, v1AppBefore, "v1 UPDATED after-snapshot must match v1 APPROVED before-snapshot");

        JsonNode v1Created = v1Logs.get(3);
        assertEquals("RECIPE_CREATED", v1Created.get("action").asText());
        assertEquals("prod.sup", v1Created.get("actorName").asText());
        assertTrue(v1Created.get("beforeValues").isNull());
        JsonNode v1CreateAfter = objectMapper.readTree(v1Created.get("afterValues").asText());
        assertEquals(v1CreateAfter, v1UpdBefore, "v1 CREATED after-snapshot must match v1 UPDATED before-snapshot");

        // Verify v2 audit trail: OBSOLETED -> APPROVED -> UPDATED -> VERSION_CREATED (4 logs)
        MvcResult v2AuditRes = mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaDirectorBearer)
                        .param("entityType", "RECIPE")
                        .param("entityId", String.valueOf(v2Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andReturn();

        JsonNode v2Logs = objectMapper.readTree(v2AuditRes.getResponse().getContentAsString());
        assertEquals("RECIPE_OBSOLETED", v2Logs.get(0).get("action").asText());
        assertEquals("compliance.qa", v2Logs.get(0).get("actorName").asText());

        assertEquals("RECIPE_APPROVED", v2Logs.get(1).get("action").asText());
        assertEquals("qa.director", v2Logs.get(1).get("actorName").asText());
        assertTrue(v2Logs.get(1).get("reason").asText().contains("superseded " + v1Code));

        assertEquals("RECIPE_UPDATED", v2Logs.get(2).get("action").asText());
        assertEquals("formulation.lead", v2Logs.get(2).get("actorName").asText());

        assertEquals("RECIPE_VERSION_CREATED", v2Logs.get(3).get("action").asText());
        assertEquals("process.eng", v2Logs.get(3).get("actorName").asText());
        assertTrue(v2Logs.get(3).get("beforeValues").isNull());
        assertTrue(v2Logs.get(3).get("reason").asText().contains(v1Code));

        // Verify v3 audit trail survives deletion: DELETED -> VERSION_CREATED (2 logs)
        MvcResult v3AuditRes = mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaDirectorBearer)
                        .param("entityType", "RECIPE")
                        .param("entityId", String.valueOf(v3Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        JsonNode v3Logs = objectMapper.readTree(v3AuditRes.getResponse().getContentAsString());
        assertEquals("RECIPE_DELETED", v3Logs.get(0).get("action").asText());
        assertEquals("prod.cleanup", v3Logs.get(0).get("actorName").asText());
        assertEquals(v3Code, v3Logs.get(0).get("entityReference").asText());
        assertTrue(v3Logs.get(0).get("afterValues").isNull());
        JsonNode v3DeletedBefore = objectMapper.readTree(v3Logs.get(0).get("beforeValues").asText());
        assertEquals(2, v3DeletedBefore.get("materials").size());
        assertEquals(2, v3DeletedBefore.get("steps").size());
        assertEquals("DRAFT", v3DeletedBefore.get("status").asText());

        assertEquals("RECIPE_VERSION_CREATED", v3Logs.get(1).get("action").asText());
        assertEquals("rd.chemist", v3Logs.get(1).get("actorName").asText());
    }

    @Test
    @DisplayName("2. Invalid requests, unauthenticated calls, duplicate steps, inactive product approvals, illegal transitions, and missing IDs write zero audits")
    void failedRecipeOperationsLeaveZeroAuditLogs() throws Exception {
        String supBearer = bearerFor("prod.sup.fail", Role.PRODUCTION_SUPERVISOR);
        String qaBearer = bearerFor("qa.rev.fail", Role.QA_REVIEWER);

        Product activeProduct = seedProduct("FAILACT", ProductStatus.ACTIVE);
        Product otherActive = seedProduct("FAILOTH", ProductStatus.ACTIVE);
        Product inactiveProduct = seedProduct("FAILINACT", ProductStatus.INACTIVE);

        Recipe draftRecipe = seedRecipe(activeProduct, 1, RecipeStatus.DRAFT, "DRAFT1");
        Recipe approvedRecipe = seedRecipe(activeProduct, 2, RecipeStatus.APPROVED, "APP2");
        Recipe obsoleteRecipe = seedRecipe(activeProduct, 3, RecipeStatus.OBSOLETE, "OBS3");
        Recipe inactiveProdDraft = seedRecipe(inactiveProduct, 1, RecipeStatus.DRAFT, "INACTDRF");

        long recipesBefore = recipeRepository.count();
        long auditsBefore = auditLogRepository.count();

        // A. Bean validation failure on create (400)
        mockMvc.perform(post("/api/recipes")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // B. Missing product on create (404)
        RecipeRequestDto missingProductReq = new RecipeRequestDto(
                999999L, new BigDecimal("500"), "kg", "Missing product",
                List.of(new RecipeMaterialDto("API", new BigDecimal("100"), "kg")),
                List.of(new RecipeStepDto(1, "Step", "Instr"))
        );
        mockMvc.perform(post("/api/recipes")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missingProductReq)))
                .andExpect(status().isNotFound());

        // C. Inactive product on create (400)
        RecipeRequestDto inactiveProductReq = new RecipeRequestDto(
                inactiveProduct.getId(), new BigDecimal("500"), "kg", "Inactive prod",
                List.of(new RecipeMaterialDto("API", new BigDecimal("100"), "kg")),
                List.of(new RecipeStepDto(1, "Step", "Instr"))
        );
        mockMvc.perform(post("/api/recipes")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inactiveProductReq)))
                .andExpect(status().isBadRequest());

        // D. Duplicate step numbers on create (400)
        RecipeRequestDto dupCreateReq = new RecipeRequestDto(
                activeProduct.getId(), new BigDecimal("500"), "kg", "Dup steps",
                List.of(new RecipeMaterialDto("API", new BigDecimal("100"), "kg")),
                List.of(
                        new RecipeStepDto(1, "Dispense", "Inst 1"),
                        new RecipeStepDto(1, "Blend", "Inst 2")
                )
        );
        mockMvc.perform(post("/api/recipes")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupCreateReq)))
                .andExpect(status().isBadRequest());

        // E. Duplicate step numbers on update (400)
        mockMvc.perform(put("/api/recipes/{id}", draftRecipe.getId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupCreateReq)))
                .andExpect(status().isBadRequest());

        // F. Reassign product on update (400)
        RecipeRequestDto reassignProdReq = new RecipeRequestDto(
                otherActive.getId(), new BigDecimal("600"), "kg", "Wrong prod",
                List.of(new RecipeMaterialDto("API", new BigDecimal("100"), "kg")),
                List.of(new RecipeStepDto(1, "Dispense", "Inst 1"))
        );
        mockMvc.perform(put("/api/recipes/{id}", draftRecipe.getId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reassignProdReq)))
                .andExpect(status().isBadRequest());

        // G. Update APPROVED / OBSOLETE / missing recipes (400 / 404)
        RecipeRequestDto validDraftPayload = new RecipeRequestDto(
                activeProduct.getId(), new BigDecimal("999"), "kg", "Should fail",
                List.of(new RecipeMaterialDto("API", new BigDecimal("100"), "kg")),
                List.of(new RecipeStepDto(1, "Dispense", "Inst 1"))
        );
        mockMvc.perform(put("/api/recipes/{id}", approvedRecipe.getId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validDraftPayload)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/recipes/{id}", obsoleteRecipe.getId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validDraftPayload)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/recipes/{id}", 999999L)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validDraftPayload)))
                .andExpect(status().isNotFound());

        // H. Approving recipe linked to INACTIVE product (400)
        mockMvc.perform(patch("/api/recipes/{id}/status", inactiveProdDraft.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isBadRequest());

        // I. Illegal transitions & missing ID on status update
        mockMvc.perform(patch("/api/recipes/{id}/status", draftRecipe.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.OBSOLETE))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/recipes/{id}/status", approvedRecipe.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.DRAFT))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/recipes/{id}/status", obsoleteRecipe.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/recipes/{id}/status", 999999L)
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isNotFound());

        // J. New version for inactive product / missing source
        mockMvc.perform(post("/api/recipes/{id}/new-version", inactiveProdDraft.getId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/recipes/{id}/new-version", 999999L)
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isNotFound());

        // K. Delete APPROVED / OBSOLETE / missing recipe
        mockMvc.perform(delete("/api/recipes/{id}", approvedRecipe.getId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/recipes/{id}", obsoleteRecipe.getId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/recipes/{id}", 999999L)
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isNotFound());

        // L. Unauthenticated create & status transitions rejected (401) even with forged X-Actor-Name / body actorName
        mockMvc.perform(post("/api/recipes")
                        .header("X-Actor-Name", "Spoofed Anonymous")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validDraftPayload)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/recipes/{id}/status", draftRecipe.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\",\"actorName\":\"Spoofed Anonymous\"}"))
                .andExpect(status().isUnauthorized());

        assertEquals(recipesBefore, recipeRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());

        assertEquals(RecipeStatus.DRAFT, recipeRepository.findById(draftRecipe.getId()).orElseThrow().getStatus());
        assertEquals(RecipeStatus.APPROVED, recipeRepository.findById(approvedRecipe.getId()).orElseThrow().getStatus());
        assertEquals(RecipeStatus.OBSOLETE, recipeRepository.findById(obsoleteRecipe.getId()).orElseThrow().getStatus());
        assertEquals(RecipeStatus.DRAFT, recipeRepository.findById(inactiveProdDraft.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("3. Audit persistence failure rolls back recipe create, update (with children), new-version, approve+supersede, obsolete, and delete completely")
    void auditPersistenceFailureRollsBackRecipeTransactions() throws Exception {
        String supBearer = bearerFor("prod.sup.rb", Role.PRODUCTION_SUPERVISOR);
        String qaBearer = bearerFor("qa.rev.rb", Role.QA_REVIEWER);

        Product product = seedProduct("RB", ProductStatus.ACTIVE);
        Recipe approvedV1 = seedRecipe(product, 1, RecipeStatus.APPROVED, "APPV1");
        Recipe draftV2 = seedRecipe(product, 2, RecipeStatus.DRAFT, "DRFV2");

        long recipesBaseline = recipeRepository.count();
        long auditsBaseline = auditLogRepository.count();

        // A. Rollback on createRecipe
        doThrow(new RuntimeException("Injected audit failure on createRecipe"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        RecipeRequestDto createAttempt = new RecipeRequestDto(
                product.getId(), new BigDecimal("777.0000"), "kg", "Rolled back create",
                List.of(new RecipeMaterialDto("Rolled Back Mat", new BigDecimal("100.0000"), "kg")),
                List.of(new RecipeStepDto(1, "Rolled Back Step", "Should roll back"))
        );
        mockMvc.perform(post("/api/recipes")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createAttempt)))
                .andExpect(status().isInternalServerError());

        assertEquals(recipesBaseline, recipeRepository.count());
        assertEquals(auditsBaseline, auditLogRepository.count());

        // B. Rollback on updateRecipe (even after flush deleted/replaced materials & steps)
        RecipeRequestDto updateAttempt = new RecipeRequestDto(
                product.getId(), new BigDecimal("9999.0000"), "g", "Should never persist",
                List.of(new RecipeMaterialDto("Corrupted Material", new BigDecimal("1.0000"), "g")),
                List.of(new RecipeStepDto(1, "Corrupted Step", "Should roll back"))
        );
        mockMvc.perform(put("/api/recipes/{id}", draftV2.getId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateAttempt)))
                .andExpect(status().isInternalServerError());

        assertEquals(auditsBaseline, auditLogRepository.count());
        mockMvc.perform(get("/api/recipes/{id}", draftV2.getId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchSize").value(500.0))
                .andExpect(jsonPath("$.batchUnit").value("kg"))
                .andExpect(jsonPath("$.description").value("Seeded recipe DRFV2"))
                .andExpect(jsonPath("$.materials.length()").value(2))
                .andExpect(jsonPath("$.materials[0].materialName").value("Seed API DRFV2"))
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[0].operationName").value("Dispensing"));

        // C. Rollback on createNewVersion
        mockMvc.perform(post("/api/recipes/{id}/new-version", approvedV1.getId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isInternalServerError());
        assertEquals(recipesBaseline, recipeRepository.count());
        assertEquals(auditsBaseline, auditLogRepository.count());

        // D. Partial failure rollback on approve superseding previous APPROVED:
        //    1st recordAudit (RECIPE_OBSOLETED on approvedV1) succeeds,
        //    2nd recordAudit (RECIPE_APPROVED on draftV2) throws -> BOTH mutations and 1st audit must roll back!
        Mockito.reset(auditTrailService);
        doCallRealMethod()
                .doThrow(new RuntimeException("Injected audit failure on RECIPE_APPROVED"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(patch("/api/recipes/{id}/status", draftV2.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isInternalServerError());

        assertEquals(RecipeStatus.APPROVED, recipeRepository.findById(approvedV1.getId()).orElseThrow().getStatus(),
                "Previous approved version must roll back from OBSOLETE to APPROVED");
        assertEquals(RecipeStatus.DRAFT, recipeRepository.findById(draftV2.getId()).orElseThrow().getStatus(),
                "Target recipe must roll back from APPROVED to DRAFT");
        assertEquals(auditsBaseline, auditLogRepository.count(),
                "First RECIPE_OBSOLETED audit entry must roll back with the transaction");

        // E. Rollback on direct APPROVED -> OBSOLETE transition
        Mockito.reset(auditTrailService);
        doThrow(new RuntimeException("Injected audit failure on direct obsolete"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(patch("/api/recipes/{id}/status", approvedV1.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.OBSOLETE))))
                .andExpect(status().isInternalServerError());

        assertEquals(RecipeStatus.APPROVED, recipeRepository.findById(approvedV1.getId()).orElseThrow().getStatus());
        assertEquals(auditsBaseline, auditLogRepository.count());

        // F. Rollback on deleteRecipe (DRAFT recipe and its materials/steps must remain intact)
        mockMvc.perform(delete("/api/recipes/{id}", draftV2.getId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isInternalServerError());

        assertEquals(recipesBaseline, recipeRepository.count());
        assertEquals(auditsBaseline, auditLogRepository.count());
        mockMvc.perform(get("/api/recipes/{id}", draftV2.getId())
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materials.length()").value(2))
                .andExpect(jsonPath("$.steps.length()").value(2));

        // G. Clean recovery after failure clears
        Mockito.reset(auditTrailService);
        mockMvc.perform(patch("/api/recipes/{id}/status", draftV2.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertEquals(RecipeStatus.OBSOLETE, recipeRepository.findById(approvedV1.getId()).orElseThrow().getStatus());
        assertEquals(auditsBaseline + 2, auditLogRepository.count());
    }

    @Test
    @DisplayName("4. Recipe audit records remain append-only and read-only over HTTP")
    void recipeAuditLogsAreAppendOnlyOverHttp() throws Exception {
        String qaBearer = bearerFor("qa.auditor.immut", Role.QA_REVIEWER);
        Product product = seedProduct("IMMUT", ProductStatus.ACTIVE);
        Recipe draft = seedRecipe(product, 1, RecipeStatus.DRAFT, "IMM");

        mockMvc.perform(patch("/api/recipes/{id}/status", draft.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isOk());

        List<AuditLog> logs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                "RECIPE", draft.getId());
        assertEquals(1, logs.size());
        assertEquals("RECIPE_APPROVED", logs.get(0).getAction());

        mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .param("entityType", "RECIPE")
                        .param("entityId", String.valueOf(draft.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("RECIPE_APPROVED"));

        mockMvc.perform(put("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, qaBearer))
                .andExpect(status().isMethodNotAllowed());
    }
}

