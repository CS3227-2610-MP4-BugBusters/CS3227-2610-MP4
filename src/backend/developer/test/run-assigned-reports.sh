#!/bin/sh
set -eu
umask 077
base=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${POSTGRES_JDBC_JAR:?Set POSTGRES_JDBC_JAR to the PostgreSQL JDBC driver jar}"
[ -f "$POSTGRES_JDBC_JAR" ] || { echo "JDBC jar not found" >&2; exit 1; }
scratch=$(mktemp -d /tmp/mp4-assigned-search.XXXXXX)
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
createdb mp4_assigned_reports_test
export MP4_TEST_DATABASE_NAME=mp4_assigned_reports_test
export MP4_TEST_DATABASE_URI="postgresql://mp4_fixture:$MP4_TEST_DB_PASSWORD@127.0.0.1:$port/mp4_assigned_reports_test"
export MP4_TEST_JDBC_URL="jdbc:postgresql://127.0.0.1:$port/mp4_assigned_reports_test?user=mp4_fixture&password=$MP4_TEST_DB_PASSWORD"
psql -X -v ON_ERROR_STOP=1 -f "$base/test/assigned-reports-fixture.sql"
jars=$POSTGRES_JDBC_JAR
javac --release 17 --add-modules jdk.httpserver -cp "$jars" -d "$scratch/classes" \
  "$base/src/ChecklistDraftStore.java" "$base/src/AssignedReportStore.java" \
  "$base/src/AssignedReportListHttpHandler.java" "$base/test/AssignedReportListHttpHarness.java"
java --add-modules jdk.httpserver -cp "$scratch/classes:$jars" \
  mp4.developer.AssignedReportListHttpHarness > "$scratch/origin" &
http_pid=$!
for attempt in $(seq 1 50); do
  if [ -s "$scratch/origin" ]; then break; fi
  kill -0 "$http_pid"
  sleep 0.1
done
export MP4_TEST_ORIGIN=$(cat "$scratch/origin")
python3 -B "$base/test/assigned_report_search_api_test.py"
python3 -B "$base/test/assigned_report_search_security_test.py"
python3 -B "$base/test/assigned_report_status_filter_test.py"
python3 -B "$base/test/assigned_report_pagination_api_test.py"
python3 -B "$base/test/assigned_reports_al_api_test.py"
python3 -B "$base/test/assigned_report_cache_header_test.py"
