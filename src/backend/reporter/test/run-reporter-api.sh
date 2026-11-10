#!/bin/sh
# Usage: POSTGRES_JDBC_JAR=... run-reporter-api.sh [python-test ...]
# Wraps run-reporter-db.sh; builds src/*.java + harness, starts it with a
# synthetic session, runs Python tests, always stops the harness.
# Actors: R1, R2 active REPORTER; R3 inactive REPORTER; D1 DEVELOPER; M1 MAINTAINER,
# plus a pool of 120 fresh active Reporters (MP4_TEST_POOL) so tests that create
# reports never hit the 3-per-minute limit (RPT-08 is task 2.6).
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ "${MP4_REPORTER_API_INNER:-}" != 1 ]; then
  MP4_REPORTER_API_INNER=1 exec sh "$here/run-reporter-db.sh" sh "$here/run-reporter-api.sh" "$@"
fi
: "${POSTGRES_JDBC_JAR:?Set POSTGRES_JDBC_JAR}"
[ -f "$POSTGRES_JDBC_JAR" ] || { echo "JDBC jar not found" >&2; exit 1; }
scratch=$(mktemp -d)
pid=''
cleanup() {
  if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  rm -rf -- "$scratch"
}
trap cleanup EXIT HUP INT TERM
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) sep=';'; native() { cygpath -m "$1"; } ;;
  *) sep=':'; native() { printf '%s
' "$1"; } ;;
esac
srcs=""
for f in "$here"/../src/*.java "$here"/../../discussion/src/mp4/discussion/*.java; do [ -e "$f" ] && srcs="$srcs $f"; done
# shellcheck disable=SC2086
javac --release 17 --add-modules jdk.httpserver -cp "$(native "$POSTGRES_JDBC_JAR")" -d "$(native "$scratch/classes")" $srcs "$(native "$here/ReporterApiHarness.java")"
python -B "$here/make_actors.py" > "$scratch/env"
# shellcheck disable=SC1091
. "$scratch/env"
export MP4_TEST_ACTORS MP4_TEST_POOL
export MP4_TEST_ID_R1 MP4_TEST_ID_R2 MP4_TEST_ID_R3 MP4_TEST_ID_D1 MP4_TEST_ID_M1
export MP4_TEST_TOKEN_R1 MP4_TEST_TOKEN_R2 MP4_TEST_TOKEN_R3 MP4_TEST_TOKEN_D1 MP4_TEST_TOKEN_M1
# Discussion display names come from the real auth accounts table (V100), with one row per synthetic actor.
accounts_sql="$here/../../auth/src/main/resources/db/auth/V100__create_auth_accounts.sql"
python -B -c 'import os
rows = []
for n, entry in enumerate(e for e in os.environ["MP4_TEST_ACTORS"].split(";") if e):
    uid, role, active = entry.split("=", 1)[1].split(":")
    rows.append("(%r,%r,%r,%r,%r,%s)" % (uid, "actor-%d" % n, "Test %s %d" % (role.title(), n), "unused", role, active))
print("INSERT INTO public.app_accounts (id,username,display_name,password_hash,role,active) VALUES %s;" % ",".join(rows))' > "$scratch/accounts.sql"
cat "$accounts_sql" "$scratch/accounts.sql" | docker exec -i "$MP4_TEST_PG_CONTAINER" \
  psql -v ON_ERROR_STOP=1 -q -U mp4_fixture -d "$MP4_TEST_DATABASE_NAME"
java --add-modules jdk.httpserver -cp "$(native "$scratch/classes")$sep$(native "$POSTGRES_JDBC_JAR")" mp4.reporter.ReporterApiHarness > "$scratch/origin" &
pid=$!
i=0
while [ ! -s "$scratch/origin" ]; do
  i=$((i + 1)); [ "$i" -le 100 ] || { echo "harness did not start" >&2; exit 1; }
  kill -0 "$pid" 2>/dev/null || { echo "harness exited" >&2; exit 1; }
  sleep 0.1
done
MP4_TEST_ORIGIN=$(tr -d '\r\n' < "$scratch/origin"); export MP4_TEST_ORIGIN
if [ "$#" -eq 0 ]; then set -- "$here/create_report_api_test.py" "$here/create_report_validation_test.py"; fi
rc=0
for t in "$@"; do
  echo "== $t"
  python -B "$t" || rc=1
done
exit $rc
