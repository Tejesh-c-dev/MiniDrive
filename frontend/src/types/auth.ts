/**
 * Authentication-related types. These mirror the backend Spring Boot DTOs
 * (com.minidrive.dto.auth.*) and must be kept in sync with them.
 */

/** Body of POST /api/auth/register (RegisterRequest). */
export interface RegisterRequest {
  name: string;
  email: string;
  password: string;
}

/** Body of POST /api/auth/login (LoginRequest). */
export interface LoginRequest {
  email: string;
  password: string;
}

/** Response of POST /api/auth/register and POST /api/auth/login (AuthResponse). */
export interface AuthResponse {
  token: string;
  tokenType: string;
  userId: string;
  name: string;
  email: string;
}

/** Response of GET /api/auth/me. */
export interface CurrentUser {
  id: string;
  name: string;
  email: string;
}

/** Field-level validation errors returned as `{ "errors": { field: message } }`. */
export type FieldErrors = Record<string, string>;
