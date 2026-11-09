#!/bin/sh
# Disposable PostgreSQL for Reporter backend tests, run in Docker.
# Usage: run-reporter-db.sh [command ...]
# Starts a fresh postgres:17 container on a random loopback port, applies
# every shared migration (sorted), every Reporter migration (sorted), then the Developer
# checklist migration, exports connection variables, runs the command (default:
# a schema smoke check), and always removes the container.
set -eu
umask 077
base=$(CDPATH= cd -- "$(dirname -- "$0")/../../../.." && pwd)
image=${MP4_TEST_PG_IMAGE:-postgres:17}
name="mp4-reporter-test-$$-$(date +%s)"
started=false
cleanup() {
  if [ "$started" = true ]; then docker rm -f "$name" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT HUP INT TERM

command -v docker >/dev/null 2>&1 || { echo "required tool not found: docker" >&2; exit 2; }
docker version >/dev/null 2>&1 || { echo "docker daemon is not running" >&2; exit 2; }

password=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
docker run -d --name "$name" -e POSTGRES_USER=mp4_fixture -e POSTGRES_PASSWORD="$password" \
  -e POSTGRES_DB=postgres -p 127.0.0.1::5432 "$image" >/dev/null
started=true

i=0
until docker exec "$name" pg_isready -U mp4_fixture -d postgres -h 127.0.0.1 >/dev/null 2>&1; do
  i=$((i + 1)); [ "$i" -le 60 ] || { echo "postgres did not become ready" >&2; docker logs "$name" >&2; exit 1; }
  sleep 1
done

db=mp4_reporter_test
psql_in() { docker exec -i "$name" psql -v ON_ERROR_STOP=1 -q -U mp4_fixture -d "$1"; }
echo "CREATE DATABASE $db;" | psql_in postgres

apply() { echo "apply $1"; psql_in "$db" < "$base/$1"; }
for m in "$base"/src/backend/shared/migrations/*.sql "$base"/src/backend/reporter/migrations/*.sql; do
  [ -e "$m" ] || continue
  apply "${m#"$base"/}"
done
apply src/backend/developer/migrations/create_investigation_checklists.sql

port=$(docker port "$name" 5432/tcp | head -n1 | sed 's/.*://')
export MP4_TEST_DATABASE_NAME="$db"
export MP4_TEST_DATABASE_URI="postgresql://mp4_fixture:$password@127.0.0.1:$port/$db"
export MP4_TEST_JDBC_URL="jdbc:postgresql://127.0.0.1:$port/$db?user=mp4_fixture&password=$password"
export MP4_TEST_PG_CONTAINER="$name"

if [ "$#" -eq 0 ]; then
  echo "SELECT count(*) AS reports_rows FROM reports; SELECT to_regclass('investigation_checklists') AS checklist_table;" | psql_in "$db"
else
  "$@"
fi
