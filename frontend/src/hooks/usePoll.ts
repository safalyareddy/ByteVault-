import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';

export interface PollState<T> {
  data: T | undefined;
  error: ApiError | undefined;
  /** True only until the first response (success or failure). */
  loading: boolean;
  /** When data was last successfully refreshed. */
  updatedAt: Date | undefined;
  /** Data exists but the latest refresh failed or is overdue (older than 2.5 intervals). */
  stale: boolean;
  refresh: () => void;
}

/**
 * Loads immediately, then every `intervalMs` while the tab is visible. Keeps the last good data
 * on failure, so screens can show it marked stale instead of blanking out.
 */
export function usePoll<T>(load: (signal: AbortSignal) => Promise<T>, intervalMs: number, deps: unknown[] = []): PollState<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<ApiError>();
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<Date>();
  const [now, setNow] = useState(() => Date.now());
  const loadRef = useRef(load);
  loadRef.current = load;
  const tick = useRef<() => void>(() => {});

  useEffect(() => {
    let controller = new AbortController();
    let cancelled = false;
    setLoading(true);
    setData(undefined);
    setError(undefined);
    setUpdatedAt(undefined);

    const run = async () => {
      controller.abort();
      controller = new AbortController();
      try {
        const next = await loadRef.current(controller.signal);
        if (cancelled) return;
        setData(next);
        setError(undefined);
        setUpdatedAt(new Date());
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        setError(e instanceof ApiError ? e : new ApiError(0, 'CLIENT_ERROR', String(e)));
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    tick.current = () => void run();

    void run();
    const id = window.setInterval(() => {
      if (document.visibilityState === 'visible') void run();
      setNow(Date.now());
    }, intervalMs);
    return () => {
      cancelled = true;
      controller.abort();
      window.clearInterval(id);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, ...deps]);

  const refresh = useCallback(() => tick.current(), []);
  const overdue = updatedAt !== undefined && now - updatedAt.getTime() > intervalMs * 2.5;
  return { data, error, loading, updatedAt, stale: data !== undefined && (error !== undefined || overdue), refresh };
}
