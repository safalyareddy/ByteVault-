#!/usr/bin/env bash
# Read-only view of container state and every service's health endpoint.
source "$(dirname "$0")/lib.sh"
require_env_file
compose ps
echo
port="$(grep -E '^BV_BACKEND_HOST_PORT=' "$ENV_FILE" | cut -d= -f2 || true)"
echo "control plane: $(curl -sS "http://127.0.0.1:${port:-8090}/api/v1/health" 2>&1)"
for p in 9101 9102 9103; do
  echo "node :$p      $(curl -sS "http://127.0.0.1:$p/internal/v1/health" 2>&1)"
done
