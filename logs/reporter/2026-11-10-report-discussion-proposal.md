# Report discussion: proposal (10 Nov 2026)

## Summary

The owner asked how to build a GitHub-style discussion between Reporter and
Developer. A new OpenSpec change, `report-discussion`, records the answer. It
contains planning artifacts only; no code was changed.
`openspec validate --strict` passes.

**Owner decisions (10 Nov):**
- Comments are allowed on closed reports.
- Comments do not change the report's version or `updated_at`.
- Comment bodies use the same Write | Preview editor and safe renderer as the
  description.

**Scope:**
- One shared thread per report, open to three participants:
  - the Reporter who filed the report;
  - the currently assigned Developer;
  - any Maintainer.
- Comments are append-only and must be under 20,000 characters.
- Each author can post at most 10 comments per minute.
- The timeline is built on the read side.
- Developer private notes and drafts are never shown in the thread.

**Prerequisite:** the Reporter backend is not in the deployment Dockerfile.
Task 1.4 wires it into the auth server, following the pattern of the Developer
handlers.

**Conflicts that need Maintainer owner approval (task 1.1):**
- The Maintainer workflow contract says "comments increment the aggregate
  version".
- MNT-DISC-01 specifies plain-text discussion.
- The `maintainer-report-browser` spec says comments render as escaped text.

The Maintainer spec delta waits until `reporter-markdown-reports` is archived,
because that change already modifies MNT-READ-05 (task 1.2).

## Details

Artifacts are in `openspec/changes/report-discussion/`:
- `proposal.md`
- `specs/report-discussion/spec.md` (DSC-01..06)
- `design.md` (D1–D8)
- `tasks.md` (5 groups)

## Apply started (10 Nov)

The owner ran `/openspec-apply-change report-discussion`. Progress is 0 of 13.

**Blocked tasks:**
- 1.1 needs the Maintainer owner's approval, which is external.
- 1.2 waits until `reporter-markdown-reports` is archived.
- Group 2 needs both 1.1 and 1.3.

**Task 1.3:** the contract draft `workflow/reporter/report-discussion-api-contract.md` is ready. It awaits the feature owner's approval.

**Developer access:** a "cannot see" request gets 403 `FORBIDDEN` with "Access denied". This follows the existing `ChecklistDraftHttpHandler`, which gives the same response for a missing report and for one that is not assigned to the Developer.

**Page size:** the default page size is 20, the same as RPT-04, as DSC-04 requires.

## Task 1.4 started (10 Nov)

The owner said: "add be then start the report-discussion". I read this as
starting 1.4 (deploying the Reporter backend) first. HEAD is `9908537`, which
contains the planning artifacts and the contract draft. The working tree is
clean.

`test_designer` is writing an auth-server integration test that proves the
Reporter API is mounted behind the real session:
- the actor comes only from the session;
- 401 and 403 responses;
- CSRF and origin checks;
- the Flyway timestamps migration.

It also checks whether `docker build` is feasible here.

The group 2 discussion endpoints still need two things:
- approval of the 1.3 contract draft (`026dc8e35a25`);
- the Maintainer owner's approval for 1.1.

## 1.4 Red (10 Nov)

`test_designer` added `src/backend/auth/src/test/java/mp4/auth/ReporterApiMountTest.java`
(sha256 `00e69427f821…`). All 5 tests are Red.

**Why they fail:**
- Four tests get 403, because no `/api/reporter/**` route is mounted and `AuthConfiguration` falls through to `denyAll`.
- One test fails because shared migration `002_add_report_timestamps.sql` is not registered with Flyway in `auth/build.gradle`. The `db/shared` folder holds only V1 to V3.

**Full auth suite:** 143 tests ran, 138 passed. Only the 5 new tests fail.

**Docker build:** it works, taking 86 s with cache, but the image currently has no Reporter classes. Green requires `classes/mp4/reporter/ReporterReportsHttpHandler.class` to be present in the image.

**Test database:** it was a Docker `postgres:17-alpine` container. The `run_setup.py` PostgreSQL binaries are not installed here.

**Open decision:** which Flyway version to use for 002. The designer proposes V4 or higher.

A fresh `baseline_verifier` with mutation testing is running.

## 1.4 baseline verification: FAIL (10 Nov)

