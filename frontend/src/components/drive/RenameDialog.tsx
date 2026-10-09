import { useEffect, useId, useRef, useState } from 'react';

import type { ApiError } from '@/types/api';

interface RenameDialogProps {
  open: boolean;
  /** Value pre-filled into the input when the dialog opens. */
  initialName: string;
  /** Entity kind shown in labels/buttons, e.g. "folder" or "file". */
  entityLabel: string;
  /** Apply the rename via the API; resolves when the request finishes. */
  onRename: (name: string) => Promise<void>;
  onClose: () => void;
}

const MAX_NAME_LENGTH = 255;

/**
 * Accessible modal dialog for renaming a folder or file (Phase 5.4C). Pre-fills
 * the current name, trims input, rejects empty names and the unchanged name,
 * caps at the backend's 255-character maximum, disables Save while the request
 * is in flight, and surfaces backend validation and duplicate-name errors.
 * Closing resets the form so a later open starts fresh.
 */
export default function RenameDialog({
  open,
  initialName,
  entityLabel,
  onRename,
  onClose,
}: RenameDialogProps) {
  const [name, setName] = useState(initialName);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const titleId = useId();
  const errorId = useId();

  // Re-seed the form (and focus the input) whenever the dialog targets a new
  // entity or is re-opened.
  useEffect(() => {
    if (open) {
      setName(initialName);
      setError(null);
      setSubmitting(false);
      // Defer so the element is mounted before focusing.
      requestAnimationFrame(() => inputRef.current?.focus());
    }
  }, [open, initialName]);

  if (!open) return null;

  const trimmed = name.trim();
  const TitleCase = entityLabel.charAt(0).toUpperCase() + entityLabel.slice(1);
  const localError =
    trimmed.length === 0
      ? `${TitleCase} name is required`
      : trimmed.length > MAX_NAME_LENGTH
        ? `${TitleCase} name must be at most ${MAX_NAME_LENGTH} characters`
        : trimmed === initialName.trim()
          ? `Choose a different ${entityLabel} name`
          : null;

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (submitting || localError !== null) return;
    setSubmitting(true);
    setError(null);
    try {
      await onRename(trimmed);
      onClose();
    } catch (err: unknown) {
      setError((err as ApiError).message);
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
      data-testid="rename-dialog-overlay"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={error ? errorId : undefined}
        data-testid="rename-dialog"
        className="w-full max-w-sm rounded-lg bg-white p-5 shadow-xl"
        onClick={(event) => event.stopPropagation()}
      >
        <h2
          id={titleId}
          className="text-sm font-bold uppercase tracking-wider text-blue-800"
        >
          Rename {entityLabel}
        </h2>

        <form onSubmit={handleSubmit} className="mt-4" noValidate>
          <label
            htmlFor={`${titleId}-name`}
            className="block text-xs font-semibold uppercase tracking-wide text-gray-500"
          >
            {TitleCase} name
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
          />

          {error && (
            <p
              id={errorId}
              role="alert"
              data-testid="rename-dialog-error"
              className="mt-2 text-sm font-medium text-red-600"
            >
              {error}
            </p>
          )}
          {!error && localError && (
            <p
              id={errorId}
              role="alert"
              data-testid="rename-dialog-error"
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
              data-testid="rename-dialog-cancel"
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting || localError !== null}
              data-testid="rename-dialog-save"
              className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {submitting ? 'Saving…' : 'Save'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
