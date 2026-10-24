// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

const mocks = vi.hoisted(() => ({ files: [] as any[], listFiles: vi.fn(), listFolders: vi.fn(), download: vi.fn(), useAuth: vi.fn() }));
vi.mock('@/api/files', () => ({ listFiles: mocks.listFiles, downloadFile: mocks.download, deleteFile: vi.fn(), renameFile: vi.fn(), replaceFileContent: vi.fn() }));
vi.mock('@/api/folders', () => ({ listFolders: mocks.listFolders, deleteFolder: vi.fn(), createFolder: vi.fn(), renameFolder: vi.fn(), getFolder: vi.fn() }));
vi.mock('@/services/authStore', () => ({ useAuth: mocks.useAuth, logout: vi.fn() }));
vi.mock('@/hooks/useDriveLocation', () => ({ useDriveLocation: () => ({ location: { folderId: null, crumbs: [{ id: null, name: 'My Drive' }] }, error: null, updateCrumbName: vi.fn() }) }));
vi.mock('@/hooks/useResumableUpload', () => ({ useResumableUpload: () => ({ state: { phase: 'idle', sessionId: null }, listResumable: () => [], start: vi.fn(), resume: vi.fn(), discardResumable: vi.fn(), cancel: vi.fn(), finalize: vi.fn(), reset: vi.fn() }) }));
import DrivePage from './DrivePage';

const file = (id: string, name: string, contentType: string, sizeBytes = 6) => ({ id, name, folderId: null, contentType, sizeBytes, updatedAt: '2026-10-23T00:00:00Z', currentVersion: 1 });

