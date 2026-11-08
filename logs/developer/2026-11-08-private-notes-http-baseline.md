# Private notes HTTP test baseline

## Human-readable summary

- **Goal:** Prepare a focused local API test for reading, saving and clearing private notes without losing a pending resolution or overwriting a newer revision.
- **Prepared:** A Python API test, synthetic report/proposal fixture and read-only state snapshot. The test includes GET readbacks after save and clear.
- **Observed:** The designer started a disposable PostgreSQL database and the existing synthetic checklist harness. Fixture and session controls passed, but the notes route returned 404 because that harness does not mount a notes handler. No notes behavior was exercised; this is setup-blocked, not a behavioral Red.
- **Independent review:** The verifier confirmed the route limitation and found missing GET readbacks in the first test draft. The designer added them; the verifier checked the corrected assertions. The verifier did not reproduce the designer's database run; its own direct invocation stopped at missing environment variables.
- **Still open:** A testable notes application route and fixture/runner integration, plus separate tests for privacy, reassignment, state guards, text boundaries and concurrency. Maintainer agreement and real authentication remain integration prerequisites.

## Technical evidence

- Starting HEAD `79f4c9e51dd2395cca24a8d001db1d9aa9c805a0`; approved local contract is uncommitted. Test designer `private_notes_red` and verifier `private_notes_red_verifier` used GPT-6 Luna low. The designer used a disposable local PostgreSQL fixture and synthetic assigned-Developer session; initial sandbox shared-memory attempts failed and the escalated local setup succeeded.
- Final test `src/backend/developer/test/private_notes_api_test.py` SHA-256 `ed0656ce90c1230bf90dc1c9f0be5f23ebe9557127348b503f443405c0b7f93a`; fixture `private-notes-fixture.sql` SHA `02b97bed70931da6265cd66efda45249cb366249e700eb0b82f816f338c0a521`; snapshot `private-notes-snapshot.sql` SHA `10ef455bed3a00894641b86ce1e4c8468a49ac5aa70f288b00be14099ad82506`.
- Designer's configured invocation: `python3 -B src/backend/developer/test/private_notes_api_test.py`, with synthetic origin, session headers, fixture IDs, snapshot path and disposable PostgreSQL settings. The initial test revision reached unmounted route 404; final readback additions were syntax-checked and reviewed but not executed against a notes implementation. The verifier independently inspected assertions and hashes; no independently reproduced behavior Red was established.
- Token usage, per-agent duration and cost unavailable. No application source or existing tests were changed.
