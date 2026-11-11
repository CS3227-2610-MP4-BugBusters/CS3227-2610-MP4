# Tasks

## 1. Provider and authorization boundary

- [ ] 1.1 Define and test the authenticated request/response contract and provider interface, including bounded fields and no domain mutation.
- [x] 1.2 Test and implement account, assignment and source-version snapshot/recheck with real PostgreSQL; verify unauthorized requests never invoke the provider and late results are discarded.

## 2. SoC integration and security

- [ ] 2.1 Test and implement the SoC adapter with bounded prompt, response validation, timeout and per-instance in-flight protection; verify malformed output, timeout, no-secret logging and sanitized usage.
- [ ] 2.2 Independently review AI authorization, prompt injection and resource controls; run frozen legitimate and malicious fixtures and record outcomes without treating timeouts as security passes.

## 3. Developer review flow

- [x] 3.1 Test and implement a separate suggestion preview and explicit Apply action in the Developer checklist; verify generation alone cannot save or share, and stale output is not applied.
- [ ] 3.2 Run integrated real-session API and UI checks with a deterministic provider; verify report changes, reassignment, CSRF, output validation and no persisted mutation.

## 4. Live evidence and release

- [ ] 4.1 Freeze the evaluation rubric and fixtures, then record bounded live SoC usefulness and adversarial outcomes, actual available token usage and timeouts without secrets or real user content.
- [ ] 4.2 Reconcile the provider adapter with the Reporter-owned shared gateway, confirm actual quota and shared limits, and verify environment-specific secrets and deployment before release.

Progress note (10 Nov): the Developer provider now routes through the shared `DEVELOPER` role service and `DEVELOPER_ASSIST` task, with trusted actor identity, serialized-input bounds, safe gateway failure mapping, output credential-reflection rejection, and no legacy `SOCLAAS_*` activation. Focused adapter/configuration tests pass. Task 4.2 remains open for confirmed live quota and shared-budget values, environment-specific secret verification, and deployed integration evidence; no live quota is claimed here.

## 5. Saved checklist context

- [x] 5.1 Send bounded saved checklist steps to the provider with the report; explicitly treat both as untrusted and instruct the model to avoid duplicates.
- [x] 5.2 Reject suggestions when the report or saved draft changes during generation or before applying; preserve private notes, unsaved edits and separate Save/Share actions.
- [ ] 5.3 Verify authorization, input bounds, stale-draft races, prompt injection and frontend behavior, and record limits of live provider testing.
