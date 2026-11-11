# Developer AI checklist: backend regression evidence

## Plain-language summary

The backend regression tests now reproduce five security and error-contract gaps against the current local implementation. Two tests show logout can race with the database snapshot locks: one still invokes the provider after logout, and another returns suggestions after logout. Three tests show timeout, invalid provider output, and local capacity exhaustion are all reported as generic 503 errors rather than their specified 504, 502, and 429 responses.

The tests also verify that failure responses do not leak provider canaries and that report/checklist state remains unchanged. Those checks execute even when the expected status is wrong. The logout tests correlate their PostgreSQL lock wait to the exact test-held row-lock connection.

The ordinary authorized request passed in the independent run; it returned 401 in one earlier run, so this appears intermittent and needs monitoring during candidate verification. No implementation fix is claimed in this baseline record.

## Technical evidence

- OpenSpec change: `developer-ai-checklist-draft`, Tasks 1.2 and 2.1.
- Starting revision: `a51ab63b2de968097535481ff9d1b59d411119f8`, branch `codex/developer-ai-checklist`; implementation worktree was dirty.
- Command: `python3 src/backend/auth/test/run_setup.py`.
- Test designer: configured `gpt-6-luna`, low reasoning. Completed disposable PostgreSQL setup, migrations and compilation; 73 tests ran, 6 failed. Five targeted gaps failed as expected. One ordinary assigned-Developer success case also returned 401 in that run.
- Independent baseline verifier: configured `gpt-6-luna`, low reasoning. Verified test hashes and assertions, then reran the same command. Setup passed; 73 tests ran, 5 failed. The same five targeted gaps failed; the ordinary assigned-Developer success case passed. The targeted Red baseline verdict was PASS; full suite was a behavioral Red, not a setup failure.
- Logout test SHA-256: `be492840774e74ba7a00c48807fe2c3d593a40b3bd1ce0cf6a00c7543df1ce15`.
- Provider failure test SHA-256: `3a857dd47e0fdfa479a1c3683e8f35de4b351c140ff2167c6c1201521425ae53`.
- Token usage for both agents: unavailable in the exposed trace.
- Environment: synthetic accounts, disposable PostgreSQL, local HTTP server and fake provider. This does not verify live SoC quality, shared quota, production deployment, or cluster-wide limits.

## Candidate verification after the fixes

The candidate changed only `SuggestionService.java` for this increment. It checks the persisted session after the initial snapshot before invoking the provider, checks again after the final snapshot lock wait before returning a result, and maps each typed provider failure to its contract status using fixed public messages.

- Candidate `SuggestionService.java` SHA-256: `e8868b0359597484bf75a47a7e8159c45ad6ba80b364c084cb16e8993d065be5`.
- Independent `candidate_verifier` reran `python3 src/backend/auth/test/run_setup.py`: **73 tests, 0 skipped, 0 failures, 0 errors; BUILD SUCCESSFUL**. Both approved regression test hashes matched.
- Independent `security_reviewer` found no blocking issue in this delta. It confirmed the session checks and safe typed-error messages. Token usage for the agents was unavailable.
- This does not complete the AI feature. The reviewer reproduced by inspection that CR/LF inside generated steps is still accepted, `application/jsonp` still passes the content-type prefix check, provider cancellation does not prove transport cleanup before releasing capacity, and sanitized usage/outcome telemetry is absent. These remain open for separate tests and fixes.

## Validation-edge Red baseline

Two additional real-session tests cover the contract's single-line suggestion rule and exact JSON request media type. The independent baseline verifier confirmed both expected failures and the test assertions: a multiline suggestion was returned with its canary, and JSONP was accepted and invoked the provider. Both test cases independently confirmed that persisted report/checklist state stayed unchanged. The other 73 tests passed.

- Command: `env LC_ALL=C LANG=C python3 src/backend/auth/test/run_setup.py`; an initial sandbox attempt failed at loopback bind, then the isolated disposable-PostgreSQL retry completed.
- Result: 75 tests; 73 passed and 2 behavior-specific Reds.
- Changed test file SHA-256: `cfbde8c5382a54d99ff1b54db48e01f7b40b71a4fce9044a86f4f51a5d08c9d7`.
- Baseline source `SuggestionService.java` SHA-256: `e8868b0359597484bf75a47a7e8159c45ad6ba80b364c084cb16e8993d065be5`.
- Test designer and baseline verifier were configured `gpt-6-luna`, low reasoning; token usage was unavailable.
- Test environment was synthetic accounts, local HTTP, disposable PostgreSQL and fake provider; no live SoC or production behavior was exercised.

## Validation-edge candidate verification

The implementer added CR/LF rejection to model steps and replaced the request media-type prefix check with parsed `application/json` type/subtype validation. The independent candidate verifier confirmed the frozen tests and ran the entire auth/backend suite.

- Candidate `SuggestionService.java` SHA-256: `c1a6a84bbe92beba9ad5314519bd982786b0f9be09108da8f6ddea95a43606af`.
- Candidate `SuggestionController.java` SHA-256: `3c6224a12e30fc136a0fc3de29986797ecfe516cd51342fba9ad117e705c131b`.
- Exact test command: `env LC_ALL=C LANG=C python3 src/backend/auth/test/run_setup.py`; sandboxed loopback was denied, then the permitted isolated PostgreSQL run completed.
- Result: **75 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESSFUL**. The verifier separately confirmed JSONP returns 400 before provider invocation, CR/LF output returns sanitized 502, the normal advisory path returns 200 without persistence, both logout races reject as expected, and all four typed errors map safely.
- Independent security review found no new authorization or provider-detail disclosure bypass. It noted the low-severity malformed parameter case `application/json; broken` still parses as JSON, plus open provider cancellation/resource cleanup and telemetry issues. Token usage was unavailable.
- Evidence is still synthetic local HTTP, fake provider and disposable PostgreSQL. It does not establish live SoC usefulness/quota, hosted production, or transport cleanup after cancellation.

