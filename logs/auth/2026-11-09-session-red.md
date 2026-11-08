# Session authentication Red baseline — 2026-11-09

The goal is real operator disable/password-reset plus browser-session authentication
for all three roles, with shared
PostgreSQL sessions and attempt budgets, CSRF, expiry/revocation and the existing
Developer assigned-report boundary. Added fourteen acceptance tests using real embedded
Tomcat, Spring Security, HTTP cookie jars, PostgreSQL and generated BCrypt-12 account
fixtures. Application code, old tests, runner and shared migrations were not changed
by the test designer.

The disposable runner completed compilation and executed twenty-four tests: eleven
passed and thirteen failed. The existing ten setup/provisioning tests and the new
real-HTTP/persisted-account setup control passed. Each of the ten HTTP behavior tests
failed because GET /api/auth/csrf returned the baseline's intentional 403 instead
of the required 200. This is missing behavior Red, not an import/database/server
setup failure. Assertions after CSRF bootstrap have not yet executed; this run
proves neither their Green result nor any completed session functionality. The
three additional lifecycle cases passed real create-command fixture setup and
failed because the existing CLI rejects disable/reset at exit 2: success expected
0 for disable/reset and missing-account expected 6. These are missing command
behavior failures, not broken fixture creation or subprocess setup.

No independent baseline review has yet occurred. Coordinator review requested five targeted test repairs before freeze: secret
properties instead of application arguments; auth-owned session table; safe JSON
parse failures; successful second-node session reuse; and production cookie API
attributes. All five were applied and the exact runner was rerun once. No
application implementation was made by this designer. Remaining coverage outside this bounded suite includes
concurrent atomic admissions, the per-address 100-attempt budget, HMAC retention
and cleanup, store-outage injection, browser HTTPS transport, all method
contracts, protected writes, browser UI, and domain-write/deactivation races.
Tasks 2.2, 3.1–3.3 and 4.1 remain incomplete. Reporter-owner review and cross-owner
handoff are separate from this agent's result.

## Technical evidence

- Run ID: auth-session-red-2026-11-09-fallback.
- Role: test_designer, available-model fallback GPT-6.1-sol, low reasoning;
  configured GPT-6-luna hit a usage limit before writing these tests. Coordinator
  reported substitution before this designer was dispatched.
- Starting revision: 3537c4cdaa4cc3452349e701058f658d03bbafed,
  branch codex/auth-foundation-specs, plus coordinator-owned uncommitted
  fail-closed platform bootstrap/build wiring. This is not a clean-commit run.
- Allowed edits: SessionAuthenticationTest.java and this evidence log initially;
  coordinator additionally authorized AccountLifecycleTest.java after the HTTP
  test freeze. The HTTP test remained unchanged during that final addition.
- Context: AGENTS.md, workflow/tdd.md, .codex/agents/test_designer.toml;
  add-shared-authentication design/tasks/session-authentication spec; auth build,
  account initializer/migration, runner and Developer list handler/report schema.
- Fixture: isolated disposable PostgreSQL 17 database, existing reports/checklist
  migrations, auth-owned V100 migration through ProvisioningDatabase.initialize;
  generated UUIDs/passwords, delegating-format BCrypt cost 12, fresh real app and
  cookie jars. No runtime authentication/session/CSRF reference implementation.
- App test inputs: loopback ephemeral port; mp4.auth.local=true;
  mp4.auth.jdbc-url/db-user/db-password from disposable runner environment;
  mp4.auth.rate-limit-secret generated once and shared with a second app instance;
  @Primary mutable Clock bean shared across test apps. Secrets are supplied with SpringApplicationBuilder.properties(Map), never
  application arguments or logs. No secret values recorded.
