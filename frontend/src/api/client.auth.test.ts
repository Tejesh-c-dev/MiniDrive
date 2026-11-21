// @vitest-environment jsdom
import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient, refreshAccessToken } from './client';
import { getToken, removeToken, setToken } from '@/utils/tokenStorage';

describe('access token refresh', () => {
  afterEach(() => {
    removeToken();
    vi.restoreAllMocks();
  });

  it('uses one cookie refresh for concurrent expired-token requests and retries both', async () => {
    setToken('expired-access-token');
    let resolveRefresh!: (value: { data: { token: string } }) => void;
    const refresh = vi.spyOn(axios, 'post').mockReturnValue(new Promise((resolve) => { resolveRefresh = resolve; }) as never);
    apiClient.defaults.adapter = async (config) => {
      if (config.headers.get('Authorization') === 'Bearer expired-access-token') {
        throw new AxiosError('expired', 'ERR_BAD_REQUEST', config as InternalAxiosRequestConfig, undefined, {
          status: 401, statusText: 'Unauthorized', headers: {}, config, data: {},
        });
      }
      return { data: { ok: true }, status: 200, statusText: 'OK', headers: {}, config };
    };

    const first = apiClient.get('/api/files');
    const second = apiClient.get('/api/folders');
    await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
    resolveRefresh({ data: { token: 'fresh-access-token' } });
    await expect(Promise.all([first, second])).resolves.toHaveLength(2);
    expect(getToken()).toBe('fresh-access-token');
  });

  it('clears the in-memory token when cookie refresh fails', async () => {
    setToken('expired-access-token');
    vi.spyOn(axios, 'post').mockRejectedValue(new Error('refresh rejected'));
    await expect(refreshAccessToken()).rejects.toThrow('refresh rejected');
    expect(getToken()).toBeNull();
  });
});
