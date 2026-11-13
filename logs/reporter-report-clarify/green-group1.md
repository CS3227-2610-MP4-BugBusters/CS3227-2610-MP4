# Green evidence: reporter-report-clarify tasks 1.1, 1.2, 1.3

## Summary

Goal: make the approved Red tests for the gateway group pass. The scope was the raised transport ceilings, the `REPORTER_ASSIST` policy and the `SOC_LLM_MODEL_REPORTER` override. The request shape had to stay the same.

Changes:
- `SocLlmTaskPolicy` ceilings were raised to 61,440 input bytes, 8,192 output tokens and a 60 s deadline. The instruction ceiling stays at 8,192 bytes.
- A new `reporterAssist()` policy has the values 61,440 / 8,192 / 8,192 / 60 s / 3 starts per 5 min / 1 in flight, with a reservation of 77,824. The default registry assigns it to `REPORTER_ASSIST`. Developer and Maintainer tasks keep `initial()`.
- `SocLlmHttpProvider` now reads its input, instruction and deadline ceilings from the `SocLlmTaskPolicy` constants, so the provider and policy values cannot drift apart. The request body ceiling rises to 393,216 bytes (384 KiB). The response ceiling stays at 65,536 bytes.
- The request JSON is unchanged: `model`, `messages`, `max_tokens`, `stream`, `response_format`. No `reasoning_effort` is sent.
- `SocLlmRoleConfiguration.modelForRole` returns the trimmed `SOC_LLM_MODEL_REPORTER` for the Reporter role when it is set and not blank. Otherwise it returns `SOC_LLM_MODEL`. Developer and Maintainer resolution is unchanged.
- `.env.example` now lists `SOC_LLM_MODEL_REPORTER=` after `SOC_LLM_MODEL_DEVELOPER=`.

Checks and results:
- Focused command: exit 0. All 27 tests passed. In Red, 7 of these failed.
- Regression command: exit 1. 62 tests ran and 61 passed. The one failure is `PostgresSocLlmAdmissionTest > initializationError`, which needs the disposable PostgreSQL fixture (`MP4_AUTH_TEST_JDBC_URL` is not set). PostgreSQL tools are not installed on this machine, so that test is recorded as not run. This change does not touch `PostgresSocLlmAdmission` or its SQL.
- The SHA-256 hashes of the approved test files are unchanged.

Review findings and fixes: no reviewer has looked at this yet. The candidate_verifier step comes next.

Remaining limitations:
- The provider does not reject `max_tokens` above 8,192 by itself. That limit is enforced only by the task policy in `SocLlmRoleService`. The direct Maintainer provider path relies on its own `SummaryContracts` limits. This matches the open gap the approval accepted.
- Nothing checks at startup that `SOC_LLM_TPM` is at least 77,824. If it is lower, every Reporter call would be refused at admission.
- The 60 s deadline is checked only down to the test's 32 s server delay, as the approval already notes.
- Agent model, reasoning effort and token usage: unavailable.

## Reflection

- Agent role: implementer. TASK: reporter-report-clarify/1.1, 1.2, 1.3.
- Context supplied: the handoff packet, approval-group1.md, red-group1.md, design decisions 5-7, the three approved tests, and the WRITE source files.
- Agent coordination: a single implementer. No other agents were dispatched.
- Test scope: the focused classes plus the socllm and platform SoC LLM regression set. The PostgreSQL admission test was not run.
- `SocLlmRoleService` was not changed. Its bounds come from the policy registry, and its 60 s admission lease is renewed while the worker runs.
- During this run, `openspec/changes/socllm-per-task-limits/specs/socllm-task-limits/spec.md` appeared as modified in the working tree. The implementer did not edit it, and it was not in the start-of-run status.
- Resource measurements: the focused run took 1 m 21 s, most of it in the 32 s deadline test. The regression run took 46 s. Token usage: unavailable.

## Technical evidence

- Revision: HEAD `1b2d3b7f5a787fe2c73d4bbc79a54f6f9e016a5c` (branch `llm-reporter`), with the approved test files and the uncommitted main changes listed below.
- Runtime: Java 25.0.4.1, Gradle 9.1.0 (wrapper).
- Scratch: `C:/Users/tieut/AppData/Local/Temp/claude/df5390b674023e49/7fa0d685-24c5-4c90-ab02-274f443beffe/scratchpad/rc-g1-green/` (`focused.txt`, `regression.txt`, `regression-counts.txt`).

### Commands and exit codes

Focused: `cd src/backend/auth && ./gradlew.bat --no-daemon test --tests mp4.socllm.SocLlmHttpProviderTest --tests mp4.socllm.SocLlmTaskPolicyTest --tests mp4.platform.SocLlmRoleConfigurationTest`
- Exit 0, BUILD SUCCESSFUL.

| Class | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| mp4.socllm.SocLlmHttpProviderTest | 10 | 0 | 0 | 0 |
| mp4.socllm.SocLlmTaskPolicyTest | 7 | 0 | 0 | 0 |
| mp4.platform.SocLlmRoleConfigurationTest | 10 | 0 | 0 | 0 |

