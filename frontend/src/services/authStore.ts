import { useSyncExternalStore } from 'react';
import {
  getToken,
  removeToken,
  setToken,
} from '@/utils/tokenStorage';
import { login as loginApi, register as registerApi } from './authService';
import type { AuthResponse, LoginRequest, RegisterRequest } from '@/types/auth';

/**
 * Minimal centralized authentication state (no external state library).
 *
 * - Components subscribe via useAuth(); auth state is derived from the stored
 *   JWT, so login/register/logout and 401 handling all flow through here.
 * - The Axios client dispatches "minidrive:unauthorized" when the backend
 *   rejects a request with 401 (after removing the invalid token); this store
 *   listens for it so consumers reactively redirect to /login. There is no
 *   retry loop because nothing ever re-adds a token automatically.
 */

type Listener = () => void;

export interface AuthState {
  readonly authenticated: boolean;
  readonly token: string | null;
}

const listeners = new Set<Listener>();

/** Cached snapshot + token mirror: useSyncExternalStore requires getSnapshot
 * to return a stable reference while the token is unchanged. */
let cachedToken: string | null | undefined;
let cachedSnapshot: AuthState = { authenticated: false, token: null };

function notify(): void {
  for (const listener of listeners) {
    listener();
  }
}

function getSnapshot(): AuthState {
  const token = getToken();
  if (token !== cachedToken) {
    cachedToken = token;
    cachedSnapshot = {
      authenticated: token !== null && token !== '',
      token,
    };
  }
  return cachedSnapshot;
}

function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

// React to the 401 handler in the Axios client (token already removed there).
if (typeof window !== 'undefined') {
  window.addEventListener('minidrive:unauthorized', notify);
}

/** Reactive authentication state for use in components. */
export function useAuth(): AuthState {
  return useSyncExternalStore(subscribe, getSnapshot);
}

/** Persist the JWT returned by register/login and update auth state. */
function applyAuthResponse(response: AuthResponse): void {
  setToken(response.token);
  notify();
}

/** Register a new account. Stores the JWT on success (backend auto-login). */
export async function registerAndLogin(
  request: RegisterRequest,
): Promise<void> {
  const response = await registerApi(request);
  applyAuthResponse(response);
}

/** Login with email/password. Stores the JWT on success. */
export async function loginWithEmailAndPassword(
  request: LoginRequest,
): Promise<void> {
  const response = await loginApi(request);
  applyAuthResponse(response);
}

/**
 * Log out: remove the stored JWT and clear client auth state. The backend
 * provides no logout endpoint (stateless JWT), so only local state is cleared.
 */
export function logout(): void {
  removeToken();
  notify();
}
