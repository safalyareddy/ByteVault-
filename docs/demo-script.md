# Demo script (about 7 minutes)

This script shows the real behaviour live: upload, replica inspection,
recovery from node loss, recovery from corruption, and a Kafka outage. Every
step uses real processes and real bytes; nothing is mocked.

## Before the audience arrives (about 2 minutes, off-camera)

```bash
scripts/up.sh --profile spare          # 4 nodes, so a lost node's copies have somewhere to go
cd frontend && npm run dev &           # console at http://localhost:5173
cd .. && python3 -I scripts/bvtool.py smoke   # end-to-end check; must print SMOKE TEST PASSED
scripts/bv.sh login                    # admin; the password is BV_BOOTSTRAP_ADMIN_PASSWORD in .env
head -c 9000000 /dev/urandom > /tmp/disposable-demo.bin
```

Sign in to the console as `admin`.

## 1. What the system is (0:00–0:45)

Open **Overview**. Every number comes from the API and shows when it was
last updated. Explain the setup:
- 4 storage-node processes, each with its own volume
- a Spring Boot control plane with PostgreSQL as the source of truth
- Kafka for events, fed through a transactional outbox
- replication factor 3

## 2. Upload and inspect replicas (0:45–1:45)

On **Objects**, upload `/tmp/disposable-demo.bin`. Point out:
- progress chunk by chunk, with the nodes holding each chunk
- the browser hashes each chunk before sending it
- the server and every storage node check that hash before acknowledging

Open **View replicas**. Each 4 MiB chunk is VERIFIED on 3 *distinct* nodes.
Optionally show that the bytes really sit on those nodes' disks, as in
`SelfHealingIT`.

## 3. Lose a node (1:45–3:30)

```bash
scripts/fault/stop-node.sh node-1 --yes
```

On **Nodes**, watch node-1:
- **about 15 s:** SUSPECT. A missed heartbeat is only a suspicion.
- **about 45 s:** OFFLINE. Its copies are marked LOST and objects show as
  DEGRADED but readable.

On **Repairs**, jobs appear: NODE_OFFLINE, with a source and target. Each job
reads a verified copy, writes it to node-4, and has node-4 re-hash the bytes
from disk before committing.

**Overview** returns to all green within seconds. Download the object and
compare the hashes:

```bash
scripts/bv.sh download <objectId> /tmp/copy.bin && shasum -a 256 /tmp/disposable-demo.bin /tmp/copy.bin
```

Then bring node-1 back with `scripts/fault/start-node.sh node-1`. It goes
JOINING, then HEALTHY, and its stale copies are deleted because the chunks
are already fully replicated.

## 4. Corrupt bytes on disk (3:30–5:00)

```bash
scripts/fault/corrupt-chunk.sh <objectId> 0 <a node holding chunk 0>   # type the chunk id to confirm
```

This flips one byte in that node's file, and nothing notices yet. Go to
**Admin → Scrub now**: the result shows `1 corrupt`. On **Repairs**, a
CORRUPTION job rebuilds the copy from a verified one. Then:
- the object detail shows 3 VERIFIED copies again
- the download hash still matches the original
- corrupted bytes are never served: a download re-hashes every chunk before
  sending it

## 5. Kafka outage (5:00–6:30)

```bash
scripts/fault/pause-kafka.sh stop
```

The console header shows DOWN, with the banner "kafka not ready". Upload
another small file: **uploads keep working**, because writes depend only on
PostgreSQL and the nodes. The **Overview** outbox backlog rises, and the
event timeline stops advancing. Then:

```bash
scripts/fault/pause-kafka.sh start
```

The backlog drains within seconds, and **Events** shows the delayed events
with their real lag. Duplicates can't double-count, because consumers
deduplicate by event ID (the automated `KafkaReliabilityIT` proves this).

## 6. Wrap-up (6:30–7:00)

- **Evidence:** `docs/implementation-status.md` maps every claim to a test or
  a recorded command.
- **Limits:** this is a single-host demonstration. All nodes share one disk
  and one Docker VM, so it is not a claim of independent failure domains.
- **Numbers:** measured results with the method and raw data are in
  `docs/benchmark-report.md`.

## Reset after the demo

```bash
scripts/down.sh            # keeps data
scripts/reset-volumes.sh   # optional, DESTRUCTIVE: wipes all demo data (asks for confirmation)
```
