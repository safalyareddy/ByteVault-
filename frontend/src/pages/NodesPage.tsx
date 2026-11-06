import { useState } from 'react';
import { api, ApiError } from '../api/client';
import type { DrainStatus, StorageNode } from '../api/types';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Badge, ErrorNote, Freshness, Loadable, Meter, Panel } from '../components/ui';
import { formatAge, formatBytes, formatTime } from '../format';
import { usePoll } from '../hooks/usePoll';

export function NodesPage() {
  const nodes = usePoll((s) => api<StorageNode[]>('/nodes', { signal: s }), 3_000);
  const [action, setAction] = useState<{ node: StorageNode; kind: 'drain' | 'cancel' }>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError>();
  const [drain, setDrain] = useState<Record<string, DrainStatus>>({});

  const draining = (nodes.data ?? []).filter((n) => n.state === 'DRAINING').map((n) => n.id).join(',');
  usePoll(async (signal) => {
    const ids = draining ? draining.split(',') : [];
    const results = await Promise.all(ids.map((id) => api<DrainStatus>(`/nodes/${id}/drain`, { signal })));
    setDrain(Object.fromEntries(results.map((r) => [r.nodeId, r])));
    return results;
  }, 3_000, [draining]);

  async function run(reason: string) {
    if (!action) return;
    setBusy(true);
    setError(undefined);
    try {
      if (action.kind === 'drain') {
        await api(`/nodes/${action.node.id}/drain`, { method: 'POST', body: { reason } });
      } else {
        await api(`/nodes/${action.node.id}/drain/cancel`, { method: 'POST' });
      }
      setAction(undefined);
      nodes.refresh();
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page-head">
        <h1>Nodes</h1>
        <Freshness state={nodes} />
      </div>
      {error && <ErrorNote error={error} />}
      <Panel title="Inventory">
        <Loadable state={nodes} isEmpty={(n) => n.length === 0} empty="No node has sent a heartbeat yet.">
          {(list) => (
            <div className="table-wrap">
              <table className="grid">
                <thead>
                  <tr>
                    <th>Node</th><th>State</th><th aria-label="Actions" /><th>Last heartbeat</th><th>Disk used</th>
                    <th>Usable</th><th>State since</th><th>Endpoint</th>
                  </tr>
                </thead>
                <tbody>
                  {list.map((n) => {
                    const d = drain[n.id];
                    return (
                      <tr key={n.id}>
                        <td className="mono">{n.id}</td>
                        <td>
                          <Badge value={n.state} />
                          {d && (
                            <div className="small muted">
                              {d.safeToRemove ? 'safe to remove' :
                                `${d.verifiedReplicas} copies left · ${d.chunksStillDependingOnNode} still needed`}
                            </div>
                          )}
                        </td>
                        <td>
                          {(n.state === 'HEALTHY' || n.state === 'SUSPECT') && (
                            <button className="btn small" onClick={() => setAction({ node: n, kind: 'drain' })}>Drain</button>
                          )}
                          {n.state === 'DRAINING' && (
                            <button className="btn small" onClick={() => setAction({ node: n, kind: 'cancel' })}>Cancel drain</button>
                          )}
                        </td>
                        <td>{formatAge(n.heartbeatAgeSeconds)}</td>
                        <td className="meter-cell">
                          <Meter used={n.usedBytes} total={n.capacityBytes} />
                          <span className="small muted">{formatBytes(n.usedBytes)} of {formatBytes(n.capacityBytes)}</span>
                        </td>
                        <td>{formatBytes(n.usableBytes)}</td>
                        <td className="small">{formatTime(n.stateChangedAt)}</td>
                        <td className="mono small">{n.endpoint}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Loadable>
        <p className="muted small">
          Disk figures are what each node measures on its data directory. On this single-host setup they all describe
          the same Docker VM disk, so they are not independent capacity.
        </p>
      </Panel>
      {action && (
        <ConfirmDialog
          title={action.kind === 'drain' ? `Drain ${action.node.id}?` : `Cancel draining ${action.node.id}?`}
          body={action.kind === 'drain'
            ? <p>No new data goes to this node. Its copies are re-created on other nodes and then removed from it.
                It becomes safe to remove once nothing depends on it.</p>
            : <p>The node becomes eligible for new data again. Copies already moved stay where they are.</p>}
          confirmLabel={action.kind === 'drain' ? 'Start drain' : 'Cancel drain'}
          requireReason={action.kind === 'drain'}
          busy={busy}
          onConfirm={(reason) => void run(reason)}
          onCancel={() => setAction(undefined)}
        />
      )}
    </>
  );
}
