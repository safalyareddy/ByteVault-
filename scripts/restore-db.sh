#!/usr/bin/env bash
# DESTRUCTIVE: replaces the metadata database with a backup. Metadata written after the backup
# is lost. Afterwards run reconciliation (dry run first):
#   curl -s -X POST -H "Authorization: Bearer $(cat .bv-token)" '127.0.0.1:8090/api/v1/admin/reconcile' | jq
#   curl -s -X POST -H "Authorization: Bearer $(cat .bv-token)" '127.0.0.1:8090/api/v1/admin/reconcile?apply=true' | jq
source "$(dirname "$0")/lib.sh"
require_env_file
require_docker
dump="${1:-}"
[[ -f "$dump" ]] || { echo "usage: $0 backups/<file>.dump" >&2; exit 2; }
echo "This REPLACES the bytevault database with $dump. Metadata newer than the backup is lost."
read -r -p "Type 'RESTORE BYTEVAULT METADATA' to continue: " answer
[[ "$answer" == "RESTORE BYTEVAULT METADATA" ]] || { echo "Aborted; nothing changed."; exit 1; }
compose stop backend
compose exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" --if-exists bytevault && createdb -U "$POSTGRES_USER" bytevault'
compose exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d bytevault --no-owner' < "$dump"
compose start backend
echo "Restored. Wait for /api/v1/health, log in again (tokens issued after the backup are gone), then reconcile."
