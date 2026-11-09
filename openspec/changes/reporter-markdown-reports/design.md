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
  - Footnotes (MDR-08, feature-owner decisions 9 Nov): keep remark-gfm's footnote
    output, with every ID prefixed `user-content-` (GitHub's guard against DOM
    clobbering).
    - Each `MarkdownView` instance uses its own prefix,
      `user-content-<instance>-`, taken from React `useId()` with the characters
      that aren't valid in an ID stripped, and passed as remark-rehype's
      `clobberPrefix`. Without that, two reports on one page would share IDs, and
      one report's footnote link could land on another report's footnote text.
      The security review found this content-spoofing case.
    - Allow a fragment `href` only when it starts with the case-sensitive
      `#user-content-` prefix and targets an ID generated in the same view. Every
      other fragment stays plain text.
    - Author-written links to a generated footnote ID (for example
      `[x](#user-content-…fn-1)`) are **allowed**. The owner accepted this,
      because the only effect is jumping within the same view.
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
`<pre className="text">{source}</pre>` (MDR-05). Also apply a nesting cap as a
**pre-check on the source, before parsing**.
- The security review measured the parse itself overflowing the stack:
  `"> - ".repeat(1000)` (4 KB) throws RangeError, and 20 KB takes 18 s. So a remark
  plugin that runs after parsing would be too late.
- The pre-check estimates the maximum blockquote and list nesting depth from line
  prefixes. Past depth 32, or past the 20,000-character bound, it renders the
  escaped plain-text fallback without calling the parser.
- **Revision (owner decision 1A, 9 Nov).** The second security review bypassed
  the line-prefix scan with a footnote-definition prefix (18.9 s). It also found
  that a wide table can exhaust memory, which an error boundary can't catch, and
  that emphasis, setext and lazy-continuation input can run 1–15 s. The owner
  chose to patch the pre-check rather than lower the formatting cap or move
  rendering to a Worker. Every check stays linear and only ever causes a false
  fallback to plain text, never a failure:
  - **Container markers.** Treat `[^label]:` as a container like `>` and list
    markers. Count `>` and list-marker-plus-blank across the whole line, not only
    a strict prefix.
  - **Tables.** Estimate the cell count as the delimiter-row column count times
    the following non-blank lines. Fall back above a cap of about 10k cells.
  - **Emphasis delimiters.** Fall back on a long run of one delimiter character
    (about 64 or more) or a high total density of `*`, `_` and `~`.
  - **Setext headings and lazy continuation lines.** Fall back above a count
    threshold.
  - Each threshold is tuned so the MDR-05 "known slow constructs" scenario
    renders in under 1 s while ordinary reports stay formatted.
- **Residual risk.** This is a blocklist. A slow construct nobody has found yet
  would still parse. That is now addressed by D11; the pre-check stays as a fast
  first filter.

### D12 No links (owner decision 9 Nov, replaces the link rules in D2 and D11)
After three rounds of link hardening (MDR-09, MDR-11, MDR-12) and a fourth
review that still found spoofable destinations (punycode joiners, blank
characters, titles), the owner chose: no links at all.
- The `a` component never renders an element. It renders its children, then
  ` (<destination>)` as text when the destination differs from the text.
  Autolinks, whose text equals the destination, show the address once.
- `urlTransform` and `safeUrl` no longer decide anything. Their code and
  MDR-09/11/12/13 are retired. Tasks 3.8 and 3.10 stay complete as history.
  The unapproved MDR-13 Red test (`MarkdownView.hidden.test.tsx`) is deleted.
- Footnotes render as superscript numbers and a footnotes section, with no
  fragment links and no back-references. The fragment trust pass is no longer
  needed. The per-view ID prefix stays for any generated IDs.
- Titles are dropped with the link, so they are never rendered.
- Approved tests that asserted a working link (subset, security, footnotes,
  urls, mailto, mailto2, suspicious) are rewritten to the new rules. This needs
  a new baseline review and owner approval (task 3.11).

### D13 Bound the worker result (3.9 security review, 9 Nov)
The worker moved parsing off the main thread but not rendering. A
19,708-character source of 35 tables passed the per-table cap and produced a
708k-node hast. Cloning and rendering it took about 6 s on the main thread in
jsdom.
- **Budgets.**
  - A pre-check caps total table cells at 10,000 across the source.
  - The worker counts hast nodes and replies `ok:false` above 25,000 nodes.
  - The main thread counts again iteratively before rendering, because the
    worker is untrusted. Above 25,000 nodes, it falls back to plain text.
  - `countHastNodes(root, limit)` stops at the first count above `limit`,
    returning `limit + 1`.
- **Shape and classes.** The main thread accepts only a `root` with a
  children array. `className` is allowlisted per tag to the classes the
  renderer generates:
  - `language-*` on `code`;
  - `contains-task-list`, `task-list-item`, `footnotes` and `sr-only`.

  Each element keeps at most one class: the first allowed one. The renderer
  never generates more than that, and the cap stops an untrusted worker from
  sending millions of allowed classes. The 3.9 review hung jsdom with 50
  elements carrying 200,000 classes each.
- **Runner.**
  - Only the request in flight when a worker dies is marked retried, so
    requests that were never sent are always sent.
  - The effect cleanup cancels unsent requests.
  - `runner.parse` is wrapped, so a synchronous throw or a non-promise return
    falls back to plain text.
  - Task 3.12.

