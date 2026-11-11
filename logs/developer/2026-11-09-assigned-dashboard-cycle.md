# Developer assigned-report dashboard: TDD evidence

## Summary

The Developer dashboard needs search, status filtering, pagination and distinct result states against the existing assigned-reports API. A focused frontend baseline shows the current UI lacks the search and pagination controls. Initial list rendering, loading/error/retry, and role denial controls passed.

The initial missing-control Red stopped before deeper query, stale-result and
pagination assertions. The implemented candidate now passes the full dashboard
suite; a later account-switch regression found and verified a privacy fix. The
tests use mocked HTTP responses and do not prove real backend authorization or
integration.

## Baseline details

- OpenSpec: `developer-assigned-reports` task 4; starting HEAD `0d6e49495e3d2461eb59e4c6b5c5f0eb5d42ef1f` plus existing dirty worktree.
- Command: `npm test -- AssignedReportsDashboard.test.tsx --reporter=dot` from `src/frontend/developer`.
- Designer and independent verifier: 8 tests, 3 controls passed and 5 failed on missing search or page controls. The first search fixture was corrected before verification.
- Test SHA-256: `39ca7dfb61f126cf45128967382ee5a8e55f9245f1791bd853db4b48c2ef9e65`.
- The verifier initially flagged unspecified control names; the design was clarified to specify labelled Search and Status controls, Previous/Next and Enter or explicit search submission. A fresh rerun passed the scoped Red review (5 expected missing-control failures, 3 controls passed). The deeper behavior assertions had not yet run.
- Specialists used configured gpt-6-luna low roles; token usage unavailable.

## Candidate and privacy follow-up

The Developer dashboard now has accessible search, status filtering, server-page
navigation, distinct empty/error/loading states, and stale-response suppression.
The separate identity-switch regression found that a signed-in Developer's
search, status and page could remain after sign-out and be sent in the next
Developer's session. The OpenSpec now records dashboard state as
identity-scoped. The application clears the search input, submitted query,
status, pagination and selection at sign-out/session/access boundaries and
before loading a newly signed-in user's reports.

- Independent dashboard candidate verdict: PASS. The follow-up account-switch
  Red test also passes after implementation.
- `npm run verify` from `src/frontend/developer`: typecheck passed, 12 test
  files / 45 tests passed, production build passed.
- Focused dashboard test: 8/8 passed. Account-switch test is included in the
  full suite; before the fix, it failed with A's query/status/page retained and
  replayed for B.
- OpenSpec change `developer-assigned-reports` validates strictly; task 4 is
  complete. Task 5 (real-session and persisted-assignment integration) remains
  open.
- Candidate source `DeveloperApp.tsx` SHA-256:
  `54192f79bb06cb2d4cb602899bc2fefe49862fb9ff5dafe3969426576a566eb7`.
- Dashboard test SHA-256:
  `8aaf42b6c660b00f88b93509ea531f44b8233bae7c9d3d7928d5c6794c3ce6a2`.
- Account-switch test file SHA-256:
  `2805d3cf42c86447f5468b7250eb51a0c5058395a4f513898fb1a14ffe82ea52`.
- Test Designer and independent Candidate Verifier used configured Luna low
  roles. The Security Reviewer confirmed the same-mounted privacy finding is
  closed and reran five focused files (21/21 passed). Exact per-agent token
  usage was unavailable. Cross-tab session replacement remains unverified.

These are frontend tests with mocked HTTP and session responses. They do not
prove live authenticated API behavior, PostgreSQL integration, or deployment.

## Context and interpretation for reflection

The dashboard cycle used a focused UI test, implementation and independent review. A privacy review found that query state could cross accounts in one mounted app, leading to an additional regression and reset.

The initial Red and revised test use different bytes; the original Red applies to the recorded original hash. Mocked frontend responses cannot prove live backend authorization or cross-tab synchronization.
