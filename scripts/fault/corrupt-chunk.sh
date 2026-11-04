#!/usr/bin/env bash
# DESTRUCTIVE fault injection: flips one byte of ONE chunk replica on ONE node.
# Guarded: the object's name must start with "disposable-", and you must type the chunk id back.
#   scripts/fault/corrupt-chunk.sh <objectId> <chunkIndex> <node-N>
# Expected: the next scrub (or a download) marks that replica CORRUPT, the object DEGRADED, and
# repair restores a verified copy. Trigger a scrub now with:
#   curl -s -X POST -H "Authorization: Bearer $(cat .bv-token)" 127.0.0.1:8090/api/v1/admin/scrub | jq   (after scripts/bv.sh login)
source "$(dirname "$0")/../lib.sh"
require_env_file
object="${1:-}"; index="${2:-}"; node="${3:-}"
[[ "$object" =~ ^[0-9a-f-]{36}$ && "$index" =~ ^[0-9]+$ && "$node" =~ ^node-[1-4]$ ]] \
  || { echo "usage: $0 <objectId> <chunkIndex> <node-N>" >&2; exit 2; }
[[ -f "$ROOT/.bv-token" ]] || { echo "log in first: scripts/bv.sh login" >&2; exit 1; }
detail=$(curl -fsS -H "Authorization: Bearer $(cat "$ROOT/.bv-token")" "http://127.0.0.1:${BV_BACKEND_HOST_PORT:-8090}/api/v1/objects/$object") || { echo "object not found" >&2; exit 1; }
name=$(jq -r .object.name <<<"$detail")
[[ "$name" == disposable-* ]] || { echo "refusing: object name '$name' does not start with 'disposable-'" >&2; exit 1; }
chunk=$(jq -r --argjson i "$index" '.chunks[] | select(.index == $i) | .chunkId' <<<"$detail")
[[ -n "$chunk" ]] || { echo "no chunk $index" >&2; exit 1; }
jq -e --arg n "$node" --argjson i "$index" '.chunks[] | select(.index == $i) | .replicas[] | select(.nodeId == $n and .state == "VERIFIED")' <<<"$detail" >/dev/null \
  || { echo "$node holds no VERIFIED replica of chunk $index" >&2; exit 1; }
echo "About to corrupt chunk $index ($chunk) of '$name' on $node."
read -r -p "Type the chunk id to confirm: " answer
[[ "$answer" == "$chunk" ]] || { echo "Aborted; nothing changed."; exit 1; }
path="/var/lib/bytevault/node/chunks/${chunk:0:2}/${chunk:2:2}/$chunk.chunk"
compose exec -T "$node" sh -c "printf 'Z' | dd of='$path' bs=1 seek=0 count=1 conv=notrunc 2>/dev/null"
echo "Corrupted 1 byte of $path on $node."
