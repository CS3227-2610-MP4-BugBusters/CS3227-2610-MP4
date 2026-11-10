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

## Release-tooling and review follow-up

Fixed the deployment-hook P2 with one environment-scoped secret and no broad
secret inheritance. Added the shared verification command, pinned redacted secret/
dependency scans, runnable synthetic agent cases and controlled comparisons.
Reconciled stale verification status and contributed guide acknowledgements/results.

Ran `python workflow/verify.py` successfully: both frontend builds/typechecks and
15 Developer + 109 Maintainer tests, 119 backend tests, 13 deployment checks,
5 workflow checks, OpenSpec 7/7, actionlint and whitespace checks. Gitleaks reported
no tracked-tree leaks; OSV reported no known issues across 90 resolved Maven and
121/124 npm packages. Missing tools/network/corrupt downloads remain failures.

Fresh candidate verification passed the 26 selected auth/Maintainer PostgreSQL
integration tests, with unchanged c8e66d2 test sources and no failures/skips. An
initial malformed selection exited before tests; corrected repeated `--tests`
arguments succeeded. Fresh independent application security review found no scoped
blocker. Fresh release-tooling candidate/security reviews also passed; a removed
wrapper checksum gate found during review was restored before verification.

Used configured Luna/high test-design/baseline/implementation roles, Luna/low
candidate verification and gpt-6.1-sol/medium security review. Frozen baseline tests:
deploy scope SHA-256 BFC5B705203D2DA101E76713D1C02BBD1D07C700544B2A71B3C1BDDB67C049E8;
runner Git blob be3e898251a119a19590d1eb4e10b5f93d151574. Preserved both.
Tested source/docs staging tree before this follow-up: a34f9b5b5931e963e907000403cbe2b0b99ac1d7.

Kept live provider/budget, Render, complete cross-role release flow and shared-owner
acceptance pending. Recorded experiments and voluntary-refusal limits separately
under logs/experiments, logs/security and logs/agent-runs. No token/cost savings or
OS-enforced agent sandbox is claimed. Reviewed main 88f1eb4; already up to date.
