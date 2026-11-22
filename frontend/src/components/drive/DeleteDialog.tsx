import { useEffect, useId, useRef, useState } from 'react';
import type { ApiError } from '@/types/api';

export type DeleteTarget = { kind: 'file' | 'folder'; id: string; name: string };

interface DeleteDialogProps {
  target: DeleteTarget;
  onDelete: () => Promise<void>;
  onClose: () => void;
}

/** Confirmation dialog for permanent deletion via the backend cleanup lifecycle. */
export default function DeleteDialog({ target, onDelete, onClose }: DeleteDialogProps) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submittingRef = useRef(false);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  const errorId = useId();
  const isFolder = target.kind === 'folder';

  useEffect(() => {
    requestAnimationFrame(() => cancelRef.current?.focus());
  }, [target.id]);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (submittingRef.current) return;
    submittingRef.current = true;
    setSubmitting(true);
    setError(null);
    try {
      await onDelete();
      onClose();
    } catch (err: unknown) {
      setError((err as ApiError).message || 'Could not delete this item. Please try again.');
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    if (!submittingRef.current) onClose();
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4"
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.stopPropagation();
          handleCancel();
        }
      }}
      data-testid="delete-dialog-overlay"
    >
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={error ? `${descriptionId} ${errorId}` : descriptionId}
        data-testid="delete-dialog"
        className="w-full max-w-sm rounded-lg bg-white p-5 shadow-xl"
        onClick={(event) => event.stopPropagation()}
      >
        <h2 id={titleId} className="text-sm font-bold uppercase tracking-wider text-red-700">
          Delete {isFolder ? 'folder' : 'file'}?
        </h2>
        <p id={descriptionId} className="mt-3 text-sm text-gray-700">
          Permanently delete <strong>{target.name}</strong>?
          {isFolder && ' This recursively deletes the folder, all nested folders, files, and stored file versions.'}
        </p>
        {error && <p id={errorId} role="alert" data-testid="delete-dialog-error" className="mt-3 text-sm font-medium text-red-600">{error}</p>}
        <form onSubmit={handleSubmit} className="mt-5 flex justify-end gap-2">
          <button
            ref={cancelRef}
            type="button"
            onClick={handleCancel}
            disabled={submitting}
            data-testid="delete-dialog-cancel"
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-semibold text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60"
          >Cancel</button>
          <button
            type="submit"
            disabled={submitting}
            data-testid="delete-dialog-confirm"
            className="rounded-md bg-red-600 px-3 py-1.5 text-sm font-semibold text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-60"
          >{submitting ? 'Deleting…' : 'Delete permanently'}</button>
        </form>
      </div>
    </div>
  );
}
