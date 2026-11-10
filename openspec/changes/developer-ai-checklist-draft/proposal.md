# Proposal

## Why

Developers need a quick starting point for investigating a programming bug while retaining control over the checklist and its publication.

## What Changes

- Let the currently assigned Developer request advisory investigation steps using the current report's bounded title and description.
- Require the Developer to review and explicitly apply suggestions before saving or sharing; generation never mutates report, checklist or lifecycle state.
- Use the configured SoC LLM through a server-side provider boundary, validate outputs, handle stale results and authorization changes, and record sanitized usage and limits.
- Verify safety, usefulness and resource-aware context selection with deterministic fixtures and bounded live SoC evaluation.

## Capabilities

### New Capabilities

- `developer-ai-checklist-draft`: Authorized, advisory generation and review of investigation steps.

### Modified Capabilities

None.

## Impact

Developer checklist UI, authenticated API, current report/account data, server-side SoC access and evaluation evidence. Developer suggestions use the Reporter-owned shared role gateway and its admission controls; actual quota, environment secrets and deployment still require release verification. Response assessment and automatic checklist publication are out of scope.
