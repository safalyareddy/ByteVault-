#!/usr/bin/env python3
"""ByteVault smoke test and benchmark harness. Standard library only.

  python3 -I scripts/bvtool.py smoke            # login, upload, verify replicas, download, compare, delete
  python3 -I scripts/bvtool.py bench            # throughput/latency runs; raw JSON -> benchmarks/raw/
  python3 -I scripts/bvtool.py bench-repair     # node-loss repair timing (needs --profile spare, 4 nodes)

Credentials: logs in as BV_BENCH_USER (default 'admin') with BV_BENCH_PASSWORD if set, otherwise
reads BV_BOOTSTRAP_ADMIN_PASSWORD from .env. The password is never printed or written anywhere.
"""
import hashlib
import json
import os
import platform
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
API = os.environ.get("BV_API", "http://127.0.0.1:8090/api/v1")
# docker compose honours COMPOSE_PROJECT_NAME from the environment (used by the fresh-clone check).
COMPOSE = ["docker", "compose", "-f", str(ROOT / "infra/compose/docker-compose.yml"), "--env-file", str(ROOT / ".env")]


class ApiError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {body[:300]}")
        self.status = status


def env_value(key):
    try:
        for line in (ROOT / ".env").read_text().splitlines():
            if line.startswith(key + "="):
                return line.split("=", 1)[1].split(" #")[0].strip()
    except FileNotFoundError:
        pass
    return None


