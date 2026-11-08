# Provisioning Red baseline

The first account-provisioning acceptance slice now has executable PostgreSQL-backed tests. The disposable database and runner setup control passed, while the expected create and validation behaviors failed because the baseline command returns `NOT_IMPLEMENTED` with exit 5. No account migration or provisioning logic was added. The fresh baseline verifier must confirm this Red evidence before implementation proceeds.

## Technical evidence

- Task: AUTH-01 provisioning task 2.1 and AUTH-02 password-hash/validation boundary from task 2.2.
- Published parent: `a17abc55543f7bde1c2135dcb1cb8681e14e366d`. Executed against the local fail-closed bootstrap and tests now consolidated into the reviewable baseline commit; the input manifest identifies the exact files. Independent verification must rerun that published baseline commit.
- Test file: `src/backend/auth/src/test/java/mp4/auth/ProvisioningAcceptanceTest.java`.
- Test SHA-256: `1840965ABB3802BC1A8649D07B911AA5D75D5CC91B4B42EE1806A807A4243D81`.
- Command: `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; python src/backend/auth/test/run_setup.py`.
- Result: Gradle compiled the acceptance test and ran 6 tests. `RunnerSetupTest` passed against the disposable PostgreSQL database with existing reports/checklist migrations and required libraries available. Five provisioning tests failed at expected process exit assertions: valid creates returned 5 rather than 0; invalid role returned 5 rather than 2. The executable emitted its fixed JSON `NOT_IMPLEMENTED` response; no `ClassNotFound`, setup, schema, or JDBC failure caused Red.
- Cases: create all three fixed roles with a generated UUID and explicit UUIDs, adaptive BCrypt cost-12 hash match, normalized username duplicate refusal, explicit UUID duplicate refusal, invalid role/password argument refusal without writes, and UTF-16 display-name plus 72/over-72 UTF-8 password boundaries.
- Environment: local JDK 21. The inherited `JAVA_HOME` was stale, so the command set `JAVA_HOME` for this process only. No generated password canary or database credential is recorded here.
- Open: independent baseline verification and feature-owner review/decision remain required. No implementation has started.

## Coordinator review before independent verification

Tightened the draft before freezing: explicit secret-versus-argument handling,
actual BCrypt match and cost checks, full duplicate-row preservation, generated
and explicit UUIDs, multibyte/UTF-16 boundaries, bounded subprocess capture and
cleanup, and safe assertions that do not print passwords or encoded hashes.
An early harness compilation error was fixed before Red; it is not counted as a
behaviour failure. The passing setup control and process exit 5 distinguish the
missing feature from missing classes, imports, schema setup or database access.

The companion normalized UTF-8/LF manifest freezes tests, runner/build, fixtures
and requirement/design inputs. No account creation implementation or migration
is included in the baseline. No source from an earlier MP was reused.
