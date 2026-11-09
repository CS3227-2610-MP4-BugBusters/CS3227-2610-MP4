# safe-markdown Red baselines, tasks 3.1 and 3.2 - 9 November 2026

Change `reporter-markdown-reports`, branch `workflow`. Baseline: git `d664503`
plus the uncommitted scaffold in `src/frontend/shared/markdown/` (task 1.2). No
commit exists for the scaffold yet, so the files under review are identified by
their sha256 hashes.

## Task 1.2 scaffold (no behavior change, no TDD)

- Main session created `package.json` (pinned react-markdown 10.1.0, remark-gfm
  4.0.1, vitest 5.1.0), `tsconfig.json`, `.gitignore`, `src/index.ts`
  (`export {}`) and `src/scaffold.test.ts`.
- `npm install` then `npm run verify` (typecheck + vitest): PASS, 1 test.

## Task 3.1 MDR-01 supported subset

| Step | Role | Outcome |
| --- | --- | --- |
| Red | `test_designer` (sonnet, low) | RED. 7/7 fail: `MarkdownView` undefined |
| Verify | fresh `baseline_verifier` (sonnet, low) | PASS. Integrity, coverage, contract, reproduce and evidence all pass |

- File: `src/MarkdownView.subset.test.tsx`, sha256
  `d3c36b440a93788a807d7f7358b5a941cbf4dc046742b971667e48a5b12c2b25`.
- Command:
  `cd src/frontend/shared/markdown && npx vitest run --environment jsdom src/MarkdownView.subset.test.tsx`
- Setup control `src/scaffold.test.ts`: 1/1 pass.
- Non-blocking finding: `expect(source).toBe(...)` in the task-list test can never
  fail, because `source` is a local constant. The click/`checked` assertions carry
  that scenario. The feature owner decides whether to keep it or remove it.
- Limit: every test fails at render, so this Red doesn't show the individual
  assertions discriminating. That is checked at Green.

## Task 3.2 MDR-02..04 raw HTML, images, links

| Step | Role | Outcome |
| --- | --- | --- |
| Red v1 | `test_designer` (sonnet, low) | RED. 17/17 fail: `MarkdownView` undefined |
| Verify v1 | fresh `baseline_verifier` (sonnet, low) | **FAIL** on test quality (see below) |
| Red v2 | same `test_designer`, findings returned | RED. 17/17 fail |
| Verify v2 | new fresh `baseline_verifier` (sonnet, low) | PASS. All five checks plus prior items (a)-(e) |

v1 findings that were fixed in v2:
- A patched `HTMLImageElement.prototype.src` was never restored.
- `setAttribute("src")` wasn't instrumented.
- `alert` was assigned directly and never restored.
- The disallowed-link test didn't check that the destination was absent from the
  text.
- The alt-text check was too loose.
- The leading-whitespace link case wasn't one CommonMark parses as a link. v2
  uses `[linktext](< javascript:alert(1)>)`.

Final file:
- `src/MarkdownView.security.test.tsx`, sha256
  `f6a2d9b9420f5f9da1a230adf2c7303eea43e4d93ff173de1c82201d80884101`.
- Command:
  `cd src/frontend/shared/markdown && npx vitest run --environment jsdom src/MarkdownView.security.test.tsx`
- Controls: the scaffold test passes alone (1/1). Leak control: scaffold plus
  security in one run gives 1 pass and 17 fail.
- The verifier confirmed that empty, naive `dangerouslySetInnerHTML` and
  plain-text-only implementations each fail at least one test.

## Status

- Both baselines are verified and **awaiting feature-owner approval** (tdd.md
  step 3).
- No `implementer` has been dispatched.
- Tasks 3.1 and 3.2 stay unchecked until Green and candidate verification pass.

## Feature-owner approval (TDD step 3)

On 9 November 2026 the Reporter feature owner (tieutrongphuc) approved:
- the 3.1 and 3.2 Red baselines as verified. These test files were committed in
  `85d7eda`, and their hashes are unchanged from those verified:
  `d3c36b44…2b25` (subset) and `f6a2d9b9…4101` (security). The 3.1 assertion that
  can never fail (`expect(source)`) is kept as is.
- the revised API contract `workflow/reporter/reporter-reports-api-contract.md`
  (two fields, 3 creations per minute), sha256 `2153c287976208749f593ac5875d6e1c8d0455818fe8bb2f262deace69d936b8`.
  Uncommitted at approval time.

The owner chose Docker as the PostgreSQL runtime for the backend test runner, and
for later deployment.

Any change to the approved test files or the contract needs a new baseline
review and approval.

## Green attempt and baseline defect in 3.2

`implementer` (opus, high) added `src/MarkdownView.tsx` and the export in
`src/index.ts`.
- Approved tests: 24/24 pass at runtime (subset 7/7, security 17/17). The
  approved test hashes are unchanged.
- `npm run verify`: **FAIL** at `tsc --noEmit`, with TS2322 at line 63 and TS7031
  at line 82. Both are in the approved security test, caused by loose
  `ReturnType<typeof vi.fn>` / `ReturnType<typeof vi.spyOn>` declarations. The
  orchestrator reproduced this independently.
- The implementer correctly stopped with BASELINE DEFECT and didn't edit the test.
- **Process gap:** the Red checks ran vitest only, and vitest doesn't typecheck.
  From now on, Red verification for TypeScript packages also runs `tsc --noEmit`
  and expects only missing-export errors.

Correction:

| Step | Role | Outcome |
| --- | --- | --- |
| Type-only fix | `test_designer` (sonnet, low) | 3 lines: `import type { Mock, MockInstance }`, plus 2 narrowed `let` annotations. New sha256 `47664743b7145ce0303f44960c0c12bb7dd9be99fe05c42cde6f1a5123b13ee1`. `tsc` exits 0. |
| Verify | fresh `baseline_verifier` (sonnet, low) | PASS. Diff vs `85d7eda` is type-only. In a temporary `85d7eda` worktree with the corrected file: vitest 17/17 fail (MarkdownView undefined), and `tsc` shows only TS2305 missing-export errors. Worktree removed afterwards. |

**Awaiting feature-owner re-approval** of the corrected 3.2 baseline, because the
approved file changed.

Implementer notes for the owner:
- GFM footnotes add `user-content-fn-*` IDs and relative `#` back-links, which
  render as plain text. MDR-01 doesn't cover footnotes. The owner needs to decide.
- Allowed URLs are normalised (`parsed.href`).
- Links keep `title`, and there is no `target`.

## Task 1.3 runner (no behavior change, no TDD)

- `src/backend/reporter/test/run-reporter-db.sh` runs a disposable `postgres:17`
  in Docker, as the feature owner chose. It uses a random loopback port and a
  random password, and applies `001`, then the Reporter migrations, then the
  checklist migration. The container is always removed.
- Smoke run: exit 0. Failing-command run: exit 3. 0 containers left after each.

## Other session work

- Task 2.1: drafted `workflow/reporter/reporter-reports-api-contract.md`. It is
  awaiting owner approval and stays unchecked.
- Task 1.3: blocked. `initdb`, `pg_ctl` and `psql` aren't on PATH, and the Docker
  daemon isn't running.
- Task 1.1: owner approvals not yet obtained.

## Resources

Orchestrator: Claude Opus 5.5, main session. Subagent tokens reported by the
harness:

| Agent | Tokens |
| --- | --- |
| 3.1 designer | 14,680 |
| 3.1 verifier | 16,849 |
| 3.2 designer v1 | 17,004 |
| 3.2 verifier v1 | 15,474 |
| 3.2 designer v2 (cumulative with v1) | 20,666 |
| 3.2 verifier v2 | 19,881 |

Cache-token counts were unavailable.

## Red baselines for tasks 2.2 and 3.6 (run in parallel)

