# Assigned-report AL-01..08 baseline

- Scope: test-only DR-01 acceptance suite, synthetic-session HTTP baseline, and disposable fixture. No application handler is present or mounted in this checkout. Do not interpret this run as an application behavior verdict or a valid behavioral Red.
- Starting revision: `b4a8ccef8c2ad9e05bca6697ece05e2aa33daff9`; pre-existing worktree changes were present. No application code or existing tests were edited.
- Added files and SHA-256:
  - `src/backend/developer/test/AssignedReportsBaselineHarness.java`: `8f9898faa79d5c6ca8f52fdc5c09f0ec6b0863694bb683f1c23f5bfaff3fb187`
  - `src/backend/developer/test/assigned_reports_al_api_test.py`: `bd91802a209091bb2136363dffd8101e2cce93fe2fcca2d7b62fd16f0a6462f9`
  - `src/backend/developer/test/assigned_reports_al_baseline_runner.py`: `9a0f541a6130b891a875b2d0b961db51f476be978a6b35e3d901625a51eaf9ea`
  - `src/backend/developer/test/assigned-reports-al-fixture.sql`: `da6304242679113b5ef80f931cd0c4bcf26923752550e5e2dd9204ed4953cbd6`
- Disposable PostgreSQL 17 cluster: initialized under `/private/tmp/mp4-dr01-pg.G2exu1` with `--no-locale --encoding=UTF8`, started on a private Unix socket only at `/private/tmp/mp4-dr01-pg.G2exu1/socket`, port `55439`; created database `mp4_assigned_reports_test`. Sandbox shared-memory and socket restrictions required the authorized escalation. The fixture applied successfully and a query confirmed 22 D1 assignments. It also seeds D2 and unassigned controls. The cluster was stopped after the run and its directory removed.
- Compile command: `javac --release 17 --add-modules jdk.httpserver -d /private/tmp/mp4-dr01-build.styku4 src/backend/developer/test/AssignedReportsBaselineHarness.java` (passed).
- Baseline command: `MP4_TEST_DATABASE_URI='postgresql://huanghao@/mp4_assigned_reports_test?host=/private/tmp/mp4-dr01-pg.G2exu1/socket&port=55439' MP4_TEST_DATABASE_NAME='mp4_assigned_reports_test' python3 -B src/backend/developer/test/assigned_reports_al_baseline_runner.py /private/tmp/mp4-dr01-build.styku4` (the same environment variables were exported before invocation; runner exited 1 on expected assertions).
- Controls: anonymous session returned 401; synthetic sessions resolved D1, D2, Reporter, inactive Developer, empty Developer and Maintainer. Per-test fixture reset and D1 count verification passed.
- AL run: 8 tests executed. AL-05 and AL-06 passed against the synthetic baseline's session gate only. AL-01 through AL-04, AL-07 and AL-08 each received HTTP 404 with `No application handler mounted`; these are setup/missing-route observations, not behavior failures of an application route. No claim is made about real session, routing, shared schema or integrated behavior.
- Cleanup: temporary PostgreSQL cluster, build directory, and generated Python bytecode removed. Token usage unavailable.

## Independent baseline review

`list_al_baseline_verifier` (GPT-6 Luna, low reasoning) reviewed the fixture, assertions, recorded hashes and workflow. The reviewer confirmed that the session gate and fixture support local setup checks, but the unmounted application handler means the 404 results are setup observations, not a verified behavioral Red. The reviewer did not independently rerun the database-backed runner after its disposable cluster was removed. No baseline approval, implementation, integrated verification, or security-review pass is claimed. Token usage unavailable.
