# Reporter backend

Reporter report creation, listing and reading. Requirements are in
`openspec/changes/reporter-markdown-reports/specs/reporter-reports/spec.md`, and
the approved HTTP contract is `workflow/reporter/reporter-reports-api-contract.md`.

Layout:
- `migrations/`: Reporter-owned additive migrations, applied after every
  shared migration (currently none)
- `src/`: store and HTTP handler
- `test/`: harness, launcher and black-box API tests

## Migrations

- The timestamp migration (design D7) lives with the shared reports schema at
  `src/backend/shared/migrations/002_add_report_timestamps.sql`. It adds
  `created_at` and `updated_at` (`timestamptz NOT NULL DEFAULT now()`) to
  `reports` and an index on `(reporter_id, created_at DESC, id)` for the
  newest-first list and the rate-limit window count. It adds no other columns.
- Order: every `src/backend/shared/migrations/*.sql` in name order (`001`, then
  `002`), then this directory's migrations in name order, then
  `src/backend/developer/migrations/create_investigation_checklists.sql`.
- Fresh databases only, like the shared migration: no upgrade path or
  applied-version tracking. Do not rerun against an existing schema.

## Disposable test database (Docker)

Needs Docker with the daemon running. No local PostgreSQL install is required.

```sh
sh src/backend/reporter/test/run-reporter-db.sh               # schema smoke check
sh src/backend/reporter/test/run-reporter-db.sh <command ...>  # run tests against it
```

Each run:
- starts a fresh `postgres:17` container on a random `127.0.0.1` port with a
  random password;
- applies every shared migration in name order, every `migrations/*.sql` in
  name order, then the Developer checklist migration;
- exports `MP4_TEST_DATABASE_URI`, `MP4_TEST_JDBC_URL`, `MP4_TEST_DATABASE_NAME`
  and `MP4_TEST_PG_CONTAINER` for the command;
- removes the container on exit, including on failure or interrupt.

Override the image with `MP4_TEST_PG_IMAGE`. Build and test steps for the store
and handler are added by task 2.7.

## Run the API tests

Needs Docker, a JDK 17+ (`javac`, `java`), Python 3 and the PostgreSQL JDBC jar.
The runner compiles `src/*.java` with the test harness (plain JDK plus JDBC, no
other libraries), starts it on a loopback port against a disposable database,
runs the given Python tests (default: both create test files) and stops the
harness and container on exit.

```sh
POSTGRES_JDBC_JAR=/path/to/postgresql-42.x.jar sh src/backend/reporter/test/run-reporter-api.sh
POSTGRES_JDBC_JAR=... sh src/backend/reporter/test/run-reporter-api.sh src/backend/reporter/test/create_report_api_test.py
```
