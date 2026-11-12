package com.pharmatrack.service;

import com.pharmatrack.dto.RecipeMaterialDto;
import com.pharmatrack.dto.RecipeRequestDto;
import com.pharmatrack.dto.RecipeResponseDto;
import com.pharmatrack.dto.RecipeStatusUpdateDto;
import com.pharmatrack.dto.RecipeStepDto;
import com.pharmatrack.entity.Product;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeMaterial;
import com.pharmatrack.entity.RecipeStatus;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.exception.DuplicateResourceException;
import com.pharmatrack.exception.InvalidRecipeException;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.RecipeRepository;
import com.pharmatrack.security.AuthenticatedActorResolver;
import com.pharmatrack.util.AppTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service implementation managing Master Recipe lifecycle, versioning,
 * cascade operations, business rules, and transactional append-only audit trail recording
 * attributed to the authenticated SecurityContext user.
 */
@Service
@Transactional
public class RecipeService {

    public static final String ENTITY_RECIPE = "RECIPE";
    public static final ZoneId APP_ZONE_ID = AppTime.APP_ZONE_ID;

    private final RecipeRepository recipeRepository;
    private final ProductRepository productRepository;
    private final AuditTrailService auditTrailService;
    private final AuthenticatedActorResolver authenticatedActorResolver;

    public RecipeService(RecipeRepository recipeRepository,
                         ProductRepository productRepository,
                         AuditTrailService auditTrailService) {
        this(recipeRepository, productRepository, auditTrailService, new AuthenticatedActorResolver());
    }

    @Autowired
    public RecipeService(RecipeRepository recipeRepository,
                         ProductRepository productRepository,
                         AuditTrailService auditTrailService,
                         AuthenticatedActorResolver authenticatedActorResolver) {
        this.recipeRepository = recipeRepository;
        this.productRepository = productRepository;
        this.auditTrailService = auditTrailService;
        this.authenticatedActorResolver = authenticatedActorResolver != null
                ? authenticatedActorResolver
                : new AuthenticatedActorResolver();
    }

