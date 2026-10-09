import { useEffect, useId, useRef, useState } from 'react';

import type { ApiError } from '@/types/api';

interface NewFolderDialogProps {
  open: boolean;
  /** Create the folder via the API; resolves when the request finishes. */
  onCreate: (name: string) => Promise<void>;
  onClose: () => void;
}

const MAX_NAME_LENGTH = 255;

/**
 * Accessible modal dialog for creating a folder (Phase 5.4B). Trims input,
 * rejects empty names, caps at the backend's 255-character maximum, disables
 * Create while the request is in flight, and surfaces backend validation and
 * duplicate-name errors. Closing resets the form.
 */
export default function NewFolderDialog({
  open,
  onCreate,
  onClose,
}: NewFolderDialogProps) {
  const [name, setName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const errorId = useId();

  // Reset the form and focus the input whenever the dialog opens.
  useEffect(() => {
    if (open) {
      setName('');
      setError(null);
      setSubmitting(false);
      // Defer so the element is mounted before focusing.
      requestAnimationFrame(() => inputRef.current?.focus());
    }
  }, [open]);

  if (!open) return null;

  const trimmed = name.trim();
  const localError =
    trimmed.length === 0
      ? 'Folder name is required'
      : trimmed.length > MAX_NAME_LENGTH
        ? `Folder name must be at most ${MAX_NAME_LENGTH} characters`
        : null;

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (submitting || localError !== null) return;
    setSubmitting(true);
    setError(null);
    try {
      await onCreate(trimmed);
      // Result is normally closed by the caller on success; this only fires
      // if the caller leaves the dialog open after a successful create.
      onClose();
    } catch (err: unknown) {
      setError((err as ApiError).message);
      setSubmitting(false);
      inputRef.current?.focus();
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    if (submitting) return;
    onClose();
  };

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.stopPropagation();
      handleCancel();
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4"
      onKeyDown={handleKeyDown}
      data-testid="new-folder-dialog-overlay"
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={error ? errorId : undefined}
        data-testid="new-folder-dialog"
        className="w-full max-w-sm rounded-lg bg-white p-5 shadow-xl"
        onClick={(event) => event.stopPropagation()}
      >
        <h2
          id={titleId}
          className="text-sm font-bold uppercase tracking-wider text-blue-800"
        >
          New Folder
        </h2>

        <form onSubmit={handleSubmit} className="mt-4" noValidate>
          <label
            htmlFor={`${titleId}-name`}
            className="block text-xs font-semibold uppercase tracking-wide text-gray-500"
          >
            Folder name
          </label>
          <input
            ref={inputRef}
            id={`${titleId}-name`}
            type="text"
            value={name}
            maxLength={MAX_NAME_LENGTH}
            onChange={(event) => {
              setName(event.target.value);
              if (error !== null) setError(null);
            }}
            disabled={submitting}
            className="mt-1 w-full rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-900 outline-none transition-colors focus:border-blue-500 focus:ring-1 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-60"
            placeholder="e.g. Projects"
          />

          {error && (
            <p
              id={errorId}
              role="alert"
              data-testid="new-folder-error"
              className="mt-2 text-sm font-medium text-red-600"
            >
              {error}
            </p>
          )}
          {!error && localError && (
            <p
              id={errorId}
              role="alert"
              data-testid="new-folder-error"
              className="mt-2 text-sm font-medium text-red-600"
            >
              {localError}
            </p>
          )}

          <div className="mt-4 flex justify-end gap-2">
            <button
              type="button"
              onClick={handleCancel}
              disabled={submitting}
              data-testid="new-folder-cancel"
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting || localError !== null}
              data-testid="new-folder-create"
              className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {submitting ? 'Creating…' : 'Create'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
