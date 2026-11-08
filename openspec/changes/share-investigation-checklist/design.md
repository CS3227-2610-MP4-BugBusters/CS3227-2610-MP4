# Design

## Context

The Maintainer branch has a demo-backed read-only frontend. Its shared application contracts are still proposed. See [the proposal](proposal.md) for the prerequisites and [the checklist spec](specs/investigation-checklist/spec.md) for the behavior contract. The design assumes the application will provide authenticated identity and persisted report ownership and assignment.

See the [storage and transaction contract](../../../workflow/developer/checklist-storage-contract.md) for the shared identity, field, version, and locking decisions confirmed by the Developer owner, plus the integration tests.

## Goals / Non-Goals

Keep draft and shared content separate and enforce the same report-specific access rules for UI and API requests. AI drafting and assessment are outside this change.

## Decisions

- Keep one checklist per report, with an ordered draft and a copy of the most recently shared steps. Save persists the draft. Share atomically copies the persisted draft to the shared version. The proposal assumes sharing is an explicit publication step, so the Reporter can still see the last shared copy while the Developer saves further edits.
- Resolve the caller's identity and role from the authenticated session and authorize against the target report's stored ownership and current assignment on each request. Developer reads, saves, and shares require an assignment match. Reporter reads require an ownership match. Maintainer reads require authorization to view the report. Both roles receive only the shared checklist steps. The report screens use these same server checks.
- Keep the authorization check and checklist mutation consistent with assignment updates. An in-flight request from a former assignee must fail if the reassignment takes effect before the mutation.

## Access by role

| Actor | Draft | Shared copy | Save / Share |
| --- | --- | --- | --- |
| Currently assigned Developer | Read | Read | Allowed in eligible states |
| Unassigned Developer, including former assignee | Denied | Denied | Denied |
| Reporter who filed the report | Denied | Read | Denied |
| Reporter for another report | Denied | Denied | Denied |
| Maintainer authorized to view the report | Denied | Read | Denied |

Share uses the persisted draft, even if the editor contains unsaved changes. An empty draft can be saved privately. The server checks the persisted draft inside the Share transaction and rejects Share if it has no steps, leaving the shared copy, report version, and any pending proposal unchanged. The UI explains the rejection with “Add at least one step before sharing.”

## Approved Save and Share lifecycle

| Report state | Save | Share |
| --- | --- | --- |
| Assigned or In progress | Update the private draft. | Publish changed steps and keep the report state. |
| Resolution proposed | Update the private draft and keep the pending proposal. | Publish changed steps, supersede the proposal, and return to In progress. |
| Submitted or Closed | Deny. | Deny. |

Save uses a draft revision separate from the report version used for closure. Check assignment and report state against current server data before saving. If the shared copy already contains the same ordered steps as the saved draft, Share leaves the proposal and report version unchanged. A first share creates the shared copy.

A Share that changes the published copy checks the expected report version and uses the same report lock and transaction as closure. In that transaction, verify assignment and state, publish the saved draft, increment the report version, and, when a proposal is pending, retain it as superseded and return the report to In progress. If Share commits first, closure against the previous version fails. If closure commits first, Share fails and the previous shared copy remains visible.

## Integration decisions

- **Optional notice.** Decide whether an authorized Maintainer should see “Unshared checklist changes” when a server-saved draft differs from the shared copy. The proposed notice would expose no draft content and would ignore unsaved browser edits. Keep it outside the implementation tasks until agreed.
### CL-05 wording for Maintainer integration

The Maintainer product and technical specifications and MT-11 currently live on [PR #1](https://github.com/CS3227-2610-MP4-BugBusters/CS3227-2610-MP4/pull/1). Apply the following alignment when those files reach the integration branch.

- **Lifecycle:** During Resolution proposed, a private checklist Save changes only the draft revision. Share with unchanged steps leaves the proposal and report version intact. A Share that changes the published steps atomically supersedes the proposal, increments the report version, and returns the report to In progress. Save and Share are denied in Submitted and Closed.
- **Versioning:** The report aggregate version tracks changes to the published checklist. Draft saves use their own revision. Changed Share and closure check the report version within the shared transaction boundary.
- **MT-11:** Preserve the existing report detail, evidence, and note edit races. For checklists, verify private Save and unchanged Share preserve the pending proposal and report version. Interleave changed Share and closure against real PostgreSQL in both commit orders. If Share wins, it publishes the new copy and closure of the superseded proposal fails. If closure wins, Share is denied and the prior shared copy remains visible. Check both outcomes for partial writes.

## Risks / Trade-offs

- A report detail response could expose the private draft if it serializes the full checklist. Apply the same access checks and field selection to any report response that includes checklist data.
- Saving and sharing are separate actions. Show which copy the Reporter and Maintainer can see and confirm sharing only after the server persists the shared copy.

## Migration Plan

Add checklist storage after report persistence exists. Existing reports can start with an empty checklist and no shared version. Deploy the authorization checks with the checklist API before exposing the report controls.
