import { apiRequest } from './apiClient.js';
import { generateClientRequestId } from '../utils/requestIdUtils.js';

export { generateClientRequestId };

/**
 * List all conversations belonging to the authenticated user, ordered by most recently updated.
 * GET /api/ai/conversations
 *
 * @param {Object} [options]
 * @param {number} [options.page] Optional 0-based page index
 * @param {number} [options.size] Optional page size limit (max 100)
 * @returns {Promise<Array<{
 *   id: number,
 *   title: string,
 *   activeModule?: string,
 *   contextType?: string,
 *   contextId?: string,
 *   createdAt: string,
 *   updatedAt: string,
 *   messageCount: number
 * }>>} CopilotConversationSummaryDto[]
 */
export async function listConversations({ page, size } = {}) {
  const params = new URLSearchParams();
  if (page != null && !Number.isNaN(Number(page))) {
    params.set('page', String(page));
  }
  if (size != null && !Number.isNaN(Number(size))) {
    params.set('size', String(size));
  }
  const qs = params.toString();
  const endpoint = qs ? `/ai/conversations?${qs}` : '/ai/conversations';
  const result = await apiRequest(endpoint, { method: 'GET' });
  return Array.isArray(result) ? result : [];
}

/**
 * Create a new conversation thread for the authenticated user.
 * POST /api/ai/conversations
 *
 * @param {Object} params
 * @param {string} params.title Conversation title (mandatory, max 150 chars)
 * @param {string} [params.activeModule] Active module name (optional, max 50 chars)
 * @param {string} [params.contextType] Context entity type (optional, max 50 chars)
 * @param {string} [params.contextId] Context identifier (optional, max 100 chars)
 * @returns {Promise<{
 *   id: number,
 *   title: string,
 *   activeModule?: string,
 *   contextType?: string,
 *   contextId?: string,
 *   createdAt: string,
 *   updatedAt: string,
 *   messageCount: number
 * }>} CopilotConversationSummaryDto
 */
