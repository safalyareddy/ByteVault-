# Operations runbook

All commands run from the repository root. Lines marked **DESTRUCTIVE** ask
for a typed confirmation and should only be run deliberately.

## Accounts

The first start creates the ADMIN account `admin`, using the password in
`BV_BOOTSTRAP_ADMIN_PASSWORD` from `.env`. That variable is read only while the
users table is empty.

```bash
scripts/bv.sh login            # prompts for the password; saves a 12 h token to .bv-token (mode 600)
scripts/bv.sh logout           # revokes it server-side
```

To create more accounts (ADMIN only), use a password of 12–72 characters:

```bash
curl -s -X POST 127.0.0.1:8090/api/v1/admin/users -H "Authorization: Bearer $(cat .bv-token)" \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"<12+ chars>","role":"USER"}'
```

| Role | Can |
|---|---|
| USER | upload, list, download and delete **its own** objects |
| ADMIN | everything a USER can, plus node inventory, repairs, events, stats, drain, scrub, reconcile, DLQ replay and user management |

## Start and stop

```bash
scripts/up.sh [--profile spare] [--profile monitoring]
scripts/status.sh
scripts/down.sh                 # keeps all volumes
scripts/reset-volumes.sh        # DESTRUCTIVE: wipes all data volumes
```

## Backup

```bash
scripts/backup-db.sh            # -> backups/bytevault-<UTC timestamp>.dump (mode 600, git-ignored)
```

This backs up **metadata only**. Chunk bytes live on the node volumes and are
protected by replication, not by this backup. A metadata backup without the
node volumes can't restore data on its own, and node volumes without metadata
are unreadable bytes.

## Restore and reconcile

1. **Restore (DESTRUCTIVE):** `scripts/restore-db.sh backups/<file>.dump`.
   This stops the control plane, replaces the database and starts it again.
   Every token issued after the backup is gone, so log in again.
2. **Dry-run reconciliation.** This changes nothing:
   ```bash
   curl -s -X POST -H "Authorization: Bearer $(cat .bv-token)" 127.0.0.1:8090/api/v1/admin/reconcile | jq
   ```
   For each node it reports:
   - **orphans:** chunk files that no replica row knows about. These were
     written after the backup.
   - **missing:** VERIFIED replica rows whose file is gone. These were deleted
     after the backup.

   Nodes that aren't HEALTHY or DRAINING are reported as skipped. They are
   never treated as empty.
3. **Apply:**
   ```bash
   curl -s -X POST -H "Authorization: Bearer $(cat .bv-token)" '127.0.0.1:8090/api/v1/admin/reconcile?apply=true' | jq
   ```
   Orphans are deleted from their nodes. Missing replicas are marked LOST, and
   repair restores them from another verified copy where one exists.

Both runs are audited (`INVENTORY_RECONCILE`).

**Limits.** Objects uploaded after the backup can't be recovered from the
restored metadata; their chunks show up as orphans. If an object was deleted
after the backup, its restored metadata points at bytes that no longer exist.
Reconciliation marks those replicas LOST, and with no verified source left,
repair can't restore them, so the object stays DEGRADED. This is the expected
outcome of restoring older metadata, and it's reported honestly rather than
hidden.

## Failure handling

| Situation | What happens automatically | Operator action |
|---|---|---|
| Node stops | SUSPECT after `BV_SUSPECT_AFTER`; OFFLINE after `BV_OFFLINE_AFTER`, when its replicas are marked LOST; repair restores RF if a spare HEALTHY node exists | `GET /api/v1/repairs`; start the spare with `--profile spare` if needed |
| Node returns | Re-admitted (JOINING → HEALTHY). Stale copies are deleted, or re-verified if still needed | none |
| Corrupt bytes | Found by the scrubber (`BV_SCRUB_INTERVAL`) or on read; repaired from a verified copy | Run a scrub now with `POST /api/v1/admin/scrub` |
| Kafka down | Uploads continue; the outbox backlog grows; `/api/v1/stats` shows `consistent:false`; health is 503 | Restore Kafka; the backlog drains on its own |
| Consumer failures | Retried 5 times with jitter, then sent to the DLQ | `GET /api/v1/admin/dlq`, fix the cause, `POST /api/v1/admin/dlq/{id}/replay` |
| Planned removal | none | `POST /api/v1/nodes/{id}/drain` and wait for `safeToRemove`, or cancel with `/drain/cancel` |

Fault injection for demos and tests is in `scripts/fault/`. Every script there
is guarded.
