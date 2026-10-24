// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ logout: vi.fn() }));
vi.mock('./authService', () => ({
  login: vi.fn(), register: vi.fn(), logout: mocks.logout,
}));

import { logout, useAuth } from './authStore';
import { act, renderHook } from '@testing-library/react';

describe('logout', () => {
  beforeEach(() => {
    localStorage.clear();
    mocks.logout.mockReset();
    localStorage.setItem('minidrive_token', 'current-token');
  });
  afterEach(() => localStorage.clear());

  it('removes local credentials immediately and reports confirmed server revocation', async () => {
    let resolveRequest!: () => void;
    mocks.logout.mockReturnValue(new Promise<void>((resolve) => { resolveRequest = resolve; }));
    const { result } = renderHook(() => useAuth());
    let operation!: Promise<boolean>;
    act(() => { operation = logout(); });

    expect(localStorage.getItem('minidrive_token')).toBeNull();
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
    expect(localStorage.getItem('minidrive_token')).toBeNull();
    expect(result.current.authenticated).toBe(false);
    await expect(operation).resolves.toBe(false);
  });
});
