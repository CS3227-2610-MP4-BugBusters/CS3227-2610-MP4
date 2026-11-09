# Maintainer workflow Red baseline

The goal is to establish a focused service-behavior baseline for Maintainer list/detail/developer reads, priority, assignment, resolution review, reopen, and comments (OpenSpec tasks 1.2, 2.1, 2.2, 3.1, 3.2). Added a deterministic transactional in-memory repository fake and 14 JUnit tests; no application code or build configuration changed. The pinned setup runner compiled the tests and ran 54 tests: 40 existing setup/auth controls passed and all 14 new workflow tests failed on the unimplemented service boundary. Failures are expected domain results or success-path outcomes not yet implemented; the service stub returns `NOT_IMPLEMENTED` instead. Review limits: fake-backed service behavior only; no real PostgreSQL transaction, lock, deactivation race, HTTP authorization, or browser behavior is established. No independent baseline verifier has reviewed this result yet.

## Technical evidence

- Task IDs: 1.2, 2.1, 2.2, 3.1, 3.2.
- Repository: `C:\Users\ngkok\Documents\ChatGPT\mp4`; test run used HEAD `23a12e1c7d657ab9414b1016362b32ef46c7ba52` (starting checkout was `885e67eb8073384c24adc5df781ba6a1e09a2442`; UI worktree advanced HEAD during preparation).
- Agent role: test_designer.
- Test files: `src/backend/auth/src/test/java/mp4/maintainer/workflow/MaintainerWorkflowTest.java`, `WorkflowFixture.java`.
- Exact command: `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot'; python src/backend/auth/test/run_setup.py`.
- Result: runner exit 1 because Gradle `:test` reported 54 tests completed, 14 failed. Compilation succeeded. Existing 40 setup/auth tests passed. Failures reached `MaintainerService`; the unimplemented service returns `NOT_IMPLEMENTED` where tests require business results or exact domain errors. Initial run without overriding stale `JAVA_HOME` was blocked because it pointed to a removed temporary JDK; rerun with installed Temurin JDK 21 completed the test phase. Runner cleaned its disposable PostgreSQL cluster.
- Input manifest: `2026-11-09-workflow-red-inputs.sha256` covers applicable instructions, TDD workflow, service contract, OpenSpec design/tasks/specs, Gradle build and setup runner/readmes, and all current auth test sources/fixtures. Hashes are SHA-256 over UTF-8 contents after converting CRLF to LF. It intentionally excludes mutable implementation source.
- SHA-256 over UTF-8 LF-normalized test source:
  - `MaintainerWorkflowTest.java`: `8cace1e76b4e4f421a13adfcec2d276f10333cfc080c1ee097e3e18bf1378887`
  - `WorkflowFixture.java`: `2562a3cb4a45d9b2345f7b51db7a3e900f6a40c2c025fd15bcfe4f5da2a174dd`
- Model/reasoning metadata: gpt-6-luna, low. Sanitized run ID: unavailable in this execution trace.
- Remaining limitation: fake transaction rollback and method ordering assertions document service expectations only; they cannot prove real database atomicity or serialization.

## Fresh independent baseline review

Confirmed Red with a fresh baseline_verifier (gpt-6-luna/low) on `74913b5`.
The single full run compiled successfully and exited 1 with all 14 new workflow
cases failing for missing behavior; the existing 40 setup/auth controls passed.
All 23 LF-normalized manifest entries matched. The reviewer checked exact domain
codes, valid safe projections, account/report/child lock ordering, populated shared
evidence preservation and injected fake rollback. This updates the earlier run
record above; fake assertions still do not prove real PostgreSQL races or HTTP.
Proceeding under the feature owner's standing authorization for verified work.

## Baseline correction before Green

The earlier rollback case incorrectly expected the fake repository's raw
`IllegalStateException` to cross the service boundary. The approved service
contract requires failures to be reported as a safe `ServiceException`; the
Maintainer design uses HTTP 503 `TEMPORARILY_UNAVAILABLE` for unavailable
workflow storage. Corrected only the two exception expectations in
`failureAppendingHistoryRollsBackReportProposalAndComment`: closure and comment
history-append failures now require status 503, code
`TEMPORARILY_UNAVAILABLE`, and the fixed message
`Maintainer workflow is temporarily unavailable`, with an explicit check that
the injected repository detail is not exposed. Every rollback assertion for
report, proposal, comment, and history remains intact. No fixture, application,
contract, setup test, or other workflow test changed.

At the earlier independently verified baseline `b563bf1`, all 14 workflow
tests were Red and 40 setup/auth controls passed. That 40-control result proves
the previous baseline's setup and auth controls only; the current focused
correction run below deliberately selected the workflow test class and did not
rerun those controls. The fresh verifier should compare the correction's
current test and 24-entry LF-normalized manifest before Green.

Correction run used HEAD `9cd0ecbf73f34116b2135a7e2749c6967473b0b2` with only
`MaintainerWorkflowTest.java` edited. The exact command was run from
`src/backend/auth` with Adoptium JDK 21:
`.\gradlew.bat --no-daemon test --tests mp4.maintainer.workflow.MaintainerWorkflowTest`.
Gradle compiled successfully, then reported 14 tests completed and 14 failed;
the corrected history-failure case now fails because the unchanged service
stub returns `NOT_IMPLEMENTED` rather than the required safe 503. All 14
workflow tests therefore remain Red against the baseline. No database was
needed for this class-only run. Current LF-normalized test hashes:

- `MaintainerWorkflowTest.java`: `34df95f26b26f2c570240c884de2f812f91f765c60de5c2cce2b3eed017251b1`
- `WorkflowFixture.java`: `2562a3cb4a45d9b2345f7b51db7a3e900f6a40c2c025fd15bcfe4f5da2a174dd`

The input manifest now contains 24 current-path entries (all prior manifest
inputs), LF-normalized before hashing. The prior independently reviewed 40
controls have not been claimed as results of this focused run.

## Corrected storage-failure assertion: fresh verification

Corrected the two injected-history exception assertions before application Green.
The contract requires a safe ServiceException/503/TEMPORARILY_UNAVAILABLE, not the
fake's raw IllegalStateException. Kept every rollback/history/data assertion and
added fixed safe-message and injected-detail exclusion checks. Did not change the
production exception inheritance to satisfy the mistaken assertion.
A fresh baseline_verifier (gpt-6-luna/low) compared against `b563bf1`, confirmed this
strengthens the contract, verified all 24 input hashes and reran the pure-fake
workflow class on `1b18791`: compile passed, 14 tests failed at NOT_IMPLEMENTED,
exit 1. Existing 40-control proof remains the separately recorded valid fixture run.
The updated baseline is the approved starting point for workflow implementation.
