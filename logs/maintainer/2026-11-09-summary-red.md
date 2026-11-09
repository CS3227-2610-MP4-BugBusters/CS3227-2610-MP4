# Queue summary Red baseline

The goal was to write focused service-contract tests for Maintainer queue summary tasks 4.1–4.4. I added 21 JUnit cases and a deterministic in-memory port fixture. `compileTestJava` passed. With the required disposable PostgreSQL setup, all 21 summary cases fail against the current setup service because generation still throws the intentional `NOT_IMPLEMENTED` failure; they do not fail from Java compilation or a missing fixture. The existing workflow behavior suite also remains 14/14 Red. All 40 pre-existing setup/control tests passed. The valid runner result is 75 tests, 35 failures and 40 passes, with failures limited to the 21 summary behavior tests and the 14 existing workflow behavior tests.

The tests cover empty queues, authorization, queue and context bounds, ordering and truncation, prompt/data separation, strict JSON and citations, full-snapshot and session rechecks, cache identity/TTL, production deterministic-mode rejection, typed admission/provider failures, and timeout lease retention. No independent review findings or fixes are recorded yet; the baseline verifier verdict is pending. The test doubles demonstrate service-port behavior only; they do not establish SQL query budgets, PostgreSQL concurrency, API/session integration, or live provider behavior.

## Technical evidence

- **Task:** `complete-maintainer-role` tasks 4.1–4.4.
- **Role/model:** test designer, `gpt-6-luna`, high reasoning effort. Run ID and token usage were unavailable.
- **Starting revision:** `9cd0ecbf73f34116b2135a7e2749c6967473b0b2`. The provided worktree already contained summary setup/API changes, including the `NOT_IMPLEMENTED` service stub. No application source was edited for this baseline.
- **Test files:** `src/backend/auth/src/test/java/mp4/maintainer/summary/QueueSummaryTest.java` and `SummaryFixture.java` (21 test methods total).
- **Inputs:** hashes normalized to UTF-8 with LF line endings are in [the input manifest](2026-11-09-summary-red-inputs.sha256). The mutable `QueueSummaryService.java` implementation is intentionally excluded from that manifest.
- **Valid command:** from repository root, set process-local `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot` and `MP4_POSTGRES_BIN=C:\Program Files\PostgreSQL\17\bin`, then run `python src/backend/auth/test/run_setup.py`. The runner created a disposable database and invoked Gradle `check` with its connection environment.
- **Valid runner outcome:** 75 tests ran, 35 failed, 40 passed, 0 skipped. `QueueSummaryTest`: 21/21 failed at expected missing behavior (`NOT_IMPLEMENTED`). `MaintainerWorkflowTest`: 14/14 failed as the known workflow Red. All 40 setup/control tests passed.
- **Setup-blocked attempt (excluded from Red verdict):** a direct Gradle invocation without `run_setup.py` ran 58 tests and had 15 auth/database setup failures because `MP4_AUTH_TEST_JDBC_URL` was absent. This was not valid behavior evidence. Its first wrapper invocation also exited before Gradle because inherited `JAVA_HOME` pointed to a missing temporary JDK. The valid disposable-database runner above used installed JDK 21 locally; no further test run was made.
- **Review and recheck:** independent summary baseline review is pending. No review fixes or rechecks have occurred.

## Fresh independent baseline verification

Confirmed Red with a fresh baseline_verifier (gpt-6-luna/low) on `1b18791`.
All 30 LF-normalized input hashes matched. The focused pure-fake command from
`src/backend/auth`, with the installed Adoptium 21 JAVA_HOME, was
`gradlew.bat --no-daemon test --tests mp4.maintainer.summary.QueueSummaryTest`.
It compiled and exited 1 with 21 tests failed, no errors or skips: four surfaced
NotImplementedException directly and 17 rejected NOT_IMPLEMENTED instead of the
required result or side effect. This updates the earlier source revision in the
historical run record above. The focused check did not repeat the separately
recorded 40 passing disposable-fixture controls. An initial invalid JAVA_HOME
invocation did not start Gradle and was excluded from behavior evidence.
Reviewed the fixture and cases for satisfiable selection, bounds, typed failures,
freshness/cache and lease behavior. No real gateway, cross-replica quota, SQL race
or live usefulness claim follows from these fakes. Proceeding under the feature
owner's standing instruction after verified Red.
