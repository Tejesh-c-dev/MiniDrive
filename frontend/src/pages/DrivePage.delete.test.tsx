// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';

const mocks = vi.hoisted(() => ({
  listFiles: vi.fn(), listFolders: vi.fn(), deleteFile: vi.fn(), deleteFolder: vi.fn(),
  useAuth: vi.fn(),
}));
vi.mock('@/api/files', () => ({
  listFiles: mocks.listFiles, deleteFile: mocks.deleteFile,
  downloadFile: vi.fn(), renameFile: vi.fn(), replaceFileContent: vi.fn(),
}));
vi.mock('@/api/folders', () => ({
  listFolders: mocks.listFolders, deleteFolder: mocks.deleteFolder,
  createFolder: vi.fn(), renameFolder: vi.fn(), getFolder: vi.fn(),
}));
vi.mock('@/services/authStore', () => ({ useAuth: mocks.useAuth, logout: vi.fn() }));
vi.mock('@/hooks/useDriveLocation', () => ({
  useDriveLocation: () => ({
    location: { folderId: null, crumbs: [{ id: null, name: 'My Drive' }] },
    error: null, updateCrumbName: vi.fn(),
  }),
}));
vi.mock('@/hooks/useResumableUpload', () => ({
  useResumableUpload: () => ({
    state: { phase: 'idle', sessionId: null }, listResumable: () => [],
    start: vi.fn(), resume: vi.fn(), discardResumable: vi.fn(), cancel: vi.fn(),
    finalize: vi.fn(), reset: vi.fn(),
  }),
}));

import DrivePage from '@/pages/DrivePage';

const file = {
  id: 'file-1', name: 'report.pdf', folderId: null, contentType: 'application/pdf',
  sizeBytes: 20, updatedAt: '2026-10-23T00:00:00Z', currentVersion: 1,
};
const folder = {
  id: 'folder-1', name: 'Projects', parentFolderId: null, ownerId: 'owner-1',
  createdAt: '2026-10-23T00:00:00Z', updatedAt: '2026-10-23T00:00:00Z',
};
let files: typeof file[];
let folders: typeof folder[];

function PathProbe() {
  return <output data-testid="pathname">{useLocation().pathname}</output>;
}

function renderDrive() {
  return render(
    <MemoryRouter initialEntries={['/drive']}>
      <Routes>
        <Route path="/drive" element={<><DrivePage /><PathProbe /></>} />
        <Route path="/drive/folders/:id" element={<p>Folder destination</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  files = [{ ...file }];
  folders = [{ ...folder }];
  mocks.useAuth.mockReturnValue({ authenticated: true });
  mocks.listFiles.mockImplementation(async () => [...files]);
  mocks.listFolders.mockImplementation(async () => [...folders]);
  mocks.deleteFile.mockImplementation(async () => {
    files = [];
    return { complete: true };
  });
  mocks.deleteFolder.mockImplementation(async () => {
    folders = [];
    return { complete: true };
  });
});
afterEach(cleanup);

describe('Drive deletion controls', () => {
  it('renders delete actions for eligible files and folders', async () => {
    renderDrive();
    expect(await screen.findByRole('button', { name: 'Delete report.pdf' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Delete Projects' })).toBeTruthy();
  });

  it('cancels file deletion without an API request', async () => {
    const user = userEvent.setup();
    renderDrive();
    await user.click(await screen.findByRole('button', { name: 'Delete report.pdf' }));
    expect(screen.getByRole('alertdialog').textContent).toContain('report.pdf');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(mocks.deleteFile).not.toHaveBeenCalled();
    expect(within(screen.getByTestId('file-row')).getByText('report.pdf')).toBeTruthy();
  });

  it('cancels folder deletion without an API request and explains recursive deletion', async () => {
    const user = userEvent.setup();
    renderDrive();
    await user.click(await screen.findByRole('button', { name: 'Delete Projects' }));
    expect(screen.getByRole('alertdialog').textContent).toContain('all nested folders, files, and stored file versions');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(mocks.deleteFolder).not.toHaveBeenCalled();
    expect(screen.getByText('Projects')).toBeTruthy();
  });

  it('confirms file deletion by id, refreshes the listing, and removes it after success', async () => {
    const user = userEvent.setup();
    renderDrive();
    await user.click(await screen.findByRole('button', { name: 'Delete report.pdf' }));
    await user.click(screen.getByRole('button', { name: 'Delete permanently' }));
    await waitFor(() => expect(mocks.deleteFile).toHaveBeenCalledWith('file-1'));
    await waitFor(() => expect(screen.queryByText('report.pdf')).toBeNull());
    expect(mocks.listFiles).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId('delete-notice').textContent).toContain('deleted');
  });

  it('confirms folder deletion by id, refreshes the containing listing, and removes the folder', async () => {
    const user = userEvent.setup();
    renderDrive();
    await user.click(await screen.findByRole('button', { name: 'Delete Projects' }));
    await user.click(screen.getByRole('button', { name: 'Delete permanently' }));
    await waitFor(() => expect(mocks.deleteFolder).toHaveBeenCalledWith('folder-1'));
    await waitFor(() => expect(screen.queryByText('Projects')).toBeNull());
    expect(mocks.listFolders).toHaveBeenCalledTimes(2);
  });

  it('keeps the target visible, shows API failure, and allows retry', async () => {
    const user = userEvent.setup();
    mocks.deleteFile.mockRejectedValueOnce({ message: 'Storage cleanup is unavailable' });
    renderDrive();
    await user.click(await screen.findByRole('button', { name: 'Delete report.pdf' }));
    await user.click(screen.getByRole('button', { name: 'Delete permanently' }));
    expect((await screen.findByRole('alert')).textContent).toContain('Storage cleanup is unavailable');
    expect(within(screen.getByTestId('file-row')).getByText('report.pdf')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Delete permanently' }));
    await waitFor(() => expect(mocks.deleteFile).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByText('report.pdf')).toBeNull());
  });

  it('does not duplicate a pending submission', async () => {
    const user = userEvent.setup();
    let resolveDelete!: (value: { complete: boolean }) => void;
    mocks.deleteFile.mockReturnValueOnce(new Promise((resolve) => { resolveDelete = resolve; }));
    renderDrive();
    await user.click(await screen.findByRole('button', { name: 'Delete report.pdf' }));
    const confirm = screen.getByRole('button', { name: 'Delete permanently' });
    await user.click(confirm);
    expect((screen.getByTestId('delete-dialog-confirm') as HTMLButtonElement).disabled).toBe(true);
    expect(mocks.deleteFile).toHaveBeenCalledTimes(1);
    resolveDelete({ complete: true });
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
  });

  it('prevents a folder delete click from navigating into the folder', async () => {
    const user = userEvent.setup();
    renderDrive();
    await user.click(await screen.findByRole('button', { name: 'Delete Projects' }));
    expect(screen.getByTestId('pathname').textContent).toBe('/drive');
    expect(mocks.deleteFolder).not.toHaveBeenCalled();
  });

  it('keeps an accepted asynchronous deletion visible and labels it as processing', async () => {
    const user = userEvent.setup();
    mocks.deleteFolder.mockResolvedValue({ complete: false, status: 'PENDING' });
    renderDrive();
    await user.click(await screen.findByRole('button', { name: 'Delete Projects' }));
    await user.click(screen.getByRole('button', { name: 'Delete permanently' }));
    expect((await screen.findByTestId('delete-notice')).textContent).toContain('still processing');
    expect(screen.getByText('Projects')).toBeTruthy();
    expect(screen.getByTestId('folder-delete').textContent).toBe('Deleting…');
  });
});
