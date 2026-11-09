# Developer checklist persistence

The first backend increment saves a private checklist draft through JDBC. It locks the report row, checks the actor's current assignment and report state, then compares and increments the draft revision. It preserves the shared copy, report version, and pending proposal. The HTTP layer will map `STALE_VERSION` to `409 STALE_VERSION`.

The [migration](migrations/create_investigation_checklists.sql) expects the shared `reports` table. The integration test uses a minimal fixture for that table until the shared migration lands. Actor data in the test is synthetic. The application will obtain it from the server's authentication layer.

## Verify locally

Use a fresh, disposable PostgreSQL database. With its connection URI and a PostgreSQL JDBC driver JAR, run this from the Developer backend directory:

```sh
psql "$MP4_TEST_DATABASE_URI" -v ON_ERROR_STOP=1 \
  -f test/report-fixture.sql -f migrations/create_investigation_checklists.sql
build_dir=$(mktemp -d)
javac --release 17 -cp "$POSTGRES_JDBC_JAR" -d "$build_dir" \
  src/ChecklistDraftStore.java test/ChecklistDraftStoreIntegrationTest.java
java -cp "$build_dir:$POSTGRES_JDBC_JAR" mp4.developer.ChecklistDraftStoreIntegrationTest \
  "$MP4_TEST_JDBC_URL"
```

Point both URLs at the same test database. The JDBC URL uses the `jdbc:postgresql://` prefix. Set `MP4_TEST_DB_PASSWORD` if the JDBC URL does not already provide the database password.

Verified on 7 November 2026 against disposable PostgreSQL 17 using a synthetic report and actors. The migration and JDBC checks passed for initial Save, stale revision, competing saves, former-assignee and Reporter denial, and Closed reports. The local HTTP adapter below still needs integration with shared routing and production authentication. Share and the Share-versus-closure race remain open.

## Verify the local Save HTTP adapter

Use the same disposable database and fixture from `workflow/evaluation/developer/checklist-save-api.md`. Set `MP4_TEST_JDBC_URL`, `MP4_TEST_DEVELOPER_ID`, and `MP4_TEST_SESSION_TOKEN`; optionally set `MP4_TEST_DB_PASSWORD`. The launcher uses the test-only synthetic-session harness and does not provide production authentication. Compile the store, adapter, harness, and launcher with JDK 17 or newer and the PostgreSQL JDBC and Jackson Core, Databind, and Annotations JARs on the classpath:

```sh
build_dir=$(mktemp -d)
classpath="$POSTGRES_JDBC_JAR:$JACKSON_CORE_JAR:$JACKSON_DATABIND_JAR:$JACKSON_ANNOTATIONS_JAR"
javac --release 17 --add-modules jdk.httpserver -cp "$classpath" -d "$build_dir" \
  src/ChecklistDraftStore.java src/ChecklistDraftHttpHandler.java \
  test/ChecklistSaveHttpHarness.java test/ChecklistSaveHttpLauncher.java
java --add-modules jdk.httpserver -cp "$build_dir:$classpath" \
  mp4.developer.ChecklistSaveHttpLauncher
```

The launcher prints its loopback origin and stays running. Set `MP4_TEST_ORIGIN` to that origin, `MP4_TEST_SESSION_HEADERS` to the JSON header object `{"Authorization":"Bearer $MP4_TEST_SESSION_TOKEN"}`, and the fixture/snapshot variables described in the HTTP evaluation instructions. Run `python3 -B src/backend/developer/test/checklist_save_api_test.py` from the repository root. Reset the fixture before rerunning.

With the same settings and a fresh fixture, run `python3 -B src/backend/developer/test/checklist_save_text_validation_test.py` to check that invalid text leaves stored data intact and valid supplementary characters round-trip.

## Assigned-report list tests

Run the list, search, status and security checks with JDK 17+, Python 3,
OpenSSL, PostgreSQL server/client tools (`initdb`, `pg_ctl`, `createdb`,
`psql`) on PATH, and a PostgreSQL JDBC driver jar:

```sh
export POSTGRES_JDBC_JAR=/absolute/path/to/postgresql.jar
src/backend/developer/test/run-assigned-reports.sh
```

The runner creates a temporary password-protected PostgreSQL cluster and a
synthetic-session HTTP server on loopback, then stops them and removes the
cluster. It does not connect to the shared database. Tests use a synthetic
schema and sessions; real application integration requires separate checks.

## Local checklist Share and shared read

From the repository root, set `POSTGRES_JDBC_JAR`, `JACKSON_CORE_JAR`,
`JACKSON_DATABIND_JAR`, and `JACKSON_ANNOTATIONS_JAR` to their JAR paths.
With JDK 17+, Python 3, OpenSSL, and PostgreSQL server/client tools on PATH,
run the disposable PostgreSQL and synthetic-session checks:

```sh
sh src/backend/developer/test/run-checklist-share-service.sh mp4.developer.JdbcChecklistShareService src/backend/developer/src/JdbcChecklistShareService.java
sh src/backend/developer/test/run-checklist-share-proposal-guard-regression.sh
sh src/backend/developer/test/run-checklist-share-closure-race-regression.sh
sh src/backend/developer/test/run-checklist-share-safe-version-regression.sh
sh src/backend/developer/test/run-checklist-share-http.sh
sh src/backend/developer/test/run-checklist-shared-read-http.sh
```

These tests use isolated fixtures. The shared-read adapter requires the host
to supply a Maintainer report-view permission callback. The test callback is
synthetic and is not the production authorization policy. Real sessions,
CSRF, shared routing, database integration, report-detail projections and
the Maintainer's actual closure service still require integration checks.

## Pull request checks

The repository CI runs this synthetic-session test runner on Java 17 and 21,
validates OpenSpec, and verifies both role frontends on Node 22 and 24. These
checks do not deploy the application or establish real authentication,
shared-schema integration, browser coverage, or production approval settings.
GitHub branch protection must be configured separately to require CI checks.