A fresh `baseline_verifier` gave **FAIL**. The Red reproduced: 5 of 5 tests fail, and the full suite is 143 tests with 5 failures. It found two test defects and one migration conflict.

**Test defects:**
1. **The column assertion is vacuous.** The Maintainer migration `V3__maintainer_evidence.sql` already adds `reports.created_at` and `updated_at`, so the assertion passes before any Reporter work. Fix: keep the index assertion and add a check on the Flyway history.
2. **The cross-origin 403 assertions have no basis in the contract.** `originAllowed` guards only login and logout, not the existing Developer and Maintainer mutating routes. Fix: delete these assertions and keep the CSRF-token checks.

**Migration conflict:** the shared `002` migration is a plain `ADD COLUMN`. Registering it as Flyway V4 would fail with "column already exists", because V3 already adds the same columns. The Green change must add an idempotent V4 instead, using `IF NOT EXISTS` for the columns and creating the index. It must not re-register `002` unchanged.

**Mutations, checked by reasoning:**
- Caught: a mount that ignores the role, a hard-coded role, the actor taken from a header, no CSRF check, and `002` left unregistered.
- Not caught: always passing `active=true`. This is acceptable, because the freshness filter already rejects inactive accounts before the handler runs.

`test_designer` is fixing the two test defects.

## 1.4 amended baseline re-verified: PASS (10 Nov)

The test designer amended `ReporterApiMountTest.java`, now sha256 `ced11a9e0235…`, to fix the two problems the earlier verification found. A fresh `baseline_verifier` gave **PASS** with no defects.

**Fixes confirmed:**
- **Migration check.** The empty column assertion is gone. The test now checks the index and requires the shared history to show a successful version of 4 or higher. The table name was confirmed as `SharedSchemaBootstrap.HISTORY`.
- **CSRF test.** It now checks only for a missing CSRF token.

**Red results:**
- Focused run: 5 of 5 tests fail.
- Full suite: 143 tests, 5 failures, 138 passing.
- Each failure comes from missing behaviour:
  - the routes are not mounted, so `denyAll` returns 403;
  - the history has no version of 4 or higher.

**Green must also run checks this test does not cover:**
- the Dockerfile copy plus a `docker build` image check;
- the Reporter harness tests for 2.3–2.11.

Leftover containers were removed.

This baseline awaits the feature owner's approval. This is a new change, so the earlier delegation does not apply.

**Approved by the owner (10 Nov): "approve 1.4".** The implementer starts.

## 1.4 Green (10 Nov)

The `implementer` finished task 1.4. Changes:

| File | Change | sha256 |
|---|---|---|
| `auth/build.gradle` | Adds the Reporter `srcDir` and the files to `include` | `a8f57f480600` |
| Dockerfile | Copies `reporter/src` | `dbcc0209299b` |
| `AuthConfiguration` | `/api/reporter/**` allowed only for `hasRole("REPORTER")`; CSRF unchanged | `c3d970178a53` |
| `AuthController` | Mounts the Reporter handler with the session principal's id and role | `6a8d8ffcd51c` |
| `auth/src/main/resources/db/shared/V4__reporter_report_timestamps.sql` (new) | `ADD COLUMN IF NOT EXISTS` and `CREATE INDEX IF NOT EXISTS` | `692b0ee7869a` |

The new V4 migration is placed in the auth module's own resources, not in `shared/migrations`. The Reporter test runner applies every file in `shared/migrations`, so putting it there would change that runner's approved setup.

Results:
- Focused test: 5/5 pass.
- Full auth suite: 143/143 pass.
- Reporter harness: 138/138 pass.
- Docker image: the Reporter class is PRESENT, and `db/shared` contains V1–V4.

The candidate check and security review are on hold. The owner removed the Reporter close action (next entry), and the 1.4 mount test exercises close.

## Owner requests (10 Nov): remove Reporter close; include the Markdown package in CI

