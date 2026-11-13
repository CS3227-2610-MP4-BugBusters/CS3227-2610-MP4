# Shared Developer notes Red baseline

## Summary and scope

Added authenticated PostgreSQL integration coverage for separately saved and explicitly shared Developer investigation notes, including no-op Share, unpublished updates, empty and invalid requests, version limits, proposal invalidation, role and active-account authorization, reassignment, closure, and deterministic Share-versus-close/reassignment orderings. The disposable PostgreSQL runner and test-only write gate worked. Two controls passed (CSRF rejection and gate blocking); the other 14 tests stop at missing shared-note routes with HTTP 404. Feature outcomes after those calls remain unexecuted. No application code or existing tests were changed. This final expanded baseline is ready for independent review.

## Implementation and coordination

- Starting revision: `1b2d3b7f5a787fe2c73d4bbc79a54f6f9e016a5c`.
- Confirmed source requirements: `openspec/changes/developer-shared-investigation-notes/{design.md,tasks.md,specs/developer-shared-investigation-notes/spec.md}`, `workflow/tdd.md`, and `workflow/developer/checklist-storage-contract.md`.
- Feature contract: `GET` and `PUT /api/developer/reports/{id}/shared-notes/draft`, and `POST /api/developer/reports/{id}/shared-notes/share`; Save is private and only Share publishes the server-saved draft. Maintainers see the shared evidence projection. The Reporter has no draft or Share access.
- Reused `SessionAuthenticationTest` for real authenticated sessions, CSRF, HTTP, and isolated PostgreSQL. Maintainer report detail is the shared-note visibility assertion. The existing private-notes and checklist authenticated tests informed the privacy and stale-write controls.
- Added only `src/backend/auth/src/test/java/mp4/auth/DeveloperSharedNotesIntegrationTest.java`. Sixteen scenarios cover initial state, Save/Share projection and history, identical Share no-op, Save B remaining private until Share B, stale draft/report revisions, empty Save and rejected empty Share, NUL/invalid Unicode/oversized text, valid 10,000-unit boundary, unsafe wire revisions and max-safe report-version overflow, changed Share during review, Reporter and unrelated Developer denial, Maintainer draft denial, inactive Developer denial, former assignee denial, CSRF/no-store/session author, closed-report read-only access, both commit orders for Share versus close, both commit orders for Share versus reassignment, and a DB-only write-gate control.
- The Share-wins race waits match `report_shared_notes` without assuming whether a changed publication inserts its first row or updates an existing published row. The gate-control itself still exercises an insert.
- Corrected test fixtures after independent candidate review: include the required nonblank reason in all three Maintainer reassignment requests; expect 403 on the first request from an inactive account, which expires the session, and 401 on subsequent requests.
- Corrected the lock-wait helper after the candidate run showed the two close/reassignment-wins cases timing out while counting direct blockers only. PostgreSQL may report the second queued waiter as blocked by the first waiter; the helper now recursively follows `pg_blocking_pids` to count waiters transitively blocked by the held report row.
- The untracked worktree already contained `.agents/skills/.openspec-target`, `.claude/commands/`, `openspec/changes/developer-report-detail/`, `openspec/specs/.gitkeep`; the parent added the current OpenSpec feature. These were preserved.
- Task supplied by `/root`: establish task 1.1 Red, then extend coverage requested for task 1.3; allowed edits remained the new focused test and this evidence log. No agent delegation was used by this test designer. Model, reasoning effort, token usage, cache usage and trace measurements are unavailable in this interface.

## Findings and fixes

