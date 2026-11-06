#!/usr/bin/env bash
# Fault injection: stop ONE named storage node (container only; its volume and data are kept).
#   scripts/fault/stop-node.sh node-2 --yes
# Expected: node SUSPECT after BV_SUSPECT_AFTER, OFFLINE after BV_OFFLINE_AFTER, its replicas LOST,
# objects DEGRADED; repair restores RF only if another HEALTHY node without a copy exists
# (start the spare with: scripts/up.sh --profile spare). Restart with scripts/fault/start-node.sh.
source "$(dirname "$0")/../lib.sh"
require_env_file
node="${1:-}"; confirm="${2:-}"
[[ "$node" =~ ^node-[1-4]$ ]] || { echo "usage: $0 node-N --yes   (N = 1..4)" >&2; exit 2; }
[[ "$confirm" == "--yes" ]] || { echo "refusing without --yes: this stops $node" >&2; exit 2; }
compose stop "$node"
echo "$node stopped at $(date -u +%H:%M:%SZ). Watch: scripts/bv.sh nodes ; curl -s 127.0.0.1:8090/api/v1/repairs | jq"
