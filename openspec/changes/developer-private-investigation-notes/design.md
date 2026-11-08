# Design: Developer private investigation notes

## Data and access

Store one plain-text notes value per report, with a separate nonnegative notes revision. Before any notes record exists, an authorized read returns empty text and revision 0. Clearing notes saves empty text and increments the revision; retain the revision even for empty text so an older tab cannot restore deleted content. The UI can label this operation Delete or Clear. No separate note-entry history is required by this change.

Resolve the actor from the trusted session, not request data. Only the active, currently assigned Developer may read or modify notes. Reporter, Maintainer, unrelated Developer and former assignee requests are denied without notes text or revision. Check direct API and report-detail projections, logs and error messages. Once reassignment commits, D2 may read/edit the existing value and D1's next request is denied; delivered content cannot be retracted from a previous browser response.

## Lifecycle and concurrency

Allow changes in Assigned, In progress and Resolution proposed. A currently assigned Developer can read notes on a Closed report but cannot change them. Submitted reports do not allow modification. All modifications carry an expected notes revision. After checking authorization and state, a stale revision returns a conflict without changing the value. Preserve unsaved UI input so the Developer can refresh and decide how to proceed.

Private saves and clears—including during Resolution proposed—update only notes and notes revision. They do not increment the aggregate report version, change the status, or invalidate, replace or supersede a resolution proposal. This follows the owner's decision for private notes and the existing CL-05 private checklist Save. The merged Maintainer change already reflects CL-05 for checklists, but its investigation-note invalidation wording needs reconciliation for this newly defined private field before implementation. A separate revision is required so private notes do not silently overwrite one another.

Use the report-first lock order shared by reassignment and closure. Recheck active role, assignment, eligible report state and expected notes revision under the transaction before committing a notes change. If reassignment or closure commits first, reject a stale writer as appropriate; if the notes write commits first, it must not change the proposal or report version. Never let a denied or stale request mutate notes.

## API and UI boundary

The exact route, length limits, error representation, and shared application wiring must follow the final shared API contract; they are not fixed by this change. Regardless of UI, unauthorized direct requests must be denied. Display notes as plain text, not executable markup. Real session, shared database and report detail integration require separate tests from synthetic fixtures.

## Proposed alignment with merged Maintainer change

Compared with `origin/main` at `17cf11b0757df64d0a723f0ab962a5643494b9f6` (merged PR #3). Its `complete-maintainer-role` change already implements the agreed CL-05 **specification wording** for private checklist Save, unchanged Share and changed Share; no checklist reversal is needed. The following is a proposed reconciliation for private notes, not a claim that integration or team review is complete:

1. In `specs/maintainer-resolution-review/spec.md`, MNT-RES-02, distinguish private Developer investigation notes from shared report detail, response and evidence: private note save/clear leaves the proposal, status and aggregate report version unchanged while incrementing the separate notes revision. Keep proposal invalidation for authorized shared-source edits and changed checklist Share as otherwise agreed.
2. In `specs/maintainer-service-integration/spec.md`, MNT-READ-SERVER, explicitly omit Developer private notes and their revision from Maintainer report detail and nested responses, in addition to private checklist drafts and draft revisions. In the `maintainer-report-browser` baseline, distinguish any existing shared investigation notes from this private Developer notes field; the latter must not be displayed.
3. In `design.md`, exclude private notes from published source version increments and invalidating-edit/closure races; require report-first locking and a separate notes revision. Do not use private notes as Maintainer summary context. Preserve the existing CL-05 checklist requirements.
4. In `verification.md` MT-11 and `tasks.md` 3.3, add private-note save/clear during resolution review as preservation cases, and confirm no private notes or revision appear in Maintainer reads. Keep changed Share versus closure in both commit orders, along with applicable shared-source edit races. Test private notes against reassignment and closure separately under their eligible-state rules.

The Developer branch now contains these proposed wording changes in the merged Maintainer artifacts. They remain subject to Maintainer-owner review and integrated verification; no team approval or implementation is claimed. The team must separately decide which Reporter-owned shared-source edits invalidate a proposal; this notes change does not decide that question.
