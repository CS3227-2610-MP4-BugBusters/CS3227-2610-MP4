# Tasks

Every behavior task follows [tdd.md](../../../workflow/tdd.md):
`test_designer` Red, a fresh `baseline_verifier`, feature-owner approval,
`implementer`, then a fresh `candidate_verifier`. Tasks marked **[sec]** also need
a separate `security_reviewer`. Build handoff packets with
[agent-context.md](../../../workflow/agent-context.md).

Groups 2 (backend) and 3 (shared Markdown) depend on nothing but group 1, so they
can run in parallel in separate worktrees. Groups 4 and 5 depend on group 3.

## 1. Approvals and scaffolding

- [ ] 1.1 Get Maintainer owner approval for the MNT-READ-05 delta and Developer owner approval for the D7 timestamp migration and the D9 description panel. Verify by recording each approver and date in `logs/reporter/`. Groups 4 and 5 and task 2.2 do not start without the matching approval.
- [x] 1.2 Scaffold `src/frontend/shared/markdown/` (package.json with React peer dependency, pinned `react-markdown` and `remark-gfm`, vitest + jsdom, tsconfig, `npm run verify`). Verify that `npm install` and `npm run verify` run with one placeholder test.
- [x] 1.3 Scaffold `src/backend/reporter/` (README, `migrations/`, `src/`, `test/`) and a disposable-PostgreSQL runner modelled on `src/backend/shared/test/run-shared-report-storage.sh`. Verify that the runner creates and drops a fresh database applying `001_create_reports.sql`.

## 2. Reporter backend (`reporter-reports`)

- [x] 2.1 Write `workflow/reporter/reporter-reports-api-contract.md` from RPT-01..08 and design D6 and D10 (paths, two-field body and response JSON, validation codes, 404 equivalence, error envelope, `no-store`, 3-per-minute rate limit with `Retry-After`). Verify that the feature owner approves it before 2.2 Red starts. It is read-only for every agent.
- [x] 2.2 Red/Green the D7 migration as `src/backend/shared/migrations/002_add_report_timestamps.sql` (owner decision: the shared folder, not the Reporter folder). Requirements: `created_at` and `updated_at` are set by default, the `(reporter_id, created_at DESC, id)` index exists, no other columns are added, and shared `001` → shared `002` → checklist migration applies cleanly on a fresh database. The 1.3 runner applies every shared migration in name order. Verify with the migration test via that runner, plus the existing shared-report-storage suite still passing. The Developer suite needs a JDBC jar and JDK that aren't available, so the owner accepted the verifier's finding that it doesn't apply this migration (decision 9 Nov) instead of running it.
- [x] 2.3 **[sec]** Red/Green RPT-01, RPT-02 and RPT-03 (create, validation, source fidelity) through the HTTP handler and synthetic-session harness. Verify with `create_report_api_test.py` and `create_report_validation_test.py` covering every RPT-01..03 scenario, including the title 200/201 and description 20,000/20,001 boundaries, rejection of `reproductionSteps` and other extra keys, and the U+0000 and unpaired-surrogate cases.
- [x] 2.4 **[sec]** Red/Green RPT-04 and RPT-05 (list own, read own, R1/R2 isolation, 404 equivalence, pagination validation). Verify with `list_own_reports_api_test.py` and `read_own_report_api_test.py`, including a byte-for-byte comparison of the 404 bodies for "other Reporter's report" and "nonexistent ID" (excluding `requestId`).
- [x] 2.5 **[sec]** Red/Green RPT-06 and RPT-07 across all three endpoints (401, 403 for inactive, Developer and Maintainer actors, ignored identity in body/query/header, safe error body, `Cache-Control: no-store`). Verify with `reporter_access_api_test.py`.
- [x] 2.6 **[sec]** Red/Green RPT-08 (3 creates per rolling 60 s per Reporter, 429 `RATE_LIMITED` with `Retry-After` >= 1, rollover, 5 concurrent creates leaving exactly 3, per-Reporter isolation, invalid and 429 requests not counted, check order session → validation → limit). Verify with `create_report_rate_limit_test.py`, using backdated fixture rows for rollover (D10) and real parallel requests for concurrency.
- [x] 2.8 Revise `workflow/reporter/reporter-reports-api-contract.md` for RPT-09 and RPT-10 (design D6, D10 and D17): the PUT edit and `POST .../close` paths and bodies, 200 responses, 409 `REPORT_LOCKED` and `VERSION_CONFLICT`, and the check order. Revision 2 (with DELETE) was approved and then superseded when the owner replaced delete with close (10 Nov). Verify that the feature owner approves revision 3 before 2.10 Red is re-baselined.
- [x] 2.10 **[sec]** Red/Green RPT-09 edit. Verify with `edit_report_api_test.py`: every RPT-09 scenario, RPT-02 boundaries on edit, extra keys, R1/R2 isolation with the byte-identical 404, 401/403 and `no-store` per RPT-06/07, a held-lock test where an assignment commits first, and a real parallel edit-versus-assignment test.
- [x] 2.11 **[sec]** Red/Green RPT-10 close. Verify with `close_report_api_test.py`: every RPT-10 scenario, a closed report still read and listed as `CLOSED`, edit and close of a closed report as `REPORT_LOCKED`, closing not freeing a rate-limit slot, R1/R2 isolation with the byte-identical 404, 401/403 and `no-store`, a held-lock test and a real parallel close-versus-assignment test.
- [ ] 2.7 Document build and run steps in `src/backend/reporter/README.md` and verify that a fresh clone following them reproduces 2.2–2.6, 2.10 and 2.11 passing.

