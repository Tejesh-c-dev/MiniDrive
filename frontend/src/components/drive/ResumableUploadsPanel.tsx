/**
 * Lists upload sessions discovered in the resume store so a user can continue
 * an interrupted upload. A browser refresh cannot preserve the original File
 * object, so "Resume" only opens the native picker: the chosen file is
 * validated against the saved record before any network call, and the server's
 * stored chunk map then decides what is re-sent.
 */
import type { ResumableUploadRecord } from '@/uploads/resumeStore';
import { formatFileSize } from '@/utils/format';

export interface ResumableUploadsPanelProps {
  records: ResumableUploadRecord[];
  /** Re-select the original file and continue this session. */
  onResume: (record: ResumableUploadRecord) => void;
  /** Abort the session on the server and drop the local record. */
  onDiscard: (record: ResumableUploadRecord) => void;
  /** Disable actions while another upload is active. */
  disabled?: boolean;
}

export default function ResumableUploadsPanel({
  records,
  onResume,
  onDiscard,
  disabled,
}: ResumableUploadsPanelProps) {
  if (records.length === 0) return null;

  return (
    <section
      data-testid="resumable-uploads"
      aria-label="Interrupted uploads"
      className="mt-6 rounded-lg border border-amber-200 bg-amber-50 p-3"
    >
      <h2 className="text-xs font-semibold uppercase tracking-wider text-amber-800">
        Interrupted uploads
      </h2>
      <p className="mt-1 text-xs text-amber-800">
        A refresh cannot keep a file selected. Choose the original file again to
        resume from where it stopped.
      </p>
      <ul className="mt-2 divide-y divide-amber-200">
        {records.map((record) => (
          <li
            key={record.sessionId}
            data-testid="resumable-row"
            className="flex items-center gap-3 py-2"
          >
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-gray-900">
                {record.filename}
              </p>
              <p className="text-xs text-gray-500">
                {formatFileSize(record.sizeBytes)} · {record.totalChunks} chunk
                {record.totalChunks === 1 ? '' : 's'}
              </p>
            </div>
            <button
              type="button"
              disabled={disabled}
              onClick={() => onResume(record)}
              data-testid="resumable-resume"
              className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Resume
            </button>
            <button
              type="button"
              disabled={disabled}
              onClick={() => onDiscard(record)}
              data-testid="resumable-discard"
              className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Discard
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
