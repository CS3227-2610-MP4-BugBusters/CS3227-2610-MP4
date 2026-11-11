# Design

## Context

Today the limits are constants spread across several classes (see proposal.md, Why):

- `SocLlmRoleService.validate`: 24,576 input bytes, 8,192 instruction bytes,
  2,048 output tokens, deadline ≤ 30 s.
- `SocLlmHttpProvider`: the same byte ceilings, plus request, response and timeout
  ceilings for the transport.
- `MaintainerAdmissionAdapter`: a fixed reservation of 8,192 + 24,576 + 2,048.
- `PostgresSocLlmAdmission`: a 5-minute actor window, 3 starts, and an implicit
  limit of 1 in flight, counted by `actor_id, role`.
- `platform/SocLlmConfiguration`: repeats the 24,576 report bound.

`mp4_socllm.attempt` already stores `task`, but its indexes cover `(actor_id, role, …)`.

## Goals / Non-Goals

**Goals:**
- One place defines each task's limits. All checks read from it.
- Tests can inject different policies to show per-task separation (TL-06, TL-08).

**Non-Goals:**
- Reading task limits from `.env` or Spring properties.
- Changing the provider transport ceilings, failure codes or key budgets.

## Decisions

1. **`SocLlmTaskPolicy` record plus a `SocLlmTaskPolicies` registry in
   `mp4.socllm`.** The record holds the seven values from AI-TASK-POLICY and
   validates them in its constructor (TL-02). `SocLlmTaskPolicies.defaults()`
   returns an `EnumMap` with one entry per `Task`, all with the initial values.
   The registry fails if any task is missing.
   *Alternative:* store the values on the `Task` enum. Rejected because tests
   could not inject differing policies without reflection.

2. **Injection.** `SocLlmRoleService`, `PostgresSocLlmAdmission` and
   `MaintainerAdmissionAdapter` take the registry through their constructors. The
   public constructors use `defaults()`, so Spring wiring in `platform` stays the
   same apart from `SocLlmConfiguration` reading the report bound from the
   Maintainer policy.

3. **Transport ceilings stay in `SocLlmHttpProvider`** as an absolute
   defense-in-depth limit. A policy above a ceiling is invalid (TL-02), so a policy
   can only tighten limits.

4. **Failure codes stay as they are.** A call over its policy limits gets
   `AI_UNAVAILABLE` from the role service, as today, before admission. The provider
   keeps `AI_INVALID_OUTPUT` for anything over a ceiling. Neither error includes
   content.

5. **Admission counts by task.** The actor in-flight and start queries change from
   `role=?` to `task=?`. They use the task policy's `maxInFlightPerActor`,
   `startsPerWindow` and `window`. A rate-limit's Retry-After equals the window in
   seconds (300 today). The row still stores `role` for auditing. The 24-hour row
   retention limits the window (TL-02).

6. **Additive `V2__socllm_task_indexes.sql`.** It creates
   `(actor_id, task, admitted_at)` and a partial `(actor_id, task, in_flight_until)`
   index. The old role indexes stay so rollback is safe.

7. **The Maintainer reservation comes from its policy:** instruction + input +
   output limits, which is 34,816 today (TL-11).

8. **No MP3 code.** MP3's `AiTaskPolicy` informed only the shape of the policy.
   The implementation is written fresh for MP4 (project context).

## Risks / Trade-offs

- [Counting by task allows more starts per role once a role has several tasks] →
  This is intended. Key RPM/TPM still caps the total. Revisit if a role gets many tasks.
- [Changing the queries from role to task during a rolling deploy] → Old and new
  instances count different scopes for at most 5 minutes. Each role has one task
  today, so the counts are identical.
- [Constructor changes break existing tests] → Keep the current public constructor
  signatures, which delegate to `defaults()`.

## Migration Plan

Deploy V2, which only adds indexes, then the code. To roll back, redeploy the
previous code. The V2 indexes are harmless to leave in place.