beforeEach(() => {
  mocks.files = [file('png', 'image.png', 'image/png'), file('jpg', 'photo.jpg', 'image/jpeg'), file('txt', 'notes.txt', 'text/plain'), file('pdf', 'doc.pdf', 'application/pdf'), file('html', 'active.html', 'text/html')];
  mocks.listFiles.mockImplementation(async () => [...mocks.files]);
  mocks.listFolders.mockResolvedValue([]);
  mocks.useAuth.mockReturnValue({ authenticated: true });
  mocks.download.mockReset();
  let nextUrl = 0;
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:preview-${++nextUrl}`);
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
function renderDrive() { return render(<MemoryRouter><DrivePage /></MemoryRouter>); }

describe('authorized file previews', () => {
  it.each([['image.png', 'image/png', 'img'], ['photo.jpg', 'image/jpeg', 'img'], ['notes.txt', 'text/plain', 'Text file preview'], ['doc.pdf', 'application/pdf', 'PDF preview']])('opens supported %s through the authenticated download API', async (name, mime, previewRole) => {
    const user = userEvent.setup();
    mocks.download.mockResolvedValue(new Blob(['safe'], { type: mime }));
    renderDrive();
    await user.click(await screen.findByRole('button', { name: `Preview ${name}` }));
    expect(mocks.download).toHaveBeenCalledWith(({ 'image.png': 'png', 'photo.jpg': 'jpg', 'notes.txt': 'txt', 'doc.pdf': 'pdf' } as Record<string,string>)[name]);
    if (previewRole === 'img') expect(await screen.findByRole('img', { name })).toBeTruthy();
    else {
      const frame = await screen.findByTitle(previewRole);
      expect(frame).toBeTruthy();
      if (mime === 'application/pdf') {
        expect(frame.getAttribute('sandbox')).toBe('allow-same-origin');
        expect(frame.getAttribute('sandbox')).not.toContain('allow-scripts');
      }
    }
    expect(URL.createObjectURL).toHaveBeenCalledOnce();
  });

  it('sandbox-displays plain text without active HTML execution', async () => {
    const user = userEvent.setup(); mocks.files = [file('txt', 'notes.txt', 'text/plain')];
    mocks.download.mockResolvedValue(new Blob(['<script>window.pwned=true</script>'], { type: 'text/plain' }));
    renderDrive(); await user.click(await screen.findByRole('button', { name: 'Preview notes.txt' }));
    const frame = await screen.findByTitle('Text file preview');
    expect(frame.getAttribute('sandbox')).toBe('');
    expect(frame.getAttribute('src')).toMatch(/^blob:/);
  });

  it('shows a fallback for unsupported active types and keeps authorized download available', async () => {
    const user = userEvent.setup(); mocks.files = [file('html', 'active.html', 'text/html')];
    mocks.download.mockResolvedValue(new Blob(['<script>bad()</script>'], { type: 'text/html' }));
    renderDrive(); await user.click(await screen.findByRole('button', { name: 'Preview active.html' }));
    expect((await screen.findByRole('alert')).textContent).toContain('Preview is not available');
    expect(mocks.download).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Download' }));
    await waitFor(() => expect(mocks.download).toHaveBeenCalledWith('html'));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('rejects metadata over 10 MB before fetching preview content', async () => {
    const user = userEvent.setup(); mocks.files = [file('big', 'large.png', 'image/png', 10 * 1024 * 1024 + 1)];
    renderDrive(); await user.click(await screen.findByRole('button', { name: 'Preview large.png' }));
    expect((await screen.findByRole('alert')).textContent).toContain('too large to preview');
    expect(mocks.download).not.toHaveBeenCalled();
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it('handles failed, missing, mismatched and oversized download content without opening a preview', async () => {
    const user = userEvent.setup(); renderDrive();
    mocks.download.mockRejectedValueOnce({ message: 'Access revoked' });
    await user.click(await screen.findByRole('button', { name: 'Preview image.png' }));
    expect((await screen.findByRole('alert')).textContent).toContain('Access revoked');
    expect(screen.queryByRole('dialog')).toBeNull();
    mocks.download.mockResolvedValueOnce(undefined);
    await user.click(screen.getByRole('button', { name: 'Preview image.png' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    mocks.download.mockResolvedValueOnce(new Blob(['bad'], { type: 'text/html' }));
    await user.click(screen.getByRole('button', { name: 'Preview image.png' }));
    expect((await screen.findByRole('alert')).textContent).toContain('could not be verified');
    mocks.download.mockResolvedValueOnce(new Blob([new Uint8Array(10 * 1024 * 1024 + 1)], { type: 'image/png' }));
    await user.click(screen.getByRole('button', { name: 'Preview image.png' }));
    expect((await screen.findByRole('alert')).textContent).toContain('too large to preview');
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it('revokes object URLs on close, replacement and unmount, and ignores stale requests', async () => {
    const user = userEvent.setup(); let finish!: (blob: Blob) => void;
    mocks.download.mockReturnValueOnce(new Promise<Blob>(resolve => { finish = resolve; }));
    mocks.download.mockResolvedValueOnce(new Blob(['new'], { type: 'image/jpeg' }));
    const view = renderDrive();
    await user.click(await screen.findByRole('button', { name: 'Preview image.png' }));
    await user.click(screen.getByRole('button', { name: 'Preview photo.jpg' }));
    finish(new Blob(['stale'], { type: 'image/png' }));
    expect(await screen.findByRole('img', { name: 'photo.jpg' })).toBeTruthy();
    expect(screen.queryByRole('img', { name: 'image.png' })).toBeNull();
    const currentUrl = (screen.getByRole('img', { name: 'photo.jpg' }) as HTMLImageElement).src;
    mocks.download.mockResolvedValueOnce(new Blob(['again'], { type: 'image/png' }));
    await user.click(screen.getByRole('button', { name: 'Preview image.png' }));
    expect(await screen.findByRole('img', { name: 'image.png' })).toBeTruthy();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(currentUrl);
    await user.click(screen.getByRole('button', { name: 'Close preview' }));
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(currentUrl);
    mocks.download.mockResolvedValueOnce(new Blob(['again'], { type: 'image/png' }));
    await user.click(screen.getByRole('button', { name: 'Preview image.png' }));
    expect(await screen.findByRole('img', { name: 'image.png' })).toBeTruthy();
    const unmountUrl = (screen.getByRole('img', { name: 'image.png' }) as HTMLImageElement).src;
    view.unmount(); expect(URL.revokeObjectURL).toHaveBeenCalledWith(unmountUrl);
  });
});
