# Red evidence: reporter-report-clarify tasks 1.1, 1.2, 1.3

## Summary

Goal: write failing tests for the raised gateway ceilings (input 61,440 bytes, output 8,192 tokens, deadline 60 s, request body 384 KiB), the Reporter policy (REPORTER_ASSIST gets raised limits, Developer and Maintainer keep the initial limits), the Reporter model override (`SOC_LLM_MODEL_REPORTER`) and a request body with no `reasoning_effort`.

Changes: three existing test files were extended. No application code was touched.

Results: the setup control (existing tests, before edits) passed. After the edits, 27 tests ran and 7 failed, all on assertions about the missing behavior. The tests compile and the failures are not setup errors. Tests that already pass are regression guards, not Red.

Limitations: the deadline test takes about 32 s because it waits on a delayed loopback server. Output-token and 60 s clamp behavior is checked through the transport and the policy registry only. Agent model, reasoning effort and token usage: unavailable.

## Technical evidence

- Agent role: test_designer. TASK: reporter-report-clarify/1.1, 1.2, 1.3.
- Revision: HEAD 1b2d3b7 (branch llm-reporter) plus uncommitted `openspec/changes/reporter-report-clarify/`.
- Context supplied: handoff packet; spec RC-03c/RC-09; design decisions 5-7; the READ files.

### Commands and exit codes

Command (both runs): `cd src/backend/auth && ./gradlew.bat --no-daemon test --tests mp4.socllm.SocLlmHttpProviderTest --tests mp4.socllm.SocLlmTaskPolicyTest --tests mp4.platform.SocLlmRoleConfigurationTest`

- Setup control before edits: exit 0, BUILD SUCCESSFUL. Output: `scratchpad/rc-g1b/control.txt`
- Red after edits: exit 1, 27 tests completed, 7 failed. Output: `scratchpad/rc-g1b/red.txt`
- Scratch dir: `C:/Users/tieut/AppData/Local/Temp/claude/df5390b674023e49/7fa0d685-24c5-4c90-ab02-274f443beffe/scratchpad/rc-g1b/`

### Failing tests (expected Red)

```
SocLlmRoleConfigurationTest > RC09a_reporterModelOverrideIsUsedWhenNonblankAndTrimmed() FAILED
    AssertionFailedError at SocLlmRoleConfigurationTest.java:121   (returns shared-model, not reporter-model)
SocLlmHttpProviderTest > RC03c_requestBodyCeilingIs384KiBAfterJsonEscaping() FAILED
    SummaryFailure (AI_INVALID_OUTPUT) at SocLlmHttpProviderTest.java:133   (61,440-byte input rejected)
SocLlmHttpProviderTest > RC03c_threeByteUtf8DescriptionOfTwentyThousandCharactersIsSent() FAILED
    SummaryFailure at SocLlmHttpProviderTest.java:111   (60,000-byte input rejected by 24,576 ceiling)
SocLlmHttpProviderTest > RC03c_inputCeilingIs61440BytesAndInstructionCeilingStays8192() FAILED
    SummaryFailure at SocLlmHttpProviderTest.java:119   (61,440 bytes rejected)
SocLlmHttpProviderTest > RC03c_deadlineCeilingIsSixtySecondsNotThirty() FAILED
    SummaryFailure at SocLlmHttpProviderTest.java:146   (50 s bound clamped to 30 s, 32 s reply times out)
SocLlmTaskPolicyTest > TL02_raisedCeilingsAreAcceptedAtTheirBoundary() FAILED
    AssertionFailedError at :54, caused by IllegalArgumentException (constructor ceilings are old)
SocLlmTaskPolicyTest > RC09_reporterAssistPolicyHasRaisedLimits() FAILED
    AssertionFailedError at :59   (Reporter policy is still the initial 24,576 limit)
```

### Expected-pass regression guards (already pass today)

- `RC09c_requestBodyHasExactlyTheFiveExpectedKeysAndNoReasoningEffort` (no reasoning_effort exists today; also keeps `max_tokens` passthrough)
- `TL02_invalidPoliciesRejected` (updated to 61,441 / 8,193 / 8,193 / 61 s; these values are also rejected today)
- `TL03_developerAndMaintainerKeepInitialValues` (Developer and Maintainer equal `initial()`, reservation 34,816)
- `RC09b_reporterModelFallsBackToSharedModelWhenOverrideIsBlankOrMissing`, `RC09_reporterOverrideDoesNotAffectDeveloperOrMaintainerModels`
- Existing tests unchanged (response 64 KiB bound, Authorization, redirects, 429 and the rest).

### Changed files (SHA-256)

- `src/backend/auth/src/test/java/mp4/socllm/SocLlmTaskPolicyTest.java` c2e2cf0b154a4a3d4f487d7b850c84057701ad2d3275ed198de46756dc2d6163
- `src/backend/auth/src/test/java/mp4/socllm/SocLlmHttpProviderTest.java` c5ceaf487727837136597db0d28e3ce4dc467b0eafa7be4d49f3ea96261ccdb5
- `src/backend/auth/src/test/java/mp4/platform/SocLlmRoleConfigurationTest.java` 510122ec3b03c1746108e40c39f43c56fa556f477a86539bc1fadceac16c62de