    /**
     * Creates a new Master Recipe for an active product and records an append-only
     * {@code RECIPE_CREATED} audit entry attributed to the authenticated user.
     * Computes the next version number and auto-generates the recipe code.
     *
     * @param requestDto creation payload
     * @return response DTO representing the created recipe
     * @throws ResourceNotFoundException  if the target product does not exist
     * @throws InvalidRecipeException     if the target product is inactive or step numbers are invalid/duplicated
     * @throws DuplicateResourceException if the generated recipe code already exists
     */
    public RecipeResponseDto createRecipe(RecipeRequestDto requestDto) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null) {
            throw new InvalidRecipeException("Recipe payload cannot be null");
        }

        Product product = productRepository.findById(requestDto.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + requestDto.getProductId()));

        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw new InvalidRecipeException("Cannot create recipe for an inactive product (code: " + product.getProductCode() + ")");
        }

        int nextVersion = recipeRepository.findMaxVersionByProductId(product.getId()).orElse(0) + 1;
        String recipeCode = generateRecipeCode(product.getProductCode(), nextVersion);

        if (recipeRepository.existsByRecipeCode(recipeCode)) {
            throw new DuplicateResourceException("Recipe with code '" + recipeCode + "' already exists");
        }

        validateStepNumbers(requestDto.getSteps());

        Recipe recipe = new Recipe();
        recipe.setRecipeCode(recipeCode);
        recipe.setProduct(product);
        recipe.setVersion(nextVersion);
        recipe.setBatchSize(requestDto.getBatchSize());
        recipe.setBatchUnit(requestDto.getBatchUnit());
        recipe.setDescription(requestDto.getDescription());
        recipe.setStatus(RecipeStatus.DRAFT);

        if (requestDto.getMaterials() != null) {
            for (RecipeMaterialDto matDto : requestDto.getMaterials()) {
                recipe.addMaterial(new RecipeMaterial(matDto.getMaterialName(), matDto.getQuantity(), matDto.getUnit()));
            }
        }

        if (requestDto.getSteps() != null) {
            for (RecipeStepDto stepDto : requestDto.getSteps()) {
                recipe.addStep(new RecipeStep(stepDto.getStepNumber(), stepDto.getOperationName(), stepDto.getInstructions()));
            }
        }

        Recipe savedRecipe = recipeRepository.saveAndFlush(recipe);
        Map<String, Object> afterSnapshot = buildRecipeSnapshot(savedRecipe);

        auditTrailService.recordAudit(
                ENTITY_RECIPE,
                savedRecipe.getId(),
                savedRecipe.getRecipeCode(),
                null,
                "RECIPE_CREATED",
                resolvedActor,
                LocalDateTime.now(APP_ZONE_ID),
                truncateAuditReason("Created recipe " + savedRecipe.getRecipeCode(), 2000),
                null,
                afterSnapshot
        );

        return mapToResponseDto(savedRecipe);
    }

    /**
     * Retrieves all recipes across all products.
     *
     * @return list of recipe response DTOs
     */
    @Transactional(readOnly = true)
    public List<RecipeResponseDto> getAllRecipes() {
        return recipeRepository.findAll().stream()
                .map(this::mapToResponseDto)
                .toList();
    }

    /**
     * Retrieves a single recipe by its primary key ID.
     *
     * @param id primary key ID
     * @return response DTO
     * @throws ResourceNotFoundException if no recipe is found
     */
    @Transactional(readOnly = true)
    public RecipeResponseDto getRecipeById(Long id) {
        Recipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe not found with id: " + id));
        return mapToResponseDto(recipe);
    }

    /**
     * Retrieves all recipes associated with a particular product.
     *
     * @param productId product identifier
     * @return list of recipes for that product
     * @throws ResourceNotFoundException if the product does not exist
     */
    @Transactional(readOnly = true)
    public List<RecipeResponseDto> getRecipesByProductId(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Product not found with id: " + productId);
        }
        return recipeRepository.findByProductId(productId).stream()
                .map(this::mapToResponseDto)
                .toList();
    }

    /**
     * Updates an existing DRAFT recipe and records an append-only {@code RECIPE_UPDATED} audit entry
     * capturing full before and after snapshots including materials and steps, attributed to the authenticated user.
     * Skips redundant persistence and audit writes if no fields, materials, or steps changed.
     * Changing product, recipe code, version, or status through this endpoint is rejected.
     *
     * @param id         recipe primary key
     * @param requestDto update payload
     * @return updated recipe response DTO
     * @throws ResourceNotFoundException if the recipe is not found
     * @throws InvalidRecipeException    if the recipe is not in DRAFT status or step numbers are invalid/duplicated
     */
    public RecipeResponseDto updateRecipe(Long id, RecipeRequestDto requestDto) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();
        if (requestDto == null) {
            throw new InvalidRecipeException("Recipe payload cannot be null");
        }

        Recipe existingRecipe = recipeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe not found with id: " + id));

        if (existingRecipe.getStatus() != RecipeStatus.DRAFT) {
            throw new InvalidRecipeException("Only DRAFT recipes can be updated. Current recipe status: " + existingRecipe.getStatus());
        }

        if (requestDto.getProductId() != null && !requestDto.getProductId().equals(existingRecipe.getProduct().getId())) {
            throw new InvalidRecipeException("Cannot change the associated product of an existing recipe");
        }

        validateStepNumbers(requestDto.getSteps());

        Map<String, Object> beforeSnapshot = buildRecipeSnapshot(existingRecipe);
        Map<String, Object> candidateAfterSnapshot = buildCandidateUpdateSnapshot(existingRecipe, requestDto);
        if (beforeSnapshot.equals(candidateAfterSnapshot)) {
            return mapToResponseDto(existingRecipe);
        }

        existingRecipe.setBatchSize(requestDto.getBatchSize());
        existingRecipe.setBatchUnit(requestDto.getBatchUnit());
        existingRecipe.setDescription(requestDto.getDescription());

        // Clear existing child collections and flush to the database immediately.
        // In Hibernate's ActionQueue, EntityInsertActions execute prior to EntityDeleteActions
        // during flush. Explicitly flushing orphan deletions before adding replacement steps
        // prevents unique constraint conflicts on uk_recipe_step (recipe_id, step_number).
        existingRecipe.getMaterials().clear();
        existingRecipe.getSteps().clear();
        recipeRepository.flush();

        // Populate replacement materials maintaining bidirectional links
        if (requestDto.getMaterials() != null) {
            for (RecipeMaterialDto matDto : requestDto.getMaterials()) {
                existingRecipe.addMaterial(new RecipeMaterial(matDto.getMaterialName(), matDto.getQuantity(), matDto.getUnit()));
            }
        }

        // Populate replacement steps maintaining bidirectional links and ascending order
        if (requestDto.getSteps() != null) {
            for (RecipeStepDto stepDto : requestDto.getSteps()) {
                existingRecipe.addStep(new RecipeStep(stepDto.getStepNumber(), stepDto.getOperationName(), stepDto.getInstructions()));
            }
        }

        Recipe updatedRecipe = recipeRepository.saveAndFlush(existingRecipe);
        Map<String, Object> afterSnapshot = buildRecipeSnapshot(updatedRecipe);

        auditTrailService.recordAudit(
                ENTITY_RECIPE,
                updatedRecipe.getId(),
                updatedRecipe.getRecipeCode(),
                null,
                "RECIPE_UPDATED",
                resolvedActor,
                LocalDateTime.now(APP_ZONE_ID),
                truncateAuditReason("Updated draft recipe " + updatedRecipe.getRecipeCode(), 2000),
                beforeSnapshot,
                afterSnapshot
        );

        return mapToResponseDto(updatedRecipe);
    }

    /**
     * Updates the lifecycle status of a recipe and records append-only audit entries
     * ({@code RECIPE_APPROVED} / {@code RECIPE_OBSOLETED}) attributed to the authenticated user transactionally.
     * Approving a new version marks any previous APPROVED version of the same product OBSOLETE and audits
     * both transitions within the same transaction. Approval is rejected when the associated product is inactive.
     *
     * @param id        recipe primary key
     * @param newStatus target status
     * @return updated recipe response DTO
     * @throws ResourceNotFoundException if the recipe does not exist
     * @throws InvalidRecipeException    if the transition violates lifecycle rules or the associated product is inactive
     */
    public RecipeResponseDto updateRecipeStatus(Long id, RecipeStatus newStatus) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();
        if (newStatus == null) {
            throw new InvalidRecipeException("Target recipe status cannot be null");
        }

        Recipe existingRecipe = recipeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe not found with id: " + id));

        RecipeStatus currentStatus = existingRecipe.getStatus();

        if (currentStatus == RecipeStatus.OBSOLETE) {
            throw new InvalidRecipeException("Cannot change status of an OBSOLETE recipe");
        }

        if (currentStatus == newStatus) {
            return mapToResponseDto(existingRecipe);
        }

        if (currentStatus == RecipeStatus.DRAFT) {
            if (newStatus == RecipeStatus.APPROVED) {
                Product product = existingRecipe.getProduct();
                if (product == null || product.getStatus() != ProductStatus.ACTIVE) {
                    String productCode = product != null ? product.getProductCode() : "unknown";
                    throw new InvalidRecipeException(
                            "Cannot approve recipe '" + existingRecipe.getRecipeCode()
                                    + "' because its associated product (" + productCode + ") is inactive"
                    );
                }

                LocalDateTime actionTime = LocalDateTime.now(APP_ZONE_ID);
                Map<String, Object> targetBeforeSnapshot = buildRecipeSnapshot(existingRecipe);

                // Supersede existing approved recipes for this product and audit each obsoletion
                List<Recipe> currentlyApproved = recipeRepository.findByProductIdAndStatus(
                                product.getId(),
                                RecipeStatus.APPROVED
                        ).stream()
                        .filter(prev -> !Objects.equals(prev.getId(), existingRecipe.getId()))
                        .sorted(Comparator.comparing(Recipe::getVersion, Comparator.nullsLast(Integer::compareTo))
                                .thenComparing(Recipe::getId, Comparator.nullsLast(Long::compareTo)))
                        .toList();

                List<String> supersededCodes = new ArrayList<>();
                for (Recipe prevApproved : currentlyApproved) {
                    Map<String, Object> prevBeforeSnapshot = buildRecipeSnapshot(prevApproved);
                    prevApproved.setStatus(RecipeStatus.OBSOLETE);
                    Recipe savedPrev = recipeRepository.saveAndFlush(prevApproved);
                    Map<String, Object> prevAfterSnapshot = buildRecipeSnapshot(savedPrev);

                    auditTrailService.recordAudit(
                            ENTITY_RECIPE,
                            savedPrev.getId(),
                            savedPrev.getRecipeCode(),
                            null,
                            "RECIPE_OBSOLETED",
                            resolvedActor,
                            actionTime,
                            truncateAuditReason(
                                    "Marked recipe " + savedPrev.getRecipeCode()
                                            + " OBSOLETE upon approval of " + existingRecipe.getRecipeCode(),
                                    2000
                            ),
                            prevBeforeSnapshot,
                            prevAfterSnapshot
                    );
                    supersededCodes.add(savedPrev.getRecipeCode());
                }

                existingRecipe.setStatus(RecipeStatus.APPROVED);
                Recipe savedApproved = recipeRepository.saveAndFlush(existingRecipe);
                Map<String, Object> targetAfterSnapshot = buildRecipeSnapshot(savedApproved);

                String approvalReason = supersededCodes.isEmpty()
                        ? "Approved recipe " + savedApproved.getRecipeCode()
                        : "Approved recipe " + savedApproved.getRecipeCode()
                                + " (superseded " + String.join(", ", supersededCodes) + ")";

                auditTrailService.recordAudit(
                        ENTITY_RECIPE,
                        savedApproved.getId(),
                        savedApproved.getRecipeCode(),
                        null,
                        "RECIPE_APPROVED",
                        resolvedActor,
                        actionTime,
                        truncateAuditReason(approvalReason, 2000),
                        targetBeforeSnapshot,
                        targetAfterSnapshot
                );

                return mapToResponseDto(savedApproved);
            } else if (newStatus == RecipeStatus.OBSOLETE) {
                throw new InvalidRecipeException("A DRAFT recipe cannot be transitioned directly to OBSOLETE. It must be APPROVED first or deleted.");
            }
        } else if (currentStatus == RecipeStatus.APPROVED) {
            if (newStatus == RecipeStatus.DRAFT) {
                throw new InvalidRecipeException("Cannot revert an APPROVED recipe back to DRAFT");
            } else if (newStatus == RecipeStatus.OBSOLETE) {
                Map<String, Object> beforeSnapshot = buildRecipeSnapshot(existingRecipe);
                existingRecipe.setStatus(RecipeStatus.OBSOLETE);
                Recipe savedObsolete = recipeRepository.saveAndFlush(existingRecipe);
                Map<String, Object> afterSnapshot = buildRecipeSnapshot(savedObsolete);

                auditTrailService.recordAudit(
                        ENTITY_RECIPE,
                        savedObsolete.getId(),
                        savedObsolete.getRecipeCode(),
                        null,
                        "RECIPE_OBSOLETED",
                        resolvedActor,
                        LocalDateTime.now(APP_ZONE_ID),
                        truncateAuditReason("Marked recipe " + savedObsolete.getRecipeCode() + " OBSOLETE", 2000),
                        beforeSnapshot,
                        afterSnapshot
                );
                return mapToResponseDto(savedObsolete);
            }
        }

        throw new InvalidRecipeException(
                "Unsupported recipe status transition from " + currentStatus + " to " + newStatus
        );
    }

    /**
     * Overload for updating recipe status using DTO.
     *
     * @param id        recipe primary key
     * @param statusDto status DTO
     * @return updated recipe response DTO
     */
    public RecipeResponseDto updateRecipeStatus(Long id, RecipeStatusUpdateDto statusDto) {
        return updateRecipeStatus(id, statusDto != null ? statusDto.getStatus() : null);
    }

    /**
     * Creates a new DRAFT recipe version by cloning an existing recipe's details,
     * materials, and steps into the next incremental version, recording an append-only
     * {@code RECIPE_VERSION_CREATED} audit entry attributed to the authenticated user.
     *
     * @param sourceRecipeId source recipe ID to clone from
     * @return response DTO representing the newly created DRAFT recipe version
     * @throws ResourceNotFoundException  if the source recipe is not found
     * @throws InvalidRecipeException     if the associated product is inactive
     * @throws DuplicateResourceException if the generated code already exists
     */
    public RecipeResponseDto createNewVersion(Long sourceRecipeId) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();

        Recipe sourceRecipe = recipeRepository.findById(sourceRecipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe not found with id: " + sourceRecipeId));

        Product product = sourceRecipe.getProduct();
        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw new InvalidRecipeException("Cannot create a new recipe version for an inactive product (code: " + product.getProductCode() + ")");
        }

        int nextVersion = recipeRepository.findMaxVersionByProductId(product.getId()).orElse(0) + 1;
        String newRecipeCode = generateRecipeCode(product.getProductCode(), nextVersion);

        if (recipeRepository.existsByRecipeCode(newRecipeCode)) {
            throw new DuplicateResourceException("Recipe with code '" + newRecipeCode + "' already exists");
        }

        Recipe newRecipe = new Recipe();
        newRecipe.setProduct(product);
        newRecipe.setVersion(nextVersion);
        newRecipe.setRecipeCode(newRecipeCode);
        newRecipe.setBatchSize(sourceRecipe.getBatchSize());
        newRecipe.setBatchUnit(sourceRecipe.getBatchUnit());
        newRecipe.setDescription(sourceRecipe.getDescription());
        newRecipe.setStatus(RecipeStatus.DRAFT);

        for (RecipeMaterial mat : sourceRecipe.getMaterials()) {
            newRecipe.addMaterial(new RecipeMaterial(mat.getMaterialName(), mat.getQuantity(), mat.getUnit()));
        }

        for (RecipeStep step : sourceRecipe.getSteps()) {
            newRecipe.addStep(new RecipeStep(step.getStepNumber(), step.getOperationName(), step.getInstructions()));
        }

        Recipe saved = recipeRepository.saveAndFlush(newRecipe);
        Map<String, Object> afterSnapshot = buildRecipeSnapshot(saved);

        auditTrailService.recordAudit(
                ENTITY_RECIPE,
                saved.getId(),
                saved.getRecipeCode(),
                null,
                "RECIPE_VERSION_CREATED",
                resolvedActor,
                LocalDateTime.now(APP_ZONE_ID),
                truncateAuditReason(
                        "Created recipe version " + saved.getRecipeCode()
                                + " from " + sourceRecipe.getRecipeCode(),
                        2000
                ),
                null,
                afterSnapshot
        );

        return mapToResponseDto(saved);
    }

    /**
     * Deletes a DRAFT recipe and records an append-only {@code RECIPE_DELETED} audit entry
     * with the full pre-deletion snapshot attributed to the authenticated user.
     * Strictly permitted only when status is DRAFT.
     *
     * @param id recipe primary key
     * @throws ResourceNotFoundException if the recipe is not found
     * @throws InvalidRecipeException    if the recipe is APPROVED or OBSOLETE
     */
    public void deleteRecipe(Long id) {
        String resolvedActor = authenticatedActorResolver.requireCurrentActor();

        Recipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe not found with id: " + id));

        if (recipe.getStatus() != RecipeStatus.DRAFT) {
            throw new InvalidRecipeException("Only DRAFT recipes can be deleted. Cannot delete a recipe with status: " + recipe.getStatus());
        }

        Long recipeId = recipe.getId();
        String recipeCode = recipe.getRecipeCode();
        Map<String, Object> beforeSnapshot = buildRecipeSnapshot(recipe);

        recipeRepository.delete(recipe);
        recipeRepository.flush();

        auditTrailService.recordAudit(
                ENTITY_RECIPE,
                recipeId,
                recipeCode,
                null,
                "RECIPE_DELETED",
                resolvedActor,
                LocalDateTime.now(APP_ZONE_ID),
                truncateAuditReason("Deleted draft recipe " + recipeCode, 2000),
                beforeSnapshot,
                null
        );
    }

    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------

    private void validateStepNumbers(List<RecipeStepDto> steps) {
        if (steps == null) {
            return;
        }
        Set<Integer> seenStepNumbers = new LinkedHashSet<>();
        Set<Integer> duplicateStepNumbers = new LinkedHashSet<>();
        for (RecipeStepDto stepDto : steps) {
            if (stepDto == null || stepDto.getStepNumber() == null || stepDto.getStepNumber() <= 0) {
                throw new InvalidRecipeException("Step number must be greater than zero");
            }
            if (!seenStepNumbers.add(stepDto.getStepNumber())) {
                duplicateStepNumbers.add(stepDto.getStepNumber());
            }
        }
        if (!duplicateStepNumbers.isEmpty()) {
            String duplicatesText = duplicateStepNumbers.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));
            throw new InvalidRecipeException(
                    "Duplicate step number(s) found: " + duplicatesText
                            + ". Each manufacturing step in a recipe must have a unique step number."
            );
        }
    }

    private Map<String, Object> buildRecipeSnapshot(Recipe recipe) {
        Product product = recipe.getProduct();
        List<Map<String, Object>> materials = recipe.getMaterials() != null
                ? recipe.getMaterials().stream()
                        .map(m -> buildMaterialSnapshot(m.getMaterialName(), m.getQuantity(), m.getUnit()))
                        .toList()
                : List.of();

        List<Map<String, Object>> steps = recipe.getSteps() != null
                ? recipe.getSteps().stream()
                        .sorted(Comparator.comparing(RecipeStep::getStepNumber, Comparator.nullsLast(Integer::compareTo)))
                        .map(s -> buildStepSnapshot(s.getStepNumber(), s.getOperationName(), s.getInstructions()))
                        .toList()
                : List.of();

        return assembleRecipeSnapshot(
                recipe.getRecipeCode(),
                product != null ? product.getId() : null,
                product != null ? product.getProductCode() : null,
                product != null ? product.getProductName() : null,
                recipe.getVersion(),
                recipe.getBatchSize(),
                recipe.getBatchUnit(),
                recipe.getStatus(),
                recipe.getDescription(),
                materials,
                steps
        );
    }

    private Map<String, Object> buildCandidateUpdateSnapshot(Recipe existingRecipe, RecipeRequestDto requestDto) {
        Product product = existingRecipe.getProduct();
        List<Map<String, Object>> materials = requestDto.getMaterials() != null
                ? requestDto.getMaterials().stream()
                        .map(m -> buildMaterialSnapshot(m.getMaterialName(), m.getQuantity(), m.getUnit()))
                        .toList()
                : List.of();

        List<Map<String, Object>> steps = requestDto.getSteps() != null
                ? requestDto.getSteps().stream()
                        .sorted(Comparator.comparing(RecipeStepDto::getStepNumber, Comparator.nullsLast(Integer::compareTo)))
                        .map(s -> buildStepSnapshot(s.getStepNumber(), s.getOperationName(), s.getInstructions()))
                        .toList()
                : List.of();

        return assembleRecipeSnapshot(
                existingRecipe.getRecipeCode(),
                product != null ? product.getId() : null,
                product != null ? product.getProductCode() : null,
                product != null ? product.getProductName() : null,
                existingRecipe.getVersion(),
                requestDto.getBatchSize(),
                requestDto.getBatchUnit(),
                existingRecipe.getStatus(),
                requestDto.getDescription(),
                materials,
                steps
        );
    }

    private Map<String, Object> assembleRecipeSnapshot(String recipeCode,
                                                       Long productId,
                                                       String productCode,
                                                       String productName,
                                                       Integer version,
                                                       BigDecimal batchSize,
                                                       String batchUnit,
                                                       RecipeStatus status,
                                                       String description,
                                                       List<Map<String, Object>> materials,
                                                       List<Map<String, Object>> steps) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("recipeCode", recipeCode);
        snapshot.put("productId", productId);
        snapshot.put("productCode", productCode);
        snapshot.put("productName", productName);
        snapshot.put("version", version);
        snapshot.put("batchSize", normalizeDecimal(batchSize));
        snapshot.put("batchUnit", batchUnit);
        snapshot.put("status", status != null ? status.name() : null);
        snapshot.put("description", description);
        snapshot.put("materials", materials);
        snapshot.put("steps", steps);
        return snapshot;
    }

    private Map<String, Object> buildMaterialSnapshot(String materialName, BigDecimal quantity, String unit) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("materialName", materialName);
        map.put("quantity", normalizeDecimal(quantity));
        map.put("unit", unit);
        return map;
    }

    private Map<String, Object> buildStepSnapshot(Integer stepNumber, String operationName, String instructions) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stepNumber", stepNumber);
        map.put("operationName", operationName);
        map.put("instructions", instructions);
        return map;
    }

    private BigDecimal normalizeDecimal(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.stripTrailingZeros().toPlainString());
    }

    private String truncateAuditReason(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private String generateRecipeCode(String productCode, int version) {
        return "RCP-" + productCode + "-V" + version;
    }

    private RecipeResponseDto mapToResponseDto(Recipe recipe) {
        List<RecipeMaterialDto> materialDtos = recipe.getMaterials().stream()
                .map(m -> new RecipeMaterialDto(m.getMaterialName(), m.getQuantity(), m.getUnit()))
                .toList();

        List<RecipeStepDto> stepDtos = recipe.getSteps().stream()
                .sorted(Comparator.comparing(RecipeStep::getStepNumber))
                .map(s -> new RecipeStepDto(s.getStepNumber(), s.getOperationName(), s.getInstructions()))
                .toList();

        return new RecipeResponseDto(
                recipe.getId(),
                recipe.getRecipeCode(),
                recipe.getProduct().getId(),
                recipe.getProduct().getProductCode(),
                recipe.getProduct().getProductName(),
                recipe.getVersion(),
                recipe.getBatchSize(),
                recipe.getBatchUnit(),
                recipe.getStatus(),
                recipe.getDescription(),
                materialDtos,
                stepDtos,
                recipe.getCreatedAt(),
                recipe.getUpdatedAt()
        );
    }
}

