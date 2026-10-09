import { useCallback, useEffect, useId, useRef, useState } from 'react';

import {
  grantPermission,
  listPermissions,
  revokePermission,
} from '@/api/permissions';
import {
  createShareLink,
  listShareLinks,
  revokeShareLink,
} from '@/api/share';
import ErrorState from '@/components/drive/ErrorState';
import LoadingState from '@/components/drive/LoadingState';
import type { ApiError } from '@/types/api';
import type {
  ExpiryPreset,
  FilePermission,
  FileSummary,
  ShareLink,
  ShareRole,
} from '@/types/drive';
import { formatUpdatedAt } from '@/utils/format';

interface ShareDialogProps {
  open: boolean;
  /** File whose direct permissions and share links are managed. Caller owns it. */
  file: FileSummary;
  onClose: () => void;
}

// Pragmatic client-side email shape check; the backend validates the address
// authoritatively and rejects malformed/unknown recipients.
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const EXPIRY_OPTIONS: ReadonlyArray<{ value: ExpiryPreset; label: string }> = [
  { value: 'NEVER', label: 'Never expires' },
  { value: '1H', label: '1 hour' },
  { value: '24H', label: '24 hours' },
  { value: '7D', label: '7 days' },
  { value: '30D', label: '30 days' },
];

const EXPIRY_HOURS: Record<Exclude<ExpiryPreset, 'NEVER'>, number> = {
  '1H': 1,
  '24H': 24,
  '7D': 24 * 7,
  '30D': 24 * 30,
};

/** Convert an expiry preset to a UTC ISO-8601 instant without a zone suffix. */
function expiryToIso(preset: ExpiryPreset): string | undefined {
  if (preset === 'NEVER') return undefined;
  const ms = Date.now() + EXPIRY_HOURS[preset] * 60 * 60 * 1000;
  // The backend stores/compares LocalDateTime in UTC, so send UTC wall time.
  return new Date(ms).toISOString().slice(0, 19);
}

/** Human label for a role returned by the backend. */
function roleLabel(role: string): string {
  if (role === 'EDITOR') return 'Editor';
  if (role === 'VIEWER') return 'Viewer';
  return role;
}

function validateEmail(value: string): string | null {
  const trimmed = value.trim();
  if (trimmed.length === 0) return 'Enter an email address';
  if (!EMAIL_PATTERN.test(trimmed)) return 'Enter a valid email address';
  return null;
}

/**
 * Accessible modal dialog for managing a file's sharing (Phases 6.2 and 6.3).
 * Owners can invite collaborators by email (VIEWER/EDITOR) and, separately,
 * create revocable public share links with an optional expiry. The backend is
 * authoritative: the raw token is shown only after the server confirms
 * creation and is never recoverable when the dialog reopens, so the list shows
 * metadata only. Reopening refetches from scratch.
 */
