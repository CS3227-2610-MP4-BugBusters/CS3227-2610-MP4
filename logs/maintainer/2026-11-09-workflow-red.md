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
