#!/usr/bin/env bash
set -euo pipefail

backend_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${POSTGRES_JDBC_JAR:?Set POSTGRES_JDBC_JAR to PostgreSQL JDBC driver JAR}"
scratch="$(mktemp -d /tmp/mp4-share-closure-race.XXXXXX)"
pg_started=false
cleanup() {
  if [[ "$pg_started" == true ]]; then pg_ctl -D "$scratch/data" -m fast -w stop >/dev/null; fi
  rm -rf "$scratch"
}
trap cleanup EXIT

port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
MP4_TEST_DB_PASSWORD="$(openssl rand -hex 24)"
export MP4_TEST_DB_PASSWORD PGPASSWORD="$MP4_TEST_DB_PASSWORD"
printf '%s\n' "$MP4_TEST_DB_PASSWORD" > "$scratch/pw"
initdb -D "$scratch/data" -U mp4_fixture --auth=scram-sha-256 --pwfile="$scratch/pw" --no-locale --encoding=UTF8 >/dev/null
rm -f "$scratch/pw"
pg_ctl -D "$scratch/data" -l "$scratch/postgres.log" -o "-h 127.0.0.1 -p $port -k $scratch" -w start
pg_started=true
export PGHOST=127.0.0.1 PGPORT="$port" PGUSER=mp4_fixture PGDATABASE=postgres
createdb mp4_share_closure_race_test
export PGDATABASE=mp4_share_closure_race_test
export MP4_TEST_DATABASE_URI="postgresql://mp4_fixture:$MP4_TEST_DB_PASSWORD@127.0.0.1:$port/mp4_share_closure_race_test"
export MP4_TEST_JDBC_URL="jdbc:postgresql://127.0.0.1:$port/mp4_share_closure_race_test?user=mp4_fixture&password=$MP4_TEST_DB_PASSWORD"

psql "$MP4_TEST_DATABASE_URI" -v ON_ERROR_STOP=1 -f "$backend_dir/test/checklist-share-fixture.sql"
mkdir -p "$scratch/classes"
javac --release 17 -cp "$POSTGRES_JDBC_JAR" -d "$scratch/classes" \
  "$backend_dir/src/ChecklistDraftStore.java" \
  "$backend_dir/src/ChecklistShareService.java" \
  "$backend_dir/src/JdbcChecklistShareService.java" \
  "$backend_dir/test/ChecklistShareClosureRaceRegressionTest.java"
java -cp "$scratch/classes:$POSTGRES_JDBC_JAR" mp4.developer.ChecklistShareClosureRaceRegressionTest \
  "$MP4_TEST_JDBC_URL"
