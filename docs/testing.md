# Testing guide

## Commands

```bash
mvn -B -ntp test                     # unit tests only, no Docker needed (about 1 minute)
mvn -B -ntp verify                   # + integration tests with Testcontainers (Docker required, about 5 minutes)
mvn -B -ntp verify -Dit.test=SecurityIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false   # one IT class
cd frontend && npm run lint && npm run typecheck && npm test && npm run build
python3 -I scripts/bvtool.py smoke   # end-to-end against a running stack
```

CI (`.github/workflows/ci.yml`) runs the same Maven and npm commands on a
GitHub runner, plus a check that the Compose configuration is valid.

## Integration tests

Every integration test runs against real infrastructure: PostgreSQL 16.9 and
Kafka 3.9.1 containers, plus storage-node containers built from
`storage-node/Dockerfile`. None of them use mocks for storage, the database
or Kafka.

| Class | Proves |
|---|---|
| `FoundationIT` | migrations apply to an empty database; health; topics exist; schema invariants (distinct replicas, verified checksum, one active repair, append-only audit) |
| `StorageIT` (3 good nodes + 2 misconfigured) | byte-identical round trip with bytes checked on each node's disk; retries without duplicates; incomplete uploads rejected; a corrupted replica is never served; delete removes bytes everywhere; too few healthy nodes never reach COMPLETE; signed heartbeats; admission policy; forged heartbeats rejected |
| `KafkaReliabilityIT` | outbox → Kafka → consumers; a duplicate delivery has one effect; a crash after commit but before ack is safe; bounded retries with jitter; DLQ with full diagnostics; guarded replay; malformed messages skip retries; a stopped consumer catches up exactly once; a broker outage holds events in the outbox, which then drains |
| `SelfHealingIT` (4 nodes) | node loss → OFFLINE → repair → rejoin cleanup; scrub finds corruption → repair; drain moves copies before removal; with too few nodes repair waits and RF is never lowered; reconciliation of orphan and missing files; repair metrics |
| `SecurityIT` | 401/403 rules; owner isolation; token logout and expiry; password policy; security headers; body-size cap including chunked bodies; CORS; login rate limit |

## Fault injection

Fault scripts live in `scripts/fault/`. They are guarded and target one named
resource at a time:
- `stop-node.sh` requires `--yes`.
- `corrupt-chunk.sh` works only on objects whose name starts with
  `disposable-`, and only after you type the chunk ID back.

## What isn't automated

- Load tests beyond the benchmark harness.
- Fault scenarios that need several hosts. All nodes share one host here.
- The destructive restore drill against the live database. The restore path
  is covered by restoring a backup into a scratch database, and
  reconciliation by `SelfHealingIT`.
