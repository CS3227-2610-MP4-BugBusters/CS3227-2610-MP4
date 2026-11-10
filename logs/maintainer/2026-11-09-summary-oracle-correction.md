# Maintainer summary test-oracle corrections

Corrected three backend test expectations that contradicted the agreed summary design: the full queue is selected oldest-first, field truncation includes capped latest updates, and a HIGH filter test supplies a matching HIGH snapshot. Corrected the summary UI tests so Closed remains available to queue browsing while generation is disabled for that filter, result filters echo the request, the successful mutation fake uses the existing void-returning operation, and the rendered summary exposes both included and excluded report IDs. The Closed-filter test now checks visible Closed reports after list loading. Frontend typecheck passed; the focused Red run still fails at the missing summary control, as expected. The backend Gradle result and independent backend review are recorded below.

## Technical evidence

- Task: MNT-P4 summary oracle correction; the coordinator later requested the bounded UI assertion corrections documented below.
- Starting revision: `5355a23ce0a3c9bf9fbb700aafb3be0bc4ebe76b`.
- Changed files: `src/backend/auth/src/test/java/mp4/maintainer/summary/QueueSummaryTest.java` and `src/frontend/maintainer/src/queue-summary-ui.test.tsx`. No production files, runner/configuration, or fixtures were changed.
- Backend correction details:
  - `contextStopsAtTwentyReportsAndTwentyFourKibWithoutSplittingAReport` now asserts included IDs in order 22 through 3 and excluded IDs 2 then 1. The fixture sets `updatedAt = NOW - i seconds`; larger `i` is older, so design order selects higher IDs first.
  - `fieldCapsUtf8EscapingAndLatestThreeUpdatesAreAppliedAndReported` now expects `latestUpdates` in `truncatedFields`, because the fixture drops the fourth update and truncates a selected update to the 300-code-point cap.
  - `cacheKeySeparatesActorSessionGenerationFiltersAndCompleteSnapshotChanges` now uses a HIGH-priority report in the snapshot for the HIGH-filtered request, matching the filter contract. It then restores the ANY-filter snapshot before testing later snapshot changes.
- UI correction details: Closed filter option is asserted enabled and still filters the report queue; summary generation is disabled while Closed is selected; generated result fixtures echo request filters; mutation fake resolves through the existing `Promise<void>` operation.
- Initial check: `npm run typecheck` from `src/frontend/maintainer` — PASS (exit 0).
- Backend verification: not run by this role, per task direction; implementer owns Gradle execution.
- Backend test SHA-256: `0B83E13C75736935DA6674644E8C18D6BE60178B2A113CD883BE65DD4438F32A`.
- UI test SHA-256: `C3268ABC5EEDE5A4AE810E41749304317A3C473D7EE8A4E54657A66701626124`.
- Role/model: test_designer; configured model `gpt-6-luna`, reasoning effort `high`; actual runtime model and token usage are unavailable in the trace.
- Remaining review: a fresh verifier should validate these changed hashes and reproduce the backend baseline on revision `5355a23ce0a3c9bf9fbb700aafb3be0bc4ebe76b` in the requested isolated worktree.

## UI baseline assertion correction and recheck

Added exact display evidence for the excluded report ID within the summary region. In the combined HIGH / Assigned / Alex Tan filter test, selecting Closed leaves the global queue filter available; the test waits for both visible Closed report buttons (`Report 15` and `Report 35`) after the updated matching count, then asserts summary generation is disabled and no second summary call occurs. The successful priority fake resolves as `Promise<void>` per the existing `Queries.updatePriority` seam.

- Check: `npm run typecheck` from `src/frontend/maintainer` — PASS (exit 0).
- Red check: `npm exec vitest run src/queue-summary-ui.test.tsx --reporter=dot` from `src/frontend/maintainer` — FAIL (exit 1), 1 file / 10 tests failed. The baseline has no accessible Generate summary control; failures occur before the summary-specific assertions, demonstrating the missing UI behavior rather than an unresolved import/type setup.
- Updated UI test SHA-256: `C3268ABC5EEDE5A4AE810E41749304317A3C473D7EE8A4E54657A66701626124`.
- No application code changed. No Gradle command was run by this role.

## Independent corrected backend verification

Confirmed valid Red with a fresh baseline verifier (gpt-6-luna/high). Copied the
corrected test into an isolated worktree at `5355a23`, retaining the original
summary stub. The LF-normalized test hash matched both copies:
`0b83e13c75736935da6674644e8c18d6be60178b2a113cd883be65dd4438f32a`.
Only that test differed from the isolated checkout. Ran `gradlew.bat --no-daemon
test --tests mp4.maintainer.summary.QueueSummaryTest` from its auth directory with
Adoptium 21: compiled successfully, 21 failures at missing behavior, zero errors
or skips. Reviewed all three corrections against the unchanged design.

The historical manifest matched 28 of 30 inputs. The reviewed build integration
and corrected test account for the two differences; recorded the current 30
inputs in `2026-11-09-summary-postmerge-inputs.sha256` without replacing the
historical manifest. Proceeded under the feature owner's standing instruction.
No live provider or PostgreSQL behavior is established by this focused check.
