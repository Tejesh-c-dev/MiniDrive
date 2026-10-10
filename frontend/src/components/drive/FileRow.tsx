import type { FileSummary } from '@/types/drive';
import { formatFileSize, formatUpdatedAt } from '@/utils/format';

interface FileRowProps {
  file: FileSummary;
  disabled?: boolean;
  /**
   * Whether the current user may modify this file. The active listing is
   * owner-scoped, so it is normally true; the backend remains authoritative
   * and rejects a content replacement without edit permission regardless of
   * this flag. When false the Replace content action is not rendered.
   */
  canEdit: boolean;
  /** True while this row's content replacement is being submitted. */
  replacing?: boolean;
  onDownload: (file: FileSummary) => void;
  onRename: (file: FileSummary) => void;
  onShare: (file: FileSummary) => void;
  onHistory: (file: FileSummary) => void;
  onReplace: (file: FileSummary) => void;
}

/**
 * A single file in the listing: name, type, size, last updated, rename,
 * download, share, version history, and content replacement. The active
 * listing only contains files the current user owns (the list endpoint is
 * owner-scoped), so the Share and History controls are shown for every row;
 * the backend remains authoritative and rejects permission management or
 * restores for non-editors regardless of the UI. Replace content is gated on
 * canEdit so a reader without edit access cannot even open the picker.
 */
export default function FileRow({
  file,
  disabled,
  canEdit,
  replacing,
  onDownload,
  onRename,
  onShare,
  onHistory,
  onReplace,
}: FileRowProps) {
  return (
    <li
      className="flex items-center gap-4 px-4 py-3 hover:bg-gray-50"
      data-testid="file-row"
    >
      <span aria-hidden="true" className="text-xl">
        📄
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-gray-900">
          {file.name}
        </p>
        <p className="truncate text-xs text-gray-400">{file.contentType}</p>
      </div>
      <span className="hidden w-20 shrink-0 text-right text-xs text-gray-500 sm:block">
        {formatFileSize(file.sizeBytes)}
      </span>
      <span className="hidden w-40 shrink-0 text-right text-xs text-gray-500 md:block">
        {formatUpdatedAt(file.updatedAt)}
      </span>
      <button
        type="button"
        onClick={() => onRename(file)}
        data-testid="file-rename"
        className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100"
      >
        Rename
      </button>
      <button
        type="button"
        onClick={() => onShare(file)}
        data-testid="file-share"
        className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100"
      >
        Share
      </button>
      <button
        type="button"
        onClick={() => onHistory(file)}
        data-testid="file-history"
        className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100"
      >
        History
      </button>
      {canEdit && (
        <button
          type="button"
          disabled={replacing}
          onClick={() => onReplace(file)}
          data-testid="file-replace"
          className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {replacing ? 'Replacing…' : 'Replace content'}
        </button>
      )}
      <button
        type="button"
        disabled={disabled}
        onClick={() => onDownload(file)}
        data-testid="file-download"
        className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50"
      >
        Download
      </button>
    </li>
  );
}
