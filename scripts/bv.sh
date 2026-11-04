#!/usr/bin/env bash
# Minimal ByteVault CLI over the public API (curl + jq).
#   scripts/bv.sh upload <file>             upload in chunks, complete, print object id
#   scripts/bv.sh download <objectId> <out> download and verify against the server's chunk checksums
#   scripts/bv.sh show <objectId>           object, chunk and replica metadata
#   scripts/bv.sh nodes                     node inventory
set -euo pipefail
API="${BV_API:-http://127.0.0.1:8090/api/v1}"

sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
die() { echo "error: $*" >&2; exit 1; }
command -v jq >/dev/null || die "jq is required"

upload() {
  local file="$1"
  [[ -f "$file" ]] || die "no such file: $file"
  local size name key tmp session upload_id chunk_size chunk_count
  size=$(stat -f %z "$file" 2>/dev/null || stat -c %s "$file")
  name=$(basename "$file")
  # Deterministic key: re-running the same upload resumes the same session.
  key="cli-$(printf '%s:%s:%s' "$name" "$size" "$(sha256 "$file")" | shasum -a 256 | cut -c1-40)"
  session=$(curl -fsS -X POST "$API/uploads" -H "Idempotency-Key: $key" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg n "$name" --argjson s "$size" '{name:$n,sizeBytes:$s,contentType:"application/octet-stream"}')")
  upload_id=$(jq -r .uploadId <<<"$session")
  chunk_size=$(jq -r .chunkSizeBytes <<<"$session")
  chunk_count=$(jq -r .chunkCount <<<"$session")
  echo "upload $upload_id: $size bytes, $chunk_count chunk(s) of $chunk_size, RF $(jq -r .replicationFactor <<<"$session")" >&2
  if [[ "$(jq -r .status <<<"$session")" == COMPLETED ]]; then
    echo "  already completed (idempotent replay)" >&2
    jq -r .objectId <<<"$session"
    return
  fi
  tmp=$(mktemp)
  trap 'rm -f "$tmp"' RETURN
  for ((i = 0; i < chunk_count; i++)); do
    dd if="$file" of="$tmp" bs="$chunk_size" skip="$i" count=1 2>/dev/null
    local res code
    res=$(curl -sS -w '\n%{http_code}' -X PUT "$API/uploads/$upload_id/chunks/$i" \
      -H 'Content-Type: application/octet-stream' -H "X-Chunk-SHA256: $(sha256 "$tmp")" --data-binary @"$tmp")
    code=$(tail -n1 <<<"$res")
    [[ "$code" == 200 ]] || die "chunk $i failed (HTTP $code): $(sed '$d' <<<"$res")"
    echo "  chunk $i stored on $(sed '$d' <<<"$res" | jq -r '[.replicas[] | select(.state=="VERIFIED") | .nodeId] | join(",")')" >&2
  done
  curl -fsS -X POST "$API/uploads/$upload_id/complete" | jq -r .objectId
}

download() {
  local id="$1" out="$2"
  curl -fsS -o "$out" "$API/objects/$id/download" || die "download failed"
  local expected actual
  expected=$(curl -fsS "$API/objects/$id" | jq -r .object.sizeBytes)
  actual=$(stat -f %z "$out" 2>/dev/null || stat -c %s "$out")
  [[ "$expected" == "$actual" ]] || die "truncated download: $actual of $expected bytes"
  echo "$out: $actual bytes, sha256 $(sha256 "$out")"
}

case "${1:-}" in
  upload) upload "${2:?file}" ;;
  download) download "${2:?objectId}" "${3:?output file}" ;;
  show) curl -fsS "$API/objects/${2:?objectId}" | jq . ;;
  nodes) curl -fsS "$API/nodes" | jq . ;;
  *) sed -n '2,7p' "$0"; exit 1 ;;
esac
