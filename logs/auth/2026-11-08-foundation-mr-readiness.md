# Authentication foundation MR readiness — 8 November 2026

Prepared a reviewable foundation increment: corrected Maintainer ownership,
specified authentication/provisioning and the operator-command handoff, established
an isolated Gradle/PostgreSQL setup control, and added its CI job. Kept the previous
priority UI PR closed and its branch separate. Have not implemented authentication,
account creation or a production account migration.

Local setup and static checks passed, followed by independent candidate and
security reviews with no remaining findings. The candidate run executed one setup
test with zero skips/failures/errors and cleaned up its private PostgreSQL fixture.
This is a contract/setup MR, not a completed AUTH-01 feature or a verified Red for
provisioning. All OpenSpec implementation checkboxes remain open.

Need the Developer owner's shared Flyway discovery/history decision and confirmation
or replacement of the proposed V100 reservation. Reporter-owner auth review also
remains pending. The contract is concrete enough to review without asking another
owner to invent our command/API shapes. The first behaviour baseline still needs an
executable fail-closed command boundary and agreed migration setup. Do not count
missing application classes/configuration as feature Red.

## Technical evidence

Run ID: AUTH-FOUNDATION-MR-2026-11-08. Base:
`d0b6f4813b834d2f525de360a29efd58f0f2ceba`. Reviewed candidate:
`548471b63e61d9a00038a366eb52beeaabe75fc4` plus the recorded working-tree edits to
`.github/workflows/ci.yml`, auth tasks status and setup README. Production sources,
existing feature tests and teammate-owned SQL were not changed.

Added only the `auth-setup` CI job: Ubuntu 24.04, Java 17/21 matrix, PostgreSQL tools,
wrapper JAR checksum and the existing Python setup runner. Preserved existing jobs
and contents:read permissions. The job needs no repository secrets or external DB.
It prints its actual PostgreSQL version; local PostgreSQL 17 evidence does not prove
the runner-distribution package's result.

Checks:

- `python src/backend/auth/test/run_setup.py`, valid local Java 21 JAVA_HOME and
  PostgreSQL 17 binary directory: PASS. Gradle `:test` executed; XML reports one
  test, zero skips, failures or errors. Owned fixture directory removed.
- Installed OpenSpec CLI `validate --all --strict --no-interactive`: 7 PASS, 0 FAIL.
- `actionlint` 1.7.12 on `.github/workflows/ci.yml`: PASS. Shellcheck/pyflakes were
  unavailable and disabled; this is workflow syntax/expression checking, not a
  remote workflow execution. Downloaded the checker from its official release and
  verified its archive against the published SHA-256 before use.
- `git diff --check d0b6f48`: PASS in independent review.
- Final wrapper JAR SHA-256 and Git executable mode match the earlier setup evidence.

Configured fresh scoped reviewers:

- `candidate_verifier`, gpt-6-luna/low, five-minute read-only scope: reran setup,
  OpenSpec and actionlint; PASS for contract/setup readiness and evidence limits.
- `security_reviewer`, gpt-6.1-sol/medium, five-minute read-only scope: PASS after
  inspecting secret input/output, duplicate/error/commit rules, fixture ownership,
  cleanup, CI permissions and wrapper checks. No additional setup run; the candidate
  reviewer owned it. This is static review, not auth runtime-security verification.

Supplied exact worktree/base/candidate, allowed read-only scope, OpenSpec/contracts,
verification commands and limitations. Reviewers retrieved the affected artifacts,
runner/build/CI and prior evidence. Neither edited files or dispatched agents.
Token/cache measurements were unavailable.

Could not execute the Linux runner locally: the available WSL distribution lacks
Java/PostgreSQL tools and sudo requires a password. Did not change privilege or
claim that run passed. Java 17 runtime, Linux and remote CI results remain unverified
until the MR pipeline executes. Existing frontend/backend regressions were not
rerun: their code and jobs are unchanged. No deployment or vulnerability scan is
claimed. Private notes, temporary tools and pre-existing untracked files are excluded.