export default function ShareDialog({ open, file, onClose }: ShareDialogProps) {
  const [collaborators, setCollaborators] = useState<FilePermission[] | null>(
    null,
  );
  const [listError, setListError] = useState<string | null>(null);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<ShareRole>('VIEWER');
  const [granting, setGranting] = useState(false);
  const [pendingUserId, setPendingUserId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirmRevokeId, setConfirmRevokeId] = useState<string | null>(null);

  // Share-link state (Phase 6.3).
  const [shareLinks, setShareLinks] = useState<ShareLink[] | null>(null);
  const [shareLinksError, setShareLinksError] = useState<string | null>(null);
  const [expiryPreset, setExpiryPreset] = useState<ExpiryPreset>('NEVER');
  const [creatingLink, setCreatingLink] = useState(false);
  const [pendingShareId, setPendingShareId] = useState<string | null>(null);
  const [confirmRevokeShareId, setConfirmRevokeShareId] = useState<
    string | null
  >(null);
  const [createdLink, setCreatedLink] = useState<{
    id: string;
    url: string;
    expiresAt: string | null;
  } | null>(null);
  const [copied, setCopied] = useState(false);
  const [linkError, setLinkError] = useState<string | null>(null);
  const [linkNotice, setLinkNotice] = useState<string | null>(null);

  const emailInputRef = useRef<HTMLInputElement>(null);
  const linkInputRef = useRef<HTMLInputElement>(null);
  const titleId = useId();
  const emailId = useId();
  const errorId = useId();

  const busy =
    granting ||
    pendingUserId !== null ||
    creatingLink ||
    pendingShareId !== null;

  // Fetch the collaborator list, surfacing failures in the dialog body.
  const loadCollaborators = useCallback(async (): Promise<void> => {
    try {
      const data = await listPermissions(file.id);
      setCollaborators(data);
      setListError(null);
    } catch (err: unknown) {
      setCollaborators(null);
      setListError((err as ApiError).message);
    }
  }, [file.id]);

  // Re-read the authoritative list after a mutation; rejects on failure so the
  // caller can surface it instead of showing success.
  const refreshCollaborators = useCallback(async (): Promise<void> => {
    const data = await listPermissions(file.id);
    setCollaborators(data);
    setListError(null);
  }, [file.id]);

  // Fetch the share-link list, surfacing failures in its own section.
  const loadShareLinks = useCallback(async (): Promise<void> => {
    try {
      const data = await listShareLinks(file.id);
      setShareLinks(data);
      setShareLinksError(null);
    } catch (err: unknown) {
      setShareLinks(null);
      setShareLinksError((err as ApiError).message);
    }
  }, [file.id]);

  const refreshShareLinks = useCallback(async (): Promise<void> => {
    const data = await listShareLinks(file.id);
    setShareLinks(data);
    setShareLinksError(null);
  }, [file.id]);

  // Reset transient state and (re)fetch whenever the dialog opens. The raw
  // token from a previous session is intentionally discarded.
  useEffect(() => {
    if (!open) return;
    setCollaborators(null);
    setListError(null);
    setActionError(null);
    setNotice(null);
    setEmail('');
    setRole('VIEWER');
    setGranting(false);
    setPendingUserId(null);
    setConfirmRevokeId(null);
    setShareLinks(null);
    setShareLinksError(null);
    setExpiryPreset('NEVER');
    setCreatingLink(false);
    setPendingShareId(null);
    setConfirmRevokeShareId(null);
    setCreatedLink(null);
    setCopied(false);
    setLinkError(null);
    setLinkNotice(null);
    void loadCollaborators();
    void loadShareLinks();
    // Defer so the element is mounted before focusing.
    requestAnimationFrame(() => emailInputRef.current?.focus());
  }, [open, file.id, loadCollaborators, loadShareLinks]);

  if (!open) return null;

  const normalizedEmail = email.trim();
  const localEmailError =
    normalizedEmail.length > 0 ? validateEmail(normalizedEmail) : null;
  const canSubmit = !busy && validateEmail(normalizedEmail) === null;

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!canSubmit) return;
    const existing = collaborators?.find(
      (permission) =>
        permission.email.toLowerCase() === normalizedEmail.toLowerCase(),
    );
    setGranting(true);
    setActionError(null);
    setNotice(null);
    try {
      const result = await grantPermission(file.id, normalizedEmail, role);
      setEmail('');
      await refreshCollaborators();
      setNotice(
        existing
          ? `Updated ${result.email} to ${roleLabel(result.role)}.`
          : `Granted ${roleLabel(result.role)} access to ${result.email}.`,
      );
    } catch (err: unknown) {
      setActionError((err as ApiError).message);
    } finally {
      setGranting(false);
    }
  };

  const handleRoleChange = async (
    permission: FilePermission,
    nextRole: ShareRole,
  ) => {
    if (busy || nextRole === permission.role) return;
    setPendingUserId(permission.userId);
    setActionError(null);
    setNotice(null);
    try {
      // The grant endpoint doubles as the update endpoint.
      const updated = await grantPermission(
        file.id,
        permission.email,
        nextRole,
      );
      await refreshCollaborators();
      setNotice(`${updated.email} is now ${roleLabel(updated.role)}.`);
    } catch (err: unknown) {
      setActionError((err as ApiError).message);
    } finally {
      setPendingUserId(null);
    }
  };

  const handleRevoke = async (permission: FilePermission) => {
    if (busy) return;
    setPendingUserId(permission.userId);
    setActionError(null);
    setNotice(null);
    try {
      await revokePermission(file.id, permission.userId);
      setConfirmRevokeId(null);
      await refreshCollaborators();
      setNotice(`Removed access for ${permission.email}.`);
    } catch (err: unknown) {
      setActionError((err as ApiError).message);
    } finally {
      setPendingUserId(null);
    }
  };

  const handleCreateLink = async () => {
    if (busy) return;
    setCreatingLink(true);
    setLinkError(null);
    setLinkNotice(null);
    setCopied(false);
    try {
      const created = await createShareLink(file.id, expiryToIso(expiryPreset));
      await refreshShareLinks();
      // Only shown after the server confirms creation.
      setCreatedLink({
        id: created.id,
        url: `${window.location.origin}/share/${created.token}`,
        expiresAt: created.expiresAt,
      });
      setLinkNotice(
        'Share link created. Copy it now — the token cannot be shown again.',
      );
    } catch (err: unknown) {
      setLinkError((err as ApiError).message);
    } finally {
      setCreatingLink(false);
    }
  };

  const handleCopyLink = async () => {
    if (createdLink === null) return;
    try {
      if (!navigator.clipboard?.writeText) {
        // Fall back to selecting the link so the user can copy it manually.
        linkInputRef.current?.select();
        setLinkError('Clipboard unavailable — select the link and copy it.');
        return;
      }
      await navigator.clipboard.writeText(createdLink.url);
      setCopied(true);
      setLinkError(null);
    } catch {
      linkInputRef.current?.select();
      setLinkError('Copy failed — select the link and copy it manually.');
    }
  };

  const handleRevokeLink = async (link: ShareLink) => {
    if (busy) return;
    setPendingShareId(link.id);
    setLinkError(null);
    setLinkNotice(null);
    try {
      await revokeShareLink(file.id, link.id);
      setConfirmRevokeShareId(null);
      await refreshShareLinks();
      if (createdLink?.id === link.id) {
        setCreatedLink(null);
      }
      setLinkNotice('Share link revoked. Its token no longer works.');
    } catch (err: unknown) {
      setLinkError((err as ApiError).message);
    } finally {
      setPendingShareId(null);
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

  const inputClass =
    'min-w-0 flex-1 rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-900 outline-none transition-colors focus:border-blue-500 focus:ring-1 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-60';
  const secondaryButtonClass =
    'shrink-0 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60';

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4"
      onKeyDown={handleKeyDown}
      data-testid="share-dialog-overlay"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={actionError ? errorId : undefined}
        data-testid="share-dialog"
        className="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-lg bg-white p-5 shadow-xl"
      >
        <h2
          id={titleId}
          className="truncate text-sm font-bold uppercase tracking-wider text-blue-800"
        >
          Share “{file.name}”
        </h2>

        <form onSubmit={handleSubmit} className="mt-4" noValidate>
          <label
            htmlFor={emailId}
            className="block text-xs font-semibold uppercase tracking-wide text-gray-500"
          >
            Invite by email
          </label>
          <div className="mt-1 flex items-start gap-2">
            <input
              ref={emailInputRef}
              id={emailId}
              type="email"
              value={email}
              onChange={(event) => {
                setEmail(event.target.value);
                if (actionError !== null) setActionError(null);
                if (notice !== null) setNotice(null);
              }}
              disabled={busy}
              placeholder="collaborator@example.com"
              autoComplete="off"
              className={inputClass}
            />
            <select
              aria-label="Access role"
              value={role}
              onChange={(event) => setRole(event.target.value as ShareRole)}
              disabled={busy}
              data-testid="share-role-select"
              className="shrink-0 rounded-md border border-gray-300 bg-white px-2 py-2 text-sm text-gray-900 outline-none transition-colors focus:border-blue-500 focus:ring-1 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <option value="VIEWER">Viewer</option>
              <option value="EDITOR">Editor</option>
            </select>
            <button
              type="submit"
              disabled={!canSubmit}
              data-testid="share-submit"
              className="shrink-0 rounded-md bg-blue-600 px-3 py-2 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {granting ? 'Sharing…' : 'Share'}
            </button>
          </div>
          {localEmailError !== null && actionError === null && (
            <p
              role="alert"
              data-testid="share-email-error"
              className="mt-2 text-sm font-medium text-red-600"
            >
              {localEmailError}
            </p>
          )}
        </form>

        {actionError !== null && (
          <p
            id={errorId}
            role="alert"
            data-testid="share-dialog-error"
            className="mt-3 text-sm font-medium text-red-600"
          >
            {actionError}
          </p>
        )}
        {notice !== null && (
          <p
            role="status"
            data-testid="share-dialog-notice"
            className="mt-3 text-sm font-medium text-green-700"
          >
            {notice}
          </p>
        )}

        <div className="mt-5">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-gray-500">
            People with access
          </h3>

          {listError !== null ? (
            <div className="mt-2">
              <ErrorState message={listError} onRetry={() => void loadCollaborators()} />
            </div>
          ) : collaborators === null ? (
            <div className="mt-2" data-testid="share-dialog-loading">
              <LoadingState rows={2} />
            </div>
          ) : collaborators.length === 0 ? (
            <p
              className="mt-2 text-sm text-gray-500"
              data-testid="share-dialog-empty"
            >
              No one else has access to this file yet.
            </p>
          ) : (
            <ul
              className="mt-2 divide-y divide-gray-100 rounded-lg border border-gray-200"
              data-testid="share-dialog-list"
            >
              {collaborators.map((permission) => {
                const rowPending = pendingUserId === permission.userId;
                const confirming = confirmRevokeId === permission.userId;
                return (
                  <li
                    key={permission.userId}
                    className="flex items-center gap-3 px-3 py-2"
                    data-testid="share-collaborator"
                  >
                    <span
                      className="min-w-0 flex-1 truncate text-sm text-gray-900"
                      title={permission.email}
                    >
                      {permission.email}
                    </span>
                    <select
                      aria-label={`Role for ${permission.email}`}
                      value={permission.role}
                      disabled={busy}
                      onChange={(event) =>
                        void handleRoleChange(
                          permission,
                          event.target.value as ShareRole,
                        )
                      }
                      data-testid="share-collaborator-role"
                      className="shrink-0 rounded-md border border-gray-300 bg-white px-2 py-1 text-xs text-gray-900 outline-none transition-colors focus:border-blue-500 focus:ring-1 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      <option value="VIEWER">Viewer</option>
                      <option value="EDITOR">Editor</option>
                    </select>

                    {confirming ? (
                      <span className="flex shrink-0 items-center gap-1">
                        <button
                          type="button"
                          onClick={() => void handleRevoke(permission)}
                          disabled={busy}
                          data-testid="share-confirm-revoke"
                          className="rounded-md bg-red-600 px-2 py-1 text-xs font-semibold text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          {rowPending ? 'Removing…' : 'Confirm'}
                        </button>
                        <button
                          type="button"
                          onClick={() => setConfirmRevokeId(null)}
                          disabled={busy}
                          data-testid="share-cancel-revoke"
                          className="rounded-md border border-gray-300 bg-white px-2 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          Cancel
                        </button>
                      </span>
                    ) : (
                      <button
                        type="button"
                        onClick={() => setConfirmRevokeId(permission.userId)}
                        disabled={busy}
                        data-testid="share-revoke"
                        className="shrink-0 rounded-md border border-gray-300 bg-white px-2 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60"
                      >
                        Remove
                      </button>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </div>

        <div className="mt-5 border-t border-gray-100 pt-4">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-gray-500">
            Public share links
          </h3>
          <p className="mt-1 text-xs text-gray-400">
            Anyone with the link can view and download this file without signing
            in.
          </p>

          <div className="mt-2 flex items-center gap-2">
            <select
              aria-label="Link expiration"
              value={expiryPreset}
              onChange={(event) =>
                setExpiryPreset(event.target.value as ExpiryPreset)
              }
              disabled={busy}
              data-testid="share-link-expiry"
              className="min-w-0 flex-1 rounded-md border border-gray-300 bg-white px-2 py-2 text-sm text-gray-900 outline-none transition-colors focus:border-blue-500 focus:ring-1 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {EXPIRY_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            <button
              type="button"
              onClick={() => void handleCreateLink()}
              disabled={busy}
              data-testid="share-link-create"
              className="shrink-0 rounded-md bg-blue-600 px-3 py-2 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {creatingLink ? 'Creating…' : 'Create link'}
            </button>
          </div>

          {createdLink !== null && (
            <div
              className="mt-3 rounded-lg border border-green-200 bg-green-50 p-3"
              data-testid="share-link-created"
            >
              <label
                htmlFor={`${titleId}-link`}
                className="block text-xs font-semibold uppercase tracking-wide text-green-700"
              >
                New link (shown once)
              </label>
              <div className="mt-1 flex items-center gap-2">
                <input
                  ref={linkInputRef}
                  id={`${titleId}-link`}
                  type="text"
                  readOnly
                  value={createdLink.url}
                  data-testid="share-link-url"
                  onFocus={(event) => event.target.select()}
                  className="min-w-0 flex-1 rounded-md border border-green-300 bg-white px-3 py-2 text-xs text-gray-900"
                />
                <button
                  type="button"
                  onClick={() => void handleCopyLink()}
                  data-testid="share-link-copy"
                  className="shrink-0 rounded-md bg-green-600 px-3 py-2 text-xs font-semibold text-white transition-colors hover:bg-green-700"
                >
                  {copied ? 'Copied' : 'Copy'}
                </button>
              </div>
              <p className="mt-1 text-xs text-green-700">
                {createdLink.expiresAt
                  ? `Expires ${formatUpdatedAt(createdLink.expiresAt)}.`
                  : 'This link never expires.'}{' '}
                Copy it now — it cannot be recovered later.
              </p>
            </div>
          )}

          {linkError !== null && (
            <p
              role="alert"
              data-testid="share-link-error"
              className="mt-3 text-sm font-medium text-red-600"
            >
              {linkError}
            </p>
          )}
          {linkNotice !== null && (
            <p
              role="status"
              data-testid="share-link-notice"
              className="mt-3 text-sm font-medium text-green-700"
            >
              {linkNotice}
            </p>
          )}

          <div className="mt-3">
            {shareLinksError !== null ? (
              <ErrorState
                message={shareLinksError}
                onRetry={() => void loadShareLinks()}
              />
            ) : shareLinks === null ? (
              <div data-testid="share-links-loading">
                <LoadingState rows={2} />
              </div>
            ) : shareLinks.length === 0 ? (
              <p
                className="text-sm text-gray-500"
                data-testid="share-links-empty"
              >
                No share links yet.
              </p>
            ) : (
              <ul
                className="divide-y divide-gray-100 rounded-lg border border-gray-200"
                data-testid="share-links-list"
              >
                {shareLinks.map((link) => {
                  const rowPending = pendingShareId === link.id;
                  const confirming = confirmRevokeShareId === link.id;
                  return (
                    <li
                      key={link.id}
                      className="flex items-center gap-3 px-3 py-2"
                      data-testid="share-link-row"
                    >
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-xs text-gray-700">
                          Created {formatUpdatedAt(link.createdAt)}
                        </p>
                        <p className="truncate text-xs text-gray-400">
                          {link.expiresAt
                            ? `Expires ${formatUpdatedAt(link.expiresAt)}`
                            : 'Never expires'}
                        </p>
                      </div>
                      <span
                        data-testid="share-link-status"
                        className={
                          link.expired
                            ? 'shrink-0 rounded-full bg-gray-100 px-2 py-0.5 text-xs font-semibold text-gray-500'
                            : 'shrink-0 rounded-full bg-green-100 px-2 py-0.5 text-xs font-semibold text-green-700'
                        }
                      >
                        {link.expired ? 'Expired' : 'Active'}
                      </span>
                      {confirming ? (
                        <span className="flex shrink-0 items-center gap-1">
                          <button
                            type="button"
                            onClick={() => void handleRevokeLink(link)}
                            disabled={busy}
                            data-testid="share-link-confirm-revoke"
                            className="rounded-md bg-red-600 px-2 py-1 text-xs font-semibold text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-60"
                          >
                            {rowPending ? 'Revoking…' : 'Confirm'}
                          </button>
                          <button
                            type="button"
                            onClick={() => setConfirmRevokeShareId(null)}
                            disabled={busy}
                            data-testid="share-link-cancel-revoke"
                            className="rounded-md border border-gray-300 bg-white px-2 py-1 text-xs font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60"
                          >
                            Cancel
                          </button>
                        </span>
                      ) : (
                        <button
                          type="button"
                          onClick={() => setConfirmRevokeShareId(link.id)}
                          disabled={busy}
                          data-testid="share-link-revoke"
                          className={secondaryButtonClass}
                        >
                          Revoke
                        </button>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>

        <div className="mt-5 flex justify-end">
          <button
            type="button"
            onClick={handleClose}
            disabled={busy}
            data-testid="share-dialog-close"
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