| Task | Red (`test_designer`, sonnet low) | Fresh `baseline_verifier` (sonnet low) |
| --- | --- | --- |
| 3.6 MDR-08 footnotes | RED, 3/3 fail on missing `#user-content-` anchors; `tsc` exit 0 | PASS. Control (approved 3.1/3.2) 24/24 pass. Probes: allow-all-fragments, missing target and wrong-case prefix would each fail. |
| 2.2 D7 timestamp migration | RED, exit 1: 4 checks FAIL (columns and index missing), control and checklist PASS | PASS. Wrong variants (no tz, nullable, no or constant default, wrong index order or ASC, extra column) each fail. SQL built from constants only. No containers left. |

- 3.6 file: `src/frontend/shared/markdown/src/MarkdownView.footnotes.test.tsx`, sha256
  `eaeac14f0c727ea572baa33d3b5b0693ca18dd22e2523206dc58d034e3724a0a`. This is a
  behavioral Red against the uncommitted 3.1/3.2 candidate.
  - Command: `npx vitest run --environment jsdom src/MarkdownView.footnotes.test.tsx`
  - Nits: the single-character plain-text checks are weak; the `__proto__` footnote
    isn't asserted to have rendered.
- 2.2 file: `src/backend/reporter/test/report_timestamps_migration_test.py`, sha256
  `3c696ee4ad0d76b8e6f4a15cf46c567971430536ec0791aedcabe632c8d6cf53`.
  - Command: `sh src/backend/reporter/test/run-reporter-db.sh python -B src/backend/reporter/test/report_timestamps_migration_test.py`
  - Note: the order "002 before the checklist migration" is enforced by the
    runner, not asserted by the test. The checklist check already passes at Red
    (it guards against regression).

Both are **awaiting feature-owner approval**, together with the corrected 3.2
baseline (`47664743…3ee1`).

Subagent tokens: 3.6 designer 15,376 · 3.6 verifier 15,631 · 2.2 designer 20,514
· 2.2 verifier 18,134.

## Feature-owner approval 2 (9 Nov)

The Reporter feature owner replied "APPROVE ALL", approving:
- the corrected 3.2 baseline (`47664743…3ee1`);
- the 3.6 footnotes baseline (`eaeac14f…a0a`);
- the 2.2 migration baseline (`3c696ee4…f53`).

The 3.1 baseline (`d3c36b44…2b25`) remains approved from approval 1.

## Green and candidate verification

### 3.1 + 3.2 MarkdownView

- Candidate files: `MarkdownView.tsx` sha256 `6a55d2b6…36c1`, `index.ts` sha256
  `526762ff…c440`. Both uncommitted.
- Fresh `candidate_verifier` (sonnet, low): **PASS**, MDR-01..04.
  - Approved tests and runner config are unchanged.
  - `tsc` exits 0. 25/25 tests pass (7 + 17 + 1), with nothing skipped or `.only`.
  - No `dangerouslySetInnerHTML` or `rehype-raw` use.
  - The 3 footnote tests fail, as expected (3.6 is pending).
- `security_reviewer`: still running when this entry was written.

### 2.2 timestamp migration

- `implementer` (opus, high): GREEN.
  - Added `src/backend/reporter/migrations/002_add_report_timestamps.sql` and a
    README "Migrations" section. Removed the `.gitkeep`.
  - Focused test: 6/6 PASS. Smoke check passes. The approved test hash is
    unchanged.
- Fresh `candidate_verifier` (sonnet, low): PASS on the SQL review and on every
  D7 check.
  - The existing shared-report-storage suite passed in a throwaway `postgres:17`
    container: SR-01/02/03 PASS. The scripts needed CR stripping, because this
    Windows checkout has CRLF line endings.
  - The existing Developer suite (`run-assigned-reports.sh`) is **BLOCKED**: it
    needs `POSTGRES_JDBC_JAR` plus a JDK and the PostgreSQL tools.
  - Reading both runners shows neither applies `src/backend/reporter/migrations`,
    so 002 can't affect them.
- **2.2 stays unchecked.** Its stated check includes the existing checklist tests.

Finding for deployment: `.sh` files are checked out with CRLF line endings and
fail in Linux containers. Suggest `.gitattributes` with `*.sh text eol=lf`.

Implementer notes:
- `updated_at` has no trigger; services must set it on update.
- The index name is `reports_reporter_created_idx`.
- The migration can't be re-run (fresh databases only).

### 3.1 + 3.2 security review

`security_reviewer` (opus, medium), 44,009 tokens: **no finding breaks MDR-02,
MDR-03 or MDR-04**.
- More than 80 probe inputs held, run in the scratch `sec-probe/` folder: encoded
  and obfuscated schemes, reference links and images, autolinks, raw
  HTML/SVG/MathML/CDATA/meta, task-list inputs, table alignment. No
  user-controlled id or name reached the DOM except the prefixed footnote IDs.
- **HIGH, availability (MDR-05 / task 3.3):** deeply nested input freezes and then
  crashes the render. Examples: `"> - ".repeat(5000)+"x"` takes 18 s and throws
  RangeError, and `"> - ".repeat(1000)+"x"` (4 KB) throws too. Task 3.3 must
  cover this, using a size/nesting guard plus an error boundary.
- LOW, phishing (allowed by the spec):
  - URL-looking link text with a different host.
  - Credentials kept in URLs (`https://good.com&#64;evil.com`).
  - `mailto:` query parameters (`body`, `cc`, `bcc`) pass through.
- LOW/INFO: the code-fence info string becomes a `language-*` class value. It is
  escaped, so this only affects CSS matching.
- INFO: the link `title` attribute passes through, escaped.
- INFO: footnote IDs carry percent-encoded user labels after `user-content-fn-`,
  as expected under MDR-08.
- Unverified: checks ran in Node SSR, not in a real browser. Lookalike (IDN) and
  RTL-override link text are not stripped.
- The reviewer left a probe running in the background. The orchestrator stopped
  it (job `bp5qa582x`), then stopped the reviewer agent.

**Tasks 3.1 and 3.2 marked complete:** candidate verification passed and the
security review found no in-scope defects. Progress is 5/23.

### 3.6 MDR-08 footnotes

- `implementer` (opus, high): GREEN.
  - `MarkdownView.tsx` sha256 is now `73c4b18b…4058`.
  - Added a rehype pass that trusts only `#user-content-` fragment links whose
    target ID was generated. All other links keep the absolute allow-list.
  - `npm run verify`: `tsc` exits 0, 28/28 tests pass.
- Fresh `candidate_verifier` (sonnet, low): **PASS**, MDR-08 plus a regression
  check of MDR-01..04.
  - The three approved test hashes are unchanged, and so is the runner config.
- `security_reviewer` (opus, medium): **no MDR-02/03/04/08 bypass found.**
  - Encoded, lookalike, autolink, raw-HTML-id and missing-target fragments all
    render as text.
  - Every generated ID is prefixed, including `__proto__`, quotes and a
    900-character label.
  - No trust carries over between renders or instances, including StrictMode.
  - The change doesn't make the MDR-05 deep-nesting crash any worse.
  - Findings:
    - **Low:** two views on one page produce duplicate IDs, so one report's
      footnote link can land on another report's footnote text (content
      spoofing). The design note calling this "harmless" understates it.
      Suggested fix: a per-instance `clobberPrefix` such as
      `user-content-<useId>-`.
    - **Low (spec gap):** any author-written link to a generated ID is trusted,
      not only links remark generates. Optional fix: also require
      `data-footnote-ref` or `data-footnote-backref`.
    - **Info:** `[a[^1]](https://x)` produces nested `<a>` elements (upstream
      behavior). The footnote `aria-*` attributes and the `sr-only` heading
      class are dropped, which affects accessibility only.
  - The reviewer killed its own background probe and confirmed no process was
    left running.
