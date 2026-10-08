/**
 * Shared formatting utilities for drive items (sizes, dates). Keep display
 * logic out of pages/components so it is never duplicated.
 */

/**
 * Format a byte count as a human-readable size: 512 B, 1.2 KB, 5.4 MB, 1.8 GB.
 */
export function formatFileSize(sizeBytes: number | null | undefined): string {
  if (
    sizeBytes === null ||
    sizeBytes === undefined ||
    Number.isNaN(sizeBytes)
  ) {
    return '—';
  }
  if (sizeBytes < 1024) {
    return `${sizeBytes} B`;
  }
  const units = ['KB', 'MB', 'GB', 'TB', 'PB'];
  let value = sizeBytes;
  let unitIndex = -1;
  do {
    value /= 1024;
    unitIndex += 1;
  } while (value >= 1024 && unitIndex < units.length - 1);
  return `${value.toFixed(1)} ${units[unitIndex]}`;
}

/** Format an ISO datetime string for display in the file listing. */
export function formatUpdatedAt(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}
