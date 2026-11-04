# Shared helpers for ByteVault scripts. Source, don't execute.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/infra/compose/docker-compose.yml"
ENV_FILE="$ROOT/.env"

compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

require_env_file() {
  if [[ ! -f "$ENV_FILE" ]]; then
    echo "Missing $ENV_FILE. Run scripts/gen-env.sh (or copy .env.example and fill in secrets)." >&2
    exit 1
  fi
}

require_docker() {
  if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon is not reachable. Start or restart Docker Desktop and retry." >&2
    exit 1
  fi
}
