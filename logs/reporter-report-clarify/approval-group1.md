# Approval: reporter-report-clarify tasks 1.1, 1.2, 1.3

## Summary

On 2026-11-13 the feature owner approved implementation against the Red baseline
for the gateway group. That baseline covers raised transport ceilings (61,440 input
bytes, 8,192 output tokens, 60 s, 384 KiB request), the `REPORTER_ASSIST` policy
(reservation 77,824), the unchanged request shape with no `reasoning_effort`, and the
`SOC_LLM_MODEL_REPORTER` override. A fresh baseline verifier returned PASS. The owner
did not ask for the non-blocking gaps to be covered first: the deadline test is
loose above 33 s; there is no provider-level `max_tokens` rejection; RC-09c is
checked on one provider path; the clean-HEAD control was not rerun by the verifier.

## Approved baseline

- Revision: HEAD `1b2d3b7` (branch `llm-reporter`) plus working-tree test files.
- `src/backend/auth/src/test/java/mp4/socllm/SocLlmTaskPolicyTest.java`
  `c2e2cf0b154a4a3d4f487d7b850c84057701ad2d3275ed198de46756dc2d6163`
- `src/backend/auth/src/test/java/mp4/socllm/SocLlmHttpProviderTest.java`
  `c5ceaf487727837136597db0d28e3ce4dc467b0eafa7be4d49f3ea96261ccdb5`
- `src/backend/auth/src/test/java/mp4/platform/SocLlmRoleConfigurationTest.java`
  `510122ec3b03c1746108e40c39f43c56fa556f477a86539bc1fadceac16c62de`
- Red evidence: [red-group1.md](red-group1.md). Expected Red: exit 1, 27 tests, 7 failed.
