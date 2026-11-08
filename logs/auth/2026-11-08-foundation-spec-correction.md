# Authentication foundation: source correction and specification review

Corrected the Maintainer plan after rechecking the original MP4 assignment text
and the project-plan PDF. The active section-2/page-2 table assigns authentication/
account provisioning and deployment/environment settings to the Maintainer owner.
Had incorrectly excluded these with the cancelled allocation. The entire workload
split on pages 17-19 is visibly crossed out; have not reinstated it.

Closed PR #5 to prioritise this foundation. Its verified priority UI remains on
`codex/maintainer-priority`; it is not merged or part of this specification branch.
Started `codex/auth-foundation-specs` from current main at
`d0b6f4813b834d2f525de360a29efd58f0f2ceba`.

Updated `complete-maintainer-role` proposal/design/tasks/migration/verification and
its service-integration delta. Own auth/provisioning with Reporter-owner review;
own deployment with Developer-owner review. Preserve Developer ownership of shared
database/schema/migrations/versioning and shared frontend/API handling, and Reporter
coordination of documentation/site/evidence and evaluation. Shared investigation
evidence remains distinct from newer private Developer notes. Restored explicit
experiment repetitions and scoring from section 12.

Proposed `add-shared-authentication` with account-provisioning and session-authentication
capabilities, a concrete endpoint/actor contract, library/session/CSRF design and
bounded dependency-ordered tasks. Include operator-only fixed-role accounts,
login/logout, current identity, expiry/revocation, shared login limits and real-role
integration. Record the existing JDK-handler versus servlet boundary explicitly;
have not claimed a framework choice or synthetic test establishes integration.

Checks passed: strict OpenSpec validation for all seven items and whitespace checks.
Independent specification review passed. Security review found a display-name unit
mismatch with the existing Maintainer parser; changed the proposed maximum to 200
UTF-16 code units and added boundary-fixture requirements. Security recheck passed.
No application code, approved priority tests, dependency lockfiles or existing
teammate contracts were edited. No auth behaviour tests, account provisioning,
deployment, team-owner agreement or implementation completion is claimed.

## Technical evidence

Run ID: AUTH-SPEC-2026-11-08. Source inspection used PDF text extraction plus visual
review of pages 2, 15, 17, 18 and 19. Local derivative workload notes are not source
ownership authority. Inspected existing Developer actor/JDBC/HTTP contracts,
shared report migration/README, Maintainer user parser and CI. Checked official
Spring documentation for the proposed library approach; links are in auth design.

Configured independent roles:

- `candidate_verifier`, gpt-6-luna/low, fresh context, five-minute scoped read-only
  planning review: PASS; independently ran strict validation for seven items.
- `security_reviewer`, gpt-6.1-sol/medium, fresh context, five-minute scoped read-only
  review: one P2 contract mismatch, then PASS after the targeted repair/recheck.

Supplied exact worktree, base revision, source ownership findings, changed artifact
paths, no-edit/no-dispatch scope and expected findings. Reviewers read the affected
planning and consumer contracts as needed. Token/cache measurements were unavailable.
No runtime or application test results are inferred from either planning review.

Commands from repository root:

- `git fetch origin`, `git switch main`, `git pull --ff-only origin main`: already
  current at the base above; created the separate foundation-spec branch.
- `gh pr close 5`: closed; `gh pr view 5 --json state,url` confirmed CLOSED.
- Installed OpenSpec CLI: `validate --all --strict --no-interactive`: 7 passed,
  0 failed; reran after the DTO repair.
- `git diff --check`: PASS.
- Java 21 is available; `mvn -version` was not available. A pinned wrapper/runner,
  disposable database fixture and passing setup control are explicit auth task 1.2;
  missing setup is not evidence of Red.

Remaining gates: owner-reviewed routing/migration boundary, pinned dependencies and
executable fixture setup, then bounded Red design and fresh baseline verification.
`workflow/tdd.md` still requires feature-owner approval of that concrete verified Red
before implementation. All new implementation tasks remain unchecked. No new PR,
push, production setting or credential change is included in this correction.
