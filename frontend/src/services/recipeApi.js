import { apiRequest } from './apiClient.js';

/**
 * Fetch all recipes from the backend.
 */
export async function getAllRecipes() {
  return apiRequest('/recipes');
}

/**
 * Fetch a single recipe by ID.
 */
export async function getRecipeById(id) {
  return apiRequest(`/recipes/${encodeURIComponent(id)}`);
}

/**
 * Fetch recipes for a specific product.
 */
export async function getRecipesByProductId(productId) {
  return apiRequest(`/recipes/product/${encodeURIComponent(productId)}`);
}

/**
 * Create a new recipe.
 */
export async function createRecipe(recipeData) {
  return apiRequest('/recipes', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(recipeData),
  });
}

/**
 * Update an existing DRAFT recipe.
 */
export async function updateRecipe(id, recipeData) {
  return apiRequest(`/recipes/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(recipeData),
  });
}

/**
 * Update recipe lifecycle status (e.g. APPROVED).
 */
export async function updateRecipeStatus(id, status) {
  return apiRequest(`/recipes/${encodeURIComponent(id)}/status`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ status }),
  });
}

/**
 * Create a new draft version from an existing recipe.
 */
export async function createNewVersion(id) {
  return apiRequest(`/recipes/${encodeURIComponent(id)}/new-version`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
  });
}

/**
 * Delete a DRAFT recipe.
 */
export async function deleteRecipe(id) {
  return apiRequest(`/recipes/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}
