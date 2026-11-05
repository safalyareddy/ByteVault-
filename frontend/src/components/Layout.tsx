import type { ReactNode } from 'react';
import { fetchHealth } from '../api/client';
import { useAuth } from '../auth';
import { usePoll } from '../hooks/usePoll';
import { Badge } from './ui';

const ADMIN_NAV = [
  ['overview', 'Overview'],
  ['nodes', 'Nodes'],
  ['objects', 'Objects'],
  ['repairs', 'Repairs'],
  ['events', 'Events'],
  ['admin', 'Admin'],
] as const;
const USER_NAV = [['objects', 'Objects']] as const;

export function Layout({ current, children }: { current: string; children: ReactNode }) {
  const { session, logout } = useAuth();
  const health = usePoll((signal) => fetchHealth(signal), 10_000);
  const nav = session?.role === 'ADMIN' ? ADMIN_NAV : USER_NAV;

  return (
    <div className="shell">
      <header className="topbar">
        <span className="brand">ByteVault</span>
        <nav className="nav" aria-label="Main">
          {nav.map(([id, label]) => (
            <a key={id} href={`#/${id}`} className={current === id ? 'active' : ''}
              aria-current={current === id ? 'page' : undefined}>{label}</a>
          ))}
        </nav>
        <div className="topbar-right">
          <span title={health.data ? Object.entries(health.data.components).map(([k, v]) => `${k}: ${v}`).join(', ') : ''}>
            {health.error?.offline ? <Badge value="OFFLINE" /> : health.data ? <Badge value={health.data.status} /> : null}
          </span>
          <span className="muted small">{session?.username} · {session?.role}</span>
          <button className="btn small" onClick={() => void logout()}>Sign out</button>
        </div>
      </header>
      {health.error?.offline && (
        <div className="banner error" role="alert">
          Control plane unreachable. Data below is the last successful load and is marked stale.
        </div>
      )}
      {/* Only from a current health reading: stale data must not claim a degraded state. */}
      {!health.error && health.data?.status === 'DOWN' && (
        <div className="banner warn" role="status">
          Degraded: {Object.entries(health.data.components).filter(([, v]) => v !== 'UP').map(([k]) => k).join(', ')}
          {' '}not ready. Uploads may still work; event-driven views can lag.
        </div>
      )}
      <main className="content">{children}</main>
    </div>
  );
}
