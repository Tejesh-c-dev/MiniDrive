// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ logout: vi.fn(), refresh: vi.fn().mockRejectedValue(new Error('no cookie')) }));
vi.mock('@/api/client', () => ({ refreshAccessToken: mocks.refresh }));
vi.mock('./authService', () => ({
  login: vi.fn(), register: vi.fn(), logout: mocks.logout,
}));

import { authRestore, logout, useAuth } from './authStore';
import { getToken, removeToken, setToken } from '@/utils/tokenStorage';
import { act, renderHook } from '@testing-library/react';

describe('logout', () => {
  beforeEach(() => {
    removeToken();
    mocks.logout.mockReset();
    setToken('current-token');
  });
  afterEach(() => removeToken());

  it('removes local credentials immediately and reports confirmed server revocation', async () => {
    let resolveRequest!: () => void;
    mocks.logout.mockReturnValue(new Promise<void>((resolve) => { resolveRequest = resolve; }));
    const { result } = renderHook(() => useAuth());
    let operation!: Promise<boolean>;
    act(() => { operation = logout(); });

    expect(getToken()).toBeNull();
    expect(result.current.authenticated).toBe(false);
    expect(mocks.logout).toHaveBeenCalledWith('current-token');
    resolveRequest();
    await expect(operation).resolves.toBe(true);
  });

  it('clears local state and reports unconfirmed revocation when the request fails', async () => {
    mocks.logout.mockRejectedValue(new Error('network unavailable'));
    const { result } = renderHook(() => useAuth());
    let operation!: Promise<boolean>;
    act(() => { operation = logout(); });
    expect(getToken()).toBeNull();
    expect(result.current.authenticated).toBe(false);
    await expect(operation).resolves.toBe(false);
  });
});

it('does not persist access tokens in Web Storage', () => {
  setToken('volatile-token');
  expect(getToken()).toBe('volatile-token');
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
  removeToken();
});

it('attempts cookie restoration before guarded routes are marked ready', async () => {
  await authRestore;
  const { result } = renderHook(() => useAuth());
  expect(result.current.ready).toBe(true);
  expect(result.current.authenticated).toBe(false);
});
