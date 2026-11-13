# Account profile and local routing

Restored the Developer Account link and routed `/account` through all three local Vite servers to the backend. Active non-demo accounts can update their own display name and password on the Account page. Usernames and roles remain unchanged; the fixed demo Maintainer remains locked. A successful password change still revokes sessions.

The focused account suite passed with a disposable PostgreSQL cluster; Developer verification passed 63 tests, typecheck and build. The security reviewer checked authorization and CSRF, and independently smoke-tested the Developer proxy and Account page using controlled fake services. The reviewer identified malformed Unicode validation, which was added. A separate read-only CLI review found no actionable issues. Live browser login and a concurrent deactivation race were not tested.

## Workflow and evidence

- Base `ae90538`; the original change hiding the Developer link was reversed rather than published as the final behavior. Existing work in the main project checkout was preserved.
- AUTH-10 specification and fixtures were prepared before implementation. Test designer created the Red; independent baseline review found missing anonymous/inactive cases and an incorrect response-field assertion. Both were corrected. Corrected Red was independently reproduced against an isolated archive: 6 tests, 4 passing, 2 expected 404 failures. Corrected test SHA-256 `e6df6cfaa8040ccef0007a8dd67bc1be44fc097405c3112f1b2503db7c79f2a8`.
- Implementer added the authenticated self-update endpoint and Account form. A fresh candidate verifier matched the test hash, passed the focused PostgreSQL suite and Developer `npm run verify`. The security reviewer passed the final code, Vite proxy smoke and a jsdom Account-page smoke; these do not substitute for a live browser test.
- Commands: `python3 src/backend/auth/test/run_setup.py --tests mp4.auth.AccountManagementTest`; `npm run verify` in `src/frontend/developer`; `openspec validate add-shared-authentication --strict --no-interactive`; `node --check src/backend/auth/src/main/resources/static/account/account.js`; `git diff --check`.
- One read-only `codex exec --json` review reported 211,283 input tokens, of which 175,616 were cached, and 983 output tokens, of which 224 were reasoning output. This measures that CLI review only; coordinator and subagent token counts, elapsed time and cost were unavailable. The local raw trace is `/tmp/mp4-account-name-review.jsonl`; inspect and sanitize before sharing. No controlled resource comparison was performed.

## Main integration

Merged `03b415b` after PR #25 landed. The Maintainer Vite conflict was resolved by retaining the Account proxy together with the Developer and Reporter workspace proxies. Developer verification passed 63 tests plus typecheck/build; Maintainer verification passed 121 tests plus typecheck/build after the merge. No live browser or deployed test was performed for the merged candidate.
