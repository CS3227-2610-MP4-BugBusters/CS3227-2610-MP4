# Private notes service: baseline preparation

## Human-readable summary

- **Goal:** Start the private-notes API using the approved local contract and a disposable database.
- **Prepared:** A reusable runner creates a temporary PostgreSQL cluster, loads the report/proposal fixture and notes migration, and starts the existing synthetic-session harness.
- **Result:** The setup and assigned-Developer session control passed, but the harness returned 404 because it does not mount a private-notes handler. No notes behavior was exercised. This is a setup blocker, not a verified behavioral Red.
- **Independent review:** A separate verifier reproduced the setup and 404 and confirmed the fixture and runner are isolated. The verifier also noted that the current test only checks one assigned synthetic actor and has no invalid-session control; access, reassignment, concurrency, state and boundary cases remain untested.
- **Still open:** A runnable application route/service seam and the required behavioral baseline; real shared sessions, account locking, Maintainer-owner alignment and API integration. No service implementation is claimed.

## Technical evidence

- Starting HEAD `79f4c9e51dd2395cca24a8d001db1d9aa9c805a0`, with pre-existing uncommitted contract, migration, tests and unrelated skill-file changes.
- Test designer `notes_service_test_designer` (GPT-6 Luna low) created `src/backend/developer/test/run-private-notes-baseline.sh` and adapted `private-notes-fixture.sql` so the notes migration creates the table. A sandboxed attempt was blocked at loopback; an escalated run of `src/backend/developer/test/run-private-notes-baseline.sh` started PostgreSQL, applied the migration and reached the 404 diagnosis. No application code was edited.
- Independent verifier `notes_service_baseline_auditor` (GPT-6 Luna low) reproduced the same run under disposable PostgreSQL and checked fixture state/session controls. It classified the result as setup-blocked and made no edits. The harness uses `start(null)` and supplies no notes handler.
- At verification: runner SHA-256 `76eeb24e86f8ceab3e2bc17ce156b59a53f931e5d0217f5cae27f6b94b3da338`; fixture `8ab45075a56d3e93c13d92401ccda330d278040fdb2e8638ead59fe7b95a5121`; test `ed0656ce90c1230bf90dc1c9f0be5f23ebe9557127348b503f443405c0b7f93a`; migration `dd72ea526ee23c94d3add95ea5ba00517656f531742ea93a2f6b8e07bbd1c80c`.
- Token usage, elapsed time and cost unavailable. No OpenSpec task marked complete.
