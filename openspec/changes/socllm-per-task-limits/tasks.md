# Tasks

For each behavior task, follow `workflow/tdd.md`: test_designer, a fresh
baseline_verifier, feature-owner approval of the verified Red, the implementer, a
fresh candidate_verifier and a separate security_reviewer, since this is an AI-security
change. A missing fixture or runner, such as the disposable PostgreSQL runner, is
BLOCKED, not a passing Red. Record evidence under `logs/socllm-per-task-limits/`.

## 1. Task policy (AI-TASK-POLICY, AI-TASK-INITIAL)

- [ ] 1.1 Add `SocLlmTaskPolicy` with constructor validation and
  `SocLlmTaskPolicies.defaults()` covering all three tasks with the initial values.
  Verify with unit tests for TL-01, TL-02 (each invalid field, including ceilings
  and the 24 h window) and TL-03.

## 2. Call bounds (AI-TASK-CALL-BOUNDS)

- [ ] 2.1 Make `SocLlmRoleService` take the policy registry and check the input,
  instruction, output and deadline limits from the call's own task before admission.
  The existing public constructor uses `defaults()`. Verify with `SocLlmRoleServiceTest`
  cases for TL-04, TL-05 and TL-06 (an injected policy that differs per task),
  zero admissions and zero provider calls on rejection, and the existing tests still passing.
- [ ] 2.2 Make the Maintainer adapter's reservation and the report bound in
  `platform/SocLlmConfiguration` come from the Maintainer policy. Verify with
  TL-11 (reservation = 34,816 with defaults and follows an injected policy) and
  `MaintainerSocLlmConfigurationTest` / `SocLlmRoleConfigurationTest` still passing.

## 3. Per-task admission (AI-TASK-ADMISSION, AI-TASK-KEY-BUDGET)

- [ ] 3.1 Add `V2__socllm_task_indexes.sql` (additive task indexes). Verify the
  migration applies on the disposable PostgreSQL runner after V1.
- [ ] 3.2 Make `PostgresSocLlmAdmission` count actor starts and in-flight calls by
  task, using the task policy's limits and window. Verify with
  `PostgresSocLlmAdmissionTest` cases for TL-07 (Retry-After = window), TL-08
  (injected policies, two tasks for one account), TL-09 and TL-10, and the existing
  multi-instance, lease and key-budget tests still passing.

## 4. Documentation and integration

- [ ] 4.1 Update `src/backend/socllm/README.md`: describe per-task policies and the
  defaults, list the three role keys, and note that `.env` is local and gitignored
  and is not loaded by Spring automatically. Verify that the README values match
  `defaults()`.
- [ ] 4.2 Run the full socllm/platform test set from the README and the
  PostgreSQL runner on the candidate. Record the revision, commands and results in
  the evidence log, with candidate_verifier and security_reviewer verdicts.

## Workflow follow-up

- Archive the change after owner review, then sync `socllm-task-limits` into `openspec/specs/`.