export async function createConversation({ title, activeModule, contextType, contextId }) {
  const cleanTitle = (title != null ? String(title) : '').trim();
  const payload = {
    title: cleanTitle || 'New Inquiry',
  };
  if (activeModule != null && String(activeModule).trim() !== '') {
    payload.activeModule = String(activeModule).trim();
  }
  if (contextType != null && String(contextType).trim() !== '') {
    payload.contextType = String(contextType).trim();
  }
  if (contextId != null && String(contextId).trim() !== '') {
    payload.contextId = String(contextId).trim();
  }

  return apiRequest('/ai/conversations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/**
 * Retrieve complete conversation details and chronological message transcript for an owned thread.
 * GET /api/ai/conversations/{id}
 *
 * @param {number|string} conversationId
 * @returns {Promise<{
 *   id: number,
 *   title: string,
 *   activeModule?: string,
 *   contextType?: string,
 *   contextId?: string,
 *   createdAt: string,
 *   updatedAt: string,
 *   messages: Array<{
 *     id: number,
 *     requestId?: number,
 *     role: string,
 *     content: string,
 *     model?: string,
 *     contextType?: string,
 *     contextIdentifier?: string,
 *     sources?: Array<any>,
 *     status?: string,
 *     createdAt: string
 *   }>
 * }>} CopilotConversationDetailDto
 */
export async function getConversationDetail(conversationId) {
  if (conversationId == null) {
    throw new Error('Conversation ID is required');
  }
  return apiRequest(`/ai/conversations/${conversationId}`, { method: 'GET' });
}

/**
 * Rename an owned conversation thread.
 * PATCH /api/ai/conversations/{id}
 *
 * @param {number|string} conversationId
 * @param {Object} params
 * @param {string} params.title Mandatory new conversation title (max 150 chars)
 * @returns {Promise<{
 *   id: number,
 *   title: string,
 *   activeModule?: string,
 *   contextType?: string,
 *   contextId?: string,
 *   createdAt: string,
 *   updatedAt: string,
 *   messageCount: number
 * }>} CopilotConversationSummaryDto
 */
export async function renameConversation(conversationId, { title }) {
  if (conversationId == null) {
    throw new Error('Conversation ID is required');
  }
  const cleanTitle = (title != null ? String(title) : '').trim();
  if (!cleanTitle) {
    throw new Error('Conversation title cannot be empty');
  }
  const payload = {
    title: cleanTitle,
  };
  return apiRequest(`/ai/conversations/${conversationId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/**
 * Delete an owned conversation thread and all its associated requests and messages.
 * DELETE /api/ai/conversations/{id}
 *
 * @param {number|string} conversationId
 * @returns {Promise<void>}
 */
export async function deleteConversation(conversationId) {
  if (conversationId == null) {
    throw new Error('Conversation ID is required');
  }
  return apiRequest(`/ai/conversations/${conversationId}`, {
    method: 'DELETE',
  });
}

/**
 * Submit an inquiry to an owned AI Copilot conversation thread.
 * POST /api/ai/conversations/{conversationId}/messages
 *
 * @param {number|string} conversationId
 * @param {Object} params
 * @param {string} params.clientRequestId Unique idempotency key (mandatory, max 64 chars)
 * @param {string} params.query User natural language inquiry (mandatory, max 2000 chars)
 * @param {string} [params.activeModule] Active module name (optional, max 50 chars)
 * @param {string} [params.contextType] Context entity type (optional, max 50 chars)
 * @param {string} [params.contextId] Context identifier (optional, max 100 chars)
 * @returns {Promise<{
 *   conversationId: number,
 *   requestId: number,
 *   clientRequestId: string,
 *   userMessage: Object,
 *   assistantMessage: Object,
 *   status: string
 * }>} CopilotMessageResponseDto
 */
export async function submitConversationMessage(
  conversationId,
  { clientRequestId, query, activeModule, contextType, contextId }
) {
  if (conversationId == null) {
    throw new Error('Conversation ID is required');
  }
  const cleanQuery = (query != null ? String(query) : '').trim();
  if (!cleanQuery) {
    throw new Error('Query is mandatory');
  }
  const reqId = (clientRequestId != null ? String(clientRequestId) : '').trim() || generateClientRequestId();

  const payload = {
    clientRequestId: reqId,
    query: cleanQuery,
  };
  if (activeModule != null && String(activeModule).trim() !== '') {
    payload.activeModule = String(activeModule).trim();
  }
  if (contextType != null && String(contextType).trim() !== '') {
    payload.contextType = String(contextType).trim();
  }
  if (contextId != null && String(contextId).trim() !== '') {
    payload.contextId = String(contextId).trim();
  }

  return apiRequest(`/ai/conversations/${conversationId}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

/**
 * Submit an advisory natural-language inquiry to PharmaTrack AI Copilot (single-query legacy endpoint).
 * POST /api/ai/query
 *
 * @param {Object} params
 * @param {string} params.query User natural language question (mandatory, max 2000 chars)
 * @param {string} [params.activeModule] Active frontend module ID (optional, max 50 chars)
 * @param {string} [params.contextType] Context entity type (e.g. 'MANUFACTURING_ORDER', optional, max 50 chars)
 * @param {string} [params.contextId] Context identifier (e.g. 'MO-2026-0001', optional, max 100 chars)
 * @returns {Promise<{
 *   answer: string,
 *   contextType?: string,
 *   contextIdentifier?: string,
 *   sources?: Array<{
 *     recordType: string,
 *     recordIdentifier: string,
 *     title: string,
 *     module: string,
 *     summary?: string
 *   }>,
 *   model?: string,
 *   timestamp: string,
 *   disclaimer: string
 * }>} AiQueryResponseDto
 */
export async function queryAiCopilot({ query, activeModule, contextType, contextId }) {
  const payload = {
    query: query != null ? String(query).trim() : '',
  };

  if (activeModule != null && String(activeModule).trim() !== '') {
    payload.activeModule = String(activeModule).trim();
  }
  if (contextType != null && String(contextType).trim() !== '') {
    payload.contextType = String(contextType).trim();
  }
  if (contextId != null && String(contextId).trim() !== '') {
    payload.contextId = String(contextId).trim();
  }

  return apiRequest('/ai/query', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });
}
