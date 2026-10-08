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
