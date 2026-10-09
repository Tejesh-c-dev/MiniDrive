import type { FileSummary } from '@/types/drive';
import { formatFileSize, formatUpdatedAt } from '@/utils/format';

interface FileRowProps {
  file: FileSummary;
  disabled?: boolean;
  onDownload: (file: FileSummary) => void;
  onRename: (file: FileSummary) => void;
}

/** A single file in the listing: name, type, size, last updated, download. */
export default function FileRow({
  file,
  disabled,
  onDownload,
  onRename,
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
