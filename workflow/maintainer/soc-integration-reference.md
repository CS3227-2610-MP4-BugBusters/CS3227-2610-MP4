# Maintainer SoC integration reference

## MP4 gateway port

The feature owner explicitly requested porting MP3's gateway and adapting it to
MP4's Reporter, Developer and Maintainer roles on 2026-11-10. The
[MP4 module](../../src/backend/socllm/README.md) replaces the inactive reference
import with MP4 packages, role configuration and durable admission. The existing
Maintainer `SummaryGateway` and `BudgetAndAdmission` ports connect queue summaries
to the shared module; no new Reporter or Developer feature endpoint is introduced.

Provider activation remains opt-in and requires trusted server configuration.
Role services retain their authorization, safe context and output validation.
Application token reservations are not confirmation of SoC total-token quota
accounting. Confirm the actual model, provider quotas and host connectivity before
enabling a deployed environment. See [port evidence](../../logs/socllm-port/approval.md)
for the owner approval and [Red review](../../logs/socllm-port/baseline-review.md)
for the preserved test baseline.

## Earlier reference investigation

The following records the earlier documentation-only investigation. Its statement
about copying no code describes that investigation, before the owner requested
the port above.

Checked MP4's current contracts first, then MP3's current documentation and
configuration for missing deployment/provider conventions. Read no MP3 application
implementation and copied no application code, migrations or tests into MP4.

Use the Reporter-owned shared gateway for the Maintainer summary feature. Keep the
role's context selection, prompt, output validation and delivery checks in the
Maintainer service. Keep shared provider transport, model routing and global quota
accounting behind its gateway port; do not create a second unrestricted provider
path to bypass admission.

## Verified public service contract

The current public SoCLaaS portal documents the fixed HTTPS chat endpoint
`https://soclaas-api.comp.nus.edu.sg/v1/chat/completions`, Bearer authentication,
JSON messages, and authenticated `GET /v1/models` model discovery. Select a
chat-capable model in trusted server configuration. Do not accept endpoint, key
or model selection from browser input, follow redirects or retry automatically.
Keep fixed summary instructions separate from the serialized untrusted report data.

MP3's reference configuration used separate role keys and explicitly local fake
mode. Its `qwen3.6:35b`, two-request/minute and 2,048-token trial settings are
historical application settings, not evidence of current availability or quotas.
Have not adopted them as provider limits. No key values are copied or published.

## Release boundary

MP4 defaults to unavailable AI when the shared provider/admission configuration is
absent. Permit deterministic fixtures only with explicit development/test opt-in;
never silently substitute them for live production output. Require bounded provider
transport, typed safe failures and durable admission from the shared gateway.
Maintainer per-account policy remains the existing three starts/five minutes,
one in-flight request and 30-second deadline, separately from provider quotas.

Have not verified a live MP4 key, current model catalogue, token accounting or
Render-to-provider connectivity. The detailed SoC documentation is network-restricted.
The public portal confirms endpoint shape, not those deployment facts. MP3's hosting
URLs and resources are references only; they are not MP4 environments.

## Sources checked

- MP3 `origin/main` at `8c45cff`: provider specification, Developer Guide,
  configuration and handoff documentation only.
- [Current public SoCLaaS portal](https://soclaas.comp.nus.edu.sg/).
- [Assignment's detailed SoC guide](https://dochub.comp.nus.edu.sg/cf/guides/soclaas/start),
  unavailable from the browsing tool during this check.
