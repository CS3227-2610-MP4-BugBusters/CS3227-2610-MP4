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
