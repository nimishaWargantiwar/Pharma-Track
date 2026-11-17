import React, { useState, useEffect, useRef, useCallback, useId, useContext } from 'react';
import {
  Sparkles,
  X,
  Send,
  RefreshCw,
  AlertCircle,
  Database,
  Plus,
  MessageSquare,
  History,
  Pencil,
  Trash2,
  ArrowLeft,
  Check,
  ChevronRight,
} from 'lucide-react';
import {
  listConversations,
  createConversation,
  getConversationDetail,
  renameConversation,
  deleteConversation,
  submitConversationMessage,
  generateClientRequestId,
  queryAiCopilot,
} from '../services/aiApi.js';
import { AuthContext } from '../context/AuthContext.jsx';
import AiSourceReferencePill from './AiSourceReferencePill.jsx';
import FormattedMessage from './FormattedMessage.jsx';

const DEFAULT_SUGGESTIONS_GLOBAL = [
  'What is the status of manufacturing order MO-2026-0001?',
  'How many batches belong to MO-2026-0001?',
  'What are the planned start and completion dates for MO-2026-0001?',
];

const DEFAULT_SUGGESTIONS_ORDER = [
  'What is the current status and batch progress?',
  'Are all batches completed and QA released?',
  'Explain the planned versus actual batch yield.',
];

const DEFAULT_SUGGESTIONS_BATCH = [
  'Provide an advisory analysis of this batch eBR execution and yield.',
  'Were there any deviations or quality anomalies recorded for this batch?',
  'Summarize the raw materials consumed and inventory lot traceability.',
];

const ORDER_NUMBER_REGEX = /^MO-\d{4}-\d{4,19}$/i;
const BATCH_NUMBER_REGEX = /^BAT-\d{4}-\d{4,19}(-\d{2})?$/i;

/**
 * Deterministically extracts verified manufacturing order context strictly from
 * programmatic backend source references, NEVER from LLM-generated text.
 * Retained for backward compatibility.
 *
 * @param {Object} response AiQueryResponseDto or CopilotMessageDto from backend
 * @returns {{ activeModule: string, contextType: string, contextId: string, title: string } | null}
 */
export function extractVerifiedOrderContext(response) {
  if (!response || typeof response !== 'object') {
    return null;
  }
  const sources = Array.isArray(response.sources)
    ? response.sources
    : Array.isArray(response.assistantMessage?.sources)
      ? response.assistantMessage.sources
      : null;

  if (!sources || sources.length === 0) {
    return null;
  }
  const orderSource = sources.find(
    (source) =>
      source &&
      source.recordType === 'MANUFACTURING_ORDER' &&
      typeof source.recordIdentifier === 'string' &&
      ORDER_NUMBER_REGEX.test(source.recordIdentifier.trim())
  );
  if (!orderSource) {
    return null;
  }
  const cleanOrderNumber = orderSource.recordIdentifier.trim().toUpperCase();
  return {
    activeModule: 'orders',
    contextType: 'MANUFACTURING_ORDER',
    contextId: cleanOrderNumber,
    title: orderSource.title || cleanOrderNumber,
  };
}

/**
 * Deterministically extracts verified context (Batch or Manufacturing Order) strictly from
 * programmatic backend source references or response contextType.
 *
 * @param {Object} response AiQueryResponseDto or CopilotMessageDto from backend
 * @returns {{ activeModule: string, contextType: string, contextId: string, title: string } | null}
 */
export function extractVerifiedContext(response) {
  if (!response || typeof response !== 'object') {
    return null;
  }
  const sources = Array.isArray(response.sources)
    ? response.sources
    : Array.isArray(response.assistantMessage?.sources)
      ? response.assistantMessage.sources
      : null;

  if (sources && sources.length > 0) {
    const batchSource = sources.find(
      (source) =>
        source &&
        source.recordType === 'BATCH' &&
        typeof source.recordIdentifier === 'string' &&
        source.recordIdentifier.trim()
    );
    if (batchSource) {
      const cleanBatchNumber = batchSource.recordIdentifier.trim();
      return {
        activeModule: 'traceability',
        contextType: 'BATCH',
        contextId: cleanBatchNumber,
        title: batchSource.title || cleanBatchNumber,
      };
    }

    const orderSource = sources.find(
      (source) =>
        source &&
        source.recordType === 'MANUFACTURING_ORDER' &&
        typeof source.recordIdentifier === 'string' &&
        ORDER_NUMBER_REGEX.test(source.recordIdentifier.trim())
    );
    if (orderSource) {
      const cleanOrderNumber = orderSource.recordIdentifier.trim().toUpperCase();
      return {
        activeModule: 'orders',
        contextType: 'MANUFACTURING_ORDER',
        contextId: cleanOrderNumber,
        title: orderSource.title || cleanOrderNumber,
      };
    }
  }

  const respContextType = response.contextType || response.assistantMessage?.contextType;
  const respContextId = response.contextIdentifier || response.assistantMessage?.contextIdentifier;
  if (respContextType === 'BATCH' && respContextId) {
    return {
      activeModule: 'traceability',
      contextType: 'BATCH',
      contextId: respContextId.trim(),
      title: `Batch ${respContextId.trim()}`,
    };
  }

  return extractVerifiedOrderContext(response);
}

/**
 * Derives a clean, readable conversation title from the first question.
 * Truncates gracefully without breaking mid-word when possible.
 */
