# Developer return reason — 10 November 2026

## Summary

The assigned Developer report detail now exposes only the latest Maintainer `resolution_returned` reason when the report is in progress. It omits unrelated history, returns null when no applicable reason exists, and renders the text as plain text. There is no schema change. Authenticated integration tests and the full Developer frontend checks passed; an independent candidate verifier also passed.

## Implementation and coordination

Starting revision `0bbcda2`. The coordinator wrote and validated `openspec/changes/developer-return-reason/`. A test designer added authenticated API and frontend tests; an independent baseline verifier confirmed the missing-field and missing-display failures against the baseline. The implementer added a report-scoped correlated history lookup and the UI. A candidate verifier reran the focused backend test and full frontend suite. The disposable database runner did not use the user's database.

## Verification

- `python3 src/backend/auth/test/run_setup.py --tests mp4.auth.DeveloperAssignedReportsAuthenticatedIntegrationTest`: 5 backend tests passed on disposable PostgreSQL, covering latest reason, unrelated history omission, non-IN_PROGRESS omission and denial to an unrelated Developer.
- `npm run verify` in `src/frontend/developer`: typecheck, 57 tests and production build passed.
- Independent candidate verification repeated both checks successfully.
- `openspec validate developer-return-reason --strict --no-interactive`: passed.
- `git diff --check`: passed.

## Limitations

This does not add assignment or reopening notifications and does not verify a deployed browser journey. Agent token, reasoning, cache and cost metrics were unavailable; no resource savings are claimed.
