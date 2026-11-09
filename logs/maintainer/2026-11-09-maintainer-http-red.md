# Maintainer authenticated HTTP Red baseline

## Coordinator execution after test-design handoff

Ran `python src/backend/auth/test/run_setup.py` from the repository root with
process-local Adoptium 21 JAVA_HOME and PostgreSQL 17 MP4_POSTGRES_BIN. The runner
created and removed its disposable database. Gradle compiled the suite and ran
95 tests: 85 passed, ten failed, no errors or skips. All ten failures belong to
`MaintainerAuthenticatedWorkflowIntegrationTest` and received HTTP 404 at the
missing Maintainer routes. Authentication, session, provisioning and Developer
checklist controls passed, as did all 21 corrected summary tests and 14 workflow
domain tests. This establishes observed route-level Red, not independent approval.
The summary security repair was not compiled in this run; its later checks are
separate evidence. Independent verification of the HTTP baseline remains pending.

Test SHA-256 at execution: `24bb4b6b284269a90707274a92d169c303d05429c6198d23acd641481cef08cc`.
Starting revision: `5355a23`, with the test-design and summary working-tree changes
recorded in their own logs. No real Maintainer HTTP implementation existed.

Before independent review, strengthened the malformed UTF-8 fixture to use an
otherwise valid comment request. The earlier priority request also contained an
unsupported field, which could produce 400 without testing strict decoding. The
new request would be accepted if malformed bytes were replaced silently. Current
test SHA-256: `50357b46f909828bfa6ed758b94b77db3ddd21c6306182b132e9cf8ff82191c4`.
Independent execution of this strengthened fixture remains pending.

## Independent bounded HTTP verification

The baseline verifier (gpt-6-luna/high) independently reviewed the fixtures and
ran the disposable runner. The first run reproduced all ten HTTP 404s but also
exposed the unrelated summary interruption timing assumption; withheld a full
gate PASS until that fixture was corrected and independently reviewed.

The second run completed 101 tests: 91 passed, ten failed, zero errors or skips.
Every failure was an expected missing Maintainer-route HTTP 404. Authentication,
session, provisioning, checklist, workflow and all 29 summary cases passed.
Verdict: PASS for these ten HTTP Red assertions, under the standing instruction
to proceed after verified Red. The runner retained hash
`e34d623398298bbed56e2747492f703bf0c301e515d49f15369ac1e1b19b706e`;
the HTTP test retained `50357b46f909828bfa6ed758b94b77db3ddd21c6306182b132e9cf8ff82191c4`.

This does not complete MT-07/MT-11 races or private-note omission: the latter
assertion lacks a private-note fixture. Those need separate real integration
coverage. No Maintainer route or storage implementation existed during this run.

## Original test-designer handoff before execution

This test suite is intended to establish real-session API coverage for Maintainer
reads, triage, resolution review, discussion and authorization. It adds ten
integration cases using the existing JDBC-backed authentication fixture, the
canonical report/checklist migrations, and the shared five-column proposal shape.
No test command has run yet: the main agent owns the disposable Gradle runner and
has not released it. There is no observed Red verdict. The suite and this record
remain a pending test-designer handoff; implementation must not treat this as a
verified baseline until the independent verifier reruns it.

## Technical evidence

- Task: MNT1.2, MNT2.3, MNT3.1–3.3
- Starting revision: `5355a23ce0a3c9bf9fbb700aafb3be0bc4ebe76b`
- Test designer: configured `test_designer`; actual model/reasoning trace unavailable
- Allowed test path: `src/backend/auth/src/test/java/mp4/auth/MaintainerAuthenticatedWorkflowIntegrationTest.java`
- Evidence path: `logs/maintainer/2026-11-09-maintainer-http-red.md`
- Spec/design: `openspec/changes/complete-maintainer-role/{tasks.md,design.md,verification.md}`
- Contracts: `workflow/maintainer/storage-adapter-contract.md`,
  `workflow/developer/checklist-storage-contract.md`
- Test command: `python src/backend/auth/test/run_setup.py` (not run; execution reserved
  by the main agent)
- Test SHA-256 after isolating the list assertion with the unique assignee filter:
  `24BB4B6B284269A90707274A92D169C303D05429C6198D23ACD641481CEF08CC`
- Log SHA-256 before recording the updated test checksum:
  `C74135332ABC144F2C01CBE3FEA6537ADB3AB1363FE82B531C5FE45054D79A27`
- Result: pending. Setup controls, route behavior, HTTP failures, hashes and candidate
  revision have not been measured.
- Open integration decision: `MaintainerService.ensureIncrementable` currently maps
  maximum-safe-version overflow to `INVALID_STATE`; the Developer checklist contract
  maps increment overflow to `VERSION_LIMIT`. The test currently expects
  `VERSION_LIMIT` following the main agent's requested shared semantics. Confirm the
  expected HTTP mapping before baseline approval if that assertion conflicts with the
  API adapter contract.
- Scope limit: this suite does not test lock races or checklist Share-versus-close
  commit order; those need separate bounded PostgreSQL fixtures and coordination.
