# Report check (clarify) API contract

Status: draft for the `reporter-report-clarify` change (RC-01..RC-09). It describes
the implemented endpoint. It is separate from the approved, read-only
`reporter-reports-api-contract.md`, which it does not change. Source requirements:
`openspec/changes/reporter-report-clarify/specs/reporter-report-clarify/spec.md`
and its `design.md`.

## Purpose

On the New Report screen, the Reporter can ask the AI which parts of the unsaved
draft are unclear and what information is missing. The server never stores the
draft or the result, and never changes a report.

## `POST /api/reporter/report-clarify`

- **Who:** an authenticated, active `REPORTER` session with a valid CSRF token
  (`X-CSRF-TOKEN` from `GET /api/auth/csrf`). The actor always comes from the
  session.
- **Request:** `Content-Type: application/json`, no query string, body at most
  128 KiB, exactly:

  ```json
  { "title": "Crash on save", "description": "it breaks after the second one" }
  ```

  `title` and `description` follow the report creation rules: title non-blank,
  at most 200 UTF-16 code units, no `\r`/`\n`; description non-blank, at most
  20,000 code units; neither may contain U+0000 or an unpaired surrogate. Any other
  key is a validation failure.

- **200 response:**

  ```json
  {
    "unclear": [ { "quote": "the second one", "question": "The second what: upload or page?" } ],
    "missing": [ { "category": "Environment", "question": "Which browser and version?" } ]
  }
  ```

  - `unclear`: 0-8 items. Each `quote` is 1-300 characters and appears exactly in
    the title or description; quotes are unique.
  - `missing`: 0-7 items. `category` is one of `Summary`, `Steps to reproduce`,
    `Expected behavior`, `Actual behavior`, `Environment`, `Frequency`,
    `Error messages and logs`, each at most once.
  - Every `question` is 1-300 characters on one visible line.
  - A question that contains a URL, an email address or a credential request is
    dropped with its item. If every item is dropped, the response is 502.
  - Both arrays empty means the model found nothing to ask.
  - Clients must render every string as plain text, never as Markdown, HTML or
    links.

## Errors

All errors use the shared envelope `{ "code", "message" }` and never contain the
draft, quotes, questions or model output.

| Status | Code | When |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | Bad JSON, extra keys, text rules, body too large, wrong content type, query string |
| 401 | `UNAUTHENTICATED` | No session, stale session, or account changed |
| 403 | `FORBIDDEN` | Inactive account or not a Reporter; missing or wrong CSRF token |
| 409 | `AI_BUSY` | This account already has a check running |
| 429 | `AI_RATE_LIMITED` | Over the task or key budget; `Retry-After` gives seconds |
| 502 | `AI_INVALID_OUTPUT` | Model output failed validation, or every item was dropped |
| 503 | `AI_UNAVAILABLE` | Live AI disabled, no Reporter key, or provider unavailable |
| 503 | `STORAGE_UNAVAILABLE` | The account store could not be read |
| 504 | `AI_TIMEOUT` | The 60 s deadline passed |

Session and account checks run before the model is called and again before a
result is returned. A result whose session or account changed in between is
discarded.

## Configuration

| Variable | Meaning |
| --- | --- |
| `SOC_LLM_ENABLED=true` | Required for any live AI |
| `SOC_LLM_API_KEY_REPORTER` | Reporter role key; without it the endpoint returns 503 |
| `SOC_LLM_MODEL_REPORTER` | Optional Reporter model (intended: `x-test-2`); blank falls back to `SOC_LLM_MODEL` |
| `SOC_LLM_TPM` | At least 77,824, the Reporter reservation, or every check is refused |

No `reasoning_effort` is sent; the provider default applies. The `x-test-2` slot
can change model without notice, so re-check it before relying on it.
