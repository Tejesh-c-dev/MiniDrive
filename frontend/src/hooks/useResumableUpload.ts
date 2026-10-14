/**
 * Reusable React interface for the resumable chunked upload client (Phase 8.3).
 *
 * Exposes the current {@link ResumableUploadState} (preparing / uploading /
 * retrying / paused / cancelled / ready / failed) plus controls to start,
 * resume, pause, cancel and reset an upload. It is intentionally not wired into
 * DrivePage: the existing single-shot upload flow stays unchanged, and a run
 * that reaches `ready` only means every chunk is staged -- file creation is
 * Phase 8.4.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { createResumableUploadApi } from '@/api/uploads';
import { ResumeStore, type ResumableUploadRecord } from '@/uploads/resumeStore';
import {
  ResumableUploadClient,
  createInitialUploadState,
  type ResumableUploadState,
  type ResumableUploadTarget,
} from '@/uploads/resumableUpload';

export interface UseResumableUploadResult {
  state: ResumableUploadState;
  /** Create a session for a new file and upload its chunks. */
  start(
    file: File,
    target?: ResumableUploadTarget,
  ): Promise<ResumableUploadState>;
  /** Resume a known session after the user re-selects the original file. */
  resume(
    file: File,
    record: ResumableUploadRecord,
  ): Promise<ResumableUploadState>;
  /** Resume the newest stored session matching the file, or null if none. */
  resumeLatest(file: File): Promise<ResumableUploadState | null>;
  /** Whether an interrupted session is stored for this file (no network call). */
  findResumable(file: File): ResumableUploadRecord | null;
  /** Stop scheduling chunks but keep the session resumable. */
  pause(): void;
  /** Abort the session on the server and drop the stored record. */
  cancel(): Promise<void>;
  /** Return the hook to its idle state (aborting any active run). */
  reset(): void;
}

export function useResumableUpload(): UseResumableUploadResult {
  const mountedRef = useRef(true);
  const [state, setState] = useState<ResumableUploadState>(
    createInitialUploadState,
  );
  const clientRef = useRef<ResumableUploadClient | null>(null);

  if (clientRef.current === null) {
    clientRef.current = new ResumableUploadClient({
      api: createResumableUploadApi(),
      store: new ResumeStore(),
      onState: (next) => {
        if (mountedRef.current) setState(next);
      },
    });
  }
  const client = clientRef.current;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const start = useCallback(
    (file: File, target?: ResumableUploadTarget) => client.start(file, target),
    [client],
  );

  const resume = useCallback(
    (file: File, record: ResumableUploadRecord) => client.resume(file, record),
    [client],
  );

  const resumeLatest = useCallback(
    (file: File) => client.resumeLatest(file),
    [client],
  );

  const findResumable = useCallback(
    (file: File) => client.findResumable(file),
    [client],
  );

  const pause = useCallback(() => {
    client.pause();
  }, [client]);

  const cancel = useCallback(async () => {
    await client.cancel();
  }, [client]);

  const reset = useCallback(() => {
    client.reset();
  }, [client]);

  return {
    state,
    start,
    resume,
    resumeLatest,
    findResumable,
    pause,
    cancel,
    reset,
  };
}
