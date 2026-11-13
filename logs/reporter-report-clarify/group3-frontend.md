# Group 3: New Report screen (tasks 3.1, 3.2)

## Summary

Goal: add Check my report to the New Report screen. It shows unclear parts and
missing information as plain text and never changes the draft (RC-01, RC-05c,
RC-06, RC-08 on the client).

- **Process waiver:** the owner decided on 2026-11-13 that groups 3 and 4 skip the
  TDD agent flow (no test_designer, baseline/candidate verifier or security
  reviewer for this group). The stated checks still apply. A separate
  security_reviewer is planned in task 4.1 for the whole change.
- **Provenance:** the frontend implementation (`ReportCheck.tsx`, `NewReport.tsx`,
  `api.ts`, `style.css`) and 12 Vitest cases in `ReportCheck.test.tsx` were already in
  the working tree, written at 06:05-06:06, before this orchestrator session started
  group 3. It did not come from any agent this session dispatched. The orchestrator
  reviewed it against the spec and added one test for RC-01b (Check my report is not
  shown on an existing report view).
- **Behavior:**
  - one on-demand request with exactly `{title, description}` and CSRF;
  - local title/description checks before any request;
  - a progress state, and an empty-result message;
  - fixed error messages that never echo the draft or model output;
  - results as React text nodes only;
  - Dismiss and Submit clear results; a new check aborts the old one.
  - The client also re-validates the response shape and rejects quotes that are not
    in the draft (defence in depth; the server is the authority).
- **Checks:** `npm run verify` passed: typecheck, 2 test files / 23 tests, build.
- **Environment issue found:** `npm run verify` and `vitest run` failed several times
  with "Vitest failed to find the runner" before any test ran, including the
  untouched `App.test.tsx`. Results changed between consecutive runs. Clearing the
  regenerable caches `node_modules/.vitest-cache` and `node_modules/.vite/vitest`
  fixed it. Likely cause, not confirmed: a stale or concurrently written Vitest
  cache, with two Vitest copies (Reporter's own and
  `shared/markdown/node_modules/vitest` via the `file:` link) and other node
  processes running.
- **Limitations:** the backend endpoint (group 2) is not implemented yet, so these
  tests use a stubbed `fetch`. End-to-end behavior is checked in group 4.

## Details

- Revision: `llm-reporter` at `1b2d3b7` plus the working tree.
- Command: `cd src/frontend/reporter && npm run verify` → exit 0 after clearing the
  cache (full output in the session scratchpad, `verify-reporter-6.txt`).
- Tests in `ReportCheck.test.tsx`: RC-01c (no request while typing or
  submitting); RC-01a (one request, exact body, CSRF, results shown); RC-01b (not on
  existing report, added); RC-05c (empty result); blank title makes no request;
  RC-06a (draft unchanged after show and dismiss); RC-06b (Markdown, HTML, URL as
  literal text, no `a`/`img`/`strong`/`script`); a quote not in the draft is rejected;
  unknown category and extra key are rejected; RC-08a (rate limit shows Retry-After,
  draft kept); RC-08b (unavailable, Submit still enabled); progress and old results
  cleared on a new check; results cleared on submit.
- SHA-256: `NewReport.tsx` 5512428a…, `api.ts` abe75fbe…, `ReportCheck.tsx`
  b1dfab42…, `ReportCheck.test.tsx` b1edeff3…, `style.css` a6925a63….
