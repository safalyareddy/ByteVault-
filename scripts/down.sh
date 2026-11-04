#!/usr/bin/env bash
# Stops and removes containers. KEEPS all volumes (metadata, Kafka log, node chunk data).
source "$(dirname "$0")/lib.sh"
require_env_file
compose --profile monitoring --profile spare down
echo "Stopped. Volumes preserved. To wipe data use scripts/reset-volumes.sh (destructive)."
