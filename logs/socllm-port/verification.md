# MP4 SoCLLM port verification

The gateway is now MP4-native and uses Reporter, Developer and Maintainer role
routes with separate server keys. The existing Maintainer queue-summary consumer
is connected to the shared provider/admission boundary. Reporter and Developer
backend routes are available to their service owners; this port adds no new role
feature endpoints. The previous inactive MP3 implementation has been removed.

Independent verification passed. A full backend run passed 135 tests after the
transaction and shared-policy repairs. After the final credential-normalization
repair, the independent verifier reran all affected gateway/configuration/database
tests and the existing Maintainer summary unit/setup/API integrations: 47 tests
passed, with no failures, errors or skips. The approved Red test remained unchanged.
Independent security source review passed after two repair rounds, and redacted
secret and dependency checks passed.

Live SoC model compatibility, provider-token accounting, actual quotas, deployment
connectivity, grounding and operational recovery remain unverified. Live generation
stays unavailable by default. No environment secrets or deployment settings were
changed. Broad OpenSpec tasks remain open where their real-provider, owner or
deployment checks are still required; this record does not complete the whole
`complete-maintainer-role` change.

## Candidate identity and approvals

- Task: SOC-MP4-PORT; `complete-maintainer-role` tasks 4.4/5.1.
- Starting HEAD: `6bdba80519ed30996cf401f6b28dc60fb31f3e75`.
- Owner approval and reuse exception: [approval.md](approval.md).
- Independently verified Red: [baseline-review.md](baseline-review.md).
- Approved test SHA-256:
  `26AD66935CCE0B18AA6423B8E1F96E18E0A076AFDC0C791C5DBD5DBACB71EC70`.
- Final 21-file hashes: [candidate-manifest.json](candidate-manifest.json), identical
  to `candidate-round2-manifest.json`. Parent and independent verifier confirmed
  all hashes before/after the final targeted run.
- Initial and first-repair identities are preserved separately as
  `candidate-initial-manifest.json` and `candidate-round1-manifest.json`.

## Independent runtime checks

- Reviewer: fresh `/root/socllm_candidate`, named `candidate_verifier`, configured
  `gpt-6-luna`, reasoning effort `low`. No source/test/runner/log edits by reviewer.
- Full first-repair command from repository root, with task portable PostgreSQL
  tools supplied through `MP4_POSTGRES_BIN`:
  `python src/backend/auth/test/run_setup.py`.
- Captured exit 0, BUILD SUCCESSFUL in 4 minutes 15 seconds. 26 XML suites,
  135 tests, 0 failures, 0 errors, 0 skipped. This run corresponds to
  `candidate-round1-manifest.json`; final credential changes were verified below.
- Final command used the unchanged disposable runner with eight `--tests`
  selectors: `mp4.platform.MaintainerSocLlmConfigurationTest`,
  `mp4.platform.SocLlmRoleConfigurationTest`, `mp4.socllm.SocLlmHttpProviderTest`,
  `mp4.socllm.SocLlmRoleServiceTest`, `mp4.socllm.PostgresSocLlmAdmissionTest`,
  `mp4.maintainer.summary.QueueSummaryTest`,
  `mp4.maintainer.summary.SummarySetupTest`, and
  `mp4.auth.MaintainerQueueSummaryIntegrationTest`.
- Exact final command:
  `python src/backend/auth/test/run_setup.py --tests mp4.platform.MaintainerSocLlmConfigurationTest --tests mp4.platform.SocLlmRoleConfigurationTest --tests mp4.socllm.SocLlmHttpProviderTest --tests mp4.socllm.SocLlmRoleServiceTest --tests mp4.socllm.PostgresSocLlmAdmissionTest --tests mp4.maintainer.summary.QueueSummaryTest --tests mp4.maintainer.summary.SummarySetupTest --tests mp4.auth.MaintainerQueueSummaryIntegrationTest`.
- Captured exit 0, BUILD SUCCESSFUL in 50 seconds. 8 suites, 47 tests,
  0 failures/errors/skips. Counts by selector: 3, 2, 5, 2, 6, 21, 1, 7.
- The three approved baseline cases executed and passed. PostgreSQL cases verify
  cross-instance/role-alias budgets, every admitted start, non-expiring protection,
  unavailable-store denial, divergent persistent policies, ambient-transaction and
  missing-lease zero-call controls, active-key cap, and padded-key quota identity.
- Loopback tests verify bounded transport, safe failures and canonical header
  identity. No live credentials or provider calls were used. The portable runner
  created and cleaned up its own PostgreSQL fixture on each completed run.
- Final credential changes were isolated to key handling/configuration and their
  tests. The unchanged broader application regression is retained from the full
  run; the full suite was not redundantly repeated after that narrow patch.
- Implementer results and failed setup attempts: [green.md](green.md).
- One verifier attempt supplied an unsupported positional `.` to `run_setup.py`
  and exited at argument parsing before tests. The corrected commands above ran
  successfully; the runner and its settings were not edited.

## Security review and remaining boundaries

- Reviewer: separate `/root/socllm_security`, named `security_reviewer`, reported
  `gpt-6.1-sol`, reasoning effort `medium`; no edits, Gradle or live calls.
- Final source verdict PASS against all final 21 hashes, no further actionable
  findings. [Review and repairs](security-review.md) record transaction ownership,
  divergent shared-key policy, and padded-key identity findings and fixes.
- [Security checks](security-checks.md): pinned OSV found no issues in 90 resolved
  Maven dependencies or either npm lockfile; pinned redacted Gitleaks scans covered
  tracked and new files, including the exact final 21-file snapshot, with no leaks.
- `git diff --check` passed. No MP3 package/role/profile references remain in
  runtime or test Java/SQL; the import manifest retains historical provenance only.
- No frontend sources changed; frontend build commands were not required for this
  backend-only port. Live-provider/deployment checks were not attempted or claimed.
- A lost worker/process can leave a fail-closed reservation requiring operator
  recovery. Stored physical-key policy can only tighten; intentional budget raises
  require coordinated maintenance. See the module README for configuration.
- Agent token usage: unavailable. The work remains local and uncommitted.