- **Task 3.6 marked complete.** The Low findings await feature-owner decisions.

## Red baselines round 3: 2.2 runner, 3.3, 3.4, 3.7, 3.8

Five `test_designer` agents ran in parallel (sonnet, low), each followed by a
fresh `baseline_verifier` (sonnet, low).

| Task | File (sha256) | Red | Verification |
| --- | --- | --- | --- |
| 2.2 runner | `run-reporter-db.sh` (`31d07969…7227`) | Infra change, no behavior change. Migration test 6/6 still passes, apply order is shared → reporter → checklist. | PASS. Risk: a migration present in both folders would be applied twice, so the move must delete the Reporter copy. Glob order depends on the locale (`LC_ALL=C` optional). |
| 3.3 MDR-05 limits | `MarkdownView.limits.test.tsx` (`6bf554cc…bec3`) | 3 fail (RangeError ×2, forced error), 1 pass | **FAIL ×2, then PASS.** v1: complete source not asserted. v2: the "formatted or plain" helper was vacuous. v3: requires the full untrimmed source as plain text, per D4. |
| 3.4 MDR-06 editor | `MarkdownEditor.test.tsx` (`d451a16c…cb1`) | 8/8 fail (MarkdownEditor undefined), `tsc` shows only TS2305 | **FAIL, then PASS.** v1: Preview used `toContain`. v2: exact innerHTML equality, raw markers absent, onChange checked after Preview. |
| 3.7 MDR-08 per-view IDs | `MarkdownView.footnotes.test.tsx` (`eba6f3ca…fe69`, was `eaeac14f…`) | 1 fail (2 distinct IDs out of 4), 3 pass | PASS |
| 3.8 MDR-09 links | `MarkdownView.urls.test.tsx` (`048ac2a1…5ed2`) | 8 fail, 2 over-blocking guards pass | PASS. Nit: link-text checks use single letters. |

Constraint for the 3.4 implementer: the tab panel's innerHTML must be exactly
MarkdownView's output, with no wrapper `div`.

All five are **awaiting feature-owner approval**.

## Feature-owner approval 3 (9 Nov)

The Reporter feature owner replied "approve all", approving:
- 2.2 runner (`31d07969…7227`);
- 3.3 limits (`6bf554cc…bec3`);
- 3.4 editor (`d451a16c…cb1`);
- 3.7 footnotes (`eba6f3ca…fe69`, replacing the earlier approved `eaeac14f…`);
- 3.8 URLs (`048ac2a1…5ed2`).

## Green round 3

### 2.2 migration moved to shared (complete)

- `implementer`: created `src/backend/shared/migrations/002_add_report_timestamps.sql`
  (sha256 `a46bf049…d015`; only its first comment differs from the verified
  SQL) and deleted the Reporter copy, leaving `.gitkeep`. Updated both READMEs.
- Fresh `candidate_verifier`: **PASS**.
  - Apply order: shared 001 → shared 002 → checklist.
  - Migration test: 6/6.
  - Shared-report-storage suite in a `postgres:17` container: SR-01/02/03 PASS.
  - Approved runner and test hashes are unchanged.
- The Developer suite was not run; the owner accepted this (decision 4b).
- **Task 2.2 marked complete.** Task 1.1 (the Developer owner being informed)
  remains open.

### 3.3, 3.4, 3.7, 3.8 shared Markdown

- Implementer A (`MarkdownView.tsx`, `faded6ee…9374`) added:
  - MDR-09: links with userinfo render as text; `mailto:` keeps only the address.
  - MDR-08: a per-view `user-content-<useId>-` clobberPrefix.
  - MDR-05: a linear pre-parse depth (>32) and length (>20,000) check that
    renders a `<pre>` fallback, plus a `RenderBoundary` keyed on the source.
- Implementer B (`MarkdownEditor.tsx`, `c7099dfa…6c3f`, and `index.ts`): ARIA
  tabs, Preview renders `MarkdownView` directly, "Nothing to preview" for empty
  or whitespace-only text.
- `npm run verify`: 7 files, 51/51 tests, `tsc` clean.
- Fresh `candidate_verifier`: **PASS** on MDR-01..06, 08 and 09. All six approved
  hashes are unchanged.
- `security_reviewer`: running.

Open owner question: should whitespace-only text count as empty in Preview?
Implementer flags:
- the depth estimate ignores code fences (a false fallback only);
- deeply nested inline constructs are not pre-checked;
- `https://@host/` still links;
- `mailto:?x` renders as text.

### Security review of round 3

`security_reviewer` (opus, medium) against `MarkdownView.tsx` `faded6ee…9374`
and `MarkdownEditor.tsx` `c7099dfa…6c3f`: **FINDINGS**.

1. **HIGH, MDR-05 bypass.** A footnote-definition prefix `[^a]: ` stops
   `lineDepth`, so deep nesting is parsed anyway.
   `"x[^a]\n\n[^a]: " + "> - ".repeat(4990)` takes 18.9 s, then RangeError, then
   the boundary falls back.
2. **HIGH, table blow-up.** remark pads every row to the header's width.
   2500 columns × 4900 rows (19,804 chars) runs out of heap (about 4 GB), which
   the boundary can't catch. 1000×2000 takes 4.7 s and about 1 GB.
3. **MEDIUM, inline and other slow paths.**
   - Nested emphasis `*`×9995 a `*`×9995: 15.6 s, then RangeError.
   - Setext headings at 20 KB: 3.25 s.
   - Lazy continuation: 1.2 s.
4. **LOW, MDR-09.** Percent-encoded `%3F`, `%23` and `%0A` survive in the
   `mailto:` address. Multiple recipients are allowed.
5. **LOW, MDR-08.** Separately server-rendered React roots repeat useId
   `_R_0_`, so footnote IDs collide. Client roots are fine.

Held up: the fallback escapes text, the boundary catches RangeError and can't
loop, about 45 URL variants behave correctly, IDs are unique per client root
(including StrictMode and two editor Previews), and the editor's `label` is
escaped. No background process was left running.

Task decisions:
- **3.4 and 3.7 marked complete.** No in-scope defect; finding 5 is about the
  hosting environment.
- **3.3 stays open** (findings 1–3 violate MDR-05).
- **3.8 stays open** pending the owner's decision on finding 4.

## Red baselines round 4: 3.3 slow constructs and 3.8 mailto

| Task | File (sha256) | Red | Verification |
| --- | --- | --- | --- |
| 3.3 MDR-05 known slow constructs | `MarkdownView.dos.test.tsx` (`7e56cff4…33eb`) | 9 fail: 8 timing cases at 1.3–17.8 s, plus the huge-table parser-spy test (parser called twice). 6 pass: the 4 KB footnote case and 5 formatted guards. | **PASS with 2 MEDIUM gaps → designer fixed → PASS.** The gaps were a trailing-sentinel-only content check, and a table size that couldn't catch out-of-memory. Fixed with START and END sentinels and a 2500×4900 react-markdown spy that throws instead of parsing, so it can't OOM even in Red. A 6×200 mid-size guard was added. The mock was confirmed not to leak. |
| 3.8 MDR-09 mailto encoding | `MarkdownView.mailto.test.tsx` (`cde733c9…4e12`) | 13 fail (link rendered), 4 pass (`%253F` guard + 3 over-blocking guards) | PASS. The orchestrator also ran `tsc` (exit 0) and the controls (34/34). Owner note: `%253F` decodes once to the literal text `%3F` and is not required to be blocked. `;` is not in the spec. |

Both are **awaiting feature-owner approval**.

## Feature-owner approval 4 (9 Nov)

The Reporter feature owner replied "approve all", approving:
- 3.3 dos (`7e56cff4…33eb`);
- 3.8 mailto (`cde733c9…4e12`), including the single-decode reading of `%253F`
  and leaving `;` unspecified.

