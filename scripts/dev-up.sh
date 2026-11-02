#!/usr/bin/env bash
# Start the local stack (postgres, backend, frontend, Mailpit) and wait until every service is healthy.
#
# Prerequisites:
#   - Docker Desktop (or Docker Engine) running, with Compose v2 (`docker compose version`)
#   - Bash: Git Bash on Windows, any shell on macOS/Linux
#   - .env in the repository root, created with `cp .env.example .env` and your own
#     POSTGRES_SUPERUSER_PASSWORD, WALLET_OWNER_PASSWORD and WALLET_APP_PASSWORD (set
#     POSTGRES_HOST_PORT if a local PostgreSQL already uses 5432), plus JWT_PRIVATE_KEY, JWT_KID,
#     OTP_HMAC_SECRET and REFRESH_TOKEN_PEPPER from scripts/generate-dev-secrets.sh
#
# Usage: scripts/dev-up.sh
set -euo pipefail

cd "$(dirname "$0")/.."

case "${1:-}" in
  "") ;;
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
  echo "  scripts/generate-dev-secrets.sh    (paste its output over the placeholders)" >&2
  exit 1
fi

if ! docker compose up -d --build --wait --wait-timeout 240; then
  echo "The stack did not become healthy. Recent backend logs:" >&2
  docker compose logs --tail=50 backend >&2
  exit 1
fi

echo "Frontend: http://localhost:5173"
echo "Backend:  http://localhost:8080 (OpenAPI at /v3/api-docs with the local profile)"
echo "Mailpit:  http://localhost:8025"
