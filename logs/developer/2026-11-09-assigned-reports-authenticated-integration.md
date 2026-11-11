# Assigned reports: authenticated integration evidence

The Developer list was exercised through the running local Spring application
with real login sessions and disposable PostgreSQL records. Both integration
tests passed. They cover current assignments, combined title and status search,
ID search, pagination, reassignment, anonymous and wrong-role denial, and the
minimal response fields. An independent review found no confirmed authorization
defect. Production schema setup and deployed integration remain unverified.

The test designer added `DeveloperAssignedReportsAuthenticatedIntegrationTest`.
An initial test run failed because its numeric assertion expected a different
JSON number type; that assertion was corrected. This was a test error, not a
behavioral Red. The existing application passed the corrected test without an
implementation change.

- Starting revision: `0d6e494`, with existing uncommitted work.
- Command: `python3 src/backend/auth/test/run_setup.py`; the local PostgreSQL
  runner required loopback access outside the sandbox. Gradle check succeeded.
- Test XML: `src/backend/auth/build/test-results/test/TEST-mp4.auth.DeveloperAssignedReportsAuthenticatedIntegrationTest.xml`;
  2 tests, 0 failures, 0 errors, 0 skipped.
- An independent candidate verifier inspected the test, runner and XML; an
  independent security reviewer traced session identity and assignment filtering.
  The verifier did not rerun the full suite. Per-agent token counts were not
  available.
- The runner applies report/checklist SQL manually to a disposable database.
  Automated fresh-deployment migrations, inactive-account list coverage, forged
  identity input and deployed integration remain open. Tasks 1–3 remain open.

## Context and interpretation for reflection

The integration designer exercised the local Spring application with real server-issued sessions and persisted assignments; candidate and security reviewers inspected the resulting evidence and authorization path.

The initial number-type mismatch was a test defect, not a behavioral Red. This run manually applied migrations; later automatic startup migration evidence is in `2026-11-09-shared-startup-migrations.md`.
