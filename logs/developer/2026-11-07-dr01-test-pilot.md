# DR-01 assigned-results test pilot

- Scope: test-only HTTP and PostgreSQL fixture harness. Application source, checklist code, frozen tests, and OpenSpec tasks were not changed. The fixture mode is an injectable reference adapter for validating the harness and assertions; it is not the application's implementation and cannot establish application behavior.
- Baseline revision: unavailable because the checkout contains numerous pre-existing uncommitted/untracked changes. Artifact hashes below identify the tested test-only files.
- Checks: Python syntax compilation passed; Java harness compilation passed with JDK 25 using `javac --release 17 --add-modules jdk.httpserver`. Loopback execution required sandbox escalation. The test passed synthetic session controls and confirmed that baseline mode returns HTTP 404 for the absent application route. This is a setup/route-presence check, not behavioral Red.
- Fixture behavior: blocked. Neither `MP4_TEST_JDBC_URL` nor a JDBC driver/database configuration was present, so the PostgreSQL SQL fixture, D1/D2 pre/post reassignment assertions, fields/isolation checks, and fixture-mode role denial were not run.
- Reproducible fixture command (from repository root, using an empty disposable PostgreSQL database and a PostgreSQL JDBC driver):

  ```sh
  export MP4_TEST_DATABASE_URI='postgresql://.../disposable'
  export MP4_TEST_JDBC_URL='jdbc:postgresql://.../disposable'
  export POSTGRES_JDBC_JAR='/path/to/postgresql.jar'
  psql "$MP4_TEST_DATABASE_URI" -v ON_ERROR_STOP=1 -f src/backend/developer/test/developer-report-list-pilot.sql
  build_dir=$(mktemp -d)
  javac --release 17 --add-modules jdk.httpserver -cp "$POSTGRES_JDBC_JAR" -d "$build_dir" \
    src/backend/developer/test/DeveloperReportListPilotHarness.java
  python3 -B src/backend/developer/test/developer_report_list_pilot_test.py "$build_dir:$POSTGRES_JDBC_JAR"
  ```

- Baseline run command: `javac --release 17 --add-modules jdk.httpserver -d "$build_dir" src/backend/developer/test/DeveloperReportListPilotHarness.java && python3 -B src/backend/developer/test/developer_report_list_pilot_test.py "$build_dir"` (run with loopback permission).
- SHA-256: `DeveloperReportListPilotHarness.java` `7613126cb029c29b44145b5a213b774b46f302e9e75b2d4f32c4e5894751de3a`; `developer_report_list_pilot_test.py` `b0ba4e19f476feaf59a218ebf4405b96fe1f4ed14e515757c9a0000ff45c5d0e`; `developer-report-list-pilot.sql` `7431eefc5d50d961b69ac72c5daa8518c2aa81beb8b887e1df4fa376882452f1`.
- Agent/model/effort metadata and token usage: unavailable in this execution context.

## Independent fixture verification

- Test designer: `list_behavior_designer`, GPT-6 Luna low; independently scoped test-only fixture and pilot. Independent verifier: `list_fixture_verifier`, GPT-6 Luna low; reviewed the spec and pilot, compiled the harness, seeded a disposable PostgreSQL database and ran the documented fixture command above with a locally cached JDBC driver. Loopback access required escalation.
- Result: session controls and missing-route baseline passed; the reference adapter passed D1/D2 assignment filtering, role denial, client identity manipulation and reassignment checks. The temporary database was stopped and its scratch data removed. These checks validate the pilot reference adapter, not an application endpoint; no behavioral Red was established and no implementation task was completed.
- The hashes above were independently verified. Token usage and costs were unavailable. The Git HEAD at pilot preparation was `b4a8ccef8c2ad9e05bca6697ece05e2aa33daff9`; worktree files were uncommitted.

## Application baseline readiness check

- Starting HEAD: `b4a8ccef8c2ad9e05bca6697ece05e2aa33daff9`, with uncommitted worktree files. Test designer `list_red_designer` (GPT-6 Luna low) inspected the app and pilot; no actual list handler, shared router, session adapter or shared report migration exists in this checkout. Its compilation succeeded, but an attempted local probe was blocked by sandbox loopback permissions. Prior independent fixture execution above remains valid as a reference-adapter check only.
- Independent read-only auditor `list_baseline_auditor` (GPT-6 Luna low) confirmed that the proposed contract and actual application fixture/routing are not integrated, and that the missing route is not a valid behavioral Red under `workflow/tdd.md`. No application or test files were changed in this readiness check. Token counts unavailable.

## Context and interpretation for reflection

This pilot gave the test designer the DR-01 requirement, actor/assignment rules and constrained fixture scope. The recorded controls establish which parts of the runner could be trusted.

Its observed results must be interpreted at the layer actually exercised; a synthetic actor or missing route does not establish real session integration. Later evidence is recorded separately.
