/**
 * Resumable chunked upload client (Phase 8.3/8.5).
 *
 * Drives the Phase 8.2 upload-session endpoints from the browser: create a
 * session, ask the server which chunks already landed, then upload only the
 * missing ones using Blob.slice() (the file is never loaded into memory whole).
 *
 * Once every chunk is staged the client calls the Phase 8.4 finalization
 * endpoint, so a run only reports `completed` after the backend has produced a
 * real file. Reaching 100% chunk progress is deliberately not success: a failed
 * finalization leaves the run `failed` while keeping the session (and its
 * persisted record) so it can be retried without re-uploading any bytes.
 *
 * Design notes
 * - The transport is injected (see ResumableUploadApi) so the orchestration is
 *   unit-testable with a fake server and decoupled from Axios.
 * - Retries are bounded and only applied to transient failures (no response,
 *   timeouts, 408/425/429, 5xx). Validation/authorization errors fail fast.
 * - After any ambiguous failed chunk request the client refreshes the server
 *   status before retrying, so a request that actually stored its bytes is
 *   detected instead of re-uploaded.
 * - Concurrency is bounded and duplicate in-flight requests for the same
 *   session+index are coalesced.
 */
import { toApiError } from '@/api/errors';
import type {
  ChunkUploadResponse,
  ResumableUploadApi,
  UploadSessionResponse,
  UploadSessionStatusResponse,
} from '@/api/uploads';
import type { ApiError } from '@/types/api';
import type { FileUploadResponse } from '@/api/files';
import {
  ResumeStore,
  isExpired,
  type ResumableUploadRecord,
} from '@/uploads/resumeStore';

/** Lifecycle phase reported to the UI. */
export type UploadPhase =
  | 'idle'
  | 'preparing'
  | 'uploading'
  | 'retrying'
  | 'paused'
  | 'cancelled'
  | 'finalizing'
  | 'completed'
  | 'failed';

/** Which stage a `failed` run failed at, so the UI can offer the right retry. */
export type UploadFailureStage = 'chunks' | 'finalize';

/** Immutable snapshot of the upload's state. */
export interface ResumableUploadState {
  phase: UploadPhase;
  sessionId: string | null;
  filename: string | null;
  totalBytes: number;
  totalChunks: number;
  uploadedBytes: number;
  uploadedChunks: number;
  progressPercent: number;
  /** Consecutive transient failures on the current chunk (0 outside a retry). */
  retryAttempt: number;
  error: ApiError | null;
  /** The finalized file once the backend completion succeeds, else null. */
  result: FileUploadResponse | null;
  /** Where a `failed` run stopped; null unless phase is `failed`. */
  failureStage: UploadFailureStage | null;
}

/** Where a new upload should land (or which file it replaces). */
export interface ResumableUploadTarget {
  folderId?: string;
  targetFileId?: string;
  /** External cancellation signal; aborting it cancels the run. */
  signal?: AbortSignal;
}

export interface ResumableUploadClientOptions {
  api: ResumableUploadApi;
  /** Persists resume metadata; defaults to a localStorage-backed store. */
  store?: ResumeStore;
  /** Maximum chunks uploaded at once (default 3). Always bounded >= 1. */
  concurrency?: number;
  /** Maximum attempts per chunk, including the first (default 5). */
  maxAttempts?: number;
  baseRetryDelayMs?: number;
  maxRetryDelayMs?: number;
  /** Injectable delay (tests pass a no-op). */
  delay?: (ms: number) => Promise<void>;
  now?: () => number;
  random?: () => number;
  onState?: (state: ResumableUploadState) => void;
}

export const DEFAULT_CONCURRENCY = 3;
export const DEFAULT_MAX_ATTEMPTS = 5;
export const DEFAULT_BASE_RETRY_DELAY_MS = 500;
export const DEFAULT_MAX_RETRY_DELAY_MS = 15_000;

/** Internal, server-authoritative description of the active session. */
interface ActiveSession {
  id: string;
  chunkSizeBytes: number;
  totalSizeBytes: number;
  totalChunks: number;
}

