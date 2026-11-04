import { fetchHealth } from './api';
import { usePolling } from './usePolling';

const POLL_MS = 10_000;

export function App() {
  const health = usePolling(fetchHealth, POLL_MS);

  return (
    <div className="shell">
      <header className="topbar">
        <span className="brand">ByteVault</span>
        <span className="subtitle">Operations console</span>
      </header>
      <main className="content">
        <h1>Control plane health</h1>
        <p className="hint">
          Live from <code>GET /api/v1/health</code>, refreshed every {POLL_MS / 1000}s. Cluster, object and repair
          screens arrive with their backend phases (see docs/implementation-status.md).
        </p>

        {health === undefined && <div className="panel muted" role="status">Loading health…</div>}

        {health?.kind === 'unreachable' && (
          <div className="panel error" role="alert">
            <strong>Offline.</strong> {health.error}. Last attempt {health.receivedAt.toLocaleTimeString()}.
          </div>
        )}

        {(health?.kind === 'ok' || health?.kind === 'degraded') && (
          <section className={`panel ${health.kind === 'ok' ? '' : 'warn'}`} aria-live="polite">
            <div className="status-row">
              <StatusBadge value={health.data.status} />
              <span>
                {health.kind === 'ok' ? 'All readiness checks passing' : 'Control plane is up but not ready'}
              </span>
            </div>
            <table className="grid">
              <thead>
                <tr>
                  <th>Check</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>liveness</td>
                  <td><StatusBadge value={health.data.liveness} /></td>
                </tr>
                {Object.entries(health.data.components).map(([name, status]) => (
                  <tr key={name}>
                    <td>{name}</td>
                    <td><StatusBadge value={status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="meta">
              Version {health.data.version} · server checked {new Date(health.data.checkedAt).toLocaleTimeString()}
              {' '}· received {health.receivedAt.toLocaleTimeString()}
            </p>
          </section>
        )}
      </main>
    </div>
  );
}

function StatusBadge({ value }: { value: string }) {
  const tone = value === 'UP' ? 'ok' : value === 'DOWN' || value === 'OUT_OF_SERVICE' ? 'bad' : 'unknown';
  return <span className={`badge ${tone}`}>{value}</span>;
}
