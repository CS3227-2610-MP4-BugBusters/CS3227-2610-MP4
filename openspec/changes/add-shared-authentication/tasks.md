# Tasks

Status: the isolated Gradle/PostgreSQL setup control has passed locally. Added a
CI setup job; PR #6 revision `a17abc5` passed all nine remote jobs, including auth
setup on Java 17 and 21. Added a fail-closed command entry for the provisioning
baseline; no account creation/login behaviour has passed implementation checks. Complete this foundation before further
fake-backed Maintainer UI increments. Keep boxes open until all stated checks pass;
Reporter-owner review remains distinct from agents.

For behaviour slices use `workflow/tdd.md`: test_designer, fresh baseline_verifier,
feature-owner approval, implementer, fresh candidate_verifier and separate
security_reviewer. Freeze test/fixture/runner hashes. Missing dependencies are
BLOCKED setup, not Red. Record exact revisions, commands, failures, repairs and
unavailable resource measurements under `logs/auth/`. Use at most two repair rounds
per bounded slice; scope specialists to one writer per file.

## 1. Integration contract and executable setup

- [ ] 1.1 Publish the proposed endpoint/actor contract and record the Developer owner's routing/migration adapter decision plus Reporter-owner auth review. Verify all existing actor/DTO/error contracts map without replacing feature authorization; resolve conflicting specs/contracts before affected code. Deliver the agreed adapter paths and request/response fixtures with no secret values, including display names at 200 UTF-16 units and an over-limit supplementary-character fixture rejected consistently by provisioning and the Maintainer DTO boundary.
- [ ] 1.2 Establish a pinned Java-17-compatible Spring/Gradle wrapper runner and disposable PostgreSQL fixture under the agreed application boundary. Verify dependency resolution, wrapper integrity, fresh fixture migration/cleanup and a passing runner/setup control. Document exact local/CI commands and prereqs; do not claim an absent app/import/database as Red. No auth behaviour is complete from scaffolding.

## 2. Accounts and controlled provisioning

- [ ] 2.1 Implement the auth account migration and operator-only create command through the agreed schema tooling. Verify AUTH-01 for all three persisted roles, UUID/username uniqueness, role constraints, duplicate refusal and no public signup/self-promotion. Record Red/Green with real disposable PostgreSQL; document the secret-input command and role-testing policy.
- [ ] 2.2 Implement adaptive password storage and operator disable/reset with generation revocation. Verify AUTH-02: hashed-only persistence, secret-safe output/arguments, unchanged UUID/role and old-session denial after account changes. Cover malformed input and 72-byte boundary without truncation; document operator recovery and avoid committed account passwords.

## 3. Sessions and browser security

- [ ] 3.1 Implement AUTH-03 login, AUTH-04 current identity and AUTH-05 logout/session lifecycle using the real security chain and JDBC sessions. Verify all roles, generic unknown/inactive/wrong-password failures, fixation/replay, idle/absolute expiry, fail-closed stores, safe errors and DTO compatibility; document endpoint examples and cookie rules.
- [ ] 3.2 Implement AUTH-06 CSRF issuance and unsafe-method checks. Verify login/logout/protected writes for missing, foreign and stale tokens, valid recovery after token refresh, no mutation on GET and no credentialed cross-origin access. Include production cookie flags, explicit loopback-only dev settings and ignored spoofed proxy/actor headers; document the frontend token sequence.
- [ ] 3.3 Implement the shared login admission budget. Verify AUTH-03 with a test clock, atomic concurrent admissions from two app instances, expiry/recovery, correct 429/Retry-After, bounded cleanup and fail-closed database errors. Document the application limits and distinguish them from SoC budgets.

## 4. Real consumers and handoff

- [ ] 4.1 Wire the trusted actor through the owner-agreed adapter into the existing Developer list service/store; integrate the Maintainer current-user consumer. Verify AUTH-07 using real provisioned accounts, login cookies, persisted reports, assignment isolation, role/active denials and forged-header resistance. Run existing Developer and Maintainer regression commands unchanged; synthetic-session results alone cannot complete this task. Publish the working integration instructions for role owners.
- [ ] 4.2 Work with the shared frontend owner to exercise three-role login/logout and routing in the browser. Verify valid login, wrong-role direct API access, CSRF refresh, expired/revoked-session UI and preserved role input on recoverable errors. Review sanitized evidence with the Reporter owner and update access/testing instructions before calling the foundation available to the team.

## Workflow follow-up

- Carry deployment/environment implementation through `complete-maintainer-role` task 6.2 with Developer-owner review; do not claim deployment from local auth tests.
- Coordinate account/report transaction locks before claiming deactivation-versus-domain-write atomicity.
- Archive only after all tasks, required independent checks and team review pass.
