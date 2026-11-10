# Developer AI report context bound

The AI service rejected oversized report text before calling the provider, but
the JDBC snapshot first loaded the full description into application memory.
The focused test checks the database projection as well as the HTTP outcome.
The implementation is not yet changed.

## Baseline

- Starting HEAD `8b74eac4e7f2403fc21a6dc57efef41af4ad6761`, with an
  uncommitted local contract clarification and test.
- A test designer used authenticated local HTTP, disposable PostgreSQL and an
  observing JDBC data source. An 8,193-byte synthetic description returned 400
  and invoked no provider, but the observer saw the complete description
  returned from JDBC. The early assertion failed; the later exact-boundary
  assertions had not run.
- The focused command was `./gradlew --no-daemon test --tests
  mp4.auth.SuggestionInputBoundsIntegrationTest` using a disposable PostgreSQL
  wrapper and loopback access outside the sandbox. Test compilation passed;
  the focused test failed on the projection assertion.
- Test SHA-256: `9e96298c40cfdb1c2fd6e24bbe55f876f4fde978cc6b158c21605186924ce9e7`.
  An independent verifier reran `python3 test/run_setup.py` from
  `src/backend/auth`: the setup control passed, and the target test reproduced
  the projection failure. The full suite had two failures: this case and the
  separate known cancellation-capacity regression. Per-agent tokens and cost
  were unavailable.

## Implementation and review

The implementer changed the locked report snapshot query to compute UTF-8 byte
lengths in PostgreSQL and return title and description only when each is within
its limit. Assignment, account checks, report lock and version snapshot remain
in place. Oversized input still returns 400 and never calls the provider.

The security reviewer noted that `octet_length(text)` depends on database
encoding. The query was revised to measure `octet_length(convert_to(...,
'UTF8'))`; the reviewer inspected that final SQL and found the encoding concern
addressed. A fresh candidate verifier reran the disposable setup and focused
integration test with `python3 /tmp/run_ai_context_bound.py`; both passed. The
temporary runner is not a checked-in reproducible entry point. The focused test
checks ASCII boundaries, not a dedicated multibyte or non-UTF-8 database case.

The implementer also ran the full backend suite before the final SQL adjustment:
96 tests ran with one failure, the separate cancellation-capacity regression.
The final query was checked by the focused test and reviewer; the full suite was
not rerun after the encoding adjustment. That cancellation issue, live gateway
integration, and live evaluation remain open. This worktree is uncommitted.
Per-agent token, cache and cost measurements were unavailable; no resource
savings are claimed.
