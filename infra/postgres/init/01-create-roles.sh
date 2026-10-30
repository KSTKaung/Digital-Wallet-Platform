#!/usr/bin/env bash
# Creates the two database roles (documentations/06 MVP-ADR-02, amendment D19):
#   wallet_owner  runs Flyway and owns every schema it creates
#   wallet_app    runtime role used by the backend: DML only, owns nothing
# Neither role is a superuser, so neither can bypass the append-only triggers.
#
# Run by the postgres image's entrypoint as the bootstrap superuser, ONLY when the data volume is
# empty (first start). Used by compose.yaml and by the Testcontainers setup in backend tests.
#
# Passwords come from WALLET_OWNER_PASSWORD and WALLET_APP_PASSWORD. They are read inside psql with
# \getenv, so they never appear on a command line or in the output.
#
# The body runs in a subshell: the entrypoint sources *.sh files that are not executable, and the
# shell options set here must not leak into the entrypoint.
main() (
  set -euo pipefail

  : "${WALLET_OWNER_PASSWORD:?WALLET_OWNER_PASSWORD must be set and non-empty}"
  : "${WALLET_APP_PASSWORD:?WALLET_APP_PASSWORD must be set and non-empty}"
  : "${POSTGRES_USER:?POSTGRES_USER must be set}"
  : "${POSTGRES_DB:?POSTGRES_DB must be set}"

  psql -v ON_ERROR_STOP=1 --no-psqlrc --quiet \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v dbname="$POSTGRES_DB" <<'SQL'
\getenv owner_password WALLET_OWNER_PASSWORD
\getenv app_password WALLET_APP_PASSWORD

CREATE ROLE wallet_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'owner_password';
CREATE ROLE wallet_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'app_password';

-- wallet_owner creates the module schemas and the flyway schema (Flyway migrations V1 and later).
GRANT CONNECT, CREATE ON DATABASE :"dbname" TO wallet_owner;
-- wallet_app gets schema USAGE and table DML from the migrations (V1 default privileges).
GRANT CONNECT ON DATABASE :"dbname" TO wallet_app;
SQL

  echo "01-create-roles.sh: roles wallet_owner and wallet_app created"
)

main "$@"
