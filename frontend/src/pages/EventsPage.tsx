import { useState } from 'react';
import { api } from '../api/client';
import type { TimelineEvent } from '../api/types';
import { Freshness, Loadable, Panel } from '../components/ui';
import { formatTime, shortId } from '../format';
import { usePoll } from '../hooks/usePoll';

export function EventsPage() {
  const [correlation, setCorrelation] = useState('');
  const [aggregate, setAggregate] = useState('');
  const [open, setOpen] = useState<string>();
  const query = new URLSearchParams({ limit: '200' });
  if (correlation) query.set('correlationId', correlation);
  if (aggregate) query.set('aggregateId', aggregate);
  const events = usePoll((s) => api<TimelineEvent[]>(`/events?${query}`, { signal: s }), 4_000,
    [correlation, aggregate]);

  return (
    <>
      <div className="page-head">
        <h1>Event timeline</h1>
        <Freshness state={events} />
      </div>
      <div className="note muted">
        Built by the <code>bv-timeline</code> Kafka consumer from the transactional outbox. It is eventually
        consistent: an event appears here after it is published and consumed, which can lag during a Kafka outage.
      </div>
      <Panel title="Events" actions={
        <>
          {(correlation || aggregate) && (
            <button className="btn small" onClick={() => { setCorrelation(''); setAggregate(''); }}>Clear filters</button>
          )}
          <input className="filter" placeholder="Filter by aggregate ID" value={aggregate}
            onChange={(e) => setAggregate(e.target.value.trim())} />
        </>
      }>
        {correlation && <div className="note">Showing correlation <code>{correlation}</code></div>}
        <Loadable state={events} isEmpty={(e) => e.length === 0} empty="No events match.">
          {(list) => (
            <div className="table-wrap">
              <table className="grid">
                <thead>
                  <tr><th>Occurred</th><th>Lag</th><th>Type</th><th>Aggregate</th><th>v</th><th>Correlation</th>
                    <th>Topic / partition / offset</th></tr>
                </thead>
                <tbody>
                  {list.map((e) => {
                    const lagMs = new Date(e.receivedAt).getTime() - new Date(e.occurredAt).getTime();
                    return [
                      <tr key={e.eventId} className="clickable" onClick={() => setOpen(open === e.eventId ? undefined : e.eventId)}>
                        <td className="small">{formatTime(e.occurredAt)}</td>
                        <td className={`small ${lagMs > 5000 ? 'warn-text' : 'muted'}`}>{(lagMs / 1000).toFixed(1)}s</td>
                        <td>{e.eventType}</td>
                        <td className="mono small" title={e.aggregateId}>{e.aggregateType} {shortId(e.aggregateId, 12)}</td>
                        <td>{e.aggregateVersion}</td>
                        <td>
                          {e.correlationId ? (
                            <button className="link mono small" onClick={(ev) => { ev.stopPropagation(); setCorrelation(e.correlationId!); }}>
                              {shortId(e.correlationId)}
                            </button>
                          ) : <span className="muted">—</span>}
                        </td>
                        <td className="mono small">{e.topic.replace('bytevault.', '')} / {e.kafkaPartition} / {e.kafkaOffset}</td>
                      </tr>,
                      open === e.eventId && (
                        <tr key={`${e.eventId}-payload`}>
                          <td colSpan={7}><pre className="payload">{JSON.stringify(e.payload, null, 2)}</pre></td>
                        </tr>
                      ),
                    ];
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Loadable>
        <p className="muted small">Lag = time from the state change being committed to the consumer storing the event.</p>
      </Panel>
    </>
  );
}
