#!/usr/bin/env bash
# Non-destructive: writes a compressed custom-format dump of the metadata database to backups/.
# Chunk bytes live on node volumes and are NOT in this backup (see docs/operations-runbook.md).
source "$(dirname "$0")/lib.sh"
require_env_file
require_docker
mkdir -p "$ROOT/backups"
out="$ROOT/backups/bytevault-$(date -u +%Y%m%dT%H%M%SZ).dump"
compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d bytevault -Fc' > "$out"
[[ -s "$out" ]] || { rm -f "$out"; echo "backup failed" >&2; exit 1; }
chmod 600 "$out"
echo "$out ($(wc -c < "$out" | tr -d ' ') bytes)"