### D14 One render path and a bounded sanitiser (3.12 security review, 9 Nov)
The 3.12 review found that the in-thread path, used when no Worker exists
(tests, SSR), had no node budget. Inputs such as `"- -\n"` ×5000 took 2.7 s.
- **One path.** The in-thread path renders through `toHast`, then
  `countHastNodes`, then the same sanitiser and `HastView` as the worker
  path. It falls back to plain text above `MAX_HAST_NODES`. react-markdown is
  no longer used to render. The approved dos and limits tests that mocked it
  are updated to mock the pipeline instead, with their assertions unchanged.
  That update needs re-approval.
- **Pre-check.** A new linear check caps the total number of list-item and
  blockquote containers across all lines, so `toHast` never sees
  `"- -\n"` ×5000. The limit is chosen so the three review repros fall back
  and ordinary nested lists still render.
- **Sanitiser.**
  - It sanitises once, before counting, and counts the sanitised tree.
  - It reads only the allowed property keys directly, instead of iterating
    `Object.keys`.
  - It scans at most 8 class entries.
- **Runner.** `pump` is a loop, not recursion.
- Task 3.14.

### D11 Parse in a Web Worker with a 1 s budget (owner decision C, 9 Nov)
The third security review found 6 more constructs under 20,000 characters that
take 2–12 s on the main thread, after the D4 patch: multi-table totals, nested
images, `]` runs, unclosed `<!--`, list and footnote lazy lines, and many
blockquotes. The blocklist doesn't converge, so parsing moves off the main thread.

- **Split the pipeline.**
  - A pure `toHast(source, prefix)` in `MarkdownView.pipeline.ts` runs
    remark-parse, remark-gfm, remark-rehype (with
    `clobberPrefix = user-content-<instance>-`) and the footnote-link trust pass.
    It returns a serialisable hast tree.
  - The main thread renders hast to React with `hast-util-to-jsx-runtime`, using
    the **same** components map and `safeUrl`. Every safety rule (images as alt
    text, the link allow-list, credentials, `mailto:`, heading IDs, the fragment
    trust check) is applied on the main thread at render time, so a bug in the
    worker can't bypass them.
  - The worker's hast is treated as untrusted structure. The main thread re-checks
    the `user-content-` prefix and same-tree targets.
- **Worker.** `markdown.worker.ts`, created with
  `new Worker(new URL("./markdown.worker.ts", import.meta.url), { type: "module" })`.
  Vite bundles this pattern for the apps that consume the `file:` package.
  - It is shared per page through a small runner module: one worker and a request
    queue keyed by request ID.
  - Each request has its own 1 s timer, started when the worker begins that
    request. On timeout the runner terminates the worker and resolves that
    request as "fallback".
  - Requests that were waiting behind the timed-out one are retried **once** on a
    fresh worker. A request that times out again falls back.
- **View state.**
  - It first renders `<pre className="text">{source}</pre>` (the complete,
    escaped source).
  - It sends `{ id, source, prefix }` to the worker. The pre-check runs first;
    over the limit, it never sends.
  - It swaps to the formatted output only if the result's source equals the
    current source (stale results are dropped).
  - An error from the worker or from the render keeps the plain text. The error
    boundary stays.
- **Environments without `Worker`** (jsdom, SSR): use the in-thread path,
  pre-check, then `toHast`, then the same renderer, synchronously. Existing
  approved tests exercise this path and stay valid.
- **Test seam.** `MarkdownView` reads the runner from a React context,
  `MarkdownParserProvider`.
  - The default is the worker runner when `typeof Worker !== "undefined"`, and
    in-thread otherwise.
  - Tests inject a fake Worker (or fake runner) with fake timers to prove the
    timeout, termination, retry, stale-drop and "no main-thread parse" behavior.
    They also check that a worker result renders the same DOM as the in-thread
    path, by running `toHast` in the fake worker.
- **Trade-offs.**
  - The first paint is plain text, then it swaps to formatted. This is usually
    within a few milliseconds, but it can flash.
  - The editor Preview goes through the same path, so in browsers it is async too.
  - Out-of-memory in the worker kills the worker, not the page.
- **Alternatives rejected.** Keep patching the blocklist: it doesn't converge.
  A lower formatting cap: it doesn't bound nested images or `]` runs at 5–6 KB,
  and it drops formatting for long reports.

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

### D7 Additive shared timestamp migration
`src/backend/shared/migrations/002_add_report_timestamps.sql`. It lives with the
shared reports schema, not in the Reporter folder (feature-owner decision 9 Nov),
because the timestamps belong to the shared record that every role reads.
- Adds `created_at` and `updated_at` (timestamptz, NOT NULL, DEFAULT now()).
- Adds an index on `(reporter_id, created_at DESC, id)`. It serves both the
  newest-first list and the rate-limit window count.
- Adds no new text columns.
- Applied after `001` and before the checklist migration, on fresh databases only
  (the same policy as SR-01). The Reporter test runner applies every
  `src/backend/shared/migrations/*.sql` in name order, then the Reporter
  migrations, then the checklist migration. The existing shared and Developer
  runners apply only `001`, so their schemas are unchanged.
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

Fresh-database only, like SR-01. Apply shared `001` → shared `002_add_report_timestamps` →
checklist migration. Rollback means rebuilding the disposable database.
Frontend rollback means reverting the dependency and component swap. No stored
data changes shape.

## Open Questions

- After the Maintainer owner reviews this, should the spec prose under
  "UI state and concurrency" ("Use no raw HTML or interpreted Markdown") be reworded
  during sync to match the modified MNT-READ-05? The intent is clear. Only the
  wording is undecided.
