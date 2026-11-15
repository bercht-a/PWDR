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
# Der Entrypoint startet Postgres zweimal (Initialisierung, dann Betrieb). Erst nach der zweiten Meldung ist es stabil.
for i in $(seq 1 90); do
  [ "$(docker logs "$NAME" 2>&1 | grep -c 'ready to accept connections')" -ge 2 ] && break
  sleep 1
done

# Eigene Datenbank ohne vorinstalliertes PostGIS, damit die Erweiterungen wie in Supabase im Schema "extensions" liegen
for i in $(seq 1 60); do
  docker exec "$NAME" psql -U postgres -d postgres -q -c "create database supa" >/dev/null 2>&1 && break
  sleep 1
done
run() { docker exec -i "$NAME" psql -U postgres -d supa -v ON_ERROR_STOP=1 -q < "$1"; }
run 00_supabase_stubs.sql
run ../supabase/migrations/0001_social.sql
run ../supabase/migrations/0002_live_latlon.sql
run ../supabase/migrations/0003_search_path_extensions.sql
run 10_rls_tests.sql
