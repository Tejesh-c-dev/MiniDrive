/**
 * Resumable, chunked upload API service (Phase 8.3). Wraps the persistent
 * upload-session endpoints implemented in Phase 8.2 so the resumable upload
 * client never talks to Axios directly and error mapping to a typed ApiError
 * happens here, exactly like the other API modules.
 *
 * Backend endpoints used (all authenticated and owner-scoped):
 * - POST   /api/uploads                            -> UploadSessionResponse (201)
 *          body { filename, contentType, totalSizeBytes, folderId?, targetFileId? }
 * - GET    /api/uploads/{sessionId}                -> UploadSessionStatusResponse
 *          (includes `uploadedChunks`: the sorted indexes already persisted)
 * - PUT    /api/uploads/{sessionId}/chunks/{index} -> ChunkUploadResponse
 *          body is the raw chunk bytes; Content-Type must be exactly
 *          application/octet-stream (the server rejects anything else)
 * - DELETE /api/uploads/{sessionId}                -> 204 No Content (abort)
 *
 * These endpoints only stage chunk bytes. Assembling the chunks into a file is
 * Phase 8.4 and is deliberately not called here.
 */
import { apiClient } from '@/api/client';
import { toApiError } from '@/api/errors';

/**
 * Request body for creating a session. Mirrors
 * com.minidrive.dto.upload.CreateUploadSessionRequest. The server chooses the
 * chunk size and derives the chunk count from totalSizeBytes, so the client
 * never dictates how the object is split.
 */
export interface CreateUploadSessionRequest {
  filename: string;
  contentType: string;
  totalSizeBytes: number;
  /** Optional destination folder for a brand-new file (omit for drive root). */
  folderId?: string;
  /** Optional existing file whose content this upload will replace. */
  targetFileId?: string;
}

/** Mirrors com.minidrive.dto.upload.UploadSessionResponse (201 body). */
export interface UploadSessionResponse {
  id: string;
  filename: string;
  contentType: string;
  folderId: string | null;
  targetFileId: string | null;
  totalSizeBytes: number;
  chunkSizeBytes: number;
  totalChunks: number;
  status: string;
  expiresAt: string;
  createdAt: string;
}

/**
 * Mirrors com.minidrive.dto.upload.UploadSessionStatusResponse. The status is
 * the resume source of truth: `uploadedChunks` lists every index the server has
 * durably persisted, so an interrupted client can skip them.
 */
export interface UploadSessionStatusResponse {
  id: string;
  filename: string;
  contentType: string;
  totalSizeBytes: number;
  chunkSizeBytes: number;
  totalChunks: number;
  status: string;
  uploadedChunks: number[];
  expiresAt: string;
}

/** Mirrors com.minidrive.dto.upload.ChunkUploadResponse. */
export interface ChunkUploadResponse {
  sessionId: string;
  chunkIndex: number;
  sizeBytes: number;
  /** true when this index was already stored and the request was an idempotent retry. */
  duplicate: boolean;
}

/**
 * Transport contract the resumable upload client depends on. Keeping the
 * client behind this interface makes the orchestration testable with a fake
 * transport and keeps it independent of Axios details.
 */
export interface ResumableUploadApi {
  createUploadSession(
    request: CreateUploadSessionRequest,
  ): Promise<UploadSessionResponse>;
  getUploadSessionStatus(
    sessionId: string,
  ): Promise<UploadSessionStatusResponse>;
  uploadChunk(
    sessionId: string,
    index: number,
    chunk: Blob,
    signal?: AbortSignal,
  ): Promise<ChunkUploadResponse>;
  abortUploadSession(sessionId: string): Promise<void>;
}

/** The exact media type the chunk endpoint consumes. */
export const CHUNK_CONTENT_TYPE = 'application/octet-stream';

/**
 * Initialize a chunked upload session from declared file metadata. The server
 * validates the name/size, picks the chunk size, and returns the session id and
 * the derived chunk count.
 */
export async function createUploadSession(
  request: CreateUploadSessionRequest,
): Promise<UploadSessionResponse> {
  try {
    const body: CreateUploadSessionRequest = {
      filename: request.filename,
      contentType: request.contentType,
      totalSizeBytes: request.totalSizeBytes,
      ...(request.folderId ? { folderId: request.folderId } : {}),
      ...(request.targetFileId ? { targetFileId: request.targetFileId } : {}),
    };
    const { data } = await apiClient.post<UploadSessionResponse>(
      '/api/uploads',
      body,
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/** Fetch the authoritative session status, including the persisted chunk indexes. */
export async function getUploadSessionStatus(
  sessionId: string,
): Promise<UploadSessionStatusResponse> {
  try {
    const { data } = await apiClient.get<UploadSessionStatusResponse>(
      `/api/uploads/${sessionId}`,
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/**
 * Upload a single chunk. The body is exactly one Blob slice and the
 * Content-Type is forced to application/octet-stream (the global client leaves
 * Content-Type unset, so it is set per request here). Axios sends a Blob body
 * verbatim without JSON-serialising it. The optional AbortSignal lets the
 * caller cancel an in-flight chunk.
 */
export async function uploadChunk(
  sessionId: string,
  index: number,
  chunk: Blob,
  signal?: AbortSignal,
): Promise<ChunkUploadResponse> {
  try {
    const { data } = await apiClient.put<ChunkUploadResponse>(
      `/api/uploads/${sessionId}/chunks/${index}`,
      chunk,
      {
        headers: { 'Content-Type': CHUNK_CONTENT_TYPE },
        ...(signal ? { signal } : {}),
      },
    );
    return data;
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/**
 * Abort a session and have the server delete all of its staging objects.
 * Idempotent on the backend, so calling it twice is safe.
 */
export async function abortUploadSession(sessionId: string): Promise<void> {
  try {
    await apiClient.delete(`/api/uploads/${sessionId}`);
  } catch (error: unknown) {
    throw toApiError(error);
  }
}

/** Build the Axios-backed transport used by the resumable upload client. */
export function createResumableUploadApi(): ResumableUploadApi {
  return {
    createUploadSession,
    getUploadSessionStatus,
    uploadChunk,
    abortUploadSession,
  };
}
