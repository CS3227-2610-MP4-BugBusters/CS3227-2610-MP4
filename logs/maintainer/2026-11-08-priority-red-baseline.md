# Priority controls: Red baseline - 8 November 2026

Started task 2.1 in `complete-maintainer-role` after syncing the Maintainer checkout
to main at `d0b6f4813b834d2f525de360a29efd58f0f2ceba`. Created local branch
`codex/maintainer-priority`; kept the PR #4 review checkout separate.

Prepared tests only for the isolated priority UI. The current report view loads,
but the four feature cases fail because it has no report-priority editor. Existing
tests and TypeScript compilation pass. Application source, existing tests, runner
settings and OpenSpec task checkboxes are unchanged. No implementation, commit,
push, server authorization or database verification is claimed.

## Behavior covered

- Display the current priority and the explicit LOW/MEDIUM/HIGH/CRITICAL choices.
  Draft selection alone must not write; save requires an explicit action.
- Submit the selected report ID, displayed expected version and chosen priority,
  then refresh the visible row and report detail after success.
- Keep the attempted priority on a version conflict, preserve the stored display
  and avoid automatic retry or overwrite.
- Disable duplicate saves while the operation is pending.
- Pass a setup control showing existing detail loading and active-Maintainer UI
  access guards still work.

The test-only query extension proposes
`updatePriority(reportId, {expectedVersion, priority}, signal) -> Promise<Summary>`.
It follows the proposed versioned priority operation in design.md without importing
or adding a nonexistent production API. The accessible editor/save labels are
proposed UI choices for this baseline. Real HTTP/CSRF, authenticated server
authorization, atomic history/version persistence and PostgreSQL races remain
separate integration work; these fixtures cannot prove them.

## Technical evidence

Run ID: MNT-2.1-RED-2026-11-08. Inputs: the triage delta (T1), design.md priority and
version/UI policy, task 2.1, MT-04/MT-13, existing frontend sources and test runner.
Dispatched configured `test_designer` with `gpt-6-luna`, low effort, ten-minute
budget and writes limited to two new test/fixture files. Token/cache usage was
unavailable. No subagent dispatch or application-code edits were allowed.

Commands run by the designer from `src/frontend/maintainer`:

- Before additions: `npm.cmd test` passed the existing 30 tests in two files;
  `npm.cmd run typecheck` passed.
- Final `npm.cmd run typecheck`: PASS.
- Final `npm.cmd test -- src/priority.test.tsx`: expected exit 1, one setup case
  passed and four feature cases failed at the missing `Report priority` combobox
  after the report detail and Description heading loaded.
- Final `npm.cmd test -- src/App.test.tsx`: PASS, 16 tests.

Coordinator review tightened the draft before independent verification: added
explicit no-write spy assertions, scoped current-priority text to report metadata
rather than matching select options, settled the deferred write within React act,
removed an unused import and formatted the files. A transient duplicate local
variable during test editing was corrected before the final clean typecheck;
that compile failure is not counted as Red evidence.

The companion `2026-11-08-priority-red-inputs.sha256` records normalized UTF-8/LF
hashes of the tests, fixtures, runner settings and specification inputs. The raw
designer handoff hashes are BF3E84B89135C6BB4BC7DD1BF24F8BC4BFF4F130A224870DAD062828ED52A009
for priority.test.tsx and D9623E03BC91833A94B8372635143AC3EDF77E4EF343A40E048140E17E90BB1F
for priority.test-fixtures.ts.

## Independent baseline verdict

A fresh configured `baseline_verifier` (`gpt-6-luna`, low effort, seven-minute
budget, no edit scope) checked the base revision and all 12 normalized manifest
hashes: PASS. It independently reran the focused suite (one pass/four expected
missing-editor failures), typecheck (PASS) and App.test.tsx (16/16 PASS). It checked
that assertions are executable and can be satisfied by the intended implementation,
not merely that tests fail. No source, test, fixture, runner or log was changed by
the verifier. No missing fixture, import, contract setup or compilation error is
being passed off as Red.

The proposed adapter signature and accessible editor/save labels remain choices
for feature-owner approval of this local UI baseline. The existing read-only
baseline describes the earlier increment; the triage delta adds controls for the
new increment without claiming a real priority service exists.

After the independent verdict and explanation were presented, the feature owner
approved proceeding through implementation and verification toward an MR-ready
change on 8 November 2026. Approval applies to this frozen UI test baseline,
including the proposed local adapter seam and accessible labels. Recorded it before
dispatching the implementer. Full backend integration remains separate.

At baseline approval all 21 implementation tasks remained unchecked and application
code was unchanged. Checked this generated summary against the designer and verifier
handoffs. The tests, fixtures and runner settings must remain intact through Green;
any change to the approved assertions requires renewed baseline review and approval.