/** Chunk byte range [start, end) for one index of a split. */
export interface ChunkRange {
  start: number;
  end: number;
}

/**
 * Byte range of one chunk. Every chunk is a full chunk except the final one,
 * which absorbs the remainder; a zero-byte file has a single empty chunk.
 */
export function chunkRange(
  chunkSizeBytes: number,
  totalSizeBytes: number,
  totalChunks: number,
  index: number,
): ChunkRange {
  if (!Number.isInteger(index) || index < 0 || index >= totalChunks) {
    throw new RangeError(
      `Chunk index ${index} is out of range for ${totalChunks} chunks`,
    );
  }
  const start = index * chunkSizeBytes;
  const end =
    index === totalChunks - 1
      ? totalSizeBytes
      : Math.min(start + chunkSizeBytes, totalSizeBytes);
  return { start, end };
}

/** Exact byte count of one chunk (matches the backend's expected size). */
export function chunkSizeFor(
  chunkSizeBytes: number,
  totalSizeBytes: number,
  totalChunks: number,
  index: number,
): number {
  const { start, end } = chunkRange(
    chunkSizeBytes,
    totalSizeBytes,
    totalChunks,
    index,
  );
  return end - start;
}

/** Indexes [0, totalChunks) that the server has not confirmed yet. */
export function missingChunkIndexes(
  totalChunks: number,
  uploaded: Iterable<number>,
): number[] {
  const done = new Set(uploaded);
  const missing: number[] = [];
  for (let index = 0; index < totalChunks; index += 1) {
    if (!done.has(index)) missing.push(index);
  }
  return missing;
}

/** Aggregate bytes represented by a set of already-stored chunk indexes. */
export function computeUploadedBytes(
  chunkSizeBytes: number,
  totalSizeBytes: number,
  totalChunks: number,
  uploaded: Iterable<number>,
): number {
  let total = 0;
  for (const index of new Set(uploaded)) {
    if (index < 0 || index >= totalChunks) continue;
    total += chunkSizeFor(chunkSizeBytes, totalSizeBytes, totalChunks, index);
  }
  return total;
}

/** Progress percentage, guarding the zero-byte edge case. */
export function computeProgressPercent(
  uploadedBytes: number,
  totalBytes: number,
  totalChunks: number,
  uploadedCount: number,
): number {
  if (totalBytes <= 0) {
    return totalChunks > 0 && uploadedCount >= totalChunks ? 100 : 0;
  }
  return Math.min(100, Math.round((uploadedBytes / totalBytes) * 100));
}

/**
 * Whether a failed request is worth retrying. A missing status means the
 * request never reached a response (offline, timeout, connection reset) and is
 * treated as transient; explicit 4xx validation/authorization errors are not.
 */
export function isRetryableError(error: ApiError): boolean {
  if (error.status === undefined) return true;
  if (error.status === 408 || error.status === 425 || error.status === 429) {
    return true;
  }
  return error.status >= 500;
}

/**
 * Exponential backoff with equal jitter, capped at maxDelayMs. `attempt` is the
 * 1-based retry number.
 */
export function backoffDelay(
  attempt: number,
  baseDelayMs: number,
  maxDelayMs: number,
  random: () => number = Math.random,
): number {
  const exponential = baseDelayMs * 2 ** (Math.max(1, attempt) - 1);
  const capped = Math.min(maxDelayMs, exponential);
  const jittered = capped / 2 + random() * (capped / 2);
  return Math.round(jittered);
}

/** Content type to declare for a selected file (files may report an empty type). */
export function normalizeContentType(type: string): string {
  const base = type.split(';', 2)[0].trim();
  return base.length > 0 ? base : 'application/octet-stream';
}

/**
 * Validate that a reselected File corresponds to a stored resume record. A
 * browser refresh never preserves the original File object, so this guards
 * against resuming onto the wrong (or a mutated) file.
 */
