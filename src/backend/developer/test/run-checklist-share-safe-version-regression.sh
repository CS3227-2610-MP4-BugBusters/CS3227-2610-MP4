#!/usr/bin/env bash
set -euo pipefail

backend_dir="$(cd "$(dirname "$0")/.." && pwd)"
: "${POSTGRES_JDBC_JAR:?Set POSTGRES_JDBC_JAR to PostgreSQL JDBC driver JAR}"
: "${JACKSON_CORE_JAR:?Set JACKSON_CORE_JAR to Jackson Core JAR}"
: "${JACKSON_DATABIND_JAR:?Set JACKSON_DATABIND_JAR to Jackson Databind JAR}"
: "${JACKSON_ANNOTATIONS_JAR:?Set JACKSON_ANNOTATIONS_JAR to Jackson Annotations JAR}"
scratch="$(mktemp -d /tmp/mp4-share-safe-version.XXXXXX)"
pg_started=false
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
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
createdb mp4_share_safe_version_test
export PGDATABASE=mp4_share_safe_version_test
export MP4_TEST_DATABASE_URI="postgresql://mp4_fixture:$MP4_TEST_DB_PASSWORD@127.0.0.1:$port/mp4_share_safe_version_test"
export MP4_TEST_JDBC_URL="jdbc:postgresql://127.0.0.1:$port/mp4_share_safe_version_test?user=mp4_fixture&password=$MP4_TEST_DB_PASSWORD"

export MP4_TEST_REPORT_ID=00000000-0000-0000-0000-000000000401
export MP4_TEST_DEVELOPER_ID=00000000-0000-0000-0000-000000000402
export MP4_TEST_UNASSIGNED_ID=00000000-0000-0000-0000-000000000403
export MP4_TEST_REPORTER_ID=00000000-0000-0000-0000-000000000404
export MP4_TEST_PROPOSAL_ID=00000000-0000-0000-0000-000000000405
export MP4_TEST_OTHER_REPORTER_ID=00000000-0000-0000-0000-000000000406
export MP4_TEST_MAINTAINER_ID=00000000-0000-0000-0000-000000000407
export MP4_TEST_INACTIVE_REPORTER_ID=00000000-0000-0000-0000-000000000408
export MP4_TEST_SESSION_TOKEN="$(openssl rand -hex 24)"
export MP4_TEST_UNASSIGNED_SESSION_TOKEN="$(openssl rand -hex 24)"
export MP4_TEST_REPORTER_SESSION_TOKEN="$(openssl rand -hex 24)"
export MP4_TEST_OTHER_REPORTER_SESSION_TOKEN="$(openssl rand -hex 24)"
export MP4_TEST_MAINTAINER_SESSION_TOKEN="$(openssl rand -hex 24)"
export MP4_TEST_INACTIVE_REPORTER_SESSION_TOKEN="$(openssl rand -hex 24)"
psql "$MP4_TEST_DATABASE_URI" -v ON_ERROR_STOP=1 <<SQL
CREATE TABLE resolution_proposals (
  id uuid PRIMARY KEY, report_id uuid NOT NULL, status text NOT NULL,
  version bigint NOT NULL, contents text NOT NULL
);
CREATE TABLE reports (
  id uuid PRIMARY KEY, reporter_id uuid NOT NULL, assignee_id uuid,
  status text NOT NULL, version bigint NOT NULL, current_proposal_id uuid
);
CREATE TABLE investigation_checklists (
  report_id uuid PRIMARY KEY REFERENCES reports(id), draft_steps text[] NOT NULL,
  draft_revision bigint NOT NULL, shared_steps text[] NOT NULL
);
SQL
psql "$MP4_TEST_DATABASE_URI" -v ON_ERROR_STOP=1 \
  -v report_id="$MP4_TEST_REPORT_ID" -v developer_id="$MP4_TEST_DEVELOPER_ID" \
  -v reporter_id="$MP4_TEST_REPORTER_ID" -v proposal_id="$MP4_TEST_PROPOSAL_ID" \
  -f "$backend_dir/test/checklist-share-http-fixture.sql"

classpath="$POSTGRES_JDBC_JAR:$JACKSON_CORE_JAR:$JACKSON_DATABIND_JAR:$JACKSON_ANNOTATIONS_JAR"
mkdir -p "$scratch/classes"
javac --release 17 --add-modules jdk.httpserver -cp "$classpath" -d "$scratch/classes" \
  "$backend_dir/src/ChecklistDraftStore.java" \
  "$backend_dir/src/ChecklistShareService.java" \
  "$backend_dir/src/JdbcChecklistShareService.java" \
  "$backend_dir/src/ChecklistShareHttpHandler.java" \
  "$backend_dir/test/ChecklistShareHttpHarness.java" \
  "$backend_dir/test/ChecklistShareSafeVersionHttpLauncher.java"
java --add-modules jdk.httpserver -cp "$scratch/classes:$classpath" \
  mp4.developer.ChecklistShareSafeVersionHttpLauncher > "$scratch/server.log" 2>&1 &
server_pid=$!
for _ in $(seq 1 100); do
  if grep -q '^http://' "$scratch/server.log"; then break; fi
  if ! kill -0 "$server_pid" 2>/dev/null; then cat "$scratch/server.log"; exit 1; fi
  sleep 0.1
done
export MP4_TEST_ORIGIN="$(head -n 1 "$scratch/server.log")"
if [[ "$MP4_TEST_ORIGIN" != http://* ]]; then cat "$scratch/server.log"; exit 1; fi
export MP4_TEST_SESSION_HEADERS="$(python3 -c 'import json,os; print(json.dumps({"Authorization":"Bearer "+os.environ["MP4_TEST_SESSION_TOKEN"]}))')"
export MP4_TEST_SNAPSHOT_SQL="$backend_dir/test/checklist-share-http-snapshot.sql"
python3 -B "$backend_dir/test/checklist_share_http_safe_version_test.py"
