/**
 * Presentation mapping for a resumable upload (Phase 8.5).
 *
 * Kept pure and framework-free so the phase -> label/tone/actions logic is unit
 * tested in the node Vitest environment (the project has no DOM test setup),
 * while `UploadState`/`DrivePage` only render what this returns.
 */
import type {
  ResumableUploadState,
  UploadPhase,
} from '@/uploads/resumableUpload';

export type UploadTone = 'info' | 'success' | 'error';

export interface UploadStatusView {
  /** Human label, e.g. "Uploading movie.bin… 42%". Empty while idle. */
  label: string;
  tone: UploadTone;
  /** 0..100 for the progress bar, or null for an indeterminate bar. */
  percent: number | null;
  /** True while the run is actively working (including finalizing). */
  busy: boolean;
  /** Offer cancel/abort while a session the server can still abort exists. */
  canCancel: boolean;
  /** Offer "retry finalization" after a finalize-stage failure. */
  canRetryFinalize: boolean;
  /** True once the run has reached completed / cancelled / failed. */
  terminal: boolean;
}

const BUSY_PHASES: ReadonlySet<UploadPhase> = new Set([
  'preparing',
  'uploading',
  'retrying',
  'finalizing',
]);

const TERMINAL_PHASES: ReadonlySet<UploadPhase> = new Set([
  'completed',
  'cancelled',
  'failed',
]);

export function isUploadBusy(phase: UploadPhase): boolean {
  return BUSY_PHASES.has(phase);
}

export function isUploadTerminal(phase: UploadPhase): boolean {
  return TERMINAL_PHASES.has(phase);
}

function clampPercent(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.min(100, Math.max(0, Math.round(value)));
}

/**
 * Describe how a run should be shown. Reaching 100% chunk progress never reads
 * as success: only the `completed` phase (backend finalization succeeded) is
 * toned as success, while `finalizing` stays informational.
 */
export function describeUploadState(state: ResumableUploadState): UploadStatusView {
  const name = state.filename ?? 'file';
  const percent = state.totalBytes > 0 ? clampPercent(state.progressPercent) : null;

  const base = {
    busy: isUploadBusy(state.phase),
    canCancel: state.sessionId !== null && !isUploadTerminal(state.phase),
    canRetryFinalize:
      state.phase === 'failed' &&
      state.failureStage === 'finalize' &&
      state.sessionId !== null,
    terminal: isUploadTerminal(state.phase),
  };

  switch (state.phase) {
    case 'idle':
      return { ...base, label: '', tone: 'info', percent: null };
    case 'preparing':
      return { ...base, label: `Preparing ${name}…`, tone: 'info', percent: null };
    case 'uploading':
      return { ...base, label: `Uploading ${name}…`, tone: 'info', percent };
    case 'retrying':
      return {
        ...base,
        label: `Reconnecting… retrying ${name} (attempt ${state.retryAttempt})`,
        tone: 'info',
        percent,
      };
    case 'paused':
      return {
        ...base,
        label: `Paused — ${name} (${percent ?? 0}% uploaded)`,
        tone: 'info',
        percent,
      };
    case 'finalizing':
      return { ...base, label: `Finalizing ${name}…`, tone: 'info', percent };
    case 'completed': {
      const version = state.result?.currentVersion;
      const suffix = version !== undefined ? ` (version ${version})` : '';
      return {
        ...base,
        label: `Uploaded ${state.result?.name ?? name}${suffix}`,
        tone: 'success',
        percent: 100,
      };
    }
    case 'cancelled':
      return { ...base, label: `Upload cancelled: ${name}`, tone: 'error', percent };
    case 'failed':
      return {
        ...base,
        label: `Upload failed: ${state.error?.message ?? 'unknown error'}`,
        tone: 'error',
        percent,
      };
    default:
      return { ...base, label: '', tone: 'info', percent: null };
  }
}
