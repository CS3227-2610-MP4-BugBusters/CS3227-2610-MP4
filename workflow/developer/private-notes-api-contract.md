# Private Developer notes: local API and storage contract

Status: Developer owner approved for local test preparation on 8 November 2026 after independent review. It makes the previously unspecified route, payload and limits concrete; it is not evidence that the endpoint, shared session or database integration exists. The Maintainer's private-notes alignment remains subject to shared review. Use the [OpenSpec change](../../openspec/changes/developer-private-investigation-notes/proposal.md) for product requirements and the [checklist contract](checklist-storage-contract.md) for report-first locking and shared authentication dependencies.

## Identity and storage

The server resolves `{id, role, active}` from trusted authentication; request data never selects the actor. Only the current active assigned Developer can read or write notes. Other roles, inactive users and unrelated or former assignees receive no note text or revision. Reporter and Maintainer report-detail responses, AI context, cache entries and logs must not include them. Do not treat previously delivered browser data as revocable.

One `developer_private_notes` row per report contains `report_id uuid` primary key/foreign key to `reports(id)`, `body text NOT NULL DEFAULT ''`, and `notes_revision bigint NOT NULL DEFAULT 0 CHECK (notes_revision >= 0)`. A missing row is logically empty at revision 0; reads do not create it. Clearing is an update to an empty string, not row deletion: retain and increment the revision. A first successful write creates the row at revision 1. The database migration assumes the shared `reports` table exists; actual migration ordering and identity/proposal foreign keys remain integration dependencies.

## HTTP interface

Use UUID report identifiers and same-origin authenticated requests. `GET /api/reports/{reportId}/private-notes` returns HTTP 200 `{ "text": "...", "notesRevision": 2 }`; a missing notes row returns `{ "text": "", "notesRevision": 0 }`. A currently assigned Developer may read in any report status, including Closed.

`PUT /api/reports/{reportId}/private-notes` replaces the whole field. JSON body: `{ "text": "Checked the stack trace", "expectedNotesRevision": 2 }`. On success return HTTP 200 `{ "notesRevision": 3 }`. Clearing uses the same PUT with `"text": ""` and the current expected revision. Each successful PUT, even with unchanged text, increments the revision exactly once. A retry with the old revision is stale, not an idempotent replay; clients should refresh before retrying. Do not accept actor or role fields, unknown or duplicate keys, non-JSON bodies, fractional/negative revisions or malformed JSON.

Text must be valid Unicode encodable as UTF-8, without NUL or unpaired surrogates, and at most 16,384 UTF-8 bytes. Whitespace and line endings are preserved exactly. Bound the complete request body to 64 KiB before parsing. The proposed limit is for the local contract and must be revisited if shared product limits differ. Revision values on the JSON wire are nonnegative integers up to `9007199254740991` (JavaScript's maximum safe integer); reject larger expected values. If incrementing would exceed that limit, reject without mutation using 409 `REVISION_LIMIT`.

## Authorization, state and errors

Authenticate and authorize using current stored assignment on each request. A missing/invalid session returns 401 `UNAUTHENTICATED`; an inactive or wrong-role actor, unrelated/former assignee, or absent/inaccessible report returns 403 `FORBIDDEN`, without protected data or existence detail. Invalid report UUID or body returns 400 `INVALID_REQUEST`; a body over 64 KiB returns 413 `PAYLOAD_TOO_LARGE`. Errors use `{ "code": "...", "message": "...", "requestId": "..." }` with a safe message and nonempty request ID. All responses use `Cache-Control: no-store`.

Writes are allowed only in `ASSIGNED`, `IN_PROGRESS`, or `RESOLUTION_PROPOSED`; a current assignee writing in another state receives 409 `INVALID_STATE`. After authorization and eligible-state checks, a mismatched revision returns 409 `STALE_VERSION`. No error returns stored text or its current revision. Failed writes leave notes, report, checklist and proposal unchanged. CSRF and cookie-session requirements must be provided by the shared authentication layer for unsafe requests.

## Transaction and concurrency

For each write, follow the shared eligibility lock order: lock/check the actor account before the report row, with any additional account locks acquired in the shared agreed order. Then lock the report before its notes or proposal rows, and recheck the current actor, active status, assignment and report state and compare the notes revision before mutation. Never acquire an account lock after the report lock. Reassignment and closure must use the same report-before-child lock order; a write that loses to either is denied according to the current assignment/state. A write that commits first changes only body and notes revision: it does not change report version, status, checklist or proposal. Do not reset the revision on clear or delete/recreate the row. Existing account eligibility locks, session revocation and lock ordering require reconciliation with the shared identity/lifecycle implementation before integration.

Use a disposable PostgreSQL fixture and synthetic sessions for the first local tests. Verify authorized empty/read/save/clear, stale retries and simultaneous clients, exact Unicode/size boundaries, wrong-role and former-assignee denial, reassignment/closure races and preservation of the pending proposal. Separately verify Reporter/Maintainer report-detail projections and real authenticated APIs when those components exist. No local synthetic result is evidence of the full integrated system.