## 3. Shared safe Markdown (`safe-markdown`)

- [x] 3.1 **[sec]** Red/Green `MarkdownView` for MDR-01 (subset, verbatim code, disabled task checkboxes, no heading IDs). Verify with vitest DOM assertions in the shared package.
- [x] 3.2 **[sec]** Red/Green MDR-02, MDR-03 and MDR-04 (raw HTML as literal text, no img element or fetch for images, scheme allow-list with `rel`, case and whitespace variants of `javascript:` and others). Verify with vitest tests that assert no `script`, `img` or `iframe` elements, no `on*` attributes, a stubbed `fetch` and `Image` that are never called, and link attributes.
- [ ] 3.3 Red/Green MDR-05 (error boundary fallback, nesting cap). Verify with a 20,000-character pathological fixture that completes in under 1 s in vitest and a forced render error that shows escaped source. **Reopened after the second security review (owner decision 1A):** also Red/Green the MDR-05 "known slow constructs" scenario (footnote-prefixed nesting, wide tables, emphasis runs and density, setext headings, lazy continuation) per the D4 revision. Verify with `MarkdownView.dos.test.tsx`: every case renders in under 1 s with the complete content, ordinary content stays formatted, and all earlier tests and `tsc` still pass.
- [x] 3.4 Red/Green `MarkdownEditor` for MDR-06 (Write/Preview tabs, preview equals `MarkdownView` output, source unchanged by switching, "Nothing to preview", ARIA tab roles and keyboard). Verify with vitest and `@testing-library/user-event` tests.
- [x] 3.6 **[sec]** Red/Green MDR-08 GitHub-style footnotes: two-way `#user-content-` links, every generated ID prefixed (including names like `__proto__`), author-written fragment links such as `#top`, a missing target, and a wrong-case prefix staying plain text. Verify with `MarkdownView.footnotes.test.tsx` and `tsc --noEmit`, with the approved 3.1 and 3.2 tests still passing.
- [x] 3.7 **[sec]** Red/Green per-view footnote IDs (MDR-08, modified): each `MarkdownView` instance gets its own `user-content-<instance>-` prefix, no ID is duplicated when two views share a page, and each view's links target only its own IDs. Update the approved footnote test's first scenario to stop hard-coding `#user-content-fn-1` (this needs a new baseline review and approval). Verify with the footnote tests, a two-view test, `tsc --noEmit`, and every earlier test still passing.
- [x] 3.8 **[sec]** Red/Green MDR-09 link destination hardening: links containing credentials (including `user:pass@`, `good@evil` and `&#64;` forms) render as text, and a `mailto:` `href` is reduced to `mailto:<address>`. Verify with `MarkdownView.urls.test.tsx`, `tsc --noEmit`, and every earlier test still passing. **Extended (owner decisions 2a and C):** also reject `;`, whitespace, `<`, `>`, `"`, `\`, `/` and anything other than exactly one `@` (MDR-11 scenario "Other recipient separators"; verify with `MarkdownView.mailto2.test.tsx`). `mailto:` allows exactly one address and renders as text when the decoded address contains `?`, `#`, `&`, a comma or a control character. Verify with `MarkdownView.mailto.test.tsx`.
- [ ] 3.9 **[sec]** Red/Green MDR-10 off-main-thread parsing (design D11, owner decision C): pure `toHast` pipeline, a shared worker runner with a 1 s per-request budget, terminate and fall back, retry queued requests once on a fresh worker, plain-text first paint, stale-result drop, the main-thread safety rules re-applied to worker hast, and in-thread behavior where `Worker` doesn't exist. Verify with `MarkdownView.worker.test.tsx` (fake Worker plus fake timers), a test that worker-path and in-thread DOM are equal, `npm run verify` with every earlier test still passing, and `npm run build` succeeding in a consuming app once group 4 or 5 adds the dependency. When 3.9 passes, close 3.3: the D4 pre-check is the first filter and MDR-05 is met through MDR-10. **Still open after 3.17 (10 Nov):** the 3.17 security review found that a Vite browser build bundles the DOM build of `decode-named-character-reference` into the worker, which throws `document is not defined` on load, so every view stays plain text (fail-safe). Close 3.9 and 3.3 only after the worker resolves a non-DOM entry and the consuming-app `npm run build` plus a real-browser smoke test pass.
- [x] 3.10 **[sec]** Red/Green MDR-12 suspicious link characters (owner decision 9 Nov: anything potentially malicious renders as text): Unicode format characters, U+2028/2029 and non-ASCII whitespace (including NBSP) in any link destination; a `%` left after one decode, or characters outside the strict ASCII allowlist, in a `mailto:` address (so IDN email renders as text, while IDN `https` links still work). Verify with `MarkdownView.suspicious.test.tsx`, `tsc --noEmit`, and every earlier test still passing.
- [x] 3.11 **[sec]** Red/Green the "no links" rule (MDR-04 and MDR-08 as modified, design D12, owner decision 9 Nov). This supersedes the MDR-09/11/12/13 link rules, so 3.8 and 3.10 remain as history. Every link, autolink and reference shows as text plus ` (<destination>)` when that differs from the text, and no element has an `href`. Footnotes show as superscript numbers and a footnotes section, with no links or back-references. Rewrite the approved subset, security, footnotes, urls, mailto, mailto2 and suspicious tests to the new rules. This needs a new baseline review and approval. Delete the unapproved `MarkdownView.hidden.test.tsx`. Verify with the rewritten tests, `tsc --noEmit`, and every other test still passing.
- [x] 3.12 **[sec]** Red/Green the D13 worker-result bounds. These fix the 3.9 security findings:
  - a 10,000-cell total table cap;
  - a 25,000-node budget in the worker and again on the main thread;
  - result shape validation and a `className` allowlist;
  - fair retries;
  - cancelling unsent requests on unmount;
  - a guarded `runner.parse`.

  Verify with `MarkdownView.budget.test.tsx`, the 35-table repro rendering as plain text without building formatted output, and `npm run verify`. 3.9 and 3.3 close when 3.12 passes.
