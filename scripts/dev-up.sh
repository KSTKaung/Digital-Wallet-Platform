#!/usr/bin/env bash
# Start the local stack (postgres, backend, frontend) and wait until every service is healthy.
#
# Prerequisites:
#   - Docker Desktop (or Docker Engine) running, with Compose v2 (`docker compose version`)
#   - Bash: Git Bash on Windows, any shell on macOS/Linux
#   - .env in the repository root, created with `cp .env.example .env` and your own DB_PASSWORD
#     (set POSTGRES_HOST_PORT if a local PostgreSQL already uses 5432)
#
# Usage: scripts/dev-up.sh [--mail]
#   --mail   also start Mailpit (SMTP localhost:1025, UI http://localhost:8025)
set -euo pipefail

cd "$(dirname "$0")/.."

case "${1:-}" in
  "") profiles=() ;;
  --mail) profiles=(--profile mail) ;;
  -h | --help) sed -n '2,12p' "$0"; exit 0 ;;
  *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
esac

if ! docker info >/dev/null 2>&1; then
  echo "Docker engine is not reachable. Start Docker Desktop and try again." >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  echo "Missing .env. Create it from the template and set your own local values:" >&2
  echo "  cp .env.example .env" >&2
  exit 1
fi

if ! docker compose "${profiles[@]}" up -d --build --wait --wait-timeout 240; then
  echo "The stack did not become healthy. Recent backend logs:" >&2
  docker compose logs --tail=50 backend >&2
  exit 1
fi

echo "Frontend: http://localhost:5173"
echo "Backend:  http://localhost:8080 (OpenAPI at /v3/api-docs with the local profile)"
if [[ ${#profiles[@]} -gt 0 ]]; then
  echo "Mailpit:  http://localhost:8025"
fi
