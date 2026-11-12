package com.pharmatrack.service;

import com.pharmatrack.entity.Batch;
import com.pharmatrack.entity.BatchExecutionStep;
import com.pharmatrack.entity.ExecutionStepStatus;
import com.pharmatrack.entity.ManufacturingOrder;
import com.pharmatrack.entity.Recipe;
import com.pharmatrack.entity.RecipeStep;
import com.pharmatrack.repository.BatchExecutionStepRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared package helper encapsulating {@link BatchExecutionStep} initialization,
 * recipe-step validation/sorting, and idempotent consistency verification across
 * batch start and shop-floor execution backfill workflows.
 */
final class BatchExecutionStepInitializer {

    private BatchExecutionStepInitializer() {
    }

    static final class InitializationPlan {
        private final List<BatchExecutionStep> stepsToCreate;
        private final List<BatchExecutionStep> existingSteps;

        private InitializationPlan(List<BatchExecutionStep> stepsToCreate, List<BatchExecutionStep> existingSteps) {
            this.stepsToCreate = stepsToCreate;
            this.existingSteps = existingSteps;
        }

        boolean hasStepsToCreate() {
            return !stepsToCreate.isEmpty();
        }

        List<BatchExecutionStep> getStepsToCreate() {
            return stepsToCreate;
        }

        List<BatchExecutionStep> getExistingSteps() {
            return existingSteps;
        }
    }

    /**
     * Prepare {@link BatchExecutionStep} snapshots from the order's exact recipe,
     * or verify idempotent consistency against already-persisted execution steps.
     *
     * @param batch                   target physical production batch
     * @param order                   parent manufacturing order carrying the authoritative recipe
     * @param executionStepRepository repository used to inspect existing batch execution steps
     * @param actionVerb              human-readable action verb for contextual error messages (e.g., "start batch")
     * @param exceptionFactory        caller-specific exception factory
     * @return plan indicating whether new PENDING steps should be created or existing steps reused
     */
    static InitializationPlan prepareExecutionSteps(
            Batch batch,
            ManufacturingOrder order,
            BatchExecutionStepRepository executionStepRepository,
            String actionVerb,
            Function<String, ? extends RuntimeException> exceptionFactory) {

        Recipe recipe = order != null ? order.getRecipe() : null;
        if (recipe == null) {
            String orderNumber = order != null ? order.getOrderNumber() : "unknown";
            throw exceptionFactory.apply("Cannot " + actionVerb + " " + batch.getBatchNumber()
                    + " because manufacturing order " + orderNumber + " has no associated recipe");
        }

        List<RecipeStep> rawSteps = recipe.getSteps();
        if (rawSteps == null || rawSteps.isEmpty()) {
            throw exceptionFactory.apply("Cannot " + actionVerb + " " + batch.getBatchNumber()
                    + " because recipe '" + recipe.getRecipeCode() + "' has no manufacturing steps defined");
        }

        Set<Integer> seenStepNumbers = new HashSet<>();
        for (RecipeStep step : rawSteps) {
            if (step == null
                    || step.getStepNumber() == null
                    || step.getStepNumber() < 1
                    || step.getOperationName() == null
                    || step.getOperationName().trim().isEmpty()
                    || step.getInstructions() == null
                    || step.getInstructions().trim().isEmpty()) {
                throw exceptionFactory.apply("Cannot " + actionVerb + " " + batch.getBatchNumber()
                        + " because recipe '" + recipe.getRecipeCode() + "' contains an invalid step definition");
            }
            if (!seenStepNumbers.add(step.getStepNumber())) {
                throw exceptionFactory.apply("Cannot " + actionVerb + " " + batch.getBatchNumber()
                        + " because recipe '" + recipe.getRecipeCode()
                        + "' contains duplicate step number " + step.getStepNumber());
            }
        }

        List<RecipeStep> sortedRecipeSteps = rawSteps.stream()
                .sorted(Comparator.comparing(RecipeStep::getStepNumber))
                .toList();

        List<BatchExecutionStep> existingSteps = executionStepRepository.findByBatchIdOrderByStepNumberAsc(batch.getId());
        if (existingSteps != null && !existingSteps.isEmpty()) {
            validateExistingExecutionStepsConsistency(
                    batch, order, recipe, sortedRecipeSteps, existingSteps, actionVerb, exceptionFactory);
            return new InitializationPlan(List.of(), existingSteps);
        }

        List<BatchExecutionStep> stepsToCreate = new ArrayList<>(sortedRecipeSteps.size());
        for (RecipeStep recipeStep : sortedRecipeSteps) {
            stepsToCreate.add(new BatchExecutionStep(
                    batch,
                    recipeStep,
                    recipeStep.getStepNumber(),
                    recipeStep.getOperationName(),
                    recipeStep.getInstructions(),
                    ExecutionStepStatus.PENDING
            ));
        }
        return new InitializationPlan(stepsToCreate, List.of());
    }

    private static void validateExistingExecutionStepsConsistency(
            Batch batch,
            ManufacturingOrder order,
            Recipe recipe,
            List<RecipeStep> sortedRecipeSteps,
            List<BatchExecutionStep> existingSteps,
            String actionVerb,
            Function<String, ? extends RuntimeException> exceptionFactory) {

        if (existingSteps.size() != sortedRecipeSteps.size()) {
            throw exceptionFactory.apply("Cannot " + actionVerb + " " + batch.getBatchNumber()
                    + ": existing execution steps count (" + existingSteps.size()
                    + ") does not match order recipe '" + recipe.getRecipeCode()
                    + "' version " + order.getRecipeVersion() + " step count (" + sortedRecipeSteps.size() + ")");
        }

        for (int i = 0; i < sortedRecipeSteps.size(); i++) {
            BatchExecutionStep existing = existingSteps.get(i);
            RecipeStep expected = sortedRecipeSteps.get(i);

            if (existing == null || existing.getStatus() == null
                    || !Objects.equals(existing.getStepNumber(), expected.getStepNumber())
                    || !Objects.equals(existing.getOperationName(), expected.getOperationName())
                    || !Objects.equals(existing.getInstructions(), expected.getInstructions())) {
                throw exceptionFactory.apply("Cannot " + actionVerb + " " + batch.getBatchNumber()
                        + ": existing execution step records are inconsistent with order recipe '"
                        + recipe.getRecipeCode() + "' version " + order.getRecipeVersion());
            }

            if (existing.getBatch() != null && batch.getId() != null
                    && !Objects.equals(existing.getBatch().getId(), batch.getId())) {
                throw exceptionFactory.apply("Cannot " + actionVerb + " " + batch.getBatchNumber()
                        + ": existing execution step does not belong to this batch");
            }

            if (expected.getId() != null) {
                if (existing.getRecipeStep() == null
                        || !Objects.equals(existing.getRecipeStep().getId(), expected.getId())) {
                    throw exceptionFactory.apply("Cannot " + actionVerb + " " + batch.getBatchNumber()
                            + ": existing execution step records do not match source recipe steps for '"
                            + recipe.getRecipeCode() + "' version " + order.getRecipeVersion());
                }
            }
        }
    }
}

