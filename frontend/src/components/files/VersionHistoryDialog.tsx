import { useCallback, useEffect, useId, useRef, useState } from 'react';

import {
  downloadFileVersion,
  listFileVersions,
  restoreFileVersion,
} from '@/api/versions';
import ErrorState from '@/components/drive/ErrorState';
import LoadingState from '@/components/drive/LoadingState';
import type { ApiError } from '@/types/api';
import type { FileSummary, FileVersion } from '@/types/drive';
import { formatFileSize, formatUpdatedAt } from '@/utils/format';

interface VersionHistoryDialogProps {
  open: boolean;
  /** The file whose history is shown. Owned/metadata comes from the listing. */
  file: FileSummary;
  /**
   * Whether the current user may restore versions. The drive listing is
   * owner-scoped, so it is normally true; the backend remains authoritative
   * and rejects restores without edit permission regardless of this flag.
   */
  canEdit: boolean;
  onClose: () => void;
  /** Called after a successful restore so the caller can refresh metadata. */
  onRestored: () => void;
}

/** Trigger a browser download for an already-fetched Blob. */
function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

/** Human label for who created a version, falling back safely. */
function creatorLabel(version: FileVersion): string {
  return (
    version.createdByName ??
    version.createdByEmail ??
    'Unknown user'
  );
}

/**
 * Version history for a single file (Phase 7.3). Opens from the file listing
 * and lists immutable snapshots newest-first, identifying the current revision
 * via `currentVersion`. Any reader may list and download versions; only owners
 * and editors see the restore action, and the backend enforces that
 * independently. Restoring appends a new version rather than rewinding, so the
 * dialog refreshes its list and reports the new current version.
 */
