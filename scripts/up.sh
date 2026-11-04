#!/usr/bin/env bash
# Starts the local stack (non-destructive) and waits for health. Run scripts/build.sh first.
# Optional profiles: scripts/up.sh --profile spare --profile monitoring
source "$(dirname "$0")/lib.sh"
require_env_file
require_docker
for jar in backend/target/bytevault-backend.jar storage-node/target/bytevault-storage-node.jar; do
  [[ -f "$ROOT/$jar" ]] || { echo "Missing $jar; run scripts/build.sh first." >&2; exit 1; }
done

compose "$@" up -d --build

port="$(grep -E '^BV_BACKEND_HOST_PORT=' "$ENV_FILE" | cut -d= -f2 || true)"
url="http://127.0.0.1:${port:-8090}/api/v1/health"
echo "Waiting for $url ..."
for _ in $(seq 1 60); do
  if curl -fsS "$url" >/dev/null 2>&1; then
    curl -fsS "$url"; echo
    compose ps
    exit 0
  fi
  sleep 3
done
echo "Control plane did not become healthy within 180s. Recent backend logs:" >&2
compose logs --tail=50 backend >&2
exit 1
