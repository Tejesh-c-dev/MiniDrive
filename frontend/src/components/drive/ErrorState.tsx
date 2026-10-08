interface ErrorStateProps {
  message: string;
  onRetry?: () => void;
}

/** Shared error panel: shows a safe message plus an optional retry action. */
export default function ErrorState({ message, onRetry }: ErrorStateProps) {
  return (
    <div
      role="alert"
      data-testid="error-state"
      className="flex flex-col items-center justify-center rounded-lg border border-red-200 bg-red-50 px-6 py-10 text-center"
    >
      <p className="text-3xl" aria-hidden="true">
        ⚠️
      </p>
      <p className="mt-2 max-w-sm text-sm font-medium text-red-700">{message}</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-4 rounded-md border border-red-300 bg-white px-4 py-1.5 text-sm font-semibold text-red-700 transition-colors hover:bg-red-100"
        >
          Try again
        </button>
      )}
    </div>
  );
}
