import { describe, expect, it } from 'vitest';
import {
  ChunkRequestPool,
  backoffDelay,
  chunkRange,
  chunkSizeFor,
  computeProgressPercent,
  computeUploadedBytes,
  isRetryableError,
  missingChunkIndexes,
  validateResumeAssociation,
} from '@/uploads/resumableUpload';
import { ResumeStore } from '@/uploads/resumeStore';
import {
  FakeUploadApi,
  axiosError,
  makeClient,
  makeFile,
  makeRecord,
  memoryStorage,
  networkError,
} from './__tests__/testSupport';

describe('chunk math', () => {
  it('computes full-chunk boundaries and a final partial chunk', () => {
    expect(chunkRange(100, 350, 4, 0)).toEqual({ start: 0, end: 100 });
    expect(chunkRange(100, 350, 4, 1)).toEqual({ start: 100, end: 200 });
    expect(chunkRange(100, 350, 4, 2)).toEqual({ start: 200, end: 300 });
    expect(chunkRange(100, 350, 4, 3)).toEqual({ start: 300, end: 350 });
    expect(chunkSizeFor(100, 350, 4, 3)).toBe(50);
  });

  it('treats a zero-byte upload as a single empty chunk', () => {
    expect(chunkRange(5 * 1024 * 1024, 0, 1, 0)).toEqual({ start: 0, end: 0 });
    expect(chunkSizeFor(5 * 1024 * 1024, 0, 1, 0)).toBe(0);
  });

  it('rejects out-of-range indexes', () => {
    expect(() => chunkRange(100, 350, 4, 4)).toThrow(RangeError);
    expect(() => chunkRange(100, 350, 4, -1)).toThrow(RangeError);
  });

  it('lists missing indexes and aggregates already-stored bytes', () => {
    expect(missingChunkIndexes(4, [0, 2])).toEqual([1, 3]);
    expect(missingChunkIndexes(3, [])).toEqual([0, 1, 2]);
    expect(computeUploadedBytes(100, 350, 4, [0, 2])).toBe(200);
    expect(computeUploadedBytes(100, 350, 4, [0, 1, 2, 3])).toBe(350);
  });

  it('computes progress including chunks uploaded before the current run', () => {
    expect(computeProgressPercent(200, 350, 4, 2)).toBe(57);
    expect(computeProgressPercent(350, 350, 4, 4)).toBe(100);
    expect(computeProgressPercent(0, 0, 1, 0)).toBe(0);
    expect(computeProgressPercent(0, 0, 1, 1)).toBe(100);
  });
});

describe('isRetryableError', () => {
  it('retries transport, timeout, rate-limit and 5xx failures', () => {
    expect(isRetryableError({ message: 'offline' })).toBe(true);
    expect(isRetryableError({ message: 'timeout', status: 408 })).toBe(true);
    expect(isRetryableError({ message: 'early', status: 425 })).toBe(true);
    expect(isRetryableError({ message: 'slow down', status: 429 })).toBe(true);
    expect(isRetryableError({ message: 'boom', status: 500 })).toBe(true);
    expect(isRetryableError({ message: 'bad gateway', status: 502 })).toBe(true);
  });

  it('does not retry validation, authorization or missing-session errors', () => {
    expect(isRetryableError({ message: 'bad', status: 400 })).toBe(false);
    expect(isRetryableError({ message: 'auth', status: 401 })).toBe(false);
    expect(isRetryableError({ message: 'forbidden', status: 403 })).toBe(false);
    expect(isRetryableError({ message: 'gone', status: 404 })).toBe(false);
    expect(isRetryableError({ message: 'conflict', status: 409 })).toBe(false);
  });
});

describe('backoffDelay', () => {
  it('grows exponentially and is capped at the maximum', () => {
    expect(backoffDelay(1, 500, 15_000, () => 1)).toBe(500);
    expect(backoffDelay(2, 500, 15_000, () => 1)).toBe(1000);
    expect(backoffDelay(1, 500, 15_000, () => 0)).toBe(250);
    expect(backoffDelay(20, 500, 15_000, () => 1)).toBe(15_000);
  });
});

describe('validateResumeAssociation', () => {
  it('accepts a matching file and rejects name/size/lastModified mismatches', () => {
    const record = makeRecord();
    expect(validateResumeAssociation(makeFile('file.bin', 350), record)).toBeNull();
    expect(
      validateResumeAssociation(makeFile('other.bin', 350), record),
    ).toMatch(/does not match/i);
    expect(validateResumeAssociation(makeFile('file.bin', 400), record)).toMatch(
      /size/i,
    );
    expect(
      validateResumeAssociation(makeFile('file.bin', 350, 'application/octet-stream', 999), record),
    ).toMatch(/changed/i);
  });
});

