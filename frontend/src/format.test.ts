import { describe, expect, it } from 'vitest';
import { formatAge, formatBytes, planChunks, tone } from './format';

describe('formatBytes', () => {
  it('uses binary units and handles missing values', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1023)).toBe('1023 B');
    expect(formatBytes(1024)).toBe('1.0 KiB');
    expect(formatBytes(26_214_500)).toBe('25.0 MiB');
    expect(formatBytes(240_120_725_504)).toBe('224 GiB');
    expect(formatBytes(null)).toBe('—');
  });
});

describe('formatAge', () => {
  it('formats heartbeat ages', () => {
    expect(formatAge(null)).toBe('never');
    expect(formatAge(4)).toBe('4s ago');
    expect(formatAge(125)).toBe('2m 5s ago');
    expect(formatAge(3_700)).toBe('1h 1m ago');
  });
});

describe('planChunks', () => {
  it('matches the server chunking rule: fixed size, short last chunk, none for empty files', () => {
    expect(planChunks(0, 4)).toEqual([]);
    expect(planChunks(8, 4)).toEqual([{ index: 0, start: 0, end: 4 }, { index: 1, start: 4, end: 8 }]);
    const p = planChunks(26_214_500, 4_194_304);
    expect(p).toHaveLength(7);
    expect(p[6]).toEqual({ index: 6, start: 25_165_824, end: 26_214_500 });
    expect(() => planChunks(10, 0)).toThrow();
  });
});

describe('tone', () => {
  it('maps lifecycle states to severities', () => {
    expect(tone('HEALTHY')).toBe('ok');
    expect(tone('SUSPECT')).toBe('warn');
    expect(tone('OFFLINE')).toBe('bad');
    expect(tone('CORRUPT')).toBe('bad');
    expect(tone('SOMETHING_NEW')).toBe('neutral');
  });
});