Regression: `cd src/backend/auth && ./gradlew.bat --no-daemon test --tests 'mp4.socllm.*' --tests 'mp4.platform.*SocLlm*' --tests 'mp4.platform.SocSuggestion*' --tests 'mp4.platform.MaintainerSocLlm*'`
- Exit 1. 62 tests completed and 1 failed. The failure is the PostgreSQL fixture precondition: `assertNotNull(url, "test requires disposable PostgreSQL fixture")`.

| Class | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| mp4.platform.MaintainerSocLlmConfigurationTest | 3 | 0 | 0 | 0 |
| mp4.platform.SocLlmRoleConfigurationTest | 10 | 0 | 0 | 0 |
| mp4.platform.SocLlmSuggestionProviderTest | 9 | 0 | 0 | 0 |
| mp4.platform.SocSuggestionCredentialReflectionTest | 4 | 0 | 0 | 0 |
| mp4.platform.SocSuggestionPromptContractTest | 1 | 0 | 0 | 0 |
| mp4.platform.SocSuggestionProviderTest | 7 | 0 | 0 | 0 |
| mp4.platform.SocSuggestionProviderTimeoutLifecycleTest | 2 | 0 | 0 | 0 |
| mp4.socllm.MaintainerAdmissionAdapterTest | 2 | 0 | 0 | 0 |
| mp4.socllm.PostgresSocLlmAdmissionTest | 1 | 1 (not run: no fixture) | 0 | 0 |
| mp4.socllm.SocLlmHttpProviderTest | 10 | 0 | 0 | 0 |
| mp4.socllm.SocLlmRoleServiceTest | 6 | 0 | 0 | 0 |
| mp4.socllm.SocLlmTaskPolicyTest | 7 | 0 | 0 | 0 |

### Changed main files (SHA-256)

- `src/backend/socllm/src/main/java/mp4/socllm/SocLlmTaskPolicy.java` cf88569cb58842104201c20e97ffae8d072381e1054e75709b299f50c1467388
- `src/backend/socllm/src/main/java/mp4/socllm/SocLlmTaskPolicies.java` e12d93f7cef7c389d94f61b65c1478f4b0a65375bf376eb76cb0c085dfb25454
- `src/backend/socllm/src/main/java/mp4/socllm/SocLlmHttpProvider.java` d94be220f633aff6df20d2c7bf8d740a829f6c50ca91be44f8ff85bcf4323a31
- `src/backend/platform/src/main/java/mp4/platform/SocLlmRoleConfiguration.java` ed608731b17af6256b27a7f406e917012d33295ed9b23a606a197c05cb3d015d
- `.env.example` 9e887ea6aa71ed0b7a49a453afcf5842195dcd2213c744c8ad76e05b9ceb5ba0

### Approved test files (SHA-256, unchanged from approval-group1.md)

- `src/backend/auth/src/test/java/mp4/socllm/SocLlmTaskPolicyTest.java` c2e2cf0b154a4a3d4f487d7b850c84057701ad2d3275ed198de46756dc2d6163 (match)
- `src/backend/auth/src/test/java/mp4/socllm/SocLlmHttpProviderTest.java` c5ceaf487727837136597db0d28e3ce4dc467b0eafa7be4d49f3ea96261ccdb5 (match)
- `src/backend/auth/src/test/java/mp4/platform/SocLlmRoleConfigurationTest.java` 510122ec3b03c1746108e40c39f43c56fa556f477a86539bc1fadceac16c62de (match)

## Independent review (orchestrator record)

- candidate_verifier (fresh context): **PASS**. The approved test hashes are
  unchanged. Focused run: exit 0, 27 tests, 0 skipped. Regression: all suites pass
  except `PostgresSocLlmAdmissionTest` (NOT RUN, no PostgreSQL). It noted two edits
  outside the implementer's listed paths. `.env.example` was an allowed implementer
  path. The `socllm-per-task-limits` spec TL-02/TL-03 wording was edited by the
  orchestrator for task 1.1.
- security_reviewer (fresh context, separate): **Low/Info findings only, nothing
  blocking.** Confirmed safe: the Reporter model comes only from server config;
  policy is chosen server-side; request keys are exact with no `reasoning_effort`;
  errors are content-free; leases cannot expire into a second concurrent call.
  1. Low: the direct Maintainer gateway checks only report bytes against its policy.
     Tokens and deadline rely on fixed Maintainer constants, and the transport
     backstop is now 60 s.
  2. Low: the transport does not check `max_tokens` against the 8,192 ceiling (this
     was already true before the change).
  3. Low (availability): 60 s calls on a 2-worker, queue-8 pool let about 4 Reporter
     accounts saturate it; it fails closed.
  4. Info: the 77,824 reservation needs `SOC_LLM_TPM` ≥ 77,824. A key shared across
     roles lets one Reporter call use most of its TPM.
  5. Info: the request-cap comment overstates worst-case escaping for control
     characters (fails closed). 8,192 tokens can exceed the 64 KiB response cap
     (fails closed as `AI_INVALID_OUTPUT`).
- Tasks 1.1-1.3 marked complete: their stated checks passed. The findings above are
  open for an owner decision.
