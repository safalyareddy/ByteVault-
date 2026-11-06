import { api, ApiError } from '../api/client';
import type { ChunkUploadResult, UploadView } from '../api/types';
import { planChunks } from '../format';

export interface UploadProgress {
  phase: 'starting' | 'uploading' | 'completing' | 'done' | 'error' | 'cancelled';
  chunksDone: number;
  chunkCount: number;
  bytesDone: number;
  totalBytes: number;
  objectId?: string;
  lastChunk?: ChunkUploadResult;
  retries: number;
  error?: string;
}

export async function sha256Hex(data: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', data);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

/** Retryable: offline, gateway/replica write failure, temporarily insufficient nodes, busy. */
export function isRetryable(e: unknown): boolean {
  return e instanceof ApiError && (e.offline || e.status === 502 || e.status === 503 || e.status === 429);
}

/**
 * Chunked upload exactly as the API defines it: create an idempotent session, PUT each chunk
 * with its SHA-256 (computed in the browser), then complete. Only one chunk is in memory at a time.
 * A retry reuses the same Idempotency-Key, so it resumes the same session without duplicates.
 */
export async function uploadFile(
  file: File,
  idempotencyKey: string,
  onProgress: (p: UploadProgress) => void,
  isCancelled: () => boolean,
): Promise<string> {
  const progress: UploadProgress = {
    phase: 'starting', chunksDone: 0, chunkCount: 0, bytesDone: 0, totalBytes: file.size, retries: 0,
  };
  const emit = () => onProgress({ ...progress });
  emit();

  const session = await api<UploadView>('/uploads', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: { name: file.name, sizeBytes: file.size, contentType: file.type || null },
  });
  progress.objectId = session.objectId;
  progress.chunkCount = session.chunkCount;
  if (session.status === 'COMPLETED') {
    progress.phase = 'done';
    progress.chunksDone = session.chunkCount;
    progress.bytesDone = file.size;
    emit();
    return session.objectId;
  }

  const plan = planChunks(file.size, session.chunkSizeBytes);
  const missing = new Set(session.missingChunkIndexes);
  progress.phase = 'uploading';
  for (const chunk of plan) {
    if (isCancelled()) {
      progress.phase = 'cancelled';
      emit();
      throw new Error('cancelled');
    }
    if (session.missingChunkIndexes.length < 1000 && !missing.has(chunk.index)) {
      progress.chunksDone++;
      progress.bytesDone += chunk.end - chunk.start;
      emit();
      continue; // already stored by an earlier attempt of this same session
    }
    const bytes = await file.slice(chunk.start, chunk.end).arrayBuffer();
    const sha = await sha256Hex(bytes);
    for (let attempt = 0; ; attempt++) {
      try {
        progress.lastChunk = await api<ChunkUploadResult>(`/uploads/${session.uploadId}/chunks/${chunk.index}`, {
          method: 'PUT',
          rawBody: bytes,
          headers: { 'Content-Type': 'application/octet-stream', 'X-Chunk-SHA256': sha },
        });
        break;
      } catch (e) {
        if (!isRetryable(e) || attempt >= 4 || isCancelled()) throw e;
        progress.retries++;
        emit();
        await new Promise((r) => setTimeout(r, 500 * 2 ** attempt));
      }
    }
    progress.chunksDone++;
    progress.bytesDone += chunk.end - chunk.start;
    emit();
  }

  progress.phase = 'completing';
  emit();
  await api(`/uploads/${session.uploadId}/complete`, { method: 'POST' });
  progress.phase = 'done';
  emit();
  return session.objectId;
}
