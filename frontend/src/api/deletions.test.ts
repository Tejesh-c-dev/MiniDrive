import { beforeEach, describe, expect, it, vi } from 'vitest';

const clientMock = vi.hoisted(() => ({ delete: vi.fn() }));
vi.mock('@/api/client', () => ({ apiClient: clientMock }));

import { deleteFile } from '@/api/files';
import { deleteFolder } from '@/api/folders';

beforeEach(() => clientMock.delete.mockReset());

describe('deletion API clients', () => {
  it('deletes a file by id and treats 204 as completed', async () => {
    clientMock.delete.mockResolvedValue({ status: 204, data: undefined });
    await expect(deleteFile('file-17')).resolves.toEqual({ complete: true });
    expect(clientMock.delete).toHaveBeenCalledWith('/api/files/file-17');
  });

  it('preserves the folder lifecycle result for an accepted 202 job', async () => {
    const result = { jobId: 'job-3', complete: false, status: 'PENDING' };
    clientMock.delete.mockResolvedValue({ status: 202, data: result });
    await expect(deleteFolder('folder-8')).resolves.toEqual(result);
    expect(clientMock.delete).toHaveBeenCalledWith('/api/folders/folder-8');
  });

});
