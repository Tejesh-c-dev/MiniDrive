/**
 * Displays upload progress inline on the DrivePage. `progressPercent` falls
 * back to an indeterminate bar while the XHR has not reported any
 * uploadProgress events — an indicator still shows even then.
 */
export interface UploadStateProps {
  fileName: string;
  progressPercent: number | null; // null = no meaningful progress events yet
}

export default function UploadState({
  fileName,
  progressPercent,
}: UploadStateProps) {
  const indeterminate = progressPercent === null;
  const shown = indeterminate ? 0 : Math.min(100, Math.max(0, progressPercent));

  return (
    <div
      data-testid="upload-state"
      role="status"
      aria-live="polite"
      className="rounded-lg border border-blue-200 bg-blue-50 p-3 text-sm text-blue-900"
    >
      <div className="flex items-center justify-between gap-3">
        <span className="truncate font-medium">
          Uploading <span className="font-semibold">{fileName}</span>
        </span>
        <span className="shrink-0 tabular-nums text-xs font-semibold text-blue-700">
          {Math.round(shown)}%
        </span>
      </div>
      <div
        className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-blue-200"
        role="progressbar"
        aria-label={`Uploading ${fileName}`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={indeterminate ? undefined : Math.round(shown)}
      >
        <div
          className={
            indeterminate
              ? 'h-full w-1/3 rounded-full bg-blue-600 animate-pulse'
              : 'h-full rounded-full bg-blue-600 transition-all duration-150 ease-out'
          }
          style={indeterminate ? undefined : { width: `${shown}%` }}
        />
      </div>
    </div>
  );
}
