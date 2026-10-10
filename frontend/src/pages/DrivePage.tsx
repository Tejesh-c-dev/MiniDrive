import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  listFiles,
  downloadFile,
  uploadFile,
  renameFile,
  replaceFileContent,
} from '@/api/files';
import { listFolders, createFolder, renameFolder } from '@/api/folders';
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
import NewFolderDialog from '@/components/drive/NewFolderDialog';
import RenameDialog from '@/components/drive/RenameDialog';
import ShareDialog from '@/components/sharing/ShareDialog';
import VersionHistoryDialog from '@/components/files/VersionHistoryDialog';
import UploadState from '@/components/drive/UploadState';

/**
 * Drive dashboard (Phase 5.3): browser of the user's folders and files.
 * Location is URL-driven (/drive, /drive/folders/:folderId); both lists are
 * re-fetched per location. Upload goes to the current folder (or root).
 * Rename is available for folders/files; Share (Phase 6.2) opens a dialog to
 * manage a file's collaborators. Delete arrives in a later phase.
 *
 * The file list is owner-scoped by the backend, so every listed file belongs
 * to the current user and is safe to offer sharing controls for; the backend
 * still enforces owner-only access for permission management.
 */
export default function DrivePage() {
  const navigate = useNavigate();
  const { authenticated } = useAuth();
  const { location, error: trailError, updateCrumbName } = useDriveLocation();
  const { folderId, crumbs } = location;

  const [folders, setFolders] = useState<FolderSummary[] | null>(null);
  const [files, setFiles] = useState<FileSummary[] | null>(null);
  const [foldersError, setFoldersError] = useState<ApiError | null>(null);
  const [filesError, setFilesError] = useState<ApiError | null>(null);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<ApiError | null>(null);
  const [uploadingName, setUploadingName] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  const [uploadError, setUploadError] = useState<ApiError | null>(null);
  const [newFolderOpen, setNewFolderOpen] = useState(false);
  const [creatingFolder, setCreatingFolder] = useState(false);
  const [renamingFolder, setRenamingFolder] = useState<FolderSummary | null>(null);
  const [renamingFile, setRenamingFile] = useState<FileSummary | null>(null);
  const [sharingFile, setSharingFile] = useState<FileSummary | null>(null);
  const [historyFile, setHistoryFile] = useState<FileSummary | null>(null);
  const [replaceTarget, setReplaceTarget] = useState<FileSummary | null>(null);
  const [replacingId, setReplacingId] = useState<string | null>(null);
  const [replaceError, setReplaceError] = useState<ApiError | null>(null);
  const [replaceNotice, setReplaceNotice] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const replaceInputRef = useRef<HTMLInputElement>(null);

  const handlePickFile = () => {
    setUploadError(null);
    fileInputRef.current?.click();
  };

  const handleFileSelected = async (
    event: React.ChangeEvent<HTMLInputElement>,
  ) => {
    const file = event.target.files?.[0];
    // Reset the input so picking the same file again re-fires the change event.
    event.target.value = '';
    if (!file || uploadingName !== null) return;
    setUploadingName(file.name);
    setUploadProgress(null);
    setUploadError(null);
    try {
      await uploadFile(file, folderId ?? undefined, setUploadProgress);
      refresh();
    } catch (err: unknown) {
      setUploadError(err as ApiError);
    } finally {
      setUploadingName(null);
      setUploadProgress(null);
    }
  };

  const [refreshTick, setRefreshTick] = useState(0);
  const refresh = useCallback(() => {
    setRefreshTick((tick) => tick + 1);
  }, []);

  // Open the native picker for a content replacement, remembering which file
  // the selected bytes will be applied to. The backend is the sole authority on
  // whether the caller may edit; the picker is only offered for files canEdit
  // already permits.
  const handlePickReplacement = (file: FileSummary) => {
    setReplaceTarget(file);
    setReplaceError(null);
    setReplaceNotice(null);
    replaceInputRef.current?.click();
  };

  // Submit the picked file to POST /api/files/{id}/content (never the file
  // creation endpoint). The input is reset first so the same local file can be
  // selected again on a later attempt.
  const handleReplacementSelected = async (
    event: React.ChangeEvent<HTMLInputElement>,
  ) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    const target = replaceTarget;
    if (!file || target === null || replacingId !== null) return;
    setReplacingId(target.id);
    setReplaceError(null);
    setReplaceNotice(null);
    try {
      const result = await replaceFileContent(target.id, file);
      // Only report success once the server confirms it. Refreshing re-fetches
      // the listing so the row's currentVersion (and size/updatedAt) reflect
      // the new revision immediately; the history dialog loads fresh too.
      setReplaceNotice(
        `${target.name} updated to version ${result.currentVersion}.`,
      );
      refresh();
    } catch (err: unknown) {
      // A failure leaves the existing file state untouched (no refresh) and
      // surfaces the server's message.
      setReplaceError(err as ApiError);
    } finally {
      setReplacingId(null);
    }
  };

  // Rename the targeted folder via the shared API module (the dialog handles
  // trimming/validation; submit rejections surface its error). On success the
  // crumb trail is patched in place if the folder is part of the current path.
  const renamingTarget = renamingFolder;
  const renamingFileTarget = renamingFile;
  const handleRenameFolder = useCallback(
    async (name: string) => {
      if (renamingTarget === null) return;
      await renameFolder(renamingTarget.id, name);
      refresh();
      updateCrumbName(renamingTarget.id, name);
    },
    [renamingTarget, refresh, updateCrumbName],
  );

  // Rename the targeted file via the shared API module.
  const handleRenameFile = useCallback(
    async (name: string) => {
      if (renamingFileTarget === null) return;
      await renameFile(renamingFileTarget.id, name);
      refresh();
    },
    [renamingFileTarget, refresh],
  );

  // Create the folder in the current location via the shared API module (the
  // dialog handles trimming/validation; submit rejections surface its error).
  const handleCreateFolder = useCallback(
    async (name: string) => {
      if (creatingFolder) return; // guard against duplicate submissions
      setCreatingFolder(true);
      try {
        await createFolder(name, folderId ?? undefined);
        refresh();
        setNewFolderOpen(false); // stay in the current folder
      } finally {
        setCreatingFolder(false);
      }
    },
    [creatingFolder, folderId, refresh],
  );

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
  const uploading = uploadingName !== null;
  // Prefer the freshest metadata for the open history dialog so currentVersion
  // reflects a just-completed restore; fall back to the clicked snapshot.
  const historyTarget =
    historyFile !== null
      ? files?.find((candidate) => candidate.id === historyFile.id) ??
        historyFile
      : null;

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
            onClick={handlePickFile}
            disabled={uploading}
            data-testid="upload-button"
            className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {uploading ? 'Uploading…' : 'Upload'}
          </button>
          <button
            type="button"
            onClick={() => setNewFolderOpen(true)}
            disabled={creatingFolder || uploading}
            data-testid="new-folder-button"
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60"
          >
            New Folder
          </button>
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

      {/* Hidden file input drives the upload flow; clicking the Upload button
          opens the native picker, results flow into handleFileSelected. */}
      <input
        ref={fileInputRef}
        type="file"
        className="hidden"
        onChange={handleFileSelected}
        data-testid="upload-input"
      />

      {/* Separate hidden input for content replacement; the target file is
          captured when the Replace content action is chosen. */}
      <input
        ref={replaceInputRef}
        type="file"
        className="hidden"
        onChange={handleReplacementSelected}
        data-testid="replace-input"
      />

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
                      <FolderCard
                        key={folder.id}
                        folder={folder}
                        onRename={setRenamingFolder}
                      />
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
                        // The listing is owner-scoped, so every listed file is
                        // editable by the current user; the backend still
                        // enforces edit permission for replacements.
                        canEdit
                        replacing={replacingId === file.id}
                        onDownload={handleDownload}
                        onRename={setRenamingFile}
                        onShare={setSharingFile}
                        onHistory={setHistoryFile}
                        onReplace={handlePickReplacement}
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

      {uploadError && (
        <p
          role="alert"
          data-testid="upload-error"
          className="mt-4 text-sm font-medium text-red-600"
        >
          {uploadError.message}
        </p>
      )}
      {uploadingName !== null && (
        <div className="mt-4">
          <UploadState
            fileName={uploadingName}
            progressPercent={uploadProgress}
          />
        </div>
      )}

      {replacingId !== null && (
        <p
          role="status"
          data-testid="replace-state"
          className="mt-4 text-sm font-medium text-blue-700"
        >
          Replacing content…
        </p>
      )}
      {replaceError && (
        <p
          role="alert"
          data-testid="replace-error"
          className="mt-4 text-sm font-medium text-red-600"
        >
          {replaceError.message}
        </p>
      )}
      {replaceNotice !== null && (
        <p
          role="status"
          data-testid="replace-notice"
          className="mt-4 text-sm font-medium text-green-700"
        >
          {replaceNotice}
        </p>
      )}

      {downloadError && (
        <p role="alert" className="mt-4 text-sm font-medium text-red-600">
          {downloadError.message}
        </p>
      )}

      {newFolderOpen && (
        <NewFolderDialog
          open={newFolderOpen}
          onCreate={handleCreateFolder}
          onClose={() => setNewFolderOpen(false)}
        />
      )}

      {renamingFolder && (
        <RenameDialog
          open={renamingFolder !== null}
          initialName={renamingFolder.name}
          entityLabel="folder"
          onRename={handleRenameFolder}
          onClose={() => setRenamingFolder(null)}
        />
      )}

      {renamingFile && (
        <RenameDialog
          open={renamingFile !== null}
          initialName={renamingFile.name}
          entityLabel="file"
          onRename={handleRenameFile}
          onClose={() => setRenamingFile(null)}
        />
      )}

      {sharingFile && (
        <ShareDialog
          open={sharingFile !== null}
          file={sharingFile}
          onClose={() => setSharingFile(null)}
        />
      )}

      {historyTarget !== null && (
        <VersionHistoryDialog
          open={historyTarget !== null}
          file={historyTarget}
          // The drive listing is owner-scoped, so every listed file is owned
          // by the current user and may be restored. The backend still enforces
          // edit permission for restores independently of this flag.
          canEdit
          onClose={() => setHistoryFile(null)}
          onRestored={refresh}
        />
      )}

      <p className="mt-10 text-xs text-gray-400">
        <Link to="/" className="transition-colors hover:text-gray-600">
          ← Back home
        </Link>
      </p>
    </section>
  );
}
