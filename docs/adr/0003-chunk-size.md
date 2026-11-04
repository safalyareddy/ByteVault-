# ADR-0003: Fixed 4 MiB default chunk size

**Status:** Accepted, 2026-11-04

## Context
The chunk size trades metadata row count against how much data a repair or
retry has to move, and against memory and temp-file use per in-flight chunk.

## Decision
- Fixed-size chunking. The default is **4 MiB (4,194,304 bytes)**, set through
  `BV_CHUNK_SIZE_BYTES`, with allowed values from 64 KiB to 64 MiB.
- The chunk size is recorded on each upload session, so changing the setting
  never affects uploads already in flight.
- The last chunk may be shorter. The chunk count is
  `ceil(totalSize / chunkSize)`, and a zero-byte object has zero chunks.

## Consequences
- A 1 GiB object needs 256 chunks and 768 replica rows at RF=3, which is
  manageable.
- The control plane spools at most `max-concurrent-chunk-uploads × chunkSize`
  bytes to temp disk at once, never to the heap.
- No content-defined chunking or deduplication in v1.
