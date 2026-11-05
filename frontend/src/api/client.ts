import type { ApiErrorBody, HealthSummary } from './types';

/** A failed API call. `status` 0 means the control plane could not be reached at all. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly correlationId?: string,
    readonly details: ApiErrorBody['details'] = [],
  ) {
    super(message);
  }

  get offline(): boolean {
    return this.status === 0;
  }
}

const TOKEN_KEY = 'bytevault.token';
let unauthorizedHandler: (() => void) | null = null;

// The token lives in sessionStorage so a reload keeps the session but closing the tab ends it.
// Every storage access is guarded: it can throw in private windows or with storage blocked.
export const tokenStore = {
  get(): string | null {
    try {
      return sessionStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  set(token: string | null) {
    try {
      if (token) sessionStorage.setItem(TOKEN_KEY, token);
      else sessionStorage.removeItem(TOKEN_KEY);
    } catch {
      // the session then lasts only for this page load
    }
  },
};

export function onUnauthorized(handler: () => void) {
  unauthorizedHandler = handler;
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  rawBody?: BodyInit;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

async function send(path: string, opts: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
  const token = tokenStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  let body: BodyInit | undefined = opts.rawBody;
  if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(opts.body);
  }
  let res: Response;
  try {
    res = await fetch(`/api/v1${path}`, { method: opts.method ?? 'GET', headers, body, signal: opts.signal });
  } catch (e) {
    if (opts.signal?.aborted) throw e;
    throw new ApiError(0, 'OFFLINE', 'Control plane is not reachable');
  }
  if (res.ok) return res;

  let parsed: Partial<ApiErrorBody> = {};
  try {
    parsed = (await res.json()) as ApiErrorBody;
  } catch {
    // Non-JSON error (e.g. the dev proxy answering for a stopped backend).
    if (res.status >= 500) throw new ApiError(0, 'OFFLINE', `Control plane is not reachable (HTTP ${res.status})`);
  }
  if (res.status === 401 && path !== '/auth/login') unauthorizedHandler?.();
  throw new ApiError(res.status, parsed.code ?? `HTTP_${res.status}`, parsed.message ?? res.statusText,
    parsed.correlationId, parsed.details ?? []);
}

export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const res = await send(path, opts);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

/** Health answers 503 with a full summary when a dependency is down; that is data, not an error. */
export async function fetchHealth(signal?: AbortSignal): Promise<HealthSummary> {
  let res: Response;
  try {
    res = await fetch('/api/v1/health', { headers: { Accept: 'application/json' }, signal });
  } catch (e) {
    if (signal?.aborted) throw e;
    throw new ApiError(0, 'OFFLINE', 'Control plane is not reachable');
  }
  if (res.status === 200 || res.status === 503) {
    try {
      return (await res.json()) as HealthSummary;
    } catch {
      // fall through
    }
  }
  throw new ApiError(0, 'OFFLINE', `Control plane is not reachable (HTTP ${res.status})`);
}

export async function apiBlob(path: string): Promise<Blob> {
  const res = await send(path, { headers: { Accept: '*/*' } });
  return res.blob();
}
