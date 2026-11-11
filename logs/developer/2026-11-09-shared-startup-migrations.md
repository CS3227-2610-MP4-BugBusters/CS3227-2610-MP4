# Shared startup migrations

The application now installs the shared report and checklist tables through
tracked Flyway migrations on startup. Fresh and authentication-only databases
are supported; an untracked existing shared table causes startup to fail instead
of silently overwriting it. Restart preserves stored reports. The packaged
Developer HTTP journey passed after rebuilding the distribution.

The test designer demonstrated the missing migrations against the previous
candidate and a separate baseline verifier confirmed the Red. The test was
then corrected to avoid depending on a specific database error message, and
extended to cover an authentication-only database and data preservation.
The implementer used the existing canonical SQL files as ordered resources.
An independent verifier reran the focused PostgreSQL checks successfully, and
a security reviewer checked the preflight, startup and packaging changes.

- Starting HEAD: `0d6e494`, with existing uncommitted work.
- `src/backend/shared/test/run-shared-report-storage.sh`: passed focused
  migration and startup checks with disposable PostgreSQL and loopback access.
- `python3 src/backend/auth/test/run_setup.py`: Gradle check passed; this run
  preceded the final auth-only preflight adjustment, which the focused startup
  test subsequently exercised.
- Fresh frontend builds and packaged distribution followed by
  `python3 src/backend/platform/deployment/test/run-packaged-developer-journey.py`:
  passed with real HTTP sessions and disposable PostgreSQL.
- `openspec validate shared-report-storage --type change --strict` and
  `git diff --check`: passed.
- Startup test SHA-256:
  `7bf03014f8dbd6e92aa168a62f2fa9e65755658fc37a1b996c3201fc9dc66b16`.
- Specialist token counts were unavailable. Docker image build and deployed
  environments were not verified. Identity/proposal foreign keys and full
  cross-role integration remain open, so task 4 remains unchecked.

## Context and interpretation for reflection

The test designer, baseline verifier, implementer and security reviewer worked on separate migration responsibilities. Review exposed missing container inputs and auth-only database compatibility; the candidate and checks were updated.

A focused startup test covered fresh databases, auth-only databases, restart and incompatible shared state. The packaged local journey passed; Docker image execution and production upgrade behavior were not established.
