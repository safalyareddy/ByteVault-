#!/usr/bin/env bash
# Restarts a stopped storage node. Expected: OFFLINE -> JOINING -> HEALTHY (admission), then its
# LOST replicas are re-verified if still needed or deleted if the chunk was already repaired.
source "$(dirname "$0")/../lib.sh"
require_env_file
node="${1:-}"
[[ "$node" =~ ^node-[1-4]$ ]] || { echo "usage: $0 node-N" >&2; exit 2; }
compose start "$node"
