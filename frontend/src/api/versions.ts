/**
 * Version-history API service. Wraps the backend /api/files/{fileId}/versions
 * endpoints so components never talk to Axios directly. Error mapping to typed
 * ApiError happens here.
 *
 * Backend endpoints used:
 * - GET  /api/files/{fileId}/versions                          -> FileVersion[]
 *          (newest first; never contains storage object keys)
 * - GET  /api/files/{fileId}/versions/{versionId}/download    -> binary stream
 * - POST /api/files/{fileId}/versions/{versionId}/restore     -> FileMetadata
 *
 * All calls go through the authenticated API client, so the Authorization
 * header is applied exactly like every other protected request.
 */
import { apiClient } from '@/api/client';
import { toApiError } from '@/api/errors';
import type { FileVersion } from '@/types/drive';

/**
 * The subset of the backend FileMetadataResponse the version UI relies on
 * after a restore. Only the fields actually consumed are modelled.
 */
export interface VersionRestoreResult {
  id: string;
  contentType: string;
  sizeBytes: number | null;
  updatedAt: string;
  currentVersion: number;
}

/** List a file's version history, newest first (any caller with read access). */
export async function listFileVersions(fileId: string): Promise<FileVersion[]> {
  try {
    const { data } = await apiClient.get<FileVersion[]>(
      `/api/files/${fileId}/versions`,
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/**
 * Download one historical version's bytes as a Blob through the authenticated
 * API client. The version is scoped to the file on the server, so an id that
 * belongs to another file resolves to a controlled error rather than content.
 */
export async function downloadFileVersion(
  fileId: string,
  versionId: string,
): Promise<Blob> {
  try {
    const { data } = await apiClient.get<Blob>(
      `/api/files/${fileId}/versions/${versionId}/download`,
      { responseType: 'blob' },
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/**
 * Restore an earlier version as the file's new current content. The backend
 * requires edit permission and appends a fresh version rather than rewinding
 * history; the returned metadata carries the new currentVersion.
 */
export async function restoreFileVersion(
  fileId: string,
  versionId: string,
): Promise<VersionRestoreResult> {
  try {
    const { data } = await apiClient.post<VersionRestoreResult>(
      `/api/files/${fileId}/versions/${versionId}/restore`,
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}
