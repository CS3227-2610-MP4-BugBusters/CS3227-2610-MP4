# Private notes: local API implementation and review

## Human-readable summary

- **Goal:** Allow the currently assigned Developer to read and save private investigation notes through a local API while preserving a pending resolution proposal.
- **Implemented:** A JDBC-backed service and HTTP adapter provide empty initial notes, save and clear, independent note revisions and stale-write rejection. The local handler validates request input. A disposable PostgreSQL runner starts a synthetic-session server for testing.
- **Checks:** The final local API runner passed default read, save, clear, stale-write and proposal-preservation checks, plus malformed UTF-8 and UTF-16/32 input rejection. An independent candidate verifier also ran the API and storage runners successfully. Strict OpenSpec validation passed; that validates planning artifacts, not application behavior.
- **Review and fixes:** An independent security reviewer found that the JSON parser could accept invalid UTF-8 and alternate encodings. The implementation was changed to decode UTF-8 strictly, and a separate regression test was added; the reviewer reran raw-byte probes and the local suite. Additional reviewer probes covered synthetic unauthorized actors, assignment changes, closed reports, text boundaries and an assignment lock wait. These probes are not all part of the checked-in automated suite.
- **Important limitations:** Sessions and actors are synthetic. Real authentication, CSRF, account eligibility and revocation locking, shared routing, report-detail privacy, shared schema integration and lifecycle races need integration and verification. Maintainer-owner alignment remains open. No OpenSpec tasks are marked complete, and this is not a production-ready API.
- **TDD evidence limitation:** The user approved a minimal route scaffold after the initial harness could only return 404. An independent verifier then observed an expected 200 fail with scaffold response 501. During implementation, the test designer fixed a case-sensitive HTTP header assertion because header names are case-insensitive; an independent reviewer checked the correction. The 501 baseline was recorded before that test change and was not repeated against the revised test. It should not be presented as a verified Red for the revised test bytes.
- **Resource evidence:** Agents had scoped roles and separate review contexts, but per-agent token counts, cost and elapsed time were not available. No numerical resource-saving claim can be made from this run.

## Technical evidence

- Starting revision: `79f4c9e51dd2395cca24a8d001db1d9aa9c805a0`; work was performed in the dirty working tree, so this revision alone does not identify the candidate.
- Test runner: `sh src/backend/developer/test/run-private-notes-baseline.sh`, with PostgreSQL JDBC and Jackson JAR paths supplied through the variables listed in the backend README. Final local run passed using a disposable PostgreSQL cluster and synthetic HTTP server. A sandboxed run could not bind loopback; the authorized unsandboxed run passed.
- `openspec validate --all --strict --no-interactive`: 6 artifacts passed.
- Final test SHA-256: `a82f5b4e95f26f626efbdeae0d0db58dba595df59f6ac016acc3a8f3a34adf5f`; strict-text test: `0da29d2793f4022ef53344267fd01d2725384fd483939cfee2f373f9e1801a1c`.
- Handler SHA-256: `3d3bcc7b5b32b4b8b835774ebbc057f8136bc8b37e67607755f89307872566ef`; JDBC service: `44b2b84f19fd95d28e07159a25cf4efd1df0c9566f89072396efd0703a7bd230`; runner: `b1ccbd3d70f56d7e5fbfc1127673a185ae3cf364f55cb21553e7a4dfeae42962`.
- Coordination: scaffold and service implementation, test-design correction, fresh candidate verification, and separate security review were delegated to scoped specialists. The security reviewer used GPT-6.1 Sol medium; the implementation and other cited specialists used GPT-6 Luna at the configured effort. The coordinator recorded the distinction between automated tests and separate probes.
- Earlier blocked setup and original baseline are retained in [baseline preparation](2026-11-08-private-notes-service-baseline.md); they are historical evidence, not the final test result.
