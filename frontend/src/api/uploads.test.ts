import { beforeEach, describe, expect, it, vi } from 'vitest';

const clientMock = vi.hoisted(() => ({
  post: vi.fn(),
  get: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));

vi.mock('@/api/client', () => ({ apiClient: clientMock }));

import {
  CHUNK_CONTENT_TYPE,
  abortUploadSession,
  completeUploadSession,
  createUploadSession,
  getUploadSessionStatus,
  uploadChunk,
} from '@/api/uploads';

beforeEach(() => {
  clientMock.post.mockReset();
  clientMock.get.mockReset();
  clientMock.put.mockReset();
  clientMock.delete.mockReset();
});

describe('uploads api', () => {
  it('creates a session at POST /api/uploads with the declared metadata', async () => {
    clientMock.post.mockResolvedValue({ data: { id: 'session-1' } });

    await createUploadSession({
      filename: 'a.bin',
      contentType: 'application/octet-stream',
      totalSizeBytes: 10,
    });

    expect(clientMock.post).toHaveBeenCalledTimes(1);
    const [url, body] = clientMock.post.mock.calls[0] as [string, unknown];
    expect(url).toBe('/api/uploads');
    expect(body).toEqual({
      filename: 'a.bin',
      contentType: 'application/octet-stream',
      totalSizeBytes: 10,
    });
  });

  it('includes the optional destination fields only when provided', async () => {
    clientMock.post.mockResolvedValue({ data: { id: 'session-1' } });

    await createUploadSession({
      filename: 'a.bin',
      contentType: 'text/plain',
      totalSizeBytes: 1,
      folderId: 'folder-1',
      targetFileId: 'file-9',
    });

    const [, body] = clientMock.post.mock.calls[0] as [string, unknown];
    expect(body).toEqual({
      filename: 'a.bin',
      contentType: 'text/plain',
      totalSizeBytes: 1,
      folderId: 'folder-1',
      targetFileId: 'file-9',
    });
  });

  it('reads session status from GET /api/uploads/{id}', async () => {
    clientMock.get.mockResolvedValue({
      data: { id: 'session-7', uploadedChunks: [0, 2] },
    });

    const status = await getUploadSessionStatus('session-7');

    expect(clientMock.get).toHaveBeenCalledWith('/api/uploads/session-7');
    expect(status.uploadedChunks).toEqual([0, 2]);
  });

  it('puts a chunk as application/octet-stream to the index endpoint', async () => {
    clientMock.put.mockResolvedValue({ data: { chunkIndex: 2 } });
    const blob = new Blob([new Uint8Array(5)]);

    await uploadChunk('session-7', 2, blob);

    expect(CHUNK_CONTENT_TYPE).toBe('application/octet-stream');
    expect(clientMock.put).toHaveBeenCalledTimes(1);
    const [url, body, config] = clientMock.put.mock.calls[0] as [
      string,
      Blob,
      { headers: Record<string, string>; signal?: AbortSignal },
    ];
    expect(url).toBe('/api/uploads/session-7/chunks/2');
    expect(body).toBe(blob);
    expect(config.headers['Content-Type']).toBe('application/octet-stream');
  });

  it('forwards an abort signal to the chunk request', async () => {
    clientMock.put.mockResolvedValue({ data: { chunkIndex: 0 } });
    const controller = new AbortController();

    await uploadChunk('session-7', 0, new Blob([]), controller.signal);

    const [, , config] = clientMock.put.mock.calls[0] as [
      string,
      Blob,
      { signal?: AbortSignal },
    ];
    expect(config.signal).toBe(controller.signal);
  });

  it('aborts a session with DELETE', async () => {
    clientMock.delete.mockResolvedValue({ data: undefined });

    await abortUploadSession('session-7');

    expect(clientMock.delete).toHaveBeenCalledWith('/api/uploads/session-7');
  });

  it('finalizes a session at POST /api/uploads/{id}/complete', async () => {
    clientMock.post.mockResolvedValue({
      data: { id: 'file-1', currentVersion: 2, uploadStatus: 'UPLOADED' },
    });

    const result = await completeUploadSession('session-7');

    expect(clientMock.post).toHaveBeenCalledWith('/api/uploads/session-7/complete');
    expect(result.id).toBe('file-1');
    expect(result.currentVersion).toBe(2);
  });

  it('maps a completion conflict to a typed ApiError', async () => {
    clientMock.post.mockRejectedValue({
      isAxiosError: true,
      response: {
        status: 409,
        data: { error: 'A file with this name already exists in this folder' },
      },
    });

    await expect(completeUploadSession('session-7')).rejects.toMatchObject({
      message: 'A file with this name already exists in this folder',
      status: 409,
    });
  });

  it('maps backend failures to a typed ApiError', async () => {
    clientMock.post.mockRejectedValue({
      isAxiosError: true,
      response: { status: 400, data: { error: 'File name is required' } },
    });

    await expect(
      createUploadSession({
        filename: '',
        contentType: 'text/plain',
        totalSizeBytes: 0,
      }),
    ).rejects.toMatchObject({
      message: 'File name is required',
      status: 400,
    });
  });
});
