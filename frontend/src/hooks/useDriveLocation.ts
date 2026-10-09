import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { getFolder } from '@/api/folders';
import { toApiError } from '@/api/errors';
import type { Crumb } from '@/types/drive';

/**
 * Resolves the current drive location from the URL: undefined while the route
 * is still being read (never in practice), null for drive root, or a folder id
 * when inside a folder. Alongside the id it maintains a lightweight breadcrumb
 * trail built by walking parent links one folder at a time — no full tree
 * prefetch.
 */
export interface DriveLocation {
  /** Current folder id, or null when at drive root. */
  folderId: string | null;
  /** Trailing crumb reflects the last-known name for the current folder. */
  crumbs: Crumb[];
}

export function useDriveLocation(): {
  location: DriveLocation;
  loading: boolean;
  error: string | null;
  /** Patch a crumb's displayed name in place (folder rename in current path). */
  updateCrumbName: (id: string, name: string) => void;
} {
  const params = useParams<{ folderId?: string }>();
  const routeFolderId = params.folderId ?? null;

  const [crumbs, setCrumbs] = useState<Crumb[]>([{ id: null, name: 'My Drive' }]);
  const [parentChain, setParentChain] = useState<Crumb[]>([]); // ancestors, root→…→parent
  const [currentName, setCurrentName] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    if (routeFolderId === null) {
      setCrumbs([{ id: null, name: 'My Drive' }]);
      setParentChain([]);
      setCurrentName(null);
      setError(null);
      return;
    }

    setLoading(true);
    setError(null);
    setCrumbs([{ id: null, name: 'My Drive' }]); // avoid stale trail while loading

    (async () => {
      const chain: Crumb[] = [];
      // Walk up via parentFolderId, collecting ancestors, then reverse.
      const path: { id: string; name: string; parentFolderId: string | null }[] = [];
      let cursor: string | null | undefined = routeFolderId;
      let fatal: string | null = null;
      let guard = 0;

      try {
        while (typeof cursor === 'string' && guard < 32) {
          guard += 1;
          const folder = await getFolder(cursor);
          if (cancelled) return;
          path.push({
            id: folder.id,
            name: folder.name,
            parentFolderId: folder.parentFolderId,
          });
          cursor = folder.parentFolderId;
        }
        // The current folder is rendered separately below; keep only its
        // ancestors in the parent chain.
        for (let i = path.length - 1; i > 0; i--) {
          chain.push({ id: path[i].id, name: path[i].name });
        }
        if (cancelled) return;
        setParentChain(chain);
        setCurrentName(path.length > 0 ? path[0].name : null);
      } catch (err: unknown) {
        fatal = toApiError(err).message;
        if (cancelled) return;
        setError(fatal);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
    // Rebuild the trail only when the route folder changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeFolderId]);

  useEffect(() => {
    setCrumbs([
      { id: null, name: 'My Drive' },
      ...parentChain,
      ...(currentName
        ? [{ id: routeFolderId, name: currentName }] satisfies Crumb[]
        : []),
    ]);
  }, [parentChain, currentName, routeFolderId]);

  // Patch a crumb's displayed name in place (e.g. after renaming a folder
  // currently in the path) without re-walking the folder chain.
  const updateCrumbName = useCallback(
    (id: string, name: string) => {
      if (id === routeFolderId) {
        setCurrentName(name);
      } else {
        setParentChain((chain) =>
          chain.map((crumb) => (crumb.id === id ? { ...crumb, name } : crumb)),
        );
      }
    },
    [routeFolderId],
  );

  return {
    location: { folderId: routeFolderId, crumbs },
    loading,
    error,
    updateCrumbName,
  };
}
