# Developer lifecycle integration

The combined application must serialize Developer start and resolution writes against account revocation and record the status change in shared history. A focused test with authenticated sessions and disposable PostgreSQL found that the current candidate does not yet satisfy these integration conditions. No fix is claimed at this baseline.

## Baseline and coordination

- Starting HEAD `8b74eac`, with an uncommitted merge of main at `b160011`.
- Test: `src/backend/auth/src/test/java/mp4/auth/DeveloperLifecycleHistoryIntegrationTest.java`, SHA-256 `e2b238889cf13690e9a5f8c6d7d45f12df11d184cef5bf0b960a55a717805140`.
- `python3 src/backend/auth/test/run_setup.py --tests mp4.auth.DeveloperLifecycleHistoryIntegrationTest` ran with a disposable PostgreSQL database and loopback access outside the sandbox. Setup control passed; four cases failed. Start and proposal did not append history; injected history failure did not roll back writes; the account-lock checks did not observe the expected block.
- An independent baseline verifier reran the cases. The account-lock assertions establish only that no block was observed, not the subsequent response. The Developer-owner integration contract explicitly records the actor-lock and atomic history requirements. Actual per-agent token counts and costs were unavailable.

## Candidate and cross-role integration

PR #10 was merged at `b160011`; the integration branch combines it with
Developer commit `8b74eac`. The merged fresh-database sequence uses the
Maintainer shared V3 and private-notes V4; a database already tracking the
former Developer V3 requires a separately designed upgrade, not a Flyway
checksum repair. The Maintainer frontend conflict retained the shared workspace
and Maintainer workflow.

The implementer changed Developer start and resolution writes to lock and
recheck the account before the report, and append generic shared history in the
same transaction. The focused lifecycle test passed. An independent security
reviewer inspected the final candidate and found no blocker in those changes;
concurrent session logout during a transaction remains unverified.

- Developer `npm run verify`: 53 tests passed, typecheck and build passed.
- Maintainer `npm run verify`: 115 tests passed, typecheck and build passed.
- `python3 src/backend/auth/test/run_setup.py`: combined backend check passed.
- `python3 src/backend/auth/test/run_setup.py --tests mp4.auth.CrossRoleJourneyIntegrationTest`:
  one real-session PostgreSQL test passed. It covers Maintainer assignment,
  Developer start, private notes, checklist Save/Share and proposal, Maintainer
  return, shared history and private-note omission from Maintainer detail.
- `openspec validate --all --strict --no-interactive`: 9 items passed.
- User requested a lean workflow: one scoped implementer and focused tests,
  with a separate security review for the authorization boundary. Actual
  per-agent token/cost counts were unavailable; no savings are claimed.

The shared AI gateway/live deployment and returned-reason Developer UI remain
open. The standalone cancellation experiment and failing regression remain in
the separate private-notes checkout and were not included in this candidate.

## Hosted CI fixture follow-up

The first PR #11 Java 17 assigned-reports job failed because its disposable
startup test cloned a database containing the new V3/V4 tables, then attempted
to drop `reports` without removing dependent shared tables. The fixture was
updated to remove those shared tables and migration functions from its cloned,
disposable databases before testing auth-only and incompatible startup. The
production migration was not changed. `src/backend/shared/test/run-shared-report-storage.sh`
then passed the storage and startup checks locally with disposable PostgreSQL.
Hosted CI must be rerun for the updated commit.
