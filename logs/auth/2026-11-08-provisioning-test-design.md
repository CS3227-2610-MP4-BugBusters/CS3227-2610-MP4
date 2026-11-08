# Provisioning test design: setup passed, behavior baseline blocked

The goal is to prepare the first account-provisioning acceptance slice: fixed
role creation, duplicate rejection, and adaptive password hashing against real
PostgreSQL. No provisioning behavior was changed or tested. The pinned isolated
setup runner passed its disposable PostgreSQL and build control. A behavior
test cannot yet execute because this repository contains no production
provisioning command/service, account schema or registered auth migration, and
the shared Flyway/migration registration decision is pending. Missing
executable test/application setup must not be misclassified as Red; once that
setup exists, missing provisioning behavior is a legitimate Red result. No
review findings or fixes apply; the owner decisions below remain open.

## Technical evidence

- Role: `test_designer`; configured model `gpt-6-luna`, effort `low`.
- Task: `add-shared-authentication` task 2.1, with task 1.1/1.2 prerequisites.
- Starting revision: `02c84cbd6faaa979078c40ec3abbda6ef6028028`.
- Relevant contract: `openspec/changes/add-shared-authentication/specs/account-provisioning/spec.md` (AUTH-01 and AUTH-02).
- Design: proposed `app_accounts` with UUID, unique normalized username, encoded password, fixed constrained role, active and auth_generation; operator-only command, duplicate refusal without mutation, BCrypt cost 12 for new hashes. Migration must be additive and registered through the shared tooling after Developer-owner coordination.
- Runner and control: `src/backend/auth/test/run_setup.py`, `src/backend/auth/src/test/java/mp4/auth/RunnerSetupTest.java`.
- Exact setup command (PowerShell):
  `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot'; $env:MP4_POSTGRES_BIN='C:\Program Files\PostgreSQL\17\bin'; python src/backend/auth/test/run_setup.py`
- Setup result: exit code 0; disposable PostgreSQL initialized, existing reports/checklist migrations applied, Gradle `check` passed (`BUILD SUCCESSFUL`, 8s). This is only a fixture/build control; it does not establish auth migration registration, application startup, or provisioning behavior.
- Baseline verdict: **BLOCKED**, no behavior Red run attempted. The auth Gradle project has no application source and `build.gradle` declares Java with only test dependencies; the only test is the setup control. No account schema/migration exists under auth. Do not create a test that fails on missing production types or absent migration registration.
- Allowed test-only fixture not created: without an executable service/API contract there is no meaningful fixture shape to freeze. No passwords or secrets were introduced.

## Smallest viable next baseline

After the relevant owners settle the cross-owner integration decisions, bootstrap
the executable test boundary without implementing provisioning business logic:
a fail-closed placeholder entry point and the existing migration executor, wired
to the disposable PostgreSQL fixture. The normal command arguments and result
semantics can be selected within Maintainer ownership. Freeze fixtures containing
three role cases, a duplicate normalized username, and a password canary, with no
actual password literals. Acceptance assertions:

1. Each of REPORTER, DEVELOPER, MAINTAINER persists once with a stable UUID and
   exactly its selected role.
2. Repeating an existing normalized username refuses creation and leaves UUID,
   role, and stored hash unchanged.
3. Persisted credential is not plaintext and verifies against the supplied
   secret via the approved adaptive encoder; output/arguments/log capture do
   not contain the canary.
4. The role constraint rejects any other value; no public signup or role-change
   endpoint exists once an HTTP application boundary is available to inspect.

## Owner decisions before a behavior Red

- Developer owner: canonical Flyway registration point and additive auth
  migration location/sequence; current setup directly applies existing SQL and
  is explicitly not production migration registration.
- The feature owner's “do it” already authorizes preparing the requested AUTH-01
  creation/duplicate/hash slice. Do not ask for that scope approval again. AUTH-02
  reset/disable/revocation and HTTP endpoint verification remain later work.
  Implementation approval is a separate gate after an actual verified Red.

Once those inputs exist, test designer can add focused PostgreSQL acceptance
coverage and record an actual behavior-specific Red for fresh baseline review.

## Independent preflight verification

A fresh configured `baseline_verifier` (gpt-6-luna/low, read-only, five-minute
scope) independently reviewed the current contract, setup and this evidence at
`02c84cbd6faaa979078c40ec3abbda6ef6028028`. Reran the exact setup command: exit 0,
Gradle `check` passed and the single setup test executed. The owned PostgreSQL
fixture shut down and cleanup completed without error. No files were edited.

Verdict: BLOCKED for behaviour Red, not a passing/failing provisioning test.
Confirmed that an executable fail-closed command boundary and agreed auth migration
fixture/discovery are prerequisites; actual account creation need not exist before
Red. Once setup reaches that boundary, missing create/duplicate/hash behaviour can
legitimately fail the acceptance tests. No feature implementation approval was
requested from this setup result. Existing authorisation to prepare the slice
remains valid; there is no request to reapprove that scope.

The coordinator supplied the concrete operator command, secret-input/result/exit
contract and proposed migration reservation in auth design. The outstanding
cross-owner handoff is shared Flyway registration/location/history and sequence,
not a request for the feature owner to invent API or CLI conventions. No production
sources, accepted tests, existing runner, schema or remote repository changed.
Token/cache measurements were unavailable for both scoped specialists. Strict
OpenSpec validation passed seven items after the contract update.
