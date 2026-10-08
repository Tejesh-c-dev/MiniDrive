/**
 * File API service. Wraps the backend /api/files endpoints so pages never talk
 * to Axios directly. Error mapping to typed ApiError happens here.
 *
 * Backend endpoints used:
 * - GET /api/files[?folderId=<uuid>] -> FileResponse[]
 * - GET /api/files/{id}/download     -> binary stream (attachment)
 */
import { apiClient } from '@/api/client';
import { toApiError } from '@/api/errors';
import type { FileSummary } from '@/types/drive';

/** List files, optionally scoped to a folder (omit folderId for drive root). */
export async function listFiles(folderId?: string): Promise<FileSummary[]> {
  try {
    const { data } = await apiClient.get<FileSummary[]>('/api/files', {
      params: folderId ? { folderId } : undefined,
    });
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/**
 * Download a file's content as a Blob through the authenticated API client so
 * the Authorization: Bearer header is applied. Storage URLs are never exposed
 * to the browser.
 */
export async function downloadFile(id: string): Promise<Blob> {
  try {
    const { data } = await apiClient.get<Blob>(`/api/files/${id}/download`, {
      responseType: 'blob',
    });
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}
