// Pure formatting helpers (unit tested in format.test.ts).

const UNITS = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes)) return '—';
  if (bytes < 1024) return `${bytes} B`;
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value >= 100 ? value.toFixed(0) : value.toFixed(1)} ${UNITS[unit]}`;
}

export function formatAge(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return 'never';
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s ago`;
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m ago`;
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

export function shortId(id: string | null | undefined, len = 8): string {
  return id ? id.slice(0, len) : '—';
}

/** Byte ranges for a fixed-size chunked upload; the last chunk may be shorter, empty files have none. */
export function planChunks(totalBytes: number, chunkSize: number): { index: number; start: number; end: number }[] {
  if (chunkSize <= 0) throw new Error('chunkSize must be positive');
  const chunks = [];
  for (let start = 0, index = 0; start < totalBytes; start += chunkSize, index++) {
    chunks.push({ index, start, end: Math.min(start + chunkSize, totalBytes) });
  }
  return chunks;
}

/** Tone for a status badge: ok / warn / bad / neutral. */
export function tone(status: string): 'ok' | 'warn' | 'bad' | 'neutral' {
  switch (status) {
    case 'UP':
    case 'HEALTHY':
    case 'COMPLETE':
    case 'VERIFIED':
    case 'STORED':
    case 'COMMITTED':
    case 'REPLAYED':
      return 'ok';
    case 'SUSPECT':
    case 'DEGRADED':
    case 'UNDER_REPLICATED':
    case 'JOINING':
    case 'DRAINING':
    case 'PENDING':
    case 'CLAIMED':
    case 'COPYING':
    case 'VERIFYING':
    case 'UPLOADING':
    case 'INITIATED':
    case 'DELETING':
    case 'FAILED':
      return 'warn';
    case 'DOWN':
    case 'OFFLINE':
    case 'CORRUPT':
    case 'LOST':
    case 'FAILED_WRITE':
    case 'ABANDONED':
      return 'bad';
    default:
      return 'neutral';
  }
}
