/** Access JWTs stay in this module's memory for this page lifetime only. */
let token: string | null = null;

export function getToken(): string | null {
  return token;
}

export function setToken(value: string): void {
  token = value;
}

export function removeToken(): void {
  token = null;
}

export function isAuthenticated(): boolean {
  return token !== null && token !== '';
}
