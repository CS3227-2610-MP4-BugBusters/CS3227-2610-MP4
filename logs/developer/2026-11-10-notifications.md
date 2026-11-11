# Developer lifecycle notifications — 10 November 2026

## Summary

Developers now see recent assignment, reassignment, resolution-return and reopening notifications on the dashboard and can mark them read. Notifications are inserted with the Maintainer lifecycle transaction and store no report content. Former assignees receive generic notices without report identifiers or links; existing report access checks remain authoritative. Notifications refresh on dashboard load rather than claiming real-time delivery. Deployed behavior remains unverified.

## Coordination and findings

Starting revision `78cb8dd`. The coordinator specified event, API, transaction and privacy rules in OpenSpec. A test designer wrote an assignment/list/read test; an independent verifier confirmed Red. The implementer added storage and API behavior, while the designer added reassignment, return, reopening and cross-user cases. A separate frontend Red was verified before implementation. The security reviewer caught misplaced event writes and a late mark-read response that could affect a later login; both were corrected. The first late-response test did not directly observe the session callback, so it was strengthened with a callback spy and deferred response. The migration fixture was updated to remove the new table when simulating an auth-only database.

The first full backend run failed because report detail tests expected the pre-return-reason response and because login tests shared and exhausted the same disposable database IP rate-limit budget. The strict DTO assertion was updated while retaining privacy checks. Test fixtures now clear the login-attempt table between fixture setups; production limits are unchanged. A forced notification insertion failure test verifies report, history and notification writes roll back together.

## Verification

- `python3 src/backend/auth/test/run_setup.py`: 189 tests passed on disposable PostgreSQL after fixture corrections.
- Independently: `python3 src/backend/auth/test/run_setup.py --tests mp4.auth.CrossRoleJourneyIntegrationTest --tests mp4.auth.DeveloperNotificationRollbackIntegrationTest`: four cross-role tests and one rollback test passed on disposable PostgreSQL.
- `src/backend/shared/test/run-shared-report-storage.sh`: shared storage and fresh/auth-only/restart startup migration checks passed after fixture correction.
- `npm run verify` in `src/frontend/developer`: independently passed 62 tests, typecheck and build.
- Independent security review covered owner scoping, former-assignee privacy, CSRF, notification event writes and logout cancellation. It found no remaining blocker by inspection.
- OpenSpec validation passed locally. Browser, hosted CI, deployment and every unauthorized/invalid API case remain unverified for this revision; tasks requiring complete endpoint rejection coverage remain open.

## Resource evidence

Agent token, reasoning, cache and cost measurements were unavailable. No token savings are claimed. Tests used synthetic accounts and disposable databases, not user data.