export function validateResumeAssociation(
  file: File,
  record: ResumableUploadRecord,
): string | null {
  if (record.filename !== file.name) {
    return `The selected file "${file.name}" does not match the interrupted upload "${record.filename}".`;
  }
  if (record.sizeBytes !== file.size) {
    return 'The selected file size does not match the interrupted upload.';
  }
  const lastModified = positiveOrNull(file.lastModified);
  if (
    lastModified !== null &&
    record.lastModified !== null &&
    record.lastModified !== lastModified
  ) {
    return 'The selected file has changed since the interrupted upload.';
  }
  return null;
}

/** Fresh idle state (new object each call so callers never share a reference). */
export function createInitialUploadState(): ResumableUploadState {
  return {
    phase: 'idle',
    sessionId: null,
    filename: null,
    totalBytes: 0,
    totalChunks: 0,
    uploadedBytes: 0,
    uploadedChunks: 0,
    progressPercent: 0,
    retryAttempt: 0,
    error: null,
    result: null,
    failureStage: null,
  };
}

/**
 * Coalesces in-flight requests keyed by session + chunk index so two callers
 * within one client instance never issue duplicate uploads for the same chunk.
 */
export class ChunkRequestPool {
  private readonly inFlight = new Map<string, Promise<unknown>>();

  run<T>(
    sessionId: string,
    index: number,
    task: () => Promise<T>,
  ): Promise<T> {
    const key = `${sessionId}:${index}`;
    const existing = this.inFlight.get(key);
    if (existing !== undefined) return existing as Promise<T>;

    const promise = task().finally(() => {
      this.inFlight.delete(key);
    });
    this.inFlight.set(key, promise);
    return promise;
  }

  /** Number of requests currently in flight (exposed for diagnostics/tests). */
  get size(): number {
    return this.inFlight.size;
  }
}

/**
 * Reusable resumable upload client. One instance manages one in-flight upload
 * at a time; start/resume return the state instead of rejecting so the UI can
 * render a `failed` phase with a useful message.
 */
export class ResumableUploadClient {
  private readonly api: ResumableUploadApi;
  private readonly store: ResumeStore;
  private readonly concurrency: number;
  private readonly maxAttempts: number;
  private readonly baseRetryDelayMs: number;
  private readonly maxRetryDelayMs: number;
  private readonly delay: (ms: number) => Promise<void>;
  private readonly now: () => number;
  private readonly random: () => number;
  private readonly onState: ((state: ResumableUploadState) => void) | undefined;
  private readonly pool = new ChunkRequestPool();

  private state: ResumableUploadState = createInitialUploadState();
  private session: ActiveSession | null = null;
  private done = new Set<number>();
  private controller = new AbortController();
  private activeRun: Promise<ResumableUploadState> | null = null;

  private paused = false;
  private cancelled = false;
  private failed = false;

