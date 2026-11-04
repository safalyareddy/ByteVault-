import { useEffect, useRef, useState } from 'react';

/** Calls `load` immediately and then every `intervalMs`, aborting in-flight calls on unmount. */
export function usePolling<T>(load: (signal: AbortSignal) => Promise<T>, intervalMs: number) {
  const [value, setValue] = useState<T | undefined>(undefined);
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    let controller = new AbortController();
    let cancelled = false;

    const tick = async () => {
      controller.abort();
      controller = new AbortController();
      try {
        const next = await loadRef.current(controller.signal);
        if (!cancelled) setValue(next);
      } catch {
        // aborted: a newer request or unmount superseded this one
      }
    };

    void tick();
    const id = window.setInterval(tick, intervalMs);
    return () => {
      cancelled = true;
      controller.abort();
      window.clearInterval(id);
    };
  }, [intervalMs]);

  return value;
}
