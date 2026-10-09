/**
 * Drive domain types. These mirror the backend Spring Boot DTOs
 * (com.minidrive.dto.file.FileResponse, com.minidrive.dto.folder.FolderResponse)
 * and must be kept in sync with them.
 */

/** Mirrors FileResponse — compact file summary returned by list endpoints. */
export interface FileSummary {
  id: string;
  name: string;
  folderId: string | null;
  contentType: string;
  sizeBytes: number;
  updatedAt: string;
}

/** Mirrors FolderResponse — folder summary returned by folder endpoints. */
export interface FolderSummary {
  id: string;
  name: string;
  parentFolderId: string | null;
  ownerId: string;
  createdAt: string;
  updatedAt: string;
}

/** One breadcrumb entry in the current location path. */
export interface Crumb {
  id: string | null; // null = drive root
  name: string;
}

/**
 * Roles a user can hold on a file. Mirrors the backend FileRole enum: OWNER is
 * never persisted as a permission row (ownership lives on the file itself).
 */
export type FileRole = 'OWNER' | 'EDITOR' | 'VIEWER';

/** Roles that may be granted to a collaborator (OWNER can never be granted). */
export type ShareRole = 'EDITOR' | 'VIEWER';

/**
 * One collaborator's direct permission on a file. Mirrors the backend
 * com.minidrive.dto.permission.PermissionResponse — grant/list responses only
 * ever contain VIEWER or EDITOR rows.
 */
export interface FilePermission {
  userId: string;
  email: string;
  role: FileRole;
  createdAt: string;
}

/**
 * Metadata for an existing public share link. Mirrors the backend
 * com.minidrive.dto.sharelink.ShareLinkResponse — the raw bearer token and its
 * stored hash are never part of this shape.
 */
export interface ShareLink {
  id: string;
  fileId: string;
  createdAt: string;
  /** ISO instant the link stops working, or null when it never expires. */
  expiresAt: string | null;
  /** Whether the link has already expired on the server. */
  expired: boolean;
}

/**
 * Response returned exactly once when a share link is created. Mirrors
 * com.minidrive.dto.sharelink.CreatedShareLinkResponse — `token` is the raw
 * bearer token and is never recoverable afterwards.
 */
export interface CreatedShareLink {
  id: string;
  fileId: string;
  token: string;
  createdAt: string;
  expiresAt: string | null;
}

/**
 * Minimal, safe metadata about a publicly shared file. Mirrors
 * com.minidrive.dto.sharelink.SharedFileResponse; no id, owner, checksum or
 * storage key is exposed.
 */
export interface SharedFileMetadata {
  name: string;
  contentType: string;
  sizeBytes: number;
}

export type ExpiryPreset = 'NEVER' | '1H' | '24H' | '7D' | '30D';

