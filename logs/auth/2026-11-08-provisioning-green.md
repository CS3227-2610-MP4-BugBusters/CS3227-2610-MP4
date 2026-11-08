# Operator provisioning: Green and independent verification — 8 November 2026

Implemented the bounded account-creation slice against real PostgreSQL. Can create
REPORTER, DEVELOPER and MAINTAINER accounts with generated or explicit UUIDs, validate
operator input, store BCrypt cost-12 hashes, and reject duplicates without replacing
an account. Read password bytes only from stdin and return fixed safe JSON results.
Use an auth-owned V100 migration and private history table in public; existing
reports/checklist SQL and shared application handlers remain unchanged.

Passed the frozen five-case provisioning baseline, four additional validation tests
and the setup control. Independent candidate/security reviews passed. The actual
Gradle operator task also passed, including a spaced display name, exact stdin,
one safe JSON result and persisted data. A report sentinel survived the first auth
initialization and account operations unchanged.

Have not implemented login, sessions/CSRF, reset/disable, a provisioning HTTP route,
shared app startup or deployment. The complete AUTH-01/AUTH-02 requirements and broad
tasks 2.1/2.2 remain open beyond this slice. Marked setup task 1.2 complete using local
fixture/integrity evidence plus the foundation's Java 17/21 CI runs. Keep cross-role
integration and human owner review distinct from agent PASS.

## Technical evidence

Run ID: AUTH-PROVISION-GREEN-2026-11-08.
Base: `a17abc55543f7bde1c2135dcb1cb8681e14e366d`.
Frozen Red: `e88dfe72d0866602d713a03f7ce5d2963a8ae945`.
Candidate: `d1b7822cf23a738a7cd4f906dc078ac1327de09b`.
The red log and nine-entry normalized input manifest identify approved tests,
fixtures, runner/build and spec/design inputs; all remain unchanged.

Configured roles, each with scoped paths and no further dispatch:

- Fresh `test_designer`, gpt-6-luna/low: executable tests from the contract.
- Fresh `baseline_verifier`, gpt-6-luna/low: independently reproduced one setup pass
  and five feature failures at the fail-closed exit 5, all nine input hashes matched.
- `implementer`, gpt-6-luna/high, 12-minute initial scope: implementation and added
  validation regressions within auth only.
- Fresh `candidate_verifier`, gpt-6-luna/low, five-minute initial review plus a focused
  recheck: PASS on exact candidate, all ten tests and nine frozen hashes.
- Fresh `security_reviewer`, gpt-6.1-sol/medium, seven-minute scope: PASS; executed
  the supplemental checks below, no repository edits.

Supplied task/acceptance IDs, exact worktree/revisions, specs/contracts, fixtures,
allowed edits, expected checks, settings and repair limits. Specialists retrieved
only relevant source/configuration to resolve gaps. Token/cache measurements were
unavailable; no cost-saving or token-efficiency result is claimed.

### Checks and results

- `python src/backend/auth/test/run_setup.py`, local Java 21 and PostgreSQL 17:
  implementer PASS and independent candidate PASS. XML: acceptance 5/5, added
  validation 4/4, setup 1/1, zero failures/errors/skips; test execution was not reused.
- Candidate: exact frozen HEAD confirmed; all nine normalized LF manifest hashes
  match; `git diff --check` PASS; strict OpenSpec validation 7/7 PASS.
- Coordinator operator smoke: temporary harness reused the owned disposable runner,
  seeded a report before auth initialization, and exercised `gradlew --quiet
  --no-daemon provisionAccount` with generated stdin and a spaced display name.
  Verified exactly one safe JSON line, committed account and unchanged report
  title/description/status/version. Owned database and scratch were cleaned up.
- Security reviewer: ten temporary Java parser/raw-byte checks and seven actual CLI
  calls using unreachable loopback database configuration. Covered malformed UTF-8,
  11/73-byte inputs, retained newline, repeated/unknown flags, malformed UUID,
  password arguments, URL credentials and safe storage failure. All expected exits
  passed; stdout was one JSON result, stderr empty, generated canaries absent.
- Existing PR #6 foundation revision `a17abc5`: all nine remote checks passed,
  including auth setup on Linux/Java 17 and 21. Updated source-head CI is separate;
  use the PR checks for its authoritative revision/result after push.

### Findings, fixes and review limitations

Fixed pre-handoff implementation issues: Flyway 11.14 treats `loggers("none")` as
an unavailable class, so supplied its configured silent LogCreator for this CLI
process; completed that API's `isDebugEnabled` method after a compile check. No
shared logging resource was added. Tightened ASCII-only username normalization,
canonical UUID validation, URI parsing, public schema qualification and operator
instructions that send exact bytes rather than a newline-adding shell pipeline.
The added validation tests exercise these corrections; approved tests were intact.

The first candidate review had a Windows result-file lock because the coordinator
ran a smoke build concurrently in the same Gradle output directory. Reran the
independent check sequentially; it passed. The reviewer also initially reported
hash mismatches from incorrect PowerShell/Python escape handling. Correct byte
normalization showed all nine matches; no approved-input repair was needed. These
were verification/orchestration errors, not unexplained application failures.

No independent PostgreSQL concurrency/commit-failure injection run is claimed.
Duplicate safety has serial database acceptance plus reviewed uniqueness and
transaction paths. Credential verification here is BCrypt matching, not a live
login/session. No production or shared development database was used. Private
preferences/notes, temporary harnesses and unrelated untracked files are excluded.
