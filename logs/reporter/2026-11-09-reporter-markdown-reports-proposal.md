# Reporter Markdown reports proposal - 9 November 2026

Planning only. No application code, tests or migrations were changed.

## Context

Branch `workflow` at `d664503`. This followed two `openspec-explore` sessions on
the Reporter role (based on `overview.md`) and on GitHub-style Write/Preview
Markdown. The feature owner chose to have Developer and Maintainer views render
the Markdown as well (option B: a shared safe renderer), rather than a
Reporter-only preview.

Inputs read:
- `overview.md` sections 3, 4, 6, 7 and 8
- `openspec/specs/maintainer-report-browser/spec.md` (MNT-READ-03 and MNT-READ-05, QR-07)
- `shared-report-storage` SR-01
- `src/backend/shared/migrations/001_create_reports.sql`
- `workflow/developer/assigned-reports-api-contract.md`
- `src/backend/developer/README.md`
- `src/backend/auth/build.gradle`
- `src/frontend/maintainer/src/{domain.ts,App.tsx}` and `package.json`

## Created

`openspec/changes/reporter-markdown-reports/`:
- `proposal.md`
- `specs/reporter-reports/spec.md` (RPT-01..07, new)
- `specs/safe-markdown/spec.md` (MDR-01..07, new)
- `specs/maintainer-report-browser/spec.md` (MODIFIED MNT-READ-05)
- `design.md` (D1..D9)
- `tasks.md` (6 groups, 21 tasks)

## Findings recorded in the artifacts

- The implemented Maintainer spec forbids interpreted Markdown
  (MNT-READ-05, and its "UI state and concurrency" prose). The delta needs
  Maintainer owner approval (task 1.1).
- SR-01 has no Reporter detail fields or timestamps, but the Maintainer DTO
  already expects them. D7 adds them in an additive Reporter migration, which
  needs Developer owner sign-off.
- The Developer frontend has no report-detail screen. D9 adds a read-only
  source panel, which needs Developer owner approval.
- Status wire values differ between the Developer contract (`SUBMITTED`) and the
  Maintainer DTO labels (`Submitted`). Noted in D6 and not fixed here.
- Images are disabled in rendered Markdown so that injected content can't leak
  data through image requests.

## Checks

- `openspec validate reporter-markdown-reports --strict --no-interactive`: valid.
- `openspec status --change reporter-markdown-reports`: 4/4 artifacts complete.

## Agents and resources

Single main-session agent (Claude Opus 5.5) using the `openspec-explore` and
`openspec-propose` skills. No specialist subagents were dispatched, and no Red or
Green was claimed. Token and cache measurements were unavailable.

## Revision: feature-owner decisions during apply (9 Nov)

The feature owner reviewed the draft API contract and decided:
1. **Only `title` and `description` are report fields.** Reproduction steps,
   expected and actual behavior, and environment are not stored. They are
   suggestions from the future improve-report AI.
2. **Report creation is limited to 3 per Reporter per minute.**

Artifacts updated:
- `reporter-reports` spec: RPT-01/02/03/05/07 reduced to two fields, and new
  RPT-08 for the rate limit.
- `safe-markdown` MDR-07 and the MNT-READ-05 delta: only `description` is
  rendered.
- Proposal.
- Design: D7 is now timestamps only (`002_add_report_timestamps.sql`). D8 and D9
  cover the description only. New D10: rate limit counted from stored
  `created_at` under a per-Reporter advisory lock, check order
  session → validation → limit, rollover tested with backdated fixture rows.
- Tasks: 1.1, 2.1–2.3, 4.2 and 5.2 updated. New task 2.6 for RPT-08. The README
  task is renumbered to 2.7.
- Contract `workflow/reporter/reporter-reports-api-contract.md`: two-field table,
  rate-limit section, `RATE_LIMITED` 429.

`openspec validate reporter-markdown-reports --strict --no-interactive`: valid.
22 tasks, 1 complete. The verified Red baselines for 3.1 and 3.2 are not affected,
since they test the renderer, not report fields.

## Revision 2: footnotes and timestamps (9 Nov)

Feature-owner decisions:
1. **Allow footnotes the way GitHub does.**
   - Added requirement MDR-08: two-way `#user-content-` links, and every generated
     ID prefixed `user-content-`. Author-written fragment links stay plain text.
   - Amended the MDR-04 first sentence so footnote links are the only exception to
     the rule that relative or fragment links render as plain text.
   - Added a footnotes bullet to design D2, and new task 3.6.
   - The approved MDR-01..04 tests use no `#` fragments, so their baselines still
     hold.
2. **Add the time fields to the database now.** The Reporter owner chose to go
   ahead with the additive `created_at` / `updated_at` migration (D7, task 2.2)
   without waiting for the Developer owner's sign-off. Grounds:
   - A code survey showed no Developer query or test is affected. All inserts
     name their columns, and the one positional insert uses a separate fixture
     table.
   - The Maintainer UI already displays and sorts by `createdAt` / `updatedAt`.

   The Developer owner is to be **informed**. Task 1.1 stays open until their
   acknowledgement is recorded.

