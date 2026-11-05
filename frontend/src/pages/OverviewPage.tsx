import { api } from '../api/client';
import type { Overview, StorageNode, TimelineEvent } from '../api/types';
import { Badge, Freshness, Loadable, Meter, Panel, Stat } from '../components/ui';
import { formatAge, formatBytes, formatTime } from '../format';
import { usePoll } from '../hooks/usePoll';

export function OverviewPage() {
  const overview = usePoll((s) => api<Overview>('/overview', { signal: s }), 5_000);
  const nodes = usePoll((s) => api<StorageNode[]>('/nodes', { signal: s }), 5_000);
  const events = usePoll((s) => api<TimelineEvent[]>('/events?limit=8', { signal: s }), 5_000);

  return (
    <>
      <div className="page-head">
        <h1>Overview</h1>
        <Freshness state={overview} />
      </div>
      <Loadable state={overview}>
        {(o) => {
          const totalNodes = Object.values(o.nodesByState).reduce((a, b) => a + b, 0);
          const unhealthy = o.nodesByState.SUSPECT + o.nodesByState.OFFLINE;
          return (
            <div className="stats">
              <Stat label="Nodes healthy" value={`${o.nodesByState.HEALTHY} / ${totalNodes}`}
                tone={unhealthy > 0 ? 'bad' : o.nodesByState.HEALTHY === totalNodes ? 'ok' : 'warn'}
                hint={unhealthy > 0 ? `${o.nodesByState.SUSPECT} suspect · ${o.nodesByState.OFFLINE} offline` :
                  o.nodesByState.DRAINING ? `${o.nodesByState.DRAINING} draining` : 'all reporting'} />
              <Stat label="Stored objects" value={o.objects.stored}
                hint={`${formatBytes(o.objects.storedBytes)} logical · ${o.objects.uploading} uploading`} />
              <Stat label="Degraded objects" value={o.objects.degraded} tone={o.objects.degraded ? 'warn' : 'ok'}
                hint="readable, below replication factor" />
              <Stat label="Under-replicated chunks" value={o.chunks.underReplicated}
                tone={o.chunks.underReplicated ? 'warn' : 'ok'} hint={`of ${o.chunks.total} stored chunks`} />
              <Stat label="Active repairs" value={o.repairs.active}
                tone={o.repairs.waitingForCapacity ? 'warn' : undefined}
                hint={`${o.repairs.waitingForCapacity} waiting for capacity · ${o.repairs.committedLastHour} done in 1h`} />
              <Stat label="Outbox backlog" value={o.events.outboxBacklog} tone={o.events.outboxBacklog > 50 ? 'warn' : undefined}
                hint="events not yet on Kafka" />
              <Stat label="DLQ pending" value={o.events.dlqPending} tone={o.events.dlqPending ? 'bad' : 'ok'}
                hint={<a href="#/admin">review in Admin</a>} />
              <Stat label="Abandoned repairs" value={o.repairs.abandoned} tone={o.repairs.abandoned ? 'bad' : 'ok'}
                hint="need operator attention" />
            </div>
          );
        }}
      </Loadable>

      <div className="grid-2">
        <Panel title="Nodes" actions={<Freshness state={nodes} />}>
          <Loadable state={nodes} isEmpty={(n) => n.length === 0} empty="No node has sent a heartbeat yet.">
            {(list) => (
              <ul className="node-list">
                {list.map((n) => (
                  <li key={n.id}>
                    <span className="node-id">{n.id}</span>
                    <Badge value={n.state} />
                    <span className="muted small">{formatAge(n.heartbeatAgeSeconds)}</span>
                    <Meter used={n.usedBytes} total={n.capacityBytes} />
                  </li>
                ))}
              </ul>
            )}
          </Loadable>
          <p className="muted small">Capacity is the Docker VM disk, shared by all nodes on this host.</p>
        </Panel>
        <Panel title="Recent events" actions={<Freshness state={events} />}>
          <Loadable state={events} isEmpty={(e) => e.length === 0} empty="No events received from Kafka yet.">
            {(list) => (
              <ul className="event-list">
                {list.map((e) => (
                  <li key={e.eventId}>
                    <span className="mono small">{formatTime(e.occurredAt)}</span>
                    <span>{e.eventType}</span>
                    <span className="muted small mono">{e.aggregateId.slice(0, 20)}</span>
                  </li>
                ))}
              </ul>
            )}
          </Loadable>
          <p className="muted small">Projection built by a Kafka consumer; may lag the authoritative state.</p>
        </Panel>
      </div>
    </>
  );
}
