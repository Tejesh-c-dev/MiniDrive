/**
 * Shared fixtures for the resumable-upload unit tests. Not a test file itself
 * (the vitest include glob only picks up *.test.ts).
 */
import type {
  ChunkUploadResponse,
  CreateUploadSessionRequest,
  ResumableUploadApi,
  UploadSessionResponse,
  UploadSessionStatusResponse,
} from '@/api/uploads';
import {
  ResumeStore,
  type ResumableUploadRecord,
  type StorageLike,
} from '@/uploads/resumeStore';
import {
  ResumableUploadClient,
  type ResumableUploadClientOptions,
  type ResumableUploadState,
} from '@/uploads/resumableUpload';

/** Build an object that axios.isAxiosError() recognises, with a response. */
export function axiosError(status: number, message?: string): unknown {
  return {
    isAxiosError: true,
    response: {
      status,
      data: message !== undefined ? { error: message } : undefined,
    },
  };
}

/** Build an axios-like error with no response (offline / timeout / reset). */
export function networkError(): unknown {
  return { isAxiosError: true, request: {}, message: 'Network Error' };
}

/** In-memory Storage shaped like localStorage. */
export function memoryStorage(): StorageLike {
  const map = new Map<string, string>();
  return {
    getItem: (key: string) => map.get(key) ?? null,
    setItem: (key: string, value: string) => {
      map.set(key, value);
    },
    removeItem: (key: string) => {
      map.delete(key);
    },
  };
}

export function makeFile(
  name: string,
  size: number,
  type = 'application/octet-stream',
  lastModified = 1000,
): File {
  return new File([new Uint8Array(size)], name, { type, lastModified });
}

export function makeRecord(
  overrides: Partial<ResumableUploadRecord> = {},
): ResumableUploadRecord {
  return {
    sessionId: 'session-1',
    filename: 'file.bin',
    contentType: 'application/octet-stream',
    sizeBytes: 350,
    folderId: null,
    targetFileId: null,
    chunkSizeBytes: 100,
    totalChunks: 4,
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    lastModified: 1000,
    savedAt: new Date().toISOString(),
    ...overrides,
  };
}

export type ChunkHook = (
  index: number,
  chunk: Blob,
  call: number,
  signal: AbortSignal | undefined,
) => void | Promise<void>;

/**
 * Scriptable in-memory implementation of the upload transport. `uploaded`
 * tracks the server's persisted chunks; `onChunk` can block, mutate server
 * state before throwing, or fail to simulate network ambiguity.
 */
export class FakeUploadApi implements ResumableUploadApi {
  readonly sessionId = 'session-1';
  chunkSizeBytes = 100;
  totalSizeBytes = 350;
  totalChunks = 4;
  status = 'INITIATED';
  expiresAt = new Date(Date.now() + 3_600_000).toISOString();
  uploaded = new Set<number>();

  createCalls = 0;
  statusCalls = 0;
  abortCalls = 0;
  chunkCalls: number[] = [];

  createError: unknown = null;
  statusError: unknown = null;
  onChunk: ChunkHook | null = null;

  async createUploadSession(
    request: CreateUploadSessionRequest,
  ): Promise<UploadSessionResponse> {
    this.createCalls += 1;
    if (this.createError) throw this.createError;
    return {
      id: this.sessionId,
      filename: request.filename,
      contentType: request.contentType,
      folderId: request.folderId ?? null,
      targetFileId: request.targetFileId ?? null,
      totalSizeBytes: this.totalSizeBytes,
      chunkSizeBytes: this.chunkSizeBytes,
      totalChunks: this.totalChunks,
      status: this.status,
      expiresAt: this.expiresAt,
      createdAt: new Date().toISOString(),
    };
  }

  async getUploadSessionStatus(
    sessionId: string,
  ): Promise<UploadSessionStatusResponse> {
    this.statusCalls += 1;
    if (this.statusError) throw this.statusError;
    return {
      id: sessionId,
      filename: 'file.bin',
      contentType: 'application/octet-stream',
      totalSizeBytes: this.totalSizeBytes,
      chunkSizeBytes: this.chunkSizeBytes,
      totalChunks: this.totalChunks,
      status: this.status,
      uploadedChunks: [...this.uploaded].sort((a, b) => a - b),
      expiresAt: this.expiresAt,
    };
  }

  async uploadChunk(
    sessionId: string,
    index: number,
    chunk: Blob,
    signal?: AbortSignal,
  ): Promise<ChunkUploadResponse> {
    const call = this.chunkCalls.filter((value) => value === index).length + 1;
    this.chunkCalls.push(index);
    if (this.onChunk) {
      await this.onChunk(index, chunk, call, signal);
    }
    const duplicate = this.uploaded.has(index);
    this.uploaded.add(index);
    return { sessionId, chunkIndex: index, sizeBytes: chunk.size, duplicate };
  }

  async abortUploadSession(): Promise<void> {
    this.abortCalls += 1;
    this.status = 'ABORTED';
  }
}

export interface TestClient {
  client: ResumableUploadClient;
  states: ResumableUploadState[];
  delays: number[];
  store: ResumeStore;
}

/** Build a client wired to a fake API with deterministic timing defaults. */
export function makeClient(
  api: ResumableUploadApi,
  overrides: Partial<ResumableUploadClientOptions> = {},
): TestClient {
  const states: ResumableUploadState[] = [];
  const delays: number[] = [];
  const store = overrides.store ?? new ResumeStore(memoryStorage());
  const client = new ResumableUploadClient({
    concurrency: 1,
    maxAttempts: 3,
    random: () => 0,
    delay: async (ms: number) => {
      delays.push(ms);
    },
    onState: (state) => {
      states.push(state);
    },
    ...overrides,
    api,
    store,
  });
  return { client, states, delays, store };
}