- [ ] 3.13 **[sec]** Red/Green MDR-14 and the linear `unlink` (owner decision 9 Nov after the 3.11 security review, "fix 1 2 4"). Covers:
  - image alt text counting as link text;
  - format, bidi and line-separator characters and non-ASCII whitespace in a shown destination appearing as `<U+XXXX>`, and `(`/`)` as `%28`/`%29`;
  - nested `a` elements in worker hast being flattened in linear time (a depth-5,000 chain in under 100 ms on the main thread).

  Verify with `test/MarkdownView.destinations.test.tsx`, `tsc --noEmit`, and every other test still passing.
- [x] 3.14 **[sec]** Red/Green design D14, the fix for the 3.12 security review (owner decision 9 Nov, "fix 3.14"). Covers:
  - the in-thread path rendering through `toHast`, the node count and the shared sanitiser;
  - a pre-check cap on total list and blockquote containers;
  - a sanitiser that cleans once, then counts, reads only the allowed properties, and scans at most 8 class entries;
  - a loop-based `pump`.

  The approved dos and limits tests have their react-markdown mocks moved to the pipeline, with their assertions unchanged. This needs re-approval. Verify with `test/MarkdownView.inthread.test.tsx` (the three review repros under 1 s as plain text, plus the property-bloat and 20,000-request queue repros), the updated dos and limits tests, and `npm run verify`. 3.12, 3.9 and 3.3 close when 3.14 passes.
- [ ] 3.15 **[sec]** Red/Green MDR-15, the fix for the 3.13 security review (owner delegated approval on 10 Nov). Covers:
  - linear-time, exception-free decoding of the shown destination, computed once per destination per render (the 3,000-reference repro under 1 s);
  - the suffix rendered inside `bdi dir="ltr"` (the text content is unchanged, so earlier exact-text tests stay valid);
  - escaped autolink text when the destination contains a hidden character;
  - the wider hidden set: controls, surrogates, combining marks, default-ignorables, Hangul fillers and U+2800;
  - space and non-ASCII brackets shown percent-encoded;
  - void elements (`input`, `br`, `hr`) contributing no displayed text.

  Verify with `test/MarkdownView.destinations2.test.tsx`, the amended earlier destination expectations (re-approved), and `npm run verify`. 3.13 closes when 3.15 passes.
