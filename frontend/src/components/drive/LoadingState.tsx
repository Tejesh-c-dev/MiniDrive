/** Skeleton rows shown while folders/files are being fetched. */
export default function LoadingState({ rows = 4 }: { rows?: number }) {
  return (
    <div className="divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white" aria-hidden="true" data-testid="loading-state">
      {Array.from({ length: rows }).map((_, index) => (
        <div key={index} className="flex items-center gap-4 px-4 py-3">
          <div className="size-8 animate-pulse rounded bg-gray-200" />
          <div className="h-3 flex-1 animate-pulse rounded bg-gray-200" />
          <div className="h-3 w-24 animate-pulse rounded bg-gray-100" />
          <div className="h-3 w-32 animate-pulse rounded bg-gray-100" />
        </div>
      ))}
    </div>
  );
}