`openspec validate --strict`: valid, 23 tasks.

## Next action

Feature-owner review of the artifacts, then task 1.1 approvals. Groups 2 and 3
can then run in parallel worktrees through the TDD agent pipeline.

## Revision 3: owner decisions after the 3.6 security review (9 Nov)

Feature-owner choices:
1. **Timestamps go in the shared migrations.** D7 moves to
   `src/backend/shared/migrations/002_add_report_timestamps.sql`. The Reporter
   runner will apply every shared migration in name order. Task 2.2 was
   reworded.
2. **Hand-written footnote links are allowed.** Option 2a, recorded in design D2.
3. **Per-view footnote ID prefix** (option 1a). MDR-08 was modified: IDs must be
   unique per view, scenario 1 no longer hard-codes `#user-content-fn-1`, and a
   new "two views never share IDs" scenario was added. New task 3.7. This
   changes an approved test, so it needs a new baseline review and approval.
4. **Link hardening** (option 3a). New requirement MDR-09: links containing
   credentials render as text, and `mailto:` keeps only the address. New task 3.8.
5. **The Developer suite is not run** (option 4b). The owner accepted the
   verifier's finding that the suite doesn't apply this migration. Recorded in
   task 2.2.

Design D4 also changed: the security review showed the parse itself overflows
the stack, so the nesting cap must be a pre-parse source check.

`openspec validate --strict`: valid. 25 tasks, 6 complete.

Five `test_designer` agents were dispatched in parallel, each on separate files:
2.2 runner, 3.3 limits, 3.4 editor, 3.7 footnote IDs, 3.8 link hardening.

## Revision 4: owner decisions after the round-3 security review (9 Nov)

The owner chose **1A, 2a, 3 keep**:
1. **1A: patch the pre-check.** No lower formatting cap and no Worker.
   - MDR-05 gains a "Known slow constructs up to 20,000 characters" scenario:
     footnote-prefixed nesting, wide tables, emphasis runs and density, setext
     headings, lazy continuation.
   - The orchestrator added the setext and lazy-continuation cases. The review
     measured them at 3.25 s and 1.23 s, and they need the same kind of
     pre-check pattern.
   - The D4 revision lists each linear check. The blocklist nature is recorded as
     an accepted residual risk.
   - Task 3.3 reopened.
2. **2a: `mailto:` hardening.** Exactly one address. Render as text when the
   decoded address contains `?`, `#`, `&`, a comma or a control character. New
   MDR-09 scenario added; task 3.8 extended.
3. **3 keep.** Whitespace-only source previews as "Nothing to preview".

`openspec validate --strict`: valid. 25 tasks, 9 complete.

Dispatched two `test_designer` agents in parallel:
- `MarkdownView.dos.test.tsx` (3.3)
- `MarkdownView.mailto.test.tsx` (3.8)

## Revision 5: owner decision C, Web Worker parsing (9 Nov)

After the round-4 security review found 6 more slow constructs (2–12 s), the
owner chose **C: parse in a Web Worker with a 1 s time limit**.

Spec changes:
- New **MDR-10**, with 6 scenarios:
  - an abandoned slow parse;
  - no parsing on the main thread;
  - the formatted result replacing the plain text;
  - one slow report not breaking the others;
  - a stale result being ignored;
  - the in-thread path when no Worker is available.
- The `mailto:` address rules move into new **MDR-11**, so MDR-09 stays under
  500 characters. MDR-11 adds `;`, whitespace, `<`, `>`, `"`, `\`, `/` and
  "exactly one `@`". This is the orchestrator's allowlist to meet the existing
  "exactly one address" rule. The owner was told and didn't object. The LOW
  items (`%253F`, zero-width characters) are excluded.

Design: new **D11**.
- A pure `toHast` pipeline runs in a shared worker. A runner gives each request
  its own 1 s timer, then terminates the worker and retries the queued requests
  once.
- The first paint is plain text. Stale results are dropped.
- Every safety rule is re-applied on the main thread when the hast is rendered.
- Where `Worker` doesn't exist, rendering is in-thread, so the existing tests stay
  valid.
- A `MarkdownParserProvider` context is the test seam.

Tasks: new 3.9. Task 3.3 closes when 3.9 passes. 3.8 is extended
(`MarkdownView.mailto2.test.tsx`).

`openspec validate --strict`: valid. 26 tasks, 9 complete.

Dispatched two `test_designer` agents in parallel: `MarkdownView.worker.test.tsx`
(3.9), against a contract the orchestrator wrote for `toHast` / `markdownRunner`
/ `MarkdownParserProvider`; and `MarkdownView.mailto2.test.tsx` (3.8).

Note: the implementation will probably need `hast-util-to-jsx-runtime` and
`unified`/`remark-*` declared in `package.json`. Today they are transitive
dependencies of react-markdown. Changing `package.json` touches the approved
runner configuration and will be raised with the owner at the Green gate.
