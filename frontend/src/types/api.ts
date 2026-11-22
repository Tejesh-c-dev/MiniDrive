/**
 * Shared API-level types used by service modules in later phases.
 * No domain logic here yet — fields are added as backend endpoints are wired up.
 */

/** Normalized shape a rejected Axios request is mapped to. */
export interface ApiError {
  /** Human-readable message, safe to render in the UI. */
  message: string;
  /** HTTP status code, when the error came from a response. */
  status?: number;
  /** Per-field messages when the backend returned validation errors. */
  fieldErrors?: Record<string, string>;
}

/** Result returned by file and folder cleanup lifecycle DELETE endpoints. */
export interface DeletionResult {
  jobId?: string;
  complete: boolean;
  status?: string;
}