## Telemetry Red baseline

The new test makes two successful real-session requests: one provider response contains 17 input and 5 output tokens, and one omits token metrics. It requires one sanitized event per request with outcome, selected model or unavailable, duration, configured timeout and capacity, and actual-or-unavailable token fields. It also checks that report text, private draft text, provider outputs and persisted changes are absent.

- Test file SHA-256: `9774ffe7722d6338ed901b75d43bf7b218c0387eb9900e9bfbbcde520fd3a4c9`.
- Source at baseline: `SuggestionService.java` `c1a6a84bbe92beba9ad5314519bd982786b0f9be09108da8f6ddea95a43606af`; `SuggestionController.java` `3c6224a12e30fc136a0fc3de29986797ecfe516cd51342fba9ad117e705c131b`.
- Command: `env LC_ALL=C LANG=C python3 src/backend/auth/test/run_setup.py`. The initial sandbox attempt was denied loopback; the elevated disposable PostgreSQL run completed setup and migrations.
- Test designer: configured `gpt-6-luna`, low reasoning; 76 tests, 75 passed and the telemetry test failed because 0 events were recorded instead of 2.
- Independent baseline verifier: configured `gpt-6-luna`, low reasoning; reproduced the same 76/1 result. Both requests returned 200; sensitive-canary and unchanged-state assertions passed.
- Token usage for the two agents: unavailable. Test environment used synthetic accounts, local HTTP and a fake provider; no live SoC, quota or deployment evidence.

#### Telemetry failure-isolation candidate

`SuggestionService` now treats telemetry as best effort: a failing model-name accessor falls back to `unavailable`, and a logger `RuntimeException` is contained without logging its text. The intended API result is preserved.

- Candidate `SuggestionService.java` SHA-256: `347543995132884719167b923743760cb47ed9c7b37cc0c48bde84030e923edb`.
- Independent candidate verifier ran `env LC_ALL=C LANG=C python3 src/backend/auth/test/run_setup.py` with the permitted isolated PostgreSQL setup: **77 tests across 17 suites, 0 failures, 0 errors, 0 skipped; BUILD SUCCESSFUL**. The integration suite passed all 15 tests, including the throwing-model test, and no exception canary appeared in service/root logs.
- Independent security review found no new issue in the telemetry change. Tests were not rerun by the reviewer. Throwing appender behavior remains inspection-only because Logback may isolate appender failures.
- Token usage unavailable. This local suite does not verify production log retention or shared provider quotas.

### Telemetry candidate result

The candidate emits safe key/value telemetry for provider-backed requests. The SoC adapter exposes its configured model; the test fake has no model and is recorded as `unavailable`. Missing provider token metrics are recorded as unavailable. The event includes outcome, duration, timeout and per-instance capacity, with no report or provider content.

- Candidate SHA-256: `SuggestionService.java` `bc7331c246bc1fe7b4e2e4f104f72869e2da13f3634d83693aaf55f06e942008`; `SuggestionProvider.java` `7e38f518f280af1f0235479b27d890a805f726c86519e6705ba880e4212e0af7`; `SocSuggestionProvider.java` `0bcf879578d184fdc0360e856d76d628d7b622d6585b24b71b20bbfeae71acd6`.
- Independent candidate verifier ran `env LC_ALL=C LANG=C python3 src/backend/auth/test/run_setup.py` with the permitted isolated PostgreSQL setup: **76 tests, 0 skipped, 0 failures, 0 errors; BUILD SUCCESSFUL**. The approved telemetry test hash remained `9774ffe7722d6338ed901b75d43bf7b218c0387eb9900e9bfbbcde520fd3a4c9`.
- Independent security review found no content leakage in the telemetry fields, but identified a P2 robustness concern: a throwing `modelName()` or logger call could interrupt an otherwise safe response. This has not yet been tested or fixed; telemetry completion remains open pending that check.
- Token usage for candidate/security reviewers: unavailable. This local test does not establish production log retention, live SoC quota, or remote resource cleanup.

### Telemetry failure-isolation Red

A focused regression configures the fake provider's `modelName()` accessor to throw a synthetic canary during an otherwise successful authorized request. The test requires telemetry to be best effort: preserve the 200 response, return the reviewed suggestions, keep domain state unchanged, and avoid logging the exception canary.

- Test file SHA-256: `5dfa64d61af2219a0f4e8e5893e26c765c7be5a85ad6ce3e6ccbff29c4d125fb`.
- Test designer: configured `gpt-6-luna`, low reasoning; full runner reported 77 tests, with only this test failing. The request instead returned 401, and the root log appender captured the canary. Domain state remained unchanged.
- The designer could not make a reliable throwing-appender test because Logback may isolate appender errors. The provider accessor failure directly reproduces the documented telemetry boundary issue.
- Independent `baseline_verifier` confirmed the same Red with `env LC_ALL=C LANG=C python3 src/backend/auth/test/run_setup.py`: 77 tests, 1 failure, 0 errors, 0 skipped. The failure was only the new resilience test; HTTP 401 and the log canary were observed, and persisted state was unchanged. Token usage unavailable; synthetic local setup only.

## Context and interpretation for reflection

The AI security work used targeted regression fixtures and separated provider, authorization and output checks. The original record identifies which cases passed, failed or remained blocked.

Passing a deterministic fixture does not establish semantic safety for every model response or successful live service. Use the listed findings and limitations when presenting the evaluation.
