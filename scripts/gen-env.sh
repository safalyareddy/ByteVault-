#!/usr/bin/env bash
# Creates .env with freshly generated local secrets. Never overwrites an existing .env.
source "$(dirname "$0")/lib.sh"

if [[ -f "$ENV_FILE" ]]; then
  echo ".env already exists; leaving it untouched." >&2
  exit 0
fi
secret() { openssl rand -hex 32; }
umask 077
sed -e "s/^DATABASE_PASSWORD=.*/DATABASE_PASSWORD=$(secret)/" \
    -e "s/^BV_NODE_1_SECRET=.*/BV_NODE_1_SECRET=$(secret)/" \
    -e "s/^BV_NODE_2_SECRET=.*/BV_NODE_2_SECRET=$(secret)/" \
    -e "s/^BV_NODE_3_SECRET=.*/BV_NODE_3_SECRET=$(secret)/" \
    -e "s/^BV_NODE_4_SECRET=[^ ]*/BV_NODE_4_SECRET=$(secret)/" \
    -e "s/^GRAFANA_ADMIN_PASSWORD=.*/GRAFANA_ADMIN_PASSWORD=$(secret)/" \
    -e "s/^BV_ADMIN_TOKEN=.*/BV_ADMIN_TOKEN=$(secret)/" \
    "$ROOT/.env.example" > "$ENV_FILE"
echo "Wrote $ENV_FILE (mode 600) with random secrets."
