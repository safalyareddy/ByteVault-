#!/usr/bin/env bash
# DESTRUCTIVE: deletes ALL ByteVault volumes (PostgreSQL metadata, Kafka log, every node's chunks).
source "$(dirname "$0")/lib.sh"
require_env_file
echo "This permanently deletes volumes: pgdata, kafkadata, node1-data, node2-data, node3-data, node4-data"
read -r -p "Type 'DELETE BYTEVAULT DATA' to continue: " answer
if [[ "$answer" != "DELETE BYTEVAULT DATA" ]]; then
  echo "Aborted; nothing deleted."
  exit 1
fi
compose --profile monitoring --profile spare down -v
echo "Volumes deleted."