  constructor(options: ResumableUploadClientOptions) {
    this.api = options.api;
    this.store = options.store ?? new ResumeStore();
    this.concurrency = clampPositive(options.concurrency, DEFAULT_CONCURRENCY);
    this.maxAttempts = clampPositive(options.maxAttempts, DEFAULT_MAX_ATTEMPTS);
    this.baseRetryDelayMs =
      options.baseRetryDelayMs ?? DEFAULT_BASE_RETRY_DELAY_MS;
    this.maxRetryDelayMs =
      options.maxRetryDelayMs ?? DEFAULT_MAX_RETRY_DELAY_MS;
    this.delay =
      options.delay ??
      ((ms) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
    this.now = options.now ?? (() => Date.now());
    this.random = options.random ?? Math.random;
    this.onState = options.onState;
  }

  getState(): ResumableUploadState {
    return this.state;
  }

  /** Start a brand-new resumable upload for `file`. */
  start(
    file: File,
    target: ResumableUploadTarget = {},
  ): Promise<ResumableUploadState> {
    if (this.activeRun !== null) return this.activeRun;
    return this.track(this.startRun(file, target));
  }

  /** Resume a previously persisted session after validating the file. */
  resume(
    file: File,
    record: ResumableUploadRecord,
  ): Promise<ResumableUploadState> {
    if (this.activeRun !== null) return this.activeRun;
    return this.track(this.resumeRun(file, record));
  }

  /**
   * Resume the newest stored session that matches `file`'s identity (name and
   * size). Returns null when nothing plausible is stored.
   */
  resumeLatest(file: File): Promise<ResumableUploadState | null> {
    const record = this.findResumable(file);
    if (record === null) return Promise.resolve(null);
    return this.resume(file, record);
  }

  /** The stored resume record for `file`, if any (does not contact the server). */
  findResumable(file: File): ResumableUploadRecord | null {
    return this.store.findForFile(file, this.now());
  }

  /** All resumable sessions stored in the browser, newest first. */
  listResumable(): ResumableUploadRecord[] {
    return this.store.list(this.now());
  }

  /**
   * Retry finalization for a run whose chunks are all staged. Uses the same
   * session as the original run, so no chunk is re-uploaded; the backend's
   * completion endpoint is idempotent. No-op when there is no active session.
   */
  finalize(): Promise<ResumableUploadState> {
    if (this.activeRun !== null) return this.activeRun;
    const session = this.session;
    if (session === null) return Promise.resolve(this.state);
    this.paused = false;
    this.cancelled = false;
    this.failed = false;
    return this.track(this.finalizeRun(session));
  }

  /**
   * Stop scheduling new chunks while keeping the session (and its persisted
   * record) so it can be resumed later. In-flight chunks finish normally.
   */
  pause(): ResumableUploadState {
    if (
      this.state.phase === 'preparing' ||
      this.state.phase === 'uploading' ||
      this.state.phase === 'retrying'
    ) {
      this.paused = true;
      this.patch({ phase: 'paused', retryAttempt: 0 });
    }
    return this.state;
  }

  /**
   * Cancel the upload: abort in-flight requests, ask the server to discard all
   * staged objects, and drop the persisted record. Idempotent.
   */
  async cancel(): Promise<ResumableUploadState> {
    this.cancelled = true;
    this.paused = false;
    this.controller.abort();

    const sessionId = this.state.sessionId;
    if (sessionId !== null) {
      try {
        await this.api.abortUploadSession(sessionId);
      } catch {
        // Best-effort: the record is still cleared so the UI never offers a
        // session the server may have already removed.
      }
      this.store.remove(sessionId);
    }

    this.patch({
      phase: 'cancelled',
      retryAttempt: 0,
      error: null,
      failureStage: null,
    });
    return this.state;
  }

  /**
   * Abort a session discovered in the resume store and drop its record. Used
   * by the interrupted-uploads panel; best-effort on the server side.
   */
  async discard(record: ResumableUploadRecord): Promise<void> {
    try {
      await this.api.abortUploadSession(record.sessionId);
    } catch {
      // The session may already be gone; the record is cleared regardless.
    }
    this.store.remove(record.sessionId);
  }

  /** Abort any active run and return to the idle state. */
  reset(): void {
    this.cancelled = true;
    this.paused = false;
    this.failed = false;
    this.controller.abort();
    this.session = null;
    this.done = new Set();
    this.emit(createInitialUploadState());
  }

  // ------------------------------------------------------------------
  // Run orchestration
  // ------------------------------------------------------------------

  private track(
    run: Promise<ResumableUploadState>,
  ): Promise<ResumableUploadState> {
    this.activeRun = run;
    const clear = () => {
      if (this.activeRun === run) this.activeRun = null;
    };
    void run.then(clear, clear);
    return run;
  }

  private async startRun(
    file: File,
    target: ResumableUploadTarget,
  ): Promise<ResumableUploadState> {
    this.beginRun(file, target.signal);

    try {
      const response = await this.api.createUploadSession({
        filename: file.name,
        contentType: normalizeContentType(file.type),
        totalSizeBytes: file.size,
        ...(target.folderId ? { folderId: target.folderId } : {}),
        ...(target.targetFileId ? { targetFileId: target.targetFileId } : {}),
      });

      const session = toActiveSession(response);
      this.session = session;
      this.done = new Set();
      this.patch({ sessionId: session.id, totalChunks: session.totalChunks });
      this.persistRecord(
        file,
        session,
        {
          folderId: target.folderId ?? null,
          targetFileId: target.targetFileId ?? null,
        },
        response.expiresAt,
      );

      await this.uploadMissing(file, session);
    } catch (error) {
      this.fail(toApiError(error));
    }

    return this.state;
  }

  private async resumeRun(
    file: File,
    record: ResumableUploadRecord,
  ): Promise<ResumableUploadState> {
    this.beginRun(file, undefined);

    const mismatch = validateResumeAssociation(file, record);
    if (mismatch !== null) {
      this.fail({ message: mismatch });
      return this.state;
    }

    if (isExpired(record, this.now())) {
      this.store.remove(record.sessionId);
      this.fail({
        message:
          'This interrupted upload has expired. Please start the upload again.',
      });
      return this.state;
    }

    const session: ActiveSession = {
      id: record.sessionId,
      chunkSizeBytes: record.chunkSizeBytes,
      totalSizeBytes: record.sizeBytes,
      totalChunks: record.totalChunks,
    };
    this.session = session;
    this.patch({ sessionId: session.id, totalChunks: session.totalChunks });

    try {
      // Always reconcile with the server before uploading anything.
      const status = await this.api.getUploadSessionStatus(session.id);
      this.applyStatus(status, record);
      if (this.failed || this.cancelled) return this.state;

      await this.uploadMissing(file, session);
    } catch (error) {
      const apiError = toApiError(error);
      if (apiError.status === 404) {
        this.store.remove(session.id);
        this.fail({
          message:
            'This upload session is no longer available. Please start the upload again.',
          status: 404,
        });
      } else {
        this.fail(apiError);
      }
    }

    return this.state;
  }

  private beginRun(file: File, signal: AbortSignal | undefined): void {
    this.paused = false;
    this.cancelled = false;
    this.failed = false;
    this.session = null;
    this.done = new Set();
    this.controller = new AbortController();

    if (signal) {
      if (signal.aborted) {
        this.cancelled = true;
      } else {
        signal.addEventListener(
          'abort',
          () => {
            this.cancelled = true;
            this.controller.abort();
            this.patch({ phase: 'cancelled' });
          },
          { once: true },
        );
      }
    }

    this.patch({
      phase: 'preparing',
      sessionId: null,
      filename: file.name,
      totalBytes: file.size,
      totalChunks: 0,
      uploadedBytes: 0,
      uploadedChunks: 0,
      progressPercent: 0,
      retryAttempt: 0,
      error: null,
      result: null,
      failureStage: null,
    });
  }

  private async uploadMissing(
    file: File,
    session: ActiveSession,
  ): Promise<void> {
    const missing = missingChunkIndexes(session.totalChunks, this.done);

    if (missing.length > 0) {
      const cursor = { next: 0 };
      const workerCount = Math.max(
        1,
        Math.min(this.concurrency, missing.length),
      );
      const workers: Promise<void>[] = [];
      for (let worker = 0; worker < workerCount; worker += 1) {
        workers.push(this.worker(file, session, missing, cursor));
      }
      await Promise.all(workers);
    }

    if (this.shouldStop()) return;

    // Every chunk is staged: finalize into a real file. Reaching this point
    // with all chunks done (including ones stored before a resumption) is the
    // only path to `completed`.
    if (this.done.size >= session.totalChunks) {
      this.recomputeProgress();
      await this.runCompletion(session);
    }
  }

  /**
   * Call the completion endpoint and adopt its result. On success the resume
   * record is cleared (the session is no longer resumable) and the run becomes
   * `completed`; on failure the session is kept so finalization can be retried
   * from `finalize()` without re-uploading anything.
   */
  private async runCompletion(session: ActiveSession): Promise<void> {
    this.patch({
      phase: 'finalizing',
      retryAttempt: 0,
      error: null,
      failureStage: null,
    });

    try {
      const file = await this.api.completeUploadSession(session.id);
      this.store.remove(session.id);
      this.done = new Set(range(session.totalChunks));
      this.recomputeProgress();
      this.patch({ phase: 'completed', result: file, failureStage: null });
    } catch (error) {
      const apiError = toApiError(error);
      if (apiError.status === 404) {
        // The session (or its file) is gone, so the record is not resumable.
        this.store.remove(session.id);
      }
      this.fail(apiError, 'finalize');
    }
  }

  private async finalizeRun(
    session: ActiveSession,
  ): Promise<ResumableUploadState> {
    await this.runCompletion(session);
    return this.state;
  }

  private async worker(
    file: File,
    session: ActiveSession,
    queue: number[],
    cursor: { next: number },
  ): Promise<void> {
    while (!this.shouldStop()) {
      const position = cursor.next;
      cursor.next += 1;
      if (position >= queue.length) return;

      await this.uploadChunkWithRetry(file, session, queue[position]);
      if (this.failed) return;
    }
  }

  /**
   * Upload one chunk with bounded retries. On a transient failure the server
   * status is refreshed first: if the index already landed (the request may
   * have succeeded before the connection dropped) it is treated as done.
   */
  private async uploadChunkWithRetry(
    file: File,
    session: ActiveSession,
    index: number,
  ): Promise<void> {
    let attempt = 0;

    for (;;) {
      if (this.shouldStop()) return;

      try {
        this.patch({ phase: 'uploading', retryAttempt: 0 });
        const blob = sliceChunk(file, session, index);
        const result = await this.pool.run(session.id, index, () =>
          this.api.uploadChunk(session.id, index, blob, this.controller.signal),
        );
        this.markDone(result, session, index);
        return;
      } catch (error) {
        if (this.cancelled || this.controller.signal.aborted) return;

        const apiError = toApiError(error);

        if (!isRetryableError(apiError)) {
          this.fail(apiError);
          return;
        }

        // Ambiguous/transient: reconcile with the server before retrying.
        const reconciled = await this.reconcile(session, index);
        if (reconciled) return;

        attempt += 1;
        if (attempt >= this.maxAttempts) {
          this.fail({
            message: `Upload failed after ${attempt} attempts: ${apiError.message}`,
            ...(apiError.status !== undefined ? { status: apiError.status } : {}),
          });
          return;
        }

        this.patch({ phase: 'retrying', retryAttempt: attempt });
        await this.delay(
          backoffDelay(
            attempt,
            this.baseRetryDelayMs,
            this.maxRetryDelayMs,
            this.random,
          ),
        );
      }
    }
  }

  /**
   * Refresh server status after an ambiguous chunk failure. Returns true when
   * the caller should stop handling this chunk (it is stored, or the session is
   * terminally gone).
   */
  private async reconcile(
    session: ActiveSession,
    index: number,
  ): Promise<boolean> {
    try {
      const status = await this.api.getUploadSessionStatus(session.id);
      this.applyStatus(status, null);
      if (this.failed || this.cancelled) return true;
      return this.done.has(index);
    } catch (error) {
      const apiError = toApiError(error);
      if (
        apiError.status === 401 ||
        apiError.status === 403 ||
        apiError.status === 404
      ) {
        this.store.remove(session.id);
        this.fail(
          apiError.status === 404
            ? {
                message:
                  'This upload session is no longer available. Please start the upload again.',
                status: 404,
              }
            : apiError,
        );
        return true;
      }
      // The refresh itself failed transiently; let the retry loop continue.
      return false;
    }
  }

  /** Adopt the server's authoritative status (chunk map, split, lifecycle). */
  private applyStatus(
    status: UploadSessionStatusResponse,
    record: ResumableUploadRecord | null,
  ): void {
    if (status.status === 'ABORTED') {
      this.store.remove(status.id);
      this.fail({ message: 'This upload was cancelled on the server.' });
      return;
    }
    if (status.status === 'EXPIRED' || isExpiredAt(status.expiresAt, this.now())) {
      this.store.remove(status.id);
      this.fail({
        message:
          'This upload session has expired. Please start the upload again.',
      });
      return;
    }

    const valid = status.uploadedChunks.filter(
      (index) => Number.isInteger(index) && index >= 0 && index < status.totalChunks,
    );
    this.done =
      status.status === 'COMPLETED'
        ? new Set(range(status.totalChunks))
        : new Set(valid);

    this.session = {
      id: status.id,
      chunkSizeBytes: status.chunkSizeBytes,
      totalSizeBytes: status.totalSizeBytes,
      totalChunks: status.totalChunks,
    };
    this.patch({ sessionId: status.id, totalChunks: status.totalChunks });
    this.recomputeProgress();

    // Keep the persisted record aligned with the server's metadata.
    if (record !== null) {
      this.persistRecordWithStatus(record, status);
    }
  }

  private markDone(
    result: ChunkUploadResponse,
    session: ActiveSession,
    index: number,
  ): void {
    if (result.duplicate || result.chunkIndex === index) {
      this.done.add(index);
    } else {
      this.done.add(result.chunkIndex);
    }
    if (this.session === null) this.session = session;
    this.recomputeProgress();
  }

  private recomputeProgress(): void {
    const session = this.session;
    if (session === null) return;
    const uploadedBytes = computeUploadedBytes(
      session.chunkSizeBytes,
      session.totalSizeBytes,
      session.totalChunks,
      this.done,
    );
    this.patch({
      uploadedBytes,
      uploadedChunks: this.done.size,
      progressPercent: computeProgressPercent(
        uploadedBytes,
        this.state.totalBytes,
        session.totalChunks,
        this.done.size,
      ),
    });
  }

  private persistRecord(
    file: File,
    session: ActiveSession,
    destination: { folderId: string | null; targetFileId: string | null },
    expiresAt: string,
  ): void {
    this.store.save({
      sessionId: session.id,
      filename: file.name,
      contentType: normalizeContentType(file.type),
      sizeBytes: file.size,
      folderId: destination.folderId,
      targetFileId: destination.targetFileId,
      chunkSizeBytes: session.chunkSizeBytes,
      totalChunks: session.totalChunks,
      expiresAt,
      lastModified: positiveOrNull(file.lastModified),
      savedAt: new Date(this.now()).toISOString(),
    });
  }

  private persistRecordWithStatus(
    record: ResumableUploadRecord,
    status: UploadSessionStatusResponse,
  ): void {
    this.store.save({
      ...record,
      chunkSizeBytes: status.chunkSizeBytes,
      totalChunks: status.totalChunks,
      expiresAt: status.expiresAt,
      savedAt: new Date(this.now()).toISOString(),
    });
  }

  private shouldStop(): boolean {
    return this.failed || this.paused || this.cancelled;
  }

  private fail(error: ApiError, stage: UploadFailureStage = 'chunks'): void {
    this.failed = true;
    this.patch({
      phase: 'failed',
      error,
      retryAttempt: 0,
      failureStage: stage,
    });
  }

  private patch(partial: Partial<ResumableUploadState>): void {
    this.emit({ ...this.state, ...partial });
  }

  private emit(state: ResumableUploadState): void {
    this.state = state;
    this.onState?.(state);
  }
}

// ----------------------------------------------------------------------------
// Helpers
// ----------------------------------------------------------------------------

function sliceChunk(
  file: File,
  session: ActiveSession,
  index: number,
): Blob {
  const { start, end } = chunkRange(
    session.chunkSizeBytes,
    session.totalSizeBytes,
    session.totalChunks,
    index,
  );
  // Blob.slice() produces a lazy view; the whole file is never read into memory.
  return file.slice(start, end);
}

function toActiveSession(response: UploadSessionResponse): ActiveSession {
  return {
    id: response.id,
    chunkSizeBytes: response.chunkSizeBytes,
    totalSizeBytes: response.totalSizeBytes,
    totalChunks: response.totalChunks,
  };
}

function isExpiredAt(expiresAt: string, now: number): boolean {
  const parsed = Date.parse(expiresAt);
  return Number.isFinite(parsed) && parsed <= now;
}

function positiveOrNull(value: number): number | null {
  return Number.isFinite(value) && value > 0 ? value : null;
}

function clampPositive(value: number | undefined, fallback: number): number {
  if (value === undefined || !Number.isFinite(value) || value < 1) {
    return fallback;
  }
  return Math.floor(value);
}

function range(size: number): number[] {
  const result: number[] = [];
  for (let index = 0; index < size; index += 1) result.push(index);
  return result;
}
