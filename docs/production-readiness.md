# Production-readiness audit

**Date:** 2026-11-05. **Scope:** the whole repository and the running Compose
stack. Every check below was actually run; anything not run is labelled.

## Verdict

**Ready to publish as an engineering portfolio project.** The repository is
clean of secrets, the documented behaviour is true, and every claim has
evidence.

**Not ready for production deployment.** The single-host topology,
plain-HTTP traffic, a superuser database account, a dev-server frontend and
missing account management are deliberate v1 limits (see "Gaps").

## 1. UI flows (live, in the browser)

| Flow | Result |
|---|---|
| Drain node-4 from the console (with a reason) | DRAINING with "13 copies left · 13 still needed" → **safe to remove** about 18 s later. node-4's disk had **0** chunk files; every chunk still had exactly 3 VERIFIED copies elsewhere; audited as `admin` with the reason. Cancel → HEALTHY |
| Create user from the console | `audit-user` (USER) created. The form clears after success, and duplicates are rejected by the API (409, `SecurityIT`) |
| Ordinary-user view, in a separate tab | nav shows only **Objects**; the admin's objects are invisible; `#/nodes` shows "needs the ADMIN role"; 7 admin reads and 5 admin actions all **403**; the admin's object view/download/delete all **404** (existence not revealed); own upload is COMPLETE, 3/3 per chunk, download hash matches; sign-out revokes the token |
| Earlier session | overview, nodes, objects, upload, detail, download, delete, repairs, events, DLQ replay, scrub, reconcile, and node/Kafka/control-plane outage states were all checked live |

## 2. Live backup → restore drill

1. Backup of 5 objects, 45 VERIFIED replicas and 2 users (845 KB).
2. **After** the backup, uploaded `drill-after-backup.bin`: 2 chunks, so 6
   files.
3. `scripts/restore-db.sh` with its typed confirmation: health UP; the state
   is **identical** to the backup (same counts, same object IDs); the newer
   object's metadata is gone; existing sessions still valid.
4. Reconcile dry run: **6 orphans** (2 on each of node-1, node-3 and node-4),
   0 missing, nothing changed. Apply deleted all 6. A second dry run shows
   files equal to rows on every node, with 0 orphans and 0 missing.
5. The `/stats` projection is consistent with the database.

## 3. Authentication, authorization, API security, rate limiting

| Check | Result |
|---|---|
| Public surface | only `/api/v1/health`, login, the node heartbeat (HMAC-authenticated), actuator health/info/prometheus and `/v3/api-docs`. `/actuator`, `env`, `configprops`, `beans`, `heapdump` and `loggers` all **401** |
| Information leaks | health bodies show no hosts or credentials; metrics contain no secrets |
| Network exposure | every published port is bound to **127.0.0.1** |
| Node endpoints | unsigned chunk read and list → **401** |
| Per-user API rate limit (live) | 2,520 requests in 8.6 s → 692 allowed, **1,828 × 429** with `Retry-After: 1` and the standard error body |
| Login limits, password policy, token expiry/logout, CORS, headers, body cap | covered by `SecurityIT` (10 tests) |
| **Fixed in this audit** | an ordinary user's object detail exposed replica `lastError` text containing internal hostnames and exception names. It's now shown only to ADMIN; owners see "hidden (operator detail)". New test `SecurityIT.replicaErrorsAreShownToAdminsOnly` |

## 4. Secrets and sensitive files

| Check | Result |
|---|---|
| Tracked files | no `.env`, keys, dumps or tokens. Only `.env.example`, which holds placeholders |
| Every real secret value in `.env`, searched across **all git history** | **0 occurrences** |
| Patterns for private keys, AWS, GitHub, Slack and OpenAI tokens in history | none |
| Hard-coded secret literals in production source | none. Test-only credentials exist in `src/test` (`IntegrationTest-Admin-Pass-0001`, node secrets); they work only against throwaway containers |
| Local sensitive files: `.env`, `.bv-token`, `.bv-test-accounts`, `backups/`, `benchmarks/raw/` | present and **git-ignored** |
| **Before publishing** | all 8 commits carry the author email `safalyareddy60@gmail.com`, which becomes public on GitHub. If you'd rather not expose it, rewrite the authors to your GitHub `noreply` address **before the first push**; this audit didn't change history |

## 5. Code quality

