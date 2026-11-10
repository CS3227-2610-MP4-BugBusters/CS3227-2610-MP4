# Developer shared gateway integration — 10 November 2026

## Summary

Developer checklist suggestions now call the shared role gateway instead of registering the old direct `SOCLAAS_*` provider. The adapter supplies the server-authenticated Developer ID and task, preserves bounded report and saved-checklist context, validates output and credential reflection, and retains authorization and freshness checks. The shared gateway records admission against the Developer role. The Reporter owner still owns the gateway and needs to review the integration and confirm model, quotas and deployment settings.

## Coordination and findings

Starting revision `0fd812d`; the worktree was fast-forwarded from the clean prior Developer revision. A test designer showed that old settings registered the direct provider; an independent baseline verifier reproduced Red. The implementer added a gateway adapter and trusted actor propagation. An independent security reviewer identified the need to preserve credential-reflection protection and strict output envelope validation; both were addressed. The candidate verifier initially lacked Gradle filesystem/socket access, then independently reran focused tests with escalation. It identified missing evidence for real shared admission, so a test was added and independently rerun using PostgreSQL admission and a fake underlying provider.

## Verification and limits

- `./gradlew test --tests mp4.platform.SocLlmSuggestionProviderTest --tests mp4.platform.SocLlmRoleConfigurationTest --no-daemon --console=plain`: passed independently. Covers actor, role/task, context, failure mapping, credential reflection and configuration.
- Disposable PostgreSQL selected tests: 31 authenticated suggestion, input bounds, failure, logout-race and trailing-output tests passed according to implementer run.
- Independently reran `PostgresSocLlmAdmissionTest.successfulDeveloperSuggestionUsesRealRoleServiceAndDurableAdmission` and `SocLlmRoleConfigurationTest.validDeveloperRoleKeyWiresOnlyTheSharedGatewaySuggestionProvider`: passed. The first uses real shared role service and durable admission, a fake provider, and verifies actor, role, task, reservation and lease release.
- Security review passed by inspection. OpenSpec strict validation passed 13 items.
- Full `python3 src/backend/auth/test/run_setup.py`: passed against disposable PostgreSQL; JUnit XML reported 222 tests, 0 failures and 0 errors.
- Live gateway credentials, actual quotas, provider transport, deployed environment separation and full end-to-end browser flow remain unverified. Gateway currently returns text only; per-request provider token usage is unavailable through this adapter. No real database or credentials used in tests.

## Resources

Development-agent token, reasoning, cache and cost measurements unavailable; no savings claimed. The gateway reserves a bounded budget but a reservation is not measured actual token consumption.
