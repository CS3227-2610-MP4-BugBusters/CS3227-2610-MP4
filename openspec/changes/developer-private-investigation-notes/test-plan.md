# Private investigation notes test baseline

## Current update

The [Developer-owner-approved local API and storage contract](../../../workflow/developer/private-notes-api-contract.md) now defines route, payload, errors and text limits. The earlier baseline assessment below remains a historical record. A notes fixture, runnable test target and synthetic session wiring still need to be prepared; real shared integration and Maintainer review remain open. No new Red is claimed.

## Plain-language summary

This baseline review covers task 3 and the task 4 access cases at the specified starting revision. No application behavior or frozen test was changed. The proposal and contract define the key invariants, but the notes route, request/response and error contract, text bounds, notes fixture/storage, and shared application session wiring are not defined. The existing runnable Developer HTTP harness covers checklist Save only. A valid notes Red test therefore cannot be run without inventing an interface or extending application/test infrastructure beyond this assignment. The cases below are ready to implement once the owner resolves those inputs.

## Focused cases

| ID | Behavior and assertion | Baseline status |
| --- | --- | --- |
| N1 | Authorized assigned active Developer reads absent notes as empty text, revision 0; save at revision 0; reread yields the saved text and revision 1. Report version is unchanged. | Blocked: no notes storage or read/write API contract. |
| N2 | Clear notes at revision 2; reread yields empty text, revision 3. A second client holding revision 2 cannot restore old text; stale result leaves notes and report/proposal state unchanged. | Blocked: no notes API/error contract or notes snapshot fixture. |
| N3 | Save and clear during Resolution proposed advance only notes revision. Status, aggregate report version, and pending proposal identity/content remain unchanged. | Blocked: no notes implementation/schema or integration fixture. |
| N4 | After reassignment commits, D1's direct read and write are denied without note text/revision or state mutation; D2 reads the preserved text/revision. | Blocked: no notes endpoint or notes fixture. Existing checklist former-assignee test is not a valid substitute. |
| N5 | Reporter, Maintainer, unrelated Developer, and former assignee cannot obtain text or revision from direct API or report-detail projection; denied attempts do not mutate state. Exercise direct notes reads and writes for all four identities, and inspect Reporter/Maintainer report-detail projections for absence of both fields. | Blocked: route and shared report-detail/session wiring are unresolved. |
| N6 | Closed report allows assigned Developer read but denies save and clear with stored note state unchanged. | Blocked: notes persistence/API absent. |
| N7 | Pending resolution proposal remains unchanged by successful notes save/clear; denied former-assignee mutation also leaves proposal unchanged. | Blocked: no notes fixture/endpoint; Maintainer wording reconciliation is still a prerequisite. |

## Runnable baseline inventory

- `src/backend/developer/test/checklist_save_stale_revision_test.py` and `checklist_save_former_assignee_test.py` exercise checklist-specific HTTP routes and checklist snapshots. They cannot establish a notes Red result and were not modified or run as proxy coverage.
- The existing synthetic HTTP launcher mounts only `ChecklistDraftHttpHandler`; it has no notes route. The checklist storage migration and fixture have no notes table/columns.
- The current spec explicitly leaves the notes route, limits, error representation, and shared application wiring to a final contract. The task list also leaves shared session, assignment, and report storage integration to task 2.
- Consequently, there is no focused notes test command that can be run against this baseline without guessing these missing inputs. No test was added and no Red verdict is claimed.

## Inputs needed to make Red runnable

1. Owner-approved notes API contract: route/method, read and write payloads, success response, denial/stale/state error status and codes, and text length/encoding rules.
2. Test fixture and snapshot contract for persisted notes text/revision plus report version/status/proposal; include D1/D2 and Reporter/Maintainer identities and report-detail projections.
3. Agreed synthetic runner wiring for notes API and sessions, or a specified storage-level test target that exercises the same transaction boundary.
4. Reconciled Maintainer lifecycle/read-projection wording for private notes, as required before implementation by DN-04 and task 1.

## Technical evidence

- Role: `test_designer`; configured model/effort in `.codex/agents/test_designer.toml`: `gpt-6-luna`, low. Actual execution model metadata is unavailable in this handoff environment.
- Task: prepare test baseline only; allowed edits were new planning/test files. No application code and no existing tests were edited.
- Starting revision: `79f4c9e51dd2395cca24a8d001db1d9aa9c805a0`.
- Context read: repository `AGENTS.md` instructions supplied by the owner; `.codex/agents/test_designer.toml`; `workflow/tdd.md`; proposal, design, tasks, and delta spec for `developer-private-investigation-notes`; `workflow/developer/checklist-storage-contract.md`; relevant checklist HTTP tests, launcher, and Developer backend README.
- Checks: inspected `git rev-parse HEAD` (matched the assigned revision), reviewed the existing routes/runner/fixture contract, and confirmed no existing notes route or storage fixture. No test command was run because the test target and interface are unspecified.
- Result: BLOCKED for a runnable Red baseline. Open requirements are listed above; no behavior failure is evidenced.
- File hashes and token/resource measurements: unavailable.
