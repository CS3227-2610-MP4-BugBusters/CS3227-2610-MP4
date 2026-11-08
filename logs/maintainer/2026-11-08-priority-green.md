# Priority controls: candidate verification — 8 November 2026

Implemented the isolated priority UI for task 2.1 in `complete-maintainer-role`.
Can select LOW, MEDIUM, HIGH or CRITICAL and explicitly save against the displayed
report version. Successful saves refresh the queue and detail; conflicts preserve
the attempted selection. Pending saves cannot be submitted twice.

Kept writes behind an optional adapter capability. Only the explicit local
development mode supplies in-memory updates and history; production still uses
the read-only HTTP adapter. Have not implemented real priority endpoints,
authentication/CSRF, PostgreSQL persistence or AI. Task 2.1 remains unchecked because
its full API/database acceptance checks have not passed.

Passed the approved UI baseline, regressions, typecheck and production build.
Independent candidate and security reviews passed the initial candidate. During
coordination review, found that an extracted adapter method lost its receiver;
requested a separate regression and repair without changing the approved tests.
Final repair evidence follows below.

## Browser checks

Exercised the explicit development mode in headless Chrome: changed the first
report from LOW/version 0 to HIGH/version 1 and CRITICAL/version 2. Draft selection
left stored metadata unchanged before Save. Each save updated the queue and added
exactly one history event. Desktop (1440 × 1000) and mobile (390 × 844) had no page
errors or horizontal overflow; inspected both screenshots locally.

Ran a production-build sign-in denial smoke check with an intercepted HTTP 401.
Observed only GET `/api/auth/me`, the sign-in message, no protected report requests
and no priority editor. This controlled response is not real backend evidence.
Screenshots and temporary browser harnesses remain outside the repository.

## Technical evidence

Run ID: MNT-2.1-GREEN-2026-11-08.
Base: `d0b6f4813b834d2f525de360a29efd58f0f2ceba`.
Approved Red commit: `e70b5c95c1ecc0256a6503990a27698068314718`.
Initial candidate: `e17aa5fcae58b752418ef2e6ac4360a9bf894e1f`.

Used the task, triage delta, design, MT-04/MT-13, main read-only specification,
TDD runbook and approved test/fixture/runner manifest as inputs. Configured
`implementer` used gpt-6-luna/high; fresh `candidate_verifier` used gpt-6-luna/low;
fresh `security_reviewer` used gpt-6.1-sol/medium. Scoped specialists to the task
and prohibited further dispatch. Token/cache usage and root model measurements
were unavailable.

Initial candidate checks:

- Implementer and independent candidate verifier: `npm.cmd run verify` from
  `src/frontend/maintainer` passed typecheck, 41 tests in five files and Vite build.
- Candidate verifier: all 12 normalized approved-input hashes matched; the
  approved baseline assertions executed unchanged. Production wiring remained
  HTTP-only and fixture markers were absent from its bundle.
- Security reviewer: focused access/demo suites passed 6/6. Supplemental in-memory
  concurrency check produced one success and one stale-version rejection for two
  same-version writes, with one version/history increment. Returned nested objects
  could not mutate adapter state or fixtures. No actionable scoped findings.
- Coordinator: OpenSpec strict validation passed all six items using the installed
  `C:\Users\ngkok\AppData\Roaming\npm\openspec.cmd validate --all --strict --no-interactive`.
  The short `openspec` command was absent from this shell PATH; used the existing
  installed command without installing or changing project dependencies.

Have not counted controlled in-memory races as PostgreSQL transaction tests.
Real authenticated writes, CSRF, rollback/deactivation races and deployed cross-role
flows remain unexecuted. Assignment, resolution review and queue AI are future work.

## Repair cycle 1

Candidate: `1fa203da68d9f8c566d4bc45bd0c4c8f2a275d0e`.
Added `priority.receiver.test.tsx` with a receiver-dependent class adapter. The
implementer reproduced failure with the unbound call, then preserved the receiver
with `update.call(queries, ...)`. The new test passed after the fix. This additional
regression does not replace or relax the approved baseline.

Implementer checks at the repaired candidate: `npm.cmd run verify` passed typecheck,
42 tests across six files and the production build; `git diff --check` passed;
all 12 normalized approved-input hashes remained unchanged.

Independent final rechecks at `1fa203da68d9f8c566d4bc45bd0c4c8f2a275d0e`:

- Candidate verifier: PASS; exact frozen HEAD matched, `npm.cmd run verify` passed
  typecheck, 42 tests in six files and production build. All 12 approved-input
  hashes matched. Reviewed the new receiver regression as meaningful and confirmed
  the repair did not change approved tests, fixtures or runner settings.
- Security reviewer: PASS; focused access/receiver checks passed 3/3 and the bound
  call retained existing access, cancellation and session guards. No new findings.
- Neither reviewer edited files. Retained the same scoped specialist settings for
  this repair recheck. Their initial independent contexts and findings remain
  recorded above.

Prepared the branch for an MR. Have not pushed or opened it in this increment.
Kept private planning/preferences and unrelated pre-existing untracked files out
of the commits. No OpenSpec task was marked complete or archived prematurely.
