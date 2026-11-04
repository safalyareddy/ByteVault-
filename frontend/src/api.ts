// Typed client for the control-plane API. Every value the UI shows as live comes through here.

export interface HealthSummary {
  status: 'UP' | 'DOWN';
  liveness: string;
  readiness: string;
  components: Record<string, string>;
  version: string;
  checkedAt: string;
}

export interface ApiError {
  status: number;
  code: string;
  message: string;
  correlationId?: string;
}

export type Fetched<T> =
  | { kind: 'ok'; data: T; receivedAt: Date }
  | { kind: 'degraded'; data: T; receivedAt: Date } // server answered, but reports itself not ready
  | { kind: 'unreachable'; error: string; receivedAt: Date };

export async function fetchHealth(signal: AbortSignal): Promise<Fetched<HealthSummary>> {
  const receivedAt = () => new Date();
  let res: Response;
  try {
    res = await fetch('/api/v1/health', { signal, headers: { Accept: 'application/json' } });
  } catch (e) {
    if (signal.aborted) throw e;
    return { kind: 'unreachable', error: 'Control plane is not reachable', receivedAt: receivedAt() };
  }
  // 503 still carries a HealthSummary body describing which component is down.
  if (res.ok || res.status === 503) {
    try {
      const data = (await res.json()) as HealthSummary;
      return { kind: data.status === 'UP' ? 'ok' : 'degraded', data, receivedAt: receivedAt() };
    } catch {
      // fall through: a proxy error page or similar non-JSON body
    }
  }
  // A non-JSON error here usually comes from the dev proxy or a gateway, not the control plane.
  return {
    kind: 'unreachable',
    error: `Control plane is not reachable (HTTP ${res.status} without a health body)`,
    receivedAt: receivedAt(),
  };
}