describe('ChunkRequestPool', () => {
  it('coalesces concurrent requests for the same session and chunk', async () => {
    const pool = new ChunkRequestPool();
    let calls = 0;
    const task = () => {
      calls += 1;
      return new Promise<string>((resolve) => {
        setTimeout(() => resolve('ok'), 5);
      });
    };

    const [first, second] = await Promise.all([
      pool.run('s', 0, task),
      pool.run('s', 0, task),
    ]);

    expect(calls).toBe(1);
    expect(first).toBe('ok');
    expect(second).toBe('ok');
    expect(pool.size).toBe(0);
  });

  it('runs different indexes independently and allows a later repeat', async () => {
    const pool = new ChunkRequestPool();
    let calls = 0;
    const task = async () => {
      calls += 1;
      return calls;
    };

    await pool.run('s', 0, task);
    await pool.run('s', 0, task);
    await pool.run('s', 1, task);

    expect(calls).toBe(3);
    expect(pool.size).toBe(0);
  });
});

describe('ResumableUploadClient', () => {
  it('uploads only the chunks the server has not confirmed', async () => {
    const api = new FakeUploadApi();
    api.uploaded = new Set([0, 2]);
    const { client, states } = makeClient(api);

    const state = await client.resume(makeFile('file.bin', 350), makeRecord());

    expect(api.chunkCalls).toEqual([1, 3]);
    expect(state.phase).toBe('completed');
    expect(state.uploadedBytes).toBe(350);
    expect(state.progressPercent).toBe(100);
    expect(api.completeCalls).toBe(1);
    expect(state.result?.id).toBe('file-1');
    // Progress accounted for the 200 bytes stored before this run.
    expect(
      states.some(
        (snapshot) =>
          snapshot.uploadedBytes === 200 && snapshot.progressPercent === 57,
      ),
    ).toBe(true);
  });

  it('reports ready without uploading when the server already has every chunk', async () => {
    const api = new FakeUploadApi();
    api.uploaded = new Set([0, 1, 2, 3]);
    const { client } = makeClient(api);

    const state = await client.resume(makeFile('file.bin', 350), makeRecord());

    expect(api.chunkCalls).toEqual([]);
    expect(state.phase).toBe('completed');
    expect(state.progressPercent).toBe(100);
    expect(api.completeCalls).toBe(1);
  });

  it('retries a transiently failing chunk and then completes', async () => {
    const api = new FakeUploadApi();
    api.totalSizeBytes = 250;
    api.totalChunks = 3;
    api.onChunk = (index, _chunk, call) => {
      if (index === 1 && call < 3) throw networkError();
    };
    const { client, delays } = makeClient(api);

    const state = await client.start(makeFile('file.bin', 250));

    expect(api.chunkCalls.filter((index) => index === 1)).toHaveLength(3);
    expect(delays).toHaveLength(2);
    expect(state.phase).toBe('completed');
  });

  it('refreshes status after an ambiguous result instead of re-uploading', async () => {
    const api = new FakeUploadApi();
    api.totalSizeBytes = 200;
    api.totalChunks = 2;
    api.onChunk = (index, _chunk, call) => {
      // The server stored the chunk but the response was lost.
      if (index === 0 && call === 1) {
        api.uploaded.add(0);
        throw networkError();
      }
    };
    const { client } = makeClient(api);

    const state = await client.start(makeFile('file.bin', 200));

    expect(api.chunkCalls.filter((index) => index === 0)).toHaveLength(1);
    expect(api.statusCalls).toBeGreaterThanOrEqual(1);
    expect(state.phase).toBe('completed');
    expect(state.uploadedBytes).toBe(200);
  });

  it('fails fast on permanent validation errors without retrying', async () => {
    const api = new FakeUploadApi();
    api.totalSizeBytes = 100;
    api.totalChunks = 1;
    api.onChunk = () => {
      throw axiosError(400, 'Chunk size does not match');
    };
    const { client, delays } = makeClient(api);

    const state = await client.start(makeFile('file.bin', 100));

    expect(api.chunkCalls).toHaveLength(1);
    expect(delays).toHaveLength(0);
    expect(state.phase).toBe('failed');
    expect(state.error?.message).toBe('Chunk size does not match');
  });

  it('stops after the bounded retry budget for a persistently failing chunk', async () => {
    const api = new FakeUploadApi();
    api.totalSizeBytes = 100;
    api.totalChunks = 1;
    api.onChunk = () => {
      throw networkError();
    };
    const { client, delays } = makeClient(api, { maxAttempts: 3 });

    const state = await client.start(makeFile('file.bin', 100));

    expect(api.chunkCalls).toHaveLength(3);
    expect(delays).toHaveLength(2);
    expect(state.phase).toBe('failed');
    expect(state.error?.message).toMatch(/after 3 attempts/i);
  });

  it('fails safely when the stored session has expired', async () => {
    const api = new FakeUploadApi();
    const store = new ResumeStore(memoryStorage());
    const record = makeRecord({
      expiresAt: new Date(Date.now() - 1000).toISOString(),
    });
    store.save(record);
    const { client } = makeClient(api, { store });

    const state = await client.resume(makeFile('file.bin', 350), record);

    expect(state.phase).toBe('failed');
    expect(state.error?.message).toMatch(/expired/i);
    expect(api.statusCalls).toBe(0);
    expect(store.get(record.sessionId)).toBeNull();
  });

  it('rejects a file that does not match the stored session', async () => {
    const api = new FakeUploadApi();
    const { client } = makeClient(api);

    const state = await client.resume(
      makeFile('file.bin', 350),
      makeRecord({ filename: 'other.bin' }),
    );

    expect(state.phase).toBe('failed');
    expect(state.error?.message).toMatch(/does not match/i);
    expect(api.statusCalls).toBe(0);
    expect(api.chunkCalls).toEqual([]);
  });

  it('clears the record when the server no longer knows the session', async () => {
    const api = new FakeUploadApi();
    api.statusError = axiosError(404, 'Upload session not found');
    const store = new ResumeStore(memoryStorage());
    const record = makeRecord();
    store.save(record);
    const { client } = makeClient(api, { store });

    const state = await client.resume(makeFile('file.bin', 350), record);

    expect(state.phase).toBe('failed');
    expect(state.error?.message).toMatch(/no longer available/i);
    expect(store.get(record.sessionId)).toBeNull();
  });

  it('fails safely when the server reports the session as aborted', async () => {
    const api = new FakeUploadApi();
    api.status = 'ABORTED';
    const store = new ResumeStore(memoryStorage());
    const record = makeRecord();
    store.save(record);
    const { client } = makeClient(api, { store });

    const state = await client.resume(makeFile('file.bin', 350), record);

    expect(state.phase).toBe('failed');
    expect(store.get(record.sessionId)).toBeNull();
  });

  it('cancels an in-flight upload and aborts the server session', async () => {
    const api = new FakeUploadApi();
    api.totalSizeBytes = 200;
    api.totalChunks = 2;

    let signalStarted: () => void = () => {};
    const started = new Promise<void>((resolve) => {
      signalStarted = resolve;
    });
    api.onChunk = (_index, _chunk, _call, signal) =>
      new Promise<void>((_resolve, reject) => {
        signalStarted();
        signal?.addEventListener('abort', () => reject(networkError()));
      });

    const store = new ResumeStore(memoryStorage());
    const { client } = makeClient(api, { store, maxAttempts: 5 });

    const run = client.start(makeFile('file.bin', 200));
    await started;
    await client.cancel();
    const state = await run;

    expect(state.phase).toBe('cancelled');
    expect(api.abortCalls).toBe(1);
    expect(store.get('session-1')).toBeNull();
  });

  it('pauses without losing progress and resumes from the server map', async () => {
    const api = new FakeUploadApi();
    api.totalSizeBytes = 200;
    api.totalChunks = 2;

    let releaseChunk: () => void = () => {};
    let chunkStarted: () => void = () => {};
    const started = new Promise<void>((resolve) => {
      chunkStarted = resolve;
    });
    api.onChunk = (index) => {
      if (index !== 0) return undefined;
      chunkStarted();
      return new Promise<void>((resolve) => {
        releaseChunk = resolve;
      });
    };

    const store = new ResumeStore(memoryStorage());
    const { client } = makeClient(api, { store });

    const run = client.start(makeFile('file.bin', 200));
    await started;
    client.pause();
    releaseChunk();

    const paused = await run;
    expect(paused.phase).toBe('paused');
    expect(paused.uploadedChunks).toBe(1);
    expect(store.get('session-1')).not.toBeNull();

    const resumed = await client.resume(
      makeFile('file.bin', 200),
      makeRecord({ sizeBytes: 200, totalChunks: 2, chunkSizeBytes: 100 }),
    );

    expect(api.chunkCalls).toEqual([0, 1]);
    expect(resumed.phase).toBe('completed');
    expect(resumed.progressPercent).toBe(100);
  });

  it('does not start a second run while one is active', async () => {
    const api = new FakeUploadApi();
    api.totalSizeBytes = 100;
    api.totalChunks = 1;
    const { client } = makeClient(api);

    const first = client.start(makeFile('file.bin', 100));
    const second = client.start(makeFile('file.bin', 100));

    expect(second).toBe(first);
    await first;
    expect(api.createCalls).toBe(1);
  });
});

