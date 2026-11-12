package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.RecipeMaterialDto;
import com.pharmatrack.dto.RecipeRequestDto;
import com.pharmatrack.dto.RecipeResponseDto;
import com.pharmatrack.dto.RecipeStatusUpdateDto;
import com.pharmatrack.dto.RecipeStepDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeMaterial;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.exception.DuplicateResourceException;
import com.pharmatrack.exception.InvalidRecipeException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.service.AuditTrailService;
import com.pharmatrack.service.RecipeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecipeServiceTest {

    @Mock
    private RecipeRepository recipeRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    private ObjectMapper objectMapper;
    private AuditTrailService auditTrailService;
    private RecipeService recipeService;

    private Product activeProduct;
    private Product inactiveProduct;
    private Recipe sampleRecipe;
    private RecipeRequestDto sampleRequestDto;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        auditTrailService = new AuditTrailService(auditLogRepository, objectMapper);
        recipeService = new RecipeService(recipeRepository, productRepository, auditTrailService);

        lenient().when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> {
            AuditLog log = invocation.getArgument(0);
            if (log.getId() == null) {
                log.setId(1L);
            }
            return log;
        });

        activeProduct = new Product(
                "PRD-001",
                "Paracetamol",
                "500 mg",
                "Tablet",
                "Analgesic medication",
                ProductStatus.ACTIVE
        );
        activeProduct.setId(1L);

        inactiveProduct = new Product(
                "PRD-002",
                "Ranitidine",
                "150 mg",
                "Tablet",
                "Recalled product",
                ProductStatus.INACTIVE
        );
        inactiveProduct.setId(2L);

        sampleRecipe = new Recipe(
                "RCP-PRD-001-V1",
                activeProduct,
                1,
                new BigDecimal("1000.0000"),
                "kg",
                RecipeStatus.DRAFT,
                "Initial master recipe description"
        );
        sampleRecipe.setId(10L);
        sampleRecipe.addMaterial(new RecipeMaterial("Paracetamol API", new BigDecimal("500.0000"), "kg"));
        sampleRecipe.addStep(new RecipeStep(1, "Dispensing", "Dispense materials into clean containers."));

        RecipeMaterialDto matDto = new RecipeMaterialDto("Paracetamol API", new BigDecimal("500.0000"), "kg");
        RecipeStepDto stepDto = new RecipeStepDto(1, "Dispensing", "Dispense materials into clean containers.");
        sampleRequestDto = new RecipeRequestDto(
                1L,
                new BigDecimal("1000.0000"),
                "kg",
                "Initial master recipe description",
                new ArrayList<>(List.of(matDto)),
                new ArrayList<>(List.of(stepDto))
        );
        authenticateAs("system.supervisor", "PRODUCTION_SUPERVISOR");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, String... roles) {
        String[] authorities = new String[roles.length];
        for (int i = 0; i < roles.length; i++) {
            authorities[i] = roles[i].startsWith("ROLE_") ? roles[i] : "ROLE_" + roles[i];
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a", AuthorityUtils.createAuthorityList(authorities))
        );
    }

    // -------------------------------------------------------------------------
    // 1. Create Recipe
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should successfully create a recipe for an ACTIVE product with version 1 and record RECIPE_CREATED audit")
    void shouldCreateRecipeSuccessfullyForActiveProduct() throws Exception {
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findMaxVersionByProductId(1L)).thenReturn(Optional.empty());
        when(recipeRepository.existsByRecipeCode("RCP-PRD-001-V1")).thenReturn(false);
        when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(invocation -> {
            Recipe r = invocation.getArgument(0);
            r.setId(100L);
            return r;
        });

        RecipeResponseDto response = recipeService.createRecipe(sampleRequestDto);

        assertNotNull(response);
        assertEquals(100L, response.getId());
        assertEquals("RCP-PRD-001-V1", response.getRecipeCode());
        assertEquals(1, response.getVersion());
        assertEquals(RecipeStatus.DRAFT, response.getStatus());
        assertEquals(1L, response.getProductId());
        assertEquals("PRD-001", response.getProductCode());
        assertEquals("Paracetamol", response.getProductName());
        assertEquals(1, response.getMaterials().size());
        assertEquals(1, response.getSteps().size());

        verify(productRepository, times(1)).findById(1L);
        verify(recipeRepository, times(1)).findMaxVersionByProductId(1L);
        verify(recipeRepository, times(1)).existsByRecipeCode("RCP-PRD-001-V1");
        verify(recipeRepository, times(1)).saveAndFlush(any(Recipe.class));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals("RECIPE", audit.getEntityType());
        assertEquals(100L, audit.getEntityId());
        assertEquals("RCP-PRD-001-V1", audit.getEntityReference());
        assertNull(audit.getBatchId());
        assertEquals("RECIPE_CREATED", audit.getAction());
        assertEquals("system.supervisor", audit.getActorName());
        assertNotNull(audit.getOccurredAt());
        assertTrue(audit.getReason().contains("RCP-PRD-001-V1"));
        assertNull(audit.getBeforeValues());

        JsonNode after = objectMapper.readTree(audit.getAfterValues());
        assertEquals("RCP-PRD-001-V1", after.get("recipeCode").asText());
        assertEquals(1L, after.get("productId").asLong());
        assertEquals("PRD-001", after.get("productCode").asText());
        assertEquals("Paracetamol", after.get("productName").asText());
        assertEquals(1, after.get("version").asInt());
        assertEquals("1000", after.get("batchSize").asText());
        assertEquals("kg", after.get("batchUnit").asText());
        assertEquals("DRAFT", after.get("status").asText());
        assertEquals("Initial master recipe description", after.get("description").asText());
        assertEquals(1, after.get("materials").size());
        assertEquals("Paracetamol API", after.get("materials").get(0).get("materialName").asText());
        assertEquals("500", after.get("materials").get(0).get("quantity").asText());
        assertEquals("kg", after.get("materials").get(0).get("unit").asText());
        assertEquals(1, after.get("steps").size());
        assertEquals(1, after.get("steps").get(0).get("stepNumber").asInt());
        assertEquals("Dispensing", after.get("steps").get(0).get("operationName").asText());
        assertEquals("Dispense materials into clean containers.", after.get("steps").get(0).get("instructions").asText());
    }

    @Test
    @DisplayName("Should increment version and generate code RCP-{code}-V{next} when previous versions exist, recording normalized authenticated actor")
    void shouldIncrementVersionWhenPreviousVersionsExist() {
        authenticateAs("  Dr.Rivera  ", "PRODUCTION_SUPERVISOR");
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findMaxVersionByProductId(1L)).thenReturn(Optional.of(2));
        when(recipeRepository.existsByRecipeCode("RCP-PRD-001-V3")).thenReturn(false);
        when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(invocation -> {
            Recipe r = invocation.getArgument(0);
            r.setId(103L);
            return r;
        });

        RecipeResponseDto response = recipeService.createRecipe(sampleRequestDto);

        assertNotNull(response);
        assertEquals(3, response.getVersion());
        assertEquals("RCP-PRD-001-V3", response.getRecipeCode());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        assertEquals("dr.rivera", captor.getValue().getActorName());
        assertEquals("RECIPE_CREATED", captor.getValue().getAction());
        assertEquals("RCP-PRD-001-V3", captor.getValue().getEntityReference());
    }

    @Test
    @DisplayName("Should fail closed when unauthenticated or when authenticated actor exceeds 100 characters without saving recipe or audit")
    void shouldRejectOversizedActorNameOnCreate() {
        SecurityContextHolder.clearContext();
        assertThrows(
                AuthenticationCredentialsNotFoundException.class,
                () -> recipeService.createRecipe(sampleRequestDto)
        );

        authenticateAs("A".repeat(101), "PRODUCTION_SUPERVISOR");
        assertThrows(
                InsufficientAuthenticationException.class,
                () -> recipeService.createRecipe(sampleRequestDto)
        );

        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject recipe creation for an INACTIVE product without writing audit")
    void shouldRejectRecipeCreationForInactiveProduct() {
        sampleRequestDto.setProductId(2L);
        when(productRepository.findById(2L)).thenReturn(Optional.of(inactiveProduct));

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.createRecipe(sampleRequestDto)
        );

        assertTrue(ex.getMessage().contains("inactive product"));
        assertTrue(ex.getMessage().contains("PRD-002"));
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject recipe creation for a nonexistent product without writing audit")
    void shouldRejectRecipeCreationForNonexistentProduct() {
        sampleRequestDto.setProductId(999L);
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(
                ResourceNotFoundException.class,
                () -> recipeService.createRecipe(sampleRequestDto)
        );

        assertTrue(ex.getMessage().contains("999"));
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject recipe creation when any stepNumber is zero, negative, or null without writing audit")
    void shouldRejectRecipeCreationWithNonPositiveStepNumber() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findMaxVersionByProductId(1L)).thenReturn(Optional.empty());
        when(recipeRepository.existsByRecipeCode("RCP-PRD-001-V1")).thenReturn(false);

        sampleRequestDto.getSteps().add(new RecipeStepDto(0, "Blending", "Blend powders"));

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.createRecipe(sampleRequestDto)
        );

        assertEquals("Step number must be greater than zero", ex.getMessage());
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject recipe update when any stepNumber is zero or negative without clearing steps or writing audit")
    void shouldRejectRecipeUpdateWithNonPositiveStepNumber() {
        sampleRecipe.setStatus(RecipeStatus.DRAFT);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        sampleRequestDto.getSteps().get(0).setStepNumber(-2);

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.updateRecipe(10L, sampleRequestDto)
        );

        assertEquals("Step number must be greater than zero", ex.getMessage());
        verify(recipeRepository, never()).flush();
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject recipe creation when duplicate step numbers are provided without saving or writing audit")
    void shouldRejectRecipeCreationWithDuplicateStepNumbers() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findMaxVersionByProductId(1L)).thenReturn(Optional.empty());
        when(recipeRepository.existsByRecipeCode("RCP-PRD-001-V1")).thenReturn(false);

        sampleRequestDto.getSteps().add(new RecipeStepDto(1, "Blending", "Duplicate step 1"));
        sampleRequestDto.getSteps().add(new RecipeStepDto(2, "Drying", "Dry granules"));
        sampleRequestDto.getSteps().add(new RecipeStepDto(2, "Milling", "Duplicate step 2"));

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.createRecipe(sampleRequestDto)
        );

        assertTrue(ex.getMessage().contains("Duplicate step number(s) found: 1, 2"));
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject recipe update with duplicate step numbers without mutating or clearing existing steps or writing audit")
    void shouldRejectRecipeUpdateWithDuplicateStepNumbersWithoutMutatingRecipe() {
        sampleRecipe.setStatus(RecipeStatus.DRAFT);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        sampleRequestDto.setBatchSize(new BigDecimal("2500.0000"));
        sampleRequestDto.setDescription("Modified description that must not be applied");
        sampleRequestDto.setSteps(List.of(
                new RecipeStepDto(2, "Compression", "Compress tablets"),
                new RecipeStepDto(1, "Dispensing", "Weigh excipients"),
                new RecipeStepDto(2, "Coating", "Duplicate step number 2")
        ));

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.updateRecipe(10L, sampleRequestDto)
        );

        assertTrue(ex.getMessage().contains("Duplicate step number(s) found: 2"));
        assertEquals(new BigDecimal("1000.0000"), sampleRecipe.getBatchSize());
        assertEquals("Initial master recipe description", sampleRecipe.getDescription());
        assertEquals(1, sampleRecipe.getMaterials().size());
        assertEquals(1, sampleRecipe.getSteps().size());
        verify(recipeRepository, never()).flush();
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject recipe creation when calculated recipe code already exists without writing audit")
    void shouldRejectRecipeCreationWhenCodeAlreadyExists() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(activeProduct));
        when(recipeRepository.findMaxVersionByProductId(1L)).thenReturn(Optional.empty());
        when(recipeRepository.existsByRecipeCode("RCP-PRD-001-V1")).thenReturn(true);

        DuplicateResourceException ex = assertThrows(
                DuplicateResourceException.class,
                () -> recipeService.createRecipe(sampleRequestDto)
        );

        assertTrue(ex.getMessage().contains("RCP-PRD-001-V1"));
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    // -------------------------------------------------------------------------
    // 2. Retrieve Recipes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should retrieve all recipes without recording audit logs")
    void shouldRetrieveAllRecipes() {
        Recipe recipe2 = new Recipe("RCP-PRD-001-V2", activeProduct, 2, new BigDecimal("1000.0000"), "kg", RecipeStatus.APPROVED, null);
        recipe2.setId(20L);

        when(recipeRepository.findAll()).thenReturn(List.of(sampleRecipe, recipe2));

        List<RecipeResponseDto> all = recipeService.getAllRecipes();

        assertEquals(2, all.size());
        verify(recipeRepository, times(1)).findAll();
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should retrieve recipe by existing ID with materials and steps")
    void shouldRetrieveRecipeByExistingId() {
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        RecipeResponseDto response = recipeService.getRecipeById(10L);

        assertNotNull(response);
        assertEquals(10L, response.getId());
        assertEquals("RCP-PRD-001-V1", response.getRecipeCode());
        assertEquals(1, response.getMaterials().size());
        assertEquals("Paracetamol API", response.getMaterials().get(0).getMaterialName());
        assertEquals(1, response.getSteps().size());
        assertEquals("Dispensing", response.getSteps().get(0).getOperationName());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when recipe ID is not found")
    void shouldThrowExceptionWhenRecipeIdNotFound() {
        when(recipeRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> recipeService.getRecipeById(999L));
    }

    @Test
    @DisplayName("Should retrieve recipes by product ID")
    void shouldRetrieveRecipesByProductId() {
        when(productRepository.existsById(1L)).thenReturn(true);
        when(recipeRepository.findByProductId(1L)).thenReturn(List.of(sampleRecipe));

        List<RecipeResponseDto> list = recipeService.getRecipesByProductId(1L);

        assertEquals(1, list.size());
        assertEquals(1L, list.get(0).getProductId());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when retrieving recipes for non-existent product ID")
    void shouldThrowExceptionWhenRetrievingForMissingProductId() {
        when(productRepository.existsById(999L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> recipeService.getRecipesByProductId(999L));
    }

    // -------------------------------------------------------------------------
    // 3. Update Recipe
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should update a DRAFT recipe and record RECIPE_UPDATED audit with full before/after snapshots")
    void shouldUpdateDraftRecipeSuccessfully() throws Exception {
        sampleRecipe.setStatus(RecipeStatus.DRAFT);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));
        when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RecipeMaterialDto updatedMat = new RecipeMaterialDto("Modified API", new BigDecimal("600.0000"), "kg");
        RecipeStepDto updatedStep = new RecipeStepDto(1, "Updated Dispensing", "New instructions");
        RecipeRequestDto updateDto = new RecipeRequestDto(
                1L,
                new BigDecimal("2000.0000"),
                "kg",
                "Updated description",
                List.of(updatedMat),
                List.of(updatedStep)
        );

        authenticateAs("Formulation.Chemist", "PRODUCTION_SUPERVISOR");
        RecipeResponseDto updated = recipeService.updateRecipe(10L, updateDto);

        assertEquals(new BigDecimal("2000.0000"), updated.getBatchSize());
        assertEquals("Updated description", updated.getDescription());
        assertEquals(1, updated.getMaterials().size());
        assertEquals("Modified API", updated.getMaterials().get(0).getMaterialName());
        assertEquals(new BigDecimal("600.0000"), updated.getMaterials().get(0).getQuantity());
        assertEquals("Updated Dispensing", updated.getSteps().get(0).getOperationName());
        verify(recipeRepository, times(1)).flush();
        verify(recipeRepository, times(1)).saveAndFlush(sampleRecipe);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals("RECIPE", audit.getEntityType());
        assertEquals(10L, audit.getEntityId());
        assertEquals("RCP-PRD-001-V1", audit.getEntityReference());
        assertNull(audit.getBatchId());
        assertEquals("RECIPE_UPDATED", audit.getAction());
        assertEquals("formulation.chemist", audit.getActorName());

        JsonNode before = objectMapper.readTree(audit.getBeforeValues());
        JsonNode after = objectMapper.readTree(audit.getAfterValues());
        assertEquals("1000", before.get("batchSize").asText());
        assertEquals("Initial master recipe description", before.get("description").asText());
        assertEquals("Paracetamol API", before.get("materials").get(0).get("materialName").asText());
        assertEquals("Dispensing", before.get("steps").get(0).get("operationName").asText());

        assertEquals("2000", after.get("batchSize").asText());
        assertEquals("Updated description", after.get("description").asText());
        assertEquals("Modified API", after.get("materials").get(0).get("materialName").asText());
        assertEquals("600", after.get("materials").get(0).get("quantity").asText());
        assertEquals("Updated Dispensing", after.get("steps").get(0).get("operationName").asText());
        assertEquals("New instructions", after.get("steps").get(0).get("instructions").asText());
    }

    @Test
    @DisplayName("Should replace multiple materials and steps in ascending step order in both recipe and audit snapshot")
    void shouldReplaceMultipleMaterialsAndStepsWhenUpdatingDraftRecipe() throws Exception {
        sampleRecipe.setStatus(RecipeStatus.DRAFT);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));
        when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RecipeMaterialDto mat1 = new RecipeMaterialDto("New API Active", new BigDecimal("120.5000"), "kg");
        RecipeMaterialDto mat2 = new RecipeMaterialDto("New Excipient Starch", new BigDecimal("45.0000"), "kg");

        // Submit step 2 before step 1 to verify deterministic ascending step ordering in audit snapshots
        RecipeStepDto step2 = new RecipeStepDto(2, "Dry Blending", "Blend ingredients at 25 RPM for 20 minutes");
        RecipeStepDto step1 = new RecipeStepDto(1, "Pre-weighing", "Weigh materials per standard operating procedure");

        RecipeRequestDto updateDto = new RecipeRequestDto(
                1L,
                new BigDecimal("1500.0000"),
                "kg",
                "Fully updated recipe details",
                List.of(mat1, mat2),
                List.of(step2, step1)
        );

        RecipeResponseDto updated = recipeService.updateRecipe(10L, updateDto);

        assertEquals(10L, updated.getId());
        assertEquals("RCP-PRD-001-V1", updated.getRecipeCode());
        assertEquals(1, updated.getVersion());
        assertEquals(RecipeStatus.DRAFT, updated.getStatus());
        assertEquals(2, updated.getMaterials().size());
        assertEquals(2, updated.getSteps().size());
        assertEquals(1, updated.getSteps().get(0).getStepNumber());
        assertEquals(2, updated.getSteps().get(1).getStepNumber());

        InOrder inOrder = inOrder(recipeRepository);
        inOrder.verify(recipeRepository).flush();
        inOrder.verify(recipeRepository).saveAndFlush(sampleRecipe);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        JsonNode after = objectMapper.readTree(captor.getValue().getAfterValues());
        assertEquals(2, after.get("materials").size());
        assertEquals("New API Active", after.get("materials").get(0).get("materialName").asText());
        assertEquals("120.5", after.get("materials").get(0).get("quantity").asText());
        assertEquals("New Excipient Starch", after.get("materials").get(1).get("materialName").asText());
        assertEquals(2, after.get("steps").size());
        assertEquals(1, after.get("steps").get(0).get("stepNumber").asInt());
        assertEquals("Pre-weighing", after.get("steps").get(0).get("operationName").asText());
        assertEquals(2, after.get("steps").get(1).get("stepNumber").asInt());
        assertEquals("Dry Blending", after.get("steps").get(1).get("operationName").asText());
    }

    @Test
    @DisplayName("Should skip persistence and audit logging when update changes nothing (even with equivalent decimal scale)")
    void shouldSkipAuditWhenUpdateChangesNothing() {
        sampleRecipe.setStatus(RecipeStatus.DRAFT);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        RecipeRequestDto noOpDto = new RecipeRequestDto(
                1L,
                new BigDecimal("1000"), // equivalent to 1000.0000
                "kg",
                "Initial master recipe description",
                List.of(new RecipeMaterialDto("Paracetamol API", new BigDecimal("500"), "kg")),
                List.of(new RecipeStepDto(1, "Dispensing", "Dispense materials into clean containers."))
        );

        RecipeResponseDto result = recipeService.updateRecipe(10L, noOpDto);

        assertEquals(10L, result.getId());
        verify(recipeRepository, never()).flush();
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject updating an APPROVED recipe without writing audit")
    void shouldRejectUpdatingApprovedRecipe() {
        sampleRecipe.setStatus(RecipeStatus.APPROVED);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.updateRecipe(10L, sampleRequestDto)
        );

        assertTrue(ex.getMessage().contains("Only DRAFT recipes can be updated"));
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject updating an OBSOLETE recipe without writing audit")
    void shouldRejectUpdatingObsoleteRecipe() {
        sampleRecipe.setStatus(RecipeStatus.OBSOLETE);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.updateRecipe(10L, sampleRequestDto)
        );

        assertTrue(ex.getMessage().contains("Only DRAFT recipes can be updated"));
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject recipe update when attempting to change associated product without writing audit")
    void shouldRejectUpdatingRecipeProduct() {
        sampleRecipe.setStatus(RecipeStatus.DRAFT);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        sampleRequestDto.setProductId(999L);

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.updateRecipe(10L, sampleRequestDto)
        );

        assertTrue(ex.getMessage().contains("Cannot change the associated product"));
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    // -------------------------------------------------------------------------
    // 4. Update Recipe Status
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should approve DRAFT recipe, mark previous APPROVED version OBSOLETE, and record RECIPE_OBSOLETED and RECIPE_APPROVED audits")
    void shouldApproveDraftRecipeAndMakePreviousApprovedObsolete() throws Exception {
        sampleRecipe.setStatus(RecipeStatus.DRAFT);

        Recipe prevApproved = new Recipe(
                "RCP-PRD-001-V0",
                activeProduct,
                0,
                new BigDecimal("500.0000"),
                "kg",
                RecipeStatus.APPROVED,
                "Old approved version"
        );
        prevApproved.setId(5L);
        prevApproved.addMaterial(new RecipeMaterial("Old API", new BigDecimal("250.0000"), "kg"));
        prevApproved.addStep(new RecipeStep(1, "Old Mixing", "Old mixing instructions"));

        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));
        when(recipeRepository.findByProductIdAndStatus(1L, RecipeStatus.APPROVED))
                .thenReturn(List.of(prevApproved));
        when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authenticateAs("QA.Approver", "QA_REVIEWER");
        RecipeResponseDto result = recipeService.updateRecipeStatus(10L, RecipeStatus.APPROVED);

        assertEquals(RecipeStatus.APPROVED, result.getStatus());
        assertEquals(RecipeStatus.OBSOLETE, prevApproved.getStatus(), "Previous approved version must become OBSOLETE");

        verify(recipeRepository, times(1)).saveAndFlush(prevApproved);
        verify(recipeRepository, times(1)).saveAndFlush(sampleRecipe);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(captor.capture());
        List<AuditLog> entries = captor.getAllValues();

        AuditLog obsoletedLog = entries.get(0);
        assertEquals("RECIPE", obsoletedLog.getEntityType());
        assertEquals(5L, obsoletedLog.getEntityId());
        assertEquals("RCP-PRD-001-V0", obsoletedLog.getEntityReference());
        assertEquals("RECIPE_OBSOLETED", obsoletedLog.getAction());
        assertEquals("qa.approver", obsoletedLog.getActorName());
        assertTrue(obsoletedLog.getReason().contains("RCP-PRD-001-V0"));
        assertTrue(obsoletedLog.getReason().contains("RCP-PRD-001-V1"));
        assertEquals("APPROVED", objectMapper.readTree(obsoletedLog.getBeforeValues()).get("status").asText());
        assertEquals("OBSOLETE", objectMapper.readTree(obsoletedLog.getAfterValues()).get("status").asText());
        assertEquals(1, objectMapper.readTree(obsoletedLog.getAfterValues()).get("materials").size());
        assertEquals(1, objectMapper.readTree(obsoletedLog.getAfterValues()).get("steps").size());

        AuditLog approvedLog = entries.get(1);
        assertEquals("RECIPE", approvedLog.getEntityType());
        assertEquals(10L, approvedLog.getEntityId());
        assertEquals("RCP-PRD-001-V1", approvedLog.getEntityReference());
        assertEquals("RECIPE_APPROVED", approvedLog.getAction());
        assertEquals("qa.approver", approvedLog.getActorName());
        assertTrue(approvedLog.getReason().contains("superseded RCP-PRD-001-V0"));
        assertEquals("DRAFT", objectMapper.readTree(approvedLog.getBeforeValues()).get("status").asText());
        assertEquals("APPROVED", objectMapper.readTree(approvedLog.getAfterValues()).get("status").asText());
        assertEquals(obsoletedLog.getOccurredAt(), approvedLog.getOccurredAt());
    }

    @Test
    @DisplayName("Should transition APPROVED recipe directly to OBSOLETE and record RECIPE_OBSOLETED audit")
    void shouldTransitionApprovedRecipeToObsoleteAndRecordAudit() throws Exception {
        sampleRecipe.setStatus(RecipeStatus.APPROVED);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));
        when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authenticateAs("QA.Compliance", "QA_REVIEWER");
        RecipeResponseDto result = recipeService.updateRecipeStatus(
                10L,
                new RecipeStatusUpdateDto(RecipeStatus.OBSOLETE)
        );

        assertEquals(RecipeStatus.OBSOLETE, result.getStatus());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLog log = captor.getValue();
        assertEquals("RECIPE", log.getEntityType());
        assertEquals(10L, log.getEntityId());
        assertEquals("RCP-PRD-001-V1", log.getEntityReference());
        assertEquals("RECIPE_OBSOLETED", log.getAction());
        assertEquals("qa.compliance", log.getActorName());
        assertEquals("APPROVED", objectMapper.readTree(log.getBeforeValues()).get("status").asText());
        assertEquals("OBSOLETE", objectMapper.readTree(log.getAfterValues()).get("status").asText());
    }

    @Test
    @DisplayName("Should skip persistence and audit logging when non-OBSOLETE target status already matches current status")
    void shouldSkipAuditWhenStatusAlreadyMatches() {
        sampleRecipe.setStatus(RecipeStatus.APPROVED);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        RecipeResponseDto result = recipeService.updateRecipeStatus(10L, RecipeStatus.APPROVED);

        assertEquals(RecipeStatus.APPROVED, result.getStatus());
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject approving DRAFT recipe when associated product is INACTIVE and write zero audits")
    void shouldRejectApprovingDraftRecipeWhenProductIsInactive() {
        sampleRecipe.setProduct(inactiveProduct);
        sampleRecipe.setStatus(RecipeStatus.DRAFT);

        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.updateRecipeStatus(10L, RecipeStatus.APPROVED)
        );

        assertTrue(ex.getMessage().contains("inactive"));
        assertTrue(ex.getMessage().contains("PRD-002"));
        assertTrue(ex.getMessage().contains("RCP-PRD-001-V1"));
        assertEquals(RecipeStatus.DRAFT, sampleRecipe.getStatus());
        verify(recipeRepository, never()).findByProductIdAndStatus(anyLong(), any(RecipeStatus.class));
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject direct transition of DRAFT recipe to OBSOLETE without writing audit")
    void shouldRejectTransitioningDraftDirectlyToObsolete() {
        sampleRecipe.setStatus(RecipeStatus.DRAFT);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.updateRecipeStatus(10L, RecipeStatus.OBSOLETE)
        );

        assertTrue(ex.getMessage().contains("cannot be transitioned directly to OBSOLETE"));
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject reverting an APPROVED recipe back to DRAFT without writing audit")
    void shouldRejectRevertingApprovedRecipeToDraft() {
        sampleRecipe.setStatus(RecipeStatus.APPROVED);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.updateRecipeStatus(10L, RecipeStatus.DRAFT)
        );

        assertTrue(ex.getMessage().contains("Cannot revert an APPROVED recipe back to DRAFT"));
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject changing status of an OBSOLETE recipe without writing audit")
    void shouldRejectChangingStatusOfObsoleteRecipe() {
        sampleRecipe.setStatus(RecipeStatus.OBSOLETE);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.updateRecipeStatus(10L, RecipeStatus.APPROVED)
        );

        assertTrue(ex.getMessage().contains("Cannot change status of an OBSOLETE recipe"));
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject null status update without touching repository or audit")
    void shouldRejectNullStatusUpdate() {
        assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.updateRecipeStatus(10L, (RecipeStatus) null)
        );
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should update status using RecipeStatusUpdateDto wrapper and optional actorName")
    void shouldUpdateStatusUsingDtoWrapper() {
        sampleRecipe.setStatus(RecipeStatus.DRAFT);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));
        when(recipeRepository.findByProductIdAndStatus(1L, RecipeStatus.APPROVED)).thenReturn(List.of());
        when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authenticateAs("QA.Release.Lead", "QA_REVIEWER");
        RecipeStatusUpdateDto dto = new RecipeStatusUpdateDto(RecipeStatus.APPROVED);
        RecipeResponseDto response = recipeService.updateRecipeStatus(10L, dto);

        assertEquals(RecipeStatus.APPROVED, response.getStatus());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        assertEquals("RECIPE_APPROVED", captor.getValue().getAction());
        assertEquals("qa.release.lead", captor.getValue().getActorName());
    }

    // -------------------------------------------------------------------------
    // 5. Create New Version
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should create new DRAFT version cloning materials/steps and record RECIPE_VERSION_CREATED audit")
    void shouldCreateNewVersionWithCopiedMaterialsAndSteps() throws Exception {
        sampleRecipe.setStatus(RecipeStatus.APPROVED);
        sampleRecipe.setVersion(1);

        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));
        when(recipeRepository.findMaxVersionByProductId(1L)).thenReturn(Optional.of(1));
        when(recipeRepository.existsByRecipeCode("RCP-PRD-001-V2")).thenReturn(false);
        when(recipeRepository.saveAndFlush(any(Recipe.class))).thenAnswer(invocation -> {
            Recipe r = invocation.getArgument(0);
            r.setId(200L);
            return r;
        });

        authenticateAs("Process.Engineer", "PRODUCTION_SUPERVISOR");
        RecipeResponseDto newVersionDto = recipeService.createNewVersion(10L);

        assertNotNull(newVersionDto);
        assertEquals(200L, newVersionDto.getId());
        assertEquals("RCP-PRD-001-V2", newVersionDto.getRecipeCode());
        assertEquals(2, newVersionDto.getVersion());
        assertEquals(RecipeStatus.DRAFT, newVersionDto.getStatus());
        assertEquals(1, newVersionDto.getMaterials().size());
        assertEquals("Paracetamol API", newVersionDto.getMaterials().get(0).getMaterialName());
        assertEquals(1, newVersionDto.getSteps().size());
        assertEquals("Dispensing", newVersionDto.getSteps().get(0).getOperationName());

        // Verify original version remains unchanged
        assertEquals(1, sampleRecipe.getVersion());
        assertEquals(RecipeStatus.APPROVED, sampleRecipe.getStatus());
        assertEquals("RCP-PRD-001-V1", sampleRecipe.getRecipeCode());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals("RECIPE", audit.getEntityType());
        assertEquals(200L, audit.getEntityId());
        assertEquals("RCP-PRD-001-V2", audit.getEntityReference());
        assertEquals("RECIPE_VERSION_CREATED", audit.getAction());
        assertEquals("process.engineer", audit.getActorName());
        assertTrue(audit.getReason().contains("RCP-PRD-001-V1"));
        assertTrue(audit.getReason().contains("RCP-PRD-001-V2"));
        assertNull(audit.getBeforeValues());

        JsonNode after = objectMapper.readTree(audit.getAfterValues());
        assertEquals(2, after.get("version").asInt());
        assertEquals("DRAFT", after.get("status").asText());
        assertEquals(1, after.get("materials").size());
        assertEquals(1, after.get("steps").size());
    }

    @Test
    @DisplayName("Should reject creating a new version when the associated product is INACTIVE without writing audit")
    void shouldRejectNewVersionWhenProductIsInactive() {
        sampleRecipe.setProduct(inactiveProduct);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.createNewVersion(10L)
        );

        assertTrue(ex.getMessage().contains("inactive product"));
        verify(recipeRepository, never()).saveAndFlush(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when cloning nonexistent recipe without writing audit")
    void shouldThrowExceptionWhenCloningNonExistentRecipe() {
        when(recipeRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> recipeService.createNewVersion(999L));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    // -------------------------------------------------------------------------
    // 6. Delete Recipe
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should delete a DRAFT recipe successfully and record RECIPE_DELETED audit with pre-deletion snapshot")
    void shouldDeleteDraftRecipeSuccessfully() throws Exception {
        sampleRecipe.setStatus(RecipeStatus.DRAFT);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        authenticateAs("QA.Reviewer", "PRODUCTION_SUPERVISOR");
        recipeService.deleteRecipe(10L);

        InOrder inOrder = inOrder(recipeRepository, auditLogRepository);
        inOrder.verify(recipeRepository).delete(sampleRecipe);
        inOrder.verify(recipeRepository).flush();
        inOrder.verify(auditLogRepository).save(any(AuditLog.class));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals("RECIPE", audit.getEntityType());
        assertEquals(10L, audit.getEntityId());
        assertEquals("RCP-PRD-001-V1", audit.getEntityReference());
        assertNull(audit.getBatchId());
        assertEquals("RECIPE_DELETED", audit.getAction());
        assertEquals("qa.reviewer", audit.getActorName());
        assertTrue(audit.getReason().contains("RCP-PRD-001-V1"));
        assertNull(audit.getAfterValues());

        JsonNode before = objectMapper.readTree(audit.getBeforeValues());
        assertEquals("RCP-PRD-001-V1", before.get("recipeCode").asText());
        assertEquals("DRAFT", before.get("status").asText());
        assertEquals(1, before.get("materials").size());
        assertEquals(1, before.get("steps").size());
    }

    @Test
    @DisplayName("Should reject deleting an APPROVED recipe without writing audit")
    void shouldRejectDeletingApprovedRecipe() {
        sampleRecipe.setStatus(RecipeStatus.APPROVED);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.deleteRecipe(10L)
        );

        assertTrue(ex.getMessage().contains("Only DRAFT recipes can be deleted"));
        assertTrue(ex.getMessage().contains("APPROVED"));
        verify(recipeRepository, never()).delete(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should reject deleting an OBSOLETE recipe without writing audit")
    void shouldRejectDeletingObsoleteRecipe() {
        sampleRecipe.setStatus(RecipeStatus.OBSOLETE);
        when(recipeRepository.findById(10L)).thenReturn(Optional.of(sampleRecipe));

        InvalidRecipeException ex = assertThrows(
                InvalidRecipeException.class,
                () -> recipeService.deleteRecipe(10L)
        );

        assertTrue(ex.getMessage().contains("Only DRAFT recipes can be deleted"));
        assertTrue(ex.getMessage().contains("OBSOLETE"));
        verify(recipeRepository, never()).delete(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when deleting nonexistent recipe without writing audit")
    void shouldThrowExceptionWhenDeletingNonexistentRecipe() {
        when(recipeRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> recipeService.deleteRecipe(999L));
        verify(recipeRepository, never()).delete(any(Recipe.class));
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }
}
