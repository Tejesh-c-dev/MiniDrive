import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { downloadFile, listSharedFiles, type SharedFileSummary } from '@/api/files';
import type { ApiError } from '@/types/api';
import { formatFileSize, formatUpdatedAt } from '@/utils/format';

export default function SharedWithMePage() {
  const [files, setFiles] = useState<SharedFileSummary[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [downloading, setDownloading] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const refresh = () => { setFiles(null); setError(null); void listSharedFiles().then(setFiles).catch(setError); };
  useEffect(() => { refresh(); }, []);
  const download = async (file: SharedFileSummary) => {
    if (downloading) return;
    setDownloading(file.id); setNotice('');
    try { const blob = await downloadFile(file.id); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = file.name; a.click(); URL.revokeObjectURL(url); }
    catch (e) {
      const failure = e as ApiError;
      setNotice(failure.message || 'Access may have expired. Refresh the listing and try again.');
      if (failure.status === 403 || failure.status === 404) {
        setFiles(current => current?.filter(candidate => candidate.id !== file.id) ?? current);
      }
    }
    finally { setDownloading(null); }
  };
  return <section className="w-full max-w-5xl py-8">
    <div className="mb-6 flex items-center justify-between"><div><h1 className="text-2xl font-bold text-gray-900">Shared with me</h1><p className="mt-1 text-sm text-gray-600">Files shared directly with your account.</p></div><div className="flex gap-4"><button type="button" onClick={refresh} className="text-sm font-medium text-blue-700">Refresh</button><Link to="/drive" className="text-sm font-medium text-blue-700">My Drive</Link></div></div>
    {files === null && !error && <p role="status">Loading shared files…</p>}
    {error && <div role="alert" className="rounded border border-red-200 bg-red-50 p-4 text-red-800">Could not load shared files: {error.message} <button onClick={refresh} className="ml-2 underline">Retry</button></div>}
    {notice && <p role="alert" className="mb-3 text-sm text-amber-800">{notice}</p>}
    {files && files.length === 0 && <p className="rounded-lg border bg-white p-8 text-center text-gray-600">No files have been shared with you yet.</p>}
    {files && files.length > 0 && <ul className="divide-y rounded-lg border bg-white">{files.map(file => <li key={file.id} className="flex flex-wrap items-center gap-4 p-4"><div className="min-w-0 flex-1"><p className="truncate font-medium text-gray-900">{file.name}</p><p className="text-xs text-gray-500">Shared by {file.ownerName} · {file.role} · {file.contentType} · {formatFileSize(file.sizeBytes)} · {formatUpdatedAt(file.updatedAt)}</p></div><button disabled={!!downloading} onClick={() => void download(file)} className="rounded border px-3 py-1 text-sm disabled:opacity-50">{downloading === file.id ? 'Downloading…' : 'Download'}</button></li>)}</ul>}
  </section>;
}
