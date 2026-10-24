import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  listFiles,
  downloadFile,
  renameFile,
  replaceFileContent,
  deleteFile,
} from '@/api/files';
import { listFolders, createFolder, renameFolder, deleteFolder } from '@/api/folders';
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
import DeleteDialog, { type DeleteTarget } from '@/components/drive/DeleteDialog';
import ShareDialog from '@/components/sharing/ShareDialog';
import VersionHistoryDialog from '@/components/files/VersionHistoryDialog';
import UploadState from '@/components/drive/UploadState';
import ResumableUploadsPanel from '@/components/drive/ResumableUploadsPanel';
import { useResumableUpload } from '@/hooks/useResumableUpload';
import { validateResumeAssociation } from '@/uploads/resumableUpload';
import { isUploadBusy } from '@/uploads/uploadStatus';
import type { ResumableUploadRecord } from '@/uploads/resumeStore';

/**
 * Drive dashboard (Phase 5.3): browser of the user's folders and files.
 * Location is URL-driven (/drive, /drive/folders/:folderId); both lists are
 * re-fetched per location. Upload goes to the current folder (or root).
 * Rename is available for folders/files; Share (Phase 6.2) opens a dialog to
 * manage a file's collaborators.
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
  const [query, setQuery] = useState('');
  const [typeFilter, setTypeFilter] = useState('all');
  const [sortBy, setSortBy] = useState('name');
  const [preview, setPreview] = useState<{ file: FileSummary; url: string; kind: 'image' | 'text' | 'pdf' } | null>(null);
  const previewRequest = useRef(0);
  const [downloadError, setDownloadError] = useState<ApiError | null>(null);
  const upload = useResumableUpload();
  const [uploadNotice, setUploadNotice] = useState<string | null>(null);
  const [resumeError, setResumeError] = useState<string | null>(null);
  const [resumeTarget, setResumeTarget] = useState<ResumableUploadRecord | null>(
    null,
  );
  const [resumeTick, setResumeTick] = useState(0);
  const [newFolderOpen, setNewFolderOpen] = useState(false);
  const [creatingFolder, setCreatingFolder] = useState(false);
  const [renamingFolder, setRenamingFolder] = useState<FolderSummary | null>(null);
  const [renamingFile, setRenamingFile] = useState<FileSummary | null>(null);
  const [sharingFile, setSharingFile] = useState<FileSummary | null>(null);
  const [historyFile, setHistoryFile] = useState<FileSummary | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<DeleteTarget | null>(null);
  const [deletionNotice, setDeletionNotice] = useState<string | null>(null);
  const [pendingDeletions, setPendingDeletions] = useState<
    Record<string, DeleteTarget['kind']>
  >({});
  const [replaceTarget, setReplaceTarget] = useState<FileSummary | null>(null);
  const [replacingId, setReplacingId] = useState<string | null>(null);
  const [replaceError, setReplaceError] = useState<ApiError | null>(null);
  const [replaceNotice, setReplaceNotice] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const replaceInputRef = useRef<HTMLInputElement>(null);
  const resumeInputRef = useRef<HTMLInputElement>(null);
  const uploadActive = isUploadBusy(upload.state.phase);

  const handlePickFile = () => {
    setUploadNotice(null);
    setResumeError(null);
    fileInputRef.current?.click();
  };

  // A new-file upload always goes through the resumable session client (chunked
  // staging + backend finalization). The listing is only refreshed once the
  // backend has produced the file. Content replacement keeps its own multipart
  // flow below, so a normal upload can never be turned into a replacement.
  const handleFileSelected = async (
    event: React.ChangeEvent<HTMLInputElement>,
  ) => {
    const file = event.target.files?.[0];
    // Reset the input so picking the same file again re-fires the change event.
    event.target.value = '';
    if (!file || uploadActive) return;
    setUploadNotice(null);
    setResumeError(null);

    const result = await upload.start(
      file,
      folderId ? { folderId: folderId } : {},
    );
    if (result.phase === 'completed') {
      setUploadNotice(`Uploaded ${result.result?.name ?? file.name}.`);
      refresh();
    }
  };

  const [refreshTick, setRefreshTick] = useState(0);
  const refresh = useCallback(() => {
    setRefreshTick((tick) => tick + 1);
  }, []);

  // Interrupted sessions discovered in the resume store, excluding whichever one
  // the active upload is already working on.
  const resumableRecords = useMemo(
    () =>
      upload
        .listResumable()
        .filter((record) => record.sessionId !== upload.state.sessionId),
    [upload.state.phase, upload.state.sessionId, refreshTick, resumeTick],
  );

  const handleResumeRequest = (record: ResumableUploadRecord) => {
    if (uploadActive) return;
    setResumeError(null);
    setUploadNotice(null);
    setResumeTarget(record);
    resumeInputRef.current?.click();
  };

  const handleResumeSelected = async (
    event: React.ChangeEvent<HTMLInputElement>,
  ) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    const record = resumeTarget;
    setResumeTarget(null);
    if (!file || record === null || uploadActive) return;

    // A refresh never preserves the original File object, so validate the
    // re-selected file against the saved record before contacting the server.
    const mismatch = validateResumeAssociation(file, record);
    if (mismatch !== null) {
      setResumeError(mismatch);
      return;
    }

    const result = await upload.resume(file, record);
    if (result.phase === 'completed') {
      setUploadNotice(`Uploaded ${result.result?.name ?? file.name}.`);
      refresh();
    }
  };

  const handleDiscardUpload = async (record: ResumableUploadRecord) => {
    setResumeError(null);
    await upload.discardResumable(record);
    setResumeTick((tick) => tick + 1);
  };

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

  const handleDelete = useCallback(async () => {
    const target = deleteTarget;
    if (target === null || target.id in pendingDeletions) return;
    setDeletionNotice(null);
    const result = target.kind === 'file'
      ? await deleteFile(target.id)
      : await deleteFolder(target.id);

    if (result.complete) {
      setDeletionNotice(`${target.kind === 'file' ? 'File' : 'Folder'} “${target.name}” deleted.`);
    } else {
      // A 202 means the durable lifecycle accepted the job but has not yet
      // removed the metadata. Keep the target visible and prevent resubmission.
      setPendingDeletions((current) => ({ ...current, [target.id]: target.kind }));
      setDeletionNotice(`Deletion of “${target.name}” is still processing. It will disappear when cleanup completes.`);
    }
    refresh();
  }, [deleteTarget, pendingDeletions, refresh]);

  // A 202 response means the lifecycle has accepted durable cleanup work.
  // Poll only while such work is pending so the row/card disappears as soon
  // as a later authoritative listing confirms metadata finalization.
  useEffect(() => {
    if (Object.keys(pendingDeletions).length === 0) return;
    const timer = window.setInterval(refresh, 4000);
    return () => window.clearInterval(timer);
  }, [pendingDeletions, refresh]);

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
        if (!cancelled) {
          setFolders(data);
          setPendingDeletions((current) => {
            const remaining = Object.fromEntries(
              Object.entries(current).filter(
                ([id, kind]) => kind !== 'folder' || data.some((folder) => folder.id === id),
              ),
            );
            return Object.keys(current).length === Object.keys(remaining).length
              ? current
              : remaining;
          });
          if (Object.entries(pendingDeletions).some(
            ([id, kind]) => kind === 'folder' && !data.some((folder) => folder.id === id),
          )) setDeletionNotice('Cleanup completed. The deleted item is no longer in this listing.');
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) setFoldersError(err as ApiError);
      });

    const filePromise = listFiles(folderId ?? undefined)
      .then((data) => {
        if (!cancelled) {
          setFiles(data);
          setPendingDeletions((current) => {
            const remaining = Object.fromEntries(
              Object.entries(current).filter(
                ([id, kind]) => kind !== 'file' || data.some((file) => file.id === id),
              ),
            );
            return Object.keys(current).length === Object.keys(remaining).length
              ? current
              : remaining;
          });
          if (Object.entries(pendingDeletions).some(
            ([id, kind]) => kind === 'file' && !data.some((file) => file.id === id),
          )) setDeletionNotice('Cleanup completed. The deleted item is no longer in this listing.');
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) setFilesError(err as ApiError);
      });

    return () => {
      cancelled = true;
      void folderPromise;
      void filePromise;
    };
  }, [folderId, pendingDeletions, refreshTick]);

  const handleLogout = async () => {
    const revoked = await logout();
    navigate('/login', { replace: true, state: { serverRevoked: revoked } });
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

  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview.url); }, [preview]);
  useEffect(() => () => { previewRequest.current += 1; }, []);
  const closePreview = () => { previewRequest.current += 1; setPreview(null); };
  const handlePreview = async (file: FileSummary) => {
    closePreview();
    setDownloadError(null);
    const mime = file.contentType.split(';')[0].trim().toLowerCase();
    const kind = mime === 'image/png' || mime === 'image/jpeg' ? 'image' : mime === 'text/plain' ? 'text' : mime === 'application/pdf' ? 'pdf' : null;
    if (!kind) { setDownloadError({ message: 'Preview is not available for this file type. Use Download instead.' } as ApiError); return; }
    if (file.sizeBytes > 10 * 1024 * 1024) { setDownloadError({ message: 'This file is too large to preview. Use Download instead.' } as ApiError); return; }
    const requestId = ++previewRequest.current;
    try {
      const blob = await downloadFile(file.id);
      if (requestId !== previewRequest.current) return;
      if (blob.type.split(';')[0].trim().toLowerCase() !== mime || blob.size > 10 * 1024 * 1024) throw new Error('The file type could not be verified or the file is too large to preview.');
      const url = URL.createObjectURL(blob);
      setPreview({ file, url, kind });
    } catch (e) { if (requestId === previewRequest.current) setDownloadError({ message: (e as Error).message || 'Could not load preview.' } as ApiError); }
  };

  const visibleFiles = useMemo(() => (files ?? []).filter(file => file.name.toLowerCase().includes(query.trim().toLowerCase()) && (typeFilter === 'all' || (typeFilter === 'image' ? file.contentType.startsWith('image/') : typeFilter === 'pdf' ? file.contentType === 'application/pdf' : typeFilter === 'text' ? file.contentType.startsWith('text/') : !file.contentType.startsWith('image/') && !file.contentType.startsWith('text/') && file.contentType !== 'application/pdf'))).sort((a,b) => sortBy === 'size' ? a.sizeBytes-b.sizeBytes : sortBy === 'updated' ? b.updatedAt.localeCompare(a.updatedAt) : a.name.localeCompare(b.name)), [files, query, typeFilter, sortBy]);

  if (!authenticated) return null; // ProtectedRoute already redirects.

  const combinedError = foldersError ?? filesError;
  const showListingSkeleton = folders === null || files === null;
  const emptyDrive =
    !showListingSkeleton &&
    !combinedError &&
    folders.length === 0 &&
    files.length === 0;
  const downloading = downloadingId !== null;
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
            disabled={uploadActive}
            data-testid="upload-button"
            className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {uploadActive ? 'Uploading…' : 'Upload'}
          </button>
          <button
            type="button"
            onClick={() => setNewFolderOpen(true)}
            disabled={creatingFolder || uploadActive}
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

      {/* Hidden input used to re-select the original file for an interrupted
          session; the target record is captured when Resume is chosen. */}
      <input
        ref={resumeInputRef}
        type="file"
        className="hidden"
        onChange={handleResumeSelected}
        data-testid="resume-input"
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
          <div className="mt-5 flex flex-wrap gap-3 rounded-lg border bg-white p-3">
            <label className="text-sm">Search <input aria-label="Search files" value={query} onChange={e => setQuery(e.target.value)} className="ml-2 rounded border px-2 py-1" placeholder="Filename" /></label>
            <label className="text-sm">Type <select aria-label="File type" value={typeFilter} onChange={e => setTypeFilter(e.target.value)} className="ml-2 rounded border px-2 py-1"><option value="all">All types</option><option value="image">Images</option><option value="text">Text</option><option value="pdf">PDFs</option><option value="other">Other</option></select></label>
            <label className="text-sm">Sort <select aria-label="Sort files" value={sortBy} onChange={e => setSortBy(e.target.value)} className="ml-2 rounded border px-2 py-1"><option value="name">Name</option><option value="updated">Last modified</option><option value="size">Size</option></select></label>
            {(query || typeFilter !== 'all') && <button onClick={() => { setQuery(''); setTypeFilter('all'); }} className="text-sm text-blue-700 underline">Clear filters</button>}
          </div>
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
                        onDelete={(target) => setDeleteTarget({ kind: 'folder', id: target.id, name: target.name })}
                        deleting={folder.id in pendingDeletions}
                      />
                    ))}
                  </div>
                </section>
              )}

              {visibleFiles.length > 0 && (
                <section className="mt-8" data-testid="file-section">
                  <h2 className="px-1 text-xs font-semibold uppercase tracking-wider text-gray-400">
                    Files
                  </h2>
                  <ul className="mt-2 divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white">
                    {visibleFiles.map((file) => (
                      <FileRow
                        key={file.id}
                        file={file}
                        disabled={downloading}
                        // The listing is owner-scoped, so every listed file is
                        // editable by the current user; the backend still
                        // enforces edit permission for replacements.
                        canEdit
                        replacing={replacingId === file.id}
                        deleting={file.id in pendingDeletions}
                        onDownload={handleDownload}
                        onRename={setRenamingFile}
                        onShare={setSharingFile}
                        onHistory={setHistoryFile}
                        onReplace={handlePickReplacement}
                        onDelete={(target) => setDeleteTarget({ kind: 'file', id: target.id, name: target.name })}
                        onPreview={handlePreview}
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
              {files.length > 0 && visibleFiles.length === 0 && <p className="mt-6 rounded border bg-white p-6 text-center text-gray-600">No files match these filters.</p>}
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
          {preview && <div role="dialog" aria-modal="true" aria-label={`Preview ${preview.file.name}`} className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onKeyDown={e => { if (e.key === 'Escape') closePreview(); }}><div className="max-h-full w-full max-w-4xl overflow-auto rounded-lg bg-white p-4"><div className="mb-3 flex justify-between"><h2 className="font-semibold">{preview.file.name}</h2><button autoFocus onClick={closePreview} aria-label="Close preview" className="rounded border px-3 py-1">Close</button></div>{preview.kind === 'image' ? <img src={preview.url} alt={preview.file.name} className="mx-auto max-h-[75vh]" /> : preview.kind === 'text' ? <iframe title="Text file preview" sandbox="" src={preview.url} className="h-[70vh] w-full border" /> : <iframe title="PDF preview" sandbox="allow-same-origin" src={preview.url} className="h-[75vh] w-full border" />}</div></div>}
        </>
      )}

      {upload.state.phase !== 'idle' && (
        <div className="mt-4">
          <UploadState
            state={upload.state}
            onCancel={() => {
              void upload.cancel();
            }}
            onRetryFinalize={() => {
              void upload.finalize();
            }}
            onDismiss={() => {
              upload.reset();
              setResumeTick((tick) => tick + 1);
            }}
          />
        </div>
      )}
      {uploadNotice !== null && (
        <p
          role="status"
          data-testid="upload-notice"
          className="mt-4 text-sm font-medium text-green-700"
        >
          {uploadNotice}
        </p>
      )}
      {resumeError !== null && (
        <p
          role="alert"
          data-testid="resume-error"
          className="mt-4 text-sm font-medium text-red-600"
        >
          {resumeError}
        </p>
      )}

      <ResumableUploadsPanel
        records={resumableRecords}
        onResume={handleResumeRequest}
        onDiscard={handleDiscardUpload}
        disabled={uploadActive}
      />

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

      {deletionNotice !== null && (
        <p role="status" data-testid="delete-notice" className="mt-4 text-sm font-medium text-blue-700">
          {deletionNotice}
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

      {deleteTarget !== null && (
        <DeleteDialog
          target={deleteTarget}
          onDelete={handleDelete}
          onClose={() => setDeleteTarget(null)}
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
