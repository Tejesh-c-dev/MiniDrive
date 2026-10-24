// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
const mocks = vi.hoisted(() => ({ list: vi.fn(), download: vi.fn() }));
vi.mock('@/api/files', () => ({ listSharedFiles: mocks.list, downloadFile: mocks.download }));
import SharedWithMePage from './SharedWithMePage';

const entry = { id: 'f1', name: 'plan.pdf', folderId: null, contentType: 'application/pdf', sizeBytes: 80, updatedAt: '2026-10-23T00:00:00Z', currentVersion: 1, ownerName: 'Alice', role: 'VIEWER' as const };
let urlId = 0;
beforeEach(() => {
  mocks.list.mockReset(); mocks.download.mockReset(); urlId = 0;
  mocks.list.mockResolvedValue([entry]);
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:shared-${++urlId}`);
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
const mount = () => render(<MemoryRouter><SharedWithMePage /></MemoryRouter>);

describe('Shared with me page', () => {
  it('renders authorized entries with owner and effective role and no write actions', async () => {
    mount();
    expect(await screen.findByText('plan.pdf')).toBeTruthy();
    expect(screen.getByText(/Shared by Alice · VIEWER/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Download' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /edit|rename|delete|share/i })).toBeNull();
  });

  it('distinguishes loading, empty and list failures', async () => {
    let resolve!: (items: typeof entry[]) => void;
    mocks.list.mockReturnValueOnce(new Promise(resolvePromise => { resolve = resolvePromise; }));
    mount(); expect(screen.getByRole('status').textContent).toContain('Loading');
    resolve([]); expect(await screen.findByText(/No files have been shared/)).toBeTruthy();
    mocks.list.mockRejectedValueOnce({ message: 'Offline' });
    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh' }));
    expect((await screen.findByRole('alert')).textContent).toContain('Offline');
  });

  it('downloads through the authorized file API and reports failed or revoked access', async () => {
    const user = userEvent.setup(); mocks.download.mockResolvedValue(new Blob(['bytes']));
    mount(); await user.click(await screen.findByRole('button', { name: 'Download' }));
    expect(mocks.download).toHaveBeenCalledWith('f1');
    await waitFor(() => expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:shared-1'));
    mocks.download.mockRejectedValueOnce({ status: 403, message: 'Permission revoked' });
    await user.click(screen.getByRole('button', { name: 'Download' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText('plan.pdf')).toBeNull();
    expect(screen.getByText(/Permission revoked/)).toBeTruthy();
  });

  it('refreshes the listing and replaces previously displayed data', async () => {
    const user = userEvent.setup(); mocks.list.mockResolvedValueOnce([entry]).mockResolvedValueOnce([{ ...entry, name: 'new.pdf', role: 'EDITOR' }]);
    mount(); expect(await screen.findByText('plan.pdf')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText('new.pdf')).toBeTruthy();
    expect(screen.queryByText('plan.pdf')).toBeNull();
    expect(mocks.list).toHaveBeenCalledTimes(2);
  });
});
