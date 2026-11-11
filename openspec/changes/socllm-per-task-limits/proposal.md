# Proposal

## Why

The MP4 SoCLaaS gateway applies one set of hard-coded limits to every role. Input
and instruction size, output tokens, deadline and per-account starts are constants
in `SocLlmRoleService`, `SocLlmHttpProvider`, `MaintainerAdmissionAdapter` and
`PostgresSocLlmAdmission`. Reporter and Developer assist tasks are about to get
consumers. They need their own limits without loosening the Maintainer summary's
limits or editing several classes for each change.

## What Changes

- Add a server-owned task policy for each `SocLlmContracts.Task`
  (`REPORTER_ASSIST`, `DEVELOPER_ASSIST`, `MAINTAINER_QUEUE_SUMMARY`) holding:
  max input bytes, max instruction bytes, max output tokens, max deadline,
  starts per rolling window per account, the window length, and max in-flight
  calls per account.
- The role service, admission store and Maintainer adapter read limits from the
  task's policy instead of shared constants.
- Per-account start and in-flight limits are counted by account + task instead of
  account + role. Each role owns exactly one task today, so this has no visible
  effect yet.
- The initial values for all three tasks equal today's values (24,576 input
  bytes, 8,192 instruction bytes, 2,048 output tokens, 30 s deadline, 3 starts per
  5 minutes, 1 in flight). **No observable limit changes in this change.**
- These stay per physical API key, unchanged: requests per minute
  (`SOC_LLM_RPM`), reserved tokens per minute (`SOC_LLM_TPM`), the 4-call
  concurrency cap, and the strictest-budget rule for keys shared by several roles.
- Document the three role keys (`SOC_LLM_API_KEY_REPORTER`,
  `SOC_LLM_API_KEY_DEVELOPER`, `SOC_LLM_API_KEY_MAINTAINER`) and the local,
  gitignored `.env` in the gateway README.

Out of scope: per-task `response_format` (stays `json_object` for every task),
Reporter/Developer endpoints and prompts, environment-configurable task values,
and any change to provider transport or key budgets. MP3's `AiTaskPolicy` is a
design reference only; no MP3 code is copied.

## Capabilities

### New Capabilities

- `socllm-task-limits`: Server-owned per-task limits for SoCLaaS gateway calls.
  Covers size, output, deadline and per-account admission limits, and how they
  combine with the existing per-key budgets.

### Modified Capabilities

None. `maintainer-queue-summary` (MNT-AI-RESOURCE) is still an in-flight change
and keeps its values. This change only moves where those values are defined.

## Impact

- Code: `src/backend/socllm` (`SocLlmContracts`, `SocLlmRoleService`,
  `SocLlmHttpProvider`, `MaintainerAdmissionAdapter`, `PostgresSocLlmAdmission`).
  Possibly `src/backend/platform/.../SocLlmConfiguration.java`, which repeats the
  24 KiB report bound.
- Tests: `src/backend/auth/src/test/java/mp4/socllm/*` and the platform
  configuration tests.
- Database: `mp4_socllm.attempt` already stores `task`. An additive V2 migration
  adds indexes keyed by task. No column or data change.
- Docs: `src/backend/socllm/README.md`.
- Security: this is an AI-security change because it alters admission limits, so it
  needs a separate `security_reviewer`.
