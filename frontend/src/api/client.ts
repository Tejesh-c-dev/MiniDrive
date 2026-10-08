import axios, {
  type AxiosInstance,
  type InternalAxiosRequestConfig,
} from 'axios';
import { getToken, removeToken } from '@/utils/tokenStorage';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL as string | undefined;

if (!API_BASE_URL) {
  // Fail fast in development rather than making requests to a relative origin.
  throw new Error(
    'VITE_API_BASE_URL is not defined. Copy frontend/.env.example to frontend/.env.',
  );
}

/**
 * Centralized Axios client. All backend calls go through this instance so
 * interceptors (JWT auth headers, 401 handling) are applied in one place.
 */
export const apiClient: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
});

/**
 * Attach "Authorization: Bearer <JWT>" when a token exists. The header is
 * omitted entirely for unauthenticated requests (login, register, health).
 */
apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken();
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

/**
 * Centralized 401 handling: a protected request rejected with 401 means the
 * stored token is missing/expired/invalid — drop it and broadcast so the auth
 * store clears client state and consumers redirect to /login. Nothing retries
 * the request, so no redirect/interceptor loop is possible.
 */
apiClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      removeToken();
      window.dispatchEvent(new Event('minidrive:unauthorized'));
    }
    return Promise.reject(error);
  },
);

export default apiClient;
