# Report and checklist storage contract

Status: The Developer owner confirmed the shared identity, versioning, and transaction rules on 7 November 2026. The checklist migration and current-revision private Save passed local HTTP and PostgreSQL verification using a synthetic Developer session. The stale-revision HTTP regression also passed locally. Shared routing, authentication and schema integration, and Share remain pending.

## Shared records

| Record | Fields used by the checklist |
| --- | --- |
| Authenticated actor | User ID, role, and active status resolved from trusted server-side authentication data |
| Report | Report ID, `reporter_id`, nullable `assignee_id`, `status`, `version`, and current resolution proposal reference |
| Checklist | Report ID as a unique foreign key, ordered private draft steps, `draft_revision`, and ordered last shared steps |

A report with no checklist reads as an empty draft at revision zero for its assigned Developer. Reporter and Maintainer views return an empty shared list until the first publication. Those views never include draft steps or the draft revision. Apply the same projection when the checklist is embedded in report details.

## Reads and writes

Every operation checks access against the target report's stored ownership or current assignment. The server takes the actor identity from the session. Developer reads, saves, and shares require the current assignment. Reporter reads require ownership. Maintainer reads require permission to view the report. Reporter and Maintainer access is read-only.

| Operation | Required version input | Effect |
| --- | --- | --- |
| Save | Expected draft revision | Replace draft steps and increment only the draft revision. Empty drafts are allowed. |
| Share | Expected draft revision and report version | Publish the persisted draft. Checking the draft revision prevents a tab from publishing a newer draft it has not seen. Reject an empty draft. Matching shared steps are a no-op. A changed publication increments the report version. |
| Close | Expected report version and proposal identity/version | Follow the Maintainer's closure rules against the current report and proposal. |

Save and Share require Assigned, In progress, or Resolution proposed. Compare the expected versions after authorization. A mismatch returns `409 STALE_VERSION`. Failed writes preserve both checklist copies and the report state.

## Private Save HTTP contract

For local application integration, `GET /api/reports/{reportId}/checklist/draft`
returns `{ "steps": ["..."], "draftRevision": 2, "reportVersion": 7 }`
only to the active, currently assigned Developer. `reportVersion` is read
under the same report lock and is the version needed to Share. A report
without a checklist returns empty steps and draft revision zero. The response excludes shared steps and uses
`Cache-Control: no-store`. Inaccessible or missing reports return generic
403 without revealing draft contents or report existence. Real session and
account eligibility locking remain integration dependencies.

Use `PUT /api/reports/{reportId}/checklist/draft` with a JSON body
`{ "steps": ["Reproduce the bug"], "expectedDraftRevision": 2 }`.
On success, respond with HTTP 200 and `{ "draftRevision": 3 }`.
Resolve the actor from the authenticated session and verify the current
assignment on the server. On a stale draft revision, return HTTP 409
with `code: "STALE_VERSION"` in the shared
`{ code, message, requestId }` error response. Leave stored state intact.
The wire revision is limited to JavaScript's maximum safe integer
(`9007199254740991`). A Save that cannot increment within that range returns
409 `VERSION_LIMIT` without changing the draft, shared copy or report.

## Local Share HTTP contract

The local test adapter uses `POST /api/reports/{reportId}/checklist/share`
with `{ "expectedDraftRevision": 2, "expectedReportVersion": 7 }`.
On success it returns HTTP 200 with `{ "reportVersion": 8, "changed": true }`;
an unchanged Share returns the current version and `changed: false`. The
request publishes the server-saved draft, not steps supplied by the client.
Both revisions use nonnegative integers no larger than JavaScript's maximum
safe integer (`9007199254740991`). A changed Share that cannot increment the
report version within that range must fail without changing stored data.
Responses use `Cache-Control: no-store`. Invalid requests return 400,
unauthorized actors 403, and stale versions, ineligible states and empty
drafts return 409 with `STALE_VERSION`, `INVALID_STATE` or `EMPTY_DRAFT` in
the shared `{ code, message, requestId }` shape. A changed Share that reaches
the maximum safe report version returns 409 `VERSION_LIMIT` without mutation.
A retry with an old report version can be stale; clients should refresh after
uncertain outcomes. Real session, CSRF and shared-route integration remain
separate dependencies.

## Local shared checklist read contract

The local test adapter uses `GET /api/reports/{reportId}/checklist/shared`
and returns `{ "steps": ["..."] }` with `Cache-Control: no-store`.
It returns only the persisted shared copy, never draft steps or draft
revision. An active Reporter may read their own report; an active assigned
Developer may read the shared copy; an active Maintainer requires an
explicit trusted report-view permission check. A synthetic permission used
in local tests is not the production authorization policy. Missing or
inaccessible reports return 403 without exposing their existence.

## Transaction boundary

Save, Share, reassignment, and closure lock the same report row before reading or changing its checklist or resolution proposal. Recheck the actor's access, current assignment, report state, and expected versions inside that transaction. A changed Share during Resolution proposed publishes the steps, increments the report version, retains the proposal as superseded, and returns the report to In progress in one commit. A private Save or unchanged Share leaves the proposal and report version intact.

## Integration checks

- Share A, privately save B, and verify Reporter and Maintainer still see A. Share B and verify both see B. Reject empty Share without changing the published steps.
- Reassign D1 to D2. Deny D1's direct reads, saves, and shares. Let D2 read the stored draft. Check that denied requests leave both copies intact.
- Save from two clients with the same draft revision. The later stale write must preserve the first client's draft. Verify a stale Share cannot publish that draft on the second client's behalf.
- For MT-11, test private Save and unchanged Share during review, then force both commit orders of changed Share versus closure on PostgreSQL. If Share wins, closure is stale and the changed copy is visible. If closure wins, Share is denied and the previous copy remains visible.

The role implementations should use this contract. When integrating the Maintainer specs and MT-11, apply the same report-first lock order relative to checklist and proposal records. Reporter and Maintainer response fields must preserve the shared-only projection.
