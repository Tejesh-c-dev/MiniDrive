/**
 * JWT token persistence. All storage access for the auth token lives here —
 * do not call localStorage directly elsewhere in the app.
 */

const TOKEN_KEY = 'minidrive_token';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function removeToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

/** Whether a non-empty token is currently stored. */
export function isAuthenticated(): boolean {
  return getToken() !== null && getToken() !== '';
}
