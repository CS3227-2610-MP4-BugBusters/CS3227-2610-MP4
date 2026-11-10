# Reporter backend

Reporters can create reports, list their own reports, read one, and edit one while it is unassigned.
- **Requirements:** `openspec/changes/reporter-markdown-reports/specs/reporter-reports/spec.md` (RPT-01..09).
- **Approved HTTP contract:** `workflow/reporter/reporter-reports-api-contract.md` (revision 4).

| Endpoint | Behaviour |
| --- | --- |
| `POST /api/reporter/reports` | Create. 201, 3 per Reporter per rolling minute, otherwise 429. |
| `GET /api/reporter/reports?page&pageSize` | List own reports, newest first. |
| `GET /api/reporter/reports/{id}` | Read own report. Another Reporter's report gives the same 404 as a missing one. |
| `PUT /api/reporter/reports/{id}` | Edit `{title, description, version}` while unassigned and `SUBMITTED`. 409 `REPORT_LOCKED` or `VERSION_CONFLICT`. |

Reporters never change a report's status. Closing is a Maintainer-only action. Any path below a report, such as `/close`, gives the read 404.

## Layout

- `src/`: `ReporterReportsHttpHandler` (routing, validation, errors), `ReporterReportStore` (JDBC) and `ReporterJson` (strict JSON reader). Plain JDK and JDBC only.
- `test/`: the synthetic-session harness, the runners and the black-box API tests.
- `migrations/`: Reporter-owned additive migrations, applied after every shared migration. There are none yet.

## Prerequisites

- **Docker**, with the daemon running. No local PostgreSQL install is needed.
- **A JDK, 17 or later**, providing `javac` and `java`.
- **Python 3**, available as `python`. The runners call `python`, not `python3`. On Linux, install `python-is-python3` or add an alias.
- **The PostgreSQL JDBC jar**, set in `POSTGRES_JDBC_JAR`. Any 42.x release works. Common locations:
  - Debian/Ubuntu: `sudo apt-get install libpostgresql-jdbc-java` installs `/usr/share/java/postgresql.jar`.
  - After any auth Gradle build: the Gradle cache, for example `~/.gradle/caches/modules-2/files-2.1/org.postgresql/postgresql/42.7.14/<hash>/postgresql-42.7.14.jar`.
- **On Windows**, use Git Bash.

## Migrations

- **Timestamp migration (design D7).** It lives with the shared reports schema at `src/backend/shared/migrations/002_add_report_timestamps.sql`. It adds `created_at` and `updated_at` (`timestamptz NOT NULL DEFAULT now()`) to `reports`. It also adds an index on `(reporter_id, created_at DESC, id)`, used by the newest-first list and by the rate-limit window count.
- **Order in the test runners.** Every `src/backend/shared/migrations/*.sql` file runs in name order (`001`, then `002`). This directory's migrations run next, then `src/backend/developer/migrations/create_investigation_checklists.sql`.
- **Test databases are fresh.** The runners build each test database from scratch and do not track applied versions.
- **The deployed app uses Flyway.** It applies `src/backend/auth/src/main/resources/db/shared/V4__reporter_report_timestamps.sql`. That file is the same change written idempotently, because the Maintainer's `V3` already adds the two columns. If you change `002`, change `V4` to match.

## Run the tests

The quickest way to run everything below is one command, the same one CI's `Reporter API` job uses:

```sh
POSTGRES_JDBC_JAR=/path/to/postgresql-42.x.jar python workflow/verify.py --group reporter
```

To run the steps by hand, do this from the repository root:

```sh
export POSTGRES_JDBC_JAR=/path/to/postgresql-42.x.jar
T=src/backend/reporter/test

# Schema smoke check, then the migration test (6 PASS lines)
sh $T/run-reporter-db.sh
sh $T/run-reporter-db.sh python -B $T/report_timestamps_migration_test.py

# API tests, in two groups
sh $T/run-reporter-api.sh $T/create_report_api_test.py $T/create_report_validation_test.py \
  $T/create_report_rate_limit_test.py $T/list_own_reports_api_test.py $T/read_own_report_api_test.py
sh $T/run-reporter-api.sh $T/reporter_access_api_test.py $T/edit_report_api_test.py $T/reporter_status_api_test.py
```

