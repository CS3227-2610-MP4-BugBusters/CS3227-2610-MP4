# Design

## Context

- The shared `reports` table (`src/backend/shared/migrations/001_create_reports.sql`)
  has id, title, description, reporter, assignee, status, version and proposal
  reference. It has no timestamps. Title and description are the only
  Reporter-authored fields (feature-owner decision, 9 Nov). The Maintainer DTO's
  `reproductionSteps`, `expectedBehavior`, `actualBehavior` and `environment` are
  not supplied by this backend.
- There are two backend patterns:
  - Developer: framework-free JDBC store + `com.sun.net.httpserver` handler, a
    synthetic-session test harness, and black-box Python API tests.
  - Auth: Spring Boot 4 / Gradle with Flyway.
  - No shared application adapter (`src/backend/platform/`) exists yet.
- The Maintainer frontend renders source fields as `<p className="text">{value}</p>`
  with `white-space: pre-wrap` (`App.tsx:434`). The Developer frontend has no
  report detail screen, only the checklist demo.
- The two frontends are separate Vite apps with their own `node_modules` and
  `npm run verify`. They share no code today.
- Requirements: see `specs/reporter-reports`, `specs/safe-markdown`, and the
  MNT-READ-05 delta.

## Goals / Non-Goals

**Goals:**
- One renderer implementation and configuration, so Preview is identical to
  what the Maintainer and Developer see.
- The Reporter backend core can later move behind the shared platform adapter
  without being rewritten.
- The server stays Markdown-agnostic. It stores text and never produces HTML.

**Non-Goals:**
- Server-side Markdown rendering or sanitisation of stored text.
- A Reporter frontend application. Only the shared editor component is built here.
- Syntax highlighting, Mermaid, math, emoji shortcodes, @mentions or `#123`
  issue links.

## Decisions

### D1 Store source, render on the client
Store Markdown source exactly as typed (RPT-03) and render only in the browser.
- Alternative: render HTML on the server and store or serve it. Rejected because
  it creates a second stored representation that can drift, and puts HTML in API
  responses. A future fix to the renderer would also require a data migration.
- The AI gateway and evaluation fixtures also read clean source text.

### D2 `react-markdown` + `remark-gfm`, no `rehype-raw`
- Without `rehype-raw`, raw HTML is never parsed into elements. It stays escaped
  text (MDR-02).
- Restrictions are enforced through the component map and URL transform, not by
  post-processing HTML:
  - `img` renders as its alt text (MDR-03).
  - `a` renders as a link only when `urlTransform` accepts the scheme (MDR-04).
  - `input` renders as a disabled checkbox.
  - Headings get no generated IDs (avoids DOM clobbering).
- Alternative: `marked` + DOMPurify on `dangerouslySetInnerHTML`. Rejected because
  it relies on a sanitiser being configured correctly at every call site, and
  `dangerouslySetInnerHTML` is the exact pattern the Maintainer spec forbids.
- Alternative: a hand-written subset parser. Rejected because GFM edge cases
  (tables, nested lists) would take longer than the deadline allows.
- Pin exact versions, matching how the repos pin React and Vite.

### D3 Shared package via local `file:` dependency
- Create `src/frontend/shared/markdown/`, a small package with its own
  `package.json`, `vitest` tests and `npm run verify`. It exports `MarkdownView`
  and `MarkdownEditor`.
- The Maintainer and Developer apps depend on it with
  `"@mp4/markdown": "file:../shared/markdown"`. React is a peer dependency, so
  only one React copy is loaded.
- Alternative: copy the component into each app. Rejected because two copies
  would break MDR-07 and MDR-06 (Preview must equal the display) the first time
  only one is changed.
- Alternative: an npm workspace at `src/frontend/`. Cleaner, but it restructures
  two existing apps owned by other people. Deferred.

### D4 Failure-safe rendering
Wrap `MarkdownView` in an error boundary that falls back to
`<pre className="text">{source}</pre>` (MDR-05). Also apply a nesting cap, either
a remark plugin that stops past depth 32 or the same pre-check on the source, so
the browser never runs a pathological render.

### D5 Reporter backend follows the Developer pattern
- `src/backend/reporter/`:
  - `ReportStore` (JDBC, framework-free)
  - `ReporterReportsHttpHandler` (JDK `HttpExchange`, Jackson)
  - a synthetic-session harness and launcher under `test/`
  - black-box `*_api_test.py` tests against a disposable PostgreSQL
- Keep validation and authorization in `ReportStore` and a small validator, not
  in the HTTP layer, so a later Spring or servlet adapter only maps requests.
- Alternative: build on the Spring Boot auth module now. Rejected because the
  shared adapter is unresolved (`add-shared-authentication` 1/9) and would couple
  this change to it.

