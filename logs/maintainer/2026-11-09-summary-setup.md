# Queue-summary setup boundary

Added a compileable, dependency-injected Java API for the future Maintainer queue-summary service. The immutable contracts reuse the shared Maintainer role/status/priority enums, represent only explicitly selected report fields, carry the full queue snapshot/version/assignment vector, and define authorization, snapshot, gateway, durable-budget, lease and cache ports. Session identity is opaque and redacted from actor/cache-key string forms. The service currently returns a deterministic `NOT_IMPLEMENTED` failure before using any port; no summary behavior, provider call, SQL, lifecycle write, controller or frontend behavior is implemented.

The setup runner completed successfully on the shared-enum source revision before the final setup-test assertion was narrowed to constructor/type availability. The runner compiled and passed the existing suite plus both Maintainer setup controls (40 test cases, 0 failures/errors/skips in its XML reports). That exact check does **not** verify the subsequently edited `SummarySetupTest`; the root coordinator will run the final combined disposable-PostgreSQL check after the parallel workflow setup edits settle. Live provider, real Maintainer authorization adapter, queue snapshot SQL, durable quota and cache integration remain open.

## Technical evidence

- Task: `complete-maintainer-role` setup for 1.3 and 4.1–4.4; allowed scope: new summary service/contracts/setup control/contract/log plus auth Gradle source directory.
- Starting revision: `e63dc47ef0b611e04239c2ca0e0d444325674b7b`; no commit created.
- Runner attempt 1 used inherited invalid `JAVA_HOME` (`C:\Users\ngkok\AppData\Local\Temp\textmax-gradle-setup\jdk-25\jdk-25.0.4+7`) and failed before Gradle. No Java source result was produced by that attempt.
- Runner attempt 2: process session `8266`; `JAVA_HOME=C:\Program Files\Java\jdk-17`; `python src/backend/auth/test/run_setup.py`; exit 0, `BUILD SUCCESSFUL in 2m 7s`. This was before shared enum alignment.
- Coordinated runner: process session `66631`; same Java 17 override and exact runner command; exit 0, `BUILD SUCCESSFUL in 1m 57s`. This compiled shared Maintainer enums and both setup controls before the final summary setup test-only assertion edit.
- Last-run JUnit XML totals: 40 tests, 0 failures, 0 errors, 0 skipped. Per-class counts: AccountLifecycle 3, ProvisioningAcceptance 5, ProvisioningValidation 4, RunnerSetup 1, SessionAuthentication 11, SessionGuardRegression 8, summary setup 1, workflow setup 2, AuthParsingAndRaceRegression 5.
- Final corrected `SummarySetupTest` has not yet been rerun in the isolated disposable PostgreSQL setup; no verification claim is made for that exact source tree.
- No AI integration, auth session-identity adapter, full-queue SQL reader, source-field migration, production gateway, shared quota implementation or cross-replica cache was attempted or verified.
