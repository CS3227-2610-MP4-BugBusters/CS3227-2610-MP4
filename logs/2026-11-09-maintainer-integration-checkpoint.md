# Maintainer integration checkpoint

Implemented real queue-summary routing/snapshots and session checks, retained proposal
history, account-disable transaction protection, deployment gates and guide
contributions. Fixed the revocation response from 500 to 401 while preserving the
Developer handler contracts. Kept summary generation unavailable without shared
provider/admission adapters; ordinary workflows remain usable.

Verified 119 backend tests (no failures/skips), 109 Maintainer frontend tests,
typecheck/build, 12 deployment checks, actionlint and all seven OpenSpec items.
Earlier packaged Chrome checks covered login, triage, discussion, close/reopen,
summary unavailability/empty queues and logout. These were local controlled checks,
not live SoC or Render evidence.

Fresh independent candidate/security review remains blocked by agent capacity/usage;
kept PR #10 draft. Have not provisioned Render, configured deploy hooks or measured
live provider cost/grounding. Required guide contributions still need coordinated
team review. No controlled single-versus-multi-agent comparison was performed.

## Technical evidence

- Task scope: complete-maintainer-role 0.2, 1.2, 2.3, 4, 6.2 and 6.3; broad tasks retain pending review/live checks.
- Base: 47da763; merged main: 88f1eb414bf51c8f939df068410e1a3c30ee7bde.
- Tested source/docs staging tree before this evidence file: c8fd667a9abc829389091931e658fc9ebe861675.
- Runner: local coordinator, Java 21 / PostgreSQL 17; fresh specialist review unavailable. Token measurements unavailable.
- Backend: `python src/backend/auth/test/run_setup.py` (119 passed, 3m19s).
- Frontend: `npm run verify` in src/frontend/maintainer (109 passed, typecheck/build passed).
- Deployment: `python -m unittest discover -s src/backend/platform/deployment/test -p 'test_*.py'` (12 passed); actionlint passed.
- Specs: `openspec validate --all --strict` (7 passed); staged diff whitespace check passed.
- Focused repair: RunnerSetupTest + DeveloperRevocationIntegrationTest passed after mapping swallowed revocation at the servlet boundary. Baseline test assertions were unchanged.
