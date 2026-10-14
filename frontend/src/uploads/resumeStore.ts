/**
 * Browser persistence for in-progress resumable uploads (Phase 8.3).
 *
 * Only the minimum metadata needed to find and resume an existing upload
 * session is stored: the session id, the declared file identity (name, size,
 * last-modified), the destination (folder / replacement target), the server's
 * chunk split and the expiry. Authentication tokens and file contents are
 * deliberately never persisted here.
 *
 * A stored record lets the UI offer "resume" after a refresh or a crash. A
 * browser refresh does not preserve the original File object, so the resume
 * flow requires the user to reselect the file and validates it against this
 * metadata before contacting the server.
 */

/** localStorage-shaped interface so the store is testable without a DOM. */
export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

/** Minimal persisted record; everything else is re-fetched from the server. */
export interface ResumableUploadRecord {
  sessionId: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  folderId: string | null;
  targetFileId: string | null;
  chunkSizeBytes: number;
  totalChunks: number;
  expiresAt: string;
  /** File.lastModified when available; used to detect a changed file. */
  lastModified: number | null;
  savedAt: string;
}

const STORAGE_KEY = 'minidrive_resumable_uploads';

/** Resolve localStorage when it is usable, otherwise null (SSR / private mode). */
function defaultStorage(): StorageLike | null {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      return window.localStorage;
    }
  } catch {
    // Accessing localStorage can throw (e.g. blocked third-party storage).
  }
  return null;
}

/**
 * Stores resumable upload records under a single key. All access is defensive:
 * unavailable or corrupted storage degrades to "no resumable upload" instead of
 * throwing, and quota / serialization errors are swallowed.
 */
export class ResumeStore {
  private readonly storage: StorageLike | null;

  constructor(storage: StorageLike | null = defaultStorage()) {
    this.storage = storage;
  }

  private readAll(): Record<string, ResumableUploadRecord> {
    if (this.storage === null) return {};
    try {
      const raw = this.storage.getItem(STORAGE_KEY);
      if (!raw) return {};
      const parsed: unknown = JSON.parse(raw);
      if (typeof parsed !== 'object' || parsed === null) return {};
      const entries = Object.entries(parsed as Record<string, unknown>).filter(
        (entry): entry is [string, ResumableUploadRecord] =>
          isRecord(entry[1]),
      );
      return Object.fromEntries(entries);
    } catch {
      return {};
    }
  }

  private writeAll(records: Record<string, ResumableUploadRecord>): void {
    if (this.storage === null) return;
    try {
      if (Object.keys(records).length === 0) {
        this.storage.removeItem(STORAGE_KEY);
      } else {
        this.storage.setItem(STORAGE_KEY, JSON.stringify(records));
      }
    } catch {
      // Storage full or blocked; resumability is best-effort.
    }
  }

  /** All non-expired records, newest first. Expired/stale records are pruned. */
  list(now: number = Date.now()): ResumableUploadRecord[] {
    const all = this.readAll();
    const live = Object.values(all).filter(
      (record) => !isExpired(record, now),
    );
    if (live.length !== Object.keys(all).length) {
      this.writeAll(
        Object.fromEntries(live.map((record) => [record.sessionId, record])),
      );
    }
    return live.sort((a, b) => b.savedAt.localeCompare(a.savedAt));
  }

  get(sessionId: string): ResumableUploadRecord | null {
    return this.readAll()[sessionId] ?? null;
  }

  /** Persist a record, replacing any previous record for the same session. */
  save(record: ResumableUploadRecord): void {
    const all = this.readAll();
    all[record.sessionId] = record;
    this.writeAll(all);
  }

  remove(sessionId: string): void {
    const all = this.readAll();
    if (all[sessionId] === undefined) return;
    delete all[sessionId];
    this.writeAll(all);
  }

  clear(): void {
    this.writeAll({});
  }

  /**
   * Find the newest resumable record that could belong to the given file,
   * matching on declared name and size (the reliable identity available
   * without reading the file). lastModified is preferred when both sides have
   * it. Returns null when nothing plausible is stored.
   */
  findForFile(file: File, now: number = Date.now()): ResumableUploadRecord | null {
    const candidates = this.list(now).filter(
      (record) =>
        record.filename === file.name && record.sizeBytes === file.size,
    );
    if (candidates.length === 0) return null;

    const lastModified = positiveOrNull(file.lastModified);
    if (lastModified !== null) {
      const exact = candidates.find(
        (record) => record.lastModified === lastModified,
      );
      if (exact) return exact;
    }
    return candidates[0];
  }
}

/** Whether a record's saved expiry has already passed. */
export function isExpired(
  record: ResumableUploadRecord,
  now: number = Date.now(),
): boolean {
  const expiresAt = Date.parse(record.expiresAt);
  return Number.isFinite(expiresAt) && expiresAt <= now;
}

function positiveOrNull(value: number): number | null {
  return Number.isFinite(value) && value > 0 ? value : null;
}

function isRecord(value: unknown): value is ResumableUploadRecord {
  if (typeof value !== 'object' || value === null) return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record.sessionId === 'string' &&
    typeof record.filename === 'string' &&
    typeof record.sizeBytes === 'number' &&
    typeof record.chunkSizeBytes === 'number' &&
    typeof record.totalChunks === 'number' &&
    typeof record.expiresAt === 'string'
  );
}