### D6 Endpoints and status codes
- Endpoints:
  - `POST /api/reporter/reports` returns 201
  - `GET /api/reporter/reports?page&pageSize`
  - `GET /api/reporter/reports/{id}`
- Another Reporter's report returns **404 `REPORT_NOT_FOUND`**, not 403, so a
  Reporter can't probe whether report IDs exist (RPT-05). A malformed UUID in the
  path also returns 404.
- Status wire values are the DB values (`SUBMITTED`, ...), matching the Developer
  contract. The Maintainer's display labels (`Submitted`) stay a Maintainer
  adapter mapping. This is a known open mismatch that this change doesn't fix.
- Responses never include `assignee` details. They would need display names from
  the auth accounts table. Deferred.

### D7 Additive Reporter migration
`src/backend/reporter/migrations/002_add_report_timestamps.sql`:
- Adds `created_at` and `updated_at` (timestamptz, NOT NULL, DEFAULT now()).
- Adds an index on `(reporter_id, created_at DESC, id)`. It serves both the
  newest-first list and the rate-limit window count.
- Adds no new text columns.
- Applied after `001` and before the checklist migration, on fresh databases only
  (the same policy as SR-01).
- Length limits are enforced by the service, not by `CHECK` constraints, because
  they're measured in UTF-16 code units.
- Needs Developer owner sign-off because it alters the shared table.

### D8 Text limits match the Maintainer parser
20,000 UTF-16 code units for `description` equals MNT-READ-03's general text
bound, so every report the Reporter can create is one the Maintainer adapter
accepts. Title is capped at 200 code units, on a single line.

### D9 Developer description panel
Add a read-only `ReportDescriptionPanel` to the Developer app. It shows the title
as plain text and the description through `MarkdownView`, with a test page using
fixture data. It connects to the Developer report detail once that screen exists.
This needs Developer owner approval.

### D10 Rate limit counted from stored rows, locked per Reporter
Inside the create transaction:
1. `SELECT pg_advisory_xact_lock(<64-bit hash of reporter_id>)`
2. `SELECT count(*), min(created_at) FROM reports WHERE reporter_id = ? AND
   created_at > now() - interval '60 seconds'`
3. If the count is 3 or more, roll back and return 429. `Retry-After` is
   `ceil(min(created_at) + 60s - now())`, at least 1. Otherwise insert and commit.

Why this approach:
- The lock serialises one Reporter's creates (the RPT-08 concurrency scenario)
  without blocking other Reporters.
- Counting stored rows means only successful creates count, the limit survives
  restarts, and it holds across instances. No extra table or cache is needed.
- `created_at` uses the database clock (`now()`), so the window doesn't depend on
  the app server's clock.

Alternatives considered:
- An in-memory token bucket per Reporter. Rejected because it resets on restart
  and breaks with more than one instance.
- A separate rate-limit table. Rejected because it duplicates what `created_at`
  already records.
- Checking the limit before validation. Rejected so that an over-limit Reporter
  still gets the more useful 400 for a malformed request. The contract fixes the
  order: session → validation → rate limit.

Testing: the rollover scenario seeds R1's three earlier reports straight into the
fixture database with `created_at = now() - interval '61 seconds'` (and 59 s for
the still-limited case). This avoids a 60-second sleep and needs no production
clock hook. The concurrency scenario uses real parallel HTTP requests.

## Risks / Trade-offs

- [The Maintainer owner rejects the MNT-READ-05 change] → The Reporter backend and
  `safe-markdown` still ship. Maintainer adoption becomes a follow-up, and
  Maintainers see source text until then (the current behavior, still readable).
- [A dependency vulnerability or behavior change in react-markdown] → Pin exact
  versions. Security tests (MDR-02..04) run in the shared package and in each
  consuming app, so an upgrade that breaks safety fails `verify`.
- [Long GFM tables or code blocks overflow on mobile] → Scope
  `overflow-x: auto` to `pre` and `table` inside the view (MNT-READ-05 scenario).
- [`file:` dependency drift] → Consumers must reinstall when the package changes.
  The shared package's `verify` runs first in tasks, and each app's `verify`
  reinstalls it.
- [Prompt-injected Markdown from future AI output] → It goes through the same
  renderer, with no images and restricted links. Images are the main way to leak
  data with no click, and they're disabled.
- [The synthetic session isn't real auth] → RPT-06 is verified against the
  harness here. Real-session verification is a dependency task. Don't claim it
  as done.

## Migration Plan

Fresh-database only, like SR-01. Apply `001` → `002_add_report_timestamps` →
checklist migration. Rollback means rebuilding the disposable database.
Frontend rollback means reverting the dependency and component swap. No stored
data changes shape.

## Open Questions

- After the Maintainer owner reviews this, should the spec prose under
  "UI state and concurrency" ("Use no raw HTML or interpreted Markdown") be reworded
  during sync to match the modified MNT-READ-05? The intent is clear. Only the
  wording is undecided.
