/**
 * Folder API service. Wraps the backend /api/folders endpoints so pages never
 * talk to Axios directly. Error mapping to typed ApiError happens here.
 *
 * Backend endpoints used:
 * - POST /api/folders                        -> FolderResponse (201)
 * - GET /api/folders[?parentFolderId=<uuid>] -> FolderResponse[]
 * - GET /api/folders/{id}                    -> FolderResponse
 * - PATCH /api/folders/{id}                  -> FolderResponse
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

/**
 * Create a folder (POST /api/folders, returns 201). Mirrors
 * com.minidrive.dto.folder.FolderRequest: name is required (trimmed, max 255
 * chars) and parentFolderId is the parent folder or omitted for drive root.
 */
export async function createFolder(
  name: string,
  parentFolderId?: string,
): Promise<FolderSummary> {
  try {
    const { data } = await apiClient.post<FolderSummary>('/api/folders', {
      name,
      ...(parentFolderId ? { parentFolderId } : {}),
    });
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/**
 * Rename a folder (PATCH /api/folders/{id}). Mirrors
 * com.minidrive.dto.folder.FolderRequest with only the name set; the backend
 * rejects empty/oversized names and duplicates in the same location.
 */
export async function renameFolder(
  id: string,
  name: string,
): Promise<FolderSummary> {
  try {
    const { data } = await apiClient.patch<FolderSummary>(
      `/api/folders/${id}`,
      { name },
    );
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
