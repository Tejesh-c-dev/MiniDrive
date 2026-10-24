import { Link } from 'react-router-dom';
import type { FolderSummary } from '@/types/drive';
import { formatUpdatedAt } from '@/utils/format';

interface FolderCardProps {
  folder: FolderSummary;
  onRename: (folder: FolderSummary) => void;
  onDelete: (folder: FolderSummary) => void;
  deleting?: boolean;
}

/** A compact, clickable folder tile used in the folder grid. */
export default function FolderCard({ folder, onRename, onDelete, deleting }: FolderCardProps) {
  return (
    <div
      data-testid="folder-card"
      className="group flex items-center gap-3 rounded-lg border border-gray-200 bg-white px-4 py-3 transition-colors hover:border-blue-300 hover:bg-blue-50"
    >
      <Link
        to={`/drive/folders/${folder.id}`}
        className="flex min-w-0 flex-1 items-center gap-3"
      >
        <span aria-hidden="true" className="text-xl">
          📁
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium text-gray-900 group-hover:text-blue-700">
            {folder.name}
          </span>
          <span className="block text-xs text-gray-400">
            Updated {formatUpdatedAt(folder.updatedAt)}
          </span>
        </span>
      </Link>
      <div className="flex shrink-0 items-center gap-2">
      <button
        type="button"
        onClick={() => onRename(folder)}
        data-testid="folder-rename"
        className="shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100"
      >
        Rename
      </button>
      <button
        type="button"
        disabled={deleting}
        onClick={() => onDelete(folder)}
        aria-label={`Delete ${folder.name}`}
        data-testid="folder-delete"
        className="shrink-0 rounded-md border border-red-200 bg-white px-3 py-1 text-xs font-semibold text-red-700 transition-colors hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {deleting ? 'Deleting…' : 'Delete'}
      </button>
      </div>
    </div>
  );
}