## Green round 4: 3.3 + 3.8 hardening

- `implementer` (opus, high), 78,952 tokens: GREEN.
  - Files: `MarkdownView.tsx` `4c2a8f9b…2fde` and the new `MarkdownView.limits.ts`
    `7bd3d146…3e03`.
  - `npm run verify`: 9 files, 83/83. Every dos case now renders in 2–28 ms.
  - Thresholds: depth 32 (counting `]:` across the whole line), 10k cells per
    table, a delimiter run ≥ 64 or more than 2,000 delimiters in total, more than
    500 setext lines, more than 500 lazy lines, CRLF normalised.
- Fresh `candidate_verifier`: **PASS**. All 8 approved hashes are unchanged, and
  every pre-check is a single linear pass.
- `security_reviewer`: **FINDINGS, 7 HIGH and 1 MEDIUM**, all at ≤ 20,000 chars
  with the parser running:
  1. Many tables just under the per-table cap (no document-wide total): 7.4 s.
  2. Nested images `![`×3300: 11.8 s (brackets are uncounted).
  3. `]`×20000: 2.9 s.
  4. `"a <!--\n"`×2857: 7.2 s (`<` is uncounted).
  5. Lazy lines after a list item or footnote: 3.7 s. Only lazy lines after `>`
     are counted.
  6. `"> a\n\n"`×4000: 1.9 s.
  7. The combined near-cap input: 2.1–2.3 s.
  8. MEDIUM: `mailto:` with `;`, `%20` or several `@` signs still renders as a
     link, which violates "exactly one address".
  9. LOW: `%253F`, zero-width and bidi characters are accepted.

  Held: every bypass of the existing checks fell back correctly, the pre-check
  costs at most 1.7 ms, the rendered href always equals the validated one, and no
  process was left running.
- **3.3 and 3.8 stay open.** After two rounds that found 11 slow constructs, the
  orchestrator recommends the owner reconsider D4. Options: A, keep patching; B,
  also format only up to about 5k chars; C, parse in a Web Worker with a 1 s
  budget (recommended). Awaiting the owner's decision.

## Red baselines round 5: 3.9 worker and 3.8 mailto2 (after owner decision C)

| Task | File (sha256) | Red | Verification |
| --- | --- | --- | --- |
| 3.9 MDR-10 worker | `MarkdownView.worker.test.tsx` (`f2eee606…b0a`) | The suite fails to load: the contract modules `markdownRunner` and `MarkdownView.pipeline` are missing. `tsc`: only TS2305/TS2307. | **PASS, then one gap fixed, then PASS.** The late reply in "slow parse abandoned" had no assertion; it now checks at runner and view level. A no-op stub run in a scratch worktree: 13/14 fail. The default-in-thread test is a regression guard. |
| 3.8 MDR-11 mailto2 | `MarkdownView.mailto2.test.tsx` (`f91ab9a9…512b`) | 17 fail (link rendered), 5 pass (`%09` + 4 valid-address guards) | PASS. Partial fixes (`;` only, `@` count only, raw whitespace only) would each fail a test. |

Contract decision by the orchestrator: `index.ts` also exports `inThreadRunner`
and `createWorkerRunner`. Open owner question: does NBSP (`%A0`) count as
whitespace for MDR-11? The orchestrator recommends blocking it.

Both are **awaiting feature-owner approval**.

## Feature-owner approval 5 (9 Nov)

The owner replied "approve all, block NBSP, ok deps, the rest potential
malicious just parse as text". Recorded:
- **Approved:** the 3.9 worker baseline (`f2eee606…b0a`) and the 3.8 mailto2
  baseline (`f91ab9a9…512b`).
- **NBSP is blocked.**
- **Dependency change approved:** `package.json` may declare
  `hast-util-to-jsx-runtime`, `unified`, `remark-parse` and `remark-rehype`,
  pinned to the versions react-markdown already installs.
- **"The rest potential malicious → text"** became new requirement **MDR-12**
  and task 3.10:
  - Unicode format characters, U+2028/2029 and non-ASCII whitespace in any link
    render as text.
  - In a `mailto:`, a `%` left after one decode or characters outside the strict
    ASCII allowlist render as text. IDN email therefore renders as text; IDN
    `https` links still work.
  - This needs its own Red, verification and approval.

Sequencing: `MarkdownView.tsx` has one writer at a time. The 3.8 mailto2
implementer runs first (small), then the 3.9 worker implementer. The MDR-12
`test_designer` runs in parallel, since it writes a test file only.

## 3.8 mailto2 candidate verification and 3.10 Red verification (9 Nov)

Both ran read-only in the frozen snapshot `scratchpad/snap-3.8c`, with
`MarkdownView.tsx` at `35dc30d7…c26e`.

- **3.8 `candidate_verifier`: PASS.** MDR-11 scenarios 1 and 2 pass. The
  regression run covered 9 files: 90 tests, 0 failed. `dos` passed 15/15.
  `tsc` errors appear only in `worker.test.tsx`, which is expected because the
  3.9 modules don't exist yet. `package.json` is identical to `85d7eda`.
  3.8 stays open until the combined 3.8/3.10 security review.
- **3.10 `baseline_verifier`: PASS.** `MarkdownView.suspicious.test.tsx`
  (`9658a467…ef0b`) gives 71 failed and 30 passed out of 101, which reproduces
  the designer's result. The failures come from the missing behavior.
  - The 30 passing tests:
    - 22 host-position format-character variants. URL host parsing already
      blocks these, so they act as regression guards.
    - 4 over-blocking guards.
    - 3 ordinary-link cases from the spec.
    - 1 NBSP case from the spec.
  - It checked that partial fixes would fail: encoded-only, ZWSP-only,
    mailto-only NBSP, and a `%` check on one side of the `@` only.
  - Non-blocking gaps:
    - (b) No guard that an https path with `%2520` stays a link.
    - (c) No guard for an allowed special local part such as
      `a+b!$*=^{|}~@c.example`.
    - (a) U+2060, U+FEFF and bidi characters are not tested in a mailto local
      part.
    - (d) U+2028 and U+2029 are not tested in a mailto.
- 3.10 baseline is **awaiting feature-owner approval**.

## 3.10 Red revisions 2 and 3 (9 Nov)

The owner chose "add guards". In two `test_designer` passes, 22 tests were
appended. No existing test was changed.

Added tests:
- **(b)** Guards that https `%2520`, `%20` and `%25` links keep their percent
  sequences.
- **(c)** Guards that a `mailto:` allowlist local part and domain stay links.
- **(a)** U+2060, U+FEFF, U+202E and U+2066 in a mailto local part, raw and
  encoded, render as text.
- **(d)** U+2028 and U+2029 in a mailto local part and domain render as text.

Revision 3, orchestrator decision: the Markdown parser's URI normalisation
encodes `^ { | }` and the backtick. A `mailto:` href may therefore be
percent-encoded, as long as `decodeURIComponent(href)` is exactly
`mailto:<address>` and the href has no `?` or `#`. The spec requires a single
address. It does not require literal characters.

Fresh `baseline_verifier`: **PASS**. Test sha256 `0bd44e9f…0e4e`, against
candidate `35dc30d7…c26e`.
- 87 failed and 36 passed out of 123.
- All 87 failures are "link still rendered".
- All 13 guards pass.
- Of the passing non-guard tests, 23 are already true on the candidate:
  - the host-position format-character cases, which URL host parsing already
    blocks;
  - `mailto:a%A0b@c.example`.

  They document existing behavior and are not Red evidence.
- The control files (mailto2, urls, security) pass 49 of 49.
- `tsc` errors come only from `worker.test.tsx`.

The 3.10 baseline is **awaiting feature-owner approval**.

## 3.9 Green: Web Worker parsing (9 Nov)

