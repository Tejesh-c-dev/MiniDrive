/**
 * File-permission API service. Wraps the backend /api/files/{id}/permissions
 * endpoints so components never talk to Axios directly. Error mapping to typed
 * ApiError happens here.
 *
 * Backend endpoints used (owner-only; the acting user comes from the JWT):
 * - GET    /api/files/{id}/permissions           -> PermissionResponse[]
 * - POST   /api/files/{id}/permissions           -> PermissionResponse
 *          body { email, role: "VIEWER" | "EDITOR" } (grants or updates)
 * - DELETE /api/files/{id}/permissions/{userId}  -> 204 No Content (revokes)
 */
import { apiClient } from '@/api/client';
import { toApiError } from '@/api/errors';
import type { FilePermission, ShareRole } from '@/types/drive';

/** List a file's direct collaborators (owner only). */
export async function listPermissions(
  fileId: string,
): Promise<FilePermission[]> {
  try {
    const { data } = await apiClient.get<FilePermission[]>(
      `/api/files/${fileId}/permissions`,
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/**
 * Grant a collaborator access, or update their role when they already have a
 * permission row. Mirrors com.minidrive.dto.permission.PermissionRequest —
 * the backend is authoritative and rejects OWNER, unknown emails, and the
 * file's own owner.
 */
export async function grantPermission(
  fileId: string,
  email: string,
  role: ShareRole,
): Promise<FilePermission> {
  try {
    const { data } = await apiClient.post<FilePermission>(
      `/api/files/${fileId}/permissions`,
      { email, role },
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/** Revoke a collaborator's direct access by user id (owner only). */
export async function revokePermission(
  fileId: string,
  userId: string,
): Promise<void> {
  try {
    await apiClient.delete(`/api/files/${fileId}/permissions/${userId}`);
  } catch (error: unknown) {
    throw toApiError(error);
  }
}
