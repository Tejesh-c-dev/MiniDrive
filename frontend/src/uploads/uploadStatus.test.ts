import { describe, expect, it } from 'vitest';
import {
  createInitialUploadState,
  type ResumableUploadState,
} from '@/uploads/resumableUpload';
import {
  describeUploadState,
  isUploadBusy,
  isUploadTerminal,
} from '@/uploads/uploadStatus';

function stateOf(
  partial: Partial<ResumableUploadState>,
): ResumableUploadState {
  return { ...createInitialUploadState(), ...partial };
}

describe('upload status description', () => {
  it('is empty and inert while idle', () => {
    const view = describeUploadState(createInitialUploadState());
    expect(view.label).toBe('');
    expect(view.busy).toBe(false);
    expect(view.terminal).toBe(false);
    expect(view.canCancel).toBe(false);
    expect(view.canRetryFinalize).toBe(false);
  });

  it('shows an indeterminate preparing state before the session exists', () => {
    const view = describeUploadState(
      stateOf({ phase: 'preparing', filename: 'file.bin' }),
    );
    expect(view.label).toBe('Preparing file.bin…');
    expect(view.percent).toBeNull();
    expect(view.busy).toBe(true);
    expect(view.canCancel).toBe(false); // no session yet
  });

  it('reports upload progress and offers cancel once a session exists', () => {
    const view = describeUploadState(
      stateOf({
        phase: 'uploading',
        sessionId: 's1',
        filename: 'file.bin',
        totalBytes: 1000,
        progressPercent: 42,
      }),
    );
    expect(view.label).toBe('Uploading file.bin…');
    expect(view.percent).toBe(42);
    expect(view.busy).toBe(true);
    expect(view.canCancel).toBe(true);
    expect(view.terminal).toBe(false);
  });

  it('shows the retry attempt while reconnecting', () => {
    const view = describeUploadState(
      stateOf({
        phase: 'retrying',
        sessionId: 's1',
        filename: 'file.bin',
        totalBytes: 1000,
        progressPercent: 10,
        retryAttempt: 2,
      }),
    );
    expect(view.label).toMatch(/attempt 2/);
    expect(view.tone).toBe('info');
  });

  it('keeps finalizing informational so 100% chunks is not success', () => {
    const view = describeUploadState(
      stateOf({
        phase: 'finalizing',
        sessionId: 's1',
        filename: 'file.bin',
        totalBytes: 1000,
        progressPercent: 100,
      }),
    );
    expect(view.percent).toBe(100);
    expect(view.tone).toBe('info');
    expect(view.terminal).toBe(false);
    expect(view.busy).toBe(true);
  });

  it('marks success only once the backend finalizes', () => {
    const view = describeUploadState(
      stateOf({
        phase: 'completed',
        sessionId: 's1',
        filename: 'file.bin',
        totalBytes: 1000,
        progressPercent: 100,
        result: {
          id: 'file-1',
          name: 'file.bin',
          folderId: null,
          ownerId: 'owner-1',
          objectKey: 'files/1',
          contentType: 'application/octet-stream',
          sizeBytes: 1000,
          checksum: null,
          uploadStatus: 'UPLOADED',
          createdAt: '2026-01-01T00:00:00.000Z',
          updatedAt: '2026-01-01T00:00:00.000Z',
          currentVersion: 3,
        },
      }),
    );
    expect(view.tone).toBe('success');
    expect(view.label).toBe('Uploaded file.bin (version 3)');
    expect(view.terminal).toBe(true);
    expect(view.canCancel).toBe(false);
    expect(view.busy).toBe(false);
  });

  it('offers retry-finalize only for a finalize-stage failure with a live session', () => {
    const finalizeFailed = describeUploadState(
      stateOf({
        phase: 'failed',
        sessionId: 's1',
        filename: 'file.bin',
        failureStage: 'finalize',
        error: { message: 'boom' },
      }),
    );
    expect(finalizeFailed.tone).toBe('error');
    expect(finalizeFailed.label).toBe('Upload failed: boom');
    expect(finalizeFailed.canRetryFinalize).toBe(true);
    expect(finalizeFailed.terminal).toBe(true);

    const chunkFailed = describeUploadState(
      stateOf({
        phase: 'failed',
        sessionId: 's1',
        filename: 'file.bin',
        failureStage: 'chunks',
        error: { message: 'nope' },
      }),
    );
    expect(chunkFailed.canRetryFinalize).toBe(false);

    const noSession = describeUploadState(
      stateOf({ phase: 'failed', failureStage: 'finalize' }),
    );
    expect(noSession.canRetryFinalize).toBe(false);
  });

  it('classifies phases for busy/terminal checks', () => {
    expect(isUploadBusy('uploading')).toBe(true);
    expect(isUploadBusy('finalizing')).toBe(true);
    expect(isUploadBusy('completed')).toBe(false);
    expect(isUploadTerminal('completed')).toBe(true);
    expect(isUploadTerminal('cancelled')).toBe(true);
    expect(isUploadTerminal('failed')).toBe(true);
    expect(isUploadTerminal('uploading')).toBe(false);
  });
});
