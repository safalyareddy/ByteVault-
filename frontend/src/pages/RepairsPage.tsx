import { useState } from 'react';
import { api } from '../api/client';
import type { RepairJob } from '../api/types';
import { Badge, Freshness, Loadable, Panel } from '../components/ui';
import { formatTime, shortId } from '../format';
import { usePoll } from '../hooks/usePoll';

const STATES = ['', 'PENDING', 'CLAIMED', 'COPYING', 'VERIFYING', 'COMMITTED', 'ABANDONED'];

export function RepairsPage() {
  const [state, setState] = useState('');
  const jobs = usePoll((s) => api<RepairJob[]>(`/repairs?limit=200${state ? `&state=${state}` : ''}`, { signal: s }),
    3_000, [state]);

  return (
    <>
      <div className="page-head">
        <h1>Repairs</h1>
        <Freshness state={jobs} />
      </div>
      <Panel title="Repair jobs" actions={
        <label className="inline-field">State
          <select value={state} onChange={(e) => setState(e.target.value)}>
            {STATES.map((s) => <option key={s} value={s}>{s || 'all'}</option>)}
          </select>
        </label>
      }>
        <Loadable state={jobs} isEmpty={(j) => j.length === 0}
          empty={state ? `No ${state} repair jobs.` : 'No repairs so far: every chunk has had its full replica set.'}>
          {(list) => (
            <div className="table-wrap">
              <table className="grid">
                <thead>
                  <tr><th>Created</th><th>Reason</th><th>State</th><th>Chunk</th><th>Source → target</th>
                    <th>Attempts</th><th>Last error / note</th><th>Completed</th></tr>
                </thead>
                <tbody>
                  {list.map((j) => (
                    <tr key={j.id}>
                      <td className="small">{formatTime(j.createdAt)}</td>
                      <td>{j.reason}</td>
                      <td>
                        <Badge value={j.state} />
                        {j.state === 'COMMITTED' && !j.targetNodeId && <div className="small muted">no-op</div>}
                      </td>
                      <td><a href={`#/objects/${j.objectId}`} className="mono small">{shortId(j.objectId)}</a> #{j.chunkIndex}</td>
                      <td className="mono small">{j.sourceNodeId ?? '—'} → {j.targetNodeId ?? '—'}</td>
                      <td>{j.attempts}/{j.maxAttempts}</td>
                      <td className="small wrap">
                        {j.state === 'COMMITTED' && !j.targetNodeId
                          ? 'Nothing to copy: replicas were already sufficient when the job ran.' : j.lastError ?? ''}
                      </td>
                      <td className="small">{formatTime(j.completedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Loadable>
      </Panel>
    </>
  );
}
