# Private-notes storage baseline

Prepared a disposable PostgreSQL migration test for the Developer private-notes table. The runner successfully created an isolated local database and applied the shared reports migration. The storage test then returned an expected missing-artifact Red because the Developer notes migration is not present. The migration-dependent constraints and SQL assertions are prepared but could not execute. The runner remains available for rerun after the migration is added.

This result checks only the database storage artifact. It does not establish service authorization, transaction behavior, report locking, or real authenticated API behavior. In particular, the note-only SQL assertions document that the selected storage operations leave report/proposal fields unchanged; they do not prove that an application transaction enforces that invariant.

## Technical evidence

- Role: test designer; task: Developer private-notes storage slice; starting revision: `79f4c9e51dd2395cca24a8d001db1d9aa9c805a0`.
- Files added: `src/backend/developer/test/private_notes_storage_migration_test.py`, `src/backend/developer/test/run-private-notes-storage.sh`.
- Attempt 1: `sh src/backend/developer/test/run-private-notes-storage.sh`; sandbox denied binding `127.0.0.1` (`PermissionError: [Errno 1] Operation not permitted`) before PostgreSQL setup. This was a setup block, not Red evidence.
- Attempt 2: same command with approved escalation for a loopback-only disposable PostgreSQL cluster; server started, shared reports migration applied, fresh-database controls passed, then test returned exit 1: `RED (missing migration artifact): src/backend/developer/migrations/create_developer_private_notes.sql`.
- No existing fixture-owned notes table or HTTP test was used; those fixtures create the table themselves and would hide the migration absence.
- SHA-256 test: `ee996de6fd48a7fc7957ee3035aa39aeb404ae168f2a0ce61a6e78fbf09f2316`.
- SHA-256 runner: `b1499dba662c84519974b69439efc9e7a5fcd802ef2ab0bb1ae98d57060a2b4d`.
- After migration exists, the test checks empty defaults, one-row-per-report uniqueness, report foreign-key enforcement, nonnegative revision, revision retention on clear, and preservation of report/proposal fields under note-only SQL. CAS and service transaction semantics are outside this storage-only test.
