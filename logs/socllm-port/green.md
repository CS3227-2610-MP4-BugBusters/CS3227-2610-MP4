# SoCLaaS MP4 port Green evidence

The SoCLaaS gateway is now implemented as an MP4-native backend module for the `REPORTER`, `DEVELOPER`, and `MAINTAINER` roles. The existing Maintainer Summary consumer uses the configured gateway and durable admission port; Reporter and Developer have role-bound shared services ready for their existing backend owners, with no new user-facing endpoints. PostgreSQL admission records count starts and conservative budget reservations across replicas, and active worker leases remain protective until the worker explicitly releases them. API keys are normalized once to the same canonical bearer value used for both HTTP transport and durable quota identity; invalid role keys leave that role unavailable without breaking startup. The repaired focused tests and relevant Maintainer regressions passed. A prior full backend run produced passing XML results, but its process exit code was not captured; the independent verifier is checking the repaired candidate. Live provider credentials, endpoint connectivity, deployed model availability, and provider-side quota semantics were not tested.

## Implementation and validation

- Task: `SOC-MP4-PORT`, OpenSpec `complete-maintainer-role`, tasks 4.4 and 5.1.
- Starting revision: `6bdba80519ed30996cf401f6b28dc60fb31f3e75`.
- Implementation: MP4-native shared transport, role routing, PostgreSQL quota/admission, renewable worker ownership, role-specific configuration, and Maintainer Summary adapter. The imported MP3 runtime/reference implementation was removed from the module; `import-manifest.json` remains as provenance only.
- Focused runner command, from the repository root, with `MP4_POSTGRES_BIN` set to the supplied disposable PostgreSQL 17 tools directory:

  ```powershell
  python src/backend/auth/test/run_setup.py --tests mp4.platform.MaintainerSocLlmConfigurationTest --tests mp4.platform.SocLlmRoleConfigurationTest --tests mp4.socllm.SocLlmHttpProviderTest --tests mp4.socllm.SocLlmRoleServiceTest --tests mp4.socllm.PostgresSocLlmAdmissionTest --tests mp4.maintainer.summary.QueueSummaryTest --tests mp4.maintainer.summary.SummarySetupTest --tests mp4.auth.MaintainerQueueSummaryIntegrationTest
  ```

- Result after Round 2 key-identity repair: exit code 0 from captured runner session `85914`; `BUILD SUCCESSFUL in 55s`. Eight JUnit suites ran 47 tests: 0 failures, 0 errors, 0 skipped. The disposable PostgreSQL runner initialized and cleaned up its temporary cluster. PostgreSQL tests covered multi-instance atomic limits, actor start counting, lease protection after the timestamp passes, divergent same-key RPM/TPM settings, padded/canonical aliases sharing request and token budgets plus the four-worker cap, ambient-transaction rollback protection, committed admission visibility to the provider worker, and zero-row renewal denial before a provider call.
- HTTP tests covered prompt-channel separation, redirect and retry behavior, safe failures and clamped retry hints, malformed output, request/response bounds, canonical padded-key header identity, and rejection of an invalid bearer token before network use. Role tests covered role/task isolation, provider-call prevention when admission is unavailable, normalization of padded role keys, and invalid optional-key startup behavior. The approved bean-configuration baseline passed unchanged.
- Approved baseline test SHA-256: `26AD66935CCE0B18AA6423B8E1F96E18E0A076AFDC0C791C5DBD5DBACB71EC70` (unchanged).
- Parent-provided disposable PostgreSQL 17 tool provenance and setup evidence: `logs/socllm-port/database-setup.md`.

## Review findings and fixes

The independent security review identified issues across two candidate rounds. Joining a caller-owned SQL transaction could roll back admission after a provider call, and separate replicas could apply weaker RPM/TPM limits to the same physical key. The implementation now rejects ambient Spring transactions at provider/admission entry points, commits admission independently, and fails closed unless lease renewal or release updates exactly one row. A PostgreSQL-backed rollback test confirms that a rejected call makes no provider request and leaves no attempted admission; the worker visibility and missing-lease tests exercise the commit and renewal boundaries.

Physical-key policy is now persisted by secret fingerprint and only tightens through the minimum configured RPM/TPM values, so a replica cannot silently raise an existing budget. A second security review found that padded keys could be sent by `HttpHeaders` as the same credential while hashing as different quota identities. The shared key validator now strips outer whitespace and rejects remaining characters outside the bearer-token alphabet; configuration checks, `KeyBudget`, role-service/provider constructors, and fingerprinting all use this canonical value. Tests confirm padded and unpadded forms produce the same Authorization header and share persisted request, token, and in-flight limits across instances. Invalid optional role keys are omitted without preventing application startup. Fingerprints and secrets are not emitted in logs or error responses. The parent is refreshing the final candidate manifest and arranging independent re-review and candidate verification; those checks are pending at the time this evidence was written.

## Remaining limits

Token budgets use conservative request-byte reservations and configured output limits. They do not measure provider tokenizer usage, actual billed tokens, or any unverified SoC provider quota. A worker lease remains protective even after its renewal timestamp expires; a crashed worker may therefore require operator recovery rather than automatic expiry-based re-admission. No real credentials or production environment were used, and no live provider calls were made. Independent candidate verification and the repaired-candidate security re-review remain pending.

## Historical full-suite evidence

Before the security repairs, the parent observed 26 JUnit XML suites with 133 tests, 0 failures, 0 errors, and 0 skipped. The original runner session handle was unavailable, so its process exit code could not be confirmed. This is historical evidence only and does not verify the repaired candidate; the independent candidate verifier is running the full check against the final snapshot.

After Round 1 repairs and before the Round 2 key-identity normalization, the parent reports the independent full backend runner exited 0 (`BUILD SUCCESSFUL in 4m15s`) and cleaned up its disposable PostgreSQL cluster. This full run predates Round 2. The 47-test targeted run above is the captured verification for the current key-identity change; an independent final-candidate review remains pending.
