# Authentication runner preflight — 8 November 2026

Established an isolated auth setup runner before attempting behaviour Red. Followed
the feature owner's instruction to use the predecessor framework/server where MP4
was undecided: MP2 is JavaFX desktop; MP3 uses Spring Boot MVC/Security with embedded
Tomcat, PostgreSQL/Flyway and Gradle. Inspected their build/configuration and guides
only. Have not copied prior application source, schemas, tests or credentials.

Pinned Spring Boot's 4.0.9 dependency set and a freshly generated Gradle 9.1.0 wrapper.
Kept Java 17 compilation compatibility with MP4's current checks; local execution
used Java 21. Added one setup control and a disposable PostgreSQL 17 runner. It
applies the current shared reports and Developer checklist migrations unchanged,
checks connectivity/library availability, then stops and removes its own cluster.
No application entry point, accounts, login/session behaviour or auth migration
is implemented by this setup. Classpath presence does not prove a functioning server.

Setup checks passed before final environment/path hardening. Independent final
verification follows below. Strict OpenSpec validation passed seven items. The
routing/migration handoff is now concrete in auth design, but another owner's
acceptance has not been fabricated. Tasks 1.1 and 1.2 remain unchecked because their
full shared-boundary review/CI requirements are not established. No behaviour Red
or approval to implement against a tested auth baseline is claimed.

## Technical evidence

Run ID: AUTH-SETUP-2026-11-08. Starting specification commit:
`59cccd3` on `codex/auth-foundation-specs`, based on main `d0b6f48`.
Read auth proposal, both capability specs, design/tasks, existing Developer actor
and query contracts, migrations, AGENTS.md and workflow/tdd.md.

Configured `test_designer` used gpt-6-luna/low in a fresh scoped context. Allowed
only the setup JUnit control, Python fixture runner and runner README; no production
sources, dispatch or commits. Coordinator wrote the build/wrapper configuration,
contract JSON fixtures and framework-decision updates in separate files. Supplied
exact worktree/revision, allowed paths, dependency versions, prerequisites, evidence
expectations and an eight-minute initial budget; token/cache metrics unavailable.

Initially generated and exercised a Maven-only setup, then replaced all uncommitted
Maven project files after the feature owner directed alignment with MP3. Retained
no Maven files in the candidate. The superseded local bootstrap included a stale
JAVA_HOME failure and a PowerShell argument-quoting failure, corrected before setup
execution; neither is behaviour Red. Avoid claiming those steps as useful auth tests.

Gradle setup checks before final runner hardening:

- `python src/backend/auth/test/run_setup.py` with a valid Java 21 JAVA_HOME and
  PostgreSQL 17 binary directory: PASS, one JUnit test, zero failures/errors/skips.
- Control checks SELECT 1, presence of reports and investigation_checklists, and
  Spring Security, Spring Session JDBC, Flyway and Tomcat class availability.
- Designer checked no owned `mp4-auth-setup-*` temporary directory remained.
- Coordinator disabled Gradle test result reuse because every fixture has a new
  database, then reran the runner: PASS with `:test` executed rather than reused.
- Hardened password-file permissions/removal, private server log/socket location,
  partial-start shutdown and refusal to delete a running cluster. Scrubbed inherited
  PG settings before psql and quoted Unix-socket paths. These final edits await the
  independent run below; no Linux execution is claimed.

Official integrity checks:

- Gradle 9.1.0 distribution SHA-256:
  `a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806`.
- Generated wrapper JAR SHA-256:
  `76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3`.
- Compared both with official `services.gradle.org` checksum endpoints; distribution
  hash is pinned in wrapper properties. Retained third-party wrapper license headers.

Dependency choices reference official Spring Boot 4.0 system requirements and servlet
documentation, linked in design.md. Current fixture SQL is executed directly; Flyway
registration/history, production deployment, cross-role real sessions and behaviour
security checks remain separate work. The runner refuses missing tools/settings
instead of skipping. No shared database, existing service, production configuration,
application source or teammate-owned migration was changed.

## Independent final setup verdict

A fresh configured `candidate_verifier` (gpt-6-luna/low, read-only, five-minute scope)
verified the exact candidate `17a6b4bd2a65ca605b4c3aa6c288acd6f3eee633`.
Ran the prescribed Python runner with local Java 21 and PostgreSQL 17: PASS.
Confirmed `:test` executed and XML recorded one test, zero skips/failures/errors;
no owned fixture directory remained. Checked wrapper integrity values against the
recorded candidate, executable Git mode 100755, setup safety and strict OpenSpec
validation (7/7 PASS). The coordinator's official-checksum comparison is recorded
above; the verifier did not repeat the external downloads. No files were edited.

Task 1.2 is a task ID inside `add-shared-authentication`, not a separate change path.
This PASS is limited to the isolated runner and fixtures. Have not established
canonical migration registration, real application hosting or auth behaviour, and
have not checked either integration/setup task complete. Framework/server selection
is settled by the feature owner's instruction; the owner handoff still concerns
concrete cross-module routing and migration registration, not selecting a new stack.
