# Design

## Context

The checklist and real-session application are under review on dependent branches. The assigned report schema contains title, description, assignment and aggregate version. The Reporter-owned shared AI gateway and confirmed SoC quota are not yet present in this checkout. A bounded connectivity probe is recorded in `logs/developer/2026-11-09-soc-connectivity.md`.

## Goals / Non-Goals

**Goals:** A minimal end-to-end Developer suggestion flow; authorization and stale-source checks; bounded context, provider response and execution; sanitized measurements.

**Non-Goals:** Model-driven database actions, automatic save/share, response assessment, private notes as prompt context, deployment, or claiming shared quota enforcement from a local instance.

## Decisions

1. **Snapshot and recheck.** Resolve a trusted session actor, check active account/generation and current assignment, and read only report ID, title, description, report version and bounded saved checklist steps and draft revision in a short transaction. Do not hold database locks during the provider call. Afterward use a fresh persisted-session lookup (not the cached servlet session) to check session existence, expiry and identity, and recheck account/generation, assignment, version, draft revision and the title/description/checklist snapshot before returning suggestions. If anything differs, discard the result. Account locks precede report locks when both are needed. Test logout during a blocked provider call, as logout does not increment account generation. Assignment-away-and-back requires a shared monotonic version policy to detect the intervening event and remains an integration requirement.
2. **Advisory UI.** Suggestions appear separately from the editor; an explicit Apply action first rechecks the report and draft versions through the authenticated API, then copies them into the editable draft and does not persist. Report/session changes cancel or ignore old requests and clear previews. Save and Share remain the only persistence/publication actions. Generation never receives tools or database mutation capabilities.
3. **Provider boundary.** Use an interface with a deterministic test fake and a server-side SoC HTTP adapter; credentials are environment-only. Use the configured model or a documented application default. The adapter is a temporary local integration, not a replacement for the Reporter-owned shared gateway or a claim of cluster-wide budgets.
4. **Bounded data and output.** Use a fixed system instruction, report title/description and saved checklist steps, bounded before transmission; delimit report and checklist text as untrusted. Ask for a JSON list of plain-text steps. Reject malformed, empty, excessive or oversized output rather than showing raw provider text. Labels/instructions alone do not guarantee injection resistance; no tools or automatic actions are available.
5. **Resource controls.** Limit context and output tokens, apply the provider deadline to connection, headers and bounded streaming, and log only sanitized request outcome, durations and provider usage fields when present. A permit remains held until the underlying provider work has stopped, including after timeout or cancellation. These are application limits, not confirmed SoC quotas or shared multi-instance rate enforcement. No automatic retry is needed for the first slice.
6. **Testing and evaluation.** Use fake provider fixtures to verify authorized data selection, invalid outputs, prompt-injection requests, provider errors/timeouts, late reassignment/source changes and no domain writes. Freeze a rubric before bounded live SoC checks; record actual available token fields and failure outcomes without sensitive content.

## Risks / Trade-offs

- [Shared gateway and real quota not yet agreed] → isolate the provider interface and keep production release blocked until team integration and quota verification.
- [Advisory output may invent facts or follow malicious text] → validate shape and bounds, label as AI-generated, require human review, and give it no mutation tools.
- [A result may become stale] → recheck account, assignment and report version immediately before delivery; include the source version and do not apply a stale result.

## Migration Plan

No domain schema migration is assumed for the first slice. Supply separate development/production provider secrets through the team's environment configuration; disable generation safely when missing. Reconcile with the shared gateway and run real authentication, deployment and quota checks before release. Disable the endpoint or remove configuration to roll back.
