// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
vi.mock('@/services/authStore', () => ({ useAuth: () => ({ authenticated: false }) }));
import HomePage from './HomePage';

describe('MiniDrive landing page', () => {
  afterEach(cleanup);
  it('offers sign in and registration to unauthenticated users', () => {
    render(<MemoryRouter><HomePage /></MemoryRouter>);
    expect(screen.getByRole('link', { name: 'Sign in' }).getAttribute('href')).toBe('/login');
    expect(screen.getByRole('link', { name: 'Create account' }).getAttribute('href')).toBe('/register');
    expect(screen.queryByText(/coming in Phase/)).toBeNull();
  });
});
