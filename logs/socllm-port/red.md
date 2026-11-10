# SoCLaaS port Red baseline — configured wiring is missing

The new tests confirm that the existing Maintainer summary service still starts with AI disabled and with incomplete settings. With all explicit SoCLaaS settings present, Spring starts but does not register the required `SummaryGateway` or `BudgetAndAdmission` ports. That failing configuration assertion demonstrates the missing integration behavior. This Red test covers bean activation, shared admission wiring, and configured model selection only; provider transport, durable budgets, timeout lease behavior, PostgreSQL persistence, and deployment checks remain open.

## Technical evidence

- Task: `SOC-MP4-PORT`, OpenSpec `complete-maintainer-role`, tasks 4.4 and 5.1.
- Agent role: `test_designer`.
- Starting revision: `6bdba80519ed30996cf401f6b28dc60fb31f3e75`.
- Contract sources: `SummaryContracts.SummaryGateway`, `BudgetAndAdmission`, `AdmissionLease`; `workflow/maintainer/soc-integration-reference.md`; OpenSpec design, tasks, and queue-summary spec.
- Test path added: `src/backend/auth/src/test/java/mp4/platform/MaintainerSocLlmConfigurationTest.java`.
- Runner fixture: Spring `ApplicationContextRunner` with mocked `DataSource` and `JdbcIndexedSessionRepository`; no credentials or live requests. The project PostgreSQL `run_setup.py` path was not used for these context-only checks.
- Setup control command: `.\gradlew.bat --no-daemon test --tests mp4.maintainer.summary.SummarySetupTest` from `src/backend/auth`, run by the parent with sandbox escalation. Result: `BUILD SUCCESSFUL`, 5 tasks executed, 47 seconds.
- Focused Red command: `.\gradlew.bat --no-daemon test --tests mp4.platform.MaintainerSocLlmConfigurationTest` from `src/backend/auth`, using the pinned Gradle wrapper with sandbox escalation.
- Focused Red result: 3 tests completed, 2 passed, 1 failed, 0 errors, 0 skipped. Default-disabled startup/service availability passed. Enabled-but-incomplete startup remains available and both AI port types are absent. Complete explicit settings (`SOC_LLM_ENABLED=true`, placeholder key, `SOC_LLM_MODEL=contract-test-model`, `SOC_LLM_RPM=3`, `SOC_LLM_TPM=2048`) failed the grouped assertion because the expected single `SummaryGateway` and `BudgetAndAdmission` beans were not registered. No provider was invoked; model-selection assertion remains conditional until a gateway exists.
- Failure detail: JUnit grouped assertion at `MaintainerSocLlmConfigurationTest.java:53-55`; both expected bean counts were 1 but observed 0. The contexts started, and both assertions ran. This was not a compilation, fixture, or startup error.
- Test SHA-256 at the verified Red run: `26AD66935CCE0B18AA6423B8E1F96E18E0A076AFDC0C791C5DBD5DBACB71EC70`.
- Runner history and limits: `python src/backend/auth/test/run_setup.py --tests mp4.maintainer.summary.SummarySetupTest` initially exited before tests because `initdb` is not installed. A direct Gradle attempt first hit access denial in the default `C:\.gradle` cache, then a workspace cache attempt could not fetch Gradle because network access was denied. The parent ran the setup control with sandbox escalation and confirmed it passed. The context-only focused test also ran with sandbox escalation. PostgreSQL-backed candidate checks remain unavailable on this workstation unless PostgreSQL tooling is supplied.
- No application files or runner settings were changed. The SoCLaaS reference import was already staged before this task and remains untouched.
