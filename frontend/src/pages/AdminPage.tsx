import { useState } from 'react';
import { api, ApiError } from '../api/client';
import type { DlqEntry, ReconcileReport, ScrubSummary } from '../api/types';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Badge, ErrorNote, Freshness, Loadable, Panel } from '../components/ui';
import { formatTime, shortId } from '../format';
import { usePoll } from '../hooks/usePoll';

export function AdminPage() {
  return (
    <>
      <div className="page-head"><h1>Admin</h1></div>
      <p className="muted">Every action here is authorized for the ADMIN role and written to the audit log with your username.</p>
      <DlqPanel />
      <div className="grid-2">
        <IntegrityPanel />
        <UsersPanel />
      </div>
    </>
  );
}

function DlqPanel() {
  const [status, setStatus] = useState('PENDING');
  const dlq = usePoll((s) => api<DlqEntry[]>(`/admin/dlq?status=${status}&limit=100`, { signal: s }), 5_000, [status]);
  const [replay, setReplay] = useState<DlqEntry>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError>();

  return (
    <Panel title="Dead-letter queue" actions={
      <>
        <Freshness state={dlq} />
        <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="DLQ status">
          <option>PENDING</option><option>REPLAYED</option><option>ALL</option>
        </select>
      </>
    }>
      {error && <ErrorNote error={error} />}
      <Loadable state={dlq} isEmpty={(d) => d.length === 0} empty="No dead-lettered events.">
        {(list) => (
          <div className="table-wrap">
            <table className="grid">
              <thead>
                <tr><th>Failed</th><th>Event</th><th>Topic / key</th><th>Group</th><th>Attempts</th><th>Reason</th>
                  <th>Status</th><th aria-label="Actions" /></tr>
              </thead>
              <tbody>
                {list.map((d) => (
                  <tr key={d.id}>
                    <td className="small">{formatTime(d.failedAt)}</td>
                    <td className="small">{d.eventType ?? 'unparseable'} <span className="mono muted">{shortId(d.eventId)}</span></td>
                    <td className="mono small">{d.originalTopic.replace('bytevault.', '')} / {shortId(d.originalKey, 12)}</td>
                    <td className="small">{d.consumerGroup ?? '—'}</td>
                    <td>{d.attempts ?? '—'}</td>
                    <td className="small wrap">{d.failureReason}</td>
                    <td><Badge value={d.status} />{d.replayCount > 0 && <div className="small muted">by {d.lastReplayedBy}</div>}</td>
                    <td>{d.status === 'PENDING' && <button className="btn small" onClick={() => setReplay(d)}>Replay</button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Loadable>
      {replay && (
        <ConfirmDialog title="Replay dead-lettered event?"
          body={<p>Re-publishes event <code>{shortId(replay.eventId)}</code> to <code>{replay.originalTopic}</code> with
            its original ID. Consumers that already processed it skip it; if it fails again it returns to the DLQ.</p>}
          confirmLabel="Replay" requireReason busy={busy}
          onCancel={() => setReplay(undefined)}
          onConfirm={async (reason) => {
            setBusy(true);
            setError(undefined);
            try {
              await api(`/admin/dlq/${replay.id}/replay`, { method: 'POST', body: { reason } });
              setReplay(undefined);
              dlq.refresh();
            } catch (e) {
              setError(e as ApiError);
            } finally {
              setBusy(false);
            }
          }} />
      )}
    </Panel>
  );
}

function IntegrityPanel() {
  const [scrub, setScrub] = useState<ScrubSummary>();
  const [report, setReport] = useState<ReconcileReport>();
  const [busy, setBusy] = useState<string>();
  const [confirmApply, setConfirmApply] = useState(false);
  const [error, setError] = useState<ApiError>();

  async function run<T>(label: string, path: string, set: (v: T) => void) {
    setBusy(label);
    setError(undefined);
    try {
      set(await api<T>(path, { method: 'POST' }));
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setBusy(undefined);
    }
  }

  return (
    <Panel title="Integrity">
      {error && <ErrorNote error={error} />}
      <div className="button-row">
        <button className="btn" disabled={!!busy} onClick={() => void run<ScrubSummary>('scrub', '/admin/scrub', setScrub)}>
          {busy === 'scrub' ? 'Scrubbing…' : 'Scrub now'}
        </button>
        <button className="btn" disabled={!!busy}
          onClick={() => void run<ReconcileReport>('dry', '/admin/reconcile', setReport)}>
          {busy === 'dry' ? 'Checking…' : 'Reconcile (dry run)'}
        </button>
        <button className="btn danger" disabled={!!busy || !report || report.applied} onClick={() => setConfirmApply(true)}>
          Apply reconciliation
        </button>
      </div>
      {scrub && (
        <p className="small">Scrub: {scrub.checked} replicas re-hashed on their nodes · {scrub.intact} intact ·{' '}
          <span className={scrub.corrupt ? 'bad-text' : ''}>{scrub.corrupt} corrupt</span> · {scrub.missing} missing ·{' '}
          {scrub.unavailable} unavailable. Problems are repaired automatically.</p>
      )}
      {report && (
        <table className="grid small">
          <thead><tr><th>Node</th><th>Status</th><th>Files</th><th>Rows</th><th>Orphans</th><th>Missing</th></tr></thead>
          <tbody>
            {report.nodes.map((n) => (
              <tr key={n.nodeId}>
                <td className="mono">{n.nodeId}</td><td>{n.status}</td><td>{n.chunksOnNode}</td><td>{n.replicaRows}</td>
                <td className={n.orphans ? 'warn-text' : ''}>{n.orphans}{report.applied && n.orphansDeleted ? ` (${n.orphansDeleted} deleted)` : ''}</td>
                <td className={n.missing ? 'warn-text' : ''}>{n.missing}{report.applied && n.missingMarkedLost ? ` (${n.missingMarkedLost} → repair)` : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className="muted small">Reconciliation compares each node's files with metadata (use after a database restore).
        A dry run changes nothing.</p>
      {confirmApply && (
        <ConfirmDialog title="Apply reconciliation?"
          body={<p>Deletes orphan chunk files from nodes and marks missing replicas LOST so repair restores them.</p>}
          confirmLabel="Apply" typeToConfirm="apply" busy={busy === 'apply'}
          onCancel={() => setConfirmApply(false)}
          onConfirm={async () => {
            await run<ReconcileReport>('apply', '/admin/reconcile?apply=true', setReport);
            setConfirmApply(false);
          }} />
      )}
    </Panel>
  );
}

function UsersPanel() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<'USER' | 'ADMIN'>('USER');
  const [result, setResult] = useState<string>();
  const [error, setError] = useState<ApiError>();

  return (
    <Panel title="Create user">
      <form className="stack" onSubmit={async (e) => {
        e.preventDefault();
        setError(undefined);
        setResult(undefined);
        try {
          await api('/admin/users', { method: 'POST', body: { username, password, role } });
          setResult(`Created ${username} (${role}).`);
          setUsername('');
          setPassword('');
        } catch (err) {
          setError(err as ApiError);
        }
      }}>
        <label className="field">Username<input value={username} onChange={(e) => setUsername(e.target.value)}
          pattern="[a-z0-9][a-z0-9._-]{2,63}" required /></label>
        <label className="field">Password (12–72 characters)<input type="password" autoComplete="new-password" value={password}
          minLength={12} maxLength={72} onChange={(e) => setPassword(e.target.value)} required /></label>
        <label className="field">Role
          <select value={role} onChange={(e) => setRole(e.target.value as 'USER' | 'ADMIN')}>
            <option>USER</option><option>ADMIN</option>
          </select>
        </label>
        <button className="btn primary" type="submit">Create</button>
        {result && <div className="note ok">{result}</div>}
        {error && <ErrorNote error={error} />}
      </form>
    </Panel>
  );
}
