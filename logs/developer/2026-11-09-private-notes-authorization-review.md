# Private notes: repeatable local authorization checks

## Human-readable summary

- **Goal:** Make local private-notes access and lifecycle-ordering checks reproducible before integrating shared authentication.
- **Changes:** Added synthetic HTTP tests for denied Reporter, Maintainer, unrelated and former Developer access, preserved notes after reassignment, and notes writes blocked behind a reassignment or closure transaction. The runner uses a disposable PostgreSQL fixture.
- **Results:** The designer and independent candidate verifier each ran the runner successfully. Existing save, clear, stale-edit, proposal-preservation and invalid-encoding checks also passed. The independent security reviewer found no additional issue within the synthetic-actor scope and separately reran the prior runner.
- **Review limitation:** The lifecycle-first cases observe the notes request waiting on a PostgreSQL lock before the lifecycle transaction commits. Notes-first cases are sequential save-then-update checks, not simultaneous races. Lifecycle updates are fixture SQL, not real lifecycle APIs. Reporter and Maintainer report-detail projections are not present for testing.
- **Still needed:** Real sessions, account eligibility and revocation locking, CSRF, report-detail privacy, shared lifecycle integration and true notes-first concurrency tests. All OpenSpec tasks remain open. The synthetic harness's unauthenticated 401 does not establish the shared error or cache contract.
- **TDD distinction:** These tests extend coverage of behavior that was already implemented; they passed on the existing candidate and are not a new behavioral Red. The earlier scaffold baseline and changed header assertion are documented in the prior service review.
- **Resource evidence:** Test design, independent candidate verification and security review used separate roles. Actual token usage, elapsed time and costs are unavailable; no resource savings are claimed.

## Technical evidence

- Starting HEAD `79f4c9e51dd2395cca24a8d001db1d9aa9c805a0`; candidate included uncommitted files.
- Test designer: `notes_designer_configured`, GPT-6 Luna low; candidate verifier: `notes_candidate_final`, GPT-6 Luna low; security reviewer: `notes_security_final`, GPT-6.1 Sol medium. Each was scoped to the private-notes contract, relevant source/tests and its role; the reviewer and verifier made no edits.
- Command: `sh src/backend/developer/test/run-private-notes-baseline.sh` with PostgreSQL JDBC and Jackson JAR paths set as documented in the backend README. Loopback binding was blocked by the sandbox; runs using a disposable server outside that restriction passed.
- Race test SHA-256 `eda611500e787418efe9fe8ac62834fffc12c3a054e13f78493988f4185c908a`; runner `62012e960f423018020bb817f80a81b676bdb0013d1d76a2bf66c856d49009f2`.
- Handler `3d3bcc7b5b32b4b8b835774ebbc057f8136bc8b37e67607755f89307872566ef`; JDBC service `44b2b84f19fd95d28e07159a25cf4efd1df0c9566f89072396efd0703a7bd230`.
- The previous [service review](2026-11-08-private-notes-service-review.md) records earlier implementation, review and baseline details.