export default function VersionHistoryDialog({
  open,
  file,
  canEdit,
  onClose,
  onRestored,
}: VersionHistoryDialogProps) {
  const [versions, setVersions] = useState<FileVersion[] | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [currentVersion, setCurrentVersion] = useState(file.currentVersion);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const [restoringId, setRestoringId] = useState<string | null>(null);
  const [confirmRestoreId, setConfirmRestoreId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const dialogRef = useRef<HTMLDivElement>(null);
  // Mirror the latest currentVersion without listing it as an effect dep, so
  // the open-time reset reads fresh metadata while a parent metadata refresh
  // during an open session does not wipe post-restore feedback.
  const currentVersionRef = useRef(file.currentVersion);
  currentVersionRef.current = file.currentVersion;
  const titleId = useId();
  const errorId = useId();

  const busy = downloadingId !== null || restoringId !== null;

  // Fetch the authoritative history, surfacing failures in the dialog body.
  const loadVersions = useCallback(async (): Promise<void> => {
    try {
      const data = await listFileVersions(file.id);
      // The backend returns newest-first; sort defensively so the current
      // revision is always at the top regardless of server ordering.
      setVersions(
        [...data].sort((a, b) => b.versionNumber - a.versionNumber),
      );
      setListError(null);
    } catch (err: unknown) {
      setVersions(null);
      setListError((err as ApiError).message);
    }
  }, [file.id]);

  // Reset transient state and (re)fetch whenever the dialog opens. The effect
  // keys off open/file.id only: the parent supplies a fresh currentVersion
  // snapshot on each open (read via currentVersionRef), and re-running on every
  // metadata change would wipe the post-restore success feedback mid-session.
  useEffect(() => {
    if (!open) return;
    setVersions(null);
    setListError(null);
    setCurrentVersion(currentVersionRef.current);
    setDownloadingId(null);
    setRestoringId(null);
    setConfirmRestoreId(null);
    setActionError(null);
    setNotice(null);
    void loadVersions();
    requestAnimationFrame(() => dialogRef.current?.focus());
  }, [open, file.id, loadVersions]);

  if (!open) return null;

  const handleDownload = async (version: FileVersion) => {
    if (busy) return;
    setDownloadingId(version.id);
    setActionError(null);
    setNotice(null);
    try {
      const blob = await downloadFileVersion(file.id, version.id);
      saveBlob(blob, version.originalFilename);
    } catch (err: unknown) {
      setActionError((err as ApiError).message);
    } finally {
      setDownloadingId(null);
    }
  };

  const handleRestore = async (version: FileVersion) => {
    if (busy) return;
    setRestoringId(version.id);
    setActionError(null);
    setNotice(null);
    try {
      const result = await restoreFileVersion(file.id, version.id);
      // Re-read the list, then apply the server-confirmed current version.
      await loadVersions();
      setCurrentVersion(result.currentVersion);
      setConfirmRestoreId(null);
      setNotice(`Version ${version.versionNumber} restored as the current content.`);
      // Let the caller refresh file metadata (size, updated time, version).
      onRestored();
    } catch (err: unknown) {
      setActionError((err as ApiError).message);
    } finally {
      setRestoringId(null);
    }
  };

  const handleClose = () => {
    if (busy) return;
    onClose();
  };

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.stopPropagation();
      handleClose();
    }
  };

  const secondaryButtonClass =
    'shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60';

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4"
      onKeyDown={handleKeyDown}
      data-testid="version-dialog-overlay"
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={actionError ? errorId : undefined}
        tabIndex={-1}
        data-testid="version-dialog"
        className="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-lg bg-white p-5 shadow-xl outline-none"
      >
        <h2
          id={titleId}
          className="truncate text-sm font-bold uppercase tracking-wider text-blue-800"
          title={file.name}
        >
          Version history — {file.name}
        </h2>
        <p className="mt-1 text-xs text-gray-400">
          Each revision is kept immutably. Restoring a version adds a new
          revision without deleting history.
        </p>

        {actionError !== null && (
          <p
            id={errorId}
            role="alert"
            data-testid="version-dialog-error"
            className="mt-3 text-sm font-medium text-red-600"
          >
            {actionError}
          </p>
        )}
        {notice !== null && (
          <p
            role="status"
            data-testid="version-dialog-notice"
            className="mt-3 text-sm font-medium text-green-700"
          >
            {notice}
          </p>
        )}

        <div className="mt-4">
          {listError !== null ? (
            <ErrorState message={listError} onRetry={() => void loadVersions()} />
          ) : versions === null ? (
            <div data-testid="version-dialog-loading">
              <LoadingState rows={3} />
            </div>
          ) : versions.length === 0 ? (
            <p
              className="rounded-lg border border-dashed border-gray-300 bg-white px-4 py-8 text-center text-sm text-gray-500"
              data-testid="version-dialog-empty"
            >
              This file has no stored versions yet.
            </p>
          ) : (
            <ul
              className="divide-y divide-gray-100 rounded-lg border border-gray-200"
              data-testid="version-list"
            >
              {versions.map((version) => {
                const isCurrent = version.versionNumber === currentVersion;
                const downloading = downloadingId === version.id;
                const restoring = restoringId === version.id;
                const confirming = confirmRestoreId === version.id;
                return (
                  <li
                    key={version.id}
                    className="flex flex-col gap-2 px-3 py-3 sm:flex-row sm:items-center sm:gap-3"
                    data-testid="version-row"
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span
                          data-testid="version-number"
                          className="text-sm font-semibold text-gray-900"
                        >
                          v{version.versionNumber}
                        </span>
                        {isCurrent && (
                          <span
                            data-testid="version-current-badge"
                            className="rounded-full bg-blue-100 px-2 py-0.5 text-xs font-semibold text-blue-700"
                          >
                            Current
                          </span>
                        )}
                      </div>
                      <p
                        className="truncate text-xs text-gray-600"
                        title={version.originalFilename}
                      >
                        {version.originalFilename}
                      </p>
                      <p className="truncate text-xs text-gray-400">
                        {formatFileSize(version.sizeBytes)} ·{' '}
                        {formatUpdatedAt(version.createdAt)} · by{' '}
                        {creatorLabel(version)}
                      </p>
                    </div>

                    <div className="flex shrink-0 items-center gap-1">
                      <button
                        type="button"
                        onClick={() => void handleDownload(version)}
                        disabled={busy}
                        data-testid="version-download"
                        className={secondaryButtonClass}
                      >
                        {downloading ? 'Downloading…' : 'Download'}
                      </button>

                      {canEdit &&
                        !isCurrent &&
                        (confirming ? (
                          <>
                            <button
                              type="button"
                              onClick={() => void handleRestore(version)}
                              disabled={busy}
                              data-testid="version-confirm-restore"
                              className="shrink-0 rounded-md bg-red-600 px-2 py-1 text-xs font-semibold text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-60"
                            >
                              {restoring ? 'Restoring…' : 'Confirm'}
                            </button>
                            <button
                              type="button"
                              onClick={() => setConfirmRestoreId(null)}
                              disabled={busy}
                              data-testid="version-cancel-restore"
                              className={secondaryButtonClass}
                            >
                              Cancel
                            </button>
                          </>
                        ) : (
                          <button
                            type="button"
                            onClick={() => {
                              setConfirmRestoreId(version.id);
                              setActionError(null);
                              setNotice(null);
                            }}
                            disabled={busy}
                            data-testid="version-restore"
                            className={secondaryButtonClass}
                          >
                            Restore
                          </button>
                        ))}
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>

        <div className="mt-5 flex justify-end">
          <button
            type="button"
            onClick={handleClose}
            disabled={busy}
            data-testid="version-dialog-close"
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