1. **"SUBMITTED → CLOSED conflicts with the shared Maintainer-only closure, remove this action."**
   - **`reporter-markdown-reports`**:
     - RPT-10 (close) is removed.
     - RPT-09 gains the scenario "Reporters cannot change status": a `status` key in the body is rejected with 400, and `/close` returns the RPT-05 404.
     - D6 and D17 are updated, and the proposal is updated.
     - Task 2.8 is reopened for contract revision 4.
     - Task 2.11 becomes "remove the close action" and is unchecked.
   - **`report-discussion`**: design D1 and D3 no longer mention close.
   - **Contract revision 4** is a draft (sha256 `f0737644cedd`) and awaits the owner's approval:
     - close is removed;
     - the report `Allow` header becomes `GET, PUT`;
     - any path below a report returns the read 404.
   - `openspec validate --strict` passes for both changes.
2. **"Include the new module's typecheck/tests and lockfile in the existing verification and dependency scans."** This follows plan sections 8 and 11. Changes:
   - `workflow/verify.py`: `FRONTENDS` now includes `shared/markdown`.
   - `.github/workflows/ci.yml`: `shared/markdown` is added to the frontend matrix.
   - `workflow/security/check.py`: OSV-Scanner now also scans `src/frontend/shared/markdown/package-lock.json`.
   - `workflow/test/test_verify.py` now expects `src/frontend/shared/markdown`.

   Checks run locally:
   - The `test_verify` unit tests: 3/3 pass.
   - `actionlint`: rc 0.
   - `git diff --check`: clean.

   Not run here: the full OSV scan, which needs the pinned tool and network.

   **Known red:** the package's `npm run verify` currently fails 114 tests, all in `MarkdownView.destinations2.test.tsx`. That file is the pending 3.15 Red baseline. CI now reports this failure, until 3.15 is green or its baseline is changed.

## Owner (10 Nov): "update .gitignore for pyc approve contract"

- **Root `.gitignore` added** (none existed). It ignores `__pycache__/` and `*.py[cod]`, which removes the untracked `workflow/**/__pycache__` folders from `git status`.
- **Contract revision 4 approved.** The status line is updated, task 2.8 is ticked, and the sha256 is `450b03c68090`. The contract is now read-only.
- **Hash note.** Git checked several Reporter files out with CRLF line endings, so their raw sha256 values changed. Their LF-normalised hashes still match the verified values (`ReporterJson.java` `aa6023bce2ca`, `create_report_api_test.py` `46a50005ceb9`). From now on, hashes in handoffs are LF-normalised (`tr -d '\r' | sha256sum`).

**Next:** `test_designer` writes the Red tests for task 2.11, removing the Reporter close action. It also amends the 1.4 mount test so that it no longer closes a report.

## 2.11 (remove close) Red (10 Nov)

`test_designer` changed four test files. Hashes are LF-normalised.

