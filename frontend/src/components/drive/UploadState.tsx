/**
 * Inline status for the resumable upload on the DrivePage. Renders the phase
 * label/tone and progress from the pure `describeUploadState` mapper, and
 * exposes cancel / retry-finalize / dismiss actions where the phase permits.
 *
 * The 100% bar during `finalizing` is intentionally not "success": the tone
 * only turns green once the backend completion succeeds.
 */
import type { ResumableUploadState } from '@/uploads/resumableUpload';
import { describeUploadState } from '@/uploads/uploadStatus';

export interface UploadStateProps {
  state: ResumableUploadState;
  /** Abort the server session (offered while one exists and is abortable). */
  onCancel?: () => void;
  /** Retry finalization after a finalize-stage failure (same session). */
  onRetryFinalize?: () => void;
  /** Clear the finished/failed status from the page. */
  onDismiss?: () => void;
}

export default function UploadState({
  state,
  onCancel,
  onRetryFinalize,
  onDismiss,
}: UploadStateProps) {
  if (state.phase === 'idle') return null;

  const view = describeUploadState(state);
  const isError = view.tone === 'error';
  const indeterminate = view.percent === null;
  const shown = view.percent ?? 0;

  const containerTone = isError
    ? 'border-red-200 bg-red-50 text-red-900'
    : view.tone === 'success'
      ? 'border-green-200 bg-green-50 text-green-900'
      : 'border-blue-200 bg-blue-50 text-blue-900';
  const barTone = isError ? 'bg-red-600' : 'bg-blue-600';

  return (
    <div
      data-testid="upload-state"
      data-phase={state.phase}
      role={isError ? 'alert' : 'status'}
      aria-live={isError ? 'assertive' : 'polite'}
      className={`rounded-lg border p-3 text-sm ${containerTone}`}
    >
      <div className="flex items-center justify-between gap-3">
        <span className="truncate font-medium">{view.label}</span>
        <span className="shrink-0 tabular-nums text-xs font-semibold">
          {indeterminate ? '…' : `${Math.round(shown)}%`}
        </span>
      </div>

      {state.filename !== null && (
        <div
          className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-black/10"
          role="progressbar"
          aria-label={view.label}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={indeterminate ? undefined : Math.round(shown)}
        >
          <div
            className={
              indeterminate
                ? `h-full w-1/3 rounded-full ${barTone} animate-pulse`
                : `h-full rounded-full ${barTone} transition-all duration-150 ease-out`
            }
            style={indeterminate ? undefined : { width: `${shown}%` }}
          />
        </div>
      )}

      {(view.canCancel || view.canRetryFinalize || view.terminal) && (
        <div className="mt-2 flex flex-wrap items-center gap-2">
          {view.canRetryFinalize && onRetryFinalize && (
            <button
              type="button"
              onClick={onRetryFinalize}
              data-testid="upload-retry-finalize"
              className="rounded-md border border-gray-300 bg-white px-2.5 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100"
            >
              Retry finalization
            </button>
          )}
          {view.canCancel && onCancel && (
            <button
              type="button"
              onClick={onCancel}
              data-testid="upload-cancel"
              className="rounded-md border border-gray-300 bg-white px-2.5 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100"
            >
              Cancel upload
            </button>
          )}
          {view.terminal && onDismiss && (
            <button
              type="button"
              onClick={onDismiss}
              data-testid="upload-dismiss"
              className="rounded-md border border-gray-300 bg-white px-2.5 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100"
            >
              Dismiss
            </button>
          )}
        </div>
      )}
    </div>
  );
}