describe('ResumableUploadClient finalization', () => {
  it('finalizes after every chunk is staged and clears the resume record', async () => {
    const api = new FakeUploadApi();
    api.totalSizeBytes = 200;
    api.totalChunks = 2;
    const store = new ResumeStore(memoryStorage());
    const { client, states } = makeClient(api, { store });

    const state = await client.start(makeFile('file.bin', 200));

    expect(api.chunkCalls).toEqual([0, 1]);
    expect(api.completeCalls).toBe(1);
    expect(state.phase).toBe('completed');
    expect(state.result?.currentVersion).toBe(1);
    expect(state.error).toBeNull();
    expect(store.get(api.sessionId)).toBeNull();

    // Finalizing was observable, and strictly before completion.
    const phases = states.map((snapshot) => snapshot.phase);
    expect(phases).toContain('finalizing');
    expect(phases.indexOf('finalizing')).toBeLessThan(phases.indexOf('completed'));
  });

  it('does not report success at 100% chunk progress when finalization fails, then retries the same session', async () => {
    const api = new FakeUploadApi();
    api.totalSizeBytes = 100;
    api.totalChunks = 1;
    api.completeError = networkError();
    const store = new ResumeStore(memoryStorage());
    const { client } = makeClient(api, { store });

    const first = await client.start(makeFile('file.bin', 100));

    // All chunks are staged, but the run is not a successful upload.
    expect(first.progressPercent).toBe(100);
    expect(first.phase).toBe('failed');
    expect(first.failureStage).toBe('finalize');
    expect(first.result).toBeNull();
    expect(store.get(api.sessionId)).not.toBeNull();

    // Retry finalization with no chunk re-uploaded.
    api.completeError = null;
    const retried = await client.finalize();

    expect(retried.phase).toBe('completed');
    expect(retried.result?.id).toBe('file-1');
    expect(api.chunkCalls).toEqual([0]);
    expect(api.completeCalls).toBe(2);
    expect(store.get(api.sessionId)).toBeNull();
  });

  it('resumes an already-completed session idempotently without re-uploading', async () => {
    const api = new FakeUploadApi();
    api.totalSizeBytes = 200;
    api.totalChunks = 2;
    api.status = 'COMPLETED';
    const store = new ResumeStore(memoryStorage());
    const record = makeRecord({
      sizeBytes: 200,
      totalChunks: 2,
      chunkSizeBytes: 100,
    });
    store.save(record);
    const { client } = makeClient(api, { store });

    const state = await client.resume(makeFile('file.bin', 200), record);

    expect(api.chunkCalls).toEqual([]);
    expect(api.completeCalls).toBe(1);
    expect(state.phase).toBe('completed');
    expect(store.get(record.sessionId)).toBeNull();
  });

  it('finalizes a zero-byte upload as a single empty chunk', async () => {
    const api = new FakeUploadApi();
    api.totalSizeBytes = 0;
    api.totalChunks = 1;
    const { client } = makeClient(api);

    const state = await client.start(makeFile('empty.bin', 0));

    expect(api.chunkCalls).toEqual([0]);
    expect(api.completeCalls).toBe(1);
    expect(state.phase).toBe('completed');
    expect(state.progressPercent).toBe(100);
  });

  it('coalesces a duplicate finalize while a run is active', async () => {
    const api = new FakeUploadApi();
    api.totalSizeBytes = 100;
    api.totalChunks = 1;
    const { client } = makeClient(api);

    const first = client.start(makeFile('file.bin', 100));
    const second = client.finalize();

    expect(second).toBe(first);
    await first;
    expect(api.completeCalls).toBe(1);
  });

  it('finalize is a no-op without an active session', async () => {
    const api = new FakeUploadApi();
    const { client } = makeClient(api);

    const state = await client.finalize();

    expect(state.phase).toBe('idle');
    expect(api.completeCalls).toBe(0);
  });

  it('lists stored resumable sessions and forgets them after finalization', async () => {
    const api = new FakeUploadApi();
    api.totalSizeBytes = 100;
    api.totalChunks = 1;
    const store = new ResumeStore(memoryStorage());
    const record = makeRecord({
      sizeBytes: 100,
      totalChunks: 1,
      chunkSizeBytes: 100,
    });
    store.save(record);
    const { client } = makeClient(api, { store });

    expect(client.listResumable().map((entry) => entry.sessionId)).toEqual([
      record.sessionId,
    ]);

    const state = await client.resume(makeFile('file.bin', 100), record);

    expect(state.phase).toBe('completed');
    expect(client.listResumable()).toEqual([]);
  });
});
