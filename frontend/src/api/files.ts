/**
 * File API service. Wraps the backend /api/files endpoints so pages never talk
 * to Axios directly. Error mapping to typed ApiError happens here.
 *
 * Backend endpoints used:
 * - POST /api/files (multipart/form-data) -> FileUploadResponse (201)
 * - GET /api/files[?folderId=<uuid>] -> FileResponse[]
 * - GET /api/files/{id}/download     -> binary stream (attachment)
 * - PATCH /api/files/{id}            -> FileMetadataResponse
 */
import { apiClient } from '@/api/client';
import { toApiError } from '@/api/errors';
import type { AxiosProgressEvent } from 'axios';
import type { FileSummary } from '@/types/drive';

/**
 * Mirrors com.minidrive.dto.file.FileUploadResponse. Sentinel REST
 * metadata-only endpoint returns UPLOAD_STATUS="PENDING" for metadata
 * requests, while the multipart upload endpoint returns "UPLOADED".
 */
export interface FileUploadResponse {
  id: string;
  name: string;
  folderId: string | null;
  ownerId: string;
  objectKey: string;
  contentType: string;
  sizeBytes: number | null;
  checksum: string | null;
  uploadStatus: string;
  createdAt: string;
  updatedAt: string;
}

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
 * Upload a real file to the current folder via the multipart/form-data
 * endpoint. The mandatory "file" part is the selected File object; folderId
 * is optional and references the current folder (omit for the drive root).
 * Axios sets the multipart boundary automatically, so Content-Type is not
 * set manually here. `onUploadProgress` gives real progress events when the
 * browser/XHR provides them (Axios 1.x does for file bodies).
 */
export async function uploadFile(
  file: globalThis.File,
  folderId?: string,
  onUploadProgress?: (percent: number) => void,
): Promise<FileUploadResponse> {
  try {
    const formData = new FormData();
    formData.append('file', file);
    if (folderId) {
      formData.append('folderId', folderId);
    }
    const { data } = await apiClient.post<FileUploadResponse>(
      '/api/files',
      formData,
      {
        onUploadProgress: (event: AxiosProgressEvent) => {
          if (event.total && event.total > 0) {
            onUploadProgress?.(
              Math.min(100, (event.loaded / event.total) * 100),
            );
          }
        },
      },
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/**
 * Rename a file (PATCH /api/files/{id}). Mirrors
 * com.minidrive.dto.file.FileMetadataUpdateRequest with only the name set;
 * unrelated metadata fields are never sent and untouched fields stay null.
 */
export async function renameFile(
  id: string,
  name: string,
): Promise<FileSummary> {
  try {
    const { data } = await apiClient.patch<FileSummary>(`/api/files/${id}`, {
      name,
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
