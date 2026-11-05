import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import { isRetryable, sha256Hex } from './uploader';

describe('sha256Hex', () => {
  it('matches the known SHA-256 of "abc"', async () => {
    const bytes = new TextEncoder().encode('abc');
    expect(await sha256Hex(bytes.buffer as ArrayBuffer))
      .toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  });
});

describe('isRetryable', () => {
  it('retries transient failures but not client errors', () => {
    expect(isRetryable(new ApiError(0, 'OFFLINE', 'x'))).toBe(true);
    expect(isRetryable(new ApiError(502, 'X', 'replica write failed'))).toBe(true);
    expect(isRetryable(new ApiError(503, 'INSUFFICIENT_HEALTHY_NODES', 'x'))).toBe(true);
    expect(isRetryable(new ApiError(429, 'RATE_LIMITED', 'x'))).toBe(true);
    expect(isRetryable(new ApiError(422, 'CHECKSUM_MISMATCH', 'x'))).toBe(false);
    expect(isRetryable(new ApiError(409, 'CHUNK_CONFLICT', 'x'))).toBe(false);
    expect(isRetryable(new Error('boom'))).toBe(false);
  });
});