- [x] 3.16 **[sec]** Red/Green design D15, the fix for the 3.14 security review (owner decision 10 Nov: loop until the security review passes, at most through 3.18). Covers:
  - the 4,000-character in-thread input cap (MDR-10 "The in-thread path is bounded by input size");
  - dense-array and node-budget checks on worker `children`, including sparse arrays and Proxies;
  - `language-` classes without whitespace;
  - a non-re-entrant `pump`.

  Approved tests that render more than 4,000 characters in-thread and expect formatting are amended (re-approval delegated by the owner for this loop). Verify with `test/MarkdownView.inthread2.test.tsx`, the amended tests, and `npm run verify`. 3.14, 3.12, 3.9 and 3.3 close when 3.16 passes its candidate and security reviews.
- [x] 3.17 **[sec]** Red/Green design D16, the fix for the 3.16 security review (loop delegation, 10 Nov). Covers:
  - a 100,000-character rendered-text budget checked in the worker and in `cleanTree`;
  - a pre-check refusing more than 200 `www.` occurrences;
  - O(1) cancellation of queued requests.

  Verify with `test/MarkdownView.textbudget.test.tsx` (the three amplification repros fall back to plain text on both paths, a hostile worker result over the budget is refused, `"www._"` ×800 under 250 ms in-thread, 20,000 aborts under 50 ms), with every earlier test and `npm run verify` passing. 3.16, 3.14, 3.12, 3.9 and 3.3 close when 3.17 passes its candidate and security reviews.
- [ ] 3.18 **[sec]** Reserved: fix the findings of the 3.17 security review, if any. If 3.18 still fails, stop and ask the owner. **Not needed (10 Nov):** 3.17 passed its security review with LOW findings only; remove this task or reuse it if the owner chooses to fix them.
- [ ] 3.5 Document usage and the safety rules in `src/frontend/shared/markdown/README.md` and verify that `npm run verify` passes in the package.

## 4. Maintainer adoption (MNT-READ-05 delta)

- [ ] 4.1 Add the `file:` dependency to `src/frontend/maintainer` and verify that `npm install` and `npm run verify` still pass with no behavior change.
- [ ] 4.2 **[sec]** Red/Green the modified MNT-READ-05: the `description` goes through `MarkdownView`, and the title, the other source fields, notes, comments, checklist, responses, proposal and history stay escaped. Verify with updated QR-07 tests in `App.test.tsx` (Markdown renders, `<img onerror>` and image URL inert, title `**bold**` literal) and the narrow-viewport overflow scenario. All existing QR-01..08 tests must still pass under `npm run verify`.
- [ ] 4.3 Add Markdown and injection examples to the demo fixtures in `demo.ts` and verify them visually in `npm run demo` at mobile and desktop widths. Record screenshots or notes in `logs/reporter/`.

## 5. Developer adoption (MDR-07)

- [ ] 5.1 Add the `file:` dependency to `src/frontend/developer` and verify that `npm install` and `npm run verify` still pass.
- [ ] 5.2 **[sec]** Red/Green a read-only `ReportDescriptionPanel` (D9) rendering the description through `MarkdownView`, with the title as plain text. Verify with a vitest test asserting the same DOM as the Maintainer for the MDR-07 fixture, and `npm run verify` in `src/frontend/developer`.

## 6. Cross-role integration

- [ ] 6.1 Verify MDR-07 end to end. Create a report through the Reporter API harness with the MDR-07 fixture, feed the returned detail to the Maintainer browser (HTTP adapter against a local stub serving that JSON) and to the Developer panel, and confirm identical rendering and literal `<b>x</b>`. Record the commands, revision and outcome in `logs/reporter/`.
- [ ] 6.2 Run `openspec validate reporter-markdown-reports --strict --no-interactive` and every touched package's verify command on one revision. Record results, agent roles, models and token usage, or mark them unavailable, in `logs/reporter/`.

## Workflow follow-up

- Real-session verification of RPT-06 once `add-shared-authentication` provides sessions. Not part of this change's completion.
- On sync or archive, reword the Maintainer spec prose "Use no raw HTML or interpreted Markdown" under "UI state and concurrency" to match the modified MNT-READ-05 (design Open Question).
- Archive after the owners' review requirements are met.