export function deriveConversationTitle(query, contextId, contextType = null) {
  if (contextType === 'BATCH' || (contextId && String(contextId).startsWith('BAT-'))) {
    return `Batch ${contextId} Analysis`;
  }
  const trimmed = (query || '').trim();
  if (!trimmed) {
    return contextId ? `Order ${contextId} Inquiry` : 'New Inquiry';
  }
  const clean = trimmed.replace(/^["'“”]+|["'“”]+$/g, '').trim();
  const formatted = clean.charAt(0).toUpperCase() + clean.slice(1);
  if (formatted.length <= 40) {
    return formatted;
  }
  return `${formatted.substring(0, 37)}...`;
}

function formatMessageTime(isoString) {
  if (!isoString) {
    return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
  try {
    const d = new Date(isoString);
    if (!Number.isNaN(d.getTime())) {
      return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }
  } catch {
    // Fallback
  }
  return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatConversationDate(isoString) {
  if (!isoString) return '';
  try {
    const d = new Date(isoString);
    if (!Number.isNaN(d.getTime())) {
      return d.toLocaleDateString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
    }
  } catch {
    // Fallback
  }
  return '';
}

function getSessionStorage() {
  if (typeof window !== 'undefined' && window.sessionStorage) {
    return window.sessionStorage;
  }
  if (typeof sessionStorage !== 'undefined') {
    return sessionStorage;
  }
  return null;
}

/**
 * PharmaTrack AI Copilot Accessible Right-Side Sliding Drawer.
 *
 * Provides real-time advisory intelligence regarding authorized Manufacturing Orders
 * and electronic Batch Records (eBR), grounded strictly in verified database facts.
 * Connects to persistent conversation threads, message histories, and source citations.
 */
export default function AiCopilotDrawer({
  isOpen,
  onClose,
  activeContext = null,
  onClearContext = null,
  onNavigateSource = null,
  onContextChange = null,
}) {
  const titleId = useId();
  const drawerRef = useRef(null);
  const inputRef = useRef(null);
  const messagesEndRef = useRef(null);
  const previousFocusRef = useRef(null);
  const isSubmittingRef = useRef(false);
  const isCreatingRef = useRef(false);
  const isDeletingRef = useRef(false);
  const activeConversationIdRef = useRef(null);
  const fetchSeqRef = useRef(0);
  const deleteModalRef = useRef(null);
  const deleteCancelBtnRef = useRef(null);

  const authContext = useContext(AuthContext);
  const user = authContext?.user || null;
  const prevUserRef = useRef(user?.id);
  const userRef = useRef(user);
  userRef.current = user;
  const onContextChangeRef = useRef(onContextChange);
  onContextChangeRef.current = onContextChange;
  const activeContextRef = useRef(activeContext);
  activeContextRef.current = activeContext;

  // Conversation list & active thread state
  const [conversations, setConversations] = useState([]);
  const [activeConversationId, setActiveConversationId] = useState(null);
  const [isLoadingList, setIsLoadingList] = useState(false);
  const [listError, setListError] = useState(null);
  const [isHistoryView, setIsHistoryView] = useState(() => {
    if (user?.id) {
      try {
        return getSessionStorage()?.getItem(`pharmatrack_copilot_view_${user.id}`) === 'history';
      } catch {
        return false;
      }
    }
    return false;
  });

  const setHistoryViewPersisted = useCallback(
    (action) => {
      setIsHistoryView((prev) => {
        const next = typeof action === 'function' ? action(prev) : action;
        if (user?.id) {
          try {
            getSessionStorage()?.setItem(
              `pharmatrack_copilot_view_${user.id}`,
              next ? 'history' : 'chat'
            );
          } catch {
            // Ignored
          }
        }
        return next;
      });
    },
    [user?.id]
  );

  useEffect(() => {
    if (user?.id) {
      try {
        const savedView = getSessionStorage()?.getItem(`pharmatrack_copilot_view_${user.id}`);
        if (savedView === 'history') {
          setIsHistoryView(true);
        } else if (savedView === 'chat') {
          setIsHistoryView(false);
        }
      } catch {
        // Ignored
      }
    }
  }, [user?.id]);

  // Rename and Delete dialog state
  const [editingTitleId, setEditingTitleId] = useState(null);
  const [editTitleText, setEditTitleText] = useState('');
  const [confirmDeleteId, setConfirmDeleteId] = useState(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState(null);
  const [isCreating, setIsCreating] = useState(false);

  // Active chat state
  const [inputQuery, setInputQuery] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [messages, setMessages] = useState([]);
  const [lastError, setLastError] = useState(null);

  // Maintain active conversation context (Batch or Manufacturing Order)
  const [conversationContext, setConversationContext] = useState(() => {
    if (activeContext?.contextId) {
      const isBatch =
        activeContext.contextType === 'BATCH' ||
        String(activeContext.contextId).startsWith('BAT-');
      return {
        activeModule: activeContext.activeModule || (isBatch ? 'traceability' : 'orders'),
        contextType: activeContext.contextType || (isBatch ? 'BATCH' : 'MANUFACTURING_ORDER'),
        contextId: activeContext.contextId,
        title: activeContext.title || activeContext.contextId,
      };
    }
    return null;
  });

  // Keep ref synchronized
  useEffect(() => {
    activeConversationIdRef.current = activeConversationId;
  }, [activeConversationId]);

  const getStorageKey = useCallback(
    (uid) => `pharmatrack_copilot_active_conv_${uid || 'anon'}`,
    []
  );

  // Reset user-specific in-memory state on logout or identity change
  useEffect(() => {
    if (prevUserRef.current !== user?.id) {
      fetchSeqRef.current += 1;
      isSubmittingRef.current = false;
      isCreatingRef.current = false;
      isDeletingRef.current = false;
      isCreatingInitialBatchConvRef.current = false;
      processedInitialQueryRef.current = null;
      setIsLoading(false);
      setIsCreating(false);
      setIsDeleting(false);
      const oldStorageKey = getStorageKey(prevUserRef.current);
      try {
        getSessionStorage()?.removeItem(oldStorageKey);
        if (prevUserRef.current) {
          getSessionStorage()?.removeItem(`pharmatrack_copilot_open_${prevUserRef.current}`);
          getSessionStorage()?.removeItem(`pharmatrack_copilot_view_${prevUserRef.current}`);
          getSessionStorage()?.removeItem(`pharmatrack_copilot_context_${prevUserRef.current}`);
        }
      } catch {
        // Ignored
      }
      prevUserRef.current = user?.id;
      setConversations([]);
      setActiveConversationId(null);
      setConversationContext(null);
      setMessages([]);
      setLastError(null);
      setIsHistoryView(false);
      setEditingTitleId(null);
      setConfirmDeleteId(null);
      setDeleteError(null);
    }
  }, [user, getStorageKey]);

  // Synchronize when external activeContext prop changes
  useEffect(() => {
    if (activeContext?.contextId) {
      const isBatch =
        activeContext.contextType === 'BATCH' ||
        String(activeContext.contextId).startsWith('BAT-');
      setConversationContext((prev) => {
        if (
          prev?.contextId === activeContext.contextId &&
          prev?.contextType === activeContext.contextType &&
          prev?.title === activeContext.title
        ) {
          return prev;
        }
        return {
          activeModule: activeContext.activeModule || (isBatch ? 'traceability' : 'orders'),
          contextType: activeContext.contextType || (isBatch ? 'BATCH' : 'MANUFACTURING_ORDER'),
          contextId: activeContext.contextId,
          title: activeContext.title || activeContext.contextId,
        };
      });
    } else if (!activeContext) {
      setConversationContext(null);
    }
  }, [activeContext?.contextId, activeContext?.contextType, activeContext?.title, activeContext?.activeModule]);

  // Fetch conversation detail and transcript with stale response protection
  const loadConversationDetail = useCallback(
    async (convId) => {
      if (convId == null) return;
      fetchSeqRef.current += 1;
      const currentSeq = fetchSeqRef.current;

      setIsLoading(true);
      setLastError(null);

      try {
        const detail = await getConversationDetail(convId);
        if (fetchSeqRef.current !== currentSeq) {
          return; // Discard stale response
        }

        const mappedMessages = Array.isArray(detail?.messages)
          ? detail.messages.map((m) => {
              const role = (m.role || '').toLowerCase();
              return {
                id: m.id ? `msg_${m.id}` : `msg_${Date.now()}_${Math.random()}`,
                backendId: m.id,
                requestId: m.requestId,
                role: role === 'user' ? 'user' : 'assistant',
                content: m.content || '',
                model: m.model,
                contextType: m.contextType,
                contextIdentifier: m.contextIdentifier,
                sources: Array.isArray(m.sources) ? m.sources : [],
                status: m.status,
                isError: m.status === 'FAILED',
                timestamp: formatMessageTime(m.createdAt),
              };
            })
          : [];

        setMessages(mappedMessages);

        if (detail?.contextId) {
          const isBatch =
            detail.contextType === 'BATCH' ||
            String(detail.contextId).startsWith('BAT-');
          const loadedCtx = {
            activeModule: detail.activeModule || (isBatch ? 'traceability' : 'orders'),
            contextType: detail.contextType || (isBatch ? 'BATCH' : 'MANUFACTURING_ORDER'),
            contextId: detail.contextId,
            title: detail.title || detail.contextId,
          };
          setConversationContext(loadedCtx);
          onContextChangeRef.current?.(loadedCtx);
        } else {
          setConversationContext(null);
          onContextChangeRef.current?.(null);
        }
      } catch (err) {
        if (fetchSeqRef.current !== currentSeq) return;
        const status = err.status || 0;
        if (status === 404) {
          // Conversation was deleted on server
          setConversations((prev) => prev.filter((c) => c.id !== convId));
          setActiveConversationId(null);
          setMessages([]);
          try {
            getSessionStorage()?.removeItem(getStorageKey(user?.id));
          } catch {
            // Ignored
          }
        } else {
          setLastError({
            message: err.message || 'Failed to restore conversation messages.',
            status,
          });
        }
      } finally {
        if (fetchSeqRef.current === currentSeq) {
          setIsLoading(false);
        }
      }
    },
    [getStorageKey, user?.id]
  );

  // Fetch conversation list when drawer opens
  const fetchConversations = useCallback(
    async (preferredId = null) => {
      setIsLoadingList(true);
      setListError(null);

      try {
        const result = await listConversations();
        const list = Array.isArray(result) ? result : [];
        setConversations(list);

        // Determine which conversation to select
        const storedIdStr = preferredId != null ? String(preferredId) : null;
        let selectedId = null;

        if (storedIdStr) {
          const match = list.find((c) => String(c.id) === storedIdStr);
          if (match) {
            selectedId = match.id;
          }
        }

        if (selectedId == null) {
          // Check session storage
          try {
            const saved = getSessionStorage()?.getItem(getStorageKey(user?.id));
            if (saved) {
              const match = list.find((c) => String(c.id) === saved);
              if (match) {
                selectedId = match.id;
              }
            }
          } catch {
            // Ignored
          }
        }

        if (selectedId != null) {
          if (!activeContextRef.current?.initialQuery) {
            setActiveConversationId(selectedId);
            await loadConversationDetail(selectedId);
          }
        } else if (list.length > 0) {
          // If the user already has an active context (e.g. order or batch from page/refresh),
          // check if any conversation matches that context first
          const contextMatch = activeContextRef.current?.contextId
            ? list.find((c) => String(c.contextId) === String(activeContextRef.current.contextId))
            : null;

          if (contextMatch) {
            setActiveConversationId(contextMatch.id);
            try {
              getSessionStorage()?.setItem(getStorageKey(user?.id), String(contextMatch.id));
            } catch {
              // Ignored
            }
            await loadConversationDetail(contextMatch.id);
          } else if (!activeContextRef.current?.contextId && !activeContextRef.current?.initialQuery) {
            const firstId = list[0].id;
            setActiveConversationId(firstId);
            try {
              getSessionStorage()?.setItem(getStorageKey(user?.id), String(firstId));
            } catch {
              // Ignored
            }
            await loadConversationDetail(firstId);
          } else {
            setActiveConversationId(null);
            setMessages([]);
          }
        } else {
          setActiveConversationId(null);
          setMessages([]);
        }
      } catch (err) {
        setListError(err.message || 'Unable to load conversations.');
      } finally {
        setIsLoadingList(false);
      }
    },
    [getStorageKey, user?.id, loadConversationDetail]
  );

  const fetchConversationsRef = useRef(fetchConversations);
  fetchConversationsRef.current = fetchConversations;

  // Load conversation list when drawer opens
  useEffect(() => {
    if (isOpen) {
      fetchConversationsRef.current();
    }
  }, [isOpen]);

  // Manage focus when delete modal opens
  useEffect(() => {
    if (confirmDeleteId != null) {
      const timer = setTimeout(() => {
        deleteCancelBtnRef.current?.focus?.();
      }, 50);
      return () => clearTimeout(timer);
    }
  }, [confirmDeleteId]);

  // Manage drawer accessibility (focus trapping, Escape key listener, restore focus on close)
  useEffect(() => {
    if (!isOpen || typeof document === 'undefined') {
      return undefined;
    }

    previousFocusRef.current = document.activeElement || null;
    drawerRef.current?.focus?.();

    const focusTimer = setTimeout(() => {
      inputRef.current?.focus?.();
    }, 100);

    const handleKeyDown = (event) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        if (confirmDeleteId != null) {
          if (!isDeletingRef.current) {
            setConfirmDeleteId(null);
            setDeleteError(null);
          }
        } else if (editingTitleId != null) {
          setEditingTitleId(null);
        } else if (isHistoryView) {
          setHistoryViewPersisted(false);
        } else {
          onClose?.();
        }
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => {
      clearTimeout(focusTimer);
      document.removeEventListener('keydown', handleKeyDown);
      const restoreTarget = previousFocusRef.current;
      previousFocusRef.current = null;
      if (
        restoreTarget &&
        typeof restoreTarget.focus === 'function' &&
        (!document.body?.contains || document.body.contains(restoreTarget))
      ) {
        restoreTarget.focus();
      }
    };
  }, [isOpen, onClose, isHistoryView, editingTitleId, confirmDeleteId]);

  // Scroll to bottom of conversation
  useEffect(() => {
    if (isOpen && !isHistoryView) {
      messagesEndRef.current?.scrollIntoView?.({ behavior: 'smooth' });
    }
  }, [messages, isLoading, isOpen, isHistoryView]);

  const effectiveContext =
    activeConversationId != null ? conversationContext : (conversationContext || activeContext);
  const isBatchContext =
    effectiveContext?.contextType === 'BATCH' ||
    (effectiveContext?.contextId && String(effectiveContext.contextId).startsWith('BAT-'));
  const isOrderContext =
    effectiveContext?.contextType === 'MANUFACTURING_ORDER' ||
    (effectiveContext?.contextId && String(effectiveContext.contextId).startsWith('MO-'));
  const activeOrderNumber = isOrderContext ? effectiveContext?.contextId : null;
  const activeBatchNumber = isBatchContext ? effectiveContext?.contextId : null;

  const activeSuggestions = isBatchContext
    ? DEFAULT_SUGGESTIONS_BATCH
    : isOrderContext
      ? DEFAULT_SUGGESTIONS_ORDER
      : DEFAULT_SUGGESTIONS_GLOBAL;

  const activeConversation = conversations.find((c) => c.id === activeConversationId) || null;

  // Create a new separate conversation thread (never overwrites the active one)
  const handleNewChat = useCallback(async () => {
    if (isCreatingRef.current) return;
    const creatingUserId = userRef.current?.id;
    const currentSeq = fetchSeqRef.current;
    isCreatingRef.current = true;
    setIsCreating(true);
    setLastError(null);

    const currentContext = conversationContext || activeContext;
    const title = currentContext?.contextId
      ? (currentContext.contextType === 'BATCH' || String(currentContext.contextId).startsWith('BAT-')
          ? `Batch ${currentContext.contextId} Analysis`
          : `Order ${currentContext.contextId} Inquiry`)
      : 'New Inquiry';

    try {
      const newConv = await createConversation({
        title,
        activeModule: currentContext?.activeModule || (currentContext?.contextType === 'BATCH' ? 'traceability' : 'orders'),
        contextType: currentContext?.contextType || undefined,
        contextId: currentContext?.contextId || undefined,
      });

      if (fetchSeqRef.current !== currentSeq || userRef.current?.id !== creatingUserId) {
        return;
      }

      const convSummary = {
        id: newConv.id,
        title: newConv.title || title,
        activeModule: newConv.activeModule,
        contextType: newConv.contextType,
        contextId: newConv.contextId,
        createdAt: newConv.createdAt || new Date().toISOString(),
        updatedAt: newConv.updatedAt || new Date().toISOString(),
        messageCount: 0,
      };

      setConversations((prev) => [convSummary, ...prev.filter((c) => c.id !== newConv.id)]);
      setActiveConversationId(newConv.id);
      try {
        getSessionStorage()?.setItem(getStorageKey(userRef.current?.id), String(newConv.id));
        if (userRef.current?.id) {
          getSessionStorage()?.setItem(`pharmatrack_copilot_view_${userRef.current.id}`, 'chat');
        }
      } catch {
        // Ignored
      }
      setMessages([]);
      setIsHistoryView(false);

      setTimeout(() => {
        inputRef.current?.focus?.();
      }, 50);
    } catch (err) {
      if (fetchSeqRef.current !== currentSeq || userRef.current?.id !== creatingUserId) {
        return;
      }
      setLastError({
        message: err.message || 'Failed to create a new conversation thread.',
      });
    } finally {
      if (fetchSeqRef.current === currentSeq && userRef.current?.id === creatingUserId) {
        isCreatingRef.current = false;
        setIsCreating(false);
      }
    }
  }, [conversationContext, activeContext, getStorageKey]);

  // Select an existing conversation thread
  const handleSelectConversation = useCallback(
    (convId) => {
      if (convId === activeConversationId) {
        setHistoryViewPersisted(false);
        return;
      }
      setActiveConversationId(convId);
      try {
        getSessionStorage()?.setItem(getStorageKey(user?.id), String(convId));
        if (user?.id) {
          getSessionStorage()?.setItem(`pharmatrack_copilot_view_${user.id}`, 'chat');
        }
      } catch {
        // Ignored
      }
      setIsHistoryView(false);
      loadConversationDetail(convId);
    },
    [activeConversationId, getStorageKey, user?.id, loadConversationDetail, setHistoryViewPersisted]
  );

  // Rename conversation thread
  const handleStartRename = (conv, e) => {
    e?.stopPropagation?.();
    setEditingTitleId(conv.id);
    setEditTitleText(conv.title || '');
  };

  const handleSaveRename = async (convId, e) => {
    e?.stopPropagation?.();
    const clean = (editTitleText || '').trim();
    if (!clean) {
      setEditingTitleId(null);
      return;
    }

    try {
      const updated = await renameConversation(convId, { title: clean });
      setConversations((prev) =>
        prev.map((c) => (c.id === convId ? { ...c, title: updated.title || clean } : c))
      );
      setEditingTitleId(null);
    } catch (err) {
      setLastError({
        message: err.message || 'Failed to rename conversation.',
      });
      setEditingTitleId(null);
    }
  };

  // Delete conversation thread permanently with duplicate request prevention and error safety
  const handleConfirmDelete = async (convId, e) => {
    e?.stopPropagation?.();
    if (isDeletingRef.current) return;
    isDeletingRef.current = true;
    setIsDeleting(true);
    setDeleteError(null);

    try {
      await deleteConversation(convId);
      const remaining = conversations.filter((c) => c.id !== convId);
      setConversations(remaining);
      setConfirmDeleteId(null);

      if (activeConversationId === convId) {
        if (remaining.length > 0) {
          const nextConv = remaining[0];
          setActiveConversationId(nextConv.id);
          try {
            getSessionStorage()?.setItem(getStorageKey(user?.id), String(nextConv.id));
          } catch {
            // Ignored
          }
          await loadConversationDetail(nextConv.id);
        } else {
          setActiveConversationId(null);
          setMessages([]);
          try {
            getSessionStorage()?.removeItem(getStorageKey(user?.id));
          } catch {
            // Ignored
          }
        }
      }
    } catch (err) {
      const errorMsg = err.message || 'Failed to delete conversation.';
      setDeleteError(errorMsg);
      setLastError({
        message: errorMsg,
      });
      // Do not remove conversation from state upon failure
    } finally {
      isDeletingRef.current = false;
      setIsDeleting(false);
    }
  };

  // Submit query in active or newly created conversation
  const submitQuery = useCallback(
    async (queryText, retryPayload = null, forcedTargetConvId = null, forcedContext = null) => {
      const trimmed = (queryText || '').trim();
      if (!trimmed && !retryPayload) {
        return;
      }

      if (isSubmittingRef.current) {
        return;
      }

      const submittingUserId = userRef.current?.id;
      fetchSeqRef.current += 1;
      const currentSeq = fetchSeqRef.current;

      isSubmittingRef.current = true;
      setIsLoading(true);
      setLastError(null);

      const currentContext =
        forcedContext ||
        (forcedTargetConvId != null
          ? conversationContext
          : (activeConversationIdRef.current != null ? conversationContext : (conversationContext || activeContext)));
      let targetConvId = forcedTargetConvId != null ? forcedTargetConvId : activeConversationIdRef.current;

      const clientRequestId = generateClientRequestId();
      const userMessageId = `msg_user_${Date.now()}`;
      const payload = retryPayload
        ? { ...retryPayload, clientRequestId }
        : {
            clientRequestId,
            query: trimmed,
            activeModule: currentContext?.activeModule || (currentContext?.contextType === 'BATCH' ? 'traceability' : 'orders'),
            contextType: currentContext?.contextType || undefined,
            contextId: currentContext?.contextId || undefined,
          };

      if (!retryPayload) {
        setMessages((prev) => [
          ...prev,
          {
            id: userMessageId,
            role: 'user',
            content: trimmed,
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
            contextSnapshot: currentContext ? { ...currentContext } : null,
          },
        ]);
        setInputQuery('');
      }

      const isStale = () =>
        fetchSeqRef.current !== currentSeq ||
        userRef.current?.id !== submittingUserId ||
        activeConversationIdRef.current !== targetConvId;

      try {
        // If no conversation is selected, create one automatically with meaningful title
        if (targetConvId == null) {
          const autoTitle = deriveConversationTitle(trimmed, currentContext?.contextId, currentContext?.contextType);
          const newConv = await createConversation({
            title: autoTitle,
            activeModule: currentContext?.activeModule || (currentContext?.contextType === 'BATCH' ? 'traceability' : 'orders'),
            contextType: currentContext?.contextType || undefined,
            contextId: currentContext?.contextId || undefined,
          });

          if (fetchSeqRef.current !== currentSeq || userRef.current?.id !== submittingUserId) {
            return;
          }

          targetConvId = newConv.id;
          setActiveConversationId(newConv.id);
          activeConversationIdRef.current = newConv.id;
          try {
            getSessionStorage()?.setItem(getStorageKey(userRef.current?.id), String(newConv.id));
          } catch {
            // Ignored
          }
          setConversations((prev) => [
            {
              id: newConv.id,
              title: newConv.title || autoTitle,
              activeModule: newConv.activeModule,
              contextType: newConv.contextType,
              contextId: newConv.contextId,
              createdAt: newConv.createdAt || new Date().toISOString(),
              updatedAt: newConv.updatedAt || new Date().toISOString(),
              messageCount: 0,
            },
            ...prev,
          ]);
        }

        let response;
        if (targetConvId != null) {
          response = await submitConversationMessage(targetConvId, payload);
        } else {
          // Fallback legacy query
          response = await queryAiCopilot(payload);
        }

        // Stale response guard
        if (isStale()) {
          return;
        }

        const assistantMsgObj = response.assistantMessage || response;
        const answerText = assistantMsgObj.content ?? response.answer ?? '';
        const modelName = assistantMsgObj.model ?? response.model ?? 'gpt-6-luna';
        const sourcesList = Array.isArray(assistantMsgObj.sources)
          ? assistantMsgObj.sources
          : Array.isArray(response.sources)
            ? response.sources
            : [];
        const status = response.status || assistantMsgObj.status || 'COMPLETED';
        const isFailed = status === 'FAILED';

        // Establish / update verified active context
        const verifiedContext = extractVerifiedContext(response);
        if (verifiedContext) {
          setConversationContext(verifiedContext);
          onContextChangeRef.current?.(verifiedContext);
        }

        const assistantMessageId = `msg_assistant_${Date.now()}`;
        setMessages((prev) => [
          ...prev,
          {
            id: assistantMessageId,
            role: 'assistant',
            content: answerText,
            model: modelName,
            contextType: assistantMsgObj.contextType || response.contextType,
            contextIdentifier: assistantMsgObj.contextIdentifier || response.contextIdentifier,
            sources: sourcesList,
            status,
            isError: isFailed,
            disclaimer: response.disclaimer,
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
          },
        ]);

        // Touch conversation recency in local list
        setConversations((prev) => {
          const match = prev.find((c) => c.id === targetConvId);
          if (!match) return prev;
          const updated = {
            ...match,
            messageCount: (match.messageCount || 0) + 2,
            updatedAt: new Date().toISOString(),
          };
          return [updated, ...prev.filter((c) => c.id !== targetConvId)];
        });
      } catch (err) {
        if (isStale()) {
          return;
        }

        const errorMessageId = `msg_err_${Date.now()}`;
        const status = err.status || 0;
        let userSafeMessage = err.message || 'An error occurred while communicating with PharmaTrack AI Copilot.';
        let canRetry = true;

        if (status === 503) {
          userSafeMessage =
            'PharmaTrack AI Copilot is currently disabled in system configuration. Contact your system administrator to enable AI advisory services.';
          canRetry = false;
        } else if (status === 504) {
          userSafeMessage =
            'The AI service timed out while analyzing records. The system may be experiencing high load; please retry shortly.';
        } else if (status === 429) {
          userSafeMessage =
            'AI query rate limit reached. Please wait a brief moment before submitting your question again.';
        } else if (status === 409) {
          userSafeMessage =
            'A duplicate request conflict occurred. Please retry your inquiry with a new request ID.';
        } else if (status === 502) {
          userSafeMessage =
            'The AI service is temporarily unavailable. PharmaTrack records remain secure; please retry in a few moments.';
        } else if (status === 400) {
          userSafeMessage = err.message || 'Please provide a valid question.';
          canRetry = false;
        } else if (err.isNetworkError || status === 0) {
          userSafeMessage =
            'Unable to reach the PharmaTrack backend server. Please verify your network connection and server status.';
        }

        setLastError({
          message: userSafeMessage,
          status,
          canRetry,
          payload,
        });

        setMessages((prev) => [
          ...prev,
          {
            id: errorMessageId,
            role: 'assistant',
            isError: true,
            content: userSafeMessage,
            status,
            canRetry,
            failedPayload: payload,
            timestamp: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
          },
        ]);
      } finally {
        if (!isStale()) {
          isSubmittingRef.current = false;
          setIsLoading(false);
        }
      }
    },
    [conversationContext, activeContext, getStorageKey]
  );

  const processedInitialQueryRef = useRef(null);
  const isCreatingInitialBatchConvRef = useRef(false);

  useEffect(() => {
    if (
      isOpen &&
      activeContext?.initialQuery &&
      activeContext?.contextId
    ) {
      const queryKey = `${user?.id || 'anon'}_${activeContext.contextId}_${activeContext.initialQuery}`;
      if (
        processedInitialQueryRef.current === queryKey ||
        isCreatingInitialBatchConvRef.current
      ) {
        return;
      }
      processedInitialQueryRef.current = queryKey;
      isCreatingInitialBatchConvRef.current = true;
      setIsHistoryView(false);

      const runDedicatedBatchAnalysis = async () => {
        const currentUserId = userRef.current?.id;
        fetchSeqRef.current += 1;
        const currentSeq = fetchSeqRef.current;

        setIsLoading(true);
        setLastError(null);
        setMessages([]);

        const targetCtx = {
          activeModule: activeContext.activeModule || 'traceability',
          contextType: activeContext.contextType || 'BATCH',
          contextId: activeContext.contextId,
          title: activeContext.title || `Batch ${activeContext.contextId}`,
        };
        setConversationContext(targetCtx);
        onContextChangeRef.current?.(targetCtx);

        const convTitle =
          targetCtx.contextType === 'BATCH'
            ? `Batch ${targetCtx.contextId} Analysis`
            : deriveConversationTitle(
                activeContext.initialQuery,
                targetCtx.contextId,
                targetCtx.contextType
              );

        try {
          const newConv = await createConversation({
            title: convTitle,
            activeModule: targetCtx.activeModule,
            contextType: targetCtx.contextType,
            contextId: targetCtx.contextId,
          });

          if (
            fetchSeqRef.current !== currentSeq ||
            userRef.current?.id !== currentUserId
          ) {
            return;
          }

          setActiveConversationId(newConv.id);
          activeConversationIdRef.current = newConv.id;
          try {
            getSessionStorage()?.setItem(
              getStorageKey(currentUserId),
              String(newConv.id)
            );
            if (currentUserId) {
              getSessionStorage()?.setItem(
                `pharmatrack_copilot_view_${currentUserId}`,
                'chat'
              );
            }
          } catch {
            // Ignored
          }

          setConversations((prev) => [
            {
              id: newConv.id,
              title: newConv.title || convTitle,
              activeModule: newConv.activeModule,
              contextType: newConv.contextType,
              contextId: newConv.contextId,
              createdAt: newConv.createdAt || new Date().toISOString(),
              updatedAt: newConv.updatedAt || new Date().toISOString(),
              messageCount: 0,
            },
            ...prev.filter((c) => c.id !== newConv.id),
          ]);

          await submitQuery(activeContext.initialQuery, null, newConv.id, targetCtx);
        } catch (err) {
          if (
            fetchSeqRef.current === currentSeq &&
            userRef.current?.id === currentUserId
          ) {
            setIsLoading(false);
            setLastError({
              message:
                err.message ||
                'Failed to initialize conversation for batch analysis.',
            });
          }
        } finally {
          isCreatingInitialBatchConvRef.current = false;
        }
      };

      runDedicatedBatchAnalysis();
    }
  }, [
    isOpen,
    activeContext?.contextId,
    activeContext?.contextType,
    activeContext?.initialQuery,
    activeContext?.title,
    activeContext?.activeModule,
    user?.id,
    getStorageKey,
    submitQuery,
  ]);

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      if (!isLoading && inputQuery.trim()) {
        submitQuery(inputQuery);
      }
    }
  };

  const handleSuggestionClick = (suggestion) => {
    if (!isLoading) {
      submitQuery(suggestion);
    }
  };

  // Clears only the active context without deleting messages or conversations
  const handleClearContext = () => {
    setConversationContext(null);
    if (user?.id) {
      try {
        getSessionStorage()?.removeItem(`pharmatrack_copilot_context_${user.id}`);
      } catch {
        // Ignored
      }
    }
    onClearContext?.();
    onContextChangeRef.current?.(null);
  };

  const handleSourceNavigate = useCallback(
    (source) => {
      onNavigateSource?.(source);
      onClose?.();
    },
    [onNavigateSource, onClose]
  );

  if (!isOpen) {
    return null;
  }

  return (
    <div
      className="fixed inset-0 z-50 flex justify-end"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
    >
      {/* Click-outside backdrop scrim */}
      <div
        aria-hidden="true"
        onClick={onClose}
        className="fixed inset-0 bg-slate-900/40 backdrop-blur-xs transition-opacity animate-backdrop-enter"
      />

      {/* Slide-out Drawer Panel */}
      <aside
        ref={drawerRef}
        tabIndex={-1}
        data-testid="ai-copilot-drawer"
        className="relative z-10 w-full max-w-full sm:max-w-md lg:max-w-lg bg-white shadow-2xl border-l border-slate-200 flex flex-col h-full focus:outline-none animate-modal-enter"
      >
        {/* Unified Premium Copilot Header */}
        <div className="px-4 py-3 bg-slate-900 text-white border-b border-slate-800 flex items-center justify-between gap-3 shrink-0">
          <div className="flex items-center space-x-2.5 min-w-0">
            <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-pharma-600 to-sky-600 border border-sky-400/30 flex items-center justify-center text-white shadow-sm shrink-0">
              <Sparkles className="w-4 h-4 text-sky-200" aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <div className="flex items-center space-x-2">
                <h2 id={titleId} className="text-sm font-bold tracking-tight text-white truncate">
                  PharmaTrack AI Copilot
                </h2>
                <span className="px-1.5 py-0.5 text-[10px] font-semibold bg-sky-950/80 text-sky-300 rounded border border-sky-700/60 uppercase tracking-wider">
                  Advisory
                </span>
              </div>
              <p className="text-[11px] text-slate-300 truncate">
                Grounded in verified MES database records
              </p>
            </div>
          </div>

          <div className="flex items-center space-x-2 shrink-0">
            {/* New Chat Button: Creates a separate conversation thread */}
            <button
              type="button"
              onClick={handleNewChat}
              disabled={isCreating}
              data-testid="copilot-new-chat-button"
              title="New Chat: Start a separate conversation thread"
              aria-label="New Chat"
              className="inline-flex items-center space-x-1.5 px-2.5 py-1 rounded-lg text-xs font-semibold bg-pharma-700 hover:bg-pharma-600 text-white border border-pharma-500/40 transition-colors disabled:opacity-50 shadow-2xs focus:outline-none focus:ring-2 focus:ring-sky-400"
            >
              <Plus className="w-3.5 h-3.5" aria-hidden="true" />
              <span>New Chat</span>
            </button>

            {/* Chat History Toggle: Opens conversation history */}
            <button
              type="button"
              onClick={() => setHistoryViewPersisted((prev) => !prev)}
              data-testid="copilot-history-toggle"
              title={isHistoryView ? 'Return to active chat' : 'Chat History: View previous conversations'}
              aria-label={isHistoryView ? 'Back to chat' : 'Chat History'}
              className={`inline-flex items-center space-x-1.5 px-2.5 py-1 rounded-lg text-xs font-semibold border transition-colors focus:outline-none focus:ring-2 focus:ring-sky-400 ${
                isHistoryView
                  ? 'bg-slate-800 text-sky-300 border-sky-500/40 shadow-inner'
                  : 'text-slate-200 hover:text-white hover:bg-slate-800 border-slate-700'
              }`}
            >
              <History className="w-3.5 h-3.5" aria-hidden="true" />
              <span>{isHistoryView ? 'Back to Chat' : 'Chat History'}</span>
            </button>

            {/* Close Drawer Button */}
            <button
              type="button"
              onClick={onClose}
              aria-label="Close AI Copilot drawer"
              data-testid="close-copilot-drawer"
              title="Close Copilot drawer"
              className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 focus:outline-none focus:ring-2 focus:ring-sky-400 transition-colors"
            >
              <X className="w-5 h-5" aria-hidden="true" />
            </button>
          </div>
        </div>

        {/* Integrated Sub-Bar (Active Thread & Context) */}
        {!isHistoryView && (
          <div className="bg-slate-50/90 border-b border-slate-200/80 px-4 py-2 shrink-0 space-y-1.5 shadow-2xs">
            {/* Active Thread Details & Actions */}
            <div className="flex items-center justify-between text-xs gap-2">
              <div className="flex items-center space-x-1.5 min-w-0">
                <span className="px-1.5 py-0.2 text-[10px] font-bold uppercase tracking-wider bg-slate-200 text-slate-700 rounded shrink-0">
                  Thread
                </span>
                <MessageSquare className="w-3.5 h-3.5 text-pharma-600 shrink-0" aria-hidden="true" />
                <span className="font-semibold text-slate-900 truncate" data-testid="copilot-active-title">
                  {activeConversation?.title || 'New Inquiry'}
                </span>
              </div>

              {activeConversation && (
                <div className="flex items-center space-x-1.5 shrink-0">
                  {/* Rename Conversation Button */}
                  <button
                    type="button"
                    onClick={(e) => handleStartRename(activeConversation, e)}
                    data-testid="copilot-rename-button"
                    title="Rename this conversation thread"
                    aria-label="Rename conversation"
                    className="inline-flex items-center space-x-1 px-2 py-0.5 text-[11px] font-medium text-slate-600 hover:text-pharma-700 hover:bg-slate-200/70 rounded transition-colors"
                  >
                    <Pencil className="w-3 h-3 text-slate-400" aria-hidden="true" />
                    <span>Rename</span>
                  </button>

                  {/* Delete Conversation Button: opens confirmation popup */}
                  <button
                    type="button"
                    onClick={() => {
                      setConfirmDeleteId(activeConversation.id);
                      setDeleteError(null);
                    }}
                    data-testid="copilot-delete-button"
                    title="Delete this conversation thread permanently"
                    aria-label="Delete Conversation"
                    className="inline-flex items-center space-x-1 px-2 py-0.5 text-[11px] font-medium text-slate-600 hover:text-rose-600 hover:bg-rose-50 rounded transition-colors"
                  >
                    <Trash2 className="w-3 h-3 text-slate-400 group-hover:text-rose-600" aria-hidden="true" />
                    <span>Delete Conversation</span>
                  </button>
                </div>
              )}
            </div>

            {/* Context Indicator & Clear Context Action */}
            <div className="flex items-center justify-between text-xs gap-2 pt-1 border-t border-slate-200/60">
              <div className="flex items-center space-x-1.5 min-w-0 text-[11px]">
                <Database className="w-3 h-3 text-pharma-600 shrink-0" aria-hidden="true" />
                <span className="text-slate-500 shrink-0 font-medium">Context:</span>
                {effectiveContext?.contextId ? (
                  <span
                    data-testid="active-context-badge"
                    className={`inline-flex items-center px-2 py-0.5 rounded font-mono font-semibold text-[11px] truncate ${
                      isBatchContext
                        ? 'bg-teal-100 text-teal-800 border border-teal-200/90'
                        : 'bg-sky-100 text-sky-800 border border-sky-200/90'
                    }`}
                  >
                    {isBatchContext && (
                      <span className="mr-1 font-sans uppercase text-[10px] tracking-wider text-teal-700 font-bold">
                        Batch
                      </span>
                    )}
                    {effectiveContext.contextId}
                    {effectiveContext?.title && ` (${effectiveContext.title})`}
                  </span>
                ) : (
                  <span className="text-slate-500 italic truncate text-[11px]">
                    Global (specify order number e.g. MO-2026-0001)
                  </span>
                )}
              </div>
              {/* Clear Context: Clears ONLY the active context, preserving all messages and conversations */}
              {effectiveContext?.contextId && (
                <button
                  type="button"
                  onClick={handleClearContext}
                  data-testid="clear-context-button"
                  title="Clear Context: Clears focus without deleting any messages or conversation history"
                  aria-label={isBatchContext ? "Clear active batch context" : "Clear active order context"}
                  className="inline-flex items-center space-x-1 text-[11px] font-semibold text-slate-600 hover:text-rose-600 px-2 py-0.5 rounded hover:bg-rose-50 transition-colors shrink-0"
                >
                  <X className="w-3 h-3 text-slate-400 hover:text-rose-600" aria-hidden="true" />
                  <span>Clear Context</span>
                </button>
              )}
            </div>
          </div>
        )}

        {/* Inline Rename Input Banner */}
        {editingTitleId != null && (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              handleSaveRename(editingTitleId, e);
            }}
            className="p-3 bg-sky-50 border-b border-sky-200 text-xs flex items-center gap-2 shrink-0 animate-fade-in shadow-2xs"
          >
            <input
              type="text"
              autoFocus
              maxLength={150}
              value={editTitleText}
              onChange={(e) => setEditTitleText(e.target.value)}
              data-testid={`rename-input-${editingTitleId}`}
              placeholder="Conversation title..."
              className="flex-1 px-2.5 py-1 rounded-lg border border-sky-300 focus:outline-none focus:ring-2 focus:ring-sky-400 bg-white text-xs font-medium text-slate-900 placeholder:text-slate-400"
            />
            <button
              type="submit"
              onClick={(e) => handleSaveRename(editingTitleId, e)}
              data-testid={`save-rename-${editingTitleId}`}
              className="px-2.5 py-1 bg-pharma-600 hover:bg-pharma-700 text-white font-semibold rounded-lg text-xs shadow-2xs transition-colors"
            >
              Save
            </button>
            <button
              type="button"
              onClick={() => setEditingTitleId(null)}
              data-testid={`cancel-rename-${editingTitleId}`}
              className="px-2 py-1 text-slate-600 hover:text-slate-800 text-xs font-medium rounded hover:bg-sky-100/60 transition-colors"
            >
              Cancel
            </button>
          </form>
        )}

        {/* View Switch: History List vs Chat View */}
        {isHistoryView ? (
          /* CONVERSATION HISTORY PANEL */
          <div className="flex-1 flex flex-col p-4 overflow-y-auto space-y-3" data-testid="copilot-history-panel">
            <div className="flex items-center justify-between pb-2 border-b border-slate-200">
              <div className="flex items-center space-x-2">
                <button
                  type="button"
                  onClick={() => setHistoryViewPersisted(false)}
                  data-testid="copilot-back-to-chat"
                  title="Back to chat"
                  className="p-1 rounded-lg text-slate-500 hover:text-slate-800 hover:bg-slate-100 transition-colors"
                >
                  <ArrowLeft className="w-4 h-4" aria-hidden="true" />
                </button>
                <h3 className="text-xs font-bold uppercase tracking-wider text-slate-700">
                  Your Conversations ({conversations.length})
                </h3>
              </div>
              <button
                type="button"
                onClick={handleNewChat}
                disabled={isCreating}
                data-testid="copilot-history-new-chat-btn"
                title="Start a new conversation thread"
                className="inline-flex items-center space-x-1 px-2.5 py-1 rounded-lg bg-pharma-600 hover:bg-pharma-700 text-white text-xs font-semibold shadow-2xs transition-colors disabled:opacity-50"
              >
                <Plus className="w-3.5 h-3.5" aria-hidden="true" />
                <span>New Chat</span>
              </button>
            </div>

            {isLoadingList && (
              <div className="p-8 text-center text-xs text-slate-500 flex flex-col items-center justify-center space-y-2">
                <RefreshCw className="w-5 h-5 animate-spin text-pharma-600" aria-hidden="true" />
                <span>Loading conversations...</span>
              </div>
            )}

            {listError && (
              <div className="p-3 bg-rose-50 border border-rose-200 rounded-xl text-xs text-rose-800 flex items-center justify-between shadow-2xs">
                <span>{listError}</span>
                <button
                  type="button"
                  onClick={() => fetchConversations()}
                  data-testid="copilot-retry-list"
                  className="text-xs font-bold text-rose-700 hover:underline"
                >
                  Retry
                </button>
              </div>
            )}

            {!isLoadingList && conversations.length === 0 && (
              <div className="p-8 text-center text-xs text-slate-500 space-y-2.5 bg-slate-50/60 rounded-2xl border border-slate-200/80 my-4">
                <div className="w-10 h-10 rounded-full bg-slate-100 flex items-center justify-center mx-auto text-slate-400">
                  <MessageSquare className="w-5 h-5" aria-hidden="true" />
                </div>
                <p className="font-semibold text-slate-800 text-sm">No conversations yet</p>
                <p className="text-[11px] text-slate-500 max-w-xs mx-auto">
                  Start a new inquiry to begin discussing orders, batch yields, and electronic records.
                </p>
                <button
                  type="button"
                  onClick={handleNewChat}
                  className="mt-3 inline-flex items-center space-x-1.5 px-3.5 py-1.5 rounded-lg bg-pharma-600 text-white font-semibold text-xs hover:bg-pharma-700 shadow-2xs transition-colors"
                >
                  <Plus className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>Start a Chat</span>
                </button>
              </div>
            )}

            {!isLoadingList && conversations.length > 0 && (
              <div className="space-y-2" data-testid="copilot-conversation-list">
                {conversations.map((conv) => {
                  const isActive = conv.id === activeConversationId;
                  return (
                    <div
                      key={conv.id}
                      data-testid={`copilot-conversation-item-${conv.id}`}
                      onClick={() => handleSelectConversation(conv.id)}
                      className={`group relative p-3 rounded-xl border text-xs cursor-pointer transition-all ${
                        isActive
                          ? 'bg-sky-50/90 border-sky-300 border-l-4 border-l-sky-600 shadow-2xs ring-1 ring-sky-200'
                          : 'bg-white border-slate-200/90 hover:bg-slate-50/80 hover:border-slate-300'
                      }`}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center space-x-2">
                            <span className="font-semibold text-slate-900 truncate">
                              {conv.title}
                            </span>
                            {isActive && (
                              <span className="inline-flex items-center space-x-1 px-1.5 py-0.2 rounded text-[10px] font-bold uppercase tracking-wider bg-sky-600 text-white shrink-0">
                                <Check className="w-3 h-3" aria-hidden="true" />
                                <span>Active</span>
                              </span>
                            )}
                          </div>
                          <div className="flex items-center space-x-2 text-[10px] text-slate-500 mt-1">
                            <span>{formatConversationDate(conv.updatedAt)}</span>
                            <span>&bull;</span>
                            <span>{conv.messageCount || 0} messages</span>
                            {conv.contextId && (
                              <>
                                <span>&bull;</span>
                                <span className="font-mono bg-slate-100 px-1 py-0.2 rounded text-slate-600 border border-slate-200/60">
                                  {conv.contextId}
                                </span>
                              </>
                            )}
                          </div>
                        </div>

                        {/* Inline Actions with visible text labels */}
                        <div className="flex items-center space-x-1 shrink-0 opacity-80 group-hover:opacity-100">
                          <button
                            type="button"
                            onClick={(e) => handleStartRename(conv, e)}
                            data-testid={`rename-conv-${conv.id}`}
                            title={`Rename ${conv.title}`}
                            aria-label={`Rename ${conv.title}`}
                            className="inline-flex items-center space-x-1 px-1.5 py-0.5 rounded text-xs text-slate-500 hover:text-pharma-700 hover:bg-slate-100 transition-colors"
                          >
                            <Pencil className="w-3 h-3" aria-hidden="true" />
                            <span className="text-[11px]">Rename</span>
                          </button>
                          <button
                            type="button"
                            onClick={(e) => {
                              e.stopPropagation();
                              setConfirmDeleteId(conv.id);
                              setDeleteError(null);
                            }}
                            data-testid={`delete-conv-${conv.id}`}
                            title={`Delete ${conv.title}`}
                            aria-label={`Delete ${conv.title}`}
                            className="inline-flex items-center space-x-1 px-1.5 py-0.5 rounded text-xs text-slate-500 hover:text-rose-600 hover:bg-slate-100 transition-colors"
                          >
                            <Trash2 className="w-3 h-3" aria-hidden="true" />
                            <span className="text-[11px]">Delete</span>
                          </button>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        ) : (
          /* CHAT VIEW */
          <>
            {/* Conversation Message List Area */}
            <div
              tabIndex={0}
              aria-label="Conversation Messages"
              data-testid="copilot-messages-container"
              className="flex-1 p-4 overflow-y-auto space-y-4 focus:outline-none"
            >
              {/* Welcome Card when conversation is empty */}
              {messages.length === 0 && !isLoading && (
                <div className="space-y-4 py-2 animate-modal-enter">
                  <div className="p-4 rounded-2xl bg-gradient-to-br from-pharma-50/80 via-white to-sky-50/50 border border-pharma-100 shadow-2xs space-y-3">
                    <div className="flex items-center space-x-2 text-pharma-800 font-semibold text-sm">
                      <div className="w-6 h-6 rounded-md bg-pharma-100 flex items-center justify-center text-pharma-700">
                        <Sparkles className="w-3.5 h-3.5" aria-hidden="true" />
                      </div>
                      <span>Manufacturing Order Intelligence</span>
                    </div>
                    <p className="text-xs text-slate-600 leading-relaxed">
                      PharmaTrack AI Copilot is an operational MES assistant. Ask questions about manufacturing
                      orders, child batch statuses, planned versus actual yields, and production execution progress.
                    </p>
                    <div className="p-3 rounded-xl bg-amber-50/80 border border-amber-200/80 text-[11px] text-amber-900 flex items-start space-x-2.5 shadow-2xs">
                      <AlertCircle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" aria-hidden="true" />
                      <span className="leading-snug">
                        <strong>Advisory Guidance Only:</strong> Answers are retrieved strictly from verified PharmaTrack database records. Final QA release decisions and shop-floor execution require formal electronic signatures.
                      </span>
                    </div>
                  </div>

                  {/* Suggested Questions */}
                  <div className="space-y-2">
                    <div className="text-[11px] font-bold uppercase tracking-wider text-slate-500 px-1">
                      Suggested Questions
                    </div>
                    <div className="space-y-1.5">
                      {activeSuggestions.map((suggestion, idx) => (
                        <button
                          key={idx}
                          type="button"
                          disabled={isLoading}
                          onClick={() => handleSuggestionClick(suggestion)}
                          className="w-full text-left px-3 py-2.5 rounded-xl text-xs font-medium text-slate-700 bg-white hover:bg-sky-50/70 hover:text-pharma-800 border border-slate-200/80 hover:border-sky-300 shadow-2xs hover:shadow-xs transition-all disabled:opacity-50 flex items-center justify-between group"
                        >
                          <span className="truncate">&ldquo;{suggestion}&rdquo;</span>
                          <ChevronRight className="w-3.5 h-3.5 text-slate-400 group-hover:text-pharma-600 group-hover:translate-x-0.5 transition-all shrink-0 ml-1.5" aria-hidden="true" />
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              )}

              {/* Render Conversational Messages */}
              {messages.map((msg) => (
                <div
                  key={msg.id}
                  data-testid={`message-${msg.role}`}
                  className={`flex flex-col ${msg.role === 'user' ? 'items-end' : 'items-start'}`}
                >
                  {/* Message Bubble Header / Timestamp */}
                  <div className="flex items-center space-x-1.5 text-[10px] text-slate-400 mb-1 px-1">
                    <span className="font-medium text-slate-600">{msg.role === 'user' ? 'You' : 'PharmaTrack Copilot'}</span>
                    {msg.model && msg.role === 'assistant' && (
                      <span className="font-mono text-[9px] px-1 py-0.2 rounded bg-slate-100 text-slate-500 border border-slate-200">
                        {msg.model}
                      </span>
                    )}
                    <span>&bull;</span>
                    <span>{msg.timestamp}</span>
                  </div>

                  {/* Message Bubble Body */}
                  <div
                    className={`max-w-[92%] rounded-2xl px-4 py-3 text-xs leading-relaxed shadow-xs ${
                      msg.role === 'user'
                        ? 'bg-pharma-600 text-white rounded-br-xs whitespace-pre-wrap font-normal'
                        : msg.isError
                          ? 'bg-rose-50 text-rose-900 border border-rose-200 rounded-bl-xs whitespace-pre-wrap'
                          : msg.status === 'PENDING'
                            ? 'bg-sky-50 text-sky-900 border border-sky-200 rounded-bl-xs animate-pulse'
                            : 'bg-slate-50/90 text-slate-800 border border-slate-200/90 rounded-bl-xs shadow-2xs'
                    }`}
                  >
                    {/* Safe React Markdown rendering without dangerouslySetInnerHTML */}
                    {msg.role === 'assistant' && !msg.isError ? (
                      <FormattedMessage content={msg.content} />
                    ) : (
                      <div>{msg.content}</div>
                    )}

                    {/* GxP Advisory Disclaimer Notice */}
                    {msg.role === 'assistant' && !msg.isError && (
                      <div
                        data-testid="copilot-advisory-disclaimer"
                        className="mt-2.5 pt-2 border-t border-slate-200/60 text-[10px] text-slate-500 italic flex items-start gap-1"
                      >
                        <span className="font-semibold not-italic text-slate-600 shrink-0">Advisory Notice:</span>
                        <span>
                          {msg.disclaimer ||
                            'AI Batch Analysis is advisory intelligence intended for Production Supervisors. All parameters, deviations, and calculations must be verified against official electronic batch records (eBR) before taking action.'}
                        </span>
                      </div>
                    )}

                    {/* Retry action for failed requests */}
                    {msg.isError && msg.canRetry && msg.failedPayload && (
                      <div className="mt-3 pt-2 border-t border-rose-200/70 flex items-center justify-between">
                        <span className="text-[11px] text-rose-700 font-medium">Request failed</span>
                        <button
                          type="button"
                          disabled={isLoading}
                          onClick={() => submitQuery(null, msg.failedPayload)}
                          className="inline-flex items-center space-x-1 px-2.5 py-1 rounded-lg bg-rose-600 hover:bg-rose-700 text-white text-[11px] font-semibold transition-colors disabled:opacity-50 shadow-2xs"
                        >
                          <RefreshCw className="w-3 h-3" aria-hidden="true" />
                          <span>Retry</span>
                        </button>
                      </div>
                    )}

                    {/* Verified Source Reference Citations */}
                    {msg.sources && msg.sources.length > 0 && (
                      <div className="mt-3.5 pt-2.5 border-t border-slate-200/90 space-y-2">
                        <div className="flex items-center space-x-1.5 text-[10px] font-bold uppercase tracking-wider text-slate-500">
                          <Database className="w-3.5 h-3.5 text-pharma-600 shrink-0" aria-hidden="true" />
                          <span>Verified PharmaTrack Records ({msg.sources.length})</span>
                        </div>
                        <div
                          role="list"
                          aria-label="Verified source records"
                          className="flex flex-wrap items-center gap-2 pt-0.5"
                          data-testid="verified-sources-list"
                        >
                          {msg.sources.map((source, sIdx) => (
                            <div key={sIdx} role="listitem">
                              <AiSourceReferencePill
                                source={source}
                                onNavigate={handleSourceNavigate}
                              />
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              ))}

              {/* Loading Indicator */}
              {isLoading && (
                <div
                  data-testid="copilot-loading-indicator"
                  className="flex items-center space-x-2.5 text-xs text-pharma-800 bg-sky-50/80 border border-sky-200/80 rounded-xl px-3.5 py-2.5 w-fit shadow-2xs animate-pulse"
                >
                  <RefreshCw className="w-3.5 h-3.5 animate-spin text-pharma-600" aria-hidden="true" />
                  <span className="font-medium">Analyzing verified PharmaTrack MES records...</span>
                </div>
              )}

              <div ref={messagesEndRef} />
            </div>

            {/* Drawer Input & Footer Area */}
            <div className="p-3.5 bg-white border-t border-slate-200/90 shrink-0 space-y-2 shadow-sm">
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  if (!isLoading && inputQuery.trim()) {
                    submitQuery(inputQuery);
                  }
                }}
                className="flex items-end gap-2"
              >
                <div className="relative flex-1">
                  <label htmlFor="ai-copilot-input" className="sr-only">
                    Ask PharmaTrack AI Copilot
                  </label>
                  <textarea
                    ref={inputRef}
                    id="ai-copilot-input"
                    data-testid="copilot-query-input"
                    rows={2}
                    maxLength={2000}
                    disabled={isLoading}
                    value={inputQuery}
                    onChange={(e) => setInputQuery(e.target.value)}
                    onKeyDown={handleKeyDown}
                    placeholder={
                      activeOrderNumber
                        ? `Ask about order ${activeOrderNumber}... (Shift+Enter for newline)`
                        : 'Ask about any manufacturing order (e.g. MO-2026-0001)...'
                    }
                    className="w-full text-xs rounded-xl border border-slate-300 focus:border-sky-500 focus:ring-2 focus:ring-sky-100 p-2.5 resize-none transition-all disabled:bg-slate-50 disabled:text-slate-400 placeholder:text-slate-400 focus:outline-none shadow-2xs leading-relaxed"
                  />
                </div>

                <button
                  type="submit"
                  disabled={isLoading || !inputQuery.trim()}
                  data-testid="copilot-submit-button"
                  aria-label="Send query to AI Copilot"
                  className="h-10 px-3.5 rounded-xl bg-pharma-600 hover:bg-pharma-700 text-white text-xs font-semibold shadow-xs flex items-center justify-center space-x-1.5 transition-all disabled:opacity-40 disabled:cursor-not-allowed shrink-0 focus:outline-none focus:ring-2 focus:ring-pharma-400"
                >
                  {isLoading ? (
                    <RefreshCw className="w-4 h-4 animate-spin" aria-hidden="true" />
                  ) : (
                    <Send className="w-4 h-4" aria-hidden="true" />
                  )}
                </button>
              </form>

              {/* Compliance & Regulatory Disclaimer Footer */}
              <div className="flex items-center justify-between text-[10px] text-slate-400 pt-1 gap-2">
                <span
                  className="truncate"
                  title="Answers are grounded in retrieved PharmaTrack MES records. AI responses are advisory and must be independently verified."
                >
                  Answers are grounded in retrieved PharmaTrack MES records. AI responses are advisory and must be independently verified.
                </span>
                <span className="font-mono text-[9px] text-slate-400 shrink-0">
                  {inputQuery.length}/2000
                </span>
              </div>
            </div>
          </>
        )}

        {/* Centered Professional SaaS Delete Confirmation Modal Dialog */}
        {confirmDeleteId != null && (
          <div
            className="absolute inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-2xs transition-opacity animate-backdrop-enter"
            aria-hidden="true"
            onClick={(e) => {
              if (e.target === e.currentTarget && !isDeleting) {
                setConfirmDeleteId(null);
                setDeleteError(null);
              }
            }}
          >
            <div
              ref={deleteModalRef}
              role="dialog"
              aria-modal="true"
              tabIndex={-1}
              data-testid="delete-conversation-modal"
              aria-labelledby="delete-dialog-title"
              aria-describedby="delete-dialog-description"
              className="relative w-full max-w-sm bg-white rounded-2xl shadow-2xl border border-slate-200 p-6 space-y-4 animate-modal-enter focus:outline-none"
            >
              {/* Header with Warning Icon and Clear Title */}
              <div className="flex items-start space-x-3.5">
                <div className="w-10 h-10 rounded-xl bg-rose-100 text-rose-600 flex items-center justify-center shrink-0 border border-rose-200">
                  <Trash2 className="w-5 h-5 text-rose-600" aria-hidden="true" />
                </div>
                <div className="min-w-0 flex-1">
                  <h3
                    id="delete-dialog-title"
                    data-testid="delete-dialog-title"
                    className="text-base font-bold text-slate-900 tracking-tight"
                  >
                    Delete Conversation?
                  </h3>
                  <p
                    id="delete-dialog-description"
                    data-testid="delete-dialog-description"
                    className="text-xs text-slate-600 leading-relaxed mt-1"
                  >
                    Are you sure you want to permanently delete this conversation? All messages in this conversation will also be deleted. This action cannot be undone.
                  </p>
                </div>
              </div>

              {/* Deletion Error Feedback if API fails */}
              {deleteError && (
                <div
                  data-testid="delete-error-message"
                  className="p-3 rounded-xl bg-rose-50 border border-rose-200 text-xs text-rose-800 flex items-start space-x-2"
                >
                  <AlertCircle className="w-4 h-4 text-rose-600 shrink-0 mt-0.5" aria-hidden="true" />
                  <span className="leading-snug">{deleteError}</span>
                </div>
              )}

              {/* Action Buttons: Cancel vs Destructive Delete */}
              <div className="flex items-center justify-end space-x-2.5 pt-2 border-t border-slate-100">
                <button
                  ref={deleteCancelBtnRef}
                  type="button"
                  disabled={isDeleting}
                  onClick={() => {
                    setConfirmDeleteId(null);
                    setDeleteError(null);
                  }}
                  data-testid={`cancel-delete-conv-${confirmDeleteId}`}
                  className="px-4 py-2 text-xs font-semibold text-slate-700 bg-white hover:bg-slate-100 border border-slate-300 rounded-xl shadow-2xs transition-colors focus:outline-none focus:ring-2 focus:ring-slate-400 disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  disabled={isDeleting}
                  onClick={(e) => handleConfirmDelete(confirmDeleteId, e)}
                  data-testid={`confirm-delete-conv-${confirmDeleteId}`}
                  className="inline-flex items-center space-x-1.5 px-4 py-2 text-xs font-semibold text-white bg-rose-600 hover:bg-rose-700 disabled:bg-rose-400 rounded-xl shadow-xs transition-colors focus:outline-none focus:ring-2 focus:ring-rose-500 disabled:cursor-not-allowed"
                >
                  {isDeleting ? (
                    <RefreshCw className="w-3.5 h-3.5 animate-spin" aria-hidden="true" />
                  ) : (
                    <Trash2 className="w-3.5 h-3.5" aria-hidden="true" />
                  )}
                  <span>{isDeleting ? 'Deleting...' : 'Delete Conversation'}</span>
                </button>
              </div>
            </div>
          </div>
        )}
      </aside>
    </div>
  );
}
