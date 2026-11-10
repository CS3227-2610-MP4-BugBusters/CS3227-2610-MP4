# Report discussion backend contract

**Status:** Draft for feature-owner approval, 10 November 2026 (task 1.3).

**Source requirements:**
- `openspec/changes/report-discussion/specs/report-discussion/spec.md` (DSC-01..06)
- design D1–D6

Once approved, this file is read-only for every agent. Any later change goes back
to the feature owner and resets every approved Red baseline that depends on it.

## Trusted actor

On every request the server resolves the actor `{ id, role, active }` from the
session.

- **Identity is never taken from the client.** An author, identity or role in the
  body, query or headers never selects the actor or the author.
- **Inactive actors** get 403 `FORBIDDEN` on every path.
- **A missing or invalid session** gets 401 `UNAUTHENTICATED`.

## Paths

| Role | Thread path | Participant when |
| --- | --- | --- |
| Reporter | `/api/reporter/reports/{id}/comments` | `reports.reporter_id = actor.id` |
| Developer | `/api/developer/reports/{id}/comments` | `reports.assignee_id = actor.id` |
| Maintainer | `/api/maintainer/reports/{id}/comments` | always, for an existing report |

- An actor whose role doesn't match the path prefix gets 403 `FORBIDDEN`.
- Each path supports `GET` and `POST`. Every other method returns 405
  `METHOD_NOT_ALLOWED` with `Allow: GET, POST`.
- No path exists for a single comment. Paths below `/comments` return 404.

## When the report can't be seen

These responses follow each role's existing contract. In each row they are
byte-identical apart from `requestId`.

| Role | Missing report, non-participant, or `{id}` not a canonical UUID |
| --- | --- |
| Reporter | 404 `REPORT_NOT_FOUND`, identical to the RPT-05 read 404 |
| Developer | 403 `FORBIDDEN` "Access denied", identical to the Developer checklist response for a report that isn't assigned |
| Maintainer | 404 `REPORT_NOT_FOUND` |

All three are checked after the session check and after request validation.

## `GET …/comments`

**Query parameters:**
- `page`: optional, a positive decimal integer, default 1.
- `pageSize`: optional, a positive decimal integer, default 20, maximum 100 (the same rules as RPT-04).

Each parameter may appear at most once. Any other parameter returns 400
`VALIDATION_FAILED`.

**200 response:**

```json
{"items":[{"id":"…","body":"Can you attach the log?","author":{"id":"…","displayName":"Dana","role":"DEVELOPER"},"createdAt":"2026-11-10T05:00:00.000Z"}],"page":1,"pageSize":20,"totalItems":1}
```

- **Order:** comments are ordered by `createdAt` ascending, then `id` ascending.
- **`displayName`:** read from the auth accounts table at read time.
- **`role`:** the author's role when they posted.
- **Empty or past-the-end page:** returns empty `items` with the true `totalItems`.

## `POST …/comments`

**Request body:** a JSON object with exactly one key, `body`.

`body` must be:
- a non-blank string, where "blank" means empty or only Unicode whitespace;
- at most 20,000 UTF-16 code units;
- free of U+0000 and of unpaired surrogates.

`body` is Markdown source. It is stored and returned exactly as submitted: no
trimming, no normalisation, no rendering.

These all return 400 `VALIDATION_FAILED`:
- any other key, including `authorId`, `role` or `reportId`;
- a body that isn't a JSON object;
- invalid JSON or invalid UTF-8;
- a request over 128 KiB.

**201 response:** the created comment, in the same shape as an item above, with
`Location: <thread path>`.

**Report status:** posting is allowed in every report status, including `CLOSED`.

**Comments never change the report.** Posting never changes the report's
`version`, `updated_at`, `status`, `assignee`, `title`, `description` or proposal,
and appends no history row.

### Rate limit: 10 comments per minute per author

- An author can post at most **10 comments in any rolling 60-second window**,
  across all reports. The count comes from their stored `created_at` values.
- An 11th post inside the window returns **429 `RATE_LIMITED`** and stores
  nothing. The response includes `Retry-After` in whole seconds, rounded up with a
  minimum of 1, until the oldest comment in the window is 60 seconds old.
- The check and the insert are atomic per author, so concurrent posts can never
  push the window above 10.
- Requests rejected for any reason don't count.
- Checks run in this order: session, then validation, then report visibility,
  then the rate limit.
- GET requests are not rate-limited.

## Errors and headers

```json
{"code":"VALIDATION_FAILED","message":"The request is invalid.","requestId":"…"}
```

- **Codes:**

  | Code | Status |
  | --- | --- |
  | `VALIDATION_FAILED` | 400 |
  | `UNAUTHENTICATED` | 401 |
  | `FORBIDDEN` | 403 |
  | `REPORT_NOT_FOUND` | 404 |
  | `METHOD_NOT_ALLOWED` | 405 |
  | `RATE_LIMITED` | 429 |
  | `INTERNAL_ERROR` | 500 |

- **Messages:** fixed per code. A message never echoes submitted text, comment
  content, SQL or a stack trace.
- **Private data:** no response ever contains Developer private notes, checklist
  draft steps or the draft revision.
- **Headers:** every response sends `Content-Type: application/json;
  charset=utf-8` and `Cache-Control: no-store`.

## Not covered

- Editing or deleting comments, moderation, notifications, mentions, reactions,
  attachments, threaded replies and AI.
- The timeline merge. It happens in the client (design D6), and this contract
  doesn't define it.