| Check | Result |
|---|---|
| Java compiler `-Xlint:all` (now permanent in `pom.xml`) | found 1 warning (`this` escaping `DataDirectory`'s constructor) → **fixed**; now **0** |
| Frontend ESLint (**added**: `@eslint/js` recommended, `typescript-eslint` recommended, `react-hooks`) | 24 files, **0 errors, 0 warnings**; 1 intentional, commented suppression in `usePoll.ts` |
| TypeScript `strict` | clean |
| npm audit, runtime dependencies | **0 vulnerabilities**; CI now fails on high or above |
| npm audit, dev tooling | Vite updated 6.3.5 → **6.4.5** (patch), clearing its advisories. **3 remain, accepted:** Vitest, `@vitest/mocker` and Tinypool advisories affect Vitest's UI/browser server and worker options, which this project never uses (`vitest run` only, never served). The fix is a major upgrade to Vitest 5, deferred to avoid a breaking toolchain change in a release audit |
| CI | now runs frontend lint, tests and the runtime audit in addition to typecheck and build |
| **Not added** | a Java formatter or style checker (Spotless or Checkstyle). Adopting one would reformat the whole codebase; recommended as a separate change |

## 6. Docker Compose behaviour (live)

| Check | Result |
|---|---|
| `scripts/down.sh` | 3 s; 0 containers left; **all 6 volumes kept** |
| `scripts/up.sh --profile spare` from cold | 29 s to healthy; same 5 objects; **0 false node transitions** (startup grace) |
| Control-plane SIGTERM | "Commencing graceful shutdown" → "Graceful shutdown complete" → DB pool closed in about 1 s (exit 143 = SIGTERM) |
| Process crash, simulated by the process exiting on its own | `restart: unless-stopped` restarted it automatically (`restarts=1`); the node returned to HEALTHY |
| Note | `docker kill` counts as a *manual* stop in Docker and is deliberately **not** restarted. Docker doesn't restart containers that are merely *unhealthy* |

## 7. README and demo accuracy

**Fixed:**
- Missing prerequisites (`python3`, `jq`, `curl`, `openssl`).
- The quick start ran `npm run dev`, which stays in the foreground and
  changes directory, before the smoke test; the frontend is now a separate
  step.
- Spare node port 9104 and the monitoring ports were missing from the ports
  table.
- Frontend lint and tests were missing from the test commands.
- The monitoring profile was described without noting it's unexercised.
- A stale reference to the removed `X-BV-Admin-Token` in
  `docs/kafka-events.md`.

**Checked:** every script and doc path referenced in the README and `docs/`
exists. The demo script's commands match the scripts' real flags and guards;
for example, the `disposable-` name guard matches the demo's file name.

## 8. Test results (final run)

| Suite | Result |
|---|---|
| Backend `mvn -B -ntp clean verify` | **125 tests, 0 failures, 0 skipped**: node-protocol 6, storage-node 23, control-plane unit 55, integration 41 (`FoundationIT` 8, `StorageIT` 9, `KafkaReliabilityIT` 8, `SelfHealingIT` 6, `SecurityIT` 10). Compiler warnings 0 |
| Frontend | lint clean; typecheck clean; 6/6 tests; build OK; runtime audit 0 |
| Live smoke test | PASSED after every stack change in this audit |

## Gaps before any real deployment

| Area | Gap |
|---|---|
| Topology | single host, shared disk, one Kafka broker: no independent failure domains |
| Transport | plain HTTP between all components; TLS is required for any multi-host deployment |
| Database | the app connects as the Postgres superuser created by the image; it needs a least-privilege role |
| Frontend | only runs through the Vite dev server; it needs a production build served behind TLS with its own CSP |
| Accounts | no password change, MFA, or user disable/delete; one bootstrap admin password from the environment |
| Abuse | no per-user storage quota (a USER can fill the disk up to the rate limit); rate limits are in memory per instance |
| Operations | Grafana dashboards not provisioned; monitoring profile unexercised; no alerting; CI not yet run on GitHub |
| Testing | no load test beyond the benchmarks; no multi-host fault tests; Vitest 5 upgrade pending |

## Audit leftovers on the local stack (not in git)

- The `audit-user` USER account. Its credentials are in `.bv-test-accounts`,
  which is git-ignored and readable only by you. There is no delete API.
- The object `user-file.bin` owned by `audit-user`.
- 3 DLQ entries from the earlier malformed-message test.
- `backups/bytevault-20261105T075757Z.dump` and the earlier backup.
