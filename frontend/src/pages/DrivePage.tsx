import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { listFiles, downloadFile } from '@/api/files';
import { listFolders } from '@/api/folders';
import { useAuth, logout } from '@/services/authStore';
import { useDriveLocation } from '@/hooks/useDriveLocation';
import type { ApiError } from '@/types/api';
import type { FileSummary, FolderSummary } from '@/types/drive';
import Breadcrumbs from '@/components/drive/Breadcrumbs';
import EmptyState from '@/components/drive/EmptyState';
import ErrorState from '@/components/drive/ErrorState';
import FileRow from '@/components/drive/FileRow';
import FolderCard from '@/components/drive/FolderCard';
import LoadingState from '@/components/drive/LoadingState';

/**
 * Drive dashboard (Phase 5.3): browser of the user's folders and files.
 * Location is URL-driven (/drive, /drive/folders/:folderId); both lists are
 * re-fetched per location. No upload/rename/delete/share here — later phases.
 */
export default function DrivePage() {
  const navigate = useNavigate();
  const { authenticated } = useAuth();
  const { location, error: trailError } = useDriveLocation();
  const { folderId, crumbs } = location;

  const [folders, setFolders] = useState<FolderSummary[] | null>(null);
  const [files, setFiles] = useState<FileSummary[] | null>(null);
  const [foldersError, setFoldersError] = useState<ApiError | null>(null);
  const [filesError, setFilesError] = useState<ApiError | null>(null);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<ApiError | null>(null);

  const [refreshTick, setRefreshTick] = useState(0);
  const refresh = useCallback(() => {
    setRefreshTick((tick) => tick + 1);
  }, []);

  // Fetch folders and files for the current location.
  useEffect(() => {
    let cancelled = false;
    setFolders(null);
    setFiles(null);
    setFoldersError(null);
    setFilesError(null);
    const folderPromise = listFolders(folderId ?? undefined)
      .then((data) => {
        if (!cancelled) setFolders(data);
      })
      .catch((err: unknown) => {
        if (!cancelled) setFoldersError(err as ApiError);
      });

    const filePromise = listFiles(folderId ?? undefined)
      .then((data) => {
        if (!cancelled) setFiles(data);
      })
      .catch((err: unknown) => {
        if (!cancelled) setFilesError(err as ApiError);
      });

    return () => {
      cancelled = true;
      void folderPromise;
      void filePromise;
    };
  }, [folderId, refreshTick]);

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const handleDownload = async (file: FileSummary) => {
    setDownloadingId(file.id);
    setDownloadError(null);
    try {
      const blob = await downloadFile(file.id);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = file.name;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    } catch (err: unknown) {
      setDownloadError(err as ApiError);
    } finally {
      setDownloadingId(null);
    }
  };

  if (!authenticated) return null; // ProtectedRoute already redirects.

  const combinedError = foldersError ?? filesError;
  const showListingSkeleton = folders === null || files === null;
  const emptyDrive =
    !showListingSkeleton &&
    !combinedError &&
    folders.length === 0 &&
    files.length === 0;
  const downloading = downloadingId !== null;

  return (
    <section className="w-full max-w-5xl pt-6 pb-16">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-gray-900">
            MiniDrive
          </h1>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={refresh}
            data-testid="refresh-button"
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-semibold text-gray-700 transition-colors hover:bg-gray-100"
          >
            Refresh
          </button>
          <button
            type="button"
            onClick={handleLogout}
            data-testid="logout-button"
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-semibold text-gray-700 transition-colors hover:bg-gray-100"
          >
            Log out
          </button>
        </div>
      </header>

      <div className="mt-4">
        <Breadcrumbs crumbs={crumbs} />
      </div>

      {trailError && (
        <div className="mt-4">
          <ErrorState message={trailError} onRetry={refresh} />
        </div>
      )}
      {!trailError && showListingSkeleton && (
        <div className="mt-6 space-y-4">
          <LoadingState />
          <LoadingState rows={2} />
        </div>
      )}
      {!trailError && !showListingSkeleton && combinedError && (
        <div className="mt-6">
          <ErrorState
            message={
              filesError !== null && foldersError !== null
                ? 'Could not load your drive. Please try again.'
                : (combinedError as ApiError).message
            }
            onRetry={refresh}
          />
        </div>
      )}
      {!trailError && !showListingSkeleton && !combinedError && (
        <>
          {emptyDrive ? (
            <div className="mt-6">
              <EmptyState
                title="Your drive is empty"
                message="No folders or files yet. Upload and organizer tools arrive in later phases."
              />
            </div>
          ) : (
            <>
              {folders.length > 0 && (
                <section className="mt-6" data-testid="folder-section">
                  <h2 className="px-1 text-xs font-semibold uppercase tracking-wider text-gray-400">
                    Folders
                  </h2>
                  <div className="mt-2 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    {folders.map((folder) => (
                      <FolderCard key={folder.id} folder={folder} />
                    ))}
                  </div>
                </section>
              )}

              {files.length > 0 && (
                <section className="mt-8" data-testid="file-section">
                  <h2 className="px-1 text-xs font-semibold uppercase tracking-wider text-gray-400">
                    Files
                  </h2>
                  <ul className="mt-2 divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white">
                    {files.map((file) => (
                      <FileRow
                        key={file.id}
                        file={file}
                        disabled={downloading}
                        onDownload={handleDownload}
                      />
                    ))}
                  </ul>
                </section>
              )}

              {folders.length === 0 && (
                <div className="mt-6" data-testid="folders-empty">
                  <EmptyState
                    title="No folders here"
                    message="This location has no subfolders."
                  />
                </div>
              )}
              {files.length === 0 && (
                <div className="mt-6" data-testid="files-empty">
                  <EmptyState
                    title="No files here"
                    message="This location has no files yet."
                  />
                </div>
              )}
            </>
          )}
        </>
      )}

      {downloadError && (
        <p role="alert" className="mt-4 text-sm font-medium text-red-600">
          {downloadError.message}
        </p>
      )}

      <p className="mt-10 text-xs text-gray-400">
        <Link to="/" className="transition-colors hover:text-gray-600">
          ← Back home
        </Link>
      </p>
    </section>
  );
}
