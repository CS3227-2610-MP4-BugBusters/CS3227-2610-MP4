# Design: Developer assigned reports

The [Developer assigned-reports backend contract](../../../workflow/developer/assigned-reports-api-contract.md) records the local implementation decisions and integration requirements.

## Boundary 1: identity and access

The server derives the active Developer identity from the authenticated session, never from a query parameter. Assignment is evaluated for each request. A former assignee loses access on their next request; already delivered data cannot be recalled. Authentication and the report schema are prerequisites, not implemented here.

## Boundary 2: list API (proposed)

`GET /api/developer/reports?search=...&status=...&page=1&pageSize=20` returns `{ "items": [{ "id": "...", "title": "...", "status": "ASSIGNED" }], "page": 1, "pageSize": 20, "totalItems": 1 }`. The route and field names are the Developer-owner implementation decision, pending integration with the shared API. IDs use the shared report identifier format. Only these fields are required; do not return report description, private checklist, or other users' data in this list.

- No status filter means all currently assigned reports, including closed reports if they remain assigned. Supported status values follow the shared lifecycle: `ASSIGNED`, `IN_PROGRESS`, `RESOLUTION_PROPOSED`, `CLOSED`; `SUBMITTED` can appear if assignment and lifecycle rules allow it.
- Trim search whitespace. Empty search means no search. Otherwise, case-insensitive substring match on title OR case-insensitive exact match on the complete report ID; status and search combine with AND. Search is literal, not a pattern language.
- Default page 1 and pageSize 20; both positive integers, pageSize at most 100. Sort by report ID ascending for deterministic pagination. Results and total are computed over the authorized filtered set. Changes between requests can move entries between pages; a page beyond the end returns an empty items array with the actual total.
- Invalid, repeated or unknown query parameters produce HTTP 400 with the shared error shape `{ "code": "VALIDATION_FAILED", "message": "...", "requestId": "..." }`; no session returns 401; non-Developer or inactive account returns 403. Use the same error shape. Reject caller-supplied identity parameters. Items and count use the same database snapshot; integration must reconcile the shared conventions.

## Boundary 3: dashboard presentation

The UI consumes the list without requiring a particular layout. An unfiltered zero total means no assignments; a zero total with a search or status filter means no matches. A nonzero total with an empty page means the requested page is out of range, not that there are no assignments. Loading and failure are distinct from empty results. Shared visual components, navigation and responsive layout remain pending team standardization.

## Verification and constraints

Use synthetic identities and assignments to test direct API requests, combined search/filter, reassignment, and pagination. Do not infer security from hidden frontend controls. Auth, actual schema and routing must be integrated before marking implementation complete.
