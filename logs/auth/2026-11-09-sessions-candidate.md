# Login, sessions and account recovery

Implemented browser sign-in/sign-out with real provisioned PostgreSQL accounts,
server-side JDBC sessions, CSRF, current identity and shared login limits. Added
operator password reset and disablement, and connected trusted sessions to the
existing Developer assigned-report handler without editing its source.

The implementer run passed all 37 backend tests, including the original 24.
Independent frontend verification passed typecheck, 40 tests and production build;
Developer frontend regressions passed typecheck, two tests and build. Packaged the
application classes, runtime libraries and frontend successfully with Gradle.
Real Chrome checks passed for all three roles: sign-in, current identity,
Developer API role access/denial, sign-out/replay denial and mobile width.
No protected report reads occurred before sign-in or for the wrong role.

Independent review found storage-failure response errors, permissive malformed
input, cleanup rollback, stale credential/role decisions and a Refresh cancellation
hang. Fixed those with added regressions, keeping the approved baselines unchanged.
Also fixed auth-before-CSRF ordering, safe method errors and missing-service errors.
The browser check caught missing Maintainer APIs returning a false 401; they now
return a safe 404 while the session remains valid. The clean independent backend rerun and final security probes also passed.

Have not implemented Maintainer report services or real AI. Existing isolated
preview fixtures remain separate from normal real-auth mode. Have not deployed
Render or verified live connectivity, production or shared migration registration.
Required teammate reviews remain separate from agent checks.

## Technical evidence

- Candidate: `61cb7338a71e1e142ea6883f77ef117e9c23b1bd`.
- Approved backend Red input commit: `e5c4a1440e79cf6732ae763f59d18b4cc5880cc0`;
  independent evidence commit: `24ce947`. Initial 24 tests: 11 passed / 13 failed for
  missing behavior, with setup controls passing.
- Approved UI Red input commit: `0eadc2ecec0ef58d031459feb9286a13084069a1`;
  five new behavior tests failed while the existing 16 App tests and typecheck passed.
- Immutable inputs: 9 November session 10-file and signin-ui 9-file LF-normalized SHA256
  manifests. Root and independent reviewers confirmed zero mismatches.
- Models: test designer initially used gpt-6.1-sol/low as a disclosed fallback
  during configured gpt-6-luna unavailability; baseline verifiers used
  gpt-6-luna/low. Backend and UI implementers used gpt-6-luna/high; candidate
  verifier gpt-6-luna/low; security reviewer gpt-6.1-sol/medium.
- Initial candidate `b776d5c` passed 24 backend / 38 frontend checks but security review
  required repairs. Subsequent regressions are additional coverage, not changes to
  approved assertions, fixtures, runner/build settings or frozen spec/design.
- Implementer backend: `python src/backend/auth/test/run_setup.py`, repository
  root, JAVA_HOME Adoptium21.0.5.11, MP4_POSTGRES_BIN PostgreSQL17/bin; 37 tests,
  zero failures. Runner owns a new isolated database and cleans it afterward.
- Repair-run issues: a new test missed an IOException declaration; auth-first
  `/me` ordering returned400rather than the frozen expected401; a new role test
  probed a nonexistent handler instead of the actual Developer route. Corrected
  the new coverage/source as appropriate; approved tests stayed unchanged.
- Added tests cover session read/save outages, strict percent UTF-8/ASCII names,
  credential reset during password verification, fresh role/active state,
  authenticated request shapes, unsafe authentication ordering, safe 405/404,
  shared two-store concurrent 10/30 admissions, client 100/101 and cleanup on denial.
- Maintainer: `npm.cmd run verify`, 40 tests / 5 files plus typecheck/build.
- Developer: initial verify lacked installed tsc; `npm.cmd ci` restored locked
  dependencies, then `npm.cmd run verify` passed two tests/typecheck/build.
- Packaging: auth `gradlew.bat --no-daemon -I ../platform/deployment/package.gradle
  authDistribution -PbuildRevision=61cb733 -PfrontendDist=<local-built-dist>`;
  BUILD SUCCESSFUL in 12s. This is native packaging, not a Docker image run.
- Browser: headless installed Chrome through Playwright; Vite 127.0.0.1:4173
  proxied to direct-Java auth 127.0.0.1:4188 and a separate disposable PostgreSQL.
  Used generated temporary accounts/passwords; no credentials entered published
  logs. Each role had an isolated browser context at 390x844. Waited for logout
  completion before asserting `/me` 401; the sign-in form appears immediately but
  remains disabled while logout is pending.
- Browser limitations: Developer assigned-list HTTP acceptance includes persisted
  assignments in the backend suite; the browser fixture had an empty list.
  Maintainer services remain absent, so successful login shows unavailable service
  feedback, not real reports. Shared cross-role frontend routing is not complete.
- Security probes before final freeze passed anonymous protected POST 401,
  unsupported login GET 405 with Allow POST, invalid CSRF query/body 400, anonymous
  `/me?user=other` 401, exact safe JSON keys and no-store.
- Resource/token counters unavailable. No cross-agent concurrent Gradle runs.

## Independent recheck

- Clean verifier: `auth_clean_runner_verifier`, gpt-6-luna/low, read-only.
  Ran the same disposable PostgreSQL command on `61cb733`; captured session 32410
  through completion: exit 0, BUILD SUCCESSFUL in 1m45s, 37 tests across 7 suites,
  zero failures/errors/skips. No PostgreSQL process remained; overall exit 0 also
  confirms the runner's strict cleanup finally completed.
- Earlier verifier: `auth_candidate_recheck`, gpt-6-luna/low. Frontend 40 tests and
  all 19 manifest entries passed. Its original backend XML also showed 37 passes,
  but it lost process tracking and launched a duplicate before the first ended;
  duplicate failed on `output.bin` lock. Did not count that attempt as a clean
  runner PASS. Used the fresh clean verifier above afterward.
- Security: `auth_security_recheck`, gpt-6.1-sol/medium, read-only source review;
  independent local fixture probes allowed. PASS: anonymous protected POST 401,
  login GET 405 / Allow POST, auth shape denials, Maintainer missing reports 404 followed
  by valid /me 200, account-store and limiter-store outages 503, and restoration.
  Real operator reset revoked old session / old password; new password worked.
  Real operator disable caused existing session 403. Temporary credentials stayed
  private, and both renamed tables were restored in finally.
- Security main-source snapshot: 22 files (auth/platform Java plus App/auth-client),
  normalized LF path+NUL+bytes+NUL SHA256
  `91ecc88ac2dcc04b3c9316fb03dae4a0d9188ce2b42bfd22c55afa6f1f6953ef`.
- Remote source-candidate CI: run 42512619045, head 61cb733, all 10 jobs PASS:
  frontend Developer/Maintainer Node 22/24, assigned reports Java 17/21,
  auth Java 17/21, OpenSpec, and Docker image startup/frontend/revision smoke.
  Docker verification ran on GitHub Ubuntu; local Docker engine remained absent.
- Root browser fixture/server and Vite processes stopped; disposable database
  cleaned after browser and security probes. No generated credentials published.
- Remaining: required teammate reviews and complete integration handoffs, shared
  routing/screens and canonical migrations, live Render/SoC/production checks.

