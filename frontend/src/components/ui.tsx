import type { ReactNode } from 'react';
import { ApiError } from '../api/client';
import { tone } from '../format';
import type { PollState } from '../hooks/usePoll';

export function Badge({ value }: { value: string }) {
  return <span className={`badge ${tone(value)}`}>{value}</span>;
}

/** "Updated 14:02:11", flagged when the data is stale. */
export function Freshness({ state }: { state: Pick<PollState<unknown>, 'updatedAt' | 'stale' | 'error'> }) {
  if (!state.updatedAt) return null;
  return (
    <span className={`freshness ${state.stale ? 'stale' : ''}`} title={state.error?.message}>
      {state.stale ? 'Stale — last updated ' : 'Updated '}
      {state.updatedAt.toLocaleTimeString()}
    </span>
  );
}

export function ErrorNote({ error }: { error: ApiError }) {
  return (
    <div className={`note ${error.offline ? 'error' : 'warn'}`} role="alert">
      <strong>{error.offline ? 'Offline.' : `${error.status} ${error.code}.`}</strong> {error.message}
      {error.correlationId && <span className="muted"> (correlation {error.correlationId})</span>}
    </div>
  );
}

/**
 * Renders the loading / error-without-data / empty / data states uniformly. With stale data and
 * an error, it shows both the data and the error.
 */
export function Loadable<T>({ state, empty, isEmpty, children }: {
  state: PollState<T>;
  empty?: ReactNode;
  isEmpty?: (data: T) => boolean;
  children: (data: T) => ReactNode;
}) {
  if (state.loading && state.data === undefined) return <div className="note muted" role="status">Loading…</div>;
  if (state.data === undefined) return state.error ? <ErrorNote error={state.error} /> : null;
  return (
    <>
      {state.error && <ErrorNote error={state.error} />}
      {isEmpty?.(state.data) ? <div className="note muted">{empty ?? 'Nothing here yet.'}</div> : children(state.data)}
    </>
  );
}

export function Panel({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="panel">
      <header className="panel-head">
        <h2>{title}</h2>
        <div className="panel-actions">{actions}</div>
      </header>
      {children}
    </section>
  );
}

export function Stat({ label, value, hint, tone: t }: { label: string; value: ReactNode; hint?: ReactNode;
  tone?: 'ok' | 'warn' | 'bad' }) {
  return (
    <div className={`stat ${t ?? ''}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {hint && <div className="stat-hint">{hint}</div>}
    </div>
  );
}

/** Usage bar with real numbers next to it. */
export function Meter({ used, total }: { used: number; total: number }) {
  const pct = total > 0 ? Math.min(100, (used / total) * 100) : 0;
  return (
    <div className="meter" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)}>
      <div className={`meter-fill ${pct > 90 ? 'bad' : pct > 75 ? 'warn' : ''}`} style={{ width: `${pct}%` }} />
    </div>
  );
}