The `implementer` reported GREEN, on uncommitted work on top of HEAD `79d8ab7`.

**Results:**
- `npm run verify`: `tsc` is clean.
- vitest: 155 passed and 87 failed. All 87 failures are in the pending 3.10
  `suspicious` file.
- The approved worker test passes 14/14, and every other approved test hash is
  unchanged.
- Throwaway parity and worker-entry checks passed. They were not kept.

**Candidate sha256:**

| File | sha256 |
|---|---|
| `MarkdownView.tsx` | `d998fa33…238d` |
| `MarkdownView.pipeline.ts` (new) | `dfa37b33…abed` |
| `markdownRunner.ts` (new) | `11e7ffab…a141` |
| `markdown.worker.ts` (new) | `e1fbd2e5…2f38` |
| `index.ts` | `254ff8db…95d1` |
| `package.json` | `18923673…37bf` |
| `package-lock.json` | `e468d3d0…9fe7` |

**Dependencies:** pinned to the versions already installed:
- hast-util-to-jsx-runtime 2.3.7
- unified 11.0.5
- remark-parse 11.0.0
- remark-rehype 11.1.2
- @types/hast 3.0.5 (dev)

**Implementer-reported deviations and risks, for review:**
- The in-thread path keeps react-markdown, so the approved limits and dos mocks
  still hold. Only the worker path uses `toHast` + `hast-util-to-jsx-runtime`.
  The D11 text needs updating to say so.
- Worker hast is rebuilt from a tag and property allowlist. Unknown nodes are
  dropped together with their subtree.
- There is no explicit depth cap on worker hast. It relies on the
  `RenderBoundary` fallback.
- Bundling of the worker URL in a real browser has not been tested yet. That
  is the task 3.9 consuming-app build check.

**Next:** the candidate is frozen at `scratchpad/snap-3.9`. A fresh
`candidate_verifier` and a `security_reviewer` were dispatched in parallel.

### 3.9 candidate verification (9 Nov)

Fresh `candidate_verifier`: **PASS** on `snap-3.9`.

- **Integrity.** All 8 candidate hashes and all 12 approved test hashes match.
  `limits.ts` is unchanged.
- **Tests.** vitest: 155 passed and 87 failed. The failures are only in the
  pending `suspicious` file. `tsc` exits 0.
- **Dependencies.** The `package.json` diff adds only the 5 approved pins, and
  they match what is installed. The lock-file diff touches only the root
  package's dependency lists.
- **MDR-10.** All 6 scenarios were confirmed from the code. The worker test
  passes.
- **Throwaway checks.** These were deleted afterwards.
  - 10 parity cases where the worker DOM equals the in-thread DOM, covering
    footnotes, tables, task lists, raw HTML, images, unsafe links, mailto
    links, fragment links and credentialed URLs.
  - 1 test sending hostile hast through a fake runner. All of the following
    were stripped: script, iframe, style, comments, img, src, on* attributes,
    `javascript:` links, credentialed links, multi-recipient mailto,
    unprefixed ids, untrusted fragments, raw nodes, and non-checkbox inputs.
- **Non-blocking finding.** The `a` component's id check uses the general
  `ID_PREFIX`, not the per-view prefix. The sanitiser enforces the full prefix,
  so nothing is exposed.

3.9 waits on the `security_reviewer` before it closes.

## Feature-owner approval 6 (9 Nov)

The owner replied "YES" and approved the 3.10 baseline
`MarkdownView.suspicious.test.tsx` `0bd44e9f…0e4e`. That includes the
orchestrator decision that a mailto href may be percent-encoded.

The 3.10 `implementer` was dispatched against the repo candidate, in which
`MarkdownView.tsx` is the 3.9 version `d998fa33…238d`. The 3.9 security
review continues in parallel on the frozen `snap-3.9`. Any 3.9 fix will be
applied on top of 3.10.

## 3.10 Green: suspicious link characters (9 Nov)

The `implementer` reported GREEN. The only file changed is `MarkdownView.tsx`,
which went from `d998fa33…238d` to `2774ec68…3751`.

**What changed**
- `safeUrl` decodes the destination once with UTF-8 (BOM kept) and rejects any
  `\p{Cf}` character, or `\p{White_Space}` outside ASCII. It does this before
  `trim()` and `new URL()`.
- A mailto link is rejected if a `%` is still present after decoding, or if the
  address fails the ASCII local-part or domain allowlist.
- One helper serves both the in-thread and worker-hast paths.

**Results**
- Before the change, the suspicious test file failed 87 of 123, confirming Red.
  After the change it passes 123 of 123.
- `npm run verify` exits 0, with 242 of 242 tests passing.
- Every test hash and every other source hash is unchanged.

**Implementer notes, for review**
- The check covers the whole destination, including a mailto query.
- A lone invalid escape in an https link, such as `%A0`, decodes to U+FFFD and
  stays a link.
- The rule also covers Cf characters the spec doesn't list, such as soft hyphen
  and tag characters, and U+0085.
- No repo test runs the MDR-12 inputs through the worker path. A throwaway
  check showed parity.

**Next steps**
- The candidate is frozen at `scratchpad/snap-3.10`.
- A fresh `candidate_verifier` and a combined 3.8/3.10 `security_reviewer`
  were dispatched in parallel.
- The 3.9 `security_reviewer` is still running on `snap-3.9`.

### 3.10 candidate verification (9 Nov)

A fresh `candidate_verifier` gave **PASS** on `snap-3.10`.

| Check | Result |
|---|---|
| Hashes | All match. Only `MarkdownView.tsx` differs from `snap-3.9`. |
| vitest | 242/242 across 12 files. `dos` 15, `suspicious` 123. |
| `tsc` | Exit 0. |
| Diff | Adds conditions only. Nothing was loosened. Both paths reach `safeUrl`. |
| Worker path (throwaway check) | All 8 MDR-12 blocked sources render no `<a>`. Ordinary links match in-thread. |
| Not over-blocking | `%20`, `100%`, IDN https, CJK path, `Dev.Name+tag@` and `a%2Bb@` all stay links. |

3.10 now waits on the combined 3.8/3.10 `security_reviewer`.

### Combined 3.8/3.10 security review (9 Nov)

`security_reviewer` on `snap-3.10` (`MarkdownView.tsx` `2774ec68…3751`) found
**no bypass** of MDR-04, MDR-08, MDR-09, MDR-11 or MDR-12 as written. It ran
122 probes on both paths, with 0 differences between the paths, and a fuzz of
300,000 random destinations:
- 0 results changed when `safeUrl` was applied twice;
- 0 leaked mailto separators;
- 0 disallowed schemes;
- 0 links that kept credentials.

The URL checks take about 1–4 ms per 1,000 links. A full 20,000-character
render with about 1,000 links takes 141 ms or less in-thread, and 70 ms or
less for the worker-path render.

All four findings are LOW. They are gaps against the owner's intent ("anything
potentially malicious renders as text"), not against the spec text:
1. Punycode hosts (`xn--…`) that decode to a ZWNJ/ZWJ or a braille blank pass,
   because MDR-12 checks only the percent-decoded source. Literal forms are
   blocked. Homographs such as Cyrillic `аpple` and the fraction slash fall
   under the owner's IDN choice.
2. Invisible characters that are neither Cf nor White_Space still render in
   https paths: U+3164, U+2800, U+FE0F and U+1160.
3. `mailto:a@-.` is accepted, because the domain check covers characters only.
   It cannot add recipients.
4. The link `title` is not checked for bidi characters. It is escaped text, not
   a destination.

Tasks 3.8 and 3.10 are marked complete, because their stated checks passed:
tests, `tsc`, candidate verification and security review. Findings 1–4 go to
the owner as a possible MDR-12 extension.

## Feature-owner decision 7 (9 Nov)

