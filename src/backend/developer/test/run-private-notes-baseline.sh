#!/bin/sh
set -eu
umask 077
base=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
dependencies="${POSTGRES_JDBC_JAR:?Set POSTGRES_JDBC_JAR to the PostgreSQL JDBC driver JAR}:${JACKSON_CORE_JAR:?Set JACKSON_CORE_JAR to the Jackson Core JAR}:${JACKSON_DATABIND_JAR:?Set JACKSON_DATABIND_JAR to the Jackson Databind JAR}:${JACKSON_ANNOTATIONS_JAR:?Set JACKSON_ANNOTATIONS_JAR to the Jackson Annotations JAR}"
scratch=$(mktemp -d /tmp/mp4-private-notes.XXXXXX)
pg_started=false
http_pid=''
cleanup() {
  if [ -n "$http_pid" ]; then kill "$http_pid" 2>/dev/null || true; wait "$http_pid" 2>/dev/null || true; fi
  if [ "$pg_started" = true ]; then pg_ctl -D "$scratch/data" -m fast -w stop >/dev/null; fi
  rm -rf -- "$scratch"
}
trap cleanup EXIT
export LC_ALL=C
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
MP4_TEST_DB_PASSWORD=$(openssl rand -hex 24)
export MP4_TEST_DB_PASSWORD PGPASSWORD="$MP4_TEST_DB_PASSWORD"
printf '%s\n' "$MP4_TEST_DB_PASSWORD" > "$scratch/pw"
initdb -D "$scratch/data" -U mp4_fixture --auth=scram-sha-256 --pwfile="$scratch/pw" --no-locale --encoding=UTF8 >/dev/null
rm -- "$scratch/pw"
pg_ctl -D "$scratch/data" -l "$scratch/postgres.log" -o "-h 127.0.0.1 -p $port -k $scratch" -w start
pg_started=true
export PGHOST=127.0.0.1 PGPORT="$port" PGUSER=mp4_fixture PGDATABASE=postgres
createdb mp4_private_notes_test
export PGDATABASE=mp4_private_notes_test
export MP4_TEST_DATABASE_NAME=mp4_private_notes_test
export MP4_TEST_JDBC_URL="jdbc:postgresql://127.0.0.1:$port/mp4_private_notes_test?user=mp4_fixture&password=$MP4_TEST_DB_PASSWORD"
report_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
reporter_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
developer_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
proposal_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
psql -X -v ON_ERROR_STOP=1 -v report_id="$report_id" -v reporter_id="$reporter_id" \
  -v developer_id="$developer_id" -v proposal_id="$proposal_id" \
  -f "$base/test/private-notes-fixture.sql"
psql -X -v ON_ERROR_STOP=1 -f "$base/migrations/create_developer_private_notes.sql"
javac --release 17 --add-modules jdk.httpserver -cp "$dependencies" -d "$scratch/classes" \
  "$base/src/ChecklistDraftStore.java" "$base/src/PrivateNotesService.java" \
  "$base/src/JdbcPrivateNotesService.java" \
  "$base/src/PrivateNotesHttpHandler.java" "$base/test/ChecklistSaveHttpHarness.java" \
  "$base/test/PrivateNotesHttpLauncher.java" "$base/test/PrivateNotesAuthorizationRaceTest.java"
MP4_TEST_DEVELOPER_ID=$developer_id
export MP4_TEST_DEVELOPER_ID
MP4_TEST_SESSION_TOKEN=$(openssl rand -hex 24)
export MP4_TEST_SESSION_TOKEN
java --add-modules jdk.httpserver -cp "$scratch/classes:$dependencies" \
  mp4.developer.PrivateNotesHttpLauncher > "$scratch/origin" &
http_pid=$!
for attempt in $(seq 1 50); do
  if [ -s "$scratch/origin" ]; then break; fi
  kill -0 "$http_pid"
  sleep 0.1
done
export MP4_TEST_ORIGIN=$(cat "$scratch/origin")
export MP4_TEST_SESSION_HEADERS="{\"Authorization\":\"Bearer $MP4_TEST_SESSION_TOKEN\"}"
export MP4_TEST_REPORT_ID="$report_id" MP4_TEST_DEVELOPER_ID="$developer_id"
export MP4_TEST_SNAPSHOT_SQL="$base/test/private-notes-snapshot.sql"
python3 -B "$base/test/private_notes_api_test.py"
python3 -B "$base/test/private_notes_utf8_test.py"
java --add-modules jdk.httpserver -cp "$scratch/classes:$dependencies" \
  mp4.developer.PrivateNotesAuthorizationRaceTest