| File | Change | sha256 |
|---|---|---|
| `reporter_status_api_test.py` | New, 4 tests | `4050a5323dbc` |
| `close_report_api_test.py` | Removed. A copy is in `scratchpad\t211\`. | — |
| Fixtures | Unused `close` helper removed | `91e42e6af65e` |
| `ReporterApiMountTest.java` | Amended: `/close` now asserts the 404 envelope and that the status stays `SUBMITTED` | `ab9c2f3121a8` |

**Red, as expected while close still exists:**
- `POST /close` on the owner's own report returns 200 and sets the status to `CLOSED`.
- `GET` and `PUT` on `/close` return 405.
- The mount test fails at line 90.

**Regression guards that already pass:**
- A `status` key in the edit body returns 400.
- The `Allow` headers are `GET, PUT` and `GET, POST`.
- A report closed via SQL is locked for editing.

**Everything else passes:**
- Reporter harness: 112 tests.
- Migration test.
- Auth suite: 142/143. The one failure is the amended mount test.

**Hash note:** the raw hashes recorded for the edit test (`1e0f5e028338`) and the contract (`450b03c68090`) came from CRLF files. Their LF hashes are `4d1629942f1d` and `a30740566363`. Both files are unchanged.

A fresh `baseline_verifier` is now running, with mutation checks.

## 2.11 baseline verification: PASS (10 Nov)

A fresh `baseline_verifier` gave **PASS**.

- **Hashes.** All match. The saved copy of the close test is identical to the HEAD version of the deleted file.
- **Dropped tests.** Every dropped close test was specific to close.
- **Red results.**
  - The status file has 8 failures, all in the sub-path test.
  - The mount test fails at line 91. The designer cited line 90.
  - The full auth suite runs 143 tests with 1 failure.
- **Mutations.** A correct removal passes the status tests (4/4), edit (31) and access (17). Every mutant was caught:
  - close returning 404 while still writing `CLOSED`;
  - `/close` returning 405;
  - the `Allow` header including POST;
  - a `status` key accepted in an edit.
- **Mount-test amendment.** Faithful to the plan.

Minor notes:
- In the plain handler, a sub-path 404 comes before the session check. In the deployed server, Spring Security answers anonymous requests (401) and wrong roles (403) on `/api/reporter/**` before the handler runs. The contract is silent on this.
- `run_setup.py` needs local `initdb`, which is not available here. Docker PostgreSQL was used instead.

This baseline awaits the feature owner's approval.

**Approved by the owner (10 Nov): "approve 2.11".** The implementer starts.

## 2.11 Green (10 Nov)

The `implementer` removed the close action. LF hashes:

| File | LF hash | Change |
|---|---|---|
| `ReporterReportsHttpHandler.java` | `c73463a97227` | Close route removed. Any deeper path returns the read 404. `Allow` is `GET, PUT` on a report and `GET, POST` on the collection. |
| `ReporterReportStore.java` | `190d8a9976bd` | `close()` removed |
| `ReporterJson.java` | `aa6023bce2ca` | Unchanged |

Results:
- Reporter harness: 116/116 pass:
  - status 4
  - edit 31
  - access 17
  - create 12
  - validation 21
  - rate limit 14
  - list 12
  - read 5
- Migration test: passes.
- Mount test: 5/5 pass.
- Full auth suite: 143/143 pass.
- Docker image: the Reporter class is present, with no `/close` left in it.

**Correction:** the LF hash of contract revision 4 is `a307405663ea`. Earlier entries in this log give it as `a30740566363`, which was a transcription typo. The file was never changed.

Next, a `candidate_verifier` and a `security_reviewer` will check tasks 2.11 and 1.4 together.

## 2.11 and 1.4 candidate verification: PASS (10 Nov)

A fresh `candidate_verifier` gave **PASS** for both tasks. The baseline hashes were unchanged before and after the run.

**Results:**
- Reporter harness: 116/116.
- Migration test: 6 PASS.
- Auth suite: 143/143, including the mount test (5/5).
- Docker image: contains the Reporter classes and V1–V4, and has no close route.

**Code conformance:**
- 2.11 matches D17 and contract revision 4.
- 1.4 matches D7:
  - Identity comes only from the session.
  - `/api/reporter/**` is limited to `hasRole("REPORTER")`.
  - CSRF and the Developer and Maintainer files are untouched.
  - The V4 placement is sound.

**Notes:** V4 duplicates the DDL in 002. If 002 changes, V4 must change with it.

The security review is still running.

## 2.11 and 1.4 security review: PASS. Both tasks complete (10 Nov)

`security_reviewer` gave **PASS**. The reviewer probed the deployed server with real logins and also checked the built image.

**What held:**
- **Role access.** Only an active Reporter reaches `/api/reporter/**`. Developer, Maintainer, anonymous, inactive, revoked and role-changed sessions are all refused, and path tricks never reached the handler.
- **Identity.** It comes only from the session principal. The exchange bridge passes no headers.
- **CSRF.** It held on every POST and PUT.
- **Report status.** No Reporter status change is possible, whether through `/close` with any method, a `status` field, PATCH, DELETE or a method override.
- **Error responses.** They are JSON and carry `no-store`. Forced 500s return the `INTERNAL_ERROR` envelope.
- **V4 migration.** It is safe on an existing V3 database. Existing timestamps were preserved.
- **Image contents.** The image contains only the three Reporter classes. It has no test, harness, synthetic-session or secret material.

**LOW findings, not fixed:**
1. A malformed `%` in the query string returns Tomcat's HTML 500 instead of the JSON 400, and logs an ERROR stack trace. The cause is in the shared `ServletHttpExchange.getRequestURI`, so Developer routes behave the same way. Fix: catch `IllegalArgumentException` and map it to `VALIDATION_FAILED`.
2. Spring answers OPTIONS itself (200, `Allow: GET,HEAD,POST,PUT,PATCH,DELETE,OPTIONS`), including on the removed `/close` path. Fix: give the mapping explicit methods. The Developer routes follow the same pattern.
3. A Windows checkout gives `start.sh` CRLF line endings, so a locally built image fails to start. CI on Linux is unaffected. Fix: add `*.sh text eol=lf` to `.gitattributes`.

**Not verified:**
- `ReporterReportStore.find` runs in autocommit, so reads skip the per-transaction account recheck. This leaves a tiny window for the user's own data only.
- `_csrf` is accepted as a query parameter. This comes from the global configuration.

All stated checks have passed. Task 2.11 (`reporter-markdown-reports`) and task 1.4 (`report-discussion`) are ticked.

## Task 1.3: discussion contract approved (10 Nov)

**Summary:** The feature owner approved the report-discussion API contract ("approve discussion contract"). Only the status line changed. From now on the file is read-only for every agent, and any later change resets the dependent Red baselines.

- File: `workflow/reporter/report-discussion-api-contract.md`, revision 1.
- Draft body sha256 (LF-normalised): `026dc8e35a254a01cf9b7dc13664b086a5904586c3cbde2063237d51bbba6f4d`.
- Approved file sha256 (LF-normalised, with the new status line): `c8bb2249da3a435675217b9b5067e8b799a65357b85b6336ff01808d37037642`.
- Revision: HEAD `d9a04eb` plus this working-tree change.
- Still blocking group 2: task 1.1 (Maintainer owner approval of D3 and D4, and Developer and Maintainer sign-off of migration `003_report_comments.sql`).

## Exception: 2.1 started before 1.1 (10 Nov)

The feature owner said "just start" when told that group 2 waits on 1.1. Task 2.1 (migration `003_report_comments.sql`) therefore begins its Red step without the Maintainer owner's D3/D4 approval and without Developer and Maintainer sign-off of the migration. Task 1.1 stays open. If those owners ask for changes, the 2.1 baseline and implementation are revisited.

## Task 2.1 Red: migration `003_report_comments.sql` (10 Nov)

**Summary:** `test_designer` wrote `src/backend/reporter/test/report_comments_migration_test.py` (LF sha256 `e173b46f894c2b3984cf8da8a4b719588b5f10acf6bc3de08be1d89c682acfd3`). All 8 checks fail because the table and file don't exist; the control `report_timestamps_migration_test.py` passes 6/6 in the same runner. A fresh `baseline_verifier` returned **RED-CONFIRMED** with LOW/info findings only: the negative role probe covers ADMIN, `reporter` and the empty string; error-text matching; and the shared-storage suite must be run at Green (task 2.1 says so).

- Revision: HEAD `d9a04eb` plus working tree.
- Command: `sh src/backend/reporter/test/run-reporter-db.sh python -B src/backend/reporter/test/report_comments_migration_test.py` (rc 1, 8/8 FAIL, missing relation or file).
- Awaiting feature-owner approval of the baseline.

**Owner approval (10 Nov):** "approve 2.1". The baseline at LF sha256 `e173b46f…2acfd3` is approved, and the owner also approved adding the Flyway copy `src/backend/auth/src/main/resources/db/shared/V5__report_comments.sql` (idempotent, same schema as `003`) so the deployed app gets the table.

## Task 2.1 Green and verification (10 Nov)

**Summary:** `implementer` added `src/backend/shared/migrations/003_report_comments.sql` (LF `e3014538a1f2…`) and the idempotent Flyway copy `src/backend/auth/src/main/resources/db/shared/V5__report_comments.sql` (LF `719d9ae84193…`), and updated `src/backend/reporter/README.md` and `src/backend/shared/README.md`. A fresh `candidate_verifier` returned **PASS** with no blocking findings. Task 2.1 is ticked.

- Baseline `report_comments_migration_test.py` LF `e173b46f…2acfd3` unchanged: 8/8 PASS. Control timestamps test: 6/6 PASS.
- Shared-storage suite: PASS (SR-01, SR-02, SR-03). It ran in a disposable postgres:17 container because the host has no `initdb`. It applies only 001, so it doesn't exercise 003.
- Flyway: `SharedSchemaStartupIntegrationTest` 4/4 and `ReporterApiMountTest` 5/5 (implementer run; `mp4_shared_schema_history` showed V5 applied). The verifier reran the startup test 4/4, applied V5 via psql on 001+002 databases (also twice, and after 003), and found 003 and V5 schema-identical. There is no other V5 in any `db/shared` location.
- LOW: the verifier did not itself drive V5 through Flyway history (the implementer's run did). Index names are not fixed by D1. V5's `IF NOT EXISTS` would skip a pre-existing, different table, the same tradeoff as V4.
- Not yet in CI: `report_comments_migration_test.py` is not in the `reporter` group of `workflow/verify.py`.

## Task 2.2 Red: Reporter comment endpoint (10 Nov)

**Summary:** `test_designer` wrote `src/backend/reporter/test/report_discussion_api_test.py` (LF sha256 `371cc0956c7d6e55114f577df16cf9f78c82f98ba5a08ab715eea5361a12a9fc`) against the approved contract (`c8bb2249…`). There are no runner, harness or fixture changes. Of 17 tests, 15 fail because the endpoint doesn't exist (every `/comments` request currently gets the RPT-05 404). The other 2 are regression guards that already pass: the RPT-05 404 for other, missing and malformed IDs, and the 404 below `/comments`. Control `read_own_report_api_test.py`: 5/5 OK. A fresh `baseline_verifier` returned **RED-CONFIRMED** with two MINOR findings:
1. Lines 148-150 assert a POST with an unknown query parameter gives 400. The contract defines query parameters only for GET.
2. The uppercase-UUID 404 case. The live read endpoint already returns 404 for it, since the guard test passes today, so the two stay consistent.

Info: `author.displayName` is asserted only as a key, because the harness database has no accounts table.

- Command: `sh src/backend/reporter/test/run-reporter-api.sh src/backend/reporter/test/report_discussion_api_test.py` (rc 1, `failures=39, errors=3` across subtests).
- Awaiting feature-owner decisions and approval.

**Owner approval (10 Nov):** "approve 2.2, drop the POST query check". The orchestrator removed the unknown-query-parameter POST 400 assertion (old lines 148-150) and renamed the test to `test_dsc01_identity_header_never_selects_the_author`. Nothing else changed. The uppercase-UUID case stays, because the read endpoint already gives the same 404. `displayName` is to be checked with real accounts in 2.4.
- Approved baseline LF sha256: `8edbb0f951413d9694eb0796859bc4938cfcf3801b4015cf0bb97af3866489c2`.

## Task 2.2 Green: blocked on a schema conflict (10 Nov)

**Summary:** `implementer` made the approved baseline pass in the harness (17/17), with no regressions:
- Reporter API tests: 116/116.
- 2.1 migration test: 8/8.
- Auth `ReporterApiMountTest` 5/5 and `SharedSchemaStartupIntegrationTest` 4/4.
- `docker build`: succeeded.

Through a real auth-server session, however, comment POST and GET return **500**. The cause is that the Maintainer's `V3__maintainer_evidence.sql` already creates `report_comments (id, report_id, author_id, text, created_at)`, with `text` instead of `body` and no `author_role`. `JdbcMaintainerRepository` and `JdbcQueueSnapshotReader` use it. The 2.1 `V5__report_comments.sql` (`CREATE TABLE IF NOT EXISTS`) is therefore a no-op on the deployed database. The 2.1 Green and its verification missed this, because the harness applies `003` and never V3, and the Flyway tests don't check the table's shape. Task 2.1's V5 part is reopened.

Implementer's changes, uncommitted:
- New package `src/backend/discussion/src/mp4/discussion/`: `ReportDiscussionService`, `ReportDiscussionJson`, `StrictJson` (moved from `ReporterJson`) and `TextRules`.
- Routing of `/comments` in `ReporterReportsHttpHandler`.
- `auth/build.gradle`, the Dockerfile, and the READMEs.
- Flagged infra change in `run-reporter-api.sh`: it compiles the discussion sources, applies the real auth `V100` accounts migration and inserts an account row per synthetic actor, so `displayName` is read by the same LEFT JOIN on `app_accounts` as in production. A missing account gives `null`.

Candidate verification and the security review are on hold until the owner decides the schema fix.

**Owner decision (10 Nov): "Keep `text`, add role".** Design D1 now has a "Reconciled with the Maintainer table" section:
- The storage column is `text`; the API field stays `body`.
- `003` creates the final shape.
- V5 becomes an idempotent ALTER on the V3 table: it adds `author_role`, backfills `'MAINTAINER'`, and adds the check and both indexes.
- The Maintainer comment INSERT sets `author_role = 'MAINTAINER'`.

This still needs Maintainer owner sign-off (1.1). `openspec validate report-discussion --strict` passes. `test_designer` is re-doing the 2.1 Red (column rename plus deployed-path checks V1→V5) and changing the column name in the 2.2 seeding, which resets both baselines.

## Re-Red after the schema decision (10 Nov)

**Summary:**
- `report_comments_migration_test.py` was amended to LF `860f4de3591979e6181cf64e6b4bab711e9aa9469e668f90e8f2b39bd1f15f01`. The column is now `text`, and new deployed-path checks apply V1→V5 on a V3-style table with a legacy row, check that V5 is idempotent, and check that the fresh and deployed shapes are identical.
- `report_discussion_api_test.py` was amended to LF `2a9e3fe5431543cc1e50e2762a85a559eda5a4d16e8c368db45cef34fdbaff60`, with one seeding column changed from `body` to `text`.

A fresh `baseline_verifier` returned **RED-CONFIRMED**. The migration test gives rc 1 with 9 FAIL and 3 PASS (primary key, indexes, fresh apply order), because `003` still uses `body` and V5 doesn't alter the V3 table. A V5 that drops and recreates the table is caught by the legacy-row check. Findings are LOW only: error-text matching, and the exact `now()` default comparison. The 2.2 file's one-line diff was verified by structure only, because the old file isn't in git. Awaiting owner approval.

**Owner approval (10 Nov):** "approve" for both amended baselines (`860f4de3…` and `2a9e3fe5…`). Task 2.1 is unticked until it is re-verified.

## Tasks 2.1 and 2.2 Green with the `text` column (10 Nov)

**Summary:** `implementer` changed four files:
- `003`: the column is now `text` (LF `3dbc7aad…`).
- V5: rewritten as an idempotent ALTER of the Maintainer V3 table (LF `6e20f9cc…`).
- The discussion service: it uses `text` (LF `ad3f4356…`).
- `JdbcMaintainerRepository`: one line, so the comment INSERT sets `author_role='MAINTAINER'` (LF `23589178…`).

No test fixtures changed. Results:
- Migration test: 11/11 PASS.
- Discussion API: 17/17.
- Reporter API: 116/116.
- Full auth Gradle suite: 143 tests, 0 failures, including the Maintainer integration and unit tests.
- `docker build`: OK.
- Real-session check on the schema built by the auth server's own Flyway run: V3–V5 applied. A Reporter's comment POST gave 201 and GET gave 200, with the author from the session and `displayName` from `app_accounts`, and the report version unchanged. A Maintainer comment added through the existing Maintainer endpoint appears in the Reporter GET with role MAINTAINER.

Gaps:
- The Maintainer owner's sign-off is still pending (1.1).
- The Maintainer comment endpoint still bumps the report version, which is its existing behaviour and conflicts with D3 until 1.1.
- The V5 backfill on real legacy rows is exercised only by the migration test.

A fresh `candidate_verifier` and `security_reviewer` are running.

### Security review, 2.1 and 2.2 (10 Nov)

A fresh `security_reviewer` gave **PASS for the 2.2 scope, with no CRITICAL or HIGH findings**.

**What held:**
- Identity comes only from the session (spoofing headers, query, method-override and body keys were all tried).
- The participation check and the insert are one atomic INSERT…SELECT, and the deactivation recheck is effective.
- Not-found responses are byte-identical apart from `requestId`, in both the harness and the deployed app.
- Pagination bounds hold, SQL is parameterised, response fields match the contract, and headers and CSRF are correct.
- D3 holds.
- V5 is idempotent and matches `003`.
- Nothing test-only is packaged.

**MEDIUM findings (block deployment, not merge):**
1. No comment length cap or rate limit yet, and the Reporter mount is live. Reporter comments also feed the Maintainer AI queue summary (`JdbcQueueSnapshotReader.java:103`, the latest 3 updates), so they can crowd out history events and are an injection surface. Fix: don't deploy 2.2 without 2.3, and add a check that the AI context frames comments as untrusted data.
2. Existing Maintainer comments, written under MNT-DISC-01 before Reporters could see discussion, become visible to the owning Reporter after the V5 backfill. Sign-off is needed under 1.1, or legacy rows are excluded.

**LOW and INFO findings:**
- A lone surrogate is stored lossily as `?`, and U+0000 gives 500. Both are deferred to 2.3.
- An authenticated OPTIONS gets Spring's 200 (pre-existing, same as the 1.4 LOW).
- A rolling deploy briefly breaks the old Maintainer INSERT after V5 sets NOT NULL.
- A deactivation racing a post gives 401 rather than 403, which is the existing pattern.

### Candidate verification, 2.1 and 2.2 (10 Nov)

A fresh `candidate_verifier` returned **PASS**:
- Hashes: both baselines and the contract are unchanged.
- Scope: the `git status` scope matches the candidate exactly.
- Tests: migration test 11/11, discussion API 17/17, Reporter API 116/116. Full auth Gradle suite 143/143 against postgres:17-alpine, with Flyway applying V3–V5 and the result matching `003`'s shape.
- Code review: matches the contract. `StrictJson` and `TextRules` are behaviour-preserving moves. The `run-reporter-api.sh` change is acceptable test infrastructure.

**INFO findings:**
- 405 is checked before the session (the existing ordering; the contract doesn't fix it).
- `displayName` is null for a missing account (the contract doesn't fix it).
- Developer and Maintainer roles fail closed until 2.4.

Tasks 2.1 and 2.2 are ticked. **Deployment hold:** don't deploy until 2.3 (length cap and rate limit) is done and 1.1 is signed off, including the visibility of existing Maintainer comments to Reporters (security MEDIUM 1 and 2).

## Reporter web app (10 Nov, owner instruction, no TDD)

**Summary:** On the feature owner's instruction ("dont use tdd Do the front end for reporter using github alike design"), I built `src/frontend/reporter`, a React 19 and Vite 8 app at `/reporter/` in an issue-tracker style:
- a list with open/closed state icons, status labels, filters and pagination;
- a report page with a state badge and a timeline (description first, then comments), plus a sidebar;
- a Write | Preview composer using the shared `MarkdownEditor`;
- `MarkdownView` rendering in the shared worker;
- create and edit (while `SUBMITTED`);
- comments on any status;
- drafts kept on failure, fixed error messages, and the `Retry-After` wait time shown.

No independent baseline, candidate or security review was run, on the owner's instruction. Both `reporter-markdown-reports` (design non-goal) and `report-discussion` (design non-goal) exclude a Reporter app, so this is outside both changes' tasks. A new OpenSpec change should record it.

**Server wiring:**
- `AuthConfiguration` adds a `/reporter/` forward and anonymous GET on `/reporter/**`.
- `package.gradle` copies `-PreporterFrontendDist` to `static/reporter`.
- The Dockerfile has a `reporter-frontend` stage, which also runs `npm ci` in `shared/markdown`.
- `src/frontend/reporter/.gitignore` ignores `node_modules` and `dist`.

**Checks:**
- `npm run verify` in `src/frontend/reporter`: typecheck, vitest 8/8, and `vite build`. The worker chunk has no `document.` reference.
- `docker build -f src/backend/platform/deployment/Dockerfile .`: OK. This needed `start.sh` converted to LF locally; it was restored afterwards.
- The image ran against postgres:17-alpine, with accounts created by `provisionAccount`:
  - `/reporter/`, the JS, CSS and worker assets all return 200, and the API returns 401 without a session.
  - A Reporter signs in, creates a report (201) and comments (201).
  - A Maintainer comment (201) appears in the Reporter thread with role MAINTAINER.
  - A 4th create within a minute gives 429 with `Retry-After`.

**Not done:**
- No real-browser visual check.
- The app isn't in `workflow/verify.py`, the CI frontend matrix or the OSV lockfile scan.
- `.gitattributes` for `*.sh` is still missing.

**Seen in the run:** the Maintainer comment endpoint bumps the report version, so a Reporter edit opened before a Maintainer comment gets 409 `VERSION_CONFLICT` (the D3 conflict, task 1.1).