After the combined 3.8/3.10 security review, the owner said: "ignore all
potential malicious element parse as plain text". All four LOW findings will be
fixed. Changes:

- **New requirement MDR-13, with 3 scenarios.** A link renders as text in any
  of these cases:
  - Its destination contains a default-ignorable, blank (U+2800, U+3164,
    U+FFA0, U+115F, U+1160) or slash-look-alike (U+2044, U+2215, U+29F8,
    U+FF0F) character.
  - Its http(s) host, after conversion from punycode, contains such a
    character or an MDR-12 character.
  - Its `mailto:` domain is not 2 or more valid labels.
  - Its title contains any of these characters.
- **Unchanged:** IDN `https` links still work. The owner chose this earlier.
  It means Cyrillic or Latin look-alike hosts still render as links. This is
  flagged to the owner.
- **New task 3.11**, to be tested in `MarkdownView.hidden.test.tsx`.

Existing approved tests checked against the new rule:
- No approved test uses a link title.
- No approved test expects a single-label `mailto:` domain to stay a link.

**3.9 security review:** the agent stopped early on an API usage limit (resets
20:40 SGT) and returned no verdict. It will be resumed, and 3.9 and 3.3 stay
open until then. No orphan node processes remain.

## 3.11 Red: MDR-13 hidden characters (9 Nov)

`test_designer` wrote `MarkdownView.hidden.test.tsx` (`76a871b0…2581`, 112
tests) against candidate `2774ec68…3751`. It fails 93 tests and passes 19.

The 19 passes:
- 18 guards.
- `U+FF0F in host`, which the URL parser already rejects. This test is a
  regression guard and does not demonstrate Red.

All 93 failures are "link still rendered". `tsc` is clean.

The worker path is covered with 6 blocked cases and 3 guards.

Fresh `baseline_verifier`: **PASS**.
- All 13 MDR-13 scenario inputs appear verbatim.
- The test's punycode encoder was checked against `bcher-kva` and against
  `domainToASCII`.
- Partial fixes would fail at least one test: percent-decode only,
  https-title only, label charset only, named blanks only, and in-thread only.
- Over-blocking would fail a guard: blocking all IDN, all non-ASCII titles,
  or every `xn--` host.
- The control tests pass 172 of 172.
- Run with `--environment jsdom`.

The 3.11 baseline is **awaiting feature-owner approval**.

### 3.9 security review, resumed after the usage limit (9 Nov)

Result: **FAIL**, with 1 HIGH finding. Reviewed `snap-3.9` (`d998fa33…`); its
hashes were unchanged before and after the run.

**HIGH: the main thread can freeze while rendering a legitimate source.** The
pre-check caps cells per table, not in total. A 19,708-character source made
of 35 tables, each 71 columns × 137 rows, passes the pre-check. The worker
then parses it in 487 ms and produces a 708k-node hast. On the main thread:
- structuredClone takes 1,318 ms;
- `toJsxRuntime` takes 1,326 ms;
- the full jsdom render takes 6,007 ms.

With 10 tables, the render still takes 2.2 s.

Proposed fix:
- a node budget in the worker, replying `ok:false`;
- the same budget enforced on the main thread;
- a cap on total table cells across the whole source.

**Other findings:**
- **MEDIUM/LOW:** `className` is not allowlisted, so a page-CSS class from the
  worker reaches the DOM.
- **LOW:** a malformed `ok` reply, such as `hast:[]` or `{}`, blanks the view
  instead of showing the source.
- **LOW:** two slow reports exhaust the retries of later requests that were
  never sent.
- **LOW:** requests aren't cancelled on unmount, so the queue keeps growing.
- **LOW/info:** a custom runner that throws synchronously unmounts the React
  root.

**Held up under testing:**
- The main-thread allowlist rebuild, against every injection probe: schemes,
  `on*`, `style`, `id`/`name`, tags, node types, `__proto__`, getters, and
  fragment trust.
- Deep hast: depths from 3k to 100k fall back in 31 ms or less.
- Runner id spoofing, terminate races, error storms and never-loading
  workers.
- Parity: worker-path DOM is identical to the in-thread path on 15 sources.
- Supply chain and the worker URL.

**Not verified:** real-browser timings, the Vite worker bundle, and
`messageerror`.

3.9 and 3.3 stay open. The owner's decision is needed.

## Feature-owner decision 8 (9 Nov): "go"

The owner accepted both proposals.

1. **No links at all.**
   - **Spec.**
     - MDR-04 is replaced by "No links". A link shows its text, followed by
       ` (<destination>)` when the destination is different. Autolinks show
       the address once. No element has an `href`.
     - MDR-08 changes to footnotes as superscript numbers plus a footnotes
       section, with no links or back-references.
     - MDR-01 is reworded: link syntax is recognised and then displayed as
       text.
     - MDR-09, 11, 12 and 13 are removed. They were added by this change
       only.
   - **Design.** New D12.
   - **Tasks.** 3.11 now means "no links" Red/Green, which rewrites the
     approved link-related tests. 3.8 and 3.10 stay checked as history.
   - **Files.** The unapproved `MarkdownView.hidden.test.tsx` (`76a871b0…`)
     is deleted.
2. **Worker-result bounds.**
   - **Spec.** MDR-10 gets three new scenarios:
     - oversized output, meaning more than 25,000 nodes or more than 10,000
       table cells in total;
     - malformed or hostile results, and class names;
     - queue fairness and cancellation.
   - **Design.** New D13.
   - **Tasks.** New 3.12. 3.9 and 3.3 close when it passes.

`openspec validate --strict`: valid.

Two `test_designer` agents were dispatched in parallel, writing to separate
files.

## 3.11 and 3.12 Red (9 Nov)

**Test layout.** The owner moved the package's tests from `src/` to `test/`, as
recorded in commits `3a7bd9e` and `50b7972` and in
`2026-11-09-markdown-view-test-folder.md`. As a result:
- The approved test hashes changed. Only the import paths differ (`./` became
  `../src/`).
- Both Red runs were adapted to the new layout.

### 3.11 "no links"

`test_designer` rewrote three test files and added one. The orchestrator moved
them into `test/` and rewrote their imports.

| File | Change | sha256 |
|---|---|---|
| subset | rewritten | `7453f1b1…` |
| security | rewritten | `1d761ebe…` |
| footnotes | rewritten | `88804860…` |
| nolinks | new | `7ba8b2e7…` |

- The urls, mailto, mailto2 and suspicious tests were folded into nolinks and
  deleted.
- Result on candidate `2774ec68…`: 53 failed, 65 passed. `tsc` is clean.
- The 15 payload tests that already pass are regression guards: the old
  `safeUrl` already rendered text for them.

### 3.12 bounds

`test_designer` wrote `test/MarkdownView.budget.test.tsx` (`189c15bb…`).
- Result: 24 failed, 6 passed. The 6 passes are guards, including `null` hast,
  which the runner already rejects.
- `tsc` is clean.

**Next step:** fresh `baseline_verifier` runs for both are in parallel, on
`snap-3.11` and `snap-3.12`.

### 3.11 baseline verification

A fresh `baseline_verifier` gave **PASS**, with no blocking findings.

- **Hashes and move:** all hashes match. The dos, limits and worker files
  differ only in their import and mock paths.
- **Spec coverage:** the inputs for MDR-04 and MDR-08 are verbatim, and the
  exact-text assertions match the spec.
- **Partial-fix resistance:** each of these partial fixes fails at least one
  test:
  - dropping `href` but keeping `<a>`;
  - fixing the in-thread path only;
  - not showing the destination;
  - showing an autolink's destination twice;
  - keeping footnote back-references;
  - keeping footnote `<a>` wrappers.
- **Reproduction:** 53 failed and 65 passed. All failures are assertion
  failures on missing behavior. `tsc` exited 0.