class Client:
    def __init__(self):
        self.token = None

    def call(self, method, path, body=None, raw=None, headers=None, timeout=300):
        h = {"Accept": "application/json", **(headers or {})}
        data = raw
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        if self.token:
            h["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(API + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as res:
                payload = res.read()
                ctype = res.headers.get("Content-Type", "")
                return json.loads(payload) if payload and "json" in ctype else payload
        except urllib.error.HTTPError as e:
            raise ApiError(e.code, e.read().decode(errors="replace")) from None

    def login(self):
        user = os.environ.get("BV_BENCH_USER", "admin")
        password = os.environ.get("BV_BENCH_PASSWORD") or env_value("BV_BOOTSTRAP_ADMIN_PASSWORD")
        if not password:
            sys.exit("no password: set BV_BENCH_PASSWORD or BV_BOOTSTRAP_ADMIN_PASSWORD in .env")
        self.token = self.call("POST", "/auth/login", {"username": user, "password": password})["token"]
        del password

    def upload(self, data, name):
        t0 = time.perf_counter()
        session = self.call("POST", "/uploads", {"name": name, "sizeBytes": len(data)},
                            headers={"Idempotency-Key": "bench-" + uuid.uuid4().hex})
        size = session["chunkSizeBytes"]
        for i in range(session["chunkCount"]):
            part = data[i * size:(i + 1) * size]
            self.call("PUT", f"/uploads/{session['uploadId']}/chunks/{i}", raw=part,
                      headers={"Content-Type": "application/octet-stream",
                               "X-Chunk-SHA256": hashlib.sha256(part).hexdigest()})
        done = self.call("POST", f"/uploads/{session['uploadId']}/complete")
        return done["objectId"], time.perf_counter() - t0

    def download(self, object_id):
        t0 = time.perf_counter()
        body = self.call("GET", f"/objects/{object_id}/download", headers={"Accept": "*/*"})
        return body, time.perf_counter() - t0


def pct(values, p):
    s = sorted(values)
    if not s:
        return None
    k = (len(s) - 1) * p / 100
    lo, hi = int(k), min(int(k) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


# --------------------------------------------------------------------------------------------- smoke

def smoke():
    c = Client()
    c.login()
    data = os.urandom(9 * 1024 * 1024 + 12345)
    oid, up = c.upload(data, "smoke-test.bin")
    detail = c.call("GET", f"/objects/{oid}")
    holders = [{r["nodeId"] for r in ch["replicas"] if r["state"] == "VERIFIED"} for ch in detail["chunks"]]
    ok_replicas = all(len(h) == detail["object"]["replicationFactor"] for h in holders)
    body, down = c.download(oid)
    match = hashlib.sha256(body).hexdigest() == hashlib.sha256(data).hexdigest()
    c.call("DELETE", f"/objects/{oid}")
    print(json.dumps({"object": oid, "bytes": len(data), "chunks": len(holders),
                      "allChunksFullyReplicatedOnDistinctNodes": ok_replicas, "downloadSha256Matches": match,
                      "uploadSeconds": round(up, 3), "downloadSeconds": round(down, 3)}, indent=2))
    if not (ok_replicas and match):
        sys.exit("SMOKE TEST FAILED")
    print("SMOKE TEST PASSED")


# --------------------------------------------------------------------------------------------- bench

class StatsSampler(threading.Thread):
    """Samples `docker stats` for the ByteVault containers while a benchmark runs."""

    def __init__(self):
        super().__init__(daemon=True)
        self.samples = []
        self.stop = threading.Event()

    def run(self):
        while not self.stop.is_set():
            try:
                out = subprocess.run(["docker", "stats", "--no-stream", "--format",
                                      "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}"],
                                     capture_output=True, text=True, timeout=20).stdout
                t = time.time()
                for line in out.splitlines():
                    name, cpu, mem = line.split("|")
                    if "bytevault" in name:
                        self.samples.append({"t": t, "name": name, "cpu": float(cpu.rstrip("%") or 0),
                                             "mem": mem.split("/")[0].strip()})
            except Exception:  # noqa: BLE001 - sampling is best effort and must not break the run
                pass
            self.stop.wait(2)


def mem_mib(text):
    num = float("".join(ch for ch in text if ch.isdigit() or ch == "."))
    return num * (1024 if "GiB" in text else 1 if "MiB" in text else 1 / 1024)


def bench():
    c = Client()
    c.login()
    MiB = 1024 * 1024
    plan = [  # (label, size, iterations per run, concurrency)
        ("1MiB-c1", 1 * MiB, 10, 1),
        ("16MiB-c1", 16 * MiB, 4, 1),
        ("64MiB-c1", 64 * MiB, 2, 1),
        ("1MiB-c4", 1 * MiB, 12, 4),
    ]
    runs = int(os.environ.get("BV_BENCH_RUNS", "3"))
    sampler = StatsSampler()
    sampler.start()
    results = []
    started = datetime.now(timezone.utc)
    for label, size, iterations, concurrency in plan:
        for run in range(1, runs + 1):
            payloads = [os.urandom(size) for _ in range(iterations)]
            t0 = time.perf_counter()
            with ThreadPoolExecutor(concurrency) as pool:
                uploads = list(pool.map(lambda d: c.upload(d, f"bench-{label}.bin"), payloads))
            up_wall = time.perf_counter() - t0
            t1 = time.perf_counter()
            with ThreadPoolExecutor(concurrency) as pool:
                downloads = list(pool.map(lambda u: c.download(u[0]), uploads))
            down_wall = time.perf_counter() - t1
            verified = all(hashlib.sha256(body).digest() == hashlib.sha256(src).digest()
                           for (body, _), src in zip(downloads, payloads))
            lags = []
            for oid, _ in uploads:
                for e in c.call("GET", f"/events?aggregateId={oid}&limit=20"):
                    lags.append((datetime.fromisoformat(e["receivedAt"]) - datetime.fromisoformat(e["occurredAt"]))
                                .total_seconds())
            for oid, _ in uploads:
                c.call("DELETE", f"/objects/{oid}")
            total = size * iterations
            results.append({
                "label": label, "run": run, "sizeBytes": size, "iterations": iterations, "concurrency": concurrency,
                "allDownloadsVerified": verified,
                "uploadLatency": [round(t, 4) for _, t in uploads],
                "downloadLatency": [round(t, 4) for _, t in downloads],
                "uploadMiBps": round(total / MiB / up_wall, 2), "downloadMiBps": round(total / MiB / down_wall, 2),
                "eventLagSeconds": lags,
            })
            print(f"{label} run {run}: upload {results[-1]['uploadMiBps']} MiB/s, "
                  f"download {results[-1]['downloadMiBps']} MiB/s, verified={verified}", flush=True)
            time.sleep(2)
    sampler.stop.set()
    sampler.join(timeout=30)
    raw = {"startedAt": started.isoformat(), "host": host_info(), "config": compose_config(), "runs": results,
           "dockerStats": sampler.samples}
    out = ROOT / "benchmarks" / "raw" / f"bench-{started.strftime('%Y%m%dT%H%M%SZ')}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(raw, indent=1))
    print(f"raw results: {out}")
    summarise(raw)


def summarise(raw):
    print("\n| Workload | Runs | Upload MiB/s (per run) | Download MiB/s (per run) | Upload p50 / p95 s | "
          "Download p50 / p95 s | Event lag p50 / p95 s | All verified |")
    print("|---|---|---|---|---|---|---|---|")
    labels = []
    for r in raw["runs"]:
        if r["label"] not in labels:
            labels.append(r["label"])
    for label in labels:
        rs = [r for r in raw["runs"] if r["label"] == label]
        up = [x for r in rs for x in r["uploadLatency"]]
        down = [x for r in rs for x in r["downloadLatency"]]
        lag = [x for r in rs for x in r["eventLagSeconds"]]
        print(f"| {label} | {len(rs)} | {', '.join(str(r['uploadMiBps']) for r in rs)} | "
              f"{', '.join(str(r['downloadMiBps']) for r in rs)} | {pct(up, 50):.3f} / {pct(up, 95):.3f} | "
              f"{pct(down, 50):.3f} / {pct(down, 95):.3f} | {pct(lag, 50):.2f} / {pct(lag, 95):.2f} | "
              f"{all(r['allDownloadsVerified'] for r in rs)} |")
    by_name = {}
    for s in raw["dockerStats"]:
        by_name.setdefault(s["name"], []).append(s)
    print("\n| Container | CPU % p50 / max | Memory MiB max | Samples |")
    print("|---|---|---|---|")
    for name in sorted(by_name):
        ss = by_name[name]
        cpu = [s["cpu"] for s in ss]
        print(f"| {name} | {pct(cpu, 50):.1f} / {max(cpu):.1f} | {max(mem_mib(s['mem']) for s in ss):.0f} | {len(ss)} |")


def host_info():
    def sh(cmd):
        try:
            return subprocess.run(cmd, capture_output=True, text=True, timeout=20).stdout.strip()
        except Exception:  # noqa: BLE001
            return None
    return {"platform": platform.platform(), "model": sh(["sysctl", "-n", "hw.model"]),
            "cpu": sh(["sysctl", "-n", "machdep.cpu.brand_string"]), "cores": os.cpu_count(),
            "memBytes": sh(["sysctl", "-n", "hw.memsize"]),
            "docker": sh(["docker", "info", "--format", "{{.ServerVersion}} cpus={{.NCPU}} mem={{.MemTotal}}"])}


def compose_config():
    keys = ["BV_CHUNK_SIZE_BYTES", "BV_REPLICATION_FACTOR", "BV_MAX_CONCURRENT_CHUNK_TRANSFERS"]
    return {k: env_value(k) for k in keys}


# ------------------------------------------------------------------------------------- bench-repair

def bench_repair():
    """Stops the node holding the most replicas, times detection and repair, restarts it. 3 runs."""
    c = Client()
    c.login()
    runs = int(os.environ.get("BV_BENCH_RUNS", "3"))
    payload = os.urandom(16 * 1024 * 1024)
    oid, _ = c.upload(payload, "bench-repair.bin")
    results = []
    for run in range(1, runs + 1):
        nodes = {n["id"]: n for n in c.call("GET", "/nodes")}
        if sum(1 for n in nodes.values() if n["state"] == "HEALTHY") < 4:
            sys.exit("bench-repair needs 4 HEALTHY nodes (scripts/up.sh --profile spare)")
        detail = c.call("GET", f"/objects/{oid}")
        counts = {}
        for ch in detail["chunks"]:
            for r in ch["replicas"]:
                if r["state"] == "VERIFIED":
                    counts[r["nodeId"]] = counts.get(r["nodeId"], 0) + 1
        victim = max(counts, key=counts.get)
        t0 = time.time()
        subprocess.run(COMPOSE + ["stop", victim], capture_output=True, check=True)
        t_offline = t_complete = None
        while time.time() - t0 < 300:
            state = {n["id"]: n["state"] for n in c.call("GET", "/nodes")}[victim]
            status = c.call("GET", f"/objects/{oid}")["object"]["status"]
            if state == "OFFLINE" and t_offline is None:
                t_offline = time.time()
            if t_offline and status == "COMPLETE":
                t_complete = time.time()
                break
            time.sleep(0.5)
        body, _ = c.download(oid)
        verified = hashlib.sha256(body).digest() == hashlib.sha256(payload).digest()
        subprocess.run(COMPOSE + ["start", victim], capture_output=True, check=True)
        while {n["id"]: n["state"] for n in c.call("GET", "/nodes")}[victim] != "HEALTHY":
            time.sleep(1)
        results.append({"run": run, "victim": victim, "replicasOnVictim": counts[victim],
                        "detectToOfflineSeconds": round(t_offline - t0, 1) if t_offline else None,
                        "offlineToCompleteSeconds": round(t_complete - t_offline, 2) if t_complete else None,
                        "downloadVerified": verified})
        print(json.dumps(results[-1]), flush=True)
        time.sleep(10)  # let rejoin reconciliation finish before the next run
    c.call("DELETE", f"/objects/{oid}")
    out = ROOT / "benchmarks" / "raw" / f"repair-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"host": host_info(), "runs": results}, indent=1))
    print(f"raw results: {out}")


if __name__ == "__main__":
    commands = {"smoke": smoke, "bench": bench, "bench-repair": bench_repair}
    if len(sys.argv) != 2 or sys.argv[1] not in commands:
        sys.exit(__doc__)
    commands[sys.argv[1]]()
