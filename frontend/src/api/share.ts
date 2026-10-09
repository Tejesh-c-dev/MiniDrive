/**
 * Share-link API service (Phase 6.3). Owner management calls use the
 * authenticated client; the public read/download calls use the same client but
 * work without a stored JWT because the backend permits them anonymously.
 *
 * Backend endpoints used:
 * - POST   /api/files/{id}/share-link            -> CreatedShareLink (201)
 *          body { expiresAt? } (omit/empty = never expires)
 * - GET    /api/files/{id}/share-links           -> ShareLink[] (metadata only)
 * - DELETE /api/files/{id}/share-links/{shareId}  -> 204 No Content (revokes)
 * - GET    /api/share/{token}                     -> SharedFileMetadata
 * - GET    /api/share/{token}/download            -> binary stream (attachment)
 */
import { apiClient } from '@/api/client';
import { toApiError } from '@/api/errors';
import type {
  CreatedShareLink,
  ShareLink,
  SharedFileMetadata,
} from '@/types/drive';

/** Create a link for an owned file. Returns the raw token exactly once. */
export async function createShareLink(
  fileId: string,
  expiresAt?: string,
): Promise<CreatedShareLink> {
  try {
    const { data } = await apiClient.post<CreatedShareLink>(
      `/api/files/${fileId}/share-link`,
      expiresAt ? { expiresAt } : {},
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/** List a file's existing share links (owner only; metadata only). */
export async function listShareLinks(fileId: string): Promise<ShareLink[]> {
  try {
    const { data } = await apiClient.get<ShareLink[]>(
      `/api/files/${fileId}/share-links`,
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/** Revoke a link by id (owner only). The token stops working immediately. */
export async function revokeShareLink(
  fileId: string,
  shareId: string,
): Promise<void> {
  try {
    await apiClient.delete(`/api/files/${fileId}/share-links/${shareId}`);
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/** Read minimal metadata for a public link without requiring login. */
export async function getPublicShare(
  token: string,
): Promise<SharedFileMetadata> {
  try {
    const { data } = await apiClient.get<SharedFileMetadata>(
      `/api/share/${encodeURIComponent(token)}`,
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/** Download a publicly shared file as a Blob without requiring login. */
export async function downloadPublicShare(token: string): Promise<Blob> {
  try {
    const { data } = await apiClient.get<Blob>(
      `/api/share/${encodeURIComponent(token)}/download`,
      { responseType: 'blob' },
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}