- Initial runner invocation was blocked before setup by sandbox loopback binding: `PermissionError: [Errno 1] Operation not permitted` at `run_setup.py:50`, `sock.bind(("127.0.0.1", 0))`.
- Retried the same focused command with the required escalation. The runner completed `RunnerSetupTest` successfully, then ran the focused integration test against its private temporary PostgreSQL cluster.
- The final focused run completed 16 tests: the PostgreSQL gate control and CSRF filter control passed; the other 14 API scenarios stopped at their first missing shared-note route. Thirteen expected HTTP 200 for a draft GET or Save and received 404. The invalid-text scenario expected 400 but received 404 because its draft route is absent. These failures establish the missing route behavior, but do not verify the later assertions.
- The four race tests are present and force both lock orderings for Share versus closure and Share versus reassignment. Each fails during authenticated fixture setup at its first Save (404), before either HTTP request race starts. The gate-control test independently confirmed the test-only trigger blocks and releases a `report_shared_notes` insert on PostgreSQL. The two-request report-lock queue and race outcomes were not exercised on baseline.
- Independent race-test review found the Share-wins wait matched only an `INSERT INTO report_shared_notes`, although later shares may `UPDATE` that row. Changed both Share-wins waits to match the table name for either insert or update; the gate-control remains insert-specific.
- Candidate review found three reassignment fixtures omitted the required reason and the inactive-account sequence expected 403 after its session had already been expired. Confirmed against `MaintainerService.assign` (`requireReason` for reassignment), `AuthSessionFreshnessFilter` (inactive account returns 403 and expires session), and existing Maintainer workflow/session tests. Added meaningful reasons to all three assignment requests; retained the first inactive-request 403 and changed the following PUT and Share expectations to 401 `UNAUTHENTICATED`.
- Candidate test XML showed only `closeWinningRaceRejectsShareAndPreservesPublishedNote` and `reassignmentWinningRaceDeniesFormerAssigneeShare` failing at `awaitBlockedRequests(..., 2)`. Replaced that helper's direct-blocker count with a recursive PostgreSQL blocking-chain query; race assertions and application code were unchanged.
- Added cases assert no-op Share preserves report version, history and row count; Save B leaves Maintainer seeing A until explicit Share B; empty Save is allowed and Share returns `EMPTY_DRAFT`; invalid text and revisions are rejected; and changed Share at the maximum safe report version returns `VERSION_LIMIT`. Those assertions are downstream of missing routes and remain unexecuted. No assertion inspected unexpected response bodies or secrets.
- The initial sandbox denial was resolved by the authorized isolated-run escalation. The test was subsequently corrected for required reassignment reasons, expired-session responses and transitive database lock waiters, as recorded above.

## Verification

Command:

```sh
python3 src/backend/auth/test/run_setup.py --tests mp4.auth.DeveloperSharedNotesIntegrationTest
```

Final corrected Red was run from a clean archive of starting revision `1b2d3b7f5a787fe2c73d4bbc79a54f6f9e016a5c` at `/private/tmp/mp4-shared-notes-red-baseline`, with only the corrected test copied into it. Command, run from that isolated root:

```sh
python3 src/backend/auth/test/run_setup.py --tests mp4.auth.DeveloperSharedNotesIntegrationTest
```

Outcome: runner setup control passed; the corrected test compiled and ran; Gradle exited 1 with 14 expected missing-route failures and two passing controls:

- 14 named feature scenarios failed at their first missing-route interaction, all with actual HTTP 404: `savedDraftStaysPrivateUntilExplicitShareAndMaintainerSeesOnlyPublishedNote` (expected GET 200); `staleDraftSaveAndStaleSharePreservePublishedNoteAndReportVersion` (expected Save 200); `reassignmentMovesDraftAccessAndClosedReportIsReadableButNotWritable` (expected Save 200); `closeWinningRaceRejectsShareAndPreservesPublishedNote` (expected fixture Save 200); `assignedDeveloperCanReadDraftOnClosedReport` (expected GET 200); `shareWinningReassignmentRaceMakesReassignmentStale` (expected fixture Save 200); `changedShareSupersedesProposalAndMakesMaintainerCloseStale` (expected GET 200); `changedShareAtMaximumSafeReportVersionDoesNotOverflow` (expected Save 200); `shareWinningCloseRaceMakesCloseStale` (expected fixture Save 200); `invalidTextAndUnsafeWireRevisionsAreRejectedWithoutMutation` (expected 400 but route was absent); `unrelatedDeveloperAndMaintainerCannotReadSaveOrShareDraft` (expected fixture Save 200); `reassignmentWinningRaceDeniesFormerAssigneeShare` (expected fixture Save 200); `emptyDraftCannotBeShared` (expected GET 200); `inactiveAssignedDeveloperCannotReadSaveOrShare` (expected fixture Save 200).
- `assignedDeveloperSaveAndShareRequireCsrf`: passed; the shared security filter rejected both mutations with `CSRF_INVALID` before route dispatch.
- `PostgreSqlGateBlocksASharedNoteWriteUntilReleased`: passed; the trigger blocked the PostgreSQL insert until the test released the row lock.

