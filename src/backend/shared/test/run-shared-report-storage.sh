#!/bin/sh
set -eu
umask 077
base=$(CDPATH= cd -- "$(dirname -- "$0")/../../../.." && pwd)
scratch=$(mktemp -d /tmp/mp4-shared-reports.XXXXXX)
pg_started=false
cleanup() {
  if [ "$pg_started" = true ]; then pg_ctl -D "$scratch/data" -m fast -w stop >/dev/null; fi
  rm -rf -- "$scratch"
}
trap cleanup EXIT HUP INT TERM

for tool in initdb pg_ctl createdb psql python3 openssl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "required tool not found: $tool" >&2; exit 2; }
done

export LC_ALL=C
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
password=$(openssl rand -hex 24)
printf '%s\n' "$password" > "$scratch/password"
initdb -D "$scratch/data" -U mp4_fixture --auth=scram-sha-256 --pwfile="$scratch/password" --no-locale --encoding=UTF8 >/dev/null
rm -- "$scratch/password"
pg_ctl -D "$scratch/data" -l "$scratch/postgres.log" -o "-h 127.0.0.1 -p $port -k $scratch" -w start
pg_started=true
export PGPASSWORD="$password" PGHOST=127.0.0.1 PGPORT="$port" PGUSER=mp4_fixture PGDATABASE=postgres
createdb mp4_shared_reports_test
export MP4_TEST_DATABASE_NAME=mp4_shared_reports_test
export MP4_TEST_DATABASE_URI="postgresql://mp4_fixture:$password@127.0.0.1:$port/mp4_shared_reports_test"

python3 -B "$base/src/backend/shared/test/shared_report_storage_migration_test.py"
