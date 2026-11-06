# Benchmark report

**Measured on 2026-11-05.** These numbers describe this single-host setup
only. They are not capacity claims for a production deployment.

## Method (reproducible)

```bash
scripts/up.sh --profile spare                # 4 storage nodes; RF 3; 4 MiB chunks
python3 -I scripts/bvtool.py bench           # throughput / latency, BV_BENCH_RUNS (default 3)
python3 -I scripts/bvtool.py bench-repair    # node-loss detection and repair timing, 3 runs
```

- **Client:** Python standard library over HTTP to `127.0.0.1:8090`, using the
  public API with an authenticated ADMIN token.
- **Upload:** create session → PUT each chunk with its SHA-256 → complete.
  An upload counts as done only when every chunk has 3 VERIFIED replicas on
  distinct nodes, so these times include writing all 3 copies.
- **Download:** a full streamed download. The server re-hashes each chunk
  before sending it, and the client then compares the SHA-256 of the whole
  object with the original. **Every download in every run was verified.**
- **Payloads:** random (`os.urandom`), so compression and deduplication can't
  help. Objects are deleted after each run.
- **Workloads**, each run 3 times:

| Workload | Payload | Objects per run | Concurrency |
|---|---|---|---|
| 1MiB-c1 | 1 MiB | 10 | 1 |
| 16MiB-c1 | 16 MiB | 4 | 1 |
| 64MiB-c1 | 64 MiB | 2 | 1 |
| 1MiB-c4 | 1 MiB | 12 | 4 |

- **Cache:** warm. Everything runs on one host, so node reads come from the
  Docker VM's page cache. Dropping caches inside Docker Desktop isn't
  practical, and cold-disk numbers weren't measured.
- **Resources:** `docker stats` sampled every few seconds during the
  throughput runs.
- **Raw data:** `benchmarks/raw/bench-20261105T072431Z.json` and
  `benchmarks/raw/repair-20261105T072943Z.json`. These are git-ignored and
  regenerated with the commands above.

## Environment

| Item | Value |
|---|---|
| Host | Apple M4 (Mac16,12), 10 cores, 16 GB RAM, macOS 26.5.1 |
| Docker | Docker Desktop 29.8.2; VM with 10 CPUs and 7.7 GiB |
| Container limits | backend 768 MiB, Kafka 1 GiB, Postgres 512 MiB, each node 256 MiB |
| Config | chunk 4 MiB, RF 3, `BV_MAX_CONCURRENT_CHUNK_TRANSFERS` 8, 4 nodes (spare profile) |
| Storage | all four node volumes on the **same** Docker VM disk |

## Throughput and latency

| Workload | Upload MiB/s (each run) | Download MiB/s (each run) | Upload p50 / p95 s | Download p50 / p95 s | Event lag p50 / p95 s | All verified |
|---|---|---|---|---|---|---|
| 1MiB-c1 | 16.7, 25.0, 13.8 | 46.5, 40.7, 35.4 | 0.055 / 0.101 | 0.024 / 0.033 | 0.31 / 0.56 | yes |
| 16MiB-c1 | 39.1, 30.2, 52.4 | 65.2, 64.1, 81.2 | 0.405 / 0.557 | 0.225 / 0.297 | 0.29 / 0.48 | yes |
| 64MiB-c1 | 46.4, 50.9, 36.5 | 51.0, 79.1, 85.5 | 1.443 / 1.837 | 0.809 / 1.459 | 0.35 / 0.59 | yes |
| 1MiB-c4 | 43.7, 44.7, 23.7 | 92.9, 113.8, 26.2 | 0.097 / 0.238 | 0.046 / 0.192 | 0.19 / 0.62 | yes |

How to read these:
- **Upload MiB/s is logical bytes.** Each logical byte is written 3 times, to
  3 nodes, and fsynced on each, so the physical write volume is 3× the
  figure shown.
- **Event lag** is the time from the database commit of a state change to
  the timeline consumer storing it. It covers the outbox poll interval
  (500 ms) plus Kafka.
- **Variance is high.** The 1 MiB, concurrency 4 download went 92.9 → 113.8 →
  26.2 MiB/s across runs. The cause wasn't isolated: other activity on the
  laptop and Docker VM disk behaviour are the likely ones. Treat these
  figures as orders of magnitude, not targets.

## Resource use during the throughput runs

| Container | CPU % p50 / max | Memory max |
|---|---|---|
| backend (control plane) | 51.8 / 171.1 | 306 MiB |
| kafka | 4.6 / 170.6 | 583 MiB |
| node-1 / node-2 / node-3 / node-4 | 3.6–13.7 / 31.5–44.1 | 162–173 MiB |
| postgres | 7.3 / 25.3 | 85 MiB |

The control plane does the most work per byte: it spools each chunk, hashes
it, and fans it out to 3 nodes, then re-hashes on every download. Memory
stayed well under every container's limit.

## Node loss → repair (16 MiB object, 4 chunks)

| Run | Node stopped | Copies on it | Stop → OFFLINE | OFFLINE → object COMPLETE again | Download verified |
|---|---|---|---|---|---|
| 1 | node-2 | 4 | 43.9 s | 4.4 s | yes |
| 2 | node-1 | 4 | 42.8 s | 9.3 s | yes |
| 3 | node-2 | 4 | 44.1 s | 11.8 s | yes |

Notes:
- **Detection is configuration, not speed.** It reflects `BV_OFFLINE_AFTER =
  45s`, measured from the last heartbeat. That heartbeat came shortly
  *before* the stop command, which is why the elapsed time is slightly under
  45 s. A missed heartbeat means suspicion, not failure (SUSPECT after
  15 s), so OFFLINE is deliberately conservative.
- **Repair time** covers the planner's scan interval (5 s), the worker's
  polling, and copying each chunk from a verified source to node-4 followed
  by a re-hash on node-4's disk. Only 4 chunks needed repair, so planner
  timing dominates.

## Not measured

- Cold-cache reads.
- Objects larger than 64 MiB.
- More than 4 concurrent clients.
- Sustained load over hours.
- Repair of large numbers of chunks.
- Multi-host or network-latency effects.

No performance targets have been set; according to the spec, targets come
only after a baseline, and this report is that baseline.