- Exact command, PowerShell from C:\Users\ngkok\Documents\ChatGPT\mp4:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot'
$env:MP4_POSTGRES_BIN='C:\Program Files\PostgreSQL\17\bin'
python src/backend/auth/test/run_setup.py *> $env:TEMP\mp4-session-red-run.txt
```

- Runner's child command: src/backend/auth/gradlew.bat --no-daemon check.
- Outcome: runner exit 1; Gradle BUILD FAILED in 46s; 24 tests, 13 failures,
  0 errors. SessionAuthenticationTest: 11 tests, 10 failures, 1 pass, 0 skipped;
  ProvisioningAcceptanceTest: 5/5; ProvisioningValidationTest: 4/4;
  RunnerSetupTest: 1/1; AccountLifecycleTest: 3 tests, 3 failures, 0 errors. Runner's cleanup finally block completed without error.
- Representative observed assertion: `CSRF bootstrap HTTP status ==> expected:
  <200> but was: <403>` at SessionAuthenticationTest.java:111 (status helper),
  called from Browser.csrf. Nine behavior cases report this same cause; the added production cookie case
  reports `production CSRF bootstrap HTTP status ==> expected: <200> but was:
  <403>`.
- Generated local evidence: src/backend/auth/build/test-results/test/TEST-mp4.auth.SessionAuthenticationTest.xml
  and TEST-mp4.auth.AccountLifecycleTest.xml in the same directory, plus
  src/backend/auth/build/reports/tests/test/index.html; temporary sanitized
  console transcript at $env:TEMP\mp4-session-red-run.txt. These build/temp files
  are rerunnable local evidence, not committed artifacts.
- Token and resource measurements: unavailable.

## Frozen input SHA-256

| Input | SHA-256 |
| --- | --- |
| src/backend/auth/src/test/java/mp4/auth/AccountLifecycleTest.java | ABBE6CCAA73C5F97C1DAE5972A0BF53C2C8356949811F9E83DFB08E3FA1FEA6B |
| openspec/changes/add-shared-authentication/design.md | EAA56A19603257EDC3FD09A4C24968D8AB2B17D14F13ADAA8387E727887359C2 |
| src/backend/auth/src/test/java/mp4/auth/SessionAuthenticationTest.java | 9ED7EE47218B1CDD199F8E5B70F822D6E1A097E5593B7CB24ABC15EC912A9534 |
| src/backend/auth/test/run_setup.py | E34D623398298BBED56E2747492F703BF0C301E515D49F15369AC1E1B19B706E |
| src/backend/auth/build.gradle | FE3EC1851F650B4C96086665FFC0B13568F51E1291395ED71F140F90502D460B |
| src/backend/platform/src/main/java/mp4/platform/AuthApplication.java | 8B557AB2291C9E915F9F5B2085A9B2080128DE8030F9E6A7B9F85961604CCA5D |
| src/backend/platform/src/main/java/mp4/platform/ClosedSecurityConfiguration.java | 20909F15B2A718633CB96FF9CA16888E018EC3EA673E11C741A42DF8314669D9 |
| src/backend/platform/src/main/java/mp4/platform/HealthController.java | 65A3EFE44053449F202E3F54F309D5EC0098A6DB5465ED095ED846E85B4A16EB |
| src/backend/shared/migrations/001_create_reports.sql | 73F86BD63DC61D545E5902CB67543758C1046876F9DF298E9633932CCF773E08 |
| src/backend/developer/migrations/create_investigation_checklists.sql | 3302B1D0CC6E397316C96847E29E5DAE6C0503A29052CF5B8AA16FA8C9C5AA6D |
| src/backend/auth/src/main/resources/db/auth/V100__create_auth_accounts.sql | 67F40021F1D9A3D9FC85D748A07457FE81F50DC43982F6954176F140B47C22C8 |

The suite's eleven cases cover setup; three-role safe identities and ID fixation;
generic wrong/unknown/inactive failures; missing/foreign/cross-origin CSRF;
login token rotation/logout replay; auth-owned JDBC persistence/cross-node reuse/idle/absolute expiry;
account generation and inactive refresh; strict/bounded login payloads;
cross-instance account attempt window; and real Developer assignment/role and
forged-actor isolation; and production-cookie Secure/HttpOnly/SameSite=Lax/
host-only attributes through the API. This cookie test makes no browser TLS
claim. Multi-assertion cases stop at their first failing check.

## Final lifecycle addition and combined freeze

After the HTTP repair run, the coordinator confirmed the authorized user scope
includes real operator disable/reset. Read the appended web/recovery design
contract, added exactly three lifecycle cases, and reran the same combined runner
once. Existing HTTP/old tests, runner, build and app stayed unchanged. This final
combined result supersedes the earlier 20-test and 21-test intermediate runs.

Lifecycle controls create each fixture with the real existing CLI and generated
password input over stdin. Disable asserts persisted inactivity, generation
increment (including repeated disable) and preserved identity/hash. Reset asserts
new BCrypt-12 hash, old-password denial, generation increment and preserved
active/inactive state. The third case asserts unknown-account exit 6 and invalid
argument exit 2 without row mutation. Capture is bounded to 8 KiB, waits are bounded
and processes are forcibly cleaned up if needed. Sensitive snapshot comparisons
use boolean assertions. The initial CLI rejection stops later lifecycle assertions
from executing; these remain planned Green checks, not demonstrated outcomes.

Observed lifecycle failures: `lifecycle command must succeed ==> expected: <0>
but was: <2>` at AccountLifecycleTest.java:81 for reset/disable; `missing account
status ==> expected: <6> but was: <2>` at line 58. No test was skipped. No independent
review or owner approval is claimed by this designer. Freeze both test file hashes
above for independent baseline review.


Consolidated the tested bootstrap, build wiring and tests into the baseline commit
containing this log. Independent verification must rerun that exact commit. The
companion normalized UTF-8/LF manifest freezes this increment's ten input files.
Earlier provisioning manifests describe their historical increments: this new build
adds platform sources and existing Developer classes, while all prior provisioning
test assertions and the disposable runner remain unchanged.
