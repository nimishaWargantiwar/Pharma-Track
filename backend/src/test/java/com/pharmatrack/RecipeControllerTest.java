package com.pharmatrack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.controller.RecipeController;
import com.pharmatrack.dto.RecipeMaterialDto;
import com.pharmatrack.dto.RecipeRequestDto;
import com.pharmatrack.dto.RecipeResponseDto;
import com.pharmatrack.dto.RecipeStatusUpdateDto;
import com.pharmatrack.dto.RecipeStepDto;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.exception.DuplicateResourceException;
import com.pharmatrack.exception.GlobalExceptionHandler;
import com.pharmatrack.exception.InvalidRecipeException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.security.SecurityConfig;
import com.pharmatrack.service.RecipeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RecipeController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@WithMockUser(username = "admin.user", roles = "ADMIN")
class RecipeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RecipeService recipeService;

    private RecipeRequestDto sampleRequestDto;
    private RecipeResponseDto sampleResponseDto;

    @BeforeEach
    void setUp() {
        RecipeMaterialDto matDto = new RecipeMaterialDto("Active API", new BigDecimal("500.0000"), "kg");
        RecipeStepDto stepDto = new RecipeStepDto(1, "Dispensing", "Dispense materials into clean containers.");

        sampleRequestDto = new RecipeRequestDto(
                1L,
                new BigDecimal("1000.0000"),
                "kg",
                "Standard Master Recipe",
                new ArrayList<>(List.of(matDto)),
                new ArrayList<>(List.of(stepDto))
        );

        sampleResponseDto = new RecipeResponseDto(
                10L,
                "RCP-PRD001-V1",
                1L,
                "PRD-001",
                "Paracetamol 500mg",
                1,
                new BigDecimal("1000.0000"),
                "kg",
                RecipeStatus.DRAFT,
                "Standard Master Recipe",
                List.of(matDto),
                List.of(stepDto),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    // -------------------------------------------------------------------------
    // 1. POST /api/recipes - Create Recipe
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/recipes - Should create recipe and return 201 Created")
    void createRecipe_Success_Returns201() throws Exception {
        when(recipeService.createRecipe(any(RecipeRequestDto.class))).thenReturn(sampleResponseDto);

        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.recipeCode").value("RCP-PRD001-V1"))
                .andExpect(jsonPath("$.productId").value(1))
                .andExpect(jsonPath("$.productCode").value("PRD-001"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.materials.length()").value(1))
                .andExpect(jsonPath("$.steps.length()").value(1));
    }

    @Test
    @DisplayName("POST /api/recipes - Should return 400 Bad Request when validation fails")
    void createRecipe_ValidationFailure_Returns400() throws Exception {
        RecipeRequestDto invalidDto = new RecipeRequestDto();
        invalidDto.setMaterials(Collections.emptyList());
        invalidDto.setSteps(Collections.emptyList());

        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.productId").exists())
                .andExpect(jsonPath("$.validationErrors.batchSize").exists())
                .andExpect(jsonPath("$.validationErrors.batchUnit").exists())
                .andExpect(jsonPath("$.validationErrors.materials").exists())
                .andExpect(jsonPath("$.validationErrors.steps").exists());
    }

    @Test
    @DisplayName("POST /api/recipes - Should reject stepNumber zero with 400 Validation Failed")
    void createRecipe_ZeroStepNumber_Returns400() throws Exception {
        sampleRequestDto.getSteps().get(0).setStepNumber(0);

        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors['steps[0].stepNumber']").value("Step number must be greater than zero"));

        verify(recipeService, never()).createRecipe(any(RecipeRequestDto.class));
    }

    @Test
    @DisplayName("POST /api/recipes - Should reject negative stepNumber and dynamically added invalid step with 400")
    void createRecipe_NegativeAndSecondStepInvalid_Returns400() throws Exception {
        sampleRequestDto.getSteps().get(0).setStepNumber(-1);
        sampleRequestDto.getSteps().add(new RecipeStepDto(0, "Blending", "Blend for 15 minutes."));

        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors['steps[0].stepNumber']").value("Step number must be greater than zero"))
                .andExpect(jsonPath("$.validationErrors['steps[1].stepNumber']").value("Step number must be greater than zero"));

        verify(recipeService, never()).createRecipe(any(RecipeRequestDto.class));
    }

    @Test
    @DisplayName("POST /api/recipes - Should reject decimal stepNumber JSON number with 400 Bad Request")
    void createRecipe_DecimalStepNumber_Returns400() throws Exception {
        String decimalStepJson = """
                {
                  "productId": 1,
                  "batchSize": 1000.0000,
                  "batchUnit": "kg",
                  "description": "Decimal step test",
                  "materials": [
                    { "materialName": "Active API", "quantity": 500.0000, "unit": "kg" }
                  ],
                  "steps": [
                    { "stepNumber": 1.5, "operationName": "Dispensing", "instructions": "Dispense materials." }
                  ]
                }
                """;

        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decimalStepJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        String wholeFloatStepJson = decimalStepJson.replace("1.5", "1.0");
        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wholeFloatStepJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(recipeService, never()).createRecipe(any(RecipeRequestDto.class));
    }

    @Test
    @DisplayName("POST /api/recipes - Should reject non-numeric and blank stepNumber JSON values with 400 Bad Request")
    void createRecipe_NonNumericStepNumber_Returns400() throws Exception {
        String nonNumericStepJson = """
                {
                  "productId": 1,
                  "batchSize": 1000.0000,
                  "batchUnit": "kg",
                  "description": "Non-numeric step test",
                  "materials": [
                    { "materialName": "Active API", "quantity": 500.0000, "unit": "kg" }
                  ],
                  "steps": [
                    { "stepNumber": "abc", "operationName": "Dispensing", "instructions": "Dispense materials." }
                  ]
                }
                """;

        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nonNumericStepJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        String emptyStepJson = nonNumericStepJson.replace("\"abc\"", "\"\"");
        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyStepJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(recipeService, never()).createRecipe(any(RecipeRequestDto.class));
    }

    @Test
    @DisplayName("POST /api/recipes - Should return 400 Bad Request when duplicate step numbers are submitted")
    void createRecipe_DuplicateStepNumbers_Returns400() throws Exception {
        sampleRequestDto.getSteps().add(new RecipeStepDto(1, "Blending", "Duplicate step number 1"));

        when(recipeService.createRecipe(any(RecipeRequestDto.class)))
                .thenThrow(new InvalidRecipeException("Duplicate step number(s) found: 1. Each manufacturing step in a recipe must have a unique step number."));

        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Duplicate step number(s) found: 1")));
    }

    @Test
    @DisplayName("POST /api/recipes - Should return 409 Conflict when recipe code already exists")
    void createRecipe_DuplicateCode_Returns409() throws Exception {
        when(recipeService.createRecipe(any(RecipeRequestDto.class)))
                .thenThrow(new DuplicateResourceException("Recipe with code 'RCP-PRD001-V1' already exists"));

        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Recipe with code 'RCP-PRD001-V1' already exists"));
    }

    // -------------------------------------------------------------------------
    // 2. GET /api/recipes - Retrieve All
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/recipes - Should return all recipes and return 200 OK")
    void getAllRecipes_Returns200() throws Exception {
        when(recipeService.getAllRecipes()).thenReturn(List.of(sampleResponseDto));

        mockMvc.perform(get("/api/recipes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].recipeCode").value("RCP-PRD001-V1"));
    }

    // -------------------------------------------------------------------------
    // 3. GET /api/recipes/{id} - Retrieve by ID
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/recipes/{id} - Should return recipe and return 200 OK")
    void getRecipeById_Success_Returns200() throws Exception {
        when(recipeService.getRecipeById(10L)).thenReturn(sampleResponseDto);

        mockMvc.perform(get("/api/recipes/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.recipeCode").value("RCP-PRD001-V1"))
                .andExpect(jsonPath("$.materials[0].materialName").value("Active API"))
                .andExpect(jsonPath("$.steps[0].operationName").value("Dispensing"));
    }

    @Test
    @DisplayName("GET /api/recipes/{id} - Missing ID should return 404 Not Found")
    void getRecipeById_NotFound_Returns404() throws Exception {
        when(recipeService.getRecipeById(999L))
                .thenThrow(new ResourceNotFoundException("Recipe not found with id: 999"));

        mockMvc.perform(get("/api/recipes/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Recipe not found with id: 999"));
    }

    // -------------------------------------------------------------------------
    // 4. GET /api/recipes/product/{productId} - Retrieve by Product
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/recipes/product/{productId} - Should return recipes for product and 200 OK")
    void getRecipesByProductId_Success_Returns200() throws Exception {
        when(recipeService.getRecipesByProductId(1L)).thenReturn(List.of(sampleResponseDto));

        mockMvc.perform(get("/api/recipes/product/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(1));
    }

    @Test
    @DisplayName("GET /api/recipes/product/{productId} - Missing product should return 404 Not Found")
    void getRecipesByProductId_NotFound_Returns404() throws Exception {
        when(recipeService.getRecipesByProductId(999L))
                .thenThrow(new ResourceNotFoundException("Product not found with id: 999"));

        mockMvc.perform(get("/api/recipes/product/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Product not found with id: 999"));
    }

    // -------------------------------------------------------------------------
    // 5. PUT /api/recipes/{id} - Update Recipe
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PUT /api/recipes/{id} - Should update DRAFT recipe and return 200 OK")
    void updateRecipe_Success_Returns200() throws Exception {
        when(recipeService.updateRecipe(eq(10L), any(RecipeRequestDto.class))).thenReturn(sampleResponseDto);

        mockMvc.perform(put("/api/recipes/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.recipeCode").value("RCP-PRD001-V1"));
    }

    @Test
    @DisplayName("PUT /api/recipes/{id} - Should reject invalid stepNumber with 400")
    void updateRecipe_InvalidStepNumber_Returns400() throws Exception {
        sampleRequestDto.getSteps().get(0).setStepNumber(0);

        mockMvc.perform(put("/api/recipes/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors['steps[0].stepNumber']").value("Step number must be greater than zero"));

        verify(recipeService, never()).updateRecipe(anyLong(), any(RecipeRequestDto.class));
    }

    @Test
    @DisplayName("PUT /api/recipes/{id} - Updating non-draft recipe should return 400 Bad Request")
    void updateRecipe_NonDraft_Returns400() throws Exception {
        when(recipeService.updateRecipe(eq(10L), any(RecipeRequestDto.class)))
                .thenThrow(new InvalidRecipeException("Only DRAFT recipes can be updated. Current recipe status: APPROVED"));

        mockMvc.perform(put("/api/recipes/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Only DRAFT recipes can be updated. Current recipe status: APPROVED"));
    }

    @Test
    @DisplayName("PUT /api/recipes/{id} - Duplicate step numbers should return 400 Bad Request")
    void updateRecipe_DuplicateStepNumbers_Returns400() throws Exception {
        sampleRequestDto.getSteps().add(new RecipeStepDto(1, "Blending", "Duplicate step number 1"));

        when(recipeService.updateRecipe(eq(10L), any(RecipeRequestDto.class)))
                .thenThrow(new InvalidRecipeException("Duplicate step number(s) found: 1. Each manufacturing step in a recipe must have a unique step number."));

        mockMvc.perform(put("/api/recipes/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Duplicate step number(s) found: 1")));
    }

    // -------------------------------------------------------------------------
    // 6. PATCH /api/recipes/{id}/status - Update Status
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /api/recipes/{id}/status - Should update status and return 200 OK")
    void updateRecipeStatus_Success_Returns200() throws Exception {
        sampleResponseDto.setStatus(RecipeStatus.APPROVED);
        when(recipeService.updateRecipeStatus(eq(10L), any(RecipeStatusUpdateDto.class))).thenReturn(sampleResponseDto);

        RecipeStatusUpdateDto statusDto = new RecipeStatusUpdateDto(RecipeStatus.APPROVED);

        mockMvc.perform(patch("/api/recipes/10/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("PATCH /api/recipes/{id}/status - Illegal transition should return 400 Bad Request")
    void updateRecipeStatus_IllegalTransition_Returns400() throws Exception {
        when(recipeService.updateRecipeStatus(eq(10L), any(RecipeStatusUpdateDto.class)))
                .thenThrow(new InvalidRecipeException("A DRAFT recipe cannot be transitioned directly to OBSOLETE. It must be APPROVED first or deleted."));

        RecipeStatusUpdateDto statusDto = new RecipeStatusUpdateDto(RecipeStatus.OBSOLETE);

        mockMvc.perform(patch("/api/recipes/10/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("cannot be transitioned directly to OBSOLETE")));
    }

    @Test
    @DisplayName("PATCH /api/recipes/{id}/status - Approving recipe for inactive product should return 400 Bad Request")
    void updateRecipeStatus_InactiveProductApproval_Returns400() throws Exception {
        when(recipeService.updateRecipeStatus(eq(10L), any(RecipeStatusUpdateDto.class)))
                .thenThrow(new InvalidRecipeException("Cannot approve recipe 'RCP-PRD001-V1' because its associated product (PRD-001) is inactive"));

        RecipeStatusUpdateDto statusDto = new RecipeStatusUpdateDto(RecipeStatus.APPROVED);

        mockMvc.perform(patch("/api/recipes/10/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("inactive")));
    }

    // -------------------------------------------------------------------------
    // 7. POST /api/recipes/{id}/new-version - Create New Version
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/recipes/{id}/new-version - Should clone and return 201 Created")
    void createNewVersion_Success_Returns201() throws Exception {
        RecipeResponseDto v2Response = new RecipeResponseDto(
                20L,
                "RCP-PRD001-V2",
                1L,
                "PRD-001",
                "Paracetamol 500mg",
                2,
                new BigDecimal("1000.0000"),
                "kg",
                RecipeStatus.DRAFT,
                "Cloned recipe",
                sampleResponseDto.getMaterials(),
                sampleResponseDto.getSteps(),
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(recipeService.createNewVersion(10L)).thenReturn(v2Response);

        mockMvc.perform(post("/api/recipes/10/new-version"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(20))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.recipeCode").value("RCP-PRD001-V2"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    // -------------------------------------------------------------------------
    // 8. DELETE /api/recipes/{id} - Delete Recipe
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /api/recipes/{id} - Should delete DRAFT recipe and return 204 No Content")
    void deleteRecipe_Success_Returns204() throws Exception {
        doNothing().when(recipeService).deleteRecipe(10L);

        mockMvc.perform(delete("/api/recipes/10"))
                .andExpect(status().isNoContent());

        verify(recipeService, times(1)).deleteRecipe(10L);
    }

    @Test
    @DisplayName("DELETE /api/recipes/{id} - Deleting non-draft recipe should return 400 Bad Request")
    void deleteRecipe_NonDraft_Returns400() throws Exception {
        doThrow(new InvalidRecipeException("Only DRAFT recipes can be deleted. Cannot delete a recipe with status: APPROVED"))
                .when(recipeService).deleteRecipe(10L);

        mockMvc.perform(delete("/api/recipes/10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("Only DRAFT recipes can be deleted")));
    }

    // -------------------------------------------------------------------------
    // 9. Ignored Forged Actor & RBAC Enforcement
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST / PUT / PATCH / new-version / DELETE ignore forged X-Actor-Name and body actorName cleanly")
    void recipeMutationsIgnoreForgedActorAndInvokeTrustedService() throws Exception {
        when(recipeService.createRecipe(any(RecipeRequestDto.class))).thenReturn(sampleResponseDto);
        mockMvc.perform(post("/api/recipes")
                        .header("X-Actor-Name", "Forged Director")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipeCode").value("RCP-PRD001-V1"));
        verify(recipeService).createRecipe(any(RecipeRequestDto.class));

        when(recipeService.updateRecipe(eq(10L), any(RecipeRequestDto.class)))
                .thenReturn(sampleResponseDto);
        mockMvc.perform(put("/api/recipes/10")
                        .header("X-Actor-Name", "Forged Lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isOk());
        verify(recipeService).updateRecipe(eq(10L), any(RecipeRequestDto.class));

        RecipeResponseDto approvedDto = new RecipeResponseDto(
                10L, "RCP-PRD001-V1", 1L, "PRD-001", "Paracetamol 500mg", 1,
                new BigDecimal("1000.0000"), "kg", RecipeStatus.APPROVED, "Standard Master Recipe",
                sampleResponseDto.getMaterials(), sampleResponseDto.getSteps(),
                LocalDateTime.now(), LocalDateTime.now()
        );
        when(recipeService.updateRecipeStatus(eq(10L), any(RecipeStatusUpdateDto.class)))
                .thenReturn(approvedDto);
        mockMvc.perform(patch("/api/recipes/10/status")
                        .header("X-Actor-Name", "Forged Header")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\",\"actorName\":\"Forged JSON Actor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        verify(recipeService).updateRecipeStatus(eq(10L), any(RecipeStatusUpdateDto.class));

        when(recipeService.createNewVersion(10L)).thenReturn(sampleResponseDto);
        mockMvc.perform(post("/api/recipes/10/new-version")
                        .header("X-Actor-Name", "Forged Engineer"))
                .andExpect(status().isCreated());
        verify(recipeService).createNewVersion(10L);

        doNothing().when(recipeService).deleteRecipe(10L);
        mockMvc.perform(delete("/api/recipes/10")
                        .header("X-Actor-Name", "Forged Cleanup"))
                .andExpect(status().isNoContent());
        verify(recipeService).deleteRecipe(10L);
    }

    @Test
    @WithMockUser(username = "operator.user", roles = "OPERATOR")
    @DisplayName("OPERATOR is denied both read and write access to Master Recipes (403 Forbidden)")
    void operatorDeniedRecipeReadAndWrite() throws Exception {
        mockMvc.perform(get("/api/recipes")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/recipes/10")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequestDto)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/recipes/10/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecipeStatusUpdateDto(RecipeStatus.APPROVED))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(recipeService);
    }

    @Test
    @WithMockUser(username = "qa.reviewer", roles = "QA_REVIEWER")
    @DisplayName("PATCH /api/recipes/{id}/status - Direct APPROVED -> OBSOLETE transition returns 200 OK for QA_REVIEWER")
    void updateRecipeStatus_ApproveToObsolete_Returns200() throws Exception {
        sampleResponseDto.setStatus(RecipeStatus.OBSOLETE);
        when(recipeService.updateRecipeStatus(eq(10L), any(RecipeStatusUpdateDto.class)))
                .thenReturn(sampleResponseDto);

        mockMvc.perform(patch("/api/recipes/10/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RecipeStatusUpdateDto(RecipeStatus.OBSOLETE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OBSOLETE"));
    }
}
