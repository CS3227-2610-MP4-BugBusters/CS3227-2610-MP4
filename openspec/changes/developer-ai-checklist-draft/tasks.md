# Tasks

## 1. Provider and authorization boundary

- [ ] 1.1 Define and test the authenticated request/response contract and provider interface, including bounded fields and no domain mutation.
- [ ] 1.2 Test and implement account, assignment and source-version snapshot/recheck with real PostgreSQL; verify unauthorized requests never invoke the provider and late results are discarded.

## 2. SoC integration and security

- [ ] 2.1 Test and implement the SoC adapter with bounded prompt, response validation, timeout and per-instance in-flight protection; verify malformed output, timeout, no-secret logging and sanitized usage.
- [ ] 2.2 Independently review AI authorization, prompt injection and resource controls; run frozen legitimate and malicious fixtures and record outcomes without treating timeouts as security passes.

## 3. Developer review flow

- [ ] 3.1 Test and implement a separate suggestion preview and explicit Apply action in the Developer checklist; verify generation alone cannot save or share, and stale output is not applied.
- [ ] 3.2 Run integrated real-session API and UI checks with a deterministic provider; verify report changes, reassignment, CSRF, output validation and no persisted mutation.

## 4. Live evidence and release

- [ ] 4.1 Freeze the evaluation rubric and fixtures, then record bounded live SoC usefulness and adversarial outcomes, actual available token usage and timeouts without secrets or real user content.
- [ ] 4.2 Reconcile the provider adapter with the Reporter-owned shared gateway, confirm actual quota and shared limits, and verify environment-specific secrets and deployment before release.
