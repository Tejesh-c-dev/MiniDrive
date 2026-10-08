import axios from 'axios';
import type { ApiError } from '@/types/api';
import type { FieldErrors } from '@/types/auth';

/**
 * Normalizes any thrown error into a typed ApiError. Maps the backend error
 * payloads ({ "error": string } or { "errors": { field: message } }) to a
 * controlled, UI-safe message so raw backend internals are never shown.
 */
export function toApiError(error: unknown): ApiError {
  if (axios.isAxiosError(error) && error.response) {
    const data: unknown = error.response.data;
    const status = error.response.status;
    let message: string | undefined;
    let fieldErrors: FieldErrors | undefined;

    if (typeof data === 'object' && data !== null) {
      const record = data as Record<string, unknown>;
      if (typeof record.error === 'string') {
        message = record.error;
      }
      if (typeof record.errors === 'object' && record.errors !== null) {
        const errorsRecord = record.errors as Record<string, unknown>;
        const entries = Object.entries(errorsRecord).filter(
          (entry): entry is [string, string] => typeof entry[1] === 'string',
        );
        if (entries.length > 0) {
          fieldErrors = Object.fromEntries(entries);
        }
      }
    }

    if (!message && fieldErrors) {
      const first = Object.values(fieldErrors)[0] as string | undefined;
      message = first;
    }

    if (!message) {
      message =
        status === 401
          ? 'Your session has expired. Please sign in again.'
          : status === 403
            ? 'You do not have permission to access this resource.'
            : status === 404
              ? 'The requested resource was not found.'
              : 'Unexpected server error. Please try again.';
    }

    return { message, status, fieldErrors };
  }

  return {
    message: 'Could not connect to the server. Please try again.',
  };
}