**Non-blocking:**
- About 60 hostile cases specific to the old validators were dropped: the
  mailto encoding matrix and the invisible-character matrix. They are moot
  under "no links", since all of those inputs render as text.
- The 15 payload guards check that no link renders, but not the destination
  text.

### 3.12 baseline verification

- **Revision 1 (`189c15bb…`):** `baseline_verifier` gave **FAIL**, with one
  blocking gap. The tests did not use a hostile class on `code`, so an
  allowlist that kept every class on `code` would still have passed.
- **Revision 2 (`6f706f4e…a519`):**
  - **Changes:**
    - Hostile classes are now tested on `code` and `li`.
    - The early-stop test asserts `limit < n <= limit + 1`.
  - **Results:**
    - A fresh `baseline_verifier` gave **PASS**.
    - 24 tests fail and 6 pass, in both plain and shuffled runs.
    - `tsc` exits 0.
    - Every partial-fix variant fails at least one test.
  - **Non-blocking finding:** the exact stop point was stricter than the
    design wording. The orchestrator added the `limit + 1` sentence to D13.
    `openspec validate --strict` reports valid.

**Both 3.11 and 3.12 baselines are awaiting feature-owner approval.**

### 3.12 revision 3 (owner decision "add it")

D13 adds a rule: each element keeps at most one class, the first allowed one.
This came from the 3.9 probe, where jsdom hung on 50 elements with 200,000
classes each.

The test has two new cases:
- at most one class per element;
- a single `code` element with 200,000 classes renders as `language-x0` in
  under 1 s.

**Baseline** `0cae4f70…dac` (32 tests):
- 26 failed and 6 passed, in both plain and shuffled runs. `tsc` exits 0.
- A fresh `baseline_verifier` gave **PASS**.
- The tests catch the partial fixes "keep all", "keep last" and
  "dedupe only". An O(n²) filter would exceed the time bound.
- Real timers are used only inside the test.

**Both 3.11 (`7453f1b1…`, `1d761ebe…`, `88804860…`, `7ba8b2e7…`) and 3.12
(`0cae4f70…`) baselines are awaiting feature-owner approval.**

## Feature-owner approval 7 (9 Nov)

The owner replied "approve" and approved both baselines:

| Task | Test file | sha256 |
|---|---|---|
| 3.11 | subset | `7453f1b1…` |
| 3.11 | security | `1d761ebe…` |
| 3.11 | footnotes | `88804860…` |
| 3.11 | nolinks | `7ba8b2e7…` |
| 3.12 | budget | `0cae4f70…` |

Both tasks write `MarkdownView.tsx`, so they are implemented one after the
other: 3.11 first, then 3.12.

The 3.11 `implementer` was dispatched against the repo, where
`MarkdownView.tsx` is `2774ec68…`.

## 3.11 Green: no links (9 Nov)

**Result.** The `implementer` reported GREEN. 124 of 150 tests pass, and all 26
failures are in the 3.12 budget file, which is expected. `tsc` is clean, and no
test file changed.

**Changed files.**

| File | Before | After |
|---|---|---|
| `MarkdownView.tsx` | `2774ec68` | `ffc2cbf3` |
| `MarkdownView.pipeline.ts` | `dfa37b33` | `f90cac0f` |

**What changed.**
- **Link handlers.** mdast `link` and `linkReference` nodes now render as their
  children followed by ` (<trimmed url>)`.
- **`rehypeNoLinks`.** It removes every remaining `a`:
  - a footnote reference keeps only its number;
  - a back-reference is removed;
  - any other `a` becomes text.
  It runs on both paths, and again in the worker sanitiser.
- **Dead code removed.** `safeUrl`, the mailto, suspicious-character and
  fragment-trust logic, and `urlTransform`.

**Implementer choices, for review.**
- The destination is omitted when it equals the text, or `mailto:`/`http://`
  plus the text. Under that rule, `[example.com](http://example.com)` shows
  its text only.
- `[x]()` shows `x`.
- The footnote `sup` has no ID.

**Next steps, running in parallel.**
- The candidate is frozen at `scratchpad/snap-3.11g`.
- A fresh `candidate_verifier` and a `security_reviewer` are checking 3.11.
- The 3.12 `implementer` is building on top of 3.11 in the repo.

### 3.11 candidate verification

Agents resumed after the session restart. A fresh `candidate_verifier` gave
**PASS** on `snap-3.11g`.

- **Hashes:** all match. Only `MarkdownView.tsx` and `pipeline.ts` differ from
  `snap-3.11`.
- **Tests:** 124 of 150 pass. The 26 failures are all in the 3.12 budget file.
  `tsc` exits 0.
- **No loosening:** images, raw HTML, heading IDs, checkboxes and the ID prefix
  behave as before. The dead link code is removed.
- **Throwaway checks, all passing:**
  - in-thread vs worker DOM parity on 15 sources;
  - 9 hostile worker trees (`a`/`javascript:`, `svg a`, `area`, `base`, `form`
    with `action`, `link`, `button` with `formaction`, footnote-style `a`);
  - no `a` or URL attribute survived.
- **Non-blocking findings:**
  - Omitting the destination for an empty destination, or for text plus
    `http://` or `mailto:`, was narrower than the MDR-04 wording. The
    orchestrator added a clarifying sentence to MDR-04 that matches the
    implementation, and `openspec validate --strict` passes. No approved test
    is affected.
  - A hostile worker `a` renders as `1 (#…)` text, which is safe.

3.11 closes after the `security_reviewer` passes.

## 3.12 Green: worker-result bounds (9 Nov)

The `implementer` reported GREEN on top of 3.11.

**Results:** 150 of 150 tests pass, including budget 32 of 32. `tsc` is clean
and no test file changed.

**Candidate files:**

| File | sha256 prefix |
|---|---|
| `limits` | `25c229f4` |
| `pipeline` | `1615675e` |
| `worker` | `764c6338` |
| `runner` | `73ce84e2` |
| `MarkdownView.tsx` | `b5d8fe39` |

**Implementer choices to review:**
- The `retried` flag is removed. Each worker failure settles only the request
  that was in flight.
- Non-string class entries are skipped.
- `language-*` is matched by prefix only.
- A new `isHastRoot` helper was exported.
- The `sanitizeHast` and `toJsxRuntime` passes are still recursive.

**Owner decision needed:** `heading()` now keeps `class="sr-only"` on `h2` on
both paths. This affects the footnote label, which renders as
`<h2 class="sr-only">Footnotes</h2>` (GitHub does the same). Before this
change, every heading attribute was dropped.

**Next:** the candidate is frozen at `scratchpad/snap-3.12g`. A fresh
`candidate_verifier` and a `security_reviewer` were dispatched in parallel.
The 3.11 `security_reviewer` is still running on `snap-3.11g`.

### 3.12 candidate verification

Fresh `candidate_verifier` on `snap-3.12g`: **PASS**.

- **Integrity and tests**
  - Only the 5 src files differ from `snap-3.11g`.
  - The suite passes 150/150, including a shuffled run.
  - `tsc` exits 0.
- **D13 review:** each item was confirmed against the code.
  - `countHastNodes` is iterative and returns `limit+1`.
  - Budgets are enforced in the worker and on the main thread, before
    `toJsxRuntime` runs.
  - The shape check runs on both paths.
  - The class allowlist is one pass and keeps the first allowed class.
  - `safeParse` is in place.
  - An aborted request is removed and never posted.
  - Fairness holds.
- **Choices accepted**
  - (a) No `retried` flag. 50 queued requests against 4 kinds of always-failing
    worker all settled, with 0 timers left.
  - (b) Non-string class entries are skipped.
  - (c) `language-*` is matched by prefix. Non-blocking.
