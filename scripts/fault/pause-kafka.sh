#!/usr/bin/env bash
# Fault injection: stop or start the Kafka broker (data volume kept).
#   scripts/fault/pause-kafka.sh stop | start
# Expected while stopped: uploads keep working, /api/v1/stats shows outboxBacklog growing and
# consistent=false, health 503; after start the backlog drains and consistent=true.
source "$(dirname "$0")/../lib.sh"
require_env_file
case "${1:-}" in
  stop) compose stop kafka ;;
  start) compose start kafka ;;
  *) echo "usage: $0 stop|start" >&2; exit 2 ;;
esac
