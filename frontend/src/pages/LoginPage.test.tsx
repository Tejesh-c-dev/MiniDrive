// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('@/services/authStore', () => ({ loginWithEmailAndPassword: vi.fn() }));
import LoginPage from './LoginPage';

function renderLogin(serverRevoked: boolean) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state: { serverRevoked } }]}>
      <Routes><Route path="/login" element={<LoginPage />} /></Routes>
    </MemoryRouter>,
  );
}

describe('logout result message', () => {
  afterEach(cleanup);

  it('does not claim server revocation when the logout request failed', () => {
    renderLogin(false);
    const status = screen.getByRole('status');
    expect(status.textContent).toContain('server could not confirm revocation');
    expect(status.textContent).toContain('copied token may remain usable');
    expect(status.textContent).not.toContain('revoked on the server');
  });

  it('confirms server revocation only when logout returned success', () => {
    renderLogin(true);
    expect(screen.getByRole('status').textContent).toContain('revoked on the server');
  });
});
