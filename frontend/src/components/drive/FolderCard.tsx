import { Link } from 'react-router-dom';
import type { FolderSummary } from '@/types/drive';
import { formatUpdatedAt } from '@/utils/format';

interface FolderCardProps {
  folder: FolderSummary;
}

/** A compact, clickable folder tile used in the folder grid. */
export default function FolderCard({ folder }: FolderCardProps) {
  return (
    <Link
      to={`/drive/folders/${folder.id}`}
      data-testid="folder-card"
      className="group flex items-center gap-3 rounded-lg border border-gray-200 bg-white px-4 py-3 transition-colors hover:border-blue-300 hover:bg-blue-50"
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
  );
}
