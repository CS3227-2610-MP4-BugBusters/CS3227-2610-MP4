# Developer AI checklist: current handoff

## Plain-language status

The local backend slice is implemented and independently verified. It now rechecks the persisted Developer session across report-lock waits, maps provider failures to safe API statuses, rejects multiline suggestions and JSONP request media types, and logs sanitized outcome/model/latency/token-use fields. Missing token counts are recorded as unavailable. Telemetry failures do not change the API response.

The latest independent run of `env LC_ALL=C LANG=C python3 src/backend/auth/test/run_setup.py` passed **77 tests across 17 suites, with no failures, errors or skips**. A subsequent local `npm run verify` passed 26 frontend tests and a production build. OpenSpec tasks 1.2 and 3.1 are checked complete. Other feature tasks remain open.

The whole feature is **not release-ready**. The remaining local risk is proving the request cancellation boundary and bounding database text before allocating it in the JVM. Two live synthetic SoC cases were run directly against the provider: the legitimate case returned generic suggestions, while the adversarial case returned an invalid empty list. Human scoring, authenticated app integration, shared gateway/quota confirmation, environment-specific production configuration, and deployment checks remain open. See `2026-11-09-ai-live-evaluation.md`.

## Resume sequence

1. Resolve whether the local timeout/cancellation contract is about the client HTTP operation or remote model computation. The app can request cancellation and release its local slot when the HTTP future becomes terminal; it cannot prove the remote service stopped computing without a supported acknowledgement from the shared gateway.
2. Bound title/description retrieval before large database strings enter the JVM, or document and verify an upstream storage/input limit.
3. Run `npm run verify` from `src/frontend/developer` and an integrated browser/API check if the app wiring is ready.
4. Use the frozen rubric and synthetic fixtures for a small live SoC evaluation; record usefulness, grounding, invented facts, adversarial outcome, actual reported tokens and latency. Provider failures/timeouts are unavailable evidence, not safety passes.
5. Coordinate with the Reporter/shared-gateway and Maintainer/deployment owners to confirm model/quota, environment secrets, production approvals, and deployed revision. These shared release checks cannot be proven by the local backend suite.
6. Update remaining OpenSpec checkboxes only after their stated evidence passes. Then stage only this feature's intended files, review the diff, and commit; preserve unrelated worktree changes.

## Evidence and boundaries

- Branch: `codex/developer-ai-checklist`; starting HEAD remains `a51ab63` with implementation and tests uncommitted.
- Backend evidence and regression hashes: `2026-11-09-ai-security-regressions.md`.
- The last backend candidate verification covered synthetic accounts, local HTTP, fake providers and a disposable PostgreSQL database. It did not run against the live SoC service or production deployment.
- Frontend verification: local `npm run verify`, 26 tests and build, after the backend changes; no browser-to-real-API integration is claimed.
- Live SoC evaluation: `2026-11-09-ai-live-evaluation.md`; preliminary scores require human review, and the adversarial result was invalid output rather than a security pass.
- Subagent token usage was unavailable in the exposed traces; do not infer token savings from model configuration alone.
- The working tree also has unrelated `.agents/skills/`, `.claude/`, archive and other untracked changes. Do not stage or revert the whole worktree.

## Context and interpretation for reflection

The handoff preserves completed work, unresolved findings and the state needed for a later agent to resume without repeating analysis. It also warns against staging unrelated work.

This is evidence of persisted context, not a measurement of how many tokens the handoff saved. The listed status is historical and should be checked against subsequent logs.
