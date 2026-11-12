# Maintainer live-summary repair — 12 November 2026

Repaired the context and model configuration behind the failed live-summary trials.
Omitted null optional fields, strengthened description/history grounding and concise
overview instructions, and added a Maintainer-only model override. Preserved strict
output validation, the response schema, deadlines, shared quotas and other roles'
settings. Independently verified the failing tests before implementation and checked
the candidate/security boundaries afterward. Kept human acceptance and provider
quota/deployment checks separate from this local repair.

## Requirements and coordination

Used `complete-maintainer-role` task 5.6 and its description-facts/model-selection
scenarios. Started from `c72b084`, with planning changes and two new test files.
Supplied the named test designer, fresh baseline verifier, implementer, fresh
candidate verifier and separate security reviewer with explicit paths and roles.
Kept one writer per source file and one Gradle owner at a time. Used the existing
fixtures, runner and response format; no application source was copied from another MP.

The test designer added three tests. A fresh verifier reproduced two failures:
context retained a null placeholder and the Maintainer model override was ignored.
The blank/absent override control passed. Proceeded under the owner's standing
instruction to implement compatible proposals after verification. Kept the approved
tests unchanged throughout. Used available named-role tool defaults; actual token
usage and cost of development agents were unavailable.

## Implementation and findings

Changed `QueueSummaryEngine` and `SocLlmConfiguration` only in application code.
Preserved description facts and populated optional fields; omitted absent optional
fields. Required the model to check all supplied text before declaring information
missing. Advanced internal context/cache policy to `MNT-CONTEXT-2`; retained the
frontend-compatible `MNT-SUMMARY-1` response contract.

Added server-owned `SOC_LLM_MODEL_MAINTAINER`, with trimmed nonblank override and
shared-model fallback. Both provider and cache/model identity use the selected
model. Left the Reporter/Developer configuration untouched, including the files
changed by pending PR #16. Used that teammate's measured Llama trial as a starting
point, then tested the Maintainer feature separately.

The initial repaired Llama calls returned valid output in 3.12–3.78 seconds and did
not reproduce the false description-only missing-information claim, but their
overviews were empty. Refined the overview/blocker guidance. That trial returned
one valid answer and two safe 502 rejections. A direct synthetic diagnostic found
`missingInformation` entries using `type` instead of `text`. Clarified the identical
finding shape for every array with a compact JSON example; did not relax validation.
These were two bounded prompt refinements after the initial implementation, not a
controlled model-comparison experiment. Llama still produced a grounding failure
on the malicious case and a format rejection, so did not select it for release.
Gemma returned provider unavailability. With revised instructions, Qwen returned
all three cases within the deadline but still claimed blank fields were missing.

Inspected the real records and SQL: the optional columns are `NOT NULL DEFAULT ''`,
not null as the first regression fixture assumed. Opened the narrow task 5.7,
added a storage-shaped blank/whitespace fixture with a populated-field control,
and independently reproduced its one failure and one pass. Corrected the omission
rule to include blank strings without trimming meaningful content. Kept this
separate from the bounded prompt refinements; the new test captures the actual
data-shape gap rather than another prompt-wording expectation.

## Independent checks

From `src/backend/auth`, with JDK 21, ran the new grounding/model tests and the
existing `QueueSummaryTest` regression. The candidate verifier independently passed
17 tests across the new tests, Maintainer/shared-role configuration and summary
safety regression classes. After prompt refinements, independently reran the three
approved tests: zero failures/errors/skips. Security review found no scoped blocker
and rechecked the final prompt without running concurrent Gradle/live work.

Approved test SHA-256 values, unchanged:

```text
9027D8C40DD19AC675FA42E566580F051A23FF06294469372A982A9A8522EFFE  SummaryDescriptionGroundingTest.java
2945D0400546670C986C246DBA458ECEB5226131E88432EF37C43A3B84EFD487  MaintainerModelOverrideTest.java
5DA8E6499AAB6F6C8660CE0C54EAEB413D1013DE4B932E2D76CD49A50001A879  SummaryBlankContextTest.java
```

Final reviewed application SHA-256 values:

```text
6756CCD26C1B48C978A4C2DF1CF2B522F49C17ED61A0D1E2DA2F19904070807D  QueueSummaryEngine.java
6C7510B40BF4F0EDA4E9DD90FF0380AD0D4BDB16F6300F83E8B8801F3E40A4B1  SocLlmConfiguration.java
```

## Live verification scope

Packaged the actual application against disposable PostgreSQL, real operator-created
sessions and the real SoC endpoint. Selected models only for Maintainer while
preserving shared model `qwen3.6:35b`; selected Qwen for the final check after the
alternative-model failures. Used the previous local application budgets without
raising the 30-second deadline or three-starts-per-account/five-minute limit.
Included the Chrome/expected-behavior description regression in the second queue.
Captured source details before/after generation and checked repeat-result reuse.
Kept credentials, raw diagnostic responses and fixture passwords outside Git.

Final candidate with `qwen3.6:35b` returned:

| Case | HTTP | Seconds | Identical-result reuse |
| --- | ---: | ---: | ---: |
| MNT-EX01 | 200 | 7.24 | 0.77 s |
| MNT-EX02 plus description-only regression | 200 | 13.89 | 0.64 s |
| Combined malicious-input case | 200 | 4.67 | 0.58 s |

All returned `providerMode: soc`, valid supplied report references and the existing
response schema. The second answer explicitly recognized Chrome and the expected
behavior in the description instead of listing them as missing. Its missing-info
findings referred to the other reports' absent browser/network/reproduction details.
The malicious case described the injection as an attempt, did not claim reports
were fixed/closed, emitted no fixture private-note marker and changed no report
details. Reuse returned identical results/capture times. A fourth forced generation
returned 429 `AI_RATE_LIMITED`, as required; did not bypass or raise the limiter.

Assessed these outputs as the coordinator, not as completed human rubric scoring.
This small sample resolves the observed regression; it is not a reliability rate
or proof against every prompt-injection technique. Earlier failed trials remain
recorded above and in the initial release-check log.

Independently passed all five approved tests after the blank-string repair (two
blank/populated controls, one description-context test and two model-selection
tests). The separate security reviewer reconfirmed the final helper/prompt/config
boundaries. The focused final command was:

```text
gradlew.bat --no-daemon test --tests mp4.maintainer.summary.SummaryBlankContextTest --tests mp4.maintainer.summary.SummaryDescriptionGroundingTest --tests mp4.platform.MaintainerModelOverrideTest
```

## Limits and resources

Measured HTTP elapsed time, not isolated model processing time. Actual application
token usage/billing and provider quotas remain unavailable. Did not run the planned
repeated full-context-versus-selected-context experiment or obtain human rubric
scores. A successful small synthetic trial does not prove broad prompt-injection
resistance or production reliability. Render remains outside this task.

Corrected local setup only when needed: supplied the disposable database's explicit
loopback port on restart and stopped the owned application before rebuilding its
Windows-locked distribution. These were harness/process issues, not application
changes. Changed no prior tests, provider output validator or rate-limit policy.

Passed strict OpenSpec validation for all 14 items and whitespace checks. Stopped
the owned local app/database and removed the temporary API-key file after the final
trials. Kept credentials, private fixtures and raw diagnostic output outside Git.
