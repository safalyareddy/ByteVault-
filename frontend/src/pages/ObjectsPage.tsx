import { useRef, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { ObjectSummary, Page } from '../api/types';
import { Badge, ErrorNote, Freshness, Loadable, Panel } from '../components/ui';
import { formatBytes, formatTime } from '../format';
import { usePoll } from '../hooks/usePoll';
import { navigate } from '../router';
import { uploadFile, type UploadProgress } from '../upload/uploader';

const PAGE_SIZE = 25;

export function ObjectsPage() {
  const [page, setPage] = useState(0);
  const objects = usePoll((s) => api<Page<ObjectSummary>>(`/objects?page=${page}&size=${PAGE_SIZE}`, { signal: s }),
    5_000, [page]);

  return (
    <>
      <div className="page-head">
        <h1>Objects</h1>
        <Freshness state={objects} />
      </div>
      <Uploader onDone={objects.refresh} />
      <Panel title="Your objects">
        <Loadable state={objects} isEmpty={(p) => p.items.length === 0} empty="No objects yet. Upload one above.">
          {(p) => (
            <>
              <div className="table-wrap">
                <table className="grid clickable">
                  <thead>
                    <tr><th>Name</th><th>Status</th><th>Size</th><th>Chunks</th><th>RF</th><th>Created</th></tr>
                  </thead>
                  <tbody>
                    {p.items.map((o) => (
                      <tr key={o.id} tabIndex={0} onClick={() => navigate(`/objects/${o.id}`)}
                        onKeyDown={(e) => e.key === 'Enter' && navigate(`/objects/${o.id}`)}>
                        <td className="name-cell">{o.name}</td>
                        <td><Badge value={o.status} /></td>
                        <td>{formatBytes(o.sizeBytes)}</td>
                        <td>{o.chunkCount}</td>
                        <td>{o.replicationFactor}</td>
                        <td className="small">{formatTime(o.createdAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="pager">
                <button className="btn small" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
                <span className="muted small">
                  {p.page * p.size + 1}–{p.page * p.size + p.items.length} of {p.totalItems}
                </span>
                <button className="btn small" disabled={(p.page + 1) * p.size >= p.totalItems}
                  onClick={() => setPage(page + 1)}>Next</button>
              </div>
            </>
          )}
        </Loadable>
      </Panel>
    </>
  );
}

function Uploader({ onDone }: { onDone: () => void }) {
  const [file, setFile] = useState<File>();
  const [progress, setProgress] = useState<UploadProgress>();
  const [error, setError] = useState<ApiError | Error>();
  const cancelled = useRef(false);
  // One key per selected file: retrying after a failure resumes the same server-side session.
  const key = useRef<string>('');
  const running = progress && ['starting', 'uploading', 'completing'].includes(progress.phase);

  async function start() {
    if (!file) return;
    cancelled.current = false;
    setError(undefined);
    try {
      await uploadFile(file, key.current, setProgress, () => cancelled.current);
      onDone();
    } catch (e) {
      if ((e as Error).message !== 'cancelled') setError(e as Error);
      setProgress((p) => (p && p.phase !== 'cancelled' ? { ...p, phase: 'error' } : p));
    }
  }

  const pct = progress && progress.totalBytes > 0 ? (progress.bytesDone / progress.totalBytes) * 100 : 0;
  return (
    <Panel title="Upload">
      <div className="upload-row">
        <input type="file" disabled={running} onChange={(e) => {
          setFile(e.target.files?.[0]);
          setProgress(undefined);
          setError(undefined);
          key.current = `web-${crypto.randomUUID()}`;
        }} />
        <button className="btn primary" disabled={!file || running} onClick={() => void start()}>
          {progress?.phase === 'error' ? 'Retry' : 'Upload'}
        </button>
        {running && <button className="btn" onClick={() => { cancelled.current = true; }}>Cancel</button>}
      </div>
      {progress && (
        <div className="upload-progress" aria-live="polite">
          <div className="meter big"><div className="meter-fill" style={{ width: `${pct}%` }} /></div>
          <div className="small">
            {progress.phase === 'done' ? 'Complete: every chunk verified on all replicas.' :
              `${progress.phase} · chunk ${progress.chunksDone}/${progress.chunkCount} · ${formatBytes(progress.bytesDone)} of ${formatBytes(progress.totalBytes)}`}
            {progress.retries > 0 && ` · ${progress.retries} retr${progress.retries === 1 ? 'y' : 'ies'}`}
            {progress.lastChunk && progress.phase !== 'done' && (
              <span className="muted"> · last chunk on {progress.lastChunk.replicas.filter((r) => r.state === 'VERIFIED')
                .map((r) => r.nodeId).join(', ')}</span>
            )}
            {progress.phase === 'done' && progress.objectId && (
              <> <a href={`#/objects/${progress.objectId}`}>View replicas</a></>
            )}
          </div>
        </div>
      )}
      {error && (error instanceof ApiError ? <ErrorNote error={error} /> :
        <div className="note error">{error.message}</div>)}
      <p className="muted small">
        Chunks are hashed in the browser (SHA-256) and verified by the server and every storage node.
        Retry resumes the same upload without duplicating data.
      </p>
    </Panel>
  );
}
