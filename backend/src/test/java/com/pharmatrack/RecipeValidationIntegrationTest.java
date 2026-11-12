package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.RecipeMaterialDto;
import com.pharmatrack.dto.RecipeRequestDto;
import com.pharmatrack.dto.RecipeStatusUpdateDto;
import com.pharmatrack.dto.RecipeStepDto;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeMaterial;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.JwtService;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack Spring Boot + H2 + MockMvc integration tests covering Recipe validation
 * and approval rules against the real JPA persistence layer and unique constraints.
 * Runs exclusively against an isolated in-memory H2 database.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_recipe_validation_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecipeValidationIntegrationTest {

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

    private Product seedProduct(String tag, ProductStatus status) {
        int n = SEQ.getAndIncrement();
        return productRepository.saveAndFlush(new Product(
                "PRD-RV-" + tag + "-" + n,
                "Recipe Validation Product " + tag,
                "500 mg",
                "Tablet",
                "Formulation validation test product",
                status
        ));
    }

    @Test
    @DisplayName("1. POST /api/recipes rejects duplicate step numbers with HTTP 400 (never 500) and persists nothing")
    void createRecipeRejectsDuplicateStepNumbersWith400AndPersistsNothing() throws Exception {
        String supBearer = bearerFor("rv.prod.sup", Role.PRODUCTION_SUPERVISOR);
        Product product = seedProduct("CRTDUP", ProductStatus.ACTIVE);
        long recipeCountBefore = recipeRepository.count();

        RecipeRequestDto duplicateStepRequest = new RecipeRequestDto(
                product.getId(),
                new BigDecimal("1000.0000"),
                "kg",
                "Recipe with duplicate steps",
                List.of(new RecipeMaterialDto("API Powder", new BigDecimal("500.0000"), "kg")),
                List.of(
                        new RecipeStepDto(1, "Dispensing", "Dispense materials."),
                        new RecipeStepDto(1, "Blending", "Duplicate step number 1.")
                )
        );

        mockMvc.perform(post("/api/recipes")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateStepRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Duplicate step number(s) found: 1")));

        assertEquals(recipeCountBefore, recipeRepository.count());
        assertTrue(recipeRepository.findByProductId(product.getId()).isEmpty());
    }

    @Test
    @DisplayName("2. PUT /api/recipes/{id} rejects duplicate step numbers with HTTP 400 and preserves existing DB recipe state")
    void updateRecipeRejectsDuplicateStepNumbersWithoutModifyingExistingData() throws Exception {
        String supBearer = bearerFor("rv.prod.sup", Role.PRODUCTION_SUPERVISOR);
        Product product = seedProduct("UPDDUP", ProductStatus.ACTIVE);

        // Create a valid DRAFT recipe first
        RecipeRequestDto validCreate = new RecipeRequestDto(
                product.getId(),
                new BigDecimal("1000.0000"),
                "kg",
                "Original valid description",
                List.of(new RecipeMaterialDto("Original API", new BigDecimal("250.0000"), "kg")),
                List.of(
                        new RecipeStepDto(1, "Dispensing", "Original step 1"),
                        new RecipeStepDto(2, "Compression", "Original step 2")
                )
        );

        MvcResult createResult = mockMvc.perform(post("/api/recipes")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreate)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        long recipeId = createdJson.get("id").asLong();

        // Attempt invalid edit with duplicate step number 2 and changed header/materials
        RecipeRequestDto duplicateEdit = new RecipeRequestDto(
                product.getId(),
                new BigDecimal("9999.0000"),
                "g",
                "Corrupted edit description",
                List.of(new RecipeMaterialDto("Changed Material", new BigDecimal("10.0000"), "g")),
                List.of(
                        new RecipeStepDto(2, "Blending", "First step 2"),
                        new RecipeStepDto(1, "Dispensing", "Step 1"),
                        new RecipeStepDto(2, "Packaging", "Duplicate step 2")
                )
        );

        mockMvc.perform(put("/api/recipes/{id}", recipeId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateEdit)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Duplicate step number(s) found: 2")));

        // Verify original DB recipe, materials, and steps are completely intact
        Recipe reloaded = recipeRepository.findById(recipeId).orElseThrow();
        assertEquals(0, new BigDecimal("1000.0000").compareTo(reloaded.getBatchSize()));
        assertEquals("kg", reloaded.getBatchUnit());
        assertEquals("Original valid description", reloaded.getDescription());
        assertEquals(RecipeStatus.DRAFT, reloaded.getStatus());

        mockMvc.perform(get("/api/recipes/{id}", recipeId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchSize").value(1000.0))
                .andExpect(jsonPath("$.batchUnit").value("kg"))
                .andExpect(jsonPath("$.description").value("Original valid description"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.materials.length()").value(1))
                .andExpect(jsonPath("$.materials[0].materialName").value("Original API"))
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[0].stepNumber").value(1))
                .andExpect(jsonPath("$.steps[0].operationName").value("Dispensing"))
                .andExpect(jsonPath("$.steps[1].stepNumber").value(2))
                .andExpect(jsonPath("$.steps[1].operationName").value("Compression"));
    }

    @Test
    @DisplayName("3. PATCH /api/recipes/{id}/status rejects approving a recipe linked to an INACTIVE product and leaves statuses unchanged")
    void approveRecipeRejectsInactiveProductAndPreservesStatuses() throws Exception {
        String qaBearer = bearerFor("rv.qa.reviewer", Role.QA_REVIEWER);
        Product product = seedProduct("INACTAPP", ProductStatus.ACTIVE);

        // Seed an APPROVED v1 recipe while product was ACTIVE
        Recipe approvedV1 = new Recipe(
                "RCP-INACTAPP-V1",
                product,
                1,
                new BigDecimal("500.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Approved V1"
        );
        approvedV1.addMaterial(new RecipeMaterial("API", new BigDecimal("100.0000"), "kg"));
        approvedV1.addStep(new RecipeStep(1, "Blend", "Blend materials"));
        approvedV1 = recipeRepository.saveAndFlush(approvedV1);

        // Seed a DRAFT v2 recipe
        Recipe draftV2 = new Recipe(
                "RCP-INACTAPP-V2",
                product,
                2,
                new BigDecimal("600.0000"),
                "kg",
                RecipeStatus.DRAFT,
                "Pending Draft V2"
        );
        draftV2.addMaterial(new RecipeMaterial("API", new BigDecimal("120.0000"), "kg"));
        draftV2.addStep(new RecipeStep(1, "Blend", "Blend materials"));
        draftV2 = recipeRepository.saveAndFlush(draftV2);

        // Product is now deactivated
        product.setStatus(ProductStatus.INACTIVE);
        productRepository.saveAndFlush(product);

        mockMvc.perform(patch("/api/recipes/{id}/status", draftV2.getId())
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("inactive")))
                .andExpect(jsonPath("$.message").value(containsString(product.getProductCode())));

        // Verify v2 stays DRAFT and v1 stays APPROVED (not superseded to OBSOLETE)
        assertEquals(RecipeStatus.DRAFT, recipeRepository.findById(draftV2.getId()).orElseThrow().getStatus());
        assertEquals(RecipeStatus.APPROVED, recipeRepository.findById(approvedV1.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("4. Valid multi-step recipe create -> edit -> approve workflow succeeds end-to-end")
    void validCreateEditApproveWorkflowRemainsIntact() throws Exception {
        String supBearer = bearerFor("rv.prod.sup", Role.PRODUCTION_SUPERVISOR);
        String qaBearer = bearerFor("rv.qa.reviewer", Role.QA_REVIEWER);
        Product product = seedProduct("VALIDFLOW", ProductStatus.ACTIVE);

        RecipeRequestDto createReq = new RecipeRequestDto(
                product.getId(),
                new BigDecimal("1000.0000"),
                "kg",
                "Valid creation flow",
                List.of(new RecipeMaterialDto("Active Ingredient", new BigDecimal("400.0000"), "kg")),
                List.of(
                        new RecipeStepDto(1, "Dispensing", "Weigh API and excipients."),
                        new RecipeStepDto(2, "Blending", "Blend for 15 mins.")
                )
        );

        MvcResult createdResult = mockMvc.perform(post("/api/recipes")
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andReturn();

        long recipeId = objectMapper.readTree(createdResult.getResponse().getContentAsString()).get("id").asLong();

        RecipeRequestDto editReq = new RecipeRequestDto(
                product.getId(),
                new BigDecimal("1200.0000"),
                "kg",
                "Updated valid recipe",
                List.of(new RecipeMaterialDto("Active Ingredient", new BigDecimal("480.0000"), "kg")),
                List.of(
                        new RecipeStepDto(1, "Dispensing", "Updated dispensing"),
                        new RecipeStepDto(2, "Blending", "Updated blending"),
                        new RecipeStepDto(3, "Compression", "Compress tablets")
                )
        );

        mockMvc.perform(put("/api/recipes/{id}", recipeId)
                        .header(HttpHeaders.AUTHORIZATION, supBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(editReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[2].stepNumber").value(3));

        mockMvc.perform(patch("/api/recipes/{id}/status", recipeId)
                        .header(HttpHeaders.AUTHORIZATION, qaBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertEquals(RecipeStatus.APPROVED, recipeRepository.findById(recipeId).orElseThrow().getStatus());
    }
}

