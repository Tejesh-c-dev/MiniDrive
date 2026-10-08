import { apiClient } from '@/api/client';
import { toApiError } from '@/api/errors';
import type {
  AuthResponse,
  CurrentUser,
  LoginRequest,
  RegisterRequest,
} from '@/types/auth';

/**
 * Authentication API service. Wraps the backend endpoints so pages never talk
 * to Axios directly. Error mapping to typed ApiError happens here.
 */

export async function register(
  request: RegisterRequest,
): Promise<AuthResponse> {
  try {
    const { data } = await apiClient.post<AuthResponse>(
      '/api/auth/register',
      request,
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

export async function login(request: LoginRequest): Promise<AuthResponse> {
  try {
    const { data } = await apiClient.post<AuthResponse>('/api/auth/login', {
      email: request.email,
      password: request.password,
    });
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

export async function fetchCurrentUser(): Promise<CurrentUser> {
  try {
    const { data } = await apiClient.get<CurrentUser>('/api/auth/me');
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}
