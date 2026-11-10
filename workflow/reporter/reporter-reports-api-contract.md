# Reporter reports backend contract

Status: revision 1 (RPT-01..08) **approved** 9 November 2026 (sha256 2153c287).
Revision 2 (edit and hard delete, sha256 9d0ad9b0) was approved and then
superseded on 10 November 2026, when the owner replaced delete with close.
Revision 3 (RPT-09 edit, RPT-10 close, sha256 575a27c8) was approved and then
superseded on 10 November 2026, when the owner removed the Reporter close action
because closure is Maintainer-only. Revision 4 (RPT-09 edit only) **approved** by
the Reporter feature owner, 10 November 2026 (task 2.8). Source requirements:
`openspec/changes/reporter-markdown-reports/specs/reporter-reports/spec.md`
(RPT-01..09) and design D6–D8, D10 and D17. Once approved, this file is read-only for every
agent. Changes go back to the feature owner and reset any approved Red baseline
that depends on them.

## Trusted actor

The server resolves the actor `{ id: UUID, role, active }` from the session on
every request. The local harness supplies a synthetic session only. Real sessions
come from `add-shared-authentication`.

| Condition | Response |
| --- | --- |
| Missing or invalid session | 401 `UNAUTHENTICATED` |
| Inactive actor, or role is not `REPORTER` | 403 `FORBIDDEN` |

Identity or role in the body, query or headers never selects the actor.

## Fields

A report has exactly two Reporter-authored fields:

| JSON field | Column | Create and edit | Rules |
| --- | --- | --- | --- |
| `title` | `title` | required | Non-blank. At most 200 UTF-16 code units. No `\r` or `\n`. Plain text. |
| `description` | `description` | required | Non-blank. At most 20,000 code units. Markdown source. |

- Both fields are JSON strings. `null` or any other type is a validation failure.
- Neither field may contain U+0000 or an unpaired surrogate.
- "Blank" means empty, or made only of Unicode whitespace.
- Values are stored and returned **exactly as submitted**, with no trimming,
  normalisation or rendering. The server never emits HTML.
- Reproduction steps, expected and actual behavior, and environment are **not**
  report fields. The future improve-report AI feature may suggest them as
  missing information for the Reporter to add to the description.

## Endpoints

### `POST /api/reporter/reports`

The body is a JSON object containing only the fields above. Any other key,
including `id`, `reporterId`, `status`, `version`, `assigneeId`, `createdAt` and
`updatedAt`, returns 400 `VALIDATION_FAILED`. A body that is not valid JSON or not
an object also returns 400 `VALIDATION_FAILED`. Nothing is stored on any failure.

**201** with the created detail, and `Location: /api/reporter/reports/{id}`:

```json
{"id":"7d3c…","title":"Login fails","description":"## Steps\n1. Open /login","status":"SUBMITTED","version":0,"createdAt":"2026-11-09T05:00:00.000Z","updatedAt":"2026-11-09T05:00:00.000Z"}
```

The server sets `id` (random UUID), `reporter_id` (the actor), status
`SUBMITTED`, version 0, no assignee, and both timestamps to the same instant.

#### Rate limit: 3 reports per minute per Reporter

- A Reporter can create at most **3 reports in any rolling 60-second window**.
- The window is counted from that Reporter's stored `created_at` values. Reports
  are never deleted. Only successful
  creates count; requests rejected for any reason don't.
- A 4th create inside the window returns **429 `RATE_LIMITED`** and stores
  nothing. The response sends `Retry-After` with the whole seconds (rounded up,
  minimum 1) until the oldest create in the window is 60 seconds old.
- The check and the insert are atomic per Reporter. Concurrent creates can never
  bring the window above 3.
- Each Reporter has their own limit, so one Reporter hitting it doesn't affect
  another.
- Order of checks: session, then validation, then rate limit. An invalid request
  gets 400 even when the Reporter is over the limit.
- List, read and edit requests are not rate-limited by this rule.

