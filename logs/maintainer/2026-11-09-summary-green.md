# Maintainer summary Green evidence

The approved queue-summary service behavior has been implemented in the isolated Maintainer module. After the three approved test-oracle defects were corrected and independently reverified, the final independent focused summary run passed all 29 tests, including cache-safety and authorization-revocation regressions. The implementation rechecks authorization after snapshot/cache boundaries, rejects expired or oversized cached results, and keeps provider work leased through actual worker termination. No SQL, authenticated HTTP adapter, live provider, durable quota adapter, or production cache is implemented here.

## Technical evidence

- Task: MNT4.1â€“4.4, isolated summary-service behavior.
- Starting revision: `5355a23ce0a3c9bf9fbb700aafb3be0bc4ebe76b`.
- Original approved test baseline hash from handoff: `fe5cc024e52378dd3108e222d18fd9a93a175dbc485e9122ad3b76bc1aa0849c`.
- Files changed for this task: `QueueSummaryService.java`, new `QueueSummaryEngine.java`, new `QueueSummarySafetyRegressionTest.java`.
- Focused approved suite command, from `src/backend/auth`:
  `gradlew.bat test --tests mp4.maintainer.summary.QueueSummaryTest --tests mp4.maintainer.summary.SummarySetupTest`
- Initial focused approved suite outcome, before oracle correction: 22 tests completed; 19 passed, 3 failed. The owner confirmed the three mismatches were test-oracle defects: newest-first exclusion expectation against oldest-first design order; missing expected `latestUpdates` truncation; and a HIGH filter paired with a MEDIUM-only snapshot. These assertions were corrected by the test designer, not by this implementer, and the independent baseline verifier confirmed genuine Red.
- Regression/setup command, from `src/backend/auth`:
  `gradlew.bat test --tests mp4.maintainer.summary.QueueSummarySafetyRegressionTest --tests mp4.maintainer.summary.SummarySetupTest`
- Final focused command, from `src/backend/auth`:
  `gradlew.bat test --tests mp4.maintainer.summary.QueueSummaryTest --tests mp4.maintainer.summary.SummarySetupTest --tests mp4.maintainer.summary.QueueSummarySafetyRegressionTest`
- Final focused outcome: PASS, 28 tests, 0 failures. Covers the corrected 21-test approved suite, setup control, cache citation/expiry/byte-bound validation, and authorization revocation during empty, cached and generated snapshot delivery and cache storage.
- Environment: `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot`; `MP4_POSTGRES_BIN=C:\Program Files\PostgreSQL\17\bin`.
- Corrected approved test SHA-256: `0b83e13c75736935da6674644e8c18d6be60178b2a113cd883be65dd4438f32a` (also recorded with current inputs in `2026-11-09-summary-postmerge-inputs.sha256`).
- Final service SHA-256: `c7c77383bba30f3360a9158edea0f1b1204e111525d01c04f8dac534c34b79a2`.
- Final engine SHA-256: `e8a5c444415660c45fc6ca404c199ad4900f8df5cebbed2fece8b1f4267d025b`.
- Final regression-test SHA-256: `6050f5e8697f209e269f8e743fa5ef2988c974ec2e8878d976a3b587e9eeddcf`.
- Candidate revision: working tree based on `5355a23ce0a3c9bf9fbb700aafb3be0bc4ebe76b`; no commit created.
- Review findings/fixes: cache reuse validates result metadata, report references, capture-time retention, and canonical serialized output bytes using the same finding validator as provider responses. Authorization is rechecked after empty, cache-hit and generated snapshot reads and after cache storage. Added regressions verify that revocation during each return path suppresses delivery. Provider worker startup/timeout uses shared lease ownership so cancellation before entry cannot release a running worker's lease; renewal begins at worker entry and is repeated on client timeout.
- Remaining checks: fresh candidate verification and security review are being coordinated by the root agent. Real PostgreSQL races, authenticated service integration, live SoC connectivity, durable shared quotas and production reuse remain unverified/unimplemented seams.

## Final independent checks

The candidate verifier (gpt-6-luna/low) independently ran the focused command
with Adoptium 21 and confirmed 29 passing tests: 21 approved cases, one setup
control and seven safety regressions, with zero failures, errors or skips.
Verified the final source/test hashes after the interruption-fixture correction.
The first invocation stopped before Gradle because of an invalid inherited
JAVA_HOME; it is excluded from behavior evidence.

The separate security reviewer (gpt-6.1-sol/medium) confirmed the final static
repairs, including cache expiry during the delivery snapshot. Verdict: PASS for
the bounded summary domain. The independent disposable database run also passed
all 29 summary cases; its ten expected failures belonged to unimplemented
Maintainer HTTP routes and are recorded in the HTTP baseline log. No real
provider, durable budget adapter, SQL snapshot or HTTP summary route is claimed.

## Final cache-age repair

The security review identified that a cached result could expire while the final snapshot check was running. The service now rechecks the result age immediately before reuse and falls through to budgeted generation when it has expired. A mutable-clock regression advances time during the second snapshot read and asserts the old cached text is not returned. The primary Gradle runner was reserved for HTTP verification, so this last bounded repair and its new regression have not yet been run; candidate verification should include the focused summary command above.

- Final repair service SHA-256: `a7ad54be12a83756c3a66221a540ca93b17227070952742b7388815fb42d68e3`.
- Final repair engine SHA-256: `e8a5c444415660c45fc6ca404c199ad4900f8df5cebbed2fece8b1f4267d025b`.
- Final repair regression-test SHA-256: `16adeccd748876135a1374d1d353df1b289d93b8132352569c53f8437cf4bbae`.
- Previous focused result: 28 tests passed before this last regression was added. `git diff --check` passed after the last repair; Gradle recheck pending runner release.
