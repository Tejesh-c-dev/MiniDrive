import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { downloadPublicShare, getPublicShare } from '@/api/share';
import ErrorState from '@/components/drive/ErrorState';
import LoadingState from '@/components/drive/LoadingState';
import type { ApiError } from '@/types/api';
import type { SharedFileMetadata } from '@/types/drive';
import { formatFileSize } from '@/utils/format';

/**
 * Public, login-free view of a file reached through a share link (Phase 6.3).
 * It reads only the anonymous /api/share endpoints — never the owner's
 * authenticated file APIs — and shows just the permitted metadata plus a
 * Download action. Invalid, revoked, expired, or unavailable links surface the
 * server's controlled error message instead of any private data.
 */
export default function SharedFilePage() {
  const { token } = useParams<{ token: string }>();
  const [metadata, setMetadata] = useState<SharedFileMetadata | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const load = useCallback(async (): Promise<void> => {
    if (!token) {
      setMetadata(null);
      setError('This share link is invalid.');
      return;
    }
    setMetadata(null);
    setError(null);
    try {
      const data = await getPublicShare(token);
      setMetadata(data);
    } catch (err: unknown) {
      setMetadata(null);
      setError((err as ApiError).message);
    }
  }, [token]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleDownload = async () => {
    if (!token || metadata === null) return;
    setDownloading(true);
    setDownloadError(null);
    try {
      const blob = await downloadPublicShare(token);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = metadata.name;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    } catch (err: unknown) {
      setDownloadError((err as ApiError).message);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <section className="w-full max-w-md pt-6 pb-16">
      <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
        <p className="text-xs font-semibold uppercase tracking-wider text-blue-800">
          Shared with you
        </p>

        {error !== null ? (
          <div className="mt-4">
            <ErrorState message={error} onRetry={() => void load()} />
          </div>
        ) : metadata === null ? (
          <div className="mt-4" data-testid="shared-file-loading">
            <LoadingState rows={2} />
          </div>
        ) : (
          <>
            <h1
              className="mt-2 truncate text-lg font-bold text-gray-900"
              title={metadata.name}
              data-testid="shared-file-name"
            >
              {metadata.name}
            </h1>
            <p className="mt-1 text-sm text-gray-500">
              {metadata.contentType} · {formatFileSize(metadata.sizeBytes)}
            </p>

            <button
              type="button"
              onClick={() => void handleDownload()}
              disabled={downloading}
              data-testid="shared-file-download"
              className="mt-5 w-full rounded-md bg-blue-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {downloading ? 'Downloading…' : 'Download'}
            </button>
            {downloadError !== null && (
              <p
                role="alert"
                data-testid="shared-file-download-error"
                className="mt-3 text-sm font-medium text-red-600"
              >
                {downloadError}
              </p>
            )}
          </>
        )}

        <p className="mt-6 text-center text-xs text-gray-400">
          <Link to="/" className="transition-colors hover:text-gray-600">
            ← Back to MiniDrive
          </Link>
        </p>
      </div>
    </section>
  );
}