### `GET /api/reporter/reports`

Optional `page` (positive decimal integer, default 1) and `pageSize` (positive
decimal integer, default 20, maximum 100), each at most once. Any other parameter,
including `reporterId`, returns 400 `VALIDATION_FAILED`.

The server filters by `reporter_id = actor.id` before ordering, paging and
counting. Results are ordered by `created_at` descending, then `id` ascending.

**200**:

```json
{"items":[{"id":"7d3c…","title":"Login fails","status":"SUBMITTED","createdAt":"…","updatedAt":"…"}],"page":1,"pageSize":20,"totalItems":1}
```

Empty results use the same shape. A page past the last one returns empty `items`
with the true `totalItems`.

### `GET /api/reporter/reports/{id}`

**200** with the same detail shape as the 201 response, when `{id}` is a report
whose `reporter_id` equals the actor.

**404 `REPORT_NOT_FOUND`** when the report doesn't exist, belongs to another
Reporter, or `{id}` isn't a canonical UUID. These three cases must be
byte-identical apart from `requestId`.

### `PUT /api/reporter/reports/{id}` (edit, RPT-09)

The body is a JSON object with exactly `title`, `description` and `version`.
`title` and `description` follow the field rules above. `version` is a JSON
integer of 0 or more, the value the Reporter last read. A missing, non-integer
or negative `version`, an invalid field, any other key, or a body that is not a
JSON object returns 400 `VALIDATION_FAILED`.

The update is one atomic write that succeeds only when the report belongs to
the actor, has no assignee, has status `SUBMITTED` and has the given version.
It then sets both fields exactly as submitted, increments `version` by 1 and
sets `updatedAt` to the current time.

**200** with the updated detail, same shape as the 201 response.

### Edit outcomes

Checks run in this order: session, validation, then the report.

| Condition | Response |
| --- | --- |
| No such report, another Reporter's report, or `{id}` not a canonical UUID | 404 `REPORT_NOT_FOUND`, byte-identical to the read 404 apart from `requestId` |
| The actor's report has an assignee, or a status other than `SUBMITTED` | 409 `REPORT_LOCKED` |
| The actor's unlocked report has a different version | 409 `VERSION_CONFLICT` |
| Otherwise | 200 |

Nothing changes on any non-success response. If an assignment and an edit
race, exactly one takes effect: either the edit succeeds first, or it returns 409
and the report stays assigned and unchanged.

Other methods on these paths return 405 `METHOD_NOT_ALLOWED`. The `Allow`
header is `GET, POST` on the collection and `GET, PUT` on a report.

Reporters never change a report's status. A `status` key in an edit body is 400
`VALIDATION_FAILED`, and any path below a report, such as `/close`, returns the
read 404 `REPORT_NOT_FOUND` and changes nothing. Closing is a Maintainer-only
lifecycle action.

## Errors and headers

```json
{"code":"VALIDATION_FAILED","message":"The request is invalid.","requestId":"…"}
```

- Codes: `VALIDATION_FAILED` 400, `UNAUTHENTICATED` 401, `FORBIDDEN` 403,
  `REPORT_NOT_FOUND` 404, `METHOD_NOT_ALLOWED` 405, `REPORT_LOCKED` 409,
  `VERSION_CONFLICT` 409, `RATE_LIMITED` 429, `INTERNAL_ERROR` 500.
- Messages are fixed per code. The body never echoes submitted text and never
  includes report content, SQL or a stack trace.
- Error responses don't say which field failed. This keeps the envelope identical
  to the Developer contract. Per-field hints are a possible later addition, not
  part of this contract.
- Every response sends `Content-Type: application/json; charset=utf-8` and
  `Cache-Control: no-store`.

## Not covered

Editing after assignment, any Reporter status change (including closing),
deleting reports, other lifecycle transitions, assignee details, discussion, checklist responses,
and AI, including the AI suggestions for reproduction steps, expected and actual
behavior, and environment.
