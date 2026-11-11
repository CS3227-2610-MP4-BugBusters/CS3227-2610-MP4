# Checklist Save HTTP test design, cycle 1

- Run: `checklist_save_api_red`, 7 November 2026 SGT.
- Starting point: `6d1a78f` with the local JDBC Save and recorded HTTP contract.
- Context supplied: OpenSpec checklist criteria, storage and HTTP contract, TDD workflow, existing API scenarios, and Developer persistence notes. Test and evaluation paths were the only paths open for specialist edits.
- Test output: [Executable HTTP contract test](../../src/backend/developer/test/checklist_save_api_test.py) and [fixture and run instructions](../../workflow/evaluation/developer/checklist-save-api.md).
- Checks: The designer ran the test entry point and received a setup-blocked result because the application origin, session, fixture IDs, snapshot query, and database configuration were unavailable. Assertion checks accepted the expected state and rejected incorrect snapshots. The main agent independently checked the state assertions and strict OpenSpec validation.
- Verdict: **Red blocked.** No request reached a real authenticated application. Once the shared server and database fixtures exist, run the current-revision test before wiring Save. The stale-revision scenario is the second cycle.
- Token usage: Unavailable in the returned evidence.

## PostgreSQL fixture follow-up

Applied the report fixture, checklist migration, and [Save seed](../../src/backend/developer/test/checklist-save-fixture.sql) to disposable PostgreSQL 17. Ran the Python test's `snapshot` and `verify_fixture` functions with a [read-only query](../../src/backend/developer/test/checklist-save-snapshot.sql). They accepted the committed fixture and verified the report and persisted proposal. This clears the local database setup check. The shared application, authenticated session, and any schema adaptation are still needed for HTTP Red.


## Local HTTP Red and candidate test baseline

- Run: `save_http_harness_designer` with starting commit `6d1a78f` and local JDBC work. The orchestrator supplied the Save wire contract, checklist spec, TDD instructions, fixture, and test-only edit boundaries.
- The designer added a loopback JDK HTTP harness and run instructions. The orchestrator started disposable PostgreSQL 17, applied the fixture and migration, and compiled and started the harness with JDK 17 source compatibility.
- The anonymous session control returned 401. The session control with the synthetic Developer token returned 200 and the expected actor. The pre-request fixture snapshot passed.
- `python3 -B src/backend/developer/test/checklist_save_api_test.py` exited 1 with `AssertionError: Expected HTTP 200, got 404`. A read-only snapshot after the request matched the baseline. The local harness has no Save handler mounted yet.
- Verdict: **Local Red confirmed.** This run used a synthetic identity and a test database. It does not establish production session handling. No implementer was dispatched. The feature owner's baseline approval is pending.
- Per-agent token usage: unavailable in the returned evidence.

Candidate test and fixture SHA-256 values:

```text
fb3d3bfdcd3881739e6ef6716f76bb3182a369d9aa088943a3638b73628bcaf6  src/backend/developer/test/ChecklistSaveHttpHarness.java
13fe742188207c5365ce9e32c0c943724ff55a245845196a0fa569b308dc8782  src/backend/developer/test/checklist_save_api_test.py
658666a8e1b7d5ebc967738f90ba7bbb96aeb3a50d9d95a531cd99130d5d15ca  src/backend/developer/test/report-fixture.sql
7e90213986ca498366bfda09c4a22606b5335119e4aa603dbda2bb118cc84552  src/backend/developer/test/checklist-save-fixture.sql
8afc7566237ea3b1cedf1247ba7aca043edfab2d2b4f8dca3b6e89c4bbfac45c  src/backend/developer/test/checklist-save-snapshot.sql
dd6857037de91b54825126fa457a10303570ca89f0341b700498fb758506d2aa  src/backend/developer/migrations/create_investigation_checklists.sql
```


## Independent baseline verification

