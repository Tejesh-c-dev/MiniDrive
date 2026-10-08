import { Link } from 'react-router-dom';
import type { Crumb } from '@/types/drive';

interface BreadcrumbsProps {
  crumbs: Crumb[];
}

/**
 * Location path (e.g. My Drive → Projects → MiniDrive). Built from the
 * lightweight breadcrumb trail tracked in DrivePage state — not from a
 * fetched folder tree.
 */
export default function Breadcrumbs({ crumbs }: BreadcrumbsProps) {
  return (
    <nav aria-label="Breadcrumb">
      <ol className="flex flex-wrap items-center gap-1 text-sm">
        {crumbs.map((crumb, index) => {
          const isLast = index === crumbs.length - 1;
          const to = crumb.id ? `/drive/folders/${crumb.id}` : '/drive';
          return (
            <li key={crumb.id ?? 'root'} className="flex items-center gap-1">
              {index > 0 && (
                <span aria-hidden="true" className="text-gray-400">
                  /
                </span>
              )}
              {isLast ? (
                <span aria-current="page" className="font-semibold text-gray-900">
                  {crumb.name}
                </span>
              ) : (
                <Link
                  to={to}
                  className="text-gray-600 transition-colors hover:text-gray-900 hover:underline"
                >
                  {crumb.name}
                </Link>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