- **Throwaway checks**
  - A 35-table source falls back to `pre` on both paths.
  - 10-source DOM parity holds.
  - Unmount leaves no leaked timers or window listeners.
  - `handleParseRequest` returns `ok:false` for oversized output.
- **Non-blocking findings**
  - `pump` recurses once per queued request when `createWorker` or
    `postMessage` throws synchronously. This is bounded by queue length.
  - The `h2.sr-only` change still needs an owner decision. A Reporter can't
    produce the class from Markdown or raw HTML; only a hostile worker could,
    and the impact would be low.

3.12 closes after the `security_reviewer` passes and the owner decides on
`h2.sr-only`.

### 3.11 security review

The `security_reviewer` ran on `snap-3.11g` and gave **PASS**: no CRITICAL,
HIGH or MEDIUM findings.

**What was tested**

- 32 link forms, run in-thread and in the worker, plus a hostile-worker barrage
  and a DOM audit covering navigating tags, URL attributes, `on*`, ARIA
  references and ids. No issues were found, and the in-thread and worker HTML
  were identical.
- Destinations are never re-parsed.
- Hostile footnote labels: every `id` is prefixed and unique across views.
- Regressions: images, raw HTML, headings and checkboxes all behave as before.

**LOW findings**

1. When deciding whether to omit the destination, `textOf` ignores `img` alt
   text. Example:
   `[https://evil![.bank.example](i)](https://evil)` shows
   `https://evil.bank.example` with no suffix.
2. A hostile worker can build a nested `a` chain that makes `unlink` do
   O(depth²) work. A depth of 5,000 takes 453 ms on the main thread. Deeper
   chains overflow the stack and fall back to plain text.
3. Worker `className` is unrestricted. This is already fixed by 3.12 (D13).
4. Informational: Cf, bidi and fake-`)` characters appear verbatim in the
   ` (destination)` suffix. Example: an RLO makes the destination read as
   `…exe.pdf`.

**Status:** task 3.11 is marked complete, because its stated checks passed.
Findings 1, 2 and 4 go to the owner as a possible follow-up.

## Feature-owner decision 9 (9 Nov): "keep sr-only fix 1 2 4"

1. **Keep `h2.sr-only`.** The footnote section heading keeps only the
   `sr-only` class, as GitHub does. MDR-08 now says so. This settles the open
   item from 3.12.
2. **Fix 3.11 security findings 1, 2 and 4.** New requirement **MDR-14**
   ("Shown destinations are unambiguous"), with 3 scenarios:
   - image alt text counts as link text;
   - Cf, U+2028/2029 and non-ASCII whitespace in a shown destination appear
     as `<U+XXXX>`;
   - `(` and `)` in a shown destination appear as `%28` and `%29`.

   New task **3.13**, which also covers a linear-time `unlink` for nested
   `a` elements in worker hast.

`openspec validate --strict`: valid.

The 3.13 `test_designer` was dispatched against `snap-3.13`, a copy of the
repo's 3.12 candidate. The 3.12 `security_reviewer` is still running.

## 3.13 Red: MDR-14 and linear unlink (9 Nov)

**Test file:** `test_designer` wrote `test/MarkdownView.destinations.test.tsx`.

**Revision 1 (`d29d9ecc…`)**
- Result: 55 failed, 23 passed.
- `baseline_verifier` gave **FAIL**. The 100 ms bound timed the React render
  plus `waitFor`, and a flat tree with no unlink work already takes 45–130 ms
  cold.

**Revision 2 (`b217598c…`)**
- Changes:
  - The view test for the depth-5,000 `a` chain no longer measures time.
  - A new test times only `rehypeNoLinks()(tree)`, after a warm-up.
  - A guard checks that parentheses in link text are not escaped.
- Result: 55 failed, 26 passed (81). Three shuffled runs gave the same
  counts. `tsc` is clean.
- A fresh `baseline_verifier` gave **PASS**.
  - A throwaway linear flatten took 0.2–1.7 ms, including cold runs. The
    current code took 800–845 ms, or overflowed the stack in some runs. The
    bound therefore separates linear from quadratic code reliably.
  - The no-`a` and DEEPEST checks are not vacuous.

**Non-blocking findings**
- The RangeError seen in some shuffled runs comes from the current recursive
  `unlink`. It reflects the missing behavior: the test is still Red, and the
  count stays at 55.
- The measured time is about 800 ms, not the 725 ms first reported.

The 3.13 baseline is **awaiting feature-owner approval**.

### 3.12 security review (9 Nov)

`security_reviewer` on `snap-3.12g` gave **FAIL**, with 1 MEDIUM and 2 LOW
findings. The worker path holds: every prior 3.9 repro is fixed.

**MEDIUM: the in-thread path has no node budget.** This is the path used when
no Worker exists (jsdom, SSR). The pre-check misses nested-list constructs.
These sources all pass `exceedsRenderLimits` and render formatted in-thread:

| Source | Time in-thread |
|---|---|
| `"- -\n"`×5000 | 2.6–2.8 s (`toHast` alone takes 2.15 s) |
| `(">"×32+"a\n\n")`×571 | 1.1–1.3 s |
| `("- "×16+"a\n")`×588 | 1.2 s |

This breaks MDR-05 "Pathological input" (under 1 s in the test environment).
The worker path is safe for the same inputs.

**LOW findings:**
- Property bloat from a hostile worker gets past the node count:
  - 200k junk props × 20 `p` takes 1.1 s;
  - a 2M-entry unmatched class list × 50 takes 0.9 s.
- `pump` recursion overflows the stack after about 10k queued requests with a
  synchronously throwing `createWorker`, which wedges the runner.

**INFO:**
- The tree is counted before it is sanitised, which a custom runner with a
  stateful getter can exploit.
- A worker that never answers is recreated for every request.
- The `language-*` suffix is Reporter-controlled.

**No issues found:**
- the worst legitimate render under all budgets on the worker path is 713 ms;
- deep, cyclic and Proxy hast fall back;
- `h2.sr-only` can't be produced by a Reporter;
- regressions and parity hold;
- the snapshot suite passes 272 of 272.

3.12, 3.9 and 3.3 stay open. The owner must decide on the fix.

## Feature-owner approval 8 (9 Nov)

The owner replied: "I approve the test move to reporter BE".

Recorded as follows:
- **Approved:** the 3.13 baseline, `test/MarkdownView.destinations.test.tsx`
  (`b217598c…`).
- **Next:** start the Reporter backend (group 2).
- **Proposed 3.14 fix** (in-thread node budget and related LOW items from the
  3.12 security review): not yet decided. 3.12, 3.9 and 3.3 stay open.
- **"Remove the old tests":** waiting for the owner to clarify. Nothing has
  been deleted.

Dispatched in parallel. They write separate files.
- **3.13 `implementer`:** works in the repo, on top of the 3.12 candidate.
- **2.3 `test_designer`:** RPT-01..03 Red. It writes the Reporter harness,
  runner and tests. Environment:
  - JDK 25;
  - JDBC jar `postgresql-42.7.14.jar` from the Gradle cache;
  - Python 3.14;
  - Docker with `postgres:17`.

## Feature-owner decision 10 (9 Nov): "fix 3.14 and ignore the remove old tests"

1. **3.14 approved as proposed.** Changes:
   - **Spec:** new MDR-10 scenario, "The in-thread path has the same output
     budget".
   - **Design:** new D14.
   - **Tasks:** new 3.14. Tasks 3.12, 3.9 and 3.3 close when 3.14 passes.
   - **Tests:** the approved dos and limits tests have their react-markdown
     mocks moved to the pipeline. Their assertions stay the same, but the
     change needs re-approval.
   - `openspec validate --strict`: valid.
2. **"Remove old tests" dropped.** Nothing was deleted.

The 3.14 `test_designer` was dispatched against `snap-3.14`, a copy of the
3.12 candidate `snap-3.12g`.
