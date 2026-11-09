# Developer AI: resumed baseline review

## Human-readable summary

- **Goal:** Resume the Developer AI checklist feature with independently checked tests for authorization and stale results.
- **Result:** The disposable PostgreSQL and authentication setup ran, but the endpoint is an explicit 501 placeholder and does not invoke its provider. Eight of 57 tests failed at that placeholder or waiting for the fake provider. This confirms missing implementation; it is not a verified behavioral Red.
- **Review:** A fresh baseline verifier checked the test and reproduced the result. The existing tests cover session and assignment denials and several late-result cases, but still need request bounds, CSRF, invalid-output, provider failure and timeout/capacity coverage.
- **Provider tests:** A separate independent review identified missing oversized-input and deterministic streaming-timeout checks. The test designer added those checks to the pending adapter test. It remains outside the Java source set because the provider adapter is absent. Concurrency and permit retention require separate service-level tests.
- **Limitations:** No AI backend implementation is claimed. The scaffold, tests and contract refinements remain uncommitted; unrelated existing worktree changes were preserved. Per-agent tokens, cost and elapsed time are unavailable.

## Technical evidence

- HEAD `a51ab63b2de968097535481ff9d1b59d411119f8`; branch `codex/developer-ai-checklist`; dirty working tree.
- Independent `baseline_verifier` `ai_resume_baseline`, configured GPT-6 Luna low, reran `python3 src/backend/auth/test/run_setup.py` with disposable PostgreSQL. Sandboxed loopback bind was blocked; the escalated run completed setup, migrations and compilation, then reported 57 tests and 8 failures attributable to the 501 scaffold or provider never being invoked.
- Real-session test SHA-256 `5a790ef69803b6a3ef6654dadcbfc4608a86be198e278e1b13de58083cbdf22b`; updated pending provider test SHA-256 `804bf22a6470cd9e72a47fda197b8ccb7b261acdcfb22ffc4c7eb351bf1b8588`.
- `ai_adapter_test_review` independently inspected the provider cases. `ai_adapter_tests_improve` added over-cap and synchronized streaming cases; these were not run because the adapter class is missing.

## Context and interpretation for reflection

The resume checkpoint captures what was known before continuing AI checklist work and separates existing evidence from unfinished provider and security checks.

Use it as a handoff record, not evidence that the later live model or deployment succeeded. The bounded live outcomes and security limitations are in the adjacent AI evaluation logs.
