# Shared schema defaults review fix

Fixed PR #10 review comment 4514220176: startup accepted a manual checklist schema
without its shared_steps default, which could break the first private Save. Added
read-only checks for the five required report/checklist defaults before baselining.
Reject incompatible schemas without shared history, V3 writes or row changes;
do not evaluate default expressions or silently repair the schema.

Added one bounded regression covering each missing default. A fresh verifier
reproduced Red on missing shared_steps while existing startup controls passed.
The implementer preserved that test; fresh candidate verification passed 10 tests,
including canonical startup/restart and authenticated private Save/Share behavior.
OpenSpec validation passed 7/7; whitespace checks passed. Canonical migrations,
V3 and proposal schema remain unchanged. The Developer owner's separate V3 overlap
will be reconciled with their resolution work after merge, as noted in review.

## Evidence

- Base: a1b127fc37fd7c0b56c4cfa00cfce7eff28e3e43; tested staging tree before this record: 392e7bb5fa905f9eb8132cde3740b633c1bf49b9.
- Contract: MNT-INTEGRATE-01 legacy-default scenario; workflow/developer/checklist-storage-contract.md; canonical report/checklist SQL.
- Frozen test SHA-256: E8B96DE50F0033BB98EBB080EA6DE31A781135C80AED70DB01C49381F77CA831 (unchanged through Green/review).
- Red: `python src/backend/auth/test/run_setup.py --tests mp4.auth.RunnerSetupTest --tests mp4.auth.SharedSchemaStartupIntegrationTest` (5 tests, intended 1 failure); independently reproduced.
- Candidate: same command plus `--tests mp4.auth.ChecklistAuthenticatedSaveIntegrationTest --tests mp4.auth.ChecklistAuthenticatedIntegrationTest` (10 passed, no failures/errors/skips).
- Roles: configured Luna/high test designer, fresh baseline verifier and implementer; fresh Luna/low candidate verifier. Windows, Java 21, PostgreSQL 17; token/cost telemetry unavailable.
- Limits: focused checks, not a local full-suite rerun. Exact catalog-form matching conservatively rejects unrecognized equivalent expressions. Registered-history and live deployment/provider checks remain separate.
