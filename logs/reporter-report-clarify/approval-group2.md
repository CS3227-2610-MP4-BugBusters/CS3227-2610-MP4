# Approval: reporter-report-clarify tasks 2.1, 2.2, 2.3

## Summary

On 2026-11-13 the owner gave **standing approval** for group 2 ("auto approve"):
implementation proceeds once a fresh baseline verifier confirms Red, without a
separate per-baseline question. The verifier returned PASS for the unit-test Red
baseline (`ReportClarifyRulesTest`, `ReportClarifyServiceTest`).

The RC-02 real-session integration test (`ReportClarifyAuthenticatedIntegrationTest`)
is **BLOCKED**: it needs a disposable PostgreSQL (`MP4_AUTH_TEST_JDBC_URL`,
`MP4_AUTH_TEST_DB_USER`, `MP4_AUTH_TEST_DB_PASSWORD`). It was compile-checked only,
never run. It is not an approved passing Red. Task 2.1 stays open until it runs.

Verifier notes, accepted as non-blocking:
- The 65 KiB output case also carries an extra key, so it does not isolate the size
  limit.
- The token-usage-present telemetry path is untested.
- "No admission used" is shown only by zero provider calls.
- The assumed API names and the telemetry log keys come from the test designer.

## Approved baseline

- Revision: HEAD `1b2d3b7` (branch `llm-reporter`) plus the group 1 and group 3
  working tree.
- `src/backend/auth/src/test/java/mp4/platform/ReportClarifyRulesTest.java`
  `a62f53959e6613bd70e6a8f9e0eadd5516e945ee588503970bad588b45b64270`
- `src/backend/auth/src/test/java/mp4/platform/ReportClarifyServiceTest.java`
  `40b4487e76078d00df3ceba91b39a035106ba5ef71c5a35331e2a027638dd0ff`
- `src/backend/auth/src/test/java/mp4/auth/ReportClarifyAuthenticatedIntegrationTest.java`
  `ba130901c974d7473c128fc0f7abcc1b57aaa9b86d733f8467cedfcac55267c5` (BLOCKED, must compile)
- Red evidence: [red-group2.md](red-group2.md).