- Verifier: `checklist_red_baseline_verifier`, fresh context, 7 November 2026 SGT. Reviewed the OpenSpec change, Save contract, verification instructions, prior evidence, and the six test and fixture files recorded above.
- Revision: `6d1a78f590a2cc274db8a41a7d44fcf5fb142af5` with the local baseline files. Python SHA-256 checks matched all six entries.
- Reproduction: Started disposable PostgreSQL 17.11 on a Unix socket, applied the report fixture, migration and seed, and checked the initial snapshot using a read-only connection. The first database initialization attempt failed on locale setup. Retrying with `LC_ALL=C` and `--encoding=UTF8` succeeded.
- Ran the [documented local HTTP baseline](../../workflow/evaluation/developer/checklist-save-api.md#local-http-baseline) with temporary output capture and Python `-B`. JDK 17-compatible compilation passed. Anonymous GET returned 401, and the synthetic Developer session returned 200 with the expected identity.
- The Save test exited 1 with `AssertionError: Expected HTTP 200, got 404`. The harness had no Save handler mounted. Before and after snapshots matched byte for byte. The temporary database and harness were stopped and cleaned up.
- Verdict: **PASS for the local Red baseline.** The evidence describes synthetic authentication and a disposable database. Integrated authentication, the shared schema, and the stale-revision HTTP case are outside this result. No baseline files changed. Owner approval is pending.
- Runner baseline: SHA-256 of the exact fenced shell block under **Local HTTP baseline** is `8beded627361cf34eff1e3f3b19b21e680a87ec652596db6ab89d226852edaa7`. This records the runner and session controls in addition to the six file hashes.
- Per-agent token usage: unavailable in the returned evidence.

## Owner go-ahead

On 7 November 2026 SGT the Developer owner approved proceeding with the Save implementation and asked for MP4 reflection evidence. The orchestrator confirmed all six file hashes and the runner hash still matched the independently verified Red. The approved scope is current-revision private Save. The stale HTTP scenario and shared authentication integration are separate checks.

## Green implementation and handoff

- Run: `checklist_save_implementer`. The orchestrator requested `gpt-6-luna` with high reasoning effort and a fresh context. It supplied the Save contract, relevant OpenSpec paths, verified Red log, JDBC store, harness, and edit boundaries. The implementer consulted those files and the project instructions. Cached PostgreSQL and Jackson JAR locations were supplied to avoid a network fetch.
- Coordination: The first implementation turn stalled after drafting the adapter. The orchestrator interrupted it and narrowed the resumed task to code and compilation, then took responsibility for the disposable PostgreSQL run. An additional handoff fixed a malformed URL whose route prefix and suffix overlapped. No extra model was dispatched for that repair.
- Result: Added a Java HTTP adapter and a test-only launcher for the existing synthetic-session harness. The adapter validates and bounds the request body, then uses the existing JDBC store with the trusted actor. It preserves the approved test, fixtures, and runner block.
- Green verification: Java compilation with `--release 17` passed. The orchestrator started disposable PostgreSQL and ran `python3 -B src/backend/developer/test/checklist_save_api_test.py` against the mounted handler after both session controls passed. The frozen test passed, including preservation of the shared copy, report and proposal. The separate JDBC integration check passed initial Save, stale revision, competing saves, former-assignee and Reporter denial, and Closed report checks.
- Baseline: All six approved file hashes and the approved runner hash still match. Strict OpenSpec validation passed. The implementation is local and uncommitted.
- Resource evidence: The model and effort above are requested settings. Runtime model telemetry and per-agent token counts were unavailable. The resumed handoff and extra route correction are included as coordination overhead. No token savings are inferred.

## Independent reviews and repair

| Run | Requested setting | Context and result |
| --- | --- | --- |
| `checklist_save_candidate_verifier` | GPT-6 Luna, low | Received the Save contract, frozen baseline, three candidate file hashes, and focused runner. Read the handler, store, launcher, project guidance, and runner. All candidate and baseline hashes matched. Independently passed the frozen HTTP test, session controls, and JDBC regression suite. |
| `checklist_save_security_reviewer` | GPT-6.1 Sol, medium | Received the access and transaction rules, candidate hashes, handler/store paths, and focused probe. Confirmed anonymous 401, rejection of malformed or spoofed inputs, and former-assignee 403 with unchanged stored state. Found that an unpaired surrogate could advance the revision without preserving the submitted text and that NUL produced a 500. |
| Implementer repair and reviewer rechecks | Same requested settings as above | Routed the concrete failing inputs to the original implementer. It added validation before the store. The candidate verifier reran the approved HTTP and JDBC suite successfully. The security reviewer ran only the added [text regression](../../src/backend/developer/test/checklist_save_text_validation_test.py). Both invalid inputs returned 400 with identical snapshots. A valid supplementary character round-tripped exactly. |

The final handler SHA-256 is `c498b528f9fd91de6bbe4ddd7a34e63c6ae152c56526ea49d3646c2ddaa7cfb0`. The store and launcher remained unchanged from the first reviewed candidate. The approved Red files and runner remained unchanged throughout.

Reflection evidence: The independent candidate verifier confirmed the approved behavior. The separate security reviewer found a data integrity defect that the approved happy-path and JDBC tests did not cover. Its finding led to one security repair and a targeted recheck. The narrower recheck avoided repeating the unaffected security probes. Both roles used separate context packages. The orchestrator reused one disposable test recipe. Runtime token counts were unavailable, so this demonstrates the work performed and extra review effort without quantifying token savings.

Final verdict: **Green and independent local reviews passed.** Production session integration, shared routing and schema, the frozen stale-revision HTTP cycle, Share, and closure races remain open.

## Context and interpretation for reflection

The handoffs separated the local HTTP contract, implementation and independent security review. The reviewer discovered invalid text handling outside the initial happy-path checks, prompting a focused repair and recheck.

The recorded 404 was produced by an unmounted handler. Treat it as a local missing-route baseline, not proof of the eventual authenticated application behavior. The later real-session work is in `2026-11-09-checklist-auth-integration.md`.
