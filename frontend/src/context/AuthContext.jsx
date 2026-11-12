import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import {
  clearAuthToken,
  getAuthToken,
  setAuthToken,
  setForbiddenHandler,
  setUnauthorizedHandler,
} from '../services/apiClient.js';
import { fetchCurrentUser, loginUser } from '../services/authApi.js';
import { formatActorDisplay, normalizeRole } from '../utils/rbacUtils.js';

export const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [token, setTokenState] = useState(() => getAuthToken());
  const [user, setUser] = useState(null);
  const [isInitializing, setIsInitializing] = useState(() => Boolean(getAuthToken()));
  const [isRetryingSession, setIsRetryingSession] = useState(false);
  const [sessionMessage, setSessionMessage] = useState(null);
  const [sessionRestoreError, setSessionRestoreError] = useState(null);
  const [forbiddenBanner, setForbiddenBanner] = useState(null);

  const mountedRef = useRef(true);
  const authEpochRef = useRef(0);
  const retryInFlightRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const handleUnauthorized = useCallback((info) => {
    clearAuthToken();
    if (!mountedRef.current) {
      return;
    }
    setTokenState(null);
    setUser(null);
    setIsInitializing(false);
    setIsRetryingSession(false);
    setSessionRestoreError(null);
    setForbiddenBanner(null);
    setSessionMessage(
      info?.message || 'Your session has expired or is no longer valid. Please sign in again.'
    );
  }, []);

  const handleForbidden = useCallback((info) => {
    if (!mountedRef.current) {
      return;
    }
    setForbiddenBanner(
      info?.message || 'Permission denied: your role is not authorized to perform this action.'
    );
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(handleUnauthorized);
    setForbiddenHandler(handleForbidden);
    return () => {
      setUnauthorizedHandler(null);
      setForbiddenHandler(null);
    };
  }, [handleUnauthorized, handleForbidden]);

  const restoreSessionAttempt = useCallback(async ({ isCancelled } = {}) => {
    const existingToken = getAuthToken();
    if (!existingToken) {
      if (!(isCancelled && isCancelled()) && mountedRef.current) {
        setTokenState(null);
        setUser(null);
        setSessionRestoreError(null);
        setIsInitializing(false);
        setIsRetryingSession(false);
      }
      return null;
    }

    const attemptEpoch = authEpochRef.current;
    const isStale = () =>
      Boolean(isCancelled && isCancelled()) ||
      !mountedRef.current ||
      attemptEpoch !== authEpochRef.current;

    try {
      const currentUser = await fetchCurrentUser();
      if (isStale()) {
        return null;
      }

      const resolvedRole = normalizeRole(currentUser?.role);
      if (!currentUser || currentUser.enabled === false || !resolvedRole) {
        clearAuthToken();
        setTokenState(null);
        setUser(null);
        setForbiddenBanner(null);
        setSessionRestoreError(null);
        setSessionMessage('Your account is disabled or unassigned. Please sign in again.');
        return null;
      }

      setTokenState(getAuthToken() || existingToken);
      setUser(currentUser);
      setSessionMessage(null);
      setSessionRestoreError(null);
      return currentUser;
    } catch (err) {
      if (isStale()) {
        return null;
      }

      const status = err?.status;
      const isAuthRejection =
        status === 401 ||
        status === 403 ||
        Boolean(err?.isUnauthorized) ||
        Boolean(err?.isForbidden);

      if (isAuthRejection) {
        clearAuthToken();
        setTokenState(null);
        setUser(null);
        setForbiddenBanner(null);
        setSessionRestoreError(null);
        setSessionMessage(
          err?.message ||
            (status === 403 || err?.isForbidden
              ? 'Your session is no longer authorized. Please sign in again.'
              : 'Your session has expired or is no longer valid. Please sign in again.')
        );
      } else {
        // Transient network error or temporary backend outage: keep token intact for manual retry
        setTokenState(getAuthToken() || existingToken);
        setUser(null);
        setSessionMessage(null);
        setSessionRestoreError(
          err?.message ||
            'Unable to reach the authentication server to verify your session. Your sign-in token was preserved.'
        );
      }
      return null;
    } finally {
      if (!(isCancelled && isCancelled()) && mountedRef.current) {
        setIsInitializing(false);
        setIsRetryingSession(false);
      }
    }
  }, []);

  useEffect(() => {
    const existingToken = getAuthToken();
    if (!existingToken) {
      setIsInitializing(false);
      return undefined;
    }

    let cancelled = false;
    setIsInitializing(true);
    restoreSessionAttempt({ isCancelled: () => cancelled });

    return () => {
      cancelled = true;
    };
  }, [restoreSessionAttempt]);

  const retrySessionRestore = useCallback(async () => {
    if (retryInFlightRef.current) {
      return null;
    }
    const existingToken = getAuthToken();
    if (!existingToken) {
      setTokenState(null);
      setUser(null);
      setSessionRestoreError(null);
      setIsInitializing(false);
      setIsRetryingSession(false);
      return null;
    }

    retryInFlightRef.current = true;
    setIsRetryingSession(true);
    try {
      return await restoreSessionAttempt();
    } finally {
      retryInFlightRef.current = false;
    }
  }, [restoreSessionAttempt]);

  const login = useCallback(async ({ username, password }) => {
    const response = await loginUser({ username, password });
    const accessToken = response?.accessToken;
    const authenticatedUser = response?.user;

    if (!accessToken || !authenticatedUser) {
      throw new Error('Authentication server returned an incomplete login response.');
    }

    authEpochRef.current += 1;
    setAuthToken(accessToken);
    if (mountedRef.current) {
      setTokenState(accessToken);
      setUser(authenticatedUser);
      setIsInitializing(false);
      setIsRetryingSession(false);
      setSessionMessage(null);
      setSessionRestoreError(null);
      setForbiddenBanner(null);
    }
    return response;
  }, []);

  const logout = useCallback((reasonMessage = null) => {
    authEpochRef.current += 1;
    clearAuthToken();
    if (!mountedRef.current) {
      return;
    }
    setTokenState(null);
    setUser(null);
    setIsInitializing(false);
    setIsRetryingSession(false);
    setSessionRestoreError(null);
    setForbiddenBanner(null);
    setSessionMessage(
      typeof reasonMessage === 'string' && reasonMessage.trim() !== '' ? reasonMessage.trim() : null
    );
  }, []);

  const clearSessionMessage = useCallback(() => {
    setSessionMessage(null);
  }, []);

  const dismissForbiddenBanner = useCallback(() => {
    setForbiddenBanner(null);
  }, []);

  const role = normalizeRole(user?.role);
  const isAuthenticated = Boolean(token && user && user.enabled !== false && role);
  const actorDisplay = formatActorDisplay(user);

  const value = {
    user,
    role,
    token,
    actorDisplay,
    isAuthenticated,
    isInitializing,
    isRetryingSession,
    sessionMessage,
    sessionRestoreError,
    forbiddenBanner,
    login,
    logout,
    retrySessionRestore,
    clearSessionMessage,
    dismissForbiddenBanner,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return ctx;
}
