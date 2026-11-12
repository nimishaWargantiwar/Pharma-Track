import { apiRequest } from './apiClient.js';

/**
 * Fetch all PharmaTrack user accounts (ADMIN only).
 */
export async function getAllUsers() {
  return apiRequest('/admin/users');
}

/**
 * Fetch a single user account summary by ID (ADMIN only).
 */
export async function getUserById(id) {
  return apiRequest(`/admin/users/${encodeURIComponent(id)}`);
}

/**
 * Provision a new user account with a temporary password (ADMIN only).
 */
export async function createUser(userData) {
  return apiRequest('/admin/users', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(userData),
  });
}

/**
 * Update non-security user account profile fields such as fullName (ADMIN only).
 */
export async function updateUserProfile(id, profileData) {
  return apiRequest(`/admin/users/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(profileData),
  });
}

/**
 * Enable or disable a user account and revoke active JWT tokens (ADMIN only).
 */
export async function updateUserStatus(id, enabled) {
  return apiRequest(`/admin/users/${encodeURIComponent(id)}/status`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ enabled: Boolean(enabled) }),
  });
}

/**
 * Update a user account's assigned RBAC role and revoke active JWT tokens (ADMIN only).
 */
export async function updateUserRole(id, role) {
  return apiRequest(`/admin/users/${encodeURIComponent(id)}/role`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ role }),
  });
}

/**
 * Reset a user account's temporary password and revoke active JWT tokens (ADMIN only).
 */
export async function resetUserPassword(id, newPassword) {
  return apiRequest(`/admin/users/${encodeURIComponent(id)}/password`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ newPassword }),
  });
}

