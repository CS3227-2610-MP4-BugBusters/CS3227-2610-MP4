# Reporter reports backend contract

Status: **Draft for Reporter feature-owner approval**, 9 November 2026. Source
requirements: `openspec/changes/reporter-markdown-reports/specs/reporter-reports/spec.md`
(RPT-01..07) and design D6–D8. Once approved, this file is read-only for every
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

| JSON field | Column | Create | Rules |
| --- | --- | --- | --- |
| `title` | `title` | required | Non-blank. At most 200 UTF-16 code units. No `\r` or `\n`. |
| `description` | `description` | required | Non-blank. At most 20,000 code units. |
| `reproductionSteps` | `reproduction_steps` | optional, default `""` | At most 20,000 code units |
| `expectedBehavior` | `expected_behavior` | optional, default `""` | At most 20,000 code units |
| `actualBehavior` | `actual_behavior` | optional, default `""` | At most 20,000 code units |
| `environment` | `environment` | optional, default `""` | At most 20,000 code units |

- Every field is a JSON string. `null` or any other type is a validation failure.
- No field may contain U+0000 or an unpaired surrogate.
- "Blank" means empty, or made only of Unicode whitespace.
- Values are stored and returned **exactly as submitted**, with no trimming,
  normalisation or rendering. Long-text fields are Markdown source. The server
  never emits HTML.

## Endpoints

### `POST /api/reporter/reports`

The body is a JSON object containing only the fields above. Any other key,
including `id`, `reporterId`, `status`, `version`, `assigneeId`, `createdAt` and
`updatedAt`, returns 400 `VALIDATION_FAILED`. A body that is not valid JSON or not
an object also returns 400 `VALIDATION_FAILED`. Nothing is stored on any failure.

**201** with the created detail, and `Location: /api/reporter/reports/{id}`:

```json
{"id":"7d3c…","title":"Login fails","description":"## Steps\n1. Open /login","reproductionSteps":"","expectedBehavior":"","actualBehavior":"","environment":"","status":"SUBMITTED","version":0,"createdAt":"2026-11-09T05:00:00.000Z","updatedAt":"2026-11-09T05:00:00.000Z"}
```

The server sets `id` (random UUID), `reporter_id` (the actor), status
`SUBMITTED`, version 0, no assignee, and both timestamps to the same instant.

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

Other methods on these paths return 405 `METHOD_NOT_ALLOWED`.

## Errors and headers

```json
{"code":"VALIDATION_FAILED","message":"The request is invalid.","requestId":"…"}
```

- Codes: `VALIDATION_FAILED` 400, `UNAUTHENTICATED` 401, `FORBIDDEN` 403,
  `REPORT_NOT_FOUND` 404, `METHOD_NOT_ALLOWED` 405, `INTERNAL_ERROR` 500.
- Messages are fixed per code. The body never echoes submitted text and never
  includes report content, SQL or a stack trace.
- Error responses don't say which field failed. This keeps the envelope identical
  to the Developer contract. Per-field hints are a possible later addition, not
  part of this contract.
- Every response sends `Content-Type: application/json; charset=utf-8` and
  `Cache-Control: no-store`.

## Not covered

Editing, lifecycle transitions, assignee details, discussion, checklist responses
and AI.
