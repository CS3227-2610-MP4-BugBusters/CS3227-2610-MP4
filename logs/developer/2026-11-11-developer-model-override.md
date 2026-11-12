# Developer model override — 11 November 2026

## Summary

A server-side `SOC_LLM_MODEL_DEVELOPER` override now selects the Developer suggestion model. Missing or blank values fall back to `SOC_LLM_MODEL`; Reporter and Maintainer retain the shared value. A synthetic authenticated Developer request succeeded with the shared model set to Qwen and the Developer override set to `llama3.1:8b`. Shared-gateway owner review, other-role live checks, browser and deployed verification remain open.

## Coordination and checks

- Starting revision `0b35be5`; uncommitted Developer envelope tolerance trial and evidence files existed before this task and were preserved.
- Test designer wrote override, missing and blank fallback cases. Baseline verifier reproduced one expected failure in seven tests, with controls passing. The verifier identified that the existing contract described only the shared model. The feature owner's explicit override instruction was recorded in the contract and OpenSpec before proceeding. Approved test SHA-256 `ab1585794cbcfce0b1643d2affb5a4e773d19df8f983260633cc2f6f32b37c8a`.
- Implementer used one server-side selector for Developer role-service construction and suggestion metadata. Focused `SocLlmRoleConfigurationTest` passed. A fresh candidate verifier confirmed the test hash, reran it and inspected the provider construction chain. It did not capture outbound model payload directly.
- Independent security review passed by inspection. Reporter and Maintainer retain the shared setting; shared admission remains keyed by configured provider key and existing task limits. The override is not supplied by report text or client input.
- `openspec validate developer-ai-checklist-draft --strict --no-interactive`: passed.
- One synthetic authenticated local live call with `SOC_LLM_MODEL=qwen3.5:9b` and `SOC_LLM_MODEL_DEVELOPER=llama3.1:8b`: login 200, suggestions 200 in 2.07 seconds, three steps, report remained `IN_PROGRESS|0`. The app used a temporary database, synthetic accounts and temporary local admission values. This single sample does not establish reliability, model quality or injection resistance. The provider was not instrumented to return its effective model name; routing is established by source inspection plus configuration tests, not provider response attribution.

## Limits and resources

No actual local `.env` was changed. Team model access, quotas, other roles' live behavior and deployment remain to be agreed. Per-agent token totals and provider usage for the application request were unavailable. No task was marked complete solely on these checks.

## Follow-up recheck

The focused configuration and Developer output-validation suites passed together. A second authenticated synthetic local request with shared Qwen and Developer-only Llama returned HTTP 200 in 2.33 seconds with three steps; report status/version stayed `IN_PROGRESS|0`. The suggestion about a Chrome "JavaScript development mode" is questionable and illustrates why output needs human review; it is not treated as a validated fact. This was an API-level check, not a browser or broader quality evaluation. The provider response did not identify the actual model; role selection remains supported by code inspection and configuration tests.

## Live suggestion and checklist HTTP journey

With shared Qwen and Developer-only Llama in a fresh temporary app/database, a synthetic Developer authenticated successfully and received three live suggestions (HTTP 200, 2.19 s). Immediately after generation, both the private draft and shared read were empty at revision/version 0. The test selected the returned steps programmatically, saved them as the private draft (HTTP 200, draft revision 1), and confirmed the shared read was still empty. Share then returned HTTP 200, report version 1, and the shared read contained the three steps. The final report remained `IN_PROGRESS` at version 1; the version change occurred after Share, not generation.

This was an authenticated HTTP journey using synthetic data and a live provider, not a browser interaction or a separate Reporter login. Apply was represented by selecting the returned steps in the test client; UI Apply is covered separately by mocked component tests. It does not establish model quality or reliability across cases. Temporary services and database were removed; no real credentials or provider response were recorded in this log.
