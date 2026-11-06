import { useState } from 'react';
import { api, apiBlob, ApiError } from '../api/client';
import type { ObjectDetail } from '../api/types';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Badge, ErrorNote, Freshness, Loadable, Panel } from '../components/ui';
import { formatBytes, formatTime, shortId, tone } from '../format';
import { usePoll } from '../hooks/usePoll';
import { navigate } from '../router';

export function ObjectDetailPage({ id }: { id: string }) {
  const detail = usePoll((s) => api<ObjectDetail>(`/objects/${id}`, { signal: s }), 3_000, [id]);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError>();

  async function download(name: string) {
    setError(undefined);
    try {
      const blob = await apiBlob(`/objects/${id}/download`);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = name;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(e as ApiError);
    }
  }

  async function remove() {
    setBusy(true);
    try {
      await api(`/objects/${id}`, { method: 'DELETE' });
      setConfirmDelete(false);
      navigate('/objects');
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page-head">
        <h1><a href="#/objects" className="muted">Objects</a> / {detail.data?.object.name ?? shortId(id)}</h1>
        <Freshness state={detail} />
      </div>
      {error && <ErrorNote error={error} />}
      <Loadable state={detail}>
        {({ object: o, chunks }) => {
          const downloadable = o.status === 'COMPLETE' || o.status === 'DEGRADED';
          const nodes = Array.from(new Set(chunks.flatMap((c) => c.replicas.map((r) => r.nodeId)))).sort();
          return (
            <>
              <Panel title="Object" actions={
                <>
                  <button className="btn primary small" disabled={!downloadable}
                    title={downloadable ? '' : `Not downloadable while ${o.status}`}
                    onClick={() => void download(o.name)}>Download</button>
                  <button className="btn danger small" disabled={o.status === 'DELETING' || o.status === 'DELETED'}
                    onClick={() => setConfirmDelete(true)}>Delete</button>
                </>
              }>
                <dl className="kv">
                  <dt>Status</dt><dd><Badge value={o.status} /></dd>
                  <dt>Size</dt><dd>{formatBytes(o.sizeBytes)} ({o.sizeBytes.toLocaleString()} bytes)</dd>
                  <dt>Chunks</dt><dd>{o.chunkCount}</dd>
                  <dt>Replication factor</dt><dd>{o.replicationFactor}</dd>
                  <dt>Created</dt><dd>{formatTime(o.createdAt)}</dd>
                  <dt>Object ID</dt><dd className="mono small">{o.id}</dd>
                </dl>
                {o.status === 'DEGRADED' && (
                  <div className="note warn">Some chunks are below the replication factor. The object is still readable
                    from verified copies, and repair is restoring the missing ones.</div>
                )}
                <p className="muted small">Downloads are verified chunk by chunk on the server and buffered in the browser
                  before saving.</p>
              </Panel>
              <Panel title="Chunks and replicas">
                {chunks.length === 0 ? <div className="note muted">Empty object (no chunks).</div> : (
                  <div className="table-wrap">
                    <table className="grid">
                      <thead>
                        <tr>
                          <th>#</th><th>Status</th><th>Size</th><th>SHA-256</th><th>Verified</th>
                          {nodes.map((n) => <th key={n} className="mono">{n}</th>)}
                        </tr>
                      </thead>
                      <tbody>
                        {chunks.map((c) => (
                          <tr key={c.chunkId}>
                            <td>{c.index}</td>
                            <td><Badge value={c.status} /></td>
                            <td>{formatBytes(c.byteSize)}</td>
                            <td className="mono small" title={c.sha256 ?? ''}>{shortId(c.sha256, 12)}</td>
                            <td>{c.verifiedReplicas}/{o.replicationFactor}</td>
                            {nodes.map((n) => {
                              const r = c.replicas.find((x) => x.nodeId === n);
                              return (
                                <td key={n}>
                                  {r ? <span className={`pill ${tone(r.state)}`}
                                    title={[r.lastError, r.lastVerifiedAt && `verified ${formatTime(r.lastVerifiedAt)}`]
                                      .filter(Boolean).join(' · ')}>{r.state}</span> : <span className="muted">—</span>}
                                </td>
                              );
                            })}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </Panel>
              {confirmDelete && (
                <ConfirmDialog
                  title={`Delete ${o.name}?`}
                  body={<p>The object is tombstoned immediately and its bytes are removed from every node in the
                    background. This cannot be undone.</p>}
                  confirmLabel="Delete" typeToConfirm="delete" busy={busy}
                  onConfirm={() => void remove()} onCancel={() => setConfirmDelete(false)} />
              )}
            </>
          );
        }}
      </Loadable>
    </>
  );
}