### Contract crosswalk

- **DSN-01:** Initial empty revision, Save A/B privacy, report-version preservation, pending-proposal preservation during private Save, stale Save no-mutation, empty Save, NUL/invalid Unicode, 10,000/10,001 UTF-16 length and safe wire revision assertions are present. Most are downstream of the missing route and unexecuted. The backend test cannot prove that a frontend preserves unsent editor text after errors.
- **DSN-02:** Explicit A/B publication, identical no-op, empty and stale Share, version-limit rejection, session-derived publication author, history/version updates, proposal invalidation, and both Share/close orderings are asserted. Their route-dependent outcomes are unexecuted. The tests do not simulate unsaved browser-only text; that belongs in the Developer UI tests.
- **DSN-03:** Assigned Developer access, Reporter denial, unrelated Developer GET/PUT/Share denial, Maintainer direct draft denial, Maintainer published-only detail, inactive account denial, former assignee denial, new assignee inheritance, Closed-report read-only access, CSRF and no-store assertions are present. Valid-session feature authorization assertions are unexecuted because the route is missing. The no-CSRF control passed at the shared security filter.
- **DSN-04:** Authenticated API and PostgreSQL checks, stale/no-op/race scenarios, and workflow evidence are present. Frontend labels, unsent-text preservation and safe rendered text require Developer/Maintainer frontend tests and were not checked in this backend-only task.

The four race tests force both lock orders for Share versus closure and Share versus reassignment. Each fails during HTTP fixture setup at its first Save (404), before either application race starts. The database gate control passed, but the two-request report-lock queue and race outcomes were not exercised on baseline. No candidate, security-review, deployment, browser/UI or hosted checks were run.

Starting revision: `1b2d3b7f5a787fe2c73d4bbc79a54f6f9e016a5c`.

SHA-256 of added test: `1b94907f1837acec30c217a1376aba7049ea68c6f241e64679c70cc9907b01ae`.

The above hash is the test before the candidate-fixture corrections. Corrected test SHA-256: `762a06baf58b1eca38d125addac63540cc4b4d1b1133ca88f610382a3c697551`. The independent archive run used exactly this corrected test hash. The corrected reassignment and inactive-account downstream assertions still did not execute on baseline because their setup first reaches the missing shared-note route (404).

After the candidate race-wait correction, test SHA-256 is `8c87e18de021e966373c7cfc25bd1c1c6de40ef2b490e02b3ead551adab538dc`. Refreshed `/private/tmp/mp4-shared-notes-red-baseline` from the same clean starting revision and copied only this test file into it. The focused command passed `RunnerSetupTest`, compiled, and ran 16 tests: 14 expected missing-route 404 failures and the two controls (`assignedDeveloperSaveAndShareRequireCsrf`, `PostgreSqlGateBlocksASharedNoteWriteUntilReleased`) passed. The new transitive race wait is not reached on this baseline because both race fixtures stop at their initial missing Save route; candidate race behavior must be rechecked after the implementation retains the corrected test.

## Reflection and resources

The shared storage contract and existing authenticated fixtures supplied the API boundary, roles, sessions, CSRF handling and database runner. The runner setup control, CSRF filter control and PostgreSQL trigger-gate control passed. The first launch was blocked solely by sandbox socket restrictions; escalation allowed the isolated local test to proceed. The final independent baseline verifier checked the corrected SHA-256 `8c87e18de021e966373c7cfc25bd1c1c6de40ef2b490e02b3ead551adab538dc` in a separate clean baseline copy and reproduced the same 14 expected 404 failures and two passing controls. The transitive race helper remained unexecuted on Red; independent candidate verification later passed the full 16-case authenticated PostgreSQL suite, including race paths. Total token counts, cache/reasoning metrics, exact agent timing and cost are unavailable; no efficiency comparison is claimed.