Expected results, all `OK`:

| Test file | Tests |
| --- | --- |
| `create_report_api_test` | 12 |
| `create_report_validation_test` | 21 |
| `create_report_rate_limit_test` | 14 |
| `list_own_reports_api_test` | 12 |
| `read_own_report_api_test` | 5 |
| `reporter_access_api_test` | 17 |
| `edit_report_api_test` | 31 |
| `reporter_status_api_test` | 4 |
| **Total** | **116** |

Each runner exits non-zero if any test file fails.

### What the runners do

`run-reporter-db.sh`:
- starts a fresh `postgres:17` container, on a random `127.0.0.1` port and with a random password;
- applies the migrations in the order above;
- exports `MP4_TEST_DATABASE_URI`, `MP4_TEST_JDBC_URL`, `MP4_TEST_DATABASE_NAME` and `MP4_TEST_PG_CONTAINER` for the command;
- removes the container on exit, including after a failure or an interrupt.

Set `MP4_TEST_PG_IMAGE` to use a different image.

`run-reporter-api.sh` wraps `run-reporter-db.sh`. It also:
- compiles `src/*.java` with `test/ReporterApiHarness.java`;
- creates the synthetic actors and a pool of 120 fresh Reporters;
- starts the harness on a loopback port, with a 16-thread executor so that parallel requests really overlap;
- runs each Python test given on the command line. With no arguments it runs only the two create test files.

### Timing and troubleshooting

- **A run takes a while.** Running all eight files at once takes more than 10 minutes, because every fixture query runs through `docker exec psql`. Use the two groups above.
- **"harness did not start" or "harness exited".** Check that `POSTGRES_JDBC_JAR` points to a readable jar, and that `javac` is version 17 or later.
- **Containers are left over.** Each run cleans up after itself. Any `mp4-reporter-test-*` container still in `docker ps -a` is from an interrupted shell, and you can remove it.

## The deployed app

The auth server packages the Reporter backend. This was task 1.4 of `openspec/changes/report-discussion`.
- `src/backend/auth/build.gradle` compiles the three `src/` files.
- `AuthConfiguration` allows `/api/reporter/**` only for `hasRole("REPORTER")`, behind the existing session and CSRF rules.
- `AuthController` passes the session user's id and role to the handler.
- The deployment Dockerfile copies `src/`.

To check the mount through the real server, use the auth integration test `src/backend/auth/src/test/java/mp4/auth/ReporterApiMountTest.java`:

```sh
cd src/backend/auth
./gradlew --no-daemon test --tests mp4.auth.ReporterApiMountTest
```

This test needs a database with `001_create_reports.sql` and the Developer checklist migration applied. Set these variables to point at it:
- `MP4_AUTH_TEST_JDBC_URL`
- `MP4_AUTH_TEST_DB_USER`
- `MP4_AUTH_TEST_DB_PASSWORD`

There are two ways to get that database:
- `python src/backend/auth/test/run_setup.py` sets it up, if the PostgreSQL binaries (`initdb`, `pg_ctl`) are installed locally.
- Without them, use Docker:

  ```sh
  docker run -d --name mp4-auth-test -e POSTGRES_PASSWORD=pw -p 127.0.0.1:55432:5432 postgres:17-alpine
  until docker exec mp4-auth-test pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1; do sleep 1; done
  docker exec mp4-auth-test createdb -U postgres mp4
  docker exec -i mp4-auth-test psql -v ON_ERROR_STOP=1 -U postgres -d mp4 < src/backend/shared/migrations/001_create_reports.sql
  docker exec -i mp4-auth-test psql -v ON_ERROR_STOP=1 -U postgres -d mp4 < src/backend/developer/migrations/create_investigation_checklists.sql
  export MP4_AUTH_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55432/mp4 MP4_AUTH_TEST_DB_USER=postgres MP4_AUTH_TEST_DB_PASSWORD=pw
  ```

  Remove the container afterwards with `docker rm -f mp4-auth-test`.

The synthetic harness in `test/` is for local tests only. It is never packaged.
