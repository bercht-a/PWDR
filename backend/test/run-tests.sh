#!/usr/bin/env bash
# Startet eine lokale Postgres-Datenbank mit PostGIS in Docker, spielt Stubs, Migration und Tests ein.
# Aufruf: backend/test/run-tests.sh   (Docker muss laufen)
set -euo pipefail
cd "$(dirname "$0")"

NAME=pwdr-pg-test
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -e POSTGRES_PASSWORD=test postgis/postgis:16-3.4 >/dev/null
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT

echo "Warte auf Postgres ..."
for i in $(seq 1 60); do
  if docker exec "$NAME" pg_isready -U postgres -d postgres >/dev/null 2>&1 \
     && docker exec "$NAME" psql -U postgres -d postgres -tAc "select 1" >/dev/null 2>&1; then
    sleep 2   # Der Entrypoint startet nach der Initialisierung neu
    docker exec "$NAME" psql -U postgres -d postgres -tAc "select 1" >/dev/null 2>&1 && break
  fi
  sleep 1
done

run() { docker exec -i "$NAME" psql -U postgres -d postgres -v ON_ERROR_STOP=1 -q < "$1"; }
run 00_supabase_stubs.sql
run ../supabase/migrations/0001_social.sql
run ../supabase/migrations/0002_live_latlon.sql
run 10_rls_tests.sql
