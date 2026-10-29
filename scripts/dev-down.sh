#!/usr/bin/env bash
# Stop the local stack. Data in the PostgreSQL volume is kept unless --purge is given.
#
# Prerequisites: Docker with Compose v2; Bash (Git Bash on Windows).
#
# Usage: scripts/dev-down.sh [--purge]
#   --purge   also delete the database volume (asks for confirmation; cannot be undone)
set -euo pipefail

cd "$(dirname "$0")/.."

case "${1:-}" in
  "")
    docker compose --profile mail down
    ;;
  --purge)
    read -r -p "This deletes ALL local database data (volume pgdata). Type 'purge' to continue: " answer
    if [[ "$answer" != "purge" ]]; then
      echo "Aborted; nothing was deleted."
      exit 1
    fi
    docker compose --profile mail down --volumes
    ;;
  -h | --help) sed -n '2,8p' "$0" ;;
  *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
esac
