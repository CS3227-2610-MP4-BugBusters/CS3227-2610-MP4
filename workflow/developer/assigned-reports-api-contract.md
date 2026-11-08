# Developer assigned-reports backend contract

Status: Developer-owner decision for the local implementation, 7 November 2026. This defines the integration target; the shared application, authentication adapter, and migration do not yet exist in this checkout. The Maintainer's proposed read API and shared authentication must be reconciled during integration; this document does not claim they are implemented or agreed by other owners.

## Trusted actor and shared records

Authentication resolves a server-side actor with UUID `id`, role and active status on every request. A missing or invalid session yields 401 `UNAUTHENTICATED`; an inactive actor or a role other than `DEVELOPER` yields 403 `FORBIDDEN`. Never accept identity or role from a query, body or header supplied by the caller. Reject unknown query parameters, including `developerId`, with 400 `VALIDATION_FAILED`; they cannot alter the actor. Existing authentication owns session creation and expiry; the local test harness supplies synthetic actors only.

The shared report record used here has UUID `id`, non-null text `title`, nullable UUID `assignee_id`, and `status`, in addition to the existing checklist fields `reporter_id`, `version`, and `current_proposal_id`. The report's current `assignee_id` is authoritative. Use the existing shared report table and additive migrations; do not create a second production report store. Title constraints beyond non-null are deferred to the shared report creation contract.

The status wire values are `SUBMITTED`, `ASSIGNED`, `IN_PROGRESS`, `RESOLUTION_PROPOSED`, `CLOSED`. This list endpoint does not change state or define which assignments are legal in each state. It includes all records currently assigned to the actor, including closed reports that remain assigned.

## List endpoint

`GET /api/developer/reports` accepts optional `search`, `status`, `page`, and `pageSize` query parameters, each at most once. No request body is required. Server-side filtering by `assignee_id = actor.id` applies before search, status filter, pagination and totals. Recheck assignment for every request. Concurrent changes between requests can shift pages; a previously delivered response cannot be recalled.

- `search`: trim leading and trailing whitespace; empty means no filter. Otherwise match a case-insensitive literal title substring OR a case-insensitive exact complete report UUID. Title matching uses locale-independent, Unicode code-point case comparison without multi-character expansions (for example, `ß` and `ẞ` compare as one character, while `ß` does not match `ss` or a partial `s`). UUID matching is case-insensitive ASCII. Wildcard characters have no special meaning.
- `status`: if present, exactly one of the uppercase values above. Combine it with search using AND.
- `page`: positive decimal integer, default 1. `pageSize`: positive decimal integer, default 20, maximum 100. Reject invalid, repeated or unknown parameters with 400 `VALIDATION_FAILED`.
- Sort by UUID `id` ascending. Apply pagination after access and filters. The count and items in one response reflect the same database snapshot. A page beyond the last page has empty `items` and retains the true filtered `totalItems`.

HTTP 200 response (UUIDs shown as canonical strings):

```json
{"items":[{"id":"00000000-0000-0000-0000-000000000301","title":"Login fails","status":"ASSIGNED"}],"page":1,"pageSize":20,"totalItems":1}
```

Items contain only `id`, `title`, and `status`. Do not include descriptions, checklist drafts, or other report data. A response uses the same shape for an empty result. The UI can distinguish no assignments from no matches by whether filters are active; a nonzero total and empty page indicates an out-of-range page.

## Errors and integration

Return `{ "code": "...", "message": "...", "requestId": "..." }` on errors, with a nonempty safe message and request ID. Do not return report content or internal details on denial. This matches the checklist's shared error envelope and aligns validation naming with the Maintainer's proposed API. Avoid caching private list responses in shared caches.

The first local TDD slice tests unfiltered assignment visibility, D1/D2 isolation, reassignment, 401/403 and data minimization using a disposable database and synthetic session adapter. It must run against the application handler, not the reference adapter. Search, filtering and pagination have separate test tasks. Integration requires the real session adapter, shared report migration and route, and tests using real sessions; the local contract does not establish their availability.
