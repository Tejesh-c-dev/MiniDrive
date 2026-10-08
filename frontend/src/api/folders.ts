/**
 * Folder API service. Wraps the backend /api/folders endpoints so pages never
 * talk to Axios directly. Error mapping to typed ApiError happens here.
 *
 * Backend endpoints used:
 * - GET /api/folders[?parentFolderId=<uuid>] -> FolderResponse[]
 * - GET /api/folders/{id}                    -> FolderResponse
 */
import { apiClient } from '@/api/client';
import { toApiError } from '@/api/errors';
import type { FolderSummary } from '@/types/drive';

/** List folders, optionally scoped to a parent (omit for drive root). */
export async function listFolders(
  parentFolderId?: string,
): Promise<FolderSummary[]> {
  try {
    const { data } = await apiClient.get<FolderSummary[]>('/api/folders', {
      params: parentFolderId ? { parentFolderId } : undefined,
    });
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/** Fetch a single folder by id (FolderResponse). */
export async function getFolder(id: string): Promise<FolderSummary> {
  try {
    const { data } = await apiClient.get<FolderSummary>(`/api/folders/${id}`);
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}
