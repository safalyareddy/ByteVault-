# Final engineering verification

**Date:** 2026-11-06. **Commit under test:** `b247d0d` (clean tree). The running
Compose stack was built from this commit: images were built at 13:24 on
2026-11-05, after the last source edit at 13:22. **Topology:** 4 storage
nodes (spare profile), RF 3, 4 MiB chunks.

Each result below comes from a command run against the live stack or from the
test suites. Results reused from the previous audit
([production-readiness.md](production-readiness.md)) are marked as reused.

## Summary

| # | Area | Status |
|---|---|---|
| 1 | Kafka shutdown and recovery | **PASS** |
| 2 | Node shutdown → OFFLINE detection → repair → rejoin | **PASS** |
| 3 | Replica restoration and data integrity | **PASS** |
| 4 | Node drain workflow | **PASS** |
| 5 | Ordinary-user role and permissions (API) | **PASS** |
| 6 | Admin user-creation workflow (API) | **PASS** |
| 7 | Live database backup → restore → reconcile drill | **PASS** |
| 8 | Frontend suite (lint, typecheck, tests, build, runtime audit) | **PASS** |
| 9 | Backend suite (`mvn clean verify`) | **PASS** (on the 2nd run; the 1st run's failure was a host port collision, see 9) |
| 10 | All replicas and object hashes, cluster-wide | **PASS** |
| 11 | Final smoke test | **PASS** |
| — | Console UI for the drain, user and creation flows | **NOT TESTED this run.** Reused: PASS in the 2026-11-05 audit on the same build. This run checked the API those screens call |
| — | Monitoring profile (Prometheus/Grafana) | **NOT TESTED**; a known gap |
| — | CI on GitHub | **NOT TESTED**; nothing has been pushed |

**Bugs found: none.** One harness expectation was wrong and was corrected
(see 5).

## Method

A scratch harness, outside the repo, used `scripts/bvtool.py`'s client. The
admin login reads `.env` internally, and no password was printed.

The harness seeded three random objects of 300 KB, 9 MiB and 17 MiB
(1 + 3 + 5 chunks) and recorded each original SHA-256. Every integrity check
did three things:
- confirmed each chunk has exactly 3 VERIFIED replicas on distinct nodes;
- ran `sha256sum` on each replica file **inside the node container**;
- downloaded the whole object and compared it with the original SHA-256.

Baseline before any faults: all 9 chunks were 3/3 VERIFIED, with disk and
download hashes matching.

## 1. Kafka shutdown and recovery: PASS

| Step | Observed |
|---|---|
| `scripts/fault/pause-kafka.sh stop` (04:04:49Z) | `/api/v1/health` returned **503** with `kafka: DOWN` and `db: UP` |
| Upload a 5 MiB object during the outage | **succeeded** in 0.17 s; the object is COMPLETE and 3/3 VERIFIED |
| During the outage | `outboxBacklog` 4, `consistent=false`; the projection lagged by exactly that object. 60 s later the backend was still `healthy` and the backlog was unchanged, so nothing was lost or dropped |
| `pause-kafka.sh start` (04:06:20Z) | health UP after about 8 s; the backlog reached **0** and `consistent=true` at 04:06:31 |
| Timeline | all 4 events for the object (`UploadSessionCreated`, 2 × `ChunkStored`, `ObjectCompleted`) arrived **once each**, about 88 s after they occurred; no duplicate event IDs |
| DLQ | unchanged; it still holds the 3 earlier test entries, none added |

## 2. Node shutdown, OFFLINE detection and recovery: PASS

| Step | Observed |
|---|---|
| `stop-node.sh node-1 --yes` | node-1 held 8 of the 9 seeded chunks |
| Detection | **SUSPECT at 13.1 s**, **OFFLINE at 43.5 s** (configured 15 s / 45 s, measured from the last heartbeat) |
| Repair | every seeded object was **COMPLETE again 14.3 s after OFFLINE**; all `NODE_OFFLINE` jobs reached COMMITTED |
| Integrity while node-1 was down | every chunk 3/3 VERIFIED on node-2/3/4; disk and download hashes match |
| `start-node.sh node-1` | OFFLINE → **JOINING at 2.0 s** → **HEALTHY at 12.1 s** |
| Rejoin cleanup | all of node-1's stale copies were retired (190 replica rows DELETED) because every chunk already had RF 3; node-1's disk held **0** chunk files |
| Database invariants afterwards | 0 live chunks without exactly 3 VERIFIED copies on HEALTHY nodes; 0 active repair jobs |

## 3. Replica restoration and data integrity: PASS

Replica counts and hashes were checked after every phase: the baseline, the
Kafka outage, node loss, rejoin, drain, and restore. **Every check passed.**

Final cluster-wide check, covering all 9 live objects including those created
before this run and `audit-user`'s file:
- The database lists **78 VERIFIED replicas**: **26 chunks × exactly 3 copies**.
- Hashing every `.chunk` file on all 4 nodes found **78 files**.
- The diff between the two lists is **empty**: every file's SHA-256 equals its
  chunk's recorded SHA-256, with no extra and no missing files.

## 4. Node drain workflow: PASS

| Step | Observed |
|---|---|
| `POST /nodes/node-4/drain` with a reason | DRAINING; 26 VERIFIED copies, all 26 chunks still depend on it |
| Progress (polled every 3 s) | 26 → 22 → 16 → 10 → 4 → 0 copies left, while old copies were retired |
| Result | **`safeToRemove: true` after 24 s**; node-4's disk held **0** files |
| Integrity | every seeded chunk 3/3 VERIFIED on node-1/2/3; hashes match |
| Placement while draining | a new upload during the drain landed only on node-1/2/3 (see 5) |
| `POST /drain/cancel` | HEALTHY |

## 5. Ordinary-user role and permissions: PASS

The harness created a fresh USER, `verify-user-153ba3`, with a random
password. The password is stored only in the git-ignored, mode-600
`.bv-test-accounts` file. The harness then logged in as that user.

| Check | Result |
|---|---|
| `/auth/me` | role `USER` |
| 11 admin endpoints: `GET` nodes, repairs, events, stats, overview, dlq and drain status; `POST` admin/users, scrub, reconcile, drain | all **403** |
| An admin-owned object: view, download, delete | all **404**, so the object's existence isn't revealed |
| Own object listing before upload | empty |
| Own 4 MiB upload | COMPLETE; 2 chunks, each 3/3 VERIFIED on node-1/2/3 (node-4 was DRAINING and correctly excluded); disk and download hashes match |
| Replica `lastError` in the owner's view | none exposed (the audit fix holds) |
| Delete own object | 202. Afterwards a download returns **409 `OBJECT_NOT_DOWNLOADABLE`** (designed: DELETING/DELETED objects can't be downloaded; `StorageIT` asserts this). The database shows the object DELETED, with every replica DELETED |
| After logout | the token is rejected with **401** |

**Harness correction (not a product bug):** the first run expected 404 after
delete. The documented state machine keeps the row as DELETED, which gives
409. The expectation was fixed.

## 6. Admin user-creation workflow: PASS

| Check | Result |
|---|---|
| ADMIN creates a USER | 201, `{username, role: USER}` |
| Duplicate username | **409** |
| Weak password | **400** |
| Unknown role (`ROOT`) | **400** |
| A USER trying to create an ADMIN | **403** |
| The new account can log in and has only USER rights | yes (see 5) |

## 7. Live database backup and restore drill: PASS

| Step | Observed |
|---|---|
| Before the backup | 9 live objects, 78 VERIFIED replicas, 3 users, object-ID digest `fa76dee9…` |
| `scripts/backup-db.sh` | `backups/bytevault-20261106T041019Z.dump` (894,875 bytes, mode 600) |
| After the backup, upload `drill-after-backup.bin` | 2 chunks; 10 objects and 84 replicas, digest changed |
| `scripts/restore-db.sh` with the typed confirmation | backend stopped, database replaced, backend started; **health 200 within about 6 s** |
| State after restore | **identical to the backup**: 9 objects, 78 replicas, 3 users, digest `fa76dee9…` |
| Reconcile dry run | **6 orphans** (node-1 2, node-2 1, node-3 2, node-4 1), which are the post-backup object's 2 chunks × 3 copies; 0 missing; nothing changed |
| Reconcile apply | deleted exactly those 6 |
| Second dry run | files equal rows on every node (26/26/26/0); 0 orphans, 0 missing |
| After restore | all nodes HEALTHY with no false transitions; `/stats` `consistent=true`, backlog 0; all seeded objects pass the full integrity check |

## 8. Frontend suite: PASS

`npm run lint` (0 warnings allowed), `typecheck`, `npm test` (**6/6**), and
`build`: all clean. `npm audit --omit=dev`: **0 vulnerabilities**.

## 9. Backend suite: PASS

**Final run:** `mvn -B -ntp clean verify` finished with **BUILD SUCCESS** in
2 min 13 s, with **0 compiler warnings**.

**125 tests, 0 failures, 0 errors, 0 skipped:**

| Suite | Tests |
|---|---|
| node-protocol | 6 |
| storage-node | 23 |
| control-plane unit | 55 |
| `SecurityIT` | 10 |
| `FoundationIT` | 8 |
| `StorageIT` | 9 |
| `KafkaReliabilityIT` | 8 |
| `SelfHealingIT` | 6 |

**First run: environmental, not a code defect.** Docker gave the
Testcontainers Kafka host port **49159**. On this Mac, **Ollama** was already
listening on `127.0.0.1:49159`, and a specific IPv4 bind takes precedence over
Docker's wildcard bind. The test clients resolve `localhost` to 127.0.0.1, so
they connected to Ollama and got no Kafka reply.

`lsof` showed both listeners, and a raw handshake probe confirmed it. As a
result, `SecurityIT.publicEndpointsAreOpenAndEverythingElseNeedsAToken` got
health **503** (`kafka DOWN`), and `FoundationIT` hung waiting for topics.

The run was stopped. The rerun drew port 52005, held only by Docker, and
passed in full.

**Note for CI and other machines:** Testcontainers ports are random, so this
can recur on any host where another process listens on an ephemeral port on
127.0.0.1. It doesn't affect the Compose stack, which uses fixed ports.

## 11. Final smoke test: PASS

`python3 -I scripts/bvtool.py smoke` uploaded a 9,449,529-byte object in
3 chunks: **every chunk fully replicated on distinct nodes**, and the
**download SHA-256 matches**. Upload took 0.41 s and download 0.11 s. The
object was then deleted.

Afterwards:
- all 4 nodes HEALTHY
- every seeded object passes the full disk-and-download integrity check
- `/stats` `consistent=true`, outbox backlog 0

## Leftovers on the local stack (not in git)

- **Test users:** `verify-user-153ba3` and `audit-user` (USER role), with
  credentials in the git-ignored `.bv-test-accounts`.
- **Test objects:** `verify-small.bin`, `verify-medium.bin`,
  `verify-large.bin` and `verify-during-kafka-outage.bin` (admin-owned).
- **Backups:** `backups/bytevault-20261106T041019Z.dump`, plus earlier ones.
- **DLQ:** the 3 older DLQ entries.
