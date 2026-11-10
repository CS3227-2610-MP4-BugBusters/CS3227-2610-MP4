# MP4 SoCLaaS gateway

This is the MP4-native shared SoCLaaS boundary. It provides role-bound Reporter,
Developer and Maintainer task routing, bounded HTTPS transport and one PostgreSQL
admission store shared by every configured role key and application instance.
The only current product consumer is the Maintainer queue summary. Reporter and
Developer services are backend ports for their owners; this module adds no new
role endpoints, prompts or user-facing AI features.

Live calls are unavailable by default. Configure `SOC_LLM_ENABLED=true`, a nonblank
`SOC_LLM_MODEL`, positive `SOC_LLM_RPM` and `SOC_LLM_TPM`, and the relevant
`SOC_LLM_API_KEY_REPORTER`, `SOC_LLM_API_KEY_DEVELOPER` or
`SOC_LLM_API_KEY_MAINTAINER`. A role without its own key has no service. A missing
Maintainer key leaves both the Maintainer summary gateway and its admission port
absent, while another configured role can remain available to its backend owner.
There is no fake or alternate provider fallback.

The provider destination is fixed to
`https://soclaas-api.comp.nus.edu.sg/v1/chat/completions`. The client disables
redirects and retries, bounds request/response bytes and applies a caller timeout
of at most 30 seconds. Trusted system instructions and untrusted task input use
separate messages. Failures use safe typed codes and clamp `Retry-After` to one hour.

Admission is serialized through PostgreSQL under `mp4_socllm`. It counts every
admitted start toward three starts per account and role in five minutes, applies
configured one-minute request and token-reservation budgets, and limits one active
worker per account/role plus four concurrent calls per physical API key. A SHA-256
fingerprint groups role aliases that share the same physical secret; their strictest
configured request/token budget applies. Each runtime registers its policy after the
SoCLaaS schema migration. PostgreSQL stores the minimum policy seen per key, so an
older or weaker instance cannot raise it on a later admission. Raising an intentional
budget requires a coordinated maintenance change to the private policy row while all
gateway instances are stopped. The database stores only that fingerprint,
actor/task metadata, reservation sizes and lease timestamps. A worker renews its
60-second lease while it runs and releases it only when it terminates. An overdue
timestamp never frees a non-null worker lease; a lost renewal or process shutdown
therefore fails closed and may leave an orphaned reservation requiring operator
recovery. Database errors fail closed before transport.

`SOC_LLM_TPM` is an application reservation budget, not a confirmed SoCLaaS quota.
Reservations use the maximum bounded UTF-8 input and instruction bytes plus
requested output tokens for Maintainer summaries, and actual bounded UTF-8 sizes
for other role tasks. They do not measure provider-tokenizer usage, hidden
reasoning, or actual provider billing.
Provider limits, selected model support, account credentials and deployment-host
connectivity remain unverified. The existing SoCLaaS migration runs after MP4's
shared/auth migrations in its own schema/history; no PostgreSQL work occurs while
the optional bean configuration is inspected in tests.

## Verification

Run the configuration and loopback tests with:

```powershell
cd src/backend/auth
.\gradlew.bat --no-daemon test --tests mp4.platform.MaintainerSocLlmConfigurationTest --tests mp4.platform.SocLlmRoleConfigurationTest --tests mp4.socllm.SocLlmHttpProviderTest --tests mp4.socllm.SocLlmRoleServiceTest
```

Run PostgreSQL quota tests with the disposable database runner:

```powershell
$env:MP4_POSTGRES_BIN = Join-Path $env:TEMP 'mp4-socllm-pg17-tools/pgsql/bin'
python src/backend/auth/test/run_setup.py --tests mp4.socllm.PostgresSocLlmAdmissionTest
```

These checks do not establish live-provider compatibility or production limits.

## Import provenance

The earlier inactive MP3 source import has been removed from runtime and test
source. [import-manifest.json](import-manifest.json) records its historical source
revision and checksums only; none of those files are copied byte-for-byte in the
MP4 implementation.
